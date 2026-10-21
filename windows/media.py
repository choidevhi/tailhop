"""미리보기·화면용·재생용 버전 만들기와 재생(2.2.0, docs/PROTOCOL.md §17.4). 평문을 디스크에 쓰지 않는다.

- 사진: 원본을 메모리에서 풀어(EXIF 회전 반영) thumb(긴 변 512, JPEG)과 screen(긴 변 2048, JPEG)을 만든다.
  원본이 이미 작으면 screen은 만들지 않는다(원본을 그대로 쓴다).
- 영상: FFmpeg(LGPL 빌드, vendor/ffmpeg)으로 poster(첫 장면, 긴 변 1280)·thumb와, 짧은 변 720의 play 버전(H.264+AAC,
  MPEG-TS)을 만든다. TS는 흘려 쓸 수 있어(뒤로 돌아가 고쳐 쓰지 않음) 출력을 그대로 암호화해 저장하면서도 재생할 때
  건너뛸 수 있다. 이미 작고 가벼운 H.264 영상은 play를 만들지 않는다.
- FFmpeg 입력은 이 PC 안에서만 열리는 주소(127.0.0.1, 임의 포트·임의 32바이트 경로)로 준다. 그 주소는 암호화된 객체를
  요청받은 범위만 그때그때 복호화해 넘긴다(RangeServer). 출력은 표준 출력으로 받아 바로 암호화해 쓴다.
- 인코더는 그래픽카드 것부터(NVENC → QSV → AMF → Media Foundation) 실제로 한 장 만들어 보고 되는 것을 쓴다.
  없으면 OpenH264(소프트웨어). FFmpeg은 낮은 우선순위로 한 번에 하나만 돌린다.
"""
from __future__ import annotations

import io
import json
import queue
import secrets
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Iterator

from PIL import Image

import cloud
import cloudstore as cs
import hub as hubmod
import protocol as p

SCREEN_PX = 2048
POSTER_PX = 1280
PLAY_SHORT = 720
PLAY_KBPS = 2500
LIGHT_KBPS = 4000  # 이보다 가볍고 이미 H.264·짧은 변 720 이하면 play를 만들지 않는다
SCREEN_MIN_BYTES = 1_500_000  # 원본이 이보다 작고 2048 이하면 screen을 만들지 않는다
ENCODERS = ("h264_nvenc", "h264_qsv", "h264_amf", "h264_mf", "libopenh264")
_NO_WINDOW = 0x08000000
_BELOW_NORMAL = 0x00004000


def ffmpeg_dir() -> Path | None:
    """설치본은 앱 폴더의 ffmpeg, 개발 중에는 windows/vendor/ffmpeg/bin."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    for candidate in (base / "ffmpeg", base / "vendor" / "ffmpeg" / "bin"):
        if (candidate / "ffmpeg.exe").exists():
            return candidate
    return None


def _run(args: list[str], **kw) -> subprocess.Popen:
    return subprocess.Popen(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            creationflags=_NO_WINDOW | _BELOW_NORMAL, **kw)


# ---------- 범위 서버 ----------
class Source:
    """크기와 read(offset, length)를 가진 것(이 PC 저장소의 객체, 다른 허브의 파일 등)."""

    def __init__(self, size: int, read: Callable[[int, int], Iterator[bytes]], mime: str = "application/octet-stream"):
        self.size, self.read, self.mime = size, read, mime


class RangeServer:
    """127.0.0.1에서만 받는 HTTP 범위 서버. 등록한 Source마다 맞히기 어려운 경로를 준다."""

    def __init__(self) -> None:
        self.sources: dict[str, Source] = {}
        self.lock = threading.Lock()
        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            timeout = 30  # 말없이 붙잡고 있는 연결이 처리 스레드를 쌓지 못하게

            def log_message(self, *args) -> None:
                pass

            def _source(self) -> Source | None:
                with server.lock:
                    return server.sources.get(self.path.lstrip("/"))

            def do_HEAD(self) -> None:
                self._reply(head=True)

            def do_GET(self) -> None:
                self._reply(head=False)

            def _reply(self, head: bool) -> None:
                src = self._source()
                if src is None:
                    self.send_error(404)
                    return
                start, end = 0, src.size - 1
                rng = self.headers.get("Range", "")
                if rng.startswith("bytes="):
                    a, _, b = rng[6:].split(",")[0].partition("-")
                    try:
                        if a:
                            start = int(a)
                            end = min(int(b), src.size - 1) if b else src.size - 1
                        elif b:
                            start = max(0, src.size - int(b))
                    except ValueError:
                        self.send_error(416)
                        return
                    if start >= src.size or start > end:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{src.size}")
                        self.send_header("Content-Length", "0")
                        self.send_header("Connection", "close")
                        self.close_connection = True
                        self.end_headers()
                        return
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes {start}-{end}/{src.size}")
                else:
                    self.send_response(200)
                length = max(0, end - start + 1)
                self.send_header("Content-Type", src.mime)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(length))
                self.send_header("Connection", "close")  # 건너뛸 때마다 새 연결(중간에 끊은 응답을 다시 쓰지 않게)
                self.close_connection = True
                self.end_headers()
                if head or not length:
                    return
                try:
                    for block in src.read(start, length):
                        self.wfile.write(block)
                except (OSError, p.ProtocolError):
                    self.close_connection = True

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        threading.Thread(target=self.httpd.serve_forever, daemon=True, name="tailhop-range").start()

    def register(self, source: Source) -> tuple[str, str]:
        """(주소, 지울 때 쓸 열쇠)."""
        key = secrets.token_urlsafe(32)
        with self.lock:
            self.sources[key] = source
        return f"http://127.0.0.1:{self.httpd.server_address[1]}/{key}", key

    def forget(self, key: str) -> None:
        with self.lock:
            self.sources.pop(key, None)

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()


def store_source(st: cs.Store, node: cs.Node, role: str | None = None) -> Source:
    size = node.size if role is None else int((node.variant(role) or {}).get("s", 0))
    mime = "video/mp2t" if role == "play" else "application/octet-stream"
    return Source(size, lambda offset, length: st.read(node, offset, length, role), mime)


# ---------- FFmpeg ----------
_encoder: list[str | None] = []


def pick_encoder(ff: Path) -> str | None:
    """실제로 1초짜리를 만들어 보고 되는 첫 인코더(한 번만 알아본다)."""
    if _encoder:
        return _encoder[0]
    for name in ENCODERS:
        proc = _run([str(ff / "ffmpeg.exe"), "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i",
                     "testsrc2=size=640x360:rate=10:duration=1", "-c:v", name, "-f", "null", "-"])
        try:
            if proc.wait(timeout=30) == 0:
                _encoder.append(name)
                return name
        except subprocess.TimeoutExpired:
            proc.kill()
    _encoder.append(None)
    return None


def probe(ff: Path, url: str) -> dict:
    proc = _run([str(ff / "ffprobe.exe"), "-v", "error", "-print_format", "json", "-show_streams", "-show_format", url])
    out, _ = proc.communicate(timeout=60)
    if proc.returncode != 0:
        raise p.ProtocolError("not_found")
    return json.loads(out or b"{}")


def _video_stream(info: dict) -> dict | None:
    return next((s for s in info.get("streams", []) if s.get("codec_type") == "video"
                 and not (s.get("disposition") or {}).get("attached_pic")), None)


def poster(ff: Path, url: str, duration: float) -> bytes:
    at = min(1.0, duration / 3) if duration > 0 else 0
    proc = _run([str(ff / "ffmpeg.exe"), "-hide_banner", "-loglevel", "error", "-ss", f"{at:.2f}", "-i", url,
                 "-frames:v", "1", "-vf", f"scale='min({POSTER_PX},iw)':-2", "-q:v", "3", "-f", "image2pipe",
                 "-c:v", "mjpeg", "pipe:1"])
    out, _ = proc.communicate(timeout=120)
    if proc.returncode != 0 or not out.startswith(b"\xff\xd8"):
        raise p.ProtocolError("not_found")
    return out


def needs_play(info: dict) -> bool:
    v = _video_stream(info)
    if v is None:
        return False
    short = min(int(v.get("width") or 0), int(v.get("height") or 0))
    kbps = int((info.get("format") or {}).get("bit_rate") or 0) // 1000
    return not (v.get("codec_name") == "h264" and short <= PLAY_SHORT and 0 < kbps <= LIGHT_KBPS)


def transcode(ff: Path, url: str, encoder: str, out_write: Callable[[bytes], None],
              cancelled: Callable[[], bool] = lambda: False) -> None:
    """짧은 변 720의 H.264+AAC MPEG-TS를 표준 출력으로 받아 out_write에 흘린다."""
    scale = (f"scale='if(gt(iw,ih),-2,min({PLAY_SHORT},iw))':'if(gt(iw,ih),min({PLAY_SHORT},ih),-2)'"
             ",format=yuv420p")
    rate = ["-b:v", f"{PLAY_KBPS}k", "-maxrate", f"{PLAY_KBPS * 3 // 2}k", "-bufsize", f"{PLAY_KBPS * 2}k"]
    proc = _run([str(ff / "ffmpeg.exe"), "-hide_banner", "-loglevel", "error", "-i", url, "-map", "0:v:0",
                 "-map", "0:a:0?", "-vf", scale, "-c:v", encoder, *rate, "-g", "60", "-c:a", "aac", "-b:a", "128k",
                 "-ac", "2", "-f", "mpegts", "pipe:1"])
    try:
        for block in iter(lambda: proc.stdout.read(cs.CHUNK), b""):
            if cancelled():
                proc.kill()
                raise p.ProtocolError("disconnected")
            out_write(block)
        if proc.wait(timeout=60) != 0:
            raise p.ProtocolError("not_found")
    finally:
        if proc.poll() is None:
            proc.kill()


# ---------- 일꾼 ----------
class Worker:
    """저장소에 사진·영상이 들어오면 딸린 버전을 만든다. 한 번에 하나씩, 낮은 우선순위."""

    def __init__(self, get_store: Callable[[], cs.Store | None]) -> None:
        self.get_store = get_store
        self.jobs: queue.Queue[str] = queue.Queue()
        self.queued: set[str] = set()
        self.failed: set[tuple[str, int]] = set()
        self.lock = threading.Lock()
        self.server: RangeServer | None = None
        threading.Thread(target=self._run, daemon=True, name="tailhop-media").start()

    def request(self, nid: str) -> None:
        with self.lock:
            if nid in self.queued:
                return
            self.queued.add(nid)
        self.jobs.put(nid)

    def backfill(self) -> None:
        """딸린 버전이 아직 없는 사진·영상을 모두 줄 세운다(열 때, 잠긴 폴더를 연 뒤). 저장소가 열리는 도중에
        불리므로 잠시 뒤 따로 한다."""
        timer = threading.Timer(1.0, self._backfill_now)
        timer.daemon = True
        timer.start()

    def _backfill_now(self) -> None:
        st = self.get_store()
        if st is None or st.state != cs.OPEN:
            return
        with st._mu:
            nodes = list(st.nodes.values())
        for node in nodes:
            if node.parent != cs.TRASH and self._wants(node):
                self.request(node.id)

    def _wants(self, node: cs.Node) -> bool:
        if node.is_dir or (node.id, node.mtime) in self.failed:
            return False
        kind = cloud.file_kind(node.name)
        if kind == "image":
            return node.variant("thumb") is None
        return kind == "video" and node.variant("poster") is None

    def _run(self) -> None:
        while True:
            nid = self.jobs.get()
            with self.lock:
                self.queued.discard(nid)
            st = self.get_store()
            node = st.nodes.get(nid) if st is not None and st.state == cs.OPEN else None
            if node is None or not self._wants(node):
                continue
            try:
                if cloud.file_kind(node.name) == "image":
                    self._image(st, node)
                else:
                    self._video(st, node)
            except Exception:  # noqa: BLE001 - 깨진 파일·못 푸는 형식: 아이콘으로 둔다(다시 시도하지 않는다)
                self.failed.add((node.id, node.mtime))

    def _image(self, st: cs.Store, node: cs.Node) -> None:
        if node.size > hubmod.DECODE_LIMIT:
            raise p.ProtocolError("too_large")
        with st.reader(node) as src, hubmod.open_image(src) as im:
            im.load()
            st.put_bytes_variant(node.id, "thumb", hubmod.jpeg(im, hubmod.THUMB_PX))
            if max(im.size) > SCREEN_PX or node.size > SCREEN_MIN_BYTES:
                st.put_bytes_variant(node.id, "screen", hubmod.jpeg(im, SCREEN_PX, limit=8 * 1024 * 1024))

    def _video(self, st: cs.Store, node: cs.Node) -> None:
        ff = ffmpeg_dir()
        if ff is None:
            raise p.ProtocolError("not_found")
        if self.server is None:
            self.server = RangeServer()
        url, key = self.server.register(store_source(st, node))
        try:
            info = probe(ff, url)
            duration = float((info.get("format") or {}).get("duration") or 0)
            jpg = poster(ff, url, duration)
            with Image.open(io.BytesIO(jpg)) as im:
                st.put_bytes_variant(node.id, "thumb", hubmod.jpeg(im, hubmod.THUMB_PX))
            st.put_bytes_variant(node.id, "poster", jpg)
            encoder = pick_encoder(ff)
            if encoder is None or not needs_play(info):
                return
            oid, okey, writer = st.new_variant_writer()
            try:
                transcode(ff, url, encoder, writer.write, lambda: st.state != cs.OPEN or nid_gone(st, node.id))
                size = writer.finish()
            except BaseException:
                writer.close()
                writer.path.unlink(missing_ok=True)
                raise
            st.attach_variant(node.id, "play", oid, okey, size, writer.path)
        finally:
            self.server.forget(key)


def nid_gone(st: cs.Store, nid: str) -> bool:
    return nid not in st.nodes
