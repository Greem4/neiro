package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
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
    onSave: (List<String>) -> Unit
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
    onSave: (List<String>) -> Unit
) {
    // Внутренняя модель для отслеживания прихода (формат "Name|attended")
    var items by remember { 
        mutableStateOf(
            initialNames.ifEmpty { listOf("") }.map { 
                val parts = it.split("|")
                val name = parts[0]
                val attended = parts.getOrNull(1)?.toBoolean() ?: true
                name to attended
            }
        )
    }
    
    // Состояния для реализации Drag-and-Drop
    val listState = rememberLazyListState()
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

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
                val totalCount = items.filter { it.first.isNotBlank() }.size
                val attendedCount = items.filter { it.first.isNotBlank() && it.second }.size
                
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

                if (userProfile.pricePerSession > 0) {
                    val totalMoney = attendedCount * userProfile.pricePerSession
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Итого ($attendedCount): ${totalMoney.toInt()} ₽",
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
                itemsIndexed(items) { index, (name, attended) ->
                    val isDragging = draggedItemIndex == index
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                translationY = if (isDragging) draggingOffset else 0f
                                scaleX = if (isDragging) 1.02f else 1f
                                scaleY = if (isDragging) 1.02f else 1f
                                shadowElevation = if (isDragging) 8f else 0f
                                alpha = if (isDragging) 0.9f else 1f
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
                                .padding(end = 4.dp)
                        ) {
                            // Галочка "Пришел"
                            IconButton(
                                onClick = {
                                    val newList = items.toMutableList()
                                    newList[index] = name to !attended
                                    items = newList
                                }
                            ) {
                                Icon(
                                    imageVector = if (attended) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = if (attended) "Пришел" else "Не пришел",
                                    tint = if (attended) MaterialTheme.colorScheme.primary 
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }

                            // Поле ввода фамилии
                            TextField(
                                value = name,
                                onValueChange = { newName ->
                                    val newList = items.toMutableList()
                                    newList[index] = newName to attended
                                    items = newList
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
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = if (attended) MaterialTheme.colorScheme.onSurface 
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            )
                            
                            // Хендл для перетаскивания
                            IconButton(
                                onClick = {},
                                modifier = Modifier.pointerInput(index, items.size) {
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
                                            
                                            val threshold = 45f 
                                            if (draggingOffset > threshold && index < items.size - 1) {
                                                val newList = items.toMutableList()
                                                val item = newList.removeAt(index)
                                                newList.add(index + 1, item)
                                                items = newList
                                                draggedItemIndex = index + 1
                                                draggingOffset = 0f
                                            } else if (draggingOffset < -threshold && index > 0) {
                                                val newList = items.toMutableList()
                                                val item = newList.removeAt(index)
                                                newList.add(index - 1, item)
                                                items = newList
                                                draggedItemIndex = index - 1
                                                draggingOffset = 0f
                                            }
                                        }
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Перетащить",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
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
                                newList.add("" to false)
                                items = newList
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Добавить ученика", fontWeight = FontWeight.Medium)
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
                        onSave(items.filter { it.first.isNotBlank() }.map { "${it.first}|${it.second}" }) 
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
                    onSave = {}
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
                    onSave = {}
                )
            }
        }
    }
}
