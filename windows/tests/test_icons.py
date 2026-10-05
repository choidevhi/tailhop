"""벡터 아이콘: SVG 읽기, 크기별 그리기, 앱이 쓰는 아이콘이 모두 있는지, Android 변환 결과가 원본과 같은지."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

import vicons

ROOT = Path(__file__).resolve().parents[1]


class IconTests(unittest.TestCase):
    def test_path_parser(self):
        cmds = vicons._path_cmds("m16 3 4 4-4 4M20 7H4a2 2 0 0 0-2 2v.01")
        self.assertEqual(cmds[:4], [("M", 16, 3), ("L", 20, 7), ("L", 16, 11), ("M", 20, 7)])
        self.assertEqual(cmds[5], ("A", 2, 2, 0, 0, 0, 2, 9))
        self.assertAlmostEqual(cmds[6][2], 9.01)
        # 아크 플래그를 붙여 쓴 형태와 Q, S도 읽는다.
        self.assertEqual(vicons._path_cmds("M0 0a1 1 0 011 1")[1], ("A", 1, 1, 0, 0, 1, 1, 1))
        self.assertEqual(vicons._path_cmds("M0 0Q3 3 6 0")[1][0], "C")

    def test_render_sizes_and_color(self):
        svg = vicons.load("plus")
        for size in (16, 20, 21, 28, 36):
            img = vicons.render(svg, size, "#FF0000")
            self.assertEqual(img.size, (size, size))
            alpha = img.getchannel("A")
            self.assertGreater(alpha.getextrema()[1], 200)  # 선이 또렷하다
            self.assertEqual(img.getpixel((size // 2, size // 2))[:3], (255, 0, 0))
            self.assertEqual(img.getpixel((0, 0))[3], 0)  # 바탕은 투명

    def test_app_icons_exist(self):
        source = (ROOT / "app.py").read_text("utf-8")
        names = set(re.findall(r'icon\("([a-z-]+)"', source))
        names |= set(re.findall(r'(?:ghost_button|round_icon|CircleButton)\([^,]+, "([a-z-]+)"', source))
        names |= {"smartphone", "laptop", "tailhop-logo"}
        self.assertGreater(len(names), 10)
        for name in names:
            self.assertTrue((vicons.ICON_DIR / f"{name}.svg").exists(), name)
        # 화면 글자에 이모지·기호 아이콘이 남지 않았다.
        self.assertIsNone(re.search("[\U0001F300-\U0001FAFF\u2190-\u21ff\u22ef\u25a0-\u25ff\u2713\u26a0\uff0b]", source))

    def test_android_vectors_match_sources(self):
        """저장소의 Android 아이콘이 build_icons.py로 지금 원본에서 만든 결과와 같다(원본만 고치고 안 돌린 경우를 잡는다)."""
        import importlib.util
        spec = importlib.util.spec_from_file_location("build_icons", ROOT / "assets" / "build_icons.py")
        b = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(b)
        for name, src in b.ANDROID.items():
            committed = re.findall(r'pathData="([^"]+)"', (b.DRAWABLE / f"{name}.xml").read_text("utf-8"))
            _, _, shapes = vicons.normalize(vicons.load(src))
            self.assertEqual(committed, [b.path_data(s.cmds) for s in shapes], name)

    def test_ico_has_every_size(self):
        from PIL import Image
        with Image.open(ROOT / "assets" / "tailhop.ico") as ico:
            self.assertTrue({(16, 16), (24, 24), (32, 32), (48, 48), (256, 256)} <= set(ico.info["sizes"]))
