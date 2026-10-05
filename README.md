# TailHop

Personal AirDrop-like text/file transfer between Windows PCs and Android phones over Tailscale, with an extra end-to-end encryption layer. MIT licensed. (Documentation is in Korean; protocol spec: `docs/PROTOCOL.md`.)

Windows PC와 Android 폰, 또는 Windows PC끼리 텍스트·이미지·파일을 AirDrop처럼 주고받는 개인용 도구. Tailscale 망 위에서만 동작하고, Tailscale 암호화 위에 페어링 키로 한 번 더 암호화한다.

- `windows/` — PC 앱 (Python, customtkinter, 트레이 상주, 드래그 앤 드롭). 1.4.0부터 다른 PC와도 연결(연결 코드 붙여넣기)
- `android/` — Android 앱 (Kotlin, 공유 메뉴, 수신 서비스)
- 1.5.0부터 두 앱 모두 한국어·English·日本語·简体中文(기본은 시스템 언어, 설정에서 고르기)와 수신 중지(포트를 닫고 보내기만, 1시간 타이머)를 지원한다. 화면 문자열은 PC `windows/locales/*.json`, Android `res/values*/strings.xml`
- 1.6.0부터 PC 앱은 폴더를 zip으로 묶어 보내고, 탐색기 '보내기' 메뉴·명령줄·전역 단축키(Ctrl+Alt+Shift+C, 켤 때만)로 보낼 수 있다. 보내는 중에 더 보내면 차례로 보낸다. 선(wire) 형식은 그대로라 예전 앱·Android도 받는다
- `docs/PROTOCOL.md` — 전송 프로토콜, `docs/test_vectors.json` — 두 구현이 공유하는 테스트 벡터

## 보안 (1.5.0 점검 반영)
- 폰은 QR을 스캔해도 바로 저장하지 않고, 확인 코드가 PC 화면과 같다고 고를 때만 연결한다. 다르면 버리고 PC에도 지우게 한다.
- 다른 앱에서 공유하면 받을 기기와 내용을 보여 주고 "보내기"를 눌러야 보낸다.
- PC는 태그가 붙은 Tailscale 기기와 연결하지 않고, 페어링되지 않은 주소의 연결은 아무것도 읽기 전에 닫는다.
- 받은 파일 이름에서 글자 순서를 바꾸는 문자와 Windows 장치 이름을 막고, 공간이 부족하면 받기 전에 거부한다.
- 자세한 규칙은 `docs/PROTOCOL.md`.

## 명령줄 (PC 1.6.0)
- `TailHop.exe [--to 기기] [--text 글] [파일·폴더...]`: 실행 중인 앱에 넘겨 보낸다(앱이 꺼져 있으면 트레이로 켜고 보낸다). `--to`가 없으면 지금 대화방 기기, 있으면 기기 ID·이름·이름 앞부분(하나만 맞을 때)으로 찾는다
- `TailHop.exe --list`: 연결된 기기 목록(`*`가 지금 대화방)
- 앱과는 `127.0.0.1`의 로컬 소켓으로만 말하고, 포트와 토큰은 이 사용자만 읽는 `%APPDATA%\TailHop\bridge.json`에 있다. 토큰이 틀리면 응답하지 않는다

## 테스트
- PC: `cd windows && set PYTHONPATH=. && .venv\Scripts\python -m unittest discover -s tests` (두 PC 시뮬레이션·번역 표·수신 중지·대용량 스트리밍 메모리 포함, 임시 폴더와 빈 포트만 씀)
- Android: `cd android && ./gradlew testDebugUnitTest lintDebug` (번역 누락은 lint 오류, 릴리즈 빌드도 막음. 페어링 확인·공유 authority·파일명 정리 회귀 테스트 포함)
- 실제 Tailscale 위 수동 확인: `tests\e2e_tailscale.py`(가짜 폰), `tests\e2e_pc_pair.py`(한 PC 안의 두 노드), `tests\e2e_bridge.py`(실제 앱을 임시 설정 폴더로 띄워 명령줄로 파일·폴더·텍스트 보내기)

## 빌드
- PC: `windows\build.bat` → `dist\TailHop_Setup_<버전>.exe` (Inno Setup 6 필요)
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
