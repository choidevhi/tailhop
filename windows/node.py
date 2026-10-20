"""PC 쪽 수신 서버와 송신. Tailscale IP에만 bind하고, 상대 기기를 IP·whois·암호키로 3중 확인한다.

상대는 폰(phone) 또는 다른 PC(pc)다. PC끼리는 한쪽이 페어링 링크·코드를 보여 주고(host)
다른 쪽이 그것을 입력해(joiner) 연결한다. 두 PC 모두 같은 포트에서 받고 상대 포트로 보낸다.

수신 중지(1.5.0): 대기 소켓을 닫아 포트 자체를 닫는다(아무 연결도 받지 않음). 앱은 그대로 돌고 보내기는 된다.
설정(settings.json의 "receiving", "pause_until")에 남아 다시 켜도 유지되고, 시간을 정했으면 그때 저절로 다시 연다.
진행 중이던 받기는 끝까지 받는다(이미 연결된 소켓은 닫지 않음).
"""
from __future__ import annotations

import json
import os
import socket
import threading
import time
from pathlib import Path
from typing import Callable

import outbox
import protocol as p
import resume
import store
import tsnet
from i18n import t

PC_PORT = p.DEFAULT_PC_PORT
PAIR_WINDOW_SEC = 120
MAX_CONNECTIONS = 4
PAUSED_POLL_SEC = 30
CAPS_TTL_SEC = 600  # 상대 능력(hello) 기억 시간. 실패하면 바로 잊는다
IP_REFRESH_SEC = 30  # 모르는 주소가 들어올 때 tailscale status로 주소표를 다시 읽는 최소 간격
SOCK_BUF = 4 * 1024 * 1024  # 먼 경로(DERP 중계)에서도 창이 막히지 않게 소켓 버퍼를 넉넉히


def _close_gently(conn: socket.socket, limit: int = p.MAX_FRAME + 64) -> None:
    """거부 응답을 보낸 뒤: 상대가 이미 보낸 프레임을 조금 읽어 버리고 닫는다.
    안 읽은 데이터가 남은 채 닫으면 Windows가 연결을 리셋(RST)해 상대가 응답을 못 읽는다."""
    conn.shutdown(socket.SHUT_WR)
    conn.settimeout(1)
    while limit > 0:
        data = conn.recv(min(65536, limit))
        if not data:
            break
        limit -= len(data)


class Node:
    def __init__(self, on_event: Callable[[str, dict], None], app_dir: Path | None = None, ts=tsnet,
                 port: int = PC_PORT) -> None:
        """app_dir: 설정·키·기록 폴더(테스트는 임시 폴더). ts: tsnet과 같은 모양의 객체(테스트는 가짜).
        port: 대기 포트(0이면 OS가 고른 포트를 쓴다)."""
        self.on_event = on_event
        self.app_dir = Path(app_dir) if app_dir is not None else store.APP_DIR
        self.ts = ts
        self.port = port
        self.pairings: dict[str, store.Pairing] = {x.stable_id: x for x in store.load_pairings(self.app_dir)}
        self.guard = p.ReplayGuard(self.app_dir / "seen.json")
        self.history = store.History(self.app_dir)
        self.book = resume.ResumeBook(self.app_dir)
        self.outbox = outbox.Outbox(self.deliver, self.app_dir, on_event)
        self._caps: dict[str, tuple[float, frozenset[str]]] = {}
        self._ip_refreshed = 0.0
        first_phone = next((x for x in self.pairings.values() if x.kind == p.KIND_PHONE), None)
        if first_phone is not None:
            # v1.1 이전 기록(기기 표시 없음)은 모두 폰과 주고받은 것이다.
            self.history.adopt_legacy(first_phone.stable_id, p.KIND_PHONE)
        self.settings = store.load_settings(self.app_dir)
        self.me: tsnet.SelfInfo | None = None
        self.listener: socket.socket | None = None
        self.slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
        self._pair_key: bytes | None = None
        self._pair_deadline = 0.0
        self._pair_raw: bytes | None = None
        self._lock = threading.Lock()
        self._settings_lock = threading.Lock()
        self._wake = threading.Event()
        self._state: tuple | None = None
        self._stop = False

    # ---------- 설정 ----------
    @property
    def save_dir(self) -> Path:
        return Path(self.settings.get("save_dir") or store.DEFAULT_SAVE_DIR)

    def set_save_dir(self, path: str) -> None:
        self.settings["save_dir"] = path
        self.save_settings()

    def save_settings(self) -> None:
        with self._settings_lock:
            store.save_settings(dict(self.settings), self.app_dir)

    def _save_pairings(self) -> None:
        store.save_pairings(list(self.pairings.values()), self.app_dir)

    # ---------- 수신 중지 ----------
    @property
    def receiving(self) -> bool:
        """지금 받는 중인지(중지 시간이 지났으면 여기서 다시 켠다)."""
        if self.settings.get("receiving", True):
            return True
        until = self.settings.get("pause_until")
        if isinstance(until, (int, float)) and until and time.time() >= until:
            self.settings["receiving"] = True
            self.settings.pop("pause_until", None)
            self.save_settings()
            return True
        return False

    @property
    def pause_until(self) -> float | None:
        """시간을 정해 멈췄으면 다시 받을 시각(유닉스 초), 아니면 None."""
        until = self.settings.get("pause_until")
        return float(until) if not self.receiving and isinstance(until, (int, float)) and until else None

    def set_receiving(self, on: bool, seconds: float | None = None) -> None:
        """수신을 켜거나 끈다. 끄면 대기 소켓을 바로 닫는다(포트 닫힘). seconds를 주면 그 뒤 저절로 다시 켠다."""
        self.settings["receiving"] = bool(on)
        if on or not seconds:
            self.settings.pop("pause_until", None)
        else:
            self.settings["pause_until"] = time.time() + float(seconds)
        self.save_settings()
        if not on:
            self.cancel_pairing()
            with self._lock:
                sock, self.listener = self.listener, None
            if sock is not None:
                sock.close()
        self._wake.set()

    # ---------- 서버 ----------
    def start(self) -> None:
        """Tailscale IP를 얻을 때까지 재시도하며 대기 소켓을 연다(수신 중지면 열지 않고 기다린다). 보낼 곳 목록도 돌린다."""
        threading.Thread(target=self._serve_forever, daemon=True).start()
        self.outbox.start()

    def stop(self) -> None:
        self._stop = True
        self.outbox.stop()
        with self._lock:
            sock, self.listener = self.listener, None
        if sock is not None:
            sock.close()
        self._wake.set()

    def _emit_state(self, kind: str, data: dict) -> None:
        """상태가 바뀔 때만 알린다(중지 중 주기 확인마다 같은 알림을 내지 않게)."""
        key = (kind, tuple(sorted(data.items())))
        if key != self._state:
            self._state = key
            self.on_event(kind, data)

    def _wait_paused(self) -> None:
        if self.me is None:
            try:
                self.me = self.ts.self_info()  # 보내기에 내 Tailscale 주소가 필요하다
            except Exception:  # noqa: BLE001 - 다음 확인 때 다시
                pass
        until = self.pause_until
        self._emit_state("paused", {"until": until, "name": self.me.name if self.me else ""})
        timeout = PAUSED_POLL_SEC if until is None else max(0.05, min(PAUSED_POLL_SEC, until - time.time()))
        self._wake.wait(timeout)

    def _serve_forever(self) -> None:
        while not self._stop:
            self._wake.clear()
            if not self.receiving:
                self._wait_paused()
                continue
            try:
                self.me = self.ts.self_info()
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                try:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                    sock.bind((self.me.ip, self.port))
                    sock.listen(8)
                except BaseException:
                    sock.close()
                    raise
                with self._lock:
                    if self._stop or not self.receiving:  # 여는 사이에 중지됐다
                        sock.close()
                        continue
                    self.port = sock.getsockname()[1]
                    self.listener = sock
                self._emit_state("listening", {"ip": self.me.ip, "name": self.me.name})
                while True:
                    conn, addr = sock.accept()
                    # 슬롯을 잡기 전에 싼 검사: 페어링된 IP이거나 페어링 창이 열려 있어야 한다.
                    # (아니면 아무것도 읽지 않고 닫는다. 미페어링 노드가 슬롯 4개를 붙잡지 못하게)
                    if not self._admissible(addr[0]):
                        conn.close()
                        self.on_event("rejected", {"ip": addr[0], "error": str(p.ProtocolError("unpaired")),
                                                   "code": "unpaired"})
                        continue
                    if not self.slots.acquire(blocking=False):
                        conn.close()
                        continue
                    threading.Thread(target=self._handle, args=(conn, addr[0]), daemon=True).start()
            except Exception as exc:  # noqa: BLE001 - 상태 표시 후 재시도
                with self._lock:
                    self.listener = None
                if self._stop:
                    return
                if not self.receiving:
                    continue  # 수신 중지로 닫았다
                self._emit_state("offline", {"error": str(exc)})
                self._wake.wait(5)

    def _admissible(self, ip: str) -> bool:
        if any(x.ip == ip for x in list(self.pairings.values())):
            return True
        with self._lock:
            if self._pair_key is not None and time.monotonic() < self._pair_deadline:
                return True
        # 페어링된 기기의 Tailscale 주소가 바뀌었을 수 있다(다시 로그인 등). 주소표는 가끔만 다시 읽는다.
        return self._refresh_ips(IP_REFRESH_SEC) and any(x.ip == ip for x in list(self.pairings.values()))

    def _refresh_ips(self, min_interval: float = 0.0) -> bool:
        """tailscale status의 주소표로 페어링된 기기의 주소를 고친다. 하나라도 바뀌면 True."""
        now = time.monotonic()
        if (self._ip_refreshed and now - self._ip_refreshed < min_interval) or not hasattr(self.ts, "peer_ips"):
            return False
        self._ip_refreshed = now
        try:
            table = self.ts.peer_ips()
        except Exception:  # noqa: BLE001 - Tailscale이 꺼져 있으면 그대로 둔다
            return False
        changed = False
        with self._lock:
            for sid, pairing in list(self.pairings.items()):
                ip = table.get(sid)
                if ip and ip != pairing.ip:
                    self.pairings[sid] = store.Pairing(ip, pairing.port, pairing.name, sid, pairing.secret,
                                                       pairing.kind, pairing.role)
                    changed = True
            if changed:
                self._save_pairings()
        return changed

    def _handle(self, conn: socket.socket, ip: str) -> None:
        try:
            # 인증 전에는 짧게 기다린다. 인증(whois·첫 프레임)이 끝나면 SOCKET_TIMEOUT으로 늘린다.
            conn.settimeout(p.HANDSHAKE_TIMEOUT)
            kind, prefix = p.read_prefix(conn)
            if kind == p.KIND_PAIR:
                self._handle_pair(conn, ip, prefix)
            else:
                self._handle_message(conn, ip, prefix)
        except Exception as exc:  # noqa: BLE001
            self.on_event("rejected", {"ip": ip, "error": str(exc), "code": getattr(exc, "code", "")})
        finally:
            conn.close()
            self.slots.release()

    def _verify_peer(self, ip: str) -> tsnet.Peer:
        """whois로 같은 사람 계정의 기기인지 확인한다. 태그 노드는 계정이 아니라 태그로 묶이므로
        (UserID가 공용 'tagged-devices') 같은 UserID여도 거부한다."""
        peer = self.ts.whois(ip)
        if getattr(peer, "tags", ()):
            raise p.ProtocolError("tagged_node")
        if self.me is None or peer.user_id != self.me.user_id:
            raise p.ProtocolError("not_my_account")
        return peer

    def _handle_pair(self, conn: socket.socket, ip: str, prefix: bytes) -> None:
        with self._lock:
            key = self._pair_key if time.monotonic() < self._pair_deadline else None
        if key is None:
            raise p.ProtocolError("pair_closed")
        peer = self._verify_peer(ip)
        ch = p.open_server(conn, prefix, key)
        raw, last = ch.recv()
        conn.settimeout(p.SOCKET_TIMEOUT)  # 페어링 키로 인증된 첫 프레임을 받았다
        try:
            header = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError) as exc:
            raise p.ProtocolError("pair_bad") from exc
        end, end_last = ch.recv()
        if last or end or not end_last or not isinstance(header, dict) or header.get("v") != 1 \
                or header.get("type") != "pair":
            raise p.ProtocolError("pair_bad")
        self.guard.check_and_add(header.get("id"), header.get("ts"))
        try:
            secret = p.b64u_decode(str(header.get("secret", "")))
        except ValueError as exc:
            raise p.ProtocolError("pair_info") from exc
        port = header.get("port")
        if len(secret) != 32 or not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
            raise p.ProtocolError("pair_info")
        # 폰은 device를 보내지 않는다(v1 그대로). PC는 "pc"를 보낸다.
        device = header.get("device", p.KIND_PHONE)
        if device not in (p.KIND_PHONE, p.KIND_PC):
            raise p.ProtocolError("unknown_device")
        if device == p.KIND_PC and self.me is not None and self.me.stable_id and peer.stable_id == self.me.stable_id:
            raise p.ProtocolError("self_pair")
        with self._lock:
            if self._pair_key is not key:
                raise p.ProtocolError("pair_used")
            if time.monotonic() >= self._pair_deadline:  # 받는 동안 120초가 지났다
                self._pair_key = None
                self._pair_raw = None
                raise p.ProtocolError("pair_closed")
            self._pair_key = None  # 1회용
            self._pair_raw = None
        name = p.clean_name(header.get("from") or "") or p.clean_name(peer.name) or "?"
        role = p.ROLE_HOST if device == p.KIND_PC else ""
        with self._lock:
            self.pairings[peer.stable_id] = store.Pairing(ip, port, name, peer.stable_id, secret, device, role)
            self._save_pairings()
        p.send_reply(ch, True, name=self.me.name if self.me else "PC")
        self.on_event("paired", {"id": peer.stable_id, "name": name, "kind": device, "code": p.confirm_code(secret)})

    def _handle_message(self, conn: socket.socket, ip: str, prefix: bytes) -> None:
        pairing = next((x for x in list(self.pairings.values()) if x.ip == ip), None)
        if pairing is None:
            raise p.ProtocolError("unpaired")
        peer = self._verify_peer(ip)
        if peer.stable_id != pairing.stable_id:
            raise p.ProtocolError("id_mismatch")
        conn.settimeout(p.SOCKET_TIMEOUT)  # 페어링된 IP + whois 같은 계정·같은 기기 ID
        _, recv_info = p.direction_infos(pairing.kind, pairing.role)
        ch = p.open_server(conn, prefix, p.derive_key(pairing.secret, recv_info))

        def progress(done: int, total: int) -> None:
            self.on_event("progress", {"dir": "in", "done": done, "total": total})

        self.outbox.wake(pairing.stable_id)  # 상대가 켜져 있다: 그 기기로 갈 대기를 깨운다
        try:
            got = p.receive_message(ch, self.guard, self.save_dir, progress, peer=pairing.stable_id, book=self.book)
        except p.ProtocolError as exc:
            # 인증된 상대에게는 이유를 알려 준다(태그 실패면 전송 자체가 안 됨).
            if exc.code != "auth_failed":
                try:
                    p.send_reply(ch, False, exc)
                    _close_gently(conn)
                except OSError:
                    pass
            raise
        kind = got.header.get("type")
        if kind == "unpair":
            # 상대가 페어링을 취소했다(폰에서 확인 코드가 다르다고 고름). 이 PC에서도 지우고 나서 답한다.
            self.unpair(pairing.stable_id)
            p.send_reply(ch, True)
            self.on_event("peer_unpaired", {"id": pairing.stable_id, "name": pairing.name})
            return
        if kind == "hello":
            p.send_reply(ch, True, **self._hello_reply(pairing.stable_id, got.header))
            return
        if got.dup:  # 이미 받은 글(응답이 사라져 다시 옴): 기록·알림 없이 받았다고만 답한다
            p.send_reply(ch, True)
            return
        who = {"peer": pairing.name, "peer_id": pairing.stable_id, "peer_kind": pairing.kind}
        if got.path is None:
            text = got.header["text"]
            item = self.history.add("in", "text", text=text, **who)
            if "xfer" in got.header:
                self.book.finish(pairing.stable_id, got.header["xfer"])  # 답하기 전에: 응답이 사라져도 두 번 받지 않게
            p.send_reply(ch, True)
            # 기록에는 긴 글의 앞부분만 남으므로 화면(클립보드 복사)에는 전체 글을 따로 넘긴다.
            self.on_event("received", {**item, "full_text": text})
        else:
            item = self.history.add("in", "file", name=got.path.name, path=str(got.path), size=got.path.stat().st_size, **who)
            p.send_reply(ch, True)
            self.on_event("received", item)

    def _hello_reply(self, peer: str, header: dict) -> dict:
        """§11.1 hello 응답: 아는 확장, 클라우드 여부, 이어받기 위치·끝났는지."""
        reply = {"caps": ["xfer"], "cloud": False, "have": 0, "done": False}
        xfer, size = header.get("xfer"), header.get("size")
        if resume.valid_xfer(xfer) and isinstance(size, int) and not isinstance(size, bool) and size >= 0:
            reply["done"] = self.book.is_done(peer, xfer)
            reply["have"] = 0 if reply["done"] else self.book.have(peer, xfer, size)
        return reply

    # ---------- 페어링 ----------
    def begin_pairing(self) -> str:
        """페어링 창을 연다(120초, 1회용). 폰 QR·PC 붙여넣기용 링크를 돌려준다."""
        if not self.receiving:
            raise RuntimeError(t("err.paused_pairing"))
        if self.me is None:
            raise RuntimeError(t("err.wait_tailscale"))
        key = os.urandom(32)
        with self._lock:
            self._pair_key = p.derive_key(key, p.INFO_PAIR)
            self._pair_deadline = time.monotonic() + PAIR_WINDOW_SEC
            self._pair_raw = key
        return p.pair_uri(self.me.ip, self.port, self.me.name, key)

    def pairing_code(self) -> str:
        """지금 열린 페어링 창의 짧은 코드(손으로 입력용). begin_pairing 뒤에만 쓴다."""
        with self._lock:
            key = self._pair_raw if self._pair_key is not None and time.monotonic() < self._pair_deadline else None
        if key is None or self.me is None:
            raise RuntimeError(t("err.pair_closed"))
        return p.pair_code(self.me.ip, self.port, key)

    def cancel_pairing(self) -> None:
        with self._lock:
            self._pair_key = None
            self._pair_raw = None

    def join_pairing(self, text: str) -> dict:
        """다른 PC가 보여 준 링크·코드로 연결한다(이 PC = joiner). 오래 걸리므로 백그라운드에서 부른다."""
        info = p.parse_pair_input(text)
        if self.me is None:
            raise RuntimeError(t("err.wait_tailscale"))
        if not self.ts.is_tailscale_ip(info.host):
            raise p.ProtocolError("not_tailscale_addr")
        if info.host == self.me.ip and info.port == self.port:
            raise p.ProtocolError("own_code")
        peer = self._verify_peer(info.host)  # 상대도 내 계정의 기기인지 먼저 확인
        if self.me.stable_id and peer.stable_id == self.me.stable_id:
            raise p.ProtocolError("self_pair")
        secret = os.urandom(32)
        sock = socket.create_connection((info.host, info.port), timeout=10, source_address=(self.me.ip, 0))
        with sock:
            sock.settimeout(p.SOCKET_TIMEOUT)
            ch = p.open_client(sock, p.KIND_PAIR, p.derive_key(info.key, p.INFO_PAIR))
            header = p.new_header("pair", self.me.name, secret=p.b64u_encode(secret), port=self.port, device=p.KIND_PC)
            ch.send(json.dumps(header).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            reply = p.read_reply(ch)
        name = p.clean_name(reply.get("name") or "") or p.clean_name(info.name) or p.clean_name(peer.name) or "PC"
        pairing = store.Pairing(info.host, info.port, name, peer.stable_id, secret, p.KIND_PC, p.ROLE_JOINER)
        with self._lock:
            self.pairings[peer.stable_id] = pairing
            self._save_pairings()
        event = {"id": peer.stable_id, "name": name, "kind": p.KIND_PC, "code": p.confirm_code(secret)}
        self.on_event("paired", event)
        return event

    def unpair(self, stable_id: str) -> None:
        """연결 해제: 페어링과 그 기기와 주고받은 기록(긴 글 파일 포함)을 함께 지운다(Android와 같게).
        받은 파일 자체(저장 폴더)는 사용자 파일이라 지우지 않는다."""
        with self._lock:
            self.pairings.pop(stable_id, None)
            self._save_pairings()
        self.history.clear_peer(stable_id)
        self.outbox.drop_peer(stable_id)
        self.book.forget_peer(stable_id)
        self._caps.pop(stable_id, None)

    # ---------- 보내기 (수신 중지와 상관없이 된다) ----------
    def _open(self, pairing: store.Pairing) -> socket.socket:
        sock = socket.create_connection((pairing.ip, pairing.port), timeout=10, source_address=(self.me.ip, 0))
        sock.settimeout(p.SOCKET_TIMEOUT)
        for opt in (socket.SO_SNDBUF, socket.SO_RCVBUF):
            try:
                sock.setsockopt(socket.SOL_SOCKET, opt, SOCK_BUF)
            except OSError:
                pass
        return sock

    def _connect(self, stable_id: str) -> tuple[socket.socket, store.Pairing]:
        pairing = self.pairings.get(stable_id)
        if pairing is None:
            raise RuntimeError(t("err.pair_first"))
        if self.me is None:
            raise p.ProtocolError("tailscale_down")
        try:
            return self._open(pairing), pairing
        except OSError:
            # 상대 주소가 바뀌었는지 보고, 바뀌었으면 새 주소로 한 번 더
            if not self._refresh_ips():
                raise
            pairing = self.pairings[stable_id]
            return self._open(pairing), pairing

    @staticmethod
    def _send_key(pairing: store.Pairing) -> bytes:
        send_info, _ = p.direction_infos(pairing.kind, pairing.role)
        return p.derive_key(pairing.secret, send_info)

    def _hello(self, stable_id: str, xfer: str | None = None, size: int | None = None) -> dict:
        """상대에게 hello를 묻는다. 예전 기기(unsupported)면 caps가 빈 응답을 만든다. 능력은 잠시 기억한다."""
        sock, pairing = self._connect(stable_id)
        legacy = {"caps": [], "cloud": False, "have": 0, "done": False}
        remember = time.monotonic()
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            try:
                reply = p.send_hello(ch, self.me.name, xfer, size)
            except p.ProtocolError as exc:
                if exc.code not in ("unsupported", "disconnected"):
                    raise
                reply = legacy
                if exc.code == "disconnected":
                    remember -= CAPS_TTL_SEC - 60  # 끊김은 잠깐만 예전 기기로 친다(진짜 망 끊김일 수도)
            except ConnectionError:
                # 예전 Android(1.5)는 hello에 unsupported로 답한 뒤 남은 프레임을 읽지 않고 닫는다.
                # 그 리셋(RST)에 답이 묻혀 사라질 수 있으므로, 연결은 됐는데 끊기면 예전 기기로 보고 예전 방식으로 보낸다.
                # (새 기기였다면 예전 방식도 받는다. 이번 한 번 이어받기를 못 쓸 뿐이다.)
                reply = legacy
                remember -= CAPS_TTL_SEC - 60
        caps = reply.get("caps")
        caps = frozenset(x for x in caps if isinstance(x, str)) if isinstance(caps, list) else frozenset()
        self._caps[stable_id] = (remember, caps)
        reply["caps"] = caps
        return reply

    def _known_caps(self, stable_id: str) -> frozenset[str] | None:
        cached = self._caps.get(stable_id)
        if cached is None or time.monotonic() - cached[0] > CAPS_TTL_SEC:
            return None
        return cached[1]

    def deliver(self, job: outbox.Job, on_progress=None) -> None:
        """보낼 곳 목록의 한 건을 한 번 보낸다(실패하면 예외, 다시 시도는 Outbox가 정한다).
        job의 xfer·packed·size·mtime_ns는 여기서 바뀔 수 있다(Outbox가 다음 시도에 이어받는다)."""
        try:
            if job.kind == "text":
                self._deliver_text(job)
            else:
                self._deliver_file(job, on_progress)
        except Exception:
            self._caps.pop(job.peer_id, None)  # 상대가 바뀌었을 수 있다(앱 업데이트 등)
            raise

    def _deliver_text(self, job: outbox.Job) -> None:
        caps = self._known_caps(job.peer_id)
        if caps is None:
            caps = self._hello(job.peer_id)["caps"]
        sock, pairing = self._connect(job.peer_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            p.send_text(ch, self.me.name, job.text or "", job.xfer if "xfer" in caps else None)
            p.read_reply(ch)
        self.on_event("sent", self.history.add("out", "text", text=job.text or "", peer=pairing.name,
                                               peer_id=job.peer_id, peer_kind=pairing.kind))

    def _source(self, job: outbox.Job) -> tuple[Path, Path]:
        """(보낼 파일, 기록에 남길 경로). 폴더는 처음 한 번 zip으로 묶어 두고 다시 시도 때도 같은 zip을 쓴다.
        파일이 대기 중에 바뀌었으면(크기·수정 시각) 새 xfer로 처음부터 보내게 한다."""
        src = Path(job.path or "")
        if job.kind == "folder":
            if not src.is_dir():
                raise outbox.LocalFileError(t("err.source_missing", name=src.name))
            packed = Path(job.packed) if job.packed else None
            if packed is None or not packed.is_file():
                self.on_event("packing", {"name": src.name, "peer": job.peer_id})
                packed = _pack_folder(src, self.outbox.work_dir / job.id)
                job.packed = str(packed)
            target = packed
        else:
            if not src.is_file():
                raise outbox.LocalFileError(t("err.source_missing", name=src.name))
            target = src
        st = target.stat()
        if (st.st_size, st.st_mtime_ns) != (job.size, job.mtime_ns):
            job.size, job.mtime_ns, job.xfer = st.st_size, st.st_mtime_ns, p.new_xfer()
        return target, Path(job.record or job.path or target)

    def _deliver_file(self, job: outbox.Job, on_progress) -> None:
        path, record = self._source(job)
        size = job.size
        info = self._hello(job.peer_id, job.xfer, size)
        if "xfer" in info["caps"] and info.get("done") is True:
            pairing = self.pairings[job.peer_id]  # 이미 다 받았다(응답만 사라졌었다)
        else:
            have = info.get("have")
            offset = have if isinstance(have, int) and not isinstance(have, bool) and 0 <= have <= size else 0

            def progress(done: int, total: int) -> None:
                self.on_event("progress", {"dir": "out", "done": done, "total": total, "name": path.name,
                                           "peer": job.peer_id})
                if on_progress:
                    on_progress(done, total)

            sock, pairing = self._connect(job.peer_id)
            with sock:
                ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
                if "xfer" in info["caps"]:
                    p.send_file_xfer(ch, self.me.name, path, job.xfer, offset, progress)
                else:
                    p.send_file(ch, self.me.name, path, progress)
                p.read_reply(ch)
        self.on_event("sent", self.history.add("out", "file", name=path.name, path=str(record), size=size,
                                               peer=pairing.name, peer_id=job.peer_id, peer_kind=pairing.kind))

    # 한 번만 보내는 길(테스트·명령 없이 바로 보낼 때). 화면은 보낼 곳 목록(self.outbox)을 쓴다.
    def send_text(self, stable_id: str, text: str) -> None:
        self.deliver(outbox.Job(id="direct", peer_id=stable_id, kind="text", text=text, xfer=p.new_xfer()))

    def send_file(self, stable_id: str, path: Path, record_path: Path | None = None) -> None:
        """record_path: 기록에 남길 원본 경로(폴더를 임시 zip으로 묶어 보낼 때 원래 폴더)."""
        st = Path(path).stat()
        self.deliver(outbox.Job(id="direct", peer_id=stable_id, kind="file", path=str(path), xfer=p.new_xfer(),
                                record=str(record_path) if record_path else None, size=st.st_size,
                                mtime_ns=st.st_mtime_ns))


def _pack_folder(folder: Path, out_dir: Path) -> Path:
    import bridge  # bridge는 Windows 셸 기능(단축키·바로 가기)을 함께 들고 있어 쓸 때만 불러온다
    return bridge.pack_folder(folder, out_dir)
