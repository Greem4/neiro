package ru.greemlab.neiro.ui.components.daydetails

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.greemlab.neiro.notifications.SessionSlotKey
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration.Companion.milliseconds

private val NowLineRed = Color(0xFFE53935)
private val HourLabelColor = Color(0xFF9E9E9E)

/** Высота одного часа на шкале при системном шрифте 100%. */
private val TimelineHourHeight: Dp = 72.dp
/**
 * Насколько шкала растягивается вслед за системным шрифтом. Высота карточки
 * занятия задана его длительностью, поэтому без растяжения при 150%+ имя с
 * комментарием переставали в неё влезать.
 */
private const val TimelineMaxFontScale = 1.6f
/** Максимальная высота прокручиваемой области расписания. */
private val TimelineViewportMaxHeight: Dp = 420.dp
private val TimeAxisWidth: Dp = 54.dp
/** Высота строки «сейчас»: бейдж + линия по центру Y. */
private val NowIndicatorHeight: Dp = 22.dp
private val NowLineStroke: Dp = 1.dp
private val SlotLaneGap: Dp = 4.dp
/** Минимальный зазор между карточками — почти стык, но без слияния. */
private val SlotBottomGap: Dp = 2.dp
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScheduleTimeline(
    entries: List<TimelineEntry>,
    date: LocalDate,
    modifier: Modifier = Modifier,
    highlightSlotKey: String? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    onTopReachedChanged: (Boolean) -> Unit = {},
    onStudentStatusChange: ((sourceIndex: Int, status: AttendanceStatus) -> Unit)? = null,
) {
    val timedEntries = entries.filter { it.time.isNotEmpty() }
    val untimedEntries = entries.filter { it.time.isEmpty() }
    val layout = remember(timedEntries) { buildDayTimelineLayout(timedEntries) }
    val scrollState = rememberScrollState()
    val currentTime by rememberCurrentTime()
    val isToday = date == LocalDate.now()
    val density = LocalDensity.current
    val context = LocalContext.current
    val pxPerMinute = with(density) {
        (TimelineHourHeight * density.fontScale.coerceIn(1f, TimelineMaxFontScale) / 60).toPx()
    }

    val onStudentClick = remember(context) {
        { name: String ->
            Toast.makeText(context, "Карточка: $name (в разработке)", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(highlightSlotKey, layout, date) {
        val key = highlightSlotKey ?: return@LaunchedEffect
        val currentLayout = layout ?: return@LaunchedEffect
        val target = currentLayout.positioned.firstOrNull { it.matchesHighlight(key, date) } ?: return@LaunchedEffect
        delay(80.milliseconds)
        val offsetMinutes = minutesFromAxisStart(currentLayout.axisStart, target.layoutAppointment.start)
        val scrollTarget = ((offsetMinutes * pxPerMinute) - with(density) { 72.dp.toPx() })
            .toInt()
            .coerceAtLeast(0)
        scrollState.animateScrollTo(
            value = scrollTarget,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        )
    }

    LaunchedEffect(layout, date, highlightSlotKey) {
        if (highlightSlotKey != null) return@LaunchedEffect
        val currentLayout = layout ?: return@LaunchedEffect
        if (date != LocalDate.now()) return@LaunchedEffect
        val now = LocalTime.now()
        if (now.isBefore(currentLayout.axisStart) || !now.isBefore(currentLayout.axisEnd)) return@LaunchedEffect
        delay(80.milliseconds)
        var layoutAttempts = 0
        while (scrollState.viewportSize == 0 && layoutAttempts < 30) {
            delay(16.milliseconds)
            layoutAttempts++
        }
        val nowLinePx = minutesFromAxisStart(currentLayout.axisStart, now) * pxPerMinute
        val viewportPx = scrollState.viewportSize.takeIf { it > 0 }
            ?: with(density) { TimelineViewportMaxHeight.toPx() }.toInt()
        val scrollTarget = (nowLinePx - viewportPx / 2f)
            .toInt()
            .coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(
            value = scrollTarget,
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        )
    }

    LaunchedEffect(scrollState) {
        androidx.compose.runtime.snapshotFlow { scrollState.value == 0 }
            .distinctUntilChanged()
            .collect { onTopReachedChanged(it) }
    }

    var selectedIntensive by remember { mutableStateOf<TimelineEntry?>(null) }
    val pullToRefreshState = rememberPullToRefreshState()

    selectedIntensive?.let { entry ->
        IntensiveDetailsDialog(
            date = date,
            time = entry.time,
            children = entry.intensiveChildren,
            amount = entry.extraAmount,
            onDismiss = { selectedIntensive = null },
        )
    }

    Column(modifier = modifier.fillMaxHeight()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (onRefresh != null) {
                        Modifier.pullToRefresh(
                            state = pullToRefreshState,
                            isRefreshing = isRefreshing,
                            onRefresh = onRefresh,
                            threshold = (PullToRefreshDefaults.PositionalThreshold - 28.dp).coerceAtLeast(48.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (layout != null) {
                val timelineHeight = with(density) {
                    (layout.totalMinutes * pxPerMinute).toDp()
                }
                val nowOffsetMinutes = if (isToday &&
                    !currentTime.isBefore(layout.axisStart) &&
                    currentTime.isBefore(layout.axisEnd)
                ) {
                    minutesFromAxisStart(layout.axisStart, currentTime)
                } else {
                    null
                }
                val nowLineY = nowOffsetMinutes?.let { offset ->
                    with(density) { (offset * pxPerMinute).toDp() }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(timelineHeight),
                ) {
                    TimelineTimeAxis(
                        layout = layout,
                        pxPerMinute = pxPerMinute,
                        modifier = Modifier.width(TimeAxisWidth),
                        timelineHeight = timelineHeight,
                    )

                    val expandedReplacements = remember { mutableStateMapOf<String, Boolean>() }
                    val expandedIntensiveCovers = remember { mutableStateMapOf<String, Boolean>() }
                    var columnWidth by remember { mutableStateOf(0.dp) }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(timelineHeight)
                            .onSizeChanged { size ->
                                columnWidth = with(density) { size.width.toDp() }
                            },
                    ) {
                        layout.positioned.forEach { positioned ->
                            val appt = positioned.layoutAppointment
                            val slotGeometry = computeSlotGeometry(
                                positioned = positioned,
                                layout = layout,
                                pxPerMinute = pxPerMinute,
                                maxWidth = columnWidth,
                                density = density,
                            )

                            val slotHighlighted = highlightSlotKey != null &&
                                positioned.matchesHighlight(highlightSlotKey, date)

                            when (positioned) {
                                is PositionedTimelineItem.Single -> {
                                    val entry = positioned.appointment.entry
                                    if (entry.isIntensiveWithChildren()) {
                                        IntensiveTimelineChip(
                                            title = entry.name,
                                            status = entry.status,
                                            onClick = { selectedIntensive = entry },
                                            compactForTimeline = true,
                                            highlighted = slotHighlighted,
                                            modifier = slotGeometry.modifier,
                                        )
                                    } else {
                                        TimelineScheduleSlot(
                                            entry = entry,
                                            highlighted = slotHighlighted,
                                            onContentClick = { onStudentClick(entry.name) },
                                            onStudentStatusChange = onStudentStatusChange,
                                            modifier = slotGeometry.modifier,
                                        )
                                    }
                                }
                                is PositionedTimelineItem.Replacement -> {
                                    val pair = positioned.pair
                                    val slotKey = buildString {
                                        append(appt.start)
                                        append('-')
                                        append(appt.end)
                                        append('-')
                                        append(pair.replacement.entry.name)
                                        pair.removed.forEach { removed ->
                                            append('-')
                                            append(removed.entry.name)
                                        }
                                    }
                                    val expanded = expandedReplacements[slotKey] == true

                                    ExpandableReplacementSlot(
                                        replacement = pair.replacement.entry.toSlotContent(),
                                        removed = pair.removed.map { it.entry.toSlotContent() },
                                        expanded = expanded,
                                        onToggle = {
                                            expandedReplacements[slotKey] = !expanded
                                        },
                                        onContentClick = { content ->
                                            onStudentClick(content.name)
                                        },
                                        compactForTimeline = true,
                                        highlighted = slotHighlighted,
                                        modifier = slotGeometry.modifier,
                                    )
                                }
                                is PositionedTimelineItem.IntensiveCover -> {
                                    val pair = positioned.pair
                                    val intensiveEntry = pair.intensive.entry
                                    val slotKey = buildString {
                                        append(appt.start)
                                        append('-')
                                        append(appt.end)
                                        append('-')
                                        append(intensiveEntry.name)
                                        pair.covered.forEach { covered ->
                                            append('-')
                                            append(covered.entry.name)
                                        }
                                    }
                                    val expanded = expandedIntensiveCovers[slotKey] == true

                                    ExpandableIntensiveCoverSlot(
                                        intensiveTitle = intensiveEntry.name,
                                        intensiveStatus = intensiveEntry.status,
                                        covered = pair.covered.map { it.entry.toSlotContent() },
                                        expanded = expanded,
                                        onToggle = {
                                            expandedIntensiveCovers[slotKey] = !expanded
                                        },
                                        onIntensiveDetails = { selectedIntensive = intensiveEntry },
                                        onCoveredContentClick = { content ->
                                            onStudentClick(content.name)
                                        },
                                        compactForTimeline = true,
                                        highlighted = slotHighlighted,
                                        modifier = slotGeometry.modifier,
                                    )
                                }
                            }
                        }
                    }
                }

                if (nowLineY != null) {
                    CurrentTimeIndicator(
                        time = currentTime,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = nowLineY - NowIndicatorHeight / 2)
                            .height(NowIndicatorHeight),
                    )
                }
            }
            }

            if (onRefresh != null) {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        if (untimedEntries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            untimedEntries.forEach { entry ->
                val entryHighlighted = highlightSlotKey != null &&
                    SessionSlotKey.fromTimelineEntry(entry, date) == highlightSlotKey
                if (entry.isIntensiveWithChildren()) {
                    IntensiveTimelineChip(
                        title = entry.name,
                        status = entry.status,
                        onClick = { selectedIntensive = entry },
                        compactForTimeline = true,
                        highlighted = entryHighlighted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                } else {
                    TimelineScheduleSlot(
                        entry = entry,
                        highlighted = entryHighlighted,
                        onContentClick = { onStudentClick(entry.name) },
                        onStudentStatusChange = onStudentStatusChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineTimeAxis(
    layout: DayTimelineLayout,
    pxPerMinute: Float,
    timelineHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    Box(modifier = modifier.height(timelineHeight)) {
        layout.hourLabels.forEach { hour ->
            val topOffset = with(density) {
                (minutesFromAxisStart(layout.axisStart, hour) * pxPerMinute).toDp()
            }
            Text(
                text = formatHourLabel(hour),
                style = MaterialTheme.typography.labelSmall,
                color = HourLabelColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = topOffset)
                    .padding(top = 2.dp),
            )
        }
    }
}

/** Капсула с текущим временем и красная линия — как в YClients, в цветах темы Neiro. */
@Composable
private fun CurrentTimeIndicator(
    time: LocalTime,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pillShape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = pillShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(NowLineStroke, NowLineRed),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Text(
                text = formatNowLabel(time),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = NowLineRed,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val strokePx = with(density) { NowLineStroke.toPx() }
            val centerY = size.height / 2f
            drawLine(
                color = NowLineRed,
                start = androidx.compose.ui.geometry.Offset(0f, centerY),
                end = androidx.compose.ui.geometry.Offset(size.width, centerY),
                strokeWidth = strokePx,
            )
        }
    }
}

@Composable
private fun TimelineScheduleSlot(
    entry: TimelineEntry,
    onContentClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onStudentStatusChange: ((sourceIndex: Int, status: AttendanceStatus) -> Unit)? = null,
) {
    val statusEditable = onStudentStatusChange != null &&
        !entry.isExtra &&
        entry.sourceIndex >= 0

    ScheduleSlotItem(
        time = entry.time,
        name = entry.displayName(),
        comment = entry.comment,
        status = entry.status,
        isDiagnostics = entry.isExtra && entry.extraType == "Диагностика",
        showTime = false,
        compactForTimeline = true,
        highlighted = highlighted,
        onStatusChange = if (statusEditable) {
            { newStatus -> onStudentStatusChange(entry.sourceIndex, newStatus) }
        } else {
            null
        },
        onContentClick = onContentClick,
        modifier = modifier,
    )
}

private fun PositionedTimelineItem.matchesHighlight(key: String, date: LocalDate): Boolean = when (this) {
    is PositionedTimelineItem.Single ->
        SessionSlotKey.fromTimelineEntry(appointment.entry, date) == key
    is PositionedTimelineItem.Replacement ->
        SessionSlotKey.fromTimelineEntry(pair.replacement.entry, date) == key ||
            pair.removed.any { SessionSlotKey.fromTimelineEntry(it.entry, date) == key }
    is PositionedTimelineItem.IntensiveCover ->
        SessionSlotKey.fromTimelineEntry(pair.intensive.entry, date) == key ||
            pair.covered.any { SessionSlotKey.fromTimelineEntry(it.entry, date) == key }
}

private fun TimelineEntry.isIntensiveWithChildren(): Boolean =
    isExtra && extraType == "Интенсив" && intensiveChildren.isNotEmpty()

private data class TimelineSlotGeometry(
    val topOffset: Dp,
    val modifier: Modifier,
)

private fun computeSlotGeometry(
    positioned: PositionedTimelineItem,
    layout: DayTimelineLayout,
    pxPerMinute: Float,
    maxWidth: Dp,
    density: androidx.compose.ui.unit.Density,
): TimelineSlotGeometry {
    val appt = positioned.layoutAppointment
    val offsetMinutes = minutesFromAxisStart(layout.axisStart, appt.start)
    val durationMinutes = Duration.between(appt.start, appt.end).toMinutes().toInt()
    val visualDurationMinutes = when {
        durationMinutes in (SESSION_DURATION_MINUTES - 2)..(SESSION_DURATION_MINUTES + 2) ->
            durationMinutes + 8
        else -> durationMinutes
    }
    val slotHeightDp = with(density) {
        (visualDurationMinutes * pxPerMinute).toDp() - SlotBottomGap
    }.coerceAtLeast(52.dp)
    val topOffset = with(density) {
        (offsetMinutes * pxPerMinute).toDp()
    }
    val laneCount = positioned.laneCount.coerceAtLeast(1)
    val laneWidth = (maxWidth - SlotLaneGap * (laneCount - 1)) / laneCount
    val laneX = laneWidth * positioned.lane + SlotLaneGap * positioned.lane
    return TimelineSlotGeometry(
        topOffset = topOffset,
        modifier = Modifier
            .offset(x = laneX, y = topOffset)
            .width(laneWidth)
            .height(slotHeightDp),
    )
}

private fun TimelineEntry.displayName(): String =
    if (isExtra && extraType != "Диагностика") {
        "$extraType: ${formatRubles(extraAmount)}"
    } else {
        name
    }

private fun TimelineEntry.toSlotContent(): ScheduleSlotContent =
    ScheduleSlotContent(
        time = time,
        name = displayName(),
        comment = comment,
        status = status,
        isDiagnostics = isExtra && extraType == "Диагностика",
        showTime = false,
    )

@Composable
private fun rememberCurrentTime(): State<LocalTime> =
    produceState(initialValue = LocalTime.now()) {
        while (true) {
            val now = LocalTime.now()
            val nextTick = now.plusMinutes(1).withSecond(0).withNano(0)
            val delayMs = Duration.between(now, nextTick).toMillis().coerceAtLeast(1_000)
            delay((delayMs + 500).milliseconds)
            value = LocalTime.now()
        }
    }

@Preview(showBackground = true)
@Composable
private fun DayScheduleTimelinePreview() {
    NeiroTheme {
        DayScheduleTimeline(
            date = LocalDate.now(),
            entries = listOf(
                TimelineEntry("Иванов Иван", "16:00-16:50", "Занятие", AttendanceStatus.ARRIVED),
                TimelineEntry("Пирогов Лев", "16:00-16:50", "Коррекция", AttendanceStatus.CANCELLED),
                TimelineEntry("Шабанова Василиса", "11:00-11:50", "Коррекция", AttendanceStatus.ARRIVED),
                TimelineEntry("Дубль", "11:00-11:50", "", AttendanceStatus.CANCELLED),
                TimelineEntry("Егорченкова Эмилия", "15:00-15:50", "2,1г", AttendanceStatus.EXPECTED),
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
