package ru.greemlab.neiro.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает baseline-профиль настоящим холодным стартом приложения.
 *
 * Что попадает в профиль, решает не список в файле, а то, что реально
 * исполнилось за этот прогон: Application, Activity, чтение синхронного кэша
 * DataStore, первая композиция главного экрана и его ViewModel'и.
 *
 * Сценарий намеренно короткий — ровно холодный старт, ради которого профиль и
 * нужен. Дописывать сюда прокрутки и переходы стоит только тогда, когда
 * замеры покажут, что тормозит именно они: каждый лишний шаг раздувает
 * профиль, а место в нём не бесплатно.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = targetPackageName) {
        pressHome()
        // startActivityAndWait ждёт первый кадр — то есть отрисованный
        // календарь, а не просто запущенный процесс.
        startActivityAndWait()
    }

    /**
     * Пакет берём из аргумента, который плагин подставляет сам для каждого
     * варианта, а не пишем строкой: у debug и prerelease свои суффиксы
     * applicationId (см. `app/build.gradle.kts`), и захардкоженное имя ломало
     * бы прогон на всём, кроме release.
     */
    private val targetPackageName: String
        get() = InstrumentationRegistry.getArguments().getString(TARGET_PACKAGE_NAME_ARG)
            ?: error("Прогон запущен мимо плагина: нет аргумента $TARGET_PACKAGE_NAME_ARG")

    private companion object {
        const val TARGET_PACKAGE_NAME_ARG = "androidx.benchmark.targetPackageName"
    }
}
