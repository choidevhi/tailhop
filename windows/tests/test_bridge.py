"""bridge.py: 명령줄 해석, 기기 고르기, 폴더 묶기, 로컬 소켓(토큰) 확인.

모든 파일은 임시 폴더에만 쓴다(실제 %APPDATA%\\TailHop과 탐색기 '보내기' 폴더는 건드리지 않는다).
"""
from __future__ import annotations

import json
import socket
import subprocess
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path

import bridge


class ParseArgsTests(unittest.TestCase):
    def test_paths_become_absolute(self):
        req = bridge.parse_args(["a.txt", "--to", "S25", "--hidden", "sub\\b.png"])
        self.assertEqual(req.to, "S25")
        self.assertTrue(req.hidden)
        self.assertEqual(req.paths, [str(Path("a.txt").absolute()), str(Path("sub\\b.png").absolute())])
        self.assertTrue(req.has_work)

    def test_text_and_list(self):
        req = bridge.parse_args(["--text", "--not-an-option", "--list"])
        self.assertEqual(req.text, "--not-an-option")
        self.assertTrue(req.list_devices)

    def test_double_dash_keeps_dash_names(self):
        req = bridge.parse_args(["--", "--weird.txt"])
        self.assertEqual(req.paths, [str(Path("--weird.txt").absolute())])

    def test_hidden_alone_has_no_work(self):
        self.assertFalse(bridge.parse_args(["--hidden"]).has_work)

    def test_bad_arguments(self):
        for argv in (["--to"], ["--text"], ["--bogus"]):
            with self.subTest(argv=argv), self.assertRaises(bridge.ArgError):
                bridge.parse_args(argv)

    def test_request_json_round_trip_and_validation(self):
        req = bridge.Request(paths=["C:\\x"], text="hi", to="pc", list_devices=False)
        self.assertEqual(bridge.request_from_json(req.to_json()), req)
        for bad in ({"paths": "C:\\x", "text": None, "to": None, "list": False},
                    {"paths": [1], "text": None, "to": None, "list": False},
                    {"paths": [], "text": 3, "to": None, "list": False},
                    {"paths": [], "text": None, "to": None, "list": "yes"}):
            with self.subTest(bad=bad), self.assertRaises(bridge.ArgError):
                bridge.request_from_json(bad)


class ResolveDeviceTests(unittest.TestCase):
    DEVICES = [("id-phone", "Galaxy S25"), ("id-pc", "Desk PC"), ("id-pc2", "Desk Laptop")]

    def test_default_is_current_chat(self):
        self.assertEqual(bridge.resolve_device(self.DEVICES, None, "id-pc"), "id-pc")
        self.assertIsNone(bridge.resolve_device(self.DEVICES, None, "gone"))
        self.assertIsNone(bridge.resolve_device(self.DEVICES, None, None))

    def test_by_id_name_and_unique_prefix(self):
        self.assertEqual(bridge.resolve_device(self.DEVICES, "id-pc2", None), "id-pc2")
        self.assertEqual(bridge.resolve_device(self.DEVICES, "desk pc", None), "id-pc")
        self.assertEqual(bridge.resolve_device(self.DEVICES, "gal", None), "id-phone")

    def test_ambiguous_or_unknown_prefix(self):
        self.assertIsNone(bridge.resolve_device(self.DEVICES, "desk", None))
        self.assertIsNone(bridge.resolve_device(self.DEVICES, "iphone", None))


class PackFolderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_zip_keeps_tree_and_empty_folders(self):
        src = self.root / "사진 모음"
        (src / "a" / "b").mkdir(parents=True)
        (src / "empty").mkdir()
        (src / "top.txt").write_text("top", "utf-8")
        (src / "a" / "b" / "deep.bin").write_bytes(bytes(range(256)) * 10)
        out = bridge.pack_folder(src, self.root / "out")
        self.assertEqual(out.name, "사진 모음.zip")
        with zipfile.ZipFile(out) as zf:
            self.assertEqual(sorted(zf.namelist()), ["a/b/deep.bin", "empty/", "top.txt"])
            self.assertEqual(zf.read("a/b/deep.bin"), bytes(range(256)) * 10)
            self.assertIsNone(zf.testzip())

    def test_junction_is_not_followed(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "secret.txt").write_text("no", "utf-8")
        src = self.root / "src"
        src.mkdir()
        (src / "keep.txt").write_text("yes", "utf-8")
        made = subprocess.run(["cmd", "/c", "mklink", "/J", str(src / "link"), str(outside)],
                              capture_output=True, check=False)
        if made.returncode != 0:
            self.skipTest("cannot create a junction here")
        with zipfile.ZipFile(bridge.pack_folder(src, self.root / "out")) as zf:
            self.assertEqual(zf.namelist(), ["keep.txt"])


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.app_dir = Path(self.tmp.name)
        self.seen: list[dict] = []

        def handler(data: dict) -> dict:
            self.seen.append(data)
            return {"ok": True, "echo": data.get("text")}

        self.server = bridge.Server(handler, self.app_dir)

    def tearDown(self):
        self.server.close()
        self.tmp.cleanup()

    def test_request_reaches_handler(self):
        reply = bridge.send_request(bridge.Request(text="안녕"), self.app_dir)
        self.assertEqual(reply, {"ok": True, "echo": "안녕"})
        self.assertEqual(self.seen[0]["text"], "안녕")

    def test_wrong_token_gets_nothing(self):
        info = json.loads((self.app_dir / bridge.BRIDGE_FILE).read_text("utf-8"))
        with socket.create_connection(("127.0.0.1", info["port"]), timeout=3) as sock:
            sock.sendall(json.dumps({"token": "0" * 64, "paths": [], "text": "x", "to": None,
                                     "list": False}).encode() + b"\n")
            self.assertEqual(sock.recv(100), b"")
        self.assertEqual(self.seen, [])

    def test_garbage_is_ignored_and_server_keeps_running(self):
        info = json.loads((self.app_dir / bridge.BRIDGE_FILE).read_text("utf-8"))
        with socket.create_connection(("127.0.0.1", info["port"]), timeout=3) as sock:
            sock.sendall(b"\xff\xfe not json\n")
            self.assertEqual(sock.recv(100), b"")
        self.assertTrue(bridge.send_request(bridge.Request(text="ok"), self.app_dir)["ok"])

    def test_listens_on_loopback_only(self):
        self.assertEqual(self.server.sock.getsockname()[0], "127.0.0.1")

    def test_close_removes_own_file_only(self):
        path = self.app_dir / bridge.BRIDGE_FILE
        path.write_text(json.dumps({"port": 1, "token": "t", "pid": -1}), "utf-8")  # 다른 프로세스가 덮어씀
        self.server.close()
        self.assertTrue(path.exists())

    def test_no_app_raises_oserror(self):
        with tempfile.TemporaryDirectory() as empty, self.assertRaises(OSError):
            bridge.send_request(bridge.Request(text="x"), Path(empty), timeout=1)

    def test_parallel_requests(self):
        results: list[dict] = []
        threads = [threading.Thread(target=lambda i=i: results.append(
            bridge.send_request(bridge.Request(text=str(i)), self.app_dir))) for i in range(4)]
        for th in threads:
            th.start()
        for th in threads:
            th.join(5)
        self.assertEqual(sorted(r["echo"] for r in results), ["0", "1", "2", "3"])


if __name__ == "__main__":
    unittest.main()
