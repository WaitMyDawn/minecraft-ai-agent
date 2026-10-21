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


def task_summaries(events, stdout):
    task_ids = set()
    for event in events:
        match = re.search(r"/api/(?:chat/task|operations)/([^/?]+)", event.get("path", ""))
        if match:
            task_ids.add(match[1])
        if event.get("event") == "response":
            try:
                body = json.loads(event.get("body", "{}"))
                if isinstance(body, dict) and isinstance(body.get("taskId"), str):
                    task_ids.add(body["taskId"])
            except (TypeError, ValueError):
                pass
    tasks = {}
    for line in stdout.splitlines():
        if "[Modrinth task]" not in line:
            continue
        task = dict(re.findall(r"(\w+)=([^\s]+)", line))
        if task.get("taskId") in task_ids:
            tasks[task["taskId"]] = task
    return tasks


def tag(reply, name, default=""):
    match = re.search(fr"<{name}>(.*?)</{name}>", reply, re.S)
    return match[1] if match else default


def cell(value):
    return str(value).replace("|", "\\|").replace("\n", " ")


OUTCOMES = {"MET": "已满足", "PARTIAL": "部分满足", "NOT_MET": "未满足", "UNKNOWN": "暂无法核实", "WAITING": "待选择", "CONTEXT": "上下文"}


def json_tag(reply, name):
    try:
        value = json.loads(tag(reply, name, "{}"))
        return value if isinstance(value, dict) else {}
    except (ValueError, TypeError):
        return {}


def artifact_check(index, preview, trace, reply, exported):
    """Verify exported references against final pins; missing proof is not a confirmed mismatch."""
    if not exported:
        return {"status": "NOT_EXPORTED", "issues": ["未导出包"], "nodes": {}}
    files, candidates = index.get("files", []), preview.get("nodes", [])
    issues, unknown, mapped, used = [], [], {}, set()
    if not isinstance(files, list) or not isinstance(candidates, list) or len(files) > 500 or len(candidates) > 500 or not all(isinstance(v, dict) for v in files + candidates):
        return {"status": "MISMATCH", "issues": ["文件或预览结构不合法"], "nodes": {}}
    paths = [f.get("path") for f in files]
    if any(not isinstance(v, str) for v in paths) or len(set(str(v).casefold() for v in paths)) != len(paths):
        issues.append("导出路径缺失或重复")
    if "nodes" not in preview:
        unknown.append("未采集预览节点")
    elif len(candidates) != len(files):
        issues.append("导出和预览的文件数不一致")
    def hashes(info):
        values = info.get("hashes") or {}
        if not isinstance(values, dict):
            return {}
        if any(k in values and (not isinstance(values[k], str) or not re.fullmatch(r"[a-fA-F0-9]{" + str(128 if k == "sha512" else 40) + r"}", values[k])) for k in ("sha512", "sha1")):
            return {}
        return {k: v.lower() for k, v in values.items() if k in {"sha512", "sha1"}
                and isinstance(v, str) and re.fullmatch(r"[a-fA-F0-9]{" + str(128 if k == "sha512" else 40) + r"}", v)}
    for file in files:
        matches, comparable = [], False
        for position, node in enumerate(candidates):
            info = node.get("fileInfo") or {}
            wanted, actual = hashes(file), hashes(info)
            common = wanted.keys() & actual.keys()
            comparable |= bool(common)
            if not common or any(wanted[k] != actual[k] for k in common):
                continue
            if isinstance(file.get("fileSize"), int) and isinstance(info.get("size"), int) and file["fileSize"] != info["size"]:
                continue
            matches.append((position, node))
        if len(matches) != 1:
            (issues if comparable or matches else unknown).append(f"{file.get('path', '未知文件')}：哈希/大小无法唯一对应预览")
            continue
        position, node = matches[0]
        slug = node.get("slug")
        if position in used or slug in mapped or not isinstance(slug, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", slug):
            issues.append("预览节点重复映射或项目身份无效")
            continue
        used.add(position)
        mapped[slug] = node
    assembly = trace.get("assembly") or {}
    pins = assembly.get("versions")
    if not isinstance(pins, dict):
        unknown.append("缺少最终构建选版快照")
    else:
        if len(mapped) == len(files) and set(pins) != set(mapped):
            issues.append("导出与最终构建快照的项目集合不一致")
        for slug, node in mapped.items():
            if not node.get("versionId"):
                unknown.append(slug + "：未采集精确版本")
            elif node["versionId"] != pins.get(slug):
                issues.append("预览的精确版本与构建快照不一致")
        preview_pins = (preview.get("assembly") or {}).get("versions")
        if preview_pins is not None and preview_pins != pins:
            issues.append("预览快照与构建快照的版本映射不一致")
        declared = tag(reply, "mods")
        if not re.search(r"<mods>.*?</mods>", reply, re.S):
            unknown.append("未采集最终回复清单")
        elif set(filter(None, declared.split(","))) != set(pins):
            issues.append("最终回复清单与构建快照不一致")
        dependencies = index.get("dependencies") or {}
        loader = {"fabric": "fabric-loader", "neoforge": "neoforge", "forge": "forge"}.get(assembly.get("loader"))
        if not loader or not dependencies.get("minecraft") or not assembly.get("mcVersion"):
            unknown.append("未采集完整环境字段")
        elif not dependencies.get(loader) or assembly["mcVersion"] != dependencies["minecraft"]:
            issues.append("导出环境与构建快照不一致")
    return {"status": "MISMATCH" if issues else "UNVERIFIED" if unknown else "VERIFIED", "issues": issues + unknown, "nodes": mapped}


def evidence_snapshot_issues(coverage, assembly):
    pins = assembly.get("versions")
    if not isinstance(pins, dict):
        return []
    issues = []
    for proof in coverage.get("ledger", [])[:4096]:
        if proof.get("status") == "VERIFIED" and (pins.get(proof.get("slug")) != proof.get("versionId")
                or not set(proof.get("partners", [])).issubset(pins)):
            issues.append("有效证据的项目、选版或关联对象与最终快照不一致")
            break
    for parent in coverage.get("requirements", [])[:64]:
        for row in [parent, *parent.get("criteria", [])[:16]]:
            q = row.get("quantity") or {}
            projects = q.get("projects", [])
            if q and (q.get("actual") != len(projects) or len(set(projects)) != len(projects)
                      or not set(projects).issubset(pins)
                      or isinstance(assembly.get("roots"), list) and not set(projects).issubset(assembly["roots"])):
                issues.append("局部计数与最终根项目快照不一致")
                break
    return issues


def valid_assessment(value, status=None):
    def projects(items):
        return isinstance(items, list) and len(items) <= 500 and all(isinstance(s, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", s) for s in items)
    return (isinstance(value, dict) and type(value.get("schemaVersion")) is int and value.get("schemaVersion") == 1
            and isinstance(value.get("outcome"), str) and value["outcome"] in OUTCOMES
            and (value.get("label") == OUTCOMES[value["outcome"]]
                 or status == "VERIFIED_PROJECTS" and value["outcome"] == "MET" and value.get("label") == "点名项目已保留") and isinstance(value.get("evidenceCode"), str)
            and re.fullmatch(r"[A-Z_]{1,64}", value["evidenceCode"]) and isinstance(value.get("evidenceLabel"), str)
            and len(value["evidenceLabel"]) <= 200 and projects(value.get("userRemoved")) and projects(value.get("cleanupRemoved")) and projects(value.get("automaticRemoved", [])))


def read_completion(value):
    labels = {"MET": "已核对的构建要求均满足", "PARTIAL": "尚未完全满足构建要求", "UNKNOWN": "整体结果仍待核实",
              "WAITING": "等待确认，尚非最终结果", "CONTEXT": "仅完成基础信息核对"}
    quantity_labels = {"MET": "数量已达标", "NOT_MET": "数量未达标", "UNVERIFIED": "数量待核实", "NO_TARGET": "无额外数量要求"}
    number = lambda n: type(n) is int and 0 <= n <= 500
    if not isinstance(value, dict) or type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1:
        return None
    q, counts = value.get("quantity"), value.get("targets")
    if (not isinstance(value.get("outcome"), str) or value["outcome"] not in labels or value.get("label") != labels[value["outcome"]]
            or not isinstance(value.get("note"), str) or len(value["note"]) > 1000 or not isinstance(q, dict)
            or not isinstance(q.get("status"), str) or q["status"] not in quantity_labels or q.get("label") != quantity_labels[q["status"]]
            or q.get("scope") not in ("", "TOTAL_FILES", "ROOT_MODS", "ADDED_ROOTS") or q.get("precision") not in ("", "EXACT", "APPROXIMATE")
            or not isinstance(q.get("display"), str) or len(q["display"]) > 500
            or any(k not in q or q[k] is not None and not number(q[k]) for k in ("target", "min", "max", "actual", "acceptedMin", "acceptedMax"))
            or q["status"] in ("MET", "NOT_MET") and (not q["scope"] or not q["precision"] or not all(number(q[k]) for k in ("target", "min", "max", "actual", "acceptedMin", "acceptedMax")))
            or not isinstance(counts, dict) or any(not number(counts.get(k)) or counts[k] > 192 for k in ("met", "partial", "notMet", "unknown", "waiting", "context"))
            or not number(counts.get("parentIssues")) or counts["parentIssues"] > 64
            or sum(counts[k] for k in ("met", "partial", "notMet", "unknown", "waiting", "context")) > 192):
        return None
    return value


def completion_lines(coverage):
    value = read_completion(coverage.get("completion"))
    lines = ["## 整体需求核对", ""]
    if value is None:
        return lines + ["暂无法核实：当前或历史记录缺少统一整体结论，逐项满足不代表全局数量已达标。", ""]
    counts = value["targets"]
    lines += [f"整体判断：**{value['label']}**。", "", f"{value['quantity']['display']}；{value['quantity']['label']}。",
              f"逐项目标（含点名核对）：{counts['met']}项满足，{counts['partial'] + counts['notMet']}项有缺口，{counts['unknown']}项待核实，{counts['waiting']}项待选择。"]
    if value["note"]:
        lines += ["", value["note"] + "。"]
    return lines + [""]


def assess(coverage, artifact, pending=False):
    """Use production assessments verbatim; artifact acceptance is an independent axis."""
    rows = []
    for parent in coverage.get("requirements", [])[:64]:
        for row in [parent, *parent.get("criteria", [])[:16]]:
            assessment = row.get("assessment") or {}
            if row.get("status") == "CONTEXT":
                continue
            canonical = valid_assessment(assessment, row.get("status"))
            outcome = "待选择" if pending else assessment["label"] if canonical else "暂无法核实（缺统一结论）"
            if not pending and canonical and row.get("status") == "VERIFIED_PROJECTS" and assessment["outcome"] == "MET":
                outcome = "点名项目已保留"
            evidence = assessment.get("evidenceLabel", "未采集统一证据状态") if canonical else "历史状态：" + str(row.get("status", "未知"))
            details = []
            for item in row.get("mods", [])[:24]:
                partners = "，关联 " + "、".join(item.get("partners", [])) if item.get("partners") else ""
                details.append(f"{item.get('slug', '未知')}：{item.get('purpose', '')} [{item.get('evidenceType', '历史依据')}]{partners}"
                               + (f"；原文：{item['quote']}" if item.get("quote") else ""))
            quantity = row.get("quantity") or {}
            if quantity:
                details.append(f"{'新增根' if quantity.get('scope') == 'ADDED_ROOTS' else '保留根'} {quantity.get('actual', '未知')} / {quantity.get('min', '?')}～{quantity.get('max', '?')}；计数{'完整' if quantity.get('complete') else '待核实'}")
                details.append("完整计数项目：" + "、".join(quantity.get("projects", [])[:500]))
            note = row.get("note", "")
            if row.get("missing"):
                note += "；未保留：" + "、".join(row["missing"][:100])
            for field, label in (("userRemoved", "用户主动取消"), ("cleanupRemoved", "随选择清理"), ("automaticRemoved", "按设置采用推荐组合，未保留")):
                if assessment.get(field):
                    note += "；" + label + "：" + "、".join(assessment[field][:500])
            acceptance = {"VERIFIED": "产物与快照一致", "MISMATCH": "产物核对不一致，导出验收未通过",
                          "UNVERIFIED": "产物快照未核实", "NOT_EXPORTED": "未评估（未导出）"}.get(artifact["status"], "产物未核实")
            if pending:
                acceptance = "等待用户选择，尚无最终验收结论"
            rows.append((row.get("source", "未知要求"), outcome, evidence, "；".join(details) or "暂无可核对项目", note + "；" + acceptance))
    return rows or [("本次需求", "待选择" if pending else "暂无法核实", "未采集最终结构化需求报告", "无最终要求证据",
                     "未评估（未导出）" if artifact["status"] == "NOT_EXPORTED" else "不能由项目名称推断完成状态")]


def evidence_table(coverage):
    labels = {"VERIFIED": "有效", "NOT_RETAINED": "未保留", "VERSION_UNVERIFIED": "选版待核实", "FACT_CHANGED": "原文已变化",
              "FACTS_UNAVAILABLE": "资料未取得", "QUOTE_MISMATCH": "引用不匹配", "VETOED": "已否决", "RELATION_CHANGED": "关系已变化"}
    lines = ["## 最终证据账本", "", "| 目标 | 项目 / 版本 | 用途及来源 | 原文或关系 | 有效性 |", "| --- | --- | --- | --- | --- |"]
    for proof in coverage.get("ledger", [])[:4096]:
        relation = "、".join(proof.get("partners", []))
        lines.append("| " + " | ".join(cell(v) for v in (proof.get("targetId", "未知"),
                     str(proof.get("slug", "未知")) + " @ " + str(proof.get("versionId", "未核实")),
                     str(proof.get("purpose", "")) + " [" + str(proof.get("evidenceType", "")) + "]",
                     proof.get("quote") or relation or "由已选版本身份/关系提供；非用途原文", labels.get(proof.get("status"), "待核实"))) + " |")
    if not coverage.get("ledger"):
        lines += ["| — | — | 未采集详细账本 | 不能补造原文或关系 | 待核实 |"]
    lines += ["", "展示行可能截断；局部完整计数项目另列。用途和关系有效不等于内容丰富度、实测优化收益或可启动性已验证。"]
    return lines


def filtered_logs(work):
    users = sorted((work / "logs").rglob("user-*.log"))
    if len(users) > 1:
        raise ValueError("Capture must contain only the evaluated account's user log")
    # Per-user tee is canonical; stdout duplicates its business messages.
    paths = ([path for path in users if path.stat().st_size > 0] or [work / "logs/stdout.log"]) + [work / "logs/stderr.log"]
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
    reply = result.get("reply") or next((e.get("reply", "") for e in reversed(events) if e.get("reply") and e.get("event") in {"chat-final", "final", "failure"}), "")
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
    trace = json_tag(reply, "trace")
    coverage = json_tag(reply, "requirement_report") or trace.get("requirementCoverage") or {}
    artifact = artifact_check(index, result.get("preview") or {}, trace, reply, bool(pack))
    if json_tag(reply, "requirement_report") and trace.get("requirementCoverage") and coverage != trace["requirementCoverage"]:
        artifact["status"] = "MISMATCH"
        artifact["issues"].append("对话需求报告与trace最终证据不一致")
    evidence_issues = evidence_snapshot_issues(coverage, trace.get("assembly") or {})
    if evidence_issues:
        artifact["status"] = "MISMATCH"
        artifact["issues"].extend(evidence_issues)
    nodes = artifact["nodes"]
    download = result.get("download", {})
    verified = bool(pack and download.get("sha256") == digest and download.get("bytes") == pack.stat().st_size)
    count = len(index.get("files", []))
    input_text = (work / "input.md").read_text(encoding="utf-8") if (work / "input.md").is_file() else "未采集实际输入。"
    run = read_json(work / "run.json", {})
    stdout = work / "logs/stdout.log"
    tasks = task_summaries(events, stdout.read_text(encoding="utf-8", errors="replace") if stdout.is_file() else "")
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
              f"导出项目证据匹配：{len(nodes)} / {count} 个文件；产物核对：{artifact['status']}；{'；'.join(artifact['issues']) or '无差异'}。",
              "", "```json", json.dumps(diagnosis, ensure_ascii=False, indent=2), "```",
              "", "## 实际项目及版本", "", "| slug | 名称 | 版本 | 项目说明（上游元数据） |", "| --- | --- | --- | --- |"]
    for slug, n in sorted(nodes.items()):
        lines.append("| " + " | ".join(cell(v) for v in [slug, n.get("label", n.get("title", slug)), n.get("version", n.get("versionNumber", "未知")), n.get("description", "未采集")]) + " |")
    lines += ["", "## 构建回复", "", "````text", reply, "````", "", "## 业务决策与 Agent 输入输出", "", business or "未采集业务及 Agent 日志。",
              "", "## 采集范围", "", f"过滤逐请求 HTTP/计数行 {removed} 条；不展开轮询、排队/运行信封及浏览器控制台 JSON。重复 stdout/tee 业务记录只选一个来源。",
              f"Agent I/O：{'已采集' if '[Agent input]' in business and '[Agent output]' in business else '采集不完整'}；认证字段脱敏。原始中间文件不发布。"]
    pending = bool(trace.get("awaitingInput") or tag(reply, "replacement_options"))
    assessment = ["# 自动评估", "", "构建判断与网站使用同一份最终结构化需求报告；导出产物另行独立核对，不通过名称匹配推断用途。",
                  "", f"包名：{index.get('name', '未知')}；环境：{index.get('dependencies', {})}。",
                  f"文件数：{count if pack else '未导出'}。",
                  f"静态依赖：{closure.get('status', '未采集')}，{len(issues)} 项诊断；下载字节/哈希核对：{verified}。",
                  f"产物核对：{artifact['status']}；{'；'.join(artifact['issues']) or '文件、项目、版本和环境与最终快照一致'}。",
                  "", *completion_lines(coverage), "| 要求 / 子目标 | 构建判断 | 证据状态 | 如何完成及实际项目 | 缺口、选择与产物核对 |", "| --- | --- | --- | --- | --- |"]
    assessment += ["| " + " | ".join(cell(v) for v in row) + " |" for row in assess(coverage, artifact, pending)]
    assessment += [""] + evidence_table(coverage)
    assessment += [""] + timings + ["", "## 网络与采集概况", "", f"浏览器 HTTP：{len(browser) if browser or run.get('browserHttpCapture') else '未采集'}；浏览器状态：{dict(statuses)}。",
                   f"服务端 HTTP：{sum(int(t.get('serverHttp', 0)) for t in tasks.values()) if tasks and all('serverHttp' in t for t in tasks.values()) else '未采集'}；"
                   f"兜底事实：{sum(int(t.get('fallbackFacts', 0)) for t in tasks.values()) if tasks and all('fallbackFacts' in t for t in tasks.values()) else '未采集'}。",
                   f"成功回执超预算：{sum(s['late'] for s in receipts.values()) if any(s['compared'] for s in receipts.values()) else '未采集预算，无法判断'}"
                   f"（已核对 {sum(s['compared'] for s in receipts.values())} 项）；原因见 log.md 的回执时间与异常汇总。",
                   "", "未进行安装及启动检查。待核实项不能按通过处理；优化效果和内容丰富度待人工评估。", "", "# 人工评估", "", "请在此追加评价、修正及新的要求。", ""]
    visible = work / "result/chat-output.md"
    if visible.is_file():
        lines += ["", "## 实际对话显示采集", "", "以下为浏览器实际可见文本；不由原始协议伪造界面。", "", visible.read_text(encoding="utf-8")]
    else:
        lines += ["", "## 实际对话显示采集", "", "未采集浏览器可见文本，不能由原始协议断言显示质量。"]
    output.mkdir(parents=True, exist_ok=True)
    (output / "log.md").write_text(sanitize("\n".join(lines)), encoding="utf-8")
    (output / "eval.md").write_text(sanitize("\n".join(assessment)), encoding="utf-8")
    if pack:
        (output / pack.name).write_bytes(pack.read_bytes())
    return {"files": count, "exported": bool(pack), "sha256Matches": verified, "artifactStatus": artifact["status"], "logBytes": (output / "log.md").stat().st_size}


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
