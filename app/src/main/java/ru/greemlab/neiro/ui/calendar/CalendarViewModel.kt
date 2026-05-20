package ru.greemlab.neiro.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import java.time.LocalDate
import java.time.YearMonth

/**
 * ViewModel для управления состоянием календаря.
 *
 * Чтение через Flow, запись — через единый [CalendarRepository], который
 * сам сериализует параллельные записи через mutex.
 */
class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CalendarRepository = CalendarDataStoreProvider.get(application)

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    /** Данные о людях для каждой даты (дата → список записей). */
    val dayData: StateFlow<Map<LocalDate, List<String>>> = repository.dayDataFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CalendarDataStoreProvider.peekDayData(application),
        )

    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
    }

    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
    }

    fun goToToday() {
        val today = LocalDate.now()
        _currentMonth.value = YearMonth.from(today)
        _selectedDate.value = today
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    /**
     * Переключает статус посещения для записи на указанной дате.
     * Игнорирует «экстра» записи (интенсив/диагностика) — у них статус меняется по-другому.
     */
    fun toggleAttendance(date: LocalDate, index: Int) {
        viewModelScope.launch {
            val current = dayData.value
            val list = current[date] ?: return@launch
            val item = list.getOrNull(index) ?: return@launch
            if (SessionParser.isExtra(item)) return@launch

            val sep = item.indexOf('|')
            val name = if (sep < 0) item else item.substring(0, sep)
            val currentAttended = SessionParser.isAttended(item)
            val updated = list.toMutableList().apply {
                this[index] = "$name|${!currentAttended}"
            }

            repository.saveDayData(current + (date to updated))
        }
    }

    /** Удаляет запись (ученика или доп. доход) из списка на дату. */
    fun deleteSession(date: LocalDate, index: Int) {
        viewModelScope.launch {
            val current = dayData.value
            val list = current[date] ?: return@launch
            if (index !in list.indices) return@launch

            val updated = list.toMutableList().apply { removeAt(index) }
            val newData = current.toMutableMap().apply {
                if (updated.isEmpty()) remove(date) else put(date, updated)
            }
            repository.saveDayData(newData)
        }
    }

    /**
     * Сохранение списка записей для указанной даты.
     *
     * @param date Дата сохранения.
     * @param names Список записей в формате "Имя|attended" или с префиксами экстра-сессий.
     * @param repeatUntilMonthEnd Если true, дублирует список на все такие же дни недели до конца месяца.
     * @param repeatNextMonth Если true, дублирует список на все такие же дни недели в следующем месяце.
     */
    fun saveNamesForDate(
        date: LocalDate,
        names: List<String>,
        repeatUntilMonthEnd: Boolean = false,
        repeatNextMonth: Boolean = false,
    ) {
        viewModelScope.launch {
            val newData = dayData.value.toMutableMap()

            if (repeatUntilMonthEnd) {
                val lastDayOfMonth = YearMonth.from(date).atEndOfMonth()
                var cursor = date
                while (!cursor.isAfter(lastDayOfMonth)) {
                    updateDateData(newData, cursor, date, names)
                    cursor = cursor.plusWeeks(1)
                }
            } else {
                updateDateData(newData, date, date, names)
            }

            if (repeatNextMonth) {
                val nextMonth = YearMonth.from(date).plusMonths(1)
                val lastDayOfNextMonth = nextMonth.atEndOfMonth()
                var cursor = nextMonth.atDay(1)
                while (cursor.dayOfWeek != date.dayOfWeek) {
                    cursor = cursor.plusDays(1)
                }
                while (!cursor.isAfter(lastDayOfNextMonth)) {
                    updateDateData(newData, cursor, date, names)
                    cursor = cursor.plusWeeks(1)
                }
            }

            repository.saveDayData(newData)
        }
    }

    private fun updateDateData(
        data: MutableMap<LocalDate, List<String>>,
        targetDate: LocalDate,
        originalDate: LocalDate,
        names: List<String>,
    ) {
        if (names.isEmpty()) {
            data.remove(targetDate)
            return
        }
        val processed = if (targetDate.isAfter(originalDate)) resetAttendance(names) else names
        data[targetDate] = processed
    }

    private fun resetAttendance(names: List<String>): List<String> = names.map { raw ->
        if (SessionParser.isExtra(raw)) {
            raw
        } else {
            val sep = raw.indexOf('|')
            val name = if (sep < 0) raw else raw.substring(0, sep)
            "$name|false"
        }
    }
}
