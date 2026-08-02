# RUNBOOK: автономный прогон «деньги из YClients»

Документ для запуска агента **без человека рядом**. Всё, что агент должен
знать, решено заранее и записано здесь: файлы, сигнатуры, порядок, тесты,
сообщения коммитов, стоп-условия.

Читать вместе с: [FOUNDATION.md](FOUNDATION.md) (модель — источник правды),
[TASKS.md](TASKS.md) (этапы), [API-HOWTO.md](API-HOWTO.md) (форматы ответов),
[HISTORY-2025-2026.md](HISTORY-2025-2026.md) (контрольные числа),
[GAPS.md](GAPS.md) (что за пределами задачи).

**Правило старшинства при расхождении:** RUNBOOK → FOUNDATION → TASKS →
остальное. Если этот документ расходится с кодом — правда в коде, расхождение
пишется в журнал прогона (раздел 12), работа продолжается.

---

## 0. Как запустить

Ветка: **`новая-оплата`** (текущая). Прогон коммитит прямо в неё, по коммиту
на этап.

Промпт для запуска (вставить целиком):

```
Выполни docs/pricing-from-api/RUNBOOK.md целиком, этапы 1–8, автономно.
Я ухожу и на вопросы отвечать не буду: все решения уже приняты в разделе 4
RUNBOOK, действуй по ним. Коммить каждый этап отдельно, как написано
в разделе 11. Веди журнал в docs/pricing-from-api/RUN-LOG.md.
Gradle не запускать ни при каких условиях.
```

Ожидаемый объём: 8 коммитов, ~25 новых/изменённых файлов, 6 новых тестовых
классов. Сборку и прогон тестов делает человек утром (раздел 13).

---

## 1. Что уже решено и не обсуждается

1. **Ставка «за занятие» не восстанавливается расчётом.** Прошлое хранится
   готовым в записи месяца, профиль отвечает за текущий и будущие месяцы.
   Лестница «ставка с даты» — отменена (FOUNDATION 3).
2. **Никаких хардкодов дат и чисел в коде.** Февраль–май 2026 — обычное
   расхождение, разбирается в UI. `1400`, `1500`, `2250`, `6500` в
   production-коде не появляются (в тестах — можно и нужно).
3. **Ручной ввод не перезаписывается никогда.** Приложение только сообщает
   о расхождении.
4. **Верно по умолчанию:** правильные цифры не должны зависеть от того,
   открыл ли человек мастер или окно.
5. **Не трогать:** push, live-опрос, уведомления, архив, `SessionParser` /
   `SessionFormat` (формат строки записи), `ArchiveSyncCompare`, экспорт архива
   (кроме добавления новых ключей, этап 8).

---

## 2. Жёсткие ограничения прогона

### 2.1. Gradle запрещён

Правило проекта: `./gradlew` и любые Gradle-команды агенту запрещены. Значит
**агент не компилирует и не запускает тесты**. Из этого следуют три вещи:

- каждый этап должен быть механически проверяемым чтением (раздел 3);
- после изменения сигнатуры **обязателен обход всех вызовов по grep**, список
  вызовов для этапа 1 уже собран в разделе 5.1;
- тесты пишутся, но их зелёность подтверждает человек утром.

### 2.2. Тестовый стек — только JUnit 4

`app/build.gradle.kts:271` — единственная тестовая зависимость `libs.junit`
(JUnit 4.13.2). В `test/` **нет** Robolectric, mockk, coroutines-test,
и ни в одном существующем тесте нет `runBlocking`.

Следствие, определяющее архитектуру всех этапов:

> **Всё, что должно быть покрыто тестом, обязано быть чистой функцией:
> без `suspend`, без `Context`, без DataStore.**

Схема для каждого нового куска: чистое ядро в отдельном файле (тестируется) +
тонкая обёртка с Android/сетью (не тестируется). Новых тестовых зависимостей
**не добавлять** — это правка `build.gradle.kts`, которую человек не просил.

### 2.3. Ничего не ломать

Существующие тесты после этапа 1 должны проходить с чисто механической
правкой конструкторов. Если для прохождения теста хочется изменить ожидаемое
число — значит рефакторинг не чистый: остановиться, откатить, записать в журнал.

---

## 3. Чем заменяется сборка

Обязательный чек-лист **после каждого этапа**, до коммита:

1. **Обход вызовов.** Для каждой изменённой сигнатуры:
   `grep -rn "имяФункции(" app/src/main app/src/test` — глазами убедиться,
   что поправлены все места, включая `@Preview`-composable внизу файлов
   (`ProfileContent.kt:726`, `SettingsScreen.kt:493`, `DayDetailsDialog.kt:942,972,1001`).
2. **Импорты.** Новый тип из другого пакета → проверить, что импорт добавлен
   в каждый файл, где он появился.
3. **Экспортируемость.** `internal fun` виден тестам того же модуля — это ок.
   `private` — нет: тестируемая функция не должна быть `private`.
4. **`when` без `else`.** Добавили вариант в `sealed`/`enum` — найти все
   исчерпывающие `when` по этому типу.
5. **Nullability.** Все поля новых сетевых моделей — nullable, разбор через
   безопасные хелперы. YClients отдаёт числа строками (`"salary":"12050"`).
6. **Самопроверка на «а компилируется ли»:** перечитать diff этапа целиком
   одним куском (`git diff`), а не по кускам после каждой правки.

Только после этого — коммит.

---

## 4. Ответы на все вопросы, которые агент задал бы

Человека рядом нет. Ниже — готовые решения; отступать от них нельзя,
сомнения — в журнал.

| Вопрос | Решение |
|---|---|
| Как назвать носитель цен | `EarningsContext`, файл `domain/models/EarningsContext.kt` |
| Где хранить историю месяцев | Отдельный `SalaryLedgerStore`, **не** в `UserProfile` (FOUNDATION 8.4) |
| Ключ записи месяца | `"$staffId:$year-$month"` (`staffId` из `TokenStorage.staffId`, `null` → `0`) |
| Что делать, если `staffId` неизвестен | Писать под ключом `0`, историю не тянуть; при появлении `staffId` — заново |
| Формат хранения | JSON через Gson, как в `CalendarDataStore`, с `corruptionHandler` |
| Признак «начисление закрыто» из API | **Не использовать.** Эвристика «≥ 7 дней после конца месяца» (FOUNDATION 4). Проверку эндпоинта не делать — сети у прогона нет |
| Что делать при 403 | Тихий фолбэк на цену профиля, пользователю ошибку **не** показывать |
| Что делать при 422 | Обрезать период (`date_to` = сегодня, длина ≤ 1 года) и повторить один раз |
| Что делать при 401 | Ничего нового: общий logout уже делает `YClientsRepository.handleUnauthorized` |
| Удалять ли старую эвристику цен | **Нет.** Она остаётся последним фолбэком (TASKS 6, FOUNDATION 6.3) |
| Менять ли формат строки записи | **Нет, никогда.** Только sidecar (этап 8) |
| Добавлять ли зависимости в Gradle | Нет |
| Менять ли UI-тексты вне денежной части | Нет |
| Формулировка дисклеймера | «Цена рассчитана автоматически — проверьте» |
| Формулировка расхождения | «В YClients другая сумма» + кнопка «Разобрать» |
| Локаль/формат денег | Существующий `formatRubles` из `ui/util/Formatters.kt` |
| Если этап оказался больше, чем описано | Сделать описанное, лишнее записать в журнал как «не сделано и почему» |
| Если этап невозможно сделать без правки чужой подсистемы | Не делать, записать в журнал, перейти к следующему этапу |

---

## 5. Карта кода (проверено 28.07.2026)

| Что | Где |
|---|---|
| Профиль (4 цены) | `domain/models/UserProfile.kt:26-29` |
| Статистика месяца | `ui/calendar/CalendarStatsCalculator.kt` — `computeMonthStats:83`, `rememberCalendarMonthStats:58` |
| Статистика года | `ui/calendar/ProfileYearStats.kt` — `computeProfileYearStats:75`, `rememberProfileYearStats:40`, `availableStatsYears:66` |
| Сводка дня | `ui/calendar/DaySummaryStats.kt` — `computeDayStats:20` |
| Итоги за всё время | `ui/calendar/ProfileTotalsCalculator.kt` — `computeProfileTotals:40` |
| Экран календаря | `ui/screens/CalendarScreen.kt:190,333,483,614,634,686,777,902` |
| Диалоги календаря | `ui/components/CalendarDialogs.kt:134,216` |
| Диалог дня | `ui/components/DayDetailsDialog.kt:848,889-908` |
| Профиль (экран) | `ui/profile/ProfileContent.kt:84,91,180` |
| Секция статистики | `ui/profile/ProfileYearStatsSection.kt` — клики по месяцам `542-554`, `selectedMonthIndex:345`, `SelectedMonthSummary` вызов `585-593`, объявление `597` |
| Настройки цен | `ui/profile/SettingsScreen.kt:113-123` |
| Автоподстановка цен (сломана) | `sync/YClientsCalendarSync.kt:232-266` |
| Диагностика по названию | `sync/YClientsCalendarSync.kt:722-724` (в `createEntryFromRecord:718`) |
| Модель услуги | `data/network/YClientsModels.kt:85-92` (`ServiceData`) |
| API | `data/network/YClientsApi.kt`, база `https://api.yclients.com/api/v1/` |
| Репозиторий API | `data/network/YClientsRepository.kt` (401 → `handleUnauthorized:248`) |
| `staffId`, `companyId` | `data/network/TokenStorage.kt:63,82`; наружу — `YClientsRepository:47-48` |
| Хранилище | `data/CalendarDataStore.kt` (экспорт `exportAllData:298`, импорт `restoreAllData:311`) |
| Образец стора | `notifications/ArchiveNotificationStore.kt` (`EXPORT_KEY:98`) |
| Настройки показа денег | `ui/settings/ProfitDisplayPreferences.kt` |
| Ключ слота | `notifications/SessionSlotKey.kt:20` — `build(clientName, date, startTime, kind)` |
| Точка входа синка | `ui/sync/SyncViewModel.kt` — `syncAfterLogin:133`, `syncDailyEdgeMonths:142`, `syncAllThroughCurrentMonth:153` |

### 5.1. Полный список вызовов четырёх цен (для этапа 1)

Production:

```
ui/calendar/DaySummaryStats.kt:22-24,44-46,57-58,65,82
ui/calendar/ProfileTotalsCalculator.kt:42-46,64,77,84,92,107
ui/calendar/ProfileYearStats.kt:43-46,50-53,58-61,78-81,98-101,112
ui/calendar/CalendarStatsCalculator.kt:50-51,61-64,68-71,76-79,86-89,118,133,141,154,177,191
ui/screens/CalendarScreen.kt:193-196,333-335,483,614-616,634-636,641-643,686,777,798,802,812,902,916
ui/components/CalendarDialogs.kt:134,216,219
ui/components/DayDetailsDialog.kt:848,889-890,906,908,942,972,1001
ui/profile/ProfileContent.kt:94-97,726-727
ui/profile/SettingsScreen.kt:113-123,493-494
ui/profile/ProfileViewModel.kt:81,86,94,96,98
ui/sync/SyncViewModel.kt:104-106,264-265
sync/YClientsCalendarSync.kt:239-264,605,727,764
push/PushEventCalendarApplier.kt:33-160
data/UserProfileJson.kt:47
```

Тесты:

```
test/.../ui/calendar/CalendarStatsCalculatorTest.kt
test/.../ui/calendar/ProfileYearStatsTest.kt
test/.../ui/calendar/DaySummaryStatsTest.kt
test/.../ui/calendar/ProfileTotalsCalculatorTest.kt
test/.../ui/settings/ProfitDisplaySettingsTest.kt
test/.../data/UserProfileJsonTest.kt
```

`PushEventCalendarApplier` и `ProfileViewModel` берут поля прямо из профиля —
их **не трогать**: они не вызывают калькуляторы.

---

## 6. Расхождения документов с кодом (уже проверены, вопросов не задавать)

| В документах | В коде | Как быть |
|---|---|---|
| `computeDaySummaryStats` (TASKS 1, 3) | `computeDayStats` в `ui/calendar/DaySummaryStats.kt:20` | Работать с `computeDayStats` |
| `SalaryRates` — «модель ставок с историей» (TODO.md) | нет | Отменено FOUNDATION 3, вместо неё `MonthEntry` |
| «поправки „YClients врёт“» (TODO.md) | нет | Отменено FOUNDATION 9 |
| `YClientsModels.kt:86-93` про `ServiceData` | реально `85-92` | Мелочь, править не надо |
| `YClientsCalendarSync.kt:239-246` | блок цен реально `232-266` | То же |
| `YClientsCalendarSync.kt:721-723` | `722-724` | То же |
| `CalendarDataStore.exportAllData()` строка 298 | 298 — верно | — |

TODO.md после прогона не переписывать: это задача человека.

---

## 7. Этапы

Каждый этап: **цель → файлы → код → тесты → проверка → коммит.**
После коммита — строка в журнале. Дальше по порядку, без пропусков.

---

### Этап 1. `EarningsContext` вместо четырёх `Double`

**Цель:** один носитель цен вместо четырёх параметров. Цифры не меняются.

**Новый файл** `domain/models/EarningsContext.kt`:

```kotlin
package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable

/**
 * Цены, по которым считаются деньги за отрезок времени.
 *
 * Один носитель вместо четырёх параметров: цена месяца берётся из истории
 * (см. docs/pricing-from-api/FOUNDATION.md, раздел 3.2), а профиль — лишь
 * один из источников. Добавление пятой цены не должно задевать десяток
 * сигнатур.
 */
@Immutable
data class EarningsContext(
    val pricePerSession: Double = 0.0,
    val pricePerDiagnostics: Double = 0.0,
    val pricePerIntensiveChild: Double = 0.0,
    val monthlyTaxAmount: Double = 0.0,
) {
    companion object {
        val Empty = EarningsContext()
    }
}

/** Цены профиля — правда про текущий и будущие месяцы (FOUNDATION 1.3). */
fun UserProfile.earningsContext(): EarningsContext = EarningsContext(
    pricePerSession = pricePerSession,
    pricePerDiagnostics = pricePerDiagnostics,
    pricePerIntensiveChild = pricePerIntensiveChild,
    monthlyTaxAmount = monthlyTaxAmount,
)
```

**Новые сигнатуры** (порядок параметров сохранить, `rates` — последним
обязательным, чтобы диффы читались):

```kotlin
internal fun computeMonthStats(currentMonth: YearMonth, dayData: Map<LocalDate, List<String>>, rates: EarningsContext): CalendarMonthStats

@Composable fun rememberCalendarMonthStats(currentMonth: YearMonth, dayData: Map<LocalDate, List<String>>, rates: EarningsContext): CalendarMonthStats

internal fun computeProfileYearStats(year: Int, dayData: Map<LocalDate, List<String>>, rates: EarningsContext): ProfileYearStats

@Composable fun rememberProfileYearStats(year: Int, dayData: Map<LocalDate, List<String>>, rates: EarningsContext): ProfileYearStats

internal fun computeDayStats(sessions: List<String>, rates: EarningsContext): DaySummaryStats

internal fun computeProfileTotals(dayData: Map<LocalDate, List<String>>, today: LocalDate, rates: EarningsContext): ProfileTotals
```

Внутри тел — механическая замена `pricePerSession` → `rates.pricePerSession`
и т. д. `monthlyNetProfit(gross, tax)` оставить как есть: это чистая
арифметика, носитель ей не нужен.

**Что сохранить дословно** (иначе поедут числа):

- фолбэк диагностики `if (rates.pricePerDiagnostics > 0.0) rates.pricePerDiagnostics else session.amount` — во всех трёх калькуляторах;
- `+ completedIntensivesCount` в `computeProfileYearStats:105`;
- `remember(...)`-ключи: вместо четырёх цен — один `rates` (это data class, `equals` корректный).

**Composable-параметры.** В `CalendarScreen.kt:614-616`, `CalendarDialogs.kt:134`
и соседних приватных composable четыре параметра заменяются на
`rates: EarningsContext = EarningsContext.Empty`. Места, где нужна только
цена занятия для показа (`CalendarScreen.kt:902,916`, `CalendarDialogs.kt:216-219`),
читают `rates.pricePerSession`. `DayDetailsDialog` получает `userProfile`
целиком — там достаточно заменить локальные `val` на `userProfile.earningsContext()`.

**Тесты:** новых не писать. Существующие четыре класса поправить механически:
`pricePerSession = 1000.0, monthlyTaxAmount = 200.0` → `EarningsContext(pricePerSession = 1000.0, monthlyTaxAmount = 200.0)`.
**Ни одно ожидаемое число не меняется.** Если поменялось — откат.

**Коммит:** `Ввёл EarningsContext вместо четырёх цен`

---

### Этап 2. Запись месяца и хранилище

**Цель:** модель `MonthEntry` и `SalaryLedgerStore` (FOUNDATION 3.1, 8.4).
Читателей пока нет — только модель, сериализация, стор.

**Новый файл** `domain/models/MonthEntry.kt`:

```kotlin
package ru.greemlab.neiro.domain.models

import androidx.compose.runtime.Immutable
import java.time.YearMonth

/** Кто выставил цену: приложение из данных API или человек руками. */
enum class PriceOrigin { AUTO, MANUAL }

/**
 * Замороженная правда об одном месяце (FOUNDATION 3.1).
 *
 * Сумма месяца не хранится: она считается как sessions × pricePerSession
 * плюс диагностики и интенсивы. Правится только цена.
 */
@Immutable
data class MonthEntry(
    val staffId: Long = 0L,
    val year: Int,
    val month: Int,
    val sessions: Int = 0,
    val pricePerSession: Double = 0.0,
    val priceDiagnostics: Double = 0.0,
    val priceIntensiveChild: Double = 0.0,
    val tax: Double = 0.0,
    /** Что говорит YClients за месяц. null — факта нет (офлайн, 403, не тянули). */
    val factGross: Double? = null,
    /** Занятий по данным API (services_count). Может расходиться с [sessions]. */
    val factSessions: Int? = null,
    val origin: PriceOrigin = PriceOrigin.AUTO,
    val frozen: Boolean = false,
    /** Расхождение разобрано человеком — больше не спрашивать. */
    val resolved: Boolean = true,
    val note: String = "",
) {
    val yearMonth: YearMonth get() = YearMonth.of(year, month)

    fun rates(): EarningsContext = EarningsContext(
        pricePerSession = pricePerSession,
        pricePerDiagnostics = priceDiagnostics,
        pricePerIntensiveChild = priceIntensiveChild,
        monthlyTaxAmount = tax,
    )
}
```

`resolved` по умолчанию `true`: месяц без расхождения не должен ничего просить.

**Новый файл** `data/SalaryLedger.kt` — чистый снимок истории, без Android:

```kotlin
package ru.greemlab.neiro.data

import ru.greemlab.neiro.domain.models.MonthEntry
import java.time.LocalDate
import java.time.YearMonth

/**
 * История денег: записи месяцев + факт по дням (FOUNDATION 3.4).
 * Чистая структура — тестируется без Android.
 */
data class SalaryLedger(
    val months: Map<String, MonthEntry> = emptyMap(),
    /** Ключ — "staffId|ISO-дата", значение — salary за день из API. */
    val dailyFact: Map<String, Double> = emptyMap(),
) {
    fun month(staffId: Long, ym: YearMonth): MonthEntry? = months[monthKey(staffId, ym)]

    fun dayFact(staffId: Long, date: LocalDate): Double? = dailyFact[dayKey(staffId, date)]

    fun withMonth(entry: MonthEntry): SalaryLedger =
        copy(months = months + (monthKey(entry.staffId, entry.yearMonth) to entry))

    fun withDayFacts(staffId: Long, facts: Map<LocalDate, Double>): SalaryLedger =
        copy(dailyFact = dailyFact + facts.mapKeys { dayKey(staffId, it.key) })

    /** Годы, за которые есть история — для переключателя лет (FOUNDATION 3.3). */
    fun years(staffId: Long): Set<Int> =
        months.values.filter { it.staffId == staffId }.map { it.year }.toSet()

    companion object {
        val Empty = SalaryLedger()

        fun monthKey(staffId: Long, ym: YearMonth): String =
            "$staffId:${ym.year}-${"%02d".format(ym.monthValue)}"

        fun dayKey(staffId: Long, date: LocalDate): String = "$staffId|$date"
    }
}
```

**Новый файл** `data/SalaryLedgerJson.kt` — сериализация, тоже чистая:

```kotlin
object SalaryLedgerJson {
    private val gson = Gson()
    private val type = object : TypeToken<SalaryLedger>() {}.type

    fun toJson(ledger: SalaryLedger): String = gson.toJson(ledger)

    /** Не бросает исключений: битые данные = пустая история, как в UserProfileJson. */
    fun fromJson(json: String?): SalaryLedger {
        if (json.isNullOrBlank() || json == "{}") return SalaryLedger.Empty
        return runCatching { gson.fromJson<SalaryLedger>(json, type) }
            .getOrNull() ?: SalaryLedger.Empty
    }
}
```

**Новый файл** `data/SalaryLedgerStore.kt` — единственное место с Android:
синглтон по образцу `ArchiveNotificationStore` (`get(context)`), внутри —
`preferencesDataStore(name = "salary_ledger", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })`,
`Mutex` на запись, `StateFlow<SalaryLedger>`, методы:

```kotlin
val ledger: StateFlow<SalaryLedger>
suspend fun warmUp()
suspend fun update(transform: (SalaryLedger) -> SalaryLedger)   // read-modify-write под mutex
fun exportJson(): String
suspend fun importJson(json: String): Boolean
companion object { const val EXPORT_KEY = "salary_ledger" }
```

`update` — единственный способ записи (как `CalendarRepository.updateDayData`),
иначе параллельные синк и правка из UI потеряют друг друга.

**Тесты** — новый `test/.../data/SalaryLedgerJsonTest.kt` (чистый JVM):

- пустая строка / `"{}"` / мусор `"не json"` → `SalaryLedger.Empty`, без исключения;
- round-trip: запись месяца с `origin = MANUAL`, `frozen`, `resolved = false`, `note` переживает `toJson → fromJson`;
- `withMonth` перезаписывает месяц с тем же ключом и не задевает соседний;
- `monthKey` для января даёт `"...:2026-01"` (двузначный месяц — иначе сортировка и сравнение ключей поедут);
- `years()` отдаёт годы только своего `staffId`.

**Коммит:** `Добавил запись месяца и хранилище истории ЗП`

---

### Этап 3. Расчёт берёт цену из истории

**Цель:** приоритет FOUNDATION 3.2 в калькуляторах. Сеть ещё не появилась —
история пока пустая, поэтому поведение не меняется ни на рубль
(пустой ledger → цена профиля везде).

**Новый файл** `ui/calendar/MonthRatesResolver.kt` — чистое ядро:

```kotlin
enum class PriceSource { MANUAL, FACT, PROFILE }

data class ResolvedRates(val rates: EarningsContext, val source: PriceSource)

/**
 * Цена месяца по приоритету FOUNDATION 3.2:
 *   MANUAL → его цена; есть факт → factGross ÷ занятия; иначе → профиль.
 * Текущий и будущие месяцы всегда считаются по профилю.
 */
fun resolveMonthRates(
    month: YearMonth,
    entry: MonthEntry?,
    profile: EarningsContext,
    today: LocalDate,
    diagnosticsCount: Int,
    diagnosticsSum: Double,
    manualIntensiveSum: Double,
): ResolvedRates
```

Правила внутри, по порядку:

1. `month >= YearMonth.from(today)` → `profile`, `PriceSource.PROFILE`.
2. `entry?.origin == MANUAL` → `entry.rates()`, `PriceSource.MANUAL`.
3. `entry?.factGross != null` → считаем:

```
база       = factGross − diagnosticsSum − manualIntensiveSum   // интенсивы, заведённые руками,
                                                               // в факте YClients отсутствуют (GAPS 7)
делитель   = (entry.factSessions ?: entry.sessions) − diagnosticsCount
цена       = if (делитель > 0 && база > 0) база / делитель else профильная цена
```

   Цены диагностики и интенсива берутся из `entry`, если > 0, иначе из профиля.
   Налог — всегда `entry.tax` если > 0, иначе профильный.
4. иначе → `profile`, `PriceSource.PROFILE`.

Деление на `factSessions` (число из API), а не на локальное — иначе цена
завысится при расхождении (FOUNDATION 3.2).

**Правки калькуляторов:**

- `computeMonthStats` — сигнатура не меняется, но вызывающая сторона обязана
  передать уже разрешённые `rates`. **Критично:** `grossEarned` и
  `statsByStudent` используют одну и ту же `rates.pricePerSession` — так уже
  сделано (`val pay = rates.pricePerSession` один раз на итерацию), не разносить.
- `computeProfileYearStats(year, dayData, profileRates, ledger, staffId, today)` —
  внутри цикла по 12 месяцам для каждого месяца берётся
  `resolveMonthRates(...)`. Диагностики месяца считаются из локальных записей
  (`Session.Diagnostics.amount` в строке `__DIAGNOSTICS__:2250|…`), а не из
  профиля — FOUNDATION 3.2 прямо это требует.
- Месяцы, которых нет в календаре (FOUNDATION 3.3): если `dayData` за месяц
  пуст, а `entry?.factGross != null` — в `monthlyNet` идёт
  `monthlyNetProfit(entry.factGross, tax)`, в `monthlyCompleted` — `entry.factSessions ?: 0`.
- `availableStatsYears(dayData, ledgerYears: Set<Int>, currentYear)` — годы
  из истории добавляются к годам календаря.
- `computeDayStats(sessions, rates, dayFact: Double? = null)`: если `dayFact != null`,
  поле `earned` берётся из него (FOUNDATION 3.4), остальные поля считаются как
  сейчас. Ручные интенсивы дня прибавляются к факту, а не заменяются им.

**Тесты** — новый `test/.../ui/calendar/MonthRatesResolverTest.kt`:

| Случай | Ожидание |
|---|---|
| Текущий месяц, есть факт и MANUAL | `PROFILE` — профиль главнее для «сейчас» |
| Прошлый месяц, `origin = MANUAL` | цена из записи, `MANUAL` |
| Прошлый месяц, факт 112 000, 80 занятий, диагностик нет | 1400, `FACT` |
| Факт 12 050, 8 занятий, 1 диагностика на 2250 | **1400**, а не 1506 — контрольный случай FOUNDATION 3.2 |
| Факт есть, занятий 0 | цена профиля, без деления на ноль |
| Факт 0.0 (не `null`) | `FACT`, цена 0 — «ноль» и «нет факта» не путать |
| `factSessions = 80`, локальных 78 | делим на 80 |
| Есть ручной интенсив на 11 200 | вычитается из базы до деления |

Плюс в `ProfileYearStatsTest` — случай «в календаре пусто, в истории месяц есть»:
год показывает цифры из истории.

**Коммит:** `Перевёл расчёт месяца на цену из истории`

---

### Этап 4. API-слой зарплат

**Цель:** модели и методы, без вызовов из UI.

**Новый файл** `data/network/SalaryModels.kt`. Все поля nullable, числа
приходят строками (`"salary":"12050"`), поэтому единый хелпер:

```kotlin
internal fun String?.toMoneyOrNull(): Double? =
    this?.trim()?.replace(" ", "")?.replace(",", ".")?.toDoubleOrNull()
```

Модели (имена полей — из API-HOWTO 5.1 и 5.2):

```kotlin
data class SalaryDailyResponse(val success: Boolean?, val data: List<SalaryDailyItem>?, val meta: SalaryMeta?)

data class SalaryDailyItem(
    val date: String?,
    @SerializedName("period_calculation") val calculation: SalaryPeriodCalculation?,
)

data class SalaryPeriodCalculation(
    @SerializedName("working_days_count") val workingDaysCount: Double?,
    @SerializedName("services_count") val servicesCount: Int?,
    @SerializedName("group_services_count") val groupServicesCount: Int?,
    @SerializedName("services_sum") val servicesSum: String?,
    @SerializedName("total_sum") val totalSum: String?,
    val salary: String?,
)

data class SalaryMeta(val message: String?)

data class SalaryCalculationListResponse(val success: Boolean?, val data: List<SalaryCalculationSummary>?, val meta: SalaryMeta?)

data class SalaryCalculationSummary(
    val id: Long?,
    @SerializedName("date_from") val dateFrom: String?,
    @SerializedName("date_to") val dateTo: String?,
    val sum: String?,
)

data class SalaryCalculationDetailsResponse(val success: Boolean?, val data: List<SalaryCalculationItem>?, val meta: SalaryMeta?)

data class SalaryCalculationItem(
    val date: String?,
    val time: String?,
    @SerializedName("item_type_slug") val itemTypeSlug: String?,
    @SerializedName("record_id") val recordId: Long?,
    @SerializedName("client_id") val clientId: Long?,
    val cost: String?,
    @SerializedName("salary_sum") val salarySum: String?,
    val targets: List<SalaryTarget>?,
)

data class SalaryTarget(
    @SerializedName("target_type_slug") val targetTypeSlug: String?,
    @SerializedName("target_id") val targetId: Long?,
    val title: String?,
    val cost: String?,
    @SerializedName("salary_sum") val salarySum: String?,
    @SerializedName("salary_calculation") val salaryCalculation: SalaryCalculationRule?,
)

data class SalaryCalculationRule(
    @SerializedName("type_slug") val typeSlug: String?,   // "fix" | "percent" | что-то ещё
    val value: Double?,
)
```

> **Риск, записать в журнал:** поля `SalaryCalculationSummary` (список
> начислений) в документах описаны только словами «месяц → id, сумма».
> Настоящий ответ не видел никто из нас. Поэтому: всё nullable, ни на что
> кроме `id` не опираться, при пустом разборе — вести себя как при 403.

**Методы в `YClientsApi.kt`** (пути относительно базы `.../api/v1/`):

```kotlin
@GET("company/{company_id}/salary/period/staff/daily/{staff_id}/")
suspend fun getSalaryDaily(
    @Path("company_id") companyId: Int,
    @Path("staff_id") staffId: Int,
    @Query("date_from") dateFrom: String,
    @Query("date_to") dateTo: String,
): Response<SalaryDailyResponse>

@GET("company/{company_id}/salary/payroll/staff/{staff_id}/calculation/")
suspend fun getSalaryCalculations(...): Response<SalaryCalculationListResponse>

@GET("company/{company_id}/salary/payroll/staff/{staff_id}/calculation/{calculation_id}")
suspend fun getSalaryCalculationDetails(...): Response<SalaryCalculationDetailsResponse>
```

**Чистое ядро для тестов** — `data/network/SalaryPeriodSplitter.kt`:

```kotlin
/**
 * Режет период под ограничения YClients (API-HOWTO 6):
 * будущее не считается (422), поиск начислений — не больше года (422).
 */
fun splitSalaryPeriods(from: LocalDate, to: LocalDate, today: LocalDate): List<ClosedRange<LocalDate>>
```

Правила: `to` обрезается до `today`; если после обрезки `from > to` — пустой
список; период режется на куски ≤ 365 дней, границы кусков — по календарным
годам, чтобы запросы были стабильными между прогонами.

**Методы в `YClientsRepository`** — возвращают `ApiResult`, как остальные:

```kotlin
suspend fun fetchSalaryDaily(from: LocalDate, to: LocalDate): ApiResult<Map<LocalDate, DayFact>>
suspend fun fetchLatestSalaryRates(): ApiResult<SalaryRatesFromApi>
```

Обработка кодов — ровно как решено в разделе 4: 401 → существующий
`handleUnauthorized(code)` и всё; 403 → `ApiResult.Error(code = 403)` без
пользовательского текста; 422 → один повтор с обрезанным периодом, дальше
`ApiResult.Error`. Незнакомый `item_type_slug` — позиция пропускается,
разбор продолжается.

**Тесты** — `test/.../data/network/SalaryPeriodSplitterTest.kt`:
период в будущем → пусто; три года → три куска; период внутри одного года →
один кусок; `to` в будущем → обрезан до `today`; `from == to` → один день.

Плюс `test/.../data/network/SalaryParsingTest.kt` на чистые хелперы:
`"12050"` → 12050.0; `null` → `null`; `"18 000"` → 18000.0; `"не число"` → `null`.

**Коммит:** `Добавил API-слой зарплат YClients`

---

### Этап 5. Заполнение истории и заморозка

**Цель:** история заполняется сама, месяц замораживается со сверкой
(FOUNDATION 4, 7).

**Чистое ядро** `data/SalaryLedgerRules.kt`:

```kotlin
/** Месяц закрыт: не текущий, факт получен, прошло ≥ [graceDays] с конца месяца. */
fun shouldFreeze(month: YearMonth, hasFact: Boolean, today: LocalDate, graceDays: Long = 7L): Boolean

/**
 * Слияние факта в запись месяца.
 * Ручное (origin = MANUAL), tax и note не трогаются никогда (FOUNDATION 7).
 */
fun mergeFact(
    existing: MonthEntry?,
    fact: MonthFact,
    profile: EarningsContext,
    staffId: Long,
    today: LocalDate,
): MonthEntry

/** Расхождение факта и цены приложения — для отметки в статистике (FOUNDATION 4). */
fun discrepancy(entry: MonthEntry, appRates: EarningsContext): Discrepancy?
```

`mergeFact` при заморозке: цена — **по цене приложения**, `frozen = true`,
`resolved = false`, если расхождение есть; при совпадении — `resolved = true`
и молча. `note` дописывается строкой вида
`«факт YClients 120 000, приложение 112 000»` **всегда**, даже если показ
расхождений выключен тумблером (FOUNDATION 5, «иначе через полгода не
останется следов»).

**Оркестровка** `sync/SalaryHistorySync.kt` (suspend, не тестируется):

| Когда | Что тянем |
|---|---|
| Первый логин (`SyncViewModel.syncAfterLogin`, ветка полного синка) | вся история по годам |
| Каждый синк | текущий месяц |
| Каждый синк | прошлый месяц, пока он не `frozen` |
| Кнопка «перетянуть историю» | всё заново: `factGross` перезаписывается, ручное не трогается |

Вызов — **после** успешного календарного синка, из `SyncViewModel`, рядом с
`syncPreferences.markInitialFullSyncComplete()`. `LiveApiCoordinator`,
`PushEventsSyncCoordinator`, воркеры уведомлений — **не трогать вообще**.

При `ApiResult.Error` любого рода история просто не обновляется: экран
продолжает считать по цене профиля, ошибка не показывается.

**Тесты** — `test/.../data/SalaryLedgerRulesTest.kt`:

- месяц закончился 5 дней назад → не морозим; 8 дней назад → морозим;
- текущий месяц с фактом → не морозим никогда;
- факта нет → не морозим, сколько бы дней ни прошло;
- расхождение → `frozen = true`, `resolved = false`, цена осталась приложения;
- совпадение → `frozen = true`, `resolved = true`;
- `mergeFact` поверх `origin = MANUAL` → цена и `note` не изменились, `factGross` обновился;
- `factGross = 0.0` → это факт, а не «нет факта».

**Коммит:** `Добавил заполнение истории ЗП и заморозку месяца`

---

### Этап 6. Профиль: АВТО/РУЧНОЕ и правильный источник цен

**Цель:** починить автоподстановку (FOUNDATION 6), не выбрасывая её.

**`UserProfile`** — три новых поля с дефолтом `AUTO`:

```kotlin
val sessionPriceOrigin: PriceOrigin = PriceOrigin.AUTO,
val diagnosticsPriceOrigin: PriceOrigin = PriceOrigin.AUTO,
val intensivePriceOrigin: PriceOrigin = PriceOrigin.AUTO,
```

Миграции данных не требуется: Gson для отсутствующего поля enum вернёт `null`,
поэтому в `UserProfileJson.normalizeLegacy()` добавить подстановку `AUTO`
вместо `null` (иначе получим `null` в non-null поле — типичная дыра Gson).
Это обязательный пункт, не пропускать.

**Где ставится MANUAL:** `ProfileViewModel.updateSessionPrice/updateDiagnosticsPrice/updateIntensiveChildPrice`
(строки 81–96) — вместе с ценой пишут `origin = MANUAL`. Налог признака не
имеет: он и так только ручной.

**`ServiceData`** (`YClientsModels.kt:85-92`) — добавить:

```kotlin
@SerializedName("first_cost") val firstCost: Double?,
@SerializedName("cost_per_unit") val costPerUnit: Double?,
```

**Источник цен в `YClientsCalendarSync.kt:232-266`.** Порядок, сверху вниз:

1. ставки из детализации последнего начисления (`salary_sum` позиции,
   диагностика — по подстроке в `targets[].title`);
2. если детализации нет — `first_cost` / `cost_per_unit` записи как цена
   клиента **только для показа**, ставку из неё не выводить;
3. если и этого нет — существующая эвристика по `cost` **остаётся как есть**.

Условие записи меняется с `== 0.0` на `origin == AUTO` — теперь приложение
может обновлять свою же подставленную цену, но никогда — ручную.

Диагностика в `createEntryFromRecord:718-724` ловится по подстроке
«диагностика» — **не менять** (FOUNDATION 3.2 прямо просит оставить).

**Тесты** — `test/.../sync/SalaryRatesPickerTest.kt` на вынесенную чистую
функцию выбора цен из позиций начисления:

- позиции «Нейрокоррекция 1400» ×20 и «диагностика 2250» ×1 → занятие 1400, диагностика 2250;
- незнакомый `item_type_slug: "penalty"` → пропущен, не уронил разбор;
- пустой список → цены не меняются;
- `origin = MANUAL` → входное значение возвращается нетронутым.

Плюс в `UserProfileJsonTest` — старый JSON без полей `*Origin` читается,
поля становятся `AUTO`.

**Коммит:** `Починил источник цен профиля и признак АВТО/РУЧНОЕ`

---

### Этап 7. UI статистики

**Цель:** показать происхождение цены, расхождение и дать правку
(FOUNDATION 4–5). Самый рискованный этап: `ProfileYearStatsSection.kt` — 677
строк с Canvas-графиком. Правило: **графику не трогать, добавлять рядом.**

**`ProfileYearStats`** дополняется помесячными метаданными:

```kotlin
val monthOrigin: List<PriceOrigin>,      // 12
val monthResolved: List<Boolean>,        // 12
val monthFrozen: List<Boolean>,          // 12
val monthPrice: List<Double>,            // 12, цена занятия
val monthFactGross: List<Double?>,       // 12, для строки «в YClients …»
```

`ProfileYearStats.empty()` заполняет их дефолтами (`AUTO`, `true`, `false`, `0.0`, `null`).

**Что добавляется в `ProfileYearStatsSection`:**

1. **Маркер расхождения на столбце** — маленькая точка над столбцами, где
   `!monthResolved[i]`. Рисуется в том же `Canvas`, отдельным `drawCircle`
   после существующей отрисовки, чтобы не менять расчёт геометрии.
2. **Бейдж в заголовке секции** — если хоть один месяц года `!resolved`.
   Человек должен видеть, не разворачивая секцию.
3. **Раскрытие сводки.** `SelectedMonthSummary` (597) получает `onClick` и
   ниже разворачивает блок: цена занятия, откуда она
   («из YClients» / «по вашей цене» / «вы поправили»), диагностики,
   интенсивы, налог, «закрыт», кнопка «Разморозить», при расхождении —
   «Разобрать».
4. **Правка цены** — маленький диалог с одним полем: цена за занятие.
   Сохранение → колбэк `onMonthPriceEdited(YearMonth, Double)`.
5. **Разбор расхождения** — диалог по макету FOUNDATION 4: три варианта
   (цена приложения / цена YClients / другая), кнопка «В историю» →
   колбэк `onMonthDiscrepancyResolved(YearMonth, Double)`.
6. **Дисклеймер** «Цена рассчитана автоматически — проверьте» — на всех
   месяцах с `origin = AUTO`, спокойным тоном, в раскрытом блоке.

**Секция остаётся презентацией.** Новые параметры — только колбэки:

```kotlin
onMonthPriceEdited: (YearMonth, Double) -> Unit = { _, _ -> },
onMonthDiscrepancyResolved: (YearMonth, Double) -> Unit = { _, _ -> },
onMonthUnfrozen: (YearMonth) -> Unit = {},
```

Хранилище в секцию не тащить. Реализация колбэков — в `ProfileContent`
(вызов на 180) через ViewModel, который пишет в `SalaryLedgerStore`:
цена → `pricePerSession`, `origin = MANUAL`, `resolved = true`.

**Настройки:** `ProfitDisplaySettings` += `showDiscrepancy: Boolean = true`,
ключ `"show_discrepancy"` в `ProfitDisplayPreferences`, тумблер в
`ProfitDisplaySettingsScreen`. Тумблер прячет **показ**, сверку и запись
в `note` не выключает.

Все новые денежные строки подчиняются существующим флагам
`ProfitDisplaySettings` — деньги не показываются там, где человек их выключил.

**Тесты:** Compose-тестов в проекте нет и заводить их не надо (это
`androidTest`, отдельный стек). Проверяемое — в `ProfileYearStatsTest`:
метаданные месяцев заполняются из истории (origin, resolved, frozen, цена).

**Коммит:** `Добавил разбор месяца и правку цены в статистике`

---

### Этап 8. Sidecar-метаданные и бэкап

**Цель:** начать копить `record_id / service_id / activity_id / first_cost`
и не потерять историю при переустановке (FOUNDATION 8.3, 8.4).

**`RecordData`** (`YClientsModels.kt:44-66`) += `@SerializedName("activity_id") val activityId: Long?`
— поля сейчас нет вовсе (GAPS 2).

**Новый файл** `data/SessionMetaStore.kt`: `SharedPreferences` + Gson,
по образцу `ArchiveNotificationStore`. Ключ —
`SessionSlotKey.build(clientName, date, startTime, kind)`, значение —

```kotlin
data class SessionMeta(
    val recordId: Long? = null,
    val serviceId: Long? = null,
    val activityId: Long? = null,
    val firstCost: Double? = null,
)
```

Заполняется в `YClientsCalendarSync` там же, где строится запись дня
(`createEntryFromRecord` / `mergeRecordsToCalendar`). **Пока никем не читается** —
это осознанно: смысл в том, чтобы через полгода была история.

Формат строки записи (`SessionFormat`, `SessionParser`) — **не трогать
ни при каких обстоятельствах**: `parseStudent` делает `split("|", limit = 5)`,
и `comment` глотает весь хвост.

**Бэкап** — `CalendarDataStore.exportAllData()` (298):

```kotlin
SalaryLedgerStore.EXPORT_KEY to SalaryLedgerStore.get(appContext).exportJson(),
```

В `restoreAllData` (311): если ключа нет — **историю не трогать**
(не очищать!), ровно как сделано для `ArchiveNotificationStore` — обязателен
только `saved_day_data`.

**Тесты** — `test/.../data/SalaryLedgerBackupTest.kt` на чистой части:
экспорт → импорт даёт ту же историю; отсутствие ключа → импорт возвращает
`false`, история остаётся; битый JSON → `false`, история остаётся.

**Коммит:** `Добавил sidecar-метаданные записей и бэкап истории ЗП`

---

## 8. Порядок и зависимости

```
1 (носитель) → 3 (цена месяца) → 5 (заполнение) → 7 (UI)
      2 (хранилище) ↗        4 (API) ↗
8 — независим, можно делать когда угодно
```

Этап нельзя начинать, пока предыдущий по этой цепочке не закоммичен.
Если этап 4 или 5 упёрся — этапы 7 и 8 всё равно делаются: UI без данных
показывает цену профиля, это штатное поведение (FOUNDATION 3.5).

---

## 9. Контрольные значения

Из [HISTORY-2025-2026.md](HISTORY-2025-2026.md), раздел 3. Параметризованный
тест на 19 месяцев писать **не сейчас**: он требует данных API, которых
в юнит-тестах нет. Вместо него — точечные проверки резолвера на этих числах:

| Месяц | Занятий | Факт YClients | Цена приложения | Что проверяет |
|---|---|---|---|---|
| апрель 2025 | 102 | 127 500 | 1250 | совпадение, `resolved = true`, молчаливая заморозка |
| март 2026 | 115 | 170 600 | 1400 | расхождение 9 600 → `resolved = false` |
| май 2026 | 126 | 193 500 (в т. ч. 2 диагностики) | 1400 | вычет диагностик до деления |
| июнь 2026 | 115 | 174 450 (в т. ч. интенсив 11 200) | 1400 | ручной интенсив вычитается |
| август 2026 | — | нет факта (422) | 1500 | цена профиля без единого запроса |

**Главный контрольный случай — день 19.06.2026** (проверен запросом):
`services_count = 8`, `salary = 12050`, из них диагностика 2250.
Ожидаемая цена занятия — **1400**, а не 1506. Этот тест обязателен на этапе 3.

---

## 10. Что прогон делать не должен

- Запускать `./gradlew` в любом виде.
- Трогать `neiro-push-events/`, `server/`, `tools/`, `.signing/`.
- Менять `build.gradle.kts`, `libs.versions.toml`, версии, зависимости.
- Трогать live-опрос, push, воркеры уведомлений, архивный календарь.
- Менять `SessionParser` / `SessionFormat`.
- Зашивать в код даты «февраль–май 2026» и любые числа ставок.
- Переписывать TODO.md и документы в `docs/pricing-from-api/`, кроме
  `RUN-LOG.md`.
- Делать `git push`, создавать ветки, делать rebase, трогать чужие коммиты.
- Разбираться в чужих задачах из TODO.md (карточки детей, виджет, PDF).

---

## 11. Git

- Ветка — текущая `новая-оплата`, не переключаться.
- Один этап = один коммит, восемь коммитов за прогон.
- Сообщения — как в разделе 7, ровно эти. Одна строка, до 72 символов,
  прошедшее время, без тела и префиксов.
- В коммит идёт **только** код своего этапа. `RUN-LOG.md` — отдельным
  коммитом в самом конце: `Добавил журнал автономного прогона`.
- Не коммитить: `local.properties`, `google-services.json`, `.idea/`,
  `data/*.db`, `__pycache__`, `.DS_Store`.
- Если `git status` перед этапом показывает чужие незакоммиченные правки —
  **остановиться** (раздел 14).

---

## 12. Журнал прогона

Файл `docs/pricing-from-api/RUN-LOG.md`, дописывается **после каждого этапа**:

```markdown
## Этап N — <название>

- Коммит: <хэш> «<сообщение>»
- Файлы: <список>
- Тесты добавлены: <список классов и что проверяют>
- Решения по ходу: <что выбрал и почему; если ничего — «нет»>
- Расхождения документов с кодом: <что нашёл>
- Не сделано: <что и почему>
- Требует внимания человека: <да/нет + что именно>
```

Раздел «Требует внимания» — самый важный: там перечисляется всё, что агент
не смог проверить без сборки и сети (в первую очередь этап 4 — реальные
имена полей списка начислений).

---

## 13. Чек-лист человека утром

1. `git log --oneline` — восемь коммитов + журнал, сообщения по правилам.
2. `./gradlew test` — все юнит-тесты, включая новые.
3. `./gradlew assembleDebug` — сборка.
4. Прочитать `RUN-LOG.md`, раздел «Требует внимания» каждого этапа.
5. Проверить на живых данных:
   - профиль → статистика → 2025 год виден (история, этап 3);
   - месяц с расхождением показывает отметку, разбор открывается по клику;
   - правка цены месяца не переписывается следующим синком;
   - календарь и суммы за текущий месяц не изменились по сравнению с вчера.
6. Сверить одно число руками: день 19.06.2026 → 12 050 ₽.
7. Если API-слой (этап 4) не сошёлся с реальным ответом — править имена
   полей в `SalaryModels.kt`, остальное от этого не зависит.

---

## 14. Стоп-условия

Прогон останавливается, пишет причину в `RUN-LOG.md` и больше ничего не
делает, если:

- в рабочем дереве есть чужие незакоммиченные изменения на старте;
- ветка не `новая-оплата`;
- чтобы пройти этап, нужно изменить ожидаемое число в существующем тесте;
- чтобы пройти этап, нужно тронуть push, live-опрос, уведомления, архив
  или `SessionParser`;
- два этапа подряд не удалось довести до коммита;
- обнаружилось, что документ и код расходятся так, что от выбора зависит
  структура данных (а не имя функции).

Во всех остальных случаях: решение принимается по разделу 4, запись в журнал,
работа продолжается.
