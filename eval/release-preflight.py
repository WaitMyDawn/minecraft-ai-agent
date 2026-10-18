"""离线发布产物核查；仅读取源码、模板及指定jar，不读取运行配置或数据库。"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import zipfile


def sensitive_name(name):
    parts = PurePosixPath(name).parts
    return any(p in {"secrets", "credentials"} or
               (p == ".env" or p.startswith(".env.")) and p != ".env.example"
               for p in parts) or name.endswith((".pem", ".key", ".p12", ".pfx", ".mv.db", ".trace.db"))


def check(root, jar):
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    if any(sensitive_name(p) for p in tracked if p):
        raise ValueError("Git索引存在敏感文件类型；本检查未读取其内容")
    for name in (".env", ".env.prod", ".env.test", "nested/.env.production"):
        result = subprocess.run(["git", "check-ignore", "--no-index", "-q", name], cwd=root)
        if result.returncode != 0:
            raise ValueError("环境配置文件的Git忽略规则不完整")
    compose = (root / "docker-compose.yml").read_text(encoding="utf-8")
    if not re.search(r"^      SPRING_PROFILES_ACTIVE: prod$", compose, re.M):
        raise ValueError("Compose没有显式启用prod")
    if '"127.0.0.1:8080:8080"' not in compose:
        raise ValueError("应用端口不再仅绑定本机，需要重新核对代理边界")
    properties = (root / "src/main/resources/application.properties").read_text(encoding="utf-8")
    for expected in ("maa.encryption.secret=${MAA_ENCRYPTION_SECRET:}",
                     "maa.allow-registration=${MAA_ALLOW_REGISTRATION:false}",
                     "maa.ops.token=${MAA_OPS_TOKEN:}", "WRITE_DELAY=0"):
        if expected not in properties:
            raise ValueError("发布默认配置发生变化，需要复核安全或持久性约束")
    resources = root / "src/main/resources"
    compared = 0
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        if any(sensitive_name(p) or "/test-classes/" in p or p.endswith("RecoveryDatabaseProbe.class")
               for p in names):
            raise ValueError("jar含敏感文件类型或测试探针；本检查未读取可疑条目内容")
        if archive.testzip() is not None:
            raise ValueError("jar CRC校验失败")
        for source in resources.rglob("*"):
            if not source.is_file():
                continue
            relative = source.relative_to(resources).as_posix()
            if sensitive_name(relative):
                raise ValueError("源码资源目录含敏感文件类型；未读取内容")
            if archive.read("BOOT-INF/classes/" + relative) != source.read_bytes():
                raise ValueError("jar资源与当前源码不一致：" + relative)
            compared += 1
        if "Main-Class: org.springframework.boot.loader.launch.JarLauncher" not in archive.read(
                "META-INF/MANIFEST.MF").decode():
            raise ValueError("产物不是预期的Spring Boot可执行jar")
    with jar.open("rb") as stream:
        checksum = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
        digest = checksum.hexdigest()
    return {"status": "passed", "jar": str(jar), "bytes": jar.stat().st_size,
            "sha256": digest,
            "sourceResourcesMatched": compared,
            "scope": "离线配置及产物检查；不是秘密内容扫描、部署验收或容量测试"}


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", default="target/maa-app.jar")
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    print(json.dumps(check(project, (project / args.jar).resolve()), ensure_ascii=False, indent=2))
