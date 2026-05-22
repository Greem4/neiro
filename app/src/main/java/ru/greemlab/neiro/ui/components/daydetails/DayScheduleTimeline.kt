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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import java.time.format.DateTimeFormatter

private val NowLineRed = Color(0xFFE53935)
private val HourLabelColor = Color(0xFF9E9E9E)
private val HourLineColor = Color(0xFFE0E0E0)

private val HourFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val TimelineHourHeight: Dp = 56.dp
private val TimeAxisWidth: Dp = 44.dp

@Immutable
data class TimelineEntry(
    val name: String,
    val time: String,
    val comment: String,
    val status: AttendanceStatus,
    val isExtra: Boolean = false,
    val extraType: String = "",
    val extraAmount: Double = 0.0,
)

@Composable
fun DayScheduleTimeline(
    entries: List<TimelineEntry>,
    date: LocalDate,
    modifier: Modifier = Modifier,
) {
    val timedEntries = entries.filter { it.time.isNotEmpty() }
    val untimedEntries = entries.filter { it.time.isEmpty() }
    val scrollState = rememberScrollState()
    val currentTime by rememberCurrentTime()
    val isToday = date == LocalDate.now()
    val density = LocalDensity.current

    val timelineHeight = TimelineHourHeight * (SCHEDULE_DAY_END.hour - SCHEDULE_DAY_START.hour)
    val pxPerMinute = with(density) { TimelineHourHeight.toPx() / 60f }

    val nowOffsetMinutes = if (isToday) minutesFromScheduleStart(currentTime) else null
    val nowLineY = nowOffsetMinutes?.let { offset ->
        with(density) { (offset * pxPerMinute).toDp() }
    }

    Column(modifier = modifier) {
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
                ScheduleTimeAxis(
                    modifier = Modifier.width(TimeAxisWidth),
                    timelineHeight = timelineHeight,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(timelineHeight),
                ) {
                    ScheduleHourGrid(
                        modifier = Modifier.matchParentSize(),
                        timelineHeight = timelineHeight,
                    )

                    timedEntries.forEach { entry ->
                        val start = parseTimeRangeStart(entry.time) ?: return@forEach
                        val offsetMinutes = minutesFromScheduleStart(start) ?: return@forEach
                        val slotHeightDp = with(density) {
                            (SESSION_DURATION_MINUTES * pxPerMinute).toDp()
                        }
                        val topOffset = with(density) {
                            (offsetMinutes * pxPerMinute).toDp()
                        }

                        TimelineScheduleSlot(
                            entry = entry,
                            modifier = Modifier
                                .padding(start = 4.dp, end = 2.dp)
                                .offset(y = topOffset)
                                .fillMaxWidth()
                                .height(slotHeightDp.coerceAtLeast(56.dp)),
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

        if (untimedEntries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
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
private fun ScheduleTimeAxis(
    timelineHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.height(timelineHeight),
        verticalArrangement = Arrangement.Top,
    ) {
        for (hour in SCHEDULE_DAY_START.hour until SCHEDULE_DAY_END.hour) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TimelineHourHeight),
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(
                    text = LocalTime.of(hour, 0).format(HourFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = HourLabelColor,
                    modifier = Modifier.padding(end = 6.dp, top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScheduleHourGrid(
    timelineHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.height(timelineHeight)) {
        val hourHeightPx = size.height / (SCHEDULE_DAY_END.hour - SCHEDULE_DAY_START.hour)
        for (h in SCHEDULE_DAY_START.hour until SCHEDULE_DAY_END.hour) {
            val y = (h - SCHEDULE_DAY_START.hour) * hourHeightPx
            drawLine(
                color = HourLineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
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
                text = time.format(HourFormat),
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
                start = Offset(0f, y),
                end = Offset(size.width, y),
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
                TimelineEntry("Егорченкова Эмилия", "12:00-12:50", "2,1г", AttendanceStatus.EXPECTED),
                TimelineEntry("Петров Иван", "14:00-14:50", "", AttendanceStatus.CONFIRMED),
            ),
            modifier = Modifier.padding(8.dp),
        )
    }
}
