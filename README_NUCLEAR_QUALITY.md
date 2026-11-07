# Дополнение команды: проверка качества

Проверка итогового экспорта и текущих Android-замеров роли 2.
Python 3.10+, без сторонних пакетов и без сети.

Прочитайте `docs/NUCLEAR_QUALITY.md` — запуск и интерпретация результатов.
Сверьте `docs/NUCLEAR_REQUIREMENTS.md` с последними ответами организаторов.

```bash
python -m unittest discover -s tests -p test_nuclear_quality.py -v
python tools/nuclear_quality.py validate exported.json
python tools/nuclear_quality.py score --reference reference.txt --hypothesis exported.json --output reports/test_2.json
python tools/nuclear_quality.py score-bench --reference reference.txt --benchmark bench_test_2.m4a.4.json --output reports/test_2_asr.json
```

Проверено 7 ноября 2026: 36 тестов прошли. В одном из них дополнительно
сверены расстояния редактирования для всех 225 пар коротких последовательностей
с независимой матричной реализацией. CLI проверен на искусственном примере:
одна замена из четырёх слов даёт WER 25%, переданные длительности 1.5/3 дают RTF 0.5.
Эти числа не являются результатами модели.

Состав: CLI, тесты, JSON Schema, искусственные примеры, описание методики
и перечень официальных требований с номерами слайдов.

`score-bench` читает формат `MainActivity.kt` из ветки
`task-2-speech-recognition` (проверено по коммиту `1dc8c38`), сохраняет метаданные
телефона и потоков, пересчитывает RTF из длительностей и сообщает о несогласованных
замерах. Его ASR-время не выдаётся за время полной цепочки с диаризацией и LLM.

Инструмент запускается на компьютере для анализа файлов; основная задача
приложения по-прежнему должна выполняться локально на Android.
