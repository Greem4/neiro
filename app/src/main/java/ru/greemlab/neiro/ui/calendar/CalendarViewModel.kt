package ru.greemlab.neiro.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.CalendarRepository
import java.time.LocalDate
import java.time.YearMonth

enum class CalendarMode {
    SYNCED, PERSONAL
}

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

    private val _calendarMode = MutableStateFlow(CalendarMode.SYNCED)
    val calendarMode: StateFlow<CalendarMode> = _calendarMode.asStateFlow()

    /** Данные о людях для каждой даты (дата → список записей). */
    val dayData: StateFlow<Map<LocalDate, List<String>>> = repository.dayDataFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CalendarDataStoreProvider.peekDayData(application),
        )

    /** Данные сохраненного (второго) календаря. */
    val savedDayData: StateFlow<Map<LocalDate, List<String>>> = repository.savedDayDataFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = CalendarDataStoreProvider.peekSavedDayData(application),
        )

    /** Эффективные данные календаря в зависимости от текущего режима. */
    val effectiveDayData: StateFlow<Map<LocalDate, List<String>>> = combine(
        dayData,
        savedDayData,
        calendarMode
    ) { synced, saved, mode ->
        if (mode == CalendarMode.SYNCED) synced else saved
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CalendarDataStoreProvider.peekDayData(application),
    )

    /**
     * Записи только для [currentMonth] — пересчитывается при смене месяца/режима
     * или при правке дня в этом месяце, без эмиссии при изменениях в архиве других месяцев.
     */
    val currentMonthDayData: StateFlow<Map<LocalDate, List<String>>> = combine(
        effectiveDayData,
        currentMonth,
    ) { data, month ->
        filterDayDataForMonth(data, month)
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = filterDayDataForMonth(
                CalendarDataStoreProvider.peekDayData(application),
                YearMonth.now(),
            ),
        )

    /** Имена для быстрого выбора — только открытый [currentMonth], без обхода архива. */
    val recentStudents: StateFlow<List<String>> = currentMonthDayData
        .map(::computeRecentStudents)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = computeRecentStudents(
                filterDayDataForMonth(
                    CalendarDataStoreProvider.peekDayData(application),
                    YearMonth.now(),
                ),
            ),
        )

    // Карточки детей — см. TODO.md «Карточки детей».

    fun nextMonth() {
        _currentMonth.value = _currentMonth.value.plusMonths(1)
    }

    fun previousMonth() {
        _currentMonth.value = _currentMonth.value.minusMonths(1)
    }

    fun setMonth(yearMonth: YearMonth) {
        _currentMonth.value = yearMonth
    }

    fun goToToday() {
        val today = LocalDate.now()
        _currentMonth.value = YearMonth.from(today)
        _selectedDate.value = today
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun navigateToDate(date: LocalDate) {
        _currentMonth.value = YearMonth.from(date)
        _selectedDate.value = date
    }

    fun setCalendarMode(mode: CalendarMode) {
        _calendarMode.value = mode
    }

    /** Сохраняет данные дня в архивный календарь. */
    fun archiveDay(date: LocalDate, data: List<String>) {
        viewModelScope.launch {
            repository.saveDayToArchive(date, data)
        }
    }

    /** Удаляет данные дня из архивного календаря. */
    fun unarchiveDay(date: LocalDate) {
        viewModelScope.launch {
            repository.deleteDayFromArchive(date)
        }
    }

    /**
     * Меняет статус ученика только в архивном календаре ([CalendarMode.PERSONAL]).
     * Интенсивы и диагностику не трогает.
     */
    fun updateSessionStatus(date: LocalDate, index: Int, status: AttendanceStatus) {
        viewModelScope.launch {
            if (calendarMode.value != CalendarMode.PERSONAL) return@launch
            val list = savedDayData.value[date] ?: return@launch
            val item = list.getOrNull(index) ?: return@launch
            if (SessionParser.isExtra(item)) return@launch

            val updated = list.toMutableList().apply {
                this[index] = SessionParser.withStatus(item, status)
            }
            repository.saveDayToArchive(date, updated)
        }
    }

    /**
     * Удаляет запись из списка на дату.
     * В SYNCED можно удалять только интенсивы — остальные записи идут из YClients.
     */
    fun deleteSession(date: LocalDate, index: Int) {
        viewModelScope.launch {
            val mode = calendarMode.value
            val currentMap = if (mode == CalendarMode.SYNCED) dayData.value else savedDayData.value
            val list = currentMap[date] ?: return@launch
            if (index !in list.indices) return@launch
            if (mode == CalendarMode.SYNCED && !SessionParser.isIntensive(list[index])) return@launch

            val updated = list.toMutableList().apply { removeAt(index) }

            if (mode == CalendarMode.SYNCED) {
                val newData = currentMap.toMutableMap().apply {
                    if (updated.isEmpty()) remove(date) else put(date, updated)
                }
                repository.saveDayData(newData)
            } else {
                if (updated.isEmpty()) {
                    repository.deleteDayFromArchive(date)
                } else {
                    repository.saveDayToArchive(date, updated)
                }
            }
        }
    }

    /**
     * Сохранение списка записей для указанной даты.
     *
     * В SYNCED меняются только интенсивы; ученики и диагностика из YClients не перезаписываются.
     * В архиве ([CalendarMode.PERSONAL]) сохраняется полный список как есть.
     *
     * @param date Дата сохранения.
     * @param names Список записей в формате "Имя|attended" или с префиксами экстра-сессий.
     */
    fun saveNamesForDate(
        date: LocalDate,
        names: List<String>,
    ) {
        viewModelScope.launch {
            if (calendarMode.value == CalendarMode.PERSONAL) {
                repository.saveDayToArchive(date, names)
                return@launch
            }

            val existing = dayData.value[date].orEmpty()
            val merged = mergeSyncedDayPreservingNonIntensives(existing, names)
            val newData = dayData.value.toMutableMap()
            if (merged.isEmpty()) {
                newData.remove(date)
            } else {
                newData[date] = merged
            }
            repository.saveDayData(newData)
        }
    }
}

/**
 * В synced-календаре локально правятся только интенсивы:
 * записи YClients (ученики, диагностика) берём из [existing], интенсивы — из [incoming].
 */
internal fun mergeSyncedDayPreservingNonIntensives(
    existing: List<String>,
    incoming: List<String>,
): List<String> {
    val fromYClients = existing.filterNot { SessionParser.isIntensive(it) }
    val intensives = incoming.filter { SessionParser.isIntensive(it) }
    return fromYClients + intensives
}
