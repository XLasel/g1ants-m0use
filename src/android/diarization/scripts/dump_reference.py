#!/usr/bin/env python3
"""Снимает эталон диаризации с Python-прототипа, чтобы сверять с ним Kotlin-модуль по шагам.

Для каждой записи пишет в <out>/<имя>/:
  audio.f32               звук 16 кГц, моно, float32 little-endian
  mel.f32                 лог-мел признаки [кадры, 128]
  embeds.f32              выход embed.onnx [кадры/8, 512]
  probs.f32               итоговые вероятности [кадры, 8], шаг 10 мс
  cache_NN.f32 / .json    состояние кэша спикеров после чанка NN (эмбеддинги, вероятности, FIFO)
  segments.json           speech_segments, plan_chunks
  words_in.json           (если задан --words) слова ASR без спикеров
  utterances.json         (если задан --words) результат assign_speakers + build_utterances + merge_adjacent
  meta.json               размеры массивов и параметры

Запуск (нужны numpy и onnxruntime):
  python dump_reference.py --original ../../../../voicerec-original --out ../../../../reference \\
      ru_3spk=../../../../voicerec-poc/testset/russian/ru_3spk.wav \\
      dialog_10min=../../../../voicerec-poc/samples/dialog_10min.wav:../../../../voicerec-original/results/onnx/dialog_10min.int8.json
"""
from __future__ import annotations

import argparse
import json
import sys
import types
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np


@dataclass
class Word:
    text: str
    start: float
    end: float
    speaker: str | None = None
    confidence: float | None = None
    probs: np.ndarray | None = field(default=None, repr=False)


def install_stubs(original: Path) -> None:
    """voicerec.models тянет PyTorch, а voicerec.audio — PyAV. Логике segmentation и merge нужны только константы."""
    sys.path[:0] = [str(original), str(original / "src")]
    models = types.ModuleType("voicerec.models")
    models.Word, models.DIAR_FRAME_SEC, models.NUM_SPEAKERS = Word, 0.01, 8
    audio = types.ModuleType("voicerec.audio")
    audio.SAMPLE_RATE = 16000
    sys.modules["voicerec.models"] = models
    sys.modules["voicerec.audio"] = audio


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        if w.getframerate() != 16000 or w.getsampwidth() != 2:
            raise SystemExit(f"{path}: нужен WAV 16 кГц, 16 бит")
        data = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").reshape(-1, w.getnchannels())
    return (data.mean(axis=1) / 32768.0).astype(np.float32)


def save(path: Path, array: np.ndarray) -> list[int]:
    np.ascontiguousarray(array, dtype="<f4").tofile(path)
    return list(array.shape)


def dump(name: str, wav: Path, words_json: Path | None, out: Path, threads: int) -> None:
    import onnx_diarizer as od
    from voicerec.merge import SpeakerNamer, assign_speakers, build_utterances, merge_adjacent
    from voicerec.segmentation import plan_chunks, speech_activity, speech_segments

    target = out / name
    target.mkdir(parents=True, exist_ok=True)
    audio = read_wav(wav)
    meta: dict = {"name": name, "source": str(wav), "samples": len(audio), "arrays": {}}
    meta["arrays"]["audio"] = save(target / "audio.f32", audio)

    diarizer = od.OnnxDiarizer("int8", threads)
    mel = od.log_mel(audio, diarizer.mel_filters)
    meta["arrays"]["mel"] = save(target / "mel.f32", mel)
    embeds = diarizer.embed.run(None, {"features": mel[None]})[0][0]
    meta["arrays"]["embeds"] = save(target / "embeds.f32", embeds)

    # Запоминаем состояние кэша после каждого чанка, не меняя логику прототипа.
    caches = []
    original_update = od.SpeakerCache.update

    def recording_update(self, step_embeds, probs, num_chunk):
        original_update(self, step_embeds, probs, num_chunk)
        n = len(caches)
        state = np.concatenate([self.embeds, self.fifo]) if len(self.embeds) + len(self.fifo) else np.zeros((0, 512))
        save(target / f"cache_{n:02d}.f32", state)
        save(target / f"cache_{n:02d}_probs.f32", self.probs)
        info = {"chunk": n, "cache": len(self.embeds), "fifo": len(self.fifo),
                "probs": len(self.probs), "compressed": self.compressed}
        (target / f"cache_{n:02d}.json").write_text(json.dumps(info))
        caches.append(info)

    od.SpeakerCache.update = recording_update
    try:
        probs = diarizer.diarize(audio)
    finally:
        od.SpeakerCache.update = original_update
    meta["arrays"]["probs"] = save(target / "probs.f32", probs)
    meta["chunks"] = caches

    activity = speech_activity(probs)
    segments = speech_segments(activity)
    chunks = plan_chunks(segments, audio, max_chunk=20.0)
    (target / "segments.json").write_text(json.dumps({"segments": segments, "chunks": chunks}, indent=1))

    if words_json:
        source = json.loads(words_json.read_text(encoding="utf-8"))
        raw = [w for u in source["utterances"] for w in u["words"]]
        words = [Word(text=w["text"], start=w["start"], end=w["end"]) for w in raw]
        (target / "words_in.json").write_text(
            json.dumps([{"text": w.text, "start": w.start, "end": w.end} for w in words], ensure_ascii=False, indent=0),
            encoding="utf-8")
        assign_speakers(words, probs, SpeakerNamer(template="{n}"))
        utterances = merge_adjacent(build_utterances(words))
        (target / "utterances.json").write_text(json.dumps([
            {"speaker": int(u.speaker), "start": u.start, "end": u.end,
             "words": [{"text": w.text, "start": w.start, "end": w.end, "speaker": int(w.speaker)} for w in u.words]}
            for u in utterances], ensure_ascii=False, indent=0), encoding="utf-8")
        meta["utterances"] = len(utterances)

    meta["speakers"] = int((probs >= 0.5).any(axis=0).sum())
    (target / "meta.json").write_text(json.dumps(meta, indent=1))
    print(f"{name}: {len(audio) / 16000:.1f} с, чанков {len(caches)}, "
          f"сжатий кэша {sum(c['compressed'] for c in caches)}, спикеров {meta['speakers']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--original", type=Path, required=True, help="папка voicerec-original")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("items", nargs="+", help="имя=запись.wav[:слова.json]")
    args = parser.parse_args()
    install_stubs(args.original.resolve())
    for item in args.items:
        name, rest = item.split("=", 1)
        wav, _, words = rest.partition(".wav:")
        wav = Path(wav if not words else wav + ".wav")
        dump(name, wav, Path(words) if words else None, args.out, args.threads)


if __name__ == "__main__":
    main()
