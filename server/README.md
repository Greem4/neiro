# Neiro Push Server

Сервер на Raspberry Pi: опрашивает YClients и шлёт FCM на телефоны.

Полная инструкция (Firebase, приложение, VPS): [docs/push-setup.md](../docs/push-setup.md).

## Деплой

```bash
./scripts/deploy.sh
```

## API

| Метод | Путь | Auth |
|-------|------|------|
| GET | `/health` | Admin API key |
| GET | `/v1/admin/overview` | Admin API key |
| POST | `/v1/admin/test-push` | Admin API key |
| POST | `/v1/devices/register` | API key (приложение) |
| DELETE | `/v1/devices/{device_id}` | API key (приложение) |

Публично: `https://push.neiro.greemlab.ru/health` — только с `Authorization: Bearer <ADMIN_API_KEY>`.

Данные: SQLite `/data/neiro_push.db` в Docker-томе на Pi (`accounts`, `devices`).

## Опрос YClients

- **09:00–21:00 МСК** — каждые 15 с
- **21:00–09:00 МСК** — раз в час

Один опрос на пару `company_id + staff_id`; push уходит на все зарегистрированные устройства.

## Скрипты

| Скрипт | Действие |
|--------|----------|
| `scripts/deploy.sh` | Деплой + патч Caddy на Pi |
| `scripts/install-autostart.sh` | @reboot в crontab Pi |
| `scripts/patch-pi-caddy.py` | vhost `push.neiro.greemlab.ru` в Caddy на Pi |
| `scripts/patch-vps-nginx.sh` | nginx + TLS на VPS |
| `scripts/test-push.sh` | Тестовый FCM push — [docs](../docs/push-setup.md) |
| `scripts/admin-status.sh` | Health и список устройств |
| `scripts/backup.sh` | Бэкап базы и `.env` с Pi на локальную машину |
| `scripts/restore.sh` | Восстановление базы из бэкапа |
| `scripts/logs.sh` | Логи контейнера с Pi (`--errors` — только проблемы за сутки) |

Бэкапы кладутся в `server/backups/` (в git не попадают) и в `~/neiro-push-backups`
на Pi, где хранятся 10 последних. Снимать **перед каждым деплоем со схемой**.

## Статус: заморожен

Этот сервис обслуживает работающую сборку приложения 0.6.9.0 и **менять его не
нужно**. Развитие идёт в отдельном сервисе `neiro-push-events/`, который ставится
рядом (свой контейнер, своя база, порт 8011, публичный путь `/v2`).

Разбор проблем текущей схемы, решения и пошаговый план —
[docs/push-events-plan.md](../docs/push-events-plan.md).
