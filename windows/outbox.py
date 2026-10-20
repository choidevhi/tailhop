"""보낼 곳 목록(outbox, docs/PROTOCOL.md §11.3): 보내기를 디스크에 적어 두고 기기마다 차례로, 실패하면 다시 보낸다.

- 기기마다 줄 두 개(대화방, 클라우드)로 차례로 보낸다(같은 줄은 넣은 순서대로). 큰 클라우드 받기가 대화방 글을 막지 않는다.
  서로 다른 기기·줄은 동시에 보낸다.
- 연결 실패·끊김은 3초, 10초, 30초, 1분, 2분, 그 뒤 5분마다 다시 시도하고 24시간이 지나면 실패로 둔다.
  상대가 이유와 함께 거부하거나 파일이 사라지면 바로 실패로 두고 사용자가 다시 보낼 수 있게 한다.
- 앱을 껐다 켜도 이어서 보낸다(outbox.json, 이 사용자만 읽는 ACL). 폴더는 처음 보낼 때 zip으로 묶어 두고
  다 보낼 때까지 같은 zip을 쓴다(이어 보내기가 같은 바이트를 보내게).
- 실제 보내기는 deliver(job, progress)가 한다(Node.deliver). 여기서는 순서·재시도·저장만 맡는다.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import threading
import time
from dataclasses import asdict, dataclass, fields
from pathlib import Path
from typing import Callable

import i18n
import protocol as p
import store

WAITING, SENDING, FAILED = "waiting", "sending", "failed"
BACKOFF = (3, 10, 30, 60, 120)
BACKOFF_LATER = 300
GIVE_UP_SEC = 24 * 3600
PROGRESS_EVERY = 0.1  # 진행 알림 간격(초). 빠른 망에서 화면 큐가 넘치지 않게
# 다시 시도해 볼 만한 상대 응답·연결 실패 코드(§11.3). 나머지 코드는 상대가 이유를 대고 거부한 것이다.
RETRY_CODES = {"disconnected", "resume_lost", "hash_mismatch", "tailscale_down", "connect_failed",
               "peer_offline", "peer_unreachable", "cloud_changed"}


@dataclass
class Job:
    id: str
    peer_id: str
    kind: str  # text | file | folder
    text: str | None = None
    path: str | None = None  # 보낼 파일·폴더
    owned: bool = False  # path가 이 앱이 만든 임시 파일(클립보드 이미지)이면 끝난 뒤 지운다
    packed: str | None = None  # 폴더를 묶은 zip
    record: str | None = None  # 기록에 남길 경로(없으면 path)
    remote: str | None = None  # 클라우드: 올릴 폴더(cloud_put) 또는 가져올 파일(cloud_get)의 경로(§12.1)
    remote_mtime: int = 0  # 클라우드에서 가져올 때 허브 파일의 수정 시각(ms, 이어받기 확인용)
    xfer: str = ""
    size: int = 0
    mtime_ns: int = 0
    created: float = 0.0
    attempts: int = 0
    next_at: float = 0.0
    state: str = WAITING
    code: str = ""
    error: str = ""
    done: int = 0
    total: int = 0

    @property
    def name(self) -> str:
        if self.kind == "cloud_get":
            return (self.remote or "").rsplit("/", 1)[-1]
        return Path(self.path).name if self.path else ""

    def copy(self) -> "Job":
        return Job(**asdict(self))


_FIELDS = {f.name for f in fields(Job)}
_JOB_ID = re.compile(r"[0-9a-f]{16}")
_KINDS = ("text", "file", "folder", "cloud_put", "cloud_get")
_STATES = (WAITING, SENDING, FAILED)


def _parse_job(item: object) -> Job | None:
    """outbox.json의 한 항목을 경계에서 검사해 Job으로. 맞지 않으면 None."""
    if not isinstance(item, dict):
        return None
    types = {f.name: f.type for f in fields(Job)}
    clean = {}
    for key, value in item.items():
        if key not in _FIELDS:
            continue
        want = types[key]
        if "int" in want and "float" not in want and (not isinstance(value, int) or isinstance(value, bool)):
            return None
        if want == "float" and (isinstance(value, bool) or not isinstance(value, (int, float))):
            return None
        if want == "bool" and not isinstance(value, bool):
            return None
        if want.startswith("str") and not (isinstance(value, str) or (value is None and "None" in want)):
            return None
        clean[key] = value
    try:
        job = Job(**clean)
    except TypeError:
        return None
    if not _JOB_ID.fullmatch(job.id) or job.kind not in _KINDS or job.state not in _STATES \
            or not p._valid_xfer(job.xfer) or job.size < 0:
        return None
    return job


def _lane(job: Job) -> tuple[str, str]:
    return job.peer_id, "cloud" if job.kind.startswith("cloud_") else "chat"


def backoff(attempts: int) -> int:
    """attempts번 실패한 뒤 기다릴 초."""
    return BACKOFF[attempts - 1] if 1 <= attempts <= len(BACKOFF) else BACKOFF_LATER


def is_retryable(exc: BaseException) -> bool:
    """다시 시도할 실패인지. 연결·소켓 오류와 RETRY_CODES. 로컬 파일 문제(LocalFileError)와 상대의 거부는 아니다."""
    if isinstance(exc, LocalFileError):
        return False
    if isinstance(exc, p.FileChanged):
        return True  # 다음 시도에서 바뀐 파일을 새 xfer로 처음부터 보낸다
    if isinstance(exc, p.ProtocolError):
        return exc.code in RETRY_CODES
    return isinstance(exc, (OSError, RuntimeError))


def describe(exc: BaseException) -> tuple[str, str]:
    """(고정 코드, 화면 문장). 소켓 오류는 Windows 오류 문장 대신 알아듣기 쉬운 말로 바꾼다."""
    if isinstance(exc, ConnectionRefusedError):
        code = "peer_offline"
    elif isinstance(exc, TimeoutError):
        code = "peer_unreachable"
    elif isinstance(exc, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)):
        code = "disconnected"
    else:
        code = getattr(exc, "code", "") or type(exc).__name__
        return code, str(exc) or code
    return code, i18n.t("err." + code)


class LocalFileError(Exception):
    """보낼 파일·폴더가 없거나 읽을 수 없다(다시 시도해도 소용없음)."""

    def __init__(self, message: str) -> None:
        super().__init__(message)


class JobCancelled(Exception):
    """보내는 중에 사용자가 취소했다."""


class Outbox:
    def __init__(self, deliver: Callable[[Job, Callable[[int, int], None]], None], app_dir: Path,
                 on_event: Callable[[str, dict], None], clock: Callable[[], float] = time.time) -> None:
        self.deliver = deliver
        self.on_event = on_event
        self.clock = clock
        self.path = Path(app_dir) / "outbox.json"
        self.work_dir = Path(app_dir) / "outbox"
        self.jobs: list[Job] = []
        self.busy: set[tuple[str, str]] = set()  # 지금 보내는 중인 (기기, 줄)
        self.lock = threading.Lock()
        self.changed = threading.Condition(self.lock)
        self.wake_event = threading.Event()
        self._stop = False
        self._load()

    # ---------- 저장 ----------
    def _load(self) -> None:
        try:
            raw = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            raw = []
        for item in raw if isinstance(raw, list) else []:
            job = _parse_job(item)
            if job is None:
                continue  # 깨졌거나 형식이 다른 항목은 버린다(한 항목 때문에 보내기 전체가 멈추지 않게)
            if job.state == SENDING:  # 보내다가 앱이 꺼졌다
                job.state = WAITING
                job.next_at = 0.0
            self.jobs.append(job)
        self._clean_work_dir()

    def _save(self) -> None:
        """self.lock을 잡은 채로 부른다."""
        data = [asdict(j) for j in self.jobs]
        try:
            store._write_private(self.path, json.dumps(data))
        except OSError:
            pass  # 저장 실패는 다음 변경 때 다시

    def _clean_work_dir(self) -> None:
        """목록에 없는 작업의 임시 폴더(묶은 zip·클립보드 이미지)를 지운다."""
        if not self.work_dir.is_dir():
            return
        alive = {j.id for j in self.jobs}
        for child in self.work_dir.iterdir():
            if child.name not in alive:
                shutil.rmtree(child, ignore_errors=True)

    # ---------- 넣기 ----------
    def _new(self, peer_id: str, kind: str, **kw) -> Job:
        now = self.clock()
        return Job(id=os.urandom(8).hex(), peer_id=peer_id, kind=kind, xfer=p.new_xfer(), created=now,
                   next_at=now, **kw)

    def _add(self, jobs: list[Job]) -> list[Job]:
        with self.lock:
            self.jobs.extend(jobs)
            self._save()
            self.changed.notify_all()
        self.wake_event.set()
        for job in jobs:
            self.on_event("outbox", {"peer": job.peer_id, "id": job.id})
        return [j.copy() for j in jobs]

    def add_text(self, peer_id: str, text: str) -> Job:
        return self._add([self._new(peer_id, "text", text=text, size=len(text.encode("utf-8")))])[0]

    def add_paths(self, peer_id: str, paths: list[Path]) -> list[Job]:
        jobs = []
        for path in paths:
            path = Path(path)
            if path.is_dir():
                jobs.append(self._new(peer_id, "folder", path=str(path)))
            elif path.is_file():
                st = path.stat()
                jobs.append(self._new(peer_id, "file", path=str(path), size=st.st_size, mtime_ns=st.st_mtime_ns))
        return self._add(jobs) if jobs else []

    def add_cloud_upload(self, peer_id: str, paths: list[Path], remote_dir: str) -> list[Job]:
        """클라우드에 올리기. 폴더는 안의 파일을 같은 모양의 폴더로 하나씩 올린다(링크는 따라가지 않는다)."""
        jobs = []
        for path in paths:
            path = Path(path)
            if path.is_file():
                jobs.append(self._cloud_put_job(peer_id, path, remote_dir))
                continue
            if not path.is_dir():
                continue
            base = path.resolve()
            for folder, dirs, files in os.walk(base, followlinks=False):
                here = Path(folder)
                dirs[:] = sorted(d for d in dirs if not (here / d).is_symlink() and not (here / d).is_junction())
                rel = here.relative_to(base).parts
                target = "/".join(x for x in (remote_dir, path.name, *rel) if x)
                for name in sorted(files):
                    if not (here / name).is_symlink():
                        jobs.append(self._cloud_put_job(peer_id, here / name, target))
        return self._add(jobs) if jobs else []

    def _cloud_put_job(self, peer_id: str, path: Path, remote_dir: str) -> Job:
        st = path.stat()
        return self._new(peer_id, "cloud_put", path=str(path), remote=remote_dir, size=st.st_size,
                         mtime_ns=st.st_mtime_ns)

    def add_cloud_download(self, peer_id: str, remote_path: str, size: int, mtime: int) -> Job:
        """클라우드에서 가져오기(이어받기 되는 받기). 다 받으면 저장 폴더로 옮긴다."""
        return self._add([self._new(peer_id, "cloud_get", remote=remote_path, size=size, remote_mtime=mtime)])[0]

    def owned_file(self, name: str) -> tuple[str, Path]:
        """클립보드 이미지처럼 이 앱이 만들어 보낼 파일의 자리: (작업 id, 경로). 다 보내면 폴더째 지운다."""
        job_id = os.urandom(8).hex()
        folder = self.work_dir / job_id
        folder.mkdir(parents=True, exist_ok=True)
        return job_id, folder / name

    def add_owned(self, peer_id: str, job_id: str, path: Path) -> Job:
        st = path.stat()
        job = self._new(peer_id, "file", path=str(path), owned=True, size=st.st_size, mtime_ns=st.st_mtime_ns)
        job.id = job_id
        return self._add([job])[0]

    # ---------- 조작 ----------
    def jobs_for(self, peer_id: str) -> list[Job]:
        with self.lock:
            return [j.copy() for j in self.jobs if j.peer_id == peer_id]

    def get(self, job_id: str) -> Job | None:
        with self.lock:
            job = next((j for j in self.jobs if j.id == job_id), None)
            return job.copy() if job else None

    def retry(self, job_id: str) -> None:
        """실패한 보내기를 다시(처음 넣은 것처럼 24시간을 새로 센다)."""
        with self.lock:
            job = next((j for j in self.jobs if j.id == job_id), None)
            if job is None or job.state == SENDING:
                return
            job.state, job.attempts, job.next_at, job.created = WAITING, 0, 0.0, self.clock()
            job.code = job.error = ""
            self._save()
            self.changed.notify_all()
        self.wake_event.set()
        self.on_event("outbox", {"peer": job.peer_id, "id": job_id})

    def cancel(self, job_id: str) -> None:
        """대기·실패 중인 보내기를 지운다. 보내는 중이면 이번 시도가 끝난 뒤 지운다."""
        with self.lock:
            job = next((j for j in self.jobs if j.id == job_id), None)
            if job is None:
                return
            if job.state == SENDING:
                job.code = "cancelled"
                return
            self._cleanup(job)  # 목록에서 빠지기 전에 지운다(빠진 것을 본 쪽이 임시 파일을 보지 않게)
            self.jobs.remove(job)
            self._save()
            self.changed.notify_all()
        self.on_event("outbox", {"peer": job.peer_id, "id": job_id, "gone": True})

    def wake(self, peer_id: str) -> None:
        """상대에게서 메시지가 왔다(상대가 켜졌다): 그 기기의 대기를 바로 깨운다."""
        now = self.clock()
        with self.lock:
            woke = False
            for job in self.jobs:
                if job.peer_id == peer_id and job.state == WAITING and job.next_at > now:
                    job.next_at = now
                    woke = True
        if woke:
            self.wake_event.set()

    def drop_peer(self, peer_id: str) -> None:
        """연결 해제: 그 기기로 갈 보내기를 모두 지운다."""
        with self.lock:
            gone = [j for j in self.jobs if j.peer_id == peer_id and j.state != SENDING]
            for job in self.jobs:
                if job.peer_id == peer_id and job.state == SENDING:
                    job.code = "cancelled"  # 보내는 중인 것은 이번 시도가 끝나면 지운다(다시 시도하지 않게)
            for job in gone:
                self._cleanup(job)
            self.jobs = [j for j in self.jobs if j not in gone]
            self._save()
            self.changed.notify_all()

    def wait(self, job_ids: list[str], timeout: float) -> dict[str, Job | None]:
        """job_ids가 모두 끝나거나 timeout까지 기다린다. 보낸 것은 목록에서 빠져 None, 실패는 FAILED 상태로 돌려준다."""
        end = time.monotonic() + timeout
        with self.lock:
            while True:
                left = {i: next((j for j in self.jobs if j.id == i), None) for i in job_ids}
                pending = [j for j in left.values() if j is not None and j.state != FAILED]
                remaining = end - time.monotonic()
                if not pending or remaining <= 0:
                    return {i: (j.copy() if j else None) for i, j in left.items()}
                self.changed.wait(min(remaining, 1.0))

    # ---------- 보내기 ----------
    def start(self) -> None:
        threading.Thread(target=self._schedule, daemon=True).start()

    def stop(self) -> None:
        self._stop = True
        self.wake_event.set()

    def _schedule(self) -> None:
        while not self._stop:
            self.wake_event.clear()
            now = self.clock()
            soonest = None
            with self.lock:
                for lane in {_lane(j) for j in self.jobs} - self.busy:
                    head = self._head(lane)
                    if head is None:
                        continue
                    if head.next_at <= now:
                        self.busy.add(lane)
                        threading.Thread(target=self._run_lane, args=(lane,), daemon=True).start()
                    else:
                        soonest = head.next_at if soonest is None else min(soonest, head.next_at)
            delay = 60.0 if soonest is None else max(0.05, min(60.0, soonest - now))
            self.wake_event.wait(delay)

    def _head(self, lane: tuple[str, str]) -> Job | None:
        """self.lock을 잡은 채로 부른다. 그 줄에서 실패하지 않은 맨 앞 건."""
        return next((j for j in self.jobs if _lane(j) == lane and j.state != FAILED), None)

    def _run_lane(self, lane: tuple[str, str]) -> None:
        """한 줄의 보내기를 넣은 순서대로 보낸다. 앞의 것이 기다리는 중이면 뒤의 것도 기다린다."""
        peer = lane[0]
        try:
            while not self._stop:
                with self.lock:
                    head = self._head(lane)
                    if head is None or head.next_at > self.clock():
                        return
                    head.state = SENDING
                    head.attempts += 1
                    self._save()
                    job = head.copy()
                self.on_event("outbox", {"peer": peer, "id": job.id})
                self._attempt(head, job)
        finally:
            with self.lock:
                self.busy.discard(lane)
            self.wake_event.set()

    def _attempt(self, live: Job, job: Job) -> None:
        last = [0.0]

        def progress(done: int, total: int) -> None:
            if live.code == "cancelled":  # 보내는 중 취소: 연결을 끊는다(상대는 조각만 남겼다가 24시간 뒤 지운다)
                raise JobCancelled()
            now = time.monotonic()
            if now - last[0] < PROGRESS_EVERY and done < total:
                return
            last[0] = now
            with self.lock:
                live.done, live.total = done, total
            self.on_event("outbox_progress", {"peer": job.peer_id, "id": job.id, "done": done, "total": total,
                                              "name": job.name})

        try:
            self.deliver(job, progress)
        except Exception as exc:  # noqa: BLE001 - 아래에서 다시 시도·실패로 나눈다
            self._failed(live, job, exc)
            return
        with self.lock:
            self._cleanup(job)
            if live in self.jobs:
                self.jobs.remove(live)
            self._save()
            self.changed.notify_all()
        self.on_event("outbox", {"peer": job.peer_id, "id": job.id, "gone": True, "kind": job.kind})

    def _failed(self, live: Job, job: Job, exc: BaseException) -> None:
        now = self.clock()
        with self.lock:
            if live.code == "cancelled":
                self._cleanup(job)
                self.jobs.remove(live)
                self._save()
                self.changed.notify_all()
                cancelled = True
            else:
                cancelled = False
                # deliver가 바꾼 값(새 xfer, 묶은 zip, 크기)을 이어받는다
                live.xfer, live.packed, live.size, live.mtime_ns = job.xfer, job.packed, job.size, job.mtime_ns
                live.remote_mtime = job.remote_mtime
                live.code, live.error = describe(exc)
                if is_retryable(exc) and now - live.created < GIVE_UP_SEC:
                    live.state = WAITING
                    live.next_at = now + backoff(live.attempts)
                else:
                    live.state = FAILED
                self._save()
                self.changed.notify_all()
        if cancelled:
            self.on_event("outbox", {"peer": job.peer_id, "id": job.id, "gone": True})
            return
        self.on_event("outbox", {"peer": job.peer_id, "id": job.id, "failed": live.state == FAILED,
                                 "error": live.error, "kind": job.kind})

    def _cleanup(self, job: Job) -> None:
        if not _JOB_ID.fullmatch(job.id):
            return  # 작업 폴더 이름은 늘 16자 hex다(다른 곳을 지우지 않게)
        folder = self.work_dir / job.id
        if folder.parent == self.work_dir and folder.exists():
            shutil.rmtree(folder, ignore_errors=True)
