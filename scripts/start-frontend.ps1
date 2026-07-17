[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$Port = 5173,
  [ValidateRange(5, 120)][int]$TimeoutSeconds = 30,
  [string]$BackendTarget = "http://127.0.0.1:18081"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..")).TrimEnd("\")
$frontendDir = Join-Path $projectRoot "frontend-vue"

if (-not (Test-Path (Join-Path $frontendDir "package.json"))) {
  throw "frontend-vue directory not found."
}

$nodeCmd = Get-Command node.exe -ErrorAction SilentlyContinue
if (-not $nodeCmd) { throw "Node.js not found. Install Node.js 18+." }

if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
  Write-Host "First run: npm install..." -ForegroundColor Yellow
  Push-Location $frontendDir
  try {
    $npmCmd = if (Get-Command npm.cmd -ErrorAction SilentlyContinue) { (Get-Command npm.cmd).Source } else { "npm" }
    & $npmCmd install
    if ($LASTEXITCODE -ne 0) { throw "npm install failed." }
  } finally { Pop-Location }
  Write-Host "Dependencies installed." -ForegroundColor Green
}

$existingProcesses = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($existingProcesses.Count -gt 0) {
  Write-Host "Port $Port in use, stopping old process..." -ForegroundColor Yellow
  foreach ($conn in $existingProcesses) {
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
  }
  Start-Sleep -Seconds 2
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port still occupied."
  }
}

Write-Host "Checking backend ($BackendTarget)..." -ForegroundColor DarkGray
$backendOk = $false
try {
  $health = Invoke-RestMethod -Uri "$BackendTarget/api/health" -TimeoutSec 5
  if ($health.success -and $health.data.status -eq "UP") {
    $backendOk = $true
    Write-Host "Backend health: UP" -ForegroundColor Green
  }
} catch {
  Write-Warning "Backend not ready, API requests may return 503."
}

Write-Host "Starting Vite dev server..." -ForegroundColor Cyan
$env:VITE_BACKEND_PROXY_TARGET = $BackendTarget

$vitePath = Join-Path $frontendDir "node_modules\.bin\vite.cmd"
$process = Start-Process -FilePath "cmd.exe" `
  -ArgumentList "/c", "`"$vitePath`" --host 127.0.0.1 --port $Port --strictPort" `
  -WorkingDirectory $frontendDir `
  -NoNewWindow -PassThru

Write-Host "Waiting for frontend (max ${TimeoutSeconds}s)..." -ForegroundColor DarkGray
$ready = $false
for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
  if ($process.HasExited) { throw "Vite exited with code: $($process.ExitCode)" }
  try {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -TimeoutSec 2 -UseBasicParsing
    if ($response.StatusCode -eq 200) { $ready = $true; break }
  } catch { Start-Sleep -Seconds 1 }
}

if ($ready) {
  Write-Host "Frontend ready: http://127.0.0.1:$Port" -ForegroundColor Green
} else {
  Write-Warning "Frontend not ready within ${TimeoutSeconds}s."
}

return [pscustomobject]@{
  Process = $process; Port = $Port; Url = "http://127.0.0.1:$Port"
  BackendTarget = $BackendTarget; BackendOk = $backendOk
}
