package ru.greemlab.neiro.theme

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Стеклянный вид поверхностей — тумблер в «Настройки → Внешний вид».
 *
 * Выключен по умолчанию: при `false` всё рисуется ровно как раньше, обычным
 * [Surface] с непрозрачным цветом схемы. Настройка живёт в
 * `AppearancePreferences`, сюда её кладёт `NeiroApp`.
 */
val LocalGlassEnabled = staticCompositionLocalOf { false }

/**
 * ПАРАМЕТРЫ СТЕКЛА (Твикай здесь)
 *
 * Прозрачность подобрана под два случая: с размытием за окном (Android 12+)
 * панель может быть заметно прозрачнее, без него — приходится держать её
 * плотнее, иначе календарь под диалогом перебивает текст.
 */
object GlassStyle {
    val panelCorner: Dp = 24.dp
    val blurRadius: Dp = 36.dp

    /** Затемнение за диалогом: со стеклом оно мягче штатного. */
    const val SCRIM_ALPHA = 0.28f

    const val DARK_TOP_ALPHA = 0.72f
    const val DARK_BOTTOM_ALPHA = 0.56f
    const val LIGHT_TOP_ALPHA = 0.86f
    const val LIGHT_BOTTOM_ALPHA = 0.74f

    /** Прибавка непрозрачности там, где размытия нет, — ради читаемости текста. */
    const val NO_BLUR_EXTRA_ALPHA = 0.16f

    const val BORDER_TOP_ALPHA = 0.34f
    const val BORDER_BOTTOM_ALPHA = 0.06f
    val borderWidth: Dp = 1.dp
}

/**
 * Панель диалога. Со стеклом — полупрозрачное матовое стекло с бликом по краю,
 * без стекла — обычный [Surface] цвета `surface`.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassStyle.panelCorner),
    content: @Composable () -> Unit,
) {
    if (!LocalGlassEnabled.current) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            content = content,
        )
        return
    }

    ApplyDialogGlass()
    Box(
        modifier = modifier
            .clip(shape)
            .background(glassPanelBrush())
            .glassBorder(shape),
    ) {
        content()
    }
}

/** Цвет контейнера для готовых диалогов Material (`AlertDialog` и подобные). */
@Composable
fun glassContainerColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) return scheme.surface
    val dark = scheme.surface.luminance() < 0.5f
    val base = if (dark) scheme.surfaceVariant else scheme.surface
    val alpha = if (dark) GlassStyle.DARK_TOP_ALPHA else GlassStyle.LIGHT_TOP_ALPHA
    return base.copy(alpha = alpha + extraAlphaWithoutBlur())
}

/** Блик по краю панели: сверху ярче, снизу почти незаметен. */
@Composable
fun Modifier.glassBorder(shape: Shape): Modifier {
    if (!LocalGlassEnabled.current) return this
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val edge = if (dark) Color.White else scheme.onSurface
    return border(
        width = GlassStyle.borderWidth,
        brush = Brush.verticalGradient(
            listOf(
                edge.copy(alpha = GlassStyle.BORDER_TOP_ALPHA),
                edge.copy(alpha = GlassStyle.BORDER_BOTTOM_ALPHA),
            ),
        ),
        shape = shape,
    )
}

/**
 * Размытие календаря за окном диалога и мягкий скрим.
 * Размытие есть только на Android 12+ и только если система его не отключила
 * (энергосбережение, слабое устройство) — иначе остаётся полупрозрачность.
 */
@Composable
fun ApplyDialogGlass(radius: Dp = GlassStyle.blurRadius) {
    val view = LocalView.current
    val density = LocalDensity.current
    val enabled = LocalGlassEnabled.current

    LaunchedEffect(view, enabled, radius) {
        if (!enabled) return@LaunchedEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setDimAmount(GlassStyle.SCRIM_ALPHA)
        if (!isBlurSupported(view.context)) return@LaunchedEffect
        window.applyBlurBehind(with(density) { radius.roundToPx() })
    }
}

@Composable
private fun glassPanelBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val base = if (dark) scheme.surfaceVariant else scheme.surface
    val extra = extraAlphaWithoutBlur()
    val top = if (dark) GlassStyle.DARK_TOP_ALPHA else GlassStyle.LIGHT_TOP_ALPHA
    val bottom = if (dark) GlassStyle.DARK_BOTTOM_ALPHA else GlassStyle.LIGHT_BOTTOM_ALPHA
    return Brush.verticalGradient(
        listOf(
            base.copy(alpha = (top + extra).coerceAtMost(1f)),
            base.copy(alpha = (bottom + extra).coerceAtMost(1f)),
        ),
    )
}

@Composable
private fun extraAlphaWithoutBlur(): Float {
    val context = LocalContext.current
    val supported = remember(context) { isBlurSupported(context) }
    return if (supported) 0f else GlassStyle.NO_BLUR_EXTRA_ALPHA
}

private fun isBlurSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
    return windowManager.isCrossWindowBlurEnabled
}

@RequiresApi(Build.VERSION_CODES.S)
private fun Window.applyBlurBehind(radiusPx: Int) {
    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    attributes = attributes.also { params -> params.blurBehindRadius = radiusPx }
}
