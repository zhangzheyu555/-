[CmdletBinding()]
param(
  [ValidateSet('h5', 'mp-weixin', 'android', 'ios', 'all')]
  [string[]]$Target = @('all')
)

$ErrorActionPreference = 'Stop'

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mobileRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'mobile-uniapp'))
$evidenceRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'output/mobile-release-evidence'))
$tempPrefix = 'ai-profit-mobile-candidate-'
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Assert-RepositoryCandidateSource {
  if (-not (Test-Path -LiteralPath $mobileRoot -PathType Container)) {
    throw 'mobile-uniapp source directory was not found; mobile candidate build is refused.'
  }

  $gitRoot = (& git -C $repoRoot rev-parse --show-toplevel).Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($gitRoot)) {
    throw 'Mobile candidate build requires a Git worktree.'
  }

  if (-not [IO.Path]::GetFullPath($gitRoot).TrimEnd('\\').Equals($repoRoot.TrimEnd('\\'), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Mobile candidate build must run from the repository root worktree.'
  }

  $changes = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
  if ($LASTEXITCODE -ne 0) {
    throw 'Cannot determine Git worktree status; mobile candidate build is refused.'
  }
  if ($changes.Count -gt 0) {
    throw 'Mobile candidate build is refused because the Git worktree is not clean. Commit or explicitly review all changes before creating a release candidate.'
  }

  $requiredTrackedPaths = @(
    'mobile-uniapp/package.json',
    'mobile-uniapp/package-lock.json',
    'mobile-uniapp/.nvmrc',
    'mobile-uniapp/.npmrc',
    'mobile-uniapp/.env.staging.example',
    'mobile-uniapp/src',
    'scripts/build-mobile-candidates.ps1'
  )
  foreach ($relativePath in $requiredTrackedPaths) {
    $tracked = @(& git -C $repoRoot ls-files --error-unmatch -- $relativePath 2>$null)
    if ($LASTEXITCODE -ne 0 -or $tracked.Count -eq 0) {
      throw "Mobile candidate build is refused because required release source is not Git tracked: $relativePath"
    }
  }

  $commit = (& git -C $repoRoot rev-parse HEAD).Trim()
  if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-fA-F]{40}$') {
    throw 'Cannot determine the immutable source commit; mobile candidate build is refused.'
  }
  return $commit.ToLowerInvariant()
}

function Assert-Node20 {
  $nodeVersion = (& node --version).Trim()
  if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v20\.\d+\.\d+$') {
    throw 'Node 20 LTS is required for UniApp candidate builds. Current Node runtime was rejected; do not use Node 24 as a release candidate builder.'
  }

  $npmVersion = (& npm --version).Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($npmVersion)) {
    throw 'npm was not found; mobile candidate build is refused.'
  }

  return [pscustomobject]@{
    node = $nodeVersion
    npm = $npmVersion
  }
}

function Get-RequestedTargets {
  if ($Target -contains 'all') {
    return @('h5', 'mp-weixin', 'android', 'ios')
  }
  return @($Target | Select-Object -Unique)
}

function ConvertTo-Sha256 {
  param([Parameter(Mandatory)][string]$Text)
  $algorithm = [Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
  }
  finally {
    $algorithm.Dispose()
  }
}

function Get-TargetApiEndpoint {
  param([Parameter(Mandatory)][string]$Name)

  $variableByTarget = @{
    'h5' = 'MOBILE_STAGING_H5_API_BASE_URL'
    'mp-weixin' = 'MOBILE_STAGING_MP_WEIXIN_API_BASE_URL'
    'android' = 'MOBILE_STAGING_ANDROID_API_BASE_URL'
    'ios' = 'MOBILE_STAGING_IOS_API_BASE_URL'
  }
  $variableName = $variableByTarget[$Name]
  if ([string]::IsNullOrWhiteSpace($variableName)) {
    throw "No staging API configuration is defined for target: $Name"
  }

  # Deliberately do not write the raw value to stdout, logs, command lines, or manifests.
  $rawValue = [Environment]::GetEnvironmentVariable($variableName, 'Process')
  if ([string]::IsNullOrWhiteSpace($rawValue)) {
    throw "$variableName must be provided through the current process environment or approved encrypted user configuration."
  }

  $uri = $null
  if (-not [Uri]::TryCreate($rawValue.Trim(), [UriKind]::Absolute, [ref]$uri) `
      -or $uri.Scheme -ne 'https' `
      -or [string]::IsNullOrWhiteSpace($uri.Host) `
      -or -not [string]::IsNullOrWhiteSpace($uri.UserInfo)) {
    throw "$variableName must be an absolute HTTPS URL with a host and without embedded credentials."
  }

  $apiHost = $uri.DnsSafeHost.TrimEnd('.').ToLowerInvariant()
  $parsedAddress = $null
  $isLoopbackAddress = [Net.IPAddress]::TryParse($apiHost, [ref]$parsedAddress) -and (
    [Net.IPAddress]::IsLoopback($parsedAddress) -or
    $parsedAddress.Equals([Net.IPAddress]::Any) -or
    $parsedAddress.Equals([Net.IPAddress]::IPv6Any)
  )
  if ($apiHost -eq 'localhost' -or $apiHost.EndsWith('.localhost') -or $apiHost -eq 'mobile-api.invalid' -or $apiHost.EndsWith('.invalid') -or $isLoopbackAddress) {
    throw "$variableName points to a prohibited candidate host. Staging candidates require a non-local, non-.invalid HTTPS host."
  }

  $normalized = $uri.AbsoluteUri.TrimEnd('/')
  return [pscustomobject]@{
    raw = $normalized
    host = $apiHost
    sha256 = ConvertTo-Sha256 -Text $normalized
  }
}

function Get-MpWeixinAppId {
  $appId = [Environment]::GetEnvironmentVariable('MOBILE_STAGING_MP_WEIXIN_APP_ID', 'Process')
  if ([string]::IsNullOrWhiteSpace($appId) -or $appId.Trim() -notmatch '^wx[0-9a-zA-Z]{16}$') {
    throw 'MOBILE_STAGING_MP_WEIXIN_APP_ID must be supplied through the current process environment or approved CI secret configuration.'
  }
  return $appId.Trim()
}

function Set-MpWeixinAppIdInTemporarySource {
  param(
    [Parameter(Mandatory)][string]$ArchivedMobileRoot,
    [Parameter(Mandatory)][string]$AppId
  )
  $manifestPath = Join-Path $ArchivedMobileRoot 'src/manifest.json'
  $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($null -eq $manifest.'mp-weixin') { throw 'Temporary mobile source is missing mp-weixin manifest configuration.' }
  $manifest.appid = $AppId
  $manifest.'mp-weixin'.appid = $AppId
  [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 12), $utf8NoBom)
}

function Invoke-WhitelistedNpm {
  param(
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string[]]$NpmArguments,
    [Parameter(Mandatory)][string]$LogPrefix,
    [Parameter(Mandatory)][string]$LogDirectory,
    [Parameter(Mandatory)][string]$ProcessSandbox,
    [string]$ApiBaseUrl = ''
  )

  $npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
  $nodeDirectory = Split-Path -Parent $npmCommand
  $systemRoot = [Environment]::GetEnvironmentVariable('SystemRoot', 'Process')
  $comSpec = [Environment]::GetEnvironmentVariable('ComSpec', 'Process')
  if ([string]::IsNullOrWhiteSpace($systemRoot) -or [string]::IsNullOrWhiteSpace($comSpec)) {
    throw 'Windows process environment is incomplete; mobile candidate build is refused.'
  }

  foreach ($directory in @($ProcessSandbox, (Join-Path $ProcessSandbox 'tmp'), (Join-Path $ProcessSandbox 'npm-cache'), (Join-Path $ProcessSandbox 'home'), (Join-Path $ProcessSandbox 'appdata'))) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
  }
  $emptyNpmrc = Join-Path $ProcessSandbox 'empty.npmrc'
  [IO.File]::WriteAllText($emptyNpmrc, '', $utf8NoBom)

  $commandLine = '""{0}" {1}"' -f $npmCommand, ($NpmArguments -join ' ')
  $processInfo = New-Object Diagnostics.ProcessStartInfo
  $processInfo.FileName = $comSpec
  $processInfo.Arguments = "/d /s /c $commandLine"
  $processInfo.WorkingDirectory = $WorkingDirectory
  $processInfo.UseShellExecute = $false
  $processInfo.CreateNoWindow = $true
  $processInfo.RedirectStandardOutput = $true
  $processInfo.RedirectStandardError = $true
  $processInfo.EnvironmentVariables.Clear()
  $processInfo.EnvironmentVariables['PATH'] = @($nodeDirectory, (Join-Path $systemRoot 'System32'), $systemRoot) -join ';'
  $processInfo.EnvironmentVariables['SystemRoot'] = $systemRoot
  $processInfo.EnvironmentVariables['ComSpec'] = $comSpec
  $processInfo.EnvironmentVariables['TEMP'] = Join-Path $ProcessSandbox 'tmp'
  $processInfo.EnvironmentVariables['TMP'] = Join-Path $ProcessSandbox 'tmp'
  $processInfo.EnvironmentVariables['APPDATA'] = Join-Path $ProcessSandbox 'appdata'
  $processInfo.EnvironmentVariables['LOCALAPPDATA'] = Join-Path $ProcessSandbox 'appdata'
  $processInfo.EnvironmentVariables['USERPROFILE'] = Join-Path $ProcessSandbox 'home'
  $processInfo.EnvironmentVariables['NPM_CONFIG_CACHE'] = Join-Path $ProcessSandbox 'npm-cache'
  $processInfo.EnvironmentVariables['NPM_CONFIG_USERCONFIG'] = $emptyNpmrc
  $processInfo.EnvironmentVariables['NPM_CONFIG_AUDIT'] = 'false'
  $processInfo.EnvironmentVariables['NPM_CONFIG_FUND'] = 'false'
  $processInfo.EnvironmentVariables['NPM_CONFIG_UPDATE_NOTIFIER'] = 'false'
  $processInfo.EnvironmentVariables['NPM_CONFIG_REGISTRY'] = 'https://registry.npmjs.org/'
  $processInfo.EnvironmentVariables['CI'] = 'true'
  if (-not [string]::IsNullOrWhiteSpace($ApiBaseUrl)) {
    $processInfo.EnvironmentVariables['VITE_API_BASE_URL'] = $ApiBaseUrl
  }

  $process = New-Object Diagnostics.Process
  $process.StartInfo = $processInfo
  if (-not $process.Start()) {
    throw "Failed to start npm for $LogPrefix."
  }
  $stdoutTask = $process.StandardOutput.ReadToEndAsync()
  $stderrTask = $process.StandardError.ReadToEndAsync()
  $process.WaitForExit()
  $stdoutTask.Wait()
  $stderrTask.Wait()

  $stdoutPath = Join-Path $LogDirectory "$LogPrefix.stdout.log"
  $stderrPath = Join-Path $LogDirectory "$LogPrefix.stderr.log"
  [IO.File]::WriteAllText($stdoutPath, $stdoutTask.Result, $utf8NoBom)
  [IO.File]::WriteAllText($stderrPath, $stderrTask.Result, $utf8NoBom)
  if ($process.ExitCode -ne 0) {
    throw "Mobile build step '$LogPrefix' failed with exit code $($process.ExitCode). See its timestamped stdout/stderr logs."
  }
}

function Resolve-DistDirectory {
  param(
    [Parameter(Mandatory)][string]$ArchivedMobileRoot,
    [Parameter(Mandatory)][string[]]$Candidates
  )
  foreach ($relative in $Candidates) {
    $candidate = Join-Path $ArchivedMobileRoot $relative
    if (Test-Path -LiteralPath $candidate -PathType Container) {
      return [IO.Path]::GetFullPath($candidate)
    }
  }
  throw ('Build completed but no output directory was found: ' + ($Candidates -join ', '))
}

function Assert-NoInvalidApiMarker {
  param([Parameter(Mandatory)][string]$Directory)
  foreach ($file in Get-ChildItem -LiteralPath $Directory -Recurse -File) {
    if ($file.Length -gt 67108864) { continue }
    $content = [IO.File]::ReadAllText($file.FullName)
    if ($content.IndexOf('mobile-api.invalid', [StringComparison]::OrdinalIgnoreCase) -ge 0) {
      throw 'Candidate output contains the prohibited mobile-api.invalid marker; artifact is rejected.'
    }
  }
}

function Write-JsonFile {
  param(
    [Parameter(Mandatory)]$Value,
    [Parameter(Mandatory)][string]$Path
  )
  [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 8), $utf8NoBom)
}

$commit = Assert-RepositoryCandidateSource
$runtime = Assert-Node20
$requestedTargets = Get-RequestedTargets
$endpointByTarget = @{}
foreach ($name in $requestedTargets) {
  $endpointByTarget[$name] = Get-TargetApiEndpoint -Name $name
}

$runId = ('{0}-{1}-{2}' -f (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssfffZ'), $commit.Substring(0, 12), [Guid]::NewGuid().ToString('N').Substring(0, 8))
$runRoot = Join-Path $evidenceRoot $runId
$artifactRoot = Join-Path $runRoot 'artifacts'
$logRoot = Join-Path $runRoot 'build-logs'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ($tempPrefix + [Guid]::NewGuid().ToString('N'))

New-Item -ItemType Directory -Force -Path $artifactRoot, $logRoot, $temporaryRoot | Out-Null
try {
  $archivePath = Join-Path $temporaryRoot 'source.zip'
  $archiveRoot = Join-Path $temporaryRoot 'source'
  & git -C $repoRoot archive --format=zip "--output=$archivePath" $commit
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $archivePath)) {
    throw 'Failed to create immutable Git archive; mobile candidate build is refused.'
  }
  Expand-Archive -LiteralPath $archivePath -DestinationPath $archiveRoot -Force
  $archivedMobileRoot = Join-Path $archiveRoot 'mobile-uniapp'
  if (-not (Test-Path -LiteralPath $archivedMobileRoot -PathType Container)) {
    throw 'Immutable source archive does not contain mobile-uniapp; mobile candidate build is refused.'
  }

  $package = Get-Content -LiteralPath (Join-Path $archivedMobileRoot 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
  if ([string]::IsNullOrWhiteSpace($package.version)) {
    throw 'mobile-uniapp/package.json does not declare a version.'
  }
  $lockfilePath = Join-Path $archivedMobileRoot 'package-lock.json'
  $lockfileHash = (Get-FileHash -LiteralPath $lockfilePath -Algorithm SHA256).Hash.ToLowerInvariant()

  $processSandbox = Join-Path $temporaryRoot 'build-process'
  Invoke-WhitelistedNpm -WorkingDirectory $archivedMobileRoot -NpmArguments @('ci', '--no-audit', '--fund=false') -LogPrefix 'npm-ci' -LogDirectory $logRoot -ProcessSandbox $processSandbox
  Invoke-WhitelistedNpm -WorkingDirectory $archivedMobileRoot -NpmArguments @('run', 'type-check') -LogPrefix 'type-check' -LogDirectory $logRoot -ProcessSandbox $processSandbox

  $targetDefinitions = @{
    'h5' = [pscustomobject]@{ npm = 'build:h5'; output = @('dist/candidate/h5', 'dist/build/h5'); artifactType = 'h5-static'; signed = $false }
    'mp-weixin' = [pscustomobject]@{ npm = 'build:mp-weixin:candidate'; output = @('dist/candidate/mp-weixin'); artifactType = 'weixin-source-unsubmitted'; signed = $false }
    'android' = [pscustomobject]@{ npm = 'build:app:android'; output = @('dist/candidate/android', 'dist/build/app-plus', 'dist/build/app'); artifactType = 'app-resources-unsigned'; signed = $false }
    'ios' = [pscustomobject]@{ npm = 'build:app:ios'; output = @('dist/candidate/ios', 'dist/build/app-plus', 'dist/build/app'); artifactType = 'app-resources-unsigned'; signed = $false }
  }

  $results = @()
  foreach ($name in $requestedTargets) {
    $definition = $targetDefinitions[$name]
    $endpoint = $endpointByTarget[$name]
    if ($name -eq 'mp-weixin') {
      # The AppID is injected only into this immutable temporary build source. It is never
      # serialized to Git, candidate manifests, stdout, stderr, or SHA evidence.
      Set-MpWeixinAppIdInTemporarySource -ArchivedMobileRoot $archivedMobileRoot -AppId (Get-MpWeixinAppId)
    }
    Invoke-WhitelistedNpm -WorkingDirectory $archivedMobileRoot -NpmArguments @('run', $definition.npm) -LogPrefix ("build-$name") -LogDirectory $logRoot -ProcessSandbox $processSandbox -ApiBaseUrl $endpoint.raw

    $distDirectory = Resolve-DistDirectory -ArchivedMobileRoot $archivedMobileRoot -Candidates $definition.output
    Assert-NoInvalidApiMarker -Directory $distDirectory
    $artifactName = ('ai-profit-mobile-{0}-{1}.zip' -f $package.version, $name)
    $artifactPath = Join-Path $artifactRoot $artifactName
    Compress-Archive -Path (Join-Path $distDirectory '*') -DestinationPath $artifactPath -CompressionLevel Optimal
    $artifactHash = (Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $nativeTarget = $name -in @('android', 'ios')
    $releaseStatus = if ($nativeTarget) {
      'NOT_RC_UNSIGNED_APP_RESOURCES'
    } else {
      'CANDIDATE_BUILT_PENDING_RELEASE_GATES'
    }
    $targetManifest = [ordered]@{
      schemaVersion = 1
      runId = $runId
      target = $name
      packageVersion = $package.version
      sourceCommit = $commit
      lockfileSha256 = $lockfileHash
      builtAt = (Get-Date).ToUniversalTime().ToString('o')
      buildEnvironment = [ordered]@{ node = $runtime.node; npm = $runtime.npm; platform = 'windows' }
      apiBaseHost = $endpoint.host
      apiBaseSha256 = $endpoint.sha256
      artifact = $artifactName
      artifactType = $definition.artifactType
      artifactSha256 = $artifactHash
      signed = [bool]$definition.signed
      releaseStatus = $releaseStatus
      published = $false
    }
    $manifestPath = Join-Path $artifactRoot (('ai-profit-mobile-{0}-{1}.manifest.json' -f $package.version, $name))
    Write-JsonFile -Value $targetManifest -Path $manifestPath
    $results += [pscustomobject]@{
      target = $name
      artifact = $artifactName
      sha256 = $artifactHash
      apiBaseHost = $endpoint.host
      apiBaseSha256 = $endpoint.sha256
      signed = [bool]$definition.signed
    }
  }

  $shaPath = Join-Path $artifactRoot 'SHA256SUMS.txt'
  $shaLines = $results | Sort-Object target | ForEach-Object { "$($_.sha256)  $($_.artifact)" }
  [IO.File]::WriteAllLines($shaPath, [string[]]$shaLines, [Text.Encoding]::ASCII)
  $summary = [ordered]@{
    schemaVersion = 1
    runId = $runId
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    sourceCommit = $commit
    packageVersion = $package.version
    lockfileSha256 = $lockfileHash
    buildEnvironment = [ordered]@{ node = $runtime.node; npm = $runtime.npm; platform = 'windows' }
    targets = @($results)
    rcEligible = $false
    releaseStatus = 'NOT_RC'
    p0Blockers = @(
      'Android and iOS outputs are unsigned UniApp resource candidates, not signed APK or IPA artifacts.',
      'Real-device, WeChat review/update, backend candidate, Flyway, authentication, authorization, network resilience and monitoring gates must pass before any RC declaration.'
    )
    notes = @(
      'Each target used its own environment-provided staging API base URL. Only host and URL hash are recorded here.',
      'The build ran from an immutable Git archive after a clean, fully tracked source gate.',
      'No store upload, signing, publication, database operation, or credential serialization was performed.'
    )
  }
  Write-JsonFile -Value $summary -Path (Join-Path $runRoot 'candidate-build-summary.json')
  $results | Format-Table -AutoSize
  Write-Host "Mobile candidate evidence: $runRoot"
}
finally {
  if (Test-Path -LiteralPath $temporaryRoot) {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
  }
}
