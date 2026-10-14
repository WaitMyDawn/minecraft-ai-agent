"""Loopback-only browser evaluation relay. Credentials remain in memory and are excluded from artifacts."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "src/main/resources/static"
STATE = {"token": "", "key": "", "secrets": []}
LOCK = threading.Lock()


def clean(value):
    if isinstance(value, dict):
        return {k: "[REDACTED]" if k.lower() in {"token", "password", "apikey", "key", "authorization"} else clean(v) for k, v in value.items()}
    if isinstance(value, list):
        return [clean(v) for v in value]
    if isinstance(value, str):
        for secret in STATE["secrets"]:
            if secret:
                value = value.replace(secret, "[REDACTED]")
        return re.sub(r"sk-[A-Za-z0-9_-]{8,}", "[REDACTED_KEY]", value)
    return value


def record(event):
    with LOCK:
        with (RUN / "logs/protocol.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(clean({"time": datetime.now(timezone.utc).isoformat(), **event}), ensure_ascii=False) + "\n")


def upstream(path, method, body, headers=None):
    request = urllib.request.Request(BASE + path, data=body, method=method, headers=headers or {})
    try:
        response = urllib.request.urlopen(request, timeout=320)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, dict(response.headers), response.read()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.dispatch()

    def do_POST(self):
        self.dispatch()

    def dispatch(self):
        origin = self.headers.get("Origin", "")
        if origin and origin != f"http://127.0.0.1:{PORT}":
            self.send_error(403)
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        if self.path == "/configure" and self.command == "POST":
            configuration = json.loads(body)
            STATE["key"] = configuration["key"]
            STATE["secrets"] = [configuration["key"], configuration["password"]]
            status, _, data = upstream("/api/user/login", "POST", json.dumps({"account": configuration["account"], "password": configuration["password"]}).encode(), {"Content-Type": "application/json"})
            login = json.loads(data)
            STATE["token"] = login.get("token", "")
            STATE["secrets"].append(STATE["token"])
            record({"event": "authentication", "status": status, "account": configuration["account"], "success": bool(STATE["token"])})
            self.reply(200, json.dumps({"ready": bool(STATE["token"]), "status": status}).encode(), "application/json")
        elif self.path == "/capture" and self.command == "POST":
            event = json.loads(body)
            record(event)
            if event.get("event") == "chat-visible":
                (RUN / "result/chat-output.md").write_text(clean(event.get("content", "")), encoding="utf-8")
            if event.get("event") == "final":
                (RUN / "result/browser-result.json").write_text(json.dumps(clean(event), ensure_ascii=False, indent=2), encoding="utf-8")
            self.reply(200, b"{}", "application/json")
        elif self.path == "/":
            self.reply(200, PAGE.encode(), "text/html;charset=utf-8")
        elif self.path.startswith("/js/"):
            path = (STATIC / self.path.lstrip("/")).resolve()
            if not path.is_relative_to(STATIC / "js") or not path.is_file():
                self.send_error(404)
                return
            self.reply(200, path.read_bytes(), "text/javascript;charset=utf-8")
        elif self.path.startswith("/api/"):
            if not STATE["token"]:
                self.reply(401, b'{"error":"test account not configured"}', "application/json")
                return
            headers = {"Content-Type": self.headers.get("Content-Type", "application/json"), "X-Auth-Token": STATE["token"]}
            if self.path == "/api/chat":
                headers["X-LLM-Api-Key"] = STATE["key"]
            record({"event": "request", "path": self.path, "method": self.command, "body": body.decode("utf-8", errors="replace")})
            try:
                status, response_headers, data = upstream(self.path, self.command, body if self.command == "POST" else None, headers)
                mime = response_headers.get("Content-Type", "application/octet-stream")
                binary = self.path == "/api/modpack/build" and status == 200 and "json" not in mime and "text" not in mime
                if binary:
                    (RUN / "result/朝花夕拾.mrpack").write_bytes(data)
                    record({"event": "download", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
                else:
                    record({"event": "response", "path": self.path, "status": status, "body": data.decode("utf-8", errors="replace")})
                self.reply(status, data, mime)
            except Exception as error:
                record({"event": "relay-error", "kind": type(error).__name__})
                self.reply(502, b'{"error":"evaluation upstream unavailable"}', "application/json")
        else:
            self.send_error(404)

    def reply(self, status, body, mime):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", required=True)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--app-port", type=int, default=8781)
    parser.add_argument("--runtime-jar", type=Path, help="Exact jar started for this evaluation")
    parser.add_argument("--delegation-budget-ms", type=int, help="Verified runtime per-call delegation budget; omit if unknown")
    args = parser.parse_args()
    if args.delegation_budget_ms is not None and args.delegation_budget_ms <= 0:
        parser.error("Delegation budget must be positive")
    FINAL = Path(args.run_dir).resolve()
    if not FINAL.is_relative_to(ROOT / "eval/results/final") or FINAL == ROOT / "eval/results/final":
        raise ValueError("Evaluation directory must be below eval/results/final")
    if FINAL.exists() and any(FINAL.iterdir()):
        raise ValueError("Existing evaluation directory cannot be reused")
    RUN = Path(tempfile.mkdtemp(prefix="maa-final-")).resolve()
    for directory in ("logs", "result"):
        (RUN / directory).mkdir(parents=True, exist_ok=True)
    BASE, PORT = f"http://127.0.0.1:{args.app_port}", args.port
    prompt = (ROOT / "docs/final-build-evaluation.md").read_text(encoding="utf-8").split("```text\n", 1)[1].split("```", 1)[0].strip()
    index = (STATIC / "index.html").read_text(encoding="utf-8")
    modal = index[index.index('    <div v-if="replacementPrompt"'):index.index('    <!-- 顶部导航栏 -->')]
    PAGE = '<!doctype html><meta charset="utf-8"><script src="https://cdn.tailwindcss.com"></script><script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script><script src="https://cdn.jsdelivr.net/npm/dompurify/dist/purify.min.js"></script><script type="importmap">{"imports":{"vue":"https://cdn.jsdelivr.net/npm/vue@3/dist/vue.esm-browser.prod.js"}}</script><div id="app" class="p-6"><button @click="run" :disabled="busy || started" class="bg-indigo-600 text-white p-3 rounded">开始朝花夕拾基线测试</button><p class="mt-3">{{status}}</p><article id="chat-output" class="max-w-4xl mx-auto mt-6 bg-white border rounded-xl p-6"><div v-html="renderMarkdownSafe(cleanChatReply(reply))"></div><build-report :content="cleanChatReply(reply)"></build-report></article>' + modal + '</div><script type="module">'
    PAGE += 'import {createApp,ref,nextTick} from "vue";import BuildReport from "/js/components/BuildReport.js";import {renderMarkdownSafe,cleanChatReply} from "/js/utils/format.js";import {apiFetch} from "/js/api.js";import {authUser} from "/js/store.js";import {chooseReplacements,replacementPrompt,replacementLink,confirmReplacement,cancelReplacement,syncReplacementSelection} from "/js/features/chat.js";'
    PAGE += 'const input=' + json.dumps(prompt, ensure_ascii=False) + ';const capture=e=>fetch("/capture",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(e)});const info=console.info;console.info=(...args)=>{info(...args);if(args[0]==="[Modrinth HTTP]")capture({event:"browser-http",detail:args[1]});};'
    PAGE += 'createApp({components:{BuildReport},setup(){const busy=ref(false),started=ref(false),status=ref("等待开始"),reply=ref("");const run=async()=>{if(started.value)return;started.value=true;busy.value=true;authUser.value={token:"relay-session"};const signal=new AbortController().signal;const body={prompt:input,currentMods:"",uuid:"final-' + FINAL.name + '",loader:"neoforge",mcVersion:"1.21.1",constraints:"",importedPack:"",excludedSlugs:""};try{status.value="浏览器取数与构建中";await capture({event:"phase-start",phase:"build-round"});let response=await apiFetch("/api/chat",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});let text=await response.text();await capture({event:"phase-end",phase:"build-round"});for(let round=0;round<5;round++){const match=text.match(/<replacement_options>([\\s\\S]*?)<\\/replacement_options>/);if(!match)break;const options=JSON.parse(match[1]);await capture({event:"candidates",options});status.value="等待选择兼容候选";const choices=await chooseReplacements(options,signal);if(!choices)throw new Error("候选选择已取消");await capture({event:"choices",choices});status.value="续建中";await capture({event:"phase-start",phase:"build-round"});response=await apiFetch("/api/chat",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({...body,selectionKind:options.kind||"REPLACEMENT",replacementTicket:options.ticket,replacementChoices:JSON.stringify(choices)})});text=await response.text();await capture({event:"phase-end",phase:"build-round"});}reply.value=text;await nextTick();await capture({event:"chat-visible",content:document.getElementById("chat-output").innerText,renderer:"production-markdown-build-report",scope:"evaluation-relay"});await capture({event:"chat-final",reply:text});const mods=text.match(/<mods>(.*?)<\\/mods>/s);if(!mods)throw new Error("未产生可导出的清单");const slugs=mods[1].split(",").filter(Boolean);const name=(text.match(/<name>(.*?)<\\/name>/s)||[])[1]||"朝花夕拾";status.value="预览与导出中";const trace=JSON.parse(text.split("<trace>")[1]?.split("</trace>")[0]||"{}");const pack={name,loader:"neoforge",mcVersion:"1.21.1",modSlugs:slugs,excludedSlugs:[],assembly:trace.assembly||null};await capture({event:"phase-start",phase:"preview"});const preview=await(await apiFetch("/api/modpack/preview",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(pack)})).json();await capture({event:"phase-end",phase:"preview"});if(preview.assembly)pack.assembly=preview.assembly;await capture({event:"phase-start",phase:"export"});const downloaded=await apiFetch("/api/modpack/build",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({...pack,manifestId:preview.manifestId,selectedIds:preview.nodes.map(n=>n.id),allowIncomplete:true})});await capture({event:"phase-end",phase:"export"});if(!downloaded.ok)throw new Error("导出失败");const bytes=await downloaded.arrayBuffer();const sha256=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",bytes))).map(v=>v.toString(16).padStart(2,"0")).join("");await capture({event:"final",reply:text,preview,download:{bytes:bytes.byteLength,sha256}});status.value="测试完成，实际清单已保存";}catch(e){status.value="测试失败："+e.message;await capture({event:"failure",message:e.message,reply:reply.value});}finally{busy.value=false;}};return{renderMarkdownSafe,cleanChatReply,busy,started,status,reply,run,replacementPrompt,replacementLink,confirmReplacement,cancelReplacement,syncReplacementSelection};}}).mount("#app");</script>'
    (RUN / "input.md").write_text("# 实际输入\n\n" + prompt + "\n\n测试方式：真实浏览器使用生产取数器；本机评测代理持有账号令牌，认证与模型 key 不写入产物。\n", encoding="utf-8")
    git_head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    metadata = {"captureKind": "final-build-session", "captureDirectory": str(RUN), "publicationDirectory": str(FINAL),
                "gitHead": git_head, "startedAt": datetime.now(timezone.utc).isoformat(),
                "appPort": args.app_port, "relayPort": PORT, "relayPid": os.getpid(), "browserHttpCapture": True}
    if args.runtime_jar:
        metadata["jarSha256"] = hashlib.sha256(args.runtime_jar.read_bytes()).hexdigest()
    if args.delegation_budget_ms is not None:
        metadata["delegationPerCallBudgetMs"] = args.delegation_budget_ms
    (RUN / "run.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"final-eval-relay pid={os.getpid()} port={PORT} final={FINAL} work={RUN}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
