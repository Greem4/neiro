package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.greemlab.neiro.theme.MutedSurfaceAlpha
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.AttendanceStatus

/**
 * Элемент расписания: фон стандартный, цвет меняется только у имени.
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
    rightIndicatorColors: List<Color>? = null,
    highlighted: Boolean = false,
    onStatusChange: ((AttendanceStatus) -> Unit)? = null,
    onStatusIconClick: (() -> Unit)? = null,
    onIndicatorClick: (() -> Unit)? = null,
    onRightIndicatorClick: (() -> Unit)? = null,
    onContentClick: (() -> Unit)? = null,
) {
    val nameColor = AttendanceStatusVisuals.nameColor(status)
    val indicatorColor = AttendanceStatusVisuals.indicatorColor(status, isDiagnostics)
    val diagnosticsNameColor = neiroSemanticColors.diagnostics
    val indicatorBars = indicatorColors?.takeIf { it.isNotEmpty() } ?: listOf(indicatorColor)

    val baseSurface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MutedSurfaceAlpha)
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
            SlotIndicatorBars(
                colors = indicatorBars,
                onClick = onIndicatorClick,
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onContentClick != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onContentClick,
                            )
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        color = if (isDiagnostics) diagnosticsNameColor else nameColor,
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
            }

            if (onStatusChange != null) {
                AttendanceStatusPickerIcon(
                    status = status,
                    onStatusSelected = onStatusChange,
                    isDiagnostics = isDiagnostics,
                    modifier = Modifier.padding(end = 8.dp),
                )
            } else {
                AttendanceStatusReadOnlyIcon(
                    status = status,
                    isDiagnostics = isDiagnostics,
                    onClick = onStatusIconClick,
                    modifier = Modifier.padding(end = if (rightIndicatorColors != null) 4.dp else 8.dp),
                )
            }

            if (rightIndicatorColors != null) {
                SlotIndicatorBars(
                    colors = rightIndicatorColors,
                    onClick = onRightIndicatorClick,
                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SlotIndicatorBars(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        colors.forEachIndexed { index, color ->
            Box(
                modifier = Modifier
                    .width(if (colors.size > 1) 3.dp else 4.dp)
                    .fillMaxHeight()
                    .then(
                        when (index) {
                            0 if shape.topStart != CornerSize(0.dp) -> Modifier.clip(shape)
                            colors.lastIndex if shape.topEnd != CornerSize(0.dp) -> Modifier.clip(shape)
                            else -> Modifier
                        },
                    )
                    .background(color),
            )
            if (index < colors.lastIndex) {
                Spacer(modifier = Modifier.width(1.dp))
            }
        }
    }
}

/**
 * Свернутая плашка интенсива в таймлайне (фиксированная высота слота).
 */
@Composable
fun IntensiveTimelineChip(
    title: String,
    status: AttendanceStatus,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compactForTimeline: Boolean = false,
    highlighted: Boolean = false,
    indicatorColors: List<Color>? = null,
    onDetailsClick: (() -> Unit)? = null,
    onIndicatorClick: (() -> Unit)? = null,
) {
    ScheduleSlotItem(
        time = "",
        name = title,
        comment = "",
        status = status,
        showTime = false,
        compactForTimeline = compactForTimeline,
        indicatorColors = indicatorColors,
        highlighted = highlighted,
        onStatusChange = null,
        onStatusIconClick = onDetailsClick,
        onIndicatorClick = onIndicatorClick,
        onContentClick = onClick,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Слот замены: свёрнуто — одна плашка с полосками красная→зелёная.
 * Открывается свайпом вправо (тянем карточку вправо — раскрываем отмены слева).
 */
@Composable
fun ExpandableReplacementSlot(
    replacement: ScheduleSlotContent,
    removed: List<ScheduleSlotContent>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onContentClick: ((ScheduleSlotContent) -> Unit)?,
    modifier: Modifier = Modifier,
    compactForTimeline: Boolean = false,
    highlighted: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val gap = if (compactForTimeline) 4.dp else 6.dp
    val slotModifier = Modifier.fillMaxHeight()

    val expansion = remember { Animatable(if (expanded) 0.5f else 0f) }

    val dragChannel = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(dragChannel) {
        for (delta in dragChannel) {
            expansion.snapTo((expansion.value + delta).coerceIn(0f, 1f))
        }
    }

    LaunchedEffect(expanded) {
        val target = if (expanded) 0.5f else 0f
        if (expansion.value != target) {
            expansion.animateTo(target, spring(stiffness = Spring.StiffnessLow))
        }
    }

    // Пороговые состояния через derivedStateOf: рекомпозиция только при пересечении
    // порога, а не на каждом кадре drag/анимации (значение читается в layout-фазе).
    val showExpandedPart by remember { derivedStateOf { expansion.value > 0.01f } }
    val showCollapsedPart by remember { derivedStateOf { expansion.value < 0.99f } }
    val showRightIndicators by remember { derivedStateOf { expansion.value > 0.95f } }
    val collapsedShowsAllIndicators by remember { derivedStateOf { expansion.value < 0.05f } }

    // pointerInput(removed.size) не перезапускается на каждый toggle — без этого
    // onDragEnd видел бы expanded/onToggle, замороженные на момент последнего
    // запуска жеста, и свайп «отпрыгивал» бы обратно (U4).
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnToggle by rememberUpdatedState(onToggle)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(removed.size) {
                val maxWidthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / (maxWidthPx * 0.8f)
                        dragChannel.trySend(delta)
                    },
                    onDragEnd = {
                        val v = expansion.value
                        val target = when {
                            v < 0.25f -> 0f
                            v < 0.75f -> 0.5f
                            else -> 1f
                        }
                        scope.launch {
                            expansion.animateTo(target, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                            if (target == 0.5f && !currentExpanded) currentOnToggle()
                            else if (target == 0f && currentExpanded) currentOnToggle()
                        }
                    },
                )
            },
    ) {
        val semantic = neiroSemanticColors
        val indicators = remember(removed.size, semantic) {
            List(removed.size) { semantic.statusCancelled } + semantic.scheduleHeader
        }

        ExpansionSplitLayout(
            expansion = { expansion.value },
            leftCount = removed.size,
            gap = gap,
            modifier = Modifier.fillMaxSize(),
            leftContent = {
                if (showExpandedPart) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        removed.forEach { entry ->
                            ScheduleSlotItem(
                                time = entry.time,
                                name = entry.name,
                                comment = entry.comment,
                                status = entry.status,
                                isDiagnostics = entry.isDiagnostics,
                                showTime = entry.showTime,
                                compactForTimeline = compactForTimeline,
                                highlighted = highlighted,
                                onContentClick = onContentClick?.let { { it(entry) } },
                                indicatorColors = listOf(semantic.statusCancelled),
                                rightIndicatorColors = if (showRightIndicators) listOf(semantic.scheduleHeader) else null,
                                onRightIndicatorClick = {
                                    scope.launch {
                                        expansion.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                                        if (expanded) onToggle()
                                    }
                                },
                                onIndicatorClick = {
                                    scope.launch {
                                        expansion.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                                        if (expanded) onToggle()
                                    }
                                },
                                modifier = slotModifier.weight(1f),
                            )
                        }
                    }
                }
            },
            rightContent = {
                if (showCollapsedPart) {
                    val currentIndicators = when {
                        collapsedShowsAllIndicators -> indicators
                        else -> listOf(semantic.scheduleHeader)
                    }

                    ScheduleSlotItem(
                        time = replacement.time,
                        name = replacement.name,
                        comment = replacement.comment,
                        status = replacement.status,
                        isDiagnostics = replacement.isDiagnostics,
                        showTime = replacement.showTime,
                        compactForTimeline = compactForTimeline,
                        indicatorColors = currentIndicators,
                        highlighted = highlighted,
                        onContentClick = onContentClick?.let { { it(replacement) } },
                        onIndicatorClick = {
                            scope.launch {
                                expansion.animateTo(0.5f, spring(stiffness = Spring.StiffnessLow))
                                if (!expanded) onToggle()
                            }
                        },
                        modifier = slotModifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
}

/**
 * Делит ширину между «раскрытой» (слева) и «свёрнутой» (справа) частями слота
 * по значению [expansion], читая его только в layout-фазе — drag и анимация
 * не рекомпозируют содержимое, а лишь перемеряют его.
 *
 * Пропорции повторяют прежнюю weight-раскладку: каждый раскрытый элемент
 * имеет вес `expansion`, свёрнутая часть — `1 - expansion`.
 */
@Composable
private fun ExpansionSplitLayout(
    expansion: () -> Float,
    leftCount: Int,
    gap: Dp,
    modifier: Modifier = Modifier,
    leftContent: @Composable () -> Unit,
    rightContent: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { leftContent() }
            Box { rightContent() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val fraction = expansion().coerceIn(0f, 1f)
        val width = constraints.maxWidth
        val gapPx = (gap.toPx() * (fraction.coerceAtMost(0.5f) * 2f)).toInt()
        val showLeft = fraction > 0.01f && leftCount > 0
        val showRight = fraction < 0.99f

        val leftWidth: Int
        val rightWidth: Int
        when {
            showLeft && showRight -> {
                val available = (width - gapPx).coerceAtLeast(0)
                val totalWeight = leftCount * fraction + (1f - fraction)
                leftWidth = (available * (leftCount * fraction / totalWeight)).toInt()
                rightWidth = available - leftWidth
            }
            showLeft -> {
                leftWidth = width
                rightWidth = 0
            }
            else -> {
                leftWidth = 0
                rightWidth = width
            }
        }

        val left = measurables[0].measure(
            constraints.copy(minWidth = leftWidth, maxWidth = leftWidth),
        )
        val right = measurables[1].measure(
            constraints.copy(minWidth = rightWidth, maxWidth = rightWidth),
        )
        layout(width, maxOf(left.height, right.height)) {
            left.placeRelative(0, 0)
            right.placeRelative(leftWidth + (if (showLeft && showRight) gapPx else 0), 0)
        }
    }
}

/**
 * Интенсив в слоте: свёрнуто — одна плашка.
 * Открывается свайпом вправо (тянем карточку вправо — раскрываем отмены слева).
 */
@Composable
fun ExpandableIntensiveCoverSlot(
    intensiveTitle: String,
    intensiveStatus: AttendanceStatus,
    covered: List<ScheduleSlotContent>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onIntensiveDetails: () -> Unit,
    onCoveredContentClick: ((ScheduleSlotContent) -> Unit)?,
    modifier: Modifier = Modifier,
    compactForTimeline: Boolean = false,
    highlighted: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val gap = if (compactForTimeline) 4.dp else 6.dp
    val slotModifier = Modifier.fillMaxHeight()
    val hasCovered = covered.isNotEmpty()

    val expansion = remember { Animatable(if (expanded) 0.5f else 0f) }

    val dragChannel = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(dragChannel) {
        for (delta in dragChannel) {
            expansion.snapTo((expansion.value + delta).coerceIn(0f, 1f))
        }
    }

    LaunchedEffect(expanded) {
        val target = if (expanded) 0.5f else 0f
        if (expansion.value != target) {
            expansion.animateTo(target, spring(stiffness = Spring.StiffnessLow))
        }
    }

    val showExpandedPart by remember { derivedStateOf { expansion.value > 0.01f } }
    val showCollapsedPart by remember { derivedStateOf { expansion.value < 0.99f } }
    val showRightIndicators by remember { derivedStateOf { expansion.value > 0.95f } }
    val collapsedShowsAllIndicators by remember { derivedStateOf { expansion.value < 0.05f } }

    // pointerInput(covered.size) не перезапускается на каждый toggle — без этого
    // onDragEnd видел бы expanded/onToggle, замороженные на момент последнего
    // запуска жеста, и свайп «отпрыгивал» бы обратно (U4).
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnToggle by rememberUpdatedState(onToggle)

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (hasCovered) {
                    Modifier.pointerInput(covered.size) {
                        val maxWidthPx = size.width.toFloat()
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val delta = dragAmount / (maxWidthPx * 0.8f)
                                dragChannel.trySend(delta)
                            },
                            onDragEnd = {
                                val v = expansion.value
                                val target = when {
                                    v < 0.25f -> 0f
                                    v < 0.75f -> 0.5f
                                    else -> 1f
                                }
                                scope.launch {
                                    expansion.animateTo(target, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                    if (target == 0.5f && !currentExpanded) currentOnToggle()
                                    else if (target == 0f && currentExpanded) currentOnToggle()
                                }
                            },
                        )
                    }
                } else Modifier
            ),
    ) {
        val semantic = neiroSemanticColors
        val indicators = remember(covered.size, semantic) {
            if (hasCovered) {
                List(covered.size) { semantic.statusCancelled } + semantic.scheduleHeader
            } else null
        }

        ExpansionSplitLayout(
            expansion = { expansion.value },
            leftCount = covered.size,
            gap = gap,
            modifier = Modifier.fillMaxSize(),
            leftContent = {
                if (showExpandedPart && hasCovered) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        covered.forEach { entry ->
                            ScheduleSlotItem(
                                time = entry.time,
                                name = entry.name,
                                comment = entry.comment,
                                status = entry.status,
                                isDiagnostics = entry.isDiagnostics,
                                showTime = entry.showTime,
                                compactForTimeline = compactForTimeline,
                                highlighted = highlighted,
                                onContentClick = onCoveredContentClick?.let { { it(entry) } },
                                indicatorColors = listOf(semantic.statusCancelled),
                                rightIndicatorColors = if (showRightIndicators) listOf(semantic.scheduleHeader) else null,
                                onRightIndicatorClick = {
                                    scope.launch {
                                        expansion.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                                        if (expanded) onToggle()
                                    }
                                },
                                onIndicatorClick = {
                                    scope.launch {
                                        expansion.animateTo(0f, spring(stiffness = Spring.StiffnessLow))
                                        if (expanded) onToggle()
                                    }
                                },
                                modifier = slotModifier.weight(1f),
                            )
                        }
                    }
                }
            },
            rightContent = {
                if (showCollapsedPart) {
                    val currentIndicators = when {
                        !hasCovered -> null
                        collapsedShowsAllIndicators -> indicators
                        else -> listOf(semantic.scheduleHeader)
                    }

                    IntensiveTimelineChip(
                        title = intensiveTitle,
                        status = intensiveStatus,
                        onClick = onIntensiveDetails,
                        compactForTimeline = compactForTimeline,
                        highlighted = highlighted,
                        indicatorColors = currentIndicators,
                        onDetailsClick = onIntensiveDetails,
                        onIndicatorClick = {
                            scope.launch {
                                expansion.animateTo(0.5f, spring(stiffness = Spring.StiffnessLow))
                                if (!expanded) onToggle()
                            }
                        },
                        modifier = slotModifier.fillMaxWidth(),
                    )
                }
            },
        )
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
                name = "Иванов Иван",
                comment = "Занятие",
                status = AttendanceStatus.ARRIVED,
                showTime = false,
            ),
            removed = listOf(
                ScheduleSlotContent(
                    time = "16:00-16:50",
                    name = "Пирогов Лев",
                    comment = "Коррекция",
                    status = AttendanceStatus.CANCELLED,
                    showTime = false,
                ),
            ),
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onContentClick = { },
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
                name = "Иванов Иван",
                comment = "Занятие",
                status = AttendanceStatus.EXPECTED,
            )
            ScheduleSlotItem(
                time = "12:00",
                name = "Пирогов Лев",
                comment = "Коррекция",
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
