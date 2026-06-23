package ru.greemlab.neiro.ui.components.daydetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.ui.calendar.AttendanceStatus
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.intensiveChildrenLabel
import ru.greemlab.neiro.ui.util.formatRubles

@Composable
fun IntensiveDetailsDialog(
    time: String,
    children: List<Session.IntensiveChild>,
    amount: Double,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        IntensiveDetailsCard(
            time = time,
            children = children,
            amount = amount,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun IntensiveDetailsCard(
    time: String,
    children: List<Session.IntensiveChild>,
    amount: Double,
    onDismiss: () -> Unit,
) {
    val timeLabel = formatIntensiveTimeLabel(time)
    val arrivedCount = children.count { it.status == AttendanceStatus.ARRIVED }
    val amountLabel = if (amount > 0.0) formatRubles(amount) else null

    Card(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .padding(vertical = 24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeLabel.ifBlank { "Интенсив" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Интенсив · ${intensiveChildrenLabel(children.size)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = buildString {
                    append("Пришли: $arrivedCount из ${children.size}")
                    amountLabel?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ScheduleHeaderGreen,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(children, key = { it.name }) { child ->
                    ScheduleSlotItem(
                        time = "",
                        name = child.name,
                        comment = child.comment,
                        status = child.status,
                        showTime = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    }
}

private fun formatIntensiveTimeLabel(time: String): String {
    if (time.isBlank()) return ""
    val normalized = normalizeSessionTime(time)
    val start = normalized.substringBefore("-").trim()
    val end = normalized.substringAfter("-", "").trim()
    return if (end.isNotEmpty()) "$start–$end" else start
}

@Preview(showBackground = true)
@Composable
private fun IntensiveDetailsCardPreview() {
    NeiroTheme {
        IntensiveDetailsCard(
            time = "18:00-19:30",
            amount = 8000.0,
            children = listOf(
                Session.IntensiveChild("Коновалов Ильдар 3,2г", AttendanceStatus.ARRIVED),
                Session.IntensiveChild("Караховская Мария 2,10л", AttendanceStatus.CONFIRMED),
                Session.IntensiveChild("Егорченкова Эмилия 2,1г", AttendanceStatus.EXPECTED),
                Session.IntensiveChild("Чижова Дарья", AttendanceStatus.CANCELLED),
            ),
            onDismiss = {},
        )
    }
}
