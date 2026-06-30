package ru.greemlab.neiro.sync

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.greemlab.neiro.data.CalendarRepository
import ru.greemlab.neiro.data.network.ApiResult
import ru.greemlab.neiro.data.network.RecordData
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.calendar.totalAmount
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Слияние записей YClients с локальным календарём.
 * Используется из UI ([ru.greemlab.neiro.ui.sync.SyncViewModel]) и фоновой автосинхронизации.
 */
class YClientsCalendarSync(
    private val appContext: Context,
    private val yclientsRepository: YClientsRepository,
    private val calendarRepository: CalendarRepository,
    private val syncPreferences: SyncPreferences,
) {

    private val syncMutex = Mutex()

    val yclientsUserName: String? get() = yclientsRepository.userName

    suspend fun syncMonth(yearMonth: YearMonth): SyncOutcome =
        syncDateRange(yearMonth.atDay(1), yearMonth.atEndOfMonth())

    /**
     * Ежедневная автосинхронизация: текущий и следующий месяц (записи «вперёд»).
     * Полная история — только вручную из профиля.
     */
    suspend fun syncDefaultAutoRange(): SyncOutcome {
        val (start, end) = defaultAutoSyncRange()
        return syncDateRange(start, end, recordSuccessfulSync = true)
    }

    /**
     * Live-опрос для уведомлений о будущих записях: текущий и следующий месяц,
     * без сдвига метки «последней синхронизации» (её обновляет [syncDefaultAutoRange]).
     *
     * Между полными подтяжками запрашивает только записи с `changed_after` —
     * пуши приходят так же часто, трафик в десятки раз меньше.
     */
    suspend fun refreshLiveRange(): SyncOutcome {
        val (start, end) = defaultLiveRefreshRange()
        return syncMutex.withLock {
            if (shouldRunFullLiveSync()) {
                return@withLock applyFullLiveSync(start, end)
            }
            refreshLiveIncrementalLocked(start, end)
        }
    }

    private fun shouldRunFullLiveSync(): Boolean =
        isFullLiveSyncDue(
            lastFullLiveSyncEpochMillis = syncPreferences.lastFullLiveSyncEpochMillis(),
            nowMillis = System.currentTimeMillis(),
        )

    private suspend fun applyFullLiveSync(
        startDate: LocalDate,
        endDate: LocalDate,
    ): SyncOutcome {
        val outcome = syncDateRangeLocked(startDate, endDate, recordSuccessfulSync = false)
        if (outcome is SyncOutcome.Success) {
            syncPreferences.recordLivePoll()
            syncPreferences.recordFullLiveSync()
        }
        return outcome
    }

    private suspend fun refreshLiveIncrementalLocked(
        startDate: LocalDate,
        endDate: LocalDate,
    ): SyncOutcome {
        val lastPoll = syncPreferences.lastLivePollEpochMillis()
        val changedAfter = if (lastPoll > 0L) {
            Instant.ofEpochMilli(lastPoll)
        } else {
            Instant.EPOCH
        }
        when (
            val result = yclientsRepository.getRecordsChangedSince(
                changedAfter = changedAfter,
                startDate = startDate,
                endDate = endDate,
            )
        ) {
            is ApiResult.Success -> {
                val records = result.data
                if (records.isEmpty()) {
                    syncPreferences.recordLivePoll()
                    return SyncOutcome.Success(0)
                }

                val dayDataBefore = calendarRepository.dayDataFlow.first()
                autoFillProfile(records)
                val outcome = mergeIncrementalRecords(
                    records = records,
                    rangeStart = startDate,
                    rangeEnd = endDate,
                )
                if (outcome is SyncOutcome.Failure) {
                    return outcome
                }
                val syncedCount = (outcome as SyncOutcome.Success).newlyAdded
                syncPreferences.recordLivePoll()
                val dayDataAfter = calendarRepository.dayDataFlow.first()
                SessionNotificationCoordinator.onCalendarUpdatedFromApi(
                    appContext,
                    dayDataBefore,
                    dayDataAfter,
                )
                return SyncOutcome.Success(syncedCount)
            }

            is ApiResult.Error -> return applyFullLiveSync(startDate, endDate)
        }
    }

    suspend fun syncDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        recordSuccessfulSync: Boolean = true,
    ): SyncOutcome = syncMutex.withLock {
        syncDateRangeLocked(startDate, endDate, recordSuccessfulSync)
    }

    private suspend fun syncDateRangeLocked(
        startDate: LocalDate,
        endDate: LocalDate,
        recordSuccessfulSync: Boolean,
    ): SyncOutcome {
        when (val result = yclientsRepository.getRecords(startDate, endDate)) {
            is ApiResult.Success -> {
                val records = result.data
                val dayDataBefore = calendarRepository.dayDataFlow.first()
                if (!shouldApplySyncMerge(records, dayDataBefore, startDate, endDate)) {
                    return SyncOutcome.Failure(
                        "YClients вернул пустой ответ, а в календаре есть записи — " +
                            "слияние пропущено, локальные данные сохранены",
                    )
                }
                autoFillProfile(records)
                val syncedCount = mergeRecordsToCalendar(records, startDate, endDate)
                val dayDataAfter = calendarRepository.dayDataFlow.first()
                if (recordSuccessfulSync) {
                    syncPreferences.recordSuccessfulSync()
                }
                SessionNotificationCoordinator.onCalendarUpdatedFromApi(
                    appContext,
                    dayDataBefore,
                    dayDataAfter,
                )
                return SyncOutcome.Success(syncedCount)
            }

            is ApiResult.Error -> return SyncOutcome.Failure(result.message)
        }
    }

    private suspend fun autoFillProfile(records: List<RecordData>) {
        val currentProfile = calendarRepository.userProfileFlow.first()
        if (currentProfile.name.isNotBlank() && currentProfile.isRegistered) return

        calendarRepository.updateProfile { profile ->
            var updated = profile
            var changed = false

            if (updated.name.isBlank()) {
                yclientsUserName?.let {
                    updated = updated.copy(name = it)
                    changed = true
                }
            }

            if (records.isNotEmpty()) {
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

                if (updated.workingDays.isEmpty()) {
                    val days = records.mapNotNull { parseRecordDate(it.date)?.dayOfWeek }.toSet()
                    if (days.isNotEmpty()) {
                        updated = updated.copy(workingDays = days)
                        changed = true
                    }
                }

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

                if (updated.pricePerIntensiveChild == 0.0) {
                    val intensiveServices = allServices.filter { service ->
                        service.title?.contains("интенсив", ignoreCase = true) == true
                    }
                    val commonIntensivePrice = intensiveServices.mapNotNull { it.cost }
                        .groupBy { it }.maxByOrNull { it.value.size }?.key
                    if (commonIntensivePrice != null) {
                        updated = updated.copy(pricePerIntensiveChild = commonIntensivePrice)
                        changed = true
                    }
                }
            }

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
            "индивидуальное", "групповое", "первичное", "повторное", "логопедическое",
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

        result = result.replace(Regex("\\(.*\\)"), "").trim()
        return result.replaceFirstChar { it.uppercase() }
    }

    private suspend fun mergeRecordsToCalendar(
        records: List<RecordData>,
        startDate: LocalDate,
        endDate: LocalDate,
        clearMissingDaysInRange: Boolean = true,
    ): Int {
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
            if (isInCurrentMonth(date)) {
                val merged = buildAuthoritativeDayFromApi(dayRecords, userProfile)
                if (merged != currentDayData[date].orEmpty()) {
                    if (merged.isEmpty()) {
                        currentDayData.remove(date)
                    } else {
                        currentDayData[date] = merged
                    }
                    changedDays++
                }
                continue
            }

            val existingRaw = currentDayData[date].orEmpty()
            val pool = existingRaw.map { it to SessionParser.parse(it) }.toMutableList()
            val (deletedDayRecords, remainingDayRecords) = dayRecords.partition { it.deleted == true }
            for (record in deletedDayRecords) {
                removeMatchingSessions(pool, record)
            }
            val (intensiveDayRecords, regularDayRecords) = remainingDayRecords.partition { isIntensiveRecord(it) }
            purgeIntensiveStudentsFromPool(pool, intensiveDayRecords)
            val collapsedRecords = collapseDuplicateRecords(regularDayRecords)
            val syncedEntries = mutableListOf<String>()

            for ((_, record) in collapsedRecords) {
                val recordName = extractClientName(record)
                val recordTime = formatRecordTime(record)
                val normalizedRecordName = recordName.normalizeForDedup()

                var matchIndex = pool.indexOfFirst { (_, session) ->
                    extractSessionName(session).normalizeForDedup() == normalizedRecordName &&
                        extractSessionTime(session) == recordTime
                }

                if (matchIndex == -1) {
                    matchIndex = pool.indexOfFirst { (_, session) ->
                        extractSessionName(session).normalizeForDedup() == normalizedRecordName &&
                            extractSessionTime(session).isEmpty()
                    }
                }

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

            newlyAdded += mergeIntensivesFromApi(intensiveDayRecords, userProfile, pool, syncedEntries)

            val merged = syncedEntries + retainManualLocalEntries(pool)
            if (merged != existingRaw) {
                currentDayData[date] = merged
                changedDays++
            }
        }

        if (clearMissingDaysInRange) {
            var date = startDate
            while (!date.isAfter(endDate)) {
                if (date !in recordsByDate) {
                    if (isInCurrentMonth(date)) {
                        if (currentDayData.remove(date) != null) {
                            changedDays++
                        }
                    } else {
                        val existingRaw = currentDayData[date] ?: run {
                            date = date.plusDays(1)
                            continue
                        }
                        val merged = retainManualLocalEntries(
                            existingRaw.map { it to SessionParser.parse(it) },
                        )
                        if (merged != existingRaw) {
                            if (merged.isEmpty()) {
                                currentDayData.remove(date)
                            } else {
                                currentDayData[date] = merged
                            }
                            changedDays++
                        }
                    }
                }
                date = date.plusDays(1)
            }
        }

        if (changedDays > 0) {
            calendarRepository.saveDayData(currentDayData)
        }

        return newlyAdded
    }

    /**
     * Инкрементальный live-опрос: текущий месяц подтягиваем целиком и перезаписываем,
     * остальной диапазон — точечное слияние по changed_after.
     */
    private suspend fun mergeIncrementalRecords(
        records: List<RecordData>,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
    ): SyncOutcome {
        val (currentMonthTouching, otherRecords) = records.partition { record ->
            parseRecordDate(record.date)?.let(::isInCurrentMonth) == true
        }

        var syncedCount = 0
        if (currentMonthTouching.isNotEmpty()) {
            val (monthStart, monthEnd) = currentCalendarMonthRange()
            when (val fullMonth = yclientsRepository.getRecords(monthStart, monthEnd)) {
                is ApiResult.Success -> {
                    syncedCount += mergeRecordsToCalendar(
                        records = fullMonth.data,
                        startDate = monthStart,
                        endDate = monthEnd,
                        clearMissingDaysInRange = true,
                    )
                }

                is ApiResult.Error -> return applyFullLiveSync(rangeStart, rangeEnd)
            }
        }

        if (otherRecords.isNotEmpty()) {
            syncedCount += mergeRecordsToCalendar(
                records = otherRecords,
                startDate = rangeStart,
                endDate = rangeEnd,
                clearMissingDaysInRange = false,
            )
        }

        return SyncOutcome.Success(syncedCount)
    }

    /** День текущего месяца целиком из ответа API — без локальных «хвостов». */
    private fun buildAuthoritativeDayFromApi(
        dayRecords: List<RecordData>,
        userProfile: UserProfile,
    ): List<String> {
        val pool = mutableListOf<Pair<String, Session>>()
        val activeRecords = dayRecords.filter { it.deleted != true }
        val (intensiveDayRecords, regularDayRecords) = activeRecords.partition { isIntensiveRecord(it) }
        purgeIntensiveStudentsFromPool(pool, intensiveDayRecords)
        val syncedEntries = mutableListOf<String>()

        for ((_, record) in collapseDuplicateRecords(regularDayRecords)) {
            syncedEntries += createEntryFromRecord(record, userProfile)
        }

        mergeIntensivesFromApi(intensiveDayRecords, userProfile, pool, syncedEntries)
        return syncedEntries
    }

    private fun removeMatchingSessions(
        pool: MutableList<Pair<String, Session>>,
        record: RecordData,
    ) {
        val normalizedName = extractClientName(record).normalizeForDedup()
        if (normalizedName.isEmpty()) return
        val recordTime = formatRecordTime(record)
        pool.removeAll { (_, session) ->
            extractSessionName(session).normalizeForDedup() == normalizedName &&
                (recordTime.isBlank() ||
                    extractSessionTime(session) == recordTime ||
                    extractSessionTime(session).isEmpty())
        }
    }

    /**
     * После слияния с API оставляем только ручные интенсивы (их нет в YClients).
     * Записи учеников/диагностики без пары в API удаляются — иначе «призраки»
     * остаются в статусе «ожидание», хотя запись в YClients уже снята.
     *
     * Слияние с удалением выполняется только при [shouldApplySyncMerge] — при ошибках
     * сети, пустом подозрительном ответе или обрезанной пагинации календарь не трогаем.
     */
    private fun retainManualLocalEntries(pool: List<Pair<String, Session>>): List<String> =
        pool.map { it.first }.filter { raw -> raw.startsWith(SessionFormat.INTENSIVE_PREFIX) }

    private fun isIntensiveRecord(record: RecordData): Boolean =
        record.services?.any { it.title?.contains("интенсив", ignoreCase = true) == true } == true

    private fun purgeIntensiveStudentsFromPool(
        pool: MutableList<Pair<String, Session>>,
        intensiveRecords: List<RecordData>,
    ) {
        if (intensiveRecords.isEmpty()) return
        val byTime = intensiveRecords.groupBy { formatRecordTime(it, intensive = true) }
        for ((time, records) in byTime) {
            val names = records.map { extractClientName(it).normalizeForDedup() }.toSet()
            pool.removeAll { (_, session) ->
                session is Session.Student &&
                    extractSessionTime(session) == time &&
                    session.name.normalizeForDedup() in names
            }
        }
    }

    private fun mergeIntensivesFromApi(
        records: List<RecordData>,
        userProfile: UserProfile,
        pool: MutableList<Pair<String, Session>>,
        syncedEntries: MutableList<String>,
    ): Int {
        if (records.isEmpty()) return 0
        var added = 0
        val groups = records.groupBy { recordStartTimeKey(it) }
        for ((_, group) in groups) {
            if (group.isEmpty()) continue
            val time = formatRecordTime(group.first(), intensive = true)
            val children = group.map { record ->
                Session.IntensiveChild(
                    name = extractClientName(record),
                    status = mapAttendanceStatus(record),
                    phone = record.client?.phone.orEmpty(),
                    comment = record.comment.orEmpty(),
                )
            }
            val status = children.maxByOrNull { it.status.mergePriority }?.status
                ?: AttendanceStatus.EXPECTED
            val unitPrice = userProfile.pricePerIntensiveChild
            val amount = if (unitPrice > 0.0) {
                unitPrice * children.count { it.status != AttendanceStatus.CANCELLED }
            } else {
                0.0
            }
            val entry = SessionFormat.serializeIntensive(
                price = if (amount > 0.0) amount.toLong().toString() else "",
                name = "Интенсив",
                status = status,
                time = time,
                children = children,
            )
            pool.removeAll { (_, session) ->
                session is Session.Intensive && extractSessionTime(session) == time
            }
            syncedEntries += entry
            added++
        }
        return added
    }

    private fun recordStartTimeKey(record: RecordData): String {
        val datetime = record.datetime ?: return ""
        return if (datetime.contains("T")) {
            datetime.substringAfter("T").take(5)
        } else {
            datetime.substringAfter(" ").take(5)
        }
    }

    private fun collapseDuplicateRecords(
        dayRecords: List<RecordData>,
    ): List<Pair<String, RecordData>> {
        val ordered = LinkedHashMap<String, RecordData>()
        for (record in dayRecords.sortedBy { it.datetime ?: it.date }) {
            val name = extractClientName(record)
            if (name.isBlank()) continue
            val time = formatRecordTime(record)
            val key = name.normalizeForDedup() + "|" + time

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

    private fun extractSessionName(session: Session): String = when (session) {
        is Session.Student -> session.name
        is Session.Extra -> session.name
    }

    private fun extractSessionTime(session: Session): String = when (session) {
        is Session.Student -> session.time
        is Session.Diagnostics -> session.time
        is Session.Intensive -> session.time
    }

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

    private fun createEntryFromRecord(record: RecordData, userProfile: UserProfile): String {
        val clientName = extractClientName(record)
        val status = mapAttendanceStatus(record)

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

    private fun updateEntryFromRecord(
        existingEntry: String,
        record: RecordData,
        userProfile: UserProfile,
    ): String {
        val session = SessionParser.parse(existingEntry)
        val clientName = extractClientName(record)
        val status = mapAttendanceStatus(record)

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

        if (session !is Session.Student) {
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

    private fun mapAttendanceStatus(record: RecordData): AttendanceStatus =
        AttendanceStatus.resolveFromRecord(record.attendance, record.visitAttendance)

    private fun formatRecordTime(record: RecordData, intensive: Boolean = false): String {
        val datetime = record.datetime ?: return ""

        return try {
            val startTime = if (datetime.contains("T")) {
                LocalTime.parse(datetime.substringAfter("T").take(5))
            } else {
                val timePart = datetime.substringAfter(" ").take(5)
                LocalTime.parse(timePart)
            }

            val durationMinutes = if (intensive) {
                (record.seanceLength ?: record.length)
                    ?.let { it / 60 }
                    ?.takeIf { mins -> mins in 1..180 }
                    ?: 90
            } else {
                50
            }
            val endTime = startTime.plusMinutes(durationMinutes.toLong())

            "${startTime.format(TIME_FORMAT)}-${endTime.format(TIME_FORMAT)}"
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Можно ли доверять ответу API и удалять локальные записи без пары.
         *
         * Пустой список при наличии учеников/диагностики в диапазоне — признак сбоя
         * (сеть, неверный staff_id, глюк API), а не «в YClients ничего нет».
         */
        internal fun shouldApplySyncMerge(
            records: List<RecordData>,
            localDayData: Map<LocalDate, List<String>>,
            startDate: LocalDate,
            endDate: LocalDate,
        ): Boolean {
            if (records.isNotEmpty()) return true
            return countYClientsManagedLocalEntries(localDayData, startDate, endDate) == 0
        }

        /** Ученики и диагностика в диапазоне (интенсивы не считаются — они только локальные). */
        internal fun countYClientsManagedLocalEntries(
            localDayData: Map<LocalDate, List<String>>,
            startDate: LocalDate,
            endDate: LocalDate,
        ): Int {
            var count = 0
            var date = startDate
            while (!date.isAfter(endDate)) {
                localDayData[date]?.forEach { raw ->
                    if (!raw.startsWith(SessionFormat.INTENSIVE_PREFIX)) count++
                }
                date = date.plusDays(1)
            }
            return count
        }

        /** Ежедневный авто: с 1-го текущего месяца по конец следующего. */
        fun defaultAutoSyncRange(): Pair<LocalDate, LocalDate> {
            val current = YearMonth.now()
            val next = current.plusMonths(1)
            return current.atDay(1) to next.atEndOfMonth()
        }

        /** Live-опрос: текущий и следующий месяц (будущие записи и пуши). */
        fun defaultLiveRefreshRange(): Pair<LocalDate, LocalDate> = defaultAutoSyncRange()

        /** Полная подтяжка live-диапазона раз в 6 ч — страховка от переносов и «призраков». */
        internal const val FULL_LIVE_SYNC_INTERVAL_MS = 6L * 60L * 60L * 1000L

        internal fun isFullLiveSyncDue(
            lastFullLiveSyncEpochMillis: Long,
            nowMillis: Long,
        ): Boolean {
            if (lastFullLiveSyncEpochMillis <= 0L) return true
            return nowMillis - lastFullLiveSyncEpochMillis >= FULL_LIVE_SYNC_INTERVAL_MS
        }

        fun currentCalendarMonthRange(): Pair<LocalDate, LocalDate> {
            val month = YearMonth.now()
            return month.atDay(1) to month.atEndOfMonth()
        }

        /** Текущий календарный месяц — источник правды YClients, локаль не сохраняется. */
        internal fun isInCurrentMonth(
            date: LocalDate,
            month: YearMonth = YearMonth.now(),
        ): Boolean = !date.isBefore(month.atDay(1)) && !date.isAfter(month.atEndOfMonth())

        @Volatile
        private var instance: YClientsCalendarSync? = null

        fun get(context: Context): YClientsCalendarSync {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: YClientsCalendarSync(
                    appContext = appContext,
                    yclientsRepository = YClientsRepository.getInstance(appContext),
                    calendarRepository = ru.greemlab.neiro.data.CalendarDataStoreProvider.get(appContext),
                    syncPreferences = SyncPreferences.get(appContext),
                ).also { instance = it }
            }
        }
    }
}
