"""1.8.0 클라우드 모드(docs/PROTOCOL.md §12): 경로 규칙, 허브 목록·링크 막기, 두 노드 사이 올리기·가져오기·이어받기·지우기.

모든 파일은 임시 폴더에만 쓴다. 휴지통으로 보내기(cloud.recycle)는 사용자의 실제 휴지통을 채우지 않게
시험용 함수로 바꿔 끼우고, 진짜 휴지통 호출은 없는 경로로만 확인한다.
"""
from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path

import protocol as p
import store
import tsnet

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import cloud  # noqa: E402
import node as nodemod  # noqa: E402
import outbox  # noqa: E402
from test_nodes import IP_A, IP_B, USER, FakeTS, wait_for  # noqa: E402

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "docs" / "test_vectors.json").read_text("utf-8"))


class Stop(Exception):
    pass


class PathTests(unittest.TestCase):
    def test_shared_vectors(self):
        for text in VECTORS["cloud_paths"]["ok"]:
            with self.subTest(ok=text):
                self.assertEqual(cloud.join_path(cloud.parse_path(text)), text)
        for text in VECTORS["cloud_paths"]["bad"]:
            with self.subTest(bad=text), self.assertRaises(p.ProtocolError) as ctx:
                cloud.parse_path(text)
            self.assertEqual(ctx.exception.code, "bad_path")

    def test_non_string(self):
        for value in (None, 3, ["a"], {"a": 1}):
            with self.subTest(value=value), self.assertRaises(p.ProtocolError):
                cloud.parse_path(value)


class FolderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name) / "cloud"
        self.outside = Path(self.tmp.name) / "outside"
        self.outside.mkdir()
        (self.outside / "secret.txt").write_text("밖", "utf-8")
        self.hub = cloud.CloudFolder(self.root)
        self.hub.ensure()

    def tearDown(self):
        self.tmp.cleanup()

    def test_list_sorts_folders_first_and_hides_parts_and_hidden(self):
        for name in ("b.txt", "A.txt", "c.part", "z"):
            (self.root / name).write_text("x", "utf-8")
        (self.root / "dir2").mkdir()
        (self.root / "Dir1").mkdir()
        hidden = self.root / "hidden.txt"
        hidden.write_text("x", "utf-8")
        subprocess.run(["attrib", "+h", str(hidden)], check=True, capture_output=True)
        items, nxt = self.hub.list([])
        self.assertEqual([(x.name, x.is_dir) for x in items],
                         [("Dir1", True), ("dir2", True), ("A.txt", False), ("b.txt", False), ("z", False)])
        self.assertIsNone(nxt)
        with self.assertRaises(p.ProtocolError) as ctx:
            self.hub.stat_file(["hidden.txt"])
        self.assertEqual(ctx.exception.code, "not_found")

    def test_pagination(self):
        for i in range(cloud.PAGE + 7):
            (self.root / f"f{i:04d}.bin").write_bytes(b"")
        first, nxt = self.hub.list([])
        self.assertEqual((len(first), nxt), (cloud.PAGE, cloud.PAGE))
        rest, end = self.hub.list([], nxt)
        self.assertEqual((len(rest), end), (7, None))

    def test_junction_inside_cloud_is_not_followed(self):
        link = self.root / "escape"
        done = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(self.outside)], capture_output=True)
        if done.returncode != 0:
            self.skipTest("정션을 만들 수 없음")
        self.assertEqual([x.name for x in self.hub.list([])[0]], [])
        for call in (lambda: self.hub.list(["escape"]), lambda: self.hub.stat_file(["escape", "secret.txt"]),
                     lambda: self.hub.put_target("escape"), lambda: self.hub.delete(["escape"])):
            with self.assertRaises(p.ProtocolError) as ctx:
                call()
            self.assertEqual(ctx.exception.code, "bad_path")
        self.assertTrue((self.outside / "secret.txt").exists())

    def test_hidden_folder_cannot_be_entered_by_name(self):
        secret = self.root / "숨김" / "안"
        secret.mkdir(parents=True)
        (secret / "a.txt").write_text("x", "utf-8")
        subprocess.run(["attrib", "+h", str(self.root / "숨김")], check=True, capture_output=True)
        self.assertEqual(self.hub.list([])[0], [])
        for call in (lambda: self.hub.list(["숨김", "안"]), lambda: self.hub.stat_file(["숨김", "안", "a.txt"])):
            with self.assertRaises(p.ProtocolError) as ctx:
                call()
            self.assertEqual(ctx.exception.code, "not_found")

    def test_root_may_itself_be_a_junction(self):
        """사용자가 고른 클라우드 폴더가 다른 곳을 가리키는 정션이어도 쓴다(그 아래 링크만 막는다)."""
        link = Path(self.tmp.name) / "linked-root"
        done = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(self.outside)], capture_output=True)
        if done.returncode != 0:
            self.skipTest("정션을 만들 수 없음")
        hub = cloud.CloudFolder(link)
        self.assertEqual([x.name for x in hub.list([])[0]], ["secret.txt"])
        self.assertEqual(hub.stat_file(["secret.txt"])[1], len("밖".encode("utf-8")))

    def test_root_cannot_be_deleted_and_missing_is_not_found(self):
        with self.assertRaises(p.ProtocolError) as ctx:
            self.hub.delete([])
        self.assertEqual(ctx.exception.code, "bad_path")
        with self.assertRaises(p.ProtocolError) as ctx:
            self.hub.delete(["없음.txt"])
        self.assertEqual(ctx.exception.code, "not_found")

    def test_real_recycle_of_missing_path_fails_quietly(self):
        self.assertFalse(cloud.recycle(self.root / "없는 파일.txt"))

    def test_mkdir_nested_and_existing_file_blocks(self):
        self.hub.mkdir(["사진", "2026"])
        self.assertTrue((self.root / "사진" / "2026").is_dir())
        (self.root / "file").write_text("x", "utf-8")
        with self.assertRaises(p.ProtocolError):
            self.hub.mkdir(["file", "sub"])


class TwoNodeCloudTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.table = {IP_A: tsnet.Peer("nodeA", USER, "pc-a"), IP_B: tsnet.Peer("nodeB", USER, "pc-b")}
        self.nodes = []
        self.a, self.ev_a = self.make_node("A", IP_A, "nodeA")
        self.b, self.ev_b = self.make_node("B", IP_B, "nodeB")
        self.b.join_pairing(self.a.begin_pairing())
        self.assertTrue(wait_for(lambda: "nodeB" in self.a.pairings))
        self.hub_dir = self.root / "A" / "cloud"
        self.a.set_cloud(True, str(self.hub_dir))
        self.recycled: list[Path] = []
        self._recycle = cloud.recycle
        cloud.recycle = self.fake_recycle
        self._backoff = outbox.BACKOFF
        outbox.BACKOFF = (0.05,) * 5

    def tearDown(self):
        cloud.recycle = self._recycle
        outbox.BACKOFF = self._backoff
        for n in self.nodes:
            n.stop()
        self.tmp.cleanup()

    def fake_recycle(self, path: Path) -> bool:
        self.recycled.append(path)
        shutil.rmtree(path) if path.is_dir() else path.unlink()
        return True

    def make_node(self, label, ip, sid):
        events = []
        ts = FakeTS(tsnet.SelfInfo(ip=ip, user_id=USER, name=f"PC-{label}", stable_id=sid), self.table)
        n = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=self.root / label / "app", ts=ts, port=0)
        n.settings["save_dir"] = str(self.root / label / "recv")
        n.start()
        self.assertTrue(wait_for(lambda: n.listener is not None and n.me is not None))
        self.nodes.append(n)
        return n, events

    def test_hello_tells_cloud_state(self):
        self.assertTrue(self.b.cloud_info("nodeA"))
        self.assertFalse(self.a.cloud_info("nodeB"))

    def test_upload_list_download_delete(self):
        src = self.root / "보고서.pdf"
        data = os.urandom(p.CHUNK * 2 + 11)
        src.write_bytes(data)
        [job] = self.b.outbox.add_cloud_upload("nodeA", [src], "문서/2026")
        self.assertTrue(wait_for(lambda: self.b.outbox.get(job.id) is None))
        self.assertEqual((self.hub_dir / "문서" / "2026" / "보고서.pdf").read_bytes(), data)
        self.assertTrue(any(k == "cloud_in" for k, _ in self.ev_a))

        items, nxt = self.b.cloud_list("nodeA", "")
        self.assertEqual([(x.name, x.is_dir) for x in items], [("문서", True)])
        items, _ = self.b.cloud_list("nodeA", "문서/2026")
        self.assertEqual([(x.name, x.size) for x in items], [("보고서.pdf", len(data))])

        got = self.b.outbox.add_cloud_download("nodeA", "문서/2026/보고서.pdf", items[0].size, items[0].mtime)
        self.assertTrue(wait_for(lambda: self.b.outbox.get(got.id) is None))
        self.assertEqual((self.root / "B" / "recv" / "보고서.pdf").read_bytes(), data)
        received = [d for k, d in self.ev_b if k == "received"]
        self.assertTrue(received and received[-1]["from_cloud"])

        self.b.cloud_mkdir("nodeA", "빈 폴더")
        self.assertTrue((self.hub_dir / "빈 폴더").is_dir())
        self.b.cloud_delete("nodeA", "문서")
        self.assertFalse((self.hub_dir / "문서").exists())
        self.assertEqual(self.recycled, [self.hub_dir / "문서"])

    def test_upload_folder_keeps_tree(self):
        folder = self.root / "여행"
        (folder / "1일차").mkdir(parents=True)
        (folder / "1일차" / "a.jpg").write_bytes(b"a")
        (folder / "b.jpg").write_bytes(b"b")
        jobs = self.b.outbox.add_cloud_upload("nodeA", [folder], "")
        self.assertEqual(len(jobs), 2)
        self.assertTrue(wait_for(lambda: all(self.b.outbox.get(j.id) is None for j in jobs)))
        self.assertEqual((self.hub_dir / "여행" / "1일차" / "a.jpg").read_bytes(), b"a")
        self.assertEqual((self.hub_dir / "여행" / "b.jpg").read_bytes(), b"b")

    def test_download_resumes_after_cut(self):
        data = os.urandom(p.CHUNK * 6 + 5)
        self.hub_dir.mkdir(parents=True, exist_ok=True)
        (self.hub_dir / "영상.mp4").write_bytes(data)
        [item] = self.b.cloud_list("nodeA", "")[0]
        job = outbox.Job(id="dl", peer_id="nodeA", kind="cloud_get", remote="영상.mp4", size=item.size,
                         remote_mtime=item.mtime)

        def cut(done, total):
            if done >= p.CHUNK * 3:
                raise Stop()

        with self.assertRaises(Stop):
            self.b.deliver(job, cut)
        part = self.b.outbox.work_dir / "dl" / "영상.mp4.part"
        have = part.stat().st_size
        self.assertGreaterEqual(have, p.CHUNK * 3)
        seen = []
        self.b.deliver(job, lambda done, total: seen.append(done))
        self.assertGreater(seen[0], have)  # 처음부터가 아니라 이어서
        self.assertEqual((self.root / "B" / "recv" / "영상.mp4").read_bytes(), data)

    def test_download_restarts_when_hub_file_changed(self):
        self.hub_dir.mkdir(parents=True, exist_ok=True)
        target = self.hub_dir / "a.bin"
        target.write_bytes(os.urandom(p.CHUNK * 3))
        [item] = self.b.cloud_list("nodeA", "")[0]
        job = outbox.Job(id="dl2", peer_id="nodeA", kind="cloud_get", remote="a.bin", size=item.size,
                         remote_mtime=item.mtime)

        def cut(done, total):
            if done >= p.CHUNK:
                raise Stop()

        with self.assertRaises(Stop):
            self.b.deliver(job, cut)
        new = os.urandom(p.CHUNK * 2)
        target.write_bytes(new)
        os.utime(target, ns=(target.stat().st_atime_ns, target.stat().st_mtime_ns + 5_000_000))
        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.deliver(job)
        self.assertEqual(ctx.exception.code, "cloud_changed")
        self.assertTrue(outbox.is_retryable(ctx.exception))
        self.b.deliver(job)
        self.assertEqual((self.root / "B" / "recv" / "a.bin").read_bytes(), new)

    def test_cloud_off_refuses(self):
        self.a.set_cloud(False)
        with self.assertRaises(p.ProtocolError) as ctx:
            self.b.cloud_list("nodeA", "")
        self.assertEqual(ctx.exception.code, "cloud_off")
        src = self.root / "x.txt"
        src.write_text("x", "utf-8")
        [job] = self.b.outbox.add_cloud_upload("nodeA", [src], "")
        self.assertTrue(wait_for(lambda: self.b.outbox.get(job.id).state == outbox.FAILED))
        self.assertEqual(self.b.outbox.get(job.id).code, "cloud_off")

    def test_collect_received_files_into_cloud(self):
        src = self.root / "chat.bin"
        src.write_bytes(b"chat")
        self.b.send_file("nodeA", src)
        self.assertTrue((self.root / "A" / "recv" / "chat.bin").exists())  # 기본: 평소 저장 폴더
        self.a.set_cloud(collect=True)
        src2 = self.root / "chat2.bin"
        src2.write_bytes(b"chat2")
        self.b.send_file("nodeA", src2)
        self.assertEqual((self.hub_dir / "chat2.bin").read_bytes(), b"chat2")

    def raw_cloud(self, kind: str, **fields) -> dict:
        pairing = self.b.pairings["nodeA"]
        s = socket.create_connection((IP_A, self.a.port), timeout=5, source_address=(IP_B, 0))
        with s:
            s.settimeout(5)
            ch = p.open_client(s, p.KIND_MSG, self.b._send_key(pairing))
            ch.send(json.dumps(p.new_header(kind, "B", **fields)).encode("utf-8"), last=False)
            ch.send(b"", last=True)
            return p.read_reply(ch)

    def test_traversal_attempts_are_refused(self):
        (self.root / "A" / "outside.txt").write_text("밖", "utf-8")
        self.hub_dir.mkdir(parents=True, exist_ok=True)
        for kind, fields in (("cloud_list", {"path": ".."}), ("cloud_get", {"path": "../outside.txt"}),
                             ("cloud_del", {"path": "..\\outside.txt"}), ("cloud_mkdir", {"path": "a/../../b"}),
                             ("cloud_list", {"path": "C:/Windows"}), ("cloud_del", {"path": ""}),
                             ("cloud_list", {"path": 5}), ("cloud_list", {"path": "", "cursor": -1})):
            with self.subTest(kind=kind, fields=fields), self.assertRaises(p.ProtocolError) as ctx:
                self.raw_cloud(kind, **fields)
            self.assertIn(ctx.exception.code, ("bad_path", "bad_header"))
        self.assertTrue((self.root / "A" / "outside.txt").exists())

    def test_put_into_bad_dir_is_refused(self):
        src = self.root / "x.bin"
        src.write_bytes(b"x")
        pairing = self.b.pairings["nodeA"]
        sock, _ = self.b._connect("nodeA")
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self.b._send_key(pairing))
            with self.assertRaises(p.ProtocolError) as ctx:
                p.send_file_xfer(ch, "B", src, p.new_xfer(), 0, kind="cloud_put", dir="../..")
                p.read_reply(ch)
        self.assertEqual(ctx.exception.code, "bad_path")
        self.assertFalse((self.root / "x.bin").with_name("x (1).bin").exists())

    def test_put_without_xfer_is_refused(self):
        pairing = self.b.pairings["nodeA"]
        sock, _ = self.b._connect("nodeA")
        src = self.root / "y.bin"
        src.write_bytes(b"y")
        with sock:
            ch = p.open_client(sock, p.KIND_MSG, self.b._send_key(pairing))
            ch.send(json.dumps(p.new_header("cloud_put", "B", name="y.bin", size=1)).encode(), last=False)
            ch.send(b"y", last=True)
            with self.assertRaises(p.ProtocolError) as ctx:
                p.read_reply(ch)
        self.assertEqual(ctx.exception.code, "bad_xfer")


if __name__ == "__main__":
    unittest.main()
