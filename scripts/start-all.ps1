[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$YoloPort = 8000,
  [ValidateRange(1, 65535)][int]$FrontendPort = 5173,
  [ValidateSet('LOCAL', 'TEST', 'STAGING')][string]$AppEnvironment = 'STAGING',
  [ValidateRange(5, 300)][int]$BackendTimeoutSeconds = 90,
  [string]$RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')

Write-Host ''
Write-Host '================================================================' -ForegroundColor Cyan
Write-Host '    AI Profit OS 一键启动' -ForegroundColor Cyan
Write-Host '    YOLO 卫生识别 + 后端双助手 + 前端 Vue3' -ForegroundColor Cyan
Write-Host '================================================================' -ForegroundColor Cyan
Write-Host ''

$backendUrl = 'http://127.0.0.1:18081'
$yoloProcess = $null
$frontendProcess = $null
$yoloOk = $false
$backendOk = $false
$frontendOk = $false

try {
  # ================================================================
  # 阶段 1/3：启动 YOLO 卫生识别服务（后台）
  # ================================================================
  Write-Host '--- [1/3] YOLO 卫生识别服务 ---' -ForegroundColor Yellow

  $yoloScript = Join-Path $PSScriptRoot 'start-yolo.ps1'
  if (Test-Path $yoloScript) {
    $yoloResult = & $yoloScript -Port $YoloPort -TimeoutSeconds 60
    $yoloProcess = $yoloResult.Process
    $yoloOk = (-not $yoloProcess.HasExited)
    if ($yoloOk) {
      Write-Host "  YOLO: http://127.0.0.1:$YoloPort ✓" -ForegroundColor Green
    }
  } else {
    Write-Warning "  未找到 start-yolo.ps1，跳过 YOLO。"
  }

  Write-Host ''

  # ================================================================
  # 阶段 2/3：启动后端 + 双助手（前台等待，最耗时）
  # ================================================================
  Write-Host '--- [2/3] 后端 + 双助手（门店经营助手 + 员工服务助手）---' -ForegroundColor Yellow

  $backendScript = Join-Path $PSScriptRoot 'start-backend.ps1'
  if (-not (Test-Path $backendScript)) {
    throw '未找到 start-backend.ps1。'
  }

  $backendArgs = @{
    AppEnvironment = $AppEnvironment
    TimeoutSeconds = $BackendTimeoutSeconds
  }
  if ($RuntimeDirectory) { $backendArgs.RuntimeDirectory = $RuntimeDirectory }

  & $backendScript @backendArgs
  if ($LASTEXITCODE -ne 0) {
    throw "后端启动失败，退出代码: $LASTEXITCODE"
  }

  # 验证后端健康
  try {
    $health = Invoke-RestMethod -Uri "$backendUrl/api/health" -TimeoutSec 5
    if ($health.success -and $health.data.status -eq 'UP') {
      $backendOk = $true
      Write-Host "  后端: $backendUrl/api/health ✓ (Flyway=$($health.data.databaseMigrationVersion))" -ForegroundColor Green
    }
  } catch {
    Write-Warning "  后端健康检查失败。"
  }

  Write-Host ''

  # ================================================================
  # 阶段 3/3：启动前端 Vue3（后台）
  # ================================================================
  Write-Host '--- [3/3] 前端 Vue3 开发服务器 ---' -ForegroundColor Yellow

  $frontendScript = Join-Path $PSScriptRoot 'start-frontend.ps1'
  if (Test-Path $frontendScript) {
    $frontendResult = & $frontendScript -Port $FrontendPort -TimeoutSeconds 30 -BackendTarget $backendUrl
    $frontendProcess = $frontendResult.Process
    $frontendOk = (-not $frontendProcess.HasExited)
    if ($frontendOk) {
      Write-Host "  前端: http://127.0.0.1:$FrontendPort ✓" -ForegroundColor Green
    }
  } else {
    Write-Warning "  未找到 start-frontend.ps1，跳过前端。"
  }

  Write-Host ''

  # ================================================================
  # 汇总
  # ================================================================
  Write-Host '================================================================' -ForegroundColor Cyan
  Write-Host '  所有服务启动完成' -ForegroundColor Green
  Write-Host '================================================================' -ForegroundColor Cyan
  Write-Host ''
  if ($yoloOk) {
    Write-Host "  YOLO 卫生识别 : http://127.0.0.1:$YoloPort ✓" -ForegroundColor Green
  } else {
    Write-Host '  YOLO 卫生识别 : 未启动 ✗' -ForegroundColor Red
  }
  if ($backendOk) {
    Write-Host "  后端 API      : $backendUrl/api/health ✓" -ForegroundColor Green
    Write-Host "  门店经营助手   : $backendUrl/api/assistant/status ✓" -ForegroundColor Green
    Write-Host "  员工服务助手   : $backendUrl/api/employee-assistant/status ✓" -ForegroundColor Green
  } else {
    Write-Host '  后端 API      : 未启动 ✗' -ForegroundColor Red
    Write-Host '  门店经营助手   : 未启动 ✗' -ForegroundColor Red
    Write-Host '  员工服务助手   : 未启动 ✗' -ForegroundColor Red
  }
  if ($frontendOk) {
    Write-Host "  前端 Vue3      : http://127.0.0.1:$FrontendPort ✓" -ForegroundColor Green
  } else {
    Write-Host '  前端 Vue3      : 未启动 ✗' -ForegroundColor Red
  }
  Write-Host ''
  Write-Host "  打开浏览器: http://127.0.0.1:$FrontendPort" -ForegroundColor Cyan
  Write-Host '  登录账号: 使用已完成初始化的正式账号' -ForegroundColor DarkGray
  Write-Host ''
  Write-Host '  按 Ctrl+C 不会停止后台服务。需手动停止请运行：' -ForegroundColor DarkGray
  Write-Host '    Get-Process python,javaw,node | Where-Object { ... } | Stop-Process' -ForegroundColor DarkGray

  exit 0

} catch {
  Write-Host ''
  Write-Host "启动失败: $_" -ForegroundColor Red
  exit 1
} finally {
  if ($yoloProcess) { $yoloProcess.Dispose() }
  if ($frontendProcess) { $frontendProcess.Dispose() }
}
