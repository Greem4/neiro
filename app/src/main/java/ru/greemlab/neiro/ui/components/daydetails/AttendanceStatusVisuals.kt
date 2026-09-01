package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

/**
 * Иконки и цвета статуса — как в [ScheduleSlotItem].
 *
 * Значки повторяют YClients, чтобы человек, глядя в оба интерфейса, видел одно
 * и то же: пришёл — восклицательный знак, оплачено — плюсик (01.09.2026).
 */
object AttendanceStatusVisuals {
    fun icon(status: AttendanceStatus): ImageVector = when (status) {
        AttendanceStatus.PAID -> Icons.Rounded.Add
        AttendanceStatus.ARRIVED -> Icons.Rounded.PriorityHigh
        AttendanceStatus.CONFIRMED -> Icons.Rounded.Check
        AttendanceStatus.CANCELLED -> Icons.Rounded.Remove
        AttendanceStatus.EXPECTED -> Icons.Rounded.History
    }

    @Composable
    @ReadOnlyComposable
    fun nameColor(status: AttendanceStatus): Color = with(neiroSemanticColors) {
        when (status) {
            // Пришёл, но ещё не заплатил — тот же «состоявшийся» зелёный, что и
            // у оплаченного: занятие прошло, разница только в деньгах, и её
            // показывает значок.
            AttendanceStatus.PAID -> profit
            AttendanceStatus.ARRIVED -> profit
            AttendanceStatus.CONFIRMED -> expected
            AttendanceStatus.CANCELLED -> statusCancelled
            AttendanceStatus.EXPECTED -> statusExpected
        }
    }

    @Composable
    @ReadOnlyComposable
    fun indicatorColor(status: AttendanceStatus, isDiagnostics: Boolean = false): Color =
        with(neiroSemanticColors) {
            when {
                status == AttendanceStatus.CANCELLED -> statusCancelled
                isDiagnostics -> diagnostics
                else -> scheduleHeader
            }
        }

    val selectableStatuses: List<AttendanceStatus> = listOf(
        AttendanceStatus.EXPECTED,
        AttendanceStatus.CONFIRMED,
        AttendanceStatus.CANCELLED,
        AttendanceStatus.ARRIVED,
        AttendanceStatus.PAID,
    )
}
