package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
private val SlotLaneGap: Dp = 4.dp
/** Минимальный зазор между карточками — почти стык, но без слияния. */
private val SlotBottomGap: Dp = 2.dp

@Composable
fun DayScheduleTimeline(
    entries: List<TimelineEntry>,
    date: LocalDate,
    modifier: Modifier = Modifier,
) {
    val timedEntries = entries.filter { it.time.isNotEmpty() }
    val untimedEntries = entries.filter { it.time.isEmpty() }
    val layout = remember(timedEntries) { buildDayTimelineLayout(timedEntries) }
    val scrollState = rememberScrollState()
    val currentTime by rememberCurrentTime()
    val isToday = date == LocalDate.now()
    val density = LocalDensity.current

    Column(modifier = modifier) {
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
                        nowTime = if (nowLineY != null) currentTime else null,
                        nowOffsetMinutes = nowOffsetMinutes,
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .height(timelineHeight),
                    ) {
                        layout.positioned.forEach { positioned ->
                            val appt = positioned.appointment
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

                            TimelineScheduleSlot(
                                entry = appt.entry,
                                modifier = Modifier
                                    .offset(x = laneX, y = topOffset)
                                    .width(laneWidth)
                                    .height(slotHeightDp),
                            )
                        }
                    }
                }

                if (nowLineY != null) {
                    CurrentTimeLine(
                        modifier = Modifier
                            .offset(y = nowLineY)
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }
        }

        if (untimedEntries.isNotEmpty()) {
            if (layout != null) Spacer(modifier = Modifier.height(8.dp))
            untimedEntries.forEach { entry ->
                TimelineScheduleSlot(
                    entry = entry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
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
    nowTime: LocalTime? = null,
    nowOffsetMinutes: Int? = null,
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

        if (nowTime != null && nowOffsetMinutes != null) {
            val nowTop = with(density) {
                (nowOffsetMinutes * pxPerMinute).toDp()
            }
            CurrentTimeBadge(
                time = nowTime,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = nowTop - 12.dp),
            )
        }
    }
}

@Composable
private fun CurrentTimeBadge(
    time: LocalTime,
    modifier: Modifier = Modifier,
) {
    val badgeShape = RoundedCornerShape(12.dp)
    Surface(
        shape = badgeShape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = modifier
            .wrapContentWidth(unbounded = true)
            .border(2.dp, NowLineRed, badgeShape),
    ) {
        Text(
            text = formatNowLabel(time),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NowLineRed,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CurrentTimeLine(
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Spacer(modifier = Modifier.width(TimeAxisWidth))
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = NowLineRed,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                strokeWidth = 2.5f,
            )
        }
    }
}

@Composable
private fun TimelineScheduleSlot(
    entry: TimelineEntry,
    modifier: Modifier = Modifier,
) {
    val displayName = if (entry.isExtra && entry.extraType != "Диагностика") {
        "${entry.extraType}: ${formatRubles(entry.extraAmount)}"
    } else {
        entry.name
    }
    ScheduleSlotItem(
        time = entry.time,
        name = displayName,
        comment = entry.comment,
        status = entry.status,
        isDiagnostics = entry.isExtra && entry.extraType == "Диагностика",
        showTime = false,
        compactForTimeline = true,
        modifier = modifier,
    )
}

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
                TimelineEntry("Шабанова Василиса", "11:00-11:50", "Нейрокоррекция", AttendanceStatus.ARRIVED),
                TimelineEntry("Дубль", "11:00-11:50", "", AttendanceStatus.CANCELLED),
                TimelineEntry("Егорченкова Эмилия", "15:00-15:50", "2,1г", AttendanceStatus.EXPECTED),
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
