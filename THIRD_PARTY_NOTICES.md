# Third-party notices

TailHop itself is released under the MIT License (see `LICENSE`).
It uses or bundles the following third-party works. Each keeps its own license.

## Icons

| Work | Where | License |
|---|---|---|
| [Lucide](https://lucide.dev) icons 1.52.0 (`arrow-down`, `arrow-right`, `arrow-right-left`, `arrow-up`, `check`, `chevron-down`, `chevron-left`, `clipboard`, `ellipsis`, `file`, `folder`, `laptop`, `message-circle`, `panel-left`, `plus`, `qr-code`, `smartphone`, `triangle-alert`) | `windows/assets/icons/*.svg` (source), PC app at runtime, Android `res/drawable/ic_*.xml` (converted by `windows/assets/build_icons.py`), app/tray/launcher/notification icons | ISC, some icons derived from Feather (MIT). Full text: `windows/assets/icons/LICENSE-lucide.txt` |
| `dot.svg`, `tailhop-logo.svg` | `windows/assets/icons/` | Part of TailHop (MIT). The logo arranges the Lucide `arrow-right-left` shape. |

## Windows app (Python, bundled by PyInstaller)

| Package | License |
|---|---|
| Python 3 runtime, Tcl/Tk | PSF License; Tcl/Tk BSD-style license |
| cryptography | Apache-2.0 OR BSD-3-Clause |
| cffi / pycparser | MIT-0 / BSD-3-Clause |
| Pillow | MIT-CMU (HPND) |
| customtkinter | MIT (package metadata also lists CC0-1.0) |
| darkdetect | BSD-3-Clause |
| pystray | LGPL-3.0 (used unmodified as a library; source: https://github.com/moses-palmer/pystray) |
| qrcode | BSD |
| tkinterdnd2 (with the tkdnd library) | MIT (tkdnd: BSD-style) |
| six | MIT |
| Tink (Google, tink-py) | Apache-2.0 |
| protobuf | BSD-3-Clause |
| absl-py | Apache-2.0 |
| bazel-runfiles | Apache-2.0 |
| PyInstaller bootloader | GPL-2.0-or-later with the bootloader exception (allows distributing the built app under any license) |

### FFmpeg (LGPL-2.1-or-later)
The Windows installer ships FFmpeg (`ffmpeg.exe`, `ffprobe.exe` and its shared DLLs) in the app's `ffmpeg` folder. It is the unmodified LGPL shared build `ffmpeg-n8.1.3-18-g46d899d7a0-win64-lgpl-shared-8.1` from https://github.com/BtbN/FFmpeg-Builds (release `autobuild-2026-10-18-13-01`, SHA-256 of the zip `eb9346bdb3312d51ce5bfcf78041fd17e3db91f9fff9446d0d758406b85d2a51`). It contains no GPL components. FFmpeg's license text is in `ffmpeg/LICENSE.txt`; the source code for this exact version is at https://github.com/FFmpeg/FFmpeg (tag `n8.1.3`) and the build scripts at https://github.com/BtbN/FFmpeg-Builds. TailHop runs FFmpeg as separate programs, and the DLLs can be replaced by the user. FFmpeg is a trademark of Fabrice Bellard.

### Note on pystray (LGPL-3.0)
TailHop does not modify pystray. The Windows build is a PyInstaller one-folder app; pystray's own source is
available at the link above, and you can rebuild TailHop from this repository with a different pystray
version (`windows/requirements.txt`, `windows/build.bat`). This satisfies the LGPL's requirement that users can
replace the library.

### mpv (GPL-2.0-or-later)
The Windows installer ships mpv (`mpv.exe`, `d3dcompiler_43.dll`) in the app's `mpv` folder for in-app video playback. It is the unmodified build `mpv-x86_64-20261019-git-6a262d11f6` from https://github.com/shinchiro/mpv-winbuild-cmake (release `20261019`, SHA-256 of the archive `e23cd1622ad299322fdf37a5405e0ff541fe2031b01487b059580e3aa27c2ba9`). mpv is a separate program: TailHop starts it with a window handle and talks to it over a local named pipe; it does not link to it. The license text is in `mpv/LICENSE.GPL` and `mpv/Copyright`; the corresponding source is mpv commit `6a262d11f63733d37e3ee6d5fafcb1be1ef83952` (https://github.com/mpv-player/mpv) and the build scripts with every library version are at the release above (see `mpv/SOURCE.txt`). If those links ever stop working, ask through the TailHop repository and the source will be provided.

## Installer

| Tool | License |
|---|---|
| Inno Setup (installer runtime) | Inno Setup License (free for any use) |

## Android app

| Library | License |
|---|---|
| Kotlin standard library | Apache-2.0 |
| AndroidX (core, appcompat, activity, recyclerview) | Apache-2.0 |
| zxing-android-embedded (journeyapps) | Apache-2.0 |
| ZXing core | Apache-2.0 |
| AndroidX Media3 (exoplayer, ui, common, container, datasource, decoder, extractor, database) 1.11.1 | Apache-2.0 |
| AndroidX ExifInterface | Apache-2.0 |

Test-only dependencies (JUnit 4 EPL-1.0, org.json Public Domain) are not shipped in the app.
