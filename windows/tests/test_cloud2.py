"""2.0.0 클라우드 미리보기·검색(§15)과 잠긴 폴더(§14)를 두 노드 사이에서 확인한다. 임시 폴더만 쓴다."""
from __future__ import annotations

import io
import json
import os
import tempfile
import time
import unittest
from pathlib import Path

from PIL import Image

import protocol as p
import store
import tsnet

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import cloud  # noqa: E402
import node as nodemod  # noqa: E402
import outbox  # noqa: E402
import vault  # noqa: E402
from test_nodes import IP_A, IP_B, IP_C, USER, FakeTS, wait_for  # noqa: E402

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "docs" / "test_vectors.json").read_text("utf-8"))


def png_bytes(color: str | None = "#ff0000") -> bytes:
    """color가 None이면 무작위 점으로 채운다(압축해도 다른 파일과 겹치는 바이트가 없게)."""
    buf = io.BytesIO()
    image = Image.frombytes("RGB", (320, 240), os.urandom(320 * 240 * 3)) if color is None         else Image.new("RGB", (640, 480), color)
    image.save(buf, "PNG")
    return buf.getvalue()


class KindTests(unittest.TestCase):
    def test_kinds_match_vectors(self):
        for kind, exts in VECTORS["file_kinds"].items():
            self.assertEqual(cloud.FILE_KINDS[kind], set(exts), kind)
            for ext in exts:
                self.assertEqual(cloud.file_kind("a." + ext.upper()), kind)
        self.assertEqual(cloud.file_kind("noext"), "other")


class Cloud2Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        import thumbs
        self._thumb_cache = thumbs.CACHE_DIR
        thumbs.CACHE_DIR = self.root / "thumbs"  # 실제 %LOCALAPPDATA%\TailHop\thumbs에 쓰지 않게
        self.table = {IP_A: tsnet.Peer("nodeA", USER, "pc-a"), IP_B: tsnet.Peer("nodeB", USER, "pc-b"),
                      IP_C: tsnet.Peer("nodeC", USER, "pc-c")}
        self.nodes = []
        self.a = self.make_node("A", IP_A, "nodeA")
        self.b = self.make_node("B", IP_B, "nodeB")
        self.c = self.make_node("C", IP_C, "nodeC")
        for other in (self.b, self.c):
            other.join_pairing(self.a.begin_pairing())
        self.assertTrue(wait_for(lambda: {"nodeB", "nodeC"} <= set(self.a.pairings)))
        self.hub_dir = self.root / "A" / "cloud"
        self.a.set_cloud(True, str(self.hub_dir))
        (self.hub_dir / "사진" / "여름").mkdir(parents=True)
        (self.hub_dir / "사진" / "여름" / "바다.png").write_bytes(png_bytes())
        (self.hub_dir / "사진" / "메모.txt").write_text("메모", "utf-8")
        (self.hub_dir / "개인").mkdir()
        self.secret = png_bytes(None)
        (self.hub_dir / "개인" / "얼굴.png").write_bytes(self.secret)
        (self.hub_dir / "개인" / "일기.txt").write_text("PLAINTEXT-DIARY", "utf-8")
        self._backoff = outbox.BACKOFF
        outbox.BACKOFF = (0.05,) * 5

    def tearDown(self):
        import thumbs
        thumbs.CACHE_DIR = self._thumb_cache
        outbox.BACKOFF = self._backoff
        for n in self.nodes:
            n.stop()
        self.tmp.cleanup()

    def make_node(self, label, ip, sid):
        ts = FakeTS(tsnet.SelfInfo(ip=ip, user_id=USER, name=f"PC-{label}", stable_id=sid), self.table)
        n = nodemod.Node(lambda k, d: None, app_dir=self.root / label / "app", ts=ts, port=0)
        n.settings["save_dir"] = str(self.root / label / "recv")
        n.start()
        self.assertTrue(wait_for(lambda: n.listener is not None and n.me is not None))
        self.nodes.append(n)
        return n

    def lock_private(self) -> str:
        code = self.b.cloud_lock("nodeA", "개인", "432100")
        self.assertTrue(wait_for(lambda: any(x.locked and x.state == "locked"
                                             for x in self.b.cloud_list("nodeA", "")[0]), 20))
        return code

    def test_caps_list_kinds_and_search(self):
        self.assertIn("cloud2", self.b._hello("nodeA")["caps"])
        raw = self.b._cloud_request("nodeA", "cloud_list", path="사진", cursor=0)
        self.assertEqual({x["name"]: x.get("kind") for x in raw["items"]}, {"여름": None, "메모.txt": "doc"})
        items, nxt, partial = self.b.cloud_search("nodeA", "", "바다")
        self.assertEqual([x["path"] for x in items], ["사진/여름/바다.png"])
        self.assertFalse(partial)
        images, _, _ = self.b.cloud_search("nodeA", "", "", "image")
        self.assertEqual(sorted(x["path"] for x in images), ["개인/얼굴.png", "사진/여름/바다.png"])

    def test_thumb_is_small_jpeg(self):
        data = self.b.cloud_thumb("nodeA", "사진/여름/바다.png", 128)
        with Image.open(io.BytesIO(data)) as im:
            self.assertEqual(im.format, "JPEG")
            self.assertLessEqual(max(im.size), 128)
        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.cloud_thumb("nodeA", "사진/메모.txt")
        self.assertEqual(ctx.exception.code, "not_found")

    def test_lock_open_use_and_isolation(self):
        code = self.lock_private()
        self.assertEqual(len(code), 39)
        disk = b"".join(x.read_bytes() for x in self.hub_dir.rglob("*") if x.is_file())
        self.assertNotIn(b"PLAINTEXT-DIARY", disk)
        self.assertNotIn(self.secret[100:200], disk)  # 사진도 암호문만
        self.assertFalse((self.hub_dir / "개인").exists())
        [locked] = [x for x in self.b.cloud_list("nodeA", "")[0] if x.locked]
        self.assertEqual(locked.name, "개인")

        # 열쇠 없이는 안 된다
        for call in (lambda: self.b.cloud_list("nodeA", "개인"), lambda: self.b.cloud_thumb("nodeA", "개인/얼굴.png"),
                     lambda: self.b.cloud_delete("nodeA", "개인")):
            with self.assertRaises(p.ProtocolError) as ctx:
                call()
            self.assertEqual(ctx.exception.code, "vault_locked")
        # 검색도 잠긴 폴더 안은 건너뛴다
        self.assertEqual([x["path"] for x in self.b.cloud_search("nodeA", "", "얼굴")[0]], [])

        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.cloud_open("nodeA", "개인", pin="000000")
        self.assertEqual(ctx.exception.code, "vault_pin")
        self.b.cloud_open("nodeA", "개인", pin="432100")
        self.assertEqual(sorted(x.name for x in self.b.cloud_list("nodeA", "개인")[0]), ["얼굴.png", "일기.txt"])
        with Image.open(io.BytesIO(self.b.cloud_thumb("nodeA", "개인/얼굴.png", 64))) as im:
            self.assertEqual(im.format, "JPEG")
        # 다른 기기(C)는 B의 열쇠를 쓸 수 없다
        token = self.b.token_for("nodeA", "개인")
        with self.assertRaises(p.ProtocolError) as ctx:
            self.c._cloud_request("nodeA", "cloud_list", path="개인", token=token)
        self.assertEqual(ctx.exception.code, "vault_locked")

        # 보관함으로 올리기: 허브 디스크에는 암호문만
        src = self.root / "새 비밀.txt"
        src.write_text("UPLOADED-SECRET", "utf-8")
        [job] = self.b.outbox.add_cloud_upload("nodeA", [src], "개인")
        self.assertTrue(wait_for(lambda: self.b.outbox.get(job.id) is None, 10))
        self.assertIn("새 비밀.txt", [x.name for x in self.b.cloud_list("nodeA", "개인")[0]])
        disk = b"".join(x.read_bytes() for x in self.hub_dir.rglob("*") if x.is_file())
        self.assertNotIn(b"UPLOADED-SECRET", disk)
        # 가져오기: 복호화해서 받는다
        [item] = [x for x in self.b.cloud_list("nodeA", "개인")[0] if x.name == "일기.txt"]
        got = self.b.outbox.add_cloud_download("nodeA", "개인/일기.txt", item.size, item.mtime)
        self.assertTrue(wait_for(lambda: self.b.outbox.get(got.id) is None, 10))
        self.assertEqual((self.root / "B" / "recv" / "일기.txt").read_text("utf-8"), "PLAINTEXT-DIARY")

    def test_unlock_restores_and_recovery_repin(self):
        code = self.lock_private()
        self.b.cloud_repin("nodeA", "개인", "999999", recovery=code)
        with self.assertRaises(p.ProtocolError):
            self.b.cloud_open("nodeA", "개인", pin="432100")
        self.b.cloud_unlock("nodeA", "개인", "999999")
        self.assertTrue(wait_for(lambda: (self.hub_dir / "개인" / "일기.txt").exists()
                                 and not any(x.locked for x in self.b.cloud_list("nodeA", "")[0]), 20))
        self.assertEqual((self.hub_dir / "개인" / "얼굴.png").read_bytes(), self.secret)

    def test_cannot_delete_folder_holding_vault_or_lock_twice(self):
        (self.hub_dir / "상위" / "안").mkdir(parents=True)
        self.b.cloud_lock("nodeA", "상위/안", "432100")
        self.assertTrue(wait_for(lambda: any(x.locked for x in self.b.cloud_list("nodeA", "상위")[0]), 20))
        for call in (lambda: self.b.cloud_delete("nodeA", "상위"), lambda: self.b.cloud_lock("nodeA", "상위", "432100"),
                     lambda: self.b.cloud_lock("nodeA", "", "432100")):
            with self.assertRaises(p.ProtocolError) as ctx:
                call()
            self.assertEqual(ctx.exception.code, "bad_path")

    def test_wait_reply_carries_retry(self):
        self.lock_private()
        for _ in range(vault.MAX_FAILS):
            with self.assertRaises(p.ProtocolError):
                self.b.cloud_open("nodeA", "개인", pin="000000")
        sock, pairing = self.b._connect("nodeA")
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self.b._send_key(pairing))
            ch.send(json.dumps(p.new_header("cloud_open", "B", path="개인", pin="432100")).encode(), last=False)
            ch.send(b"", last=True)
            raw, _ = ch.recv()
        reply = json.loads(bytes(raw).decode())
        self.assertEqual(reply["code"], "vault_wait")
        self.assertGreater(reply["retry"], 0)
        time.sleep(0)


if __name__ == "__main__":
    unittest.main()
