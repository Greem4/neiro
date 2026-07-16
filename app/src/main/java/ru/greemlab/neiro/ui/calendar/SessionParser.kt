package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable
import java.time.LocalDate

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
 *  - Интенсив:        `__INTENSIVE__:amount|name|status|time|children`
 *                       amount: `5600` или `=5600` (фиксированная сумма вручную)
 *                       children: `имя|код;;имя2|код` (опционально)
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
    data class IntensiveChild(
        val name: String,
        val status: AttendanceStatus = AttendanceStatus.EXPECTED,
        val phone: String = "",
        val comment: String = "",
    )

    @Immutable
    data class Intensive(
        override val amount: Double,
        override val name: String,
        override val attended: Boolean,
        val time: String = "",
        val children: List<IntensiveChild> = emptyList(),
        override val status: AttendanceStatus = AttendanceStatus.fromBoolean(attended),
        /** true — сумма задана вручную целиком; false — считать по ставке × дети (API). */
        val amountFixed: Boolean = false,
    ) : Extra {
        override fun isEffectivelyDeleted(): Boolean {
            if (children.isNotEmpty()) {
                return children.all { child ->
                    child.status == AttendanceStatus.CANCELLED ||
                        child.name.startsWith("-") || child.name.startsWith("—") ||
                        child.name.startsWith("–") || child.name.startsWith("−")
                }
            }
            return status == AttendanceStatus.CANCELLED ||
                name.startsWith("-") || name.startsWith("—") ||
                name.startsWith("–") || name.startsWith("−")
        }

        override fun countsTowardEarnings(): Boolean {
            if (children.isNotEmpty()) {
                return children.any { it.status.countsTowardEarnings }
            }
            return !isEffectivelyDeleted() && status.countsTowardEarnings
        }
    }

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
        return SessionFormat.parseIntensivePriceField(raw.substring(colon + 1, end)).first
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

    /** Число занятий на день с учётом детей интенсива (без дублей в слоте интенсива). */
    fun countCalendarLessons(sessions: List<String>): Int {
        val parsed = sessions.map(::parse)
        val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)
        return parsed.count { session ->
            when (session) {
                is Session.Student ->
                    !session.isEffectivelyDeleted() &&
                        !isStudentCoveredByIntensive(session, intensiveChildrenByTime)
                is Session.Diagnostics -> !session.isEffectivelyDeleted()
                else -> false
            }
        }
    }

    /**
     * Возвращает строку для сетки календаря.
     *
     * Правила отображения:
     * 1. Если до дня > 1 дня (далёкое будущее) — общее число потенциальных занятий (кроме отмен).
     * 2. Если сегодня, завтра или в прошлом — только подтверждённые/проведённые занятия.
     */
    fun formatCalendarCounts(sessions: List<String>, date: LocalDate, today: LocalDate): String {
        val parsed = sessions.map(::parse)
        val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)
        var confirmed = 0
        var pending = 0
        parsed.forEach { session ->
            val isLesson = when (session) {
                is Session.Student -> !session.isEffectivelyDeleted() &&
                    !isStudentCoveredByIntensive(session, intensiveChildrenByTime)
                is Session.Diagnostics -> !session.isEffectivelyDeleted()
                else -> false
            }
            if (isLesson) {
                if (session.status == AttendanceStatus.EXPECTED) {
                    pending++
                } else {
                    confirmed++
                }
            }
        }

        val isFutureFar = date.isAfter(today.plusDays(1))

        return if (isFutureFar) {
            (confirmed + pending).toString()
        } else {
            confirmed.toString()
        }
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
            price = SessionFormat.intensivePriceField(session.amount, session.amountFixed),
            name = session.name.ifBlank { "Интенсив" },
            status = status,
            time = session.time,
            children = session.children,
            amountFixed = session.amountFixed,
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
        val payload = raw.substring(payloadStart)
        if (intensive) {
            return parseIntensivePayload(payload)
        }
        return parseDiagnosticsPayload(payload)
    }

    private fun parseIntensivePayload(payload: String): Session.Intensive {
        val segments = payload.split("|", limit = 5)
        val (amount, amountFixed) = SessionFormat.parseIntensivePriceField(segments.getOrNull(0).orEmpty())
        val name = segments.getOrNull(1).orEmpty()
        val statusField = segments.getOrNull(2).orEmpty()
        val time = segments.getOrNull(3).orEmpty()
        val childrenRaw = segments.getOrNull(4).orEmpty()
        val (attended, status) = when {
            statusField.isEmpty() -> true to AttendanceStatus.ARRIVED
            else -> parseExtraStatusField(statusField)
                ?: run {
                    val bool = statusField.toBooleanStrictOrNullCompat() ?: true
                    bool to AttendanceStatus.fromBoolean(bool)
                }
        }
        val children = parseIntensiveChildren(childrenRaw)
        return Session.Intensive(amount, name, attended, time, children, status, amountFixed)
    }

    private fun parseIntensiveChildren(raw: String): List<Session.IntensiveChild> {
        if (raw.isBlank()) return emptyList()
        return raw.split(INTENSIVE_CHILD_SEP).mapNotNull { chunk ->
            if (chunk.isBlank()) return@mapNotNull null
            val parts = chunk.split("|", limit = 4)
            val childName = parts.getOrNull(0).orEmpty()
            if (childName.isBlank()) return@mapNotNull null
            val childStatus = parts.getOrNull(1)?.toIntOrNull()?.let(AttendanceStatus::fromCode)
                ?: AttendanceStatus.EXPECTED
            Session.IntensiveChild(
                name = childName,
                status = childStatus,
                phone = parts.getOrNull(2).orEmpty(),
                comment = parts.getOrNull(3).orEmpty(),
            )
        }
    }

    private fun parseDiagnosticsPayload(payload: String): Session.Diagnostics {
        val firstSep = payload.indexOf('|')
        val amount = if (firstSep < 0) {
            payload.toDoubleOrNull() ?: 0.0
        } else {
            payload.substring(0, firstSep).toDoubleOrNull() ?: 0.0
        }
        var name = ""
        var attended = true
        var status = AttendanceStatus.ARRIVED
        var time = ""
        if (firstSep >= 0) {
            val secondSep = payload.indexOf('|', startIndex = firstSep + 1)
            if (secondSep < 0) {
                name = payload.substring(firstSep + 1)
            } else {
                name = payload.substring(firstSep + 1, secondSep)
                val thirdSep = payload.indexOf('|', startIndex = secondSep + 1)
                if (thirdSep < 0) {
                    parseExtraStatusField(payload.substring(secondSep + 1))?.let { (a, s) ->
                        attended = a
                        status = s
                    } ?: run {
                        attended = payload.substring(secondSep + 1).toBooleanStrictOrNullCompat() ?: true
                        status = AttendanceStatus.fromBoolean(attended)
                    }
                } else {
                    parseExtraStatusField(payload.substring(secondSep + 1, thirdSep))?.let { (a, s) ->
                        attended = a
                        status = s
                    } ?: run {
                        attended = payload.substring(secondSep + 1, thirdSep).toBooleanStrictOrNullCompat() ?: true
                        status = AttendanceStatus.fromBoolean(attended)
                    }
                    time = payload.substring(thirdSep + 1)
                }
            }
        }
        return Session.Diagnostics(amount, name, attended, time, status)
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

    private const val INTENSIVE_CHILD_SEP = ";;"
}

/**
 * Константы и хелперы сериализации сессий в строку. Используются и при парсинге,
 * и при сериализации в [ru.greemlab.neiro.ui.components.DayDetailsDialog].
 */
object SessionFormat {
    const val INTENSIVE_PREFIX = "__INTENSIVE__:"
    const val DIAGNOSTICS_PREFIX = "__DIAGNOSTICS__:"
    private const val INTENSIVE_CHILD_SEP = ";;"

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
        children: List<Session.IntensiveChild> = emptyList(),
        amountFixed: Boolean = false,
    ): String {
        val priceField = when {
            price.startsWith(AMOUNT_FIXED_PREFIX) -> price
            amountFixed -> "$AMOUNT_FIXED_PREFIX${price.removePrefix(AMOUNT_FIXED_PREFIX)}"
            else -> price
        }
        val base = "$INTENSIVE_PREFIX$priceField|$name|${status.code}"
        val withTime = if (time.isNotBlank()) "$base|$time" else base
        if (children.isEmpty()) return withTime
        val childrenPart = children.joinToString(INTENSIVE_CHILD_SEP) { child ->
            "${child.name}|${child.status.code}|${child.phone}|${child.comment}"
        }
        return "$withTime|$childrenPart"
    }

    /** Поле суммы: `5600` или `=5600` (фиксированная вручную). */
    fun intensivePriceField(amount: Double, amountFixed: Boolean): String {
        val digits = if (amount == 0.0) "" else amount.toLong().toString()
        return if (amountFixed) "$AMOUNT_FIXED_PREFIX$digits" else digits
    }

    /** Разбор поля суммы интенсива → (amount, amountFixed). */
    fun parseIntensivePriceField(raw: String): Pair<Double, Boolean> {
        val fixed = raw.startsWith(AMOUNT_FIXED_PREFIX)
        val digits = if (fixed) raw.substring(AMOUNT_FIXED_PREFIX.length) else raw
        return (digits.toDoubleOrNull() ?: 0.0) to fixed
    }

    private const val AMOUNT_FIXED_PREFIX = "="

    fun serializeDiagnostics(price: String, name: String, attended: Boolean, time: String = ""): String =
        serializeDiagnostics(price, name, AttendanceStatus.fromBoolean(attended), time)

    fun serializeDiagnostics(
        price: String,
        name: String,
        status: AttendanceStatus,
        time: String = "",
    ): String = "$DIAGNOSTICS_PREFIX$price|$name|${status.code}|$time"
}
