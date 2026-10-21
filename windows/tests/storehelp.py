"""테스트 도우미: 허브 노드에 암호화 저장소(§17)를 만들고, 그 안에 파일을 넣거나 읽는다."""
from __future__ import annotations

import os
from pathlib import Path

import cloud
import cloudstore as cs

PW = "test password 1234"


def open_hub(node, folder: Path) -> cs.Store:
    folder.mkdir(parents=True, exist_ok=True)
    node.set_cloud(True, str(folder))
    st = node.store()
    if st.state == cs.NEW:
        st.create(PW)
    return st


def seed(st: cs.Store, path: str, data: bytes, mtime: int = 1_700_000_000_000) -> None:
    parts = cloud.parse_path(path)
    folder, ring = st.folder_for_put(parts[:-1], {}, "local") if len(parts) > 1 else ("", cs.MAIN)
    up = st.begin_upload(folder, ring, parts[-1], len(data), mtime, "local", os.urandom(16).hex())
    up.write(data)
    assert up.commit(unique=False) == parts[-1]


def read(st: cs.Store, path: str, header: dict | None = None, peer: str = "local") -> bytes:
    return b"".join(st.read(st.node_at(cloud.parse_path(path), header or {}, peer)))


def names(st: cs.Store, path: str = "", header: dict | None = None) -> list[str]:
    return [x["name"] for x in st.list_children(cloud.parse_path(path), header or {}, "local")]
