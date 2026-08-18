package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.SendAndArchive
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.ApplyDialogGlass
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.glassContainerColor

/** Вариант иконки для выбора (номер → подстановка в код). */
data class ArchiveIconOption(
    val number: Int,
    val title: String,
    val codeName: String,
    val icon: ImageVector,
)

val archiveIconOptions: List<ArchiveIconOption> = listOf(
    ArchiveIconOption(1, "Архив", "Icons.Rounded.Archive", Icons.Rounded.Archive),
    ArchiveIconOption(2, "Из архива", "Icons.Rounded.Unarchive", Icons.Rounded.Unarchive),
    ArchiveIconOption(3, "Коробка", "Icons.Rounded.Inventory2", Icons.Rounded.Inventory2),
    ArchiveIconOption(4, "Инвентарь", "Icons.Rounded.Inventory", Icons.Rounded.Inventory),
    ArchiveIconOption(5, "Папка-zip", "Icons.Rounded.FolderZip", Icons.Rounded.FolderZip),
    ArchiveIconOption(6, "Хранилище (сейчас)", "Icons.Rounded.Storage", Icons.Rounded.Storage),
    ArchiveIconOption(7, "Входящие", "Icons.Rounded.Inbox", Icons.Rounded.Inbox),
    ArchiveIconOption(8, "История", "Icons.Rounded.History", Icons.Rounded.History),
    ArchiveIconOption(9, "Закладки в папке", "Icons.Rounded.CollectionsBookmark", Icons.Rounded.CollectionsBookmark),
    ArchiveIconOption(10, "Закладки", "Icons.Rounded.Bookmarks", Icons.Rounded.Bookmarks),
    ArchiveIconOption(11, "Особая папка", "Icons.Rounded.FolderSpecial", Icons.Rounded.FolderSpecial),
    ArchiveIconOption(12, "Склад", "Icons.Rounded.Warehouse", Icons.Rounded.Warehouse),
    ArchiveIconOption(13, "Архив контур", "Icons.Outlined.Archive", Icons.Outlined.Archive),
    ArchiveIconOption(14, "Архив заливка", "Icons.Filled.Archive", Icons.Filled.Archive),
    ArchiveIconOption(15, "Отправить в архив", "Icons.AutoMirrored.Rounded.SendAndArchive", Icons.AutoMirrored.Rounded.SendAndArchive),
)

@Composable
fun ArchiveIconPickerDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassContainerColor(),
        title = { Text("Иконки архива") },
        text = {
            ArchiveIconPickerContent(modifier = Modifier.height(420.dp))
        },
        confirmButton = {
            // Размытие за окном включается изнутри диалога — только здесь
            // composable сидит в его собственном окне.
            ApplyDialogGlass()
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

@Composable
fun ArchiveIconPickerContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Напиши в чат номер (1–${archiveIconOptions.size}) — подставлю везде.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        archiveIconOptions.forEach { option ->
            ArchiveIconOptionRow(option = option)
        }
    }
}

@Composable
private fun ArchiveIconOptionRow(option: ArchiveIconOption) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.number.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = option.codeName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ArchiveUsagePreview(icon = option.icon, usage = ArchiveIconUsage.TAB)
                ArchiveUsagePreview(icon = option.icon, usage = ArchiveIconUsage.BUTTON)
            }
        }
    }
}

private enum class ArchiveIconUsage { TAB, BUTTON }

/** Как вкладка «Архив» в [CalendarToolbar] (16 dp) или кнопка «В архив» в дне (20 dp). */
@Composable
private fun ArchiveUsagePreview(
    icon: ImageVector,
    usage: ArchiveIconUsage,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (usage) {
            ArchiveIconUsage.TAB -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Архив",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
                Text(
                    text = "вкладка",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ArchiveIconUsage.BUTTON -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "В архив",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = "кнопка",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Иконки архива — светлая", widthDp = 400, heightDp = 900)
@Composable
private fun ArchiveIconPickerLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ArchiveIconPickerContent(modifier = Modifier.padding(16.dp))
        }
    }
}

@Preview(showBackground = true, name = "Иконки архива — тёмная", widthDp = 400, heightDp = 900)
@Composable
private fun ArchiveIconPickerDarkPreview() {
    NeiroTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ArchiveIconPickerContent(modifier = Modifier.padding(16.dp))
        }
    }
}
