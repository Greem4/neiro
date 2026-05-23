package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.greemlab.neiro.notifications.SessionSlotKey
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.util.formatRubles
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

private val NowLineRed = Color(0xFFE53935)
private val HourLabelColor = Color(0xFF9E9E9E)

/** Высота одного часа на шкале. */
private val TimelineHourHeight: Dp = 72.dp
private val TimelineMinuteHeight: Dp = TimelineHourHeight / 60
private val TimeAxisWidth: Dp = 54.dp
/** Высота строки «сейчас»: бейдж + линия по центру Y. */
private val NowIndicatorHeight: Dp = 22.dp
private val NowLineStroke: Dp = 1.dp
private val SlotLaneGap: Dp = 4.dp
/** Минимальный зазор между карточками — почти стык, но без слияния. */
private val SlotBottomGap: Dp = 2.dp
/**
 * Выше [PullToRefreshDefaults.PositionalThreshold], чтобы обновление срабатывало
 * только при явном сильном потягивании списка вниз.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val SchedulePullRefreshThreshold: Dp =
    PullToRefreshDefaults.PositionalThreshold + 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScheduleTimeline(
    entries: List<TimelineEntry>,
    date: LocalDate,
    modifier: Modifier = Modifier,
    highlightSlotKey: String? = null,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val timedEntries = entries.filter { it.time.isNotEmpty() }
    val untimedEntries = entries.filter { it.time.isEmpty() }
    val layout = remember(timedEntries) { buildDayTimelineLayout(timedEntries) }
    val scrollState = rememberScrollState()
    val currentTime by rememberCurrentTime()
    val isToday = date == LocalDate.now()
    val density = LocalDensity.current

    LaunchedEffect(highlightSlotKey, layout) {
        val key = highlightSlotKey ?: return@LaunchedEffect
        val currentLayout = layout ?: return@LaunchedEffect
        val target = currentLayout.positioned.firstOrNull { it.matchesHighlight(key, date) } ?: return@LaunchedEffect
        delay(80)
        val pxPerMinute = with(density) { TimelineMinuteHeight.toPx() }
        val offsetMinutes = minutesFromAxisStart(currentLayout.axisStart, target.layoutAppointment.start)
        val scrollTarget = ((offsetMinutes * pxPerMinute) - with(density) { 72.dp.toPx() })
            .toInt()
            .coerceAtLeast(0)
        scrollState.animateScrollTo(scrollTarget)
    }

    val timelineBody: @Composable () -> Unit = {
        if (layout != null) {
            val pxPerMinute = with(density) { TimelineMinuteHeight.toPx() }
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
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
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

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .height(timelineHeight),
                    ) {
                        val expandedReplacements = remember { mutableStateMapOf<String, Boolean>() }

                        layout.positioned.forEach { positioned ->
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
                            val laneWidth = (this@BoxWithConstraints.maxWidth - SlotLaneGap * (laneCount - 1)) / laneCount
                            val laneX = laneWidth * positioned.lane + SlotLaneGap * positioned.lane
                            val slotModifier = Modifier
                                .offset(x = laneX, y = topOffset)
                                .width(laneWidth)
                                .height(slotHeightDp)

                            val slotHighlighted = highlightSlotKey != null &&
                                positioned.matchesHighlight(highlightSlotKey, date)

                            when (positioned) {
                                is PositionedTimelineItem.Single -> {
                                    TimelineScheduleSlot(
                                        entry = positioned.appointment.entry,
                                        highlighted = slotHighlighted,
                                        modifier = slotModifier,
                                    )
                                }
                                is PositionedTimelineItem.Replacement -> {
                                    val pair = positioned.pair
                                    val slotKey = "${appt.start}-${appt.end}-${pair.replacement.entry.name}-${pair.removed.entry.name}"
                                    val expanded = expandedReplacements[slotKey] == true

                                    ExpandableReplacementSlot(
                                        replacement = pair.replacement.entry.toSlotContent(),
                                        removed = pair.removed.entry.toSlotContent(),
                                        expanded = expanded,
                                        onToggle = {
                                            expandedReplacements[slotKey] = !expanded
                                        },
                                        compactForTimeline = true,
                                        highlighted = slotHighlighted,
                                        modifier = slotModifier,
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

        if (untimedEntries.isNotEmpty()) {
            if (layout != null) Spacer(modifier = Modifier.height(8.dp))
            untimedEntries.forEach { entry ->
                val entryHighlighted = highlightSlotKey != null &&
                    SessionSlotKey.fromTimelineEntry(entry, date) == highlightSlotKey
                TimelineScheduleSlot(
                    entry = entry,
                    highlighted = entryHighlighted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
        }
    }

    Column(modifier = modifier) {
        if (onRefresh != null) {
            val state = rememberPullToRefreshState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .pullToRefresh(
                        state = state,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        threshold = SchedulePullRefreshThreshold,
                    ),
            ) {
                timelineBody()
                PullToRefreshDefaults.Indicator(
                    state = state,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                timelineBody()
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
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    ScheduleSlotItem(
        time = entry.time,
        name = entry.displayName(),
        comment = entry.comment,
        status = entry.status,
        isDiagnostics = entry.isExtra && entry.extraType == "Диагностика",
        showTime = false,
        compactForTimeline = true,
        highlighted = highlighted,
        modifier = modifier,
    )
}

private fun PositionedTimelineItem.matchesHighlight(key: String, date: LocalDate): Boolean = when (this) {
    is PositionedTimelineItem.Single ->
        SessionSlotKey.fromTimelineEntry(appointment.entry, date) == key
    is PositionedTimelineItem.Replacement ->
        SessionSlotKey.fromTimelineEntry(pair.replacement.entry, date) == key ||
            SessionSlotKey.fromTimelineEntry(pair.removed.entry, date) == key
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
            delay(delayMs + 500)
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
                TimelineEntry("Ерженинов Владислав", "16:00-16:50", "7.6(Юля)", AttendanceStatus.ARRIVED),
                TimelineEntry("Пирогов Лев", "16:00-16:50", "Нейрокоррекция", AttendanceStatus.CANCELLED),
                TimelineEntry("Шабанова Василиса", "11:00-11:50", "Нейрокоррекция", AttendanceStatus.ARRIVED),
                TimelineEntry("Дубль", "11:00-11:50", "", AttendanceStatus.CANCELLED),
                TimelineEntry("Егорченкова Эмилия", "15:00-15:50", "2,1г", AttendanceStatus.EXPECTED),
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
