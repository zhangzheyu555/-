param(
  [int]$TimeoutSeconds = 120,
  [int]$HealthPort = $(if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }),
  [string]$DiagnosticsAuthorizationToken = $env:QA_BOSS_TOKEN,
  [switch]$SkipPackage
)

$ErrorActionPreference = 'Stop'

$required = @(
  'MYSQL_HOST',
  'MYSQL_PORT',
  'MYSQL_DATABASE',
  'MYSQL_USERNAME',
  'MYSQL_PASSWORD'
)

$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
if ($missing.Count -gt 0) {
  throw "Missing required MySQL environment variables: $($missing -join ', ')"
}

if ([string]::IsNullOrWhiteSpace($env:APP_ENV)) {
  $env:APP_ENV = 'STAGING'
}
if ([string]::IsNullOrWhiteSpace($env:SERVER_PORT)) {
  $env:SERVER_PORT = [string]$HealthPort
}
if ([string]::IsNullOrWhiteSpace($DiagnosticsAuthorizationToken)) {
  throw 'A BOSS diagnostics token is required. Set QA_BOSS_TOKEN or pass -DiagnosticsAuthorizationToken.'
}
$diagnosticsAuthorization = if ($DiagnosticsAuthorizationToken.Trim().StartsWith('Bearer ', [StringComparison]::OrdinalIgnoreCase)) {
  $DiagnosticsAuthorizationToken.Trim()
} else {
  'Bearer ' + $DiagnosticsAuthorizationToken.Trim()
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$backendDir = Join-Path $repoRoot 'backend'
$jarPath = Join-Path $backendDir 'target/store-profit-backend-0.1.0-SNAPSHOT.jar'
$logDir = Join-Path $repoRoot 'output/mysql-flyway'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (-not $SkipPackage) {
  Push-Location $backendDir
  try {
    & mvn -q -DskipTests package
  } finally {
    Pop-Location
  }
}

if (-not (Test-Path $jarPath)) {
  throw "Backend jar not found: $jarPath"
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $logDir "flyway-v62-$timestamp.out.log"
$stderr = Join-Path $logDir "flyway-v62-$timestamp.err.log"

Write-Host "Starting backend to trigger Flyway migrations. Use an empty MySQL database for V1..V62 validation."
$process = Start-Process -FilePath 'java' `
  -ArgumentList @('-jar', $jarPath) `
  -WorkingDirectory $backendDir `
  -RedirectStandardOutput $stdout `
  -RedirectStandardError $stderr `
  -PassThru `
  -WindowStyle Hidden

try {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  $healthUrl = "http://127.0.0.1:$HealthPort/api/health"
  while ((Get-Date) -lt $deadline) {
    if ($process.HasExited) {
      $tail = ''
      if (Test-Path $stderr) {
        $tail = (Get-Content $stderr -Tail 80) -join [Environment]::NewLine
      }
      throw "Backend exited before health check passed. ExitCode=$($process.ExitCode). Log: $stderr`n$tail"
    }

    try {
      $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3
      if ($health.success -and $health.data.status -eq 'UP') {
        $diagnostics = Invoke-RestMethod -Uri "$healthUrl/diagnostics" -Headers @{ Authorization = $diagnosticsAuthorization } -TimeoutSec 3
        $version = [string]$diagnostics.data.databaseMigrationVersion
        Write-Host "Backend liveness is UP. Authenticated Flyway current version: $version"
        Write-Host "Authenticated database target: $($diagnostics.data.databaseName) on port $($diagnostics.data.databasePort)"
        if ($version -ne '62') {
          Write-Warning "Expected Flyway version 62. Current version is $version."
        }
        Write-Host "Logs: $stdout ; $stderr"
        return
      }
    } catch {
      Start-Sleep -Seconds 2
    }
  }

  throw "Timed out waiting for $healthUrl after $TimeoutSeconds seconds. Logs: $stdout ; $stderr"
} finally {
  $DiagnosticsAuthorizationToken = $null
  $diagnosticsAuthorization = $null
  if ($process -and -not $process.HasExited) {
    Stop-Process -Id $process.Id -Force
  }
}
