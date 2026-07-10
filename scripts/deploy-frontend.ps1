param(
  [Parameter(Mandatory = $true)]
  [string]$Server,

  [string]$User = "root",
  [string]$RemoteRoot = "/opt/store-profit",
  [string]$PublicUrl = "",

  [switch]$All,
  [string[]]$Files = @(),
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$runtimeDir = Join-Path $projectRoot "runtime-static"
$frontendDir = "$RemoteRoot/frontend"
$backupRoot = "$RemoteRoot/frontend-backup"
$backupName = Get-Date -Format "yyyyMMdd-HHmmss"
$backupDir = "$backupRoot/$backupName"
$remote = "$User@$Server"

function Require-Command($name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command: $name. Please make sure OpenSSH client is installed and available in PATH."
  }
}

function Normalize-UploadFiles {
  if ($Files.Count -gt 0) {
    return $Files
  }
  if ($All) {
    return @("index.html", "database.js", "cloudbase.full.js")
  }
  return @("index.html")
}

$uploadFiles = Normalize-UploadFiles
$existingFiles = @()
foreach ($file in $uploadFiles) {
  $localPath = Join-Path $runtimeDir $file
  if (-not (Test-Path -LiteralPath $localPath)) {
    throw "Local frontend file not found: $localPath"
  }
  $existingFiles += [PSCustomObject]@{
    Name = $file
    LocalPath = (Resolve-Path -LiteralPath $localPath).Path
    RemotePath = "$frontendDir/$file"
  }
}

Write-Host "AI Profit OS frontend deploy"
Write-Host "Server: $remote"
Write-Host "Frontend dir: $frontendDir"
Write-Host "Backup dir: $backupDir"
Write-Host "Files:"
$existingFiles | ForEach-Object { Write-Host " - $($_.Name)" }
Write-Host "This script does not run mvn package and does not restart Java."

if ($DryRun) {
  Write-Host "DryRun enabled. No remote command will be executed."
  exit 0
}

Require-Command "ssh"
Require-Command "scp"

$remoteFileList = ($existingFiles | ForEach-Object { "'" + $_.Name.Replace("'", "'\''") + "'" }) -join " "
$backupCommand = @"
set -e
mkdir -p '$frontendDir' '$backupDir'
cd '$frontendDir'
for f in $remoteFileList; do
  if [ -f "`$f" ]; then
    cp -p "`$f" '$backupDir/'"`$f"
  fi
done
"@

Write-Host "Creating remote backup..."
ssh $remote $backupCommand

foreach ($file in $existingFiles) {
  Write-Host "Uploading $($file.Name) ..."
  scp $file.LocalPath "$remote`:$($file.RemotePath)"
}

Write-Host "Frontend upload completed."
Write-Host "Backup saved at: $backupDir"
if ($PublicUrl) {
  Write-Host "Open: $PublicUrl"
} else {
  Write-Host "Open your Nginx frontend URL and refresh the browser."
}
Write-Host "Verify API separately: /api/health"
