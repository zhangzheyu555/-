[CmdletBinding()]
param(
  [ValidateSet('REMOTE', 'MODEL')]
  [string]$EmployeeAssistantMode,
  [string]$BusinessAssistantUrl,
  [string]$BusinessAssistantModel,
  [string]$EmployeeAssistantRemoteUrl,
  [string]$EmployeeAssistantModelUrl,
  [string]$EmployeeAssistantModelName,
  [string]$RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'assistant-runtime-config.ps1')

function Read-RequiredText {
  param([Parameter(Mandatory)][string]$Prompt, [AllowNull()][string]$Value)
  $result = if ([string]::IsNullOrWhiteSpace($Value)) { Read-Host $Prompt } else { $Value }
  if ([string]::IsNullOrWhiteSpace($result)) { throw "$Prompt 不能为空。" }
  return $result.Trim()
}

function Read-EmployeeAssistantMode {
  param([AllowNull()][string]$Value)
  $mode = if ([string]::IsNullOrWhiteSpace($Value)) { Read-Host '请选择员工服务助手模式（REMOTE 或 MODEL）' } else { $Value }
  $normalized = if ($null -eq $mode) { '' } else { $mode.Trim().ToUpperInvariant() }
  if ($normalized -notin @('REMOTE', 'MODEL')) {
    throw '员工服务助手模式只能是 REMOTE 或 MODEL。'
  }
  return $normalized
}

$businessApiKeyInput = $null
$employeeSecretInput = $null
$businessApiKey = $null
$employeeSecret = $null
$configuration = $null
try {
  $mode = Read-EmployeeAssistantMode $EmployeeAssistantMode
  $businessAssistantUrl = Read-RequiredText '请输入门店经营助手模型基础地址' $BusinessAssistantUrl
  $businessAssistantModel = Read-RequiredText '请输入门店经营助手模型名' $BusinessAssistantModel
  if (-not (Test-AssistantSafeHttpBaseUrl $businessAssistantUrl)) {
    throw '门店经营助手地址必须是无用户信息、查询参数和片段的 http/https 基础地址。'
  }

  if ($mode -eq 'REMOTE') {
    $employeeAssistantRemoteUrl = Read-RequiredText '请输入员工服务助手 REMOTE 上游基础地址' $EmployeeAssistantRemoteUrl
    if (-not (Test-AssistantSafeHttpBaseUrl $employeeAssistantRemoteUrl)) {
      throw '员工服务助手 REMOTE 地址必须是无用户信息、查询参数和片段的 http/https 基础地址。'
    }
  } else {
    $employeeAssistantModelUrl = Read-RequiredText '请输入员工服务助手 MODEL 模型基础地址' $EmployeeAssistantModelUrl
    $employeeAssistantModelName = Read-RequiredText '请输入员工服务助手 MODEL 模型名' $EmployeeAssistantModelName
    if (-not (Test-AssistantSafeHttpBaseUrl $employeeAssistantModelUrl)) {
      throw '员工服务助手 MODEL 地址必须是无用户信息、查询参数和片段的 http/https 基础地址。'
    }
  }

  $targetPath = Get-AssistantRuntimeConfigPath $RuntimeDirectory
  if (Test-Path -LiteralPath $targetPath -PathType Leaf) {
    $confirmation = Read-Host '已有当前用户的双助手加密配置。输入 REPLACE_ASSISTANT_RUNTIME_CONFIG 才会覆盖'
    if ($confirmation -cne 'REPLACE_ASSISTANT_RUNTIME_CONFIG') {
      throw '未获得明确覆盖确认；原有加密配置未改动。'
    }
  }

  # Credentials are intentionally available only through hidden local prompts.
  $businessApiKeyInput = Read-Host '请输入门店经营助手专属 API Key（输入不会显示）' -AsSecureString
  $employeeSecretPrompt = if ($mode -eq 'REMOTE') {
    '请输入员工服务助手 REMOTE 专属令牌（输入不会显示）'
  } else {
    '请输入员工服务助手 MODEL 专属 API Key（输入不会显示）'
  }
  $employeeSecretInput = Read-Host $employeeSecretPrompt -AsSecureString

  $businessApiKey = Convert-AssistantSecureStringToPlainText $businessApiKeyInput
  $employeeSecret = Convert-AssistantSecureStringToPlainText $employeeSecretInput
  if ([string]::IsNullOrWhiteSpace($businessApiKey) -or [string]::IsNullOrWhiteSpace($employeeSecret)) {
    throw '两套助手的专属凭据都必须非空。'
  }

  $employeeConfig = if ($mode -eq 'REMOTE') {
    [pscustomobject]@{
      provider = 'REMOTE'
      remoteUrl = $employeeAssistantRemoteUrl.TrimEnd('/')
      apiToken = $employeeSecret
      modelUrl = ''
      modelApiKey = ''
      modelName = ''
    }
  } else {
    [pscustomobject]@{
      provider = 'MODEL'
      remoteUrl = ''
      apiToken = ''
      modelUrl = $employeeAssistantModelUrl.TrimEnd('/')
      modelApiKey = $employeeSecret
      modelName = $employeeAssistantModelName
    }
  }
  $configuration = [pscustomobject]@{
    schemaVersion = 1
    businessAssistant = [pscustomobject]@{
      provider = 'DEEPSEEK'
      baseUrl = $businessAssistantUrl.TrimEnd('/')
      model = $businessAssistantModel
      apiKey = $businessApiKey
    }
    employeeAssistant = $employeeConfig
  }

  Save-AssistantRuntimeConfig -Configuration $configuration -RuntimeDirectory $RuntimeDirectory
  Write-Host '已保存当前 Windows 用户的双助手 DPAPI 加密运行配置；未显示路径、地址、模型或凭据。' -ForegroundColor Green
  Write-Host '数据库账号密码不持久化；每次候选启动仍需在本机隐藏输入。' -ForegroundColor DarkGray
} finally {
  $businessApiKey = $null
  $employeeSecret = $null
  if ($configuration) { Clear-AssistantRuntimeConfigPlaintext $configuration }
  if ($businessApiKeyInput) { $businessApiKeyInput.Dispose() }
  if ($employeeSecretInput) { $employeeSecretInput.Dispose() }
}
