# Development setup: Makefile and Docker

## `make` from WSL, Linux or macOS

The repo-root `Makefile` wraps the scripts and Gradle. `make help` lists everything; the main ones:

| Command | What it does |
|---|---|
| `make up` | **Start everything:** fetch models → build → tests → install on the phone → push the Whisper model → launch → follow the logs. Add `PARAKEET=1` to push Parakeet too |
| `make build` / `make test` | Debug APK / all tests that need no phone (Kotlin + Python) |
| `make install launch logs` | Phone only, with the last build |
| `make push-whisper WHISPER_MODEL=small` | Change the Whisper model on the phone |
| `make metrics` | Save the phone's Metrics log, score it, check the performance budgets |
| `make doctor` | Show the JDK, Gradle, SDK and phone that would be used |
| `make docker-build` | Build and test in the container (below) |

Pick a phone with `SERIAL=<serial from adb devices>`.

**Which toolchain.** From WSL with the repo on a Windows drive (`/mnt/c/dev/Offline-g2-glasses`),
`make` drives the Windows Android Studio toolchain, the same one `scripts/deploy_phone.ps1` uses:
`gradlew.bat` through `cmd.exe`, Android Studio's JDK, the SDK in `%LOCALAPPDATA%\Android\Sdk`,
and the Windows `adb.exe` (the phone is plugged into Windows). It sets
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\dev\.jtmp`, without which Gradle under WSL interop
fails with "Unable to establish loopback connection". Everywhere else it uses `./gradlew` and `adb`
from `PATH`. Force either with `TOOLCHAIN=windows` or `TOOLCHAIN=native`; change paths with
`WIN_SDK=...`, `WIN_JAVA_HOME=...`, `ADB=...`.

**Requirements.** Android Studio with SDK platform 37, **NDK 28.2.13676358** and **CMake 3.31.0**
(SDK Manager → SDK Tools, *Show package details*), USB debugging on the phone, and `python3` in WSL
for the score/budget scripts. On Linux without Android Studio, `bash docker/install_android_sdk.sh`
installs the same SDK packages into `$ANDROID_HOME`.

## Docker

`docker/Dockerfile` is the build environment CI uses: JDK 25, SDK platform 37, build-tools 36,
NDK 28, CMake 3.31 (installed by `docker/install_android_sdk.sh`).

```bash
make docker-build    # builds the image once, then setup + build + test inside it
make docker-shell    # a shell in the image, repo mounted read-only at /src
```

The container never writes to your checkout: `docker/run_in_container.sh` copies it to `/build`
(without build outputs, `local.properties` or the model cache), runs `make` there, and copies the
APK and test reports to `docker/out/`. Gradle caches and downloaded models are kept in the Docker
volumes `g2glasses-gradle` and `g2glasses-models`, so later runs are fast. Docker Desktop must be
running; from WSL the Makefile falls back to `docker.exe` when there is no `docker` in the distro.

The container builds and tests only: it has no USB phone. Install its APK with
`adb install -r docker/out/app-debug.apk` (or `make install` after copying it).
