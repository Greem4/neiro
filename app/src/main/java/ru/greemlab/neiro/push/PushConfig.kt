package ru.greemlab.neiro.push

import ru.greemlab.neiro.BuildConfig

object PushConfig {
    val isServerConfigured: Boolean
        get() = BuildConfig.PUSH_SERVER_CONFIGURED

    val isFcmEnabled: Boolean
        get() = BuildConfig.PUSH_FCM_ENABLED

    /** Серверный push полностью готов к работе. */
    val isActive: Boolean
        get() = isServerConfigured && isFcmEnabled
}
