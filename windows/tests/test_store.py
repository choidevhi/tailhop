"""저장 형식 이전(v1.2 이전 폰 전용 → 폰/PC 공통) 검사. 모두 임시 폴더에서만 한다."""
from __future__ import annotations

import base64
import json
import tempfile
import unittest
from pathlib import Path

import store

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import node as nodemod  # noqa: E402

S1, S2 = bytes(range(32)), bytes(range(1, 33))


def legacy_entry(ip: str, port: int, name: str, sid: str, secret: bytes) -> dict:
    return {"phone_ip": ip, "phone_port": port, "phone_name": name, "phone_stable_id": sid,
            "secret_dpapi": base64.b64encode(store._dpapi(secret, protect=True)).decode()}


class OfflineTS:
    @staticmethod
    def self_info():
        raise RuntimeError("offline")


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "app"
        self.dir.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_v12_list_migrates_to_phone_kind(self):
        old = [legacy_entry("100.1.1.1", 47101, "Galaxy", "nP1", S1), legacy_entry("100.1.1.2", 47101, "Note", "nP2", S2)]
        (self.dir / "pairings.json").write_text(json.dumps(old), "utf-8")
        items = store.load_pairings(self.dir)
        self.assertEqual([(x.ip, x.port, x.name, x.stable_id, x.secret, x.kind, x.role) for x in items],
                         [("100.1.1.1", 47101, "Galaxy", "nP1", S1, "phone", ""), ("100.1.1.2", 47101, "Note", "nP2", S2, "phone", "")])
        saved = json.loads((self.dir / "pairings.json").read_text("utf-8"))
        self.assertTrue(all("phone_ip" not in d and d["kind"] == "phone" and "secret_dpapi" in d for d in saved))
        self.assertNotIn(S1.hex(), (self.dir / "pairings.json").read_text("utf-8"))
        # 옮긴 뒤에는 옛 형식 사본(해제한 기기의 비밀까지 담김)을 남기지 않는다.
        self.assertFalse((self.dir / "pairings.json.v1.bak").exists())
        self.assertEqual([x.stable_id for x in store.load_pairings(self.dir)], ["nP1", "nP2"])
        self.assertFalse((self.dir / "pairings.json.v1.bak").exists())

    def test_stale_backup_from_older_version_is_removed(self):
        """1.5.0 이전이 남긴 pairings.json.v1.bak은 새 형식을 제대로 읽으면 지운다."""
        old = [legacy_entry("100.1.1.1", 47101, "Galaxy", "nP1", S1), legacy_entry("100.1.1.2", 47101, "Gone", "nGone", S2)]
        (self.dir / "pairings.json.v1.bak").write_text(json.dumps(old), "utf-8")
        store.save_pairings([store.Pairing("100.1.1.1", 47101, "Galaxy", "nP1", S1)], self.dir)
        self.assertEqual([x.stable_id for x in store.load_pairings(self.dir)], ["nP1"])
        self.assertFalse((self.dir / "pairings.json.v1.bak").exists())

    def test_broken_pairings_keep_backup(self):
        """새 파일을 못 읽으면(손상) 사본을 지우지 않는다(복구할 길을 남김)."""
        (self.dir / "pairings.json").write_text("{broken", "utf-8")
        (self.dir / "pairings.json.v1.bak").write_text("[]", "utf-8")
        self.assertEqual(store.load_pairings(self.dir), [])
        self.assertTrue((self.dir / "pairings.json.v1.bak").exists())

    def test_private_files_are_user_only(self):
        """_write_private: 상속을 끊고 이 사용자 한 명에게만 권한을 준다."""
        import subprocess
        store.save_pairings([store.Pairing("100.1.1.1", 47101, "Galaxy", "nP1", S1)], self.dir)
        sddl = subprocess.run(["powershell", "-NoProfile", "-Command",
                               f"(Get-Acl -LiteralPath '{self.dir / 'pairings.json'}').Sddl"],
                              capture_output=True, text=True, timeout=60).stdout.strip()
        dacl = sddl[sddl.index("D:"):]
        self.assertTrue(dacl.startswith("D:P"), dacl)  # 상속 끊김
        self.assertEqual(dacl.count("(A;"), 1, dacl)  # 허용 항목 하나
        # SDDL may print a well-known alias (e.g. "LA" for the built-in Administrator on CI), so ask for the SID itself.
        sids = subprocess.run(["powershell", "-NoProfile", "-Command",
                               f"(Get-Acl -LiteralPath '{self.dir / 'pairings.json'}').GetAccessRules($true, $false, "
                               "[System.Security.Principal.SecurityIdentifier]) | ForEach-Object { $_.IdentityReference.Value }"],
                              capture_output=True, text=True, timeout=60).stdout.split()
        self.assertEqual(sids, [store._current_user_sid()])

    def test_explorer_command_quotes_whole_path(self):
        cmd = store.explorer_select_command(Path(r"C:\Users\me\Downloads\TailHop\a,b,%PATH%,c.txt"))
        self.assertTrue(cmd.endswith(r' /select,"C:\Users\me\Downloads\TailHop\a,b,%PATH%,c.txt"'), cmd)
        self.assertTrue(cmd.startswith('"') and cmd.split('"')[1].lower().endswith("explorer.exe"))
        self.assertEqual(cmd.count('"'), 4)  # 실행 파일과 경로, 두 덩어리뿐
        self.assertIsNone(store.explorer_select_command(Path('C:\\x\\a" /e,"C:\\Windows\\b.txt')))

    def test_v10_single_file_migrates(self):
        (self.dir / "pairing.json").write_text(json.dumps(legacy_entry("100.1.1.1", 47101, "Galaxy", "nP1", S1)), "utf-8")
        items = store.load_pairings(self.dir)
        self.assertEqual((items[0].kind, items[0].secret), ("phone", S1))
        self.assertFalse((self.dir / "pairing.json").exists())
        self.assertEqual(json.loads((self.dir / "pairings.json").read_text("utf-8"))[0]["stable_id"], "nP1")

    def test_new_format_roundtrip_with_pc(self):
        items = [store.Pairing("100.1.1.1", 47101, "Galaxy", "nP1", S1),
                 store.Pairing("100.1.1.9", 47100, "Desk", "nPC", S2, "pc", "joiner")]
        store.save_pairings(items, self.dir)
        self.assertEqual(store.load_pairings(self.dir), items)

    def test_invalid_role_rejected(self):
        store.save_pairings([store.Pairing("100.1.1.9", 47100, "Desk", "nPC", S2, "pc", "")], self.dir)
        self.assertEqual(store.load_pairings(self.dir), [])

    def test_legacy_history_adopted_by_first_phone(self):
        (self.dir / "pairings.json").write_text(json.dumps([legacy_entry("100.1.1.1", 47101, "Galaxy", "nP1", S1)]), "utf-8")
        history = [{"time": 1.0, "dir": "in", "kind": "text", "text": "옛날"},
                   {"time": 2.0, "dir": "out", "kind": "text", "text": "v1.2", "peer": "Galaxy", "peer_id": "nP1"}]
        (self.dir / "history.json").write_text(json.dumps(history, ensure_ascii=False), "utf-8")
        n = nodemod.Node(lambda *_: None, app_dir=self.dir, ts=OfflineTS)
        self.assertEqual([i["text"] for i in n.history.for_peer("nP1")], ["옛날", "v1.2"])
        saved = json.loads((self.dir / "history.json").read_text("utf-8"))
        self.assertEqual([(i["peer_id"], i.get("peer_kind")) for i in saved], [("nP1", "phone"), ("nP1", None)])

    def test_node_uses_only_given_dir(self):
        n = nodemod.Node(lambda *_: None, app_dir=self.dir, ts=OfflineTS)
        n.set_save_dir(str(self.dir / "recv"))
        self.assertTrue((self.dir / "settings.json").exists())
        self.assertFalse(store.APP_DIR.exists())


class HistoryTextTests(unittest.TestCase):
    """긴 텍스트는 texts/에 따로 두고 기록(파일·메모리)에는 앞부분만 남긴다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name) / "app"

    def tearDown(self):
        self.tmp.cleanup()

    def test_long_text_kept_outside_list(self):
        h = store.History(self.dir)
        long_text = "가나다라" * 100_000  # 40만 자
        item = h.add("in", "text", text=long_text, peer_id="P1")
        short = h.add("out", "text", text="짧은 글", peer_id="P1")
        self.assertEqual(len(item["text"]), store.TEXT_INLINE)
        self.assertEqual(item["text_len"], len(long_text))
        self.assertNotIn("text_file", short)
        self.assertLess((self.dir / "history.json").stat().st_size, 20_000)
        self.assertEqual(h.full_text(item), long_text)
        self.assertEqual(h.full_text(short), "짧은 글")
        again = store.History(self.dir)
        self.assertEqual(again.full_text(again.for_peer("P1")[0]), long_text)

    def test_old_inline_history_is_migrated(self):
        self.dir.mkdir(parents=True)
        long_text = "x" * 50_000
        (self.dir / "history.json").write_text(json.dumps(
            [{"time": 1.0, "dir": "in", "kind": "text", "text": long_text, "peer_id": "P1"},
             {"time": 2.0, "dir": "in", "kind": "file", "name": "a.txt", "path": "C:/a.txt", "size": 1, "peer_id": "P1"}]),
            "utf-8")
        h = store.History(self.dir)
        self.assertEqual(len(h.items[0]["text"]), store.TEXT_INLINE)
        self.assertEqual(h.full_text(h.items[0]), long_text)
        self.assertLess((self.dir / "history.json").stat().st_size, 5_000)
        self.assertEqual(len(list((self.dir / "texts").glob("*.txt"))), 1)

    def test_trim_and_clear_remove_text_files(self):
        h = store.History(self.dir)
        old_limit = store.HISTORY_LIMIT
        store.HISTORY_LIMIT = 3
        try:
            for i in range(5):
                h.add("in", "text", text=str(i) * 5000, peer_id="P1" if i % 2 else "P2")
            self.assertEqual(len(h.items), 3)
            self.assertEqual(len(list((self.dir / "texts").glob("*.txt"))), 3)
            h.clear_peer("P2")
            self.assertEqual(len(list((self.dir / "texts").glob("*.txt"))), 1)
            h.clear()
            self.assertEqual(list((self.dir / "texts").glob("*.txt")), [])
        finally:
            store.HISTORY_LIMIT = old_limit

    def test_corrupt_history_keeps_text_files(self):
        h = store.History(self.dir)
        h.add("in", "text", text="z" * 5000, peer_id="P1")
        (self.dir / "history.json").write_text("{broken", "utf-8")
        again = store.History(self.dir)
        self.assertEqual(again.items, [])
        self.assertEqual(len(list((self.dir / "texts").glob("*.txt"))), 1)  # 못 읽었으면 지우지 않는다

    def test_tampered_text_file_name_ignored(self):
        h = store.History(self.dir)
        item = {"kind": "text", "text": "preview", "text_file": r"..\..\settings.json"}
        self.assertEqual(h.full_text(item), "preview")


if __name__ == "__main__":
    unittest.main()
