# Testing and performance validation

Three layers: JVM unit tests (no phone), Python tests for the PC-side scripts, and on-device
performance checks against budgets.

## 1. Unit tests (Gradle, no phone needed)

```bash
cd G2glasses && ./gradlew testDebugUnitTest          # Windows: .\gradlew.bat testDebugUnitTest
```

Reports: `G2glasses/<module>/build/reports/tests/testDebugUnitTest/index.html`.

| Test | Covers |
|---|---|
| `glasses-ui` `RenderGovernorTest` | Repeats dropped, partials throttled to the interval and merged, urgent pages skip the wait, `forgetShown`, stats (fake clock and timers) |
| `glasses-ui` `CaptionScreenPartialTest` | Live speech on the bottom line, blank partials hidden, long partials stay valid |
| `glasses-ui` `RenderPerformanceTest` | Budgets for main-thread work: `CaptionScreen.render` < 0.5 ms, `CaptionLayout.wrap` (200 captions) < 1 ms, `RenderGovernor.submit` < 10 µs |
| `app` `WhisperModelSelectorTest` | model.txt preference, RAM limits, fallbacks, file names match the fetch scripts |
| `app` `WhisperTextTest` | `[BLANK_AUDIO]`, `(music)`, `♪…♪` removed from Whisper output |
| `app` `AsrEngineTest` | Which engine hears which language; Whisper's language list equals the selectable languages; chip order |
| existing | Vosk result parsing, Metrics line formats, WAV header checks, G2 protocol, caption layout, glasses pages |

The performance budgets are about 10x what a laptop or CI runner needs: they catch a regression
(an accidental O(n²) wrap, re-rendering all history) without failing on a slow machine.

## 2. Python tests (scripts)

```bash
python -m unittest discover -s scripts/tests
```

`scripts/tests/data/metrics_sample.txt` is a small Logcat dump with a Vosk run (within budget)
and a whisper-base run (over the RTF budget), used to test `validate_perf.py` and the parsing in
`score_metrics.py`.

## 3. On-device performance validation

Run the test WAVs on the phone (**Run test WAVs**), once per engine you want to check, then:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\deploy_phone.ps1 -CollectMetrics
python scripts\validate_perf.py metrics_<timestamp>.txt      # exit 1 if over budget
python scripts\score_metrics.py metrics_<timestamp>.txt      # accuracy: WER/CER, chrF/BLEU
```

Budgets live in `test_audio/perf_budgets.json`: `default`, then the engine family
(`vosk`, `parakeet`, `whisper`), then an exact engine id (`whisper-large-v3-turbo`).

| Check | Default budget | Why |
|---|---|---|
| `rtf_max` | 1.0 (Whisper 0.8) | ASR slower than real time falls behind the speaker; Whisper has no live partial, so it needs headroom |
| `translate_p95_ms` | 500 ms | ML Kit time per caption |
| `startup_model_ms` | 15 s (Parakeet 30 s) | Model load before the first caption |
| `startup_mlkit_ms` | 10 s | ML Kit ready (pack already downloaded) |

A check with no data in the log (e.g. no WAVs were run) shows `SKIP`, not `FAIL`.
