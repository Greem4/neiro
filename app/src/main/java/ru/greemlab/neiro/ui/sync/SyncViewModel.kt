package ru.greemlab.neiro.ui.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.BuildConfig
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
     * Шорткат для быстрой авторизации в режиме разработки.
     * Использует данные из local.properties (DEV_LOGIN/DEV_PASSWORD).
     */
    fun devLogin(autoSync: Boolean = false) {
        val login = BuildConfig.DEV_LOGIN
        val pass = BuildConfig.DEV_PASSWORD
        if (login.isBlank() || pass.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "DEV_LOGIN/PASS не заданы в local.properties")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = yclientsRepository.login(login, pass)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, showSuccess = true)
                    if (autoSync) {
                        devSyncAll()
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    /**
     * Полная настройка для разработчика: сброс, логин, синхронизация и установка цен.
     */
    fun devFullSetup() {
        viewModelScope.launch {
            // 1. Сброс
            calendarRepository.clearAllData()
            yclientsRepository.logout()

            // 2. Установка дефолтных цен разработчика
            calendarRepository.updateProfile { profile ->
                profile.copy(
                    pricePerSession = 1400.0,
                    pricePerDiagnostics = 2050.0,
                    monthlyTaxAmount = 6500.0
                )
            }

            // 3. Логин и синхронизация
            devLogin(autoSync = true)
        }
    }

    /**
     * Шорткат для полного сброса всех данных приложения.
     */
    fun devResetData() {
        viewModelScope.launch {
            calendarRepository.clearAllData()
            logoutYClients()
        }
    }

    /**
     * Шорткат для сбора данных (синхронизации).
     */
    fun devSyncAll() {
        syncCurrentMonth()
    }

    /**
     * Автоматически заполняет профиль на основе данных синхронизации, если он ещё не заполнен.
     * Это позволяет пользователю сразу видеть свои данные без ручного ввода.
     */
    private suspend fun autoFillProfile(records: List<RecordData>) {
        val currentProfile = calendarRepository.userProfileFlow.first()
        // Если имя уже заполнено вручную, мы не хотим его затирать.
        // Но если оно пустое, мы можем попробовать заполнить его и другие поля.
        if (currentProfile.name.isNotBlank() && currentProfile.isRegistered) return

        calendarRepository.updateProfile { profile ->
            var updated = profile
            var changed = false

            // 1. Имя берем из аккаунта YClients, если своего нет
            if (updated.name.isBlank()) {
                yclientsUserName?.let { 
                    updated = updated.copy(name = it) 
                    changed = true
                }
            }

            if (records.isNotEmpty()) {
                // 2. Вид деятельности — попробуем угадать по самой частой услуге
                if (updated.activityType.isBlank()) {
                    val bestTitle = records.flatMap { it.services.orEmpty() }
                        .mapNotNull { it.title }
                        .groupBy { it }
                        .maxByOrNull { it.value.size }?.key
                    
                    bestTitle?.let { title ->
                        updated = updated.copy(activityType = cleanActivityType(title))
                        changed = true
                    }
                }

                // 3. Рабочие дни — все дни недели, в которые есть записи в выборке
                if (updated.workingDays.isEmpty()) {
                    val days = records.mapNotNull { parseRecordDate(it.date)?.dayOfWeek }.toSet()
                    if (days.isNotEmpty()) {
                        updated = updated.copy(workingDays = days)
                        changed = true
                    }
                }

                // 4. Цены: берем самые частые значения из списка услуг
                val allServices = records.flatMap { it.services.orEmpty() }
                val diagKeywords = listOf("диагностика", "пробн", "тест")

                val (diagServices, regularServices) = allServices.partition { service ->
                    diagKeywords.any { kw -> service.title?.contains(kw, ignoreCase = true) == true }
                }

                if (updated.pricePerSession == 0.0) {
                    val commonPrice = regularServices.mapNotNull { it.cost }
                        .groupBy { it }.maxByOrNull { it.value.size }?.key
                    if (commonPrice != null) {
                        updated = updated.copy(pricePerSession = commonPrice)
                        changed = true
                    }
                }

                if (updated.pricePerDiagnostics == 0.0) {
                    val commonDiagPrice = diagServices.mapNotNull { it.cost }
                        .groupBy { it }.maxByOrNull { it.value.size }?.key
                    if (commonDiagPrice != null) {
                        updated = updated.copy(pricePerDiagnostics = commonDiagPrice)
                        changed = true
                    }
                }
            }

            // Если удалось заполнить хотя бы имя, считаем первичную настройку завершённой
            if (updated.name.isNotBlank() && !updated.isRegistered) {
                updated = updated.copy(isRegistered = true)
                changed = true
            }

            if (changed) updated else profile
        }
    }

    private fun cleanActivityType(title: String): String {
        val prefixes = listOf(
            "прием", "консультация", "занятие", "сеанс", "урок",
            "индивидуальное", "групповое", "первичное", "повторное", "логопедическое"
        )
        var result = title.lowercase()

        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (result.startsWith(prefix)) {
                    result = result.substring(prefix.length).trim()
                    changed = true
                }
            }
        }

        // Удаляем цену или время в скобках: "Занятие (1500)" -> "Занятие"
        result = result.replace(Regex("\\(.*\\)"), "").trim()

        return result.replaceFirstChar { it.uppercase() }
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
                    autoFillProfile(records)
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
                    autoFillProfile(records)
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
     *  - записи из YClients сортируются по времени — это сохраняет порядок расписания;
     *  - поддерживается несколько занятий одного ребенка в один день (они различаются по времени);
     *  - при сопоставлении сначала ищем идеальный матч (имя + время), затем матч по имени
     *    среди записей без времени (ручных), чтобы «подхватить» их и обновить данными из API;
     *  - локальные записи, которым нет соответствия в YClients, сохраняются в конце списка;
     *  - при обновлении существующей записи сохраняется её локальное имя (если пользователь его правил).
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

            // Пул существующих записей для сопоставления.
            // При нахождении соответствия запись удаляется из пула.
            val pool = existingRaw.map { it to SessionParser.parse(it) }.toMutableList()

            // Сортируем и схлопываем только полные дубли YClients (если есть)
            val collapsedRecords = collapseDuplicateRecords(dayRecords)
            val syncedEntries = mutableListOf<String>()

            for ((_, record) in collapsedRecords) {
                val recordName = extractClientName(record)
                val recordTime = formatRecordTime(record)
                val normalizedRecordName = recordName.normalizeForDedup()

                // 1. Ищем идеальное совпадение: имя + время
                var matchIndex = pool.indexOfFirst { (_, session) ->
                    extractSessionName(session).normalizeForDedup() == normalizedRecordName &&
                            extractSessionTime(session) == recordTime
                }

                // 2. Если не нашли, ищем по имени среди записей БЕЗ времени (ручные)
                if (matchIndex == -1) {
                    matchIndex = pool.indexOfFirst { (_, session) ->
                        extractSessionName(session).normalizeForDedup() == normalizedRecordName &&
                                extractSessionTime(session).isEmpty()
                    }
                }

                // 3. Если всё равно не нашли, ищем просто по имени (мало ли время немного не совпало)
                if (matchIndex == -1) {
                    matchIndex = pool.indexOfFirst { (_, session) ->
                        extractSessionName(session).normalizeForDedup() == normalizedRecordName
                    }
                }

                if (matchIndex != -1) {
                    val (existingRawEntry, _) = pool.removeAt(matchIndex)
                    syncedEntries += updateEntryFromRecord(existingRawEntry, record, userProfile)
                } else {
                    syncedEntries += createEntryFromRecord(record, userProfile)
                    newlyAdded++
                }
            }

            // То, что осталось в пуле — это записи, которых нет в YClients (ручные или из других источников)
            val leftover = pool.map { it.first }

            // Итоговый список дня: сначала синхронизированные (в порядке YClients), потом остальные.
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
     * Свернёт полные дубли записей в YClients, если они есть.
     * Теперь НЕ схлопывает разные занятия одного клиента в один день (разное время = разные записи).
     */
    private fun collapseDuplicateRecords(
        dayRecords: List<RecordData>,
    ): List<Pair<String, RecordData>> {
        val ordered = LinkedHashMap<String, RecordData>()
        // Сортируем по времени, чтобы порядок в календаре был естественным
        for (record in dayRecords.sortedBy { it.datetime ?: it.date }) {
            val name = extractClientName(record)
            if (name.isBlank()) continue
            val time = formatRecordTime(record)

            // Ключ теперь включает время, чтобы не схлопывать разные сессии
            val key = name.normalizeForDedup() + "|" + time

            val previous = ordered[key]
            if (previous == null) {
                ordered[key] = record
            } else {
                // Если вдруг в YClients две записи на одно и то же время, берем ту, где статус «лучше»
                val previousStatus = mapAttendanceStatus(previous)
                val currentStatus = mapAttendanceStatus(record)
                if (currentStatus.mergePriority > previousStatus.mergePriority) {
                    ordered[key] = record
                }
            }
        }
        return ordered.entries.map { it.key to it.value }
    }

    private fun extractSessionName(session: Session): String = when (session) {
        is Session.Student -> session.name
        is Session.Extra -> session.name
    }

    private fun extractSessionTime(session: Session): String = when (session) {
        is Session.Student -> session.time
        is Session.Diagnostics -> session.time
        is Session.Intensive -> ""
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
                status = status,
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
                status = status,
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

            val durationMinutes = 50
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
