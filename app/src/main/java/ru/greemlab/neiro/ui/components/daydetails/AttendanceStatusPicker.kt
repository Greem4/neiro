package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.R
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

@Composable
fun AttendanceStatusPickerIcon(
    status: AttendanceStatus,
    onStatusSelected: (AttendanceStatus) -> Unit,
    modifier: Modifier = Modifier,
    isDiagnostics: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val indicatorColor = AttendanceStatusVisuals.indicatorColor(status, isDiagnostics)

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(24.dp),
            shape = CircleShape,
            color = neiroSemanticColors.statusIconSurface,
            onClick = { expanded = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AttendanceStatusVisuals.icon(status),
                    contentDescription = stringResource(R.string.attendance_status_picker_cd),
                    tint = indicatorColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AttendanceStatusVisuals.selectableStatuses.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = AttendanceStatusVisuals.icon(option),
                                contentDescription = null,
                                tint = AttendanceStatusVisuals.indicatorColor(option, isDiagnostics),
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = attendanceStatusLabel(option),
                                modifier = Modifier.padding(start = 12.dp),
                                color = if (option == status) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (option != status) onStatusSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
fun AttendanceStatusReadOnlyIcon(
    status: AttendanceStatus,
    modifier: Modifier = Modifier,
    isDiagnostics: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val indicatorColor = AttendanceStatusVisuals.indicatorColor(status, isDiagnostics)
    // Тач-зона расширяется до минимума Material (48dp) только когда по иконке
    // действительно можно нажать: без onClick это просто индикатор статуса, и
    // лишние 24dp отъедали бы ширину у имени ученика в строке расписания.
    // minimumInteractiveComponentSize, а не ручной Box: тем же способом это
    // делает AttendanceStatusPickerIcon выше, и он уважает выключение минимума
    // через LocalMinimumInteractiveComponentSize в плотных списках.
    val touchTarget = if (onClick != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    } else {
        Modifier
    }
    Surface(
        modifier = modifier
            .then(touchTarget)
            .size(24.dp),
        shape = CircleShape,
        color = neiroSemanticColors.statusIconSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = AttendanceStatusVisuals.icon(status),
                contentDescription = null,
                tint = indicatorColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun attendanceStatusLabel(status: AttendanceStatus): String = when (status) {
    AttendanceStatus.EXPECTED -> stringResource(R.string.attendance_status_expected)
    AttendanceStatus.CONFIRMED -> stringResource(R.string.attendance_status_confirmed)
    AttendanceStatus.CANCELLED -> stringResource(R.string.attendance_status_cancelled)
    AttendanceStatus.ARRIVED -> stringResource(R.string.attendance_status_arrived)
    AttendanceStatus.PAID -> stringResource(R.string.attendance_status_paid)
}
