package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

/** Иконки и цвета статуса — как в [ScheduleSlotItem]. */
object AttendanceStatusVisuals {
    fun icon(status: AttendanceStatus): ImageVector = when (status) {
        AttendanceStatus.ARRIVED -> Icons.Rounded.Add
        AttendanceStatus.CONFIRMED -> Icons.Rounded.Check
        AttendanceStatus.CANCELLED -> Icons.Rounded.Remove
        AttendanceStatus.EXPECTED -> Icons.Rounded.History
    }

    @Composable
    @ReadOnlyComposable
    fun nameColor(status: AttendanceStatus): Color = with(neiroSemanticColors) {
        when (status) {
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
    )
}
