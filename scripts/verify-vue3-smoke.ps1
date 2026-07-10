param(
  [string]$FrontendUrl = "http://127.0.0.1:5173",
  [string]$ApiBaseUrl = "http://127.0.0.1:8080",
  [string]$ArchiveRoot = ""
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$vueDir = Join-Path $projectRoot "frontend-vue"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"

if (-not $ArchiveRoot) {
  $ArchiveRoot = Join-Path $projectRoot "output\vue3-smoke-check"
}

$archiveDir = Join-Path $ArchiveRoot $timestamp
$logFile = Join-Path $archiveDir "smoke-check.log"
$summaryFile = Join-Path $archiveDir "summary.txt"
$backendHealthUrl = "$($ApiBaseUrl.TrimEnd('/'))/api/health"
$loginUrl = "$($FrontendUrl.TrimEnd('/'))/login"
$failedStep = ""
$passed = $false

New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null

function Write-Log {
  param([string]$Message)
  $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Message"
  Write-Host $line
  Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8
}

function Test-HttpOk {
  param(
    [string]$StepName,
    [string]$Url
  )
  $script:failedStep = $StepName
  Write-Log "START: $StepName - $Url"
  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 10
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
      throw "HTTP status $($response.StatusCode)"
    }
    Write-Log "PASS: $StepName"
  } catch {
    throw "$StepName failed. Please make sure the service is already running. Detail: $($_.Exception.Message)"
  }
}

function Invoke-NpmStep {
  param(
    [string]$StepName,
    [string[]]$Arguments
  )
  $script:failedStep = $StepName
  Write-Log "START: $StepName - npm $($Arguments -join ' ')"
  Push-Location $vueDir
  try {
    & npm @Arguments 2>&1 | Tee-Object -FilePath $logFile -Append
    if ($LASTEXITCODE -ne 0) {
      throw "npm $($Arguments -join ' ') exited with code $LASTEXITCODE"
    }
    Write-Log "PASS: $StepName"
  } finally {
    Pop-Location
  }
}

function Archive-PlaywrightReport {
  Write-Log "Archiving Playwright smoke report..."
  $testResults = Join-Path $vueDir "test-results"
  if (Test-Path -LiteralPath $testResults) {
    Copy-Item -LiteralPath $testResults -Destination (Join-Path $archiveDir "test-results") -Recurse -Force
    Write-Log "Report archived: $archiveDir"
  } else {
    Write-Log "No frontend-vue/test-results directory found to archive."
  }
}

function Write-Summary {
  param([bool]$Success)
  $status = if ($Success) { "PASS" } else { "FAIL" }
  $lines = @(
    "AI Profit OS Vue3 smoke check",
    "Status: $status",
    "Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "Vue3: $FrontendUrl",
    "Backend: $ApiBaseUrl",
    "Report directory: $archiveDir",
    "Failed step: $(if ($Success) { '-' } else { $failedStep })",
    "",
    "Steps:",
    "- Backend /api/health",
    "- Vue3 /login",
    "- npm run test:e2e:smoke",
    "",
    "Note: smoke is read-only and does not run write E2E tests."
  )
  Set-Content -LiteralPath $summaryFile -Value $lines -Encoding UTF8
}

Write-Log "AI Profit OS Vue3 smoke verification"
Write-Log "FrontendUrl: $FrontendUrl"
Write-Log "ApiBaseUrl: $ApiBaseUrl"
Write-Log "ArchiveDir: $archiveDir"
Write-Log "This script does not start or stop backend/Vue services and does not run write E2E tests."

try {
  Test-HttpOk "backend health check" $backendHealthUrl
  Test-HttpOk "Vue login check" $loginUrl

  $env:E2E_BASE_URL = $FrontendUrl.TrimEnd("/")
  $env:E2E_API_URL = $ApiBaseUrl.TrimEnd("/")

  $testResults = Join-Path $vueDir "test-results"
  if (Test-Path -LiteralPath $testResults) {
    Remove-Item -LiteralPath $testResults -Recurse -Force
  }

  Invoke-NpmStep "Vue3 E2E smoke" @("run", "test:e2e:smoke")
  $passed = $true
} catch {
  Write-Log "FAILED: $($_.Exception.Message)"
} finally {
  Archive-PlaywrightReport
  Write-Summary $passed
}

if ($passed) {
  Write-Host ""
  Write-Host "Vue3 smoke check passed. Report directory: $archiveDir"
  exit 0
}

Write-Host ""
Write-Host "Vue3 smoke check failed. Failed step: $failedStep"
Write-Host "Report directory: $archiveDir"
exit 1
