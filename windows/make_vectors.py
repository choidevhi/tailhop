"""docs/test_vectors.json 생성: 고정 입력으로 키·프레임을 계산해 안드로이드 구현과 비교한다."""
import json
import re
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import protocol as p

S = bytes(range(32))
P = bytes(range(32, 64))
SALT = bytes(range(100, 116))
header = b'{"v":1,"type":"text","id":"00112233445566778899aabbccddeeff","ts":1700000000000,"from":"t","text":"hi \xed\x95\x9c"}'


def frames(dir_key, kind):
    prefix = p.MAGIC + bytes([kind]) + SALT + b"\x00"
    sk = p.session_key(dir_key, SALT)
    a = AESGCM(sk)
    return {
        "prefix_hex": prefix.hex(),
        "session_key_hex": sk.hex(),
        "frame0_plain_hex": header.hex(),
        "frame0_sealed_hex": a.encrypt(p.nonce(0, 0, False), header, prefix).hex(),
        "frame1_sealed_hex": a.encrypt(p.nonce(0, 1, True), b"", prefix).hex(),
        "reply_plain_hex": b'{"ok":true}'.hex(),
        "reply_sealed_hex": a.encrypt(p.nonce(1, 0, True), b'{"ok":true}', prefix).hex(),
    }


k_ph = p.derive_key(S, p.INFO_PHONE_TO_PC)
k_pc = p.derive_key(S, p.INFO_PC_TO_PHONE)
k_pair = p.derive_key(P, p.INFO_PAIR)
k_h2j = p.derive_key(S, p.INFO_PC_HOST_TO_JOINER)
k_j2h = p.derive_key(S, p.INFO_PC_JOINER_TO_HOST)
out = {
    "S_hex": S.hex(), "P_hex": P.hex(), "salt_hex": SALT.hex(),
    "S_b64url": p.b64u_encode(S),
    "confirm_code": p.confirm_code(S),
    "k_phone_to_pc_hex": k_ph.hex(), "k_pc_to_phone_hex": k_pc.hex(), "k_pair_hex": k_pair.hex(),
    "nonce_dir1_index258_last_hex": p.nonce(1, 258, True).hex(),
    "msg_phone_to_pc": frames(k_ph, p.KIND_MSG),
    "pair": frames(k_pair, p.KIND_PAIR),
    # PC ↔ PC (v1.4 추가): 코드를 보여 준 쪽 host, 입력한 쪽 joiner
    "k_pc_host_to_joiner_hex": k_h2j.hex(), "k_pc_joiner_to_host_hex": k_j2h.hex(),
    "msg_pc_joiner_to_host": frames(k_j2h, p.KIND_MSG),
    "msg_pc_host_to_joiner": frames(k_h2j, p.KIND_MSG),
    "pair_code": {"host": "100.101.102.103", "port": 47100, "code": p.pair_code("100.101.102.103", 47100, P),
                  "uri": p.pair_uri("100.101.102.103", 47100, "My PC", P)},
    "sanitize": {n: p.sanitize_filename(n) for n in ["../../evil.exe", "a<b>c.txt", "CON.txt", "...hidden ", "", "사진 1.jpg", "x" * 200 + ".png",
                                                    "evil\u202egnp.exe", "a\u0085b\u200f.txt", "COM1 .txt", "LPT\u00b9.log", "conin$", "report.txt. . "]},
}
# 보이지 않는 문자(bidi 제어 등)는 파일에서 보이도록 \uXXXX로 적는다(읽는 값은 같다).
text = re.sub(p._NAME_BAD.pattern, lambda m: m.group() if m.group() == "\n" else "\\u%04x" % ord(m.group()), json.dumps(out, ensure_ascii=False, indent=2))
Path(__file__).resolve().parents[1].joinpath("docs/test_vectors.json").write_text(text, "utf-8")
print(out["confirm_code"], out["sanitize"])
