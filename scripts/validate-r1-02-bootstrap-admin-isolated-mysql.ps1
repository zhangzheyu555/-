[CmdletBinding()]
param(
  [ValidateRange(0, 65535)]
  [int]$MySqlPort = 0,
  [ValidateRange(0, 65535)]
  [int]$ServerPort = 0,
  [string]$ReportDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar'
$mysqlBase = 'C:\Program Files\MySQL\MySQL Server 8.0'
$mysqld = Join-Path $mysqlBase 'bin\mysqld.exe'
$mysql = Join-Path $mysqlBase 'bin\mysql.exe'
$java = (Get-Command java.exe -ErrorAction Stop).Source
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runId = "$timestamp-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$temporaryRoot = Join-Path $env:TEMP "ai-profit-r1-02-isolated-$runId"
$temporaryPrefix = ([IO.Path]::GetFullPath((Join-Path $env:TEMP 'ai-profit-r1-02-isolated-'))).TrimEnd('\')
$allowedEvidenceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'output\release-evidence')).TrimEnd('\')
$reportRoot = if ($ReportDirectory) {
  [IO.Path]::GetFullPath($ReportDirectory)
}
else {
  Join-Path $allowedEvidenceRoot 'R1-02-20260715'
}
$reportFile = Join-Path $reportRoot "r1-02-isolated-mysql-$runId.json"
$reportMarkdown = Join-Path $reportRoot "r1-02-isolated-mysql-$runId.md"

$mysqlProcess = $null
$backendProcess = $null
$cliProcesses = [System.Collections.Generic.List[Diagnostics.Process]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$failureCode = $null
$cleanupComplete = $false
$ownedProcessesStopped = $false
$secretScanPassed = $false
$sourceDiffScanPassed = $false
$sourceDiffFilesScanned = 0
$evidenceFilesScanned = 0
$evidenceSelfCheck = $false
$scannedLogFiles = 0
$database = "ai_profit_qa_r102_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$appUser = "r102_$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$appPassword = "Db9!$([Guid]::NewGuid().ToString('N'))"
do {
  $bootstrapPassword = "Rz9!$([Guid]::NewGuid().ToString('N'))"
} while ($bootstrapPassword.ToLowerInvariant().Contains('123'))
$usernameA = "r102_boss_a_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$usernameB = "r102_boss_b_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$operatorName = 'R1-02 Isolated Operator'
$ticketNumber = "R102-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$auditReason = 'Isolated first BOSS provisioning validation'
$tenantId = 1
$tenantNameConfirm = $null
$candidateHash = $null
$flywayVersion = $null
$failedFlywayMigrations = $null
$counts = [ordered]@{}
$http = [ordered]@{}
$cliExitCodes = [ordered]@{}
$cliPortMonitor = [ordered]@{
  samples = 0
  listenersBefore = @()
  listenersAfter = @()
  ownedListenerObservations = @()
  passed = $false
}
$reservedListenersBefore = @()
$reservedListenersAfter = @()
$reservedListenersChanged = $false
$winnerUsername = $null
$winnerDisplayName = $null
$storedPasswordHash = $null
$token = $null
$auditText = ''

function Add-Result {
  param(
    [Parameter(Mandatory)][string]$Stage,
    [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL')][string]$Status,
    [Parameter(Mandatory)][string]$Evidence
  )
  $results.Add([pscustomobject]@{ stage = $Stage; status = $Status; evidence = $Evidence })
  $colour = if ($Status -eq 'PASS') { 'Green' } else { 'Red' }
  Write-Host ("[{0}] {1}: {2}" -f $Status, $Stage, $Evidence) -ForegroundColor $colour
}

function Assert-IsolatedPath {
  param([Parameter(Mandatory)][string]$Path)
  $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
  if (-not $full.StartsWith($temporaryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ISOLATED_PATH_REJECTED'
  }
  $full
}

function Assert-EvidencePath {
  param([Parameter(Mandatory)][string]$Path)
  $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
  if ($full -ne $allowedEvidenceRoot -and
      -not $full.StartsWith("$allowedEvidenceRoot\", [StringComparison]::OrdinalIgnoreCase)) {
    throw 'EVIDENCE_PATH_REJECTED'
  }
  $full
}

function Get-FreeIsolatedPort {
  param([int[]]$Excluded = @())
  for ($attempt = 0; $attempt -lt 200; $attempt++) {
    $candidate = Get-Random -Minimum 20000 -Maximum 60000
    if ($candidate -in $Excluded) { continue }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $candidate)
    try {
      $listener.Start()
      return $candidate
    }
    catch {
      continue
    }
    finally {
      $listener.Stop()
    }
  }
  throw 'NO_FREE_ISOLATED_PORT'
}

function Assert-FreePort {
  param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Label)
  if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "${Label}_PORT_IN_USE"
  }
}

function Get-ListenerSnapshot {
  @(
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
      Sort-Object LocalPort, LocalAddress, OwningProcess |
      ForEach-Object {
        [pscustomobject]@{
          address = [string]$_.LocalAddress
          port = [int]$_.LocalPort
          processId = [int]$_.OwningProcess
        }
      }
  )
}

function Get-ReservedListeners {
  $items = [System.Collections.Generic.List[object]]::new()
  foreach ($port in @(3306, 3307, 18081)) {
    foreach ($listener in @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
        Sort-Object OwningProcess, LocalAddress)) {
      $items.Add([pscustomobject]@{
        port = [int]$port
        address = [string]$listener.LocalAddress
        processId = [int]$listener.OwningProcess
      })
    }
  }
  @($items)
}

function ConvertTo-ArgumentLine {
  param([Parameter(Mandatory)][string[]]$Arguments)
  @($Arguments | ForEach-Object {
    if ($_ -match '[\s"]') {
      if ($_ -match '"') { throw 'PROCESS_ARGUMENT_REJECTED' }
      '"{0}"' -f $_
    }
    else { $_ }
  }) -join ' '
}

function Copy-Environment {
  param([Parameter(Mandatory)][hashtable]$Source)
  $copy = @{}
  foreach ($item in $Source.GetEnumerator()) {
    $copy[$item.Key] = [string]$item.Value
  }
  $copy
}

function Set-CleanEnvironment {
  param([Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo)
  $values = [ordered]@{
    'SystemRoot' = $env:SystemRoot
    'WINDIR' = $env:WINDIR
    'COMSPEC' = $env:COMSPEC
    'PATH' = $env:PATH
    'PATHEXT' = $env:PATHEXT
    'JAVA_HOME' = $env:JAVA_HOME
    'USERPROFILE' = $env:USERPROFILE
    'LOCALAPPDATA' = $env:LOCALAPPDATA
    'APPDATA' = $env:APPDATA
    'TEMP' = $temporaryRoot
    'TMP' = $temporaryRoot
  }
  $StartInfo.EnvironmentVariables.Clear()
  foreach ($item in $values.GetEnumerator()) {
    if (-not [string]::IsNullOrWhiteSpace([string]$item.Value)) {
      $StartInfo.EnvironmentVariables[$item.Key] = [string]$item.Value
    }
  }
}

function Complete-IsolatedProcess {
  param([Parameter(Mandatory)][Diagnostics.Process]$Process)
  $completed = $Process.PSObject.Properties['IsolatedCompleted']
  if ($completed -and $completed.Value) { return }
  $Process.WaitForExit()
  foreach ($taskName in @('IsolatedStdoutTask', 'IsolatedStderrTask')) {
    $property = $Process.PSObject.Properties[$taskName]
    if ($property -and $property.Value) { [void]$property.Value.GetAwaiter().GetResult() }
  }
  foreach ($streamName in @('IsolatedStdoutStream', 'IsolatedStderrStream')) {
    $property = $Process.PSObject.Properties[$streamName]
    if ($property -and $property.Value) { $property.Value.Dispose() }
  }
  if ($completed) { $completed.Value = $true }
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
  $info.Arguments = ConvertTo-ArgumentLine -Arguments $Arguments
  $info.WorkingDirectory = $WorkingDirectory
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  Set-CleanEnvironment -StartInfo $info
  foreach ($item in $Environment.GetEnumerator()) {
    $info.EnvironmentVariables[$item.Key] = [string]$item.Value
  }
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $info
  [void]$process.Start()
  $stdoutStream = [IO.File]::Open(
    $RedirectStandardOutput, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::Read)
  $stderrStream = [IO.File]::Open(
    $RedirectStandardError, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::Read)
  $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
  $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
  $process | Add-Member -NotePropertyName IsolatedStdoutStream -NotePropertyValue $stdoutStream
  $process | Add-Member -NotePropertyName IsolatedStderrStream -NotePropertyValue $stderrStream
  $process | Add-Member -NotePropertyName IsolatedStdoutTask -NotePropertyValue $stdoutTask
  $process | Add-Member -NotePropertyName IsolatedStderrTask -NotePropertyValue $stderrTask
  $process | Add-Member -NotePropertyName IsolatedCompleted -NotePropertyValue $false
  if ($Wait) { Complete-IsolatedProcess -Process $process }
  $process
}

function Invoke-ProcessChecked {
  param(
    [Parameter(Mandatory)][string]$FilePath,
    [Parameter(Mandatory)][string[]]$Arguments,
    [Parameter(Mandatory)][string]$Label
  )
  $stdout = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stdout.log")
  $stderr = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stderr.log")
  $process = Start-IsolatedProcess -FilePath $FilePath -Arguments $Arguments -WorkingDirectory $temporaryRoot `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Wait
  try {
    if ($process.ExitCode -ne 0) { throw "${Label}_FAILED" }
  }
  finally { $process.Dispose() }
}

function Invoke-MySql {
  param([AllowEmptyString()][string]$Sql = '', [string]$Database)
  $arguments = "--default-character-set=utf8mb4 --protocol=TCP --host=127.0.0.1 --port=$MySqlPort --user=root --batch --skip-column-names"
  if ($Database) {
    if ($Database -notmatch '^[A-Za-z0-9_]+$') { throw 'UNEXPECTED_DATABASE_NAME' }
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
  Set-CleanEnvironment -StartInfo $info
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $info
  try {
    [void]$process.Start()
    if ($Sql) {
      $payload = [Text.UTF8Encoding]::new($false).GetBytes("$Sql`n")
      $process.StandardInput.BaseStream.Write($payload, 0, $payload.Length)
      [Array]::Clear($payload, 0, $payload.Length)
    }
    $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd().Trim()
    $null = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw 'ISOLATED_MYSQL_COMMAND_FAILED' }
    $output
  }
  finally {
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
  }
}

function Get-BootstrapCounts {
  $sql = @"
select
  (select count(*) from auth_user),
  (select count(*) from auth_user where role = 'BOSS'),
  (select count(*) from operation_log where action = 'first_boss_provisioned'),
  (select count(*) from auth_token),
  (select count(*) from auth_user where username = '$usernameA'),
  (select count(*) from auth_user where username = '$usernameB'),
  (select count(*) from operation_log);
"@
  $parts = @((Invoke-MySql -Database $database -Sql $sql) -split "`t")
  if ($parts.Count -ne 7) { throw 'UNEXPECTED_COUNT_RESULT' }
  [ordered]@{
    authUser = [int]$parts[0]
    boss = [int]$parts[1]
    firstBossAudit = [int]$parts[2]
    authToken = [int]$parts[3]
    candidateA = [int]$parts[4]
    candidateB = [int]$parts[5]
    operationLog = [int]$parts[6]
  }
}

function Assert-CountsEqual {
  param([Parameter(Mandatory)]$Expected, [Parameter(Mandatory)]$Actual)
  foreach ($name in @('authUser', 'boss', 'firstBossAudit', 'authToken', 'candidateA', 'candidateB', 'operationLog')) {
    if ([int]$Expected[$name] -ne [int]$Actual[$name]) { throw 'COUNTS_CHANGED_UNEXPECTEDLY' }
  }
}

function Invoke-Http {
  param(
    [Parameter(Mandatory)][string]$Method,
    [Parameter(Mandatory)][string]$Path,
    [string]$Json,
    [string]$BearerToken
  )
  Add-Type -AssemblyName System.Net.Http
  $client = [Net.Http.HttpClient]::new()
  $client.Timeout = [TimeSpan]::FromSeconds(10)
  $request = [Net.Http.HttpRequestMessage]::new(
    [Net.Http.HttpMethod]::new($Method), "http://127.0.0.1:$ServerPort$Path")
  if ($Json) {
    $request.Content = [Net.Http.StringContent]::new($Json, [Text.Encoding]::UTF8, 'application/json')
  }
  if ($BearerToken) {
    [void]$request.Headers.TryAddWithoutValidation('Authorization', "Bearer $BearerToken")
  }
  $response = $null
  try {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [pscustomobject]@{ status = [int]$response.StatusCode; body = $body }
  }
  finally {
    if ($response) { $response.Dispose() }
    $request.Dispose()
    $client.Dispose()
  }
}

function Assert-Status {
  param(
    [Parameter(Mandatory)]$Response,
    [Parameter(Mandatory)][int]$Expected,
    [Parameter(Mandatory)][string]$Label
  )
  if ($Response.status -ne $Expected) { throw "${Label}_HTTP_STATUS_FAILED" }
}

function Wait-ForBackendHealth {
  param([Parameter(Mandatory)][Diagnostics.Process]$Process)
  for ($attempt = 0; $attempt -lt 120; $attempt++) {
    if ($Process.HasExited) { throw 'BACKEND_EXITED_BEFORE_HEALTH' }
    try {
      $candidate = Invoke-Http -Method 'GET' -Path '/api/health'
      if ($candidate.status -eq 200) {
        $healthJson = $candidate.body | ConvertFrom-Json
        $healthData = if ($healthJson.data) { $healthJson.data } else { $healthJson }
        if ($healthData.status -eq 'UP') { return }
      }
    }
    catch { }
    Start-Sleep -Seconds 1
  }
  throw 'BACKEND_HEALTH_TIMEOUT'
}

function Stop-OwnedProcess {
  param([Diagnostics.Process]$Process)
  if (-not $Process) { return }
  try {
    if (-not $Process.HasExited) {
      Stop-Process -Id $Process.Id -Force -ErrorAction Stop
      for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (-not (Get-Process -Id $Process.Id -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 500
      }
    }
    Complete-IsolatedProcess -Process $Process
  }
  finally { $Process.Dispose() }
}

function Stop-OwnedMySqlProcess {
  param([Diagnostics.Process]$Process, [Parameter(Mandatory)][string]$OwnedRoot)
  $safeRoot = Assert-IsolatedPath $OwnedRoot
  $ownedProcessIds = [System.Collections.Generic.HashSet[int]]::new()
  if ($Process) { [void]$ownedProcessIds.Add($Process.Id) }
  foreach ($candidate in @(Get-CimInstance Win32_Process -Filter "Name = 'mysqld.exe'" -ErrorAction Stop)) {
    if ($candidate.CommandLine -and
        $candidate.CommandLine.IndexOf($safeRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
      [void]$ownedProcessIds.Add([int]$candidate.ProcessId)
    }
  }
  try {
    foreach ($processId in $ownedProcessIds) {
      if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $processId -Force -ErrorAction Stop
      }
    }
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
      $remaining = @($ownedProcessIds | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
      if ($remaining.Count -eq 0) { break }
      if ($attempt -eq 29) { throw 'OWNED_MYSQL_PROCESS_NOT_STOPPED' }
      Start-Sleep -Milliseconds 500
    }
    if ($Process) { Complete-IsolatedProcess -Process $Process }
  }
  finally {
    if ($Process) { $Process.Dispose() }
  }
}

function Wait-ForPortRelease {
  param([Parameter(Mandatory)][int]$Port)
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) { return }
    Start-Sleep -Milliseconds 250
  }
  throw 'OWNED_WEB_PORT_NOT_RELEASED'
}

function New-CliEnvironment {
  param(
    [Parameter(Mandatory)][hashtable]$BaseEnvironment,
    [Parameter(Mandatory)][string]$Username,
    [Parameter(Mandatory)][string]$DisplayName
  )
  $environment = Copy-Environment -Source $BaseEnvironment
  $environment['APP_BOOTSTRAP_ADMIN_ENABLED'] = 'true'
  $environment['APP_BOOTSTRAP_ADMIN_TENANT_ID'] = [string]$tenantId
  $environment['APP_BOOTSTRAP_ADMIN_TENANT_NAME_CONFIRM'] = $tenantNameConfirm
  $environment['APP_BOOTSTRAP_ADMIN_USERNAME'] = $Username
  $environment['APP_BOOTSTRAP_ADMIN_DISPLAY_NAME'] = $DisplayName
  $environment['APP_BOOTSTRAP_ADMIN_OPERATOR'] = $operatorName
  $environment['APP_BOOTSTRAP_ADMIN_TICKET'] = $ticketNumber
  $environment['APP_BOOTSTRAP_ADMIN_REASON'] = $auditReason
  $environment['APP_BOOTSTRAP_ADMIN_PASSWORD'] = $bootstrapPassword
  $environment
}

function Assert-CliMachineOutput {
  param(
    [Parameter(Mandatory)][int]$ExitCode,
    [Parameter(Mandatory)][string]$StdoutPath,
    [Parameter(Mandatory)][string]$StderrPath
  )
  $stdout = ([IO.File]::ReadAllText($StdoutPath, [Text.Encoding]::UTF8)).Trim()
  $stderr = ([IO.File]::ReadAllText($StderrPath, [Text.Encoding]::UTF8)).Trim()
  $expected = switch ($ExitCode) {
    0 { 'ADMIN_BOOTSTRAP_CREATED' }
    5 { 'ADMIN_BOOTSTRAP_ALREADY_INITIALIZED' }
    6 { 'ADMIN_BOOTSTRAP_CONCURRENT_FAILURE' }
    default { throw 'UNEXPECTED_CLI_EXIT_CODE' }
  }
  $actual = if ($ExitCode -eq 0) { $stdout } else { $stderr }
  $other = if ($ExitCode -eq 0) { $stderr } else { $stdout }
  if ($actual -cne $expected -or -not [string]::IsNullOrWhiteSpace($other)) {
    throw 'UNSTABLE_CLI_OUTPUT'
  }
}

function Monitor-CliProcesses {
  param(
    [Parameter(Mandatory)][Diagnostics.Process[]]$Processes,
    [Parameter(Mandatory)][object[]]$ListenersBefore
  )
  $observations = [System.Collections.Generic.List[object]]::new()
  $observationKeys = [System.Collections.Generic.HashSet[string]]::new()
  $samples = 0
  $deadline = (Get-Date).AddSeconds(90)
  while ($true) {
    $running = $false
    foreach ($process in $Processes) {
      if (-not $process.HasExited) { $running = $true }
      foreach ($listener in @(Get-NetTCPConnection -State Listen -OwningProcess $process.Id -ErrorAction SilentlyContinue)) {
        $key = "$($process.Id)|$($listener.LocalAddress)|$($listener.LocalPort)"
        if ($observationKeys.Add($key)) {
          $observations.Add([pscustomobject]@{
            processId = [int]$process.Id
            address = [string]$listener.LocalAddress
            port = [int]$listener.LocalPort
          })
        }
      }
    }
    $samples++
    if (-not $running) { break }
    if ((Get-Date) -ge $deadline) { throw 'CLI_PROCESS_TIMEOUT' }
    Start-Sleep -Milliseconds 25
  }
  [pscustomobject]@{
    samples = $samples
    listenersBefore = @($ListenersBefore)
    listenersAfter = @(Get-ListenerSnapshot)
    ownedListenerObservations = @($observations)
    passed = ($observations.Count -eq 0)
  }
}

function Invoke-SingleCli {
  param(
    [Parameter(Mandatory)][string]$Label,
    [Parameter(Mandatory)][hashtable]$Environment
  )
  $stdout = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stdout.log")
  $stderr = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stderr.log")
  $process = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $jar, '--admin-bootstrap') `
    -WorkingDirectory $temporaryRoot -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
    -Environment $Environment -Wait
  try {
    $exitCode = [int]$process.ExitCode
    Assert-CliMachineOutput -ExitCode $exitCode -StdoutPath $stdout -StderrPath $stderr
    $exitCode
  }
  finally { $process.Dispose() }
}

function Assert-NoSecretsInText {
  param(
    [AllowEmptyString()][string]$Text,
    [Parameter(Mandatory)][object[]]$Secrets
  )
  foreach ($secret in $Secrets) {
    $value = [string]$secret
    if (-not [string]::IsNullOrEmpty($value) -and
        $Text.IndexOf($value, [StringComparison]::Ordinal) -ge 0) {
      throw 'SECRET_DISCLOSURE_DETECTED'
    }
  }
}

function Get-SecretScanValues {
  $values = @(
    @($appPassword, $bootstrapPassword, $storedPasswordHash, $token) |
      Where-Object { -not [string]::IsNullOrEmpty([string]$_) }
  )
  if ($values.Count -lt 2) { throw 'SECRET_SCAN_INPUT_INCOMPLETE' }
  $values
}

function Get-SafeFailureCode {
  param([Parameter(Mandatory)][Exception]$Exception)
  $message = [string]$Exception.Message
  if ($message -cmatch '^[A-Z][A-Z0-9_]{2,100}$') { return $message }
  'R1_02_VALIDATION_FAILED'
}

function Invoke-TemporarySecretScan {
  param([Parameter(Mandatory)][object[]]$Secrets)
  $script:scannedLogFiles = 0
  if (Test-Path -LiteralPath $temporaryRoot) {
    $files = @(Get-ChildItem -LiteralPath $temporaryRoot -Recurse -File -ErrorAction Stop |
        Where-Object { $_.Extension -in @('.log', '.err', '.txt') })
    foreach ($file in $files) {
      $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
      Assert-NoSecretsInText -Text $text -Secrets $Secrets
      $script:scannedLogFiles++
    }
  }
  Assert-NoSecretsInText -Text $auditText -Secrets $Secrets
}

function Invoke-GitCapture {
  param(
    [Parameter(Mandatory)][string]$Label,
    [Parameter(Mandatory)][string[]]$Arguments
  )
  $git = (Get-Command git.exe -ErrorAction Stop).Source
  $stdout = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stdout.txt")
  $stderr = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stderr.txt")
  $process = Start-IsolatedProcess -FilePath $git -Arguments $Arguments -WorkingDirectory $projectRoot `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Wait
  try {
    if ($process.ExitCode -ne 0) { throw 'SOURCE_DIFF_SCAN_COMMAND_FAILED' }
    [IO.File]::ReadAllText($stdout, [Text.Encoding]::UTF8)
  }
  finally { $process.Dispose() }
}

function Invoke-SourceDiffSecretScan {
  param([Parameter(Mandatory)][object[]]$Secrets)
  $script:sourceDiffFilesScanned = 0
  $workingTreeDiff = Invoke-GitCapture -Label 'source-diff-worktree' `
    -Arguments @('diff', '--no-ext-diff', '--no-textconv', '--', '.')
  $indexDiff = Invoke-GitCapture -Label 'source-diff-index' `
    -Arguments @('diff', '--cached', '--no-ext-diff', '--no-textconv', '--', '.')
  Assert-NoSecretsInText -Text $workingTreeDiff -Secrets $Secrets
  Assert-NoSecretsInText -Text $indexDiff -Secrets $Secrets
  $script:sourceDiffFilesScanned += 2

  $untracked = Invoke-GitCapture -Label 'source-diff-untracked' `
    -Arguments @('-c', 'core.quotepath=false', 'ls-files', '--others', '--exclude-standard')
  $sourceExtensions = @('.java', '.kt', '.xml', '.yml', '.yaml', '.properties', '.ps1', '.md', '.json', '.ts', '.js', '.vue')
  $projectRootFull = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\')
  foreach ($relativePath in @($untracked -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
    $fullPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $relativePath)).TrimEnd('\')
    if (-not $fullPath.StartsWith("$projectRootFull\", [StringComparison]::OrdinalIgnoreCase)) {
      throw 'UNTRACKED_SOURCE_PATH_REJECTED'
    }
    if ([IO.Path]::GetExtension($fullPath).ToLowerInvariant() -notin $sourceExtensions) { continue }
    $text = [IO.File]::ReadAllText($fullPath, [Text.Encoding]::UTF8)
    Assert-NoSecretsInText -Text $text -Secrets $Secrets
    $script:sourceDiffFilesScanned++
  }
}

function Invoke-EvidenceDirectorySecretScan {
  param([Parameter(Mandatory)][object[]]$Secrets)
  $count = 0
  foreach ($file in @(Get-ChildItem -LiteralPath $reportRoot -Recurse -File -ErrorAction Stop)) {
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    Assert-NoSecretsInText -Text $text -Secrets $Secrets
    $count++
  }
  $count
}

try {
  $reportRoot = Assert-EvidencePath $reportRoot
  [void](New-Item -ItemType Directory -Force -Path $reportRoot)
  $reservedListenersBefore = @(Get-ReservedListeners)
  $refusedPorts = @(3306, 3307, 18081)
  if ($MySqlPort -eq 0) { $MySqlPort = Get-FreeIsolatedPort -Excluded $refusedPorts }
  if ($ServerPort -eq 0) {
    $ServerPort = Get-FreeIsolatedPort -Excluded @($refusedPorts + $MySqlPort)
  }
  foreach ($reserved in $refusedPorts) {
    if ($MySqlPort -eq $reserved -or $ServerPort -eq $reserved) { throw 'RESERVED_PORT_REJECTED' }
  }
  if ($MySqlPort -eq $ServerPort) { throw 'ISOLATED_PORT_COLLISION' }
  foreach ($input in @($mysqld, $mysql, $java, $jar)) {
    if (-not (Test-Path -LiteralPath $input -PathType Leaf)) { throw 'VALIDATION_INPUT_MISSING' }
  }
  Assert-FreePort -Port $MySqlPort -Label 'MYSQL'
  Assert-FreePort -Port $ServerPort -Label 'WEB'

  [void](New-Item -ItemType Directory -Path $temporaryRoot)
  [void](Assert-IsolatedPath $temporaryRoot)
  $security = [Security.AccessControl.DirectorySecurity]::new()
  $security.SetAccessRuleProtection($true, $false)
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
  $rule = [Security.AccessControl.FileSystemAccessRule]::new(
    $identity,
    [Security.AccessControl.FileSystemRights]::FullControl,
    [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
    [Security.AccessControl.PropagationFlags]::None,
    [Security.AccessControl.AccessControlType]::Allow)
  $security.AddAccessRule($rule)
  Set-Acl -LiteralPath $temporaryRoot -AclObject $security
  Add-Result -Stage 'Isolation boundary' -Status 'PASS' `
    -Evidence 'Random private MySQL/Web ports selected; 3306, 3307 and 18081 are refused.'

  $dataDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-data')
  $tmpDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-tmp')
  [void](New-Item -ItemType Directory -Path $tmpDirectory)
  Invoke-ProcessChecked -FilePath $mysqld -Arguments @(
    '--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", '--initialize-insecure') `
    -Label 'mysql-initialize'
  $mysqlProcess = Start-IsolatedProcess -FilePath $mysqld -Arguments @(
    '--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", "--tmpdir=$tmpDirectory",
    "--port=$MySqlPort", '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin',
    '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci') `
    -WorkingDirectory $temporaryRoot `
    -RedirectStandardOutput (Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stdout.log')) `
    -RedirectStandardError (Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stderr.log'))
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if ($mysqlProcess.HasExited) { throw 'ISOLATED_MYSQL_EARLY_EXIT' }
    try { $null = Invoke-MySql -Sql 'select 1;'; break }
    catch {
      if ($attempt -eq 59) { throw 'ISOLATED_MYSQL_READY_TIMEOUT' }
      Start-Sleep -Seconds 1
    }
  }
  Add-Result -Stage 'Isolated MySQL 8 startup' -Status 'PASS' `
    -Evidence 'Fresh random datadir is loopback-only and owned by this validation run.'

  Invoke-MySql -Sql "create database $database character set utf8mb4 collate utf8mb4_unicode_ci;" | Out-Null
  $grantDatabase = ([char]96) + $database.Replace('_', '\_') + ([char]96)
  Invoke-MySql -Sql "create user '$appUser'@'127.0.0.1' identified with mysql_native_password by '$appPassword'; grant all privileges on ${grantDatabase}.* to '$appUser'@'127.0.0.1'; flush privileges;" | Out-Null
  $candidateHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
  $baseEnvironment = @{
    SPRING_PROFILES_ACTIVE = 'qa'
    APP_ENV = 'QA'
    APP_SEED_DEMO_ENABLED = 'false'
    APP_SEED_LEGACY_EMPLOYEE_ENABLED = 'false'
    APP_MIGRATION_AUTO_RUN = 'false'
    APP_BOOTSTRAP_ADMIN_ENABLED = 'false'
    DEEPSEEK_ENABLED = 'false'
    MYSQL_HOST = '127.0.0.1'
    MYSQL_PORT = [string]$MySqlPort
    MYSQL_DATABASE = $database
    MYSQL_USERNAME = $appUser
    MYSQL_PASSWORD = $appPassword
    MYSQL_SSL_MODE = 'DISABLED'
    SERVER_PORT = [string]$ServerPort
    APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT = (Assert-IsolatedPath (Join-Path $temporaryRoot 'expense-supplements'))
  }

  $backendProcess = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $jar) `
    -WorkingDirectory $temporaryRoot `
    -RedirectStandardOutput (Assert-IsolatedPath (Join-Path $temporaryRoot 'flyway-web.stdout.log')) `
    -RedirectStandardError (Assert-IsolatedPath (Join-Path $temporaryRoot 'flyway-web.stderr.log')) `
    -Environment $baseEnvironment
  Wait-ForBackendHealth -Process $backendProcess
  $flywayVersion = [int](Invoke-MySql -Database $database `
    -Sql 'select max(cast(version as unsigned)) from flyway_schema_history where success = 1;')
  $failedFlywayMigrations = [int](Invoke-MySql -Database $database `
    -Sql 'select count(*) from flyway_schema_history where success = 0;')
  $successfulFlywayMigrations = [int](Invoke-MySql -Database $database `
    -Sql 'select count(*) from flyway_schema_history where success = 1 and version is not null;')
  if ($flywayVersion -ne 56 -or $failedFlywayMigrations -ne 0 -or $successfulFlywayMigrations -ne 56) {
    throw 'UNEXPECTED_FLYWAY_STATE'
  }
  $tenantParts = @((Invoke-MySql -Database $database `
      -Sql "select id, name, status from tenant where id = $tenantId;") -split "`t")
  if ($tenantParts.Count -ne 3 -or [int]$tenantParts[0] -ne $tenantId -or $tenantParts[2] -cne 'ACTIVE') {
    throw 'EXPECTED_ACTIVE_TENANT_MISSING'
  }
  $tenantNameConfirm = [string]$tenantParts[1]
  $counts.afterFlyway = Get-BootstrapCounts
  if ($counts.afterFlyway.authUser -ne 0 -or $counts.afterFlyway.authToken -ne 0 -or
      $counts.afterFlyway.firstBossAudit -ne 0 -or $counts.afterFlyway.operationLog -ne 0) {
    throw 'FLYWAY_CREATED_ACCOUNT_OR_BOOTSTRAP_AUDIT'
  }
  Stop-OwnedProcess -Process $backendProcess
  $backendProcess = $null
  Wait-ForPortRelease -Port $ServerPort
  Add-Result -Stage 'Flyway-only Web candidate' -Status 'PASS' `
    -Evidence 'Normal QA Web startup reached V56, created no account, then stopped before CLI validation.'

  $environmentA = New-CliEnvironment -BaseEnvironment $baseEnvironment `
    -Username $usernameA -DisplayName 'R1-02 Candidate A'
  $environmentB = New-CliEnvironment -BaseEnvironment $baseEnvironment `
    -Username $usernameB -DisplayName 'R1-02 Candidate B'
  $cliAStdout = Assert-IsolatedPath (Join-Path $temporaryRoot 'cli-a.stdout.log')
  $cliAStderr = Assert-IsolatedPath (Join-Path $temporaryRoot 'cli-a.stderr.log')
  $cliBStdout = Assert-IsolatedPath (Join-Path $temporaryRoot 'cli-b.stdout.log')
  $cliBStderr = Assert-IsolatedPath (Join-Path $temporaryRoot 'cli-b.stderr.log')
  $listenersBeforeCli = @(Get-ListenerSnapshot)
  $cliA = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $jar, '--admin-bootstrap') `
    -WorkingDirectory $temporaryRoot -RedirectStandardOutput $cliAStdout `
    -RedirectStandardError $cliAStderr -Environment $environmentA
  $cliProcesses.Add($cliA)
  $cliB = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $jar, '--admin-bootstrap') `
    -WorkingDirectory $temporaryRoot -RedirectStandardOutput $cliBStdout `
    -RedirectStandardError $cliBStderr -Environment $environmentB
  $cliProcesses.Add($cliB)
  $cliPortMonitor = Monitor-CliProcesses -Processes @($cliA, $cliB) -ListenersBefore $listenersBeforeCli
  Complete-IsolatedProcess -Process $cliA
  Complete-IsolatedProcess -Process $cliB
  $cliExitCodes.candidateA = [int]$cliA.ExitCode
  $cliExitCodes.candidateB = [int]$cliB.ExitCode
  Assert-CliMachineOutput -ExitCode $cliExitCodes.candidateA -StdoutPath $cliAStdout -StderrPath $cliAStderr
  Assert-CliMachineOutput -ExitCode $cliExitCodes.candidateB -StdoutPath $cliBStdout -StderrPath $cliBStderr
  if (-not $cliPortMonitor.passed) { throw 'CLI_OPENED_TCP_LISTENER' }
  $successCount = @(
    @($cliExitCodes.candidateA, $cliExitCodes.candidateB) | Where-Object { $_ -eq 0 }
  ).Count
  if ($successCount -ne 1) { throw 'CONCURRENT_CLI_SUCCESS_COUNT_INVALID' }
  if ($cliExitCodes.candidateA -eq 0) {
    if ($cliExitCodes.candidateB -notin @(5, 6)) { throw 'CONCURRENT_CLI_LOSER_CODE_INVALID' }
    $winnerUsername = $usernameA
    $winnerDisplayName = 'R1-02 Candidate A'
    $winnerEnvironment = $environmentA
  }
  else {
    if ($cliExitCodes.candidateA -notin @(5, 6) -or $cliExitCodes.candidateB -ne 0) {
      throw 'CONCURRENT_CLI_LOSER_CODE_INVALID'
    }
    $winnerUsername = $usernameB
    $winnerDisplayName = 'R1-02 Candidate B'
    $winnerEnvironment = $environmentB
  }
  $cliA.Dispose()
  $cliB.Dispose()
  $cliProcesses.Clear()

  $counts.afterConcurrentCli = Get-BootstrapCounts
  $winnerCount = if ($winnerUsername -ceq $usernameA) {
    $counts.afterConcurrentCli.candidateA
  }
  else {
    $counts.afterConcurrentCli.candidateB
  }
  $loserCount = if ($winnerUsername -ceq $usernameA) {
    $counts.afterConcurrentCli.candidateB
  }
  else {
    $counts.afterConcurrentCli.candidateA
  }
  if ($counts.afterConcurrentCli.authUser -ne 1 -or
      $counts.afterConcurrentCli.boss -ne 1 -or
      $counts.afterConcurrentCli.firstBossAudit -ne 1 -or
      $counts.afterConcurrentCli.authToken -ne 0 -or
      $counts.afterConcurrentCli.operationLog -ne 1 -or
      $winnerCount -ne 1 -or $loserCount -ne 0) {
    throw 'CONCURRENT_CLI_DATABASE_INVARIANT_FAILED'
  }
  Add-Result -Stage 'Concurrent non-Web bootstrap' -Status 'PASS' `
    -Evidence 'Exactly one CLI created one BOSS and one audit; the other exited 5/6; no CLI PID listened on TCP.'

  $cliExitCodes.repeat = Invoke-SingleCli -Label 'cli-repeat' -Environment $winnerEnvironment
  if ($cliExitCodes.repeat -ne 5) { throw 'REPEAT_CLI_EXIT_CODE_INVALID' }
  $counts.afterRepeat = Get-BootstrapCounts
  Assert-CountsEqual -Expected $counts.afterConcurrentCli -Actual $counts.afterRepeat
  Add-Result -Stage 'One-time replay protection' -Status 'PASS' `
    -Evidence 'Repeated bootstrap exited 5 and left all account, audit and Token counts unchanged.'

  $webEnvironment = Copy-Environment -Source $baseEnvironment
  $webEnvironment['APP_BOOTSTRAP_ADMIN_ENABLED'] = 'false'
  $backendProcess = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $jar) `
    -WorkingDirectory $temporaryRoot `
    -RedirectStandardOutput (Assert-IsolatedPath (Join-Path $temporaryRoot 'regression-web.stdout.log')) `
    -RedirectStandardError (Assert-IsolatedPath (Join-Path $temporaryRoot 'regression-web.stderr.log')) `
    -Environment $webEnvironment
  Wait-ForBackendHealth -Process $backendProcess
  $http.health = 200
  $counts.afterWebStartup = Get-BootstrapCounts
  if ($counts.afterWebStartup.authUser -ne 1 -or
      $counts.afterWebStartup.firstBossAudit -ne 1 -or
      $counts.afterWebStartup.authToken -ne 0 -or
      $counts.afterWebStartup.operationLog -ne 1) {
    throw 'WEB_STARTUP_REEXECUTED_BOOTSTRAP'
  }

  $login = Invoke-Http -Method 'POST' -Path '/api/auth/login' -Json (@{
      username = $winnerUsername
      password = $bootstrapPassword
      tenantId = $tenantId
    } | ConvertTo-Json -Compress)
  Assert-Status -Response $login -Expected 200 -Label 'BOSS_LOGIN'
  $http.bossLogin = $login.status
  $loginJson = $login.body | ConvertFrom-Json
  $token = [string]$loginJson.data.token
  if ([string]::IsNullOrWhiteSpace($token)) { throw 'LOGIN_TOKEN_MISSING' }

  $me = Invoke-Http -Method 'GET' -Path '/api/auth/me' -BearerToken $token
  Assert-Status -Response $me -Expected 200 -Label 'BOSS_ME'
  $meJson = $me.body | ConvertFrom-Json
  if ($meJson.data.role -cne 'BOSS') { throw 'LOGIN_ROLE_NOT_BOSS' }
  $http.bossMe = $me.status

  $users = Invoke-Http -Method 'GET' -Path '/api/users' -BearerToken $token
  Assert-Status -Response $users -Expected 200 -Label 'BOSS_USERS'
  $http.bossUsers = $users.status

  $logout = Invoke-Http -Method 'POST' -Path '/api/auth/logout' -BearerToken $token
  Assert-Status -Response $logout -Expected 200 -Label 'BOSS_LOGOUT'
  $http.logout = $logout.status
  $reused = Invoke-Http -Method 'GET' -Path '/api/auth/me' -BearerToken $token
  Assert-Status -Response $reused -Expected 401 -Label 'REUSED_TOKEN'
  $http.reusedToken = $reused.status

  $counts.afterLogout = Get-BootstrapCounts
  if ($counts.afterLogout.authUser -ne 1 -or
      $counts.afterLogout.boss -ne 1 -or
      $counts.afterLogout.firstBossAudit -ne 1 -or
      $counts.afterLogout.authToken -ne 0 -or
      $counts.afterLogout.operationLog -ne 2) {
    throw 'WEB_REGRESSION_DATABASE_INVARIANT_FAILED'
  }
  $storedPasswordHash = Invoke-MySql -Database $database `
    -Sql "select password_hash from auth_user where username = '$winnerUsername';"
  $auditText = Invoke-MySql -Database $database -Sql @"
select concat_ws('|', coalesce(operator_name, ''), action, target_type,
                 coalesce(target_id, ''), coalesce(after_json, ''), coalesce(reason, ''))
from operation_log
order by id;
"@
  Add-Result -Stage 'Normal Web regression' -Status 'PASS' `
    -Evidence 'Bootstrap disabled: health/login/me/users/logout returned 200, reused Token returned 401, and initialization audit stayed at one.'
}
catch {
  if (-not $failureCode) { $failureCode = Get-SafeFailureCode -Exception $_.Exception }
  Add-Result -Stage 'R1-02 isolated system validation' -Status 'FAIL' `
    -Evidence 'A validation gate failed; sensitive exception details were suppressed.'
}
finally {
  $ownedProcessesStopped = $true
  foreach ($process in @($cliProcesses)) {
    try { Stop-OwnedProcess -Process $process }
    catch {
      $ownedProcessesStopped = $false
      $failureCode = 'R1_02_CLEANUP_FAILED'
    }
  }
  $cliProcesses.Clear()
  try {
    if ($backendProcess) { Stop-OwnedProcess -Process $backendProcess; $backendProcess = $null }
  }
  catch {
    $ownedProcessesStopped = $false
    $failureCode = 'R1_02_CLEANUP_FAILED'
  }
  try {
    if ($mysqlProcess) {
      Stop-OwnedMySqlProcess -Process $mysqlProcess -OwnedRoot $temporaryRoot
      $mysqlProcess = $null
    }
  }
  catch {
    $ownedProcessesStopped = $false
    $failureCode = 'R1_02_CLEANUP_FAILED'
  }

  $secrets = @(Get-SecretScanValues)
  try {
    if (Test-Path -LiteralPath $temporaryRoot) {
      Invoke-SourceDiffSecretScan -Secrets $secrets
      $sourceDiffScanPassed = $true
    }
    Invoke-TemporarySecretScan -Secrets $secrets
    $secretScanPassed = $true
  }
  catch {
    $secretScanPassed = $false
    $failureCode = 'R1_02_SECRET_SCAN_FAILED'
  }

  try {
    $safeRoot = Assert-IsolatedPath $temporaryRoot
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
      try {
        if (Test-Path -LiteralPath $safeRoot) {
          Remove-Item -LiteralPath $safeRoot -Recurse -Force
        }
        break
      }
      catch {
        if ($attempt -eq 29) { throw }
        Start-Sleep -Milliseconds 500
      }
    }
    $cleanupComplete = -not (Test-Path -LiteralPath $safeRoot)
    if (-not $cleanupComplete) { throw 'TEMPORARY_ROOT_NOT_REMOVED' }
  }
  catch {
    $cleanupComplete = $false
    $failureCode = 'R1_02_CLEANUP_FAILED'
  }

  $reservedListenersAfter = @(Get-ReservedListeners)
  $reservedListenersChanged = (($reservedListenersBefore | ConvertTo-Json -Compress) -cne
      ($reservedListenersAfter | ConvertTo-Json -Compress))
  if ($reservedListenersChanged) { $failureCode = 'R1_02_RESERVED_LISTENER_CHANGED' }
}

$status = if ($failureCode) { 'FAIL' } else { 'PASS' }
$evidence = [ordered]@{
  schema = 'ai-profit-os-r1-02-isolated-first-boss-evidence/v1'
  generatedAt = (Get-Date).ToString('o')
  status = $status
  candidateJarSha256 = $candidateHash
  latestFlyway = $flywayVersion
  failedFlywayMigrations = $failedFlywayMigrations
  isolatedMysqlPort = $MySqlPort
  isolatedWebPort = $ServerPort
  refusedPorts = @(3306, 3307, 18081)
  reservedListenersBefore = $reservedListenersBefore
  reservedListenersAfter = $reservedListenersAfter
  productionPortsTouched = $reservedListenersChanged
  cliExitCodes = $cliExitCodes
  cliPortMonitor = $cliPortMonitor
  counts = $counts
  httpStatus = $http
  secretScan = [ordered]@{
    passed = $secretScanPassed
    logFilesScanned = $scannedLogFiles
    operationLogScanned = -not [string]::IsNullOrEmpty($auditText)
    sourceDiffPassed = $sourceDiffScanPassed
    sourceDiffFilesScanned = $sourceDiffFilesScanned
    httpEvidenceIsMetadataOnly = $true
    evidenceFilesScanned = 0
    evidenceSelfCheck = $false
  }
  cleanup = [ordered]@{
    ownedProcessesStopped = $ownedProcessesStopped
    temporaryDataRemoved = $cleanupComplete
  }
  results = @($results)
  failure = $failureCode
}

$markdownLines = [System.Collections.Generic.List[string]]::new()
$markdownLines.Add('# R1-02 isolated MySQL 8 first BOSS validation')
$markdownLines.Add('')
$markdownLines.Add("- Result: **$status**")
$markdownLines.Add("- Candidate JAR SHA-256: $candidateHash")
$markdownLines.Add("- Flyway: V$flywayVersion; failed migrations: $failedFlywayMigrations")
$markdownLines.Add("- Concurrent CLI exit codes: $($cliExitCodes.candidateA), $($cliExitCodes.candidateB)")
$markdownLines.Add("- Repeat CLI exit code: $($cliExitCodes.repeat)")
$markdownLines.Add("- CLI TCP listener observations: $(@($cliPortMonitor.ownedListenerObservations).Count)")
$markdownLines.Add("- HTTP health/login/me/users/logout/reused: $($http.health)/$($http.bossLogin)/$($http.bossMe)/$($http.bossUsers)/$($http.logout)/$($http.reusedToken)")
$markdownLines.Add("- Secret scan passed: $secretScanPassed")
$markdownLines.Add("- Temporary data removed: $cleanupComplete")
$markdownLines.Add('')
$markdownLines.Add('## Validation stages')
$markdownLines.Add('')
foreach ($result in $results) {
  $markdownLines.Add("- $($result.status) | $($result.stage) | $($result.evidence)")
}
if ($failureCode) {
  $markdownLines.Add('')
  $markdownLines.Add("- Failure: $failureCode")
}

$secrets = @(Get-SecretScanValues)
try {
  $jsonText = $evidence | ConvertTo-Json -Depth 12
  $markdownText = $markdownLines -join [Environment]::NewLine
  Assert-NoSecretsInText -Text $jsonText -Secrets $secrets
  Assert-NoSecretsInText -Text $markdownText -Secrets $secrets
  [IO.File]::WriteAllText($reportFile, $jsonText, [Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText($reportMarkdown, $markdownText, [Text.UTF8Encoding]::new($false))
  $evidenceFilesScanned = Invoke-EvidenceDirectorySecretScan -Secrets $secrets
  $evidence['secretScan']['evidenceFilesScanned'] = $evidenceFilesScanned
  $evidence['secretScan']['evidenceSelfCheck'] = $true
  $jsonText = $evidence | ConvertTo-Json -Depth 12
  Assert-NoSecretsInText -Text $jsonText -Secrets $secrets
  [IO.File]::WriteAllText($reportFile, $jsonText, [Text.UTF8Encoding]::new($false))
  $evidenceFilesScanned = Invoke-EvidenceDirectorySecretScan -Secrets $secrets
  Assert-NoSecretsInText -Text ([IO.File]::ReadAllText($reportFile, [Text.Encoding]::UTF8)) -Secrets $secrets
  Assert-NoSecretsInText -Text ([IO.File]::ReadAllText($reportMarkdown, [Text.Encoding]::UTF8)) -Secrets $secrets
  $evidenceSelfCheck = $true
}
catch {
  $evidenceSelfCheck = $false
  $failureCode = 'R1_02_EVIDENCE_SECRET_SCAN_FAILED'
  foreach ($path in @($reportFile, $reportMarkdown)) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
  }
  $minimalEvidence = [ordered]@{
    schema = 'ai-profit-os-r1-02-isolated-first-boss-evidence/v1'
    generatedAt = (Get-Date).ToString('o')
    status = 'FAIL'
    candidateJarSha256 = $candidateHash
    failure = $failureCode
    temporaryDataRemoved = $cleanupComplete
  } | ConvertTo-Json -Depth 4
  Assert-NoSecretsInText -Text $minimalEvidence -Secrets $secrets
  [IO.File]::WriteAllText($reportFile, $minimalEvidence, [Text.UTF8Encoding]::new($false))
  [IO.File]::WriteAllText(
    $reportMarkdown,
    "# R1-02 isolated validation`r`n`r`n- Result: **FAIL**`r`n- Failure: $failureCode",
    [Text.UTF8Encoding]::new($false))
}

Write-Host "Evidence: $reportMarkdown"
if ($failureCode) { throw 'R1-02 isolated validation failed; inspect the redacted evidence report.' }
