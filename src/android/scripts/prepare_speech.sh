#!/usr/bin/env bash
# Готовит большие файлы, которых нет в git: AAR sherpa-onnx и модель GigaAM в assets.
#   ./scripts/prepare_speech.sh                полная подготовка
#   ./scripts/prepare_speech.sh --models-only  только модель
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=2.0.1
AAR="sherpa-onnx-static-link-onnxruntime-$VERSION.aar"
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
MODEL=sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
LOCAL="$ROOT/../voicerec-poc/models/$MODEL"
ASSETS="$ROOT/speech/src/main/assets/models/gigaam"
FILES=(encoder.int8.onnx decoder.onnx joiner.onnx tokens.txt)

if [ "${1:-}" != "--models-only" ]; then
  # Имя файла в flatDir должно быть <имя>-<версия>.aar, как в speech/build.gradle.kts.
  if [ ! -f "$ROOT/speech/libs/$AAR" ]; then
    curl -fL --retry 3 -o "$ROOT/speech/libs/$AAR.part" "$BASE/v$VERSION/$AAR"
    mv "$ROOT/speech/libs/$AAR.part" "$ROOT/speech/libs/$AAR"
  fi
  echo "AAR готов: speech/libs/$AAR"
fi

if [ ! -f "$LOCAL/encoder.int8.onnx" ]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  curl -fL --retry 3 -o "$TMP/model.tar.bz2" "$BASE/asr-models/$MODEL.tar.bz2"
  tar xjf "$TMP/model.tar.bz2" -C "$TMP"
  LOCAL="$TMP/$MODEL"
fi

mkdir -p "$ASSETS"
for file in "${FILES[@]}"; do
  if ! cmp -s "$LOCAL/$file" "$ASSETS/$file"; then
    cp "$LOCAL/$file" "$ASSETS/$file"
  fi
done
echo "Модель готова: $(du -sh "$ASSETS" | cut -f1) в speech/src/main/assets/models/gigaam"
