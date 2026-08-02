package ru.greemlab.neiro.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.OnYClientsYellow
import ru.greemlab.neiro.theme.YClientsYellow
import ru.greemlab.neiro.ui.calendar.CalendarMode

private val ToolbarHeight = 40.dp

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
    val bg: Color
    val contentColor: Color
    when {
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

    Surface(
        modifier = modifier
            .heightIn(min = ToolbarHeight - 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bg,
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
                tint = contentColor,
            )
            // Вкладок ровно две и они делят ширину поровну: при крупном шрифте
            // подпись ужимается по кеглю, но не обрезается и не выдавливает бейдж.
            AutoShrinkText(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = contentColor,
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
