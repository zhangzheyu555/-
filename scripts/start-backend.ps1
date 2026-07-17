[CmdletBinding()]
param(
  [ValidateSet("LOCAL", "TEST", "STAGING")][string]$AppEnvironment = "STAGING",
  [ValidateRange(5, 300)][int]$TimeoutSeconds = 90,
  [string]$RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..")).TrimEnd("\")
$secureLauncher = Join-Path $PSScriptRoot "start-backend-assistants-secure.ps1"

if (-not (Test-Path $secureLauncher)) {
  throw "Secure launcher not found: start-backend-assistants-secure.ps1"
}

$runtimeDir = if ($RuntimeDirectory) { $RuntimeDirectory } else {
  Join-Path ([Environment]::GetFolderPath("LocalApplicationData")) "AI-Profit-OS\runtime-config"
}
$dpapiConfig = Join-Path $runtimeDir "assistant-providers.v1.dpapi"
if (-not (Test-Path $dpapiConfig)) {
  throw "DPAPI config not found. Run configure-and-start-assistants-simple.ps1 first."
}

$jarPath = Join-Path $projectRoot "backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path $jarPath)) {
  throw "JAR not found. Run: cd backend; mvn -q -DskipTests package"
}

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  AI Profit OS Backend + Dual Assistants" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "Environment: $AppEnvironment" -ForegroundColor DarkGray
Write-Host ""

& $secureLauncher `
  -AppEnvironment $AppEnvironment `
  -TimeoutSeconds $TimeoutSeconds `
  -RuntimeDirectory $runtimeDir `
  -PromoteTo18081 `
  -ApprovedUnattendedPromotion

if ($LASTEXITCODE -ne 0) {
  throw "Backend failed with exit code: $LASTEXITCODE"
}

Write-Host ""
Write-Host "Backend + Assistants started." -ForegroundColor Green
Write-Host "  API:       http://127.0.0.1:18081/api/health" -ForegroundColor Green
Write-Host "  Assistant: http://127.0.0.1:18081/api/assistant/status" -ForegroundColor Green
Write-Host "  Employee:  http://127.0.0.1:18081/api/employee-assistant/status" -ForegroundColor Green
