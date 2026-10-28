#!/usr/bin/env bash
# Runs inside the g2glasses-build image (see Makefile: make docker-build).
#
# Copies the read-only checkout mounted at $SRC into $BUILD, leaving out build outputs, Gradle
# state, local.properties (it points at the host's SDK) and the model cache, then runs make there
# with the native toolchain. The APK and test reports are copied to $OUT.
#   bash run_in_container.sh setup build test      # arguments are make targets
set -euo pipefail

SRC="${SRC:-/src}"
BUILD="${BUILD:-/build}"
OUT="${OUT:-/out}"

mkdir -p "$BUILD" "$OUT"
tar -C "$SRC" \
    --exclude=.git --exclude=build --exclude=.cxx --exclude=.gradle --exclude=.kotlin \
    --exclude=local.properties --exclude=./scripts/.cache --exclude=./docker/out \
    -cf - . | tar -C "$BUILD" -xf -

cd "$BUILD"
make TOOLCHAIN=native "$@"

apk="G2glasses/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] && cp "$apk" "$OUT/"
for reports in G2glasses/*/build/reports/tests; do
    [ -d "$reports" ] || continue
    module="$(basename "$(dirname "$(dirname "$(dirname "$reports")")")")"
    rm -rf "$OUT/tests-$module"
    cp -r "$reports" "$OUT/tests-$module"
done
echo "Outputs in $OUT"
