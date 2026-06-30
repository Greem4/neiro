# Этап 5 — Data слой (network, storage)

**Уровень риска:** Средний. Меняем поведение пагинации, обработку 401 и поиск staff. Тестируется на реальном входе/выходе и большом календаре.
**Зависимости:** —
**Acceptance:**
- [x] HTTP 401 в `login()`/`fetchRecords`/`getClients` → автоматический `tokenStorage.clear()` + понятное сообщение пользователю.
- [x] `MIN_NAME_MATCH_SCORE = 2` (или 1 с явным fallback на 1 при коротком имени).
- [x] `getClients()` использует пагинацию (как `fetchRecords`).
- [x] `TokenStorage.createSecurePrefs` fallback логирует ошибку.
- [x] `DEFAULT_COMPANY_ID = 0` (или удалить совсем).
- [x] `parseErrorMessage` не съедает все exceptions молча — лог.

---

## Файлы для правки

1. `app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt`
2. `app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt`

---

## 5.1 HTTP 401: автоматический logout

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt`

### Проблема

Когда YClients возвращает 401 (user_token истёк, либо partner_token отозван), приложение получает `ApiResult.Error(code=401)`, показывает «Ошибка авторизации» и **продолжает делать запросы** с тем же недействительным токеном. Live-poll и WorkManager-задачи спамят 401 до явного логаута.

### Правка

#### 5.1.1 Helper для обработки 401

В `YClientsRepository.kt`, в самом конце класса (перед `companion object`, ~строка 297):

```kotlin
    private fun handleUnauthorized(code: Int?) {
        if (code == 401) {
            tokenStorage.clear()
        }
    }
```

#### 5.1.2 Применить в `fetchRecords` (185-188):

Заменить:

```185:188:app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt
            return ApiResult.Success(all)
        } catch (e: Exception) {
            return ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }
```

И блок выше (158-164):

```158:164:app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt
                if (!response.isSuccessful) {
                    return ApiResult.Error(
                        message = "Ошибка загрузки записей",
                        code = response.code(),
                    )
                }
```

**Заменить блок с 158-164 на:**

```kotlin
                if (!response.isSuccessful) {
                    handleUnauthorized(response.code())
                    return ApiResult.Error(
                        message = if (response.code() == 401) {
                            "Сессия истекла. Войдите ещё раз."
                        } else {
                            "Ошибка загрузки записей"
                        },
                        code = response.code(),
                    )
                }
```

#### 5.1.3 `login()` (51-78):

```51:78:app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt
    suspend fun login(login: String, password: String): ApiResult<AuthData> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.auth(AuthRequest(login, password))

                if (response.isSuccessful) {
                    ...
                } else {
                    val errorBody = response.errorBody()?.string()
                    ApiResult.Error(
                        message = parseErrorMessage(errorBody) ?: "Ошибка авторизации",
                        code = response.code(),
                    )
                }
            } catch (e: Exception) {
                ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
            }
        }
```

> `login` сам делает auth — здесь 401 это «неверный логин», `tokenStorage` логично НЕ очищать (там и так может быть пусто). Но сообщение можно уточнить: 401 → «Неверный логин или пароль», 403 → «Доступ запрещён». Минимально: оставить как есть.

#### 5.1.4 `getClients` (246-268):

Заменить:

```246:268:app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt
    suspend fun getClients(): ApiResult<List<ClientData>> = withContext(Dispatchers.IO) {
        try {
            val response = api.getClients(
                companyId = tokenStorage.companyId,
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    ApiResult.Success(body.data.orEmpty())
                } else {
                    ApiResult.Error("Не удалось получить клиентов")
                }
            } else {
                ApiResult.Error(
                    message = "Ошибка загрузки клиентов",
                    code = response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }
```

**На (с пагинацией + 401 handling) — см. пункт 5.2 ниже.**

**Коммит:** `Очищаю токены при 401 от YClients`

---

## 5.2 `getClients()` с пагинацией

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt`

### Проблема

`getClients()` запрашивает одну страницу (`count=200` по умолчанию в `YClientsApi.kt:64`). Если у филиала >200 клиентов — обрезка. Также нет 401 handling.

### Заменить полностью (246-268):

```kotlin
    suspend fun getClients(): ApiResult<List<ClientData>> = withContext(Dispatchers.IO) {
        try {
            val all = mutableListOf<ClientData>()
            var page = 1
            var lastPageSize = 0

            while (page <= MAX_PAGES) {
                val response = api.getClients(
                    companyId = tokenStorage.companyId,
                    page = page,
                    count = PAGE_SIZE,
                )

                if (!response.isSuccessful) {
                    handleUnauthorized(response.code())
                    return@withContext ApiResult.Error(
                        message = if (response.code() == 401) {
                            "Сессия истекла. Войдите ещё раз."
                        } else {
                            "Ошибка загрузки клиентов"
                        },
                        code = response.code(),
                    )
                }

                val body = response.body()
                if (body?.success != true) {
                    return@withContext ApiResult.Error("Не удалось получить клиентов")
                }

                val pageData = body.data.orEmpty()
                lastPageSize = pageData.size
                all += pageData
                if (pageData.size < PAGE_SIZE) break
                page++
            }

            if (lastPageSize >= PAGE_SIZE && page > MAX_PAGES) {
                return@withContext ApiResult.Error(
                    "Слишком много клиентов — список обрезан",
                )
            }

            ApiResult.Success(all)
        } catch (e: Exception) {
            ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }
```

> `PAGE_SIZE` (200) и `MAX_PAGES` (50) уже есть в companion object этого класса.

**Коммит:** `Добавил пагинацию в getClients`

---

## 5.3 `MIN_NAME_MATCH_SCORE = 2`

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt`
**Строки:** 306-311

### Проблема

С `MIN_NAME_MATCH_SCORE = 1` тёзка из филиала, у которого с владельцем приложения хоть один общий токен (имя/фамилия/отчество), может быть выбран как `staffId`. Тогда календарь покажет **чужое** расписание.

**Пример:** Владелец «Зеленкина Светлана Васильевна», в филиале есть «Иванова Светлана». Один общий токен («светлана») → выбран Иванова, чужое расписание.

### Сейчас:

```306:311:app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt
        /**
         * Минимальное число совпавших токенов имени для матча сотрудника.
         * Берём 1 — этого достаточно для поиска, если имя в профиле короткое.
         * Приоритет всё равно у тех, у кого совпадений больше.
         */
        private const val MIN_NAME_MATCH_SCORE = 1
```

### Заменить на:

```kotlin
        /**
         * Минимальное число совпавших токенов имени для матча сотрудника.
         * 2 — нужны минимум 2 общих токена (имя+фамилия), чтобы исключить
         * ложный матч по одному имени, когда в филиале несколько тёзок.
         */
        private const val MIN_NAME_MATCH_SCORE = 2
```

И добавить **fallback** для коротких имён в `detectAndSaveStaffId` (~строка 222):

Сейчас:

```kotlin
            val match = staffList
                .mapNotNull { staff ->
                    val staffTokens = staff.name?.normalizeNameTokens() ?: emptySet()
                    if (staffTokens.isEmpty()) return@mapNotNull null
                    val score = (staffTokens intersect needleTokens).size
                    if (score < MIN_NAME_MATCH_SCORE) null else staff to score
                }
                .sortedWith(...)
                .firstOrNull()
                ?.first
```

**Заменить на:**

```kotlin
            // Если у пользователя в профиле YClients только 1 токен (например, "Светлана"),
            // понизим требование, иначе совсем не найдём.
            val minScore = MIN_NAME_MATCH_SCORE.coerceAtMost(needleTokens.size)

            val match = staffList
                .mapNotNull { staff ->
                    val staffTokens = staff.name?.normalizeNameTokens() ?: emptySet()
                    if (staffTokens.isEmpty()) return@mapNotNull null
                    val score = (staffTokens intersect needleTokens).size
                    if (score < minScore) null else staff to score
                }
                .sortedWith(
                    compareByDescending<Pair<StaffData, Int>> { (it.first.fired ?: 0) == 0 }
                        .thenByDescending { it.second },
                )
                .firstOrNull()
                ?.first
```

**Коммит:** `Поднял минимальный score сопоставления сотрудника`

---

## 5.4 `TokenStorage`: логирование fallback

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt`
**Строки:** 114-130

### Проблема

```kotlin
return try {
    val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    EncryptedSharedPreferences.create(...)
} catch (_: Throwable) {
    fallback
}
```

Если `EncryptedSharedPreferences.create()` бросит exception (corruption keystore, нестандартный OEM) — токены тихо уезжают в **обычный** `SharedPreferences`. Это уже не security guarantee, но приложение работает «как будто всё ок».

### Заменить (114-130):

```kotlin
    private fun createSecurePrefs(appContext: Context): SharedPreferences {
        val fallback = appContext.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_SECURE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            android.util.Log.e(
                "TokenStorage",
                "EncryptedSharedPreferences failed, falling back to plain prefs. " +
                    "Токены YClients будут храниться нешифрованно на этом устройстве.",
                t,
            )
            fallback
        }
    }
```

**Почему:** При проблеме появится лог в `adb logcat` для диагностики. Не падаем (это сломало бы install обновления у пользователей с corrupted keystore), но видим причину.

**Опционально**, более защищённый вариант: вернуть `null` и **запретить login** до решения проблемы (`createSecurePrefs(): SharedPreferences?`). Это меняет API и требует обработки везде — **не делаем**, оставляем fallback с логом.

**Коммит:** `Логирую fallback EncryptedSharedPreferences`

---

## 5.5 `DEFAULT_COMPANY_ID = 0`

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt`
**Строки:** 145, 64-69

### Проблема

`DEFAULT_COMPANY_ID = 520135` — это конкретный филиал автора приложения. Если кто-то склонирует репо и забудет настроить `YCLIENTS_COMPANY_ID` в `local.properties` — приложение начнёт делать запросы к чужому филиалу. Это юридическая проблема (попытка авторизации к чужой компании).

### Сейчас (63-69):

```63:69:app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt
    var companyId: Int
        get() {
            val fromPrefs = prefs.getInt(KEY_COMPANY_ID, -1)
            if (fromPrefs > 0) return fromPrefs
            val fromBuildConfig = BuildConfig.YCLIENTS_COMPANY_ID
            return if (fromBuildConfig > 0) fromBuildConfig else DEFAULT_COMPANY_ID
        }
```

### Заменить на:

```kotlin
    var companyId: Int
        get() {
            val fromPrefs = prefs.getInt(KEY_COMPANY_ID, -1)
            if (fromPrefs > 0) return fromPrefs
            return BuildConfig.YCLIENTS_COMPANY_ID.takeIf { it > 0 } ?: 0
        }
```

И удалить константу:

```145:145:app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt
        private const val DEFAULT_COMPANY_ID = 520135
```

— удалить эту строку.

> Если `companyId == 0`, то YClients API вернёт 404. Это лучше, чем тихая работа с чужой компанией.

**Коммит:** `Убрал хардкод дефолтного company id`

---

## 5.6 `parseErrorMessage` — не глотать exception молча

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt`
**Строки:** 288-297

### Проблема

```kotlin
private fun parseErrorMessage(errorBody: String?): String? {
    if (errorBody.isNullOrBlank()) return null
    return try {
        val gson = com.google.gson.Gson()
        val error = gson.fromJson(errorBody, ApiError::class.java)
        error.meta?.message
    } catch (e: Exception) {
        null
    }
}
```

При parse error — `null`, пользователь видит generic «Ошибка авторизации» без причины. Лог тоже не пишется. Сложно диагностировать API-сюрпризы.

### Заменить (288-297):

```kotlin
    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val gson = com.google.gson.Gson()
            val error = gson.fromJson(errorBody, ApiError::class.java)
            error.meta?.message
        } catch (e: Exception) {
            android.util.Log.w("YClientsRepository", "Cannot parse error body: $errorBody", e)
            null
        }
    }
```

**Коммит:** `Логирую неразобранные ошибки YClients`

---

## 5.7 *(Опционально)* `YClientsModels.kt` — nullable поля

Если в `app/src/main/java/ru/greemlab/neiro/data/network/YClientsModels.kt` есть non-nullable поля для API-ответов, которые YClients реально может пропустить — конвертация Gson выдаст `null` через рефлексию и при первом разыменовании случится NPE.

**Поиск:**
```
rg 'class.*Data\(' app/src/main/java/ru/greemlab/neiro/data/network/YClientsModels.kt
```

Прогон вручную: открыть, найти поля без `?`, проверить против документации YClients (`developer.yclients.com`). Если поле опциональное по докам — сделать nullable.

**Пример (типичный):** `services: List<ServiceData>` → `services: List<ServiceData>?`.

> Это потенциально breaking change в коде, который читает поле. Делать осторожно — лучше проверить точечно по NPE в Crashlytics (или вручную по краш-логам). Если нет crash-логов — **отложить**, см. OUT_OF_SCOPE.md.

**Коммит:** `Сделал поля YClients моделей nullable где документация позволяет`

---

## Финальная проверка этапа

1. **`ReadLints`** для всех правленых файлов.
2. **Поиск `DEFAULT_COMPANY_ID`**:
   ```
   rg 'DEFAULT_COMPANY_ID|520135' app/src/main
   ```
   Не должно остаться ни одного места.
3. **Поиск `MIN_NAME_MATCH_SCORE`**: должен быть `= 2`.
4. **Поиск `getClients`**:
   ```
   rg 'getClients\(' app/src/main
   ```
   Все вызовы должны работать без изменений — мы изменили внутренности, API остался.
5. **Поиск 401 handling**:
   ```
   rg 'handleUnauthorized|response\.code\(\) == 401' app/src/main
   ```
   Минимум 2 места: `fetchRecords` и `getClients`.

## Коммиты этапа (порядок)

1. `Очищаю токены при 401 от YClients`
2. `Добавил пагинацию в getClients`
3. `Поднял минимальный score сопоставления сотрудника`
4. `Логирую fallback EncryptedSharedPreferences`
5. `Убрал хардкод дефолтного company id`
6. `Логирую неразобранные ошибки YClients`
