package ru.greemlab.neiro

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.screens.CalendarScreen

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Обертка темы. Она сама поймет, какая тема стоит на телефоне юзера (светлая/темная)
            // и передаст правильные цвета внутрь
            NeiroTheme {
                CalendarScreen()
            }
        }
    }
}