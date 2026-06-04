package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.theme.ProfitGreen
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.StatusExpectedMint
import ru.greemlab.neiro.theme.StatusRedBody
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

private val CancelledIndicatorRed = Color(0xFFF44336)

/** Иконки и цвета статуса — как в [ScheduleSlotItem]. */
object AttendanceStatusVisuals {
    fun icon(status: AttendanceStatus): ImageVector = when (status) {
        AttendanceStatus.ARRIVED -> Icons.Rounded.Add
        AttendanceStatus.CONFIRMED -> Icons.Rounded.Check
        AttendanceStatus.CANCELLED -> Icons.Rounded.Remove
        AttendanceStatus.EXPECTED -> Icons.Rounded.History
    }

    fun nameColor(status: AttendanceStatus): Color = when (status) {
        AttendanceStatus.ARRIVED -> ProfitGreen
        AttendanceStatus.CONFIRMED -> ExpectedAmber
        AttendanceStatus.CANCELLED -> StatusRedBody
        AttendanceStatus.EXPECTED -> StatusExpectedMint
    }

    fun indicatorColor(status: AttendanceStatus, isDiagnostics: Boolean = false): Color = when {
        status == AttendanceStatus.CANCELLED -> CancelledIndicatorRed
        isDiagnostics -> Color(0xFF5C6BC0)
        else -> ScheduleHeaderGreen
    }

    val selectableStatuses: List<AttendanceStatus> = listOf(
        AttendanceStatus.EXPECTED,
        AttendanceStatus.CONFIRMED,
        AttendanceStatus.CANCELLED,
        AttendanceStatus.ARRIVED,
    )
}
