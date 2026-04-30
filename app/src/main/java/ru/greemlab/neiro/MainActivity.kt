package ru.greemlab.neiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.greemlab.neiro.ui.screens.CalendarScreen

/**
 * Главная активити, точка входа приложения
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CalendarScreen() // Показываем календарь
        }
    }
}