package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DayDetailsDialog(
    date: LocalDate,
    initialNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var names by remember { 
        mutableStateOf(initialNames.ifEmpty { listOf("") })
    }
    
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
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    itemsIndexed(names) { index, name ->
                        val isDragging = draggedItemIndex == index
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationY = if (isDragging) draggingOffset else 0f
                                    scaleX = if (isDragging) 1.02f else 1f
                                    scaleY = if (isDragging) 1.02f else 1f
                                    shadowElevation = if (isDragging) 8f else 0f
                                    alpha = if (isDragging) 0.9f else 1f
                                }
                                .background(
                                    if (isDragging) MaterialTheme.colorScheme.surfaceVariant 
                                    else Color.Transparent,
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(vertical = 2.dp)
                        ) {
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
                                                
                                                val threshold = 40f
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }

                            TextField(
                                value = name,
                                onValueChange = { newName ->
                                    val newList = names.toMutableList()
                                    newList[index] = newName
                                    names = newList
                                },
                                placeholder = { Text("Фамилия ${index + 1}") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge
                            )
                            
                            IconButton(onClick = {
                                val newList = names.toMutableList()
                                newList.removeAt(index)
                                names = newList
                            }) {
                                Icon(
                                    Icons.Default.Delete, 
                                    contentDescription = "Удалить",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (names.isEmpty()) {
                    Text(
                        "Список пуст",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

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
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Добавить")
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
                                onSave(names.filter { it.isNotBlank() }) 
                            },
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }
}
