#!/usr/bin/env bash
# Downloads the Parakeet TDT 0.6B v3 model (multilingual, includes Portuguese; int8, ~490 MB) and the
# Silero VAD model, then pushes them to the phone's app folder:
#   /sdcard/Android/data/com.example.g2glasses/files/parakeet-pt/
# Usage: bash scripts/fetch_parakeet.sh [--skip-push] [--serial SERIAL]   (install the app first)
set -euo pipefail

MODEL_NAME="sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
FILES="encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt"
PACKAGE="com.example.g2glasses"
REMOTE="/sdcard/Android/data/$PACKAGE/files/parakeet-pt"

SKIP_PUSH=0; SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-push) SKIP_PUSH=1 ;;
        --serial) SERIAL="$2"; shift ;;
        *) echo "unknown option $1" >&2; exit 1 ;;
    esac
    shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE="$SCRIPT_DIR/.cache"
LOCAL="$CACHE/parakeet-pt"
mkdir -p "$LOCAL"

download() {  # url dest
    [ -f "$2" ] && return
    echo "Downloading $1 ..."
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "$2.part" "$1"; else wget -O "$2.part" "$1"; fi
    mv "$2.part" "$2"
}

missing=0
for f in $FILES; do [ -f "$LOCAL/$f" ] || missing=1; done
if [ "$missing" = 1 ]; then
    TAR="$CACHE/$MODEL_NAME.tar.bz2"
    download "$BASE/$MODEL_NAME.tar.bz2" "$TAR"
    echo "Extracting ..."
    members=""; for f in $FILES; do members="$members $MODEL_NAME/$f"; done
    tar -xjf "$TAR" -C "$CACHE" $members
    for f in $FILES; do mv -f "$CACHE/$MODEL_NAME/$f" "$LOCAL/$f"; done
    rm -rf "${CACHE:?}/$MODEL_NAME"
fi
download "$BASE/silero_vad.onnx" "$LOCAL/silero_vad.onnx"
echo "Model files ready in $LOCAL"
[ "$SKIP_PUSH" = 1 ] && exit 0

if [ -z "$SERIAL" ]; then
    SERIAL="$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
    [ -n "$SERIAL" ] || { echo "No phone connected. Use --serial emulator-5554 for the emulator." >&2; exit 1; }
fi
adb -s "$SERIAL" shell pm path "$PACKAGE" | grep -q package: ||
    { echo "$PACKAGE is not installed on $SERIAL. Install the app first." >&2; exit 1; }
# Let the app create its own external files folder first, so it owns it.
adb -s "$SERIAL" shell am start -n "$PACKAGE/.MainActivity" >/dev/null; sleep 2
adb -s "$SERIAL" shell mkdir -p "$REMOTE"
for f in $FILES silero_vad.onnx; do
    size=$(wc -c < "$LOCAL/$f" | tr -d ' ')
    remote=$(adb -s "$SERIAL" shell stat -c %s "$REMOTE/$f" 2>/dev/null | tr -d '\r' || true)
    if [ "$remote" = "$size" ]; then echo "$f already on device"; continue; fi
    echo "Pushing $f ..."
    adb -s "$SERIAL" push "$LOCAL/$f" "$REMOTE/$f"
done
echo "Done. In the app, switch the engine to Parakeet."
