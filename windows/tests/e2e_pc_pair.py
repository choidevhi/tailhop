"""실제 Tailscale 위에서 PC 두 대를 흉내 낸다(수동 실행): 이 PC의 Tailscale IP에 Node 두 개를 다른 포트로 띄우고
짧은 코드로 페어링한 뒤 양방향으로 주고받는다. whois·bind·소켓은 실제 것을 쓴다.

한 PC라 두 노드의 Tailscale 기기 ID가 같으므로, '자기 자신과 연결 금지' 검사를 통과하도록
각 노드의 자기 ID만 가짜로 바꾼다(제품 코드는 그대로). 임시 폴더만 쓴다.
"""
import os
import sys
import tempfile
import time
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
tmp = Path(tempfile.mkdtemp())
import store  # noqa: E402

store.APP_DIR = tmp / "guard"
store.DEFAULT_SAVE_DIR = tmp / "guard-recv"
import node as nodemod  # noqa: E402
import tsnet  # noqa: E402


class SelfIdOverride:
    def __init__(self, fake_id: str) -> None:
        self.fake_id = fake_id

    def self_info(self):
        return replace(tsnet.self_info(), stable_id=self.fake_id)

    whois = staticmethod(tsnet.whois)
    is_tailscale_ip = staticmethod(tsnet.is_tailscale_ip)


def make(label: str):
    events = []
    n = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=tmp / label, ts=SelfIdOverride(f"e2e-{label}"), port=0)
    n.settings["save_dir"] = str(tmp / label / "recv")
    n.start()
    for _ in range(100):
        if n.me and n.listener:
            break
        time.sleep(0.1)
    assert n.listener, f"{label} not listening"
    return n, events


a, ev_a = make("A")
b, ev_b = make("B")
print("A", a.me.ip, a.port, "| B", b.me.ip, b.port)

a.begin_pairing()
code = a.pairing_code()
print("code length:", len(code))
res = b.join_pairing(code)
time.sleep(0.3)
host = next(d for k, d in ev_a if k == "paired")
assert res["code"] == host["code"], "confirm codes differ"
(peer_a,) = a.pairings.values()
(peer_b,) = b.pairings.values()
print("ok: paired", peer_a.kind, peer_a.role, "/", peer_b.kind, peer_b.role, "code match")

b.send_text(peer_b.stable_id, "B → A 실제 Tailscale")
src = tmp / "e2e.bin"
src.write_bytes(os.urandom(3 * 1024 * 1024 + 7))
a.send_file(peer_a.stable_id, src)
time.sleep(0.3)
print("ok: A got text" if any(k == "received" and d.get("text") == "B → A 실제 Tailscale" for k, d in ev_a) else "FAIL text")
print("ok: B got file intact" if (tmp / "B" / "recv" / "e2e.bin").read_bytes() == src.read_bytes() else "FAIL file")

try:
    b.join_pairing(code)
    print("FAIL: code reuse accepted")
except Exception as exc:  # noqa: BLE001
    print("ok: code reuse rejected:", type(exc).__name__)

a.stop()
b.stop()
print("rejected events A:", [d["error"] for k, d in ev_a if k == "rejected"])
