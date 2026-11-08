# Реплика: Android-сборка и замеры

Модуль `speech` читает аудио и распознаёт речь локально, `pipeline` объединяет
голоса, слова и конспект, а `bench` — диагностический экран для замеров на телефоне.
Пользовательское приложение — модуль `:app`.

**Проверка модуля speech:** 24 модульных теста проходят. Проверено на Android-телефоне,
отдельно от диаризации и LLM:

| Проверка | Результат |
|---|---|
| m4a (AAC) | RTF около 0,09–0,10 (было 0,155 до ускорения чтения), запись на 14,5 минуты обработана без падений |
| WAV | RTF около 0,09 |
| Пик памяти | 0,47–0,59 ГБ |
| WER на 6 собранных русских диалогах | 7,5%, на Mac было около 7,2% |
| Release-сборка | Та же скорость, что у debug |
| Форматы | m4a, mp3, wav (16 и 44,1 кГц, стерео), flac, ogg/vorbis, ogg/opus 48 кГц как у голосовых Telegram |
| Битые и пустые файлы | Понятная ошибка `AudioDecodeException` без падения приложения |

## Что внутри

| Файл в `speech` | За что отвечает |
|---|---|
| `Contracts.kt` | Общие `Word`, `AudioDecoder`, `Recognizer` |
| `Envelope.kt` | Огибающая громкости для события `Decoded` |
| `MediaCodecAudioDecoder.kt` | Файл → звук 16 кГц, моно |
| `Stitch.kt` | Склейка кусков, прочитанных параллельно, и проверка стыков |
| `Pcm.kt` | Сведение каналов и смена частоты |
| `ModelInstaller.kt` | Копирование модели из APK во внутреннюю память |
| `SherpaRecognizer.kt` | GigaAM v3 через sherpa-onnx |
| `TokenWords.kt` | Токены → слова с таймкодами |
| `QuietChunker.kt` | Временная нарезка, пока нет `plan_chunks` от роли 3 |

## Как собрать

Нужны Android SDK (API 36), JDK 21, NDK `29.0.13113456` и CMake `3.22.1`.
Команды выполняются из папки `android`, переменная `ANDROID_HOME` должна указывать на SDK.

```bash
./scripts/prepare_speech.sh
```

Дальше откройте папку `android` в Android Studio или соберите из терминала:

```bash
./gradlew :speech:testDebugUnitTest :bench:assembleDebug
```

Если при первой сборке обрывается соединение, запустите команду ещё раз: уже скачанные зависимости сохраняются.

## Как замерить на телефоне

```bash
adb install -r bench/build/outputs/apk/debug/bench-debug.apk
```

```bash
adb shell mkdir -p /sdcard/Android/data/ru.stenographer.speechbench/files
```

```bash
adb push ../voicerec-poc/test_2.m4a /sdcard/Android/data/ru.stenographer.speechbench/files/
```

```bash
adb shell am start -n ru.stenographer.speechbench/.MainActivity --es file test_2.m4a --ei threads 4
```

```bash
adb logcat -s SpeechBench
```

Когда в журнале появится `DONE`, заберите результат и сравните его с Mac:

```bash
adb pull /sdcard/Android/data/ru.stenographer.speechbench/files/bench_test_2.m4a.4.json
```

```bash
python3 scripts/compare_words.py bench_test_2.m4a.4.json ../voicerec-original/results/onnx/test_2.int8.json
```

## Вся обработка в замере

Бенч гоняет ту же обработку, что и приложение: `TranscriptionPipeline` из `:pipeline` (чтение → диаризация → куски речи → GigaAM → реплики → конспект), и меряет её по событиям. Модули описаны в `pipeline/README.md`, `diarization/README.md`, `core/README.md`.

Модель диаризации (около 106 МБ) кладётся в assets `:pipeline` отдельно: скрипт ищет `voicerec-original/models_onnx` рядом с репозиторием или рядом с папкой `android`, путь можно передать аргументом. Для `:summary-android` нужны NDK 29.0.13113456, CMake 3.22.1 и исходники llama.cpp: `python summary-android/scripts/prepare_llama.py`.

```bash
./scripts/prepare_diarization.sh
```

Запуск такой же, как выше. Конспект — если положить GGUF-модель в папку приложения и добавить `--es summary_model имя.gguf`.

Кроме `bench_*.json` появятся `transcript_test_2.m4a.json` в формате задания и `summary_*.json`, если был конспект. В `bench_*.json`: время и пик памяти по этапам `decode`, `diarize`, `recognize`, `merge`, `summary` (с установкой и загрузкой моделей), `transcription_ms` и `rtf` до готовой расшифровки, события с моментами прихода (`events`), слова.

```bash
adb pull /sdcard/Android/data/ru.stenographer.speechbench/files/transcript_test_2.m4a.json
```

## Чтение файлов

Системный кодек работает в отдельном процессе, и раньше почти всё время чтения уходило на передачу ему кадров по одному.
Теперь:

- кодек работает в асинхронном режиме, а перевод в float и пересчёт в 16 кГц идут сразу в его потоке;
- **AAC (m4a) и Opus** длиннее 20 с делятся на 4 куска, каждый читает свой кодек. Кусок начинается на 0,5 с раньше
  своего стыка, лишнее обрезается по номеру отсчёта. Соседние куски сравниваются в перекрытии: если они разошлись,
  запись перечитывается одним проходом;
- **MP3 и Vorbis** читаются одним проходом: после перемотки их кодеки сообщают время кадров неточно, и куски съезжали
  на 8–120 мс. **WAV и FLAC** и так читаются быстро.

Время чтения на телефоне, старый и новый способ, каждый в отдельном запуске приложения:

| Файл | Было | Стало |
|---|---|---|
| m4a с диктофона телефона, 14,5 мин | 69,7 с | 10,0 с |
| m4a с диктофона телефона, 3,3 мин | 15,0 с | 2,1 с |
| m4a (AAC из ffmpeg), 2 мин | 8,4 с | 1,3 с |
| ogg/opus, как у голосовых Telegram, 2 мин | 8,1 с | 1,3 с |
| mp3, 2 мин | 3,1 с | 2,0 с |
| ogg/vorbis, 2 мин | 4,8 с | 2,9 с |
| wav 44,1 кГц стерео, 2 мин | 1,1 с | 0,8 с |

Записи с диктофона телефона и AAC без PNS склеиваются бит в бит с чтением одним проходом, слова и таймкоды
распознавания не меняются. У AAC с PNS и у Opus разница в отсчётах до 0,2 и 0,002: эти кодеки синтезируют часть
шума случайно, и после перемотки шум другой. На WER это не повлияло: 4,5% против 4,0% у исходного WAV, столько же даёт MP3, который читается одним проходом, то есть разницу дают сжатие и кодек, а не склейка.

Проверить стыки на другом телефоне можно так: `bench` прочитает файл обоими способами и сравнит отсчёты.

```bash
adb shell am start -n ru.stenographer.speechbench/.MainActivity --es file test_2.m4a --ez verify true
```

Результат появится в `verify_test_2.m4a.4.json` рядом с файлом.

## Как подключить в приложение

```kotlin
val audio = MediaCodecAudioDecoder(context).decode(uri) { progress -> /* StageProgress(DECODE) */ }
val envelope = loudnessEnvelope(audio) // Decoded: RMS по 50 мс, от 0 до 1
val modelDir = ModelInstaller(context).install()
val words = SherpaRecognizer.open(modelDir, threads = 4).use { asr ->
    asr.transcribe(
        audio,
        chunks,
        onProgress = { progress -> /* StageProgress(RECOGNIZE) */ },
        onChunk = { chunkWords, processedUntilMs -> /* Words */ },
    )
}
```

`chunks` — куски речи как `началоМс..конецМс` от начала записи. `onChunk` вызывается после каждого куска,
а `processedUntilMs` не уменьшается. Все колбэки приходят не в главном потоке.

Ошибки чтения приходят как `AudioDecodeException`, её текст можно показывать пользователю.

В `build.gradle.kts` итогового приложения нужны `androidResources { noCompress += "onnx" }` и `abiFilters += "arm64-v8a"`.
В манифесте нужен `android:largeHeap="true"`.

## Итоговый APK со всеми моделями

Жюри устанавливает один APK, все модели внутри. Так собран `replika-v1.0.apk` из релиза v1.0.
Нужны Android SDK 36, NDK 29.0.13113456, CMake 3.22.1 и JDK 17+. Команды — из папки `src/android`.

```bash
# Распознавание: AAR sherpa-onnx и GigaAM v3 скачиваются скриптом
./scripts/prepare_speech.sh
# Голоса: четыре файла Nemotron (embed.onnx, step_int8.onnx, mel_filters.bin, silence_embeds.bin)
./scripts/prepare_diarization.sh путь/к/models_onnx
# Конспект: исходники llama.cpp и модель Qwen2.5-0.5B-Instruct Q4_K_M
python summary-android/scripts/prepare_llama.py
curl -L -o qwen2.5-0.5b-instruct-q4_k_m.gguf \
  "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
shasum -a 256 qwen2.5-0.5b-instruct-q4_k_m.gguf
# 74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db
./scripts/prepare_summary.sh qwen2.5-0.5b-instruct-q4_k_m.gguf
# Сборка и установка
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Модель конспекта выбрана по замеру на Pixel 7 Pro: Qwen2.5-0.5B даёт конспект за 53 с при 0,82 ГБ памяти,
1.5B — за 101 с при 1,46 ГБ при том же числе пунктов. Скрипт `prepare_summary_model.py` по умолчанию скачивает
1.5B и нужен только для сравнения моделей. Каталоги с моделями исключены из Git. Без модели конспекта APK
тоже собирается и работает, но названия и конспекта не будет, а локальная LLM обязательна по условиям задачи.

Для замера первой установки используют чистое состояние приложения, для
повторного прогона — то же установленное приложение. Не объединяйте эти
измерения. Итоговый APK проверяется в авиарежиме на настоящих аудиофайлах;
установки и тестов модулей недостаточно для подтверждения полного сценария.

## Ограничения

- **Память на длинных записях:** вся запись лежит в одном `FloatArray`, а час звука занимает около 230 МБ, причём в конце чтения кратковременно нужна ещё одна копия. Для таких файлов нужна потоковая обработка.
- **Место на устройстве:** модель хранится и в APK, и во внутренней памяти, это ещё около 232 МБ.
- **Совместимость с диаризацией:** выбран AAR со статически встроенным ONNX Runtime. Работу рядом с `onnxruntime-android` нужно проверить в одном APK.
- **Чтение MP3 и Vorbis:** только одним проходом, см. «Чтение файлов».
- **Язык:** GigaAM v3 распознаёт только русский.
