"""Tailscale로 하는 업데이트(1.9.0, docs/PROTOCOL.md §13).

- 배포 PC: `<설정 폴더>\\updates\\manifest.json`과 같은 폴더의 설치 파일·APK를 hello로 알리고 update_get으로 내준다.
  빌드 스크립트(tools\\publish_update.py)가 파일을 복사하고 manifest를 쓴다.
- 받는 PC: 페어링된 PC의 hello에서 더 높은 PC 버전을 보면 설정 창에 보이고, 받으면 크기·SHA-256을 확인한 뒤
  설치 프로그램을 조용히 실행한다(설치 프로그램이 이 앱을 --quit으로 끄고 설치한 뒤 다시 켠다).
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import protocol as p

TARGETS = ("pc", "android")
_VERSION = re.compile(r"\d{1,4}(\.\d{1,4}){1,3}")
_SHA = re.compile(r"[0-9a-f]{64}")
MAX_SIZE = 1024 ** 3  # 설치 파일 하나의 상한(1 GiB)


def version_tuple(text: str) -> tuple[int, ...]:
    return tuple(int(x) for x in text.split("."))


def newer(candidate: str, current: str) -> bool:
    try:
        return version_tuple(candidate) > version_tuple(current)
    except ValueError:
        return False


@dataclass(frozen=True)
class Package:
    target: str
    version: str
    path: Path
    size: int
    sha256: str
    code: int = 0  # Android versionCode

    def advert(self) -> dict:
        info = {"version": self.version, "size": self.size}
        if self.target == "android":
            info["code"] = self.code
        return info


def _parse_entry(target: str, raw: object, folder: Path) -> Package | None:
    if not isinstance(raw, dict):
        return None
    version, name, size, sha = raw.get("version"), raw.get("file"), raw.get("size"), raw.get("sha256")
    code = raw.get("code", 0)
    if not isinstance(version, str) or not _VERSION.fullmatch(version):
        return None
    if not isinstance(name, str) or p.sanitize_filename(name) != name or not isinstance(sha, str) \
            or not _SHA.fullmatch(sha):
        return None
    if not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= MAX_SIZE:
        return None
    if not isinstance(code, int) or isinstance(code, bool) or code < 0 or (target == "android" and code == 0):
        return None
    path = folder / name
    try:
        if not path.is_file() or path.stat().st_size != size:
            return None
    except OSError:
        return None
    return Package(target, version, path, size, sha, code)


class Shelf:
    """배포 PC의 업데이트 선반. manifest가 바뀌면 다시 읽는다(빌드 뒤 앱을 다시 켜지 않아도 된다)."""

    def __init__(self, app_dir: Path) -> None:
        self.folder = Path(app_dir) / "updates"
        self._stamp: tuple[int, int] | None = None
        self._packages: dict[str, Package] = {}

    def packages(self) -> dict[str, Package]:
        manifest = self.folder / "manifest.json"
        try:
            st = manifest.stat()
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            self._stamp, self._packages = None, {}
            return {}
        if stamp != self._stamp:
            self._stamp = stamp
            try:
                data = json.loads(manifest.read_text("utf-8"))
            except (OSError, ValueError):
                data = {}
            found = {}
            for target in TARGETS:
                pkg = _parse_entry(target, data.get(target) if isinstance(data, dict) else None, self.folder)
                if pkg is not None:
                    found[target] = pkg
            self._packages = found
        return dict(self._packages)

    def advert(self) -> dict:
        return {target: pkg.advert() for target, pkg in self.packages().items()}


def parse_advert(raw: object) -> dict[str, dict]:
    """상대 hello의 updates를 경계에서 검사한다."""
    out: dict[str, dict] = {}
    if not isinstance(raw, dict):
        return out
    for target in TARGETS:
        item = raw.get(target)
        if not isinstance(item, dict):
            continue
        version, size, code = item.get("version"), item.get("size"), item.get("code", 0)
        if isinstance(version, str) and _VERSION.fullmatch(version) and isinstance(size, int) \
                and not isinstance(size, bool) and 0 < size <= MAX_SIZE and isinstance(code, int):
            out[target] = {"version": version, "size": size, "code": code}
    return out


def verify(path: Path, size: int, sha256: str) -> bool:
    if path.stat().st_size != size:
        return False
    digest = hashlib.sha256()
    with open(path, "rb") as src:
        for block in iter(lambda: src.read(p.CHUNK), b""):
            digest.update(block)
    return digest.hexdigest() == sha256


def run_installer(installer: Path) -> None:
    """설치 프로그램을 조용히 실행한다. 설치 프로그램이 이 앱을 끄고(--quit) 설치한 뒤 다시 켠다(/RELAUNCH).
    이 앱보다 오래 살아야 하므로 따로 떼어 낸 프로세스로 띄운다."""
    flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    subprocess.Popen([str(installer), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH"],
                     creationflags=flags, close_fds=True, cwd=str(installer.parent))


def can_self_update() -> bool:
    """설치본(PyInstaller exe)에서만 스스로 업데이트한다. 개발 중(python app.py)은 설치하지 않는다."""
    return bool(getattr(sys, "frozen", False)) and os.name == "nt"
