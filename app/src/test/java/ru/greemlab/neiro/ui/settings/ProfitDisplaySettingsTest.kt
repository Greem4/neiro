package ru.greemlab.neiro.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.ui.screens.buildOverviewProfitSubtitle

class ProfitDisplaySettingsTest {

    @Test
    fun `overview subtitle joins expected income and session price`() {
        val display = ProfitDisplaySettings(
            showExpectedInOverview = true,
            showPricePerSession = true,
        )
        val subtitle = buildOverviewProfitSubtitle(
            display = display,
            expectedIncome = 5000.0,
            expectedIncomeText = "5 000 ₽",
            pricePerSession = 1400.0,
            sessionPriceText = "1 400 ₽",
        )
        assertEquals("Ожидается 5 000 ₽ · занятие 1 400 ₽", subtitle)
    }

    @Test
    fun `overview subtitle is empty when both toggles off`() {
        val display = ProfitDisplaySettings(
            showExpectedInOverview = false,
            showPricePerSession = false,
        )
        val subtitle = buildOverviewProfitSubtitle(
            display = display,
            expectedIncome = 5000.0,
            expectedIncomeText = "5 000 ₽",
            pricePerSession = 1400.0,
            sessionPriceText = "1 400 ₽",
        )
        assertEquals("", subtitle)
    }
}
