# Дополнение команды: проверка качества

Проверка итогового экспорта, старых ASR-замеров и текущего Android pipeline-бенча.
Python 3.10+, без сторонних пакетов и без сети.

Прочитайте `docs/NUCLEAR_QUALITY.md` — запуск и интерпретация результатов.
Сверьте `docs/NUCLEAR_REQUIREMENTS.md` с последними ответами организаторов.

```bash
python -m unittest discover -s tests -p test_nuclear_quality.py -v
python tools/nuclear_quality.py validate exported.json
python tools/nuclear_quality.py score --reference reference.txt --hypothesis exported.json --output reports/test_2.json
python tools/nuclear_quality.py score-bench --reference reference.txt --benchmark bench_test_2.m4a.4.json --output reports/test_2_asr.json
```

Проверено 7 ноября 2026: 41 тест прошёл. В одном из них дополнительно
сверены расстояния редактирования для всех 225 пар коротких последовательностей
с независимой матричной реализацией. CLI проверен на искусственном примере:
одна замена из четырёх слов даёт WER 25%, переданные длительности 1.5/3 дают RTF 0.5.
Эти числа не являются результатами модели.

Состав: CLI, тесты, JSON Schema, искусственные примеры, описание методики
и перечень официальных требований с номерами слайдов.

`score-bench` сохраняет метаданные телефона и потоков и пересчитывает RTF.
В pipeline-формате `transcription_ms` / `rtf` относятся к готовой расшифровке,
`processing_ms` / `full_rtf` — к завершению Flow, включая попытку LLM-конспекта.
Старый ASR-формат без `transcription_ms` поддерживается отдельно. Неуспешный
прогон основной обработки не получает оценку успешного результата.

Инструмент запускается на компьютере для анализа файлов; основная задача
приложения по-прежнему должна выполняться локально на Android.
