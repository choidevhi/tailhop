"""모든 기기 동기화의 이 기기 쪽 사본(2.3.0, docs/PROTOCOL.md §19.3). 허브마다 하나.

- 자리: %LOCALAPPDATA%\\TailHop\\Mirror\\<sha256(허브 id) 앞 16자>\\. 폴더 이름에도 허브 이름을 쓰지 않는다.
- index.db: 줄마다 Tink AEAD로 잠근 항목 정보(경로·크기·수정 시각·버전·파일 열쇠). 그 keyset은 Windows DPAPI로 잠근다
  (key.bin, 이 PC의 이 Windows 계정에서만 풀린다). 파일 내용은 저장소(§17.1)와 같은 1 MiB 청크 AES-256-GCM, 파일마다 열쇠.
- 탐색기에서는 암호문뿐이고 TailHop 앱 안에서만 열린다. 읽기 전용(바꾸기는 허브로).
- 맞추기(apply)는 허브의 목록과 비교해 이름만 고치거나, 받거나(끊기면 다 쓴 청크부터), 지운다.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator

import cloud
import cloudkeys as keys
import cloudstore as cs
import protocol as p

MIRROR_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "TailHop" / "Mirror"
RESERVE = 1 << 30  # 이만큼은 늘 남긴다(§19.3)
PIECE = 4 * cs.CHUNK  # 한 번에 받는 크기
THUMB_LIMIT = 2 << 20
ID_RE = re.compile(r"[0-9a-f]{32}")
DIGEST_RE = re.compile(r"[0-9a-f]{16}")


class SpaceError(p.ProtocolError):
    def __init__(self) -> None:
        super().__init__("sync_space")


@dataclass
class Want:
    """허브 목록의 한 줄(검사를 마친 것)."""
    id: str
    path: str
    dir: bool
    size: int = 0
    mtime: int = 0
    ver: str = ""
    thumb: int = 0
    tver: str = ""


def parse_manifest_item(raw: object) -> Want | None:
    """§19.2 sync_manifest 항목을 검사한다. 모양이 틀리면 None(그 줄만 버린다)."""
    if not isinstance(raw, dict) or not isinstance(raw.get("id"), str) or not ID_RE.fullmatch(raw["id"]):
        return None
    try:
        parts = cloud.parse_path(raw.get("path"))
    except p.ProtocolError:
        return None
    if not parts:
        return None
    path = cloud.join_path(parts)
    if raw.get("dir") is True:
        return Want(raw["id"], path, True)
    size, mtime, ver = raw.get("size"), raw.get("mtime"), raw.get("ver")
    if not _uint(size) or size > p.MAX_FILE or not _uint(mtime) or not isinstance(ver, str) or not ID_RE.fullmatch(ver):
        return None
    thumb, tver = raw.get("thumb", 0), raw.get("tver", "")
    if not (_uint(thumb) and thumb <= THUMB_LIMIT and isinstance(tver, str) and (ID_RE.fullmatch(tver) or not thumb)):
        thumb, tver = 0, ""
    return Want(raw["id"], path, False, size, mtime, ver, thumb, tver if thumb else "")


def _uint(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def mirror_dir(base: Path, hub_id: str) -> Path:
    return Path(base) / hashlib.sha256(hub_id.encode()).hexdigest()[:16]


@dataclass
class Plan:
    """맞추기 계획: 받을 것(새것·바뀐 것), 경로만 고칠 것, 지울 것."""
    fetch: list[Want]
    moves: list[Want]
    drop: list[str]


def plan(have: dict[str, dict], wants: list[Want]) -> Plan:
    """have: 사본의 {허브 id: 정보}. 같은 id·ver(다 받은 것)면 경로만, 아니면 받기, 목록에 없으면 지우기."""
    fetch, moves = [], []
    seen = set()
    for want in wants:
        seen.add(want.id)
        old = have.get(want.id)
        if want.dir:
            if old is None or old.get("path") != want.path or not old.get("dir"):
                moves.append(want)
            continue
        complete = old is not None and old.get("ver") == want.ver and old.get("done") == cs.chunk_count(want.size)
        if not complete:
            fetch.append(want)
        elif old.get("path") != want.path or old.get("mtime") != want.mtime or old.get("tver") != want.tver:
            moves.append(want)
    return Plan(fetch, moves, [hid for hid in have if hid not in seen])


class Mirror:
    """허브 하나의 사본. 모든 함수는 작업 스레드에서 불러도 된다(안에서 잠근다)."""

    def __init__(self, base: Path, hub_id: str) -> None:
        self.hub_id = hub_id
        self.dir = mirror_dir(base, hub_id)
        self._mu = threading.RLock()
        self.entries: dict[str, dict] = {}
        self.digest = ""
        self.synced_at = 0.0
        self.db: sqlite3.Connection | None = None
        self.aead = None
        self._open()

    # ---------- 열기 ----------
    def _ad(self, what: str) -> bytes:
        return f"tailhop mirror|{self.dir.name}|{what}".encode()

    def _open(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        cs._hide(self.dir.parent)
        key_path = self.dir / "key.bin"
        handle = keys.open_local(key_path.read_bytes(), self._ad("key")) if key_path.exists() else None
        if handle is None:  # 처음이거나 다른 Windows 계정·다시 깐 Windows: 못 푸는 사본은 지우고 새로
            self._wipe_files()
            self.dir.mkdir(parents=True, exist_ok=True)
            handle = keys.new_keyset()
            tmp = key_path.with_suffix(".tmp")
            tmp.write_bytes(keys.seal_local(handle, self._ad("key")))
            os.replace(tmp, key_path)
        self.aead = keys.primitive(handle)
        db = sqlite3.connect(self.dir / "index.db", check_same_thread=False, isolation_level=None)
        db.execute("PRAGMA trusted_schema=OFF")
        if db.execute("SELECT count(*) FROM sqlite_master WHERE type IN ('trigger', 'view')").fetchone()[0]:
            db.close()
            raise p.ProtocolError("storage")
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA secure_delete=ON")
        db.executescript("""
            CREATE TABLE IF NOT EXISTS entries(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, sealed BLOB NOT NULL);
        """)
        self.db = db
        for hid, sealed in db.execute("SELECT id, sealed FROM entries").fetchall():
            info = self._unseal(sealed, "entry " + hid)
            if ID_RE.fullmatch(hid) and isinstance(info, dict) and isinstance(info.get("path"), str):
                self.entries[hid] = info
        state = self._unseal_kv("state") or {}
        self.digest = state.get("digest", "") if isinstance(state.get("digest"), str) else ""
        self.synced_at = float(state.get("at", 0)) if isinstance(state.get("at"), (int, float)) else 0.0

    def _seal(self, data: dict, what: str) -> bytes:
        return self.aead.encrypt(json.dumps(data, separators=(",", ":")).encode(), self._ad(what))

    def _unseal(self, sealed: bytes, what: str) -> dict | None:
        try:
            data = json.loads(self.aead.decrypt(sealed, self._ad(what)))
        except (keys.tink.TinkError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _unseal_kv(self, name: str) -> dict | None:
        row = self.db.execute("SELECT sealed FROM kv WHERE k=?", (name,)).fetchone()
        return self._unseal(row[0], "kv " + name) if row else None

    def _put(self, hid: str, info: dict) -> None:
        self.db.execute("INSERT OR REPLACE INTO entries VALUES(?, ?)", (hid, self._seal(info, "entry " + hid)))
        self.entries[hid] = info

    def _save_state(self) -> None:
        self.db.execute("INSERT OR REPLACE INTO kv VALUES('state', ?)",
                        (self._seal({"digest": self.digest, "at": self.synced_at}, "kv state"),))

    def close(self) -> None:
        with self._mu:
            if self.db is not None:
                self.db.close()
                self.db = None

    # ---------- 파일 ----------
    def _object(self, oid: str) -> Path:
        if not ID_RE.fullmatch(oid):
            raise p.ProtocolError("storage")
        return self.dir / "objects" / oid[:2] / oid

    def _drop_objects(self, info: dict) -> None:
        for ref in (info, info.get("t") or {}):
            oid = ref.get("o")
            if isinstance(oid, str) and ID_RE.fullmatch(oid):
                self._object(oid).unlink(missing_ok=True)

    def complete(self, info: dict) -> bool:
        return not info.get("dir") and info.get("done") == cs.chunk_count(int(info.get("size", 0)))

    def find(self, path: str, size: int | None = None, mtime: int | None = None) -> dict | None:
        """다 받은 사본 중 그 경로의 파일(크기·수정 시각을 주면 같아야 한다)."""
        with self._mu:
            for info in self.entries.values():
                if info.get("path") == path and self.complete(info) \
                        and (size is None or info.get("size") == size) and (mtime is None or info.get("mtime") == mtime):
                    return info
        return None

    def read(self, info: dict, offset: int = 0, length: int | None = None) -> Iterator[bytes]:
        return cs.iter_plain(self._object(info["o"]), keys.unb64(info["k"]), int(info["size"]), offset, length)

    def read_all(self, info: dict, limit: int) -> bytes:
        if int(info["size"]) > limit:
            raise p.ProtocolError("too_large")
        return b"".join(self.read(info))

    def thumb(self, info: dict) -> bytes | None:
        ref = info.get("t")
        if not isinstance(ref, dict) or not ref.get("o"):
            return None
        try:
            return b"".join(cs.iter_plain(self._object(ref["o"]), keys.unb64(ref["k"]), int(ref["s"])))
        except (OSError, p.ProtocolError):
            return None

    def list_dir(self, path: str) -> list[cloud.Item]:
        """사본의 한 폴더(허브 목록과 같은 모양). 폴더는 받은 폴더 줄과 파일 경로의 중간 폴더에서."""
        prefix = path + "/" if path else ""
        files: dict[str, cloud.Item] = {}
        dirs: dict[str, int] = {}
        with self._mu:
            for info in self.entries.values():
                full = info.get("path", "")
                if not full.startswith(prefix) or full == path:
                    continue
                rest = full[len(prefix):]
                head, sep, _ = rest.partition("/")
                if sep or info.get("dir"):
                    dirs.setdefault(head, 0)
                elif self.complete(info):
                    files[head] = cloud.Item(head, False, int(info["size"]), int(info.get("mtime", 0)),
                                             variants=("thumb",) if info.get("t") else ())
        items = [cloud.Item(name, True, 0, 0) for name in dirs] + [x for name, x in files.items() if name not in dirs]
        items.sort(key=lambda x: (not x.is_dir, x.name.casefold(), x.name))
        return items

    def usage(self) -> tuple[int, int]:
        """(다 받은 파일 수, 그 크기)."""
        with self._mu:
            done = [x for x in self.entries.values() if self.complete(x)]
        return len(done), sum(int(x["size"]) for x in done)

    # ---------- 맞추기 ----------
    def apply(self, digest: str, wants: list[Want], fetch: Callable[[str, int, int], bytes],
              fetch_thumb: Callable[[str], bytes], stop: Callable[[], bool] = lambda: False,
              progress: Callable[[int, int], None] | None = None) -> int:
        """허브 목록에 맞춘다. 받은 파일 수. 공간이 모자라면 SpaceError(받은 데까지는 남는다)."""
        with self._mu:
            todo = plan(self.entries, wants)
            for hid in todo.drop:
                info = self.entries.pop(hid)
                self._drop_objects(info)
                self.db.execute("DELETE FROM entries WHERE id=?", (hid,))
            for want in todo.moves:
                info = dict(self.entries.get(want.id) or {})
                info.update({"path": want.path, "dir": want.dir})
                if not want.dir:
                    info["mtime"] = want.mtime
                    if info.get("tver") != want.tver:
                        self._drop_thumb(info)
                self._put(want.id, info)
        total = sum(w.size for w in todo.fetch)
        got_bytes, count = 0, 0
        for want in todo.fetch:
            if stop():
                break
            self._fetch_one(want, fetch, stop)
            count += 1
            got_bytes += want.size
            if progress is not None:
                progress(got_bytes, total)
        for want in wants:  # 미리보기만 바뀌었거나 아직 없는 것
            if stop():
                break
            info = self.entries.get(want.id)
            if info is not None and want.thumb and self.complete(info) and not info.get("t"):
                self._fetch_thumb(want, info, fetch_thumb)
        if not stop():
            with self._mu:
                self.digest, self.synced_at = digest, time.time()
                self._save_state()
        return count

    def _drop_thumb(self, info: dict) -> None:
        ref = info.pop("t", None)
        info.pop("tver", None)
        if isinstance(ref, dict) and isinstance(ref.get("o"), str) and ID_RE.fullmatch(ref["o"]):
            self._object(ref["o"]).unlink(missing_ok=True)

    def _fetch_thumb(self, want: Want, info: dict, fetch_thumb: Callable[[str], bytes]) -> None:
        try:
            data = fetch_thumb(want.path)
        except (OSError, p.ProtocolError):
            return
        oid, key = os.urandom(16).hex(), keys.to_bytes(keys.new_keyset())
        w = cs.ObjectWriter(self._object(oid), key)
        try:
            w.write(data)
            size = w.finish()
        finally:
            w.close()
        with self._mu:
            info["t"] = {"o": oid, "k": keys.b64(key), "s": size}
            info["tver"] = want.tver
            self._put(want.id, info)

    def _fetch_one(self, want: Want, fetch: Callable[[str, int, int], bytes], stop: Callable[[], bool]) -> None:
        with self._mu:
            old = self.entries.get(want.id)
            resume = old is not None and old.get("ver") == want.ver and isinstance(old.get("o"), str)
            if old is not None and not resume:
                self._drop_objects(old)
            info = dict(old) if resume else {"o": os.urandom(16).hex(), "k": keys.b64(keys.to_bytes(keys.new_keyset())),
                                            "done": 0}
            info.update({"path": want.path, "dir": False, "size": want.size, "mtime": want.mtime, "ver": want.ver})
            done = int(info.get("done", 0)) if resume else 0
            if not self._object(info["o"]).exists():
                done = 0
            self._check_space(want.size - done * cs.CHUNK)
            info["done"] = done
            self._put(want.id, info)
        writer = cs.ObjectWriter(self._object(info["o"]), keys.unb64(info["k"]), done)
        try:
            offset = done * cs.CHUNK
            while offset < want.size:
                if stop():
                    writer.sync()
                    self._mark(want.id, info, writer.chunks)
                    return
                data = fetch(want.path, offset, min(PIECE, want.size - offset))
                if not data:
                    raise p.ProtocolError("file_incomplete")
                writer.write(data)
                offset += len(data)
                writer.sync()
                self._mark(want.id, info, writer.chunks)
            if offset != want.size:
                raise p.ProtocolError("file_incomplete")
            writer.finish()
            self._mark(want.id, info, cs.chunk_count(want.size))
        finally:
            writer.close()

    def _mark(self, hid: str, info: dict, chunks: int) -> None:
        with self._mu:
            info["done"] = chunks
            self._put(hid, info)

    def _check_space(self, need: int) -> None:
        try:
            free = shutil.disk_usage(self.dir).free
        except OSError:
            return
        if free < max(0, need) + RESERVE:
            raise SpaceError()

    # ---------- 지우기 ----------
    def _wipe_files(self) -> None:
        if self.dir.exists() and not cs._is_link(self.dir):
            shutil.rmtree(self.dir, ignore_errors=True)

    def wipe(self) -> None:
        """이 허브의 사본을 모두 지운다(받기를 끄거나 페어링을 끊을 때)."""
        with self._mu:
            self.close()
            self.entries.clear()
            self.digest = ""
            self._wipe_files()
