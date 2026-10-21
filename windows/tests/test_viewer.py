"""사진 보기(라이트박스)·영상 재생기(2.2.1): 그림 풀기, 돌리기, 이어 보기 열쇠, 창 크기 맞춤, 라이트박스 조작."""
import io
import queue
import sys
import time
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import lightbox  # noqa: E402
import player  # noqa: E402


_ROOT = None


def shared_root():
    """창 시험 전체가 Tk 하나를 같이 쓴다(아이콘 캐시가 첫 Tk에 묶여 있어 새로 만들면 그림을 못 찾는다)."""
    global _ROOT
    if _ROOT is None:
        import customtkinter as ctk
        _ROOT = ctk.CTk()
        _ROOT.withdraw()
    return _ROOT


def jpeg(w: int, h: int, orientation: int = 1, model: str = "") -> bytes:
    im = Image.new("RGB", (w, h), (200, 30, 30))
    exif = Image.Exif()
    exif[0x0112] = orientation
    if model:
        exif[0x0110] = model
    buf = io.BytesIO()
    im.save(buf, "JPEG", exif=exif)
    return buf.getvalue()


class DecodeTests(unittest.TestCase):
    def test_keeps_original_size_and_exif(self):
        out = lightbox.decode(jpeg(300, 200, model="TestCam"))
        self.assertEqual(out.size, (300, 200))
        self.assertEqual(out.info["tailhop_size"], (300, 200))
        self.assertEqual(out.info["tailhop_exif"].get("model"), "TestCam")

    def test_exif_rotation_swaps_reported_size(self):
        out = lightbox.decode(jpeg(300, 200, orientation=6))
        self.assertEqual(out.size, (200, 300))
        self.assertEqual(out.info["tailhop_size"], (200, 300))

    def test_refuses_huge(self):
        buf = io.BytesIO()
        Image.new("1", (20000, 10000)).save(buf, "PNG")
        with self.assertRaises(ValueError):
            lightbox.decode(buf.getvalue())

    def test_rotate_and_tile(self):
        im = Image.new("RGB", (40, 20))
        self.assertEqual(lightbox._rotate(im, 1).size, (20, 40))
        self.assertEqual(lightbox._rotate(im, 2).size, (40, 20))
        self.assertEqual(lightbox._rotate(im, 0).size, (40, 20))
        self.assertEqual(lightbox._tile(im, 16).size, (16, 16))


class PlayerHelperTests(unittest.TestCase):
    def test_resume_key_is_salted_and_stable(self):
        settings: dict = {}
        a = player.resume_key(settings, "LOCAL|/a.mp4|10")
        self.assertEqual(a, player.resume_key(settings, "LOCAL|/a.mp4|10"))
        self.assertNotEqual(a, player.resume_key(settings, "LOCAL|/b.mp4|10"))
        self.assertEqual(len(settings["resume_salt"]), 64)
        other: dict = {}
        self.assertNotEqual(a, player.resume_key(other, "LOCAL|/a.mp4|10"))  # 설치마다 다르다
        self.assertNotIn("a.mp4", a)

    def test_clock(self):
        self.assertEqual(player.clock(None), "--:--")
        self.assertEqual(player.clock(65), "1:05")
        self.assertEqual(player.clock(3725), "1:02:05")

    def test_mpv_bundled(self):
        self.assertIsNotNone(player.mpv_path(), "tools/fetch_mpv.py 를 먼저 실행")


class LightboxUiTests(unittest.TestCase):
    """실제 창을 띄워 조작한다(화면이 있는 PC에서)."""

    @classmethod
    def setUpClass(cls):
        cls.root = shared_root()
        cls.calls = queue.Queue()  # 작업 스레드가 넘긴 일(앱의 이벤트 큐처럼 이 스레드에서 돌린다)

    def drain(self) -> None:
        while not self.calls.empty():
            self.calls.get_nowait()()
        self.root.update()

    def pump(self, n: int = 40) -> None:
        for _ in range(n):
            self.drain()

    def make(self, count: int = 3, video_at: int = -1):
        ran = []
        shots = [lightbox.Shot(str(i), f"p{i}.jpg", Image.new("RGB", (64, 48), (i * 40, 0, 0)),
                               (lambda i=i: lightbox.decode(jpeg(1600 + i, 1200))), video=i == video_at,
                               details=[("Size", "1 KB")])
                 for i in range(count)]
        actions = [lightbox.Action("Play", "play", lambda s: ran.append(("play", s.key)), key="play", video_only=True),
                   lightbox.Action("Delete", "trash-2", lambda s: ran.append(("delete", s.key)), "danger",
                                   key="delete")]
        box = lightbox.Lightbox(self.root, shots, 0, actions, self.calls.put)
        self.pump(80)
        return box, ran

    def wait_loaded(self, box, key: str) -> None:
        end = time.monotonic() + 10
        while key not in box.full and time.monotonic() < end:
            self.drain()
            time.sleep(0.01)
        self.pump(10)

    def test_fits_inside_work_area(self):
        import ui
        box, _ = self.make()
        try:
            x, y, aw, ah = ui.work_area(box)
            box.update_idletasks()
            self.assertLessEqual(box.winfo_width(), aw + 16)
            self.assertLessEqual(box.winfo_height(), ah + 16)
        finally:
            box.close()

    def test_navigate_rotate_info_and_preload(self):
        box, ran = self.make(4, video_at=2)
        try:
            self.wait_loaded(box, "0")
            self.assertEqual(box.size_wh, (1600, 1200))
            self.wait_loaded(box, "1")  # 다음 장을 미리 불러 둔다
            self.assertIn("1", box.full)
            box.rotate(1)
            self.assertEqual(box.size_wh, (1200, 1600))
            box.rotate(-1)
            self.assertEqual(box.size_wh, (1600, 1200))
            box.actual_size()
            self.assertAlmostEqual(box.scale, 1.0, places=3)
            box.fit()
            self.assertTrue(box.fitted)
            box.toggle_info()
            self.pump()
            self.assertTrue(box.panel.winfo_manager())
            box.toggle_info()
            box._run_key("play")  # 사진에서는 재생이 없다
            self.assertEqual(ran, [])
            box.show(2)
            box._run_key("play")
            box._run_key("delete")
            self.assertEqual(ran, [("play", "2"), ("delete", "2")])
            box.remove_current()
            self.assertEqual(len(box.shots), 3)
        finally:
            box.close()
        self.assertTrue(box.closed)

    def test_fullscreen_bars(self):
        box, _ = self.make(3)
        try:
            box.toggle_fullscreen()
            self.pump()
            box.last_move -= 10
            box._tick()
            self.assertFalse(box.bars)
            self.assertFalse(box.top.winfo_manager())
            box._woke()
            self.assertTrue(box.bars)
            box._escape()  # 전체 화면만 끈다
            self.assertFalse(box.fullscreen)
            self.assertFalse(box.closed)
        finally:
            box.close()


class PlayerUiTests(unittest.TestCase):
    """mpv를 실제로 띄워 짧은 시험 영상을 재생·탐색·멈춤·닫기(이 창이 띄운 mpv만 끈다)."""

    @classmethod
    def setUpClass(cls):
        import subprocess
        import tempfile
        import media
        cls.tmp = tempfile.TemporaryDirectory()
        cls.video = Path(cls.tmp.name) / "clip.mp4"
        ff = media.ffmpeg_dir()
        subprocess.run([str(ff / "ffmpeg.exe"), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                        "testsrc2=size=640x360:rate=30:duration=20", "-f", "lavfi", "-i", "sine=frequency=440:duration=20",
                        "-c:v", "libopenh264", "-c:a", "aac", "-shortest", str(cls.video)], check=True,
                       creationflags=0x08000000)
        cls.root = shared_root()
        cls.calls = queue.Queue()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def drain_until(self, cond, seconds: float = 15) -> bool:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            while not self.calls.empty():
                self.calls.get_nowait()()
            self.root.update()
            if cond():
                return True
            time.sleep(0.02)
        return False

    def test_play_seek_pause_resume_close(self):
        released = []
        settings: dict = {}
        clip = player.Clip(player.resume_key(settings, str(self.video)), "clip.mp4",
                           lambda: (str(self.video), lambda: released.append(1)))
        win = player.VideoWindow(self.root, [clip], 0, self.calls.put, settings, lambda: None)
        try:
            self.assertTrue(self.drain_until(lambda: (win.state_.get("duration") or 0) > 19), "duration")
            self.assertTrue(self.drain_until(lambda: (win.state_.get("time-pos") or 0) > 0.3), "playing")
            win._seek_to(12.0, True)
            self.assertTrue(self.drain_until(lambda: 11.5 < (win.state_.get("time-pos") or 0) < 14), "seek")
            win.toggle_pause()
            self.assertTrue(self.drain_until(lambda: win.state_.get("pause") is True), "pause")
            win._speed_step(1)
            self.assertTrue(self.drain_until(lambda: win.state_.get("speed") == 1.25), "speed")
            proc = win.proc
        finally:
            win.close()
        self.assertIsNotNone(proc)
        self.assertIsNotNone(proc.poll())  # mpv가 끝났다
        self.assertEqual(released, [1])
        self.assertGreater(settings["resume"][clip.key], 11)  # 멈춘 자리를 기억(해시 열쇠로)


if __name__ == "__main__":
    unittest.main()
