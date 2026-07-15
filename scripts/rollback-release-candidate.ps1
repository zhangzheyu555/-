[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9.-]*$')]
  [string]$Server,

  [ValidatePattern('^[a-z_][a-z0-9_-]*$')]
  [string]$User = 'deploy',

  [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
  [string]$RemoteRoot = '/opt/store-profit',

  [string]$ReleaseId = '',
  [switch]$List,
  [switch]$RestartUserService,

  [ValidatePattern('^[A-Za-z0-9@_.-]+\.service$')]
  [string]$UserServiceName = 'ai-profit-backend.service',

  [string]$HealthUrl = '',
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
if ($RemoteRoot -match '(^|/)\.\.(/|$)') {
  throw 'RemoteRoot must not contain a parent-directory segment.'
}
if ($ReleaseId -and $ReleaseId -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'ReleaseId is invalid.'
}
if ($HealthUrl -and $HealthUrl -notmatch '^https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~/-]*)?$') {
  throw 'HealthUrl must be an http(s) URL without shell-sensitive query parameters.'
}

$remote = "$User@$Server"
$releaseRoot = "$RemoteRoot/releases"
$currentLink = "$RemoteRoot/current"
$previousFile = "$releaseRoot/.previous-release"
$restartFlag = if ($RestartUserService) { '1' } else { '0' }

Write-Host 'AI Profit OS immutable release rollback'
Write-Host "Server: $remote"
Write-Host "Versioned release root: $releaseRoot"
Write-Host "Atomic current link: $currentLink"
Write-Host 'This script only switches an approved versioned release symlink; it never restores database data.'

if ($DryRun) {
  Write-Host 'DryRun enabled. No remote command will be executed.'
  exit 0
}

Require-Command -Name 'ssh'

if ($List) {
  $listCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ] || [ "`$(id -un)" != '$User' ]; then
  echo 'Refusing root or unexpected deployment account.' >&2
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
if [ "`$(id -u)" -eq 0 ] || [ "`$(id -un)" != '$User' ]; then
  echo 'Refusing root or unexpected deployment account.' >&2
  exit 1
fi
release_root='$releaseRoot'
current_link='$currentLink'
previous_file='$previousFile'
requested_release='$releaseSelector'
restart_service='$restartFlag'
user_service='$UserServiceName'
health_url='$HealthUrl'

if [ ! -L "`$current_link" ]; then
  echo 'Current deployment path is not an approved symlink; refusing rollback.' >&2
  exit 1
fi
current_target="`$(readlink -f "`$current_link" || true)"
case "`$current_target" in
  "`$release_root"/*) ;;
  *) echo 'Current symlink does not point to the approved versioned release root.' >&2; exit 1 ;;
esac

if [ "`$requested_release" = '__PREVIOUS__' ]; then
  if [ ! -s "`$previous_file" ]; then
    echo 'No recorded previous release exists. Supply -ReleaseId explicitly.' >&2
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
if [ ! -d "`$target_dir" ] || [ ! -s "`$target_dir/backend/store-profit-backend.jar" ] || [ ! -f "`$target_dir/frontend/index.html" ] || [ ! -s "`$target_dir/release-manifest.json" ]; then
  echo 'Requested rollback target is not a complete immutable release.' >&2
  exit 1
fi

next_link='${RemoteRoot}/.current-rollback-next'
if [ -e "`$next_link" ] || [ -L "`$next_link" ]; then
  echo 'Rollback staging link already exists; inspect it before retrying.' >&2
  exit 1
fi
ln -s "`$target_dir" "`$next_link"
mv -Tf "`$next_link" "`$current_link"
printf '%s\n' "`$current_target" > "`$previous_file"

if [ "`$restart_service" = '1' ]; then
  systemctl --user restart "`$user_service"
  systemctl --user is-active --quiet "`$user_service"
  if [ -n "`$health_url" ]; then
    curl --fail --silent --show-error --max-time 15 "`$health_url" >/dev/null
  fi
fi
echo "Release rolled back to: `$target_dir"
"@

Write-Host ('Rolling back to ' + $(if ($ReleaseId) { $ReleaseId } else { 'the recorded previous release' }) + '...')
ssh $remote $rollbackCommand
if ($LASTEXITCODE -ne 0) { throw "Remote release rollback failed with exit code $LASTEXITCODE." }

Write-Host 'Release rollback completed.'
