package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val rowStartPadding: Dp = 12.dp
    val rowEndPadding: Dp = 4.dp
    val rowVerticalPadding: Dp = 6.dp
    val rowHeight: Dp = 44.dp

    val logoSize: Dp = 36.dp

    val monthNavButtonSize: Dp = 40.dp
    val monthNavIconSize: Dp = 24.dp
    val monthTitleFontSize = 16.sp
    /** Фиксированная ширина подписи — стрелки не смещаются при смене месяца. */
    val monthTitleWidth: Dp = 148.dp

    val bellButtonSize: Dp = 44.dp
    val bellIconSize: Dp = 24.dp
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
    onMonthTitleClick: () -> Unit = {},
    onMenuClick: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    unreadNotificationCount: Int = 0,
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

        // [2] ВЫБОР МЕСЯЦА — стрелки и подпись с фиксированной геометрией
        Box(
            modifier = Modifier
                .weight(1f)
                .height(CalendarHeaderLayout.rowHeight),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(CalendarHeaderLayout.monthTitleWidth)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onMonthTitleClick)
                        .padding(vertical = 4.dp),
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
        }

        // =========================================================================================
        // [3] УВЕДОМЛЕНИЯ (Справа)
        // =========================================================================================
        NotificationsBellButton(
            unreadCount = unreadNotificationCount,
            onClick = onNotificationsClick,
        )
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
                    Badge(
                        containerColor = Color.Red,
                        contentColor = Color.White
                    ) {
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
            )
        }
    }
}
