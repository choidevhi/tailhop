"""1.7.0 전송 신뢰성(docs/PROTOCOL.md §11): hello, 이어받기, 중복 무시, 보낼 곳 목록(재시도·취소·저장), 주소 갱신.

test_nodes와 같은 두 노드 시뮬레이션(루프백 주소 + 가짜 Tailscale)을 쓴다. 모든 파일은 임시 폴더에만 쓴다.
"""
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
import outbox  # noqa: E402
import resume  # noqa: E402
from test_nodes import IP_A, IP_B, USER, FakeTS, wait_for  # noqa: E402

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "docs" / "test_vectors.json").read_text("utf-8"))


class Stop(Exception):
    """보내는 쪽에서 일부러 끊는다(전송 중 끊김 흉내)."""


class FakeTSWithStatus(FakeTS):
    def __init__(self, me, table, ips) -> None:
        super().__init__(me, table)
        self.ips = ips

    def peer_ips(self) -> dict[str, str]:
        return dict(self.ips)


class ResumeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.table = {IP_A: tsnet.Peer("nodeA", USER, "pc-a"), IP_B: tsnet.Peer("nodeB", USER, "pc-b")}
        self.ips: dict[str, str] = {}
        self.nodes: list[nodemod.Node] = []
        self.a, self.ev_a = self.make_node("A", IP_A, "nodeA")
        self.b, self.ev_b = self.make_node("B", IP_B, "nodeB")
        uri = self.a.begin_pairing()
        self.b.join_pairing(uri)
        self.assertTrue(wait_for(lambda: "nodeB" in self.a.pairings))
        self._backoff = outbox.BACKOFF
        outbox.BACKOFF = (0.05, 0.05, 0.05, 0.05, 0.05)

    def tearDown(self):
        outbox.BACKOFF = self._backoff
        for n in self.nodes:
            n.stop()
        self.tmp.cleanup()

    def make_node(self, label: str, ip: str, stable_id: str, port: int = 0):
        events: list[tuple[str, dict]] = []
        ts = FakeTSWithStatus(tsnet.SelfInfo(ip=ip, user_id=USER, name=f"PC-{label}", stable_id=stable_id),
                              self.table, self.ips)
        n = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=self.root / label / "app", ts=ts, port=port)
        n.settings["save_dir"] = str(self.root / label / "recv")
        n.start()
        self.assertTrue(wait_for(lambda: n.listener is not None and n.me is not None), "listen")
        self.nodes.append(n)
        return n, events

    def src_file(self, size: int, name: str = "big.bin") -> tuple[Path, bytes]:
        data = os.urandom(size)
        path = self.root / name
        path.write_bytes(data)
        return path, data

    def file_job(self, path: Path, peer: str = "nodeB") -> outbox.Job:
        st = path.stat()
        return outbox.Job(id="t", peer_id=peer, kind="file", path=str(path), xfer=p.new_xfer(), size=st.st_size,
                          mtime_ns=st.st_mtime_ns)

    def idle(self, n: nodemod.Node) -> bool:
        return not n.book.busy

    # ---------- 이어받기 ----------
    def test_cut_transfer_resumes_from_kept_part(self):
        size = p.CHUNK * 6 + 777
        src, data = self.src_file(size)
        job = self.file_job(src)

        def cut(done, total):
            if done >= p.CHUNK * 3:
                raise Stop()

        with self.assertRaises(Stop):
            self.a.deliver(job, cut)
        self.assertTrue(wait_for(lambda: self.idle(self.b) and self.b.book.have("nodeA", job.xfer, size) > 0))
        have = self.b.book.have("nodeA", job.xfer, size)
        self.assertGreater(have, 0)
        self.assertLess(have, size)

        seen = []
        self.a.deliver(job, lambda done, total: seen.append(done))
        self.assertGreaterEqual(seen[0], have + 1)  # 처음부터가 아니라 조각 뒤부터 보냈다
        self.assertEqual((self.root / "B" / "recv" / "big.bin").read_bytes(), data)
        self.assertEqual(list((self.root / "B" / "recv").glob("*.part")), [])
        self.assertTrue(self.b.book.is_done("nodeA", job.xfer))
        received = [d for k, d in self.ev_b if k == "received"]
        self.assertEqual(len(received), 1)

    def test_corrupted_part_is_caught_by_hash_then_resent(self):
        size = p.CHUNK * 4 + 5
        src, data = self.src_file(size)
        job = self.file_job(src)

        def cut(done, total):
            if done >= p.CHUNK * 2:
                raise Stop()

        with self.assertRaises(Stop):
            self.a.deliver(job, cut)
        self.assertTrue(wait_for(lambda: self.idle(self.b) and self.b.book.have("nodeA", job.xfer, size) > 0))
        part = next((self.root / "B" / "recv").glob("*.part"))
        raw = bytearray(part.read_bytes())
        raw[10] ^= 0xFF  # 받는 쪽 디스크에서 조각이 망가졌다
        part.write_bytes(bytes(raw))

        with self.assertRaises(p.ProtocolError) as ctx:
            self.a.deliver(job)
        self.assertEqual(ctx.exception.code, "hash_mismatch")
        self.assertTrue(outbox.is_retryable(ctx.exception))
        self.assertEqual(list((self.root / "B" / "recv").glob("*.part")), [])  # 망가진 조각은 지웠다

        self.a.deliver(job)  # 같은 xfer로 다시: 조각이 없으니 처음부터
        self.assertEqual((self.root / "B" / "recv" / "big.bin").read_bytes(), data)

    def test_resume_offset_without_part_is_resume_lost(self):
        src, _ = self.src_file(p.CHUNK + 1)
        pairing = self.a.pairings["nodeB"]
        sock, _ = self.a._connect("nodeB")
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self.a._send_key(pairing))
            with self.assertRaises(p.ProtocolError) as ctx:
                p.send_file_xfer(ch, "PC-A", src, p.new_xfer(), offset=100)
                p.read_reply(ch)
        self.assertEqual(ctx.exception.code, "resume_lost")

    def test_done_transfer_is_not_sent_twice(self):
        src, _ = self.src_file(p.CHUNK + 3)
        job = self.file_job(src)
        self.a.deliver(job)
        sent_before = sum(1 for k, _ in self.ev_a if k == "progress")
        self.a.deliver(job)  # 응답이 사라져 다시 보낸 경우: hello의 done을 보고 건너뛴다
        self.assertEqual(sum(1 for k, _ in self.ev_a if k == "progress"), sent_before)
        self.assertEqual(len(list((self.root / "B" / "recv").iterdir())), 1)
        self.assertEqual(sum(1 for k, _ in self.ev_b if k == "received"), 1)

    def test_duplicate_text_with_same_xfer_is_recorded_once(self):
        job = outbox.Job(id="t", peer_id="nodeB", kind="text", text="한 번만", xfer=p.new_xfer())
        self.a.deliver(job)
        self.a.deliver(job)
        self.assertEqual([d["text"] for k, d in self.ev_b if k == "received"], ["한 번만"])
        self.assertEqual(len(self.b.history.for_peer("nodeA")), 1)

    def test_hello_reports_caps_and_have(self):
        info = self.a._hello("nodeB", p.new_xfer(), 10)
        self.assertIn("xfer", info["caps"])
        self.assertEqual((info["have"], info["done"], info["cloud"]), (0, False, False))

    def test_trailer_vector(self):
        vec = VECTORS["xfer_trailer"]
        self.assertEqual(hashlib.sha256(bytes.fromhex(vec["data_hex"])).hexdigest(), vec["sha256"])

    # ---------- 보낼 곳 목록 ----------
    def test_outbox_waits_for_offline_peer_then_delivers(self):
        port_b = self.b.port
        self.b.stop()
        self.assertTrue(wait_for(lambda: self.b.listener is None))
        src, data = self.src_file(p.CHUNK * 2 + 9, "later.bin")
        [job] = self.a.outbox.add_paths("nodeB", [src])
        # 실패로 버리지 않고 다시 시도하며 기다린다
        self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id).attempts >= 2
                                 and self.a.outbox.get(job.id).state == outbox.WAITING))
        self.assertTrue(outbox.is_retryable(ConnectionRefusedError()))

        # B를 같은 설정 폴더·포트로 다시 켠다(앱 재시작과 같다)
        self.nodes.remove(self.b)
        self.b, self.ev_b = self.make_node("B", IP_B, "nodeB", port=port_b)
        self.a.outbox.wake("nodeB")
        self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id) is None, 10))
        self.assertEqual((self.root / "B" / "recv" / "later.bin").read_bytes(), data)

    def test_outbox_peer_refusal_fails_without_retry_then_manual_retry(self):
        orig = p.free_bytes
        p.free_bytes = lambda folder: 0  # B의 디스크가 가득 찼다
        try:
            [job] = self.a.outbox.add_paths("nodeB", [self.src_file(1000, "x.bin")[0]])
            self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id).state == outbox.FAILED))
            failed = self.a.outbox.get(job.id)
            self.assertEqual((failed.code, failed.attempts), ("disk_full", 1))
        finally:
            p.free_bytes = orig
        self.a.outbox.retry(job.id)
        self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id) is None))
        self.assertTrue((self.root / "B" / "recv" / "x.bin").is_file())

    def test_outbox_keeps_order_per_peer_and_texts(self):
        jobs = [self.a.outbox.add_text("nodeB", f"글 {i}") for i in range(5)]
        self.assertTrue(wait_for(lambda: all(self.a.outbox.get(j.id) is None for j in jobs)))
        self.assertEqual([d["text"] for k, d in self.ev_b if k == "received"], [f"글 {i}" for i in range(5)])

    def test_outbox_missing_file_fails_at_once(self):
        src, _ = self.src_file(10, "gone.bin")
        [job] = self.a.outbox.add_paths("nodeB", [src])
        self.a.outbox.stop()  # 지우기 전에 보내 버리지 않게 잠시 멈춘다
        src.unlink()
        self.a.outbox._stop = False
        self.a.outbox.start()
        self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id).state == outbox.FAILED))
        self.assertEqual(self.a.outbox.get(job.id).code, "LocalFileError")

    def test_outbox_cancel_while_sending_stops_transfer(self):
        src, _ = self.src_file(p.CHUNK * 40, "huge.bin")
        [job] = self.a.outbox.add_paths("nodeB", [src])
        self.assertTrue(wait_for(lambda: (self.a.outbox.get(job.id) or job).done > 0))
        self.a.outbox.cancel(job.id)
        self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id) is None))
        self.assertFalse((self.root / "B" / "recv" / "huge.bin").exists())

    def test_outbox_survives_restart(self):
        self.a.outbox.stop()
        self.a.outbox.jobs.clear()
        box = outbox.Outbox(lambda job, progress: None, self.root / "A" / "app", lambda k, d: None)
        job = box.add_text("nodeB", "다시 켜도")
        box.jobs[0].state = outbox.SENDING  # 보내는 중에 꺼졌다
        with box.lock:
            box._save()
        again = outbox.Outbox(lambda job, progress: None, self.root / "A" / "app", lambda k, d: None)
        self.assertEqual([(j.id, j.state, j.text) for j in again.jobs], [(job.id, outbox.WAITING, "다시 켜도")])

    def test_folder_job_packs_once_and_records_folder(self):
        folder = self.root / "사진들"
        (folder / "a").mkdir(parents=True)
        (folder / "a" / "1.txt").write_text("하나", "utf-8")
        [job] = self.a.outbox.add_paths("nodeB", [folder])
        self.assertEqual(job.kind, "folder")
        self.assertTrue(wait_for(lambda: self.a.outbox.get(job.id) is None))
        self.assertTrue((self.root / "B" / "recv" / "사진들.zip").is_file())
        sent = [d for k, d in self.ev_a if k == "sent"][-1]
        self.assertEqual(sent["path"], str(folder))
        self.assertFalse((self.root / "A" / "app" / "outbox" / job.id).exists())  # 묶은 zip은 지웠다

    def test_backoff_schedule(self):
        outbox.BACKOFF = self._backoff
        self.assertEqual([outbox.backoff(n) for n in range(1, 8)], [3, 10, 30, 60, 120, 300, 300])

    def test_retryable_classification(self):
        self.assertTrue(outbox.is_retryable(ConnectionRefusedError()))
        self.assertTrue(outbox.is_retryable(TimeoutError()))
        self.assertTrue(outbox.is_retryable(p.ProtocolError("disconnected")))
        self.assertTrue(outbox.is_retryable(p.FileChanged()))
        self.assertFalse(outbox.is_retryable(p.ProtocolError("disk_full")))
        self.assertFalse(outbox.is_retryable(outbox.LocalFileError("x")))
        self.assertFalse(outbox.is_retryable(FileNotFoundError()) and False)

    # ---------- 주소 갱신 ----------
    def test_peer_ip_change_is_followed(self):
        pa = self.a.pairings["nodeB"]
        self.a.pairings["nodeB"] = store.Pairing("127.0.0.9", pa.port, pa.name, pa.stable_id, pa.secret, pa.kind,
                                                 pa.role)  # 예전 주소(이제 아무도 없음)
        self.ips["nodeB"] = IP_B
        self.a.send_text("nodeB", "새 주소로")
        self.assertEqual(self.a.pairings["nodeB"].ip, IP_B)
        self.assertTrue(any(k == "received" and d["text"] == "새 주소로" for k, d in self.ev_b))


class ResumeBookTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.now = [1000.0]
        self.book = resume.ResumeBook(self.dir, clock=lambda: self.now[0])

    def tearDown(self):
        self.tmp.cleanup()

    def part(self, name: str, size: int) -> Path:
        path = self.dir / (name + ".part")
        path.write_bytes(b"x" * size)
        return path

    def test_claim_requires_same_size_and_enough_bytes_and_one_user(self):
        x = p.new_xfer()
        part = self.part("a", 50)
        self.book.begin("peer", x, 100, part, self.dir / "a")
        self.book.keep("peer", x, part)
        self.assertEqual(self.book.have("peer", x, 100), 50)
        self.assertEqual(self.book.have("peer", x, 99), 0)
        self.assertEqual(self.book.have("other", x, 100), 0)  # 남의 조각은 안 보인다
        self.assertIsNone(self.book.claim("peer", x, 100, 51))
        self.assertIsNotNone(self.book.claim("peer", x, 100, 50))
        self.assertIsNone(self.book.claim("peer", x, 100, 10))  # 이미 다른 연결이 쓰는 중

    def test_prune_deletes_old_parts_and_survives_reload(self):
        x = p.new_xfer()
        part = self.part("b", 5)
        self.book.begin("peer", x, 10, part, self.dir / "b")
        self.book.keep("peer", x, part)
        again = resume.ResumeBook(self.dir, clock=lambda: self.now[0])
        self.assertEqual(again.have("peer", x, 10), 5)
        self.now[0] += resume.KEEP_SEC + 1
        again.prune()
        self.assertFalse(part.exists())
        self.assertEqual(again.have("peer", x, 10), 0)

    def test_keep_after_takeover_removes_stale_part(self):
        x = p.new_xfer()
        old = self.part("c", 5)
        self.book.begin("peer", x, 10, old, self.dir / "c")
        new = self.part("c (1)", 0)
        self.book.busy.clear()  # 예전 연결이 멈춘 사이 새 연결이 처음부터 다시 시작했다
        self.book.begin("peer", x, 10, new, self.dir / "c")
        self.book.keep("peer", x, old)  # 예전 연결이 뒤늦게 끝남
        self.assertFalse(old.exists())
        self.assertTrue(new.exists())

    def test_forget_peer(self):
        x = p.new_xfer()
        part = self.part("d", 1)
        self.book.begin("peer", x, 2, part, self.dir / "d")
        self.book.keep("peer", x, part)
        self.book.finish("peer", p.new_xfer())
        self.book.forget_peer("peer")
        self.assertFalse(part.exists())
        self.assertEqual(self.book.done, {})


if __name__ == "__main__":
    unittest.main()
