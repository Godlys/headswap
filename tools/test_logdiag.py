"""Тесты диагноза: python -X utf8 -m unittest tools/test_logdiag.py

Синтетические логи и ожидания лежат в tools/logdiag_cases/ (cases.json). Те же кейсы гоняет
Kotlin-тест приложения (LogDiagTest), так что Python и Android обязаны давать одинаковый ответ.
"""
import json
import os
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import logdiag  # noqa: E402

RULES = logdiag.load_rules()
CASES_DIR = os.path.join(HERE, "logdiag_cases")
LOG_NAME = "headswap-20261012-090000.log"
ACTIONS = {"open_aa", "video", "audio", "battery", "autostart", "app_settings", "wifi", "hotspot",
           "bluetooth", "onboarding", "update", "share_log"}


def device(version):
    return f"""Headswap {version}
Время: 2026-10-12 09:00:00 +0300
Устройство: Google Pixel 8, Android 16 (SDK 36), BP1A
"""


def run_case(case):
    with open(os.path.join(CASES_DIR, case["log"]), encoding="utf-8") as fh:
        log = fh.read()
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "headswap-20261012-090000.zip")
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("device.txt", device(case["version"]))
            z.writestr("logs/" + LOG_NAME, log)
        return logdiag.run(p, RULES)


class TestCases(unittest.TestCase):
    def test_cases(self):
        with open(os.path.join(CASES_DIR, "cases.json"), encoding="utf-8") as fh:
            cases = json.load(fh)
        for case in cases:
            name = f"{case['log']} @ {case['version']}"
            with self.subTest(name):
                f, hits = run_case(case)
                causes = [h["id"] for h in hits if h["category"] != "note" and h["score"] >= logdiag.MIN_SCORE]
                notes = [h["id"] for h in hits if h["category"] == "note"]
                if "top" in case:
                    self.assertEqual(causes[0] if causes else None, case["top"], causes)
                for i in case.get("has", []):
                    self.assertIn(i, causes)
                for i in case.get("not", []):
                    self.assertNotIn(i, causes)
                for i in case.get("notes", []):
                    self.assertIn(i, notes)
                for k, v in case.get("facts", {}).items():
                    self.assertEqual(f.get(k), v, k)


class TestEngine(unittest.TestCase):
    def test_rules_file_valid(self):
        ids = [r["id"] for r in RULES["rules"]]
        self.assertEqual(len(ids), len(set(ids)))
        for r in RULES["rules"]:
            for c in r.get("all", []) + r.get("any", []) + r.get("none", []):
                if c[0].startswith("m."):
                    self.assertIn(c[0][2:], RULES["markers"], f"{r['id']}: нет маркера {c[0]}")
            for k in r.get("evidence", []):
                self.assertIn(k, RULES["markers"], f"{r['id']}: нет маркера {k}")
            for k in ("title_en", "advice_en"):
                self.assertTrue(r.get(k), f"{r['id']}: нет {k}")
            for k in ("what", "what_en"):
                self.assertTrue(r.get(k), f"{r['id']}: нет {k}")
            self.assertTrue(1 <= len(r.get("steps", [])) <= 4, f"{r['id']}: шагов должно быть 1-4")
            for s in r["steps"]:
                self.assertTrue(s.get("text") and s.get("text_en"), f"{r['id']}: шаг без text/text_en")
                if "action" in s:
                    self.assertIn(s["action"], ACTIONS, f"{r['id']}: неизвестное действие {s['action']}")

    def test_update_prefix(self):
        f, hits = run_case({"log": "auth_v2.log", "version": "1.1.6"})
        h = [h for h in hits if h["id"] == "auth_v2_old_version"][0]
        self.assertTrue(h["advice"].startswith("Обновите Headswap до 1.1.7"))
        self.assertTrue(h["steps"][0].startswith("Обновите Headswap до 1.1.7"))

    def test_works_not_unrecognized(self):
        f, hits = run_case({"log": "works.log", "version": "2.4.4"})
        self.assertNotIn("не распознана", logdiag.render(f, hits))

    def test_check_none(self):
        f = {"x": None}
        self.assertTrue(logdiag.check(["x", "!=", 0], f))
        self.assertFalse(logdiag.check(["x", "==", 0], f))
        self.assertTrue(logdiag.check(["x", "<", 30], f))
        self.assertTrue(logdiag.check(["v", "<v", "2.4.0"], {"v": "2.3.4-h196test1"}))
        self.assertFalse(logdiag.check(["v", "<v", "2.4.0"], {"v": None}))


if __name__ == "__main__":
    unittest.main()
