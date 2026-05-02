package ru.greemlab.neiro.ui.calendar

/**
 * Утилита для парсинга строковых данных о сессиях (учениках и доп. доходах).
 */
object SessionParser {
    
    /**
     * Проверяет, является ли запись подтвержденным занятием ("Имя|true").
     */
    fun isAttended(sessionString: String): Boolean {
        if (isExtra(sessionString)) return false
        val parts = sessionString.split("|")
        return parts.getOrNull(1)?.toBoolean() ?: false
    }

    /**
     * Проверяет, является ли запись специальной (интенсив, диагностика и т.д.).
     */
    fun isExtra(sessionString: String): Boolean {
        return sessionString.startsWith("__")
    }

    /**
     * Извлекает имя ученика из записи.
     */
    fun getName(sessionString: String): String {
        if (isExtra(sessionString)) return ""
        return sessionString.split("|")[0]
    }

    /**
     * Извлекает сумму дополнительного дохода из специальной записи.
     */
    fun getExtraAmount(sessionString: String): Double {
        if (!isExtra(sessionString)) return 0.0
        // Формат: __TYPE__:AMOUNT|attended ( attended обычно не используется для экстра)
        val valuePart = sessionString.split("|")[0]
        return valuePart.substringAfter(":").toDoubleOrNull() ?: 0.0
    }
}
