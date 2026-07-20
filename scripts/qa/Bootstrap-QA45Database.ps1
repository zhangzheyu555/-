param(
  [Parameter(Mandatory = $true)]
  [string]$Database,
  [string]$MySqlHost = '127.0.0.1',
  [int]$Port = 3307,
  [string]$Username = 'root',
  [switch]$AllowLocalQaPort
)

$ErrorActionPreference = 'Stop'

if ($Database -notmatch '(?i)(qa|test)') {
  throw 'Database must contain qa or test.'
}
if ($Port -eq 3307 -and -not $AllowLocalQaPort) {
  throw 'Port 3307 requires -AllowLocalQaPort and must only target a new QA/Test database.'
}
$databasePassword = $env:MYSQL_PASSWORD
if ([string]::IsNullOrWhiteSpace($databasePassword)) {
  $databasePassword = $env:MYSQL_PWD
}
if ([string]::IsNullOrWhiteSpace($databasePassword)) {
  throw 'MYSQL_PASSWORD must be supplied by the runtime environment.'
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jar = Join-Path $repoRoot 'backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jar)) {
  throw 'Backend jar is missing. Run backend package first.'
}

$logDir = Join-Path $repoRoot 'output\qa45-bootstrap'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$stdout = Join-Path $logDir 'flyway-bootstrap.stdout.log'
$stderr = Join-Path $logDir 'flyway-bootstrap.stderr.log'
Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue

$env:APP_ENV = 'QA'
$env:MYSQL_HOST = $MySqlHost
$env:MYSQL_PORT = [string]$Port
$env:MYSQL_DATABASE = $Database
$env:MYSQL_USERNAME = $Username
$env:MYSQL_PASSWORD = $databasePassword
$env:SERVER_PORT = '19085'

$process = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar) -WorkingDirectory (Join-Path $repoRoot 'backend') -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
try {
  $deadline = (Get-Date).AddSeconds(60)
  do {
    Start-Sleep -Seconds 2
    $log = (Get-Content $stdout -Raw -ErrorAction SilentlyContinue) + (Get-Content $stderr -Raw -ErrorAction SilentlyContinue)
    if ($log -match '(?m)^.*\sStarted StoreProfitApplication in\s') {
      Write-Output "Flyway bootstrap completed for $Database."
      exit 0
    }
    if ($log -match 'APPLICATION FAILED TO START|FlywayValidateException|Unable to obtain connection|Migration .* failed') {
      throw 'Flyway bootstrap failed. Inspect output/qa45-bootstrap logs.'
    }
  } while ((Get-Date) -lt $deadline)
  throw 'Flyway bootstrap timed out. Inspect output/qa45-bootstrap logs.'
} finally {
  Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
  Remove-Item Env:MYSQL_PASSWORD -ErrorAction SilentlyContinue
}
