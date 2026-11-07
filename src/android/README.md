# Распознавание речи на Android (роль 2)

Модуль `speech` читает аудио и распознаёт речь локально, а `bench` — временный экран для замеров на телефоне. Модуль `:app` освобождён под интерфейс.

**Статус:** проект собирается, 15 модульных тестов проходят. Проверено на одном телефоне (HUAWEI ADY-LX9, Android 12), пока без диаризации:

| Проверка | Результат |
|---|---|
| m4a (AAC) | RTF около 0,155, запись на 14,5 минуты обработана без падений |
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
| `Pcm.kt` | Сведение каналов и смена частоты |
| `ModelInstaller.kt` | Копирование модели из APK во внутреннюю память |
| `SherpaRecognizer.kt` | GigaAM v3 через sherpa-onnx |
| `TokenWords.kt` | Токены → слова с таймкодами |
| `QuietChunker.kt` | Временная нарезка, пока нет `plan_chunks` от роли 3 |

## Как собрать

Нужны Android SDK (API 36) и JDK. Проверено на Java 25, на Java 27 сборка не проверялась. Команды выполняются из папки `android`, переменная `ANDROID_HOME` должна указывать на SDK.

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

## Ограничения

- **Память на длинных записях:** вся запись лежит в одном `FloatArray`, а час звука занимает около 230 МБ, причём в конце чтения кратковременно нужна ещё одна копия. Для таких файлов нужна потоковая обработка.
- **Место на устройстве:** модель хранится и в APK, и во внутренней памяти, это ещё около 232 МБ.
- **Совместимость с диаризацией:** выбран AAR со статически встроенным ONNX Runtime. Работу рядом с `onnxruntime-android` нужно проверить в одном APK.
- **Чтение AAC:** около половины времени чтения уходит на обмен кадрами с системным кодеком, а не на код модуля.
- **Язык:** GigaAM v3 распознаёт только русский.
