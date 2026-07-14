#!/usr/bin/env bash
set -Eeuo pipefail

required_env=(
  E2E_BASE_URL E2E_API_URL
  E2E_BOSS_USERNAME E2E_BOSS_PASSWORD
  E2E_FINANCE_USERNAME E2E_FINANCE_PASSWORD
  E2E_WAREHOUSE_USERNAME E2E_WAREHOUSE_PASSWORD
  E2E_LEARNER_USERNAME E2E_LEARNER_PASSWORD
  E2E_OPERATIONS_USERNAME E2E_OPERATIONS_PASSWORD
  E2E_STORE_USERNAME E2E_STORE_PASSWORD
)

for name in "${required_env[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing controlled E2E input or GitHub Actions secret: ${name}" >&2
    exit 1
  fi
done

for name in E2E_BASE_URL E2E_API_URL; do
  value="${!name}"
  if [[ ! "$value" =~ ^https?:// ]]; then
    echo "${name} must start with http:// or https://" >&2
    exit 1
  fi
done

echo "Controlled E2E inputs are configured. Credential values are not printed."
