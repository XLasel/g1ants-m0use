#!/usr/bin/env python3
"""Один раз считает вероятности Nemotron и слова GigaAM для набора записей, чтобы перебирать пороги без моделей.

Для каждой записи пишет в <cache>/<id>.npz: probs [кадры, 8], words (текст, начало, конец), chunks,
плюс путь к эталону. Куски для ASR — как в прототипе: plan_chunks(speech_segments(...)) с порогами по умолчанию.

Набор:
  - testset/manifest.json из voicerec-poc (VoxConverse и русские диалоги, без test_2);
  - зашумлённые русские диалоги из build_noisy.py (--noisy), эталон у них общий с чистой записью.

Запуск:
  python cache_testset.py --poc D:/hackmifi/voicerec-poc --original D:/hackmifi/voicerec-original \\
      --noisy D:/hackmifi/noisy --cache D:/hackmifi/tuning/cache
Нужны numpy, onnxruntime, sherpa-onnx.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import types
import wave
from pathlib import Path

import numpy as np

SR = 16000
MAX_WORD_SECONDS = 1.0


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        assert w.getframerate() == SR and w.getsampwidth() == 2, path
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, w.getnchannels())
    return (data.mean(axis=1) / 32768.0).astype(np.float32)


def install_stubs(original: Path) -> None:
    sys.path[:0] = [str(original), str(original / "src")]
    models = types.ModuleType("voicerec.models")
    models.DIAR_FRAME_SEC, models.NUM_SPEAKERS = 0.01, 8
    audio = types.ModuleType("voicerec.audio")
    audio.SAMPLE_RATE = SR
    sys.modules["voicerec.models"] = models
    sys.modules["voicerec.audio"] = audio


def make_recognizer(poc: Path, threads: int):
    import sherpa_onnx
    asr = poc / "models" / "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(asr / "encoder.int8.onnx"), decoder=str(asr / "decoder.onnx"), joiner=str(asr / "joiner.onnx"),
        tokens=str(asr / "tokens.txt"), num_threads=threads, model_type="nemo_transducer")


def tokens_to_words(tokens, timestamps, offset, chunk_end):
    """Как tokensToWords у роли 2: новое слово с пробела, конец — начало следующего, но не дальше 1 с."""
    words = []
    for token, stamp in zip(tokens, timestamps):
        start = offset + float(stamp)
        if token.startswith((" ", "▁")) or not words:
            words.append([token.lstrip(" ▁"), start, start])
        else:
            words[-1][0] += token
    for current, following in zip(words, words[1:]):
        current[2] = max(current[1], min(following[1], current[1] + MAX_WORD_SECONDS))
    if words:
        words[-1][2] = max(words[-1][1], min(chunk_end, words[-1][1] + MAX_WORD_SECONDS))
    return [w for w in words if w[0]]


def transcribe(recognizer, audio, chunks):
    words = []
    for start, end in chunks:
        a, b = int(start * SR), int(end * SR)
        if b - a < 400:
            continue
        stream = recognizer.create_stream()
        stream.accept_waveform(SR, audio[a:b])
        recognizer.decode_stream(stream)
        result = stream.result
        words += tokens_to_words(result.tokens, result.timestamps, start, b / SR)
    return words


def items(poc: Path, noisy: Path | None) -> list[dict]:
    testset = poc / "testset"
    result = []
    for item in json.loads((testset / "manifest.json").read_text(encoding="utf-8")):
        if item["kind"] == "live":
            continue  # эталон test_2 пока черновой и сделан самим гибридом
        result.append({"id": item["id"], "kind": item["kind"], "audio": testset / item["audio"],
                       "reference": testset / item["reference"], "speakers": item["speakers"]})
    if noisy:
        clean = {i["id"]: i for i in result}
        for wav in sorted(noisy.glob("*__*.wav")):
            base, condition = wav.stem.split("__")
            result.append({**clean[base], "id": wav.stem, "kind": f"noisy_{condition}", "audio": wav})
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--poc", type=Path, required=True)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--noisy", type=Path)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    install_stubs(args.original.resolve())
    from onnx_diarizer import OnnxDiarizer
    from voicerec.segmentation import plan_chunks, speech_activity, speech_segments

    args.cache.mkdir(parents=True, exist_ok=True)
    diarizer = OnnxDiarizer("int8", args.threads)
    recognizer = make_recognizer(args.poc, args.threads)
    for item in items(args.poc, args.noisy):
        target = args.cache / f"{item['id']}.npz"
        if target.exists():
            continue
        started = time.perf_counter()
        audio = read_wav(item["audio"])
        probs = diarizer.diarize(audio)
        chunks = plan_chunks(speech_segments(speech_activity(probs)), audio, max_chunk=20.0)
        words = transcribe(recognizer, audio, chunks)
        np.savez_compressed(
            target, probs=probs, chunks=np.array(chunks, dtype=np.float64).reshape(-1, 2),
            word_text=np.array([w[0] for w in words], dtype=object),
            word_times=np.array([[w[1], w[2]] for w in words], dtype=np.float64).reshape(-1, 2),
            meta=json.dumps({k: str(v) if isinstance(v, Path) else v for k, v in item.items()}
                            | {"seconds": len(audio) / SR}))
        print(f"{item['id']}: {len(audio) / SR:.0f} с, слов {len(words)}, {time.perf_counter() - started:.1f} с",
              flush=True)


if __name__ == "__main__":
    main()
