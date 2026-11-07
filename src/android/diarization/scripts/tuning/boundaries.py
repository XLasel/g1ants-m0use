#!/usr/bin/env python3
"""Точность таймкодов реплик: насколько начало и конец реплик расходятся с эталоном, и помогает ли уточнение
границ по вероятностям Nemotron.

Для каждой реплики эталона ищется реплика гипотезы того же спикера (после сопоставления меток) с наибольшим
перекрытием; считаются |Δначала| и |Δконца|. Эталон VoxConverse (RTTM) сначала склеивается в реплики так же,
как гипотеза (паузы до utt_gap), иначе он дробнее любой расшифровки.

Запуск: python boundaries.py --cache D:/hackmifi/tuning/cache
"""
from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment

import tune

FRAME = 0.01


def refine(item, idx, utts, p):
    """Уточняет границы реплик по вероятности её спикера.

    Конец: от начала последнего слова идём вперёд, пока спикер активен (вероятность >= th, провалы до gap кадров
    прощаются), но не дальше max_tail секунд и не дальше начала следующей реплики.
    Начало: от начала первого слова идём назад так же, не дальше max_head секунд и не раньше конца предыдущей реплики.
    """
    probs = item.probs
    n = len(probs)
    th, gap = p["th"], int(p["gap"])
    out = []
    for k, (spk, start, end, words) in enumerate(utts):
        col = probs[:, spk]
        new_start, new_end = start, end
        if p["tail"]:
            last_start = item.times[words[-1], 0]
            f = int(round(last_start / FRAME))
            limit = min(n, int(round((last_start + p["max_tail"]) / FRAME)))
            if k + 1 < len(utts):
                limit = min(limit, int(round(utts[k + 1][1] / FRAME)))
            last_active, misses = f, 0
            while f < limit:
                if col[f] >= th:
                    last_active, misses = f, 0
                else:
                    misses += 1
                    if misses > gap:
                        break
                f += 1
            candidate = (last_active + 1) * FRAME
            if candidate > last_start + p["min_word"]:
                new_end = candidate
        if p["head"]:
            f = int(round(start / FRAME)) - 1
            limit = max(0, int(round((start - p["max_head"]) / FRAME)))
            if k > 0:
                limit = max(limit, int(round(out[-1][2] / FRAME)))
            first_active, misses = int(round(start / FRAME)), 0
            while f >= limit:
                if col[f] >= th:
                    first_active, misses = f, 0
                else:
                    misses += 1
                    if misses > gap:
                        break
                f -= 1
            new_start = first_active * FRAME
        out.append((spk, new_start, max(new_end, new_start + FRAME), words))
    return out


def reference_turns(item, gap):
    turns = sorted(item.reference, key=lambda t: t["start"])
    merged = []
    for t in turns:
        if merged and merged[-1]["speaker"] == t["speaker"] and t["start"] - merged[-1]["end"] <= gap:
            merged[-1]["end"] = max(merged[-1]["end"], t["end"])
        else:
            merged.append(dict(t))
    return merged


def boundary_errors(item, utts, gap):
    ref = reference_turns(item, gap) if item.kind == "voxconverse" else item.reference
    labels = sorted({t["speaker"] for t in ref}, key=str)
    hyp_spk = sorted({u[0] for u in utts})
    overlap = np.zeros((len(labels), len(hyp_spk)))
    for t in ref:
        for u in utts:
            o = min(t["end"], u[2]) - max(t["start"], u[1])
            if o > 0:
                overlap[labels.index(t["speaker"]), hyp_spk.index(u[0])] += o
    rows, cols = linear_sum_assignment(-overlap)
    mapping = {labels[r]: hyp_spk[c] for r, c in zip(rows, cols)}
    ds, de = [], []
    for t in ref:
        best, best_o = None, 0.0
        for u in utts:
            if u[0] != mapping.get(t["speaker"]):
                continue
            o = min(t["end"], u[2]) - max(t["start"], u[1])
            if o > best_o:
                best, best_o = u, o
        if best is not None:
            ds.append(abs(best[1] - t["start"]))
            de.append(abs(best[2] - t["end"]))
    return ds, de


def run(items, p):
    rows = defaultdict(lambda: [[], [], []])
    for item in items:
        if len(item.times) == 0:
            continue
        idx = tune.assign(item, tune.DEFAULTS)
        utts = tune.utterances(item, idx, tune.DEFAULTS["utt_gap"])
        if p:
            utts = refine(item, idx, utts, p)
        ds, de = boundary_errors(item, utts, tune.DEFAULTS["utt_gap"])
        d, _ = tune.der(item.reference, utts, item.frames)
        for g in (item.group, "ВСЕ"):
            rows[g][0] += ds
            rows[g][1] += de
            rows[g][2].append(d)
    return {g: (np.median(r[0]), np.median(r[1]), np.mean(r[1]), np.mean(r[2])) for g, r in rows.items()}


def show(result, title):
    print(f"\n{title}")
    print(f"{'группа':<16}{'Δначала мед':>12}{'Δконца мед':>12}{'Δконца ср':>11}{'DER':>8}")
    for g in sorted(result, key=lambda g: (g == "ВСЕ", g)):
        a, b, c, d = result[g]
        print(f"{g:<16}{a:>11.2f}с{b:>11.2f}с{c:>10.2f}с{d:>8.1%}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, required=True)
    args = parser.parse_args()
    items = tune.load(args.cache)
    show(run(items, None), "Как сейчас: границы по словам")
    base = {"tail": 1, "head": 0, "th": 0.5, "gap": 10, "max_tail": 2.0, "min_word": 0.1, "max_head": 0.5}
    for change in [{}, {"th": 0.3}, {"th": 0.7}, {"gap": 3}, {"gap": 30}, {"max_tail": 1.0}, {"max_tail": 3.0},
                   {"head": 1}, {"head": 1, "max_head": 0.3}, {"tail": 0, "head": 1}]:
        p = dict(base, **change)
        show(run(items, p), "Уточнение по Nemotron: " + (", ".join(f"{k}={v}" for k, v in change.items()) or "базовое"))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
