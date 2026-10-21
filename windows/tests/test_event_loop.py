"""2.2.1: 앱의 이벤트 순환은 한 이벤트가 예외를 내도 멈추지 않는다(멈추면 설치 프로그램의 끄기 요청도 받지 못한다)."""
import queue
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app  # noqa: E402
import store  # noqa: E402


class _Loop:
    """App._poll이 쓰는 것만 갖춘 자리(진짜 창을 띄우지 않는다)."""

    def __init__(self) -> None:
        self.events: queue.Queue = queue.Queue()
        self.handled: list[str] = []
        self.scheduled = 0

    def _handle_event(self, kind: str, data: dict) -> None:
        if kind == "boom":
            raise RuntimeError("handler failed")
        self.handled.append(kind)

    _poll = app.App._poll

    def after(self, _ms: int, _fn) -> None:
        self.scheduled += 1


class EventLoopTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.saved = store.APP_DIR
        store.APP_DIR = Path(self.tmp.name)  # 오류 기록을 임시 폴더에

    def tearDown(self):
        store.APP_DIR = self.saved
        self.tmp.cleanup()

    def test_exception_does_not_stop_the_loop(self):
        loop = _Loop()
        for kind in ("a", "boom", "quit"):
            loop.events.put((kind, {}))
        loop._poll()
        self.assertEqual(loop.handled, ["a", "quit"])  # 예외 뒤의 끄기 요청도 처리했다
        self.assertEqual(loop.scheduled, 1)  # 다음 차례도 잡았다
        log = (Path(self.tmp.name) / "error.log").read_text("utf-8")
        self.assertIn("event boom", log)
        self.assertIn("handler failed", log)

    def test_log_rotates_when_large(self):
        path = Path(self.tmp.name) / "error.log"
        path.write_text("x" * (app.ERROR_LOG_MAX + 10), "utf-8")
        try:
            raise ValueError("again")
        except ValueError:
            app.log_error("test")
        self.assertLess(path.stat().st_size, app.ERROR_LOG_MAX)


if __name__ == "__main__":
    unittest.main()
