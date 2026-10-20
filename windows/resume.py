"""이어받기 장부(docs/PROTOCOL.md §11): 받다 만 조각(.part)과 끝까지 받은 xfer를 상대 기기별로 24시간 기억한다.

- 조각: 끊기거나 시간 초과로 멈춘 받기의 `.part`. 같은 상대가 같은 xfer·size로 다시 오면 그 길이부터 이어 받는다.
- 끝난 xfer: 응답이 사라져 상대가 다시 보낸 글·파일을 두 번 기록하지 않게 한다(hello의 done, 글 중복 무시).
- 한 조각은 한 번에 한 연결만 쓴다(busy). 다른 연결이 이어 쓰려 하면 resume_lost로 처음부터 다시 하게 한다.
장부 파일(resume.json)은 이 사용자만 읽는 ACL로 쓴다. 조각 파일은 받는 폴더에 그대로 둔다.
"""
from __future__ import annotations

import json
import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import store

KEEP_SEC = 24 * 3600
MAX_DONE = 5000  # 끝난 xfer 기록 수 상한(오래된 것부터 버린다)
MAX_PARTIALS_PER_PEER = 32  # 한 상대가 남겨 둘 수 있는 조각 수(넘으면 오래된 것부터 지운다)
PRUNE_EVERY = 3600  # 실행 중에도 이 간격으로 24시간 지난 것을 지운다
_XFER = re.compile(r"[0-9a-f]{32}")


def valid_xfer(value: object) -> bool:
    return isinstance(value, str) and _XFER.fullmatch(value) is not None


@dataclass
class Partial:
    peer: str
    xfer: str
    size: int
    part: Path
    final: Path
    ts: float
    kind: str = "file"  # file(대화방) 또는 cloud_put. 조각은 같은 종류·같은 폴더로만 이어 받는다

    def to_json(self) -> dict:
        return {"peer": self.peer, "xfer": self.xfer, "size": self.size, "part": str(self.part),
                "final": str(self.final), "ts": self.ts, "kind": self.kind}


def _key(peer: str, xfer: str) -> str:
    return f"{peer}|{xfer}"


class ResumeBook:
    def __init__(self, app_dir: Path, clock=time.time) -> None:
        self.path = Path(app_dir) / "resume.json"
        self.clock = clock
        self.lock = threading.Lock()
        self.partials: dict[str, Partial] = {}
        self.done: dict[str, float] = {}
        self.busy: set[str] = set()
        self._pruned = 0.0
        self._load()
        self.prune()

    def _load(self) -> None:
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError):
            return

        if not isinstance(data, dict):
            return

        for raw in data.get("partials") or []:
            try:
                item = Partial(str(raw["peer"]), str(raw["xfer"]), int(raw["size"]), Path(raw["part"]),
                               Path(raw["final"]), float(raw["ts"]), str(raw.get("kind") or "file"))
            except (KeyError, TypeError, ValueError):
                continue

            if valid_xfer(item.xfer) and item.part.name.endswith(".part"):
                self.partials[_key(item.peer, item.xfer)] = item

        for key, ts in (data.get("done") or {}).items():
            if isinstance(key, str) and isinstance(ts, (int, float)):
                self.done[key] = float(ts)

    def _save(self) -> None:
        data = {"partials": [x.to_json() for x in self.partials.values()], "done": self.done}
        try:
            store._write_private(self.path, json.dumps(data))
        except OSError:
            pass  # 장부를 못 써도 받기는 된다(다음에 이어받기만 못 할 뿐)

    def prune(self) -> None:
        """24시간 지난 조각(파일까지)과 끝난 기록을 지운다."""
        now = self.clock()
        self._pruned = now
        with self.lock:
            old = [k for k, x in self.partials.items() if now - x.ts > KEEP_SEC and k not in self.busy]
            for key in old:
                self.partials.pop(key).part.unlink(missing_ok=True)

            self.done = {k: v for k, v in self.done.items() if now - v <= KEEP_SEC}
            if len(self.done) > MAX_DONE:
                self.done = dict(sorted(self.done.items(), key=lambda kv: kv[1])[-MAX_DONE:])

            self._save()

    # ---------- hello ----------
    def have(self, peer: str, xfer: str, size: int) -> int:
        """같은 상대·xfer·size의 조각 길이(없으면 0)."""
        with self.lock:
            item = self.partials.get(_key(peer, xfer))
            if item is None or item.size != size:
                return 0

            try:
                return min(item.part.stat().st_size, size)
            except OSError:
                return 0

    def is_done(self, peer: str, xfer: str) -> bool:
        with self.lock:
            return _key(peer, xfer) in self.done

    # ---------- 받기 ----------
    def begin(self, peer: str, xfer: str, size: int, part: Path, final: Path, kind: str = "file") -> None:
        """처음부터 받는 조각을 적는다. 같은 키의 예전 조각 파일은 지운다. 한 상대의 조각이 너무 많으면 오래된 것부터 지운다."""
        if self.clock() - self._pruned > PRUNE_EVERY:
            self.prune()
        key = _key(peer, xfer)
        with self.lock:
            old = self.partials.get(key)
            if old is not None and old.part != part and key not in self.busy:
                old.part.unlink(missing_ok=True)

            mine = sorted((x.ts, k) for k, x in self.partials.items()
                          if x.peer == peer and k != key and k not in self.busy)
            for _, k in mine[:max(0, len(mine) - MAX_PARTIALS_PER_PEER + 1)]:
                self.partials.pop(k).part.unlink(missing_ok=True)

            self.partials[key] = Partial(peer, xfer, size, part, final, self.clock(), kind)
            self.busy.add(key)
            self._save()

    def claim(self, peer: str, xfer: str, size: int, offset: int, kind: str = "file",
              folder: Path | None = None) -> Partial | None:
        """이어 받을 조각을 잡는다. 없거나, 크기·종류·받을 폴더가 다르거나, 짧거나, 다른 연결이 쓰는 중이면 None.
        (클라우드를 끈 뒤 대화방 파일로 이어 보내 클라우드 폴더에 쓰는 일, 바꾸기 전 폴더에 쓰는 일을 막는다.)"""
        key = _key(peer, xfer)
        with self.lock:
            item = self.partials.get(key)
            if item is None or item.size != size or key in self.busy or item.kind != kind:
                return None
            if folder is not None and item.final.parent != Path(folder):
                return None

            try:
                length = item.part.stat().st_size
            except OSError:
                return None

            if length < offset:
                return None

            self.busy.add(key)
            item.ts = self.clock()
            return item

    def keep(self, peer: str, xfer: str, part: Path) -> None:
        """끊김: 조각을 남겨 둔다. 그 사이 다른 연결이 같은 키를 새로 잡았으면 내 조각 파일은 지운다."""
        key = _key(peer, xfer)
        with self.lock:
            self.busy.discard(key)
            item = self.partials.get(key)
            if item is None or item.part != part:
                part.unlink(missing_ok=True)
                return

            item.ts = self.clock()
            self._save()

    def drop(self, peer: str, xfer: str, part: Path) -> None:
        """규칙 위반·해시 불일치 등: 조각과 기록을 지운다."""
        key = _key(peer, xfer)
        with self.lock:
            self.busy.discard(key)
            item = self.partials.get(key)
            if item is not None and item.part == part:
                del self.partials[key]

            part.unlink(missing_ok=True)
            self._save()

    def finish(self, peer: str, xfer: str) -> None:
        """끝까지 받음(글은 기록에 넣은 뒤): 조각 기록을 지우고 끝난 xfer로 남긴다."""
        key = _key(peer, xfer)
        with self.lock:
            self.busy.discard(key)
            self.partials.pop(key, None)
            self.done[key] = self.clock()
            self._save()

    def forget_kind(self, kind: str) -> None:
        """그 종류의 조각을 모두 지운다(클라우드를 끄거나 폴더를 바꿀 때 cloud_put 조각)."""
        with self.lock:
            for key in [k for k, x in self.partials.items() if x.kind == kind and k not in self.busy]:
                self.partials.pop(key).part.unlink(missing_ok=True)
            self._save()

    def forget_peer(self, peer: str) -> None:
        """연결 해제: 그 상대의 조각 파일과 기록을 지운다."""
        with self.lock:
            for key in [k for k, x in self.partials.items() if x.peer == peer and k not in self.busy]:
                self.partials.pop(key).part.unlink(missing_ok=True)

            self.done = {k: v for k, v in self.done.items() if not k.startswith(peer + "|")}
            self._save()
