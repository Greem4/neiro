package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable

/**
 * Статус записи из YClients.
 *
 *  - [EXPECTED] (0) — ожидает подтверждения, можно зачислить деньги
 *  - [CONFIRMED] (1) — подтверждён, придёт
 *  - [CANCELLED] (2) — не будет, не учитываем в деньгах
 */
enum class AttendanceStatus(val code: Int) {
    EXPECTED(0),   // + зелёный, можно зачислять
    CONFIRMED(1),  // ✓ оранжевый, подтверждён
    CANCELLED(2);  // - красный, не будет

    companion object {
        fun fromCode(code: Int): AttendanceStatus = when (code) {
            1 -> CONFIRMED
            2 -> CANCELLED
            else -> EXPECTED
        }

        fun fromBoolean(attended: Boolean): AttendanceStatus =
            if (attended) CONFIRMED else EXPECTED
    }
}

/**
 * Разобранное представление одной записи дневника.
 *
 * Сериализованный формат (обратно-совместимый со старыми данными):
 *  - Обычное занятие: `name|attended` или расширенный `name|status|time|phone|comment`
 *  - Интенсив:        `__INTENSIVE__:amount|name|attended`
 *  - Диагностика:     `__DIAGNOSTICS__:amount|name|attended`
 */
@Immutable
sealed interface Session {
    val attended: Boolean
    val status: AttendanceStatus get() = AttendanceStatus.fromBoolean(attended)

    @Immutable
    data class Student(
        val name: String,
        override val attended: Boolean,
        val time: String = "",        // Например "10:00-10:50"
        val phone: String = "",       // Телефон клиента
        val comment: String = "",     // Комментарий к записи
        override val status: AttendanceStatus = AttendanceStatus.fromBoolean(attended),
    ) : Session

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
 * Утилита парсинга строковых записей о сессиях. Без аллокаций и без regex.
 *
 * Все константы префиксов вынесены в [SessionFormat] — это позволяет
 * другим модулям сериализовать данные обратно без дублирования строк.
 */
object SessionParser {
    fun parse(raw: String): Session = when {
        raw.startsWith(SessionFormat.INTENSIVE_PREFIX) ->
            parseExtra(raw, SessionFormat.INTENSIVE_PREFIX.length, intensive = true)

        raw.startsWith(SessionFormat.DIAGNOSTICS_PREFIX) ->
            parseExtra(raw, SessionFormat.DIAGNOSTICS_PREFIX.length, intensive = false)

        else -> parseStudent(raw)
    }

    fun isExtra(raw: String): Boolean =
        raw.startsWith(SessionFormat.INTENSIVE_PREFIX) ||
            raw.startsWith(SessionFormat.DIAGNOSTICS_PREFIX)

    fun isIntensive(raw: String): Boolean = raw.startsWith(SessionFormat.INTENSIVE_PREFIX)

    fun isDiagnostics(raw: String): Boolean = raw.startsWith(SessionFormat.DIAGNOSTICS_PREFIX)

    /** Сумма доп. дохода. Возвращает 0.0 для обычных учеников. */
    fun getExtraAmount(raw: String): Double {
        if (!isExtra(raw)) return 0.0
        val colon = raw.indexOf(':')
        if (colon < 0) return 0.0
        val sep = raw.indexOf('|', startIndex = colon + 1)
        val end = if (sep == -1) raw.length else sep
        return raw.substring(colon + 1, end).toDoubleOrNull() ?: 0.0
    }

    /**
     * Проверяет, была ли сессия (включая интенсив/диагностику) посещена.
     * Для старых записей экстра-сессий значение по умолчанию — true.
     */
    fun isAttended(raw: String): Boolean = when (val s = parse(raw)) {
        is Session.Extra -> s.attended
        is Session.Student -> s.attended
    }

    /** Возвращает статус attendance из сырой строки. */
    fun getStatus(raw: String): AttendanceStatus = parse(raw).status

    /**
     * Парсит запись ученика.
     *
     * Форматы (обратная совместимость):
     *  - Старый: `name|attended` где attended = true/false
     *  - Новый:  `name|statusCode|time|phone|comment` где statusCode = 0/1/2
     */
    private fun parseStudent(raw: String): Session.Student {
        val parts = raw.split('|')
        if (parts.isEmpty()) return Session.Student("", attended = false)

        val name = parts[0]

        // Только имя
        if (parts.size == 1) {
            return Session.Student(name, attended = false)
        }

        // Проверяем второй элемент: это boolean (старый формат) или число (новый формат)?
        val second = parts[1]
        val statusCode = second.toIntOrNull()

        return if (statusCode != null) {
            // Новый формат: name|statusCode|time|phone|comment
            val status = AttendanceStatus.fromCode(statusCode)
            val time = parts.getOrNull(2).orEmpty()
            val phone = parts.getOrNull(3).orEmpty()
            val comment = parts.getOrNull(4).orEmpty()
            Session.Student(
                name = name,
                attended = status == AttendanceStatus.CONFIRMED,
                time = time,
                phone = phone,
                comment = comment,
                status = status,
            )
        } else {
            // Старый формат: name|attended (true/false)
            val attended = second.toBooleanStrictOrNullCompat() ?: false
            Session.Student(
                name = name,
                attended = attended,
                status = AttendanceStatus.fromBoolean(attended),
            )
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

/**
 * Константы и хелперы сериализации сессий в строку. Используются и при парсинге,
 * и при сериализации в [ru.greemlab.neiro.ui.components.DayDetailsDialog].
 */
object SessionFormat {
    const val INTENSIVE_PREFIX = "__INTENSIVE__:"
    const val DIAGNOSTICS_PREFIX = "__DIAGNOSTICS__:"

    /** Старый формат (для обратной совместимости). */
    fun serializeStudent(name: String, attended: Boolean): String = "$name|$attended"

    /**
     * Новый расширенный формат записи ученика.
     * Формат: `name|statusCode|time|phone|comment`
     */
    fun serializeStudentExtended(
        name: String,
        status: AttendanceStatus,
        time: String = "",
        phone: String = "",
        comment: String = "",
    ): String = "$name|${status.code}|$time|$phone|$comment"

    fun serializeIntensive(price: String, name: String, attended: Boolean): String =
        "$INTENSIVE_PREFIX$price|$name|$attended"

    fun serializeDiagnostics(price: String, name: String, attended: Boolean): String =
        "$DIAGNOSTICS_PREFIX$price|$name|$attended"
}
