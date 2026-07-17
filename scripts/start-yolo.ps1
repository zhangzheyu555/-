[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$Port = 8000,
  [ValidateRange(5, 120)][int]$TimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..")).TrimEnd("\")
$serviceDir = Join-Path $projectRoot "inspection-service"
$venvDir = Join-Path ([Environment]::GetFolderPath("LocalApplicationData")) "AI-Profit-OS\inspection-venv"

$pythonCmd = $null
foreach ($cmd in @("python", "python3", "py")) {
  $found = Get-Command $cmd -ErrorAction SilentlyContinue
  if ($found) { $pythonCmd = $found.Source; break }
}
if (-not $pythonCmd) { throw "Python not found. Install Python 3.9-3.12 with Add to PATH." }

Write-Host "Python: $pythonCmd" -ForegroundColor DarkGray

if (-not (Test-Path (Join-Path $venvDir "Scripts\python.exe"))) {
  Write-Host "First run: creating venv and installing dependencies..." -ForegroundColor Yellow
  [void](New-Item -ItemType Directory -Path (Split-Path $venvDir -Parent) -Force)
  & $pythonCmd -m venv $venvDir
  if ($LASTEXITCODE -ne 0) { throw "venv creation failed." }
  $venvPython = Join-Path $venvDir "Scripts\python.exe"
  & $venvPython -m pip install --upgrade pip
  & $venvPython -m pip install -r (Join-Path $serviceDir "requirements.txt")
  if ($LASTEXITCODE -ne 0) { throw "pip install failed." }
  Write-Host "Dependencies installed." -ForegroundColor Green
}

$venvPython = Join-Path $venvDir "Scripts\python.exe"

$existingProcess = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existingProcess) {
  Write-Host "Port $Port in use, stopping old process..." -ForegroundColor Yellow
  Stop-Process -Id $existingProcess.OwningProcess -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $Port still occupied."
  }
}

Write-Host "Starting YOLO inspection service..." -ForegroundColor Cyan
$process = Start-Process -FilePath $venvPython `
  -ArgumentList "-m", "uvicorn", "api_server:api", "--host", "127.0.0.1", "--port", $Port `
  -WorkingDirectory $serviceDir `
  -NoNewWindow -PassThru

Write-Host "Waiting for YOLO (max ${TimeoutSeconds}s)..." -ForegroundColor DarkGray
$ready = $false
for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
  if ($process.HasExited) { throw "YOLO process exited with code: $($process.ExitCode)" }
  try {
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/" -TimeoutSec 2
    $ready = $true; break
  } catch { Start-Sleep -Seconds 1 }
}

if ($ready) {
  Write-Host "YOLO ready: http://127.0.0.1:$Port" -ForegroundColor Green
} else {
  Write-Warning "YOLO not ready within ${TimeoutSeconds}s, process still running."
}

return [pscustomobject]@{
  Process = $process; Port = $Port; Url = "http://127.0.0.1:$Port"
  PythonPath = $venvPython; WorkingDirectory = $serviceDir
}
