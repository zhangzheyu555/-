[CmdletBinding()]
param(
  [ValidatePattern('^(127\.0\.0\.1|localhost|::1)$')]
  [string]$MySqlHost = '127.0.0.1',

  [ValidateRange(1024, 65535)]
  [int]$MySqlPort = 3308,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^(?i)ai_profit_(qa|test)_[a-z0-9_]+$')]
  [string]$MySqlDatabase,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$MySqlUsername,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$PasswordEnvironmentName = 'QA_MYSQL_PASSWORD',

  [string]$EvidenceRoot = '',

  [switch]$Apply,
  [switch]$AuthorizeQaReset
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  $EvidenceRoot = Join-Path $projectRoot 'output\release-evidence'
}
Import-Module (Join-Path $PSScriptRoot 'QAReleaseCommon.psm1') -Force
$run = New-QAReleaseRun -EvidenceRoot $EvidenceRoot -Category 'qa/reset'
$reportPath = Join-Path $run.directory 'qa-reset-receipt.json'
$result = [ordered]@{
  schema = 'ai-profit-os/qa-release-reset/v1'
  runId = $run.id
  generatedAt = [DateTime]::UtcNow.ToString('o')
  environment = 'QA'
  database = [ordered]@{ host = $MySqlHost; port = $MySqlPort; name = $MySqlDatabase; usernameRecorded = $false }
  applyRequested = [bool]$Apply
  explicitAuthorization = [bool]$AuthorizeQaReset
  productionDatabaseAccess = $false
  backup = $null
  before = $null
  after = $null
  operationLogReceipt = [ordered]@{
    status = 'NOT_APPLICABLE_DATABASE_RESET'
    reason = 'A database reset cannot preserve application operation_log rows. The subsequent fixture script must record its own API operation-log receipt.'
  }
  status = 'PLAN_ONLY'
  cleanup = [ordered]@{ databaseReset = $false; result = 'NOT_RUN' }
}

try {
  Assert-QAReleaseDatabaseTarget -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlDatabase $MySqlDatabase -MySqlUsername $MySqlUsername
  if (-not $Apply) {
    $result.status = 'PLAN_ONLY'
    $result.message = 'No database connection was attempted. Re-run only after explicit authorization with -Apply -AuthorizeQaReset.'
    return
  }
  if (-not $AuthorizeQaReset) {
    throw 'QA whole-database reset requires explicit authorization. Re-run only after the release owner approves -Apply -AuthorizeQaReset.'
  }

  $password = Get-QAReleaseSecret -EnvironmentName $PasswordEnvironmentName
  $beforeVersion = Get-QAFlywayVersion -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase
  $beforeCounts = Get-QATableCounts -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase
  $backupDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) ('AI-Profit-OS\qa-backups\' + $run.id)
  New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
  $backupPath = Join-Path $backupDirectory ($MySqlDatabase + '-before-reset.sql')
  Invoke-QAMysqldump -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase -ResultFile $backupPath
  $result.backup = [ordered]@{ path = $backupPath; sha256 = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash; bytes = (Get-Item -LiteralPath $backupPath).Length }
  $result.before = [ordered]@{ flywayVersion = $beforeVersion; tableCounts = @($beforeCounts) }

  Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Sql "drop database ``$MySqlDatabase``" | Out-Null
  Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Sql "create database ``$MySqlDatabase`` character set utf8mb4 collate utf8mb4_unicode_ci" | Out-Null
  $result.after = [ordered]@{ flywayVersion = $null; tableCounts = @(); state = 'EMPTY_DATABASE_CREATED_MIGRATION_REQUIRED' }
  $result.status = 'RESET_COMPLETE_MIGRATION_REQUIRED'
  $result.cleanup = [ordered]@{ databaseReset = $true; result = 'SUCCESS'; nextStep = 'Run Start-QAReleaseCandidate.ps1 with separate explicit QA candidate-start authorization.' }
}
catch {
  $result.status = 'BLOCKED'
  $result.message = $_.Exception.Message
  throw
}
finally {
  Write-QAReleaseJson -Value $result -Path $reportPath
  Write-Host "Sanitized QA reset receipt: $reportPath"
}
