# Этап 2 — Sync и Push (критический)

**Уровень риска:** **Высокий**. Меняем поведение фоновой синхронизации и push. Тестируется на реальном устройстве: вход/выход YClients, перезагрузка телефона, переход в фон/обратно, отсутствие интернета на 5–10 минут.
**Зависимости:** Этап 1 (ProGuard `-keep` для push моделей).
**Acceptance:**
- [ ] `recordLivePoll`/`recordFullLiveSync` вызываются **после** удачного merge + save.
- [ ] Все unique workers используют `ExistingWorkPolicy.KEEP` (`PushSyncCoordinator`, `PushKeepAliveCoordinator`, `LiveApiCoordinator`).
- [ ] `LiveApiRefreshWorker` всегда планирует следующий запуск (`try/finally`), независимо от exception.
- [ ] `LiveApiPollSchedule.DAY_INTERVAL_MINUTES = 5L` (или объяснено почему 1).
- [ ] `LogoutCoordinator` — единственная точка логаута; `AuthViewModel.logout` и `SyncViewModel.logoutYClients` вызывают только его.
- [ ] `PushDeviceId.get` thread-safe (`@Synchronized` + `commit()`).
- [ ] `PushRegistrar.registerWithToken` различает 4xx/5xx, retry только на 5xx и сетевых ошибках.
- [ ] `fetchFcmToken` корректно реагирует на cancellation (`invokeOnCancellation`).
- [ ] `SyncPreferences.recordLivePoll`/`recordFullLiveSync`/`recordSuccessfulSync` используют `commit()`.

---

## Файлы для правки

1. `app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt` — перенос watermark.
2. `app/src/main/java/ru/greemlab/neiro/sync/SyncPreferences.kt` — `commit()` для watermark.
3. `app/src/main/java/ru/greemlab/neiro/sync/LiveApiPollSchedule.kt` — интервал 5 минут.
4. `app/src/main/java/ru/greemlab/neiro/sync/LiveApiRefreshWorker.kt` — `try/finally`.
5. `app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt` — `KEEP`.
6. `app/src/main/java/ru/greemlab/neiro/push/PushSyncCoordinator.kt` — `KEEP`.
7. `app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveCoordinator.kt` — `KEEP`.
8. `app/src/main/java/ru/greemlab/neiro/push/PushDeviceId.kt` — `@Synchronized` + `commit()`.
9. `app/src/main/java/ru/greemlab/neiro/push/PushRegistrar.kt` — retry policy + cancellation.
10. `app/src/main/java/ru/greemlab/neiro/auth/LogoutCoordinator.kt` *(новый)*
11. `app/src/main/java/ru/greemlab/neiro/ui/auth/AuthViewModel.kt` — `LogoutCoordinator`.
12. `app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt` — `LogoutCoordinator`.

---

## 2.1 Перенос `recordLivePoll`/`recordFullLiveSync` после merge

**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt`

### Проблема

В `refreshLiveIncrementalLocked` `syncPreferences.recordLivePoll()` вызывается сразу после получения данных, **до** `mergeIncrementalRecords`. Если merge упадёт (DataStore не записал), watermark уйдёт вперёд, и в следующий incremental запрос YClients не вернёт «потерянные» записи — мы пропустим события навсегда (до полного периодического `applyFullLiveSync`).

Аналогично в `applyFullLiveSync` — `recordLivePoll` и `recordFullLiveSync` ставятся даже если merge внутри `syncDateRangeLocked` упал.

### Сейчас (строки 74-84, 86-130)

```74:84:app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt
    private suspend fun applyFullLiveSync(
        startDate: LocalDate,
        endDate: LocalDate,
    ): SyncOutcome {
        val outcome = syncDateRangeLocked(startDate, endDate, recordSuccessfulSync = false)
        if (outcome is SyncOutcome.Success) {
            syncPreferences.recordLivePoll()
            syncPreferences.recordFullLiveSync()
        }
        return outcome
    }
```

В `applyFullLiveSync` уже всё правильно: watermark обновляется только если `SyncOutcome.Success`. **Оставить как есть.**

```96:130:app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt
        when (
            val result = yclientsRepository.getRecordsChangedSince(
                changedAfter = changedAfter,
                startDate = startDate,
                endDate = endDate,
            )
        ) {
            is ApiResult.Success -> {
                syncPreferences.recordLivePoll()
                val records = result.data
                if (records.isEmpty()) {
                    return SyncOutcome.Success(0)
                }

                val dayDataBefore = calendarRepository.dayDataFlow.first()
                autoFillProfile(records)
                val outcome = mergeIncrementalRecords(
                    records = records,
                    rangeStart = startDate,
                    rangeEnd = endDate,
                )
                if (outcome is SyncOutcome.Failure) {
                    return outcome
                }
                val syncedCount = (outcome as SyncOutcome.Success).newlyAdded
                val dayDataAfter = calendarRepository.dayDataFlow.first()
                SessionNotificationCoordinator.onCalendarUpdatedFromApi(
                    appContext,
                    dayDataBefore,
                    dayDataAfter,
                )
                return SyncOutcome.Success(syncedCount)
            }

            is ApiResult.Error -> return applyFullLiveSync(startDate, endDate)
        }
```

### Правка

Переместить `syncPreferences.recordLivePoll()` **после** `mergeIncrementalRecords`:

```kotlin
        when (
            val result = yclientsRepository.getRecordsChangedSince(
                changedAfter = changedAfter,
                startDate = startDate,
                endDate = endDate,
            )
        ) {
            is ApiResult.Success -> {
                val records = result.data
                if (records.isEmpty()) {
                    syncPreferences.recordLivePoll()
                    return SyncOutcome.Success(0)
                }

                val dayDataBefore = calendarRepository.dayDataFlow.first()
                autoFillProfile(records)
                val outcome = mergeIncrementalRecords(
                    records = records,
                    rangeStart = startDate,
                    rangeEnd = endDate,
                )
                if (outcome is SyncOutcome.Failure) {
                    return outcome
                }
                val syncedCount = (outcome as SyncOutcome.Success).newlyAdded
                syncPreferences.recordLivePoll()
                val dayDataAfter = calendarRepository.dayDataFlow.first()
                SessionNotificationCoordinator.onCalendarUpdatedFromApi(
                    appContext,
                    dayDataBefore,
                    dayDataAfter,
                )
                return SyncOutcome.Success(syncedCount)
            }

            is ApiResult.Error -> return applyFullLiveSync(startDate, endDate)
        }
```

**Почему:** Watermark = «всё что у YClients было до этого момента — успешно сохранено локально». Если merge не дошёл до диска — watermark ещё не сдвигаем.

**Коммит:** `Перенёс recordLivePoll после успешного merge`

---

## 2.2 `SyncPreferences` — `commit()` для watermarks

**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/SyncPreferences.kt`

### Проблема

`recordLivePoll`, `recordFullLiveSync`, `recordSuccessfulSync` используют `.apply()` — асинхронная запись. Если процесс упадёт между `apply` и фактической записью на диск, watermark не сохранится → дублирование запросов. Для критичных watermark используем `commit()` (синхронно).

### Правка

В `SyncPreferences.kt:27-29, 56-58, 63-65` заменить `.apply()` на `.commit()` для трёх методов.

**Сейчас:**

```27:29:app/src/main/java/ru/greemlab/neiro/sync/SyncPreferences.kt
    fun recordSuccessfulSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_EPOCH, atMillis).apply()
    }
```

```56:58:app/src/main/java/ru/greemlab/neiro/sync/SyncPreferences.kt
    fun recordLivePoll(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_LIVE_POLL_EPOCH, atMillis).apply()
    }
```

```63:65:app/src/main/java/ru/greemlab/neiro/sync/SyncPreferences.kt
    fun recordFullLiveSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FULL_LIVE_SYNC_EPOCH, atMillis).apply()
    }
```

**Заменить на (для каждого из трёх методов):**

```kotlin
    fun recordSuccessfulSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_EPOCH, atMillis).commit()
    }

    fun recordLivePoll(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_LIVE_POLL_EPOCH, atMillis).commit()
    }

    fun recordFullLiveSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FULL_LIVE_SYNC_EPOCH, atMillis).commit()
    }
```

**Почему:** Вызываются они из background-thread (`CoroutineWorker.doWork`, `viewModelScope.launch(Dispatchers.IO)` или внутри `syncMutex.withLock` в sync). Синхронная запись здесь приемлема, а потеря watermark существенно дороже одного `commit()`.

Остальные `apply()` (isAutoSyncEnabled, clearSyncState, etc.) оставить как есть — потеря non-critical.

**Коммит:** `Перешёл на commit() для watermarks синхронизации`

---

## 2.3 `LiveApiPollSchedule.DAY_INTERVAL_MINUTES = 5L`

**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/LiveApiPollSchedule.kt`
**Строка:** 20

### Проблема

`DAY_INTERVAL_MINUTES = 1L` — каждую минуту приложение бьёт по YClients API в foreground. Это нагрузка на batter + квоты YClients. Достаточно 5 минут (в реальности FCM — основной канал, polling — fallback).

### Сейчас:

```20:21:app/src/main/java/ru/greemlab/neiro/sync/LiveApiPollSchedule.kt
    const val DAY_INTERVAL_MINUTES = 1L
    const val NIGHT_INTERVAL_MINUTES = 60L
```

### Заменить на:

```kotlin
    const val DAY_INTERVAL_MINUTES = 5L
    const val NIGHT_INTERVAL_MINUTES = 60L
```

**Почему:** Push (FCM) обеспечивает мгновенные обновления. Polling — это fallback на случай если push не дошёл (Doze, экономия батареи). 5 минут более чем достаточно.

**Коммит:** `Поднял интервал live-опроса до 5 минут`

---

## 2.4 `LiveApiRefreshWorker` — try/finally + проверка outcome

**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/LiveApiRefreshWorker.kt`

### Проблема

Если `refreshLiveRange()` бросит exception (например NPE из-за бага), цепочка `scheduleNextBackgroundRefresh` оборвётся → фоновое обновление умрёт до перезапуска приложения.

### Сейчас (1-26):

```1:26:app/src/main/java/ru/greemlab/neiro/sync/LiveApiRefreshWorker.kt
package ru.greemlab.neiro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Фоновая подтяжка записей с YClients; после выполнения планирует следующий запуск
 * с интервалом по [LiveApiPollSchedule].
 */
class LiveApiRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!YClientsRepository.getInstance(applicationContext).isLoggedIn.value) {
            return Result.success()
        }

        YClientsCalendarSync.get(applicationContext).refreshLiveRange()
        LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
        return Result.success()
    }
}
```

### Заменить полностью на:

```kotlin
package ru.greemlab.neiro.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Фоновая подтяжка записей с YClients; после выполнения планирует следующий запуск
 * с интервалом по [LiveApiPollSchedule].
 *
 * Гарантирует, что следующий запуск планируется даже при exception — иначе
 * цепочка обновлений ломается до перезапуска приложения.
 */
class LiveApiRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!YClientsRepository.getInstance(applicationContext).isLoggedIn.first()) {
            return Result.success()
        }

        val outcome = runCatching {
            YClientsCalendarSync.get(applicationContext).refreshLiveRange()
        }

        try {
            return when {
                outcome.isFailure -> {
                    Log.w(TAG, "refreshLiveRange threw", outcome.exceptionOrNull())
                    Result.retry()
                }
                outcome.getOrNull() is SyncOutcome.Failure -> {
                    Result.retry()
                }
                else -> Result.success()
            }
        } finally {
            LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
        }
    }

    private companion object {
        const val TAG = "LiveApiRefreshWorker"
    }
}
```

**Почему:**
1. `try/finally` гарантирует `scheduleNextBackgroundRefresh`.
2. `SyncOutcome.Failure` → `Result.retry()` (WorkManager сам сделает retry с backoff).
3. `isLoggedIn.first()` вместо `.value` — корректно для подвисших InitFlow (минорно, но единообразно с другими местами).

**Коммит:** `Добавил try/finally и retry в LiveApiRefreshWorker`

---

## 2.5 `LiveApiCoordinator` — `KEEP` вместо `REPLACE`

**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt`
**Строки:** 128-132

### Проблема

`ExistingWorkPolicy.REPLACE` отменяет уже запущенный worker. Сценарий:
- Worker A начал `refreshLiveRange`, выполнил merge на полпути.
- Приходит FCM, вызывает `LiveApiCoordinator.scheduleNextBackgroundRefresh` (или logout/login переключение).
- `REPLACE` отменяет A → merge оборван → состояние неконсистентно.

`KEEP` оставляет уже запущенный worker и не enqueues новый, если есть тот же uniqueWorkName. Но мы хотим **обязательно** запустить следующий — поэтому используем `APPEND_OR_REPLACE` или `KEEP` с проверкой задержки.

**Решение:** `ExistingWorkPolicy.KEEP` — если worker уже запланирован/выполняется, новый игнорируется. Цепочка `scheduleNextBackgroundRefresh` всегда вызывается из самого worker'а — следующий запуск точно будет.

### Сейчас:

```128:132:app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BACKGROUND_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
```

### Заменить на:

```kotlin
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BACKGROUND_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
```

**Коммит:** `Сменил REPLACE на KEEP для LiveApiCoordinator`

---

## 2.6 `PushSyncCoordinator` — `KEEP` вместо `REPLACE`

**Файл:** `app/src/main/java/ru/greemlab/neiro/push/PushSyncCoordinator.kt`
**Строки:** 20-24

### Проблема

FCM-сервис может несколько раз подряд получать `sync` payload (доставка с retries). Каждый `enqueue` с `REPLACE` отменяет уже запущенный worker, мерж рвётся.

### Сейчас:

```20:24:app/src/main/java/ru/greemlab/neiro/push/PushSyncCoordinator.kt
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
```

### Заменить на:

```kotlin
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
```

**Почему:** Если sync уже идёт — повторный FCM игнорируется (refresh всё равно подтянет свежие данные). Если предыдущий завершён — новый запустится. `KEEP` не отменяет mid-flight merge.

**Коммит:** `Сменил REPLACE на KEEP для PushSyncCoordinator`

---

## 2.7 `PushKeepAliveCoordinator` — `KEEP` вместо `REPLACE`

**Файл:** `app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveCoordinator.kt`
**Строки:** 31-35

### Сейчас:

```31:35:app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveCoordinator.kt
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
```

### Заменить на:

```kotlin
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
```

**Коммит:** `Сменил REPLACE на KEEP для PushKeepAliveCoordinator`

---

## 2.8 `PushDeviceId.get` — thread-safe + commit()

**Файл:** `app/src/main/java/ru/greemlab/neiro/push/PushDeviceId.kt`

### Проблема

Если два потока одновременно зашли первый раз — оба сгенерируют разный UUID, последний `apply()` выиграет. Push-сервер получит два разных device_id с одного устройства → дубликаты в БД, лишние FCM.

### Сейчас (1-27):

```1:27:app/src/main/java/ru/greemlab/neiro/push/PushDeviceId.kt
package ru.greemlab.neiro.push

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

object PushDeviceId {

    private const val PREFS = "neiro_push_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

        val generated = androidId ?: UUID.randomUUID().toString()
        val deviceId = "neiro-${Build.MODEL}-$generated".take(120)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }
}
```

### Заменить полностью на:

```kotlin
package ru.greemlab.neiro.push

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

object PushDeviceId {

    private const val PREFS = "neiro_push_device"
    private const val KEY_DEVICE_ID = "device_id"

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }

        val sanitizedModel = Build.MODEL.orEmpty()
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(40)

        val generated = androidId ?: UUID.randomUUID().toString()
        val deviceId = "neiro-$sanitizedModel-$generated".take(120)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).commit()
        return deviceId
    }
}
```

**Почему:**
- `@Synchronized` — мьютекс на объект, защищает от race.
- `commit()` — синхронная запись (нужна перед `return`, чтобы следующий вызов гарантированно увидел значение).
- Sanitize `Build.MODEL` — у некоторых OEM модель содержит спецсимволы (`/`, пробелы), портит URL/headers.

**Коммит:** `Сделал PushDeviceId thread-safe`

---

## 2.9 `PushRegistrar` — retry policy + cancellation

**Файл:** `app/src/main/java/ru/greemlab/neiro/push/PushRegistrar.kt`

### 2.9.1 Retry только на 5xx и сетевых ошибках

**Сейчас (89-120):**

```89:120:app/src/main/java/ru/greemlab/neiro/push/PushRegistrar.kt
    private suspend fun registerWithToken(context: Context, fcmToken: String): Boolean {
        val api = PushClient.getApi() ?: return false
        val repository = YClientsRepository.getInstance(context)
        val staffId = repository.staffId ?: return false
        val userToken = repository.userToken ?: return false
        val partnerToken = repository.partnerToken
        if (partnerToken.isBlank()) return false

        val body = RegisterDeviceRequest(
            deviceId = PushDeviceId.get(context),
            fcmToken = fcmToken,
            companyId = repository.companyId,
            staffId = staffId,
            partnerToken = partnerToken,
            userToken = userToken,
            label = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
        )

        return withContext(Dispatchers.IO) {
            repeat(REGISTER_RETRY_COUNT) { attempt ->
                val ok = runCatching {
                    api.registerDevice(PushClient.authHeader(), body).isSuccessful
                }.getOrDefault(false)
                if (ok) return@withContext true
                if (attempt < REGISTER_RETRY_COUNT - 1) {
                    delay(1_000L * (attempt + 1))
                }
            }
            false
        }
    }
```

**Заменить на:**

```kotlin
    private suspend fun registerWithToken(context: Context, fcmToken: String): Boolean {
        val api = PushClient.getApi() ?: return false
        val repository = YClientsRepository.getInstance(context)
        val staffId = repository.staffId ?: return false
        val userToken = repository.userToken ?: return false
        val partnerToken = repository.partnerToken
        if (partnerToken.isBlank()) return false

        val body = RegisterDeviceRequest(
            deviceId = PushDeviceId.get(context),
            fcmToken = fcmToken,
            companyId = repository.companyId,
            staffId = staffId,
            partnerToken = partnerToken,
            userToken = userToken,
            label = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
        )

        return withContext(Dispatchers.IO) {
            repeat(REGISTER_RETRY_COUNT) { attempt ->
                val outcome = runCatching {
                    api.registerDevice(PushClient.authHeader(), body)
                }
                val response = outcome.getOrNull()
                when {
                    response != null && response.isSuccessful -> return@withContext true
                    response != null && response.code() in 400..499 -> {
                        // 4xx: невалидный токен/payload — retry бесполезен.
                        return@withContext false
                    }
                    attempt < REGISTER_RETRY_COUNT - 1 -> {
                        delay(1_000L * (1 shl attempt))
                    }
                }
            }
            false
        }
    }
```

**Почему:**
- 4xx (`401 Unauthorized`, `400 Bad Request`) — баг на стороне клиента, retry не помогает.
- Сетевые/5xx — retry с exponential backoff (1s, 2s, 4s) вместо линейного (1s, 2s, 3s).

### 2.9.2 `fetchFcmToken` — корректный cancellation

**Сейчас (132-139):**

```132:139:app/src/main/java/ru/greemlab/neiro/push/PushRegistrar.kt
    private suspend fun fetchFcmToken(): String? {
        if (!PushConfig.isFcmEnabled) return null
        return suspendCancellableCoroutine { cont ->
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        }
    }
```

**Заменить на:**

```kotlin
    private suspend fun fetchFcmToken(): String? {
        if (!PushConfig.isFcmEnabled) return null
        return suspendCancellableCoroutine { cont ->
            val task = com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            task.addOnSuccessListener { value ->
                if (cont.isActive) cont.resume(value)
            }
            task.addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation {
                // Firebase Task нельзя отменить, но мы перестаём слушать.
            }
        }
    }
```

**Почему:** Если корутина отменена раньше, чем FCM ответит, `cont.resume()` бросит `IllegalStateException`. Проверяем `cont.isActive`.

**Коммит:** `Уточнил retry-политику и cancellation push регистрации`

---

## 2.10 `LogoutCoordinator` — единая точка логаута

**Файл:** `app/src/main/java/ru/greemlab/neiro/auth/LogoutCoordinator.kt` *(создать)*

### Проблема

Сейчас две точки логаута:
1. `AuthViewModel.logout`: `PushRegistrar.onLogout` + `repository.logout` — **не очищает sync state**, **не отменяет WorkManager-задачи**.
2. `SyncViewModel.logoutYClients`: `repository.logout` + `syncPreferences.clearSyncState` + `cancelLegacyPeriodicSync` — **не дёргает PushRegistrar.onLogout**, **не отменяет live worker**, **не отменяет notifications**.

Создаём `LogoutCoordinator.logout(context)` — единственный метод, обе ViewModel вызывают только его.

### Создать файл:

```kotlin
package ru.greemlab.neiro.auth

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.push.PushKeepAliveCoordinator
import ru.greemlab.neiro.push.PushRegistrar
import ru.greemlab.neiro.sync.AutoSyncCoordinator
import ru.greemlab.neiro.sync.SyncPreferences

/**
 * Единственная точка логаута YClients.
 *
 * Делает в порядке:
 * 1. Останавливает периодические задачи (auto-sync, live API, push keepalive, notifications).
 * 2. Отзывает регистрацию устройства на push-сервере.
 * 3. Чистит локальные токены и watermark sync.
 * 4. Сбрасывает состояние уведомлений (baseline, dedupe).
 *
 * Профиль, архивный календарь и тема НЕ затрагиваются.
 */
object LogoutCoordinator {

    suspend fun logout(context: Context) {
        val appContext = context.applicationContext

        AutoSyncCoordinator.cancelLegacyPeriodicSync(appContext)
        cancelLiveApiWorker(appContext)
        PushKeepAliveCoordinator.cancel(appContext)
        SessionNotificationCoordinator.onLoggedOut(appContext)

        PushRegistrar.onLogout(appContext)

        YClientsRepository.getInstance(appContext).logout()
        SyncPreferences.get(appContext).clearSyncState()
    }

    private fun cancelLiveApiWorker(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LIVE_API_WORK_NAME)
    }

    private const val LIVE_API_WORK_NAME = "yclients_live_api_refresh"
}
```

> **Зависимость:** `SessionNotificationCoordinator.onLoggedOut(context)` должен существовать. Если его нет — в Этапе 3 будет описано его создание. Сейчас можно временно вызывать `SessionNotificationCoordinator.cancelAll(context)` или похожий уже существующий метод. **Проверить grep'ом** наличие метода в `SessionNotificationCoordinator.kt` и при отсутствии — закомментировать строку до Этапа 3 + добавить TODO с пометкой «реализовать в ETAP_3».

**Коммит:** `Добавил LogoutCoordinator как единую точку`

---

## 2.11 `AuthViewModel.logout` через `LogoutCoordinator`

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/auth/AuthViewModel.kt`

### Сейчас (157-165):

```157:165:app/src/main/java/ru/greemlab/neiro/ui/auth/AuthViewModel.kt
    fun logout() {
        PushRegistrar.onLogout(getApplication())
        repository.logout()
        _uiState.value = _uiState.value.copy(
            isLoggedIn = false,
            userName = null,
            password = "",
        )
    }
```

### Заменить на:

```kotlin
    fun logout() {
        viewModelScope.launch {
            LogoutCoordinator.logout(getApplication())
            _uiState.value = _uiState.value.copy(
                isLoggedIn = false,
                userName = null,
                password = "",
            )
        }
    }
```

### И обновить импорты (top of file):

Заменить:

```kotlin
import ru.greemlab.neiro.push.PushRegistrar
```

На:

```kotlin
import ru.greemlab.neiro.auth.LogoutCoordinator
```

(Если `PushRegistrar` используется ещё в `onLoginSuccess` — оставить оба импорта.)

**Коммит:** `Перевёл AuthViewModel на LogoutCoordinator`

---

## 2.12 `SyncViewModel.logoutYClients` через `LogoutCoordinator`

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt`

### Сейчас (65-70):

```65:70:app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt
    fun logoutYClients() {
        yclientsRepository.logout()
        syncPreferences.clearSyncState()
        AutoSyncCoordinator.cancelLegacyPeriodicSync(getApplication())
        _uiState.value = SyncUiState()
    }
```

### Заменить на:

```kotlin
    fun logoutYClients() {
        viewModelScope.launch {
            LogoutCoordinator.logout(getApplication())
            _uiState.value = SyncUiState()
        }
    }
```

### Импорты (top of file):

Добавить:

```kotlin
import ru.greemlab.neiro.auth.LogoutCoordinator
```

Можно удалить `AutoSyncCoordinator` если он больше не используется в этом файле (проверить — он используется в `setAutoSyncEnabled`, `devLogin`, `devFullSetup`).

**Коммит:** `Перевёл SyncViewModel на LogoutCoordinator`

---

## Финальная проверка этапа

1. **`ReadLints`** для всех правленых файлов.
2. Поиск `ExistingWorkPolicy.REPLACE` по всему `app/src/main/java/ru/greemlab/neiro/`:
   ```
   rg 'ExistingWorkPolicy\.REPLACE' app/src/main
   ```
   Должно остаться **только** в местах, где `REPLACE` оправдан (на момент аудита таких не было — должно быть пусто). Если что-то осталось — добавить комментарий, зачем `REPLACE`.
3. Поиск `repository.logout()` напрямую:
   ```
   rg 'repository\.logout\(' app/src/main
   ```
   Должен быть **только** внутри `LogoutCoordinator`. Если ещё есть — заменить на `LogoutCoordinator.logout()`.
4. Поиск `syncPreferences.clearSyncState`:
   ```
   rg 'clearSyncState' app/src/main
   ```
   Должен быть **только** в `LogoutCoordinator` и в самом `SyncPreferences`.
5. Поиск `PushRegistrar.onLogout`:
   ```
   rg 'PushRegistrar\.onLogout' app/src/main
   ```
   Должен быть **только** в `LogoutCoordinator`.

## Коммиты этапа (порядок)

1. `Перенёс recordLivePoll после успешного merge`
2. `Перешёл на commit() для watermarks синхронизации`
3. `Поднял интервал live-опроса до 5 минут`
4. `Добавил try/finally и retry в LiveApiRefreshWorker`
5. `Сменил REPLACE на KEEP для LiveApiCoordinator`
6. `Сменил REPLACE на KEEP для PushSyncCoordinator`
7. `Сменил REPLACE на KEEP для PushKeepAliveCoordinator`
8. `Сделал PushDeviceId thread-safe`
9. `Уточнил retry-политику и cancellation push регистрации`
10. `Добавил LogoutCoordinator как единую точку`
11. `Перевёл AuthViewModel на LogoutCoordinator`
12. `Перевёл SyncViewModel на LogoutCoordinator`
