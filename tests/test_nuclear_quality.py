"""Boundary cases that can otherwise make exported results or comparisons misleading."""

import contextlib
import importlib.util
import io
import itertools
import json
import math
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).resolve().parents[1] / "tools" / "nuclear_quality.py"
SPEC = importlib.util.spec_from_file_location("nuclear_quality", SOURCE)
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)


def export(text="Привет, котики!", start=0, end=1000, speaker="speaker_1"):
    return {"transcript": [{"speaker_id": speaker, "start_ms": start, "end_ms": end, "text": text}]}


class ExportTests(unittest.TestCase):
    def test_official_example(self):
        self.assertTrue(quality.validate_transcript(export())["format_valid"])

    def test_wrong_root_and_missing_array(self):
        for value in ([], None, "text", {}, {"transcript": None}, {"transcript": {}}):
            with self.subTest(value=value):
                self.assertFalse(quality.validate_transcript(value)["format_valid"])

    def test_missing_fields(self):
        for field in ("speaker_id", "start_ms", "end_ms", "text"):
            data = export()
            del data["transcript"][0][field]
            self.assertFalse(quality.validate_transcript(data)["format_valid"])

    def test_integers_are_not_booleans_or_floats(self):
        for bad in (True, False, 1000.0, "1000", None):
            with self.subTest(bad=bad):
                self.assertFalse(quality.validate_transcript(export(end=bad))["format_valid"])

    def test_negative_and_reversed_times(self):
        for start, end in ((-1, 10), (0, -1), (2000, 1000)):
            self.assertFalse(quality.validate_transcript(export(start=start, end=end))["format_valid"])

    def test_overlapping_speakers_are_valid(self):
        data = export(start=0, end=2000)
        data["transcript"] += export(start=500, end=1500, speaker="Б")["transcript"]
        report = quality.validate_transcript(data)
        self.assertTrue(report["format_valid"])
        self.assertEqual(report["speaker_count"], 2)
        self.assertEqual(report["warnings"], [])

    def test_unspecified_constraints_are_advisory(self):
        for data in ({"transcript": []}, export(text=""), export(speaker=""), export(end=0)):
            report = quality.validate_transcript(data)
            self.assertTrue(report["format_valid"])
            self.assertTrue(report["warnings"])

    def test_duration_and_order_warnings(self):
        data = export(start=200, end=1000)
        data["transcript"] += export(start=100, end=1200)["transcript"]
        report = quality.validate_transcript(data, audio_seconds=1)
        self.assertTrue(report["format_valid"])
        self.assertTrue(any("order" in issue for issue in report["warnings"]))
        self.assertTrue(any("duration" in issue for issue in report["warnings"]))

    def test_additional_keys_are_not_rejected(self):
        data = export()
        data["summary"] = "Пример"
        data["transcript"][0]["confidence"] = 0.9
        self.assertTrue(quality.validate_transcript(data)["format_valid"])

    def test_non_string_fields_and_non_object_utterance(self):
        self.assertFalse(quality.validate_transcript(export(text=42))["format_valid"])
        self.assertFalse(quality.validate_transcript(export(speaker=1))["format_valid"])
        self.assertFalse(quality.validate_transcript({"transcript": [None]})["format_valid"])


class MetricTests(unittest.TestCase):
    def test_exact_match(self):
        result = quality.edit_counts(["a", "b"], ["a", "b"])
        self.assertEqual(result["wer"], 0)
        self.assertEqual(result["edits"], 0)

    def test_each_edit_type(self):
        for ref, hyp, key in ((["a"], ["b"], "substitutions"), (["a"], [], "deletions"), (["a"], ["a", "b"], "insertions")):
            result = quality.edit_counts(ref, hyp)
            self.assertEqual(result[key], 1)
            self.assertEqual(result["wer"], 1)

    def test_insertions_can_exceed_one_hundred_percent(self):
        self.assertEqual(quality.edit_counts(["a"], ["a", "b", "c"])["wer_percent"], 200)

    def test_empty_reference_is_undefined(self):
        for hyp in ([], ["a"]):
            result = quality.edit_counts([], hyp)
            self.assertIsNone(result["wer"])
            self.assertEqual(result["insertions"], len(hyp))

    def test_local_normalization(self):
        self.assertEqual(quality.words("Ёжик — КОТ, из-за окна!"), ["ежик", "кот", "из", "за", "окна"])
        self.assertEqual(quality.words("Е\u0308жик"), ["ежик"])
        self.assertNotEqual(quality.words("21"), quality.words("двадцать один"))

    def test_exact_policy_preserves_case_and_punctuation(self):
        self.assertEqual(quality.words("Кот!  кот", "exact"), ["Кот!", "кот"])

    def test_distance_against_independent_short_sequence_oracle(self):
        # Full scalar matrix oracle, deliberately separate from the linear-memory counter.
        def oracle(a, b):
            matrix = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
            for i in range(len(a) + 1):
                matrix[i][0] = i
            for j in range(len(b) + 1):
                matrix[0][j] = j
            for i in range(1, len(a) + 1):
                for j in range(1, len(b) + 1):
                    matrix[i][j] = min(matrix[i-1][j]+1, matrix[i][j-1]+1, matrix[i-1][j-1]+(a[i-1] != b[j-1]))
            return matrix[-1][-1]
        sequences = [seq for length in range(4) for seq in itertools.product(("a", "b"), repeat=length)]
        for ref, hyp in itertools.product(sequences, repeat=2):
            result = quality.edit_counts(ref, hyp)
            self.assertEqual(result["edits"], oracle(ref, hyp))
            self.assertEqual(result["edits"], result["substitutions"] + result["deletions"] + result["insertions"])
            self.assertEqual(len(hyp), len(ref) - result["deletions"] + result["insertions"])


class FileAndCliTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.hyp = self.root / "hyp.json"
        self.hyp.write_text(json.dumps(export("Котики обсуждают новый проект."), ensure_ascii=False), encoding="utf-8")
        self.ref = self.root / "ref.txt"
        self.ref.write_text("Котики обсуждают важный проект.", encoding="utf-8")

    def tearDown(self):
        self.directory.cleanup()

    def test_real_files_wer_and_supplied_rtf(self):
        report = quality.score_files(self.ref, self.hyp, audio_seconds=3, elapsed_seconds=1.5, device="fixture", timing_scope="synthetic")
        self.assertEqual(report["wer_percent"], 25)
        self.assertEqual(report["rtf"], 0.5)
        self.assertEqual(report["timing_source"], "caller_supplied")

    def test_no_timing_does_not_fabricate_rtf(self):
        self.assertIsNone(quality.score_files(self.ref, self.hyp)["rtf"])

    def test_reference_json_and_utf8_bom(self):
        self.ref = self.root / "ref.json"
        self.ref.write_text(json.dumps(export("Котики обсуждают важный проект."), ensure_ascii=False), encoding="utf-8-sig")
        self.assertEqual(quality.score_files(self.ref, self.hyp)["substitutions"], 1)

    def test_invalid_timing_inputs(self):
        for duration, elapsed in ((None, 1), (1, None), (0, 1), (-1, 1), (1, -1), (math.nan, 1), (1, math.inf)):
            with self.subTest(duration=duration, elapsed=elapsed):
                with self.assertRaises(quality.InputError):
                    quality.score_files(self.ref, self.hyp, audio_seconds=duration, elapsed_seconds=elapsed)

    def test_duplicate_keys_and_nonstandard_json(self):
        for text in ('{"transcript": [], "transcript": []}', '{"transcript": [], "x": NaN}', '{"transcript": [], "x": Infinity}', '{"transcript": [], "x": 1e999}', '{'):
            self.hyp.write_text(text, encoding="utf-8")
            with self.assertRaises(quality.InputError):
                quality.read_json(self.hyp)

    def test_invalid_hypothesis_does_not_receive_a_score(self):
        self.hyp.write_text('{"transcript": [{"text": "text"}]}', encoding="utf-8")
        with self.assertRaises(quality.InputError):
            quality.score_files(self.ref, self.hyp)

    def test_cli_writes_readable_report(self):
        output = self.root / "reports" / "score.json"
        with contextlib.redirect_stdout(io.StringIO()):
            code = quality.main(["score", "--reference", str(self.ref), "--hypothesis", str(self.hyp), "--output", str(output)])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["wer_percent"], 25)

    def test_cli_invalid_file_exit_code(self):
        self.hyp.write_text("{}", encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(quality.main(["validate", str(self.hyp)]), 2)

    def test_cli_does_not_overwrite_an_input(self):
        original = self.hyp.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(quality.main(["validate", str(self.hyp), "--output", str(self.hyp)]), 2)
        self.assertEqual(self.hyp.read_bytes(), original)

    def test_cli_strict_warnings_exit_code(self):
        self.hyp.write_text('{"transcript": []}', encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(quality.main(["validate", str(self.hyp)]), 0)
            self.assertEqual(quality.main(["validate", str(self.hyp), "--fail-on-warnings"]), 1)


class AndroidBenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.ref = self.root / "reference.txt"
        self.ref.write_text("Котики обсуждают важный проект.", encoding="utf-8")
        self.bench = self.root / "bench.json"
        self.data = {
            "words": [
                {"text": "Котики", "start_ms": 0, "end_ms": 700},
                {"text": "обсуждают", "start_ms": 700, "end_ms": 1400},
                {"text": "новый", "start_ms": 1600, "end_ms": 2200},
                {"text": "проект.", "start_ms": 2200, "end_ms": 3000},
            ],
            "audio_seconds": 3,
            "processing_ms": 1500,
            "timings_ms": {"decode": 200, "load": 300, "asr": 1000},
            "rtf": 0.5,
            "threads": 4,
            "device": "synthetic-fixture",
            "peak_rss_mb": {"decode": 100, "load": 200, "asr": 250},
        }
        self.write()

    def tearDown(self):
        self.directory.cleanup()

    def write(self):
        self.bench.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")

    def test_role_2_format_without_fake_speakers(self):
        report = quality.score_benchmark(self.ref, self.bench)
        self.assertEqual(report["wer_percent"], 25)
        self.assertEqual(report["rtf"], 0.5)
        self.assertEqual(report["timing_source"], "benchmark_file")
        self.assertEqual(report["device"], "synthetic-fixture")
        self.assertEqual(report["warnings"], [])
        self.assertNotIn("transcript", report)
        self.assertNotIn("speaker_count", report)

    def test_unreliable_recorded_rtf_is_recomputed(self):
        self.data["rtf"] = 99
        self.write()
        report = quality.score_benchmark(self.ref, self.bench)
        self.assertEqual(report["rtf"], 0.5)
        self.assertTrue(any("rtf" in item for item in report["warnings"]))

    def test_scope_mismatch_is_visible(self):
        self.data["timings_ms"]["asr"] = 700
        self.write()
        self.assertTrue(any("scope" in item for item in quality.score_benchmark(self.ref, self.bench)["warnings"]))

    def test_invalid_duration_and_processing_time(self):
        for field, bad in (("audio_seconds", 0), ("audio_seconds", True), ("audio_seconds", "3"), ("processing_ms", -1), ("processing_ms", True), ("processing_ms", 1500.0)):
            with self.subTest(field=field, bad=bad):
                previous = self.data[field]
                self.data[field] = bad
                self.write()
                with self.assertRaises(quality.InputError):
                    quality.score_benchmark(self.ref, self.bench)
                self.data[field] = previous

    def test_invalid_words_are_not_silently_dropped(self):
        for bad in (None, {"text": None}, {"text": "кот"}, {"text": "кот", "start_ms": 0, "end_ms": True}, {"text": "кот", "start_ms": 2, "end_ms": 1}):
            with self.subTest(word=bad):
                self.data["words"] = [bad]
                self.write()
                with self.assertRaises(quality.InputError):
                    quality.score_benchmark(self.ref, self.bench)

    def test_empty_word_array_keeps_deletion_errors(self):
        self.data["words"] = []
        self.write()
        report = quality.score_benchmark(self.ref, self.bench)
        self.assertEqual(report["deletions"], 4)
        self.assertEqual(report["wer_percent"], 100)

    def test_wrong_benchmark_root(self):
        for data in ([], {}, {"words": {}}, {"transcript": []}):
            self.data = data
            self.write()
            with self.assertRaises(quality.InputError):
                quality.score_benchmark(self.ref, self.bench)

    def test_cli_android_benchmark(self):
        output = self.root / "report.json"
        with contextlib.redirect_stdout(io.StringIO()):
            code = quality.main(["score-bench", "--reference", str(self.ref), "--benchmark", str(self.bench), "--output", str(output)])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["wer_percent"], 25)

    def test_benchmark_cannot_overwrite_itself(self):
        original = self.bench.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()):
            code = quality.main(["score-bench", "--reference", str(self.ref), "--benchmark", str(self.bench), "--output", str(self.bench)])
        self.assertEqual(code, 2)
        self.assertEqual(self.bench.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
