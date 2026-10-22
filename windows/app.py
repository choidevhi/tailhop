"""TailHop PC 앱: 여러 폰·PC와 연결, 기기별 대화방, 드래그 앤 드롭 보내기, QR·코드 페어링, 트레이 상주.

화면 문자열은 모두 i18n.t()로 꺼낸다(locales/*.json). 언어를 바꾸면 창 내용을 그 자리에서 다시 그린다.
보내기·받기는 Node의 스레드에서 돌기 때문에 다시 그려도 끊기지 않는다.
"""
from __future__ import annotations

import ctypes
import datetime as dt
import hashlib
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import traceback
import winreg
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk
import pystray
import qrcode
from PIL import Image, ImageGrab
from tkinterdnd2 import DND_FILES, TkinterDnD

import bridge
import cloud
import cloud_ui
import cloudstore
import i18n
import lightbox
import media
import player
import migrate
import mirror
import settings_ui
import store_ui
import outbox
import protocol as p
import store
import thumbs
import vicons
from i18n import t
from ui import (fit_geometry, MAIN_BG, SIDEBAR_BG, BORDER, TEXT, SECONDARY, HOVER, SELECTED, BUBBLE, INK, INK_TEXT, INK_HOVER,
                MUTED_BUTTON, GREEN, RED, AMBER, DROP, DROP_ROW, MONO, KIND_ICON, _SCALE, icon, kind_label,
                resource_path, human_size, font, ghost_button, round_icon, CircleButton, drop_tree, bind_tree,
                GUTTER, apply_glass, card)
from node import PAIR_WINDOW_SEC, Node

APP_NAME = "TailHop"
CHAT_FILTERS = {"all": "chat.filter_all", "text": "chat.filter_text", "media": "chat.filter_media",
                "file": "chat.filter_file"}
APP_VERSION = "2.3.1"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
HISTORY_PAGE = 50  # 대화방에 한 번에 그리는 말풍선 수. "더 보기"로 늘린다(기록은 최대 store.HISTORY_LIMIT개).
SIDEBAR_WIDTH = 236
TEXT_PREVIEW = 400
VIEW_LIMIT = 200 * 1024 * 1024  # 라이트박스가 원본을 메모리로 읽는 상한(넘으면 썸네일)
THUMB_MEMORY = 120  # 화면에 들고 있는 썸네일 이미지 수(넘으면 오래된 것부터 버린다)
PAUSE_HOUR = 3600

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
        fit_geometry(self, 880, 700)
        self.minsize(560, 520)
        icon = resource_path("assets/tailhop.ico")
        self.icon_path = icon if icon.exists() else None
        if self.icon_path is not None:
            self.after(250, lambda: self.iconbitmap(str(icon)))
        self.protocol("WM_DELETE_WINDOW", self.withdraw)

        self.events: queue.Queue[tuple[str, dict]] = queue.Queue()
        self.node = Node(lambda kind, data: self.events.put((kind, data)))
        self.lang_var = tk.StringVar(value=self.node.settings.get("language", i18n.SYSTEM))
        i18n.set_language(self.lang_var.get())
        self.active: dict[str, tuple[int, int, str]] = {}  # 보내는 중인 건: id: (보낸 바이트, 전체, 이름)
        self.pending_labels: dict[str, ctk.CTkLabel] = {}  # 대화방의 대기 말풍선 상태 줄(진행률만 바꿀 때)
        self.chat_search_open = False
        self.chat_filter = "all"
        self.chat_grid = False  # 사진·영상만 모아 보는 그리드
        self.thumb_tiles: dict[str, list[ctk.CTkLabel]] = {}  # 그리드 칸의 썸네일 자리(칸 크기에 맞춰 줄인다)
        self._render_pending = False
        self.qr_window: ctk.CTkToplevel | None = None
        self.cloud_window: cloud_ui.CloudWindow | None = None
        self.settings_window: settings_ui.SettingsWindow | None = None
        self.store_asked = False
        self.migrate_asked = False
        self.version = APP_VERSION
        self.hotkey_label = bridge.HOTKEY_LABEL
        self.cloud_on_var = tk.BooleanVar(value=self.node.cloud_on)
        self.cloud_collect_var = tk.BooleanVar(value=self.node.cloud_collect)
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
        self.viewer: lightbox.Lightbox | None = None
        self.video: player.VideoWindow | None = None
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
        self.after(1000, self._tick_pending)
        self.after(1500, self._check_store)
        self.after(30, lambda: apply_glass(self))
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
        # 창 바탕은 유리(아크릴), 사이드바와 대화방은 그 위에 뜬 카드 두 장이다(ui.apply_glass).
        # 왼쪽: 사이드바 카드
        self.sidebar = card(self, fg_color=SIDEBAR_BG, width=SIDEBAR_WIDTH)
        self.sidebar.pack(side="left", fill="y", padx=(GUTTER, GUTTER // 2), pady=GUTTER)
        self.sidebar.pack_propagate(False)

        head = ctk.CTkFrame(self.sidebar, fg_color="transparent", height=52)
        head.pack(fill="x", padx=10, pady=(8, 4))
        ctk.CTkLabel(head, text=APP_NAME, font=font(17, "bold"), text_color=TEXT).pack(side="left", padx=6)
        ghost_button(head, "ellipsis", self.open_settings).pack(side="right")
        ghost_button(head, "folder", self._open_save_dir).pack(side="right")

        new_row = ctk.CTkFrame(self.sidebar, fg_color="transparent", corner_radius=6, height=36, cursor="hand2")
        new_row.pack(fill="x", padx=8)
        new_row.pack_propagate(False)
        ctk.CTkLabel(new_row, text="", image=icon("plus", 16), width=22).pack(side="left", padx=(10, 6))
        ctk.CTkLabel(new_row, text=t("sidebar.new_device"), font=font(13), text_color=TEXT).pack(side="left")
        bind_tree(new_row, "<Button-1>", lambda _e: self._open_pair_menu())
        bind_tree(new_row, "<Enter>", lambda _e: new_row.configure(fg_color=HOVER))
        bind_tree(new_row, "<Leave>", lambda _e: new_row.configure(fg_color="transparent"))
        cloud_row = ctk.CTkFrame(self.sidebar, fg_color="transparent", corner_radius=6, height=36, cursor="hand2")
        cloud_row.pack(fill="x", padx=8)
        cloud_row.pack_propagate(False)
        ctk.CTkLabel(cloud_row, text="", image=icon("cloud", 16), width=22).pack(side="left", padx=(10, 6))
        ctk.CTkLabel(cloud_row, text=t("sidebar.cloud"), font=font(13), text_color=TEXT).pack(side="left")
        self.cloud_badge = ctk.CTkLabel(cloud_row, text="", font=font(11), text_color=GREEN)
        self.cloud_badge.pack(side="right", padx=10)
        bind_tree(cloud_row, "<Button-1>", lambda _e: self.open_cloud())
        bind_tree(cloud_row, "<Enter>", lambda _e: cloud_row.configure(fg_color=HOVER))
        bind_tree(cloud_row, "<Leave>", lambda _e: cloud_row.configure(fg_color="transparent"))

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

        # 오른쪽: 대화방 카드
        self.main = card(self)
        self.main.pack(side="left", fill="both", expand=True, padx=(GUTTER // 2, GUTTER), pady=GUTTER)
        if not self.sidebar_open:
            self.sidebar.pack_forget()
            self.main.pack_configure(padx=GUTTER)

        top = ctk.CTkFrame(self.main, fg_color="transparent", height=52)
        top.pack(fill="x", padx=10, pady=(8, 0))
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
        self.media_button = ghost_button(top, "layout-grid" if not self.chat_grid else "list", self._toggle_chat_grid)
        self.media_button.pack(side="right")
        ghost_button(top, "search", self._toggle_chat_search).pack(side="right")

        # 대화 찾기 줄(돋보기를 누르면 보인다): 글·파일 이름 찾기와 종류 거르기
        self.search_row = ctk.CTkFrame(self.main, fg_color="transparent")
        self.chat_query = ctk.CTkEntry(self.search_row, placeholder_text=t("chat.search"), height=32, corner_radius=6,
                                       border_width=1, border_color=BORDER, fg_color=MAIN_BG, text_color=TEXT,
                                       font=font(12), width=220)
        self.chat_query.pack(side="left")
        self.chat_query.bind("<KeyRelease>", lambda _e: self._render_history_soon())
        labels = [t(CHAT_FILTERS[k]) for k in CHAT_FILTERS]
        self.chat_filter_tabs = ctk.CTkSegmentedButton(
            self.search_row, values=labels, command=self._chat_filter_changed, height=30, corner_radius=6,
            font=font(11), fg_color=BUBBLE, selected_color=MAIN_BG, selected_hover_color=MAIN_BG,
            unselected_color=BUBBLE, unselected_hover_color=HOVER, text_color=TEXT)
        self.chat_filter_tabs.set(t(CHAT_FILTERS[self.chat_filter]))
        self.chat_filter_tabs.pack(side="left", padx=10)
        if self.chat_search_open:
            self.search_row.pack(fill="x", padx=20, pady=(0, 6), after=top)

        # 아래 입력창 (먼저 아래에 고정)
        composer_area = ctk.CTkFrame(self.main, fg_color="transparent")
        composer_area.pack(side="bottom", fill="x", padx=20, pady=(4, 16))
        self.progress_row = ctk.CTkFrame(composer_area, fg_color="transparent")
        self.progress = ctk.CTkProgressBar(self.progress_row, height=3, corner_radius=2, progress_color=INK, fg_color=BORDER)
        self.progress.pack(fill="x", padx=16)
        self.progress_label = ctk.CTkLabel(self.progress_row, text="", font=font(11), text_color=SECONDARY, height=18)
        self.progress_label.pack()
        composer = ctk.CTkFrame(composer_area, fg_color=MAIN_BG, corner_radius=12, border_width=1, border_color=BORDER)
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
        self.target_chip = ctk.CTkButton(tools, text="", command=self._open_target_menu, height=30, corner_radius=6,
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
        self.log.pack(fill="both", expand=True, padx=8)
        self.log.drop_target_register(DND_FILES)
        self._bind_main_drop(self.log)

    def _rebuild(self) -> None:
        """언어를 바꾼 뒤 창 내용을 다시 그린다. 진행 중인 보내기·받기는 다른 스레드라 그대로 이어진다."""
        typed = self._typed_text()
        for widget in (self.sidebar, self.main):
            widget.destroy()
        self._build()
        if typed:
            self._placeholder(False)
            self.text.insert("1.0", typed)
            self._on_typing()
        self._refresh_all()
        self._update_tray()
        self._show_active()

    def _toggle_sidebar(self) -> None:
        if self.sidebar_open:
            self.sidebar.pack_forget()
            self.main.pack_configure(padx=GUTTER)
        else:
            self.sidebar.pack(side="left", fill="y", padx=(GUTTER, GUTTER // 2), pady=GUTTER, before=self.main)
            self.main.pack_configure(padx=(GUTTER // 2, GUTTER))
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
        # 다 보낼 때까지(다시 시도 포함) 보낼 곳 목록의 작업 폴더에 둔다. 끝나면 목록이 지운다.
        job_id, path = self.node.outbox.owned_file(name)
        try:
            image.convert("RGBA" if "A" in image.getbands() else "RGB").save(path, "PNG")
        finally:
            image.close()  # 큰 스크린샷을 들고 있지 않는다
        self.node.outbox.add_owned(target, job_id, path)

    def _on_enter(self, event) -> str | None:
        if event.state & 0x1:  # Shift+Enter는 줄바꿈
            self.after(1, self._on_typing)
            return None
        self._send_typed()
        return "break"

    # ---------- 메뉴 ----------
    def _menu(self) -> tk.Menu:
        return tk.Menu(self, tearoff=0, font=(i18n.ui_font(), 10))

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
        menu.add_command(label=t("devmenu.cloud"), command=lambda: self.open_cloud(stable_id))
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
        self.cloud_badge.configure(text=t("sidebar.cloud_on") if self.node.cloud_on else "")
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
            row = ctk.CTkFrame(self.device_list, fg_color=SELECTED if selected else "transparent", corner_radius=6,
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
        self.pending_labels.clear()
        all_items = self._items_for(self.current) if self.current else []
        filtering = self.chat_grid or self.chat_filter != "all" or bool(self._chat_query())
        if filtering:
            all_items = [x for x in all_items if self._chat_match(x)]
        if self.chat_grid:
            self._render_media_grid(all_items)
            self._scroll_log(0.0)
            return
        items = all_items[-self.visible_rows:]
        pending = [j for j in self.node.outbox.jobs_for(self.current) if j.kind not in cloud_ui.CLOUD_KINDS] \
            if self.current and not filtering else []
        if not items and not pending:
            if filtering:
                ctk.CTkLabel(self.log, text=t("chat.no_results"), font=font(13), text_color=SECONDARY).pack(pady=120)
            else:
                self._render_empty()
            self._scroll_log(0.0)
            return
        hidden = len(all_items) - len(items)
        if hidden > 0:
            ctk.CTkButton(self.log, text=t("history.more", count=hidden), command=self._show_more, height=28,
                          corner_radius=6, fg_color="transparent", hover_color=HOVER, text_color=SECONDARY,
                          border_width=1, border_color=BORDER, font=font(11)).pack(pady=(10, 0))
        last_day = None
        for item in items:
            when = dt.datetime.fromtimestamp(item["time"])
            if when.date() != last_day:
                last_day = when.date()
                ctk.CTkLabel(self.log, text=date_label(when), font=font(11), text_color=SECONDARY).pack(pady=(16, 4))
            self._message(item, when)
        for job in pending:
            self._pending_message(job)
        self._scroll_log(1.0 if scroll_to_end else 0.0)

    def _scroll_log(self, where: float) -> None:
        """다시 그린 뒤 스크롤 범위를 지금 내용 크기로 고치고 옮긴다(줄어든 내용 아래 빈 곳을 보고 있지 않게)."""
        def run() -> None:
            canvas = self.log._parent_canvas
            canvas.update_idletasks()
            canvas.configure(scrollregion=canvas.bbox("all"))
            canvas.yview_moveto(where)

        self.after(80, run)

    # ---------- 대화 찾기·그리드 ----------
    def _chat_query(self) -> str:
        return self.chat_query.get().strip().casefold() if self.chat_search_open else ""

    def _chat_match(self, item: dict) -> bool:
        """찾는 글·종류에 맞는 기록인지. 긴 글은 기록에 남은 앞부분(2000자)에서 찾는다."""
        is_text = item["kind"] == "text"
        media = not is_text and thumbs.previewable(item.get("name", ""))
        kind = "media" if self.chat_grid else self.chat_filter
        if kind == "text" and not is_text or kind == "media" and not media or kind == "file" and (is_text or media):
            return False
        query = self._chat_query()
        if not query:
            return True
        return query in (item.get("text") if is_text else item.get("name", "")).casefold()

    def _toggle_chat_search(self) -> None:
        self.chat_search_open = not self.chat_search_open
        if self.chat_search_open:
            self.search_row.pack(fill="x", padx=20, pady=(0, 6), after=self.main.winfo_children()[0])
            self.chat_query.focus_set()
        else:
            self.search_row.pack_forget()
        self._render_history()

    def _chat_filter_changed(self, label: str) -> None:
        self.chat_filter = next(k for k, v in CHAT_FILTERS.items() if t(v) == label)
        self._render_history()

    def _toggle_chat_grid(self) -> None:
        self.chat_grid = not self.chat_grid
        self.media_button.configure(image=icon("list" if self.chat_grid else "layout-grid", 18))
        self._render_history(scroll_to_end=False)

    def _render_media_grid(self, items: list[dict]) -> None:
        """이 대화방의 사진·영상을 최근 것부터 칸으로 모아 보인다. 누르면 라이트박스로 크게 본다."""
        items = [x for x in reversed(items) if Path(x.get("path") or "").is_file()][:300]
        if not items:
            ctk.CTkLabel(self.log, text=t("chat.no_media"), font=font(13), text_color=SECONDARY).pack(pady=120)
            return
        width = max(self.log.winfo_width(), 520)
        cols = max(2, (width - 24) // 158)
        grid = ctk.CTkFrame(self.log, fg_color="transparent")
        grid.pack(fill="x", padx=6, pady=8)
        for i, item in enumerate(items):
            tile = ctk.CTkFrame(grid, fg_color=BUBBLE, corner_radius=8, width=146, height=146, cursor="hand2")
            tile.grid(row=i // cols, column=i % cols, padx=6, pady=6)
            tile.pack_propagate(False)
            label = ctk.CTkLabel(tile, text="", image=icon("image", 28, SECONDARY))
            label.place(relx=0.5, rely=0.5, anchor="center")
            key = str(Path(item["path"]))
            cached = self.thumb_images.get(key)
            if cached is not None:
                self._fit_tile(label, cached)
            else:
                self.thumb_tiles.setdefault(key, []).append(label)
                self.thumbs.request(Path(item["path"]))
            bind_tree(tile, "<Button-1>", lambda _e, it=item: self._open_history_item(it))

    @staticmethod
    def _fit_tile(label: ctk.CTkLabel, image: ctk.CTkImage) -> None:
        w, h = image.cget("size")
        scale = min(138 / max(1, w), 138 / max(1, h))
        label.configure(image=ctk.CTkImage(light_image=image.cget("light_image"), dark_image=image.cget("dark_image"),
                                           size=(max(1, int(w * scale)), max(1, int(h * scale)))))

    def _pending_status(self, job: outbox.Job) -> tuple[str, tuple]:
        """대기 말풍선 아래 줄: (문장, 색)."""
        if job.state == outbox.SENDING:
            if job.total:
                return t("pending.sending", pct=int(job.done * 100 / max(1, job.total)),
                         done=human_size(job.done), total=human_size(job.total)), SECONDARY
            return t("pending.sending_start"), SECONDARY
        if job.state == outbox.FAILED:
            return t("pending.failed", error=job.error), RED
        if job.attempts == 0:
            return t("pending.queued"), SECONDARY
        if job.code in outbox.OFFLINE_CODES:  # 상대가 꺼져 있다: 켜지면 바로 보낸다(§11.5)
            return t("pending.offline"), SECONDARY
        secs = max(0, int(job.next_at - time.time() + 0.99))
        return t("pending.retry", secs=secs, error=job.error), AMBER

    def _pending_message(self, job: outbox.Job) -> None:
        """아직 못 보낸 건(보내는 중·다시 시도 대기·실패): 내 쪽 말풍선을 흐리게, 아래에 상태와 다시·취소."""
        row = ctk.CTkFrame(self.log, fg_color="transparent")
        row.pack(fill="x", padx=12, pady=4)
        box = ctk.CTkFrame(row, fg_color=BUBBLE, corner_radius=8)
        box.pack(anchor="e")
        if job.kind == "text":
            text = job.text or ""
            text = text if len(text) <= TEXT_PREVIEW else text[:TEXT_PREVIEW].rstrip() + " …"
            ctk.CTkLabel(box, text=text, font=font(13), text_color=SECONDARY, justify="left", wraplength=420,
                         anchor="w").pack(padx=14, pady=8)
        else:
            inner = ctk.CTkFrame(box, fg_color="transparent")
            inner.pack(padx=12, pady=10)
            round_icon(inner, "folder" if job.kind == "folder" else "file", 38, fg=MAIN_BG,
                       color=SECONDARY).pack(side="left")
            names = ctk.CTkFrame(inner, fg_color="transparent")
            names.pack(side="left", padx=(10, 4))
            name = job.name + (".zip" if job.kind == "folder" else "")
            name = name if len(name) <= 44 else name[:41] + "…"
            ctk.CTkLabel(names, text=name, font=font(13, "bold"), text_color=SECONDARY, anchor="w",
                         height=19).pack(anchor="w")
            if job.size:
                ctk.CTkLabel(names, text=human_size(job.size), font=font(11), text_color=SECONDARY,
                             anchor="w", height=15).pack(anchor="w")
        meta = ctk.CTkFrame(row, fg_color="transparent")
        meta.pack(anchor="e", padx=2)
        text, color = self._pending_status(job)
        icon_name = "triangle-alert" if job.state == outbox.FAILED else "clock"
        status = ctk.CTkLabel(meta, text=" " + text, font=font(10), text_color=color, height=22,
                              image=icon(icon_name, 12, color), compound="left", wraplength=360, justify="right")
        status.pack(side="left", padx=(0, 4))
        self.pending_labels[job.id] = status
        if job.state != outbox.SENDING:
            ghost_button(meta, "rotate-ccw", lambda j=job.id: self.node.outbox.retry(j), size=24).pack(side="left")
        ghost_button(meta, "x", lambda j=job.id: self._cancel_job(j), size=24).pack(side="left")

    def _cancel_job(self, job_id: str) -> None:
        job = self.node.outbox.get(job_id)
        if job is None:
            return
        if job.state == outbox.SENDING and not messagebox.askyesno(APP_NAME, t("dialog.cancel_sending"), parent=self):
            return
        self.node.outbox.cancel(job_id)

    def _tick_pending(self) -> None:
        """다시 시도까지 남은 초를 1초마다 바꾼다(대화방을 다시 그리지 않고 글자만)."""
        try:
            for job_id, label in list(self.pending_labels.items()):
                job = self.node.outbox.get(job_id)
                if job is None or not label.winfo_exists():
                    continue
                text, color = self._pending_status(job)
                label.configure(text=" " + text, text_color=color)
        finally:
            self.after(1000, self._tick_pending)

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
                          hover_color=INK_HOVER, text_color=INK_TEXT, corner_radius=6, height=36, width=130,
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
        box = ctk.CTkFrame(row, fg_color=BUBBLE if mine else "transparent", corner_radius=8,
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
            hint = t("msg.click_view") if thumbs.previewable(item["name"]) else t("msg.click_show")
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
        tiles = [x for x in self.thumb_tiles.pop(src, []) if x.winfo_exists()]
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
        for tile in tiles:
            self._fit_tile(tile, image)
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
        elif item.get("cloud"):  # 클라우드에 모은 파일(이 PC의 암호화 저장소 맨 위): 클라우드 창에서 연다
            self.open_cloud(cloud_ui.LOCAL, focus=item["cloud"])
        elif thumbs.previewable(Path(item.get("path") or "").name) and Path(item["path"]).is_file():
            self._open_viewer(item)
        else:
            self._show_in_folder(item)

    # ---------- 라이트박스(2.2.0) ----------
    def _viewer_items(self, item: dict) -> list[dict]:
        """이 대화방의 사진·영상(오래된 것부터, 파일이 남아 있는 것만). 누른 항목은 꼭 들어간다."""
        items = [x for x in self._items_for(self.current) if x["kind"] != "text"
                 and thumbs.previewable(Path(x.get("path") or "").name) and Path(x["path"]).is_file()] \
            if self.current else []
        return items if any(x is item for x in items) else [*items, item]

    def _open_viewer(self, item: dict) -> None:
        items = self._viewer_items(item)
        by_key = {str(id(x)): x for x in items}
        shots = [lightbox.Shot(key, x.get("name") or Path(x["path"]).name, self._viewer_preview(x),
                               self._viewer_loader(Path(x["path"])),
                               video=Path(x["path"]).suffix.lower() in thumbs.VIDEO_EXT) for key, x in by_key.items()]
        index = next(i for i, x in enumerate(items) if x is item)
        actions = [lightbox.Action(t("chat.show_in_folder"), "folder", lambda s: self._show_in_folder(by_key[s.key])),
                   lightbox.Action(t("chat.delete"), "trash-2", lambda s: self._viewer_delete(by_key[s.key]), "danger",
                                   key="delete")]
        if player.mpv_path() is not None and any(s.video for s in shots):
            actions.insert(0, lightbox.Action(t("lightbox.play"), "play", lambda s: self._play_file(by_key[s.key]),
                                              key="play", video_only=True))
        if self.viewer is not None and self.viewer.winfo_exists():
            self.viewer.close()
        self.viewer = lightbox.Lightbox(self, shots, index, actions,
                                        lambda fn: self.events.put(("ui_call", {"fn": fn})), self.icon_path)

    def _play_file(self, item: dict) -> None:
        """대화방의 영상(이 PC의 파일)을 앱 안 재생기로. 이 대화방의 영상끼리 이전·다음으로 넘긴다."""
        path = Path(item.get("path") or "")
        if path.suffix.lower() not in thumbs.VIDEO_EXT or not path.is_file():
            return
        videos = [Path(x["path"]) for x in self._viewer_items(item) if Path(x["path"]).suffix.lower() in thumbs.VIDEO_EXT]
        if path not in videos:
            videos.append(path)
        settings = self.node.settings
        clips = [player.Clip(player.resume_key(settings, str(v)), v.name, lambda v=v: (str(v), lambda: None))
                 for v in videos]
        if self.video is not None and self.video.winfo_exists():
            self.video.close()
        self.video = player.VideoWindow(self, clips, videos.index(path),
                                        lambda fn: self.events.put(("ui_call", {"fn": fn})), settings,
                                        self.node.save_settings, self.icon_path)

    def _viewer_preview(self, item: dict):
        cached = self.thumb_images.get(str(Path(item["path"])))
        return cached.cget("light_image") if cached is not None else None

    @staticmethod
    def _viewer_loader(path: Path):
        def from_thumb():
            ctypes.windll.ole32.CoInitializeEx(None, 0x2)  # 영상·HEIC는 셸 썸네일(COM)을 쓴다
            out = thumbs.make(path)
            if out is None:
                return None
            with Image.open(out) as im:
                im.load()
                return im.copy()

        def load():
            if path.suffix.lower() in thumbs.VIDEO_EXT or path.stat().st_size > VIEW_LIMIT:
                return from_thumb()
            try:
                return lightbox.decode(path.read_bytes())
            except (OSError, ValueError):
                return from_thumb()  # Pillow가 못 푸는 형식(HEIC 등)

        return load

    def _viewer_delete(self, item: dict) -> None:
        """대화에서 지운다. 받은 파일은 휴지통으로 보내고, 보낸 파일(내 원본)은 그대로 둔다."""
        mine = item["dir"] == "out"
        key = "chat.delete_sent_confirm" if mine else "chat.delete_received_confirm"
        if not messagebox.askyesno(APP_NAME, t(key, name=item.get("name", "")), parent=self.viewer):
            return
        self.node.history.remove(item)
        if not mine and not cloud.recycle(Path(item["path"])):
            self._flash(t("chat.delete_file_failed"))
        if self.viewer is not None and self.viewer.winfo_exists():
            self.viewer.remove_current()
        self._render_history(scroll_to_end=False)

    def _show_in_folder(self, item: dict) -> None:
        path = Path(item["path"])
        if not path.is_file():
            messagebox.showinfo(APP_NAME, t("dialog.file_missing"), parent=self)
            return

        # 받은 파일을 직접 실행하지 않고 탐색기에서 선택만 한다. 경로는 통째로 따옴표로 묶어 넘긴다
        # (파일명의 쉼표가 explorer 인자로 쪼개지지 않게).
        cmd = store.explorer_select_command(path)
        if cmd is not None:
            subprocess.Popen(cmd)
        else:
            os.startfile(str(path.parent))  # 폴더만 연다

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

    def flash(self, text: str) -> None:
        """다른 창(클라우드)에서 쓰는 짧은 알림 줄."""
        self._flash(text)

    def _flash(self, text: str) -> None:
        self._show_progress(text, 1.0)
        self.after(2000, self._hide_progress)

    def _show_active(self) -> None:
        """보내는 중인 건이 있으면 아래 진행 줄에 보인다(여러 기기로 보내면 가장 최근 것)."""
        if not self.active:
            return
        done, total, name = next(reversed(self.active.values()))
        if total:
            self._show_progress(t("progress.sending_named", name=name, done=human_size(done),
                                  total=human_size(total)), done / max(1, total))
        else:
            self._show_progress(t("progress.sending"), None)

    def _hide_progress(self) -> None:
        if not self.active and self.progress_row.winfo_exists():
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

    def _send_paths(self, paths: list[Path], stable_id: str | None = None) -> None:
        """보낼 곳 목록에 넣는다. 파일은 그대로, 폴더는 보낼 때 zip(<폴더 이름>.zip)으로 묶는다.
        상대가 꺼져 있으면 켜질 때까지 다시 시도한다(docs/PROTOCOL.md §11.3)."""
        target = self._target(stable_id)
        items = [x for x in paths if x.is_file() or x.is_dir()]
        if target is None or not items:
            return
        self.node.outbox.add_paths(target, items)

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
            self.node.outbox.add_text(target, text)
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
            self.node.outbox.add_text(target, text)
            self._notify(t("notify.sending_to", name=self._target_name(target)))
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
        frame = ctk.CTkFrame(win, fg_color="#FFFFFF", corner_radius=12, border_width=1, border_color=BORDER)
        frame.pack(pady=(12, 10))
        ctk.CTkLabel(frame, image=qr, text="").pack(padx=10, pady=10)

        ctk.CTkLabel(win, text=t("pair.pc_hint"), font=font(12), text_color=SECONDARY, wraplength=420).pack(padx=24)
        where, body = code.split("-", 1)
        groups = body.split("-")
        half = (len(groups) + 1) // 2
        shown = f"{where}\n{'-'.join(groups[:half])}\n{'-'.join(groups[half:])}"
        code_box = ctk.CTkFrame(win, fg_color=BUBBLE, corner_radius=8)
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
                          corner_radius=6, fg_color="transparent", hover_color=HOVER, text_color=TEXT,
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
                             border_width=0, corner_radius=6, text_color=TEXT, wrap="char")
        box.pack(padx=24, pady=12)
        status = ctk.CTkLabel(win, text=t("join.same_account"), font=font(11), text_color=SECONDARY, wraplength=380)
        status.pack()
        button = ctk.CTkButton(win, text=t("join.connect"), fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT,
                               corner_radius=6, height=36, width=130, font=font(13, "bold"))
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
        if req.quit:
            self.events.put(("quit", {}))  # 화면 스레드에서 끈다(설치 프로그램이 업데이트 전에 부른다)
            return {"ok": True, "quit": True}
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
        # 보낼 곳 목록은 여러 스레드에서 써도 된다: 여기서 바로 넣고, 기다리라면 상대가 받을 때까지 기다린다.
        jobs = self._enqueue(req, target)
        name = self.node.pairings[target].name
        self.events.put(("bridge_queued", {"target": target}))
        if not req.wait:
            return {"ok": True, "to": name}
        result = self.node.outbox.wait([j.id for j in jobs], req.timeout)
        failed = [{"name": j.name or "text", "error": j.error} for j in result.values()
                  if j is not None and j.state == outbox.FAILED]
        pending = [j for j in result.values() if j is not None and j.state != outbox.FAILED]
        sent = sum(1 for j in result.values() if j is None)
        if failed or pending:
            return {"ok": False, "error": "failed" if failed else "pending", "to": name, "sent": sent,
                    "failed": failed, "pending": len(pending)}
        return {"ok": True, "to": name, "sent": sent, "delivered": True}

    def _enqueue(self, req: bridge.Request, target: str) -> list[outbox.Job]:
        jobs = []
        if req.text is not None and req.text.strip():
            jobs.append(self.node.outbox.add_text(target, req.text))
        if req.paths:
            jobs.extend(self.node.outbox.add_paths(target, [Path(x) for x in req.paths]))
        return jobs

    def _do_request(self, req: bridge.Request, target: str | None = None) -> None:
        if target is None:
            devices = [(sid, x.name) for sid, x in self._devices()]
            target = bridge.resolve_device(devices, req.to, self.current)
            if target is None:
                self._show()
                messagebox.showwarning(APP_NAME, t("dialog.no_device", name=req.to or ""), parent=self)
                return
        self._enqueue(req, target)
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
        """이벤트 처리. 한 이벤트가 예외를 내도 다음 이벤트와 다음 차례는 계속 돈다(2.2.0에서는 한 번의 예외로
        이 순환이 멈춰 설치 프로그램의 끄기 요청까지 받지 못했다)."""
        try:
            while True:
                try:
                    kind, data = self.events.get_nowait()
                except queue.Empty:
                    break
                try:
                    self._handle_event(kind, data)
                except Exception:  # noqa: BLE001 - 기록하고 계속
                    log_error(f"event {kind}")
        finally:
            self.after(100, self._poll)

    def report_callback_exception(self, exc, val, tb) -> None:
        """Tk 콜백의 예외(창이 없는 앱이라 보이지 않는다)를 오류 기록에 남긴다."""
        log_error("tk callback", (exc, val, tb))

    def _on_outbox(self, data: dict) -> None:
        """보낼 곳 목록이 바뀜: 넣음·보내기 시작·다시 시도 대기·실패·끝(gone)."""
        job_id, peer = data["id"], data["peer"]
        if data.get("gone"):
            self.active.pop(job_id, None)
            if not self.active:
                self._hide_progress()
        job = self.node.outbox.get(job_id)
        if job is not None and job.state != outbox.SENDING:
            self.active.pop(job_id, None)
        if data.get("failed") and job is not None:
            what = job.name or t("pending.text")
            message = t("notify.send_failed", peer=self._target_name(peer), name=what, error=job.error)
            if self.winfo_viewable() and peer == self.current:
                self._flash(message)
            else:  # 창이 숨어 있을 때(명령줄·단축키) 보이지 않는 대화상자 대신 알림
                self._notify(message)
        elif job is not None and job.state == outbox.WAITING and job.attempts == 1:
            self._notify(t("notify.waiting", peer=self._target_name(peer)))  # 처음 실패했을 때만 한 번
        if self.cloud_window is not None and self.cloud_window.winfo_exists():
            self.cloud_window.on_outbox(data)
        if peer == self.current and data.get("kind") not in cloud_ui.CLOUD_KINDS:
            self._render_history_soon()

    def _render_history_soon(self) -> None:
        """여러 건이 한꺼번에 바뀔 때(파일 100개를 넣음 등) 대화방을 한 번만 다시 그린다."""
        if self._render_pending:
            return
        self._render_pending = True

        def run() -> None:
            self._render_pending = False
            self._render_history()

        self.after(150, run)

    # ---------- 설정 ----------
    def open_settings(self) -> None:
        win = self.settings_window
        if win is not None and win.winfo_exists():
            win.deiconify()
            win.lift()
            win.focus_force()
            return
        self.settings_window = settings_ui.SettingsWindow(self)

    @staticmethod
    def confirm_code(pairing: store.Pairing) -> str:
        return p.confirm_code(pairing.secret)

    # ---------- 클라우드 ----------
    def open_cloud(self, source: str | None = None, focus: str | None = None) -> None:
        if self.cloud_window is not None and self.cloud_window.winfo_exists():
            if source is not None:
                self.cloud_window.focus_path = focus
                self.cloud_window.open_source(source)
            self.cloud_window.deiconify()
            self.cloud_window.lift()
            self.cloud_window.focus_force()
            return
        self.cloud_window = cloud_ui.CloudWindow(self, source, focus)

    def toggle_cloud_on(self) -> None:
        self.cloud_on_var.set(not self.node.cloud_on)
        self._on_cloud_switch()

    def _on_cloud_switch(self) -> None:
        on = bool(self.cloud_on_var.get())
        if on and not self.node.settings.get("cloud_dir"):
            # 처음 켤 때 폴더를 보여 준다(기본: 사용자 폴더의 'TailHop Cloud')
            if not messagebox.askyesno(APP_NAME, t("dialog.cloud_on", path=str(self.node.cloud_dir)), parent=self):
                self.cloud_on_var.set(False)
                return
            self.node.cloud_dir.mkdir(parents=True, exist_ok=True)
        self.node.set_cloud(on=on)
        self.cloud_collect_var.set(self.node.cloud_collect)
        self._refresh_header()
        self._flash(t("flash.cloud_on") if on else t("flash.cloud_off"))
        self.store_changed()
        if on:
            self._check_store(force=True)

    def _check_store(self, force: bool = False) -> None:
        """클라우드가 켜져 있는데 저장소가 아직 없거나(새로 만들기) 비밀번호가 필요하면(새 PC) 한 번 묻는다."""
        st = self.node.store()
        if st is None or (self.store_asked and not force):
            return
        self.store_asked = True
        mode = {cloudstore.NEW: "create", cloudstore.LOCKED: "unlock"}.get(st.state)
        if mode is not None:
            self.deiconify()
            store_ui.ask(self, self, mode, self.store_changed)
        elif st.state == cloudstore.MISSING:
            self._flash(t("store.state_missing"))
        else:
            self._offer_migration()

    # ---------- 2.1까지의 클라우드 옮기기(2.2.0) ----------
    def _migration_sources(self, st) -> list[Path]:
        sources = [st.root]
        old = self.node.settings.get("cloud_import_from")
        if isinstance(old, str) and old and Path(old) != st.root and Path(old).is_dir():
            sources.append(Path(old))
        return sources

    def _offer_migration(self, force: bool = False) -> None:
        """저장소가 열렸는데 예전 평문 파일·예전 잠긴 폴더가 남아 있으면 옮길지 묻는다(한 번)."""
        st = self.node.store()
        if st is None or st.state != cloudstore.OPEN or (self.migrate_asked and not force):
            return
        self.migrate_asked = True

        def scan():
            return [(src, *migrate.pending(src)) for src in self._migration_sources(st)]

        def found(result, error) -> None:
            if error is not None or not result:
                return
            for src, count, size, mounts in result:
                if count:
                    if messagebox.askyesno(APP_NAME, t("migrate.ask", count=count, size=human_size(size),
                                                       path=str(src)), parent=self):
                        self._run_migration(st, src, mounts)
                    return
                if mounts:
                    self._convert_vaults(st, src, mounts)
                    return

        store_ui.run_bg(self, scan, found)

    def _run_migration(self, st, src: Path, mounts: list[str]) -> None:
        self._flash(t("migrate.running", pct=0))

        def progress(done: int, total: int) -> None:
            self.events.put(("ui_call", {"fn": lambda: self._flash(t("migrate.running", pct=done * 100 // total))}))

        def done(report, error) -> None:
            if error is not None:
                messagebox.showerror(APP_NAME, str(error), parent=self)
                return
            if report.failed:
                lines = "\n".join(f"{name}: {why}" for name, why in report.failed[:6])
                messagebox.showwarning(APP_NAME, t("migrate.some_failed", done=report.moved,
                                                   failed=len(report.failed)) + "\n\n" + lines, parent=self)
            else:
                self._flash(t("migrate.done", count=report.moved))
            self.store_changed()
            if mounts:
                self._convert_vaults(st, src, mounts)

        store_ui.run_bg(self, lambda: migrate.move_plain(st, src, progress), done)

    def _convert_vaults(self, st, src: Path, mounts: list[str]) -> None:
        """예전 잠긴 폴더마다 지금 PIN(또는 복구 코드)과 새 PIN을 물어 새 저장소로 옮긴다."""
        if not mounts:
            return
        mount, rest = mounts[0], mounts[1:]

        def submit(dialog, values) -> None:
            def work():
                return migrate.convert_vault(st, src, mount, values["new_pin"], values.get("pin"),
                                             values.get("recovery"))

            def after(code, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                cloud_ui.show_recovery(self, code)
                self.store_changed()
                self._convert_vaults(st, src, rest)

            store_ui.run_bg(self, work, after)

        cloud_ui.PinDialog(self, "convert", mount, submit)

    def store_changed(self) -> None:
        """저장소를 만들거나 열거나 리셋한 뒤: 설정·클라우드 창을 다시 그린다. 예전 파일이 남았으면 옮길지 묻는다."""
        self.after(300, self._offer_migration)
        self._refresh_header()
        win = self.settings_window
        if win is not None and win.winfo_exists():
            win.rebuild()
        self._refresh_cloud_window(local=True)

    def _on_collect_switch(self) -> None:
        self.node.set_cloud(collect=bool(self.cloud_collect_var.get()))

    def _change_cloud_dir(self) -> None:
        """저장 위치 바꾸기(아무 드라이브·외장 SSD). 지금 저장소가 열려 있으면 그대로 옮길지 묻는다."""
        chosen = cloud_ui.cloud_folder_dialog(self.settings_window or self)
        if not chosen or Path(chosen) == self.node.cloud_dir:
            return
        target = Path(chosen)
        st = self.node.store()
        existing = cloudstore.store_id_at(target)
        old = self.node.cloud_dir
        if cloudstore.store_id_at(old) is None and any(migrate.pending(old)[::2]):
            self.node.settings["cloud_import_from"] = str(old)  # 예전 평문 폴더: 새 저장소로 옮길 수 있게 기억한다
        if st is not None and st.state == cloudstore.OPEN and existing is None and st.nodes:
            answer = messagebox.askyesnocancel(APP_NAME, t("store.move_ask", path=chosen), parent=self)
            if answer is None:
                return
            if answer:
                self._flash(t("store.moving"))

                def progress(done: int, total: int) -> None:
                    self.events.put(("ui_call", {"fn": lambda: self._flash(
                        t("store.moving_pct", pct=done * 100 // max(1, total)))}))

                def done(_r, error) -> None:
                    if error is not None:
                        messagebox.showerror(APP_NAME, str(error), parent=self)
                        return
                    self.node.set_cloud(folder=chosen)
                    self._flash(t("store.moved", path=chosen))
                    self.store_changed()

                store_ui.run_bg(self, lambda: cloudstore.relocate(st, target, progress), done)
                return
        self.node.set_cloud(folder=chosen)
        self._flash(t("flash.cloud_dir", path=chosen))
        self.store_changed()
        self._check_store(force=True)

    def _refresh_cloud_window(self, local: bool = False) -> None:
        win = self.cloud_window
        if win is None or not win.winfo_exists():
            return
        win.render_sources()
        if not local or win.source == cloud_ui.LOCAL:
            win.reload()

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
            self._render_history_soon()
        elif kind == "progress":
            if data["dir"] == "out":
                return  # 보내기 진행은 보낼 곳 목록(outbox_progress)으로 보인다
            total = max(1, data["total"])
            key = "progress.receiving_bytes" if data["dir"] == "in" else "progress.sending_bytes"
            self._show_progress(t(key, done=human_size(data["done"]), total=human_size(data["total"])),
                                data["done"] / total)
        elif kind == "outbox":
            self._on_outbox(data)
        elif kind == "ui_call":
            data["fn"]()
        elif kind == "quit":
            self._quit()
        elif kind in ("cloud_in", "cloud_changed"):
            if kind == "cloud_in":
                self._notify(t("notify.cloud_in", peer=data["peer"], name=data["name"]) if data["name"]
                             else t("notify.cloud_in_locked", peer=data["peer"]))
            self._refresh_cloud_window(local=True)
        elif kind == "cloud_sent":
            self._flash(t("flash.cloud_sent", name=data["name"]))
        elif kind == "sync_changed":  # 동기화 사본이 바뀌었다(§19.3)
            win = self.cloud_window
            if win is not None and win.winfo_exists():
                win.on_sync_changed(data)
            if self.settings_window is not None and self.settings_window.winfo_exists():
                self.settings_window.refresh_sync()
        elif kind == "store_changed":  # 강한 잠금: 잠기거나(Windows 잠금·쓰지 않음) 다른 기기에서 열었다
            if data.get("state") != cloudstore.OPEN:
                win = self.cloud_window
                if win is not None and win.winfo_exists():
                    win.close_viewers()  # 풀어 둔 그림·영상을 메모리에 두지 않게
                if self.viewer is not None and self.viewer.winfo_exists():
                    self.viewer.close()
                self._flash(t("store.locked_now"))
            elif data.get("peer"):
                self._flash(t("store.unlocked_by", peer=data["peer"]))
            self.store_changed()
        elif kind == "outbox_progress":
            if self.cloud_window is not None and self.cloud_window.winfo_exists():
                self.cloud_window.update_transfers(data)
            self.active[data["id"]] = (data["done"], data["total"], data.get("name") or "")
            self._show_active()
            label = self.pending_labels.get(data["id"])
            if label is not None and label.winfo_exists():
                pct = int(data["done"] * 100 / max(1, data["total"]))
                label.configure(text=" " + t("pending.sending", pct=pct, done=human_size(data["done"]),
                                             total=human_size(data["total"])), text_color=SECONDARY)
        elif kind == "bridge_queued":
            self._notify(t("notify.sending_to", name=self._target_name(data["target"])))
        elif kind == "thumb":
            self._on_thumb(data["src"], data["out"])
        elif kind == "packing":
            if data.get("peer") == self.current:
                self._show_progress(t("progress.packing", name=data["name"]), None)
        elif kind == "hotkey":
            if self.current is None:
                self._notify(t("dialog.connect_first"))
            else:
                self._send_clipboard()
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
        if req.list_devices or req.quit:
            return 0
        _say(t("cli.delivered", name=reply.get("to", "")) if reply.get("delivered")
             else t("cli.sending", name=reply.get("to", "")))
        return 0

    error = reply.get("error")
    if error in ("failed", "pending"):
        for item in reply.get("failed", []):
            _say(t("cli.item_failed", name=item.get("name", ""), error=item.get("error", "")))
        if reply.get("pending"):
            _say(t("cli.pending", name=reply.get("to", ""), count=reply["pending"]))
        return 1 if reply.get("failed") else 3
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


ERROR_LOG_MAX = 256 * 1024


def log_error(where: str, info=None) -> None:
    """예외의 위치와 호출 순서만 %APPDATA%\\TailHop\\error.log에 남긴다(파일 내용·비밀번호는 넣지 않는다). 너무 커지면 새로 쓴다."""
    try:
        text = "".join(traceback.format_exception(*info) if info else traceback.format_exc())
        path = store.APP_DIR / "error.log"
        path.parent.mkdir(parents=True, exist_ok=True)
        mode = "w" if path.exists() and path.stat().st_size > ERROR_LOG_MAX else "a"
        with open(path, mode, encoding="utf-8") as out:
            out.write(f"--- {dt.datetime.now().isoformat(timespec='seconds')} {APP_VERSION} {where}\n{text}\n")
    except OSError:
        pass


def selftest(out: Path) -> int:
    """빌드한 설치본 확인용(--selftest 결과파일): 임시 폴더에 암호화 저장소를 만들어 넣고 읽고, FFmpeg을 찾는다.
    사용자 설정·클라우드는 건드리지 않는다."""
    result: dict[str, str | bool] = {"version": APP_VERSION}
    try:
        with tempfile.TemporaryDirectory() as tmp:
            st = cloudstore.Store(Path(tmp) / "cloud", Path(tmp) / "app")
            (Path(tmp) / "cloud").mkdir()
            st.create("selftest password 1")
            up = st.begin_upload("", cloudstore.MAIN, "a.bin", 5, 0, "local", os.urandom(16).hex())
            up.write(b"hello")
            up.commit()
            result["store"] = b"".join(st.read(st.node_at(["a.bin"], {}, "local"))) == b"hello"
            st.close()
            # 동기화 사본(§19.3): 잠긴 채로 공개키로 쓰고, 사본 비밀번호로 연 뒤에만 읽힌다(장부는 DPAPI)
            base = Path(tmp) / "mirror"
            device = mirror.DeviceKey(base)
            device.create("selftest password 1")
            device.lock()
            copy = mirror.Mirror(base / ("0" * 16), device, mirror.ledger_aead(base))
            copy.apply("0" * 16, [mirror.Want("1" * 32, "a.bin", False, 5, 0, "2" * 32)],
                       lambda _path, offset, length: b"hello"[offset:offset + length], lambda _path: b"")
            copy.close()
            fresh = mirror.DeviceKey(base)
            again = mirror.Mirror(base / ("0" * 16), fresh, mirror.ledger_aead(base))
            result["mirror"] = fresh.unlock("selftest password 1") \
                and b"".join(again.read(again.find("a.bin"))) == b"hello"
            again.close()
        ff = media.ffmpeg_dir()
        result["ffmpeg"] = str(ff) if ff else ""
        if ff:
            done = subprocess.run([str(ff / "ffmpeg.exe"), "-hide_banner", "-version"], capture_output=True,
                                  creationflags=0x08000000)
            result["ffmpeg_ok"] = done.returncode == 0 and b"ffmpeg version" in done.stdout
            result["encoder"] = media.pick_encoder(ff) or ""
    except Exception as exc:  # noqa: BLE001 - 결과 파일에 이유를 남긴다
        result["error"] = f"{type(exc).__name__}: {exc}"
    out.write_text(json.dumps(result, ensure_ascii=False), "utf-8")
    return 0 if result.get("store") and result.get("mirror") and result.get("ffmpeg_ok") else 1


def main() -> int:
    if len(sys.argv) == 3 and sys.argv[1] == "--selftest":
        return selftest(Path(sys.argv[2]))
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
    if req.quit:
        return 0  # 끌 앱이 없다

    ctk.set_appearance_mode("system")
    App(start_hidden=req.hidden or req.has_work, request=req).mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
