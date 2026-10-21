"""여러 항목 옮기기(§16 cloud_move, 2.2.0부터 암호화 저장소 위)와 라이트박스 원본 받기(cloud_get → 메모리). 임시 폴더만 쓴다."""
from __future__ import annotations

import io
import tempfile
import unittest
from pathlib import Path

from PIL import Image

import protocol as p
import test_cloud2

import cloudstore as cs  # noqa: E402  (test_cloud2가 store 경로를 임시 폴더로 바꾼 뒤에 불러온다)
import hub as hubmod  # noqa: E402
import lightbox  # noqa: E402
from storehelp import PW, names, read, seed  # noqa: E402


class HubMoveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "cloud"
        self.root.mkdir()
        self.st = cs.Store(self.root, Path(self.tmp.name) / "app")
        self.st.create(PW)
        seed(self.st, "a/x.txt", b"X")
        seed(self.st, "a/b/y.txt", b"Y")
        seed(self.st, "dest/x.txt", b"OLD")
        seed(self.st, "Secret/s.txt", b"S")
        seed(self.st, "Secret/inner/t.txt", b"T")
        self.hub = hubmod.Hub(self.st)

    def tearDown(self):
        self.st.close()
        self.tmp.cleanup()

    def move(self, paths, dest, header=None):
        return self.hub.move([x.split("/") for x in paths], dest.split("/") if dest else [], header or {}, "peer")

    def test_moves_files_and_folders_without_overwriting(self):
        moved, failed = self.move(["a/x.txt", "a/b"], "dest")
        self.assertEqual(failed, [])
        self.assertEqual(moved, [{"path": "a/x.txt", "name": "x (1).txt"}, {"path": "a/b", "name": "b"}])
        self.assertEqual(read(self.st, "dest/x.txt"), b"OLD")  # 덮어쓰지 않았다
        self.assertEqual(read(self.st, "dest/x (1).txt"), b"X")
        self.assertEqual(read(self.st, "dest/b/y.txt"), b"Y")
        self.assertEqual(names(self.st, "a"), [])

    def test_item_errors_do_not_stop_others(self):
        moved, failed = self.move(["a", "a/x.txt", "nope.txt", "dest/x.txt"], "a/b")
        self.assertEqual({x["path"]: x["code"] for x in failed}, {"a": "bad_move", "nope.txt": "not_found"})
        self.assertEqual([x["path"] for x in moved], ["a/x.txt", "dest/x.txt"])
        moved, _ = self.move(["a/b/x.txt"], "a/b")  # 이미 그 폴더 안: 그대로
        self.assertEqual(moved, [{"path": "a/b/x.txt", "name": "x.txt"}])

    def test_missing_destination_refuses_whole_request(self):
        with self.assertRaises(p.ProtocolError) as ctx:
            self.move(["a/x.txt"], "없는폴더")
        self.assertEqual(ctx.exception.code, "not_found")
        self.assertEqual(read(self.st, "a/x.txt"), b"X")

    def test_locked_folder_boundaries(self):
        """2.2.1: 잠긴 폴더 안팎·잠긴 폴더 사이로도 옮긴다(meta만 다시 잠금). 두 열쇠가 모두 있어야 하고, 겹쳐 잠그지는 않는다."""
        self.st.lock(["Secret"], "246810")
        seed(self.st, "Other/o.txt", b"O")
        self.st.lock(["Other"], "135791")
        t1 = self.st.open_vault(["Secret"], "peer", "246810", None)
        t2 = self.st.open_vault(["Other"], "peer", "135791", None)
        moved, _ = self.move(["a/x.txt"], "Secret", {"token": t1})  # 안으로
        self.assertEqual(moved, [{"path": "a/x.txt", "name": "x.txt"}])
        node = self.st.node_at(["Secret", "x.txt"], {"token": t1}, "peer")
        self.assertNotEqual(node.ring, cs.MAIN)
        moved, _ = self.move(["Secret/s.txt"], "dest", {"token": t1})  # 밖으로
        self.assertEqual(moved, [{"path": "Secret/s.txt", "name": "s.txt"}])
        self.assertEqual(read(self.st, "dest/s.txt"), b"S")
        with self.assertRaises(p.ProtocolError):  # 다른 잠긴 폴더로: 도착 열쇠가 없으면 거부
            self.move(["Secret/x.txt"], "Other", {"token": t1})
        moved, _ = self.move(["Secret/x.txt"], "Other", {"token": t2, "tokens": [t1]})  # 두 열쇠로는 된다
        self.assertEqual(read(self.st, "Other/x.txt", {"token": t2}, "peer"), b"X")
        _, failed = self.move(["Secret"], "Other", {"token": t2})  # 잠긴 폴더를 잠긴 폴더 안에 넣지 않는다
        self.assertEqual(failed[0]["code"], "vault_move")
        moved, _ = self.move(["Secret"], "dest")  # 잠긴 폴더 자체는 열쇠 없이도 옮길 수 있다(안은 그대로 잠김)
        self.assertEqual(moved, [{"path": "Secret", "name": "Secret"}])
        with self.assertRaises(p.ProtocolError) as ctx:  # 열쇠 없이 잠긴 폴더 안으로는 못 옮긴다
            self.move(["dest/x.txt"], "dest/Secret")
        self.assertEqual((ctx.exception.code, ctx.exception.kw.get("mount")), ("vault_locked", "dest/Secret"))
        self.st.close()
        again = cs.Store(self.root, self.root.parent / "app")  # 다시 열어도 안으로 옮긴 것은 PIN 없이 안 보인다
        self.assertNotIn(node.id, again.nodes)  # 잠긴 폴더로 옮긴 그 항목은 저장소 열쇠만으로 읽히지 않는다
        again.close()


class MoveOverNetworkTests(test_cloud2.Cloud2Tests):
    """두 노드: B가 A 허브에서 옮기고 원본을 메모리로 받는다(cloud_move·cloud_read)."""

    # Cloud2Tests의 테스트는 다시 돌리지 않는다
    test_caps_list_kinds_and_search = test_thumb_is_small_jpeg = test_lock_open_use_and_isolation = None
    test_unlock_restores_and_recovery_repin = test_cannot_delete_folder_holding_vault_or_lock_twice = None
    test_wait_reply_carries_retry = None

    def test_move_and_read(self):
        self.assertIn("move", self.b._hello("nodeA")["caps"])
        moved, failed = self.b.cloud_move("nodeA", ["사진/메모.txt", "사진/여름/바다.png", "없음.txt"], "개인")
        self.assertEqual([x["path"] for x in moved], ["사진/메모.txt", "사진/여름/바다.png"])
        self.assertEqual(failed, [{"path": "없음.txt", "code": "not_found"}])
        data = self.b.cloud_read("nodeA", "개인/바다.png", 10 * 1024 * 1024)
        self.assertEqual(data, read(self.st, "개인/바다.png"))
        self.assertEqual(lightbox.decode(data).size, (640, 480))
        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.cloud_read("nodeA", "개인/바다.png", 100)
        self.assertEqual(ctx.exception.code, "too_large")
        with self.assertRaises(p.ProtocolError) as ctx:  # 형식이 틀린 요청은 통째로 거부
            self.b._cloud_request("nodeA", "cloud_move", paths=["../x"], dest="")
        self.assertEqual(ctx.exception.code, "bad_path")

    def test_many_paths_are_sent_in_batches(self):
        for i in range(450):
            seed(self.st, f"사진/f{i:03}.txt", str(i).encode())
        moved, failed = self.b.cloud_move("nodeA", [f"사진/f{i:03}.txt" for i in range(450)], "개인")
        self.assertEqual((len(moved), failed), (450, []))
        self.assertEqual(sum(1 for x in names(self.st, "개인") if x.startswith("f")), 450)

    def test_range_get_returns_only_that_part(self):
        """§17.3 cloud_get length: 영상 건너뛰기처럼 그 부분만 받는다(청크 경계를 넘어도)."""
        data = bytes(range(256)) * 20000
        seed(self.st, "v.bin", data)
        for offset, length in ((3_000_001, 1000), (cs.CHUNK - 3, 10), (len(data) - 5, 100), (0, 0)):
            got = self.b.cloud_read("nodeA", "v.bin", 1000, offset=offset, length=length)
            self.assertEqual(got, data[offset:offset + length])


class RemoteStreamTests(test_cloud2.Cloud2Tests):
    """다른 PC 허브의 영상을 재생: 크기만 묻고(cloud_size) 2 MiB씩 범위로 받는다(cloud_source.Source.stream)."""

    test_caps_list_kinds_and_search = test_thumb_is_small_jpeg = test_lock_open_use_and_isolation = None
    test_unlock_restores_and_recovery_repin = test_cannot_delete_folder_holding_vault_or_lock_twice = None
    test_wait_reply_carries_retry = None

    def test_stream_reads_ranges(self):
        import os
        from cloud_source import Source
        data = os.urandom(cs.CHUNK * 5 + 3)
        seed(self.st, "영상.mp4", data)
        src = Source(self.b, "nodeA").stream("영상.mp4")
        self.assertEqual(src.size, len(data))
        self.assertEqual(b"".join(src.read(cs.CHUNK * 2 + 5, cs.CHUNK * 3)), data[cs.CHUNK * 2 + 5:cs.CHUNK * 5 + 5])
        with self.assertRaises(p.ProtocolError):
            self.b.cloud_size("nodeA", "영상.mp4", "play")  # 아직 재생용 버전이 없다


class DecodeTests(unittest.TestCase):
    def test_exif_rotation_and_big_images_shrink(self):
        image = Image.new("RGB", (40, 20), "#123456")
        exif = image.getexif()
        exif[0x0112] = 6  # 90° 돌려 보여야 하는 사진
        buf = io.BytesIO()
        image.save(buf, "JPEG", exif=exif.tobytes())
        self.assertEqual(lightbox.decode(buf.getvalue()).size, (20, 40))
        old = lightbox.MAX_PIXELS
        lightbox.MAX_PIXELS = 10_000
        try:
            out = lightbox.decode(test_cloud2.png_bytes())
        finally:
            lightbox.MAX_PIXELS = old
        self.assertLessEqual(out.width * out.height, 10_000)
        self.assertGreater(len(lightbox.pyramid(Image.new("RGB", (5000, 300)))), 2)
        with self.assertRaises(OSError):
            lightbox.decode(b"not an image")


if __name__ == "__main__":
    unittest.main()
