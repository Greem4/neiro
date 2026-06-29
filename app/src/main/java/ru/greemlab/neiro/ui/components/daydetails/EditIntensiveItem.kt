package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.StatusRedBody
import java.time.LocalTime
import kotlin.math.abs

@Composable
fun EditIntensiveItem(
    amountText: String,
    time: String,
    timeSlotOptions: List<String>,
    requestFocus: Boolean,
    focusRequester: FocusRequester,
    onAmountChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedTime = remember(time) { normalizeSessionTime(time) }
    val scrollSlots = remember(timeSlotOptions, normalizedTime) {
        buildIntensiveScrollSlots(timeSlotOptions, normalizedTime)
    }

    val cardShape = RoundedCornerShape(12.dp)
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        color = colors.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(colors.primary),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = colors.primaryContainer.copy(alpha = 0.7f),
                    ) {
                        Text(
                            text = "Интенсив",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.onPrimaryContainer,
                        )
                    }
                    val amountStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    val amountSizingText = if (amountText.length < 6) "Сумма" else amountText
                    Surface(
                        modifier = Modifier
                            .width(IntrinsicSize.Min)
                            .defaultMinSize(minHeight = 28.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.55f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .width(IntrinsicSize.Min),
                        ) {
                            Text(
                                text = amountSizingText,
                                style = amountStyle,
                                modifier = Modifier.alpha(0f),
                                maxLines = 1,
                                softWrap = false,
                            )
                            BasicTextField(
                                value = amountText,
                                onValueChange = { onAmountChange(it.filter(Char::isDigit)) },
                                modifier = Modifier
                                    .matchParentSize()
                                    .then(
                                        if (requestFocus) Modifier.focusRequester(focusRequester) else Modifier,
                                    ),
                                singleLine = true,
                                textStyle = amountStyle,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (amountText.isEmpty()) {
                                            Text(
                                                text = "Сумма",
                                                style = amountStyle,
                                                color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }
                    }
                    Text(
                        text = "₽",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = ScheduleHeaderGreen,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "Удалить интенсив",
                            tint = StatusRedBody,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Время",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurfaceVariant,
                    )
                    IntensiveTimeScrollPicker(
                        slots = scrollSlots,
                        selectedSlot = normalizedTime,
                        onSlotSelected = onTimeChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntensiveTimeScrollPicker(
    slots: List<String>,
    selectedSlot: String,
    onSlotSelected: (String) -> Unit,
) {
    if (slots.isEmpty()) return

    val hourItemWidth = 72.dp
    val edgePeekWidth = 20.dp
    val defaultIndex = remember(slots) { intensiveDefaultSlotIndex(slots) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = defaultIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    var manualPickerOpen by remember { mutableStateOf(false) }
    var skipScrollSync by remember { mutableStateOf(false) }

    val selectedNormalized = remember(selectedSlot) {
        normalizeSessionTime(selectedSlot.ifEmpty { intensiveDefaultTimeSlot() })
    }
    val currentSelected = rememberUpdatedState(selectedNormalized)
    val centeredLabel by remember(slots) {
        derivedStateOf {
            listState.centerItemIndex()
                ?.let { slots.getOrNull(it) }
                ?.let { formatTimeSlotLabel(normalizeSessionTime(it)) }
        }
    }
    val displayLabel = centeredLabel ?: formatTimeSlotLabel(selectedNormalized)

    LaunchedEffect(selectedNormalized, slots) {
        if (skipScrollSync) {
            skipScrollSync = false
            return@LaunchedEffect
        }
        val targetIndex = slots.indexOfFirst { normalizeSessionTime(it) == selectedNormalized }
        if (targetIndex >= 0) {
            listState.scrollToCenteredItem(targetIndex)
        }
    }

    LaunchedEffect(listState, slots) {
        snapshotFlow { listState.layoutInfo }
            .collect {
                val index = listState.centerItemIndex() ?: return@collect
                val slot = slots.getOrNull(index) ?: return@collect
                if (normalizeSessionTime(slot) == currentSelected.value) return@collect
                skipScrollSync = true
                onSlotSelected(slot)
            }
    }

    val density = LocalDensity.current
    var pickerWidth by remember { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .onSizeChanged { size ->
                pickerWidth = with(density) { size.width.toDp() }
            },
    ) {
        val sidePadding = ((pickerWidth - hourItemWidth) / 2 - edgePeekWidth).coerceAtLeast(8.dp)
        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

        LazyRow(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(horizontal = sidePadding),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.matchParentSize(),
        ) {
            items(
                count = slots.size,
                key = { index -> slots[index] },
            ) { index ->
                Text(
                    text = formatTimeSlotLabel(slots[index]),
                    modifier = Modifier.width(hourItemWidth),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = mutedColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .width(80.dp)
                .height(44.dp)
                .intensiveTimeChipScrollTap(
                    listState = listState,
                    onTap = { manualPickerOpen = true },
                ),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
            border = BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            ),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Ввести время вручную",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
            }
        }
    }

    if (manualPickerOpen) {
        IntensiveTimeManualPickerDialog(
            selectedSlot = selectedNormalized,
            onConfirm = { slot ->
                onSlotSelected(slot)
                manualPickerOpen = false
            },
            onDismiss = { manualPickerOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntensiveTimeManualPickerDialog(
    selectedSlot: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = parseTimeRangeStart(selectedSlot)
        ?: LocalTime.of(INTENSIVE_TIME_SCROLL_ANCHOR_HOUR, 0)
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время интенсива") },
        confirmButton = {
            TextButton(
                onClick = {
                    val raw = LocalTime.of(pickerState.hour, pickerState.minute)
                    val start = clampIntensiveStartTime(raw)
                    onConfirm(timeRangeFromStart(start))
                },
            ) {
                Text("Готово")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
        text = { TimePicker(state = pickerState) },
    )
}

private suspend fun LazyListState.scrollToCenteredItem(index: Int) {
    scrollToItem(index)
    snapshotFlow { layoutInfo.visibleItemsInfo.any { it.index == index } }
        .first { it }
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.width / 2
    val itemCenter = item.offset + item.size / 2
    val delta = (itemCenter - viewportCenter).toFloat()
    if (abs(delta) > 1f) {
        scrollBy(delta)
    }
}

private fun LazyListState.centerItemIndex(): Int? {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    val viewportCenter = info.viewportStartOffset + info.viewportSize.width / 2
    return info.visibleItemsInfo.minByOrNull { item ->
        abs((item.offset + item.size / 2) - viewportCenter)
    }?.index
}

private fun Modifier.intensiveTimeChipScrollTap(
    listState: LazyListState,
    onTap: () -> Unit,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    pointerInput(listState, onTap) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var isDrag = false
            val pointerId = down.id
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) {
                    if (isDrag) {
                        scope.launch {
                            val index = listState.centerItemIndex()
                            if (index != null) {
                                listState.scrollToCenteredItem(index)
                            }
                        }
                    } else {
                        onTap()
                    }
                    break
                }
                val delta = change.position.x - change.previousPosition.x
                if (!isDrag && abs(change.position.x - down.position.x) > touchSlop) {
                    isDrag = true
                }
                if (isDrag) {
                    change.consume()
                    scope.launch { listState.scrollBy(-delta) }
                }
            }
        }
    }
}
