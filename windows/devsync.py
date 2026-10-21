"""모든 기기 동기화의 기기 쪽 일꾼(2.3.0, docs/PROTOCOL.md §19.3).

- 연결된 허브 PC마다(hello caps "sync") 그 허브의 동기화 대상을 이 PC의 암호화 사본(mirror.Mirror)으로 받아 둔다.
- 언제: 앱이 켜져 있는 동안 15분마다, 허브가 바뀌었다고 hello를 보낼 때(§19.2), 허브의 hello 응답에서 sync 값이
  바뀐 것을 볼 때, "지금 동기화"를 누를 때. 한 번에 한 허브, 한 파일씩.
- 끄면(sync_receive = False) 사본을 모두 지운다. 페어링을 끊으면 그 허브의 사본을 지운다.
"""
from __future__ import annotations

import threading
import time
from pathlib import Path

import mirror as mm
import protocol as p

EVERY_SEC = 15 * 60
MANIFEST_RESTARTS = 3
MAX_ITEMS = 200_000  # 목록 줄 수 상한(상대가 끝없이 보내도 메모리를 다 쓰지 않게)


class Syncer:
    def __init__(self, node, base: Path) -> None:
        self.node = node
        self.base = Path(base)
        self._mu = threading.Lock()
        self._mirrors: dict[str, mm.Mirror] = {}
        self._queue: set[str] = set()
        self._wake = threading.Event()
        self._busy = threading.Lock()
        self.status: dict[str, dict] = {}  # 허브 → {"state": ok|syncing|locked|offline|space|error, ...}
        self._stopped = False

    @property
    def enabled(self) -> bool:
        return self.node.settings.get("sync_receive", True) is not False

    def start(self) -> None:
        threading.Thread(target=self._loop, daemon=True, name="tailhop-sync").start()

    def stop(self) -> None:
        self._stopped = True
        self._wake.set()

    def poke(self, sid: str | None = None) -> None:
        """그 허브(없으면 모두)를 곧 맞춘다."""
        with self._mu:
            self._queue.update([sid] if sid else list(self.node.pairings))
        self._wake.set()

    # ---------- 사본 ----------
    def mirror(self, sid: str, create: bool = False) -> mm.Mirror | None:
        """그 허브의 사본(없으면 None, create면 만든다)."""
        with self._mu:
            found = self._mirrors.get(sid)
            if found is not None:
                return found
            if not create and not mm.mirror_dir(self.base, sid).exists():
                return None
            try:
                found = self._mirrors[sid] = mm.Mirror(self.base, sid)
            except (OSError, p.ProtocolError):
                return None
            return found

    def forget(self, sid: str) -> None:
        """페어링을 끊었다: 그 허브의 사본을 지운다."""
        with self._mu:
            found = self._mirrors.pop(sid, None)
        if found is None and mm.mirror_dir(self.base, sid).exists():
            found = mm.Mirror(self.base, sid)
        if found is not None:
            with self._busy:
                found.wipe()
        self.status.pop(sid, None)
        self.node.on_event("sync_changed", {"peer_id": sid})

    def wipe_all(self) -> None:
        for sid in {*self._mirrors, *self.node.pairings}:
            if self.mirror(sid) is not None:
                self.forget(sid)

    def consider(self, sid: str, reply: dict) -> None:
        """hello 응답을 보았다: 허브의 sync 값이 사본과 다르면 맞추기를 예약한다(같은 스레드에서는 받지 않는다)."""
        if not self.enabled or "sync" not in reply.get("caps", ()):
            return
        found = self.mirror(sid)
        digest = reply.get("sync") if isinstance(reply.get("sync"), str) else ""
        if reply.get("store") == "open" and (found.digest if found else "") != digest:
            self.poke(sid)

    # ---------- 일 ----------
    def _loop(self) -> None:
        next_all = time.monotonic() + 20  # 켠 직후에는 알리기(announce)가 먼저
        while not self._stopped and not self.node._stop:
            self._wake.wait(max(1.0, next_all - time.monotonic()))
            self._wake.clear()
            if self._stopped:
                return
            with self._mu:
                todo, self._queue = self._queue, set()
            if time.monotonic() >= next_all:
                todo |= set(self.node.pairings)
                next_all = time.monotonic() + EVERY_SEC
            for sid in sorted(todo):
                if self._stopped or not self.enabled:
                    break
                self.sync_one(sid)

    def sync_one(self, sid: str) -> None:
        """그 허브와 한 번 맞춘다. 실패는 상태로 남기고 다음 차례에 다시."""
        if sid not in self.node.pairings:
            return
        with self._busy:
            if not self.enabled or self._stopped:  # 기다리는 사이 받기를 껐다(지운 사본을 다시 만들지 않게)
                return
            try:
                reply = self.node._hello(sid)
            except Exception:  # noqa: BLE001 - 꺼져 있거나 닿지 않는다: 사본은 그대로
                self._set(sid, "offline")
                return
            if "sync" not in reply.get("caps", ()):
                return
            if reply.get("store") != "open":
                self._set(sid, "locked")
                return
            digest = reply.get("sync") if isinstance(reply.get("sync"), str) else ""
            if not digest:  # 허브에 대상이 없다: 사본도 지운다
                found = self.mirror(sid)
                if found is not None:
                    with self._mu:
                        self._mirrors.pop(sid, None)
                    found.wipe()
                    self.node.on_event("sync_changed", {"peer_id": sid})
                self.status.pop(sid, None)
                return
            found = self.mirror(sid, create=True)
            if found is None:
                self._set(sid, "error", code="storage")
                return
            if found.digest == digest:
                self._set(sid, "ok", at=found.synced_at)
                return
            self._set(sid, "syncing", done=0, total=0)
            try:
                got_digest, wants = self._manifest(sid)
                count = found.apply(
                    got_digest, wants,
                    fetch=lambda path, offset, length: self.node.cloud_read(sid, path, length, offset=offset,
                                                                            length=length),
                    fetch_thumb=lambda path: self.node.cloud_read(sid, path, mm.THUMB_LIMIT, variant="thumb"),
                    stop=lambda: self._stopped or self.node._stop or not self.enabled,
                    progress=lambda done, total: self._set(sid, "syncing", done=done, total=total))
            except mm.SpaceError:
                self._set(sid, "space")
            except p.ProtocolError as exc:
                self._set(sid, "error", code=exc.code)
            except OSError:
                self._set(sid, "offline")
            else:
                self._set(sid, "ok", at=found.synced_at, got=count)
            self.node.on_event("sync_changed", {"peer_id": sid})

    def _manifest(self, sid: str) -> tuple[str, list[mm.Want]]:
        """§19.2 목록을 끝까지. 쪽 사이에 요약값이 바뀌면 처음부터(몇 번까지)."""
        for _ in range(MANIFEST_RESTARTS):
            digest, wants, cursor = None, [], 0
            while True:
                reply = self.node.sync_manifest(sid, cursor)
                page_digest = reply.get("digest")
                if not isinstance(page_digest, str) or not mm.DIGEST_RE.fullmatch(page_digest):
                    raise p.ProtocolError("bad_reply")
                if digest is None:
                    digest = page_digest
                elif page_digest != digest:
                    break  # 받는 사이 허브가 바뀌었다
                raw = reply.get("items")
                for item in raw if isinstance(raw, list) else []:
                    want = mm.parse_manifest_item(item)
                    if want is not None:
                        wants.append(want)
                if len(wants) > MAX_ITEMS:
                    raise p.ProtocolError("bad_reply")
                nxt = reply.get("next")
                if nxt is None:
                    return digest, wants
                if not isinstance(nxt, int) or isinstance(nxt, bool) or nxt <= cursor:
                    raise p.ProtocolError("bad_reply")
                cursor = nxt
        raise p.ProtocolError("sync_busy")

    def _set(self, sid: str, state: str, **extra) -> None:
        before = self.status.get(sid, {}).get("state")
        self.status[sid] = {"state": state, **extra}
        if before != state:
            self.node.on_event("sync_changed", {"peer_id": sid})
