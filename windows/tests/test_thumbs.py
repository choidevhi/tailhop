"""thumbs.py: 미리보기 만들기, 크기 상한, 캐시, 정리. 임시 폴더만 쓴다(실제 %LOCALAPPDATA%\\TailHop\\thumbs는 건드리지 않는다)."""
from __future__ import annotations

import os
import tempfile
import threading
import time
import unittest
from pathlib import Path

from PIL import Image

import thumbs


class ThumbTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.cache = self.root / "cache"

    def tearDown(self):
        self.tmp.cleanup()

    def image(self, name: str, size=(1600, 1200), color=(200, 30, 30)) -> Path:
        path = self.root / name
        Image.new("RGB", size, color).save(path)
        return path

    def test_previewable_by_extension(self):
        for name in ("a.JPG", "b.png", "c.webp", "d.mp4", "e.MOV", "f.heic"):
            self.assertTrue(thumbs.previewable(name), name)
        for name in ("a.txt", "b.zip", "c.exe", "noext"):
            self.assertFalse(thumbs.previewable(name), name)

    def test_image_thumbnail_fits_box_and_keeps_ratio(self):
        out = thumbs.make(self.image("사진.jpg"), self.cache)
        self.assertIsNotNone(out)
        self.assertEqual(out.parent, self.cache)
        with Image.open(out) as im:
            self.assertEqual(im.size, (480, 360))
            for got, want in zip(im.getpixel((10, 10))[:3], (200, 30, 30)):  # JPEG라 조금 달라도 된다
                self.assertAlmostEqual(got, want, delta=6)

    def test_exif_rotation_is_applied(self):
        path = self.root / "rotated.jpg"
        exif = Image.Exif()
        exif[0x0112] = 6  # 시계 방향 90도로 보여야 함
        Image.new("RGB", (800, 400), (0, 0, 255)).save(path, exif=exif)
        with Image.open(thumbs.make(path, self.cache)) as im:
            self.assertGreater(im.height, im.width)

    def test_cache_is_reused_and_refreshed_when_file_changes(self):
        path = self.image("a.png")
        first = thumbs.make(path, self.cache)
        self.assertEqual(thumbs.make(path, self.cache), first)
        time.sleep(0.02)
        Image.new("RGB", (300, 300), (0, 255, 0)).save(path)
        second = thumbs.make(path, self.cache)
        self.assertNotEqual(second, first)
        with Image.open(second) as im:
            self.assertEqual(im.getpixel((5, 5))[:3], (0, 255, 0))

    def test_too_many_pixels_is_refused(self):
        path = self.image("huge.png", size=(400, 300))
        old = thumbs.MAX_PIXELS
        thumbs.MAX_PIXELS = 100_000
        try:
            self.assertIsNone(thumbs.make(path, self.cache))
        finally:
            thumbs.MAX_PIXELS = old

    def test_broken_or_missing_or_unsupported(self):
        broken = self.root / "broken.jpg"
        broken.write_bytes(b"\xff\xd8\xff\xe0 not really a jpeg")
        self.assertIsNone(thumbs.make(broken, self.cache))
        self.assertIsNone(thumbs.make(self.root / "missing.png", self.cache))
        text = self.root / "note.txt"
        text.write_text("hi", "utf-8")
        self.assertIsNone(thumbs.make(text, self.cache))
        self.assertFalse(any(self.cache.glob("*.tmp")) if self.cache.exists() else False)

    def test_shell_thumbnail_reads_a_bitmap(self):
        import ctypes

        ctypes.windll.ole32.CoInitializeEx(None, 0x2)
        image = thumbs._from_shell(self.image("shell.png", size=(640, 480), color=(10, 200, 10)))
        if image is None:
            self.skipTest("no shell thumbnail handler on this machine")
        self.assertLessEqual(image.width, thumbs.BOX[0])
        r, g, b = image.convert("RGB").getpixel((image.width // 2, image.height // 2))
        self.assertGreater(g, 150)
        self.assertLess(r, 80)

    def test_play_badge_marks_center(self):
        image = thumbs._play_badge(Image.new("RGB", (200, 100), (0, 0, 0)))
        self.assertEqual(image.size, (200, 100))
        self.assertGreater(image.getpixel((102, 50))[0], 200)  # 가운데 흰 삼각형

    def test_prune_removes_only_old_files(self):
        self.cache.mkdir()
        old, new = self.cache / "old.png", self.cache / "new.png"
        old.write_bytes(b"x")
        new.write_bytes(b"x")
        past = time.time() - 40 * 86400
        os.utime(old, (past, past))
        self.assertEqual(thumbs.prune(self.cache), 1)
        self.assertEqual([x.name for x in self.cache.iterdir()], ["new.png"])

    def test_worker_reports_each_request_once(self):
        path = self.image("w.png")
        results: list[tuple[Path, Path | None]] = []
        done = threading.Event()

        def finished(src, out):
            results.append((src, out))
            done.set()

        worker = thumbs.Worker(finished, self.cache)
        worker.request(path)
        self.assertTrue(done.wait(10))
        self.assertEqual(results[0][0], path)
        self.assertTrue(results[0][1].exists())


if __name__ == "__main__":
    unittest.main()
