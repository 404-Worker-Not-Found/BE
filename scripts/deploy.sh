#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${DEPLOY_ENV_FILE:-${ROOT_DIR}/.env.deploy}"
COMPOSE=(docker compose --project-name wnf --env-file "$ENV_FILE" -f "${ROOT_DIR}/compose.deploy.yml")
APPS=(auth-service member-service job-service matching-service work-service chat-service payment-service notification-service)

check_config() {
  local rendered
  rendered="$(mktemp)"
  chmod 600 "$rendered"
  if ! "${COMPOSE[@]}" config --format json > "$rendered"; then
    rm -f "$rendered"
    return 1
  fi
  local result=0
  python3 "${ROOT_DIR}/deploy/check-config.py" "$rendered" || result=$?
  rm -f "$rendered"
  return "$result"
}

backup_mysql() {
  local output temporary
  mkdir -p "${ROOT_DIR}/backups"
  chmod 700 "${ROOT_DIR}/backups"
  output="${ROOT_DIR}/backups/mysql-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  temporary="$(mktemp "${ROOT_DIR}/backups/.mysql-XXXXXX")"
  chmod 600 "$temporary"
  if ! "${COMPOSE[@]}" exec -T mysql sh -c '
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot \
      --single-transaction --routines --triggers --events \
      --no-tablespaces --set-gtid-purged=OFF --databases \
      auth_service member_service job_service matching_service \
      work_service chat_service payment_service notification_service
  ' | gzip > "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  mv "$temporary" "$output"
  echo "Database backup saved: $output (copy it off this server)."
}

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Create $ENV_FILE from deploy/env.example first." >&2
  exit 1
fi

case "${1:-}" in
  check)
    check_config
    ;;
  pull)
    check_config
    "${COMPOSE[@]}" pull
    ;;
  up)
    check_config
    "${COMPOSE[@]}" up -d --no-build --wait --wait-timeout 360 mysql auth-redis matching-redis app-network
    # Always rejoin the current namespace, including after a host/anchor restart.
    "${COMPOSE[@]}" up -d --no-deps --no-build --force-recreate --wait --wait-timeout 360 "${APPS[@]}" proxy
    # Compose's proxy has no application readiness check. Check its configuration too.
    "${COMPOSE[@]}" exec -T proxy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
    ;;
  status)
    "${COMPOSE[@]}" ps
    ;;
  logs)
    "${COMPOSE[@]}" logs --tail=100 "${2:?Specify one service name}"
    ;;
  backup)
    backup_mysql
    ;;
  pause)
    # Keep MySQL, Redis and certificate storage running during maintenance.
    "${COMPOSE[@]}" stop proxy "${APPS[@]}"
    ;;
  *)
    echo "Usage: $0 {check|pull|up|status|logs SERVICE|backup|pause}" >&2
    exit 1
    ;;
esac
