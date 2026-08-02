package ru.greemlab.neiro.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Размер шрифта, который растёт вместе с системным, но не более чем в [maxScale] раза.
 *
 * Нужен только там, где место под текст жёстко ограничено геометрией — подписи под
 * столбцами графика, числа в клетках календаря. При системном шрифте 150–200%
 * такие подписи иначе наезжают друг на друга. Обычный текст экранов ограничивать
 * нельзя: пользователь увеличил шрифт, чтобы его читать.
 *
 * [size] задаётся в dp — это физический размер при системном шрифте 100%.
 */
@Composable
fun cappedSp(size: Dp, maxScale: Float = 1.15f): TextUnit {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= maxScale) return size.value.sp
    return (size.value * maxScale / fontScale).sp
}
