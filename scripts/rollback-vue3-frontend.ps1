[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9.-]*$')]
  [string]$Server,

  [ValidatePattern('^[a-z_][a-z0-9_-]*$')]
  [string]$User = 'deploy',

  [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
  [string]$RemoteRoot = '/opt/store-profit',

  [Alias('BackupName')]
  [string]$ReleaseId = '',

  [string]$PublicUrl = '',
  [switch]$List,
  [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-Command {
  param([Parameter(Mandatory)][string]$Name)
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "Missing command: $Name. Install it or add it to PATH."
  }
}

if ($User -eq 'root') {
  throw 'Root rollback is refused. Use the dedicated non-root deployment account.'
}
if ($ReleaseId -and $ReleaseId -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'ReleaseId is invalid.'
}

$remote = "$User@$Server"
$releaseRoot = "$RemoteRoot/releases/frontend"
$currentLink = "$RemoteRoot/frontend"
$previousFile = "$releaseRoot/.previous"

Write-Host 'AI Profit OS Vue3 candidate rollback'
Write-Host "Server: $remote"
Write-Host "Versioned release root: $releaseRoot"
Write-Host "Atomic current link: $currentLink"

if ($DryRun) {
  Write-Host 'DryRun enabled. No remote command will be executed.'
  exit 0
}

Require-Command 'ssh'

if ($List) {
  $listCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ]; then
  echo 'Refusing root deployment account.' >&2
  exit 1
fi
if [ -d '$releaseRoot' ]; then
  find '$releaseRoot' -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r
fi
"@
  ssh $remote $listCommand
  if ($LASTEXITCODE -ne 0) { throw "Remote release listing failed with exit code $LASTEXITCODE." }
  exit 0
}

$releaseSelector = if ($ReleaseId) { $ReleaseId } else { '__PREVIOUS__' }
$rollbackCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ]; then
  echo 'Refusing root deployment account.' >&2
  exit 1
fi
release_root='$releaseRoot'
current_link='$currentLink'
previous_file='$previousFile'
requested_release='$releaseSelector'

if [ ! -L "`$current_link" ]; then
  echo 'Current frontend path is not an approved symlink; refusing destructive rollback.' >&2
  exit 1
fi
current_target="`$(readlink -f "`$current_link" || true)"
case "`$current_target" in
  "`$release_root"/*) ;;
  *) echo 'Current frontend symlink does not point to the approved versioned release root.' >&2; exit 1 ;;
esac

if [ "`$requested_release" = '__PREVIOUS__' ]; then
  if [ ! -s "`$previous_file" ]; then
    echo 'No approved previous release is recorded. Supply -ReleaseId explicitly.' >&2
    exit 1
  fi
  target_dir="`$(cat "`$previous_file")"
else
  target_dir="`$release_root/`$requested_release"
fi
case "`$target_dir" in
  "`$release_root"/*) ;;
  *) echo 'Requested rollback target is outside the approved versioned release root.' >&2; exit 1 ;;
esac
if [ ! -d "`$target_dir" ] || [ ! -f "`$target_dir/index.html" ] || [ ! -f "`$target_dir/release-manifest.json" ]; then
  echo 'Requested rollback target is not a complete immutable frontend release.' >&2
  exit 1
fi

next_link='$RemoteRoot/.frontend-rollback-next'
if [ -e "`$next_link" ] || [ -L "`$next_link" ]; then
  echo 'Rollback staging link already exists; inspect it before retrying.' >&2
  exit 1
fi
ln -s "`$target_dir" "`$next_link"
mv -Tf "`$next_link" "`$current_link"
printf '%s\n' "`$current_target" > "`$previous_file"
echo "Vue3 frontend rolled back to: `$target_dir"
"@

Write-Host ("Rolling back to " + $(if ($ReleaseId) { $ReleaseId } else { 'the recorded previous release' }) + '...')
ssh $remote $rollbackCommand
if ($LASTEXITCODE -ne 0) { throw "Remote rollback failed with exit code $LASTEXITCODE." }

Write-Host 'Vue3 frontend rollback completed.'
if ($PublicUrl) {
  Write-Host "Open: $PublicUrl"
}
