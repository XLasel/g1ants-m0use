package ru.stenographer.app.ui

/** Короткие слова, после которых нельзя переносить строку: «в доме», а не «в» в конце строки. */
private val SHORT = Regex("(?<=^|\\s)([а-яёА-ЯЁ]{1,2}|без|для|над|под|при|про|что|как|это|все|его|еще|ещё)\\s", RegexOption.IGNORE_CASE)

/** Ставит неразрывный пробел после предлогов, союзов и коротких слов. */
fun nbsp(text: String): String = SHORT.replace(text) { it.groupValues[1] + " " }
