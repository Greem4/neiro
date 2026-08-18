package ru.greemlab.neiro.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.R
import ru.greemlab.neiro.notifications.InAppNotification
import ru.greemlab.neiro.notifications.SessionEventType
import ru.greemlab.neiro.theme.GlassPanel
import ru.greemlab.neiro.theme.LocalGlassEnabled
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.util.RU_LOCALE
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val timeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU_LOCALE)

/**
 * ПАРАМЕТРЫ СВАЙПА (Твикай здесь)
 */
private object NotificationSwipeStyle {
    val cornerRadius: Dp = 16.dp
    val iconPadding: Dp = 20.dp

    /** Доля ширины карточки, после которой отпускание удаляет уведомление. */
    const val DISMISS_THRESHOLD = 0.35f

    /** Подложка не вспыхивает сразу: до порога она полупрозрачная. */
    const val BACKDROP_MIN_ALPHA = 0.35f
    const val ICON_MIN_SCALE = 0.7f
    const val ICON_OVERSHOOT = 0.15f

    /** Насколько уезжающая карточка успевает погаснуть к краю экрана. */
    const val CONTENT_FADE = 0.4f

    /** Доля высоты экрана под ленту: ниже — футер с кнопками, он всегда виден. */
    const val LIST_MAX_SCREEN_FRACTION = 0.45f
    val listMinHeight: Dp = 160.dp

    /** На стекле карточки тоже просвечивают, иначе панель выглядит заклеенной. */
    const val CARD_GLASS_ALPHA = 0.62f
}

/**
 * Лента in-app уведомлений (колокольчик в [CalendarHeader]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsDialog(
    notifications: List<InAppNotification>,
    onDismiss: () -> Unit,
    subtitleRes: Int = R.string.in_app_notifications_subtitle_sync,
    allowDismiss: Boolean = true,
    onNotificationClick: (InAppNotification) -> Unit = {},
    onDismissNotification: (InAppNotification) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NotificationsContent(
            notifications = notifications,
            subtitleRes = subtitleRes,
            allowDismiss = allowDismiss,
            onDismiss = onDismiss,
            onNotificationClick = onNotificationClick,
            onDismissNotification = onDismissNotification,
            onClearAll = onClearAll,
        )
    }
}

/**
 * Выделенный контент диалога для возможности превью без самого [Dialog].
 * Это решает проблемы с рендерингом Dialog в Compose Preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsContent(
    notifications: List<InAppNotification>,
    subtitleRes: Int,
    allowDismiss: Boolean,
    onDismiss: () -> Unit,
    onNotificationClick: (InAppNotification) -> Unit,
    onDismissNotification: (InAppNotification) -> Unit,
    onClearAll: () -> Unit,
) {
    // Лента не должна выдавливать кнопки за экран: ей отдаём долю высоты,
    // остальное остаётся футеру.
    val maxListHeight = with(LocalConfiguration.current) {
        (screenHeightDp * NotificationSwipeStyle.LIST_MAX_SCREEN_FRACTION).dp
            .coerceAtLeast(NotificationSwipeStyle.listMinHeight)
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 20.dp,
                end = 20.dp,
                top = 20.dp,
                bottom = 12.dp,
            ),
        ) {
            Text(
                text = stringResource(R.string.in_app_notifications_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            if (notifications.isEmpty()) {
                Text(
                    text = stringResource(R.string.in_app_notifications_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = maxListHeight),
                ) {
                    items(notifications, key = { it.id }) { item ->
                        // Соседи смыкаются пружиной, ушедший элемент гаснет — без этого
                        // список после свайпа дёргается.
                        val itemModifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 180),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            fadeOutSpec = tween(durationMillis = 180),
                        )
                        if (allowDismiss) {
                            SwipeableNotificationItem(
                                item = item,
                                onClick = { onNotificationClick(item) },
                                onDismiss = { onDismissNotification(item) },
                                modifier = itemModifier,
                            )
                        } else {
                            NotificationListItem(
                                item = item,
                                onClick = { onNotificationClick(item) },
                                modifier = itemModifier,
                            )
                        }
                    }
                }
            }

            // Кнопки отделены линией и стоят в один ряд: раньше они шли двумя
            // строками сразу под обрезанной карточкой и читались как её часть.
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (allowDismiss && notifications.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.in_app_notifications_clear))
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                FilledTonalButton(onClick = onDismiss) {
                    Text(stringResource(R.string.in_app_notifications_close))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNotificationItem(
    item: InAppNotification,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NotificationSwipeStyle.cornerRadius)
    // Ширина карточки нужна анимациям подложки; читаем её в draw-фазе, поэтому
    // за жест не происходит ни одной рекомпозиции.
    var widthPx by remember { mutableFloatStateOf(0f) }

    val dismissState = rememberSwipeToDismissBoxState(
        // Смахнуть можно в обе стороны — обе удаляют уведомление.
        confirmValueChange = { it != SwipeToDismissBoxValue.Settled },
        positionalThreshold = { distance -> distance * NotificationSwipeStyle.DISMISS_THRESHOLD },
    )

    // Удаляем не в момент отпускания, а когда карточка уже уехала за край: иначе
    // элемент пропадает рывком посреди анимации.
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.currentValue }
            .first { it != SwipeToDismissBoxValue.Settled }
        onDismiss()
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clip(shape),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            SwipeDeleteBackground(state = dismissState, widthPx = widthPx)
        },
        content = {
            NotificationListItem(
                item = item,
                onClick = onClick,
                modifier = Modifier.graphicsLayer {
                    // Уезжающая карточка слегка гаснет — движение читается мягче.
                    alpha = 1f - NotificationSwipeStyle.CONTENT_FADE * dismissState.swipeFraction(widthPx)
                },
            )
        },
    )
}

/**
 * Подложка под карточкой: корзина с той стороны, откуда тянут.
 * Наливается и растёт по мере приближения к порогу удаления.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDeleteBackground(
    state: SwipeToDismissBoxState,
    widthPx: Float,
) {
    val direction = state.dismissDirection
    if (direction == SwipeToDismissBoxValue.Settled) return

    val backdropColor = MaterialTheme.colorScheme.errorContainer
    val corner = NotificationSwipeStyle.cornerRadius

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val reach = state.swipeReach(widthPx)
                drawRoundRect(
                    color = backdropColor.copy(
                        alpha = NotificationSwipeStyle.BACKDROP_MIN_ALPHA +
                            (1f - NotificationSwipeStyle.BACKDROP_MIN_ALPHA) * reach,
                    ),
                    cornerRadius = CornerRadius(corner.toPx()),
                )
            }
            .padding(horizontal = NotificationSwipeStyle.iconPadding),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        },
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = stringResource(R.string.in_app_notifications_dismiss),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.graphicsLayer {
                val reach = state.swipeReach(widthPx)
                // После порога иконка ещё чуть подрастает — видно, что отпускать пора.
                val overshoot = state.swipeOvershoot(widthPx)
                val scale = NotificationSwipeStyle.ICON_MIN_SCALE +
                    (1f - NotificationSwipeStyle.ICON_MIN_SCALE) * reach +
                    NotificationSwipeStyle.ICON_OVERSHOOT * overshoot
                scaleX = scale
                scaleY = scale
                alpha = reach
            },
        )
    }
}

/** Насколько карточка уведена от места, долей ширины: 0f — на месте, 1f — за краем. */
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeToDismissBoxState.swipeFraction(widthPx: Float): Float {
    if (widthPx <= 0f) return 0f
    // requireOffset() падает, пока якоря не размечены, — до первой компоновки ответ 0f.
    val offset = runCatching { requireOffset() }.getOrDefault(0f)
    return (abs(offset) / widthPx).coerceIn(0f, 1f)
}

/** Путь до порога удаления: 1f означает «отпусти — удалится». */
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeToDismissBoxState.swipeReach(widthPx: Float): Float =
    (swipeFraction(widthPx) / NotificationSwipeStyle.DISMISS_THRESHOLD).coerceIn(0f, 1f)

/** Перебег за порогом — на нём держится «подскок» иконки. */
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeToDismissBoxState.swipeOvershoot(widthPx: Float): Float {
    val threshold = NotificationSwipeStyle.DISMISS_THRESHOLD
    return ((swipeFraction(widthPx) - threshold) / (1f - threshold)).coerceIn(0f, 1f)
}

@Composable
private fun NotificationListItem(
    item: InAppNotification,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val timeLabel = remember(item.timestamp, zone) {
        timeFormatter.format(item.timestamp.atZone(zone))
    }
    val kind = remember(item.kind, item.title) { item.displayKind }
    val onTintedBackground = !item.read
    val textColors = rememberNotificationTextColors(
        kind = kind,
        read = item.read,
        onTintedBackground = onTintedBackground,
    )
    val titleText = remember(item.title, textColors) {
        buildInAppNotificationTitle(item, textColors)
    }
    val bodyText = remember(item.body, kind, textColors) {
        buildNotificationBody(item.body, kind, textColors)
    }

    val baseColor = if (item.read) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NotificationSwipeStyle.cornerRadius),
        color = if (LocalGlassEnabled.current) {
            baseColor.copy(alpha = NotificationSwipeStyle.CARD_GLASS_ALPHA)
        } else {
            baseColor
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Preview(showBackground = true, name = "All Notification Variants")
@Composable
private fun AllNotificationVariantsPreview() {
    val now = remember { 1716552000000L } // Fixed timestamp for 24.05.2024
    val dayStr = "24.05.2026"

    val notifications = listOf(
        InAppNotification(
            id = "new",
            title = "Новая запись: Анна",
            body = "$dayStr, 14:00–15:00 · Анна · Занятие",
            timestampEpochMillis = now,
            kind = SessionEventType.NEW_BOOKING.name,
        ),
        InAppNotification(
            id = "cancel",
            title = "Отмена: Борис",
            body = "$dayStr, 16:00–16:50 · Борис · Занятие",
            timestampEpochMillis = now - 60_000,
            kind = SessionEventType.CANCELLED.name,
        ),
        InAppNotification(
            id = "resched",
            title = "Перенос: Лев",
            body = "Было: $dayStr, 15:00–15:50 · Лев · Занятие\nСтало: $dayStr, 17:00–17:50 · Лев · Занятие",
            timestampEpochMillis = now - 120_000,
            kind = SessionEventType.RESCHEDULED.name,
        ),
        InAppNotification(
            id = "deleted",
            title = "Удалено: Мария",
            body = "Запись на $dayStr, 10:00–10:50 удалена из календаря",
            timestampEpochMillis = now - 180_000,
            kind = SessionEventType.DELETED.name,
        ),
        InAppNotification(
            id = "confirmed",
            title = "Подтвердил: Виктор",
            body = "Клиент подтвердил визит на $dayStr, 11:00",
            timestampEpochMillis = now - 240_000,
            kind = SessionEventType.CLIENT_CONFIRMED.name,
        ),
        InAppNotification(
            id = "arrived",
            title = "Пришёл: Елена",
            body = "Отметка «пришёл» для записи на $dayStr, 12:00",
            timestampEpochMillis = now - 300_000,
            kind = SessionEventType.CLIENT_ARRIVED.name,
        ),
        InAppNotification(
            id = "reminder",
            title = "Скоро: Дмитрий",
            body = "Занятие начнется через 30 минут ($dayStr, 18:00)",
            timestampEpochMillis = now - 360_000,
            kind = SessionEventType.REMINDER.name,
        ),
        InAppNotification(
            id = "today",
            title = "Сегодня 24 мая",
            body = "У вас 5 занятий на сегодня. Первое в 10:00.",
            timestampEpochMillis = now - 420_000,
            kind = SessionEventType.TODAY_DIGEST.name,
        ),
        InAppNotification(
            id = "tomorrow",
            title = "Завтра 25 мая",
            body = "На завтра запланировано 3 занятия.",
            timestampEpochMillis = now - 480_000,
            kind = SessionEventType.TOMORROW_DIGEST.name,
        ),
        InAppNotification(
            id = "archive",
            title = "Перенести в архив",
            body = "День 23.05.2026 завершен. Перенесите записи в архив.",
            timestampEpochMillis = now - 540_000,
            kind = SessionEventType.ARCHIVE_REMINDER.name,
        ),
        InAppNotification(
            id = "read",
            title = "Прочитанное: Иван (Пример)",
            body = "$dayStr, 09:00–09:50 · Иван · Занятие",
            timestampEpochMillis = now - 600_000,
            kind = SessionEventType.NEW_BOOKING.name,
            read = true,
        ),
    )

    NeiroTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            NotificationsContent(
                notifications = notifications,
                subtitleRes = R.string.in_app_notifications_subtitle_sync,
                allowDismiss = true,
                onDismiss = {},
                onNotificationClick = {},
                onDismissNotification = {},
                onClearAll = {},
            )
        }
    }
}

@Preview
@Composable
private fun NotificationsDialogPreview() {

    val now = remember { 1716552000000L }
    val day = LocalDate.of(2026, 5, 24)
    NeiroTheme {
        NotificationsContent(
            notifications = listOf(
                InAppNotification(
                    id = "1",
                    title = "Новая запись: Анна",
                    body = "24.05.2026, 14:00–15:00 · Анна · Занятие",
                    timestampEpochMillis = now,
                    relatedDateEpochDay = day.toEpochDay(),
                    kind = SessionEventType.NEW_BOOKING.name,
                ),
                InAppNotification(
                    id = "2",
                    title = "Отмена: Борис",
                    body = "24.05.2026, 16:00–16:50 · Борис · Занятие",
                    timestampEpochMillis = now - 60_000,
                    relatedDateEpochDay = day.toEpochDay(),
                    kind = SessionEventType.CANCELLED.name,
                ),
                InAppNotification(
                    id = "3",
                    title = "Перенос: Лев",
                    body = "Было: 24.05.2026, 15:00–15:50 · Лев · Занятие\nСтало: 24.05.2026, 17:00–17:50 · Лев · Занятие",
                    timestampEpochMillis = now - 120_000,
                    relatedDateEpochDay = day.toEpochDay(),
                    kind = SessionEventType.RESCHEDULED.name,
                ),
            ),
            subtitleRes = R.string.in_app_notifications_subtitle_sync,
            allowDismiss = true,
            onDismiss = {},
            onNotificationClick = {},
            onDismissNotification = {},
            onClearAll = {},
        )
    }
}
