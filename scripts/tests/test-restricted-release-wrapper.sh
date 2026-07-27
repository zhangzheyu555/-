#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

project_root="$temporary_root/project"
staging_root="$temporary_root/releases"
release_id='release-001'
mkdir -p "$project_root/deploy" "$staging_root/$release_id" "$temporary_root/bin"
printf 'backend archive' >"$staging_root/$release_id/backend-release.tar.gz"
printf 'frontend archive' >"$staging_root/$release_id/frontend-release.tar.gz"
cat >"$project_root/deploy/local-release-update.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$1" >"${RELEASE_CALL_LOG:?}"
EOF
chmod +x "$project_root/deploy/local-release-update.sh"
cat >"$temporary_root/bin/id" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == '-u' ]]; then echo 0; else command id "$@"; fi
EOF
chmod +x "$temporary_root/bin/id"
cat >"$temporary_root/bin/install" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
directory=false
arguments=()
while (($#)); do
  case "$1" in
    -d) directory=true; shift ;;
    -o|-g|-m) shift 2 ;;
    *) arguments+=("$1"); shift ;;
  esac
done
if [[ "$directory" == true ]]; then
  mkdir -p "${arguments[0]}"
else
  cp "${arguments[0]}" "${arguments[1]}"
fi
EOF
chmod +x "$temporary_root/bin/install"

export PATH="$temporary_root/bin:$PATH"
export RELEASE_CALL_LOG="$temporary_root/release-call.log"
PROJECT_ROOT="$project_root" RELEASE_STAGING_ROOT="$staging_root" \
  "$repo_root/deploy/restricted-release-wrapper.sh" "$release_id"

cmp "$staging_root/$release_id/backend-release.tar.gz" "$project_root/incoming/$release_id/backend-release.tar.gz"
cmp "$staging_root/$release_id/frontend-release.tar.gz" "$project_root/incoming/$release_id/frontend-release.tar.gz"
test "$(cat "$RELEASE_CALL_LOG")" = "$release_id"

if PROJECT_ROOT="$project_root" RELEASE_STAGING_ROOT="$staging_root" \
  "$repo_root/deploy/restricted-release-wrapper.sh" '../unsafe' >/dev/null 2>&1; then
  echo 'Expected unsafe release ID to be rejected.' >&2
  exit 1
fi

rm "$staging_root/$release_id/backend-release.tar.gz"
ln -s /etc/passwd "$staging_root/$release_id/backend-release.tar.gz"
if PROJECT_ROOT="$project_root" RELEASE_STAGING_ROOT="$staging_root" \
  "$repo_root/deploy/restricted-release-wrapper.sh" "$release_id" >/dev/null 2>&1; then
  echo 'Expected a symlinked release archive to be rejected.' >&2
  exit 1
fi

echo 'PASS restricted wrapper accepts only staged release artifacts and delegates by release ID.'
