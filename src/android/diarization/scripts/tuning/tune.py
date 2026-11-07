#!/usr/bin/env python3
"""Перебор порогов постобработки диаризации на кэше из cache_testset.py.

Логика повторяет TranscriptMerger.kt (порт merge.py), но все пороги — параметры. Метрики считаются по итоговым
репликам, то есть по тому, что попадёт в JSON задания:
  word_acc   доля слов с верным спикером (соответствие меток один к одному, при наложении годится любой говорящий)
  der        DER реплик с допуском 0,25 с; confusion — его часть «перепутан спикер»
  spk_ok     доля записей, где число спикеров в репликах совпало с эталоном

Запуск:
  python tune.py --cache D:/hackmifi/tuning/cache baseline     # метрики по умолчанию
  python tune.py --cache ... sweep                              # по одному параметру вокруг значения по умолчанию
  python tune.py --cache ... eval key=value key=value           # конкретный набор
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment

FRAME = 0.01
COLLAR = 0.25
SENTENCE_END = (".", "?", "!", "…")

DEFAULTS = {
    # Спикер слова
    "narrow_min": 0.2,      # если лучший спикер на интервале слова слабее, смотрим шире
    "wide_frames": 50,      # насколько шире, кадров по 10 мс в каждую сторону
    "skip_min": 0.05,       # слабее этого — слово без спикера, берёт спикера соседа
    "median": 0,            # медианный фильтр вероятностей по времени, кадров (0 — выкл.)
    # Сглаживание одиночных слов
    "smooth": 1,
    "smooth_gap": 0.4,
    "smooth_score": 0.3,
    # Большинство в предложении
    "majority": 1,
    "pause": 0.5,
    "min_share": 0.6,
    "conf_own": 0.6,
    "conf_major": 0.25,
    # Спикеры, у которых почти нет слов, отдаются второму по вероятности спикеру
    "min_speaker_words": 0,
    # Реплики
    "utt_gap": 2.0,
}


@dataclass
class Item:
    id: str
    kind: str
    group: str
    probs: np.ndarray
    texts: list[str]
    times: np.ndarray
    reference: list[dict]
    speakers: int
    frames: int


def read_reference(path: Path) -> list[dict]:
    if path.suffix == ".rttm":
        turns = []
        for line in path.read_text().splitlines():
            p = line.split()
            if p and p[0] == "SPEAKER":
                turns.append({"speaker": p[7], "start": float(p[3]), "end": float(p[3]) + float(p[4])})
        return turns
    return [{"speaker": t["speaker"], "start": t["start"], "end": t["end"]}
            for t in json.loads(path.read_text(encoding="utf-8"))]


def load(cache: Path) -> list[Item]:
    items = []
    for file in sorted(cache.glob("*.npz")):
        data = np.load(file, allow_pickle=True)
        meta = json.loads(str(data["meta"]))
        kind = meta["kind"]
        group = "чистые русские" if kind == "russian" else "VoxConverse" if kind == "voxconverse" else kind.replace("noisy_", "шум ")
        items.append(Item(meta["id"], kind, group, data["probs"], list(data["word_text"]), data["word_times"],
                          read_reference(Path(meta["reference"])), meta["speakers"],
                          int(np.ceil(meta["seconds"] / FRAME))))
    return items


# --------------------------------------------------------------------------- постобработка


def median_filter(probs: np.ndarray, k: int) -> np.ndarray:
    if k <= 1:
        return probs
    pad = k // 2
    padded = np.pad(probs, ((pad, pad), (0, 0)), mode="edge")
    windows = np.lib.stride_tricks.sliding_window_view(padded, k, axis=0)
    return np.median(windows, axis=-1).astype(np.float32)


def norm(text: str) -> str:
    return re.sub(r"[^\w]+", "", text.lower().replace("ё", "е"))


def is_boundary(cur: str, cur_end: float, nxt: str, nxt_start: float, pause: float) -> bool:
    if cur.endswith(SENTENCE_END) or nxt_start - cur_end >= pause:
        return True
    return cur[-1:] in ",:;—-" and nxt[:1].isupper()


def assign(item: Item, p: dict) -> np.ndarray:
    probs = median_filter(item.probs, int(p["median"]))
    n_frames = len(probs)
    starts, ends = item.times[:, 0], item.times[:, 1]
    n = len(starts)
    idx = np.full(n, -1)
    scores = np.zeros((n, 8), dtype=np.float32)

    def window_mean(a, b):
        a, b = max(0, a), min(n_frames, b)
        return None if b <= a else probs[a:b].mean(axis=0)

    wide = int(p["wide_frames"])
    for i in range(n):
        a = int(round(starts[i] / FRAME))
        b = max(a + 1, int(round(ends[i] / FRAME)))
        score = window_mean(a, b)
        if score is None or score.max() < p["narrow_min"]:
            w = window_mean(a - wide, b + wide)
            if w is not None and (score is None or w.max() > score.max()):
                score = w
        if score is None or score.max() < p["skip_min"]:
            continue
        idx[i] = int(score.argmax())
        scores[i] = score

    known = np.flatnonzero(idx >= 0)
    if known.size == 0:
        idx[:] = 0
    else:
        for i in np.flatnonzero(idx < 0):
            idx[i] = idx[known[np.argmin(np.abs(known - i))]]

    if p["min_speaker_words"] > 0:
        # Спикер с очень малым числом слов — чаще всего ложный: отдаём его слова следующему по вероятности спикеру
        for _ in range(8):
            counts = np.bincount(idx, minlength=8)
            weak = [s for s in range(8) if 0 < counts[s] < p["min_speaker_words"]]
            if not weak:
                break
            s = min(weak, key=lambda x: counts[x])
            for i in np.flatnonzero(idx == s):
                order = np.argsort(-scores[i])
                alternatives = [o for o in order if o != s and counts[o] > 0]
                if alternatives:
                    idx[i] = alternatives[0]

    if n > 2 and p["smooth"]:
        for i in range(1, n - 1):
            prev_spk, cur, nxt = idx[i - 1], idx[i], idx[i + 1]
            if prev_spk != nxt or cur == prev_spk:
                continue
            tight = starts[i] - ends[i - 1] < p["smooth_gap"] and starts[i + 1] - ends[i] < p["smooth_gap"]
            if tight and scores[i, prev_spk] >= p["smooth_score"]:
                idx[i] = prev_spk

    if n > 2 and p["majority"]:
        texts = item.texts
        start = 0
        for i in range(n):
            if i < n - 1 and not is_boundary(texts[i], ends[i], texts[i + 1], starts[i + 1], p["pause"]):
                continue
            sent = np.arange(start, i + 1)
            start = i + 1
            speakers = idx[sent]
            if len(set(speakers.tolist())) < 2:
                continue
            weights = np.array([max(len(norm(texts[k])), 1) for k in sent], dtype=np.float32)
            totals = {int(s): float(weights[speakers == s].sum()) for s in sorted(set(speakers.tolist()))}
            best = max(totals, key=totals.get)
            if totals[best] / weights.sum() < p["min_share"]:
                continue
            for spk in totals:
                if spk == best:
                    continue
                minority = sent[speakers == spk]
                confident = scores[minority, spk].mean() >= p["conf_own"] and scores[minority, best].mean() < p["conf_major"]
                if not confident:
                    idx[minority] = best
    return idx


def utterances(item: Item, idx: np.ndarray, gap: float) -> list[tuple[int, float, float, list[int]]]:
    utts: list[list] = []
    for i in np.argsort(item.times[:, 0], kind="stable"):
        s, e = item.times[i]
        if utts and utts[-1][0] == idx[i] and s - utts[-1][2] <= gap:
            utts[-1][2] = max(utts[-1][2], e)
            utts[-1][3].append(i)
        else:
            utts.append([int(idx[i]), s, e, [i]])
    return [tuple(u) for u in utts]


# --------------------------------------------------------------------------- метрики


def matrix(turns, frames, key):
    labels = sorted({key(t) for t in turns}, key=str)
    m = np.zeros((frames, max(len(labels), 1)), dtype=bool)
    for t in turns:
        a, b = int(round(t[1] / FRAME)), int(round(t[2] / FRAME))
        m[max(a, 0):min(b, frames), labels.index(key(t))] = True
    return m


def der(reference: list[dict], utts, frames: int) -> tuple[float, float]:
    ref = matrix([(t["speaker"], t["start"], t["end"]) for t in reference], frames, lambda t: t[0])
    hyp = matrix([(u[0], u[1], u[2]) for u in utts], frames, lambda t: t[0])
    scored = np.ones(frames, dtype=bool)
    width = int(COLLAR / FRAME)
    for t in reference:
        for edge in (t["start"], t["end"]):
            c = int(round(edge / FRAME))
            scored[max(c - width, 0):min(c + width, frames)] = False
    ref, hyp = ref[scored], hyp[scored]
    overlap = ref.T.astype(np.int64) @ hyp.astype(np.int64)
    rows, cols = linear_sum_assignment(-overlap)
    correct = np.zeros(len(ref), dtype=np.int64)
    for r, c in zip(rows, cols):
        correct += ref[:, r] & hyp[:, c]
    n_ref, n_hyp = ref.sum(axis=1), hyp.sum(axis=1)
    total = max(int(n_ref.sum()), 1)
    return (float(np.maximum(n_ref, n_hyp).sum() - correct.sum()) / total,
            float((np.minimum(n_ref, n_hyp) - correct).sum()) / total)


def word_accuracy(item: Item, idx: np.ndarray) -> float:
    ref = item.reference
    labels = sorted({t["speaker"] for t in ref}, key=str)
    predicted = sorted(set(idx.tolist()))
    if not predicted:
        return 0.0
    table = np.zeros((len(predicted), len(labels)), dtype=np.int64)
    active = []
    for (s, e), spk in zip(item.times, idx):
        mid = (s + e) / 2
        cur = {t["speaker"] for t in ref if t["start"] <= mid <= t["end"]}
        if not cur:
            cur = {min(ref, key=lambda t: min(abs(mid - t["start"]), abs(mid - t["end"])))["speaker"]}
        active.append(cur)
        for label in cur:
            table[predicted.index(spk), labels.index(label)] += 1
    rows, cols = linear_sum_assignment(-table)
    mapping = {predicted[r]: labels[c] for r, c in zip(rows, cols)}
    return sum(mapping.get(int(spk)) in cur for spk, cur in zip(idx, active)) / len(idx)


def evaluate(items: list[Item], p: dict) -> dict[str, dict[str, float]]:
    rows = defaultdict(list)
    for item in items:
        if len(item.times) == 0:
            continue
        idx = assign(item, p)
        utts = utterances(item, idx, p["utt_gap"])
        d, conf = der(item.reference, utts, item.frames)
        found = len({u[0] for u in utts})
        row = {"word_acc": word_accuracy(item, idx), "der": d, "confusion": conf, "spk_ok": float(found == item.speakers)}
        rows[item.group].append(row)
        rows["ВСЕ"].append(row)
    return {g: {k: float(np.mean([r[k] for r in rs])) for k in rs[0]} for g, rs in rows.items()}


def score(result: dict) -> float:
    """Одно число для сравнения: доля верных слов минус DER, усреднённые по группам (каждая группа с равным весом)."""
    groups = [g for g in result if g != "ВСЕ"]
    return float(np.mean([result[g]["word_acc"] - result[g]["der"] for g in groups]))


def show(result: dict, title: str) -> None:
    print(f"\n{title}   итог {score(result):.4f}")
    print(f"{'группа':<16}{'слова':>8}{'DER':>8}{'путаница':>10}{'спикеры':>9}")
    for g in sorted(result, key=lambda g: (g == "ВСЕ", g)):
        r = result[g]
        print(f"{g:<16}{r['word_acc']:>8.1%}{r['der']:>8.1%}{r['confusion']:>10.1%}{r['spk_ok']:>9.0%}")


SWEEP = {
    "narrow_min": [0.1, 0.3, 0.4],
    "wide_frames": [0, 25, 100],
    "skip_min": [0.0, 0.1, 0.2],
    "median": [11, 25, 51],
    "smooth": [0],
    "smooth_gap": [0.2, 0.8],
    "smooth_score": [0.1, 0.5],
    "majority": [0],
    "pause": [0.3, 0.8, 1.2],
    "min_share": [0.5, 0.7, 0.8],
    "conf_own": [0.5, 0.8, 1.01],
    "conf_major": [0.1, 0.4],
    "min_speaker_words": [3, 5, 10],
    "utt_gap": [0.5, 1.0, 3.0],
}


def parse(pairs: list[str]) -> dict:
    p = dict(DEFAULTS)
    for pair in pairs:
        k, v = pair.split("=")
        assert k in DEFAULTS, f"нет параметра {k}"
        p[k] = type(DEFAULTS[k])(float(v)) if isinstance(DEFAULTS[k], int) else float(v)
    return p


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("mode", choices=["baseline", "sweep", "eval"])
    parser.add_argument("params", nargs="*")
    args = parser.parse_args()
    items = load(args.cache)
    print(f"Записей: {len(items)}")
    base = evaluate(items, DEFAULTS)
    if args.mode == "baseline":
        show(base, "По умолчанию")
    elif args.mode == "eval":
        show(base, "По умолчанию")
        show(evaluate(items, parse(args.params)), " ".join(args.params))
    else:
        base_score = score(base)
        print(f"По умолчанию: итог {base_score:.4f}")
        print(f"{'параметр':<28}{'итог':>9}{'Δ':>9}{'слова':>8}{'DER':>8}{'спикеры':>9}")
        for key, values in SWEEP.items():
            for value in values:
                p = dict(DEFAULTS, **{key: value})
                r = evaluate(items, p)
                s = score(r)
                print(f"{key + '=' + str(value):<28}{s:>9.4f}{s - base_score:>+9.4f}"
                      f"{r['ВСЕ']['word_acc']:>8.1%}{r['ВСЕ']['der']:>8.1%}{r['ВСЕ']['spk_ok']:>9.0%}", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
