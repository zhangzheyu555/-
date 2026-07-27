#!/usr/bin/env bash
set -Eeuo pipefail

# This file must be installed as root:root with mode 0755 outside the deploy user's home.
# sudo must keep env_reset enabled, so deploy cannot replace these paths at runtime.
project_root="${PROJECT_ROOT:-/root/store-profit}"
release_staging_root="${RELEASE_STAGING_ROOT:-/home/deploy/releases}"
release_id="${1:-}"

if [[ "$(id -u)" != '0' ]]; then
  echo 'This release entrypoint must run as root.' >&2
  exit 1
fi

if [[ ! "$release_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo 'Usage: store-profit-release <release-id>' >&2
  exit 1
fi

source_directory="$release_staging_root/$release_id"
target_directory="$project_root/incoming/$release_id"
backend_archive="$source_directory/backend-release.tar.gz"
frontend_archive="$source_directory/frontend-release.tar.gz"
release_script="$project_root/deploy/local-release-update.sh"

for required_path in "$backend_archive" "$frontend_archive"; do
  if [[ ! -f "$required_path" ]]; then
    echo "Required release file is missing: $required_path" >&2
    exit 1
  fi
  if [[ -L "$required_path" ]]; then
    echo "Release archive must not be a symbolic link: $required_path" >&2
    exit 1
  fi
done

if [[ ! -f "$release_script" || -L "$release_script" ]]; then
  echo "Required release script is missing or unsafe: $release_script" >&2
  exit 1
fi

install -d -o root -g root -m 0750 "$target_directory"
install -o root -g root -m 0640 "$backend_archive" "$target_directory/backend-release.tar.gz"
install -o root -g root -m 0640 "$frontend_archive" "$target_directory/frontend-release.tar.gz"

exec "$release_script" "$release_id"
