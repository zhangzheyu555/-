Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:LegacyFlywayMaximumMajorVersion = 105
$script:LegacyFlywayMigrationPattern = '^V(?<major>\d+)__(?<description>[A-Za-z0-9][A-Za-z0-9_]*)\.sql$'
$script:TimestampedFlywayMigrationPattern = '^V(?<major>[1-9]\d*)_(?<timestamp>20\d{15})__(?<description>[A-Za-z0-9][A-Za-z0-9_]*)\.sql$'

function ConvertTo-ReleaseFlywayMigrationInfo {
  param(
    [Parameter(Mandatory = $true)][System.IO.FileInfo]$File,
    [Parameter(Mandatory = $true)][string]$Label
  )

  $timestamped = [regex]::Match($File.Name, $script:TimestampedFlywayMigrationPattern)
  if ($timestamped.Success) {
    $major = [int]$timestamped.Groups['major'].Value
    if ($major -le $script:LegacyFlywayMaximumMajorVersion) {
      throw "$Label Flyway migration '$($File.Name)' must use a major version greater than V$script:LegacyFlywayMaximumMajorVersion after the timestamped-version cutover."
    }
    $timestamp = $timestamped.Groups['timestamp'].Value
    return [pscustomobject]@{
      major = $major
      timestamp = $timestamp
      version = "$major.$timestamp"
      fileName = $File.Name
      fullPath = $File.FullName
    }
  }

  $legacy = [regex]::Match($File.Name, $script:LegacyFlywayMigrationPattern)
  if ($legacy.Success) {
    $major = [int]$legacy.Groups['major'].Value
    if ($major -gt $script:LegacyFlywayMaximumMajorVersion) {
      throw "$Label Flyway migration '$($File.Name)' must use V<major>_<yyyyMMddHHmmssSSS>__<description>.sql."
    }
    return [pscustomobject]@{
      major = $major
      timestamp = ''
      version = [string]$major
      fileName = $File.Name
      fullPath = $File.FullName
    }
  }

  throw "$Label Flyway migration filename is invalid: '$($File.Name)'. Expected a legacy V<major>__<description>.sql file through V$script:LegacyFlywayMaximumMajorVersion, or V<major>_<yyyyMMddHHmmssSSS>__<description>.sql afterwards."
}

function Get-ReleaseFlywayMigrationInfo {
  param(
    [Parameter(Mandatory = $true)][string]$MigrationDirectory,
    [Parameter(Mandatory = $true)][string]$Label
  )

  if (-not (Test-Path -LiteralPath $MigrationDirectory -PathType Container)) {
    throw "$Label Flyway migration directory is missing: $MigrationDirectory"
  }

  $migrationFiles = @(Get-ChildItem -LiteralPath $MigrationDirectory -File -Filter 'V*__*.sql')
  $migrations = @($migrationFiles | ForEach-Object {
      ConvertTo-ReleaseFlywayMigrationInfo -File $_ -Label $Label
    })
  if ($migrations.Count -eq 0) {
    throw "$Label Flyway migration directory has no versioned SQL files: $MigrationDirectory"
  }

  $duplicateVersions = @($migrations | Group-Object -Property version | Where-Object { $_.Count -gt 1 })
  if ($duplicateVersions.Count -gt 0) {
    throw "$Label Flyway version V$($duplicateVersions[0].Name) is duplicated by $($duplicateVersions[0].Group.fileName -join ', ')."
  }

  $latestMigration = $migrations |
    Sort-Object -Property @{ Expression = 'major'; Descending = $true }, @{ Expression = 'timestamp'; Descending = $true } |
    Select-Object -First 1
  return [pscustomobject]@{
    label = $Label
    major = $latestMigration.major
    version = $latestMigration.version
    fileName = $latestMigration.fileName
    fullPath = $latestMigration.fullPath
  }
}

function Get-ReleaseFlywaySource {
  param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [string]$ExpectedVersion = ''
  )

  $mysql = Get-ReleaseFlywayMigrationInfo -MigrationDirectory (Join-Path $ProjectRoot 'backend\src\main\resources\db\migration') -Label 'MySQL'
  $h2 = Get-ReleaseFlywayMigrationInfo -MigrationDirectory (Join-Path $ProjectRoot 'backend\src\main\resources\db\migration-h2') -Label 'H2'
  if ($mysql.version -ne $h2.version) {
    throw "Flyway source trees are not synchronized: MySQL V$($mysql.version), H2 V$($h2.version)."
  }
  if (-not $mysql.fileName.Equals($h2.fileName, [StringComparison]::Ordinal)) {
    throw "Flyway source trees use different latest migration names: MySQL '$($mysql.fileName)', H2 '$($h2.fileName)'."
  }
  if (-not [string]::IsNullOrWhiteSpace($ExpectedVersion) -and $mysql.version -ne $ExpectedVersion) {
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
