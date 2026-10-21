"""클라우드 모드 허브 쪽(docs/PROTOCOL.md §12): 클라우드 폴더 안의 경로 검사, 목록, 읽기, 올릴 폴더, 지우기.

- 경로는 `/`로 나눈 상대 경로다. 조각마다 파일명 정리(§5)를 거쳐도 그대로인 이름만 받는다(`..`, `\\`, 장치 이름 등 거부).
- 풀어 낸 위치가 클라우드 폴더 안인지 다시 확인하고, 중간에 링크(심볼릭 링크·정션·재분석 지점)가 있으면 따라가지 않는다.
- `.part`(받는 중인 조각)와 숨김·시스템 파일, 규칙에 맞지 않는 이름은 목록에 보이지 않고 요청도 not_found로 답한다.
- 지우기는 휴지통으로 보낸다(실수로 지워도 되살릴 수 있게).
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import os
import stat
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import protocol as p

MAX_DEPTH = 16
MAX_PATH_LEN = 1024
PAGE = 500
_HIDDEN, _SYSTEM, _REPARSE = 0x2, 0x4, 0x400


def parse_path(text: object) -> list[str]:
    """클라우드 경로 문자열을 조각 목록으로. 규칙(§12.1)에 맞지 않으면 ProtocolError("bad_path"). 빈 문자열 = 맨 위."""
    if not isinstance(text, str) or len(text) > MAX_PATH_LEN:
        raise p.ProtocolError("bad_path")
    if text == "":
        return []
    if "\\" in text or text.startswith("/") or text.endswith("/"):
        raise p.ProtocolError("bad_path")
    parts = text.split("/")
    if len(parts) > MAX_DEPTH:
        raise p.ProtocolError("bad_path")
    for part in parts:
        if part in ("", ".", "..") or p.sanitize_filename(part) != part:
            raise p.ProtocolError("bad_path")
    return parts


def join_path(parts: list[str]) -> str:
    return "/".join(parts)


def _attrs(path: Path) -> int:
    """링크를 따라가지 않은 파일 속성(Windows). 없으면 FileNotFoundError."""
    return getattr(os.lstat(path), "st_file_attributes", 0)


def _is_link(path: Path) -> bool:
    try:
        st = os.lstat(path)
    except FileNotFoundError:
        return False
    return stat.S_ISLNK(st.st_mode) or bool(getattr(st, "st_file_attributes", 0) & _REPARSE)


def _visible_name(name: str) -> bool:
    # NTFS는 대소문자를 가리지 않으므로 `.PART`도 받는 중인 조각이다.
    return not name.casefold().endswith(".part") and name not in (".", "..") and p.sanitize_filename(name) == name


@dataclass
class Item:
    name: str
    is_dir: bool
    size: int
    mtime: int  # 유닉스 ms
    locked: bool = False  # 잠긴 폴더(§14). 받는 쪽 목록에서만 채운다
    state: str = ""
    progress: int = 0

    def to_json(self) -> dict:
        return {"name": self.name, "dir": self.is_dir, "size": self.size, "mtime": self.mtime}


class CloudFolder:
    def __init__(self, root: Path) -> None:
        self.root = Path(root)

    def ensure(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)

    def _walk(self, parts: list[str]) -> Path:
        """조각을 하나씩 내려가며 링크·숨김을 막는다. 마지막 조각까지 있는지는 확인하지 않는다.
        맨 위 폴더 자체는 사용자가 고른 것이라 링크여도(다른 드라이브로 옮긴 정션 등) 따라간다. 그 아래만 막는다."""
        here = self.root
        for part in parts:
            here = here / part
            if _is_link(here):
                raise p.ProtocolError("bad_path")
            try:  # 있는 조각이 숨김·시스템이면 이름을 알아도 들어가지 못한다
                if _attrs(here) & (_HIDDEN | _SYSTEM):
                    raise p.ProtocolError("not_found")
            except FileNotFoundError:
                pass
        real_root = os.path.realpath(self.root)
        real = os.path.realpath(here)
        if os.path.commonpath([real_root, real]) != real_root:
            raise p.ProtocolError("bad_path")
        # realpath는 있는 조각을 디스크의 실제 이름으로 푼다. 요청한 이름이 그 실제 이름과 다르면(8.3 짧은 이름 `ENV~1`,
        # 다른 대소문자의 `.PART` 등) 숨긴 이름 규칙을 돌아가는 별칭이므로 없는 것으로 친다.
        real_parts = Path(os.path.relpath(real, real_root)).parts if real != real_root else ()
        if len(real_parts) != len(parts):
            raise p.ProtocolError("bad_path")
        for asked, actual in zip(parts, real_parts):
            if actual != asked and (actual.casefold() != asked.casefold() or not _visible_name(actual)):
                raise p.ProtocolError("not_found")
            if not _visible_name(actual):
                raise p.ProtocolError("not_found")
        return here

    def _existing(self, parts: list[str], want_dir: bool | None = None) -> Path:
        """있고 보이는 항목의 경로. 없거나 숨김·조각이면 not_found."""
        path = self._walk(parts)
        try:
            attrs = _attrs(path)
        except FileNotFoundError as exc:
            raise p.ProtocolError("not_found") from exc
        if parts and (attrs & (_HIDDEN | _SYSTEM) or not _visible_name(parts[-1])):
            raise p.ProtocolError("not_found")
        if want_dir is True and not path.is_dir() or want_dir is False and not path.is_file():
            raise p.ProtocolError("not_found")
        return path

    # ---------- 요청 ----------
    def list(self, parts: list[str], cursor: int = 0) -> tuple[list[Item], int | None]:
        """폴더 먼저, 이름순(대소문자 무시). 한 번에 PAGE개. (항목, 다음 cursor 또는 None)."""
        items = self.list_all(parts)
        page = items[cursor:cursor + PAGE]
        nxt = cursor + PAGE if cursor + PAGE < len(items) else None
        return page, nxt

    def list_all(self, parts: list[str]) -> list[Item]:
        folder = self._existing(parts, want_dir=True)
        items: list[Item] = []
        with os.scandir(folder) as it:
            for entry in it:
                if not _visible_name(entry.name):
                    continue
                try:
                    st = entry.stat(follow_symlinks=False)
                except OSError:
                    continue
                attrs = getattr(st, "st_file_attributes", 0)
                if attrs & (_HIDDEN | _SYSTEM | _REPARSE) or stat.S_ISLNK(st.st_mode):
                    continue
                is_dir = stat.S_ISDIR(st.st_mode)
                if not is_dir and not stat.S_ISREG(st.st_mode):
                    continue
                items.append(Item(entry.name, is_dir, 0 if is_dir else st.st_size, st.st_mtime_ns // 1_000_000))
        items.sort(key=lambda x: (not x.is_dir, x.name.casefold(), x.name))
        return items

    def search(self, parts: list[str], query: str, kind: str, skip: Callable[[str], bool]) -> tuple[list[dict], bool]:
        """parts 아래를 훑어 이름이 맞는 항목을 찾는다. skip(클라우드 경로)이 True인 폴더(잠긴 폴더)는 들어가지 않는다.
        (항목: path는 parts 기준 상대 경로, 다 훑지 못했으면 True)."""
        start = self._existing(parts, want_dir=True)
        base = join_path(parts)
        found: list[dict] = []
        scanned, partial = 0, False
        end = time.monotonic() + SEARCH_SECONDS
        stack = [("", start)]
        while stack:
            rel, folder = stack.pop()
            for item in self.list_all([*parts, *rel.split("/")] if rel else parts):
                scanned += 1
                if scanned > SEARCH_SCAN or time.monotonic() > end:
                    partial = True
                    stack.clear()
                    break
                sub = f"{rel}/{item.name}" if rel else item.name
                full = f"{base}/{sub}" if base else sub
                if item.is_dir and skip(full):
                    continue
                if matches(item.name, item.is_dir, query, kind):
                    found.append(item_json(item, path=sub))
                if item.is_dir:
                    stack.append((sub, folder / item.name))
        found.sort(key=lambda x: (x["path"].casefold(), x["path"]))
        return found, partial

    def stat_file(self, parts: list[str]) -> tuple[Path, int, int]:
        """(경로, 크기, 수정 시각 ms). 파일이 아니면 not_found."""
        if not parts:
            raise p.ProtocolError("bad_path")
        path = self._existing(parts, want_dir=False)
        st = path.stat()
        return path, st.st_size, st.st_mtime_ns // 1_000_000

    def folder_for_put(self, parts: list[str]) -> Path:
        """올릴 폴더(없으면 만든다). 만드는 조각마다 링크가 아닌지 본다."""
        self.ensure()
        here = self._walk(parts)
        if here.exists():
            return self._existing(parts, want_dir=True)
        self.mkdir(parts)
        return here

    def put_target(self, dir_text: object, header: dict | None = None) -> Path:
        """cloud_put 헤더의 dir → 받을 폴더(protocol.receive_message가 부른다)."""
        return self.folder_for_put(parse_path(dir_text))

    def mkdir(self, parts: list[str]) -> None:
        if not parts:
            return
        self.ensure()
        self._walk(parts)  # 이미 있는 조각이 숨김·별칭이면 만들기 전에 막는다
        here = self.root
        for part in parts:
            here = here / part
            if _is_link(here):
                raise p.ProtocolError("bad_path")
            if here.exists():
                if not here.is_dir():
                    raise p.ProtocolError("bad_path")
                continue
            here.mkdir()
        self._walk(parts)

    def delete(self, parts: list[str]) -> None:
        if not parts:
            raise p.ProtocolError("bad_path")  # 맨 위는 지울 수 없다
        path = self._existing(parts)
        if not recycle(path):
            raise p.ProtocolError("cloud_denied")


class _SHFILEOPSTRUCTW(ctypes.Structure):
    _fields_ = [("hwnd", wt.HWND), ("wFunc", wt.UINT), ("pFrom", wt.LPCWSTR), ("pTo", wt.LPCWSTR),
                ("fFlags", ctypes.c_ushort), ("fAnyOperationsAborted", wt.BOOL), ("hNameMappings", ctypes.c_void_p),
                ("lpszProgressTitle", wt.LPCWSTR)]


def recycle(path: Path) -> bool:
    """휴지통으로 보낸다(폴더는 안의 것까지). 묻는 창·진행 창을 띄우지 않는다. 성공하면 True."""
    FO_DELETE = 3
    FOF_SILENT, FOF_NOCONFIRMATION, FOF_ALLOWUNDO, FOF_NOERRORUI = 0x4, 0x10, 0x40, 0x400
    op = _SHFILEOPSTRUCTW(None, FO_DELETE, str(Path(path).absolute()) + "\0", None,
                          FOF_SILENT | FOF_NOCONFIRMATION | FOF_ALLOWUNDO | FOF_NOERRORUI, False, None, None)
    result = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op))
    return result == 0 and not op.fAnyOperationsAborted and not Path(path).exists()


# ---------- §15 종류·검색 ----------
# docs/test_vectors.json의 file_kinds와 같은 표(테스트가 같은지 확인한다)
FILE_KINDS = {
    "image": {"jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "tif", "tiff", "avif", "svg", "raw", "dng",
              "cr2", "nef", "arw"},
    "video": {"mp4", "mov", "mkv", "avi", "webm", "m4v", "wmv", "flv", "3gp", "mts", "m2ts", "ts"},
    "audio": {"mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "alac"},
    "doc": {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "txt", "md", "rtf", "csv", "odt", "ods",
            "odp", "pages", "numbers", "key", "epub"},
    "archive": {"zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "apk", "exe", "msi", "iso", "dmg"},
}
KIND_NAMES = ("all", *FILE_KINDS, "other")
SEARCH_SCAN = 50_000
SEARCH_SECONDS = 3.0


def file_kind(name: str) -> str:
    ext = name.rpartition(".")[2].lower() if "." in name else ""
    return next((kind for kind, exts in FILE_KINDS.items() if ext in exts), "other")


def matches(name: str, is_dir: bool, query: str, kind: str) -> bool:
    if query and query.casefold() not in name.casefold():
        return False
    if kind == "all":
        return True
    return not is_dir and file_kind(name) == kind


def item_json(item: Item, **extra) -> dict:
    data = item.to_json()
    if not item.is_dir:
        data["kind"] = file_kind(item.name)
    data.update(extra)
    return data
