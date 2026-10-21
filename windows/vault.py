"""잠긴 폴더(보관함, docs/PROTOCOL.md §14). PC 허브만 쓴다.

- 폴더를 잠그면 안의 파일을 하나씩 암호화해 `<클라우드>\\.tailhop-vault\\<id>`로 옮기고 원본은 휴지통을 거치지 않고 지운다.
  허브의 탐색기에서는 암호문만 보인다. 이름·크기·폴더 구조도 암호화한 목록(index.bin)에만 있다.
- 보관함 키(VK)는 PIN에서 scrypt로 만든 키와 복구 코드에서 만든 키로 각각 감싸 둔다. PIN 없이는 허브 디스크로도 못 연다.
- 앱에서 PIN을 넣으면 그 기기에 30분짜리 열쇠(token)를 준다. 쓰일 때마다 연장되고 앱이 꺼지면 사라진다.
- 잠그기·풀기는 뒤에서 하나씩 하고, 앱이 꺼졌다 켜지면 남은 것부터 이어 한다(파일을 보관함에 넣고 목록을 쓴 다음에야 원본을 지운다).
"""
from __future__ import annotations

import base64
import ctypes
import hashlib
import json
import os
import shutil
import stat
import struct
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Callable, Iterator

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import protocol as p
import store

VAULT_DIR = ".tailhop-vault"
CHUNK = 1024 * 1024
TAG = 16
HEAD = 4 + 8 + 8  # "THV1" + 파일 nonce 접두 + 크기
SCRYPT = {"n": 32768, "r": 8, "p": 1}
TOKEN_TTL = 1800
MAX_FAILS = 5
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_HIDDEN_SYSTEM = 0x2 | 0x4


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _kek(secret: str, salt: bytes) -> bytes:
    return hashlib.scrypt(secret.encode("utf-8"), salt=salt, n=SCRYPT["n"], r=SCRYPT["r"], p=SCRYPT["p"],
                          maxmem=64 * 1024 * 1024, dklen=32)


def _wrap(kek: bytes, vk: bytes, vid: str) -> str:
    nonce = os.urandom(12)
    return _b64(nonce + AESGCM(kek).encrypt(nonce, vk, b"tailhop vault v1|" + vid.encode()))


def _unwrap(kek: bytes, wrapped: str, vid: str) -> bytes | None:
    raw = _unb64(wrapped)
    try:
        return AESGCM(kek).decrypt(raw[:12], raw[12:], b"tailhop vault v1|" + vid.encode())
    except Exception:  # noqa: BLE001 - 틀린 PIN
        return None


def new_recovery_code() -> str:
    bits = int.from_bytes(os.urandom(20), "big")
    chars = "".join(CODE_ALPHABET[(bits >> (5 * (31 - i))) & 31] for i in range(32))
    return "-".join(chars[i:i + 4] for i in range(0, 32, 4))


def normalize_code(text: str) -> str:
    raw = "".join(c for c in text.upper() if c.isalnum()).translate(str.maketrans("OIL", "011"))
    return "-".join(raw[i:i + 4] for i in range(0, len(raw), 4))


def valid_pin(pin: object) -> bool:
    return isinstance(pin, str) and 4 <= len(pin) <= 64 and pin == pin.strip()


def _hide(path: Path) -> None:
    try:
        attrs = ctypes.windll.kernel32.GetFileAttributesW(str(path))
        if attrs != -1:
            ctypes.windll.kernel32.SetFileAttributesW(str(path), attrs | _HIDDEN_SYSTEM)
    except (AttributeError, OSError):
        pass


# ---------- 보관함 하나 ----------
@dataclass
class Entry:
    is_dir: bool
    size: int
    mtime: int
    obj: str = ""


class Vault:
    def __init__(self, folder: Path, vid: str, vk: bytes) -> None:
        self.folder = folder
        self.id = vid
        self.vk = vk
        self.aead = AESGCM(vk)
        self.lock = threading.RLock()
        self.entries: dict[str, Entry] = self._load_index()

    # 목록
    def _load_index(self) -> dict[str, Entry]:
        path = self.folder / "index.bin"
        if not path.exists():
            return {}
        raw = path.read_bytes()
        data = json.loads(self.aead.decrypt(raw[:12], raw[12:], b"tailhop vault index|" + self.id.encode()))
        return {k: Entry(bool(v["dir"]), int(v["size"]), int(v["mtime"]), str(v.get("obj", "")))
                for k, v in data["entries"].items()}

    def save_index(self) -> None:
        data = {"entries": {k: {"dir": e.is_dir, "size": e.size, "mtime": e.mtime, "obj": e.obj}
                            for k, e in self.entries.items()}}
        nonce = os.urandom(12)
        blob = nonce + self.aead.encrypt(nonce, json.dumps(data).encode(), b"tailhop vault index|" + self.id.encode())
        tmp = self.folder / "index.bin.tmp"
        tmp.write_bytes(blob)
        os.replace(tmp, self.folder / "index.bin")

    def children(self, rel: str) -> list[tuple[str, Entry]]:
        prefix = rel + "/" if rel else ""
        out = []
        for key, entry in self.entries.items():
            if key.startswith(prefix) and "/" not in key[len(prefix):]:
                out.append((key[len(prefix):], entry))
        return out

    def is_dir(self, rel: str) -> bool:
        return rel == "" or (rel in self.entries and self.entries[rel].is_dir)

    def ensure_dirs(self, rel: str, mtime: int | None = None) -> None:
        parts = rel.split("/") if rel else []
        for i in range(1, len(parts) + 1):
            key = "/".join(parts[:i])
            entry = self.entries.get(key)
            if entry is None:
                self.entries[key] = Entry(True, 0, mtime or int(time.time() * 1000))
            elif not entry.is_dir:
                raise p.ProtocolError("bad_path")

    # 파일 쓰기
    def writer(self, rel: str, size: int, mtime: int) -> "ObjectWriter":
        return ObjectWriter(self, rel, size, mtime)

    def put_file(self, rel: str, src: Path) -> None:
        st = src.stat()
        w = self.writer(rel, st.st_size, st.st_mtime_ns // 1_000_000)
        try:
            with open(src, "rb") as f:
                for block in iter(lambda: f.read(CHUNK), b""):
                    w.write(block)
            w.commit()
        except BaseException:
            w.abort()
            raise

    # 파일 읽기
    def read(self, rel: str, offset: int = 0) -> Iterator[bytes]:
        """평문을 offset부터 1 MiB 안팎으로 돌려준다. 조각마다 태그를 확인한다."""
        entry = self.entries.get(rel)
        if entry is None or entry.is_dir:
            raise p.ProtocolError("not_found")
        path = self.folder / "objects" / entry.obj
        with open(path, "rb") as f:
            head = f.read(HEAD)
            if head[:4] != b"THV1":
                raise p.ProtocolError("storage")
            prefix, size = head[4:12], struct.unpack(">Q", head[12:20])[0]
            count = max(1, -(-size // CHUNK))
            index = offset // CHUNK
            f.seek(HEAD + index * (CHUNK + TAG))
            skip = offset - index * CHUNK
            while index < count:
                plain_len = min(CHUNK, size - index * CHUNK)
                sealed = f.read(plain_len + TAG)
                last = index == count - 1
                aad = entry.obj.encode() + struct.pack(">I", index) + (b"\x01" if last else b"\x00")
                plain = self.aead.decrypt(prefix + struct.pack(">I", index), sealed, aad)
                yield plain[skip:] if skip else plain
                skip = 0
                index += 1

    def read_all(self, rel: str, limit: int) -> bytes:
        entry = self.entries.get(rel)
        if entry is None or entry.is_dir or entry.size > limit:
            raise p.ProtocolError("not_found")
        return b"".join(self.read(rel))

    # 지우기
    def delete(self, rel: str) -> None:
        with self.lock:
            doomed = [k for k in self.entries if k == rel or k.startswith(rel + "/")]
            if not doomed:
                raise p.ProtocolError("not_found")
            for key in doomed:
                entry = self.entries.pop(key)
                if entry.obj:
                    (self.folder / "objects" / entry.obj).unlink(missing_ok=True)
            self.save_index()


class ObjectWriter:
    """평문을 받아 1 MiB 조각씩 암호화해 쓴다. 다 쓰면 commit으로 목록에 넣는다(그 전에는 보이지 않는다)."""

    def __init__(self, vault: Vault, rel: str, size: int, mtime: int) -> None:
        self.vault, self.rel, self.size, self.mtime = vault, rel, size, mtime
        self.obj = os.urandom(16).hex()
        self.prefix = os.urandom(8)
        (vault.folder / "objects").mkdir(exist_ok=True)
        self.path = vault.folder / "objects" / self.obj
        self.out: BinaryIO = open(self.path, "xb")
        self.out.write(b"THV1" + self.prefix + struct.pack(">Q", size))
        self.buf = bytearray()
        self.index = 0
        self.written = 0
        self.count = max(1, -(-size // CHUNK))

    def _flush(self, data: bytes) -> None:
        last = self.index == self.count - 1
        aad = self.obj.encode() + struct.pack(">I", self.index) + (b"\x01" if last else b"\x00")
        self.out.write(self.vault.aead.encrypt(self.prefix + struct.pack(">I", self.index), data, aad))
        self.index += 1

    def write(self, data: bytes) -> None:
        self.written += len(data)
        if self.written > self.size:
            raise p.ProtocolError("file_too_big")
        self.buf += data
        while len(self.buf) >= CHUNK and self.index < self.count - 1:
            self._flush(bytes(self.buf[:CHUNK]))
            del self.buf[:CHUNK]

    def commit(self) -> None:
        if self.written != self.size:
            raise p.ProtocolError("file_incomplete")
        self._flush(bytes(self.buf))
        self.out.close()
        with self.vault.lock:
            parent = self.rel.rpartition("/")[0]
            self.vault.ensure_dirs(parent)
            old = self.vault.entries.get(self.rel)
            if old is not None and old.is_dir:
                raise p.ProtocolError("bad_path")
            self.vault.entries[self.rel] = Entry(False, self.size, self.mtime, self.obj)
            self.vault.save_index()
            if old is not None and old.obj:
                (self.vault.folder / "objects" / old.obj).unlink(missing_ok=True)

    def abort(self) -> None:
        try:
            self.out.close()
        finally:
            self.path.unlink(missing_ok=True)


# ---------- 클라우드 폴더 하나의 보관함들 ----------
@dataclass
class Mount:
    id: str
    mount: str  # 클라우드 경로(§12.1)
    state: str  # locked | locking | unlocking
    progress: int = 0


@dataclass
class Token:
    vid: str
    peer: str
    expires: float


class VaultManager:
    def __init__(self, cloud_root: Path, clock: Callable[[], float] = time.time) -> None:
        self.root = Path(cloud_root)
        self.base = self.root / VAULT_DIR
        self.clock = clock
        self.lock = threading.RLock()
        self.mounts: dict[str, Mount] = {}
        self.open_vaults: dict[str, Vault] = {}  # 열쇠가 하나라도 살아 있는 보관함(잠그기·풀기가 끝난 뒤에만 만든다)
        self.keys: dict[str, bytes] = {}  # 열린 보관함의 VK(메모리에만, 열쇠가 모두 끝나면 버린다)
        self.tokens: dict[str, Token] = {}
        self.fails: dict[str, tuple[int, float]] = {}  # vid: (틀린 횟수, 막힌 끝 시각)
        self.jobs: dict[str, threading.Thread] = {}
        self._pending_keys: dict[str, bytes] = {}
        self._load()

    # 등록부
    def _load(self) -> None:
        try:
            data = json.loads((self.base / "vaults.json").read_text("utf-8"))
        except (OSError, ValueError):
            return
        for raw in data.get("vaults", []) if isinstance(data, dict) else []:
            try:
                m = Mount(str(raw["id"]), str(raw["mount"]), str(raw.get("state", "locked")))
            except (KeyError, TypeError):
                continue
            if len(m.id) == 32 and (self.base / m.id / "vault.json").exists():
                self.mounts[m.mount] = m

    def _save(self) -> None:
        self.base.mkdir(parents=True, exist_ok=True)
        _hide(self.base)
        data = {"vaults": [{"id": m.id, "mount": m.mount, "state": m.state} for m in self.mounts.values()]}
        store._write_private(self.base / "vaults.json", json.dumps(data))

    def mount_of(self, path: str) -> tuple[Mount, str] | None:
        """path가 잠긴 폴더 자리이거나 그 안이면 (보관함, 안쪽 상대 경로)."""
        with self.lock:
            for mount, m in self.mounts.items():
                if path == mount:
                    return m, ""
                if path.startswith(mount + "/"):
                    return m, path[len(mount) + 1:]
        return None

    def mounts_under(self, path: str) -> list[Mount]:
        """path 폴더 바로 아래에 있는 잠긴 폴더들(목록에 보일 것)."""
        prefix = path + "/" if path else ""
        with self.lock:
            return [m for k, m in self.mounts.items() if k.startswith(prefix) and "/" not in k[len(prefix):]]

    def any_inside(self, path: str) -> bool:
        prefix = path + "/" if path else ""
        with self.lock:
            return any(k == path or k.startswith(prefix) for k in self.mounts)

    # 잠그기
    def lock_folder(self, path: str, folder: Path, pin: str) -> str:
        """폴더를 잠근다. 키를 만들어 두고 바로 복구 코드를 돌려준다. 파일은 뒤에서 하나씩 옮긴다."""
        if not path or not valid_pin(pin):
            raise p.ProtocolError("bad_path" if not path else "vault_pin")
        if self.mount_of(path) is not None or self.any_inside(path):
            raise p.ProtocolError("bad_path")
        if not folder.is_dir():
            raise p.ProtocolError("not_found")
        vid = os.urandom(16).hex()
        vk = os.urandom(32)
        code = new_recovery_code()
        salt, rsalt = os.urandom(16), os.urandom(16)
        meta = {"v": 1, "kdf": "scrypt", **SCRYPT, "salt": _b64(salt), "wrapped": _wrap(_kek(pin, salt), vk, vid),
                "rsalt": _b64(rsalt), "rwrapped": _wrap(_kek(code, rsalt), vk, vid)}
        vfolder = self.base / vid
        (vfolder / "objects").mkdir(parents=True)
        _hide(self.base)
        store._write_private(vfolder / "vault.json", json.dumps(meta))
        Vault(vfolder, vid, vk).save_index()
        with self.lock:
            self.mounts[path] = Mount(vid, path, "locking")
            self._save()
            self._pending_keys[vid] = vk
        self._start(vid, self._run_lock)
        return code

    def _start(self, vid: str, target) -> None:
        if vid in self.jobs and self.jobs[vid].is_alive():
            return
        thread = threading.Thread(target=self._job, args=(vid, target), daemon=True)
        self.jobs[vid] = thread
        thread.start()

    def _job(self, vid: str, target) -> None:
        try:
            target(vid)
        finally:
            with self.lock:
                self.open_vaults.pop(vid, None)  # 다음 사용 때 끝난 목록으로 다시 연다

    def _mount_by_id(self, vid: str) -> Mount | None:
        with self.lock:
            return next((m for m in self.mounts.values() if m.id == vid), None)

    def _run_lock(self, vid: str) -> None:
        m = self._mount_by_id(vid)
        vk = self._pending_keys.get(vid)
        if m is None or vk is None:
            return
        vault = Vault(self.base / vid, vid, vk)
        src_root = self.root.joinpath(*m.mount.split("/"))
        files = []
        for folder, dirs, names in os.walk(src_root, followlinks=False):
            here = Path(folder)
            dirs[:] = [d for d in dirs if not (here / d).is_symlink() and not (here / d).is_junction()]
            for d in dirs:
                rel = (here / d).relative_to(src_root).as_posix()
                vault.ensure_dirs(rel, (here / d).stat().st_mtime_ns // 1_000_000)
            for name in names:
                path = here / name
                if not path.is_symlink() and stat.S_ISREG(os.lstat(path).st_mode):
                    files.append(path)
        total = sum(f.stat().st_size for f in files) or 1
        done = 0
        for path in files:
            rel = path.relative_to(src_root).as_posix()
            vault.put_file(rel, path)  # 보관함에 넣고 목록을 쓴 다음
            done += path.stat().st_size
            os.remove(path)  # 원본을 지운다(휴지통을 거치지 않음)
            m.progress = min(99, done * 100 // total)
        vault.save_index()
        shutil.rmtree(src_root, ignore_errors=True)
        with self.lock:
            m.state, m.progress = "locked", 100
            self._save()
            self._pending_keys.pop(vid, None)

    # 열기
    def _meta(self, vid: str) -> dict:
        return json.loads((self.base / vid / "vault.json").read_text("utf-8"))

    def open(self, path: str, peer: str, pin: str | None = None, recovery: str | None = None) -> str:
        found = self.mount_of(path)
        if found is None or found[1]:
            raise p.ProtocolError("not_found")
        m = found[0]
        vk = self._check_secret(m.id, pin, recovery)
        with self.lock:
            self.keys[m.id] = vk
            token = os.urandom(16).hex()
            self.tokens[token] = Token(m.id, peer, self.clock() + TOKEN_TTL)
            stalled = m.state != "locked" and not (m.id in self.jobs and self.jobs[m.id].is_alive())
            if stalled:  # 잠그거나 풀다가 앱이 꺼졌다: 키를 얻었으니 이어 한다
                self._pending_keys[m.id] = vk
        if stalled:
            self._start(m.id, self._run_lock if m.state == "locking" else self._run_unlock)
        return token

    def _check_secret(self, vid: str, pin: str | None, recovery: str | None) -> bytes:
        now = self.clock()
        count, until = self.fails.get(vid, (0, 0.0))
        if now < until:
            raise p.ProtocolError("vault_wait", retry=int(until - now) + 1)
        meta = self._meta(vid)
        vk = None
        if isinstance(pin, str) and valid_pin(pin):
            vk = _unwrap(_kek(pin, _unb64(meta["salt"])), meta["wrapped"], vid)
        elif isinstance(recovery, str) and recovery:
            vk = _unwrap(_kek(normalize_code(recovery), _unb64(meta["rsalt"])), meta["rwrapped"], vid)
        if vk is None:
            count += 1
            wait = 0.0 if count < MAX_FAILS else min(3600.0, 60.0 * 2 ** (count - MAX_FAILS))
            self.fails[vid] = (count, now + wait)
            raise p.ProtocolError("vault_pin")
        self.fails.pop(vid, None)
        return vk

    def vault_for(self, mount: Mount, token: object, peer: str) -> Vault:
        """열쇠를 확인하고 열린 보관함을 돌려준다(30분 연장). 잠그기·풀기 중이면 vault_busy."""
        if mount.state != "locked":
            raise p.ProtocolError("vault_busy")
        with self.lock:
            tok = self.tokens.get(token) if isinstance(token, str) else None
            if tok is None or tok.vid != mount.id or tok.peer != peer or tok.expires < self.clock():
                raise p.ProtocolError("vault_locked")
            tok.expires = self.clock() + TOKEN_TTL
            vault = self.open_vaults.get(mount.id)
            if vault is None:
                vk = self.keys.get(mount.id)
                if vk is None:
                    raise p.ProtocolError("vault_locked")
                vault = self.open_vaults[mount.id] = Vault(self.base / mount.id, mount.id, vk)
        return vault

    def close_expired(self) -> None:
        now = self.clock()
        with self.lock:
            self.tokens = {k: v for k, v in self.tokens.items() if v.expires >= now}
            alive = {t.vid for t in self.tokens.values()}
            self.open_vaults = {k: v for k, v in self.open_vaults.items() if k in alive}
            self.keys = {k: v for k, v in self.keys.items() if k in alive}

    # 풀기·PIN 바꾸기·지우기
    def unlock_folder(self, path: str, pin: str) -> None:
        found = self.mount_of(path)
        if found is None or found[1]:
            raise p.ProtocolError("not_found")
        m = found[0]
        if m.state != "locked":
            raise p.ProtocolError("vault_busy")
        vk = self._check_secret(m.id, pin, None)
        with self.lock:
            m.state, m.progress = "unlocking", 0
            self._save()
            self._pending_keys[m.id] = vk
        self._start(m.id, self._run_unlock)

    def _run_unlock(self, vid: str) -> None:
        m = self._mount_by_id(vid)
        vk = self._pending_keys.get(vid)
        if m is None or vk is None:
            return
        vault = Vault(self.base / vid, vid, vk)
        dest = self.root.joinpath(*m.mount.split("/"))
        dest.mkdir(parents=True, exist_ok=True)
        items = sorted(vault.entries.items())
        total = sum(e.size for _, e in items) or 1
        done = 0
        for rel, entry in items:
            target = dest.joinpath(*rel.split("/"))
            if entry.is_dir:
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            tmp = target.with_name(target.name + ".part")
            with open(tmp, "wb") as out:
                for block in vault.read(rel):
                    out.write(block)
            os.replace(tmp, target)
            os.utime(target, ns=(entry.mtime * 1_000_000, entry.mtime * 1_000_000))
            done += entry.size
            m.progress = min(99, done * 100 // total)
        shutil.rmtree(self.base / vid, ignore_errors=True)
        with self.lock:
            self.mounts.pop(m.mount, None)
            self.open_vaults.pop(vid, None)
            self.tokens = {k: v for k, v in self.tokens.items() if v.vid != vid}
            self._pending_keys.pop(vid, None)
            self._save()

    def repin(self, path: str, new_pin: str, pin: str | None = None, recovery: str | None = None) -> None:
        found = self.mount_of(path)
        if found is None or found[1]:
            raise p.ProtocolError("not_found")
        if not valid_pin(new_pin):
            raise p.ProtocolError("vault_pin")
        m = found[0]
        vk = self._check_secret(m.id, pin, recovery)
        meta = self._meta(m.id)
        salt = os.urandom(16)
        meta["salt"], meta["wrapped"] = _b64(salt), _wrap(_kek(new_pin, salt), vk, m.id)
        store._write_private(self.base / m.id / "vault.json", json.dumps(meta))

    def destroy(self, mount: Mount) -> None:
        """잠긴 폴더째 영구히 지운다(열쇠가 있을 때만 부른다)."""
        with self.lock:
            self.mounts.pop(mount.mount, None)
            self.open_vaults.pop(mount.id, None)
            self.tokens = {k: v for k, v in self.tokens.items() if v.vid != mount.id}
            self._save()
        shutil.rmtree(self.base / mount.id, ignore_errors=True)
