#!/usr/bin/env bash
set -Eeuo pipefail

backend_jar="${1:-}"
readonly expected_flyway_latest=56
required_env=(
  APP_ENV SERVER_PORT MYSQL_HOST MYSQL_PORT MYSQL_DATABASE MYSQL_USERNAME
  MYSQL_PASSWORD MYSQL_CONTAINER_ID
)

for name in "${required_env[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required CI environment variable: ${name}" >&2
    exit 1
  fi
done

if [[ -z "$backend_jar" || ! -f "$backend_jar" ]]; then
  echo "Packaged backend jar was not found: ${backend_jar:-<empty>}" >&2
  exit 1
fi

log_file="${RUNNER_TEMP:-/tmp}/store-profit-backend-ci.log"
health_file="${RUNNER_TEMP:-/tmp}/store-profit-health-ci.json"
backend_pid=""

cleanup() {
  if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" 2>/dev/null; then
    kill "$backend_pid" 2>/dev/null || true
    wait "$backend_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

java -jar "$backend_jar" >"$log_file" 2>&1 &
backend_pid=$!

health_url="http://127.0.0.1:${SERVER_PORT}/api/health"
for _ in $(seq 1 90); do
  if curl --fail --silent --show-error "$health_url" >"$health_file"; then
    break
  fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then
    echo "Backend exited before the health endpoint became ready." >&2
    tail -n 200 "$log_file" >&2
    exit 1
  fi
  sleep 1
done

if [[ ! -s "$health_file" ]]; then
  echo "Backend health endpoint did not become ready within 90 seconds." >&2
  tail -n 200 "$log_file" >&2
  exit 1
fi

mysql_query() {
  docker exec \
    -e MYSQL_PWD="$MYSQL_PASSWORD" \
    "$MYSQL_CONTAINER_ID" \
    mysql --protocol=TCP --host=127.0.0.1 \
      --user="$MYSQL_USERNAME" --database="$MYSQL_DATABASE" \
      --batch --skip-column-names --execute="$1"
}

mysql_version="$(mysql_query 'SELECT VERSION();')"
if [[ "$mysql_version" != 8.* ]]; then
  echo "Expected MySQL 8, received: $mysql_version" >&2
  exit 1
fi

applied_count="$(mysql_query 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;')"
failed_count="$(mysql_query 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;')"
latest_migration="$(mysql_query 'SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;')"

if [[ ! "$applied_count" =~ ^[1-9][0-9]*$ ]]; then
  echo "Flyway did not record any successful migration." >&2
  exit 1
fi
if [[ "$failed_count" != "0" ]]; then
  echo "Flyway recorded ${failed_count} failed migration(s)." >&2
  exit 1
fi
if [[ "$latest_migration" != "$expected_flyway_latest" ]]; then
  echo "Expected MySQL Flyway migration V${expected_flyway_latest}, received V${latest_migration:-<none>}." >&2
  exit 1
fi

echo "MySQL version: ${mysql_version}"
echo "Successful Flyway migrations: ${applied_count}"
echo "Latest Flyway migration: V${latest_migration}"
echo "Application health: $(tr -d '\n\r' <"$health_file")"
