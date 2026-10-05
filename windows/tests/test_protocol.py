from __future__ import annotations

import json
import socket
import tempfile
import threading
import time
import tracemalloc
import unittest
from pathlib import Path

import protocol as p

S = bytes(range(32))
KEY = p.derive_key(S, p.INFO_PHONE_TO_PC)


class Loop:
    """socketpair 위에서 서버 쪽 처리를 스레드로 돌린다."""

    def __init__(self, handler):
        self.client, self.server = socket.socketpair()
        self.result = {}
        self.thread = threading.Thread(target=self._run, args=(handler,), daemon=True)
        self.thread.start()

    def _run(self, handler):
        try:
            self.result["value"] = handler(self.server)
        except Exception as exc:  # noqa: BLE001 - 테스트에서 확인
            self.result["error"] = exc
        finally:
            self.server.close()

    def join(self):
        self.thread.join(5)
        self.client.close()
        return self.result


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.guard = p.ReplayGuard(self.root / "seen.json")

    def tearDown(self):
        self.tmp.cleanup()

    def serve(self, sock):
        kind, prefix = p.read_prefix(sock)
        ch = p.open_server(sock, prefix, KEY)
        got = p.receive_message(ch, self.guard, self.root / "in")
        p.send_reply(ch, True)
        return got

    def test_vectors_match_file(self):
        vectors = json.loads((Path(__file__).resolve().parents[2] / "docs/test_vectors.json").read_text("utf-8"))
        self.assertEqual(p.derive_key(S, p.INFO_PHONE_TO_PC).hex(), vectors["k_phone_to_pc_hex"])
        self.assertEqual(p.confirm_code(S), vectors["confirm_code"])

    def test_pc_vectors_match_file(self):
        vectors = json.loads((Path(__file__).resolve().parents[2] / "docs/test_vectors.json").read_text("utf-8"))
        # 기존 폰 벡터는 그대로다.
        self.assertEqual(p.derive_key(S, p.INFO_PC_TO_PHONE).hex(), vectors["k_pc_to_phone_hex"])
        self.assertEqual(p.derive_key(S, p.INFO_PC_HOST_TO_JOINER).hex(), vectors["k_pc_host_to_joiner_hex"])
        self.assertEqual(p.derive_key(S, p.INFO_PC_JOINER_TO_HOST).hex(), vectors["k_pc_joiner_to_host_hex"])
        P = bytes.fromhex(vectors["P_hex"])
        pc = vectors["pair_code"]
        self.assertEqual(p.pair_code(pc["host"], pc["port"], P), pc["code"])
        for text in (pc["code"], pc["uri"]):
            info = p.parse_pair_input(text)
            self.assertEqual((info.host, info.port, info.key), (pc["host"], pc["port"], P))
        # 프레임 벡터: joiner→host 첫 프레임을 host 키로 열 수 있다.
        f = vectors["msg_pc_joiner_to_host"]
        sk = p.session_key(p.derive_key(S, p.INFO_PC_JOINER_TO_HOST), bytes.fromhex(vectors["salt_hex"]))
        self.assertEqual(sk.hex(), f["session_key_hex"])

    def test_direction_keys_never_shared(self):
        infos = {
            ("phone", ""): p.direction_infos("phone"),
            ("pc", "host"): p.direction_infos("pc", "host"),
            ("pc", "joiner"): p.direction_infos("pc", "joiner"),
        }
        # 내가 보내는 키 = 상대가 받는 키
        self.assertEqual(infos[("pc", "host")][0], infos[("pc", "joiner")][1])
        self.assertEqual(infos[("pc", "joiner")][0], infos[("pc", "host")][1])
        self.assertEqual(infos[("phone", "")], (p.INFO_PC_TO_PHONE, p.INFO_PHONE_TO_PC))
        for send, recv in infos.values():
            self.assertNotEqual(send, recv)
        labels = {p.INFO_PHONE_TO_PC, p.INFO_PC_TO_PHONE, p.INFO_PAIR, p.INFO_PC_HOST_TO_JOINER, p.INFO_PC_JOINER_TO_HOST}
        self.assertEqual(len({p.derive_key(S, i) for i in labels}), 5)
        with self.assertRaises(ValueError):
            p.direction_infos("pc", "")
        with self.assertRaises(ValueError):
            p.direction_infos("tablet")

    def test_host_to_joiner_frame_not_accepted_as_joiner_to_host(self):
        k_h2j = p.derive_key(S, p.INFO_PC_HOST_TO_JOINER)
        k_j2h = p.derive_key(S, p.INFO_PC_JOINER_TO_HOST)

        def serve(sock):
            _, prefix = p.read_prefix(sock)
            ch = p.open_server(sock, prefix, k_j2h)
            return p.receive_message(ch, self.guard, self.root / "in")

        loop = Loop(serve)
        ch = p.open_client(loop.client, p.KIND_MSG, k_h2j)
        p.send_text(ch, "pc", "x")
        self.assertIsInstance(loop.join()["error"], p.ProtocolError)

    def test_pair_code_roundtrip_and_typos(self):
        key = bytes(range(200, 232))
        code = p.pair_code("100.64.0.7", 47100, key)
        self.assertNotIn(":", code)
        self.assertEqual(p.parse_pair_input(code).key, key)
        # 소문자·공백·O/I 혼동 허용
        where, body = code.split("-", 1)
        loose = body.lower().replace("-", " ").replace("0", "o").replace("1", "i")
        self.assertEqual(p.parse_pair_input(f"  {where} {loose} ").key, key)
        self.assertEqual(p.parse_pair_input(p.pair_code("100.64.0.7", 5000, key)).port, 5000)
        # IP 오타·글자 오타는 검사 바이트로 잡힌다.
        with self.assertRaises(p.ProtocolError):
            p.parse_pair_input(code.replace("100.64.0.7", "100.64.0.8"))
        body = code.split("-", 1)[1]
        swapped = body[1] + body[0] + body[2:] if body[0] != body[1] else body[2] + body[1] + body[0] + body[3:]
        with self.assertRaises(p.ProtocolError):
            p.parse_pair_input("100.64.0.7-" + swapped)
        with self.assertRaises(p.ProtocolError):
            p.parse_pair_input(code[:-5])

    def test_pair_uri_strict(self):
        key = bytes(32)
        uri = p.pair_uri("100.64.0.7", 47100, "내 PC", key)
        info = p.parse_pair_input(uri)
        self.assertEqual((info.host, info.port, info.name, info.key), ("100.64.0.7", 47100, "내 PC", key))
        for bad in (uri.replace("v=1", "v=2"), uri + "&x=1", uri.replace("&p=47100", ""),
                    uri.replace("p=47100", "p=99999"), uri.replace("h=100.64.0.7", "h=evil.com"),
                    uri.replace(p.b64u_encode(key), p.b64u_encode(bytes(16))), "https://example.com", ""):
            with self.assertRaises(p.ProtocolError, msg=bad):
                p.parse_pair_input(bad)

    def test_text_roundtrip(self):
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        p.send_text(ch, "phone", "안녕 hello")
        p.read_reply(ch)
        self.assertEqual(loop.join()["value"].header["text"], "안녕 hello")

    def test_file_roundtrip_multi_chunk(self):
        src = self.root / "big.bin"
        data = bytes(range(256)) * (p.CHUNK // 256 * 2 + 7)
        src.write_bytes(data)
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        p.send_file(ch, "phone", src)
        p.read_reply(ch)
        got = loop.join()["value"]
        self.assertEqual(got.path.read_bytes(), data)
        self.assertEqual(list((self.root / "in").glob("*.part")), [])

    def test_large_file_streams_in_bounded_memory(self):
        """64 MiB 파일을 보내고 받는 동안 파이썬 메모리 최고치가 파일 크기와 상관없이 작다(1 MiB 조각 몇 개).
        전체를 메모리에 올리면 최고치가 64 MiB를 넘는다."""
        size_mb = 64
        src = self.root / "large.bin"
        block = bytes(range(256)) * 4096  # 1 MiB
        with open(src, "wb") as f:
            for i in range(size_mb):
                f.write(block[i:] + block[:i])
        tracemalloc.start()
        try:
            loop = Loop(self.serve)
            ch = p.open_client(loop.client, p.KIND_MSG, KEY)
            p.send_file(ch, "phone", src)
            p.read_reply(ch)
            got = loop.join()["value"]
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(got.path.stat().st_size, size_mb * 1024 * 1024)
        self.assertLess(peak, 12 * 1024 * 1024, f"peak {peak / 2**20:.1f} MiB")
        with open(src, "rb") as a, open(got.path, "rb") as b:
            while True:
                x, y = a.read(p.CHUNK), b.read(p.CHUNK)
                self.assertEqual(x, y)
                if not x:
                    break

    def test_empty_file(self):
        src = self.root / "empty.txt"
        src.write_bytes(b"")
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        p.send_file(ch, "phone", src)
        p.read_reply(ch)
        self.assertEqual(loop.join()["value"].path.read_bytes(), b"")

    def test_wrong_key_rejected(self):
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, p.derive_key(bytes(32), p.INFO_PHONE_TO_PC))
        p.send_text(ch, "attacker", "x")
        self.assertIsInstance(loop.join()["error"], p.ProtocolError)

    def test_tampered_frame_rejected(self):
        def tamper_client():
            salt = bytes(16)
            prefix = p.MAGIC + bytes([p.KIND_MSG]) + salt + b"\x00"
            ch = p.Channel(loop.client, p.session_key(KEY, salt), prefix, True)
            sealed = ch.aead.encrypt(p.nonce(0, 0, False), json.dumps(p.new_header("text", "x", text="a")).encode(), prefix)
            sealed = sealed[:-1] + bytes([sealed[-1] ^ 1])
            loop.client.sendall(prefix + len(sealed).to_bytes(4, "big") + sealed)

        loop = Loop(self.serve)
        tamper_client()
        self.assertIsInstance(loop.join()["error"], p.ProtocolError)

    def test_truncated_file_discarded(self):
        def serve_expect_fail(sock):
            return self.serve(sock)

        loop = Loop(serve_expect_fail)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        ch.send(json.dumps(p.new_header("file", "x", name="a.txt", size=10)).encode(), last=False)
        ch.send(b"12345", last=False)
        loop.client.close()
        self.assertIn("error", loop.join())
        self.assertEqual(list((self.root / "in").iterdir()), [])

    def test_oversize_file_rejected(self):
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        ch.send(json.dumps(p.new_header("file", "x", name="a.txt", size=3)).encode(), last=False)
        ch.send(b"12345", last=True)
        self.assertIsInstance(loop.join()["error"], p.ProtocolError)
        self.assertEqual(list((self.root / "in").iterdir()), [])

    def test_replay_and_skew(self):
        now = int(time.time() * 1000)
        self.guard.check_and_add("a" * 32, now)
        with self.assertRaises(p.ProtocolError):
            self.guard.check_and_add("a" * 32, now)
        with self.assertRaises(p.ProtocolError):
            self.guard.check_and_add("b" * 32, now - 400_000)
        # 재시작해도 기억한다.
        with self.assertRaises(p.ProtocolError):
            p.ReplayGuard(self.root / "seen.json").check_and_add("a" * 32, now)

    def test_bad_magic(self):
        loop = Loop(self.serve)
        loop.client.sendall(b"GET / HTTP/1.1\r\n\r\n....")
        self.assertIsInstance(loop.join()["error"], p.ProtocolError)

    def test_oversized_length_rejected(self):
        loop = Loop(self.serve)
        loop.client.sendall(p.MAGIC + bytes([p.KIND_MSG]) + bytes(16) + b"\x00" + (p.MAX_FRAME + 1).to_bytes(4, "big"))
        self.assertIsInstance(loop.join()["error"], p.ProtocolError)

    def test_sanitize(self):
        self.assertEqual(p.sanitize_filename("..\\..\\Windows\\evil.exe"), "evil.exe")
        self.assertEqual(p.sanitize_filename("nul"), "_nul")
        self.assertEqual(p.sanitize_filename("a\x00b.txt"), "a_b.txt")
        self.assertTrue(p.sanitize_filename("y" * 300 + ".jpeg").endswith(".jpeg"))
        self.assertLessEqual(len(p.sanitize_filename("y" * 300 + ".jpeg")), 150)

    def test_sanitize_bidi_and_invisible(self):
        # RLO로 확장자를 속이는 이름("evil\u202egnp.exe"는 화면에 evilexe.png처럼 보임)
        self.assertEqual(p.sanitize_filename("evil\u202egnp.exe"), "evil_gnp.exe")
        for ch in "\u200e\u200f\u202a\u202b\u202c\u202d\u2066\u2067\u2068\u2069\u061c\u200b\u2060\ufeff\u0085\u009b":
            self.assertEqual(p.sanitize_filename(f"a{ch}b.txt"), "a_b.txt", repr(ch))
        # 글자를 잇는 ZWJ(이모지·인도계 문자)는 그대로 둔다.
        self.assertEqual(p.sanitize_filename("\U0001f468\u200d\U0001f469.png"), "\U0001f468\u200d\U0001f469.png")

    def test_sanitize_windows_device_names(self):
        cases = {
            "COM1 .txt": "_COM1 .txt", "con": "_con", "Con.tar.gz": "_Con.tar.gz", "nul ": "_nul",
            "LPT\u00b9.log": "_LPT\u00b9.log", "com0.txt": "_com0.txt", "CONIN$": "_CONIN$", "conout$.x": "_conout$.x",
            "AUX  .  .": "_AUX", "report.txt. . ": "report.txt", "COM10.txt": "COM10.txt", "CONSOLE.txt": "CONSOLE.txt",
        }
        for raw, want in cases.items():
            self.assertEqual(p.sanitize_filename(raw), want, raw)

    def test_clean_name_strips_bidi_and_controls(self):
        self.assertEqual(p.clean_name("  My\u202ePC\u0007\n "), "MyPC")
        self.assertEqual(p.clean_name("x" * 100), "x" * 64)
        self.assertEqual(p.clean_name(None), "None")

    def test_replay_guard_concurrent_same_id_accepts_once(self):
        now = int(time.time() * 1000)
        ok, errors = [], []
        start = threading.Barrier(16)

        def worker(msg_id: str):
            start.wait()
            try:
                self.guard.check_and_add(msg_id, now)
                ok.append(msg_id)
            except p.ProtocolError as exc:
                errors.append(exc.code)

        threads = [threading.Thread(target=worker, args=("c" * 32,)) for _ in range(8)]
        threads += [threading.Thread(target=worker, args=(f"{i:032x}",)) for i in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(10)
        self.assertEqual(ok.count("c" * 32), 1)
        self.assertEqual(errors, ["replay"] * 7)
        # 디스크의 seen.json은 깨지지 않고 모든 id를 담는다(임시 파일도 남지 않음).
        saved = json.loads((self.root / "seen.json").read_text("utf-8"))
        self.assertEqual(len(saved), 9)
        self.assertFalse((self.root / "seen.json.tmp").exists())

    def test_disk_full_rejected_before_writing(self):
        orig = p.free_bytes
        p.free_bytes = lambda _folder: 10 * 1024 * 1024  # 10 MiB 남음
        try:
            loop = Loop(self.serve)
            ch = p.open_client(loop.client, p.KIND_MSG, KEY)
            ch.send(json.dumps(p.new_header("file", "x", name="big.iso", size=20 * 1024 * 1024)).encode(), last=False)
            result = loop.join()
        finally:
            p.free_bytes = orig
        self.assertEqual(getattr(result.get("error"), "code", None), "disk_full")
        self.assertEqual(list((self.root / "in").iterdir()), [])

    def test_failed_receive_leaves_other_part_files_alone(self):
        """다른 수신이 이미 같은 이름의 .part를 만들었으면 다음 이름을 쓰고, 실패해도 남의 .part는 지우지 않는다."""
        inbox = self.root / "in"
        inbox.mkdir()
        other = inbox / "a.txt.part"
        other.write_bytes(b"someone else's download")
        real_unique = p.unique_path
        calls = []

        def racing_unique(folder, name):
            calls.append(name)
            # 첫 호출은 다른 수신이 막 .part를 만든 이름을 고른 것처럼(경쟁) 돌려준다.
            return folder / name if len(calls) == 1 else real_unique(folder, name)

        p.unique_path = racing_unique
        try:
            loop = Loop(self.serve)
            ch = p.open_client(loop.client, p.KIND_MSG, KEY)
            ch.send(json.dumps(p.new_header("file", "x", name="a.txt", size=10)).encode(), last=False)
            ch.send(b"12345", last=False)
            time.sleep(0.2)
            loop.client.close()
            result = loop.join()
        finally:
            p.unique_path = real_unique
        self.assertIn("error", result)
        self.assertGreaterEqual(len(calls), 2)
        self.assertEqual(other.read_bytes(), b"someone else's download")
        self.assertEqual(sorted(x.name for x in inbox.iterdir()), ["a.txt.part"])

    def test_bool_size_rejected(self):
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        ch.send(json.dumps(p.new_header("file", "x", name="a.txt", size=True)).encode(), last=False)
        ch.send(b"1", last=True)
        self.assertEqual(getattr(loop.join().get("error"), "code", None), "bad_file_info")

    def test_unpair_message_parsed(self):
        loop = Loop(self.serve)
        ch = p.open_client(loop.client, p.KIND_MSG, KEY)
        ch.send(json.dumps(p.new_header("unpair", "phone")).encode(), last=False)
        ch.send(b"", last=True)
        p.read_reply(ch)
        got = loop.join()["value"]
        self.assertEqual((got.header["type"], got.path), ("unpair", None))


if __name__ == "__main__":
    unittest.main()
