"""두 노드 시뮬레이션: 한 PC 안에서 루프백 주소 두 개(127.0.0.1, 127.0.0.2)에 Node를 띄우고
가짜 Tailscale(whois)로 PC ↔ PC 페어링·양방향 전송·거부 규칙을 확인한다.

실제 사용자 설정 폴더(%APPDATA%\\TailHop)는 건드리지 않는다: 모든 Node는 임시 폴더를 쓰고,
store.APP_DIR도 임시 폴더로 바꿔 둔다(실수로 기본값을 써도 임시 폴더로 간다).
"""
from __future__ import annotations

import json
import os
import socket
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

import node as nodemod  # noqa: E402

USER, OTHER_USER = 111, 222
IP_A, IP_B, IP_C, IP_X = "127.0.0.1", "127.0.0.2", "127.0.0.3", "127.0.0.4"


class FakeTS:
    """tsnet 대신 쓰는 가짜. whois 표는 모든 노드가 공유한다(같은 tailnet)."""

    def __init__(self, me: tsnet.SelfInfo, table: dict[str, tsnet.Peer]) -> None:
        self.me, self.table = me, table

    def self_info(self) -> tsnet.SelfInfo:
        return self.me

    def whois(self, ip: str) -> tsnet.Peer:
        if ip not in self.table:
            raise RuntimeError("unknown peer")
        return self.table[ip]

    @staticmethod
    def is_tailscale_ip(ip: str) -> bool:
        return ip.startswith("127.")


def wait_for(cond, timeout: float = 5.0) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return True
        time.sleep(0.02)
    return False


class TwoNodeTests(unittest.TestCase):
    def setUp(self):
        # 멈춘 노드의 마지막 수신 스레드가 정리 직전에 파일을 쓸 수 있어 정리 오류는 무시한다(임시 폴더라 남아도 된다)
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.table = {
            IP_A: tsnet.Peer("nodeA", USER, "pc-a"),
            IP_B: tsnet.Peer("nodeB", USER, "pc-b"),
            IP_C: tsnet.Peer("nodeC", USER, "pc-c"),
            IP_X: tsnet.Peer("nodeX", OTHER_USER, "stranger"),
        }
        self.a, self.ev_a = self.make_node("A", IP_A, "nodeA", USER)
        self.b, self.ev_b = self.make_node("B", IP_B, "nodeB", USER)

    def tearDown(self):
        for n in getattr(self, "nodes", []):
            n.stop()
        self.tmp.cleanup()

    def make_node(self, label: str, ip: str, stable_id: str, user: int):
        events: list[tuple[str, dict]] = []
        ts = FakeTS(tsnet.SelfInfo(ip=ip, user_id=user, name=f"PC-{label}", stable_id=stable_id), self.table)
        n = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=self.root / label / "app", ts=ts, port=0)
        n.settings["save_dir"] = str(self.root / label / "recv")
        n.start()
        self.assertTrue(wait_for(lambda: n.listener is not None and n.me is not None), "listen")
        self.nodes = getattr(self, "nodes", []) + [n]
        return n, events

    def pair_ab(self, use_uri: bool = False) -> dict:
        uri = self.a.begin_pairing()
        text = uri if use_uri else self.a.pairing_code()
        result = self.b.join_pairing(text)
        self.assertTrue(wait_for(lambda: any(k == "paired" for k, _ in self.ev_a)))
        return result

    def raw_send(self, src_ip: str, dst: nodemod.Node, key: bytes, header: dict) -> dict:
        s = socket.create_connection((dst.me.ip, dst.port), timeout=5, source_address=(src_ip, 0))
        s.settimeout(5)
        with s:
            ch = p.open_client(s, p.KIND_MSG, key)
            ch.send(json.dumps(header).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            return p.read_reply(ch)

    def rejected_count(self, events) -> int:
        return sum(1 for k, _ in events if k == "rejected")

    # ---------- 페어링 ----------
    def test_pair_with_short_code_then_both_directions(self):
        result = self.pair_ab()
        host_event = next(d for k, d in self.ev_a if k == "paired")
        self.assertEqual(result["code"], host_event["code"])  # 양쪽 확인 코드가 같다
        pa, pb = self.a.pairings["nodeB"], self.b.pairings["nodeA"]
        self.assertEqual((pa.kind, pa.role, pb.kind, pb.role), ("pc", "host", "pc", "joiner"))
        self.assertEqual(pa.secret, pb.secret)
        self.assertEqual((pa.port, pb.port), (self.b.port, self.a.port))
        self.assertEqual(pb.name, "PC-A")
        self.assertEqual(pa.name, "PC-B")

        self.b.send_text("nodeA", "B에서 A로")
        self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_a)))
        got = next(d for k, d in self.ev_a if k == "received")
        self.assertEqual((got["text"], got["peer_id"], got["peer_kind"]), ("B에서 A로", "nodeB", "pc"))

        src = self.root / "보고서.bin"
        data = os.urandom(p.CHUNK * 2 + 123)
        src.write_bytes(data)
        self.a.send_file("nodeB", src)
        self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_b)))
        self.assertEqual((self.root / "B" / "recv" / "보고서.bin").read_bytes(), data)

        # 페어링은 DPAPI로 저장되고 다시 읽힌다.
        again = {x.stable_id: x for x in store.load_pairings(self.root / "B" / "app")}
        self.assertEqual(again["nodeA"].role, "joiner")
        self.assertNotIn(pb.secret.hex(), (self.root / "B" / "app" / "pairings.json").read_text("utf-8"))

    def test_folder_is_sent_as_zip_and_history_keeps_folder(self):
        import zipfile

        import bridge

        self.pair_ab()
        folder = self.root / "프로젝트"
        (folder / "src").mkdir(parents=True)
        (folder / "src" / "main.py").write_text("print(1)", "utf-8")
        packed = bridge.pack_folder(folder, self.root / "tmpzip")
        self.a.send_file("nodeB", packed, record_path=folder)
        self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_b)))
        with zipfile.ZipFile(self.root / "B" / "recv" / "프로젝트.zip") as zf:
            self.assertEqual(zf.read("src/main.py"), b"print(1)")
        sent = self.a.history.for_peer("nodeB")[-1]
        self.assertEqual((sent["name"], sent["path"]), ("프로젝트.zip", str(folder)))

    def test_pair_with_link(self):
        self.pair_ab(use_uri=True)
        self.b.send_text("nodeA", "link ok")
        self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_a)))

    def test_pair_window_is_one_time(self):
        uri = self.a.begin_pairing()
        code = self.a.pairing_code()
        self.b.join_pairing(code)
        c, _ = self.make_node("C", IP_C, "nodeC", USER)
        with self.assertRaises(Exception):
            c.join_pairing(uri)
        with self.assertRaises(RuntimeError):
            self.a.pairing_code()  # 창이 닫혀 코드도 더 못 꺼낸다
        self.assertEqual(set(self.a.pairings), {"nodeB"})

    def test_pair_window_expires(self):
        code = (self.a.begin_pairing(), self.a.pairing_code())[1]
        self.a._pair_deadline = time.monotonic() - 1
        with self.assertRaises(Exception):
            self.b.join_pairing(code)
        self.assertEqual(self.a.pairings, {})
        self.assertEqual(self.b.pairings, {})

    def test_cancelled_window_rejects(self):
        code = (self.a.begin_pairing(), self.a.pairing_code())[1]
        self.a.cancel_pairing()
        with self.assertRaises(Exception):
            self.b.join_pairing(code)
        self.assertEqual(self.a.pairings, {})

    def test_host_rejects_other_user(self):
        x, _ = self.make_node("X", IP_X, "nodeX", OTHER_USER)
        # 공격자 노드가 자기 계정은 같다고 믿게 해도(호스트 whois 는 OTHER_USER) 호스트가 막는다.
        x.ts.me = tsnet.SelfInfo(IP_X, USER, "PC-X", "nodeX")
        x.me = x.ts.me
        self.a.begin_pairing()
        code = self.a.pairing_code()
        with self.assertRaises(Exception):
            x.join_pairing(code)
        self.assertEqual(self.a.pairings, {})
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "not_my_account" for k, d in self.ev_a)))
        # 창은 그대로 열려 있어 정상 기기는 이어서 연결할 수 있다.
        self.b.join_pairing(code)
        self.assertIn("nodeB", self.a.pairings)

    def test_joiner_refuses_host_of_other_user(self):
        self.table[IP_A] = tsnet.Peer("nodeA", OTHER_USER, "pc-a")
        self.a.begin_pairing()
        code = self.a.pairing_code()
        with self.assertRaises(p.ProtocolError):
            self.b.join_pairing(code)
        self.assertEqual(self.b.pairings, {})
        self.assertEqual(self.rejected_count(self.ev_a), 0)  # 연결 전에 멈춘다

    def test_cannot_pair_with_self(self):
        self.a.begin_pairing()
        with self.assertRaises(p.ProtocolError):
            self.a.join_pairing(self.a.pairing_code())
        self.assertEqual(self.a.pairings, {})

    def test_typo_in_code_detected(self):
        self.a.begin_pairing()
        code = self.a.pairing_code()
        last = code[-1]
        bad = code[:-1] + ("0" if last != "0" else "1")
        with self.assertRaises(p.ProtocolError):
            self.b.join_pairing(bad)
        self.assertEqual(self.rejected_count(self.ev_a), 0)

    # ---------- 메시지 거부 ----------
    def test_unpaired_device_rejected(self):
        self.pair_ab()
        c, _ = self.make_node("C", IP_C, "nodeC", USER)
        with self.assertRaises(Exception):
            self.raw_send(IP_C, self.a, p.derive_key(os.urandom(32), p.INFO_PC_JOINER_TO_HOST), p.new_header("text", "c", text="x"))
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "unpaired" for k, d in self.ev_a)))

    def test_paired_ip_but_other_user_rejected(self):
        self.pair_ab()
        secret = self.a.pairings["nodeB"].secret
        self.table[IP_B] = tsnet.Peer("nodeB", OTHER_USER, "pc-b")
        with self.assertRaises(Exception):
            self.raw_send(IP_B, self.a, p.derive_key(secret, p.INFO_PC_JOINER_TO_HOST), p.new_header("text", "b", text="x"))
        self.assertFalse(any(k == "received" for k, _ in self.ev_a))

    def test_stable_id_mismatch_rejected(self):
        self.pair_ab()
        secret = self.a.pairings["nodeB"].secret
        self.table[IP_B] = tsnet.Peer("someone-else", USER, "pc-b")
        with self.assertRaises(Exception):
            self.raw_send(IP_B, self.a, p.derive_key(secret, p.INFO_PC_JOINER_TO_HOST), p.new_header("text", "b", text="x"))
        self.assertFalse(any(k == "received" for k, _ in self.ev_a))

    def test_wrong_direction_key_rejected(self):
        self.pair_ab()
        secret = self.a.pairings["nodeB"].secret
        # host(A)가 받는 키는 joiner→host 하나뿐. 반대 방향 키·폰용 키는 같은 비밀에서 나와도 통하지 않는다.
        for info in (p.INFO_PC_HOST_TO_JOINER, p.INFO_PHONE_TO_PC, p.INFO_PC_TO_PHONE):
            with self.assertRaises(Exception):
                self.raw_send(IP_B, self.a, p.derive_key(secret, info), p.new_header("text", "b", text="x"))
        self.assertFalse(any(k == "received" for k, _ in self.ev_a))
        reply = self.raw_send(IP_B, self.a, p.derive_key(secret, p.INFO_PC_JOINER_TO_HOST), p.new_header("text", "b", text="ok"))
        self.assertTrue(reply["ok"])
        # joiner(B)도 마찬가지: host→joiner 키만 받는다.
        with self.assertRaises(Exception):
            self.raw_send(IP_A, self.b, p.derive_key(secret, p.INFO_PC_JOINER_TO_HOST), p.new_header("text", "a", text="x"))
        self.assertTrue(self.raw_send(IP_A, self.b, p.derive_key(secret, p.INFO_PC_HOST_TO_JOINER),
                                      p.new_header("text", "a", text="ok"))["ok"])

    def test_replay_rejected(self):
        self.pair_ab()
        key = p.derive_key(self.a.pairings["nodeB"].secret, p.INFO_PC_JOINER_TO_HOST)
        header = p.new_header("text", "b", text="한 번만")
        self.assertTrue(self.raw_send(IP_B, self.a, key, header)["ok"])
        self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_a)))
        with self.assertRaises(Exception):
            self.raw_send(IP_B, self.a, key, header)
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "replay" for k, d in self.ev_a)))
        self.assertEqual(sum(1 for k, _ in self.ev_a if k == "received"), 1)

    def test_unpair_blocks(self):
        self.pair_ab()
        self.a.unpair("nodeB")
        with self.assertRaises(Exception):
            self.b.send_text("nodeA", "x")
        self.assertEqual(store.load_pairings(self.root / "A" / "app"), [])

    def test_repair_replaces_and_swaps_roles(self):
        self.pair_ab()
        old = self.a.pairings["nodeB"].secret
        self.b.begin_pairing()
        self.a.join_pairing(self.b.pairing_code())
        self.assertEqual((self.a.pairings["nodeB"].role, len(self.a.pairings)), ("joiner", 1))
        self.assertTrue(wait_for(lambda: self.b.pairings["nodeA"].role == "host"))
        self.assertNotEqual(self.a.pairings["nodeB"].secret, old)
        self.a.send_text("nodeB", "역할 바뀐 뒤")
        self.b.send_text("nodeA", "양쪽 다")

    # ---------- 수신 중지 ----------
    def port_open(self, ip: str, port: int) -> bool:
        try:
            socket.create_connection((ip, port), timeout=5, source_address=(IP_B, 0)).close()
            return True
        except OSError:
            return False

    def test_pause_closes_listener_and_resume_reopens(self):
        self.pair_ab()
        ip, port = self.a.me.ip, self.a.port
        self.assertTrue(self.port_open(ip, port))
        self.a.set_receiving(False)
        self.assertIsNone(self.a.listener)  # 바로 닫는다
        self.assertFalse(self.port_open(ip, port))  # 포트 자체가 닫혀 아무것도 받지 않는다
        self.assertTrue(wait_for(lambda: any(k == "paused" for k, _ in self.ev_a)))
        with self.assertRaises(OSError):
            self.b.send_text("nodeA", "중지 중")
        self.assertFalse(any(k == "received" for k, _ in self.ev_a))
        # 중지 중에도 보내기는 된다.
        self.a.send_text("nodeB", "A는 보낼 수 있다")
        self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_b)))
        # 새 기기 연결 창은 열 수 없다(받을 수 없으므로).
        with self.assertRaises(RuntimeError):
            self.a.begin_pairing()
        self.assertFalse(store.load_settings(self.root / "A" / "app")["receiving"])

        self.a.set_receiving(True)
        self.assertTrue(wait_for(lambda: self.a.listener is not None), "listener reopened")
        self.assertEqual(self.a.port, port)  # 같은 포트로 다시 연다
        self.b.send_text("nodeA", "다시 받기")
        self.assertTrue(wait_for(lambda: any(k == "received" and d["text"] == "다시 받기" for k, d in self.ev_a)))
        self.assertTrue(store.load_settings(self.root / "A" / "app")["receiving"])

    def test_pause_persists_across_restart(self):
        self.a.set_receiving(False)
        self.a.stop()
        events: list = []
        again = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=self.root / "A" / "app", ts=self.a.ts, port=0)
        again.start()
        self.nodes.append(again)
        self.assertTrue(wait_for(lambda: any(k == "paused" for k, _ in events)))
        time.sleep(0.2)
        self.assertIsNone(again.listener)
        self.assertFalse(again.receiving)
        self.assertIsNotNone(again.me)  # 중지 중에도 보내기용 내 주소는 알아 둔다
        again.set_receiving(True)
        self.assertTrue(wait_for(lambda: again.listener is not None))

    def test_timed_pause_resumes_by_itself(self):
        self.a.set_receiving(False, seconds=0.4)
        self.assertIsNotNone(self.a.pause_until)
        self.assertIsNone(self.a.listener)
        self.assertTrue(wait_for(lambda: self.a.listener is not None, timeout=5), "auto resume")
        self.assertTrue(self.a.receiving)
        saved = store.load_settings(self.root / "A" / "app")
        self.assertTrue(saved["receiving"])
        self.assertNotIn("pause_until", saved)

    def test_error_reply_keeps_korean_wire_text_and_code(self):
        """거부 응답: 예전 버전이 그대로 보여 주는 한국어 "error"와 새 버전이 번역하는 "code"를 함께 보낸다."""
        self.pair_ab()
        key = p.derive_key(self.a.pairings["nodeB"].secret, p.INFO_PC_JOINER_TO_HOST)
        header = p.new_header("text", "b", text="한 번만")
        self.assertTrue(self.raw_send(IP_B, self.a, key, header)["ok"])
        s = socket.create_connection((self.a.me.ip, self.a.port), timeout=5, source_address=(IP_B, 0))
        with s:
            ch = p.open_client(s, p.KIND_MSG, key)
            ch.send(json.dumps(header).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            raw, last = ch.recv()
        reply = json.loads(raw.decode("utf-8"))
        self.assertEqual((reply["ok"], reply["code"]), (False, "replay"))
        self.assertEqual(reply["error"], "이미 받은 메시지입니다(재전송 차단).")  # 1.4 그대로

    # ---------- 보안 점검 2차 ----------
    def raw_pair(self, src_ip: str, dst: nodemod.Node, uri: str, before_end=None, **fields) -> dict:
        info = p.parse_pair_input(uri)
        s = socket.create_connection((dst.me.ip, info.port), timeout=5, source_address=(src_ip, 0))
        s.settimeout(5)
        with s:
            ch = p.open_client(s, p.KIND_PAIR, p.derive_key(info.key, p.INFO_PAIR))
            header = {**p.new_header("pair", "Galaxy", secret=p.b64u_encode(os.urandom(32)), port=47101), **fields}
            ch.send(json.dumps(header).encode(), last=False)
            if before_end:
                time.sleep(0.2)
                before_end()
            ch.send(b"", last=True)
            return p.read_reply(ch)

    def test_unpaired_ip_does_not_take_slots_or_whois(self):
        """미페어링 IP는 슬롯을 잡기 전에(아무것도 읽지 않고) 닫힌다. 붙잡고 있어도 페어링된 기기는 보낼 수 있다."""
        self.pair_ab()
        calls: list[str] = []
        real_whois = self.a.ts.whois
        self.a.ts.whois = lambda ip: (calls.append(ip), real_whois(ip))[1]
        held = [socket.create_connection((IP_A, self.a.port), timeout=5, source_address=(IP_C, 0))
                for _ in range(nodemod.MAX_CONNECTIONS + 2)]
        try:
            for s in held:
                s.settimeout(3)
                try:
                    self.assertEqual(s.recv(1), b"")  # 서버가 바로 닫았다
                except ConnectionResetError:
                    pass
            self.b.send_text("nodeA", "슬롯 남아 있음")
            self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_a)))
        finally:
            for s in held:
                s.close()
        self.assertNotIn(IP_C, calls)  # 미페어링 IP에는 whois(외부 명령)도 돌리지 않는다
        self.assertTrue(any(k == "rejected" and d["code"] == "unpaired" and d["ip"] == IP_C for k, d in self.ev_a))

    def test_silent_paired_sockets_released_after_handshake_timeout(self):
        """인증 전 무응답 소켓은 짧은 시간(HANDSHAKE_TIMEOUT) 뒤 끊겨 슬롯을 돌려준다."""
        self.pair_ab()
        orig = p.HANDSHAKE_TIMEOUT
        p.HANDSHAKE_TIMEOUT = 0.3
        try:
            held = [socket.create_connection((IP_A, self.a.port), timeout=5, source_address=(IP_B, 0))
                    for _ in range(nodemod.MAX_CONNECTIONS)]
            time.sleep(1.0)
            self.b.send_text("nodeA", "타임아웃 뒤")
            self.assertTrue(wait_for(lambda: any(k == "received" for k, _ in self.ev_a)))
            for s in held:
                s.close()
        finally:
            p.HANDSHAKE_TIMEOUT = orig

    def test_tagged_node_rejected_for_pairing(self):
        self.table[IP_B] = tsnet.Peer("nodeB", USER, "pc-b", ("tag:server",))
        self.a.begin_pairing()
        code = self.a.pairing_code()
        with self.assertRaises(Exception):
            self.b.join_pairing(code)
        self.assertEqual(self.a.pairings, {})
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "tagged_node" for k, d in self.ev_a)))

    def test_joiner_refuses_tagged_host(self):
        self.table[IP_A] = tsnet.Peer("nodeA", USER, "pc-a", ("tag:shared",))
        self.a.begin_pairing()
        with self.assertRaises(p.ProtocolError) as cm:
            self.b.join_pairing(self.a.pairing_code())
        self.assertEqual(cm.exception.code, "tagged_node")
        self.assertEqual(self.rejected_count(self.ev_a), 0)  # 보내기 전에 멈춘다

    def test_tagged_node_rejected_for_messages(self):
        self.pair_ab()
        secret = self.a.pairings["nodeB"].secret
        self.table[IP_B] = tsnet.Peer("nodeB", USER, "pc-b", ("tag:server",))
        with self.assertRaises(Exception):
            self.raw_send(IP_B, self.a, p.derive_key(secret, p.INFO_PC_JOINER_TO_HOST), p.new_header("text", "b", text="x"))
        self.assertFalse(any(k == "received" for k, _ in self.ev_a))
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "tagged_node" for k, d in self.ev_a)))

    def test_pair_deadline_rechecked_when_consuming_key(self):
        """창이 열린 동안 시작했어도 끝 프레임이 오기 전에 120초가 지나면 저장하지 않는다."""
        uri = self.a.begin_pairing()

        def expire():
            self.a._pair_deadline = time.monotonic() - 1

        with self.assertRaises(Exception):
            self.raw_pair(IP_C, self.a, uri, before_end=expire)
        self.assertEqual(self.a.pairings, {})
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "pair_closed" for k, d in self.ev_a)))
        self.assertIsNone(self.a._pair_key)

    def test_pair_rejects_bool_port_and_cleans_name(self):
        uri = self.a.begin_pairing()
        with self.assertRaises(Exception):
            self.raw_pair(IP_C, self.a, uri, port=True)
        self.assertEqual(self.a.pairings, {})
        self.assertTrue(wait_for(lambda: any(k == "rejected" and d["code"] == "pair_info" for k, d in self.ev_a)))
        # 창은 그대로라 정상 요청은 통하고, 이름의 bidi·제어문자는 지운다.
        info = p.parse_pair_input(uri)
        s = socket.create_connection((IP_A, info.port), timeout=5, source_address=(IP_C, 0))
        with s:
            ch = p.open_client(s, p.KIND_PAIR, p.derive_key(info.key, p.INFO_PAIR))
            header = p.new_header("pair", "Gal\u202eaxy\n", secret=p.b64u_encode(os.urandom(32)), port=47101)
            ch.send(json.dumps(header).encode(), last=False)
            ch.send(b"", last=True)
            p.read_reply(ch)
        self.assertEqual(self.a.pairings["nodeC"].name, "Galaxy")

    def test_unpair_deletes_peer_history(self):
        self.pair_ab()
        self.a.history.add("in", "text", text="다른 기기", peer="C", peer_id="nodeC", peer_kind="phone")
        self.b.send_text("nodeA", "짧은 글")
        self.b.send_text("nodeA", "긴 글 " * 1000)
        self.assertTrue(wait_for(lambda: len(self.a.history.for_peer("nodeB")) == 2))
        self.assertEqual(len(list((self.root / "A" / "app" / "texts").glob("*.txt"))), 1)
        self.a.unpair("nodeB")
        self.assertEqual(self.a.history.for_peer("nodeB"), [])
        saved = json.loads((self.root / "A" / "app" / "history.json").read_text("utf-8"))
        self.assertEqual([i["peer_id"] for i in saved], ["nodeC"])  # 다른 기기 기록은 남는다
        self.assertEqual(list((self.root / "A" / "app" / "texts").glob("*.txt")), [])

    def test_phone_cancel_removes_pairing_on_pc(self):
        """폰이 확인 코드가 다르다고 고르면 type:"unpair"를 보낸다 → PC도 페어링·기록을 지운다."""
        uri = self.a.begin_pairing()
        info = p.parse_pair_input(uri)
        S = os.urandom(32)
        s = socket.create_connection((IP_A, info.port), timeout=5, source_address=(IP_C, 0))
        with s:
            ch = p.open_client(s, p.KIND_PAIR, p.derive_key(info.key, p.INFO_PAIR))
            ch.send(json.dumps(p.new_header("pair", "Galaxy", secret=p.b64u_encode(S), port=47101)).encode(), last=False)
            ch.send(b"", last=True)
            p.read_reply(ch)
        self.assertIn("nodeC", self.a.pairings)
        reply = self.raw_send(IP_C, self.a, p.derive_key(S, p.INFO_PHONE_TO_PC), p.new_header("unpair", "Galaxy"))
        self.assertTrue(reply["ok"])
        self.assertNotIn("nodeC", self.a.pairings)
        self.assertEqual(store.load_pairings(self.root / "A" / "app"), [])
        self.assertTrue(any(k == "peer_unpaired" and d["id"] == "nodeC" for k, d in self.ev_a))
        # 이제 그 비밀로는 아무것도 보낼 수 없다.
        with self.assertRaises(Exception):
            self.raw_send(IP_C, self.a, p.derive_key(S, p.INFO_PHONE_TO_PC), p.new_header("text", "Galaxy", text="x"))

    # ---------- 폰 (v1 그대로) ----------
    def test_phone_pairing_unchanged_on_wire(self):
        """안드로이드 앱이 보내는 v1 페어링(device 없음)이 그대로 폰으로 저장되고 양방향이 된다."""
        uri = self.a.begin_pairing()
        info = p.parse_pair_input(uri)
        phone_srv = socket.socket()
        phone_srv.bind((IP_C, 0))
        phone_srv.listen(1)
        phone_port = phone_srv.getsockname()[1]
        S = os.urandom(32)
        s = socket.create_connection((IP_A, info.port), source_address=(IP_C, 0))
        with s:
            ch = p.open_client(s, p.KIND_PAIR, p.derive_key(info.key, p.INFO_PAIR))
            ch.send(json.dumps(p.new_header("pair", "Galaxy", secret=p.b64u_encode(S), port=phone_port)).encode(), last=False)
            ch.send(b"", last=True)
            self.assertEqual(p.read_reply(ch)["name"], "PC-A")
        pc = self.a.pairings["nodeC"]
        self.assertEqual((pc.kind, pc.role, pc.port, pc.name), ("phone", "", phone_port, "Galaxy"))

        self.assertTrue(self.raw_send(IP_C, self.a, p.derive_key(S, p.INFO_PHONE_TO_PC), p.new_header("text", "Galaxy", text="폰"))["ok"])
        box = {}

        def phone_server():
            c, _ = phone_srv.accept()
            with c:
                _, prefix = p.read_prefix(c)
                ch2 = p.open_server(c, prefix, p.derive_key(S, p.INFO_PC_TO_PHONE))
                box["r"] = p.receive_message(ch2, p.ReplayGuard(self.root / "pseen.json"), self.root / "phone")
                p.send_reply(ch2, True)

        t = threading.Thread(target=phone_server)
        t.start()
        self.a.send_text("nodeC", "PC에서 폰으로")
        t.join(5)
        phone_srv.close()
        self.assertEqual(box["r"].header["text"], "PC에서 폰으로")


if __name__ == "__main__":
    unittest.main()
