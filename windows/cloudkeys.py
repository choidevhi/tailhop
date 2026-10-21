"""암호화 클라우드의 열쇠(2.2.0, docs/PROTOCOL.md §17.1). 암호 계산은 모두 검증된 라이브러리에 맡긴다.

- 열쇠는 Google Tink의 AEAD 키 묶음(AES-256-GCM)이다. 저장소에 하나(main), 잠긴 폴더(PIN)마다 하나,
  파일(객체)마다 하나. 파일 열쇠는 그 파일의 메타(이름·크기 등)와 함께 폴더 열쇠로 암호화해 둔다(봉투 암호화).
- 키 묶음을 디스크에 둘 때는 Tink 표준 '암호화된 키 묶음'(proto_keyset_format.serialize_encrypted)만 쓴다.
  감싸는 쪽(keyset_encryption_aead)은 세 가지다.
  - 이 PC의 Windows 계정: DPAPI(CryptProtectData, 연관 데이터를 엔트로피로) — 평소 자동으로 연다.
  - 비밀번호·PIN·복구 코드: Argon2id(cryptography, RFC 9106)로 만든 32바이트 키의 AES-256-GCM(cryptography).
- 복구 코드는 20바이트 난수(Crockford base32, 4글자씩 끊음). 화면에 한 번만 보인다.
"""
from __future__ import annotations

import base64
import os

import tink
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
from tink import aead, proto_keyset_format, secret_key_access

import store

aead.register()

TEMPLATE = aead.aead_key_templates.AES256_GCM
OVERHEAD = 33  # Tink AES-GCM 암호문 = 키 표시 5 + nonce 12 + 평문 + 태그 16
KDF = {"alg": "argon2id", "t": 3, "m": 262144, "p": 4}  # 256 MiB, 3회: 이 PC에서 한 번 0.2초 안팎
MIN_PASSWORD = 10
MAX_SECRET = 256
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class _KeyAead(aead.Aead):
    """키 묶음을 감싸는 데만 쓰는 AES-256-GCM(cryptography). nonce는 매번 새 난수."""

    def __init__(self, key: bytes) -> None:
        self._gcm = AESGCM(key)

    def encrypt(self, plaintext: bytes, associated_data: bytes) -> bytes:
        nonce = os.urandom(12)
        return nonce + self._gcm.encrypt(nonce, plaintext, associated_data)

    def decrypt(self, ciphertext: bytes, associated_data: bytes) -> bytes:
        try:
            return self._gcm.decrypt(ciphertext[:12], ciphertext[12:], associated_data)
        except (InvalidTag, ValueError) as exc:
            raise tink.TinkError("wrong key") from exc


class _DpapiAead(aead.Aead):
    """이 PC의 Windows 계정으로 감싼다(DPAPI). 연관 데이터는 엔트로피로 넣어 다른 용도의 덩어리와 섞이지 않게."""

    def encrypt(self, plaintext: bytes, associated_data: bytes) -> bytes:
        return store._dpapi(plaintext, True, b"TailHop store|" + associated_data)

    def decrypt(self, ciphertext: bytes, associated_data: bytes) -> bytes:
        try:
            return store._dpapi(ciphertext, False, b"TailHop store|" + associated_data)
        except OSError as exc:
            raise tink.TinkError("dpapi") from exc


def new_keyset() -> tink.KeysetHandle:
    return tink.new_keyset_handle(TEMPLATE)


def primitive(handle: tink.KeysetHandle) -> aead.Aead:
    return handle.primitive(aead.Aead)


def to_bytes(handle: tink.KeysetHandle) -> bytes:
    """평문 키 묶음(다른 열쇠로 암호화한 메타 안에만 넣는다)."""
    return proto_keyset_format.serialize(handle, secret_key_access.TOKEN)


def from_bytes(data: bytes) -> tink.KeysetHandle:
    return proto_keyset_format.parse(data, secret_key_access.TOKEN)


# ---------- 감싸기 ----------
def _derive(secret: str, salt: bytes, kdf: dict) -> bytes:
    return Argon2id(salt=salt, length=32, iterations=kdf["t"], lanes=kdf["p"],
                    memory_cost=kdf["m"]).derive(secret.encode("utf-8"))


def seal_secret(handle: tink.KeysetHandle, secret: str, ad: bytes) -> dict:
    """비밀번호·PIN·복구 코드로 감싼 키 묶음(JSON에 그대로 넣을 수 있는 dict)."""
    salt = os.urandom(16)
    blob = proto_keyset_format.serialize_encrypted(handle, _KeyAead(_derive(secret, salt, KDF)), ad)
    return {**KDF, "salt": b64(salt), "blob": b64(blob)}


def open_secret(record: dict, secret: str, ad: bytes) -> tink.KeysetHandle | None:
    """틀리면 None. 기록의 KDF 값은 지금 값보다 약할 수 없다(누가 저장소 파일을 고쳐 약하게 만들지 못하게)."""
    try:
        kdf = {"alg": record["alg"], "t": int(record["t"]), "m": int(record["m"]), "p": int(record["p"])}
        salt, blob = unb64(record["salt"]), unb64(record["blob"])
    except (KeyError, TypeError, ValueError):
        return None
    if kdf["alg"] != "argon2id" or kdf["t"] < KDF["t"] or kdf["m"] < KDF["m"] or kdf["p"] < 1 \
            or kdf["m"] > 1024 * 1024 or kdf["t"] > 10 or kdf["p"] > 16 or len(salt) < 16:
        return None
    try:
        return proto_keyset_format.parse_encrypted(blob, _KeyAead(_derive(secret, salt, kdf)), ad)
    except tink.TinkError:
        return None


def seal_local(handle: tink.KeysetHandle, ad: bytes) -> bytes:
    return proto_keyset_format.serialize_encrypted(handle, _DpapiAead(), ad)


def open_local(blob: bytes, ad: bytes) -> tink.KeysetHandle | None:
    """다른 PC·다른 Windows 계정이거나 Windows를 다시 깔았으면 None(비밀번호로 연다)."""
    try:
        return proto_keyset_format.parse_encrypted(blob, _DpapiAead(), ad)
    except tink.TinkError:
        return None


# ---------- 비밀 값 규칙 ----------
def password_ok(text: object) -> bool:
    return isinstance(text, str) and MIN_PASSWORD <= len(text) <= MAX_SECRET


def new_recovery_code() -> str:
    raw = int.from_bytes(os.urandom(20), "big")
    chars = "".join(CODE_ALPHABET[(raw >> (5 * i)) & 31] for i in range(32))
    return "-".join(chars[i:i + 4] for i in range(0, 32, 4))


def normalize_code(text: str) -> str:
    """띄어쓰기·하이픈·대소문자 상관없이, 헷갈리는 글자(O→0, I/L→1)는 바로잡아 정식 모양으로."""
    clean = "".join(c for c in text.upper() if c.isalnum()).replace("O", "0").replace("I", "1").replace("L", "1")
    return "-".join(clean[i:i + 4] for i in range(0, len(clean), 4))
