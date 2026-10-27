# Whisper speech engine (whisper.cpp)

A third engine next to Vosk and Parakeet: OpenAI Whisper running on the phone's CPU through
[whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.9.5, built from source with the NDK.
Pick it with the **Engine** chips (it appears as **Whisper**).

## Build

The app module now has a native build (`G2glasses/app/src/main/cpp/`):

| File | What it does |
|---|---|
| `CMakeLists.txt` | Downloads the pinned whisper.cpp release (SHA-256 checked), builds whisper + ggml as static libraries and links them into `libwhisper_jni.so` |
| `whisper_jni.cpp` | JNI bridge: load a model, transcribe float PCM, free, CPU info |

It needs, in the Android SDK: **NDK 28.2.13676358** and **CMake 3.31.0**
(Android Studio → SDK Manager → SDK Tools → *NDK (Side by side)* and *CMake*, tick *Show package
details* to pick the versions). The first build downloads whisper.cpp (~10 MB) into `app/.cxx/`;
for an offline build pass `-DWHISPER_CPP_DIR=<unpacked whisper.cpp>` in `externalNativeBuild`.

Choices made in the native build:

- `arm64-v8a` targets `armv8.2-a+fp16`, like whisper.cpp's own Android example.
- No OpenMP (one fewer runtime to package); static C++ runtime; NDK r28 aligns to 16 KB pages.
- whisper/ggml are compiled with `-O3` even in debug builds: the demo APK is a debug build, and an
  unoptimized whisper.cpp is far slower than real time.

## Models and model selection

Models are too big for the APK. Push one with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch_whisper.ps1 -Model base   # tiny | base | small | large-v3-turbo
```
```bash
bash scripts/fetch_whisper.sh --model base
```

This puts the ggml model, `silero_vad.onnx` and `model.txt` (the model's id) in
`/sdcard/Android/data/com.example.g2glasses/files/whisper/`.

| Model | File | Size | Picked automatically from |
|---|---|---|---|
| tiny | `ggml-tiny-q5_1.bin` | 31 MB | 2 GB RAM |
| base | `ggml-base-q5_1.bin` | 57 MB | 3 GB RAM |
| small | `ggml-small-q5_1.bin` | 181 MB | 4 GB RAM |
| large-v3-turbo | `ggml-large-v3-turbo-q5_0.bin` | 547 MB | 8 GB RAM |

All are the multilingual (not `.en`) models, quantized. `WhisperModelSelector` decides which one to
load from what is on the phone:

1. the model named in `model.txt`, if its file is there and the phone has the RAM for it;
2. otherwise the largest model present that fits in RAM;
3. otherwise the smallest model present.

**base** is the default: fast enough to keep up with speech on a mid-range phone. **small** is
noticeably more accurate for Portuguese and still keeps up on an S25 Ultra; measure with the test
WAVs and `score_metrics.py` before switching the default.

## How it listens

Whisper is not a streaming model. Like Parakeet, Silero VAD (sherpa-onnx) cuts the audio at 0.5 s
pauses and each sentence is decoded once, in the spoken language selected in the app (Whisper only
transcribes; ML Kit still translates). There is no live partial: re-decoding the sentence in
progress is too slow on a phone. Bracketed annotations Whisper sometimes emits (`[BLANK_AUDIO]`,
`(music)`) are removed.

Logcat metrics name the model: `STARTUP engine=whisper-base ...`.
