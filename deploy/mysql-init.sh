#!/bin/bash
(
set -euo pipefail

# The official MySQL entrypoint runs this only for an empty data directory.
# Restrict values before interpolating SQL; generated hex passwords satisfy this.
for service in auth member job matching work chat payment notification; do
  variable="${service^^}_DB_PASSWORD"
  password="${!variable}"
  if [[ ! "$password" =~ ^[a-zA-Z0-9_-]{32,128}$ ]]; then
    echo "Invalid password format for ${variable}; use 32-128 letters, digits, _ or -." >&2
    exit 1
  fi
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS ${service}_service CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${service}_user'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON ${service}_service.* TO '${service}_user'@'%';
SQL
done
touch /var/lib/mysql/.service-databases-ready
)
