package ru.greemlab.neiro.ui.util

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Запас растворения у краёв прокручиваемого блока внутри диалога. */
val DialogEdgeFade: Dp = 16.dp

/**
 * Заливка под шапкой или под кнопками, лежащими поверх ленты: плотная у края
 * панели и сходящая на нет к содержимому. В паре с [fadingEdges] граница
 * перестаёт читаться как обрез.
 */
fun panelScrim(color: Color, fromTop: Boolean): Brush = Brush.verticalGradient(
    if (fromTop) {
        listOf(color, color, Color.Transparent)
    } else {
        listOf(Color.Transparent, color, color)
    },
)

/**
 * Растворение содержимого у верхнего и нижнего края.
 *
 * Нужно там, где список уезжает под шапку или под кнопки: без этого строка
 * режется ровно пополам и выглядит как поломка, а с ним — уходит в прозрачность,
 * как в iOS и Telegram.
 *
 * Маска рисуется в offscreen-слое ([CompositingStrategy.Offscreen]) — без него
 * `BlendMode.DstIn` съел бы всё, что нарисовано под этим элементом.
 */
fun Modifier.fadingEdges(top: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val topPx = top.toPx()
        val bottomPx = bottom.toPx()
        if (topPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = topPx,
                ),
                size = Size(size.width, topPx),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottomPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - bottomPx,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - bottomPx),
                size = Size(size.width, bottomPx),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Растворение краёв прокручиваемой области — только с той стороны, где
 * действительно спрятан контент.
 *
 * Безусловное растворение приглушало первую и последнюю строку даже там, где
 * список помещается целиком и не двигается: текст выглядел выцветшим без
 * причины, а цифры — недорисованными.
 */
@Composable
fun Modifier.fadingScrollEdges(
    scrollState: ScrollState,
    top: Dp = DialogEdgeFade,
    bottom: Dp = DialogEdgeFade,
): Modifier = fadingEdges(
    top = if (scrollState.canScrollBackward) top else 0.dp,
    bottom = if (scrollState.canScrollForward) bottom else 0.dp,
)
