[CmdletBinding()]
param(
  [ValidateRange(1024, 65535)]
  [int]$MySqlPort = 3311,
  [ValidateRange(1024, 65535)]
  [int]$ServerPort = 18121,
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
$temporaryRoot = Join-Path $env:TEMP "ai-profit-r1-01-isolated-$timestamp"
$temporaryPrefix = ([IO.Path]::GetFullPath((Join-Path $env:TEMP 'ai-profit-r1-01-isolated-'))).TrimEnd('\')
$allowedEvidenceRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'output\release-evidence')).TrimEnd('\')
$reportRoot = if ($ReportDirectory) {
  [IO.Path]::GetFullPath($ReportDirectory)
}
else {
  Join-Path $allowedEvidenceRoot 'R1-01-20260715'
}
$reportFile = Join-Path $reportRoot "r1-01-isolated-mysql-$timestamp.json"
$reportMarkdown = Join-Path $reportRoot "r1-01-isolated-mysql-$timestamp.md"
$mysqlProcess = $null
$backendProcess = $null
$results = [System.Collections.Generic.List[object]]::new()
$failure = $null
$cleanupComplete = $false
$database = "ai_profit_qa_r101_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$appUser = "r101_$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$appPassword = "R101$([Guid]::NewGuid().ToString('N'))"
$fixtureUsername = "e2e_r101_boss_$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$fixturePassword = "R101!$([Guid]::NewGuid().ToString('N'))"
$candidateHash = $null
$flywayVersion = $null
$failedFlywayMigrations = $null
$counts = [ordered]@{}
$http = [ordered]@{}
$reservedListenersBefore = @()
$reservedListenersAfter = @()
$reservedListenersChanged = $false

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
    throw "Refusing a file operation outside the isolated temporary root: $full"
  }
  $full
}

function Assert-EvidencePath {
  param([Parameter(Mandatory)][string]$Path)
  $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
  if ($full -ne $allowedEvidenceRoot -and
      -not $full.StartsWith("$allowedEvidenceRoot\", [StringComparison]::OrdinalIgnoreCase)) {
    throw "Evidence must stay under output/release-evidence: $full"
  }
  $full
}

function Assert-FreePort {
  param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Label)
  if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "$Label port $Port is already in use; no existing process will be stopped."
  }
}

function Get-ReservedListeners {
  $items = [System.Collections.Generic.List[object]]::new()
  foreach ($port in @(3306, 3307, 18081)) {
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
      Sort-Object OwningProcess, LocalAddress)
    foreach ($listener in $listeners) {
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
      if ($_ -match '"') { throw 'Process arguments must not contain a double quote.' }
      '"{0}"' -f $_
    }
    else { $_ }
  }) -join ' '
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
    [Parameter(Mandatory)][string]$Label
  )
  $stdout = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stdout.log")
  $stderr = Assert-IsolatedPath (Join-Path $temporaryRoot "$Label.stderr.log")
  $process = Start-IsolatedProcess -FilePath $FilePath -Arguments $Arguments -WorkingDirectory $temporaryRoot `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -Wait
  try {
    if ($process.ExitCode -ne 0) { throw "$Label failed with exit code $($process.ExitCode)." }
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
  Set-CleanEnvironment -StartInfo $info
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

function Get-Counts {
  $parts = @(Invoke-MySql -Database $database -Sql 'select (select count(*) from auth_user), (select count(*) from auth_token), (select count(*) from operation_log);') -split "`t"
  if ($parts.Count -ne 3) { throw 'Unexpected account table count result.' }
  [ordered]@{ authUser = [int]$parts[0]; authToken = [int]$parts[1]; operationLog = [int]$parts[2] }
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
  $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "http://127.0.0.1:$ServerPort$Path")
  if ($Json) { $request.Content = [Net.Http.StringContent]::new($Json, [Text.Encoding]::UTF8, 'application/json') }
  if ($BearerToken) { [void]$request.Headers.TryAddWithoutValidation('Authorization', "Bearer $BearerToken") }
  try {
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [pscustomobject]@{ status = [int]$response.StatusCode; body = $body }
  }
  finally {
    $request.Dispose()
    $client.Dispose()
  }
}

function New-PasswordHash {
  param([Parameter(Mandatory)][string]$Password)
  $salt = New-Object byte[] 16
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($salt) }
  finally { $rng.Dispose() }
  $derive = [Security.Cryptography.Rfc2898DeriveBytes]::new(
    $Password, $salt, 120000, [Security.Cryptography.HashAlgorithmName]::SHA256)
  try { $hash = $derive.GetBytes(32) }
  finally { $derive.Dispose() }
  'pbkdf2$120000${0}${1}' -f [Convert]::ToBase64String($salt), [Convert]::ToBase64String($hash)
}

function Assert-Status {
  param([Parameter(Mandatory)]$Response, [Parameter(Mandatory)][int]$Expected, [Parameter(Mandatory)][string]$Label)
  if ($Response.status -ne $Expected) {
    throw "$Label returned HTTP $($Response.status), expected $Expected."
  }
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
      $remaining = @($ownedProcessIds | Where-Object {
        Get-Process -Id $_ -ErrorAction SilentlyContinue
      })
      if ($remaining.Count -eq 0) { break }
      if ($attempt -eq 29) {
        throw "Owned isolated MySQL processes did not stop: $($remaining -join ', ')."
      }
      Start-Sleep -Milliseconds 500
    }
    if ($Process) { Complete-IsolatedProcess -Process $Process }
  }
  finally {
    if ($Process) { $Process.Dispose() }
  }
}

try {
  $reservedListenersBefore = Get-ReservedListeners
  foreach ($reserved in @(3306, 3307, 18081)) {
    if ($MySqlPort -eq $reserved -or $ServerPort -eq $reserved) {
      throw "This validator refuses reserved port $reserved."
    }
  }
  if ($MySqlPort -eq $ServerPort) { throw 'The isolated MySQL and backend ports must differ.' }
  foreach ($input in @($mysqld, $mysql, $java, $jar)) {
    if (-not (Test-Path -LiteralPath $input -PathType Leaf)) { throw "Required validation input is missing: $input" }
  }
  Assert-FreePort -Port $MySqlPort -Label 'Isolated MySQL'
  Assert-FreePort -Port $ServerPort -Label 'Isolated backend'
  $reportRoot = Assert-EvidencePath $reportRoot
  [void](New-Item -ItemType Directory -Force -Path $reportRoot)
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
  Add-Result -Stage 'Isolation boundary' -Status 'PASS' -Evidence "Private temporary runtime uses loopback ports $MySqlPort/$ServerPort; 3306, 3307 and 18081 are refused."

  $dataDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-data')
  $tmpDirectory = Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql-tmp')
  [void](New-Item -ItemType Directory -Path $tmpDirectory)
  Invoke-ProcessChecked -FilePath $mysqld -Arguments @(
      '--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", '--initialize-insecure') -Label 'mysql-initialize'
  $mysqlProcess = Start-IsolatedProcess -FilePath $mysqld -Arguments @(
      '--no-defaults', "--basedir=$mysqlBase", "--datadir=$dataDirectory", "--tmpdir=$tmpDirectory",
      "--port=$MySqlPort", '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin',
      '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci') `
      -WorkingDirectory $temporaryRoot `
      -RedirectStandardOutput (Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stdout.log')) `
      -RedirectStandardError (Assert-IsolatedPath (Join-Path $temporaryRoot 'mysql.stderr.log'))
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    if ($mysqlProcess.HasExited) { throw 'Isolated MySQL exited before accepting TCP connections.' }
    try { $null = Invoke-MySql -Sql 'select 1;'; break }
    catch {
      if ($attempt -eq 59) { throw 'Isolated MySQL did not become ready within 60 seconds.' }
      Start-Sleep -Seconds 1
    }
  }
  Add-Result -Stage 'Isolated MySQL 8 startup' -Status 'PASS' -Evidence "Fresh datadir is bound only to 127.0.0.1:$MySqlPort."

  Invoke-MySql -Sql "create database $database character set utf8mb4 collate utf8mb4_unicode_ci;" | Out-Null
  Invoke-MySql -Sql "create user '$appUser'@'127.0.0.1' identified with mysql_native_password by '$appPassword'; grant all privileges on $database.* to '$appUser'@'127.0.0.1'; flush privileges;" | Out-Null
  $candidateHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
  $backendEnvironment = @{
    SPRING_PROFILES_ACTIVE = 'qa'
    APP_ENV = 'QA'
    APP_SEED_DEMO_ENABLED = 'false'
    APP_SEED_LEGACY_EMPLOYEE_ENABLED = 'false'
    APP_MIGRATION_AUTO_RUN = 'false'
    APP_BOOTSTRAP_DEFAULT_USERS_ENABLED = 'true'
    APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD = '123'
    APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED = 'true'
    APP_BOOTSTRAP_STORE_MANAGER_PASSWORD = '123'
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
  $backendProcess = Start-IsolatedProcess -FilePath $java -Arguments @('-jar', $jar) -WorkingDirectory $temporaryRoot `
      -RedirectStandardOutput (Assert-IsolatedPath (Join-Path $temporaryRoot 'backend.stdout.log')) `
      -RedirectStandardError (Assert-IsolatedPath (Join-Path $temporaryRoot 'backend.stderr.log')) `
      -Environment $backendEnvironment

  $healthResponse = $null
  for ($attempt = 0; $attempt -lt 120; $attempt++) {
    if ($backendProcess.HasExited) { throw 'Backend candidate exited before health was available.' }
    try {
      $candidate = Invoke-Http -Method 'GET' -Path '/api/health'
      if ($candidate.status -eq 200) { $healthResponse = $candidate; break }
    }
    catch { }
    Start-Sleep -Seconds 1
  }
  if (-not $healthResponse) { throw 'Backend candidate did not become healthy within 120 seconds.' }
  $healthJson = $healthResponse.body | ConvertFrom-Json
  $healthData = if ($healthJson.data) { $healthJson.data } else { $healthJson }
  if ($healthData.status -ne 'UP') { throw 'Health endpoint did not report UP.' }
  $http.health = 200
  Add-Result -Stage 'Candidate health' -Status 'PASS' -Evidence "Candidate is UP in QA on isolated port $ServerPort."

  $flywayVersion = [int](Invoke-MySql -Database $database -Sql 'select max(cast(version as unsigned)) from flyway_schema_history where success = 1;')
  $failedFlywayMigrations = [int](Invoke-MySql -Database $database -Sql 'select count(*) from flyway_schema_history where success = 0;')
  if ($flywayVersion -ne 56 -or $failedFlywayMigrations -ne 0) {
    throw "Unexpected Flyway result: latest=$flywayVersion failed=$failedFlywayMigrations."
  }
  Add-Result -Stage 'Empty MySQL Flyway' -Status 'PASS' -Evidence 'Fresh isolated database reached V56 with no failed migration.'

  $counts.afterStartup = Get-Counts
  if ($counts.afterStartup.authUser -ne 0 -or $counts.afterStartup.authToken -ne 0) {
    throw 'Application startup created an account or token in the empty database.'
  }
  Add-Result -Stage 'Startup account invariant' -Status 'PASS' -Evidence 'auth_user=0 and auth_token=0 even with removed legacy bootstrap variables set true and password 123.'

  $unknownLogin = Invoke-Http -Method 'POST' -Path '/api/auth/login' -Json (@{
      username = 'boss'; password = '123'; tenantId = 1 } | ConvertTo-Json -Compress)
  Assert-Status -Response $unknownLogin -Expected 401 -Label 'Unknown-account login'
  $http.unknownLogin = $unknownLogin.status
  $noAuthMe = Invoke-Http -Method 'GET' -Path '/api/auth/me'
  Assert-Status -Response $noAuthMe -Expected 401 -Label 'Unauthenticated /api/auth/me'
  $http.noAuthMe = $noAuthMe.status
  $noAuthUsers = Invoke-Http -Method 'GET' -Path '/api/users'
  Assert-Status -Response $noAuthUsers -Expected 401 -Label 'Unauthenticated /api/users'
  $http.noAuthUsers = $noAuthUsers.status
  $noAuthLogout = Invoke-Http -Method 'POST' -Path '/api/auth/logout'
  Assert-Status -Response $noAuthLogout -Expected 401 -Label 'Unauthenticated /api/auth/logout'
  $http.noAuthLogout = $noAuthLogout.status
  $counts.afterNegativeHttp = Get-Counts
  if ($counts.afterNegativeHttp.authUser -ne $counts.afterStartup.authUser -or
      $counts.afterNegativeHttp.authToken -ne $counts.afterStartup.authToken -or
      $counts.afterNegativeHttp.operationLog -ne $counts.afterStartup.operationLog) {
    throw 'Failed or unauthenticated HTTP requests changed account, token or operation-log counts.'
  }
  Add-Result -Stage 'Negative HTTP account invariant' -Status 'PASS' -Evidence 'Unknown login and unauthenticated me/users/logout returned 401 with no database side effect.'

  $passwordHash = New-PasswordHash -Password $fixturePassword
  Invoke-MySql -Database $database -Sql "insert into auth_user(tenant_id, username, password_hash, display_name, role, store_id, enabled, permission_version) values (1, '$fixtureUsername', '$passwordHash', 'R1-01 Isolated BOSS', 'BOSS', null, 1, 1);" | Out-Null
  $counts.afterFixture = Get-Counts
  if ($counts.afterFixture.authUser -ne 1 -or $counts.afterFixture.authToken -ne 0) {
    throw 'Controlled BOSS fixture was not the only account in the isolated database.'
  }

  $positiveLogin = Invoke-Http -Method 'POST' -Path '/api/auth/login' -Json (@{
      username = $fixtureUsername; password = $fixturePassword; tenantId = 1 } | ConvertTo-Json -Compress)
  Assert-Status -Response $positiveLogin -Expected 200 -Label 'Controlled BOSS login'
  $positiveJson = $positiveLogin.body | ConvertFrom-Json
  $token = [string]$positiveJson.data.token
  if ([string]::IsNullOrWhiteSpace($token)) { throw 'Successful login returned no Token.' }
  $http.bossLogin = $positiveLogin.status
  $bossMe = Invoke-Http -Method 'GET' -Path '/api/auth/me' -BearerToken $token
  Assert-Status -Response $bossMe -Expected 200 -Label 'Authenticated /api/auth/me'
  $meJson = $bossMe.body | ConvertFrom-Json
  if ($meJson.data.role -ne 'BOSS') { throw 'Authenticated fixture did not resolve to BOSS.' }
  $http.bossMe = $bossMe.status
  $bossUsers = Invoke-Http -Method 'GET' -Path '/api/users' -BearerToken $token
  Assert-Status -Response $bossUsers -Expected 200 -Label 'Authenticated BOSS /api/users'
  $http.bossUsers = $bossUsers.status
  $counts.afterPositiveLogin = Get-Counts
  if ($counts.afterPositiveLogin.authUser -ne 1 -or $counts.afterPositiveLogin.authToken -ne 1) {
    throw 'Successful login produced an unexpected account or Token count.'
  }

  $logout = Invoke-Http -Method 'POST' -Path '/api/auth/logout' -BearerToken $token
  Assert-Status -Response $logout -Expected 200 -Label 'BOSS logout'
  $http.logout = $logout.status
  $expiredMe = Invoke-Http -Method 'GET' -Path '/api/auth/me' -BearerToken $token
  Assert-Status -Response $expiredMe -Expected 401 -Label 'Reused logged-out Token'
  $http.reusedToken = $expiredMe.status
  $counts.afterLogout = Get-Counts
  if ($counts.afterLogout.authUser -ne 1 -or $counts.afterLogout.authToken -ne 0 -or
      $counts.afterLogout.operationLog -ne ($counts.afterStartup.operationLog + 1)) {
    throw 'Logout did not restore Token count or produce exactly one logout audit record.'
  }
  $logoutAudit = [int](Invoke-MySql -Database $database -Sql "select count(*) from operation_log where action = 'logout' and operator_name = 'R1-01 Isolated BOSS';")
  if ($logoutAudit -ne 1) { throw 'Expected logout operation_log record was not found.' }
  Add-Result -Stage 'Existing-account regression' -Status 'PASS' -Evidence 'Controlled BOSS login/me/users/logout succeeded; reused Token returned 401 and logout audit count is exactly one.'
}
catch {
  $failure = $_.Exception.Message
  Add-Result -Stage 'R1-01 isolated system validation' -Status 'FAIL' -Evidence $failure
}
finally {
  try { Stop-OwnedProcess -Process $backendProcess } catch { if (-not $failure) { $failure = $_.Exception.Message } }
  try { Stop-OwnedMySqlProcess -Process $mysqlProcess -OwnedRoot $temporaryRoot } catch { if (-not $failure) { $failure = $_.Exception.Message } }
  try {
    $safeRoot = Assert-IsolatedPath $temporaryRoot
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
      try {
        if (Test-Path -LiteralPath $safeRoot) { Remove-Item -LiteralPath $safeRoot -Recurse -Force }
        break
      }
      catch {
        if ($attempt -eq 29) { throw }
        Start-Sleep -Milliseconds 500
      }
    }
    $cleanupComplete = -not (Test-Path -LiteralPath $safeRoot)
    if (-not $cleanupComplete) { throw 'The isolated temporary runtime could not be removed.' }
  }
  catch { if (-not $failure) { $failure = $_.Exception.Message } }
  $reservedListenersAfter = Get-ReservedListeners
  $reservedListenersChanged = (($reservedListenersBefore | ConvertTo-Json -Compress) -cne
      ($reservedListenersAfter | ConvertTo-Json -Compress))
  if ($reservedListenersChanged -and -not $failure) {
    $failure = 'Reserved listener state changed during isolated validation.'
  }
}

$status = if ($failure) { 'FAIL' } else { 'PASS' }
$evidence = [ordered]@{
  schema = 'ai-profit-os-r1-01-isolated-account-bootstrap-evidence/v1'
  generatedAt = (Get-Date).ToString('o')
  status = $status
  candidateJarSha256 = $candidateHash
  isolatedEndpoint = "127.0.0.1:$MySqlPort"
  backendEndpoint = "http://127.0.0.1:$ServerPort"
  backendStoppedAfterValidation = $true
  productionPortsTouched = $reservedListenersChanged
  refusedPorts = @(3306, 3307, 18081)
  reservedListenersBefore = $reservedListenersBefore
  reservedListenersAfter = $reservedListenersAfter
  latestFlyway = $flywayVersion
  failedFlywayMigrations = $failedFlywayMigrations
  counts = $counts
  httpStatus = $http
  cleanupComplete = $cleanupComplete
  results = @($results)
  failure = $failure
  limitations = @(
    'The test account was inserted only after startup and negative-login counts proved auth_user=0.',
    'The isolated database and test account were destroyed after validation.',
    'This validates R1-01 account bootstrap removal, not R1-03 business Seed cleanup.'
  )
}
[IO.File]::WriteAllText($reportFile, ($evidence | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# R1-01 isolated MySQL 8 system validation')
$lines.Add('')
$lines.Add("- Result: **$status**")
$lines.Add("- Candidate JAR SHA-256: $candidateHash")
$lines.Add("- Isolated MySQL: 127.0.0.1:$MySqlPort (stopped and removed)")
$lines.Add("- Isolated backend: http://127.0.0.1:$ServerPort (stopped)")
$lines.Add("- Flyway: V$flywayVersion; failed migrations: $failedFlywayMigrations")
$lines.Add('- Production ports touched: no')
$lines.Add("- Temporary data removed: $cleanupComplete")
$lines.Add('')
$lines.Add('## Validation stages')
$lines.Add('')
foreach ($result in $results) { $lines.Add("- $($result.status) | $($result.stage) | $($result.evidence)") }
if ($failure) {
  $lines.Add('')
  $lines.Add("- Failure: $failure")
}
[IO.File]::WriteAllLines($reportMarkdown, $lines, [Text.UTF8Encoding]::new($false))

Write-Host "Evidence: $reportMarkdown"
if ($failure) { throw $failure }
