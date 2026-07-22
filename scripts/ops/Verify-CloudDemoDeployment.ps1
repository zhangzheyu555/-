[CmdletBinding()]
param(
  [Alias('API_BASE_URL')]
  [string]$ApiBaseUrl = $env:API_BASE_URL,

  [Alias('BOSS_TOKEN')]
  [string]$BossToken = $env:BOSS_TOKEN,

  [Alias('ACCOUNTS_CSV')]
  [string]$AccountsCsv = $env:ACCOUNTS_CSV,

  [string]$AppEnv = $env:APP_ENV,
  [string]$DemoPassword = $env:DEMO_PASSWORD,

  [Alias('CONFIRM_DEMO_PASSWORD')]
  [string]$ConfirmDemoPassword = $env:CONFIRM_DEMO_PASSWORD,

  [switch]$AllowInsecureDemoPassword,
  [switch]$SkipCertificateCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RequiredConfirm = 'I_UNDERSTAND_12345678_IS_DEMO_ONLY'
$AllowedEnvironments = @('STAGING', 'DEMO')

function Require-Text {
  param([string]$Value, [string]$Name)
  if ([string]::IsNullOrWhiteSpace($Value)) {
    throw "$Name is required."
  }
  return $Value.Trim()
}

function Assert-DemoPasswordAllowed {
  if ($DemoPassword -ne '12345678') {
    return
  }
  $normalizedEnv = (Require-Text $AppEnv 'APP_ENV').ToUpperInvariant()
  if ($AllowedEnvironments -notcontains $normalizedEnv) {
    throw "Refusing to verify with 12345678 because APP_ENV must be STAGING or DEMO. Current APP_ENV=$normalizedEnv."
  }
  if (-not $AllowInsecureDemoPassword) {
    throw 'Refusing to verify with 12345678. Pass -AllowInsecureDemoPassword for STAGING/DEMO only.'
  }
  if ($ConfirmDemoPassword -ne $RequiredConfirm) {
    throw "Refusing to verify with 12345678. Set CONFIRM_DEMO_PASSWORD=$RequiredConfirm."
  }
}

function Enable-InsecureCertificateCheckIfRequested {
  [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
  if (-not $SkipCertificateCheck) {
    return
  }
  Write-Warning 'TLS certificate validation is disabled for this process. Use this only for STAGING/DEMO self-signed certificates.'
  [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
}

function Invoke-Api {
  param(
    [ValidateSet('GET', 'POST')]
    [string]$Method,
    [string]$Path,
    [string]$Token = $BossToken,
    [object]$Body = $null,
    [int[]]$ExpectedStatus = @(200)
  )
  $headers = @{}
  if (-not [string]::IsNullOrWhiteSpace($Token)) {
    $headers.Authorization = "Bearer $Token"
  }
  $uri = "$ApiBaseUrl$Path"
  $content = $null
  $status = $null
  for ($attempt = 1; $attempt -le 3; $attempt++) {
    try {
      if ($null -eq $Body) {
        $response = Invoke-WebRequest -Method $Method -Uri $uri -Headers $headers -UseBasicParsing -TimeoutSec 30
      } else {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        $response = Invoke-WebRequest -Method $Method -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $json -UseBasicParsing -TimeoutSec 30
      }
      $status = [int]$response.StatusCode
      $content = $response.Content
      break
    } catch {
      $webResponse = $_.Exception.Response
      if ($null -eq $webResponse) {
        if ($attempt -lt 3) {
          Start-Sleep -Milliseconds (300 * $attempt)
          continue
        }
        throw
      }
      $status = [int]$webResponse.StatusCode
      $reader = New-Object IO.StreamReader($webResponse.GetResponseStream())
      $content = $reader.ReadToEnd()
      break
    }
  }
  if ($ExpectedStatus -notcontains $status) {
    throw "Unexpected HTTP status for $Method $Path. Expected $($ExpectedStatus -join ',') but got $status. Body: $content"
  }
  $data = $null
  if (-not [string]::IsNullOrWhiteSpace($content)) {
    try { $data = $content | ConvertFrom-Json } catch { $data = $content }
  }
  [pscustomobject]@{ Status = $status; Body = $data; Raw = $content }
}

function Assert-True {
  param([bool]$Condition, [string]$Message)
  if (-not $Condition) {
    throw $Message
  }
  Write-Host "PASS $Message"
}

function Get-LatestLocalFlywayVersion {
  $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
  $files = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'backend\src\main\resources\db\migration') -Filter 'V*__*.sql'
  $versions = foreach ($file in $files) {
    if ($file.Name -match '^V(\d+)__') { [int]$Matches[1] }
  }
  if (-not $versions) { throw 'No local Flyway migration files found.' }
  ($versions | Measure-Object -Maximum).Maximum
}

$ApiBaseUrl = (Require-Text $ApiBaseUrl 'API_BASE_URL').TrimEnd('/')
$BossToken = Require-Text $BossToken 'BOSS_TOKEN'
$AccountsCsv = Require-Text $AccountsCsv 'ACCOUNTS_CSV'
$DemoPassword = if ([string]::IsNullOrWhiteSpace($DemoPassword)) { '12345678' } else { $DemoPassword }
Assert-DemoPasswordAllowed
Enable-InsecureCertificateCheckIfRequested

if (-not (Test-Path -LiteralPath $AccountsCsv -PathType Leaf)) {
  throw "ACCOUNTS_CSV does not exist: $AccountsCsv"
}
$accounts = @(Import-Csv -LiteralPath $AccountsCsv -Encoding UTF8)
Assert-True ($accounts.Count -eq 46) "CSV contains exactly 46 accounts"

$liveness = Invoke-Api -Method GET -Path '/api/health' -Token '' -ExpectedStatus @(200)
Assert-True ($liveness.Body.success -eq $true -and $liveness.Body.data.status -eq 'UP') '/api/health status is UP'
$diagnostics = Invoke-Api -Method GET -Path '/api/health/diagnostics' -ExpectedStatus @(200)
$localLatest = Get-LatestLocalFlywayVersion
$remoteVersion = [int]$diagnostics.Body.data.databaseMigrationVersion
Assert-True ($remoteVersion -eq $localLatest) "Flyway version matches local latest V$localLatest"

$users = (Invoke-Api -Method GET -Path '/api/users').Body.data
$userMap = @{}
foreach ($user in @($users)) { $userMap[[string]$user.username] = $user }

foreach ($account in $accounts) {
  $username = ([string]$account.username).Trim().ToLowerInvariant()
  Assert-True ($userMap.ContainsKey($username)) "account exists: $username"
}

foreach ($account in $accounts) {
  $username = ([string]$account.username).Trim().ToLowerInvariant()
  $role = ([string]$account.role).Trim().ToUpperInvariant()
  $login = Invoke-Api -Method POST -Path '/api/auth/login' -Token '' -Body @{
    username = $username
    password = $DemoPassword
  }
  Assert-True ($login.Body.data.user.role -eq $role) "account can login with demo password and role matches: $username"
}

$rg1Employees = @($accounts | Where-Object {
  ([string]$_.role).Trim().ToUpperInvariant() -eq 'EMPLOYEE' -and
  ([string]$_.storeId).Trim().ToLowerInvariant() -eq 'rg1'
})
Assert-True ($rg1Employees.Count -ge 3) 'CSV contains at least 3 rg1 EMPLOYEE accounts'
foreach ($employee in $rg1Employees) {
  $username = ([string]$employee.username).Trim().ToLowerInvariant()
  $target = $userMap[$username]
  Assert-True ($target.role -eq 'EMPLOYEE' -and $target.storeId -eq 'rg1') "rg1 employee exists and is bound to rg1: $username"
}

$employeeAccount = ($rg1Employees | Select-Object -First 1)
$employeeLogin = Invoke-Api -Method POST -Path '/api/auth/login' -Token '' -Body @{
  username = $employeeAccount.username
  password = $DemoPassword
}
$employeeToken = $employeeLogin.Body.data.token
$employeeUser = $employeeLogin.Body.data.user
Assert-True ($employeeUser.role -eq 'EMPLOYEE') 'employee login returns EMPLOYEE role'
Assert-True ($employeeUser.defaultWorkspace -eq '/employee') 'employee default workspace is /employee'

Invoke-Api -Method GET -Path '/api/users' -Token $employeeToken -ExpectedStatus @(403) | Out-Null
Write-Host 'PASS EMPLOYEE cannot access account management'
Invoke-Api -Method GET -Path '/api/finance/workbench' -Token $employeeToken -ExpectedStatus @(403) | Out-Null
Write-Host 'PASS EMPLOYEE cannot access finance workbench'
Invoke-Api -Method GET -Path '/api/operations/inventory-checks' -Token $employeeToken -ExpectedStatus @(403) | Out-Null
Write-Host 'PASS EMPLOYEE cannot access operations inventory checks'
Invoke-Api -Method GET -Path '/api/warehouse/overview' -Token $employeeToken -ExpectedStatus @(403) | Out-Null
Write-Host 'PASS EMPLOYEE cannot access warehouse overview'
Invoke-Api -Method GET -Path '/api/audit/logs?limit=1' -Token '' -ExpectedStatus @(401) | Out-Null
Write-Host 'PASS /api/audit/logs requires authentication'
Invoke-Api -Method GET -Path '/api/audit/logs?limit=1' -Token $employeeToken -ExpectedStatus @(403) | Out-Null
Write-Host 'PASS EMPLOYEE cannot access audit logs'

$supervisor = $accounts | Where-Object { ([string]$_.role).Trim().ToUpperInvariant() -eq 'SUPERVISOR' } | Select-Object -First 1
Assert-True ($null -ne $supervisor) 'CSV contains SUPERVISOR account'

$supervisorLogin = Invoke-Api -Method POST -Path '/api/auth/login' -Token '' -Body @{ username = $supervisor.username; password = $DemoPassword }
$supervisorToken = $supervisorLogin.Body.data.token
Assert-True ($supervisorLogin.Body.data.user.defaultWorkspace -eq '/operations') 'SUPERVISOR default workspace is supervisor workspace'
Invoke-Api -Method GET -Path '/api/inspections' -Token $supervisorToken -ExpectedStatus @(200) | Out-Null
Write-Host 'PASS SUPERVISOR can read inspections'
Invoke-Api -Method GET -Path '/api/operations/inventory-checks' -Token $supervisorToken -ExpectedStatus @(200) | Out-Null
Write-Host 'PASS SUPERVISOR can access managed operations inventory checks'

$logs = Invoke-Api -Method GET -Path '/api/audit/logs?limit=100'
Assert-True ($logs.Body.success -eq $true -and @($logs.Body.data).Count -gt 0) 'operation_log is readable and has rows'
$authLogs = @($logs.Body.data | Where-Object {
  ([string]$_.targetType) -eq 'auth_user'
})
Assert-True ($authLogs.Count -gt 0) 'operation_log contains account-related audit records'

Write-Host 'Cloud demo deployment verification completed.'
