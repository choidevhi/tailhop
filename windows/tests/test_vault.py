"""2.0.0 잠긴 폴더(docs/PROTOCOL.md §14) 보관함 자체: 잠그기, 열기, PIN·복구 코드, 막기, 풀기, 이어 하기. 임시 폴더만 쓴다."""
from __future__ import annotations

import os
import tempfile
import time
import unittest
from pathlib import Path

import protocol as p
import vault


def wait_state(vm: vault.VaultManager, path: str, state: str | None, timeout: float = 20) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        found = vm.mount_of(path)
        if (found is None and state is None) or (found is not None and found[0].state == state):
            return True
        time.sleep(0.05)
    return False


class VaultTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "cloud"
        self.secret = self.root / "개인" / "사진"
        self.secret.mkdir(parents=True)
        self.files = {"개인/메모.txt": "비밀 메모 내용 SECRET-MARKER".encode(),
                      "개인/사진/a.bin": os.urandom(vault.CHUNK * 2 + 333),
                      "개인/사진/빈.txt": b""}
        for rel, data in self.files.items():
            (self.root / rel).write_bytes(data)
        self.now = [1000.0]
        self.vm = vault.VaultManager(self.root, clock=lambda: self.now[0])

    def tearDown(self):
        self.tmp.cleanup()

    def lock(self, pin: str = "2468") -> str:
        code = self.vm.lock_folder("개인", self.root / "개인", pin)
        self.assertTrue(wait_state(self.vm, "개인", "locked"))
        return code

    def test_lock_hides_plaintext_and_reads_back(self):
        code = self.lock()
        self.assertEqual(len(code), 39)
        self.assertFalse((self.root / "개인").exists())  # 원본은 지웠다
        raw = b"".join(x.read_bytes() for x in (self.root / vault.VAULT_DIR).rglob("*") if x.is_file())
        self.assertNotIn(b"SECRET-MARKER", raw)
        self.assertNotIn("메모".encode(), raw)  # 이름도 암호문 안에만 있다
        token = self.vm.open("개인", "peerA", pin="2468")
        m, _ = self.vm.mount_of("개인")
        v = self.vm.vault_for(m, token, "peerA")
        self.assertEqual(sorted(n for n, _ in v.children("")), ["메모.txt", "사진"])
        for rel, data in self.files.items():
            inner = rel.split("/", 1)[1]
            self.assertEqual(b"".join(v.read(inner)), data)
        self.assertEqual(b"".join(v.read("사진/a.bin", vault.CHUNK + 5)), self.files["개인/사진/a.bin"][vault.CHUNK + 5:])

    def test_token_bound_to_peer_and_expires(self):
        self.lock()
        token = self.vm.open("개인", "peerA", pin="2468")
        m, _ = self.vm.mount_of("개인")
        for bad in (None, "x" * 32, token):
            peer = "peerB" if bad == token else "peerA"
            with self.subTest(bad=bad), self.assertRaises(p.ProtocolError) as ctx:
                self.vm.vault_for(m, bad, peer)
            self.assertEqual(ctx.exception.code, "vault_locked")
        self.now[0] += vault.TOKEN_TTL + 1
        with self.assertRaises(p.ProtocolError):
            self.vm.vault_for(m, token, "peerA")

    def test_wrong_pin_is_throttled_then_recovery_and_repin(self):
        code = self.lock()
        for _ in range(vault.MAX_FAILS):
            with self.assertRaises(p.ProtocolError) as ctx:
                self.vm.open("개인", "peerA", pin="0000")
            self.assertEqual(ctx.exception.code, "vault_pin")
        with self.assertRaises(p.ProtocolError) as ctx:
            self.vm.open("개인", "peerA", pin="2468")  # 맞는 PIN도 잠시 막힌다
        self.assertEqual(ctx.exception.code, "vault_wait")
        self.now[0] += 61
        self.vm.open("개인", "peerA", recovery=code.lower().replace("-", " "))
        self.vm.repin("개인", "13579", recovery=code)
        with self.assertRaises(p.ProtocolError):
            self.vm.open("개인", "peerA", pin="2468")
        self.vm.open("개인", "peerA", pin="13579")

    def test_write_delete_inside_vault(self):
        self.lock()
        token = self.vm.open("개인", "peerA", pin="2468")
        m, _ = self.vm.mount_of("개인")
        v = self.vm.vault_for(m, token, "peerA")
        w = v.writer("새 폴더/b.txt", 5, 1)
        w.write(b"hello")
        w.commit()
        self.assertTrue(v.is_dir("새 폴더"))
        self.assertEqual(b"".join(v.read("새 폴더/b.txt")), b"hello")
        v.delete("사진")
        self.assertEqual(sorted(n for n, _ in v.children("")), ["메모.txt", "새 폴더"])
        objects = list((self.root / vault.VAULT_DIR / m.id / "objects").iterdir())
        self.assertEqual(len(objects), 2)  # 사진의 조각 파일도 지웠다

    def test_unlock_restores_exact_files(self):
        self.lock()
        self.vm.unlock_folder("개인", "2468")
        self.assertTrue(wait_state(self.vm, "개인", None))
        for rel, data in self.files.items():
            self.assertEqual((self.root / rel).read_bytes(), data)
        self.assertEqual(list((self.root / vault.VAULT_DIR).glob("*/")), [])

    def test_survives_restart_and_resumes_stalled_lock(self):
        self.lock()
        again = vault.VaultManager(self.root)
        token = again.open("개인", "peerB", pin="2468")
        m, _ = again.mount_of("개인")
        self.assertEqual(b"".join(again.vault_for(m, token, "peerB").read("메모.txt")), self.files["개인/메모.txt"])
        # 잠그다 꺼진 것처럼: 상태만 locking으로 되돌리고 남은 평문을 둔다
        (self.root / "개인").mkdir()
        (self.root / "개인" / "늦게.txt").write_bytes(b"late")
        m.state = "locking"
        again._save()
        third = vault.VaultManager(self.root)
        third.open("개인", "peerC", pin="2468")
        self.assertTrue(wait_state(third, "개인", "locked"))
        token = third.open("개인", "peerC", pin="2468")
        m3, _ = third.mount_of("개인")
        self.assertEqual(b"".join(third.vault_for(m3, token, "peerC").read("늦게.txt")), b"late")
        self.assertFalse((self.root / "개인").exists())

    def test_cannot_lock_root_nested_or_with_short_pin(self):
        with self.assertRaises(p.ProtocolError):
            self.vm.lock_folder("", self.root, "2468")
        with self.assertRaises(p.ProtocolError):
            self.vm.lock_folder("개인", self.root / "개인", "12")
        self.lock()
        (self.root / "다른").mkdir()
        with self.assertRaises(p.ProtocolError):
            self.vm.lock_folder("개인/사진", self.root / "개인" / "사진", "2468")

    def test_recovery_code_format(self):
        code = vault.new_recovery_code()
        self.assertRegex(code, r"^([0-9A-HJKMNP-TV-Z]{4}-){7}[0-9A-HJKMNP-TV-Z]{4}$")
        self.assertEqual(vault.normalize_code(code.lower().replace("-", "")), code)


if __name__ == "__main__":
    unittest.main()
