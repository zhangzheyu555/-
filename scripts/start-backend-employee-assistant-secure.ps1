[CmdletBinding()]
param(
  [string]$MySqlHost,
  [int]$MySqlPort,
  [string]$MySqlDatabase,
  [string]$MySqlUsername,
  [Security.SecureString]$MySqlPasswordInput,
  [string]$AppEnvironment,
  [int]$CandidatePort,
  [string]$JarPath,
  [string]$BackendWorkingDirectory,
  [string]$RuntimeDirectory,
  [int]$TimeoutSeconds,
  [switch]$PromoteTo18081,
  [string]$Mode,
  [string]$RemoteUrl,
  [string]$ModelUrl,
  [string]$ModelName,
  [switch]$AutoPromoteConfirmed
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Compatibility name only. Provider, URL and model values must now come from the DPAPI source.
$obsolete = @('Mode', 'RemoteUrl', 'ModelUrl', 'ModelName', 'AutoPromoteConfirmed') |
  Where-Object { $PSBoundParameters.ContainsKey($_) }
if ($obsolete.Count -gt 0) {
  throw '此旧入口不再接受员工助手模式、地址、模型或自动替换参数。请先运行 configure-assistant-runtime-config.ps1，再使用 start-backend-assistants-secure.ps1。'
}

$arguments = @{}
foreach ($name in @(
    'MySqlHost', 'MySqlPort', 'MySqlDatabase', 'MySqlUsername', 'MySqlPasswordInput',
    'AppEnvironment', 'CandidatePort', 'JarPath', 'BackendWorkingDirectory',
    'RuntimeDirectory', 'TimeoutSeconds'
)) {
  if ($PSBoundParameters.ContainsKey($name)) { $arguments[$name] = $PSBoundParameters[$name] }
}
if ($PromoteTo18081) { $arguments.PromoteTo18081 = $true }
& (Join-Path $PSScriptRoot 'start-backend-assistants-secure.ps1') @arguments
exit $LASTEXITCODE
