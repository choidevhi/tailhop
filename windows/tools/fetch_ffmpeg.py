r"""빌드용 FFmpeg(LGPL, 공유 DLL)를 받아 windows/vendor/ffmpeg에 둔다. 저장소에는 넣지 않는다(.gitignore).

받는 곳: BtbN/FFmpeg-Builds의 고정 릴리스. 받은 zip의 SHA-256이 아래 값과 같을 때만 푼다.
LGPL 빌드라서 libx264 같은 GPL 부품이 없고, 공유 DLL이라 사용자가 DLL을 바꿔 끼울 수 있다(LGPL 조건).
실행: cd windows && .venv\Scripts\python tools\fetch_ffmpeg.py
"""
from __future__ import annotations

import hashlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

TAG = "autobuild-2026-10-18-13-01"
NAME = "ffmpeg-n8.1.3-18-g46d899d7a0-win64-lgpl-shared-8.1"
SHA256 = "eb9346bdb3312d51ce5bfcf78041fd17e3db91f9fff9446d0d758406b85d2a51"
URL = f"https://github.com/BtbN/FFmpeg-Builds/releases/download/{TAG}/{NAME}.zip"
DEST = Path(__file__).resolve().parents[1] / "vendor" / "ffmpeg"


def main() -> int:
    if (DEST / "bin" / "ffmpeg.exe").exists() and (DEST / "VERSION").read_text("utf-8").strip() == NAME:
        (DEST / "bin" / "ffplay.exe").unlink(missing_ok=True)  # 재생은 mpv(앱 안)로 한다
        print("already", DEST)
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        zpath = Path(tmp) / "ffmpeg.zip"
        with urllib.request.urlopen(URL, timeout=120) as r, open(zpath, "wb") as out:
            shutil.copyfileobj(r, out)
        digest = hashlib.sha256(zpath.read_bytes()).hexdigest()
        if digest != SHA256:
            print("SHA-256 mismatch", digest, file=sys.stderr)
            return 1
        with zipfile.ZipFile(zpath) as z:
            z.extractall(tmp)
        src = Path(tmp) / NAME
        shutil.rmtree(DEST, ignore_errors=True)
        DEST.mkdir(parents=True)
        shutil.copytree(src / "bin", DEST / "bin", ignore=shutil.ignore_patterns("ffplay.exe"))
        shutil.copy2(src / "LICENSE.txt", DEST / "LICENSE.txt")
    (DEST / "VERSION").write_text(NAME + "\n", "utf-8")
    print("ok", DEST)
    return 0


if __name__ == "__main__":
    sys.exit(main())
