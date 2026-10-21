# TailHop

Personal AirDrop-like text/file transfer between Windows PCs and Android phones over Tailscale, with an extra end-to-end encryption layer. MIT licensed. (Documentation is in Korean; protocol spec: `docs/PROTOCOL.md`.)

Windows PC와 Android 폰, 또는 Windows PC끼리 텍스트·이미지·파일을 AirDrop처럼 주고받는 개인용 도구. Tailscale 망 위에서만 동작하고, Tailscale 암호화 위에 페어링 키로 한 번 더 암호화한다.

- `windows/` — PC 앱 (Python, customtkinter, 트레이 상주, 드래그 앤 드롭). 1.4.0부터 다른 PC와도 연결(연결 코드 붙여넣기)
- `android/` — Android 앱 (Kotlin, 공유 메뉴, 수신 서비스)
- 1.5.0부터 두 앱 모두 한국어·English·日本語·简体中文(기본은 시스템 언어, 설정에서 고르기)와 수신 중지(포트를 닫고 보내기만, 1시간 타이머)를 지원한다. 화면 문자열은 PC `windows/locales/*.json`, Android `res/values*/strings.xml`
- 1.6.0부터 PC 앱은 폴더를 zip으로 묶어 보내고, 탐색기 '보내기' 메뉴·명령줄·전역 단축키(Ctrl+Alt+Shift+C, 켤 때만)로 보낼 수 있다. 보내는 중에 더 보내면 차례로 보낸다. 선(wire) 형식은 그대로라 예전 앱·Android도 받는다
- 1.6.1부터 PC 대화방에 이미지·영상 미리보기(썸네일)를 보여 준다(메뉴에서 끌 수 있음). PC 안에서만 만들고 네트워크로 보내지 않는다. 이미지는 8000×8000 픽셀을 넘으면 만들지 않고, 영상은 Windows 탐색기와 같은 셸 썸네일을 쓴다. 캐시는 동기화되지 않는 `%LOCALAPPDATA%\TailHop\thumbs`에 두고 30일 안 쓰면 지운다
- 1.7.0부터 보내기는 보낼 곳 목록에 남아 상대가 꺼져 있어도 24시간 동안 저절로 다시 보낸다. 끊기면 받던 곳부터 이어 받고 끝에 SHA-256으로 확인한다(§11). 대화방에 아직 못 보낸 건이 상태와 함께 보이고, 다시 보내기·취소를 할 수 있다. Android는 받기 서비스를 하루 6시간 제한이 없는 종류로 바꿨다
- 1.8.0부터 클라우드 모드(§12): 켠 기기의 클라우드 폴더(PC 기본 `%USERPROFILE%\TailHop Cloud`, 폰 `Download/TailHop/Cloud`)를 연결된 모든 기기가 보고, 가져오고, 올리고, 지운다(PC에서 지운 것은 휴지통). 대화방으로 받은 파일도 클라우드에 모으는 설정이 있다. PC는 사이드바의 '클라우드', 폰은 홈의 '클라우드'
- 1.9.0부터 사이드바의 … 버튼이 설정 창을 연다(일반·받기·클라우드·기기·업데이트, 받은 파일 폴더와 클라우드 폴더 바꾸기). PC 창은 Windows 11 아크릴 흐림 위에 shadcn 카드가 떠 있다(Windows 10은 단색 바탕)
- 1.9.0부터 Tailscale 업데이트(§13): 빌드한 PC에서 `windows\tools\publish_update.py`를 돌리면 그 PC가 새 설치 파일·APK를 내주고, 다른 PC는 설정 → 업데이트, 폰은 홈의 새 버전 줄이나 메뉴의 '업데이트 확인'으로 받아 바로 설치한다. PC 설치 프로그램은 실행 중인 앱을 `--quit`으로 끄고 설치한 뒤 다시 켠다. 폰은 같은 서명의 APK만 설치한다
- 2.0.0부터 클라우드 미리보기·격자/목록·이름 찾기·종류 거르기(§15)와 PIN 잠긴 폴더(§14): PIN(또는 한 번만 보이는 복구 코드)으로만 열리고 그 기기에 묶인 30분 열쇠로 쓴다. 5번 틀리면 기기마다 따로 막는다
- 2.1.0부터 사진·영상을 누르면 라이트박스(핀치·휠 확대, 끌어 옮기기, 옆으로 넘기기, 받기·지우기·취소)로 보고, 여러 개를 골라(폰은 꾹 눌러 끌기) 한꺼번에 받기·옮기기(§16)·지우기를 한다
- 2.2.0부터 PC 클라우드는 통째로 암호화 저장소(§17): 이름·내용·미리보기가 모두 암호문이라 허브 PC의 탐색기·다른 Windows 계정·떼어 간 외장 드라이브로는 볼 수 없고 TailHop 앱에서만 열린다. 암호 계산은 Google Tink(AES-256-GCM, 파일마다 따로 열쇠, 1 MiB 청크), 비밀번호는 Argon2id, 이 PC 자동 열기는 Windows DPAPI. 위치는 아무 드라이브·폴더(외장 SSD도, 드라이브 문자가 바뀌어도 따라감). 새 PC에서는 클라우드 비밀번호(또는 복구 코드)를 한 번 넣고, 잊으면 리셋뿐이다. 사진은 화면용(긴 변 2048)을, 영상은 720p 재생용(FFmpeg, 그래픽카드 인코더)을 허브가 만들어 두고 받지 않고 바로 흘려 본다. 지운 것은 허브 휴지통에 30일. 2.1까지의 평문 폴더·예전 잠긴 폴더는 저장소를 만들 때 옮긴다
- 2.2.1부터 클라우드 창은 화면 캡처·녹화·화면 공유에 잡히지 않고(PC는 SetWindowDisplayAffinity, 폰은 FLAG_SECURE), 강한 잠금을 켜면 이 PC에서도 자동으로 열리지 않아 클라우드 비밀번호 없이는 아무도(이 PC의 다른 프로그램도) 안을 볼 수 없다(앱을 켤 때·Windows를 잠글 때·쓰지 않을 때 잠기고, 폰에서 비밀번호로 열 수 있음). 여러 개 한꺼번에 이름 바꾸기(찾아 바꾸기·앞뒤 붙이기·번호·대소문자, 미리보기), 창 안 끌어서 옮기기(잠긴 폴더 안팎도), 이 클라우드의 로컬 폴더 열기. 사진 보기는 화면 크기에 맞춰 열리고 확대·축소·맞춤·원래 크기·돌리기·정보(EXIF)·필름 띠·전체 화면·앞뒤 미리 불러오기·고품질 다시 그리기를, 영상은 앱 안 재생기(PC는 mpv, 폰은 Media3)로 탐색 막대·받아 둔 범위·±10초·속도·음량·전체 화면·이어 보기를 지원한다
- `docs/PROTOCOL.md` — 전송 프로토콜, `docs/test_vectors.json` — 두 구현이 공유하는 테스트 벡터

## 보안 (1.5.0 점검 반영)
- 폰은 QR을 스캔해도 바로 저장하지 않고, 확인 코드가 PC 화면과 같다고 고를 때만 연결한다. 다르면 버리고 PC에도 지우게 한다.
- 다른 앱에서 공유하면 받을 기기와 내용을 보여 주고 "보내기"를 눌러야 보낸다.
- PC는 태그가 붙은 Tailscale 기기와 연결하지 않고, 페어링되지 않은 주소의 연결은 아무것도 읽기 전에 닫는다.
- 받은 파일 이름에서 글자 순서를 바꾸는 문자와 Windows 장치 이름을 막고, 공간이 부족하면 받기 전에 거부한다.
- 자세한 규칙은 `docs/PROTOCOL.md`.

## 명령줄 (PC 1.6.0)
- `TailHop.exe [--to 기기] [--text 글] [--no-wait] [--timeout 초] [파일·폴더...]`: 실행 중인 앱에 넘겨 보낸다(앱이 꺼져 있으면 트레이로 켜고 보낸다). `--to`가 없으면 지금 대화방 기기, 있으면 기기 ID·이름·이름 앞부분(하나만 맞을 때)으로 찾는다
- (1.7.0) 기본으로 상대가 받았다고 답할 때까지 기다린다(기본 60초). 종료 코드 0 = 도착, 1 = 실패, 3 = 아직 못 보냄(앱이 계속 다시 시도한다). `--no-wait`은 넘기기만 하고 0으로 끝난다
- `TailHop.exe --list`: 연결된 기기 목록(`*`가 지금 대화방)
- 앱과는 `127.0.0.1`의 로컬 소켓으로만 말하고, 포트와 토큰은 이 사용자만 읽는 `%APPDATA%\TailHop\bridge.json`에 있다. 토큰이 틀리면 응답하지 않는다

## 테스트
- PC: `cd windows && set PYTHONPATH=. && .venv\Scripts\python -m unittest discover -s tests` (두 PC 시뮬레이션·번역 표·수신 중지·대용량 스트리밍 메모리 포함, 임시 폴더와 빈 포트만 씀)
- Android: `cd android && ./gradlew testDebugUnitTest lintDebug` (번역 누락은 lint 오류, 릴리즈 빌드도 막음. 페어링 확인·공유 authority·파일명 정리 회귀 테스트 포함)
- 실제 Tailscale 위 수동 확인: `tests\e2e_tailscale.py`(가짜 폰), `tests\e2e_pc_pair.py`(한 PC 안의 두 노드), `tests\e2e_bridge.py`(실제 앱을 임시 설정 폴더로 띄워 명령줄로 파일·폴더·텍스트 보내기)

## 빌드
- 업데이트로 내주기: 두 빌드를 마친 뒤 `cd windows && .venv\Scripts\python tools\publish_update.py` (`%APPDATA%\TailHop\updates`에 복사하고 manifest를 쓴다. 디버그 서명 APK는 거부)
- PC: `windows\build.bat` → `dist\TailHop_Setup_<버전>.exe` (Inno Setup 6 필요)
- FFmpeg: `build.bat`이 `windows\tools\fetch_ffmpeg.py`로 고정된 LGPL 빌드(BtbN, SHA-256 확인)를 `windows\vendor\ffmpeg`에 받아 설치 파일에 넣는다. 저장소에는 넣지 않는다
- Android: `cd android && ./gradlew assembleRelease` (서명 키는 저장소에 없음)
- 릴리즈(`.github/workflows/release.yml`): Android 서명 키 시크릿(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, 선택 `ANDROID_KEY_PASSWORD`)이 없으면 실패한다. 올리기 전에 APK가 debuggable이 아닌지, 서명 인증서 SHA-256이 워크플로의 기대값(또는 저장소 변수 `ANDROID_SIGNER_SHA256`)과 같은지 확인한다.
- 아이콘: 원본은 `windows/assets/icons/*.svg`(Lucide, ISC). 고친 뒤 `cd windows && .venv\Scripts\python assets\build_icons.py`로 PC `.ico`와 Android 벡터 아이콘을 다시 만든다(PC 화면 아이콘은 실행 중에 화면 배율에 맞춰 그린다). 테스트가 Android 아이콘이 원본과 같은지 확인한다.

## 포크에서 릴리즈 빌드하기
릴리즈 워크플로는 서명 키가 없거나 서명자가 다르면 멈춘다(디버그 서명 APK를 올리지 않는다). 포크에서는 자기 키를 쓴다.
1. 릴리즈 키를 만든다: `keytool -genkeypair -v -keystore release.jks -alias <별칭> -keyalg RSA -keysize 4096 -validity 10000`
2. 저장소 **Settings → Secrets and variables → Actions → Secrets**에 넣는다.
   - `ANDROID_KEYSTORE_BASE64`: `release.jks`를 base64로 바꾼 값(PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))`, Linux/macOS `base64 -w0 release.jks`)
   - `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`(키 비밀번호가 저장소 비밀번호와 같으면 생략)
3. 같은 화면의 **Variables**에 `ANDROID_SIGNER_SHA256`을 넣는다: 인증서 SHA-256 지문(콜론 있어도 됨). `keytool -list -v -keystore release.jks -alias <별칭>`의 `SHA256:` 줄, 또는 서명한 APK에 `apksigner verify --print-certs`. 이 변수가 없으면 원 저장소 키의 지문으로 검사하므로 포크의 APK는 일부러 실패한다.
4. `v1.2.3` 형식의 태그를 push하거나 Actions에서 Release를 수동 실행한다.
로컬 빌드는 `android/keystore/keystore.properties`(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)와 키 파일을 두면 `assembleRelease`가 서명한다. 이 폴더는 `.gitignore`에 있다. 비밀번호와 키 파일은 커밋하지 않는다.

## 라이선스
MIT (`LICENSE`). 아이콘(Lucide, ISC)과 함께 쓰는 라이브러리의 라이선스는 `THIRD_PARTY_NOTICES.md`.
