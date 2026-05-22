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
     * Диапазон для автосинхронизации: предыдущий, текущий и следующий месяц.
     */
    suspend fun syncDefaultAutoRange(): SyncOutcome {
        val (start, end) = defaultAutoSyncRange()
        return syncDateRange(start, end)
    }

    suspend fun syncDateRange(startDate: LocalDate, endDate: LocalDate): SyncOutcome =
        syncMutex.withLock {
            when (val result = yclientsRepository.getRecords(startDate, endDate)) {
                is ApiResult.Success -> {
                    val records = result.data
                    val dayDataBefore = calendarRepository.dayDataFlow.first()
                    autoFillProfile(records)
                    val syncedCount = mergeRecordsToCalendar(records)
                    val dayDataAfter = calendarRepository.dayDataFlow.first()
                    syncPreferences.recordSuccessfulSync()
                    SessionNotificationCoordinator.onSyncCompleted(
                        appContext,
                        dayDataBefore,
                        dayDataAfter,
                    )
                    SyncOutcome.Success(syncedCount)
                }

                is ApiResult.Error -> SyncOutcome.Failure(result.message)
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
            val pool = existingRaw.map { it to SessionParser.parse(it) }.toMutableList()
            val collapsedRecords = collapseDuplicateRecords(dayRecords)
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

            val leftover = pool.map { it.first }
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
        is Session.Intensive -> ""
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

        fun defaultAutoSyncRange(): Pair<LocalDate, LocalDate> {
            val center = YearMonth.now()
            return center.minusMonths(1).atDay(1) to center.plusMonths(1).atEndOfMonth()
        }

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
