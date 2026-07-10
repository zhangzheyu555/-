param(
  [Parameter(Mandatory = $true)]
  [string]$Server,

  [string]$User = "root",
  [string]$RemoteRoot = "/opt/store-profit",
  [string]$BackupName = "",
  [string]$PublicUrl = "",

  [switch]$List,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$frontendDir = "$RemoteRoot/frontend"
$backupRoot = "$RemoteRoot/frontend-backup"
$remote = "$User@$Server"

function Require-Command($name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command: $name. Please install it or add it to PATH."
  }
}

Write-Host "AI Profit OS Vue3 frontend rollback"
Write-Host "Server: $remote"
Write-Host "Frontend dir: $frontendDir"
Write-Host "Backup root: $backupRoot"
Write-Host "This script does not restart Java."

Require-Command "ssh"

if ($DryRun) {
  Write-Host "DryRun enabled. No remote command will be executed."
  exit 0
}

if ($List) {
  ssh $remote "if [ -d '$backupRoot' ]; then ls -1t '$backupRoot' | head -20; else echo 'No backup directory: $backupRoot'; fi"
  exit 0
}

if (-not $BackupName) {
  $BackupName = (ssh $remote "if [ -d '$backupRoot' ]; then ls -1t '$backupRoot' | head -1; fi").Trim()
}

if (-not $BackupName) {
  throw "No Vue3 frontend backup found under $backupRoot"
}

$restoreCommand = @"
set -e
backup_dir='$backupRoot/$BackupName'
if [ ! -d "`$backup_dir" ]; then
  echo "Backup not found: `$backup_dir" >&2
  exit 1
fi
mkdir -p '$frontendDir'
rm -rf '$frontendDir'/*
cp -a "`$backup_dir"/. '$frontendDir'/
echo "Restored Vue3 frontend backup: `$backup_dir"
"@

Write-Host "Restoring backup: $BackupName"
ssh $remote $restoreCommand

Write-Host "Vue3 frontend rollback completed."
if ($PublicUrl) {
  Write-Host "Open: $PublicUrl"
} else {
  Write-Host "Open your Nginx Vue3 frontend URL and refresh the browser."
}
