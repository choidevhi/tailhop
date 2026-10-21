"""클라우드 허브의 요청 처리(docs/PROTOCOL.md §12, §14–§17). 모든 내용은 암호화 저장소(cloudstore.Store)에 있다.

요청 모양(경로·쪽·잠긴 폴더 열쇠)은 2.1까지와 같다. 폰·다른 PC는 저장소가 암호화됐는지 몰라도 된다.
- 미리보기: 그 항목에 딸린 미리보기(thumb)가 있으면 그것을, 없으면 사진은 원본을 메모리에서 풀어 만들고 딸려 둔다.
  잠긴 폴더 안의 것도 같다(딸린 파일도 그 폴더 열쇠로 잠긴 meta 안에 열쇠가 있다).
- 가져오기: 원본 또는 딸린 버전(variant: thumb·screen·play·poster)을 offset부터, length를 주면 그만큼만(§17.3).
"""
from __future__ import annotations

import io
import json
from dataclasses import dataclass

from PIL import Image

import cloud
import cloudstore as cs
import protocol as p

THUMB_MAX = 256 * 1024
THUMB_PX = 512  # 딸려 두는 미리보기의 긴 변
DECODE_LIMIT = 200 * 1024 * 1024  # 미리보기를 만들려고 메모리에서 풀어 보는 원본 크기 상한
MAX_PIXELS = 170_000_000
VARIANTS = ("thumb", "screen", "play", "poster")
SYNC_PAGE_BYTES = 600_000  # §19.2 목록 한 쪽의 글자 수 상한(프레임 1 MiB 안)


def jpeg(image: Image.Image, px: int, limit: int = THUMB_MAX) -> bytes:
    image = image.convert("RGB")
    image.thumbnail((px, px))
    for quality in (85, 70, 55, 40):
        buf = io.BytesIO()
        image.save(buf, "JPEG", quality=quality, optimize=True)
        if buf.tell() <= limit:
            return buf.getvalue()
    raise p.ProtocolError("not_found")


def open_image(src) -> Image.Image:
    """사진을 연다(EXIF 회전 반영). 너무 크면(압축 폭탄) not_found."""
    from PIL import ImageOps
    im = Image.open(src)
    w, h = im.size
    if w <= 0 or h <= 0 or w * h > MAX_PIXELS:
        raise p.ProtocolError("not_found")
    im.draft("RGB", (THUMB_PX * 4, THUMB_PX * 4))
    return ImageOps.exif_transpose(im)


@dataclass
class StoreTarget:
    """cloud_put·모으기로 받을 폴더(그 폴더 id와 그 안 줄의 열쇠)."""
    store: cs.Store
    folder: str
    ring: str

    def begin(self, name: str, size: int, mtime: int, peer: str, xfer: str) -> cs.Upload:
        return self.store.begin_upload(self.folder, self.ring, name, size, mtime, peer, xfer)

    def resume(self, peer: str, xfer: str, size: int, offset: int) -> cs.Upload:
        return self.store.resume_upload(peer, xfer, size, offset, self.folder, self.ring)

    @property
    def secret(self) -> bool:
        return self.ring != cs.MAIN


class Hub:
    def __init__(self, store: cs.Store) -> None:
        self.store = store

    # ---------- 목록 ----------
    def list(self, parts: list[str], cursor: int, header: dict, peer: str) -> tuple[list[dict], int | None]:
        items = self.store.list_children(parts, header, peer)
        page = items[cursor:cursor + cloud.PAGE]
        return page, cursor + cloud.PAGE if cursor + cloud.PAGE < len(items) else None

    def search(self, parts: list[str], query: str, kind: str, cursor: int, header: dict,
               peer: str) -> tuple[list[dict], int | None, bool]:
        found, partial = self.store.search(parts, query, kind, header, peer)
        page = found[cursor:cursor + cloud.PAGE]
        return page, cursor + cloud.PAGE if cursor + cloud.PAGE < len(found) else None, partial

    # ---------- 가져오기 ----------
    def open_file(self, parts: list[str], header: dict, peer: str, role: str | None = None):
        """(크기, 수정 시각 ms, read(offset, length)). role이 있으면 그 딸린 버전."""
        if not parts:
            raise p.ProtocolError("bad_path")
        node = self.store.node_at(parts, header, peer)
        if node.is_dir:
            raise p.ProtocolError("not_found")
        if role is not None:
            ref = node.variant(role)
            if ref is None:
                raise p.ProtocolError("not_found")
            size = int(ref["s"])
        else:
            size = node.size
        return size, node.mtime, lambda offset, length=None: self.store.read(node, offset, length, role)

    def thumb(self, parts: list[str], px: int, header: dict, peer: str) -> tuple[bytes, int, int]:
        """JPEG 미리보기(바이트, 가로, 세로)."""
        if not parts:
            raise p.ProtocolError("not_found")
        node = self.store.node_at(parts, header, peer)
        if node.is_dir:
            raise p.ProtocolError("not_found")
        if node.variant("thumb") is None:
            if cloud.file_kind(node.name) != "image" or node.size > DECODE_LIMIT:
                raise p.ProtocolError("not_found")  # 영상 미리보기는 들어올 때 따로 만든다(§17.4)
            try:
                with self.store.reader(node) as src, open_image(src) as im:
                    big = jpeg(im, THUMB_PX)
            except (OSError, Image.DecompressionBombError, ValueError) as exc:
                raise p.ProtocolError("not_found") from exc
            self.store.put_bytes_variant(node.id, "thumb", big)
        else:
            big = self.store.read_all(node, THUMB_MAX * 4, "thumb")
        with Image.open(io.BytesIO(big)) as im:
            data = big if max(im.size) <= px and len(big) <= THUMB_MAX else jpeg(im, px)
        with Image.open(io.BytesIO(data)) as im:
            return data, im.width, im.height

    # ---------- 바꾸기 ----------
    def mkdir(self, parts: list[str], header: dict, peer: str) -> None:
        self.store.mkdir(parts, header, peer)

    def delete(self, parts: list[str], header: dict, peer: str) -> None:
        self.store.delete(parts, header, peer)

    def move(self, paths: list[list[str]], dest: list[str], header: dict, peer: str) -> tuple[list[dict], list[dict]]:
        return self.store.move(paths, dest, header, peer)

    def rename(self, items: list[tuple[list[str], str]], header: dict, peer: str) -> tuple[list[dict], list[dict]]:
        return self.store.rename(items, header, peer)

    # ---------- 모든 기기 동기화(§19.2) ----------
    def sync_set(self, paths: list[list[str]], on: bool, header: dict, peer: str) -> tuple[int, list[dict]]:
        return self.store.sync_set(paths, on, header, peer)

    def sync_all(self, on: bool) -> None:
        self.store.set_sync_all(on)

    def sync_digest(self) -> str:
        return self.store.sync_manifest()[0]

    def sync_page(self, cursor: int) -> tuple[str, list[dict], int | None]:
        """한 쪽: 최대 SYNC_PAGE개, 응답이 한 프레임(1 MiB)을 넘지 않게 글자 수로도 자른다."""
        digest, items = self.store.sync_manifest()
        page, size, nxt = [], 0, cursor
        while nxt < len(items) and len(page) < cs.SYNC_PAGE:
            size += len(json.dumps(items[nxt], ensure_ascii=False).encode("utf-8")) + 2
            if page and size > SYNC_PAGE_BYTES:
                break
            page.append(items[nxt])
            nxt += 1
        return digest, page, nxt if nxt < len(items) else None

    def put_target(self, dir_text: object, header: dict, peer: str) -> StoreTarget:
        folder, ring = self.store.folder_for_put(cloud.parse_path(dir_text), header, peer)
        return StoreTarget(self.store, folder, ring)

    # ---------- 잠그기 ----------
    def lock(self, parts: list[str], pin: object) -> str:
        if not parts:
            raise p.ProtocolError("bad_path")
        return self.store.lock(parts, pin)

    def open(self, parts: list[str], header: dict, peer: str) -> str:
        pin, recovery = header.get("pin"), header.get("recovery")
        return self.store.open_vault(parts, peer, pin if isinstance(pin, str) else None,
                                     recovery if isinstance(recovery, str) else None)

    def unlock(self, parts: list[str], pin: object, peer: str) -> None:
        if not isinstance(pin, str):
            raise p.ProtocolError("vault_pin")
        self.store.unlock_vault(parts, pin, peer)

    def repin(self, parts: list[str], header: dict, peer: str) -> None:
        pin, recovery, new_pin = header.get("pin"), header.get("recovery"), header.get("new_pin")
        if not isinstance(new_pin, str):
            raise p.ProtocolError("vault_pin")
        self.store.repin(parts, new_pin, pin if isinstance(pin, str) else None,
                         recovery if isinstance(recovery, str) else None, peer)

    def is_vault_path(self, path: str) -> bool:
        try:
            return self.store.is_vault_path(path)
        except p.ProtocolError:
            return False


class HubAccess:
    """한 연결(한 상대 기기)에서 protocol.receive_message가 받을 자리를 물을 때 쓰는 얇은 묶음."""

    def __init__(self, hub: Hub, peer: str) -> None:
        self.hub, self.peer = hub, peer

    def put_target(self, dir_text: object, header: dict) -> StoreTarget:
        return self.hub.put_target(dir_text, header, self.peer)
