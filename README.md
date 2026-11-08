# Tina2 Print

An unofficial, open-source Android client for the **WEEDO Tina2S** (and compatible
WEEDO) 3D printer over its **local Wi-Fi API** — no cloud, no PC, no vendor app.

The printer's network module exposes an **OctoPrint-compatible REST API over plain
HTTP on port 80**, with no authentication. This app talks to that API directly so you
can slice and print entirely from your phone.

> The protocol was reverse-engineered statically from the vendor's *Poloprint Local*
> Android app (`com.weedo3d.pplocal`) — no proxy, no live capture. See
> [`docs/LAN-PROTOCOL.md`](docs/LAN-PROTOCOL.md).

## Features

**Print tab**
- Set / auto-discover the printer IP (scans the local `/24`).
- Show status: device, firmware, state, temperatures, current job.
- Upload a sliced `.gcode` file and optionally start printing.
- Job control: **Start**, **Pause**, **Cancel**.

**Slice tab** — offline slicing on the phone
- Load an **STL** straight from the phone's storage.
- Slice with the embedded [Kiri:Moto](#the-slicer) engine, fully **offline**.
- Adjust layer height, infill, wall shells, nozzle and bed temperature.
- Send the resulting G-code straight to the printer.

## The slicer

Slicing is done by **[Kiri:Moto](https://grid.space/kiri/)** by
[GridSpace](https://github.com/GridSpace/grid-apps) — a mature, browser-based,
**MIT-licensed** slicer. Its standalone JavaScript engine (`engine.js` + `worker.js`
and the WASM geometry modules) is **bundled inside the APK** under `assets/`, so
slicing works with no network connection at all.

The engine runs inside a `WebView`. Because it needs `SharedArrayBuffer`, the page is
served from a tiny loopback HTTP server (`LocalServer.java`) that adds the required
`Cross-Origin-Opener-Policy` / `Cross-Origin-Embedder-Policy` headers — serving over
`http://127.0.0.1` rather than `file://` also lets the page stream large STL files
efficiently.

The slicer is configured with a **Tina2S** device profile (100 × 100 × 100 mm bed,
0.4 mm nozzle, 1.75 mm filament) and a PLA process. Kiri:Moto also ships a built-in
`Tina2.Basic.json` device profile, which this profile is based on.

## Requirements

- Android 5.0+ (`minSdk 21`).
- The phone and printer on the **same LAN**.
- The printer powered on and connected to Wi-Fi.

## Build

Built without Gradle, using the Android SDK command-line tools directly
(`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`).

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
./build.sh          # -> Tina2Print.apk (signed with the debug keystore)
```

`build.sh` expects `build-tools/35.0.0` and `platforms/android-34`. The debug keystore
is created automatically at `~/.android/debug.keystore` if missing.

Install:

```bash
adb install -r Tina2Print.apk
```

## How printing works on this printer

There is **no "play" button** — uploading a file does not start it. The flow is:

1. `POST /api/files/local` — upload the `.gcode` (multipart). The firmware writes it
   to the SD card, renaming it to an **8.3** name (e.g. `slice.gcode` → `SLICE~1.GCO`).
2. `POST /api/files/sdcard/<name> {"command":"select","print":true}` — **this is what
   actually starts the print.**

The firmware needs a moment to flush the upload to the SD card, so the app retries the
`select` until the job is confirmed `Printing`. Note the upload must use a fixed
`Content-Length` — the ESP32 network module does not accept chunked transfer encoding.

## Project layout

```
AndroidManifest.xml            app manifest (package com.retallack.tina2print)
build.sh                       Gradle-free build script
src/com/retallack/tina2print/
    MainActivity.java          UI: Print + Slice tabs, upload, job control
    LocalServer.java           loopback HTTP server hosting the slicer page
assets/
    slice.html                 slicer UI (Kiri:Moto engine glue)
    sample-cube.stl            test model
    lib/kiri/run/              Kiri:Moto engine.js, worker.js, manifold.wasm
    wasm/                      Kiri:Moto kiri-geo.wasm, kiri-sla.wasm
docs/LAN-PROTOCOL.md           recovered Tina2S local Wi-Fi protocol
```

## Third-party components

- **Kiri:Moto** (`assets/lib`, `assets/wasm`, and the slicing logic in
  `assets/slice.html`) — © GridSpace, **MIT License**.
  <https://github.com/GridSpace/grid-apps>

## License

**MIT** — see [LICENSE](LICENSE). © 2026 Mark Retallack.

Not affiliated with or endorsed by WEEDO / Entina. "Tina2" and "WEEDO" are trademarks
of their respective owners. Use at your own risk.
