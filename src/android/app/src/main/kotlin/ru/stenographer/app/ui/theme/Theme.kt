package ru.stenographer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.stenographer.app.R
import ru.stenographer.app.data.ThemeMode

/*
 * Тема приложения. Цвета свои, без динамических цветов Android 12:
 * иначе цвета голосов смешаются с обоями пользователя.
 * Значения взяты из прототипа (design/prototype).
 */

private val Ink = Color(0xFF121212)
private val Mute = Color(0xFF6E6E73)

private val LightColors = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    background = Color.White, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF4F4F5), onSurfaceVariant = Mute,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEBEBED),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    secondaryContainer = Color(0xFFEBEBED), onSecondaryContainer = Ink,
    outline = Color(0xFFD4D4D8), outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFFF3B30), onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDEDED), onPrimary = Color(0xFF0E0E10),
    background = Color(0xFF0E0E10), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0E0E10), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1A1A1D), onSurfaceVariant = Color(0xFF9A9AA0),
    surfaceContainerLowest = Color(0xFF0A0A0B),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF1A1A1D),
    surfaceContainerHigh = Color(0xFF232327),
    surfaceContainerHighest = Color(0xFF2A2A2E),
    secondaryContainer = Color(0xFF2E2E33), onSecondaryContainer = Color(0xFFEDEDED),
    outline = Color(0xFF3A3A3F), outlineVariant = Color(0xFF2A2A2E),
    error = Color(0xFFFF5A52), onError = Color.White,
)

/** Цвета, которых нет в схеме Material: голоса, запись, подсветка найденного. */
@Immutable
data class ExtraColors(
    val voices: List<Color>,
    val record: Color,
    val highlight: Color,
    /** В тёмной теме узоры голосов светятся. */
    val glow: Boolean,
) {
    fun voice(index: Int): Color = voices[index.mod(voices.size)]
}

private val LightExtra = ExtraColors(
    voices = listOf(
        Color(0xFF2B59FF), Color(0xFFFF5A1F), Color(0xFF14A86E),
        Color(0xFF8E44FF), Color(0xFFE0399A), Color(0xFFC98A00),
    ),
    record = Color(0xFFFF3B30),
    highlight = Color(0xFFFFE27A),
    glow = false,
)

private val DarkExtra = ExtraColors(
    voices = listOf(
        Color(0xFF7C9BFF), Color(0xFFFF8A5C), Color(0xFF4FD39C),
        Color(0xFFB58CFF), Color(0xFFFF7FC4), Color(0xFFFFC94D),
    ),
    record = Color(0xFFFF5A52),
    highlight = Color(0xFF8A6B00),
    glow = true,
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

/** Geologica: переменный шрифт, нужные начертания задаём осью веса. */
@OptIn(ExperimentalTextApi::class)
private fun geologica(weight: Int) = Font(
    R.font.geologica,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geologica = FontFamily(
    geologica(300), geologica(340), geologica(400), geologica(500), geologica(600),
)

/** Для длинного русского текста: переносы по слогам и ровные строки абзаца. */
private val Paragraph = TextStyle(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(300), fontSize = 72.sp, letterSpacing = (-0.02).em),
    displayMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(400), fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(400), fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(600), fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(600), fontSize = 20.sp, lineHeight = 26.sp),
    headlineMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(600), fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em),
    titleLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(600), fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(500), fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(500), fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(340), fontSize = 17.sp, lineHeight = 25.sp).merge(Paragraph),
    bodyMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 21.sp).merge(Paragraph),
    bodySmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(400), fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(500), fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Geologica, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun StenographerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}

/** Короткий доступ к дополнительным цветам: Extra.colors.voice(1). */
object Extra {
    val colors: ExtraColors
        @Composable get() = LocalExtraColors.current
}

/** Фон диалогов: в светлой теме белый, как в прототипе, в тёмной чуть светлее фона. */
val dialogColor: Color
    @Composable get() = if (LocalExtraColors.current.glow) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background
