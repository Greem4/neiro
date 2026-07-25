#!/usr/bin/env bash
# Ставит reverse SSH-туннель Pi → VPS для neiro-push-events как systemd --user
# юнит: VPS 127.0.0.1:<порт> → Pi 127.0.0.1:8011. Независим от туннелей других
# проектов на этом Pi (использует свой порт и свой юнит).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

TUNNEL_USER="${NEIRO_PUSH_EVENTS_TUNNEL_USER:-tunnel}"
TUNNEL_HOST="${NEIRO_PUSH_EVENTS_TUNNEL_HOST:?укажи NEIRO_PUSH_EVENTS_TUNNEL_HOST — IP/хост VPS}"
TUNNEL_PORT="${NEIRO_PUSH_EVENTS_TUNNEL_PORT:-18082}"

ssh_pi env TUNNEL_USER="${TUNNEL_USER}" TUNNEL_HOST="${TUNNEL_HOST}" TUNNEL_PORT="${TUNNEL_PORT}" bash -s <<'REMOTE'
set -euo pipefail
mkdir -p ~/.config/neiro-push-events ~/.config/systemd/user

cat > ~/.config/neiro-push-events/tunnel.env <<EOF
REMOTE_USER=${TUNNEL_USER}
REMOTE_HOST=${TUNNEL_HOST}
REMOTE_PORT=22
REMOTE_BIND_ADDR=127.0.0.1
REMOTE_BIND_PORT=${TUNNEL_PORT}
LOCAL_TARGET_ADDR=127.0.0.1
LOCAL_TARGET_PORT=8011
SSH_KEY=/home/greem4/.ssh/id_ed25519_vps_tunnel
EOF

cat > ~/.config/systemd/user/neiro-push-events-tunnel.service <<'EOF'
[Unit]
Description=neiro-push-events — reverse SSH-туннель Pi → VPS (/v2)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=%h/neiro-push-events/scripts/start-tunnel.sh
Restart=always
RestartSec=5
StartLimitIntervalSec=0

[Install]
WantedBy=default.target
EOF

chmod +x ~/neiro-push-events/scripts/start-tunnel.sh
systemctl --user daemon-reload
systemctl --user enable --now neiro-push-events-tunnel.service
sleep 2
systemctl --user status neiro-push-events-tunnel.service --no-pager || true
REMOTE
