"""실제 앱(app.py)을 띄워 명령줄 → 실행 중인 앱 → 상대 PC까지 확인한다(수동 실행, 1.6.0).

- 앱은 임시 APPDATA로 띄운다(실제 %APPDATA%\\TailHop, 실행 중인 설치본, 탐색기 '보내기' 폴더는 건드리지 않는다).
  설정 폴더가 다르면 중복 실행 방지 이름도 달라서 설치본과 함께 뜬다.
- 받는 쪽은 이 PC의 Tailscale IP에 다른 포트로 띄운 Node(가짜 PC "FakeB")다. whois·소켓은 실제 것을 쓴다.
- 확인: --list, 파일 + 폴더(zip) + 텍스트를 한 번에 보내기, 없는 기기·없는 파일 거부, 탐색기 '보내기' 바로 가기.
- 빌드한 exe로 확인하려면 TAILHOP_EXE=dist\TailHop\TailHop.exe 를 준다. 앱은 트레이로만(--hidden) 띄운다.
"""
import os
import subprocess
import sys
import tempfile
import time
import zipfile
from dataclasses import replace
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
tmp = Path(tempfile.mkdtemp(prefix="tailhop_e2e_"))
import store  # noqa: E402

store.APP_DIR = tmp / "guard"
store.DEFAULT_SAVE_DIR = tmp / "guard-recv"
import bridge  # noqa: E402
import node as nodemod  # noqa: E402
import tsnet  # noqa: E402


class SelfIdOverride:
    def __init__(self, fake_id: str, name: str) -> None:
        self.fake_id, self.name = fake_id, name

    def self_info(self):
        return replace(tsnet.self_info(), stable_id=self.fake_id, name=self.name)

    whois = staticmethod(tsnet.whois)
    is_tailscale_ip = staticmethod(tsnet.is_tailscale_ip)


def wait_for(cond, timeout=20.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return True
        time.sleep(0.1)
    return False


APP_CMD = [os.environ["TAILHOP_EXE"]] if os.environ.get("TAILHOP_EXE") else [sys.executable, str(HERE / "app.py")]


def run_cli(env, *args):
    done = subprocess.run([*APP_CMD, *args], env=env, capture_output=True,
                          text=True, errors="replace", timeout=30)  # 콘솔·파이프는 시스템 코드 페이지
    print(f"$ TailHop {' '.join(args)}  -> exit {done.returncode}\n{done.stdout.strip()}")
    return done


me = tsnet.self_info()
secret = os.urandom(32)
events = []
b = nodemod.Node(lambda k, d: events.append((k, d)), app_dir=tmp / "B" / "app",
                 ts=SelfIdOverride("fakeB", "FakeB"), port=0)
b.settings["save_dir"] = str(tmp / "B" / "recv")
b.pairings[me.stable_id] = store.Pairing(ip=me.ip, port=47100, name="DevApp", stable_id=me.stable_id,
                                         secret=secret, kind="pc", role="joiner")
b.start()
assert wait_for(lambda: b.listener is not None), "B listen"

appdata = tmp / "appdata"
store.save_pairings([store.Pairing(ip=me.ip, port=b.port, name="FakeB", stable_id="fakeB", secret=secret,
                                   kind="pc", role="host")], appdata / "TailHop")
env = {**os.environ, "APPDATA": str(appdata), "PYTHONPATH": str(HERE)}
app = subprocess.Popen([*APP_CMD, "--hidden"], env=env)
try:
    assert wait_for(lambda: (appdata / "TailHop" / bridge.BRIDGE_FILE).exists()), "app bridge"

    listing = run_cli(env, "--list")
    assert listing.returncode == 0 and "FakeB" in listing.stdout and "* FakeB" in listing.stdout

    src = tmp / "src"
    (src / "폴더" / "안쪽").mkdir(parents=True)
    (src / "폴더" / "안쪽" / "a.txt").write_text("깊은 파일", "utf-8")
    big = os.urandom(3 * 1024 * 1024 + 7)
    (src / "사진.bin").write_bytes(big)
    sent = run_cli(env, "--to", "fake", "--text", "명령줄에서 안녕", str(src / "사진.bin"), str(src / "폴더"))
    assert sent.returncode == 0, sent.stdout
    assert wait_for(lambda: sum(1 for k, _ in events if k == "received") == 3, 60), events

    recv = tmp / "B" / "recv"
    assert (recv / "사진.bin").read_bytes() == big
    with zipfile.ZipFile(recv / "폴더.zip") as zf:
        assert zf.read("안쪽/a.txt").decode("utf-8") == "깊은 파일"
    texts = [d["text"] for k, d in events if k == "received" and d["kind"] == "text"]
    assert texts == ["명령줄에서 안녕"], texts
    leftovers = [x for x in Path(tempfile.gettempdir()).glob("tailhop_*") if x.is_dir() and x != tmp
                 and (x / "폴더.zip").exists()]
    assert not leftovers, leftovers

    assert run_cli(env, "--to", "nobody", "--text", "x").returncode == 1
    assert run_cli(env, str(src / "없는 파일.txt")).returncode == 1
    assert run_cli(env, "--bogus").returncode == 2

    # 탐색기 '보내기' 바로 가기(임시 APPDATA의 SendTo 폴더에 만든다)
    os.environ["APPDATA"] = str(appdata)
    bridge.set_sendto(True, sys.executable, f'"{HERE / "app.py"}"')
    assert bridge.sendto_enabled() and str(appdata) in str(bridge.sendto_path())
    target = subprocess.run(["powershell", "-NoProfile", "-Command",
                             "(New-Object -ComObject WScript.Shell).CreateShortcut($env:L).TargetPath"],
                            env={**os.environ, "L": str(bridge.sendto_path())}, capture_output=True, text=True)
    assert target.stdout.strip().lower() == sys.executable.lower(), target.stdout
    bridge.set_sendto(False, "")
    assert not bridge.sendto_enabled()
    print("E2E OK:", tmp)
finally:
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(app.pid)], capture_output=True)
    b.stop()
