package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import ru.greemlab.neiro.notifications.InAppNotification
import ru.greemlab.neiro.notifications.SessionEventType
import ru.greemlab.neiro.theme.ExpectedAmber
import ru.greemlab.neiro.theme.RescheduleNotificationDark
import ru.greemlab.neiro.theme.RescheduleNotificationLight
import ru.greemlab.neiro.theme.ScheduleHeaderGreen
import ru.greemlab.neiro.theme.StatusRedBody

/**
 * Палитра акцентов для карточки in-app уведомления (согласована с [MaterialTheme]).
 */
@Stable
data class NotificationTextColors(
    val titlePrefix: Color,
    val titleDetail: Color,
    val bodyBase: Color,
    val bodyAccent: Color,
    val bodyMuted: Color,
    val bodyPositive: Color,
)

@Composable
fun rememberNotificationTextColors(
    kind: SessionEventType?,
    read: Boolean,
    onTintedBackground: Boolean,
): NotificationTextColors {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = isSystemInDarkTheme()
    val rescheduleAccent = if (darkTheme) RescheduleNotificationDark else RescheduleNotificationLight
    return remember(kind, read, onTintedBackground, darkTheme) {
        val titleDetail = if (onTintedBackground && !read) {
            scheme.onPrimaryContainer
        } else {
            scheme.onSurface
        }
        val bodyBase = if (onTintedBackground && !read) {
            scheme.onPrimaryContainer.copy(alpha = 0.88f)
        } else if (read) {
            scheme.onSurfaceVariant
        } else {
            scheme.onSurface.copy(alpha = 0.82f)
        }

        val prefix = when (kind) {
            SessionEventType.NEW_BOOKING,
            SessionEventType.REMINDER,
            SessionEventType.TODAY_DIGEST,
            SessionEventType.TOMORROW_DIGEST,
            -> scheme.primary

            SessionEventType.CANCELLED,
            SessionEventType.DELETED,
            -> StatusRedBody

            SessionEventType.RESCHEDULED -> rescheduleAccent
            SessionEventType.CLIENT_CONFIRMED -> ExpectedAmber
            SessionEventType.CLIENT_ARRIVED -> ScheduleHeaderGreen
            SessionEventType.ARCHIVE_REMINDER -> scheme.secondary
            null -> scheme.onSurfaceVariant
        }

        val accent = when (kind) {
            SessionEventType.CANCELLED,
            SessionEventType.DELETED,
            -> StatusRedBody

            SessionEventType.RESCHEDULED -> rescheduleAccent
            SessionEventType.CLIENT_CONFIRMED -> ExpectedAmber
            SessionEventType.CLIENT_ARRIVED -> ScheduleHeaderGreen
            SessionEventType.ARCHIVE_REMINDER -> scheme.secondary
            else -> scheme.primary
        }

        NotificationTextColors(
            titlePrefix = prefix,
            titleDetail = titleDetail,
            bodyBase = bodyBase,
            bodyAccent = accent,
            bodyMuted = bodyBase.copy(alpha = 0.65f),
            bodyPositive = accent,
        )
    }
}

fun buildNotificationTitle(title: String, colors: NotificationTextColors): AnnotatedString {
    val colonIndex = title.indexOf(':')
    if (colonIndex <= 0) {
        return AnnotatedString(title)
    }

    return buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = colors.titlePrefix,
                fontWeight = FontWeight.SemiBold,
            ),
        ) {
            append(title.substring(0, colonIndex + 1))
        }
        val detail = title.substring(colonIndex + 1).trimStart()
        if (detail.isNotEmpty()) {
            append(' ')
            withStyle(
                SpanStyle(
                    color = colors.titleDetail,
                    fontWeight = FontWeight.Medium,
                ),
            ) {
                append(detail)
            }
        }
    }
}

fun buildNotificationBody(
    body: String,
    kind: SessionEventType?,
    colors: NotificationTextColors,
): AnnotatedString {
    if (body.isBlank()) return AnnotatedString("")

    return if ((kind == SessionEventType.RESCHEDULED) && body.contains('\n')) {
        buildAnnotatedString {
            body.lines().forEachIndexed { index, line ->
                if (index > 0) append('\n')
                appendRescheduleLine(line, colors)
            }
        }
    } else {
        buildAnnotatedString {
            appendHighlightedSchedule(body, colors, emphasize = true)
        }
    }
}

fun buildInAppNotificationTitle(
    item: InAppNotification,
    colors: NotificationTextColors,
): AnnotatedString = buildNotificationTitle(item.title, colors)

private fun AnnotatedString.Builder.appendRescheduleLine(
    line: String,
    colors: NotificationTextColors,
) {
    when {
        line.startsWith(WAS_LABEL) -> {
            withStyle(SpanStyle(color = colors.bodyMuted, fontWeight = FontWeight.Medium)) {
                append(WAS_LABEL)
            }
            val rest = line.removePrefix(WAS_LABEL).trimStart()
            if (rest.isNotEmpty()) {
                append(' ')
                appendHighlightedSchedule(rest, colors, emphasize = false)
            }
        }

        line.startsWith(NOW_LABEL) -> {
            withStyle(SpanStyle(color = colors.bodyPositive, fontWeight = FontWeight.SemiBold)) {
                append(NOW_LABEL)
            }
            val rest = line.removePrefix(NOW_LABEL).trimStart()
            if (rest.isNotEmpty()) {
                append(' ')
                appendHighlightedSchedule(rest, colors, emphasize = true)
            }
        }

        else -> appendHighlightedSchedule(line, colors, emphasize = true)
    }
}

private fun AnnotatedString.Builder.appendHighlightedSchedule(
    text: String,
    colors: NotificationTextColors,
    emphasize: Boolean,
) {
    val accentStyle = SpanStyle(
        color = if (emphasize) colors.bodyAccent else colors.bodyMuted,
        fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
    )
    val spans = collectHighlightSpans(
        text,
        NUMERIC_DATE to accentStyle,
        TEXT_MONTH_DATE to accentStyle,
        TIME_RANGE to accentStyle,
    )
    appendWithSpans(text, colors.bodyBase, spans)
}

private fun collectHighlightSpans(
    text: String,
    vararg rules: Pair<Regex, SpanStyle>,
): List<StyledRange> =
    rules
        .asSequence()
        .flatMap { (regex, style) ->
            regex.findAll(text).map { match ->
                StyledRange(match.range.first, match.range.last + 1, style)
            }
        }
        .sortedWith(compareBy<StyledRange> { it.start }.thenByDescending { it.end - it.start })
        .fold(mutableListOf()) { merged, span ->
            if (merged.isEmpty() || span.start >= merged.last().end) {
                merged += span
            }
            merged
        }

private fun AnnotatedString.Builder.appendWithSpans(
    text: String,
    baseColor: Color,
    spans: List<StyledRange>,
) {
    withStyle(SpanStyle(color = baseColor)) {
        var position = 0
        for (span in spans) {
            if (span.start > position) {
                append(text.substring(position, span.start))
            }
            withStyle(span.style) {
                append(text.substring(span.start, span.end))
            }
            position = span.end
        }
        if (position < text.length) {
            append(text.substring(position))
        }
    }
}

private data class StyledRange(
    val start: Int,
    val end: Int,
    val style: SpanStyle,
)

private const val WAS_LABEL = "Было:"
private const val NOW_LABEL = "Стало:"

private val NUMERIC_DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")
private val TEXT_MONTH_DATE = Regex(
    """\d{1,2}\s+(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)""",
    RegexOption.IGNORE_CASE,
)
private val TIME_RANGE = Regex("""\d{1,2}:\d{2}[–-]\d{1,2}:\d{2}""")
