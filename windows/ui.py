"""두 창(대화방, 클라우드)이 같이 쓰는 화면 도구: 색, 벡터 아이콘, 글꼴, 버튼, 크기 표시."""
from __future__ import annotations

import ctypes
import sys
from pathlib import Path

import customtkinter as ctk
from tkinterdnd2 import DND_FILES

import i18n
import vicons
from i18n import t

# shadcn/ui 'zinc' 테마의 색 토큰을 그대로 옮겼다 (라이트, 다크). 이름 옆은 shadcn 변수 이름.
MAIN_BG = ("#FFFFFF", "#09090B")  # --background
SIDEBAR_BG = ("#FAFAFA", "#18181B")  # --sidebar-background
BORDER = ("#E4E4E7", "#27272A")  # --border
TEXT = ("#09090B", "#FAFAFA")  # --foreground
SECONDARY = ("#71717A", "#A1A1AA")  # --muted-foreground
HOVER = ("#F4F4F5", "#27272A")  # --accent (ghost 버튼·줄의 hover)
SELECTED = ("#F4F4F5", "#27272A")  # --sidebar-accent (사이드바의 고른 줄)
BUBBLE = ("#F4F4F5", "#27272A")  # --muted
INK = ("#18181B", "#FAFAFA")  # --primary
INK_TEXT = ("#FAFAFA", "#18181B")  # --primary-foreground
INK_HOVER = ("#2F2F32", "#E1E1E2")  # primary/90 (bg-primary/90 hover)
MUTED_BUTTON = ("#8C8C8D", "#828283")  # primary + disabled:opacity-50
GREEN = ("#16A34A", "#22C55E")  # tailwind green-600/500 (상태 점)
RED = ("#EF4444", "#F87171")  # --destructive / red-400
AMBER = ("#D97706", "#FBBF24")  # tailwind amber-600/400
DROP = ("#F4F4F5", "#18181B")  # --muted (드롭 강조)
DROP_ROW = ("#E4E4E7", "#3F3F46")  # zinc-200/700
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
                         corner_radius=6, fg_color="transparent", hover_color=HOVER, **kw)


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


# ---------- 유리(아크릴) 창 ----------
# 창 바탕을 이 색으로 칠하고 그 색을 투명색으로 지정하면, 그 자리에 Windows 아크릴 흐림(뒤 화면을 흐리게 비춤)이 보인다.
# 카드(사이드바·대화방)는 불투명하게 그리고, 카드 사이 틈과 가장자리만 유리가 된다. 이 색은 다른 곳에 쓰지 않는다
# (같은 색 픽셀은 모두 뚫리고 클릭도 뒤 창으로 간다).
GLASS_KEY = ("#F2F3F5", "#131315")
_GLASS_ALPHA = (0x30, 0x70)  # 흐림 위에 얹는 색의 진하기(밝은, 어두운)
CARD_BG = MAIN_BG  # --card
GUTTER = 12  # 카드 사이 틈(유리가 보이는 곳)


class _Accent(ctypes.Structure):
    _fields_ = [("AccentState", ctypes.c_int), ("AccentFlags", ctypes.c_int), ("GradientColor", ctypes.c_uint),
                ("AnimationId", ctypes.c_int)]


class _CompositionData(ctypes.Structure):
    _fields_ = [("Attribute", ctypes.c_int), ("Data", ctypes.c_void_p), ("SizeOfData", ctypes.c_size_t)]


def glass_supported() -> bool:
    """Windows 11(빌드 22000+)에서만 쓴다. 10에서는 아크릴 흐림이 끌 때 느리고 깜박인다."""
    return sys.platform == "win32" and sys.getwindowsversion().build >= 22000


def is_dark() -> bool:
    return ctk.get_appearance_mode() == "Dark"


def apply_glass(win) -> bool:
    """창 바탕을 유리로. 안 되면(Windows 10 등) 사이드바 색으로 칠하고 False."""
    dark = is_dark()
    win.update_idletasks()
    hwnd = ctypes.windll.user32.GetAncestor(win.winfo_id(), 2)  # GA_ROOT
    mode = ctypes.c_int(1 if dark else 0)
    ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 20, ctypes.byref(mode), 4)  # 제목 줄도 어둡게
    corner = ctypes.c_int(2)
    ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, 33, ctypes.byref(corner), 4)  # 둥근 모서리
    if not glass_supported():
        win.configure(fg_color=SIDEBAR_BG)
        return False
    key = GLASS_KEY[1 if dark else 0]
    win.configure(fg_color=GLASS_KEY)
    win.wm_attributes("-transparentcolor", key)
    r, g, b = int(key[1:3], 16), int(key[3:5], 16), int(key[5:7], 16)
    color = (_GLASS_ALPHA[1 if dark else 0] << 24) | (b << 16) | (g << 8) | r  # AABBGGRR
    accent = _Accent(4, 2, color, 0)  # ACCENT_ENABLE_ACRYLICBLURBEHIND
    data = _CompositionData(19, ctypes.cast(ctypes.pointer(accent), ctypes.c_void_p), ctypes.sizeof(accent))
    return bool(ctypes.windll.user32.SetWindowCompositionAttribute(hwnd, ctypes.byref(data)))


def card(parent, **kw) -> ctk.CTkFrame:
    """shadcn Card: 둥근 모서리(rounded-xl) + 테두리, 불투명."""
    return ctk.CTkFrame(parent, fg_color=kw.pop("fg_color", CARD_BG), corner_radius=12, border_width=1,
                        border_color=BORDER, **kw)
