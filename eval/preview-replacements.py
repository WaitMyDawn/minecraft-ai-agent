"""Serve the production candidate dialog with deterministic public metadata, without an application account."""
import argparse
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

STATIC = Path(__file__).resolve().parents[1] / "src/main/resources/static"
INDEX = (STATIC / "index.html").read_text(encoding="utf-8")
START = INDEX.index('    <div v-if="replacementPrompt"')
END = INDEX.index('    <!-- 顶部导航栏 -->', START)
OPTIONS = {
    "ticket": "fixture-ticket", "loader": "neoforge", "mcVersion": "1.21.1",
    "groups": [
        {"original": "alexs-mobs", "verified": True, "candidates": [
            {"slug": "alexs-mobs-1.21.1", "title": "Alex's Mobs (Unofficial Port)", "description": "Alex 生物的社区移植版，包含原模组的生物与内容。",
             "iconUrl": "", "downloads": 494079, "similarity": 100, "relation": "移植版"},
            {"slug": "alexs-mobs-continued", "title": "Alex's Mobs Continued", "description": "Alex 生物延续版，更新并移植到新版本。",
             "iconUrl": "", "downloads": 270476, "similarity": 100, "relation": "延续版"}]},
        {"original": "alexs-caves", "verified": True, "candidates": [
            {"slug": "alexs-caves-unofficial-port", "title": "Alex's Caves (Unofficial Port)", "description": "Alex 洞穴的社区移植版。",
             "iconUrl": "", "downloads": 343600, "similarity": 100, "relation": "移植版"}]}
    ]
}
ICON_METADATA = {}
HTML = ('<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<script src="https://cdn.tailwindcss.com"></script>'
        '<script type="importmap">{"imports":{"vue":"https://cdn.jsdelivr.net/npm/vue@3/dist/vue.esm-browser.prod.js"}}</script>'
        '</head><body class="bg-slate-100"><div id="app">' + INDEX[START:END] + '</div><pre id="result"></pre>'
        '<script type="module">import {createApp} from "vue";'
        'import {replacementPrompt, replacementLink, confirmReplacement, cancelReplacement, chooseReplacements} from "/js/features/chat.js";'
        'createApp({setup:()=>({replacementPrompt,replacementLink,confirmReplacement,cancelReplacement})}).mount("#app");'
        'chooseReplacements(' + json.dumps(OPTIONS, ensure_ascii=False) + ',new AbortController().signal)'
        '.then(result=>document.getElementById("result").textContent=JSON.stringify(result));</script></body></html>')


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/":
            content, mime = HTML.encode(), "text/html;charset=utf-8"
        elif self.path.startswith("/api/prefs/mod-info/"):
            from urllib.parse import unquote
            slug = unquote(self.path.rsplit("/", 1)[-1])
            content = json.dumps(ICON_METADATA.get(slug, {"error": "fixture metadata absent"})).encode()
            mime = "application/json"
        elif self.path.startswith("/js/"):
            path = (STATIC / self.path.lstrip("/")).resolve()
            if not path.is_relative_to(STATIC / "js") or not path.is_file():
                self.send_error(404)
                return
            content, mime = path.read_bytes(), "text/javascript;charset=utf-8"
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--mode", choices=["candidates", "report"], default="candidates")
    args = parser.parse_args()
    if args.mode == "report":
        metadata_file = Path("D:/tmpdir/Temp/maa-report-preview-icons.json")
        if metadata_file.is_file():
            ICON_METADATA = json.loads(metadata_file.read_text(encoding="utf-8-sig"))
        report = {"schemaVersion": 1, "total": 78, "categories": {"adventure": 26, "magic": 19, "library": 20, "未分类": 5},
                  "issues": [{"mod": "asterism-arcanum-addon-for-irons-spells-and-spellbooks", "dependency": "irons-spells-n-spellbooks",
                              "versionId": "Az9kyOso", "reason": "VERSION_CONFLICT", "dropped": False},
                             {"mod": "mebahels-api", "dependency": "", "versionId": "", "reason": "DEPENDENT_OF_DROPPED", "dropped": True}]}
        content = "已生成「铁魔法冒险整合包」，共 78 个模组文件（含前置），环境为 neoforge / 1.21.1。\n<build_report>" + json.dumps(report, ensure_ascii=False) + "</build_report>"
        HTML = ('<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                '<script src="https://cdn.tailwindcss.com"></script>'
                '<script type="importmap">{"imports":{"vue":"https://cdn.jsdelivr.net/npm/vue@3/dist/vue.esm-browser.prod.js"}}</script>'
                '</head><body class="bg-slate-100 p-4"><div id="app" class="max-w-3xl mx-auto bg-white p-5 rounded-xl">'
                '<p>已生成「铁魔法冒险整合包」，共 78 个模组文件（含前置），环境为 neoforge / 1.21.1。</p><build-report :content="content"></build-report></div>'
                '<script type="module">import {createApp} from "vue";import BuildReport from "/js/components/BuildReport.js";'
                'createApp({components:{BuildReport},setup:()=>({content:' + json.dumps(content, ensure_ascii=False) + '})}).mount("#app");</script></body></html>')
    print(f"replacement-preview pid={os.getpid()} port={args.port}", flush=True)
    HTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
