# Прогон фиксов по аудиту 14.08.2026

Рабочий файл: по нему идут правки, одна за другой. Каждый пункт — законченная
порция и один коммит. Отмечать `[x]` по мере выполнения прямо здесь.

Разбор находок — [FINDINGS.md](FINDINGS.md), обоснование порядка —
[ROADMAP.md](ROADMAP.md). Здесь только «что сделать руками».

---

## Правила прогона

1. **Один пункт — один коммит.** Сообщение берётся из пункта как есть: одна
   строка, до 72 символов, на русском, без тела ([`CLAUDE.md`](../../CLAUDE.md)).
2. **Не сваливать пункты в кучу.** В таком коммите незамеченной проезжает
   поломка, и откатить по частям его нельзя.
3. **Gradle не запускает агент.** `./gradlew testDebugUnitTest` и сборку гоняет
   пользователь; агент проверяет только линты и читает код.
4. **Порядок волн менять можно, порядок внутри волны — нет.** Внутри волны
   каждый следующий пункт ложится на уже исправленное.
5. **Расходится с реальностью — остановиться и спросить.** Если код в файле не
   похож на описанный здесь (успела уехать другая правка) — не подгонять, а
   выяснить.
6. **Пункт со звёздочкой (★) требует решения пользователя** до начала работы.
7. После каждой волны — блок «Проверка волны» внизу соответствующего раздела.
   Пока он не пройден, следующая волна не начинается.

Состояние прогона: **15 из 18 пунктов.**

---

# Волна 1 — потеря данных и доступа

Три пункта. После них систему можно оставить в покое и спокойно смотреть
остальное.

## [x] 1.1 · S1 · Не стираю месяц по пустому ответу в live-синке

**Находка:** [S1](FINDINGS.md#s1-высоко--инкрементальный-live-sync-стирает-текущий-месяц-при-пустом-ответе-api) · Высоко
**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt`

**Что сделать.** В `mergeIncrementalRecords` (:476) перед вызовом
`mergeRecordsToCalendar` для текущего месяца прогнать ответ через ту же защиту,
что стоит в `syncDateRangeLocked` (:171):

```kotlin
is ApiResult.Success -> {
    val dayDataBefore = calendarRepository.dayDataFlow.first()
    if (!shouldApplySyncMerge(fullDays.data, dayDataBefore, subStart, subEnd)) {
        // Пустой ответ при живом календаре — сбой, а не «месяц опустел».
        // Метку live-опроса не двигаем: следующий тик обязан повторить.
        return SyncOutcome.Failure(
            "YClients вернул пустой месяц — слияние пропущено, данные сохранены",
        )
    }
    syncedCount += mergeRecordsToCalendar(...)
}
```

Проверить, что `Failure` из этой ветки не двигает `syncPreferences.recordLivePoll()`
в `refreshLiveIncrementalLocked` (:138) — сейчас метка ставится после проверки
`outcome is SyncOutcome.Failure`, убедиться, что порядок сохранён.

**Тест (обязателен).** В `YClientsCalendarSyncTest`: инкрементальный синк, где
перезапрос текущего месяца вернул пустой список, а в календаре есть ученики —
`dayData` не изменилась. Это тот же класс, что ловил аудит 23.07.26 в полном
синке.

**Проверка руками.** Открыть приложение при живом календаре текущего месяца
несколько раз подряд — месяц на месте, уведомлений об «удалении» нет.

**Коммит:** `Не стираю месяц по пустому ответу в live-синке`

---

## [x] 1.2 · K2 · Не выкидываю устройство из-за мёртвого токена FCM

**Находка:** [K2](FINDINGS.md#k2-высоко--невалидный-fcm-токен-выкидывает-пользователя-из-аккаунта) · Высоко
**Файлы:** `neiro-push/app/poller.py`, `neiro-push/app/database.py`

**Что сделать.**

1. В `database.py` рядом с `update_device_fcm` завести метод, обнуляющий токен:

```python
def clear_device_fcm(self, device_id: str) -> None:
    """Токен пуша умер, а доступ жив: device_token остаётся рабочим.

    Удалять строку нельзя — в ней token_hash, и телефон получил бы 401,
    то есть полный выход из аккаунта из-за проблемы с доставкой пуша.
    """
```

2. В `poller.py:329` заменить `delete_device` на новый метод, лог оставить:

```python
if result.token_invalid:
    for event_id in event_ids:
        self._db.record_push_delivery(event_id, device_id, "token_invalid", None)
    self._db.clear_device_fcm(device_id)
    logger.warning("device %s: FCM token invalid, cleared", device_id)
    return False
```

Устройство с пустым `fcm_token` поллер и так пропускает (`_poll_account:267`), а
новый токен телефон пришлёт сам через `POST /v1/devices/fcm`.

3. `delete_device` оставить — им пользуются admin API и дашборд.

**Тест.** В `neiro-push/tests/test_poller.py`: ответ FCM `UNREGISTERED` → строка
устройства на месте, `fcm_token` пуст, `token_hash` не тронут.

**Коммит:** `Не выкидываю устройство из-за мёртвого токена FCM`

---

## [x] 1.3 · K1 · Считаю лимит входа по настоящему адресу клиента

**Находка:** [K1](FINDINGS.md#k1-высоко--лимит-попыток-входа-обходится-подменой-заголовка) · Высоко
**Файл:** `neiro-push/app/ratelimit.py`

**Перед началом — проверить на VPS**, что nginx ставит `X-Real-IP`:

```
ssh <vps> "grep -n 'X-Real-IP\|X-Forwarded-For' /etc/nginx/sites-enabled/*"
```

В [`scripts/patch-vps-nginx-v1.sh:44`](../../neiro-push/scripts/patch-vps-nginx-v1.sh)
он есть (`proxy_set_header X-Real-IP $remote_addr`), но живой конфиг мог
разойтись. **Если заголовка нет — остановиться и сказать пользователю:** фикс
без него не работает, править надо nginx.

**Что сделать.** Переписать `client_ip` (:68):

```python
def client_ip(request) -> str:
    """IP клиента с поправкой на nginx.

    Берём X-Real-IP: его nginx ставит из $remote_addr, и подменить его клиент
    не может. X-Forwarded-For для этого не годится — директива
    $proxy_add_x_forwarded_for дописывает настоящий адрес в КОНЕЦ списка,
    а первым остаётся то, что прислал клиент. Считать лимит по нему значит
    выдавать каждому запросу свежий пустой счётчик.
    """
    real_ip = request.headers.get("x-real-ip", "").strip()
    if real_ip:
        return real_ip
    return request.client.host if request.client else "unknown"
```

**Тест.** В `neiro-push/tests/test_ratelimit.py`: запрос с
`X-Forwarded-For: 1.2.3.4` и `X-Real-IP: 5.6.7.8` даёт `5.6.7.8`; шесть попыток
входа с разными `X-Forwarded-For`, но одним `X-Real-IP` — шестая получает `429`.

**Коммит:** `Считаю лимит входа по настоящему адресу клиента`

---

### Проверка волны 1

- [ ] `./gradlew testDebugUnitTest` зелёный, новый тест на `S1` проходит;
- [ ] `pytest neiro-push/tests` зелёный;
- [ ] сервис поднялся после правки (`docker compose logs`);
- [ ] пять неудачных входов подряд с телефона дают `429`, в логе есть
      `login rate limit hit`;
- [ ] уведомления о занятиях приходят как прежде (K2 трогает путь доставки).

---

# Волна 2 — нагрузка на Pi и потеря событий

## [x] 2.1 · K3 · Чищу состояния прошедших записей

**Находка:** [K3](FINDINGS.md#k3-средне--record_states-растёт-без-границ) · Средне
**Файл:** `neiro-push/app/database.py`

**Что сделать.** В `purge_old_data` (:800) добавить четвёртый `DELETE`:

```python
# record_states растёт только вверх: инкрементальный опрос надстраивает
# снимок поверх прежнего и никогда ничего не удаляет. Записи с датой в
# прошлом в окно опроса уже не попадают — диффу они не нужны.
conn.execute(
    "DELETE FROM record_states WHERE date < ?",
    (datetime.now(timezone.utc).date().isoformat(),),
)
```

Дата в `record_states.date` лежит в формате `YYYY-MM-DD` (см.
`yclients._extract_date`), поэтому строковое сравнение здесь корректно — в
отличие от `created_at`, где формат `utc_now_iso` и `datetime('now')`
расходятся.

**Тест.** В `test_database.py`: состояние с датой вчера уходит, с датой сегодня
и завтра — остаётся.

**Проверка.** `SELECT COUNT(*) FROM record_states` перестаёт расти день ото дня.

**Коммит:** `Чищу состояния прошедших записей`

---

## [x] 2.2 · K4 · Поднимаю повторный вход по 401 в поллере

**Находка:** [K4](FINDINGS.md#k4-средне--401-от-yclients-в-поллере-не-поднимает-reauth_required) · Средне
**Файлы:** `neiro-push/app/poller.py`, `neiro-push/app/yclients.py`

**Что сделать.** В `_poll_company` (:165) отделить отказ доступа от прочих
ошибок — сейчас `raise_for_status()` отдаёт `httpx.HTTPStatusError`, и он падает
в общий `except` наравне с таймаутом:

```python
for candidate in active_accounts:
    try:
        user_token = self._secret_box.decrypt(candidate.user_token_enc)
        records = await self._yclients.fetch_company_records(...)
        break
    except httpx.HTTPStatusError as exc:
        if exc.response.status_code in (401, 403):
            # Тот же счётчик, что и в прокси: по одному 401 флаг не ставим —
            # авария на стороне YClients разлогинила бы всех разом.
            self._db.note_upstream_auth_failure(candidate.id)
        error_message = f"HTTP {exc.response.status_code}"
        logger.warning(...)
    except Exception as exc:
        error_message = str(exc)[:500]
        logger.warning(...)
```

Аккаунт с взведённым `reauth_required` `poll_once` уже пропускает (:91), так что
вечный backoff прекратится сам.

**Тест.** В `test_poller.py`: три цикла с 401 от YClients → `reauth_required = 1`,
аккаунт выпадает из списка опроса.

**Коммит:** `Поднимаю повторный вход по 401 в поллере`

---

## [x] 2.3 · K5 · Сидирую аккаунты по отдельности

**Находка:** [K5](FINDINGS.md#k5-средне--сидирование-включается-на-всю-компанию) · Средне
**Файл:** `neiro-push/app/poller.py`

**Что сделать.** Сейчас `seeding` — признак компании (:157), и приход нового
сотрудника съедает цикл событий у уже работающих. Разделить два решения:

- **запрос** остаётся общим: если сидируется хоть кто-то, `changed_after=None`
  (полный горизонт нужен новичку);
- **разбор** становится персональным: в `_poll_account` решать по
  `self._db.has_record_states(account.id)`, а не по переданному флагу.

```python
# _poll_company
needs_full_fetch = any(not self._db.has_record_states(a.id) for a in active_accounts)
changed_after = None if needs_full_fetch else self._company_changed_after(active_accounts)
...
created, sent, account_error = await self._poll_account(account, account_records)

# _poll_account
async def _poll_account(self, account, records):
    previous_states = self._db.get_record_states(account.id)
    if not previous_states:          # сидируется именно этот аккаунт
        ...
```

Признак «сидируется» и так уже равен «нет состояний» — `has_record_states`
проверяет ровно это, и `previous_states` в `_poll_account` уже прочитан.

**Тест.** В `test_poller.py`: у аккаунта A состояния есть, у B нет; в цикле,
где B сидируется, изменение у A порождает событие.

**Коммит:** `Сидирую аккаунты по отдельности`

---

## [x] 2.4 · K6 · Не перепривязываю чужое устройство при входе

**Находка:** [K6](FINDINGS.md#k6-низко--вход-перепривязывает-чужой-device_id) · Низко
**Файлы:** `neiro-push/app/auth.py`, `neiro-push/app/database.py`

**Что сделать.** В `login` (:227) перед `upsert_device` проверить владельца:

```python
existing = db.get_device(body.device_id)
if existing is not None and existing.account_id != account_id:
    # Тот же device_id под другим аккаунтом: перезапись убила бы токен
    # чужого телефона и увела бы на него пуши. Это не «повторный вход».
    logger.warning(
        "device %s belongs to account %s, login from account %s rejected",
        body.device_id, existing.account_id, account_id,
    )
    raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="device_taken")
```

В приложении `409` уже обрабатывается как «нужен повторный вход» — проверить
текст в `YClientsRepository.loginErrorMessage` (:719) и, если он про имя
сотрудника, добавить различение по `detail`. **Если различать нечем — оставить
общий текст и записать это в пункте, а не выдумывать новый код ответа.**

**Тест.** В `test_auth.py`: вход аккаунтом B с `device_id` аккаунта A → `409`,
`token_hash` устройства A не изменился.

**Коммит:** `Не перепривязываю чужое устройство при входе`

---

### Проверка волны 2

- [ ] `pytest neiro-push/tests` зелёный;
- [ ] `/v1/admin/poll-log` — циклы идут, `events_created` совпадает с
      фактическими изменениями в YClients;
- [ ] дашборд открыт полчаса — `duration_ms` циклов не растёт;
- [ ] `SELECT COUNT(*) FROM record_states` не растёт день ото дня;
- [ ] аккаунт с намеренно испорченным `user_token` уходит в `reauth_required`,
      а не в вечный backoff.

---

# Волна 3 — сборка и проверки

## [x] 3.1 · B2 · Гоняю тесты бэкенда в CI

**Находка:** [B2](FINDINGS.md#b2-средне--ci-не-гоняет-тесты-бэкенда) · Средне
**Файл:** `.github/workflows/ci.yml`

**Идёт первым в волне:** покажет, не сломала ли волна 2 что-нибудь в уже
написанных тестах бэкенда.

**Что сделать.** Добавить job рядом с `test`:

```yaml
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Зависимости
        run: python -m pip install -r neiro-push/requirements.txt
      - name: Тесты бэкенда
        run: python -m pytest neiro-push/tests
        working-directory: .
```

Проверить версию Python против `neiro-push/Dockerfile` — расходиться они не
должны. Рабочий каталог тестов уточнить по `pytest.ini`/`pyproject.toml`, если
они есть; импорты в тестах идут от `app.`, значит запускать нужно из
`neiro-push/`.

**Коммит:** `Гоняю тесты бэкенда в CI`

---

## [x] 3.2 · B1 · Обновил baseline-профиль под текущие сигнатуры

**Находка:** [B1](FINDINGS.md#b1-средне--baseline-prof-разошёлся-с-кодом) · Средне
**Файл:** `app/src/main/baseline-prof.txt`

**Что сделать.** Переписать пять разошедшихся правил под текущие сигнатуры —
список расхождений в таблице находки. Дескрипторы брать не на глаз, а из
собранного APK:

```
unzip -p app/build/outputs/apk/release/neiro-*.apk classes.dex > /tmp/c.dex
# затем dexdump/baksmali и грепом по имени метода
```

**Правило пункта:** правило, чью сигнатуру не удалось подтвердить по dex, из
файла удаляется, а не остаётся «на всякий случай» — молча не матчащееся правило
хуже отсутствующего, потому что создаёт видимость покрытия.

★ **Решение пользователя (14.08.26): оба пути.** Сигнатуры чиним сейчас —
профиль начинает работать без устройства и без сборки; следом заводим модуль
`:baselineprofile`, чтобы впредь файл генерировался прогоном и разойтись не
мог. Отдельным коммитом.

**Коммит:** `Обновил baseline-профиль под текущие сигнатуры`

---

## [x] 3.2b · B1 · Генерирую baseline-профиль прогоном на устройстве

**Файлы:** `settings.gradle.kts`, `gradle/libs.versions.toml`,
`app/build.gradle.kts`, `baselineprofile/`

Модуль `com.android.test` + плагин `androidx.baselineprofile` 1.5.0-rc01.
Сценарий — холодный старт (`startActivityAndWait`), устройство подключённое,
managed-девайс не заводим.

**Проверка за пользователем (Gradle агент не запускает):**

```
./gradlew :app:generateReleaseBaselineProfile   # телефон подключён, API 28+
```

Результат ляжет в `app/src/release/generated/baselineProfiles/`. После первого
удачного прогона ручной `app/src/main/baseline-prof.txt` можно удалить.

**Если конфигурация не сойдётся** (плагин 1.5.0-rc01 против AGP 9.2.1 не
проверен — сборку агент не гоняет): откат — убрать строку
`include(":baselineprofile")` из `settings.gradle.kts` и алиас плагина из
`app/build.gradle.kts`. Ручной профиль от этого не пострадает, он уже верный.

**Коммит:** `Генерирую baseline-профиль прогоном на устройстве`

---

## [x] 3.3 · B3 · Убрал неиспользуемую проверку ключа

**Находка:** [B3](FINDINGS.md#b3-низко--verify_api_key-мёртвая-функция) · Низко
**Файл:** `neiro-push/app/main.py`

**Что сделать.** Удалить `verify_api_key` (:105–115) целиком. Перед удалением
убедиться грепом, что её не подключает ни один `Depends`:

```
grep -rn "verify_api_key" neiro-push/
```

Должна остаться единственная строка — само определение. Клиентский ключ
проверяет `require_app_key` в `auth.py`, и это единственное место, где он
уместен.

**Коммит:** `Убрал неиспользуемую проверку ключа`

---

### Проверка волны 3

- [ ] CI на PR зелёный и показывает оба набора тестов;
- [ ] release-сборка собирается и запускается;
- [ ] холодный старт не медленнее прежнего (замер до/после, если правился
      baseline-профиль).

---

# Волна 4 — гигиена и мёртвый код

## [x] 4.1 · A1 · Не качаю обновление повторно поверх готового

**Находка:** [A1](FINDINGS.md#a1-низко--isbusy-не-покрывает-готовность-к-установке) · Низко
**Файлы:** `app/.../update/UpdateState.kt`, `app/.../update/UpdateViewModel.kt`

**Что сделать.**

1. В `UpdateState.isBusy` (:54) добавить два состояния:

```kotlin
val UpdateState.isBusy: Boolean
    get() = this is UpdateState.Checking ||
        this is UpdateState.Downloading ||
        this is UpdateState.Verifying ||
        this is UpdateState.Installing ||
        // Файл уже скачан и проверен: проверять обновления и качать заново
        // отсюда нечего, установка ждёт ответа пользователя.
        this is UpdateState.ReadyToInstall ||
        this is UpdateState.AwaitingConfirmation
```

2. В `UpdateViewModel.install` (:169) поставить тот же guard, что в
   `downloadAndInstall`, и выставить `Installing` **до** запуска корутины —
   иначе двойное нажатие проходит оба раза:

```kotlin
fun install(info: UpdateInfo, apk: File) {
    if (_state.value is UpdateState.Installing) return
    _state.value = UpdateState.Installing(info)
    viewModelScope.launch { ... }
}
```

Проверить, что кнопка «Установить» в `ReadyToInstallBlock` при этом не гаснет:
`AboutScreen` рисует её по состоянию, а не по `isBusy`.

**Проверка руками.** Скачать обновление, закрыть системный диалог, вернуться на
экран — кнопка «Установить» на месте, «Проверить обновления» неактивна,
повторной закачки нет.

**Коммит:** `Не качаю обновление повторно поверх готового`

---

## [x] 4.2 · A2 · Проверяю разрешение на установку один раз за возврат

**Находка:** [A2](FINDINGS.md#a2-низко--проверка-разрешения-на-установку-в-теле-композиции) · Низко
**Файлы:** `app/.../update/UpdateViewModel.kt`, `app/.../ui/settings/AboutScreen.kt`

**Что сделать.** Убрать `viewModel.needsInstallPermission()` из тела композиции
(`AboutScreen.kt:165`) — сейчас это binder-вызов на каждом кадре прогресса, до
пяти раз в секунду. Завести во ViewModel:

```kotlin
private val _needsInstallPermission = MutableStateFlow(!ApkInstaller.canInstall(app))
val needsInstallPermission: StateFlow<Boolean> = _needsInstallPermission.asStateFlow()

/** Вернулись из системных настроек — пересчитать. */
fun refreshInstallPermission() {
    _needsInstallPermission.value = !ApkInstaller.canInstall(app)
}
```

На экране подписаться через `collectAsStateWithLifecycle()` и звать
`refreshInstallPermission()` на `ON_RESUME` (`LifecycleEventEffect` или
`DisposableEffect` с `LifecycleEventObserver`). Тогда подсказка «разрешите
установку» исчезает сразу после возврата из настроек.

`ApkInstaller.canInstall` в `install()` оставить — там проверка живая и нужна.

**Коммит:** `Проверяю разрешение на установку один раз за возврат`

---

## [x] 4.3 · N1 · Чищу ленту уведомлений при выходе из аккаунта

**Находка:** [N1](FINDINGS.md#n1-низко--logout-не-чистит-ленту-уведомлений) · Низко
**Файлы:** `app/.../auth/LogoutCoordinator.kt`, `app/.../notifications/ArchiveNotificationStore.kt`

**Что сделать.**

1. Завести `clearAll()` в `ArchiveNotificationStore` по образцу такого же метода
   в `InAppNotificationStore` (:74).
2. В `LogoutCoordinator.logout` дописать очистку обеих лент — рядом с
   `SessionNotificationCoordinator.onLoggedOut(appContext)`:

```kotlin
// Лента — единственное место, где события аккаунта переживают выход из него:
// после «сменить аккаунт» там оставались имена клиентов прошлого сотрудника.
InAppNotificationStore.get(appContext).clearAll()
ArchiveNotificationStore.get(appContext).clearAll()
```

**Осторожно:** `ArchiveNotificationStore` участвует в экспорте и импорте архива
(`CalendarDataStore.exportAllData/restoreAllData`). Убедиться, что очистка при
логауте не ломает уже сделанный бэкап — она трогает только текущее устройство,
файл экспорта остаётся.

**Проверка руками.** «Сменить аккаунт» → войти → лента уведомлений пуста.

**Коммит:** `Чищу ленту уведомлений при выходе из аккаунта`

---

## [x] 4.4 · S2 · Убрал вторую подтяжку календаря на старте

**Находка:** [S2](FINDINGS.md#s2-низко--двойная-подтяжка-календаря-на-холодном-старте) · Низко
**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt`

**Что сделать.** В `initialize` (:83) коллектор `isLoggedIn` немедленно отдаёт
текущее значение, и на холодном старте вошедшего пользователя `refreshNow`
срабатывает дважды — из него и из `onStart`. Оставить коллектору только реакцию
на **переход**:

```kotlin
scope.launch {
    var wasLoggedIn = yclientsRepository.isLoggedIn.value
    yclientsRepository.isLoggedIn.collect { loggedIn ->
        if (loggedIn && !wasLoggedIn) {
            // Именно вход, а не «уже вошли»: стартовую подтяжку делает onStart.
            if (serverPushActive) PushKeepAliveCoordinator.schedule(appContext)
            refreshNow(appContext)
        } else if (!loggedIn && serverPushActive) {
            PushKeepAliveCoordinator.cancel(appContext)
        }
        wasLoggedIn = loggedIn
    }
}
```

**Не трогать** ветку отмены keepalive при выходе — она обязана срабатывать и на
текущем значении. Проверить, что при первом запуске **после входа** keepalive
всё равно планируется: это делает `PushRegistrar.onLoginSuccess` и
`PushRegistrar.initialize`.

**Коммит:** `Убрал вторую подтяжку календаря на старте`

---

## [x] 4.5 · U1 · Убрал неиспользуемую сводку по всем записям

**Находка:** [U1](FINDINGS.md#u1-низко--computeprofiletotals-мёртвый-код) · Низко
**Файлы:** `app/.../ui/calendar/ProfileTotalsCalculator.kt`,
`app/src/test/.../ui/calendar/ProfileTotalsCalculatorTest.kt`

**Что сделать.** Удалить оба файла целиком. Перед удалением — грепом убедиться,
что ссылок больше нет:

```
grep -rn "computeProfileTotals\|ProfileTotals" app/src
```

Должны остаться только сам файл и его тест. Функция считает деньги по формуле,
отличной от той, что видит пользователь (`netEarned` берёт налог из профиля для
всех месяцев), — оставлять её «на будущее» значит однажды получить третье число
за тот же период.

**Коммит:** `Убрал неиспользуемую сводку по всем записям`

---

## [ ] 4.6 ★ U2 · Годовой налог: удалить или вывести

**Находка:** [U2](FINDINGS.md#u2-низко--годовой-налог-считается-но-нигде-не-выводится) · Низко
**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/calendar/ProfileYearStats.kt`

★ **Нужно решение пользователя.** Пункт аудита 30.07.26 просил «удалить или
считать по месяцам с доходом»; формулу поправили, но поле так и не показали, и
теперь оно расходится с соседними месячными значениями.

**Вариант А — удалить.** Убрать `totalTaxAmount` из `ProfileYearStats`, из
`empty()`, из обоих `@Preview` и четыре проверки из `ProfileYearStatsTest`.
Коммит: `Убрал невыводимый годовой налог`

**Вариант Б — вывести.** Показать «налог за год» рядом с «чистыми за год» на
экране статистики и считать его суммой `monthRates.monthlyTaxAmount` по тем же
месяцам, по которым считается `monthlyNet`, — тогда числа сойдутся:

```kotlin
// Налог месяца берётся из истории ЗП и после правки в профиле не
// пересчитывается. Считать год по сегодняшнему налогу профиля значит
// показать сумму, из которой не складывается ни один месяц рядом.
totalTaxAmount += monthRates.monthlyTaxAmount.takeIf { month <= elapsed } ?: 0.0
```

Коммит: `Свёл годовой налог с месячными значениями`

---

## [ ] 4.7 · D1 · Убрал незавершённый сброс клиентов

**Находка:** [D1](FINDINGS.md#d1-низко--clearinstance-неполон-и-никем-не-вызывается) · Низко
**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt`

**Что сделать.** Удалить `clearInstance()` (:204–210). Функция никем не
вызывается, но сбрасывает `yclientsApi`/`neiroApi`/`retrofit` и **не** сбрасывает
`pushApi` и `tokenStorage` — после первого же вызова половина клиентов осталась
бы от прежней конфигурации. Логаут в ней не нуждается: `TokenStorage.clear()`
меняет то, что читает интерцептор на каждом запросе.

**Коммит:** `Убрал незавершённый сброс клиентов`

---

## [ ] 4.8 · D2 · Чищу все хранилища при отладочном сбросе

**Находка:** [D2](FINDINGS.md#d2-низко--clearalldata-не-чистит-соседние-хранилища) · Низко
**Файл:** `app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt`

**Что сделать.** В `clearAllData()` (:273) дописать очистку соседних хранилищ —
сейчас после «сброса» статистика подхватывает историю ЗП прежней установки:

```kotlin
// DataStore — не всё состояние приложения: история ЗП, метаданные записей и
// ленты уведомлений живут в своих SharedPreferences. Отладочный сброс,
// который их не трогает, чистого старта не даёт.
SalaryLedgerStore.get(appContext).clear()
appContext.deleteSharedPreferences("neiro_session_meta")
InAppNotificationStore.get(appContext).clearAll()
ArchiveNotificationStore.get(appContext).clearAll()
```

У `SalaryLedgerStore` метода очистки нет (только `warmUp`, `update`,
`exportJson`, `importJson`) — завести по образцу `InAppNotificationStore.clearAll()`,
через тот же `update { SalaryLedger.Empty }`, чтобы `_ledger` обновился.
`ArchiveNotificationStore.clearAll()` появляется в пункте 4.3.

**Проверка.** Кнопка отладочного сброса → профиль, календарь, история ЗП и лента
пусты одновременно.

**Коммит:** `Чищу все хранилища при отладочном сбросе`

---

### Проверка волны 4

- [ ] `./gradlew testDebugUnitTest` зелёный после удаления тестов `U1`/`U2`;
- [ ] «Сменить аккаунт» → лента уведомлений пуста;
- [ ] экран «О программе»: скачали, закрыли системный диалог, вернулись —
      кнопка «Установить» на месте, повторной закачки нет;
- [ ] выдать разрешение на установку в системных настройках и вернуться —
      подсказка исчезла без пересоздания экрана.

---

# После прогона

- [ ] Написать `REPORT.md` в этом же каталоге: что сделано по каждому пункту и
      почему, что не сделано и почему. Формат — § 7 методики.
- [ ] Обновить `CHANGELOG.md` (скилл `changelog`) — там раздел для пользователя,
      а не для того, кто читает код.
- [ ] Обновить [`METHODIKA.md`](../audit/METHODIKA.md) § 8: пометить пакет
      выполненным, перенести незакрытое.
- [ ] Перенести каталог в `docs/archive/audit-14.08.26/` — как это сделано с
      прошлыми пакетами.

**Не входит в прогон** (см. [ROADMAP.md § Вне дорожной карты](ROADMAP.md#вне-дорожной-карты)):
ротация `NEIRO_PUSH_API_KEY`, переписывание git-истории, Firebase BOM 33 → 34,
ночное окно опроса.
