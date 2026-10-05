"""실행 중인 앱에 바깥에서 보내기를 맡기는 길(1.6.0).

- 명령줄: `TailHop.exe [--to 기기] [--text 글] [파일·폴더...]`, `TailHop.exe --list`
- 탐색기 '보내기' 메뉴: Windows가 고른 파일들을 명령줄 인자로 넘긴다(위와 같은 길).
- 앱이 이미 떠 있으면 새 프로세스는 창을 띄우지 않고, 127.0.0.1의 로컬 소켓으로 요청만 넘기고 끝난다.
  포트와 토큰은 bridge.json(이 사용자만 읽는 ACL)에 있고, 토큰이 맞지 않으면 아무것도 하지 않는다.
- 폴더는 임시 zip으로 묶어 보통 파일로 보낸다. 선(wire) 형식은 그대로라 예전 앱·Android도 받는다.
- 전역 단축키(설정에서 켤 때만): 지금 대화방 기기로 클립보드를 보낸다.
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import hmac
import json
import os
import secrets
import socket
import subprocess
import threading
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import store

BRIDGE_FILE = "bridge.json"
MAX_REQUEST = 4 * 1024 * 1024  # 텍스트 1 MiB(UTF-8 최대 4바이트/글자)와 경로 목록을 넉넉히 담는다
CONNECT_TIMEOUT = 5.0
SENDTO_NAME = "TailHop.lnk"
HOTKEY_LABEL = "Ctrl+Alt+Shift+C"
_MOD_ALT, _MOD_CONTROL, _MOD_SHIFT, _MOD_NOREPEAT = 0x1, 0x2, 0x4, 0x4000
_VK_C = 0x43
_WM_HOTKEY, _WM_QUIT = 0x0312, 0x0012


@dataclass
class Request:
    paths: list[str] = field(default_factory=list)
    text: str | None = None
    to: str | None = None
    list_devices: bool = False
    hidden: bool = False

    @property
    def has_work(self) -> bool:
        return bool(self.paths) or self.text is not None or self.list_devices

    def to_json(self) -> dict:
        return {"paths": self.paths, "text": self.text, "to": self.to, "list": self.list_devices}


class ArgError(ValueError):
    pass


def parse_args(argv: list[str]) -> Request:
    """명령줄 → 요청. 경로는 절대 경로로 바꾼다(요청을 받는 앱은 작업 폴더가 다르다)."""
    req = Request()
    it = iter(argv)
    for arg in it:
        if arg == "--hidden":
            req.hidden = True
        elif arg == "--list":
            req.list_devices = True
        elif arg in ("--to", "--text"):
            value = next(it, None)
            if value is None:
                raise ArgError(arg)
            if arg == "--to":
                req.to = value
            else:
                req.text = value
        elif arg == "--":
            req.paths.extend(str(Path(x).absolute()) for x in it)
        elif arg.startswith("--"):
            raise ArgError(arg)
        else:
            req.paths.append(str(Path(arg).absolute()))
    return req


def request_from_json(data: dict) -> Request:
    """소켓에서 받은 JSON을 경계에서 한 번 검사한다. 형식이 틀리면 ArgError."""
    paths, text, to, listing = data.get("paths"), data.get("text"), data.get("to"), data.get("list")
    if not isinstance(paths, list) or not all(isinstance(x, str) for x in paths):
        raise ArgError("paths")
    if text is not None and not isinstance(text, str):
        raise ArgError("text")
    if to is not None and not isinstance(to, str):
        raise ArgError("to")
    if not isinstance(listing, bool):
        raise ArgError("list")
    return Request(paths=paths, text=text, to=to, list_devices=listing)


def resolve_device(devices: list[tuple[str, str]], query: str | None, current: str | None) -> str | None:
    """받을 기기 고르기. devices = [(stable_id, 이름)].
    query가 없으면 지금 대화방, 있으면 기기 ID → 이름(대소문자 무시) → 이름 앞부분이 하나만 맞을 때 순서로 찾는다."""
    if query is None:
        return current if any(sid == current for sid, _ in devices) else None
    for sid, _ in devices:
        if sid == query:
            return sid
    q = query.casefold()
    exact = [sid for sid, name in devices if name.casefold() == q]
    if len(exact) == 1:
        return exact[0]
    prefix = [sid for sid, name in devices if name.casefold().startswith(q)]
    return prefix[0] if len(prefix) == 1 else None


# ---------- 폴더 묶기 ----------
def pack_folder(folder: Path, out_dir: Path) -> Path:
    """폴더를 out_dir/<폴더 이름>.zip으로 묶는다. 링크(심볼릭·정션)는 따라가지 않는다(폴더 밖을 담지 않게).
    압축은 가장 빠른 단계로 한다(사진·영상은 어차피 거의 줄지 않는다)."""
    folder = folder.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"{folder.name or 'folder'}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as zf:
        for root, dirs, files in os.walk(folder, followlinks=False):
            base = Path(root)
            dirs[:] = [d for d in dirs if not _is_link(base / d)]
            rel_root = base.relative_to(folder)
            if not files and not dirs and base != folder:
                zf.writestr(f"{rel_root.as_posix()}/", b"")  # 빈 폴더도 남긴다
            for name in files:
                path = base / name
                if _is_link(path):
                    continue
                zf.write(path, (rel_root / name).as_posix())
    return target


def _is_link(path: Path) -> bool:
    try:
        return path.is_symlink() or path.is_junction()
    except OSError:
        return True


# ---------- 로컬 소켓 ----------
def _bridge_path(app_dir: Path | None) -> Path:
    return (app_dir or store.APP_DIR) / BRIDGE_FILE


def _read_line(conn: socket.socket) -> bytes:
    buf = bytearray()
    while not buf.endswith(b"\n"):
        chunk = conn.recv(65536)
        if not chunk:
            break
        buf += chunk
        if len(buf) > MAX_REQUEST:
            raise ArgError("too_big")
    return bytes(buf)


class Server:
    """앱 안에서 도는 받는 쪽. handler(요청 JSON) → 응답 dict는 이 스레드에서 부른다."""

    def __init__(self, handler: Callable[[dict], dict], app_dir: Path | None = None) -> None:
        self.handler = handler
        self.path = _bridge_path(app_dir)
        self.token = secrets.token_hex(32)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(4)
        self.port = self.sock.getsockname()[1]
        store._write_private(self.path, json.dumps({"port": self.port, "token": self.token, "pid": os.getpid()}))
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self) -> None:
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn: socket.socket) -> None:
        with conn:
            conn.settimeout(CONNECT_TIMEOUT)
            try:
                data = json.loads(_read_line(conn).decode("utf-8"))
                token = data.get("token") if isinstance(data, dict) else None
                if not isinstance(token, str) or not hmac.compare_digest(token, self.token):
                    return  # 토큰이 틀리면 답도 하지 않는다
                reply = self.handler(data)
            except (OSError, ValueError, UnicodeDecodeError):
                return
            try:
                conn.sendall(json.dumps(reply).encode("utf-8") + b"\n")
            except OSError:
                pass

    def close(self) -> None:
        self.sock.close()
        try:
            info = json.loads(self.path.read_text("utf-8"))
            if info.get("pid") == os.getpid():  # 다음에 뜬 앱의 파일은 지우지 않는다
                self.path.unlink()
        except (OSError, ValueError):
            pass


def send_request(req: Request, app_dir: Path | None = None, timeout: float = CONNECT_TIMEOUT) -> dict:
    """실행 중인 앱에 요청을 넘기고 응답을 돌려준다. 앱이 없거나 응답이 없으면 OSError."""
    try:
        info = json.loads(_bridge_path(app_dir).read_text("utf-8"))
        port, token = int(info["port"]), str(info["token"])
    except (KeyError, TypeError, ValueError) as exc:
        raise OSError("bridge file") from exc
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.sendall(json.dumps({"token": token, **req.to_json()}).encode("utf-8") + b"\n")
        line = _read_line(sock)
    if not line:
        raise OSError("no reply")
    return json.loads(line.decode("utf-8"))


# ---------- 탐색기 '보내기' 메뉴 ----------
def sendto_path() -> Path:
    return Path(os.environ.get("APPDATA", Path.home())) / "Microsoft" / "Windows" / "SendTo" / SENDTO_NAME


def sendto_enabled() -> bool:
    return sendto_path().exists()


def set_sendto(enabled: bool, target: str, arguments: str = "", icon: str = "") -> None:
    """바로 가기(.lnk)를 만들거나 지운다. 만들기는 WScript.Shell COM(PowerShell)을 쓰고,
    경로는 명령에 끼워 넣지 않고 환경 변수로 넘긴다."""
    link = sendto_path()
    if not enabled:
        link.unlink(missing_ok=True)
        return
    link.parent.mkdir(parents=True, exist_ok=True)
    script = ("$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:TH_LINK);"
              "$s.TargetPath=$env:TH_TARGET;$s.Arguments=$env:TH_ARGS;"
              "if($env:TH_ICON){$s.IconLocation=$env:TH_ICON};$s.Save()")
    env = {**os.environ, "TH_LINK": str(link), "TH_TARGET": target, "TH_ARGS": arguments, "TH_ICON": icon}
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script], env=env, check=True,
                   capture_output=True, timeout=30, creationflags=subprocess.CREATE_NO_WINDOW)


# ---------- 전역 단축키 ----------
class Hotkey:
    """RegisterHotKey는 등록한 스레드의 메시지 큐로 알림이 오므로 전용 스레드에서 등록하고 기다린다."""

    def __init__(self, on_press: Callable[[], None]) -> None:
        self.on_press = on_press
        self.thread_id: int | None = None
        self.ok = threading.Event()
        self.ready = threading.Event()
        threading.Thread(target=self._run, daemon=True).start()
        self.ready.wait(2)

    def _run(self) -> None:
        user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
        self.thread_id = kernel32.GetCurrentThreadId()
        mods = _MOD_CONTROL | _MOD_ALT | _MOD_SHIFT | _MOD_NOREPEAT
        if user32.RegisterHotKey(None, 1, mods, _VK_C):
            self.ok.set()
        self.ready.set()
        if not self.ok.is_set():
            return
        msg = wt.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            if msg.message == _WM_HOTKEY:
                self.on_press()
        user32.UnregisterHotKey(None, 1)

    def stop(self) -> None:
        if self.thread_id is not None and self.ok.is_set():
            ctypes.windll.user32.PostThreadMessageW(self.thread_id, _WM_QUIT, 0, 0)
