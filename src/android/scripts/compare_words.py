#!/usr/bin/env python3
"""Сравнивает текст замера с телефона с результатом на Mac.

Запуск: python3 scripts/compare_words.py bench_test_2.m4a.4.json ../voicerec-original/results/onnx/test_2.int8.json

Показанная доля расхождений не является WER относительно истины: это разница двух расшифровок.
Нарезка на телефоне и на Mac сейчас разная, поэтому небольшие расхождения ожидаемы.
"""
import json
import re
import sys


def words_of(text):
    return re.findall(r"[а-яёa-z0-9]+", text.lower().replace("ё", "е"))


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        previous, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (x != y))
    return row[-1]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    phone = json.load(open(sys.argv[1], encoding="utf-8"))
    mac = json.load(open(sys.argv[2], encoding="utf-8"))
    phone_words = words_of(" ".join(w["text"] for w in phone["words"]))
    mac_words = words_of(" ".join(u["text"] for u in mac["utterances"]))
    edits = distance(mac_words, phone_words)
    print(f"Устройство: {phone.get('device', '?')}, потоков: {phone.get('threads', '?')}")
    print(f"Слов: телефон {len(phone_words)}, Mac {len(mac_words)}")
    print(f"Расхождение текста: {edits / max(len(mac_words), 1):.1%}")
    print(f"RTF: {phone.get('rtf', 0):.3f}, обработка: {phone.get('processing_ms', 0) / 1000:.1f} с")
    print(f"Пик памяти: {max(phone.get('peak_rss_mb', {'-': 0}).values())} МБ")


if __name__ == "__main__":
    main()
