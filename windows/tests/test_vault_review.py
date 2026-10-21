"""2.0.0 잠긴 폴더 보안 점검에서 나온 문제들(V1–V5, R1–R4)의 회귀 테스트. 임시 폴더만 쓴다."""
from __future__ import annotations

import json
import msvcrt
import os
import stat
import tempfile
import threading
import time
import unittest
from pathlib import Path

import protocol as p
import thumbs
import vault
from test_vault import wait_state


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "cloud"
        (self.root / "Secret").mkdir(parents=True)
        (self.root / "Secret" / "a.txt").write_text("PLAIN-A", "utf-8")
        self.vm = vault.VaultManager(self.root)
        self._cache = thumbs.CACHE_DIR
        thumbs.CACHE_DIR = Path(self.tmp.name) / "thumbs"

    def tearDown(self):
        thumbs.CACHE_DIR = self._cache
        self.tmp.cleanup()

    def lock(self, pin: str = "246810") -> None:
        self.vm.lock_folder("Secret", self.root / "Secret", pin)
        self.assertTrue(wait_state(self.vm, "Secret", "locked"))

    def test_v1_disk_copy_alone_cannot_test_pins(self):
        """vault.json만으로는 PIN을 맞혀 볼 수 없다(PIN으로 감싼 키를 DPAPI로 한 번 더 감쌌다)."""
        self.lock()
        m, _ = self.vm.mount_of("Secret")
        meta = json.loads((self.root / vault.VAULT_DIR / m.id / "vault.json").read_text("utf-8"))
        kek = vault._kek("246810", vault._unb64(meta["salt"]))
        self.assertIsNone(vault._unwrap(kek, meta["wrapped"], m.id))  # 디스크의 값은 DPAPI 덩어리라 PIN 키로 바로 열리지 않는다
        self.assertIsNotNone(vault._unwrap(kek, vault._open_local(meta["wrapped"]), m.id))
        self.assertFalse(vault.valid_pin("12345"))  # 최소 6자

    def test_v2_case_variant_cannot_bypass_mount(self):
        self.lock()
        for path in ("SECRET", "secret/a.txt", "Secret/a.txt"):
            self.assertIsNotNone(self.vm.mount_of(path))
        with self.assertRaises(p.ProtocolError):
            self.vm.lock_folder("SECRET", self.root / "SECRET", "246810")
        self.assertTrue(self.vm.any_inside("SECRET"))

    def test_v3_thumbnail_cache_is_removed_when_locking(self):
        from PIL import Image
        pic = self.root / "Secret" / "pic.png"
        Image.new("RGB", (300, 200), "#0c2c22").save(pic)
        cached = thumbs.make(pic, thumbs.CACHE_DIR)
        self.assertTrue(cached is not None and cached.exists())
        real = thumbs.cache_path(pic)
        self.lock()
        self.assertFalse(real.exists())

    def test_v4_parallel_wrong_pins_all_count_and_peers_are_separate(self):
        self.lock()
        errors = []

        def wrong():
            try:
                self.vm.open("Secret", "attacker", pin="000000")
            except p.ProtocolError as exc:
                errors.append(exc.code)

        threads = [threading.Thread(target=wrong) for _ in range(8)]
        for th in threads:
            th.start()
        for th in threads:
            th.join()
        self.assertIn("vault_wait", errors)  # 한꺼번에 넣어도 하나씩 세어 막힌다
        with self.assertRaises(p.ProtocolError) as ctx:
            self.vm.open("Secret", "attacker", pin="246810")
        self.assertEqual(ctx.exception.code, "vault_wait")
        self.vm.open("Secret", "owner-pc", pin="246810")  # 다른 기기(주인)는 막히지 않는다

    def test_r1_file_added_during_lock_is_not_lost(self):
        late = self.root / "Secret" / "late.txt"
        original = vault.Vault.put_file
        added = []

        def put_and_add(self_vault, rel, src, save=True):
            original(self_vault, rel, src, save)
            if not added:
                added.append(True)
                late.write_text("LATE", "utf-8")  # 잠그는 도중 새 파일이 들어온다

        vault.Vault.put_file = put_and_add
        try:
            self.lock()
        finally:
            vault.Vault.put_file = original
        token = self.vm.open("Secret", "local", pin="246810")
        m, _ = self.vm.mount_of("Secret")
        v = self.vm.vault_for(m, token, "local")
        self.assertEqual(b"".join(v.read("late.txt")), b"LATE")

    def test_r2_read_only_file_locks_and_busy_file_can_be_reverted(self):
        ro = self.root / "Secret" / "ro.txt"
        ro.write_text("RO", "utf-8")
        os.chmod(ro, stat.S_IREAD)
        busy = self.root / "Secret" / "busy.txt"
        busy.write_text("BUSY" * 10, "utf-8")
        handle = open(busy, "r+b")
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 4)  # 다른 프로그램이 잡고 있는 파일
        try:
            self.vm.lock_folder("Secret", self.root / "Secret", "246810")
            end = time.monotonic() + 20
            while time.monotonic() < end and self.vm.display_state(self.vm.mount_of("Secret")[0]) != "stalled":
                time.sleep(0.05)
            self.assertEqual(self.vm.display_state(self.vm.mount_of("Secret")[0]), "stalled")
            self.assertFalse(ro.exists())  # 읽기 전용은 풀고 넣었다
        finally:
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 4)
            handle.close()
        self.vm.unlock_folder("Secret", "246810", "local")  # 멈춘 잠그기를 되돌린다
        self.assertTrue(wait_state(self.vm, "Secret", None))
        self.assertEqual((self.root / "Secret" / "a.txt").read_text("utf-8"), "PLAIN-A")
        self.assertEqual((self.root / "Secret" / "ro.txt").read_text("utf-8"), "RO")
        self.assertTrue((self.root / "Secret" / "busy.txt").exists())

    def test_repin_ends_old_tokens(self):
        self.lock()
        token = self.vm.open("Secret", "local", pin="246810")
        self.vm.repin("Secret", "135791", pin="246810", peer="local")
        m, _ = self.vm.mount_of("Secret")
        with self.assertRaises(p.ProtocolError) as ctx:
            self.vm.vault_for(m, token, "local")
        self.assertEqual(ctx.exception.code, "vault_locked")

    def test_concurrent_uploads_with_same_name_both_kept(self):
        self.lock()
        token = self.vm.open("Secret", "local", pin="246810")
        m, _ = self.vm.mount_of("Secret")
        v = self.vm.vault_for(m, token, "local")
        a, b = v.writer("x.txt", 1, 1), v.writer("x.txt", 1, 1)
        a.write(b"1")
        b.write(b"2")
        names = sorted([a.commit(unique=True), b.commit(unique=True)])
        self.assertEqual(names, ["x (1).txt", "x.txt"])

    def test_unlock_waits_for_active_upload(self):
        self.lock()
        token = self.vm.open("Secret", "local", pin="246810")
        m, _ = self.vm.mount_of("Secret")
        w = self.vm.vault_for(m, token, "local").writer("up.txt", 3, 1)
        with self.assertRaises(p.ProtocolError) as ctx:
            self.vm.unlock_folder("Secret", "246810", "local")
        self.assertEqual(ctx.exception.code, "vault_busy")
        w.abort()


if __name__ == "__main__":
    unittest.main()
