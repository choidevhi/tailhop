"""설정·기록 저장. 비밀키는 Windows DPAPI로 감싸서 저장한다.

모든 함수는 app_dir을 받는다(없으면 APP_DIR). 테스트는 임시 폴더를 넘겨 실제 사용자 파일을 건드리지 않는다.
"""
from __future__ import annotations

import base64
import ctypes
import ctypes.wintypes as wt
import json
import os
import re
import threading
import time
from dataclasses import asdict, dataclass
from pathlib import Path

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "TailHop"
DEFAULT_SAVE_DIR = Path.home() / "Downloads" / "TailHop"
HISTORY_LIMIT = 500
TEXT_INLINE = 2000  # 기록 목록에 그대로 두는 텍스트 길이(글자). 넘으면 texts/에 따로 저장
_TEXT_FILE = re.compile(r"[0-9a-f]{32}\.txt")
KINDS = ("phone", "pc")
ROLES = {"phone": ("",), "pc": ("host", "joiner")}


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wt.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _blob(data: bytes) -> _Blob:
    buf = ctypes.create_string_buffer(data, len(data))
    return _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))


def _dpapi(data: bytes, protect: bool) -> bytes:
    crypt32 = ctypes.windll.crypt32
    entropy = _blob(b"TailHop v1")
    out = _Blob()
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    # CRYPTPROTECT_UI_FORBIDDEN = 0x1
    if not fn(ctypes.byref(_blob(data)), None, ctypes.byref(entropy), None, None, 0x1, ctypes.byref(out)):
        raise OSError("DPAPI 처리에 실패했습니다.")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


@dataclass
class Pairing:
    """연결된 기기 하나. kind: "phone" 또는 "pc". role: PC끼리일 때 이 PC 기준 상대와의 관계
    ("host" = 내가 코드를 보여 줌, "joiner" = 내가 코드를 입력함). 폰이면 빈 문자열."""

    ip: str
    port: int
    name: str
    stable_id: str
    secret: bytes
    kind: str = "phone"
    role: str = ""


def _app_dir(app_dir: Path | None) -> Path:
    return Path(app_dir) if app_dir is not None else APP_DIR


_SID_CACHE: list[str] = []


def _current_user_sid() -> str:
    """이 프로세스 사용자의 SID 문자열(S-1-5-21-…)."""
    if _SID_CACHE:
        return _SID_CACHE[0]
    advapi32, kernel32 = ctypes.windll.advapi32, ctypes.windll.kernel32
    kernel32.GetCurrentProcess.restype = wt.HANDLE
    advapi32.OpenProcessToken.argtypes = [wt.HANDLE, wt.DWORD, ctypes.POINTER(wt.HANDLE)]
    advapi32.GetTokenInformation.argtypes = [wt.HANDLE, ctypes.c_int, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD)]
    advapi32.ConvertSidToStringSidW.argtypes = [ctypes.c_void_p, ctypes.POINTER(wt.LPWSTR)]
    kernel32.LocalFree.argtypes = [ctypes.c_void_p]
    kernel32.CloseHandle.argtypes = [wt.HANDLE]
    token = wt.HANDLE()
    if not advapi32.OpenProcessToken(kernel32.GetCurrentProcess(), 0x0008, ctypes.byref(token)):  # TOKEN_QUERY
        raise OSError("OpenProcessToken")
    try:
        size = wt.DWORD()
        advapi32.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))  # TokenUser
        buf = ctypes.create_string_buffer(size.value)
        if not advapi32.GetTokenInformation(token, 1, buf, size, ctypes.byref(size)):
            raise OSError("GetTokenInformation")
        psid = ctypes.cast(buf, ctypes.POINTER(ctypes.c_void_p))[0]  # TOKEN_USER.User.Sid
        text = wt.LPWSTR()
        if not advapi32.ConvertSidToStringSidW(psid, ctypes.byref(text)):
            raise OSError("ConvertSidToStringSidW")
        try:
            sid = str(text.value)
        finally:
            kernel32.LocalFree(text)
    finally:
        kernel32.CloseHandle(token)
    _SID_CACHE.append(sid)
    return sid


def restrict_to_user(path: Path) -> bool:
    """파일 ACL을 '이 사용자만 모든 권한'으로 바꾸고 상속을 끊는다(보호된 DACL). 실패하면 False
    (FAT32 등 ACL이 없는 디스크). 저장은 실패시키지 않는다 — 비밀은 어차피 DPAPI로 감싸 둔다."""
    try:
        advapi32, kernel32 = ctypes.windll.advapi32, ctypes.windll.kernel32
        advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [
            wt.LPCWSTR, wt.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
        advapi32.SetFileSecurityW.argtypes = [wt.LPCWSTR, wt.DWORD, ctypes.c_void_p]
        kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        sd = ctypes.c_void_p()
        if not advapi32.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                f"D:P(A;;FA;;;{_current_user_sid()})", 1, ctypes.byref(sd), None):
            return False
        try:
            # DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION
            return bool(advapi32.SetFileSecurityW(str(path), 0x00000004 | 0x80000000, sd))
        finally:
            kernel32.LocalFree(sd)
    except (OSError, AttributeError):
        return False


def _write_private(path: Path, text: str) -> None:
    """임시 파일에 쓰고 ACL을 이 사용자만으로 줄인 뒤 바꿔 끼운다(이름 바꾸기는 파일의 ACL을 그대로 둔다)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(text, "utf-8")
    restrict_to_user(tmp)
    os.replace(tmp, path)


def explorer_select_command(path: Path) -> str | None:
    """받은 파일을 탐색기에서 선택만 하는 명령줄(실행하지 않는다).

    explorer.exe는 명령줄을 직접 읽어 쉼표를 인자 구분자로 쓰므로, 경로를 통째로 큰따옴표로 묶어 한 덩어리로 넘긴다
    (list로 넘기면 공백 없는 경로는 따옴표 없이 들어가 `a,b.txt` 같은 이름이 쪼개진다). 실행 파일도 전체 경로로 지정한다.
    경로에 큰따옴표·줄바꿈·NUL이 있으면(Windows 파일명에는 올 수 없음) None → 호출하는 쪽은 폴더만 연다."""
    text = str(Path(path).absolute())
    if any(c in text for c in '"\r\n\0'):
        return None
    explorer = Path(os.environ.get("SystemRoot") or r"C:\Windows") / "explorer.exe"
    return f'"{explorer}" /select,"{text}"'


_LEGACY_FIELDS = {"phone_ip": "ip", "phone_port": "port", "phone_name": "name", "phone_stable_id": "stable_id"}


def _decode(data: dict) -> tuple[Pairing, bool]:
    """저장 형식 → Pairing. v1.2 이전 형식(phone_*)이면 두 번째 값이 True."""
    data = dict(data)
    legacy = any(k in data for k in _LEGACY_FIELDS)
    for old, new in _LEGACY_FIELDS.items():
        if old in data:
            data[new] = data.pop(old)
    secret = _dpapi(base64.b64decode(data.pop("secret_dpapi")), protect=False)
    pairing = Pairing(
        ip=str(data["ip"]), port=int(data["port"]), name=str(data["name"]), stable_id=str(data["stable_id"]),
        secret=secret, kind=str(data.get("kind", "phone")), role=str(data.get("role", "")),
    )
    if pairing.kind not in KINDS or pairing.role not in ROLES[pairing.kind] or len(secret) != 32:
        raise ValueError("pairing kind/role/secret")
    return pairing, legacy


def _encode(p: Pairing) -> dict:
    data = asdict(p)
    data["secret_dpapi"] = base64.b64encode(_dpapi(data.pop("secret"), protect=True)).decode()
    return data


def load_pairings(app_dir: Path | None = None) -> list[Pairing]:
    """연결된 기기 목록. 예전 1대용 pairing.json과 v1.2 이전 형식(폰 전용 필드)은 새 형식으로 옮긴다.
    1.5.0까지 옮기기 전 파일을 남기던 pairings.json.v1.bak은 연결을 해제한 기기의 비밀까지 남기므로,
    새 형식을 제대로 읽으면(옮긴 뒤 포함) 지운다."""
    root = _app_dir(app_dir)
    path = root / "pairings.json"
    try:
        raw = json.loads(path.read_text("utf-8"))
        decoded = [_decode(d) for d in raw]
    except FileNotFoundError:
        decoded = None
    except (OSError, ValueError, KeyError, TypeError):
        return []
    if decoded is not None:
        items = [x for x, _ in decoded]
        if any(legacy for _, legacy in decoded):
            save_pairings(items, root)
        _drop_backup(root)
        return items
    legacy_path = root / "pairing.json"
    try:
        items = [_decode(json.loads(legacy_path.read_text("utf-8")))[0]]
    except (OSError, ValueError, KeyError, TypeError):
        return []
    save_pairings(items, root)
    legacy_path.unlink(missing_ok=True)
    _drop_backup(root)
    return items


def _drop_backup(root: Path) -> None:
    try:
        (root / "pairings.json.v1.bak").unlink(missing_ok=True)
    except OSError:
        pass


def save_pairings(items: list[Pairing], app_dir: Path | None = None) -> None:
    _write_private(_app_dir(app_dir) / "pairings.json", json.dumps([_encode(p) for p in items]))


def load_settings(app_dir: Path | None = None) -> dict:
    try:
        return json.loads((_app_dir(app_dir) / "settings.json").read_text("utf-8"))
    except (OSError, ValueError):
        return {}


def save_settings(settings: dict, app_dir: Path | None = None) -> None:
    _write_private(_app_dir(app_dir) / "settings.json", json.dumps(settings, ensure_ascii=False))


class History:
    """주고받은 기록(최근 HISTORY_LIMIT개). 긴 텍스트는 texts/<id>.txt에 따로 두고 목록에는 앞부분만 남긴다.

    1.4까지는 텍스트 전체(최대 1 MiB × 500개)를 history.json과 메모리에 들고 있었다. 1.5부터 목록(메모리·파일)은
    항목마다 최대 TEXT_INLINE 글자라서 크기가 묶인다. 전체 글은 복사할 때만 full_text()로 읽는다.
    예전 기록은 처음 열 때 한 번 옮긴다.
    """

    def __init__(self, app_dir: Path | None = None) -> None:
        root = _app_dir(app_dir)
        self.path = root / "history.json"
        self.text_dir = root / "texts"
        self.lock = threading.Lock()
        loaded = False
        try:
            self.items: list[dict] = json.loads(self.path.read_text("utf-8"))
            loaded = isinstance(self.items, list)
        except FileNotFoundError:
            self.items = []
            loaded = True
        except (OSError, ValueError):
            pass
        self.items = [i for i in self.items if isinstance(i, dict)] if loaded else []
        with self.lock:
            moved = 0
            for item in self.items:
                text = item.get("text")
                if item.get("kind") == "text" and isinstance(text, str) and len(text) > TEXT_INLINE \
                        and not item.get("text_file"):
                    try:
                        self._externalize(item, text)
                        moved += 1
                    except OSError:
                        pass  # 못 옮기면 그대로 둔다(다음에 다시 시도)
            try:
                if moved:
                    self._save()
                if loaded:  # 기록을 못 읽었으면 따로 둔 글을 지우지 않는다
                    self._remove_orphans()
            except OSError:
                pass

    # ---------- 긴 텍스트 ----------
    def _externalize(self, item: dict, text: str) -> None:
        name = os.urandom(16).hex() + ".txt"
        _write_private(self.text_dir / name, text)
        item["text"] = text[:TEXT_INLINE]
        item["text_file"] = name
        item["text_len"] = len(text)

    def _text_path(self, item: dict) -> Path | None:
        name = item.get("text_file")
        if isinstance(name, str) and _TEXT_FILE.fullmatch(name):
            return self.text_dir / name
        return None

    def full_text(self, item: dict) -> str:
        """항목의 전체 텍스트(따로 둔 파일이 없어졌으면 남은 앞부분)."""
        path = self._text_path(item)
        if path is not None:
            try:
                return path.read_text("utf-8")
            except OSError:
                pass
        return str(item.get("text", ""))

    def _drop_files(self, removed: list[dict]) -> None:
        for item in removed:
            path = self._text_path(item)
            if path is not None:
                path.unlink(missing_ok=True)

    def _remove_orphans(self) -> None:
        if not self.text_dir.is_dir():
            return
        keep = {item.get("text_file") for item in self.items}
        for path in self.text_dir.glob("*.txt"):
            if path.name not in keep:
                path.unlink(missing_ok=True)

    def _save(self) -> None:
        _write_private(self.path, json.dumps(self.items, ensure_ascii=False))

    # ---------- 목록 ----------
    def add(self, direction: str, kind: str, **fields) -> dict:
        item = {"time": time.time(), "dir": direction, "kind": kind, **fields}
        with self.lock:
            text = item.get("text")
            if kind == "text" and isinstance(text, str) and len(text) > TEXT_INLINE:
                self._externalize(item, text)
            self.items.append(item)
            self._drop_files(self.items[:-HISTORY_LIMIT])
            self.items = self.items[-HISTORY_LIMIT:]
            self._save()
        return item

    def adopt_legacy(self, peer_id: str, peer_kind: str = "phone") -> int:
        """기기 표시가 없는 예전(v1.1 이전) 기록을 한 기기의 대화로 옮겨 저장한다. 옮긴 개수를 돌려준다."""
        with self.lock:
            moved = 0
            for item in self.items:
                if "peer_id" not in item:
                    item["peer_id"] = peer_id
                    item.setdefault("peer_kind", peer_kind)
                    moved += 1
            if moved:
                self._save()
        return moved

    def for_peer(self, peer_id: str, fallback: bool = False) -> list[dict]:
        """한 기기의 대화. fallback이면 기기 표시 없는 예전 기록도 포함한다."""
        return [i for i in self.items if i.get("peer_id") == peer_id or (fallback and "peer_id" not in i)]

    def last_for_peer(self, peer_id: str) -> dict | None:
        """한 기기의 마지막 항목(목록 전체를 복사하지 않고 뒤에서 찾는다)."""
        return next((i for i in reversed(self.items) if i.get("peer_id") == peer_id), None)

    def clear_peer(self, peer_id: str, fallback: bool = False) -> None:
        with self.lock:
            hit = [i for i in self.items if i.get("peer_id") == peer_id or (fallback and "peer_id" not in i)]
            self._drop_files(hit)
            self.items = [i for i in self.items if not (i.get("peer_id") == peer_id or (fallback and "peer_id" not in i))]
            self._save()

    def clear(self) -> None:
        with self.lock:
            self._drop_files(self.items)
            self.items = []
            _write_private(self.path, "[]")
