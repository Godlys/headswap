"""Тесты диагноза на синтетических мини-логах: python -X utf8 -m unittest tools/test_logdiag.py"""
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import logdiag  # noqa: E402

RULES = logdiag.load_rules()


def device(version):
    return f"""Headswap {version}
Время: 2026-10-12 10:00:00 +0300
Устройство: Google Pixel 8, Android 16 (SDK 36), BP1A
"""


START = """09:00:00.000 сканирование (старт activity)
09:00:00.001 аксессуар не найден (getAccessoryList=null)
"""

ATTACH = """09:00:05.000 == USB_ACCESSORY_ATTACHED, в intent: manufacturer="Baidu" model="CarLife"
09:00:05.100 сессия: старт
09:00:05.200 == ВЕРСИЯ ПРОТОКОЛА ГУ: major=2 minor=0
09:00:05.300 WL hs STATISTIC_INFO channel=20082122 t=1
"""


def resume(dur, reason, frames):
    return f"""== РЕЗЮМЕ СЕССИИ ({dur} с, причина: {reason})
  видео: отправлено кадров={frames}
"""


class Case:
    def __init__(self, log, version="2.4.4"):
        self.log, self.version = log, version

    def __enter__(self):
        self.dir = tempfile.TemporaryDirectory()
        p = os.path.join(self.dir.name, "headswap-20261012-090000.zip")
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("device.txt", device(self.version))
            z.writestr("logs/headswap-20261012-090000.log", self.log)
        f, hits = logdiag.run(p, RULES)
        self.facts = f
        self.ids = [h["id"] for h in hits if h["category"] != "note"]
        self.notes = [h["id"] for h in hits if h["category"] == "note"]
        self.hits = hits
        return self

    def __exit__(self, *a):
        self.dir.cleanup()


class TestDiag(unittest.TestCase):
    def test_rules_file_valid(self):
        ids = [r["id"] for r in RULES["rules"]]
        self.assertEqual(len(ids), len(set(ids)))
        for r in RULES["rules"]:
            for c in r.get("all", []) + r.get("any", []) + r.get("none", []):
                name = c[0]
                if name.startswith("m."):
                    self.assertIn(name[2:], RULES["markers"], f"{r['id']}: нет маркера {name}")
            for k in r.get("evidence", []):
                self.assertIn(k, RULES["markers"], f"{r['id']}: нет маркера {k}")

    def test_no_accessory(self):
        with Case(START) as c:
            self.assertEqual(c.ids[0], "aoa_not_started")

    def test_aa_server_off(self):
        refused = "".join(
            f"09:00:{10 + 3 * i:02d}.000 AA сервер 127.0.0.1:5277 недоступен: ConnectException: ECONNREFUSED\n"
            for i in range(5))
        with Case(START + ATTACH + refused) as c:
            self.assertEqual(c.ids[0], "aa_server_off")
            ev = [h for h in c.hits if h["id"] == "aa_server_off"][0]["evidence"]
            self.assertTrue(ev and "5277" in ev[0])

    def test_fixed_in_gates_by_version(self):
        log = START + ATTACH + "09:00:06.000 == ГУ ШЛЁТ HU_AUTHEN_REQUEST randomValue=2.0;20261012\n"
        with Case(log, "1.1.6") as c:
            self.assertIn("auth_v2_old_version", c.ids)
            adv = [h for h in c.hits if h["id"] == "auth_v2_old_version"][0]["advice"]
            self.assertTrue(adv.startswith("Обновите Headswap до 1.1.7"))
            self.assertIn("old_version", c.notes)
        with Case(log, "2.4.4") as c:
            self.assertNotIn("auth_v2_old_version", c.ids)

    def test_events_deduplicated_within_2s(self):
        # одна ошибка abort пишется дважды (лог + WL) — это одно событие
        log = START + ATTACH + (
            "09:01:00.000 ERROR tcp6 VIDEO(8240): Software caused connection abort\n"
            "09:01:00.010 WL tcp close chan=VIDEO why=\"ошибка чтения: Software caused connection abort\"\n")
        with Case(log) as c:
            self.assertEqual(c.facts["m.abort"], 1)
            self.assertNotIn("wifi_vpn_abort", c.ids)

    def test_vw_pause_then_eio(self):
        log = START + ATTACH
        for i, m in enumerate(("10", "20")):
            log += (f"09:{m}:00.000 RX CMD 0x0001800A VIDEO_ENCODER_PAUSE len=0\n"
                    f"09:{m}:05.500 ERROR ошибка записи: IOException: write failed: EIO (I/O error)\n")
        with Case(log, "2.3.4") as c:
            self.assertEqual(c.facts["pause_eio"], 2)
            self.assertIn("vw_pause_eio", c.ids)

    def test_works_note(self):
        log = START + ATTACH + "09:30:00.000 " + resume(1500, "остановлено пользователем", 40000)
        with Case(log) as c:
            self.assertIn("works", c.notes)
            text = logdiag.render(c.facts, c.hits)
            self.assertNotIn("не распознана", text)

    def test_check_none(self):
        f = {"x": None}
        self.assertTrue(logdiag.check(["x", "!=", 0], f))
        self.assertFalse(logdiag.check(["x", "==", 0], f))
        self.assertTrue(logdiag.check(["x", "<", 30], f))
        self.assertTrue(logdiag.check(["v", "<v", "2.4.0"], {"v": "2.3.4-h196test1"}))
        self.assertFalse(logdiag.check(["v", "<v", "2.4.0"], {"v": None}))


if __name__ == "__main__":
    unittest.main()
