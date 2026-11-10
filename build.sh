#!/bin/bash
# Build PoloPrint APK directly with Android SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-/home/mark/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
MIN_SDK=21
TARGET_SDK=34
OUT="build"

# Optional: stamp versionCode/versionName (e.g. from a CI tag).
declare -a VERARGS=()
[ -n "${VERSION_CODE:-}" ] && VERARGS+=(--version-code "$VERSION_CODE")
[ -n "${VERSION_NAME:-}" ] && VERARGS+=(--version-name "$VERSION_NAME")

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/obj" "$OUT/dex"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    "${VERARGS[@]}" \
    -A assets \
    --java "$OUT/gen"

echo "== javac =="
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -nowarn \
    -classpath "$PLATFORM" \
    -d "$OUT/obj" @"$OUT/sources.txt"

echo "== d8 =="
find "$OUT/obj" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api "$MIN_SDK" --output "$OUT/dex" @"$OUT/classes.txt"

echo "== package dex =="
python3 - "$OUT/base.apk" "$OUT/app.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, shutil
base, out, dex = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "== zipalign =="
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"

echo "== sign =="
# Use a release keystore when provided (KEYSTORE_PATH + passwords), else the
# debug keystore (generated on demand).
KS="${KEYSTORE_PATH:-$HOME/.android/debug.keystore}"
KS_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KS_PASS="${KEYSTORE_PASSWORD:-android}"
KEY_PASS="${KEY_PASSWORD:-android}"
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
        -storepass "$KS_PASS" -keypass "$KEY_PASS" \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out Tina2Print.apk "$OUT/app-aligned.apk"

echo "== done =="
ls -l Tina2Print.apk
"$BT/apksigner" verify --print-certs Tina2Print.apk | head -3
