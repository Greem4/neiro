package ru.greemlab.neiro.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.LocalGlassEnabled
import ru.greemlab.neiro.theme.LocalGlassPanelAbove
import ru.greemlab.neiro.theme.OnYClientsYellow
import ru.greemlab.neiro.theme.YClientsYellow
import ru.greemlab.neiro.theme.glassControlColor
import ru.greemlab.neiro.ui.calendar.CalendarMode

private val ToolbarHeight = 40.dp

/**
 * Как гаснет фирменная плашка, когда сверху встаёт стеклянная панель.
 *
 * Задержка — потому что окно диалога со стеклом появляется на кадр-другой позже
 * смены состояния: без неё плашка успевала посереть на глазах, и только потом
 * её накрывало стекло. За [GlassMuteDelayMillis] панель уже стоит, и переход
 * доживает под ней.
 */
private const val GlassMuteDelayMillis = 150
private const val GlassMuteFadeMillis = 220

/**
 * Панель над сеткой: переключатель источника данных (YClients / архив).
 *
 * @param archiveBadgeCount Число забытых вне архива прошлых дней — бейдж на вкладке «Архив».
 */
@Composable
fun CalendarToolbar(
    calendarMode: CalendarMode,
    onModeChange: (CalendarMode) -> Unit,
    modifier: Modifier = Modifier,
    archiveBadgeCount: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ToolbarHeight)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarSourceSwitcher(
            calendarMode = calendarMode,
            onModeChange = onModeChange,
            archiveBadgeCount = archiveBadgeCount,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CalendarSourceSwitcher(
    calendarMode: CalendarMode,
    onModeChange: (CalendarMode) -> Unit,
    archiveBadgeCount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = ToolbarHeight),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceTab(
                label = "YClients",
                icon = Icons.Rounded.CloudSync,
                selected = calendarMode == CalendarMode.SYNCED,
                brandSelected = true,
                onClick = { onModeChange(CalendarMode.SYNCED) },
                modifier = Modifier.weight(1f),
            )
            SourceTab(
                label = "Архив",
                icon = Icons.Rounded.Storage,
                selected = calendarMode == CalendarMode.PERSONAL,
                brandSelected = false,
                badgeCount = archiveBadgeCount,
                onClick = { onModeChange(CalendarMode.PERSONAL) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SourceTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    brandSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
) {
    // Жёлтый — фирменный цвет YClients, и на самом экране он остаётся при любом
    // оформлении. Гаснет он только пока сверху стоит стеклянный диалог: сквозь
    // размытие плашка светится жёлтым пятном и тянет взгляд с цифр в диалоге.
    // На это время вкладка берёт тот же серый тон, что и элементы поверх ленты.
    val mutedByGlassPanel = LocalGlassEnabled.current && LocalGlassPanelAbove.current
    val bg: Color
    val contentColor: Color
    when {
        selected && brandSelected && mutedByGlassPanel -> {
            bg = glassControlColor()
            contentColor = MaterialTheme.colorScheme.onSurface
        }
        selected && brandSelected -> {
            bg = YClientsYellow
            contentColor = OnYClientsYellow
        }
        selected -> {
            bg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
            contentColor = MaterialTheme.colorScheme.primary
        }
        else -> {
            bg = Color.Transparent
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    // Плавно — только уход в серый под панель. Обратно жёлтый возвращается
    // мгновенно: к этому кадру панели над экраном уже нет. Переключение вкладок
    // тоже осталось мгновенным, как было.
    val fadeSpec: AnimationSpec<Color> = if (mutedByGlassPanel) {
        tween(durationMillis = GlassMuteFadeMillis, delayMillis = GlassMuteDelayMillis)
    } else {
        snap()
    }
    val animatedBg by animateColorAsState(
        targetValue = bg,
        animationSpec = fadeSpec,
        label = "sourceTabBackground",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = fadeSpec,
        label = "sourceTabContent",
    )

    Surface(
        modifier = modifier
            .heightIn(min = ToolbarHeight - 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = animatedBg,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = animatedContentColor,
            )
            // Вкладок ровно две и они делят ширину поровну: при крупном шрифте
            // подпись ужимается по кеглю, но не обрезается и не выдавливает бейдж.
            AutoShrinkText(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = animatedContentColor,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = 5.dp),
            )
            if (badgeCount > 0) {
                TabBadge(
                    count = badgeCount,
                    modifier = Modifier.padding(start = 5.dp),
                )
            }
        }
    }
}

/** Пилюля-счётчик на вкладке: «сколько прошлых дней ждёт переноса в архив». */
@Composable
private fun TabBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.error,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onError,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
