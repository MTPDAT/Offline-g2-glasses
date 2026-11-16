#!/usr/bin/env bash
# Downloads the Vosk Portuguese small model into G2glasses/app/src/main/assets/model-pt/ and the
# sherpa-onnx Android library (used by the Parakeet engine) into G2glasses/app/libs/.
# Idempotent: skips whatever is already in place. Neither is committed to git.
# Usage:  bash scripts/fetch_models.sh
set -euo pipefail

MODEL_NAME="vosk-model-small-pt-0.3"
URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"
UUID="pt-small-0.3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
DEST="$REPO_ROOT/G2glasses/app/src/main/assets/model-pt"
CACHE="$SCRIPT_DIR/.cache"

# vosk-model-small-pt-0.3 uses Vosk's older flat layout (final.mdl, HCLr.fst, Gr.fst, ivector/ at
# the top level, no am/ conf/ graph/ folders). Newer models use am/final.mdl. Vosk loads both.
model_complete() {
    for p in ivector README uuid; do
        [ -e "$DEST/$p" ] || return 1
    done
    [ -e "$DEST/final.mdl" ] || [ -e "$DEST/am/final.mdl" ] || return 1
    [ "$(tr -d '[:space:]' < "$DEST/uuid")" = "$UUID" ]
}

download() {  # url dest
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "$2.part" "$1"; else wget -O "$2.part" "$1"; fi
    mv "$2.part" "$2"
}

# sherpa-onnx AAR (native libs + Kotlin API) for the Parakeet engine; ~50 MB, not on Maven Central.
SHERPA_VERSION="1.13.8"
AAR="$REPO_ROOT/G2glasses/app/libs/sherpa-onnx-$SHERPA_VERSION.aar"
if [ ! -f "$AAR" ]; then
    mkdir -p "$(dirname "$AAR")"
    echo "Downloading sherpa-onnx-$SHERPA_VERSION.aar ..."
    download "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar" "$AAR"
else
    echo "sherpa-onnx AAR already present."
fi

if model_complete; then
    echo "Model already present in $DEST (uuid $UUID). Nothing to do."
    exit 0
fi

mkdir -p "$CACHE"
ZIP="$CACHE/$MODEL_NAME.zip"
if [ ! -f "$ZIP" ]; then
    echo "Downloading $URL ..."
    download "$URL" "$ZIP"
fi

EXTRACT="$CACHE/extract"
rm -rf "$EXTRACT"
unzip -q "$ZIP" -d "$EXTRACT"

# The zip contains a top-level folder; copy its *contents* so there is no extra level.
SRC="$EXTRACT/$MODEL_NAME"
[ -f "$SRC/README" ] || { echo "Unexpected zip layout: $SRC/README not found" >&2; exit 1; }

rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$SRC"/. "$DEST"/

# Vosk's StorageService re-copies the model on the phone whenever this value changes.
printf '%s' "$UUID" > "$DEST/uuid"
rm -rf "$EXTRACT"

model_complete || { echo "Model folder incomplete after extraction: $DEST" >&2; exit 1; }
echo "Model ready in $DEST"
