# Приложение под сервис событий: что менять

Детализация этапа 8 из [push-events-plan.md](push-events-plan.md). План описывает
сервер подробно, а приложение — четырьмя таблицами; здесь развёрнуто то, что
исполнителю иначе пришлось бы додумывать на месте.

Составлен 25.07.2026 по коду на коммите `346e24b`. Этот документ **не отменяет**
план, а раскрывает его §8. Если они разойдутся — прав план в части архитектуры,
прав этот документ в части кода приложения.

**Одно решение отменяет план сознательно:** §5 — push применяет изменение к
календарю из payload. В плане было «календарь обновляется только при открытии
приложения»; решение пользователя от 25.07.2026 — обновлять сразу, но по-прежнему
не ходя в YClients.

---

## 1. Как уведомление живёт сейчас

```
FCM {action:"sync"} → NeiroFirebaseMessagingService.onMessageReceived
                    → PushSyncCoordinator.enqueue → PushSyncWorker
                    → YClientsCalendarSync.refreshLiveRange()   ← поход в YClients
                    → SessionNotificationCoordinator.onCalendarUpdatedFromApi
                    → CalendarSessionSnapshot.from(dayData до/после)
                    → SessionChangeDetector.detect
                    → фильтры prefs → SessionNotificationDisplay.showEvents
```

Ключевые точки:

| Файл | Роль |
|---|---|
| [NeiroFirebaseMessagingService.kt:12](../app/src/main/java/ru/greemlab/neiro/push/NeiroFirebaseMessagingService.kt#L12) | Единственная ветка `action == "sync"` |
| [SessionNotificationCoordinator.kt:191](../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt#L191) | `processSnapshotTransition` — эталон хвоста «фильтры → показ → mark» |
| [SessionChangeDetector.kt](../app/src/main/java/ru/greemlab/neiro/notifications/SessionChangeDetector.kt) | Локальный дифф снимков |
| [TrackedSession.kt:53](../app/src/main/java/ru/greemlab/neiro/notifications/TrackedSession.kt#L53) | `SessionEvent.dedupeKey` — общий предохранитель от дублей |

**Что из этого сохраняется.** Весь хвост после `SessionEvent`: детектор для
локального пути, дисплей, тексты, in-app лента, архив, настройки типов,
напоминания и дайджесты. Новый путь обязан **строить настоящий `SessionEvent`**
и вливаться в тот же хвост, а не заводить параллельный показ.

---

## 2. Контракт, который нельзя нарушить

Приложение узнаёт занятие не по `record_id` (его в модели уведомлений нет), а по
`slotKey` = `нормализованное имя | дата | HH:mm | kind`
([SessionSlotKey.kt:20](../app/src/main/java/ru/greemlab/neiro/notifications/SessionSlotKey.kt#L20)).
Из `slotKey` собирается `dedupeKey`, а `dedupeKey` — единственное, что не даёт
показать одно событие дважды, когда оно пришло и push'ом, и локальным диффом.

Отсюда три жёстких требования к payload сервера. Ни одно из них в плане явно не
записано — если сервер сделает иначе, дубли поедут молча.

### 2.1 `time` — локальное время из `datetime`, без пересчёта часового пояса

Приложение берёт `HH:MM` подстрокой из `record.datetime`, никаких TZ-конверсий:

```kotlin
// YClientsCalendarSync.kt:794 formatRecordTime
LocalTime.parse(datetime.substringAfter("T").take(5))
```

Сервер обязан делать ровно то же. Приведение к UTC сдвинет `slotKey` на три часа
и превратит каждое событие в дубль.

### 2.2 `client_name` — по той же логике, что `extractClientName`

```kotlin
// YClientsCalendarSync.kt:700
client.displayName ?: (client.name + " " + client.surname)
```

Нормализация ключа
([UpcomingSession.kt:185](../app/src/main/java/ru/greemlab/neiro/notifications/UpcomingSession.kt#L185))
сортирует токены и гасит `ё`/пунктуацию, поэтому «Иванов Ваня» и «Ваня Иванов»
совпадут. Но `displayName = "Ваня"` против `name+surname = "Ваня Иванов"` — уже
разные ключи. Порядок предпочтения полей должен совпадать.

### 2.3 `kind` — «диагностика» без учёта регистра в любой из `services`

Как в [YClientsCalendarSync.kt:722](../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt#L722).
`kind` входит в `slotKey`: занятие и диагностика одного клиента в одно время —
разные слоты.

### 2.4 Приоритет FCM

Data-only сообщение должно уходить с `android.priority = "high"`, иначе в Doze
доставка откладывается на часы и весь смысл payload'а теряется. Для приложения,
убитого через «Принудительная остановка», FCM не доставляется вообще — это
чинится только догоном (§6).

---

## 3. Этап A — убрать локальный опрос (план 8.1)

Готовый диф лежит в `git stash` (`stash@{0}`), собран 25.07.2026 и соответствует
плану. Проще применить его, чем писать заново:

```bash
git stash show -p stash@{0} | git apply
```

Состав:

| Файл | Что делать |
|---|---|
| `sync/LiveApiRefreshWorker.kt` | Удалить файл |
| `sync/LiveApiCoordinator.kt` | Убрать `startForegroundPolling` / `stopForegroundPolling` / `foregroundPollJob`, `scheduleBackgroundRefresh`, `cancelBackgroundRefresh`, `scheduleNextBackgroundRefresh`, константу `BACKGROUND_WORK_NAME`. Остаётся `refreshNow` при логине и в `onStart` |
| `push/PushKeepAliveWorker.kt` | Убрать `refreshLiveRange()`; обернуть тело в `try/finally` с `if (!isStopped) scheduleNext(...)` |
| `auth/LogoutCoordinator.kt` | Убрать `cancelLiveApiWorker` |
| `sync/AutoSyncCoordinator.kt` | Поправить KDoc |

**Про `finally`.** Сейчас в
[PushKeepAliveWorker.kt:38](../app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveWorker.kt#L38)
ранний `return Result.retry()` проскакивает мимо `scheduleNext` — после первой
сетевой ошибки цепочка keepalive умирает до перезапуска приложения. Это и был баг
«пришло одно уведомление и тишина» из `51d8fe3`. `scheduleNext` обязан стоять в
`finally`; `isStopped` в условии оставить — иначе logout будет воскрешать цепочку.

**Что при этом теряется осознанно.** Пока календарь открыт, он больше не
опрашивает YClients по таймеру. Актуальность держится иначе: точечное применение
события из payload (§5) и полный синк при `onStart`. Локальный опрос обратно не
возвращать ([push-events-plan.md §12.2](push-events-plan.md)).

**Приёмка:** уведомления приходят, `LiveApiRefreshWorker` в
`WorkManager` больше не появляется, keepalive продолжает вставать в очередь после
обрыва сети.

---

## 4. Этап B — показ уведомления из payload (план 8.2)

### 4.1 `push/PushSessionEvent.kt` (новый)

Модель события из payload плюс перевод в доменный `SessionEvent`.

```kotlin
data class PushSessionEvent(
    val id: Long,
    val type: String,
    val clientName: String,
    val date: String,      // YYYY-MM-DD
    val time: String,      // HH:MM
    val kind: String,      // LESSON | DIAGNOSTICS
    val prevDate: String?,
    val prevTime: String?,
)
```

`toSessionEvent(): SessionEvent?` — `null`, если тип неизвестен или дата/время не
парсятся (сервер новее приложения; молча игнорировать, не падать).

Сборка `TrackedSession`:

| Поле | Значение |
|---|---|
| `date`, `startTime` | из payload |
| `endTime` | `startTime + SESSION_DURATION_MINUTES` (50, [ScheduleTime.kt:27](../app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/ScheduleTime.kt#L27)) |
| `clientName` | из payload, как есть |
| `kind` | `UpcomingSessionKind.valueOf(kind)` |
| `status` | по типу события, таблица ниже |
| `isMarkedDeleted` | `type == DELETED` |

Маппинг `status` — не косметика: он входит в `dedupeKey` для двух типов
([TrackedSession.kt:53](../app/src/main/java/ru/greemlab/neiro/notifications/TrackedSession.kt#L53)),
поэтому обязан совпасть с тем, что даст локальный дифф.

| Тип события | `status` | Почему |
|---|---|---|
| `CLIENT_CONFIRMED` | `CONFIRMED` | `dedupeKey = confirmed\|slot\|CONFIRMED` |
| `CLIENT_ARRIVED` | `ARRIVED` | `dedupeKey = arrived\|slot\|ARRIVED` |
| `CANCELLED` | `CANCELLED` | в ключ не входит, но верно по смыслу |
| `NEW_BOOKING`, `RESCHEDULED` | `EXPECTED` | в ключ не входит |
| `DELETED` | `EXPECTED` + `isMarkedDeleted = true` | в ключ не входит |

Коды YClients → `AttendanceStatus` смотреть в
[SessionParser.kt:41](../app/src/main/java/ru/greemlab/neiro/ui/calendar/SessionParser.kt#L41)
(`-1` → `CANCELLED`, `1` → `ARRIVED`, `2` → `CONFIRMED`).

Для `RESCHEDULED` собрать второй `TrackedSession` из `prev_date`/`prev_time` (имя
и `kind` те же) и положить в `SessionEvent.previous` — иначе `dedupeKey` получит
пустой хвост и разойдётся с локальным диффом. Если `prev_*` не пришли — событие
отбросить, а не показывать как перенос «в никуда».

### 4.2 `push/PushEventNotifier.kt` (новый)

Зеркало хвоста `processSnapshotTransition`
([SessionNotificationCoordinator.kt:214](../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt#L214)),
порядок шагов менять нельзя:

```kotlin
fun notify(context: Context, events: List<SessionEvent>) {
    val prefs = SessionNotificationPreferences.get(context)
    if (!prefs.isEnabled) return

    val ready = events
        .filter { it.session.date.isWithinHorizon() }   // см. ниже
        .filter { prefs.isTypeEnabled(it.type) }
        .filter { !prefs.wasEventNotified(it.dedupeKey) }
        .distinctBy { it.dedupeKey }
    if (ready.isEmpty()) return

    val shown = SessionNotificationDisplay.showEvents(context, ready)
    ready.filter { it.dedupeKey in shown }.forEach { prefs.markEventNotified(it.dedupeKey) }
}
```

Два места, где легко ошибиться:

- **`markEventNotified` только для показанных.** `showEvents` возвращает ключи,
  чей push система реально приняла; помечать всё подряд — значит терять события
  при отказе `NotificationManager`.
- **Горизонт.** Локальный путь работает в окне «сегодня … +60 дней»
  (`CalendarSessionSnapshot.DEFAULT_HORIZON_DAYS`,
  [TrackedSession.kt:66](../app/src/main/java/ru/greemlab/neiro/notifications/TrackedSession.kt#L66)),
  сервер такого фильтра не знает и пришлёт событие по записи на любую дату.
  Без фильтра появятся уведомления о прошлом и о занятиях за горизонтом — то, чего
  сборка 0.6.9.0 никогда не показывала.

`prefs.hasBaselineSnapshot` здесь **не проверять**: baseline защищает от лавины
при первом локальном снимке, а у push-пути такой проблемы нет — сервер уже
отсеял её сидированием.

### 4.3 `push/NeiroFirebaseMessagingService.kt`

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    when (message.data["action"]) {
        "session_events" -> { /* разбор → применение к календарю (§5) → показ → курсор */ }
        "sync_events" -> PushEventsSyncCoordinator.enqueue(applicationContext)
    }
}
```

**Ветки `"sync"` не будет** (решение пользователя от 25.07.2026): новая сборка со
старым сервисом не работает вообще, переход делается устройство за устройством —
их всего два. Вместе с веткой удаляются `push/PushSyncWorker.kt` и
`push/PushSyncCoordinator.kt`, а из `LogoutCoordinator` — строка
`cancelUniqueWork(PushSyncCoordinator.WORK_NAME)`.

### 4.4 Осиротевшие работы WorkManager

Новая сборка ставится **поверх** старой, база WorkManager при обновлении
сохраняется. После этапов A и B из кода исчезают три воркера, но их уникальные
работы останутся в очереди у обновившегося телефона и будут падать до первой
переустановки:

| Имя работы | Чей воркер |
|---|---|
| `yclients_live_api_refresh` | `LiveApiRefreshWorker`, удалён на этапе A |
| `push_fcm_sync` | `PushSyncWorker`, удалён здесь |

Отменить их один раз при старте по строковому имени — ровно так, как в проекте
уже сделано для `yclients_periodic_sync`
([AutoSyncCoordinator.kt:78](../app/src/main/java/ru/greemlab/neiro/sync/AutoSyncCoordinator.kt#L78)).
Имена держать константами рядом с этой отменой: классов, из которых их можно было
бы взять, больше нет.

**Показывать прямо в `onMessageReceived`, без Worker'а.** `onMessageReceived`
вызывается на фоновом потоке, а весь путь показа синхронный: `SharedPreferences`
в `SessionNotificationPreferences` и `InAppNotificationStore`, `NotificationManager`
в `SessionNotificationDisplay`. Похода в сеть на этом пути больше нет — ради чего
всё и затевалось. Прогонять это через WorkManager значит добавить задержку Doze к
уведомлению, которое можно показать за миллисекунды.

Разбор JSON — `Gson` (уже в зависимостях, используется в `PushClient` и
`InAppNotificationStore`). Весь разбор в `runCatching`: битый payload не должен
ронять FCM-сервис.

Курсор `last_event_id` сохранять **после** показа, максимумом с уже сохранённым
(push'и могут прийти не по порядку).

---

## 5. Этап B2 — применение события к календарю

Решение пользователя от 25.07.2026, **расхождение с планом**: в
[push-events-plan.md §4](push-events-plan.md) записано «календарь обновляется
только при открытии приложения». Отказались: уведомление «Ваня подтвердился» при
открытом календаре, где слот остался прежним, — это выглядит поломкой.

Важно, что решение **не возвращает стоимость**: календарь правится данными из
payload, в YClients приложение по-прежнему не ходит.

### 5.1 Где применять

Новый файл `push/PushEventCalendarApplier.kt`. Вызывается из FCM-сервиса и из
догона — везде, где появился разобранный `PushSessionEvent`.

Запись только через `calendarRepository.updateDayData { current -> ... }`
([CalendarDataStore.kt:230](../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt#L230)):
это атомарный read-modify-write под writer-локом, тем же механизмом пишет синк
([YClientsCalendarSync.kt:324](../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt#L324)).
Паттерн «прочитал `peekDayData` → поправил → сохранил» потеряет параллельную
правку из UI или из синка.

### 5.2 Как найти запись в дне

`dayData` — это `Map<LocalDate, List<String>>`, где строка сериализована
`SessionFormat`. Ищем в `current[date]` строку, чей `slotKey` равен `slotKey`
события: `SessionParser.parse(raw)` → имя, время, `kind` → `SessionSlotKey.build`.

Логика разбора строки в занятие уже есть в `CalendarSessionSnapshot.parseEntries`
([TrackedSession.kt:96](../app/src/main/java/ru/greemlab/neiro/notifications/TrackedSession.kt#L96)) —
вынести её в переиспользуемую функцию, а не копировать: две копии этого разбора
разъедутся при первой же правке формата.

### 5.3 Что делает каждый тип

| Тип | Действие над `dayData` |
|---|---|
| `CLIENT_CONFIRMED` | статус найденной записи → `CONFIRMED` |
| `CLIENT_ARRIVED` | → `ARRIVED` |
| `CANCELLED` | → `CANCELLED` |
| `DELETED` | удалить строку из дня |
| `NEW_BOOKING` | добавить строку в день, если такой ещё нет |
| `RESCHEDULED` | удалить из `prev_date`, добавить в `date` |

Запись не нашлась — ничего не делаем. Это нормальная ситуация: календарь мог быть
не синхронизирован до нужной даты. Уведомление всё равно покажется, а запись
появится при ближайшем полном синке.

### 5.4 Смена статуса: править, а не пересоздавать

Разобрать существующую строку, поменять **только** статус и сериализовать обратно
тем же сериализатором: `serializeStudentExtended(name, newStatus, time, phone, comment)`
или `serializeDiagnostics(price, name, newStatus, time)`
([SessionParser.kt:471](../app/src/main/java/ru/greemlab/neiro/ui/calendar/SessionParser.kt#L471)).

Пересборка строки с нуля затрёт телефон, комментарий и цену диагностики — их в
payload нет и взять неоткуда.

### 5.5 Новая запись: формат времени как у синка

`time` в строке — диапазон `HH:mm-HH:mm`, конец = начало + 50 минут, как в
[formatRecordTime](../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt#L794).
`phone` и `comment` — пустые, подтянутся полным синком. Цена диагностики —
`profile.pricePerDiagnostics`, как в `createEntryFromRecord`.

Разойдётся формат — следующий полный синк перезапишет строку. Не авария, но
породит лишний локальный дифф, поэтому лучше совпасть сразу.

### 5.6 Интенсивы из push не правим

Интенсив хранит нескольких детей внутри одной строки, а слияние с YClients там
уже нетривиально (сопоставление по имени и времени,
[YClientsCalendarSync.kt:369](../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt#L369)).
Точечная правка ребёнка из push'а рискует испортить ручные данные ради экономии
одного синка.

Поэтому: если найденная строка — интенсив, календарь не трогаем. Уведомление
показывается как обычно, календарь освежится при открытии приложения.

### 5.7 Порядок: календарь, потом уведомление — но независимо

```kotlin
runCatching { PushEventCalendarApplier.apply(context, events) }   // упало — идём дальше
PushEventNotifier.notify(context, sessionEvents)
```

Уведомление важнее календаря: ошибка применения не должна съесть push. Обратный
порядок означал бы «не смогли поправить календарь — не сказали пользователю».

### 5.8 Побочная выгода: дубли гаснут сами

После применения при следующем `refreshLiveRange` снимок «до» уже содержит
изменение, локальный дифф выходит пустым и второе уведомление не появляется в
принципе. То есть §5 не только чинит экран, но и снимает риск 2 из §9 —
`dedupeKey` остаётся вторым эшелоном, а не единственной защитой.

**Приёмка:** при открытом календаре пришёл push «подтвердился» → статус в слоте
поменялся без похода в YClients (проверять по логам сети); перенос → занятие
уехало на новую дату; удаление → исчезло.

---

## 6. Этап C — догон (план 8.3)

### 6.1 `push/PushApi.kt`

```kotlin
@GET("v1/devices/{deviceId}/events")
suspend fun getEvents(
    @Header("Authorization") authorization: String,
    @Path("deviceId") deviceId: String,
    @Query("since") since: Long,
    @Query("limit") limit: Int = 100,
): Response<EventsResponse>

@POST("v1/devices/{deviceId}/events/ack")
suspend fun ackEvents(...)   // необязательно, только для дашборда
```

DTO с `@SerializedName` в snake_case — как в существующих `RegisterDeviceRequest`.
`EventsResponse`: `events`, `last_event_id`, `has_more`.

### 6.2 `push/PushEventsSyncer.kt` (новый)

`suspend fun syncNow(context: Context)`:

1. `PushConfig.isActive` и `isLoggedIn` — иначе выход.
2. Курсор из prefs `neiro_push_registrar` (те же, что у `PushRegistrar`), ключ
   `last_event_id`, по умолчанию `0`.
3. Цикл, пока `has_more` — но не больше 10 итераций, чтобы не зациклиться на
   сервере, который всегда отвечает `has_more = true`.
4. Каждую страницу: `toSessionEvent()` → `PushEventNotifier.notify(...)`.
5. Курсор сохранять после каждой успешно показанной страницы, а не в конце: обрыв
   сети посреди догона не должен приводить к повторному показу.
6. Ошибку сети — проглотить и выйти; следующий тик keepalive повторит.

**Гонка.** `syncNow` может стартовать одновременно из keepalive-воркера и из
`onStart`. Обернуть тело в `Mutex` (объект-синглтон), иначе два потока прочитают
один курсор и покажут одно и то же дважды. `wasEventNotified` это, скорее всего,
погасит, но полагаться на LRU в гонке не стоит.

### 6.3 Точки вызова

| Где | Когда |
|---|---|
| `PushKeepAliveWorker` | каждый тик, до `registerNow` |
| `LiveApiCoordinator.onStart` | при каждом открытии приложения, рядом с `refreshNow` |
| `NeiroFirebaseMessagingService` | по `action = "sync_events"` (нудж при переполнении payload) |

Для третьего случая нужен `PushEventsSyncCoordinator` — one-time work по образцу
[PushSyncCoordinator.kt](../app/src/main/java/ru/greemlab/neiro/push/PushSyncCoordinator.kt):
догон ходит в сеть, а FCM-сервис для сетевого запроса — плохое место.

### 6.4 Первый запуск и переустановка

Решение пользователя от 25.07.2026: **курсор задаётся при регистрации, лента
прошлых событий не показывается.**

Почему так. Вход в приложение и так запускает полный синк календаря
(`shouldRunFullLiveSync` при `lastFullLiveSync = 0`,
[YClientsCalendarSync.kt:73](../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt#L73)) —
текущий и следующий месяц приезжают целиком. Актуальное состояние на экране уже
есть; уведомление «Ваня подтвердился три дня назад» ничего не добавляет, только
шумит.

Это третий экземпляр одного принципа, уже принятого дважды: `hasBaselineSnapshot`
в приложении (первый снимок не порождает событий,
[SessionNotificationCoordinator.kt:198](../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt#L198))
и сидирование `record_states` на сервере ([план, этап 5.3](push-events-plan.md)).
Новый наблюдатель начинает с «сейчас», а не с истории.

**Сервер:**

- `POST /v1/devices/register` для **нового** `device_id` ставит
  `last_ack_event_id = max(events.id)` этого аккаунта.
- Для уже известного `device_id` курсор **не трогает** — иначе keepalive каждые
  30 минут проглатывал бы неотданные события.
- Ответ регистрации возвращает `last_event_id` — текущий курсор устройства.

**Приложение:**

- Курсора в prefs нет → взять из ответа регистрации, не начинать с нуля.
- Курсор есть → ответ регистрации игнорировать, локальный источник главнее.

**Следствие: `ack` становится обязательным.** В плане он помечен «необязательно,
для дашборда» — с этим решением курсор обязан жить и на сервере, иначе после
переустановки серверный курсор окажется на старой позиции и лента всё-таки
польётся. Слать `ack` после каждой успешно показанной пачки.

`device_id` строится из `ANDROID_ID`
([PushDeviceId.kt:18](../app/src/main/java/ru/greemlab/neiro/push/PushDeviceId.kt#L18)),
а он переживает переустановку приложения — так что типичный случай «удалил,
поставил, вошёл» попадёт в ветку «известное устройство» и подхватит серверный
курсор. Ветка с `max(events.id)` нужна для по-настоящему нового телефона и после
сброса до заводских.

### 6.5 Logout

Курсор сбрасывать в `PushRegistrar.onLogout` вместе с регистрацией: иначе после
входа под другим аккаунтом догон начнётся с чужого `id` и пропустит события.

---

## 7. Этап D — конфигурация сборки

`local.properties`:

```properties
NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru/v2
```

Читается в [app/build.gradle.kts:66](../app/build.gradle.kts#L66), путь `/v2`
снимается Caddy через `handle_path`, до сервиса доходят обычные `/v1/...`.

`NEIRO_PUSH_API_KEY` — **новый ключ нового сервиса**, не старый: у сервисов свои
`.env`. Регистрация с чужим ключом вернёт 401, а `PushRegistrar` на 4xx не
повторяет запрос ([PushRegistrar.kt:131](../app/src/main/java/ru/greemlab/neiro/push/PushRegistrar.kt#L131))
— телефон молча останется незарегистрированным.

Версию приложения поднять (`0.7.0.0`): она уходит в `app_version` при регистрации
и по ней в дашборде видно, какая сборка на каком сервисе сидит.

---

## 8. Что не трогаем

Список нужен, чтобы «заодно» не поехало то, что работает:

- `SessionChangeDetector` — локальный дифф остаётся третьим путём доставки
  (сервер лёг / FCM выключен).
- `SessionNotificationDisplay`, `SessionNotificationTexts`, `InAppNotificationRecorder`,
  `ArchiveNotificationStore` — новый путь пользуется ими как есть.
- `SessionNotificationPreferences` — добавляется только чтение существующих
  флагов; новых ключей не заводить.
- Напоминания, дайджесты, архивные напоминания — к серверным событиям отношения
  не имеют.
- `AutoSyncCoordinator` — ежедневная синхронизация месяцев остаётся.
- `CalendarDataStore` — применение события (§5) пользуется существующим
  `updateDayData`, новых способов записи в календарь не заводить.
- `SessionFormat` — формат строки не меняется; push пишет теми же
  сериализаторами, что и синк.

---

## 9. Риски

1. **Интенсивы.** Приложение разворачивает интенсив в отдельный `TrackedSession`
   на каждого ребёнка со временем начала интенсива
   ([TrackedSession.kt:101](../app/src/main/java/ru/greemlab/neiro/notifications/TrackedSession.kt#L101)),
   а сервер шлёт событие на запись YClients с её собственным `datetime`. Если
   времена разойдутся — `slotKey` разный, и одно изменение покажется дважды:
   push'ом и локальным диффом. Проверять на живом интенсиве, отдельным пунктом
   приёмки.

2. **Расхождение `slotKey` вообще.** Любое несовпадение имени, времени или `kind`
   между сервером и приложением даёт дубли, а не потерю — то есть заметно сразу,
   но только на устройстве. Первый день после раскатки смотреть на дубли
   специально.

3. **Событие вне окна.** Сервер видит всю компанию за 62 дня, приложение
   показывает 60 дней вперёд. Фильтр горизонта (§4.2) обязателен.

4. **Курсор при первом запуске.** Решено в §6.4: курсор приходит из регистрации,
   лента прошлых событий не показывается. Риск остаточный: если сервер забудет
   ветку «известное устройство — курсор не трогать», keepalive будет каждые
   30 минут съедать недоставленные события молча. Проверять отдельным пунктом
   приёмки на сервере.

5. **Календарь врёт до следующего синка.** Применение из payload (§5) —
   единственный источник, который правит календарь без сверки с YClients. Если
   событие ошибочное или запись успели изменить ещё раз, экран будет показывать
   неверное состояние до ближайшего `refreshLiveRange` (открытие приложения).
   Ограничение по времени приемлемое, но это плата за отказ от похода в API.

6. **Порядок событий при догоне.** Догон отдаёт события пачкой по возрастанию
   `id`, и применять их к календарю нужно **в том же порядке** — иначе «перенос,
   затем отмена» ляжет наоборот. Сортировать по `id` перед применением, не
   полагаться на порядок в JSON.

---

## 10. Приёмка приложения

Из [push-events-plan.md §11](push-events-plan.md), плюс то, что вытекает из кода:

- [ ] Push с событием → уведомление появилось, запросов в YClients не было
      (проверять по логам `YClientsCalendarSync`)
- [ ] Тап открывает нужный день с подсветкой слота
- [ ] Выключенный в настройках тип не показывается
- [ ] Офлайн → изменение в YClients → онлайн → открыть приложение → уведомление
      пришло догоном
- [ ] Событие пришло и push'ом, и догоном → уведомление одно
- [ ] Событие пришло push'ом, следом локальный дифф увидел то же → уведомление
      одно (проверка §2 и маппинга статусов)
- [ ] Перенос занятия → одно уведомление `RESCHEDULED` с прежним временем в тексте
- [ ] Изменение в интенсиве → одно уведомление, не два (риск 1)
- [ ] Событие на дату за горизонтом → уведомления нет
- [ ] При открытом календаре push «подтвердился» → статус в слоте поменялся сам,
      без запроса в YClients (§5)
- [ ] Перенос push'ом → занятие уехало на новую дату, со старой исчезло
- [ ] Удаление push'ом → запись исчезла из дня
- [ ] Новая запись push'ом → появилась с временем в формате `HH:mm-HH:mm`
- [ ] Телефон и комментарий записи после смены статуса push'ом не потерялись (§5.4)
- [ ] Изменение в интенсиве → уведомление есть, календарь не поехал (§5.6)
- [ ] Календарь освежается при открытии приложения
- [ ] Обрыв сети во время keepalive → следующий тик всё равно встал в очередь
      (проверка `finally`)
- [ ] Обновление поверх 0.6.9.0 → в WorkManager не осталось `push_fcm_sync` и
      `yclients_live_api_refresh` (§4.4)
- [ ] Logout → курсор сброшен, устройство снято с регистрации
- [ ] Сборка 0.6.9.0 на другом телефоне продолжает работать как раньше

---

## 11. Открытые вопросы

Открытых вопросов нет. Решённые — для истории, все от 25.07.2026:

- **Календарь по push'у** — обновляется из payload, §5.
- **Первый запуск после установки** — курсор из регистрации, §6.4.
- **Совместимость со старым сервисом** — не нужна, §4.3. Переход
  устройство за устройством, их два.
