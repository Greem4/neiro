package ru.greemlab.neiro.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.greemlab.neiro.domain.models.EarningsContext
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
            netProfit = 10000.0,
            rates = EarningsContext(pricePerSession = 1400.0),
            sessionPriceText = "1 400 ₽",
        )
        assertSubtitleEquals("Ожидается 5 000 ₽ · занятие 1 400 ₽", subtitle)
    }

    @Test
    fun `overview subtitle shows sum when expectedIncludesNet is true`() {
        val display = ProfitDisplaySettings(
            showExpectedInOverview = true,
            expectedIncludesNet = true,
        )
        val subtitle = buildOverviewProfitSubtitle(
            display = display,
            expectedIncome = 5000.0,
            expectedIncomeText = "5 000 ₽",
            netProfit = 12000.0,
            rates = EarningsContext.Empty,
            sessionPriceText = "",
        )
        assertSubtitleEquals("Ожидается 17 000 ₽", subtitle)
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
            netProfit = 10000.0,
            rates = EarningsContext(pricePerSession = 1400.0),
            sessionPriceText = "1 400 ₽",
        )
        assertEquals("", subtitle)
    }

    /**
     * Суммы форматируются с неразрывным пробелом (U+00A0), а на другой версии
     * JDK или ICU разделителем может оказаться обычный пробел или узкий
     * неразрывный (U+202F). Для текста подписи разница не значит ничего —
     * сравниваем, приведя разделители к одному виду, иначе тест краснеет от
     * смены окружения, а не от поломки.
     */
    private fun assertSubtitleEquals(expected: String, actual: String) {
        assertEquals(expected.normalizeSpaces(), actual.normalizeSpaces())
    }

    private fun String.normalizeSpaces(): String =
        replace('\u00A0', ' ').replace('\u202F', ' ')
}
