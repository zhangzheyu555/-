[CmdletBinding()]
param(
  [ValidateRange(0, 9999)]
  [int]$ExpectedFlywayLatest = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Text.UTF8Encoding]::new($false)

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$releaseCommon = Join-Path $PSScriptRoot 'ReleaseCandidateCommon.psm1'
if (-not (Test-Path -LiteralPath $releaseCommon -PathType Leaf)) {
  throw "Release candidate helper is missing: $releaseCommon"
}
Import-Module -Name $releaseCommon -Force -ErrorAction Stop
$failures = [System.Collections.Generic.List[string]]::new()
$blocks = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$cnBusinessBackup = -join [char[]](0x4E1A, 0x52A1, 0x5907, 0x4EFD)
$cnDataBackup = -join [char[]](0x6570, 0x636E, 0x5907, 0x4EFD)
$cnSnapshot = -join [char[]](0x5FEB, 0x7167)
$cnBackup = -join [char[]](0x5907, 0x4EFD)
$cnExport = -join [char[]](0x5BFC, 0x51FA)
$cnStoreData = -join [char[]](0x95E8, 0x5E97, 0x6570, 0x636E)
$cnBusinessData = -join [char[]](0x4E1A, 0x52A1, 0x6570, 0x636E)
$cnStore = -join [char[]](0x95E8, 0x5E97)
$cnOperationLog = -join [char[]](0x64CD, 0x4F5C, 0x65E5, 0x5FD7)
$cnProfit = -join [char[]](0x5229, 0x6DA6)
$cnSalary = -join [char[]](0x5DE5, 0x8D44)
$cnExpense = -join [char[]](0x62A5, 0x9500)
$cnTenant = -join [char[]](0x79DF, 0x6237)
$cnStoreId = $cnStore + 'ID'
$cnCreatedAt = -join [char[]](0x521B, 0x5EFA, 0x65F6, 0x95F4)
$backupDirectoryPattern = '(^|/)(backup|backups|snapshot|snapshots|dump|dumps|database-backup|db-backup|data-backup|business-backup|' + [regex]::Escape($cnBusinessBackup) + '|' + [regex]::Escape($cnDataBackup) + '|' + [regex]::Escape($cnSnapshot) + ')(/|$)'
$backupFilenamePattern = '(backup|snapshot|dump|export|' + [regex]::Escape($cnBackup) + '|' + [regex]::Escape($cnSnapshot) + '|' + [regex]::Escape($cnExport) + ').+\.(json|jsonl|csv|tsv|xlsx|xls|ods|sql|zip|gz|7z|tar|tgz)$'
$businessDataFilenamePattern = '(^|/)(store[-_]?data|business[-_]?data|' + [regex]::Escape($cnStoreData) + '|' + [regex]::Escape($cnBusinessData) + ').+\.(json|jsonl|csv|tsv|xlsx|xls|ods|sql|zip|gz|7z|tar|tgz)$'
$businessCollectionPattern = '(?i)("(?:stores|store_list|operation_log|profit_records|expense_records|salary_records|warehouse|inventory)"|' + [regex]::Escape($cnStore) + '|' + [regex]::Escape($cnOperationLog) + '|' + [regex]::Escape($cnProfit) + '|' + [regex]::Escape($cnSalary) + '|' + [regex]::Escape($cnExpense) + ')'
$recordIdentityPattern = '(?i)("(?:tenant_id|store_id|created_at|updated_at|operation_type)"|' + [regex]::Escape($cnTenant) + '|' + [regex]::Escape($cnStoreId) + '|' + [regex]::Escape($cnCreatedAt) + ')'

function Add-Failure {
  param([Parameter(Mandatory)][string]$Message)

  $failures.Add($Message)
  Write-Host $Message -ForegroundColor Red
}

function Add-Block {
  param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$Reason
  )

  $key = "$Path`0$Reason"
  if ($blocks.Add($key)) {
    Add-Failure "Release source blocked [$Reason]: $Path"
  }
}

function Get-RepositoryPath {
  param([Parameter(Mandatory)][string]$Path)

  return (Join-Path $projectRoot ($Path -replace '/', [IO.Path]::DirectorySeparatorChar))
}

function Test-TrackedFile {
  param([Parameter(Mandatory)][string]$Path)

  $fullPath = Get-RepositoryPath -Path $Path
  if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
    Add-Failure "Required release source is missing: $Path"
    return $false
  }

  & git -C $projectRoot ls-files --error-unmatch -- $Path | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Add-Failure "Required release source is not tracked by Git: $Path"
    return $false
  }
  return $true
}

function Test-FlywayMigrationPath {
  param([Parameter(Mandatory)][string]$Path)

  return $Path -match '^backend/src/main/resources/db/migration(?:-h2)?/'
}

function Test-FlywayLatestVersion {
  param(
    [Parameter(Mandatory)][string]$RelativeDirectory,
    [Parameter(Mandatory)][string]$Label
  )

  if ($ExpectedFlywayLatest -lt 1) {
    Add-Failure "Cannot validate $Label Flyway latest version because no synchronized source version was resolved."
    return $null
  }

  $directory = Get-RepositoryPath -Path $RelativeDirectory
  $migrations = @(
    Get-ChildItem -LiteralPath $directory -File -Filter 'V*__*.sql' |
      Where-Object { $_.Name -match '^V(\d+)__.+\.sql$' }
  )
  if ($migrations.Count -eq 0) {
    Add-Failure "$Label Flyway migrations are missing: $RelativeDirectory"
    return $null
  }

  $latest = [int](($migrations | ForEach-Object {
    [int]([regex]::Match($_.Name, '^V(\d+)__').Groups[1].Value)
  } | Measure-Object -Maximum).Maximum)
  if ($latest -ne $ExpectedFlywayLatest) {
    Add-Failure "$Label Flyway latest source version must be V$ExpectedFlywayLatest, found V$latest."
  }

  $expected = @($migrations | Where-Object { $_.Name -match "^V$ExpectedFlywayLatest`__.+\.sql$" })
  if ($expected.Count -ne 1) {
    Add-Failure "Expected exactly one $Label V$ExpectedFlywayLatest Flyway migration, found $($expected.Count)."
    return $null
  }
  Test-TrackedFile -Path ("$RelativeDirectory/" + $expected[0].Name) | Out-Null
  return [pscustomobject]@{
    version = $latest
    fileName = $expected[0].Name
  }
}

function Test-PlaceholderValue {
  param([Parameter(Mandatory)][string]$Value)

  $normalized = $Value.Trim().Trim([char[]]@('"', "'"))
  if ([string]::IsNullOrWhiteSpace($normalized)) { return $true }
  return $normalized -match '^(\$\{[^}]+\}|\$\{\{.+\}\}|\$[A-Za-z_][A-Za-z0-9_:.-]*|\[.*|<[^>]+>|@[A-Za-z0-9_.-]+@|\(.*\)|(get|read|convert|new)-[A-Za-z0-9_-]+.*|example|sample|placeholder|changeme|replace[-_]?.*|none|null)$'
}

function Get-InspectableText {
  param([Parameter(Mandatory)][string]$FullPath)

  $item = Get-Item -LiteralPath $FullPath -ErrorAction Stop
  if ($item.Length -gt 16MB) { return $null }

  $bytes = [IO.File]::ReadAllBytes($FullPath)
  if ($bytes.Length -eq 0) { return '' }
  $probeLength = [Math]::Min($bytes.Length, 4096)
  for ($index = 0; $index -lt $probeLength; $index++) {
    if ($bytes[$index] -eq 0) { return $null }
  }
  return [Text.Encoding]::UTF8.GetString($bytes)
}

function Test-SensitiveContent {
  param(
    [Parameter(Mandatory)][string]$TrackedPath,
    [Parameter(Mandatory)][string]$LowerPath
  )

  $fullPath = Get-RepositoryPath -Path $TrackedPath
  if ($LowerPath -match '\.(jpg|jpeg|png|webp|gif|ico|woff|woff2|ttf|otf|pdf|jar|class)$') {
    return
  }
  $text = Get-InspectableText -FullPath $fullPath
  if ($null -eq $text) {
    if ($LowerPath -match '\.(json|jsonl|csv|tsv)$') {
      Add-Block -Path $TrackedPath -Reason 'large or binary structured data requires data-owner approval'
    }
    return
  }

  if ($text -match '-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----') {
    Add-Block -Path $TrackedPath -Reason 'private key material in content'
  }
  if ($text -match '(?i)(^|[^A-Za-z0-9_])(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|glpat-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35})([^A-Za-z0-9_-]|$)') {
    Add-Block -Path $TrackedPath -Reason 'provider credential pattern in content'
  }

  if ($LowerPath -match '\.(env|yml|yaml|properties|conf|ini|ps1|sh)$') {
    $sensitiveAssignments = [regex]::Matches(
      $text,
      '(?im)^\s*(?:export\s+)?(?:DEEPSEEK_API_KEY|OPENAI_API_KEY|EMPLOYEE_ASSISTANT_API_TOKEN|MYSQL_PASSWORD|DB_PASSWORD|DATABASE_URL|AWS_SECRET_ACCESS_KEY|AWS_ACCESS_KEY_ID|PASSWORD|SECRET|TOKEN|PRIVATE_KEY)\s*[:=]\s*(?<value>[^\r\n#]+)'
    )
    foreach ($assignment in $sensitiveAssignments) {
      if (-not (Test-PlaceholderValue -Value $assignment.Groups['value'].Value)) {
        Add-Block -Path $TrackedPath -Reason 'sensitive configuration value in content'
        break
      }
    }
  }

  if ($LowerPath -like '*.sql' -and -not (Test-FlywayMigrationPath -Path $TrackedPath)) {
    $hasDumpHeader = $text -match '(?im)(^|\s)--\s*(mysql|mariadb) dump|^/\*![0-9]{5}\s+.*(?:database|table)'
    $hasBusinessInsert = $text -match '(?is)(?:insert|replace)\s+into\s+[`\[]?(?:tenant|store|operation_log|profit|expense|salary|inventory|warehouse|requisition|inspection|employee)[A-Za-z0-9_]*[`\]]?.*\bvalues\b'
    if ($hasDumpHeader -or $hasBusinessInsert) {
      Add-Block -Path $TrackedPath -Reason 'database dump or business data insert signature in content'
    }
  }

  if ($LowerPath -match '\.(json|jsonl|csv|tsv)$') {
    $hasBusinessCollection = $text -match $businessCollectionPattern
    $hasRecordIdentity = $text -match $recordIdentityPattern
    if ($hasBusinessCollection -and $hasRecordIdentity) {
      Add-Block -Path $TrackedPath -Reason 'business data export signature in content'
    }
  }
}

function Write-RemediationApprovalChecklist {
  @(
    '',
    'Release-source remediation approval checklist (this check did not delete data, untrack files, or rewrite history):',
    '  [ ] Data owner classifies each blocked path and preserves any required encrypted repository-external copy.',
    '  [ ] Repository owner approves the exact quarantine and git-untrack change; this gate never runs git rm.',
    '  [ ] Security owner approves any Git-history cleanup scope before a separate, controlled rewrite is attempted.',
    '  [ ] Security owner records credential rotation for every detected key, token, password, or connection secret.',
    '  [ ] Release owner reruns this gate from a clean reviewed commit and records all approval references.'
  ) | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
}

Test-TrackedFile -Path 'scripts/ReleaseCandidateCommon.psm1' | Out-Null
$synchronizedFlyway = $null
try {
  $synchronizedFlyway = Get-ReleaseFlywaySource -ProjectRoot $projectRoot
  if ($ExpectedFlywayLatest -gt 0 -and $ExpectedFlywayLatest -ne $synchronizedFlyway.version) {
    Add-Failure "Requested Flyway V$ExpectedFlywayLatest does not match the synchronized source latest V$($synchronizedFlyway.version)."
  }
  $ExpectedFlywayLatest = $synchronizedFlyway.version
}
catch {
  Add-Failure $_.Exception.Message
}

$requiredExamAndTrainingFiles = @(
  'backend/src/main/resources/db/migration/V28__exam_training_seed_data.sql',
  'backend/src/main/java/com/storeprofit/system/operations/ExamCenterController.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamCenterModels.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamCenterRepository.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamCenterService.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamLearningController.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamLearningModels.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamLearningRepository.java',
  'backend/src/main/java/com/storeprofit/system/operations/ExamLearningService.java',
  'frontend-vue/src/api/exams.ts',
  'frontend-vue/src/pages/ExamCenterPage.vue'
)
foreach ($path in $requiredExamAndTrainingFiles) {
  Test-TrackedFile -Path $path | Out-Null
}

$trainingImageDirectory = Get-RepositoryPath -Path 'backend/src/main/resources/static/train-img'
$expectedTrainingImageCount = 119
if (-not (Test-Path -LiteralPath $trainingImageDirectory -PathType Container)) {
  Add-Failure 'Runtime training image directory is missing: backend/src/main/resources/static/train-img'
}
else {
  $trainingImages = @(Get-ChildItem -LiteralPath $trainingImageDirectory -File -Recurse)
  if ($trainingImages.Count -ne $expectedTrainingImageCount) {
    Add-Failure "Expected $expectedTrainingImageCount runtime training images, found $($trainingImages.Count)."
  }
  foreach ($image in $trainingImages) {
    $relativeImage = $image.FullName.Substring($projectRoot.Length + 1).Replace([IO.Path]::DirectorySeparatorChar, '/')
    Test-TrackedFile -Path $relativeImage | Out-Null
  }
}

$mysqlFlyway = Test-FlywayLatestVersion -RelativeDirectory 'backend/src/main/resources/db/migration' -Label 'MySQL'
$h2Flyway = Test-FlywayLatestVersion -RelativeDirectory 'backend/src/main/resources/db/migration-h2' -Label 'H2'
if ($null -ne $mysqlFlyway -and $null -ne $h2Flyway -and -not $mysqlFlyway.fileName.Equals($h2Flyway.fileName, [StringComparison]::Ordinal)) {
  Add-Failure "MySQL and H2 Flyway latest migration names differ: '$($mysqlFlyway.fileName)' vs '$($h2Flyway.fileName)'."
}

$trackedPaths = @(& git -C $projectRoot -c core.quotepath=false ls-files)
if ($LASTEXITCODE -ne 0) {
  throw 'Unable to enumerate tracked release source files.'
}
$scannedTrackedFileCount = 0
foreach ($trackedPath in $trackedPaths) {
  $scannedTrackedFileCount++
  $lowerPath = $trackedPath.ToLowerInvariant()

  if ($lowerPath -match '(^|/)\.env(?:$|\.)' -and $lowerPath -notmatch '\.(example|sample|template)$') {
    Add-Block -Path $trackedPath -Reason 'environment file path'
  }
  if ($lowerPath -match '(\.pem|\.key|\.p12|\.pfx|\.jks|\.keystore|\.kdb)$|(^|/)(id_rsa|id_ed25519)$') {
    Add-Block -Path $trackedPath -Reason 'private-key or credential-container extension'
  }
  if ($lowerPath -match $backupDirectoryPattern) {
    Add-Block -Path $trackedPath -Reason 'backup or snapshot directory path'
  }
  if ($lowerPath -match '(\.dump|\.bak|\.backup|\.mysqldump|\.sqlite|\.sqlite3|\.rdb|\.sql\.(gz|zip|tgz|tar|7z))$') {
    Add-Block -Path $trackedPath -Reason 'database snapshot extension'
  }
  if (-not (Test-FlywayMigrationPath -Path $trackedPath) -and $lowerPath -match $backupFilenamePattern) {
    Add-Block -Path $trackedPath -Reason 'backup or export filename pattern'
  }
  if ($lowerPath -match $businessDataFilenamePattern) {
    Add-Block -Path $trackedPath -Reason 'business data filename pattern'
  }

  Test-SensitiveContent -TrackedPath $trackedPath -LowerPath $lowerPath
}

Write-Host "Release source scan completed: $scannedTrackedFileCount tracked files, $($blocks.Count) blocked path or content finding(s)."

if ($blocks.Count -gt 0) {
  Write-RemediationApprovalChecklist
}
if ($failures.Count -gt 0) {
  exit 1
}

Write-Host "Release source check passed: Flyway latest V$ExpectedFlywayLatest, required sources, and tracked backup/snapshot/key exclusions are complete." -ForegroundColor Green
