package ru.greemlab.neiro.ui.components

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionFormat
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.daydetails.DayDetailsFooter
import ru.greemlab.neiro.ui.components.daydetails.DayDetailsHeader
import ru.greemlab.neiro.ui.components.daydetails.StudentItem
import ru.greemlab.neiro.ui.components.daydetails.StudentItemRow
import ru.greemlab.neiro.ui.components.daydetails.StudentItemType
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.UUID

private const val MAX_STUDENTS = 12
private const val AUTO_SCROLL_DELAY_MS = 16L

/**
 * Диалог редактирования списка людей (фамилий) на выбранную дату.
 *
 * Поддерживает три вида записей: ученика, интенсив, диагностику.
 * Записи можно перетаскивать в режиме редактирования (`isPlanningMode`).
 */
@Composable
fun DayDetailsDialog(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean, Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        DayDetailsContent(
            date = date,
            initialNames = initialNames,
            userProfile = userProfile,
            onDismiss = onDismiss,
            onSave = onSave,
        )
    }
}

@Composable
private fun DayDetailsContent(
    date: LocalDate,
    initialNames: List<String>,
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean, Boolean) -> Unit,
) {
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
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

    val studentCount by remember {
        derivedStateOf {
            items.count { it.type == StudentItemType.STUDENT && it.name.isNotBlank() }
        }
    }
    val attendedStudents by remember {
        derivedStateOf {
            items.count { it.type == StudentItemType.STUDENT && it.name.isNotBlank() && it.attended }
        }
    }
    val intensiveMoney by remember {
        derivedStateOf {
            items.sumOf {
                if (it.type == StudentItemType.INTENSIVE && it.attended) {
                    it.price.toDoubleOrNull() ?: 0.0
                } else 0.0
            }
        }
    }
    val diagnosticsMoney by remember {
        derivedStateOf {
            items.sumOf {
                if (it.type == StudentItemType.DIAGNOSTICS && it.attended) {
                    it.price.toDoubleOrNull() ?: 0.0
                } else 0.0
            }
        }
    }
    val totalMoney by remember(userProfile.pricePerSession) {
        derivedStateOf {
            (attendedStudents * userProfile.pricePerSession) + intensiveMoney + diagnosticsMoney
        }
    }
    val showMoney by remember(userProfile.pricePerSession) {
        derivedStateOf { userProfile.pricePerSession > 0 || intensiveMoney > 0 || diagnosticsMoney > 0 }
    }

    // Жёстко удерживаем визуальное положение перетаскиваемой карточки внутри
    // viewport. Без этого при быстром драге `draggingOffset` уезжал далеко за
    // край, а `tryReorder` начинал свопить со «слепыми» оценочными размерами —
    // карточка мгновенно пролетала через всех невидимых соседей. С клампом
    // карточка просто прижимается к верхней/нижней границе, а дальше темп
    // задаёт авто-скролл.
    fun clampDragOffset() {
        val draggedId = draggedItemId ?: return
        val layoutInfo = listState.layoutInfo
        val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == draggedId } ?: return
        val maxOffsetDown =
            (layoutInfo.viewportEndOffset - draggedItem.offset - draggedItem.size).toFloat()
        val maxOffsetUp = (layoutInfo.viewportStartOffset - draggedItem.offset).toFloat()
        val low = minOf(maxOffsetUp, maxOffsetDown)
        val high = maxOf(maxOffsetUp, maxOffsetDown)
        draggingOffset = draggingOffset.coerceIn(low, high)
    }

    // Пытается переставить перетаскиваемый элемент через соседа, если смещение
    // перешло половину высоты соседа. За один вызов — максимум один шаг.
    //
    // Раньше порог считался по разнице offset'ов соседа и перетаскиваемого, но
    // у соседа во время перестановки работает animateItem, и его offset
    // временно «дрожит». Из-за этого приходилось ставить cooldown, который
    // отставал от авто-скролла — карточка теряла индекс и «слетала» в начало
    // списка при длинных перетаскиваниях.
    //
    // Теперь порог считается по статическому размеру соседа + spacing — это
    // значение не зависит от анимации, поэтому cooldown не нужен и свопы
    // успевают за скроллом, даже когда в списке больше 9 элементов.
    fun tryReorder() {
        val draggedId = draggedItemId ?: return
        val currentIndex = items.indexOfFirst { it.id == draggedId }
        if (currentIndex == -1) return

        val layoutInfo = listState.layoutInfo
        val spacing = layoutInfo.mainAxisItemSpacing.toFloat()

        if (draggingOffset > 0f && currentIndex < items.size - 1) {
            val nextKey = items[currentIndex + 1].id
            val nextInfo = layoutInfo.visibleItemsInfo.find { it.key == nextKey } ?: return
            val displacement = nextInfo.size + spacing
            if (draggingOffset > displacement * 0.5f) {
                items.add(currentIndex + 1, items.removeAt(currentIndex))
                draggingOffset -= displacement
                return
            }
        }
        if (draggingOffset < 0f && currentIndex > 0) {
            val prevKey = items[currentIndex - 1].id
            val prevInfo = layoutInfo.visibleItemsInfo.find { it.key == prevKey } ?: return
            val displacement = prevInfo.size + spacing
            if (draggingOffset < -displacement * 0.5f) {
                items.add(currentIndex - 1, items.removeAt(currentIndex))
                draggingOffset += displacement
            }
        }
    }

    LaunchedEffect(focusItemId) {
        if (focusItemId != null) {
            focusRequester.requestFocus()
            focusItemId = null
        }
    }

    // Авто-скролл при перетаскивании. Корутина живёт только пока есть активный drag.
    // Помимо самого скролла прокручиваем перестановки — иначе при удержании пальца
    // у края список двигался бы, а карточка визуально «улетала» от соседей.
    LaunchedEffect(draggedItemId) {
        val dragged = draggedItemId ?: return@LaunchedEffect
        while (true) {
            val layoutInfo = listState.layoutInfo
            val draggedItem = layoutInfo.visibleItemsInfo.find { it.key == dragged }

            if (draggedItem != null) {
                val topEdge = layoutInfo.viewportStartOffset
                val bottomEdge = layoutInfo.viewportEndOffset
                val itemTop = draggedItem.offset + draggingOffset
                val itemBottom = itemTop + draggedItem.size

                val padding = 120f
                if (itemTop < topEdge + padding && listState.canScrollBackward) {
                    val scrollAmount =
                        (((topEdge + padding) - itemTop) / padding * 20f).coerceIn(2f, 25f)
                    listState.scrollBy(-scrollAmount)
                    draggingOffset -= scrollAmount
                } else if (itemBottom > bottomEdge - padding && listState.canScrollForward) {
                    val scrollAmount =
                        ((itemBottom - (bottomEdge - padding)) / padding * 20f).coerceIn(2f, 25f)
                    listState.scrollBy(scrollAmount)
                    draggingOffset += scrollAmount
                }

                // Клампаем после компенсации скролла, чтобы не было рассинхрона
                clampDragOffset()
                tryReorder()
            }
            delay(AUTO_SCROLL_DELAY_MS)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f)
            .padding(vertical = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DayDetailsHeader(
                date = date,
                totalCount = studentCount,
                totalMoney = totalMoney,
                showMoney = showMoney,
                isPlanningMode = isPlanningMode,
                onTogglePlanningMode = { isPlanningMode = !isPlanningMode },
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, student ->
                    val isDragging = draggedItemId == student.id
                    val dragAnimSpec: AnimationSpec<Float> =
                        if (isDragging) snap() else spring(stiffness = Spring.StiffnessMedium)
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.05f else 1f,
                        animationSpec = dragAnimSpec,
                        label = "dragScale",
                    )
                    val rotation by animateFloatAsState(
                        targetValue = if (isDragging) -1.5f else 0f,
                        animationSpec = if (isDragging) snap()
                        else spring(stiffness = Spring.StiffnessMedium),
                        label = "dragRotation",
                    )
                    val elevation by animateFloatAsState(
                        targetValue = if (isDragging) 12f else 0f,
                        animationSpec = dragAnimSpec,
                        label = "dragElevation",
                    )

                    // Соседи быстро сдвигаются на освобождённое место. Сам перетаскиваемый
                    // элемент анимировать нельзя — мы управляем его позицией через
                    // translationY, и анимация компоновки вступила бы в конфликт.
                    val placementModifier =
                        if (isDragging) Modifier else Modifier.animateItem(
                            placementSpec = spring(stiffness = Spring.StiffnessHigh),
                        )

                    StudentItemRow(
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .then(placementModifier),
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
                        onAttendedChange = { attended ->
                            items[index] = student.copy(attended = attended)
                        },
                        onNameChange = { name -> items[index] = student.copy(name = name) },
                        onPriceChange = { price -> items[index] = student.copy(price = price) },
                        onDelete = { items.removeAt(index) },
                        onDragStart = { draggedItemId = student.id },
                        onDragEnd = {
                            draggedItemId = null
                            draggingOffset = 0f
                        },
                        onDrag = { dy ->
                            draggingOffset += dy
                            clampDragOffset()
                        },
                    )
                }

                if (isPlanningMode) {
                    item {
                        PlanningControls(
                            items = items,
                            date = date,
                            onAddStudent = {
                                val newId = UUID.randomUUID().toString()
                                items.add(
                                    StudentItem(
                                        id = newId,
                                        name = "",
                                        attended = false,
                                        type = StudentItemType.STUDENT,
                                    ),
                                )
                                focusItemId = newId
                            },
                            onAddIntensive = {
                                val newId = UUID.randomUUID().toString()
                                items.add(
                                    StudentItem(
                                        id = newId,
                                        name = "",
                                        attended = true,
                                        type = StudentItemType.INTENSIVE,
                                        price = "",
                                    ),
                                )
                                focusItemId = newId
                            },
                            onAddDiagnostics = {
                                val newId = UUID.randomUUID().toString()
                                items.add(
                                    StudentItem(
                                        id = newId,
                                        name = "",
                                        attended = true,
                                        type = StudentItemType.DIAGNOSTICS,
                                        price = "",
                                    ),
                                )
                                focusItemId = newId
                            },
                            repeatUntilEndOfMonth = repeatUntilEndOfMonth,
                            onRepeatUntilEndChange = { repeatUntilEndOfMonth = it },
                            repeatNextMonth = repeatNextMonth,
                            onRepeatNextMonthChange = { repeatNextMonth = it },
                        )
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
                                StudentItemType.STUDENT -> if (it.name.isNotBlank()) {
                                    add(SessionFormat.serializeStudent(it.name, it.attended))
                                }

                                StudentItemType.INTENSIVE -> if (it.price.isNotBlank()) {
                                    add(SessionFormat.serializeIntensive(it.price, it.name, it.attended))
                                }

                                StudentItemType.DIAGNOSTICS -> if (it.price.isNotBlank()) {
                                    add(SessionFormat.serializeDiagnostics(it.price, it.name, it.attended))
                                }
                            }
                        }
                    }
                    onSave(finalNames, repeatUntilEndOfMonth, repeatNextMonth)
                },
            )
        }
    }
}

@Composable
private fun PlanningControls(
    items: SnapshotStateList<StudentItem>,
    date: LocalDate,
    onAddStudent: () -> Unit,
    onAddIntensive: () -> Unit,
    onAddDiagnostics: () -> Unit,
    repeatUntilEndOfMonth: Boolean,
    onRepeatUntilEndChange: (Boolean) -> Unit,
    repeatNextMonth: Boolean,
    onRepeatNextMonthChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val canAddStudent by remember {
            derivedStateOf {
                items.count { it.type == StudentItemType.STUDENT } < MAX_STUDENTS
            }
        }
        if (canAddStudent) {
            OutlinedButton(
                onClick = onAddStudent,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Добавить ребёнка", fontWeight = FontWeight.Medium)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onAddIntensive,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Интенсив", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }

            OutlinedButton(
                onClick = onAddDiagnostics,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Диагностика", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }

        val dayOfWeekLower = remember(date) {
            date.dayOfWeek.getDisplayName(TextStyle.FULL, RU_LOCALE).lowercase(RU_LOCALE)
        }

        RepeatRow(
            text = "Дублировать на все $dayOfWeekLower до конца месяца",
            checked = repeatUntilEndOfMonth,
            onCheckedChange = onRepeatUntilEndChange,
            paddingTop = 16,
        )

        RepeatRow(
            text = "Дублировать на все $dayOfWeekLower следующего месяца",
            checked = repeatNextMonth,
            onCheckedChange = onRepeatNextMonthChange,
            paddingTop = 8,
        )
    }
}

@Composable
private fun RepeatRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    paddingTop: Int,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = paddingTop.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
            )

            is Session.Diagnostics -> StudentItem(
                id = UUID.randomUUID().toString(),
                name = session.name,
                attended = session.attended,
                type = StudentItemType.DIAGNOSTICS,
                price = if (session.amount == 0.0) "" else session.amount.toLong().toString(),
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

@Preview(showBackground = true, name = "Day Details Light")
@Composable
private fun DayDetailsLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.LightGray) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = LocalDate.now(),
                    initialNames = listOf("Света|true", "Иван|false"),
                    userProfile = UserProfile(pricePerSession = 1200.0),
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                )
            }
        }
    }
}
