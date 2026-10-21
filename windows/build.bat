@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" py -3 -m venv .venv
call ".venv\Scripts\python.exe" -m pip install --disable-pip-version-check -r requirements.txt
if errorlevel 1 goto :failed
call ".venv\Scripts\python.exe" tools\fetch_ffmpeg.py
if errorlevel 1 goto :failed
call ".venv\Scripts\python.exe" tools\fetch_mpv.py
if errorlevel 1 goto :failed
set PYTHONPATH=.
call ".venv\Scripts\python.exe" -m unittest discover -s tests
if errorlevel 1 goto :failed
call ".venv\Scripts\pyinstaller.exe" --noconfirm --clean --onedir --windowed --name TailHop ^
  --icon assets\tailhop.ico --version-file installer\version_info.txt ^
  --add-data "assets\tailhop.ico;assets" --add-data "assets\icons;assets\icons" --add-data "locales;locales" ^
  --add-data "..\LICENSE;." --add-data "..\THIRD_PARTY_NOTICES.md;." ^
  --add-data "vendor\ffmpeg\bin;ffmpeg" --add-data "vendor\ffmpeg\LICENSE.txt;ffmpeg" ^
  --add-data "vendor\mpv\mpv.exe;mpv" --add-data "vendor\mpv\d3dcompiler_43.dll;mpv" ^
  --add-data "vendor\mpv\LICENSE.GPL;mpv" --add-data "vendor\mpv\Copyright;mpv" ^
  --add-data "vendor\mpv\SOURCE.txt;mpv" ^
  --collect-all tkinterdnd2 --collect-all customtkinter --collect-all tink app.py
if errorlevel 1 goto :failed
set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
"%ISCC%" installer\TailHop.iss
if errorlevel 1 goto :failed
echo Done: ..\dist\TailHop_Setup_2.2.1.exe
exit /b 0
:failed
echo Build failed.
exit /b 1
