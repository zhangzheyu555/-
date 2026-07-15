Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ReleaseFlywayMigrationInfo {
  param(
    [Parameter(Mandatory = $true)][string]$MigrationDirectory,
    [Parameter(Mandatory = $true)][string]$Label
  )

  if (-not (Test-Path -LiteralPath $MigrationDirectory -PathType Container)) {
    throw "$Label Flyway migration directory is missing: $MigrationDirectory"
  }

  $migrations = @(
    Get-ChildItem -LiteralPath $MigrationDirectory -File -Filter 'V*__*.sql' |
      Where-Object { $_.Name -match '^V(\d+)__.+\.sql$' }
  )
  if ($migrations.Count -eq 0) {
    throw "$Label Flyway migration directory has no versioned SQL files: $MigrationDirectory"
  }

  $latestVersion = [int](($migrations | ForEach-Object {
    [int]([regex]::Match($_.Name, '^V(\d+)__').Groups[1].Value)
  } | Measure-Object -Maximum).Maximum)
  $latestMigrations = @($migrations | Where-Object { $_.Name -match ("^V{0}__.+\.sql$" -f $latestVersion) })
  if ($latestMigrations.Count -ne 1) {
    throw "$Label Flyway V$latestVersion must have exactly one migration file; found $($latestMigrations.Count)."
  }

  return [pscustomobject]@{
    label = $Label
    version = $latestVersion
    fileName = $latestMigrations[0].Name
    fullPath = $latestMigrations[0].FullName
  }
}

function Get-ReleaseFlywaySource {
  param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [ValidateRange(0, 9999)][int]$ExpectedVersion = 0
  )

  $mysql = Get-ReleaseFlywayMigrationInfo -MigrationDirectory (Join-Path $ProjectRoot 'backend\src\main\resources\db\migration') -Label 'MySQL'
  $h2 = Get-ReleaseFlywayMigrationInfo -MigrationDirectory (Join-Path $ProjectRoot 'backend\src\main\resources\db\migration-h2') -Label 'H2'
  if ($mysql.version -ne $h2.version) {
    throw "Flyway source trees are not synchronized: MySQL V$($mysql.version), H2 V$($h2.version)."
  }
  if (-not $mysql.fileName.Equals($h2.fileName, [StringComparison]::Ordinal)) {
    throw "Flyway source trees use different latest migration names: MySQL '$($mysql.fileName)', H2 '$($h2.fileName)'."
  }
  if ($ExpectedVersion -gt 0 -and $mysql.version -ne $ExpectedVersion) {
    throw "Flyway source latest version V$($mysql.version) does not match the expected V$ExpectedVersion."
  }

  return [pscustomobject]@{
    version = $mysql.version
    fileName = $mysql.fileName
    mysql = $mysql
    h2 = $h2
  }
}

function Assert-ReleaseNode20 {
  $nodeVersion = (& node --version 2>$null).Trim()
  if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v20\.\d+\.\d+$') {
    throw 'Node 20 LTS is required for release-candidate frontend builds. Configure the approved Node 20 runtime before retrying; Node 24 is refused.'
  }

  $npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
  if ($null -eq $npmCommand) {
    $npmCommand = Get-Command npm -ErrorAction SilentlyContinue
  }
  if ($null -eq $npmCommand) {
    throw 'npm is unavailable. Release-candidate frontend builds require the npm paired with Node 20.'
  }
  $npmVersion = (& $npmCommand.Source --version 2>$null).Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($npmVersion)) {
    throw 'npm did not return a usable version. Release-candidate frontend builds are refused.'
  }

  return [pscustomobject]@{
    node = $nodeVersion
    npm = $npmVersion
    npmPath = $npmCommand.Source
  }
}

Export-ModuleMember -Function Get-ReleaseFlywayMigrationInfo, Get-ReleaseFlywaySource, Assert-ReleaseNode20
