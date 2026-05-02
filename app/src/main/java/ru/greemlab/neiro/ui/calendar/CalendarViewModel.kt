package ru.greemlab.neiro.ui.calendar

import android.app.Application
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import ru.greemlab.neiro.data.CalendarDataStore

/**
 * ViewModel для управления состоянием календаря с поддержкой сохранения данных в DataStore.
 */
@RequiresApi(Build.VERSION_CODES.O)
class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    
    // Хранилище данных
    private val dataStore = CalendarDataStore(application)

    // Текущий отображаемый месяц
    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth.asStateFlow()

    // Текущая выбранная дата
    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    // Данные о людях для каждой даты (дата -> список фамилий)
    private val _dayData = MutableStateFlow<Map<LocalDate, List<String>>>(emptyMap())
    val dayData: StateFlow<Map<LocalDate, List<String>>> = _dayData.asStateFlow()

    init {
        // Загружаем данные из постоянного хранилища при старте
        viewModelScope.launch {
            dataStore.dayDataFlow.collectLatest { savedData ->
                _dayData.value = savedData
            }
        }
    }

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
     */
    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /**
     * Сохранение списка имен для указанной даты в DataStore.
     * @param date Дата сохранения.
     * @param names Список имен в формате "Имя|attended".
     * @param repeatUntilMonthEnd Если true, дублирует список на все такие же дни недели до конца месяца.
     */
    fun saveNamesForDate(date: LocalDate, names: List<String>, repeatUntilMonthEnd: Boolean = false) {
        viewModelScope.launch {
            val newData = _dayData.value.toMutableMap()
            
            if (repeatUntilMonthEnd) {
                // Находим все такие же дни недели до конца месяца
                val lastDayOfMonth = YearMonth.from(date).atEndOfMonth()
                var nextDate = date
                
                while (!nextDate.isAfter(lastDayOfMonth)) {
                    if (names.isEmpty()) {
                        newData.remove(nextDate)
                    } else {
                        // Для будущих дат сбрасываем статус "пришел" (attended = false)
                        // чтобы они не считались проведенными в статистике до ручного подтверждения
                        val futureNames = if (nextDate.isAfter(date)) {
                            names.map { nameWithStatus ->
                                val name = nameWithStatus.split("|")[0]
                                "$name|false"
                            }
                        } else {
                            names
                        }
                        newData[nextDate] = futureNames
                    }
                    nextDate = nextDate.plusWeeks(1)
                }
            } else {
                if (names.isEmpty()) {
                    newData.remove(date)
                } else {
                    newData[date] = names
                }
            }

            // Сохраняем всё состояние в DataStore
            dataStore.saveDayData(newData)
        }
    }
}
