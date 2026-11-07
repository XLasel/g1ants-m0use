# Обработка файла (`:pipeline`)

`TranscriptionPipeline` обрабатывает один аудиофайл целиком и отдаёт поток событий `PipelineEvent` из `:core`. Интерфейс подписывается на поток и рисует; ни модели, ни модули речи и диаризации ему напрямую не нужны.

```kotlin
val pipeline = TranscriptionPipeline(context, PipelineConfig(threads = 4, summaryModel = ggufFileOrNull))
pipeline.process(fileId, uri).collect { event ->
    when (event) {
        is PipelineEvent.StageProgress -> { /* этап и 0..1 */ }
        is PipelineEvent.Decoded -> { /* длительность и огибающая громкости по 50 мс */ }
        is PipelineEvent.SpeakerFound -> { /* новый голос: рисуем узор */ }
        is PipelineEvent.Segments -> { /* отрезки голосов, перекрытия сохранены; последняя порция final = true */ }
        is PipelineEvent.Words -> { /* слова очередного куска с таймкодами */ }
        is PipelineEvent.Done -> { /* реплики; экспорт: TranscriptJson.encode(event.utterances) */ }
        is PipelineEvent.Summary -> { /* название, конспект, пункты со ссылками на моменты */ }
        is PipelineEvent.Failed -> { /* event.reason можно показать пользователю */ }
        is PipelineEvent.SpeakerFeatures -> { /* тон, интонация и громкость голоса для узора */ }
    }
}
```

## Что происходит внутри

| Этап (`Stage`) | Что делает | События |
|---|---|---|
| `DECODE` | Чтение файла в 16 кГц моно (`:speech`) | `Decoded` |
| `DIARIZE` | Установка и загрузка Nemotron, диаризация кусками по 27 с (`:diarization`); затем тон и громкость каждого голоса | `SpeakerFound`, `Segments` по ходу; `SpeakerFeatures` в конце этапа |
| `RECOGNIZE` | Куски речи по диаризации, установка и загрузка GigaAM, распознавание (`:speech`) | `Words` после каждого куска |
| `MERGE` | Спикер каждому слову, реплики; номера `speaker_N` те же, что у найденных голосов | `Done` |
| `SUMMARY` | Только если задана `summaryModel`: название и конспект (`:summary-android`) | `Summary` |

`StageProgress` приходит в начале (0) и в конце (1) каждого этапа и не чаще 10 раз в секунду между ними.

## Договорённости

- **Один файл за раз.** Очередь держит вызывающий код: следующий `process` после окончания предыдущего потока. Модели загружаются по очереди и закрываются после своего этапа.
- **Поток холодный.** Обработка начинается при подписке; отмена подписки прерывает её между этапами и кусками распознавания. Сама диаризация одного файла не прерывается на середине (8 с на 3 минуты звука на Pixel 7 Pro).
- **Ошибки — событием `Failed`**, исключение наружу не выбрасывается. Ошибка конспекта не трогает расшифровку: `Done` уже отдан, а `Summary` просто не придёт.
- **Первый запуск** копирует модели из APK во внутреннюю память (около 330 МБ, несколько секунд), это входит в этапы `DIARIZE` и `RECOGNIZE`.
- **Приложению** нужны `androidResources { noCompress += listOf("onnx", "gguf") }`, только `arm64-v8a` (см. `packaging` в `bench/build.gradle.kts`) и `android:largeHeap="true"` в манифесте.

## Модели

Все модели лежат в APK и при первом запуске копируются во внутреннюю память, интернет не нужен:

| Модель | Где | Кладёт |
|---|---|---|
| GigaAM v3, 222 МБ | assets `:speech` | `../scripts/prepare_speech.sh` |
| Nemotron, 106 МБ | assets `:pipeline` | `../scripts/prepare_diarization.sh` |
| Qwen2.5-1.5B Q4_K_M, 1,1 ГБ | assets `:pipeline` | `../scripts/prepare_summary.sh` |

Копирование (около 1,4 ГБ) можно сделать заранее на первом запуске приложения: `pipeline.installModels { progress -> }`. Иначе оно пройдёт на первой обработке. Если Qwen в APK не положен, конспект не делается; свой GGUF можно передать в `PipelineConfig.summaryModel` (импорт — `GgufModelStore` из `:summary-android`). Конспект отключается `PipelineConfig(summaryEnabled = false)`.

Приложению нужно `androidResources { noCompress += listOf("onnx", "gguf") }`: без этого модели сжимаются в APK и копируются в разы дольше.
