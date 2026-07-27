#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

readonly legacy_flyway_maximum_major_version=105

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
  local migration_path file_name major timestamp version key
  local latest_key='' latest_version='' latest_file=''
  local seen_versions='|'

  while IFS= read -r -d '' migration_path; do
    file_name="${migration_path##*/}"
    if [[ "$file_name" =~ ^V([1-9][0-9]*)_((20[0-9]{15}))__([a-zA-Z0-9][a-zA-Z0-9_]*)\.sql$ ]]; then
      major="${BASH_REMATCH[1]}"
      timestamp="${BASH_REMATCH[2]}"
      if (( 10#$major <= legacy_flyway_maximum_major_version )); then
        echo "${label} timestamped Flyway migration must use a major version greater than V${legacy_flyway_maximum_major_version}: ${file_name}" >&2
        return 1
      fi
      version="${major}.${timestamp}"
      key="$(printf '%010d.%s' "$((10#$major))" "$timestamp")"
    elif [[ "$file_name" =~ ^V([0-9]+)__([a-zA-Z0-9][a-zA-Z0-9_]*)\.sql$ ]]; then
      major="${BASH_REMATCH[1]}"
      if (( 10#$major > legacy_flyway_maximum_major_version )); then
        echo "${label} Flyway migration must use V<major>_<yyyyMMddHHmmssSSS>__<description>.sql: ${file_name}" >&2
        return 1
      fi
      version="$major"
      key="$(printf '%010d.%017d' "$((10#$major))" 0)"
    else
      echo "${label} Flyway migration filename is invalid: ${file_name}" >&2
      return 1
    fi

    if [[ "$seen_versions" == *"|${version}|"* ]]; then
      echo "${label} Flyway version V${version} is duplicated: ${file_name}." >&2
      return 1
    fi
    seen_versions+="${version}|"
    if [[ -z "$latest_key" || "$key" > "$latest_key" ]]; then
      latest_key="$key"
      latest_version="$version"
      latest_file="$file_name"
    fi
  done < <(find "$migration_dir" -maxdepth 1 -type f -name 'V*__*.sql' -print0)

  if [[ -z "$latest_version" ]]; then
    echo "${label} Flyway migrations are missing: ${migration_dir}" >&2
    return 1
  fi
  require_tracked_file "$migration_dir/$latest_file"
  printf '%s|%s\n' "$latest_version" "$latest_file"
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

mysql_flyway_info="$(verify_latest_flyway backend/src/main/resources/db/migration MySQL)"
h2_flyway_info="$(verify_latest_flyway backend/src/main/resources/db/migration-h2 H2)"
IFS='|' read -r mysql_flyway_version mysql_flyway_file <<<"$mysql_flyway_info"
IFS='|' read -r h2_flyway_version h2_flyway_file <<<"$h2_flyway_info"
if [[ "$mysql_flyway_version" != "$h2_flyway_version" || "$mysql_flyway_file" != "$h2_flyway_file" ]]; then
  echo "MySQL and H2 Flyway latest migrations are not synchronized: ${mysql_flyway_file} vs ${h2_flyway_file}." >&2
  failures=1
fi

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

echo "Release source check passed: MySQL and H2 Flyway latest V${mysql_flyway_version}, exam/training sources, and tracked source data/key exclusions are complete."
