"""2.2.0 꺼진 상대 기다리기(docs/PROTOCOL.md §11.5): 30일까지 기다림, "켜졌다" hello로 바로 보냄, Tailscale Online 확인.
임시 폴더와 빈 포트만 쓴다."""
from __future__ import annotations

import tempfile
import threading
import time
import unittest
from pathlib import Path

import outbox
import store
import tsnet

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import node as nodemod  # noqa: E402
from test_nodes import IP_A, IP_B, USER, FakeTS, wait_for  # noqa: E402


class GiveUpTests(unittest.TestCase):
    def test_offline_peer_is_waited_for_30_days_then_fails(self):
        tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        now = [1_000_000.0]

        def deliver(job, progress):
            raise ConnectionRefusedError()  # 상대 Tailscale이 꺼져 있다

        box = outbox.Outbox(deliver, Path(tmp.name), lambda k, d: None, clock=lambda: now[0])
        job = box.add_text("peer", "안녕")
        for days in (1, 2, 29):
            now[0] = 1_000_000.0 + days * 86400
            live = next(j for j in box.jobs if j.id == job.id)
            box._attempt(live, live.copy())
            self.assertEqual((box.get(job.id).state, box.get(job.id).code), (outbox.WAITING, "peer_offline"), days)
        self.assertEqual(box.waiting_offline(), {"peer"})
        now[0] = 1_000_000.0 + 31 * 86400
        live = next(j for j in box.jobs if j.id == job.id)
        box._attempt(live, live.copy())
        self.assertEqual(box.get(job.id).state, outbox.FAILED)
        box.cancel(job.id)  # 취소하면 목록에서 빠진다
        self.assertIsNone(box.get(job.id))
        tmp.cleanup()


class PresenceTests(unittest.TestCase):
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

    def park(self, n: nodemod.Node, peer: nodemod.Node) -> outbox.Job:
        """상대가 받기를 닫아(꺼짐) 실패한 건을 5분 뒤로 미룬다."""
        peer.set_receiving(False)
        self.assertTrue(wait_for(lambda: peer.listener is None))
        job = n.outbox.add_text(peer.me.stable_id, "기다리던 글")
        self.assertTrue(wait_for(lambda: (n.outbox.get(job.id).code or "") in outbox.OFFLINE_CODES
                                 and n.outbox.get(job.id).state == outbox.WAITING))
        with n.outbox.lock:
            next(j for j in n.outbox.jobs if j.id == job.id).next_at = time.time() + 300
        return job

    def test_peer_coming_online_announces_and_gets_queued_items_at_once(self):
        job = self.park(self.b, self.a)
        self.a._announced.clear()
        self.a.set_receiving(True)  # A가 다시 켜졌다: 받기를 열고 연결된 기기에 hello를 보낸다
        self.assertTrue(wait_for(lambda: self.b.outbox.get(job.id) is None, 8))
        self.assertEqual(self.a.history.items[-1]["text"], "기다리던 글")

    def test_tailscale_online_flag_wakes_waiting_jobs(self):
        old = nodemod.PRESENCE_SEC
        nodemod.PRESENCE_SEC = 0.1
        calls = []
        self.a.announce = lambda: None  # 이 테스트는 Online 확인만 본다
        try:
            self.b.ts.peer_online = lambda: calls.append(1) or {"nodeA": self.a.listener is not None}
            threading.Thread(target=self.b._presence_loop, daemon=True).start()
            job = self.park(self.b, self.a)
            self.a.set_receiving(True)
            self.assertTrue(wait_for(lambda: self.b.outbox.get(job.id) is None, 8))
            time.sleep(0.3)
            calls.clear()
            time.sleep(0.5)
            self.assertEqual(calls, [])  # 기다리는 건이 없으면 Tailscale에 묻지도 않는다
        finally:
            nodemod.PRESENCE_SEC = old


if __name__ == "__main__":
    unittest.main()
