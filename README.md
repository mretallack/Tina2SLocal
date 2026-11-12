# Tina2 Print

An unofficial, open-source Android client for the **WEEDO Tina2S** (and compatible
WEEDO) 3D printer over its **local Wi-Fi API** — no cloud, no PC, no vendor app.

The printer's network module exposes an **OctoPrint-compatible REST API over plain
HTTP on port 80**, with no authentication. This app talks to that API directly so you
can slice and print entirely from your phone.

> The protocol was reverse-engineered statically from the vendor's *Poloprint Local*
> Android app (`com.weedo3d.pplocal`) — no proxy, no live capture. See
> [`docs/LAN-PROTOCOL.md`](docs/LAN-PROTOCOL.md).

The app has three tabs — **Print**, **Slice** and **Status**.

**Print tab**
- Set / auto-discover the printer IP (scans the local `/24`).
- Upload a sliced `.gcode` file and optionally start printing.
- Job control: **Start**, **Pause**, **Cancel**.

**Slice tab** — offline slicing on the phone
- Load an **STL** straight from the phone's storage.
- Slice with the embedded [Kiri:Moto](#the-slicer) engine, fully **offline**.
- Adjust layer height, infill, wall shells, nozzle and bed temperature.
- **Raft tuning** panel (collapsed by default) exposes every raft layer the
  engine emits — height, line spacing, flow multiplier and speed for each of the
  four layers (base, middle, top 1, top 2), plus the air gap between the raft and
  the part's first layer. The defaults are exactly the values Kiri:Moto used to
  hardcode, so changing nothing slices as before.
- **Supports** panel — on/off, overhang angle, density, XY clearance, gap under
  the part and support outline. On by default; see [Supports](#supports).
- Send the resulting G-code straight to the printer.

**Status tab** — live printer readout
- Polls `/api/version`, `/api/printer` and `/api/job` every 2 s while visible.
- Shows device/firmware, printer state, nozzle and bed temperature, the current
  file, and job progress / elapsed / time left.
- Polling stops automatically when the tab is not on screen.

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

### Print start and end G-code

The device profile carries the start (`gcodePre`) and end (`gcodePost`) blocks, so
the printer behaves the same as with Cura's Tina2 start/end scripts:

- **Start** — preheat the bed and raise the nozzle to **150 °C** (not full print
  temperature) before homing, so the nozzle doesn't ooze over the bed; home Z and XY,
  move to the centre and **auto-level (`G29`) on a hot bed** (wait for `M190` first);
  then wait for the nozzle to reach print temperature (`M109`), heat-soak for **10 s**,
  and lay a prime line. Keeping the nozzle at 150 °C until after levelling matches
  Cura's proven sequence on this printer.
- **End** — the verbatim Cura / upstream-Kiri:Moto Tina2 park: raise the Z feedrate cap
  (`M203 Z15`), turn the heaters and fan off, home Z and XY, then run the bed forward
  (`G1 Y90`) so the finished part presents at the front. Motors are left energised so
  the park position is held.

Appended to every export is the end-of-file terminator (`M82` + `;End of Gcode`). The
Tina2S firmware only executes the trailing end block when the file ends with a newline
and that marker, so without it the head does not park.

### Building the slicer from source

Every Kiri:Moto asset shipped in the APK is **compiled from source** — nothing is
copied from a prebuilt download. The upstream source is vendored as a git submodule
at `thirdparty/grid-apps` (pinned to a specific commit) and `tools/build-slicer.sh`
rebuilds the runtime assets:

```bash
git submodule update --init --recursive   # fetch the vendored slicer source
./tools/build-slicer.sh                    # rebuild assets/lib/kiri + assets/wasm
```

The script:

1. `npm install` the slicer's dependencies, then pins `manifold-3d@3.3.2` (the
   version the engine was built against) so `manifold.wasm` is reproducible.
2. Generates the derived JS bundles (`three`, `jszip`, `quickjs`, `planegcs`).
3. Bundles `engine.js` and `worker.js` with esbuild (`bin/esbuild.config.mjs prod`).
4. Compiles the WASM geometry modules with Emscripten (`emcc` for `kiri-sla.c`,
   `em++` for the C++ `kiri-geo.cpp`).

Requirements: `node`/`npm` and the [Emscripten SDK](https://emscripten.org)
(`emcc`/`em++`) on `PATH`.

### Supports

Support generation is **on by default** (`sliceSupportType: "automatic"`), because
without it the printer simply cannot make an overhang. Kiri:Moto builds supports as
the projected *shadow* of every face steeper than the overhang angle, truncated where
it would touch the part, and prints them as a separate object per layer — so they
never mix with the part's paths and always print with no retractions between lines.

The panel exposes the five settings that matter in practice:

| Setting | Default | Effect |
|---|---|---|
| Generate supports | on | `sliceSupportType` — `automatic` or `disabled` |
| Overhang angle | 50° | only overhangs steeper than this get support |
| Density | 12 % | line spacing = nozzle ÷ density (12 % ≈ 3.3 mm); lower snaps off easier, higher holds more |
| XY clearance | 0.8 mm | gap between support and part wall; raise if supports weld on |
| Gap under part | on | also clip support against the layer above/below |
| Support outline | on | adds a perimeter wall to each support column |

### Local patches to the vendored slicer

`thirdparty/grid-apps` is an upstream submodule, so our changes are kept as patches in
`tools/patches/` and applied by `tools/build-slicer.sh` (a fresh `git submodule update`
would otherwise silently drop them). There are two:

- `0001-raft-layer-settings.patch` — in `src/kiri/mode/fdm/work/prepare.js`: the four
  hardcoded raft layers now read their height, line spacing, speed and flow from
  `outputRaft*` process settings, each falling back to the original hardcoded value
  when unset. That is what the **Raft tuning** panel in the Slice tab drives. Because
  every fallback equals the old constant, an untouched configuration produces
  byte-identical G-code (verified by slicing the same model with the old and new
  `worker.js` and diffing the output).

- `0002-cap-minion-pool.patch` — in `src/kiri/run/worker.js`: the minion pool size is
  capped at 2 workers instead of scaling with `hardwareConcurrency` (4-8 on a phone,
  each carrying its own multi-MB heap).

Any further slicer changes should follow the same shape — a small hunk in the
submodule, exported as a patch in `tools/patches/` and applied automatically by
`tools/build-slicer.sh` — so the diff stays reviewable and upstream-friendly, and a
fresh `git submodule update` can't silently lose it.

### Why the minion pool must run

Kiri:Moto's per-layer boolean diffs (the "layer deltas" stage) run inline for small
meshes but are **queued to a minion worker** for anything larger, and a queued job with
no minion to pick it up never resolves. With the pool stopped, large models hung at
~70 % forever — that was the original "stuck at layer deltas" bug, not bad geometry.
Support generation is queued to minions unconditionally. The app therefore always
starts the pool (`setController({ threaded: true })`) and ships the minion bundle as
`assets/lib/kiri/run/minion.js`. The 60-second stall watchdog and the **Reset slicer**
button remain as a backstop.

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

## Updates

The app updates itself — no Play Store or F-Droid. On startup it queries the
**GitHub Releases** API for `mretallack/Tina2SLocal`, and if the latest release tag is
newer than the installed build it offers to download the release's `.apk` and hand it
to Android's `PackageInstaller` (you can defer with **Later** or silence a version with
**Skip this version**).

It works because the app is updating *its own* package, which Android permits without
root, given two things:

- the user grants **"install unknown apps"** for Tina2 Print once (the app opens that
  Settings page for you when needed); and
- **every build is signed with the same release keystore** — an update signed with a
  different key is rejected by Android.

So self-update only works for **release-signed** builds. The default `./build.sh`
output is debug-signed; to get updates, build and publish releases with a real
keystore (see the CI secrets below) and keep using that same key forever.

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
    MainActivity.java          UI: Print + Slice + Status tabs, upload, job control
    LocalServer.java           loopback HTTP server hosting the slicer page
assets/
    slice.html                 slicer UI + Tina2S device/process profile
    sample-cube.stl            test model
    lib/kiri/run/              Kiri:Moto engine.js, worker.js, minion.js, manifold.wasm  [generated]
    lib/kiri/wasm/             manifold.wasm (engine copy)                    [generated]
    wasm/                      kiri-geo.wasm, kiri-sla.wasm, manifold.wasm    [generated]
tools/build-slicer.sh          rebuilds the slicer assets from vendored source
tools/patches/                 local patches applied to the vendored slicer
thirdparty/grid-apps/          Kiri:Moto source (git submodule, pinned commit)
docs/LAN-PROTOCOL.md           recovered Tina2S local Wi-Fi protocol
```

Files marked `[generated]` are produced by `tools/build-slicer.sh`; they are
committed for convenience but are rebuilt from `thirdparty/grid-apps` by the script.

## Third-party components

- **Kiri:Moto** — © GridSpace, **MIT License**.
  <https://github.com/GridSpace/grid-apps>. The source is vendored at
  `thirdparty/grid-apps`; the shipped `assets/lib/kiri` and `assets/wasm` binaries
  are built from it by `tools/build-slicer.sh`, and the slicing logic lives in
  `assets/slice.html`.
- **manifold-3d** (bundled as `manifold.wasm`) — © Emmett Lalish, **Apache-2.0**.
  <https://github.com/elalish/manifold>

## License

**MIT** — see [LICENSE](LICENSE). © 2026 Mark Retallack.

Not affiliated with or endorsed by WEEDO / Entina. "Tina2" and "WEEDO" are trademarks
of their respective owners. Use at your own risk.
