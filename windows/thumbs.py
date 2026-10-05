"""말풍선 미리보기(썸네일, 1.6.1). 이미 받았거나 보낸 파일로 PC 안에서만 만든다(네트워크로 아무것도 보내지 않는다).

- 이미지: Pillow로 읽되 픽셀 수 상한(MAX_PIXELS)을 넘으면 만들지 않는다(압축 폭탄 방지). JPEG는 draft로 작게 읽는다.
- 영상 등: Windows 탐색기와 같은 셸 썸네일(IShellItemImageFactory, 썸네일만)을 쓴다. 따로 디코더를 넣지 않는다.
- 캐시: %LOCALAPPDATA%\\TailHop\\thumbs (동기화되지 않는 로컬 폴더). 이름은 경로·크기·수정 시각의 해시라
  파일이 바뀌면 새로 만든다. 오래 안 쓴 캐시는 PRUNE_DAYS 뒤 지운다.
- 만들기는 전용 스레드 하나에서 차례로 한다(화면이 멈추지 않게, 같은 파일은 한 번만).
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import hashlib
import os
import queue
import threading
import time
from pathlib import Path
from typing import Callable

from PIL import Image, ImageDraw, ImageOps

IMAGE_EXT = {".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff"}
SHELL_EXT = {".mp4", ".mov", ".mkv", ".avi", ".wmv", ".webm", ".m4v", ".3gp", ".heic", ".heif"}
VIDEO_EXT = SHELL_EXT - {".heic", ".heif"}
MAX_PIXELS = 64_000_000  # 8000×8000. 넘으면 아이콘만 보인다
BOX = (480, 360)  # 캐시에 저장하는 최대 픽셀 크기(화면에는 240×180 논리 크기로, 200 % 배율까지 또렷하게)
PRUNE_DAYS = 30
CACHE_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")) / "TailHop" / "thumbs"


def previewable(name: str) -> bool:
    ext = Path(name).suffix.lower()
    return ext in IMAGE_EXT or ext in SHELL_EXT


def cache_path(path: Path, cache_dir: Path | None = None) -> Path | None:
    """파일이 없으면 None. 경로·크기·수정 시각이 같으면 같은 이름."""
    try:
        st = path.stat()
    except OSError:
        return None
    key = f"{str(path).casefold()}|{st.st_size}|{st.st_mtime_ns}|{BOX}"
    return (cache_dir or CACHE_DIR) / (hashlib.sha256(key.encode("utf-8")).hexdigest()[:32] + ".png")


def make(path: Path, cache_dir: Path | None = None) -> Path | None:
    """썸네일 PNG 경로. 만들 수 없으면(지원하지 않음, 너무 큼, 깨진 파일) None."""
    target = cache_path(path, cache_dir)
    if target is None or not previewable(path.name):
        return None
    if target.exists():
        os.utime(target)  # 최근에 쓴 캐시는 남긴다
        return target
    ext = path.suffix.lower()
    try:
        image = _from_pillow(path) if ext in IMAGE_EXT else _from_shell(path)
    except Exception:  # noqa: BLE001 - 깨지거나 이상한 파일은 아이콘만 보인다
        image = None
    if image is None:
        return None
    if ext in VIDEO_EXT:
        image = _play_badge(image)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".tmp")
    image.save(tmp, "PNG")
    os.replace(tmp, target)
    return target


def _from_pillow(path: Path) -> Image.Image | None:
    with Image.open(path) as im:
        w, h = im.size
        if w <= 0 or h <= 0 or w * h > MAX_PIXELS:
            return None
        im.draft("RGB", BOX)  # JPEG는 줄인 크기로 바로 읽는다(다른 형식은 무시)
        im.seek(0)
        out = ImageOps.exif_transpose(im)
        out = out.convert("RGBA" if "A" in out.getbands() or out.mode == "P" else "RGB")
        out.thumbnail(BOX)
        return out


def _play_badge(image: Image.Image) -> Image.Image:
    """영상이라는 표시: 가운데 반투명 원과 재생 삼각형."""
    out = image.convert("RGBA")
    layer = Image.new("RGBA", out.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = max(12, min(out.size) // 7)
    cx, cy = out.width // 2, out.height // 2
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(0, 0, 0, 140))
    s = r * 0.5
    d.polygon([(cx - s * 0.7, cy - s), (cx - s * 0.7, cy + s), (cx + s, cy)], fill=(255, 255, 255, 235))
    return Image.alpha_composite(out, layer)


# ---------- Windows 셸 썸네일 ----------
class _GUID(ctypes.Structure):
    _fields_ = [("d1", wt.DWORD), ("d2", wt.WORD), ("d3", wt.WORD), ("d4", ctypes.c_ubyte * 8)]


class _SIZE(ctypes.Structure):
    _fields_ = [("cx", ctypes.c_long), ("cy", ctypes.c_long)]


class _BITMAP(ctypes.Structure):
    _fields_ = [("bmType", ctypes.c_long), ("bmWidth", ctypes.c_long), ("bmHeight", ctypes.c_long),
                ("bmWidthBytes", ctypes.c_long), ("bmPlanes", wt.WORD), ("bmBitsPixel", wt.WORD),
                ("bmBits", ctypes.c_void_p)]


class _BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wt.DWORD), ("biWidth", ctypes.c_long), ("biHeight", ctypes.c_long),
                ("biPlanes", wt.WORD), ("biBitCount", wt.WORD), ("biCompression", wt.DWORD),
                ("biSizeImage", wt.DWORD), ("biXPelsPerMeter", ctypes.c_long), ("biYPelsPerMeter", ctypes.c_long),
                ("biClrUsed", wt.DWORD), ("biClrImportant", wt.DWORD)]


# IShellItemImageFactory {bcc18b79-ba16-442f-80c4-8a59c30c463b}
_IID_IMAGE_FACTORY = _GUID(0xBCC18B79, 0xBA16, 0x442F, (ctypes.c_ubyte * 8)(0x80, 0xC4, 0x8A, 0x59, 0xC3, 0x0C, 0x46, 0x3B))
_SIIGBF_BIGGERSIZEOK, _SIIGBF_THUMBNAILONLY = 0x1, 0x8
_GET_IMAGE = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_void_p, _SIZE, ctypes.c_int, ctypes.POINTER(wt.HBITMAP))
_RELEASE = ctypes.WINFUNCTYPE(ctypes.c_ulong, ctypes.c_void_p)


def _from_shell(path: Path) -> Image.Image | None:
    """탐색기가 보여 주는 것과 같은 썸네일. 썸네일 처리기가 없으면(아이콘뿐이면) None."""
    shell32, gdi32 = ctypes.windll.shell32, ctypes.windll.gdi32
    shell32.SHCreateItemFromParsingName.argtypes = [wt.LPCWSTR, ctypes.c_void_p, ctypes.POINTER(_GUID),
                                                    ctypes.POINTER(ctypes.c_void_p)]
    gdi32.GetObjectW.argtypes = [wt.HANDLE, ctypes.c_int, ctypes.c_void_p]
    gdi32.GetDIBits.argtypes = [wt.HDC, wt.HBITMAP, wt.UINT, wt.UINT, ctypes.c_void_p, ctypes.c_void_p, wt.UINT]
    gdi32.DeleteObject.argtypes = [wt.HANDLE]
    factory = ctypes.c_void_p()
    if shell32.SHCreateItemFromParsingName(str(path), None, ctypes.byref(_IID_IMAGE_FACTORY),
                                           ctypes.byref(factory)) != 0:
        return None
    vtable = ctypes.cast(ctypes.cast(factory, ctypes.POINTER(ctypes.c_void_p))[0], ctypes.POINTER(ctypes.c_void_p))
    hbmp = wt.HBITMAP()
    try:
        hr = _GET_IMAGE(vtable[3])(factory, _SIZE(*BOX), _SIIGBF_THUMBNAILONLY | _SIIGBF_BIGGERSIZEOK,
                                   ctypes.byref(hbmp))
    finally:
        _RELEASE(vtable[2])(factory)
    if hr != 0 or not hbmp:
        return None
    try:
        bm = _BITMAP()
        gdi32.GetObjectW(hbmp, ctypes.sizeof(bm), ctypes.byref(bm))
        w, h = bm.bmWidth, abs(bm.bmHeight)
        if w <= 0 or h <= 0 or w * h > MAX_PIXELS:
            return None
        header = _BITMAPINFOHEADER(biSize=ctypes.sizeof(_BITMAPINFOHEADER), biWidth=w, biHeight=-h, biPlanes=1,
                                   biBitCount=32, biCompression=0)
        buf = ctypes.create_string_buffer(w * h * 4)
        dc = ctypes.windll.user32.GetDC(None)
        try:
            lines = gdi32.GetDIBits(dc, hbmp, 0, h, buf, ctypes.byref(header), 0)
        finally:
            ctypes.windll.user32.ReleaseDC(None, dc)
        if lines != h:
            return None
        image = Image.frombuffer("RGBA", (w, h), buf.raw, "raw", "BGRA", 0, 1)
        if image.getchannel("A").getextrema() == (0, 0):  # 알파가 없는 비트맵
            image = image.convert("RGB")
        image.thumbnail(BOX)
        return image.copy()
    finally:
        gdi32.DeleteObject(hbmp)


# ---------- 캐시 정리·작업 스레드 ----------
def prune(cache_dir: Path | None = None, days: int = PRUNE_DAYS) -> int:
    """days일 동안 쓰지 않은 썸네일을 지운다. 지운 개수."""
    folder = cache_dir or CACHE_DIR
    limit = time.time() - days * 86400
    removed = 0
    for f in folder.glob("*.png") if folder.is_dir() else []:
        try:
            if f.stat().st_mtime < limit:
                f.unlink()
                removed += 1
        except OSError:
            pass
    return removed


class Worker:
    """요청을 차례로 처리하고 done(원본 경로, 썸네일 경로 또는 None)을 이 스레드에서 부른다."""

    def __init__(self, done: Callable[[Path, Path | None], None], cache_dir: Path | None = None) -> None:
        self.done, self.cache_dir = done, cache_dir
        self.jobs: queue.Queue[Path] = queue.Queue()
        self.pending: set[str] = set()
        self.lock = threading.Lock()
        threading.Thread(target=self._run, daemon=True).start()

    def request(self, path: Path) -> None:
        key = str(path)
        with self.lock:
            if key in self.pending:
                return

            self.pending.add(key)
        self.jobs.put(path)

    def _run(self) -> None:
        ctypes.windll.ole32.CoInitializeEx(None, 0x2)  # 셸 썸네일용(COINIT_APARTMENTTHREADED)
        prune(self.cache_dir)
        while True:
            path = self.jobs.get()
            try:
                result = make(path, self.cache_dir)
            except Exception:  # noqa: BLE001
                result = None
            with self.lock:
                self.pending.discard(str(path))
            self.done(path, result)
