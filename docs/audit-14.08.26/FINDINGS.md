# Находки аудита 14.08.2026

18 находок: **0 критично · 3 высоко · 5 средне · 10 низко**.

Каждая находка проверена по коду: указан путь, механизм и то, что делает этот
путь достижимым. Номера строк — на коммит `0079df0`. Пути приложения сокращены
от `app/src/main/java/ru/greemlab/neiro/`, пути бэкенда — от корня репозитория.

Пункты из [OUT_OF_SCOPE.md](../archive/audit-17.07.26/OUT_OF_SCOPE.md) находками
не считались.

---

## Сводная таблица

| # | Уровень | Область | Что |
|---|---|---|---|
| [S1](#s1-высоко--инкрементальный-live-sync-стирает-текущий-месяц-при-пустом-ответе-api) | Высоко | Синхронизация | Инкрементальный live-sync обходит защиту от пустого ответа и стирает текущий месяц |
| [K1](#k1-высоко--лимит-попыток-входа-обходится-подменой-заголовка) | Высоко | Бэкенд | Лимит попыток входа обходится подменой `X-Forwarded-For` и `device_id` |
| [K2](#k2-высоко--невалидный-fcm-токен-выкидывает-пользователя-из-аккаунта) | Высоко | Бэкенд | Невалидный FCM-токен удаляет устройство и выкидывает пользователя из аккаунта |
| [K3](#k3-средне--record_states-растёт-без-границ) | Средне | Бэкенд | `record_states` растёт без границ и перечитывается каждые 15 секунд |
| [K4](#k4-средне--401-от-yclients-в-поллере-не-поднимает-reauth_required) | Средне | Бэкенд | 401 в поллере не поднимает `reauth_required` — мёртвый токен жжёт квоту вечно |
| [K5](#k5-средне--сидирование-включается-на-всю-компанию) | Средне | Бэкенд | Приход нового сотрудника съедает цикл событий у существующих аккаунтов |
| [B1](#b1-средне--baseline-prof-разошёлся-с-кодом) | Средне | Сборка | `baseline-prof.txt` разошёлся с кодом — половина правил молча не матчится |
| [B2](#b2-средне--ci-не-гоняет-тесты-бэкенда) | Средне | Сборка | CI не гоняет `pytest` — 2 632 строки тестов бэкенда никем не проверяются |
| [A1](#a1-низко--isbusy-не-покрывает-готовность-к-установке) | Низко | Обновление | `isBusy` не покрывает `ReadyToInstall`/`AwaitingConfirmation` |
| [A2](#a2-низко--проверка-разрешения-на-установку-в-теле-композиции) | Низко | Обновление | `canRequestPackageInstalls()` вызывается на каждом кадре прогресса |
| [B3](#b3-низко--verify_api_key-мёртвая-функция) | Низко | Сборка | `verify_api_key` не используется ни одним эндпоинтом |
| [K6](#k6-низко--вход-перепривязывает-чужой-device_id) | Низко | Бэкенд | Вход с чужим `device_id` перепривязывает устройство и убивает его токен |
| [D1](#d1-низко--clearinstance-неполон-и-никем-не-вызывается) | Низко | Данные | `YClientsClient.clearInstance()` не сбрасывает `pushApi` и `tokenStorage` |
| [D2](#d2-низко--clearalldata-не-чистит-соседние-хранилища) | Низко | Данные | `clearAllData()` оставляет историю ЗП, метаданные и ленту уведомлений |
| [N1](#n1-низко--logout-не-чистит-ленту-уведомлений) | Низко | Уведомления | Записи прошлого аккаунта остаются в ленте и в архиве уведомлений |
| [S2](#s2-низко--двойная-подтяжка-календаря-на-холодном-старте) | Низко | Синхронизация | Холодный старт даёт две подтяжки календаря подряд |
| [U1](#u1-низко--computeprofiletotals-мёртвый-код) | Низко | UI | `computeProfileTotals` (123 строки + тест) не используется ни одним экраном |
| [U2](#u2-низко--годовой-налог-считается-но-нигде-не-выводится) | Низко | UI | `totalTaxAmount` считается по другой формуле, чем месячные значения, и не выводится |

---

# Высоко

## S1. Высоко — инкрементальный live-sync стирает текущий месяц при пустом ответе API

**Файлы:** [`sync/YClientsCalendarSync.kt:476–518`](../../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt),
он же `:162–199`, `:904–912`

**Механизм.** У полного синка есть защита от пустого ответа — `shouldApplySyncMerge`:

```kotlin
// syncDateRangeLocked, :171
if (!shouldApplySyncMerge(records, dayDataBefore, startDate, endDate)) {
    return SyncOutcome.Failure(
        "YClients вернул пустой ответ, а в календаре есть записи — " +
            "слияние пропущено, локальные данные сохранены",
    )
}
```

Инкрементальный путь этой проверки не делает. `mergeIncrementalRecords` при
любой записи текущего месяца перезапрашивает месяц целиком и отдаёт результат
прямо в слияние, минуя защиту:

```kotlin
// mergeIncrementalRecords, :494
when (val fullDays = yclientsRepository.getRecords(subStart, subEnd)) {
    is ApiResult.Success -> {
        syncedCount += mergeRecordsToCalendar(
            records = fullDays.data,          // ← может быть пустым списком
            startDate = subStart,
            endDate = subEnd,
            clearMissingDaysInRange = true,   // ← и тогда чистится весь месяц
        ).newlyAdded
    }
```

При `fullDays.data == emptyList()` цикл `recordsByDate` не выполняется вовсе, а
блок `clearMissingDaysInRange` (`:424–462`) проходит по каждому дню текущего
месяца и оставляет от него только ручные интенсивы
(`retainManualLocalEntries`). Все ученики и диагностика текущего месяца
удаляются из локального календаря.

**Достижимость.** `refreshLiveRange()` вызывается из
[`LiveApiCoordinator.refreshNow`](../../app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt)
при каждом возврате в приложение и при входе. Пока с прошлой полной подтяжки не
прошло 6 часов, идёт именно инкрементальная ветка. Чтобы попасть в удаление,
нужно, чтобы `changed_after`-запрос вернул хотя бы одну запись текущего месяца
(это и есть обычный сценарий «что-то поменялось»), а следующий за ним запрос
месяца целиком вернул `success: true, data: []`.

Такой ответ — ровно то, ради чего написан `shouldApplySyncMerge`: «сеть, неверный
`staff_id`, глюк API» (комментарий на `:899`). Отдельный узкий случай, который
воспроизводится без всякого глюка: в текущем месяце была одна-единственная
запись и её удалили. Инкрементальный запрос идёт с `with_deleted=1` и её
возвращает, а перезапрос месяца — без него, и отдаёт пустой список.

Данные восстанавливаются следующим успешным синком (YClients авторитативен для
текущего месяца), но до него пользователь видит пустой месяц, а
`SessionNotificationCoordinator.onCalendarUpdatedFromApi` успевает разослать
уведомления об «удалении» всех занятий.

**Фикс.** Прогнать результат `getRecords(subStart, subEnd)` через тот же
`shouldApplySyncMerge` перед вызовом `mergeRecordsToCalendar`; при отказе —
вернуть `SyncOutcome.Failure` и не двигать `lastLivePoll`. Тест: инкрементальный
синк, где перезапрос месяца отдал пустой список, а в календаре есть ученики.

---

## K1. Высоко — лимит попыток входа обходится подменой заголовка

**Файлы:** [`neiro-push/app/ratelimit.py:68–78`](../../neiro-push/app/ratelimit.py),
[`neiro-push/app/auth.py:164–183`](../../neiro-push/app/auth.py),
[`neiro-push/scripts/patch-vps-nginx-v1.sh:45`](../../neiro-push/scripts/patch-vps-nginx-v1.sh)

**Механизм.** Вход считает лимит по двум ключам:

```python
# auth.py:171
for scope, key in (("device", f"login:device:{body.device_id}"), ("ip", f"login:ip:{ip}")):
```

Оба значения приходят от клиента. `device_id` — поле тела запроса
(`min_length=8`, произвольная строка). `ip` берётся так:

```python
# ratelimit.py:75
forwarded = request.headers.get("x-forwarded-for", "")
if forwarded:
    return forwarded.split(",")[0].strip()
```

nginx на VPS настроен как `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for`
— эта директива **дописывает** настоящий адрес в конец списка, не затирая то,
что прислал клиент. Значит первый элемент — всегда значение атакующего, и
`split(",")[0]` берёт именно его.

Итог: меняя `X-Forwarded-For` и `device_id` на каждом запросе, клиент получает
свежий пустой счётчик каждый раз. Лимит `5 попыток / 15 минут` не срабатывает
никогда, и строка `login rate limit hit` — единственный способ вообще заметить
перебор — в лог не попадает.

**Достижимость.** `POST /v1/auth/login` открыт ключом `API_KEY`, который лежит в
APK открытым текстом (`BuildConfig.NEIRO_PUSH_API_KEY`, вытаскивается из APK за
минуту). За этой дверью — перебор паролей YClients через собственный сервер,
без каких-либо ограничений, кроме лимитов самого YClients.

**Фикс.** Брать адрес не из первого элемента `X-Forwarded-For`, а из последнего
доверенного — или из `X-Real-IP`, который nginx уже ставит из `$remote_addr`
(строка 44 того же скрипта) и который клиент подменить не может. Тест на
`client_ip` с подставленным списком адресов есть смысл добавить в
`tests/test_ratelimit.py`.

---

## K2. Высоко — невалидный FCM-токен выкидывает пользователя из аккаунта

**Файлы:** [`neiro-push/app/poller.py:329–335`](../../neiro-push/app/poller.py),
[`neiro-push/app/database.py:456–471`](../../neiro-push/app/database.py),
[`data/network/YClientsRepository.kt:369–395`](../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt)

**Механизм.** Ответ FCM `UNREGISTERED` приводит к удалению строки устройства
целиком:

```python
# poller.py:329
if result.token_invalid:
    for event_id in event_ids:
        self._db.record_push_delivery(event_id, device_id, "token_invalid", None)
    removed = self._db.delete_device(device_id)
```

Но в этой же строке живёт `token_hash` — хэш `device_token`, которым телефон
ходит за всеми данными. Удаление строки убивает не только доставку пуша, но и
доступ: следующий запрос приложения не находит устройство и получает `401`.

На стороне приложения `401` — это не «попробуй ещё раз», а полный выход:

```kotlin
// YClientsRepository.handleAuthFailure, :381
tokenStorage.clear()
if (logoutOn401InProgress.compareAndSet(false, true)) {
    logoutOn401Job = logoutScope.launch { LogoutCoordinator.logout(appContext) }
}
```

`LogoutCoordinator` отменяет воркеры, сбрасывает watermark'и синхронизации и
состояние уведомлений (baseline + dedupe). Пользователь оказывается на экране
входа и должен снова вводить пароль YClients — из-за проблемы с токеном пуша,
к доступу отношения не имеющей.

**Достижимость.** FCM отдаёт `UNREGISTERED` не только на удалённое приложение:
токен инвалидируется при восстановлении устройства из бэкапа, при сбросе Google
Play Services и после долгой неактивности приложения. Приложение обновляет токен
при каждом выходе на передний план и в keepalive-тике (30–60 мин), но окно между
инвалидацией токена и следующим тиком существует всегда, а если телефон в это
время без сети — окно растягивается. Кода, который отличал бы «токен пуша умер»
от «доступ отозван», ни на сервере, ни в приложении нет.

**Фикс.** По `token_invalid` обнулять `fcm_token` устройства, а не удалять
строку: пуши перестанут отправляться (`_poll_account` уже фильтрует устройства с
пустым токеном, `:267–271`), а `device_token` останется рабочим — приложение
пришлёт новый FCM-токен само при следующем `POST /v1/devices/fcm`. Удаление
строки оставить только за админом.

---

# Средне

## K3. Средне — `record_states` растёт без границ

**Файлы:** [`neiro-push/app/events.py:25–147`](../../neiro-push/app/events.py),
[`neiro-push/app/database.py:202–212, 639–648, 800–819`](../../neiro-push/app/database.py),
[`neiro-push/app/yclients.py:130–195`](../../neiro-push/app/yclients.py)

**Механизм.** После сидирования опрос идёт инкрементально: `fetch_company_records`
отправляет `changed_after` и возвращает только изменившиеся записи. `derive_events`
строит новый снимок поверх старого:

```python
# events.py:36
new_states = dict(previous)
```

Дальше он только дописывает и обновляет ключи. Из-за этого в
`_record_state_changes` список `removed` всегда пуст:

```python
# database.py:211
removed = [record_id for record_id in previous if record_id not in states]
```

`states` — надмножество `previous`, значит удалять нечего никогда. Ретеншен
`purge_old_data` (`:800`) чистит `events`, `push_deliveries` и `poll_runs`, но
`record_states` не трогает.

**Достижимость.** Каждый цикл опроса (на Pi — раз в 15 секунд) выполняет
`get_record_states(account.id)`, то есть читает всю накопленную таблицу аккаунта
целиком, чтобы сравнить с ней несколько изменившихся записей. Строка появляется
на каждую запись YClients, попавшую в горизонт 62 дня, и остаётся там навсегда —
даже когда занятие давно прошло и из окна опроса ушло. За год работы одного
специалиста это тысячи строк, читаемых ~5 500 раз в сутки с SD-карты.

**Фикс.** Удалять состояния записей, чья дата ушла в прошлое: в `purge_old_data`
добавить `DELETE FROM record_states WHERE date < :today`. Дифф от этого не
страдает — записи прошлых дат в окно опроса всё равно не попадают.

---

## K4. Средне — 401 от YClients в поллере не поднимает `reauth_required`

**Файлы:** [`neiro-push/app/poller.py:165–205`](../../neiro-push/app/poller.py),
[`neiro-push/app/yclients.py:166–174`](../../neiro-push/app/yclients.py),
[`neiro-push/app/proxy.py:61–69`](../../neiro-push/app/proxy.py)

**Механизм.** В прокси протухший `user_token` считается: три `401` подряд взводят
`reauth_required`, и приложение просит пароль (`proxy.py:62`,
`database.note_upstream_auth_failure`). В поллере такого счётчика нет:

```python
# poller.py:165
for candidate in active_accounts:
    try:
        ...
        records = await self._yclients.fetch_company_records(...)
        break
    except Exception as exc:
        error_message = str(exc)[:500]
```

`fetch_company_records` делает `response.raise_for_status()`, поэтому `401` от
YClients прилетает сюда обычным исключением и попадает в общий `except` наравне
с таймаутом. Аккаунт получает backoff (до 15 минут) и `last_error`, но
`auth_failures` не растёт и `reauth_required` не взводится.

**Достижимость.** Аккаунт с мёртвым `user_token` остаётся в списке
`poll_once` (он фильтрует только по `reauth_required`) и каждые 15 минут снова
идёт в YClients с заведомо негодным токеном — бесконечно. Пользователь узнает о
проблеме только когда приложение само сходит в прокси; до тех пор уведомления не
приходят, а причина в дашборде выглядит как «ошибка» без объяснения.

**Фикс.** Отделить `httpx.HTTPStatusError` с кодом 401/403 от прочих ошибок и
звать для него `db.note_upstream_auth_failure(account.id)` — тот же путь, что и
в прокси.

---

## K5. Средне — сидирование включается на всю компанию

**Файл:** [`neiro-push/app/poller.py:157–158, 245–255`](../../neiro-push/app/poller.py)

**Механизм.**

```python
seeding = any(not self._db.has_record_states(a.id) for a in active_accounts)
changed_after = None if seeding else self._company_changed_after(active_accounts)
```

Флаг общий для всех аккаунтов компании. В режиме сидирования `_poll_account`
идёт по ветке `merge_states` — состояния обновляются, события **не создаются**:

```python
# poller.py:252
if seeding:
    new_states = merge_states(previous_states, records)
    self._db.commit_poll_result(account.id, [], new_states, previous_states)
    return 0, 0, None
```

**Достижимость.** Как только в компании появляется новый аккаунт (первый вход
второго сотрудника), ближайший цикл считается сидированием для **всех**
аккаунтов компании. Изменения, произошедшие у уже работающего сотрудника с
момента прошлого опроса, молча уезжают в снимок без события — уведомление о них
не придёт никогда, потому что следующий цикл считает эти данные исходным
состоянием.

Сейчас в эксплуатации один аккаунт, и путь достижим только в момент подключения
второго. Но именно этот момент и есть первое включение второго сотрудника —
самый неудобный момент для «уведомления не пришли».

**Фикс.** Сделать `seeding` признаком аккаунта, а не компании:
`changed_after=None` (полный горизонт) запросить один раз для всей компании, а
внутри `_poll_account` решать по `has_record_states(account.id)`, сидируется этот
аккаунт или диффится.

---

## B1. Средне — `baseline-prof` разошёлся с кодом

**Файл:** [`app/src/main/baseline-prof.txt`](../../app/src/main/baseline-prof.txt)

**Механизм.** Профиль написан руками 24.07.2026 и с тех пор не обновлялся, а
сигнатуры перечисленных методов изменились. Правило, чья сигнатура не совпала с
кодом, ART молча игнорирует — ошибки сборки при этом нет.

Расхождения (проверены по текущему коду):

| Строка профиля | Что в коде сейчас |
|---|---|
| `CalendarScreen(CalendarViewModel;ProfileViewModel;Composer;II)V` | добавлены `openDateFromNotification`, `highlightSlotKeyFromNotification`, `notificationDeepLinkVersion`, `openAboutFromNotification` ([`CalendarScreen.kt:123`](../../app/src/main/java/ru/greemlab/neiro/ui/screens/CalendarScreen.kt)) |
| `CalendarScreenContent(YearMonth;LocalDate;Map;CalendarMonthStats;Set;Z;…)` | ~20 параметров, включая `Modifier`, `EarningsContext`, `ProfitDisplaySettings` (`:660`) |
| `CalendarGrid(YearMonth;LocalDate;Map;Set;Function1;…)` | добавлены `daysNeedingArchive`, `workingDays` ([`CalendarGrid.kt:41`](../../app/src/main/java/ru/greemlab/neiro/ui/components/CalendarGrid.kt)) |
| `computeMonthStats(YearMonth;Map;DD)` | третий параметр — `EarningsContext`, а не две `double` ([`CalendarStatsCalculator.kt:67`](../../app/src/main/java/ru/greemlab/neiro/ui/calendar/CalendarStatsCalculator.kt)) |
| `rememberCalendarMonthStats(YearMonth;Map;DD;Composer;I)` | то же самое (`:59`) |

**Достижимость.** Из 33 правил профиля не матчатся пять — и это ровно те, что
описывают отрисовку главного экрана и пересчёт статистики, то есть основной
смысл файла. Холодный старт теряет AOT-компиляцию главного экрана; заметить это
без замеров нельзя, потому что всё продолжает работать.

**Фикс.** Переписать сигнатуры по текущему коду либо, что надёжнее, перейти на
генерацию профиля через macrobenchmark — на это указывает комментарий в шапке
самого файла. Иначе он разойдётся снова после следующей правки экрана.

---

## B2. Средне — CI не гоняет тесты бэкенда

**Файл:** [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)

**Механизм.** Workflow состоит из четырёх шагов: запрет артефактов, JDK, Gradle,
`./gradlew testDebugUnitTest`. Шага с `pytest` нет.

**Достижимость.** В `neiro-push/tests` лежит 9 файлов и 2 632 строки тестов —
`test_auth.py`, `test_ratelimit.py`, `test_poller.py`, `test_proxy.py`,
`test_events.py`, `test_device_events.py`, `test_database.py`, `test_main.py`,
`test_yclients.py`. Ни один из них не запускается ни на PR, ни на push в `main`.
Правка в `app/auth.py` доезжает до Pi через `deploy`, ни разу не встретив
собственный тест — и это при том, что тестами покрыты именно чувствительные
места: лимиты, авторизация, дифф событий.

Отдельно: `release.yml` перед сборкой APK тоже гоняет только Kotlin-тесты.

**Фикс.** Добавить в `ci.yml` job с `python -m pip install -r
neiro-push/requirements.txt && python -m pytest neiro-push/tests`. Стоит одного
шага, закрывает 2 600 строк уже написанных проверок.

---

# Низко

## A1. Низко — `isBusy` не покрывает готовность к установке

**Файлы:** [`update/UpdateState.kt:54–58`](../../app/src/main/java/ru/greemlab/neiro/update/UpdateState.kt),
[`update/UpdateViewModel.kt:132–135, 169–184`](../../app/src/main/java/ru/greemlab/neiro/update/UpdateViewModel.kt)

**Механизм.**

```kotlin
val UpdateState.isBusy: Boolean
    get() = this is UpdateState.Checking ||
        this is UpdateState.Downloading ||
        this is UpdateState.Verifying ||
        this is UpdateState.Installing
```

`ReadyToInstall` и `AwaitingConfirmation` в список не входят, поэтому:

- строка «Проверить обновления» остаётся активной, когда APK уже скачан и
  проверен; нажатие переводит состояние в `Checking` → `Available`, и оттуда
  кнопка «Обновить» качает те же 15 МБ заново, хотя проверенный файл лежит в
  `cacheDir/updates` и записан в `pending_apk_path`;
- `install(info, apk)` (`:169`) не проверяет `isBusy` вовсе, а `Installing`
  выставляется уже внутри запущенной корутины — двойное нажатие «Установить»
  создаёт две сессии `PackageInstaller` на один и тот же файл.

**Достижимость.** Состояние `ReadyToInstall` восстанавливается при каждом
открытии экрана после того, как пользователь закрыл системный диалог установки
(`restoreAfterInstall`, `:58–98`) — это штатный, а не редкий сценарий.

**Фикс.** Добавить `ReadyToInstall` и `AwaitingConfirmation` в `isBusy` (или
завести отдельный признак «есть незавершённая установка») и поставить в
`install()` тот же guard, что и в `downloadAndInstall()`.

---

## A2. Низко — проверка разрешения на установку в теле композиции

**Файл:** [`ui/settings/AboutScreen.kt:165`](../../app/src/main/java/ru/greemlab/neiro/ui/settings/AboutScreen.kt)

**Механизм.** `needsInstallPermission = viewModel.needsInstallPermission()`
вызывается прямо в теле `AboutScreen`, без `remember` и без состояния. Под ним —
`packageManager.canRequestPackageInstalls()`, то есть binder-вызов в
PackageManager.

**Достижимость.** `AboutScreen` рекомпозится на каждое изменение `state`, а во
время загрузки прогресс обновляется до пяти раз в секунду
([`UpdateDownloader.PROGRESS_INTERVAL_MS = 200`](../../app/src/main/java/ru/greemlab/neiro/update/UpdateDownloader.kt)).
Каждый такой кадр делает синхронный IPC. Плюс значение не обновляется само:
пользователь ушёл в системные настройки, выдал разрешение, вернулся — подсказка
«разрешите установку» продолжает висеть, пока экран не пересоберётся по другой
причине.

Установку это не ломает: `ApkInstaller.install` проверяет разрешение заново и
живьём.

**Фикс.** Держать признак в `UpdateViewModel` как `StateFlow` и обновлять его при
`ON_RESUME` — тогда и IPC один на возврат, и подсказка исчезает вовремя.

---

## B3. Низко — `verify_api_key` мёртвая функция

**Файл:** [`neiro-push/app/main.py:105–115`](../../neiro-push/app/main.py)

**Механизм.** Функция объявлена, но ни одним `Depends` не используется:
`/health` и все `/v1/admin/*` защищены `verify_admin_api_key`, а единственная
дверь под `API_KEY` — `POST /v1/auth/login` — использует `require_app_key` из
`auth.py`.

**Достижимость.** Мёртвого кода в рантайме нет, но функция выглядит как рабочая
защита: следующий эндпоинт, который «просто подключит проверку ключа», получит
клиентский ключ из APK вместо админского — ровно та ошибка, которую чинил
пункт `K1` аудита 30.07.2026.

**Фикс.** Удалить.

---

## K6. Низко — вход перепривязывает чужой `device_id`

**Файлы:** [`neiro-push/app/auth.py:227–236`](../../neiro-push/app/auth.py),
[`neiro-push/app/database.py:319–354`](../../neiro-push/app/database.py)

**Механизм.** `device_id` приходит из тела запроса и служит ключом upsert'а:

```sql
ON CONFLICT(device_id) DO UPDATE SET
    account_id = excluded.account_id,
    token_hash = excluded.token_hash,
    revoked_at = NULL,
```

Кто угодно, войдя со **своими** учётными данными YClients, но подставив чужой
`device_id`, перепишет чужую строку: `token_hash` заменится, и `device_token`
настоящего владельца телефона перестанет работать (`401` → полный logout, как в
[K2](#k2-высоко--невалидный-fcm-токен-выкидывает-пользователя-из-аккаунта)).
Заодно к чужому аккаунту переедет `fcm_token` — пуши нового владельца строки
пойдут на телефон прежнего.

**Достижимость.** Нужны валидные учётные данные YClients, ключ приложения из
APK и знание чужого `device_id` (`neiro-<модель>-<androidId>`, 16 hex-символов —
не угадывается). Данных это не раскрывает: чужие записи не отдаются, `staff_id`
подставляет прокси. Практически — способ сломать чужой телефон, а не украсть
данные.

**Фикс.** При конфликте по `device_id` с другим `account_id` заводить новую
строку с суффиксом либо отвечать `409`, а не молча перепривязывать.

---

## D1. Низко — `clearInstance` неполон и никем не вызывается

**Файл:** [`data/network/YClientsClient.kt:204–210`](../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt)

**Механизм.**

```kotlin
fun clearInstance() {
    synchronized(this) {
        yclientsApi = null
        neiroApi = null
        retrofit = null
    }
}
```

`pushApi` и `tokenStorage` не сбрасываются. После вызова `getPushApi` вернёт
старый прокси, построенный поверх уже обнулённого `retrofit` — то есть половина
клиентов останется от прежней конфигурации, а половина пересоздастся.

**Достижимость.** Сейчас функция не вызывается ниоткуда (проверено по всему
`app/src/main`), так что сломать ничего не может. Опасен именно её вид готового
инструмента: логаут или смена адреса сервиса — первое, где её захочется позвать.

**Фикс.** Либо удалить, либо дописать `pushApi = null` и решить, что делать с
`tokenStorage`.

---

## D2. Низко — `clearAllData` не чистит соседние хранилища

**Файлы:** [`data/CalendarDataStore.kt:273–288`](../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt),
[`ui/sync/SyncViewModel.kt:105–127`](../../app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt)

**Механизм.** `clearAllData()` очищает DataStore и sync-кэш, но не трогает
`SalaryLedgerStore` (история цен и фактов ЗП), `SessionMetaStore`,
`InAppNotificationStore` и `ArchiveNotificationStore` — это отдельные
SharedPreferences-файлы.

**Достижимость.** Единственные вызовы — `devFullSetup()` и `devResetData()`, то
есть кнопки отладочного сброса. После «сброса» статистика подхватывает историю ЗП
прежней установки, а лента уведомлений остаётся от прошлых прогонов — то есть
сброс не даёт чистого старта, ради которого его нажимают.

**Фикс.** Дописать очистку остальных хранилищ в `clearAllData()` либо завести
отдельный `devWipeEverything()`, который зовёт их по очереди.

---

## N1. Низко — logout не чистит ленту уведомлений

**Файлы:** [`auth/LogoutCoordinator.kt:26–41`](../../app/src/main/java/ru/greemlab/neiro/auth/LogoutCoordinator.kt),
[`notifications/InAppNotificationStore.kt:74`](../../app/src/main/java/ru/greemlab/neiro/notifications/InAppNotificationStore.kt)

**Механизм.** `LogoutCoordinator` отменяет воркеры, отзывает устройство, чистит
сессию, watermark'и синхронизации и состояние уведомлений
(`SessionNotificationCoordinator.onLoggedOut`), но `InAppNotificationStore` и
`ArchiveNotificationStore` не затрагивает. `clearAll()` у первого есть и
вызывается только из UI («очистить ленту»), у второго его нет вовсе.

**Достижимость.** После «Сменить аккаунт» и входа другим сотрудником лента
уведомлений и её архив по-прежнему показывают события прошлого аккаунта — с
именами его клиентов, датами и временем занятий.

Сейчас приложение эксплуатируется одним человеком, поэтому «утечкой» это не
становится. Но лента — единственное место, где данные аккаунта переживают выход
из него, и `LogoutCoordinator` про неё не знает.

**Фикс.** Добавить в `LogoutCoordinator` очистку обеих лент; для
`ArchiveNotificationStore` завести `clearAll()` рядом с существующим
`exportJson`/`importJson`.

---

## S2. Низко — двойная подтяжка календаря на холодном старте

**Файл:** [`sync/LiveApiCoordinator.kt:55–94`](../../app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt)

**Механизм.** `initialize` заводит два независимых триггера `refreshNow`:

```kotlin
val observer = object : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) { … refreshNow(appContext) }
}
…
scope.launch {
    yclientsRepository.isLoggedIn.collect { loggedIn ->
        if (loggedIn) { … refreshNow(appContext) }
    }
}
```

`isLoggedIn` — `StateFlow`, и `collect` немедленно отдаёт текущее значение.
У вошедшего пользователя на холодном старте срабатывают оба: и `onStart`, и
первая эмиссия коллектора.

**Достижимость.** Каждый запуск приложения. Оба вызова проходят через
`syncMutex`, поэтому гонки нет; второй заход идёт инкрементальным путём с
курсором, только что обновлённым первым, и обычно возвращает пустой список.
Цена — лишний запрос к Pi на каждый старт плюс лишняя запись `recordLivePoll`.

**Фикс.** Оставить один триггер: подписка на `isLoggedIn` нужна для реакции на
вход, `onStart` — на возврат в приложение; достаточно пропускать первую эмиссию
коллектора (`drop(1)` или сравнение с предыдущим значением).

---

## U1. Низко — `computeProfileTotals` мёртвый код

**Файл:** [`ui/calendar/ProfileTotalsCalculator.kt:39–114`](../../app/src/main/java/ru/greemlab/neiro/ui/calendar/ProfileTotalsCalculator.kt)

**Механизм.** `computeProfileTotals` и модель `ProfileTotals` (123 строки)
считают сводку по всем записям: прошлые и будущие занятия, заработано, чистыми,
ожидается от будущих.

**Достижимость.** Ни один экран их не вызывает — единственные ссылки во всём
`app/src` находятся в самом файле и в `ProfileTotalsCalculatorTest`. Функция
живая на вид, покрыта тестами и считает деньги по формуле, отличной от той, что
реально показывается пользователю (`netEarned` берёт налог из профиля для всех
месяцев, тогда как экраны используют налог месяца из истории ЗП). Взять её в
работу «как готовую» — значит получить третье число за тот же период.

**Фикс.** Удалить вместе с тестом либо, если сводка нужна, привести её к
`computeMonthEarnings` и вывести на экран.

---

## U2. Низко — годовой налог считается, но нигде не выводится

**Файл:** [`ui/calendar/ProfileYearStats.kt:191`](../../app/src/main/java/ru/greemlab/neiro/ui/calendar/ProfileYearStats.kt)

**Механизм.**

```kotlin
val totalTaxAmount = profileRates.monthlyTaxAmount * elapsedMonthsInYear(year, today)
```

Формула налога за год берёт **текущий налог из профиля**, а месячная чистая
прибыль рядом (`monthlyNet`) считается по налогу месяца из истории ЗП
(`MonthEntry.tax`, [`MonthRatesResolver.kt:149`](../../app/src/main/java/ru/greemlab/neiro/ui/calendar/MonthRatesResolver.kt)).
Налог месяца фиксируется при первой записи и больше не обновляется:

```kotlin
// SalaryLedgerRules.mergeFact, :157
tax = existing?.tax?.takeIf { it > 0.0 } ?: profile.monthlyTaxAmount,
```

Поэтому после правки налога в профиле `totalTaxAmount` и сумма месячных значений
описывают разные вселенные.

**Достижимость.** Поле нигде не выводится: во всём `app/src/main` оно встречается
только в двух `@Preview`-блоках
([`ProfileYearStatsSection.kt:1278`](../../app/src/main/java/ru/greemlab/neiro/ui/profile/ProfileYearStatsSection.kt),
[`ProfileContent.kt:790`](../../app/src/main/java/ru/greemlab/neiro/ui/profile/ProfileContent.kt)).
Формула при этом закреплена четырьмя тестами в `ProfileYearStatsTest` — то есть
поддерживается число, которого никто не видит.

Аудит 30.07.2026 (`U2`) просил «удалить или считать по месяцам с доходом».
Формулу исправили (`elapsedMonthsInYear`), но поле так и не показали — и оно
разошлось с соседними значениями. Это не регрессия фикса, а его незакрытая
половина.

**Фикс.** Либо удалить поле вместе с тестами, либо выводить его на экране и
считать суммой `monthRates.monthlyTaxAmount` по тем же месяцам, по которым
считается `monthlyNet` — тогда «чистыми за год» и «налог за год» будут сходиться.

---

## Сверка фиксов аудита 30.07.2026

Пройдены все пункты [ROADMAP.md](../archive/audit-30.07.26/ROADMAP.md) прошлого
пакета (влиты коммитом `e4a62ec`, PR #27). **Регрессий не найдено.**

| Пункт | Где сейчас | Статус |
|---|---|---|
| `S1` logout не отменяет догон | `LogoutCoordinator:33` + тройная проверка `isLoggedIn` в `PushEventsSyncer` | на месте |
| `K1`/`E1` админский ключ | `verify_admin_api_key` сверяет только `admin_api_key`; `admin_api_key: str` без дефолта — сервис не поднимется без него | на месте |
| `S3` курсор догона | `PushEventsSyncer:70` — курсор и ack только после успешного применения | на месте |
| `S2` keepalive ретраит и планирует | `PushKeepAliveWorker:47` — `Result.success()` + `finally { if (!isStopped) … }` | на месте |
| `N1` лента без разрешения | `SessionNotificationDisplay:50` — запись в ленту до проверки разрешения | на месте |
| `D1` прогрев `TokenStorage` | `NeiroApplication:32` — все `initialize` на IO | на месте |
| `D2` запоздавший 401 | `sessionGeneration` в `YClientsRepository:73, 377` | на месте |
| `S4` счётчик интенсивов | `mergeIntensivesFromApi:645` — `if (localMatch == null) added++` | на месте |
| `B1` тесты пакета `push` | `PushSessionEventTest`, `PushEventCalendarApplierTest` | на месте |
| `E3` дифф состояний | `database._write_record_states` пишет только изменившееся | перенесено в `neiro-push` |
| `E2` шапка дашборда | `collect_status_data` — отдельный лёгкий сбор | перенесено |
| `E4` окно суток | `utc_iso_days_ago` в `stats()` и `poll_errors_today()` | перенесено |
| `E5` индекс доставок | `idx_deliveries_device (device_id, id DESC)` | перенесено |
| `E6` `assert` на сироте | `admin_revoke_device` отдаёт 404 | перенесено |
| `K7` `PRAGMA foreign_keys` | `Database.connect()` ставит на каждое соединение | перенесено |
| `B2` proguard | правила на удалённый воркер нет; широкое `push.**` оставлено с обоснованием в комментарии | на месте |
| `B3` мёртвое расписание | `LiveApiPollSchedule.kt` удалён | на месте |
| `U1` KDoc слияния | описание двух режимов соответствует коду | на месте |
| `U2` годовой налог | формула исправлена (`elapsedMonthsInYear`), но поле по-прежнему не выводится — см. [U2](#u2-низко--годовой-налог-считается-но-нигде-не-выводится) | половина |
| `U3` смена типа услуги | `updateEntryFromRecord:842–849` пересобирает запись | на месте |
| `K2`–`K6` старого `server/` | сервис удалён из репозитория 13.08.2026 | сняты |

---

## Что осталось незакрытым с прошлых заходов

- **Ротация `NEIRO_PUSH_API_KEY`** — из репозитория подтвердить нельзя. Ключи
  из git-истории (`app-release.aab` в коммитах `315d125`, `911d5be`)
  по-прежнему считаются скомпрометированными. Важно: `YCLIENTS_PARTNER_TOKEN`
  из APK **ушёл** — он живёт в `.env` на Pi, и менять его теперь можно без
  пересборки.
- **Переписывание git-истории** (`git filter-repo`) — не выполнялось, решение
  пользователя.
- **Firebase BOM 33 → 34** — не обновлялся, остаётся отдельным заходом.
- **`REPORT.md` пакета 30.07.2026 не написан** — фиксы влиты `e4a62ec`, но
  отчёт по ним не оформлен. Сверка выше его частично заменяет.
- **Ночное окно опроса на сервере** (`QUIET_START_HOUR` без парного «конца
  тишины»: при `23` ночной режим длится с 23:00 до 23:59) — обсуждалось
  11.08.2026, решение отложено осознанно. Находкой не считается.
- **Низкие `N11`, `N12`, `U10`–`U12`, `P9`** из пакета 23.07.26 — не делались.
