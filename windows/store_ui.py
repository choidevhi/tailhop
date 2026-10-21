"""암호화 클라우드 저장소의 창(2.2.0, docs/PROTOCOL.md §17): 만들기·열기·비밀번호 바꾸기·리셋·위치 옮기기.

- 만들기: 비밀번호를 두 번(10자 이상) 넣으면 저장소를 만들고 복구 코드를 한 번만 보여 준다.
- 열기: 새 PC·Windows를 다시 깔았을 때 비밀번호(또는 복구 코드)로 연다. 그 뒤로는 이 PC에서 자동으로 열린다.
- 리셋: 비밀번호를 잊었을 때. 그 저장소의 내용을 모두 지운다(되돌릴 수 없다). 확인 단어를 직접 쳐야 한다.
- 무거운 일(Argon2id·복사)은 작업 스레드에서 하고 결과는 앱의 이벤트 큐("ui_call")로 화면 스레드에 넘긴다.
"""
from __future__ import annotations

import threading
from pathlib import Path

import customtkinter as ctk

import cloudkeys
import cloudstore as cs
from i18n import t
from ui import (fit_geometry, AMBER, BORDER, GUTTER, HOVER, INK, INK_HOVER, INK_TEXT, MAIN_BG, RED, SECONDARY, TEXT, apply_glass,
                card, font, human_size, protect)

TITLES = {"create": "store.create_title", "unlock": "store.unlock_title", "change": "store.change_title",
          "reset": "store.reset_title"}
OK_TEXT = {"create": "store.ok_create", "unlock": "store.ok_unlock", "change": "store.ok_change",
           "reset": "store.ok_reset"}
HINTS = {"create": "store.create_hint", "unlock": "store.unlock_hint", "change": "store.change_hint",
         "reset": "store.reset_hint"}


def run_bg(app, work, done) -> None:
    """work()를 작업 스레드에서, done(결과, 예외)을 화면 스레드에서."""
    def run() -> None:
        try:
            result, error = work(), None
        except Exception as exc:  # noqa: BLE001 - 창에 이유를 보인다
            result, error = None, exc
        app.events.put(("ui_call", {"fn": lambda: done(result, error)}))

    threading.Thread(target=run, daemon=True, name="tailhop-store").start()


class StoreDialog(ctk.CTkToplevel):
    def __init__(self, app, parent, mode: str, store: cs.Store, on_done=None) -> None:
        super().__init__(parent)
        self.app, self.mode, self.store, self.on_done = app, mode, store, on_done
        self.use_recovery = False
        self.title(t(TITLES[mode]))
        fit_geometry(self, 460, 400 if mode in ("create", "change") else 330)
        self.resizable(False, False)
        self.transient(parent)
        self.after(30, lambda: (apply_glass(self), protect(self)))
        box = card(self)
        box.pack(fill="both", expand=True, padx=GUTTER, pady=GUTTER)
        ctk.CTkLabel(box, text=t(TITLES[mode]), font=font(15, "bold"), text_color=TEXT,
                     anchor="w").pack(fill="x", padx=20, pady=(16, 0))
        ctk.CTkLabel(box, text=t(HINTS[mode], path=str(store.root)), font=font(12),
                     text_color=AMBER if mode == "reset" else SECONDARY, anchor="w", justify="left",
                     wraplength=390).pack(fill="x", padx=20, pady=(2, 8))
        self.fields = ctk.CTkFrame(box, fg_color="transparent")
        self.fields.pack(fill="x", padx=20)
        self.error = ctk.CTkLabel(box, text="", font=font(11), text_color=RED, anchor="w", wraplength=390,
                                  justify="left")
        self.error.pack(fill="x", padx=20)
        buttons = ctk.CTkFrame(box, fg_color="transparent")
        buttons.pack(fill="x", side="bottom", padx=20, pady=(4, 16))
        danger = mode == "reset"
        self.ok = ctk.CTkButton(buttons, text=t(OK_TEXT[mode]),
                                command=self.submit, height=34, corner_radius=6,
                                fg_color="#DC2626" if danger else INK, hover_color="#B91C1C" if danger else INK_HOVER,
                                text_color="#FAFAFA" if danger else INK_TEXT, font=font(12, "bold"), width=110)
        self.ok.pack(side="right")
        ctk.CTkButton(buttons, text=t("vault.cancel"), command=self.destroy, height=34, corner_radius=6,
                      fg_color="transparent", hover_color=HOVER, text_color=TEXT, border_width=1, border_color=BORDER,
                      font=font(12), width=80).pack(side="right", padx=8)
        if mode in ("unlock", "change"):
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
                             font=font(13))
        entry.pack(fill="x", pady=4)
        return entry

    def _fields(self) -> None:
        for child in self.fields.winfo_children():
            child.destroy()
        self.current = self.new = self.again = self.word = None
        if self.mode in ("unlock", "change"):
            self.current = self._entry(t("vault.recovery") if self.use_recovery else t("store.password"),
                                       secret=not self.use_recovery)
        if self.mode in ("create", "change"):
            self.new = self._entry(t("store.new_password"))
            self.again = self._entry(t("store.new_password_again"))
        if self.mode == "reset":
            self.word = self._entry(t("store.reset_type", word=t("store.reset_word")), secret=False)
        (self.current or self.new or self.word).focus_set()

    def toggle_recovery(self) -> None:
        self.use_recovery = not self.use_recovery
        self.switch_link.configure(text=t("vault.use_pin") if self.use_recovery else t("vault.use_recovery"))
        self._fields()

    def fail(self, message: str) -> None:
        if self.winfo_exists():
            self.ok.configure(state="normal")
            self.error.configure(text=message)

    def submit(self) -> None:
        if self.ok.cget("state") == "disabled":
            return
        current = self.current.get() if self.current is not None else ""
        new = self.new.get() if self.new is not None else ""
        if self.new is not None:
            if new != self.again.get():
                self.fail(t("vault.mismatch"))
                return
            if not cloudkeys.password_ok(new):
                self.fail(t("store.password_rule"))
                return
        if self.mode == "reset" and self.word.get().strip() != t("store.reset_word"):
            self.fail(t("store.reset_type", word=t("store.reset_word")))
            return
        secret = {"recovery": current} if self.use_recovery else {"password": current}
        work = {"create": lambda: self.store.create(new),
                "unlock": lambda: self.store.unlock(**secret),
                "change": lambda: self.store.change_password(new, **secret),
                "reset": self.store.reset}[self.mode]
        self.ok.configure(state="disabled")
        self.error.configure(text=t("vault.working"), text_color=SECONDARY)

        def done(result, error) -> None:
            if error is not None:
                self.error.configure(text_color=RED)
                self.fail(str(error))
                return
            parent = self.master
            self.destroy()
            if self.mode == "create":
                from cloud_ui import show_recovery
                show_recovery(parent, result, "store.recovery_hint")
            if self.on_done is not None:
                self.on_done()

        run_bg(self.app, work, done)


def ask(app, parent, mode: str, on_done=None) -> None:
    """지금 저장소에 맞는 창을 연다(mode: create·unlock·change·reset)."""
    st = app.node.store()
    if st is not None:
        StoreDialog(app, parent, mode, st, on_done)


def state_text(state: str) -> tuple[str, tuple]:
    """설정·클라우드 창에 보일 상태 문장과 색."""
    return {"open": (t("store.state_open"), SECONDARY), "new": (t("store.state_new"), AMBER),
            "locked": (t("store.state_locked"), AMBER), "missing": (t("store.state_missing"), RED),
            "off": (t("cloud.state_off"), SECONDARY)}.get(state, (state, SECONDARY))


def place_text(path: Path) -> str:
    """'E: Samsung T7 · 남은 공간 812 GB · E:\\TailHop Cloud' 모양의 위치 설명."""
    info = cs.drive_info(path)
    parts = [" ".join(x for x in (info["drive"], info["label"]) if x)]
    if info["removable"]:
        parts.append(t("store.removable"))
    if info["free"] is not None:
        parts.append(t("store.free", size=human_size(info["free"])))
    return " · ".join(parts) + "\n" + str(path)
