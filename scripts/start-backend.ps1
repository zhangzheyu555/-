[CmdletBinding()]
param(
  [ValidateSet('LOCAL', 'TEST', 'STAGING')][string]$AppEnvironment = 'STAGING',
  [ValidateRange(5, 300)][int]$TimeoutSeconds = 90,
  [string]$RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$secureLauncher = Join-Path $PSScriptRoot 'start-backend-assistants-secure.ps1'

if (-not (Test-Path $secureLauncher)) {
  throw '未找到安全启动器 start-backend-assistants-secure.ps1。'
}

# 检查 DPAPI 运行配置
$runtimeDir = if ($RuntimeDirectory) { $RuntimeDirectory } else {
  Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'AI-Profit-OS\runtime-config'
}
$dpapiConfig = Join-Path $runtimeDir 'assistant-providers.v1.dpapi'
if (-not (Test-Path $dpapiConfig)) {
  throw @'

未找到双助手 DPAPI 运行配置。
请先运行一次 configure-and-start-assistants-simple.ps1 完成首次配置。
此后每次启动直接运行本脚本即可。
'@
}

# 检查 JAR
$jarPath = Join-Path $projectRoot 'backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path $jarPath)) {
  throw '未找到后端 JAR，请先在 backend 目录执行 mvn -q -DskipTests package。'
}

Write-Host '============================================' -ForegroundColor Cyan
Write-Host '  AI Profit OS 后端 + 双助手启动' -ForegroundColor Cyan
Write-Host '============================================' -ForegroundColor Cyan
Write-Host "环境: $AppEnvironment" -ForegroundColor DarkGray
Write-Host "DPAPI 配置: $dpapiConfig" -ForegroundColor DarkGray
Write-Host "JAR: $jarPath" -ForegroundColor DarkGray
Write-Host ''

# 调用安全启动器（DPAPI 配置中已包含数据库密码，无需交互输入）
& $secureLauncher `
  -AppEnvironment $AppEnvironment `
  -TimeoutSeconds $TimeoutSeconds `
  -RuntimeDirectory $runtimeDir `
  -PromoteTo18081 `
  -ApprovedUnattendedPromotion

if ($LASTEXITCODE -ne 0) {
  throw "后端启动失败，退出代码: $LASTEXITCODE"
}

Write-Host ''
Write-Host '后端 + 双助手启动完成。' -ForegroundColor Green
Write-Host "  后端 API:  http://127.0.0.1:18081/api/health" -ForegroundColor Green
Write-Host "  门店经营助手: http://127.0.0.1:18081/api/assistant/status" -ForegroundColor Green
Write-Host "  员工服务助手: http://127.0.0.1:18081/api/employee-assistant/status" -ForegroundColor Green
