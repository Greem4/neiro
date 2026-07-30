# Как искать и доставать API YClients

Практическая методичка: как были найдены эндпоинты раздела «Расчёт зарплат»,
как повторить любой запрос из [HISTORY-2025-2026.md](HISTORY-2025-2026.md)
и где искать то, чего в этом документе нет.

---

## 1. Где вообще искать эндпоинты

Три источника, по убыванию полезности:

### 1.1. Официальная документация

<https://developer.yclients.com/> — есть далеко не всё. Раздел ЗП
(`/salary/...`) документирован частично: посуточный расчёт и детализация
начислений в доке отсутствуют или описаны иначе, чем работают.
Дока годится, чтобы понять **формат** (версия `v2+json`, схема авторизации,
конверт `{success, data, meta}`), но не как каталог возможностей.

### 1.2. Веб-кабинет + DevTools — основной способ

Самый надёжный: открыть нужную страницу в личном кабинете YClients и посмотреть,
какие XHR она делает. Именно так найден весь раздел ЗП.

1. Открыть <https://yclients.com/salary_daily/520135/?date=2026-07-26>
   (или любую другую страницу с нужными числами).
2. DevTools → Network → фильтр **Fetch/XHR**, перезагрузить страницу.
3. Найти запрос к `api.yclients.com` — там же в нём готовые заголовки.
4. Правый клик → **Copy as cURL**, вставить в терминал, заменить токены на свои.

Правило: **если число видно на экране кабинета — значит есть эндпоинт,
который его отдаёт.** Страницу «Расчёт за день» ищем по URL `salary_daily`,
«Начисления» — в разделе «Зарплата» карточки сотрудника.

### 1.3. Перебор по шаблону URL

Когда один эндпоинт найден, соседние часто угадываются. Раздел ЗП устроен так:

```
/company/{company_id}/salary/period/staff/daily/{staff_id}/    ← нашли в DevTools
/company/{company_id}/salary/period/staff/{staff_id}/          ← угадали, работает
/company/{company_id}/salary/staff/{staff_id}/period/          ← угадали, работает
/company/{company_id}/salary/payroll/staff/{staff_id}/calculation/
/company/{company_id}/salary/staff/{staff_id}/salary_schemes/
```

Отличать «нет такого эндпоинта» от «нет прав» помогает код ответа:

| Код | Значение |
|---|---|
| 200 | есть |
| 404 + `"Произошла ошибка"` | такого маршрута нет (так отвечают `salary/schemes/`, `activity/{id}/records/`) |
| 403 | маршрут есть, прав нет |
| 422 + осмысленный `meta.message` | маршрут есть, параметры не те (это подсказка, читать текст) |

---

## 2. Авторизация

Нужны **два** токена, оба в одном заголовке:

```
Accept: application/vnd.yclients.v2+json
Authorization: Bearer <partner_token>, User <user_token>
```

- `partner_token` — постоянный, лежит в `local.properties` как
  `YCLIENTS_PARTNER_TOKEN`. Приложение шлёт его же (`data/network/YClientsClient.kt:20,53`).
- `user_token` — получается логином, живёт долго, но не вечно:

```bash
curl -s -X POST https://api.yclients.com/api/v1/auth \
  -H 'Accept: application/vnd.yclients.v2+json' \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $YCLIENTS_PARTNER_TOKEN" \
  -d '{"login":"'"$DEV_LOGIN"'","password":"'"$DEV_PASSWORD"'"}'
```

Ответ: `data.user_token`. Секреты (`YCLIENTS_PARTNER_TOKEN`, `YCLIENTS_COMPANY_ID`,
`DEV_LOGIN`, `DEV_PASSWORD`) — только в `local.properties`, файл в `.gitignore`.
**В документы и коммиты токены не попадают.**

`staff_id` берётся из `GET /company/{c}/staff/` по имени; в приложении он уже есть
как `TokenStorage.staffId`.

---

## 3. Минимальный клиент

Скрипты разбора жили во временной папке сессии и в репозиторий не попали —
здесь воспроизводимый минимум, 30 строк, этого хватает для всего разбора:

```python
import json, os, urllib.request, urllib.error, urllib.parse

props = dict(l.strip().split("=", 1) for l in open("local.properties")
             if "=" in l and not l.startswith("#"))
PT, COMPANY = props["YCLIENTS_PARTNER_TOKEN"], props["YCLIENTS_COMPANY_ID"]
UT = os.environ["YC_USER_TOKEN"]          # из /auth
STAFF, BASE = "3618433", "https://api.yclients.com/api/v1"

def get(path, params=None):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.yclients.v2+json",
        "Authorization": f"Bearer {PT}, User {UT}",
    })
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return {"_http_error": e.code, "_body": e.read().decode()[:400]}

# ЗП по дням за год — 2,5 секунды, 92 КБ
print(get(f"/company/{COMPANY}/salary/period/staff/daily/{STAFF}/",
          {"date_from": "2025-01-01", "date_to": "2025-12-31"}))
```

Ловить `HTTPError` обязательно: YClients отдаёт содержательные `422` с текстом
в `meta.message`, и без перехвата исключения этот текст теряется.

---

## 4. Карта эндпоинтов, проверенных на фирме 520135

| Эндпоинт | Что даёт | Статус |
|---|---|---|
| `POST /auth` | `user_token` | 200 |
| `GET /company/{c}/staff/` | список сотрудников, `staff_id` | 200 |
| `GET /records/{c}?staff_id=&start_date=&end_date=&count=300&page=` | журнал записей, статусы, услуги | 200 |
| `GET /company/{c}/salary/period/staff/daily/{s}/?date_from&date_to` | **ЗП по каждому дню** | 200 |
| `GET /company/{c}/salary/period/staff/{s}/?date_from&date_to` | агрегат за период | 200 |
| `GET /company/{c}/salary/staff/{s}/period/?date_from&date_to` | то же, «свой» вариант | 200 |
| `GET /company/{c}/salary/payroll/staff/{s}/calculation/?date_from&date_to` | список начислений (месяц → id, сумма) | 200 |
| `GET /company/{c}/salary/payroll/staff/{s}/calculation/{id}` | **детализация: каждая запись + ставка** | 200 |
| `GET /company/{c}/salary/staff/{s}/salary_schemes/` | какая схема ЗП и с какой даты | 200 |
| `GET /company/{c}/salary/calculation/staff/daily/{s}/` | взаиморасчёты (баланс), к ставкам не относится | 200 |
| `GET /activity/{c}/{activity_id}/` | групповое событие: услуга, мест, записано | 200 |
| `GET /company/{c}/salary/schemes/` | — | **404**, нет такого |
| `GET /activity/{c}/{activity_id}/records/` | — | **404**, нет такого |

---

## 5. Что в ответах читать

### 5.1. Посуточный расчёт — «сколько получено»

```json
{"date":"2026-07-26","period_calculation":{
   "working_days_count":1,"working_hours_count":8.83,
   "group_services_count":0,"services_count":6,
   "services_sum":"18000","total_sum":"18000","salary":"8400"}}
```

`salary` — ровно то, что показывает страница `salary_daily`.
`services_count` — индивидуальные услуги (занятия + диагностики),
`group_services_count` — интенсивы, они считаются отдельно.

Год одним запросом (365 дней) — 2,5 с и 92 КБ. Тянуть историю разом дёшево.

### 5.2. Детализация начисления — «по какой ставке»

```json
{"date":"2026-06-30","time":"16:00","item_type_slug":"record",
 "record_id":1511357589,"client_id":255370970,"cost":"3000","salary_sum":"1400",
 "salary_calculation_info":{"scheme_title":"Зеленкина Светлана"},
 "targets":[{"target_type_slug":"service","target_id":17390933,
   "title":"Нейрокоррекция","cost":"3000","salary_sum":"1400",
   "salary_calculation":{"type_slug":"fix","value":1400}}]}
```

Что где:

| Поле | Смысл |
|---|---|
| `item_type_slug` | `record` — обычная запись, `activity` — групповое событие (интенсив) |
| `targets[].salary_calculation.type_slug` | **`fix` или `percent`** — схема начисления |
| `targets[].salary_calculation.value` | сумма при `fix`, процент при `percent` |
| `targets[].cost` | база, от которой считается процент (**прайс, не оплата клиента**) |
| `salary_calculation_info.scheme_title` | название схемы — по нему видно смену схемы задним числом |
| `record_id` | связь с записью журнала |

Именно здесь нашлось, что до июня 2026 схема была `percent 50`, а не фикс.
**Не полагаться на одно последнее начисление** — прогонять все и смотреть,
менялись ли `type_slug`, `value` и `cost`.

### 5.3. Журнал записей — сколько занятий и по какой цене

Главная ловушка. У услуги в `/records` четыре похожих поля:

```json
{"id":17390933,"title":"Нейрокоррекция",
 "cost":0,"cost_to_pay":0,"manual_cost":2500,"cost_per_unit":2500,
 "discount":0,"first_cost":2500,"amount":1}
```

| Поле | Что это |
|---|---|
| `cost` / `cost_to_pay` | **сколько клиент заплатил деньгами** — `0`, если списано с абонемента |
| `first_cost`, `cost_per_unit`, `manual_cost` | **базовая цена на момент записи** — то, что нужно |

В апреле 2025 из 102 занятий у 57 стоит `cost = 0`. Любая эвристика по `cost`
(в том числе текущая автоподстановка в `YClientsCalendarSync.kt:239-246`)
на этих данных врёт. В модели `ServiceData` (`data/network/YClientsModels.kt:86`)
поля `first_cost` сейчас нет — его надо добавить.

Статус посещения — `attendance` в записи: `1` пришёл, `0` ожидание, `-1` неявка.
**В ЗП идёт только `1`.**

---

## 6. Ограничения, на которые наткнулись

| Ограничение | Как проявляется |
|---|---|
| **Будущее не считается** | `date_to` > сегодня → `422`, `«Расчет заработной платы возможен только по текущую дату»`. Диапазон, залезающий в будущее, отбивается целиком. |
| **Поиск начислений — не больше года** | `422`, `«Поиск начислений заработной платы доступен только за период до 1 года»`. Разбивать по годам. |
| **Детализация только у закрытых месяцев** | Начисление создаётся в конце месяца. За текущий месяц список пустой — остаётся посуточный агрегат. |
| **Схема ЗП без истории** | `salary_schemes/` отдаёт только действующую запись (`date_start: 2026-06-01`). Историю схем видно лишь косвенно — по `scheme_title` в старых начислениях. |
| **Содержимое схемы недоступно** | Эндпоинта, отдающего ставки схемы по её `id`, нет (404). Ставки достаются только из фактических начислений. |
| **Интенсив без разбивки** | В позиции `activity` нет `record_id` и списка детей. Число пришедших выводится только делением `cost` на цену за ребёнка. |
| **Лимиты** | 200 запросов/мин, 5/с. Наша нагрузка — единицы запросов, запас огромный. |

---

## 7. Как проверять, что вытащенное — правда

Порядок, который сработал:

1. **Два эндпоинта на одно число.** Сумма по дням
   (`salary/period/staff/daily/`) против суммы позиций начисления
   (`payroll/.../calculation/{id}`). Они считаются независимо —
   совпали до рубля во всех 18 закрытых месяцах.
2. **Третий источник на количество.** Число записей с `attendance == 1`
   в `/records/` против числа позиций в начислении.
3. **Глазами по одному дню.** `https://yclients.com/salary_daily/520135/?date=ГГГГ-ММ-ДД`
   — число на странице должно совпасть с `salary` за этот день.
4. **Ручное разложение дня.** Взять день, расписать «N пришедших × ставка»
   и сойтись с `salary`. Так проверили, что `cost = 0` всё равно даёт полную ставку,
   что неявка даёт ноль и что интенсив = ставка × пришедшие.

Если хоть один из четырёх шагов не сходится — значит в модели чего-то не хватает,
а не «API округляет».

---

## 8. Чего ещё не искали

- **Бонусы, штрафы, гарантированный минимум.** У этого сотрудника их в данных нет,
  но в схеме они бывают. Появятся отдельными позициями в начислении — код разбора
  не должен падать на незнакомом `item_type_slug`.
- **Выплаты (не начисления).** `salary/calculation/staff/daily/` — это взаиморасчёты;
  что именно и когда выплачено на карту, оттуда достать не пробовали.
- **Абонементы.** `/records` показывает только факт списания (`cost = 0`).
  Отдельный раздел абонементов не смотрели — он бы объяснил, почему у ребёнка
  держится старая цена месяцами.
- **Права на чужих аккаунтах.** Всё проверено на одном логине. Что отдаёт
  посуточный эндпоинт сотруднику без прав владельца — неизвестно, ожидаем 403.
