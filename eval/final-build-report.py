"""Publish readable evaluation evidence; raw captures stay outside final results."""
import argparse
import collections
import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]


def sanitize(text):
    text = re.sub(r"sk-[A-Za-z0-9_-]{8,}", "[REDACTED_KEY]", text)
    text = re.sub(r"(?i)Bearer\s+[A-Za-z0-9._~-]+", "Bearer [REDACTED]", text)
    return re.sub(r'(?i)("(?:password|api[-_]?key|token|authorization|x-auth-token)"\s*:\s*)"[^"\n]*"', r'\1"[REDACTED]"', text)


def read_json(path, default):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.is_file() else default


def tag(reply, name, default=""):
    match = re.search(fr"<{name}>(.*?)</{name}>", reply, re.S)
    return match[1] if match else default


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


def assess(nodes, complete=True):
    """Name matches locate evidence; they do not prove richness or add-on relations."""
    slugs = set(nodes)
    def find(*terms):
        return sorted(s for s in slugs if any(t in s if len(t) > 3 else t in s.split("-") for t in terms))
    def show(items):
        return "、".join(f"`{s}`（{nodes[s].get('title', nodes[s].get('label', s))}）" for s in items) or "未取得可核对项目"
    identities = {
        "Alex 生物": [s for s in find("alexs-mobs") if not any(t in s for t in ("delight", "music", "addon"))],
        "Alex 洞穴": find("alexs-caves"), "冰火传说": find("iceandfire", "ice-and-fire"),
        "灾变": ["l_enders-cataclysm"] if "l_enders-cataclysm" in slugs else [],
        "海灵物语": ["aquamirae"] if "aquamirae" in slugs else [],
        "yagens-attributes": ["yagens-attributes"] if "yagens-attributes" in slugs else [],
    }
    missing = [k for k, v in identities.items() if not v]
    utility_missing = [s for s in ("appleskin", "jei") if s not in slugs]
    return [
        ("1 铁魔法及附属", "部分证据，关系待核实", show(find("irons-spells", "spellbook", "spellbooks")), "核对核心及附属作用、数量和版本关系；名称匹配不证明充分性。"),
        ("2 六项点名项目", ("未满足" if complete else "未核实") if missing else "身份候选待核实", "；".join(f"{k}：{show(v)}" for k, v in identities.items()), ("缺少：" if complete else "未取得项目证据：") + "、".join(missing) if missing else "移植和平替身份须结合用户确认记录核对。"),
        ("3 农夫乐事与包内联动", "联动要求待核实", show(find("delight", "farmers-spell")), "需证明 A/附属/B 关系；核对灾变等包内核心，不能凭名称认定联动。"),
        ("4 群系与结构", "丰富度待人工评估", show(find("biome", "terralith", "structure", "dungeon", "tavern")), "分别核对群系与结构；数量不能证明丰富度，未点名的地形改造不作为额外硬性要求。"),
        ("5 生物生态", "生态覆盖待人工评估", show(find("mobs", "creature", "mowzie", "born-in-chaos", "aquamirae", "iceandfire")), "核对陆地、水域、敌对及中立生物的实际作用。"),
        ("6 下界与末地生态", "维度覆盖待核实", show(find("nether", "incendium", "end-", "enderscape", "nullscape")), "分别核对两维度群系、生物及结构，不由项目数量推断丰富度。"),
        ("7 优化、现代 UI 与修复", "三类目标分别待核实", show(find("sodium", "ferrite", "modernfix", "optimization", "performance", "fix", "ui", "hud", "tooltip")), "ModernFix 不等于现代 UI；核对用途、重复和冲突，未测量优化效果。"),
        ("8 实用模组（苹果皮、JEI）", ("示例项目缺失" if complete else "示例证据待核实") if utility_missing else "示例在包内，充分性待评", show(find("appleskin", "jei", "jade", "inventory", "tooltip", "mouse-tweaks")), ("示例缺少：" if complete else "未取得示例证据：") + "、".join(utility_missing) if utility_missing else "示例身份存在；其它实用能力待评。"),
    ]


def filtered_logs(work):
    users = sorted((work / "logs").rglob("user-*.log"))
    if len(users) > 1:
        raise ValueError("Capture must contain only the evaluated account's user log")
    # Per-user tee is canonical; stdout duplicates its business messages.
    paths = (users or [work / "logs/stdout.log"]) + [work / "logs/stderr.log"]
    sections, removed = [], 0
    for path in paths:
        if not path.is_file():
            continue
        kept = []
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if "[Modrinth HTTP]" in line or "[Modrinth task]" in line:
                removed += 1
            else:
                kept.append(line)
        sections.append(f"### {path.name}\n\n````text\n{sanitize(chr(10).join(kept))}\n````")
    return "\n\n".join(sections), removed


def receipt_summary(events, fallback_budget=None):
    """Summarize successful fact timing without publishing payloads or polling envelopes."""
    pending, totals, budgets = {}, {}, {}
    for event in events:
        path = event.get("path", "")
        if not path.endswith("/facts") or not event.get("time"):
            continue
        try:
            body = json.loads(event.get("body") or "{}")
        except json.JSONDecodeError:
            continue
        if not isinstance(body, dict):
            continue
        now = datetime.fromisoformat(event["time"])
        if event.get("event") == "response":
            if isinstance(body.get("perCallBudgetMs"), (int, float)):
                budgets[path] = body["perCallBudgetMs"]
            for want in body.get("wanted", []):
                pending.setdefault((path, want.get("kind"), want.get("key")), (now, budgets.get(path, fallback_budget)))
        if event.get("event") != "request":
            continue
        for answer in body.get("answered", []):
            identity = (path, answer.get("kind"), answer.get("key"))
            group = (path.split("/")[-2], answer.get("kind", "未知"))
            stats = totals.setdefault(group, {"answered": 0, "late": 0, "compared": 0, "maxMs": None, "budgets": set()})
            stats["answered"] += 1
            sent = pending.pop(identity, None)
            if sent:
                elapsed = (now - sent[0]).total_seconds() * 1000
                stats["maxMs"] = max(stats["maxMs"] or 0, elapsed)
                if isinstance(sent[1], (int, float)):
                    stats["budgets"].add(sent[1])
                    stats["compared"] += 1
                    stats["late"] += elapsed > sent[1]
    return totals


def stage_timings(events, trace):
    """Wall times may overlap; missing measurements remain unknown rather than estimated."""
    rows = []
    for name in ("architect", "critic"):
        agent = trace.get(name) or {}
        ms = agent.get("ms")
        rows.append((name, f"{ms / 1000:.3f}" if isinstance(ms, (int, float)) else "未采集", "最终回复 trace 的调用累计；可包含修复，不代表此前暂停轮次"))
    ms = trace.get("dependencyMs")
    rows.append(("依赖解析", f"{ms / 1000:.3f}" if isinstance(ms, (int, float)) else "未采集", "后端 trace 口径"))
    pending, measured = {}, collections.defaultdict(list)
    for event in events:
        if not event.get("time"):
            continue
        now = datetime.fromisoformat(event["time"])
        kind = event.get("event")
        if kind == "candidates":
            pending["用户确认等待"] = now
        elif kind == "choices" and "用户确认等待" in pending:
            measured["用户确认等待"].append((now - pending.pop("用户确认等待")).total_seconds())
        elif kind == "phase-start":
            pending[event.get("phase")] = now
        elif kind == "phase-end" and event.get("phase") in pending:
            phase = event["phase"]
            measured[phase].append((now - pending.pop(phase)).total_seconds())
    for phase, label in (("build-round", "构建请求累计"), ("用户确认等待", "用户确认等待"), ("preview", "预览"), ("export", "导出响应")):
        values = measured.get(phase)
        rows.append((label, f"{sum(values):.3f}" if values else "未采集", "浏览器/代理事件墙钟时间；多个区间累计"))
    start = next((e for e in events if e.get("event") == "request" and e.get("path") == "/api/chat" and e.get("time")), None)
    end = next((e for e in reversed(events) if e.get("event") in {"final", "failure"} and e.get("time")), None)
    total = (datetime.fromisoformat(end["time"]) - datetime.fromisoformat(start["time"])).total_seconds() if start and end else None
    rows.append(("总墙钟时间", f"{total:.3f}" if total is not None else "未采集", "包含确认等待、网络、预览及导出；不能与各阶段重复相加"))
    rows.append(("独立排队及检索耗时", "未采集", "尚无独立阶段计时，不由总时间减法推算"))
    return ["## 阶段耗时", "", "| 阶段 | 秒 | 口径 |", "| --- | ---: | --- |"] + ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]


def publish(work, output):
    work, output = Path(work).resolve(), Path(output).resolve()
    if work == output:
        raise ValueError("Capture and publication directories must differ")
    if output.exists() and any(output.iterdir()):
        raise FileExistsError("Publication directory must be empty; existing human assessments are preserved")
    protocol = work / "logs/protocol.jsonl"
    events = [json.loads(s) for s in protocol.read_text(encoding="utf-8").splitlines()] if protocol.is_file() else []
    result = read_json(work / "result/browser-result.json", {})
    reply = result.get("reply", next((e.get("reply", "") for e in reversed(events) if e.get("event") == "chat-final"), ""))
    preview_nodes = result.get("preview", {}).get("nodes", [])
    packs = list((work / "result").glob("*.mrpack"))
    if len(packs) > 1:
        raise ValueError("Ambiguous exported pack")
    pack = packs[0] if packs else None
    index, diagnosis, digest = {}, {}, ""
    if pack:
        with zipfile.ZipFile(pack) as archive:
            index = json.loads(archive.read("modrinth.index.json"))
            if "maa-diagnostics.json" in archive.namelist():
                diagnosis = json.loads(archive.read("maa-diagnostics.json"))
        digest = hashlib.sha256(pack.read_bytes()).hexdigest()
    exported_urls = {url for f in index.get("files", []) for url in f.get("downloads", [])}
    exported_hashes = {(k, v) for f in index.get("files", []) for k, v in f.get("hashes", {}).items()}
    nodes = {n["slug"]: n for n in preview_nodes
             if n.get("fileInfo", {}).get("url") in exported_urls
             or any((k, v) in exported_hashes for k, v in n.get("fileInfo", {}).get("hashes", {}).items())}
    download = result.get("download", {})
    verified = bool(pack and download.get("sha256") == digest and download.get("bytes") == pack.stat().st_size)
    count = len(index.get("files", []))
    input_text = (work / "input.md").read_text(encoding="utf-8") if (work / "input.md").is_file() else "未采集实际输入。"
    run = read_json(work / "run.json", {})
    stdout = work / "logs/stdout.log"
    tasks = {}
    if stdout.is_file():
        for line in stdout.read_text(encoding="utf-8", errors="replace").splitlines():
            if "[Modrinth task]" in line:
                t = dict(re.findall(r"(\w+)=([^\s]+)", line))
                tasks[t.get("taskId", str(len(tasks)))] = t
    browser = [e for e in events if e.get("event") == "browser-http"]
    http_details = [json.loads(e["detail"]) if isinstance(e.get("detail"), str) else e.get("detail", {}) for e in browser]
    statuses = collections.Counter(str(d.get("status", "unknown")) for d in http_details)
    business, removed = filtered_logs(work)
    closure = diagnosis.get("closure", {})
    issues = closure.get("issues", [])
    lines = ["# 真实需求构建记录", "", "## 实际输入与测试条件", "", input_text,
             "", "运行元数据（缺失字段不推断）：", "", "```json", json.dumps(run, ensure_ascii=False, indent=2), "```",
             "", "## 阶段与关键决定", ""]
    start = next((e for e in events if e.get("event") == "request" and e.get("path") == "/api/chat"), None)
    end = next((e for e in reversed(events) if e.get("event") in {"final", "failure"}), None)
    if start and end:
        duration = (datetime.fromisoformat(end["time"]) - datetime.fromisoformat(start["time"])).total_seconds()
        lines += [f"开始：{start['time']}；结束：{end['time']}；总耗时 {duration:.2f} 秒（包含用户确认等待）。", ""]
    for e in events:
        if e.get("event") in {"authentication", "candidates", "choices", "failure", "relay-error", "download"}:
            detail = {k: v for k, v in e.items() if k not in {"event", "time"}}
            lines += [f"### {e.get('time', '')} · {e['event']}", "", "```json", json.dumps(detail, ensure_ascii=False, indent=2), "```", ""]
    trace = json.loads(tag(reply, "trace", "{}"))
    timings = stage_timings(events, trace)
    lines += timings + [""]
    lines += ["## 构建阶段统计", "", "| Agent | 耗时 ms | 调用次数 | 输入/输出 token |", "| --- | ---: | ---: | --- |"]
    for name in ("architect", "critic"):
        a = trace.get(name) or {}
        lines.append(f"| {name} | {a.get('ms', '未知')} | {a.get('calls', '未知')} | {a.get('in', '未知')} / {a.get('out', '未知')} |")
    lines += ["", "补充构建统计：", "", "```json", json.dumps(trace, ensure_ascii=False, indent=2), "```",
              "", "## 网络汇总", "", f"浏览器实际 HTTP：{len(browser) if browser or run.get('browserHttpCapture') else '未采集'}；状态分布：{dict(statuses)}。",
              "事实复用次数与物理 HTTP 次数分开统计；未采集的服务器计数标记未知。",
              "", "| 任务 | 浏览器 HTTP | 服务端 HTTP | 兜底事实 | 服务端 429 | 等待下发超时 | 浏览器超时 | 明确交还 |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for task, t in tasks.items():
        lines.append("| " + " | ".join(cell(v) for v in [task, *(t.get(k, "未知") for k in ("browserHttp", "serverHttp", "fallbackFacts", "server429", "queueTimeout", "browserTimeout", "handBack"))]) + " |")
    if not tasks:
        lines.append("| 未采集服务器计数 | 未知 | 未知 | 未知 | 未知 | 未知 | 未知 | 未知 |")
    anomalies = collections.Counter((str(d.get("status", "未知")), d.get("error", ""))
                                    for d in http_details if d.get("error") or str(d.get("status", "0")).startswith(("4", "5")))
    lines += ["", f"异常汇总（状态/错误类型 → 次数）：{dict(anomalies)}。"]
    receipts = receipt_summary(events, run.get("delegationPerCallBudgetMs"))
    lines += ["", "事实回执时间汇总（从首次下发到提交，包含浏览器排队和整批回传等待）：", "",
              "| 任务 | 类型 | 成功回答 | 超预算回答 | 最大耗时 ms | 下发预算 ms |", "| --- | --- | ---: | ---: | ---: | --- |"]
    for (task, kind), stats in receipts.items():
        lines.append("| " + " | ".join(cell(v) for v in [task, kind, stats["answered"],
                     f"{stats['late']}（已核对 {stats['compared']} 项）" if stats["compared"] else "未知",
                     round(stats["maxMs"], 2) if stats["maxMs"] is not None else "未知", sorted(stats["budgets"]) or "未知"]) + " |")
    if any(s["late"] for s in receipts.values()):
        lines += ["", "发现成功回执超过委派预算；如相同任务还有服务端兜底，需优先检查批量回传和预算竞争，不能将其直接归因为网络不可用。"]
    lines += ["", "兜底原因需结合异常与关键时间线核查；仅凭计数不能认定网络故障。",
              "", "## 导出及静态依赖", "", f"文件数：{count if pack else '未导出'}；名称：{index.get('name', '未知')}；环境：{index.get('dependencies', {})}。",
              f"SHA-256：{digest or '无'}；浏览器响应与落盘字节/哈希一致：{verified}。",
              f"静态状态：{closure.get('status', '未采集')}，{len(issues)} 项诊断。未运行 MAA-Checker，不声明可启动。",
              f"导出项目证据匹配：{len(nodes)} / {count} 个文件；未匹配项不用于声称内容满足。",
              "", "```json", json.dumps(diagnosis, ensure_ascii=False, indent=2), "```",
              "", "## 实际项目及版本", "", "| slug | 名称 | 版本 | 项目说明（上游元数据） |", "| --- | --- | --- | --- |"]
    for slug, n in sorted(nodes.items()):
        lines.append("| " + " | ".join(cell(v) for v in [slug, n.get("label", n.get("title", slug)), n.get("version", n.get("versionNumber", "未知")), n.get("description", "未采集")]) + " |")
    lines += ["", "## 构建回复", "", "````text", reply, "````", "", "## 业务决策与 Agent 输入输出", "", business or "未采集业务及 Agent 日志。",
              "", "## 采集范围", "", f"过滤逐请求 HTTP/计数行 {removed} 条；不展开轮询、排队/运行信封及浏览器控制台 JSON。重复 stdout/tee 业务记录只选一个来源。",
              f"Agent I/O：{'已采集' if '[Agent input]' in business and '[Agent output]' in business else '采集不完整'}；认证字段脱敏。原始中间文件不发布。"]
    assessment = ["# 自动评估", "", "以下依据实际清单与采集记录；名称匹配仅辅助定位证据，不证明联动关系或游戏体验。",
                  "", f"包名：{index.get('name', '未知')}；环境：{index.get('dependencies', {})}。",
                  "", f"文件数：{count if pack else '未导出'}；100～200 区间：{'满足' if pack and 100 <= count <= 200 else '未满足或无法判断'}。",
                  f"静态依赖：{closure.get('status', '未采集')}，{len(issues)} 项诊断；下载核对：{verified}。",
                  f"最终清单可对应项目证据：{len(nodes)} / {count}；缺少映射不等于模组不存在。",
                  f"第八项输入：{'已记录' if '8）' in input_text else '未在输入中确认，不能作为本轮已测试要求'}。",
                  "", "| 要求 | 自动判断 | 实际项目证据 | 缺口与核对事项 |", "| --- | --- | --- | --- |"]
    rows = assess(nodes, bool(pack) and len(nodes) == count)
    if not pack:
        rows = [(row[0], "未评估（未导出）", "无最终清单", "构建或导出未完成，不能据此判断内容是否满足。") for row in rows]
    assessment += ["| " + " | ".join(cell(v) for v in row) + " |" for row in rows]
    assessment += [""] + timings + ["", "## 网络与采集概况", "", f"浏览器 HTTP：{len(browser) if browser or run.get('browserHttpCapture') else '未采集'}；浏览器状态：{dict(statuses)}。",
                   f"服务端 HTTP：{sum(int(t.get('serverHttp', 0)) for t in tasks.values()) if tasks and all('serverHttp' in t for t in tasks.values()) else '未采集'}；"
                   f"兜底事实：{sum(int(t.get('fallbackFacts', 0)) for t in tasks.values()) if tasks and all('fallbackFacts' in t for t in tasks.values()) else '未采集'}。",
                   f"成功回执超预算：{sum(s['late'] for s in receipts.values()) if any(s['compared'] for s in receipts.values()) else '未采集预算，无法判断'}"
                   f"（已核对 {sum(s['compared'] for s in receipts.values())} 项）；原因见 log.md 的回执时间与异常汇总。",
                   "", "未进行安装及启动检查。待核实项不能按通过处理；优化效果和内容丰富度待人工评估。", "", "# 人工评估", "", "请在此追加评价、修正及新的要求。", ""]
    render_scope = "完整生产页面，经本机评测代理采集；截图的可见范围和展开状态见 chat-output.md。" if run.get("renderScope") == "production-site" else "评测代理复用生产 Markdown 与 BuildReport 组件，不包含网站完整页面布局。"
    evidence = ["", "## 对话显示采集", "", "渲染范围：" + render_scope,
                f"可见文本：{'chat-output.md' if (work / 'result/chat-output.md').is_file() else '未采集'}；"
                f"浏览器截图：{'chat-output.png' if (work / 'result/chat-output.png').is_file() else '未采集，不能据原始协议判断显示质量'}。"]
    human = assessment.index("# 人工评估")
    assessment[human:human] = evidence + [""]
    output.mkdir(parents=True, exist_ok=True)
    (output / "log.md").write_text(sanitize("\n".join(lines)), encoding="utf-8")
    (output / "eval.md").write_text(sanitize("\n".join(assessment)), encoding="utf-8")
    if pack:
        (output / pack.name).write_bytes(pack.read_bytes())
    # Visible text and browser screenshots must be captured from rendered UI, never synthesized from protocol tags.
    for name in ("chat-output.md", "chat-output.png"):
        source = work / "result" / name
        if source.is_file():
            if name.endswith(".md"):
                (output / name).write_text(sanitize(source.read_text(encoding="utf-8")), encoding="utf-8")
            else:
                if not source.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError("Chat screenshot must be an actual PNG")
                (output / name).write_bytes(source.read_bytes())
    return {"files": count, "exported": bool(pack), "sha256Matches": verified, "logBytes": (output / "log.md").stat().st_size}


def cleanup_capture(work, output):
    work, output = Path(work).resolve(), Path(output).resolve()
    temporary_root = Path(tempfile.gettempdir()).resolve()
    metadata = read_json(work / "run.json", {})
    if (not work.is_relative_to(temporary_root) or work == temporary_root or not work.name.startswith("maa-final-")
            or metadata.get("captureKind") != "final-build-session"
            or metadata.get("captureDirectory") != str(work)
            or metadata.get("publicationDirectory") != str(output)):
        raise ValueError("Refusing to remove an unowned capture directory")
    shutil.rmtree(work)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir", help="Empty final publication directory")
    parser.add_argument("--work-dir", required=True, help="Temporary sanitized capture directory")
    parser.add_argument("--cleanup-work-dir", action="store_true", help="Remove owned temporary capture after publication; stop collectors first")
    args = parser.parse_args()
    output = Path(args.run_dir).resolve()
    if not output.is_relative_to(PROJECT / "eval/results/final") or output == PROJECT / "eval/results/final":
        raise ValueError("Invalid publication directory")
    print(json.dumps(publish(args.work_dir, output), ensure_ascii=False))
    if args.cleanup_work_dir:
        cleanup_capture(args.work_dir, output)
