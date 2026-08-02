package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import ru.greemlab.neiro.ui.util.formatRubles

@Composable
fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f),
        ),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.15f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = color,
                    )
                }
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Строка «подпись слева — значение справа», которая не ломается при увеличенном
 * системном шрифте: пока пара помещается по ширине, это обычный ряд со SpaceBetween;
 * как только перестаёт помещаться — значение переезжает на строку ниже и
 * прижимается к правому краю, а подпись занимает всю ширину.
 */
@Composable
fun LabelValueRow(
    modifier: Modifier = Modifier,
    horizontalGap: Dp = 12.dp,
    verticalGap: Dp = 2.dp,
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(label, value),
        modifier = modifier,
    ) { (labelMeasurables, valueMeasurables), constraints ->
        val labelMeasurable = labelMeasurables.first()
        val valueMeasurable = valueMeasurables.first()
        val horizontalGapPx = horizontalGap.roundToPx()
        val content = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)

        // Натуральная ширина обеих частей «в одну строку» — по ней и решаем раскладку.
        val labelWidth = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val fitsOneLine = !constraints.hasBoundedWidth ||
            labelWidth + horizontalGapPx + valueWidth <= constraints.maxWidth

        if (fitsOneLine) {
            val valuePlaceable = valueMeasurable.measure(content)
            val labelConstraints = if (constraints.hasBoundedWidth) {
                content.copy(
                    maxWidth = (constraints.maxWidth - valuePlaceable.width - horizontalGapPx)
                        .coerceAtLeast(0),
                )
            } else {
                content
            }
            val labelPlaceable = labelMeasurable.measure(labelConstraints)
            val width = if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                labelPlaceable.width + horizontalGapPx + valuePlaceable.width
            }
            val height = maxOf(labelPlaceable.height, valuePlaceable.height)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.placeRelative(
                    x = width - valuePlaceable.width,
                    y = (height - valuePlaceable.height) / 2,
                )
            }
        } else {
            val labelPlaceable = labelMeasurable.measure(content)
            val valuePlaceable = valueMeasurable.measure(content)
            val width = constraints.maxWidth
            val verticalGapPx = verticalGap.roundToPx()
            layout(width, labelPlaceable.height + verticalGapPx + valuePlaceable.height) {
                labelPlaceable.placeRelative(0, 0)
                valuePlaceable.placeRelative(
                    x = width - valuePlaceable.width,
                    y = labelPlaceable.height + verticalGapPx,
                )
            }
        }
    }
}

/**
 * Текст в одну строку, который при нехватке ширины уменьшает кегль, а не
 * обрезается многоточием: сумма или подпись вкладки должны читаться целиком
 * и при крупном системном шрифте.
 *
 * Размер подбирается за один проход измерением, без «прыжков» при рекомпозиции.
 */
@Composable
fun AutoShrinkText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.65f,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val available = constraints.maxWidth
        val fittedStyle = remember(text, style, available) {
            val natural = measurer.measure(
                text = AnnotatedString(text),
                style = style,
                softWrap = false,
            ).size.width
            if (natural == 0 || natural <= available) {
                style
            } else {
                val scale = (available.toFloat() / natural).coerceIn(minScale, 1f)
                style.copy(
                    fontSize = if (style.fontSize.isSpecified) style.fontSize * scale else style.fontSize,
                    lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
                )
            }
        }
        Text(
            text = text,
            style = fittedStyle,
            color = color,
            maxLines = 1,
            softWrap = false,
            textAlign = textAlign,
            // Выравнивание имеет смысл только когда текст занимает всю отведённую
            // ширину, иначе Box сжимается по содержимому и выравнивать нечего.
            modifier = if (textAlign != null) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

@Composable
fun LessonStatRow(
    label: String,
    value: Int,
    color: Color,
    isBold: Boolean = false,
) {
    LabelValueRow(
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        value = {
            Text(
                text = value.toString(),
                style = if (isBold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isBold) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = color,
            )
        },
    )
}

@Composable
fun StatRow(label: String, value: String, isHighlight: Boolean = false) {
    LabelValueRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        value = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (isHighlight) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
    )
}

@Composable
fun ProfitRow(
    label: String,
    value: Double,
    color: Color,
    modifier: Modifier = Modifier,
    isBold: Boolean = false,
    compact: Boolean = false,
    approximate: Boolean = false,
    prefix: String = "",
) {
    val formattedValue = remember(value, prefix, approximate) {
        val amount = "$prefix${formatRubles(value)}"
        if (approximate) "~$amount" else amount
    }
    val labelStyle = when {
        compact -> MaterialTheme.typography.bodySmall
        else -> MaterialTheme.typography.bodyMedium
    }
    val valueStyle = when {
        isBold && !compact -> MaterialTheme.typography.titleMedium
        compact -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    val valueWeight = when {
        isBold -> FontWeight.ExtraBold
        compact -> FontWeight.Medium
        else -> FontWeight.SemiBold
    }
    LabelValueRow(
        modifier = modifier.fillMaxWidth(),
        horizontalGap = if (compact) 8.dp else 12.dp,
        label = {
            Text(
                text = label,
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        value = {
            Text(
                text = formattedValue,
                style = valueStyle,
                fontWeight = valueWeight,
                color = color,
            )
        },
    )
}
