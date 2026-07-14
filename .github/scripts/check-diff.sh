#!/usr/bin/env bash
set -Eeuo pipefail

head_sha="${CI_HEAD_SHA:-HEAD}"
base_sha="${CI_BASE_SHA:-}"
zero_sha="0000000000000000000000000000000000000000"

if [[ -z "$base_sha" || "$base_sha" == "$zero_sha" ]]; then
  if git rev-parse "${head_sha}^" >/dev/null 2>&1; then
    base_sha="${head_sha}^"
  else
    base_sha="$(git rev-list --max-parents=0 "$head_sha")"
  fi
fi

git cat-file -e "${base_sha}^{commit}"
git cat-file -e "${head_sha}^{commit}"
echo "Checking whitespace errors in ${base_sha}..${head_sha}"
git diff --check "$base_sha" "$head_sha"
