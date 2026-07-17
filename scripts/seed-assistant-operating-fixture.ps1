[CmdletBinding()]
param(
  [string]$BackendBaseUrl = $(if ($env:E2E_API_URL) { $env:E2E_API_URL } else { 'http://127.0.0.1:18081' }),
  [string]$AuthorizationToken = $env:ASSISTANT_FIXTURE_TOKEN,
  [string]$BossUsername = $env:E2E_BOSS_USERNAME,
  [Security.SecureString]$BossPassword,
  [string[]]$Months = @('2026-05', '2026-06'),
  [switch]$Apply,
  [switch]$VerifyAi
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Convert-SecureStringToText {
  param([Parameter(Mandatory)][Security.SecureString]$Value)
  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    if ($pointer -ne [IntPtr]::Zero) {
      [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
  }
}

function Format-Money {
  param([Parameter(Mandatory)][decimal]$Value)
  return ([Math]::Round($Value, 2)).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
}

function Invoke-FixtureApi {
  param(
    [Parameter(Mandatory)][ValidateSet('GET', 'POST', 'PUT')] [string]$Method,
    [Parameter(Mandatory)][string]$Path,
    [AllowNull()]$Body = $null,
    [AllowNull()][string]$Token = $AuthorizationToken
  )
  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $headers.Authorization = "Bearer $Token"
  }
  $parameters = @{
    Method = $Method
    Uri = ($BackendBaseUrl.TrimEnd('/') + $Path)
    Headers = $headers
    TimeoutSec = 90
  }
  if ($null -ne $Body) {
    $parameters.ContentType = 'application/json; charset=utf-8'
    $parameters.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
  }
  $response = Invoke-RestMethod @parameters
  if ($null -ne $response.success -and -not $response.success) {
    $message = [string]$response.message
    if ([string]::IsNullOrWhiteSpace($message)) { $message = 'API returned failure.' }
    throw $message
  }
  return $response.data
}

function Assert-LocalFixtureTarget {
  $health = Invoke-FixtureApi -Method GET -Path '/api/health' -Token ''
  $environment = ([string]$health.environment).Trim().ToUpperInvariant()
  if ($environment -eq 'PRODUCTION') {
    throw 'Refuse to seed fixture operating data in PRODUCTION.'
  }
  if ([string]$health.databaseAccountScope -ne 'LOCAL_SCOPED') {
    throw 'Database account scope is not LOCAL_SCOPED; refuse to write fixture operating data.'
  }
  return $health
}

function Resolve-AuthorizationToken {
  if (-not [string]::IsNullOrWhiteSpace($AuthorizationToken)) {
    return $AuthorizationToken
  }
  if ([string]::IsNullOrWhiteSpace($BossUsername)) {
    throw 'Missing boss username. Provide -BossUsername/-BossPassword or ASSISTANT_FIXTURE_TOKEN.'
  }
  $passwordText = $null
  if ($BossPassword) {
    $passwordText = Convert-SecureStringToText $BossPassword
  } elseif ($env:E2E_BOSS_PASSWORD) {
    $passwordText = $env:E2E_BOSS_PASSWORD
  }
  if ([string]::IsNullOrWhiteSpace($passwordText)) {
    throw 'Missing boss password. Provide -BossPassword or E2E_BOSS_PASSWORD.'
  }
  $session = Invoke-FixtureApi -Method POST -Path '/api/auth/login' -Token '' -Body @{
    username = $BossUsername
    password = $passwordText
  }
  $script:AuthorizationToken = [string]$session.token
  if ([string]::IsNullOrWhiteSpace($script:AuthorizationToken)) {
    throw 'Login response did not include a token.'
  }
  return $script:AuthorizationToken
}

function New-FixtureEntry {
  param(
    [Parameter(Mandatory)]$Store,
    [Parameter(Mandatory)][string]$Month,
    [Parameter(Mandatory)][int]$StoreIndex
  )
  $monthOffset = if ($Month -eq '2026-06') { 1 } elseif ($Month -eq '2026-05') { 0 } else { 2 }
  $base = [decimal](185000 + (($StoreIndex * 7919) % 82000) + ($monthOffset * 9500))
  $refund = $base * [decimal]0.018
  $discount = $base * [decimal]0.026
  $income = $base - $refund - $discount
  $material = $income * ([decimal]0.285 + ([decimal](($StoreIndex % 5)) * [decimal]0.004))
  $packaging = $income * [decimal]0.052
  $loss = $income * ([decimal]0.010 + ([decimal](($StoreIndex % 4)) * [decimal]0.002))
  $costOther = $income * [decimal]0.011
  $rent = [decimal](18500 + (($StoreIndex * 127) % 5800))
  $labor = $income * ([decimal]0.152 + ([decimal](($StoreIndex % 3)) * [decimal]0.006))
  $utility = $income * [decimal]0.019
  $property = $income * [decimal]0.012
  $commission = $income * [decimal]0.038
  $promo = $income * ([decimal]0.017 + ([decimal](($StoreIndex % 4)) * [decimal]0.003))
  $repair = $income * [decimal]0.004
  $equip = $income * [decimal]0.006
  $expOther = $income * [decimal]0.018

  return @{
    storeId = [string]$Store.id
    month = $Month
    sales = Format-Money $base
    refund = Format-Money $refund
    discount = Format-Money $discount
    material = Format-Money $material
    packaging = Format-Money $packaging
    loss = Format-Money $loss
    costOther = Format-Money $costOther
    rent = Format-Money $rent
    labor = Format-Money $labor
    utility = Format-Money $utility
    property = Format-Money $property
    commission = Format-Money $commission
    promo = Format-Money $promo
    repair = Format-Money $repair
    equip = Format-Money $equip
    expOther = Format-Money $expOther
    note = 'local assistant ai verification fixture'
    brandId = [long]$Store.brandId
  }
}

if (-not $Apply) {
  throw 'This script writes MySQL operating fixture data. Add -Apply after confirming this is a local verification target.'
}

$healthInfo = Assert-LocalFixtureTarget
$AuthorizationToken = Resolve-AuthorizationToken
$stores = @(Invoke-FixtureApi -Method GET -Path '/api/stores')
$disabledStatusValues = @(
  (-join ([char[]](20572,29992))),
  (-join ([char[]](38381,24215)))
)
$activeStores = @($stores | Where-Object {
    $status = [string]$_.status
    -not [string]::IsNullOrWhiteSpace([string]$_.id) -and
    $_.brandId -and
    $status -notmatch '(?i)closed|disabled' -and
    $disabledStatusValues -notcontains $status
  })
if ($activeStores.Count -eq 0) {
  throw 'No active stores were returned for fixture seeding.'
}

$saved = 0
for ($storeIndex = 0; $storeIndex -lt $activeStores.Count; $storeIndex++) {
  foreach ($month in $Months) {
    if ($month -notmatch '^\d{4}-\d{2}$') {
      throw "Invalid month format: $month"
    }
    $entry = New-FixtureEntry -Store $activeStores[$storeIndex] -Month $month -StoreIndex ($storeIndex + 1)
    [void](Invoke-FixtureApi -Method PUT -Path '/api/finance/entries' -Body $entry)
    $saved++
  }
}

Write-Host ("Seeded assistant operating fixture: stores={0}, months={1}, upserts={2}." -f `
    $activeStores.Count, ($Months -join ','), $saved)
Write-Host ("Target environment={0}, database={1}:{2}/{3}." -f `
    $healthInfo.environment, '127.0.0.1', $healthInfo.databasePort, $healthInfo.databaseName)

if ($VerifyAi) {
  $verifyMonth = ($Months | Sort-Object -Descending | Select-Object -First 1)
  $snapshot = Invoke-FixtureApi -Method GET -Path ("/api/assistant/operating-snapshot?month=$verifyMonth")
  $analysisQuestion = -join ([char[]](32463,33829,21033,28070,21464,21270,30340,20027,35201,21407,22240,26159,20160,20040,65311))
  $answer = Invoke-FixtureApi -Method POST -Path '/api/assistant/chat' -Body @{
    message = "$verifyMonth $analysisQuestion"
    history = @()
    mode = 'AI'
    month = $verifyMonth
    snapshotId = $snapshot.snapshotId
  }
  Write-Host ("AI verification: selectedMode={0}, aiInvocation={1}, analysisType={2}, provider={3}, model={4}." -f `
      $answer.selectedMode, $answer.localData.aiInvocation, $answer.aiAnalysis.analysisType,
      $answer.aiAnalysis.provider, $answer.aiAnalysis.model)
  if ($answer.error) {
    Write-Host ("AI verification error: {0} {1}" -f $answer.error.code, $answer.error.message)
  } else {
    Write-Host ("AI summary: {0}" -f $answer.aiAnalysis.summary)
  }
}
