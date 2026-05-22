package ru.greemlab.neiro.notifications

import android.content.Context
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.SessionFormat
import java.time.LocalDate

/**
 * Симуляция diff календаря после синхронизации YClients (только debug-сборка).
 */
object SessionNotificationSyncSimulation {

    private const val CLIENT_NEW = "Sim Новая запись"
    private const val CLIENT_CANCEL = "Sim Отмена"
    private const val CLIENT_RESCHEDULE = "Sim Перенос"
    private const val CLIENT_DELETE = "Sim Удаление"
    private const val CLIENT_STATUS = "Sim Статус"
    private const val CLIENT_MULTI_A = "Sim Группа А"
    private const val CLIENT_MULTI_B = "Sim Группа Б"

    fun resetState(context: Context) {
        SessionNotificationPreferences.get(context.applicationContext).resetSyncNotificationState()
    }

    suspend fun simulateNewBooking(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(today),
            after = day(today, entry(CLIENT_NEW, "14:00-15:00", AttendanceStatus.EXPECTED)),
        )
    }

    suspend fun simulateCancelled(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(today, entry(CLIENT_CANCEL, "10:00-11:00", AttendanceStatus.EXPECTED)),
            after = day(today, entry(CLIENT_CANCEL, "10:00-11:00", AttendanceStatus.CANCELLED)),
        )
    }

    suspend fun simulateRescheduled(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(today, entry(CLIENT_RESCHEDULE, "10:00-11:00", AttendanceStatus.EXPECTED)),
            after = day(today, entry(CLIENT_RESCHEDULE, "14:00-15:00", AttendanceStatus.EXPECTED)),
        )
    }

    suspend fun simulateDeleted(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(today, entry(CLIENT_DELETE, "12:00-13:00", AttendanceStatus.EXPECTED)),
            after = day(today),
        )
    }

    suspend fun simulateClientConfirmed(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(today, entry(CLIENT_STATUS, "11:00-12:00", AttendanceStatus.EXPECTED)),
            after = day(today, entry(CLIENT_STATUS, "11:00-12:00", AttendanceStatus.CONFIRMED)),
        )
    }

    suspend fun simulateClientArrived(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(today, entry(CLIENT_STATUS, "11:00-12:00", AttendanceStatus.EXPECTED)),
            after = day(today, entry(CLIENT_STATUS, "11:00-12:00", AttendanceStatus.ARRIVED)),
        )
    }

    suspend fun simulateMultipleEvents(context: Context) {
        val today = LocalDate.now()
        run(
            context,
            before = day(
                today,
                entry(CLIENT_MULTI_A, "09:00-10:00", AttendanceStatus.EXPECTED),
            ),
            after = day(
                today,
                entry(CLIENT_MULTI_B, "16:00-17:00", AttendanceStatus.EXPECTED),
                entry(CLIENT_MULTI_A, "09:00-10:00", AttendanceStatus.CANCELLED),
            ),
        )
    }

    private suspend fun run(
        context: Context,
        before: Map<LocalDate, List<String>>,
        after: Map<LocalDate, List<String>>,
    ) {
        SessionNotificationCoordinator.simulateSyncForDev(
            context.applicationContext,
            before,
            after,
        )
    }

    private fun day(date: LocalDate, vararg entries: String): Map<LocalDate, List<String>> =
        mapOf(date to entries.toList())

    private fun entry(
        name: String,
        time: String,
        status: AttendanceStatus,
    ): String = SessionFormat.serializeStudentExtended(
        name = name,
        status = status,
        time = time,
    )
}
