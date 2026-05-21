package ru.greemlab.neiro.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.data.network.ApiResult
import ru.greemlab.neiro.data.network.RecordData
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Состояние синхронизации.
 */
data class SyncUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val syncedCount: Int = 0,
    val lastSyncDate: LocalDate? = null,
    val showSuccess: Boolean = false,
)

/**
 * ViewModel для синхронизации данных из YClients.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    private val yclientsRepository = YClientsRepository.getInstance(application)
    private val calendarRepository: CalendarRepository =
        CalendarDataStoreProvider.get(application)

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = yclientsRepository.isLoggedIn

    /**
     * Синхронизирует записи за текущий месяц.
     */
    fun syncCurrentMonth() {
        val now = YearMonth.now()
        syncMonth(now)
    }

    /**
     * Синхронизирует записи за указанный месяц.
     */
    fun syncMonth(yearMonth: YearMonth) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                showSuccess = false,
            )

            val startDate = yearMonth.atDay(1)
            val endDate = yearMonth.atEndOfMonth()

            when (val result = yclientsRepository.getRecords(startDate, endDate)) {
                is ApiResult.Success -> {
                    val records = result.data
                    val syncedCount = mergeRecordsToCalendar(records)

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        syncedCount = syncedCount,
                        lastSyncDate = LocalDate.now(),
                        showSuccess = true,
                    )
                }

                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    /**
     * Синхронизирует записи за диапазон дат.
     */
    fun syncDateRange(startDate: LocalDate, endDate: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                showSuccess = false,
            )

            when (val result = yclientsRepository.getRecords(startDate, endDate)) {
                is ApiResult.Success -> {
                    val records = result.data
                    val syncedCount = mergeRecordsToCalendar(records)

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        syncedCount = syncedCount,
                        lastSyncDate = LocalDate.now(),
                        showSuccess = true,
                    )
                }

                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    /**
     * Преобразует записи YClients в формат календаря и сохраняет.
     *
     * Правила слияния:
     *  - имя из YClients добавляется к дате, только если на этом дне ещё нет такого
     *    же имени (сравнение по нормализованной форме — без регистра/пробелов,
     *    с приведением `ё` → `е`);
     *  - проверка учитывает и обычных учеников, и интенсивы/диагностики, потому
     *    что у них тоже есть поле имени;
     *  - в рамках одного батча второй визит одного и того же клиента в один день
     *    тоже игнорируется (защита от внутреннего дубля).
     */
    private suspend fun mergeRecordsToCalendar(records: List<RecordData>): Int {
        if (records.isEmpty()) return 0

        val currentDayData = calendarRepository.dayDataFlow.first().toMutableMap()
        var syncedCount = 0

        val recordsByDate = records
            .mapNotNull { record ->
                val date = parseRecordDate(record.date) ?: return@mapNotNull null
                date to record
            }
            .sortedBy { (_, record) -> record.datetime ?: record.date }
            .groupBy({ it.first }, { it.second })

        for ((date, dayRecords) in recordsByDate) {
            val existingRaw = currentDayData[date].orEmpty()
            val takenNames = existingRaw
                .mapNotNull { entry -> sessionDisplayName(entry)?.normalizeForDedup() }
                .toMutableSet()

            val newEntries = mutableListOf<String>()

            for (record in dayRecords) {
                val clientName = extractClientName(record)
                if (clientName.isBlank()) continue

                val key = clientName.normalizeForDedup()
                if (!takenNames.add(key)) continue

                val attended = record.attendance == 1 || record.visitAttendance == 1
                newEntries += SessionFormat.serializeStudent(clientName, attended)
                syncedCount++
            }

            if (newEntries.isNotEmpty()) {
                currentDayData[date] = existingRaw + newEntries
            }
        }

        if (syncedCount > 0) {
            calendarRepository.saveDayData(currentDayData)
        }

        return syncedCount
    }

    /** Имя из сериализованной записи дня (для всех типов сессий). */
    private fun sessionDisplayName(raw: String): String? =
        when (val session = SessionParser.parse(raw)) {
            is Session.Student -> session.name.takeIf { it.isNotBlank() }
            is Session.Extra -> session.name.takeIf { it.isNotBlank() }
        }

    /** Нормализованный ключ для сравнения имён (без регистра, пробелов и `ё`/`е`). */
    private fun String.normalizeForDedup(): String =
        trim().lowercase().replace('ё', 'е')

    private fun parseRecordDate(dateString: String): LocalDate? {
        return try {
            if (dateString.contains("T")) {
                LocalDate.parse(dateString.substringBefore("T"))
            } else {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                LocalDate.parse(dateString, formatter)
            }
        } catch (e: Exception) {
            try {
                LocalDate.parse(dateString.take(10))
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun extractClientName(record: RecordData): String {
        val client = record.client ?: return ""

        return when {
            !client.displayName.isNullOrBlank() -> client.displayName
            !client.name.isNullOrBlank() -> {
                buildString {
                    append(client.name)
                    if (!client.surname.isNullOrBlank()) {
                        append(" ")
                        append(client.surname)
                    }
                }
            }
            else -> ""
        }.trim()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(showSuccess = false)
    }
}
