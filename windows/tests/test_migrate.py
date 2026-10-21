"""2.2.0 옮기기: 2.1까지의 평문 클라우드 폴더와 예전 잠긴 폴더를 암호화 저장소로(migrate.py). 임시 폴더만 쓴다."""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import cloudstore as cs
import migrate
import vault
from storehelp import PW, names, read
from test_vault import wait_state


class MigrateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "TailHop Cloud"
        (self.root / "사진" / "여름").mkdir(parents=True)
        (self.root / "사진" / "여름" / "바다.jpg").write_bytes(os.urandom(cs.CHUNK + 9))
        (self.root / "메모.txt").write_text("PLAIN-MEMO", "utf-8")
        (self.root / "받는중.bin.part").write_bytes(b"partial")
        hidden = self.root / "desktop.ini"
        hidden.write_text("[x]", "utf-8")
        subprocess.run(["attrib", "+h", "+s", str(hidden)], check=True, capture_output=True)
        self.app = Path(self.tmp.name) / "app"

    def tearDown(self):
        self.tmp.cleanup()

    def test_plain_files_are_encrypted_in_place_and_originals_removed(self):
        before = (self.root / "사진" / "여름" / "바다.jpg").read_bytes()
        self.assertEqual(migrate.pending(self.root)[:1], (2,))
        st = cs.Store(self.root, self.app)
        st.create(PW)
        report = migrate.move_plain(st, self.root)
        self.assertEqual((report.moved, report.failed), (2, []))
        self.assertEqual(read(st, "사진/여름/바다.jpg"), before)
        self.assertEqual(read(st, "메모.txt"), b"PLAIN-MEMO")
        self.assertFalse((self.root / "사진").exists())  # 빈 폴더도 지웠다
        self.assertTrue((self.root / "받는중.bin.part").exists())  # 조각·숨김 파일은 건드리지 않는다
        self.assertTrue((self.root / "desktop.ini").exists())
        disk = b"".join(x.read_bytes() for x in self.root.rglob("*") if x.is_file())
        self.assertNotIn(b"PLAIN-MEMO", disk)
        self.assertEqual(migrate.pending(self.root)[:2], (0, 0))
        st.close()

    def test_crash_after_import_does_not_duplicate(self):
        st = cs.Store(self.root, self.app)
        st.create(PW)
        real_remove = os.remove

        def crash(path):
            raise OSError("꺼짐")

        os.remove = crash
        try:
            report = migrate.move_plain(st, self.root)
        finally:
            os.remove = real_remove
        self.assertEqual(report.moved, 0)
        self.assertEqual(len(report.failed), 2)
        report = migrate.move_plain(st, self.root)  # 다시 켰다
        self.assertEqual(report.moved, 2)
        self.assertEqual(names(st), ["사진", "메모.txt"])  # 두 번 들어가지 않았다
        st.close()

    def test_old_locked_folder_moves_with_new_pin(self):
        (self.root / "비밀").mkdir()
        (self.root / "비밀" / "일기.txt").write_text("OLD-SECRET", "utf-8")
        (self.root / "비밀" / "안").mkdir()
        (self.root / "비밀" / "안" / "a.bin").write_bytes(os.urandom(cs.CHUNK * 2 + 1))
        expect = (self.root / "비밀" / "안" / "a.bin").read_bytes()
        old = vault.VaultManager(self.root)
        old.lock_folder("비밀", self.root / "비밀", "111111")
        self.assertTrue(wait_state(old, "비밀", "locked"))
        self.assertEqual(migrate.pending(self.root)[2], ["비밀"])
        st = cs.Store(self.root, self.app)
        st.create(PW)
        migrate.move_plain(st, self.root)
        code = migrate.convert_vault(st, self.root, "비밀", "222222", pin="111111")
        self.assertEqual(len(code), 39)
        self.assertEqual(migrate.pending(self.root)[2], [])
        self.assertFalse((self.root / vault.VAULT_DIR).exists())
        token = st.open_vault(["비밀"], "local", "222222", None)
        self.assertEqual(read(st, "비밀/일기.txt", {"token": token}), b"OLD-SECRET")
        self.assertEqual(read(st, "비밀/안/a.bin", {"token": token}), expect)
        st.close()
        again = cs.Store(self.root, self.app)
        self.assertNotIn("일기.txt", {n.name for n in again.nodes.values()})  # 새 PIN 없이는 안이 안 보인다
        again.close()


if __name__ == "__main__":
    unittest.main()
