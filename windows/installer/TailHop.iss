#define AppName "TailHop"
#define AppVersion "1.6.0"
#define AppExe "TailHop.exe"

[Setup]
AppId={{B3E2A1C4-7D5F-4C2A-9E61-7A0F3C9D2B18}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=choidev
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\dist
OutputBaseFilename=TailHop_Setup_{#AppVersion}
SetupIconFile=..\assets\tailhop.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

; 설치 마법사 언어: Windows 표시 언어를 따라 고르고(처음 화면에서 바꿀 수 있음), 맞는 것이 없으면 영어.
; 간체 중국어는 Inno Setup 기본 묶음에 없어 Inno Setup 저장소의 비공식 번역(ChineseSimplified.isl, is-6_7_1)을 함께 둔다.
[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"
Name: "japanese"; MessagesFile: "compiler:Languages\Japanese.isl"
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[CustomMessages]
english.AutoStart=Start automatically with Windows (in the tray)
korean.AutoStart=Windows 시작 시 자동 실행(트레이)
japanese.AutoStart=Windows の起動時に自動で実行(トレイ)
chinesesimplified.AutoStart=开机时自动启动（托盘）
english.SendTo=Add to Explorer's "Send to" menu
korean.SendTo=탐색기 '보내기' 메뉴에 추가
japanese.SendTo=エクスプローラーの「送る」メニューに追加
chinesesimplified.SendTo=添加到资源管理器的“发送到”菜单

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "autostart"; Description: "{cm:AutoStart}"; GroupDescription: "{cm:AdditionalIcons}"
Name: "sendto"; Description: "{cm:SendTo}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\dist\TailHop\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
; 탐색기 오른쪽 클릭 → 보내기 → TailHop (1.6.0). 고른 파일·폴더를 인자로 넘기면 실행 중인 앱이 지금 대화방 기기로 보낸다.
Name: "{usersendto}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: sendto

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"" --hidden"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
