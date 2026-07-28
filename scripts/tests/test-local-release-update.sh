#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

mkdir -p "$temporary_root/backend/target" "$temporary_root/frontend-vue/dist" "$temporary_root/incoming/release-001" "$temporary_root/bin"
touch "$temporary_root/docker-compose.yml"
printf 'old backend' >"$temporary_root/backend/target/store-profit-backend-old.jar"
printf 'old frontend' >"$temporary_root/frontend-vue/dist/index.html"
mkdir -p "$temporary_root/package/backend" "$temporary_root/package/frontend/dist"
printf 'new backend' >"$temporary_root/package/backend/store-profit-backend-0.1.0-SNAPSHOT.jar"
printf 'new frontend' >"$temporary_root/package/frontend/dist/index.html"
tar -czf "$temporary_root/incoming/release-001/backend-release.tar.gz" -C "$temporary_root/package/backend" .
tar -czf "$temporary_root/incoming/release-001/frontend-release.tar.gz" -C "$temporary_root/package/frontend" dist
cat >"$temporary_root/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${DOCKER_CALL_LOG:?}"
EOF
chmod +x "$temporary_root/bin/docker"

export PATH="$temporary_root/bin:$PATH"
export DOCKER_CALL_LOG="$temporary_root/docker-calls.log"
PROJECT_ROOT="$temporary_root" "$repo_root/deploy/local-release-update.sh" release-001

test "$(cat "$temporary_root/backend/target/store-profit-backend-0.1.0-SNAPSHOT.jar")" = 'new backend'
test "$(cat "$temporary_root/frontend-vue/dist/index.html")" = 'new frontend'
test -f "$temporary_root/current-release"
grep -Fx 'compose build backend frontend' "$DOCKER_CALL_LOG"
grep -Fx 'compose up -d backend frontend gateway' "$DOCKER_CALL_LOG"
echo 'PASS local release update replaces artifacts only after a recoverable backup.'
