#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env"
COMPOSE_FILE="${ROOT_DIR}/compose.local.yml"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/local-run.sh infra     Start local MySQL/Redis containers
  ./scripts/local-run.sh member    Run member-service with .env
  ./scripts/local-run.sh auth      Run auth-service with .env
  ./scripts/local-run.sh job       Run job-service with .env
  ./scripts/local-run.sh stop      Stop local containers
  ./scripts/local-run.sh status    Show local container status
EOF
}

require_env_file() {
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "Missing .env file: ${ENV_FILE}" >&2
    echo "Create it from .env.example and fill required values." >&2
    exit 1
  fi
}

load_env() {
  require_env_file
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
  export AUTH_VERIFICATION_LOG_CODE_ENABLED="${AUTH_VERIFICATION_LOG_CODE_ENABLED:-true}"
}

case "${1:-}" in
  infra)
    require_env_file
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d
    ;;
  member)
    load_env
    cd "${ROOT_DIR}/member-service"
    ./gradlew bootRun
    ;;
  auth)
    load_env
    cd "${ROOT_DIR}/auth-service"
    ./gradlew bootRun
    ;;
  job)
    load_env
    cd "${ROOT_DIR}/job-service"
    ./gradlew bootRun
    ;;
  stop)
    require_env_file
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" down
    ;;
  status)
    require_env_file
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
    ;;
  *)
    usage
    exit 1
    ;;
esac
