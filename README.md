# Neiro

[![CI](https://github.com/Greem4/neiro/actions/workflows/ci.yml/badge.svg)](https://github.com/Greem4/neiro/actions/workflows/ci.yml)
[![Релиз](https://img.shields.io/github/v/release/Greem4/neiro?label=релиз&color=brightgreen)](https://github.com/Greem4/neiro/releases/latest)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#установка)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.9-4285F4?logo=jetpackcompose&logoColor=white)](gradle/libs.versions.toml)
[![Лицензия MIT](https://img.shields.io/github/license/Greem4/neiro?label=лицензия&color=blue)](LICENSE)

Приложение для педагога: расписание занятий, деньги за них и уведомления о
том, что в расписании поменялось. Данные приходят из
[YClients](https://www.yclients.com/) — той же системы, где центр ведёт
запись, — но живут на телефоне и открываются мгновенно, без ожидания сети.

Главное отличие от кабинета YClients в браузере: приложение считает **деньги
педагога**, а не выручку филиала, и знает про перенос, отмену и «не пришёл»
раньше, чем об этом успеют сказать. Расписание на неделю, сколько выйдет за
месяц, что изменилось со вчера — на одном экране.

<!-- Этап 10 плана: сюда встанут скриншоты, как только появятся docs/images/
<p align="center">
  <img src="docs/images/calendar-light.png" width="240" alt="Календарь месяца">
  <img src="docs/images/day-details.png" width="240" alt="Расписание дня">
  <img src="docs/images/profile.png" width="240" alt="Профиль и статистика">
</p>
-->

---

## Установка

**Требования:** Android 7.0 и новее.

Готовые сборки — на [странице релизов](https://github.com/Greem4/neiro/releases/latest):
скачать `neiro-<версия>.apk`, открыть на телефоне, один раз разрешить
установку из этого источника. Дальше приложение обновляет себя само —
[как именно](#обновления). Что изменилось от версии к версии —
[CHANGELOG.md](CHANGELOG.md).

> Начиная с 0.1.1 приложение обновляет себя само: замечает новую версию,
> сообщает уведомлением и по согласию ставит её одним нажатием. Версии старше
> обновляются файлом со страницы релизов — поверх старой, данные сохраняются.

Сборка из исходников — Android Studio или `./gradlew assembleDebug`; что нужно
положить рядом, описано в [быстром старте](#быстрый-старт-разработка).

## Что умеет

- **Календарь месяца** — занятия, суммы по дням, быстрый переход к сегодня,
  выбор месяца оверлеем.
- **День целиком** — таймлайн занятий, статусы «подтвердил / пришёл / не
  пришёл», правка вручную, перенос.
- **Деньги** — расчёт по ставкам с учётом налога, годовая статистика и графики
  по месяцам, разделение факта и прогноза.
- **Уведомления** — напоминание перед занятием, утренний и вечерний дайджест,
  и главное: push о новой записи, отмене и переносе — их присылает
  собственный сервис событий, приложение не опрашивает YClients само.
- **Архив** — своя история занятий помимо YClients: офлайн-правки, экспорт и
  импорт JSON, журнал уведомлений.
- **Офлайн** — всё читается из локального хранилища; синхронизация нужна для
  обновления, а не для запуска.

Что сделано и что в планах — [TODO.md](TODO.md).

## Обновления

Релиз выпускается тегом: `git tag v0.2.0 && git push origin v0.2.0` — дальше
GitHub Actions собирает подписанный APK, считает контрольную сумму и
публикует релиз с описанием изменений.

Приложение раз в сутки спрашивает GitHub, не вышло ли что-то новее. Нашло —
показывает уведомление; по согласию скачивает APK, сверяет SHA256 **и** подпись
со своей, и ставит поверх. Настройки, календарь и архив остаются на месте.
На Android 12+ обновление приложения самого себя проходит без системного
диалога — согласия, которое пользователь уже дал, достаточно.

Механика целиком — [docs/updater/](docs/updater/README.md):
[архитектура](docs/updater/ARCHITECTURE.md),
[релизы](docs/updater/RELEASE.md),
[ограничения](docs/updater/RISKS.md).

## Быстрый старт (разработка)

1. `local.properties` — SDK, адрес и ключ сервиса Neiro
   (см. [yclients-integration.md](docs/yclients-integration.md#настройка)):

   ```properties
   NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru
   NEIRO_PUSH_API_KEY=<API_KEY из ~/neiro-push/.env>
   ```

2. `app/google-services.json` — для FCM ([push-setup.md § Firebase](docs/push-setup.md#2-firebase-fcm)).
3. Сборка — Android Studio / Gradle на машине разработчика.

Без `local.properties` приложение соберётся, но вход не пройдёт: сервис
неизвестен. Без `google-services.json` соберётся тоже — с выключенным FCM,
локальный режим и синхронизация при этом работают.

## Push-сервер

Публичный URL: `https://push.neiro.greemlab.ru`

Приложение работает с третьим поколением сервиса — `neiro-push` (маршрут `/v1`,
порт 8012). Второе поколение `neiro-push-events` (`/v2`) остаётся включённым как
страховка до конца перехода, первое (`server/`) погашено.

Сервис и его эксплуатация — [docs/neiro-push/](docs/neiro-push/README.md).

## Приватность

Приложение работает с чужими данными — расписанием и именами учеников, — и
поэтому важно, где они лежат.

**На телефоне:** расписание, имена учеников, статусы занятий, ставки и расчёт
денег, архив и журнал уведомлений. Всё это локальное хранилище приложения;
оно не выгружается никуда, кроме экспорта в JSON, который вы делаете сами и
сами решаете, куда положить файл.

**На сервере владельца:** токены YClients (`partner_token`, `user_token`) и
FCM-токены устройств. Сервер — Raspberry Pi владельца проекта, не облако:
именно он ходит в YClients вместо телефона и присылает push об изменениях.
Пароль от YClients не хранится нигде — он используется один раз при входе.
Устройство отзывается кнопкой в дашборде, после чего его токен перестаёт
работать.

**Чего нет вовсе:** аналитики, трекеров, рекламы, сбора статистики
использования. Из сторонних SDK в приложении только Firebase Cloud Messaging —
он нужен, чтобы доставлять push, и знает лишь токен устройства, а не
содержимое расписания.

Устройство целиком — [docs/neiro-push/ARCHITECTURE.md](docs/neiro-push/ARCHITECTURE.md).

## Документация

| Тема | Документ | О чём |
|------|----------|--------|
| Релизы и самообновление | [docs/updater/](docs/updater/README.md) | Версии, выпуск по тегам, как приложение обновляет себя, риски и план работ |
| Сервис `neiro-push` (текущий) | [docs/neiro-push/](docs/neiro-push/README.md) | Токены YClients на сервере, приложение ходит через прокси: архитектура, API, деплой, порядок запуска |
| YClients API | [docs/yclients-integration.md](docs/yclients-integration.md) | Вход, сетевой слой, синхронизация, `local.properties` |
| Push и FCM | [docs/push-setup.md](docs/push-setup.md) | Архив первого поколения: домен, Firebase, где лежит БД |
| Сервис событий (архив) | [docs/push-events/](docs/push-events/README.md) | Второе поколение `neiro-push-events`: погашено 13.08.2026, план и журнал работ |
| Скриншоты | [docs/screenshots.md](docs/screenshots.md) | Что снимать, требования README и RuStore, скрипт разбора кадров |
| Боковая панель | [docs/profile-drawer.md](docs/profile-drawer.md) | Drawer профиля, жесты, файлы в коде |
| Push-сервер (кратко) | [server/README.md](server/README.md) | API, деплой, скрипты — детали в [push-setup](docs/push-setup.md) |
| Аудит | [docs/audit/METHODIKA.md](docs/audit/METHODIKA.md) | Как проводить аудит: границы, чек-листы по областям, формат пакета, история прошлых аудитов |
| Дорожная карта | [TODO.md](TODO.md) | Что сделано и что в планах |

## Структура репозитория

```
app/                 Android-приложение (Kotlin, Compose)
app/.../push/        Регистрация FCM, приём push
app/.../update/      Самообновление (по плану docs/updater/)
neiro-push/          Новый сервис: токены и прокси YClients (по плану docs/neiro-push/)
neiro-push-events/   Действующий сервис событий (FastAPI, Pi) — работает до перехода
server/              Первый push-сервер (FastAPI, Pi) — погашен, удаляется при уборке
docs/                Документация
```

## Секреты (не в git)

| Файл | Содержимое |
|------|------------|
| `local.properties` | SDK, `NEIRO_PUSH_*`, подпись release |
| `app/google-services.json` | Firebase |
| `.signing/` | Ключи публикации |
| `~/neiro-push/.env` на Pi | `API_KEY`, `ADMIN_API_KEY`, шифрование токенов, `YCLIENTS_PARTNER_TOKEN` |
| `~/neiro-push/secrets/` на Pi | FCM service account JSON |

Ключей YClients в сборке нет: `partner_token` и `user_token` живут только на
Pi, приложение ходит к ним через прокси ([docs/neiro-push/](docs/neiro-push/README.md)).

Те же значения, что нужны сборке релиза, лежат в секретах GitHub Actions —
список в [docs/updater/RELEASE.md](docs/updater/RELEASE.md#секреты). Ключ
подписи (`.jks`) незаменим: без него обновить уже установленное приложение
нечем.

## Автор

Пишет и ведёт проект [Greem4](https://github.com/Greem4). Приложение личное,
сделано под конкретную работу конкретного педагога — но исходники открыты,
разбор устройства есть в `docs/`.

## Как помочь

Приложение личное, но исходники открыты: сообщения о проблемах и предложения
приветствуются. Как собрать, как оформить коммит и что стоит обсудить до
кода — [CONTRIBUTING.md](CONTRIBUTING.md).

Нашли дыру в безопасности — не в публичный issue, а приватно, кнопкой «Report
a vulnerability» во вкладке Security: [SECURITY.md](SECURITY.md).

## Лицензия

Код распространяется по лицензии MIT — брать, менять и использовать можно,
сохранив уведомление об авторстве. Полный текст — [LICENSE](LICENSE).
