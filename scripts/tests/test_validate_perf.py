"""Tests for validate_perf.py and the parts of score_metrics.py it relies on.

    python -m unittest discover -s scripts/tests
"""
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

import score_metrics  # noqa: E402
import validate_perf  # noqa: E402

SAMPLE = Path(__file__).resolve().parent / "data" / "metrics_sample.txt"
BUDGETS = SCRIPTS.parent / "test_audio" / "perf_budgets.json"


class ParseTest(unittest.TestCase):
    def test_linesAreAttributedToTheEngineOfTheStartupBeforeThem(self):
        by_engine = score_metrics.parse_metrics(SAMPLE)
        self.assertEqual({"vosk", "whisper-base"}, set(by_engine))
        self.assertEqual(2, len(by_engine["vosk"]["CAPTION"]))
        self.assertEqual(1, len(by_engine["whisper-base"]["CAPTION"]))

    def test_modelNames(self):
        self.assertEqual("vosk-small-pt-0.3+mlkit", score_metrics.model_name("vosk"))
        self.assertEqual("whisper.cpp-small+mlkit", score_metrics.model_name("whisper-small"))
        self.assertEqual("something-new", score_metrics.model_name("something-new"))


class BudgetTest(unittest.TestCase):
    def setUp(self):
        self.budgets = json.loads(BUDGETS.read_text(encoding="utf-8"))

    def test_familyAndExactEngineOverrideTheDefault(self):
        b = validate_perf.budgets_for("whisper-large-v3-turbo", self.budgets)
        self.assertEqual(1.5, b["rtf_max"])                                         # exact id
        self.assertEqual(self.budgets["default"]["translate_p95_ms"], b["translate_p95_ms"])  # default
        self.assertEqual(0.8, validate_perf.budgets_for("whisper-base", self.budgets)["rtf_max"])  # family

    def test_measure(self):
        m = validate_perf.measure(score_metrics.parse_metrics(SAMPLE)["vosk"])
        self.assertEqual(2400, m["startup_model_ms"])
        self.assertEqual(900, m["startup_mlkit_ms"])
        self.assertAlmostEqual(177.0, m["translate_p95_ms"])
        self.assertAlmostEqual(0.387, m["rtf_max"])

    def test_sample_voskPasses_whisperBaseFailsOnRtf(self):
        rows = validate_perf.check(score_metrics.parse_metrics(SAMPLE), self.budgets)
        status = {(e, metric): s for e, metric, _, _, s in rows}
        self.assertTrue(all(s == "PASS" for (e, _), s in status.items() if e == "vosk"))
        self.assertEqual("FAIL", status[("whisper-base", "rtf_max")])
        self.assertEqual("PASS", status[("whisper-base", "translate_p95_ms")])

    def test_checkWithoutData_isSkippedNotFailed(self):
        rows = validate_perf.check({"vosk": {"STARTUP": [], "CAPTION": [], "WAV_DONE": []}}, self.budgets)
        self.assertTrue(rows)
        self.assertTrue(all(r[4] == "SKIP" for r in rows))


class ExitCodeTest(unittest.TestCase):
    def run_main(self, *args):
        with redirect_stdout(io.StringIO()):
            return validate_perf.main([str(a) for a in args])

    def test_overBudget_exits1(self):
        self.assertEqual(1, self.run_main(SAMPLE, "--budgets", BUDGETS))

    def test_withinBudget_exits0(self):
        with tempfile.TemporaryDirectory() as d:
            lenient = Path(d) / "budgets.json"
            lenient.write_text(json.dumps({"default": {"rtf_max": 2.0, "translate_p95_ms": 1000}}))
            self.assertEqual(0, self.run_main(SAMPLE, "--budgets", lenient))

    def test_noMetrics_exits2(self):
        with tempfile.TemporaryDirectory() as d:
            empty = Path(d) / "empty.txt"
            empty.write_text("nothing here\n")
            self.assertEqual(2, self.run_main(empty, "--budgets", BUDGETS))


if __name__ == "__main__":
    unittest.main()
