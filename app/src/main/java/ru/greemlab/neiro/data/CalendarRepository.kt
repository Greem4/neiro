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

    /**
     * Атомарно обновляет карту дней через [transform]: чтение актуального значения
     * и запись происходят под общим writer-локом. Единственный способ записи dayData —
     * паттерн «прочитал snapshot → правка → save» терял бы параллельные изменения.
     */
    suspend fun updateDayData(
        transform: (Map<LocalDate, List<String>>) -> Map<LocalDate, List<String>>,
    )

    /** Сохраняет данные конкретного дня в "архивный" (второй) календарь. */
    suspend fun saveDayToArchive(date: LocalDate, data: List<String>)

    /** Удаляет данные конкретного дня из "архивного" календаря. */
    suspend fun deleteDayFromArchive(date: LocalDate)

    /**
     * Автоматическая правка архива по данным YClients: [compute] получает основной
     * календарь и архив и возвращает только изменённые дни архива. Всё под общим
     * writer-локом. Исходное содержимое каждого изменённого дня сохраняется (только
     * первый раз) — из него [rollbackArchiveMigration] делает откат. После отката
     * правка больше не запускается. Возвращает число изменённых дней.
     */
    suspend fun migrateArchiveDays(
        compute: (
            synced: Map<LocalDate, List<String>>,
            archived: Map<LocalDate, List<String>>,
        ) -> Map<LocalDate, List<String>>,
    ): Int

    /** Есть что откатывать после [migrateArchiveDays]. */
    val archiveMigrationRollbackAvailable: Flow<Boolean>

    /**
     * Возвращает дни, изменённые [migrateArchiveDays], к исходному содержимому и
     * выключает автоматическую правку. Возвращает число восстановленных дней.
     */
    suspend fun rollbackArchiveMigration(): Int

    /** Полная очистка всех данных (для отладки). */
    suspend fun clearAllData()

    suspend fun saveTheme(theme: String)

    /** JSON с архивным календарём, лентой уведомлений и меткой `exported_at` (дд-мм-гггг чч:мм). */
    suspend fun exportAllData(): String

    /**
     * Восстанавливает архивный календарь и уведомления из JSON. Профиль, тема и основной календарь
     * не затрагиваются. Возвращает [ImportResult] с информацией об успехе или отказе.
     */
    suspend fun restoreAllData(json: String): ImportResult
}

/** Результат импорта данных из JSON. */
sealed interface ImportResult {
    data object Success : ImportResult
    data class Failure(val reason: String) : ImportResult
}
