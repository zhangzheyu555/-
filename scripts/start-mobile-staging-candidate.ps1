[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateNotNullOrEmpty()]
  [string]$JarPath,

  [ValidateRange(1024, 65535)]
  [int]$ServerPort = 18082,

  [ValidatePattern('^127\.0\.0\.1$')]
  [string]$MySqlHost = '127.0.0.1',

  [ValidateRange(1024, 65535)]
  [int]$MySqlPort = 3308,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z0-9_]+$')]
  [string]$MySqlDatabase,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z0-9_]+$')]
  [string]$MySqlUsername,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$MySqlPasswordEnvironmentName = 'MOBILE_STAGING_MYSQL_PASSWORD',

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$CrossPermissionTokenEnvironmentName = 'MOBILE_STAGING_LEAST_PRIVILEGE_TOKEN',

  # Separate from the deliberately under-privileged token below. This BOSS token is used only
  # against the protected readiness endpoint after anonymous liveness has succeeded.
  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$DiagnosticsBossTokenEnvironmentName = 'MOBILE_STAGING_BOSS_DIAGNOSTICS_TOKEN',

  [ValidatePattern('^127\.0\.0\.1$')]
  [string]$RedisHost = '127.0.0.1',

  [ValidateRange(1024, 65535)]
  [int]$RedisPort = 6380,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$RedisPasswordEnvironmentName = 'MOBILE_STAGING_REDIS_PASSWORD',

  [ValidatePattern('^/[A-Za-z0-9._~/-]*$')]
  [string]$CrossPermissionEndpoint = '/api/boss/data-health',

  [ValidateRange(15, 300)]
  [int]$StartupTimeoutSeconds = 90,

  [string]$EvidenceRoot = '',

  [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# This script deliberately validates a QA candidate only. It never starts a STAGING
# runtime, never invokes deployment scripts, and never replaces the 18081 instance.
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$releaseCommon = Join-Path $PSScriptRoot 'ReleaseCandidateCommon.psm1'
if (-not (Test-Path -LiteralPath $releaseCommon -PathType Leaf)) {
  throw "Release candidate helper is missing: $releaseCommon"
}
Import-Module -Name $releaseCommon -Force -ErrorAction Stop
$defaultEvidenceRoot = Join-Path $projectRoot 'output\mobile-release-evidence'
$runId = 'isolated-prerelease-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'), [guid]::NewGuid().ToString('N').Substring(0, 8)
$candidateProcess = $null
$mysqlPasswordPlain = $null
$crossPermissionTokenPlain = $null
$diagnosticsBossTokenPlain = $null
$diagnosticsAuthorization = $null
$redisPasswordPlain = $null
$results = New-Object 'System.Collections.Generic.List[object]'
$gateStatus = 'BLOCKED'
$failureMessage = $null
$flywaySource = $null

function Add-GateResult {
  param(
    [Parameter(Mandatory = $true)][string]$Check,
    [Parameter(Mandatory = $true)][ValidateSet('PASS', 'BLOCKED', 'NOT_RUN')][string]$Status,
    [Parameter(Mandatory = $true)][string]$Evidence,
    [Nullable[int]]$HttpStatus = $null,
    [string]$RequestId = ''
  )

  $results.Add([pscustomobject][ordered]@{
      check = $Check
      status = $Status
      evidence = $Evidence
      httpStatus = $HttpStatus
      requestId = if ([string]::IsNullOrWhiteSpace($RequestId)) { $null } else { $RequestId }
    })
  $colour = if ($Status -eq 'PASS') { 'Green' } elseif ($Status -eq 'BLOCKED') { 'Yellow' } else { 'DarkYellow' }
  Write-Host ('[{0}] {1}: {2}' -f $Status, $Check, $Evidence) -ForegroundColor $colour
}

function Get-EnvironmentSecret {
  param([Parameter(Mandatory = $true)][string]$Name)

  foreach ($target in @(
      [EnvironmentVariableTarget]::Process,
      [EnvironmentVariableTarget]::User,
      [EnvironmentVariableTarget]::Machine
    )) {
    $value = [Environment]::GetEnvironmentVariable($Name, $target)
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      return $value
    }
  }
  throw "The required secret environment variable '$Name' is unavailable. Only its name is reported; its value is never logged."
}

function Get-ListeningEndpoint {
  param([Parameter(Mandatory = $true)][int]$Port)

  if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    return @(
      Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        ForEach-Object {
          [pscustomobject]@{ localAddress = [string]$_.LocalAddress; localPort = [int]$_.LocalPort }
        }
    )
  }

  return @(
    [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
      Where-Object { $_.Port -eq $Port } |
      ForEach-Object {
        [pscustomobject]@{ localAddress = [string]$_.Address; localPort = [int]$_.Port }
      }
  )
}

function Test-ListeningPort {
  param([Parameter(Mandatory = $true)][int]$Port)
  return @(Get-ListeningEndpoint -Port $Port).Count -gt 0
}

function Assert-IsolatedCandidateInputs {
  if ($ServerPort -eq 18081) {
    throw 'Port 18081 is protected. The isolated mobile candidate must use a separate port.'
  }
  if ($MySqlPort -eq 3307) {
    throw 'Port 3307 is protected. The isolated mobile candidate must not connect to the production MySQL instance.'
  }
  if ($RedisPort -eq 6379) {
    throw 'Port 6379 is protected. The isolated mobile candidate requires a dedicated non-default Redis port.'
  }
  if ($RedisPort -in @($ServerPort, $MySqlPort, 18081, 3307)) {
    throw 'Redis port conflicts with a candidate, MySQL, or protected runtime port.'
  }
  if ($MySqlUsername.Trim().Equals('root', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The MySQL root account is forbidden for isolated pre-release validation.'
  }
  if ($MySqlDatabase.Equals('store_profit_mysql8', [StringComparison]::OrdinalIgnoreCase) -or
      $MySqlDatabase -match '(?i)(^|_)(prod|production|live|final)(_|$)') {
    throw 'A production-like MySQL database name is forbidden for isolated pre-release validation.'
  }
  if ($MySqlDatabase -notmatch '(?i)(test|qa)') {
    throw 'The isolated QA database name must contain test or qa to satisfy DatabaseEnvironmentGuard.'
  }
  if (Test-ListeningPort -Port $ServerPort) {
    throw "Candidate port $ServerPort is already in use; this script never terminates an existing process."
  }
  $redisListeners = @(Get-ListeningEndpoint -Port $RedisPort)
  if ($redisListeners.Count -eq 0) {
    throw "A dedicated Redis listener is required on $RedisHost`:$RedisPort before the QA candidate can start."
  }
  if (@($redisListeners | Where-Object { $_.localAddress -notin @('127.0.0.1', '::1') }).Count -gt 0) {
    throw "Redis listener $RedisPort is not restricted to loopback; isolated pre-release validation refuses wildcard or external bindings."
  }
}

function Remove-InheritedSensitiveEnvironment {
  param([Parameter(Mandatory = $true)][Diagnostics.ProcessStartInfo]$StartInfo)

  $names = @($StartInfo.EnvironmentVariables.Keys)
  foreach ($name in $names) {
    if ($name -match '(?i)(password|secret|token|api[_-]?key|credential)' -or
        $name -match '(?i)^(SPRING_DATA_REDIS|REDIS)_') {
      [void]$StartInfo.EnvironmentVariables.Remove($name)
    }
  }
}

function Get-RequestId {
  param([Parameter(Mandatory = $true)]$Headers)

  $requestId = [string]$Headers['X-Request-Id']
  if ([string]::IsNullOrWhiteSpace($requestId)) {
    $requestId = [string]$Headers['X-Request-ID']
  }
  return $requestId
}

function Invoke-HttpGet {
  param(
    [Parameter(Mandatory = $true)][string]$Uri,
    [hashtable]$Headers = @{},
    [ValidateRange(1, 30)][int]$TimeoutSeconds = 5
  )

  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Method Get -Headers $Headers `
      -TimeoutSec $TimeoutSeconds -ErrorAction Stop
    return [pscustomobject]@{
      statusCode = [int]$response.StatusCode
      content = $response.Content
      requestId = Get-RequestId -Headers $response.Headers
    }
  }
  catch [System.Net.WebException] {
    $response = $_.Exception.Response
    if ($null -ne $response) {
      return [pscustomobject]@{
        statusCode = [int]$response.StatusCode
        content = ''
        requestId = Get-RequestId -Headers $response.Headers
      }
    }
    throw 'The isolated candidate did not return an HTTP response.'
  }
}

function Get-ApiData {
  param([Parameter(Mandatory = $true)][string]$Content)

  try {
    $payload = $Content | ConvertFrom-Json -ErrorAction Stop
  }
  catch {
    throw 'The health endpoint returned a non-JSON payload.'
  }

  if ($null -ne $payload.PSObject.Properties['data']) {
    return $payload.data
  }
  return $payload
}

function Test-ExpectedStatus {
  param(
    [Parameter(Mandatory = $true)][string]$Check,
    [Parameter(Mandatory = $true)][string]$Uri,
    [Parameter(Mandatory = $true)][int]$ExpectedStatus,
    [hashtable]$Headers = @{}
  )

  $response = Invoke-HttpGet -Uri $Uri -Headers $Headers
  if ($response.statusCode -ne $ExpectedStatus) {
    Add-GateResult -Check $Check -Status 'BLOCKED' `
      -Evidence "Expected HTTP $ExpectedStatus, received HTTP $($response.statusCode)." `
      -HttpStatus $response.statusCode -RequestId $response.requestId
    throw "$Check did not return HTTP $ExpectedStatus."
  }
  Add-GateResult -Check $Check -Status 'PASS' -Evidence "Received expected HTTP $ExpectedStatus." `
    -HttpStatus $response.statusCode -RequestId $response.requestId
}

function Write-GateEvidence {
  param(
    [Parameter(Mandatory = $true)][string]$Directory,
    [Parameter(Mandatory = $true)][string]$Conclusion,
    [string]$Failure = ''
  )

  $jarExists = Test-Path -LiteralPath $JarPath -PathType Leaf
  $safeJarPath = if ($jarExists) { (Resolve-Path -LiteralPath $JarPath).Path } else { $JarPath }
  $candidateJarSha256 = if ($jarExists) {
    (Get-FileHash -LiteralPath $safeJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
  } else {
    $null
  }
  $failureValue = if ([string]::IsNullOrWhiteSpace($Failure)) { $null } else { $Failure }
  $promotionBoundary = if ($Conclusion -eq 'PASS') {
    'MANUAL_PROMOTION_REQUIRED: this script did not deploy, replace, or restart the pre-release instance.'
  } else {
    'FORBIDDEN: resolve every BLOCKED check before any manual promotion review.'
  }
  $checkEntries = $results.ToArray()
  $payload = [ordered]@{
    schema = 'ai-profit-os/mobile-isolated-prerelease-candidate-gate/v1'
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    conclusion = $Conclusion
    environment = 'QA'
    purpose = 'isolated pre-release validation only; no staging replacement or production promotion was performed'
    candidate = [ordered]@{
      jarFileName = [IO.Path]::GetFileName($safeJarPath)
      jarSha256 = $candidateJarSha256
      serverPort = $ServerPort
      keepRunning = [bool]($KeepRunning -and $Conclusion -eq 'PASS')
    }
    database = [ordered]@{
      host = $MySqlHost
      port = $MySqlPort
      name = $MySqlDatabase
      usernameRecorded = $false
      passwordRecorded = $false
    }
    secrets = [ordered]@{
      mysqlPasswordSource = 'environment-name-only'
      crossPermissionTokenSource = 'environment-name-only'
      diagnosticsBossTokenSource = 'environment-name-only'
      redisPasswordSource = 'environment-name-only'
      valuesRecorded = $false
    }
    videoTicketStore = [ordered]@{
      mode = 'REDIS_SHARED'
      requireSharedVideoTickets = $true
      redisHost = $RedisHost
      redisPort = $RedisPort
      redisPasswordRecorded = $false
      stickySessionAccepted = $false
    }
    flyway = [ordered]@{
      expectedVersion = if ($null -eq $flywaySource) { $null } else { $flywaySource.version }
      latestFile = if ($null -eq $flywaySource) { $null } else { $flywaySource.fileName }
      sourceSynchronized = ($null -ne $flywaySource)
    }
    checks = $checkEntries
    failure = $failureValue
    manualPromotion = $promotionBoundary
  }

  $reportPath = Join-Path $Directory 'candidate-gate.json'
  $payload | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
  return $reportPath
}

if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  $EvidenceRoot = $defaultEvidenceRoot
}
$evidenceRootFull = [IO.Path]::GetFullPath($EvidenceRoot)
$projectRootFull = [IO.Path]::GetFullPath($projectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
if (-not $evidenceRootFull.StartsWith($projectRootFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'EvidenceRoot must stay inside this checkout so the gate cannot write to an arbitrary path.'
}
$evidenceDirectory = Join-Path $evidenceRootFull $runId
$reportPath = $null

try {
  New-Item -ItemType Directory -Path $evidenceDirectory -Force -ErrorAction Stop | Out-Null
  if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
    throw "Candidate Jar was not found: $JarPath"
  }
  Assert-IsolatedCandidateInputs
  $flywaySource = Get-ReleaseFlywaySource -ProjectRoot $projectRoot
  Add-GateResult -Check 'Isolation preflight' -Status 'PASS' `
    -Evidence "QA only; candidate port $ServerPort and local MySQL port $MySqlPort are isolated from 18081/3307."
  Add-GateResult -Check "Flyway source synchronization V$($flywaySource.version)" -Status 'PASS' `
    -Evidence "MySQL and H2 source trees agree on $($flywaySource.fileName)."
  Add-GateResult -Check 'Shared Redis preflight' -Status 'PASS' `
    -Evidence "Dedicated loopback Redis is present on $RedisHost`:$RedisPort; sticky sessions are not accepted for this candidate."

  $javaCommand = Get-Command java.exe -ErrorAction Stop
  $javaPath = if ($javaCommand.Source) { $javaCommand.Source } else { $javaCommand.Path }
  if ([string]::IsNullOrWhiteSpace($javaPath)) {
    throw 'java.exe was not resolved for the isolated candidate.'
  }

  $mysqlPasswordPlain = Get-EnvironmentSecret -Name $MySqlPasswordEnvironmentName
  $crossPermissionTokenPlain = Get-EnvironmentSecret -Name $CrossPermissionTokenEnvironmentName
  $diagnosticsBossTokenPlain = Get-EnvironmentSecret -Name $DiagnosticsBossTokenEnvironmentName
  $redisPasswordPlain = Get-EnvironmentSecret -Name $RedisPasswordEnvironmentName
  Add-GateResult -Check 'Secret handling' -Status 'PASS' `
    -Evidence 'MySQL password, Redis password, least-privilege token, and BOSS diagnostics token were supplied only through environment-variable names; values are not recorded.'

  $jarFullPath = (Resolve-Path -LiteralPath $JarPath).Path
  $startInfo = New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName = $javaPath
  $startInfo.Arguments = '-jar "' + $jarFullPath + '"'
  $startInfo.WorkingDirectory = Split-Path -Parent $jarFullPath
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.CreateNoWindow = $true
  Remove-InheritedSensitiveEnvironment -StartInfo $startInfo
  $startInfo.EnvironmentVariables['APP_ENV'] = 'QA'
  $startInfo.EnvironmentVariables['MYSQL_HOST'] = $MySqlHost
  $startInfo.EnvironmentVariables['MYSQL_PORT'] = [string]$MySqlPort
  $startInfo.EnvironmentVariables['MYSQL_DATABASE'] = $MySqlDatabase
  $startInfo.EnvironmentVariables['MYSQL_USERNAME'] = $MySqlUsername
  $startInfo.EnvironmentVariables['MYSQL_PASSWORD'] = $mysqlPasswordPlain
  $startInfo.EnvironmentVariables['MYSQL_SSL_MODE'] = 'DISABLED'
  $startInfo.EnvironmentVariables['SERVER_PORT'] = [string]$ServerPort
  $startInfo.EnvironmentVariables['SPRING_FLYWAY_ENABLED'] = 'true'
  $startInfo.EnvironmentVariables['SPRING_FLYWAY_VALIDATE_ON_MIGRATE'] = 'true'
  $startInfo.EnvironmentVariables['SPRING_FLYWAY_CLEAN_DISABLED'] = 'true'
  $startInfo.EnvironmentVariables['SPRING_FLYWAY_BASELINE_ON_MIGRATE'] = 'false'
  $startInfo.EnvironmentVariables['SPRING_FLYWAY_OUT_OF_ORDER'] = 'false'
  $startInfo.EnvironmentVariables['MOBILE_REQUIRE_SHARED_VIDEO_TICKETS'] = 'true'
  $startInfo.EnvironmentVariables['SPRING_DATA_REDIS_HOST'] = $RedisHost
  $startInfo.EnvironmentVariables['SPRING_DATA_REDIS_PORT'] = [string]$RedisPort
  $startInfo.EnvironmentVariables['SPRING_DATA_REDIS_PASSWORD'] = $redisPasswordPlain

  $candidateProcess = New-Object Diagnostics.Process
  $candidateProcess.StartInfo = $startInfo
  if (-not $candidateProcess.Start()) {
    throw 'The isolated candidate process could not be started.'
  }
  # Drain output without persisting raw application logs. This prevents a credential or
  # playback-ticket value from becoming part of release evidence.
  $candidateProcess.BeginOutputReadLine()
  $candidateProcess.BeginErrorReadLine()
  $mysqlPasswordPlain = $null
  $redisPasswordPlain = $null

  $healthUri = "http://127.0.0.1:$ServerPort/api/health"
  $healthResponse = $null
  $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    if ($candidateProcess.HasExited) {
      throw 'The isolated candidate stopped before health verification completed.'
    }
    try {
      $candidate = Invoke-HttpGet -Uri $healthUri -TimeoutSeconds 2
      if ($candidate.statusCode -eq 200) {
        $healthResponse = $candidate
        break
      }
    }
    catch {
      # No response while the candidate is still starting. Raw process output is deliberately discarded.
    }
    Start-Sleep -Seconds 1
  }
  if ($null -eq $healthResponse) {
    throw "The isolated candidate did not become healthy within $StartupTimeoutSeconds seconds."
  }

  $health = Get-ApiData -Content $healthResponse.content
  if ([string]$health.status -ne 'UP') { throw 'The candidate public liveness status is not UP.' }

  $diagnosticsAuthorization = if ($diagnosticsBossTokenPlain.Trim().StartsWith('Bearer ', [StringComparison]::OrdinalIgnoreCase)) {
    $diagnosticsBossTokenPlain.Trim()
  } else {
    'Bearer ' + $diagnosticsBossTokenPlain.Trim()
  }
  $diagnosticsResponse = Invoke-HttpGet -Uri "$healthUri/diagnostics" -Headers @{ Authorization = $diagnosticsAuthorization }
  $diagnosticsBossTokenPlain = $null
  $diagnosticsAuthorization = $null
  if ($diagnosticsResponse.statusCode -ne 200) {
    throw "The authenticated diagnostics endpoint returned HTTP $($diagnosticsResponse.statusCode)."
  }
  $diagnostics = Get-ApiData -Content $diagnosticsResponse.content
  foreach ($property in @('status', 'environment', 'databaseMigrationVersion', 'databasePort', 'databaseName')) {
    if ($null -eq $diagnostics.PSObject.Properties[$property]) {
      throw "The diagnostics response omitted required field '$property'."
    }
  }
  if ([string]$diagnostics.status -ne 'UP') { throw 'The candidate diagnostics status is not UP.' }
  if ([string]$diagnostics.environment -ne 'QA') { throw 'The candidate diagnostics did not report QA environment.' }
  if ([string]$diagnostics.databaseMigrationVersion -ne [string]$flywaySource.version) {
    throw "The isolated candidate diagnostics did not report synchronized Flyway V$($flywaySource.version)."
  }
  if ([int]$diagnostics.databasePort -ne $MySqlPort -or [int]$diagnostics.databasePort -eq 3307) {
    throw 'The candidate diagnostics database port does not match the isolated MySQL port.'
  }
  if (-not [string]$diagnostics.databaseName -or -not ([string]$diagnostics.databaseName).Equals($MySqlDatabase, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The candidate diagnostics database name does not match the isolated QA database.'
  }
  Add-GateResult -Check "Authenticated diagnostics and Flyway V$($flywaySource.version)" -Status 'PASS' `
    -Evidence "Public liveness is UP; authenticated diagnostics reports QA and Flyway V$($flywaySource.version) on the isolated QA database." `
    -HttpStatus $diagnosticsResponse.statusCode -RequestId $diagnosticsResponse.requestId
  Add-GateResult -Check 'Shared Redis video-ticket store' -Status 'PASS' `
    -Evidence 'Candidate reached health only after required shared Redis ticket-store startup verification; no local-memory or sticky-session fallback is accepted.' `
    -HttpStatus $healthResponse.statusCode -RequestId $healthResponse.requestId

  $baseUri = "http://127.0.0.1:$ServerPort"
  Test-ExpectedStatus -Check 'Unauthenticated mobile version endpoint' `
    -Uri "$baseUri/api/mobile/version?platform=android&version=0.1.0" -ExpectedStatus 401
  Test-ExpectedStatus -Check 'Unauthenticated current-user endpoint' `
    -Uri "$baseUri/api/auth/me" -ExpectedStatus 401
  Test-ExpectedStatus -Check 'Unauthenticated protected stores endpoint' `
    -Uri "$baseUri/api/stores" -ExpectedStatus 401

  $authorization = if ($crossPermissionTokenPlain.Trim().StartsWith('Bearer ', [StringComparison]::OrdinalIgnoreCase)) {
    $crossPermissionTokenPlain.Trim()
  } else {
    'Bearer ' + $crossPermissionTokenPlain.Trim()
  }
  Test-ExpectedStatus -Check 'Least-privilege cross-permission endpoint' `
    -Uri ($baseUri + $CrossPermissionEndpoint) -ExpectedStatus 403 -Headers @{ Authorization = $authorization }
  $crossPermissionTokenPlain = $null

  $gateStatus = 'PASS'
  Add-GateResult -Check 'Manual promotion boundary' -Status 'PASS' `
    -Evidence 'Candidate verification passed. Any promotion remains an explicit human action; no replacement was attempted.'
}
catch {
  $failureMessage = $_.Exception.Message
  Add-GateResult -Check 'Candidate gate execution' -Status 'BLOCKED' -Evidence $failureMessage
  $gateStatus = 'BLOCKED'
}
finally {
  if ($candidateProcess -and -not $candidateProcess.HasExited -and (-not $KeepRunning -or $gateStatus -ne 'PASS')) {
    Stop-Process -Id $candidateProcess.Id -Force -ErrorAction SilentlyContinue
    $candidateProcess.WaitForExit()
  }
  if (Test-Path -LiteralPath $evidenceDirectory) {
    $reportPath = Write-GateEvidence -Directory $evidenceDirectory -Conclusion $gateStatus -Failure $failureMessage
  }
  $mysqlPasswordPlain = $null
  $crossPermissionTokenPlain = $null
  $diagnosticsBossTokenPlain = $null
  $diagnosticsAuthorization = $null
  $redisPasswordPlain = $null
  if ($candidateProcess) { $candidateProcess.Dispose() }
}

if ($gateStatus -ne 'PASS') {
  $suffix = if ($reportPath) { " Sanitized evidence: $reportPath" } else { '' }
  throw "ISOLATED PRE-RELEASE VALIDATION BLOCKED.$suffix"
}

if ($KeepRunning) {
  Write-Host "PASS: isolated QA candidate is listening on http://127.0.0.1:$ServerPort. MANUAL_PROMOTION_REQUIRED; no pre-release instance was replaced." -ForegroundColor Green
} else {
  Write-Host "PASS: isolated QA candidate verification completed and the candidate process was stopped. MANUAL_PROMOTION_REQUIRED; no pre-release instance was replaced." -ForegroundColor Green
}
Write-Host "Sanitized evidence: $reportPath"
