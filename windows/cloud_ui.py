"""클라우드 창(docs/PROTOCOL.md §12, §14, §15): 이 PC와 연결된 기기의 클라우드 폴더를 보고, 가져오고, 올리고, 지운다.

- 왼쪽 카드: 이 PC와 연결된 기기. 기기마다 hello로 클라우드가 켜져 있는지 확인해 보인다.
- 오른쪽 카드: 지금 폴더. 목록/그리드 보기, 사진·영상 미리보기(허브가 만든 썸네일), 이름 검색과 종류 거르기.
  잠긴 폴더는 자물쇠로 보이고 PIN(또는 복구 코드)을 넣어야 열린다. 폴더 메뉴에서 잠그기·잠금 풀기·PIN 바꾸기.
- 올리기·가져오기는 보낼 곳 목록(outbox)에 넣어 끊겨도 이어서 한다(이 PC는 바로 복사). 아래 줄에 진행 상황을 보인다.
- 2.1.0: 사진·영상을 누르면 라이트박스(lightbox.py)로 크게 본다. Ctrl+클릭·Shift+클릭·체크 상자로 여러 개를 고르고
  고른 것을 한꺼번에 받기·옮기기(§16 cloud_move)·지우기. Esc로 고르기 해제, Ctrl+A로 모두 고르기.
- 네트워크·디스크·암호화 일은 모두 작업 스레드에서 하고, 결과는 앱의 이벤트 큐("ui_call")로 화면 스레드에 넘긴다.
"""
from __future__ import annotations

import datetime as dt
import os
import subprocess
import threading
import tkinter as tk
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk
from tkinterdnd2 import DND_FILES

import cloud
import cloudkeys
import lightbox
import media
import outbox
import player
import protocol as p
import rename_plan
import store
import store_ui
from cloud_source import LOCAL, Source
from i18n import t
from ui import (fit_geometry, AMBER, BORDER, BUBBLE, DROP, DROP_ROW, GREEN, GUTTER, HOVER, INK, INK_HOVER, INK_TEXT, KIND_ICON, MAIN_BG, MONO,
                RED, SECONDARY, SELECTED, SIDEBAR_BG, TEXT, apply_glass, bind_tree, card, checkbox_image, copy_secret, font,
                protect,
                ghost_button,
                human_size, icon, round_icon)

RENDER_PAGE = 100  # 한 번에 그리는 항목 수(CTk 위젯이 무거워 나눠 그린다)
CLOUD_KINDS = ("cloud_put", "cloud_get")
STATE_TEXT = {"checking": "cloud.state_checking", "on": "cloud.state_on", "off": "cloud.state_off",
              "old": "cloud.state_old", "unreachable": "cloud.state_unreachable", "store": "cloud.state_store"}
KIND_FILTERS = ("all", "image", "video", "audio", "doc", "archive", "other")
KIND_ICONS = {"image": "image", "video": "video"}
PREVIEW_KINDS = ("image", "video")
THUMB_CACHE = 300  # 화면에 들고 있는 썸네일 수
TILE = 150
FULL_LIMIT = 200 * 1024 * 1024  # 라이트박스에서 원본을 메모리로 받는 상한(넘으면 허브의 큰 미리보기)
BIG_PX = 512  # 원본을 못 쓸 때(영상·너무 큼·못 푸는 형식) 받는 큰 미리보기


class PinDialog(ctk.CTkToplevel):
    """PIN을 묻는 작은 창. mode: open(PIN 또는 복구 코드), lock(새 PIN 두 번), unlock(PIN), repin(지금 PIN 또는 복구 코드 + 새 PIN 두 번)."""

    def __init__(self, parent, mode: str, name: str, on_done) -> None:
        super().__init__(parent)
        self.mode, self.on_done = mode, on_done
        self.use_recovery = False
        self.title(t(VAULT_TITLES[mode]))
        self.geometry("420x330")
        self.resizable(False, False)
        self.transient(parent)
        self.after(30, lambda: (apply_glass(self), protect(self)))
        self.box = card(self)
        self.box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
        ctk.CTkLabel(self.box, text=t(VAULT_TITLES[mode]), font=font(15, "bold"), text_color=TEXT,
                     anchor="w").pack(fill="x", padx=20, pady=(16, 0))
        ctk.CTkLabel(self.box, text=t(VAULT_HINTS[mode], name=name), font=font(12), text_color=SECONDARY, anchor="w",
                     justify="left", wraplength=340).pack(fill="x", padx=20, pady=(2, 8))
        self.fields = ctk.CTkFrame(self.box, fg_color="transparent")
        self.fields.pack(fill="x", padx=20)
        self.error = ctk.CTkLabel(self.box, text="", font=font(11), text_color=RED, anchor="w")
        self.error.pack(fill="x", padx=20)
        buttons = ctk.CTkFrame(self.box, fg_color="transparent")
        buttons.pack(fill="x", side="bottom", padx=20, pady=(4, 16))
        ctk.CTkButton(buttons, text=t("vault.ok"), command=self.submit, height=34, corner_radius=6, fg_color=INK,
                      hover_color=INK_HOVER, text_color=INK_TEXT, font=font(12, "bold"), width=90).pack(side="right")
        ctk.CTkButton(buttons, text=t("vault.cancel"), command=self.destroy, height=34, corner_radius=6,
                      fg_color="transparent", hover_color=HOVER, text_color=TEXT, border_width=1, border_color=BORDER,
                      font=font(12), width=80).pack(side="right", padx=8)
        if mode in ("open", "repin", "convert"):
            self.switch_link = ctk.CTkButton(buttons, text=t("vault.use_recovery"), command=self.toggle_recovery,
                                             fg_color="transparent", hover_color=HOVER, text_color=SECONDARY,
                                             font=font(11), height=34, width=10)
            self.switch_link.pack(side="left")
        self._fields()
        self.bind("<Return>", lambda _e: self.submit())
        self.grab_set()

    def _entry(self, placeholder: str, secret: bool = True) -> ctk.CTkEntry:
        entry = ctk.CTkEntry(self.fields, placeholder_text=placeholder, show="•" if secret else "", height=34,
                             corner_radius=6, border_width=1, border_color=BORDER, fg_color=MAIN_BG, text_color=TEXT,
                             font=font(13, family=MONO if not secret else None))
        entry.pack(fill="x", pady=4)
        return entry

    def _fields(self) -> None:
        for child in self.fields.winfo_children():
            child.destroy()
        self.current = self.new = self.again = None
        if self.mode in ("open", "unlock", "repin", "convert", "store", "copy_open"):
            label = t("vault.recovery") if self.use_recovery else t("store.password") \
                if self.mode in ("store", "copy_open") else t("vault.pin")
            self.current = self._entry(label, secret=not self.use_recovery)
        if self.mode in ("lock", "repin", "convert"):
            self.new = self._entry(t("vault.new_pin"))
            self.again = self._entry(t("vault.new_pin_again"))
        if self.mode == "copy_set":
            self.new = self._entry(t("store.new_password"))
            self.again = self._entry(t("store.new_password_again"))
        (self.current or self.new).focus_set()

    def toggle_recovery(self) -> None:
        self.use_recovery = not self.use_recovery
        self.switch_link.configure(text=t("vault.use_pin") if self.use_recovery else t("vault.use_recovery"))
        self._fields()

    def submit(self) -> None:
        values = {}
        if self.current is not None:
            values["recovery" if self.use_recovery else "pin"] = self.current.get().strip()
        if self.new is not None:
            if self.new.get() != self.again.get():
                self.error.configure(text=t("vault.mismatch"))
                return
            if self.mode == "copy_set" and not cloudkeys.password_ok(self.new.get()):
                self.error.configure(text=t("store.password_rule"))
                return
            if self.mode != "copy_set" and not 6 <= len(self.new.get()) <= 64:
                self.error.configure(text=t("vault.pin_rule"))
                return
            values["new_pin"] = self.new.get()
        self.error.configure(text=t("vault.working"))
        self.on_done(self, values)

    def fail(self, message: str) -> None:
        if self.winfo_exists():
            self.error.configure(text=message)


RECOVERY_HINTS = {"vault.recovery_hint": "vault.recovery_hint", "store.recovery_hint": "store.recovery_hint"}
VAULT_TITLES = {"open": "vault.open_title", "lock": "vault.lock_title", "unlock": "vault.unlock_title",
                "repin": "vault.repin_title", "convert": "migrate.vault_title", "store": "store.remote_title",
                "copy_set": "sync.copy_set_title", "copy_open": "sync.copy_open_title"}
VAULT_HINTS = {"open": "vault.open_hint", "lock": "vault.lock_hint", "unlock": "vault.unlock_hint",
               "repin": "vault.repin_hint", "convert": "migrate.vault_hint", "store": "store.remote_hint",
               "copy_set": "sync.copy_set_hint", "copy_open": "sync.copy_open_hint"}


def ask_copy_password(win, then=None) -> None:
    """동기화 사본(§19.3): 비밀번호를 아직 안 정했으면 정하고, 잠겨 있으면 연다. 되면 then()."""
    syncer = win.app.node.syncer
    mode = "copy_open" if syncer.device.exists else "copy_set"

    def done(dialog, values) -> None:
        def work() -> bool:
            if mode == "copy_set":
                syncer.setup(values["new_pin"])
                return True
            return syncer.unlock(values.get("pin", ""))

        def after(ok, error) -> None:
            if error is not None or not ok:
                dialog.fail(str(error) if error is not None else t("err.store_password"))
                return
            dialog.destroy()
            if then is not None:
                then()

        threading.Thread(target=lambda: _run(win, work, after), daemon=True).start()

    PinDialog(win, mode, "", done)


def _run(win, work, after) -> None:
    try:
        result, error = work(), None
    except Exception as exc:  # noqa: BLE001 - 창에 이유를 보인다
        result, error = None, exc
    win.app.events.put(("ui_call", {"fn": lambda: win.winfo_exists() and after(result, error)}))


def show_recovery(parent, code: str, hint: str = "vault.recovery_hint") -> None:
    """복구 코드를 한 번만 보여 준다(복사 버튼, 다시 볼 수 없다는 경고)."""
    win = ctk.CTkToplevel(parent)
    win.title(t("vault.recovery_title"))
    win.geometry("460x300")
    win.resizable(False, False)
    win.transient(parent)
    win.after(30, lambda: (apply_glass(win), protect(win)))
    box = card(win)
    box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
    ctk.CTkLabel(box, text=t("vault.recovery_title"), font=font(15, "bold"), text_color=TEXT,
                 anchor="w").pack(fill="x", padx=20, pady=(16, 0))
    ctk.CTkLabel(box, text=t(RECOVERY_HINTS[hint]), font=font(12), text_color=AMBER, anchor="w", justify="left",
                 wraplength=400).pack(fill="x", padx=20, pady=(4, 10))
    code_box = ctk.CTkFrame(box, fg_color=BUBBLE, corner_radius=8)
    code_box.pack(fill="x", padx=20)
    ctk.CTkLabel(code_box, text=code, font=font(14, "bold", MONO), text_color=TEXT).pack(padx=12, pady=12)
    row = ctk.CTkFrame(box, fg_color="transparent")
    row.pack(fill="x", side="bottom", padx=20, pady=16)

    def copy() -> None:
        copy_secret(win, code)
        copy_button.configure(text=t("vault.copied"))

    copy_button = ctk.CTkButton(row, text=t("vault.copy"), command=copy, height=34, corner_radius=6,
                                fg_color="transparent", hover_color=HOVER, text_color=TEXT, border_width=1,
                                border_color=BORDER, font=font(12), width=90)
    copy_button.pack(side="left")
    ctk.CTkButton(row, text=t("vault.saved"), command=win.destroy, height=34, corner_radius=6, fg_color=INK,
                  hover_color=INK_HOVER, text_color=INK_TEXT, font=font(12, "bold"), width=120).pack(side="right")
    win.grab_set()


class CloudWindow(ctk.CTkToplevel):
    def __init__(self, app, source: str | None = None, focus: str | None = None) -> None:
        super().__init__(app, fg_color=MAIN_BG)
        self.app = app
        self.node = app.node
        self.title(t("cloud.title"))
        fit_geometry(self, 980, 660)
        self.minsize(700, 460)
        self.source = source if source is not None else (LOCAL if self.node.cloud_on else next(iter(self.node.pairings), LOCAL))
        self.path: list[str] = []
        self.states: dict[str, str] = {}  # 기기별 상태: checking | on | off | old | unreachable
        self.state_errors: dict[str, str] = {}
        self.caps: dict[str, frozenset] = {}
        self.sync_all: dict[str, bool] = {}  # 허브별 전체 동기화(hello "sync_all", §19.2)
        self._menu_var: tk.BooleanVar | None = None
        self.items: list[cloud.Item] = []
        self.results: list[dict] | None = None  # 검색 중이면 검색 결과
        self.next_cursor: int | None = None
        self.shown = RENDER_PAGE
        self.loading = 0  # 늦게 온 응답이 다른 폴더의 목록을 덮지 않게 요청마다 번호를 붙인다
        self.error = ""
        self.view = app.node.settings.get("cloud_view") if app.node.settings.get("cloud_view") in ("grid", "list") \
            else "list"
        self.kind = "all"
        self.query_after: str | None = None
        self.thumbs: OrderedDict[tuple, ctk.CTkImage] = OrderedDict()
        self.thumb_waiting: dict[tuple, list[ctk.CTkLabel]] = {}
        self.pool = ThreadPoolExecutor(max_workers=3, thread_name_prefix="tailhop-thumb")
        self.selected: dict[str, cloud.Item] = {}  # 고른 항목(클라우드 경로별 항목)
        self.anchor: str | None = None  # Shift+클릭 범위의 시작
        self.cells: dict[str, tuple[ctk.CTkFrame, ctk.CTkLabel, bool]] = {}  # 경로별 (줄·칸, 체크 상자, 칸인지)
        self.list_key: tuple = ()
        self.viewer: lightbox.Lightbox | None = None
        self.player: media.RangeServer | None = None  # 영상 재생용(창을 닫으면 끈다)
        self.video: player.VideoWindow | None = None
        self.cell_items: dict[str, cloud.Item] = {}
        self.no_click: dict[str, list] = {}  # 경로별 눌러도 열지 않는 자리(체크 상자·오른쪽 버튼)
        self.crumb_targets: list[tuple[ctk.CTkLabel, list[str]]] = []
        self.drag: dict | None = None  # 끌어다 놓기로 옮기는 중
        self.focus_path = focus  # 처음 목록을 읽으면 이 파일을 연다(대화방에서 '클라우드에 모은 파일'을 눌렀을 때)
        self._build()
        self.drop_target_register(DND_FILES)
        self.dnd_bind("<<DropEnter>>", lambda e: (self.list.configure(fg_color=DROP), e.action)[1])
        self.dnd_bind("<<DropLeave>>", lambda e: (self.list.configure(fg_color=MAIN_BG), e.action)[1])
        self.dnd_bind("<<Drop>>", self._on_drop)
        self.after(250, self._set_icon)
        self.after(30, lambda: (apply_glass(self), protect(self)))
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.bind("<Escape>", lambda _e: self.clear_selection())
        self.bind("<Control-a>", self._select_all_key)
        self.check_sources()
        self.open_source(self.source)

    def close(self) -> None:
        self.pool.shutdown(wait=False, cancel_futures=True)
        self.close_viewers()
        if self.player is not None:
            self.player.close()
        self.destroy()

    def _set_icon(self) -> None:
        ico = self.app.icon_path
        if ico is not None:
            try:
                self.iconbitmap(str(ico))
            except tk.TclError:
                pass

    # ---------- 스레드 ----------
    def _bg(self, work, done) -> None:
        """work()를 작업 스레드에서 돌리고 done(결과, 예외)을 화면 스레드에서 부른다."""
        def run() -> None:
            try:
                result, error = work(), None
            except Exception as exc:  # noqa: BLE001 - 화면에 이유를 보인다
                result, error = None, exc
            self.app.events.put(("ui_call", {"fn": lambda: self.winfo_exists() and done(result, error)}))

        threading.Thread(target=run, daemon=True).start()

    def _src(self) -> Source:
        return Source(self.node, self.source, offline=self._offline())

    def _offline(self) -> bool:
        """허브가 꺼졌거나 닿지 않거나 잠겨 있는데 이 PC에 그 허브의 동기화 사본이 있다: 사본을 읽기 전용으로(§19.3)."""
        return self.source != LOCAL and self.states.get(self.source) in ("unreachable", "store") \
            and self.node.syncer.mirror(self.source) is not None

    def _can_sync(self) -> bool:
        if self._offline():
            return False
        return (self.source == LOCAL and self.node.cloud_state == "open") \
            or "sync" in self.caps.get(self.source, frozenset())

    def _post(self, fn) -> None:
        self.app.events.put(("ui_call", {"fn": fn}))

    # ---------- 틀 ----------
    def _build(self) -> None:
        # 큰 창과 같이: 유리 바탕 위에 카드 두 장(기기 목록, 폴더 목록)
        self.side = card(self, fg_color=SIDEBAR_BG, width=220)
        self.side.pack(side="left", fill="y", padx=(GUTTER, GUTTER // 2), pady=GUTTER)
        self.side.pack_propagate(False)
        head = ctk.CTkFrame(self.side, fg_color="transparent", height=52)
        head.pack(fill="x", padx=10, pady=(8, 4))
        ctk.CTkLabel(head, text=" " + t("cloud.title"), image=icon("cloud", 18), compound="left",
                     font=font(17, "bold"), text_color=TEXT).pack(side="left", padx=6)
        ghost_button(head, "refresh-cw", self.check_sources).pack(side="right")
        self.source_list = ctk.CTkScrollableFrame(self.side, fg_color="transparent", corner_radius=0,
                                                  scrollbar_button_color=SIDEBAR_BG,
                                                  scrollbar_button_hover_color=BORDER)
        self.source_list.pack(fill="both", expand=True, padx=(2, 0), pady=(0, 10))  # 카드의 아래 둥근 모서리를 덮지 않게

        main = card(self)
        main.pack(side="left", fill="both", expand=True, padx=(GUTTER // 2, GUTTER), pady=GUTTER)
        top = ctk.CTkFrame(main, fg_color="transparent", height=52)
        top.pack(fill="x", padx=10, pady=(8, 0))
        top.pack_propagate(False)
        self.up_button = ghost_button(top, "chevron-left", self.go_up)
        self.up_button.pack(side="left")
        self.crumbs = ctk.CTkFrame(top, fg_color="transparent")
        self.crumbs.pack(side="left", fill="x", expand=True, padx=4)
        self.tools = ctk.CTkFrame(top, fg_color="transparent")
        self.tools.pack(side="right")

        # 검색 줄: 검색 칸 + 종류(shadcn Tabs 모양의 나눔 버튼)
        search = ctk.CTkFrame(main, fg_color="transparent")
        search.pack(fill="x", padx=18, pady=(0, 6))
        self.query = ctk.CTkEntry(search, placeholder_text=t("cloud.search"), height=32, corner_radius=6,
                                  border_width=1, border_color=BORDER, fg_color=MAIN_BG, text_color=TEXT,
                                  font=font(12), width=220)
        self.query.pack(side="left")
        self.query.bind("<KeyRelease>", lambda _e: self._query_changed())
        names = [t(KIND_LABELS[k]) for k in KIND_FILTERS]
        self.kind_tabs = ctk.CTkSegmentedButton(search, values=names, command=self._kind_changed, height=30,
                                                corner_radius=6, font=font(11), fg_color=BUBBLE,
                                                selected_color=MAIN_BG, selected_hover_color=MAIN_BG,
                                                unselected_color=BUBBLE, unselected_hover_color=HOVER,
                                                text_color=TEXT)
        self.kind_tabs.set(names[0])
        self.kind_tabs.pack(side="left", padx=10)

        # 고른 항목이 있을 때만 목록 위에 보이는 줄: 개수, 모두 고르기·받기·옮기기·지우기, 해제
        self.select_bar = ctk.CTkFrame(main, fg_color=BUBBLE, corner_radius=8, height=44)
        self.select_bar.pack_propagate(False)
        ghost_button(self.select_bar, "x", self.clear_selection, size=30).pack(side="left", padx=(6, 2))
        self.select_label = ctk.CTkLabel(self.select_bar, text="", font=font(13, "bold"), text_color=TEXT)
        self.select_label.pack(side="left", padx=4)
        self.select_buttons = ctk.CTkFrame(self.select_bar, fg_color="transparent")
        self.select_buttons.pack(side="right", padx=6)

        bottom = ctk.CTkFrame(main, fg_color="transparent")
        bottom.pack(side="bottom", fill="x", padx=20, pady=(4, 12))
        self.transfer_bar = ctk.CTkProgressBar(bottom, height=3, corner_radius=2, progress_color=INK, fg_color=BORDER)
        self.transfer_label = ctk.CTkLabel(bottom, text="", font=font(11), text_color=SECONDARY, height=18,
                                           anchor="w")
        self.transfer_label.pack(fill="x")
        self.sync_label = ctk.CTkLabel(bottom, text="", font=font(11), text_color=SECONDARY, height=18, anchor="w",
                                       image=icon("monitor-smartphone", 13, SECONDARY), compound="left")

        self.list = ctk.CTkScrollableFrame(main, fg_color=MAIN_BG, corner_radius=0,
                                           scrollbar_button_color=BORDER, scrollbar_button_hover_color=SECONDARY)
        self.list.pack(fill="both", expand=True, padx=8, pady=(0, 4))
        self.list.drop_target_register(DND_FILES)
        self.list.dnd_bind("<<Drop>>", self._on_drop)
        self.update_transfers()

    def _source_name(self, sid: str) -> str:
        if sid == LOCAL:
            return t("cloud.this_pc")
        pairing = self.node.pairings.get(sid)
        return pairing.name if pairing else "?"

    def render_sources(self) -> None:
        for child in self.source_list.winfo_children():
            child.destroy()
        own = self.node.space()
        rows = [(LOCAL, "laptop", t("cloud.this_pc"), store_ui.state_text(self.node.cloud_state)[0],
                 (own["free"], own["total"]) if own else None)]
        for sid, pairing in list(self.node.pairings.items()):
            state = self.states.get(sid, "checking")
            detail = t("sync.offline_copy") if state in ("unreachable", "store") and self.node.syncer.mirror(sid) \
                else t(STATE_TEXT[state])
            rows.append((sid, KIND_ICON.get(pairing.kind, "smartphone"), pairing.name, detail,
                         self.node.peer_space.get(sid, (None, None))[:2]))
        for sid, icon_name, name, detail, space in rows:
            selected = sid == self.source
            known = space is not None and space[0] is not None
            row = ctk.CTkFrame(self.source_list, fg_color=SELECTED if selected else "transparent", corner_radius=6,
                               height=64 if known else 50, cursor="hand2")
            row.pack(fill="x", padx=6, pady=1)
            row.pack_propagate(False)
            round_icon(row, icon_name, 30).pack(side="left", padx=(8, 10))
            texts = ctk.CTkFrame(row, fg_color="transparent")
            texts.pack(side="left", fill="x", expand=True)
            ctk.CTkLabel(texts, text=name, font=font(13), text_color=TEXT, anchor="w", height=18).pack(fill="x",
                                                                                                    pady=(7, 0))
            on = (sid == LOCAL and self.node.cloud_state == "open") or self.states.get(sid) == "on"
            ctk.CTkLabel(texts, text=detail, font=font(11), text_color=GREEN if on else SECONDARY, anchor="w",
                         height=15).pack(fill="x")
            if known:  # 그 기기의 용량(§19.1)
                ctk.CTkLabel(texts, text=t("sync.free", free=human_size(space[0])) + " / " + human_size(space[1]),
                             font=font(10), text_color=SECONDARY, anchor="w", height=14).pack(fill="x")
            bind_tree(row, "<Button-1>", lambda _e, s=sid: self.open_source(s))
            if not selected:
                bind_tree(row, "<Enter>", lambda _e, r=row: r.configure(fg_color=HOVER))
                bind_tree(row, "<Leave>", lambda _e, r=row: r.configure(fg_color="transparent"))

    def check_sources(self) -> None:
        """기기마다 클라우드가 켜져 있는지 hello로 묻는다(동시에)."""
        for sid in list(self.node.pairings):
            self.states[sid] = "checking"

            def work(s=sid):
                info = self.node._hello(s)
                return info

            def done(info, error, s=sid):
                if error is not None:
                    self.states[s], self.state_errors[s] = "unreachable", str(error)
                else:
                    caps = info["caps"]
                    self.caps[s] = caps
                    self.sync_all[s] = info.get("sync_all") is True
                    not_ready = info.get("store") in ("new", "locked", "missing")  # §17: 켜졌지만 저장소가 안 열림
                    self.states[s] = "old" if "cloud" not in caps else "on" if info.get("cloud") is True \
                        else "store" if not_ready else "off"
                    self.state_errors[s] = ""
                self.render_sources()
                if s == self.source and not self.items:
                    self.reload()

            self._bg(work, done)
        self.render_sources()

    def open_source(self, sid: str) -> None:
        self.source = sid
        self.path = []
        self.render_sources()
        self.reload()

    def go_up(self) -> None:
        if self.path:
            self.path = self.path[:-1]
            self.reload()

    def enter(self, name: str) -> None:
        self.path = [*self.path, name]
        self.reload()

    def _has_cloud2(self) -> bool:
        return self.source == LOCAL or self._offline() or "cloud2" in self.caps.get(self.source, frozenset())

    def _render_tools(self) -> None:
        for child in self.tools.winfo_children():
            child.destroy()
        if not self._browsable():
            return
        if self._offline():  # 사본은 읽기만
            ghost_button(self.tools, "refresh-cw", self.check_sources).pack(side="right")
            ghost_button(self.tools, "list" if self.view == "grid" else "layout-grid", self.toggle_view).pack(
                side="right")
            return
        if self._can_sync():
            ghost_button(self.tools, "monitor-smartphone", self.sync_menu).pack(side="right")
        if self.source == LOCAL:
            ghost_button(self.tools, "folder", self.open_local_folder).pack(side="right")
            ghost_button(self.tools, "trash-2", lambda: TrashWindow(self)).pack(side="right")
        ghost_button(self.tools, "cloud-upload", self.pick_upload).pack(side="right")
        ghost_button(self.tools, "folder-plus", self.new_folder).pack(side="right")
        ghost_button(self.tools, "refresh-cw", self.reload).pack(side="right")
        ghost_button(self.tools, "list" if self.view == "grid" else "layout-grid", self.toggle_view).pack(side="right")

    def _render_crumbs(self) -> None:
        for child in self.crumbs.winfo_children():
            child.destroy()
        parts = [(t("cloud.root_of", name=self._source_name(self.source)), [])]
        parts += [(name, self.path[:i + 1]) for i, name in enumerate(self.path)]
        self.crumb_targets = []
        for i, (label, target) in enumerate(parts):
            if i:
                ctk.CTkLabel(self.crumbs, text="", image=icon("chevron-right", 14, SECONDARY), width=16).pack(side="left")
            last = i == len(parts) - 1
            name = label if len(label) <= 24 else label[:23] + "…"
            crumb = ctk.CTkLabel(self.crumbs, text=name, font=font(14 if last else 13, "bold" if last else "normal"),
                                 text_color=TEXT if last else SECONDARY, cursor="" if last else "hand2")
            crumb.pack(side="left", padx=2)
            if not last:
                crumb.bind("<Button-1>", lambda _e, tg=target: self._jump(tg))
                self.crumb_targets.append((crumb, list(target)))  # 끌어다 놓으면 그 위 폴더로
        self.up_button.configure(state="normal" if self.path else "disabled")

    def _jump(self, target: list[str]) -> None:
        self.path = list(target)
        self.reload()

    def _browsable(self) -> bool:
        return (self.source == LOCAL and self.node.cloud_state == "open") or self.states.get(self.source) == "on" \
            or self._offline()

    def toggle_view(self) -> None:
        self.view = "grid" if self.view == "list" else "list"
        self.node.settings["cloud_view"] = self.view
        self.node.save_settings()
        self._render_tools()
        self.render_items()

    # ---------- 검색 ----------
    def _query_changed(self) -> None:
        if self.query_after is not None:
            self.after_cancel(self.query_after)
        self.query_after = self.after(300, self.reload)  # 칠 때마다가 아니라 멈추면 찾는다

    def _kind_changed(self, label: str) -> None:
        self.kind = next(k for k in KIND_FILTERS if t(KIND_LABELS[k]) == label)
        self.reload()

    def _searching(self) -> bool:
        return bool(self.query.get().strip()) or self.kind != "all"

    # ---------- 목록 ----------
    def reload(self) -> None:
        self.query_after = None
        key = (self.source, tuple(self.path), self.query.get().strip(), self.kind)
        if key != self.list_key:
            self.list_key = key
            self.selected.clear()
            self.anchor = None
            self._update_select_bar()
        self._render_crumbs()
        self._render_tools()
        self.refresh_sync_status()
        self.items, self.results, self.next_cursor, self.shown, self.error = [], None, None, RENDER_PAGE, ""
        if not self._browsable():
            self._render_message()
            return
        self.loading += 1
        token, src, path = self.loading, self._src(), cloud.join_path(self.path)
        self._message(t("cloud.loading"))
        if self._searching() and self._has_cloud2():
            query, kind = self.query.get().strip(), self.kind
            self._bg(lambda: src.search(path, query, kind), lambda r, e: self._searched(token, r, e))
        else:
            self._bg(lambda: src.list(path, 0), lambda r, e: self._loaded(token, r, e, append=False))

    def _loaded(self, token: int, result, error, append: bool) -> None:
        if token != self.loading:
            return  # 그 사이 다른 폴더로 옮겼다
        if error is not None:
            code = getattr(error, "code", "")
            if code == "not_found" and self.path:
                self.path = self.path[:-1]  # 폴더가 사라졌다(다른 기기에서 지움): 위로
                self.reload()
                return
            if code == "mirror_locked" and self._offline():  # 사본이 잠겨 있다: 비밀번호로 연다
                self._message(t("sync.copy_locked", name=self._source_name(self.source)))
                ctk.CTkButton(self.list, text=t("sync.copy_open"), command=lambda: ask_copy_password(self, self.reload),
                              fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT, corner_radius=6, height=36,
                              font=font(13, "bold")).pack(pady=14)
                return
            if code == "vault_locked" and self.path:
                locked_name = self.path[-1]
                self.path = self.path[:-1]  # 열쇠가 끝났다(30분): 다시 PIN을 묻는다
                self.reload()
                self.ask_open(locked_name)
                return
            self.error = str(error)
            self._render_message()
            return
        items, nxt = result
        self.items = self.items + items if append else items
        self.next_cursor = nxt
        self.render_items()
        focus, self.focus_path = self.focus_path, None
        hit = next(((x, f) for x, f, _ in self._entries() if f == focus), None) if focus else None
        if hit is not None:
            self.activate(*hit)
        if any(x.locked and x.state in ("locking", "unlocking") for x in self.items):
            self.after(2000, lambda: token == self.loading and self.reload())  # 잠그는 중: 진행을 다시 본다

    def _searched(self, token: int, result, error) -> None:
        if token != self.loading:
            return
        if error is not None:
            self.error = str(error)
            self._render_message()
            return
        found, nxt, partial = result
        self.results = found
        self.next_cursor = nxt
        self.render_items(partial=partial)

    def load_more(self) -> None:
        if self.shown < len(self.results if self.results is not None else self.items):
            self.shown += RENDER_PAGE
            self.render_items()
            return
        if self.next_cursor is None:
            return
        token, src, path, cursor = self.loading, self._src(), cloud.join_path(self.path), self.next_cursor
        self.shown += RENDER_PAGE
        if self.results is not None:
            query, kind = self.query.get().strip(), self.kind

            def more(r, e):
                if e is None and token == self.loading:
                    self.results += r[0]
                    self.next_cursor = r[1]
                    self.render_items()

            self._bg(lambda: src.search(path, query, kind, cursor), more)
        else:
            self._bg(lambda: src.list(path, cursor), lambda r, e: self._loaded(token, r, e, append=True))

    def _clear_list(self) -> None:
        for child in self.list.winfo_children():
            child.destroy()
        self.after(80, self._fix_scroll)

    def _fix_scroll(self) -> None:
        """다시 그린 뒤 스크롤 범위를 지금 내용 크기로 고치고 맨 위로(줄어든 내용 아래 빈 곳을 보고 있지 않게)."""
        if not self.winfo_exists():
            return
        canvas = self.list._parent_canvas
        canvas.update_idletasks()
        canvas.configure(scrollregion=canvas.bbox("all"))
        if canvas.yview()[1] >= 1.0 and canvas.yview()[0] > 0:
            canvas.yview_moveto(0.0)

    def _message(self, text: str, color=SECONDARY) -> None:
        self._clear_list()
        ctk.CTkLabel(self.list, text=text, font=font(13), text_color=color, wraplength=520,
                     justify="center").pack(pady=(120, 0))

    def _render_message(self) -> None:
        if self.error:
            self._message(self.error, RED)
            return
        if self.source == LOCAL and self.node.cloud_state != "open":
            state = self.node.cloud_state
            text, button, command = {
                "off": (t("cloud.local_off"), t("cloud.turn_on"), self.app.toggle_cloud_on),
                "new": (t("store.local_new"), t("store.ok_create"),
                        lambda: store_ui.ask(self.app, self, "create", self.app.store_changed)),
                "locked": (t("store.local_locked"), t("store.ok_unlock"),
                           lambda: store_ui.ask(self.app, self, "unlock", self.app.store_changed)),
            }.get(state, (t("store.local_missing", path=str(self.node.cloud_dir)), t("settings.change"),
                          self.app._change_cloud_dir))
            self._clear_list()
            box = ctk.CTkFrame(self.list, fg_color="transparent")
            box.pack(pady=(90, 0))
            round_icon(box, "lock" if state in ("new", "locked") else "cloud", 56, fg=SELECTED,
                       color=SECONDARY).pack()
            ctk.CTkLabel(box, text=text, font=font(13), text_color=SECONDARY, wraplength=480,
                         justify="center").pack(pady=(14, 14))
            ctk.CTkButton(box, text=button, command=command, fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT,
                          corner_radius=6, height=36, font=font(13, "bold")).pack()
            return
        state = self.states.get(self.source, "checking")
        name = self._source_name(self.source)
        if state == "checking":
            self._message(t("cloud.loading"))
        elif state == "unreachable":
            self._message(t("cloud.peer_unreachable", name=name, error=self.state_errors.get(self.source, "")), RED)
        elif state == "old":
            self._message(t("cloud.peer_old", name=name))
        elif state == "store":
            self._message(t("cloud.peer_store", name=name))
            if "store_unlock" in self.caps.get(self.source, frozenset()):
                ctk.CTkButton(self.list, text=t("store.ok_unlock"), command=self.unlock_remote_store, fg_color=INK,
                              hover_color=INK_HOVER, text_color=INK_TEXT, corner_radius=6, height=36,
                              font=font(13, "bold")).pack(pady=14)
        else:
            self._message(t("cloud.peer_off", name=name))

    def unlock_remote_store(self) -> None:
        """§18.3: 강한 잠금인 다른 PC의 클라우드를 그 비밀번호로 연다(비밀번호는 어디에도 저장하지 않는다)."""
        sid = self.source

        def done(dialog, values) -> None:
            def after(_r, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                self.check_sources()

            self._bg(lambda: self.node.store_unlock(sid, values.get("pin", "")), after)

        PinDialog(self, "store", self._source_name(sid), done)

    def render_items(self, partial: bool = False) -> None:
        self._clear_list()
        self.cells = {}
        self.cell_items = {}
        self.no_click = {}
        entries = self._entries()
        present = {full for _, full, _ in entries}
        for gone in [x for x in self.selected if x not in present]:  # 다른 곳에서 지워지거나 옮겨진 것
            del self.selected[gone]
        self._update_select_bar()
        if self._offline():
            self._offline_banner()
        if not entries:
            if self._offline():
                ctk.CTkLabel(self.list, text=t("cloud.no_results") if self.results is not None else t("cloud.empty"),
                             font=font(13), text_color=SECONDARY).pack(pady=(80, 0))
                return
            self._message(t("cloud.no_results") if self.results is not None else t("cloud.empty"))
            return
        if self.view == "grid":
            self._grid(entries[:self.shown])
        else:
            for entry in entries[:self.shown]:
                self._row(*entry)
        if partial:
            ctk.CTkLabel(self.list, text=t("cloud.partial"), font=font(11), text_color=SECONDARY).pack(pady=6)
        if self.shown < len(entries) or self.next_cursor is not None:
            ctk.CTkButton(self.list, text=t("cloud.more"), command=self.load_more, height=28, corner_radius=6,
                          fg_color="transparent", hover_color=HOVER, text_color=SECONDARY, border_width=1,
                          border_color=BORDER, font=font(11)).pack(pady=10)

    def _entries(self) -> list[tuple[cloud.Item, str, str]]:
        """(항목, 클라우드 경로, 부제). 검색 결과면 부제가 찾은 폴더 위치다."""
        base = cloud.join_path(self.path)
        if self.results is None:
            return [(x, cloud.join_path([*self.path, x.name]), "") for x in self.items]
        out = []
        for raw in self.results:
            item = cloud.Item(raw["name"], raw["dir"], raw["size"], raw["mtime"])
            full = f"{base}/{raw['path']}" if base else raw["path"]
            where = raw["path"].rpartition("/")[0]
            out.append((item, full, where))
        return out

    def _item_icon(self, item: cloud.Item) -> str:
        if item.locked:
            return "lock"
        if item.is_dir:
            return "folder"
        return KIND_ICONS.get(cloud.file_kind(item.name), "file")

    def _row(self, item: cloud.Item, full: str, where: str) -> None:
        row = ctk.CTkFrame(self.list, fg_color="transparent", corner_radius=6, height=48, cursor="hand2")
        row.pack(fill="x", padx=4, pady=1)
        row.pack_propagate(False)
        check = self._check_box(row)
        check.pack(side="left", padx=(10, 0))
        badge = self._preview(row, item, full, 36)
        badge.pack(side="left", padx=(8, 10))
        texts = ctk.CTkFrame(row, fg_color="transparent")
        texts.pack(side="left", fill="x", expand=True)
        name = item.name if len(item.name) <= 60 else item.name[:57] + "…"
        label = ctk.CTkLabel(texts, text=name + "  " if item.sync else name, font=font(13), text_color=TEXT, anchor="w",
                             height=18, image=icon("monitor-smartphone", 14, GREEN) if item.sync else None,
                             compound="right")  # 모든 기기에 동기화하는 항목은 이름 옆에 표시
        label.pack(fill="x", pady=(7 if where or item.locked else 14, 0))
        sub = where or self._locked_text(item)
        if sub:
            ctk.CTkLabel(texts, text=sub, font=font(11), text_color=AMBER if item.locked else SECONDARY, anchor="w",
                         height=15).pack(fill="x")
        actions = ctk.CTkFrame(row, fg_color="transparent")
        actions.pack(side="right", padx=(4, 6))
        ghost_button(actions, "ellipsis", lambda it=item, f=full: self.item_menu(it, f), size=28).pack(side="right")
        if not item.is_dir:
            ghost_button(actions, "download", lambda it=item, f=full: self.download(it, f), size=28).pack(side="right")
        when = dt.datetime.fromtimestamp(item.mtime / 1000).strftime("%Y-%m-%d %H:%M") if item.mtime else ""
        date = ctk.CTkLabel(row, text=when, font=font(11), text_color=SECONDARY, width=120, anchor="e")
        date.pack(side="right")
        size = ctk.CTkLabel(row, text="" if item.is_dir else human_size(item.size), font=font(11),
                            text_color=SECONDARY, width=80, anchor="e")
        size.pack(side="right")
        bind_tree(row, "<Enter>", lambda _e, f=full: self._paint(f, hover=True))
        bind_tree(row, "<Leave>", lambda _e, f=full: self._paint(f, hover=self._hovering(f)))
        # 줄을 누르면 열기·가져오기(고르는 중이거나 Ctrl·Shift면 고르기). 오른쪽 버튼(가져오기·메뉴)은 따로 동작한다.
        for widget in (row, badge, texts, date, size):
            self._bind_press(widget, item, full)
            bind_tree(widget, "<Button-3>", lambda _e, it=item, f=full: self.item_menu(it, f))
        bind_tree(check, "<Button-1>", lambda _e, it=item, f=full: self.toggle(it, f))
        self.cells[full] = (row, check, False)
        self.cell_items[full] = item
        self.no_click[full] = [check, actions]
        self._paint(full)

    def _grid(self, entries) -> None:
        width = max(self.list.winfo_width(), 600)
        cols = max(2, (width - 24) // (TILE + 12))
        grid = ctk.CTkFrame(self.list, fg_color="transparent")
        grid.pack(fill="x", padx=4, pady=4)
        for i, (item, full, where) in enumerate(entries):
            tile = ctk.CTkFrame(grid, fg_color="transparent", corner_radius=8, width=TILE, height=TILE + 34,
                                cursor="hand2")
            tile.grid(row=i // cols, column=i % cols, padx=6, pady=6)
            tile.pack_propagate(False)
            badge = self._preview(tile, item, full, TILE - 20)
            badge.pack(pady=(8, 4))
            name = item.name if len(item.name) <= 18 else item.name[:16] + "…"
            label = ctk.CTkLabel(tile, text=name, font=font(12), text_color=TEXT, height=16)
            label.pack()
            sub = self._locked_text(item) or (human_size(item.size) if not item.is_dir else "")
            ctk.CTkLabel(tile, text=(" " + sub) if item.sync else sub, font=font(10),
                         text_color=AMBER if item.locked else SECONDARY, height=14,
                         image=icon("monitor-smartphone", 12, GREEN) if item.sync else None,
                         compound="left").pack()
            check = self._check_box(tile)
            bind_tree(tile, "<Enter>", lambda _e, f=full: self._paint(f, hover=True))
            bind_tree(tile, "<Leave>", lambda _e, f=full: self._paint(f, hover=self._hovering(f)))
            self._bind_press(tile, item, full)
            bind_tree(tile, "<Button-3>", lambda _e, it=item, f=full: self.item_menu(it, f))
            bind_tree(check, "<Button-1>", lambda _e, it=item, f=full: self.toggle(it, f))
            self.cells[full] = (tile, check, True)
            self.cell_items[full] = item
            self.no_click[full] = [check]
            self._paint(full)

    # ---------- 고르기 ----------
    def _check_box(self, parent) -> ctk.CTkLabel:
        """체크 상자: 매끈하게 그린 그림 한 장(작은 CTk 테두리는 계단진다)."""
        return ctk.CTkLabel(parent, text="", image=checkbox_image(False), width=20, height=20, fg_color="transparent",
                            cursor="hand2")

    def _paint(self, full: str, hover: bool = False) -> None:
        """한 줄·칸의 고른 모양(바탕, 체크 상자). 칸의 체크 상자는 고르는 중이거나 마우스가 올라갔을 때만 보인다."""
        cell = self.cells.get(full)
        if cell is None or not cell[0].winfo_exists():
            return
        frame, check, tile = cell
        on = full in self.selected
        frame.configure(fg_color=DROP_ROW if on else HOVER if hover else "transparent")
        check.configure(image=checkbox_image(on))
        if tile:
            if on or hover or self.selected:
                check.place(x=10, y=10)
                check.lift()
            else:
                check.place_forget()

    def _paint_all(self) -> None:
        for full in list(self.cells):
            self._paint(full)

    def _inside(self, widget, owner) -> bool:
        """widget이 owner(또는 그 안의 CTk 내부 위젯)인지. CTk의 bind는 덧붙이므로 누른 자리를 보고 가른다."""
        return str(widget) == str(owner) or str(widget).startswith(str(owner) + ".")

    def _hovering(self, full: str) -> bool:
        """마우스가 아직 그 줄·칸 안에 있는지(체크 상자로 옮겨 가며 생기는 Leave에 깜박이지 않게)."""
        cell = self.cells.get(full)
        if cell is None:
            return False
        under = self.winfo_containing(*self.winfo_pointerxy())
        return under is not None and self._inside(under, cell[0])

    # ---------- 누르기·끌어다 놓기 ----------
    def _bind_press(self, widget, item: cloud.Item, full: str) -> None:
        """누르고 떼면 열기·고르기, 누른 채 끌면 옮기기(고른 것 전부 또는 그 하나)."""
        bind_tree(widget, "<ButtonPress-1>", lambda e, it=item, f=full: self._press(e, it, f))
        bind_tree(widget, "<B1-Motion>", self._motion)
        bind_tree(widget, "<ButtonRelease-1>", lambda e, it=item, f=full: self._release(e, it, f))

    def _blocked_zone(self, widget, full: str) -> bool:
        return any(zone is not None and self._inside(widget, zone) for zone in self.no_click.get(full, []))

    def _press(self, event, item: cloud.Item, full: str) -> None:
        if self._blocked_zone(event.widget, full):
            self.drag = None
            return
        self.drag = {"x": event.x_root, "y": event.y_root, "item": item, "full": full, "on": False, "over": None}

    def _motion(self, event) -> None:
        d = self.drag
        if d is None:
            return
        if not d["on"]:
            if abs(event.x_root - d["x"]) + abs(event.y_root - d["y"]) < 10 or not self._can_move():
                return
            moving = dict(self.selected) if d["full"] in self.selected else {d["full"]: d["item"]}
            ghost = tk.Toplevel(self)
            ghost.overrideredirect(True)
            ghost.attributes("-topmost", True)
            protect(ghost)
            ctk.CTkLabel(ghost, text=" " + t("cloud.drag_count", count=len(moving)), image=icon("folder-input", 15,
                                                                                               INK_TEXT),
                         compound="left", fg_color=INK, text_color=INK_TEXT, corner_radius=6, font=font(12, "bold"),
                         height=30).pack(ipadx=8)
            d.update(on=True, moving=moving, ghost=ghost)
        d["ghost"].geometry(f"+{event.x_root + 16}+{event.y_root + 12}")
        over = self._drop_target(event.x_root, event.y_root, d["moving"])
        if over != d["over"]:
            self._mark_drop(d["over"], False)
            self._mark_drop(over, True)
            d["over"] = over

    def _release(self, event, item: cloud.Item, full: str) -> None:
        d, self.drag = self.drag, None
        if d is None:
            return
        if not d["on"]:
            self._click(event, item, full)
            return
        d["ghost"].destroy()
        self._mark_drop(d["over"], False)
        target = self._drop_target(event.x_root, event.y_root, d["moving"])
        if target is None:
            return
        dest = target[1]
        locked = self.cell_items.get(dest)
        if locked is not None and locked.locked and not self._src().is_open(dest):
            # 잠긴 폴더 위에 놓았다: 먼저 PIN으로 열고 옮긴다
            self._open_then(locked.name, dest, lambda: self._move(list(d["moving"]), dest))
            return
        self._move(list(d["moving"]), dest)

    def _drop_target(self, x: int, y: int, moving: dict) -> tuple[ctk.CTkBaseClass, str] | None:
        """그 자리의 놓을 수 있는 폴더(위젯, 경로). 옮기는 것 자신·그 아래·지금 폴더는 안 된다."""
        under = self.winfo_containing(x, y)
        if under is None:
            return None
        here = cloud.join_path(self.path)
        for widget, target in self.crumb_targets:
            if self._inside(under, widget):
                path = cloud.join_path(target)
                return (widget, path) if path != here else None
        for path, (frame, _check, _tile) in self.cells.items():
            if not self._inside(under, frame):
                continue
            item = self.cell_items.get(path)
            if item is None or not item.is_dir or path in moving or any(path.startswith(m + "/") for m in moving):
                return None
            if item.locked and item.state != "locked":
                return None
            return frame, path
        return None

    def _mark_drop(self, target, on: bool) -> None:
        if target is None or not target[0].winfo_exists():
            return
        widget, path = target
        if path in self.cells:
            if on:
                widget.configure(fg_color=DROP_ROW)
            else:
                self._paint(path)
        else:
            widget.configure(text_color=TEXT if on else SECONDARY)

    def _open_then(self, name: str, full: str, then) -> None:
        src = self._src()

        def done(dialog, values) -> None:
            def after(_r, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                then()

            self._bg(lambda: src.open(full, values.get("pin"), values.get("recovery")), after)

        PinDialog(self, "open", name, done)

    def _click(self, event, item: cloud.Item, full: str) -> None:
        if self._blocked_zone(event.widget, full):
            return  # 체크 상자·오른쪽 버튼을 눌렀다: 그 버튼만(열지 않는다)
        if event.state & 0x1 and self.anchor is not None:  # Shift: 앞서 고른 것부터 여기까지
            self._select_range(self.anchor, full)
        elif event.state & 0x4 or self.selected:  # Ctrl, 또는 이미 고르는 중이면 누를 때마다 고르기·빼기
            self.toggle(item, full)
        else:
            self.activate(item, full)

    def toggle(self, item: cloud.Item, full: str) -> None:
        was = bool(self.selected)
        if full in self.selected:
            del self.selected[full]
        else:
            self.selected[full] = item
        self.anchor = full
        if was != bool(self.selected):
            self._paint_all()  # 고르기 모드가 켜지거나 꺼짐: 모든 칸의 체크 상자
        else:
            self._paint(full)
        self._update_select_bar()

    def _select_range(self, start: str, end: str) -> None:
        entries = [(item, full) for item, full, _ in self._entries()]
        order = [full for _, full in entries]
        if start not in order or end not in order:
            return
        i, j = sorted((order.index(start), order.index(end)))
        for item, full in entries[i:j + 1]:
            self.selected[full] = item
        self._paint_all()
        self._update_select_bar()

    def select_all(self) -> None:
        for item, full, _ in self._entries():
            self.selected[full] = item
        self._paint_all()
        self._update_select_bar()

    def _select_all_key(self, _event):
        if isinstance(self.focus_get(), tk.Entry):
            return None  # 검색 칸에서는 글자 전체 선택
        if self._browsable():
            self.select_all()
        return "break"

    def clear_selection(self) -> None:
        if not self.selected:
            return
        self.selected.clear()
        self.anchor = None
        self._paint_all()
        self._update_select_bar()

    def _can_rename(self) -> bool:
        return self.source == LOCAL or "rename" in self.caps.get(self.source, frozenset())

    def _can_move(self) -> bool:
        return self.source == LOCAL or "move" in self.caps.get(self.source, frozenset())

    def _bar_button(self, text: str, icon_name: str, command, danger: bool = False) -> None:
        color = RED if danger else TEXT
        ctk.CTkButton(self.select_buttons, text=" " + text, image=icon(icon_name, 15, color), compound="left",
                      command=command, height=30, corner_radius=6, fg_color="transparent", hover_color=HOVER,
                      text_color=color, font=font(12), width=10).pack(side="left", padx=2)

    def _update_select_bar(self) -> None:
        if not self.selected:
            self.select_bar.pack_forget()
            return
        self.select_label.configure(text=t("cloud.selected", count=len(self.selected)))
        for child in self.select_buttons.winfo_children():
            child.destroy()
        self._bar_button(t("cloud.select_all"), "check", self.select_all)
        if any(not x.is_dir for x in self.selected.values()):
            self._bar_button(t("cloud.download"), "download", self.download_selected)
        if self._can_move():
            self._bar_button(t("cloud.move"), "folder-input", lambda: self.move_items(dict(self.selected)))
        if self._can_rename():
            self._bar_button(t("cloud.rename"), "file-text", lambda: RenameDialog(self, dict(self.selected)))
        if self._can_sync():  # 잠긴 폴더도 폴더째로(§19.6). 잠긴 폴더 안의 것은 허브가 이유와 함께 거부한다
            off = all(x.sync for x in self.selected.values())
            self._bar_button(t("sync.remove") if off else t("sync.add"), "monitor-smartphone",
                             lambda: self.sync_items(dict(self.selected), not off))
        if not self._offline():
            self._bar_button(t("cloud.delete"), "trash-2", self.delete_selected, danger=True)
        if not self.select_bar.winfo_ismapped():
            self.select_bar.pack(fill="x", padx=18, pady=(0, 6), before=self.list._parent_frame)

    def download_selected(self) -> None:
        files = [(item, full) for full, item in self.selected.items() if not item.is_dir]
        if self.source != LOCAL and not self._offline():
            for item, full in files:
                self.node.outbox.add_cloud_download(self.source, full, item.size, item.mtime)
            self.app.flash(t("cloud.queued_down_many", count=len(files)))
            self.clear_selection()
            return
        wanted = [full for _, full in files]
        if wanted:  # 이 PC의 저장소는 암호문뿐: 저장 폴더로 풀어 꺼낸다
            src, dest = self._src(), self.node.save_dir

            def exported(paths, error) -> None:
                if error is not None:
                    messagebox.showerror(t("cloud.title"), str(error), parent=self)
                    return
                self.app.flash(t("vault.exported"))
                self._show_local(paths[-1])

            self._bg(lambda: [src.export(full, dest) for full in wanted], exported)
        self.clear_selection()

    def _report(self, done: int, failed: list[tuple[str, str]], key: str) -> None:
        """여러 개를 한 뒤: 다 되면 짧은 알림, 안 된 것이 있으면 이유를 몇 개 보인다."""
        if not failed:
            self.app.flash(t(key, count=done))
            return
        lines = [f"{path.rsplit('/', 1)[-1]}: {reason}" for path, reason in failed[:6]]
        if len(failed) > 6:
            lines.append("…")
        messagebox.showwarning(t("cloud.title"), t("cloud.some_failed", done=done, failed=len(failed)) + "\n\n" +
                               "\n".join(lines), parent=self)

    def delete_selected(self) -> None:
        chosen = list(self.selected.items())
        src = self._src()
        if any(item.locked and not src.is_open(full) for full, item in chosen):
            messagebox.showinfo(t("cloud.title"), t("vault.open_first"), parent=self)
            return
        key = "cloud.delete_many_confirm_bin" if self._to_bin(any(x.locked for _, x in chosen)) \
            else "cloud.delete_many_confirm"
        if not messagebox.askyesno(t("cloud.title"), t(key, count=len(chosen), where=self._source_name(self.source)),
                                   parent=self):
            return

        def work() -> list[tuple[str, str]]:
            failed = []
            for full, _ in chosen:
                try:
                    src.delete(full)
                except Exception as exc:  # noqa: BLE001 - 하나가 안 돼도 나머지는 지운다
                    failed.append((full, str(exc)))
            return failed

        def done(failed, error) -> None:
            failed = failed if error is None else [(full, str(error)) for full, _ in chosen]
            self._report(len(chosen) - len(failed), failed, "cloud.deleted_many")
            self.selected.clear()
            self.reload()

        self._bg(work, done)

    def move_items(self, chosen: dict[str, cloud.Item]) -> None:
        if not chosen:
            return
        FolderPicker(self, self._src(), list(self.path), set(chosen), lambda dest: self._move(list(chosen), dest))

    def _move(self, paths: list[str], dest: str) -> None:
        src = self._src()

        def done(result, error) -> None:
            if error is not None:
                messagebox.showerror(t("cloud.title"), str(error), parent=self)
            else:
                moved, failed = result
                self._report(len(moved), [(x["path"], str(p.ProtocolError(x["code"]))) for x in failed], "cloud.moved")
            self.selected.clear()
            self.anchor = None
            self.reload()

        self._bg(lambda: src.move(paths, dest), done)

    # ---------- 모든 기기 동기화(§19) ----------
    def sync_items(self, chosen: dict[str, cloud.Item], on: bool) -> None:
        """고른 항목을 모든 기기에 동기화하거나 뺀다(잠긴 폴더는 허브가 거부한다)."""
        paths = list(chosen)
        if not paths:
            return
        src = self._src()

        def done(result, error) -> None:
            if error is not None:
                messagebox.showerror(t("cloud.title"), str(error), parent=self)
            else:
                changed, failed = result
                self._report(len(paths) - len(failed), [(x["path"], str(p.ProtocolError(x["code"]))) for x in failed],
                             "sync.added" if on else "sync.removed")
            self.selected.clear()
            self.anchor = None
            self.reload()

        self._bg(lambda: src.sync_set(paths, on), done)

    def _sync_all_now(self) -> bool:
        if self.source == LOCAL:
            st = self.node.store()
            return st is not None and st.state == "open" and st.sync_all
        return self.sync_all.get(self.source, False)

    def sync_menu(self) -> None:
        menu = tk.Menu(self, tearoff=0)
        self._menu_var = tk.BooleanVar(value=self._sync_all_now())
        menu.add_checkbutton(label=t("sync.all_menu"), variable=self._menu_var,
                             command=lambda: self.set_sync_all(bool(self._menu_var.get())))
        menu.add_separator()
        menu.add_command(label=t("sync.how"), state="disabled")
        if self.source != LOCAL and self.node.syncer.enabled:
            menu.add_command(label=t("sync.now"), command=lambda: self.node.syncer.poke(self.source))
        syncer = self.node.syncer
        if self.source != LOCAL and not syncer.device.exists:
            menu.add_command(label=t("sync.copy_set_title") + "…", command=lambda: ask_copy_password(self))
        elif self.source != LOCAL and not syncer.device.locked:
            menu.add_command(label=t("sync.copy_lock"), command=syncer.lock)
        menu.add_command(label=t("sync.settings"), command=self.app.open_settings)
        menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    def set_sync_all(self, on: bool) -> None:
        src = self._src()

        def done(_r, error) -> None:
            if error is not None:
                messagebox.showerror(t("cloud.title"), str(error), parent=self)
                return
            self.sync_all[self.source] = on
            self.app.flash(t("sync.all_on") if on else t("sync.all_off"))
            self.reload()

        self._bg(lambda: src.sync_all(on), done)

    def _offline_banner(self) -> None:
        box = ctk.CTkFrame(self.list, fg_color=BUBBLE, corner_radius=8)
        box.pack(fill="x", padx=6, pady=(4, 8))
        ctk.CTkLabel(box, text=" " + t("sync.offline_banner", name=self._source_name(self.source)),
                     image=icon("monitor-smartphone", 15, SECONDARY), compound="left", font=font(12),
                     text_color=SECONDARY, anchor="w", justify="left", wraplength=560).pack(side="left", padx=12,
                                                                                            pady=8)
        if self.states.get(self.source) == "store" and "store_unlock" in self.caps.get(self.source, frozenset()):
            ctk.CTkButton(box, text=t("store.ok_unlock"), command=self.unlock_remote_store, height=28,
                          corner_radius=6, fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT,
                          font=font(12, "bold"), width=80).pack(side="right", padx=10)

    def refresh_sync_status(self) -> None:
        """아래 줄: 이 허브의 동기화 대상 수(이 PC가 허브면) 또는 이 PC 사본의 상태."""
        text = self._sync_text()
        self.sync_label.configure(text=(" " + text) if text else "")
        if text and not self.sync_label.winfo_ismapped():
            self.sync_label.pack(fill="x", before=self.transfer_label)
        elif not text:
            self.sync_label.pack_forget()

    def _sync_text(self) -> str:
        if self.source == LOCAL:
            st = self.node.store()
            if st is None or st.state != "open":
                return ""
            try:
                _, items = st.sync_manifest()
            except p.ProtocolError:
                return ""
            files = [x for x in items if not x["dir"]]
            return t("sync.hub_line", count=len(files), size=human_size(sum(x["size"] for x in files))) if files \
                else ""
        if not self.node.syncer.enabled:
            return ""
        found = self.node.syncer.mirror(self.source)
        status = self.node.syncer.status.get(self.source, {})
        state = status.get("state")
        if state == "setup":
            return t("sync.need_setup")
        if state == "syncing":
            total = status.get("total") or 0
            pct = int(status.get("done", 0) * 100 / total) if total else 0
            return t("sync.syncing", pct=pct)
        if found is None:
            return ""
        count, size = found.usage()
        line = t("sync.copy_line", count=count, size=human_size(size))
        if state == "space":
            return line + " · " + t("err.sync_space")
        if state == "error":
            return line + " · " + str(p.ProtocolError(status.get("code") or "peer_refused"))
        if found.synced_at:
            line += " · " + t("sync.synced_at", when=dt.datetime.fromtimestamp(found.synced_at).strftime("%m-%d %H:%M"))
        return line + " · " + (t("sync.copy_is_locked") if found.locked else t("sync.copy_is_open"))

    def on_sync_changed(self, data: dict) -> None:
        """사본이 바뀌었다(맞춤·지움): 상태 줄을 고치고, 사본을 보는 중이면 다시 읽는다."""
        if not self.winfo_exists():
            return
        if data.get("locked"):  # 사본을 다시 잠갔다: 사본에서 연 그림·영상을 닫는다
            self.close_viewers()
        if data.get("peer_id") not in (self.source, ""):
            return
        self.refresh_sync_status()
        self.render_sources()
        if self._offline() and self.node.syncer.status.get(self.source, {}).get("state") != "syncing":
            self.reload()

    def _locked_text(self, item: cloud.Item) -> str:
        if not item.locked:
            return ""
        if item.state == "locking":
            return t("vault.locking", pct=item.progress)
        if item.state == "unlocking":
            return t("vault.unlocking", pct=item.progress)
        if item.state == "stalled":
            return t("vault.stalled")
        return t("vault.locked")

    # ---------- 미리보기 ----------
    def _preview(self, parent, item: cloud.Item, full: str, size: int) -> ctk.CTkLabel:
        """사진·영상이면 허브가 만든 썸네일, 아니면 아이콘. 썸네일은 작업 스레드에서 받아 오면 채운다."""
        kind = "" if item.is_dir else cloud.file_kind(item.name)
        holder = ctk.CTkFrame(parent, fg_color=BUBBLE, corner_radius=8, width=size, height=size)
        holder.pack_propagate(False)
        label = ctk.CTkLabel(holder, text="", image=icon(self._item_icon(item), max(16, size // 3), SECONDARY))
        label.place(relx=0.5, rely=0.5, anchor="center")
        if kind in PREVIEW_KINDS and self._has_cloud2() and not item.locked:
            px = 128 if size <= 48 else 256
            key = (self.source, full, item.mtime, px)
            cached = self.thumbs.get(key)
            if cached is not None:
                self._show_thumb(label, cached, size)
            else:
                first = key not in self.thumb_waiting
                self.thumb_waiting.setdefault(key, []).append((label, size))
                if first:
                    self._fetch_thumb(key, full, px, item)
        return holder

    def _fetch_thumb(self, key: tuple, full: str, px: int, item: cloud.Item | None = None) -> None:
        src = self._src()

        def work() -> None:
            try:
                image = src.thumb(full, px, item)
            except Exception:  # noqa: BLE001 - 만들 수 없으면 아이콘 그대로
                image = None
            self.app.events.put(("ui_call", {"fn": lambda: self.winfo_exists() and self._thumb_ready(key, image)}))

        self.pool.submit(work)

    def _thumb_ready(self, key: tuple, image) -> None:
        waiting = self.thumb_waiting.pop(key, [])
        if image is None:
            return
        picture = ctk.CTkImage(light_image=image, dark_image=image, size=image.size)
        self.thumbs[key] = picture
        while len(self.thumbs) > THUMB_CACHE:
            self.thumbs.popitem(last=False)
        for label, size in waiting:
            if label.winfo_exists():
                self._show_thumb(label, picture, size)

    @staticmethod
    def _show_thumb(label: ctk.CTkLabel, picture: ctk.CTkImage, size: int) -> None:
        w, h = picture.cget("size")
        scale = min(size / max(1, w), size / max(1, h))
        label.configure(image=ctk.CTkImage(light_image=picture.cget("light_image"),
                                           dark_image=picture.cget("dark_image"),
                                           size=(max(1, int(w * scale)), max(1, int(h * scale)))))

    # ---------- 동작 ----------
    def activate(self, item: cloud.Item, full: str) -> None:
        if item.locked:
            if item.state != "locked":
                return  # 잠그거나 푸는 중
            if self._src().is_open(full):
                self._jump(full.split("/"))
            else:
                self.ask_open(item.name, full)
            return
        if item.is_dir:
            self._jump(full.split("/"))
        elif cloud.file_kind(item.name) in PREVIEW_KINDS and self._has_cloud2():
            self.open_viewer(full)
        else:
            self.download(item, full)

    # ---------- 라이트박스 ----------
    def close_viewers(self) -> None:
        """사진 보기와 영상 재생 창을 닫는다(잠글 때·창을 닫을 때, 풀어 둔 내용을 메모리에 두지 않게)."""
        for win in (self.viewer, self.video):
            if win is not None and win.winfo_exists():
                win.close()
        self.viewer = self.video = None

    def _details(self, item: cloud.Item, full: str) -> list[tuple[str, str]]:
        when = dt.datetime.fromtimestamp(item.mtime / 1000).strftime("%Y-%m-%d %H:%M") if item.mtime else ""
        folder = "/".join(full.split("/")[:-1]) or "/"
        rows = [(t("lightbox.info_size"), human_size(item.size)), (t("lightbox.info_modified"), when),
                (t("lightbox.info_where"), f"{self._source_name(self.source)} · {folder}")]
        return [row for row in rows if row[1]]

    def open_viewer(self, start: str) -> None:
        """지금 목록의 사진·영상을 라이트박스로. 받기·지우기·취소, 옆으로 넘기기."""
        src = self._src()
        pairs = [(item, full) for item, full, _ in self._entries()
                 if not item.is_dir and cloud.file_kind(item.name) in PREVIEW_KINDS]
        items = {full: item for item, full in pairs}
        shots = [lightbox.Shot(full, item.name, self._cached_thumb(item, full), self._loader(src, item, full),
                               video=cloud.file_kind(item.name) == "video",
                               hires=self._hires(src, item, full) if "screen" in item.variants else None,
                               details=self._details(item, full))
                 for item, full in pairs]
        index = next((i for i, shot in enumerate(shots) if shot.key == start), 0)
        actions = [lightbox.Action(t("cloud.download"), "download", lambda s: self.download(items[s.key], s.key),
                                   "primary"),
                   lightbox.Action(t("cloud.delete"), "trash-2", lambda s: self._viewer_delete(items[s.key], s.key),
                                   "danger", key="delete")]
        if any(cloud.file_kind(item.name) == "video" for item, _ in pairs) and player.mpv_path() is not None:
            actions.insert(0, lightbox.Action(t("lightbox.play"), "play", lambda s: self._play(src, s.key),
                                              key="play", video_only=True))
        if self.viewer is not None and self.viewer.winfo_exists():
            self.viewer.close()
        if self._offline():  # 사본은 지울 수 없다
            actions = [a for a in actions if a.key != "delete"]
        self.viewer = lightbox.Lightbox(self, shots, index, actions, self._post, self.app.icon_path)

    def _cached_thumb(self, item: cloud.Item, full: str):
        for px in (256, 128):
            cached = self.thumbs.get((self.source, full, item.mtime, px))
            if cached is not None:
                return cached.cget("light_image")
        return None

    @staticmethod
    def _loader(src: Source, item: cloud.Item, full: str):
        video = cloud.file_kind(item.name) == "video"

        def load():
            if video:
                if "poster" in item.variants:  # 허브가 만든 첫 장면(긴 변 1280)
                    return lightbox.decode(src.read_variant(full, "poster", 8 << 20))
                return src.thumb(full, BIG_PX, item)
            if src.mirror() is not None and src._copy(full, item) is not None:
                return lightbox.decode(src.read_bytes(full, FULL_LIMIT, item))  # 이 PC 사본의 원본(망을 쓰지 않는다)
            if "screen" in item.variants:  # 화면용(긴 변 2048): 원본보다 훨씬 작아 빨리 열린다
                return lightbox.decode(src.read_variant(full, "screen", 16 << 20))
            try:
                return lightbox.decode(src.read_bytes(full, FULL_LIMIT, item))
            except p.ProtocolError as exc:
                if exc.code != "too_large":
                    raise
            except (OSError, ValueError):
                pass  # Pillow가 못 푸는 형식(HEIC 등)이나 너무 큰 그림: 허브의 큰 미리보기
            return src.thumb(full, BIG_PX, item)

        return load

    @staticmethod
    def _hires(src: Source, item: cloud.Item, full: str):
        return lambda: lightbox.decode(src.read_bytes(full, FULL_LIMIT, item))

    def _play(self, src: Source, start: str) -> None:
        """영상 재생(앱 안 재생기): 이 PC 안에서만 열리는 주소로 그때그때 복호화해 흘린다(재생용 720p가 있으면 그것을).
        지금 목록의 영상끼리 이전·다음으로 넘긴다."""
        pairs = [(item, full) for item, full, _ in self._entries()
                 if not item.is_dir and cloud.file_kind(item.name) == "video"]
        index = next((i for i, (_item, full) in enumerate(pairs) if full == start), None)
        if index is None:
            return
        if self.player is None:
            self.player = media.RangeServer()
        settings = self.app.node.settings
        clips = []
        for item, full in pairs:
            secret = item.locked or src.is_open(full)  # 잠긴 폴더 안은 이어 보기도 남기지 않는다
            key = "" if secret else player.resume_key(settings, f"{self.source}|{full}|{item.size}")
            clips.append(player.Clip(key, item.name, self._clip_opener(src, item, full)))
        if self.video is not None and self.video.winfo_exists():
            self.video.close()
        self.video = player.VideoWindow(self, clips, index, self._post, settings, self.app.node.save_settings,
                                        self.app.icon_path)

    def _clip_opener(self, src: Source, item: cloud.Item, full: str):
        role = "play" if "play" in item.variants else None

        def open_clip() -> tuple[str, object]:
            server = self.player
            url, key = server.register(src.stream(full, role, item))
            return url, lambda: server.forget(key)

        return open_clip

    def _viewer_delete(self, item: cloud.Item, full: str) -> None:
        box = self.viewer
        if item.locked and not self._src().is_open(full):
            return
        key = "cloud.delete_confirm_bin" if self._to_bin(item.locked or self._src().is_open(full)) \
            else "cloud.delete_confirm"
        if not messagebox.askyesno(t("cloud.title"), t(key, name=item.name, where=self._source_name(self.source)),
                                   parent=box):
            return
        src = self._src()

        def done(_r, error) -> None:
            if error is not None:
                messagebox.showerror(t("cloud.title"), str(error), parent=box if box.winfo_exists() else self)
                return
            if box is not None and box.winfo_exists():
                box.remove_current()
            self.selected.pop(full, None)
            self.reload()

        self._bg(lambda: src.delete(full), done)

    def item_menu(self, item: cloud.Item, full: str) -> None:
        menu = tk.Menu(self, tearoff=0)
        menu.add_command(label=t("cloud.select"), command=lambda: self.toggle(item, full))
        menu.add_separator()
        if item.is_dir and not item.locked:
            menu.add_command(label=t("cloud.open"), command=lambda: self.activate(item, full))
            if self._has_cloud2():
                menu.add_command(label=t("vault.lock_menu"), command=lambda: self.ask_lock(item.name, full))
        if item.locked and item.state == "stalled":
            menu.add_command(label=t("vault.resume_menu"), command=lambda: self.ask_open(item.name, full))
            menu.add_command(label=t("vault.unlock_menu"), command=lambda: self.ask_unlock(item.name, full))
        if item.locked and item.state == "locked":
            menu.add_command(label=t("cloud.open"), command=lambda: self.activate(item, full))
            menu.add_command(label=t("vault.unlock_menu"), command=lambda: self.ask_unlock(item.name, full))
            menu.add_command(label=t("vault.repin_menu"), command=lambda: self.ask_repin(item.name, full))
        if not item.is_dir:
            menu.add_command(label=t("cloud.download"), command=lambda: self.download(item, full))
        if self._can_sync() and (not item.locked or item.state == "locked"):
            menu.add_command(label=t("sync.remove") if item.sync else t("sync.add"),
                             command=lambda: self.sync_items({full: item}, not item.sync))
        if self._offline():
            menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())
            return
        if self._can_move() and not (item.locked and item.is_dir):
            menu.add_command(label=t("cloud.move_menu"), command=lambda: self.move_items({full: item}))
        if self._can_rename():
            menu.add_command(label=t("cloud.rename_menu"), command=lambda: RenameDialog(self, {full: item}))
        menu.add_separator()
        menu.add_command(label=t("cloud.delete"), command=lambda: self.delete(item, full))
        menu.tk_popup(self.winfo_pointerx(), self.winfo_pointery())

    def ask_open(self, name: str, full: str | None = None) -> None:
        full = full or cloud.join_path([*self.path, name])
        src = self._src()

        def done(dialog, values) -> None:
            def work():
                src.open(full, values.get("pin"), values.get("recovery"))

            def after(_r, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                self._jump(full.split("/"))

            self._bg(work, after)

        PinDialog(self, "open", name, done)

    def ask_lock(self, name: str, full: str) -> None:
        src = self._src()

        def done(dialog, values) -> None:
            def after(code, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                show_recovery(self, code)
                self.reload()

            self._bg(lambda: src.lock(full, values["new_pin"]), after)

        PinDialog(self, "lock", name, done)

    def ask_unlock(self, name: str, full: str) -> None:
        src = self._src()

        def done(dialog, values) -> None:
            def after(_r, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                self.reload()

            self._bg(lambda: src.unlock(full, values.get("pin", "")), after)

        PinDialog(self, "unlock", name, done)

    def ask_repin(self, name: str, full: str) -> None:
        src = self._src()

        def done(dialog, values) -> None:
            def after(_r, error) -> None:
                if error is not None:
                    dialog.fail(str(error))
                    return
                dialog.destroy()
                self.app.flash(t("vault.repinned"))

            self._bg(lambda: src.repin(full, values["new_pin"], values.get("pin"), values.get("recovery")), after)

        PinDialog(self, "repin", name, done)

    def download(self, item: cloud.Item, full: str) -> None:
        if self.source != LOCAL and not self._offline():
            self.node.outbox.add_cloud_download(self.source, full, item.size, item.mtime)
            self.app.flash(t("cloud.queued_down", name=item.name))
            return
        src = self._src()  # 이 PC의 저장소는 암호문뿐: 저장 폴더로 풀어 꺼낸다(복호화한 사본)
        def exported(path, error) -> None:
            if error is not None:
                messagebox.showerror(t("cloud.title"), str(error), parent=self)
                return
            self.app.flash(t("vault.exported"))  # 꺼낸 것은 평문 사본이다
            self._show_local(path)

        self._bg(lambda: src.export(full, self.node.save_dir), exported)

    def _to_bin(self, secret: bool) -> bool:
        """PC 허브의 평범한 파일은 휴지통으로 간다(잠긴 폴더 안은 영구히)."""
        pairing = self.node.pairings.get(self.source)
        return (self.source == LOCAL or (pairing is not None and pairing.kind == "pc")) and not secret

    def delete(self, item: cloud.Item, full: str) -> None:
        where = self._source_name(self.source)
        key = "cloud.delete_confirm_bin" if self._to_bin(item.locked or self._src().is_open(full)) \
            else "cloud.delete_confirm"
        if not messagebox.askyesno(t("cloud.title"), t(key, name=item.name, where=where), parent=self):
            return
        if item.locked and not self._src().is_open(full):
            messagebox.showinfo(t("cloud.title"), t("vault.open_first"), parent=self)
            return
        src = self._src()
        self._bg(lambda: src.delete(full), self._after_change)

    def new_folder(self) -> None:
        dialog = ctk.CTkInputDialog(text=t("cloud.new_folder_prompt"), title=t("cloud.new_folder"),
                                    font=font(13), button_fg_color=INK, button_hover_color=INK_HOVER,
                                    button_text_color=INK_TEXT)
        name = (dialog.get_input() or "").strip()
        if not name:
            return
        if p.sanitize_filename(name) != name:
            messagebox.showwarning(t("cloud.title"), t("cloud.bad_name"), parent=self)
            return
        src, full = self._src(), cloud.join_path([*self.path, name])
        self._bg(lambda: src.mkdir(full), self._after_change)

    def _after_change(self, _result, error) -> None:
        if error is not None:
            messagebox.showerror(t("cloud.title"), str(error), parent=self)
        self.reload()

    def pick_upload(self) -> None:
        chosen = filedialog.askopenfilenames(title=t("cloud.upload_title"), parent=self)
        if chosen:
            self.upload([Path(x) for x in chosen])

    def _on_drop(self, event) -> str:
        self.list.configure(fg_color=MAIN_BG)
        if self._browsable():
            self.upload([Path(x) for x in self.tk.splitlist(event.data)])
        return event.action

    def upload(self, paths: list[Path]) -> None:
        paths = [x for x in paths if x.is_file() or x.is_dir()]
        if not paths:
            return
        src, folder = self._src(), cloud.join_path(self.path)
        if self.source == LOCAL:
            self.app.flash(t("cloud.copying"))
            self._bg(lambda: src.upload(paths, folder), self._after_change)
            return
        count = src.upload(paths, folder)
        self.app.flash(t("cloud.queued_up", count=count))

    def open_local_folder(self) -> None:
        """이 PC 클라우드의 저장 위치를 탐색기로 연다(보이는 것은 .tailhop-store의 암호문뿐이다)."""
        folder = self.node.cloud_dir
        if folder.is_dir():
            os.startfile(str(folder))  # noqa: S606 - 폴더만 연다

    @staticmethod
    def _show_local(path: Path) -> None:
        cmd = store.explorer_select_command(path)
        if cmd is not None:
            subprocess.Popen(cmd)

    # ---------- 전송 ----------
    def on_outbox(self, data: dict) -> None:
        """앱이 넘겨주는 보낼 곳 목록 알림. 지금 보는 기기로 올리기가 끝나면 목록을 다시 읽는다."""
        self.update_transfers()
        if data.get("gone") and data.get("peer") == self.source and data.get("kind") == "cloud_put":
            self.reload()

    def update_transfers(self, progress: dict | None = None) -> None:
        jobs = [j for sid in list(self.node.pairings) for j in self.node.outbox.jobs_for(sid) if j.kind in CLOUD_KINDS]
        if not jobs:
            self.transfer_bar.pack_forget()
            self.transfer_label.configure(text="")
            return
        up = sum(1 for j in jobs if j.kind == "cloud_put")
        down = len(jobs) - up
        failed = [j for j in jobs if j.state == outbox.FAILED]
        text = t("cloud.transfers", up=up, down=down)
        color = SECONDARY
        if failed:
            text += " · " + t("cloud.transfers_failed", count=len(failed), error=failed[-1].error)
            color = RED
        elif any(j.state == outbox.WAITING and j.attempts for j in jobs):
            color = AMBER
        self.transfer_label.configure(text=text, text_color=color)
        if progress is not None and progress.get("total"):
            if not self.transfer_bar.winfo_ismapped():
                self.transfer_bar.pack(fill="x", before=self.transfer_label, pady=(0, 4))
            self.transfer_bar.set(progress["done"] / max(1, progress["total"]))


class FolderPicker(ctk.CTkToplevel):
    """옮겨 갈 폴더 고르기(§16). 그 허브의 폴더만 보이고, 옮길 폴더 자신과 그 아래로는 들어가지 못한다."""

    def __init__(self, win: CloudWindow, src: Source, start: list[str], moving: set[str], on_pick) -> None:
        super().__init__(win)
        self.win, self.src, self.path, self.moving, self.on_pick = win, src, list(start), moving, on_pick
        self.loading = 0
        self.title(t("cloud.move_title"))
        fit_geometry(self, 460, 540)
        self.minsize(380, 420)
        self.transient(win)
        self.after(30, lambda: (apply_glass(self), protect(self)))
        box = card(self)
        box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
        ctk.CTkLabel(box, text=t("cloud.move_title"), font=font(15, "bold"), text_color=TEXT,
                     anchor="w").pack(fill="x", padx=20, pady=(16, 0))
        ctk.CTkLabel(box, text=t("cloud.move_hint", count=len(moving)), font=font(12), text_color=SECONDARY,
                     anchor="w").pack(fill="x", padx=20, pady=(2, 8))
        head = ctk.CTkFrame(box, fg_color="transparent")
        head.pack(fill="x", padx=12)
        self.up = ghost_button(head, "chevron-left", self.go_up, size=30)
        self.up.pack(side="left")
        self.where = ctk.CTkLabel(head, text="", font=font(13, "bold"), text_color=TEXT, anchor="w")
        self.where.pack(side="left", fill="x", expand=True, padx=4)
        ghost_button(head, "folder-plus", self.new_folder, size=30).pack(side="right")
        buttons = ctk.CTkFrame(box, fg_color="transparent")
        buttons.pack(fill="x", side="bottom", padx=20, pady=(8, 16))
        self.ok = ctk.CTkButton(buttons, text=t("cloud.move_here"), command=self.pick, height=34, corner_radius=6,
                                fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT, font=font(12, "bold"),
                                width=120)
        self.ok.pack(side="right")
        ctk.CTkButton(buttons, text=t("vault.cancel"), command=self.destroy, height=34, corner_radius=6,
                      fg_color="transparent", hover_color=HOVER, text_color=TEXT, border_width=1, border_color=BORDER,
                      font=font(12), width=80).pack(side="right", padx=8)
        self.list = ctk.CTkScrollableFrame(box, fg_color=MAIN_BG, corner_radius=0, scrollbar_button_color=BORDER,
                                           scrollbar_button_hover_color=SECONDARY)
        self.list.pack(fill="both", expand=True, padx=8, pady=(4, 0))
        self.bind("<Escape>", lambda _e: self.destroy())
        self.grab_set()
        self.load()

    def _blocked(self, full: str) -> bool:
        return any(full == x or full.startswith(x + "/") for x in self.moving)

    def load(self) -> None:
        self.loading += 1
        token, path = self.loading, cloud.join_path(self.path)
        name = self.path[-1] if self.path else t("cloud.root_of", name=self.win._source_name(self.win.source))
        self.where.configure(text=name if len(name) <= 34 else name[:33] + "…")
        self.up.configure(state="normal" if self.path else "disabled")
        self.ok.configure(state="disabled")
        for child in self.list.winfo_children():
            child.destroy()
        ctk.CTkLabel(self.list, text=t("cloud.loading"), font=font(12), text_color=SECONDARY).pack(pady=40)
        self.win._bg(lambda: self.src.list(path, 0), lambda r, e: self._loaded(token, r, e))

    def _loaded(self, token: int, result, error) -> None:
        if token != self.loading or not self.winfo_exists():
            return
        for child in self.list.winfo_children():
            child.destroy()
        if error is not None:
            ctk.CTkLabel(self.list, text=str(error), font=font(12), text_color=RED, wraplength=360).pack(pady=40)
            return
        self.ok.configure(state="normal")
        folders = [x for x in result[0] if x.is_dir]
        if not folders:
            ctk.CTkLabel(self.list, text=t("cloud.no_folders"), font=font(12), text_color=SECONDARY).pack(pady=40)
        for item in folders:
            full = cloud.join_path([*self.path, item.name])
            blocked = self._blocked(full) or (item.locked and item.state != "locked")
            row = ctk.CTkFrame(self.list, fg_color="transparent", corner_radius=6, height=40,
                               cursor="" if blocked else "hand2")
            row.pack(fill="x", padx=2, pady=1)
            row.pack_propagate(False)
            color = SECONDARY if blocked else TEXT
            ctk.CTkLabel(row, text="", image=icon("lock" if item.locked else "folder", 18, color)).pack(side="left",
                                                                                                    padx=(10, 8))
            ctk.CTkLabel(row, text=item.name, font=font(13), text_color=color, anchor="w").pack(side="left", fill="x",
                                                                                               expand=True)
            if blocked:
                continue
            ctk.CTkLabel(row, text="", image=icon("chevron-right", 16, SECONDARY)).pack(side="right", padx=8)
            bind_tree(row, "<Enter>", lambda _e, r=row: r.configure(fg_color=HOVER))
            bind_tree(row, "<Leave>", lambda _e, r=row: r.configure(fg_color="transparent"))
            bind_tree(row, "<Button-1>", lambda _e, it=item, f=full: self.enter(it, f))

    def enter(self, item: cloud.Item, full: str) -> None:
        if item.locked and not self.src.is_open(full):
            def done(dialog, values) -> None:
                def after(_r, error) -> None:
                    if error is not None:
                        dialog.fail(str(error))
                        return
                    dialog.destroy()
                    self.grab_set()
                    self.path = full.split("/")
                    self.load()

                self.win._bg(lambda: self.src.open(full, values.get("pin"), values.get("recovery")), after)

            PinDialog(self, "open", item.name, done)
            return
        self.path = full.split("/")
        self.load()

    def go_up(self) -> None:
        if self.path:
            self.path = self.path[:-1]
            self.load()

    def new_folder(self) -> None:
        dialog = ctk.CTkInputDialog(text=t("cloud.new_folder_prompt"), title=t("cloud.new_folder"), font=font(13),
                                    button_fg_color=INK, button_hover_color=INK_HOVER, button_text_color=INK_TEXT)
        name = (dialog.get_input() or "").strip()
        self.grab_set()
        if not name:
            return
        if p.sanitize_filename(name) != name:
            messagebox.showwarning(t("cloud.title"), t("cloud.bad_name"), parent=self)
            return
        full = cloud.join_path([*self.path, name])

        def after(_r, error) -> None:
            if error is not None:
                messagebox.showerror(t("cloud.title"), str(error), parent=self)
                return
            self.path = full.split("/")
            self.load()

        self.win._bg(lambda: self.src.mkdir(full), after)

    def pick(self) -> None:
        dest = cloud.join_path(self.path)
        self.destroy()
        self.on_pick(dest)


class RenameDialog(ctk.CTkToplevel):
    """이름 바꾸기(§18.2). 하나면 이름 칸, 여럿이면 방식(찾아 바꾸기·앞뒤 붙이기·번호·대소문자)과 미리보기."""

    def __init__(self, win: CloudWindow, chosen: dict[str, cloud.Item]) -> None:
        super().__init__(win)
        self.win, self.src = win, win._src()
        self.paths = list(chosen)
        self.items = [chosen[x] for x in self.paths]
        self.single = len(self.paths) == 1
        self.title(t("cloud.rename"))
        fit_geometry(self, 520, 200) if self.single else fit_geometry(self, 620, 600)
        self.minsize(460, 200 if self.single else 460)
        self.transient(win)
        self.after(30, lambda: (apply_glass(self), protect(self)))
        box = card(self)
        box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
        ctk.CTkLabel(box, text=t("cloud.rename_title", count=len(self.paths)), font=font(15, "bold"), text_color=TEXT,
                     anchor="w").pack(fill="x", padx=20, pady=(16, 6))
        self.fields = ctk.CTkFrame(box, fg_color="transparent")
        self.fields.pack(fill="x", padx=20)
        self.mode = "replace"
        self.a = self._entry(self.fields, "")
        self.b = self._entry(self.fields, "")
        self.number_row = ctk.CTkFrame(self.fields, fg_color="transparent")
        ctk.CTkLabel(self.number_row, text=t("cloud.rename_start"), font=font(12), text_color=SECONDARY).pack(side="left")
        self.start = self._entry(self.number_row, "1")
        self.start.configure(width=70)
        self.start.pack(side="left", padx=(6, 16))
        ctk.CTkLabel(self.number_row, text=t("cloud.rename_digits"), font=font(12), text_color=SECONDARY).pack(
            side="left")
        self.digits = self._entry(self.number_row, "2")
        self.digits.configure(width=60)
        self.digits.pack(side="left", padx=6)
        self.case = "lower"
        buttons = ctk.CTkFrame(box, fg_color="transparent")
        buttons.pack(fill="x", side="bottom", padx=20, pady=(4, 16))
        self.ok = ctk.CTkButton(buttons, text=t("cloud.rename_ok"), command=self.submit, height=34, corner_radius=6,
                                fg_color=INK, hover_color=INK_HOVER, text_color=INK_TEXT, font=font(12, "bold"),
                                width=110)
        self.ok.pack(side="right")
        ctk.CTkButton(buttons, text=t("vault.cancel"), command=self.destroy, height=34, corner_radius=6,
                      fg_color="transparent", hover_color=HOVER, text_color=TEXT, border_width=1, border_color=BORDER,
                      font=font(12), width=80).pack(side="right", padx=8)
        self.note = ctk.CTkLabel(buttons, text="", font=font(11), text_color=SECONDARY, anchor="w")
        self.note.pack(side="left")
        if self.single:
            self.a.pack(fill="x", pady=4)
            self.a.insert(0, self.items[0].name)
            stem, _ = rename_plan.split_ext(self.items[0].name, self.items[0].is_dir)
            self.a.focus_set()
            self.after(60, lambda: self.a._entry.select_range(0, len(stem)))
        else:
            labels = [t(RENAME_MODES[m]) for m in rename_plan.MODES]
            self.tabs = ctk.CTkSegmentedButton(self.fields, values=labels, command=self._mode_changed, height=30,
                                               corner_radius=6, font=font(11), fg_color=BUBBLE, selected_color=MAIN_BG,
                                               selected_hover_color=MAIN_BG, unselected_color=BUBBLE,
                                               unselected_hover_color=HOVER, text_color=TEXT)
            self.tabs.set(labels[0])
            self.tabs.pack(anchor="w", pady=(0, 8))
            self.case_tabs = ctk.CTkSegmentedButton(self.fields, values=[t(RENAME_CASES[c]) for c in rename_plan.CASES],
                                                    command=self._case_changed, height=28, corner_radius=6,
                                                    font=font(11), fg_color=BUBBLE, selected_color=MAIN_BG,
                                                    selected_hover_color=MAIN_BG, unselected_color=BUBBLE,
                                                    unselected_hover_color=HOVER, text_color=TEXT)
            self.case_tabs.set(t(RENAME_CASES["lower"]))
            self.preview = ctk.CTkScrollableFrame(box, fg_color=MAIN_BG, corner_radius=0,
                                                  scrollbar_button_color=BORDER, scrollbar_button_hover_color=SECONDARY)
            self.preview.pack(fill="both", expand=True, padx=12, pady=(8, 0))
            for entry in (self.a, self.b, self.start, self.digits):
                entry.bind("<KeyRelease>", lambda _e: self._refresh())
            self._mode_changed(labels[0])
        self.bind("<Return>", lambda _e: self.submit())
        self.bind("<Escape>", lambda _e: self.destroy())
        self.grab_set()

    @staticmethod
    def _entry(parent, value: str) -> ctk.CTkEntry:
        entry = ctk.CTkEntry(parent, height=32, corner_radius=6, border_width=1, border_color=BORDER, fg_color=MAIN_BG,
                             text_color=TEXT, font=font(13))
        if value:
            entry.insert(0, value)
        return entry

    def _mode_changed(self, label: str) -> None:
        self.mode = next(m for m in rename_plan.MODES if t(RENAME_MODES[m]) == label)
        for widget in (self.a, self.b, self.number_row, self.case_tabs):
            widget.pack_forget()
        hints = {"replace": ("cloud.rename_find", "cloud.rename_with"), "affix": ("cloud.rename_prefix",
                                                                            "cloud.rename_suffix"),
                 "number": ("cloud.rename_pattern", None), "case": (None, None)}[self.mode]
        if hints[0]:
            self.a.configure(placeholder_text=t(hints[0]))
            self.a.pack(fill="x", pady=3)
        if hints[1]:
            self.b.configure(placeholder_text=t(hints[1]))
            self.b.pack(fill="x", pady=3)
        if self.mode == "number":
            self.number_row.pack(anchor="w", pady=3)
        if self.mode == "case":
            self.case_tabs.pack(anchor="w", pady=3)
        self._refresh()

    def _case_changed(self, label: str) -> None:
        self.case = next(c for c in rename_plan.CASES if t(RENAME_CASES[c]) == label)
        self._refresh()

    def _new_names(self) -> list[str]:
        if self.single:
            return [self.a.get().strip()]
        try:
            start, digits = int(self.start.get() or 1), int(self.digits.get() or 2)
        except ValueError:
            start, digits = 1, 2
        return rename_plan.plan([(x.name, x.is_dir) for x in self.items], self.mode, self.a.get(), self.b.get(),
                                start, digits, self.case)

    def _refresh(self) -> None:
        new = self._new_names()
        issues = rename_plan.problems([x.name for x in self.items], new)
        for child in self.preview.winfo_children():
            child.destroy()
        for item, name, issue in list(zip(self.items, new, issues))[:300]:
            row = ctk.CTkFrame(self.preview, fg_color="transparent")
            row.pack(fill="x", padx=4, pady=1)
            ctk.CTkLabel(row, text=item.name, font=font(12), text_color=SECONDARY, anchor="w", width=250).pack(
                side="left")
            ctk.CTkLabel(row, text="", image=icon("chevron-right", 14, SECONDARY), width=18).pack(side="left")
            text = name if issue is None else f"{name}  ({t(RENAME_ISSUES[issue])})"
            ctk.CTkLabel(row, text=text, font=font(12), text_color=TEXT if issue is None else RED,
                         anchor="w").pack(side="left", fill="x", expand=True)
        bad = sum(1 for x in issues if x)
        self.note.configure(text=t("cloud.rename_bad", count=bad) if bad else "")
        self.ok.configure(state="disabled" if bad else "normal")

    def submit(self) -> None:
        new = self._new_names()
        if any(rename_plan.problems([x.name for x in self.items], new)):
            self.note.configure(text=t("err.bad_name"), text_color=RED)
            return
        items = [(path, name) for path, item, name in zip(self.paths, self.items, new) if name != item.name]
        if not items:
            self.destroy()
            return
        self.ok.configure(state="disabled")

        def done(result, error) -> None:
            if not self.winfo_exists():
                return
            if error is not None:
                self.ok.configure(state="normal")
                self.note.configure(text=str(error), text_color=RED)
                return
            renamed, failed = result
            self.destroy()
            self.win._report(len(renamed), [(x["path"], str(p.ProtocolError(x["code"]))) for x in failed],
                             "cloud.renamed")
            self.win.selected.clear()
            self.win.reload()

        self.win._bg(lambda: self.src.rename(items), done)


RENAME_MODES = {"replace": "cloud.rename_mode_replace", "affix": "cloud.rename_mode_affix",
                "number": "cloud.rename_mode_number", "case": "cloud.rename_mode_case"}
RENAME_CASES = {"lower": "cloud.rename_case_lower", "upper": "cloud.rename_case_upper",
                "title": "cloud.rename_case_title"}
RENAME_ISSUES = {"bad_name": "err.bad_name", "exists": "err.exists"}


class TrashWindow(ctk.CTkToplevel):
    """이 PC 저장소의 휴지통(§17.5): 지운 지 30일이 지나면 저절로 비운다. 되돌리기·영구 삭제·비우기."""

    def __init__(self, win: CloudWindow) -> None:
        super().__init__(win)
        self.win = win
        self.title(t("trash.title"))
        fit_geometry(self, 560, 520)
        self.minsize(440, 360)
        self.transient(win)
        self.after(30, lambda: (apply_glass(self), protect(self)))
        box = card(self)
        box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
        head = ctk.CTkFrame(box, fg_color="transparent")
        head.pack(fill="x", padx=20, pady=(16, 0))
        ctk.CTkLabel(head, text=t("trash.title"), font=font(15, "bold"), text_color=TEXT, anchor="w").pack(side="left")
        self.empty_button = ctk.CTkButton(head, text=t("trash.empty"), command=self.empty_all, height=30,
                                          corner_radius=6, fg_color="transparent", hover_color=HOVER, text_color=RED,
                                          border_width=1, border_color=BORDER, font=font(12), width=90)
        self.empty_button.pack(side="right")
        ctk.CTkLabel(box, text=t("trash.hint"), font=font(12), text_color=SECONDARY, anchor="w", justify="left",
                     wraplength=480).pack(fill="x", padx=20, pady=(2, 8))
        self.list = ctk.CTkScrollableFrame(box, fg_color=MAIN_BG, corner_radius=0, scrollbar_button_color=BORDER,
                                           scrollbar_button_hover_color=SECONDARY)
        self.list.pack(fill="both", expand=True, padx=8, pady=(0, 12))
        self.bind("<Escape>", lambda _e: self.destroy())
        self.load()

    def _store(self):
        hub = self.win.node.hub()
        if hub is None:
            raise p.ProtocolError("store_locked")
        return hub.store

    def load(self) -> None:
        self.win._bg(lambda: self._store().trash_items(), self._loaded)

    def _loaded(self, items, error) -> None:
        if not self.winfo_exists():
            return
        for child in self.list.winfo_children():
            child.destroy()
        if error is not None:
            ctk.CTkLabel(self.list, text=str(error), font=font(12), text_color=RED, wraplength=440).pack(pady=40)
            return
        self.empty_button.configure(state="normal" if items else "disabled")
        if not items:
            ctk.CTkLabel(self.list, text=t("trash.none"), font=font(12), text_color=SECONDARY).pack(pady=60)
            return
        for item in sorted(items, key=lambda x: -x["deleted"]):
            row = ctk.CTkFrame(self.list, fg_color="transparent", corner_radius=6, height=46)
            row.pack(fill="x", padx=2, pady=1)
            row.pack_propagate(False)
            name = "lock" if item.get("locked") else "folder" if item["dir"] else "file"
            ctk.CTkLabel(row, text="", image=icon(name, 18, SECONDARY)).pack(side="left", padx=(10, 8))
            texts = ctk.CTkFrame(row, fg_color="transparent")
            texts.pack(side="left", fill="x", expand=True)
            label = item["name"] if len(item["name"]) <= 44 else item["name"][:43] + "…"
            ctk.CTkLabel(texts, text=label, font=font(13), text_color=TEXT, anchor="w", height=18).pack(fill="x",
                                                                                                       pady=(5, 0))
            when = dt.datetime.fromtimestamp(item["deleted"] / 1000).strftime("%Y-%m-%d %H:%M")
            sub = t("trash.deleted_at", when=when) + ("" if item["dir"] else " · " + human_size(item["size"]))
            ctk.CTkLabel(texts, text=sub, font=font(11), text_color=SECONDARY, anchor="w", height=15).pack(fill="x")
            ghost_button(row, "trash-2", lambda i=item: self.purge(i), size=28).pack(side="right", padx=(2, 8))
            ghost_button(row, "rotate-ccw", lambda i=item: self.restore(i), size=28).pack(side="right")

    def restore(self, item: dict) -> None:
        def done(name, error) -> None:
            if error is not None:
                messagebox.showerror(t("trash.title"), str(error), parent=self)
            else:
                self.win.app.flash(t("trash.restored", name=name))
                self.win.reload()
            self.load()

        self.win._bg(lambda: self._store().restore(item["id"]), done)

    def purge(self, item: dict) -> None:
        if not messagebox.askyesno(t("trash.title"), t("trash.purge_confirm", name=item["name"]), parent=self):
            return
        self.win._bg(lambda: self._store().purge_trashed(item["id"]), lambda _r, _e: self.load())

    def empty_all(self) -> None:
        if not messagebox.askyesno(t("trash.title"), t("trash.empty_confirm"), parent=self):
            return
        self.win._bg(lambda: self._store().empty_trash(), lambda _r, _e: self.load())


KIND_LABELS = {"all": "cloud.kind_all", "image": "cloud.kind_image", "video": "cloud.kind_video",
               "audio": "cloud.kind_audio", "doc": "cloud.kind_doc", "archive": "cloud.kind_archive",
               "other": "cloud.kind_other"}


def cloud_folder_dialog(parent) -> str | None:
    chosen = filedialog.askdirectory(title=t("dialog.cloud_folder_title"), parent=parent)
    return chosen or None
