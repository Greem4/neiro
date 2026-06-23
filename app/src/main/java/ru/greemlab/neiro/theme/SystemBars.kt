package ru.greemlab.neiro.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

internal fun applyWindowSystemBars(
    window: Window,
    view: View,
    backgroundColor: Color,
    darkTheme: Boolean,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.Transparent.toArgb()
    window.navigationBarColor = Color.Transparent.toArgb()
    window.setBackgroundDrawable(ColorDrawable(backgroundColor.toArgb()))
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}

/** Прозрачные системные панели и фон окна = [MaterialTheme.colorScheme.background]. */
@Composable
fun ApplySystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val backgroundColor = MaterialTheme.colorScheme.background
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        applyWindowSystemBars(window, view, backgroundColor, darkTheme)
    }
}
