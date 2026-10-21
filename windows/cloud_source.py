"""클라우드 창이 쓰는 허브 하나(이 PC 또는 연결된 기기)를 같은 모양으로 다룬다.

- 이 PC: Node.hub()를 바로 부른다(잠긴 폴더 열쇠는 'local' 기기 이름으로 받는다).
- 다른 기기: Node의 cloud_* 함수(§12, §14, §15)를 부른다. 가져오기·올리기는 보낼 곳 목록(outbox)에 넣는다.
모두 작업 스레드에서 부른다(네트워크·디스크·암호화가 오래 걸린다).
"""
from __future__ import annotations

import io
from pathlib import Path

from PIL import Image

import cloud
import hub as hubmod
import media
import protocol as p

LOCAL = ""  # 왼쪽 목록에서 '이 PC'
LOCAL_PEER = "local"
MOVE_BATCH = 200
STREAM_PIECE = 2 * 1024 * 1024  # 다른 기기의 영상을 재생할 때 한 번에 받는 크기


def _item(raw: dict) -> cloud.Item:
    return cloud.Item(raw["name"], raw["dir"], raw["size"], raw["mtime"], raw.get("locked") is True,
                      raw.get("state") or "", raw.get("progress") or 0, tuple(raw.get("variants") or ()))


class Source:
    def __init__(self, node, sid: str) -> None:
        self.node = node
        self.sid = sid

    @property
    def local(self) -> bool:
        return self.sid == LOCAL

    def _hub(self) -> hubmod.Hub:
        hub = self.node.hub()
        if hub is None:
            state = self.node.cloud_state
            raise p.ProtocolError({"off": "cloud_off", "missing": "store_missing"}.get(state, "store_locked"))
        return hub

    def _header(self, path: str, **fields) -> dict:
        token = self.node.token_for(self.sid, path)
        return {**fields, "token": token} if token else fields

    # ---------- 읽기 ----------
    def list(self, path: str, cursor: int = 0) -> tuple[list[cloud.Item], int | None]:
        if not self.local:
            return self.node.cloud_list(self.sid, path, cursor)
        raw, nxt = self._hub().list(cloud.parse_path(path), cursor, self._header(path), LOCAL_PEER)
        return [_item(x) for x in raw], nxt

    def search(self, path: str, query: str, kind: str, cursor: int = 0) -> tuple[list[dict], int | None, bool]:
        if not self.local:
            return self.node.cloud_search(self.sid, path, query, kind, cursor)
        return self._hub().search(cloud.parse_path(path), query, kind, cursor, self._header(path), LOCAL_PEER)

    def thumb(self, path: str, px: int) -> Image.Image:
        if self.local:
            data, _, _ = self._hub().thumb(cloud.parse_path(path), px, self._header(path), LOCAL_PEER)
        else:
            data = self.node.cloud_thumb(self.sid, path, px)
        with Image.open(io.BytesIO(data)) as im:
            im.load()
            return im.copy()

    def read_variant(self, path: str, role: str, limit: int) -> bytes:
        """허브가 만든 딸린 버전(§17.4) 전체. 없으면 not_found."""
        if not self.local:
            return self.node.cloud_read(self.sid, path, limit, variant=role)
        size, _, read = self._hub().open_file(cloud.parse_path(path), self._header(path), LOCAL_PEER, role)
        if size > limit:
            raise p.ProtocolError("too_large")
        return b"".join(read(0))

    def stream(self, path: str, role: str | None = None) -> media.Source:
        """재생용으로 중간부터 읽을 수 있는 원본(또는 딸린 버전). 다른 기기면 2 MiB씩 범위로 받는다."""
        if self.local:
            hub = self._hub()
            parts = cloud.parse_path(path)
            node = hub.store.node_at(parts, self._header(path), LOCAL_PEER)
            first = media.store_source(hub.store, node, role)

            def checked(offset: int, length: int):
                # 읽을 때마다 잠긴 폴더 열쇠를 다시 확인한다(닫히거나 PIN을 바꾼 뒤에는 그 주소로도 못 읽는다)
                live = self._hub().store.node_at(parts, self._header(path), LOCAL_PEER)
                return self._hub().store.read(live, offset, length, role)

            return media.Source(first.size, checked, first.mime)
        size = self.node.cloud_size(self.sid, path, role)

        def read(offset: int, length: int):
            end = offset + length
            while offset < end:
                part = min(STREAM_PIECE, end - offset)
                data = self.node.cloud_read(self.sid, path, part, offset=offset, length=part, variant=role)
                if not data:
                    return
                yield data
                offset += len(data)

        return media.Source(size, read, "video/mp2t" if role == "play" else "application/octet-stream")

    def read_bytes(self, path: str, limit: int) -> bytes:
        """파일 전체를 메모리로(라이트박스의 원본 보기). limit보다 크면 too_large. 잠긴 폴더 안이어도 디스크에 쓰지 않는다."""
        if not self.local:
            return self.node.cloud_read(self.sid, path, limit)
        size, _, read = self._hub().open_file(cloud.parse_path(path), self._header(path), LOCAL_PEER)
        if size > limit:
            raise p.ProtocolError("too_large")
        data = b"".join(read(0))
        if len(data) != size:
            raise p.ProtocolError("file_incomplete")
        return data

    # ---------- 잠긴 폴더 ----------
    def open(self, path: str, pin: str | None = None, recovery: str | None = None) -> None:
        if not self.local:
            self.node.cloud_open(self.sid, path, pin, recovery)
            return
        header = {"pin": pin} if pin is not None else {"recovery": recovery}
        token = self._hub().open(cloud.parse_path(path), header, LOCAL_PEER)
        self.node._open_tokens[(LOCAL, path)] = token

    def lock(self, path: str, pin: str) -> str:
        if not self.local:
            return self.node.cloud_lock(self.sid, path, pin)
        return self._hub().lock(cloud.parse_path(path), pin)

    def unlock(self, path: str, pin: str) -> None:
        if not self.local:
            self.node.cloud_unlock(self.sid, path, pin)
            return
        self._hub().unlock(cloud.parse_path(path), pin, LOCAL_PEER)
        self.node._open_tokens.pop((LOCAL, path), None)

    def repin(self, path: str, new_pin: str, pin: str | None = None, recovery: str | None = None) -> None:
        if not self.local:
            self.node.cloud_repin(self.sid, path, new_pin, pin, recovery)
            return
        header = {"new_pin": new_pin, "pin": pin, "recovery": recovery}
        self._hub().repin(cloud.parse_path(path), header, LOCAL_PEER)

    def is_open(self, path: str) -> bool:
        return self.node.token_for(self.sid, path) is not None

    # ---------- 바꾸기 ----------
    def mkdir(self, path: str) -> None:
        if not self.local:
            self.node.cloud_mkdir(self.sid, path)
            return
        self._hub().mkdir(cloud.parse_path(path), self._header(path), LOCAL_PEER)

    def delete(self, path: str) -> None:
        if not self.local:
            self.node.cloud_delete(self.sid, path)
            return
        self._hub().delete(cloud.parse_path(path), self._header(path), LOCAL_PEER)
        self.node._open_tokens.pop((LOCAL, path), None)

    def move(self, paths: list[str], dest: str) -> tuple[list[dict], list[dict]]:
        """§16: 여러 항목을 dest 폴더로 옮긴다. (옮긴 것, 안 된 것)."""
        if not self.local:
            return self.node.cloud_move(self.sid, paths, dest)
        moved, failed = [], []
        for start in range(0, len(paths), MOVE_BATCH):
            batch = paths[start:start + MOVE_BATCH]
            header = self._header(dest)
            tokens = {self.node.token_for(self.sid, x) for x in batch} - {None, header.get("token")}
            if tokens:
                header = {**header, "tokens": sorted(tokens)}
            got = self._hub().move([cloud.parse_path(x) for x in batch], cloud.parse_path(dest), header, LOCAL_PEER)
            moved += got[0]
            failed += got[1]
        self.node.retarget_tokens(self.sid, moved, dest)
        return moved, failed

    def rename(self, items: list[tuple[str, str]]) -> tuple[list[dict], list[dict]]:
        """§18.2: [(경로, 새 이름)]. (바꾼 것, 안 된 것)."""
        if not self.local:
            return self.node.cloud_rename(self.sid, items)
        tokens = sorted({self.node.token_for(self.sid, path) for path, _ in items} - {None})
        header = {"token": tokens[0], "tokens": tokens} if tokens else {}
        renamed, failed = self._hub().rename([(cloud.parse_path(path), name) for path, name in items], header,
                                             LOCAL_PEER)
        for item in renamed:
            self.node.retarget_tokens(self.sid, [item], item["path"].rpartition("/")[0])
        return renamed, failed

    def upload(self, paths: list[Path], folder: str) -> int:
        """다른 기기면 보낼 곳 목록에 넣고, 이 PC면 바로 복사한다(잠긴 폴더 안이면 암호화해 넣는다). 넣은 수."""
        if not self.local:
            return len(self.node.outbox.add_cloud_upload(self.sid, paths, folder))
        hub = self._hub()
        count = 0
        for src in paths:
            for file, rel_dir in _walk(src):
                dest = "/".join(x for x in (folder, rel_dir) if x)
                target = hub.put_target(dest, self._header(dest), LOCAL_PEER)
                hub.store.import_file(target.folder, target.ring, file)  # 원본은 그대로 두고 암호화한 사본을 넣는다
                count += 1
        return count

    def export(self, path: str, dest_dir: Path) -> Path:
        """이 PC 저장소의 파일을 저장 폴더로 풀어 꺼낸다(복호화한 사본, 사용자가 고른 것만)."""
        return self._hub().store.export(cloud.parse_path(path), self._header(path), LOCAL_PEER, dest_dir)


def _walk(src: Path):
    """(파일, 올릴 폴더 안 상대 폴더). 폴더는 같은 모양으로, 링크는 따라가지 않는다."""
    if src.is_file():
        yield src, ""
        return
    base = src.resolve()
    for file in sorted(base.rglob("*")):
        if file.is_file() and not file.is_symlink() and not any(x.is_symlink() for x in file.parents if x != base.parent):
            rel = file.parent.relative_to(base.parent).as_posix()
            yield file, rel

