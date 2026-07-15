Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function New-QAReleaseRun {
  param(
    [Parameter(Mandatory = $true)][string]$EvidenceRoot,
    [Parameter(Mandatory = $true)][string]$Category
  )

  $runId = 'qa-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'), [Guid]::NewGuid().ToString('N').Substring(0, 8)
  $directory = Join-Path ([IO.Path]::GetFullPath($EvidenceRoot)) (Join-Path $Category $runId)
  New-Item -ItemType Directory -Force -Path $directory | Out-Null
  return [pscustomobject]@{ id = $runId; directory = $directory }
}

function Write-QAReleaseJson {
  param(
    [Parameter(Mandatory = $true)]$Value,
    [Parameter(Mandatory = $true)][string]$Path
  )

  $utf8NoBom = New-Object Text.UTF8Encoding($false)
  [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), $utf8NoBom)
}

function Get-QAReleaseSecret {
  param([Parameter(Mandatory = $true)][string]$EnvironmentName)

  foreach ($target in @(
      [EnvironmentVariableTarget]::Process,
      [EnvironmentVariableTarget]::User,
      [EnvironmentVariableTarget]::Machine
    )) {
    $value = [Environment]::GetEnvironmentVariable($EnvironmentName, $target)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      return $value
    }
  }
  throw "Required QA secret environment variable '$EnvironmentName' is unavailable. Its value is never read from files or printed."
}

function Assert-QAReleaseDatabaseTarget {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlDatabase,
    [Parameter(Mandatory = $true)][string]$MySqlUsername
  )

  if ($MySqlHost -notin @('127.0.0.1', 'localhost', '::1')) {
    throw 'QA database scripts only accept a loopback MySQL host. Use an isolated local QA instance, never a remote or production target.'
  }
  if ($MySqlPort -eq 3307) {
    throw 'Port 3307 is protected and is forbidden for QA release scripts.'
  }
  if ($MySqlPort -lt 1024 -or $MySqlPort -gt 65535) {
    throw 'QA MySQL port is invalid.'
  }
  if ($MySqlDatabase -notmatch '^(?i)ai_profit_(qa|test)_[a-z0-9_]+$') {
    throw 'QA database must match ai_profit_qa_* or ai_profit_test_*; production-like names are rejected.'
  }
  if ($MySqlDatabase -match '(?i)(prod|production|live|final)') {
    throw 'Production-like QA database names are rejected.'
  }
  if ($MySqlUsername.Trim().Equals('root', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The MySQL root account is forbidden for QA release scripts.'
  }
}

function Assert-QAReleaseRedisTarget {
  param(
    [Parameter(Mandatory = $true)][string]$RedisHost,
    [Parameter(Mandatory = $true)][int]$RedisPort
  )

  # The QA start script runs a local candidate and cannot establish that an arbitrary remote
  # Redis endpoint is not production. Fail closed unless it is an isolated loopback store.
  if ($RedisHost -notin @('127.0.0.1', 'localhost', '::1')) {
    throw 'QA Redis for this local candidate must use an isolated loopback host; remote targets are not accepted.'
  }
  if ($RedisPort -eq 3307 -or $RedisPort -lt 1024 -or $RedisPort -gt 65535) {
    throw 'QA Redis port is protected or invalid.'
  }
}

function Get-QAMysqlCommand {
  $command = Get-Command mysql.exe -ErrorAction SilentlyContinue
  if ($null -eq $command) { $command = Get-Command mysql -ErrorAction SilentlyContinue }
  if ($null -eq $command) { throw 'mysql client is required for an explicitly authorized QA reset.' }
  return if ($command.Source) { $command.Source } else { $command.Path }
}

function Get-QAMysqldumpCommand {
  $command = Get-Command mysqldump.exe -ErrorAction SilentlyContinue
  if ($null -eq $command) { $command = Get-Command mysqldump -ErrorAction SilentlyContinue }
  if ($null -eq $command) { throw 'mysqldump client is required for an explicitly authorized QA reset.' }
  return if ($command.Source) { $command.Source } else { $command.Path }
}

function Invoke-QAMysql {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlUsername,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$Database = '',
    [Parameter(Mandatory = $true)][string]$Sql
  )

  $mysql = Get-QAMysqlCommand
  $arguments = @(
    '--protocol=TCP', "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUsername",
    '--batch', '--skip-column-names', '--raw', "--execute=$Sql"
  )
  if (-not [string]::IsNullOrWhiteSpace($Database)) { $arguments += "--database=$Database" }
  $startInfo = New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName = $mysql
  $startInfo.Arguments = ($arguments | ForEach-Object {
      if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
  $process = New-Object Diagnostics.Process
  try {
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Could not start the mysql client.' }
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
      throw "QA MySQL command failed with exit code $($process.ExitCode): $stderr"
    }
    return $stdout
  }
  finally {
    $Password = $null
    if ($process) { $process.Dispose() }
  }
}

function Invoke-QAMysqldump {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlUsername,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$ResultFile
  )

  $dump = Get-QAMysqldumpCommand
  $arguments = @(
    '--protocol=TCP', "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUsername",
    '--single-transaction', '--routines', '--events', '--databases', "--result-file=$ResultFile", $Database
  )
  $startInfo = New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName = $dump
  $startInfo.Arguments = ($arguments | ForEach-Object {
      if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
  $process = New-Object Diagnostics.Process
  try {
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Could not start mysqldump.' }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
      throw "QA MySQL backup failed with exit code $($process.ExitCode): $stderr"
    }
  }
  finally {
    $Password = $null
    if ($process) { $process.Dispose() }
  }
}

function Invoke-QAMysqlScriptFile {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlUsername,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$ScriptPath
  )

  if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) {
    throw 'QA baseline SQL input file is unavailable.'
  }
  $mysql = Get-QAMysqlCommand
  $arguments = @(
    '--protocol=TCP', "--host=$MySqlHost", "--port=$MySqlPort", "--user=$MySqlUsername",
    '--binary-mode', '--default-character-set=utf8mb4', "--database=$Database"
  )
  $startInfo = New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName = $mysql
  $startInfo.Arguments = ($arguments | ForEach-Object {
      if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    }) -join ' '
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
  $process = New-Object Diagnostics.Process
  $inputStream = $null
  try {
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Could not start the mysql client for the approved QA baseline.' }
    $inputStream = [IO.File]::OpenRead($ScriptPath)
    $inputStream.CopyTo($process.StandardInput.BaseStream)
    $process.StandardInput.Close()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
      throw "QA baseline import failed with exit code $($process.ExitCode): $stderr"
    }
  }
  finally {
    $Password = $null
    if ($inputStream) { $inputStream.Dispose() }
    if ($process) { $process.Dispose() }
  }
}

function Get-QAFlywayVersion {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlUsername,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][string]$Database
  )

  $sql = "select coalesce(max(version), '') from flyway_schema_history where success = 1"
  try {
    return (Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $Password -Database $Database -Sql $sql).Trim()
  }
  catch {
    return $null
  }
}

function Get-QATableCounts {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlUsername,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][string]$Database
  )

  $escapedDatabase = $Database.Replace("'", "''")
  $tables = @(Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $Password -Database $Database -Sql "select table_name from information_schema.tables where table_schema = '$escapedDatabase' and table_type = 'BASE TABLE' order by table_name")
  $rows = @()
  foreach ($table in $tables) {
    $name = $table.Trim()
    if ([string]::IsNullOrWhiteSpace($name)) { continue }
    if ($name -notmatch '^[A-Za-z0-9_]+$') { throw 'Unexpected table name returned by QA database metadata.' }
    $countText = (Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $Password -Database $Database -Sql "select count(*) from ``$name``").Trim()
    $rows += [pscustomobject]@{ table = $name; rows = [int64]$countText }
  }
  return @($rows)
}

function Get-QATableNames {
  param(
    [Parameter(Mandatory = $true)][string]$MySqlHost,
    [Parameter(Mandatory = $true)][int]$MySqlPort,
    [Parameter(Mandatory = $true)][string]$MySqlUsername,
    [Parameter(Mandatory = $true)][string]$Password,
    [Parameter(Mandatory = $true)][string]$Database
  )

  $escapedDatabase = $Database.Replace("'", "''")
  $tables = @(Invoke-QAMysql -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlUsername $MySqlUsername -Password $Password -Database $Database -Sql "select table_name from information_schema.tables where table_schema = '$escapedDatabase' and table_type = 'BASE TABLE' order by table_name")
  return @($tables | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

Export-ModuleMember -Function @(
  'New-QAReleaseRun', 'Write-QAReleaseJson', 'Get-QAReleaseSecret',
  'Assert-QAReleaseDatabaseTarget', 'Invoke-QAMysql', 'Invoke-QAMysqldump',
  'Assert-QAReleaseRedisTarget', 'Invoke-QAMysqlScriptFile', 'Get-QAFlywayVersion', 'Get-QATableCounts', 'Get-QATableNames'
)
