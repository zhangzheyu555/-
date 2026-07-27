#!/usr/bin/env bash
set -Eeuo pipefail

release_id="${1:-}"
if [[ ! "$release_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo 'Usage: local-release-update.sh <release-id>' >&2
  exit 1
fi

script_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_root="${PROJECT_ROOT:-$(cd "$script_directory/.." && pwd)}"
incoming_directory="$project_root/incoming/$release_id"
backend_archive="$incoming_directory/backend-release.tar.gz"
frontend_archive="$incoming_directory/frontend-release.tar.gz"

for required_path in "$project_root/docker-compose.yml" "$backend_archive" "$frontend_archive"; do
  if [[ ! -f "$required_path" ]]; then
    echo "Required deployment file is missing: $required_path" >&2
    exit 1
  fi
done

archive_is_safe() {
  local archive="$1"
  if tar -tzf "$archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    echo "Unsafe archive path rejected: $archive" >&2
    return 1
  fi
}

archive_is_safe "$backend_archive"
archive_is_safe "$frontend_archive"

staging_directory="$(mktemp -d "$project_root/.release-staging.XXXXXX")"
cleanup() { rm -rf "$staging_directory"; }
trap cleanup EXIT

mkdir -p "$staging_directory/backend" "$staging_directory/frontend"
tar -xzf "$backend_archive" -C "$staging_directory/backend"
tar -xzf "$frontend_archive" -C "$staging_directory/frontend"

backend_jars=()
while IFS= read -r backend_jar; do
  backend_jars+=("$backend_jar")
done < <(find "$staging_directory/backend" -maxdepth 1 -type f -name 'store-profit-backend-*.jar' -print)
if [[ "${#backend_jars[@]}" -ne 1 ]]; then
  echo 'Backend release must contain exactly one store-profit-backend-*.jar file at its archive root.' >&2
  exit 1
fi
if [[ ! -f "$staging_directory/frontend/dist/index.html" ]]; then
  echo 'Frontend release must contain dist/index.html.' >&2
  exit 1
fi

timestamp="$(date +%Y%m%d-%H%M%S)"
backup_directory="$project_root/backup/artifact-release-$release_id-$timestamp"
mkdir -p "$backup_directory"
if [[ -d "$project_root/backend/target" ]]; then cp -a "$project_root/backend/target" "$backup_directory/backend-target"; fi
if [[ -d "$project_root/frontend-vue/dist" ]]; then cp -a "$project_root/frontend-vue/dist" "$backup_directory/frontend-dist"; fi

mkdir -p "$project_root/backend/target"
find "$project_root/backend/target" -maxdepth 1 -type f -name 'store-profit-backend-*.jar' -delete
install -m 0644 "${backend_jars[0]}" "$project_root/backend/target/$(basename "${backend_jars[0]}")"
rm -rf "$project_root/frontend-vue/dist"
mv "$staging_directory/frontend/dist" "$project_root/frontend-vue/dist"

cd "$project_root"
docker compose build backend frontend
docker compose up -d backend frontend gateway
for _ in $(seq 1 90); do
  if docker compose exec -T backend curl -fsS http://127.0.0.1:8080/api/health >/dev/null \
    && docker compose exec -T gateway wget -qO- http://127.0.0.1/healthz >/dev/null; then
    printf '%s\n' "$release_id" > "$project_root/current-release"
    echo "Release $release_id deployed. Artifact backup: $backup_directory"
    exit 0
  fi
  sleep 1
done

echo "Release $release_id did not become healthy. Previous artifacts are retained in $backup_directory; database migrations are not rolled back automatically." >&2
exit 1
