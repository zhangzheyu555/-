[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  # Production-like QA uses HTTPS.  Loopback HTTP is allowed only for the
  # isolated candidate started by Start-QAReleaseCandidate.ps1; it can never
  # point at a LAN or production endpoint.
  [ValidatePattern('^(https://|http://(127\.0\.0\.1|localhost)(:\d+)?$)')]
  [string]$QaBaseUrl,

  [Parameter(Mandatory = $true)]
  [string]$FixtureSpecPath,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$BootstrapBossTokenEnvironmentName = 'QA_BOOTSTRAP_BOSS_TOKEN',

  [string]$EvidenceRoot = '',

  [switch]$Apply,
  [switch]$AuthorizeQaFixtureWrite
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  $EvidenceRoot = Join-Path $projectRoot 'output\release-evidence'
}
Import-Module (Join-Path $PSScriptRoot 'QAReleaseCommon.psm1') -Force
$run = New-QAReleaseRun -EvidenceRoot $EvidenceRoot -Category 'qa/fixtures'
$reportPath = Join-Path $run.directory 'qa-fixture-receipt.json'
$result = [ordered]@{
  schema = 'ai-profit-os/qa-release-fixtures/v1'
  runId = $run.id
  generatedAt = [DateTime]::UtcNow.ToString('o')
  applyRequested = [bool]$Apply
  explicitAuthorization = [bool]$AuthorizeQaFixtureWrite
  productionDatabaseAccess = $false
  qaBaseUrlRecorded = $false
  fixture = $null
  resolvedBrand = $null
  createdStores = @()
  createdAccounts = @()
  operationLogReceipt = $null
  status = 'PLAN_ONLY'
}

function Get-ApiData {
  param([Parameter(Mandatory = $true)]$Payload)
  return if ($null -ne $Payload.PSObject.Properties['data']) { $Payload.data } else { $Payload }
}

function Get-RequestId {
  param([Parameter(Mandatory = $true)]$Headers)
  $value = [string]$Headers['X-Request-Id']
  if ([string]::IsNullOrWhiteSpace($value)) { $value = [string]$Headers['X-Request-ID'] }
  return $value
}

function Invoke-QAApi {
  param(
    [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
    [Parameter(Mandatory = $true)][string]$Path,
    [string]$Authorization = '',
    $Body = $null
  )

  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($Authorization)) { $headers.Authorization = $Authorization }
  $uri = $QaBaseUrl.TrimEnd('/') + $Path
  try {
    $args = @{ Uri = $uri; Method = $Method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 15; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
      $args.ContentType = 'application/json'
      $args.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
    }
    $response = Invoke-WebRequest @args
    $payload = $response.Content | ConvertFrom-Json -ErrorAction Stop
    return [pscustomobject]@{ status = [int]$response.StatusCode; requestId = Get-RequestId -Headers $response.Headers; data = Get-ApiData -Payload $payload }
  }
  catch [System.Net.WebException] {
    $response = $_.Exception.Response
    $status = if ($null -ne $response) { [int]$response.StatusCode } else { 0 }
    throw "QA API $Method $Path failed with HTTP $status."
  }
}

function Assert-E2ELabel {
  param([Parameter(Mandatory = $true)][string]$Value, [Parameter(Mandatory = $true)][string]$Name)
  if (-not $Value.StartsWith('E2E_', [StringComparison]::Ordinal)) {
    throw "$Name must use the E2E_ prefix."
  }
}

try {
  if (-not (Test-Path -LiteralPath $FixtureSpecPath -PathType Leaf)) { throw "QA fixture spec was not found: $FixtureSpecPath" }
  $spec = Get-Content -LiteralPath $FixtureSpecPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
  Assert-E2ELabel -Value ([string]$spec.batchId) -Name 'fixture batchId'
  $brandId = if ($null -ne $spec.PSObject.Properties['brandId']) { [int64]$spec.brandId } else { 0 }
  $brandCode = if ($null -ne $spec.PSObject.Properties['brandCode']) { [string]$spec.brandCode } else { '' }
  $hasBrandId = $brandId -gt 0
  $hasBrandCode = -not [string]::IsNullOrWhiteSpace($brandCode)
  if ($hasBrandId -eq $hasBrandCode) {
    throw 'QA fixture spec must specify exactly one approved brand selector: a positive baseline brandId, or an E2E_ brandCode restored by the QA baseline.'
  }
  if ($hasBrandCode) { Assert-E2ELabel -Value $brandCode -Name 'fixture brandCode' }
  $stores = @($spec.stores)
  $accounts = @($spec.accounts)
  if ($stores.Count -lt 2 -or $accounts.Count -ne 6) { throw 'QA fixture spec must define two stores and exactly six controlled role accounts.' }
  foreach ($store in $stores) {
    Assert-E2ELabel -Value ([string]$store.id) -Name 'store id'
    Assert-E2ELabel -Value ([string]$store.code) -Name 'store code'
    Assert-E2ELabel -Value ([string]$store.name) -Name 'store name'
  }
  foreach ($account in $accounts) {
    if (-not ([string]$account.username).StartsWith('e2e_', [StringComparison]::Ordinal)) { throw 'QA usernames must use lower-case e2e_ because account normalization lower-cases login names.' }
    Assert-E2ELabel -Value ([string]$account.displayName) -Name 'account displayName'
    if ([string]::IsNullOrWhiteSpace([string]$account.passwordEnvironmentName)) { throw 'Each QA account requires a password environment-variable name.' }
  }
  $result.fixture = [ordered]@{
    batchId = [string]$spec.batchId
    brandSelector = if ($hasBrandCode) { [ordered]@{ type = 'E2E_BRAND_CODE'; code = $brandCode } } else { [ordered]@{ type = 'APPROVED_BASELINE_BRAND_ID'; id = $brandId } }
    storeCount = $stores.Count
    accountCount = $accounts.Count
  }
  if (-not $Apply) {
    $result.status = 'PLAN_ONLY'
    $result.message = 'No QA endpoint or secret was accessed. Restore an approved sanitized QA baseline first, then re-run only after explicit authorization with -Apply -AuthorizeQaFixtureWrite.'
    return
  }
  if (-not $AuthorizeQaFixtureWrite) { throw 'QA fixture creation requires explicit authorization. Re-run only after approval with -Apply -AuthorizeQaFixtureWrite.' }

  $liveness = Invoke-QAApi -Method GET -Path '/api/health'
  if ([string]$liveness.data.status -ne 'UP') {
    throw 'The supplied endpoint did not pass the public liveness check.'
  }
  $token = Get-QAReleaseSecret -EnvironmentName $BootstrapBossTokenEnvironmentName
  $authorization = if ($token.StartsWith('Bearer ', [StringComparison]::OrdinalIgnoreCase)) { $token } else { 'Bearer ' + $token }
  $token = $null
  $diagnostics = Invoke-QAApi -Method GET -Path '/api/health/diagnostics' -Authorization $authorization
  if ([string]$diagnostics.data.environment -ne 'QA' -or [int]$diagnostics.data.databasePort -eq 3307 -or ([string]$diagnostics.data.databaseName -notmatch '(?i)(qa|test)')) {
    throw 'The supplied endpoint is not an isolated QA candidate according to authenticated diagnostics.'
  }
  $me = Invoke-QAApi -Method GET -Path '/api/auth/me' -Authorization $authorization
  if ([string]$me.data.role -ne 'BOSS') { throw 'QA fixture creation requires a QA BOSS bootstrap token.' }
  $brandResponse = Invoke-QAApi -Method GET -Path '/api/brands' -Authorization $authorization
  $brands = @($brandResponse.data)
  $matches = if ($hasBrandCode) {
    @($brands | Where-Object { [string]$_.code -eq $brandCode })
  } else {
    @($brands | Where-Object { [int64]$_.id -eq $brandId })
  }
  if ($matches.Count -ne 1) {
    throw 'The requested QA fixture brand could not be resolved exactly from the approved QA baseline.'
  }
  $resolvedBrand = $matches[0]
  $resolvedBrandId = [int64]$resolvedBrand.id
  $result.resolvedBrand = [ordered]@{ id = $resolvedBrandId; code = [string]$resolvedBrand.code }

  foreach ($store in $stores) {
    $body = [ordered]@{
      id = [string]$store.id; code = [string]$store.code; name = [string]$store.name
      brandId = $resolvedBrandId; area = [string]$store.area; manager = 'E2E_QA'
      openDate = [string]$store.openDate; status = 'ACTIVE'; note = ('E2E_ QA fixture ' + [string]$spec.batchId)
      regionCode = [string]$store.regionCode; supplyWarehouseId = $null
    }
    $created = Invoke-QAApi -Method POST -Path '/api/stores' -Authorization $authorization -Body $body
    $result.createdStores += [ordered]@{ id = $body.id; status = $created.status; requestId = $created.requestId }
  }

  foreach ($account in $accounts) {
    $password = Get-QAReleaseSecret -EnvironmentName ([string]$account.passwordEnvironmentName)
    $body = [ordered]@{
      username = [string]$account.username; displayName = [string]$account.displayName; role = [string]$account.role
      storeId = if ([string]::IsNullOrWhiteSpace([string]$account.storeId)) { $null } else { [string]$account.storeId }
      storeScope = @($account.storeScope); password = $password
    }
    $created = Invoke-QAApi -Method POST -Path '/api/users' -Authorization $authorization -Body $body
    $password = $null
    $result.createdAccounts += [ordered]@{ username = $body.username; id = [int64]$created.data.id; role = [string]$created.data.role; status = $created.status; requestId = $created.requestId }
  }

  $audit = Invoke-QAApi -Method GET -Path '/api/audit/logs?limit=500' -Authorization $authorization
  $targetIds = @($result.createdAccounts | ForEach-Object { [string]$_.id })
  $matched = @($audit.data | Where-Object { $targetIds -contains [string]$_.targetId } | ForEach-Object {
      [ordered]@{ id = $_.id; action = $_.action; targetId = $_.targetId; createdAt = $_.createdAt }
    })
  if ($matched.Count -lt $result.createdAccounts.Count) {
    throw 'QA fixture account writes did not produce a complete operation-log receipt.'
  }
  $result.operationLogReceipt = [ordered]@{ status = 'PASS'; requestId = $audit.requestId; matchedEntries = @($matched) }
  $result.status = 'PASS'
}
catch {
  $result.status = 'BLOCKED'
  $result.message = $_.Exception.Message
  throw
}
finally {
  Write-QAReleaseJson -Value $result -Path $reportPath
  Write-Host "Sanitized QA fixture receipt: $reportPath"
}
