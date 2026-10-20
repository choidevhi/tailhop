"""설정 창(1.9.0): 사이드바의 … 버튼으로 연다. 유리 바탕 위에 shadcn Card 모양의 구역을 세로로 늘어놓는다.

- 일반: 언어, Windows 시작 시 실행, 미리보기, 탐색기 '보내기', 전역 단축키
- 받기: 받기 켜기·1시간 멈춤, 받은 파일 저장 폴더(바꾸기·열기)
- 클라우드: 이 PC를 클라우드로 쓰기, 클라우드 폴더(바꾸기·열기), 받은 파일도 클라우드에 모으기
- 기기: 연결된 기기(확인 코드, 연결 해제), 새 기기 연결
- 업데이트: 지금 버전, 연결된 PC가 내주는 새 버전(Tailscale로 받아 바로 설치), 이 PC가 내주는 업데이트
설정 값은 큰 창(App)의 변수·함수를 그대로 써서 두 창이 늘 같은 상태를 보인다.
"""
from __future__ import annotations

import os
import threading
import tkinter as tk
from pathlib import Path

import customtkinter as ctk

import i18n
import store
import updates
from i18n import t
from ui import (BORDER, GREEN, GUTTER, HOVER, INK, INK_HOVER, INK_TEXT, KIND_ICON, RED, SECONDARY, TEXT, apply_glass,
                card, font, human_size, icon, round_icon)

TARGET_TEXT = {"pc": "settings.target_pc", "android": "settings.target_android"}


def _button(parent, text: str, command, primary: bool = False, **kw) -> ctk.CTkButton:
    """shadcn Button: h-9(36) rounded-md(6). primary = 진한 바탕, 아니면 outline."""
    if primary:
        return ctk.CTkButton(parent, text=text, command=command, height=32, corner_radius=6, fg_color=INK,
                             hover_color=INK_HOVER, text_color=INK_TEXT, font=font(12, "bold"), **kw)
    return ctk.CTkButton(parent, text=text, command=command, height=32, corner_radius=6, fg_color="transparent",
                         hover_color=HOVER, text_color=TEXT, border_width=1, border_color=BORDER, font=font(12), **kw)


class SettingsWindow(ctk.CTkToplevel):
    def __init__(self, app) -> None:
        super().__init__(app)
        self.app = app
        self.node = app.node
        self.title(t("settings.title"))
        self.geometry("620x720")
        self.minsize(520, 480)
        self.update_rows: ctk.CTkFrame | None = None
        self.update_note: ctk.CTkLabel | None = None
        self.update_bar: ctk.CTkProgressBar | None = None
        self._build()
        self.after(30, lambda: apply_glass(self))
        if app.icon_path is not None:
            self.after(250, lambda: self.iconbitmap(str(app.icon_path)))
        self.check_updates()

    # ---------- 틀 ----------
    def _build(self) -> None:
        self.body = ctk.CTkScrollableFrame(self, fg_color="transparent", corner_radius=0,
                                           scrollbar_button_color=BORDER, scrollbar_button_hover_color=SECONDARY)
        self.body.pack(fill="both", expand=True, padx=GUTTER // 2, pady=GUTTER // 2)
        self._general()
        self._receiving()
        self._cloud()
        self._devices()
        self._updates()

    def rebuild(self) -> None:
        for child in self.body.winfo_children():
            child.destroy()
        self.title(t("settings.title"))
        self._general()
        self._receiving()
        self._cloud()
        self._devices()
        self._updates()
        self.check_updates()

    def _section(self, title: str, description: str) -> ctk.CTkFrame:
        box = card(self.body)
        box.pack(fill="x", padx=GUTTER // 2, pady=GUTTER // 2)
        ctk.CTkLabel(box, text=title, font=font(15, "bold"), text_color=TEXT, anchor="w").pack(
            fill="x", padx=20, pady=(16, 0))
        ctk.CTkLabel(box, text=description, font=font(12), text_color=SECONDARY, anchor="w", justify="left",
                     wraplength=520).pack(fill="x", padx=20, pady=(2, 8))
        return box

    def _row(self, box, label: str, hint: str = "") -> ctk.CTkFrame:
        row = ctk.CTkFrame(box, fg_color="transparent")
        row.pack(fill="x", padx=20, pady=7)
        texts = ctk.CTkFrame(row, fg_color="transparent")
        texts.pack(side="left", fill="x", expand=True)
        ctk.CTkLabel(texts, text=label, font=font(13), text_color=TEXT, anchor="w").pack(fill="x")
        if hint:
            ctk.CTkLabel(texts, text=hint, font=font(11), text_color=SECONDARY, anchor="w", justify="left",
                         wraplength=360).pack(fill="x")
        return row

    @staticmethod
    def _end(box) -> None:
        """구역 카드의 마지막 줄 아래 여백."""
        ctk.CTkFrame(box, fg_color="transparent", height=10).pack(fill="x", padx=20)  # 가장자리에 닿으면 둥근 모서리를 덮는다

    def _switch(self, row, variable: tk.BooleanVar, command, state: str = "normal") -> ctk.CTkSwitch:
        sw = ctk.CTkSwitch(row, text="", variable=variable, command=command, onvalue=True, offvalue=False, width=40,
                           switch_width=38, switch_height=20, progress_color=INK, button_color=("#FFFFFF", "#FAFAFA"),
                           button_hover_color=("#FFFFFF", "#FAFAFA"), fg_color=("#D4D4D8", "#3F3F46"), state=state)
        sw.pack(side="right")
        return sw

    def _path_row(self, box, label: str, path: Path, change, open_folder) -> None:
        row = self._row(box, label, str(path))
        _button(row, t("settings.open"), open_folder, width=64).pack(side="right", padx=(6, 0))
        _button(row, t("settings.change"), change, width=64).pack(side="right")

    # ---------- 구역 ----------
    def _general(self) -> None:
        box = self._section(t("settings.general"), t("settings.general_desc"))
        row = self._row(box, t("menu.language"))
        names = [t("lang.system"), *i18n.LANGS.values()]
        codes = [i18n.SYSTEM, *i18n.LANGS.keys()]
        current = names[codes.index(self.app.lang_var.get())] if self.app.lang_var.get() in codes else names[0]
        ctk.CTkOptionMenu(row, values=names, command=lambda name: self._language(codes[names.index(name)]),
                          width=150, height=32, corner_radius=6, fg_color=("#FFFFFF", "#09090B"),
                          button_color=("#FFFFFF", "#09090B"), button_hover_color=HOVER, text_color=TEXT,
                          dropdown_fg_color=("#FFFFFF", "#09090B"), dropdown_text_color=TEXT,
                          dropdown_hover_color=HOVER, font=font(12), dropdown_font=font(12),
                          variable=tk.StringVar(master=row, value=current)).pack(side="right")
        self._switch(self._row(box, t("menu.autostart")), self.app.autostart_var, self.app._toggle_autostart)
        self._switch(self._row(box, t("menu.previews")), self.app.preview_var, self.app._toggle_previews)
        self._switch(self._row(box, t("menu.sendto")), self.app.sendto_var, self.app._toggle_sendto)
        self._switch(self._row(box, t("settings.hotkey"), t("settings.hotkey_hint", key=self.app.hotkey_label)),
                     self.app.hotkey_var, self.app._toggle_hotkey)
        self._end(box)

    def _language(self, code: str) -> None:
        self.app._set_language(code)
        self.rebuild()

    def _receiving(self) -> None:
        box = self._section(t("settings.receiving"), t("settings.receiving_desc"))
        row = self._row(box, t("sidebar.receive"), self.app._status_text())
        self._switch(row, self.app.receiving_var, self.app._on_receive_switch)
        row = self._row(box, t("menu.pause_hour"))
        _button(row, t("settings.pause"), lambda: self.app._set_receiving(False, 3600), width=90).pack(side="right")
        self._path_row(box, t("settings.save_dir"), self.node.save_dir, self._change_save_dir, self.app._open_save_dir)
        self._end(box)

    def _change_save_dir(self) -> None:
        self.app._change_save_dir()
        self.rebuild()

    def _cloud(self) -> None:
        box = self._section(t("cloud.title"), t("settings.cloud_desc"))
        self._switch(self._row(box, t("menu.cloud_on")), self.app.cloud_on_var, self._cloud_switch)
        self._path_row(box, t("settings.cloud_dir"), self.node.cloud_dir, self._change_cloud_dir,
                       lambda: _open_folder(self.node.cloud_dir))
        self._switch(self._row(box, t("menu.cloud_collect"), t("settings.collect_hint")), self.app.cloud_collect_var,
                     self.app._on_collect_switch, state="normal" if self.node.cloud_on else "disabled")
        row = self._row(box, t("menu.cloud_open"))
        _button(row, t("settings.open"), self.app.open_cloud, width=64).pack(side="right")
        self._end(box)

    def _cloud_switch(self) -> None:
        self.app._on_cloud_switch()
        self.rebuild()

    def _change_cloud_dir(self) -> None:
        self.app._change_cloud_dir()
        self.rebuild()

    def _devices(self) -> None:
        box = self._section(t("settings.devices"), t("settings.devices_desc"))
        for sid, pairing in self.app._devices():
            row = ctk.CTkFrame(box, fg_color="transparent")
            row.pack(fill="x", padx=20, pady=8)
            round_icon(row, KIND_ICON.get(pairing.kind, "smartphone"), 30).pack(side="left", padx=(0, 10))
            texts = ctk.CTkFrame(row, fg_color="transparent")
            texts.pack(side="left", fill="x", expand=True)
            ctk.CTkLabel(texts, text=pairing.name, font=font(13), text_color=TEXT, anchor="w").pack(fill="x")
            ctk.CTkLabel(texts, text=t("settings.device_detail", ip=pairing.ip, code=self.app.confirm_code(pairing)),
                         font=font(11), text_color=SECONDARY, anchor="w").pack(fill="x")
            _button(row, t("devmenu.unpair"), lambda s=sid: self._unpair(s), width=80).pack(side="right")
        row = ctk.CTkFrame(box, fg_color="transparent")
        row.pack(fill="x", padx=20, pady=(4, 16))
        _button(row, t("pairmenu.show"), self.app._show_pair_qr, primary=True).pack(side="left")
        _button(row, t("pairmenu.enter"), self.app._show_join_dialog).pack(side="left", padx=8)

    def _unpair(self, sid: str) -> None:
        self.app._unpair(sid)
        self.rebuild()

    # ---------- 업데이트 ----------
    def _updates(self) -> None:
        box = self._section(t("settings.updates"), t("settings.updates_desc", version=self.app.version))
        self.update_rows = ctk.CTkFrame(box, fg_color="transparent")
        self.update_rows.pack(fill="x")
        self.update_note = ctk.CTkLabel(box, text=t("settings.update_checking"), font=font(12),
                                        text_color=SECONDARY, anchor="w", justify="left", wraplength=520)
        self.update_note.pack(fill="x", padx=20, pady=(4, 4))
        self.update_bar = ctk.CTkProgressBar(box, height=4, corner_radius=2, progress_color=INK, fg_color=BORDER)
        offered = self.node.shelf.packages()
        if offered:
            lines = [t("settings.shelf_item", target=t(TARGET_TEXT[target]), version=pkg.version,
                       size=human_size(pkg.size)) for target, pkg in offered.items()]
            ctk.CTkLabel(box, text=t("settings.shelf", items=", ".join(lines)), font=font(11), text_color=GREEN,
                         anchor="w", justify="left", wraplength=520).pack(fill="x", padx=20, pady=(0, 4))
        row = ctk.CTkFrame(box, fg_color="transparent")
        row.pack(fill="x", padx=20, pady=(4, 16))
        _button(row, t("settings.update_check"), self.check_updates).pack(side="left")

    def check_updates(self) -> None:
        """연결된 PC마다 hello로 내주는 PC 업데이트를 묻는다(동시에, 작업 스레드)."""
        if self.update_note is None:
            return
        self.update_note.configure(text=t("settings.update_checking"), text_color=SECONDARY)
        pcs = [(sid, x) for sid, x in self.app._devices() if x.kind == "pc"]
        found: list[tuple[str, str, dict]] = []
        own = self.node.shelf.packages().get("pc")
        if own is not None and updates.newer(own.version, self.app.version):
            found.append(("", t("cloud.this_pc"), {"version": own.version, "size": own.size}))
        left = [len(pcs)]

        def finish() -> None:
            if self.winfo_exists():
                self._show_updates(found)

        if not pcs:
            finish()
            return
        for sid, pairing in pcs:
            def work(s=sid, name=pairing.name):
                try:
                    offered = self.node.peer_updates(s).get("pc")
                except Exception:  # noqa: BLE001 - 꺼진 PC는 건너뛴다
                    offered = None
                if offered and updates.newer(offered["version"], self.app.version):
                    found.append((s, name, offered))
                left[0] -= 1
                if left[0] == 0:
                    self.app.events.put(("ui_call", {"fn": finish}))

            threading.Thread(target=work, daemon=True).start()

    def _show_updates(self, found: list[tuple[str, str, dict]]) -> None:
        for child in self.update_rows.winfo_children():
            child.destroy()
        if not found:
            self.update_note.configure(text=t("settings.update_none"), text_color=SECONDARY)
            return
        found.sort(key=lambda x: updates.version_tuple(x[2]["version"]), reverse=True)
        self.update_note.configure(text="")
        for sid, name, info in found[:3]:
            row = ctk.CTkFrame(self.update_rows, fg_color="transparent")
            row.pack(fill="x", padx=20, pady=10)
            ctk.CTkLabel(row, text=" " + t("settings.update_item", version=info["version"], name=name,
                                           size=human_size(info["size"])),
                         image=icon("download", 16), compound="left", font=font(13), text_color=TEXT,
                         anchor="w").pack(side="left")
            if updates.can_self_update():
                _button(row, t("settings.update_now"), lambda s=sid, i=info: self._install(s, i),
                        primary=True, width=110).pack(side="right")
            else:
                ctk.CTkLabel(row, text=t("settings.update_dev"), font=font(11), text_color=SECONDARY).pack(side="right")

    def _install(self, sid: str, info: dict) -> None:
        """받고(이 PC 선반이면 그대로) 크기·SHA-256을 확인한 뒤 설치 프로그램을 조용히 실행한다. 앱은 곧 꺼졌다 다시 켜진다."""
        self.update_note.configure(text=t("settings.update_downloading", version=info["version"]), text_color=SECONDARY)
        self.update_bar.pack(fill="x", padx=20, pady=(0, 6))
        self.update_bar.set(0)
        dest = Path(os.environ.get("LOCALAPPDATA", store.APP_DIR)) / "TailHop" / "update"

        def progress(done: int, total: int) -> None:
            self.app.events.put(("ui_call", {"fn": lambda: self.winfo_exists() and self.update_bar.set(done / max(1, total))}))

        def work() -> None:
            try:
                if sid:
                    path, _ = self.node.fetch_update(sid, "pc", dest, progress)
                else:
                    pkg = self.node.shelf.packages()["pc"]
                    if not updates.verify(pkg.path, pkg.size, pkg.sha256):
                        raise RuntimeError(t("err.hash_mismatch"))
                    path = pkg.path
                updates.run_installer(path)
                message, color = t("settings.update_installing"), GREEN
            except Exception as exc:  # noqa: BLE001 - 이유를 보인다
                message, color = t("settings.update_failed", error=str(exc)), RED

            self.app.events.put(("ui_call", {"fn": lambda: self.winfo_exists() and self.update_note.configure(
                text=message, text_color=color)}))

        threading.Thread(target=work, daemon=True).start()


def _open_folder(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    os.startfile(str(path))  # noqa: S606 - 탐색기로 폴더만 연다

