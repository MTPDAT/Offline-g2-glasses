#!/usr/bin/env python3
"""Score the app's Logcat metrics against reference transcripts and translations.

    adb logcat -d -s Metrics:V > metrics.txt
    python scripts/score_metrics.py metrics.txt [--refs test_audio/references.csv] [--out results/android]

Reads the tab-separated STARTUP / CAPTION / WAV_DONE / RESULT lines the app logs under the
`Metrics` tag. Each line is attributed to the speech engine of the STARTUP line before it, so one
log holding a Vosk run and a Parakeet run gives a side-by-side comparison. RESULT lines are matched
to references by file name. The references CSV needs
the columns file, reference_source, reference_english (use_case is optional); add rows for your
own recordings (pt_01.wav, ...) next to the synthetic tts_XX.wav ones.

Prints a summary and writes:
  android_by_file.csv    per file: prediction, reference, WER, CER, chrF, RTF
  android_trials.csv     same rows in the *_trials.csv column layout of capstone_tests
                         (note: accuracy_report.py only summarizes whisper base/small rows)

WER/CER compare lowercased text without punctuation, because Vosk outputs neither case nor
punctuation, and with simple Portuguese number words mapped to digits ("dez" -> "10"), because
Parakeet writes digits. Compound numbers ("vinte e cinco") are not mapped. chrF/BLEU need `pip install sacrebleu`; without it only ASR scores are reported.
"""
import argparse
import csv
import re
import statistics
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
EVENTS = ("STARTUP", "CAPTION", "WAV_DONE", "RESULT")


MODEL_NAMES = {"vosk": "vosk-small-pt-0.3+mlkit", "parakeet": "parakeet-tdt-0.6b-v3-int8+mlkit"}


def model_name(engine):
    """Report label for an engine id; Whisper logs whisper-<model>, e.g. whisper-base."""
    if engine.startswith("whisper-"):
        return f"whisper.cpp-{engine[len('whisper-'):]}+mlkit"
    return MODEL_NAMES.get(engine, engine)

PT_NUMBERS = {
    "zero": "0", "um": "1", "uma": "1", "dois": "2", "duas": "2", "três": "3", "quatro": "4",
    "cinco": "5", "seis": "6", "sete": "7", "oito": "8", "nove": "9", "dez": "10", "onze": "11",
    "doze": "12", "treze": "13", "catorze": "14", "quatorze": "14", "quinze": "15", "dezesseis": "16",
    "dezessete": "17", "dezoito": "18", "dezenove": "19", "vinte": "20", "trinta": "30",
    "quarenta": "40", "cinquenta": "50", "sessenta": "60", "setenta": "70", "oitenta": "80",
    "noventa": "90", "cem": "100", "mil": "1000",
}
# "um"/"uma" are usually the article "a", not the number; leave them alone.
PT_NUMBERS_SAFE = {k: v for k, v in PT_NUMBERS.items() if k not in ("um", "uma")}


def parse_metrics(path):
    """Returns {engine: {event: [fields...]}} from a logcat dump (any logcat format).

    Lines belong to the engine named by the most recent STARTUP line; logs from before the
    engine switch existed have no engine= field and count as vosk.
    """
    out = defaultdict(lambda: defaultdict(list))
    pattern = re.compile(r"\b(" + "|".join(EVENTS) + r")\t(.*)$")
    engine = "vosk"
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        m = pattern.search(line.rstrip("\r"))
        if not m:
            continue
        event, fields = m.group(1), m.group(2).split("\t")
        if event == "STARTUP":
            kv = dict(f.split("=", 1) for f in fields if "=" in f)
            engine = kv.get("engine", "vosk")
        out[engine][event].append(fields)
    return out


def normalize(text):
    text = unicodedata.normalize("NFC", text.lower())
    text = re.sub(r"[^\w\s]", " ", text)
    return " ".join(PT_NUMBERS_SAFE.get(w, w) for w in text.split())


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref, hyp):
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h) / len(r) if r else None


def cer(ref, hyp):
    r, h = normalize(ref), normalize(hyp)
    return edit_distance(r, h) / len(r) if r else None


def pct(values, q):
    values = sorted(values)
    if not values:
        return None
    k = (len(values) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(values) - 1)
    return values[lo] + (values[hi] - values[lo]) * (k - lo)


def fmt(x, digits=3):
    return "n/a" if x is None else f"{x:.{digits}f}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("metrics", help="output of: adb logcat -d -s Metrics:V")
    ap.add_argument("--refs", default=str(REPO / "test_audio" / "references.csv"))
    ap.add_argument("--out", default=".", help="folder for the CSV reports")
    ap.add_argument("--device", default="android", help="label for the backend column, e.g. s25ultra")
    a = ap.parse_args()

    try:
        import sacrebleu
    except ImportError:
        sacrebleu = None

    by_engine = parse_metrics(a.metrics)
    if not by_engine:
        sys.exit(f"No Metrics lines found in {a.metrics}")

    refs = {}
    if Path(a.refs).is_file():
        with open(a.refs, newline="", encoding="utf-8-sig") as f:
            refs = {row["file"]: row for row in csv.DictReader(f)}

    print(f"Metrics file: {a.metrics}")
    rows = []
    for engine, m in by_engine.items():
        engine_rows = score_engine(engine, m, refs, sacrebleu)
        rows += engine_rows
        summarize(engine, m, engine_rows, sacrebleu)

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    if rows:
        with (out / "android_by_file.csv").open("w", newline="", encoding="utf-8-sig") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        with (out / "android_trials.csv").open("w", newline="", encoding="utf-8-sig") as f:
            fields = ["sample_id", "model", "backend", "language", "reference_source", "source_prediction",
                      "reference_english", "english_prediction", "source_wer", "source_cer", "rtf"]
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            for r in rows:
                writer.writerow({
                    "sample_id": Path(r["file"]).stem, "model": model_name(r["engine"]),
                    "backend": a.device, "language": "pt",
                    "reference_source": r["reference_source"], "source_prediction": r["source_prediction"],
                    "reference_english": r["reference_english"], "english_prediction": r["english_prediction"],
                    "source_wer": r["source_wer"], "source_cer": r["source_cer"], "rtf": r["rtf"],
                })
        print(f"\nWrote {out / 'android_by_file.csv'} and {out / 'android_trials.csv'}")
    missing = sorted({r["file"] for r in rows if not r["reference_source"]})
    if missing:
        print(f"No reference for: {', '.join(missing)}")


def score_engine(engine, m, refs, sacrebleu):
    # Last WAV_DONE / RESULT per file (a file may have been run more than once).
    wav = {f[0]: f for f in m["WAV_DONE"] if len(f) >= 4}
    results = {}
    for f in m["RESULT"]:
        f = f + [""] * (3 - len(f))
        results[f[0]] = {"source": f[1], "english": f[2]}

    rows = []
    for name, res in sorted(results.items()):
        ref = refs.get(name, {})
        src_ref = ref.get("reference_source", "").strip()
        eng_ref = ref.get("reference_english", "").strip()
        w = wav.get(name)
        rows.append({
            "engine": engine,
            "file": name,
            "use_case": ref.get("use_case", ""),
            "reference_source": src_ref,
            "source_prediction": res["source"],
            "source_wer": wer(src_ref, res["source"]) if src_ref else None,
            "source_cer": cer(src_ref, res["source"]) if src_ref else None,
            "reference_english": eng_ref,
            "english_prediction": res["english"],
            "chrf": sacrebleu.sentence_chrf(res["english"], [eng_ref]).score if sacrebleu and eng_ref else None,
            "audio_s": float(w[1]) if w else None,
            "wall_s": float(w[2]) if w else None,
            "rtf": float(w[3]) if w else None,
        })
    return rows


def summarize(engine, m, rows, sacrebleu):
    print(f"\n== {engine} ({model_name(engine)})")
    for f in m["STARTUP"][-1:]:
        print("Startup (last):", "  ".join(f))

    translate_ms = [int(f[1]) for f in m["CAPTION"] if len(f) >= 2 and f[1].isdigit()]
    if translate_ms:
        print(f"Captions: {len(translate_ms)}  translateMs mean={statistics.mean(translate_ms):.0f} "
              f"p50={pct(translate_ms, .5):.0f} p95={pct(translate_ms, .95):.0f} max={max(translate_ms)}")

    rtfs = [r["rtf"] for r in rows if r["rtf"] is not None]
    if rtfs:
        print(f"ASR real-time factor over {len(rtfs)} WAVs: mean={statistics.mean(rtfs):.3f} "
              f"max={max(rtfs):.3f} (below 1 = faster than real time)")

    scored = [r for r in rows if r["reference_source"]]
    if scored:
        total_words = sum(len(normalize(r["reference_source"]).split()) for r in scored)
        total_errs = sum(edit_distance(normalize(r["reference_source"]).split(),
                                       normalize(r["source_prediction"]).split()) for r in scored)
        print(f"ASR over {len(scored)} files: corpus WER={total_errs / total_words:.3f}  "
              f"mean CER={statistics.mean(r['source_cer'] for r in scored):.3f}")
    eng = [r for r in rows if r["reference_english"]]
    if eng and sacrebleu:
        hyps = [r["english_prediction"] for r in eng]
        refs_ = [[r["reference_english"] for r in eng]]
        print(f"Translation over {len(eng)} files: chrF={sacrebleu.corpus_chrf(hyps, refs_).score:.1f}  "
              f"BLEU={sacrebleu.corpus_bleu(hyps, refs_).score:.1f}")
    elif eng:
        print("Translation: install sacrebleu for chrF/BLEU")
    if rows and len(rows) < 10:
        print("CAUTION: fewer than 10 clips; results are preliminary.")

if __name__ == "__main__":
    main()
