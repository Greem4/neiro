package ru.greemlab.neiro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// corruptionHandler по образцу CalendarDataStore: битый salary_ledger.preferences_pb
// не роняет старт — история окажется пустой и дозаполнится при следующем синке.
private val Context.salaryLedgerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "salary_ledger",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private const val LEDGER_JSON_KEY = "salary_ledger_json"

/**
 * Хранилище истории ЗП — единственное место с Android во всей денежной части
 * (FOUNDATION 8.4). Лежит отдельно от `UserProfile`: там 12 полей в одном JSON,
 * а здесь растущий список месяцев.
 */
class SalaryLedgerStore private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val ledgerKey = stringPreferencesKey(LEDGER_JSON_KEY)

    /** Все записи идут только под этим mutex — параллельные апдейты не теряются. */
    private val writeMutex = Mutex()

    private val _ledger = MutableStateFlow(SalaryLedger.Empty)
    val ledger: StateFlow<SalaryLedger> = _ledger.asStateFlow()

    /** Гидратация из DataStore. До вызова [ledger] отдаёт пустую историю. */
    suspend fun warmUp() {
        writeMutex.withLock {
            val prefs = appContext.salaryLedgerDataStore.data.first()
            _ledger.value = SalaryLedgerJson.fromJson(prefs[ledgerKey])
        }
    }

    /**
     * Единственный способ записи: read-modify-write под mutex, как
     * `CalendarRepository.updateDayData`. Иначе параллельные синк и правка
     * из UI потеряют друг друга.
     */
    suspend fun update(transform: (SalaryLedger) -> SalaryLedger) {
        writeMutex.withLock {
            var next: SalaryLedger? = null
            // Читаем актуальное значение внутри edit, а не из _ledger:
            // RMW не зависит от того, успел ли завершиться warmUp.
            appContext.salaryLedgerDataStore.edit { prefs ->
                val current = SalaryLedgerJson.fromJson(prefs[ledgerKey])
                val updated = transform(current)
                if (updated == current) return@edit
                prefs[ledgerKey] = SalaryLedgerJson.toJson(updated)
                next = updated
            }
            next?.let { _ledger.value = it }
        }
    }

    /** JSON истории для экспорта архива. */
    fun exportJson(): String = SalaryLedgerJson.toJson(_ledger.value)

    /**
     * Восстановление из бэкапа. Пустой разбор (битый JSON или пустая история)
     * ничего не меняет: ручные правки — самая невосстановимая часть данных,
     * терять их из-за плохого файла нельзя.
     *
     * @return `true`, если данные применены.
     */
    suspend fun importJson(json: String): Boolean {
        val parsed = SalaryLedgerJson.restorable(json) ?: return false
        writeMutex.withLock {
            appContext.salaryLedgerDataStore.edit { prefs ->
                prefs[ledgerKey] = SalaryLedgerJson.toJson(parsed)
            }
            _ledger.value = parsed
        }
        return true
    }

    companion object {
        /** Ключ в JSON-файле экспорта архива. */
        const val EXPORT_KEY = "salary_ledger"

        @Volatile
        private var instance: SalaryLedgerStore? = null

        fun get(context: Context): SalaryLedgerStore =
            instance ?: synchronized(this) {
                instance ?: SalaryLedgerStore(context).also { instance = it }
            }
    }
}
