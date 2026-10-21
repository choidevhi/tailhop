"""2.2.0 암호화 저장소 보안 점검(15건)에서 고친 것들의 회귀 테스트. 임시 폴더만 쓴다."""
from __future__ import annotations

import json
import os
import socket
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

import cloudkeys as keys
import cloudstore as cs
import hub as hubmod
import media
import protocol as p
from storehelp import PW, names, read, seed


def junction(link: Path, target: Path) -> bool:
    done = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)], capture_output=True)
    return done.returncode == 0


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.base = Path(self.tmp.name)
        self.root = self.base / "TailHop Cloud"
        self.root.mkdir()
        self.victim = self.base / "victim"
        self.victim.mkdir()
        (self.victim / "tax-2026.pdf").write_text("TAX", "utf-8")
        self.app = self.base / "app"
        self.now = [1_000_000.0]

    def tearDown(self):
        self.tmp.cleanup()

    def store(self) -> cs.Store:
        return cs.Store(self.root, self.app, clock=lambda: self.now[0])

    def test_users_own_objects_incoming_folders_survive_create_open_and_reset(self):
        """1번: 고른 폴더에 원래 있던 objects·incoming 폴더의 파일을 지우지 않는다."""
        for rel in ("incoming/my-notes.txt", "objects/photo.jpg", "objects/ab/" + "ab" + "0" * 30):
            (self.root / rel).parent.mkdir(parents=True, exist_ok=True)
            (self.root / rel).write_text("USER", "utf-8")
        st = self.store()
        st.create(PW)
        st.close()
        st = self.store()
        st.reset()
        for rel in ("incoming/my-notes.txt", "objects/photo.jpg", "objects/ab/" + "ab" + "0" * 30):
            self.assertEqual((self.root / rel).read_text("utf-8"), "USER", rel)
        self.assertFalse((self.root / cs.STORE_DIR).exists())

    def test_junction_planted_inside_store_is_not_followed(self):
        """1번: .tailhop-store 안에 심은 정션 너머의 파일을 정리·리셋·옮기기가 지우거나 복사하지 않는다."""
        st = self.store()
        st.create(PW)
        seed(st, "a.txt", b"A")
        st.close()
        if not junction(self.root / cs.STORE_DIR / "objects" / "zz", self.victim):
            self.skipTest("정션을 만들 수 없음")
        st = self.store()  # 열 때 정리
        self.assertEqual(read(st, "a.txt"), b"A")
        cs.relocate(st, self.base / "ssd")
        self.assertFalse((self.base / "ssd" / cs.STORE_DIR / "objects" / "zz").exists())
        st.close()
        self.assertTrue((self.victim / "tax-2026.pdf").exists())
        st2 = cs.Store(self.base / "ssd", self.app)
        self.assertEqual(read(st2, "a.txt"), b"A")
        st2.reset()
        self.assertTrue((self.victim / "tax-2026.pdf").exists())

    def test_tampered_oid_columns_cannot_point_outside(self):
        """2번: 평문 열 oid를 바꿔 다른 파일을 가리키게 해도 열기·휴지통 비우기가 그 파일을 지우지 않는다."""
        st = self.store()
        st.create(PW)
        seed(st, "doc.txt", b"D")
        st.delete(["doc.txt"], {}, "x")
        st.close()
        db = sqlite3.connect(self.root / cs.STORE_DIR / "meta.db")
        target = os.path.relpath(self.victim / "tax-2026.pdf", self.root / cs.STORE_DIR / "objects" / "xx")
        db.execute("UPDATE objects SET oid=?", (target,))
        db.execute("INSERT INTO partials VALUES(?, 'main', 'x', 0, x'00', 0)", (str(self.victim / "tax-2026"),))
        db.commit()
        db.close()
        st = self.store()
        st.empty_trash()
        self.now[0] += 40 * 86400
        st.close_expired()
        st.close()
        self.assertTrue((self.victim / "tax-2026.pdf").exists())

    def test_trigger_or_view_in_meta_db_is_refused(self):
        st = self.store()
        st.create(PW)
        st.close()
        db = sqlite3.connect(self.root / cs.STORE_DIR / "meta.db")
        db.execute("CREATE TRIGGER t AFTER INSERT ON nodes BEGIN DELETE FROM nodes; END")
        db.commit()
        db.close()
        with self.assertRaises(p.ProtocolError) as ctx:
            self.store()
        self.assertEqual(ctx.exception.code, "store_state")

    def test_locking_while_receiving_does_not_leak_main_rows_into_vault(self):
        """3번: 받을 자리를 정한 뒤(put_target) 그 폴더가 잠기면, 그 자리로 받기를 시작할 수 없다.
        받는 중이면 잠그기 자체가 vault_busy다."""
        st = self.store()
        st.create(PW)
        folder, ring = st.folder_for_put(["Family"], {}, "x")  # 받을 자리를 정했다
        up = st.begin_upload(folder, ring, "a.jpg", 1, 1, "x", os.urandom(16).hex())
        with self.assertRaises(p.ProtocolError) as ctx:
            st.lock(["Family"], "246810")  # 받는 중에는 잠그지 않는다
        self.assertEqual(ctx.exception.code, "vault_busy")
        up.abort()
        st.lock(["Family"], "246810")  # 자리를 정한 다음 잠겼다
        st.open_vault(["Family"], "x", "246810", None)  # 누가 PIN으로 열어 두었다
        with self.assertRaises(p.ProtocolError) as ctx:
            st.begin_upload(folder, ring, "passport.jpg", 8, 1, "x", os.urandom(16).hex())
        self.assertEqual(ctx.exception.code, "vault_busy")
        rows = st.db.execute("SELECT count(*) FROM nodes WHERE parent=? AND ring=?", (folder, cs.MAIN)).fetchone()[0]
        self.assertEqual(rows, 0)
        st.close()

    def test_restore_into_folder_locked_meanwhile_goes_to_top(self):
        """4번: 지운 뒤 그 폴더가 잠겼으면 휴지통에서 되돌려도 잠긴 폴더 안이 아니라 맨 위로 간다."""
        st = self.store()
        st.create(PW)
        seed(st, "Family/id-card.jpg", b"ID")
        st.delete(["Family", "id-card.jpg"], {}, "x")
        st.lock(["Family"], "246810")
        [item] = st.trash_items()
        st.restore(item["id"])
        self.assertIn("id-card.jpg", names(st))
        token = st.open_vault(["Family"], "x", "246810", None)
        self.assertEqual(st.list_children(["Family"], {"token": token}, "x"), [])
        st.close()

    def test_relocate_into_itself_is_refused(self):
        """8번: 저장소를 자기 안으로 옮기면 거부한다(저장소가 통째로 사라지지 않게)."""
        st = self.store()
        st.create(PW)
        seed(st, "a.txt", b"A")
        for bad in (self.root, self.root / "inside", self.root / cs.STORE_DIR / "objects", self.base):
            with self.assertRaises(p.ProtocolError):
                cs.relocate(st, bad)
        self.assertEqual(st.state, cs.OPEN)
        self.assertEqual(read(st, "a.txt"), b"A")
        st.close()

    def test_partials_keep_no_peer_or_size_in_plaintext(self):
        st = self.store()
        st.create(PW)
        cols = [row[1] for row in st.db.execute("PRAGMA table_info(partials)")]
        self.assertNotIn("peer", cols)
        self.assertNotIn("size", cols)
        x = os.urandom(16).hex()
        up = st.begin_upload("", cs.MAIN, "a.bin", cs.CHUNK * 2, 1, "phone-1", x)
        up.write(os.urandom(cs.CHUNK + 5))
        up.keep()
        self.assertEqual(st.partial_have("phone-1", x, cs.CHUNK * 2), cs.CHUNK)
        self.assertEqual(st.partial_have("phone-2", x, cs.CHUNK * 2), 0)
        with self.assertRaises(p.ProtocolError):
            st.resume_upload("phone-2", x, cs.CHUNK * 2, cs.CHUNK, "", cs.MAIN)
        st.close()

    def test_weakened_or_huge_kdf_values_are_refused(self):
        h = keys.new_keyset()
        rec = keys.seal_secret(h, "long password 123", b"ad")
        for change in ({"m": 4 * 1024 * 1024}, {"t": 64}, {"p": 64}, {"m": 1024}):
            self.assertIsNone(keys.open_secret({**rec, **change}, "long password 123", b"ad"), change)
        self.assertIsNotNone(keys.open_secret(rec, "long password 123", b"ad"))

    def test_moved_drive_is_followed_only_with_same_volume_serial(self):
        self.assertIsNone(cs.find_moved("0" * 32, Path("E:/TailHop Cloud"), None))
        self.assertIsNone(cs.find_moved("0" * 32, Path("E:/TailHop Cloud"), 12345))


class ShredTests(unittest.TestCase):
    def test_permanent_delete_leaves_no_file_key_on_disk(self):
        """영구 삭제: 파일 열쇠가 든 meta 줄이 DB·WAL 어디에도 남지 않는다(조각이 디스크에 남아도 열 수 없다)."""
        tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        root = Path(tmp.name) / "c"
        root.mkdir()
        st = cs.Store(root, Path(tmp.name) / "app")
        st.create(PW)
        seed(st, "secret.pdf", b"SECRET" * 1000)
        st.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        node = st.node_at(["secret.pdf"], {}, "x")
        meta = st.db.execute("SELECT meta FROM nodes WHERE id=?", (node.id,)).fetchone()[0]
        key = node.meta["k"].encode()
        st.delete(["secret.pdf"], {}, "x")  # 휴지통(아직 암호문 그대로)
        trashed = st.db.execute("SELECT meta FROM nodes WHERE id=?", (node.id,)).fetchone()[0]
        st.empty_trash()
        # 앱이 켜져 있는 동안(DB를 닫기 전)에 본다: 닫으면 SQLite가 WAL을 비워 틈이 가려진다
        disk = b"".join(x.read_bytes() for x in (root / cs.STORE_DIR).rglob("*") if x.is_file())
        st.close()
        for blob in (meta, trashed):
            self.assertNotIn(blob[5:60], disk)
        self.assertNotIn(key, disk)
        self.assertNotIn(b"SECRET", disk)
        self.assertEqual(sum(1 for x in (root / cs.STORE_DIR / "objects").rglob("*") if x.is_file()), 0)
        tmp.cleanup()


class RangeServerReviewTests(unittest.TestCase):
    def test_416_has_length_and_closes(self):
        """12번: 맞지 않는 범위에 416과 Content-Length: 0, 연결을 닫는다(처리 스레드가 묶이지 않게)."""
        server = media.RangeServer()
        url, _ = server.register(media.Source(10, lambda o, n: iter([b"0123456789"[o:o + n]])))
        host, port = server.httpd.server_address
        path = url.rsplit("/", 1)[1]
        for rng in ("bytes=5-3", "bytes=20-"):
            with socket.create_connection((host, port), timeout=5) as s:
                s.sendall(f"GET /{path} HTTP/1.1\r\nHost: x\r\nRange: {rng}\r\n\r\n".encode())
                data = b""
                while True:
                    chunk = s.recv(4096)
                    if not chunk:
                        break  # 서버가 닫았다
                    data += chunk
            self.assertIn(b"416", data.split(b"\r\n")[0])
            self.assertIn(b"Content-Length: 0", data)
        server.close()

    def test_local_stream_rechecks_vault_token(self):
        """5번: 재생 주소는 읽을 때마다 잠긴 폴더 열쇠를 다시 확인한다(닫힌 뒤에는 그 주소로도 못 읽는다)."""
        from cloud_source import LOCAL, Source
        tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        root = Path(tmp.name) / "c"
        root.mkdir()
        now = [1000.0]
        st = cs.Store(root, Path(tmp.name) / "app", clock=lambda: now[0])
        st.create(PW)
        seed(st, "V/movie.mp4", b"VIDEO-PLAINTEXT" * 100)
        st.lock(["V"], "246810")
        token = st.open_vault(["V"], "local", "246810", None)

        class FakeNode:
            cloud_state = "open"

            @staticmethod
            def hub():
                return hubmod.Hub(st)

            @staticmethod
            def token_for(sid, path):
                return token

        src = Source(FakeNode, LOCAL).stream("V/movie.mp4")
        self.assertEqual(b"".join(src.read(0, 15)), b"VIDEO-PLAINTEXT")
        now[0] += cs.TOKEN_TTL + 5
        st.close_expired()
        with self.assertRaises(p.ProtocolError) as ctx:
            b"".join(src.read(0, 15))
        self.assertEqual(ctx.exception.code, "vault_locked")
        st.close()
        tmp.cleanup()


class StoreFolderTests(unittest.TestCase):
    def test_store_lives_in_one_hidden_subfolder(self):
        tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        root = Path(tmp.name) / "E-drive"
        root.mkdir()
        st = cs.Store(root, Path(tmp.name) / "app")
        st.create(PW)
        seed(st, "x/y.txt", b"Y")
        st.close()
        self.assertEqual(sorted(x.name for x in root.iterdir()), [cs.STORE_DIR])
        attrs = os.stat(root / cs.STORE_DIR).st_file_attributes
        self.assertTrue(attrs & 0x2)  # 숨김
        self.assertEqual(json.loads((root / cs.STORE_DIR / "store.json").read_text("utf-8"))["format"], cs.FORMAT)
        tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
