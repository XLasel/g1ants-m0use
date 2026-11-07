# Название и конспект: роль 4

Реализация по `04_ui_contract.md` от Кати (7 ноября): после готовой расшифровки
локальная LLM выдаёт название 3–6 слов, конспект и `SummaryPoint(text, sourceStartMs)`.
Реплики ASR и JSON задания не редактируются.

## Состав

- `summary`: Kotlin-логика отбора ключевых фрагментов, ограничение контекста,
  сокращение длинных записей и точная привязка текста и времени к источнику.
- `summary-android`: CPU llama.cpp через JNI, импорт GGUF из локального файла,
  coroutine-адаптер с отменой и освобождением модели.
- `tools/test_summary.py`: тесты на компьютере без Android SDK и без весов.

Первый кандидат весов — **Qwen2.5-1.5B-Instruct-GGUF, Q4_K_M**:
https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF.
Веса не входят в git. Настоящий GGUF проверен через JNI на Linux; native-библиотека
также собрана для Android ARM64 (API 26, NDK 29). Скорость, пиковая память и качество
LLM на телефоне ещё не измерены; «6 секунд» из HTML-прототипа не являются замером.

## Подключение Кате

Базовый Android-проект и wrapper находятся в ветке `task-2-speech-recognition`.
В `task-3-diarization` уже есть этот проект с подключённой диаризацией и экспортом
JSON (`f6f9010`, проверено 7 ноября), включая голоса и отрезки по ходу обработки. Учтён и коммит Антона `f750612`: модуль
замеров теперь `bench`, а `app` оставлен для интерфейса. Модули конспекта не зависят
от конкретного диаризатора: ниже показано преобразование готовых реплик в `SourceUtterance`.
В этой интеграционной ветке оба include и зависимость бенча уже добавлены.
При переносе только модулей в другую ветку нужны следующие строки
в `src/android/settings.gradle.kts`:

```kotlin
include(":summary", ":summary-android")
```

В зависимостях приложения:

```kotlin
implementation(project(":summary-android"))
```

Оба модуля используют существующий `com.android.library` и встроенный Kotlin
AGP 9.5.1 из проекта Антона. Собственного JVM-плагина и project repositories
нет: это совместимо с `FAIL_ON_PROJECT_REPOS` в общих settings.
На устройстве используется платформенный org.json.

Из корня репозитория подготовить исходники native-движка:

```bash
python src/android/summary-android/scripts/prepare_llama.py
```

Нужны Git 2.25+, SDK 36, NDK `29.0.13113456`, CMake `3.22.1`, JDK 17+.
NDK и CMake устанавливаются через SDK Manager. Исходники llama.cpp закреплены
на `cf659f240b232e15700b1721bbb43c1d973c52e9`; CMake проверяет эту ревизию.
Сеть нужна для подготовки сборки, а не для обработки записи в приложении.

Из `src/android`:

```bash
./gradlew :summary:testDebugUnitTest :summary-android:testDebugUnitTest :summary-android:assembleDebug
```

Проверено через Gradle 9.8.2 / AGP 9.5.1, JDK 21: 24 теста прошли,
`summary-android-debug.aar` и диагностический `bench-debug.apk` собраны. Native-библиотека
в AAR — ARM64. Это проверка сборки, запуск LLM на телефоне ещё нужен.

## Замер всей цепочки на телефоне

Бенч из `bench` подключён к `diarizeWithSegments` Артёма и после сохранения
транскрипции вызывает `summarizeLocal`. Для проверки из `src/android`:

```bash
./scripts/prepare_speech.sh
./scripts/prepare_diarization.sh /path/to/voicerec-original/models_onnx
python summary-android/scripts/prepare_llama.py
python summary-android/scripts/prepare_summary_model.py
./gradlew :bench:assembleDebug
adb install -r bench/build/outputs/apk/debug/bench-debug.apk
adb shell mkdir -p /sdcard/Android/data/ru.stenographer.speechbench/files
adb push /path/to/test_2.m4a /sdcard/Android/data/ru.stenographer.speechbench/files/test_2.m4a
adb push summary-android/models/qwen2.5-1.5b-instruct-q4_k_m.gguf /sdcard/Android/data/ru.stenographer.speechbench/files/summary.gguf
adb shell am start -n ru.stenographer.speechbench/.MainActivity --es file test_2.m4a --ei threads 4 --es summary_model summary.gguf
adb logcat -s SpeechBench
```

Если `am start` сообщает о уже открытой Activity, сначала остановить бенч
(`adb shell am force-stop ru.stenographer.speechbench`) и повторить запуск.
Перед обработкой включить авиарежим. Все модели должны быть установлены заранее.

После `DONE` забрать файлы:

```bash
adb pull /sdcard/Android/data/ru.stenographer.speechbench/files/transcript_test_2.m4a.json
adb pull /sdcard/Android/data/ru.stenographer.speechbench/files/summary_test_2.m4a.json
adb pull /sdcard/Android/data/ru.stenographer.speechbench/files/bench_test_2.m4a.4.json
```

`transcript_*` — прежний JSON задания; конспект записывается отдельно.
В отчёте `summary_status` должен быть `success`; `summary` в `timings_ms`
включает загрузку, генерацию и закрытие GGUF. `processing_ms` / `rtf`
включают LLM, `transcription_ms` / `transcription_rtf` — обработка до конспекта.
`peak_rss_mb` — накопительный максимум процесса, не память только одной модели.
При ошибке LLM отчёт содержит `summary_status=failed`, готовый transcript остаётся.

Флаг `summary_model` позволяет сравнить замеры бенча с LLM и без неё. В итоговом
приложении локальная LLM обязательна; этот флаг не является настройкой продукта.
APK, собранный здесь, проверен без assets моделей ASR/диаризации: для реального
запуска их нужно подготовить командами выше. Результаты физического телефона
этой интеграции пока не заявляются.

## Вызов в pipeline

```kotlin
import ru.stenographer.summary.SourceUtterance
import ru.stenographer.summary.android.summarizeLocal

val source = utterances.map {
    SourceUtterance(it.speakerId, it.startMs, it.endMs, it.text)
}
val result = summarizeLocal(ggufFile, source, contextSize = 4096, threads = 4) { p ->
    // callback с рабочего потока: в channelFlow используйте trySend(StageProgress(... SUMMARY, p)).
}
// result.title, result.text, result.points — данные для события PipelineEvent.Summary.
// Если SummaryPoint в pipeline свой: result.points.map { SummaryPoint(it.text, it.sourceStartMs) }.
```

**Порядок:** отправить `Done` с готовыми репликами → выполнить `summarizeLocal`
→ отправить `Summary`. `Done` не закрывает Flow. Модели ASR и диаризации уже
должны быть закрыты; очередь не загружает модели следующего файла, пока
конспект не завершился или не закончился ошибкой. Сам адаптер освобождает LLM
до возврата успешного результата или ошибки.

При ошибке LLM готовая расшифровка остаётся доступной и экспортируемой.
Caller показывает состояние ошибки конспекта отдельно; нельзя заменять текст
заранее подготовленным конспектом или объявлять, что LLM успешно сработала.
В контракте UI нет отдельного события ошибки конспекта — способ его отображения
нужно согласовать при подключении, не превращая уже успешный `Done` в потерю результата.

## Доставка модели

Модель должна быть на телефоне до проверки. `GgufModelStore.importModel(context, uri)`
копирует **локальный** файл из SAF во внутреннюю память; этот вызов выполняется
вне Main. Далее сохраните путь и используйте его как `ggufFile`.
Запросов к сети и разрешения INTERNET модуль не добавляет.

Импорт создаёт новый файл с проверкой GGUF magic и удалением незавершённой копии
при ошибке. Старый mmap-файл не перезаписывается во время инференса. Полную
валидность GGUF и совместимость архитектуры затем проверяет llama.cpp при загрузке.
На время копирования нужны место для исходного файла и внутренней копии;
старые модели удаляются после закрытия использующего их движка.

Для подготовки файла на компьютере есть скрипт:

```bash
python src/android/summary-android/scripts/prepare_summary_model.py
```

Он скачивает официальный Qwen с закреплённой ревизии, проверяет размер и SHA-256.
По умолчанию файл лежит в `summary-android/models/` (исключено из git).
Перед проверкой перенесите модель на телефон: загрузка весов в runtime не нужна.
Готовые файлы `models_onnx` из Python-архивов не заменяют GGUF для LLM.

## Что именно проверяется

- Конспект экстрактивный: LLM создаёт название и выбирает ID фрагментов для темы,
  решений и задач. Текст пунктов берётся дословно из выбранного источника.
  Генерируемый моделью текст пунктов запрещён схемой и парсером.
- Ответ ограничен JSON-грамматикой; ID разрешены только из текущего набора.
  Парсер повторно проверяет поля, целочисленные ID и размер массивов.
- `sourceStartMs` берётся из той же исходной реплики, что и текст пункта.
  Модель не может написать один текст и прикрепить к нему чужое время.
- Название очищается от внешних кавычек/точки и проверяется на 3–6 слов.
- Ограничение контекста считается настоящим токенизатором движка вместе с chat template.
- Длинная реплика делится на фрагменты до 320 символов со своими ID, каждый хранит
  связь с исходной репликой. Иерархический отбор работает с исходными фрагментами.
  Нет молчаливого обрезания конца. Ограничения — 24 вызова и 4 раунда; ответ до 256 токенов.
- Ошибки JSON, выдуманные ID, обрезанный ответ или превышение бюджета дают исключение.
- Точность цитируемого текста и времени проверяется механически. Выбор важных
  фрагментов, распределение по разделам и смысл названия остаются задачей LLM
  и требуют проверки на реальных разговорах.

Если решений/задач нет, соответствующий массив должен быть пустым. Возможна ошибка
классификации (например, обсуждение без решения попало в «Решения»), хотя текст
останется точной выдержкой. Выдержка не является гарантией правильной интерпретации.

## Проверка на компьютере

```bash
python tools/test_summary.py
```

Первый запуск скачивает закреплённые compiler/test JAR из Maven Central и
проверяет SHA-1. Android 4.1 stubs используются только для проверки совместимости
вызовов API при компиляции, не для запуска приложения. В тестах используются
фиктивные ответы тестового backend, они не входят в runtime реализации.

Проверено: **24 unit-теста** (структура, ссылки, контекст, длинная реплика,
отмена, закрытие движка, отмена другого файла в очереди). JNI проверен
компилятором по заголовкам закреплённой llama.cpp и собран в shared library
на Linux. Отдельный host smoke проверяет вызовы JNI и обработку некорректного GGUF:

```bash
python tools/test_summary.py --native-library-dir /path/to/host-library
```

Настоящий Qwen Q4_K_M проверен на Linux с ручным текстовым примером:

```bash
python tools/test_summary.py --native-library-dir /path/to/host-library \
  --model /path/to/qwen2.5-1.5b-instruct-q4_k_m.gguf \
  --transcript examples/summary_smoke/transcript.json
```

Отчёт — `artifacts/test-results/summary-host.json`, включает SHA-256 входа/модели,
реальный ответ, время и пик памяти процесса. Это проверка LLM по тексту, не тест ASR,
не настоящая запись для демо и не метрика телефона. На Linux native и ARM64 Android
сборки проверены отдельно; запуск модели на физическом Android ещё нужен.

## Следующий проверяемый результат команды

1. Включить подготовленные модели в сборку команды и установить GGUF на телефон.
2. В авиарежиме обработать настоящую запись с 2–4 голосами.
3. Проверить: `Done` доступен до конспекта, затем приходят название и пункты;
   ссылки ведут к реальным репликам; JSON транскрипции не изменился.
4. Измерить загрузку LLM, генерацию, полный сценарий и пик памяти по отдельности.
5. Проверить отмену/повторный запуск и длинную запись. Сохранить реальные числа для защиты.
