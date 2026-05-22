package ru.greemlab.neiro.ui.components

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CollectionsBookmark
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
    onModeChange: (CalendarMode) -> Unit = {},
    isRegistered: Boolean = true,
    onRegistrationRequired: () -> Unit = {},
) {
    val title = remember(currentMonth) { "${getMonthName(currentMonth)} ${currentMonth.year}" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            NeiroLogo(size = 30.dp, onClick = onMenuClick)

            Spacer(modifier = Modifier.width(4.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
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
                        text = if (calendarMode == CalendarMode.SYNCED) "Синхронизация" else "Личный",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "Сегодня",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (isRegistered) onTodayClick() else onRegistrationRequired()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий месяц",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp),
                )
            }

            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий месяц",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp),
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
