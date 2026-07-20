[CmdletBinding()]
param([switch]$AllowKnownRiskDatabase)

$required = 'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USERNAME', 'MYSQL_PASSWORD'
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
if ($missing.Count) { throw "Missing required MySQL environment variables: $($missing -join ', ')" }

$database = [Environment]::GetEnvironmentVariable('MYSQL_DATABASE')
$port = [Environment]::GetEnvironmentVariable('MYSQL_PORT')
if ($database -notmatch '(?i)(test|qa)') { throw 'MYSQL_DATABASE must contain test or qa.' }
if ($port -eq '3307' -and -not $AllowKnownRiskDatabase) { throw 'Refusing port 3307 without -AllowKnownRiskDatabase.' }

$backend = Join-Path $PSScriptRoot '..\..\backend'
Push-Location $backend
try {
  mvn -q '-Dtest=DailyLossServiceTest' test
} finally {
  Pop-Location
}
