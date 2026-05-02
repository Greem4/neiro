package ru.greemlab.neiro.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.NeiroTheme
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Шапка календаря с отображением текущего месяца и кнопками навигации.
 *
 * @param currentMonth Месяц и год, которые сейчас отображаются.
 * @param onPreviousMonth Обработчик нажатия для перехода к предыдущему месяцу.
 * @param onNextMonth Обработчик нажатия для перехода к следующему месяцу.
 * @param onTodayClick Обработчик нажатия кнопки "Сегодня".
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun CalendarHeader(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onTodayClick: () -> Unit,
    onMenuClick: () -> Unit,
    isRegistered: Boolean = true,
    onRegistrationRequired: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Левая часть: Логотип, Месяц и "Сегодня"
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onMenuClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                NeiroLogo(size = 30.dp)
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = currentMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))
                    .replaceFirstChar { it.uppercase() } + " " + currentMonth.year,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            
            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "Сегодня",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { 
                    if (isRegistered) onTodayClick() else onRegistrationRequired()
                }
            )
        }

        // Правая часть: Стрелки навигации
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий месяц",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }

            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий месяц",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Light Theme")
@Composable
fun CalendarHeaderLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(16.dp)
        ) {
            CalendarHeader(
                currentMonth = YearMonth.of(2024, 10),
                onPreviousMonth = {},
                onNextMonth = {},
                onTodayClick = {},
                onMenuClick = {}
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Dark Theme")
@Composable
fun CalendarHeaderDarkPreview() {
    NeiroTheme(darkTheme = true) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.padding(16.dp)
        ) {
            CalendarHeader(
                currentMonth = YearMonth.of(2024, 10),
                onPreviousMonth = {},
                onNextMonth = {},
                onTodayClick = {},
                onMenuClick = {}
            )
        }
    }
}
