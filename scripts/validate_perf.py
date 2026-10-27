#!/usr/bin/env python3
"""Check a device run's Logcat metrics against performance budgets; exit 1 if any is over.

    adb logcat -d -s Metrics:V > metrics.txt          # or: deploy_phone.ps1 -CollectMetrics
    python scripts/validate_perf.py metrics.txt [--budgets test_audio/perf_budgets.json]

Reads the same STARTUP / CAPTION / WAV_DONE lines as score_metrics.py (each attributed to the
engine of the STARTUP line before it) and checks, per engine:

  startup_model_ms   speech model load time (STARTUP modelUnpackMs), last startup
  startup_mlkit_ms   ML Kit ready time (STARTUP mlkitReadyMs), last startup
  translate_p95_ms   95th percentile of ML Kit time per caption (CAPTION translateMs)
  rtf_max            worst ASR real-time factor over the test WAVs (WAV_DONE); 1.0 = real time

Budgets come from the JSON file: "default", overridden by the engine family ("whisper") and then
the exact engine id ("whisper-small"). A check with no data (e.g. no WAVs were run) is skipped,
not failed. Exit codes: 0 all within budget, 1 over budget, 2 no metrics in the file.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from score_metrics import model_name, parse_metrics, pct  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
DEFAULT_BUDGETS = REPO / "test_audio" / "perf_budgets.json"


def budgets_for(engine, budgets):
    """default <- family (text before the first '-') <- exact engine id."""
    out = dict(budgets.get("default", {}))
    out.update(budgets.get(engine.split("-", 1)[0], {}))
    out.update(budgets.get(engine, {}))
    return out


def measure(events):
    """The measured values the budgets refer to; None where the log has no data."""
    startups = [dict(f.split("=", 1) for f in fields if "=" in f) for fields in events["STARTUP"]]
    last = startups[-1] if startups else {}
    translate = [int(f[1]) for f in events["CAPTION"] if len(f) >= 2 and f[1].isdigit()]
    rtfs = [float(f[3]) for f in events["WAV_DONE"] if len(f) >= 4]
    return {
        "startup_model_ms": int(last["modelUnpackMs"]) if "modelUnpackMs" in last else None,
        "startup_mlkit_ms": int(last["mlkitReadyMs"]) if "mlkitReadyMs" in last else None,
        "translate_p95_ms": pct(translate, 0.95) if translate else None,
        "rtf_max": max(rtfs) if rtfs else None,
    }


def check(by_engine, budgets):
    """Rows of (engine, metric, measured, budget, status) with status PASS, FAIL or SKIP."""
    rows = []
    for engine, events in by_engine.items():
        limits = budgets_for(engine, budgets)
        measured = measure(events)
        for metric, limit in sorted(limits.items()):
            value = measured.get(metric)
            if value is None:
                status = "SKIP"
            else:
                status = "PASS" if value <= limit else "FAIL"
            rows.append((engine, metric, value, limit, status))
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("metrics", help="output of: adb logcat -d -s Metrics:V")
    ap.add_argument("--budgets", default=str(DEFAULT_BUDGETS), help="JSON budgets file")
    a = ap.parse_args(argv)

    by_engine = parse_metrics(a.metrics)
    if not by_engine:
        print(f"No Metrics lines found in {a.metrics}", file=sys.stderr)
        return 2
    budgets = json.loads(Path(a.budgets).read_text(encoding="utf-8"))

    rows = check(by_engine, budgets)
    print(f"{'engine':<34} {'metric':<18} {'measured':>10} {'budget':>10}  result")
    for engine, metric, value, limit, status in rows:
        shown = "-" if value is None else f"{value:.3f}" if isinstance(value, float) else str(value)
        print(f"{model_name(engine):<34} {metric:<18} {shown:>10} {limit:>10}  {status}")
    failed = [r for r in rows if r[4] == "FAIL"]
    print(f"\n{len(rows) - len(failed)} of {len(rows)} checks within budget" + (", FAILED" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
