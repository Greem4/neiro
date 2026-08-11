package ru.greemlab.neiro.update

import android.content.Context
import android.os.Build

/**
 * Почему самообновление молчит. Показывается на экране «О программе» вместо
 * кнопки — человек должен понимать, куда делась проверка.
 */
enum class UpdateBlockReason {
    /** debug или prerelease: у них свой applicationId, релизный APK им не обновление. */
    NotReleaseBuild,

    /** Установлено из RuStore — обновляет магазин, наш APK поверх не встанет. */
    InstalledFromRuStore,

    /** Установлено из Google Play — то же самое. */
    InstalledFromPlay,
}

/**
 * Можно ли этой сборке обновлять саму себя.
 *
 * Проверка канала — не украшение: магазинная сборка подписана ключом магазина,
 * и предложить ей несовместимый файл значит показать «Приложение не
 * установлено» без объяснений (RISKS.md § Магазин и самообновление).
 */
object UpdateChannelGate {

    /**
     * Проверяется на живом устройстве до того, как этап считается сделанным:
     * константа из документации без проверки — та же догадка.
     */
    private const val RUSTORE_PACKAGE = "ru.vk.store"

    private const val PLAY_PACKAGE = "com.android.vending"

    /**
     * null — обновляться можно.
     *
     * `null` в installer (установка вручную), `com.android.shell` (adb),
     * системный установщик и наш собственный пакет (обновились сами) —
     * разрешены.
     */
    fun blockReason(context: Context): UpdateBlockReason? {
        // Флаг приходит из BuildConfig через UpdateConfig — сборка знает, кто
        // она, и в debug проверка выключена ещё до всякой сети.
        if (!UpdateConfig.isEnabled) return UpdateBlockReason.NotReleaseBuild
        return when (installerPackage(context)) {
            RUSTORE_PACKAGE -> UpdateBlockReason.InstalledFromRuStore
            PLAY_PACKAGE -> UpdateBlockReason.InstalledFromPlay
            else -> null
        }
    }

    fun isAllowed(context: Context): Boolean = blockReason(context) == null

    /**
     * Кто установил приложение. На API 30+ — `getInstallSourceInfo`, ниже —
     * устаревший `getInstallerPackageName`. Ошибку глушим: пакета может уже не
     * быть в списке (редко, но бывает после восстановления из бэкапа), и это
     * не повод считать сборку магазинной.
     */
    private fun installerPackage(context: Context): String? {
        val packageManager = context.packageManager
        val packageName = context.packageName
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
    }
}
