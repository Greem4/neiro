package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.daydetails.*

private const val INTENSIVE_PREFIX = "__INTENSIVE__:"
private const val DIAGNOSTICS_PREFIX = "__DIAGNOSTICS__:"
private val RU_LOCALE: Locale = Locale.forLanguageTag("ru")
private const val MAX_STUDENTS = 12

/**
 * Диалоговое окно для редактирования списка людей (фамилий) на выбранную дату.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DayDetailsDialog(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean, Boolean) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        DayDetailsContent(
            date = date,
            initialNames = initialNames,
            userProfile = userProfile,
            onDismiss = onDismiss,
            onSave = onSave
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DayDetailsContent(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean, Boolean) -> Unit
) {
    // mutableStateListOf вместо var items by remember { mutableStateOf(List<...>) } —
    // даёт O(1) изменения отдельных элементов без копирования списка.
    val items: SnapshotStateList<StudentItem> = remember(initialNames) {
        mutableStateListOf<StudentItem>().apply {
            addAll(parseInitialItems(initialNames))
        }
    }

    val focusRequester = remember { FocusRequester() }
    var focusItemId by remember { mutableStateOf<String?>(null) }
    var repeatUntilEndOfMonth by remember { mutableStateOf(false) }
    var repeatNextMonth by remember { mutableStateOf(false) }
    var isPlanningMode by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 8.dp.toPx() }
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

    // derivedStateOf — пересчёт только когда меняются нужные элементы списка.
    val studentCount by remember(items) {
        derivedStateOf { items.count { it.type == StudentItemType.STUDENT && it.name.isNotBlank() } }
    }
    val attendedStudents by remember(items) {
        derivedStateOf { items.count { it.type == StudentItemType.STUDENT && it.name.isNotBlank() && it.attended } }
    }
    val intensiveMoney by remember(items) {
        derivedStateOf {
            items.sumOf {
                if (it.type == StudentItemType.INTENSIVE && it.attended) {
                    it.price.toDoubleOrNull() ?: 0.0
                } else 0.0
            }
        }
    }
    val diagnosticsMoney by remember(items) {
        derivedStateOf {
            items.sumOf {
                if (it.type == StudentItemType.DIAGNOSTICS && it.attended) {
                    it.price.toDoubleOrNull() ?: 0.0
                } else 0.0
            }
        }
    }
    val totalMoney by remember(items, userProfile.pricePerSession) {
        derivedStateOf {
            (attendedStudents * userProfile.pricePerSession) + intensiveMoney + diagnosticsMoney
        }
    }
    val showMoney by remember(userProfile.pricePerSession) {
        derivedStateOf { userProfile.pricePerSession > 0 || intensiveMoney > 0 || diagnosticsMoney > 0 }
    }

    fun itemOffsetDelta(fromIndex: Int, toIndex: Int): Float {
        if (fromIndex == toIndex) return 0f
        val layoutInfo = listState.layoutInfo
        val fromKey = items[fromIndex].id
        val toKey = items[toIndex].id
        val fromInfo = layoutInfo.visibleItemsInfo.find { it.key == fromKey }
        val toInfo = layoutInfo.visibleItemsInfo.find { it.key == toKey }
        if (fromInfo != null && toInfo != null) {
            return (toInfo.offset - fromInfo.offset).toFloat()
        }
        val fallbackSize = fromInfo?.size ?: layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 64
        return if (toIndex > fromIndex) fallbackSize + itemSpacingPx else -(fallbackSize + itemSpacingPx)
    }

    LaunchedEffect(focusItemId) {
        if (focusItemId != null) {
            focusRequester.requestFocus()
            focusItemId = null
        }
    }

    LaunchedEffect(draggedItemId) {
        if (draggedItemId == null) return@LaunchedEffect
        while (true) {
            val layoutInfo = listState.layoutInfo
            val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == draggedItemId }

            if (draggedItem != null) {
                val topEdge = layoutInfo.viewportStartOffset
                val bottomEdge = layoutInfo.viewportEndOffset
                val itemTop = draggedItem.offset + draggingOffset
                val itemBottom = itemTop + draggedItem.size

                val padding = 120f
                if (itemTop < topEdge + padding && listState.canScrollBackward) {
                    val scrollAmount = (((topEdge + padding) - itemTop) / padding * 20f).coerceIn(2f, 25f)
                    listState.scrollBy(-scrollAmount)
                    draggingOffset -= scrollAmount
                } else if (itemBottom > bottomEdge - padding && listState.canScrollForward) {
                    val scrollAmount = ((itemBottom - (bottomEdge - padding)) / padding * 20f).coerceIn(2f, 25f)
                    listState.scrollBy(scrollAmount)
                    draggingOffset += scrollAmount
                }
            }
            delay(12)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).padding(vertical = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DayDetailsHeader(
                date = date,
                totalCount = studentCount,
                totalMoney = totalMoney,
                showMoney = showMoney,
                isPlanningMode = isPlanningMode,
                onTogglePlanningMode = { isPlanningMode = !isPlanningMode }
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, student ->
                    val isDragging = draggedItemId == student.id
                    val dragAnimSpec: AnimationSpec<Float> = if (isDragging) {
                        snap()
                    } else {
                        spring(stiffness = Spring.StiffnessLow)
                    }
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.05f else 1f,
                        animationSpec = dragAnimSpec,
                        label = "dragScale"
                    )
                    val rotation by animateFloatAsState(
                        targetValue = if (isDragging) -1.5f else 0f,
                        animationSpec = if (isDragging) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                        label = "dragRotation"
                    )
                    val elevation by animateFloatAsState(
                        targetValue = if (isDragging) 12f else 0f,
                        animationSpec = dragAnimSpec,
                        label = "dragElevation"
                    )

                    StudentItemRow(
                        modifier = Modifier.zIndex(if (isDragging) 1f else 0f),
                        student = student,
                        index = index,
                        isDragging = isDragging,
                        draggingOffset = draggingOffset,
                        scale = scale,
                        rotation = rotation,
                        elevation = elevation,
                        focusRequester = focusRequester,
                        isFocused = student.id == focusItemId,
                        isPlanningMode = isPlanningMode,
                        onAttendedChange = { attended -> items[index] = student.copy(attended = attended) },
                        onNameChange = { name -> items[index] = student.copy(name = name) },
                        onPriceChange = { price -> items[index] = student.copy(price = price) },
                        onDelete = { items.removeAt(index) },
                        onDragStart = { draggedItemId = student.id },
                        onDragEnd = { draggedItemId = null; draggingOffset = 0f },
                        onDrag = { dy ->
                            draggingOffset += dy
                            val currentIndex = items.indexOfFirst { it.id == draggedItemId }
                            if (currentIndex == -1) return@StudentItemRow

                            if (currentIndex < items.size - 1) {
                                val deltaDown = itemOffsetDelta(currentIndex, currentIndex + 1)
                                if (draggingOffset > deltaDown * 0.5f) {
                                    items.add(currentIndex + 1, items.removeAt(currentIndex))
                                    draggingOffset -= deltaDown
                                    return@StudentItemRow
                                }
                            }
                            if (currentIndex > 0) {
                                val deltaUp = itemOffsetDelta(currentIndex, currentIndex - 1)
                                if (draggingOffset < deltaUp * 0.5f) {
                                    items.add(currentIndex - 1, items.removeAt(currentIndex))
                                    draggingOffset -= deltaUp
                                }
                            }
                        }
                    )
                }

                if (isPlanningMode) {
                    item {
                        Column(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val canAddStudent by remember(items) {
                                derivedStateOf { items.count { it.type == StudentItemType.STUDENT } < MAX_STUDENTS }
                            }
                            if (canAddStudent) {
                                OutlinedButton(
                                    onClick = {
                                        val newId = UUID.randomUUID().toString()
                                        items.add(StudentItem(id = newId, name = "", attended = false, type = StudentItemType.STUDENT))
                                        focusItemId = newId
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Добавить ребенка", fontWeight = FontWeight.Medium)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val newId = UUID.randomUUID().toString()
                                        items.add(StudentItem(id = newId, name = "", attended = true, type = StudentItemType.INTENSIVE, price = ""))
                                        focusItemId = newId
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Интенсив", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val newId = UUID.randomUUID().toString()
                                        items.add(StudentItem(id = newId, name = "", attended = true, type = StudentItemType.DIAGNOSTICS, price = ""))
                                        focusItemId = newId
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Диагностика", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }

                    item {
                        // День недели мемоизируем — toLowerCase + getDisplayName аллоцирует строки.
                        val dayOfWeekLower = remember(date) {
                            date.dayOfWeek.getDisplayName(TextStyle.FULL, RU_LOCALE).lowercase(RU_LOCALE)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            onClick = { repeatUntilEndOfMonth = !repeatUntilEndOfMonth }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = repeatUntilEndOfMonth, onCheckedChange = { repeatUntilEndOfMonth = it })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Дублировать на все $dayOfWeekLower до конца месяца",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            onClick = { repeatNextMonth = !repeatNextMonth }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = repeatNextMonth, onCheckedChange = { repeatNextMonth = it })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Дублировать на все $dayOfWeekLower следующего месяца",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            DayDetailsFooter(
                onDismiss = onDismiss,
                onSave = {
                    val finalNames = buildList(items.size) {
                        for (it in items) {
                            when (it.type) {
                                StudentItemType.STUDENT -> if (it.name.isNotBlank())
                                    add("${it.name}|${it.attended}")
                                StudentItemType.INTENSIVE -> if (it.price.isNotBlank())
                                    add("$INTENSIVE_PREFIX${it.price}|${it.name}|${it.attended}")
                                StudentItemType.DIAGNOSTICS -> if (it.price.isNotBlank())
                                    add("$DIAGNOSTICS_PREFIX${it.price}|${it.name}|${it.attended}")
                            }
                        }
                    }
                    onSave(finalNames, repeatUntilEndOfMonth, repeatNextMonth)
                }
            )
        }
    }
}

private fun parseInitialItems(initialNames: List<String>): List<StudentItem> {
    val parsed = ArrayList<StudentItem>(initialNames.size)
    for (raw in initialNames) {
        if (raw.isBlank()) continue
        val item = when (val session = SessionParser.parse(raw)) {
            is Session.Intensive -> StudentItem(
                id = UUID.randomUUID().toString(),
                name = session.name,
                attended = session.attended,
                type = StudentItemType.INTENSIVE,
                price = session.amount.let { if (it == 0.0) "" else it.toLong().toString() },
            )
            is Session.Diagnostics -> StudentItem(
                id = UUID.randomUUID().toString(),
                name = session.name,
                attended = session.attended,
                type = StudentItemType.DIAGNOSTICS,
                price = session.amount.let { if (it == 0.0) "" else it.toLong().toString() },
            )
            is Session.Student -> StudentItem(
                id = UUID.randomUUID().toString(),
                name = session.name,
                attended = session.attended,
                type = StudentItemType.STUDENT,
            )
        }
        parsed += item
    }
    return if (parsed.isEmpty()) {
        listOf(StudentItem(id = UUID.randomUUID().toString(), name = "", attended = false))
    } else parsed
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Day Details Light")
@Composable
fun DayDetailsLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.LightGray) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = LocalDate.now(),
                    initialNames = listOf("Света", "Иван"),
                    userProfile = UserProfile(pricePerSession = 1200.0),
                    onDismiss = {},
                    onSave = { _, _, _ -> }
                )
            }
        }
    }
}
