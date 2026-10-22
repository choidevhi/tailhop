"""TailHop 프로토콜 v1 (docs/PROTOCOL.md) 구현: 암호화 프레임, 헤더 검사, 파일명 정리."""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
import shutil
import socket
import struct
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Callable, Iterator
from urllib.parse import quote, unquote

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

import i18n

MAGIC = b"THP1"
KIND_PAIR = 0x01
KIND_MSG = 0x02
PREFIX_LEN = 22
CHUNK = 1024 * 1024
TAG = 16
MAX_FRAME = CHUNK + TAG
MAX_TEXT = CHUNK
MAX_FILE = 4 * 1024 ** 3
MAX_SKEW_MS = 300_000
SOCKET_TIMEOUT = 30
HANDSHAKE_TIMEOUT = 5  # 인증 전(머리·첫 프레임) 무응답 한도
DISK_RESERVE = 64 * 1024 * 1024  # 파일을 받은 뒤에도 남겨 둘 디스크 여유

INFO_PHONE_TO_PC = b"tailhop v1 phone->pc"
INFO_PC_TO_PHONE = b"tailhop v1 pc->phone"
INFO_PAIR = b"tailhop v1 pair"
# PC ↔ PC: 코드를 보여 준 쪽(host)과 입력한 쪽(joiner)으로 방향을 나눈다.
INFO_PC_HOST_TO_JOINER = b"tailhop v1 pc-host->pc-joiner"
INFO_PC_JOINER_TO_HOST = b"tailhop v1 pc-joiner->pc-host"

KIND_PHONE = "phone"
KIND_PC = "pc"
ROLE_HOST = "host"
ROLE_JOINER = "joiner"

PAIR_URI_HEAD = "tailhop://pair?"
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # Crockford base32 (I, L, O, U 없음)
DEFAULT_PC_PORT = 47100

# Windows 장치 이름(확장자·뒤 공백이 붙어도 장치로 열린다). 위첨자 숫자와 0번도 포함.
_RESERVED = {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
             *(f"{d}{i}" for d in ("COM", "LPT") for i in (*"0123456789", "\u00b9", "\u00b2", "\u00b3"))}
# 화면에서 글자 순서를 바꾸거나 보이지 않는 문자(bidi 제어, 폭 없는 공백 등)와 C0·C1 제어문자.
_INVISIBLE = "\u0000-\u001f\u007f-\u009f\u061c\u200b\u200e\u200f\u202a-\u202e\u2060-\u2064\u2066-\u206f\ufeff"
_FILENAME_BAD = re.compile(f'[{_INVISIBLE}<>:"/\\\\|?*]')
_NAME_BAD = re.compile(f"[{_INVISIBLE}]")


class FileChanged(ValueError):
    """보내는 사이 파일이 바뀌었다(크기). 보낼 곳 목록은 새 xfer로 처음부터 다시 보낸다."""

    def __init__(self) -> None:
        super().__init__(i18n.t("err.file_changed"))


class ProtocolError(Exception):
    """상대가 규칙을 어겼거나 인증에 실패했다.

    code: 언어와 상관없는 고정 코드(locales의 "err.<code>"). str()은 지금 화면 언어 문장,
    wire_text()는 상대에게 보내는 한국어 문장(1.4 이하·Android 1.3 이하가 그대로 보여 준다)."""

    def __init__(self, code: str, **kw) -> None:
        super().__init__(code)
        self.code = code
        self.kw = kw

    def __str__(self) -> str:
        return i18n.t("err." + self.code, **self.kw)

    def wire_text(self) -> str:
        return i18n.text(i18n.WIRE, "err." + self.code, **self.kw)


def b64u_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64u_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def derive_key(ikm: bytes, info: bytes) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=bytes(32), info=info).derive(ikm)


def session_key(dir_key: bytes, salt: bytes) -> bytes:
    return hmac.new(dir_key, salt, hashlib.sha256).digest()


def confirm_code(secret: bytes) -> str:
    value = struct.unpack(">I", hashlib.sha256(secret).digest()[:4])[0]
    return f"{value % 1_000_000:06d}"


def direction_infos(kind: str, role: str = "") -> tuple[bytes, bytes]:
    """이 PC가 상대에게 (보낼 때, 받을 때) 쓰는 HKDF info. 두 방향이 같은 키를 쓰는 일은 없다."""
    if kind == KIND_PHONE:
        return INFO_PC_TO_PHONE, INFO_PHONE_TO_PC
    if kind == KIND_PC and role == ROLE_HOST:
        return INFO_PC_HOST_TO_JOINER, INFO_PC_JOINER_TO_HOST
    if kind == KIND_PC and role == ROLE_JOINER:
        return INFO_PC_JOINER_TO_HOST, INFO_PC_HOST_TO_JOINER
    raise ValueError(f"unknown device kind: {kind}/{role}")


# ---------- 페어링 링크·코드 ----------
@dataclass(frozen=True)
class PairInfo:
    host: str
    port: int
    name: str
    key: bytes


def _b32_encode(data: bytes) -> str:
    bits = int.from_bytes(data, "big")
    count = -(-len(data) * 8 // 5)
    bits <<= count * 5 - len(data) * 8
    return "".join(CODE_ALPHABET[(bits >> (5 * (count - 1 - i))) & 31] for i in range(count))


def _b32_decode(text: str, size: int) -> bytes:
    text = text.upper().translate(str.maketrans("OIL", "011"))
    count = -(-size * 8 // 5)
    if len(text) != count or any(c not in CODE_ALPHABET for c in text):
        raise ProtocolError("code_chars")
    bits = 0
    for c in text:
        bits = (bits << 5) | CODE_ALPHABET.index(c)
    pad = count * 5 - size * 8
    if bits & ((1 << pad) - 1):
        raise ProtocolError("code_bad")
    return (bits >> pad).to_bytes(size, "big")


def _code_check(host: str, port: int, key: bytes) -> bytes:
    return hashlib.sha256(b"tailhop pair code|" + f"{host}:{port}|".encode() + key).digest()[:2]


def pair_uri(host: str, port: int, name: str, key: bytes) -> str:
    return f"{PAIR_URI_HEAD}v=1&h={host}&p={port}&n={quote(name)}&k={b64u_encode(key)}"


def pair_code(host: str, port: int, key: bytes) -> str:
    """손으로 칠 수 있는 형태: `100.x.y.z-XXXXX-XXXXX-…` (키 32바이트 + 오타 검사 2바이트, 대소문자 무관)."""
    body = _b32_encode(key + _code_check(host, port, key))
    where = host if port == DEFAULT_PC_PORT else f"{host}:{port}"
    return where + "-" + "-".join(body[i:i + 5] for i in range(0, len(body), 5))


def _check_ipv4(host: str) -> str:
    try:
        addr = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ProtocolError("addr_bad") from exc
    if addr.version != 4:
        raise ProtocolError("addr_bad")
    return str(addr)


def parse_pair_input(text: str) -> PairInfo:
    """페어링 링크(tailhop://pair?…) 또는 짧은 코드를 엄격하게 읽는다. 실패하면 ProtocolError."""
    text = text.strip()
    if text.lower().startswith(PAIR_URI_HEAD):
        params: dict[str, str] = {}
        for part in text[len(PAIR_URI_HEAD):].split("&"):
            k, sep, v = part.partition("=")
            if not sep or k not in ("v", "h", "p", "n", "k") or k in params:
                raise ProtocolError("link_bad")
            params[k] = unquote(v)
        if params.get("v") != "1" or set(params) != {"v", "h", "p", "n", "k"}:
            raise ProtocolError("link_unsupported")
        if not params["p"].isdigit() or not 1 <= int(params["p"]) <= 65535:
            raise ProtocolError("port_bad")
        try:
            key = b64u_decode(params["k"])
        except ValueError as exc:
            raise ProtocolError("key_bad") from exc
        if len(key) != 32:
            raise ProtocolError("key_bad")
        return PairInfo(_check_ipv4(params["h"]), int(params["p"]), params["n"][:64], key)

    match = re.fullmatch(r"(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?[\s-]+([0-9A-Za-z\s-]+)", text)
    if not match:
        raise ProtocolError("not_link_or_code")
    host = _check_ipv4(match.group(1))
    port = int(match.group(2) or DEFAULT_PC_PORT)
    if not 1 <= port <= 65535:
        raise ProtocolError("port_bad")
    raw = _b32_decode(re.sub(r"[\s-]", "", match.group(3)), 34)
    key, check = raw[:32], raw[32:]
    if not hmac.compare_digest(check, _code_check(host, port, key)):
        raise ProtocolError("code_typo")
    return PairInfo(host, port, "", key)


def nonce(direction: int, index: int, last: bool) -> bytes:
    return bytes([direction]) + index.to_bytes(10, "big") + bytes([1 if last else 0])


def _recv_exact(sock: socket.socket, n: int) -> bytearray:
    """n바이트를 미리 잡은 버퍼 하나에 바로 받는다(조각을 이어 붙이고 다시 복사하지 않음)."""
    buf = bytearray(n)
    view = memoryview(buf)
    got = 0
    while got < n:
        part = sock.recv_into(view[got:], n - got)
        if not part:
            raise ProtocolError("disconnected")
        got += part
    return buf


class Channel:
    """한 연결의 양방향 암호 프레임. 클라이언트는 dir 0으로 보내고 1로 받는다."""

    def __init__(self, sock: socket.socket, key: bytes, prefix: bytes, is_client: bool) -> None:
        self.sock = sock
        self.aead = AESGCM(key)
        self.prefix = prefix
        self.send_dir = 0 if is_client else 1
        self.recv_dir = 1 if is_client else 0
        self.send_index = 0
        self.recv_index = 0
        self.recv_done = False

    def send(self, plaintext: bytes, last: bool) -> None:
        sealed = self.aead.encrypt(nonce(self.send_dir, self.send_index, last), plaintext, self.prefix)
        self.send_index += 1
        self.sock.sendall(struct.pack(">I", len(sealed)) + sealed)

    def recv(self) -> tuple[bytes, bool]:
        if self.recv_done:
            raise ProtocolError("after_last")
        (length,) = struct.unpack(">I", _recv_exact(self.sock, 4))
        if not TAG <= length <= MAX_FRAME:
            raise ProtocolError("bad_frame_len")
        sealed = _recv_exact(self.sock, length)
        # last 플래그는 nonce에 들어가므로 둘 다 시도해 맞는 쪽을 쓴다.
        for last in (False, True):
            try:
                plain = self.aead.decrypt(nonce(self.recv_dir, self.recv_index, last), sealed, self.prefix)
            except Exception:
                continue
            self.recv_index += 1
            self.recv_done = last
            return plain, last
        raise ProtocolError("auth_failed")


def open_client(sock: socket.socket, kind: int, dir_key: bytes, salt: bytes | None = None) -> Channel:
    salt = salt or os.urandom(16)
    prefix = MAGIC + bytes([kind]) + salt + b"\x00"
    sock.sendall(prefix)
    return Channel(sock, session_key(dir_key, salt), prefix, is_client=True)


def read_prefix(sock: socket.socket) -> tuple[int, bytes]:
    prefix = bytes(_recv_exact(sock, PREFIX_LEN))
    if prefix[:4] != MAGIC or prefix[4] not in (KIND_PAIR, KIND_MSG) or prefix[21] != 0:
        raise ProtocolError("not_tailhop")
    return prefix[4], prefix


def open_server(sock: socket.socket, prefix: bytes, dir_key: bytes) -> Channel:
    return Channel(sock, session_key(dir_key, prefix[5:21]), prefix, is_client=False)


def new_header(kind: str, sender: str, /, **fields) -> dict:
    return {"v": 1, "type": kind, "id": os.urandom(16).hex(), "ts": int(time.time() * 1000), "from": sender, **fields}


class ReplayGuard:
    """최근 10분 안에 본 메시지 id를 디스크에 남겨 재전송을 막는다."""

    WINDOW_MS = 600_000

    def __init__(self, path: Path) -> None:
        self.path = path
        self.seen: dict[str, int] = {}
        # 연결마다 스레드가 따로 돌므로 검사·추가·저장을 한 번에 한 스레드만 한다.
        self.lock = threading.Lock()
        try:
            self.seen = {k: int(v) for k, v in json.loads(path.read_text("utf-8")).items()}
        except (OSError, ValueError, AttributeError, TypeError):
            pass

    def check_and_add(self, msg_id: str, ts: int, now_ms: int | None = None) -> None:
        now = now_ms if now_ms is not None else int(time.time() * 1000)
        if not isinstance(ts, int) or isinstance(ts, bool) or abs(ts - now) > MAX_SKEW_MS:
            raise ProtocolError("clock_skew")
        if not isinstance(msg_id, str) or not re.fullmatch(r"[0-9a-f]{32}", msg_id):
            raise ProtocolError("bad_id")
        with self.lock:
            self.seen = {k: v for k, v in self.seen.items() if now - v <= self.WINDOW_MS}
            if msg_id in self.seen:
                raise ProtocolError("replay")
            self.seen[msg_id] = now
            # 임시 파일에 쓰고 바꿔 끼운다(쓰다 멈춰도 seen.json이 반쯤 남지 않는다).
            tmp = self.path.with_name(self.path.name + ".tmp")
            try:
                tmp.write_text(json.dumps(self.seen), "utf-8")
                os.replace(tmp, self.path)
            except OSError:
                pass


def clean_name(name: object, limit: int = 64) -> str:
    """상대가 보낸 기기 이름: 제어문자·bidi 등 보이지 않는 문자를 지우고 앞뒤 공백을 자른 뒤 limit 글자까지."""
    return _NAME_BAD.sub("", str(name)).strip()[:limit]


def is_reserved_name(name: str) -> bool:
    """Windows 장치 이름인지: 첫 `.` 앞부분(뒤 공백 무시, 대소문자 무관). 예: `con`, `COM1 .txt`, `nul.tar.gz`."""
    return name.split(".")[0].rstrip(" ").upper() in _RESERVED


def sanitize_filename(name: str) -> str:
    name = re.split(r"[/\\]", str(name))[-1]
    name = _FILENAME_BAD.sub("_", name)
    name = name.lstrip(".").rstrip(" .")
    if len(name) > 150:
        stem, dot, ext = name.rpartition(".")
        if dot and 0 < len(ext) <= 16:
            name = stem[: 150 - len(ext) - 1] + "." + ext
        else:
            name = name[:150]
        name = name.rstrip(" .")
    if not name:
        name = "file"
    if is_reserved_name(name):
        name = "_" + name
    return name


def free_bytes(folder: Path) -> int:
    """folder가 있는 디스크의 남은 바이트(테스트에서 바꿔 끼울 수 있게 함수로 둔다)."""
    return shutil.disk_usage(folder).free


def unique_path(folder: Path, name: str) -> Path:
    path = folder / name
    stem, suffix = path.stem, path.suffix
    n = 1
    while path.exists() or path.with_name(path.name + ".part").exists():
        path = folder / f"{stem} ({n}){suffix}"
        n += 1
    return path


_XFER_RE = re.compile(r"[0-9a-f]{32}")
_SHA_RE = re.compile(r"[0-9a-f]{64}")


def _valid_xfer(value: object) -> bool:
    return isinstance(value, str) and _XFER_RE.fullmatch(value) is not None


def new_xfer() -> str:
    return os.urandom(16).hex()


def _open_part(folder: Path, name: str) -> tuple[Path, Path, BinaryIO]:
    """(최종 경로, .part 경로, 열린 .part). .part는 배타적으로 만든다.
    다른 수신이 같은 이름을 먼저 잡았으면 다음 번호로(남의 .part는 건드리지 않는다)."""
    for _ in range(100):
        final = unique_path(folder, name)
        part = final.with_name(final.name + ".part")
        try:
            return final, part, open(part, "xb")
        except FileExistsError:
            continue
    raise ProtocolError("storage")


def _hash_prefix(path: Path, length: int) -> "hashlib._Hash":
    """파일 앞 length 바이트의 SHA-256 진행 상태(이어받기·이어 보내기 때 이미 있는 부분)."""
    digest = hashlib.sha256()
    left = length
    with open(path, "rb") as src:
        while left > 0:
            block = src.read(min(CHUNK, left))
            if not block:
                raise ProtocolError("resume_lost")
            digest.update(block)
            left -= len(block)
    return digest


class _NetDrop(Exception):
    """받는 중 연결이 끊기거나 시간 초과(조각을 남겨 둘 실패)."""


def _recv_data(ch: Channel) -> tuple[bytes, bool]:
    try:
        return ch.recv()
    except ProtocolError as exc:
        if exc.code == "disconnected":
            raise _NetDrop() from exc
        raise
    except OSError as exc:  # 시간 초과·연결 리셋
        raise _NetDrop() from exc


CLOUD_NAME_MAX = 140  # 클라우드에 둘 이름의 길이. 뒤에 `_`나 ` (12)`가 붙어도 150자를 넘지 않게(넘으면 목록에서 사라진다)


def cloud_name(name: str) -> str:
    """클라우드 폴더에 저장할 이름(§12.2): `.part`로 끝나면 `_`를 붙이고, 140자로 미리 줄인다(확장자는 남긴다).
    그래야 같은 이름이 있어 번호가 붙어도 §12.1 규칙(정리해도 그대로인 이름)을 지켜 모든 기기에서 보이고 지울 수 있다."""
    if name.casefold().endswith(".part"):
        name += "_"
    if len(name) > CLOUD_NAME_MAX:
        stem, dot, ext = name.rpartition(".")
        if dot and 0 < len(ext) <= 16:
            name = stem[: CLOUD_NAME_MAX - len(ext) - 1].rstrip(" .") + "." + ext
        else:
            name = name[:CLOUD_NAME_MAX].rstrip(" .")
    return name or "file"


def _receive_xfer_file(ch: Channel, header: dict, save_dir: Path, on_progress, peer: str, book) -> Path:
    """§11.2: offset부터 받아 이어 쓰고, 트레일러의 SHA-256으로 파일 전체를 확인한다.
    끊기면 조각을 남기고(book.keep), 규칙 위반·해시 불일치면 지운다(book.drop)."""
    size, xfer, offset = header["size"], header["xfer"], header.get("offset", 0)
    if not _is_int(offset) or not 0 <= offset <= size:
        raise ProtocolError("bad_xfer")
    if free_bytes(save_dir) < size - offset + DISK_RESERVE:
        raise ProtocolError("disk_full")

    kind = header["type"]
    if offset > 0:
        entry = book.claim(peer, xfer, size, offset, kind, save_dir)
        if entry is None:
            raise ProtocolError("resume_lost")
        final, part = entry.final, entry.part
        try:
            out = open(part, "r+b")
            out.truncate(offset)
            out.seek(offset)
            digest = _hash_prefix(part, offset)
        except (OSError, ProtocolError) as exc:
            book.drop(peer, xfer, part)
            raise ProtocolError("resume_lost") from exc
    else:
        final, part, out = _open_part(save_dir, sanitize_filename(header["name"]))
        book.begin(peer, xfer, size, part, final, kind)
        digest = hashlib.sha256()

    got = offset
    try:
        with out:
            while True:
                data, last = _recv_data(ch)
                if last:
                    trailer = data
                    break
                if not data:
                    raise ProtocolError("bad_xfer")
                got += len(data)
                if got > size:
                    raise ProtocolError("file_too_big")
                out.write(data)
                digest.update(data)
                if on_progress:
                    on_progress(got, size)
        if got != size:
            raise ProtocolError("file_incomplete")
        try:
            sha = json.loads(bytes(trailer).decode("utf-8")).get("sha256")
        except (UnicodeDecodeError, ValueError, AttributeError) as exc:
            raise ProtocolError("bad_xfer") from exc
        if not isinstance(sha, str) or not _SHA_RE.fullmatch(sha):
            raise ProtocolError("bad_xfer")
        if not hmac.compare_digest(sha, digest.hexdigest()):
            raise ProtocolError("hash_mismatch")
        final = unique_path(save_dir, final.name) if final.exists() else final
        part.rename(final)
    except _NetDrop as exc:
        book.keep(peer, xfer, part)
        raise ProtocolError("disconnected") from exc
    except BaseException:
        book.drop(peer, xfer, part)
        raise
    book.finish(peer, xfer)
    return final


def _receive_into_store(ch: Channel, header: dict, target, on_progress, peer: str, book) -> str:
    """§17: 암호화 저장소로 바로 받는다(평문 조각을 디스크에 두지 않는다). xfer가 있으면 청크 단위로 이어 받고
    트레일러의 SHA-256으로 확인한다. 끊기면 다 쓴 청크까지 남긴다. 저장된 이름을 돌려준다."""
    size, xfer, offset = header["size"], header.get("xfer"), header.get("offset", 0)
    if not _is_int(offset) or not 0 <= offset <= size or (offset and xfer is None):
        raise ProtocolError("bad_xfer")
    if free_bytes(target.store.root) < size - offset + DISK_RESERVE:
        raise ProtocolError("disk_full")
    if offset:
        up = target.resume(peer, xfer, size, offset)
    else:
        name = cloud_name(sanitize_filename(header["name"]))
        now = int(time.time() * 1000)
        mtime = header.get("mtime")  # §19.5 허브로 복원: 원래 수정 시각(없거나 이상하면 지금)
        mtime = mtime if _is_int(mtime) and 0 < mtime <= now + 86_400_000 else now
        up = target.begin(name, size, mtime, peer, xfer or new_xfer())
    got = offset
    try:
        while True:
            data, last = _recv_data(ch)
            if last and xfer is not None:
                trailer = data
                break
            if not data and not last:
                raise ProtocolError("bad_xfer")
            got += len(data)
            if got > size:
                raise ProtocolError("file_too_big")
            up.write(data)
            if on_progress:
                on_progress(got, size)
            if last:
                break
        if got != size:
            raise ProtocolError("file_incomplete")
        if xfer is not None:
            try:
                sha = json.loads(bytes(trailer).decode("utf-8")).get("sha256")
            except (UnicodeDecodeError, ValueError, AttributeError) as exc:
                raise ProtocolError("bad_xfer") from exc
            if not isinstance(sha, str) or not _SHA_RE.fullmatch(sha):
                raise ProtocolError("bad_xfer")
            if not hmac.compare_digest(sha, up.digest.hexdigest()):
                raise ProtocolError("hash_mismatch")
        stored = up.commit(unique=True)  # 같은 이름이 있으면 번호(§12.2)
    except _NetDrop as exc:
        if xfer is not None:
            up.keep()
        else:
            up.abort()
        raise ProtocolError("disconnected") from exc
    except BaseException:
        up.abort()
        raise
    if xfer is not None:
        book.finish(peer, xfer)
    return stored


@dataclass
class Received:
    header: dict
    path: Path | None = None
    dup: bool = False  # 이미 받은 xfer를 다시 받음(응답이 사라져 상대가 다시 보냄). 기록·알림하지 않는다
    stored: str | None = None  # 암호화 저장소에 넣은 이름(cloud_put·모으기, §17)


# 본문 없이 헤더 뒤 빈 last 프레임 하나만 오는 메시지(§4 unpair, §11 hello, §12 클라우드 요청)
CLOUD_REQUESTS = ("cloud_list", "cloud_get", "cloud_del", "cloud_mkdir", "cloud_thumb", "cloud_search",
                  "cloud_lock", "cloud_open", "cloud_unlock", "cloud_repin", "cloud_move", "cloud_rename",
                  "sync_set", "sync_manifest", "sync_raw")
STORE_REQUESTS = ("store_unlock", "store_lock")  # 저장소가 잠겨 있어도 받는다(§18.3)
CLOUD_TYPES = CLOUD_REQUESTS + ("cloud_put",)
_EMPTY_BODY = ("unpair", "hello", "update_get") + CLOUD_REQUESTS + STORE_REQUESTS


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _recv_empty_end(ch: Channel, code: str = "bad_header") -> None:
    body, last = ch.recv()
    if body or not last:
        raise ProtocolError(code)


def receive_message(
    ch: Channel,
    guard: ReplayGuard,
    save_dir: Path,
    on_progress: Callable[[int, int], None] | None = None,
    peer: str = "",
    book=None,
    cloud=None,
    collect=None,
    cloud_off: str = "cloud_off",
) -> Received:
    """헤더와 본문을 받아 검증한다. 파일은 .part로 받은 뒤 검증되면 이름을 바꾼다.

    peer·book(resume.ResumeBook): §11 확장(hello, xfer 이어받기·중복 무시)을 쓸 때. book이 없으면 예전 v1만 받는다.
    cloud(hub.HubAccess): 클라우드 저장소가 열려 있을 때(§12, §17). 없으면 클라우드 요청은 cloud_off(또는 그 이유)로 거부한다.
    collect(hub.StoreTarget): "받은 파일도 클라우드에 모으기"면 대화방 파일을 저장소 맨 위로 받는다.
    클라우드 요청(목록·가져오기·지우기·폴더)은 헤더만 검사해 돌려주고, 답은 부른 쪽이 만든다."""
    raw, last = ch.recv()
    if last:
        raise ProtocolError("no_body")
    try:
        header = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("bad_header") from exc
    known = ("text", "file", "unpair") + (("hello", "update_get") + CLOUD_TYPES + STORE_REQUESTS
                                           if book is not None else ())
    if not isinstance(header, dict) or header.get("v") != 1 or header.get("type") not in known:
        raise ProtocolError("unsupported")
    guard.check_and_add(header.get("id"), header.get("ts"))
    if header["type"] in CLOUD_TYPES and cloud is None:
        raise ProtocolError(cloud_off)

    if header["type"] in _EMPTY_BODY:
        # unpair: 상대가 페어링을 취소했다(§6). hello: 능력·이어받기 위치 확인(§11.1). 응답은 부른 쪽이 만든다.
        _recv_empty_end(ch)
        return Received(header)

    xfer = header.get("xfer")
    if xfer is not None and (book is None or not _valid_xfer(xfer)):
        raise ProtocolError("bad_xfer")

    if header["type"] == "text":
        text = header.get("text")
        if not isinstance(text, str) or len(text.encode("utf-8")) > MAX_TEXT:
            raise ProtocolError("bad_text")
        _recv_empty_end(ch, "bad_text_format")
        return Received(header, dup=xfer is not None and book.is_done(peer, xfer))

    size = header.get("size")
    if not _is_int(size) or not 0 <= size <= MAX_FILE or not isinstance(header.get("name"), str):
        raise ProtocolError("bad_file_info")
    if header["type"] == "cloud_put":
        if xfer is None:
            raise ProtocolError("bad_xfer")  # 클라우드에 올리기는 이어받기·트레일러가 필수다(§12.2)
        target = cloud.put_target(header.get("dir", ""), header)
        return Received(header, stored=_receive_into_store(ch, header, target, on_progress, peer, book))
    if collect is not None:
        return Received(header, stored=_receive_into_store(ch, header, collect, on_progress, peer, book))
    save_dir.mkdir(parents=True, exist_ok=True)
    if xfer is not None:
        return Received(header, _receive_xfer_file(ch, header, save_dir, on_progress, peer, book))

    if free_bytes(save_dir) < size + DISK_RESERVE:
        raise ProtocolError("disk_full")
    name = sanitize_filename(header["name"])
    final, part, out = _open_part(save_dir, name)
    got = 0
    try:
        with out:
            while True:
                data, last = ch.recv()
                got += len(data)
                if got > size:
                    raise ProtocolError("file_too_big")
                out.write(data)
                if on_progress:
                    on_progress(got, size)
                if last:
                    break
        if got != size:
            raise ProtocolError("file_incomplete")
        final = unique_path(save_dir, final.name) if final.exists() else final
        part.rename(final)
    except BaseException:
        part.unlink(missing_ok=True)
        raise
    return Received(header, final)


def send_hello(ch: Channel, sender: str, xfer: str | None = None, size: int | None = None, **extra) -> dict:
    """§11.1: 상대의 능력(caps)과, xfer를 주면 이어 보낼 위치(have)·이미 끝났는지(done)를 묻는다.
    extra: 이 기기의 용량(space)·허브의 동기화 값(sync, §19). 예전 기기는 unsupported로 거부한다(ProtocolError)."""
    fields = dict(extra) if xfer is None else {"xfer": xfer, "size": size, **extra}
    ch.send(json.dumps(new_header("hello", sender, **fields)).encode("utf-8"), last=False)
    ch.send(b"", last=True)
    return read_reply(ch)


def send_text(ch: Channel, sender: str, text: str, xfer: str | None = None) -> None:
    if len(text.encode("utf-8")) > MAX_TEXT:
        raise ValueError(i18n.t("err.text_too_long"))
    fields = {"text": text} if xfer is None else {"text": text, "xfer": xfer}
    ch.send(json.dumps(new_header("text", sender, **fields)).encode("utf-8"), last=False)
    ch.send(b"", last=True)


def send_file(ch: Channel, sender: str, path: Path, on_progress: Callable[[int, int], None] | None = None) -> None:
    size = path.stat().st_size
    if size > MAX_FILE:
        raise ValueError(i18n.t("err.file_too_large"))
    ch.send(json.dumps(new_header("file", sender, name=path.name, size=size)).encode("utf-8"), last=False)
    sent = 0
    with open(path, "rb") as src:
        chunk = src.read(CHUNK)
        while True:
            nxt = src.read(CHUNK) if chunk else b""
            sent += len(chunk)
            ch.send(chunk, last=not nxt)
            if on_progress:
                on_progress(sent, size)
            if not nxt:
                break
            chunk = nxt
    if sent != size:
        raise FileChanged()


def send_file_xfer(ch: Channel, sender: str, path: Path, xfer: str, offset: int = 0,
                   on_progress: Callable[[int, int], None] | None = None, kind: str = "file", **fields) -> None:
    """§11.2: offset부터 데이터 프레임(last=0)을 보내고 끝에 파일 전체의 SHA-256 트레일러(last=1)를 붙인다.
    이미 보낸 앞부분(offset)은 이 PC에서 다시 읽어 해시만 이어 간다. 보내는 중 파일이 바뀌면 ValueError."""
    size = path.stat().st_size
    if size > MAX_FILE:
        raise ValueError(i18n.t("err.file_too_large"))
    if not 0 <= offset <= size:
        raise FileChanged()
    try:
        digest = _hash_prefix(path, offset)
    except ProtocolError as exc:
        raise FileChanged() from exc
    header = new_header(kind, sender, name=path.name, size=size, xfer=xfer, offset=offset, **fields)
    ch.send(json.dumps(header).encode("utf-8"), last=False)
    sent = offset
    with open(path, "rb") as src:
        src.seek(offset)
        while True:
            block = src.read(CHUNK)
            if not block:
                break
            sent += len(block)
            if sent > size:  # 보내는 사이 커졌다: last 없이 끊어 상대가 조각만 남기게 한다
                raise FileChanged()
            digest.update(block)
            ch.send(block, last=False)
            if on_progress:
                on_progress(sent, size)
    if sent != size:
        raise FileChanged()
    ch.send(json.dumps({"sha256": digest.hexdigest()}).encode("utf-8"), last=True)


def send_stream_xfer(ch: Channel, sender: str, name: str, size: int, xfer: str,
                     read: Callable[[int, int], Iterator[bytes]], kind: str = "cloud_put", **fields) -> None:
    """send_file_xfer와 같은 모양으로, 파일 대신 read(offset, length)에서 흘려 보낸다(§19.5 사본을 허브로 복원:
    평문을 디스크에 쓰지 않는다). 처음부터 보낸다."""
    if not 0 <= size <= MAX_FILE:
        raise ValueError(i18n.t("err.file_too_large"))
    header = new_header(kind, sender, name=name, size=size, xfer=xfer, offset=0, **fields)
    ch.send(json.dumps(header).encode("utf-8"), last=False)
    digest = hashlib.sha256()
    sent = 0
    for block in read(0, size):
        for start in range(0, len(block), CHUNK):
            piece = block[start:start + CHUNK]
            sent += len(piece)
            if sent > size:
                raise FileChanged()
            digest.update(piece)
            ch.send(piece, last=False)
    if sent != size:
        raise FileChanged()
    ch.send(json.dumps({"sha256": digest.hexdigest()}).encode("utf-8"), last=True)


def read_reply(ch: Channel) -> dict:
    """응답을 읽는다. 거부면 ProtocolError: 아는 code면 그 코드로(화면 언어로 번역), 모르면 상대 문장 그대로."""
    raw, last = ch.recv()
    if not last:
        raise ProtocolError("bad_reply")
    return parse_reply(raw)


def parse_reply(raw: bytes, last_ok: bool = True) -> dict:
    """응답 프레임 하나를 읽는다. last_ok=False면 응답 뒤에 데이터가 이어지는 경우(§12.2 cloud_get의 첫 프레임)로,
    성공 응답만 받아들인다(거부는 늘 last=1 한 프레임)."""
    if not last_ok:
        reply = _reply_json(raw)
        if not reply.get("ok"):
            raise ProtocolError("bad_reply")
        return reply
    reply = _reply_json(raw)
    if not reply.get("ok"):
        code = reply.get("code")
        if isinstance(code, str) and re.fullmatch(r"[a-z_]{1,40}", code) and i18n.has("err." + code):
            raise ProtocolError(code)
        reason = str(reply.get("error") or "")[:200]
        raise ProtocolError("peer_refused_reason", reason=reason) if reason else ProtocolError("peer_refused")
    return reply


def _reply_json(raw: bytes) -> dict:
    try:
        reply = json.loads(bytes(raw).decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("bad_reply") from exc
    if not isinstance(reply, dict):
        raise ProtocolError("bad_reply")
    return reply


def send_reply(ch: Channel, ok: bool, error: ProtocolError | None = None, **extra) -> None:
    """응답 한 프레임. 거부면 예전 버전용 한국어 문장("error")과 고정 코드("code")를 함께 보낸다."""
    body = {"ok": ok, **extra}
    if not ok:
        err = error or ProtocolError("peer_refused")
        body["error"] = err.wire_text()
        body["code"] = err.code
    ch.send(json.dumps(body).encode("utf-8"), last=True)
