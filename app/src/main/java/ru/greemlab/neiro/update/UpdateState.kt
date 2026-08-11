package ru.greemlab.neiro.update

import java.io.File

/**
 * Состояние экрана «О программе». Одна цепочка на весь путь обновления:
 * проверка → найдено → скачивание → сверка → готово → установка → результат.
 *
 * Отдельно от [UpdateStatus] намеренно: тот — ответ на единственный вопрос
 * «есть ли новее», и живёт в фоне, без экрана. Здесь же есть промежуточные
 * шаги, которые видит только пользователь.
 */
sealed interface UpdateState {

    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val checkedAt: Long) : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState

    data class Verifying(val info: UpdateInfo) : UpdateState

    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState

    data class Installing(val info: UpdateInfo) : UpdateState

    /** Система требует подтверждения — ждём, пока пользователь ответит. */
    data class AwaitingConfirmation(val info: UpdateInfo) : UpdateState

    data class Failed(val reason: UpdateFailure, val info: UpdateInfo?) : UpdateState

    /** Сборка из магазина или debug — обновлять себя нельзя. */
    data class Blocked(val why: UpdateBlockReason) : UpdateState
}

/** Информация о версии, которую состояние несёт с собой, если она уже известна. */
val UpdateState.info: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> info
        is UpdateState.Downloading -> info
        is UpdateState.Verifying -> info
        is UpdateState.ReadyToInstall -> info
        is UpdateState.Installing -> info
        is UpdateState.AwaitingConfirmation -> info
        is UpdateState.Failed -> info
        else -> null
    }

/** Идёт работа — кнопку «Проверить» в это время показывать нечем. */
val UpdateState.isBusy: Boolean
    get() = this is UpdateState.Checking ||
        this is UpdateState.Downloading ||
        this is UpdateState.Verifying ||
        this is UpdateState.Installing
