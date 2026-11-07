# Диаризация (роль 3)

Модуль `diarization` определяет, кто и когда говорит, режет речь на куски для распознавания и назначает спикера каждому слову. Это перенос Python-прототипа из `voicerec-original` на Kotlin:

| Файл | Порт из | За что отвечает |
|---|---|---|
| `LogMel.kt` | `onnx_diarizer.log_mel` | Звук → лог-мел признаки [кадры × 128], своё БПФ |
| `SpeakerCache.kt` | `onnx_diarizer.SpeakerCache` | Кэш спикеров и FIFO между чанками |
| `NemotronDiarizer.kt` | `onnx_diarizer.OnnxDiarizer` | Nemotron 3 Diarization через ONNX Runtime → вероятности [кадры × 8], шаг 10 мс |
| `ChunkPlanner.kt` | `voicerec/segmentation.py` | Вероятности → куски речи до 20 с для `Recognizer.transcribe` |
| `TranscriptMerger.kt` | `voicerec/merge.py` | Слова + вероятности → спикеры слов → реплики |

Модуль на чистом Kotlin/JVM, без Android: тесты идут на компьютере без SDK и телефона.

**Статус:** 19 тестов проходят, результат сверен с прототипом на трёх записях (2,1, 2,3 и 10,4 минуты, от 3 до 8 спикеров):

| Проверка | Результат |
|---|---|
| Лог-мел признаки | Расхождение не больше 2·10⁻⁶ |
| Эмбеддинги embed.onnx | Совпадают точно |
| Кэш спикеров после каждого из 34 чанков | Совпадает точно |
| Вероятности от эмбеддингов Python | Расхождение не больше 1,2·10⁻⁷, ни одно решение «говорит» не изменилось |
| Вероятности от звука | Изменилось 0,02–0,15% решений «говорит», см. ниже |
| Куски речи и реплики на вероятностях Python | Совпадают точно, 96 из 96 реплик |
| Вся цепочка на Kotlin, dialog_10min | 841 из 841 слова получили того же спикера, что на Mac |
| Скорость на компьютере (4 потока) | RTF около 0,04 |

Почему вероятности от звука совпадают не точно: признаки отличаются от Python на ~2·10⁻⁶ из-за порядка сложения во float32, а динамическое int8-квантование `step_int8.onnx` раздувает это в отдельных кадрах. Сам Python от шума 10⁻⁶ в признаках меняет 384 решения из 112 432 на `kpjud`, Kotlin-версия — 167.

## Как пользоваться

```kotlin
val probs = NemotronDiarizer.open(modelDir, threads = 4).use { diarizer ->
    diarizer.diarize(audio) { progress, speakersFound -> /* прогресс и «Голосов: N» на экране */ }
}
val chunks: List<LongRange> = ChunkPlanner.plan(probs, audio)        // вместо QuietChunker
val words = recognizer.transcribe(audio, chunks)                      // speech.Word
val utterances = TranscriptMerger.merge(
    words.map { AsrWord(it.text, it.startMs, it.endMs) }, probs,
)
// utterances[i].speakerId, startMs, endMs, text — формат JSON задания
```

Модели загружаются по очереди: `NemotronDiarizer` закрывается до загрузки GigaAM. `probs` для часовой записи занимает около 11 МБ.

## Файлы модели

В `modelDir` нужны четыре файла из `voicerec-original/models_onnx`: `embed.onnx` (2 МБ), `step_int8.onnx` (103 МБ), `mel_filters.bin`, `silence_embeds.bin`. В git их нет, в APK их нужно класть так же, как GigaAM (`noCompress += "onnx"`).

## Тесты

```bash
cd src/android/diarization
```

```bash
../gradlew test
```

Тесты сверки ищут эталон в `../../../../reference`, а модель — в `../../../../voicerec-original/models_onnx`, то есть рядом с папкой репозитория, как `voicerec-poc` у роли 2. Без них такие тесты пропускаются. Другие пути: `-PreferenceDir=... -PmodelsDir=...`.

Эталон снимается с прототипа (нужны `numpy` и `onnxruntime`, звук — WAV 16 кГц):

```bash
python scripts/dump_reference.py --original ../../../../voicerec-original --out ../../../../reference ru_3spk=../../../../voicerec-poc/testset/russian/ru_3spk.wav kpjud=../../../../voicerec-poc/testset/voxconverse/audio/kpjud.wav dialog_10min=../../../../voicerec-poc/samples/dialog_10min.wav:../../../../voicerec-original/results/onnx/dialog_10min.int8.json
```

На Windows тесты используют ONNX Runtime 1.18: сборки 1.19 и новее падают при загрузке DLL, потому что JDK подгружает свой старый `msvcp140.dll`. На Linux и Mac можно `-PortTestVersion=1.22.0`.

## Подбор порогов

Скрипты в `scripts/tuning/`: `cache_testset.py` один раз считает вероятности Nemotron и слова GigaAM, `tune.py` переигрывает постобработку (как в `TranscriptMerger.kt`) с другими порогами и считает метрики по итоговым репликам.

Набор — 48 записей: 6 VoxConverse, 6 русских диалогов и 36 их зашумлённых версий из `build_noisy.py` (розовый шум 15 и 5 дБ, чужие голоса на фоне 15 и 5 дБ, реверберация, телефонная полоса). Результат с порогами по умолчанию:

| Группа | Слова с верным спикером | DER реплик | Число спикеров угадано |
|---|---|---|---|
| Чистые русские | 100% | 0,7% | 100% |
| Розовый шум 15 / 5 дБ | 100% / 100% | 0,8% / 1,1% | 100% |
| Реверберация | 99,6% | 1,3% | 100% |
| Телефонная полоса | 91,0% | 11,2% | 83% |
| Чужие голоса на фоне 15 дБ | 90,0% | 12,0% | 17% |
| Чужие голоса на фоне 5 дБ | 59,0% | 51,8% | 0% |
| VoxConverse | 97,3% | 17,3% | 67% |

Выводы:

- **Пороги постобработки почти не влияют:** перебор 14 параметров по одному меняет итог в пределах ±0,5%. Лучшая комбинация (`min_share=0.5`, `smooth_gap=0.8`) выигрывает только на голосах на фоне 5 дБ, а на 15 дБ и реверберации чуть хуже, поэтому оставлены значения прототипа.
- **Нужны сглаживание и большинство в предложении:** без большинства точность слов падает на 0,9%, без сглаживания DER растёт.
- **Слабое место — чужие голоса на фоне.** Nemotron находит фоновых людей как отдельных спикеров, а GigaAM расшифровывает их слова, поэтому число спикеров завышено. Порогами это не лечится: у ложных спикеров бывает и 1–2 слова, и 20.
- Набор искусственный (аудиокниги, разные микрофоны у дикторов). Чтобы улучшать дальше, нужна размеченная живая речь, например `test_2` с исправленным эталоном.

Запуск:

```bash
python scripts/tuning/cache_testset.py --poc ../../../../voicerec-poc --original ../../../../voicerec-original --noisy ../../../../noisy --cache ../../../../tuning/cache
```

```bash
python scripts/tuning/tune.py --cache ../../../../tuning/cache sweep
```

## Подключение к приложению

Модуль пока не включён в `../settings.gradle.kts`: на компьютере роли 3 нет Android SDK, и проверить сборку всего проекта не на чем. Чтобы подключить:

1. В `../settings.gradle.kts`: `include(":diarization")`.
2. В приложении: `implementation(project(":diarization"))` и `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")`.
3. Проверить, что `onnxruntime-android` уживается с `sherpa-onnx-static-link-onnxruntime` в одном APK.

Если плагин `kotlin("jvm")` с версией конфликтует с AGP 9, версию нужно убрать из `build.gradle.kts` модуля: тогда возьмётся та, что уже есть в проекте.

## Ограничения и что дальше

- **Память на длинных записях:** `embed.onnx` запускается на всю запись сразу, как в прототипе. Признаки часа звука — около 180 МБ, эмбеддинги — около 90 МБ. Следующий шаг — считать признаки и эмбеддинги блоками.
- **Номера спикеров:** `speaker_N` — номер столбца Nemotron. Он идёт по порядку появления, но если столбец не попал ни в одно слово, в номерах будет пропуск. Можно перенумеровать по первому слову.
- **Эхо микрофона** (`remove_echo` из merge.py) не перенесено: оно нужно только для живой записи с двух источников.
- **Скорость на телефоне** не замерена.
