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

  [Parameter(Mandatory = $true)]
  [string]$BaselineManifestPath,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$PasswordEnvironmentName = 'QA_MYSQL_PASSWORD',

  [string]$EvidenceRoot = '',
  [switch]$Apply,
  [switch]$AuthorizeQaBaselineRestore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) { $EvidenceRoot = Join-Path $projectRoot 'output\release-evidence' }
Import-Module (Join-Path $PSScriptRoot 'QAReleaseCommon.psm1') -Force
$run = New-QAReleaseRun -EvidenceRoot $EvidenceRoot -Category 'qa/baseline'
$reportPath = Join-Path $run.directory 'qa-baseline-restore-receipt.json'
$result = [ordered]@{
  schema = 'ai-profit-os/qa-release-baseline-restore/v1'
  runId = $run.id
  generatedAt = [DateTime]::UtcNow.ToString('o')
  environment = 'QA'
  database = [ordered]@{ host = $MySqlHost; port = $MySqlPort; name = $MySqlDatabase; usernameRecorded = $false }
  applyRequested = [bool]$Apply
  explicitAuthorization = [bool]$AuthorizeQaBaselineRestore
  productionDatabaseAccess = $false
  baseline = $null
  validation = [ordered]@{ emptyDatabaseConfirmed = $false; prohibitedStatementCategories = @(); postRestore = $null }
  operationLogReceipt = [ordered]@{
    status = 'NOT_APPLICABLE_SQL_RESTORE'
    reason = 'The baseline is restored before the application starts. This receipt records only the reviewed hash; subsequent fixture API writes must provide operation_log evidence.'
  }
  status = 'PLAN_ONLY'
}

function Add-InputError {
  param([System.Collections.Generic.List[string]]$Errors, [string]$Message)
  $Errors.Add($Message) | Out-Null
}

function Get-ManifestValue {
  param($Manifest, [string]$Name)
  if ($null -eq $Manifest.PSObject.Properties[$Name]) { return '' }
  return [string]$Manifest.$Name
}

function Test-QABaselineInput {
  param([Parameter(Mandatory = $true)]$Manifest)

  $errors = New-Object 'System.Collections.Generic.List[string]'
  $schema = Get-ManifestValue $Manifest 'schema'
  $baselineId = Get-ManifestValue $Manifest 'baselineId'
  $sqlPath = Get-ManifestValue $Manifest 'sqlPath'
  $expectedSha = Get-ManifestValue $Manifest 'sha256'
  $attestation = Get-ManifestValue $Manifest 'sanitizationAttestation'
  $fixtureBrandCode = Get-ManifestValue $Manifest 'fixtureBrandCode'
  if ($schema -ne 'ai-profit-os/qa-sanitized-baseline/v1') { Add-InputError $errors 'Baseline manifest schema is not supported.' }
  if ($baselineId -notmatch '^QA_BASELINE_[A-Z0-9_]+$') { Add-InputError $errors 'Baseline manifest baselineId must use the QA_BASELINE_ prefix.' }
  if ([string]::IsNullOrWhiteSpace($sqlPath) -or -not $sqlPath.EndsWith('.sql', [StringComparison]::OrdinalIgnoreCase)) { Add-InputError $errors 'Baseline manifest must reference one approved .sql input file.' }
  if ($expectedSha -notmatch '^[A-Fa-f0-9]{64}$') { Add-InputError $errors 'Baseline manifest requires an approved SHA-256.' }
  if ([string]::IsNullOrWhiteSpace($attestation) -or $attestation -match 'REQUIRED') { Add-InputError $errors 'Baseline manifest requires a non-placeholder sanitization attestation.' }
  if ($fixtureBrandCode -notmatch '^E2E_[A-Z0-9_]+$') { Add-InputError $errors 'Baseline manifest requires a controlled E2E_ fixtureBrandCode.' }
  $requiredTables = @($Manifest.expected.requiredTables)
  foreach ($requiredTable in @('auth_user', 'brand', 'flyway_schema_history')) {
    if ($requiredTable -notin $requiredTables) { Add-InputError $errors "Baseline manifest must require $requiredTable." }
  }
  if ([int]$Manifest.expected.minimumActiveBosses -lt 1) { Add-InputError $errors 'Baseline manifest requires at least one enabled QA BOSS account.' }
  if ([int]$Manifest.expected.minimumBrands -lt 1) { Add-InputError $errors 'Baseline manifest requires at least one QA brand.' }

  $actualSha = $null
  $prohibited = @()
  if ($errors.Count -eq 0 -and (Test-Path -LiteralPath $sqlPath -PathType Leaf)) {
    $item = Get-Item -LiteralPath $sqlPath
    if ($item.Length -gt 134217728) {
      Add-InputError $errors 'Approved baseline SQL exceeds the 128 MiB QA safety limit.'
    } else {
      $actualSha = (Get-FileHash -LiteralPath $sqlPath -Algorithm SHA256).Hash
      if (-not $actualSha.Equals($expectedSha, [StringComparison]::OrdinalIgnoreCase)) { Add-InputError $errors 'Approved baseline SQL SHA-256 does not match the reviewed manifest.' }
      $sql = [IO.File]::ReadAllText($sqlPath, [Text.Encoding]::UTF8)
      $patterns = [ordered]@{
        DATABASE_SCOPE = '(?im)(?:^|;)\s*(?:CREATE|DROP|ALTER)\s+(?:DATABASE|SCHEMA)\b|(?:^|;)\s*USE\s+'
        PRIVILEGE_OR_IDENTITY = '(?im)(?:^|;)\s*(?:GRANT|REVOKE|CREATE\s+USER|ALTER\s+USER|DROP\s+USER)\b'
        GLOBAL_OR_FILE_IO = '(?im)(?:^|;)\s*(?:SET\s+(?:@@)?GLOBAL|FLUSH\b|SOURCE\b|LOAD\s+DATA\b|SELECT\b[\s\S]{0,256}\bINTO\s+(?:OUTFILE|DUMPFILE))'
        DESTRUCTIVE_DML_OR_DDL = '(?im)(?:^|;)\s*(?:DROP\s+TABLE|TRUNCATE\s+TABLE|DELETE\s+FROM|UPDATE\s+|REPLACE\s+INTO)\b'
        DEFINER = '(?i)\bDEFINER\s*='
        MYSQL_EXECUTABLE_COMMENT = '(?is)/\*!'
        SYSTEM_SCHEMA_REFERENCE = '(?i)\b(?:mysql|information_schema|performance_schema|sys)\s*\.'
      }
      foreach ($entry in $patterns.GetEnumerator()) {
        if ([regex]::IsMatch($sql, $entry.Value)) { $prohibited += $entry.Key }
      }
      if ($prohibited.Count -gt 0) { Add-InputError $errors 'Approved baseline SQL contains prohibited statements for an empty QA database import.' }
    }
  } elseif ($errors.Count -eq 0) {
    Add-InputError $errors 'Approved baseline SQL input file is unavailable.'
  }

  return [pscustomobject]@{
    errors = @($errors); baselineId = $baselineId; sqlPath = $sqlPath; expectedSha256 = $expectedSha; actualSha256 = $actualSha
    fixtureBrandCode = $fixtureBrandCode; requiredTables = @($requiredTables); minimumActiveBosses = [int]$Manifest.expected.minimumActiveBosses
    minimumBrands = [int]$Manifest.expected.minimumBrands; prohibitedStatementCategories = @($prohibited)
  }
}

try {
  Assert-QAReleaseDatabaseTarget -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlDatabase $MySqlDatabase -MySqlUsername $MySqlUsername
  if (-not (Test-Path -LiteralPath $BaselineManifestPath -PathType Leaf)) {
    if ($Apply) { throw 'Approved QA baseline manifest is unavailable.' }
    $result.status = 'PLAN_ONLY_INPUT_REQUIRED'
    $result.message = 'No database connection or secret access occurred. Supply an approved sanitized QA baseline manifest before requesting a restore.'
    return
  }
  $manifest = Get-Content -LiteralPath $BaselineManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
  $check = Test-QABaselineInput -Manifest $manifest
  $result.baseline = [ordered]@{
    baselineId = $check.baselineId; expectedSha256 = $check.expectedSha256; actualSha256 = $check.actualSha256
    fixtureBrandCode = $check.fixtureBrandCode; requiredTables = @($check.requiredTables)
    minimumActiveBosses = $check.minimumActiveBosses; minimumBrands = $check.minimumBrands
  }
  $result.validation.prohibitedStatementCategories = @($check.prohibitedStatementCategories)
  if ($check.errors.Count -gt 0) {
    if ($Apply) { throw ('Approved QA baseline input is not ready: ' + ($check.errors -join ' ')) }
    $result.status = 'PLAN_ONLY_INPUT_INCOMPLETE'
    $result.message = 'No database connection or secret access occurred. ' + ($check.errors -join ' ')
    return
  }
  if (-not $Apply) {
    $result.status = 'PLAN_ONLY'
    $result.message = 'No database connection or secret access occurred. Re-run only after independent approval with -Apply -AuthorizeQaBaselineRestore.'
    return
  }
  if (-not $AuthorizeQaBaselineRestore) { throw 'QA baseline restore requires independent authorization. Re-run only after approval with -Apply -AuthorizeQaBaselineRestore.' }

  $password = Get-QAReleaseSecret -EnvironmentName $PasswordEnvironmentName
  $existingTables = Get-QATableNames -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase
  if ($existingTables.Count -ne 0) { throw 'QA baseline restore only accepts an empty database immediately after an authorized QA reset.' }
  $result.validation.emptyDatabaseConfirmed = $true
  Invoke-QAMysqlScriptFile -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase -ScriptPath $check.sqlPath
  $restoredTables = Get-QATableNames -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase
  $missing = @($check.requiredTables | Where-Object { $_ -notin $restoredTables })
  if ($missing.Count -gt 0) { throw 'Approved QA baseline did not restore its required table contract.' }
  $brandCount = [int](Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase -Sql 'select count(*) from brand').Trim()
  $bossCount = [int](Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase -Sql "select count(*) from auth_user where role = 'BOSS' and enabled = 1").Trim()
  $escapedBrandCode = $check.fixtureBrandCode.Replace("'", "''")
  $fixtureBrandCount = [int](Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase -Sql "select count(*) from brand where code = '$escapedBrandCode'").Trim()
  if ($brandCount -lt $check.minimumBrands -or $bossCount -lt $check.minimumActiveBosses -or $fixtureBrandCount -ne 1) { throw 'Approved QA baseline failed its brand or QA BOSS contract.' }
  $result.validation.postRestore = [ordered]@{
    flywayVersion = Get-QAFlywayVersion -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $password -Database $MySqlDatabase
    requiredTablesPresent = $true; brandCount = $brandCount; activeBossCount = $bossCount; fixtureBrandCount = $fixtureBrandCount
  }
  $result.status = 'BASELINE_RESTORED_CANDIDATE_START_REQUIRED'
  $result.message = 'Approved sanitized QA baseline was restored into an empty QA database. Start the isolated candidate next so Flyway can validate and migrate it.'
}
catch {
  $result.status = 'BLOCKED'
  $result.message = $_.Exception.Message
  throw
}
finally {
  Write-QAReleaseJson -Value $result -Path $reportPath
  Write-Host "Sanitized QA baseline restore receipt: $reportPath"
}
