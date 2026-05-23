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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import ru.greemlab.neiro.ui.components.daydetails.DayScheduleTimeline
import ru.greemlab.neiro.ui.components.daydetails.TimelineEntry
import ru.greemlab.neiro.ui.components.daydetails.normalizeSessionTime
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", RU_LOCALE)

/** Порог жеста «потянуть вниз» для обновления — выше стандартного Material. */
@OptIn(ExperimentalMaterial3Api::class)
private val DayDetailsPullRefreshThreshold =
    PullToRefreshDefaults.PositionalThreshold + 36.dp

private val StatusLavender = Color(0xFFA7B2FF)
private val StatusRed = Color(0xFFF44336)
private val StatusGreen = Color(0xFF4CAF50)
private val StatusMint = Color(0xFFB2DFDB)

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
    val employeePay: Double = 0.0,
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
    isArchived: Boolean = false,
    highlightSlotKey: String? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onArchive: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DayDetailsContent(
            date = date,
            initialNames = initialNames,
            userProfile = userProfile,
            isArchived = isArchived,
            highlightSlotKey = highlightSlotKey,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onDismiss = onDismiss,
            onSave = onSave,
            onArchive = onArchive,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailsContent(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    isArchived: Boolean,
    highlightSlotKey: String?,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onArchive: () -> Unit,
) {
    val currentNames = remember { mutableStateListOf<String>().apply { addAll(initialNames) } }
    var isPlanningMode by remember { mutableStateOf(false) }

    LaunchedEffect(initialNames, isPlanningMode) {
        if (!isPlanningMode) {
            currentNames.clear()
            currentNames.addAll(initialNames)
        }
    }

    val entries = remember(currentNames.toList()) {
        parseEntries(currentNames)
    }

    val stats = remember(entries, userProfile, date) {
        calculateStats(entries, userProfile, date)
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
            StatsRow(stats = stats, date = date)

            Spacer(modifier = Modifier.height(16.dp))

            // Список записей
            if (entries.isEmpty() && !isPlanningMode) {
                DayDetailsRefreshableSection(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                ) {
                    EmptySchedule()
                }
            } else if (!isPlanningMode) {
                DayScheduleTimeline(
                    entries = entries.map { entry ->
                        TimelineEntry(
                            name = entry.name,
                            time = entry.time,
                            comment = entry.comment,
                            status = entry.status,
                            isExtra = entry.isExtra,
                            extraType = entry.extraType,
                            extraAmount = entry.extraAmount,
                        )
                    },
                    date = date,
                    highlightSlotKey = highlightSlotKey,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                )
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
                                is Session.Student -> normalizeSessionTime(parsed.time)
                                is Session.Diagnostics -> normalizeSessionTime(parsed.time)
                                else -> ""
                            }
                            name == entry.name && time == entry.time
                        }

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
                                            SessionFormat.serializeIntensive(newPrice, parsed.name, parsed.status)
                                        } else {
                                            val diag = parsed as Session.Diagnostics
                                            SessionFormat.serializeDiagnostics(
                                                newPrice, diag.name, diag.status, diag.time,
                                            )
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
                                            SessionFormat.serializeIntensive(priceStr, newName, parsed.status)
                                        } else {
                                            val diag = parsed as Session.Diagnostics
                                            SessionFormat.serializeDiagnostics(
                                                priceStr, newName, diag.status, diag.time,
                                            )
                                        }
                                        currentNames[rawIndex] = updated
                                    }
                                }
                            },
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                currentNames.add(SessionFormat.serializeIntensive("0", "Новый интенсив", true))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Интенсив")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Кнопки действий
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onArchive,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isArchived) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isArchived) Icons.Rounded.Check else Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isArchived) "В архиве" else "В архив")
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("Закрыть")
                }

                if (isPlanningMode) {
                    Button(
                        onClick = { onSave(currentNames.toList()) },
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Сохранить", fontWeight = FontWeight.Bold)
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

/**
 * Строка со статистикой дня.
 */
@Composable
private fun StatsRow(stats: DayStats, date: LocalDate) {
    val today = remember { LocalDate.now() }
    val isFuture = date.isAfter(today)
    val noLessonsStarted = stats.arrivedCount == 0
    val showFutureLayout = isFuture || (date.isEqual(today) && noLessonsStarted)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showFutureLayout) {
                StatBadge(
                    label = "Подтверждено",
                    value = stats.confirmedCount.toString(),
                    color = StatusLavender,
                    modifier = Modifier.weight(1f),
                )
                StatBadge(
                    label = "Ожидают",
                    value = stats.expectedCount.toString(),
                    color = StatusMint,
                    modifier = Modifier.weight(1f),
                )
            } else {
                val totalLessons = stats.expectedCount + stats.confirmedCount + stats.arrivedCount
                StatBadge(
                    label = "Занятий",
                    value = totalLessons.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatBadge(
                    label = "Итог",
                    value = formatRubles(stats.totalMoney),
                    color = StatusGreen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Бейдж со статистикой без иконок.
 */
@Composable
private fun StatBadge(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color.copy(alpha = 0.8f),
            )
            Text(
                text = " $value",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = color,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailsRefreshableSection(
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (onRefresh == null) {
        Box(modifier = modifier) { content() }
        return
    }
    val scrollState = rememberScrollState()
    val state = rememberPullToRefreshState()
    Box(
        modifier = modifier
            .heightIn(max = 420.dp)
            .pullToRefresh(
                state = state,
                isRefreshing = isRefreshing,
                onRefresh = { onRefresh() },
                threshold = DayDetailsPullRefreshThreshold,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            content()
        }

        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
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
                time = normalizeSessionTime(session.time),
                comment = session.comment,
                status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
                employeePay = session.payAmount ?: 0.0,
            )

            is Session.Intensive -> ScheduleEntry(
                name = session.name,
                time = "",
                comment = "",
                status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
                isExtra = true,
                extraType = "Интенсив",
                extraAmount = session.amount,
            )

            is Session.Diagnostics -> ScheduleEntry(
                name = session.name,
                time = normalizeSessionTime(session.time),
                comment = "",
                status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
                isExtra = true,
                extraType = "Диагностика",
                extraAmount = session.amount,
            )
        }
    }.sortedBy { entry ->
        if (entry.time.isEmpty()) "99:99" else entry.time
    }
}

private fun calculateStats(
    entries: List<ScheduleEntry>,
    userProfile: UserProfile,
    date: LocalDate,
): DayStats {
    // TODO: разобраться с оплатой — сейчас только ставка из профиля, без payAmount и истории.
    val pricePerSession = userProfile.pricePerSession
    val pricePerDiagnostics = userProfile.pricePerDiagnostics
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
                if (!entry.isExtra) {
                    money += pricePerSession
                    // val pay = if (entry.employeePay > 0.0) entry.employeePay else pricePerSession
                } else if (entry.extraType == "Диагностика") {
                    money += if (pricePerDiagnostics > 0.0) pricePerDiagnostics else entry.extraAmount
                } else {
                    money += entry.extraAmount
                }
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
                    isArchived = false,
                    highlightSlotKey = null,
                    onDismiss = {},
                    onSave = { _ -> },
                    onArchive = {},
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
                    isArchived = true,
                    highlightSlotKey = null,
                    onDismiss = {},
                    onSave = { _ -> },
                    onArchive = {},
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
                    isArchived = true,
                    highlightSlotKey = null,
                    onDismiss = {},
                    onSave = { _ -> },
                    onArchive = {},
                )
            }
        }
    }
}
