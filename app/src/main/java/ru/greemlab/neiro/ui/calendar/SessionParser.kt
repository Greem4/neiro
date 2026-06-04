package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable

/**
 * Статус записи (синхронизация с YClients и локальное хранение).
 *
 *  - [EXPECTED] (0) — ожидание, «+», в деньги не входит
 *  - [CONFIRMED] (1) — подтвердил, что придёт, «галка», в деньги не входит
 *  - [CANCELLED] (2) — не пришёл / отказ, «−», в деньги не входит
 *  - [ARRIVED] (3) — пришёл, занятие проведено, в деньги входит
 */
enum class AttendanceStatus(val code: Int) {
    EXPECTED(0),
    CONFIRMED(1),
    CANCELLED(2),
    ARRIVED(3);

    /** Учитывается в заработке (только «пришёл»). */
    val countsTowardEarnings: Boolean get() = this == ARRIVED

    /** Приоритет при слиянии нескольких визитов одного клиента за день. */
    val mergePriority: Int
        get() = when (this) {
            ARRIVED -> 4
            CONFIRMED -> 3
            CANCELLED -> 2
            EXPECTED -> 1
        }

    companion object {
        fun fromCode(code: Int): AttendanceStatus = when (code) {
            1 -> CONFIRMED
            2 -> CANCELLED
            3 -> ARRIVED
            else -> EXPECTED
        }

        /** Коды YClients: -1 не пришёл, 0 ожидание, 1 пришёл, 2 подтвердил. */
        fun fromYClients(code: Int): AttendanceStatus = when (code) {
            -1 -> CANCELLED
            1 -> ARRIVED
            2 -> CONFIRMED
            else -> EXPECTED
        }

        fun resolveFromRecord(attendance: Int, visitAttendance: Int?): AttendanceStatus {
            val fromVisit = visitAttendance?.let { fromYClients(it) }
            val fromAttendance = fromYClients(attendance)
            return if (fromVisit == null) {
                fromAttendance
            } else {
                maxOf(fromVisit, fromAttendance, compareBy { it.mergePriority })
            }
        }

        /**
         * Старый формат `name|true` и ручной офлайн-ввод (в т.ч. будущее редактирование архива).
         * Даёт только EXPECTED/ARRIVED; для YClients и таймлайна — полный код статуса в строке.
         */
        fun fromBoolean(attended: Boolean): AttendanceStatus =
            if (attended) ARRIVED else EXPECTED
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
    /** Упрощённая отметка для ручной сериализации; при наличии кода в строке — см. [status]. */
    val attended: Boolean
    val status: AttendanceStatus get() = AttendanceStatus.fromBoolean(attended)

    /**
     * Проверяет, является ли запись «удалённой» (отменённой).
     * Такие записи не учитываются в статистике и счётчиках, но видны в списке дня.
     */
    fun isEffectivelyDeleted(): Boolean {
        val name = when (this) {
            is Student -> name
            is Extra -> name
        }
        return status == AttendanceStatus.CANCELLED ||
                name.startsWith("-") || name.startsWith("—") ||
                name.startsWith("–") || name.startsWith("−")
    }

    /** Учитывается в заработке: только статус «пришёл», без отменённых записей. */
    fun countsTowardEarnings(): Boolean =
        !isEffectivelyDeleted() && status.countsTowardEarnings

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
        val time: String = "",
        override val status: AttendanceStatus = AttendanceStatus.fromBoolean(attended),
    ) : Extra

    @Immutable
    data class Diagnostics(
        override val amount: Double,
        override val name: String,
        override val attended: Boolean,
        val time: String = "",
        override val status: AttendanceStatus = AttendanceStatus.fromBoolean(attended),
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

    /** Проверяет, является ли запись «удалённой» (отменённой). */
    fun isEffectivelyDeleted(raw: String): Boolean = parse(raw).isEffectivelyDeleted()

    /**
     * Один проход по сырой строке: учитывается ли в счётчике занятий на ячейке календаря.
     * (ученик или диагностика, не отменено)
     */
    fun countsAsCalendarLesson(raw: String): Boolean {
        val session = parse(raw)
        if (session.isEffectivelyDeleted()) return false
        return session is Session.Student || session is Session.Diagnostics
    }

    fun isVisibleIntensive(raw: String): Boolean =
        isIntensive(raw) && !isEffectivelyDeleted(raw)

    /** Обновляет код статуса в сырой строке, сохраняя остальные поля. */
    fun withStatus(raw: String, status: AttendanceStatus): String = when (val session = parse(raw)) {
        is Session.Student -> SessionFormat.serializeStudentExtended(
            name = session.name,
            status = status,
            time = session.time,
            phone = session.phone,
            comment = session.comment,
        )
        is Session.Intensive -> SessionFormat.serializeIntensive(
            price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            name = session.name.ifBlank { "Интенсив" },
            status = status,
            time = session.time,
        )
        is Session.Diagnostics -> SessionFormat.serializeDiagnostics(
            price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            name = session.name,
            status = status,
            time = session.time,
        )
    }

    /**
     * Парсит запись ученика.
     *
     * Форматы (обратная совместимость):
     *  - Старый: `name|attended` где attended = true/false
     *  - Новый:  `name|statusCode|time|phone|comment` где statusCode = 0/1/2/3
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
                attended = status.countsTowardEarnings,
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
        var status = AttendanceStatus.ARRIVED
        var time = ""
        if (firstSep >= 0) {
            val secondSep = raw.indexOf('|', startIndex = firstSep + 1)
            if (secondSep < 0) {
                name = raw.substring(firstSep + 1)
            } else {
                name = raw.substring(firstSep + 1, secondSep)
                val thirdSep = raw.indexOf('|', startIndex = secondSep + 1)
                if (thirdSep < 0) {
                    parseExtraStatusField(raw.substring(secondSep + 1))?.let { (a, s) ->
                        attended = a
                        status = s
                    } ?: run {
                        attended = raw.substring(secondSep + 1).toBooleanStrictOrNullCompat() ?: true
                        status = AttendanceStatus.fromBoolean(attended)
                    }
                } else {
                    parseExtraStatusField(raw.substring(secondSep + 1, thirdSep))?.let { (a, s) ->
                        attended = a
                        status = s
                    } ?: run {
                        attended = raw.substring(secondSep + 1, thirdSep).toBooleanStrictOrNullCompat() ?: true
                        status = AttendanceStatus.fromBoolean(attended)
                    }
                    time = raw.substring(thirdSep + 1)
                }
            }
        }
        return if (intensive) {
            Session.Intensive(amount, name, attended, time, status)
        } else {
            Session.Diagnostics(amount, name, attended, time, status)
        }
    }

    private fun String.toBooleanStrictOrNullCompat(): Boolean? = when (this) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /** Третье поле экстра-записи: `true`/`false` или код статуса 0–3. */
    private fun parseExtraStatusField(field: String): Pair<Boolean, AttendanceStatus>? {
        val code = field.toIntOrNull() ?: return null
        val status = AttendanceStatus.fromCode(code)
        return status.countsTowardEarnings to status
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

    /** Расширенный формат: `name|statusCode|time|phone|comment`. */
    fun serializeStudentExtended(
        name: String,
        status: AttendanceStatus,
        time: String = "",
        phone: String = "",
        comment: String = "",
    ): String = "$name|${status.code}|$time|$phone|$comment"

    fun serializeIntensive(price: String, name: String, attended: Boolean, time: String = ""): String =
        serializeIntensive(price, name, AttendanceStatus.fromBoolean(attended), time)

    fun serializeIntensive(
        price: String,
        name: String,
        status: AttendanceStatus,
        time: String = "",
    ): String {
        val base = "$INTENSIVE_PREFIX$price|$name|${status.code}"
        return if (time.isNotBlank()) "$base|$time" else base
    }

    fun serializeDiagnostics(price: String, name: String, attended: Boolean, time: String = ""): String =
        serializeDiagnostics(price, name, AttendanceStatus.fromBoolean(attended), time)

    fun serializeDiagnostics(
        price: String,
        name: String,
        status: AttendanceStatus,
        time: String = "",
    ): String = "$DIAGNOSTICS_PREFIX$price|$name|${status.code}|$time"
}
