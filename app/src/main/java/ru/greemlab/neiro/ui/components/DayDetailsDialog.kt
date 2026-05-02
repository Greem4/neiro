package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.domain.models.UserProfile

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
    onSave: (List<String>, Boolean) -> Unit
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
    onSave: (List<String>, Boolean) -> Unit
) {
    // Внутренняя модель для отслеживания прихода (формат "Name|attended")
    data class StudentItem(val id: String, val name: String, val attended: Boolean)

    val initialStudents = initialNames.filter { !it.startsWith("__") }
    var items by remember { 
        mutableStateOf(
            initialStudents.ifEmpty { listOf("") }.map { 
                val parts = it.split("|")
                val name = parts[0]
                val attended = parts.getOrNull(1)?.toBoolean() ?: false
                StudentItem(id = UUID.randomUUID().toString(), name = name, attended = attended)
            }
        )
    }

    var intensivePrice by remember { 
        mutableStateOf(initialNames.find { it.startsWith("__INTENSIVE__:") }?.split("|")?.get(0)?.removePrefix("__INTENSIVE__:") ?: "") 
    }
    var diagnosticsPrice by remember { 
        mutableStateOf(initialNames.find { it.startsWith("__DIAGNOSTICS__:") }?.split("|")?.get(0)?.removePrefix("__DIAGNOSTICS__:") ?: "") 
    }

    // Состояния для фокуса
    val focusRequester = remember { FocusRequester() }
    var focusItemId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focusItemId) {
        if (focusItemId != null) {
            focusRequester.requestFocus()
            focusItemId = null
        }
    }

    // Флаг повторения до конца месяца
    var repeatUntilEndOfMonth by remember { mutableStateOf(false) }
    
    // Состояния для реализации Drag-and-Drop
    val listState = rememberLazyListState()
    var draggedItemId by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

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
                
                val padding = 120f // Увеличенная зона срабатывания
                
                if (itemTop < topEdge + padding && listState.canScrollBackward) {
                    // Скорость зависит от близости к краю
                    val intensity = ((topEdge + padding) - itemTop) / padding
                    val scrollAmount = (intensity * 20f).coerceIn(2f, 25f)
                    listState.scrollBy(-scrollAmount)
                    draggingOffset -= scrollAmount // Точная компенсация
                } else if (itemBottom > bottomEdge - padding && listState.canScrollForward) {
                    val intensity = (itemBottom - (bottomEdge - padding)) / padding
                    val scrollAmount = (intensity * 20f).coerceIn(2f, 25f)
                    listState.scrollBy(scrollAmount)
                    draggingOffset += scrollAmount
                }
            }
            delay(12) // Более частый цикл для плавности
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f)
            .padding(vertical = 16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Заголовок с датой
            Text(
                text = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Отображение итога
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val totalCount = items.filter { it.name.isNotBlank() }.size
                val attendedCount = items.filter { it.name.isNotBlank() && it.attended }.size
                
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Всего: $totalCount",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (userProfile.pricePerSession > 0 || intensivePrice.isNotBlank() || diagnosticsPrice.isNotBlank()) {
                    val studentsMoney = attendedCount * userProfile.pricePerSession
                    val extraMoney = (intensivePrice.toDoubleOrNull() ?: 0.0) + (diagnosticsPrice.toDoubleOrNull() ?: 0.0)
                    val totalMoney = studentsMoney + extraMoney
                    
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Итого: ${totalMoney.toInt()} ₽",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Список фамилий с поддержкой перетаскивания
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, student ->
                    val isDragging = draggedItemId == student.id
                    
                    // Плавные пружинные анимации
                    val scale by animateFloatAsState(
                        targetValue = if (isDragging) 1.05f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "scale"
                    )
                    val rotation by animateFloatAsState(
                        targetValue = if (isDragging) -1.5f else 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "rotation"
                    )
                    val elevation by animateFloatAsState(
                        targetValue = if (isDragging) 12f else 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "elevation"
                    )
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (isDragging) draggingOffset else 0f
                                scaleX = scale
                                scaleY = scale
                                rotationZ = rotation
                                shadowElevation = elevation
                                alpha = if (isDragging) 0.85f else 1f
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isDragging) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        },
                        tonalElevation = if (isDragging) 6.dp else 0.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp)
                        ) {
                            // Галочка "Пришел"
                            IconButton(
                                onClick = {
                                    val newList = items.toMutableList()
                                    newList[index] = student.copy(attended = !student.attended)
                                    items = newList
                                }
                            ) {
                                Icon(
                                    imageVector = if (student.attended) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = if (student.attended) "Пришел" else "Не пришел",
                                    tint = if (student.attended) MaterialTheme.colorScheme.primary 
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }

                            // Поле ввода фамилии
                            TextField(
                                value = student.name,
                                onValueChange = { newName ->
                                    val newList = items.toMutableList()
                                    newList[index] = student.copy(name = newName)
                                    items = newList
                                },
                                placeholder = { 
                                    Text(
                                        "Фамилия ${index + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    ) 
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (student.id == focusItemId) Modifier.focusRequester(focusRequester) else Modifier),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = if (student.attended) MaterialTheme.colorScheme.onSurface 
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            )
                            
                            // Область захвата (Полоски)
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .pointerInput(student.id, items.size) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { draggedItemId = student.id },
                                            onDragEnd = { 
                                                draggedItemId = null
                                                draggingOffset = 0f
                                            },
                                            onDragCancel = { 
                                                draggedItemId = null
                                                draggingOffset = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                draggingOffset += dragAmount.y
                                                
                                                val itemHeight = 64f // Приблизительная высота элемента
                                                // Увеличил порог до 0.9, чтобы переброс был менее "шустрым" и более осознанным
                                                val moveThreshold = itemHeight * 0.9f

                                                val currentIndex = items.indexOfFirst { it.id == draggedItemId }
                                                if (currentIndex != -1) {
                                                    if (draggingOffset > moveThreshold && currentIndex < items.size - 1) {
                                                        val newList = items.toMutableList()
                                                        val itemToMove = newList.removeAt(currentIndex)
                                                        newList.add(currentIndex + 1, itemToMove)
                                                        items = newList
                                                        draggingOffset -= itemHeight
                                                    } else if (draggingOffset < -moveThreshold && currentIndex > 0) {
                                                        val newList = items.toMutableList()
                                                        val itemToMove = newList.removeAt(currentIndex)
                                                        newList.add(currentIndex - 1, itemToMove)
                                                        items = newList
                                                        draggingOffset += itemHeight
                                                    }
                                                }
                                            }
                                        )
                                    }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Перетащить",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            // Кнопка удаления
                            IconButton(
                                onClick = {
                                    val newList = items.toMutableList()
                                    newList.removeAt(index)
                                    items = newList
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete, 
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Кнопка "Добавить" в конце списка
                item {
                    if (items.size < 12) {
                        OutlinedButton(
                            onClick = {
                                val newList = items.toMutableList()
                                val newId = UUID.randomUUID().toString()
                                newList.add(StudentItem(id = newId, name = "", attended = false))
                                items = newList
                                focusItemId = newId
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить ребенка", fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Дополнительный доход (Интенсив и Диагностика)
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Дополнительно",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = intensivePrice,
                                onValueChange = { intensivePrice = it.filter { char -> char.isDigit() } },
                                label = { Text("Интенсив", style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text("₽", style = MaterialTheme.typography.bodySmall) },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            )
                            
                            OutlinedTextField(
                                value = diagnosticsPrice,
                                onValueChange = { diagnosticsPrice = it.filter { char -> char.isDigit() } },
                                label = { Text("Диагностика", style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text("₽", style = MaterialTheme.typography.bodySmall) },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }

                // Переключатель повторения внутри списка (после кнопки Добавить)
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        onClick = { repeatUntilEndOfMonth = !repeatUntilEndOfMonth }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = repeatUntilEndOfMonth,
                                onCheckedChange = { repeatUntilEndOfMonth = it }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Дублировать на все ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale("ru")).lowercase()} до конца месяца",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Нижняя панель
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Отмена")
                }
                Button(
                    onClick = { 
                        // Сохраняем в формате "Имя|attended"
                        val finalNames = items.filter { it.name.isNotBlank() }.map { "${it.name}|${it.attended}" }.toMutableList()
                        if (intensivePrice.isNotBlank()) finalNames.add("__INTENSIVE__:$intensivePrice")
                        if (diagnosticsPrice.isNotBlank()) finalNames.add("__DIAGNOSTICS__:$diagnosticsPrice")
                        
                        onSave(finalNames, repeatUntilEndOfMonth)
                    },
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Text("Готово", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Day Details Light")
@Composable
fun DayDetailsLightPreview() {
    // --- РУКОЯТКИ ДЛЯ НАСТРОЙКИ (МЕНЯЙТЕ ТУТ) ---
    val testDate = LocalDate.now()
    val testNames = listOf("Света", "Иван", "Мария", "")
    val testPrice = 1200.0
    // ------------------------------------------

    NeiroTheme(darkTheme = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.LightGray
        ) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = testDate,
                    initialNames = testNames,
                    userProfile = UserProfile(pricePerSession = testPrice),
                    onDismiss = {},
                    onSave = { _, _ -> }
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Day Details Dark")
@Composable
fun DayDetailsDarkPreview() {
    NeiroTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.DarkGray
        ) {
            Box(contentAlignment = Alignment.Center) {
                DayDetailsContent(
                    date = LocalDate.now(),
                    initialNames = listOf("Света", "Иван"),
                    userProfile = UserProfile(pricePerSession = 1500.0),
                    onDismiss = {},
                    onSave = { _, _ -> }
                )
            }
        }
    }
}
