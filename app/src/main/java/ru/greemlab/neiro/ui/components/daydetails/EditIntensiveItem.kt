package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val PickerTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val TIME_GRID_COLUMNS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditIntensiveItem(
    amountText: String,
    time: String,
    timeSlotOptions: List<String>,
    requestFocus: Boolean,
    focusRequester: FocusRequester,
    onAmountChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    val normalizedTime = remember(time) { normalizeSessionTime(time) }
    val isCustomTimeSelected = normalizedTime.isNotEmpty() &&
        timeSlotOptions.none { normalizeSessionTime(it) == normalizedTime }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    TextField(
                        value = amountText,
                        onValueChange = { onAmountChange(it.filter(Char::isDigit)) },
                        placeholder = {
                            Text(
                                "Сумма (₽)",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (requestFocus) Modifier.focusRequester(focusRequester) else Modifier,
                            ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = transparentIntensiveFieldColors(),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    Text(
                        text = "Интенсив",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Удалить интенсив",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Время",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (normalizedTime.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = formatTimeSlotLabel(normalizedTime),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                IntensiveTimeSlotGrid(
                    timeSlotOptions = timeSlotOptions,
                    normalizedTime = normalizedTime,
                    isCustomTimeSelected = isCustomTimeSelected,
                    onTimeChange = onTimeChange,
                    onCustomTimeClick = { showTimePicker = true },
                )
            }
        }
    }

    if (showTimePicker) {
        val initial = parseTimeRangeStart(normalizedTime) ?: LocalTime.of(10, 0)
        val pickerState = rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Время") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = LocalTime.of(pickerState.hour, pickerState.minute)
                        val end = start.plusMinutes(SESSION_DURATION_MINUTES.toLong())
                        onTimeChange(
                            "${start.format(PickerTimeFormat)}-${end.format(PickerTimeFormat)}",
                        )
                        showTimePicker = false
                    },
                ) {
                    Text("Готово")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text("Отмена")
                }
            },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@Composable
private fun IntensiveTimeSlotGrid(
    timeSlotOptions: List<String>,
    normalizedTime: String,
    isCustomTimeSelected: Boolean,
    onTimeChange: (String) -> Unit,
    onCustomTimeClick: () -> Unit,
) {
    val rows = remember(timeSlotOptions) {
        timeSlotOptions
            .map { normalizeSessionTime(it) }
            .distinct()
            .chunked(TIME_GRID_COLUMNS)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { rowSlots ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowSlots.forEach { slotNormalized ->
                    IntensiveTimeSlotChip(
                        label = formatTimeSlotLabel(slotNormalized),
                        selected = normalizedTime.isNotEmpty() && normalizedTime == slotNormalized,
                        onClick = { onTimeChange(slotNormalized) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(TIME_GRID_COLUMNS - rowSlots.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IntensiveTimeSlotChip(
                label = if (isCustomTimeSelected) {
                    formatTimeSlotLabel(normalizedTime)
                } else {
                    "Другое"
                },
                selected = isCustomTimeSelected,
                onClick = onCustomTimeClick,
                modifier = Modifier.weight(1f),
                leadingIcon = if (!isCustomTimeSelected) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else {
                    null
                },
            )
            repeat(TIME_GRID_COLUMNS - 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IntensiveTimeSlotChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val border = if (selected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.size(3.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun transparentIntensiveFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
