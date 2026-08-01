package ru.greemlab.neiro.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.greemlab.neiro.domain.models.MonthEntry
import ru.greemlab.neiro.domain.models.PriceOrigin
import java.time.YearMonth

/**
 * Бэкап истории ЗП: экспорт кладёт JSON в тот же файл архива, что и остальные
 * ключи, импорт при отсутствии ключа историю не трогает (FOUNDATION 8.4).
 */
class SalaryLedgerBackupTest {

    private val gson = Gson()
    private val backupType = object : TypeToken<Map<String, String>>() {}.type
    private val staffId = 3618433L

    private val ledger = SalaryLedger.Empty.withMonth(
        MonthEntry(
            staffId = staffId,
            year = 2026,
            month = 3,
            sessions = 115,
            pricePerSession = 1400.0,
            origin = PriceOrigin.MANUAL,
            frozen = true,
            resolved = true,
            note = "разобрано вручную",
        ),
    )

    @Test
    fun `export and import give the same history`() {
        val backup: Map<String, String> = mapOf(
            "saved_day_data" to "{}",
            SalaryLedgerStore.EXPORT_KEY to SalaryLedgerJson.toJson(ledger),
        )
        val file = gson.toJson(backup)

        val parsed: Map<String, String> = gson.fromJson(file, backupType)
        val restored = SalaryLedgerJson.restorable(parsed[SalaryLedgerStore.EXPORT_KEY])

        assertNotNull(restored)
        val entry = restored!!.month(staffId, YearMonth.of(2026, 3))
        assertEquals(1400.0, entry!!.pricePerSession, 0.0)
        assertEquals(PriceOrigin.MANUAL, entry.origin)
        assertEquals("разобрано вручную", entry.note)
    }

    @Test
    fun `missing key means do not touch history`() {
        val oldBackup: Map<String, String> = mapOf("saved_day_data" to "{}")
        val file = gson.toJson(oldBackup)

        val parsed: Map<String, String> = gson.fromJson(file, backupType)

        assertNull(parsed[SalaryLedgerStore.EXPORT_KEY])
        assertNull(SalaryLedgerJson.restorable(parsed[SalaryLedgerStore.EXPORT_KEY]))
    }

    @Test
    fun `broken history json is not applied`() {
        assertNull(SalaryLedgerJson.restorable("не json"))
        assertNull(SalaryLedgerJson.restorable("{}"))
        assertNull(SalaryLedgerJson.restorable(""))
    }

    @Test
    fun `export key is stable`() {
        // Ключ уезжает в файлы пользователей — переименование сломает старые бэкапы.
        assertEquals("salary_ledger", SalaryLedgerStore.EXPORT_KEY)
    }
}
