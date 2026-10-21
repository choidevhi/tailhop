"""1.8.0 보안 점검에서 나온 문제들의 회귀 테스트(F1–F8). 모든 파일은 임시 폴더에만 쓴다."""
from __future__ import annotations

import ctypes
import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

import protocol as p
import store
import tsnet

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import bridge  # noqa: E402
import cloud  # noqa: E402
import node as nodemod  # noqa: E402
import outbox  # noqa: E402
import resume  # noqa: E402
from test_nodes import IP_A, IP_B, USER, FakeTS, wait_for  # noqa: E402


def short_name(path: Path) -> str | None:
    buf = ctypes.create_unicode_buffer(520)
    if not ctypes.windll.kernel32.GetShortPathNameW(str(path), buf, 520):
        return None
    name = Path(buf.value).name
    return None if name == path.name else name


class AliasTests(unittest.TestCase):
    """F1·F2: 대소문자·8.3 짧은 이름으로 숨긴 이름을 돌아가지 못한다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "cloud"
        self.root.mkdir()
        self.hub = cloud.CloudFolder(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def assert_hidden(self, call):
        with self.assertRaises(p.ProtocolError) as ctx:
            call()
        self.assertIn(ctx.exception.code, ("not_found", "bad_path"))

    def test_part_in_other_case_is_hidden(self):
        (self.root / "movie.mp4.part").write_bytes(b"PARTIAL")
        (self.root / "b.PART").write_bytes(b"x")
        self.assertEqual(self.hub.list([])[0], [])
        self.assert_hidden(lambda: self.hub.stat_file(["movie.mp4.PART"]))
        self.assert_hidden(lambda: self.hub.stat_file(["b.PART"]))
        self.assert_hidden(lambda: self.hub.delete(["movie.mp4.Part"]))
        self.assertTrue((self.root / "movie.mp4.part").exists())

    def test_short_name_alias_is_refused(self):
        secret = self.root / ".env"
        secret.write_text("SECRET=1", "utf-8")
        long_part = self.root / "bigarchive.zip.part"
        long_part.write_bytes(b"PART2")
        long_dir = self.root / "아주 긴 폴더 이름입니다"
        long_dir.mkdir()
        (long_dir / "in.txt").write_text("x", "utf-8")
        aliases = [short_name(x) for x in (secret, long_part)]
        if not any(aliases):
            self.skipTest("이 디스크는 8.3 이름을 만들지 않음")
        for alias in aliases:
            if alias:
                self.assert_hidden(lambda a=alias: self.hub.stat_file([a]))
        alias_dir = short_name(long_dir)
        if alias_dir:  # 보이는 폴더라도 별칭으로는 들어가지 않는다(실제 이름으로만)
            self.assert_hidden(lambda: self.hub.list([alias_dir]))
        self.assertEqual([x.name for x in self.hub.list(["아주 긴 폴더 이름입니다"])[0]], ["in.txt"])

    def test_mkdir_inside_hidden_folder_is_refused(self):
        private = self.root / "Private"
        private.mkdir()
        subprocess.run(["attrib", "+h", str(private)], check=True, capture_output=True)
        self.assert_hidden(lambda: self.hub.mkdir(["Private", "planted"]))
        self.assertFalse((private / "planted").exists())
        self.assert_hidden(lambda: self.hub.put_target("Private"))


class TwoNodeSecurityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.table = {IP_A: tsnet.Peer("nodeA", USER, "pc-a"), IP_B: tsnet.Peer("nodeB", USER, "pc-b")}
        self.nodes = []
        self.a, self.ev_a = self.make_node("A", IP_A, "nodeA")
        self.b, self.ev_b = self.make_node("B", IP_B, "nodeB")
        self.b.join_pairing(self.a.begin_pairing())
        self.assertTrue(wait_for(lambda: "nodeB" in self.a.pairings))
        self.hub_dir = self.root / "A" / "cloud"
        self.a.set_cloud(True, str(self.hub_dir))

    def tearDown(self):
        for n in self.nodes:
            n.stop()
        self.tmp.cleanup()

    def make_node(self, label, ip, sid):
        events = []
        ts = FakeTS(tsnet.SelfInfo(ip=ip, user_id=USER, name=f"PC-{label}", stable_id=sid), self.table)
        n = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=self.root / label / "app", ts=ts, port=0)
        n.settings["save_dir"] = str(self.root / label / "recv")
        n.start()
        self.assertTrue(wait_for(lambda: n.listener is not None and n.me is not None))
        self.nodes.append(n)
        return n, events

    def test_cloud_partial_cannot_be_resumed_as_chat_file_after_cloud_off(self):
        """F3: 클라우드에 올리다 만 조각을 클라우드를 끈 뒤 대화방 파일로 이어 보내도 클라우드 폴더에 쓰지 못한다."""
        src = self.root / "v.bin"
        data = os.urandom(p.CHUNK * 4)
        src.write_bytes(data)
        st = src.stat()
        job = outbox.Job(id="0123456789abcdef", peer_id="nodeA", kind="cloud_put", path=str(src), remote="",
                         xfer=p.new_xfer(), size=st.st_size, mtime_ns=st.st_mtime_ns)

        def cut(done, total):
            if done >= p.CHUNK * 2:
                raise RuntimeError("cut")

        with self.assertRaises(RuntimeError):
            self.b.deliver(job, cut)
        self.assertTrue(wait_for(lambda: not self.a.book.busy and self.a.book.have("nodeB", job.xfer, job.size)))
        self.a.set_cloud(False)
        self.assertEqual(self.a.book.have("nodeB", job.xfer, job.size), 0)  # 끈 클라우드의 조각은 지웠다
        self.assertEqual(list(self.hub_dir.glob("*.part")), [])
        # 같은 xfer를 대화방 파일로 offset을 주고 이어 보내 본다
        sock, pairing = self.b._connect("nodeA")
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self.b._send_key(pairing))
            # 허브는 헤더만 보고 바로 거부하고 닫으므로, 보내는 쪽은 거부 응답 또는 연결 끊김을 본다
            with self.assertRaises((p.ProtocolError, ConnectionError)) as ctx:
                p.send_file_xfer(ch, "B", src, job.xfer, p.CHUNK * 2)
                p.read_reply(ch)
        if isinstance(ctx.exception, p.ProtocolError):
            self.assertEqual(ctx.exception.code, "resume_lost")
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d.get("code") == "resume_lost" for k, d in self.ev_a)))
        self.assertEqual([x.name for x in self.hub_dir.iterdir()], [])
        self.assertFalse((self.root / "A" / "recv" / "v.bin").exists())

    def test_claim_checks_kind_and_folder(self):
        book = resume.ResumeBook(self.root / "book")
        part = self.root / "x.part"
        part.write_bytes(b"abc")
        x = p.new_xfer()
        book.begin("peer", x, 10, part, self.root / "x", "cloud_put")
        book.keep("peer", x, part)
        self.assertIsNone(book.claim("peer", x, 10, 3, "file", self.root))
        self.assertIsNone(book.claim("peer", x, 10, 3, "cloud_put", self.root / "other"))
        self.assertIsNotNone(book.claim("peer", x, 10, 3, "cloud_put", self.root))

    def test_hub_read_error_after_header_does_not_corrupt_download(self):
        """F4: 허브가 보내는 중 읽기 오류가 나도 받는 쪽 파일에 오류 문장이 섞이지 않는다."""
        self.hub_dir.mkdir(parents=True, exist_ok=True)
        target = self.hub_dir / "big.bin"
        data = os.urandom(p.CHUNK * 3)
        target.write_bytes(data)
        [item] = self.b.cloud_list("nodeA", "")[0]
        real_stream = nodemod.Node._stream_blocks

        def broken(ch, first, blocks, left):
            ch.send(bytes(first[:p.CHUNK]), last=False)
            raise OSError("읽기 오류")

        nodemod.Node._stream_blocks = staticmethod(broken)
        job = outbox.Job(id="0123456789abcdee", peer_id="nodeA", kind="cloud_get", remote="big.bin",
                         size=item.size, remote_mtime=item.mtime)
        try:
            with self.assertRaises(Exception):
                self.b.deliver(job)
        finally:
            nodemod.Node._stream_blocks = staticmethod(real_stream)
        part = self.b.outbox.work_dir / job.id / "big.bin.part"
        if part.exists():
            self.assertEqual(part.read_bytes(), data[:part.stat().st_size])  # 남은 조각은 진짜 데이터뿐
        self.b.deliver(job)
        self.assertEqual((self.root / "B" / "recv" / "big.bin").read_bytes(), data)

    def test_unpaired_connection_does_not_block_accept_loop(self):
        """F6: 모르는 주소가 와도 주소표 새로 읽기가 받기 루프를 막지 않는다."""
        slow = threading.Event()

        def peer_ips():
            slow.wait(3)
            return {}

        self.a.ts.peer_ips = peer_ips
        self.a._ip_refreshed = 0.0
        s = socket.create_connection((IP_A, self.a.port), source_address=("127.0.0.7", 0), timeout=5)
        s.close()
        time.sleep(0.1)
        t0 = time.monotonic()
        self.b.send_text("nodeA", "바로 받는다")
        self.assertLess(time.monotonic() - t0, 1.5)
        slow.set()

    def test_uploaded_part_name_stays_visible(self):
        """.part로 끝나는 파일을 올리면 이름 끝에 _를 붙여 목록에 보이게 저장한다(Android 허브와 같다)."""
        src = self.root / "a.part"
        src.write_bytes(b"x")
        [job] = self.b.outbox.add_cloud_upload("nodeA", [src], "")
        self.assertTrue(wait_for(lambda: self.b.outbox.get(job.id) is None))
        self.assertEqual([x.name for x in self.b.cloud_list("nodeA", "")[0]], ["a.part_"])


    def test_long_names_stay_visible_after_numbering(self):
        """A5: 150자 이름을 두 번 올려 번호가 붙어도, .part로 끝나는 긴 이름도 목록에 보이고 지울 수 있다."""
        long_name = "가" * 146 + ".txt"
        part_name = "나" * 145 + ".part"
        srcs = []
        for i, name in enumerate((long_name, long_name, part_name)):
            folder = self.root / f"src{i}"
            folder.mkdir()
            (folder / name).write_bytes(str(i).encode())
            srcs.append(folder / name)
        jobs = self.b.outbox.add_cloud_upload("nodeA", srcs, "")
        self.assertTrue(wait_for(lambda: all(self.b.outbox.get(j.id) is None for j in jobs), 10))
        names = [x.name for x in self.b.cloud_list("nodeA", "")[0]]
        self.assertEqual(len(names), 3, names)
        for name in names:
            self.assertLessEqual(len(name), 150)
            self.b.cloud_delete("nodeA", name)  # 모두 이름으로 지울 수 있다
        self.assertEqual(self.b.cloud_list("nodeA", "")[0], [])

    def test_collected_chat_file_with_long_name_is_visible(self):
        self.a.set_cloud(collect=True)
        src = self.root / ("다" * 148 + ".md")
        src.write_bytes(b"x")
        self.b.send_file("nodeA", src)
        self.b.send_file("nodeA", src)
        self.assertEqual(len(self.b.cloud_list("nodeA", "")[0]), 2)


class OutboxFileTests(unittest.TestCase):
    """F7: 손상되거나 고친 outbox.json이 다른 폴더를 지우거나 보내기를 멈추게 하지 못한다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.app_dir = Path(self.tmp.name) / "app"
        self.app_dir.mkdir()
        self.victim = Path(self.tmp.name) / "victim"
        self.victim.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def test_bad_entries_are_dropped(self):
        good = {"id": "0123456789abcdef", "peer_id": "p", "kind": "text", "text": "x", "xfer": p.new_xfer(),
                "created": 1.0, "next_at": 1.0}
        bad = [dict(good, id="..\\..\\victim"), dict(good, id="0123456789abcdee", next_at="soon"),
               dict(good, id="0123456789abcded", kind="rm"), dict(good, id="0123456789abcdec", xfer="x"),
               dict(good, id="0123456789abcdeb", attempts=True), "junk", None]
        (self.app_dir / "outbox.json").write_text(json.dumps([good, *bad]), "utf-8")
        box = outbox.Outbox(lambda job, progress: None, self.app_dir, lambda k, d: None)
        self.assertEqual([j.id for j in box.jobs], ["0123456789abcdef"])
        box._cleanup(outbox.Job(id="..\\..\\victim", peer_id="p", kind="text"))
        self.assertTrue(self.victim.exists())

    def test_scheduler_survives_and_delivers(self):
        good = {"id": "0123456789abcdef", "peer_id": "p", "kind": "text", "text": "x", "xfer": p.new_xfer(),
                "created": 1.0, "next_at": "soon"}
        (self.app_dir / "outbox.json").write_text(json.dumps([good]), "utf-8")
        sent = []
        box = outbox.Outbox(lambda job, progress: sent.append(job.text), self.app_dir, lambda k, d: None)
        box.start()
        box.add_text("p", "새 글")
        self.assertTrue(wait_for(lambda: sent == ["새 글"]))
        box.stop()


class BridgeLimitTests(unittest.TestCase):
    """F5: 인증 전 연결 수 제한, 비ASCII 토큰에도 서버가 계속 돈다."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.server = bridge.Server(lambda data: {"ok": True}, Path(self.tmp.name))

    def tearDown(self):
        self.server.close()
        self.tmp.cleanup()

    def test_idle_connections_are_capped(self):
        before = threading.active_count()
        socks = [socket.create_connection(("127.0.0.1", self.server.port)) for _ in range(40)]
        time.sleep(0.5)
        self.assertLessEqual(threading.active_count() - before, bridge.MAX_CLIENTS + 1)
        for s in socks:
            s.close()

    def test_non_ascii_token_is_refused_quietly(self):
        with socket.create_connection(("127.0.0.1", self.server.port), timeout=5) as s:
            s.sendall(json.dumps({"token": "한글토큰"}).encode("utf-8") + b"\n")
            self.assertEqual(s.recv(10), b"")
        self.assertTrue(bridge.send_request(bridge.Request(text="ok"), Path(self.tmp.name))["ok"])


if __name__ == "__main__":
    unittest.main()

