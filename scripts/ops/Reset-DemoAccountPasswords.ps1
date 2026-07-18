[CmdletBinding()]
param(
  [Alias('API_BASE_URL')]
  [string]$ApiBaseUrl = $env:API_BASE_URL,

  [Alias('BOSS_TOKEN')]
  [string]$BossToken = $env:BOSS_TOKEN,

  [Alias('ACCOUNTS_CSV')]
  [string]$AccountsCsv = $env:ACCOUNTS_CSV,

  [string]$OutputDirectory = 'output/secure',
  [string]$CurrentBossPassword = $env:CURRENT_BOSS_PASSWORD,
  [switch]$DryRun,
  [switch]$SkipCertificateCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-Text {
  param([string]$Value, [string]$Name)
  if ([string]::IsNullOrWhiteSpace($Value)) {
    throw "$Name is required."
  }
  return $Value.Trim()
}

function New-RandomPassword {
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^*-_=+'
  $bytes = New-Object byte[] 24
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($bytes)
  } finally {
    $rng.Dispose()
  }
  $chars = foreach ($byte in $bytes) {
    $alphabet[$byte % $alphabet.Length]
  }
  -join $chars
}

function Invoke-Api {
  param(
    [ValidateSet('GET', 'POST')]
    [string]$Method,
    [string]$Path,
    [object]$Body = $null
  )
  $headers = @{ Authorization = "Bearer $BossToken" }
  $uri = "$ApiBaseUrl$Path"
  if ($null -eq $Body) {
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -TimeoutSec 30
  }
  $json = $Body | ConvertTo-Json -Depth 10 -Compress
  return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $json -TimeoutSec 30
}

function Enable-InsecureCertificateCheckIfRequested {
  [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
  if (-not $SkipCertificateCheck) {
    return
  }
  Write-Warning 'TLS certificate validation is disabled for this process. Use this only for STAGING/DEMO self-signed certificates.'
  [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
}

$ApiBaseUrl = (Require-Text $ApiBaseUrl 'API_BASE_URL').TrimEnd('/')
$AccountsCsv = Require-Text $AccountsCsv 'ACCOUNTS_CSV'
Enable-InsecureCertificateCheckIfRequested
if (-not $DryRun) {
  $BossToken = Require-Text $BossToken 'BOSS_TOKEN'
}
if (-not (Test-Path -LiteralPath $AccountsCsv -PathType Leaf)) {
  throw "ACCOUNTS_CSV does not exist: $AccountsCsv"
}

$accounts = @(Import-Csv -LiteralPath $AccountsCsv -Encoding UTF8)
if ($accounts.Count -ne 47) {
  throw "Cloud demo account CSV must contain exactly 47 accounts. Actual count: $($accounts.Count)."
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputPath = Join-Path $OutputDirectory "demo-account-passwords-$timestamp.csv"

$existingUsers = @{}
if (-not $DryRun) {
  $existingResponse = Invoke-Api -Method GET -Path '/api/users'
  foreach ($user in @($existingResponse.data)) {
    $existingUsers[[string]$user.username] = $user
  }
}

$records = New-Object System.Collections.Generic.List[object]
$reset = 0
$manual = 0
$missing = 0

foreach ($account in $accounts) {
  $username = ([string]$account.username).Trim().ToLowerInvariant()
  $role = ([string]$account.role).Trim().ToUpperInvariant()
  $password = New-RandomPassword
  $status = 'RESET'
  $note = ''

  if ($DryRun) {
    $status = 'DRY_RUN'
    $note = 'No API request was sent.'
  } elseif (-not $existingUsers.ContainsKey($username)) {
    $status = 'MISSING'
    $note = 'Account does not exist in target environment.'
    $password = ''
    $missing++
  } elseif ($role -eq 'BOSS' -and [string]::IsNullOrWhiteSpace($CurrentBossPassword)) {
    $status = 'MANUAL_REQUIRED'
    $note = 'Boss password requires self-service current password verification. Set CURRENT_BOSS_PASSWORD to reset through this script.'
    $password = ''
    $manual++
  } else {
    $target = $existingUsers[$username]
    $body = @{ password = $password }
    if ($role -eq 'BOSS') {
      $body.currentPassword = $CurrentBossPassword
    }
    Invoke-Api -Method POST -Path "/api/users/$($target.id)/reset-password" -Body $body | Out-Null
    $reset++
  }

  $records.Add([pscustomobject]@{
    username = $username
    displayName = $account.displayName
    role = $role
    storeId = $account.storeId
    password = $password
    status = $status
    note = $note
  })
}

$records | Export-Csv -LiteralPath $outputPath -NoTypeInformation -Encoding UTF8
Write-Host "Password reset output written to: $outputPath"
Write-Host "Reset result: reset=$reset manualRequired=$manual missing=$missing dryRun=$DryRun"
Write-Warning 'The output file contains one-time passwords. Keep it only under output/secure, which is gitignored. Delete it or move it to a controlled password vault after distribution.'
if ($missing -gt 0) {
  exit 1
}
