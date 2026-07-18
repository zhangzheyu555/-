[CmdletBinding()]
param(
  [int]$FromVersion = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$required = @(
  'MYSQL_HOST',
  'MYSQL_PORT',
  'MYSQL_DATABASE',
  'MYSQL_USERNAME',
  'MYSQL_PASSWORD'
)

$missing = $required | Where-Object {
  [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_))
}
if ($missing.Count -gt 0) {
  throw "Missing required MySQL environment variables: $($missing -join ', ')"
}

$mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue
if ($null -eq $mysql) { $mysql = Get-Command mysql -ErrorAction SilentlyContinue }
if ($null -eq $mysql) {
  throw 'mysql client is required for read-only Flyway history inspection.'
}
$mysqlPath = if ($mysql.Source) { $mysql.Source } else { $mysql.Path }

$hostName = [Environment]::GetEnvironmentVariable('MYSQL_HOST')
$portText = [Environment]::GetEnvironmentVariable('MYSQL_PORT')
$database = [Environment]::GetEnvironmentVariable('MYSQL_DATABASE')
$username = [Environment]::GetEnvironmentVariable('MYSQL_USERNAME')
$password = [Environment]::GetEnvironmentVariable('MYSQL_PASSWORD')

if ($hostName -notin @('127.0.0.1', 'localhost')) {
  throw 'This inspection script only accepts local MySQL hosts.'
}
if ($portText -ne '3307') {
  throw 'This inspection script is scoped to local MySQL port 3307.'
}
if ($database -notmatch '^[A-Za-z0-9_]+$' -or $username -notmatch '^[A-Za-z0-9_]+$') {
  throw 'MYSQL_DATABASE or MYSQL_USERNAME contains unsupported characters.'
}

$query = @"
select
  version,
  description,
  script,
  checksum,
  installed_on,
  success
from flyway_schema_history
where version regexp '^[0-9]+$'
  and cast(version as unsigned) >= $FromVersion
order by installed_rank;
"@

$arguments = @(
  '--protocol=TCP',
  "--host=$hostName",
  "--port=$portText",
  "--user=$username",
  '--batch',
  '--raw',
  '--skip-column-names',
  "--database=$database",
  "--execute=$query"
)

$startInfo = [Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $mysqlPath
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.EnvironmentVariables['MYSQL_PWD'] = $password
foreach ($argument in $arguments) {
  [void]$startInfo.ArgumentList.Add($argument)
}

$process = [Diagnostics.Process]::new()
try {
  $process.StartInfo = $startInfo
  if (-not $process.Start()) { throw 'Could not start mysql client.' }
  $stdout = $process.StandardOutput.ReadToEnd()
  $stderr = $process.StandardError.ReadToEnd()
  $process.WaitForExit()
  if ($process.ExitCode -ne 0) {
    throw "Read-only Flyway history query failed with exit code $($process.ExitCode): $stderr"
  }
} finally {
  $password = $null
  if ($process) { $process.Dispose() }
}

$rows = @()
foreach ($line in @($stdout -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
  $columns = $line -split "`t", 6
  if ($columns.Count -lt 6) { continue }
  $rows += [pscustomobject]@{
    Version = $columns[0]
    Description = $columns[1]
    Script = $columns[2]
    Checksum = $columns[3]
    InstalledOn = $columns[4]
    Success = $columns[5]
  }
}

$oldDailyLossAt63Or64 = @($rows | Where-Object {
  $_.Version -in @('63', '64') -and
      ($_.Script -match '(?i)daily_loss|reimbursement' -or
       $_.Description -match '(?i)daily.loss|reimbursement|报损|报销')
})
$expectedRemote63To66 = @($rows | Where-Object {
  ($_.Version -eq '63' -and $_.Script -eq 'V63__qmai_platform_config.sql') -or
  ($_.Version -eq '64' -and $_.Script -eq 'V64__qmai_config_brand.sql') -or
  ($_.Version -eq '65' -and $_.Script -eq 'V65__qmai_console_token.sql') -or
  ($_.Version -eq '66' -and $_.Script -eq 'V66__employee_profile_fields.sql')
})
$dailyLoss67Or68 = @($rows | Where-Object {
  $_.Version -in @('67', '68') -and
      ($_.Script -match '(?i)daily_loss|reimbursement' -or
       $_.Description -match '(?i)daily.loss|reimbursement|报损|报销')
})

$summary = [pscustomobject]@{
  Database = $database
  Host = $hostName
  Port = $portText
  FromVersion = $FromVersion
  RowCount = $rows.Count
  FoundOldDailyLossAtV63OrV64 = $oldDailyLossAt63Or64.Count -gt 0
  RemoteV63ToV66Count = $expectedRemote63To66.Count
  DailyLossV67OrV68Count = $dailyLoss67Or68.Count
}

Write-Output 'Flyway history summary:'
$summary | Format-List
Write-Output 'Flyway history rows:'
$rows | Format-Table -AutoSize
