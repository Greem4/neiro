package ru.greemlab.neiro.ui.util

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Ширина полосы прокрутки и запас под неё у правого края содержимого. */
val ScrollIndicatorWidth: Dp = 3.dp
val ScrollIndicatorGutter: Dp = 8.dp

private val ScrollIndicatorMinThumb: Dp = 24.dp

/**
 * Тонкая полоса прокрутки у правого края — видно, что список длиннее окна и
 * насколько он прокручен. Рисуется, только когда прокручивать есть что.
 *
 * Ставится **до** `verticalScroll` и до [fadingScrollEdges]: тогда размер — это
 * видимая область, а сама полоса не растворяется вместе с краями списка.
 * Содержимому нужен отступ справа [ScrollIndicatorGutter], чтобы полоса не
 * ложилась на карточки.
 */
fun Modifier.verticalScrollIndicator(
    scrollState: ScrollState,
    color: Color,
    width: Dp = ScrollIndicatorWidth,
): Modifier = drawWithContent {
    drawContent()
    val max = scrollState.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    val viewport = size.height
    val content = viewport + max
    val thumb = (viewport * viewport / content).coerceAtLeast(ScrollIndicatorMinThumb.toPx())
        .coerceAtMost(viewport)
    val top = (viewport - thumb) * (scrollState.value.toFloat() / max)
    val barWidth = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - barWidth, top),
        size = Size(barWidth, thumb),
        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
    )
}
