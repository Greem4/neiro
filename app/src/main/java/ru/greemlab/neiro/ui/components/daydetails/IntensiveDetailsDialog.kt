package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.greemlab.neiro.theme.ApplyDialogGlass
import ru.greemlab.neiro.theme.LocalGlassEnabled
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.glassBorder
import ru.greemlab.neiro.theme.glassContainerColor
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.intensiveChildrenLabel
import ru.greemlab.neiro.ui.util.fadingEdges
import ru.greemlab.neiro.ui.util.formatRubles
import ru.greemlab.neiro.ui.util.panelScrim
import java.time.LocalDate

@Composable
fun IntensiveDetailsDialog(
    date: LocalDate,
    time: String,
    children: List<Session.IntensiveChild>,
    amount: Double,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        IntensiveDetailsCard(
            date = date,
            time = time,
            children = children,
            amount = amount,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun IntensiveDetailsCard(
    date: LocalDate,
    time: String,
    children: List<Session.IntensiveChild>,
    amount: Double,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val isFutureFar = date.isAfter(today.plusDays(1))
    val semantic = neiroSemanticColors

    val timeLabel = formatIntensiveTimeLabel(time)
    val arrivedCount = children.count { it.status == AttendanceStatus.ARRIVED }
    val confirmedCount = children.count {
        it.status == AttendanceStatus.CONFIRMED || it.status == AttendanceStatus.ARRIVED
    }
    val amountLabel = if (amount > 0.0) formatRubles(amount) else null

    val countLabel = if (isFutureFar) {
        intensiveChildrenLabel(children.size)
    } else {
        "$confirmedCount подтверждено"
    }

    val totalToReport = if (isFutureFar) children.size else confirmedCount

    // Стекло: карточка полупрозрачная, тень убираем — на просвечивающей
    // поверхности она читается как грязь.
    ApplyDialogGlass()
    val glass = LocalGlassEnabled.current
    val cardShape = MaterialTheme.shapes.extraLarge

    val density = LocalDensity.current
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var footerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(density) { headerHeightPx.toDp() }
    val footerHeight = with(density) { footerHeightPx.toDp() }
    val maxCardHeight = with(LocalConfiguration.current) {
        (screenHeightDp * IntensiveDialogStyle.MAX_SCREEN_FRACTION).dp
            .coerceAtLeast(IntensiveDialogStyle.minHeight)
    }
    val panelColor = glassContainerColor()

    Card(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .padding(vertical = 24.dp)
            .glassBorder(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = panelColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (glass) 0.dp else 10.dp),
    ) {
        // Шапка и кнопка лежат поверх списка: имена уезжают под них и там
        // растворяются, а не обрываются на полстроки.
        Box(modifier = Modifier.heightIn(max = maxCardHeight)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fadingEdges(top = headerHeight, bottom = footerHeight),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = headerHeight,
                    bottom = footerHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = children,
                    key = { index, child -> "${index}-${child.name}" },
                ) { _, child ->
                    ScheduleSlotItem(
                        time = "",
                        name = child.name,
                        comment = child.comment,
                        status = child.status,
                        showTime = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onSizeChanged { headerHeightPx = it.height }
                    .background(panelScrim(panelColor, fromTop = true))
                    .padding(horizontal = 16.dp)
                    .padding(top = 18.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = timeLabel.ifBlank { "Интенсив" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Интенсив · $countLabel",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = buildString {
                        append("Пришли: $arrivedCount из $totalToReport")
                        amountLabel?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = semantic.scheduleHeader,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { footerHeightPx = it.height }
                    .background(panelScrim(panelColor, fromTop = false))
                    .padding(top = 14.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        }
    }
}

/**
 * ПАРАМЕТРЫ ДИАЛОГА ИНТЕНСИВА (Твикай здесь)
 */
private object IntensiveDialogStyle {
    /** Доля высоты экрана: шапка, список детей и кнопка вместе. */
    const val MAX_SCREEN_FRACTION = 0.72f
    val minHeight: Dp = 240.dp
}

private fun formatIntensiveTimeLabel(time: String): String {
    if (time.isBlank()) return ""
    val normalized = normalizeSessionTime(time)
    val start = normalized.substringBefore("-").trim()
    val end = normalized.substringAfter("-", "").trim()
    return if (end.isNotEmpty()) "$start–$end" else start
}

@Preview(showBackground = true)
@Composable
private fun IntensiveDetailsCardPreview() {
    NeiroTheme {
        IntensiveDetailsCard(
            date = LocalDate.now(),
            time = "18:00-19:30",
            amount = 8000.0,
            children = listOf(
                Session.IntensiveChild("Коновалов Ильдар 3,2г", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Караховская Мария 2,10л", AttendanceStatus.CONFIRMED),
                Session.IntensiveChild("Егорченкова Эмилия 2,1г", AttendanceStatus.EXPECTED),
                Session.IntensiveChild("Чижова Дарья", AttendanceStatus.CANCELLED),
            ),
            onDismiss = {},
        )
    }
}
