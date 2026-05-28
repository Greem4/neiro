package ru.greemlab.neiro.ui.util

import java.util.Locale

/** Локаль для русскоязычного форматирования сумм и дат. */
val RU_LOCALE: Locale = Locale.forLanguageTag("ru")

/**
 * Форматирует сумму как `1 234 ₽` без дробной части. Использует неразрывный пробел
 * стандартного русского формата (фактически — обычный, но визуально равный).
 */
fun formatRubles(value: Double): String {
    val rounded = value.toLong()
    return String.format(RU_LOCALE, "%,d ₽", rounded).replace(',', ' ')
}

/** Краткое форматирование (без символа валюты), без дробной части. */
fun formatNumber(value: Double): String = String.format(RU_LOCALE, "%.0f", value)

/**
 * Форматирует строку цифр (11 знаков, начиная с 7) в читаемый формат: +7 (XXX) XXX-XX-XX.
 */
fun formatPhoneForUi(digits: String): String {
    if (digits.length != 11) return digits
    return "+${digits[0]} (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7, 9)}-${digits.substring(9, 11)}"
}
