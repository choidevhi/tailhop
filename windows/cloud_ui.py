"""클라우드 창(1.8.0, docs/PROTOCOL.md §12): 이 PC와 연결된 기기의 클라우드 폴더를 보고, 가져오고, 올리고, 지운다.

- 왼쪽: 이 PC와 연결된 기기. 기기마다 hello로 클라우드가 켜져 있는지 확인해 보인다.
- 오른쪽: 지금 폴더의 목록(폴더 먼저). 폴더를 누르면 들어가고, 파일은 가져오기(다른 기기) 또는 탐색기에서 보기(이 PC).
  창에 파일·폴더를 끌어다 놓으면 지금 폴더로 올린다(이 PC면 복사).
- 올리기·가져오기는 보낼 곳 목록(outbox)에 넣어 끊겨도 이어서 한다. 아래 줄에 진행 상황을 보인다.
- 네트워크 일은 모두 작업 스레드에서 하고, 결과는 앱의 이벤트 큐("ui_call")로 화면 스레드에 넘긴다.
"""
from __future__ import annotations

import datetime as dt
import os
import shutil
import subprocess
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox

import customtkinter as ctk
from tkinterdnd2 import DND_FILES

import cloud
import outbox
import protocol as p
import store
from i18n import t
from ui import (BORDER, DROP, GREEN, GUTTER, HOVER, INK, INK_HOVER, INK_TEXT, KIND_ICON, MAIN_BG, RED, SECONDARY,
                SELECTED, SIDEBAR_BG, TEXT, AMBER, apply_glass, bind_tree, card, font, ghost_button, human_size, icon,
                round_icon)

LOCAL = ""  # 왼쪽 목록에서 '이 PC'
RENDER_PAGE = 100  # 한 번에 그리는 줄 수(CTk 위젯이 무거워 나눠 그린다)
CLOUD_KINDS = ("cloud_put", "cloud_get")
STATE_TEXT = {"checking": "cloud.state_checking", "on": "cloud.state_on", "off": "cloud.state_off",
              "old": "cloud.state_old", "unreachable": "cloud.state_unreachable"}


class CloudWindow(ctk.CTkToplevel):
    def __init__(self, app, source: str | None = None) -> None:
        super().__init__(app, fg_color=MAIN_BG)
        self.app = app
        self.node = app.node
        self.title(t("cloud.title"))
        self.geometry("900x620")
        self.minsize(640, 420)
        self.source = source if source is not None else (LOCAL if self.node.cloud_on else next(iter(self.node.pairings), LOCAL))
        self.path: list[str] = []
        self.states: dict[str, str] = {}  # 기기별 상태: checking | on | off | old | unreachable
        self.state_errors: dict[str, str] = {}
        self.items: list[cloud.Item] = []
        self.next_cursor: int | None = None
        self.shown = RENDER_PAGE
        self.loading = 0  # 늦게 온 응답이 다른 폴더의 목록을 덮지 않게 요청마다 번호를 붙인다
        self.error = ""
        self._build()
        self.drop_target_register(DND_FILES)
        self.dnd_bind("<<DropEnter>>", lambda e: (self.list.configure(fg_color=DROP), e.action)[1])
        self.dnd_bind("<<DropLeave>>", lambda e: (self.list.configure(fg_color=MAIN_BG), e.action)[1])
        self.dnd_bind("<<Drop>>", self._on_drop)
        self.after(250, self._set_icon)
        self.after(30, lambda: apply_glass(self))
        self.check_sources()
        self.open_source(self.source)

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

    # ---------- 화면 ----------
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
                if "cloud" not in info["caps"]:
                    return "old"
                return "on" if info.get("cloud") is True else "off"

            def done(state, error, s=sid):
                self.states[s] = "unreachable" if error is not None else state
                self.state_errors[s] = str(error) if error is not None else ""
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
        state = "normal" if self.path else "disabled"
        self.up_button.configure(state=state)

    def _jump(self, target: list[str]) -> None:
        self.path = list(target)
        self.reload()

    def _browsable(self) -> bool:
        return (self.source == LOCAL and self.node.cloud_on) or self.states.get(self.source) == "on"

    # ---------- 목록 ----------
    def reload(self) -> None:
        self._render_crumbs()
        self._render_tools()
        self.items, self.next_cursor, self.shown, self.error = [], None, RENDER_PAGE, ""
        if not self._browsable():
            self._render_message()
            return
        self.loading += 1
        token, source, path = self.loading, self.source, list(self.path)
        self._message(t("cloud.loading"))
        self._bg(lambda: self._fetch(source, path, 0), lambda r, e: self._loaded(token, r, e, append=False))

    def _fetch(self, source: str, path: list[str], cursor: int):
        if source == LOCAL:
            return cloud.CloudFolder(self.node.cloud_dir).list(path, cursor)
        return self.node.cloud_list(source, cloud.join_path(path), cursor)

    def _loaded(self, token: int, result, error, append: bool) -> None:
        if token != self.loading:
            return  # 그 사이 다른 폴더로 옮겼다
        if error is not None:
            if isinstance(error, p.ProtocolError) and error.code == "not_found" and self.path:
                self.path = self.path[:-1]  # 폴더가 사라졌다(다른 기기에서 지움): 위로
                self.reload()
                return
            self.error = str(error)
            self._render_message()
            return
        items, nxt = result
        self.items = self.items + items if append else items
        self.next_cursor = nxt
        self.render_items()

    def load_more(self) -> None:
        if self.shown < len(self.items):
            self.shown += RENDER_PAGE
            self.render_items()
            return
        if self.next_cursor is None:
            return
        token, source, path, cursor = self.loading, self.source, list(self.path), self.next_cursor
        self.shown += RENDER_PAGE
        self._bg(lambda: self._fetch(source, path, cursor), lambda r, e: self._loaded(token, r, e, append=True))

    def _clear_list(self) -> None:
        for child in self.list.winfo_children():
            child.destroy()

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

    def render_items(self) -> None:
        self._clear_list()
        if not self.items:
            self._message(t("cloud.empty"))
            return
        for item in self.items[:self.shown]:
            self._row(item)
        if self.shown < len(self.items) or self.next_cursor is not None:
            ctk.CTkButton(self.list, text=t("cloud.more"), command=self.load_more, height=28, corner_radius=6,
                          fg_color="transparent", hover_color=HOVER, text_color=SECONDARY, border_width=1,
                          border_color=BORDER, font=font(11)).pack(pady=10)

    def _row(self, item: cloud.Item) -> None:
        row = ctk.CTkFrame(self.list, fg_color="transparent", corner_radius=6, height=44, cursor="hand2")
        row.pack(fill="x", padx=4, pady=1)
        row.pack_propagate(False)
        badge = round_icon(row, "folder" if item.is_dir else "file", 30)
        badge.pack(side="left", padx=(8, 10))
        name = item.name if len(item.name) <= 60 else item.name[:57] + "…"
        label = ctk.CTkLabel(row, text=name, font=font(13), text_color=TEXT, anchor="w")
        label.pack(side="left", fill="x", expand=True)
        actions = ctk.CTkFrame(row, fg_color="transparent")
        actions.pack(side="right", padx=(4, 6))
        ghost_button(actions, "trash-2", lambda it=item: self.delete(it), size=28).pack(side="right")
        if not item.is_dir and self.source != LOCAL:
            ghost_button(actions, "download", lambda it=item: self.download(it), size=28).pack(side="right")
        when = dt.datetime.fromtimestamp(item.mtime / 1000).strftime("%Y-%m-%d %H:%M") if item.mtime else ""
        date = ctk.CTkLabel(row, text=when, font=font(11), text_color=SECONDARY, width=120, anchor="e")
        date.pack(side="right")
        size = ctk.CTkLabel(row, text="" if item.is_dir else human_size(item.size), font=font(11),
                            text_color=SECONDARY, width=80, anchor="e")
        size.pack(side="right")
        bind_tree(row, "<Enter>", lambda _e, r=row: r.configure(fg_color=HOVER))
        bind_tree(row, "<Leave>", lambda _e, r=row: r.configure(fg_color="transparent"))
        # 줄을 누르면 열기·가져오기. 오른쪽 버튼(가져오기·지우기)은 따로 동작한다.
        for widget in (row, badge, label, date, size):
            bind_tree(widget, "<Button-1>", lambda _e, it=item: self.activate(it))

    def activate(self, item: cloud.Item) -> None:
        if item.is_dir:
            self.enter(item.name)
        elif self.source == LOCAL:
            self._show_local(self.node.cloud_dir.joinpath(*self.path, item.name))
        else:
            self.download(item)

    # ---------- 동작 ----------
    def download(self, item: cloud.Item) -> None:
        remote = cloud.join_path([*self.path, item.name])
        self.node.outbox.add_cloud_download(self.source, remote, item.size, item.mtime)
        self.app.flash(t("cloud.queued_down", name=item.name))

    def delete(self, item: cloud.Item) -> None:
        where = self._source_name(self.source)
        pairing = self.node.pairings.get(self.source)
        to_bin = self.source == LOCAL or (pairing is not None and pairing.kind == "pc")
        key = "cloud.delete_confirm_bin" if to_bin else "cloud.delete_confirm"
        if not messagebox.askyesno(t("cloud.title"), t(key, name=item.name, where=where), parent=self):
            return
        parts = [*self.path, item.name]
        if self.source == LOCAL:
            work = lambda: cloud.CloudFolder(self.node.cloud_dir).delete(parts)  # noqa: E731
        else:
            source = self.source
            work = lambda: self.node.cloud_delete(source, cloud.join_path(parts))  # noqa: E731
        self._bg(work, self._after_change)

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
        parts = [*self.path, name]
        if self.source == LOCAL:
            work = lambda: cloud.CloudFolder(self.node.cloud_dir).mkdir(parts)  # noqa: E731
        else:
            source = self.source
            work = lambda: self.node.cloud_mkdir(source, cloud.join_path(parts))  # noqa: E731
        self._bg(work, self._after_change)

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
        if self.source == LOCAL:
            folder = cloud.CloudFolder(self.node.cloud_dir)
            parts = list(self.path)
            self.app.flash(t("cloud.copying"))
            self._bg(lambda: _copy_into(folder.folder_for_put(parts), paths), self._after_change)
            return
        jobs = self.node.outbox.add_cloud_upload(self.source, paths, cloud.join_path(self.path))
        self.app.flash(t("cloud.queued_up", count=len(jobs)))

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


def _copy_into(folder: Path, paths: list[Path]) -> None:
    """이 PC의 클라우드 폴더에 복사한다(같은 이름이 있으면 번호를 붙인다). 링크는 따라가지 않는다."""
    for src in paths:
        name = p.sanitize_filename(src.name)
        target = p.unique_path(folder, name)
        if src.is_dir():
            shutil.copytree(src, target, symlinks=True)
        else:
            shutil.copy2(src, target)


def cloud_folder_dialog(parent) -> str | None:
    chosen = filedialog.askdirectory(title=t("dialog.cloud_folder_title"), parent=parent)
    return chosen or None

