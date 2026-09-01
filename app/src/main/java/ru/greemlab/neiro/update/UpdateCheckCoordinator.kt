package ru.greemlab.neiro.update

import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Когда приложение спрашивает GitHub о новой версии. Три триггера, один вход.
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
 * 3. Пуш `app_update` от neiro-push — [onUpdatePush]. Первые два триггера
 *    отвечают на вопрос «не вышло ли чего», третий приходит в тот момент,
 *    когда релиз действительно вышел: `release.yml` после публикации дёргает
 *    сервер, сервер рассылает пуш. Без него телефон в кармане узнавал бы о
 *    версии только на следующие сутки.
 *
 * Скачивание отсюда не запускается никогда — только по нажатию пользователя
 * (этап 6). Пятнадцать мегабайт по мобильному интернету без спроса не прощают.
 */
object UpdateCheckCoordinator {

    const val WORK_NAME = "update_check"

    /** Разовая проверка по пушу — отдельно от суточной, чтобы не сбивать её расписание. */
    const val PUSH_WORK_NAME = "update_check_push"

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
        val preferences = UpdatePreferences.get(appContext)
        when (status) {
            is UpdateStatus.Available -> {
                Log.i(TAG, "Есть обновление: ${status.info.version.versionName}")
                // Найденное предложение переживает закрытие приложения: экран
                // «О программе» поднимает его при открытии, и тап по
                // уведомлению приводит сразу к кнопке «Обновить», а не к
                // пустому экрану с повторной проверкой.
                preferences.availableUpdate = status.info
                // Правило «об одной версии говорим один раз» и проверка
                // «пропустили» живут внутри UpdateNotifier — здесь не дублируем.
                UpdateNotifier.notifyIfNeeded(appContext, status.info)
            }

            is UpdateStatus.UpToDate -> {
                // Новее нечего — значит и предлагать нечего: релиз откатили или
                // обновились мимо приложения. Оставленная запись предлагала бы
                // скачать APK, которого в релизе уже нет.
                preferences.availableUpdate = null
                Log.i(TAG, "Обновлений нет")
            }
            is UpdateStatus.Throttled -> Log.i(TAG, "Проверяли меньше суток назад, GitHub не трогаем")
            is UpdateStatus.Blocked -> Log.i(TAG, "Самообновление выключено: ${status.why}")
            is UpdateStatus.Failed -> Log.w(TAG, "Проверка не удалась: ${status.failure}")
        }
        return status
    }

    /**
     * Пуш «вышел релиз». Вызывается из `NeiroFirebaseMessagingService`, то
     * есть с фонового потока FCM и в любом состоянии приложения.
     *
     * В сеть отсюда не ходим: у обработчика пуша считанные секунды, а проверка
     * тянет за собой GitHub, скачивание заметок и уведомление. Ставим разовую
     * работу — тот же [UpdateCheckWorker], но с `force`, потому что суточный
     * троттлинг здесь бессмысленен: релиз уже опубликован.
     *
     * @param versionName версия из пуша (`0.2.2`) или null, если сервер её не
     * прислал. Нужна только чтобы не дёргать GitHub из-за новости о версии,
     * которая на телефоне уже стоит — так бывает у того, кто обновился первым.
     */
    fun onUpdatePush(context: Context, versionName: String?) {
        val appContext = context.applicationContext

        val blocked = UpdateChannelGate.blockReason(appContext)
        if (blocked != null) {
            Log.i(TAG, "Пуш о релизе пришёл, но самообновление выключено: $blocked")
            return
        }

        // Выключенная автопроверка — это «не ходи в GitHub сам», и пуш её не
        // отменяет: у пользователя остаётся кнопка на экране «О программе».
        if (!UpdatePreferences.get(appContext).isAutoCheckEnabled) {
            Log.i(TAG, "Пуш о релизе пришёл, но автопроверка выключена")
            return
        }

        // Версия не разобралась — проверяем: пуш пришёл, значит релиз был, а
        // разошедшийся формат версии не повод пропустить обновление.
        val pushed = versionName?.let { ReleaseVersion.parseName(it) }
        if (pushed != null && !pushed.isNewerThan(BuildConfig.VERSION_CODE)) {
            Log.i(TAG, "Пуш о версии ${pushed.versionName}: она не новее установленной")
            return
        }

        enqueuePushCheck(appContext)
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

    private fun enqueuePushCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            // Разовый запрос к GitHub и уведомление — квоты expedited на такое
            // тратить незачем, но и ждать общего окна WorkManager не хочется.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(UpdateCheckWorker.KEY_FORCE to true))
            .setConstraints(constraints)
            .build()

        // REPLACE, а не APPEND: два пуша подряд (перевыпуск того же релиза) —
        // одна новость, и ходить к GitHub дважды незачем. Батарею здесь, в
        // отличие от суточной работы, не сторожим: проверка разовая.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            PUSH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
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
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(WORK_NAME)
            // Снимаем и разовую: выключили автопроверку — значит и работа,
            // поставленная пушем минуту назад, в GitHub уже не идёт.
            cancelUniqueWork(PUSH_WORK_NAME)
        }
    }
}
