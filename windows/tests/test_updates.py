"""1.9.0 Tailscale 업데이트(docs/PROTOCOL.md §13): 선반(manifest) 검사, hello 알림, 받기와 SHA-256 확인. 임시 폴더만 쓴다."""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

import protocol as p
import store
import tsnet

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import node as nodemod  # noqa: E402
import updates  # noqa: E402
from test_nodes import IP_A, IP_B, USER, FakeTS, wait_for  # noqa: E402


def put_shelf(app_dir: Path, name: str, data: bytes, version: str = "9.9.9", sha: str | None = None,
              target: str = "pc", **extra) -> Path:
    shelf = app_dir / "updates"
    shelf.mkdir(parents=True, exist_ok=True)
    (shelf / name).write_bytes(data)
    entry = {"version": version, "file": name, "size": len(data),
             "sha256": sha or hashlib.sha256(data).hexdigest(), **extra}
    manifest = shelf / "manifest.json"
    current = json.loads(manifest.read_text("utf-8")) if manifest.exists() else {}
    current[target] = entry
    manifest.write_text(json.dumps(current), "utf-8")
    return shelf


class ShelfTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_valid_entries_are_offered(self):
        put_shelf(self.dir, "TailHop_Setup_9.9.9.exe", b"exe")
        put_shelf(self.dir, "TailHop-9.9.9.apk", b"apk", target="android", code=99)
        offered = updates.Shelf(self.dir).advert()
        self.assertEqual(offered, {"pc": {"version": "9.9.9", "size": 3},
                                   "android": {"version": "9.9.9", "size": 3, "code": 99}})

    def test_bad_entries_are_ignored(self):
        for name, extra in (("..\\evil.exe", {}), ("CON", {}), ("ok.exe", {"version": "x"}),
                            ("ok.exe", {"size": 999}), ("ok.exe", {"sha256": "zz"})):
            with self.subTest(name=name, extra=extra):
                shelf = self.dir / "updates"
                shelf.mkdir(exist_ok=True)
                (shelf / "ok.exe").write_bytes(b"abc")
                entry = {"version": "1.0.0", "file": name, "size": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}
                entry.update(extra)
                (shelf / "manifest.json").write_text(json.dumps({"pc": entry}), "utf-8")
                self.assertEqual(updates.Shelf(self.dir).advert(), {})

    def test_android_needs_version_code(self):
        put_shelf(self.dir, "a.apk", b"apk", target="android")
        self.assertEqual(updates.Shelf(self.dir).advert(), {})

    def test_version_compare(self):
        self.assertTrue(updates.newer("1.10.0", "1.9.9"))
        self.assertFalse(updates.newer("1.9.0", "1.9.0"))
        self.assertFalse(updates.newer("abc", "1.0"))


class TwoNodeUpdateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.table = {IP_A: tsnet.Peer("nodeA", USER, "pc-a"), IP_B: tsnet.Peer("nodeB", USER, "pc-b")}
        self.nodes = []
        self.a = self.make_node("A", IP_A, "nodeA")
        self.b = self.make_node("B", IP_B, "nodeB")
        self.b.join_pairing(self.a.begin_pairing())
        self.assertTrue(wait_for(lambda: "nodeB" in self.a.pairings))

    def tearDown(self):
        for n in self.nodes:
            n.stop()
        self.tmp.cleanup()

    def make_node(self, label, ip, sid):
        ts = FakeTS(tsnet.SelfInfo(ip=ip, user_id=USER, name=f"PC-{label}", stable_id=sid), self.table)
        n = nodemod.Node(lambda k, d: None, app_dir=self.root / label / "app", ts=ts, port=0)
        n.settings["save_dir"] = str(self.root / label / "recv")
        n.start()
        self.assertTrue(wait_for(lambda: n.listener is not None and n.me is not None))
        self.nodes.append(n)
        return n

    def test_offer_and_fetch(self):
        data = os.urandom(p.CHUNK * 2 + 17)
        put_shelf(self.root / "A" / "app", "TailHop_Setup_9.9.9.exe", data)
        self.assertEqual(self.b.peer_updates("nodeA"), {"pc": {"version": "9.9.9", "size": len(data), "code": 0}})
        self.assertEqual(self.a.peer_updates("nodeB"), {})  # B는 선반이 없다
        seen = []
        path, version = self.b.fetch_update("nodeA", "pc", self.root / "dl", lambda d, t: seen.append(d))
        self.assertEqual((path.read_bytes(), version), (data, "9.9.9"))
        self.assertEqual(seen[-1], len(data))

    def test_tampered_file_is_rejected_and_removed(self):
        data = b"real installer"
        put_shelf(self.root / "A" / "app", "s.exe", data, sha=hashlib.sha256(b"other").hexdigest())
        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.fetch_update("nodeA", "pc", self.root / "dl")
        self.assertEqual(ctx.exception.code, "hash_mismatch")
        self.assertEqual(list((self.root / "dl").iterdir()), [])

    def test_missing_target_is_not_found(self):
        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.fetch_update("nodeA", "android", self.root / "dl")
        self.assertEqual(ctx.exception.code, "not_found")


if __name__ == "__main__":
    unittest.main()
