"""화면 문자열 번역. 언어마다 locales/<코드>.json 하나를 두고 t(key, **kw)로 꺼낸다.

- 언어: 한국어(ko), English(en), 日本語(ja), 简体中文(zh_CN). 설정이 "system"이면 Windows 표시 언어를 따르고,
  네 언어가 아니면 영어를 쓴다.
- 상대 기기에 보내는 오류 문장(응답의 "error")은 예전 버전이 그대로 보여 주므로 언제나 한국어(WIRE)로 만든다.
  대신 응답에 고정 코드("code")를 함께 실어 새 버전은 자기 언어로 바꿔 보여 준다(docs/PROTOCOL.md §3).
"""
from __future__ import annotations

import ctypes
import json
import sys
from pathlib import Path

LANGS = {"ko": "한국어", "en": "English", "ja": "日本語", "zh_CN": "简体中文"}
SYSTEM = "system"
FALLBACK = "en"
WIRE = "ko"
_FONTS = {"ko": "Malgun Gothic", "en": "Segoe UI", "ja": "Yu Gothic UI", "zh_CN": "Microsoft YaHei UI"}

_tables: dict[str, dict[str, str]] = {}
_current: str | None = None


def locales_dir() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)) / "locales"


def table(lang: str) -> dict[str, str]:
    if lang not in _tables:
        _tables[lang] = json.loads((locales_dir() / f"{lang}.json").read_text("utf-8"))
    return _tables[lang]


def from_langid(langid: int) -> str:
    """Windows LANGID → 지원 언어 코드. 중국어는 간체 지역(중국 본토·싱가포르)만 간체로, 나머지는 영어."""
    primary, sub = langid & 0x3FF, langid >> 10
    if primary == 0x12:
        return "ko"
    if primary == 0x11:
        return "ja"
    if primary == 0x04 and sub in (0x02, 0x04):
        return "zh_CN"
    return FALLBACK


def system_language() -> str:
    try:
        return from_langid(int(ctypes.windll.kernel32.GetUserDefaultUILanguage()))
    except (AttributeError, OSError):
        return FALLBACK


def resolve(choice: str | None) -> str:
    return choice if choice in LANGS else system_language()


def set_language(choice: str | None) -> str:
    """choice: "system" 또는 LANGS의 코드. 실제로 쓰게 된 언어 코드를 돌려준다."""
    global _current
    _current = resolve(choice)
    return _current


def current() -> str:
    return _current if _current is not None else set_language(SYSTEM)


def text(lang: str, key: str, **kw) -> str:
    value = table(lang).get(key)
    if value is None:
        value = table(FALLBACK).get(key, key)
    return value.format(**kw) if kw else value


def t(key: str, **kw) -> str:
    return text(current(), key, **kw)


def has(key: str) -> bool:
    return key in table(FALLBACK)


def ui_font() -> str:
    return _FONTS[current()]
