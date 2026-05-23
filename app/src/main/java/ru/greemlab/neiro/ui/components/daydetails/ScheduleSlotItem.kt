package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ProfitGreen
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.StatusExpectedMint
import ru.greemlab.neiro.theme.StatusRedBody
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

private val CancelledIndicatorRed = Color(0xFFF44336)

/**
 * Элемент расписания: фон стандартный, цвет меняется только у имени.
 *
 * @param indicatorColors несколько полосок слева (например зелёная + красная у замены).
 */
@Composable
fun ScheduleSlotItem(
    time: String,
    name: String,
    comment: String,
    status: AttendanceStatus,
    modifier: Modifier = Modifier,
    isDiagnostics: Boolean = false,
    showTime: Boolean = true,
    compactForTimeline: Boolean = false,
    indicatorColors: List<Color>? = null,
    highlighted: Boolean = false,
) {
    // Цвет для текста имени в зависимости от статуса
    val nameColor = when (status) {
        AttendanceStatus.ARRIVED -> ProfitGreen
        AttendanceStatus.CONFIRMED -> ExpectedAmber
        AttendanceStatus.CANCELLED -> StatusRedBody
        AttendanceStatus.EXPECTED -> StatusExpectedMint
    }

    val icon = when (status) {
        AttendanceStatus.ARRIVED -> Icons.Rounded.Add
        AttendanceStatus.CONFIRMED -> Icons.Rounded.Check
        AttendanceStatus.CANCELLED -> Icons.Rounded.Remove
        AttendanceStatus.EXPECTED -> Icons.Rounded.History
    }

    val indicatorColor = when {
        status == AttendanceStatus.CANCELLED -> CancelledIndicatorRed
        isDiagnostics -> Color(0xFF5C6BC0)
        else -> ScheduleHeaderGreen
    }
    val indicatorBars = indicatorColors?.takeIf { it.isNotEmpty() } ?: listOf(indicatorColor)

    val baseSurface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val highlightSurface = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    val surfaceColor by animateColorAsState(
        targetValue = if (highlighted) highlightSurface else baseSurface,
        animationSpec = tween(durationMillis = 450),
        label = "slotHighlight",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (compactForTimeline) Modifier else Modifier.defaultMinSize(minHeight = 56.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        color = surfaceColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlotIndicatorBars(colors = indicatorBars)

            if (showTime && time.isNotEmpty()) {
                Text(
                    text = time.substringBefore("-"),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (!compactForTimeline) {
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Имя (ЦВЕТ МЕНЯЕТСЯ) и комментарий
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (compactForTimeline) 6.dp else 0.dp)
                    .padding(vertical = if (compactForTimeline) 2.dp else 6.dp),
            ) {
                Text(
                    text = name.ifEmpty { "Без имени" },
                    style = if (compactForTimeline) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (isDiagnostics) Color(0xFF5C6BC0) else nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (comment.isNotEmpty()) {
                    Text(
                        text = comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Иконка
            Surface(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(24.dp),
                shape = CircleShape,
                color = Color.White,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = indicatorColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotIndicatorBars(
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val barShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
    Row(modifier = modifier.fillMaxHeight()) {
        colors.forEachIndexed { index, color ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .then(
                        if (index == 0) {
                            Modifier.clip(barShape)
                        } else {
                            Modifier
                        },
                    )
                    .background(color),
            )
        }
    }
}

/** Красная и зелёная полоски слева — маркер слота с заменой (сняли → встал). */
val ReplacementCollapsedIndicators: List<Color> =
    listOf(CancelledIndicatorRed, ScheduleHeaderGreen)

/**
 * Слот замены: свёрнуто — одна плашка с полосками красная→зелёная;
 * по нажатию слева отменённый, справа кто встал на место.
 */
@Composable
fun ExpandableReplacementSlot(
    replacement: ScheduleSlotContent,
    removed: ScheduleSlotContent,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    compactForTimeline: Boolean = false,
    highlighted: Boolean = false,
) {
    val gap = if (compactForTimeline) 4.dp else 6.dp
    val slotModifier = Modifier.fillMaxHeight()

    Row(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
        horizontalArrangement = Arrangement.spacedBy(if (expanded) gap else 0.dp),
    ) {
        if (expanded) {
            ScheduleSlotItem(
                time = removed.time,
                name = removed.name,
                comment = removed.comment,
                status = removed.status,
                isDiagnostics = removed.isDiagnostics,
                showTime = removed.showTime,
                compactForTimeline = compactForTimeline,
                highlighted = highlighted,
                modifier = slotModifier.weight(1f),
            )
            ScheduleSlotItem(
                time = replacement.time,
                name = replacement.name,
                comment = replacement.comment,
                status = replacement.status,
                isDiagnostics = replacement.isDiagnostics,
                showTime = replacement.showTime,
                compactForTimeline = compactForTimeline,
                highlighted = highlighted,
                modifier = slotModifier.weight(1f),
            )
        } else {
            ScheduleSlotItem(
                time = replacement.time,
                name = replacement.name,
                comment = replacement.comment,
                status = replacement.status,
                isDiagnostics = replacement.isDiagnostics,
                showTime = replacement.showTime,
                compactForTimeline = compactForTimeline,
                indicatorColors = ReplacementCollapsedIndicators,
                highlighted = highlighted,
                modifier = slotModifier.fillMaxWidth(),
            )
        }
    }
}

data class ScheduleSlotContent(
    val time: String,
    val name: String,
    val comment: String,
    val status: AttendanceStatus,
    val isDiagnostics: Boolean = false,
    val showTime: Boolean = true,
)

@Preview(showBackground = true)
@Composable
private fun ExpandableReplacementSlotPreview() {
    NeiroTheme {
        var expanded by remember { mutableStateOf(false) }
        ExpandableReplacementSlot(
            replacement = ScheduleSlotContent(
                time = "16:00-16:50",
                name = "Ерженинов Владислав",
                comment = "7.6(Юля)",
                status = AttendanceStatus.ARRIVED,
                showTime = false,
            ),
            removed = ScheduleSlotContent(
                time = "16:00-16:50",
                name = "Пирогов Лев",
                comment = "Нейрокоррекция",
                status = AttendanceStatus.CANCELLED,
                showTime = false,
            ),
            expanded = expanded,
            onToggle = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(16.dp),
            compactForTimeline = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ScheduleSlotItemPreview() {
    NeiroTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScheduleSlotItem(
                time = "10:00",
                name = "Ерженинов Владислав",
                comment = "7.6(Юля)",
                status = AttendanceStatus.EXPECTED,
            )
            ScheduleSlotItem(
                time = "12:00",
                name = "Пирогов Лев",
                comment = "Нейрокоррекция",
                status = AttendanceStatus.ARRIVED,
            )
            ScheduleSlotItem(
                time = "15:00",
                name = "Петрушкин Михаил",
                comment = "1,9г (Алина)",
                status = AttendanceStatus.CANCELLED,
            )
        }
    }
}
