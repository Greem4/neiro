package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarMode
import ru.greemlab.neiro.ui.calendar.getMonthName
import java.time.YearMonth

/**
 * Компактная шапка: логотип, навигация по месяцу по центру, переключатель режима справа.
 *
 * «Сегодня» и синхронизация — в [CalendarToolbar] над сеткой календаря.
 *
 * @param onMenuClick Тап по [NeiroLogo] — боковая панель профиля.
 */
@Composable
fun CalendarHeader(
    currentMonth: YearMonth,
    calendarMode: CalendarMode = CalendarMode.SYNCED,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onMenuClick: () -> Unit,
    onModeChange: (CalendarMode) -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeiroLogo(size = 32.dp, onClick = onMenuClick)

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий месяц",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            Text(
                text = "${getMonthName(currentMonth)} ${currentMonth.year}",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий месяц",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        CalendarModeChip(
            calendarMode = calendarMode,
            onModeChange = onModeChange,
        )
    }
}

@Composable
private fun CalendarModeChip(
    calendarMode: CalendarMode,
    onModeChange: (CalendarMode) -> Unit,
) {
    val isSynced = calendarMode == CalendarMode.SYNCED
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                onModeChange(
                    if (isSynced) CalendarMode.PERSONAL else CalendarMode.SYNCED,
                )
            },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (isSynced) Icons.Rounded.CloudSync else Icons.Rounded.CollectionsBookmark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (isSynced) "Синхр" else "Архив",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

@Preview(showBackground = true, name = "Light Theme")
@Composable
private fun CalendarHeaderLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CalendarHeader(
                currentMonth = YearMonth.of(2024, 10),
                onPreviousMonth = {},
                onNextMonth = {},
                onMenuClick = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark Theme")
@Composable
private fun CalendarHeaderDarkPreview() {
    NeiroTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CalendarHeader(
                currentMonth = YearMonth.of(2024, 10),
                calendarMode = CalendarMode.PERSONAL,
                onPreviousMonth = {},
                onNextMonth = {},
                onMenuClick = {},
            )
        }
    }
}
