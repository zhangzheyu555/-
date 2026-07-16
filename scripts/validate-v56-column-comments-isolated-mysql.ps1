[CmdletBinding()]
param(
  [ValidateRange(1024, 65535)]
  [int]$MySqlPort = 3310,
  [ValidateRange(1024, 65535)]
  [int]$V55ServerPort = 18111,
  [ValidateRange(1024, 65535)]
  [int]$V56ServerPort = 18112,
  [string]$ReportDirectory,
  [switch]$KeepTemporaryArtifacts
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendSource = Join-Path $projectRoot 'backend'
$mysqlBase = 'C:\Program Files\MySQL\MySQL Server 8.0'
$mysqld = Join-Path $mysqlBase 'bin\mysqld.exe'
$mysql = Join-Path $mysqlBase 'bin\mysql.exe'
$java = (Get-Command java.exe -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd,mvn -ErrorAction Stop | Select-Object -First 1).Source
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$temporaryRoot = Join-Path $env:TEMP "ai-profit-v56-comments-isolated-$timestamp"
$temporaryPrefix = ([IO.Path]::GetFullPath((Join-Path $env:TEMP 'ai-profit-v56-comments-isolated-'))).TrimEnd('\')
$reportRoot = if ($ReportDirectory) { $ReportDirectory } else { Join-Path $projectRoot 'output\release-evidence' }
$reportRoot = [IO.Path]::GetFullPath($reportRoot)
$reportFile = Join-Path $reportRoot "isolated-v56-column-comments-$timestamp.json"
$reportMarkdown = Join-Path $reportRoot "isolated-v56-column-comments-$timestamp.md"
$v55SignatureFile = Join-Path $reportRoot "isolated-v56-column-signature-v55-$timestamp.jsonl"
$v56SignatureFile = Join-Path $reportRoot "isolated-v56-column-signature-v56-$timestamp.jsonl"
$mysqlProcess = $null
$results = [System.Collections.Generic.List[object]]::new()
$isolatedAppUser = "v56_$([Guid]::NewGuid().ToString('N').Substring(0, 16))"
$isolatedAppPassword = "V56$([Guid]::NewGuid().ToString('N'))"
$expectedBusinessTables = 93
$expectedBusinessColumns = 1206

function Add-Result {
  param(
    [Parameter(Mandatory)][string]$Stage,
    [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'BLOCKED')][string]$Status,
    [Parameter(Mandatory)][string]$Evidence
  )
  $results.Add([pscustomobject]@{ stage = $Stage; status = $Status; evidence = $Evidence })
  $colour = if ($Status -eq 'PASS') { 'Green' } elseif ($Status -eq 'BLOCKED') { 'Yellow' } else { 'Red' }
  Write-Host ("[{0}] {1}: {2}" -f $Status, $Stage, $Evidence) -ForegroundColor $colour
}

function Assert-IsolatedPath {
  param([Parameter(Mandatory)][string]$Path)
  $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
  if (-not $full.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing a file operation outside the isolated temporary root: $full"
  }
  $full
}

function Assert-FreePort {
  param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Label)
  if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "$Label port $Port is already in use; no existing process will be stopped."
  }
}

function ConvertTo-IsolatedProcessArgumentLine {
  param([Parameter(Mandatory)][string[]]$Arguments)
  @($Arguments | ForEach-Object {
    if ($_ -match '[\s"]') {
      if ($_ -match '"') { throw 'Isolated process arguments must not contain a double quote.' }
      '"{0}"' -f $_
    }
    else { $_ }
  }) -join ' '
}

function Remove-InheritedSensitiveEnvironment {
  param([Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo)
  foreach ($name in @(
      'DEEPSEEK_API_KEY', 'DEEPSEEK_KEY', 'OPENAI_API_KEY', 'EMPLOYEE_ASSISTANT_API_TOKEN',
      'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USERNAME', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD',
      'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
      'APP_ENV', 'APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD', 'APP_BOOTSTRAP_STORE_MANAGER_PASSWORD'
    )) {
    # Never retrieve an inherited value. The isolated child receives only explicit test credentials.
    [void]$StartInfo.EnvironmentVariables.Remove($name)
  }
}

function Complete-IsolatedProcess {
  param([Parameter(Mandatory)][Diagnostics.Process]$Process)
  $Process.WaitForExit()
  foreach ($taskName in @('IsolatedStdoutTask', 'IsolatedStderrTask')) {
    $property = $Process.PSObject.Properties[$taskName]
    if ($property -and $property.Value) { [void]$property.Value.GetAwaiter().GetResult() }
  }
  foreach ($streamName in @('IsolatedStdoutStream', 'IsolatedStderrStream')) {
    $property = $Process.PSObject.Properties[$streamName]
    if ($property -and $property.Value) { $property.Value.Dispose() }
  }
}

function Start-IsolatedProcess {
  param(
    [Parameter(Mandatory)][string]$FilePath,
    [Parameter(Mandatory)][string[]]$Arguments,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$RedirectStandardOutput,
    [Parameter(Mandatory)][string]$RedirectStandardError,
    [hashtable]$Environment = @{},
    [switch]$Wait
  )
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $FilePath
  $info.Arguments = ConvertTo-IsolatedProcessArgumentLine -Arguments $Arguments
  $info.WorkingDirectory = $WorkingDirectory
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  Remove-InheritedSensitiveEnvironment -StartInfo $info
  foreach ($item in $Environment.GetEnumerator()) {
    $info.EnvironmentVariables[$item.Key] = [string]$item.Value
  }
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $info
  [void]$process.Start()
  $stdoutStream = [IO.File]::Open($RedirectStandardOutput, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::Read)
  $stderrStream = [IO.File]::Open($RedirectStandardError, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::Read)
  $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
  $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
  $process | Add-Member -NotePropertyName IsolatedStdoutStream -NotePropertyValue $stdoutStream
  $process | Add-Member -NotePropertyName IsolatedStderrStream -NotePropertyValue $stderrStream
  $process | Add-Member -NotePropertyName IsolatedStdoutTask -NotePropertyValue $stdoutTask
  $process | Add-Member -NotePropertyName IsolatedStderrTask -NotePropertyValue $stderrTask
  if ($Wait) { Complete-IsolatedProcess -Process $process }
  $process
}

function Invoke-ProcessChecked {
  param(
    [Parameter(Mandatory)][string]$FilePath,
    [Parameter(Mandatory)][string[]]$Arguments,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$Label
  )
  $stdout = Assert-IsolatedPath (Join-Path $temporaryRoot ("$Label.stdout.log"))
  $stderr = Assert-IsolatedPath (Join-Path $temporaryRoot ("$Label.stderr.log"))
  $process = Start-IsolatedProcess -FilePath $FilePath -Arguments $Arguments -WorkingDirectory $WorkingDirectory `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Wait
  try {
    if ($process.ExitCode -ne 0) {
      throw "$Label failed with exit code $($process.ExitCode). Inspect isolated logs without copying credentials."
    }
  }
  finally { $process.Dispose() }
}

function Invoke-MySql {
  param([AllowEmptyString()][string]$Sql = '', [string]$Database)
  $arguments = "--default-character-set=utf8mb4 --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=root --batch --skip-column-names"
  if ($Database) {
    if ($Database -notmatch '^[A-Za-z0-9_]+$') { throw 'Unexpected isolated database name.' }
    $arguments += " --database=$Database"
  }
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $mysql
  $info.Arguments = $arguments
  $info.UseShellExecute = $false
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.CreateNoWindow = $true
  $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
  Remove-InheritedSensitiveEnvironment -StartInfo $info
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $info
  try {
    [void]$process.Start()
    if ($Sql) {
      $payload = [Text.UTF8Encoding]::new($false).GetBytes("$Sql`n")
      $process.StandardInput.BaseStream.Write($payload, 0, $payload.Length)
    }
    $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd().Trim()
    $errorOutput = $process.StandardError.ReadToEnd().Trim()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Isolated MySQL command failed: $errorOutput" }
    $output
  }
  finally {
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
  }
}

function Copy-BackendSource {
  param(
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$Destination
  )
  $safeDestination = Assert-IsolatedPath $Destination
  [void](New-Item -ItemType Directory -Force -Path $safeDestination)
  & robocopy $SourceDirectory $safeDestination /E /XD target .git /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -gt 7) { throw "Isolated backend source copy failed with robocopy code $LASTEXITCODE." }
  $global:LASTEXITCODE = 0
}

function Get-MaxMainMigrationVersion {
  param([Parameter(Mandatory)][string]$SourceDirectory)
  $migrationDirectory = Join-Path $SourceDirectory 'src\main\resources\db\migration'
  $versions = @(Get-ChildItem -LiteralPath $migrationDirectory -File -Filter 'V*__*.sql' | ForEach-Object {
    if ($_.Name -match '^V(\d+)__') { [int]$Matches[1] }
  })
  if ($versions.Count -eq 0) { throw 'No main MySQL Flyway migrations were found in the isolated source.' }
  ($versions | Measure-Object -Maximum).Maximum
}

function Build-Backend {
  param([Parameter(Mandatory)][string]$SourceDirectory, [Parameter(Mandatory)][string]$Label)
  Invoke-ProcessChecked -FilePath $maven -Arguments @('-q', '-DskipTests', 'package') -WorkingDirectory $SourceDirectory -Label $Label
  $jar = Join-Path $SourceDirectory 'target\store-profit-backend-0.1.0-SNAPSHOT.jar'
  if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "$Label did not produce an executable backend JAR." }
  $jar
}

function Start-BackendMigration {
  param(
    [Parameter(Mandatory)][string]$Jar,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][int]$ServerPort,
    [Parameter(Mandatory)][string]$Label
  )
  Assert-FreePort -Port $ServerPort -Label "$Label backend"
  $stdout = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.backend.stdout.log")
  $stderr = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.backend.stderr.log")
  $variables = [ordered]@{
    APP_ENV = 'TEST'
    APP_SEED_DEMO_ENABLED = 'false'
    APP_SEED_LEGACY_EMPLOYEE_ENABLED = 'false'
    APP_BOOTSTRAP_DEFAULT_USERS_ENABLED = 'false'
    APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED = 'false'
    APP_MIGRATION_AUTO_RUN = 'false'
    MYSQL_HOST = '127.0.0.1'
    MYSQL_PORT = [string]$MySqlPort
    MYSQL_DATABASE = $Database
    MYSQL_USERNAME = $isolatedAppUser
    MYSQL_PASSWORD = $isolatedAppPassword
    MYSQL_SSL_MODE = 'DISABLED'
    SERVER_PORT = [string]$ServerPort
    APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT = (Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.expense-supplements"))
  }
  $backendProcess = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $Jar) -WorkingDirectory $temporaryRoot `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Environment $variables
  try {
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
      if ($backendProcess.HasExited) { throw "$Label backend exited before the health endpoint was available." }
      try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/api/health" -TimeoutSec 2
        $data = if ($response.data) { $response.data } else { $response }
        if ($data.status -eq 'UP') { return $data }
      }
      catch { Start-Sleep -Seconds 1 }
    }
    throw "$Label backend did not become healthy within 120 seconds."
  }
  finally {
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($backendProcess) { Complete-IsolatedProcess -Process $backendProcess; $backendProcess.Dispose() }
  }
}

function Get-FlywayVersion {
  param([Parameter(Mandatory)][string]$Database)
  [int](Invoke-MySql -Database $Database -Sql 'select coalesce(max(cast(version as unsigned)), 0) from flyway_schema_history where success = 1;')
}

function Get-BusinessTableCount {
  param([Parameter(Mandatory)][string]$Database)
  [int](Invoke-MySql -Sql "select count(*) from information_schema.tables where table_schema = '$Database' and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history';")
}

function Get-BusinessColumnCount {
  param([Parameter(Mandatory)][string]$Database)
  [int](Invoke-MySql -Sql "select count(*) from information_schema.columns c join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name where c.table_schema = '$Database' and t.table_type = 'BASE TABLE' and c.table_name <> 'flyway_schema_history';")
}

function Get-ColumnSignature {
  param([Parameter(Mandatory)][string]$Database)
  $query = @"
select json_array(
  c.table_catalog, c.table_schema, c.table_name, c.column_name, c.ordinal_position,
  c.column_default, c.is_nullable, c.data_type, c.character_maximum_length,
  c.character_octet_length, c.numeric_precision, c.numeric_scale, c.datetime_precision,
  c.character_set_name, c.collation_name, c.column_type, c.column_key, c.extra,
  c.privileges, c.generation_expression, c.srs_id
)
from information_schema.columns c
join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
where c.table_schema = '$Database'
  and t.table_type = 'BASE TABLE'
  and c.table_name <> 'flyway_schema_history'
order by c.table_name, c.ordinal_position;
"@
  $raw = Invoke-MySql -Sql $query
  $lines = @($raw -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  $content = $lines -join "`n"
  $bytes = [Text.UTF8Encoding]::new($false).GetBytes($content)
  $sha = [Security.Cryptography.SHA256]::Create()
  try { $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '') }
  finally { $sha.Dispose() }
  [pscustomobject]@{ rowCount = $lines.Count; sha256 = $hash; content = $content }
}

function Get-ColumnCommentStats {
  param([Parameter(Mandatory)][string]$Database)
  $query = @"
select
  count(*) as total_rows,
  sum(case when trim(c.column_comment) = '' then 1 else 0 end) as empty_rows,
  sum(case when trim(c.column_comment) <> '' and c.column_comment regexp convert(0x5BE4B8802DE9BEA55D using utf8mb4) then 1 else 0 end) as chinese_rows,
  sum(case when trim(c.column_comment) = '' or c.column_comment not regexp convert(0x5BE4B8802DE9BEA55D using utf8mb4) then 1 else 0 end) as failed_rows
from information_schema.columns c
join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
where c.table_schema = '$Database'
  and t.table_type = 'BASE TABLE'
  and c.table_name <> 'flyway_schema_history';
"@
  $parts = @(Invoke-MySql -Sql $query) -split "`t"
  if ($parts.Count -ne 4) { throw 'Unexpected isolated column comment statistics result.' }
  [pscustomobject]@{
    totalRows = [int]$parts[0]
    emptyRows = [int]$parts[1]
    chineseRows = [int]$parts[2]
    failedRows = [int]$parts[3]
  }
}

function Assert-ExpectedV56Schema {
  param([Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$Label)
  $version = Get-FlywayVersion -Database $Database
  $tableCount = Get-BusinessTableCount -Database $Database
  $columnCount = Get-BusinessColumnCount -Database $Database
  $comments = Get-ColumnCommentStats -Database $Database
  if ($version -ne 56) { throw "$Label stopped at Flyway V$version instead of V56." }
  if ($tableCount -ne $expectedBusinessTables) { throw "$Label has $tableCount business tables; expected $expectedBusinessTables." }
  if ($columnCount -ne $expectedBusinessColumns) { throw "$Label has $columnCount business columns; expected $expectedBusinessColumns." }
  if ($comments.totalRows -ne $expectedBusinessColumns -or $comments.failedRows -ne 0 -or
      $comments.emptyRows -ne 0 -or $comments.chineseRows -ne $expectedBusinessColumns) {
    throw "$Label column comment assertion failed: total=$($comments.totalRows), empty=$($comments.emptyRows), Chinese=$($comments.chineseRows), failed=$($comments.failedRows)."
  }
  [pscustomobject]@{ version = $version; tableCount = $tableCount; columnCount = $columnCount; comments = $comments }
}

function Stop-IsolatedMySql {
  param([Diagnostics.Process]$Process, [Parameter(Mandatory)][string]$TemporaryRoot)
  $ownedProcessIds = [System.Collections.Generic.HashSet[int]]::new()
  if ($Process) { [void]$ownedProcessIds.Add($Process.Id) }
  $temporaryRootPattern = [regex]::Escape($TemporaryRoot)
  Get-CimInstance Win32_Process -Filter "Name = 'mysqld.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match $temporaryRootPattern } |
    ForEach-Object { [void]$ownedProcessIds.Add([int]$_.ProcessId) }
  foreach ($ownedProcessId in $ownedProcessIds) {
    $ownedProcess = Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue
    if ($ownedProcess -and -not $ownedProcess.HasExited) {
      Stop-Process -Id $ownedProcessId -Force -ErrorAction Stop
      for ($attempt = 1; $attempt -le 30; $attempt++) {
        if (-not (Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 500
      }
      if (Get-Process -Id $ownedProcessId -ErrorAction SilentlyContinue) {
        throw "Owned isolated MySQL process $ownedProcessId did not exit during cleanup."
      }
    }
  }
  if ($Process) { Complete-IsolatedProcess -Process $Process; $Process.Dispose() }
}

function Stop-IsolatedJava {
  param([Parameter(Mandatory)][string]$TemporaryRoot)
  $temporaryRootPattern = [regex]::Escape($TemporaryRoot)
  $ownedJava = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match $temporaryRootPattern })
  foreach ($ownedProcess in $ownedJava) {
    Stop-Process -Id ([int]$ownedProcess.ProcessId) -Force -ErrorAction Stop
  }
  foreach ($ownedProcess in $ownedJava) {
    for ($attempt = 1; $attempt -le 30; $attempt++) {
      if (-not (Get-Process -Id ([int]$ownedProcess.ProcessId) -ErrorAction SilentlyContinue)) { break }
      Start-Sleep -Milliseconds 500
    }
    if (Get-Process -Id ([int]$ownedProcess.ProcessId) -ErrorAction SilentlyContinue) {
      throw "Owned isolated Java process $($ownedProcess.ProcessId) did not exit during cleanup."
    }
  }
}

try {
  $ports = @($MySqlPort, $V55ServerPort, $V56ServerPort)
  foreach ($port in $ports) {
    if ($port -in @(3307, 18081)) { throw "This validator intentionally refuses reserved port $port." }
  }
  if (($ports | Select-Object -Unique).Count -ne 3) { throw 'All isolated ports must differ.' }
  foreach ($tool in @($mysqld, $mysql, $backendSource)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required isolated validation input is missing: $tool" }
  }
  Assert-FreePort -Port $MySqlPort -Label 'Isolated MySQL'
  Assert-FreePort -Port $V55ServerPort -Label 'V55 backend'
  Assert-FreePort -Port $V56ServerPort -Label 'V56 backend'
  [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
  [void](Assert-IsolatedPath $temporaryRoot)
  [void](New-Item -ItemType Directory -Path $reportRoot -Force)

  $dataDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-data')
  $temporaryDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-tmp')
  [void](New-Item -ItemType Directory -Path $temporaryDirectory -Force)
  Invoke-ProcessChecked -FilePath $mysqld -Arguments @('--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", '--initialize-insecure') -WorkingDirectory $temporaryRoot -Label 'mysql-initialize'
  $mysqlStdout = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stdout.log')
  $mysqlStderr = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stderr.log')
  $mysqlProcess = Start-IsolatedProcess -FilePath $mysqld -Arguments @(
    '--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", "--tmpdir=$temporaryDirectory",
    "--port=$MySqlPort", '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin',
    '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci'
  ) -WorkingDirectory $temporaryRoot -RedirectStandardOutput $mysqlStdout -RedirectStandardError $mysqlStderr
  for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($mysqlProcess.HasExited) { throw 'Isolated MySQL exited before it accepted TCP connections.' }
    try { $null = Invoke-MySql -Sql 'select 1;'; break }
    catch {
      if ($attempt -eq 44) { throw 'Isolated MySQL did not accept TCP connections within 45 seconds.' }
      Start-Sleep -Seconds 1
    }
  }
  Add-Result -Stage 'Isolated MySQL startup' -Status 'PASS' -Evidence "Fresh MySQL 8 listens only on 127.0.0.1:$MySqlPort; reserved 3307/18081 were not touched."

  $upgradeDatabase = 'ai_profit_test_v56_upgrade'
  $emptyDatabase = 'ai_profit_test_v56_empty'
  Invoke-MySql -Sql "create database $upgradeDatabase character set utf8mb4 collate utf8mb4_unicode_ci; create database $emptyDatabase character set utf8mb4 collate utf8mb4_unicode_ci;" | Out-Null
  Invoke-MySql -Sql "create user '$isolatedAppUser'@'localhost' identified with mysql_native_password by '$isolatedAppPassword'; create user '$isolatedAppUser'@'127.0.0.1' identified with mysql_native_password by '$isolatedAppPassword'; grant all privileges on $upgradeDatabase.* to '$isolatedAppUser'@'localhost'; grant all privileges on $emptyDatabase.* to '$isolatedAppUser'@'localhost'; grant all privileges on $upgradeDatabase.* to '$isolatedAppUser'@'127.0.0.1'; grant all privileges on $emptyDatabase.* to '$isolatedAppUser'@'127.0.0.1'; flush privileges;" | Out-Null
  $globalPrivileges = [int](Invoke-MySql -Sql "select count(*) from information_schema.user_privileges where grantee in (concat(char(39), '$isolatedAppUser', char(39), '@', char(39), 'localhost', char(39)), concat(char(39), '$isolatedAppUser', char(39), '@', char(39), '127.0.0.1', char(39))) and privilege_type <> 'USAGE';")
  if ($globalPrivileges -ne 0) { throw 'Random isolated application account unexpectedly has global privileges.' }
  Add-Result -Stage 'Restricted random application account' -Status 'PASS' -Evidence 'Random credentials are scoped to the two temporary databases and were not written to evidence.'

  $v56Source = Assert-IsolatedPath (Join-Path $temporaryRoot 'backend-v56')
  $v55Source = Assert-IsolatedPath (Join-Path $temporaryRoot 'backend-v55')
  Copy-BackendSource -SourceDirectory $backendSource -Destination $v56Source
  Copy-BackendSource -SourceDirectory $v56Source -Destination $v55Source
  $candidateV56 = @(Get-ChildItem -LiteralPath (Join-Path $v56Source 'src') -Recurse -File -Filter 'V56__*.sql')
  if (-not ($candidateV56 | Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]resources[\\/]db[\\/]migration[\\/]' })) {
    throw 'The isolated candidate copy does not contain the MySQL V56 migration.'
  }
  $baselineV56 = @(Get-ChildItem -LiteralPath (Join-Path $v55Source 'src') -Recurse -File -Filter 'V56__*.sql')
  if ($baselineV56.Count -eq 0) { throw 'The V55 baseline copy contained no V56 migration to remove.' }
  foreach ($migration in $baselineV56) {
    $safeMigration = Assert-IsolatedPath $migration.FullName
    Remove-Item -LiteralPath $safeMigration -Force
  }
  $baselineMax = Get-MaxMainMigrationVersion -SourceDirectory $v55Source
  $candidateMax = Get-MaxMainMigrationVersion -SourceDirectory $v56Source
  if ($baselineMax -ne 55 -or $candidateMax -ne 56) {
    throw "Isolated migration split is invalid: baseline=V$baselineMax, candidate=V$candidateMax."
  }
  $v55Jar = Build-Backend -SourceDirectory $v55Source -Label 'build-v55-baseline'
  $v56Jar = Build-Backend -SourceDirectory $v56Source -Label 'build-v56-candidate'
  Add-Result -Stage 'Independent V55 and V56 builds' -Status 'PASS' -Evidence "Built one snapshot twice; removed $($baselineV56.Count) MySQL/H2 V56 resource(s) only from the V55 copy."

  $null = Start-BackendMigration -Jar $v55Jar -Database $upgradeDatabase -ServerPort $V55ServerPort -Label 'upgrade-v55-baseline'
  $baselineVersion = Get-FlywayVersion -Database $upgradeDatabase
  $baselineTables = Get-BusinessTableCount -Database $upgradeDatabase
  $baselineColumns = Get-BusinessColumnCount -Database $upgradeDatabase
  if ($baselineVersion -ne 55 -or $baselineTables -ne $expectedBusinessTables -or $baselineColumns -ne $expectedBusinessColumns) {
    throw "V55 baseline mismatch: version=$baselineVersion, tables=$baselineTables, columns=$baselineColumns."
  }
  $beforeSignature = Get-ColumnSignature -Database $upgradeDatabase
  if ($beforeSignature.rowCount -ne $expectedBusinessColumns) { throw 'V55 full column signature row count is invalid.' }
  [IO.File]::WriteAllText($v55SignatureFile, $beforeSignature.content, [Text.UTF8Encoding]::new($false))
  Add-Result -Stage 'V55 baseline signature' -Status 'PASS' -Evidence "Flyway V55 has $baselineTables business tables and $baselineColumns columns; signature SHA-256=$($beforeSignature.sha256)."

  $null = Start-BackendMigration -Jar $v56Jar -Database $upgradeDatabase -ServerPort $V56ServerPort -Label 'upgrade-v56-candidate'
  $upgradeValidation = Assert-ExpectedV56Schema -Database $upgradeDatabase -Label 'V55-to-V56 upgrade'
  Add-Result -Stage 'V56 upgraded comment coverage' -Status 'PASS' -Evidence 'Flyway V56 has 93 business tables, 1206 columns, 1206 non-empty Chinese comments, and failed rows=0.'
  $afterSignature = Get-ColumnSignature -Database $upgradeDatabase
  [IO.File]::WriteAllText($v56SignatureFile, $afterSignature.content, [Text.UTF8Encoding]::new($false))
  if ($beforeSignature.content -cne $afterSignature.content -or $beforeSignature.sha256 -cne $afterSignature.sha256) {
    throw "V56 changed information_schema.COLUMNS metadata outside COLUMN_COMMENT: before=$($beforeSignature.sha256), after=$($afterSignature.sha256)."
  }
  Add-Result -Stage 'V55 to V56 metadata-preserving upgrade' -Status 'PASS' -Evidence "Non-comment column signature stayed byte-identical; 1206/1206 comments are non-empty Chinese and failed rows=0."

  $null = Start-BackendMigration -Jar $v56Jar -Database $emptyDatabase -ServerPort $V56ServerPort -Label 'empty-v56-candidate'
  $emptyValidation = Assert-ExpectedV56Schema -Database $emptyDatabase -Label 'Empty V56 migration'
  Add-Result -Stage 'Empty MySQL V56 migration' -Status 'PASS' -Evidence 'Fresh schema reached V56 with 93 business tables, 1206 columns, and failed comment rows=0.'

  $candidateHash = (Get-FileHash -LiteralPath $v56Jar -Algorithm SHA256).Hash
  $evidence = [ordered]@{
    schema = 'ai-profit-os-isolated-v56-column-comments-evidence/v1'
    generatedAt = (Get-Date).ToString('o')
    endpoint = "127.0.0.1:$MySqlPort"
    productionPortsTouched = $false
    latestFlyway = 56
    expectedBusinessTables = $expectedBusinessTables
    expectedBusinessColumns = $expectedBusinessColumns
    v55SignatureSha256 = $beforeSignature.sha256
    v56SignatureSha256 = $afterSignature.sha256
    signatureRowCount = $afterSignature.rowCount
    upgradedFailedCommentRows = $upgradeValidation.comments.failedRows
    emptyFailedCommentRows = $emptyValidation.comments.failedRows
    candidateJarSha256 = $candidateHash
    signatureFiles = @([IO.Path]::GetFileName($v55SignatureFile), [IO.Path]::GetFileName($v56SignatureFile))
    results = @($results)
    limitations = @(
      'No production database, production credential, port 3307, or port 18081 was used.',
      'The validator compares every information_schema.COLUMNS field except COLUMN_COMMENT on one isolated database.'
    )
  }
  $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportFile -Encoding UTF8
  $lines = @(
    '# Isolated MySQL V56 column-comment validation', '',
    "- Generated: $($evidence.generatedAt)",
    "- Isolated endpoint: $($evidence.endpoint)",
    '- Production 3307/18081 touched: no',
    "- Flyway: V$($evidence.latestFlyway)",
    "- Business schema: $expectedBusinessTables tables / $expectedBusinessColumns columns",
    "- V55 signature: $($evidence.v55SignatureSha256)",
    "- V56 signature: $($evidence.v56SignatureSha256)",
    "- Failed comment rows: upgrade=$($evidence.upgradedFailedCommentRows), empty=$($evidence.emptyFailedCommentRows)",
    "- Candidate JAR SHA-256: $candidateHash", '',
    '| Stage | Status | Evidence |', '|---|---|---|'
  )
  foreach ($result in $results) { $lines += "| $($result.stage) | $($result.status) | $($result.evidence -replace '\|', '\|') |" }
  $lines += @('', '## Safety boundary', '', '- Temporary MySQL 8 and random database credentials only.', '- V1-V55 and the workspace V56 migration were not edited.', '- Only owned processes under the isolated temporary root are stopped during cleanup.')
  Set-Content -LiteralPath $reportMarkdown -Value $lines -Encoding UTF8
  Write-Host "PASS - isolated V56 evidence written to $reportFile" -ForegroundColor Green
}
catch {
  Add-Result -Stage 'Isolated V56 column-comment validation' -Status 'FAIL' -Evidence $_.Exception.Message
  if (-not (Test-Path -LiteralPath $reportRoot)) { [void](New-Item -ItemType Directory -Path $reportRoot -Force) }
  [ordered]@{
    schema = 'ai-profit-os-isolated-v56-column-comments-evidence/v1'
    generatedAt = (Get-Date).ToString('o')
    productionPortsTouched = $false
    results = @($results)
  } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportFile -Encoding UTF8
  throw
}
finally {
  Stop-IsolatedJava -TemporaryRoot $temporaryRoot
  Stop-IsolatedMySql -Process $mysqlProcess -TemporaryRoot $temporaryRoot
  $mysqlProcess = $null
  $isolatedAppPassword = $null
  if ((Test-Path -LiteralPath $temporaryRoot) -and -not $KeepTemporaryArtifacts) {
    $safeTemporaryRoot = Assert-IsolatedPath $temporaryRoot
    for ($attempt = 1; $attempt -le 4; $attempt++) {
      try { Remove-Item -LiteralPath $safeTemporaryRoot -Recurse -Force; break }
      catch {
        if ($attempt -eq 4) { throw }
        Start-Sleep -Milliseconds 500
      }
    }
  }
}
