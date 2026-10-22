"""설치본 확인(--selftest)이 지금 코드와 맞는지: 저장소·동기화 사본·FFmpeg을 임시 폴더에서 확인한다."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app  # noqa: E402
import media  # noqa: E402


class SelftestTests(unittest.TestCase):
    def test_selftest_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "result.json"
            code = app.selftest(out)
            result = json.loads(out.read_text("utf-8"))
        self.assertNotIn("error", result)
        self.assertTrue(result["store"])
        self.assertTrue(result["mirror"])
        if media.ffmpeg_dir() is not None:
            self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
