#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_env="$(mktemp)"
trap 'rm -f "$temporary_env"' EXIT

printf '%s\n' 'QMAI_CREDENTIAL_ENCRYPTION_KEY=non-secret-compose-check' >"$temporary_env"

rendered_config="$(docker compose --env-file "$temporary_env" -f "$repo_root/docker-compose.yml" config)"
printf '%s\n' "$rendered_config" | grep -F 'QMAI_CREDENTIAL_ENCRYPTION_KEY: non-secret-compose-check'

echo 'PASS QMAI credential encryption key is forwarded to the backend container.'
