package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.greemlab.neiro.R
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.getMonthName
import java.time.YearMonth

/**
 * ПАРАМЕТРЫ ДИЗАЙНА (Твикай здесь)
 */
object CalendarHeaderLayout {
    // Весь контейнер
    val rowStartPadding: Dp = 12.dp    // Отступ слева (от края до лого)
    val rowEndPadding: Dp = 4.dp      // Отступ справа (от края до "Сегодня")
    val rowVerticalPadding: Dp = 4.dp // Отступ сверху/снизу

    // 1. Логотип
    val logoSize: Dp = 32.dp

    // 2. Блок месяца (центр)
    val monthNavButtonSize: Dp = 36.dp
    val monthNavIconSize: Dp = 22.dp
    val monthTitleFontSize = 16.sp

    // ХОЧЕШЬ МЕСЯЦ БЛИЖЕ К СТРЕЛКАМ? Уменьшай или увеличивай это число:
    val monthTitleHorizontalPadding: Dp = 0.dp 

    // 3. Правый блок (Колокольчик + Сегодня)
    val rightActionsSpacing: Dp = 0.dp   // Расстояние между колокольчиком и кнопкой
    val bellButtonSize: Dp = 40.dp
    val bellIconSize: Dp = 22.dp

    // Кнопка "Сегодня"
    val todayButtonCorner: Dp = 12.dp
    val todayButtonHorizontalPadding: Dp = 8.dp
    val todayButtonVerticalPadding: Dp = 6.dp
    val todayIconSize: Dp = 18.dp
    val todayLabelStartPadding: Dp = 4.dp // Отступ текста от иконки
    const val showTodayLabel: Boolean = true // false - скрыть текст "Сегодня"
}

/**
 * Шапка календаря.
 * Чтобы быстро перейти к коду кнопки: Ctrl (или Cmd) + Клик по кнопке в Preview справа.
 */
@Composable
fun CalendarHeader(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onMenuClick: () -> Unit,
    onTodayClick: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    unreadNotificationCount: Int = 0,
    isRegistered: Boolean = true,
    onRegistrationRequired: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = CalendarHeaderLayout.rowStartPadding,
                end = CalendarHeaderLayout.rowEndPadding,
                top = CalendarHeaderLayout.rowVerticalPadding,
                bottom = CalendarHeaderLayout.rowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // =========================================================================================
        // [1] ЛОГОТИП (Слева)
        // Чтобы изменить размер: меняй CalendarHeaderLayout.logoSize
        // =========================================================================================
        NeiroLogo(size = CalendarHeaderLayout.logoSize, onClick = onMenuClick)

        // =========================================================================================
        // [2] ВЫБОР МЕСЯЦА (Центр)
        //
        // ХОЧЕШЬ СДВИНУТЬ ВЕСЬ БЛОК ВЛЕВО? 
        // Поменяй ниже Arrangement.Center на Arrangement.Start
        // =========================================================================================
        Row(
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Кнопка: Назад
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.size(CalendarHeaderLayout.monthNavButtonSize),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Предыдущий месяц",
                    modifier = Modifier.size(CalendarHeaderLayout.monthNavIconSize),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }

            // Название месяца (например: "Октябрь 2024")
            Text(
                text = "${getMonthName(currentMonth)} ${currentMonth.year}",
                style = MaterialTheme.typography.titleMedium,
                fontSize = CalendarHeaderLayout.monthTitleFontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = CalendarHeaderLayout.monthTitleHorizontalPadding),
            )

            // Кнопка: Вперед
            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.size(CalendarHeaderLayout.monthNavButtonSize),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Следующий месяц",
                    modifier = Modifier.size(CalendarHeaderLayout.monthNavIconSize),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        // =========================================================================================
        // [3] ДЕЙСТВИЯ (Справа)
        // В этом Row лежат Колокольчик и кнопка Сегодня.
        // Чтобы поменять их местами - просто переставь вызовы функций местами.
        // =========================================================================================
        Row(
            horizontalArrangement = Arrangement.spacedBy(CalendarHeaderLayout.rightActionsSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // КНОПКА "СЕГОДНЯ" (Теперь слева)
            FilledTonalButton(
                onClick = {
                    if (isRegistered) onTodayClick() else onRegistrationRequired()
                },
                shape = RoundedCornerShape(CalendarHeaderLayout.todayButtonCorner),
                contentPadding = PaddingValues(
                    horizontal = CalendarHeaderLayout.todayButtonHorizontalPadding,
                    vertical = CalendarHeaderLayout.todayButtonVerticalPadding,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Today,
                    contentDescription = null,
                    modifier = Modifier.size(CalendarHeaderLayout.todayIconSize),
                )
                if (CalendarHeaderLayout.showTodayLabel) {
                    Text(
                        text = "Сегодня",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = CalendarHeaderLayout.todayLabelStartPadding),
                    )
                }
            }

            // КОЛОКОЛЬЧИК (Теперь справа)
            NotificationsBellButton(
                unreadCount = unreadNotificationCount,
                onClick = onNotificationsClick,
            )
        }
    }
}

/**
 * Отдельный компонент для колокольчика.
 * Нажми Cmd+Click в Preview на иконку уведомлений, чтобы попасть сюда.
 */
@Composable
private fun NotificationsBellButton(
    unreadCount: Int,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(CalendarHeaderLayout.bellButtonSize),
    ) {
        BadgedBox(
            badge = {
                if (unreadCount > 0) {
                    Badge {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Rounded.Notifications,
                contentDescription = stringResource(R.string.in_app_notifications_bell),
                modifier = Modifier.size(CalendarHeaderLayout.bellIconSize),
                tint = MaterialTheme.colorScheme.onBackground,
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
                onNotificationsClick = {},
                unreadNotificationCount = 3,
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
