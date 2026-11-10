#!/usr/bin/env bash
#
# Rebuild the bundled Kiri:Moto slicer assets from the vendored source.
#
# The slicer runtime that ships inside the APK is Kiri:Moto
# (https://github.com/GridSpace/grid-apps, MIT).  Its source is vendored as a
# git submodule at thirdparty/grid-apps and this script turns that source into
# the files the app loads at runtime:
#
#   assets/lib/kiri/run/engine.js     <- src/pack/kiri-eng.js   (esbuild)
#   assets/lib/kiri/run/worker.js     <- src/pack/kiri-work.js  (esbuild)
#   assets/lib/kiri/run/minion.js     <- src/pack/kiri-pool.js  (esbuild)
#   assets/lib/kiri/run/manifold.wasm <- node_modules/manifold-3d
#   assets/lib/kiri/wasm/manifold.wasm
#   assets/wasm/manifold.wasm
#   assets/wasm/kiri-geo.wasm         <- src/wasm/kiri-geo.cpp  (em++)
#   assets/wasm/kiri-sla.wasm         <- src/wasm/kiri-sla.c    (emcc)
#
# Requirements: node/npm and the Emscripten SDK (emcc/em++) on PATH.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$HERE/thirdparty/grid-apps"
OUT="$HERE/assets"
KIRI="$OUT/lib/kiri/run"

for tool in node npm emcc em++; do
  command -v "$tool" >/dev/null 2>&1 || { echo "error: '$tool' not found on PATH" >&2; exit 1; }
done
[ -f "$SRC/src/kiri/run/engine.js" ] || {
  echo "error: submodule missing — run: git submodule update --init --recursive" >&2; exit 1; }

mkdir -p "$KIRI" "$OUT/lib/kiri/wasm" "$OUT/wasm"

# Apply the local patch series (see tools/patches/).  Upstream is an unmodified
# submodule, so our few changes live here as patches rather than as edits that
# would be lost on `git submodule update`.  Each patch is applied only once.
for patch in "$HERE"/tools/patches/*.patch; do
  [ -e "$patch" ] || continue
  name="$(basename "$patch")"
  if git -C "$SRC" apply --check "$patch" 2>/dev/null; then
    git -C "$SRC" apply "$patch"
    echo "==> applied $name"
  elif git -C "$SRC" apply --reverse --check "$patch" 2>/dev/null; then
    echo "==> $name already applied"
  else
    echo "error: $name does not apply to the vendored slicer" >&2; exit 1
  fi
done

echo "==> 1/4  Installing Kiri:Moto dependencies (npm)"
( cd "$SRC" && npm install --ignore-scripts )
# Pin manifold-3d to the exact version the slicer was built against.  Its
# package.json says "^3.3.2", which floats to a newer manifold.wasm; 3.3.2 is
# the build that ships inside the app (byte-identical manifold.wasm).
( cd "$SRC" && npm install --no-save --ignore-scripts manifold-3d@3.3.2 )

echo "==> 2/4  Building generated JS bundles (three, jszip, quickjs, planegcs)"
( cd "$SRC" && npm run webpack-ext )

echo "==> 3/4  Bundling engine.js and worker.js (esbuild)"
( cd "$SRC" && node bin/esbuild.config.mjs prod )

echo "==> 4/4  Compiling WASM geometry modules (emscripten)"
( cd "$SRC/src/wasm" \
  && emcc --no-entry -o kiri-sla.wasm kiri-sla.c -O3 \
        -s ERROR_ON_UNDEFINED_SYMBOLS=0 -s INITIAL_MEMORY=64mb -s ALLOW_MEMORY_GROWTH=1 \
  && em++ --no-entry -o kiri-geo.wasm clipper.cpp kiri-geo.cpp -Oz \
        -s ERROR_ON_UNDEFINED_SYMBOLS=0 -s TOTAL_MEMORY=40mb )

echo "==> Copying assets into $OUT"
cp "$SRC/src/pack/kiri-eng.js"    "$KIRI/engine.js"
cp "$SRC/src/pack/kiri-work.js"   "$KIRI/worker.js"
cp "$SRC/src/pack/kiri-pool.js"   "$KIRI/minion.js"
cp "$SRC/node_modules/manifold-3d/manifold.wasm" "$KIRI/manifold.wasm"
cp "$SRC/node_modules/manifold-3d/manifold.wasm" "$OUT/lib/kiri/wasm/manifold.wasm"
cp "$SRC/node_modules/manifold-3d/manifold.wasm" "$OUT/wasm/manifold.wasm"
cp "$SRC/src/wasm/kiri-geo.wasm"  "$OUT/wasm/kiri-geo.wasm"
cp "$SRC/src/wasm/kiri-sla.wasm"  "$OUT/wasm/kiri-sla.wasm"

echo "==> Done. Slicer assets:"
ls -l "$KIRI"/engine.js "$KIRI"/worker.js "$KIRI"/manifold.wasm \
      "$OUT/wasm"/kiri-geo.wasm "$OUT/wasm"/kiri-sla.wasm
