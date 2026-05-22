package ru.greemlab.neiro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.ui.text.style.TextOverflow
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarMode
import ru.greemlab.neiro.ui.calendar.getMonthName
import java.time.YearMonth

/**
 * Шапка календаря с навигацией по месяцам и кнопками «Сегодня»/«N» (профиль).
 *
 * @param onMenuClick Тап по кругу [NeiroLogo] — открывает боковую панель профиля.
 * @param onRegistrationRequired Показ подсказки при тапе «Сегодня», если [isRegistered] == false.
 */
@Composable
fun CalendarHeader(
    currentMonth: YearMonth,
    calendarMode: CalendarMode = CalendarMode.SYNCED,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSyncClick: () -> Unit = {},
    isSyncing: Boolean = false,
    onModeChange: (CalendarMode) -> Unit = {},
    isRegistered: Boolean = true,
    onRegistrationRequired: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeiroLogo(size = 32.dp, onClick = onMenuClick)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${getMonthName(currentMonth)} ${currentMonth.year}",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Вторая строка: Статус
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        onModeChange(
                            if (calendarMode == CalendarMode.SYNCED) CalendarMode.PERSONAL
                            else CalendarMode.SYNCED
                        )
                    }
            ) {
                Icon(
                    imageVector = if (calendarMode == CalendarMode.SYNCED)
                        Icons.Rounded.CloudSync else Icons.Rounded.CollectionsBookmark,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (calendarMode == CalendarMode.SYNCED) "Синхронизация" else "Архив",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Кнопки управления
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            if (calendarMode == CalendarMode.SYNCED) {
                val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rotation"
                )

                IconButton(
                    onClick = onSyncClick,
                    modifier = Modifier.size(32.dp),
                    enabled = !isSyncing
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Синхронизировать месяц",
                        modifier = Modifier
                            .size(20.dp)
                            .then(if (isSyncing) Modifier.graphicsLayer(rotationZ = rotation) else Modifier),
                        tint = if (isSyncing) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = "Сегодня",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (isRegistered) onTodayClick() else onRegistrationRequired()
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )

            IconButton(onClick = onPreviousMonth, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий месяц",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = onNextMonth, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий месяц",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Light Theme")
@Composable
private fun CalendarHeaderLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(16.dp),
        ) {
            CalendarHeader(
                currentMonth = YearMonth.of(2024, 10),
                onPreviousMonth = {},
                onNextMonth = {},
                onTodayClick = {},
                onMenuClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun CalendarHeaderDarkPreview() {
    NeiroTheme(darkTheme = true) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(16.dp),
        ) {
            CalendarHeader(
                currentMonth = YearMonth.of(2024, 10),
                calendarMode = CalendarMode.PERSONAL,
                onPreviousMonth = {},
                onNextMonth = {},
                onTodayClick = {},
                onMenuClick = {},
            )
        }
    }
}
