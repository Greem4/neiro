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
 * Размеры и отступы верхней плашки календаря.
 *
 * ## Как подогнать вручную (Figma / превью)
 *
 * 1. Открой [CalendarHeader] в Android Studio → Split / Design → превью Light/Dark.
 * 2. Меняй значения ниже **по одному**, смотри превью; центр месяца сжимается первым
 *    (у него `Modifier.weight(1f)`), правая группа (колокольчик + «Сегодня») — фиксированная ширина.
 * 3. Если не влезает длинное «Сентябрь 2025»:
 *    - уменьши [monthTitleFontSize] или [monthNavIconSize];
 *    - уменьши [todayButtonHorizontalPadding] / скрой текст «Сегодня» (см. [showTodayLabel]);
 *    - увеличь [rowEndPadding] только если нужен воздух у края экрана.
 * 4. «Перетаскивание» в Compose — это не drag-and-drop, а сдвиг через отступы:
 *    - весь ряд: [rowStartPadding], [rowEndPadding], [rowVerticalPadding];
 *    - блок месяца по горизонтали: [monthTitleHorizontalPadding] между стрелками и текстом;
 *    - колокольчик ↔ «Сегодня»: [rightActionsSpacing];
 *    - логотип ↔ месяц: фиксированная ширина логотипа ([logoSize] в [NeiroLogo]).
 *
 * Порядок слева направо: **логотип | ‹ месяц год › | 🔔 | Сегодня**.
 */
object CalendarHeaderLayout {
    /** Внешние отступы всей плашки от краёв экрана (слева чуть больше — под логотип). */
    val rowStartPadding: Dp = 12.dp
    val rowEndPadding: Dp = 4.dp
    val rowVerticalPadding: Dp = 4.dp

    /** Логотип «N» — передаётся в [NeiroLogo]. */
    val logoSize: Dp = 32.dp

    /** Кнопки ‹ › вокруг названия месяца. */
    val monthNavButtonSize: Dp = 36.dp
    val monthNavIconSize: Dp = 22.dp

    /** Заголовок «Октябрь 2024». */
    val monthTitleFontSize = 16.sp
    val monthTitleHorizontalPadding: Dp = 0.dp

    /** Правая группа: колокольчик и «Сегодня». */
    val rightActionsSpacing: Dp = 0.dp
    val bellButtonSize: Dp = 40.dp
    val bellIconSize: Dp = 22.dp

    val todayButtonCorner: Dp = 12.dp
    val todayButtonHorizontalPadding: Dp = 8.dp
    val todayButtonVerticalPadding: Dp = 6.dp
    val todayIconSize: Dp = 18.dp
    val todayLabelStartPadding: Dp = 4.dp

    /** false — только иконка календаря, больше места для месяца на узких экранах. */
    const val showTodayLabel: Boolean = true
}

/**
 * Шапка: логотип, навигация по месяцу, колокольчик уведомлений, «Сегодня» справа.
 *
 * Переключение YClients / архив и обновление — в [CalendarToolbar].
 *
 * @param onMenuClick Тап по [NeiroLogo] — боковая панель профиля.
 * @param onNotificationsClick Открыть ленту in-app уведомлений.
 * @param unreadNotificationCount Бейдж на колокольчике (0 — скрыт).
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
        NeiroLogo(size = CalendarHeaderLayout.logoSize, onClick = onMenuClick)

        Row(
            modifier = Modifier
                .weight(1f)
                .widthIn(min = 0.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(CalendarHeaderLayout.rightActionsSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotificationsBellButton(
                unreadCount = unreadNotificationCount,
                onClick = onNotificationsClick,
            )

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
        }
    }
}

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
