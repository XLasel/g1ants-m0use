#!/usr/bin/env bash
# Кладёт модель Nemotron для диаризации в assets приложения. В git её нет: step_int8.onnx весит 103 МБ.
#   ./scripts/prepare_diarization.sh                 ищет models_onnx в voicerec-original рядом с репозиторием
#   ./scripts/prepare_diarization.sh путь/к/models_onnx
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/bench/src/main/assets/models/nemotron"
FILES=(embed.onnx step_int8.onnx mel_filters.bin silence_embeds.bin)

if [ $# -ge 1 ]; then
  SOURCE="$1"
else
  # voicerec-original рядом с папкой репозитория или рядом с папкой android
  for candidate in "$ROOT/../../../voicerec-original/models_onnx" "$ROOT/../voicerec-original/models_onnx"; do
    if [ -f "$candidate/step_int8.onnx" ]; then SOURCE="$candidate"; break; fi
  done
fi
if [ -z "${SOURCE:-}" ] || [ ! -f "$SOURCE/step_int8.onnx" ]; then
  echo "Не найдена папка models_onnx из voicerec-original, укажите её первым аргументом" >&2
  exit 1
fi

mkdir -p "$ASSETS"
for file in "${FILES[@]}"; do
  if ! cmp -s "$SOURCE/$file" "$ASSETS/$file"; then
    cp "$SOURCE/$file" "$ASSETS/$file"
  fi
done
echo "Модель готова: $(du -sh "$ASSETS" | cut -f1) в bench/src/main/assets/models/nemotron"
