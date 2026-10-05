"""Tailscale CLI 래퍼: 내 IP, 내 사용자, 접속한 상대 기기 확인(whois)."""
from __future__ import annotations

import ipaddress
import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from i18n import t

CGNAT = ipaddress.ip_network("100.64.0.0/10")
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _exe() -> str:
    """정식 설치 경로(관리자만 쓸 수 있는 Program Files)를 먼저 쓰고, 없을 때만 PATH에서 찾는다."""
    for base in (os.environ.get("ProgramW6432"), os.environ.get("ProgramFiles"), r"C:\Program Files"):
        if base:
            default = Path(base) / "Tailscale" / "tailscale.exe"
            if default.is_file():
                return str(default)
    found = shutil.which("tailscale")
    if found:
        return found
    raise RuntimeError(t("err.tailscale_missing"))


def _run(*args: str) -> str:
    done = subprocess.run([_exe(), *args], capture_output=True, text=True, encoding="utf-8", timeout=10, creationflags=_NO_WINDOW)
    if done.returncode != 0:
        raise RuntimeError((done.stderr or done.stdout).strip() or t("err.tailscale_cmd"))
    return done.stdout


def is_tailscale_ip(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return addr.version == 4 and addr in CGNAT


@dataclass(frozen=True)
class SelfInfo:
    ip: str
    user_id: int
    name: str
    stable_id: str = ""


def self_info() -> SelfInfo:
    status = json.loads(_run("status", "--json"))
    if status.get("BackendState") != "Running":
        raise RuntimeError(t("err.tailscale_down"))
    me = status["Self"]
    ip = next((a for a in me.get("TailscaleIPs", []) if is_tailscale_ip(a)), None)
    if ip is None:
        raise RuntimeError(t("err.no_tailscale_ipv4"))
    return SelfInfo(ip=ip, user_id=int(me["UserID"]), name=me.get("HostName") or "PC", stable_id=str(me.get("ID") or ""))


@dataclass(frozen=True)
class Peer:
    """tags: 태그 노드(서버·공유 기기 등)면 ACL 태그 목록. 태그 노드는 사람 계정이 아니라 공용 'tagged-devices'
    사용자로 묶이므로 UserID 비교만으로는 내 기기인지 알 수 없다. TailHop은 태그 노드를 거부한다."""

    stable_id: str
    user_id: int
    name: str
    tags: tuple[str, ...] = ()


def whois(ip: str) -> Peer:
    if not is_tailscale_ip(ip):
        raise RuntimeError(t("err.not_tailscale_addr"))
    data = json.loads(_run("whois", "--json", ip))
    node = data["Node"]
    tags = tuple(str(x) for x in (node.get("Tags") or []))
    return Peer(stable_id=str(node["StableID"]), user_id=int(node.get("User") or 0),
                name=node.get("ComputedName") or node["Name"], tags=tags)
