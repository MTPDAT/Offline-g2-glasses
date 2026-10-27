#!/usr/bin/env bash
# Downloads a multilingual whisper.cpp model and the Silero VAD model, then pushes them to the
# phone's app folder with model.txt naming the model to use:
#   /sdcard/Android/data/com.example.g2glasses/files/whisper/
# Usage: bash scripts/fetch_whisper.sh [--model tiny|base|small|large-v3-turbo] [--skip-push] [--serial SERIAL]
# Default model: base. Install the app first. Idempotent: skips cached downloads and files
# already on the phone with the right size.
set -euo pipefail

MODEL="base"
SKIP_PUSH=0; SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --model) MODEL="$2"; shift ;;
        --skip-push) SKIP_PUSH=1 ;;
        --serial) SERIAL="$2"; shift ;;
        *) echo "unknown option $1" >&2; exit 1 ;;
    esac
    shift
done

# Keep in step with WhisperModel.kt.
case "$MODEL" in
    tiny) FILE="ggml-tiny-q5_1.bin" ;;
    base) FILE="ggml-base-q5_1.bin" ;;
    small) FILE="ggml-small-q5_1.bin" ;;
    large-v3-turbo) FILE="ggml-large-v3-turbo-q5_0.bin" ;;
    *) echo "unknown model $MODEL (tiny, base, small, large-v3-turbo)" >&2; exit 1 ;;
esac

MODELS_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
VAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
PACKAGE="com.example.g2glasses"
REMOTE="/sdcard/Android/data/$PACKAGE/files/whisper"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL="$SCRIPT_DIR/.cache/whisper"
mkdir -p "$LOCAL"

download() {  # url dest
    [ -f "$2" ] && return
    echo "Downloading $1 ..."
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "$2.part" "$1"; else wget -O "$2.part" "$1"; fi
    mv "$2.part" "$2"
}

download "$MODELS_URL/$FILE" "$LOCAL/$FILE"
download "$VAD_URL" "$LOCAL/silero_vad.onnx"
printf '%s\n' "$MODEL" > "$LOCAL/model.txt"
echo "Whisper $MODEL ready in $LOCAL"
[ "$SKIP_PUSH" = 1 ] && exit 0

ADB="${ADB:-adb}"
if [ -z "$SERIAL" ]; then
    SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
    [ -n "$SERIAL" ] || { echo "No phone connected. Use --serial emulator-5554 for the emulator." >&2; exit 1; }
fi
"$ADB" -s "$SERIAL" shell pm path "$PACKAGE" | grep -q package: ||
    { echo "$PACKAGE is not installed on $SERIAL. Install the app first." >&2; exit 1; }
# Let the app create its own external files folder first, so it owns it.
"$ADB" -s "$SERIAL" shell am start -n "$PACKAGE/.MainActivity" >/dev/null; sleep 2
"$ADB" -s "$SERIAL" shell mkdir -p "$REMOTE"
for f in "$FILE" silero_vad.onnx model.txt; do
    size=$(wc -c < "$LOCAL/$f" | tr -d ' ')
    remote=$("$ADB" -s "$SERIAL" shell stat -c %s "$REMOTE/$f" 2>/dev/null | tr -d '\r' || true)
    if [ "$remote" = "$size" ] && [ "$f" != model.txt ]; then echo "$f already on device"; continue; fi
    echo "Pushing $f ..."
    "$ADB" -s "$SERIAL" push "$LOCAL/$f" "$REMOTE/$f"
done
echo "Done. In the app, switch the engine to Whisper (it loads $MODEL)."
