package ru.greemlab.neiro.ui.calendar

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.YearMonth

/**
 * ViewModel для управления состоянием календаря.
 * Отвечает за навигацию по месяцам, выбор даты и хранение данных о людях на конкретные дни.
 */
@RequiresApi(Build.VERSION_CODES.O)
class CalendarViewModel : ViewModel() {
    // Текущий отображаемый месяц
    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth.asStateFlow()

    // Текущая выбранная дата
    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    // Данные о людях для каждой даты (дата -> список фамилий)
    private val _dayData = MutableStateFlow<Map<LocalDate, List<String>>>(emptyMap())
    val dayData: StateFlow<Map<LocalDate, List<String>>> = _dayData.asStateFlow()

    /**
     * Переход к следующему месяцу.
     */
    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
    }

    /**
     * Переход к предыдущему месяцу.
     */
    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
    }

    /**
     * Установка календаря на текущую системную дату.
     */
    fun goToToday() {
        val today = LocalDate.now()
        _currentMonth.value = YearMonth.from(today)
        _selectedDate.value = today
    }

    /**
     * Выбор конкретной даты в календаре.
     * @param date Дата для выбора.
     */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /**
     * Сохранение списка имен для указанной даты.
     * @param date Дата, для которой сохраняются данные.
     * @param names Список фамилий/имен.
     */
    fun saveNamesForDate(date: LocalDate, names: List<String>) {
        val newData = _dayData.value.toMutableMap()
        newData[date] = names
        _dayData.value = newData
    }
}
