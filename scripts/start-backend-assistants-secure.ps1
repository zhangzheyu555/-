[CmdletBinding()]
param(
  [string]$MySqlHost = $(if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }),
  [ValidateRange(1, 65535)][int]$MySqlPort = $(if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3307 }),
  [string]$MySqlDatabase = $env:MYSQL_DATABASE,
  [string]$MySqlUsername = $env:MYSQL_USERNAME,
  [ValidateSet('DISABLED', 'REQUIRED', 'VERIFY_CA', 'VERIFY_IDENTITY')]
  [string]$MySqlSslMode = $(if ($env:MYSQL_SSL_MODE) { $env:MYSQL_SSL_MODE } else { 'DISABLED' }),
  [Security.SecureString]$MySqlPasswordInput,
  [ValidateSet('LOCAL', 'TEST', 'STAGING')][string]$AppEnvironment = $(if ($env:APP_ENV) { $env:APP_ENV } else { 'STAGING' }),
  [ValidateRange(1, 65535)][int]$CandidatePort = 18082,
  [string]$JarPath,
  [string]$BackendWorkingDirectory,
  [string]$RuntimeDirectory,
  [ValidateRange(5, 300)][int]$TimeoutSeconds = 45,
  [switch]$PromoteTo18081,
  [switch]$PauseBeforePromotion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
if ([string]::IsNullOrWhiteSpace($JarPath)) {
  $JarPath = Join-Path $PSScriptRoot '..\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar'
}
if ([string]::IsNullOrWhiteSpace($BackendWorkingDirectory)) {
  $BackendWorkingDirectory = Join-Path $PSScriptRoot '..\backend'
}
. (Join-Path $PSScriptRoot 'assistant-runtime-config.ps1')

function Read-RequiredText {
  param([Parameter(Mandatory)][string]$Prompt, [AllowNull()][string]$Value)
  $result = if ([string]::IsNullOrWhiteSpace($Value)) { Read-Host $Prompt } else { $Value }
  if ([string]::IsNullOrWhiteSpace($result)) { throw "$Prompt 不能为空。" }
  return $result.Trim()
}

function Assert-PortFree {
  param([Parameter(Mandatory)][int]$Port)
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "端口 $Port 正在被现有服务使用；未执行停止或替换。"
  }
}

function Wait-ForBackendHealth {
  param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][Diagnostics.Process]$Process)
  for ($attempt = 1; $attempt -le 30; $attempt++) {
    if ($Process.HasExited) { throw 'Java 进程在健康检查通过前退出。' }
    try {
      $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 3
      if ($response.success -and $response.data.status -eq 'UP') { return $response.data }
    } catch { }
    Start-Sleep -Seconds 2
  }
  throw '后端未在 60 秒内通过健康检查。'
}

function Assert-ExpectedBackendHealth {
  param(
    [Parameter(Mandatory)]$Health,
    [Parameter(Mandatory)][string]$ExpectedEnvironment,
    [Parameter(Mandatory)][int]$ExpectedDatabasePort,
    [Parameter(Mandatory)][string]$ExpectedDatabaseName
  )
  if ($Health.environment -ne $ExpectedEnvironment -or
      [int]$Health.databasePort -ne $ExpectedDatabasePort -or
      $Health.databaseName -ne $ExpectedDatabaseName -or
      $Health.databaseAccountScope -ne 'LOCAL_SCOPED' -or
      [string]::IsNullOrWhiteSpace([string]$Health.databaseMigrationVersion)) {
    throw '候选健康响应与预期环境、3307 目标库、受限账号或 Flyway 状态不一致。'
  }
}

function Write-SafeHealth {
  param([Parameter(Mandatory)]$Health, [Parameter(Mandatory)][int]$Port)
  Write-Host ("健康检查通过：端口={0}，状态={1}，环境={2}，Flyway={3}，账号范围={4}。" -f
      $Port, $Health.status, $Health.environment, $Health.databaseMigrationVersion,
      $Health.databaseAccountScope) -ForegroundColor Green
}

function Assert-UnauthenticatedAssistantGate {
  param(
    [Parameter(Mandatory)][int]$Port,
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)][string]$Label
  )
  $handler = [System.Net.Http.HttpClientHandler]::new()
  $handler.AllowAutoRedirect = $false
  $client = [System.Net.Http.HttpClient]::new($handler)
  $client.Timeout = [TimeSpan]::FromSeconds(5)
  $request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Get, "http://127.0.0.1:$Port$Path")
  try {
    try {
      $response = $client.SendAsync($request).GetAwaiter().GetResult()
    } catch {
      throw "$Label 认证门禁未在 5 秒内响应；拒绝继续。"
    }
    try {
      if ([int]$response.StatusCode -ne 401) {
        throw "$Label 未登录门禁没有返回 401；拒绝继续。"
      }
      Write-Host "$Label 认证门禁通过：端口=$Port，未登录=401。" -ForegroundColor Green
    } finally {
      $response.Dispose()
    }
  } finally {
    $request.Dispose()
    $client.Dispose()
    $handler.Dispose()
  }
}

function Test-AssistantUpstreamHealth {
  param(
    [Parameter(Mandatory)][string]$Label,
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$Credential,
    [Parameter(Mandatory)][string]$HealthPath,
    [Parameter(Mandatory)][int]$Timeout
  )
  $handler = [System.Net.Http.HttpClientHandler]::new()
  $handler.AllowAutoRedirect = $false
  $client = [System.Net.Http.HttpClient]::new($handler)
  $client.Timeout = [TimeSpan]::FromSeconds($Timeout)
  $request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Get, ($BaseUrl.TrimEnd('/') + $HealthPath))
  $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Credential)
  try {
    try {
      $response = $client.SendAsync($request).GetAwaiter().GetResult()
    } catch {
      throw "$Label 上游健康检查未通过：UNAVAILABLE。"
    }
    try {
      $statusCode = [int]$response.StatusCode
      if ($statusCode -ge 200 -and $statusCode -lt 300) {
        Write-Host "$Label 上游健康检查通过：READY（未显示地址、响应或凭据）。" -ForegroundColor Green
        return
      }
      if ($statusCode -eq 401 -or $statusCode -eq 403) {
        throw "$Label 上游健康检查未通过：AUTH_FAILED。"
      }
      throw "$Label 上游健康检查未通过：UNAVAILABLE。"
    } finally {
      $response.Dispose()
    }
  } finally {
    $request.Dispose()
    $client.Dispose()
    $handler.Dispose()
  }
}

function Assert-AssistantProviderEnvironment {
  param([Parameter(Mandatory)][hashtable]$Environment)
  $businessNames = @('DEEPSEEK_ENABLED', 'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL', 'DEEPSEEK_MODEL')
  $employeeNames = @('EMPLOYEE_ASSISTANT_PROVIDER')
  $mode = [string]$Environment.EMPLOYEE_ASSISTANT_PROVIDER
  if ($mode -eq 'REMOTE') {
    $employeeNames += @('EMPLOYEE_ASSISTANT_URL', 'EMPLOYEE_ASSISTANT_API_TOKEN')
  } elseif ($mode -eq 'MODEL') {
    $employeeNames += @('EMPLOYEE_ASSISTANT_MODEL_URL', 'EMPLOYEE_ASSISTANT_MODEL_API_KEY', 'EMPLOYEE_ASSISTANT_MODEL_NAME')
  } else {
    throw '员工服务助手安全配置源的模式无效。'
  }
  foreach ($name in @($businessNames + $employeeNames)) {
    if (-not $Environment.ContainsKey($name) -or [string]::IsNullOrWhiteSpace([string]$Environment[$name])) {
      throw '双助手安全配置源缺少必填项。'
    }
  }
  $forbiddenEmployeeNames = if ($mode -eq 'REMOTE') {
    @('EMPLOYEE_ASSISTANT_MODEL_URL', 'EMPLOYEE_ASSISTANT_MODEL_API_KEY', 'EMPLOYEE_ASSISTANT_MODEL_NAME')
  } else {
    @('EMPLOYEE_ASSISTANT_URL', 'EMPLOYEE_ASSISTANT_API_TOKEN')
  }
  if (@($forbiddenEmployeeNames | Where-Object { $Environment.ContainsKey($_) }).Count -gt 0) {
    throw '员工服务助手安全配置源混入了另一模式变量。'
  }
  Write-Host '双助手安全配置源完整：DEEPSEEK_* 与 EMPLOYEE_ASSISTANT_* 将被分别白名单注入。' -ForegroundColor Green
}

function Get-VerifiedWorkspaceJavaOn18081 {
  param([Parameter(Mandatory)][string]$ExpectedBackendDirectory)
  $listeners = @(Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue)
  if ($listeners.Count -eq 0) { return $null }
  if ($listeners.Count -ne 1) { throw '18081 存在多个监听项，拒绝自动替换。' }

  $processId = $listeners[0].OwningProcess
  $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction Stop
  if ($process.Name -notmatch '^(?i:javaw?(?:\.exe)?)$' -or [string]::IsNullOrWhiteSpace($process.CommandLine)) {
    throw '18081 监听进程不是可验证的 Java；拒绝停止。'
  }
  $jarMatch = [regex]::Match($process.CommandLine, '(?i)(?:^|\s)-jar\s+(?:"(?<quoted>[^"]+)"|(?<plain>\S+))')
  if (-not $jarMatch.Success) { throw '18081 Java 命令行缺少可验证的 -jar 参数；拒绝停止。' }
  $jarArgument = if ($jarMatch.Groups['quoted'].Success) { $jarMatch.Groups['quoted'].Value } else { $jarMatch.Groups['plain'].Value }

  $jcmd = Get-Command jcmd.exe -ErrorAction SilentlyContinue
  if (-not $jcmd) { throw '缺少 jcmd，无法安全核对 18081 Java 工作目录；拒绝停止。' }
  $properties = @(& $jcmd.Source $processId VM.system_properties 2>$null)
  $userDirLine = $properties | Where-Object { $_ -like 'user.dir=*' } | Select-Object -First 1
  if (-not $userDirLine) { throw '无法取得 18081 Java 工作目录；拒绝停止。' }
  $userDirectory = [Text.RegularExpressions.Regex]::Unescape($userDirLine.Substring('user.dir='.Length))
  $resolvedUserDirectory = [IO.Path]::GetFullPath($userDirectory).TrimEnd('\')
  $resolvedExpectedDirectory = [IO.Path]::GetFullPath($ExpectedBackendDirectory).TrimEnd('\')
  if (-not $resolvedUserDirectory.Equals($resolvedExpectedDirectory, [StringComparison]::OrdinalIgnoreCase)) {
    throw '18081 Java 不属于本工作区 backend 目录；拒绝停止。'
  }

  $resolvedRunningJar = if ([IO.Path]::IsPathRooted($jarArgument)) {
    [IO.Path]::GetFullPath($jarArgument)
  } else {
    [IO.Path]::GetFullPath((Join-Path $resolvedUserDirectory $jarArgument))
  }
  $workspaceRoot = [IO.Path]::GetFullPath((Join-Path $resolvedExpectedDirectory '..')).TrimEnd('\')
  $targetDirectory = [IO.Path]::GetFullPath((Join-Path $resolvedExpectedDirectory 'target')).TrimEnd('\')
  $outputDirectory = [IO.Path]::GetFullPath((Join-Path $workspaceRoot 'output')).TrimEnd('\')
  $isWorkspaceBackendJar = ($resolvedRunningJar.StartsWith($targetDirectory + '\', [StringComparison]::OrdinalIgnoreCase) -or
      $resolvedRunningJar.StartsWith($outputDirectory + '\', [StringComparison]::OrdinalIgnoreCase)) -and
      ([IO.Path]::GetFileName($resolvedRunningJar) -match '^(?i:store-profit-backend(?:-[A-Za-z0-9._-]+)?\.jar)$')
  if (-not $isWorkspaceBackendJar) {
    throw '18081 Java 不是本工作区可验证的后端 JAR；拒绝停止。'
  }
  return [pscustomobject]@{ ProcessId = $processId; JarPath = $resolvedRunningJar }
}

function Stop-VerifiedWorkspaceJavaOn18081 {
  param([Parameter(Mandatory)][string]$ExpectedBackendDirectory)
  $verified = Get-VerifiedWorkspaceJavaOn18081 $ExpectedBackendDirectory
  if (-not $verified) { return $false }
  $confirmation = Read-Host '候选与两套上游门禁均已通过。输入 REPLACE_18081_CONFIRM 才会替换当前 18081'
  if ($confirmation -cne 'REPLACE_18081_CONFIRM') {
    throw '未获得明确维护确认；未停止 18081 服务。'
  }
  Stop-Process -Id $verified.ProcessId -ErrorAction Stop
  for ($attempt = 1; $attempt -le 15; $attempt++) {
    if (-not (Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue)) { return $true }
    Start-Sleep -Seconds 1
  }
  throw '目标 Java 已收到停止请求，但 18081 未释放；未继续启动替换服务。'
}

function Set-ChildBaseEnvironment {
  param(
    [Parameter(Mandatory)][Diagnostics.ProcessStartInfo]$StartInfo,
    [Parameter(Mandatory)][string]$JavaPath
  )
  # Do not inherit Spring, Java-option or assistant overrides from this PowerShell.
  # The child receives only this Windows runtime allowlist plus explicit deployment variables below.
  $StartInfo.Environment.Clear()
  $systemRoot = [Environment]::GetEnvironmentVariable('SystemRoot')
  if ([string]::IsNullOrWhiteSpace($systemRoot)) {
    $systemRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::Windows)
  }
  $javaBin = Split-Path -Parent $JavaPath
  $javaHome = Split-Path -Parent $javaBin
  $baseEnvironment = [ordered]@{
    SystemRoot = $systemRoot
    WINDIR = $systemRoot
    SystemDrive = [Environment]::GetEnvironmentVariable('SystemDrive')
    ComSpec = [Environment]::GetEnvironmentVariable('ComSpec')
    ProgramData = [Environment]::GetEnvironmentVariable('ProgramData')
    ProgramFiles = [Environment]::GetEnvironmentVariable('ProgramFiles')
    'ProgramFiles(x86)' = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    USERPROFILE = [Environment]::GetEnvironmentVariable('USERPROFILE')
    HOMEDRIVE = [Environment]::GetEnvironmentVariable('HOMEDRIVE')
    HOMEPATH = [Environment]::GetEnvironmentVariable('HOMEPATH')
    LOCALAPPDATA = [Environment]::GetEnvironmentVariable('LOCALAPPDATA')
    APPDATA = [Environment]::GetEnvironmentVariable('APPDATA')
    TEMP = [Environment]::GetEnvironmentVariable('TEMP')
    TMP = [Environment]::GetEnvironmentVariable('TMP')
    JAVA_HOME = $javaHome
    PATH = ($javaBin + ';' + (Join-Path $systemRoot 'System32'))
  }
  foreach ($entry in $baseEnvironment.GetEnumerator()) {
    if (-not [string]::IsNullOrWhiteSpace([string]$entry.Value)) {
      $StartInfo.Environment[$entry.Key] = [string]$entry.Value
    }
  }
}

function New-ImmutableJarSnapshot {
  param([Parameter(Mandatory)][string]$SourceJar)

  $resolvedSource = [IO.Path]::GetFullPath($SourceJar)
  if (-not (Test-Path -LiteralPath $resolvedSource -PathType Leaf)) {
    throw '未找到后端 JAR。'
  }
  try {
    $firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSource).Hash
    Start-Sleep -Milliseconds 500
    $secondHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSource).Hash
  } catch {
    throw '后端 JAR 正在被构建或其他进程占用，请等待构建完成后重新运行；18081 未改动。'
  }
  if ($firstHash -cne $secondHash) {
    throw '后端 JAR 在快照前发生变化，请等待构建完成后重新运行；18081 未改动。'
  }
  $outputDirectory = Join-Path (Split-Path -Parent $resolvedSource) '..\output'
  [void](New-Item -ItemType Directory -Path $outputDirectory -Force)
  $snapshot = Join-Path $outputDirectory (
    'store-profit-backend-snapshot-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff') +
    '-' + $firstHash.Substring(0, 12) + '.jar'
  )
  try {
    Copy-Item -LiteralPath $resolvedSource -Destination $snapshot -ErrorAction Stop
    $snapshotHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $snapshot).Hash
    if ($snapshotHash -cne $firstHash) {
      throw 'JAR 快照校验不一致。'
    }
    return [pscustomobject]@{ Path = $snapshot; SHA256 = $snapshotHash; SourceVersion = 'v0.1.0-SNAPSHOT' }
  } catch {
    if (Test-Path -LiteralPath $snapshot) {
      Remove-Item -LiteralPath $snapshot -Force -ErrorAction SilentlyContinue
    }
    throw
  }
}

function Assert-DeploymentContractProbe {
  param([Parameter(Mandatory)][int]$Port, [Parameter(Mandatory)][string]$Label)
  $handler = [System.Net.Http.HttpClientHandler]::new()
  $handler.AllowAutoRedirect = $false
  $client = [System.Net.Http.HttpClient]::new($handler)
  $client.Timeout = [TimeSpan]::FromSeconds(3)
  $content = [System.Net.Http.StringContent]::new(
    '{"message":"probe"}', [Text.Encoding]::UTF8, 'application/json')
  try {
    try {
      $response = $client.PostAsync(
        "http://127.0.0.1:$Port/api/assistant/chat", $content).GetAwaiter().GetResult()
    } catch {
      throw "$Label 部署契约探针未在 3 秒内响应。"
    }
    try {
      if ([int]$response.StatusCode -ne 401) {
        throw "$Label 部署契约探针未返回 401；实际状态=$([int]$response.StatusCode)。"
      }
    } finally {
      $response.Dispose()
    }
  } finally {
    $content.Dispose()
    $client.Dispose()
    $handler.Dispose()
  }
  Write-Host "$Label 部署契约探针通过：post /api/assistant/chat 无认证 → 401（Jackson 可正常反序列化）。" -ForegroundColor Green
}

function Start-BackendChild {
  param(
    [Parameter(Mandatory)][string]$ResolvedJar,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][int]$Port,
    [Parameter(Mandatory)][hashtable]$ChildEnvironment
  )
  Assert-PortFree $Port
  $java = Get-Command java.exe -ErrorAction Stop
  $javaw = Join-Path (Split-Path -Parent $java.Source) 'javaw.exe'
  $runtimeLogDirectory = Join-Path (Split-Path -Parent $WorkingDirectory) 'output\runtime-logs'
  [void](New-Item -ItemType Directory -Path $runtimeLogDirectory -Force)
  $runtimeLogPath = Join-Path $runtimeLogDirectory ("assistant-runtime-{0}-{1}.log" -f $Port, (Get-Date -Format 'yyyyMMdd-HHmmssfff'))
  $info = [Diagnostics.ProcessStartInfo]::new()
  $info.FileName = if (Test-Path -LiteralPath $javaw -PathType Leaf) { $javaw } else { $java.Source }
  $info.Arguments = "-jar `"$ResolvedJar`" --server.port=$Port --logging.file.name=`"$runtimeLogPath`""
  $info.WorkingDirectory = $WorkingDirectory
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true

  Set-ChildBaseEnvironment -StartInfo $info -JavaPath $info.FileName
  # Environment.Clear() above removes DEEPSEEK_*, EMPLOYEE_ASSISTANT_*, APP_ASSISTANT_DEEPSEEK_*,
  # APP_EMPLOYEE_ASSISTANT_*, SPRING_APPLICATION_JSON, SPRING_CONFIG_*, JAVA_TOOL_OPTIONS,
  # JDK_JAVA_OPTIONS and _JAVA_OPTIONS before this white-list injection.
  foreach ($name in $ChildEnvironment.Keys) { $info.Environment[$name] = [string]$ChildEnvironment[$name] }

  $child = [Diagnostics.Process]::new()
  $child.StartInfo = $info
  [void]$child.Start()
  return [pscustomobject]@{ Process = $child; StartInfo = $info; RuntimeLogPath = $runtimeLogPath }
}

function Remove-LaunchSecrets {
  param([Parameter(Mandatory)]$Launch, [Parameter(Mandatory)][hashtable]$Environment)
  foreach ($name in @($Launch.StartInfo.Environment.Keys | Where-Object {
      $_ -match '^(?i:DEEPSEEK_|EMPLOYEE_ASSISTANT_)' -or $_ -eq 'MYSQL_PASSWORD'
  })) { [void]$Launch.StartInfo.Environment.Remove($name) }
  foreach ($name in @($Environment.Keys)) { [void]$Environment.Remove($name) }
}

function New-ChildEnvironment {
  param(
    [Parameter(Mandatory)][int]$Port,
    [Parameter(Mandatory)][string]$DatabasePassword,
    [Parameter(Mandatory)]$Configuration
  )
  $environment = @{
    APP_ENV = $AppEnvironment
    SERVER_PORT = [string]$Port
    MYSQL_HOST = $MySqlHost
    MYSQL_PORT = [string]$MySqlPort
    MYSQL_DATABASE = $MySqlDatabase
    MYSQL_USERNAME = $MySqlUsername
    MYSQL_PASSWORD = $DatabasePassword
    MYSQL_SSL_MODE = $MySqlSslMode
    APP_SEED_DEMO_ENABLED = 'false'
    APP_SEED_LEGACY_EMPLOYEE_ENABLED = 'false'
    APP_MIGRATION_AUTO_RUN = 'false'
  }
  if ($Port -eq $CandidatePort) {
    # Candidate acceptance must remain read-only against the real database.
    $environment.APP_EXCEPTION_AUTO_RECONCILE_ENABLED = 'false'
  }
  $providerEnvironment = New-AssistantProviderEnvironment $Configuration
  foreach ($name in $providerEnvironment.Keys) {
    $environment[$name] = $providerEnvironment[$name]
  }
  $providerEnvironment.Clear()
  # Mark that variables were injected by the secure launcher through DPAPI.
  # Without this marker the backend treats EMPLOYEE_ASSISTANT_* as untrusted (UNCONFIGURED).
  $environment['ASSISTANT_RUNTIME_SECURED'] = 'true'
  Assert-AssistantProviderEnvironment $environment
  return $environment
}

function Test-AllAssistantUpstreams {
  param([Parameter(Mandatory)]$Configuration, [Parameter(Mandatory)][int]$Timeout)
  $business = Get-AssistantConfigProperty $Configuration 'businessAssistant'
  $employee = Get-AssistantConfigProperty $Configuration 'employeeAssistant'
  [void](Assert-CompleteAssistantRuntimeConfig $Configuration)
  Test-AssistantUpstreamHealth -Label '门店经营助手' `
    -BaseUrl ([string](Get-AssistantConfigProperty $business 'baseUrl')) `
    -Credential ([string](Get-AssistantConfigProperty $business 'apiKey')) -HealthPath '/models' -Timeout $Timeout
  $employeeMode = ([string](Get-AssistantConfigProperty $employee 'provider')).Trim().ToUpperInvariant()
  if ($employeeMode -eq 'REMOTE') {
    Test-AssistantUpstreamHealth -Label '员工服务助手' `
      -BaseUrl ([string](Get-AssistantConfigProperty $employee 'remoteUrl')) `
      -Credential ([string](Get-AssistantConfigProperty $employee 'apiToken')) -HealthPath '/api/v1/health' -Timeout $Timeout
  } else {
    Test-AssistantUpstreamHealth -Label '员工服务助手' `
      -BaseUrl ([string](Get-AssistantConfigProperty $employee 'modelUrl')) `
      -Credential ([string](Get-AssistantConfigProperty $employee 'modelApiKey')) -HealthPath '/models' -Timeout $Timeout
  }
}

$mysqlPassword = $null
$mysqlPointer = [IntPtr]::Zero
$mysqlPlain = $null
$candidateConfiguration = $null
$productionConfiguration = $null
$candidateEnvironment = $null
$productionEnvironment = $null
$candidate = $null
$production = $null
$oldServiceStopped = $false

try {
  $resolvedJar = [IO.Path]::GetFullPath($JarPath)
  $resolvedBackendDirectory = [IO.Path]::GetFullPath($BackendWorkingDirectory)
  if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf)) { throw '未找到后端 JAR；请先完成隔离打包。' }
  if (-not (Test-Path -LiteralPath $resolvedBackendDirectory -PathType Container)) { throw '未找到本工作区 backend 运行目录。' }
  # Create immutable snapshot so Maven cannot overwrite the running JAR.
  $jarSnapshot = New-ImmutableJarSnapshot -SourceJar $resolvedJar
  $resolvedJar = $jarSnapshot.Path
  Write-Host ("JAR 快照已创建：SHA-256={0}" -f $jarSnapshot.SHA256) -ForegroundColor Cyan
  $MySqlDatabase = Read-RequiredText '请输入 MySQL 数据库名称' $MySqlDatabase
  $MySqlUsername = Read-RequiredText '请输入 MySQL 3307 独立应用账号' $MySqlUsername
  if ($MySqlUsername -match '^(?i:root)(?:@|$)') { throw '禁止使用 root 运行后端。' }
  $mysqlPassword = if ($MySqlPasswordInput) { $MySqlPasswordInput } else { Read-Host '请输入 MySQL 应用账号密码（输入不会显示）' -AsSecureString }
  $mysqlPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($mysqlPassword)
  $mysqlPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($mysqlPointer)
  if ([string]::IsNullOrWhiteSpace($mysqlPlain)) { throw '数据库密码不能为空。' }

  $candidateConfiguration = Read-AssistantRuntimeConfig -RuntimeDirectory $RuntimeDirectory
  Test-AllAssistantUpstreams -Configuration $candidateConfiguration -Timeout ([Math]::Min($TimeoutSeconds, 30))
  $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedJar).Hash
  Write-Host ("候选 JAR SHA-256={0}" -f $jarHash) -ForegroundColor Cyan
  Write-Host "正在候选端口 $CandidatePort 启动；当前 18081 保持不动。" -ForegroundColor Cyan
  $candidateEnvironment = New-ChildEnvironment $CandidatePort $mysqlPlain $candidateConfiguration
  $candidate = Start-BackendChild $resolvedJar $resolvedBackendDirectory $CandidatePort $candidateEnvironment
  Remove-LaunchSecrets $candidate $candidateEnvironment
  Clear-AssistantRuntimeConfigPlaintext $candidateConfiguration
  $candidateConfiguration = $null

  $candidateHealth = Wait-ForBackendHealth $CandidatePort $candidate.Process
  Assert-ExpectedBackendHealth $candidateHealth $AppEnvironment $MySqlPort $MySqlDatabase
  Write-SafeHealth $candidateHealth $CandidatePort
  Assert-UnauthenticatedAssistantGate $CandidatePort '/api/assistant/status' '门店经营助手'
  Assert-UnauthenticatedAssistantGate $CandidatePort '/api/employee-assistant/status' '员工服务助手'
  Assert-DeploymentContractProbe $CandidatePort '门店经营助手'

  if (-not $PromoteTo18081) {
    $mysqlPlain = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($mysqlPointer); $mysqlPointer = [IntPtr]::Zero
    $mysqlPassword.Dispose(); $mysqlPassword = $null
    Write-Host '候选实例已通过两套助手、健康和认证门禁；18081 未改动。验证完成后按 Ctrl+C 结束候选。' -ForegroundColor Yellow
    $candidate.Process.WaitForExit()
    exit $candidate.Process.ExitCode
  }

  if ($PauseBeforePromotion) {
    $candidateApproval = Read-Host '候选已通过健康与认证门禁。完成浏览器验收后输入 CANDIDATE_BROWSER_VERIFIED 才会进入 18081 替换确认'
    if ($candidateApproval -cne 'CANDIDATE_BROWSER_VERIFIED') {
      throw '未获得候选浏览器验收确认；18081 未被停止。'
    }
  }

  # Re-read and validate the current-user DPAPI source before touching 18081.
  # This guards against a deleted/corrupt/changed configuration after the candidate was started.
  $productionConfiguration = Read-AssistantRuntimeConfig -RuntimeDirectory $RuntimeDirectory
  Test-AllAssistantUpstreams -Configuration $productionConfiguration -Timeout ([Math]::Min($TimeoutSeconds, 30))
  $productionEnvironment = New-ChildEnvironment 18081 $mysqlPlain $productionConfiguration
  $oldServiceStopped = Stop-VerifiedWorkspaceJavaOn18081 $resolvedBackendDirectory
  Write-Host '候选所有门禁已通过，正在从安全配置源启动新的 18081 实例；候选 18082 暂时保留。' -ForegroundColor Cyan
  $production = Start-BackendChild $resolvedJar $resolvedBackendDirectory 18081 $productionEnvironment
  Remove-LaunchSecrets $production $productionEnvironment
  Clear-AssistantRuntimeConfigPlaintext $productionConfiguration
  $productionConfiguration = $null
  $mysqlPlain = $null
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($mysqlPointer); $mysqlPointer = [IntPtr]::Zero
  $mysqlPassword.Dispose(); $mysqlPassword = $null

  $productionHealth = Wait-ForBackendHealth 18081 $production.Process
  Assert-ExpectedBackendHealth $productionHealth $AppEnvironment $MySqlPort $MySqlDatabase
  Write-SafeHealth $productionHealth 18081
  Assert-UnauthenticatedAssistantGate 18081 '/api/assistant/status' '门店经营助手'
  Assert-UnauthenticatedAssistantGate 18081 '/api/employee-assistant/status' '员工服务助手'
  if ($candidate -and -not $candidate.Process.HasExited) {
    Stop-Process -Id $candidate.Process.Id -ErrorAction Stop
    $candidate.Process.WaitForExit()
  } else {
    Write-Warning '候选实例已提前退出；正式 18081 已独立通过健康与认证门禁，继续保留。'
  }
  Write-Host '受控替换完成：18081 已从当前 Windows 用户 DPAPI 配置分别注入两套助手变量。' -ForegroundColor Green
  Write-Host '请在已登录页面检查两套助手状态；页面只显示中文业务状态，不会显示上游细节。' -ForegroundColor DarkGray
  exit 0
} catch {
  if ($production -and -not $production.Process.HasExited) { Stop-Process -Id $production.Process.Id -ErrorAction SilentlyContinue }
  if ($oldServiceStopped -and $candidate -and -not $candidate.Process.HasExited) {
    Write-Warning "18081 替换未完成；已验证候选仍保留在 http://127.0.0.1:$CandidatePort，请按恢复流程处理。"
  } elseif ($candidate -and -not $candidate.Process.HasExited) {
    Stop-Process -Id $candidate.Process.Id -ErrorAction SilentlyContinue
  }
  throw
} finally {
  if ($mysqlPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($mysqlPointer) }
  $mysqlPlain = $null
  if ($mysqlPassword) { $mysqlPassword.Dispose() }
  if ($candidateConfiguration) { Clear-AssistantRuntimeConfigPlaintext $candidateConfiguration }
  if ($productionConfiguration) { Clear-AssistantRuntimeConfigPlaintext $productionConfiguration }
  if ($candidateEnvironment) { $candidateEnvironment.Clear() }
  if ($productionEnvironment) { $productionEnvironment.Clear() }
  if ($candidate) { $candidate.Process.Dispose() }
  if ($production) { $production.Process.Dispose() }
}
