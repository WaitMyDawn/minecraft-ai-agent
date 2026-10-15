"""Regression checks for evidence publication and preservation of human review."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("final_build_report", Path(__file__).with_name("final-build-report.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)
capture_spec = importlib.util.spec_from_file_location("capture_final_logs", Path(__file__).with_name("capture-final-logs.py"))
capture = importlib.util.module_from_spec(capture_spec)
capture_spec.loader.exec_module(capture)
session_spec = importlib.util.spec_from_file_location("final_build_session", Path(__file__).with_name("final-build-session.py"))
session = importlib.util.module_from_spec(session_spec)
session_spec.loader.exec_module(session)


class BrowserSessionCaptureTest(unittest.TestCase):
    def test_production_page_preserves_ui_and_adds_only_http_telemetry(self):
        page = session.production_page('<head></head><main>production chat</main>')
        self.assertIn('<main>production chat</main>', page)
        self.assertIn('[Modrinth HTTP]', page)
        self.assertNotIn('X-LLM-Api-Key', page)

    def test_operation_poll_download_is_captured_only_for_successful_zip(self):
        self.assertTrue(session.is_pack_response('/api/operations/fixture', 200, 'application/zip', b'PK\x03\x04fixture'))
        self.assertFalse(session.is_pack_response('/api/operations/fixture', 400, 'application/zip', b'PK\x03\x04fixture'))
        self.assertFalse(session.is_pack_response('/api/modpack/build', 202, 'application/json', b'{}'))

    def test_browser_login_token_is_redacted_before_protocol_capture(self):
        old = list(session.STATE['secrets'])
        try:
            session.remember_auth_response('/api/user/login', b'{"token":"fixture-browser-token"}')
            self.assertNotIn('fixture-browser-token', session.clean('{"token":"fixture-browser-token"}'))
        finally:
            session.STATE['secrets'] = old


class CaptureRedactionTest(unittest.TestCase):
    def test_credentials_are_removed_before_archiving(self):
        value = 'password=fixture-secret token="fixture-token" apiKey=sk-fixtureSecret123 Bearer fixtureBearerToken '
        clean = capture.redact(value, ['fixture-secret'])
        for secret in ['fixture-secret', 'fixture-token', 'sk-fixtureSecret123', 'fixtureBearerToken']:
            self.assertNotIn(secret, clean)
        self.assertIn('REDACTED', clean)

    def test_interval_preserves_current_multiline_agent_evidence(self):
        value = '[2026-10-14 10:00:00.000] old\nold details\n[2026-10-14 11:00:00.000] current\ncurrent agent JSON\n'
        selected = capture.interval(value, '2026-10-14 11:00:00')
        self.assertNotIn('old', selected)
        self.assertIn('current agent JSON', selected)


class FinalBuildReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="maa-final-tests-")
        self.base = Path(self.temporary.name)
        self.work = self.base / "capture"
        (self.work / "logs").mkdir(parents=True)
        (self.work / "result").mkdir()
        self.output = self.base / "published"

    def tearDown(self):
        self.temporary.cleanup()

    def test_failure_with_no_critic_still_publishes_honest_reports(self):
        reply = '<trace>{"branch":"FORMAT_VIOLATION","architect":{"ms":20,"calls":2},"critic":null}</trace>'
        (self.work / "logs/protocol.jsonl").write_text(json.dumps({"event":"failure","reply":reply,"message":"未产生清单"}) + "\n", encoding="utf-8")
        (self.work / "input.md").write_text("# 失败测试输入\n", encoding="utf-8")
        (self.work / "run.json").write_text("{}", encoding="utf-8")
        summary = report.publish(self.work, self.output)
        self.assertFalse(summary['exported'])
        self.assertEqual({'log.md','eval.md'}, {p.name for p in self.output.iterdir()})
        self.assertIn('FORMAT_VIOLATION', (self.output / 'log.md').read_text(encoding='utf-8'))

    def fixture(self):
        pack = self.work / "result/朝花夕拾.mrpack"
        files = [{"path": f"mods/{i}.jar", "downloads": [f"https://example.invalid/{i}.jar"], "hashes": {"sha1": str(i)}} for i in range(100)]
        with zipfile.ZipFile(pack, "w") as archive:
            archive.writestr("modrinth.index.json", json.dumps({"name": "朝花夕拾", "files": files, "dependencies": {"minecraft": "1.21.1", "neoforge": "21.1.256"}}))
            archive.writestr("maa-diagnostics.json", json.dumps({"closure": {"status": "INCOMPLETE", "issues": [{"reason": "VERSION_CONFLICT"}]}}))
        result = {"reply": '<mods>jei</mods><trace>{"architect":{"ms":50,"calls":1}}</trace>',
                  "preview": {"nodes": [
                      {"slug": "jei", "title": "JEI", "versionNumber": "1", "fileInfo": {"hashes": {"sha1": "0"}}},
                      {"slug": "appleskin", "title": "AppleSkin", "fileInfo": {"hashes": {"sha1": "not-exported"}}}]},
                  "download": {"sha256": hashlib.sha256(pack.read_bytes()).hexdigest(), "bytes": pack.stat().st_size}}
        (self.work / "result/browser-result.json").write_text(json.dumps(result), encoding="utf-8")
        events = [{"event": "browser-http", "detail": json.dumps({"status": 429, "url": "https://example.invalid"})},
                  {"event": "response", "path": "/api/chat/task/t/facts", "status": 202, "body": "polling-noise-sentinel"},
                  {"event": "choices", "choices": [{"original": "alexs-mobs", "selected": ["alexs-mobs-continued"]}]},
                  {"event": "failure", "message": "key-timing-error-preserved"}]
        (self.work / "logs/protocol.jsonl").write_text("\n".join(json.dumps(e) for e in events), encoding="utf-8")
        (self.work / "logs/stdout.log").write_text("[Modrinth HTTP] http-noise-sentinel\n[Modrinth task] taskId=t browserHttp=1 serverHttp=0 fallbackFacts=0 server429=0\nduplicated-business-sentinel\n", encoding="utf-8")
        (self.work / "logs/user-4.log").write_text('[Agent input]\nUSER: test\n[Agent output]\n{"token":"synthetic-token"}\nsk-SYNTHETIC_FIXTURE_ONLY\n[Modrinth HTTP] http-noise-sentinel\nrequired-core-missing\n', encoding="utf-8")
        (self.work / "input.md").write_text("8）有很多实用模组，例如苹果皮、jei", encoding="utf-8")
        return pack

    def test_three_artifacts_actual_download_and_readable_evidence(self):
        pack = self.fixture()
        outcome = report.publish(self.work, self.output)
        self.assertEqual({p.name for p in self.output.iterdir()}, {"log.md", "eval.md", "朝花夕拾.mrpack"})
        self.assertEqual(pack.read_bytes(), (self.output / pack.name).read_bytes())
        self.assertTrue(outcome["sha256Matches"])
        log = (self.output / "log.md").read_text(encoding="utf-8")
        evaluation = (self.output / "eval.md").read_text(encoding="utf-8")
        for noise in ("polling-noise-sentinel", "http-noise-sentinel", "duplicated-business-sentinel", "synthetic-token", "sk-SYNTHETIC_FIXTURE_ONLY"):
            self.assertNotIn(noise, log)
        for evidence in ("required-core-missing", "key-timing-error-preserved", "alexs-mobs-continued", "VERSION_CONFLICT", "429"):
            self.assertIn(evidence, log)
        self.assertIn("未取得示例证据：appleskin", evaluation)
        self.assertIn("海灵物语", evaluation)
        self.assertIn("# 人工评估", evaluation)
        self.assertNotIn("可启动：是", evaluation)

    def test_human_evaluation_is_never_overwritten(self):
        self.fixture()
        self.output.mkdir()
        review = self.output / "eval.md"
        review.write_text("人工评估及新要求", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            report.publish(self.work, self.output)
        self.assertEqual(review.read_text(encoding="utf-8"), "人工评估及新要求")
        self.assertEqual(list(self.output.iterdir()), [review])

    def test_measured_timings_preserve_wait_and_unknown_stages(self):
        events = [
            {"event": "request", "path": "/api/chat", "time": "2026-10-14T10:00:00+00:00"},
            {"event": "candidates", "time": "2026-10-14T10:00:10+00:00"},
            {"event": "choices", "time": "2026-10-14T10:01:10+00:00"},
            {"event": "phase-start", "phase": "export", "time": "2026-10-14T10:02:00+00:00"},
            {"event": "phase-end", "phase": "export", "time": "2026-10-14T10:02:05+00:00"},
            {"event": "final", "time": "2026-10-14T10:02:06+00:00"},
        ]
        rows = "\n".join(report.stage_timings(events, {"architect": {"ms": 1234}, "critic": None}))
        for expected in ("| architect | 1.234", "| 用户确认等待 | 60.000", "| 导出响应 | 5.000", "| 总墙钟时间 | 126.000", "| critic | 未采集"):
            self.assertIn(expected, rows)
        self.assertIn("不能与各阶段重复相加", rows)

    def test_captured_visible_text_and_screenshot_are_published(self):
        self.fixture()
        (self.work / "result/chat-output.md").write_text("实际渲染的结果表格\nsk-SYNTHETIC_FIXTURE_ONLY", encoding="utf-8")
        png = b"\x89PNG\r\n\x1a\nfixture"
        (self.work / "result/chat-output.png").write_bytes(png)
        report.publish(self.work, self.output)
        self.assertEqual(png, (self.output / "chat-output.png").read_bytes())
        self.assertNotIn("sk-SYNTHETIC", (self.output / "chat-output.md").read_text(encoding="utf-8"))
        self.assertIn("## 阶段耗时", (self.output / "eval.md").read_text(encoding="utf-8"))

    def test_failure_does_not_fabricate_pack_or_success(self):
        (self.work / "logs/protocol.jsonl").write_text(json.dumps({"event": "failure", "message": "export unavailable"}), encoding="utf-8")
        result = report.publish(self.work, self.output)
        self.assertFalse(result["exported"])
        self.assertEqual({p.name for p in self.output.iterdir()}, {"log.md", "eval.md"})
        evaluation = (self.output / "eval.md").read_text(encoding="utf-8")
        self.assertIn("未导出", evaluation)
        self.assertIn("未采集", evaluation)
        self.assertEqual(evaluation.count("未评估（未导出）"), 8)
        self.assertNotIn("部分证据，关系待核实", evaluation)

    def test_missing_browser_hash_does_not_claim_download_verified(self):
        self.fixture()
        path = self.work / "result/browser-result.json"
        result = json.loads(path.read_text(encoding="utf-8"))
        del result["download"]
        path.write_text(json.dumps(result), encoding="utf-8")
        self.assertFalse(report.publish(self.work, self.output)["sha256Matches"])

    def test_unowned_capture_cannot_be_deleted(self):
        self.fixture()
        with self.assertRaises(ValueError):
            report.cleanup_capture(self.work, self.output)
        self.assertTrue(self.work.exists())

    def test_late_success_remains_visible_after_polling_filtered(self):
        events = [
            {"event": "response", "path": "/api/chat/task/t/facts", "time": "2026-10-13T09:59:59+00:00",
             "body": json.dumps({"perCallBudgetMs": 30000, "wanted": []})},
            {"event": "response", "path": "/api/chat/task/t/facts", "time": "2026-10-13T10:00:00+00:00",
             "body": json.dumps({"wanted": [{"kind": "env_version", "key": "a"}]})},
            {"event": "request", "path": "/api/chat/task/t/facts", "time": "2026-10-13T10:00:36+00:00",
             "body": json.dumps({"answered": [{"kind": "env_version", "key": "a", "data": []}]})},
        ]
        (self.work / "logs/protocol.jsonl").write_text("\n".join(json.dumps(e) for e in events), encoding="utf-8")
        report.publish(self.work, self.output)
        log = (self.output / "log.md").read_text(encoding="utf-8")
        self.assertIn("36000.0", log)
        self.assertIn("发现成功回执超过委派预算", log)
        self.assertNotIn('"perCallBudgetMs"', log)

    def test_mixed_user_logs_refused(self):
        self.fixture()
        (self.work / "logs/user-other.log").write_text("unrelated-user", encoding="utf-8")
        with self.assertRaises(ValueError):
            report.publish(self.work, self.output)
        self.assertFalse(self.output.exists())

    def test_unknown_budget_is_not_reported_as_zero_late_successes(self):
        event = {"event": "request", "path": "/api/chat/task/t/facts", "time": "2026-10-13T10:00:36+00:00",
                 "body": json.dumps({"answered": [{"kind": "env_version", "key": "a", "data": []}]})}
        (self.work / "logs/protocol.jsonl").write_text(json.dumps(event), encoding="utf-8")
        report.publish(self.work, self.output)
        self.assertIn("未采集预算，无法判断", (self.output / "eval.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
