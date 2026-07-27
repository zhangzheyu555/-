[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Equal {
  param(
    [Parameter(Mandatory)]$Actual,
    [Parameter(Mandatory)]$Expected,
    [Parameter(Mandatory)][string]$Message
  )

  if ($Actual -cne $Expected) {
    throw "$Message Expected '$Expected', received '$Actual'."
  }
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$modulePath = Join-Path $projectRoot 'scripts\ReleaseCandidateCommon.psm1'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-flyway-rule-' + [Guid]::NewGuid().ToString('N'))

try {
  $mysqlDirectory = Join-Path $temporaryRoot 'mysql'
  $h2Directory = Join-Path $temporaryRoot 'h2'
  New-Item -ItemType Directory -Force -Path $mysqlDirectory, $h2Directory | Out-Null
  New-Item -ItemType File -Path (Join-Path $mysqlDirectory 'V105__legacy.sql') | Out-Null
  New-Item -ItemType File -Path (Join-Path $h2Directory 'V105__legacy.sql') | Out-Null
  New-Item -ItemType File -Path (Join-Path $mysqlDirectory 'V106_20260727143015842__new_rule.sql') | Out-Null
  New-Item -ItemType File -Path (Join-Path $h2Directory 'V106_20260727143015842__new_rule.sql') | Out-Null

  Import-Module -Name $modulePath -Force
  $mysql = Get-ReleaseFlywayMigrationInfo -MigrationDirectory $mysqlDirectory -Label 'MySQL test'
  $h2 = Get-ReleaseFlywayMigrationInfo -MigrationDirectory $h2Directory -Label 'H2 test'

  Assert-Equal -Actual $mysql.version -Expected '106.20260727143015842' -Message 'The new timestamped migration must be selected as latest.'
  Assert-Equal -Actual $mysql.fileName -Expected 'V106_20260727143015842__new_rule.sql' -Message 'The timestamped MySQL migration filename must be preserved.'
  Assert-Equal -Actual $h2.version -Expected $mysql.version -Message 'MySQL and H2 timestamped migration versions must remain synchronized.'
  Write-Host 'PASS timestamped Flyway migration rule is parsed and synchronized.'
}
finally {
  Remove-Module ReleaseCandidateCommon -Force -ErrorAction SilentlyContinue
  if (Test-Path -LiteralPath $temporaryRoot) {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
  }
}
