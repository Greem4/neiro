package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun StudentItemRow(
    student: StudentItem,
    index: Int,
    isDragging: Boolean,
    draggingOffset: Float,
    scale: Float,
    rotation: Float,
    elevation: Float,
    focusRequester: FocusRequester,
    isFocused: Boolean,
    isPlanningMode: Boolean = true,
    onAttendedChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDrag: (Float) -> Unit
) {
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
            IconButton(onClick = { onAttendedChange(!student.attended) }) {
                Icon(
                    imageVector = if (student.attended) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (student.attended) "Пришел" else "Не пришел",
                    tint = if (student.attended) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            if (isPlanningMode) {
                TextField(
                    value = student.name,
                    onValueChange = onNameChange,
                    placeholder = { 
                        Text(
                            "Фамилия ${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        ) 
                    },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (isFocused) Modifier.focusRequester(focusRequester) else Modifier),
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
            } else {
                Text(
                    text = student.name.ifEmpty { "Без имени" },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (student.attended) MaterialTheme.colorScheme.onSurface 
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .pointerInput(student.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
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
            
            IconButton(onClick = onDelete) {
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
