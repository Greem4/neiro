package ru.greemlab.neiro.data.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ответы раздела «Начисления», снятые с живого API 01.08.2026 (фирма 520135).
 *
 * Обе модели были написаны по догадке и обе разошлись с реальностью: сумма
 * лежит в `amount`, а не в `sum`, а позиции детализации — внутри объекта, в
 * `salary_items`, а не массивом. Из-за второго автоподстановка ставок из API
 * не работала ни разу — падение ловилось и превращалось в тишину.
 */
class SalaryCalculationShapeTest {

    private val liveList = """
        {"success": true,
         "data": [
           {"id": 22498229, "date_create": "2025-01-31 18:28:08",
            "date_from": "2025-01-01", "date_to": "2025-01-31",
            "staff_id": 3618433, "company_id": 520135,
            "amount": 22500, "status": "confirmed", "comment": ""},
           {"id": 32727765, "date_create": "2026-03-02 10:56:16",
            "date_from": "2026-02-01", "date_to": "2026-02-28",
            "staff_id": 3618433, "company_id": 520135,
            "amount": 136600, "status": "draft", "comment": ""}
         ],
         "meta": {"count": 2}}
    """.trimIndent()

    private val liveDetails = """
        {"success": true,
         "data": {"id": 32727765, "date_from": "2026-02-01", "date_to": "2026-02-28",
           "amount": 136600, "status": "confirmed", "comment": "",
           "salary_items": [
             {"date": "2026-02-27", "time": "18:00", "item_id": 1511362129,
              "item_type_slug": "record", "salary_sum": "1400",
              "record_id": 1511362129, "client_id": 267147119, "cost": "2800",
              "paid": {"abonement_sum": "3000"},
              "salary_calculation_info": {"scheme_title": "Специалисты"},
              "targets": [{"target_type_slug": "service", "target_id": 17390933,
                 "title": "Нейрокоррекция", "cost": "2800", "net_cost": "0",
                 "salary_sum": "1400",
                 "salary_calculation": {"type_slug": "percent", "value": 50}}],
              "salary_discrepancy": {"reason": "deleted", "actual_sum": "0"}},
             {"date": "2026-02-26", "time": "16:00", "item_type_slug": "record",
              "salary_sum": "1500",
              "targets": [{"target_type_slug": "service", "title": "Нейрокоррекция",
                 "salary_sum": "1500",
                 "salary_calculation": {"type_slug": "fix", "value": 1500}}]},
             {"date": "2026-02-25", "time": "10:00", "item_type_slug": "activity",
              "salary_sum": "0",
              "targets": [{"target_type_slug": "service", "title": "Интенсив",
                 "salary_sum": "0"}]}
           ]},
         "meta": []}
    """.trimIndent()

    @Test
    fun `calculation list reads amount and status`() {
        val parsed = salaryCalculations(SalaryEnvelope(JsonParser.parseString(liveList)).data)

        assertEquals(2, parsed.size)
        assertEquals(22_500.0, parsed[0].amount.toMoneyOrNull()!!, 0.0)
        assertTrue(parsed[0].isConfirmed)
        assertFalse(parsed[1].isConfirmed)
    }

    @Test
    fun `details keep items hidden inside the object`() {
        val parsed = salaryCalculationItems(SalaryEnvelope(JsonParser.parseString(liveDetails)).data)

        assertEquals(3, parsed.size)
        assertEquals("record", parsed[0].itemTypeSlug)
        assertEquals("activity", parsed[2].itemTypeSlug)
    }

    @Test
    fun `rate is the most frequent one, intensive does not count`() {
        val items = salaryCalculationItems(SalaryEnvelope(JsonParser.parseString(liveDetails)).data)

        // Интенсив (`activity`) в ставку занятия не идёт — в феврале 2026 он и
        // вовсе дал 0 ₽. Из двух записей берётся любая частая; здесь их поровну,
        // важно лишь, что это 1400 или 1500, а не ноль интенсива.
        val rate = extractSalaryRates(items).pricePerSession
        assertTrue("ставка не из записей: $rate", rate == 1_400.0 || rate == 1_500.0)
    }
}
