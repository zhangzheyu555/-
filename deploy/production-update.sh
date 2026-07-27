#!/usr/bin/env bash
set -Eeuo pipefail

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_directory/.." && pwd)"
export COMPOSE_FILE="${COMPOSE_FILE:-$script_directory/docker-compose.production.yml}"
export ENV_FILE="${ENV_FILE:-$project_root/.env.production}"
export APP_IMAGE_TAG="${APP_IMAGE_TAG:-}"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Production Compose file is missing: $COMPOSE_FILE" >&2
  exit 1
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "Production environment file is missing: $ENV_FILE" >&2
  exit 1
fi
if [[ ! "$APP_IMAGE_TAG" =~ ^(sha-[a-f0-9]{40}|v[0-9A-Za-z._-]+)$ ]]; then
  echo 'APP_IMAGE_TAG must be an immutable sha-<40 hexadecimal characters> or version tag.' >&2
  exit 1
fi

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
"${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans --wait --wait-timeout 180
echo "Deployment completed with immutable image tag: $APP_IMAGE_TAG"
