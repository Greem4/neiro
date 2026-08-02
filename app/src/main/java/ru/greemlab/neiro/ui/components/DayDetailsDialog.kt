package ru.greemlab.neiro.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EventBusy
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.Alignment
import ru.greemlab.neiro.R
import ru.greemlab.neiro.domain.models.EarningsContext
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.StatusExpectedMint
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.calendar.displayStatus
import ru.greemlab.neiro.ui.calendar.buildIntensiveChildrenByTime
import ru.greemlab.neiro.ui.calendar.isStudentCoveredByIntensive
import ru.greemlab.neiro.ui.calendar.totalAmount
import ru.greemlab.neiro.ui.components.daydetails.DayScheduleTimeline
import ru.greemlab.neiro.ui.components.daydetails.EditIntensiveItem
import ru.greemlab.neiro.ui.components.daydetails.TimelineEntry
import ru.greemlab.neiro.ui.components.daydetails.buildIntensiveTimeSlotOptions
import ru.greemlab.neiro.ui.components.daydetails.intensiveDefaultTimeSlot
import ru.greemlab.neiro.ui.components.daydetails.normalizeSessionTime
import androidx.compose.material.icons.rounded.Warning
import ru.greemlab.neiro.ui.util.RU_LOCALE
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", RU_LOCALE)

/** Добавление ручных интенсивов в диалоге дня. */
private const val MANUAL_INTENSIVES_ENABLED = true

/** Порог жеста «потянуть вниз» — ниже стандартного, чтобы проще обновить день. */
@OptIn(ExperimentalMaterial3Api::class)
private val DayDetailsPullRefreshThreshold: Dp =
    (PullToRefreshDefaults.PositionalThreshold - 28.dp).coerceAtLeast(48.dp)

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
    val intensiveChildren: List<Session.IntensiveChild> = emptyList(),
    val coveredEntries: List<ScheduleEntry> = emptyList(),
    val sourceIndex: Int,
)

/**
 * Полноэкранный список занятий на выбранную дату: таймлайн, статистика, архив.
 *
 * Рисуется overlay в том же окне, что и календарь — без отдельного [androidx.compose.ui.window.Dialog],
 * чтобы фон был ровным от статус-бара до низа экрана.
 */
@Composable
fun DayDetailsDialog(
    date: LocalDate,
    initialNames: List<String>,
    /**
     * Цены месяца, к которому относится [date]: за прошлое их называет
     * начисление YClients, профиль отвечает только за текущий и будущие месяцы.
     */
    rates: EarningsContext,
    isArchived: Boolean = false,
    archiveMismatch: Boolean = false,
    archiveMismatchDetails: List<String> = emptyList(),
    allowStatusEdit: Boolean = false,
    highlightSlotKey: String? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onMoveToArchive: () -> Unit = {},
    onUnarchive: () -> Unit = {},
    onRequestOverwriteArchive: () -> Unit = {},
    onStudentStatusChange: ((sourceIndex: Int, status: AttendanceStatus) -> Unit)? = null,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(30f)
            .background(MaterialTheme.colorScheme.background),
    ) {
        DayDetailsContent(
            date = date,
            initialNames = initialNames,
            rates = rates,
            isArchived = isArchived,
            archiveMismatch = archiveMismatch,
            archiveMismatchDetails = archiveMismatchDetails,
            allowStatusEdit = allowStatusEdit,
            highlightSlotKey = highlightSlotKey,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onDismiss = onDismiss,
            onSave = onSave,
            onMoveToArchive = onMoveToArchive,
            onUnarchive = onUnarchive,
            onRequestOverwriteArchive = onRequestOverwriteArchive,
            onStudentStatusChange = onStudentStatusChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailsContent(
    date: LocalDate,
    initialNames: List<String>,
    rates: EarningsContext,
    isArchived: Boolean,
    archiveMismatch: Boolean,
    archiveMismatchDetails: List<String>,
    allowStatusEdit: Boolean,
    highlightSlotKey: String?,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onMoveToArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onRequestOverwriteArchive: () -> Unit,
    onStudentStatusChange: ((sourceIndex: Int, status: AttendanceStatus) -> Unit)?,
) {
    val currentNames = remember { mutableStateListOf<String>().apply { addAll(initialNames) } }
    // Режим планирования — только интенсивы; статусы учеников — в таймлайне (архив).
    var isPlanningMode by remember { mutableStateOf(false) }
    var showArchiveMismatchDetails by remember { mutableStateOf(false) }
    val intensiveFocusRequester = remember { FocusRequester() }
    val showPlanningMode = allowStatusEdit

    LaunchedEffect(allowStatusEdit, showPlanningMode) {
        if (!allowStatusEdit || !showPlanningMode) isPlanningMode = false
    }

    LaunchedEffect(initialNames, isPlanningMode) {
        if (!isPlanningMode) {
            currentNames.clear()
            currentNames.addAll(initialNames)
        }
    }

    val handleStudentStatusChange: ((sourceIndex: Int, status: AttendanceStatus) -> Unit)? =
        if (onStudentStatusChange == null) {
            null
        } else {
            { sourceIndex, status ->
                if (sourceIndex in currentNames.indices) {
                    currentNames[sourceIndex] = SessionParser.withStatus(
                        currentNames[sourceIndex],
                        status,
                    )
                }
                onStudentStatusChange.invoke(sourceIndex, status)
            }
        }

    // derivedStateOf вместо key = currentNames.toList(): без аллокации списка
    // на каждый рекомпоз, пересчёт только при реальном изменении содержимого (E9).
    val entries by remember(rates, date) {
        derivedStateOf { parseEntries(currentNames.toList(), rates) }
    }

    val stats = remember(entries, rates, date) {
        calculateStats(entries, rates)
    }

    val dateText = remember(date) { date.format(DATE_FORMAT) }

    val lessonTimes = remember(entries) {
        entries
            .filter { !it.isExtra && it.time.isNotEmpty() }
            .map { normalizeSessionTime(it.time) }
            .distinct()
    }
    val intensiveTimeSlots = remember(lessonTimes) {
        buildIntensiveTimeSlotOptions(lessonTimes)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                )

                if (showPlanningMode) {
                    IconButton(
                        onClick = { isPlanningMode = !isPlanningMode },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(
                            imageVector = if (isPlanningMode) Icons.Rounded.History else Icons.Rounded.Edit,
                            contentDescription = "Режим редактирования",
                            tint = if (isPlanningMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (archiveMismatch) {
                ArchiveMismatchBanner(
                    onClick = if (archiveMismatchDetails.isNotEmpty()) {
                        { showArchiveMismatchDetails = true }
                    } else {
                        null
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            StatsRow(stats = stats, date = date)

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Список записей
        if (entries.isEmpty() && !isPlanningMode) {
            DayDetailsRefreshableSection(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                        intensiveChildren = entry.intensiveChildren,
                        coveredEntries = entry.coveredEntries.map { covered ->
                            TimelineEntry(
                                name = covered.name,
                                time = covered.time,
                                comment = covered.comment,
                                status = covered.status,
                                sourceIndex = covered.sourceIndex,
                            )
                        },
                        sourceIndex = entry.sourceIndex,
                    )
                },
                date = date,
                highlightSlotKey = highlightSlotKey,
                isRefreshing = isRefreshing,
                onRefresh = if (allowStatusEdit) null else onRefresh,
                onStudentStatusChange = handleStudentStatusChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        } else {
            val intensiveIndices by remember {
                derivedStateOf {
                    currentNames.mapIndexedNotNull { index, raw ->
                        if (SessionParser.isIntensive(raw)) index else null
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(
                    count = intensiveIndices.size,
                    // Стабильный ключ: иначе смена суммы/времени пересоздаёт пикер и лента «бесится».
                    key = { listIndex -> "intensive-edit-${intensiveIndices[listIndex]}" },
                ) { listIndex ->
                    val rawIndex = intensiveIndices[listIndex]
                    val intensive = SessionParser.parse(currentNames[rawIndex]) as Session.Intensive
                    val amountText = if (intensive.amount == 0.0) {
                        ""
                    } else {
                        intensive.amount.toLong().toString()
                    }

                    EditIntensiveItem(
                        amountText = amountText,
                        time = normalizeSessionTime(intensive.time),
                        timeSlotOptions = intensiveTimeSlots,
                        requestFocus = false,
                        focusRequester = intensiveFocusRequester,
                        onAmountChange = { newPrice ->
                            updateIntensiveAt(currentNames, rawIndex) { session ->
                                session.copy(
                                    amount = newPrice.toDoubleOrNull() ?: 0.0,
                                    amountFixed = true,
                                )
                            }
                        },
                        onTimeChange = { newTime ->
                            updateIntensiveAt(currentNames, rawIndex) { session ->
                                session.copy(time = newTime)
                            }
                        },
                        onDelete = { currentNames.removeAt(rawIndex) },
                    )
                }

                if (MANUAL_INTENSIVES_ENABLED) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                currentNames.add(
                                    SessionFormat.serializeIntensive(
                                        price = "",
                                        name = "Интенсив",
                                        status = AttendanceStatus.ARRIVED,
                                        time = intensiveDefaultTimeSlot(),
                                        amountFixed = true,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Добавить интенсив")
                        }
                    }
                }
            }
        }

        DayDetailsBottomBar(
            isPlanningMode = isPlanningMode,
            allowStatusEdit = allowStatusEdit,
            isArchived = isArchived,
            showArchiveAction = onStudentStatusChange == null || isArchived,
            onArchiveClick = {
                when {
                    isArchived && archiveMismatch -> onRequestOverwriteArchive()
                    allowStatusEdit && isArchived -> onUnarchive()
                    isArchived -> onUnarchive()
                    else -> onMoveToArchive()
                }
            },
            onDismiss = onDismiss,
            onSave = { onSave(currentNames.toList()) },
        )
    }

    if (showArchiveMismatchDetails) {
        ArchiveMismatchDetailsDialog(
            details = archiveMismatchDetails,
            onDismiss = { showArchiveMismatchDetails = false },
        )
    }
}

private val DayDetailsBottomBarButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

@Composable
private fun DayDetailsBottomBar(
    isPlanningMode: Boolean,
    allowStatusEdit: Boolean,
    isArchived: Boolean,
    showArchiveAction: Boolean,
    onArchiveClick: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isPlanningMode && showArchiveAction) {
                TextButton(
                    onClick = onArchiveClick,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isArchived) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                    contentPadding = DayDetailsBottomBarButtonPadding,
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isArchived) {
                            stringResource(R.string.archive_action_in_archive)
                        } else {
                            stringResource(R.string.archive_action_to_archive)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = onDismiss,
                contentPadding = DayDetailsBottomBarButtonPadding,
                modifier = Modifier.defaultMinSize(minHeight = 36.dp),
            ) {
                Text(
                    text = "Закрыть",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (allowStatusEdit && isPlanningMode) {
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                ) {
                    Text("Сохранить", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ArchiveMismatchBanner(onClick: (() -> Unit)? = null) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickableModifier),
        shape = RoundedCornerShape(12.dp),
        color = ExpectedAmber.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = ExpectedAmber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.archive_sync_mismatch_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = stringResource(R.string.archive_sync_mismatch_banner_cd),
                    tint = ExpectedAmber,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ArchiveMismatchDetailsDialog(
    details: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.archive_sync_mismatch_details_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.archive_sync_mismatch_details_ok))
            }
        },
    )
}

private fun updateIntensiveAt(
    names: MutableList<String>,
    rawIndex: Int,
    transform: (Session.Intensive) -> Session.Intensive,
) {
    if (rawIndex !in names.indices) return
    val parsed = SessionParser.parse(names[rawIndex])
    if (parsed !is Session.Intensive) return
    val updated = transform(parsed)
    names[rawIndex] = SessionFormat.serializeIntensive(
        price = SessionFormat.intensivePriceField(updated.amount, updated.amountFixed),
        name = updated.name.ifBlank { "Интенсив" },
        status = updated.status,
        time = normalizeSessionTime(updated.time),
        children = updated.children,
        amountFixed = updated.amountFixed,
    )
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
                    color = ExpectedAmber,
                    modifier = Modifier.weight(1f),
                )
                StatBadge(
                    label = "Ожидают",
                    value = stats.expectedCount.toString(),
                    color = StatusExpectedMint,
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
                    color = ScheduleHeaderGreen,
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
            .fillMaxSize()
            .pullToRefresh(
                state = state,
                isRefreshing = isRefreshing,
                onRefresh = { onRefresh() },
                threshold = DayDetailsPullRefreshThreshold,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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

private fun parseEntries(
    rawNames: List<String>,
    rates: EarningsContext,
): List<ScheduleEntry> {
    val intensiveChildrenByTime = buildIntensiveChildrenByTime(
        rawNames.map(SessionParser::parse),
    )
    val coveredByIntensiveTime = mutableMapOf<String, MutableList<ScheduleEntry>>()
    val entries = mutableListOf<ScheduleEntry>()

    rawNames.forEachIndexed { index, raw ->
        if (raw.isBlank()) return@forEachIndexed

        val session = SessionParser.parse(raw)
        val isDeleted = session.isEffectivelyDeleted()

        when (session) {
            is Session.Student -> {
                if (isStudentCoveredByIntensive(session, intensiveChildrenByTime)) {
                    val covered = ScheduleEntry(
                        name = session.name,
                        time = normalizeSessionTime(session.time),
                        comment = session.comment,
                        status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
                        sourceIndex = index,
                    )
                    coveredByIntensiveTime
                        .getOrPut(covered.time) { mutableListOf() }
                        .add(covered)
                    return@forEachIndexed
                }
                entries.add(
                    ScheduleEntry(
                        name = session.name,
                        time = normalizeSessionTime(session.time),
                        comment = session.comment,
                        status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
                        sourceIndex = index,
                    ),
                )
            }

            is Session.Intensive -> {
                val title = session.name.ifBlank { "Интенсив" }

                entries.add(
                    ScheduleEntry(
                        name = title,
                        time = normalizeSessionTime(session.time),
                        comment = "",
                        status = if (isDeleted) AttendanceStatus.CANCELLED else session.displayStatus(),
                        isExtra = true,
                        extraType = "Интенсив",
                        extraAmount = session.totalAmount(
                            rates.pricePerIntensiveChild,
                            onlyArrived = true,
                        ),
                        intensiveChildren = session.children,
                        sourceIndex = index,
                    ),
                )
            }

            is Session.Diagnostics -> entries.add(
                ScheduleEntry(
                    name = session.name,
                    time = normalizeSessionTime(session.time),
                    comment = "",
                    status = if (isDeleted) AttendanceStatus.CANCELLED else session.status,
                    isExtra = true,
                    extraType = "Диагностика",
                    extraAmount = session.amount,
                    sourceIndex = index,
                ),
            )
        }
    }

    return entries
        .map { entry ->
            if (entry.isExtra && entry.extraType == "Интенсив") {
                entry.copy(coveredEntries = coveredByIntensiveTime[entry.time].orEmpty())
            } else {
                entry
            }
        }
        .sortedBy { entry ->
            entry.time.ifEmpty { "99:99" }
        }
}

private fun calculateStats(
    entries: List<ScheduleEntry>,
    rates: EarningsContext,
): DayStats {
    var expected = 0
    var confirmed = 0
    var arrived = 0
    var cancelled = 0
    var money = 0.0

    for (entry in entries) {
        val isIntensive = entry.isExtra && entry.extraType == "Интенсив"

        when (entry.status) {
            AttendanceStatus.EXPECTED -> if (!isIntensive) expected++
            AttendanceStatus.CONFIRMED -> if (!isIntensive) confirmed++
            AttendanceStatus.ARRIVED -> {
                if (!isIntensive) arrived++
                money += if (!entry.isExtra) {
                    rates.pricePerSession
                } else if (entry.extraType == "Диагностика") {
                    if (rates.pricePerDiagnostics > 0.0) rates.pricePerDiagnostics else entry.extraAmount
                } else {
                    entry.extraAmount
                }
            }

            AttendanceStatus.CANCELLED -> if (!isIntensive) cancelled++
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
                    rates = EarningsContext(pricePerSession = 1400.0),
                    isArchived = false,
                    archiveMismatch = false,
                    archiveMismatchDetails = emptyList(),
                    allowStatusEdit = false,
                    highlightSlotKey = null,
                    onDismiss = {},
                    onSave = { _ -> },
                    onMoveToArchive = {},
                    onUnarchive = {},
                    onRequestOverwriteArchive = {},
                    onStudentStatusChange = null,
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
                    rates = EarningsContext(pricePerSession = 1400.0),
                    isArchived = true,
                    archiveMismatch = true,
                    archiveMismatchDetails = listOf(
                        "10:00 — Моторнов Егор, статус: YClients — Пришёл, архив — Ожидает",
                    ),
                    allowStatusEdit = true,
                    highlightSlotKey = null,
                    onDismiss = {},
                    onSave = { _ -> },
                    onMoveToArchive = {},
                    onUnarchive = {},
                    onRequestOverwriteArchive = {},
                    onStudentStatusChange = { _, _ -> },
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
                    rates = EarningsContext(pricePerSession = 1400.0),
                    isArchived = true,
                    archiveMismatch = true,
                    archiveMismatchDetails = listOf(
                        "10:00 — Моторнов Егор, статус: YClients — Пришёл, архив — Ожидает",
                    ),
                    allowStatusEdit = true,
                    highlightSlotKey = null,
                    onDismiss = {},
                    onSave = { _ -> },
                    onMoveToArchive = {},
                    onUnarchive = {},
                    onRequestOverwriteArchive = {},
                    onStudentStatusChange = { _, _ -> },
                )
            }
        }
    }
}
