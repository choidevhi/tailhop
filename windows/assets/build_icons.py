"""아이콘 만들기(다시 돌리면 같은 결과). 원본은 assets/icons/*.svg 하나뿐이다.

- PC: assets/tailhop.ico(16–256 각 크기를 따로 그림), assets/tailhop.png. 화면 아이콘은 앱이 실행 중에
  vicons로 화면 배율에 맞춰 그리므로 미리 만든 PNG가 없다.
- Android: res/drawable의 ic_*.xml(VectorDrawable, 선 아이콘), 적응형 아이콘 앞면·단색 레이어, 알림 아이콘.
두 앱 모두 Lucide와 같은 24 격자·선 굵기 2·둥근 끝/이음을 쓴다.

실행: cd windows && .venv\\Scripts\\python assets\\build_icons.py
"""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from PIL import Image  # noqa: E402

import vicons  # noqa: E402

ICONS = HERE / "icons"
DRAWABLE = HERE.parents[1] / "android" / "app" / "src" / "main" / "res" / "drawable"
ICO_SIZES = (16, 20, 24, 32, 40, 48, 64, 128, 256)

# Android 이름 → Lucide 원본
ANDROID = {
    "ic_arrow_down": "arrow-down",
    "ic_arrow_up": "arrow-up",
    "ic_back": "chevron-left",
    "ic_bubble": "message-circle",
    "ic_doc": "file",
    "ic_laptop": "laptop",
    "ic_more": "ellipsis",
    "ic_plus": "plus",
    "ic_qr": "qr-code",
    "ic_stat": "arrow-right-left",  # 알림 작은 아이콘(단색)
}
HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<!-- build_icons.py가 만든 파일(원본: windows/assets/icons/{src}.svg, Lucide ISC). 직접 고치지 않는다. -->\n'


def n(v: float) -> str:
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_data(cmds: list[tuple]) -> str:
    out = []
    for c in cmds:
        if c[0] in "ML":
            out.append(f"{c[0]}{n(c[1])},{n(c[2])}")
        elif c[0] == "C":
            out.append("C" + " ".join(f"{n(c[i])},{n(c[i + 1])}" for i in (1, 3, 5)))
        elif c[0] == "A":
            _, rx, ry, rot, large, sweep, x, y = c
            out.append(f"A{n(rx)},{n(ry)} {n(rot)} {large},{sweep} {n(x)},{n(y)}")
        else:
            out.append("Z")
    return "".join(out)


def vector_paths(svg: str, color: str | None, indent: str = "    ") -> str:
    """모양 → <path>. color가 있으면 currentColor 대신 그 색(Android는 레이아웃의 tint로 칠한다)."""
    _, _, shapes = vicons.normalize(svg)
    lines = []
    for s in shapes:
        attrs = [f'android:pathData="{path_data(s.cmds)}"']
        if s.fill is not None:
            attrs.append(f'android:fillColor="{_argb(color if s.fill == "current" else s.fill)}"')
        if s.stroke is not None:
            attrs += [f'android:strokeColor="{_argb(color if s.stroke == "current" else s.stroke)}"',
                      f'android:strokeWidth="{n(s.width)}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        lines.append(f"{indent}<path " + f"\n{indent}    ".join(attrs) + " />")
    return "\n".join(lines)


def _argb(c: str) -> str:
    r, g, b, a = vicons._rgba(c)
    return f"#{a:02X}{r:02X}{g:02X}{b:02X}"


def write(path: Path, text: str) -> None:
    path.write_text(text, "utf-8", newline="\n")
    print("wrote", path.relative_to(HERE.parents[1]))


def android() -> None:
    for name, src in ANDROID.items():
        svg = vicons.load(src, ICONS)
        body = vector_paths(svg, "#FFFFFF")
        write(DRAWABLE / f"{name}.xml", HEAD.format(src=src) +
              '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
              '    android:width="24dp" android:height="24dp"\n'
              '    android:viewportWidth="24" android:viewportHeight="24">\n' + body + "\n</vector>\n")
    # 적응형 아이콘(108 격자, 안전 영역 지름 66): 화살표 24 격자를 2배로 가운데(30,30)에 둔다.
    mark = vicons.load("arrow-right-left", ICONS)
    _, _, shapes = vicons.normalize(mark)
    two_tone = []
    for i, s in enumerate(shapes):
        color = "#FF408CFF" if i < 2 else "#FF40D2A0"  # 위 화살표 파랑, 아래 초록(PC 아이콘과 같음)
        two_tone.append(f'        <path android:pathData="{path_data(s.cmds)}"\n'
                        f'            android:strokeColor="{color}" android:strokeWidth="1.9"\n'
                        '            android:strokeLineCap="round" android:strokeLineJoin="round" />')
    group = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
             '    android:width="108dp" android:height="108dp"\n'
             '    android:viewportWidth="108" android:viewportHeight="108">\n'
             '    <group android:scaleX="2" android:scaleY="2" android:translateX="30" android:translateY="30">\n'
             "{paths}\n    </group>\n</vector>\n")
    write(DRAWABLE / "ic_launcher_fg.xml", HEAD.format(src="arrow-right-left") + group.format(paths="\n".join(two_tone)))
    mono = vector_paths(mark, "#FFFFFF", indent="        ").replace('android:strokeWidth="2"', 'android:strokeWidth="1.9"')
    write(DRAWABLE / "ic_launcher_mono.xml", HEAD.format(src="arrow-right-left") + group.format(paths=mono))


def pc() -> None:
    logo = vicons.load("tailhop-logo", ICONS)
    frames = [vicons.render(logo, s) for s in ICO_SIZES]
    big = frames[-1]
    big.save(HERE / "tailhop.ico", format="ICO", sizes=[(s, s) for s in ICO_SIZES], append_images=frames[:-1])
    big.save(HERE / "tailhop.png")
    print("wrote windows/assets/tailhop.ico, tailhop.png")


if __name__ == "__main__":
    pc()
    android()
