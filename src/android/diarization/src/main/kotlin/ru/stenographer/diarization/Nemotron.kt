package ru.stenographer.diarization

import kotlin.math.floor

/** Шаг вероятностей диаризации: 10 мс. */
const val FRAME_MS = 10L

/** Сколько спикеров различает Nemotron. */
const val NUM_SPEAKERS = 8

/**
 * Параметры Nemotron 3 Diarization, перенесённые из onnx_diarizer.py.
 * Менять их нельзя: модель обучена именно с такими признаками и чанками.
 */
internal object Nemotron {
    // Признаки
    const val N_FFT = 512
    const val WIN_LENGTH = 400
    const val HOP_LENGTH = 160
    const val PREEMPHASIS = 0.97f
    const val LOG_GUARD = 5.9604645E-8f // 2^-24
    const val MEL_BINS = 128
    const val SPECTRUM_BINS = N_FFT / 2 + 1

    // Чанки в кадрах кодировщика (80 мс)
    const val SUBSAMPLING = 8
    const val EMBED_DIM = 512
    const val CHUNK_LENGTH = 340
    const val CHUNK_RIGHT_CONTEXT = 40
    const val FIFO_LENGTH = 40
    const val CACHE_UPDATE_PERIOD = 300

    // Политика кэша спикеров
    const val CACHE_LENGTH = 264
    const val SILENCE_FRAMES = 1
    const val SCORE_THRESHOLD = 0.25f
    const val LATEST_BOOST = 0.05f
    const val BUDGET = CACHE_LENGTH / NUM_SPEAKERS - SILENCE_FRAMES
    val MIN_POSITIVE = floor(BUDGET * 0.5).toInt()
    val STRONG_BOOSTED = floor(BUDGET * 0.75).toInt()
    val WEAK_BOOSTED = floor(BUDGET * 1.5).toInt()

    // Файлы модели
    const val EMBED_FILE = "embed.onnx"
    const val STEP_FILE = "step_int8.onnx"
    const val MEL_FILTERS_FILE = "mel_filters.bin"
    const val SILENCE_FILE = "silence_embeds.bin"
    val MODEL_FILES = listOf(EMBED_FILE, STEP_FILE, MEL_FILTERS_FILE, SILENCE_FILE)
}
