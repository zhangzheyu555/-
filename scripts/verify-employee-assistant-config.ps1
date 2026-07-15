[CmdletBinding()]
param([string]$RuntimeDirectory)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'assistant-runtime-config.ps1')

$configuration = $null
try {
  $configuration = Read-AssistantRuntimeConfig -RuntimeDirectory $RuntimeDirectory
  $summary = Assert-CompleteAssistantRuntimeConfig $configuration
  Write-Output '双助手部署前检查（仅检查当前 Windows 用户 DPAPI 加密配置；不显示路径、地址、模型或任何凭据）'
  Write-Output '[通过] 门店经营助手 DEEPSEEK_* 配置完整，且将由安全配置源注入。'
  Write-Output '[通过] 员工服务助手 EMPLOYEE_ASSISTANT_* 配置完整且模式唯一。'
  Write-Output ('[通过] 员工服务助手模式为 {0}；两套助手变量严格隔离。' -f $summary.EmployeeMode)
  Write-Output '[通过] 两套上游基础地址格式合法，令牌/API Key 已在内存中确认非空。'
  Write-Output ''
  Write-Output '检查通过：本脚本未读取或修改当前 Java 进程环境，未启动、停止或重启服务。'
  exit 0
} catch {
  Write-Output '未通过：当前 Windows 用户的双助手 DPAPI 加密配置缺失、不可读取或不完整。'
  Write-Output '请先运行 configure-assistant-runtime-config.ps1，通过本机隐藏输入重新配置两套助手；不要把凭据写入环境变量、命令行或仓库。'
  Write-Output '本脚本未读取或修改当前 Java 进程环境，未启动、停止或重启服务。'
  exit 1
} finally {
  if ($configuration) { Clear-AssistantRuntimeConfigPlaintext $configuration }
}
