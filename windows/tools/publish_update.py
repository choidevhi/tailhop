r"""빌드한 설치 파일·APK를 이 PC의 업데이트 선반에 올린다(docs/PROTOCOL.md §13.1).

실행: cd windows && .venv\Scripts\python tools\publish_update.py [--pc] [--android]
(아무것도 주지 않으면 둘 다) 새로 만든 dist\TailHop_Setup_<버전>.exe와 android\...\app-release.apk를
%APPDATA%\TailHop\updates로 복사하고 manifest.json을 쓴다. 실행 중인 TailHop은 다시 켜지 않아도 바로 알린다.
APK는 릴리즈 키로 서명된 것만 올린다(디버그 서명이면 거부).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
REPO = HERE.parent
sys.path.insert(0, str(HERE))

import store  # noqa: E402

RELEASE_SIGNER = "e910aa8f1da90619c6e3f7a2c0e84cb040a151fe5458cdacf0410b44f0aff789"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as src:
        for block in iter(lambda: src.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def pc_version() -> str:
    match = re.search(r'APP_VERSION = "([\d.]+)"', (HERE / "app.py").read_text("utf-8"))
    if not match:
        raise SystemExit("app.py에서 APP_VERSION을 찾지 못했습니다")
    return match.group(1)


def android_version() -> tuple[str, int]:
    text = (REPO / "android" / "app" / "build.gradle.kts").read_text("utf-8")
    name = re.search(r'versionName = "([\d.]+)"', text)
    code = re.search(r"versionCode = (\d+)", text)
    if not name or not code:
        raise SystemExit("build.gradle.kts에서 버전을 찾지 못했습니다")
    return name.group(1), int(code.group(1))


def apk_signer(apk: Path) -> str:
    tools = Path(os.environ.get("ANDROID_HOME") or r"C:\Android\Sdk") / "build-tools"
    apksigner = max(tools.glob("*/apksigner.bat"), default=None)
    if apksigner is None:
        raise SystemExit("apksigner를 찾지 못했습니다(ANDROID_HOME)")
    out = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)], capture_output=True, text=True)
    match = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", out.stdout)
    if out.returncode != 0 or not match:
        raise SystemExit("APK 서명을 확인하지 못했습니다:\n" + out.stdout + out.stderr)
    return match.group(1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pc", action="store_true")
    ap.add_argument("--android", action="store_true")
    args = ap.parse_args()
    both = not args.pc and not args.android
    shelf = store.APP_DIR / "updates"
    shelf.mkdir(parents=True, exist_ok=True)
    manifest_path = shelf / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text("utf-8"))
    except (OSError, ValueError):
        manifest = {}
    if args.pc or both:
        version = pc_version()
        src = REPO / "dist" / f"TailHop_Setup_{version}.exe"
        if not src.is_file():
            raise SystemExit(f"설치 파일이 없습니다: {src} (windows/build.bat 먼저)")
        name = f"TailHop_Setup_{version}.exe"
        shutil.copyfile(src, shelf / name)
        manifest["pc"] = {"version": version, "file": name, "size": src.stat().st_size, "sha256": sha256(src)}
        print("PC", version, name)
    if args.android or both:
        version, code = android_version()
        src = REPO / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
        if not src.is_file():
            raise SystemExit(f"APK가 없습니다: {src} (gradlew assembleRelease 먼저)")
        if apk_signer(src) != RELEASE_SIGNER:
            raise SystemExit("APK 서명이 릴리즈 키가 아닙니다. 올리지 않습니다.")
        name = f"TailHop-{version}.apk"
        shutil.copyfile(src, shelf / name)
        manifest["android"] = {"version": version, "code": code, "file": name, "size": src.stat().st_size,
                               "sha256": sha256(src)}
        print("Android", version, code, name)
    keep = {x["file"] for x in manifest.values() if isinstance(x, dict)}
    for old in shelf.iterdir():
        if old.name != "manifest.json" and old.name not in keep:
            old.unlink()  # 예전 버전 파일은 지운다
    store._write_private(manifest_path, json.dumps(manifest, indent=2))
    print("선반:", shelf)
    return 0


if __name__ == "__main__":
    sys.exit(main())
