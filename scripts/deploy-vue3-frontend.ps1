param(
  [Parameter(Mandatory = $true)]
  [string]$Server,

  [string]$User = "root",
  [string]$RemoteRoot = "/opt/store-profit",
  [string]$PublicUrl = "",

  [switch]$SkipBuild,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$vueDir = Join-Path $projectRoot "frontend-vue"
$distDir = Join-Path $vueDir "dist"
$frontendDir = "$RemoteRoot/frontend"
$backupRoot = "$RemoteRoot/frontend-backup"
$backupName = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = "$backupRoot/$backupName"
$remote = "$User@$Server"
$archive = Join-Path ([System.IO.Path]::GetTempPath()) "ai-profit-vue3-$backupName.tar.gz"
$remoteArchive = "/tmp/ai-profit-vue3-$backupName.tar.gz"

function Require-Command($name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command: $name. Please install it or add it to PATH."
  }
}

Write-Host "AI Profit OS Vue3 frontend deploy"
Write-Host "Server: $remote"
Write-Host "Frontend dir: $frontendDir"
Write-Host "Backup dir: $backupDir"
Write-Host "This script does not run mvn package and does not restart Java."

Require-Command "ssh"
Require-Command "scp"
Require-Command "tar"

if (-not $SkipBuild) {
  Write-Host "Building Vue3 frontend..."
  Push-Location $vueDir
  try {
    npm run build
  } finally {
    Pop-Location
  }
}

if (-not (Test-Path -LiteralPath (Join-Path $distDir "index.html"))) {
  throw "Vue3 dist not found. Run npm run build first: $distDir"
}

if ($DryRun) {
  Write-Host "DryRun enabled. Build checked, no remote command will be executed."
  exit 0
}

if (Test-Path -LiteralPath $archive) {
  Remove-Item -LiteralPath $archive -Force
}

Write-Host "Packing dist..."
tar -czf $archive -C $distDir .

$remoteCommand = @"
set -e
mkdir -p '$frontendDir' '$backupDir'
if [ -d '$frontendDir' ] && [ "`$(ls -A '$frontendDir' 2>/dev/null)" ]; then
  cp -a '$frontendDir'/.' '$backupDir'/
fi
rm -rf '$frontendDir'/*
tar -xzf '$remoteArchive' -C '$frontendDir'
rm -f '$remoteArchive'
echo 'Vue3 frontend deployed to $frontendDir'
"@

Write-Host "Uploading archive..."
scp $archive "$remote`:$remoteArchive"

Write-Host "Deploying on server..."
ssh $remote $remoteCommand

Remove-Item -LiteralPath $archive -Force

Write-Host "Vue3 frontend deploy completed."
Write-Host "Backup saved at: $backupDir"
if ($PublicUrl) {
  Write-Host "Open: $PublicUrl"
} else {
  Write-Host "Open your Nginx Vue3 frontend URL and refresh the browser."
}
Write-Host "Verify API separately: /api/health"
