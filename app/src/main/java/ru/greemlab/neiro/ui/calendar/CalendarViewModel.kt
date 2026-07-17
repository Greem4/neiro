package ru.greemlab.neiro.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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

/** Всё, что нужно экранам про выбранный день, — без подписки на полные карты. */
data class SelectedDayContext(
    val date: LocalDate,
    /** Записи дня в текущем режиме календаря (synced или архив). */
    val effective: List<String>,
    /** Записи дня в основном (YClients) календаре. */
    val synced: List<String>,
    /** Записи дня в архиве; null — день не архивирован. */
    val archived: List<String>?,
)

/**
 * ViewModel для управления состоянием календаря.
 *
 * Чтение через Flow, запись — через единый [CalendarRepository], который
 * сам сериализует параллельные записи через mutex.
 *
 * Месяц/выбранная дата/режим переживают process death через [SavedStateHandle].
 */
class CalendarViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val repository: CalendarRepository = CalendarDataStoreProvider.get(application)

    private val _currentMonth = MutableStateFlow(restoredMonth())
    val currentMonth: StateFlow<YearMonth> = _currentMonth.asStateFlow()

    private val _selectedDate = MutableStateFlow(restoredSelectedDate())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    private val _calendarMode = MutableStateFlow(restoredMode())
    val calendarMode: StateFlow<CalendarMode> = _calendarMode.asStateFlow()

    private fun restoredMonth(): YearMonth =
        savedStateHandle.get<String>(KEY_MONTH)
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: YearMonth.now()

    private fun restoredSelectedDate(): LocalDate? {
        val raw = savedStateHandle.get<String>(KEY_SELECTED_DATE) ?: return LocalDate.now()
        if (raw.isEmpty()) return null
        return runCatching { LocalDate.parse(raw) }.getOrNull() ?: LocalDate.now()
    }

    private fun restoredMode(): CalendarMode =
        savedStateHandle.get<String>(KEY_MODE)
            ?.let { runCatching { CalendarMode.valueOf(it) }.getOrNull() }
            ?: CalendarMode.SYNCED

    private fun updateMonth(month: YearMonth) {
        _currentMonth.value = month
        savedStateHandle[KEY_MONTH] = month.toString()
    }

    private fun updateSelectedDate(date: LocalDate?) {
        _selectedDate.value = date
        savedStateHandle[KEY_SELECTED_DATE] = date?.toString() ?: ""
    }

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

    /** Даты текущего месяца с расхождением архива и синхронизации; считается вне Main. */
    val archiveMismatchDates: StateFlow<Set<LocalDate>> = combine(
        dayData,
        savedDayData,
        currentMonth,
    ) { synced, saved, month ->
        val savedInMonth = saved.filterKeys { YearMonth.from(it) == month }
        if (savedInMonth.isEmpty()) {
            emptySet()
        } else {
            ArchiveSyncCompare.mismatchDates(synced, savedInMonth)
        }
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    /** Контекст выбранного дня — экран не подписывается на полные карты. */
    val selectedDayContext: StateFlow<SelectedDayContext?> = combine(
        selectedDate,
        dayData,
        savedDayData,
        calendarMode,
    ) { date, synced, saved, mode ->
        if (date == null) {
            null
        } else {
            val archived = saved[date]
            SelectedDayContext(
                date = date,
                effective = if (mode == CalendarMode.SYNCED) {
                    synced[date].orEmpty()
                } else {
                    archived.orEmpty()
                },
                synced = synced[date].orEmpty(),
                archived = archived,
            )
        }
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    // Карточки детей — см. TODO.md «Карточки детей».

    fun nextMonth() {
        updateMonth(_currentMonth.value.plusMonths(1))
    }

    fun previousMonth() {
        updateMonth(_currentMonth.value.minusMonths(1))
    }

    fun setMonth(yearMonth: YearMonth) {
        updateMonth(yearMonth)
    }

    fun goToToday() {
        val today = LocalDate.now()
        updateMonth(YearMonth.from(today))
        updateSelectedDate(today)
    }

    fun selectDate(date: LocalDate) {
        updateSelectedDate(date)
    }

    fun navigateToDate(date: LocalDate) {
        updateMonth(YearMonth.from(date))
        updateSelectedDate(date)
    }

    fun setCalendarMode(mode: CalendarMode) {
        _calendarMode.value = mode
        savedStateHandle[KEY_MODE] = mode.name
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
            if (mode == CalendarMode.SYNCED) {
                repository.updateDayData { current ->
                    val list = current[date] ?: return@updateDayData current
                    if (index !in list.indices) return@updateDayData current
                    if (!SessionParser.isIntensive(list[index])) return@updateDayData current
                    val updated = list.toMutableList().apply { removeAt(index) }
                    current.toMutableMap().apply {
                        if (updated.isEmpty()) remove(date) else put(date, updated)
                    }
                }
            } else {
                val list = savedDayData.value[date] ?: return@launch
                if (index !in list.indices) return@launch
                val updated = list.toMutableList().apply { removeAt(index) }
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

            repository.updateDayData { current ->
                val existing = current[date].orEmpty()
                val merged = mergeSyncedDayPreservingNonIntensives(existing, names)
                current.toMutableMap().apply {
                    if (merged.isEmpty()) remove(date) else put(date, merged)
                }
            }
        }
    }

    private companion object {
        const val KEY_MONTH = "calendar_month"
        const val KEY_SELECTED_DATE = "calendar_selected_date"
        const val KEY_MODE = "calendar_mode"
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
