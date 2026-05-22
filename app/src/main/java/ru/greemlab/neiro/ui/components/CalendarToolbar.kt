package ru.greemlab.neiro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.ui.calendar.CalendarMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Панель действий над сеткой календаря: переход к сегодняшнему дню и синхронизация месяца.
 */
@Composable
fun CalendarToolbar(
    currentMonth: YearMonth,
    calendarMode: CalendarMode,
    isRegistered: Boolean,
    isSyncing: Boolean,
    onTodayClick: () -> Unit,
    onSyncClick: () -> Unit,
    onRegistrationRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val isViewingTodayMonth = currentMonth == YearMonth.from(today)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = {
                if (isRegistered) onTodayClick() else onRegistrationRequired()
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Today,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (isViewingTodayMonth) "Сегодня" else "К сегодня",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        if (calendarMode == CalendarMode.SYNCED) {
            val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "rotation",
            )

            FilledTonalIconButton(
                onClick = onSyncClick,
                enabled = !isSyncing,
                shape = RoundedCornerShape(12.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Sync,
                    contentDescription = "Синхронизировать месяц",
                    modifier = Modifier
                        .size(22.dp)
                        .then(
                            if (isSyncing) Modifier.graphicsLayer(rotationZ = rotation) else Modifier,
                        ),
                    tint = if (isSyncing) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}
