package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.theme.glassDividerColor
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.calendar.DayKindStats
import ru.greemlab.neiro.ui.calendar.DaySummaryStats
import ru.greemlab.neiro.ui.util.formatDayMonth
import java.time.LocalDate

/**
 * Диалоги плиток дня: «занятий», «проведено», «Заработано», «Ожидается».
 *
 * Плитка показывает, что в дне есть сейчас, а разбор — здесь. Порядок разбора
 * один на все четыре: сверху общая цифра за весь день, под ней конкретика по
 * видам — занятия, интенсивы, диагностики. Общая цифра считает и отменённое:
 * разбор отвечает на вопрос «сколько должно было быть», а не «сколько
 * осталось» — это плитка и так говорит.
 *
 * Строка вида появляется, только если вид в дне был: день без диагностик не
 * должен показывать «Диагностик 0».
 *
 * Занятие — это ученик или диагностика. Интенсив занятием не считается и в
 * счётчики занятий не входит: у него свой блок в разборе. Поэтому день с
 * одним интенсивом показывает «занятий 0», и это не ошибка.
 */

/** Отступ вложенных строк — тот же, что в разборе занятий за месяц. */
private val NestedRowIndent = 12.dp

@Composable
private fun BreakdownColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NestedRowIndent),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** Пояснение под цифрами: почему сумма именно такая. */
@Composable
private fun DialogNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Разбор занятий за день: сколько должно было быть и что из этого прошло. */
@Composable
fun DayLessonsDialog(
    date: LocalDate,
    stats: DaySummaryStats,
    onDismiss: () -> Unit,
) {
    val title = remember(date) { "Занятия за ${formatDayMonth(date)}" }
    val semanticColors = neiroSemanticColors

    StatsDialogScaffold(title = title, onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LessonStatRow(
                label = "Всего занятий",
                value = stats.scheduledLessons,
                color = MaterialTheme.colorScheme.onSurface,
                isBold = true,
                icon = Icons.Rounded.Event,
                iconTint = MaterialTheme.colorScheme.primary,
            )

            // Разбивка нужна, только когда диагностики есть: иначе она слово
            // в слово повторяет строку выше.
            if (!stats.diagnostics.isEmpty) {
                BreakdownColumn {
                    LessonStatRow(
                        label = "Занятий",
                        value = stats.lessons.scheduled,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LessonStatRow(
                        label = "Диагностик",
                        value = stats.diagnostics.scheduled,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(color = glassDividerColor())

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LessonStatRow(
                label = "Проведено",
                value = stats.attendedLessons,
                color = semanticColors.scheduleHeader,
                isBold = true,
                icon = Icons.Rounded.CheckCircle,
                iconTint = semanticColors.scheduleHeader,
            )
            BreakdownColumn {
                if (stats.confirmedLessons > 0) {
                    LessonStatRow(
                        label = "Подтверждено",
                        value = stats.confirmedLessons,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stats.pendingLessons > 0) {
                    LessonStatRow(
                        label = "Ждут подтверждения",
                        value = stats.pendingLessons,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stats.cancelledLessons > 0) {
                    LessonStatRow(
                        label = "Отменено",
                        value = stats.cancelledLessons,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (stats.intensiveCount > 0) {
            HorizontalDivider(color = glassDividerColor())
            IntensiveBlock(stats)
        }

        if (stats.scheduledLessons == 0 && stats.intensiveCount == 0) {
            DialogNote("На этот день ничего не записано.")
        }
    }
}

/**
 * Интенсив в разборе дня — своим блоком.
 *
 * Занятием он не считается, поэтому и не смешивается со счётчиками выше:
 * день может показывать «занятий 0» и при этом честно рассказать здесь, что
 * интенсив был и сколько детей на него пришло.
 */
@Composable
private fun IntensiveBlock(stats: DaySummaryStats) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LessonStatRow(
            label = "Интенсивов",
            value = stats.intensiveCount,
            color = MaterialTheme.colorScheme.onSurface,
            isBold = true,
            icon = Icons.Rounded.Groups,
            iconTint = MaterialTheme.colorScheme.tertiary,
        )
        BreakdownColumn {
            LessonStatRow(
                label = "Детей всего",
                value = stats.intensives.scheduled,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.intensives.attended > 0) {
                LessonStatRow(
                    label = "Пришли",
                    value = stats.intensives.attended,
                    color = neiroSemanticColors.scheduleHeader,
                )
            }
            if (stats.intensives.confirmed > 0) {
                LessonStatRow(
                    label = "Подтвердились",
                    value = stats.intensives.confirmed,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.intensives.pending > 0) {
                LessonStatRow(
                    label = "Ждут подтверждения",
                    value = stats.intensives.pending,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.intensives.cancelled > 0) {
                LessonStatRow(
                    label = "Отменено",
                    value = stats.intensives.cancelled,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Разбор проведённого за день: что уже прошло, а что впереди. */
@Composable
fun DayConductedDialog(
    date: LocalDate,
    stats: DaySummaryStats,
    onDismiss: () -> Unit,
) {
    val title = remember(date) { "Проведено ${formatDayMonth(date)}" }
    val semanticColors = neiroSemanticColors
    val remaining = (stats.totalLessons - stats.attendedLessons).coerceAtLeast(0)

    StatsDialogScaffold(title = title, onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LessonStatRow(
                label = "Всего занятий",
                value = stats.scheduledLessons,
                color = MaterialTheme.colorScheme.onSurface,
                isBold = true,
                icon = Icons.Rounded.Event,
                iconTint = MaterialTheme.colorScheme.primary,
            )
            LessonStatRow(
                label = "Проведено",
                value = stats.attendedLessons,
                color = semanticColors.scheduleHeader,
                isBold = true,
                icon = Icons.Rounded.CheckCircle,
                iconTint = semanticColors.scheduleHeader,
            )
            if (!stats.diagnostics.isEmpty) {
                BreakdownColumn {
                    KindProgressRow("Занятия", stats.lessons)
                    KindProgressRow("Диагностики", stats.diagnostics)
                }
            }
        }

        HorizontalDivider(color = glassDividerColor())

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LessonStatRow(
                label = "Осталось",
                value = remaining,
                color = if (remaining > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                icon = Icons.Rounded.Schedule,
                iconTint = semanticColors.expected,
            )
            BreakdownColumn {
                if (stats.pendingLessons > 0) {
                    LessonStatRow(
                        label = "Из них ждут подтверждения",
                        value = stats.pendingLessons,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stats.cancelledLessons > 0) {
                    LessonStatRow(
                        label = "Отменено",
                        value = stats.cancelledLessons,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (stats.intensiveCount > 0) {
            HorizontalDivider(color = glassDividerColor())
            IntensiveBlock(stats)
        }

        if (stats.totalLessons > 0 && remaining == 0) {
            DialogNote("День закрыт — всё запланированное проведено.")
        }
    }
}

/** «Проведено X из N» одной строкой — вид дня в разборе. */
@Composable
private fun KindProgressRow(label: String, kind: DayKindStats) {
    StatRow(
        label = label,
        value = "${kind.attended} из ${kind.scheduled}",
        isHighlight = kind.scheduled > 0 && kind.attended == kind.scheduled,
    )
}

/** Разбор заработанного за день: потолок дня, а под ним — что уже пришло. */
@Composable
fun DayEarnedDialog(
    date: LocalDate,
    stats: DaySummaryStats,
    onDismiss: () -> Unit,
) {
    val title = remember(date) { "Заработано ${formatDayMonth(date)}" }
    val semanticColors = neiroSemanticColors
    val breakdownSum = remember(stats) {
        stats.lessons.earned + stats.intensives.earned + stats.diagnostics.earned
    }
    // Факт из YClients приходит одной суммой за день. Разложить его по видам
    // нечем — разбивка ниже считается по ставкам профиля, и при расхождении
    // об этом надо сказать прямо, а не подгонять цифры друг под друга.
    val factDiffers = stats.earnedFromFact && kotlin.math.abs(breakdownSum - stats.earned) >= 1.0

    StatsDialogScaffold(title = title, onDismiss = onDismiss) {
        ProfitRow(
            label = "Можно было заработать",
            value = stats.potentialEarned,
            color = MaterialTheme.colorScheme.onSurface,
            isBold = true,
            icon = Icons.Rounded.Summarize,
            iconTint = MaterialTheme.colorScheme.primary,
        )

        HorizontalDivider(color = glassDividerColor())

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfitRow(
                label = "Заработано",
                value = stats.earned,
                color = semanticColors.scheduleHeader,
                isBold = true,
                icon = Icons.Rounded.Payments,
                iconTint = semanticColors.scheduleHeader,
            )
            BreakdownColumn {
                if (stats.lessons.earned > 0.0) {
                    ProfitRow(
                        label = "Занятия",
                        value = stats.lessons.earned,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
                if (stats.intensives.earned > 0.0) {
                    ProfitRow(
                        label = "Интенсив",
                        value = stats.intensives.earned,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
                if (stats.diagnostics.earned > 0.0) {
                    ProfitRow(
                        label = "Диагностика",
                        value = stats.diagnostics.earned,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
            }
        }

        if (stats.expected > 0.0 || stats.cancelledAmount > 0.0) {
            HorizontalDivider(color = glassDividerColor())
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (stats.expected > 0.0) {
                    ProfitRow(
                        label = "Ещё ожидается",
                        value = stats.expected,
                        color = semanticColors.expected,
                        icon = Icons.Rounded.Schedule,
                        iconTint = semanticColors.expected,
                    )
                }
                if (stats.cancelledAmount > 0.0) {
                    ProfitRow(
                        label = "Потеряно на отменах",
                        value = stats.cancelledAmount,
                        color = MaterialTheme.colorScheme.error,
                        prefix = "−",
                        icon = Icons.Rounded.EventBusy,
                        iconTint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        when {
            factDiffers -> DialogNote(
                "Сумма дня пришла из YClients, а разбивка посчитана по ставкам " +
                    "профиля — поэтому строки могут не сойтись с итогом.",
            )

            stats.earnedFromFact -> DialogNote("Сумма дня пришла из YClients.")

            stats.earned <= 0.0 -> DialogNote("За этот день пока ничего не начислено.")
        }
    }
}

/** Разбор ожидаемого за день: что ещё должно прийти и что уже не придёт. */
@Composable
fun DayExpectedDialog(
    date: LocalDate,
    stats: DaySummaryStats,
    onDismiss: () -> Unit,
) {
    val title = remember(date) { "Ожидается ${formatDayMonth(date)}" }
    val semanticColors = neiroSemanticColors

    StatsDialogScaffold(title = title, onDismiss = onDismiss) {
        ProfitRow(
            label = "Можно было заработать",
            value = stats.potentialEarned,
            color = MaterialTheme.colorScheme.onSurface,
            isBold = true,
            icon = Icons.Rounded.Summarize,
            iconTint = MaterialTheme.colorScheme.primary,
        )

        HorizontalDivider(color = glassDividerColor())

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfitRow(
                label = "Ожидается",
                value = stats.expected,
                color = if (stats.expected > 0.0) {
                    semanticColors.expected
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                isBold = true,
                icon = Icons.Rounded.Schedule,
                iconTint = semanticColors.expected,
            )
            BreakdownColumn {
                if (stats.lessons.expected > 0.0) {
                    ProfitRow(
                        label = "Занятия",
                        value = stats.lessons.expected,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
                if (stats.intensives.expected > 0.0) {
                    ProfitRow(
                        label = "Интенсив",
                        value = stats.intensives.expected,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
                if (stats.diagnostics.expected > 0.0) {
                    ProfitRow(
                        label = "Диагностика",
                        value = stats.diagnostics.expected,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                }
            }
        }

        HorizontalDivider(color = glassDividerColor())

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfitRow(
                label = "Уже заработано",
                value = stats.earned,
                color = semanticColors.scheduleHeader,
                icon = Icons.Rounded.Payments,
                iconTint = semanticColors.scheduleHeader,
            )
            // Отменённое не попадает ни в заработок, ни в ожидание — иначе день
            // всё утро висел бы с суммой, которая уже никогда не придёт.
            if (stats.cancelledAmount > 0.0) {
                ProfitRow(
                    label = "Потеряно на отменах",
                    value = stats.cancelledAmount,
                    color = MaterialTheme.colorScheme.error,
                    prefix = "−",
                    icon = Icons.Rounded.EventBusy,
                    iconTint = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (stats.expected <= 0.0) {
            DialogNote(
                if (stats.scheduledLessons > 0 || stats.intensiveCount > 0) {
                    "Ждать больше нечего — всё запланированное уже проведено."
                } else {
                    "На этот день ничего не записано."
                },
            )
        }
    }
}
