"""벡터 아이콘(SVG, 24×24 격자의 선 아이콘)을 Pillow만으로 그린다.

PC 앱은 화면 배율(100 %, 125 %, 150 %, 175 %, 200 % …)에 맞는 정확한 픽셀 크기로 그때그때 그려 흐리지 않다.
외부 SVG 렌더러(cairo 등)를 쓰지 않으려고 아이콘이 쓰는 SVG 부분만 읽는다:
path(M L H V C S Q T A Z, 상대·절대), line, polyline, polygon, rect(rx), circle, ellipse,
stroke/fill 색, stroke-width, 둥근 끝·이음. 아이콘 원본은 icons/ (Lucide, ISC 라이선스, THIRD_PARTY_NOTICES.md).

같은 해석 결과(normalize)를 icons/build_icons.py가 Android VectorDrawable과 .ico로도 바꾼다.
"""
from __future__ import annotations

import math
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw

_NUM = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
_TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


@dataclass
class Shape:
    """한 요소: 절대 좌표 명령 목록 [("M", x, y), ("L", x, y), ("C", x1, y1, x2, y2, x, y),
    ("A", rx, ry, rot, large, sweep, x, y), ("Z",)]과 칠·선 정보. 색 None = currentColor."""

    cmds: list[tuple] = field(default_factory=list)
    stroke: str | None = "current"
    fill: str | None = None
    width: float = 2.0


def _color(value: str | None, inherited: str | None) -> str | None:
    if value is None:
        return inherited
    value = value.strip()
    if value in ("none", "transparent"):
        return None
    if value == "currentColor":
        return "current"
    return value


def _path_cmds(d: str) -> list[tuple]:
    """SVG path d → 절대 좌표 명령(M, L, C, Q→C, A, Z). 아크 플래그가 붙어 쓰인 경우(`0 01 1 1`)도 읽는다."""
    out: list[tuple] = []
    pos = 0
    x = y = sx = sy = 0.0
    last_ctrl: tuple[float, float] | None = None
    last_q: tuple[float, float] | None = None
    cmd = ""

    def number() -> float:
        nonlocal pos
        m = _NUM.match(d, pos)
        while m is None or m.start() != pos:
            if pos >= len(d):
                raise ValueError("path ended")
            if d[pos] in " ,\t\r\n":
                pos += 1
                m = _NUM.match(d, pos)
                continue
            raise ValueError(f"number expected at {pos}: {d[pos:pos + 10]!r}")
        pos = m.end()
        return float(m.group())

    def flag() -> int:
        nonlocal pos
        while pos < len(d) and d[pos] in " ,\t\r\n":
            pos += 1
        if pos < len(d) and d[pos] in "01":
            pos += 1
            return int(d[pos - 1])
        raise ValueError("arc flag expected")

    def more() -> bool:
        nonlocal pos
        while pos < len(d) and d[pos] in " ,\t\r\n":
            pos += 1
        return pos < len(d) and not d[pos].isalpha()

    while True:
        while pos < len(d) and d[pos] in " ,\t\r\n":
            pos += 1
        if pos >= len(d):
            break
        if d[pos].isalpha():
            cmd = d[pos]
            pos += 1
        elif not cmd:
            raise ValueError("path must start with a command")
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = (x, y) if rel else (0.0, 0.0)
        if c == "Z":
            out.append(("Z",))
            x, y = sx, sy
            last_ctrl = last_q = None
            continue
        if c == "M":
            x, y = ox + number(), oy + number()
            sx, sy = x, y
            out.append(("M", x, y))
            cmd = "l" if rel else "L"  # 이어지는 좌표 쌍은 선
            last_ctrl = last_q = None
            continue
        if c == "L":
            x, y = ox + number(), oy + number()
            out.append(("L", x, y))
            last_ctrl = last_q = None
        elif c == "H":
            x = ox + number()
            out.append(("L", x, y))
            last_ctrl = last_q = None
        elif c == "V":
            y = oy + number()
            out.append(("L", x, y))
            last_ctrl = last_q = None
        elif c in "CS":
            if c == "C":
                x1, y1 = ox + number(), oy + number()
            else:
                x1, y1 = (2 * x - last_ctrl[0], 2 * y - last_ctrl[1]) if last_ctrl else (x, y)
            x2, y2 = ox + number(), oy + number()
            x, y = ox + number(), oy + number()
            out.append(("C", x1, y1, x2, y2, x, y))
            last_ctrl, last_q = (x2, y2), None
        elif c in "QT":
            if c == "Q":
                qx, qy = ox + number(), oy + number()
            else:
                qx, qy = (2 * x - last_q[0], 2 * y - last_q[1]) if last_q else (x, y)
            ex, ey = ox + number(), oy + number()
            out.append(("C", x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y),
                        ex + 2 / 3 * (qx - ex), ey + 2 / 3 * (qy - ey), ex, ey))
            x, y = ex, ey
            last_q, last_ctrl = (qx, qy), None
        elif c == "A":
            rx, ry, rot = number(), number(), number()
            large, sweep = flag(), flag()
            x, y = ox + number(), oy + number()
            out.append(("A", abs(rx), abs(ry), rot, large, sweep, x, y))
            last_ctrl = last_q = None
        else:
            raise ValueError(f"unsupported path command {cmd}")
        if not more():
            continue
    return out


def _rect_cmds(x: float, y: float, w: float, h: float, rx: float, ry: float) -> list[tuple]:
    if rx <= 0 and ry <= 0:
        return [("M", x, y), ("L", x + w, y), ("L", x + w, y + h), ("L", x, y + h), ("Z",)]
    rx = min(rx or ry, w / 2)
    ry = min(ry or rx, h / 2)
    return [("M", x + rx, y), ("L", x + w - rx, y), ("A", rx, ry, 0, 0, 1, x + w, y + ry),
            ("L", x + w, y + h - ry), ("A", rx, ry, 0, 0, 1, x + w - rx, y + h),
            ("L", x + rx, y + h), ("A", rx, ry, 0, 0, 1, x, y + h - ry),
            ("L", x, y + ry), ("A", rx, ry, 0, 0, 1, x + rx, y), ("Z",)]


def _ellipse_cmds(cx: float, cy: float, rx: float, ry: float) -> list[tuple]:
    return [("M", cx - rx, cy), ("A", rx, ry, 0, 1, 0, cx + rx, cy), ("A", rx, ry, 0, 1, 0, cx - rx, cy), ("Z",)]


def _f(el: ET.Element, name: str, default: float = 0.0) -> float:
    v = el.get(name)
    return float(v) if v not in (None, "") else default


def normalize(svg: str) -> tuple[float, float, list[Shape]]:
    """SVG 문자열 → (viewBox 너비, 높이, 모양 목록)."""
    root = ET.fromstring(svg)
    vb = [float(v) for v in (root.get("viewBox") or "0 0 24 24").replace(",", " ").split()]
    shapes: list[Shape] = []

    def walk(el: ET.Element, stroke: str | None, fill: str | None, width: float) -> None:
        stroke = _color(el.get("stroke"), stroke)
        fill = _color(el.get("fill"), fill)
        width = _f(el, "stroke-width", width)
        tag = el.tag.split("}")[-1]
        cmds: list[tuple] | None = None
        if tag == "path":
            cmds = _path_cmds(el.get("d", ""))
        elif tag == "line":
            cmds = [("M", _f(el, "x1"), _f(el, "y1")), ("L", _f(el, "x2"), _f(el, "y2"))]
        elif tag in ("polyline", "polygon"):
            nums = [float(n) for n in _NUM.findall(el.get("points", ""))]
            pts = list(zip(nums[::2], nums[1::2]))
            cmds = [("M", *pts[0])] + [("L", *p) for p in pts[1:]] + ([("Z",)] if tag == "polygon" else [])
        elif tag == "rect":
            cmds = _rect_cmds(_f(el, "x"), _f(el, "y"), _f(el, "width"), _f(el, "height"),
                              _f(el, "rx", _f(el, "ry")), _f(el, "ry", _f(el, "rx")))
        elif tag == "circle":
            r = _f(el, "r")
            cmds = _ellipse_cmds(_f(el, "cx"), _f(el, "cy"), r, r)
        elif tag == "ellipse":
            cmds = _ellipse_cmds(_f(el, "cx"), _f(el, "cy"), _f(el, "rx"), _f(el, "ry"))
        if cmds:
            shapes.append(Shape(cmds, stroke, fill, width))
        for child in el:
            walk(child, stroke, fill, width)

    walk(root, None, "#000000", 1.0)
    return vb[2], vb[3], shapes


# ---------- 평면화(선분으로) ----------
def _arc_points(x0, y0, rx, ry, rot, large, sweep, x, y) -> list[tuple[float, float]]:
    """SVG 끝점 아크 → 점 목록(시작점 제외). W3C SVG 부록 F.6."""
    if rx == 0 or ry == 0 or (x0 == x and y0 == y):
        return [(x, y)]
    phi = math.radians(rot)
    cos, sin = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x) / 2, (y0 - y) / 2
    x1, y1 = cos * dx + sin * dy, -sin * dx + cos * dy
    lam = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    den = rx * rx * y1 * y1 + ry * ry * x1 * x1
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cx1, cy1 = co * rx * y1 / ry, -co * ry * x1 / rx
    cx, cy = cos * cx1 - sin * cy1 + (x0 + x) / 2, sin * cx1 + cos * cy1 + (y0 + y) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry)
    dt = ang((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(4, int(abs(dt) / (math.pi / 32)) + 1)
    pts = []
    for i in range(1, n + 1):
        t = t1 + dt * i / n
        ex, ey = rx * math.cos(t), ry * math.sin(t)
        pts.append((cos * ex - sin * ey + cx, sin * ex + cos * ey + cy))
    pts[-1] = (x, y)
    return pts


def subpaths(cmds: list[tuple]) -> list[tuple[list[tuple[float, float]], bool]]:
    """명령 → [(점 목록, 닫힘)]."""
    out: list[tuple[list[tuple[float, float]], bool]] = []
    pts: list[tuple[float, float]] = []
    closed = False
    for c in cmds:
        if c[0] == "M":
            if pts:
                out.append((pts, closed))
            pts, closed = [(c[1], c[2])], False
        elif c[0] == "L":
            pts.append((c[1], c[2]))
        elif c[0] == "C":
            x0, y0 = pts[-1]
            _, x1, y1, x2, y2, x, y = c
            n = 24
            for i in range(1, n + 1):
                t = i / n
                mt = 1 - t
                pts.append((mt ** 3 * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t ** 3 * x,
                            mt ** 3 * y0 + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t ** 3 * y))
        elif c[0] == "A":
            x0, y0 = pts[-1]
            pts.extend(_arc_points(x0, y0, *c[1:]))
        elif c[0] == "Z":
            closed = True
            out.append((pts, True))
            pts = [pts[0]]
            closed = False
    if len(pts) > 1 or (pts and not out):
        out.append((pts, closed))
    return out


# ---------- 그리기 ----------
def _mask(shape: Shape, size: int, vw: float, vh: float, ss: int, what: str) -> Image.Image:
    big = size * ss
    sx, sy = big / vw, big / vh
    img = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(img)
    paths = subpaths(shape.cmds)
    if what == "fill":
        for pts, _closed in paths:
            if len(pts) >= 3:
                d.polygon([(x * sx, y * sy) for x, y in pts], fill=255)
    else:
        w = shape.width * sx
        r = w / 2
        for pts, closed in paths:
            p = [(x * sx, y * sy) for x, y in pts]
            if closed and p[0] != p[-1]:
                p.append(p[0])
            if len(p) > 1 and any(q != p[0] for q in p):
                d.line(p, fill=255, width=max(1, round(w)), joint="curve")
            # 둥근 끝(닫힌 경로는 시작 이음도 둥글게)
            for x, y in (p[0], p[-1]):
                d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    return img.reduce(ss)


def _rgba(color: str) -> tuple[int, int, int, int]:
    c = color.lstrip("#")
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    if len(c) == 6:
        c += "ff"
    return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), int(c[6:8], 16)


def render(svg: str, size: int, color: str = "#000000", ss: int | None = None) -> Image.Image:
    """size×size RGBA 이미지. currentColor 부분은 color로 칠한다. 4–8배로 크게 그린 뒤 줄여(면적 평균) 가장자리를 부드럽게."""
    vw, vh, shapes = normalize(svg)
    ss = ss or (8 if size <= 48 else 4)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for shape in shapes:
        for what, col in (("fill", shape.fill), ("stroke", shape.stroke)):
            if col is None:
                continue
            rgba = _rgba(color if col == "current" else col)
            mask = _mask(shape, size, vw, vh, ss, what)
            if rgba[3] != 255:
                mask = mask.point(lambda v, a=rgba[3]: v * a // 255)
            layer = Image.new("RGBA", (size, size), rgba[:3] + (255,))
            layer.putalpha(mask)
            out = Image.alpha_composite(out, layer)
    return out


ICON_DIR = Path(__file__).resolve().parent / "assets" / "icons"


def load(name: str, base: Path | None = None) -> str:
    return ((base or ICON_DIR) / f"{name}.svg").read_text("utf-8")
