"""암호화 클라우드 저장소(2.2.0, docs/PROTOCOL.md §17). 허브 PC의 아무 드라이브·폴더(외장 SSD도)에 둔다.

탐색기에는 알아볼 수 없는 조각만 보인다. 이름·폴더 구조의 이름·내용·미리보기는 모두 암호문이고, TailHop 앱(PC·폰)이
허브를 거쳐서만 연다. 암호 계산은 Tink(cloudkeys.py)가 한다.

폴더 안 모양: 고른 폴더 안의 숨김 하위 폴더 .tailhop-store 하나만 쓴다(같은 폴더의 다른 파일·폴더는 읽지도 지우지도 않는다).
- store.json: 저장소 표식(id)과 비밀번호·복구 코드로 감싼 저장소 열쇠(Tink 암호화된 키 묶음).
- meta.db: SQLite(WAL, synchronous=FULL, secure_delete). 항목마다 한 줄:
  id(난수) · parent(부모 id, 맨 위는 "") · ring(그 줄을 연 열쇠: "main" 또는 잠긴 폴더 id) · meta(암호문).
  meta는 ring 열쇠의 Tink AEAD(JSON, 연관 데이터 = id·parent·ring)이고 이름·크기·시각·파일 열쇠가 들어 있다.
  드러나는 것은 나무 모양(무엇이 무엇 아래 있는지)과 개수, 조각 파일 크기뿐이다.
- objects/<2글자>/<oid>: 파일 내용. 파일마다 새 Tink 키 묶음(봉투 암호화)으로 1 MiB씩 따로 잠근 청크를 이어 붙인다.
  청크 i의 연관 데이터 = 번호 i와 마지막 여부. 그래서 중간부터 읽을 수 있고(영상 건너뛰기), 순서를 바꾸거나 잘라내면 열리지 않는다.
- incoming/<oid>.part: 받는 중인 파일(이어받기). 끊기면 청크 단위로 이어 받는다.

열쇠
- 저장소 열쇠(main)는 이 PC의 Windows 계정(DPAPI) 사본(%APPDATA%\\TailHop\\stores\\<id>.key)으로 자동으로 연다.
  새 PC·Windows를 다시 깔았으면 비밀번호(또는 복구 코드)로 한 번 연다. 잊으면 리셋(새 빈 저장소)뿐이다.
- 잠긴 폴더(PIN): 그 아래 줄들의 meta를 그 폴더 열쇠로 다시 암호화한다(파일 내용은 다시 쓰지 않는다 — 파일 열쇠가 meta 안에
  있으므로). 그래서 잠그기·풀기가 바로 끝난다. 폴더 열쇠는 PIN·복구 코드로 감싸 저장소 열쇠로 한 번 더 암호화해 둔다.
- 지우기: 잠긴 폴더 밖은 휴지통(30일 뒤 영구 삭제), 잠긴 폴더 안은 바로 영구 삭제.
"""
from __future__ import annotations

import ctypes
import hashlib
import io
import json
import os
import re
import shutil
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator

import cloud
import cloudkeys as keys
import protocol as p

FORMAT = "tailhop-store"
VERSION = 1
CHUNK = 1024 * 1024
CT_CHUNK = CHUNK + keys.OVERHEAD
MAIN = "main"
TRASH = "~trash"
TRASH_DAYS = 30
TOKEN_TTL = 1800
MAX_FAILS = 5
MIN_PIN = 6
MAX_PIN = 64
CHECKPOINT_EVERY = 32  # 받는 중 이어받기 위치를 디스크에 적는 청크 간격
PARTIAL_DAYS = 7  # 이어받지 않은 조각은 이만큼 뒤 지운다
SEARCH_SCAN = 50_000
SEARCH_SECONDS = 3.0

MISSING, NEW, LOCKED, OPEN = "missing", "new", "locked", "open"
STORE_DIR = ".tailhop-store"  # 고른 폴더 안에서 저장소가 쓰는 단 하나의 하위 폴더
OID_RE = re.compile(r"[0-9a-f]{32}")  # 객체·조각 파일 이름(평문 열에서 읽은 값은 늘 이것으로 확인한다)
SWEEP_SEC = 3600
SYNC_PAGE = 2000  # §19.2 sync_manifest 한 쪽
SYNC_SET_MAX = 500


def _is_link(path: Path) -> bool:
    try:
        return path.is_symlink() or path.is_junction()
    except OSError:
        return True


def _hide(path: Path) -> None:
    try:
        attrs = ctypes.windll.kernel32.GetFileAttributesW(str(path))
        if attrs != -1 and attrs != 0xFFFFFFFF:
            ctypes.windll.kernel32.SetFileAttributesW(str(path), attrs | 0x2)
    except (AttributeError, OSError):
        pass


def _lookup(peer: str, xfer: str, size: int) -> str:
    """이어받기 조각을 찾는 열쇠. 평문 열에 기기 이름·크기를 두지 않으려고 xfer(128비트 난수)와 함께 해시한다."""
    return hashlib.sha256(f"{xfer}|{peer}|{size}".encode()).hexdigest()


def _hex() -> str:
    return os.urandom(16).hex()


def chunk_count(size: int) -> int:
    return max(1, -(-size // CHUNK))


def _chunk_ad(index: int, last: bool) -> bytes:
    return b"tailhop chunk|" + index.to_bytes(8, "big") + (b"\x01" if last else b"\x00")


def _node_ad(nid: str, parent: str, ring: str) -> bytes:
    return f"tailhop node|{nid}|{parent}|{ring}".encode()


def valid_pin(pin: object) -> bool:
    return isinstance(pin, str) and MIN_PIN <= len(pin) <= MAX_PIN


# ---------- 파일 내용(객체) ----------
class ObjectWriter:
    """평문을 받아 1 MiB 청크로 잠가 쓴다. 마지막 청크를 알 수 있게 한 청크를 늘 들고 있다가 다음 것이 오면 쓴다.
    이어 쓸 때는 끝난 청크 수(done)까지 자르고 그 뒤에 붙인다."""

    def __init__(self, path: Path, key: bytes, done: int = 0) -> None:
        self.path, self.key = path, key
        self.aead = keys.primitive(keys.from_bytes(key))
        path.parent.mkdir(parents=True, exist_ok=True)
        self.out = open(path, "r+b" if done else "wb")
        if done:
            self.out.truncate(done * CT_CHUNK)
            self.out.seek(done * CT_CHUNK)
        self.chunks = done
        self.buf = bytearray()
        self.total = done * CHUNK

    def write(self, data: bytes) -> None:
        self.buf += data
        self.total += len(data)
        while len(self.buf) > CHUNK:
            self._emit(bytes(self.buf[:CHUNK]), last=False)
            del self.buf[:CHUNK]

    def _emit(self, plain: bytes, last: bool) -> None:
        self.out.write(self.aead.encrypt(plain, _chunk_ad(self.chunks, last)))
        self.chunks += 1

    @property
    def done_bytes(self) -> int:
        """디스크에 다 쓴 청크까지의 평문 크기(이어받기 위치)."""
        return self.chunks * CHUNK

    def sync(self) -> None:
        self.out.flush()
        os.fsync(self.out.fileno())

    def finish(self) -> int:
        self._emit(bytes(self.buf), last=True)
        self.buf.clear()
        self.sync()
        self.out.close()
        return self.total

    def close(self) -> None:
        if not self.out.closed:
            self.out.close()


def iter_plain(path: Path, key: bytes, size: int, offset: int = 0, length: int | None = None,
               partial: bool = False) -> Iterator[bytes]:
    """offset부터 length만큼(없으면 끝까지) 평문을 청크 단위로 준다. partial이면 받는 중인 조각(마지막 표시 없음)."""
    aead = keys.primitive(keys.from_bytes(key))
    count = size // CHUNK if partial else chunk_count(size)
    end = size if length is None else min(size, offset + length)
    if offset >= end and not (size == 0 and offset == 0):
        return
    index = offset // CHUNK
    skip = offset - index * CHUNK
    with open(path, "rb") as f:
        f.seek(index * CT_CHUNK)
        while index < count and index * CHUNK < max(end, 1):
            last = not partial and index == count - 1
            plain_len = size - index * CHUNK if last else CHUNK
            sealed = f.read(plain_len + keys.OVERHEAD)
            try:
                plain = aead.decrypt(sealed, _chunk_ad(index, last))
            except keys.tink.TinkError as exc:
                raise p.ProtocolError("storage") from exc
            if len(plain) != plain_len:
                raise p.ProtocolError("storage")
            stop = min(len(plain), end - index * CHUNK)
            yield plain[skip:stop]
            skip = 0
            index += 1


class ObjectReader(io.RawIOBase):
    """중간부터 읽을 수 있는 평문 보기(Pillow·영상 변환이 seek한다). 청크 하나만 들고 있는다."""

    def __init__(self, path: Path, key: bytes, size: int) -> None:
        super().__init__()
        self.path, self.size = path, size
        self.aead = keys.primitive(keys.from_bytes(key))
        self.file = open(path, "rb")
        self.pos = 0
        self.cached: tuple[int, bytes] | None = None

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return True

    def tell(self) -> int:
        return self.pos

    def seek(self, offset: int, whence: int = io.SEEK_SET) -> int:
        base = {io.SEEK_SET: 0, io.SEEK_CUR: self.pos, io.SEEK_END: self.size}[whence]
        self.pos = max(0, base + offset)
        return self.pos

    def _chunk(self, index: int) -> bytes:
        if self.cached is not None and self.cached[0] == index:
            return self.cached[1]
        count = chunk_count(self.size)
        last = index == count - 1
        plain_len = self.size - index * CHUNK if last else CHUNK
        self.file.seek(index * CT_CHUNK)
        try:
            plain = self.aead.decrypt(self.file.read(plain_len + keys.OVERHEAD), _chunk_ad(index, last))
        except keys.tink.TinkError as exc:
            raise OSError("storage") from exc
        self.cached = (index, plain)
        return plain

    def readinto(self, b) -> int:
        if self.pos >= self.size:
            return 0
        index = self.pos // CHUNK
        data = self._chunk(index)
        start = self.pos - index * CHUNK
        n = min(len(b), len(data) - start)
        b[:n] = data[start:start + n]
        self.pos += n
        return n

    def close(self) -> None:
        self.file.close()
        super().close()


# ---------- 항목 ----------
@dataclass
class Node:
    id: str
    parent: str
    ring: str
    meta: dict

    @property
    def name(self) -> str:
        return self.meta["n"]

    @property
    def is_dir(self) -> bool:
        return bool(self.meta.get("d"))

    @property
    def size(self) -> int:
        return int(self.meta.get("s", 0))

    @property
    def mtime(self) -> int:
        return int(self.meta.get("m", 0))

    @property
    def vault(self) -> str | None:
        return self.meta.get("vault")

    def variant(self, role: str) -> dict | None:
        return (self.meta.get("v") or {}).get(role)


@dataclass
class Token:
    ring: str
    peer: str
    expires: float


@dataclass
class Upload:
    """받는 중인 파일 하나(cloud_put·모으기). commit 전에는 목록에 보이지 않는다."""
    store: "Store"
    oid: str
    ring: str
    parent: str
    name: str
    mtime: int
    size: int
    peer: str
    xfer: str
    writer: ObjectWriter
    digest: "hashlib._Hash" = field(default_factory=hashlib.sha256)
    since_sync: int = 0

    def write(self, data: bytes) -> None:
        before = self.writer.chunks
        self.writer.write(data)
        self.digest.update(data)
        self.since_sync += self.writer.chunks - before
        if self.since_sync >= CHECKPOINT_EVERY:
            self.checkpoint()

    def checkpoint(self) -> None:
        self.writer.sync()
        self.since_sync = 0
        self.store._partial_done(self.oid, self.writer.chunks)

    def keep(self) -> None:
        """끊김: 다 쓴 청크까지 남겨 둔다(다음 hello의 have)."""
        try:
            self.checkpoint()
        finally:
            self.writer.close()
            self.store._writer_done(self)

    def abort(self) -> None:
        self.writer.close()
        self.store._drop_partial(self.oid)
        self.store._writer_done(self)

    def commit(self, unique: bool = True) -> str:
        """다 받음: 객체를 제자리로 옮기고 목록에 넣는다. 저장된 이름."""
        try:
            size = self.writer.finish()
            if size != self.size:
                raise p.ProtocolError("file_incomplete")
            return self.store._commit_upload(self, unique)
        finally:
            self.writer.close()
            self.store._writer_done(self)


class Store:
    """저장소 하나. 상태: missing(폴더가 없음, 외장 드라이브가 빠짐) · new(아직 안 만듦) · locked(비밀번호 필요) · open."""

    def __init__(self, root: Path, app_dir: Path, clock: Callable[[], float] = time.time,
                 on_media: Callable[[str], None] | None = None, on_open: Callable[[], None] | None = None,
                 strong: bool = False) -> None:
        self.root = Path(root)
        self.strong = strong  # 강한 잠금(§18.3): 이 PC 계정으로 자동으로 여는 사본을 두지 않는다
        self.app_dir = Path(app_dir)
        self.clock = clock
        self._mu = threading.RLock()
        self.state = MISSING
        self.id = ""
        self.db: sqlite3.Connection | None = None
        self.rings: dict[str, keys.aead.Aead] = {}
        self.nodes: dict[str, Node] = {}
        self.kids: dict[str, dict[str, str]] = {}  # 부모 id → {이름: id}
        self.tokens: dict[str, Token] = {}
        self.fails: dict[tuple[str, str], tuple[int, float]] = {}
        self.gates: dict[str, threading.Lock] = {}
        self.uploads: dict[str, Upload] = {}  # oid → 받는 중
        self.changes = 0  # 항목이 바뀔 때마다 올린다(동기화 목록을 다시 만들지 정한다, §19.2)
        self.sync_all = False
        self._manifest: tuple[int, str, list[dict]] | None = None
        self.on_media = on_media  # 새 사진·영상이 들어오면(미리보기 만들기)
        self.on_open = on_open  # 저장소·잠긴 폴더가 열리면(밀린 미리보기 만들기)
        self.refresh()

    # ---------- 상태 ----------
    @property
    def base(self) -> Path:
        return self.root / STORE_DIR

    @property
    def info_path(self) -> Path:
        return self.base / "store.json"

    def _inner(self, name: str) -> Path:
        """저장소 안의 하위 폴더(objects·incoming). 링크·정션이면 쓰지 않는다(밖의 파일을 지우거나 쓰지 않게)."""
        if _is_link(self.base):
            raise p.ProtocolError("storage")
        path = self.base / name
        if path.exists() and _is_link(path):
            raise p.ProtocolError("storage")
        return path

    def _part_path(self, oid: str) -> Path:
        if not OID_RE.fullmatch(oid):
            raise p.ProtocolError("storage")
        return self._inner("incoming") / f"{oid}.part"

    def _key_path(self, sid: str) -> Path:
        return self.app_dir / "stores" / f"{sid}.key"

    def _read_info(self) -> dict | None:
        try:
            info = json.loads(self.info_path.read_text("utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(info, dict) or info.get("format") != FORMAT or info.get("v") != VERSION:
            return None
        sid = info.get("id")
        return info if isinstance(sid, str) and len(sid) == 32 and all(c in "0123456789abcdef" for c in sid) else None

    def refresh(self) -> str:
        """폴더·저장소·이 PC의 열쇠를 다시 본다(외장 드라이브를 다시 꽂았을 때 등). 지금 상태."""
        with self._mu:
            if self.state == OPEN and self.root.is_dir() and self.info_path.exists():
                return self.state
            self._close()
            if not self.root.is_dir() or (self.base.exists() and _is_link(self.base)):
                self.state = MISSING
                return self.state
            if not self.info_path.exists():
                self.state = NEW
                return self.state
            info = self._read_info()
            if info is None:
                self.state = MISSING  # 알아볼 수 없는 store.json(다른 버전·망가짐): 건드리지 않는다
                return self.state
            self.id = info["id"]
            try:
                blob = None if self.strong else self._key_path(self.id).read_bytes()
            except OSError:
                blob = None
            handle = keys.open_local(blob, self._ad("local")) if blob else None
            if handle is None:
                self.state = LOCKED
                return self.state
            self._open(handle)
            return self.state

    def _ad(self, what: str) -> bytes:
        return f"tailhop store {what}|{self.id}".encode()

    def _close(self) -> None:
        if self.db is not None:
            self.db.close()
        self.db = None
        self._main_keyset = b""
        self.rings.clear()
        self.nodes.clear()
        self.kids.clear()
        self.tokens.clear()

    def close(self) -> None:
        with self._mu:
            for up in list(self.uploads.values()):
                up.writer.close()
            self.uploads.clear()
            self._close()
            self.state = MISSING if not self.root.is_dir() else self.state

    # ---------- 만들기·열기·비밀번호 ----------
    def create(self, password: str) -> str:
        """빈 폴더에 새 저장소를 만든다. 복구 코드를 돌려준다(한 번만 보인다)."""
        if not keys.password_ok(password):
            raise p.ProtocolError("store_password")
        with self._mu:
            if self.refresh() != NEW:
                raise p.ProtocolError("store_exists")
            self.id = _hex()
            handle = keys.new_keyset()
            code = keys.new_recovery_code()
            info = {"format": FORMAT, "v": VERSION, "id": self.id, "created": int(self.clock() * 1000),
                    "password": keys.seal_secret(handle, password, self._ad("password")),
                    "recovery": keys.seal_secret(handle, code, self._ad("recovery"))}
            self.base.mkdir(parents=True, exist_ok=True)
            _hide(self.base)
            tmp = self.info_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(info), "utf-8")
            os.replace(tmp, self.info_path)
            if not self.strong:
                self._save_local(handle)
            self._open(handle)
            return code

    def _save_local(self, handle) -> None:
        path = self._key_path(self.id)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(keys.seal_local(handle, self._ad("local")))
        os.replace(tmp, path)

    def unlock(self, password: str | None = None, recovery: str | None = None) -> None:
        """새 PC·다시 깐 Windows: 비밀번호나 복구 코드로 열고 이 PC의 Windows 계정에 다시 묶는다."""
        with self._mu:
            if self.state != LOCKED:
                raise p.ProtocolError("store_state")
            handle = self._check_store_secret(password, recovery)
            if self.strong:
                self._key_path(self.id).unlink(missing_ok=True)
            else:
                self._save_local(handle)
            self._open(handle)

    def unlock_remote(self, password: object, peer: str) -> None:
        """§18.3 store_unlock: 다른 기기(폰)에서 클라우드 비밀번호로 연다. 기기마다 틀린 횟수를 따로 센다."""
        if not isinstance(password, str) or not password:
            raise p.ProtocolError("store_password")
        with self._mu:
            gate = self.gates.setdefault("store", threading.Lock())
        with gate:
            now = self.clock()
            count, until = self.fails.get(("store", peer), (0, 0.0))
            if now < until:
                raise p.ProtocolError("vault_wait", retry=int(until - now) + 1)
            count += 1
            wait = 0.0 if count < MAX_FAILS else min(3600.0, 60.0 * 2 ** (count - MAX_FAILS))
            self.fails[("store", peer)] = (count, now + wait)
            with self._mu:
                if self.state == OPEN:
                    self.fails.pop(("store", peer), None)
                    return
                if self.state != LOCKED:
                    raise p.ProtocolError("store_state")
                self.unlock(password=password)
            self.fails.pop(("store", peer), None)

    def lock_now(self) -> bool:
        """강한 잠금: 지금 잠근다(열쇠를 메모리에서 버린다). 받는 중이면 끝날 때까지 미룬다(False)."""
        with self._mu:
            if not self.strong or self.state != OPEN:
                return False
            if self.uploads:
                return False
            self._close()
            self.state = LOCKED
            return True

    def set_strong(self, on: bool) -> None:
        """강한 잠금 켜기·끄기. 켜면 자동 열기 사본을 지운다(지금 열린 것은 그대로). 끄려면 열려 있어야 한다."""
        with self._mu:
            if on:
                self.strong = True
                if self.id:
                    self._key_path(self.id).unlink(missing_ok=True)
                return
            if self.state != OPEN:
                raise p.ProtocolError("store_locked")
            self.strong = False
            self._save_local(keys.from_bytes(self._main_keyset))

    def _check_store_secret(self, password: str | None, recovery: str | None):
        info = self._read_info()
        if info is None:
            raise p.ProtocolError("store_state")
        handle = None
        if isinstance(password, str) and password:
            handle = keys.open_secret(info.get("password") or {}, password, self._ad("password"))
        elif isinstance(recovery, str) and recovery:
            handle = keys.open_secret(info.get("recovery") or {}, keys.normalize_code(recovery), self._ad("recovery"))
        if handle is None:
            raise p.ProtocolError("store_password")
        return handle

    def change_password(self, new_password: str, password: str | None = None, recovery: str | None = None) -> None:
        if not keys.password_ok(new_password):
            raise p.ProtocolError("store_password")
        with self._mu:
            handle = self._check_store_secret(password, recovery)
            info = self._read_info()
            info["password"] = keys.seal_secret(handle, new_password, self._ad("password"))
            tmp = self.info_path.with_suffix(".tmp")
            tmp.write_text(json.dumps(info), "utf-8")
            os.replace(tmp, self.info_path)

    def reset(self) -> None:
        """비밀번호를 잊었을 때: 이 저장소를 통째로 지우고(내용은 되찾을 수 없다) 새로 만들 수 있게 한다.
        .tailhop-store 안에서 이 저장소가 만든 이름의 파일만 지운다(링크는 따라가지 않는다). 다른 파일은 둔다."""
        with self._mu:
            sid = self.id or (self._read_info() or {}).get("id", "")
            for up in list(self.uploads.values()):
                up.writer.close()
            self.uploads.clear()
            self._close()
            _remove_store_files(self.base)
            if sid:
                self._key_path(sid).unlink(missing_ok=True)
            self.id = ""
            self.refresh()

    # ---------- 목록(SQLite) ----------
    def _open(self, handle) -> None:
        if _is_link(self.base):
            raise p.ProtocolError("storage")
        db = sqlite3.connect(self.base / "meta.db", check_same_thread=False, isolation_level=None)
        db.execute("PRAGMA trusted_schema=OFF")
        if db.execute("SELECT count(*) FROM sqlite_master WHERE type IN ('trigger', 'view')").fetchone()[0]:
            db.close()
            raise p.ProtocolError("store_state")  # 누가 끼워 넣은 트리거·뷰: 열지 않는다
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA secure_delete=ON")  # 지운·바꾼 줄의 예전 암호문을 0으로 덮는다
        db.executescript("""
            CREATE TABLE IF NOT EXISTS nodes(id TEXT PRIMARY KEY, parent TEXT NOT NULL, ring TEXT NOT NULL,
                                             meta BLOB NOT NULL);
            CREATE INDEX IF NOT EXISTS nodes_parent ON nodes(parent);
            CREATE TABLE IF NOT EXISTS objects(oid TEXT PRIMARY KEY, node TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS objects_node ON objects(node);
            CREATE TABLE IF NOT EXISTS rings(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS partials(oid TEXT PRIMARY KEY, ring TEXT NOT NULL, lookup TEXT NOT NULL,
                                                done INTEGER NOT NULL, sealed BLOB NOT NULL, created REAL NOT NULL);
            CREATE INDEX IF NOT EXISTS partials_lookup ON partials(lookup);
            CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT NOT NULL);
        """)
        row = db.execute("SELECT v FROM kv WHERE k='store'").fetchone()
        if row is None:
            db.execute("INSERT INTO kv VALUES('store', ?)", (self.id,))
        elif row[0] != self.id:
            db.close()
            raise p.ProtocolError("store_state")  # 다른 저장소의 meta.db
        self.db = db
        self._main_keyset = keys.to_bytes(handle)  # 강한 잠금을 끌 때 이 PC 사본을 다시 만들려고(메모리에만)
        self.rings = {MAIN: keys.primitive(handle)}
        self._load_ring(MAIN)
        self.state = OPEN
        self.sync_all = self.secret_get("sync_all") == b"1"
        self._sweep()
        self.swept = self.clock()
        if self.on_open is not None:
            self.on_open()

    def _load_ring(self, ring: str) -> None:
        aead = self.rings[ring]
        for nid, parent, meta in self.db.execute("SELECT id, parent, meta FROM nodes WHERE ring=?", (ring,)):
            try:
                data = json.loads(aead.decrypt(meta, _node_ad(nid, parent, ring)))
            except (keys.tink.TinkError, ValueError):
                continue  # 망가진 줄: 보이지 않게 둔다(다른 줄은 그대로 쓴다)
            if not isinstance(data, dict) or not isinstance(data.get("n"), str):
                continue
            self._cache(Node(nid, parent, ring, data))

    def _unload_ring(self, ring: str) -> None:
        for node in [n for n in self.nodes.values() if n.ring == ring]:
            self._uncache(node)
        self.rings.pop(ring, None)

    def _cache(self, node: Node) -> None:
        self.changes += 1
        self.nodes[node.id] = node
        self.kids.setdefault(node.parent, {})[node.name] = node.id

    def _uncache(self, node: Node) -> None:
        self.changes += 1
        self.nodes.pop(node.id, None)
        siblings = self.kids.get(node.parent)
        if siblings is not None and siblings.get(node.name) == node.id:
            del siblings[node.name]

    def _seal(self, node: Node) -> bytes:
        return self.rings[node.ring].encrypt(json.dumps(node.meta, separators=(",", ":")).encode(),
                                             _node_ad(node.id, node.parent, node.ring))

    def _put_row(self, node: Node) -> None:
        self.changes += 1  # meta만 바뀌어도(미리보기·동기화 표시·이름)
        self.db.execute("INSERT OR REPLACE INTO nodes VALUES(?, ?, ?, ?)",
                        (node.id, node.parent, node.ring, self._seal(node)))

    def _require_open(self) -> None:
        if self.state != OPEN or self.db is None:
            raise p.ProtocolError("cloud_off" if self.state == MISSING else "store_locked")

    # ---------- 정리 ----------
    def _sweep(self) -> None:
        """열 때 한 번: 오래된 휴지통·조각, 목록에 없는 객체 파일을 지운다."""
        now = self.clock()
        for node in [n for n in self.nodes.values() if n.parent == TRASH]:
            if now - node.meta.get("t", now) / 1000 > TRASH_DAYS * 86400:
                self._purge(node.id)
        for oid, created in self.db.execute("SELECT oid, created FROM partials").fetchall():
            if now - created > PARTIAL_DAYS * 86400:
                self._drop_partial(oid)
        known = {row[0] for row in self.db.execute("SELECT oid FROM objects")}
        known |= {row[0] for row in self.db.execute("SELECT oid FROM partials")}
        for oid, path in _store_files(self.base):  # 정해진 깊이·이름만, 링크는 따라가지 않는다
            if oid not in known and oid not in self.uploads:
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass

    # ---------- 경로 ----------
    def _child(self, parent: str, name: str) -> Node | None:
        nid = self.kids.get(parent, {}).get(name)
        return self.nodes.get(nid) if nid else None

    def _walk(self, parts: list[str], header: dict, peer: str) -> Node | None:
        """경로의 항목(맨 위는 None). 잠긴 폴더를 지나가면 그 열쇠(token)를 확인한다."""
        here: Node | None = None
        for i, part in enumerate(parts):
            if here is not None:
                if not here.is_dir:
                    raise p.ProtocolError("not_found")
                if here.vault:
                    self._check_token(here, header, peer, cloud.join_path(parts[:i]))
            child = self._child(here.id if here else "", part)
            if child is None:
                raise p.ProtocolError("not_found")
            here = child
        return here

    def _folder(self, parts: list[str], header: dict, peer: str) -> tuple[str, str]:
        """폴더의 (id, 그 안 줄들의 ring). 잠긴 폴더 자체면 그 열쇠가 있어야 한다."""
        node = self._walk(parts, header, peer)
        if node is None:
            return "", MAIN
        if not node.is_dir:
            raise p.ProtocolError("not_found")
        if node.vault:
            self._check_token(node, header, peer, cloud.join_path(parts))
            return node.id, node.vault
        return node.id, node.ring

    def _free_name(self, parent: str, name: str) -> str:
        taken = {x.casefold() for x in self.kids.get(parent, {})}
        if name.casefold() not in taken:
            return name
        stem, dot, ext = name.rpartition(".")
        if not dot or not stem:
            stem, dot, ext = name, "", ""
        n = 1
        while True:
            candidate = f"{stem} ({n}){dot}{ext}"
            if candidate.casefold() not in taken:
                return candidate
            n += 1

    def _subtree(self, nid: str) -> list[tuple[str, str, str]]:
        """(id, parent, ring) — 그 항목과 모든 아래(열쇠가 없어 못 읽는 줄도 평문 열로 찾는다)."""
        return self.db.execute("""
            WITH RECURSIVE t(id, parent, ring) AS (
                SELECT id, parent, ring FROM nodes WHERE id=?
                UNION ALL SELECT n.id, n.parent, n.ring FROM nodes n JOIN t ON n.parent=t.id)
            SELECT id, parent, ring FROM t""", (nid,)).fetchall()

    # ---------- 잠긴 폴더 열쇠 ----------
    def _check_token(self, vnode: Node, header: dict, peer: str, mount: str) -> None:
        """그 잠긴 폴더의 열쇠가 헤더에 있는지. "token" 하나 또는 "tokens" 목록(§16, 잠긴 폴더 사이로 옮길 때)."""
        extra = header.get("tokens")
        candidates = [header.get("token"), *(extra[:16] if isinstance(extra, list) else [])]
        for token in candidates:
            tok = self.tokens.get(token) if isinstance(token, str) else None
            if tok is not None and tok.ring == vnode.vault and tok.peer == peer and tok.expires >= self.clock() \
                    and vnode.vault in self.rings:
                tok.expires = self.clock() + TOKEN_TTL
                return
        raise p.ProtocolError("vault_locked", mount=mount)

    def _ring_record(self, ring: str) -> dict:
        row = self.db.execute("SELECT sealed FROM rings WHERE id=?", (ring,)).fetchone()
        if row is None:
            raise p.ProtocolError("not_found")
        return json.loads(self.rings[MAIN].decrypt(row[0], self._ad("ring " + ring)))

    def _check_secret(self, ring: str, peer: str, pin: str | None, recovery: str | None):
        """PIN·복구 코드 확인. 잠긴 폴더마다 한 번에 하나씩, 틀린 횟수는 확인 전에 올린다(기기마다 따로 센다)."""
        with self._mu:
            gate = self.gates.setdefault(ring, threading.Lock())
        with gate:
            now = self.clock()
            count, until = self.fails.get((ring, peer), (0, 0.0))
            if now < until:
                raise p.ProtocolError("vault_wait", retry=int(until - now) + 1)
            count += 1
            wait = 0.0 if count < MAX_FAILS else min(3600.0, 60.0 * 2 ** (count - MAX_FAILS))
            self.fails[(ring, peer)] = (count, now + wait)
            with self._mu:
                record = self._ring_record(ring)
            handle = None
            if valid_pin(pin):
                handle = keys.open_secret(record["pin"], pin, self._ad("pin " + ring))
            elif isinstance(recovery, str) and recovery:
                handle = keys.open_secret(record["recovery"], keys.normalize_code(recovery),
                                          self._ad("vault recovery " + ring))
            if handle is None:
                raise p.ProtocolError("vault_pin")
            self.fails.pop((ring, peer), None)
            return handle

    def _forget_ring(self, ring: str) -> None:
        self.tokens = {k: v for k, v in self.tokens.items() if v.ring != ring}
        if ring in self.rings and ring != MAIN:
            self._unload_ring(ring)

    def close_expired(self) -> None:
        with self._mu:
            now = self.clock()
            self.tokens = {k: v for k, v in self.tokens.items() if v.expires >= now}
            alive = {t.ring for t in self.tokens.values()}
            busy = {u.ring for u in self.uploads.values()}
            for ring in [r for r in self.rings if r != MAIN and r not in alive and r not in busy]:
                self._unload_ring(ring)
            if self.db is not None and now - getattr(self, "swept", now) > SWEEP_SEC:
                self.swept = now
                for oid, created in self.db.execute("SELECT oid, created FROM partials").fetchall():
                    if now - created > PARTIAL_DAYS * 86400 and oid not in self.uploads:
                        self._drop_partial(oid)

    # ---------- 받는 중인 파일 ----------
    def _seal_partial(self, ring: str, oid: str, data: dict) -> bytes:
        return self.rings[ring].encrypt(json.dumps(data).encode(), self._ad("partial " + oid))

    def _content_ring(self, folder: str) -> str:
        """그 폴더 안 줄들이 지금 쓰는 열쇠. 폴더가 없으면 not_found, 잠긴 폴더인데 닫혀 있으면 vault_locked."""
        if folder == "":
            return MAIN
        node = self.nodes.get(folder)
        if node is None or not node.is_dir or node.parent == TRASH:
            raise p.ProtocolError("not_found")
        ring = node.vault or node.ring
        if ring not in self.rings:
            raise p.ProtocolError("vault_locked")
        return ring

    def begin_upload(self, folder: str, ring: str, name: str, size: int, mtime: int, peer: str,
                     xfer: str) -> Upload:
        with self._mu:
            self._require_open()
            if self._content_ring(folder) != ring:
                raise p.ProtocolError("vault_busy")  # 받을 자리를 정한 사이 그 폴더가 잠기거나 풀렸다
            oid, key = _hex(), keys.to_bytes(keys.new_keyset())
            sealed = self._seal_partial(ring, oid, {"parent": folder, "name": name, "mtime": mtime,
                                                    "key": keys.b64(key), "peer": peer, "size": size})
            path = self._part_path(oid)
            self.db.execute("INSERT INTO partials VALUES(?, ?, ?, 0, ?, ?)",
                            (oid, ring, _lookup(peer, xfer, size), sealed, self.clock()))
            up = Upload(self, oid, ring, folder, name, mtime, size, peer, xfer, ObjectWriter(path, key))
            self.uploads[oid] = up
            return up

    def partial_have(self, peer: str, xfer: str, size: int) -> int:
        """hello의 have: 이 기기·전송의 조각이 있으면 다 쓴 청크까지의 크기(청크 단위)."""
        with self._mu:
            if self.db is None:
                return 0
            row = self.db.execute("SELECT done, oid FROM partials WHERE lookup=?",
                                  (_lookup(peer, xfer, size),)).fetchone()
            if row is None or row[1] in self.uploads:
                return 0
            return min(row[0] * CHUNK, size)

    def resume_upload(self, peer: str, xfer: str, size: int, offset: int, folder: str, ring: str) -> Upload:
        """offset부터 이어 받는다. 조각이 없거나 위치·폴더가 다르면 resume_lost."""
        with self._mu:
            self._require_open()
            row = self.db.execute("SELECT oid, done, sealed FROM partials WHERE lookup=? AND ring=?",
                                  (_lookup(peer, xfer, size), ring)).fetchone()
            if row is None or row[0] in self.uploads or offset != min(row[1] * CHUNK, size) or ring not in self.rings:
                raise p.ProtocolError("resume_lost")
            oid, done, sealed = row
            try:
                info = json.loads(self.rings[ring].decrypt(sealed, self._ad("partial " + oid)))
            except (keys.tink.TinkError, ValueError) as exc:
                raise p.ProtocolError("resume_lost") from exc
            if info["parent"] != folder or info.get("peer") != peer or info.get("size") != size \
                    or self._content_ring(folder) != ring:
                raise p.ProtocolError("resume_lost")
            key = keys.unb64(info["key"])
            path = self._part_path(oid)
            if not path.exists() or path.stat().st_size < done * CT_CHUNK:
                raise p.ProtocolError("resume_lost")
            up = Upload(self, oid, ring, folder, info["name"], info["mtime"], size, peer, xfer,
                        ObjectWriter(path, key, done))
            self.uploads[oid] = up
        for block in iter_plain(path, key, done * CHUNK, partial=True):  # 앞부분의 SHA-256을 다시 센다
            up.digest.update(block)
        return up

    def _partial_done(self, oid: str, done: int) -> None:
        with self._mu:
            if self.db is not None:
                self.db.execute("UPDATE partials SET done=? WHERE oid=?", (done, oid))

    def _drop_partial(self, oid: str) -> None:
        with self._mu:
            if self.db is not None:
                self.db.execute("DELETE FROM partials WHERE oid=?", (oid,))
        try:
            self._part_path(oid).unlink(missing_ok=True)
        except (OSError, p.ProtocolError):
            pass  # 이상한 이름(고친 평문 열)이거나 사용 중: 다음 정리 때

    def _writer_done(self, up: Upload) -> None:
        with self._mu:
            self.uploads.pop(up.oid, None)

    def _object_path(self, oid: str) -> Path:
        if not OID_RE.fullmatch(oid):
            raise p.ProtocolError("storage")  # 평문 열을 고쳐 다른 곳을 가리키게 해도 그 파일을 열거나 지우지 않는다
        folder = self._inner("objects") / oid[:2]
        if folder.exists() and _is_link(folder):
            raise p.ProtocolError("storage")
        return folder / oid

    def _commit_upload(self, up: Upload, unique: bool) -> str:
        with self._mu:
            self._require_open()
            try:
                now_ring = self._content_ring(up.parent)
            except p.ProtocolError:
                now_ring = None
            if now_ring != up.ring:  # 그 사이 폴더가 지워지거나, 잠기거나 풀렸거나, 잠긴 폴더가 닫혔다
                self._drop_partial(up.oid)
                raise p.ProtocolError("vault_busy" if now_ring else "not_found")
            name = self._free_name(up.parent, up.name) if unique else up.name
            if not unique and name.casefold() in {x.casefold() for x in self.kids.get(up.parent, {})}:
                raise p.ProtocolError("exists")
            dest = self._object_path(up.oid)
            dest.parent.mkdir(parents=True, exist_ok=True)
            os.replace(up.writer.path, dest)
            node = Node(_hex(), up.parent, up.ring, {"n": name, "d": False, "s": up.size, "m": up.mtime,
                                                    "o": up.oid, "k": keys.b64(up.writer.key)})
            with self.db:
                self.db.execute("BEGIN")
                self._put_row(node)
                self.db.execute("INSERT INTO objects VALUES(?, ?)", (up.oid, node.id))
                self.db.execute("DELETE FROM partials WHERE oid=?", (up.oid,))
            self._cache(node)
        if self.on_media is not None and cloud.file_kind(name) in ("image", "video"):
            self.on_media(node.id)
        return name

    # ---------- 암호화한 작은 값 ----------
    def secret_get(self, name: str) -> bytes | None:
        """저장소 열쇠로 잠가 둔 작은 값(옮기기에서 넣은 파일 표시 등). 이름도 평문 열에 두지 않는다."""
        with self._mu:
            self._require_open()
            key = "s:" + hashlib.sha256(f"{self.id}|{name}".encode()).hexdigest()
            row = self.db.execute("SELECT v FROM kv WHERE k=?", (key,)).fetchone()
            if row is None:
                return None
            try:
                return self.rings[MAIN].decrypt(keys.unb64(row[0]), self._ad("kv " + name))
            except (keys.tink.TinkError, ValueError):
                return None

    def secret_put(self, name: str, data: bytes) -> None:
        with self._mu:
            self._require_open()
            key = "s:" + hashlib.sha256(f"{self.id}|{name}".encode()).hexdigest()
            self.db.execute("INSERT OR REPLACE INTO kv VALUES(?, ?)",
                            (key, keys.b64(self.rings[MAIN].encrypt(data, self._ad("kv " + name)))))

    # ---------- 넣기(이 PC에서) ----------
    def import_file(self, folder: str, ring: str, src: Path, name: str | None = None) -> str:
        """이 PC의 파일 하나를 암호화해 넣는다. 저장된 이름."""
        st = src.stat()
        up = self.begin_upload(folder, ring, p.cloud_name(p.sanitize_filename(name or src.name)), st.st_size,
                               st.st_mtime_ns // 1_000_000, "local", _hex())
        try:
            with open(src, "rb") as f:
                for block in iter(lambda: f.read(CHUNK), b""):
                    up.write(block)
        except BaseException:
            up.abort()
            raise
        return up.commit(unique=True)

    def put_bytes_variant(self, nid: str, role: str, data: bytes) -> None:
        """미리보기·화면용·재생용 등 딸린 파일을 넣는다(그 항목의 meta에 열쇠를 함께 적는다)."""
        oid, key = _hex(), keys.to_bytes(keys.new_keyset())
        w = ObjectWriter(self._part_path(oid), key)
        w.write(data)
        size = w.finish()
        self.attach_variant(nid, role, oid, key, size, w.path)

    def attach_variant(self, nid: str, role: str, oid: str, key: bytes, size: int, path: Path) -> None:
        with self._mu:
            node = self.nodes.get(nid)
            if node is None or self.db is None:
                path.unlink(missing_ok=True)
                return
            dest = self._object_path(oid)
            dest.parent.mkdir(parents=True, exist_ok=True)
            os.replace(path, dest)
            old = node.variant(role)
            node.meta.setdefault("v", {})[role] = {"o": oid, "k": keys.b64(key), "s": size}
            with self.db:
                self.db.execute("BEGIN")
                self._put_row(node)
                self.db.execute("INSERT INTO objects VALUES(?, ?)", (oid, nid))
                if old:
                    self.db.execute("DELETE FROM objects WHERE oid=?", (old["o"],))
            if old:
                self._object_path(old["o"]).unlink(missing_ok=True)

    def new_variant_writer(self) -> tuple[str, bytes, ObjectWriter]:
        """크기를 모르는 딸린 파일(영상 변환 출력)을 흘려 쓰는 자리."""
        oid, key = _hex(), keys.to_bytes(keys.new_keyset())
        return oid, key, ObjectWriter(self._part_path(oid), key)

    # ---------- 읽기 ----------
    def node_at(self, parts: list[str], header: dict, peer: str) -> Node:
        with self._mu:
            self._require_open()
            node = self._walk(parts, header, peer)
            if node is None:
                raise p.ProtocolError("bad_path")
            return node

    def reader(self, node: Node, role: str | None = None) -> ObjectReader:
        ref = node.variant(role) if role else {"o": node.meta["o"], "k": node.meta["k"], "s": node.size}
        if ref is None:
            raise p.ProtocolError("not_found")
        return ObjectReader(self._object_path(ref["o"]), keys.unb64(ref["k"]), int(ref["s"]))

    def read(self, node: Node, offset: int = 0, length: int | None = None,
             role: str | None = None) -> Iterator[bytes]:
        ref = node.variant(role) if role else {"o": node.meta.get("o"), "k": node.meta.get("k"), "s": node.size}
        if ref is None or not ref.get("o"):
            raise p.ProtocolError("not_found")
        return iter_plain(self._object_path(ref["o"]), keys.unb64(ref["k"]), int(ref["s"]), offset, length)

    def read_all(self, node: Node, limit: int, role: str | None = None) -> bytes:
        size = (node.variant(role) or {}).get("s", 0) if role else node.size
        if size > limit:
            raise p.ProtocolError("too_large")
        return b"".join(self.read(node, role=role))

    # ---------- 목록 ----------
    def item_json(self, node: Node, **extra) -> dict:
        if node.vault:
            return {"name": node.name, "dir": True, "size": 0, "mtime": node.mtime, "locked": True,
                    "state": "locked", "progress": 0, **extra}
        out = cloud.item_json(cloud.Item(node.name, node.is_dir, 0 if node.is_dir else node.size, node.mtime),
                              **extra)
        roles = sorted((node.meta.get("v") or {}).keys())
        if roles:
            out["variants"] = roles
        if self.sync_on(node):
            out["sync"] = True
        return out

    # ---------- 모든 기기 동기화(§19.2) ----------
    def sync_on(self, node: Node) -> bool:
        """이 항목이 동기화 대상인가: 자기 또는 가장 가까운 위 폴더의 표시(y), 없으면 저장소 전체 설정.
        잠긴 폴더와 그 안, 휴지통은 늘 아니다."""
        here = node
        while True:
            if here.ring != MAIN or here.vault:
                return False
            mark = here.meta.get("y")
            if mark in (0, 1) and not isinstance(mark, bool):
                return mark == 1
            if here.parent == "":
                return self.sync_all
            parent = self.nodes.get(here.parent)
            if parent is None:  # 휴지통(TRASH) 또는 읽을 수 없는 줄
                return False
            here = parent

    def _inherited(self, node: Node) -> bool:
        parent = self.nodes.get(node.parent)
        if node.parent == "":
            return self.sync_all
        return parent is not None and self.sync_on(parent)

    def set_sync_all(self, on: bool) -> None:
        with self._mu:
            self._require_open()
            self.secret_put("sync_all", b"1" if on else b"0")
            self.sync_all = bool(on)
            self.changes += 1

    def sync_set(self, paths: list[list[str]], on: bool, header: dict, peer: str) -> tuple[int, list[dict]]:
        """고른 항목을 동기화 대상에 넣거나 뺀다. 폴더면 그 아래의 따로 단 표시는 지운다(폴더를 따르게).
        (바뀐 수, 안 된 것[{"path","code"}])."""
        changed, failed = 0, []
        with self._mu:
            self._require_open()
            for parts in paths[:SYNC_SET_MAX]:
                path = cloud.join_path(parts)
                try:
                    node = self._walk(parts, header, peer)
                except p.ProtocolError as exc:
                    failed.append({"path": path, "code": exc.code})
                    continue
                if node is None:
                    failed.append({"path": path, "code": "bad_path"})
                    continue
                if not self._syncable(node):
                    failed.append({"path": path, "code": "sync_vault"})
                    continue
                before = self.sync_on(node)
                touched = []
                if self._inherited(node) == on:
                    if node.meta.pop("y", None) is not None:
                        touched.append(node)
                elif node.meta.get("y") != (1 if on else 0):
                    node.meta["y"] = 1 if on else 0
                    touched.append(node)
                if node.is_dir:
                    for nid, _parent, ring in self._subtree(node.id):
                        kid = self.nodes.get(nid)
                        if nid != node.id and ring == MAIN and kid is not None and kid.meta.pop("y", None) is not None:
                            touched.append(kid)
                if touched:
                    with self.db:
                        self.db.execute("BEGIN")
                        for item in touched:
                            self._put_row(item)
                if touched or before != self.sync_on(node):
                    changed += 1
        return changed, failed

    def _syncable(self, node: Node) -> bool:
        here: Node | None = node
        while here is not None:
            if here.ring != MAIN or here.vault:
                return False
            if here.parent == "":
                return True
            here = self.nodes.get(here.parent)
        return False  # 휴지통 안

    def sync_manifest(self) -> tuple[str, list[dict]]:
        """동기화 대상 전체 목록(경로 순서)과 그 요약값. 대상이 없으면 ("", []). 바뀌지 않았으면 만든 것을 다시 쓴다."""
        with self._mu:
            self._require_open()
            if self._manifest is not None and self._manifest[0] == self.changes:
                return self._manifest[1], self._manifest[2]
            items: list[dict] = []
            stack = [("", "", self.sync_all)]
            while stack:
                folder, prefix, inherited = stack.pop()
                for name, nid in self.kids.get(folder, {}).items():
                    node = self.nodes[nid]
                    if node.ring != MAIN or node.vault:
                        continue
                    mark = node.meta.get("y")
                    on = mark == 1 if mark in (0, 1) and not isinstance(mark, bool) else inherited
                    path = prefix + name
                    if node.is_dir:
                        if on:
                            items.append({"id": node.id, "path": path, "dir": True})
                        stack.append((nid, path + "/", on))
                        continue
                    if not on or not node.meta.get("o"):
                        continue
                    entry = {"id": node.id, "path": path, "dir": False, "size": node.size, "mtime": node.mtime,
                             "ver": node.meta["o"]}
                    thumb = node.variant("thumb")
                    if thumb:
                        entry["thumb"], entry["tver"] = int(thumb["s"]), thumb["o"]
                    items.append(entry)
            items.sort(key=lambda x: x["path"])
            digest = hashlib.sha256(json.dumps(items, separators=(",", ":"), ensure_ascii=False).encode()) \
                .hexdigest()[:16] if items else ""
            self._manifest = (self.changes, digest, items)
            return digest, items

    def list_children(self, parts: list[str], header: dict, peer: str) -> list[dict]:
        with self._mu:
            self._require_open()
            folder, _ = self._folder(parts, header, peer)
            items = [self.item_json(self.nodes[nid]) for nid in self.kids.get(folder, {}).values()]
        items.sort(key=lambda x: (not x["dir"], x["name"].casefold(), x["name"]))
        return items

    def search(self, parts: list[str], query: str, kind: str, header: dict, peer: str) -> tuple[list[dict], bool]:
        with self._mu:
            self._require_open()
            start, _ = self._folder(parts, header, peer)
            found, scanned, partial = [], 0, False
            end = time.monotonic() + SEARCH_SECONDS
            stack = [(start, "")]
            while stack:
                folder, rel = stack.pop()
                for nid in list(self.kids.get(folder, {}).values()):
                    scanned += 1
                    if scanned > SEARCH_SCAN or time.monotonic() > end:
                        partial = True
                        stack.clear()
                        break
                    node = self.nodes[nid]
                    sub = f"{rel}/{node.name}" if rel else node.name
                    if cloud.matches(node.name, node.is_dir and not node.vault, query, kind) and not node.vault:
                        found.append(self.item_json(node, path=sub))
                    if node.is_dir and not node.vault:  # 잠긴 폴더 안은 그 안에서 찾을 때만
                        stack.append((nid, sub))
        found.sort(key=lambda x: (x["path"].casefold(), x["path"]))
        return found, partial

    # ---------- 바꾸기 ----------
    def mkdir(self, parts: list[str], header: dict, peer: str) -> str:
        """없는 폴더는 모두 만든다. 마지막 폴더의 id."""
        with self._mu:
            self._require_open()
            folder, ring = "", MAIN
            for i, part in enumerate(parts):
                child = self._child(folder, part)
                if child is None:
                    if part.casefold() in {x.casefold() for x in self.kids.get(folder, {})}:
                        raise p.ProtocolError("exists")
                    child = Node(_hex(), folder, ring, {"n": part, "d": True, "s": 0,
                                                        "m": int(self.clock() * 1000)})
                    self._put_row(child)
                    self._cache(child)
                elif not child.is_dir:
                    raise p.ProtocolError("bad_path")
                if child.vault:
                    self._check_token(child, header, peer, cloud.join_path(parts[:i + 1]))
                    folder, ring = child.id, child.vault
                else:
                    folder, ring = child.id, child.ring
            return folder

    def folder_for_put(self, parts: list[str], header: dict, peer: str) -> tuple[str, str]:
        with self._mu:
            self.mkdir(parts, header, peer)
            return self._folder(parts, header, peer)

    def delete(self, parts: list[str], header: dict, peer: str) -> None:
        """잠긴 폴더 밖은 휴지통으로, 잠긴 폴더 안은 영구히. 잠긴 폴더 자체는 그 열쇠가 있어야 한다."""
        if not parts:
            raise p.ProtocolError("bad_path")
        with self._mu:
            self._require_open()
            node = self._walk(parts, header, peer)
            if node.vault:
                self._check_token(node, header, peer, cloud.join_path(parts))
            if node.ring != MAIN:
                self._purge(node.id)
                return
            if not node.vault and any(r[2] != MAIN or (self.nodes.get(r[0]) and self.nodes[r[0]].vault)
                                      for r in self._subtree(node.id)[1:]):
                raise p.ProtocolError("bad_path")  # 잠긴 폴더를 품은 폴더는 지우지 않는다(그 열쇠 없이 함께 사라지지 않게)
            self._uncache(node)
            node.meta["t"] = int(self.clock() * 1000)
            node.meta["from"] = node.parent
            node.parent = TRASH
            node.meta["n"] = self._free_name(TRASH, node.name)
            self._put_row(node)
            self._cache(node)
            if node.vault:
                self._forget_ring(node.vault)

    def _purge(self, nid: str) -> None:
        """그 항목과 아래 모두를 영구히 지운다(줄·객체·잠긴 폴더 기록)."""
        rows = self._subtree(nid)
        ids = [r[0] for r in rows]
        oids: list[str] = []
        rings = set()
        for i in range(0, len(ids), 500):
            chunk = ids[i:i + 500]
            marks = ",".join("?" * len(chunk))
            oids += [r[0] for r in self.db.execute(f"SELECT oid FROM objects WHERE node IN ({marks})", chunk)]
            rings |= {r[2] for r in rows[i:i + 500]}
        for nid_ in ids:
            node = self.nodes.get(nid_)
            if node is not None:
                self._uncache(node)
                if node.vault:
                    rings.add(node.vault)
        with self.db:
            self.db.execute("BEGIN")
            for i in range(0, len(ids), 500):
                chunk = ids[i:i + 500]
                marks = ",".join("?" * len(chunk))
                self.db.execute(f"DELETE FROM nodes WHERE id IN ({marks})", chunk)
                self.db.execute(f"DELETE FROM objects WHERE node IN ({marks})", chunk)
            for ring in rings - {MAIN}:
                if not self.db.execute("SELECT 1 FROM nodes WHERE ring=? LIMIT 1", (ring,)).fetchone():
                    self.db.execute("DELETE FROM rings WHERE id=?", (ring,))
        for ring in rings - {MAIN}:
            self._forget_ring(ring)
        self._checkpoint()  # 지운 줄(파일 열쇠가 든 meta)의 예전 판이 WAL에 남지 않게: 열쇠가 사라지면 암호문만 남아도 못 연다
        for oid in oids:
            try:
                self._object_path(oid).unlink(missing_ok=True)
            except (OSError, p.ProtocolError):
                pass  # 읽는 중(사용 중)이거나 이상한 이름: 줄은 지웠으니 다음에 열 때 정리한다

    def trash_items(self) -> list[dict]:
        with self._mu:
            self._require_open()
            return [self.item_json(n, id=n.id, deleted=n.meta.get("t", 0))
                    for n in self.nodes.values() if n.parent == TRASH]

    def restore(self, nid: str) -> str:
        """휴지통에서 되돌린다(원래 폴더가 없으면 맨 위로). 되돌린 이름."""
        with self._mu:
            self._require_open()
            node = self.nodes.get(nid)
            if node is None or node.parent != TRASH:
                raise p.ProtocolError("not_found")
            target = node.meta.pop("from", "")
            node.meta.pop("t", None)
            try:
                target_ring = self._content_ring(target)
            except p.ProtocolError:
                target, target_ring = "", MAIN
            if target_ring != node.ring or (node.vault and target_ring != MAIN):
                target = ""  # 지운 뒤 그 폴더가 잠겼다(또는 풀렸다): 맨 위로 되돌린다(잠긴 폴더 안에 열쇠가 다른 줄을 두지 않게)
            self._uncache(node)
            node.parent = target
            node.meta["n"] = self._free_name(target, node.name)
            self._put_row(node)
            self._cache(node)
            return node.name

    def purge_trashed(self, nid: str) -> None:
        """휴지통의 항목 하나를 영구히 지운다."""
        with self._mu:
            self._require_open()
            node = self.nodes.get(nid)
            if node is None or node.parent != TRASH:
                raise p.ProtocolError("not_found")
            self._purge(nid)

    def empty_trash(self) -> None:
        with self._mu:
            self._require_open()
            for node in [n for n in self.nodes.values() if n.parent == TRASH]:
                self._purge(node.id)

    def move(self, paths: list[list[str]], dest: list[str], header: dict, peer: str) -> tuple[list[dict], list[dict]]:
        """§16: 옮긴다. 잠긴 폴더 안팎·잠긴 폴더 사이로도 된다(2.2.1): 그 항목과 아래 줄의 meta만 도착 자리의 열쇠로
        다시 잠그고 파일 내용은 그대로라 바로 끝난다. 출발·도착 잠긴 폴더의 열쇠가 모두 있어야 한다("tokens").
        잠긴 폴더를 다른 잠긴 폴더 안에 넣지는 않는다(겹침)."""
        with self._mu:
            self._require_open()
            dest_id, dest_ring = self._folder(dest, header, peer)
            dest_path = cloud.join_path(dest)
            moved, failed = [], []
            crossed = False
            for parts in paths:
                path = cloud.join_path(parts)
                try:
                    if path == dest_path or dest_path.startswith(path + "/"):
                        raise p.ProtocolError("bad_move")
                    node = self._walk(parts, header, peer)
                    if node.parent == dest_id:
                        moved.append({"path": path, "name": node.name})
                        continue
                    if node.ring != dest_ring:
                        self._move_across(node, dest_id, dest_ring)
                        crossed = True
                    else:
                        self._uncache(node)
                        node.parent = dest_id
                        node.meta["n"] = self._free_name(dest_id, node.name)
                        self._put_row(node)
                        self._cache(node)
                    moved.append({"path": path, "name": node.name})
                except p.ProtocolError as exc:
                    failed.append({"path": path, "code": exc.code})
                except (KeyError, keys.tink.TinkError):
                    failed.append({"path": path, "code": "storage"})
            if crossed:
                self._checkpoint()  # 예전 열쇠로 잠긴 meta가 WAL에 남지 않게
            return moved, failed

    def _move_across(self, node: Node, dest_id: str, dest_ring: str) -> None:
        """열쇠가 다른 자리로 옮긴다(한 트랜잭션). 그 아래에 잠긴 폴더가 있으면 잠긴 폴더 안으로는 못 간다."""
        sub = self._subtree(node.id)
        inside = {r[0] for r in sub}
        if node.vault or (dest_ring != MAIN and any(self.nodes.get(r[0]) and self.nodes[r[0]].vault for r in sub)):
            raise p.ProtocolError("vault_move")  # 잠긴 폴더 겹침
        if any(r[2] not in self.rings for r in sub):
            raise p.ProtocolError("vault_locked")
        if any(u.parent in inside for u in self.uploads.values()):
            raise p.ProtocolError("vault_busy")
        old = (node.parent, node.ring, node.name)
        self._uncache(node)
        node.parent, node.ring = dest_id, dest_ring
        node.meta["n"] = self._free_name(dest_id, node.name)
        try:
            with self.db:
                self.db.execute("BEGIN")
                self._put_row(node)
                self._rewrap(node.id, dest_ring)
        except BaseException:
            node.parent, node.ring, node.meta["n"] = old
            self._cache(node)
            raise
        self._cache(node)

    def rename(self, items: list[tuple[list[str], str]], header: dict, peer: str) -> tuple[list[dict], list[dict]]:
        """§18.2: 항목마다 새 이름으로. 같은 폴더에 대소문자까지 무시해 같은 이름이 있으면 exists(번호를 붙이지 않는다)."""
        with self._mu:
            self._require_open()
            renamed, failed = [], []
            for parts, name in items:
                path = cloud.join_path(parts)
                try:
                    try:
                        if cloud.parse_path(name) != [name]:
                            raise p.ProtocolError("bad_name")
                    except p.ProtocolError as exc:
                        raise p.ProtocolError("bad_name") from exc
                    node = self._walk(parts, header, peer)
                    if node is None:
                        raise p.ProtocolError("bad_path")
                    if name != node.name:
                        taken = {x.casefold() for x in self.kids.get(node.parent, {}) if x != node.name}
                        if name.casefold() in taken:
                            raise p.ProtocolError("exists")
                        self._uncache(node)
                        node.meta["n"] = name
                        self._put_row(node)
                        self._cache(node)
                    renamed.append({"path": path, "name": name})
                except p.ProtocolError as exc:
                    failed.append({"path": path, "code": exc.code})
            return renamed, failed

    # ---------- 잠그기(PIN) ----------
    def _rewrap(self, root_id: str, ring: str) -> None:
        """root_id 아래 모든 줄의 meta를 ring 열쇠로 다시 암호화한다. 부르는 쪽의 트랜잭션 안에서 부른다.
        캐시가 아니라 DB의 줄을 그 줄의 열쇠로 풀어서 다시 쓴다(캐시에 없는 줄도 빠뜨리지 않게). 내용(객체)은 그대로다."""
        for nid, parent, old_ring in self._subtree(root_id):
            if nid == root_id:
                continue
            meta = self.db.execute("SELECT meta FROM nodes WHERE id=?", (nid,)).fetchone()[0]
            data = json.loads(self.rings[old_ring].decrypt(meta, _node_ad(nid, parent, old_ring)))
            node = Node(nid, parent, ring, data)
            self._put_row(node)
            cached = self.nodes.get(nid)
            if cached is not None:
                cached.ring = ring

    def _checkpoint(self) -> None:
        """예전 암호문이 WAL에 남지 않게 비운다. 읽는 중이라 못 하면 다음 기회에(데이터는 이미 커밋됐다)."""
        try:
            self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        except sqlite3.OperationalError:
            pass

    def lock(self, parts: list[str], pin: object) -> str:
        """폴더를 잠근다(바로 끝난다). 복구 코드를 돌려준다(한 번만)."""
        if not valid_pin(pin):
            raise p.ProtocolError("vault_pin")
        with self._mu:
            self._require_open()
            node = self._walk(parts, {}, "")
            if node is None or not node.is_dir or node.vault or node.ring != MAIN:
                raise p.ProtocolError("bad_path")
            sub = self._subtree(node.id)
            if any(r[2] != MAIN for r in sub) or any(self.nodes.get(r[0]) and self.nodes[r[0]].vault for r in sub):
                raise p.ProtocolError("bad_path")  # 안에 잠긴 폴더가 있다(겹쳐 잠그지 않는다)
            inside = {r[0] for r in sub}
            if any(u.parent in inside for u in self.uploads.values()):
                raise p.ProtocolError("vault_busy")  # 그 안으로 받는 중
            for oid, sealed in self.db.execute("SELECT oid, sealed FROM partials WHERE ring=?", (MAIN,)).fetchall():
                try:
                    if json.loads(self.rings[MAIN].decrypt(sealed, self._ad("partial " + oid)))["parent"] in inside:
                        self._drop_partial(oid)  # 그 안으로 받다 만 조각(저장소 열쇠로 잠겨 있다)은 버린다
                except (keys.tink.TinkError, ValueError, KeyError):
                    continue
            ring = _hex()
            handle = keys.new_keyset()
            code = keys.new_recovery_code()
            record = {"pin": keys.seal_secret(handle, pin, self._ad("pin " + ring)),
                      "recovery": keys.seal_secret(handle, code, self._ad("vault recovery " + ring))}
            self.rings[ring] = keys.primitive(handle)
            node.meta["vault"] = ring
            try:
                with self.db:  # 기록·안의 줄·폴더 줄을 한꺼번에(중간에 꺼져도 반쯤 잠긴 폴더가 남지 않게)
                    self.db.execute("BEGIN")
                    self.db.execute("INSERT INTO rings VALUES(?, ?)", (ring, self.rings[MAIN].encrypt(
                        json.dumps(record).encode(), self._ad("ring " + ring))))
                    self._rewrap(node.id, ring)
                    self._put_row(node)
            except BaseException:
                del node.meta["vault"]
                self._uncache(node)
                self._load_ring(MAIN)
                self.rings.pop(ring, None)
                raise
            self._checkpoint()
            self._unload_ring(ring)  # 열려면 PIN이 필요하다
            return code

    def open_vault(self, parts: list[str], peer: str, pin: str | None, recovery: str | None) -> str:
        with self._mu:
            self._require_open()
            node = self._walk(parts, {}, "") if parts else None
            if node is None or not node.vault:
                raise p.ProtocolError("not_found")
            ring = node.vault
        handle = self._check_secret(ring, peer, pin, recovery)
        with self._mu:
            if ring not in self.rings:
                self.rings[ring] = keys.primitive(handle)
                self._load_ring(ring)
            token = os.urandom(16).hex()
            self.tokens[token] = Token(ring, peer, self.clock() + TOKEN_TTL)
        if self.on_open is not None:
            self.on_open()
        return token

    def unlock_vault(self, parts: list[str], pin: str, peer: str) -> None:
        """잠금을 푼다(바로 끝난다). 그 안으로 받는 중이면 vault_busy."""
        with self._mu:
            self._require_open()
            node = self._walk(parts, {}, "") if parts else None
            if node is None or not node.vault:
                raise p.ProtocolError("not_found")
            ring = node.vault
            if any(u.ring == ring for u in self.uploads.values()):
                raise p.ProtocolError("vault_busy")
        handle = self._check_secret(ring, peer, pin, None)
        with self._mu:
            if ring not in self.rings:
                self.rings[ring] = keys.primitive(handle)
                self._load_ring(ring)
            if any(u.ring == ring for u in self.uploads.values()):
                raise p.ProtocolError("vault_busy")
            gone = [row[0] for row in self.db.execute("SELECT oid FROM partials WHERE ring=?", (ring,))]
            del node.meta["vault"]
            try:
                with self.db:
                    self.db.execute("BEGIN")
                    self._rewrap(node.id, MAIN)
                    self._put_row(node)
                    self.db.execute("DELETE FROM partials WHERE ring=?", (ring,))
                    self.db.execute("DELETE FROM rings WHERE id=?", (ring,))
            except BaseException:
                node.meta["vault"] = ring
                raise
            for oid in gone:
                self._drop_partial(oid)
            self._checkpoint()
            self.tokens = {k: v for k, v in self.tokens.items() if v.ring != ring}
            self.rings.pop(ring, None)

    def repin(self, parts: list[str], new_pin: str, pin: str | None, recovery: str | None, peer: str) -> None:
        if not valid_pin(new_pin):
            raise p.ProtocolError("vault_pin")
        with self._mu:
            self._require_open()
            node = self._walk(parts, {}, "") if parts else None
            if node is None or not node.vault:
                raise p.ProtocolError("not_found")
            ring = node.vault
        handle = self._check_secret(ring, peer, pin, recovery)
        with self._mu:
            record = self._ring_record(ring)
            record["pin"] = keys.seal_secret(handle, new_pin, self._ad("pin " + ring))
            self.db.execute("UPDATE rings SET sealed=? WHERE id=?",
                            (self.rings[MAIN].encrypt(json.dumps(record).encode(), self._ad("ring " + ring)), ring))
            self._checkpoint()
            self._forget_ring(ring)  # 예전 PIN으로 연 열쇠도 끝낸다

    def is_vault_path(self, path: str) -> bool:
        with self._mu:
            if self.state != OPEN:
                return False
            folder = ""
            for part in cloud.parse_path(path):
                child = self._child(folder, part)
                if child is None:
                    return False
                if child.vault or child.ring != MAIN:
                    return True
                folder = child.id
            return False

    # ---------- 꺼내기 ----------
    def export(self, parts: list[str], header: dict, peer: str, dest_dir: Path) -> Path:
        """복호화한 사본을 dest_dir에 만든다(가져오기). 이름이 겹치면 번호."""
        node = self.node_at(parts, header, peer)
        if node.is_dir:
            raise p.ProtocolError("not_found")
        dest_dir.mkdir(parents=True, exist_ok=True)
        target = p.unique_path(dest_dir, p.sanitize_filename(node.name))
        part = target.with_name(target.name + ".part")
        try:
            with open(part, "wb") as out:
                for block in self.read(node):
                    out.write(block)
            os.replace(part, target)
        except BaseException:
            part.unlink(missing_ok=True)
            raise
        os.utime(target, ns=(node.mtime * 1_000_000, node.mtime * 1_000_000))
        return target


# ---------- 저장소 위치 ----------
def _store_files(base: Path):
    """(oid, 경로) — .tailhop-store 안의 objects/<2글자>/<oid>와 incoming/<oid>.part만. 링크·정션·다른 이름은 건너뛴다."""
    if not base.is_dir() or _is_link(base):
        return
    objects = base / "objects"
    if objects.is_dir() and not _is_link(objects):
        for sub in os.scandir(objects):
            if len(sub.name) != 2 or not sub.is_dir(follow_symlinks=False) or _is_link(Path(sub.path)):
                continue
            for entry in os.scandir(sub.path):
                if OID_RE.fullmatch(entry.name) and entry.name[:2] == sub.name and entry.is_file(follow_symlinks=False):
                    yield entry.name, Path(entry.path)
    incoming = base / "incoming"
    if incoming.is_dir() and not _is_link(incoming):
        for entry in os.scandir(incoming):
            oid = entry.name.removesuffix(".part")
            if entry.name.endswith(".part") and OID_RE.fullmatch(oid) and entry.is_file(follow_symlinks=False):
                yield oid, Path(entry.path)


def _remove_store_files(base: Path) -> None:
    """저장소 파일만 지운다(리셋·옮긴 뒤). 모르는 파일이 있으면 그 폴더는 남긴다."""
    if not base.is_dir() or _is_link(base):
        return
    for _oid, path in list(_store_files(base)):
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass
    for name in ("meta.db", "meta.db-wal", "meta.db-shm", "store.json", "store.tmp"):
        try:
            (base / name).unlink(missing_ok=True)
        except OSError:
            pass
    objects = base / "objects"
    if objects.is_dir() and not _is_link(objects):
        for sub in os.scandir(objects):
            if sub.is_dir(follow_symlinks=False) and not _is_link(Path(sub.path)):
                try:
                    os.rmdir(sub.path)
                except OSError:
                    pass
    for folder in (objects, base / "incoming", base):
        try:
            if folder.is_dir() and not _is_link(folder):
                os.rmdir(folder)
        except OSError:
            pass


def store_id_at(root: Path) -> str | None:
    """root에 저장소가 있으면 그 id."""
    base = Path(root) / STORE_DIR
    if _is_link(base):
        return None
    try:
        info = json.loads((base / "store.json").read_text("utf-8"))
    except (OSError, ValueError):
        return None
    sid = info.get("id") if isinstance(info, dict) and info.get("format") == FORMAT else None
    return sid if isinstance(sid, str) and len(sid) == 32 else None


def volume_serial(path: Path) -> int | None:
    """그 경로가 있는 볼륨의 일련번호(드라이브 문자가 바뀌어도 같은 드라이브인지 볼 때)."""
    anchor = Path(path).anchor
    serial = ctypes.c_uint32()
    try:
        ok = ctypes.windll.kernel32.GetVolumeInformationW(anchor, None, 0, ctypes.byref(serial), None, None, None, 0)
    except (AttributeError, OSError):
        return None
    return serial.value if ok else None


def find_moved(sid: str, old_root: Path, serial: int | None) -> Path | None:
    """외장 드라이브를 다시 꽂았더니 드라이브 문자가 바뀌었다: 다른 문자의 같은 폴더에 같은 저장소가 있고
    그 드라이브의 볼륨 일련번호도 처음 연 드라이브와 같을 때만 그 자리(같은 id를 베낀 다른 USB를 따라가지 않게)."""
    old = Path(old_root)
    if serial is None or not old.drive or len(old.drive) != 2:
        return None
    rest = old.relative_to(old.anchor)
    for letter in "DEFGHIJKLMNOPQRSTUVWXYZ":
        candidate = Path(f"{letter}:\\") / rest
        if candidate != old and store_id_at(candidate) == sid and volume_serial(candidate) == serial:
            return candidate
    return None


def relocate(store: Store, new_root: Path, progress: Callable[[int, int], None] | None = None) -> None:
    """저장소를 다른 폴더(외장 SSD 등)로 옮긴다. .tailhop-store만 암호문 그대로 복사하고(링크는 따라가지 않는다),
    크기와(작은 파일은) SHA-256까지 같은지 확인한 뒤에야 예전 것을 지운다. 받는 중인 조각은 옮기지 않는다."""
    new_root = Path(new_root)
    with store._mu:
        if store.state != OPEN or store.uploads:
            raise p.ProtocolError("store_state" if store.state != OPEN else "vault_busy")
        old_root = store.root
        a, b = old_root.resolve(), new_root.resolve()
        if store_id_at(new_root) is not None or a == b or a in b.parents or b in a.parents:
            raise p.ProtocolError("store_exists")  # 같은 자리·안쪽·바깥쪽으로는 옮기지 않는다
        store.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        old_base, new_base = store.base, new_root / STORE_DIR
        files = [old_base / "store.json", old_base / "meta.db"]
        files += [path for oid, path in _store_files(old_base) if path.parent.parent.name == "objects"]
        total = sum(x.stat().st_size for x in files) or 1
        if shutil.disk_usage(new_root if new_root.exists() else new_root.parent).free < total + p.DISK_RESERVE:
            raise p.ProtocolError("disk_full")
        store._close()
        done = 0
        try:
            new_base.mkdir(parents=True, exist_ok=False)
            _hide(new_base)
            for src in files:
                dest = new_base / src.relative_to(old_base)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, dest)
                if dest.stat().st_size != src.stat().st_size or (src.stat().st_size < 64 << 20 and
                                                                 _sha(dest) != _sha(src)):
                    raise p.ProtocolError("storage")
                done += src.stat().st_size
                if progress:
                    progress(done, total)
        except BaseException:
            _remove_store_files(new_base)  # 반쯤 복사한 새 자리는 지우고 예전 자리를 다시 연다
            store.state = MISSING
            store.refresh()
            raise
        _remove_store_files(old_base)
        store.root = new_root
        store.state = MISSING
        store.refresh()


def _sha(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def drive_info(path: Path) -> dict:
    """화면용: 드라이브 이름(볼륨 레이블)·종류·남은 공간. 모르면 빈 값."""
    anchor = Path(path).anchor or str(path)
    label = ctypes.create_unicode_buffer(261)
    ok = ctypes.windll.kernel32.GetVolumeInformationW(anchor, label, 261, None, None, None, None, 0)
    kind = ctypes.windll.kernel32.GetDriveTypeW(anchor)  # 2 이동식, 3 고정, 4 네트워크
    try:
        free = shutil.disk_usage(path if Path(path).exists() else anchor).free
    except OSError:
        free = None
    return {"drive": anchor.rstrip("\\"), "label": label.value if ok else "", "removable": kind == 2,
            "network": kind == 4, "free": free}
