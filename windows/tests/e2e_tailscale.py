"""실제 Tailscale IP 위에서 가짜 폰으로 페어링·양방향 전송을 확인한다(수동 실행)."""
import json
import os
import socket
import sys
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
tmp = Path(tempfile.mkdtemp())
import store  # noqa: E402

store.APP_DIR = tmp / "appdata"
store.DEFAULT_SAVE_DIR = tmp / "recv"
import node as nodemod  # noqa: E402
import protocol as p  # noqa: E402

events = []
# port=0: 설치된 TailHop(47100)과 겹치지 않게 빈 포트를 쓴다.
n = nodemod.Node(lambda k, d: events.append((k, d)), port=0)
n.start()
for _ in range(50):
    if n.me and n.listener:
        break
    time.sleep(0.2)
ip = n.me.ip
assert n.listener, "not listening"
print("listening", ip, n.port)

# 1) 페어링 창 없이 pair 시도 → 거부
def raw_pair(key, secret):
    s = socket.create_connection((ip, n.port), source_address=(ip, 0))
    ch = p.open_client(s, p.KIND_PAIR, key)
    ch.send(json.dumps(p.new_header("pair", "FakePhone", secret=p.b64u_encode(secret), port=47101)).encode(), last=False)
    ch.send(b"", last=True)
    try:
        return p.read_reply(ch)
    finally:
        s.close()

try:
    raw_pair(p.derive_key(os.urandom(32), p.INFO_PAIR), os.urandom(32))
    print("FAIL: pairing without window accepted")
except Exception as e:
    print("ok: pairing without window rejected:", type(e).__name__)

# 2) 정상 페어링
q = parse_qs(urlparse(n.begin_pairing()).query)
P = p.b64u_decode(q["k"][0])
S = os.urandom(32)
print("pair reply:", raw_pair(p.derive_key(P, p.INFO_PAIR), S))
pairing = next(iter(n.pairings.values()))
assert pairing.secret == S
assert store.load_pairings()[0].secret == S, "DPAPI roundtrip"
assert b"secret_dpapi" in (store.APP_DIR / "pairings.json").read_bytes() and S.hex() not in (store.APP_DIR / "pairings.json").read_text()

# 두 번째 기기 페어링 (같은 IP라 같은 기기로 교체되는지 확인)
q2 = parse_qs(urlparse(n.begin_pairing()).query)
S2 = os.urandom(32)
raw_pair(p.derive_key(p.b64u_decode(q2["k"][0]), p.INFO_PAIR), S2)
assert len(n.pairings) == 1 and next(iter(n.pairings.values())).secret == S2, "same device replaced"
S = S2
print("ok: re-pair replaces same device")

# 3) QR 재사용 → 거부
try:
    raw_pair(p.derive_key(P, p.INFO_PAIR), os.urandom(32))
    print("FAIL: QR reuse accepted")
except Exception as e:
    print("ok: QR reuse rejected:", type(e).__name__)

# 4) 폰 → PC 텍스트·파일
def phone_send(fn):
    s = socket.create_connection((ip, n.port), source_address=(ip, 0))
    s.settimeout(30)
    ch = p.open_client(s, p.KIND_MSG, p.derive_key(S, p.INFO_PHONE_TO_PC))
    fn(ch)
    r = p.read_reply(ch)
    s.close()
    return r

phone_send(lambda ch: p.send_text(ch, "FakePhone", "폰에서 보낸 글"))
src = tmp / "사진.jpg"
src.write_bytes(os.urandom(3 * 1024 * 1024 + 5))
phone_send(lambda ch: p.send_file(ch, "FakePhone", src))
time.sleep(0.3)
got = (tmp / "recv" / "사진.jpg").read_bytes()
print("ok: file intact" if got == src.read_bytes() else "FAIL: file differs")

# 5) 잘못된 키 → 거부
try:
    s = socket.create_connection((ip, n.port), source_address=(ip, 0))
    ch = p.open_client(s, p.KIND_MSG, p.derive_key(os.urandom(32), p.INFO_PHONE_TO_PC))
    p.send_text(ch, "x", "evil")
    p.read_reply(ch)
    print("FAIL: wrong key accepted")
except Exception as e:
    print("ok: wrong key rejected:", type(e).__name__)

# 6) PC → 폰 (가짜 폰 서버)
srv = socket.socket()
srv.bind((ip, 47101))
srv.listen(1)
box = {}
def phone_server():
    c, _ = srv.accept()
    kind, prefix = p.read_prefix(c)
    ch = p.open_server(c, prefix, p.derive_key(S, p.INFO_PC_TO_PHONE))
    box["r"] = p.receive_message(ch, p.ReplayGuard(tmp / "pseen.json"), tmp / "phone")
    p.send_reply(ch, True)
    c.close()
t = threading.Thread(target=phone_server); t.start()
n.send_text(next(iter(n.pairings)), "PC에서 보낸 글")
t.join(5)
print("phone got:", box["r"].header["text"])
print("events:", [k for k, _ in events])
print("history:", [(i["dir"], i["kind"], bool(i.get("peer_id"))) for i in n.history.items])
