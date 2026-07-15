[CmdletBinding()]
param(
  [string]$CandidateRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$releaseCommon = Join-Path $PSScriptRoot 'ReleaseCandidateCommon.psm1'
if (-not (Test-Path -LiteralPath $releaseCommon -PathType Leaf)) {
  throw "Release candidate helper is missing: $releaseCommon"
}
Import-Module -Name $releaseCommon -Force -ErrorAction Stop
if ([string]::IsNullOrWhiteSpace($CandidateRoot)) {
  $candidateBase = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { [IO.Path]::GetTempPath() }
  $CandidateRoot = Join-Path $candidateBase 'AI-Profit-OS\release-candidates'
}

function Get-Sha256 {
  param([Parameter(Mandatory)][string]$Path)
  return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Assert-TrackedFile {
  param([Parameter(Mandatory)][string]$Path)

  & git -C $projectRoot ls-files --error-unmatch -- $Path | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "Release candidate requires a tracked file: $Path"
  }
}

function Invoke-ExternalStep {
  param(
    [Parameter(Mandatory)][string]$Name,
    [Parameter(Mandatory)][scriptblock]$Action
  )

  & $Action
  if ($LASTEXITCODE -ne 0) {
    throw "$Name failed with exit code $LASTEXITCODE."
  }
}

function Invoke-ReleaseSourceGate {
  param([Parameter(Mandatory)][ValidateRange(1, 9999)][int]$ExpectedFlywayLatest)

  $gate = Join-Path $projectRoot 'scripts\verify-release-source.ps1'
  if (-not (Test-Path -LiteralPath $gate)) {
    throw "Release source gate is missing: $gate"
  }

  & powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $gate -ExpectedFlywayLatest $ExpectedFlywayLatest
  if ($LASTEXITCODE -ne 0) {
    throw "Release source gate failed with exit code $LASTEXITCODE."
  }
}

$projectRootFull = [IO.Path]::GetFullPath($projectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$candidateRootFull = [IO.Path]::GetFullPath($CandidateRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
if ($candidateRootFull.Equals($projectRootFull, [StringComparison]::OrdinalIgnoreCase) -or
  $candidateRootFull.StartsWith($projectRootFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
  throw 'CandidateRoot must be outside the source checkout so artifacts cannot become release source inputs.'
}

$dirtyStatus = @(& git -C $projectRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) {
  throw 'Unable to read Git working-tree status.'
}
if (-not [string]::IsNullOrWhiteSpace(($dirtyStatus -join "`n"))) {
  throw 'Release candidate build is refused because the source working tree is not clean. Commit or explicitly review all changes first.'
}

$commit = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') {
  throw 'Unable to resolve the immutable Git commit for this release candidate.'
}

$flywaySource = Get-ReleaseFlywaySource -ProjectRoot $projectRoot
$mysqlFlywayLatest = $flywaySource.mysql.version
$h2FlywayLatest = $flywaySource.h2.version
Assert-TrackedFile (Join-Path 'backend/src/main/resources/db/migration' $flywaySource.mysql.fileName).Replace('\', '/')
Assert-TrackedFile (Join-Path 'backend/src/main/resources/db/migration-h2' $flywaySource.h2.fileName).Replace('\', '/')
Assert-TrackedFile 'scripts/ReleaseCandidateCommon.psm1'
Invoke-ReleaseSourceGate -ExpectedFlywayLatest $flywaySource.version
$nodeRuntime = Assert-ReleaseNode20

$candidateId = 'release-{0}-{1}' -f (Get-Date -AsUTC -Format 'yyyyMMddTHHmmssZ'), $commit.Substring(0, 12)
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-candidate-' + [guid]::NewGuid().ToString('N'))
$sourceCopy = Join-Path $temporaryRoot 'source'
$sourceArchive = Join-Path $temporaryRoot 'source.tar'
$candidateStaging = Join-Path $candidateRootFull ('.staging-' + $candidateId + '-' + [guid]::NewGuid().ToString('N'))
$candidateDirectory = Join-Path $candidateRootFull $candidateId
$completed = $false

try {
  if (Test-Path -LiteralPath $candidateDirectory) {
    throw "Candidate directory already exists: $candidateDirectory"
  }
  New-Item -ItemType Directory -Path $temporaryRoot, $candidateRootFull -Force | Out-Null

  if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
    throw 'tar.exe is required to extract the immutable Git source archive and package the frontend artifact.'
  }
  Invoke-ExternalStep -Name 'Freeze Git source archive' -Action {
    & git -C $projectRoot archive --format=tar --output=$sourceArchive $commit
  }
  $sourceArchiveHash = Get-Sha256 -Path $sourceArchive
  New-Item -ItemType Directory -Path $sourceCopy -Force | Out-Null
  Invoke-ExternalStep -Name 'Extract immutable Git source archive' -Action {
    & tar.exe -xf $sourceArchive -C $sourceCopy
  }

  $candidateBackend = Join-Path $sourceCopy 'backend'
  $candidateFrontend = Join-Path $sourceCopy 'frontend-vue'
  if (-not (Test-Path -LiteralPath (Join-Path $candidateBackend 'pom.xml')) -or
    -not (Test-Path -LiteralPath (Join-Path $candidateFrontend 'package-lock.json'))) {
    throw 'Immutable Git source archive did not contain the expected backend and Vue source trees.'
  }
  Push-Location $candidateBackend
  try {
    Invoke-ExternalStep -Name 'Isolated backend package' -Action { & mvn -q -DskipTests package }
  }
  finally {
    Pop-Location
  }

  Push-Location $candidateFrontend
  try {
    Invoke-ExternalStep -Name 'Isolated frontend dependency install' -Action { & npm ci --no-audit --fund=false }
    Invoke-ExternalStep -Name 'Isolated Vue production build' -Action { & npm run build }
  }
  finally {
    Pop-Location
  }

  $builtJar = Join-Path $candidateBackend 'target\store-profit-backend-0.1.0-SNAPSHOT.jar'
  $builtDist = Join-Path $candidateFrontend 'dist'
  if (-not (Test-Path -LiteralPath $builtJar) -or -not (Test-Path -LiteralPath (Join-Path $builtDist 'index.html'))) {
    throw 'Isolated build did not produce both the backend JAR and frontend dist/index.html.'
  }
  New-Item -ItemType Directory -Path $candidateStaging -Force | Out-Null
  $candidateJar = Join-Path $candidateStaging 'store-profit-backend.jar'
  $candidateFrontendArchive = Join-Path $candidateStaging 'frontend-dist.tar.gz'
  Copy-Item -LiteralPath $builtJar -Destination $candidateJar -Force
  Invoke-ExternalStep -Name 'Frontend candidate archive' -Action { & tar.exe -czf $candidateFrontendArchive -C $builtDist . }

  $builtAtUtc = [DateTime]::UtcNow.ToString('o')
  $manifest = [ordered]@{
    schemaVersion = 2
    candidateId = $candidateId
    gitCommit = $commit
    sourceTreeClean = $true
    source = [ordered]@{
      extraction = 'git archive'
      archiveSha256 = $sourceArchiveHash
    }
    flyway = [ordered]@{
      latest = $flywaySource.version
      mysqlLatest = $mysqlFlywayLatest
      h2Latest = $h2FlywayLatest
      mysqlLatestFile = $flywaySource.mysql.fileName
      h2LatestFile = $flywaySource.h2.fileName
    }
    buildEnvironment = [ordered]@{
      node = $nodeRuntime.node
      npm = $nodeRuntime.npm
      nodePolicy = 'Node 20 LTS required'
    }
    builtAtUtc = $builtAtUtc
    artifacts = @(
      [ordered]@{
        name = 'store-profit-backend.jar'
        sha256 = Get-Sha256 -Path $candidateJar
        bytes = (Get-Item -LiteralPath $candidateJar).Length
      },
      [ordered]@{
        name = 'frontend-dist.tar.gz'
        sha256 = Get-Sha256 -Path $candidateFrontendArchive
        bytes = (Get-Item -LiteralPath $candidateFrontendArchive).Length
      }
    )
  }
  $manifestPath = Join-Path $candidateStaging 'release-manifest.json'
  [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))

  Move-Item -LiteralPath $candidateStaging -Destination $candidateDirectory
  $completed = $true
  Write-Host "Release candidate created: $candidateDirectory"
  Write-Host "Manifest: $(Join-Path $candidateDirectory 'release-manifest.json')"
}
finally {
  if (Test-Path -LiteralPath $temporaryRoot) {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
  }
  if (-not $completed -and (Test-Path -LiteralPath $candidateStaging)) {
    Remove-Item -LiteralPath $candidateStaging -Recurse -Force
  }
}
