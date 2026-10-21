"""앱 안 영상 재생기(2.2.1). mpv(따로 실행되는 프로그램, vendor/mpv)를 이 창 안 영역에 띄우고(--wid)
명령 통로(이름 있는 파이프 IPC)로 다룬다. 화면의 막대·단추는 모두 이 앱이 그린다.

- 입력: 이 PC 안에서만 열리는 주소(media.RangeServer, 복호화한 범위만 그때그때) 또는 받은 파일 경로.
  mpv는 설정 파일·스크립트·외부 내려받기(ytdl)를 쓰지 않고(--no-config), 받은 데이터는 메모리 캐시에만 둔다(--cache-on-disk=no).
- 조작: 재생·멈춤, 탐색 막대(받아 둔 범위 표시, 끌어 옮기기, 마우스를 올리면 그 시각), ±10초, 이전·다음,
  음량·음소거, 속도(0.25–2배), 전체 화면. 키: 스페이스 K 재생, 왼쪽·오른쪽 화살표 5초(Shift 30초), J/L 10초, 위·아래 화살표 음량, M 음소거,
  F·더블클릭 전체 화면, [ ] 속도, , . 한 장씩, Home 처음, PageUp/PageDown 이전·다음, Esc 전체 화면 끄기·닫기.
- 이어 보기: 같은 영상을 다시 열면 멈춘 자리부터(설정에는 경로의 해시와 초만, 이름은 남기지 않는다).
- 화면 캡처·녹화에서 뺀다(ui.protect). mpv는 이 창의 자식 창이라 같이 빠진다.
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import hashlib
import hmac
import json
import secrets
import subprocess
import sys
import threading
import time
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import customtkinter as ctk

from i18n import t
from lightbox import _dark_title_bar
from ui import fit_geometry, font, icon, protect

BG = "#000000"
BAR = "#0A0A0B"
TEXT = "#FAFAFA"
MUTED = "#A1A1AA"
TRACK = "#3F3F46"
BUFFER = "#71717A"
PLAYED = "#FAFAFA"
HOVER_BG = "#27272A"
SPEEDS = (0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
HIDE_AFTER = 2.5  # 전체 화면에서 막대를 숨기기까지(초)
RESUME_KEEP = 300  # 기억하는 이어 보기 수
_NO_WINDOW = 0x08000000


def mpv_path() -> Path | None:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    for folder in (base / "mpv", base / "vendor" / "mpv"):
        if (folder / "mpv.exe").exists():
            return folder / "mpv.exe"
    return None


def resume_key(settings: dict, text: str) -> str:
    """이어 보기 열쇠: 이 설치만의 무작위 소금으로 만든 HMAC(설정 파일만 보고는 어떤 영상인지 알 수 없게)."""
    salt = settings.get("resume_salt")
    if not isinstance(salt, str) or len(salt) != 64:
        salt = secrets.token_hex(32)
        settings["resume_salt"] = salt
    return hmac.new(bytes.fromhex(salt), text.encode("utf-8"), hashlib.sha256).hexdigest()[:32]


def clock(seconds: float | None) -> str:
    if seconds is None or seconds < 0:
        return "--:--"
    s = int(seconds)
    return f"{s // 3600}:{s // 60 % 60:02}:{s % 60:02}" if s >= 3600 else f"{s // 60}:{s % 60:02}"


@dataclass
class Clip:
    """재생할 것 하나. open()은 작업 스레드에서 (주소, 다 쓰면 부를 정리 함수)를 돌려준다."""
    key: str  # 이어 보기 열쇠(경로의 HMAC, 빈 값이면 이어 보기를 쓰지 않는다: 잠긴 폴더 안 등)
    name: str
    open: Callable[[], tuple[str, Callable[[], None]]]


class _Pipe:
    """mpv IPC(이름 있는 파이프)의 한 연결. 읽기는 막지 않고(PeekNamedPipe), 줄마다 JSON."""

    def __init__(self, name: str) -> None:
        k32 = ctypes.windll.kernel32
        k32.CreateFileW.restype = wt.HANDLE
        self.k32 = k32
        self.handle = None
        for _ in range(100):  # mpv가 통로를 열 때까지 잠깐 기다린다
            h = k32.CreateFileW(name, 0xC0000000, 0, None, 3, 0, None)  # GENERIC_READ|WRITE, OPEN_EXISTING
            if h and h != wt.HANDLE(-1).value:
                self.handle = h
                break
            time.sleep(0.05)
        if self.handle is None:
            raise OSError("mpv ipc")
        self.buf = b""

    def send(self, command: list) -> None:
        data = (json.dumps({"command": command}) + "\n").encode("utf-8")
        written = wt.DWORD()
        self.k32.WriteFile(self.handle, data, len(data), ctypes.byref(written), None)

    def poll(self) -> list[dict]:
        avail = wt.DWORD()
        if not self.k32.PeekNamedPipe(self.handle, None, 0, None, ctypes.byref(avail), None):
            raise OSError("mpv ipc closed")
        out = []
        if avail.value:
            chunk = ctypes.create_string_buffer(avail.value)
            got = wt.DWORD()
            self.k32.ReadFile(self.handle, chunk, avail.value, ctypes.byref(got), None)
            self.buf += chunk.raw[:got.value]
            *lines, self.buf = self.buf.split(b"\n")
            for line in lines:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    continue
        return out

    def close(self) -> None:
        if self.handle:
            self.k32.CloseHandle(self.handle)
            self.handle = None


class SeekBar(tk.Canvas):
    """탐색 막대: 바탕, 받아 둔 범위, 본 범위, 손잡이. 마우스를 올리면 그 시각을 보이고, 누르거나 끌면 옮긴다."""

    def __init__(self, parent, on_seek: Callable[[float, bool], None], **kw) -> None:
        super().__init__(parent, height=26, bg=BAR, highlightthickness=0, cursor="hand2", **kw)
        self.on_seek = on_seek
        self.duration, self.pos, self.buffered = 0.0, 0.0, 0.0
        self.dragging = False
        self.hover_x: int | None = None
        self.bind("<Configure>", lambda _e: self.draw())
        self.bind("<Motion>", self._motion)
        self.bind("<Leave>", lambda _e: self._leave())
        self.bind("<ButtonPress-1>", self._press)
        self.bind("<B1-Motion>", self._drag)
        self.bind("<ButtonRelease-1>", self._release)

    def _at(self, x: int) -> float:
        w = max(1, self.winfo_width() - 16)
        return max(0.0, min(1.0, (x - 8) / w)) * self.duration

    def _motion(self, e) -> None:
        self.hover_x = e.x
        self.draw()

    def _leave(self) -> None:
        self.hover_x = None
        self.draw()

    def _press(self, e) -> None:
        if self.duration > 0:
            self.dragging = True
            self.pos = self._at(e.x)
            self.on_seek(self.pos, False)
            self.draw()

    def _drag(self, e) -> None:
        if self.dragging:
            self.pos = self._at(e.x)
            self.hover_x = e.x
            self.on_seek(self.pos, False)
            self.draw()

    def _release(self, e) -> None:
        if self.dragging:
            self.dragging = False
            self.pos = self._at(e.x)
            self.on_seek(self.pos, True)  # 놓을 때는 정확한 자리로
            self.draw()

    def update_state(self, pos: float | None, duration: float | None, buffered: float | None) -> None:
        if duration:
            self.duration = duration
        if pos is not None and not self.dragging:
            self.pos = pos
        if buffered is not None:
            self.buffered = buffered
        self.draw()

    def draw(self) -> None:
        self.delete("all")
        w, h = self.winfo_width(), self.winfo_height()
        y = h // 2
        x0, x1 = 8, max(9, w - 8)
        span = x1 - x0
        thick = 6 if (self.hover_x is not None or self.dragging) else 4
        self.create_line(x0, y, x1, y, fill=TRACK, width=thick, capstyle="round")
        if self.duration > 0:
            buf = x0 + span * min(1.0, self.buffered / self.duration)
            if buf > x0:
                self.create_line(x0, y, buf, y, fill=BUFFER, width=thick, capstyle="round")
            cur = x0 + span * min(1.0, self.pos / self.duration)
            self.create_line(x0, y, cur, y, fill=PLAYED, width=thick, capstyle="round")
            r = 7 if (self.hover_x is not None or self.dragging) else 5
            self.create_oval(cur - r, y - r, cur + r, y + r, fill=PLAYED, outline="")
            if self.hover_x is not None:
                hx = max(x0, min(x1, self.hover_x))
                label = clock(self._at(hx))
                self.create_rectangle(hx - 26, 0, hx + 26, 12, fill=HOVER_BG, outline="")
                self.create_text(hx, 6, text=label, fill=TEXT, font=("Segoe UI", 8))


class VideoWindow(ctk.CTkToplevel):
    def __init__(self, parent, clips: list[Clip], index: int, post: Callable[[Callable[[], None]], None],
                 settings: dict | None = None, save_settings: Callable[[], None] | None = None, icon_path=None) -> None:
        super().__init__(parent, fg_color=BG)
        self.clips, self.index, self.post = clips, index, post
        self.settings = settings if settings is not None else {}
        self.save_settings = save_settings or (lambda: None)
        self.proc: subprocess.Popen | None = None
        self.pipe: _Pipe | None = None
        self.release: Callable[[], None] | None = None
        self.state_: dict = {"pause": False, "time-pos": None, "duration": None, "volume": 100.0, "mute": False,
                             "speed": 1.0, "demuxer-cache-time": None, "eof-reached": False, "paused-for-cache": False}
        self.generation = 0
        self.last_move = time.monotonic()
        self.fullscreen = False
        self.closed = False
        self.seek_after: str | None = None
        self.title(clips[index].name)
        self.minsize(560, 360)
        fit_geometry(self, 1100, 700)  # 최대화를 풀었을 때의 크기
        try:
            self.state("zoomed")  # 화면 크기에 맞춘다(배율 때문에 화면보다 커지지 않게)
        except tk.TclError:
            pass
        if icon_path is not None:
            self.after(250, lambda: self._set_icon(icon_path))
        self._build()
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.bind("<Destroy>", self._on_destroy, add="+")
        self._bind_keys(self)
        self.after(20, lambda: (_dark_title_bar(self), protect(self)))
        self.after(60, lambda: self.play(index))
        self.after(200, self._tick)

    def _set_icon(self, icon_path) -> None:
        try:
            self.iconbitmap(str(icon_path))
        except tk.TclError:
            pass

    # ---------- 틀 ----------
    def _button(self, parent, name: str, command, size: int = 36) -> ctk.CTkButton:
        return ctk.CTkButton(parent, text="", image=icon(name, 18, TEXT), command=command, width=size, height=size,
                             corner_radius=size // 2, fg_color="transparent", hover_color=HOVER_BG)

    def _build(self) -> None:
        self.top = ctk.CTkFrame(self, fg_color=BAR, corner_radius=0, height=44)
        self.top.pack(fill="x")
        self.top.pack_propagate(False)
        self.title_label = ctk.CTkLabel(self.top, text="", font=font(13, "bold"), text_color=TEXT, anchor="w")
        self.title_label.pack(side="left", fill="x", expand=True, padx=16)
        self._button(self.top, "x", self.close, 32).pack(side="right", padx=8)
        self.count = ctk.CTkLabel(self.top, text="", font=font(12), text_color=MUTED)
        self.count.pack(side="right", padx=6)

        self.bottom = ctk.CTkFrame(self, fg_color=BAR, corner_radius=0)
        self.bottom.pack(side="bottom", fill="x")
        self.seek = SeekBar(self.bottom, self._seek_to)
        self.seek.pack(fill="x", padx=12, pady=(6, 0))
        row = ctk.CTkFrame(self.bottom, fg_color="transparent")
        row.pack(fill="x", padx=8, pady=(0, 8))
        self.prev_btn = self._button(row, "skip-back", self.prev)
        self.prev_btn.pack(side="left")
        self._button(row, "rewind", lambda: self.command(["seek", -10, "relative"])).pack(side="left")
        self.play_btn = self._button(row, "pause", self.toggle_pause, 42)
        self.play_btn.pack(side="left", padx=4)
        self._button(row, "fast-forward", lambda: self.command(["seek", 10, "relative"])).pack(side="left")
        self.next_btn = self._button(row, "skip-forward", self.next)
        self.next_btn.pack(side="left")
        self.time_label = ctk.CTkLabel(row, text="--:-- / --:--", font=font(12), text_color=TEXT)
        self.time_label.pack(side="left", padx=12)
        self.full_btn = self._button(row, "expand", self.toggle_fullscreen)
        self.full_btn.pack(side="right")
        self.speed_btn = ctk.CTkButton(row, text="1×", command=self._speed_menu, width=48, height=32, corner_radius=8,
                                       fg_color="transparent", hover_color=HOVER_BG, text_color=TEXT,
                                       font=font(12, "bold"))
        self.speed_btn.pack(side="right", padx=4)
        self.volume = ctk.CTkSlider(row, from_=0, to=130, number_of_steps=130, width=110, height=14,
                                    command=lambda v: self.command(["set_property", "volume", float(v)]),
                                    button_color=TEXT, button_hover_color=TEXT, progress_color=TEXT, fg_color=TRACK)
        self.volume.pack(side="right", padx=4)
        self.mute_btn = self._button(row, "volume-2", lambda: self.command(["cycle", "mute"]))
        self.mute_btn.pack(side="right")

        self.video = tk.Frame(self, bg=BG, cursor="arrow")
        self.video.pack(fill="both", expand=True)
        self.note = tk.Label(self.video, text="", bg=BG, fg=MUTED, font=("Segoe UI", 11))
        self.retry = ctk.CTkButton(self.video, text=t("player.retry"), command=lambda: self.play(self.index),
                                   height=34, corner_radius=8, fg_color=TEXT, hover_color="#E4E4E7",
                                   text_color="#09090B", font=font(12, "bold"))
        for widget in (self, self.video):
            widget.bind("<Motion>", lambda _e: self._woke())

    def _bind_keys(self, widget) -> None:
        keys = {"<space>": self.toggle_pause, "<k>": self.toggle_pause, "<Left>": lambda: self._step(-5),
                "<Right>": lambda: self._step(5), "<Shift-Left>": lambda: self._step(-30),
                "<Shift-Right>": lambda: self._step(30), "<j>": lambda: self._step(-10), "<l>": lambda: self._step(10),
                "<Up>": lambda: self._volume(5), "<Down>": lambda: self._volume(-5),
                "<m>": lambda: self.command(["cycle", "mute"]), "<f>": self.toggle_fullscreen,
                "<F11>": self.toggle_fullscreen, "<bracketleft>": lambda: self._speed_step(-1),
                "<bracketright>": lambda: self._speed_step(1), "<comma>": lambda: self.command(["frame-back-step"]),
                "<period>": lambda: self.command(["frame-step"]), "<Home>": lambda: self._seek_to(0, True),
                "<Prior>": self.prev, "<Next>": self.next, "<Escape>": self._escape}
        for seq, fn in keys.items():
            widget.bind(seq, lambda _e, f=fn: (f(), "break")[1])

    # mpv 창이 포커스를 가져가도 같은 키가 되도록 mpv에도 묶는다(누르면 그 이름을 메시지로 보내 온다)
    MPV_KEYS = {"SPACE": "pause", "k": "pause", "LEFT": "back5", "RIGHT": "fwd5", "Shift+LEFT": "back30",
                "Shift+RIGHT": "fwd30", "j": "back10", "l": "fwd10", "UP": "volup", "DOWN": "voldown", "m": "mute",
                "f": "full", "F11": "full", "[": "slower", "]": "faster", ",": "frameback", ".": "frame",
                "HOME": "start", "PGUP": "prev", "PGDWN": "next", "ESC": "escape", "MBTN_LEFT": "click",
                "MBTN_LEFT_DBL": "full", "WHEEL_UP": "volup", "WHEEL_DOWN": "voldown", "MOUSE_MOVE": "move"}

    def _message(self, name: str) -> None:
        actions = {"pause": self.toggle_pause, "back5": lambda: self._step(-5), "fwd5": lambda: self._step(5),
                   "back30": lambda: self._step(-30), "fwd30": lambda: self._step(30),
                   "back10": lambda: self._step(-10), "fwd10": lambda: self._step(10),
                   "volup": lambda: self._volume(5), "voldown": lambda: self._volume(-5),
                   "mute": lambda: self.command(["cycle", "mute"]), "full": self.toggle_fullscreen,
                   "slower": lambda: self._speed_step(-1), "faster": lambda: self._speed_step(1),
                   "frameback": lambda: self.command(["frame-back-step"]), "frame": lambda: self.command(["frame-step"]),
                   "start": lambda: self._seek_to(0, True), "prev": self.prev, "next": self.next,
                   "escape": self._escape, "click": self.toggle_pause, "move": self._woke}
        fn = actions.get(name)
        if fn is not None:
            fn()

    # ---------- 재생 ----------
    def play(self, index: int) -> None:
        self._save_resume()
        self._stop()
        self.index = max(0, min(index, len(self.clips) - 1))
        clip = self.clips[self.index]
        self.title(clip.name)
        self.title_label.configure(text=clip.name)
        self.count.configure(text=f"{self.index + 1} / {len(self.clips)}" if len(self.clips) > 1 else "")
        self.prev_btn.configure(state="normal" if self.index > 0 else "disabled")
        self.next_btn.configure(state="normal" if self.index < len(self.clips) - 1 else "disabled")
        self.retry.place_forget()
        self._show_note(t("player.loading"))
        self.generation += 1
        gen = self.generation

        def work() -> None:
            try:
                url, release = clip.open()
                error = ""
            except Exception as exc:  # noqa: BLE001 - 화면에 이유를 보인다
                url, release, error = "", None, str(exc) or type(exc).__name__
            self.post(lambda: self._start(gen, url, release, error))

        threading.Thread(target=work, daemon=True, name="tailhop-player-open").start()

    def _start(self, gen: int, url: str, release, error: str) -> None:
        if self.closed or gen != self.generation:
            if release:
                release()
            return
        if error:
            self._failed(error)
            return
        exe = mpv_path()
        if exe is None:
            self._failed(t("player.no_mpv"))
            return
        self.release = release
        self.update_idletasks()
        pipe_name = r"\\.\pipe\tailhop-mpv-" + secrets.token_hex(12)
        key = self.clips[self.index].key
        start = (self.settings.get("resume") or {}).get(key, 0) if key else 0
        volume = self.settings.get("player_volume", 100)
        args = [str(exe), "--no-config", "--idle=no", "--force-window=yes", f"--wid={self.video.winfo_id()}",
                f"--input-ipc-server={pipe_name}", "--osc=no", "--osd-level=1", "--osd-duration=900",
                "--keep-open=yes", "--hwdec=auto-safe", "--cache=yes", "--cache-on-disk=no",
                "--demuxer-max-bytes=256MiB", "--demuxer-max-back-bytes=128MiB", "--no-input-default-bindings",
                "--input-vo-keyboard=yes", "--cursor-autohide=1500", "--load-scripts=no", "--ytdl=no",
                "--msg-level=all=error", f"--volume={volume}", f"--start={float(start):.1f}", "--", url]
        try:
            self.proc = subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, creationflags=_NO_WINDOW)
        except OSError as exc:
            self._failed(str(exc))
            return

        def connect() -> None:
            try:
                pipe = _Pipe(pipe_name)
            except OSError as exc:
                message = str(exc)  # except 블록이 끝나면 exc는 지워진다
                self.post(lambda: self._failed(message))
                return
            self.post(lambda: self._connected(gen, pipe))

        threading.Thread(target=connect, daemon=True, name="tailhop-player-ipc").start()

    def _connected(self, gen: int, pipe: _Pipe) -> None:
        if self.closed or gen != self.generation:
            pipe.close()
            return
        self.pipe = pipe
        for i, name in enumerate(("pause", "time-pos", "duration", "volume", "mute", "speed", "demuxer-cache-time",
                                  "eof-reached", "paused-for-cache"), 1):
            pipe.send(["observe_property", i, name])
        for key, name in self.MPV_KEYS.items():
            pipe.send(["keybind", key, f"script-message tailhop {name}"])
        self._hide_note()
        self._poll()

    def _poll(self) -> None:
        if self.closed or self.pipe is None:
            return
        try:
            events = self.pipe.poll()
        except OSError:
            events = None
        if events is None or (self.proc is not None and self.proc.poll() is not None):
            self.pipe.close()
            self.pipe = None
            if not self.closed:
                self._failed(t("player.stopped"))
            return
        for ev in events:
            if ev.get("event") == "property-change":
                self.state_[ev.get("name")] = ev.get("data")
            elif ev.get("event") == "client-message":
                args = ev.get("args") or []
                if len(args) == 2 and args[0] == "tailhop":
                    self._message(str(args[1]))
        if events:
            self._render()
        self.after(30, self._poll)

    def _render(self) -> None:
        s = self.state_
        self.play_btn.configure(image=icon("play" if s["pause"] or s["eof-reached"] else "pause", 18, TEXT))
        self.time_label.configure(text=f"{clock(s['time-pos'])} / {clock(s['duration'])}")
        self.seek.update_state(s["time-pos"], s["duration"], s["demuxer-cache-time"])
        self.mute_btn.configure(image=icon("volume-x" if s["mute"] else "volume-2", 18, TEXT))
        if isinstance(s["volume"], (int, float)) and abs(self.volume.get() - s["volume"]) > 0.5:
            self.volume.set(s["volume"])
        speed = s["speed"] if isinstance(s["speed"], (int, float)) else 1.0
        self.speed_btn.configure(text=f"{speed:g}×")
        if s["paused-for-cache"]:
            self._show_note(t("player.buffering"))
        else:
            self._hide_note()

    def command(self, cmd: list) -> None:
        if self.pipe is not None:
            try:
                self.pipe.send(cmd)
            except OSError:
                pass
        self._woke()

    def toggle_pause(self) -> None:
        if self.state_.get("eof-reached"):
            self._seek_to(0, True)
            self.command(["set_property", "pause", False])
            return
        self.command(["cycle", "pause"])

    def _step(self, seconds: float) -> None:
        self.command(["seek", seconds, "relative"])

    def _volume(self, delta: float) -> None:
        current = self.state_.get("volume") if isinstance(self.state_.get("volume"), (int, float)) else 100
        self.command(["set_property", "volume", max(0.0, min(130.0, current + delta))])

    def _speed_step(self, step: int) -> None:
        speed = self.state_.get("speed") if isinstance(self.state_.get("speed"), (int, float)) else 1.0
        near = min(range(len(SPEEDS)), key=lambda i: abs(SPEEDS[i] - speed))
        self.command(["set_property", "speed", SPEEDS[max(0, min(len(SPEEDS) - 1, near + step))]])

    def _speed_menu(self) -> None:
        menu = tk.Menu(self, tearoff=0)
        for value in SPEEDS:
            menu.add_command(label=f"{value:g}×", command=lambda v=value: self.command(["set_property", "speed", v]))
        menu.tk_popup(self.speed_btn.winfo_rootx(), self.speed_btn.winfo_rooty() - 8 - 26 * len(SPEEDS))

    def _seek_to(self, seconds: float, exact: bool) -> None:
        """끄는 중에는 키프레임으로 빠르게(너무 자주 보내지 않게 묶어서), 놓을 때는 정확한 자리로."""
        if self.seek_after is not None:
            self.after_cancel(self.seek_after)
            self.seek_after = None
        mode = "absolute+exact" if exact else "absolute+keyframes"
        if exact:
            self.command(["seek", seconds, mode])
        else:
            self.seek_after = self.after(60, lambda: self.command(["seek", seconds, mode]))

    def prev(self) -> None:
        if self.index > 0:
            self.play(self.index - 1)

    def next(self) -> None:
        if self.index < len(self.clips) - 1:
            self.play(self.index + 1)

    # ---------- 전체 화면·막대 ----------
    def toggle_fullscreen(self) -> None:
        self.fullscreen = not self.fullscreen
        self.attributes("-fullscreen", self.fullscreen)
        self.full_btn.configure(image=icon("shrink" if self.fullscreen else "expand", 18, TEXT))
        self._woke()

    def _escape(self) -> None:
        if self.fullscreen:
            self.toggle_fullscreen()
        else:
            self.close()

    def _woke(self) -> None:
        self.last_move = time.monotonic()
        if not self.top.winfo_ismapped():
            self.top.pack(fill="x", before=self.video)
            self.bottom.pack(side="bottom", fill="x", before=self.video)

    def _tick(self) -> None:
        """전체 화면에서 마우스를 가만두면 막대를 숨긴다."""
        if self.closed:
            return
        if self.fullscreen and time.monotonic() - self.last_move > HIDE_AFTER and self.top.winfo_ismapped() \
                and not self.seek.dragging:
            self.top.pack_forget()
            self.bottom.pack_forget()
        self.after(250, self._tick)

    def _show_note(self, text: str) -> None:
        self.note.configure(text=text)
        self.note.place(relx=0.5, rely=0.5, anchor="center")

    def _hide_note(self) -> None:
        self.note.place_forget()

    def _failed(self, message: str) -> None:
        self._stop()
        self._show_note(t("player.failed", error=message))
        self.retry.place(relx=0.5, rely=0.5, y=40, anchor="center")

    # ---------- 끝내기 ----------
    def _save_resume(self) -> None:
        """멈춘 자리를 기억한다(처음 5초·끝 5초 안이면 지운다). 경로 해시와 초만."""
        pos, dur = self.state_.get("time-pos"), self.state_.get("duration")
        if not self.clips or not isinstance(pos, (int, float)) or not isinstance(dur, (int, float)):
            return
        key = self.clips[self.index].key
        vol = self.state_.get("volume")
        if isinstance(vol, (int, float)):
            self.settings["player_volume"] = round(vol)
        if not key:
            self.save_settings()
            return
        resume = dict(self.settings.get("resume") or {})
        resume.pop(key, None)
        if 5 < pos < dur - 5:
            resume[key] = round(pos, 1)
        self.settings["resume"] = dict(list(resume.items())[-RESUME_KEEP:])
        self.save_settings()

    def _stop(self) -> None:
        if self.pipe is not None:
            try:
                self.pipe.send(["quit"])
            except OSError:
                pass
            self.pipe.close()
            self.pipe = None
        if self.proc is not None:
            proc, self.proc = self.proc, None
            try:
                proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                proc.kill()  # 이 창이 띄운 mpv(그 PID)만
        if self.release is not None:
            release, self.release = self.release, None
            release()
        for key in ("time-pos", "duration", "demuxer-cache-time"):
            self.state_[key] = None
        self.state_["eof-reached"] = False

    def _on_destroy(self, event) -> None:
        """부모 창(클라우드 창 등)이 닫혀 같이 없어질 때도 mpv를 끄고 주소를 지운다."""
        if event.widget is self and not self.closed:
            self.closed = True
            self._stop()

    def close(self) -> None:
        if self.closed:
            return
        self._save_resume()
        self.closed = True
        self._stop()
        self.destroy()
