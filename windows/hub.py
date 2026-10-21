"""클라우드 허브의 요청 처리(docs/PROTOCOL.md §12, §14, §15).

평범한 폴더는 cloud.CloudFolder가, 잠긴 폴더(보관함)는 vault.VaultManager가 맡고, 여기서는 경로에 따라 둘 중 하나로 보낸다.
- 잠긴 폴더 자리(mount)와 그 안의 경로는 보관함으로 간다. 요청에 그 기기의 열쇠(token)가 있어야 한다.
- 평범한 폴더 목록에는 그 자리에 잠긴 폴더 항목을 끼워 넣는다(잠그는 중이면 남은 평문 폴더는 숨긴다).
- 썸네일: 평범한 파일은 thumbs 캐시를 쓰고, 보관함 안의 사진은 메모리에서만 만든다(평문 캐시를 남기지 않는다).
"""
from __future__ import annotations

import io
from dataclasses import dataclass

from PIL import Image

import cloud
import protocol as p
import thumbs
import vault

THUMB_MAX = 256 * 1024
VAULT_THUMB_LIMIT = 50 * 1024 * 1024  # 보관함 안 사진은 이 크기까지만 메모리에 풀어 미리보기를 만든다


@dataclass
class VaultTarget:
    """보관함 안으로 올리기(cloud_put)를 받을 자리."""
    vault: vault.Vault
    rel_dir: str


def _jpeg(image: Image.Image, px: int) -> bytes:
    image = image.convert("RGB")
    image.thumbnail((px, px))
    for quality in (85, 70, 55, 40):
        buf = io.BytesIO()
        image.save(buf, "JPEG", quality=quality, optimize=True)
        if buf.tell() <= THUMB_MAX:
            return buf.getvalue()
    raise p.ProtocolError("not_found")


class Hub:
    def __init__(self, folder: cloud.CloudFolder, vaults: vault.VaultManager) -> None:
        self.folder = folder
        self.vaults = vaults

    # ---------- 경로 ----------
    def _route(self, path: str, header: dict, peer: str) -> tuple[vault.Mount, vault.Vault, str] | None:
        found = self.vaults.mount_of(path)
        if found is None:
            return None
        mount, inner = found
        try:
            return mount, self.vaults.vault_for(mount, header.get("token"), peer), inner
        except p.ProtocolError as exc:
            if exc.code == "vault_locked":  # 어느 잠긴 폴더의 PIN을 물어야 하는지 알려 준다(§14.2)
                raise p.ProtocolError("vault_locked", mount=mount.mount) from exc
            raise

    # ---------- 목록 ----------
    def list(self, parts: list[str], cursor: int, header: dict, peer: str) -> tuple[list[dict], int | None]:
        path = cloud.join_path(parts)
        routed = self._route(path, header, peer)
        if routed is not None:
            _, v, inner = routed
            if not v.is_dir(inner):
                raise p.ProtocolError("not_found")
            items = [cloud.item_json(cloud.Item(name, e.is_dir, 0 if e.is_dir else e.size, e.mtime))
                     for name, e in v.children(inner)]
        else:
            locked = {m.mount.rsplit("/", 1)[-1]: m for m in self.vaults.mounts_under(path)}
            hidden = {name.casefold() for name in locked}  # 잠그는 중 남은 평문 폴더는 숨긴다
            items = [cloud.item_json(x) for x in self.folder.list_all(parts) if x.name.casefold() not in hidden]
            for name, m in locked.items():
                items.append({"name": name, "dir": True, "size": 0, "mtime": 0, "locked": True,
                              "state": self.vaults.display_state(m), "progress": m.progress})
        items.sort(key=lambda x: (not x["dir"], x["name"].casefold(), x["name"]))
        page = items[cursor:cursor + cloud.PAGE]
        return page, cursor + cloud.PAGE if cursor + cloud.PAGE < len(items) else None

    def search(self, parts: list[str], query: str, kind: str, cursor: int, header: dict,
               peer: str) -> tuple[list[dict], int | None, bool]:
        path = cloud.join_path(parts)
        routed = self._route(path, header, peer)
        if routed is not None:
            _, v, inner = routed
            prefix = inner + "/" if inner else ""
            found = []
            for key, e in v.entries.items():
                if not key.startswith(prefix) or key == inner:
                    continue
                name = key.rsplit("/", 1)[-1]
                if cloud.matches(name, e.is_dir, query, kind):
                    found.append(cloud.item_json(cloud.Item(name, e.is_dir, 0 if e.is_dir else e.size, e.mtime),
                                                 path=key[len(prefix):]))
            found.sort(key=lambda x: (x["path"].casefold(), x["path"]))
            partial = False
        else:
            found, partial = self.folder.search(parts, query, kind,
                                                lambda full: self.vaults.mount_of(full) is not None)
        page = found[cursor:cursor + cloud.PAGE]
        return page, cursor + cloud.PAGE if cursor + cloud.PAGE < len(found) else None, partial

    # ---------- 가져오기 ----------
    def open_file(self, parts: list[str], header: dict, peer: str):
        """(크기, 수정 시각 ms, offset부터 평문을 주는 함수). 보관함 안이면 복호화해서 준다."""
        path = cloud.join_path(parts)
        routed = self._route(path, header, peer)
        if routed is not None:
            _, v, inner = routed
            entry = v.entries.get(inner)
            if entry is None or entry.is_dir:
                raise p.ProtocolError("not_found")
            return entry.size, entry.mtime, lambda offset: v.read(inner, offset)
        file_path, size, mtime = self.folder.stat_file(parts)

        def read(offset: int):
            with open(file_path, "rb") as src:
                src.seek(offset)
                yield from iter(lambda: src.read(p.CHUNK), b"")

        return size, mtime, read

    def thumb(self, parts: list[str], px: int, header: dict, peer: str) -> tuple[bytes, int, int]:
        """JPEG 미리보기(바이트, 가로, 세로)."""
        path = cloud.join_path(parts)
        routed = self._route(path, header, peer)
        name = parts[-1] if parts else ""
        if routed is not None:
            _, v, inner = routed
            if cloud.file_kind(name) != "image":
                raise p.ProtocolError("not_found")  # 보관함 안 영상은 평문 파일이 없어 셸 미리보기를 쓸 수 없다
            try:
                with Image.open(io.BytesIO(v.read_all(inner, VAULT_THUMB_LIMIT))) as im:
                    data = _jpeg(im, px)
            except (OSError, Image.DecompressionBombError) as exc:
                raise p.ProtocolError("not_found") from exc
        else:
            file_path, _, _ = self.folder.stat_file(parts)
            if not thumbs.previewable(name):
                raise p.ProtocolError("not_found")
            cached = thumbs.make(file_path)
            if cached is None:
                raise p.ProtocolError("not_found")
            with Image.open(cached) as im:
                data = _jpeg(im, px)
        with Image.open(io.BytesIO(data)) as im:
            return data, im.width, im.height

    # ---------- 바꾸기 ----------
    def mkdir(self, parts: list[str], header: dict, peer: str) -> None:
        routed = self._route(cloud.join_path(parts), header, peer)
        if routed is None:
            self.folder.mkdir(parts)
            return
        _, v, inner = routed
        with v.lock:
            v.ensure_dirs(inner)
            v.save_index()

    def delete(self, parts: list[str], header: dict, peer: str) -> None:
        path = cloud.join_path(parts)
        routed = self._route(path, header, peer)
        if routed is not None:
            mount, v, inner = routed
            if inner:
                v.delete(inner)
            else:
                self.vaults.destroy(mount)
            return
        if self.vaults.any_inside(path):
            raise p.ProtocolError("bad_path")  # 잠긴 폴더를 품은 폴더는 지우지 않는다(열쇠 없이 보관함이 사라지지 않게)
        self.folder.delete(parts)

    def put_target(self, dir_text: object, header: dict, peer: str):
        """cloud_put을 받을 곳: 평범한 폴더(Path) 또는 보관함 안(VaultTarget)."""
        parts = cloud.parse_path(dir_text)
        routed = self._route(cloud.join_path(parts), header, peer)
        if routed is None:
            return self.folder.folder_for_put(parts)
        _, v, inner = routed
        if not v.is_dir(inner):
            with v.lock:
                v.ensure_dirs(inner)
                v.save_index()
        return VaultTarget(v, inner)

    # ---------- 잠그기 ----------
    def lock(self, parts: list[str], pin: object) -> str:
        path = cloud.join_path(parts)
        if not parts or self.vaults.mount_of(path) is not None or self.vaults.any_inside(path):
            raise p.ProtocolError("bad_path")
        folder = self.folder._existing(parts, want_dir=True)
        if not isinstance(pin, str) or not vault.valid_pin(pin):
            raise p.ProtocolError("vault_pin")
        return self.vaults.lock_folder(path, folder, pin)

    def open(self, parts: list[str], header: dict, peer: str) -> str:
        pin, recovery = header.get("pin"), header.get("recovery")
        return self.vaults.open(cloud.join_path(parts), peer, pin if isinstance(pin, str) else None,
                                recovery if isinstance(recovery, str) else None)

    def unlock(self, parts: list[str], pin: object, peer: str) -> None:
        if not isinstance(pin, str):
            raise p.ProtocolError("vault_pin")
        self.vaults.unlock_folder(cloud.join_path(parts), pin, peer)

    def repin(self, parts: list[str], header: dict, peer: str) -> None:
        pin, recovery, new_pin = header.get("pin"), header.get("recovery"), header.get("new_pin")
        if not isinstance(new_pin, str):
            raise p.ProtocolError("vault_pin")
        self.vaults.repin(cloud.join_path(parts), new_pin, pin if isinstance(pin, str) else None,
                          recovery if isinstance(recovery, str) else None, peer)

    def is_vault_path(self, path: str) -> bool:
        return self.vaults.mount_of(path) is not None


class HubAccess:
    """한 연결(한 상대 기기)에서 protocol.receive_message가 cloud_put 자리를 물을 때 쓰는 얇은 묶음."""

    def __init__(self, hub: Hub, peer: str) -> None:
        self.hub, self.peer = hub, peer

    def put_target(self, dir_text: object, header: dict):
        return self.hub.put_target(dir_text, header, self.peer)

