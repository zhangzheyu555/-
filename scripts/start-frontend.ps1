[CmdletBinding()]
param(
  [ValidateRange(1, 65535)][int]$Port = 5173,
  [ValidateRange(5, 120)][int]$TimeoutSeconds = 30,
  [string]$BackendTarget = 'http://127.0.0.1:18081'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
$frontendDir = Join-Path $projectRoot 'frontend-vue'

if (-not (Test-Path (Join-Path $frontendDir 'package.json'))) {
  throw '未找到 frontend-vue 目录。'
}

# 1. 查找 Node.js
$nodeCmd = Get-Command node.exe -ErrorAction SilentlyContinue
if (-not $nodeCmd) {
  throw '未找到 Node.js，请先安装 Node.js 18+。'
}
$npmCmd = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCmd) { $npmCmd = Get-Command npm -ErrorAction SilentlyContinue }
if (-not $npmCmd) { throw '未找到 npm。' }

# 2. 检查 node_modules
if (-not (Test-Path (Join-Path $frontendDir 'node_modules'))) {
  Write-Host '首次运行：安装前端依赖...' -ForegroundColor Yellow
  Push-Location $frontendDir
  try {
    & $npmCmd.Source install
    if ($LASTEXITCODE -ne 0) { throw 'npm install 失败。' }
  } finally { Pop-Location }
  Write-Host '依赖安装完成。' -ForegroundColor Green
}

# 3. 释放端口
$existingProcesses = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($existingProcesses.Count -gt 0) {
  Write-Host "端口 $Port 已被占用，正在停止旧进程..." -ForegroundColor Yellow
  foreach ($conn in $existingProcesses) {
    Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
  }
  Start-Sleep -Seconds 2
  if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    throw "端口 $Port 释放失败，请手动停止占用进程。"
  }
}

# 4. 检查后端健康
Write-Host "检查后端连接 ($BackendTarget)..." -ForegroundColor DarkGray
$backendOk = $false
try {
  $health = Invoke-RestMethod -Uri "$BackendTarget/api/health" -TimeoutSec 5
  if ($health.success -and $health.data.status -eq 'UP') {
    $backendOk = $true
    Write-Host "后端健康: UP (Flyway=$($health.data.databaseMigrationVersion))" -ForegroundColor Green
  }
} catch {
  Write-Warning "后端未就绪，前端启动后 API 请求可能返回 503。"
}

# 5. 启动 Vite
Write-Host "启动前端开发服务器..." -ForegroundColor Cyan

$env:VITE_BACKEND_PROXY_TARGET = $BackendTarget

$process = Start-Process -FilePath $nodeCmd.Source `
  -ArgumentList (Join-Path $frontendDir 'node_modules\.bin\vite.cmd'), '--host', '127.0.0.1', '--port', $Port, '--strictPort' `
  -WorkingDirectory $frontendDir `
  -NoNewWindow -PassThru

# 6. 等待就绪
Write-Host "等待前端就绪（最多 ${TimeoutSeconds}s）..." -ForegroundColor DarkGray
$ready = $false
for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
  if ($process.HasExited) {
    throw "Vite 进程意外退出，退出代码: $($process.ExitCode)"
  }
  try {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -TimeoutSec 2 -UseBasicParsing
    if ($response.StatusCode -eq 200) {
      $ready = $true
      break
    }
  } catch {
    Start-Sleep -Seconds 1
  }
}

if ($ready) {
  Write-Host "前端已就绪: http://127.0.0.1:$Port" -ForegroundColor Green
} else {
  Write-Warning "前端未在 ${TimeoutSeconds}s 内就绪，进程仍在运行，请手动检查。"
}

return [pscustomobject]@{
  Process = $process
  Port = $Port
  Url = "http://127.0.0.1:$Port"
  BackendTarget = $BackendTarget
  BackendOk = $backendOk
}
