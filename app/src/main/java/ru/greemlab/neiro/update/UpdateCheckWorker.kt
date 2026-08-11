package ru.greemlab.neiro.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Суточная проверка обновлений. Работа уникальная (`update_check`), в чужие
 * цепочки — push и уведомления о занятиях — не вмешивается.
 *
 * Сам ничего не решает: спрашивает [UpdateCheckCoordinator.checkNow] и
 * переводит ответ в `Result`. Показ уведомления о найденной версии появится на
 * этапе 5.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val status = runCatching { UpdateCheckCoordinator.checkNow(applicationContext) }
            // Отмену не глушим: иначе Result.retry() воскресил бы работу,
            // остановленную системой.
            .onFailure { if (it is CancellationException) throw it }
            .getOrElse {
                Log.w(TAG, "Проверка обновлений сорвалась: ${it.message}")
                return Result.retry()
            }

        return when {
            // Сеть отвалилась посреди суточного окна — повторим с backoff'ом,
            // не дожидаясь следующих суток.
            status is UpdateStatus.Failed && status.failure == UpdateFailure.NoNetwork ->
                Result.retry()

            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "UpdateCheckWorker"
    }
}
