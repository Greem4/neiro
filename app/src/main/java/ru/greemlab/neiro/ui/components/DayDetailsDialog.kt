package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.daydetails.ScheduleSlotItem
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", RU_LOCALE)

private val StatusGreen = Color(0xFF4CAF50)
private val StatusOrange = Color(0xFFFF9800)
private val StatusRed = Color(0xFFF44336)

/**
 * Данные для отображения записи в расписании.
 */
private data class ScheduleEntry(
    val name: String,
    val time: String,
    val comment: String,
    val status: AttendanceStatus,
    val isExtra: Boolean = false,
    val extraType: String = "",
    val extraAmount: Double = 0.0,
)

/**
 * Диалог просмотра расписания на выбранную дату.
 *
 * Отображает список записей в компактном виде без возможности редактирования.
 * Все изменения производятся через синхронизацию с YClients.
 */
@Composable
fun DayDetailsDialog(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean, Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DayDetailsContent(
            date = date,
            initialNames = initialNames,
            userProfile = userProfile,
            onDismiss = onDismiss,
            onSave = { onSave(it, false, false) }
        )
    }
}

@Composable
private fun DayDetailsContent(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val currentNames = remember { mutableStateListOf<String>().apply { addAll(initialNames) } }
    var isPlanningMode by remember { mutableStateOf(false) }

    val entries = remember(currentNames.toList()) {
        parseEntries(currentNames)
    }

    val stats = remember(entries, userProfile.pricePerSession) {
        calculateStats(entries, userProfile.pricePerSession)
    }

    val dateText = remember(date) { date.format(DATE_FORMAT) }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Заголовок
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )

                IconButton(
                    onClick = { isPlanningMode = !isPlanningMode },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(
                        imageVector = if (isPlanningMode) Icons.Rounded.History else Icons.Rounded.Edit,
                        contentDescription = "Режим редактирования",
                        tint = if (isPlanningMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Статистика
            StatsRow(stats = stats)

            Spacer(modifier = Modifier.height(16.dp))

            // Список записей
            if (entries.isEmpty() && !isPlanningMode) {
                EmptySchedule()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        val rawIndex = currentNames.indexOfFirst {
                            val parsed = SessionParser.parse(it)
                            val name = when (parsed) {
                                is Session.Student -> parsed.name
                                is Session.Extra -> parsed.name
                            }
                            val time = when (parsed) {
                                is Session.Student -> parsed.time
                                is Session.Diagnostics -> parsed.time
                                else -> ""
                            }
                            name == entry.name && time == entry.time
                        }

                        if (isPlanningMode) {
                            EditSessionItem(
                                entry = entry,
                                onDelete = {
                                    if (rawIndex >= 0) currentNames.removeAt(rawIndex)
                                },
                                onPriceChange = { newPrice ->
                                    if (rawIndex >= 0) {
                                        val currentRaw = currentNames[rawIndex]
                                        val parsed = SessionParser.parse(currentRaw)
                                        if (parsed is Session.Extra) {
                                            val updated = if (parsed is Session.Intensive) {
                                                SessionFormat.serializeIntensive(newPrice, parsed.name, parsed.attended)
                                            } else {
                                                SessionFormat.serializeDiagnostics(newPrice, parsed.name, parsed.attended)
                                            }
                                            currentNames[rawIndex] = updated
                                        }
                                    }
                                },
                                onNameChange = { newName ->
                                    if (rawIndex >= 0) {
                                        val currentRaw = currentNames[rawIndex]
                                        val parsed = SessionParser.parse(currentRaw)
                                        if (parsed is Session.Extra) {
                                            val priceStr = parsed.amount.toInt().toString()
                                            val updated = if (parsed is Session.Intensive) {
                                                SessionFormat.serializeIntensive(priceStr, newName, parsed.attended)
                                            } else {
                                                SessionFormat.serializeDiagnostics(priceStr, newName, parsed.attended)
                                            }
                                            currentNames[rawIndex] = updated
                                        }
                                    }
                                }
                            )
                        } else {
                            ScheduleSlotItem(
                                time = entry.time,
                                name = if (entry.isExtra && entry.extraType != "Диагностика") {
                                    "${entry.extraType}: ${formatRubles(entry.extraAmount)}"
                                } else {
                                    entry.name
                                },
                                comment = entry.comment,
                                status = entry.status,
                                isDiagnostics = entry.isExtra && entry.extraType == "Диагностика",
                            )
                        }
                    }

                    if (isPlanningMode) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            ExtraButtonsRow(
                                onAddIntensive = {
                                    currentNames.add(SessionFormat.serializeIntensive("0", "Новый интенсив", true))
                                },
                                onAddDiagnostics = {
                                    val price = userProfile.pricePerDiagnostics.toInt().toString()
                                    currentNames.add(SessionFormat.serializeDiagnostics(price, "Диагностика", true))
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопки действий
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Закрыть", fontWeight = FontWeight.Medium)
                }
                if (isPlanningMode) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(currentNames.toList()) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сохранить")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSessionItem(
    entry: ScheduleEntry,
    onDelete: () -> Unit,
    onPriceChange: (String) -> Unit,
    onNameChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (entry.isExtra) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = if (entry.extraAmount == 0.0) "" else entry.extraAmount.toInt().toString(),
                            onValueChange = onPriceChange,
                            label = { Text(entry.extraType, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.width(100.dp),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextField(
                            value = entry.name,
                            onValueChange = onNameChange,
                            placeholder = { Text("Имя") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    }
                } else {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (entry.time.isNotEmpty()) {
                        Text(text = entry.time, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "Удалить", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ExtraButtonsRow(
    onAddIntensive: () -> Unit,
    onAddDiagnostics: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = onAddIntensive,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Интенсив")
        }
        TextButton(
            onClick = onAddDiagnostics,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.Add, null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Диагностика")
        }
    }
}

/**
 * Строка со статистикой дня.
 */
@Composable
private fun StatsRow(stats: DayStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ожидают
        StatBadge(
            icon = Icons.Rounded.Add,
            count = stats.expectedCount,
            color = StatusGreen,
            label = "ожидают",
            modifier = Modifier.weight(1f),
        )

        // Подтвердили, что придут
        StatBadge(
            icon = Icons.Rounded.Check,
            count = stats.confirmedCount,
            color = StatusOrange,
            label = "подтв.",
            modifier = Modifier.weight(1f),
        )

        // Пришли (в деньги)
        StatBadge(
            icon = Icons.Rounded.Check,
            count = stats.arrivedCount,
            color = Color(0xFF2E7D32),
            label = "пришли",
            modifier = Modifier.weight(1f),
        )

        // Не пришли
        StatBadge(
            icon = Icons.Rounded.Remove,
            count = stats.cancelledCount,
            color = StatusRed,
            label = "минус",
            modifier = Modifier.weight(1f),
        )
    }

    if (stats.totalMoney > 0) {
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = "Итого: ${formatRubles(stats.totalMoney)}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Бейдж со статистикой.
 */
@Composable
private fun StatBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = " $count",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

/**
 * Пустое расписание.
 */
@Composable
private fun EmptySchedule() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.EventBusy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Нет записей",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

private data class DayStats(
    val expectedCount: Int,
    val confirmedCount: Int,
    val arrivedCount: Int,
    val cancelledCount: Int,
    val totalMoney: Double,
)

private fun parseEntries(rawNames: List<String>): List<ScheduleEntry> {
    return rawNames.mapNotNull { raw ->
        if (raw.isBlank()) return@mapNotNull null

        val session = SessionParser.parse(raw)
        val isDeleted = session.isEffectivelyDeleted()

        when (session) {
            is Session.Student -> ScheduleEntry(
                name = session.name,
                time = session.time,
                comment = session.comment,
                status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
            )

            is Session.Intensive -> ScheduleEntry(
                name = session.name,
                time = "",
                comment = "",
                status = when {
                    isDeleted -> AttendanceStatus.CANCELLED
                    session.attended -> AttendanceStatus.ARRIVED
                    else -> AttendanceStatus.EXPECTED
                },
                isExtra = true,
                extraType = "Интенсив",
                extraAmount = session.amount,
            )

            is Session.Diagnostics -> ScheduleEntry(
                name = session.name,
                time = session.time,
                comment = "",
                status = when {
                    isDeleted -> AttendanceStatus.CANCELLED
                    session.attended -> AttendanceStatus.ARRIVED
                    else -> AttendanceStatus.EXPECTED
                },
                isExtra = true,
                extraType = "Диагностика",
                extraAmount = session.amount,
            )
        }
    }.sortedBy { entry ->
        if (entry.time.isEmpty()) "99:99" else entry.time
    }
}

private fun calculateStats(entries: List<ScheduleEntry>, pricePerSession: Double): DayStats {
    var expected = 0
    var confirmed = 0
    var arrived = 0
    var cancelled = 0
    var money = 0.0

    for (entry in entries) {
        when (entry.status) {
            AttendanceStatus.EXPECTED -> expected++
            AttendanceStatus.CONFIRMED -> confirmed++
            AttendanceStatus.ARRIVED -> {
                arrived++
                if (!entry.isExtra) money += pricePerSession
                else money += entry.extraAmount
            }
            AttendanceStatus.CANCELLED -> cancelled++
        }
    }

    return DayStats(
        expectedCount = expected,
        confirmedCount = confirmed,
        arrivedCount = arrived,
        cancelledCount = cancelled,
        totalMoney = money,
    )
}

@Preview(showBackground = true, name = "Day Details Light")
@Composable
private fun DayDetailsLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.LightGray) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = LocalDate.now(),
                    initialNames = listOf(
                        "Шахабутдинов Тимур|0|10:00-10:50|+79684123493|5л",
                        "Зорин Владимир|1|11:00-11:50|+79151234538|2.8Г",
                        "Медников Владимир|0|12:00-13:00|+79621234536|5,9/ Рома 2,11л",
                        "Савельев Михаил|3|13:00-13:50|+79621234582|5л",
                        "Якуборов Рашит|2|14:00-14:50|+79681234569|6,11л",
                    ),
                    userProfile = UserProfile(pricePerSession = 1400.0),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Day Details Dark")
@Composable
private fun DayDetailsDarkPreview() {
    NeiroTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = LocalDate.now(),
                    initialNames = listOf(
                        "Сухова Мария|0|15:00-15:50|+79931234500|5 лет",
                        "Моторнов Егор|3|16:00-16:50|+79631234575|5л",
                    ),
                    userProfile = UserProfile(pricePerSession = 1400.0),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Day Details Empty")
@Composable
private fun DayDetailsEmptyPreview() {
    NeiroTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.LightGray) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = LocalDate.now(),
                    initialNames = emptyList(),
                    userProfile = UserProfile(pricePerSession = 1400.0),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
    }
}
