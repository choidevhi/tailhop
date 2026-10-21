"""2.2.1 여러 개 이름 바꾸기(rename_plan.py, §18.2 cloud_rename). 임시 폴더만 쓴다."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import cloudstore as cs
import rename_plan as rp
from storehelp import PW, names, seed


class PlanTests(unittest.TestCase):
    def test_modes_keep_extension(self):
        files = [("IMG_0001.JPG", False), ("IMG_0002.JPG", False), ("여행", True)]
        self.assertEqual(rp.plan(files, "replace", "IMG_", "제주_"), ["제주_0001.JPG", "제주_0002.JPG", "여행"])
        self.assertEqual(rp.plan(files, "affix", "[가족] ", " (원본)"),
                         ["[가족] IMG_0001 (원본).JPG", "[가족] IMG_0002 (원본).JPG", "[가족] 여행 (원본)"])
        self.assertEqual(rp.plan(files, "number", "제주_{n}", start=7, digits=3),
                         ["제주_007.JPG", "제주_008.JPG", "제주_009"])
        self.assertEqual(rp.plan(files[:1], "number", "{name}"), ["IMG_0001_01.JPG"])
        self.assertEqual(rp.plan(files[:1], "case", case="lower"), ["img_0001.JPG"])
        self.assertEqual(rp.split_ext(".env", False), (".env", ""))

    def test_problems_find_bad_and_clashing_names(self):
        old = ["a.txt", "b.txt", "c.txt", "d.txt"]
        new = ["x.txt", "X.TXT", "bad/name.txt", "ok.part"]
        self.assertEqual(rp.problems(old, new), ["exists", "exists", "bad_name", None])  # .part는 저장소에서는 괜찮다


class StoreRenameTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        root = Path(self.tmp.name) / "c"
        root.mkdir()
        self.st = cs.Store(root, Path(self.tmp.name) / "app")
        self.st.create(PW)
        for name in ("a.jpg", "b.jpg", "keep.jpg"):
            seed(self.st, "사진/" + name, name.encode())
        seed(self.st, "비밀/x.txt", b"X")

    def tearDown(self):
        self.st.close()
        self.tmp.cleanup()

    def test_rename_many_and_refuse_clash(self):
        renamed, failed = self.st.rename([(["사진", "a.jpg"], "1.jpg"), (["사진", "b.jpg"], "KEEP.jpg"),
                                          (["사진", "없음.jpg"], "z.jpg"), (["사진", "keep.jpg"], "a/b")],
                                         {}, "x")
        self.assertEqual(renamed, [{"path": "사진/a.jpg", "name": "1.jpg"}])
        self.assertEqual({x["path"]: x["code"] for x in failed},
                         {"사진/b.jpg": "exists", "사진/없음.jpg": "not_found", "사진/keep.jpg": "bad_name"})
        self.assertEqual(sorted(names(self.st, "사진")), ["1.jpg", "b.jpg", "keep.jpg"])

    def test_locked_folder_itself_and_inside(self):
        self.st.lock(["비밀"], "246810")
        renamed, _ = self.st.rename([(["비밀"], "금고")], {}, "x")  # 잠긴 폴더 자체는 열쇠 없이
        self.assertEqual(renamed, [{"path": "비밀", "name": "금고"}])
        _, failed = self.st.rename([(["금고", "x.txt"], "y.txt")], {}, "x")
        self.assertEqual(failed[0]["code"], "vault_locked")
        token = self.st.open_vault(["금고"], "x", "246810", None)
        renamed, _ = self.st.rename([(["금고", "x.txt"], "y.txt")], {"token": token}, "x")
        self.assertEqual(renamed, [{"path": "금고/x.txt", "name": "y.txt"}])


class StrongLockTests(unittest.TestCase):
    def test_strong_mode_has_no_auto_open_and_remote_unlock_is_throttled(self):
        tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        root, app = Path(tmp.name) / "c", Path(tmp.name) / "app"
        root.mkdir()
        st = cs.Store(root, app)
        st.create(PW)
        seed(st, "a.txt", b"A")
        st.set_strong(True)
        self.assertFalse(any((app / "stores").glob("*.key")))  # 자동으로 여는 사본이 없다
        self.assertTrue(st.lock_now())
        self.assertEqual(st.state, cs.LOCKED)
        st.close()
        again = cs.Store(root, app, strong=True)
        self.assertEqual(again.state, cs.LOCKED)  # 다시 켜도 비밀번호 전에는 잠김
        for _ in range(cs.MAX_FAILS):
            with self.assertRaises(Exception):
                again.unlock_remote("wrong password!!", "phone")
        with self.assertRaises(Exception) as ctx:
            again.unlock_remote(PW, "phone")
        self.assertEqual(getattr(ctx.exception, "code", ""), "vault_wait")
        again.unlock_remote(PW, "other-phone")  # 다른 기기는 막히지 않는다
        self.assertEqual(again.state, cs.OPEN)
        self.assertFalse(any((app / "stores").glob("*.key")))  # 열어도 사본을 만들지 않는다
        again.set_strong(False)  # 끄면 이 PC 사본을 다시 만든다
        self.assertTrue(any((app / "stores").glob("*.key")))
        again.close()
        self.assertEqual(cs.Store(root, app).state, cs.OPEN)
        tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
