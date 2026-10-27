# Commit 1 of 5 — Speech Engine (whisper.cpp Android build, model selection)

**Owner:** Bill McCarty
**Order:** Push first. Commit 2 (UI) calls `AsrEngine.create(context, language)` and `AsrEngine.WHISPER`, both added here.

## Commit message

In `commit-message.txt` (used by the commands below):

```
Add Whisper speech engine: whisper.cpp NDK build and model selection

- app/src/main/cpp: CMake fetches whisper.cpp v1.9.5 (SHA-256 pinned) and links
  whisper + ggml statically into libwhisper_jni.so; arm64 targets armv8.2-a+fp16,
  -O3 even in debug builds, no OpenMP, 16 KB page aligned (NDK r28)
- whisper_jni.cpp: load / transcribe / free / system info; text returned as UTF-8 bytes
- WhisperSpeechToText: Silero VAD sentences decoded by whisper.cpp in the spoken language
- WhisperModel + WhisperModelSelector: tiny, base, small, large-v3-turbo (q5); picks from
  model.txt, phone RAM and the files present
- AsrEngine: WHISPER entry; create() takes the spoken language
- build.gradle.kts: ndkVersion 28.2.13676358, CMake 3.31.0; R8 keeps the JNI methods
- scripts/fetch_whisper.{sh,ps1}: download a model + VAD, push them with model.txt
- docs/WHISPER.md
```

## Files

Everything under `files/` goes to the same path in the repo.

| File | Change |
|---|---|
| `G2glasses/app/build.gradle.kts` | modified |
| `G2glasses/app/src/main/cpp/CMakeLists.txt` | new |
| `G2glasses/app/src/main/cpp/whisper_jni.cpp` | new |
| `G2glasses/app/src/main/java/com/example/g2glasses/pipeline/AsrEngine.kt` | modified |
| `G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperModel.kt` | new |
| `G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperNative.kt` | new |
| `G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperSpeechToText.kt` | new |
| `G2glasses/app/src/main/keepRules/rules.keep` | modified |
| `docs/WHISPER.md` | new |
| `scripts/fetch_whisper.ps1` | new |
| `scripts/fetch_whisper.sh` | new |

## Before you push

Install **NDK 28.2.13676358** and **CMake 3.31.0** in the Android SDK (Android Studio → SDK Manager → SDK Tools, tick *Show package details*). Without them Gradle stops with "NDK not configured". Try the engine on a phone with `scripts\fetch_whisper.ps1 -Model base` after installing the APK.

## Apply, commit, push

PowerShell, from the repo (`C:\dev\Offline-g2-glasses`), on the team branch:

```powershell
robocopy "C:\dev\g2-role-commits\1-bill-mccarty-speech-engine-whisper\files" . /E /NFL /NDL /NJH /NJS
git add `
    'G2glasses/app/build.gradle.kts' `
    'G2glasses/app/src/main/cpp/CMakeLists.txt' `
    'G2glasses/app/src/main/cpp/whisper_jni.cpp' `
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/AsrEngine.kt' `
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperModel.kt' `
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperNative.kt' `
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperSpeechToText.kt' `
    'G2glasses/app/src/main/keepRules/rules.keep' `
    'docs/WHISPER.md' `
    'scripts/fetch_whisper.ps1' `
    'scripts/fetch_whisper.sh'
git commit -F "C:\dev\g2-role-commits\1-bill-mccarty-speech-engine-whisper\commit-message.txt"
git push
```

Or from WSL / Git Bash:

```bash
cp -r /mnt/c/dev/g2-role-commits/1-bill-mccarty-speech-engine-whisper/files/. .
git add \
    'G2glasses/app/build.gradle.kts' \
    'G2glasses/app/src/main/cpp/CMakeLists.txt' \
    'G2glasses/app/src/main/cpp/whisper_jni.cpp' \
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/AsrEngine.kt' \
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperModel.kt' \
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperNative.kt' \
    'G2glasses/app/src/main/java/com/example/g2glasses/pipeline/WhisperSpeechToText.kt' \
    'G2glasses/app/src/main/keepRules/rules.keep' \
    'docs/WHISPER.md' \
    'scripts/fetch_whisper.ps1' \
    'scripts/fetch_whisper.sh'
git commit -F /mnt/c/dev/g2-role-commits/1-bill-mccarty-speech-engine-whisper/commit-message.txt
git push
```

Commit with your own git identity (`git config user.name` / `user.email`), so the commit is
yours on GitHub.

## Verified

Applied in order 1→5 to a fresh clone of `feature/vosk-mlkit-mvp` (6f9a9aa), committed one by
one, with `testDebugUnitTest` and `assembleDebug` passing after every commit. Builds for arm64-v8a and x86_64; `libwhisper_jni.so` exports the four JNI functions, is 16 KB aligned and needs only system libraries. The same `whisper_jni.cpp` built for a PC transcribed whisper.cpp's `jfk.wav` sample correctly (tiny model) and returned accented Portuguese text intact.
