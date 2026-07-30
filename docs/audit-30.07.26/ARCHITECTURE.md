# Архитектура на 30.07.2026

Снимок системы на дату аудита. Главное отличие от пакета 23.07.26 — доставка
уведомлений полностью переехала на серверный push: локального опроса YClients с
телефона больше нет, приложение получает готовые события из payload.

Пути приложения сокращены от `app/src/main/java/ru/greemlab/neiro/`.

---

## 1. Общая схема

```
YClients API
   ▲  ▲
   │  └──────────────── neiro-push-events (Pi, :8011, /v2)  ── FCM ──┐
   │                    опрос всей компании раз в 10 с,              │
   │                    журнал событий + курсор устройства           │
   │                                                                 ▼
   └── приложение ── разовый синк (вход, открытие) ────────► Android-приложение
       (Retrofit)                                            календарь + уведомления
                                                                     ▲
       server/ (Pi, :8010) — legacy, опрос раз в 15 с ── FCM ────────┘
       шлёт action="sync", которое сборка 0.6.10.1 уже не обрабатывает
```

Три части, все в этом репозитории:

| Часть | Путь | Роль |
|---|---|---|
| Android-приложение | [`app/`](../../app/) | Календарь занятий, статистика, уведомления |
| Сервис событий | [`neiro-push-events/`](../../neiro-push-events/) | Действующий push: считает дифф записей и шлёт готовое событие |
| Push-сервер | [`server/`](../../server/) | Legacy (Этап 10 «гашение» не выполнен): шлёт только нудж «сходи в API» |

---

## 2. Приложение: слои

| Слой | Пакет | Ключевое |
|---|---|---|
| UI | `ui/` (14 273 строки) | Compose, `CalendarScreen`, `DayDetailsDialog`, профиль, настройки |
| Уведомления | `notifications/` (2 987) | Планирование, дифф, показ, in-app лента |
| Push-клиент | `push/` (977) | Регистрация, FCM-сервис, догон по курсору, применение событий к календарю |
| Данные | `data/` (1 630) | DataStore + зеркало в SharedPreferences, сеть YClients, `TokenStorage` |
| Синхронизация | `sync/` (1 275) | Слияние записей YClients с календарём, watermark'и |
| Тема | `theme/` (238) | Material 3, системные бары |
| Домен | `domain/` (105) | `UserProfile`, `CalendarMonthStats` |
| Auth | `auth/` (37) | `LogoutCoordinator` — единственная точка логаута |

DI нет: всё синглтоны через `getInstance(context)` / `object` (осознанно,
[OUT_OF_SCOPE §3.1](../audit-17.07.26/OUT_OF_SCOPE.md)).

---

## 3. Хранение

Два слоя, оба в [`data/CalendarDataStore.kt`](../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt):

1. **DataStore `calendar_data`** — авторитативный источник. Ключи:
   `day_data_json` (основной календарь), `saved_day_data_json` (архив),
   `user_profile_json`, `app_theme`. `corruptionHandler` подставляет пустые prefs.
2. **SharedPreferences `neiro_sync_cache`** — синхронное зеркало для первого
   кадра UI (`peekSnapshot()`), заполняется в конструкторе. Архив в зеркало
   намеренно не пишется.

Все записи — под `writeMutex`; read-modify-write выполняется **внутри**
`dataStore.edit` (`updateProfile`, `updateDayData`). `snapshotsFlow` обёрнут в
`shareIn(Eagerly, replay = 1)` — один разбор JSON на эмиссию, а не N по числу
подписчиков.

Отдельные SharedPreferences-хранилища:

| Имя | Владелец | Что лежит |
|---|---|---|
| `neiro_yclients_secure` | `TokenStorage` (EncryptedSharedPreferences) | `user_token`, логин, имя, аватар, `partner_token`, `company_id`, `staff_id` |
| `neiro_sync_prefs` | `SyncPreferences` | watermark'и синка и live-опроса |
| `neiro_session_notifications` | `SessionNotificationPreferences` | тумблеры, времена сводок, LRU показанных ключей (300), claim-и дайджестов |
| `neiro_push_registrar` | `PushRegistrar`, `PushEventsCursor` | `pending_unregister`, `last_event_id` |
| `neiro_push_device` | `PushDeviceId` | `device_id` |

`TokenStorage` при провале шифрования пересоздаёт keyset, при повторном провале
уходит в `InMemoryPrefs` (сессия живёт до перезапуска, plaintext на диск не
пишется).

---

## 4. Формат записи дня

Строка в списке дня — один из трёх форматов
([`ui/calendar/SessionParser.kt`](../../app/src/main/java/ru/greemlab/neiro/ui/calendar/SessionParser.kt)):

| Тип | Формат |
|---|---|
| Ученик, старый | `имя\|true` |
| Ученик, расширенный | `имя\|статус\|время\|телефон\|комментарий` (`split(limit = 5)` — комментарий может содержать `\|`) |
| Диагностика | `__DIAGNOSTICS__:сумма\|имя\|статус\|время` |
| Интенсив | `__INTENSIVE__:сумма\|имя\|статус\|время\|дети` (`=сумма` — зафиксирована вручную; дети через `;;`, каждый — `имя\|статус\|телефон\|комментарий`) |

Статусы: `0` ожидание, `1` подтвердил, `2` не пришёл, `3` пришёл. В деньги
входит только `3`. Слот времени интенсива сериализуется всегда, если есть дети
(иначе первый ребёнок читался бы как время — фикс `U2`).

---

## 5. Синхронизация с YClients

[`sync/YClientsCalendarSync.kt`](../../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt),
всё под одним `syncMutex`.

| Путь | Диапазон | Когда |
|---|---|---|
| `syncDefaultAutoRange()` | текущий + следующий месяц | ежедневно при возврате в приложение, если прошло > 24 ч |
| `refreshLiveRange()` | текущий + следующий месяц | при входе и при каждом `onStart` приложения |
| `syncDateRange()` | произвольный | ручная синхронизация из профиля (история 36 месяцев) |
| `syncMonth()` | месяц | кнопка на экране календаря |

`refreshLiveRange()` раз в 6 часов делает полную подтяжку
(`FULL_LIVE_SYNC_INTERVAL_MS`), между ними — инкремент по `changed_after` с
`with_deleted=1`. При инкременте, если изменился хоть один день текущего месяца,
месяц перечитывается **целиком** — иначе перенос записи оставлял бы «призрака»
(фикс `S4`).

Два режима слияния: текущий месяц пересобирается из ответа API авторитативно
(by design), остальные месяцы — совпадение по имени и времени с обновлением на
месте. В обоих режимах из локальных записей без пары в API выживают только
ручные интенсивы.

Watermark'и (`SyncPreferences`): `last_sync_epoch`, `last_live_poll_epoch`,
`last_full_live_sync_epoch`, `initial_full_sync_done` — все обновляются только
при успехе, все чистятся в `clearSyncState()` при логауте.

---

## 6. Push: контракт и потоки

### Регистрация

`PushRegistrar` шлёт `POST /v1/devices/register` с `device_id`, `fcm_token`,
`company_id`, `staff_id`, `partner_token`, `user_token`, `label`, `app_version`.
Ответ несёт `last_event_id`: новое устройство стартует с конца журнала, известное
получает свой сохранённый курсор. Вызывается при старте, при логине, при
`onStart` и из keepalive-воркера.

### Доставка

| Действие FCM | Что делает приложение |
|---|---|
| `session_events` | Разбирает `events` из payload, отсеивает чужой `staff_id`, правит календарь (`PushEventCalendarApplier`), показывает уведомление (`PushEventNotifier`), двигает курсор по `last_event_id` |
| `sync_events` (payload > 3 КБ) | Ставит `PushEventsSyncWorker` — сеть в FCM-сервисе не место |

### Догон

`PushEventsSyncer.syncNow()` (под своим `Mutex`, до 10 страниц по 100):
`GET /v1/devices/{id}/events?since=курсор` → применение → показ → `markSeen` →
`POST .../events/ack`. Вызывается из keepalive-тика, из `onStart` и по нуджу.

### Типы событий

`NEW_BOOKING`, `CANCELLED`, `RESCHEDULED` (с `prev_date`/`prev_time`), `DELETED`,
`CLIENT_CONFIRMED`, `CLIENT_ARRIVED`. Сервер отдаёт только события про занятия,
дата которых не раньше сегодняшней; приложение дополнительно режет горизонтом
60 дней.

---

## 7. Фоновые задачи (WorkManager)

| Уникальное имя | Класс | Тип | Политика | Роль |
|---|---|---|---|---|
| `push_keepalive` | `PushKeepAliveWorker` | one-time, самопланирующийся | `KEEP` извне, `APPEND_OR_REPLACE` из воркера | Перерегистрация + догон; 30 мин днём, 60 мин с 21:00 МСК |
| `push_events_sync` | `PushEventsSyncWorker` | one-time | `APPEND_OR_REPLACE` | Догон по нуджу сервера |
| `session_daily_notifications` | `SessionDailyNotificationWorker` | periodic 15 мин | `KEEP` на старте, `UPDATE` при смене настроек | Тик: сводки, архив, fallback напоминаний |
| `session_today_digest` / `session_tomorrow_digest` / `session_archive_digest` | `SessionScheduledDigestWorker` | one-time, самопланирующийся | `REPLACE` из UI, `APPEND_OR_REPLACE` из воркера, `KEEP` из синка | Доставка в точное время |
| `session_reminder_<ключ>` | `SessionReminderWorker` | one-time | `REPLACE` | Напоминание за N минут, diff-перепланирование по тегам |

Отменяются при логауте: `push_keepalive`, все уведомления и напоминания, легаси
`yclients_periodic_sync`. Осиротевшие имена удалённых воркеров
(`yclients_live_api_refresh`, `push_fcm_sync`) гасятся один раз при старте в
`AutoSyncCoordinator.initialize`.

---

## 8. Уведомления

```
sync/push ──► dayData «до»/«после» ──► CalendarSessionSnapshot.from()
                                              │
                                     SessionChangeDetector.detect()
                                              │
                      фильтр типа ──► фильтр dedupe ──► SessionNotificationDisplay
                                                              │
                                            ┌─────────────────┴──────────────────┐
                                     системный push                      in-app лента
                                    (может не пройти)              InAppNotificationRecorder
                                                                  (активная + архивная)
```

Событийный путь дублируется push-клиентом: `PushEventNotifier` — зеркало хвоста
`processSnapshotTransition` без бейзлайна (сервер уже отсеял его сидированием).

`dedupeKey` события включает тип, слот (`имя|дата|время|вид`) и — для смены
статуса — сам статус. LRU показанных ключей — 300 на каждый из трёх списков.
Сводки и напоминание об архиве защищены атомарным `claim`, откатываемым при
неудачном показе.

---

## 9. Бэкенд `neiro-push-events` (действующий)

FastAPI + SQLite (WAL, `busy_timeout=5000`), один `Database`, один
`httpx.AsyncClient`, один `PollService` на процесс — собираются в `lifespan`.

**Цикл опроса** (`poller.py`), раз в 10 с до 23:00 МСК, дальше раз в час:

1. аккаунты группируются по `company_id`;
2. один `GET /records/{company}` на компанию, токен перебирается по активным
   аккаунтам; `changed_after` — минимум по аккаунтам компании;
3. если у любого аккаунта компании пустые `record_states` — весь цикл идёт
   полным запросом и события не генерируются (сидирование, решение 25.07.2026);
4. `derive_events()` — чистая функция без БД и сети;
5. события и состояния пишутся **одной транзакцией** (`commit_poll_result`);
6. рассылка на устройства аккаунта через `asyncio.gather`, payload > 3 КБ
   превращается в нудж; `UNREGISTERED`/`NOT_FOUND` удаляют устройство;
7. `purge_old_data()`: события и доставки 30 дней, циклы опроса 7 дней;
8. backoff при ошибке компании: 10 → 30 → 60 → 120 → 300 → 600 → 900 с.

**Схема БД:** `accounts` (токены Fernet-шифрованы, курсор, backoff),
`devices` (`last_ack_event_id`), `record_states` (PK `account_id, record_id`),
`events` (сквозной `id`, индекс `(account_id, id)`), `push_deliveries`
(индекс по `event_id`), `poll_runs`.

**HTTP:** `/v1/devices/register`, `DELETE /v1/devices/{id}`,
`GET /v1/devices/{id}/events`, `POST /v1/devices/{id}/events/ack` — под
`API_KEY`; `/health`, `/v1/admin/*` — под `ADMIN_API_KEY`; `/dashboard` — HTML
за cookie с тем же ключом, шапка сама обновляется раз в 10 с.

---

## 10. Бэкенд `server/` (legacy)

Тот же каркас, но: детектор изменений — sha256-фингерпринт выборки, push несёт
только `action="sync"` («сходи в API»), журнала событий нет, курсора нет,
SQLite без WAL и без `busy_timeout`, `Database` создаётся на каждый HTTP-запрос.
Сборка 0.6.10.1 действие `sync` не обрабатывает.

---

## 11. Инвентарь тестов

| Где | Файлов | Строк | Что покрыто |
|---|---|---|---|
| `app/src/test` | 26 | 2 175 | Парсер и round-trip форматов, статистика дня/месяца/года, дифф уведомлений, окна напоминаний и сводок, `SessionSlotKey`, слияние синка, сравнение архива, `ScheduleTime`, `ProfitDisplay` |
| `app/src/androidTest` | 1 | 23 | Смоук `APPLICATION_ID` |
| `neiro-push-events/tests` | 4 | ~1 100 | Дифф событий (21 тест), поллер (7), API и дашборд (30+), транзакция `commit_poll_result` и её откат |
| `server/tests` | 1 | 26 | Только расписание опроса |

**Белые пятна:** пакет `push/` приложения (0 тестов на 977 строк, включая разбор
payload), `server/` целиком кроме расписания, в сервисе событий — `fcm.py`
(порог нуджа, разбор ошибок FCM) и `security.SecretBox`.

---

## 12. Метрики

Пересчитано 30.07.2026 (совпадает со снимком в [методике §2](../audit/METHODIKA.md)).

| Часть | Файлы | Строк | Тесты |
|---|---|---|---|
| `app/src/main` (Kotlin) | 116 | 21 749 | 27 файлов / 2 198 строк |
| `server/app` (Python) | 13 | 1 329 | 1 файл / 26 строк |
| `neiro-push-events` (Python + шаблоны) | 24 | 4 833 | 4 файла / ~1 100 строк |

Пакеты `app` по строкам: `ui/` 14 273 · `notifications/` 2 987 · `data/` 1 630 ·
`sync/` 1 275 · `push/` 977 · `theme/` 238 · `domain/` 105 · `auth/` 37.

Крупнейшие файлы: `CalendarScreen.kt` 1035 · `DayDetailsDialog.kt` 1019 ·
`YClientsCalendarSync.kt` 928 · `ProfileContent.kt` 750 ·
`SessionNotificationCoordinator.kt` 730 · `ScheduleSlotItem.kt` 705 ·
`ProfileYearStatsSection.kt` 677 · `DayScheduleTimeline.kt` 577 ·
`SessionParser.kt` 531 (god-composables не дробятся — OUT_OF_SCOPE §3.3).

Дельта с 23.07.26: `sync/` 1 374 → 1 275 (удалён `LiveApiRefreshWorker`),
`push/` 462 → 977 (новый клиент событий), `notifications/` 2 903 → 2 987.
