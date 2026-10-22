"""모든 기기 동기화의 기기 쪽 일꾼(2.3.0, 2.3.1, docs/PROTOCOL.md §19.3·§19.5).

- 연결된 허브 PC마다(hello caps "sync") 그 허브의 동기화 대상을 이 PC의 암호화 사본(mirror.Mirror)으로 받아 둔다.
  사본은 잠긴 채로도 받는다(공개키로 쓴다). 보려면 사본 비밀번호로 연다(unlock). 사본 비밀번호를 정하기 전에는 받지 않는다.
- 언제: 앱이 켜져 있는 동안 15분마다, 허브가 바뀌었다고 hello를 보낼 때(§19.2), 허브의 hello 응답에서 sync 값이
  바뀐 것을 볼 때, "지금 동기화"를 누를 때. 한 번에 한 허브, 한 파일씩.
- 허브의 저장소 번호(store_id)가 바뀌거나 페어링을 끊으면 사본을 지우지 않고 이전 사본으로 둔다. 이전 사본은 허브로 복원할 수 있다.
- 열린 사본은 Windows가 잠길 때, 정한 시간 동안 쓰지 않을 때 다시 잠근다(강한 잠금과 같은 시간).
"""
from __future__ import annotations

import hashlib
import threading
import time
from pathlib import Path

import mirror as mm
import protocol as p

EVERY_SEC = 15 * 60
MANIFEST_RESTARTS = 3
MAX_ITEMS = 200_000  # 목록 줄 수 상한(상대가 끝없이 보내도 메모리를 다 쓰지 않게)
COPY_IDLE_MIN = 30


class Syncer:
    def __init__(self, node, base: Path) -> None:
        self.node = node
        self.base = Path(base)
        self.device = mm.DeviceKey(self.base)
        self._ledger = None
        self._mu = threading.Lock()
        self._mirrors: dict[str, mm.Mirror] = {}
        self._archives: dict[str, mm.Mirror] = {}
        self._queue: set[str] = set()
        self._wake = threading.Event()
        self._busy = threading.Lock()
        self.status: dict[str, dict] = {}  # 허브 → {"state": ok|syncing|locked|offline|space|error|setup, ...}
        self.restoring: dict | None = None  # 복원 중: {"name","done","total"}
        self.used = time.monotonic()
        self._stopped = False

    @property
    def enabled(self) -> bool:
        return self.node.settings.get("sync_receive", True) is not False

    @property
    def ready(self) -> bool:
        """받을 수 있다: 받기가 켜져 있고 사본 비밀번호를 정했다."""
        return self.enabled and self.device.exists

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

    # ---------- 사본 비밀번호 ----------
    def setup(self, password: str) -> None:
        self.device.create(password)
        self.touch()
        self.poke()
        self.node.on_event("sync_changed", {"peer_id": ""})

    def unlock(self, password: str) -> bool:
        ok = self.device.unlock(password)
        if ok:
            self.touch()
            self.node.on_event("sync_changed", {"peer_id": ""})
        return ok

    def lock(self) -> None:
        if self.device.lock():
            self.node.on_event("sync_changed", {"peer_id": "", "locked": True})

    def touch(self) -> None:
        self.used = time.monotonic()

    def check_idle(self, session_locked: bool) -> None:
        """잠금 순환(node._lock_loop)이 5초마다 부른다: Windows 잠금·쓰지 않음이면 사본을 다시 잠근다."""
        if self.device.locked:
            return
        minutes = self.node.settings.get("store_idle_min", COPY_IDLE_MIN)
        idle = isinstance(minutes, int) and minutes > 0 and time.monotonic() - self.used > minutes * 60
        if session_locked or idle:
            self.lock()

    def forget_password(self) -> None:
        """사본 비밀번호를 잊었다: 열쇠와 모든 사본(이전 사본까지)을 지운다. 허브에서 다시 받는다."""
        with self._busy:
            for found in [*self._all_mirrors(), *self.archive_list()]:
                found.wipe()
            with self._mu:
                self._mirrors.clear()
                self._archives.clear()
            self.device.forget()
        self.status.clear()
        self.node.on_event("sync_changed", {"peer_id": ""})

    # ---------- 사본 ----------
    def _ledger_aead(self):
        if self._ledger is None:
            self._ledger = mm.ledger_aead(self.base)
        return self._ledger

    def mirror(self, sid: str, create: bool = False) -> mm.Mirror | None:
        """그 허브의 지금 사본(없으면 None, create면 만든다)."""
        with self._mu:
            found = self._mirrors.get(sid)
            if found is not None:
                return found
            folder = mm.mirror_dir(self.base, sid)
            if not create and not (folder / "index.db").exists():
                return None
            try:
                found = self._mirrors[sid] = mm.Mirror(folder, self.device, self._ledger_aead())
            except (OSError, p.ProtocolError):
                return None
            return found

    def _all_mirrors(self) -> list[mm.Mirror]:
        return [x for x in (self.mirror(sid) for sid in {*self._mirrors, *self.node.pairings}) if x is not None]

    def archive_list(self) -> list[mm.Mirror]:
        """이전 사본들(허브 초기화·페어링 해제로 남은 것)."""
        out = []
        for folder in mm.archives(self.base):
            key = folder.name
            with self._mu:
                found = self._archives.get(key)
                if found is None:
                    try:
                        found = self._archives[key] = mm.Mirror(folder, self.device, self._ledger_aead())
                    except (OSError, p.ProtocolError):
                        continue
            out.append(found)
        return out

    def _archive(self, sid: str) -> None:
        with self._mu:
            found = self._mirrors.pop(sid, None)
        if found is None:
            folder = mm.mirror_dir(self.base, sid)
            if not (folder / "index.db").exists():
                return
            found = mm.Mirror(folder, self.device, self._ledger_aead())
        if found.usage()[0] == 0 and not found.book:
            found.wipe()  # 빈 사본은 남길 것이 없다
            return
        found.archive()

    def forget(self, sid: str) -> None:
        """페어링을 끊었다: 그 허브의 사본을 지우지 않고 이전 사본으로 둔다(§19.5)."""
        with self._busy:
            self._archive(sid)
        self.status.pop(sid, None)
        self.node.on_event("sync_changed", {"peer_id": sid})

    def wipe(self, sid: str) -> None:
        """설정의 "사본 지우기": 그 허브의 지금 사본을 지운다."""
        with self._busy:
            with self._mu:
                found = self._mirrors.pop(sid, None)
            if found is None and (mm.mirror_dir(self.base, sid) / "index.db").exists():
                found = mm.Mirror(mm.mirror_dir(self.base, sid), self.device, self._ledger_aead())
            if found is not None:
                found.wipe()
        self.status.pop(sid, None)
        self.node.on_event("sync_changed", {"peer_id": sid})

    def wipe_archive(self, archive: mm.Mirror) -> None:
        with self._busy:
            with self._mu:
                self._archives.pop(archive.dir.name, None)
            archive.wipe()
        self.node.on_event("sync_changed", {"peer_id": ""})

    def wipe_all(self) -> None:
        """받기를 껐다: 지금 사본을 모두 지운다(이전 사본은 백업이라 둔다)."""
        for sid in {*self._mirrors, *self.node.pairings}:
            if self.mirror(sid) is not None:
                self.wipe(sid)

    def consider(self, sid: str, reply: dict) -> None:
        """hello 응답을 보았다: 허브의 sync 값이 사본과 다르면 맞추기를 예약한다(같은 스레드에서는 받지 않는다)."""
        if not self.ready or "sync" not in reply.get("caps", ()):
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
                try:
                    self.sync_one(sid)
                except Exception as exc:  # noqa: BLE001 - 한 허브의 실패로 일꾼이 멈추지 않게
                    self._set(sid, "error", code=getattr(exc, "code", "") or type(exc).__name__)

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
            store_id = reply.get("store_id") if isinstance(reply.get("store_id"), str) else ""
            found = self.mirror(sid)
            if found is not None and found.store_id and store_id and found.store_id != store_id:
                self._archive(sid)  # §19.5: 허브의 저장소가 바뀌었다(초기화 등). 지우지 않고 이전 사본으로
                found = None
                self.node.on_event("sync_changed", {"peer_id": sid})
            if reply.get("store") != "open":
                self._set(sid, "locked")
                return
            digest = reply.get("sync") if isinstance(reply.get("sync"), str) else ""
            if not digest:  # 같은 저장소인데 대상이 없다(허브에서 동기화를 껐다): 사본도 지운다
                if found is not None:
                    with self._mu:
                        self._mirrors.pop(sid, None)
                    found.wipe()
                    self.node.on_event("sync_changed", {"peer_id": sid})
                self.status.pop(sid, None)
                return
            if not self.device.exists:
                self._set(sid, "setup")
                return
            found = found or self.mirror(sid, create=True)
            if found is None:
                self._set(sid, "error", code="storage")
                return
            pairing = self.node.pairings.get(sid)
            if store_id and found.store_id != store_id or (pairing and found.state.get("hub_name") != pairing.name):
                found.set_info(store_id=store_id, hub_name=pairing.name if pairing else "",
                               hub_hash=hashlib.sha256(sid.encode()).hexdigest()[:16])
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
                    progress=lambda done, total: self._set(sid, "syncing", done=done, total=total),
                    fetch_raw=lambda oid, offset, length: self.node.sync_raw_read(sid, oid, offset, length))
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

    # ---------- 허브로 복원(§19.5) ----------
    def restore(self, archive: mm.Mirror, target: str, cancel=lambda: False) -> tuple[int, list[tuple[str, str]]]:
        """이전 사본의 파일을 허브의 원래 경로로 올린다(사본이 열려 있어야 한다). (올린 수, 안 된 것[(경로, 이유)])."""
        # 잠긴 폴더 안(§19.6)은 올리면 잠금이 풀린 채가 되므로 복원하지 않는다(허브에서 다시 잠가 넣어야 한다)
        files = sorted((x for x in archive.entries.values() if archive.complete(x) and not x.get("raw")),
                       key=lambda x: x["path"])
        done, failed, tops = 0, [], []
        total = sum(int(x["size"]) for x in files)
        sent = 0
        self.restoring = {"name": archive.state.get("hub_name", ""), "done": 0, "total": total}
        try:
            for info in files:
                if cancel() or self._stopped:
                    break
                path = info["path"]
                folder, _, name = path.rpartition("/")
                try:
                    self.node.cloud_put_stream(target, folder, name, int(info["size"]), int(info.get("mtime", 0)),
                                               lambda offset, length, i=info: archive.read(i, offset, length))
                    done += 1
                    top = path.partition("/")[0]
                    if top not in tops:
                        tops.append(top)
                except (OSError, p.ProtocolError) as exc:
                    failed.append((path, str(exc)))
                sent += int(info["size"])
                self.restoring = {**self.restoring, "done": sent}
                self.node.on_event("sync_changed", {"peer_id": ""})
            if tops:
                try:
                    self.node.sync_set(target, tops, True)  # 올린 것을 다시 모든 기기에 동기화
                except (OSError, p.ProtocolError) as exc:
                    failed.append(("", str(exc)))
        finally:
            self.restoring = None
            self.node.on_event("sync_changed", {"peer_id": ""})
        return done, failed

