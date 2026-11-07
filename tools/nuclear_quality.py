#!/usr/bin/env python3
"""Offline checks for Nuclear IT Hack transcript exports. Python 3.10+, stdlib only.

WER normalization and timing scope must be agreed with the organizers before
these local diagnostic numbers can be treated as official scores.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import unicodedata
from pathlib import Path
from typing import Any, Sequence


class InputError(ValueError):
    """An input cannot be read or does not satisfy the export contract."""


def _unique_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise InputError(f"Duplicate JSON key: {key!r}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise InputError(f"Non-standard JSON number: {value}")


def _finite_json_float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise InputError(f"JSON number is outside the finite float range: {value}")
    return result


def read_json(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8-sig"),
            object_pairs_hook=_unique_keys,
            parse_constant=_reject_constant,
            parse_float=_finite_json_float,
        )
    except (OSError, UnicodeError, ValueError, RecursionError) as exc:
        raise InputError(f"Cannot read JSON {path}: {exc}") from exc


def validate_transcript(data: Any, audio_seconds: float | None = None) -> dict[str, Any]:
    """Check types plus basic time consistency; keep advisory issues separate.

    Overlap and arbitrary nonempty speaker identifiers are allowed. Neither
    the official slide nor this validator requires the literal 'speaker_N'.
    """
    errors: list[str] = []
    warnings: list[str] = []
    report: dict[str, Any] = {
        "format_valid": False,
        "utterance_count": 0,
        "speaker_count": 0,
        "errors": errors,
        "warnings": warnings,
    }
    if not isinstance(data, dict):
        errors.append("Root must be a JSON object.")
        return report
    if not isinstance(data.get("transcript"), list):
        errors.append("transcript must be an array.")
        return report
    extra = set(data) - {"transcript"}
    if extra:
        warnings.append(f"Additional root keys are not described by the official format: {sorted(extra)}")
    utterances = data["transcript"]
    report["utterance_count"] = len(utterances)
    if not utterances:
        warnings.append("Transcript is empty. This can be correct for silence, but verify speech inputs.")
    speakers: set[str] = set()
    previous_start: int | None = None
    required = {"speaker_id", "start_ms", "end_ms", "text"}
    for i, utterance in enumerate(utterances):
        location = f"transcript[{i}]"
        if not isinstance(utterance, dict):
            errors.append(f"{location} must be an object.")
            continue
        for key in sorted(required - set(utterance)):
            errors.append(f"{location}.{key} is required.")
        additional = set(utterance) - required
        if additional:
            warnings.append(f"{location} has additional keys: {sorted(additional)}")
        for key in ("speaker_id", "text"):
            if key not in utterance:
                continue
            if not isinstance(utterance[key], str):
                errors.append(f"{location}.{key} must be a string.")
            elif not utterance[key].strip():
                warnings.append(f"{location}.{key} is blank.")
        speaker = utterance.get("speaker_id")
        if isinstance(speaker, str) and speaker.strip():
            speakers.add(speaker)
        times_valid = True
        for key in ("start_ms", "end_ms"):
            value = utterance.get(key)
            if key not in utterance:
                times_valid = False
            elif type(value) is not int:  # bool is an int subclass in Python.
                errors.append(f"{location}.{key} must be an integer, not a float or boolean.")
                times_valid = False
            elif value < 0:
                errors.append(f"{location}.{key} must be non-negative.")
                times_valid = False
        if not times_valid:
            continue
        start, end = utterance["start_ms"], utterance["end_ms"]
        if end < start:
            errors.append(f"{location}.end_ms precedes start_ms.")
        elif end == start:
            warnings.append(f"{location} has zero duration.")
        if previous_start is not None and start < previous_start:
            warnings.append(f"{location} is out of start-time order; WER uses the exported array order.")
        previous_start = start
        if audio_seconds is not None and end > audio_seconds * 1000 + 1:
            warnings.append(f"{location}.end_ms exceeds the supplied audio duration.")
    report["speaker_count"] = len(speakers)
    report["format_valid"] = not errors
    return report


def words(text: str, normalization: str = "ru-basic") -> list[str]:
    """Tokenize using an explicitly named, reproducible local policy."""
    if normalization == "exact":
        return text.split()
    if normalization != "ru-basic":
        raise InputError(f"Unknown normalization: {normalization}")
    text = unicodedata.normalize("NFC", text).casefold().replace("ё", "е")
    text = "".join(" " if unicodedata.category(char).startswith("P") else char for char in text)
    return text.split()


def edit_counts(reference: Sequence[str], hypothesis: Sequence[str]) -> dict[str, Any]:
    """Exact Levenshtein WER with O(len(hypothesis)) memory.

    Ties prefer diagonal, deletion, insertion in that order. Different valid
    alignments can change S/D/I individually, but not the total edit distance.
    An empty reference has undefined WER: report null, never a fabricated 0%.
    """
    # Cells are (distance, substitutions, deletions, insertions).
    previous = [(j, 0, 0, j) for j in range(len(hypothesis) + 1)]
    for i, ref_word in enumerate(reference, 1):
        current = [(i, 0, i, 0)]
        for j, hyp_word in enumerate(hypothesis, 1):
            diagonal = previous[j - 1]
            if ref_word != hyp_word:
                diagonal = (diagonal[0] + 1, diagonal[1] + 1, diagonal[2], diagonal[3])
            above, left = previous[j], current[j - 1]
            deletion = (above[0] + 1, above[1], above[2] + 1, above[3])
            insertion = (left[0] + 1, left[1], left[2], left[3] + 1)
            current.append(min((diagonal, deletion, insertion), key=lambda cell: cell[0]))
        previous = current
    distance, substitutions, deletions, insertions = previous[-1]
    count = len(reference)
    rate = distance / count if count else None
    return {
        "reference_words": count,
        "hypothesis_words": len(hypothesis),
        "substitutions": substitutions,
        "deletions": deletions,
        "insertions": insertions,
        "edits": distance,
        "wer": rate,
        "wer_percent": rate * 100 if rate is not None else None,
    }


def transcript_text(data: dict[str, Any]) -> str:
    return " ".join(item["text"] for item in data["transcript"])


def _validated_json(path: Path, audio_seconds: float | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
    data = read_json(path)
    validation = validate_transcript(data, audio_seconds)
    if not validation["format_valid"]:
        raise InputError(f"Invalid transcript {path}: " + "; ".join(validation["errors"]))
    return data, validation


def _reference_text(path: Path, audio_seconds: float | None = None) -> tuple[str, list[str]]:
    if path.suffix.lower() == ".json":
        data, validation = _validated_json(path, audio_seconds)
        return transcript_text(data), validation["warnings"]
    try:
        return path.read_text(encoding="utf-8-sig"), []
    except (OSError, UnicodeError) as exc:
        raise InputError(f"Cannot read reference {path}: {exc}") from exc


def score_files(
    reference: Path,
    hypothesis: Path,
    normalization: str = "ru-basic",
    audio_seconds: float | None = None,
    elapsed_seconds: float | None = None,
    device: str | None = None,
    timing_scope: str | None = None,
) -> dict[str, Any]:
    if (audio_seconds is None) != (elapsed_seconds is None):
        raise InputError("Supply both audio_seconds and elapsed_seconds, or neither.")
    if audio_seconds is not None and (not math.isfinite(audio_seconds) or audio_seconds <= 0):
        raise InputError("audio_seconds must be finite and greater than zero.")
    if elapsed_seconds is not None and (not math.isfinite(elapsed_seconds) or elapsed_seconds < 0):
        raise InputError("elapsed_seconds must be finite and non-negative.")
    hyp, hyp_validation = _validated_json(hypothesis, audio_seconds)
    reference_text, reference_warnings = _reference_text(reference, audio_seconds)
    counts = edit_counts(words(reference_text, normalization), words(transcript_text(hyp), normalization))
    notes = [
        "Local diagnostic; organizers' normalization and timing scope are not specified in the slides.",
        "WER ignores speaker labels and timing. It does not measure diarization or timestamp accuracy.",
    ]
    if not counts["reference_words"]:
        notes.append("Reference has zero words after normalization; WER is undefined (null).")
    if elapsed_seconds is not None:
        notes.append("Timing values were supplied by the caller; this tool did not measure device inference.")
        if not device or not timing_scope:
            notes.append("Specify device and timing_scope before comparing performance measurements.")
    return {
        "reference": str(reference),
        "hypothesis": str(hypothesis),
        "normalization": normalization,
        **counts,
        "audio_seconds": audio_seconds,
        "elapsed_seconds": elapsed_seconds,
        "rtf": elapsed_seconds / audio_seconds if elapsed_seconds is not None else None,
        "device": device,
        "timing_scope": timing_scope,
        "timing_source": "caller_supplied" if elapsed_seconds is not None else None,
        "hypothesis_validation": hyp_validation,
        "reference_warnings": reference_warnings,
        "notes": notes,
    }


def score_benchmark(reference: Path, benchmark: Path, normalization: str = "ru-basic") -> dict[str, Any]:
    """Читает старый ASR-бенч и новый pipeline-бенч, разделяя границы замера."""
    data = read_json(benchmark)
    if not isinstance(data, dict) or not isinstance(data.get("words"), list):
        raise InputError("Android benchmark must be an object containing a words array.")
    if "failed" in data or data.get("success") is False:
        raise InputError("Benchmark processing failed; partial words are not a completed-run score.")
    duration, processing_ms = data.get("audio_seconds"), data.get("processing_ms")
    if type(duration) not in (int, float) or not math.isfinite(duration) or duration <= 0:
        raise InputError("Benchmark audio_seconds must be a finite positive number.")
    if type(processing_ms) is not int or processing_ms < 0:
        raise InputError("Benchmark processing_ms must be a non-negative integer.")
    pipeline_format = "transcription_ms" in data
    transcription_ms = data.get("transcription_ms", processing_ms)
    if type(transcription_ms) is not int or not 0 <= transcription_ms <= processing_ms:
        raise InputError("Benchmark transcription_ms must be an integer between zero and processing_ms.")
    warnings: list[str] = []
    text: list[str] = []
    previous_start: int | None = None
    for i, word in enumerate(data["words"]):
        location = f"words[{i}]"
        if not isinstance(word, dict) or not isinstance(word.get("text"), str):
            raise InputError(f"{location}.text must be a string.")
        start, end = word.get("start_ms"), word.get("end_ms")
        if type(start) is not int or type(end) is not int:
            raise InputError(f"{location} needs integer start_ms and end_ms.")
        if start < 0 or end < start:
            raise InputError(f"{location} has invalid timestamps.")
        if end > duration * 1000 + 1:
            warnings.append(f"{location}.end_ms exceeds audio duration.")
        if previous_start is not None and start < previous_start:
            warnings.append(f"{location} is out of start-time order; WER uses array order.")
        previous_start = start
        text.append(word["text"])
    reference_text, reference_warnings = _reference_text(reference, duration)
    counts = edit_counts(words(reference_text, normalization), words(" ".join(text), normalization))
    elapsed = transcription_ms / 1000.0
    rtf = elapsed / duration
    reported_rtf = data.get("rtf")
    if reported_rtf is not None and (
        type(reported_rtf) not in (int, float)
        or not math.isfinite(reported_rtf)
        or not math.isclose(reported_rtf, rtf, rel_tol=1e-6, abs_tol=1e-6)
    ):
        timing_field = "transcription_ms" if pipeline_format else "processing_ms"
        warnings.append(f"Recorded rtf differs from {timing_field} / audio_seconds; using recomputed RTF.")
    stages = data.get("timings_ms")
    if not pipeline_format and isinstance(stages, dict) and all(type(stages.get(key)) is int and stages[key] >= 0 for key in ("decode", "load", "asr")):
        if sum(stages[key] for key in ("decode", "load", "asr")) != processing_ms:
            warnings.append("processing_ms differs from decode + load + asr; check benchmark timing scope.")
    notes = [
        "Local diagnostic; the reference must be manually checked before interpreting this as ASR accuracy.",
        "WER uses the words array, not the required transcript export; speaker attribution is not scored.",
        "Timing was read from the benchmark file, not independently measured by this tool.",
        ("RTF stops at Done; full_rtf uses processing_ms including any summary attempt; see full_timing_scope for export inclusion."
         if pipeline_format else
         "Legacy role-2 timing excludes diarization, LLM, model installation, envelope and JSON export."),
        "WER normalization and the final RTF timing scope must be confirmed with the organizers.",
    ]
    if not counts["reference_words"]:
        notes.append("Reference has zero words after normalization; WER is undefined (null).")
    return {
        "reference": str(reference),
        "hypothesis": str(benchmark),
        "input_format": "android_pipeline_words" if pipeline_format else "android_speechbench_words",
        "normalization": normalization,
        **counts,
        "audio_seconds": duration,
        "elapsed_seconds": elapsed,
        "rtf": rtf,
        "timing_source": "benchmark_file",
        "timing_scope": "decode+diarize+recognize+merge until Done" if pipeline_format else "decode+load+asr (role-2 benchmark)",
        "full_elapsed_seconds": processing_ms / 1000.0 if pipeline_format else None,
        "full_rtf": processing_ms / 1000.0 / duration if pipeline_format else None,
        "full_timing_scope": (
            "until pipeline flow completion before export, including any summary attempt"
            if data.get("processing_scope") == "until_flow_completion_before_export"
            else "producer-defined processing_ms; older pipeline reports may include export"
        ) if pipeline_format else None,
        "summary_status": data.get("summary_status") if pipeline_format else None,
        "device": data.get("device"),
        "threads": data.get("threads"),
        "peak_rss_mb": data.get("peak_rss_mb"),
        "warnings": warnings,
        "reference_warnings": reference_warnings,
        "notes": notes,
    }


def _number(value: str) -> float:
    try:
        number = float(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Expected a finite number.") from exc
    if not math.isfinite(number):
        raise argparse.ArgumentTypeError("Expected a finite number.")
    return number


def _emit(report: dict[str, Any], output: Path | None) -> None:
    serialized = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if output is not None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(serialized, encoding="utf-8")
    print(serialized, end="")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate", help="Validate one official-format transcript JSON.")
    validate.add_argument("path", type=Path)
    validate.add_argument("--audio-seconds", type=_number)
    validate.add_argument("--fail-on-warnings", action="store_true")
    validate.add_argument("--output", type=Path)
    score = commands.add_parser("score", help="Compute local WER; optionally compute RTF from supplied timings.")
    score.add_argument("--reference", type=Path, required=True, help="UTF-8 .txt or official-format .json.")
    score.add_argument("--hypothesis", type=Path, required=True)
    score.add_argument("--normalization", choices=("ru-basic", "exact"), default="ru-basic")
    score.add_argument("--audio-seconds", type=_number)
    score.add_argument("--elapsed-seconds", type=_number)
    score.add_argument("--device")
    score.add_argument("--timing-scope")
    score.add_argument("--output", type=Path)
    bench = commands.add_parser("score-bench", help="Score Android ASR/pipeline benchmark JSON; separate transcript and full RTF.")
    bench.add_argument("--reference", type=Path, required=True)
    bench.add_argument("--benchmark", type=Path, required=True)
    bench.add_argument("--normalization", choices=("ru-basic", "exact"), default="ru-basic")
    bench.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            inputs = [args.path]
        elif args.command == "score-bench":
            inputs = [args.reference, args.benchmark]
        else:
            inputs = [args.reference, args.hypothesis]
        if args.output is not None:
            for source in inputs:
                same_path = args.output.resolve() == source.resolve()
                same_file = args.output.exists() and source.exists() and args.output.samefile(source)
                if same_path or same_file:
                    raise InputError("Output report must not overwrite a source transcript or reference.")
        if args.command == "validate":
            if args.audio_seconds is not None and args.audio_seconds <= 0:
                raise InputError("audio_seconds must be greater than zero.")
            report = validate_transcript(read_json(args.path), args.audio_seconds)
            _emit(report, args.output)
            if not report["format_valid"]:
                return 2
            return 1 if args.fail_on_warnings and report["warnings"] else 0
        if args.command == "score-bench":
            _emit(score_benchmark(args.reference, args.benchmark, args.normalization), args.output)
            return 0
        report = score_files(
            args.reference, args.hypothesis, args.normalization,
            args.audio_seconds, args.elapsed_seconds, args.device, args.timing_scope,
        )
        _emit(report, args.output)
        return 0
    except (InputError, OSError) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
