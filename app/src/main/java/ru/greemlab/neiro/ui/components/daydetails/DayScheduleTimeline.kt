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
private val TimelineHourHeight: Dp = 60.dp
private val TimelineMinuteHeight: Dp = TimelineHourHeight / 60
private val TimeAxisWidth: Dp = 48.dp
private val SlotLaneGap: Dp = 4.dp
/** Небольшой зазор снизу карточки, чтобы соседние не слипались. */
private val SlotBottomGap: Dp = 6.dp

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
                            val slotHeightDp = with(density) {
                                (durationMinutes * pxPerMinute).toDp() - SlotBottomGap
                            }.coerceAtLeast(40.dp)
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
                    CurrentTimeIndicator(
                        time = currentTime,
                        modifier = Modifier
                            .offset(y = nowLineY - 10.dp)
                            .fillMaxWidth(),
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
                    .align(Alignment.TopEnd)
                    .offset(y = topOffset)
                    .padding(end = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun CurrentTimeIndicator(
    time: LocalTime,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(TimeAxisWidth - 4.dp))
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.White,
            modifier = Modifier.border(1.5.dp, NowLineRed, RoundedCornerShape(10.dp)),
        ) {
            Text(
                text = formatNowLabel(time),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = NowLineRed,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val y = size.height / 2f
            drawLine(
                color = NowLineRed,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
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
