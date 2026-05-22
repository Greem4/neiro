package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.getMonthName
import java.time.YearMonth

/**
 * Шапка: логотип, навигация по месяцу, «Сегодня» справа.
 *
 * Переключение YClients / архив и обновление — в [CalendarToolbar].
 *
 * @param onMenuClick Тап по [NeiroLogo] — боковая панель профиля.
 */
@Composable
fun CalendarHeader(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onMenuClick: () -> Unit,
    onTodayClick: () -> Unit,
    isRegistered: Boolean = true,
    onRegistrationRequired: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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

        FilledTonalButton(
            onClick = {
                if (isRegistered) onTodayClick() else onRegistrationRequired()
            },
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Today,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Сегодня",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
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
                onTodayClick = {},
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
                onPreviousMonth = {},
                onNextMonth = {},
                onMenuClick = {},
                onTodayClick = {},
            )
        }
    }
}
