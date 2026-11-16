# Offline-g2-glasses
Offline Portuguese → English live captions on Android, for Even Realities G2 glasses.
Phone mic → Vosk (on-device speech recognition) → Google ML Kit (on-device translation) →
captions on screen. Bluetooth output to the glasses comes next (`CaptionSink`).

## Run the MVP (Windows)

Needs Android Studio (for its JDK and the SDK), `JAVA_HOME` set to `...\Android Studio\jbr`,
and a phone with USB debugging on (Samsung: also turn off Auto Blocker).

```powershell
cd C:\dev\Offline-g2-glasses
powershell -ExecutionPolicy Bypass -File scripts\fetch_models.ps1    # Vosk pt model, ~31 MB, not in git
powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1    # build, test, install, launch
```

The first launch needs internet once while ML Kit downloads its language packs; after the app
says **Listening** it works in airplane mode.

## Speech engines: Vosk or Parakeet

Pick the engine with the **Engine** chips at the top of the screen (the choice is remembered).

| | Vosk (default) | Parakeet |
|---|---|---|
| Model | `vosk-model-small-pt-0.3`, 31 MB, in the APK | NVIDIA Parakeet TDT 0.6B v3 int8 via sherpa-onnx, ~670 MB, pushed to the phone |
| How it listens | Streaming, word-by-word partials | Silero VAD cuts sentences at 0.5 s pauses; partial re-decoded about once a second |
| Output | lowercase, no punctuation | cased and punctuated, numbers as digits |

Parakeet's model is too large for the APK. With the app installed and the phone connected:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\fetch_parakeet.ps1    # downloads ~490 MB once, then adb push
```

Logcat metrics carry the engine (`STARTUP engine=...`), and `score_metrics.py` reports each engine
separately, so one log with a Vosk run and a Parakeet run gives a side-by-side comparison.

## Test audio and metrics

- Put 16 kHz, mono, 16-bit WAVs in `G2glasses/app/src/main/assets/test/` (gitignored), rebuild,
  and tap **Run test WAVs**. `scripts/make_test_wavs.py` makes 12 synthetic clips from
  `test_audio/references.csv`; add rows there for your own recordings.
- The app logs tab-separated `STARTUP`, `CAPTION`, `WAV_DONE` and `RESULT` lines under the
  Logcat tag `Metrics`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1 -CollectMetrics
python scripts\score_metrics.py metrics_<timestamp>.txt    # WER/CER, chrF/BLEU, latency, RTF
```

## Languages

Pick the spoken language with **Spoken** on the Captions tab. The list is the 25 languages NVIDIA
Parakeet v3 recognizes (Bulgarian … Ukrainian); ML Kit translates each of them to English, and
English itself is passed through. Vosk ships only Portuguese, so other languages switch to
Parakeet. A language's first use downloads its ML Kit pack (~30 MB), which needs internet once.

## Glasses (Even Realities G2)

The **Glasses** tab connects to the G2 over Bluetooth LE, like the men-g2-ble-gateway browser UI:
status, **Connect**, a **Test message** with Send and Clear, **Show captions on glasses**, and the
event log (also in Logcat, tag `G2Gateway`).

1. Close the Even Realities app (force stop it); while it holds the glasses they don't advertise.
2. Turn the glasses on, tap **Connect**, and allow **Nearby devices**. If Android asks to pair, accept.
3. The first connect scans and saves the pair; later connects reuse it (**Forget glasses** to rescan).

## Modules (`G2glasses/`)

| Module | What it does |
|---|---|
| `app` | Speech pipeline, captions screen, Glasses tab |
| `glasses-ui` | What the glasses show: `GlassesPage`/`TextBox` (576×288 display), `CaptionScreen` (edit this to change the on-glasses UI), `CaptionLayout`. No Bluetooth or speech code. |
| `g2-gateway` | `GlassesGateway` and its BLE implementation `G2BleGateway`: scan, connect both lenses, setup, heartbeats, reconnect, text updates. Port of men-g2-ble-gateway / MentraOS G2.kt (see its `NOTICE.md`). |

Data path: mic → `SpeechToText` (Vosk or Parakeet) → `TranslationPipeline` (ML Kit → English)
→ `Caption` → phone screen, `Metrics` log, and `GlassesCaptionSink` → `CaptionScreen` →
`GlassesPage` → `G2BleGateway` → glasses.

## Code map (`G2glasses/app/src/main/java/com/example/g2glasses/`)

| File | Role |
|---|---|
| `pipeline/SpeechToText.kt`, `AsrEngine.kt` | Swappable ASR interface and the engine list |
| `pipeline/VoskSpeechToText.kt`, `ParakeetSpeechToText.kt` | The two engines |
| `pipeline/SourceLanguage.kt` | The selectable spoken languages |
| `pipeline/TranslationPipeline.kt` | Finals → ML Kit in spoken order → `Caption` flow; WAV test runs |
| `pipeline/Caption.kt` | One record per sentence |
| `pipeline/CaptionSink.kt` | Where captions go: Logcat and the glasses (`GlassesCaptionSink`) |
| `pipeline/Metrics.kt` | The `Metrics` log lines |
| `MainActivity.kt` | Captions tab: status, Offline/Online chip, engine, language, live partial, captions |
| `GlassesPanel.kt` | Glasses tab |
