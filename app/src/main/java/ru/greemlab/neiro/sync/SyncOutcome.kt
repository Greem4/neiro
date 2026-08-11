package ru.greemlab.neiro.sync

/**
 * Результат операции синхронизации календаря с YClients.
 */
sealed interface SyncOutcome {
    data class Success(val newlyAdded: Int) : SyncOutcome

    /**
     * @param offline сервер Neiro не ответил. Календарь при этом не пуст — в нём
     * сохранённые данные, и сказать об этом надо прямо (Этап 8).
     */
    data class Failure(val message: String, val offline: Boolean = false) : SyncOutcome
}
