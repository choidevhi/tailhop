"""2.3.0 모든 기기 동기화·기기 용량(docs/PROTOCOL.md §19). 임시 폴더만 쓴다."""
from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

import store

_GUARD = tempfile.TemporaryDirectory()
store.APP_DIR = Path(_GUARD.name) / "appdata-guard"
store.DEFAULT_SAVE_DIR = Path(_GUARD.name) / "save-guard"

import cloud  # noqa: E402
import cloudstore as cs  # noqa: E402
import mirror as mm  # noqa: E402
import node as nodemod  # noqa: E402
import tsnet  # noqa: E402
from storehelp import PW, open_hub, seed  # noqa: E402
from test_nodes import IP_A, IP_B, USER, FakeTS, wait_for  # noqa: E402


def make_store(root: Path) -> cs.Store:
    (root / "cloud").mkdir(parents=True)
    st = cs.Store(root / "cloud", root / "app")
    st.create(PW)
    return st


def paths(items: list[dict]) -> list[str]:
    return [x["path"] for x in items]


class HubSyncSetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.st = make_store(Path(self.tmp.name))
        for path in ("a/1.jpg", "a/b/2.jpg", "c.txt", "v/secret.txt"):
            seed(self.st, path, path.encode())

    def tearDown(self):
        self.st.close()
        self.tmp.cleanup()

    def set(self, path: str, on: bool):
        return self.st.sync_set([cloud.parse_path(path)], on, {}, "local")

    def test_nothing_synced_by_default(self):
        self.assertEqual(self.st.sync_manifest(), ("", []))

    def test_folder_includes_children_and_new_files(self):
        changed, failed = self.set("a", True)
        self.assertEqual((changed, failed), (1, []))
        digest, items = self.st.sync_manifest()
        self.assertRegex(digest, r"^[0-9a-f]{16}$")
        self.assertEqual(paths(items), ["a", "a/1.jpg", "a/b", "a/b/2.jpg"])
        seed(self.st, "a/b/3.jpg", b"new")
        digest2, items2 = self.st.sync_manifest()
        self.assertNotEqual(digest, digest2)  # 나중에 들어온 것도 대상
        self.assertIn("a/b/3.jpg", paths(items2))
        files = [x for x in items2 if not x["dir"]]
        self.assertTrue(all(len(x["ver"]) == 32 and x["size"] > 0 for x in files))

    def test_exclude_inside_and_folder_reset(self):
        self.set("a", True)
        self.set("a/b", False)
        self.assertEqual(paths(self.st.sync_manifest()[1]), ["a", "a/1.jpg"])
        self.set("a", True)  # 폴더를 다시 넣으면 아래의 따로 단 표시는 지운다
        self.assertIn("a/b/2.jpg", paths(self.st.sync_manifest()[1]))

    def test_all_and_listing_badge(self):
        self.st.set_sync_all(True)
        self.assertEqual(len([x for x in self.st.sync_manifest()[1] if not x["dir"]]), 4)
        listed = {x["name"]: x for x in self.st.list_children([], {}, "local")}
        self.assertTrue(listed["c.txt"].get("sync"))
        self.set("c.txt", False)
        self.assertNotIn("c.txt", paths(self.st.sync_manifest()[1]))
        listed = {x["name"]: x for x in self.st.list_children([], {}, "local")}
        self.assertNotIn("sync", listed["c.txt"])

    def test_rename_keeps_ver_and_changes_digest(self):
        self.set("c.txt", True)
        digest, items = self.st.sync_manifest()
        self.st.rename([(["c.txt"], "d.txt")], {}, "local")
        digest2, items2 = self.st.sync_manifest()
        self.assertNotEqual(digest, digest2)
        self.assertEqual(items[0]["ver"], items2[0]["ver"])
        self.assertEqual(items2[0]["path"], "d.txt")

    def test_locked_folder_cannot_sync(self):
        self.st.lock(["v"], "123456")
        changed, failed = self.set("v", True)
        self.assertEqual(changed, 0)
        self.assertEqual(failed, [{"path": "v", "code": "sync_vault"}])
        self.st.set_sync_all(True)
        self.assertNotIn("v", paths(self.st.sync_manifest()[1]))  # 전체여도 잠긴 폴더는 빠진다

    def test_marks_survive_reopen(self):
        self.set("a/1.jpg", True)
        self.st.set_sync_all(False)
        before = self.st.sync_manifest()
        self.st.close()
        again = cs.Store(self.st.root, self.st.app_dir)
        try:
            self.assertEqual(again.state, cs.OPEN)
            self.assertEqual(again.sync_manifest(), before)
        finally:
            again.close()


class MirrorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.base = Path(self.tmp.name) / "Mirror"
        self.files = {"x/a.bin": os.urandom(cs.CHUNK * 2 + 77), "b.txt": b"hello"}
        self.calls = 0

    def tearDown(self):
        self.tmp.cleanup()

    def wants(self, files=None, ver="1" * 32):
        files = self.files if files is None else files
        out = [mm.Want("d" * 32, "x", True)]
        for i, (path, data) in enumerate(sorted(files.items())):
            out.append(mm.Want(f"{i:032x}", path, False, len(data), 5, ver))
        return out

    def fetch(self, files=None):
        files = self.files if files is None else files

        def get(path, offset, length):
            self.calls += 1
            return files[path][offset:offset + length]

        return get

    def test_apply_read_and_no_plaintext_on_disk(self):
        m = mm.Mirror(self.base, "hubA")
        got = m.apply("a" * 16, self.wants(), self.fetch(), lambda path: b"")
        self.assertEqual(got, 2)
        info = m.find("x/a.bin", len(self.files["x/a.bin"]), 5)
        self.assertEqual(b"".join(m.read(info)), self.files["x/a.bin"])
        self.assertEqual([x.name for x in m.list_dir("")], ["x", "b.txt"])
        disk = b"".join(f.read_bytes() for f in m.dir.rglob("*") if f.is_file())
        self.assertNotIn(self.files["x/a.bin"][100:200], disk)
        self.assertNotIn(b"a.bin", disk)  # 이름도 암호문
        m.close()
        again = mm.Mirror(self.base, "hubA")  # 다시 열어도(DPAPI로 잠근 열쇠) 그대로
        self.assertEqual(again.digest, "a" * 16)
        self.assertEqual(again.usage(), (2, sum(map(len, self.files.values()))))
        again.close()

    def test_rename_only_and_delete(self):
        m = mm.Mirror(self.base, "hubA")
        m.apply("a" * 16, self.wants(), self.fetch(), lambda path: b"")
        before = self.calls
        moved = [w if w.path != "b.txt" else mm.Want(w.id, "c.txt", False, w.size, w.mtime, w.ver)
                 for w in self.wants() if w.path != "x/a.bin"]
        m.apply("b" * 16, moved, self.fetch(), lambda path: b"")
        self.assertEqual(self.calls, before)  # 이름만 바뀌면 다시 받지 않는다
        self.assertIsNotNone(m.find("c.txt"))
        self.assertIsNone(m.find("x/a.bin"))  # 목록에서 빠진 것은 지운다
        m.close()

    def test_resume_after_interruption(self):
        m = mm.Mirror(self.base, "hubA")
        big = {"big.bin": os.urandom(mm.PIECE * 2 + 10)}
        sent = []

        def flaky(path, offset, length):
            if len(sent) == 1:
                raise OSError("cut")
            sent.append(offset)
            return big[path][offset:offset + length]

        with self.assertRaises(OSError):
            m.apply("a" * 16, self.wants(big), flaky, lambda path: b"")
        self.assertEqual(m.digest, "")  # 다 맞추지 못했다
        resumed = []

        def rest(path, offset, length):
            resumed.append(offset)
            return big[path][offset:offset + length]

        m.apply("a" * 16, self.wants(big), rest, lambda path: b"")
        self.assertGreater(resumed[0], 0)  # 다 쓴 청크부터 이어 받았다
        self.assertEqual(b"".join(m.read(m.find("big.bin"))), big["big.bin"])
        m.close()

    def test_bad_manifest_items_are_dropped(self):
        good = {"id": "a" * 32, "path": "p/q.jpg", "dir": False, "size": 3, "mtime": 1, "ver": "b" * 32}
        self.assertIsNotNone(mm.parse_manifest_item(good))
        for bad in ({**good, "id": "x"}, {**good, "path": "../q"}, {**good, "size": -1}, {**good, "ver": 5},
                    {**good, "size": True}, {**good, "path": ""}, "nope"):
            self.assertIsNone(mm.parse_manifest_item(bad), bad)
        thumbless = mm.parse_manifest_item({**good, "thumb": 9, "tver": "zz"})
        self.assertEqual((thumbless.thumb, thumbless.tver), (0, ""))

    def test_space_guard(self):
        m = mm.Mirror(self.base, "hubA")
        huge = mm.Want("e" * 32, "huge.bin", False, 1 << 50, 1, "f" * 32)
        with self.assertRaises(mm.SpaceError):
            m.apply("a" * 16, [huge], self.fetch(), lambda path: b"")
        m.close()

    def test_wipe(self):
        m = mm.Mirror(self.base, "hubA")
        m.apply("a" * 16, self.wants(), self.fetch(), lambda path: b"")
        m.wipe()
        self.assertFalse(m.dir.exists())


class NodeSyncTests(unittest.TestCase):
    """두 PC 노드: A가 허브, B가 사본을 받는 기기."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self.tmp.name)
        self.table = {IP_A: tsnet.Peer("nodeA", USER, "pc-a"), IP_B: tsnet.Peer("nodeB", USER, "pc-b")}
        self.nodes = []
        self.a = self.make_node("A", IP_A, "nodeA")
        self.b = self.make_node("B", IP_B, "nodeB")
        self.b.join_pairing(self.a.begin_pairing())
        self.assertTrue(wait_for(lambda: "nodeB" in self.a.pairings))
        self.st = open_hub(self.a, self.root / "A" / "cloud")
        for path in ("photos/1.jpg", "photos/2.jpg", "docs/x.txt"):
            seed(self.st, path, os.urandom(3000) + path.encode())

    def tearDown(self):
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

    def test_space_in_hello_both_ways(self):
        self.b._hello("nodeA")
        free, total, _ = self.b.peer_space["nodeA"]
        self.assertGreater(total, 0)
        self.assertLessEqual(free, total)
        self.assertTrue(wait_for(lambda: "nodeB" in self.a.peer_space))  # 요청 헤더로도 알렸다

    def test_device_sets_target_and_gets_encrypted_copy(self):
        changed, failed = self.b.sync_set("nodeA", ["photos"], True)
        self.assertEqual((changed, failed), (1, []))
        self.b.syncer.sync_one("nodeA")
        m = self.b.syncer.mirror("nodeA")
        self.assertIsNotNone(m)
        self.assertEqual(self.b.syncer.status["nodeA"]["state"], "ok")
        self.assertEqual(sorted(x.name for x in m.list_dir("photos")), ["1.jpg", "2.jpg"])
        want = read_hub(self.st, "photos/2.jpg")
        self.assertEqual(b"".join(m.read(m.find("photos/2.jpg"))), want)
        self.assertEqual(m.digest, self.st.sync_manifest()[0])
        # 허브에서 빼면 다음 맞추기에서 사본도 지운다
        self.b.sync_set("nodeA", ["photos/1.jpg"], False)
        self.b.syncer.sync_one("nodeA")
        self.assertIsNone(m.find("photos/1.jpg"))
        # 대상이 모두 없어지면 사본 폴더째로
        self.b.sync_set("nodeA", ["photos"], False)
        self.b.syncer.sync_one("nodeA")
        self.assertIsNone(self.b.syncer.mirror("nodeA"))

    def test_sync_all_and_unpair_wipes(self):
        self.b.sync_all("nodeA", True)
        self.b.syncer.sync_one("nodeA")
        m = self.b.syncer.mirror("nodeA")
        self.assertEqual(m.usage()[0], 3)
        folder = m.dir
        self.b.unpair("nodeA")
        self.assertTrue(wait_for(lambda: not folder.exists()))

    def test_source_reads_copy_offline_and_online(self):
        from cloud_source import Source
        self.b.sync_set("nodeA", ["photos"], True)
        self.b.syncer.sync_one("nodeA")
        want = read_hub(self.st, "photos/1.jpg")
        off = Source(self.b, "nodeA", offline=True)
        self.assertEqual(sorted(x.name for x in off.list("photos")[0]), ["1.jpg", "2.jpg"])
        self.assertEqual(off.read_bytes("photos/1.jpg", 1 << 20), want)
        self.assertEqual(b"".join(off.stream("photos/1.jpg").read(0, 10)), want[:10])
        self.assertEqual([x["name"] for x in off.search("", "1", "all")[0]], ["1.jpg"])
        with self.assertRaises(Exception) as ctx:
            off.delete("photos/1.jpg")
        self.assertEqual(getattr(ctx.exception, "code", ""), "sync_readonly")
        out = off.export("photos/1.jpg", self.root / "out")  # 사용자가 고른 것만 풀어 꺼낸다
        self.assertEqual(out.read_bytes(), want)
        # 켜져 있을 때: 같은 크기·수정 시각이면 사본에서(허브에 묻지 않는다)
        item = next(x for x in self.b.cloud_list("nodeA", "photos")[0] if x.name == "1.jpg")
        self.assertTrue(item.sync)
        self.st.close()  # 허브 저장소를 닫아도
        on = Source(self.b, "nodeA")
        self.assertEqual(on.read_bytes("photos/1.jpg", 1 << 20, item), want)
        stale = cloud.Item(item.name, False, item.size + 1, item.mtime)
        with self.assertRaises(Exception):
            on.read_bytes("photos/1.jpg", 1 << 20, stale)  # 크기가 다르면 사본을 쓰지 않는다(허브가 닫혀 실패)

    def test_receive_off_wipes(self):
        self.b.sync_all("nodeA", True)
        self.b.syncer.sync_one("nodeA")
        folder = self.b.syncer.mirror("nodeA").dir
        self.b.set_sync_receive(False)
        self.assertTrue(wait_for(lambda: not folder.exists()))


def read_hub(st: cs.Store, path: str) -> bytes:
    return b"".join(st.read(st.node_at(cloud.parse_path(path), {}, "local")))


if __name__ == "__main__":
    unittest.main()
