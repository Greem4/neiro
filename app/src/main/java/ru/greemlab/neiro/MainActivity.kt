package ru.greemlab.neiro

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.screens.CalendarScreen

/**
 * Главная Activity приложения.
 * Является точкой входа и устанавливает основной контент с использованием Jetpack Compose.
 */
class MainActivity : ComponentActivity() {
    
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Установка UI контента
        setContent {
            // NeiroTheme — кастомная тема приложения, которая поддерживает темный и светлый режимы.
            NeiroTheme {
                // Главный экран приложения — Календарь
                CalendarScreen()
            }
        }
    }
}
