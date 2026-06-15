#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

run_gradle_build() {
  local service_dir="$1"
  local service_name
  service_name="$(basename "$service_dir")"

  if [[ ! -x "$service_dir/gradlew" ]]; then
    echo "Gradle wrapper not found or not executable: $service_dir/gradlew" >&2
    exit 1
  fi

  echo "==> Verifying $service_name"
  (
    cd "$service_dir"
    ./gradlew build
  )
}

run_gradle_build "$REPO_ROOT/auth-service"

echo "==> Repository verification completed"
