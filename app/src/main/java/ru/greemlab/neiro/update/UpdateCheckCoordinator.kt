package ru.greemlab.neiro.update

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Когда приложение спрашивает GitHub о новой версии. Два триггера, один вход.
 *
 * 1. [UpdateCheckWorker] раз в сутки — `PeriodicWorkRequest`, а не
 *    самопланирующийся `OneTimeWorkRequest`, как у `PushKeepAliveCoordinator`.
 *    Тому нужен интервал, меняющийся по времени суток, и он платит за это
 *    хрупкой цепочкой: оборвался `scheduleNext` — умерли все следующие звенья.
 *    Проверке обновлений хватает ровных суток, а периодическую работу
 *    WorkManager перепланирует сам и переживает перезагрузку.
 * 2. Открытие приложения, если с прошлой проверки прошло больше суток — по
 *    образцу `AutoSyncCoordinator`. Воркер может задержаться на день в Doze;
 *    открытие приложения — самый естественный момент спросить. Суточный порог
 *    считает сам [UpdateChecker], здесь порога нет.
 *
 * Скачивание отсюда не запускается никогда — только по нажатию пользователя
 * (этап 6). Пятнадцать мегабайт по мобильному интернету без спроса не прощают.
 */
object UpdateCheckCoordinator {

    const val WORK_NAME = "update_check"

    private const val TAG = "UpdateCheck"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    /**
     * Вызывается из `NeiroApplication.onCreate` в общем `appScope` на IO:
     * `UpdatePreferences` и `WorkManager` трогают диск, им на main делать
     * нечего. Подписка на [ProcessLifecycleOwner] возвращается на main — она
     * main-only по контракту.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext

        val blocked = UpdateChannelGate.blockReason(appContext)
        if (blocked != null) {
            // debug, prerelease или сборка из магазина: в сеть не ходим и
            // снимаем работу, оставшуюся от прошлой установки.
            Log.i(TAG, "Самообновление выключено: $blocked")
            cancel(appContext)
            return
        }

        val preferences = UpdatePreferences.get(appContext)
        cleanupInstalledLeftovers(appContext, preferences)

        if (preferences.isAutoCheckEnabled) {
            schedulePeriodic(appContext)
        } else {
            cancel(appContext)
        }

        // Наблюдатель ставится независимо от настройки, а не под `if`: иначе
        // включённая в настройках автопроверка начала бы работать при открытии
        // приложения только после перезапуска процесса.
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!preferences.isAutoCheckEnabled) return
                scope.launch { checkNow(appContext) }
            }
        }
        // addObserver обязан идти с main. Приложение к этому моменту может уже
        // быть STARTED — LifecycleRegistry догоняет новичка событиями до
        // текущего состояния, поэтому первый onStart не теряется.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
    }

    /**
     * Единственная точка вызова проверки: и воркер, и старт приложения, и
     * кнопка на экране «О программе» (этап 8) идут через неё — тогда в логах
     * видно ровно столько запросов к GitHub, сколько их было.
     *
     * @param force ручная проверка: суточный троттлинг игнорируется.
     */
    suspend fun checkNow(context: Context, force: Boolean = false): UpdateStatus {
        val appContext = context.applicationContext
        val status = UpdateChecker.create(appContext).check(force)
        when (status) {
            is UpdateStatus.Available -> {
                Log.i(TAG, "Есть обновление: ${status.info.version.versionName}")
                // Правило «об одной версии говорим один раз» и проверка
                // «пропустили» живут внутри UpdateNotifier — здесь не дублируем.
                UpdateNotifier.notifyIfNeeded(appContext, status.info)
            }

            is UpdateStatus.UpToDate -> Log.i(TAG, "Обновлений нет")
            is UpdateStatus.Throttled -> Log.i(TAG, "Проверяли меньше суток назад, GitHub не трогаем")
            is UpdateStatus.Blocked -> Log.i(TAG, "Самообновление выключено: ${status.why}")
            is UpdateStatus.Failed -> Log.w(TAG, "Проверка не удалась: ${status.failure}")
        }
        return status
    }

    /** Настройка «проверять автоматически» на экране «О программе» (этап 8). */
    fun onAutoCheckToggled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        UpdatePreferences.get(appContext).isAutoCheckEnabled = enabled
        if (enabled && UpdateChannelGate.isAllowed(appContext)) {
            schedulePeriodic(appContext)
        } else {
            cancel(appContext)
        }
    }

    /**
     * Пятнадцать мегабайт от версии, которая уже стоит (или устарела), лежат в
     * кэше мёртвым грузом. Чистим при старте, не дожидаясь, пока человек зайдёт
     * на экран «О программе».
     *
     * Отметку `updated_from_version_code` здесь не трогаем: её читает и стирает
     * экран, чтобы показать «Обновлено до 0.1.2» ровно один раз.
     */
    private fun cleanupInstalledLeftovers(context: Context, preferences: UpdatePreferences) {
        val pending = preferences.pendingVersionCode
        if (preferences.pendingApkPath == null && pending == 0) return
        if (pending > BuildConfig.VERSION_CODE) return

        UpdateDownloader.clearDownloads(context)
        preferences.clearPendingApk()
        Log.i(TAG, "Убрал скачанный APK версии $pending — она уже не новее установленной")
    }

    private fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            // Проверка обновлений — не та новость, ради которой стоит доедать
            // последние проценты батареи.
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            UpdateConfig.CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
            .setConstraints(constraints)
            .build()

        // KEEP: при каждом старте приложения перезапись расписания сдвигала бы
        // проверку в бесконечность — у того, кто заходит чаще раза в сутки,
        // она не сработала бы никогда.
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
