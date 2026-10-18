"""在临时目录通过curl和Docker替身执行备份脚本，不读取真实.env或启动容器。"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--bash", default="bash")
args = parser.parse_args()
project = Path(__file__).resolve().parent.parent
with tempfile.TemporaryDirectory(prefix="backup-check-", dir=project / "target") as temporary:
    root = Path(temporary)
    app = root / "app space"
    db = app / "maa_db"
    db.mkdir(parents=True)
    (db / "knowledge.mv.db").write_text("fixture-database", encoding="utf-8")
    commands = root / "bin"
    commands.mkdir()
    with zipfile.ZipFile(root / "fixture.zip", "w") as fixture:
        fixture.writestr("knowledge.mv.db", "fixture-database")
    scripts = {
        "curl": '''#!/usr/bin/env bash
set -eu
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-o" ]; then BODY="$2"; shift 2; else shift; fi
done
DEST="$MAA_DIR/maa_db/backups/$TAG"
[ ! -e "$DEST" ] || exit 12
mkdir "$DEST"
cp "$FIXTURE_ZIP" "$DEST/knowledge.zip"
printf '{"ok":true}' > "$BODY"
printf '200'
''',
        "docker": '''#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$FIXTURE_DOCKER_LOG"
''',
        "python3": '#!/usr/bin/env bash\nexec "$FIXTURE_PYTHON" "$@"\n',
        "cp": '''#!/usr/bin/env bash
if [ "${FIXTURE_FAIL_COPY:-0}" = "1" ]; then exit 1; fi
exec /bin/cp "$@"
''',
    }
    for name, content in scripts.items():
        command = commands / name
        command.write_text(content, encoding="utf-8", newline="\n")
        command.chmod(0o755)
    env = dict(os.environ, MAA_DIR=app.as_posix(), BACKUP_DIR=(db / "backups").as_posix(),
               TMPDIR=root.as_posix(), TMP=root.as_posix(), TEMP=root.as_posix(),
               MAA_OPS_TOKEN="fixture-ops-token", FIXTURE_ZIP=(root / "fixture.zip").as_posix(),
               FIXTURE_BIN=commands.as_posix(), APP_URL="http://127.0.0.1:9",
               FIXTURE_PYTHON=Path(sys.executable).as_posix(),
               FIXTURE_DOCKER_LOG=(root / "docker.log").as_posix())
    env["PATH"] = str(commands) + os.pathsep + env["PATH"]

    def run(tag, cold=False, succeeds=True):
        result = subprocess.run([args.bash, "-c",
                                'export PATH="$(cd "$FIXTURE_BIN" && pwd):$PATH"; exec bash "$@"',
                                "backup-check", str(project / "ops/backup.sh"), *(["--cold"] if cold else [])],
                                env=dict(env, TAG=tag), capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=30)
        assert (result.returncode == 0) == succeeds, (result.returncode, result.stdout, result.stderr)

    run("online")
    with zipfile.ZipFile(db / "backups/online/knowledge.zip") as archive:
        assert archive.testzip() is None
    run("online", succeeds=False)
    assert not (root / "docker.log").exists()
    run("cold", cold=True)
    assert (db / "backups/cold/knowledge.mv.db").read_text() == "fixture-database"
    calls = (root / "docker.log").read_text()
    assert calls.splitlines() == ["compose stop", "compose up -d"]
    run("cold", cold=True, succeeds=False)
    run("../escape", cold=True, succeeds=False)
    assert (root / "docker.log").read_text() == calls
    env["FIXTURE_FAIL_COPY"] = "1"
    run("copy-failure", cold=True, succeeds=False)
    assert (root / "docker.log").read_text().splitlines() == [
        "compose stop", "compose up -d", "compose stop", "compose up -d"]
    env["FIXTURE_FAIL_COPY"] = "0"
    env["KEEP_MAX"] = "1"
    run("latest")
    assert [p.name for p in (db / "backups").iterdir()] == ["latest"]
    assert (db / "knowledge.mv.db").read_text() == "fixture-database"
print("备份脚本检查通过：在线目录交由应用占用、冷备成功及失败后恢复、同名拒绝和非法tag拒绝；仅使用替身。")
