"""클라우드 창이 쓰는 허브 하나(이 PC 또는 연결된 기기)를 같은 모양으로 다룬다.

- 이 PC: Node.hub()를 바로 부른다(잠긴 폴더 열쇠는 'local' 기기 이름으로 받는다).
- 다른 기기: Node의 cloud_* 함수(§12, §14, §15)를 부른다. 가져오기·올리기는 보낼 곳 목록(outbox)에 넣는다.
  그 허브의 동기화 사본(§19.3)에 같은 경로·크기·수정 시각인 파일이 있으면 사본에서 읽는다(빠르고 망을 쓰지 않는다).
  offline이면(허브가 꺼졌거나 닿지 않음) 사본만 읽기 전용으로 보인다.
모두 작업 스레드에서 부른다(네트워크·디스크·암호화가 오래 걸린다).
"""
from __future__ import annotations

import io
import os
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
                      raw.get("state") or "", raw.get("progress") or 0, tuple(raw.get("variants") or ()),
                      raw.get("sync") is True)


class Source:
    def __init__(self, node, sid: str, offline: bool = False) -> None:
        self.node = node
        self.sid = sid
        self.offline = offline and sid != LOCAL

    @property
    def local(self) -> bool:
        return self.sid == LOCAL

    # ---------- 동기화 사본(§19.3) ----------
    def mirror(self):
        if self.local:
            return None
        return self.node.syncer.mirror(self.sid)

    def _copy(self, path: str, hint: cloud.Item | None):
        """사본의 그 파일(다 받은 것). 허브가 켜져 있으면 크기·수정 시각까지 같아야 한다(바뀐 것을 옛 사본으로 열지 않게).
        사본이 잠겨 있으면: 켜진 허브를 볼 때는 허브에서 받고(None), 오프라인이면 mirror_locked(열어 달라고 묻는다)."""
        found = self.mirror()
        if found is None or (not self.offline and hint is None):
            return None
        try:
            info = found.find(path) if self.offline else found.find(path, hint.size, hint.mtime)
        except p.ProtocolError as exc:
            if exc.code == "mirror_locked" and not self.offline:
                return None
            raise
        if info is not None:
            self.node.syncer.touch()
        return info

    def _offline_copy(self, path: str):
        info = self._copy(path, None)
        if info is None:
            raise p.ProtocolError("not_found")
        return self.mirror(), info

    def _read_only(self) -> None:
        if self.offline:
            raise p.ProtocolError("sync_readonly")

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
        if self.offline:
            found = self.mirror()
            if found is not None:
                self.node.syncer.touch()
            return (found.list_dir(path) if found is not None else []), None
        if not self.local:
            return self.node.cloud_list(self.sid, path, cursor)
        raw, nxt = self._hub().list(cloud.parse_path(path), cursor, self._header(path), LOCAL_PEER)
        return [_item(x) for x in raw], nxt

    def search(self, path: str, query: str, kind: str, cursor: int = 0) -> tuple[list[dict], int | None, bool]:
        if self.offline:
            return self._search_copy(path, query, kind), None, False
        if not self.local:
            return self.node.cloud_search(self.sid, path, query, kind, cursor)
        return self._hub().search(cloud.parse_path(path), query, kind, cursor, self._header(path), LOCAL_PEER)

    def _search_copy(self, path: str, query: str, kind: str) -> list[dict]:
        found = self.mirror()
        if found is None:
            return []
        prefix = path + "/" if path else ""
        out = []
        for info in list(found.entries.values()):
            full = info.get("path", "")
            if not full.startswith(prefix) or not found.complete(info):
                continue
            rel = full[len(prefix):]
            if cloud.matches(rel.rpartition("/")[2], False, query, kind):
                out.append({"name": rel.rpartition("/")[2], "dir": False, "size": int(info["size"]),
                            "mtime": int(info.get("mtime", 0)), "path": rel})
        out.sort(key=lambda x: (x["path"].casefold(), x["path"]))
        return out

    def thumb(self, path: str, px: int, hint: cloud.Item | None = None) -> Image.Image:
        copy = self._copy(path, hint)
        data = self.mirror().thumb(copy) if copy is not None else None
        if data is None and self.offline:
            if copy is None:
                raise p.ProtocolError("not_found")
            if cloud.file_kind(path) != "image" or int(copy["size"]) > 40 << 20:
                raise p.ProtocolError("not_found")
            data = self.mirror().read_all(copy, 40 << 20)  # 미리보기를 받기 전: 사진 원본을 메모리에서 줄인다
            with Image.open(io.BytesIO(data)) as im:
                im.thumbnail((px, px))
                return im.convert("RGB").copy()
        if data is not None:
            with Image.open(io.BytesIO(data)) as im:
                im.load()
                return im.copy()
        if self.local:
            data, _, _ = self._hub().thumb(cloud.parse_path(path), px, self._header(path), LOCAL_PEER)
        else:
            data = self.node.cloud_thumb(self.sid, path, px)
        with Image.open(io.BytesIO(data)) as im:
            im.load()
            return im.copy()

    def read_variant(self, path: str, role: str, limit: int) -> bytes:
        """허브가 만든 딸린 버전(§17.4) 전체. 없으면 not_found."""
        if self.offline:
            raise p.ProtocolError("not_found")  # 사본에는 원본과 미리보기만 있다
        if not self.local:
            return self.node.cloud_read(self.sid, path, limit, variant=role)
        size, _, read = self._hub().open_file(cloud.parse_path(path), self._header(path), LOCAL_PEER, role)
        if size > limit:
            raise p.ProtocolError("too_large")
        return b"".join(read(0))

    def stream(self, path: str, role: str | None = None, hint: cloud.Item | None = None) -> media.Source:
        """재생용으로 중간부터 읽을 수 있는 원본(또는 딸린 버전). 다른 기기면 2 MiB씩 범위로 받는다.
        사본에 그 파일이 있으면 원본을 사본에서(재생용 720p보다 낫고 망을 쓰지 않는다)."""
        copy = self._copy(path, hint)
        if copy is not None:
            found = self.mirror()
            return media.Source(int(copy["size"]), lambda offset, length: found.read(copy, offset, length),
                                "application/octet-stream")
        if self.offline:
            raise p.ProtocolError("not_found")
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

    def read_bytes(self, path: str, limit: int, hint: cloud.Item | None = None) -> bytes:
        """파일 전체를 메모리로(라이트박스의 원본 보기). limit보다 크면 too_large. 잠긴 폴더 안이어도 디스크에 쓰지 않는다."""
        copy = self._copy(path, hint)
        if copy is not None:
            return self.mirror().read_all(copy, limit)
        if self.offline:
            raise p.ProtocolError("not_found")
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
        if self.offline:  # §19.6 사본의 잠긴 폴더: 사본이 열려 있고 그 폴더의 PIN도 맞아야 한다
            found = self.mirror()
            vid = found.vault_at(path) if found is not None else None
            if vid is None:
                raise p.ProtocolError("not_found")
            found.open_vault(vid, pin, recovery)
            return
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
        if self.offline:
            found = self.mirror()
            try:
                vid = found.vault_at(path) if found is not None else None
            except p.ProtocolError:
                return False
            return vid is not None and found.vault_open(vid)
        return self.node.token_for(self.sid, path) is not None

    # ---------- 모든 기기 동기화(§19.2) ----------
    def sync_set(self, paths: list[str], on: bool) -> tuple[int, list[dict]]:
        self._read_only()
        if not self.local:
            return self.node.sync_set(self.sid, paths, on)
        tokens = sorted({self.node.token_for(self.sid, x) for x in paths} - {None})
        header = {"token": tokens[0], "tokens": tokens} if tokens else {}
        return self._hub().sync_set([cloud.parse_path(x) for x in paths], on, header, LOCAL_PEER)

    def sync_all(self, on: bool) -> None:
        self._read_only()
        if not self.local:
            self.node.sync_all(self.sid, on)
            return
        self._hub().sync_all(on)

    # ---------- 바꾸기 ----------
    def mkdir(self, path: str) -> None:
        self._read_only()
        if not self.local:
            self.node.cloud_mkdir(self.sid, path)
            return
        self._hub().mkdir(cloud.parse_path(path), self._header(path), LOCAL_PEER)

    def delete(self, path: str) -> None:
        self._read_only()
        if not self.local:
            self.node.cloud_delete(self.sid, path)
            return
        self._hub().delete(cloud.parse_path(path), self._header(path), LOCAL_PEER)
        self.node._open_tokens.pop((LOCAL, path), None)

    def move(self, paths: list[str], dest: str) -> tuple[list[dict], list[dict]]:
        """§16: 여러 항목을 dest 폴더로 옮긴다. (옮긴 것, 안 된 것)."""
        self._read_only()
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
        self._read_only()
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
        self._read_only()
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
        """이 PC 저장소(또는 오프라인이면 동기화 사본)의 파일을 저장 폴더로 풀어 꺼낸다(복호화한 사본, 사용자가 고른 것만)."""
        if self.offline:
            found, info = self._offline_copy(path)
            Path(dest_dir).mkdir(parents=True, exist_ok=True)
            dest = _free_path(Path(dest_dir) / p.sanitize_filename(path.rpartition("/")[2]))
            tmp = dest.with_name(dest.name + ".part")
            try:
                with open(tmp, "xb") as out:
                    for block in found.read(info):
                        out.write(block)
                os.replace(tmp, dest)
            except BaseException:
                tmp.unlink(missing_ok=True)
                raise
            return dest
        return self._hub().store.export(cloud.parse_path(path), self._header(path), LOCAL_PEER, dest_dir)


def _free_path(path: Path) -> Path:
    if not path.exists():
        return path
    n = 1
    while True:
        candidate = path.with_name(f"{path.stem} ({n}){path.suffix}")
        if not candidate.exists():
            return candidate
        n += 1


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

