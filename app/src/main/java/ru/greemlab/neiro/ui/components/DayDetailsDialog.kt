package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.greemlab.neiro.theme.NeiroTheme

/**
 * Диалоговое окно для редактирования списка людей (фамилий) на выбранную дату.
 * 
 * @param date Выбранная дата.
 * @param initialNames Начальный список фамилий.
 * @param onDismiss Функция закрытия диалога без сохранения.
 * @param onSave Функция сохранения отредактированного списка.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DayDetailsDialog(
    date: LocalDate,
    initialNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    // Состояние списка фамилий внутри диалога
    var names by remember { 
        mutableStateOf(initialNames.ifEmpty { listOf("") })
    }
    
    // Состояния для реализации Drag-and-Drop
    val listState = rememberLazyListState()
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffset by remember { mutableStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                    itemsIndexed(names) { index, name ->
                        val isDragging = draggedItemIndex == index
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = if (isDragging) draggingOffset else 0f
                                    scaleX = if (isDragging) 1.04f else 1f
                                    scaleY = if (isDragging) 1.04f else 1f
                                    shadowElevation = if (isDragging) 12f else 0f
                                    alpha = if (isDragging) 0.95f else 1f
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            },
                            tonalElevation = if (isDragging) 4.dp else 0.dp
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                // Область для перетаскивания
                                Box(
                                    modifier = Modifier
                                        .pointerInput(index, names.size) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { draggedItemIndex = index },
                                                onDragEnd = { 
                                                    draggedItemIndex = null
                                                    draggingOffset = 0f
                                                },
                                                onDragCancel = { 
                                                    draggedItemIndex = null
                                                    draggingOffset = 0f
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    draggingOffset += dragAmount.y
                                                    
                                                    val threshold = 45f // Порог чувствительности для смены позиций
                                                    if (draggingOffset > threshold && index < names.size - 1) {
                                                        val newList = names.toMutableList()
                                                        val item = newList.removeAt(index)
                                                        newList.add(index + 1, item)
                                                        names = newList
                                                        draggedItemIndex = index + 1
                                                        draggingOffset = 0f
                                                    } else if (draggingOffset < -threshold && index > 0) {
                                                        val newList = names.toMutableList()
                                                        val item = newList.removeAt(index)
                                                        newList.add(index - 1, item)
                                                        names = newList
                                                        draggedItemIndex = index - 1
                                                        draggingOffset = 0f
                                                    }
                                                }
                                            )
                                        }
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = "Перетащить",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }

                                // Поле ввода фамилии
                                TextField(
                                    value = name,
                                    onValueChange = { newName ->
                                        val newList = names.toMutableList()
                                        newList[index] = newName
                                        names = newList
                                    },
                                    placeholder = { 
                                        Text(
                                            "Фамилия ${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        ) 
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    textStyle = MaterialTheme.typography.bodyLarge
                                )
                                
                                // Кнопка удаления
                                IconButton(
                                    onClick = {
                                        val newList = names.toMutableList()
                                        newList.removeAt(index)
                                        names = newList
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete, 
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Сообщение, если список пуст
                if (names.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Список пуст",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Нижняя панель с кнопками управления
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (names.size < 12) {
                        TextButton(
                            onClick = {
                                val newList = names.toMutableList()
                                newList.add("")
                                names = newList
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Добавить", fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Отмена")
                        }
                        Button(
                            onClick = { 
                                // Сохраняем только непустые записи
                                onSave(names.filter { it.isNotBlank() }) 
                            },
                            shape = RoundedCornerShape(12.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Text("Сохранить", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true)
@Composable
fun DayDetailsDialogPreview() {
    NeiroTheme {
        DayDetailsDialog(
            date = LocalDate.now(),
            initialNames = listOf("Иванов", "Петров", "Сидоров"),
            onDismiss = {},
            onSave = {}
        )
    }
}
