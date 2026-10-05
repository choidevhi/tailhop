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
| PyInstaller bootloader | GPL-2.0-or-later with the bootloader exception (allows distributing the built app under any license) |

### Note on pystray (LGPL-3.0)
TailHop does not modify pystray. The Windows build is a PyInstaller one-folder app; pystray's own source is
available at the link above, and you can rebuild TailHop from this repository with a different pystray
version (`windows/requirements.txt`, `windows/build.bat`). This satisfies the LGPL's requirement that users can
replace the library.

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

Test-only dependencies (JUnit 4 EPL-1.0, org.json Public Domain) are not shipped in the app.
