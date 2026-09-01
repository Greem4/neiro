package ru.greemlab.neiro.ui.calendar

import ru.greemlab.neiro.ui.components.daydetails.normalizeSessionTime

private val deletedNamePrefixes = charArrayOf('-', '—', '–', '−')

/** Ребёнок интенсива считается отменённым — как обычная запись с «−». */
fun Session.IntensiveChild.isEffectivelyDeleted(): Boolean =
    status == AttendanceStatus.CANCELLED ||
        deletedNamePrefixes.any { prefix -> name.startsWith(prefix) }

/** Дети интенсива без отменённых. */
fun List<Session.IntensiveChild>.visibleChildren(): List<Session.IntensiveChild> =
    filter { !it.isEffectivelyDeleted() }

/** Статус интенсива для UI: по видимым детям или по записи целиком. */
fun Session.Intensive.displayStatus(): AttendanceStatus {
    val visible = children.visibleChildren()
    if (visible.isEmpty()) return if (children.isEmpty()) status else AttendanceStatus.CANCELLED
    return visible.maxByOrNull { it.status.mergePriority }?.status ?: status
}

/** Ставка за одного ребёнка: из профиля или из суммы записи. */
fun Session.Intensive.unitPrice(pricePerChild: Double): Double {
    val visible = children.visibleChildren()
    if (amountFixed || visible.isEmpty()) {
        if (amount > 0.0 && visible.isNotEmpty()) return amount / visible.size
        return amount
    }
    if (pricePerChild > 0.0) return pricePerChild
    if (amount > 0.0 && visible.isNotEmpty()) return amount / visible.size
    return amount
}

/** Ожидаемый или фактический доход интенсива. */
fun Session.Intensive.totalAmount(
    pricePerChild: Double,
    onlyArrived: Boolean,
): Double {
    val visible = children.visibleChildren()
    if (amountFixed || visible.isEmpty()) {
        return if (!onlyArrived || countsTowardEarnings()) amount else 0.0
    }
    val unit = if (pricePerChild > 0.0) {
        pricePerChild
    } else if (amount > 0.0) {
        amount / visible.size
    } else {
        0.0
    }
    val count = if (onlyArrived) {
        visible.count { it.status.countsTowardEarnings }
    } else {
        visible.size
    }
    return unit * count
}

/** Сколько детей подтвердились или уже пришли на интенсив. */
fun Session.Intensive.confirmedChildCount(): Int = when {
    children.isNotEmpty() -> children.count {
        it.status == AttendanceStatus.CONFIRMED || it.status.hasArrived
    }
    isEffectivelyDeleted() -> 0
    status == AttendanceStatus.CONFIRMED || status.hasArrived -> 1
    else -> 0
}

/** Сколько детей всё ещё в статусе ожидания. */
fun Session.Intensive.pendingChildCount(): Int = when {
    children.isNotEmpty() -> children.count { it.status == AttendanceStatus.EXPECTED }
    status == AttendanceStatus.EXPECTED && !isEffectivelyDeleted() -> 1
    else -> 0
}

/** Сколько детей пришло на интенсив. */
fun Session.Intensive.arrivedChildCount(): Int = when {
    children.isNotEmpty() -> children.count { it.status.hasArrived }
    countsTowardEarnings() -> 1
    else -> 0
}

/** Подпись для интенсива: «Подтверждено / Ожидают» или просто «Подтверждено», если ожидающих нет. */
fun formatIntensiveConductedLabel(confirmed: Int, pending: Int): String = when {
    pending > 0 -> "$confirmed/$pending"
    else -> confirmed.toString()
}

fun intensiveChildrenLabel(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "$count ребёнок"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count ребёнка"
    else -> "$count детей"
}

/** Нормализация имени для сопоставления ученика и ребёнка интенсива. */
fun String.normalizeNameForDedup(): String =
    lowercase()
        .replace('ё', 'е')
        .split(
            ' ', '\t', '\n', '\r',
            '-', '.', ',', ';', ':', '(', ')', '/', '\\',
        )
        .asSequence()
        .map { token -> token.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/**
 * Ученик на том же слоте, что и ребёнок интенсива — показываем только интенсив.
 * Отменённые дубли не выводим отдельной строкой.
 */
fun isStudentCoveredByIntensive(
    student: Session.Student,
    intensiveChildrenByTime: Map<String, Set<String>>,
): Boolean {
    if (intensiveChildrenByTime.isEmpty()) return false
    val time = normalizeSessionTime(student.time)
    val names = intensiveChildrenByTime[time] ?: return false
    return student.name.normalizeNameForDedup() in names
}

internal fun buildIntensiveChildrenByTime(sessions: List<Session>): Map<String, Set<String>> {
    val result = mutableMapOf<String, MutableSet<String>>()
    for (session in sessions) {
        if (session !is Session.Intensive || session.children.isEmpty()) continue
        val time = normalizeSessionTime(session.time)
        val bucket = result.getOrPut(time) { mutableSetOf() }
        session.children.forEach { child ->
            bucket += child.name.normalizeNameForDedup()
        }
    }
    return result
}
