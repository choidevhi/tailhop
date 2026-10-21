"""PC 쪽 수신 서버와 송신. Tailscale IP에만 bind하고, 상대 기기를 IP·whois·암호키로 3중 확인한다.

상대는 폰(phone) 또는 다른 PC(pc)다. PC끼리는 한쪽이 페어링 링크·코드를 보여 주고(host)
다른 쪽이 그것을 입력해(joiner) 연결한다. 두 PC 모두 같은 포트에서 받고 상대 포트로 보낸다.

수신 중지(1.5.0): 대기 소켓을 닫아 포트 자체를 닫는다(아무 연결도 받지 않음). 앱은 그대로 돌고 보내기는 된다.
설정(settings.json의 "receiving", "pause_until")에 남아 다시 켜도 유지되고, 시간을 정했으면 그때 저절로 다시 연다.
진행 중이던 받기는 끝까지 받는다(이미 연결된 소켓은 닫지 않음).
"""
from __future__ import annotations

import ctypes
import json
import os
import socket
import threading
import time
from pathlib import Path
from typing import Callable

import cloud
import cloudstore as cs
import hub as hubmod
import media
import outbox
import protocol as p
import resume
import store
import tsnet
import updates
from i18n import t

PC_PORT = p.DEFAULT_PC_PORT
PAIR_WINDOW_SEC = 120
MAX_CONNECTIONS = 4
PAUSED_POLL_SEC = 30
CAPS_TTL_SEC = 600  # 상대 능력(hello) 기억 시간. 실패하면 바로 잊는다
PRESENCE_SEC = 30  # 꺼진 상대를 기다리는 건이 있을 때만 이 간격으로 Tailscale에 켜졌는지 묻는다
ANNOUNCE_GAP_SEC = 60  # "켜졌다" hello를 같은 기기에 이보다 자주 보내지 않는다
IP_REFRESH_SEC = 30  # 모르는 주소가 들어올 때 tailscale status로 주소표를 다시 읽는 최소 간격
SOCK_BUF = 4 * 1024 * 1024  # 먼 경로(DERP 중계)에서도 창이 막히지 않게 소켓 버퍼를 넉넉히
DEFAULT_CLOUD_DIR = Path.home() / "TailHop Cloud"
STORE_IDLE_MIN = 30  # 강한 잠금: 이만큼 쓰지 않으면 잠근다(설정에서 바꿈, 0이면 시간으로는 안 잠금)
LOCK_POLL_SEC = 5
STORE_RECHECK_SEC = 5  # 저장소가 열려 있지 않으면 이 간격으로 다시 본다(드라이브를 다시 꽂음 등)
MOVE_BATCH = 200  # cloud_move 한 번에 보내는 경로 수(§16, 헤더가 한 프레임을 넘지 않게)


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
        self.shelf = updates.Shelf(self.app_dir)
        self._store: cs.Store | None = None
        self._store_checked = 0.0
        self._store_lock = threading.Lock()
        self.media: media.Worker | None = None  # 허브일 때만 만든다(미리보기·재생용 버전)
        self._store_used = time.monotonic()
        self._open_tokens: dict[tuple[str, str], str] = {}  # (허브 기기, 잠긴 폴더 경로): 그 허브가 준 열쇠
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
        self._announced: dict[str, float] = {}

    # ---------- 설정 ----------
    @property
    def save_dir(self) -> Path:
        return Path(self.settings.get("save_dir") or store.DEFAULT_SAVE_DIR)

    def set_save_dir(self, path: str) -> None:
        self.settings["save_dir"] = path
        self.save_settings()

    # ---------- 클라우드 모드(§12) ----------
    @property
    def cloud_on(self) -> bool:
        return self.settings.get("cloud") is True

    @property
    def cloud_dir(self) -> Path:
        return Path(self.settings.get("cloud_dir") or DEFAULT_CLOUD_DIR)

    @property
    def cloud_collect(self) -> bool:
        """대화방으로 받은 파일도 클라우드 폴더 맨 위에 모은다(켰을 때만)."""
        return self.cloud_on and self.settings.get("cloud_collect") is True

    def store(self) -> cs.Store | None:
        """클라우드가 켜져 있으면 그 폴더의 암호화 저장소(§17). 상태(missing·new·locked·open)는 store.state.
        열려 있지 않으면 몇 초에 한 번 다시 본다(외장 드라이브를 다시 꽂았을 때 저절로 열리게)."""
        if not self.cloud_on:
            return None
        with self._store_lock:
            if self._store is None or self._store.root != self.cloud_dir:
                if self._store is not None:
                    self._store.close()
                self._store = self._new_store(self.cloud_dir)
                self._store_checked = time.monotonic()
            elif time.monotonic() - self._store_checked > STORE_RECHECK_SEC:
                self._store_checked = time.monotonic()
                self._store.refresh()
                known, serial = self.settings.get("cloud_store_id"), self.settings.get("cloud_volume")
                moved = cs.find_moved(known, self.cloud_dir, serial if isinstance(serial, int) else None) \
                    if self._store.state == cs.MISSING and known else None
                if moved is not None:  # 외장 드라이브의 문자가 바뀌었다: 같은 저장소를 따라간다
                    self.settings["cloud_dir"] = str(moved)
                    self.save_settings()
                    self._store = self._new_store(moved)
            if self._store.state == cs.OPEN and self.settings.get("cloud_store_id") != self._store.id:
                self.settings["cloud_store_id"] = self._store.id
                self.settings["cloud_volume"] = cs.volume_serial(self._store.root)  # 문자가 바뀌어도 같은 드라이브인지
                self.save_settings()
            return self._store

    def _new_store(self, root: Path) -> cs.Store:
        """저장소를 만들고 미리보기 일꾼을 잇는다(열릴 때 밀린 것, 새로 들어온 사진·영상)."""
        if self.media is None:
            self.media = media.Worker(lambda: self._store)
        return cs.Store(root, self.app_dir, on_media=self.media.request, on_open=self.media.backfill,
                        strong=self.settings.get("store_strong") is True)

    def hub(self) -> hubmod.Hub | None:
        """저장소가 열려 있으면 허브. 잠긴 폴더 열쇠가 끝났으면 그 폴더 열쇠를 메모리에서 버린다."""
        st = self.store()
        if st is None or st.state != cs.OPEN:
            return None
        st.close_expired()
        self._store_used = time.monotonic()  # 강한 잠금의 '쓰지 않은 시간'은 여기서 센다
        return hubmod.Hub(st)

    def set_strong(self, on: bool, idle_min: int | None = None) -> None:
        """강한 잠금 켜기·끄기(§18.3). 끄려면 저장소가 열려 있어야 한다(이 PC 사본을 다시 만든다)."""
        st = self.store()
        if st is not None:
            st.set_strong(on)
        self.settings["store_strong"] = bool(on)
        if idle_min is not None:
            self.settings["store_idle_min"] = max(0, int(idle_min))
        self.save_settings()

    def _lock_loop(self) -> None:
        """강한 잠금: Windows가 잠기거나 정한 시간 동안 클라우드를 쓰지 않으면 잠근다(5초마다 본다)."""
        while not self._stop:
            time.sleep(LOCK_POLL_SEC)
            st = self._store
            if st is None or not st.strong or st.state != cs.OPEN:
                continue
            idle_min = self.settings.get("store_idle_min", STORE_IDLE_MIN)
            idle = isinstance(idle_min, int) and idle_min > 0 and time.monotonic() - self._store_used > idle_min * 60
            if (_session_locked() or idle) and st.lock_now():
                self.on_event("store_changed", {"peer": "", "state": st.state})

    @property
    def cloud_state(self) -> str:
        """화면·hello용: off | missing | new | locked | open."""
        st = self.store()
        return "off" if st is None else st.state

    def set_cloud(self, on: bool | None = None, folder: str | None = None, collect: bool | None = None) -> None:
        if on is not None:
            self.settings["cloud"] = bool(on)
        if folder is not None:
            self.settings["cloud_dir"] = folder
        if collect is not None:
            self.settings["cloud_collect"] = bool(collect)
        self.save_settings()
        if on is False or folder is not None:
            self.book.forget_kind("cloud_put")  # 끈 클라우드·예전 폴더에 이어 쓰지 않게
        if on is False and self._store is not None:
            with self._store_lock:
                self._store.close()
                self._store = None

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
        threading.Thread(target=self._presence_loop, daemon=True, name="tailhop-presence").start()
        threading.Thread(target=self._lock_loop, daemon=True, name="tailhop-lock").start()
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
                threading.Thread(target=self.announce, daemon=True, name="tailhop-announce").start()
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

    # ---------- 켜졌다 알리기·꺼진 상대 기다리기(2.2.0, §11.5) ----------
    def announce(self) -> None:
        """받기를 열었다(앱을 켬·Tailscale이 다시 붙음): 연결된 기기마다 hello를 한 번 보낸다. 받은 쪽은 이 PC로 가려고
        기다리던 것을 바로 보낸다(받은 쪽 대기열이 깨어난다). 실패는 조용히 넘긴다(상대가 꺼져 있으면 그쪽이 켜질 때 알린다)."""
        now = time.monotonic()
        for sid in list(self.pairings):
            if now - self._announced.get(sid, -ANNOUNCE_GAP_SEC) < ANNOUNCE_GAP_SEC:
                continue
            self._announced[sid] = now

            def hello(s=sid) -> None:
                try:
                    self._hello(s)
                except Exception:  # noqa: BLE001 - 꺼져 있거나 예전 기기
                    return
                self.outbox.wake(s)  # 닿았다: 그 기기로 가려던 것도 바로

            threading.Thread(target=hello, daemon=True, name="tailhop-announce").start()

    def _presence_loop(self) -> None:
        """꺼진 상대를 기다리는 건이 있을 때만 30초마다 Tailscale에 그 기기가 켜졌는지 묻고, 켜졌으면 바로 깨운다.
        기다리는 건이 없으면 아무것도 하지 않는다."""
        while not self._stop:
            time.sleep(PRESENCE_SEC)
            waiting = self.outbox.waiting_offline()
            if not waiting:
                continue
            try:
                online = self.ts.peer_online()
            except Exception:  # noqa: BLE001 - Tailscale이 꺼져 있다
                continue
            for sid in waiting:
                if online.get(sid):
                    self.outbox.wake(sid)

    def _admissible(self, ip: str) -> bool:
        if any(x.ip == ip for x in list(self.pairings.values())):
            return True
        with self._lock:
            if self._pair_key is not None and time.monotonic() < self._pair_deadline:
                return True
        # 페어링된 기기의 Tailscale 주소가 바뀌었을 수 있다(다시 로그인 등). 주소표는 가끔만, 받기 루프를 막지 않게
        # 따로 스레드에서 다시 읽는다. 이번 연결은 닫고, 주소가 고쳐지면 상대의 다음 시도부터 받는다.
        now = time.monotonic()
        if not self._ip_refreshed or now - self._ip_refreshed >= IP_REFRESH_SEC:
            self._ip_refreshed = now
            threading.Thread(target=self._refresh_ips, daemon=True).start()
        return False

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
        hub = self.hub()
        collect = hubmod.StoreTarget(hub.store, "", cs.MAIN) if hub is not None and self.cloud_collect else None
        reason = {"off": "cloud_off", "missing": "store_missing"}.get(self.cloud_state, "store_locked")
        try:
            got = p.receive_message(ch, self.guard, self.save_dir, progress, peer=pairing.stable_id, book=self.book,
                                    cloud=hubmod.HubAccess(hub, pairing.stable_id) if hub else None,
                                    collect=collect, cloud_off=reason)
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
        if kind in p.CLOUD_REQUESTS:
            self._serve_cloud(conn, ch, hub, got.header, pairing)
            return
        if kind in p.STORE_REQUESTS:
            self._serve_store(conn, ch, got.header, pairing)
            return
        if kind == "update_get":
            self._serve_update(conn, ch, got.header)
            return
        if kind == "cloud_put":
            p.send_reply(ch, True, name=got.stored)
            hidden = hub is not None and hub.is_vault_path(str(got.header.get("dir") or ""))
            # 잠긴 폴더에 들어온 이름은 알림(Windows 알림 기록에 남는다)에 쓰지 않는다
            self.on_event("cloud_in", {"peer": pairing.name, "peer_id": pairing.stable_id,
                                       "name": "" if hidden else got.stored})
            return
        if got.dup:  # 이미 받은 글(응답이 사라져 다시 옴): 기록·알림 없이 받았다고만 답한다
            p.send_reply(ch, True)
            return
        who = {"peer": pairing.name, "peer_id": pairing.stable_id, "peer_kind": pairing.kind}
        if got.stored is not None:  # 클라우드에 모았다(암호화 저장소 맨 위, 평문 파일은 없다)
            item = self.history.add("in", "file", name=got.stored, cloud=got.stored, size=got.header["size"], **who)
            p.send_reply(ch, True)
            self.on_event("received", item)
        elif got.path is None:
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
        state = self.cloud_state
        reply = {"caps": ["xfer", "cloud", "cloud2", "update", "move", "move2", "store", "rename", "store_unlock"],
                 "cloud": state == cs.OPEN,
                 "have": 0, "done": False}
        if state != "off":
            reply["store"] = state  # §17: 켜져 있지만 열리지 않음(missing·new·locked)을 앱이 알 수 있게
            if self.settings.get("store_strong") is True:
                reply["strong"] = True  # §18.3: 강한 잠금(폰에서 "지금 잠그기"를 보인다)
        offered = self.shelf.advert()
        if offered:
            reply["updates"] = offered
        xfer, size = header.get("xfer"), header.get("size")
        if resume.valid_xfer(xfer) and isinstance(size, int) and not isinstance(size, bool) and size >= 0:
            reply["done"] = self.book.is_done(peer, xfer)
            st = self._store if state == cs.OPEN else None
            have = max(self.book.have(peer, xfer, size), st.partial_have(peer, xfer, size) if st else 0)
            reply["have"] = 0 if reply["done"] else have
        return reply

    def _serve_cloud(self, conn: socket.socket, ch: p.Channel, hub: hubmod.Hub, header: dict,
                     pairing: store.Pairing) -> None:
        """클라우드 요청에 답한다(§12, §14, §15). 거부는 이유 코드와 함께."""
        kind, peer = header["type"], pairing.stable_id
        changed = True
        try:
            parts = cloud.parse_path(header.get("path", ""))
            if kind in ("cloud_list", "cloud_search"):
                cursor = header.get("cursor", 0)
                if not _uint(cursor):
                    raise p.ProtocolError("bad_header")
                if kind == "cloud_list":
                    items, nxt = hub.list(parts, cursor, header, peer)
                    p.send_reply(ch, True, items=items, next=nxt)
                else:
                    query, what = header.get("query", ""), header.get("kind", "all")
                    if not isinstance(query, str) or len(query) > 100 or what not in cloud.KIND_NAMES:
                        raise p.ProtocolError("bad_header")
                    items, nxt, partial = hub.search(parts, query.strip(), what, cursor, header, peer)
                    p.send_reply(ch, True, items=items, next=nxt, partial=partial)
                return
            if kind == "cloud_get":
                self._serve_cloud_get(ch, hub, parts, header, peer)
                return
            if kind == "cloud_thumb":
                px = header.get("px", 256)
                if not _uint(px) or not 64 <= px <= 512:
                    raise p.ProtocolError("bad_header")
                data, w, h = hub.thumb(parts, px, header, peer)
                ch.send(json.dumps({"ok": True, "mime": "image/jpeg", "w": w, "h": h}).encode("utf-8"), last=False)
                ch.send(data, last=True)
                return
            if kind == "cloud_rename":
                renamed, failed = hub.rename(_rename_request(header), header, peer)
                p.send_reply(ch, True, renamed=renamed, failed=failed)
                if renamed:
                    self.on_event("cloud_changed", {"peer": pairing.name, "path": ""})
                return
            if kind == "cloud_move":
                sources, dest = _move_request(header)
                moved, failed = hub.move(sources, dest, header, peer)
                p.send_reply(ch, True, moved=moved, failed=failed)
                if moved:
                    self.on_event("cloud_changed", {"peer": pairing.name, "path": cloud.join_path(dest)})
                return
            if kind == "cloud_open":
                token = hub.open(parts, header, peer)
                p.send_reply(ch, True, token=token, ttl=cs.TOKEN_TTL)
                return
            if kind == "cloud_lock":
                p.send_reply(ch, True, recovery=hub.lock(parts, header.get("pin")))
            elif kind == "cloud_unlock":
                hub.unlock(parts, header.get("pin"), peer)
                p.send_reply(ch, True)
            elif kind == "cloud_repin":
                hub.repin(parts, header, peer)
                p.send_reply(ch, True)
                changed = False
            elif kind == "cloud_mkdir":
                hub.mkdir(parts, header, peer)
                p.send_reply(ch, True)
            else:
                hub.delete(parts, header, peer)
                p.send_reply(ch, True)
            if changed:
                self.on_event("cloud_changed", {"peer": pairing.name, "path": cloud.join_path(parts)})
        except p.ProtocolError as exc:
            self._refuse(conn, ch, exc)
        except OSError:
            self._refuse(conn, ch, p.ProtocolError("cloud_denied"))

    def _serve_store(self, conn: socket.socket, ch: p.Channel, header: dict, pairing: store.Pairing) -> None:
        """§18.3 store_unlock·store_lock: 강한 잠금인 저장소를 다른 기기에서 열거나 잠근다."""
        st = self.store()
        try:
            if st is None:
                raise p.ProtocolError("cloud_off")
            if header["type"] == "store_unlock":
                st.unlock_remote(header.get("password"), pairing.stable_id)
                self._store_used = time.monotonic()
                self.on_event("store_changed", {"peer": pairing.name, "state": st.state})
            elif not st.lock_now():
                raise p.ProtocolError("store_state")
            else:
                self.on_event("store_changed", {"peer": pairing.name, "state": st.state})
            p.send_reply(ch, True)
        except p.ProtocolError as exc:
            self._refuse(conn, ch, exc)

    def _serve_update(self, conn: socket.socket, ch: p.Channel, header: dict) -> None:
        """§13 update_get: 머리 {"ok","version","size","sha256"}(last=0) 뒤에 파일 전체(마지막 데이터 프레임 last=1)."""
        pkg = self.shelf.packages().get(header.get("target")) if isinstance(header.get("target"), str) else None
        if pkg is None:
            self._refuse(conn, ch, p.ProtocolError("not_found"))
            return
        try:
            src = open(pkg.path, "rb")
        except OSError:
            self._refuse(conn, ch, p.ProtocolError("not_found"))
            return
        with src:
            ch.send(json.dumps({"ok": True, "version": pkg.version, "size": pkg.size, "sha256": pkg.sha256,
                                "code": pkg.code}).encode("utf-8"), last=False)
            try:
                Node._stream_file(ch, src, pkg.size)
            except OSError:
                return  # 머리를 보낸 뒤에는 끊기만 한다(§12.2와 같다)

    @staticmethod
    def _refuse(conn: socket.socket, ch: p.Channel, exc: p.ProtocolError) -> None:
        # vault_wait: 몇 초 뒤에 다시, vault_locked: 어느 잠긴 폴더인지(§14.2)
        extra = {k: exc.kw[k] for k in ("retry", "mount") if k in exc.kw}
        try:
            p.send_reply(ch, False, exc, **extra)
            _close_gently(conn)
        except OSError:
            pass

    @staticmethod
    def _serve_cloud_get(ch: p.Channel, hub: hubmod.Hub, parts: list[str], header: dict, peer: str) -> None:
        """§12.2 cloud_get: 먼저 {"ok","size","mtime"}(last=0), 이어서 offset부터 데이터(마지막 데이터 프레임 last=1).
        잠긴 폴더 안이면 허브가 복호화해서 보낸다(§14)."""
        if not parts:
            raise p.ProtocolError("bad_path")
        role = header.get("variant")
        if role is not None and role not in hubmod.VARIANTS:
            raise p.ProtocolError("bad_header")
        size, mtime, read = hub.open_file(parts, header, peer, role)
        # 바뀌었는지 먼저 본다: 파일이 줄어 offset이 넘친 경우도 cloud_changed(처음부터 다시)로 답한다.
        want_size, want_mtime = header.get("size"), header.get("mtime")
        if (want_size is not None and want_size != size) or (want_mtime is not None and want_mtime != mtime):
            raise p.ProtocolError("cloud_changed")
        offset, length = header.get("offset", 0), header.get("length")
        if not _uint(offset) or offset > size or (length is not None and not _uint(length)):
            raise p.ProtocolError("bad_header")
        left = size - offset if length is None else min(length, size - offset)
        blocks = read(offset, left)  # 열기 실패(사용 중 등)는 ok를 보내기 전에 거부로
        first = next(blocks, b"")
        ch.send(json.dumps({"ok": True, "size": size, "mtime": mtime}).encode("utf-8"), last=False)
        try:
            Node._stream_blocks(ch, first, blocks, left)
        except Exception:  # noqa: BLE001 - ok를 보낸 뒤에는 거부 응답을 보내지 않고 끊기만 한다(받는 쪽이 데이터로 읽는다)
            return

    @staticmethod
    def _stream_blocks(ch: p.Channel, first: bytes, blocks, left: int) -> None:
        """평문 조각들을 1 MiB 이하 프레임으로 보내고 마지막 데이터 프레임에 last를 붙인다(빈 평문 가능)."""
        pending = bytearray(first)
        sent = 0
        for block in blocks:
            pending += block
            while len(pending) > p.CHUNK:
                ch.send(bytes(pending[:p.CHUNK]), last=False)
                sent += p.CHUNK
                del pending[:p.CHUNK]
        if sent + len(pending) != left:
            return  # 보내는 사이 파일이 바뀌었다: last 없이 끊는다(상대는 다시 묻고 cloud_changed를 받는다)
        ch.send(bytes(pending), last=True)

    @staticmethod
    def _stream_file(ch: p.Channel, src, left: int) -> None:
        """left 바이트를 1 MiB씩 보내고 마지막 데이터 프레임에 last를 붙인다(빈 평문 가능)."""
        chunk = src.read(min(p.CHUNK, left))
        left -= len(chunk)
        while True:
            nxt = src.read(min(p.CHUNK, left)) if left > 0 else b""
            left -= len(nxt)
            if left > 0 and not nxt:
                return  # 보내는 사이 파일이 줄었다: last 없이 끊는다(상대는 다시 묻고 cloud_changed를 받는다)
            ch.send(chunk, last=not nxt)
            if not nxt:
                return
            chunk = nxt

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
            elif job.kind == "cloud_put":
                self._deliver_cloud_put(job, on_progress)
            elif job.kind == "cloud_get":
                self._deliver_cloud_get(job, on_progress)
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

    # ---------- 업데이트(§13, 상대가 배포 PC) ----------
    def peer_updates(self, stable_id: str) -> dict[str, dict]:
        """상대가 내주는 업데이트(hello의 updates). 예전 기기면 빈 dict."""
        return updates.parse_advert(self._hello(stable_id).get("updates"))

    def fetch_update(self, stable_id: str, target: str, dest: Path, on_progress=None) -> tuple[Path, str]:
        """업데이트 파일을 dest 폴더로 받는다. 크기·SHA-256이 머리와 다르면 지우고 hash_mismatch. (경로, 버전)."""
        sock, pairing = self._connect(stable_id)
        dest.mkdir(parents=True, exist_ok=True)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            ch.send(json.dumps(p.new_header("update_get", self.me.name, target=target)).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            raw, last = ch.recv()
            head = p.parse_reply(raw) if last else p.parse_reply(raw, last_ok=False)
            if last:
                raise p.ProtocolError("bad_reply")
            version, size, sha = head.get("version"), head.get("size"), head.get("sha256")
            if not updates.parse_advert({target: {"version": version, "size": size}}) or not isinstance(sha, str):
                raise p.ProtocolError("bad_reply")
            suffix = ".exe" if target == "pc" else ".apk"
            path = dest / f"TailHop-{version}-{target}{suffix}"
            got = 0
            with open(path, "wb") as out:
                while True:
                    data, last = ch.recv()
                    got += len(data)
                    if got > size:
                        raise p.ProtocolError("file_too_big")
                    out.write(data)
                    if on_progress:
                        on_progress(got, size)
                    if last:
                        break
        if got != size or not updates.verify(path, size, sha):
            path.unlink(missing_ok=True)
            raise p.ProtocolError("hash_mismatch")
        return path, version

    # ---------- 클라우드 쓰기(§12, 상대가 허브) ----------
    def cloud_info(self, stable_id: str) -> bool:
        """상대가 클라우드 모드를 켰는지(예전 기기면 False)."""
        info = self._hello(stable_id)
        return "cloud" in info["caps"] and info.get("cloud") is True

    def token_for(self, stable_id: str, path: str) -> str | None:
        """path가 그 허브에서 연 잠긴 폴더 안이면 그 열쇠."""
        for (sid, mount), token in list(self._open_tokens.items()):
            if sid == stable_id and (path == mount or path.startswith(mount + "/")):
                return token
        return None

    def retarget_tokens(self, stable_id: str, moved: list[dict], dest: str) -> None:
        """옮긴 잠긴 폴더(또는 그 위 폴더)의 열쇠가 새 자리를 따라가게 한다."""
        for item in moved:
            old = item["path"]
            new = f"{dest}/{item['name']}" if dest else item["name"]
            for (sid, mount), token in list(self._open_tokens.items()):
                if sid == stable_id and (mount == old or mount.startswith(old + "/")):
                    del self._open_tokens[(sid, mount)]
                    self._open_tokens[(sid, new + mount[len(old):])] = token

    def _with_token(self, stable_id: str, path: str, fields: dict) -> dict:
        token = self.token_for(stable_id, path)
        return {**fields, "token": token} if token else fields

    def cloud_open(self, stable_id: str, path: str, pin: str | None = None, recovery: str | None = None) -> None:
        """허브의 잠긴 폴더를 PIN(또는 복구 코드)으로 연다. 열쇠는 앱이 켜져 있는 동안 기억한다."""
        fields = {"path": path, "pin": pin} if pin is not None else {"path": path, "recovery": recovery}
        try:
            reply = self._cloud_request(stable_id, "cloud_open", **fields)
        except p.ProtocolError:
            self._open_tokens.pop((stable_id, path), None)
            raise
        token = reply.get("token")
        if not isinstance(token, str) or len(token) != 32:
            raise p.ProtocolError("bad_reply")
        self._open_tokens[(stable_id, path)] = token

    def cloud_lock(self, stable_id: str, path: str, pin: str) -> str:
        """허브의 폴더를 잠근다. 복구 코드를 돌려준다(한 번만 보여 준다)."""
        code = self._cloud_request(stable_id, "cloud_lock", path=path, pin=pin).get("recovery")
        if not isinstance(code, str):
            raise p.ProtocolError("bad_reply")
        return code

    def cloud_unlock(self, stable_id: str, path: str, pin: str) -> None:
        self._cloud_request(stable_id, "cloud_unlock", path=path, pin=pin)
        self._open_tokens.pop((stable_id, path), None)

    def cloud_repin(self, stable_id: str, path: str, new_pin: str, pin: str | None = None,
                    recovery: str | None = None) -> None:
        fields = {"path": path, "new_pin": new_pin}
        fields.update({"pin": pin} if pin is not None else {"recovery": recovery})
        self._cloud_request(stable_id, "cloud_repin", **fields)

    def cloud_search(self, stable_id: str, path: str, query: str, kind: str = "all",
                     cursor: int = 0) -> tuple[list[dict], int | None, bool]:
        reply = self._cloud_request(stable_id, "cloud_search",
                                    **self._with_token(stable_id, path, {"path": path, "query": query, "kind": kind,
                                                                         "cursor": cursor}))
        items = [x for x in reply.get("items") or [] if _valid_item(x) and _valid_rel(x.get("path"))]
        nxt = reply.get("next")
        return items, nxt if _uint(nxt) and nxt > cursor else None, reply.get("partial") is True

    def cloud_thumb(self, stable_id: str, path: str, px: int = 256) -> bytes:
        """허브가 만든 JPEG 미리보기. 만들 수 없으면 not_found."""
        sock, pairing = self._connect(stable_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            fields = self._with_token(stable_id, path, {"path": path, "px": px})
            ch.send(json.dumps(p.new_header("cloud_thumb", self.me.name, **fields)).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            raw, last = ch.recv()
            if last:
                p.parse_reply(raw)
                raise p.ProtocolError("bad_reply")
            p.parse_reply(raw, last_ok=False)
            data, last = ch.recv()
            if not last or len(data) > hubmod.THUMB_MAX or not bytes(data[:3]) == b"\xff\xd8\xff":
                raise p.ProtocolError("bad_reply")
            return bytes(data)

    def _cloud_request(self, stable_id: str, kind: str, /, **fields) -> dict:
        sock, pairing = self._connect(stable_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            ch.send(json.dumps(p.new_header(kind, self.me.name, **fields)).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            return p.read_reply(ch)

    def cloud_list(self, stable_id: str, path: str = "", cursor: int = 0) -> tuple[list[cloud.Item], int | None]:
        """허브의 폴더 목록 한 쪽. 상대가 보낸 항목도 이름·형식을 다시 검사한다."""
        reply = self._cloud_request(stable_id, "cloud_list",
                                    **self._with_token(stable_id, path, {"path": path, "cursor": cursor}))
        items = []
        for raw in reply.get("items") if isinstance(reply.get("items"), list) else []:
            if not _valid_item(raw):
                continue
            state, progress = raw.get("state"), raw.get("progress")
            roles = raw.get("variants")
            roles = tuple(x for x in roles if x in hubmod.VARIANTS) if isinstance(roles, list) else ()
            items.append(cloud.Item(raw["name"], raw["dir"], raw["size"], raw["mtime"], raw.get("locked") is True,
                                    state if isinstance(state, str) else "", progress if _uint(progress) else 0,
                                    roles))
        nxt = reply.get("next")
        return items, nxt if _uint(nxt) and nxt > cursor else None

    def cloud_read(self, stable_id: str, path: str, limit: int, offset: int = 0, length: int | None = None,
                   variant: str | None = None) -> bytes:
        """허브의 파일(또는 딸린 버전)을 메모리로 받는다(라이트박스·스트리밍). length를 주면 그 부분만(§17.3).
        받을 크기가 limit보다 크면 받지 않고 too_large."""
        sock, pairing = self._connect(stable_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            want = {"path": path, "offset": offset}
            if length is not None:
                want["length"] = length
            if variant is not None:
                want["variant"] = variant
            fields = self._with_token(stable_id, path, want)
            ch.send(json.dumps(p.new_header("cloud_get", self.me.name, **fields)).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            raw, last = ch.recv()
            if last:
                p.parse_reply(raw)
                raise p.ProtocolError("bad_reply")
            head = p.parse_reply(raw, last_ok=False)
            size = head.get("size")
            if not _uint(size) or size < offset:
                raise p.ProtocolError("bad_reply")
            size = size - offset if length is None else min(length, size - offset)  # 이번에 올 크기
            if size > limit:
                raise p.ProtocolError("too_large")  # 연결을 닫으면 허브는 보내기를 멈춘다
            out = bytearray()
            empty = 0
            while True:
                data, last = ch.recv()
                empty = empty + 1 if not data and not last else 0
                if empty > 16:  # 진척 없이 빈 프레임만 온다
                    raise p.ProtocolError("bad_reply")
                out += data
                if len(out) > size:
                    raise p.ProtocolError("bad_reply")
                if last:
                    break
            if len(out) != size:
                raise p.ProtocolError("file_incomplete")
            return bytes(out)

    def cloud_size(self, stable_id: str, path: str, variant: str | None = None) -> int:
        """허브의 파일(또는 딸린 버전) 크기만 묻는다(cloud_get length=0)."""
        sock, pairing = self._connect(stable_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            want = {"path": path, "offset": 0, "length": 0}
            if variant is not None:
                want["variant"] = variant
            ch.send(json.dumps(p.new_header("cloud_get", self.me.name, **self._with_token(stable_id, path, want)))
                    .encode("utf-8"), last=False)
            ch.send(b"", last=True)
            raw, last = ch.recv()
            if last:
                p.parse_reply(raw)
                raise p.ProtocolError("bad_reply")
            size = p.parse_reply(raw, last_ok=False).get("size")
            if not _uint(size):
                raise p.ProtocolError("bad_reply")
            return size

    def cloud_rename(self, stable_id: str, items: list[tuple[str, str]]) -> tuple[list[dict], list[dict]]:
        """§18.2: [(경로, 새 이름)]을 200개씩. 잠긴 폴더 안이면 그 열쇠들을 tokens로."""
        renamed, failed = [], []
        for start in range(0, len(items), MOVE_BATCH):
            batch = items[start:start + MOVE_BATCH]
            tokens = sorted({self.token_for(stable_id, path) for path, _ in batch} - {None})
            fields = {"items": [{"path": path, "name": name} for path, name in batch]}
            if tokens:
                fields["token"], fields["tokens"] = tokens[0], tokens[:16]
            reply = self._cloud_request(stable_id, "cloud_rename", **fields)
            asked = {path for path, _ in batch}
            renamed += [x for x in reply.get("renamed") or [] if isinstance(x, dict) and x.get("path") in asked
                        and isinstance(x.get("name"), str)]
            failed += [x for x in reply.get("failed") or [] if isinstance(x, dict) and x.get("path") in asked
                       and isinstance(x.get("code"), str)]
        for item in renamed:  # 이름을 바꾼 잠긴 폴더의 열쇠도 새 이름을 따라가게
            parent = item["path"].rpartition("/")[0]
            self.retarget_tokens(stable_id, [item], parent)
        return renamed, failed

    def store_unlock(self, stable_id: str, password: str) -> None:
        self._cloud_request(stable_id, "store_unlock", password=password)
        self._caps.pop(stable_id, None)

    def store_lock(self, stable_id: str) -> None:
        self._cloud_request(stable_id, "store_lock")
        self._caps.pop(stable_id, None)

    def cloud_move(self, stable_id: str, paths: list[str], dest: str) -> tuple[list[dict], list[dict]]:
        """§16: 허브 안에서 여러 항목을 dest 폴더로 옮긴다. 200개씩 나눠 묻고 (옮긴 것, 안 된 것)을 합친다."""
        cloud.parse_path(dest)
        moved, failed = [], []
        for start in range(0, len(paths), MOVE_BATCH):
            batch = paths[start:start + MOVE_BATCH]
            fields = self._with_token(stable_id, dest, {"paths": batch, "dest": dest})
            tokens = {self.token_for(stable_id, x) for x in batch} - {None, fields.get("token")}
            if tokens:  # 출발이 잠긴 폴더 안이면 그 열쇠들도(§16, 잠긴 폴더 사이로 옮기기)
                fields["tokens"] = sorted(tokens)[:16]
            reply = self._cloud_request(stable_id, "cloud_move", **fields)
            moved += [x for x in reply.get("moved") or [] if isinstance(x, dict) and x.get("path") in batch
                      and isinstance(x.get("name"), str)]
            failed += [x for x in reply.get("failed") or [] if isinstance(x, dict) and x.get("path") in batch
                       and isinstance(x.get("code"), str)]
        self.retarget_tokens(stable_id, moved, dest)
        return moved, failed

    def cloud_mkdir(self, stable_id: str, path: str) -> None:
        cloud.parse_path(path)
        self._cloud_request(stable_id, "cloud_mkdir", **self._with_token(stable_id, path, {"path": path}))

    def cloud_delete(self, stable_id: str, path: str) -> None:
        if not cloud.parse_path(path):
            raise p.ProtocolError("bad_path")
        self._cloud_request(stable_id, "cloud_del", **self._with_token(stable_id, path, {"path": path}))
        self._open_tokens.pop((stable_id, path), None)

    def _deliver_cloud_put(self, job: outbox.Job, on_progress) -> None:
        path, _ = self._source(job)
        info = self._hello(job.peer_id, job.xfer, job.size)
        if "cloud" not in info["caps"] or info.get("cloud") is not True:
            raise p.ProtocolError("cloud_off")
        if info.get("done") is True:
            return
        have = info.get("have")
        offset = have if _uint(have) and have <= job.size else 0
        sock, pairing = self._connect(job.peer_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            p.send_file_xfer(ch, self.me.name, path, job.xfer, offset, on_progress, kind="cloud_put",
                             **self._with_token(job.peer_id, job.remote or "", {"dir": job.remote or ""}))
            reply = p.read_reply(ch)
        self.on_event("cloud_sent", {"peer_id": job.peer_id, "name": str(reply.get("name") or path.name),
                                     "dir": job.remote or ""})

    def _deliver_cloud_get(self, job: outbox.Job, on_progress) -> None:
        """허브의 파일을 받아 저장 폴더에 둔다. 끊기면 작업 폴더의 조각을 두었다가 size·mtime이 같을 때 이어 받는다."""
        name = p.sanitize_filename(job.name)
        work = self.outbox.work_dir / job.id
        work.mkdir(parents=True, exist_ok=True)
        part = work / (name + ".part")
        offset = part.stat().st_size if part.exists() and job.size and job.remote_mtime else 0
        if offset > job.size:
            offset = 0
        fields = self._with_token(job.peer_id, job.remote or "", {"path": job.remote or "", "offset": offset})
        if offset:
            fields.update(size=job.size, mtime=job.remote_mtime)
        sock, pairing = self._connect(job.peer_id)
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self._send_key(pairing))
            ch.send(json.dumps(p.new_header("cloud_get", self.me.name, **fields)).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            raw, last = ch.recv()
            empty = 0
            try:
                head = p.parse_reply(raw, last_ok=False) if not last else p.parse_reply(raw)
            except p.ProtocolError as exc:
                if exc.code == "cloud_changed":  # 이어 받는 사이 허브의 파일이 바뀌었다: 처음부터
                    part.unlink(missing_ok=True)
                    job.remote_mtime = 0
                raise
            if last:
                raise p.ProtocolError("bad_reply")
            size, mtime = head.get("size"), head.get("mtime")
            if not _uint(size) or not _uint(mtime) or size < offset or size > p.MAX_FILE:
                raise p.ProtocolError("bad_reply")
            job.size, job.remote_mtime = size, mtime
            self.save_dir.mkdir(parents=True, exist_ok=True)
            if p.free_bytes(self.save_dir) < size - offset + p.DISK_RESERVE:
                raise p.ProtocolError("disk_full")
            got = offset
            with open(part, "r+b" if offset else "wb") as out:
                out.truncate(offset)
                out.seek(offset)
                while True:
                    data, last = ch.recv()
                    empty = empty + 1 if not data and not last else 0
                    if empty > 16:  # 진척 없이 빈 프레임만 온다
                        raise p.ProtocolError("bad_reply")
                    got += len(data)
                    if got > size:
                        raise p.ProtocolError("file_too_big")
                    out.write(data)
                    if on_progress:
                        on_progress(got, size)
                    if last:
                        break
        if got != size:
            part.unlink(missing_ok=True)  # 끝(last)까지 왔는데 모자라다: 이 조각으로 이어 받지 않는다
            job.remote_mtime = 0
            raise p.ProtocolError("file_incomplete")
        self.save_dir.mkdir(parents=True, exist_ok=True)
        final = p.unique_path(self.save_dir, name)
        part.replace(final)
        item = self.history.add("in", "file", name=final.name, path=str(final), size=size, peer=pairing.name,
                                peer_id=job.peer_id, peer_kind=pairing.kind, cloud=job.remote)
        self.on_event("received", {**item, "from_cloud": True})

    # 한 번만 보내는 길(테스트·명령 없이 바로 보낼 때). 화면은 보낼 곳 목록(self.outbox)을 쓴다.
    def send_text(self, stable_id: str, text: str) -> None:
        self.deliver(outbox.Job(id="direct", peer_id=stable_id, kind="text", text=text, xfer=p.new_xfer()))

    def send_file(self, stable_id: str, path: Path, record_path: Path | None = None) -> None:
        """record_path: 기록에 남길 원본 경로(폴더를 임시 zip으로 묶어 보낼 때 원래 폴더)."""
        st = Path(path).stat()
        self.deliver(outbox.Job(id="direct", peer_id=stable_id, kind="file", path=str(path), xfer=p.new_xfer(),
                                record=str(record_path) if record_path else None, size=st.st_size,
                                mtime_ns=st.st_mtime_ns))


def _uint(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _valid_item(raw: object) -> bool:
    """허브가 보낸 목록 항목을 경계에서 검사한다(이름은 한 조각, 형식이 맞는 숫자)."""
    if not isinstance(raw, dict):
        return False
    name, is_dir = raw.get("name"), raw.get("dir")
    if not isinstance(name, str) or "/" in name or not isinstance(is_dir, bool):
        return False
    if not _uint(raw.get("size")) or not _uint(raw.get("mtime")):
        return False
    try:
        cloud.parse_path(name)
    except p.ProtocolError:
        return False
    return True


def _move_request(header: dict) -> tuple[list[list[str]], list[str]]:
    """§16 cloud_move 헤더 → (옮길 경로 조각들, 옮겨 갈 폴더 조각). 같은 경로는 한 번만. 틀리면 bad_path."""
    paths, dest = header.get("paths"), header.get("dest")
    if not isinstance(paths, list) or not 1 <= len(paths) <= MOVE_BATCH or not isinstance(dest, str):
        raise p.ProtocolError("bad_path")
    seen: dict[str, list[str]] = {}
    for raw in paths:
        parts = cloud.parse_path(raw)
        if not parts:
            raise p.ProtocolError("bad_path")
        seen.setdefault(cloud.join_path(parts), parts)
    return list(seen.values()), cloud.parse_path(dest)


def _session_locked() -> bool:
    """Windows 잠금 화면인지(입력 데스크톱을 열 수 없으면 잠겨 있다)."""
    try:
        user32 = ctypes.windll.user32
        desk = user32.OpenInputDesktop(0, False, 0x0100)  # DESKTOP_SWITCHDESKTOP
        if not desk:
            return True
        user32.CloseDesktop(desk)
    except (AttributeError, OSError):
        pass
    return False


def _rename_request(header: dict) -> list[tuple[list[str], str]]:
    """§18.2 cloud_rename 헤더 → [(경로 조각, 새 이름)]. 모양이 틀리면 요청 전체를 bad_path로."""
    items = header.get("items")
    if not isinstance(items, list) or not 1 <= len(items) <= MOVE_BATCH:
        raise p.ProtocolError("bad_path")
    out = []
    for raw in items:
        if not isinstance(raw, dict) or not isinstance(raw.get("name"), str) or len(raw["name"]) > 255:
            raise p.ProtocolError("bad_path")
        parts = cloud.parse_path(raw.get("path"))
        if not parts:
            raise p.ProtocolError("bad_path")
        out.append((parts, raw["name"]))
    return out


def _valid_rel(value: object) -> bool:
    try:
        return bool(cloud.parse_path(value))
    except p.ProtocolError:
        return False


def _pack_folder(folder: Path, out_dir: Path) -> Path:
    import bridge  # bridge는 Windows 셸 기능(단축키·바로 가기)을 함께 들고 있어 쓸 때만 불러온다
    return bridge.pack_folder(folder, out_dir)
