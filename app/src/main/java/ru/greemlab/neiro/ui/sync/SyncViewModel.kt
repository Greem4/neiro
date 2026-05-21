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
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import java.time.LocalDate
import java.time.LocalTime
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
     * Имя авторизованного в YClients пользователя.
     * Возвращает `null`, если пользователь ещё не вошёл.
     */
    val yclientsUserName: String? get() = yclientsRepository.userName

    /**
     * Завершает сессию YClients и сбрасывает статус последней синхронизации,
     * чтобы при следующем входе UI начал с чистого листа.
     */
    fun logoutYClients() {
        yclientsRepository.logout()
        _uiState.value = SyncUiState()
    }

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
     * Правила слияния (на каждый день отдельно):
     *  - записи из YClients сортируются по `datetime` (по возрастанию) — самое
     *    раннее занятие оказывается сверху, самое позднее — снизу;
     *  - если на этом дне уже была запись с таким же именем (сравнение по
     *    нормализованной форме — см. [normalizeForDedup]), то она
     *    переиспользуется «как есть» — это сохраняет ручные правки пользователя
     *    (флаг «пришёл», тип записи: интенсив/диагностика/ученик);
     *  - локальные записи, которым нет соответствия в YClients (т.е. добавленные
     *    вручную), кладутся ниже синхронизированных, в исходном порядке;
     *  - дубли внутри одного батча (несколько визитов одного клиента в один
     *    день) сворачиваются в одну запись; если хотя бы один из визитов
     *    помечен «пришёл», именно он попадает в календарь;
     *  - если в дне уже лежали несколько записей с одинаковым нормализованным
     *    именем (остатки от старой логики), они тоже схлопываются — приоритет
     *    у записи с отметкой посещения.
     *
     * Возвращает количество новых записей, реально добавленных в календарь.
     */
    private suspend fun mergeRecordsToCalendar(records: List<RecordData>): Int {
        if (records.isEmpty()) return 0

        val userProfile = calendarRepository.userProfileFlow.first()
        val currentDayData = calendarRepository.dayDataFlow.first().toMutableMap()
        var newlyAdded = 0
        var changedDays = 0

        val recordsByDate = records
            .mapNotNull { record ->
                val date = parseRecordDate(record.date) ?: return@mapNotNull null
                date to record
            }
            .groupBy({ it.first }, { it.second })

        for ((date, dayRecords) in recordsByDate) {
            val existingRaw = currentDayData[date].orEmpty()

            // Группируем уже существующие записи по ключу. Если на день лежат
            // дубли (например, после миграции данных) — берём ту, что отмечена
            // «пришёл», чтобы не терять отметку посещения.
            val existingByName: Map<String, String> = existingRaw
                .mapNotNull { entry ->
                    val key = sessionDisplayName(entry)?.normalizeForDedup()
                    if (key.isNullOrEmpty()) null else key to entry
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, entries) ->
                    entries.maxByOrNull { SessionParser.getStatus(it).mergePriority }
                        ?: entries.first()
                }

            // Свернём дубли визитов одного клиента в один день: оставим
            // самый ранний по времени, но с «лучшим» флагом посещения.
            val collapsedRecords = collapseDuplicateRecords(dayRecords)

            val seenKeys = mutableSetOf<String>()
            val syncedEntries = mutableListOf<String>()

            for ((key, record) in collapsedRecords) {
                if (!seenKeys.add(key)) continue

                val existingEntry = existingByName[key]
                if (existingEntry != null) {
                    // Обновляем существующую запись с новыми данными из YClients
                    syncedEntries += updateEntryFromRecord(existingEntry, record, userProfile)
                } else {
                    syncedEntries += createEntryFromRecord(record, userProfile)
                    newlyAdded++
                }
            }

            // Под синхронизированными — ручные записи. Параллельно схлопываем
            // случайно образовавшиеся дубли по нормализованному имени, оставляя
            // запись с отметкой «пришёл», если такая есть.
            val leftover = mutableListOf<String>()
            val leftoverIndexByKey = mutableMapOf<String, Int>()
            for (entry in existingRaw) {
                val key = sessionDisplayName(entry)?.normalizeForDedup()
                if (key.isNullOrEmpty()) {
                    leftover += entry
                    continue
                }
                if (key in seenKeys) continue

                val existingIdx = leftoverIndexByKey[key]
                if (existingIdx == null) {
                    leftoverIndexByKey[key] = leftover.size
                    leftover += entry
                } else if (
                    SessionParser.getStatus(entry).mergePriority >
                    SessionParser.getStatus(leftover[existingIdx]).mergePriority
                ) {
                    leftover[existingIdx] = entry
                }
            }

            val merged = syncedEntries + leftover
            if (merged != existingRaw) {
                currentDayData[date] = merged
                changedDays++
            }
        }

        if (changedDays > 0) {
            calendarRepository.saveDayData(currentDayData)
        }

        return newlyAdded
    }

    /**
     * Свернёт несколько записей одного клиента в одном дне в одну.
     *
     * Возвращает пары `(нормализованный ключ, запись)` в порядке самого
     * раннего визита. Если у клиента было несколько визитов и хотя бы один
     * с отметкой посещения — используется именно он (но позиция в списке
     * остаётся «по времени первого визита»).
     */
    private fun collapseDuplicateRecords(
        dayRecords: List<RecordData>,
    ): List<Pair<String, RecordData>> {
        val ordered = LinkedHashMap<String, RecordData>()
        for (record in dayRecords.sortedBy { it.datetime ?: it.date }) {
            val name = extractClientName(record)
            if (name.isBlank()) continue
            val key = name.normalizeForDedup()
            if (key.isEmpty()) continue

            val previous = ordered[key]
            if (previous == null) {
                ordered[key] = record
            } else {
                val previousStatus = mapAttendanceStatus(previous)
                val currentStatus = mapAttendanceStatus(record)
                if (currentStatus.mergePriority > previousStatus.mergePriority) {
                    ordered[key] = record
                }
            }
        }
        return ordered.entries.map { it.key to it.value }
    }

    /** Имя из сериализованной записи дня (для всех типов сессий). */
    private fun sessionDisplayName(raw: String): String? =
        when (val session = SessionParser.parse(raw)) {
            is Session.Student -> session.name.takeIf { it.isNotBlank() }
            is Session.Extra -> session.name.takeIf { it.isNotBlank() }
        }

    /**
     * Нормализованный ключ для сравнения имён клиента.
     *
     * Решает следующие источники дубликатов между синхронизациями:
     *  - регистр и буква «ё» (`Маша` / `маша` / `Мёша` / `Меша`);
     *  - множественные пробелы и табуляции (`"Маша  Иванова"`);
     *  - порядок слов (`"Маша Иванова"` vs `"Иванова Маша"`);
     *  - пунктуация и разделители (`"Маша-Лена"`, `"Маша."`);
     *  - незначащие пробелы по краям.
     *
     * Не решает (намеренно, чтобы не схлопнуть разных людей):
     *  - частичное совпадение токенов (`"Маша"` vs `"Маша Иванова"`).
     */
    private fun String.normalizeForDedup(): String =
        lowercase()
            .replace('ё', 'е')
            .split(
                ' ', '\t', '\n', '\r',
                '-', '.', ',', ';', ':', '(', ')', '/', '\\',
            )
            .asSequence()
            .map { token -> token.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(" ")

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

    /**
     * Создаёт новую запись из данных YClients с расширенным форматом.
     */
    private fun createEntryFromRecord(record: RecordData, userProfile: UserProfile): String {
        val clientName = extractClientName(record)
        val status = mapAttendanceStatus(record)

        // Если услуга содержит «диагностика», создаем специальную запись
        val isDiagnostics = record.services?.any {
            it.title?.contains("диагностика", ignoreCase = true) == true
        } == true

        if (isDiagnostics) {
            val price = userProfile.pricePerDiagnostics.toInt().toString()
            val time = formatRecordTime(record)
            return SessionFormat.serializeDiagnostics(
                price = price,
                name = clientName,
                attended = status == AttendanceStatus.ARRIVED,
                time = time,
            )
        }

        val time = formatRecordTime(record)
        val phone = record.client?.phone.orEmpty()
        val comment = record.comment.orEmpty()

        return SessionFormat.serializeStudentExtended(
            name = clientName,
            status = status,
            time = time,
            phone = phone,
            comment = comment,
        )
    }

    /**
     * Обновляет существующую запись с новыми данными из YClients.
     * Сохраняет локальные изменения, но обновляет время, телефон и комментарий.
     */
    private fun updateEntryFromRecord(
        existingEntry: String,
        record: RecordData,
        userProfile: UserProfile,
    ): String {
        val session = SessionParser.parse(existingEntry)
        val clientName = extractClientName(record)
        val status = mapAttendanceStatus(record)

        // Если в YClients это диагностика, обновляем тип записи, даже если раньше был Student
        val isDiagnosticsInYClients = record.services?.any {
            it.title?.contains("диагностика", ignoreCase = true) == true
        } == true

        if (isDiagnosticsInYClients) {
            val price = userProfile.pricePerDiagnostics.toInt().toString()
            val time = formatRecordTime(record)
            return SessionFormat.serializeDiagnostics(
                price = price,
                name = clientName,
                attended = status == AttendanceStatus.ARRIVED,
                time = time,
            )
        }

        // Если это не диагностика, но была диагностикой — превращаем в Student?
        // Наверное, лучше оставить как есть или обновить как Student, если в YClients это обычная услуга.
        if (session !is Session.Student) {
            // Если в YClients это НЕ диагностика, но локально это была диагностика/интенсив,
            // мы могли бы захотеть конвертировать её обратно в Student, если имена совпадают.
            // Но пока просто обновим обычные записи.
            return existingEntry
        }

        val time = formatRecordTime(record)
        val phone = record.client?.phone.orEmpty()
        val comment = record.comment.orEmpty()

        return SessionFormat.serializeStudentExtended(
            name = session.name,
            status = status,
            time = time,
            phone = phone,
            comment = comment,
        )
    }

    /** Преобразует статус визита YClients в [AttendanceStatus]. */
    private fun mapAttendanceStatus(record: RecordData): AttendanceStatus =
        AttendanceStatus.resolveFromRecord(record.attendance, record.visitAttendance)

    /**
     * Форматирует время записи в виде "HH:mm-HH:mm".
     */
    private fun formatRecordTime(record: RecordData): String {
        val datetime = record.datetime ?: return ""

        return try {
            val startTime = if (datetime.contains("T")) {
                LocalTime.parse(datetime.substringAfter("T").take(5))
            } else {
                val timePart = datetime.substringAfter(" ").take(5)
                LocalTime.parse(timePart)
            }

            val durationMinutes = record.seanceLength ?: record.length ?: 50
            val endTime = startTime.plusMinutes(durationMinutes.toLong())

            "${startTime.format(TIME_FORMAT)}-${endTime.format(TIME_FORMAT)}"
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccess() {
        _uiState.value = _uiState.value.copy(showSuccess = false)
    }
}
