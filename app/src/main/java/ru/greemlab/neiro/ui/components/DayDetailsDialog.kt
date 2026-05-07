package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.components.daydetails.*

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
    var items by remember { 
        mutableStateOf(
            initialNames.filter { it.isNotBlank() }.map { 
                if (it.startsWith("__INTENSIVE__:")) {
                    val content = it.removePrefix("__INTENSIVE__:")
                    val parts = content.split("|")
                    StudentItem(
                        id = UUID.randomUUID().toString(),
                        name = parts.getOrNull(1) ?: "",
                        attended = parts.getOrNull(2)?.toBoolean() ?: true,
                        type = StudentItemType.INTENSIVE,
                        price = parts[0]
                    )
                } else if (it.startsWith("__DIAGNOSTICS__:")) {
                    val content = it.removePrefix("__DIAGNOSTICS__:")
                    val parts = content.split("|")
                    StudentItem(
                        id = UUID.randomUUID().toString(),
                        name = parts.getOrNull(1) ?: "",
                        attended = parts.getOrNull(2)?.toBoolean() ?: true,
                        type = StudentItemType.DIAGNOSTICS,
                        price = parts[0]
                    )
                } else {
                    val parts = it.split("|")
                    StudentItem(
                        id = UUID.randomUUID().toString(),
                        name = parts[0],
                        attended = parts.getOrNull(1)?.toBoolean() ?: false,
                        type = StudentItemType.STUDENT
                    )
                }
            }.let { if (it.isEmpty()) listOf(StudentItem(id = UUID.randomUUID().toString(), name = "", attended = false)) else it }
        )
    }

    val focusRequester = remember { FocusRequester() }
    var focusItemId by remember { mutableStateOf<String?>(null) }
    var repeatUntilEndOfMonth by remember { mutableStateOf(false) }
    var repeatNextMonth by remember { mutableStateOf(false) }
    
    var isPlanningMode by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(focusItemId) {
        if (focusItemId != null) {
            focusRequester.requestFocus()
            focusItemId = null
        }
    }

    // Логика авто-скролла при перетаскивании
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
            val studentCount = items.filter { it.type == StudentItemType.STUDENT && it.name.isNotBlank() }.size
            val attendedStudents = items.filter { it.type == StudentItemType.STUDENT && it.name.isNotBlank() && it.attended }.size
            
            val intensiveMoney = items.filter { it.type == StudentItemType.INTENSIVE && it.attended }
                .sumOf { it.price.toDoubleOrNull() ?: 0.0 }
            val diagnosticsMoney = items.filter { it.type == StudentItemType.DIAGNOSTICS && it.attended }
                .sumOf { it.price.toDoubleOrNull() ?: 0.0 }
            
            val totalMoney = (attendedStudents * userProfile.pricePerSession) + intensiveMoney + diagnosticsMoney

            DayDetailsHeader(
                date = date,
                totalCount = studentCount,
                totalMoney = totalMoney,
                showMoney = userProfile.pricePerSession > 0 || intensiveMoney > 0 || diagnosticsMoney > 0,
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
                    val scale by animateFloatAsState(if (isDragging) 1.05f else 1f, spring(stiffness = Spring.StiffnessLow))
                    val rotation by animateFloatAsState(if (isDragging) -1.5f else 0f, spring(stiffness = Spring.StiffnessMediumLow))
                    val elevation by animateFloatAsState(if (isDragging) 12f else 0f, spring(stiffness = Spring.StiffnessLow))
                    
                    StudentItemRow(
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
                            items = items.toMutableList().apply { this[index] = student.copy(attended = attended) }
                        },
                        onNameChange = { name ->
                            items = items.toMutableList().apply { this[index] = student.copy(name = name) }
                        },
                        onPriceChange = { price ->
                            items = items.toMutableList().apply { this[index] = student.copy(price = price) }
                        },
                        onDelete = { items = items.toMutableList().apply { removeAt(index) } },
                        onDragStart = { draggedItemId = student.id },
                        onDragEnd = { draggedItemId = null; draggingOffset = 0f },
                        onDrag = { dy ->
                            draggingOffset += dy
                            val itemHeight = 64f
                            val threshold = itemHeight * 0.9f
                            val currentIndex = items.indexOfFirst { it.id == draggedItemId }
                            if (currentIndex != -1) {
                                if (draggingOffset > threshold && currentIndex < items.size - 1) {
                                    items = items.toMutableList().apply { add(currentIndex + 1, removeAt(currentIndex)) }
                                    draggingOffset -= itemHeight
                                } else if (draggingOffset < -threshold && currentIndex > 0) {
                                    items = items.toMutableList().apply { add(currentIndex - 1, removeAt(currentIndex)) }
                                    draggingOffset += itemHeight
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
                            if (items.count { it.type == StudentItemType.STUDENT } < 12) {
                                OutlinedButton(
                                    onClick = {
                                        val newId = UUID.randomUUID().toString()
                                        items = items + StudentItem(id = newId, name = "", attended = false, type = StudentItemType.STUDENT)
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
                                        items = items + StudentItem(id = newId, name = "", attended = true, type = StudentItemType.INTENSIVE, price = "")
                                        focusItemId = newId
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Интенсив", style = MaterialTheme.typography.labelLarge)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val newId = UUID.randomUUID().toString()
                                        items = items + StudentItem(id = newId, name = "", attended = true, type = StudentItemType.DIAGNOSTICS, price = "")
                                        focusItemId = newId
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Диагностика", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }

                    item {
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
                                    text = "Дублировать на все ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale("ru")).lowercase()} до конца месяца",
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
                                    text = "Дублировать на все ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale("ru")).lowercase()} следующего месяца",
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
                    val finalNames = items.filter { 
                        (it.type == StudentItemType.STUDENT && it.name.isNotBlank()) ||
                        (it.type != StudentItemType.STUDENT && it.price.isNotBlank())
                    }.map { 
                        when (it.type) {
                            StudentItemType.INTENSIVE -> "__INTENSIVE__:${it.price}|${it.name}|${it.attended}"
                            StudentItemType.DIAGNOSTICS -> "__DIAGNOSTICS__:${it.price}|${it.name}|${it.attended}"
                            else -> "${it.name}|${it.attended}"
                        }
                    }
                    onSave(finalNames, repeatUntilEndOfMonth, repeatNextMonth)
                }
            )
        }
    }
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
