[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'assistant-runtime-config.ps1')

$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-os-assistant-dpapi-' + [Guid]::NewGuid().ToString('N'))
$businessKey = 'test-' + [Guid]::NewGuid().ToString('N')
$employeeKey = 'test-' + [Guid]::NewGuid().ToString('N')
$configuration = $null
$loaded = $null
try {
  $configuration = [pscustomobject]@{
    schemaVersion = 1
    businessAssistant = [pscustomobject]@{
      provider = 'DEEPSEEK'
      baseUrl = 'https://example.invalid/v1'
      model = 'test-model'
      apiKey = $businessKey
    }
    employeeAssistant = [pscustomobject]@{
      provider = 'MODEL'
      remoteUrl = ''
      apiToken = ''
      modelUrl = 'https://employee.example.invalid/v1'
      modelApiKey = $employeeKey
      modelName = 'test-model'
    }
  }
  Save-AssistantRuntimeConfig -Configuration $configuration -RuntimeDirectory $testDirectory
  $configPath = Get-AssistantRuntimeConfigPath $testDirectory
  $cipherText = [IO.File]::ReadAllText($configPath, [Text.UTF8Encoding]::new($false))
  if ($cipherText.Contains($businessKey) -or $cipherText.Contains($employeeKey)) {
    throw 'DPAPI 文件包含了虚拟凭据明文。'
  }

  $loaded = Read-AssistantRuntimeConfig -RuntimeDirectory $testDirectory
  $summary = Assert-CompleteAssistantRuntimeConfig $loaded
  if ($summary.EmployeeMode -ne 'MODEL') { throw 'DPAPI 读取后的模式不一致。' }
  $environment = New-AssistantProviderEnvironment $loaded
  if (-not $environment.ContainsKey('DEEPSEEK_API_KEY') -or
      -not $environment.ContainsKey('EMPLOYEE_ASSISTANT_MODEL_API_KEY') -or
      $environment.ContainsKey('EMPLOYEE_ASSISTANT_API_TOKEN')) {
    throw '双助手白名单注入检查失败。'
  }

  $invalidConfiguration = [pscustomobject]@{
    businessAssistant = [pscustomobject]@{ provider = 'DEEPSEEK'; baseUrl = 'https://example.invalid/v1'; model = 'test-model'; apiKey = '' }
    employeeAssistant = $configuration.employeeAssistant
  }
  $rejected = $false
  try { [void](Assert-CompleteAssistantRuntimeConfig $invalidConfiguration) } catch { $rejected = $true }
  if (-not $rejected) { throw '缺失门店经营助手凭据未被拒绝。' }

  $repositoryDirectoryRejected = $false
  try { [void](Get-AssistantRuntimeConfigDirectory (Join-Path $PSScriptRoot '..\runtime-config-forbidden')) } catch { $repositoryDirectoryRejected = $true }
  if (-not $repositoryDirectoryRejected) { throw '仓库内 DPAPI 配置目录未被拒绝。' }

  $reparseTarget = Join-Path $testDirectory 'reparse-target'
  $reparseLink = Join-Path $testDirectory 'reparse-link'
  [void](New-Item -ItemType Directory -Path $reparseTarget -Force)
  $junctionCreated = $false
  try {
    [void](New-Item -ItemType Junction -Path $reparseLink -Target $reparseTarget -ErrorAction Stop)
    $junctionCreated = $true
  } catch {
    # Some Windows policy profiles prohibit junction creation; retain a static guard in that case.
  }
  if ($junctionCreated) {
    $reparseRejected = $false
    try { [void](Get-AssistantRuntimeConfigDirectory (Join-Path $reparseLink 'child')) } catch { $reparseRejected = $true }
    if (-not $reparseRejected) { throw 'junction 运行配置目录未被拒绝。' }
    Remove-Item -LiteralPath $reparseLink -Force -ErrorAction SilentlyContinue
  } else {
    $helperSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'assistant-runtime-config.ps1'), [Text.UTF8Encoding]::new($false))
    if (-not ($helperSource.Contains('ReparsePoint') -and $helperSource.Contains('Assert-AssistantRuntimeDirectoryHasNoReparsePoints'))) {
      throw 'DPAPI 重解析点拒绝门禁缺失。'
    }
  }

  $verifyScript = Join-Path $PSScriptRoot 'verify-employee-assistant-config.ps1'
  $verifyOutput = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $verifyScript -RuntimeDirectory $testDirectory 2>&1
  if ($LASTEXITCODE -ne 0) { throw 'DPAPI 预检未通过。' }
  $verifyText = ($verifyOutput | Out-String)
  if ($verifyText.Contains($businessKey) -or $verifyText.Contains($employeeKey)) {
    throw 'DPAPI 预检输出包含了虚拟凭据。'
  }

  $legacyChecks = @(
    @{ Path = 'start-backend-employee-assistant-secure.ps1'; Arguments = @('-Mode', 'MODEL') },
    @{ Path = 'start-backend-employee-assistant-model-secure.ps1'; Arguments = @() },
    @{ Path = 'start-backend-deepseek-secure.ps1'; Arguments = @() },
    @{ Path = 'configure-employee-assistant-once.ps1'; Arguments = @() },
    @{ Path = 'start-backend-v43-secure.ps1'; Arguments = @() },
    @{ Path = 'bootstrap-runtime-account-and-start-v43.ps1'; Arguments = @() }
  )
  foreach ($legacy in $legacyChecks) {
    $legacyPath = Join-Path $PSScriptRoot $legacy.Path
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
      $legacyOutput = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $legacyPath @($legacy.Arguments) 2>&1
      $legacyExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($legacyExitCode -eq 0) { throw '旧单助手入口意外允许执行。' }
    $legacyText = ($legacyOutput | Out-String)
    if ($legacyText.Contains($businessKey) -or $legacyText.Contains($employeeKey)) {
      throw '旧入口输出包含了虚拟凭据。'
    }
  }

  $launcherSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'start-backend-assistants-secure.ps1'), [Text.UTF8Encoding]::new($false))
  foreach ($marker in @(
      '.Environment.Clear()', 'APP_ASSISTANT_DEEPSEEK_*', 'APP_EMPLOYEE_ASSISTANT_*',
      'SPRING_APPLICATION_JSON', 'SPRING_CONFIG_*', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS',
      '_JAVA_OPTIONS', '/api/assistant/status', '/api/employee-assistant/status'
  )) {
    if (-not $launcherSource.Contains($marker)) { throw '统一启动器缺少环境隔离或双助手门禁。' }
  }
  if ($launcherSource.Contains('AutoPromoteConfirmed')) { throw '统一启动器仍允许自动替换绕过。' }
  Write-Output 'PASS DPAPI round-trip, encrypted file, missing-field/repository/reparse rejection, isolated injection, safe preflight output, legacy-launcher rejection and environment-clear gate.'
} finally {
  $businessKey = $null
  $employeeKey = $null
  if ($configuration) { Clear-AssistantRuntimeConfigPlaintext $configuration }
  if ($loaded) { Clear-AssistantRuntimeConfigPlaintext $loaded }
  if (Test-Path -LiteralPath $testDirectory) {
    Remove-Item -LiteralPath $testDirectory -Recurse -Force -ErrorAction SilentlyContinue
  }
}
