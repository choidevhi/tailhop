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
from typing import BinaryIO, Callable
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


def new_header(kind: str, sender: str, **fields) -> dict:
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


@dataclass
class Received:
    header: dict
    path: Path | None = None


def receive_message(
    ch: Channel,
    guard: ReplayGuard,
    save_dir: Path,
    on_progress: Callable[[int, int], None] | None = None,
) -> Received:
    """헤더와 본문을 받아 검증한다. 파일은 .part로 받은 뒤 검증되면 이름을 바꾼다."""
    raw, last = ch.recv()
    if last:
        raise ProtocolError("no_body")
    try:
        header = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("bad_header") from exc
    if not isinstance(header, dict) or header.get("v") != 1 or header.get("type") not in ("text", "file", "unpair"):
        raise ProtocolError("unsupported")
    guard.check_and_add(header.get("id"), header.get("ts"))

    if header["type"] == "unpair":
        # 상대가 페어링을 취소했다(폰에서 확인 코드를 거절함, §6). 본문 없이 빈 last 프레임 하나.
        body, last = ch.recv()
        if body or not last:
            raise ProtocolError("bad_header")
        return Received(header)

    if header["type"] == "text":
        text = header.get("text")
        if not isinstance(text, str) or len(text.encode("utf-8")) > MAX_TEXT:
            raise ProtocolError("bad_text")
        body, last = ch.recv()
        if body or not last:
            raise ProtocolError("bad_text_format")
        return Received(header)

    size = header.get("size")
    if not isinstance(size, int) or isinstance(size, bool) or not 0 <= size <= MAX_FILE \
            or not isinstance(header.get("name"), str):
        raise ProtocolError("bad_file_info")
    save_dir.mkdir(parents=True, exist_ok=True)
    if free_bytes(save_dir) < size + DISK_RESERVE:
        raise ProtocolError("disk_full")
    name = sanitize_filename(header["name"])
    # .part는 배타적으로 만든다. 다른 수신이 같은 이름을 먼저 잡았으면 다음 번호로(남의 .part는 건드리지 않는다).
    for _ in range(100):
        final = unique_path(save_dir, name)
        part = final.with_name(final.name + ".part")
        try:
            out = open(part, "xb")
            break
        except FileExistsError:
            continue
    else:
        raise ProtocolError("storage")
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


def send_text(ch: Channel, sender: str, text: str) -> None:
    if len(text.encode("utf-8")) > MAX_TEXT:
        raise ValueError(i18n.t("err.text_too_long"))
    ch.send(json.dumps(new_header("text", sender, text=text)).encode("utf-8"), last=False)
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
        raise ValueError(i18n.t("err.file_changed"))


def read_reply(ch: Channel) -> dict:
    """응답을 읽는다. 거부면 ProtocolError: 아는 code면 그 코드로(화면 언어로 번역), 모르면 상대 문장 그대로."""
    raw, last = ch.recv()
    if not last:
        raise ProtocolError("bad_reply")
    try:
        reply = json.loads(bytes(raw).decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("bad_reply") from exc
    if not isinstance(reply, dict):
        raise ProtocolError("bad_reply")
    if not reply.get("ok"):
        code = reply.get("code")
        if isinstance(code, str) and re.fullmatch(r"[a-z_]{1,40}", code) and i18n.has("err." + code):
            raise ProtocolError(code)
        reason = str(reply.get("error") or "")[:200]
        raise ProtocolError("peer_refused_reason", reason=reason) if reason else ProtocolError("peer_refused")
    return reply


def send_reply(ch: Channel, ok: bool, error: ProtocolError | None = None, **extra) -> None:
    """응답 한 프레임. 거부면 예전 버전용 한국어 문장("error")과 고정 코드("code")를 함께 보낸다."""
    body = {"ok": ok, **extra}
    if not ok:
        err = error or ProtocolError("peer_refused")
        body["error"] = err.wire_text()
        body["code"] = err.code
    ch.send(json.dumps(body).encode("utf-8"), last=True)
