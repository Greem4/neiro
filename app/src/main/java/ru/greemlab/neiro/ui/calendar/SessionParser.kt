package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable

/**
 * Разобранное представление одной записи дневника.
 *
 * Сериализованный формат остался обратно-совместимым со старыми данными:
 *  - Обычное занятие: `name|attended`
 *  - Интенсив:        `__INTENSIVE__:amount|name|attended`
 *  - Диагностика:     `__DIAGNOSTICS__:amount|name|attended`
 */
@Immutable
sealed interface Session {
    val attended: Boolean

    @Immutable
    data class Student(val name: String, override val attended: Boolean) : Session

    @Immutable
    sealed interface Extra : Session {
        val amount: Double
        val name: String
    }

    @Immutable
    data class Intensive(
        override val amount: Double,
        override val name: String,
        override val attended: Boolean,
    ) : Extra

    @Immutable
    data class Diagnostics(
        override val amount: Double,
        override val name: String,
        override val attended: Boolean,
    ) : Extra
}

/**
 * Утилита для парсинга строковых данных о сессиях (учениках и доп. доходах).
 * Парсит один раз без лишних аллокаций и регулярных выражений.
 */
object SessionParser {
    private const val INTENSIVE_PREFIX = "__INTENSIVE__:"
    private const val DIAGNOSTICS_PREFIX = "__DIAGNOSTICS__:"
    private const val EXTRA_MARKER = "__"

    fun parse(raw: String): Session = when {
        raw.startsWith(INTENSIVE_PREFIX) -> parseExtra(raw, INTENSIVE_PREFIX.length, intensive = true)
        raw.startsWith(DIAGNOSTICS_PREFIX) -> parseExtra(raw, DIAGNOSTICS_PREFIX.length, intensive = false)
        else -> parseStudent(raw)
    }

    fun isExtra(raw: String): Boolean = raw.startsWith(EXTRA_MARKER)

    fun isIntensive(raw: String): Boolean = raw.startsWith(INTENSIVE_PREFIX)

    fun isDiagnostics(raw: String): Boolean = raw.startsWith(DIAGNOSTICS_PREFIX)

    /** Сумма доп. дохода. Возвращает 0.0 для обычных учеников. */
    fun getExtraAmount(raw: String): Double {
        if (!raw.startsWith(EXTRA_MARKER)) return 0.0
        val colon = raw.indexOf(':')
        if (colon < 0) return 0.0
        val sep = raw.indexOf('|', startIndex = colon + 1)
        val end = if (sep == -1) raw.length else sep
        return raw.substring(colon + 1, end).toDoubleOrNull() ?: 0.0
    }

    /**
     * Проверяет, была ли сессия (включая интенсив/диагностику) посещена.
     * Для старых записей экстра-сессий значение по умолчанию = true.
     */
    fun isAttended(raw: String): Boolean = when (val s = parse(raw)) {
        is Session.Extra -> s.attended
        is Session.Student -> s.attended
    }

    private fun parseStudent(raw: String): Session.Student {
        val sep = raw.indexOf('|')
        return if (sep < 0) {
            Session.Student(raw, attended = false)
        } else {
            val name = raw.substring(0, sep)
            val attended = raw.substring(sep + 1).toBooleanStrictOrNullCompat() ?: false
            Session.Student(name, attended)
        }
    }

    private fun parseExtra(raw: String, payloadStart: Int, intensive: Boolean): Session.Extra {
        // Формат payload: amount|name|attended
        val firstSep = raw.indexOf('|', startIndex = payloadStart)
        val amount = if (firstSep < 0) {
            raw.substring(payloadStart).toDoubleOrNull() ?: 0.0
        } else {
            raw.substring(payloadStart, firstSep).toDoubleOrNull() ?: 0.0
        }
        var name = ""
        var attended = true // Старые записи без флага считаем посещёнными.
        if (firstSep >= 0) {
            val secondSep = raw.indexOf('|', startIndex = firstSep + 1)
            if (secondSep < 0) {
                name = raw.substring(firstSep + 1)
            } else {
                name = raw.substring(firstSep + 1, secondSep)
                attended = raw.substring(secondSep + 1).toBooleanStrictOrNullCompat() ?: true
            }
        }
        return if (intensive) {
            Session.Intensive(amount, name, attended)
        } else {
            Session.Diagnostics(amount, name, attended)
        }
    }

    private fun String.toBooleanStrictOrNullCompat(): Boolean? = when (this) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
