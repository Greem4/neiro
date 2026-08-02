package ru.greemlab.neiro.data.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форма `data` у посуточного расчёта.
 *
 * Живой ответ отдаёт здесь объект, а не массив — на этом Gson падал с
 * `Expected BEGIN_ARRAY but was BEGIN_OBJECT at path $.data`, и зарплата не
 * доезжала до приложения вообще. Форма нигде не описана, поэтому разбор обязан
 * пережить любую из правдоподобных.
 */
class SalaryDailyShapeTest {

    private fun items(json: String) = salaryDailyItems(JsonParser.parseString(json))

    /**
     * Ответ живого API, снятый 01.08.2026 с фирмы 520135 за январь 2025.
     *
     * Именно на нём разбор падал: `data` — объект с массивом внутри, `meta` —
     * пустой массив, и в позициях есть поля, которых нет в документации
     * (`goods_sales_*`). Это эталон: разойдётся — сломается здесь, а не в бою.
     */
    private val liveResponse = """
        {"success": true,
         "data": {
           "period_calculation_daily": [
             {"date": "2025-01-01", "period_calculation": {
                "working_days_count": 0, "working_hours_count": 0,
                "group_services_count": 0, "services_count": 0,
                "services_sum": "0", "goods_sales_count": 0,
                "goods_sales_sum": "0", "total_sum": "0", "salary": "0"}},
             {"date": "2025-01-16", "period_calculation": {
                "working_days_count": 1, "working_hours_count": 2,
                "group_services_count": 0, "services_count": 2,
                "services_sum": "5000", "goods_sales_count": 0,
                "goods_sales_sum": "0", "total_sum": "5000", "salary": "2500"}}
           ],
           "currency": {"symbol": "₽"}
         },
         "meta": []}
    """.trimIndent()

    @Test
    fun `live response from yclients is parsed`() {
        val envelope = SalaryEnvelope(JsonParser.parseString(liveResponse))
        assertTrue(envelope.isSuccess)

        val parsed = salaryDailyItems(envelope.data)

        assertEquals(2, parsed.size)
        // Пустой день тоже позиция: «не работал» — это факт, а не отсутствие данных.
        assertEquals("2025-01-01", parsed[0].date)
        assertEquals(0.0, parsed[0].calculation?.salary.toMoneyOrNull()!!, 0.0)
        // 16 января — два занятия по 1250, как на странице salary_daily.
        assertEquals("2025-01-16", parsed[1].date)
        assertEquals(2, parsed[1].calculation?.servicesCount)
        assertEquals(2_500.0, parsed[1].calculation?.salary.toMoneyOrNull()!!, 0.0)
    }

    @Test
    fun `currency next to the array is not mistaken for a day`() {
        // В data лежит ещё и {"currency": {...}} — она не должна стать позицией.
        val parsed = salaryDailyItems(
            SalaryEnvelope(JsonParser.parseString(liveResponse)).data,
        )
        assertTrue(parsed.none { it.date == "currency" })
    }

    @Test
    fun `array of items as in the docs`() {
        val parsed = items(
            """
            [{"date":"2026-07-26","period_calculation":{"services_count":6,"salary":"8400"}}]
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals("2026-07-26", parsed.first().date)
        assertEquals(8_400.0, parsed.first().calculation?.salary.toMoneyOrNull()!!, 0.0)
    }

    @Test
    fun `object keyed by date with nested calculation`() {
        val parsed = items(
            """
            {"2025-01-16":{"period_calculation":{"services_count":2,"salary":"2500"}}}
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals("2025-01-16", parsed.first().date)
        assertEquals(2, parsed.first().calculation?.servicesCount)
    }

    @Test
    fun `object keyed by date with the calculation inlined`() {
        // Дата только в ключе — внутри сразу поля расчёта.
        val parsed = items("""{"2025-01-16":{"services_count":2,"salary":"2500"}}""")

        assertEquals(1, parsed.size)
        assertEquals("2025-01-16", parsed.first().date)
        assertEquals(2_500.0, parsed.first().calculation?.salary.toMoneyOrNull()!!, 0.0)
    }

    @Test
    fun `wrapper object with the array inside`() {
        val parsed = items(
            """
            {"total":"2500","dates":[
              {"date":"2025-01-16","period_calculation":{"services_count":2,"salary":"2500"}}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals("2025-01-16", parsed.first().date)
    }

    @Test
    fun `unknown shapes give nothing instead of throwing`() {
        // До разбора вручную любая из этих строк роняла весь запрос.
        assertTrue(items("null").isEmpty())
        assertTrue(items("\"строка\"").isEmpty())
        assertTrue(items("{}").isEmpty())
        assertTrue(items("""{"meta":{"message":"нет прав"}}""").isEmpty())
    }

    @Test
    fun `one broken entry does not take the month with it`() {
        val parsed = items(
            """
            {"2025-01-16":{"services_count":2,"salary":"2500"},
             "2025-01-17":"мусор",
             "2025-01-18":{"services_count":1,"salary":"1250"}}
            """.trimIndent(),
        )

        assertEquals(2, parsed.size)
    }
}
