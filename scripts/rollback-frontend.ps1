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
    throw "Missing command: $name. Please make sure OpenSSH client is installed and available in PATH."
  }
}

Write-Host "AI Profit OS frontend rollback"
Write-Host "Server: $remote"
Write-Host "Frontend dir: $frontendDir"
Write-Host "Backup root: $backupRoot"

if ($DryRun) {
  Write-Host "DryRun enabled. No remote command will be executed."
  if ($BackupName) {
    Write-Host "Would restore backup: $BackupName"
  } else {
    Write-Host "Would restore latest backup."
  }
  exit 0
}

Require-Command "ssh"

if ($List) {
  ssh $remote "if [ -d '$backupRoot' ]; then ls -1t '$backupRoot' | head -20; else echo 'No backup directory: $backupRoot'; fi"
  exit 0
}

if (-not $BackupName) {
  $BackupName = (ssh $remote "if [ -d '$backupRoot' ]; then ls -1t '$backupRoot' | head -1; fi").Trim()
}

if (-not $BackupName) {
  throw "No frontend backup found under $backupRoot"
}

$restoreCommand = @"
set -e
backup_dir='$backupRoot/$BackupName'
if [ ! -d "`$backup_dir" ]; then
  echo "Backup not found: `$backup_dir" >&2
  exit 1
fi
mkdir -p '$frontendDir'
cp -p "`$backup_dir"/* '$frontendDir'/
echo "Restored frontend backup: `$backup_dir"
"@

Write-Host "Restoring backup: $BackupName"
ssh $remote $restoreCommand

Write-Host "Frontend rollback completed."
Write-Host "This script did not restart Java."
if ($PublicUrl) {
  Write-Host "Open: $PublicUrl"
} else {
  Write-Host "Open your Nginx frontend URL and refresh the browser."
}
