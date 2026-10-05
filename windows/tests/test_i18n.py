"""번역 표 검사: 모든 언어에 같은 키가 있고 자리표시자가 맞는지, 코드가 쓰는 키가 표에 있는지,
상대에게 보내는 오류 문장은 화면 언어와 상관없이 한국어(예전 버전 호환)인지."""
from __future__ import annotations

import json
import re
import socket
import string
import unittest
from pathlib import Path

import i18n
import protocol as p

ROOT = Path(__file__).resolve().parents[1]
# 언어마다 쓰는 자리표시자가 달라도 되는 키: 코드가 넘기는 값의 집합 안이면 된다.
FLEXIBLE = {"date.header": {"month", "day", "weekday", "month_name"}}


def placeholders(value: str) -> set[str]:
    return {field for _, field, _, _ in string.Formatter().parse(value) if field}


class TableTests(unittest.TestCase):
    def setUp(self):
        self.tables = {lang: i18n.table(lang) for lang in i18n.LANGS}

    def tearDown(self):
        i18n.set_language(i18n.SYSTEM)

    def test_every_key_in_every_language(self):
        reference = set(self.tables["ko"])
        for lang, table in self.tables.items():
            self.assertEqual(set(table), reference, lang)
            for key, value in table.items():
                self.assertIsInstance(value, str, (lang, key))
                self.assertTrue(value.strip(), (lang, key))

    def test_placeholders_match(self):
        for key, ko_value in self.tables["ko"].items():
            for lang, table in self.tables.items():
                got = placeholders(table[key])
                if key in FLEXIBLE:
                    self.assertLessEqual(got, FLEXIBLE[key], (lang, key))
                else:
                    self.assertEqual(got, placeholders(ko_value), (lang, key))
                # 실제로 꺼내 쓸 수 있어야 한다(중괄호 짝, 형식 오류 없음).
                table[key].format(**{name: "x" for name in FLEXIBLE.get(key, placeholders(ko_value))})

    def test_lists(self):
        for lang, table in self.tables.items():
            self.assertEqual(len(table["date.weekdays"].split(",")), 7, lang)
            self.assertEqual(len(table["date.months"].split(",")), 12, lang)

    def test_keys_used_in_code_exist(self):
        """코드에서 t("…")·ProtocolError("…")로 쓰는 키가 모두 표에 있다."""
        used: set[str] = set()
        for path in ROOT.glob("*.py"):
            source = path.read_text("utf-8")
            used |= set(re.findall(r'\bt\("([a-z_]+\.[a-z_.]+)"', source))
            used |= {"err." + c for c in re.findall(r'ProtocolError\("([a-z_]+)"', source)}
            used |= set(re.findall(r'"((?:status|tray|menu|pair|progress|flash)\.[a-z_]+)"', source))
        self.assertGreater(len(used), 100)
        missing = sorted(k for k in used if k not in self.tables["ko"])
        self.assertEqual(missing, [])

    def test_wire_texts_unchanged_from_14(self):
        """1.4가 보내던 한국어 오류 문장 그대로(예전 PC·Android가 화면에 그대로 띄운다)."""
        ko = self.tables["ko"]
        self.assertEqual(ko["err.replay"], "이미 받은 메시지입니다(재전송 차단).")
        self.assertEqual(ko["err.clock_skew"], "시간이 맞지 않는 메시지입니다(기기 시계를 확인하세요).")
        self.assertEqual(ko["err.file_incomplete"], "파일이 덜 왔습니다.")


class LanguageTests(unittest.TestCase):
    def tearDown(self):
        i18n.set_language(i18n.SYSTEM)

    def test_windows_langid_mapping(self):
        cases = {0x0412: "ko", 0x0409: "en", 0x0809: "en", 0x0411: "ja", 0x0804: "zh_CN", 0x1004: "zh_CN",
                 0x0404: "en", 0x0C04: "en", 0x040C: "en", 0x0407: "en"}
        for langid, lang in cases.items():
            self.assertEqual(i18n.from_langid(langid), lang, hex(langid))

    def test_choice_and_fallback(self):
        self.assertEqual(i18n.set_language("ja"), "ja")
        self.assertEqual(i18n.t("menu.pause"), "受信を停止")
        self.assertIn(i18n.set_language("system"), i18n.LANGS)
        self.assertIn(i18n.set_language("fr"), i18n.LANGS)  # 모르는 값은 시스템 언어로
        self.assertEqual(i18n.text("en", "no.such.key"), "no.such.key")

    def test_protocol_error_shows_ui_language_but_sends_korean(self):
        i18n.set_language("en")
        err = p.ProtocolError("replay")
        self.assertEqual(str(err), "This message was already received (replay blocked).")
        self.assertEqual(err.wire_text(), "이미 받은 메시지입니다(재전송 차단).")
        i18n.set_language("zh_CN")
        self.assertEqual(str(err), "这条消息已经收到过（已阻止重放）。")


class ReplyTests(unittest.TestCase):
    def tearDown(self):
        i18n.set_language(i18n.SYSTEM)

    def reply(self, body: dict):
        a, b = socket.socketpair()
        with a, b:
            key = bytes(32)
            client = p.open_client(a, p.KIND_MSG, key)
            _, prefix = p.read_prefix(b)
            server = p.open_server(b, prefix, key)
            server.send(json.dumps(body).encode("utf-8"), last=True)
            return p.read_reply(client)

    def test_known_code_is_translated(self):
        i18n.set_language("en")
        with self.assertRaises(p.ProtocolError) as ctx:
            self.reply({"ok": False, "error": "이미 받은 메시지입니다(재전송 차단).", "code": "replay"})
        self.assertEqual(ctx.exception.code, "replay")
        self.assertIn("replay blocked", str(ctx.exception))

    def test_old_peer_text_is_shown_as_is(self):
        """1.4 PC·Android 1.3은 code 없이 한국어 문장만 보낸다 → 그 문장을 그대로 보여 준다."""
        i18n.set_language("en")
        with self.assertRaises(p.ProtocolError) as ctx:
            self.reply({"ok": False, "error": "폰에 저장 권한이 없습니다."})
        self.assertEqual(ctx.exception.code, "peer_refused_reason")
        self.assertEqual(str(ctx.exception), "The other device refused: 폰에 저장 권한이 없습니다.")

    def test_unknown_or_bad_code_falls_back(self):
        with self.assertRaises(p.ProtocolError) as ctx:
            self.reply({"ok": False, "code": "../../evil", "error": "x" * 500})
        self.assertEqual(ctx.exception.code, "peer_refused_reason")
        self.assertEqual(len(ctx.exception.kw["reason"]), 200)
        with self.assertRaises(p.ProtocolError) as ctx:
            self.reply({"ok": False})
        self.assertEqual(ctx.exception.code, "peer_refused")

    def test_send_reply_carries_code(self):
        a, b = socket.socketpair()
        with a, b:
            key = bytes(32)
            client = p.open_client(a, p.KIND_MSG, key)
            _, prefix = p.read_prefix(b)
            p.send_reply(p.open_server(b, prefix, key), False, p.ProtocolError("file_incomplete"))
            raw, last = client.recv()
        body = json.loads(raw)
        self.assertEqual(body, {"ok": False, "error": "파일이 덜 왔습니다.", "code": "file_incomplete"})


if __name__ == "__main__":
    unittest.main()
