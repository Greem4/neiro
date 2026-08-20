package ru.greemlab.neiro.sync

import ru.greemlab.neiro.data.network.DayFact
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.calendar.buildIntensiveChildrenByTime
import ru.greemlab.neiro.ui.calendar.isStudentCoveredByIntensive
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round

/** Как глубоко назад ищем день, из которого видно сегодняшнюю ставку. */
const val DAILY_RATE_DEPTH_DAYS = 45L

/** Ставка занятия, выведенная из начисления за один день, и день, из которого она взята. */
data class DailySessionRate(val date: LocalDate, val pricePerSession: Double)

/**
 * Ставка занятия из посуточного расчёта — единственный источник цены за
 * текущий месяц.
 *
 * Начисление создаётся в конце месяца (API-HOWTO 6), поэтому у идущего месяца
 * позиций со ставками нет вовсе: 20.08.2026 список начислений за август пуст,
 * а последнее закрытое — июль со ставкой 1400. Ставка выросла до 1500 с
 * первого августа, и увидеть её можно только в посуточном расчёте: за 20.08
 * YClients отдаёт `services_count: 6` и `salary: 9000`.
 *
 * Делим начисление дня на число услуг, но **только у дня, в котором одни
 * обычные занятия**. Смешанный день даёт цену, которой не было ни у одного
 * занятия (API-HOWTO 5.4): 5 занятий по 1500 плюс диагностика 2250 — это
 * 9750 ÷ 6 = 1625. Состав дня берётся из локального календаря: интенсивы
 * отсекаются ещё и по `group_services_count` из самого ответа.
 *
 * Идём от свежего дня к старому и берём первый подходящий: ставка меняется с
 * начала месяца, и самый свежий чистый день — единственный, кто про неё знает.
 */
fun sessionRateFromDailyFacts(
    facts: Map<LocalDate, DayFact>,
    dayData: Map<LocalDate, List<String>>,
    today: LocalDate,
    depthDays: Long = DAILY_RATE_DEPTH_DAYS,
): DailySessionRate? {
    val earliest = today.minusDays(depthDays)
    return facts.entries
        .asSequence()
        .filter { (date, _) -> !date.isAfter(today) && !date.isBefore(earliest) }
        .sortedByDescending { (date, _) -> date }
        .firstNotNullOfOrNull { (date, fact) -> rateFromDay(date, fact, dayData[date]) }
}

/**
 * Ставка одного дня или `null`, если по этому дню её не вывести.
 *
 * Отказываемся молча и часто — это нормально: пропущенный день стоит одной
 * итерации, а принятый неверно уедет в профиль и в деньги месяца.
 */
private fun rateFromDay(
    date: LocalDate,
    fact: DayFact,
    sessions: List<String>?,
): DailySessionRate? {
    if (fact.salary <= 0.0 || fact.servicesCount <= 0) return null
    // Интенсив в дне: его деньги лежат в той же сумме, а в services_count его нет.
    if (fact.groupServicesCount > 0) return null
    // Календарь по этому дню не синхронизирован — состав услуг неизвестен.
    val parsed = sessions?.map(SessionParser::parse) ?: return null

    val intensiveChildrenByTime = buildIntensiveChildrenByTime(parsed)
    var students = 0
    for (session in parsed) {
        if (!session.countsTowardEarnings()) continue
        when (session) {
            // Диагностика и интенсив идут по своим ставкам — день смешанный.
            is Session.Diagnostics -> return null
            is Session.Intensive -> return null
            is Session.Student ->
                if (!isStudentCoveredByIntensive(session, intensiveChildrenByTime)) students++
        }
    }

    // Локальный календарь должен сойтись с YClients ровно: разошлись — значит
    // в начислении дня есть что-то, чего в календаре не видно.
    if (students == 0 || students != fact.servicesCount) return null

    val price = fact.salary / fact.servicesCount
    // Ставка — целое число рублей. Копейки в частном означают, что в сумму дня
    // попало что-то ещё: доплата, бонус, сдвоенное занятие.
    if (abs(price - round(price)) > 0.001) return null
    return DailySessionRate(date = date, pricePerSession = price)
}
