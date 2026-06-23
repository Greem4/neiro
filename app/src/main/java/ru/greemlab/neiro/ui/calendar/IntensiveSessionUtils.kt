package ru.greemlab.neiro.ui.calendar

/** Статус интенсива для UI: по детям или по записи целиком. */
fun Session.Intensive.displayStatus(): AttendanceStatus {
    if (children.isEmpty()) return status
    return children.maxByOrNull { it.status.mergePriority }?.status ?: status
}

/** Ставка за одного ребёнка: из профиля или из суммы записи. */
fun Session.Intensive.unitPrice(pricePerChild: Double): Double {
    if (pricePerChild > 0.0) return pricePerChild
    if (amount > 0.0 && children.isNotEmpty()) return amount / children.size
    return amount
}

/** Ожидаемый или фактический доход интенсива. */
fun Session.Intensive.totalAmount(
    pricePerChild: Double,
    onlyArrived: Boolean,
): Double {
    val unit = unitPrice(pricePerChild)
    if (children.isNotEmpty()) {
        val count = if (onlyArrived) {
            children.count { it.status.countsTowardEarnings }
        } else {
            children.count { it.status != AttendanceStatus.CANCELLED }
        }
        return unit * count
    }
    return if (!onlyArrived || countsTowardEarnings()) unit else 0.0
}

fun intensiveChildrenLabel(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "$count ребёнок"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "$count ребёнка"
    else -> "$count детей"
}
