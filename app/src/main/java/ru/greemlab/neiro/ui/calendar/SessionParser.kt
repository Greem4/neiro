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
     * Проверяет, является ли запись интенсивом.
     */
    fun isIntensive(sessionString: String): Boolean {
        return sessionString.startsWith("__INTENSIVE__:")
    }

    /**
     * Проверяет, является ли запись диагностикой.
     */
    fun isDiagnostics(sessionString: String): Boolean {
        return sessionString.startsWith("__DIAGNOSTICS__:")
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
