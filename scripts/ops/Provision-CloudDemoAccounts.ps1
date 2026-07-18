[CmdletBinding()]
param(
  [Alias('API_BASE_URL')]
  [string]$ApiBaseUrl = $env:API_BASE_URL,

  [Alias('BOSS_TOKEN')]
  [string]$BossToken = $env:BOSS_TOKEN,

  [Alias('ACCOUNTS_CSV')]
  [string]$AccountsCsv = $env:ACCOUNTS_CSV,

  [string]$AppEnv = $env:APP_ENV,

  [Alias('CONFIRM_DEMO_PASSWORD')]
  [string]$ConfirmDemoPassword = $env:CONFIRM_DEMO_PASSWORD,

  [switch]$AllowInsecureDemoPassword,
  [switch]$ResetPassword,
  [switch]$UpdateExistingProfile,
  [switch]$ConfigureDemoAccessProfile,
  [switch]$DryRun,
  [switch]$SkipCertificateCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$DemoPassword = '12345678'
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
  $normalizedEnv = (Require-Text $AppEnv 'APP_ENV').ToUpperInvariant()
  if ($AllowedEnvironments -notcontains $normalizedEnv) {
    throw "Refusing demo password provisioning because APP_ENV must be STAGING or DEMO. Current APP_ENV=$normalizedEnv."
  }
  if (-not $AllowInsecureDemoPassword) {
    throw 'Refusing to use 12345678. Pass -AllowInsecureDemoPassword for STAGING/DEMO only.'
  }
  if ($ConfirmDemoPassword -ne $RequiredConfirm) {
    throw "Refusing to use 12345678. Set CONFIRM_DEMO_PASSWORD=$RequiredConfirm."
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

function ConvertTo-StoreScope {
  param([string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) {
    return @()
  }
  return @($Value.Split(';') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function ConvertTo-Enabled {
  param([string]$Value)
  $raw = if ($null -eq $Value) { 'true' } else { $Value.Trim().ToLowerInvariant() }
  return @('true', '1', 'yes', 'y') -contains $raw
}

function Invoke-Api {
  param(
    [ValidateSet('GET', 'POST', 'PUT')]
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

function Format-ApiException {
  param([object]$ErrorRecord)
  $message = $ErrorRecord.Exception.Message
  $response = $ErrorRecord.Exception.Response
  if ($null -eq $response) {
    return $message
  }
  try {
    $reader = New-Object IO.StreamReader($response.GetResponseStream())
    $body = $reader.ReadToEnd()
    if (-not [string]::IsNullOrWhiteSpace($body)) {
      return "$message Body: $body"
    }
  } catch {
    return $message
  }
  return $message
}

function New-DataScope {
  param(
    [string]$Domain,
    [string]$Mode,
    [string[]]$StoreIds = @(),
    [string[]]$WarehouseIds = @()
  )
  @{
    domainCode = $Domain
    mode = $Mode
    storeIds = @($StoreIds)
    warehouseIds = @($WarehouseIds)
  }
}

function New-DemoDataScopes {
  param(
    [string]$Role,
    [string]$StoreId,
    [string[]]$StoreScope,
    [string[]]$AllStoreIds
  )
  $roleCode = $Role.ToUpperInvariant()
  $scope = @($StoreScope | Where-Object { $_ })
  if ($scope.Count -eq 0) {
    $scope = @($AllStoreIds)
  }
  switch ($roleCode) {
    'FINANCE' {
      return @(
        (New-DataScope -Domain 'FINANCE' -Mode 'ALL'),
        (New-DataScope -Domain 'STORE' -Mode 'ALL'),
        (New-DataScope -Domain 'SALARY' -Mode 'ALL'),
        (New-DataScope -Domain 'WAREHOUSE' -Mode 'ALL')
      )
    }
    'WAREHOUSE' {
      return @((New-DataScope -Domain 'WAREHOUSE' -Mode 'CENTRAL_WAREHOUSE'))
    }
    'OPERATIONS' {
      return @(
        (New-DataScope -Domain 'STORE' -Mode 'STORE_LIST' -StoreIds $scope),
        (New-DataScope -Domain 'EXAM' -Mode 'ALL'),
        (New-DataScope -Domain 'PLATFORM' -Mode 'ALL')
      )
    }
    'SUPERVISOR' {
      return @(
        (New-DataScope -Domain 'STORE' -Mode 'STORE_LIST' -StoreIds $scope),
        (New-DataScope -Domain 'INSPECTION' -Mode 'STORE_LIST' -StoreIds $scope)
      )
    }
    'STORE_MANAGER' {
      return @(
        (New-DataScope -Domain 'STORE' -Mode 'OWN_STORE'),
        (New-DataScope -Domain 'FINANCE' -Mode 'OWN_STORE'),
        (New-DataScope -Domain 'SALARY' -Mode 'OWN_STORE'),
        (New-DataScope -Domain 'WAREHOUSE' -Mode 'OWN_STORE'),
        (New-DataScope -Domain 'INSPECTION' -Mode 'OWN_STORE'),
        (New-DataScope -Domain 'EXAM' -Mode 'OWN_STORE')
      )
    }
    'EMPLOYEE' {
      return @((New-DataScope -Domain 'EXAM' -Mode 'SELF'))
    }
    default {
      return @()
    }
  }
}

function Set-DemoAccessProfile {
  param(
    [long]$UserId,
    [string]$Username,
    [string]$DisplayName,
    [string]$Role,
    [string]$StoreId,
    [string[]]$StoreScope,
    [bool]$Enabled,
    [string[]]$AllStoreIds
  )
  $roleCode = $Role.ToUpperInvariant()
  if ($roleCode -eq 'BOSS') {
    Invoke-Api -Method PUT -Path "/api/users/$UserId" -Body @{
      displayName = $DisplayName
      role = $roleCode
      storeId = $null
      storeScope = @()
      enabled = $Enabled
    } | Out-Null
    Write-Host "UPDATED existing account profile: $Username"
    return
  }
  Invoke-Api -Method PUT -Path "/api/users/$UserId/access-profile" -Body @{
    displayName = $DisplayName
    role = $roleCode
    storeId = $StoreId
    storeScope = @($StoreScope)
    enabled = $Enabled
    overrides = @()
    dataScopes = @(New-DemoDataScopes -Role $roleCode -StoreId $StoreId -StoreScope $StoreScope -AllStoreIds $AllStoreIds)
  } | Out-Null
  Write-Host "CONFIGURED demo access profile: $Username"
}

Assert-DemoPasswordAllowed
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

$seen = @{}
foreach ($account in $accounts) {
  $username = (Require-Text $account.username 'username').ToLowerInvariant()
  if ($seen.ContainsKey($username)) {
    throw "Duplicate username in CSV: $username"
  }
  $seen[$username] = $true
}

$allStoreIds = @($accounts | ForEach-Object {
  @(
    if (-not [string]::IsNullOrWhiteSpace($_.storeId)) { ([string]$_.storeId).Trim() }
    ConvertTo-StoreScope $_.storeScope
  )
} | Where-Object { $_ } | Select-Object -Unique)

Write-Host "Cloud demo provisioning target: $ApiBaseUrl"
Write-Host "APP_ENV: $((Require-Text $AppEnv 'APP_ENV').ToUpperInvariant())"
Write-Host "Account rows: $($accounts.Count)"
Write-Host "Password policy: temporary demo password is enabled only for this explicit run."

if ($DryRun) {
  foreach ($account in $accounts) {
    $scope = ConvertTo-StoreScope $account.storeScope
    Write-Host ("DRY-RUN create/skip username={0} role={1} storeId={2} scope={3}" -f
      $account.username, $account.role, $account.storeId, ($scope -join ';'))
  }
  Write-Host 'DRY-RUN completed. No API request was sent.'
  exit 0
}

$existingResponse = Invoke-Api -Method GET -Path '/api/users'
$existingUsers = @{}
foreach ($user in @($existingResponse.data)) {
  $existingUsers[[string]$user.username] = $user
}

$created = 0
$skipped = 0
$reset = 0
$failed = 0

foreach ($account in $accounts) {
  $username = ([string]$account.username).Trim().ToLowerInvariant()
  $role = ([string]$account.role).Trim().ToUpperInvariant()
  $storeId = if ([string]::IsNullOrWhiteSpace($account.storeId)) { $null } else { ([string]$account.storeId).Trim() }
  $storeScope = @(ConvertTo-StoreScope $account.storeScope)
  $enabled = ConvertTo-Enabled $account.enabled

  try {
    if ($existingUsers.ContainsKey($username)) {
      $target = $existingUsers[$username]
      $skipped++
      Write-Host "SKIP existing account: $username ($role)"
      if ($ConfigureDemoAccessProfile) {
        Set-DemoAccessProfile `
          -UserId $target.id `
          -Username $username `
          -DisplayName (Require-Text $account.displayName "displayName for $username") `
          -Role $role `
          -StoreId $storeId `
          -StoreScope $storeScope `
          -Enabled $enabled `
          -AllStoreIds $allStoreIds
      } elseif ($UpdateExistingProfile) {
        Invoke-Api -Method PUT -Path "/api/users/$($target.id)" -Body @{
          displayName = Require-Text $account.displayName "displayName for $username"
          role = $role
          storeId = $storeId
          storeScope = $storeScope
          enabled = $enabled
        } | Out-Null
        Write-Host "UPDATED existing account profile: $username"
      }
      if ($ResetPassword) {
        if (($target.role -as [string]).ToUpperInvariant() -eq 'BOSS') {
          Write-Warning "SKIP password reset for BOSS account $username. Boss password reset requires self-service current password verification."
        } else {
          Invoke-Api -Method POST -Path "/api/users/$($target.id)/reset-password" -Body @{ password = $DemoPassword } | Out-Null
          $reset++
          Write-Host "RESET password for existing account: $username"
        }
      }
      continue
    }

    $body = @{
      username = $username
      displayName = Require-Text $account.displayName "displayName for $username"
      role = $role
      storeId = $storeId
      storeScope = $storeScope
      password = $DemoPassword
    }
    $createdResponse = Invoke-Api -Method POST -Path '/api/users' -Body $body
    $created++
    Write-Host "CREATED account: $username ($role)"

    $createdUser = $createdResponse.data
    if ($ConfigureDemoAccessProfile) {
      Set-DemoAccessProfile `
        -UserId $createdUser.id `
        -Username $username `
        -DisplayName $body.displayName `
        -Role $role `
        -StoreId $storeId `
        -StoreScope $storeScope `
        -Enabled $enabled `
        -AllStoreIds $allStoreIds
    } elseif (-not $enabled) {
      Invoke-Api -Method PUT -Path "/api/users/$($createdUser.id)" -Body @{
        displayName = $body.displayName
        role = $role
        storeId = $storeId
        storeScope = $storeScope
        enabled = $false
      } | Out-Null
      Write-Host "DISABLED newly created account: $username"
    }
  } catch {
    $failed++
    Write-Warning ("FAILED account {0}: {1}" -f $username, (Format-ApiException $_))
  }
}

Write-Host "Provision result: created=$created skipped=$skipped reset=$reset failed=$failed"
Write-Warning 'Before production go-live, run Reset-DemoAccountPasswords.ps1 or manually reset every demo password. Never keep 12345678 in production.'
if ($failed -gt 0) {
  exit 1
}
