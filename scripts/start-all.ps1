[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$YoloPort = 8000,
  [ValidateRange(1, 65535)][int]$FrontendPort = 5173,
  [ValidateSet("LOCAL", "TEST", "STAGING")][string]$AppEnvironment = "STAGING",
  [ValidateRange(5, 300)][int]$BackendTimeoutSeconds = 90,
  [string]$RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "    AI Profit OS" -ForegroundColor Cyan
Write-Host "    YOLO + Backend + Assistants + Frontend" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$backendUrl = "http://127.0.0.1:18081"
$yoloProcess = $null
$frontendProcess = $null
$yoloOk = $false
$backendOk = $false
$frontendOk = $false

try {
  Write-Host "--- [1/3] YOLO ---" -ForegroundColor Yellow
  $yoloScript = Join-Path $PSScriptRoot "start-yolo.ps1"
  if (Test-Path $yoloScript) {
    $yoloResult = & $yoloScript -Port $YoloPort -TimeoutSeconds 60
    $yoloProcess = $yoloResult.Process
    $yoloOk = (-not $yoloProcess.HasExited)
    if ($yoloOk) {
      Write-Host "  YOLO: http://127.0.0.1:$YoloPort [OK]" -ForegroundColor Green
    }
  } else {
    Write-Warning "  start-yolo.ps1 not found, skipping YOLO."
  }

  Write-Host ""
  Write-Host "--- [2/3] Backend + Assistants ---" -ForegroundColor Yellow

  $backendScript = Join-Path $PSScriptRoot "start-backend.ps1"
  if (-not (Test-Path $backendScript)) {
    throw "start-backend.ps1 not found."
  }

  $backendArgs = @{
    AppEnvironment = $AppEnvironment
    TimeoutSeconds = $BackendTimeoutSeconds
  }
  if ($RuntimeDirectory) {
    $backendArgs.RuntimeDirectory = $RuntimeDirectory
  }

  & $backendScript @backendArgs
  if ($LASTEXITCODE -ne 0) {
    throw "Backend failed with exit code: $LASTEXITCODE"
  }

  try {
    $health = Invoke-RestMethod -Uri "$backendUrl/api/health" -TimeoutSec 5
    if ($health.success -and $health.data.status -eq "UP") {
      $backendOk = $true
      Write-Host "  Backend: $backendUrl/api/health [OK]" -ForegroundColor Green
    }
  } catch {
    Write-Warning "  Backend health check failed."
  }

  Write-Host ""
  Write-Host "--- [3/3] Frontend Vue3 ---" -ForegroundColor Yellow

  $frontendScript = Join-Path $PSScriptRoot "start-frontend.ps1"
  if (Test-Path $frontendScript) {
    $frontendResult = & $frontendScript -Port $FrontendPort -TimeoutSeconds 30 -BackendTarget $backendUrl
    $frontendProcess = $frontendResult.Process
    $frontendOk = (-not $frontendProcess.HasExited)
    if ($frontendOk) {
      Write-Host "  Frontend: http://127.0.0.1:$FrontendPort [OK]" -ForegroundColor Green
    }
  } else {
    Write-Warning "  start-frontend.ps1 not found, skipping frontend."
  }

  Write-Host ""
  Write-Host "================================================================" -ForegroundColor Cyan
  Write-Host "  All services started" -ForegroundColor Green
  Write-Host "================================================================" -ForegroundColor Cyan
  Write-Host ""

  if ($yoloOk) {
    Write-Host "  YOLO     : http://127.0.0.1:$YoloPort [OK]" -ForegroundColor Green
  } else {
    Write-Host "  YOLO     : NOT STARTED" -ForegroundColor Red
  }
  if ($backendOk) {
    Write-Host "  Backend  : $backendUrl/api/health [OK]" -ForegroundColor Green
    Write-Host "  Assistant: $backendUrl/api/assistant/status" -ForegroundColor Green
    Write-Host "  Employee : $backendUrl/api/employee-assistant/status" -ForegroundColor Green
  } else {
    Write-Host "  Backend  : NOT STARTED" -ForegroundColor Red
  }
  if ($frontendOk) {
    Write-Host "  Frontend : http://127.0.0.1:$FrontendPort [OK]" -ForegroundColor Green
  } else {
    Write-Host "  Frontend : NOT STARTED" -ForegroundColor Red
  }

  Write-Host ""
  Write-Host "  Open: http://127.0.0.1:$FrontendPort" -ForegroundColor Cyan
  Write-Host ""

  exit 0
} catch {
  Write-Host ""
  Write-Host "START FAILED: $_" -ForegroundColor Red
  exit 1
} finally {
  if ($yoloProcess) { $yoloProcess.Dispose() }
  if ($frontendProcess) { $frontendProcess.Dispose() }
}
