[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$JarPath,

  [ValidateRange(1024, 65535)]
  [int]$ServerPort = 18110,

  [ValidatePattern('^(127\.0\.0\.1|localhost|::1)$')]
  [string]$MySqlHost = '127.0.0.1',

  [ValidateRange(1024, 65535)]
  [int]$MySqlPort = 3308,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^(?i)ai_profit_(qa|test)_[a-z0-9_]+$')]
  [string]$MySqlDatabase,

  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$MySqlUsername,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$PasswordEnvironmentName = 'QA_MYSQL_PASSWORD',

  [ValidateRange(1, 300)]
  [int]$StartupTimeoutSeconds = 90,

  # This records the intended QA topology. A locally started candidate is one process, but a
  # multi-instance QA deployment must select REDIS_SHARED or STICKY_SESSION explicitly.
  [ValidateRange(1, 64)]
  [int]$ExpectedApplicationInstances = 1,

  [ValidateSet('LOCAL_SINGLE_INSTANCE', 'REDIS_SHARED', 'STICKY_SESSION')]
  [string]$VideoTicketMode = 'LOCAL_SINGLE_INSTANCE',

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$RedisHostEnvironmentName = 'QA_REDIS_HOST',

  [ValidateRange(1, 65535)]
  [int]$RedisPort = 6379,

  [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
  [string]$RedisPasswordEnvironmentName = 'QA_REDIS_PASSWORD',

  # A reference is deliberately a documentation anchor, never a URL or a ticket-bearing path.
  [ValidatePattern('^$|^[A-Za-z0-9._/-]+#[A-Za-z0-9._-]+$')]
  [string]$StickySessionConstraintReference = '',

  [string]$EvidenceRoot = '',

  [switch]$Apply,
  [switch]$AuthorizeQaCandidateStart,
  [switch]$KeepRunning
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  $EvidenceRoot = Join-Path $projectRoot 'output\release-evidence'
}
Import-Module (Join-Path $PSScriptRoot 'QAReleaseCommon.psm1') -Force
$run = New-QAReleaseRun -EvidenceRoot $EvidenceRoot -Category 'qa/candidate'
$reportPath = Join-Path $run.directory 'qa-candidate.json'
$candidateProcess = $null
$result = [ordered]@{
  schema = 'ai-profit-os/qa-release-candidate/v1'
  runId = $run.id
  generatedAt = [DateTime]::UtcNow.ToString('o')
  environment = 'QA'
  applyRequested = [bool]$Apply
  explicitAuthorization = [bool]$AuthorizeQaCandidateStart
  productionDatabaseAccess = $false
  candidate = [ordered]@{ jarSha256 = $null; serverPort = $ServerPort; keepRunning = [bool]$KeepRunning }
  database = [ordered]@{ host = $MySqlHost; port = $MySqlPort; name = $MySqlDatabase; usernameRecorded = $false }
  videoTickets = [ordered]@{
    expectedApplicationInstances = $ExpectedApplicationInstances
    mode = $VideoTicketMode
    sharedStoreRequired = $VideoTicketMode -eq 'REDIS_SHARED'
    stickySessionConstraintReference = if ($VideoTicketMode -eq 'STICKY_SESSION') { $StickySessionConstraintReference } else { $null }
    redisHostRecorded = $false
    redisPasswordRecorded = $false
    ticketQueryParametersRecorded = $false
  }
  health = $null
  status = 'PLAN_ONLY'
}

function Test-ListeningPort {
  param([Parameter(Mandatory = $true)][int]$Port)
  if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    return @(
      Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    ).Count -gt 0
  }
  return @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
      Where-Object { $_.Port -eq $Port }).Count -gt 0
}

function Get-QAHealth {
  param([Parameter(Mandatory = $true)][string]$Uri)
  $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Method Get -TimeoutSec 3 -ErrorAction Stop
  $payload = $response.Content | ConvertFrom-Json -ErrorAction Stop
  $data = if ($null -ne $payload.PSObject.Properties['data']) { $payload.data } else { $payload }
  $requestId = [string]$response.Headers['X-Request-Id']
  if ([string]::IsNullOrWhiteSpace($requestId)) { $requestId = [string]$response.Headers['X-Request-ID'] }
  return [pscustomobject]@{ data = $data; requestId = $requestId; statusCode = [int]$response.StatusCode }
}

function Assert-VideoTicketTopology {
  param(
    [Parameter(Mandatory = $true)][int]$InstanceCount,
    [Parameter(Mandatory = $true)][string]$Mode,
    [Parameter(Mandatory = $true)][AllowEmptyString()][string]$StickySessionReference
  )

  if ($InstanceCount -gt 1 -and $Mode -eq 'LOCAL_SINGLE_INSTANCE') {
    throw 'Multi-instance QA requires -VideoTicketMode REDIS_SHARED or STICKY_SESSION; local video tickets are not safe across instances.'
  }
  if ($Mode -eq 'STICKY_SESSION' -and [string]::IsNullOrWhiteSpace($StickySessionReference)) {
    throw 'STICKY_SESSION requires a documentation-only -StickySessionConstraintReference, for example docs/video-playback-ticket-gate.md#sticky-session-constraint.'
  }
  if ($Mode -ne 'STICKY_SESSION' -and -not [string]::IsNullOrWhiteSpace($StickySessionReference)) {
    throw 'Sticky-session evidence may only be recorded with -VideoTicketMode STICKY_SESSION.'
  }
}

try {
  Assert-QAReleaseDatabaseTarget -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlDatabase $MySqlDatabase -MySqlUsername $MySqlUsername
  Assert-VideoTicketTopology -InstanceCount $ExpectedApplicationInstances -Mode $VideoTicketMode -StickySessionReference $StickySessionConstraintReference
  if ($ServerPort -eq 18081 -or $ServerPort -eq 3307 -or $ServerPort -eq $MySqlPort) {
    throw 'QA candidate port conflicts with a protected or database port.'
  }
  if (Test-ListeningPort -Port $ServerPort) {
    throw "QA candidate port $ServerPort is already in use; this script never stops an existing process."
  }
  if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "QA candidate JAR was not found: $JarPath" }
  $jarFullPath = (Resolve-Path -LiteralPath $JarPath).Path
  $result.candidate.jarSha256 = (Get-FileHash -LiteralPath $jarFullPath -Algorithm SHA256).Hash

  if (-not $Apply) {
    $result.status = 'PLAN_ONLY'
    $result.message = 'No process or database connection was started. Re-run only after explicit authorization with -Apply -AuthorizeQaCandidateStart.'
    return
  }
  if (-not $AuthorizeQaCandidateStart) {
    throw 'QA candidate start can migrate a QA database and requires explicit authorization. Re-run only after approval with -Apply -AuthorizeQaCandidateStart.'
  }

  $password = Get-QAReleaseSecret -EnvironmentName $PasswordEnvironmentName
  $redisHost = $null
  $redisPassword = $null
  if ($VideoTicketMode -eq 'REDIS_SHARED') {
    # Values are taken only after explicit start authorization and are never added to evidence,
    # command lines, error messages, or script output.
    $redisHost = Get-QAReleaseSecret -EnvironmentName $RedisHostEnvironmentName
    $redisPassword = Get-QAReleaseSecret -EnvironmentName $RedisPasswordEnvironmentName
    Assert-QAReleaseRedisTarget -RedisHost $redisHost -RedisPort $RedisPort
  }
  $java = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($null -eq $java) { $java = Get-Command java -ErrorAction SilentlyContinue }
  if ($null -eq $java) { throw 'Java runtime is required for a QA candidate start.' }
  $javaPath = if ($java.Source) { $java.Source } else { $java.Path }
  $startInfo = New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName = $javaPath
  $startInfo.Arguments = '-jar "' + $jarFullPath + '"'
  $startInfo.WorkingDirectory = Split-Path -Parent $jarFullPath
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.EnvironmentVariables.Clear()
  $startInfo.EnvironmentVariables['Path'] = $env:Path
  $startInfo.EnvironmentVariables['SystemRoot'] = $env:SystemRoot
  $startInfo.EnvironmentVariables['ComSpec'] = $env:ComSpec
  $startInfo.EnvironmentVariables['APP_ENV'] = 'QA'
  $startInfo.EnvironmentVariables['SPRING_PROFILES_ACTIVE'] = 'qa'
  $startInfo.EnvironmentVariables['SERVER_PORT'] = [string]$ServerPort
  $startInfo.EnvironmentVariables['MYSQL_HOST'] = $MySqlHost
  $startInfo.EnvironmentVariables['MYSQL_PORT'] = [string]$MySqlPort
  $startInfo.EnvironmentVariables['MYSQL_DATABASE'] = $MySqlDatabase
  $startInfo.EnvironmentVariables['MYSQL_USERNAME'] = $MySqlUsername
  $startInfo.EnvironmentVariables['MYSQL_PASSWORD'] = $password
  $startInfo.EnvironmentVariables['MYSQL_SSL_MODE'] = 'DISABLED'
  $startInfo.EnvironmentVariables['APP_SEED_DEMO_ENABLED'] = 'false'
  $startInfo.EnvironmentVariables['DEEPSEEK_ENABLED'] = 'false'
  $startInfo.EnvironmentVariables['SERVER_TOMCAT_ACCESSLOG_ENABLED'] = 'false'
  $startInfo.EnvironmentVariables['SPRING_MVC_LOG_REQUEST_DETAILS'] = 'false'
  if ($VideoTicketMode -eq 'REDIS_SHARED') {
    $startInfo.EnvironmentVariables['MOBILE_REQUIRE_SHARED_VIDEO_TICKETS'] = 'true'
    $startInfo.EnvironmentVariables['SPRING_DATA_REDIS_HOST'] = $redisHost
    $startInfo.EnvironmentVariables['SPRING_DATA_REDIS_PORT'] = [string]$RedisPort
    $startInfo.EnvironmentVariables['SPRING_DATA_REDIS_PASSWORD'] = $redisPassword
  }
  else {
    $startInfo.EnvironmentVariables['MOBILE_REQUIRE_SHARED_VIDEO_TICKETS'] = 'false'
  }
  $candidateProcess = New-Object Diagnostics.Process
  $candidateProcess.StartInfo = $startInfo
  if (-not $candidateProcess.Start()) { throw 'Could not start the QA candidate process.' }
  $password = $null
  $redisHost = $null
  $redisPassword = $null

  $healthUri = "http://127.0.0.1:$ServerPort/api/health"
  $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
  $health = $null
  while ((Get-Date) -lt $deadline) {
    if ($candidateProcess.HasExited) { throw 'QA candidate stopped before health verification completed.' }
    try {
      $health = Get-QAHealth -Uri $healthUri
      if ($health.statusCode -eq 200) { break }
    }
    catch { Start-Sleep -Seconds 1 }
  }
  if ($null -eq $health) { throw "QA candidate did not become healthy within $StartupTimeoutSeconds seconds." }
  if ([string]$health.data.status -ne 'UP' -or [string]$health.data.environment -ne 'QA') {
    throw 'Candidate health did not report UP/QA.'
  }
  if ([int]$health.data.databasePort -ne $MySqlPort -or [int]$health.data.databasePort -eq 3307) {
    throw 'Candidate health reported an unexpected or protected database port.'
  }
  if (-not ([string]$health.data.databaseName).Equals($MySqlDatabase, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Candidate health reported a database other than the requested QA database.'
  }
  $result.health = [ordered]@{
    status = [string]$health.data.status
    environment = [string]$health.data.environment
    flywayVersion = [string]$health.data.databaseMigrationVersion
    databasePort = [int]$health.data.databasePort
    databaseName = [string]$health.data.databaseName
    httpStatus = $health.statusCode
    requestId = $health.requestId
  }
  $result.status = 'PASS_QA_CANDIDATE_STARTED'
}
catch {
  $result.status = 'BLOCKED'
  $result.message = $_.Exception.Message
  throw
}
finally {
  if ($candidateProcess -and -not $candidateProcess.HasExited -and -not $KeepRunning) {
    Stop-Process -Id $candidateProcess.Id -Force -ErrorAction SilentlyContinue
    $candidateProcess.WaitForExit()
  }
  Write-QAReleaseJson -Value $result -Path $reportPath
  Write-Host "Sanitized QA candidate evidence: $reportPath"
  if ($candidateProcess) { $candidateProcess.Dispose() }
}
