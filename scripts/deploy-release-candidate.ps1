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

function Get-Sha256 {
  param([Parameter(Mandatory)][string]$Path)

  return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Get-ManifestArtifact {
  param(
    [Parameter(Mandatory)]$Manifest,
    [Parameter(Mandatory)][string]$Name
  )

  $matches = @($Manifest.artifacts | Where-Object { [string]$_.name -eq $Name })
  if ($matches.Count -ne 1) {
    throw "Release manifest must declare exactly one $Name artifact."
  }
  $artifact = $matches[0]
  $hash = ([string]$artifact.sha256).ToLowerInvariant()
  if ($hash -notmatch '^[a-f0-9]{64}$') {
    throw "Release manifest $Name SHA-256 is invalid."
  }
  if ([int64]$artifact.bytes -le 0) {
    throw "Release manifest $Name size is invalid."
  }
  return $artifact
}

function Assert-ArtifactIntegrity {
  param(
    [Parameter(Mandatory)][string]$CandidateDirectory,
    [Parameter(Mandatory)]$Artifact
  )

  $name = [string]$Artifact.name
  $path = Join-Path $CandidateDirectory $name
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw "Candidate artifact is missing: $name"
  }
  if ([int64]$Artifact.bytes -ne (Get-Item -LiteralPath $path).Length) {
    throw "Candidate artifact size does not match release-manifest.json: $name"
  }
  if ((Get-Sha256 -Path $path) -ne ([string]$Artifact.sha256).ToLowerInvariant()) {
    throw "Candidate artifact SHA-256 does not match release-manifest.json: $name"
  }
  return $path
}

if ($User -eq 'root') {
  throw 'Root deployment is refused. Use a dedicated non-root deployment account with only release-directory permissions.'
}
if ($RemoteRoot -match '(^|/)\.\.(/|$)') {
  throw 'RemoteRoot must not contain a parent-directory segment.'
}
if ($HealthUrl -and $HealthUrl -notmatch '^https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~/-]*)?$') {
  throw 'HealthUrl must be an http(s) URL without shell-sensitive query parameters.'
}

$candidateDirectory = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$manifestPath = Join-Path $candidateDirectory 'release-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
  throw 'CandidateDirectory must contain release-manifest.json from build-release-candidate.ps1.'
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([int]$manifest.schemaVersion -ne 2 -or -not $manifest.sourceTreeClean) {
  throw 'Release manifest is not an immutable clean-tree candidate.'
}
if ([string]$manifest.gitCommit -notmatch '^[a-f0-9]{40}$') {
  throw 'Release manifest Git commit is invalid.'
}
$mysqlFlywayLatest = [int]$manifest.flyway.mysqlLatest
$h2FlywayLatest = [int]$manifest.flyway.h2Latest
$manifestFlywayLatest = [int]$manifest.flyway.latest
$mysqlFlywayFile = [string]$manifest.flyway.mysqlLatestFile
$h2FlywayFile = [string]$manifest.flyway.h2LatestFile
if ($mysqlFlywayLatest -lt 1 -or $h2FlywayLatest -lt 1 -or $manifestFlywayLatest -lt 1 -or
    $mysqlFlywayLatest -ne $h2FlywayLatest -or $mysqlFlywayLatest -ne $manifestFlywayLatest) {
  throw 'Release manifest Flyway versions must be positive and synchronized between MySQL, H2, and the recorded latest version.'
}
if ($mysqlFlywayFile -notmatch ("^V{0}__.+\.sql$" -f $manifestFlywayLatest) -or
    $h2FlywayFile -notmatch ("^V{0}__.+\.sql$" -f $manifestFlywayLatest) -or
    -not $mysqlFlywayFile.Equals($h2FlywayFile, [StringComparison]::Ordinal)) {
  throw 'Release manifest Flyway latest migration filenames are missing, invalid, or not synchronized between MySQL and H2.'
}
$releaseId = [string]$manifest.candidateId
if ($releaseId -notmatch '^[A-Za-z0-9._-]+$') {
  throw 'Release manifest candidateId is invalid.'
}

$backendArtifact = Get-ManifestArtifact -Manifest $manifest -Name 'store-profit-backend.jar'
$frontendArtifact = Get-ManifestArtifact -Manifest $manifest -Name 'frontend-dist.tar.gz'
$backendPath = Assert-ArtifactIntegrity -CandidateDirectory $candidateDirectory -Artifact $backendArtifact
$frontendArchivePath = Assert-ArtifactIntegrity -CandidateDirectory $candidateDirectory -Artifact $frontendArtifact
$manifestHash = Get-Sha256 -Path $manifestPath

$remote = "$User@$Server"
$releaseRoot = "$RemoteRoot/releases"
$currentLink = "$RemoteRoot/current"
$remoteBackend = "/tmp/ai-profit-$releaseId-backend.jar"
$remoteFrontend = "/tmp/ai-profit-$releaseId-frontend.tar.gz"
$remoteManifest = "/tmp/ai-profit-$releaseId-manifest.json"
$restartFlag = if ($RestartUserService) { '1' } else { '0' }

Write-Host 'AI Profit OS immutable release deployment'
Write-Host "Server: $remote"
Write-Host "Candidate: $releaseId"
Write-Host "Git commit: $($manifest.gitCommit)"
Write-Host "Flyway: synchronized V$manifestFlywayLatest ($mysqlFlywayFile)"
Write-Host "Backend SHA-256: $($backendArtifact.sha256)"
Write-Host "Frontend SHA-256: $($frontendArtifact.sha256)"
Write-Host "Versioned release root: $releaseRoot"
Write-Host "Atomic current link: $currentLink"
Write-Host 'No password, API key, database credential, or root account is accepted by this script.'

if ($DryRun) {
  Write-Host 'DryRun enabled. Candidate integrity was checked; no remote command will be executed.'
  exit 0
}

Require-Command -Name 'ssh'
Require-Command -Name 'scp'

$preflightCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ] || [ "`$(id -un)" != '$User' ]; then
  echo 'Refusing root or unexpected deployment account.' >&2
  exit 1
fi
mkdir -p '$releaseRoot'
if [ -e '$currentLink' ] && [ ! -L '$currentLink' ]; then
  echo 'Refusing to replace an existing current directory. Migrate it to the approved symlink layout first.' >&2
  exit 1
fi
if [ -L '$currentLink' ]; then
  current_target="`$(readlink -f '$currentLink' || true)"
  case "`$current_target" in
    '$releaseRoot'/*) ;;
    *) echo 'Current symlink does not point to the approved versioned release root.' >&2; exit 1 ;;
  esac
fi
"@

Write-Host 'Checking remote deployment account and versioned-release layout...'
ssh $remote $preflightCommand
if ($LASTEXITCODE -ne 0) { throw "Remote deployment preflight failed with exit code $LASTEXITCODE." }

Write-Host 'Uploading immutable backend, frontend, and manifest...'
scp $backendPath "$remote`:$remoteBackend"
if ($LASTEXITCODE -ne 0) { throw "Backend artifact upload failed with exit code $LASTEXITCODE." }
scp $frontendArchivePath "$remote`:$remoteFrontend"
if ($LASTEXITCODE -ne 0) { throw "Frontend artifact upload failed with exit code $LASTEXITCODE." }
scp $manifestPath "$remote`:$remoteManifest"
if ($LASTEXITCODE -ne 0) { throw "Release manifest upload failed with exit code $LASTEXITCODE." }

$activateCommand = @"
set -Eeuo pipefail
if [ "`$(id -u)" -eq 0 ] || [ "`$(id -un)" != '$User' ]; then
  echo 'Refusing root or unexpected deployment account.' >&2
  exit 1
fi
release_root='$releaseRoot'
release_dir='${releaseRoot}/$releaseId'
staging_dir='${releaseRoot}/.staging-$releaseId'
current_link='$currentLink'
next_link='${RemoteRoot}/.current-next-$releaseId'
previous_file='${releaseRoot}/.previous-release'
backend='$remoteBackend'
frontend='$remoteFrontend'
manifest='$remoteManifest'
expected_backend_hash='$($backendArtifact.sha256)'
expected_frontend_hash='$($frontendArtifact.sha256)'
expected_manifest_hash='$manifestHash'
restart_service='$restartFlag'
user_service='$UserServiceName'
health_url='$HealthUrl'

for path in "`$backend" "`$frontend" "`$manifest"; do
  if [ ! -f "`$path" ]; then
    echo 'Uploaded candidate is incomplete.' >&2
    exit 1
  fi
done
if [ "`$(sha256sum "`$backend" | awk '{print `$1}')" != "`$expected_backend_hash" ] || \
   [ "`$(sha256sum "`$frontend" | awk '{print `$1}')" != "`$expected_frontend_hash" ] || \
   [ "`$(sha256sum "`$manifest" | awk '{print `$1}')" != "`$expected_manifest_hash" ]; then
  echo 'Uploaded candidate hash verification failed.' >&2
  exit 1
fi
if tar -tzf "`$frontend" | grep -Eq '(^/|(^|/)\.\.(/|`$))'; then
  echo 'Frontend archive contains an unsafe path.' >&2
  exit 1
fi
if [ -e "`$release_dir" ] || [ -e "`$staging_dir" ] || [ -L "`$next_link" ]; then
  echo 'A release or staging path already exists; refusing to overwrite an immutable candidate.' >&2
  exit 1
fi

mkdir -p "`$staging_dir/backend" "`$staging_dir/frontend"
install -m 0644 "`$backend" "`$staging_dir/backend/store-profit-backend.jar"
tar -xzf "`$frontend" -C "`$staging_dir/frontend"
cp "`$manifest" "`$staging_dir/release-manifest.json"
if [ ! -s "`$staging_dir/backend/store-profit-backend.jar" ] || [ ! -f "`$staging_dir/frontend/index.html" ] || [ ! -s "`$staging_dir/release-manifest.json" ]; then
  echo 'Candidate archive did not contain the required backend, frontend, and manifest files.' >&2
  exit 1
fi
mv "`$staging_dir" "`$release_dir"

previous_target=''
if [ -L "`$current_link" ]; then
  previous_target="`$(readlink -f "`$current_link" || true)"
fi
ln -s "`$release_dir" "`$next_link"
mv -Tf "`$next_link" "`$current_link"
printf '%s\n' "`$previous_target" > "`$previous_file"
rm -f "`$backend" "`$frontend" "`$manifest"

if [ "`$restart_service" = '1' ]; then
  systemctl --user restart "`$user_service"
  systemctl --user is-active --quiet "`$user_service"
  if [ -n "`$health_url" ]; then
    curl --fail --silent --show-error --max-time 15 "`$health_url" >/dev/null
  fi
fi
echo "Release activated: `$release_dir"
"@

Write-Host 'Verifying remote hashes and atomically switching the unified release symlink...'
ssh $remote $activateCommand
if ($LASTEXITCODE -ne 0) { throw "Remote candidate activation failed with exit code $LASTEXITCODE. The uploaded candidate is retained for investigation; use the rollback entry only after review." }

Write-Host "Release candidate deployment completed: $releaseId"
Write-Host "Rollback: .\scripts\rollback-release-candidate.ps1 -Server $Server -User $User$(if ($RestartUserService) { " -RestartUserService -UserServiceName $UserServiceName" })"
