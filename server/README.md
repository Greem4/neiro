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
| GET | `/health` | — |
| POST | `/v1/devices/register` | Bearer API_KEY |
| DELETE | `/v1/devices/{device_id}` | Bearer API_KEY |

Публично (через VPS-туннель): `https://medicine.greemlab.ru/neiro-push/health`

## Опрос YClients

- **09:00–21:00 МСК** — каждые 15 с
- **21:00–09:00 МСК** — раз в час

Один опрос на пару `company_id + staff_id`; push уходит на все зарегистрированные устройства.

## Скрипты

| Скрипт | Действие |
|--------|----------|
| `scripts/deploy.sh` | Деплой + патч Caddy на Pi |
| `scripts/install-autostart.sh` | @reboot в crontab Pi |
| `scripts/patch-pi-caddy.py` | Добавляет `/neiro-push` в ~/server/caddy/Caddyfile |
