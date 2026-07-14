#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

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

for version in 30 31 32 33 34 35 36; do
  mapfile -t migrations < <(
    find backend/src/main/resources/db/migration -maxdepth 1 -type f \
      -name "V${version}__*.sql" -print | sort
  )
  if [[ "${#migrations[@]}" -ne 1 ]]; then
    echo "Expected exactly one V${version} Flyway migration, found ${#migrations[@]}." >&2
    failures=1
    continue
  fi
  require_tracked_file "${migrations[0]}"
done

while IFS= read -r -d '' tracked_path; do
  lower_path="${tracked_path,,}"
  case "$lower_path" in
    store-data-backup.json|*/store-data-backup.json|*.dump|*.bak|*.backup|*.mysqldump|*.sql.gz|*.sql.zip|*.sql.tgz|*.sql.tar)
      echo "Business backup must not be tracked by Git: $tracked_path" >&2
      failures=1
      ;;
    */backup/*|backup/*|*/backups/*|backups/*|*/database-backup/*|database-backup/*|*/db-backup/*|db-backup/*)
      echo "Backup directory content must not be tracked by Git: $tracked_path" >&2
      failures=1
      ;;
  esac

  if [[ "$lower_path" =~ (^|/)(database|mysql|store-data)[_-]?backup[^/]*\.(json|sql|zip|gz|7z|tar)$ ]]; then
    echo "Business backup must not be tracked by Git: $tracked_path" >&2
    failures=1
  fi
done < <(git ls-files -z)

if [[ "$failures" -ne 0 ]]; then
  exit 1
fi

echo "Release source check passed: V30-V36, exam/training sources and backup exclusions are complete."
