package ru.greemlab.neiro.ui.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Локаль для русскоязычного форматирования сумм и дат. */
val RU_LOCALE: Locale = Locale.forLanguageTag("ru")

/** Неразрывный пробел: держит сумму и знак рубля одним куском при переносе строки. */
const val NBSP = '\u00A0'

// ICU в разных версиях JDK группирует разряды для ru по-своему, поэтому берём
// символы явно. Разделитель — неразрывный пробел: при увеличенном системном
// шрифте обычный пробел давал перенос внутри суммы («33 060» и «₽» на разных строках).
private val rublesFormat = DecimalFormat(
    "#,##0",
    DecimalFormatSymbols(RU_LOCALE).apply { groupingSeparator = NBSP },
)

/** Форматирует сумму как `1 234 ₽` (неразрывными пробелами) без дробной части. */
fun formatRubles(value: Double): String = "${rublesFormat.format(value.toLong())}$NBSP₽"

/** Краткое форматирование (без символа валюты), без дробной части. */
fun formatNumber(value: Double): String = String.format(RU_LOCALE, "%.0f", value)

/**
 * Форматирует строку цифр (11 знаков, начиная с 7) в читаемый формат: +7 (XXX) XXX-XX-XX.
 */
fun formatPhoneForUi(digits: String): String {
    if (digits.length != 11) return digits
    return "+${digits[0]} (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7, 9)}-${digits.substring(9, 11)}"
}
