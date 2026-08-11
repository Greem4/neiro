#!/usr/bin/env bash
# Ставит reverse SSH-туннель Pi → VPS для neiro-push как systemd --user юнит:
# VPS 127.0.0.1:18083 → Pi 127.0.0.1:8012. Свой порт и свой юнит — туннель
# neiro-push-events (18082 → 8011) продолжает работать рядом нетронутым.
#
#   NEIRO_PUSH_TUNNEL_HOST=176.12.65.86 ./neiro-push/scripts/install-tunnel.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

TUNNEL_USER="${NEIRO_PUSH_TUNNEL_USER:-tunnel}"
TUNNEL_HOST="${NEIRO_PUSH_TUNNEL_HOST:?укажи NEIRO_PUSH_TUNNEL_HOST — IP/хост VPS}"
TUNNEL_PORT="${NEIRO_PUSH_TUNNEL_PORT:-18083}"

ssh_pi env TUNNEL_USER="${TUNNEL_USER}" TUNNEL_HOST="${TUNNEL_HOST}" TUNNEL_PORT="${TUNNEL_PORT}" bash -s <<'REMOTE'
set -euo pipefail
mkdir -p ~/.config/neiro-push ~/.config/systemd/user

cat > ~/.config/neiro-push/tunnel.env <<ENVEOF
REMOTE_USER=${TUNNEL_USER}
REMOTE_HOST=${TUNNEL_HOST}
REMOTE_PORT=22
REMOTE_BIND_ADDR=127.0.0.1
REMOTE_BIND_PORT=${TUNNEL_PORT}
LOCAL_TARGET_ADDR=127.0.0.1
LOCAL_TARGET_PORT=8012
SSH_KEY=/home/greem4/.ssh/id_ed25519_vps_tunnel
ENVEOF

cat > ~/.config/systemd/user/neiro-push-tunnel.service <<'UNITEOF'
[Unit]
Description=neiro-push — reverse SSH-туннель Pi → VPS (/v1)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=%h/neiro-push/scripts/start-tunnel.sh
Restart=always
RestartSec=5
StartLimitIntervalSec=0

[Install]
WantedBy=default.target
UNITEOF

chmod +x ~/neiro-push/scripts/start-tunnel.sh
systemctl --user daemon-reload
systemctl --user enable --now neiro-push-tunnel.service
sleep 2
systemctl --user status neiro-push-tunnel.service --no-pager || true
REMOTE
