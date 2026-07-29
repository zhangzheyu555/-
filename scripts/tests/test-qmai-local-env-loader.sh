#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_env="$(mktemp)"
trap 'rm -f "$temporary_env"' EXIT

printf '%s\n' 'QMAI_CREDENTIAL_ENCRYPTION_KEY="non-secret-local-check"' >"$temporary_env"

unset QMAI_CREDENTIAL_ENCRYPTION_KEY
export QMAI_LOCAL_ENV_FILE="$temporary_env"
. "$repo_root/scripts/load-qmai-credential-env.sh"
test "$QMAI_CREDENTIAL_ENCRYPTION_KEY" = "non-secret-local-check"

export QMAI_CREDENTIAL_ENCRYPTION_KEY="deployment-value-wins"
printf '%s\n' 'QMAI_CREDENTIAL_ENCRYPTION_KEY=must-not-overwrite' >"$temporary_env"
. "$repo_root/scripts/load-qmai-credential-env.sh"
test "$QMAI_CREDENTIAL_ENCRYPTION_KEY" = "deployment-value-wins"

echo 'PASS local QMAI key file loads without overriding an injected deployment value.'
