# Build environment for the Android app, the same toolchain CI uses:
# JDK 25, Android SDK platform 37, build-tools 36, NDK 28 (whisper.cpp) and CMake 3.31.
#
#   make docker-build      # from the repo root: builds this image, then build + tests inside it
#
# or by hand:
#   docker build -t g2glasses-build docker
#   docker run --rm -v "$PWD":/src:ro -v "$PWD/docker/out":/out g2glasses-build bash /src/docker/run_in_container.sh build test
FROM eclipse-temurin:25-jdk-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip make python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

COPY install_android_sdk.sh /tmp/install_android_sdk.sh
RUN bash /tmp/install_android_sdk.sh && rm /tmp/install_android_sdk.sh

# Gradle caches go here; mount a volume on it to keep them between runs.
ENV GRADLE_USER_HOME=/gradle
WORKDIR /build
