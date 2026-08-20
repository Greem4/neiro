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
 * Тем же воркером идёт и разовая проверка по пушу `app_update` — там работа
 * ставится под своим именем (`update_check_push`) и с `force = true`.
 *
 * Сам ничего не решает: спрашивает [UpdateCheckCoordinator.checkNow] и
 * переводит ответ в `Result`.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Суточная работа приходит без входных данных — для неё force = false,
        // и суточный троттлинг остаётся на месте. true ставит только пуш о
        // вышедшем релизе: ждать там нечего, версия уже опубликована.
        val force = inputData.getBoolean(KEY_FORCE, false)
        val status = runCatching { UpdateCheckCoordinator.checkNow(applicationContext, force) }
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

    companion object {
        /** Ключ входных данных: игнорировать суточный троттлинг. */
        const val KEY_FORCE = "force"

        private const val TAG = "UpdateCheckWorker"
    }
}
