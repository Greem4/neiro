package ru.greemlab.neiro.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SplitGapExtraMax = 36.dp
private val RestSpacingBelowOverview = 10.dp

/**
 * Разрыв pull-to-refresh между блоком статистики и нижней карточкой календаря:
 * верх остаётся на месте, низ (вкладки + сетка) опускается, в зазоре — индикатор.
 */
@Composable
fun CalendarRefreshSplit(
    pullFraction: Float,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    topContent: @Composable () -> Unit,
    bottomContent: @Composable () -> Unit,
) {
    val targetFraction = when {
        isRefreshing -> 1f
        else -> pullFraction.coerceIn(0f, 1.2f)
    }
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "CalendarRefreshSplit",
    )

    val gapHeight = RestSpacingBelowOverview + lerpDp(0.dp, SplitGapExtraMax, animatedFraction.coerceIn(0f, 1f))
    val indicatorAlpha = ((animatedFraction - 0.15f) / 0.85f).coerceIn(0f, 1f)
    val indicatorScale = 0.55f + 0.45f * indicatorAlpha

    Column(modifier = modifier.fillMaxWidth()) {
        topContent()

        CalendarRefreshGap(
            height = gapHeight,
            indicatorAlpha = indicatorAlpha,
            indicatorScale = indicatorScale,
            isRefreshing = isRefreshing,
            pullProgress = animatedFraction.coerceIn(0f, 1f),
        )

        bottomContent()
    }
}

@Composable
private fun CalendarRefreshGap(
    height: Dp,
    indicatorAlpha: Float,
    indicatorScale: Float,
    isRefreshing: Boolean,
    pullProgress: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        if (indicatorAlpha > 0.02f) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            alpha = indicatorAlpha
                            scaleX = indicatorScale
                            scaleY = indicatorScale
                        },
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                )
            } else {
                CircularProgressIndicator(
                    progress = { pullProgress },
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            alpha = indicatorAlpha
                            scaleX = indicatorScale
                            scaleY = indicatorScale
                        },
                    strokeWidth = 2.5.dp,
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                )
            }
        }
    }
}

private fun lerpDp(start: Dp, end: Dp, fraction: Float): Dp {
    val f = fraction.coerceIn(0f, 1f)
    return start + (end - start) * f
}
