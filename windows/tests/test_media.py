"""2.2.0 미리보기·화면용·재생용 버전(media.py). FFmpeg(vendor/ffmpeg)이 없으면 영상 쪽은 건너뛴다. 임시 폴더만 쓴다."""
from __future__ import annotations

import io
import os
import subprocess
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

from PIL import Image

import cloudstore as cs
import media
from storehelp import PW


def wait(cond, timeout=120.0) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return True
        time.sleep(0.1)
    return False


class MediaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.base = Path(self.tmp.name)
        (self.base / "cloud").mkdir()
        self.worker = media.Worker(lambda: self.st)
        self.st = cs.Store(self.base / "cloud", self.base / "app", on_media=self.worker.request,
                           on_open=self.worker.backfill)
        self.st.create(PW)

    def tearDown(self):
        self.st.close()
        self.tmp.cleanup()

    def node(self, name: str) -> cs.Node:
        return self.st.node_at([name], {}, "x")

    def test_photo_gets_thumb_and_screen(self):
        src = self.base / "big.jpg"
        Image.frombytes("RGB", (4000, 3000), os.urandom(4000 * 3000 * 3)).save(src, quality=95)
        self.st.import_file("", cs.MAIN, src)
        self.assertTrue(wait(lambda: self.node("big.jpg").variant("screen") is not None, 60))
        node = self.node("big.jpg")
        with Image.open(io.BytesIO(self.st.read_all(node, 10 << 20, "screen"))) as im:
            self.assertEqual(max(im.size), media.SCREEN_PX)
        with Image.open(io.BytesIO(self.st.read_all(node, 1 << 20, "thumb"))) as im:
            self.assertEqual(max(im.size), 512)
        self.assertLess(node.variant("screen")["s"], node.size)

    def test_range_server_serves_only_registered_ranges(self):
        data = os.urandom(cs.CHUNK * 2 + 77)
        up = self.st.begin_upload("", cs.MAIN, "x.bin", len(data), 1, "x", os.urandom(16).hex())
        up.write(data)
        up.commit()
        server = media.RangeServer()
        url, key = server.register(media.store_source(self.st, self.node("x.bin")))
        req = urllib.request.Request(url, headers={"Range": f"bytes={cs.CHUNK - 3}-{cs.CHUNK + 4}"})
        with urllib.request.urlopen(req) as r:
            self.assertEqual(r.status, 206)
            self.assertEqual(r.read(), data[cs.CHUNK - 3:cs.CHUNK + 5])
        with self.assertRaises(urllib.error.HTTPError):
            urllib.request.urlopen(url.rsplit("/", 1)[0] + "/guess")
        server.forget(key)
        with self.assertRaises(urllib.error.HTTPError):
            urllib.request.urlopen(url)
        self.assertEqual(server.httpd.server_address[0], "127.0.0.1")
        server.close()

    @unittest.skipIf(media.ffmpeg_dir() is None, "FFmpeg 없음(tools/fetch_ffmpeg.py)")
    def test_video_gets_poster_and_720p_play_without_plaintext_on_disk(self):
        ff = media.ffmpeg_dir()
        src = self.base / "phone.mp4"
        subprocess.run([str(ff / "ffmpeg.exe"), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                        "testsrc2=size=1080x1920:rate=30:duration=4", "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
                        "-c:v", "libopenh264", "-b:v", "12M", "-c:a", "aac", "-shortest", str(src)], check=True,
                       creationflags=0x08000000)
        self.st.import_file("", cs.MAIN, src)
        self.assertTrue(wait(lambda: self.node("phone.mp4").variant("play") is not None, 180))
        node = self.node("phone.mp4")
        self.assertIsNotNone(node.variant("poster"))
        self.assertIsNotNone(node.variant("thumb"))
        server = media.RangeServer()
        url, _ = server.register(media.store_source(self.st, node, "play"))
        info = media.probe(ff, url)
        v = media._video_stream(info)
        self.assertEqual((v["codec_name"], v["width"], v["height"]), ("h264", 720, 1280))
        self.assertTrue(any(s.get("codec_type") == "audio" for s in info["streams"]))
        self.assertLess(node.variant("play")["s"], node.size)
        server.close()
        raw = src.read_bytes()
        disk = b"".join(x.read_bytes() for x in (self.base / "cloud").rglob("*") if x.is_file())
        self.assertNotIn(raw[2000:2100], disk)
        self.assertNotIn(b"ftypisom", disk)


if __name__ == "__main__":
    unittest.main()
