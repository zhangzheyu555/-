[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$Port = 8000,
  [ValidateRange(5, 120)][int]$TimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$serviceDir = Join-Path $projectRoot 'inspection-service'
$venvDir = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'AI-Profit-OS\inspection-venv'

# 1. 查找 Python
$pythonCmd = $null
foreach ($cmd in @('python', 'python3', 'py')) {
  $found = Get-Command $cmd -ErrorAction SilentlyContinue
  if ($found) {
    $pythonCmd = $found.Source
    break
  }
}
if (-not $pythonCmd) {
  throw '未找到 Python，请先安装 Python 3.9-3.12 并勾选 Add to PATH。'
}
Write-Host "Python: $pythonCmd" -ForegroundColor DarkGray

# 2. 创建虚拟环境（如首次运行）
if (-not (Test-Path (Join-Path $venvDir 'Scripts\python.exe'))) {
  Write-Host '首次运行：创建虚拟环境并安装依赖（约1-2GB，请耐心等待）...' -ForegroundColor Yellow
  [void](New-Item -ItemType Directory -Path (Split-Path $venvDir -Parent) -Force)
  & $pythonCmd -m venv $venvDir
  if ($LASTEXITCODE -ne 0) { throw '虚拟环境创建失败。' }
  $venvPython = Join-Path $venvDir 'Scripts\python.exe'
  & $venvPython -m pip install --upgrade pip
  & $venvPython -m pip install -r (Join-Path $serviceDir 'requirements.txt')
  if ($LASTEXITCODE -ne 0) { throw '依赖安装失败，请检查网络后重试。' }
  Write-Host '依赖安装完成。' -ForegroundColor Green
}

$venvPython = Join-Path $venvDir 'Scripts\python.exe'

# 3. 检查端口
$existingProcess = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existingProcess) {
  Write-Host "端口 $Port 已被占用，正在停止旧进程..." -ForegroundColor Yellow
  $procId = $existingProcess.OwningProcess
  Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "端口 $Port 释放失败，请手动停止占用进程。"
  }
}

# 4. 启动 YOLO 服务
Write-Host "启动 YOLO 卫生识别服务..." -ForegroundColor Cyan
$process = Start-Process -FilePath $venvPython `
  -ArgumentList '-m', 'uvicorn', 'api_server:api', '--host', '127.0.0.1', '--port', $Port `
  -WorkingDirectory $serviceDir `
  -NoNewWindow -PassThru

# 5. 等待就绪
Write-Host "等待 YOLO 服务就绪（最多 ${TimeoutSeconds}s）..." -ForegroundColor DarkGray
$ready = $false
for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
  if ($process.HasExited) {
    throw "YOLO 进程意外退出，退出代码: $($process.ExitCode)"
  }
  try {
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/" -TimeoutSec 2
    $ready = $true
    break
  } catch {
    Start-Sleep -Seconds 1
  }
}

if ($ready) {
  Write-Host "YOLO 卫生识别服务已就绪: http://127.0.0.1:$Port" -ForegroundColor Green
} else {
  Write-Warning "YOLO 未在 ${TimeoutSeconds}s 内就绪，进程仍在运行，请手动检查。"
}

# 返回进程信息供调用者管理
return [pscustomobject]@{
  Process = $process
  Port = $Port
  Url = "http://127.0.0.1:$Port"
  PythonPath = $venvPython
  WorkingDirectory = $serviceDir
}
