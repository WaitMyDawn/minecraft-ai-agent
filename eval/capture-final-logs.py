"""Archive only the evaluated account's interval, redacting before any write."""
import argparse
import json
import re
import sys
from pathlib import Path


def redact(text, secrets):
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    text = re.sub(r"sk-[A-Za-z0-9_-]{8,}", "[REDACTED_KEY]", text)
    text = re.sub(r"(?i)Bearer\s+[A-Za-z0-9._~-]+", "Bearer [REDACTED]", text)
    text = re.sub(r"[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}", "[REDACTED_TOKEN]", text)
    text = re.sub(r'''(?i)(["']?(?:password|passwd|api[-_ ]?key|token|authorization|x-auth-token|密码)["']?\s*[:=]\s*)["']?[^\s,;\r\n}"']+["']?''', r'\1"[REDACTED]"', text)
    if any(secret and secret in text for secret in secrets):
        raise ValueError("Redaction did not remove a supplied secret")
    return text


def interval(text, since):
    selected, keep = [], False
    for line in text.splitlines():
        stamp = re.match(r"\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", line)
        if stamp:
            keep = stamp.group(1) >= since
        if keep:
            selected.append(line)
    return "\n".join(selected)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--user-log", required=True)
    parser.add_argument("--since", required=True)
    parser.add_argument("--stdout", required=True)
    parser.add_argument("--stderr", required=True)
    parser.add_argument("--redactions-stdin", action="store_true")
    args = parser.parse_args()
    work = Path(args.work_dir).resolve()
    metadata = json.loads((work / "run.json").read_text(encoding="utf-8"))
    if metadata.get("captureKind") != "final-build-session" or metadata.get("captureDirectory") != str(work):
        raise ValueError("Unknown capture directory")
    user = Path(args.user_log)
    if not re.fullmatch(r"user-\d+\.log", user.name) or not re.fullmatch(r"\d{4}-\d\d-\d\d \d\d:\d\d:\d\d", args.since):
        raise ValueError("Invalid account log or interval")
    secrets = json.load(sys.stdin) if args.redactions_stdin else []
    # All transformations occur in memory; raw input is never copied into the capture.
    sanitized_user = redact(interval(user.read_text(encoding="utf-8"), args.since), secrets)
    sanitized_stdout = redact(Path(args.stdout).read_text(encoding="utf-8", errors="replace"), secrets)
    sanitized_stderr = redact(Path(args.stderr).read_text(encoding="utf-8", errors="replace"), secrets)
    (work / "logs" / user.name).write_text(sanitized_user, encoding="utf-8")
    (work / "logs/stdout.log").write_text(sanitized_stdout, encoding="utf-8")
    (work / "logs/stderr.log").write_text(sanitized_stderr, encoding="utf-8")
    metadata["logRedaction"] = "In-memory supplied-secret, API-key, bearer, JWT and credential-field redaction before writing"
    metadata["userLogSince"] = args.since
    (work / "run.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print("sanitized-test-log-lines=" + str(len(sanitized_user.splitlines())))
