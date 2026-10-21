"""클라우드 창이 쓰는 허브 하나(이 PC 또는 연결된 기기)를 같은 모양으로 다룬다.

- 이 PC: Node.hub()를 바로 부른다(잠긴 폴더 열쇠는 'local' 기기 이름으로 받는다).
- 다른 기기: Node의 cloud_* 함수(§12, §14, §15)를 부른다. 가져오기·올리기는 보낼 곳 목록(outbox)에 넣는다.
모두 작업 스레드에서 부른다(네트워크·디스크·암호화가 오래 걸린다).
"""
from __future__ import annotations

import io
import shutil
from pathlib import Path

from PIL import Image

import cloud
import hub as hubmod
import protocol as p

LOCAL = ""  # 왼쪽 목록에서 '이 PC'
LOCAL_PEER = "local"


def _item(raw: dict) -> cloud.Item:
    return cloud.Item(raw["name"], raw["dir"], raw["size"], raw["mtime"], raw.get("locked") is True,
                      raw.get("state") or "", raw.get("progress") or 0)


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
            raise p.ProtocolError("cloud_off")
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
                name = p.sanitize_filename(file.name)
                if isinstance(target, Path):
                    shutil.copy2(file, p.unique_path(target, name))
                else:
                    with target.vault.lock:
                        rel = target.vault.free_name(f"{target.rel_dir}/{p.cloud_name(name)}" if target.rel_dir
                                                     else p.cloud_name(name))
                    target.vault.put_file(rel, file)
                count += 1
        return count

    def export(self, path: str, dest_dir: Path) -> Path:
        """이 PC의 잠긴 폴더 안 파일을 저장 폴더로 풀어 꺼낸다(복호화한 사본)."""
        size, _, read = self._hub().open_file(cloud.parse_path(path), self._header(path), LOCAL_PEER)
        dest_dir.mkdir(parents=True, exist_ok=True)
        target = p.unique_path(dest_dir, p.sanitize_filename(path.rsplit("/", 1)[-1]))
        part = target.with_name(target.name + ".part")
        with open(part, "wb") as out:
            for block in read(0):
                out.write(block)
        if part.stat().st_size != size:
            part.unlink(missing_ok=True)
            raise p.ProtocolError("file_incomplete")
        part.replace(target)
        return target


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

