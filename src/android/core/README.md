# Общие модели (`:core`)

Чистый Kotlin без Android, от него зависят все модули и интерфейс.

| Файл | Что внутри |
|---|---|
| `Models.kt` | `Word`, `Segment`, `Utterance`, `SummaryPoint`, `Stage`, `SAMPLE_RATE` |
| `PipelineEvent.kt` | События обработки для интерфейса по контракту из чекпоинта команды |
| `TranscriptJson.kt` | Экспорт реплик в JSON задания: `{"transcript": [{"speaker_id", "start_ms", "end_ms", "text"}]}` |

Договорённости: время в миллисекундах (`Long`) от начала записи, спикеры `speaker_1`, `speaker_2`… по порядку первого появления, те же id в JSON. Отрезки разных голосов могут пересекаться. `Utterance.words` нужны интерфейсу для подсветки слова и в JSON не попадают.

`Word` в `:speech` — это `typealias` на `core.Word`, поэтому старый код роли 2 не менялся. `SummaryPoint` пока продублирован в `:summary` (роль 4): `:pipeline` переводит один в другой.
