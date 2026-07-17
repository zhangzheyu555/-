param(
  [int]$Port = 5184,
  [int]$TimeoutSeconds = 45
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$frontendDir = Join-Path $repoRoot 'frontend-vue'
$logDir = Join-Path $repoRoot 'output/playwright'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$stdout = Join-Path $logDir 'phase4b-preview.out.log'
$stderr = Join-Path $logDir 'phase4b-preview.err.log'
$env:FRONTEND_PREVIEW_URL = "http://127.0.0.1:$Port"
$env:FRONTEND_ROLE_MENU_ARTIFACT_DIR = $logDir

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
  throw "Port $Port is already in use. Pass -Port with a free local port."
}

$process = Start-Process -FilePath 'npx.cmd' `
  -ArgumentList @('vite', 'preview', '--host', '127.0.0.1', '--port', [string]$Port, '--strictPort') `
  -WorkingDirectory $frontendDir `
  -RedirectStandardOutput $stdout `
  -RedirectStandardError $stderr `
  -PassThru `
  -WindowStyle Hidden

try {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    if ($process.HasExited) {
      $tail = if (Test-Path $stderr) { (Get-Content $stderr -Tail 80) -join [Environment]::NewLine } else { '' }
      throw "frontend preview exited before becoming ready. ExitCode=$($process.ExitCode). Log=$stderr`n$tail"
    }
    try {
      Invoke-WebRequest -UseBasicParsing -Uri $env:FRONTEND_PREVIEW_URL -TimeoutSec 2 | Out-Null
      break
    } catch {
      Start-Sleep -Milliseconds 500
    }
  } while ((Get-Date) -lt $deadline)

  if ((Get-Date) -ge $deadline) {
    throw "frontend preview did not start in $TimeoutSeconds seconds"
  }

  Push-Location $frontendDir
  try {
    & node scripts/verify-role-menus.mjs
    if ($LASTEXITCODE -ne 0) {
      throw "frontend role menu verification failed with exit code $LASTEXITCODE"
    }
  } finally {
    Pop-Location
  }
} finally {
  if ($process -and -not $process.HasExited) {
    Stop-Process -Id $process.Id -Force
  }
  Get-CimInstance Win32_Process |
      Where-Object {
        ($_.Name -match 'node|npm') -and
            ($_.CommandLine -like "*$repoRoot*") -and
            ($_.CommandLine -like '*vite*preview*')
      } |
      ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
      }
}
