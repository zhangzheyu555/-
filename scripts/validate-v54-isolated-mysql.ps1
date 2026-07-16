[CmdletBinding()]
param(
  [ValidateRange(1024, 65535)]
  [int]$MySqlPort = 3308,
  [ValidateRange(1024, 65535)]
  [int]$V53ServerPort = 18101,
  [ValidateRange(1024, 65535)]
  [int]$V54ServerPort = 18102,
  [string]$ReportDirectory,
  [switch]$KeepTemporaryArtifacts
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$backendSource = Join-Path $projectRoot 'backend'
$mysqlBase = 'C:\Program Files\MySQL\MySQL Server 8.0'
$mysqld = Join-Path $mysqlBase 'bin\mysqld.exe'
$mysql = Join-Path $mysqlBase 'bin\mysql.exe'
$mysqldump = Join-Path $mysqlBase 'bin\mysqldump.exe'
$java = (Get-Command java.exe -ErrorAction Stop).Source
$maven = (Get-Command mvn.cmd,mvn -ErrorAction Stop | Select-Object -First 1).Source
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$temporaryRoot = Join-Path $env:TEMP "ai-profit-v54-isolated-$timestamp"
$temporaryPrefix = ([IO.Path]::GetFullPath((Join-Path $env:TEMP 'ai-profit-v54-isolated-'))).TrimEnd('\')
$reportRoot = if ($ReportDirectory) { $ReportDirectory } else { Join-Path $projectRoot 'output\release-evidence' }
$reportRoot = [IO.Path]::GetFullPath($reportRoot)
$reportFile = Join-Path $reportRoot "isolated-v54-mysql-$timestamp.json"
$reportMarkdown = Join-Path $reportRoot "isolated-v54-mysql-$timestamp.md"
$mysqlProcess = $null
$backendProcess = $null
$results = [System.Collections.Generic.List[object]]::new()
$isolatedAppUser = 'v54_isolated_validator'
$isolatedAppPassword = "v54$([Guid]::NewGuid().ToString('N'))"

function Add-Result {
  param([Parameter(Mandatory)][string]$Stage, [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'BLOCKED')][string]$Status, [Parameter(Mandatory)][string]$Evidence)
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
  if ($process.ExitCode -ne 0) {
    throw "$Label failed with exit code $($process.ExitCode). Inspect the isolated evidence logs without copying credentials."
  }
}

function ConvertTo-IsolatedProcessArgumentLine {
  param([Parameter(Mandatory)][string[]]$Arguments)

  # Start-Process on Windows PowerShell 5.1 flattens an argument array and loses
  # the boundary around values such as --basedir=C:\Program Files\....  Build one
  # quoted command-line string instead so mysqld receives each option intact.
  @($Arguments | ForEach-Object {
    if ($_ -match '[\s"]') {
      if ($_ -match '"') { throw 'Isolated process arguments must not contain a double quote.' }
      '"{0}"' -f $_
    }
    else {
      $_
    }
  }) -join ' '
}

function Remove-InheritedSensitiveEnvironment {
  param([Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo)
  foreach ($name in @(
      'DEEPSEEK_API_KEY', 'DEEPSEEK_KEY', 'OPENAI_API_KEY', 'EMPLOYEE_ASSISTANT_API_TOKEN',
      'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USERNAME', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD',
      'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
      'APP_ENV'
    )) {
    # Do not retrieve the inherited value: isolated child processes receive no runtime database or model credentials.
    [void]$StartInfo.EnvironmentVariables.Remove($name)
  }
}

function Complete-IsolatedProcess {
  param([Parameter(Mandatory)][Diagnostics.Process]$Process)
  $Process.WaitForExit()
  foreach ($taskName in @('IsolatedStdoutTask', 'IsolatedStderrTask')) {
    $task = $Process.PSObject.Properties[$taskName].Value
    if ($task) { [void]$task.GetAwaiter().GetResult() }
  }
  foreach ($streamName in @('IsolatedStdoutStream', 'IsolatedStderrStream')) {
    $stream = $Process.PSObject.Properties[$streamName].Value
    if ($stream) { $stream.Dispose() }
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

function Invoke-MySql {
  param(
    [AllowEmptyString()][string]$Sql = '',
    [string]$Database,
    [string]$InputFile
  )
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
    if ($InputFile) {
      $safeInput = Assert-IsolatedPath $InputFile
      $inputStream = [IO.File]::OpenRead($safeInput)
      try {
        $inputStream.CopyTo($process.StandardInput.BaseStream)
      }
      finally { $inputStream.Dispose() }
    }
    elseif ($Sql) {
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

function Get-FlywayVersion {
  param([Parameter(Mandatory)][string]$Database)
  [int](Invoke-MySql -Database $Database -Sql "select coalesce(max(cast(version as unsigned)), 0) from flyway_schema_history where success = 1;")
}

function Get-TableCount {
  param([Parameter(Mandatory)][string]$Database)
  [int](Invoke-MySql -Sql "select count(*) from information_schema.tables where table_schema = '$Database';")
}

function Copy-BackendSource {
  param([Parameter(Mandatory)][string]$Destination)
  $safeDestination = Assert-IsolatedPath $Destination
  [void](New-Item -ItemType Directory -Force -Path $safeDestination)
  & robocopy $backendSource $safeDestination /E /XD target .git /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -gt 7) { throw "Isolated backend source copy failed with robocopy code $LASTEXITCODE." }
  $global:LASTEXITCODE = 0
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
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
      if ($backendProcess.HasExited) { throw "$Label backend exited before the health endpoint was available." }
      try {
        $response = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/api/health" -TimeoutSec 2
        $data = if ($response.data) { $response.data } else { $response }
        if ($data.status -eq 'UP') { return $data }
      }
      catch { Start-Sleep -Seconds 1 }
    }
    throw "$Label backend did not become healthy within 90 seconds."
  }
  finally {
    if ($backendProcess -and -not $backendProcess.HasExited) {
      Stop-Process -Id $backendProcess.Id -ErrorAction SilentlyContinue
    }
    if ($backendProcess) { Complete-IsolatedProcess -Process $backendProcess }
    $backendProcess = $null
  }
}

function Invoke-IsolatedDump {
  param([Parameter(Mandatory)][string]$Database, [Parameter(Mandatory)][string]$DumpPath)
  $safeDump = Assert-IsolatedPath $DumpPath
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = $mysqldump
  $info.Arguments = "--default-character-set=utf8mb4 --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=root --no-tablespaces --skip-comments $Database"
  $info.UseShellExecute = $false
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.CreateNoWindow = $true
  $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
  Remove-InheritedSensitiveEnvironment -StartInfo $info
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $info
  try {
    [void]$process.Start()
    $content = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd().Trim()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Isolated mysqldump failed: $errorOutput" }
    [IO.File]::WriteAllText($safeDump, $content, [Text.UTF8Encoding]::new($false))
    if ((Get-Item -LiteralPath $safeDump).Length -le 0) { throw 'Isolated backup dump is empty.' }
  }
  finally {
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
  }
}

function Stop-IsolatedMySql {
  param(
    [Diagnostics.Process]$Process,
    [Parameter(Mandatory)][string]$TemporaryRoot
  )

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
  if ($Process) { Complete-IsolatedProcess -Process $Process }
}

try {
  foreach ($port in @($MySqlPort, $V53ServerPort, $V54ServerPort)) {
    if ($port -in @(3307, 18081)) { throw "This validator intentionally refuses reserved port $port." }
  }
  if ((@($MySqlPort, $V53ServerPort, $V54ServerPort) | Select-Object -Unique).Count -ne 3) {
    throw 'Isolated MySQL, V53 backend, and V54 backend ports must all differ.'
  }
  foreach ($tool in @($mysqld, $mysql, $mysqldump, $backendSource)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Required isolated validation input is missing: $tool" }
  }
  Assert-FreePort -Port $MySqlPort -Label 'Isolated MySQL'
  Assert-FreePort -Port $V53ServerPort -Label 'V53 backend'
  Assert-FreePort -Port $V54ServerPort -Label 'V54 backend'
  [void](New-Item -ItemType Directory -Path $temporaryRoot -Force)
  [void](Assert-IsolatedPath $temporaryRoot)
  [void](New-Item -ItemType Directory -Path $reportRoot -Force)

  $dataDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-data')
  $temporaryDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-tmp')
  [void](New-Item -ItemType Directory -Path $temporaryDirectory -Force)
  Invoke-ProcessChecked -FilePath $mysqld -Arguments @('--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", '--initialize-insecure') -WorkingDirectory $temporaryRoot -Label 'mysql-initialize'
  $mysqlStdout = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stdout.log')
  $mysqlStderr = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stderr.log')
  $mysqlProcess = Start-IsolatedProcess -FilePath $mysqld -Arguments @('--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", "--tmpdir=$temporaryDirectory", "--port=$MySqlPort", '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin', '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci') -WorkingDirectory $temporaryRoot -RedirectStandardOutput $mysqlStdout -RedirectStandardError $mysqlStderr
  for ($attempt = 0; $attempt -lt 45; $attempt++) {
    if ($mysqlProcess.HasExited) { throw 'Isolated MySQL exited before it accepted TCP connections.' }
    try {
      $null = Invoke-MySql -Sql 'select 1;'
      break
    }
    catch {
      if ($attempt -eq 44) { throw 'Isolated MySQL did not accept TCP connections within 45 seconds.' }
      Start-Sleep -Seconds 1
    }
  }
  Add-Result -Stage 'Isolated MySQL startup' -Status 'PASS' -Evidence "Fresh MySQL 8 instance listening only on 127.0.0.1:$MySqlPort."

  $emptyDatabase = 'ai_profit_test_v54_empty'
  $upgradeDatabase = 'ai_profit_test_v54_upgrade'
  $restoreDatabase = 'ai_profit_test_v54_restore'
  Invoke-MySql -Sql "create database $emptyDatabase character set utf8mb4 collate utf8mb4_unicode_ci; create database $upgradeDatabase character set utf8mb4 collate utf8mb4_unicode_ci; create database $restoreDatabase character set utf8mb4 collate utf8mb4_unicode_ci;" | Out-Null
  Invoke-MySql -Sql "create user '$isolatedAppUser'@'localhost' identified with mysql_native_password by '$isolatedAppPassword'; create user '$isolatedAppUser'@'127.0.0.1' identified with mysql_native_password by '$isolatedAppPassword'; grant all privileges on $emptyDatabase.* to '$isolatedAppUser'@'localhost'; grant all privileges on $upgradeDatabase.* to '$isolatedAppUser'@'localhost'; grant all privileges on $restoreDatabase.* to '$isolatedAppUser'@'localhost'; grant all privileges on $emptyDatabase.* to '$isolatedAppUser'@'127.0.0.1'; grant all privileges on $upgradeDatabase.* to '$isolatedAppUser'@'127.0.0.1'; grant all privileges on $restoreDatabase.* to '$isolatedAppUser'@'127.0.0.1'; flush privileges;" | Out-Null

  $v54Source = Assert-IsolatedPath (Join-Path $temporaryRoot 'backend-v54')
  $v53Source = Assert-IsolatedPath (Join-Path $temporaryRoot 'backend-v53')
  Copy-BackendSource -Destination $v54Source
  Copy-BackendSource -Destination $v53Source
  $v53V54Migration = Assert-IsolatedPath (Join-Path $v53Source 'src\main\resources\db\migration\V54__warehouse_return_receive_warehouse_snapshot.sql')
  if (-not (Test-Path -LiteralPath $v53V54Migration)) { throw 'The V53 baseline copy is missing V54 before isolation; cannot create an upgrade rehearsal.' }
  Remove-Item -LiteralPath $v53V54Migration -Force
  $v53Jar = Build-Backend -SourceDirectory $v53Source -Label 'build-v53-baseline'
  $v54Jar = Build-Backend -SourceDirectory $v54Source -Label 'build-v54-candidate'
  Add-Result -Stage 'Independent candidate builds' -Status 'PASS' -Evidence 'Built separate V53 baseline and V54 candidate JARs outside the workspace.'

  $emptyHealth = Start-BackendMigration -Jar $v54Jar -Database $emptyDatabase -ServerPort $V54ServerPort -Label 'empty-v54'
  $emptyVersion = Get-FlywayVersion -Database $emptyDatabase
  if ($emptyVersion -ne 54 -or (Get-TableCount -Database $emptyDatabase) -le 0) { throw "Empty MySQL migration did not reach V54 with a schema." }
  Add-Result -Stage 'Empty MySQL migration' -Status 'PASS' -Evidence "Fresh schema reached Flyway V$emptyVersion."

  $v53Health = Start-BackendMigration -Jar $v53Jar -Database $upgradeDatabase -ServerPort $V53ServerPort -Label 'upgrade-v53'
  $v53Version = Get-FlywayVersion -Database $upgradeDatabase
  if ($v53Version -ne 53) { throw "V53 baseline did not stop at Flyway V53 (found V$v53Version)." }
  $v54Health = Start-BackendMigration -Jar $v54Jar -Database $upgradeDatabase -ServerPort $V54ServerPort -Label 'upgrade-v54'
  $upgradeVersion = Get-FlywayVersion -Database $upgradeDatabase
  if ($upgradeVersion -ne 54) { throw "V53-to-V54 upgrade did not reach Flyway V54 (found V$upgradeVersion)." }
  Add-Result -Stage 'V53 to V54 upgrade' -Status 'PASS' -Evidence 'Separate V53 baseline upgraded to V54 on the same isolated MySQL database.'

  $dump = Assert-IsolatedPath (Join-Path $temporaryRoot 'v54-upgrade.sql')
  Invoke-IsolatedDump -Database $upgradeDatabase -DumpPath $dump
  Invoke-MySql -Database $restoreDatabase -InputFile $dump | Out-Null
  $restoreVersion = Get-FlywayVersion -Database $restoreDatabase
  $upgradeTables = Get-TableCount -Database $upgradeDatabase
  $restoreTables = Get-TableCount -Database $restoreDatabase
  if ($restoreVersion -ne 54 -or $restoreTables -ne $upgradeTables) { throw 'Isolated V54 backup restore did not preserve Flyway version and table count.' }
  Add-Result -Stage 'Backup restore rehearsal' -Status 'PASS' -Evidence "Restored V54 schema with $restoreTables tables and Flyway V$restoreVersion."

  $sourceHash = (Get-FileHash -LiteralPath $v54Jar -Algorithm SHA256).Hash
  $evidence = [ordered]@{
    schema = 'ai-profit-os-isolated-v54-mysql-evidence/v1'
    generatedAt = (Get-Date).ToString('o')
    endpoint = "127.0.0.1:$MySqlPort"
    productionPortTouched = $false
    latestFlyway = 54
    candidateJarSha256 = $sourceHash
    results = @($results)
    limitations = @('No production data, credentials, or 3307 service were used.', 'This is an isolated schema migration and restore rehearsal, not a production restore.')
  }
  $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportFile -Encoding UTF8
  $lines = @('# Isolated MySQL V54 validation', '', "- Generated: $($evidence.generatedAt)", "- Endpoint: $($evidence.endpoint)", '- Production 3307 touched: no', "- Candidate JAR SHA-256: $sourceHash", '', '| Stage | Status | Evidence |', '|---|---|---|')
  foreach ($result in $results) { $lines += "| $($result.stage) | $($result.status) | $($result.evidence -replace '\|', '\|') |" }
  $lines += @('', '## Limitations', '', '- No production data, credentials, or 3307 service were used.', '- This is an isolated schema migration and restore rehearsal, not a production restore.')
  Set-Content -LiteralPath $reportMarkdown -Value $lines -Encoding UTF8
  Write-Host "PASS - isolated evidence written to $reportFile" -ForegroundColor Green
}
catch {
  Add-Result -Stage 'Isolated V54 validation' -Status 'FAIL' -Evidence $_.Exception.Message
  if (-not (Test-Path -LiteralPath $reportRoot)) { [void](New-Item -ItemType Directory -Path $reportRoot -Force) }
  [ordered]@{ schema = 'ai-profit-os-isolated-v54-mysql-evidence/v1'; generatedAt = (Get-Date).ToString('o'); productionPortTouched = $false; results = @($results) } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportFile -Encoding UTF8
  throw
}
finally {
  Stop-IsolatedMySql -Process $mysqlProcess -TemporaryRoot $temporaryRoot
  $mysqlProcess = $null
  if ((Test-Path -LiteralPath $temporaryRoot) -and -not $KeepTemporaryArtifacts) {
    $safeTemporaryRoot = Assert-IsolatedPath $temporaryRoot
    for ($attempt = 1; $attempt -le 4; $attempt++) {
      try {
        Remove-Item -LiteralPath $safeTemporaryRoot -Recurse -Force
        break
      }
      catch {
        if ($attempt -eq 4) { throw }
        Start-Sleep -Milliseconds 500
      }
    }
  }
}
