package ru.greemlab.neiro.domain.pay

import java.time.LocalDate

// TODO: разобраться с оплатой — заморозка ставки в payAmount при смене цены в профиле.

/**
 * Замораживает [rate] в записях учеников (отключено: прибыль считается по текущей ставке профиля).
 */
object SessionPayBackfill {

    fun freezeRate(dayData: Map<LocalDate, List<String>>, rate: Double): Map<LocalDate, List<String>> =
        dayData

    internal fun freezeRateInRaw(raw: String, rate: Double): String = raw

    /*
    import ru.greemlab.neiro.ui.calendar.Session
    import ru.greemlab.neiro.ui.calendar.SessionFormat
    import ru.greemlab.neiro.ui.calendar.SessionParser

    fun freezeRate(dayData: Map<LocalDate, List<String>>, rate: Double): Map<LocalDate, List<String>> {
        ...
    }

    internal fun freezeRateInRaw(raw: String, rate: Double): String {
        ...
    }
    */
}
