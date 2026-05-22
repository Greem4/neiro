package ru.greemlab.neiro.sync

/**
 * Результат операции синхронизации календаря с YClients.
 */
sealed interface SyncOutcome {
    data class Success(val newlyAdded: Int) : SyncOutcome
    data class Failure(val message: String) : SyncOutcome
}
