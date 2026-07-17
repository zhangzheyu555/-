[CmdletBinding()]
param(
  [string]$ApiBaseUrl = $env:API_BASE_URL,
  [string]$BossToken = $env:BOSS_TOKEN,
  [string]$EmployeePassword = $env:LOCAL_EMPLOYEE_PASSWORD
)

$ErrorActionPreference = 'Stop'

function Fail-Safely([string]$Message, [int]$Code = 2) {
  Write-Error $Message
  exit $Code
}

function New-TemporaryPassword {
  $bytes = New-Object byte[] 18
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($bytes)
  } finally {
    $rng.Dispose()
  }
  return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant().Substring(0, 16) + 'a1!'
}

function Normalize-ApiBase([string]$Value) {
  if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
  return $Value.Trim().TrimEnd('/')
}

$ApiBaseUrl = Normalize-ApiBase $ApiBaseUrl
if ([string]::IsNullOrWhiteSpace($ApiBaseUrl)) {
  Fail-Safely 'Missing API_BASE_URL. Point it to the local or QA backend, for example http://127.0.0.1:8080.'
}
if ([string]::IsNullOrWhiteSpace($BossToken)) {
  Fail-Safely 'Missing BOSS_TOKEN. Login with a boss account first and provide the Bearer token.'
}

$passwordGenerated = $false
if ([string]::IsNullOrWhiteSpace($EmployeePassword)) {
  $EmployeePassword = New-TemporaryPassword
  $passwordGenerated = $true
}
if ($EmployeePassword.Length -lt 8) {
  Fail-Safely 'LOCAL_EMPLOYEE_PASSWORD must be at least 8 characters.'
}

$authHeader = $BossToken.Trim()
if ($authHeader -notmatch '^\s*Bearer\s+') {
  $authHeader = "Bearer $authHeader"
}
$headers = @{
  Authorization = $authHeader
  Accept = 'application/json'
}

function Invoke-BusinessApi {
  param(
    [ValidateSet('GET', 'POST')] [string]$Method,
    [string]$Path,
    [object]$Body = $null
  )
  $uri = "$ApiBaseUrl$Path"
  $parameters = @{
    Method = $Method
    Uri = $uri
    Headers = $headers
    ContentType = 'application/json; charset=utf-8'
  }
  if ($null -ne $Body) {
    $parameters.Body = ($Body | ConvertTo-Json -Depth 8)
  }
  try {
    $response = Invoke-RestMethod @parameters
  } catch {
    throw "API call failed: $Method $Path. $($_.Exception.Message)"
  }
  if ($response.PSObject.Properties.Name -contains 'success' -and $response.success -eq $false) {
    $message = if ($response.message) { $response.message } else { 'business api returned failure' }
    $code = if ($response.code) { $response.code } else { 'UNKNOWN' }
    throw "$Method $Path failed: $code $message"
  }
  if ($response.PSObject.Properties.Name -contains 'data') { return $response.data }
  return $response
}

$stores = Invoke-BusinessApi -Method GET -Path '/api/stores'
$rg1 = @($stores) | Where-Object { $_.id -eq 'rg1' } | Select-Object -First 1
if (-not $rg1) {
  Fail-Safely 'Store rg1 was not found. Initialize or verify the local QA database first.'
}

$users = Invoke-BusinessApi -Method GET -Path '/api/users'
$labelEmployee = '' + [char]0x5458 + [char]0x5DE5
$employees = @(
  @{ username = 'rg1_emp_01'; displayName = "RG1$labelEmployee$([char]0x4E00)" },
  @{ username = 'rg1_emp_02'; displayName = "RG1$labelEmployee$([char]0x4E8C)" },
  @{ username = 'rg1_emp_03'; displayName = "RG1$labelEmployee$([char]0x4E09)" }
)

$created = @()
$skipped = @()
foreach ($employee in $employees) {
  $existing = @($users) | Where-Object { $_.username -eq $employee.username } | Select-Object -First 1
  if ($existing) {
    $skipped += $employee.username
    Write-Host "Already exists, skipped: $($employee.username)"
    continue
  }
  $payload = @{
    username = $employee.username
    displayName = $employee.displayName
    role = 'EMPLOYEE'
    storeId = 'rg1'
    storeScope = @('rg1')
    password = $EmployeePassword
  }
  Invoke-BusinessApi -Method POST -Path '/api/users' -Body $payload | Out-Null
  $created += $employee.username
  Write-Host "Created: $($employee.username)"
}

Write-Host ''
Write-Host "rg1 employee account processing completed. Created $($created.Count), skipped $($skipped.Count)."
if ($created.Count -gt 0) {
  Write-Host "Created accounts: $($created -join ', ')"
}
if ($passwordGenerated) {
  Write-Host "Temporary password: $EmployeePassword"
  Write-Host 'Use it only for local testing and reset it after first login.'
} else {
  Write-Host 'Password source: LOCAL_EMPLOYEE_PASSWORD'
}
