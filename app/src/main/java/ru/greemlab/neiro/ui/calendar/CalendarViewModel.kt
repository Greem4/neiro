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
     * Переключает статус посещения для ученика.
     */
    fun toggleAttendance(date: LocalDate, index: Int) {
        viewModelScope.launch {
            val currentList = _dayData.value[date] ?: return@launch
            val newList = currentList.toMutableList()
            if (index in newList.indices) {
                val item = newList[index]
                if (!SessionParser.isExtra(item)) {
                    val parts = item.split("|")
                    val name = parts[0]
                    val currentAttended = parts.getOrNull(1)?.toBoolean() ?: false
                    newList[index] = "$name|${!currentAttended}"
                    
                    val newData = _dayData.value.toMutableMap()
                    newData[date] = newList
                    dataStore.saveDayData(newData)
                }
            }
        }
    }

    /**
     * Удаляет запись (ученика или доп. доход) из списка на дату.
     */
    fun deleteSession(date: LocalDate, index: Int) {
        viewModelScope.launch {
            val currentList = _dayData.value[date] ?: return@launch
            val newList = currentList.toMutableList()
            if (index in newList.indices) {
                newList.removeAt(index)
                val newData = _dayData.value.toMutableMap()
                if (newList.isEmpty()) {
                    newData.remove(date)
                } else {
                    newData[date] = newList
                }
                dataStore.saveDayData(newData)
            }
        }
    }

    /**
     * Сохранение списка имен для указанной даты в DataStore.
     * @param date Дата сохранения.
     * @param names Список имен в формате "Имя|attended".
     * @param repeatUntilMonthEnd Если true, дублирует список на все такие же дни недели до конца месяца.
     * @param repeatNextMonth Если true, дублирует список на все такие же дни недели в следующем месяце.
     */
    fun saveNamesForDate(
        date: LocalDate, 
        names: List<String>, 
        repeatUntilMonthEnd: Boolean = false,
        repeatNextMonth: Boolean = false
    ) {
        viewModelScope.launch {
            val newData = _dayData.value.toMutableMap()
            
            // 1. Обработка текущего месяца
            if (repeatUntilMonthEnd) {
                val lastDayOfMonth = YearMonth.from(date).atEndOfMonth()
                var nextDate = date
                while (!nextDate.isAfter(lastDayOfMonth)) {
                    updateDateData(newData, nextDate, date, names)
                    nextDate = nextDate.plusWeeks(1)
                }
            } else {
                updateDateData(newData, date, date, names)
            }
            
            // 2. Обработка следующего месяца
            if (repeatNextMonth) {
                val nextMonth = YearMonth.from(date).plusMonths(1)
                val lastDayOfNextMonth = nextMonth.atEndOfMonth()
                
                // Находим первый такой же день недели в следующем месяце
                var nextMonthDate = nextMonth.atDay(1)
                while (nextMonthDate.dayOfWeek != date.dayOfWeek) {
                    nextMonthDate = nextMonthDate.plusDays(1)
                }
                
                // Повторяем каждую неделю до конца следующего месяца
                while (!nextMonthDate.isAfter(lastDayOfNextMonth)) {
                    updateDateData(newData, nextMonthDate, date, names)
                    nextMonthDate = nextMonthDate.plusWeeks(1)
                }
            }

            // Сохраняем всё состояние в DataStore
            dataStore.saveDayData(newData)
        }
    }

    /**
     * Вспомогательный метод для обновления данных конкретной даты.
     */
    private fun updateDateData(
        data: MutableMap<LocalDate, List<String>>,
        targetDate: LocalDate,
        originalDate: LocalDate,
        names: List<String>
    ) {
        if (names.isEmpty()) {
            data.remove(targetDate)
        } else {
            // Для будущих дат сбрасываем статус "пришел" (attended = false)
            val processedNames = if (targetDate.isAfter(originalDate)) {
                names.map { nameWithStatus ->
                    if (nameWithStatus.startsWith("__")) {
                        nameWithStatus
                    } else {
                        val name = nameWithStatus.split("|")[0]
                        "$name|false"
                    }
                }
            } else {
                names
            }
            data[targetDate] = processedNames
        }
    }
}
