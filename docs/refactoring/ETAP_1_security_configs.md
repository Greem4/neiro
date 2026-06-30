# Этап 1 — Безопасность и конфигурация

**Уровень риска:** Низкий (правки сборки и ProGuard могут проявиться только в release).
**Зависимости:** —
**Acceptance:**
- [x] OkHttp в release **не пишет** Authorization в логи и не пишет тела (`Level.HEADERS` + `redactHeader`).
- [x] Release сборка без `RELEASE_STORE_FILE` падает с `GradleException`, а не подписывается debug-ключом.
- [x] Cloud backup **не содержит** `datastore/` (или явно ограничен только архивом, по выбору пользователя).
- [x] ProGuard `-keep` есть для всех Gson-моделей вне `domain/`/`data/network/` (notifications, sync, push).
- [x] Манифест содержит `enableOnBackInvokedCallback="true"` и `tools:targetApi="tiramisu"`.
- [x] `android.usesCleartextTraffic` явно `false` (через `network_security_config.xml`).
- [x] `versions.toml`: явный pinning `foundation = 1.7.0` удалён, foundation тянется через BOM.

---

## Файлы для правки

1. `app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt`
2. `app/proguard-rules.pro`
3. `app/src/main/res/xml/backup_rules.xml`
4. `app/src/main/res/xml/data_extraction_rules.xml`
5. `app/src/main/res/xml/network_security_config.xml` *(новый)*
6. `app/src/main/AndroidManifest.xml`
7. `app/build.gradle.kts`
8. `gradle/libs.versions.toml`

---

## 1.1 OkHttp: убрать body-логи + redact Authorization

**Файл:** `app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt`
**Строки:** 70-76
**Проблема:** В debug Authorization и тела запросов (включая токен авторизации в ответе `/auth`) пишутся в logcat. Если разработчик собирает debug на устройстве пользователя — токены утекают.

**Сейчас:**

```70:76:app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt
        if (BuildConfig.DEBUG) {
            okHttpClientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                },
            )
        }
```

**Заменить на:**

```kotlin
        if (BuildConfig.DEBUG) {
            okHttpClientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                },
            )
        }
```

**Почему:** `Level.HEADERS` показывает URL, статус, headers без тел — диагностика остаётся, секретов в логах нет. `redactHeader` подменяет значение `Authorization` на `█`.

**Коммит:** `Скрыл Authorization и тела в HTTP-логах`

---

## 1.2 ProGuard: keep для Gson-моделей вне `domain/`

**Файл:** `app/proguard-rules.pro`
**Проблема:** В release Gson сериализует через рефлексию модели, у которых нет `@SerializedName`. R8 переименовывает их, JSON ломается:
- `ru.greemlab.neiro.notifications.InAppNotification` (id, title, body, kind, dedupeKey…)
- `ru.greemlab.neiro.notifications.SnapshotDto` (`SessionNotificationPreferences`)
- `ru.greemlab.neiro.notifications.TrackedSession`
- `ru.greemlab.neiro.sync.SyncPreferences.LiveSyncState` *(если есть JSON в prefs)*
- `ru.greemlab.neiro.push.PushApi$RegisterRequest/$RegisterResponse/$UnregisterRequest`

`-keep class ru.greemlab.neiro.data.network.**` уже покрывает api-модели. `-keep class ru.greemlab.neiro.push.**` уже покрывает push.

**Правка:** заменить блок «Доменные модели» в `proguard-rules.pro:20-24`:

```20:24:app/proguard-rules.pro
# --- Доменные модели (сериализуются Gson через рефлексию) ---
-keep class ru.greemlab.neiro.domain.models.** { *; }
-keep class ru.greemlab.neiro.data.UserProfileJson { *; }
-keep class ru.greemlab.neiro.data.StoreSnapshot { *; }
-keep class ru.greemlab.neiro.data.network.** { *; }
```

**На:**

```
# --- Модели, сериализуемые Gson через рефлексию ---
-keep class ru.greemlab.neiro.domain.models.** { *; }
-keep class ru.greemlab.neiro.data.UserProfileJson { *; }
-keep class ru.greemlab.neiro.data.StoreSnapshot { *; }
-keep class ru.greemlab.neiro.data.network.** { *; }
-keep class ru.greemlab.neiro.notifications.InAppNotification { *; }
-keep class ru.greemlab.neiro.notifications.TrackedSession { *; }
-keep class ru.greemlab.neiro.notifications.SessionNotificationPreferences$SnapshotDto { *; }
-keep class ru.greemlab.neiro.notifications.SessionNotificationPreferences$* { *; }
```

> **Проверка:** перед заливкой пробежаться по `notifications/` и `sync/` ripgrep'ом по `data class` и `gson.toJson` / `fromJson` — если найдутся другие модели в JSON, добавить `-keep`. Минимум перечисленных уже хватает по аудиту.

**Также убрать мёртвые правила Room** (Room в проекте не используется):

В `proguard-rules.pro:66-71` блок `# --- WorkManager & Room (R8 full mode fix) ---` — удалить строки про Room:

```kotlin
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}
```

И `WorkDatabase_Impl` — оставить, он используется WorkManager.

**Коммит:** `Добавил keep-правила для Gson-моделей и почистил Room`

---

## 1.3 Backup: убрать DataStore из cloud backup

**Файл:** `app/src/main/res/xml/backup_rules.xml`
**Проблема:** В DataStore лежит профиль (имя, цена занятия, налоги). Это персональные данные одного человека. В D2D-перенос или Google Backup попадать **не должны**.

**Сейчас:**

```1:7:app/src/main/res/xml/backup_rules.xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <!-- DataStore: профиль, календарь, тема -->
    <include domain="file" path="datastore/" />
    <!-- sharedpref не включён — токены YClients (neiro_yclients_*) не попадают в бэкап -->
</full-backup-content>
```

**Заменить на (рекомендуемый вариант):**

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <!-- Бэкап выключен полностью: персональные данные, токены и кеш не покидают устройство. -->
    <exclude domain="root" path="." />
</full-backup-content>
```

И параллельно — `data_extraction_rules.xml`:

```1:9:app/src/main/res/xml/data_extraction_rules.xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <include domain="file" path="datastore/" />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

**Заменить на:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
    </cloud-backup>
    <device-transfer>
        <include domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

**Почему:**
- Cloud backup (Google Drive) — выключен полностью.
- Device-to-device transfer (новый телефон по проводу) — оставляем `datastore/`, чтобы пользователь не потерял настройки при смене устройства.
- Архив (он в DataStore) уезжает только в локальный D2D-перенос, не в cloud.

**Альтернатива (если пользователь хочет cloud backup всего архива):** оставить `backup_rules.xml` как есть, **но добавить `android:allowBackup="false"` либо подписать пользователя на риск**. По умолчанию ставим запрет cloud — это безопаснее.

**Коммит:** `Запретил cloud backup приложения`

---

## 1.4 Network security config: запрет cleartext

**Файл:** `app/src/main/res/xml/network_security_config.xml` *(создать)*

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```

**Почему:** Эвристически блокируем HTTP трафик. Все наши endpoint'ы HTTPS (`api.yclients.com`, `push.neiro.greemlab.ru`). Если в release сборку случайно попадёт http-вызов — он провалится с `CleartextNotPermittedException` вместо незаметной утечки.

**Коммит:** `Добавил network security config с запретом cleartext`

---

## 1.5 AndroidManifest: networkSecurityConfig + onBackInvoked + receiver exported

**Файл:** `app/src/main/AndroidManifest.xml`

### 1.5.1 Привязать network security config + усиление back gesture

**Сейчас (строки 9-18):**

```9:18:app/src/main/AndroidManifest.xml
    <application
        android:name=".NeiroApplication"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Neiro">
```

**Заменить на:**

```xml
    <application
        android:name=".NeiroApplication"
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:enableOnBackInvokedCallback="true"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@style/Theme.Neiro"
        tools:targetApi="tiramisu">
```

**Почему:**
- `enableOnBackInvokedCallback="true"` — корректная работа predictive back на Android 14+.
- `networkSecurityConfig` — привязываем созданный XML.

> Атрибут `tools:targetApi` нужен, потому что `enableOnBackInvokedCallback` появился на API 33. `xmlns:tools` уже подключён в шапке манифеста (строка 3).

### 1.5.2 Receiver — direct boot + replace

Сейчас (строки 32-38):

```32:38:app/src/main/AndroidManifest.xml
        <receiver
            android:name=".notifications.SessionNotificationBootReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

**Оставить как есть.** Дополнительные `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` требуют тестирования на OEM (Xiaomi, Huawei) — **отложено**, см. `OUT_OF_SCOPE.md`.

**Коммит:** `Добавил onBackInvoked и network security config в манифест`

---

## 1.6 `libs.versions.toml`: убрать явный foundation pinning

**Файл:** `gradle/libs.versions.toml`
**Проблема:** `foundation = "1.7.0"` прибит гвоздями, BOM `2024.10.00` контролирует другие compose-артефакты. Расходимость версий → потенциальный classpath conflict при обновлении BOM.

**Сейчас (строки 1-4):**

```1:4:gradle/libs.versions.toml
[versions]
agp = "9.2.1"
coreKtx = "1.13.0"
foundation = "1.7.0"
```

**Заменить на:**

```
[versions]
agp = "9.2.1"
coreKtx = "1.13.0"
```

И строка 38:

```38:38:gradle/libs.versions.toml
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation", version.ref = "foundation" }
```

**На:**

```
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
```

**Почему:** Без `version.ref` Gradle подтянет foundation из Compose BOM — все compose-артефакты будут консистентны.

**Коммит:** `Перенёс foundation на версию из Compose BOM`

---

## 1.7 `app/build.gradle.kts`: release без keystore должен падать

**Файл:** `app/build.gradle.kts`
**Строки:** 123-128
**Проблема:** Если кто-то соберёт release без настроенного `RELEASE_STORE_FILE`, билд подпишется debug-ключом и спокойно соберётся. APK выйдет «как бы релизный», но с debug-подписью. Это пускают на Google Play по ошибке, или, ещё хуже, раздают пользователям.

**Сейчас:**

```123:128:app/build.gradle.kts
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
```

**Заменить на:**

```kotlin
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    throw GradleException(
                        "Release требует RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
                            "RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD в local.properties. " +
                            "Соберите debug или prerelease для локальной отладки."
                    )
                }
```

**Импорт сверху файла (если нет):** автодобавится Kotlin-плагином, или явно прописать `import org.gradle.api.GradleException` в самом верху (для линта).

**Почему:** Fail fast. Локальная разработка использует `debug` или `prerelease`, у обоих своя signing-конфигурация. Если делается release — обязан быть настроен release keystore.

**Коммит:** `Запретил релиз с debug-ключом`

---

## 1.8 ProfileViewModel: `Locale("ru")` deprecated (опционально)

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/profile/ProfileContent.kt`
**Строки:** ~712-713 *(найти grep'ом `Locale("ru")`)*
**Проблема:** `Locale(String)` устарел с Java 17, deprecated warning. Поведение идентично `Locale.forLanguageTag("ru")`.

**Поиск:**
```
rg 'Locale\("ru"\)' app/src/main/java
```

**Замена:** `Locale("ru")` → `Locale.forLanguageTag("ru")`.

**Почему:** убираем deprecated API. Поведение не меняется.

**Коммит:** `Заменил Locale("ru") на forLanguageTag`

---

## Финальная проверка этапа

После всех правок:

1. Открыть IDE → `ReadLints` по правленым файлам:
   - `app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt`
   - `app/build.gradle.kts`
   - `app/src/main/AndroidManifest.xml`
   - Любые правленые `.kt` файлы из 1.8.
2. Убедиться, что в `proguard-rules.pro` нет дубликатов `-keep`.
3. Не запускать `./gradlew` — сборку делает пользователь. Если правка в Gradle вызывает ошибку синтаксиса, она будет видна в линте Android Studio.

## Коммиты этапа (порядок)

1. `Скрыл Authorization и тела в HTTP-логах`
2. `Добавил keep-правила для Gson-моделей и почистил Room`
3. `Запретил cloud backup приложения`
4. `Добавил network security config с запретом cleartext`
5. `Добавил onBackInvoked и network security config в манифест`
6. `Перенёс foundation на версию из Compose BOM`
7. `Запретил релиз с debug-ключом`
8. `Заменил Locale("ru") на forLanguageTag` *(опционально)*
