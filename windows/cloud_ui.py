"""클라우드 창(docs/PROTOCOL.md §12, §14, §15): 이 PC와 연결된 기기의 클라우드 폴더를 보고, 가져오고, 올리고, 지운다.

- 왼쪽 카드: 이 PC와 연결된 기기. 기기마다 hello로 클라우드가 켜져 있는지 확인해 보인다.
- 오른쪽 카드: 지금 폴더. 목록/그리드 보기, 사진·영상 미리보기(허브가 만든 썸네일), 이름 검색과 종류 거르기.
  잠긴 폴더는 자물쇠로 보이고 PIN(또는 복구 코드)을 넣어야 열린다. 폴더 메뉴에서 잠그기·잠금 풀기·PIN 바꾸기.
- 올리기·가져오기는 보낼 곳 목록(outbox)에 넣어 끊겨도 이어서 한다(이 PC는 바로 복사). 아래 줄에 진행 상황을 보인다.
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
import outbox
import protocol as p
import store
from cloud_source import LOCAL, Source
from i18n import t
from ui import (AMBER, BORDER, BUBBLE, DROP, GREEN, GUTTER, HOVER, INK, INK_HOVER, INK_TEXT, KIND_ICON, MAIN_BG, MONO,
                RED, SECONDARY, SELECTED, SIDEBAR_BG, TEXT, apply_glass, bind_tree, card, font, ghost_button, human_size,
                icon, round_icon)

RENDER_PAGE = 100  # 한 번에 그리는 항목 수(CTk 위젯이 무거워 나눠 그린다)
CLOUD_KINDS = ("cloud_put", "cloud_get")
STATE_TEXT = {"checking": "cloud.state_checking", "on": "cloud.state_on", "off": "cloud.state_off",
              "old": "cloud.state_old", "unreachable": "cloud.state_unreachable"}
KIND_FILTERS = ("all", "image", "video", "audio", "doc", "archive", "other")
KIND_ICONS = {"image": "image", "video": "video"}
PREVIEW_KINDS = ("image", "video")
THUMB_CACHE = 300  # 화면에 들고 있는 썸네일 수
TILE = 150


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
        self.after(30, lambda: apply_glass(self))
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
        if mode in ("open", "repin"):
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
        if self.mode in ("open", "unlock", "repin"):
            label = t("vault.recovery") if self.use_recovery else t("vault.pin")
            self.current = self._entry(label, secret=not self.use_recovery)
        if self.mode in ("lock", "repin"):
            self.new = self._entry(t("vault.new_pin"))
            self.again = self._entry(t("vault.new_pin_again"))
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
            if not 4 <= len(self.new.get()) <= 64:
                self.error.configure(text=t("vault.pin_rule"))
                return
            values["new_pin"] = self.new.get()
        self.error.configure(text=t("vault.working"))
        self.on_done(self, values)

    def fail(self, message: str) -> None:
        if self.winfo_exists():
            self.error.configure(text=message)


VAULT_TITLES = {"open": "vault.open_title", "lock": "vault.lock_title", "unlock": "vault.unlock_title",
                "repin": "vault.repin_title"}
VAULT_HINTS = {"open": "vault.open_hint", "lock": "vault.lock_hint", "unlock": "vault.unlock_hint",
               "repin": "vault.repin_hint"}


def show_recovery(parent, code: str) -> None:
    """복구 코드를 한 번만 보여 준다(복사 버튼, 다시 볼 수 없다는 경고)."""
    win = ctk.CTkToplevel(parent)
    win.title(t("vault.recovery_title"))
    win.geometry("460x300")
    win.resizable(False, False)
    win.transient(parent)
    win.after(30, lambda: apply_glass(win))
    box = card(win)
    box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
    ctk.CTkLabel(box, text=t("vault.recovery_title"), font=font(15, "bold"), text_color=TEXT,
                 anchor="w").pack(fill="x", padx=20, pady=(16, 0))
    ctk.CTkLabel(box, text=t("vault.recovery_hint"), font=font(12), text_color=AMBER, anchor="w", justify="left",
                 wraplength=400).pack(fill="x", padx=20, pady=(4, 10))
    code_box = ctk.CTkFrame(box, fg_color=BUBBLE, corner_radius=8)
    code_box.pack(fill="x", padx=20)
    ctk.CTkLabel(code_box, text=code, font=font(14, "bold", MONO), text_color=TEXT).pack(padx=12, pady=12)
    row = ctk.CTkFrame(box, fg_color="transparent")
    row.pack(fill="x", side="bottom", padx=20, pady=16)

    def copy() -> None:
        win.clipboard_clear()
        win.clipboard_append(code)
        copy_button.configure(text=t("vault.copied"))

    copy_button = ctk.CTkButton(row, text=t("vault.copy"), command=copy, height=34, corner_radius=6,
                                fg_color="transparent", hover_color=HOVER, text_color=TEXT, border_width=1,
                                border_color=BORDER, font=font(12), width=90)
    copy_button.pack(side="left")
    ctk.CTkButton(row, text=t("vault.saved"), command=win.destroy, height=34, corner_radius=6, fg_color=INK,
                  hover_color=INK_HOVER, text_color=INK_TEXT, font=font(12, "bold"), width=120).pack(side="right")
    win.grab_set()


class CloudWindow(ctk.CTkToplevel):
    def __init__(self, app, source: str | None = None) -> None:
        super().__init__(app, fg_color=MAIN_BG)
        self.app = app
        self.node = app.node
        self.title(t("cloud.title"))
        self.geometry("980x660")
        self.minsize(700, 460)
        self.source = source if source is not None else (LOCAL if self.node.cloud_on else next(iter(self.node.pairings), LOCAL))
        self.path: list[str] = []
        self.states: dict[str, str] = {}  # 기기별 상태: checking | on | off | old | unreachable
        self.state_errors: dict[str, str] = {}
        self.caps: dict[str, frozenset] = {}
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
        self._build()
        self.drop_target_register(DND_FILES)
        self.dnd_bind("<<DropEnter>>", lambda e: (self.list.configure(fg_color=DROP), e.action)[1])
        self.dnd_bind("<<DropLeave>>", lambda e: (self.list.configure(fg_color=MAIN_BG), e.action)[1])
        self.dnd_bind("<<Drop>>", self._on_drop)
        self.after(250, self._set_icon)
        self.after(30, lambda: apply_glass(self))
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.check_sources()
        self.open_source(self.source)

    def close(self) -> None:
        self.pool.shutdown(wait=False, cancel_futures=True)
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
        return Source(self.node, self.source)

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

        bottom = ctk.CTkFrame(main, fg_color="transparent")
        bottom.pack(side="bottom", fill="x", padx=20, pady=(4, 12))
        self.transfer_bar = ctk.CTkProgressBar(bottom, height=3, corner_radius=2, progress_color=INK, fg_color=BORDER)
        self.transfer_label = ctk.CTkLabel(bottom, text="", font=font(11), text_color=SECONDARY, height=18,
                                           anchor="w")
        self.transfer_label.pack(fill="x")

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
        rows = [(LOCAL, "laptop", t("cloud.this_pc"), t("cloud.state_on") if self.node.cloud_on
                 else t("cloud.state_off"))]
        for sid, pairing in list(self.node.pairings.items()):
            state = self.states.get(sid, "checking")
            rows.append((sid, KIND_ICON.get(pairing.kind, "smartphone"), pairing.name, t(STATE_TEXT[state])))
        for sid, icon_name, name, detail in rows:
            selected = sid == self.source
            row = ctk.CTkFrame(self.source_list, fg_color=SELECTED if selected else "transparent", corner_radius=6,
                               height=50, cursor="hand2")
            row.pack(fill="x", padx=6, pady=1)
            row.pack_propagate(False)
            round_icon(row, icon_name, 30).pack(side="left", padx=(8, 10))
            texts = ctk.CTkFrame(row, fg_color="transparent")
            texts.pack(side="left", fill="x", expand=True)
            ctk.CTkLabel(texts, text=name, font=font(13), text_color=TEXT, anchor="w", height=18).pack(fill="x",
                                                                                                    pady=(7, 0))
            on = (sid == LOCAL and self.node.cloud_on) or self.states.get(sid) == "on"
            ctk.CTkLabel(texts, text=detail, font=font(11), text_color=GREEN if on else SECONDARY, anchor="w",
                         height=15).pack(fill="x")
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
                    self.states[s] = "old" if "cloud" not in caps else "on" if info.get("cloud") is True else "off"
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
        return self.source == LOCAL or "cloud2" in self.caps.get(self.source, frozenset())

    def _render_tools(self) -> None:
        for child in self.tools.winfo_children():
            child.destroy()
        if not self._browsable():
            return
        if self.source == LOCAL:
            ghost_button(self.tools, "folder", self.open_local_folder).pack(side="right")
        ghost_button(self.tools, "cloud-upload", self.pick_upload).pack(side="right")
        ghost_button(self.tools, "folder-plus", self.new_folder).pack(side="right")
        ghost_button(self.tools, "refresh-cw", self.reload).pack(side="right")
        ghost_button(self.tools, "list" if self.view == "grid" else "layout-grid", self.toggle_view).pack(side="right")

    def _render_crumbs(self) -> None:
        for child in self.crumbs.winfo_children():
            child.destroy()
        parts = [(t("cloud.root_of", name=self._source_name(self.source)), [])]
        parts += [(name, self.path[:i + 1]) for i, name in enumerate(self.path)]
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
        self.up_button.configure(state="normal" if self.path else "disabled")

    def _jump(self, target: list[str]) -> None:
        self.path = list(target)
        self.reload()

    def _browsable(self) -> bool:
        return (self.source == LOCAL and self.node.cloud_on) or self.states.get(self.source) == "on"

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
        self._render_crumbs()
        self._render_tools()
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
        if self.source == LOCAL and not self.node.cloud_on:
            self._clear_list()
            box = ctk.CTkFrame(self.list, fg_color="transparent")
            box.pack(pady=(90, 0))
            round_icon(box, "cloud", 56, fg=SELECTED, color=SECONDARY).pack()
            ctk.CTkLabel(box, text=t("cloud.local_off"), font=font(13), text_color=SECONDARY, wraplength=480,
                         justify="center").pack(pady=(14, 14))
            ctk.CTkButton(box, text=t("cloud.turn_on"), command=self.app.toggle_cloud_on, fg_color=INK,
                          hover_color=INK_HOVER, text_color=INK_TEXT, corner_radius=6, height=36,
                          font=font(13, "bold")).pack()
            return
        state = self.states.get(self.source, "checking")
        name = self._source_name(self.source)
        if state == "checking":
            self._message(t("cloud.loading"))
        elif state == "unreachable":
            self._message(t("cloud.peer_unreachable", name=name, error=self.state_errors.get(self.source, "")), RED)
        elif state == "old":
            self._message(t("cloud.peer_old", name=name))
        else:
            self._message(t("cloud.peer_off", name=name))

    def render_items(self, partial: bool = False) -> None:
        self._clear_list()
        entries = self._entries()
        if not entries:
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
        badge = self._preview(row, item, full, 36)
        badge.pack(side="left", padx=(8, 10))
        texts = ctk.CTkFrame(row, fg_color="transparent")
        texts.pack(side="left", fill="x", expand=True)
        name = item.name if len(item.name) <= 60 else item.name[:57] + "…"
        label = ctk.CTkLabel(texts, text=name, font=font(13), text_color=TEXT, anchor="w", height=18)
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
        bind_tree(row, "<Enter>", lambda _e, r=row: r.configure(fg_color=HOVER))
        bind_tree(row, "<Leave>", lambda _e, r=row: r.configure(fg_color="transparent"))
        # 줄을 누르면 열기·가져오기. 오른쪽 버튼(가져오기·메뉴)은 따로 동작한다.
        for widget in (row, badge, texts, date, size):
            bind_tree(widget, "<Button-1>", lambda _e, it=item, f=full: self.activate(it, f))
            bind_tree(widget, "<Button-3>", lambda _e, it=item, f=full: self.item_menu(it, f))

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
            ctk.CTkLabel(tile, text=sub, font=font(10), text_color=AMBER if item.locked else SECONDARY,
                         height=14).pack()
            bind_tree(tile, "<Enter>", lambda _e, w=tile: w.configure(fg_color=HOVER))
            bind_tree(tile, "<Leave>", lambda _e, w=tile: w.configure(fg_color="transparent"))
            bind_tree(tile, "<Button-1>", lambda _e, it=item, f=full: self.activate(it, f))
            bind_tree(tile, "<Button-3>", lambda _e, it=item, f=full: self.item_menu(it, f))

    def _locked_text(self, item: cloud.Item) -> str:
        if not item.locked:
            return ""
        if item.state == "locking":
            return t("vault.locking", pct=item.progress)
        if item.state == "unlocking":
            return t("vault.unlocking", pct=item.progress)
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
                    self._fetch_thumb(key, full, px)
        return holder

    def _fetch_thumb(self, key: tuple, full: str, px: int) -> None:
        src = self._src()

        def work() -> None:
            try:
                image = src.thumb(full, px)
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
        else:
            self.download(item, full)

    def item_menu(self, item: cloud.Item, full: str) -> None:
        menu = tk.Menu(self, tearoff=0)
        if item.is_dir and not item.locked:
            menu.add_command(label=t("cloud.open"), command=lambda: self.activate(item, full))
            if self._has_cloud2():
                menu.add_command(label=t("vault.lock_menu"), command=lambda: self.ask_lock(item.name, full))
        if item.locked and item.state == "locked":
            menu.add_command(label=t("cloud.open"), command=lambda: self.activate(item, full))
            menu.add_command(label=t("vault.unlock_menu"), command=lambda: self.ask_unlock(item.name, full))
            menu.add_command(label=t("vault.repin_menu"), command=lambda: self.ask_repin(item.name, full))
        if not item.is_dir:
            menu.add_command(label=t("cloud.download"), command=lambda: self.download(item, full))
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
        if self.source != LOCAL:
            self.node.outbox.add_cloud_download(self.source, full, item.size, item.mtime)
            self.app.flash(t("cloud.queued_down", name=item.name))
            return
        if not self.node.token_for(LOCAL, full):  # 평범한 파일: 탐색기에서 보기
            self._show_local(self.node.cloud_dir.joinpath(*full.split("/")))
            return
        src = self._src()  # 잠긴 폴더 안: 저장 폴더로 풀어 꺼낸다
        self._bg(lambda: src.export(full, self.node.save_dir),
                 lambda path, e: self._show_local(path) if e is None else
                 messagebox.showerror(t("cloud.title"), str(e), parent=self))

    def delete(self, item: cloud.Item, full: str) -> None:
        where = self._source_name(self.source)
        pairing = self.node.pairings.get(self.source)
        to_bin = (self.source == LOCAL or (pairing is not None and pairing.kind == "pc")) and not item.locked \
            and not self._src().is_open(full)
        key = "cloud.delete_confirm_bin" if to_bin else "cloud.delete_confirm"
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
        folder = cloud.CloudFolder(self.node.cloud_dir)
        try:
            target = folder.folder_for_put(list(self.path))
        except p.ProtocolError:
            target = folder.root
        os.startfile(str(target))  # noqa: S606 - 탐색기로 폴더만 연다

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


KIND_LABELS = {"all": "cloud.kind_all", "image": "cloud.kind_image", "video": "cloud.kind_video",
               "audio": "cloud.kind_audio", "doc": "cloud.kind_doc", "archive": "cloud.kind_archive",
               "other": "cloud.kind_other"}


def cloud_folder_dialog(parent) -> str | None:
    chosen = filedialog.askdirectory(title=t("dialog.cloud_folder_title"), parent=parent)
    return chosen or None
