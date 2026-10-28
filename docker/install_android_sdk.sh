#!/usr/bin/env bash
# Installs the Android SDK pieces this project builds with into $ANDROID_HOME.
# Used by docker/Dockerfile; also works on any Linux machine (or WSL) with a JDK 17+:
#   ANDROID_HOME=$HOME/android-sdk bash docker/install_android_sdk.sh
# Idempotent: sdkmanager skips packages that are already installed.
set -euo pipefail

: "${ANDROID_HOME:?set ANDROID_HOME to the folder the SDK should live in}"

# Keep in step with G2glasses/app/build.gradle.kts (compileSdk, ndkVersion, cmake version).
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-13114758}"
PACKAGES=(
    "platform-tools"
    "platforms;android-37.0"
    "build-tools;36.0.0"
    "ndk;28.2.13676358"
    "cmake;3.31.0"
)

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    echo "Downloading Android command-line tools ($CMDLINE_TOOLS_BUILD) ..."
    tmp="$(mktemp -d)"
    curl -fL --retry 3 -o "$tmp/tools.zip" \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
fi

# sdkmanager asks to accept each license; answer yes to all of them.
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "${PACKAGES[@]}"
echo "Android SDK ready in $ANDROID_HOME"
