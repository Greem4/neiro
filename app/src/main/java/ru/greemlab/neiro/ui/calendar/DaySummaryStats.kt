package ru.greemlab.neiro.ui.calendar

import androidx.compose.runtime.Immutable
import ru.greemlab.neiro.domain.models.EarningsContext

/**
 * Разбивка дня по одному виду записей: занятия, интенсивы, диагностики.
 *
 * У интенсива счётчики считают детей, а не сами интенсивы: на плашке дня и в
 * её диалогах человек считает головы, а не строки расписания. Сколько было
 * самих интенсивов — отдельно, в [DaySummaryStats.intensiveCount].
 */
@Immutable
data class DayKindStats(
    /** Запланировано за день без отменённых. */
    val planned: Int = 0,
    /** Пришли — то, что уже в заработке. */
    val attended: Int = 0,
    /** Подтвердились, но ещё не пришли. */
    val confirmed: Int = 0,
    /** Ждут подтверждения. */
    val pending: Int = 0,
    /** Отменённые записи — их не будет, но день их планировал. */
    val cancelled: Int = 0,
    val earned: Double = 0.0,
    val expected: Double = 0.0,
    /** Деньги, которые не придут из-за отмен. */
    val cancelledAmount: Double = 0.0,
) {
    /** Вида в дне не было — строку в диалоге не показываем. */
    val isEmpty: Boolean
        get() = planned == 0 && cancelled == 0 && earned == 0.0 && expected == 0.0

    /** Сколько записей этого вида должно было быть — вместе с отменёнными. */
    val scheduled: Int get() = planned + cancelled

    /** Сколько вид мог принести, если бы никто не отменил. */
    val potential: Double get() = earned + expected + cancelledAmount
}

/** Сводка по одному дню для панели под календарём. */
@Immutable
data class DaySummaryStats(
    val totalLessons: Int = 0,
    val attendedLessons: Int = 0,
    val confirmedLessons: Int = 0,
    val pendingLessons: Int = 0,
    val confirmedIntensiveChildren: Int = 0,
    val pendingIntensiveChildren: Int = 0,
    val hasIntensive: Boolean = false,
    val earned: Double = 0.0,
    val expected: Double = 0.0,
    val lost: Double = 0.0,
    /** Только ученики: без интенсивов и диагностик. */
    val lessons: DayKindStats = DayKindStats(),
    /** Дети интенсивов за день. */
    val intensives: DayKindStats = DayKindStats(),
    val diagnostics: DayKindStats = DayKindStats(),
    /** Сколько интенсивов в дне — детей в них считает [intensives]. */
    val intensiveCount: Int = 0,
    /**
     * Заработок дня пришёл фактом из YClients, а не посчитан по ставкам.
     * Тогда разбивка по видам — оценка, и диалог обязан это сказать.
     */
    val earnedFromFact: Boolean = false,
) {
    /**
     * Занятие — это ученик или диагностика. Интенсив занятием не считается
     * ни в одной цифре: у него своя запись в дне и свои дети, и день с одним
     * интенсивом честно показывает «занятий 0». Разбор по нажатию покажет,
     * что в этом дне интенсив всё-таки был.
     *
     * Счётчики живых занятий уже лежат готовыми: [totalLessons],
     * [attendedLessons], [confirmedLessons], [pendingLessons].
     */
    val cancelledLessons: Int get() = lessons.cancelled + diagnostics.cancelled

    /** Сколько занятий должно было быть — вместе с отменёнными. */
    val scheduledLessons: Int get() = totalLessons + cancelledLessons

    /** Деньги, которые не придут из-за отмен — здесь интенсив считается. */
    val cancelledAmount: Double
        get() = lessons.cancelledAmount + intensives.cancelledAmount + diagnostics.cancelledAmount

    /** Потолок дня: заработанное, ожидаемое и потерянное на отменах вместе. */
    val potentialEarned: Double get() = earned + expected + cancelledAmount
}

/** Накопитель разбивки: считает один вид записей за день. */
private class DayKindAccumulator {
    var planned = 0
    var attended = 0
    var confirmed = 0
    var pending = 0
    var cancelled = 0
    var earned = 0.0
    var expected = 0.0
    var cancelledAmount = 0.0

    fun build(): DayKindStats = DayKindStats(
        planned = planned,
        attended = attended,
        confirmed = confirmed,
        pending = pending,
        cancelled = cancelled,
        earned = earned,
        expected = expected,
        cancelledAmount = cancelledAmount,
    )
}

/**
 * Сводка дня. Если за этот день есть факт из YClients ([dayFact]), заработок
 * берётся из него: прошедший день должен показывать начисленное, а не
 * «цена × занятия» (FOUNDATION 3.4). Интенсивы, заведённые руками, в факте
 * отсутствуют — они к нему прибавляются, а не заменяются им (GAPS 7).
 */
internal fun computeDayStats(
    sessions: List<String>,
    rates: EarningsContext,
    dayFact: Double? = null,
): DaySummaryStats {
    var totalLessons = 0
    var attendedLessons = 0
    var confirmedLessons = 0
    var pendingLessons = 0
    var confirmedIntensiveChildren = 0
    var pendingIntensiveChildren = 0
    var hasIntensive = false
    var intensiveCount = 0
    var earned = 0.0
    var expected = 0.0
    var lost = 0.0
    var manualIntensiveEarned = 0.0

    val lessonsAcc = DayKindAccumulator()
    val intensivesAcc = DayKindAccumulator()
    val diagnosticsAcc = DayKindAccumulator()

    val parsed = sessions.map(SessionParser::parse)
    val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)

    for (session in parsed) {
        if (session.isEffectivelyDeleted()) {
            if (session is Session.Intensive) hasIntensive = true
            val price = when (session) {
                is Session.Intensive -> session.totalAmount(rates.pricePerIntensiveChild, onlyArrived = false)
                is Session.Diagnostics ->
                    if (rates.pricePerDiagnostics > 0.0) rates.pricePerDiagnostics else session.amount
                is Session.Student -> rates.pricePerSession
            }
            lost += price
            when (session) {
                is Session.Intensive -> {
                    intensivesAcc.cancelled += if (session.children.isEmpty()) {
                        1
                    } else {
                        session.children.size
                    }
                    intensivesAcc.cancelledAmount += price
                }

                is Session.Diagnostics -> {
                    diagnosticsAcc.cancelled++
                    diagnosticsAcc.cancelledAmount += price
                }

                is Session.Student -> {
                    lessonsAcc.cancelled++
                    lessonsAcc.cancelledAmount += price
                }
            }
            continue
        }

        when (session) {
            is Session.Intensive -> {
                hasIntensive = true
                intensiveCount++
                val arrived = session.arrivedChildCount()
                confirmedIntensiveChildren += session.confirmedChildCount()
                pendingIntensiveChildren += session.pendingChildCount()
                val actual = session.totalAmount(rates.pricePerIntensiveChild, onlyArrived = true)
                val planned = session.totalAmount(rates.pricePerIntensiveChild, onlyArrived = false)
                earned += actual
                if (session.amountFixed) manualIntensiveEarned += actual
                expected += (planned - actual).coerceAtLeast(0.0)

                // Интенсив без детей — одна запись, иначе считаем головы:
                // счётчики детей [confirmedChildCount] и [pendingChildCount]
                // ведут себя так же.
                intensivesAcc.planned += if (session.children.isEmpty()) {
                    1
                } else {
                    session.children.visibleChildren().size
                }
                intensivesAcc.attended += arrived
                // confirmedChildCount() включает пришедших — в разбивке
                // «подтвердились» это те, кто ещё впереди.
                intensivesAcc.confirmed += (session.confirmedChildCount() - arrived).coerceAtLeast(0)
                intensivesAcc.pending += session.pendingChildCount()
                intensivesAcc.earned += actual
                intensivesAcc.expected += (planned - actual).coerceAtLeast(0.0)

                // Отменённый ребёнок внутри живого интенсива: в деньгах его уже
                // нет — ни в заработке, ни в ожидании, — но день его планировал.
                // При сумме, заданной руками, отмена ребёнка её не меняет, и
                // терять там нечего.
                val cancelledChildren = session.children.count { it.isEffectivelyDeleted() }
                if (cancelledChildren > 0) {
                    intensivesAcc.cancelled += cancelledChildren
                    if (!session.amountFixed) {
                        intensivesAcc.cancelledAmount +=
                            session.unitPrice(rates.pricePerIntensiveChild) * cancelledChildren
                    }
                }
            }

            is Session.Diagnostics -> {
                totalLessons++
                diagnosticsAcc.planned++
                val price = if (rates.pricePerDiagnostics > 0.0) rates.pricePerDiagnostics else session.amount
                if (session.countsTowardEarnings()) {
                    attendedLessons++
                    earned += price
                    diagnosticsAcc.attended++
                    diagnosticsAcc.earned += price
                } else {
                    expected += price
                    diagnosticsAcc.expected += price
                    if (session.status == AttendanceStatus.CONFIRMED) {
                        confirmedLessons++
                        diagnosticsAcc.confirmed++
                    } else if (session.status == AttendanceStatus.EXPECTED) {
                        pendingLessons++
                        diagnosticsAcc.pending++
                    }
                }
            }

            is Session.Student -> {
                if (isStudentCoveredByIntensive(session, intensiveChildrenByTime)) continue
                totalLessons++
                lessonsAcc.planned++
                val pay = rates.pricePerSession
                if (session.countsTowardEarnings()) {
                    attendedLessons++
                    earned += pay
                    lessonsAcc.attended++
                    lessonsAcc.earned += pay
                } else {
                    expected += pay
                    lessonsAcc.expected += pay
                    if (session.status == AttendanceStatus.CONFIRMED) {
                        confirmedLessons++
                        lessonsAcc.confirmed++
                    } else if (session.status == AttendanceStatus.EXPECTED) {
                        pendingLessons++
                        lessonsAcc.pending++
                    }
                }
            }
        }
    }

    return DaySummaryStats(
        totalLessons = totalLessons,
        attendedLessons = attendedLessons,
        confirmedLessons = confirmedLessons,
        pendingLessons = pendingLessons,
        confirmedIntensiveChildren = confirmedIntensiveChildren,
        pendingIntensiveChildren = pendingIntensiveChildren,
        hasIntensive = hasIntensive,
        earned = if (dayFact != null) dayFact + manualIntensiveEarned else earned,
        expected = expected,
        lost = lost,
        lessons = lessonsAcc.build(),
        intensives = intensivesAcc.build(),
        diagnostics = diagnosticsAcc.build(),
        intensiveCount = intensiveCount,
        earnedFromFact = dayFact != null,
    )
}
