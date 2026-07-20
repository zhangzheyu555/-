#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

readonly expected_mysql_flyway_latest=74
readonly expected_h2_flyway_latest=73

required_exam_and_training_files=(
  backend/src/main/resources/db/migration/V28__exam_training_seed_data.sql
  backend/src/main/java/com/storeprofit/system/operations/ExamCenterController.java
  backend/src/main/java/com/storeprofit/system/operations/ExamCenterModels.java
  backend/src/main/java/com/storeprofit/system/operations/ExamCenterRepository.java
  backend/src/main/java/com/storeprofit/system/operations/ExamCenterService.java
  backend/src/main/java/com/storeprofit/system/operations/ExamLearningController.java
  backend/src/main/java/com/storeprofit/system/operations/ExamLearningModels.java
  backend/src/main/java/com/storeprofit/system/operations/ExamLearningRepository.java
  backend/src/main/java/com/storeprofit/system/operations/ExamLearningService.java
  frontend-vue/src/api/exams.ts
  frontend-vue/src/pages/ExamCenterPage.vue
)

failures=0
blocked_source_count=0
declare -A blocked_release_sources=()
content_scan_file="$(mktemp "${TMPDIR:-/tmp}/ai-profit-source-gate.XXXXXX")"
trap 'rm -f "$content_scan_file"' EXIT

require_tracked_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "Required release source is missing: $path" >&2
    failures=1
    return
  fi
  if ! git ls-files --error-unmatch -- "$path" >/dev/null 2>&1; then
    echo "Required release source is not tracked by Git: $path" >&2
    failures=1
  fi
}

block_release_source() {
  local path="$1"
  local reason="$2"
  local key="${path}|${reason}"
  if [[ -n "${blocked_release_sources[$key]:-}" ]]; then
    return
  fi
  blocked_release_sources["$key"]=1
  printf 'Release source blocked [%s]: %s\n' "$reason" "$path" >&2
  failures=1
  blocked_source_count=$((blocked_source_count + 1))
}

verify_latest_flyway() {
  local migration_dir="$1"
  local label="$2"
  local expected_flyway_latest="$3"
  local migration_path file_name version latest=0
  local -a expected_migrations=()

  while IFS= read -r -d '' migration_path; do
    file_name="${migration_path##*/}"
    if [[ "$file_name" =~ ^V([0-9]+)__.*\.sql$ ]]; then
      version=$((10#${BASH_REMATCH[1]}))
      if (( version > latest )); then
        latest="$version"
      fi
    fi
  done < <(find "$migration_dir" -maxdepth 1 -type f -name 'V*__*.sql' -print0)

  if (( latest != expected_flyway_latest )); then
    echo "${label} Flyway latest source version must be V${expected_flyway_latest}, found V${latest}." >&2
    failures=1
  fi

  mapfile -d '' -t expected_migrations < <(
    find "$migration_dir" -maxdepth 1 -type f -name "V${expected_flyway_latest}__*.sql" -print0 | sort -z
  )
  if [[ "${#expected_migrations[@]}" -ne 1 ]]; then
    echo "Expected exactly one ${label} V${expected_flyway_latest} Flyway migration, found ${#expected_migrations[@]}." >&2
    failures=1
    return
  fi
  require_tracked_file "${expected_migrations[0]}"
}

is_flyway_migration_path() {
  case "$1" in
    backend/src/main/resources/db/migration/*|backend/src/main/resources/db/migration-h2/*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

write_content_match_file() {
  local pattern="$1"
  local status=0

  if git grep -z -I -l -E -e "$pattern" -- >"$content_scan_file"; then
    return 0
  else
    status=$?
  fi
  if [[ "$status" -eq 1 ]]; then
    : >"$content_scan_file"
    return 0
  fi
  echo "Unable to complete release-source content scan." >&2
  failures=1
  return 1
}

scan_content_matches() {
  local pattern="$1"
  local reason="$2"
  local matched_path

  if ! write_content_match_file "$pattern"; then
    return
  fi
  while IFS= read -r -d '' matched_path; do
    block_release_source "$matched_path" "$reason"
  done < "$content_scan_file"
}

scan_sensitive_config_content() {
  local matched_path lower_path
  local pattern="^[[:space:]]*(export[[:space:]]+)?(DEEPSEEK_API_KEY|OPENAI_API_KEY|EMPLOYEE_ASSISTANT_API_TOKEN|AWS_SECRET_ACCESS_KEY|AWS_ACCESS_KEY_ID)[[:space:]]*[:=][[:space:]]*['\"]?[A-Za-z0-9][A-Za-z0-9._~+/-]{31,}"

  if ! write_content_match_file "$pattern"; then
    return
  fi
  while IFS= read -r -d '' matched_path; do
    lower_path="${matched_path,,}"
    case "$lower_path" in
      *.env|*.env.*|*.json|*.yaml|*.yml|*.properties|*.toml|*.ini)
        block_release_source "$matched_path" 'sensitive configuration value in content'
        ;;
    esac
  done < "$content_scan_file"
}

scan_sensitive_configuration_values() {
  local matched_path lower_path
  # Values beginning with $, [, ( or @ are runtime expressions/collections rather
  # than embedded credentials (for example PowerShell's `[string](...)`).
  local pattern="^[[:space:]]*(export[[:space:]]+)?(DEEPSEEK_API_KEY|OPENAI_API_KEY|EMPLOYEE_ASSISTANT_API_TOKEN|MYSQL_PASSWORD|DB_PASSWORD|DATABASE_URL|AWS_SECRET_ACCESS_KEY|AWS_ACCESS_KEY_ID|PASSWORD|SECRET|TOKEN|PRIVATE_KEY)[[:space:]]*[:=][[:space:]]*['\"]?[^\$<@\[\([:space:]'\"]{8,}"

  if ! write_content_match_file "$pattern"; then
    return
  fi
  while IFS= read -r -d '' matched_path; do
    lower_path="${matched_path,,}"
    case "$lower_path" in
      *.env|*.yml|*.yaml|*.properties|*.conf|*.ini|*.ps1|*.sh)
        block_release_source "$matched_path" 'sensitive configuration value in content'
        ;;
    esac
  done < "$content_scan_file"
}

scan_tracked_content() {
  local matched_path lower_path
  local -A business_export_paths=()

  scan_content_matches '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----' 'private key material in content'
  scan_content_matches '(^|[^[:alnum:]_])(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|glpat-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35})([^[:alnum:]_-]|$)' 'provider credential pattern in content'
  scan_sensitive_config_content
  scan_sensitive_configuration_values

  if write_content_match_file '(^|[[:space:]])--[[:space:]]*(MySQL|MariaDB) dump|^/\*![0-9]{5}[[:space:]]+.*(DATABASE|TABLE)'; then
    while IFS= read -r -d '' matched_path; do
      lower_path="${matched_path,,}"
      if [[ "$lower_path" == *.sql ]] && ! is_flyway_migration_path "$matched_path"; then
        block_release_source "$matched_path" 'database dump signature in content'
      fi
    done < "$content_scan_file"
  fi

  if write_content_match_file '(INSERT|REPLACE)[[:space:]]+INTO[[:space:]]+`?(tenant|store|operation_log|profit|expense|salary|inventory|warehouse|requisition|inspection|employee)[A-Za-z0-9_]*`?.*(VALUES|VALUE)[[:space:]]*\('; then
    while IFS= read -r -d '' matched_path; do
      lower_path="${matched_path,,}"
      if [[ "$lower_path" == *.sql ]] && ! is_flyway_migration_path "$matched_path"; then
        block_release_source "$matched_path" 'business data insert signature in content'
      fi
    done < "$content_scan_file"
  fi

  if write_content_match_file '"(stores|store_list|operation_log|profit_records|expense_records|salary_records|warehouse|inventory)"|门店|操作日志|利润|工资|报销'; then
    while IFS= read -r -d '' matched_path; do
      lower_path="${matched_path,,}"
      if [[ "$lower_path" =~ \.(json|jsonl|csv|tsv)$ ]]; then
        business_export_paths["$matched_path"]=1
      fi
    done < "$content_scan_file"
  fi

  if write_content_match_file '"(tenant_id|store_id|created_at|updated_at|operation_type)"|租户|门店ID|创建时间'; then
    while IFS= read -r -d '' matched_path; do
      if [[ -n "${business_export_paths[$matched_path]+x}" ]]; then
        block_release_source "$matched_path" 'business data export signature in content'
      fi
    done < "$content_scan_file"
  fi
}

print_remediation_approval_checklist() {
  cat >&2 <<'EOF'

Release-source remediation approval checklist (no files were removed, untracked, or rewritten by this check):
  [ ] Data owner classifies every blocked file and preserves an encrypted, repository-external copy where required.
  [ ] Repository owner approves the exact tracked-path removal/quarantine change; this checker does not run git rm.
  [ ] Security owner approves any history-rewrite plan before historical cleanup is attempted.
  [ ] Security owner rotates credentials associated with any detected key material before a new release candidate is built.
  [ ] Release owner reruns this gate from a clean, reviewed commit and records the approval references.
EOF
}

for path in "${required_exam_and_training_files[@]}"; do
  require_tracked_file "$path"
done

training_image_dir=backend/src/main/resources/static/train-img
expected_training_image_count=119
if [[ ! -d "$training_image_dir" ]]; then
  echo "Runtime training image directory is missing: $training_image_dir" >&2
  failures=1
else
  mapfile -d '' -t training_images < <(find "$training_image_dir" -type f -print0 | sort -z)
  if [[ "${#training_images[@]}" -eq 0 ]]; then
    echo "Runtime training image directory is empty: $training_image_dir" >&2
    failures=1
  elif [[ "${#training_images[@]}" -ne "$expected_training_image_count" ]]; then
    echo "Expected ${expected_training_image_count} runtime training images, found ${#training_images[@]}." >&2
    failures=1
  fi
  for image_path in "${training_images[@]}"; do
    require_tracked_file "$image_path"
  done
fi

mapfile -t referenced_training_images < <(
  grep --recursive --only-matching --no-filename \
    --extended-regexp '/train-img/[A-Za-z0-9._/-]+\.(jpg|jpeg|png|webp)' \
    backend/src/main/resources/db/migration | sort --unique
)
if [[ "${#referenced_training_images[@]}" -eq 0 ]]; then
  echo "No /train-img runtime asset reference was found in Flyway migrations." >&2
  failures=1
else
  for image_reference in "${referenced_training_images[@]}"; do
    require_tracked_file "backend/src/main/resources/static${image_reference}"
  done
fi

verify_latest_flyway backend/src/main/resources/db/migration MySQL "$expected_mysql_flyway_latest"
verify_latest_flyway backend/src/main/resources/db/migration-h2 H2 "$expected_h2_flyway_latest"

while IFS= read -r -d '' tracked_path; do
  lower_path="${tracked_path,,}"

  case "$lower_path" in
    .env|*/.env|.env.*|*/.env.*)
      case "$lower_path" in
        *.example|*.sample|*.template) ;;
        *) block_release_source "$tracked_path" 'environment file path' ;;
      esac
      ;;
  esac

  case "$lower_path" in
    *.pem|*.key|*.p12|*.pfx|*.jks|*.keystore|*.kdb|*/id_rsa|*/id_ed25519)
      block_release_source "$tracked_path" 'private-key or credential-container extension'
      ;;
  esac

  case "/$lower_path/" in
    */backup/*|*/backups/*|*/snapshot/*|*/snapshots/*|*/dump/*|*/dumps/*|*/database-backup/*|*/db-backup/*|*/data-backup/*|*/business-backup/*|*/业务备份/*|*/数据备份/*|*/快照/*)
      block_release_source "$tracked_path" 'backup or snapshot directory path'
      ;;
  esac

  case "$lower_path" in
    *.dump|*.bak|*.backup|*.mysqldump|*.sqlite|*.sqlite3|*.db|*.rdb|*.sql.gz|*.sql.zip|*.sql.tgz|*.sql.tar|*.sql.7z)
      block_release_source "$tracked_path" 'database snapshot extension'
      ;;
  esac

  if ! is_flyway_migration_path "$tracked_path" && [[ "$lower_path" =~ (backup|snapshot|dump|export|备份|快照|导出).*(\.json|\.jsonl|\.csv|\.tsv|\.xlsx|\.xls|\.ods|\.sql|\.zip|\.gz|\.7z|\.tar|\.tgz)$ ]]; then
    block_release_source "$tracked_path" 'backup or export filename pattern'
  fi

  if [[ "$lower_path" =~ (^|/)(store[-_]?data|business[-_]?data|门店数据|业务数据).*(\.json|\.jsonl|\.csv|\.tsv|\.xlsx|\.xls|\.ods|\.sql|\.zip|\.gz|\.7z|\.tar|\.tgz)$ ]]; then
    block_release_source "$tracked_path" 'business data filename pattern'
  fi

done < <(git ls-files -z)

scan_tracked_content

if [[ "$failures" -ne 0 ]]; then
  if [[ "$blocked_source_count" -gt 0 ]]; then
    print_remediation_approval_checklist
  fi
  exit 1
fi

echo "Release source check passed: Flyway latest V${expected_flyway_latest}, exam/training sources, and tracked source data/key exclusions are complete."
