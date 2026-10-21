"""사진·영상 크게 보기(라이트박스, 2.1.0, 2.2.1에서 고도화). 클라우드 창과 대화방이 함께 쓴다.

- 창은 그 모니터의 작업 영역에 맞춰 최대화로 연다(화면 배율 때문에 화면보다 커지지 않게).
- 먼저 미리보기(작은 그림)를 크게 보이고, 원본은 작업 스레드에서 불러와 바꾼다(실패하면 미리보기 그대로 + 이유).
  보고 있는 장이 열리면 앞뒤 장을 미리 불러 둔다(넘길 때 바로 보인다).
- 화면에는 보이는 부분만 그때그때 잘라 줄여 그린다. 큰 사진은 1/2, 1/4… 사본(피라미드)을 미리 만들어 두고
  배율에 맞는 것에서 자른다. 움직이는 동안은 빠르게, 멈추면 곧바로 고품질(LANCZOS)로 다시 그린다.
- 휠·핀치(Windows가 Ctrl+휠로 보낸다)로 커서 자리를 기준으로 확대·축소, 끌어서 이동, 두 번 눌러 맞춤과 100 % 사이,
  맞춤 배율에서 옆으로 밀어 넘기기. 도구 막대: 축소·배율·확대·맞춤·원래 크기·왼쪽/오른쪽 돌리기(보기만)·정보·필름 띠·전체 화면.
- 키: 왼쪽·오른쪽 화살표 PageUp/PageDown 넘기기, Home/End 처음·끝, + - 확대·축소, 0 맞춤, 1 원래 크기, R / Shift+R 돌리기, I 정보,
  T 필름 띠, F·F11 전체 화면, Delete 지우기, Enter·Space 영상 재생, Esc 전체 화면 끄기·닫기.
- 원본은 메모리에만 둔다(잠긴 폴더 안 파일도 디스크에 쓰지 않는다). 최근 몇 장만, 화소 수 한도 안에서 들고 있는다.
- 화면 캡처·녹화에서 뺀다(ui.protect).
"""
from __future__ import annotations

import ctypes
import io
import threading
import time
import tkinter as tk
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Callable

import customtkinter as ctk
from PIL import ExifTags, Image, ImageDraw, ImageOps, ImageTk

from i18n import t
from ui import fit_geometry, font, icon, protect

BG = "#0A0A0B"
BAR_TEXT = "#FAFAFA"
BAR_MUTED = "#A1A1AA"
BAR_BORDER = "#3F3F46"
BAR_HOVER = "#27272A"
ROUND_BG = "#18181B"
PANEL_BG = "#111113"
MAX_PIXELS = 40_000_000  # 화면에 들고 있는 원본 크기 상한(넘으면 줄여 둔다)
REFUSE_PIXELS = 170_000_000  # 이보다 큰 그림은 풀지 않는다(압축 폭탄, Pillow 기본 한도 아래)
PYRAMID_MIN = 600  # 피라미드는 긴 변이 이만큼 될 때까지 반씩 줄인다
MAX_ZOOM = 8.0
WHEEL_STEP = 1.2  # 휠 한 칸(120)마다 배율
BUTTON_STEP = 1.5  # 확대·축소 단추 한 번
SWIPE_PX = 120
KEEP = 5  # 원본을 들고 있는 장 수
KEEP_PIXELS = 120_000_000  # 들고 있는 원본 화소 합 상한(지금 장은 늘 남긴다)
HQ_DELAY_MS = 140  # 움직임이 멈추고 고품질로 다시 그리기까지
HIDE_AFTER = 2.5  # 전체 화면에서 막대를 숨기기까지(초)
STRIP_PX = 60  # 필름 띠 한 칸(논리 단위)
STRIP_GAP = 6
STRIP_CACHE = 240
_EXIF_NAMES = {"DateTimeOriginal": "taken", "Make": "make", "Model": "model", "FNumber": "fnumber",
               "ExposureTime": "exposure", "ISOSpeedRatings": "iso", "FocalLength": "focal"}


@dataclass
class Shot:
    """한 장. key는 부른 쪽이 자기 항목을 찾는 값, preview는 바로 보일 작은 그림(없으면 None),
    load는 작업 스레드에서 원본을 돌려준다(None이면 미리보기만). details는 정보 칸에 보일 (이름, 값)."""
    key: str
    name: str
    preview: Image.Image | None
    load: Callable[[], Image.Image | None]
    video: bool = False
    hires: Callable[[], Image.Image | None] | None = None  # 화면용 버전보다 크게 확대하면 원본을 받는다(없으면 없음)
    details: list[tuple[str, str]] = field(default_factory=list)


@dataclass
class Action:
    label: str
    icon: str
    run: Callable[[Shot], None]
    style: str = "outline"  # outline | primary | danger
    key: str = ""  # "play"면 Enter·Space·영상 두 번 누르기, "delete"면 Delete 키
    video_only: bool = False  # 영상일 때만 보인다


def _exif_summary(im: Image.Image) -> dict[str, str]:
    """찍은 날짜·카메라·노출만(위치 정보는 읽지 않는다)."""
    try:
        exif = im.getexif()
        ifd = exif.get_ifd(ExifTags.IFD.Exif)
    except (AttributeError, KeyError, ValueError, OSError):
        return {}
    out: dict[str, str] = {}
    for tag, value in [*exif.items(), *ifd.items()]:
        name = _EXIF_NAMES.get(ExifTags.TAGS.get(tag, ""))
        if name is None or value in (None, ""):
            continue
        if name == "exposure" and isinstance(value, float) and 0 < value < 1:
            text = f"1/{round(1 / value)}s"
        elif name == "exposure":
            text = f"{float(value):g}s"
        elif name == "fnumber":
            text = f"f/{float(value):g}"
        elif name == "focal":
            text = f"{float(value):g}mm"
        else:
            text = str(value).strip("\x00 ")
        if text:
            out[name] = text[:60]
    return out


def decode(data: bytes) -> Image.Image:
    """바이트를 화면에 둘 그림으로(EXIF 회전 반영, 너무 크면 줄인다). 풀 수 없으면 OSError·ValueError.
    원래 크기와 EXIF 요약은 info["tailhop_size"], info["tailhop_exif"]에 둔다."""
    try:
        opened = Image.open(io.BytesIO(data))
    except Image.DecompressionBombError as exc:
        raise ValueError("too large") from exc
    with opened as im:
        w, h = im.size
        if w <= 0 or h <= 0 or w * h > REFUSE_PIXELS:
            raise ValueError("too large")
        exif = _exif_summary(im)
        if w * h > MAX_PIXELS:
            ratio = (MAX_PIXELS / (w * h)) ** 0.5
            im.draft("RGB", (int(w * ratio), int(h * ratio)))  # JPEG는 줄인 크기로 바로 읽는다
        im.seek(0)
        out = ImageOps.exif_transpose(im)
        out = out.convert("RGBA" if "A" in out.getbands() or out.mode == "P" else "RGB")
        orientation = im.getexif().get(0x0112, 1) if hasattr(im, "getexif") else 1
    if out.width * out.height > MAX_PIXELS:
        ratio = (MAX_PIXELS / (out.width * out.height)) ** 0.5
        out = out.resize((max(1, int(out.width * ratio)), max(1, int(out.height * ratio))), Image.Resampling.LANCZOS)
    out.info["tailhop_size"] = (h, w) if orientation in (5, 6, 7, 8) else (w, h)
    out.info["tailhop_exif"] = exif
    return out


def pyramid(image: Image.Image) -> list[Image.Image]:
    levels = [image]
    while max(levels[-1].size) > PYRAMID_MIN * 2:
        levels.append(levels[-1].reduce(2))
    return levels


def play_badge(image: Image.Image) -> Image.Image:
    """영상이라는 표시: 가운데 반투명 원과 재생 삼각형."""
    out = image.convert("RGBA")
    layer = Image.new("RGBA", out.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = max(14, min(out.size) // 8)
    cx, cy = out.width // 2, out.height // 2
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(0, 0, 0, 150))
    s = r * 0.5
    d.polygon([(cx - s * 0.7, cy - s), (cx - s * 0.7, cy + s), (cx + s, cy)], fill=(255, 255, 255, 240))
    return Image.alpha_composite(out, layer)


def _rotate(image: Image.Image, quarter: int) -> Image.Image:
    turn = {1: Image.Transpose.ROTATE_270, 2: Image.Transpose.ROTATE_180, 3: Image.Transpose.ROTATE_90}
    return image.transpose(turn[quarter]) if quarter in turn else image


def _tile(image: Image.Image, px: int) -> Image.Image:
    """필름 띠 한 칸: 가운데를 정사각형으로 잘라 줄인다."""
    return ImageOps.fit(image.convert("RGB"), (px, px), Image.Resampling.LANCZOS)


def _dark_title_bar(win: tk.Misc) -> None:
    try:
        hwnd = ctypes.windll.user32.GetParent(win.winfo_id())
        on = ctypes.c_int(1)
        ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(on), ctypes.sizeof(on))
    except (AttributeError, OSError):
        pass


class _Tip:
    """아이콘 단추에 마우스를 잠깐 올리면 이름과 단축키를 보인다."""

    def __init__(self, widget, text: str) -> None:
        self.widget, self.text = widget, text
        self.win: tk.Toplevel | None = None
        self.after_id: str | None = None
        widget.bind("<Enter>", lambda _e: self._arm(), add="+")
        widget.bind("<Leave>", lambda _e: self._hide(), add="+")
        widget.bind("<ButtonPress>", lambda _e: self._hide(), add="+")

    def _arm(self) -> None:
        self._hide()
        self.after_id = self.widget.after(450, self._show)

    def _show(self) -> None:
        self.after_id = None
        if not self.widget.winfo_exists():
            return
        self.win = tk.Toplevel(self.widget)
        self.win.wm_overrideredirect(True)
        self.win.attributes("-topmost", True)
        tk.Label(self.win, text=self.text, bg="#27272A", fg=BAR_TEXT, font=("Segoe UI", 9), padx=8, pady=4).pack()
        self.win.update_idletasks()
        x = self.widget.winfo_rootx() + self.widget.winfo_width() // 2 - self.win.winfo_width() // 2
        y = self.widget.winfo_rooty() + self.widget.winfo_height() + 6
        self.win.geometry(f"+{x}+{y}")

    def _hide(self) -> None:
        if self.after_id is not None:
            self.widget.after_cancel(self.after_id)
            self.after_id = None
        if self.win is not None:
            self.win.destroy()
            self.win = None


class Lightbox(ctk.CTkToplevel):
    show_info = False  # 정보 칸·필름 띠를 켜 두었는지(창을 다시 열어도 그대로)
    show_strip = True

    def __init__(self, parent, shots: list[Shot], index: int, actions: list[Action],
                 post: Callable[[Callable[[], None]], None], icon_path=None) -> None:
        super().__init__(parent, fg_color=BG)
        self.shots, self.index, self.actions, self.post = shots, index, actions, post
        self.full: OrderedDict[str, list[Image.Image]] = OrderedDict()  # key별 피라미드(돌리기 전)
        self.levels: list[Image.Image] = []  # 지금 보이는 피라미드(돌린 뒤)
        self.size_wh = (1, 1)  # 지금 그림의 논리 크기(원본이 오면 원본 크기)
        self.scale, self.ox, self.oy = 1.0, 0.0, 0.0
        self.fitted = True  # 사용자가 아직 확대·이동하지 않았다(창 크기가 바뀌면 다시 맞춘다)
        self.loading = 0
        self.note = ""
        self.photo: ImageTk.PhotoImage | None = None
        self.drag: tuple[int, int, float, float] | None = None
        self.render_after: str | None = None
        self.hq_after: str | None = None
        self.hires_asked: set[str] = set()
        self.preloading: set[str] = set()
        self.preload_tried: set[str] = set()  # 한 번 미리 불러 본 장(실패해도 다시 하지 않는다)
        self.rotation: dict[str, int] = {}
        self.meta: dict[str, dict] = {}  # key별 원래 크기·EXIF
        self.tiles: OrderedDict[str, ImageTk.PhotoImage] = OrderedDict()
        self.strip_offset = 0.0
        self.fullscreen = False
        self.bars = True  # 막대가 보이는가(전체 화면에서 마우스를 가만두면 숨긴다)
        self.want_actual = ""  # 원래 크기를 눌렀는데 원본이 오는 중인 장
        self.last_move = time.monotonic()
        self.closed = False

        fit_geometry(self, 1280, 860, share=0.9)  # 최대화를 풀었을 때의 크기
        self.minsize(560, 400)
        self.after(10, lambda: (_dark_title_bar(self), protect(self)))
        self.after(15, self._maximize)
        if icon_path is not None:
            self.after(250, lambda: self._set_icon(icon_path))
        self._build()
        self.protocol("WM_DELETE_WINDOW", self.close)
        self._bind_keys()
        self.after(30, lambda: (self.lift(), self.focus_force()))
        self.after(250, self._tick)
        self.show(index)

    def _maximize(self) -> None:
        try:
            self.state("zoomed")
        except tk.TclError:
            pass

    def _set_icon(self, icon_path) -> None:
        try:
            self.iconbitmap(str(icon_path))
        except tk.TclError:
            pass

    # ---------- 틀 ----------
    def _tool(self, parent, name: str, command, tip: str) -> ctk.CTkButton:
        button = ctk.CTkButton(parent, text="", image=icon(name, 18, BAR_TEXT), command=command, width=36, height=36,
                               corner_radius=6, fg_color="transparent", hover_color=BAR_HOVER)
        _Tip(button, tip)
        return button

    def _build(self) -> None:
        self.top = ctk.CTkFrame(self, fg_color=BG, corner_radius=0, height=52)
        self.top.pack(fill="x")
        self.top.pack_propagate(False)
        self.title_label = ctk.CTkLabel(self.top, text="", font=font(14, "bold"), text_color=BAR_TEXT, anchor="w")
        self.title_label.pack(side="left", fill="x", expand=True, padx=(18, 8))
        self._tool(self.top, "x", self.close, t("lightbox.tip_close")).pack(side="right", padx=(2, 12))
        self.full_btn = self._tool(self.top, "expand", self.toggle_fullscreen, t("lightbox.tip_full"))
        self.full_btn.pack(side="right", padx=2)
        self._tool(self.top, "panel-bottom", self.toggle_strip, t("lightbox.tip_strip")).pack(side="right", padx=2)
        self._tool(self.top, "info", self.toggle_info, t("lightbox.tip_info")).pack(side="right", padx=2)
        ctk.CTkFrame(self.top, fg_color=BAR_BORDER, width=1, height=22).pack(side="right", padx=8)
        self._tool(self.top, "rotate-cw", lambda: self.rotate(1), t("lightbox.tip_rotate_cw")).pack(side="right",
                                                                                                     padx=2)
        self._tool(self.top, "rotate-ccw", lambda: self.rotate(-1), t("lightbox.tip_rotate_ccw")).pack(side="right",
                                                                                                        padx=2)
        ctk.CTkFrame(self.top, fg_color=BAR_BORDER, width=1, height=22).pack(side="right", padx=8)
        one = ctk.CTkButton(self.top, text="1:1", width=40, height=30, corner_radius=6, fg_color="transparent",
                            hover_color=BAR_HOVER, text_color=BAR_TEXT, font=font(12, "bold"), command=self.actual_size)
        one.pack(side="right", padx=2)
        _Tip(one, t("lightbox.tip_actual"))
        self._tool(self.top, "scan", self.fit, t("lightbox.tip_fit")).pack(side="right", padx=2)
        self._tool(self.top, "zoom-in", lambda: self.zoom_center(BUTTON_STEP), t("lightbox.tip_zoom_in")).pack(
            side="right", padx=2)
        self.pct = ctk.CTkButton(self.top, text="100%", width=58, height=30, corner_radius=6, fg_color="transparent",
                                 hover_color=BAR_HOVER, text_color=BAR_TEXT, font=font(12), command=self._pct_click)
        self.pct.pack(side="right")
        _Tip(self.pct, t("lightbox.tip_pct"))
        self._tool(self.top, "zoom-out", lambda: self.zoom_center(1 / BUTTON_STEP), t("lightbox.tip_zoom_out")).pack(
            side="right", padx=2)
        self.count = ctk.CTkLabel(self.top, text="", font=font(12), text_color=BAR_MUTED)
        self.count.pack(side="right", padx=12)

        self.bottom = ctk.CTkFrame(self, fg_color=BG, corner_radius=0, height=64)
        self.bottom.pack(side="bottom", fill="x")
        self.bottom.pack_propagate(False)
        row = ctk.CTkFrame(self.bottom, fg_color="transparent")
        row.place(relx=0.5, rely=0.5, anchor="center")
        self.action_buttons: list[tuple[Action, ctk.CTkButton]] = []
        for action in self.actions:
            button = self._button(row, action.label, action.icon, lambda a=action: self._run(a), action.style)
            self.action_buttons.append((action, button))
        self.cancel_btn = self._button(row, t("lightbox.cancel"), "x", self.close, "outline")

        k = self._get_window_scaling()
        self.strip = tk.Canvas(self, bg=BG, highlightthickness=0, bd=0, height=round((STRIP_PX + 16) * k))
        self.strip.pack(side="bottom", fill="x")
        self.strip.bind("<Configure>", lambda _e: self._draw_strip())
        self.strip.bind("<MouseWheel>", self._strip_wheel)
        self.strip.bind("<ButtonPress-1>", self._strip_click)

        self.panel = ctk.CTkScrollableFrame(self, fg_color=PANEL_BG, corner_radius=0, width=270)
        self.canvas = tk.Canvas(self, bg=BG, highlightthickness=0, bd=0, cursor="fleur")
        self.canvas.pack(fill="both", expand=True)
        self.image_item = self.canvas.create_image(0, 0, anchor="nw")
        self.text_item = self.canvas.create_text(0, 0, text="", fill=BAR_MUTED, font=("Segoe UI", 11))
        self.canvas.bind("<Configure>", lambda _e: self._on_resize())
        self.canvas.bind("<MouseWheel>", self._on_wheel)
        self.canvas.bind("<ButtonPress-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.canvas.bind("<Double-Button-1>", self._on_double)
        self.bind("<Motion>", lambda _e: self._woke(), add="+")
        # 이전/다음: 그림 위에서도 둥글게 보이도록 캔버스 도형으로 그린다(위젯은 네모 바탕이 생긴다)
        self.nav = {side: (self.canvas.create_oval(0, 0, 0, 0, fill=ROUND_BG, outline="", tags=("nav", side)),
                           self.canvas.create_line(0, 0, 0, 0, fill=BAR_TEXT, capstyle="round", joinstyle="round",
                                                   tags=("nav", side)))
                    for side in ("prev", "next")}
        for side, (oval, _) in self.nav.items():
            self.canvas.tag_bind(side, "<Enter>", lambda _e, o=oval: (self.canvas.itemconfigure(o, fill=BAR_HOVER),
                                                                     self.canvas.configure(cursor="hand2")))
            self.canvas.tag_bind(side, "<Leave>", lambda _e, o=oval: (self.canvas.itemconfigure(o, fill=ROUND_BG),
                                                                     self.canvas.configure(cursor="fleur")))
        self._layout()

    def _layout(self) -> None:
        """막대·필름 띠·정보 칸을 지금 상태대로 놓는다(전체 화면에서 막대가 숨었으면 띠와 아래 막대도 숨는다)."""
        bars = not self.fullscreen or self.bars
        for widget in (self.top, self.bottom, self.strip, self.panel):
            widget.pack_forget()
        if bars:
            self.top.pack(fill="x", before=self.canvas)
            self.bottom.pack(side="bottom", fill="x", before=self.canvas)
        if bars and self.show_strip and len(self.shots) > 1:
            self.strip.pack(side="bottom", fill="x", before=self.canvas)
        if self.show_info and not self.fullscreen:
            self.panel.pack(side="right", fill="y", before=self.canvas)

    @staticmethod
    def _button(parent, text: str, icon_name: str, command, style: str) -> ctk.CTkButton:
        colors = {"primary": ("#FAFAFA", "#E1E1E2", "#18181B", 0),
                  "danger": ("#DC2626", "#B91C1C", "#FAFAFA", 0),
                  "outline": ("transparent", BAR_HOVER, BAR_TEXT, 1)}[style]
        return ctk.CTkButton(parent, text=" " + text, image=icon(icon_name, 16, colors[2]), compound="left",
                             command=command, height=38, corner_radius=8, fg_color=colors[0], hover_color=colors[1],
                             text_color=colors[2], border_width=colors[3], border_color=BAR_BORDER,
                             font=font(13, "bold" if style != "outline" else "normal"), width=96)

    def _show_actions(self) -> None:
        """영상에만 쓰는 단추(재생)는 영상일 때만 보인다."""
        video = bool(self.shots) and self.shots[self.index].video
        for action, button in self.action_buttons:
            button.pack_forget()
        self.cancel_btn.pack_forget()
        for action, button in self.action_buttons:
            if video or not action.video_only:
                button.pack(side="left", padx=5)
        self.cancel_btn.pack(side="left", padx=5)

    def _run(self, action: Action) -> None:
        if self.shots:
            action.run(self.shots[self.index])

    def _run_key(self, key: str) -> None:
        if not self.shots:
            return
        shot = self.shots[self.index]
        for action in self.actions:
            if action.key == key and (shot.video or not action.video_only):
                action.run(shot)
                return

    def _bind_keys(self) -> None:
        keys = {"<Escape>": self._escape, "<Left>": self.prev, "<Right>": self.next, "<Prior>": self.prev,
                "<Next>": self.next, "<Home>": lambda: self.show(0), "<End>": lambda: self.show(len(self.shots) - 1),
                "<plus>": lambda: self.zoom_center(BUTTON_STEP), "<equal>": lambda: self.zoom_center(BUTTON_STEP),
                "<KP_Add>": lambda: self.zoom_center(BUTTON_STEP), "<minus>": lambda: self.zoom_center(1 / BUTTON_STEP),
                "<KP_Subtract>": lambda: self.zoom_center(1 / BUTTON_STEP), "<Key-0>": self.fit,
                "<Key-1>": self.actual_size, "<r>": lambda: self.rotate(1), "<R>": lambda: self.rotate(-1),
                "<i>": self.toggle_info, "<t>": self.toggle_strip, "<f>": self.toggle_fullscreen,
                "<F11>": self.toggle_fullscreen, "<Delete>": lambda: self._run_key("delete"),
                "<Return>": lambda: self._run_key("play"), "<space>": lambda: self._run_key("play")}
        for seq, fn in keys.items():
            self.bind(seq, lambda _e, f=fn: (f(), "break")[1])

    # ---------- 장 바꾸기 ----------
    def show(self, index: int) -> None:
        if not self.shots:
            self.close()
            return
        self.index = max(0, min(index, len(self.shots) - 1))
        shot = self.shots[self.index]
        self.title(shot.name)
        self.title_label.configure(text=shot.name)
        self.loading += 1
        self.note = ""
        self._show_actions()
        self._fill_info()
        self._scroll_strip_to_current()
        cached = self.full.get(shot.key)
        if cached is not None:
            self.full.move_to_end(shot.key)
            self._set_levels(cached, refit=True)
            self.note = t("lightbox.video") if shot.video else ""
            self._preload()
            return
        preview = shot.preview
        if preview is not None and shot.video:
            preview = play_badge(preview)
        self._set_levels([preview] if preview is not None else [], refit=True)
        self.note = t("lightbox.loading")
        self._load(shot, self.loading)
        self._render_soon()

    def _load(self, shot: Shot, token: int) -> None:
        def work() -> None:
            try:
                image = shot.load()
                info = dict(image.info) if image is not None else {}
                if image is not None and shot.video:
                    image = play_badge(image)
                result, error = (pyramid(image) if image is not None else None), ""
            except Exception as exc:  # noqa: BLE001 - 이유를 화면에 보이고 미리보기는 그대로 둔다
                result, error, info = None, str(exc) or type(exc).__name__, {}
            self.post(lambda: self._loaded(shot, token, result, error, info))

        threading.Thread(target=work, daemon=True, name="tailhop-lightbox").start()

    def _keep(self, key: str, levels: list[Image.Image]) -> None:
        current = self.shots[self.index].key if self.shots else ""
        self.full[key] = levels
        self.full.move_to_end(key)
        if current in self.full:
            self.full.move_to_end(current)  # 지금 장은 가장 나중에 지운다

        def pixels() -> int:
            return sum(lv[0].width * lv[0].height for lv in self.full.values())

        while len(self.full) > 1 and (len(self.full) > KEEP or pixels() > KEEP_PIXELS):
            self.full.popitem(last=False)

    def _loaded(self, shot: Shot, token: int, levels: list[Image.Image] | None, error: str, info: dict) -> None:
        self.preloading.discard(shot.key)
        if self.closed or not self.winfo_exists():
            return
        if levels is not None and not (self.full.get(shot.key) and levels[0].width < self.full[shot.key][0].width):
            self._keep(shot.key, levels)
            meta = self.meta.setdefault(shot.key, {})
            if info.get("tailhop_size"):
                meta["size"] = info["tailhop_size"]
            if info.get("tailhop_exif"):
                meta["exif"] = info["tailhop_exif"]
            meta.setdefault("shown", levels[0].size)
            if shot.preview is None:  # 필름 띠에 쓸 작은 그림
                shot.preview = levels[-1].copy()
                shot.preview.thumbnail((256, 256))
                self.tiles.pop(shot.key, None)
                self._draw_strip()
        if token == -1:
            self._preload()  # 다른 쪽 이웃도
            return
        if token != self.loading:
            return  # 그 사이 다른 장으로 넘어갔거나 미리 불러 둔 것(원본은 위에 넣어 두어 돌아오면 바로 보인다)
        self.note = t("lightbox.failed", error=error) if error else t("lightbox.video") if shot.video else ""
        if levels is not None:
            self._set_levels(levels, refit=self.fitted)
            if self.want_actual == shot.key:  # 원래 크기를 누른 뒤 원본이 왔다
                self.want_actual = ""
                self.zoom_center(1.0 / self.scale)
            self._fill_info()
            self._preload()
        self._render_soon()

    def _preload(self) -> None:
        """지금 장 다음과 앞을 한 장씩 미리 불러 둔다(이미 있거나 부르는 중이면 건너뛴다)."""
        if len(self.shots) < 2:
            return
        for step in (1, -1):
            shot = self.shots[(self.index + step) % len(self.shots)]
            if shot.key in self.full or shot.key in self.preloading or shot.key in self.preload_tried:
                continue
            self.preloading.add(shot.key)
            self.preload_tried.add(shot.key)
            self._load(shot, -1)
            return  # 한 번에 하나씩(끝나면 다시 부른다)

    def _set_levels(self, levels: list[Image.Image], refit: bool) -> None:
        old_w = self.size_wh[0]
        quarter = self.rotation.get(self.shots[self.index].key, 0) if self.shots else 0
        self.levels = [_rotate(lv, quarter) for lv in levels] if quarter else list(levels)
        self.size_wh = self.levels[0].size if self.levels else (1, 1)
        if refit or not self.levels:
            self.fit()
        else:  # 미리보기에서 원본으로: 보던 자리와 배율을 그대로
            self.scale *= old_w / max(1, self.size_wh[0])
            self._clamp()
            self._render_soon()

    def prev(self) -> None:
        if len(self.shots) > 1:
            self.show((self.index - 1) % len(self.shots))

    def next(self) -> None:
        if len(self.shots) > 1:
            self.show((self.index + 1) % len(self.shots))

    def remove_current(self) -> None:
        """지금 장을 뺀다(지운 뒤). 남은 것이 없으면 닫는다."""
        if not self.shots:
            return
        shot = self.shots.pop(self.index)
        self.full.pop(shot.key, None)
        self.tiles.pop(shot.key, None)
        if not self.shots:
            self.close()
            return
        self._layout()
        self.show(min(self.index, len(self.shots) - 1))

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        self.loading += 1
        self.full.clear()
        self.levels = []
        self.tiles.clear()
        self.destroy()

    # ---------- 돌리기·정보·띠·전체 화면 ----------
    def rotate(self, step: int) -> None:
        """보기만 돌린다(파일은 그대로)."""
        if not self.shots or not self.levels:
            return
        key = self.shots[self.index].key
        self.rotation[key] = (self.rotation.get(key, 0) + step) % 4
        base = self.full.get(key)
        if base is None:
            preview = self.shots[self.index].preview
            base = [play_badge(preview) if self.shots[self.index].video else preview] if preview is not None else []
        self._set_levels(base, refit=True)

    def toggle_info(self) -> None:
        Lightbox.show_info = not Lightbox.show_info
        self._layout()
        self._fill_info()

    def toggle_strip(self) -> None:
        Lightbox.show_strip = not Lightbox.show_strip
        self._layout()

    def toggle_fullscreen(self) -> None:
        self.fullscreen = not self.fullscreen
        self.attributes("-fullscreen", self.fullscreen)
        self.full_btn.configure(image=icon("shrink" if self.fullscreen else "expand", 18, BAR_TEXT))
        self.bars = True
        self.last_move = time.monotonic()
        self._layout()

    def _escape(self) -> None:
        if self.fullscreen:
            self.toggle_fullscreen()
        else:
            self.close()

    def _woke(self) -> None:
        self.last_move = time.monotonic()
        if self.fullscreen and not self.bars:
            self.bars = True
            self._layout()

    def _tick(self) -> None:
        """전체 화면에서 마우스를 가만두면 막대를 숨긴다."""
        if self.closed:
            return
        if self.fullscreen and self.bars and self.drag is None and time.monotonic() - self.last_move > HIDE_AFTER:
            self.bars = False
            self._layout()
        self.after(250, self._tick)

    def _fill_info(self) -> None:
        if not self.show_info or not self.shots:
            return
        for child in self.panel.winfo_children():
            child.destroy()
        shot = self.shots[self.index]
        meta = self.meta.get(shot.key, {})
        ctk.CTkLabel(self.panel, text=t("lightbox.info_title"), font=font(13, "bold"), text_color=BAR_TEXT,
                     anchor="w").pack(fill="x", padx=14, pady=(14, 8))
        rows = [(t("lightbox.info_name"), shot.name), *shot.details]
        if meta.get("size"):
            rows.append((t("lightbox.info_pixels"), "{} × {}".format(*meta["size"])))
        if meta.get("shown") and meta.get("shown") != meta.get("size"):
            rows.append((t("lightbox.info_shown"), "{} × {}".format(*meta["shown"])))
        exif = meta.get("exif") or {}
        for name in ("taken", "make", "model", "fnumber", "exposure", "iso", "focal"):
            if exif.get(name):
                rows.append((t(f"lightbox.exif_{name}"), exif[name]))
        if self.rotation.get(shot.key):
            rows.append((t("lightbox.info_rotated"), f"{self.rotation[shot.key] * 90}°"))
        for label, value in rows:
            ctk.CTkLabel(self.panel, text=label, font=font(11), text_color=BAR_MUTED, anchor="w").pack(
                fill="x", padx=14)
            ctk.CTkLabel(self.panel, text=value, font=font(12), text_color=BAR_TEXT, anchor="w", justify="left",
                         wraplength=230).pack(fill="x", padx=14, pady=(0, 10))

    # ---------- 필름 띠 ----------
    def _strip_metrics(self) -> tuple[float, float, float]:
        k = self._get_window_scaling()
        cell = STRIP_PX * k
        gap = STRIP_GAP * k
        return cell, gap, cell + gap

    def _scroll_strip_to_current(self) -> None:
        cell, _gap, step = self._strip_metrics()
        width = max(1, self.strip.winfo_width())
        total = step * len(self.shots)
        target = self.index * step + cell / 2 - width / 2
        self.strip_offset = max(0.0, min(max(0.0, total - width), target))
        self._draw_strip()

    def _strip_wheel(self, event) -> None:
        _cell, _gap, step = self._strip_metrics()
        width = max(1, self.strip.winfo_width())
        total = step * len(self.shots)
        self.strip_offset = max(0.0, min(max(0.0, total - width), self.strip_offset - event.delta / 120 * step * 2))
        self._draw_strip()

    def _strip_shift(self) -> float:
        _cell, _gap, step = self._strip_metrics()
        return max(0.0, (self.strip.winfo_width() - step * len(self.shots)) / 2)  # 몇 장 없으면 가운데로

    def _strip_click(self, event) -> None:
        _cell, gap, step = self._strip_metrics()
        i = int((event.x - self._strip_shift() + self.strip_offset - gap / 2) // step)
        if 0 <= i < len(self.shots) and i != self.index:
            self.show(i)

    def _tile_photo(self, shot: Shot, px: int) -> ImageTk.PhotoImage | None:
        photo = self.tiles.get(shot.key)
        if photo is None and shot.preview is not None:
            photo = ImageTk.PhotoImage(_tile(shot.preview, px))
            self.tiles[shot.key] = photo
            while len(self.tiles) > STRIP_CACHE:
                self.tiles.popitem(last=False)
        return photo

    def _draw_strip(self) -> None:
        if self.closed or not self.strip.winfo_manager():
            return
        self.strip.delete("all")
        cell, gap, step = self._strip_metrics()
        width, height = self.strip.winfo_width(), self.strip.winfo_height()
        shift = self._strip_shift()
        y = (height - cell) / 2
        first = max(0, int(self.strip_offset // step))
        last = min(len(self.shots), int((self.strip_offset + width) // step) + 2)
        k = self._get_window_scaling()
        for i in range(first, last):
            shot = self.shots[i]
            x = shift + gap / 2 + i * step - self.strip_offset
            photo = self._tile_photo(shot, round(cell))
            if photo is not None:
                self.strip.create_image(x, y, image=photo, anchor="nw")
            else:
                self.strip.create_rectangle(x, y, x + cell, y + cell, fill=ROUND_BG, outline="")
            if shot.video:
                r = 9 * k
                cx, cy = x + cell - r - 4 * k, y + cell - r - 4 * k
                self.strip.create_oval(cx - r, cy - r, cx + r, cy + r, fill="#000000", outline="")
                self.strip.create_polygon(cx - r * 0.35, cy - r * 0.5, cx - r * 0.35, cy + r * 0.5, cx + r * 0.5, cy,
                                          fill=BAR_TEXT, outline="")
            if i == self.index:
                self.strip.create_rectangle(x - 2 * k, y - 2 * k, x + cell + 2 * k, y + cell + 2 * k,
                                            outline=BAR_TEXT, width=max(2, round(2 * k)))

    # ---------- 배율·이동 ----------
    def _base(self) -> float:
        """맞춤 배율: 창에 다 들어가게(원본이 작으면 100 %)."""
        cw, ch = max(1, self.canvas.winfo_width()), max(1, self.canvas.winfo_height())
        w, h = self.size_wh
        fit = min(cw / w, ch / h)
        preview_only = not self.shots or self.shots[self.index].key not in self.full
        return fit if preview_only else min(fit, 1.0)

    def fit(self) -> None:
        self.scale = self._base()
        self.fitted = True
        self._clamp()
        self._render_soon()

    def actual_size(self) -> None:
        """그림 1픽셀 = 화면 1픽셀(원래 크기). 화면용 버전이면 원본을 받아 온다."""
        if not self.levels:
            return
        self.zoom_center(1.0 / self.scale)
        self.fitted = False
        shot = self.shots[self.index]
        if shot.hires is not None and shot.key not in self.hires_asked:
            self.want_actual = shot.key
        self._maybe_hires(force=True)

    def _pct_click(self) -> None:
        if self.scale > self._base() * 1.05 or abs(self.scale - 1.0) < 0.01:
            self.fit()
        else:
            self.actual_size()

    def zoom_at(self, x: float, y: float, factor: float) -> None:
        base = self._base()
        new = max(base * 0.5, min(max(MAX_ZOOM, base * MAX_ZOOM), self.scale * factor))
        if new == self.scale:
            return
        self.ox = x - (x - self.ox) * new / self.scale
        self.oy = y - (y - self.oy) * new / self.scale
        self.scale = new
        self.fitted = False
        self._clamp()
        self._render_soon()
        self._maybe_hires()

    def _maybe_hires(self, force: bool = False) -> None:
        """화면 1픽셀이 그림 1픽셀보다 크게(100 % 넘게) 확대했고 원본이 따로 있으면 원본을 받아 바꾼다."""
        if not self.shots or (self.scale <= 1.0 and not force):
            return
        shot = self.shots[self.index]
        if shot.hires is None or shot.key in self.hires_asked:
            return
        self.hires_asked.add(shot.key)
        self.note = t("lightbox.loading")
        self._render_soon()
        self._load(Shot(shot.key, shot.name, shot.preview, shot.hires, shot.video), self.loading)

    def zoom_center(self, factor: float) -> None:
        self.zoom_at(self.canvas.winfo_width() / 2, self.canvas.winfo_height() / 2, factor)

    def _clamp(self) -> None:
        """그림이 창보다 작은 쪽은 가운데, 큰 쪽은 가장자리가 창 안으로 들어오지 않게."""
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        w, h = self.size_wh[0] * self.scale, self.size_wh[1] * self.scale
        self.ox = (cw - w) / 2 if w <= cw else min(0.0, max(cw - w, self.ox))
        self.oy = (ch - h) / 2 if h <= ch else min(0.0, max(ch - h, self.oy))

    def _on_resize(self) -> None:
        if self.fitted:
            self.fit()
        else:
            self._clamp()
            self._render_soon()

    def _on_wheel(self, event) -> None:
        self.zoom_at(event.x, event.y, WHEEL_STEP ** (event.delta / 120))

    def _on_press(self, event) -> None:
        self.canvas.focus_set()
        tags = self.canvas.gettags("current")
        if "nav" in tags:
            self.drag = None
            if "prev" in tags:
                self.prev()
            else:
                self.next()
            return
        self.drag = (event.x, event.y, self.ox, self.oy)

    def _on_drag(self, event) -> None:
        if self.drag is None:
            return
        x0, y0, ox, oy = self.drag
        self.ox, self.oy = ox + event.x - x0, oy + event.y - y0
        if not self.fitted:
            self._clamp()
        self._render_soon()

    def _on_release(self, event) -> None:
        if self.drag is None:
            return
        x0, y0, _, _ = self.drag
        self.drag = None
        dx, dy = event.x - x0, event.y - y0
        if self.fitted and abs(dx) > SWIPE_PX and abs(dx) > abs(dy) * 1.5 and len(self.shots) > 1:
            if dx > 0:  # 맞춤 배율에서 옆으로 밀기 = 넘기기
                self.prev()
            else:
                self.next()
            return
        self._clamp()
        self._render_soon()

    def _on_double(self, event) -> None:
        if self.shots and self.shots[self.index].video and any(a.key == "play" for a in self.actions):
            self._run_key("play")
            return
        base = self._base()
        if self.scale > base * 1.05:
            self.fit()
            return
        target = 1.0 if base < 0.8 else base * 2.5  # 큰 사진은 원본 크기(100 %)로, 작은 것은 2.5배로
        self.zoom_at(event.x, event.y, target / self.scale)

    # ---------- 그리기 ----------
    def _render_soon(self) -> None:
        if self.render_after is None:
            self.render_after = self.after_idle(self._render)

    def _render(self, hq: bool = False) -> None:
        self.render_after = None
        if self.hq_after is not None:
            self.after_cancel(self.hq_after)
            self.hq_after = None
        if self.closed or not self.winfo_exists():
            return
        cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
        self.canvas.coords(self.text_item, cw / 2, ch - 22)
        self.canvas.itemconfigure(self.text_item, text=self.note)
        self.count.configure(text=f"{self.index + 1} / {len(self.shots)}" if len(self.shots) > 1 else "")
        self.pct.configure(text=f"{int(round(self.scale * 100))}%" if self.levels else "")
        if not self.levels:
            self.canvas.itemconfigure(self.image_item, image="")
            self._place_nav(cw, ch)
            return
        w, h = self.size_wh
        s = self.scale
        x0, y0 = max(0.0, -self.ox / s), max(0.0, -self.oy / s)
        x1, y1 = min(w, (cw - self.ox) / s), min(h, (ch - self.oy) / s)
        if x1 <= x0 or y1 <= y0:
            return
        dw, dh = max(1, round((x1 - x0) * s)), max(1, round((y1 - y0) * s))
        level = self.levels[0]
        for candidate in self.levels:  # 줄일 때는 배율에 맞는 작은 사본에서(빠르고 계단이 덜하다)
            if w / candidate.width <= 1 / s:
                level = candidate
        fx, fy = w / level.width, h / level.height
        per_px = s * fx  # 사본 1픽셀이 화면 몇 픽셀인가
        if per_px >= 3:
            resample = Image.Resampling.NEAREST  # 크게 확대하면 픽셀을 그대로(흐려지지 않게)
        elif hq:
            resample = Image.Resampling.LANCZOS if per_px < 1 else Image.Resampling.BICUBIC
        else:
            resample = Image.Resampling.BILINEAR
        part = level.resize((dw, dh), resample, box=(x0 / fx, y0 / fy, x1 / fx, y1 / fy))
        self.photo = ImageTk.PhotoImage(part)
        self.canvas.itemconfigure(self.image_item, image=self.photo)
        self.canvas.coords(self.image_item, round(max(self.ox, 0)), round(max(self.oy, 0)))
        self.canvas.tag_raise(self.text_item)
        self._place_nav(cw, ch)
        if not hq and resample is Image.Resampling.BILINEAR:  # 움직임이 멈추면 고품질로 다시
            self.hq_after = self.after(HQ_DELAY_MS, lambda: self._render(hq=True))

    def _place_nav(self, cw: int, ch: int) -> None:
        k = self._get_window_scaling()
        r, pad, arm = 22 * k, 18 * k, 6 * k
        state = "normal" if len(self.shots) > 1 else "hidden"
        for side, (oval, line) in self.nav.items():
            cx = pad + r if side == "prev" else cw - pad - r
            cy = ch / 2
            tip = -arm if side == "prev" else arm
            self.canvas.coords(oval, cx - r, cy - r, cx + r, cy + r)
            self.canvas.coords(line, cx - tip / 2, cy - arm, cx + tip / 2, cy, cx - tip / 2, cy + arm)
            self.canvas.itemconfigure(oval, state=state)
            self.canvas.itemconfigure(line, state=state, width=max(2, 2.2 * k))
            self.canvas.tag_raise(oval)
            self.canvas.tag_raise(line)
