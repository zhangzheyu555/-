[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9.-]*$')]
  [string]$Server,

  [Parameter(Mandatory = $true)]
  [string]$CandidateDirectory,

  [ValidatePattern('^[a-z_][a-z0-9_-]*$')]
  [string]$User = 'deploy',

  [ValidatePattern('^/[A-Za-z0-9._/-]+$')]
  [string]$RemoteRoot = '/opt/store-profit',

  [string]$PublicUrl = '',
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

function Get-Sha256 {
  param([Parameter(Mandatory)][string]$Path)
  return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

if ($User -eq 'root') {
  throw 'Root deployment is refused. Use a dedicated non-root deployment account with only the required release-directory permissions.'
}

$candidateDirectory = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$manifestPath = Join-Path $candidateDirectory 'release-manifest.json'
$archivePath = Join-Path $candidateDirectory 'frontend-dist.tar.gz'
if (-not (Test-Path -LiteralPath $manifestPath) -or -not (Test-Path -LiteralPath $archivePath)) {
  throw 'CandidateDirectory must contain release-manifest.json and frontend-dist.tar.gz from build-release-candidate.ps1.'
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([int]$manifest.schemaVersion -ne 1 -or -not $manifest.sourceTreeClean) {
  throw 'Release manifest is not an immutable clean-tree candidate.'
}
$releaseId = [string]$manifest.candidateId
if ($releaseId -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'Release manifest candidateId is invalid.'
}
$frontendArtifact = @($manifest.artifacts | Where-Object { $_.name -eq 'frontend-dist.tar.gz' })
if ($frontendArtifact.Count -ne 1) {
  throw 'Release manifest must declare exactly one frontend-dist.tar.gz artifact.'
}
$expectedArchiveHash = ([string]$frontendArtifact[0].sha256).ToLowerInvariant()
if ($expectedArchiveHash -notmatch '^[a-f0-9]{64}$') {
  throw 'Release manifest frontend artifact SHA-256 is invalid.'
}
if ((Get-Sha256 -Path $archivePath) -ne $expectedArchiveHash) {
  throw 'Candidate frontend archive SHA-256 does not match release-manifest.json.'
}
if ([int64]$frontendArtifact[0].bytes -ne (Get-Item -LiteralPath $archivePath).Length) {
  throw 'Candidate frontend archive size does not match release-manifest.json.'
}

$manifestHash = Get-Sha256 -Path $manifestPath
$remote = "$User@$Server"
$releaseRoot = "$RemoteRoot/releases/frontend"
$currentLink = "$RemoteRoot/frontend"
$remoteArchive = "/tmp/ai-profit-frontend-$releaseId.tar.gz"
$remoteManifest = "/tmp/ai-profit-frontend-$releaseId.manifest.json"

Write-Host 'AI Profit OS Vue3 candidate deployment'
Write-Host "Server: $remote"
Write-Host "Candidate: $releaseId"
Write-Host "Frontend SHA-256: $expectedArchiveHash"
Write-Host "Versioned release root: $releaseRoot"
Write-Host "Atomic current link: $currentLink"
Write-Host 'This script deploys only a prebuilt candidate and does not restart Java.'

if ($DryRun) {
  Write-Host 'DryRun enabled. Candidate integrity was checked; no remote command will be executed.'
  exit 0
}

Require-Command 'ssh'
Require-Command 'scp'

$preflightCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ]; then
  echo 'Refusing root deployment account.' >&2
  exit 1
fi
mkdir -p '$releaseRoot'
if [ -e '$currentLink' ] && [ ! -L '$currentLink' ]; then
  echo 'Refusing to replace existing frontend directory. Migrate it to the approved symlink layout first.' >&2
  exit 1
fi
if [ -L '$currentLink' ]; then
  current_target="`$(readlink -f '$currentLink' || true)"
  case "`$current_target" in
    '$releaseRoot'/*) ;;
    *) echo 'Current frontend symlink does not point to the approved versioned release root.' >&2; exit 1 ;;
  esac
fi
"@

Write-Host 'Checking remote deployment account and release layout...'
ssh $remote $preflightCommand
if ($LASTEXITCODE -ne 0) { throw "Remote deployment preflight failed with exit code $LASTEXITCODE." }

Write-Host 'Uploading immutable frontend candidate and manifest...'
scp $archivePath "$remote`:$remoteArchive"
if ($LASTEXITCODE -ne 0) { throw "Frontend archive upload failed with exit code $LASTEXITCODE." }
scp $manifestPath "$remote`:$remoteManifest"
if ($LASTEXITCODE -ne 0) { throw "Release manifest upload failed with exit code $LASTEXITCODE." }

$activateCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ]; then
  echo 'Refusing root deployment account.' >&2
  exit 1
fi
release_root='$releaseRoot'
release_dir='$releaseRoot/$releaseId'
current_link='$currentLink'
next_link='$RemoteRoot/.frontend-next-$releaseId'
previous_file='$releaseRoot/.previous'
archive='$remoteArchive'
manifest='$remoteManifest'
expected_archive_hash='$expectedArchiveHash'
expected_manifest_hash='$manifestHash'

archive_hash="`$(sha256sum "`$archive" | awk '{print `$1}')"
manifest_hash="`$(sha256sum "`$manifest" | awk '{print `$1}')"
if [ "`$archive_hash" != "`$expected_archive_hash" ] || [ "`$manifest_hash" != "`$expected_manifest_hash" ]; then
  echo 'Uploaded candidate hash verification failed.' >&2
  exit 1
fi
if [ -e "`$release_dir" ]; then
  echo 'Release directory already exists; refusing to overwrite an immutable candidate.' >&2
  exit 1
fi
mkdir -p "`$release_dir"
tar -xzf "`$archive" -C "`$release_dir"
if [ ! -f "`$release_dir/index.html" ]; then
  echo 'Candidate archive did not contain frontend index.html.' >&2
  exit 1
fi
cp "`$manifest" "`$release_dir/release-manifest.json"

previous_target=''
if [ -L "`$current_link" ]; then
  previous_target="`$(readlink -f "`$current_link" || true)"
fi
ln -s "`$release_dir" "`$next_link"
mv -Tf "`$next_link" "`$current_link"
printf '%s\n' "`$previous_target" > "`$previous_file"
rm -f "`$archive" "`$manifest"
echo "Vue3 frontend activated: `$release_dir"
"@

Write-Host 'Verifying hashes and atomically switching the frontend symlink...'
ssh $remote $activateCommand
if ($LASTEXITCODE -ne 0) { throw "Remote candidate activation failed with exit code $LASTEXITCODE." }

Write-Host "Vue3 candidate deployment completed: $releaseId"
Write-Host "Rollback: .\scripts\rollback-vue3-frontend.ps1 -Server $Server -User $User -ReleaseId <approved-release-id>"
if ($PublicUrl) {
  Write-Host "Open: $PublicUrl"
}
