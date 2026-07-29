#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_env="$(mktemp)"
trap 'rm -f "$temporary_env"' EXIT

printf '%s\n' 'QMAI_CREDENTIAL_ENCRYPTION_KEY=non-secret-compose-check' >"$temporary_env"

rendered_config="$(docker compose --env-file "$temporary_env" -f "$repo_root/docker-compose.yml" config)"
printf '%s\n' "$rendered_config" | grep -F 'QMAI_CREDENTIAL_ENCRYPTION_KEY: non-secret-compose-check'

rendered_production_config="$(
  GATEWAY_IMAGE_REPOSITORY=example.invalid/gateway \
  FRONTEND_IMAGE_REPOSITORY=example.invalid/frontend \
  BACKEND_IMAGE_REPOSITORY=example.invalid/backend \
  APP_IMAGE_TAG=test \
  SSL_DIRECTORY=/tmp/non-secret-ssl \
  ENV_FILE="$temporary_env" \
  docker compose -f "$repo_root/deploy/docker-compose.production.yml" config
)"
printf '%s\n' "$rendered_production_config" \
  | grep -F 'QMAI_CREDENTIAL_ENCRYPTION_KEY: non-secret-compose-check'

echo 'PASS local and production Compose configurations forward the QMAI credential key.'
