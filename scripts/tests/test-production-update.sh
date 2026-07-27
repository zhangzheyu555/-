#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

mkdir -p "$temporary_root/bin"
cat >"$temporary_root/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${DOCKER_CALL_LOG:?}"
EOF
chmod +x "$temporary_root/bin/docker"

export PATH="$temporary_root/bin:$PATH"
export DOCKER_CALL_LOG="$temporary_root/docker-calls.log"
touch "$temporary_root/production.env"

APP_IMAGE_TAG='sha-0123456789abcdef0123456789abcdef01234567' \
  COMPOSE_FILE="$repo_root/deploy/docker-compose.production.yml" \
  ENV_FILE="$temporary_root/production.env" \
  "$repo_root/deploy/production-update.sh"

expected_pull="compose --env-file $temporary_root/production.env -f $repo_root/deploy/docker-compose.production.yml pull"
expected_up="compose --env-file $temporary_root/production.env -f $repo_root/deploy/docker-compose.production.yml up -d --remove-orphans --wait --wait-timeout 180"
grep -Fx "$expected_pull" "$DOCKER_CALL_LOG"
grep -Fx "$expected_up" "$DOCKER_CALL_LOG"
echo 'PASS production update pulls immutable images then starts Compose.'
