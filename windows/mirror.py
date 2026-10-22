"""모든 기기 동기화의 이 기기 쪽 사본(2.3.0, 2.3.1에서 잠금, docs/PROTOCOL.md §19.3·§19.5). 허브마다 하나.

- 자리: %LOCALAPPDATA%\\TailHop\\Mirror\\<sha256(허브 id) 앞 16자>\\. 폴더 이름에도 허브 이름을 쓰지 않는다.
- 잠금: 이 PC의 공개키 한 쌍(Tink HPKE, device.json). 새 파일 열쇠·이름·경로는 공개키로만 잠가 쓰고(잠긴 채로 동기화가 된다),
  읽는 개인키는 "사본 비밀번호"(Argon2id)로만 풀린다. 비밀번호는 저장하지 않는다. 이 PC의 다른 프로그램도 비밀번호 없이는
  사본의 이름·내용을 볼 수 없다.
- 장부(ledger): 받은 청크 수·버전·경로의 키 붙은 해시 같은 동기화에 필요한 것만 DPAPI로 잠가 둔다. 받는 중인 파일의 열쇠만
  잠깐 장부에 있고, 다 받으면 지운다(공개키로 잠근 것만 남는다).
- 파일 내용은 저장소(§17.1)와 같은 1 MiB 청크 AES-256-GCM, 파일마다 열쇠. 탐색기에서는 암호문뿐이다.
- 허브의 저장소 번호가 바뀌면(허브 초기화) 사본을 지우지 않고 이전 사본(<16자>.old-<시각>)으로 둔다(§19.5).
"""
from __future__ import annotations

import hashlib
import hmac
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

import tink
from tink import hybrid, proto_keyset_format

import cloud
import cloudkeys as keys
import cloudstore as cs
import protocol as p

hybrid.register()

MIRROR_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "TailHop" / "Mirror"
RESERVE = 1 << 30  # 이만큼은 늘 남긴다(§19.3)
PIECE = 4 * cs.CHUNK  # 한 번에 받는 크기
THUMB_LIMIT = 2 << 20
ID_RE = re.compile(r"[0-9a-f]{32}")
DIGEST_RE = re.compile(r"[0-9a-f]{16}")
FOLDER_RE = re.compile(r"[0-9a-f]{16}(\.old-\d{10,})?")
STATE = "state"
HPKE = hybrid.hybrid_key_templates.DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM


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
    vault: dict | None = None  # §19.6 잠긴 폴더 줄: {"ring","pin","recovery","pin_ad","recovery_ad"}
    row: str = ""  # §19.6 잠긴 폴더 안의 줄(id는 그 잠긴 폴더)
    parent: str = ""
    ring: str = ""
    meta: str = ""
    objs: tuple = ()


def _sealed_ok(record: object) -> bool:
    return isinstance(record, dict) and all(isinstance(record.get(k), str) and len(record[k]) < 4096
                                            for k in ("alg", "salt", "blob")) \
        and all(_uint(record.get(k)) for k in ("t", "m", "p"))


def _parse_vault_row(raw: dict, path: str) -> Want | None:
    row, parent, ring, meta, objs = (raw.get(k) for k in ("row", "parent", "ring", "meta", "objs"))
    if not all(isinstance(x, str) and ID_RE.fullmatch(x) for x in (row, parent, ring)):
        return None
    if not isinstance(meta, str) or not 0 < len(meta) <= 65536 or not isinstance(objs, list) or len(objs) > 16:
        return None
    clean = []
    for obj in objs:
        if not isinstance(obj, dict) or not isinstance(obj.get("o"), str) or not ID_RE.fullmatch(obj["o"]) \
                or not _uint(obj.get("s")) or obj["s"] > p.MAX_FILE + (p.MAX_FILE >> 10):
            return None
        clean.append((obj["o"], obj["s"]))
    return Want(raw["id"], path, True, row=row, parent=parent, ring=ring, meta=meta, objs=tuple(clean))


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
    if "row" in raw:
        return _parse_vault_row(raw, path)
    if raw.get("dir") is True and "vault" in raw:
        vault = raw["vault"]
        if not isinstance(vault, dict) or not isinstance(vault.get("ring"), str) or not ID_RE.fullmatch(vault["ring"]) \
                or not _sealed_ok(vault.get("pin")) or not _sealed_ok(vault.get("recovery")) \
                or not all(isinstance(vault.get(k), str) and len(vault[k]) <= 300 for k in ("pin_ad", "recovery_ad")):
            return None
        keep = {k: vault[k] for k in ("ring", "pin", "recovery", "pin_ad", "recovery_ad")}
        return Want(raw["id"], path, True, vault=keep)
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


# ---------- 이 PC의 사본 열쇠 ----------
class DeviceKey:
    """사본 공개키 한 쌍. 쓰기는 늘 되고, 읽기는 사본 비밀번호로 연 동안만."""

    def __init__(self, base: Path) -> None:
        self.base = Path(base)
        self.path = self.base / "device.json"
        self._mu = threading.Lock()
        self._enc = None
        self._dec = None
        self.generation = 0  # 열고 잠글 때마다 올린다(사본들이 풀어 둔 목록을 버리게)
        self._load()

    def _load(self) -> None:
        try:
            data = json.loads(self.path.read_text("utf-8"))
            pub = proto_keyset_format.parse_without_secret(keys.unb64(data["pub"]))
            self._enc = pub.primitive(hybrid.HybridEncrypt)
        except (OSError, ValueError, KeyError, TypeError, tink.TinkError):
            self._enc = None

    @property
    def exists(self) -> bool:
        return self._enc is not None

    @property
    def locked(self) -> bool:
        return self._dec is None

    def create(self, password: str) -> None:
        """처음 한 번: 사본 비밀번호를 정한다(10자 이상)."""
        if not keys.password_ok(password):
            raise ValueError("password rule")  # 화면이 먼저 확인한다(10자 이상)
        with self._mu:
            priv = tink.new_keyset_handle(HPKE)
            pub = proto_keyset_format.serialize_without_secret(priv.public_keyset_handle())
            record = {"v": 1, "pub": keys.b64(pub), "priv": keys.seal_secret(priv, password, b"tailhop mirror device")}
            self.base.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(record), "utf-8")
            os.replace(tmp, self.path)
            self._load()
            self._dec = priv.primitive(hybrid.HybridDecrypt)
            self.generation += 1

    def unlock(self, password: str) -> bool:
        try:
            record = json.loads(self.path.read_text("utf-8"))["priv"]
        except (OSError, ValueError, KeyError, TypeError):
            return False
        handle = keys.open_secret(record, password, b"tailhop mirror device") if isinstance(password, str) else None
        if handle is None:
            return False
        with self._mu:
            self._dec = handle.primitive(hybrid.HybridDecrypt)
            self.generation += 1
        return True

    def lock(self) -> bool:
        with self._mu:
            was = self._dec is not None
            self._dec = None
            self.generation += 1
        return was

    def forget(self) -> None:
        """비밀번호를 잊었다: 열쇠를 지운다(사본은 모두 지우고 다시 받아야 한다)."""
        with self._mu:
            self._enc = self._dec = None
            self.path.unlink(missing_ok=True)
            self.generation += 1

    def encrypt(self, data: bytes, context: bytes) -> bytes:
        if self._enc is None:
            raise p.ProtocolError("mirror_setup")
        return self._enc.encrypt(data, context)

    def decrypt(self, data: bytes, context: bytes) -> bytes:
        dec = self._dec
        if dec is None:
            raise p.ProtocolError("mirror_locked")
        try:
            return dec.decrypt(data, context)
        except tink.TinkError as exc:
            raise p.ProtocolError("storage") from exc


def ledger_aead(base: Path):
    """장부를 잠그는 열쇠(이 PC의 Windows 계정에 묶임). 다른 계정·다시 깐 Windows면 새로(장부가 안 맞는 사본은 다시 받는다)."""
    path = Path(base) / "ledger.bin"
    handle = keys.open_local(path.read_bytes(), b"tailhop mirror ledger") if path.exists() else None
    if handle is None:
        handle = keys.new_keyset()
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(keys.seal_local(handle, b"tailhop mirror ledger"))
        os.replace(tmp, path)
    return keys.primitive(handle)


# ---------- 맞추기 계획 ----------
@dataclass
class Plan:
    """받을 것(새것·바뀐 것), 경로·수정 시각만 고칠 것, 지울 것."""
    fetch: list[Want]
    moves: list[Want]
    drop: list[str]


def plan(have: dict[str, dict], wants: list[Want], path_hash: Callable[[str], str]) -> Plan:
    """have: 장부의 {허브 id: 줄}. 같은 id·ver(다 받은 것)면 경로만, 아니면 받기, 목록에 없으면 지우기."""
    fetch, moves = [], []
    seen = set()
    for want in wants:
        seen.add(want.id)
        old = have.get(want.id)
        if want.dir:
            if old is None or old.get("ph") != path_hash(want.path) or not old.get("dir") \
                    or old.get("vh", "") != vault_hash(want):
                moves.append(want)
            continue
        complete = old is not None and old.get("ver") == want.ver and old.get("done") == cs.chunk_count(want.size)
        if not complete:
            fetch.append(want)
        elif old.get("ph") != path_hash(want.path) or old.get("mt") != want.mtime or old.get("tver") != want.tver:
            moves.append(want)
    return Plan(fetch, moves, [hid for hid in have if hid not in seen])


def vault_hash(want: Want) -> str:
    """잠긴 폴더 줄의 봉인이 바뀌었는지(PIN을 바꿈) 볼 값. 보통 폴더면 ""."""
    if want.vault is None:
        return ""
    return hashlib.sha256(json.dumps(want.vault, sort_keys=True).encode()).hexdigest()[:32]


def row_hash(want: Want) -> str:
    return hashlib.sha256(json.dumps([want.id, want.parent, want.ring, want.meta, list(want.objs)]).encode()) \
        .hexdigest()[:32]


class Mirror:
    """사본 하나(지금 허브의 것 또는 이전 사본). 모든 함수는 작업 스레드에서 불러도 된다(안에서 잠근다)."""

    def __init__(self, folder: Path, device: DeviceKey, ledger) -> None:
        self.dir = Path(folder)
        self.device = device
        self.ledger_aead = ledger
        self._mu = threading.RLock()
        self.book: dict[str, dict] = {}  # 허브 id → 장부 줄(잠겨 있어도 읽힌다)
        self.state: dict = {}
        self.db: sqlite3.Connection | None = None
        self._view: tuple[int, dict[str, dict]] | None = None
        self.vrows: dict[str, dict] = {}  # §19.6 잠긴 폴더 안의 줄(meta 암호문 그대로)
        self._vault_keys: dict[str, tuple[int, object]] = {}  # 연 잠긴 폴더: (열쇠 세대, AEAD)
        self._open()

    # ---------- 열기 ----------
    def _open(self, again: bool = True) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        if (self.dir / "key.bin").exists():  # 2.3.0 사본(이 PC 계정 열쇠만): 지우고 새로 받는다
            self._wipe_files()
            self.dir.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.dir / "index.db", check_same_thread=False, isolation_level=None)
        db.execute("PRAGMA trusted_schema=OFF")
        if db.execute("SELECT count(*) FROM sqlite_master WHERE type IN ('trigger', 'view')").fetchone()[0]:
            db.close()
            raise p.ProtocolError("storage")
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA secure_delete=ON")
        db.executescript("""
            CREATE TABLE IF NOT EXISTS ledger(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS names(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS fkeys(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS thumbs(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS vaults(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS vrows(id TEXT PRIMARY KEY, sealed BLOB NOT NULL);
        """)
        self.db = db
        row = db.execute("SELECT sealed FROM kv WHERE k='state'").fetchone()
        state = self._unseal(row[0], STATE) if row else None
        if state is None or not isinstance(state.get("uid"), str):
            if row is not None or db.execute("SELECT count(*) FROM ledger").fetchone()[0]:
                # 장부를 풀 수 없다(다른 Windows 계정·다시 깐 Windows): 처음부터 다시 받는다
                db.close()
                if not again:
                    raise p.ProtocolError("storage")
                self._wipe_files()
                self._open(again=False)
                return
            state = {"uid": os.urandom(16).hex(), "salt": os.urandom(16).hex(), "digest": "", "at": 0.0}
            self.state = state
            self._save_state()
        self.state = state
        for hid, sealed in db.execute("SELECT id, sealed FROM ledger").fetchall():
            line = self._unseal(sealed, "ledger " + hid)
            if ID_RE.fullmatch(hid) and line is not None:
                self.book[hid] = line
        for rid, sealed in db.execute("SELECT id, sealed FROM vrows").fetchall():
            line = self._unseal(sealed, "vrow " + rid)
            if ID_RE.fullmatch(rid) and line is not None:
                self.vrows[rid] = line

    def _ad(self, what: str) -> bytes:
        if what == STATE:  # 상태 줄은 uid를 담고 있어 uid에 기대지 않는다
            return b"tailhop mirror|state"
        return f"tailhop mirror|{self.state['uid']}|{what}".encode()

    def _seal(self, data: dict, what: str) -> bytes:
        return self.ledger_aead.encrypt(json.dumps(data, separators=(",", ":")).encode(), self._ad(what))

    def _unseal(self, sealed: bytes, what: str) -> dict | None:
        try:
            data = json.loads(self.ledger_aead.decrypt(sealed, self._ad(what)))
        except (tink.TinkError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _hide(self, table: str, hid: str, data: dict) -> None:
        """공개키로 잠가 쓴다(잠겨 있어도 된다, 열려 있어야 읽힌다)."""
        sealed = self.device.encrypt(json.dumps(data, separators=(",", ":")).encode(), self._ad(f"{table} {hid}"))
        self.db.execute(f"INSERT OR REPLACE INTO {table} VALUES(?, ?)", (hid, sealed))
        self._view = None

    def _save_state(self) -> None:
        self.db.execute("INSERT OR REPLACE INTO kv VALUES('state', ?)", (self._seal(self.state, STATE),))

    def _put_book(self, hid: str, line: dict) -> None:
        self.db.execute("INSERT OR REPLACE INTO ledger VALUES(?, ?)", (hid, self._seal(line, "ledger " + hid)))
        self.book[hid] = line
        self._view = None

    def path_hash(self, path: str) -> str:
        return hmac.new(bytes.fromhex(self.state["salt"]), path.encode("utf-8"), hashlib.sha256).hexdigest()[:32]

    @property
    def digest(self) -> str:
        return self.state.get("digest", "") if isinstance(self.state.get("digest"), str) else ""

    @property
    def synced_at(self) -> float:
        at = self.state.get("at", 0)
        return float(at) if isinstance(at, (int, float)) else 0.0

    @property
    def store_id(self) -> str:
        sid = self.state.get("store_id", "")
        return sid if isinstance(sid, str) else ""

    def set_info(self, **fields) -> None:
        """허브 이름·저장소 번호 등(이전 사본 목록에 보인다)."""
        with self._mu:
            self.state.update(fields)
            self._save_state()

    def close(self) -> None:
        with self._mu:
            if self.db is not None:
                self.db.close()
                self.db = None

    # ---------- 풀어 보기(열려 있을 때만) ----------
    @property
    def locked(self) -> bool:
        return self.device.locked

    @property
    def entries(self) -> dict[str, dict]:
        """허브 id → {path, dir, size, mtime, ver, done, o, k, t}. 잠겨 있으면 mirror_locked."""
        with self._mu:
            gen = self.device.generation
            if self._view is not None and self._view[0] == gen:
                return self._view[1]
            out: dict[str, dict] = {}
            names = dict(self.db.execute("SELECT id, sealed FROM names").fetchall())
            fkeys = dict(self.db.execute("SELECT id, sealed FROM fkeys").fetchall())
            thumbs = dict(self.db.execute("SELECT id, sealed FROM thumbs").fetchall())
            for hid, line in self.book.items():
                if hid not in names:
                    continue
                info = {**json.loads(self.device.decrypt(names[hid], self._ad("names " + hid))), **line}
                if line.get("vault"):  # 잠긴 폴더: 열었으면 그 안의 줄도
                    info["locked"] = True
                    out[hid] = info
                    out.update(self._vault_rows(hid, info["path"]))
                    continue
                if hid in fkeys:
                    info["k"] = json.loads(self.device.decrypt(fkeys[hid], self._ad("fkeys " + hid)))["k"]
                if hid in thumbs and line.get("to"):
                    info["t"] = json.loads(self.device.decrypt(thumbs[hid], self._ad("thumbs " + hid)))
                out[hid] = info
            self._view = (gen, out)
            return out

    def complete(self, info: dict) -> bool:
        return not info.get("dir") and info.get("done") == cs.chunk_count(int(info.get("size", 0))) \
            and isinstance(info.get("k"), str)

    # ---------- 잠긴 폴더(§19.6) ----------
    def vault_at(self, path: str) -> str | None:
        """그 경로가 잠긴 폴더면 그 id(사본이 열려 있어야 이름을 안다)."""
        for hid, info in self.entries.items():
            if info.get("vault") is True and info.get("path") == path:
                return hid
        return None

    def vault_open(self, vid: str) -> bool:
        found = self._vault_keys.get(vid)
        return found is not None and found[0] == self.device.generation

    def open_vault(self, vid: str, pin: str | None = None, recovery: str | None = None) -> None:
        """PIN(또는 복구 코드)으로 사본의 잠긴 폴더를 연다. 사본도 열려 있어야 한다. 5번 틀리면 기다린다(허브와 같다)."""
        with self._mu:
            row = self.db.execute("SELECT sealed FROM vaults WHERE id=?", (vid,)).fetchone()
            if row is None:
                raise p.ProtocolError("not_found")
            record = json.loads(self.device.decrypt(row[0], self._ad("vaults " + vid)))
            fails = self.state.setdefault("vfail", {})
            count, until = fails.get(vid, [0, 0.0])
            now = time.time()
            if now < until:
                raise p.ProtocolError("vault_wait", retry=int(until - now) + 1)
            count += 1
            fails[vid] = [count, now + (0.0 if count < cs.MAX_FAILS else min(3600.0, 60.0 * 2 ** (count - cs.MAX_FAILS)))]
            self._save_state()  # 틀린 횟수는 확인 전에 올린다(앱을 껐다 켜도 이어진다)
        handle = None
        if cs.valid_pin(pin):
            handle = keys.open_secret(record["pin"], pin, record["pin_ad"].encode())
        elif isinstance(recovery, str) and recovery:
            handle = keys.open_secret(record["recovery"], keys.normalize_code(recovery), record["recovery_ad"].encode())
        if handle is None:
            raise p.ProtocolError("vault_pin")
        with self._mu:
            self.state["vfail"].pop(vid, None)
            self._save_state()
            self._vault_keys[vid] = (self.device.generation, keys.primitive(handle))
            self._view = None

    def close_vaults(self) -> None:
        with self._mu:
            self._vault_keys.clear()
            self._view = None

    def _vault_rows(self, vid: str, vault_path: str) -> dict[str, dict]:
        """연 잠긴 폴더 안의 줄을 풀어 경로를 붙인다(닫혀 있으면 없음)."""
        if not self.vault_open(vid):
            return {}
        aead = self._vault_keys[vid][1]
        metas: dict[str, dict] = {}
        for rid, line in self.vrows.items():
            if line.get("vault") != vid:
                continue
            try:
                meta = json.loads(aead.decrypt(keys.unb64(line["meta"]),
                                               f"tailhop node|{rid}|{line['parent']}|{line['ring']}".encode()))
            except (tink.TinkError, ValueError, KeyError):
                continue  # 망가진 줄은 보이지 않게
            if isinstance(meta, dict) and isinstance(meta.get("n"), str):
                metas[rid] = {"meta": meta, "parent": line["parent"], "objs": dict(line.get("objs") or [])}
        out: dict[str, dict] = {}

        def path_of(rid: str, depth: int = 0) -> str | None:
            item = metas.get(rid)
            if item is None or depth > 64:
                return None
            if item["parent"] == vid:
                return f"{vault_path}/{item['meta']['n']}"
            up = path_of(item["parent"], depth + 1)
            return None if up is None else f"{up}/{item['meta']['n']}"

        for rid, item in metas.items():
            path = path_of(rid)
            if path is None:
                continue
            meta = item["meta"]
            info = {"path": path, "dir": bool(meta.get("d")), "size": int(meta.get("s", 0)),
                    "mtime": int(meta.get("m", 0)), "raw": True}
            if not meta.get("d") and isinstance(meta.get("o"), str) and isinstance(meta.get("k"), str):
                have = self._raw_size(meta["o"])
                info.update(o=meta["o"], k=meta["k"],
                            done=cs.chunk_count(info["size"]) if have == item["objs"].get(meta["o"]) else 0)
                thumb = (meta.get("v") or {}).get("thumb")
                if isinstance(thumb, dict) and self._raw_size(thumb.get("o", "")) == item["objs"].get(thumb.get("o")):
                    info["t"] = {"o": thumb["o"], "k": thumb["k"], "s": int(thumb["s"]), "raw": True}
            out[rid] = info
        return out

    def _raw_path(self, oid: str) -> Path:
        if not isinstance(oid, str) or not ID_RE.fullmatch(oid):
            raise p.ProtocolError("storage")
        return self.dir / "vobjects" / oid[:2] / oid

    def _raw_size(self, oid: str) -> int:
        try:
            return self._raw_path(oid).stat().st_size
        except (OSError, p.ProtocolError):
            return -1

    def _sync_rows(self, rows: list[Want], fetch_raw, stop: Callable[[], bool]) -> None:
        """잠긴 폴더 안의 줄과 객체 암호문을 받은 그대로 맞춘다(풀지 않는다). 끊기면 받은 데부터."""
        wanted = {w.row: w for w in rows}
        with self._mu:
            for rid in [r for r in self.vrows if r not in wanted]:
                self.vrows.pop(rid)
                self.db.execute("DELETE FROM vrows WHERE id=?", (rid,))
            keep = {o for line in self.vrows.values() for o, _ in line.get("objs") or []}
            keep |= {o for w in rows for o, _ in w.objs}
            raw_dir = self.dir / "vobjects"
            if raw_dir.exists():
                for file in raw_dir.glob("*/*"):
                    if ID_RE.fullmatch(file.name) and file.name not in keep and not file.is_symlink():
                        file.unlink(missing_ok=True)
            for rid, want in wanted.items():
                h = row_hash(want)
                if self.vrows.get(rid, {}).get("h") != h:
                    line = {"vault": want.id, "parent": want.parent, "ring": want.ring, "meta": want.meta,
                            "objs": [list(x) for x in want.objs], "h": h}
                    self.db.execute("INSERT OR REPLACE INTO vrows VALUES(?, ?)", (rid, self._seal(line, "vrow " + rid)))
                    self.vrows[rid] = line
            self._view = None
        if fetch_raw is None:
            return
        for want in rows:
            for oid, size in want.objs:
                if stop():
                    return
                path = self._raw_path(oid)
                have = self._raw_size(oid)
                if have == size:
                    continue
                if have > size:
                    path.unlink(missing_ok=True)
                    have = 0
                have = max(0, have)
                self._check_space(size - have)
                path.parent.mkdir(parents=True, exist_ok=True)
                with open(path, "ab") as out:
                    while have < size:
                        if stop():
                            return
                        data = fetch_raw(oid, have, min(PIECE, size - have))
                        if not data:
                            raise p.ProtocolError("file_incomplete")
                        out.write(data)
                        out.flush()
                        os.fsync(out.fileno())
                        have += len(data)

    def find(self, path: str, size: int | None = None, mtime: int | None = None) -> dict | None:
        """다 받은 사본 중 그 경로의 파일(크기·수정 시각을 주면 같아야 한다). 잠겨 있으면 mirror_locked."""
        for info in self.entries.values():
            if info.get("path") == path and self.complete(info) \
                    and (size is None or info.get("size") == size) and (mtime is None or info.get("mtime") == mtime):
                return info
        return None

    def read(self, info: dict, offset: int = 0, length: int | None = None) -> Iterator[bytes]:
        where = self._raw_path(info["o"]) if info.get("raw") else self._object(info["o"])
        return cs.iter_plain(where, keys.unb64(info["k"]), int(info["size"]), offset, length)

    def read_all(self, info: dict, limit: int) -> bytes:
        if int(info["size"]) > limit:
            raise p.ProtocolError("too_large")
        return b"".join(self.read(info))

    def thumb(self, info: dict) -> bytes | None:
        ref = info.get("t")
        if not isinstance(ref, dict) or not ref.get("o"):
            return None
        try:
            where = self._raw_path(ref["o"]) if ref.get("raw") else self._object(ref["o"])
            return b"".join(cs.iter_plain(where, keys.unb64(ref["k"]), int(ref["s"])))
        except (OSError, p.ProtocolError):
            return None

    def list_dir(self, path: str) -> list[cloud.Item]:
        """사본의 한 폴더(허브 목록과 같은 모양). 폴더는 받은 폴더 줄과 파일 경로의 중간 폴더에서."""
        prefix = path + "/" if path else ""
        files: dict[str, cloud.Item] = {}
        dirs: dict[str, int] = {}
        locked: dict[str, bool] = {}
        for info in self.entries.values():
            full = info.get("path", "")
            if not full.startswith(prefix) or full == path:
                continue
            rest = full[len(prefix):]
            head, sep, _ = rest.partition("/")
            if info.get("vault") and not sep:
                locked[head] = True
                dirs.setdefault(head, 0)
            elif sep or info.get("dir"):
                dirs.setdefault(head, 0)
            elif self.complete(info):
                files[head] = cloud.Item(head, False, int(info["size"]), int(info.get("mtime", 0)),
                                         variants=("thumb",) if info.get("t") else ())
        items = [cloud.Item(name, True, 0, 0, locked=name in locked, state="locked" if name in locked else "")
                 for name in dirs] + [x for name, x in files.items() if name not in dirs]
        items.sort(key=lambda x: (not x.is_dir, x.name.casefold(), x.name))
        return items

    def usage(self) -> tuple[int, int]:
        """(다 받은 파일 수, 그 크기). 잠겨 있어도 장부로 센다."""
        with self._mu:
            done = [x for x in self.book.values()
                    if not x.get("dir") and x.get("done") == cs.chunk_count(int(x.get("size", 0)))]
        return len(done), sum(int(x.get("size", 0)) for x in done)

    # ---------- 파일 ----------
    def _object(self, oid: str) -> Path:
        if not isinstance(oid, str) or not ID_RE.fullmatch(oid):
            raise p.ProtocolError("storage")
        return self.dir / "objects" / oid[:2] / oid

    def _drop(self, hid: str) -> None:
        line = self.book.pop(hid, None) or {}
        for oid in (line.get("o"), line.get("to")):
            if isinstance(oid, str) and ID_RE.fullmatch(oid):
                self._object(oid).unlink(missing_ok=True)
        for table in ("ledger", "names", "fkeys", "thumbs", "vaults"):
            self.db.execute(f"DELETE FROM {table} WHERE id=?", (hid,))
        self._vault_keys.pop(hid, None)
        self._view = None

    # ---------- 맞추기(잠겨 있어도 된다) ----------
    def apply(self, digest: str, wants: list[Want], fetch: Callable[[str, int, int], bytes],
              fetch_thumb: Callable[[str], bytes], stop: Callable[[], bool] = lambda: False,
              progress: Callable[[int, int], None] | None = None, fetch_raw=None) -> int:
        """허브 목록에 맞춘다. 받은 파일 수. 공간이 모자라면 SpaceError(받은 데까지는 남는다).
        fetch_raw(oid, offset, length): 잠긴 폴더 안 객체의 암호문(§19.6 sync_raw)."""
        rows = [w for w in wants if w.row]
        wants = [w for w in wants if not w.row]
        with self._mu:
            todo = plan(self.book, wants, self.path_hash)
            for hid in todo.drop:
                self._drop(hid)
            for want in todo.moves:
                line = dict(self.book.get(want.id) or {})
                line.update({"dir": want.dir, "ph": self.path_hash(want.path)})
                if want.vault is not None:  # §19.6 PIN·복구 코드 봉인을 이 PC의 공개키로 한 번 더 잠가 둔다
                    self._hide("vaults", want.id, want.vault)
                    line.update(vault=True, vh=vault_hash(want))
                if not want.dir:
                    line["mt"] = want.mtime
                    if line.get("tver") != want.tver:
                        self._drop_thumb(want.id, line)
                self._hide("names", want.id, {"path": want.path, "dir": want.dir, "size": want.size,
                                              "mtime": want.mtime})
                self._put_book(want.id, line)
        self._sync_rows(rows, fetch_raw, stop)
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
            line = self.book.get(want.id)
            if line is not None and want.thumb and not line.get("to") \
                    and line.get("done") == cs.chunk_count(want.size) and not want.dir:
                self._fetch_thumb(want, fetch_thumb)
        if not stop():
            with self._mu:
                self.state.update(digest=digest, at=time.time())
                self._save_state()
        return count

    def _drop_thumb(self, hid: str, line: dict) -> None:
        oid = line.pop("to", None)
        line.pop("tver", None)
        if isinstance(oid, str) and ID_RE.fullmatch(oid):
            self._object(oid).unlink(missing_ok=True)
        self.db.execute("DELETE FROM thumbs WHERE id=?", (hid,))

    def _fetch_thumb(self, want: Want, fetch_thumb: Callable[[str], bytes]) -> None:
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
            line = self.book.get(want.id)
            if line is None:
                self._object(oid).unlink(missing_ok=True)
                return
            self._hide("thumbs", want.id, {"o": oid, "k": keys.b64(key), "s": size})
            self._put_book(want.id, {**line, "to": oid, "tver": want.tver})

    def _fetch_one(self, want: Want, fetch: Callable[[str, int, int], bytes], stop: Callable[[], bool]) -> None:
        with self._mu:
            old = self.book.get(want.id)
            resume = old is not None and old.get("ver") == want.ver and isinstance(old.get("pk"), str) \
                and self._object(old.get("o", "")).exists()
            if old is not None and not resume:
                self._drop(want.id)
            line = dict(old) if resume else {"o": os.urandom(16).hex(), "pk": keys.b64(keys.to_bytes(keys.new_keyset())),
                                            "done": 0}
            line.update({"dir": False, "size": want.size, "mt": want.mtime, "ver": want.ver,
                         "ph": self.path_hash(want.path)})
            done = int(line.get("done", 0)) if resume else 0
            self._check_space(want.size - done * cs.CHUNK)
            line["done"] = done
            self._hide("names", want.id, {"path": want.path, "dir": False, "size": want.size, "mtime": want.mtime})
            self._put_book(want.id, line)
        key = keys.unb64(line["pk"])
        writer = cs.ObjectWriter(self._object(line["o"]), key, done)
        try:
            offset = done * cs.CHUNK
            while offset < want.size:
                if stop():
                    writer.sync()
                    self._mark(want.id, line, writer.chunks)
                    return
                data = fetch(want.path, offset, min(PIECE, want.size - offset))
                if not data:
                    raise p.ProtocolError("file_incomplete")
                writer.write(data)
                offset += len(data)
                writer.sync()
                self._mark(want.id, line, writer.chunks)
            if offset != want.size:
                raise p.ProtocolError("file_incomplete")
            writer.finish()
        finally:
            writer.close()
        with self._mu:  # 다 받았다: 파일 열쇠는 공개키로 잠근 것만 남기고 장부에서 지운다
            self._hide("fkeys", want.id, {"k": line["pk"]})
            line = {k: v for k, v in line.items() if k != "pk"}
            line["done"] = cs.chunk_count(want.size)
            self._put_book(want.id, line)

    def _mark(self, hid: str, line: dict, chunks: int) -> None:
        with self._mu:
            line["done"] = chunks
            self._put_book(hid, line)

    def _check_space(self, need: int) -> None:
        try:
            free = shutil.disk_usage(self.dir).free
        except OSError:
            return
        if free < max(0, need) + RESERVE:
            raise SpaceError()

    # ---------- 지우기·보관 ----------
    def _wipe_files(self) -> None:
        if self.dir.exists() and not cs._is_link(self.dir):
            shutil.rmtree(self.dir, ignore_errors=True)

    def wipe(self) -> None:
        """이 사본을 모두 지운다."""
        with self._mu:
            self.close()
            self.book.clear()
            self._view = None
            self._wipe_files()

    def archive(self) -> Path:
        """이전 사본으로 옮긴다(§19.5: 허브의 저장소가 바뀌었거나 페어링을 끊었다). 옮긴 자리."""
        with self._mu:
            self.state["archived"] = time.time()
            self._save_state()
            self.close()
            target = self.dir.with_name(f"{self.dir.name[:16]}.old-{int(time.time())}")
            n = 0
            while target.exists():
                n += 1
                target = self.dir.with_name(f"{self.dir.name[:16]}.old-{int(time.time()) + n}")
            for attempt in range(20):  # 백신 검사 등이 방금 쓴 파일을 잠깐 잡고 있을 수 있다
                try:
                    os.replace(self.dir, target)
                    break
                except PermissionError:
                    if attempt == 19:
                        raise
                    time.sleep(0.1)
            self.dir = target
            return target


def archives(base: Path) -> list[Path]:
    """이전 사본 폴더들(오래된 것부터)."""
    base = Path(base)
    if not base.exists():
        return []
    found = [x for x in base.iterdir() if x.is_dir() and not cs._is_link(x) and FOLDER_RE.fullmatch(x.name)
             and ".old-" in x.name]
    return sorted(found, key=lambda x: x.name.rpartition("-")[2])
