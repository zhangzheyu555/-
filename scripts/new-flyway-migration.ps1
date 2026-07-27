[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[a-z][a-z0-9_]*$')]
  [string]$Name
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$modulePath = Join-Path $PSScriptRoot 'ReleaseCandidateCommon.psm1'
Import-Module -Name $modulePath -Force -ErrorAction Stop

$source = Get-ReleaseFlywaySource -ProjectRoot $projectRoot
$nextMajor = [int]$source.mysql.major + 1
$chinaStandardTime = @('China Standard Time', 'Asia/Shanghai') |
  ForEach-Object {
    try { [TimeZoneInfo]::FindSystemTimeZoneById($_) } catch { $null }
  } |
  Where-Object { $null -ne $_ } |
  Select-Object -First 1
if ($null -eq $chinaStandardTime) {
  throw 'China Standard Time / Asia/Shanghai is unavailable; refusing to generate an ambiguous migration timestamp.'
}
$timestamp = [TimeZoneInfo]::ConvertTimeFromUtc([DateTime]::UtcNow, $chinaStandardTime).ToString('yyyyMMddHHmmssfff')
$baseName = "V${nextMajor}_${timestamp}__${Name}.sql"
$mysqlPath = Join-Path $projectRoot (Join-Path 'backend\src\main\resources\db\migration' $baseName)
$h2Path = Join-Path $projectRoot (Join-Path 'backend\src\main\resources\db\migration-h2' $baseName)

if ((Test-Path -LiteralPath $mysqlPath) -or (Test-Path -LiteralPath $h2Path)) {
  throw "Generated Flyway migration version already exists: $baseName. Retry to obtain a new timestamp."
}

$mysqlTemplate = @"
-- Flyway version: $($nextMajor).$timestamp
-- Purpose: $Name
-- Add MySQL migration SQL below. Do not modify an executed migration.
"@
$h2Template = @"
-- Flyway version: $($nextMajor).$timestamp
-- Purpose: $Name
-- Add H2-compatible migration SQL below. Keep this version synchronized with MySQL.
"@

[IO.File]::WriteAllText($mysqlPath, $mysqlTemplate, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($h2Path, $h2Template, [Text.UTF8Encoding]::new($false))
Write-Host "Created MySQL migration: $mysqlPath"
Write-Host "Created H2 migration: $h2Path"
