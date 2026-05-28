package ru.greemlab.neiro.data

import kotlinx.coroutines.flow.Flow
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.LocalDate

/**
 * Контракт хранилища приложения. Все слои UI работают только с этим интерфейсом —
 * это позволяет подменять реализацию в тестах и не зависеть от деталей DataStore.
 *
 * Все методы записи thread-safe: внутри реализации сериализуются через единый
 * Mutex/writer, поэтому параллельные обновления из разных корутин не теряются.
 */
interface CalendarRepository {

    /** Снимок данных, доступный синхронно прямо со старта (заполняется из disk-кэша). */
    fun peekSnapshot(): StoreSnapshot

    val themeFlow: Flow<String>
    val dayDataFlow: Flow<Map<LocalDate, List<String>>>
    val savedDayDataFlow: Flow<Map<LocalDate, List<String>>>
    val userProfileFlow: Flow<UserProfile>

    /** Гидратация из DataStore в фоне. */
    suspend fun warmUp()

    /** Миграции старых форматов профиля. */
    suspend fun migrateProfileIfNeeded()

    /**
     * Атомарно обновляет профиль через [transform]. Чтение и запись происходят
     * под общим writer-локом — параллельные вызовы не теряют изменения.
     */
    suspend fun updateProfile(transform: (UserProfile) -> UserProfile)

    /** Меняет текущую ставку за занятие в профиле. */
    suspend fun applySessionPriceChange(newPrice: Double)

    suspend fun saveDayData(data: Map<LocalDate, List<String>>)

    /** Сохраняет данные конкретного дня в "архивный" (второй) календарь. */
    suspend fun saveDayToArchive(date: LocalDate, data: List<String>)

    /** Удаляет данные конкретного дня из "архивного" календаря. */
    suspend fun deleteDayFromArchive(date: LocalDate)

    /** Полная очистка всех данных (для отладки). */
    suspend fun clearAllData()

    suspend fun saveTheme(theme: String)

    /** JSON с данными архивного календаря и меткой `exported_at` (дд-мм-гггг чч:мм). */
    suspend fun exportAllData(): String

    /**
     * Восстанавливает архивный календарь из JSON. Профиль, тема и основной календарь
     * не затрагиваются. Возвращает [ImportResult] с информацией об успехе или отказе.
     */
    suspend fun restoreAllData(json: String): ImportResult
}

/** Результат импорта данных из JSON. */
sealed interface ImportResult {
    data object Success : ImportResult
    data class Failure(val reason: String) : ImportResult
}
