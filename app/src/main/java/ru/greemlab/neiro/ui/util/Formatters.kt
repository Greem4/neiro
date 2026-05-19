package ru.greemlab.neiro.ui.util

import java.util.Locale

/** Локаль для русскоязычного форматирования сумм и дат. */
val RU_LOCALE: Locale = Locale.forLanguageTag("ru")

/** Форматирует сумму как `1 234 ₽` без дробной части. */
fun formatRubles(value: Double): String {
    val rounded = value.toLong()
    return String.format(RU_LOCALE, "%,d ₽", rounded).replace(',', ' ')
}

/** Краткое форматирование (без символа валюты), без дробной части. */
fun formatNumber(value: Double): String = String.format(RU_LOCALE, "%.0f", value)
