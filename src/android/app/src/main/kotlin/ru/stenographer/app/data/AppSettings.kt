package ru.stenographer.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Тема: как в системе, всегда светлая или всегда тёмная. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Настройки приложения. Их несколько, поэтому хватает SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM),
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _summary = MutableStateFlow(prefs.getBoolean("summary", true))
    /** Делать ли конспект и название локальной моделью после расшифровки. */
    val summary: StateFlow<Boolean> = _summary.asStateFlow()
    val summaryEnabled: Boolean get() = _summary.value

    private val _exportTxt = MutableStateFlow(prefs.getBoolean("export_txt", false))
    /** Сохранять рядом с JSON ещё и текст для чтения глазами. */
    val exportTxt: StateFlow<Boolean> = _exportTxt.asStateFlow()

    fun setExportTxt(on: Boolean) {
        _exportTxt.value = on
        prefs.edit().putBoolean("export_txt", on).apply()
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setSummary(on: Boolean) {
        _summary.value = on
        prefs.edit().putBoolean("summary", on).apply()
    }
}
