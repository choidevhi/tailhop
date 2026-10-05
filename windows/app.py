"""TailHop PC 앱: 여러 폰·PC와 연결, 기기별 대화방, 드래그 앤 드롭 보내기, QR·코드 페어링, 트레이 상주.

화면 문자열은 모두 i18n.t()로 꺼낸다(locales/*.json). 언어를 바꾸면 창 내용을 그 자리에서 다시 그린다.
보내기·받기는 Node의 스레드에서 돌기 때문에 다시 그려도 끊기지 않는다.
"""
from __future__ import annotations

import ctypes
import datetime as dt
import hashlib
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
import winreg
from collections import deque
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk
import pystray
import qrcode
from PIL import Image, ImageGrab
from tkinterdnd2 import DND_FILES, TkinterDnD

import bridge
import i18n
import protocol as p
import store
import thumbs
import vicons
from i18n import t
from node import PAIR_WINDOW_SEC, Node

APP_NAME = "TailHop"
APP_VERSION = "1.6.1"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
HISTORY_PAGE = 50  # 대화방에 한 번에 그리는 말풍선 수. "더 보기"로 늘린다(기록은 최대 store.HISTORY_LIMIT개).
SIDEBAR_WIDTH = 236
TEXT_PREVIEW = 400
THUMB_MEMORY = 120  # 화면에 들고 있는 썸네일 이미지 수(넘으면 오래된 것부터 버린다)
PAUSE_HOUR = 3600

# 중립 회색 팔레트 (라이트, 다크)
MAIN_BG = ("#FFFFFF", "#212121")
SIDEBAR_BG = ("#F9F9F9", "#181818")
BORDER = ("#E5E5E5", "#383838")
TEXT = ("#0D0D0D", "#ECECEC")
SECONDARY = ("#8F8F8F", "#9B9B9B")
HOVER = ("#EFEFEF", "#2A2A2A")
SELECTED = ("#E7E7E7", "#303030")
BUBBLE = ("#F1F1F3", "#303030")
INK = ("#0D0D0D", "#ECECEC")
INK_TEXT = ("#FFFFFF", "#0D0D0D")
INK_HOVER = ("#333333", "#CFCFCF")
MUTED_BUTTON = ("#D9D9D9", "#4A4A4A")
GREEN = ("#10A37F", "#19C37D")
RED = ("#E5484D", "#FF6369")
AMBER = ("#B7791F", "#F5A524")
DROP = ("#F4F4F5", "#262626")
DROP_ROW = ("#DCEBFF", "#22344D")
MONO = "Consolas"
KIND_ICON = {"phone": "smartphone", "pc": "laptop"}  # assets/icons/*.svg (Lucide)
_ICONS: dict[tuple, ctk.CTkImage] = {}
_SCALE = [1.0]  # 화면 배율(앱 창을 만든 뒤 정함)


def icon(name: str, size: int = 16, color=TEXT) -> ctk.CTkImage:
    """벡터 아이콘. 지금 화면 배율의 실제 픽셀 크기로 그려서(125 %·175 % 등도) 흐리지 않다. 라이트·다크 색을 따로 그린다."""
    light, dark = color if isinstance(color, tuple) else (color, color)
    key = (name, size, light, dark, _SCALE[0])
    cached = _ICONS.get(key)
    if cached is None:
        px = max(1, round(size * _SCALE[0]))
        svg = vicons.load(name, resource_path("assets/icons"))
        cached = _ICONS[key] = ctk.CTkImage(light_image=vicons.render(svg, px, light),
                                            dark_image=vicons.render(svg, px, dark), size=(size, size))
    return cached


def kind_label(kind: str) -> str:
    return t(f"kind.{kind}") if kind in KIND_ICON else kind


def resource_path(name: str) -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / name


def human_size(n: int) -> str:
    size = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{n} B"


def exe_parts() -> tuple[str, str]:
    """(실행 파일, 앞에 붙는 인자): 설치본은 TailHop.exe, 개발 중에는 pythonw.exe "app.py"."""
    if getattr(sys, "frozen", False):
        return sys.executable, ""
    return str(Path(sys.executable).with_name("pythonw.exe")), f'"{Path(__file__).resolve()}"'


def exe_command() -> str:
    target, args = exe_parts()
    return " ".join(x for x in (f'"{target}"', args, "--hidden") if x)


def autostart_enabled() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.QueryValueEx(key, APP_NAME)
            return True
    except OSError:
        return False


def set_autostart(enabled: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, exe_command())
        else:
            try:
                winreg.DeleteValue(key, APP_NAME)
            except OSError:
                pass


_FONTS: dict[tuple[str, int, str], ctk.CTkFont] = {}


def font(size: int, weight: str = "normal", family: str | None = None) -> ctk.CTkFont:
    """글꼴은 (글꼴, 크기, 굵기)마다 하나만 만들어 같이 쓴다. 말풍선마다 새로 만들면 Tk 글꼴이 수백 개 쌓인다."""
    key = (family or i18n.ui_font(), size, weight)
    cached = _FONTS.get(key)
    if cached is None:
        cached = _FONTS[key] = ctk.CTkFont(family=key[0], size=size, weight=weight)
    return cached


def ghost_button(parent, name: str, command, size: int = 32, **kw) -> ctk.CTkButton:
    """투명한 아이콘 버튼(name = assets/icons의 아이콘 이름)."""
    return ctk.CTkButton(parent, text="", image=icon(name, 18), command=command, width=size, height=size,
                         corner_radius=8, fg_color="transparent", hover_color=HOVER, **kw)


def round_icon(parent, name: str, size: int = 32, fg=SELECTED, color=TEXT) -> ctk.CTkFrame:
    circle = ctk.CTkFrame(parent, width=size, height=size, corner_radius=size // 2, fg_color=fg)
    circle.pack_propagate(False)
    circle.grid_propagate(False)
    ctk.CTkLabel(circle, text="", image=icon(name, max(14, size // 2 + 1), color),
                 fg_color="transparent").place(relx=0.5, rely=0.5, anchor="center")
    return circle


class CircleButton(ctk.CTkFrame):
    """정확한 원형 버튼(CTkButton은 모서리 반경 때문에 알약 모양이 된다)."""

    def __init__(self, parent, name: str, command, size: int = 34, fg="transparent", hover=HOVER,
                 color=TEXT, border=None, icon_size: int = 18) -> None:
        super().__init__(parent, width=size, height=size, corner_radius=size // 2, fg_color=fg,
                         border_width=1 if border else 0, border_color=border or fg, cursor="hand2")
        self.pack_propagate(False)
        self._fg, self._hover, self._command = fg, hover, command
        self.label = ctk.CTkLabel(self, text="", image=icon(name, icon_size, color), fg_color="transparent")
        self.label.place(relx=0.5, rely=0.5, anchor="center")
        for w in (self, self.label):
            w.bind("<Button-1>", lambda _e: self._command())
            w.bind("<Enter>", lambda _e: self.configure(fg_color=self._hover))
            w.bind("<Leave>", lambda _e: self.configure(fg_color=self._fg))

    def set_colors(self, fg, hover) -> None:
        self._fg, self._hover = fg, hover
        self.configure(fg_color=fg)


class TrayIcon(pystray.Icon):
    """트레이 아이콘을 알림 영역 크기(SM_CXSMICON, 화면 배율 반영) 그대로 불러온다.
    pystray 기본은 한 장을 ICO로 저장해 큰 아이콘 크기로 읽은 뒤 셸이 줄이므로 작은 크기에서 흐려진다.
    여기서는 크기마다 따로 그린 프레임(th_frames)으로 ICO를 만들어 Windows가 맞는 프레임을 고르게 한다."""

    SIZES = (16, 20, 24, 32, 40, 48, 64, 256)

    def _assert_icon_handle(self):  # pystray 0.19.5 (Windows) 내부 메서드
        if getattr(self, "_icon_handle", None):
            return
        from pystray._util import win32
        frames = getattr(self.icon, "th_frames", None) or [self.icon]
        small = ctypes.windll.user32.GetSystemMetrics(49) or 16  # SM_CXSMICON
        fd, path = tempfile.mkstemp(".ico")
        os.close(fd)
        try:
            frames[-1].save(path, format="ICO", sizes=[f.size for f in frames], append_images=frames[:-1])
            self._icon_handle = win32.LoadImage(None, path, win32.IMAGE_ICON, small, small, win32.LR_LOADFROMFILE)
        finally:
            os.unlink(path)


def drop_tree(widget, on_enter, on_leave, on_drop) -> None:
    """프레임과 모든 자식을 파일 드롭 대상으로 등록한다(어디에 놓아도 같은 기기로)."""
    widget.drop_target_register(DND_FILES)
    widget.dnd_bind("<<DropEnter>>", on_enter)
    widget.dnd_bind("<<DropLeave>>", on_leave)
    widget.dnd_bind("<<Drop>>", on_drop)
    for child in widget.winfo_children():
        drop_tree(child, on_enter, on_leave, on_drop)


def bind_tree(widget, sequence: str, handler) -> None:
    """프레임 안의 모든 자식까지 같은 이벤트를 건다(클릭 영역을 행 전체로)."""
    widget.bind(sequence, handler)
    for child in widget.winfo_children():
        bind_tree(child, sequence, handler)


def date_label(when: dt.datetime) -> str:
    weekdays = t("date.weekdays").split(",")
    months = t("date.months").split(",")
    return t("date.header", month=when.month, day=when.day, weekday=weekdays[when.weekday()],
             month_name=months[when.month - 1])


class App(ctk.CTk, TkinterDnD.DnDWrapper):
    def __init__(self, start_hidden: bool, request: bridge.Request | None = None) -> None:
        super().__init__(fg_color=MAIN_BG)
        self.TkdndVersion = TkinterDnD._require(self)
        _SCALE[0] = ctk.ScalingTracker.get_widget_scaling(self)  # 아이콘을 이 배율의 픽셀 크기로 그린다
        self.title(APP_NAME)
        self.geometry("880x700")
        self.minsize(560, 520)
        icon = resource_path("assets/tailhop.ico")
        if icon.exists():
            self.after(250, lambda: self.iconbitmap(str(icon)))
        self.protocol("WM_DELETE_WINDOW", self.withdraw)

        self.events: queue.Queue[tuple[str, dict]] = queue.Queue()
        self.node = Node(lambda kind, data: self.events.put((kind, data)))
        self.lang_var = tk.StringVar(value=self.node.settings.get("language", i18n.SYSTEM))
        i18n.set_language(self.lang_var.get())
        self.busy = False
        self.jobs: deque = deque()  # 보내는 중에 들어온 보내기 (이름표, 할 일). 앞의 것이 끝나면 차례로 보낸다
        self.qr_window: ctk.CTkToplevel | None = None
        self.join_window: ctk.CTkToplevel | None = None
        self.status: tuple[str, dict] = ("status.checking", {})
        self.status_warn = False
        self.unread: set[str] = set()
        self.sidebar_open = True
        self.visible_rows = HISTORY_PAGE
        self.autostart_var = tk.BooleanVar(value=autostart_enabled())
        self.sendto_var = tk.BooleanVar(value=bridge.sendto_enabled())
        self.hotkey_var = tk.BooleanVar(value=self.node.settings.get("hotkey") is True)
        self.hotkey: bridge.Hotkey | None = None
        self.preview_var = tk.BooleanVar(value=self.node.settings.get("previews") is not False)
        self.thumb_images: dict[str, ctk.CTkImage] = {}  # 원본 경로별 화면용 이미지(넣은 순서 = 오래된 순)
        self.thumb_waiting: dict[str, list[ctk.CTkLabel]] = {}
        self.thumbs = thumbs.Worker(lambda src, out: self.events.put(("thumb", {"src": str(src), "out": out})))
        self.receiving_var = tk.BooleanVar(value=self.node.receiving)
        last = self.node.settings.get("current")
        self.current: str | None = last if last in self.node.pairings else next(iter(self.node.pairings), None)

        # 창 전체를 드롭 영역으로(한 번만 등록, 대화 영역은 _build에서 다시 등록)
        self.drop_target_register(DND_FILES)
        self._bind_main_drop(self)
        self._build()
        self._refresh_all()
        self._start_tray()
        self.node.start()
        try:
            self.bridge: bridge.Server | None = bridge.Server(self._on_bridge)
        except OSError:
            self.bridge = None  # 명령줄·'보내기' 메뉴만 안 될 뿐 앱은 그대로 쓴다
        if self.hotkey_var.get():
            self._start_hotkey(quiet=True)
        self.after(100, self._poll)
        if start_hidden:
            self.withdraw()
        if request is not None and request.has_work:
            self.after(300, lambda: self._do_request(request))

    # ---------- 기기 ----------
    @property
    def pairing(self):
        return self.node.pairings.get(self.current) if self.current else None

    def _items_for(self, stable_id: str) -> list[dict]:
        return self.node.history.for_peer(stable_id)

    def _devices(self) -> list[tuple[str, store.Pairing]]:
        """수신 스레드가 목록을 바꿔도 안전하게 복사본으로 돈다."""
        return list(self.node.pairings.items())

    def _icon_for(self, item: dict) -> str:
        pairing = self.node.pairings.get(item.get("peer_id", ""))
        kind = pairing.kind if pairing else item.get("peer_kind", "phone")
        return KIND_ICON.get(kind, "smartphone")

    def _select(self, stable_id: str) -> None:
        if stable_id != self.current:
            self.visible_rows = HISTORY_PAGE
        self.current = stable_id
        self.unread.discard(stable_id)
        self.node.settings["current"] = stable_id
        self.node.save_settings()
        self._refresh_all()

    # ---------- 화면 ----------
    def _bind_main_drop(self, target) -> None:
        target.dnd_bind("<<DropEnter>>", lambda e: (self.log.configure(fg_color=DROP), e.action)[1])
        target.dnd_bind("<<DropLeave>>", lambda e: (self.log.configure(fg_color=MAIN_BG), e.action)[1])
        target.dnd_bind("<<Drop>>", self._on_drop)

    def _build(self) -> None:
        # 왼쪽: 사이드바
        self.sidebar = ctk.CTkFrame(self, fg_color=SIDEBAR_BG, corner_radius=0, width=SIDEBAR_WIDTH)
        self.sidebar.pack(side="left", fill="y")
        self.sidebar.pack_propagate(False)
        self.sidebar_line = ctk.CTkFrame(self, fg_color=BORDER, width=1, corner_radius=0)
        self.sidebar_line.pack(side="left", fill="y")

        head = ctk.CTkFrame(self.sidebar, fg_color="transparent", height=52)
        head.pack(fill="x", padx=10, pady=(6, 4))
        ctk.CTkLabel(head, text=APP_NAME, font=font(17, "bold"), text_color=TEXT).pack(side="left", padx=6)
        ghost_button(head, "ellipsis", self._open_app_menu).pack(side="right")
        ghost_button(head, "folder", self._open_save_dir).pack(side="right")

        new_row = ctk.CTkFrame(self.sidebar, fg_color="transparent", corner_radius=8, height=36, cursor="hand2")
        new_row.pack(fill="x", padx=8)
        new_row.pack_propagate(False)
        ctk.CTkLabel(new_row, text="", image=icon("plus", 16), width=22).pack(side="left", padx=(10, 6))
        ctk.CTkLabel(new_row, text=t("sidebar.new_device"), font=font(13), text_color=TEXT).pack(side="left")
        bind_tree(new_row, "<Button-1>", lambda _e: self._open_pair_menu())
        bind_tree(new_row, "<Enter>", lambda _e: new_row.configure(fg_color=HOVER))
        bind_tree(new_row, "<Leave>", lambda _e: new_row.configure(fg_color="transparent"))

        ctk.CTkLabel(self.sidebar, text=t("sidebar.devices"), font=font(12), text_color=SECONDARY,
                     anchor="w").pack(fill="x", padx=18, pady=(16, 4))
        # 아래쪽(먼저 고정): 수신 스위치와 상태
        bottom = ctk.CTkFrame(self.sidebar, fg_color="transparent")
        bottom.pack(side="bottom", fill="x", padx=14, pady=(4, 12))
        self.receive_switch = ctk.CTkSwitch(bottom, text=t("sidebar.receive"), variable=self.receiving_var,
                                            command=self._on_receive_switch, onvalue=True, offvalue=False,
                                            font=font(12), text_color=TEXT,
                                            progress_color=GREEN, switch_width=34, switch_height=18)
        self.receive_switch.pack(anchor="w", padx=2, pady=(0, 4))
        self.sidebar_status = ctk.CTkLabel(bottom, text="", font=font(11), text_color=SECONDARY,
                                           anchor="w", justify="left", wraplength=200)
        self.sidebar_status.pack(fill="x", padx=2)
        self.device_list = ctk.CTkScrollableFrame(self.sidebar, fg_color="transparent", corner_radius=0,
                                                  scrollbar_button_color=SIDEBAR_BG, scrollbar_button_hover_color=BORDER)
        self.device_list.pack(fill="both", expand=True, padx=(2, 0))

        # 오른쪽: 대화방
        self.main = ctk.CTkFrame(self, fg_color=MAIN_BG, corner_radius=0)
        self.main.pack(side="left", fill="both", expand=True)
        if not self.sidebar_open:
            self.sidebar.pack_forget()
            self.sidebar_line.pack_forget()

        top = ctk.CTkFrame(self.main, fg_color="transparent", height=52)
        top.pack(fill="x", padx=10, pady=(6, 0))
        top.pack_propagate(False)
        ghost_button(top, "panel-left", self._toggle_sidebar).pack(side="left")
        titles = ctk.CTkFrame(top, fg_color="transparent")
        titles.pack(side="left", padx=8)
        self.peer_name = ctk.CTkLabel(titles, text="", font=font(15, "bold"), text_color=TEXT, anchor="w", height=22)
        self.peer_name.pack(anchor="w")
        self.peer_detail = ctk.CTkLabel(titles, text="", font=font(11), text_color=SECONDARY, anchor="w", height=14)
        self.peer_detail.pack(anchor="w")
        self.device_menu_button = ghost_button(top, "ellipsis", self._open_device_menu)
        self.device_menu_button.pack(side="right")

        # 아래 입력창 (먼저 아래에 고정)
        composer_area = ctk.CTkFrame(self.main, fg_color="transparent")
        composer_area.pack(side="bottom", fill="x", padx=24, pady=(4, 18))
        self.progress_row = ctk.CTkFrame(composer_area, fg_color="transparent")
        self.progress = ctk.CTkProgressBar(self.progress_row, height=3, corner_radius=2, progress_color=INK, fg_color=BORDER)
        self.progress.pack(fill="x", padx=16)
        self.progress_label = ctk.CTkLabel(self.progress_row, text="", font=font(11), text_color=SECONDARY, height=18)
        self.progress_label.pack()
        composer = ctk.CTkFrame(composer_area, fg_color=MAIN_BG, corner_radius=26, border_width=1, border_color=BORDER)
        composer.pack(side="bottom", fill="x")
        self.text = ctk.CTkTextbox(composer, height=40, fg_color="transparent", border_width=0, font=font(13),
                                   text_color=TEXT, wrap="word", activate_scrollbars=False)
        self.text.pack(fill="x", padx=14, pady=(10, 0))
        tools = ctk.CTkFrame(composer, fg_color="transparent")
        tools.pack(fill="x", padx=10, pady=(0, 10))
        CircleButton(tools, "plus", self._pick_files, fg=MAIN_BG, border=BORDER, icon_size=18).pack(side="left")
        CircleButton(tools, "clipboard", self._send_clipboard, fg=MAIN_BG, color=SECONDARY,
                     icon_size=16).pack(side="left", padx=6)
        self.send_button = CircleButton(tools, "arrow-up", self._send_typed, fg=MUTED_BUTTON, hover=MUTED_BUTTON,
                                        color=INK_TEXT, icon_size=18)
        self.send_button.pack(side="right")
        # 받는 기기 선택(대화방 전환). 여러 기기가 있을 때 어디로 가는지 늘 보이게 한다.
        self.target_chip = ctk.CTkButton(tools, text="", command=self._open_target_menu, height=30, corner_radius=15,
                                         fg_color="transparent", hover_color=HOVER, text_color=SECONDARY,
                                         border_width=1, border_color=BORDER, font=font(12),
                                         image=icon("chevron-down", 14, SECONDARY), compound="right")
        self.target_chip.pack(side="right", padx=8)
        self._placeholder_on = False
        self.text.bind("<Return>", self._on_enter)
        self.text.bind("<<Paste>>", self._on_paste)
        self.text.bind("<KeyRelease>", lambda _e: self._on_typing())
        self.text.bind("<FocusIn>", lambda _e: self._placeholder(False))
        self.text.bind("<FocusOut>", lambda _e: self._placeholder(True))
        self._placeholder(True)

        # 가운데 대화 기록 (창 전체가 드롭 영역)
        self.log = ctk.CTkScrollableFrame(self.main, fg_color=MAIN_BG, corner_radius=0,
                                          scrollbar_button_color=BORDER, scrollbar_button_hover_color=SECONDARY)
        self.log.pack(fill="both", expand=True, padx=12)
        self.log.drop_target_register(DND_FILES)
        self._bind_main_drop(self.log)

    def _rebuild(self) -> None:
        """언어를 바꾼 뒤 창 내용을 다시 그린다. 진행 중인 보내기·받기는 다른 스레드라 그대로 이어진다."""
        typed = self._typed_text()
        for widget in (self.sidebar, self.sidebar_line, self.main):
            widget.destroy()
        self._build()
        if typed:
            self._placeholder(False)
            self.text.insert("1.0", typed)
            self._on_typing()
        self._refresh_all()
        self._update_tray()
        if self.busy:
            self._show_progress(t("progress.sending"), None)

    def _toggle_sidebar(self) -> None:
        if self.sidebar_open:
            self.sidebar.pack_forget()
            self.sidebar_line.pack_forget()
        else:
            self.sidebar.pack(side="left", fill="y", before=self.main)
            self.sidebar_line.pack(side="left", fill="y", before=self.main)
        self.sidebar_open = not self.sidebar_open

    # ---------- 입력창 ----------
    def _placeholder(self, show: bool) -> None:
        current = self.text.get("1.0", "end-1c")
        if show and not current:
            self.text.insert("1.0", t("composer.placeholder"))
            self.text.configure(text_color=SECONDARY)
            self._placeholder_on = True
        elif not show and self._placeholder_on:
            self.text.delete("1.0", "end")
            self.text.configure(text_color=TEXT)
            self._placeholder_on = False

    def _typed_text(self) -> str:
        return "" if self._placeholder_on else self.text.get("1.0", "end-1c")

    def _on_typing(self) -> None:
        lines = int(self.text.index("end-1c").split(".")[0])
        self.text.configure(height=min(6, max(2, lines)) * 20)
        ready = bool(self._typed_text().strip())
        self.send_button.set_colors(INK if ready else MUTED_BUTTON, INK_HOVER if ready else MUTED_BUTTON)

    def _on_paste(self, _event) -> str | None:
        """Ctrl+V: 클립보드의 파일·이미지는 바로 보내고, 텍스트는 기본 붙여넣기."""
        try:
            clip = ImageGrab.grabclipboard()
        except Exception:  # noqa: BLE001
            return None
        if isinstance(clip, list):
            self._send_paths([Path(x) for x in clip])
            return "break"
        if isinstance(clip, Image.Image):
            try:
                self.clipboard_get()
                return None  # 텍스트도 함께 있으면 텍스트로 붙여넣기
            except tk.TclError:
                pass
            self._send_clipboard_image(clip)
            return "break"
        return None

    def _send_clipboard_image(self, image: Image.Image, stable_id: str | None = None) -> None:
        target = self._target(stable_id)
        if target is None:
            return
        name = dt.datetime.now().strftime("clipboard_%Y%m%d_%H%M%S.png")
        folder = Path(tempfile.mkdtemp(prefix="tailhop_"))
        path = folder / name
        try:
            image.convert("RGBA" if "A" in image.getbands() else "RGB").save(path, "PNG")
        finally:
            image.close()  # 큰 스크린샷을 들고 있지 않는다

        def job() -> None:
            try:
                self.node.send_file(target, path)
            finally:
                path.unlink(missing_ok=True)
                folder.rmdir()

        self._run_bg(t("progress.sending_clipboard_image"), job)

    def _on_enter(self, event) -> str | None:
        if event.state & 0x1:  # Shift+Enter는 줄바꿈
            self.after(1, self._on_typing)
            return None
        self._send_typed()
        return "break"

    # ---------- 메뉴 ----------
    def _menu(self) -> tk.Menu:
        return tk.Menu(self, tearoff=0, font=(i18n.ui_font(), 10))

    def _open_app_menu(self) -> None:
        menu = self._menu()
        menu.add_command(label=t("menu.show_pair"), command=self._show_pair_qr)
        menu.add_command(label=t("menu.enter_code"), command=self._show_join_dialog)
        menu.add_separator()
        if self.node.receiving:
            menu.add_command(label=t("menu.pause"), command=lambda: self._set_receiving(False))
            menu.add_command(label=t("menu.pause_hour"), command=lambda: self._set_receiving(False, PAUSE_HOUR))
        else:
            menu.add_command(label=t("menu.resume"), command=lambda: self._set_receiving(True))
        menu.add_separator()
        menu.add_command(label=t("menu.open_folder"), command=self._open_save_dir)
        menu.add_command(label=t("menu.change_folder"), command=self._change_save_dir)
        menu.add_checkbutton(label=t("menu.autostart"), variable=self.autostart_var, command=self._toggle_autostart)
        menu.add_checkbutton(label=t("menu.previews"), variable=self.preview_var, command=self._toggle_previews)
        menu.add_checkbutton(label=t("menu.sendto"), variable=self.sendto_var, command=self._toggle_sendto)
        menu.add_checkbutton(label=t("menu.hotkey", key=bridge.HOTKEY_LABEL), variable=self.hotkey_var,
                             command=self._toggle_hotkey)
        languages = self._menu()
        languages.add_radiobutton(label=t("lang.system"), variable=self.lang_var, value=i18n.SYSTEM,
                                  command=lambda: self._set_language(i18n.SYSTEM))
        languages.add_separator()
        for code, name in i18n.LANGS.items():
            languages.add_radiobutton(label=name, variable=self.lang_var, value=code,
                                      command=lambda c=code: self._set_language(c))
        menu.add_cascade(label=t("menu.language"), menu=languages)
        menu.add_separator()
        menu.add_command(label=self._status_text(), state="disabled")
        menu.add_command(label=f"{APP_NAME} {APP_VERSION}", state="disabled")
        menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    def _open_pair_menu(self) -> None:
        menu = self._menu()
        menu.add_command(label=t("pairmenu.show"), command=self._show_pair_qr)
        menu.add_command(label=t("pairmenu.enter"), command=self._show_join_dialog)
        menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    def _open_target_menu(self) -> None:
        devices = self._devices()
        if not devices:
            self._open_pair_menu()
            return
        menu = self._menu()
        menu.add_command(label=t("target.header"), state="disabled")
        chosen = tk.StringVar(master=menu, value=self.current or "")
        menu._chosen = chosen  # type: ignore[attr-defined]  # 메뉴가 떠 있는 동안 변수를 붙잡아 둔다
        for stable_id, pairing in devices:
            # 지금 기기는 메뉴의 기본 선택 표시(라디오)로 보여 준다(글자 기호 대신).
            menu.add_radiobutton(label=f"{pairing.name}  ({kind_label(pairing.kind)})", variable=chosen,
                                 value=stable_id, command=lambda sid=stable_id: self._select(sid))
        menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    def _open_device_menu(self, stable_id: str | None = None) -> None:
        stable_id = stable_id or self.current
        pairing = self.node.pairings.get(stable_id) if stable_id else None
        if pairing is None:
            return
        menu = self._menu()
        menu.add_command(label=t("devmenu.code", code=p.confirm_code(pairing.secret)), state="disabled")
        menu.add_command(label=t("devmenu.kind", kind=kind_label(pairing.kind)), state="disabled")
        menu.add_command(label=t("devmenu.send_clipboard"), command=lambda: self._send_clipboard(stable_id))
        menu.add_command(label=t("devmenu.send_files"), command=lambda: self._pick_files(stable_id))
        menu.add_separator()
        menu.add_command(label=t("devmenu.clear"), command=lambda: self._clear_history(stable_id))
        menu.add_command(label=t("devmenu.unpair"), command=lambda: self._unpair(stable_id))
        menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    # ---------- 그리기 ----------
    def _status_text(self) -> str:
        key, kw = self.status
        return t(key, **kw)

    def _refresh_all(self) -> None:
        self._render_devices()
        self._refresh_header()
        self._render_history()

    def _refresh_header(self) -> None:
        pairing = self.pairing
        receiving = self.node.receiving
        if pairing is None:
            self.peer_name.configure(text=APP_NAME)
            self.peer_detail.configure(text=t("header.no_device"), text_color=SECONDARY, image=None)
            self.device_menu_button.pack_forget()
        else:
            self.peer_name.configure(text=pairing.name)
            kind = kind_label(pairing.kind)
            if not receiving:
                detail, color = t("header.paused", kind=kind), AMBER
            elif self.status_warn:
                detail, color = t("header.offline", kind=kind), RED
            else:
                detail, color = t("header.connected", kind=kind), GREEN
            self.peer_detail.configure(text=" " + detail, text_color=color, image=icon("dot", 10, color),
                                       compound="left")
            self.device_menu_button.pack(side="right")
        if pairing is None:
            self.target_chip.configure(text=t("chip.connect"))
        else:
            name = pairing.name if len(pairing.name) <= 18 else pairing.name[:17] + "…"
            self.target_chip.configure(text=name)
        self.receiving_var.set(receiving)
        color = AMBER if not receiving else RED if self.status_warn else SECONDARY
        warn = icon("triangle-alert", 13, color) if self.status_warn and self.status[0] == "status.offline" else None
        self.sidebar_status.configure(text=(" " if warn else "") + self._status_text(), text_color=color,
                                      image=warn, compound="left")

    def _render_devices(self) -> None:
        for child in self.device_list.winfo_children():
            child.destroy()
        if not self.node.pairings:
            ctk.CTkLabel(self.device_list, text=t("sidebar.no_devices"), font=font(12), text_color=SECONDARY,
                         anchor="w").pack(fill="x", padx=14, pady=6)
            return
        for stable_id, pairing in self._devices():
            last = self.node.history.last_for_peer(stable_id)
            if last is None:
                preview = t("device.no_messages")
            elif last["kind"] == "text":
                preview = last["text"][:60].replace("\n", " ")[:28]
            else:
                preview = last["name"][:26]
            selected = stable_id == self.current
            row = ctk.CTkFrame(self.device_list, fg_color=SELECTED if selected else "transparent", corner_radius=8,
                               height=52, cursor="hand2")
            row.pack(fill="x", padx=6, pady=1)
            row.pack_propagate(False)
            round_icon(row, KIND_ICON.get(pairing.kind, "smartphone"), 30).pack(side="left", padx=(8, 10))
            texts = ctk.CTkFrame(row, fg_color="transparent")
            texts.pack(side="left", fill="x", expand=True)
            weight = "bold" if stable_id in self.unread else "normal"
            ctk.CTkLabel(texts, text=pairing.name, font=font(13, weight), text_color=TEXT, anchor="w",
                         height=18).pack(fill="x", pady=(8, 0))
            is_file = last is not None and last["kind"] != "text"
            ctk.CTkLabel(texts, text=(" " + preview) if is_file else preview, font=font(11), text_color=SECONDARY,
                         anchor="w", height=15, image=icon("file", 12, SECONDARY) if is_file else None,
                         compound="left").pack(fill="x")
            if stable_id in self.unread:
                ctk.CTkFrame(row, width=8, height=8, corner_radius=4, fg_color=GREEN).pack(side="right", padx=10)
            bind_tree(row, "<Button-1>", lambda _e, sid=stable_id: self._select(sid))
            bind_tree(row, "<Button-3>", lambda _e, sid=stable_id: self._open_device_menu(sid))
            if not selected:
                bind_tree(row, "<Enter>", lambda _e, r=row: r.configure(fg_color=HOVER))
                bind_tree(row, "<Leave>", lambda _e, r=row: r.configure(fg_color="transparent"))
            # 파일을 기기 줄에 끌어다 놓으면 그 기기로 보낸다(대화방을 바꾸지 않아도 됨).
            rest = SELECTED if selected else "transparent"
            drop_tree(row,
                      lambda e, r=row: (r.configure(fg_color=DROP_ROW), e.action)[1],
                      lambda e, r=row, c=rest: (r.configure(fg_color=c), e.action)[1],
                      lambda e, sid=stable_id: self._on_drop(e, sid))

    def _render_history(self, scroll_to_end: bool = True) -> None:
        for child in self.log.winfo_children():
            child.destroy()
        all_items = self._items_for(self.current) if self.current else []
        items = all_items[-self.visible_rows:]
        if not items:
            self._render_empty()
            return
        hidden = len(all_items) - len(items)
        if hidden > 0:
            ctk.CTkButton(self.log, text=t("history.more", count=hidden), command=self._show_more, height=28,
                          corner_radius=14, fg_color="transparent", hover_color=HOVER, text_color=SECONDARY,
                          border_width=1, border_color=BORDER, font=font(11)).pack(pady=(10, 0))
        last_day = None
        for item in items:
            when = dt.datetime.fromtimestamp(item["time"])
            if when.date() != last_day:
                last_day = when.date()
                ctk.CTkLabel(self.log, text=date_label(when), font=font(11), text_color=SECONDARY).pack(pady=(16, 4))
            self._message(item, when)
        self.after(80, lambda: self.log._parent_canvas.yview_moveto(1.0 if scroll_to_end else 0.0))

    def _show_more(self) -> None:
        self.visible_rows += HISTORY_PAGE
        self._render_history(scroll_to_end=False)

    def _render_empty(self) -> None:
        empty = ctk.CTkFrame(self.log, fg_color="transparent")
        empty.pack(expand=True, fill="both", pady=(140, 0))
        mark = ctk.CTkFrame(empty, width=56, height=56, corner_radius=28, fg_color="transparent",
                            border_width=2, border_color=SECONDARY)
        mark.pack()
        mark.pack_propagate(False)
        ctk.CTkLabel(mark, text="", image=icon("arrow-right-left", 24, SECONDARY)).place(relx=0.5, rely=0.5,
                                                                                       anchor="center")
        if self.pairing:
            ctk.CTkLabel(empty, text=t("empty.title"), font=font(26), text_color=TEXT).pack(pady=(16, 4))
            ctk.CTkLabel(empty, text=t("empty.sub", name=self.pairing.name),
                         font=font(12), text_color=SECONDARY).pack()
        else:
            ctk.CTkLabel(empty, text=t("empty.connect_title"), font=font(26), text_color=TEXT).pack(pady=(16, 4))
            ctk.CTkLabel(empty, text=t("empty.connect_sub"), font=font(12), text_color=SECONDARY,
                         wraplength=460).pack()
            ctk.CTkButton(empty, text=t("empty.connect_button"), command=self._open_pair_menu, fg_color=INK,
                          hover_color=INK_HOVER, text_color=INK_TEXT, corner_radius=18, height=36, width=130,
                          font=font(13, "bold")).pack(pady=18)

    def _message(self, item: dict, when: dt.datetime) -> None:
        mine = item["dir"] == "out"
        row = ctk.CTkFrame(self.log, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=4)
        side = "e" if mine else "w"
        if not mine:
            ctk.CTkLabel(row, text=f" {item.get('peer', '')} · {when:%H:%M}", font=font(11), text_color=SECONDARY,
                         height=16, image=icon(self._icon_for(item), 12, SECONDARY),
                         compound="left").pack(anchor="w", padx=2)
        box = ctk.CTkFrame(row, fg_color=BUBBLE if mine else "transparent", corner_radius=18,
                           border_width=0 if mine or item["kind"] == "text" else 1, border_color=BORDER)
        box.pack(anchor=side)
        if item["kind"] == "text":
            full_len = int(item.get("text_len") or len(item["text"]))
            shown = item["text"]
            text = shown if full_len <= TEXT_PREVIEW else shown[:TEXT_PREVIEW].rstrip() + " …"
            ctk.CTkLabel(box, text=text, font=font(13), text_color=TEXT, justify="left", wraplength=420,
                         anchor="w").pack(padx=14 if mine else 2, pady=8 if mine else 2)
            hint = t("msg.click_copy") if full_len <= TEXT_PREVIEW else t("msg.click_copy_long", count=f"{full_len:,}")
        else:
            inner = ctk.CTkFrame(box, fg_color="transparent")
            inner.pack(padx=12, pady=10)
            self._thumbnail(box, inner, item)
            round_icon(inner, "file", 38, fg=MAIN_BG if mine else BUBBLE).pack(side="left")
            names = ctk.CTkFrame(inner, fg_color="transparent")
            names.pack(side="left", padx=(10, 4))
            name = item["name"] if len(item["name"]) <= 44 else item["name"][:41] + "…"
            ctk.CTkLabel(names, text=name, font=font(13, "bold"), text_color=TEXT, anchor="w", height=19).pack(anchor="w")
            ctk.CTkLabel(names, text=human_size(item.get("size", 0)), font=font(11), text_color=SECONDARY,
                         anchor="w", height=15).pack(anchor="w")
            hint = t("msg.click_show")
        meta = f"{when:%H:%M} · {hint}" if mine else hint
        ctk.CTkLabel(row, text=meta, font=font(10), text_color=SECONDARY, height=14).pack(anchor=side, padx=6)
        bind_tree(box, "<Button-1>", lambda _e, it=item: self._open_history_item(it))

    def _thumbnail(self, box, inner, item: dict) -> None:
        """이미지·영상 파일이면 말풍선 위쪽에 미리보기 자리를 두고, 썸네일이 준비되면 채운다."""
        path = Path(item.get("path") or "")
        if not self.preview_var.get() or not thumbs.previewable(item.get("name", "")) or not path.is_file():
            return

        key = str(path)
        label = ctk.CTkLabel(box, text="", fg_color="transparent")
        label.pack(before=inner, padx=8, pady=(8, 0), anchor="w")
        image = self.thumb_images.get(key)
        if image is not None:
            label.configure(image=image)
            return

        self.thumb_waiting.setdefault(key, []).append(label)
        self.thumbs.request(path)

    def _on_thumb(self, src: str, out: Path | None) -> None:
        labels = [x for x in self.thumb_waiting.pop(src, []) if x.winfo_exists()]
        if out is None:
            for label in labels:
                label.pack_forget()
            return

        try:
            with Image.open(out) as im:
                im.load()
                picture = im.copy()
        except OSError:
            return

        size = (max(1, picture.width // 2), max(1, picture.height // 2))  # 캐시는 2배 픽셀(고배율 화면용)
        image = ctk.CTkImage(light_image=picture, dark_image=picture, size=size)
        self.thumb_images[src] = image
        while len(self.thumb_images) > THUMB_MEMORY:
            self.thumb_images.pop(next(iter(self.thumb_images)))
        canvas = self.log._parent_canvas
        at_bottom = canvas.yview()[1] >= 0.98
        for label in labels:
            label.configure(image=image)
        if labels and at_bottom:  # 맨 아래를 보고 있었으면 그림이 늘어난 만큼 따라 내려간다
            self.after(30, lambda: canvas.yview_moveto(1.0))

    def _toggle_previews(self) -> None:
        self.node.settings["previews"] = bool(self.preview_var.get())
        self.node.save_settings()
        self._render_history()

    def _open_history_item(self, item: dict) -> None:
        if item["kind"] == "text":
            self.clipboard_clear()
            self.clipboard_append(self.node.history.full_text(item))
            self._flash(t("flash.copied"))
        else:
            path = Path(item["path"])
            if path.is_file():
                # 받은 파일을 직접 실행하지 않고 탐색기에서 선택만 한다. 경로는 통째로 따옴표로 묶어 넘긴다
                # (파일명의 쉼표가 explorer 인자로 쪼개지지 않게).
                cmd = store.explorer_select_command(path)
                if cmd is not None:
                    subprocess.Popen(cmd)
                else:
                    os.startfile(str(path.parent))  # 폴더만 연다
            else:
                messagebox.showinfo(APP_NAME, t("dialog.file_missing"), parent=self)

    def _clear_history(self, stable_id: str) -> None:
        name = self.node.pairings[stable_id].name
        if messagebox.askyesno(APP_NAME, t("dialog.clear_confirm", name=name), parent=self):
            self.node.history.clear_peer(stable_id)
            self._refresh_all()

    # ---------- 진행 표시 ----------
    def _show_progress(self, text: str, fraction: float | None) -> None:
        if not self.progress_row.winfo_ismapped():
            self.progress_row.pack(fill="x", side="top", pady=(0, 6))
        if fraction is None:
            self.progress.configure(mode="indeterminate")
            self.progress.start()
        else:
            self.progress.stop()
            self.progress.configure(mode="determinate")
            self.progress.set(fraction)
        self.progress_label.configure(text=text)

    def _flash(self, text: str) -> None:
        self._show_progress(text, 1.0)
        self.after(2000, self._hide_progress)

    def _hide_progress(self) -> None:
        if not self.busy and self.progress_row.winfo_exists():
            self.progress.stop()
            self.progress_row.pack_forget()

    # ---------- 보내기 ----------
    def _target(self, stable_id: str | None = None) -> str | None:
        """받을 기기: 지정한 기기, 없으면 지금 대화방."""
        target = stable_id if stable_id in self.node.pairings else self.current
        if target is None:
            messagebox.showinfo(APP_NAME, t("dialog.connect_first"), parent=self)
        return target

    def _target_name(self, stable_id: str) -> str:
        pairing = self.node.pairings.get(stable_id)
        return pairing.name if pairing else ""

    def _run_bg(self, label: str, job) -> None:
        """보내기는 한 번에 하나씩. 보내는 중이면 대기열에 넣고 앞의 것이 끝나면 이어서 보낸다."""
        if self.busy:
            self.jobs.append((label, job))
            self._notify(t("notify.queued", count=len(self.jobs)))
            return
        self.busy = True
        self._show_progress(label, None)

        def work() -> None:
            try:
                # 막 켜진 앱(명령줄·'보내기' 메뉴로 켰을 때): Tailscale 주소를 얻을 때까지 15초까지 기다린다
                waited = threading.Event()
                for _ in range(150):
                    if self.node.me is not None:
                        break
                    waited.wait(0.1)
                job()
                self.events.put(("job_done", {}))
            except Exception as exc:  # noqa: BLE001
                self.events.put(("job_error", {"error": str(exc)}))

        threading.Thread(target=work, daemon=True).start()

    def _send_paths(self, paths: list[Path], stable_id: str | None = None) -> None:
        """파일은 그대로, 폴더는 임시 zip(<폴더 이름>.zip)으로 묶어 보낸다."""
        target = self._target(stable_id)
        items = [x for x in paths if x.is_file() or x.is_dir()]
        if target is None or not items:
            return

        def job() -> None:
            for item in items:
                if item.is_file():
                    self.node.send_file(target, item)
                    continue
                self.events.put(("packing", {"name": item.name}))
                folder = Path(tempfile.mkdtemp(prefix="tailhop_"))
                try:
                    self.node.send_file(target, bridge.pack_folder(item, folder), record_path=item)
                finally:
                    shutil.rmtree(folder, ignore_errors=True)

        self._run_bg(t("progress.sending_files", name=self._target_name(target), count=len(items)), job)

    def _on_drop(self, event, stable_id: str | None = None) -> str:
        self.log.configure(fg_color=MAIN_BG)
        if stable_id is not None:
            self.after(10, self._render_devices)  # 드롭 강조 색 되돌리기
        self._send_paths([Path(x) for x in self.tk.splitlist(event.data)], stable_id)
        return event.action

    def _pick_files(self, stable_id: str | None = None) -> None:
        target = self._target(stable_id)
        if target is None:
            return
        chosen = filedialog.askopenfilenames(title=t("pick.title", name=self._target_name(target)), parent=self)
        if chosen:
            self._send_paths([Path(x) for x in chosen], target)

    def _send_typed(self) -> None:
        text = self._typed_text()
        target = self._target() if text.strip() else None
        if target:
            self._run_bg(t("progress.sending"), lambda: self.node.send_text(target, text))
            self.text.delete("1.0", "end")
            self._on_typing()

    def _send_clipboard(self, stable_id: str | None = None) -> None:
        """클립보드 보내기(트레이·단축키): 텍스트, 없으면 복사한 파일·폴더, 없으면 이미지."""
        target = self._target(stable_id)
        if target is None:
            return
        try:
            text = self.clipboard_get()
        except tk.TclError:
            text = ""
        if text.strip():
            self._run_bg(t("progress.sending_clipboard", name=self._target_name(target)),
                         lambda: self.node.send_text(target, text))
            return
        try:
            clip = ImageGrab.grabclipboard()
        except Exception:  # noqa: BLE001
            clip = None
        if isinstance(clip, list) and clip:
            self._send_paths([Path(x) for x in clip], target)
        elif isinstance(clip, Image.Image):
            self._send_clipboard_image(clip, target)
        else:
            self._notify(t("dialog.no_clip_text"))

    # ---------- 페어링 ----------
    def _show_pair_qr(self) -> None:
        try:
            url = self.node.begin_pairing()
            code = self.node.pairing_code()
        except RuntimeError as exc:
            messagebox.showerror(APP_NAME, str(exc), parent=self)
            return
        if self.qr_window:
            self.qr_window.destroy()
        win = ctk.CTkToplevel(self, fg_color=MAIN_BG)
        win.title(t("pair.title"))
        win.resizable(False, False)
        win.transient(self)
        ctk.CTkLabel(win, text=t("pair.title"), font=font(22), text_color=TEXT).pack(pady=(22, 2), padx=40)
        ctk.CTkLabel(win, text=t("pair.phone_hint"), font=font(12), text_color=SECONDARY).pack()
        img = qrcode.make(url, box_size=7, border=2).get_image().convert("RGB")
        qr = ctk.CTkImage(light_image=img, dark_image=img, size=img.size)
        frame = ctk.CTkFrame(win, fg_color="#FFFFFF", corner_radius=16, border_width=1, border_color=BORDER)
        frame.pack(pady=(12, 10))
        ctk.CTkLabel(frame, image=qr, text="").pack(padx=10, pady=10)

        ctk.CTkLabel(win, text=t("pair.pc_hint"), font=font(12), text_color=SECONDARY, wraplength=420).pack(padx=24)
        where, body = code.split("-", 1)
        groups = body.split("-")
        half = (len(groups) + 1) // 2
        shown = f"{where}\n{'-'.join(groups[:half])}\n{'-'.join(groups[half:])}"
        code_box = ctk.CTkFrame(win, fg_color=BUBBLE, corner_radius=12)
        code_box.pack(padx=24, pady=(6, 6), fill="x")
        ctk.CTkLabel(code_box, text=shown, font=font(15, "bold", MONO), text_color=TEXT,
                     justify="center").pack(padx=12, pady=10)
        buttons = ctk.CTkFrame(win, fg_color="transparent")
        buttons.pack()

        def copy(value: str, done_key: str) -> None:
            self.clipboard_clear()
            self.clipboard_append(value)
            hint.configure(text=t(done_key))

        for label_key, value, done_key in (("pair.copy_code", code, "pair.copied_code"),
                                           ("pair.copy_link", url, "pair.copied_link")):
            ctk.CTkButton(buttons, text=t(label_key), command=lambda v=value, d=done_key: copy(v, d), height=30,
                          corner_radius=15, fg_color="transparent", hover_color=HOVER, text_color=TEXT,
                          border_width=1, border_color=BORDER, font=font(12)).pack(side="left", padx=4)
        countdown = ctk.CTkLabel(win, text="", font=font(13, "bold"), text_color=TEXT)
        countdown.pack(pady=(10, 0))
        hint = ctk.CTkLabel(win, text=t("pair.one_time"), font=font(11), text_color=SECONDARY, wraplength=420)
        hint.pack(pady=(2, 20))
        self.qr_window = win
        remaining = [PAIR_WINDOW_SEC]

        def tick() -> None:
            if not win.winfo_exists():
                return
            if remaining[0] <= 0:
                self.node.cancel_pairing()
                win.destroy()
                return
            countdown.configure(text=t("pair.expires", seconds=remaining[0]))
            remaining[0] -= 1
            win.after(1000, tick)

        def on_close() -> None:
            self.node.cancel_pairing()
            win.destroy()

        win.protocol("WM_DELETE_WINDOW", on_close)
        win.after(200, win.lift)
        tick()

    def _show_join_dialog(self) -> None:
        if self.join_window and self.join_window.winfo_exists():
            self.join_window.lift()
            return
        win = ctk.CTkToplevel(self, fg_color=MAIN_BG)
        win.title(t("join.title"))
        win.resizable(False, False)
        win.transient(self)
        ctk.CTkLabel(win, text=t("join.title"), font=font(22), text_color=TEXT).pack(pady=(22, 2), padx=40)
        ctk.CTkLabel(win, text=t("join.hint"), font=font(12), text_color=SECONDARY, wraplength=380,
                     justify="center").pack(padx=24)
        box = ctk.CTkTextbox(win, height=84, width=400, font=font(14, family=MONO), fg_color=BUBBLE,
                             border_width=0, corner_radius=12, text_color=TEXT, wrap="char")
        box.pack(padx=24, pady=12)
        status = ctk.CTkLabel(win, text=t("join.same_account"), font=font(11), text_color=SECONDARY, wraplength=380)
        status.pack()
        button = ctk.CTkButton(win, text=t("join.connect"), fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT,
                               corner_radius=18, height=36, width=130, font=font(13, "bold"))
        button.pack(pady=(10, 22))

        def submit() -> None:
            text = box.get("1.0", "end").strip()
            if not text:
                return
            try:
                p.parse_pair_input(text)  # 형식·오타는 바로 알려 준다
            except p.ProtocolError as exc:
                status.configure(text=str(exc), text_color=RED)
                return
            button.configure(state="disabled", text=t("join.connecting"))
            status.configure(text=t("join.checking"), text_color=SECONDARY)

            def work() -> None:
                try:
                    self.node.join_pairing(text)
                except Exception as exc:  # noqa: BLE001
                    self.events.put(("join_error", {"error": str(exc) or type(exc).__name__}))

            threading.Thread(target=work, daemon=True).start()

        def on_error(message: str) -> None:
            if win.winfo_exists():
                button.configure(state="normal", text=t("join.connect"))
                status.configure(text=t("join.failed", error=message), text_color=RED)

        button.configure(command=submit)
        box.bind("<Return>", lambda _e: (submit(), "break")[1])
        win.on_error = on_error  # type: ignore[attr-defined]
        self.join_window = win
        win.after(200, lambda: (win.lift(), box.focus_set()))

    def _unpair(self, stable_id: str) -> None:
        name = self.node.pairings[stable_id].name
        if messagebox.askyesno(APP_NAME, t("dialog.unpair_confirm", name=name), parent=self):
            self.node.unpair(stable_id)
            self.unread.discard(stable_id)
            if self.current == stable_id:
                self.current = next(iter(self.node.pairings), None)
                self.visible_rows = HISTORY_PAGE
            self._refresh_all()
            self._update_tray()

    # ---------- 설정 ----------
    def _open_save_dir(self) -> None:
        self.node.save_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(str(self.node.save_dir))  # 폴더 열기(명령줄을 거치지 않아 경로의 쉼표와 상관없다)

    def _change_save_dir(self) -> None:
        chosen = filedialog.askdirectory(title=t("folder.title"), initialdir=str(self.node.save_dir), parent=self)
        if chosen:
            self.node.set_save_dir(chosen)
            self._flash(t("flash.save_dir", path=chosen))

    def _toggle_autostart(self) -> None:
        try:
            set_autostart(self.autostart_var.get())
        except OSError as exc:
            messagebox.showerror(APP_NAME, str(exc), parent=self)
            self.autostart_var.set(autostart_enabled())

    def _toggle_sendto(self) -> None:
        target, args = exe_parts()
        try:
            bridge.set_sendto(self.sendto_var.get(), target, args, str(resource_path("assets/tailhop.ico")))
        except (OSError, subprocess.SubprocessError) as exc:
            messagebox.showerror(APP_NAME, str(exc), parent=self)
        self.sendto_var.set(bridge.sendto_enabled())
        if self.sendto_var.get():
            self._flash(t("flash.sendto_on"))

    def _start_hotkey(self, quiet: bool = False) -> bool:
        self.hotkey = bridge.Hotkey(lambda: self.events.put(("hotkey", {})))
        if self.hotkey.ok.is_set():
            return True

        self.hotkey = None
        if not quiet:
            messagebox.showwarning(APP_NAME, t("dialog.hotkey_taken", key=bridge.HOTKEY_LABEL), parent=self)
        return False

    def _toggle_hotkey(self) -> None:
        on = self.hotkey_var.get()
        if self.hotkey is not None:
            self.hotkey.stop()
            self.hotkey = None
        if on and not self._start_hotkey():
            self.hotkey_var.set(False)
            on = False
        self.node.settings["hotkey"] = on
        self.node.save_settings()

    # ---------- 명령줄·'보내기' 메뉴 ----------
    def _on_bridge(self, data: dict) -> dict:
        """bridge 스레드에서 불린다. 기기 목록·검사만 여기서 답하고, 보내기는 이벤트로 화면 스레드에 넘긴다."""
        try:
            req = bridge.request_from_json(data)
        except bridge.ArgError:
            return {"ok": False, "error": "bad_request"}
        devices = [{"id": sid, "name": x.name, "kind": x.kind, "current": sid == self.current}
                   for sid, x in self._devices()]
        if req.list_devices:
            return {"ok": True, "devices": devices}
        target = bridge.resolve_device([(d["id"], d["name"]) for d in devices], req.to, self.current)
        if target is None:
            return {"ok": False, "error": "no_device", "devices": devices}
        missing = [x for x in req.paths if not Path(x).exists()]
        if missing:
            return {"ok": False, "error": "missing", "paths": missing}
        self.events.put(("bridge", {"request": req, "target": target}))
        return {"ok": True, "to": self.node.pairings[target].name}

    def _do_request(self, req: bridge.Request, target: str | None = None) -> None:
        if target is None:
            devices = [(sid, x.name) for sid, x in self._devices()]
            target = bridge.resolve_device(devices, req.to, self.current)
            if target is None:
                self._show()
                messagebox.showwarning(APP_NAME, t("dialog.no_device", name=req.to or ""), parent=self)
                return
        text = req.text
        if text is not None and text.strip():
            self._run_bg(t("progress.sending"), lambda: self.node.send_text(target, text))
        if req.paths:
            self._send_paths([Path(x) for x in req.paths], target)
        self._notify(t("notify.sending_to", name=self._target_name(target)))

    def _set_language(self, choice: str) -> None:
        self.lang_var.set(choice)
        self.node.settings["language"] = choice
        self.node.save_settings()
        i18n.set_language(choice)
        self._rebuild()

    def _set_receiving(self, on: bool, seconds: float | None = None) -> None:
        """수신 켜기/끄기(끄면 포트를 닫는다). 보내기는 계속 된다."""
        self.node.set_receiving(on, seconds)
        if on:
            self.status = ("status.checking", {})
        else:
            self.status = self._paused_status(self.node.pause_until)
            if self.qr_window is not None and self.qr_window.winfo_exists():
                self.qr_window.destroy()  # 수신을 끄면 페어링 창도 닫힌다
        self._refresh_header()
        self._update_tray()
        self._flash(t("flash.resumed") if on else t("flash.paused"))

    def _on_receive_switch(self) -> None:
        self._set_receiving(bool(self.receiving_var.get()))

    @staticmethod
    def _paused_status(until: float | None) -> tuple[str, dict]:
        if until:
            return "status.paused_until", {"time": dt.datetime.fromtimestamp(until).strftime("%H:%M")}
        return "status.paused", {}

    # ---------- 트레이 ----------
    def _start_tray(self) -> None:
        # 앱 아이콘과 같은 벡터 원본을 트레이 크기별로 따로 그린다. 수신 중지 때는 흑백으로 상태를 보인다.
        logo = vicons.load("tailhop-logo", resource_path("assets/icons"))
        frames = [vicons.render(logo, s) for s in TrayIcon.SIZES]
        self.tray_image = frames[-1]
        self.tray_image.th_frames = frames  # type: ignore[attr-defined]
        self.tray_image_paused = self.tray_image.convert("LA").convert("RGBA")
        self.tray_image_paused.th_frames = [f.convert("LA").convert("RGBA") for f in frames]  # type: ignore[attr-defined]
        menu = pystray.Menu(
            pystray.MenuItem(lambda _i: t("tray.open"), lambda: self.after(0, self._show), default=True),
            pystray.MenuItem(lambda _i: t("tray.send_clipboard"), pystray.Menu(self._tray_targets)),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda _i: t("tray.receiving"),
                             lambda: self.after(0, lambda: self._set_receiving(not self.node.receiving)),
                             checked=lambda _i: self.node.receiving),
            pystray.MenuItem(lambda _i: t("tray.pause_hour"),
                             lambda: self.after(0, lambda: self._set_receiving(False, PAUSE_HOUR)),
                             visible=lambda _i: self.node.receiving),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda _i: t("tray.quit"), lambda: self.after(0, self._quit)),
        )
        self.tray = TrayIcon(APP_NAME, self._tray_icon(), self._tray_title(), menu)
        threading.Thread(target=self.tray.run, daemon=True).start()

    def _tray_icon(self) -> Image.Image:
        return self.tray_image if self.node.receiving else self.tray_image_paused

    def _tray_title(self) -> str:
        return APP_NAME if self.node.receiving else t("tray.title_paused")

    def _tray_targets(self):
        """트레이 '클립보드 보내기' 하위 메뉴: 받을 기기를 고른다."""
        def action(sid: str):
            return lambda: self.after(0, lambda: self._send_clipboard(sid))

        devices = self._devices()
        if not devices:
            return [pystray.MenuItem(t("tray.no_devices"), None, enabled=False)]
        return [pystray.MenuItem(t("tray.device", kind=kind_label(x.kind), name=x.name), action(sid))
                for sid, x in devices]

    def _update_tray(self) -> None:
        try:
            self.tray.icon = self._tray_icon()
            self.tray.title = self._tray_title()
            self.tray.update_menu()
        except Exception:  # noqa: BLE001 - 트레이가 아직 준비 전이면 다음 변경 때 반영
            pass

    def _notify(self, message: str) -> None:
        try:
            self.tray.notify(message, APP_NAME)
        except Exception:  # noqa: BLE001 - 알림 실패는 무시
            pass

    def _show(self) -> None:
        self.deiconify()
        self.lift()
        self.focus_force()

    def _next_job(self) -> None:
        if self.jobs and not self.busy:
            label, job = self.jobs.popleft()
            self._run_bg(label, job)

    def _quit(self) -> None:
        if self.bridge is not None:
            self.bridge.close()
        if self.hotkey is not None:
            self.hotkey.stop()
        self.node.stop()
        self.tray.stop()
        self.destroy()

    # ---------- 이벤트 ----------
    def _poll(self) -> None:
        try:
            while True:
                kind, data = self.events.get_nowait()
                self._handle_event(kind, data)
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _handle_event(self, kind: str, data: dict) -> None:
        if kind == "listening":
            self.status, self.status_warn = ("status.listening", {"name": data["name"]}), False
            self._refresh_header()
            self._update_tray()
        elif kind == "paused":
            self.status, self.status_warn = self._paused_status(data.get("until")), False
            self._refresh_header()
            self._update_tray()
        elif kind == "offline":
            self.status, self.status_warn = ("status.offline", {"error": data["error"]}), True
            self._refresh_header()
        elif kind == "paired":
            for attr in ("qr_window", "join_window"):
                win = getattr(self, attr)
                if win is not None and win.winfo_exists():
                    win.destroy()
                setattr(self, attr, None)
            self._select(data["id"])
            self._update_tray()
            messagebox.showinfo(APP_NAME, t("dialog.paired", name=data["name"], code=data["code"]), parent=self)
        elif kind == "received":
            sender = data.get("peer_id")
            if sender and sender != self.current:
                self.unread.add(sender)
            self._render_devices()
            if sender == self.current or sender is None:
                self._render_history()
            if data["kind"] == "text":
                full = data.get("full_text", data["text"])
                self.clipboard_clear()
                self.clipboard_append(full)
                self._notify(t("notify.text", peer=data.get("peer", ""), text=full[:100]))
            else:
                self._notify(t("notify.file", peer=data.get("peer", ""), name=data["name"]))
            self._flash(t("flash.received"))
        elif kind == "peer_unpaired":
            # 상대(폰)가 확인 코드가 다르다며 페어링을 취소했다. 이 PC에서도 지워졌다.
            self.unread.discard(data["id"])
            if self.current == data["id"]:
                self.current = next(iter(self.node.pairings), None)
                self.visible_rows = HISTORY_PAGE
            self._refresh_all()
            self._update_tray()
            messagebox.showwarning(APP_NAME, t("dialog.peer_unpaired", name=data["name"]), parent=self)
        elif kind == "sent":
            self._render_devices()
            self._render_history()
        elif kind == "progress":
            total = max(1, data["total"])
            key = "progress.receiving_bytes" if data["dir"] == "in" else "progress.sending_bytes"
            self._show_progress(t(key, done=human_size(data["done"]), total=human_size(data["total"])),
                                data["done"] / total)
        elif kind == "job_done":
            self.busy = False
            self._flash(t("flash.sent"))
            self._next_job()
        elif kind == "job_error":
            self.busy = False
            self._hide_progress()
            if self.winfo_viewable():
                messagebox.showerror(APP_NAME, t("dialog.send_failed", error=data["error"]), parent=self)
            else:  # 창이 숨어 있을 때(명령줄·단축키) 보이지 않는 대화상자 대신 알림
                self._notify(t("dialog.send_failed", error=data["error"]))
            self._next_job()
        elif kind == "thumb":
            self._on_thumb(data["src"], data["out"])
        elif kind == "packing":
            self._show_progress(t("progress.packing", name=data["name"]), None)
        elif kind == "hotkey":
            if self.current is None:
                self._notify(t("dialog.connect_first"))
            else:
                self._send_clipboard()
        elif kind == "bridge":
            self._do_request(data["request"], data["target"])
        elif kind == "join_error":
            if self.join_window is not None and self.join_window.winfo_exists():
                self.join_window.on_error(data["error"])
            else:
                messagebox.showerror(APP_NAME, t("dialog.join_failed", error=data["error"]), parent=self)
        elif kind == "rejected":
            self.status = ("status.blocked", {"error": data["error"]})
            self._refresh_header()
            if data.get("code") == "unpaired" and not getattr(self, "_unpaired_warned", False):
                self._unpaired_warned = True  # 한 번만 안내
                if messagebox.askyesno(APP_NAME, t("dialog.unpaired_peer", ip=data["ip"]), parent=self):
                    self._show_pair_qr()


def instance_mutex() -> str:
    """기본 설정 폴더면 1.5 이하와 같은 이름(새 버전과 예전 버전이 함께 뜨지 않게), 다른 폴더면 폴더별 이름."""
    if store.APP_DIR == store.DEFAULT_APP_DIR:
        return "Local\\TailHopSingleInstance"
    digest = hashlib.sha256(str(store.APP_DIR).casefold().encode("utf-8")).hexdigest()[:16]
    return f"Local\\TailHopSingleInstance-{digest}"


def _console() -> None:
    """창 없는 exe(--windowed)를 터미널에서 불렀을 때 그 터미널에 결과를 찍는다."""
    if sys.stdout is None and ctypes.windll.kernel32.AttachConsole(-1):  # ATTACH_PARENT_PROCESS
        sys.stdout = open("CONOUT$", "w", encoding="utf-8")  # noqa: SIM115 - 프로세스가 끝날 때까지 쓴다
        sys.stderr = sys.stdout


def _say(text: str) -> None:
    if sys.stdout is not None:
        print(text)


def _print_reply(req: bridge.Request, reply: dict) -> int:
    if req.list_devices or reply.get("error") == "no_device":
        for d in reply.get("devices", []):
            _say(f"{'*' if d['current'] else ' '} {d['name']}  ({kind_label(d['kind'])}, {d['id']})")
    if reply.get("ok"):
        if not req.list_devices:
            _say(t("cli.sending", name=reply.get("to", "")))
        return 0

    error = reply.get("error")
    if error == "no_device":
        _say(t("dialog.no_device", name=req.to or ""))
    elif error == "missing":
        _say(t("cli.missing", paths=", ".join(reply.get("paths", []))))
    else:
        _say(t("cli.failed", error=error))
    return 1


def _forward(req: bridge.Request) -> int:
    """이미 떠 있는 앱에 요청을 넘긴다. 막 켜지는 중이면 bridge.json이 생길 때까지 5초까지 다시 시도한다."""
    waited = threading.Event()
    for _ in range(25):
        try:
            return _print_reply(req, bridge.send_request(req))
        except OSError:
            waited.wait(0.2)
    _say(t("cli.no_app"))
    return 1


def main() -> int:
    i18n.set_language(store.load_settings().get("language", i18n.SYSTEM))
    _console()
    try:
        req = bridge.parse_args(sys.argv[1:])
    except bridge.ArgError as exc:
        _say(t("cli.usage", arg=str(exc)))
        return 2

    # 중복 실행 방지: 이미 떠 있으면 할 일만 넘기고 끝낸다. 설정 폴더마다 하나(bridge.json도 그 폴더에 있다)
    ctypes.windll.kernel32.CreateMutexW(None, False, instance_mutex())
    if ctypes.windll.kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
        if req.has_work:
            return _forward(req)

        ctypes.windll.user32.MessageBoxW(None, t("dialog.already_running"), APP_NAME, 0x40)
        return 0

    if req.list_devices:
        _say(t("cli.no_app"))
        return 1

    ctk.set_appearance_mode("system")
    App(start_hidden=req.hidden or req.has_work, request=req).mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
