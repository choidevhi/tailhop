"""여러 개 이름 바꾸기 계획(2.2.1, docs/PROTOCOL.md §18.2). 화면 없이 이름만 계산한다(테스트하기 쉽게)."""
from __future__ import annotations

import cloud
import protocol as p

MODES = ("replace", "affix", "number", "case")
CASES = ("lower", "upper", "title")


def split_ext(name: str, is_dir: bool) -> tuple[str, str]:
    """('이름', '.확장자'). 폴더·점으로 시작하는 이름·확장자 없는 이름은 확장자가 없다."""
    stem, dot, ext = name.rpartition(".")
    if is_dir or not dot or not stem:
        return name, ""
    return stem, "." + ext


def plan(names: list[tuple[str, bool]], mode: str, a: str = "", b: str = "", start: int = 1,
         digits: int = 2, case: str = "lower") -> list[str]:
    """(이름, 폴더인지) 목록과 방식으로 새 이름들을 만든다. 확장자는 그대로 둔다.
    - replace: a를 b로(대소문자 그대로 찾기)
    - affix: a를 앞에, b를 뒤에(확장자 앞)
    - number: a가 패턴. {n}이 번호(start부터, digits 자리), {name}이 원래 이름. {n}이 없으면 뒤에 _번호
    - case: 대소문자(lower·upper·title)"""
    out = []
    for i, (name, is_dir) in enumerate(names):
        stem, ext = split_ext(name, is_dir)
        if mode == "replace":
            new = stem.replace(a, b) if a else stem
        elif mode == "affix":
            new = f"{a}{stem}{b}"
        elif mode == "number":
            n = str(start + i).zfill(max(1, min(9, digits)))
            pattern = a or "{name}"
            new = pattern.replace("{n}", n).replace("{name}", stem) if "{n}" in pattern \
                else f"{pattern.replace('{name}', stem)}_{n}"
        elif mode == "case":
            new = {"lower": stem.lower(), "upper": stem.upper(), "title": stem.title()}.get(case, stem)
        else:
            raise ValueError(mode)
        out.append(new + ext)
    return out


def problems(old: list[str], new: list[str]) -> list[str | None]:
    """새 이름마다 문제(오류 코드) 또는 None. 규칙(§12.1)에 맞지 않음, 이번 계획 안에서 서로 겹침."""
    seen: dict[str, int] = {}
    for name in new:
        seen[name.casefold()] = seen.get(name.casefold(), 0) + 1
    out: list[str | None] = []
    for before, name in zip(old, new):
        try:
            ok = cloud.parse_path(name) == [name]
        except p.ProtocolError:
            ok = False
        if not ok:
            out.append("bad_name")
        elif seen[name.casefold()] > 1:
            out.append("exists")
        else:
            out.append(None)
    return out
