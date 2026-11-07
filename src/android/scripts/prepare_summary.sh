#!/usr/bin/env bash
# Кладёт модель конспекта (Qwen GGUF) в assets модуля pipeline, чтобы она попала в APK. В git её нет: 1,1 ГБ.
#   ./scripts/prepare_summary.sh                  берёт summary-android/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
#   ./scripts/prepare_summary.sh путь/к/model.gguf
# Скачать закреплённую модель: python summary-android/scripts/prepare_summary_model.py
# Убрать модель из APK (конспект тогда не делается): rm -r pipeline/src/main/assets/models/summary
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${1:-$ROOT/summary-android/models/qwen2.5-1.5b-instruct-q4_k_m.gguf}"
TARGET="$ROOT/pipeline/src/main/assets/models/summary/summary.gguf"

if [ ! -f "$SOURCE" ]; then
  echo "Нет модели $SOURCE: скачайте её python summary-android/scripts/prepare_summary_model.py или укажите путь" >&2
  exit 1
fi
if [ "$(head -c 4 "$SOURCE")" != "GGUF" ]; then
  echo "$SOURCE — не GGUF" >&2
  exit 1
fi

mkdir -p "$(dirname "$TARGET")"
if ! cmp -s "$SOURCE" "$TARGET"; then
  cp "$SOURCE" "$TARGET"
fi
echo "Модель конспекта готова: $(du -h "$TARGET" | cut -f1) в pipeline/src/main/assets/models/summary"
