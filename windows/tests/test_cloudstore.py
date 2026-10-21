"""2.2.0 암호화 클라우드 저장소(cloudstore.py, docs/PROTOCOL.md §17). 임시 폴더만 쓴다."""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path

import cloudkeys as keys
import cloudstore as cs
import protocol as p

PW = "correct horse battery"


def put(store: cs.Store, folder: str, ring: str, name: str, data: bytes, peer: str = "peerA") -> str:
    up = store.begin_upload(folder, ring, name, len(data), 1_700_000_000_000, peer, os.urandom(16).hex())
    for i in range(0, len(data), 300_000):
        up.write(data[i:i + 300_000])
    return up.commit()


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.base = Path(self.tmp.name)
        self.root = self.base / "ssd" / "MyCloud"
        self.root.mkdir(parents=True)
        (self.root / "keep-me.txt").write_text("사용자 파일", "utf-8")  # 같은 폴더의 다른 파일은 건드리지 않는다
        self.app = self.base / "app"
        self.now = [1_000_000.0]
        self.store = cs.Store(self.root, self.app, clock=lambda: self.now[0])
        self.assertEqual(self.store.state, cs.NEW)
        self.code = self.store.create(PW)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def again(self, app: Path | None = None) -> cs.Store:
        return cs.Store(self.root, app or self.app, clock=lambda: self.now[0])

    def disk_bytes(self) -> bytes:
        return b"".join(x.read_bytes() for x in self.root.rglob("*") if x.is_file() and x.name != "keep-me.txt")

    def test_disk_shows_nothing_readable(self):
        folder = self.store.mkdir(["개인 사진"], {}, "local")
        put(self.store, folder, cs.MAIN, "여권 스캔.jpg", b"PASSPORT-SECRET" * 1000)
        self.store.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        raw = self.disk_bytes()
        for needle in ("개인 사진".encode(), "여권".encode(), b"PASSPORT-SECRET", b".jpg"):
            self.assertNotIn(needle, raw)
        info = json.loads((self.root / cs.STORE_DIR / "store.json").read_text("utf-8"))
        self.assertEqual(set(info), {"format", "v", "id", "created", "password", "recovery"})
        self.assertEqual(info["password"]["alg"], "argon2id")
        self.assertEqual((self.root / "keep-me.txt").read_text("utf-8"), "사용자 파일")

    def test_auto_open_new_pc_password_recovery_and_change(self):
        put(self.store, "", cs.MAIN, "a.txt", b"A")
        self.store.close()
        same_pc = self.again()
        self.assertEqual(same_pc.state, cs.OPEN)  # 이 PC의 Windows 계정 사본으로 자동
        self.assertEqual([x["name"] for x in same_pc.list_children([], {}, "x")], ["a.txt"])
        same_pc.close()
        other = self.again(self.base / "other-pc")  # 새 PC·다시 깐 Windows: 이 PC 사본이 없다
        self.assertEqual(other.state, cs.LOCKED)
        with self.assertRaises(p.ProtocolError) as ctx:
            other.unlock(password="wrong password!")
        self.assertEqual(ctx.exception.code, "store_password")
        other.unlock(password=PW)
        self.assertEqual(other.state, cs.OPEN)
        self.assertTrue((self.base / "other-pc" / "stores" / f"{other.id}.key").exists())
        other.change_password("new password 2026", password=PW)
        other.close()
        third = self.again(self.base / "third-pc")
        with self.assertRaises(p.ProtocolError):
            third.unlock(password=PW)
        third.unlock(recovery=self.code.lower().replace("-", " "))
        self.assertEqual(third.state, cs.OPEN)
        third.close()
        fourth = self.again(self.base / "fourth-pc")
        fourth.unlock(password="new password 2026")
        fourth.close()
        with self.assertRaises(p.ProtocolError):
            cs.Store(self.base / "empty", self.app).create("short")  # 10자 미만

    def test_ranges_and_reader_cross_chunks(self):
        data = os.urandom(cs.CHUNK * 3 + 12345)
        put(self.store, "", cs.MAIN, "v.mp4", data)
        node = self.store.node_at(["v.mp4"], {}, "x")
        self.assertEqual(b"".join(self.store.read(node)), data)
        for offset, length in ((0, 10), (cs.CHUNK - 5, 10), (cs.CHUNK * 2 + 7, cs.CHUNK), (len(data) - 3, 100)):
            self.assertEqual(b"".join(self.store.read(node, offset, length)), data[offset:offset + length])
        reader = self.store.reader(node)
        reader.seek(cs.CHUNK * 3)
        self.assertEqual(reader.read(20), data[cs.CHUNK * 3:cs.CHUNK * 3 + 20])
        reader.close()
        empty = put(self.store, "", cs.MAIN, "empty.bin", b"")
        self.assertEqual(b"".join(self.store.read(self.store.node_at([empty], {}, "x"))), b"")

    def test_tamper_swap_and_truncate_are_detected(self):
        data = os.urandom(cs.CHUNK * 2 + 10)
        put(self.store, "", cs.MAIN, "x.bin", data)
        node = self.store.node_at(["x.bin"], {}, "x")
        path = self.store._object_path(node.meta["o"])
        original = path.read_bytes()
        swapped = original[cs.CT_CHUNK:2 * cs.CT_CHUNK] + original[:cs.CT_CHUNK] + original[2 * cs.CT_CHUNK:]
        for bad in (original[:2 * cs.CT_CHUNK], swapped, original[:100] + bytes([original[100] ^ 1]) + original[101:]):
            path.write_bytes(bad)
            with self.assertRaises(p.ProtocolError):
                b"".join(self.store.read(node))
        path.write_bytes(original)
        self.assertEqual(b"".join(self.store.read(node)), data)

    def test_resume_after_disconnect(self):
        data = os.urandom(cs.CHUNK * 5 + 777)
        xfer = os.urandom(16).hex()
        up = self.store.begin_upload("", cs.MAIN, "big.bin", len(data), 1, "peerA", xfer)
        up.write(data[:cs.CHUNK * 3 + 500])
        up.keep()  # 끊김
        have = self.store.partial_have("peerA", xfer, len(data))
        self.assertEqual(have, cs.CHUNK * 3)  # 들고 있던 청크와 남은 조각은 다시 받는다
        self.assertEqual(self.store.partial_have("peerB", xfer, len(data)), 0)
        self.store.close()
        store = self.again()  # 앱을 다시 켜도
        with self.assertRaises(p.ProtocolError):
            store.resume_upload("peerA", xfer, len(data), have + 1, "", cs.MAIN)
        up = store.resume_upload("peerA", xfer, len(data), have, "", cs.MAIN)
        up.write(data[have:])
        self.assertEqual(up.digest.hexdigest(), hashlib.sha256(data).hexdigest())
        self.assertEqual(up.commit(), "big.bin")
        self.assertEqual(b"".join(store.read(store.node_at(["big.bin"], {}, "x"))), data)
        self.assertEqual(list((self.root / cs.STORE_DIR / "incoming").glob("*")), [])
        store.close()

    def test_names_move_and_trash(self):
        a = self.store.mkdir(["A"], {}, "x")
        self.store.mkdir(["B", "C"], {}, "x")
        self.assertEqual(put(self.store, a, cs.MAIN, "f.txt", b"1"), "f.txt")
        self.assertEqual(put(self.store, a, cs.MAIN, "F.TXT", b"2"), "F (1).TXT")  # 대소문자만 다른 이름도 겹침
        moved, failed = self.store.move([["A", "f.txt"], ["없음"]], ["B", "C"], {}, "x")
        self.assertEqual([x["path"] for x in moved], ["A/f.txt"])
        self.assertEqual({x["path"]: x["code"] for x in failed}, {"없음": "not_found"})
        _, failed = self.store.move([["B"]], ["B", "C"], {}, "x")
        self.assertEqual(failed[0]["code"], "bad_move")
        self.store.delete(["B"], {}, "x")
        self.assertEqual([x["name"] for x in self.store.list_children([], {}, "x")], ["A"])
        [trashed] = self.store.trash_items()
        self.assertEqual(self.store.restore(trashed["id"]), "B")
        self.assertEqual(b"".join(self.store.read(self.store.node_at(["B", "C", "f.txt"], {}, "x"))), b"1")
        self.store.delete(["A"], {}, "x")
        self.now[0] += cs.TRASH_DAYS * 86400 + 10
        self.store.close()
        store = self.again()  # 열 때 30일 지난 휴지통을 비운다
        self.assertEqual(store.trash_items(), [])
        self.assertEqual(store.db.execute("SELECT count(*) FROM objects").fetchone()[0], 1)
        self.assertEqual(sum(1 for x in (self.root / cs.STORE_DIR / "objects").rglob("*") if x.is_file()), 1)
        store.close()

    def test_pin_folder_is_sealed_from_the_store_key(self):
        folder = self.store.mkdir(["비밀"], {}, "x")
        sub = self.store.mkdir(["비밀", "안"], {}, "x")
        put(self.store, sub, cs.MAIN, "일기.txt", b"DIARY")
        code = self.store.lock(["비밀"], "246810")
        self.assertEqual(len(code), 39)
        self.store.close()
        store = self.again()  # 저장소 열쇠만으로는(이 PC에서 자동으로 열려도) 안을 못 본다
        names = {n.name for n in store.nodes.values()}
        self.assertEqual(names, {"비밀"})
        main = store.rings[cs.MAIN]
        for nid, parent, ring, meta in store.db.execute("SELECT id, parent, ring, meta FROM nodes WHERE id != ?",
                                                        (folder,)):
            with self.assertRaises(keys.tink.TinkError):
                main.decrypt(meta, cs._node_ad(nid, parent, cs.MAIN))
        with self.assertRaises(p.ProtocolError) as ctx:
            store.list_children(["비밀"], {}, "peerA")
        self.assertEqual((ctx.exception.code, ctx.exception.kw.get("mount")), ("vault_locked", "비밀"))
        self.assertEqual(store.search([], "일기", "all", {}, "peerA")[0], [])
        for _ in range(cs.MAX_FAILS):
            with self.assertRaises(p.ProtocolError):
                store.open_vault(["비밀"], "attacker", "000000", None)
        with self.assertRaises(p.ProtocolError) as ctx:
            store.open_vault(["비밀"], "attacker", "246810", None)
        self.assertEqual(ctx.exception.code, "vault_wait")
        token = store.open_vault(["비밀"], "peerA", "246810", None)  # 다른 기기는 막히지 않는다
        header = {"token": token}
        self.assertEqual([x["name"] for x in store.list_children(["비밀", "안"], header, "peerA")], ["일기.txt"])
        with self.assertRaises(p.ProtocolError):
            store.list_children(["비밀"], header, "peerB")  # 열쇠는 그 기기에만
        node = store.node_at(["비밀", "안", "일기.txt"], header, "peerA")
        self.assertEqual(b"".join(store.read(node)), b"DIARY")
        moved, _ = store.move([["비밀", "안", "일기.txt"]], [], header, "peerA")  # 잠긴 폴더 밖으로(2.2.1)
        self.assertEqual(moved, [{"path": "비밀/안/일기.txt", "name": "일기.txt"}])
        moved, _ = store.move([["일기.txt"]], ["비밀", "안"], header, "peerA")  # 다시 안으로
        self.assertEqual(moved, [{"path": "일기.txt", "name": "일기.txt"}])
        store.repin(["비밀"], "135791", None, code, "peerA")
        with self.assertRaises(p.ProtocolError):
            store.list_children(["비밀"], header, "peerA")  # PIN을 바꾸면 예전 열쇠는 끝난다
        store.unlock_vault(["비밀"], "135791", "peerA")
        self.assertEqual([x["name"] for x in store.list_children(["비밀", "안"], {}, "x")], ["일기.txt"])
        store.close()
        fresh = self.again()
        self.assertIn("일기.txt", {n.name for n in fresh.nodes.values()})
        fresh.close()

    def test_old_metas_do_not_remain_on_disk_after_lock(self):
        """잠그기 전 저장소 열쇠로 잠겨 있던 meta 암호문(파일 열쇠가 들어 있다)이 DB·WAL 어디에도 남지 않는다."""
        folder = self.store.mkdir(["V"], {}, "x")
        for i in range(30):
            put(self.store, folder, cs.MAIN, f"f{i}.txt", b"x" * 100)
        self.store.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        old = [row[0] for row in self.store.db.execute("SELECT meta FROM nodes WHERE parent=?", (folder,))]
        self.store.lock(["V"], "246810")
        self.store.close()
        raw = b"".join((self.root / n).read_bytes() for n in ("meta.db", "meta.db-wal") if (self.root / n).exists())
        for blob in old:
            self.assertNotIn(blob[5:40], raw)

    def test_vault_delete_is_permanent_and_token_needed(self):
        self.store.mkdir(["V"], {}, "x")
        self.store.lock(["V"], "246810")
        with self.assertRaises(p.ProtocolError):
            self.store.delete(["V"], {}, "x")
        token = self.store.open_vault(["V"], "x", "246810", None)
        inner = self.store.mkdir(["V", "in"], {"token": token}, "x")
        put(self.store, inner, self.store.nodes[inner].ring, "s.txt", b"S")
        self.store.delete(["V", "in", "s.txt"], {"token": token}, "x")
        self.assertEqual(self.store.trash_items(), [])  # 잠긴 폴더 안은 바로 영구히
        self.assertEqual(self.store.db.execute("SELECT count(*) FROM objects").fetchone()[0], 0)

    def test_reset_missing_and_reappear(self):
        put(self.store, "", cs.MAIN, "a.txt", b"A")
        self.store.reset()
        self.assertEqual(self.store.state, cs.NEW)
        self.assertEqual(sorted(x.name for x in self.root.iterdir()), ["keep-me.txt"])
        self.assertFalse(any((self.app / "stores").glob("*.key")))
        self.store.create(PW)
        self.store.close()
        moved = self.base / "ssd-unplugged"
        self.root.parent.rename(moved)  # 외장 SSD를 뺐다
        store = self.again()
        self.assertEqual(store.state, cs.MISSING)
        moved.rename(self.root.parent)  # 다시 꽂았다
        self.assertEqual(store.refresh(), cs.OPEN)
        store.close()

    def test_export_and_import(self):
        src = self.base / "photo.jpg"
        src.write_bytes(os.urandom(2 * cs.CHUNK + 5))
        name = self.store.import_file("", cs.MAIN, src)
        out = self.store.export([name], {}, "local", self.base / "out")
        self.assertEqual(out.read_bytes(), src.read_bytes())

    def test_damaged_meta_row_is_skipped(self):
        put(self.store, "", cs.MAIN, "ok.txt", b"1")
        put(self.store, "", cs.MAIN, "bad.txt", b"2")
        nid = next(n.id for n in self.store.nodes.values() if n.name == "bad.txt")
        self.store.close()
        db = sqlite3.connect(self.root / cs.STORE_DIR / "meta.db")
        db.execute("UPDATE nodes SET meta=? WHERE id=?", (b"garbage" * 10, nid))
        db.commit()
        db.close()
        store = self.again()
        self.assertEqual([x["name"] for x in store.list_children([], {}, "x")], ["ok.txt"])
        store.close()


if __name__ == "__main__":
    unittest.main()
