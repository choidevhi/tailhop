"""2.1까지의 평문 클라우드 폴더와 예전 잠긴 폴더(vault.py 형식)를 암호화 저장소(§17)로 옮긴다(2.2.0).

- 평문 파일: 하나씩 저장소에 암호화해 넣고(목록에 들어간 것을 확인한 뒤) 원본을 지운다(휴지통을 거치지 않음 —
  휴지통에 평문을 남기지 않으려고). 숨김·시스템 파일, 링크, .part, 저장소 자신의 파일은 건드리지 않는다.
  넣은 것은 저장소 안에 저장소 열쇠로 잠가 적어 두어(이름을 평문으로 남기지 않는다), 중간에 꺼져도 두 번 넣지 않는다.
  다 옮기면 빈 폴더만 지운다.
- 예전 잠긴 폴더: 그 PIN(또는 복구 코드)으로 열어 새 저장소의 같은 자리에 잠긴 폴더를 만들고 새 PIN으로 잠근 뒤
  그 안으로 바로 넣는다(평문을 디스크에 두지 않는다). 다 넣은 뒤 예전 보관함을 지운다. 새 복구 코드가 생긴다.
"""
from __future__ import annotations

import json
import os
import shutil
import stat
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import cloud
import cloudstore as cs
import protocol as p
import vault

STORE_NAMES = {cs.STORE_DIR, vault.VAULT_DIR}  # 옮기지 않는 맨 위 이름(새 저장소, 예전 보관함)
_HIDDEN_SYSTEM = 0x2 | 0x4


@dataclass
class Report:
    moved: int = 0
    bytes: int = 0
    failed: list[tuple[str, str]] = field(default_factory=list)


def _plain_files(root: Path) -> list[Path]:
    out: list[Path] = []
    for folder, dirs, names in os.walk(root, followlinks=False):
        here = Path(folder)
        keep = []
        for d in dirs:
            path = here / d
            if here == root and d in STORE_NAMES:
                continue
            try:
                attrs = os.lstat(path).st_file_attributes
            except OSError:
                continue
            if path.is_symlink() or path.is_junction() or attrs & _HIDDEN_SYSTEM:
                continue
            keep.append(d)
        dirs[:] = keep
        for name in names:
            path = here / name
            if here == root and name in STORE_NAMES:
                continue
            try:
                st = os.lstat(path)
            except OSError:
                continue
            if not stat.S_ISREG(st.st_mode) or st.st_file_attributes & _HIDDEN_SYSTEM \
                    or name.lower().endswith(".part"):
                continue
            out.append(path)
    return sorted(out)


def pending(root: Path) -> tuple[int, int, list[str]]:
    """(옮길 평문 파일 수, 크기 합, 예전 잠긴 폴더 자리들)."""
    root = Path(root)
    if not root.is_dir():
        return 0, 0, []
    files = _plain_files(root)
    size = 0
    for f in files:
        try:
            size += f.stat().st_size
        except OSError:
            pass
    return len(files), size, sorted(vault.VaultManager(root).mounts)


def _load_done(st: cs.Store) -> set[str]:
    raw = st.secret_get("migrated")
    try:
        data = json.loads(raw) if raw else []
    except ValueError:
        return set()
    return {x for x in data if isinstance(x, str)} if isinstance(data, list) else set()


def _save_done(st: cs.Store, done: set[str]) -> None:
    st.secret_put("migrated", json.dumps(sorted(done), ensure_ascii=False).encode("utf-8"))


def _key(path: Path, root: Path) -> str:
    s = path.stat()
    return f"{path.relative_to(root).as_posix()}|{s.st_size}|{s.st_mtime_ns}"


def move_plain(st: cs.Store, source: Path, progress: Callable[[int, int], None] | None = None) -> Report:
    """source 폴더의 평문 파일을 저장소의 같은 자리로 옮긴다."""
    source = Path(source)
    files = _plain_files(source)
    total = sum(f.stat().st_size for f in files) or 1
    done = _load_done(st)
    report = Report()
    for path in files:
        rel = path.relative_to(source).parts
        try:
            key = _key(path, source)
            if key not in done:  # 넣었는데 원본을 못 지우고 꺼졌다면 다시 넣지 않는다
                parts = [p.cloud_name(p.sanitize_filename(x)) for x in rel[:-1]]
                folder, ring = st.folder_for_put(parts, {}, "local")
                st.import_file(folder, ring, path)
                done.add(key)
                _save_done(st, done)
            size = path.stat().st_size
            try:
                os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
            except OSError:
                pass
            os.remove(path)
            report.moved += 1
            report.bytes += size
        except (OSError, p.ProtocolError) as exc:
            report.failed.append(("/".join(rel), str(exc)))
        if progress:
            progress(report.bytes, total)
    for folder, _dirs, _files in sorted(os.walk(source, followlinks=False), key=lambda x: -len(x[0])):
        here = Path(folder)
        if here == source or (here.parent == source and here.name in STORE_NAMES):
            continue
        try:
            os.rmdir(here)  # 빈 폴더만 지운다
        except OSError:
            pass
    return report


def import_stream(st: cs.Store, folder: str, ring: str, name: str, size: int, mtime: int, blocks) -> str:
    up = st.begin_upload(folder, ring, name, size, mtime, "local", os.urandom(16).hex())
    try:
        for block in blocks:
            up.write(block)
    except BaseException:
        up.abort()
        raise
    return up.commit(unique=True)


def convert_vault(st: cs.Store, source: Path, mount: str, new_pin: str, pin: str | None = None,
                  recovery: str | None = None) -> str:
    """예전 잠긴 폴더 하나를 새 저장소의 잠긴 폴더로 옮긴다. 새 복구 코드를 돌려준다."""
    if not cs.valid_pin(new_pin):
        raise p.ProtocolError("vault_pin")
    vm = vault.VaultManager(Path(source))
    found = vm.mount_of(mount)
    if found is None or found[1]:
        raise p.ProtocolError("not_found")
    m = found[0]
    token = vm.open(m.mount, "local", pin, recovery)
    old = vm.vault_for(m, token, "local")
    parts = cloud.parse_path(m.mount)
    with st._mu:
        parent, _ = st.folder_for_put(parts[:-1], {}, "local") if len(parts) > 1 else ("", cs.MAIN)
        name = st._free_name(parent, parts[-1])  # 같은 이름의 새 폴더가 이미 있으면 번호를 붙인다
    target = [*parts[:-1], name]
    st.mkdir(target, {}, "local")
    code = st.lock(target, new_pin)  # 비어 있을 때 잠그고 그 열쇠로 바로 넣는다(저장소 열쇠로 잠긴 내용을 거치지 않게)
    header = {"token": st.open_vault(target, "local", new_pin, None)}
    for rel, entry in sorted(old.entries.items()):
        inner = rel.split("/")
        if entry.is_dir:
            st.mkdir([*target, *inner], header, "local")
            continue
        folder, ring = st.folder_for_put([*target, *inner[:-1]], header, "local")
        import_stream(st, folder, ring, inner[-1], entry.size, entry.mtime, old.read(rel))
    vm.destroy(m)
    if not vm.mounts:  # 예전 보관함이 더 없으면 그 폴더(.tailhop-vault)도 지운다
        shutil.rmtree(Path(source) / vault.VAULT_DIR, ignore_errors=True)
    return code
