[CmdletBinding()]
param(
  [string]$MySqlHost = '127.0.0.1',
  [ValidateRange(1, 65535)][int]$MySqlPort = 3307,
  [string]$MySqlDatabase = 'store_profit_mysql8',
  [string]$MySqlClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$ModelBaseUrl = 'https://api.deepseek.com',
  [string]$ModelName = 'deepseek-v4-pro',
  [ValidateSet('LOCAL', 'STAGING')][string]$AppEnvironment = 'STAGING',
  [ValidateRange(5, 300)][int]$TimeoutSeconds = 45,
  [string]$RuntimeDirectory,
  [switch]$ReuseSavedAssistantConfiguration
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
. (Join-Path $PSScriptRoot 'assistant-runtime-config.ps1')

$script:RuntimeUserName = 'ai_profit_assist_runtime'

function Convert-SecureValueToPlainText {
  param([Parameter(Mandatory)][Security.SecureString]$Value)

  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
  }
}

function Set-CurrentUserOnlyFileAcl {
  param([Parameter(Mandatory)][string]$Path)

  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
  if ($null -eq $identity) { throw '无法确认当前 Windows 用户，拒绝写入临时凭据文件。' }
  $acl = [Security.AccessControl.FileSecurity]::new()
  $acl.SetOwner($identity)
  $acl.SetAccessRuleProtection($true, $false)
  $rule = [Security.AccessControl.FileSystemAccessRule]::new(
    $identity,
    [Security.AccessControl.FileSystemRights]::FullControl,
    [Security.AccessControl.AccessControlType]::Allow
  )
  [void]$acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}

function New-MySqlOptionFile {
  param(
    [Parameter(Mandatory)][string]$UserName,
    [Parameter(Mandatory)][string]$Password
  )

  if ($UserName -notmatch '^[A-Za-z0-9_.@-]+$' -or $Password -match "[`r`n]") {
    throw '本机 MySQL 凭据格式不受支持。'
  }
  $path = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-os-' + [Guid]::NewGuid().ToString('N') + '.cnf')
  $escapedPassword = $Password.Replace('\', '\\').Replace('"', '\"')
  $content = "[client]`r`nprotocol=TCP`r`nhost=$MySqlHost`r`nport=$MySqlPort`r`nuser=$UserName`r`npassword=`"$escapedPassword`"`r`ndefault-character-set=utf8mb4`r`n"
  [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
  Set-CurrentUserOnlyFileAcl $path
  return $path
}

function Invoke-MySqlCommandSafely {
  param(
    [Parameter(Mandatory)][string]$OptionFile,
    [Parameter(Mandatory)][string]$Sql,
    [Parameter(Mandatory)][string]$Action
  )

  $start = [Diagnostics.ProcessStartInfo]::new()
  $start.FileName = $MySqlClientPath
  # defaults-extra-file must be the first mysql option so no password reaches the command line.
  $start.Arguments = "--defaults-extra-file=`"$OptionFile`" --batch --raw --skip-column-names --default-character-set=utf8mb4"
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardInput = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $start
  try {
    [void]$process.Start()
    $standardOut = $process.StandardOutput.ReadToEndAsync()
    $standardError = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($Sql)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $output = $standardOut.GetAwaiter().GetResult()
    [void]$standardError.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
      throw "$Action 未通过。请确认本机 MySQL root 密码、3307 服务和目标库；不会输出或记录凭据。"
    }
    return $output
  } finally {
    $process.Dispose()
  }
}

function New-RandomRuntimePassword {
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  $bytes = [byte[]]::new(48)
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Assert-SafeDatabaseIdentifier {
  param([Parameter(Mandatory)][string]$Value)
  if ($Value -notmatch '^[A-Za-z0-9_]+$') { throw '目标数据库名包含不受支持的字符。' }
}

function Initialize-RestrictedRuntimeAccount {
  param(
    [Parameter(Mandatory)][string]$RootOptionFile,
    [Parameter(Mandatory)][string]$RuntimePassword
  )

  Assert-SafeDatabaseIdentifier $MySqlDatabase
  $databaseIdentifier = '`' + $MySqlDatabase.Replace('`', '``') + '`'
  $sql = @"
CREATE USER IF NOT EXISTS '$script:RuntimeUserName'@'127.0.0.1' IDENTIFIED BY '$RuntimePassword';
ALTER USER '$script:RuntimeUserName'@'127.0.0.1' IDENTIFIED BY '$RuntimePassword';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$script:RuntimeUserName'@'127.0.0.1';
GRANT ALL PRIVILEGES ON $databaseIdentifier.* TO '$script:RuntimeUserName'@'127.0.0.1';
FLUSH PRIVILEGES;
SHOW GRANTS FOR '$script:RuntimeUserName'@'127.0.0.1';
"@
  $grants = Invoke-MySqlCommandSafely -OptionFile $RootOptionFile -Sql $sql -Action '受限应用账号创建或轮换'
  $lines = @($grants -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ($lines.Count -eq 0 -or ($lines -join "`n") -match '(?i)WITH\s+GRANT\s+OPTION') {
    throw '受限应用账号权限验证失败。'
  }
  $expectedGrantPattern = '(?i)^GRANT\s+ALL\s+PRIVILEGES\s+ON\s+`' +
    [regex]::Escape($MySqlDatabase) + '`\.\*\s+TO'
  foreach ($line in $lines) {
    if ($line -match '(?i)^GRANT\s+USAGE\s+ON\s+\*\.\*') { continue }
    if ($line -notmatch $expectedGrantPattern) {
      throw '受限应用账号出现了非目标库权限，已停止部署。'
    }
  }
}

function Test-RestrictedRuntimeAccount {
  param([Parameter(Mandatory)][string]$RuntimePassword)

  $runtimeOptionFile = $null
  try {
    $runtimeOptionFile = New-MySqlOptionFile -UserName $script:RuntimeUserName -Password $RuntimePassword
    $databaseIdentifier = '`' + $MySqlDatabase.Replace('`', '``') + '`'
    [void](Invoke-MySqlCommandSafely -OptionFile $runtimeOptionFile -Sql "USE $databaseIdentifier; SELECT 1;`n" -Action '受限应用账号连接验证')
  } finally {
    if ($runtimeOptionFile -and (Test-Path -LiteralPath $runtimeOptionFile)) {
      Remove-Item -LiteralPath $runtimeOptionFile -Force -ErrorAction SilentlyContinue
    }
  }
}

function Test-ModelChatPreflight {
  param(
    [Parameter(Mandatory)][string]$BaseUrl,
    [Parameter(Mandatory)][string]$ApiKey,
    [Parameter(Mandatory)][string]$Model
  )

  $request = $null
  $response = $null
  $client = $null
  try {
    $uri = $BaseUrl.TrimEnd('/') + '/chat/completions'
    # Reasoning-capable models can consume a few completion tokens before emitting their final
    # message. A four-token probe therefore produced a successful HTTP response with blank
    # content, even though the model was reachable. Keep this request static and data-free,
    # but give it enough room to return the final acknowledgement.
    $payload = @{ model = $Model; max_tokens = 128; temperature = 0; messages = @(@{ role = 'user'; content = '只回复 OK，不要解释。' }) } |
      ConvertTo-Json -Compress -Depth 5
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds([Math]::Min($TimeoutSeconds, 30))
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $ApiKey)
    $request.Content = [System.Net.Http.StringContent]::new($payload, [Text.Encoding]::UTF8, 'application/json')
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
      throw 'DeepSeek 模型预检未通过。请确认新 API Key、模型名称和网络；不会切换 18081。'
    }
    $parsed = $body | ConvertFrom-Json -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace([string]$parsed.choices[0].message.content)) {
      throw 'DeepSeek 模型预检未返回有效文本；不会切换 18081。'
    }
  } finally {
    if ($response) { $response.Dispose() }
    if ($request) { $request.Dispose() }
    if ($client) { $client.Dispose() }
  }
}

function New-StableCandidateJarSnapshot {
  param([Parameter(Mandatory)][string]$SourceJar)

  $resolvedSource = [IO.Path]::GetFullPath($SourceJar)
  if (-not (Test-Path -LiteralPath $resolvedSource -PathType Leaf)) {
    throw '未找到候选后端 JAR。'
  }
  try {
    $firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSource).Hash
    Start-Sleep -Milliseconds 300
    $secondHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedSource).Hash
  } catch {
    throw '后端 JAR 正在被构建或其他进程占用，请等待构建完成后重新运行；18081 未改动。'
  }
  if ($firstHash -cne $secondHash) {
    throw '后端 JAR 在候选快照前发生变化，请等待构建完成后重新运行；18081 未改动。'
  }
  $outputDirectory = Join-Path $PSScriptRoot '..\output'
  [void](New-Item -ItemType Directory -Path $outputDirectory -Force)
  $snapshot = Join-Path $outputDirectory (
    'store-profit-backend-assistants-candidate-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff') +
    '-' + $firstHash.Substring(0, 12) + '.jar'
  )
  try {
    Copy-Item -LiteralPath $resolvedSource -Destination $snapshot -ErrorAction Stop
    $snapshotHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $snapshot).Hash
    if ($snapshotHash -cne $firstHash) {
      throw '候选 JAR 快照校验不一致。'
    }
    return $snapshot
  } catch {
    if (Test-Path -LiteralPath $snapshot) {
      Remove-Item -LiteralPath $snapshot -Force -ErrorAction SilentlyContinue
    }
    throw
  }
}

function Save-SingleKeyAssistantConfiguration {
  param([Parameter(Mandatory)][string]$ApiKey)

  if (-not (Test-AssistantSafeHttpBaseUrl $ModelBaseUrl)) {
    throw '模型地址必须是无账号信息、查询参数和片段的 http/https 基础地址。'
  }
  if ([string]::IsNullOrWhiteSpace($ModelName)) { throw '模型名称不能为空。' }
  $configuration = [pscustomobject]@{
    schemaVersion = 1
    businessAssistant = [pscustomobject]@{
      provider = 'DEEPSEEK'
      baseUrl = $ModelBaseUrl.TrimEnd('/')
      model = $ModelName.Trim()
      apiKey = $ApiKey
    }
    employeeAssistant = [pscustomobject]@{
      provider = 'MODEL'
      remoteUrl = ''
      apiToken = ''
      modelUrl = $ModelBaseUrl.TrimEnd('/')
      modelApiKey = $ApiKey
      modelName = $ModelName.Trim()
    }
  }
  try {
    Save-AssistantRuntimeConfig -Configuration $configuration -RuntimeDirectory $RuntimeDirectory
  } finally {
    Clear-AssistantRuntimeConfigPlaintext $configuration
  }
}

$rootPasswordInput = $null
$modelApiKeyInput = $null
$rootPassword = $null
$modelApiKey = $null
$rootOptionFile = $null
$runtimePassword = $null
$runtimePasswordInput = $null
$savedAssistantConfiguration = $null
$candidateJar = $null
$reuseAssistantConfiguration = $false
try {
  if (-not (Test-Path -LiteralPath $MySqlClientPath -PathType Leaf)) {
    throw '未找到 MySQL 8 客户端 mysql.exe，未执行任何配置。'
  }
  Assert-SafeDatabaseIdentifier $MySqlDatabase
  if ($ReuseSavedAssistantConfiguration) {
    $savedAssistantConfiguration = Read-AssistantRuntimeConfig -RuntimeDirectory $RuntimeDirectory
    $savedBusiness = Get-AssistantConfigProperty $savedAssistantConfiguration 'businessAssistant'
    $ModelBaseUrl = [string](Get-AssistantConfigProperty $savedBusiness 'baseUrl')
    $ModelName = [string](Get-AssistantConfigProperty $savedBusiness 'model')
    $modelApiKey = [string](Get-AssistantConfigProperty $savedBusiness 'apiKey')
    if (-not (Test-AssistantSafeHttpBaseUrl $ModelBaseUrl) -or
        [string]::IsNullOrWhiteSpace($ModelName) -or
        [string]::IsNullOrWhiteSpace($modelApiKey)) {
      throw '当前 Windows 用户的已保存助手配置不完整，不能复用。'
    }
    $reuseAssistantConfiguration = $true
    Write-Host '已读取当前 Windows 用户的加密双助手配置；本次仅需输入 MySQL root 密码。' -ForegroundColor Cyan
  } else {
    if (-not (Test-AssistantSafeHttpBaseUrl $ModelBaseUrl)) {
      throw 'DeepSeek 地址格式无效。'
    }
    Write-Host '一次配置将只隐藏输入两项：MySQL 3307 root 密码和一个 DeepSeek API Key。' -ForegroundColor Cyan
    Write-Host '该 Key 会作为两套独立运行变量注入；不会写入源码、命令行、日志或前端。' -ForegroundColor DarkGray
    $modelApiKeyInput = Read-Host '请输入 DeepSeek API Key（输入不会显示）' -AsSecureString
    $modelApiKey = Convert-SecureValueToPlainText $modelApiKeyInput
    if ([string]::IsNullOrWhiteSpace($modelApiKey)) {
      throw 'DeepSeek API Key 不能为空。'
    }
  }
  Write-Host '将创建或轮换仅限 127.0.0.1 和当前数据库的受限运行账号；后端绝不使用 root。' -ForegroundColor DarkGray
  $rootPasswordInput = Read-Host '请输入 MySQL 3307 root 密码（输入不会显示）' -AsSecureString
  $rootPassword = Convert-SecureValueToPlainText $rootPasswordInput
  if ([string]::IsNullOrWhiteSpace($rootPassword)) {
    throw 'root 密码不能为空。'
  }

  Write-Host '正在进行不含业务数据的 DeepSeek 实际模型预检…' -ForegroundColor Cyan
  Test-ModelChatPreflight -BaseUrl $ModelBaseUrl -ApiKey $modelApiKey -Model $ModelName
  Write-Host 'DeepSeek 模型预检通过。' -ForegroundColor Green

  $rootOptionFile = New-MySqlOptionFile -UserName 'root' -Password $rootPassword
  $runtimePassword = New-RandomRuntimePassword
  Initialize-RestrictedRuntimeAccount -RootOptionFile $rootOptionFile -RuntimePassword $runtimePassword
  Test-RestrictedRuntimeAccount -RuntimePassword $runtimePassword
  if (-not $reuseAssistantConfiguration) {
    Write-Host '本机受限运行账号已验证；正在保存当前 Windows 用户的加密助手配置。' -ForegroundColor Green
    Save-SingleKeyAssistantConfiguration -ApiKey $modelApiKey
  } else {
    Write-Host '本机受限运行账号已验证；将复用当前 Windows 用户的加密助手配置。' -ForegroundColor Green
  }
  $candidateJar = New-StableCandidateJarSnapshot -SourceJar (Join-Path $PSScriptRoot '..\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar')

  $runtimePasswordInput = ConvertTo-SecureString -String $runtimePassword -AsPlainText -Force
  $launcher = Join-Path $PSScriptRoot 'start-backend-assistants-secure.ps1'
  & $launcher `
    -MySqlHost $MySqlHost -MySqlPort $MySqlPort -MySqlDatabase $MySqlDatabase `
    -MySqlUsername $script:RuntimeUserName -MySqlPasswordInput $runtimePasswordInput `
    -AppEnvironment $AppEnvironment -CandidatePort 18082 `
    -JarPath $candidateJar `
    -BackendWorkingDirectory (Join-Path $PSScriptRoot '..\backend') `
    -RuntimeDirectory $RuntimeDirectory -TimeoutSeconds $TimeoutSeconds -PromoteTo18081
  if ($LASTEXITCODE -ne 0) { throw '双助手候选或受控替换未完成；当前 18081 未被不安全替换。' }
  Write-Host '部署完成。请在已登录页面分别点击“检查服务”并发送一条通用问题验证。' -ForegroundColor Green
} finally {
  $rootPassword = $null
  $modelApiKey = $null
  $runtimePassword = $null
  if ($savedAssistantConfiguration) { Clear-AssistantRuntimeConfigPlaintext $savedAssistantConfiguration }
  if ($rootPasswordInput) { $rootPasswordInput.Dispose() }
  if ($modelApiKeyInput) { $modelApiKeyInput.Dispose() }
  if ($runtimePasswordInput) { $runtimePasswordInput.Dispose() }
  if ($rootOptionFile -and (Test-Path -LiteralPath $rootOptionFile)) {
    Remove-Item -LiteralPath $rootOptionFile -Force -ErrorAction SilentlyContinue
  }
}
