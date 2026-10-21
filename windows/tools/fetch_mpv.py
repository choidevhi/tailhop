r"""빌드용 mpv(앱 안 영상 재생기)를 받아 windows/vendor/mpv에 둔다. 저장소에는 넣지 않는다(.gitignore).

받는 곳: shinchiro/mpv-winbuild-cmake의 고정 릴리스. GitHub이 알려 주는 SHA-256과 같을 때만 푼다.
mpv는 GPLv2+ 프로그램이다. TailHop은 따로 실행되는 mpv.exe에 창(--wid)과 명령 통로(IPC)만 넘기고 링크하지 않는다.
실행: cd windows && .venv\Scripts\python tools\fetch_mpv.py
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

TAG = "20261019"
NAME = "mpv-x86_64-20261019-git-6a262d11f6"
SHA256 = "e23cd1622ad299322fdf37a5405e0ff541fe2031b01487b059580e3aa27c2ba9"
URL = f"https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/{TAG}/{NAME}.7z"
COMMIT = "6a262d11f63733d37e3ee6d5fafcb1be1ef83952"  # 이 빌드의 mpv 소스(GPL: 대응 소스의 위치를 함께 둔다)
LICENSES = {"LICENSE.GPL": "edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6",
            "Copyright": "bfe9ee4cceabcb8ecbfadf208d04156f73d801e6a57369a5606bb8341e204a23"}
SOURCE = f"""mpv (GPLv2+) - separate program started by TailHop for in-app video playback.
Binary: {URL}
mpv source for this build: https://github.com/mpv-player/mpv/tree/{COMMIT}
Build scripts (with the exact library versions): https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/{TAG}
"""
DEST = Path(__file__).resolve().parents[1] / "vendor" / "mpv"


def main() -> int:
    if (DEST / "mpv.exe").exists() and (DEST / "LICENSE.GPL").exists()             and (DEST / "VERSION").read_text("utf-8").strip() == NAME:
        print("already", DEST)
        return 0
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        archive = Path(tmp) / "mpv.7z"
        with urllib.request.urlopen(URL, timeout=120) as r, open(archive, "wb") as out:
            shutil.copyfileobj(r, out)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != SHA256:
            print("SHA-256 mismatch", digest, file=sys.stderr)
            return 1
        unpacked = Path(tmp) / "x"
        unpacked.mkdir()
        # Windows 11에 들어 있는 tar(libarchive)로 푼다(7z의 BCJ2 필터까지 읽는다)
        tar = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "tar.exe"
        subprocess.run([str(tar), "-xf", str(archive), "-C", str(unpacked)], check=True)
        shutil.rmtree(DEST, ignore_errors=True)
        DEST.mkdir(parents=True)
        for name in ("mpv.exe", "d3dcompiler_43.dll"):
            if (unpacked / name).exists():
                shutil.copy2(unpacked / name, DEST / name)
        for name, want in LICENSES.items():
            url = f"https://raw.githubusercontent.com/mpv-player/mpv/{COMMIT}/{name}"
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
            if hashlib.sha256(data).hexdigest() != want:
                print("SHA-256 mismatch", name, file=sys.stderr)
                return 1
            (DEST / name).write_bytes(data)
        (DEST / "SOURCE.txt").write_text(SOURCE, "utf-8")
    (DEST / "VERSION").write_text(NAME + "\n", "utf-8")
    print("ok", DEST)
    return 0


if __name__ == "__main__":
    sys.exit(main())
