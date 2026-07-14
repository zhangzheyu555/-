[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'DisableLegacy', 'RollbackPlan')]
    [string]$Mode = 'Preflight',
    [string]$ValidationReport,
    [string]$ValidationReportSha256,
    [string]$ReleaseEvidenceReport,
    [string]$ReleaseEvidenceReportSha256,
    [string]$CutoverReceipt,
    [string]$CutoverReceiptSha256,
    [string]$BackupManifest,
    [string]$BackupManifestSha256,
    [string]$RawSqlPath,
    [string]$RawSqlSha256,
    [string]$TransformManifestPath,
    [string]$TransformManifestSha256,
    [string]$ApprovalManifest,
    [string]$ApprovedSignerThumbprint,
    [string]$ReceiptOutputRoot,
    [string]$PlanOutputPath,
    [switch]$Execute
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$legacyServiceName = 'MySQL'
$targetServiceName = 'MySQL80Test'
$trustedSignerPolicyPath = Join-Path $env:ProgramData 'AI-Profit-OS\release-trust\trusted-signers.psd1'
$TargetDatabase = 'store_profit_mysql8_final'

function Get-NormalizedSha256 {
    param([Parameter(Mandatory)][string]$Value)
    $normalized = ($Value -replace '\s', '').ToUpperInvariant()
    if ($normalized -notmatch '^[0-9A-F]{64}$') { throw 'A required SHA-256 value is malformed.' }
    $normalized
}

function Assert-OutsideWorkspaceFile {
    param([Parameter(Mandatory)][string]$Path, [string]$Label = 'Evidence file')
    if (-not [IO.Path]::IsPathRooted($Path)) { throw "$Label must use an absolute path." }
    $full = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "$Label does not exist." }
    if ($full.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "$Label must be outside the Git workspace." }
    $full
}

function Assert-OutsideWorkspaceDirectory {
    param([Parameter(Mandatory)][string]$Path, [string]$Label = 'Directory')
    if (-not [IO.Path]::IsPathRooted($Path)) { throw "$Label must use an absolute path." }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (($full + '\').StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "$Label must be outside the Git workspace." }
    $full
}

function Assert-FileHash {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Expected, [string]$Label = 'Evidence file')
    $expectedHash = Get-NormalizedSha256 $Expected
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
    if ($actualHash -ne $expectedHash) { throw "$Label SHA-256 does not match the approved value." }
    $actualHash
}

function Get-ServiceIdentitySha256 {
    param([Parameter(Mandatory)][string]$ServiceName)
    $service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'" -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($service.PathName)) { throw 'Windows service executable identity is missing.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(("{0}|{1}" -f $service.Name, $service.PathName))))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
}

function Get-RequiredProperty {
    param([Parameter(Mandatory)]$InputObject, [Parameter(Mandatory)][string]$Path)
    $value = $InputObject
    foreach ($segment in $Path.Split('.')) {
        if ($null -eq $value) { throw "Required evidence property '$Path' is missing." }
        if ($value -is [Collections.IDictionary]) {
            if (-not $value.Contains($segment)) { throw "Required evidence property '$Path' is missing." }
            $value = $value[$segment]
        }
        else {
            $property = $value.PSObject.Properties[$segment]
            if ($null -eq $property) { throw "Required evidence property '$Path' is missing." }
            $value = $property.Value
        }
    }
    $value
}

function Assert-TrueProperty {
    param([Parameter(Mandatory)]$InputObject, [Parameter(Mandatory)][string]$Path)
    if ((Get-RequiredProperty $InputObject $Path) -ne $true) { throw "Evidence gate '$Path' is not true." }
}

function Assert-PassProperty {
    param([Parameter(Mandatory)]$InputObject, [Parameter(Mandatory)][string]$Path)
    if ([string](Get-RequiredProperty $InputObject $Path) -cne 'PASS') { throw "Evidence gate '$Path' is not PASS." }
}

function Assert-SignedApprovalManifest {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$SignerThumbprint,
        [Parameter(Mandatory)][string]$ValidationHash,
        [Parameter(Mandatory)][string]$ReleaseHash,
        [Parameter(Mandatory)][string]$CutoverHash,
        [Parameter(Mandatory)][string]$BackupHash,
        [Parameter(Mandatory)][string]$RawSqlHash,
        [Parameter(Mandatory)][string]$TransformHash,
        [Parameter(Mandatory)][string]$JarHash,
        [Parameter(Mandatory)][string]$V37Hash,
        [Parameter(Mandatory)][int]$V37Checksum,
        [Parameter(Mandatory)][string]$FinalEnvironment,
        [Parameter(Mandatory)][DateTimeOffset]$CutoverAt
    )
    $manifestPath = Assert-OutsideWorkspaceFile -Path $Path -Label 'Signed DisableLegacy approval manifest'
    if ([IO.Path]::GetExtension($manifestPath) -ine '.psd1') { throw 'The signed approval manifest must be a .psd1 PowerShell data file.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $manifestPath
    if ($signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or -not $signature.SignerCertificate) { throw 'The DisableLegacy approval Authenticode signature is not valid.' }
    $expectedThumbprint = ($SignerThumbprint -replace '\s', '').ToUpperInvariant()
    if ($expectedThumbprint -notmatch '^[0-9A-F]{40,64}$' -or $signature.SignerCertificate.Thumbprint.ToUpperInvariant() -ne $expectedThumbprint) { throw 'The DisableLegacy signer does not match the explicitly approved certificate thumbprint.' }
    $trustedThumbprints = Get-TrustedSignerThumbprints
    if ($trustedThumbprints -notcontains $expectedThumbprint) { throw 'The DisableLegacy signer is not present in the protected machine release-trust policy.' }
    $codeSigningEku = @($signature.SignerCertificate.Extensions | Where-Object { $_ -is [Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension] } | ForEach-Object { $_.EnhancedKeyUsages } | Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.3' })
    if ($codeSigningEku.Count -eq 0) { throw 'The DisableLegacy signer certificate is not authorized for Code Signing.' }
    $manifest = Import-PowerShellDataFile -LiteralPath $manifestPath
    if ([int](Get-RequiredProperty $manifest 'SchemaVersion') -ne 1 -or [string](Get-RequiredProperty $manifest 'Status') -cne 'APPROVED') { throw 'DisableLegacy approval manifest is not an approved schema version 1 document.' }
    if (@(Get-RequiredProperty $manifest 'Operations') -notcontains 'DisableLegacy') { throw 'Approval manifest does not authorize DisableLegacy.' }
    if ([string](Get-RequiredProperty $manifest 'TargetDatabase') -cne $TargetDatabase) { throw 'DisableLegacy approval target database differs from the cutover target.' }
    if ($FinalEnvironment -cne 'STAGING' -or [string](Get-RequiredProperty $manifest 'FinalEnvironment') -cne 'STAGING') { throw 'DisableLegacy requires a signed STAGING final environment.' }
    $bindings = @{
        ValidationReportSha256 = $ValidationHash
        ReleaseEvidenceSha256 = $ReleaseHash
        CutoverReceiptSha256 = $CutoverHash
        BackupManifestSha256 = $BackupHash
        RawSqlSha256 = $RawSqlHash
        TransformManifestSha256 = $TransformHash
        JarSha256 = $JarHash
        V37Sha256 = $V37Hash
    }
    foreach ($binding in $bindings.GetEnumerator()) {
        if ((Get-NormalizedSha256 (Get-RequiredProperty $manifest $binding.Key)) -ne $binding.Value) { throw "DisableLegacy approval does not bind $($binding.Key)." }
    }
    if ([int](Get-RequiredProperty $manifest 'V37Checksum') -ne $V37Checksum) { throw 'DisableLegacy approval does not bind the successful V37 checksum.' }
    $approvalId = [string](Get-RequiredProperty $manifest 'ApprovalId')
    $approver = [string](Get-RequiredProperty $manifest 'Approver')
    if ($approvalId -notmatch '^[A-Za-z0-9._-]{6,128}$' -or [string]::IsNullOrWhiteSpace($approver)) { throw 'DisableLegacy approval identity is incomplete.' }
    $approvedAt = [DateTimeOffset]::Parse([string](Get-RequiredProperty $manifest 'ApprovedAt'), [Globalization.CultureInfo]::InvariantCulture)
    $expiresAt = [DateTimeOffset]::Parse([string](Get-RequiredProperty $manifest 'ExpiresAt'), [Globalization.CultureInfo]::InvariantCulture)
    $retentionUntil = [DateTimeOffset]::Parse([string](Get-RequiredProperty $manifest 'BackupRetentionUntil'), [Globalization.CultureInfo]::InvariantCulture)
    $now = [DateTimeOffset]::Now
    if ($approvedAt -gt $now.AddMinutes(5) -or $expiresAt -le $now -or $expiresAt -le $approvedAt) { throw 'DisableLegacy approval is not currently valid.' }
    if ($retentionUntil -lt $CutoverAt.AddDays(30) -or $retentionUntil -le $now) { throw 'The signed backup retention commitment is shorter than 30 days after cutover.' }
    $backupRoot = [string](Get-RequiredProperty $manifest 'BackupRoot')
    $backupFileCount = [int](Get-RequiredProperty $manifest 'BackupFileCount')
    $backupTotalBytes = [int64](Get-RequiredProperty $manifest 'BackupTotalBytes')
    if ($backupFileCount -le 0 -or $backupTotalBytes -le 0) { throw 'Signed offline backup inventory is empty.' }
    [pscustomobject]@{ Path = $manifestPath; ApprovalId = $approvalId; Approver = $approver; ExpiresAt = $expiresAt; RetentionUntil = $retentionUntil; SignerThumbprint = $expectedThumbprint; BackupRoot = $backupRoot; BackupFileCount = $backupFileCount; BackupTotalBytes = $backupTotalBytes }
}

function Get-TrustedSignerThumbprints {
    if (-not (Test-Path -LiteralPath $trustedSignerPolicyPath -PathType Leaf)) { throw 'Protected release-trust policy is missing; DisableLegacy remains blocked.' }
    $item = Get-Item -LiteralPath $trustedSignerPolicyPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.Extension -ine '.psd1') { throw 'Protected release-trust policy is not a regular .psd1 file.' }
    $acl = Get-Acl -LiteralPath $trustedSignerPolicyPath
    $ownerSid = $acl.Owner
    try { $ownerSid = ([Security.Principal.NTAccount]$acl.Owner).Translate([Security.Principal.SecurityIdentifier]).Value } catch {}
    if ($ownerSid -notin @('S-1-5-18', 'S-1-5-32-544')) { throw 'Protected release-trust policy must be owned by SYSTEM or Administrators.' }
    $broadSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
    $writeRights = [Security.AccessControl.FileSystemRights]'Write, Modify, FullControl, Delete, ChangePermissions, TakeOwnership'
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        try { $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { throw 'A release-trust policy ACL identity could not be resolved safely.' }
        if ($sid -in $broadSids -and (($rule.FileSystemRights -band $writeRights) -ne 0)) { throw 'Protected release-trust policy grants broad write access.' }
    }
    $policy = Import-PowerShellDataFile -LiteralPath $trustedSignerPolicyPath
    if ([int](Get-RequiredProperty $policy 'SchemaVersion') -ne 1) { throw 'Unsupported release-trust policy schema.' }
    $thumbprints = @((Get-RequiredProperty $policy 'TrustedCodeSigningThumbprints') | ForEach-Object { ($_ -replace '\s', '').ToUpperInvariant() })
    if ($thumbprints.Count -eq 0 -or @($thumbprints | Where-Object { $_ -notmatch '^[0-9A-F]{40,64}$' }).Count -ne 0) { throw 'Protected release-trust policy has no valid signer thumbprints.' }
    $thumbprints
}

function Get-ListenerOwners {
    param([Parameter(Mandatory)][int]$Port)
    @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -in @('127.0.0.1', '0.0.0.0', '::', '::1') } | Select-Object -ExpandProperty OwningProcess -Unique)
}

function Test-ProcessDescendsFrom {
    param([Parameter(Mandatory)][int]$ProcessId, [Parameter(Mandatory)][int]$AncestorId)
    $seen = [Collections.Generic.HashSet[int]]::new()
    $current = $ProcessId
    while ($current -gt 0 -and $seen.Add($current)) {
        if ($current -eq $AncestorId) { return $true }
        $item = Get-CimInstance Win32_Process -Filter "ProcessId=$current" -ErrorAction SilentlyContinue
        if (-not $item) { return $false }
        $current = [int]$item.ParentProcessId
    }
    $false
}

function Get-HealthData {
    param([Parameter(Mandatory)][int]$Port)
    $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 15
    if ([int]$response.StatusCode -ne 200) { throw "Health endpoint on $Port did not return HTTP 200." }
    $json = $response.Content | ConvertFrom-Json
    $data = Get-RequiredProperty $json 'data'
    if ([string](Get-RequiredProperty $data 'status') -cne 'UP') { throw "Health endpoint on $Port is not UP." }
    $data
}

function Assert-HealthDatabaseIdentity {
    param([Parameter(Mandatory)]$HealthData, [Parameter(Mandatory)][string]$Label)
    if ([string](Get-RequiredProperty $HealthData 'databaseVersion') -cne '8.0.46' -or
        [int](Get-RequiredProperty $HealthData 'databasePort') -ne 3307 -or
        [string](Get-RequiredProperty $HealthData 'databaseName') -cne $TargetDatabase -or
        [string](Get-RequiredProperty $HealthData 'databaseAccountScope') -cne 'LOCAL_SCOPED') {
        throw "$Label does not report the approved local MySQL 8.0.46 identity."
    }
}

function Assert-LiveFinalRuntime {
    param([Parameter(Mandatory)]$Receipt)
    if ([string](Get-RequiredProperty $Receipt 'target.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $Receipt 'target.port') -ne 3307 -or [string](Get-RequiredProperty $Receipt 'target.database') -cne $TargetDatabase) { throw 'The cutover receipt does not identify the only approved final database.' }
    if ([int](Get-RequiredProperty $Receipt 'backend.port') -ne 18081 -or [string](Get-RequiredProperty $Receipt 'backend.environment') -cne 'STAGING') { throw 'The cutover receipt does not identify the STAGING final backend environment.' }
    $legacy = Get-CimInstance Win32_Service -Filter "Name='$legacyServiceName'" -ErrorAction Stop
    if ($legacy.State -ne 'Stopped') { throw 'Legacy MySQL service is not stopped.' }
    if ([string](Get-RequiredProperty $Receipt 'legacy.serviceName') -cne $legacyServiceName -or (Get-NormalizedSha256 (Get-RequiredProperty $Receipt 'legacy.serviceImagePathSha256')) -ne (Get-ServiceIdentitySha256 -ServiceName $legacyServiceName)) { throw 'The legacy service executable identity differs from the cutover receipt.' }
    $active3306 = @(Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { ($_.LocalPort -eq 3306 -or $_.RemotePort -eq 3306) -and $_.State -notin @('Closed','TimeWait','DeleteTcb') })
    if ($active3306.Count -ne 0) { throw 'An active TCP endpoint still references port 3306.' }

    $target = Get-CimInstance Win32_Service -Filter "Name='$targetServiceName'" -ErrorAction Stop
    if ($target.State -ne 'Running' -or [int]$target.ProcessId -le 0) { throw 'MySQL80Test is not running.' }
    $targetOwners = Get-ListenerOwners -Port 3307
    if ($targetOwners.Count -ne 1 -or -not (Test-ProcessDescendsFrom -ProcessId ([int]$targetOwners[0]) -AncestorId ([int]$target.ProcessId))) { throw 'The 3307 listener is not owned by MySQL80Test or its child process.' }

    $backendPid = [int](Get-RequiredProperty $Receipt 'backend.processId')
    $backendOwners = Get-ListenerOwners -Port 18081
    if ($backendOwners.Count -ne 1 -or [int]$backendOwners[0] -ne $backendPid) { throw 'The live 18081 backend differs from the cutover receipt.' }
    $backendProcess = Get-Process -Id $backendPid -ErrorAction Stop
    if ($backendProcess.ProcessName -notmatch '^java') { throw 'The final 18081 listener is not Java.' }
    $approvedJava = Assert-OutsideWorkspaceFile -Path ([string](Get-RequiredProperty $Receipt 'backend.javaPath')) -Label 'Final backend Java executable'
    [void](Assert-FileHash -Path $approvedJava -Expected ([string](Get-RequiredProperty $Receipt 'backend.javaSha256')) -Label 'Final backend Java executable')
    $backendJar = Assert-OutsideWorkspaceFile -Path ([string](Get-RequiredProperty $Receipt 'backend.jarPath')) -Label 'Final backend restricted Jar'
    $backendJarHash = Assert-FileHash -Path $backendJar -Expected ([string](Get-RequiredProperty $Receipt 'backend.jarSha256')) -Label 'Final backend restricted Jar'
    if ($backendJarHash -ne (Get-NormalizedSha256 (Get-RequiredProperty $Receipt 'jarSha256'))) { throw 'Final backend Jar identity differs inside the cutover receipt.' }
    $runtime = Get-CimInstance Win32_Process -Filter "ProcessId=$backendPid" -ErrorAction Stop
    if ([IO.Path]::GetFullPath($runtime.ExecutablePath) -ine $approvedJava) { throw 'The final 18081 Java executable differs from the cutover receipt.' }
    if ([string]::IsNullOrWhiteSpace($runtime.CommandLine) -or $runtime.CommandLine.IndexOf($backendJar, [StringComparison]::OrdinalIgnoreCase) -lt 0) { throw 'The final 18081 process command does not reference the restricted approved Jar.' }
    if ((Get-Item -LiteralPath $backendJar).LastWriteTimeUtc -gt $backendProcess.StartTime.ToUniversalTime().AddSeconds(1)) { throw 'The final backend Jar was modified after the 18081 process started.' }
    $health = Get-HealthData -Port 18081
    Assert-HealthDatabaseIdentity -HealthData $health -Label 'The live 18081 backend'
    if ([string](Get-RequiredProperty $health 'environment') -cne [string](Get-RequiredProperty $Receipt 'backend.environment') -or [string](Get-RequiredProperty $health 'databaseMigrationVersion') -cne '37' -or [string](Get-RequiredProperty $health 'sourceVersion') -cne [string](Get-RequiredProperty $Receipt 'backend.sourceVersion')) { throw 'The live 18081 identity differs from the cutover receipt.' }
    $frontendHealth = Get-HealthData -Port 5173
    Assert-HealthDatabaseIdentity -HealthData $frontendHealth -Label 'The 5173 proxied backend'
    if ([string](Get-RequiredProperty $frontendHealth 'environment') -cne [string](Get-RequiredProperty $Receipt 'backend.environment') -or [string](Get-RequiredProperty $frontendHealth 'databaseMigrationVersion') -cne '37') { throw 'The 5173 frontend does not proxy to the final V37 backend.' }
}

function Assert-OfflineBackupManifest {
    param(
        [Parameter(Mandatory)][string]$ManifestPath,
        [Parameter(Mandatory)][string]$BackupRoot,
        [Parameter(Mandatory)][int]$ExpectedFileCount,
        [Parameter(Mandatory)][int64]$ExpectedTotalBytes
    )
    $root = Assert-OutsideWorkspaceDirectory -Path $BackupRoot -Label 'Pristine MySQL 5.5 backup root'
    if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw 'Pristine MySQL 5.5 backup root does not exist.' }
    $rootItem = Get-Item -LiteralPath $root -Force
    if (($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Pristine backup root must not be a reparse point.' }
    $manifest = @(Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json)
    if ($manifest.Count -ne $ExpectedFileCount) { throw 'Offline backup manifest file count differs from the signed approval.' }
    $rootPrefix = $root.TrimEnd('\') + '\'
    $totalBytes = 0L
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $manifest) {
        $relative = [string](Get-RequiredProperty $entry 'RelativePath')
        if ([string]::IsNullOrWhiteSpace($relative) -or [IO.Path]::IsPathRooted($relative) -or @($relative -split '[\\/]' | Where-Object { $_ -eq '..' }).Count -gt 0) { throw 'Offline backup manifest contains an unsafe relative path.' }
        $full = [IO.Path]::GetFullPath((Join-Path $root $relative))
        if (-not $full.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase) -or -not $seen.Add($full)) { throw 'Offline backup manifest contains a duplicate or escaping path.' }
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw 'A file listed by the offline backup manifest is missing.' }
        $file = Get-Item -LiteralPath $full -Force
        if (($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Offline backup manifest resolves through a reparse point.' }
        $expectedSize = [int64](Get-RequiredProperty $entry 'Size')
        if ($file.Length -ne $expectedSize -or (Get-FileHash -Algorithm SHA256 -LiteralPath $full).Hash.ToUpperInvariant() -ne (Get-NormalizedSha256 (Get-RequiredProperty $entry 'Sha256')) -or (Get-RequiredProperty $entry 'SourceMatch') -ne $true) { throw 'An offline backup file differs from its SHA-256 manifest.' }
        $totalBytes += $file.Length
    }
    $actualEntries = @(Get-ChildItem -LiteralPath $root -Recurse -Force)
    if (@($actualEntries | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -ne 0) { throw 'Pristine backup tree contains a reparse point.' }
    if (@($actualEntries | Where-Object { -not $_.PSIsContainer }).Count -ne $ExpectedFileCount -or $totalBytes -ne $ExpectedTotalBytes) { throw 'Pristine backup tree count or total bytes differs from the signed inventory.' }
    $root
}

function Assert-LogicalBackupArtifacts {
    param(
        [Parameter(Mandatory)][string]$RawPath,
        [Parameter(Mandatory)][string]$RawHash,
        [Parameter(Mandatory)][string]$TransformPath,
        [Parameter(Mandatory)][string]$TransformHash
    )
    $raw = Assert-OutsideWorkspaceFile -Path $RawPath -Label 'Raw MySQL 5.5 logical dump'
    [void](Assert-FileHash -Path $raw -Expected $RawHash -Label 'Raw MySQL 5.5 logical dump')
    if (((Get-Item -LiteralPath $raw).Attributes -band [IO.FileAttributes]::ReadOnly) -eq 0) { throw 'Raw MySQL 5.5 logical dump is not read-only.' }
    $transformFile = Assert-OutsideWorkspaceFile -Path $TransformPath -Label 'SQL compatibility transform manifest'
    [void](Assert-FileHash -Path $transformFile -Expected $TransformHash -Label 'SQL compatibility transform manifest')
    $transform = Get-Content -LiteralPath $transformFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifestRaw = [IO.Path]::GetFullPath([string](Get-RequiredProperty $transform 'RawPath'))
    if ($manifestRaw -ine $raw -or (Get-NormalizedSha256 (Get-RequiredProperty $transform 'RawSha256')) -ne $RawHash -or (Get-RequiredProperty $transform 'RawModified') -ne $false) { throw 'Transform manifest does not bind the immutable raw SQL dump.' }
    if ([string](Get-RequiredProperty $transform 'AffectedTable') -cne 'platform_webhook_event' -or [string](Get-RequiredProperty $transform 'AffectedColumn') -cne 'last_received_at' -or [int](Get-RequiredProperty $transform 'ReplacementCount') -ne 1 -or [int](Get-RequiredProperty $transform 'AffectedDataRows') -ne 0) { throw 'Transform manifest contains an unapproved compatibility conversion.' }
    $compatible = Assert-OutsideWorkspaceFile -Path ([string](Get-RequiredProperty $transform 'CompatiblePath')) -Label 'MySQL 8 compatible SQL copy'
    [void](Assert-FileHash -Path $compatible -Expected ([string](Get-RequiredProperty $transform 'CompatibleSha256')) -Label 'MySQL 8 compatible SQL copy')
    if (((Get-Item -LiteralPath $compatible).Attributes -band [IO.FileAttributes]::ReadOnly) -eq 0) { throw 'MySQL 8 compatible SQL copy is not read-only.' }
}

function Initialize-RestrictedDirectory {
    param([Parameter(Mandatory)][string]$Path)
    $full = Assert-OutsideWorkspaceDirectory -Path $Path -Label 'DisableLegacy receipt directory'
    if (Test-Path -LiteralPath $full) { throw 'DisableLegacy receipt directory must be a new dedicated directory.' }
    [void](New-Item -ItemType Directory -Path $full)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit', [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
    [void]$acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $full -AclObject $acl
    $full
}

function Write-DisableReceipt {
    param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)]$Receipt, [string]$FileName = 'mysql55-disable-receipt.json')
    if ($FileName -notmatch '^[A-Za-z0-9._-]+\.json$') { throw 'DisableLegacy receipt file name is unsafe.' }
    $path = Join-Path $Directory $FileName
    $Receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding UTF8
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToUpperInvariant()
    [IO.File]::WriteAllText("$path.sha256", $hash + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{ Path = $path; Sha256 = $hash }
}

function Write-RollbackPlan {
    $plan = @"
# Legacy MySQL disable rollback plan

- This plan does not execute any command and never enables or starts MySQL 5.5.
- If the 3307 final backend has not accepted a business write, stop only its recorded PID and investigate under a new signed approval.
- If 3307 has accepted any business write, do not point the backend to 3306. Freeze writes, export 3307 logically, reconcile the delta, and approve a separate recovery migration.
- Never copy a MySQL 8 data directory into MySQL 5.5.
- Keep the original datadir, pristine offline recovery copy, raw SQL dump, transform manifest, validation report, and SHA-256 evidence through the signed retention date.
- Any request to re-enable the legacy service is a separate emergency change and is not automated here.
"@
    if ($PlanOutputPath) {
        $path = [IO.Path]::GetFullPath($PlanOutputPath)
        $directory = Assert-OutsideWorkspaceDirectory -Path (Split-Path -Parent $path) -Label 'Rollback plan directory'
        if (Test-Path -LiteralPath $directory) { throw 'RollbackPlan requires a new dedicated output directory and will not change an existing parent ACL.' }
        [void](Initialize-RestrictedDirectory -Path $directory)
        [IO.File]::WriteAllText($path, $plan, [Text.UTF8Encoding]::new($false))
        Write-Host "Rollback plan written outside the workspace: $path"
    }
    else { Write-Output $plan }
}

if ($Mode -eq 'RollbackPlan') { Write-RollbackPlan; exit 0 }

foreach ($required in @{
    ValidationReport = $ValidationReport
    ValidationReportSha256 = $ValidationReportSha256
    ReleaseEvidenceReport = $ReleaseEvidenceReport
    ReleaseEvidenceReportSha256 = $ReleaseEvidenceReportSha256
    CutoverReceipt = $CutoverReceipt
    CutoverReceiptSha256 = $CutoverReceiptSha256
    BackupManifest = $BackupManifest
    BackupManifestSha256 = $BackupManifestSha256
    RawSqlPath = $RawSqlPath
    RawSqlSha256 = $RawSqlSha256
    TransformManifestPath = $TransformManifestPath
    TransformManifestSha256 = $TransformManifestSha256
    ApprovalManifest = $ApprovalManifest
    ApprovedSignerThumbprint = $ApprovedSignerThumbprint
}.GetEnumerator()) { if ([string]::IsNullOrWhiteSpace([string]$required.Value)) { throw "Missing required parameter: $($required.Key)." } }

$validationPath = Assert-OutsideWorkspaceFile -Path $ValidationReport -Label 'Validation report'
$releasePath = Assert-OutsideWorkspaceFile -Path $ReleaseEvidenceReport -Label 'Release evidence report'
$cutoverPath = Assert-OutsideWorkspaceFile -Path $CutoverReceipt -Label 'Cutover receipt'
$backupPath = Assert-OutsideWorkspaceFile -Path $BackupManifest -Label 'Offline backup SHA-256 manifest'
$rawSql = Assert-OutsideWorkspaceFile -Path $RawSqlPath -Label 'Raw MySQL 5.5 logical dump'
$transformManifest = Assert-OutsideWorkspaceFile -Path $TransformManifestPath -Label 'SQL compatibility transform manifest'
$validationHash = Assert-FileHash -Path $validationPath -Expected $ValidationReportSha256 -Label 'Validation report'
$releaseHash = Assert-FileHash -Path $releasePath -Expected $ReleaseEvidenceReportSha256 -Label 'Release evidence report'
$cutoverHash = Assert-FileHash -Path $cutoverPath -Expected $CutoverReceiptSha256 -Label 'Cutover receipt'
$backupHash = Assert-FileHash -Path $backupPath -Expected $BackupManifestSha256 -Label 'Offline backup SHA-256 manifest'
$rawSqlHash = Assert-FileHash -Path $rawSql -Expected $RawSqlSha256 -Label 'Raw MySQL 5.5 logical dump'
$transformHash = Assert-FileHash -Path $transformManifest -Expected $TransformManifestSha256 -Label 'SQL compatibility transform manifest'
if ((Get-Item -LiteralPath $backupPath).Length -le 2) { throw 'Offline backup SHA-256 manifest is empty.' }

$validation = Get-Content -LiteralPath $validationPath -Raw -Encoding UTF8 | ConvertFrom-Json
$release = Get-Content -LiteralPath $releasePath -Raw -Encoding UTF8 | ConvertFrom-Json
$cutover = Get-Content -LiteralPath $cutoverPath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-PassProperty $validation 'status'
if (@(Get-RequiredProperty $validation 'blockers').Count -ne 0 -or @(Get-RequiredProperty $validation 'tableCountMismatches').Count -ne 0) { throw 'Validation report contains blockers or table mismatches.' }
if ([int](Get-RequiredProperty $validation 'source.port') -ne 3309) { throw 'Validation source is not the isolated 3309 recovery instance.' }
foreach ($gate in @('flywayMatches','criticalAggregatesMatch','passwordHashesMatch','indexDefinitionsMatch','columnDefinitionsMatch','foreignKeyDefinitionsMatch','programmableObjectDefinitionsMatch','binaryAttachmentsMatch')) { Assert-TrueProperty $validation $gate }
if ([string](Get-RequiredProperty $validation 'target.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $validation 'target.port') -ne 3307 -or [string](Get-RequiredProperty $validation 'target.database') -cne $TargetDatabase -or [string](Get-RequiredProperty $validation 'target.version') -notmatch '^8\.0\.46(?:[-+].*)?$') { throw 'Validation target differs from the only approved MySQL 8.0.46 final database.' }

Assert-PassProperty $release 'status'
if ([string](Get-RequiredProperty $release 'target.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $release 'target.port') -ne 3307 -or [string](Get-RequiredProperty $release 'target.database') -cne $TargetDatabase -or [string](Get-RequiredProperty $release 'target.mysqlVersion') -notmatch '^8\.0\.46(?:[-+].*)?$' -or [string](Get-RequiredProperty $release 'flyway.version') -cne '37' -or [string](Get-RequiredProperty $release 'flyway.script') -notmatch '^V37__') { throw 'Release evidence is not the approved V37 final target.' }
foreach ($gate in @('flyway.success','flyway.validatePass','flyway.repeatedStartPass','target.noFailedFlywayRows','backend18082.healthPass','backend18082.databaseIdentityPass','backend18082.secondStartPass','browser.crossStore403','browser.no404Or500','browser.noJavaScriptErrors','attachments.filesystemPass','attachments.trainingImagesPass','attachments.expensePass','attachments.inspectionPass','attachments.examPass','network.no3306Connection')) { Assert-TrueProperty $release $gate }
foreach ($gate in @('backend18082.status','automatedTests.backend','automatedTests.vueTypeCheck','automatedTests.vueBuild','automatedTests.e2e','browser.status','browser.roles.BOSS','browser.roles.FINANCE','browser.roles.STORE_MANAGER','browser.roles.SUPERVISOR','browser.roles.WAREHOUSE','browser.roles.OPERATIONS','browser.roles.EMPLOYEE','attachments.status')) { Assert-PassProperty $release $gate }
if ([int](Get-RequiredProperty $release 'backend18082.port') -ne 18082 -or [string](Get-RequiredProperty $release 'backend18082.environment') -cne 'STAGING' -or [int](Get-RequiredProperty $release 'backend18082.mysqlPort') -ne 3307 -or [string](Get-RequiredProperty $release 'backend18082.database') -cne $TargetDatabase) { throw 'Release evidence does not identify the approved 18082 STAGING runtime.' }

Assert-PassProperty $cutover 'status'
if ([string](Get-RequiredProperty $cutover 'target.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $cutover 'target.port') -ne 3307 -or [string](Get-RequiredProperty $cutover 'target.database') -cne $TargetDatabase -or [string](Get-RequiredProperty $cutover 'backend.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $cutover 'backend.port') -ne 18081 -or [string](Get-RequiredProperty $cutover 'backend.environment') -cne 'STAGING' -or [string](Get-RequiredProperty $cutover 'backend.health') -cne 'UP' -or [string](Get-RequiredProperty $cutover 'backend.flywayVersion') -cne '37') { throw 'Cutover receipt does not identify the expected STAGING final target.' }
foreach ($gate in @('noDualWrite','frontend.proxyVerified','v37.success','legacy.serviceStopped')) { Assert-TrueProperty $cutover $gate }
if ((Get-RequiredProperty $cutover 'legacy.port3306Listening') -ne $false -or [int](Get-RequiredProperty $cutover 'legacy.active3306ConnectionCount') -ne 0) { throw 'Cutover receipt does not prove that 3306 was unused.' }
if ((Get-NormalizedSha256 (Get-RequiredProperty $cutover 'validationReportSha256')) -ne $validationHash -or (Get-NormalizedSha256 (Get-RequiredProperty $cutover 'releaseEvidenceSha256')) -ne $releaseHash) { throw 'Cutover receipt does not bind the supplied validation and release evidence.' }
if ((Get-NormalizedSha256 (Get-RequiredProperty $cutover 'jarSha256')) -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.jarSha256'))) { throw 'Cutover receipt Jar differs from release evidence.' }
if ((Get-NormalizedSha256 (Get-RequiredProperty $cutover 'backend.javaSha256')) -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.javaSha256')) -or [IO.Path]::GetFullPath([string](Get-RequiredProperty $cutover 'backend.javaPath')) -ine [IO.Path]::GetFullPath([string](Get-RequiredProperty $release 'artifact.javaPath'))) { throw 'Cutover receipt Java runtime differs from release evidence.' }
if ([int](Get-RequiredProperty $cutover 'v37.checksum') -ne [int](Get-RequiredProperty $release 'flyway.checksum') -or (Get-NormalizedSha256 (Get-RequiredProperty $cutover 'v37.sha256')) -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.v37Sha256'))) { throw 'Cutover receipt V37 identity differs from release evidence.' }

$cutoverAt = [DateTimeOffset]::Parse([string](Get-RequiredProperty $cutover 'generatedAt'), [Globalization.CultureInfo]::InvariantCulture)
$approval = Assert-SignedApprovalManifest -Path $ApprovalManifest -SignerThumbprint $ApprovedSignerThumbprint -ValidationHash $validationHash -ReleaseHash $releaseHash -CutoverHash $cutoverHash -BackupHash $backupHash -RawSqlHash $rawSqlHash -TransformHash $transformHash -JarHash (Get-NormalizedSha256 (Get-RequiredProperty $cutover 'jarSha256')) -V37Hash (Get-NormalizedSha256 (Get-RequiredProperty $cutover 'v37.sha256')) -V37Checksum ([int](Get-RequiredProperty $cutover 'v37.checksum')) -FinalEnvironment ([string](Get-RequiredProperty $cutover 'backend.environment')) -CutoverAt $cutoverAt

[void](Assert-OfflineBackupManifest -ManifestPath $backupPath -BackupRoot $approval.BackupRoot -ExpectedFileCount $approval.BackupFileCount -ExpectedTotalBytes $approval.BackupTotalBytes)
Assert-LogicalBackupArtifacts -RawPath $rawSql -RawHash $rawSqlHash -TransformPath $transformManifest -TransformHash $transformHash

Assert-LiveFinalRuntime -Receipt $cutover
$legacy = Get-CimInstance Win32_Service -Filter "Name='$legacyServiceName'" -ErrorAction Stop
Write-Host 'DisableLegacy preflight: PASS' -ForegroundColor Green
Write-Host "Final backend: 127.0.0.1:18081 -> MySQL 127.0.0.1:3307/$TargetDatabase"
Write-Host 'Legacy service state: Stopped'
Write-Host "Legacy service start mode: $($legacy.StartMode)"
Write-Host "Offline backup retention through: $($approval.RetentionUntil.ToString('o'))"
if ($Mode -eq 'Preflight') { exit 0 }

if (-not $Execute) { throw 'DisableLegacy is fail-closed. Re-run with -Mode DisableLegacy -Execute after reviewing the PASS preflight.' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'DisableLegacy requires an elevated PowerShell session.' }

$firstPhrase = 'DISABLE-MYSQL55-3306'
if ((Read-Host "First confirmation: type $firstPhrase") -cne $firstPhrase) { throw 'First DisableLegacy confirmation did not match.' }
if ((Read-Host "Second confirmation: type approval ID $($approval.ApprovalId)") -cne $approval.ApprovalId) { throw 'Second DisableLegacy confirmation did not match.' }

if (-not $ReceiptOutputRoot) { $ReceiptOutputRoot = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql8-cutover\disable-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$receiptDirectory = Initialize-RestrictedDirectory -Path $ReceiptOutputRoot
$prechange = [ordered]@{
    schemaVersion = 1; generatedAt = (Get-Date).ToString('o'); status = 'PREPARED'; operation = 'DisableLegacy'
    approvalId = $approval.ApprovalId; signerThumbprint = $approval.SignerThumbprint
    target = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $TargetDatabase }
    legacy = [ordered]@{ serviceName = $legacyServiceName; serviceImagePathSha256 = (Get-ServiceIdentitySha256 -ServiceName $legacyServiceName); state = 'Stopped'; startModeBefore = $legacy.StartMode; intendedStartMode = 'Disabled' }
    evidence = [ordered]@{ validationReportSha256 = $validationHash; releaseEvidenceSha256 = $releaseHash; cutoverReceiptSha256 = $cutoverHash; backupManifestSha256 = $backupHash; rawSqlSha256 = $rawSqlHash; transformManifestSha256 = $transformHash }
}
$prechangeFile = Write-DisableReceipt -Directory $receiptDirectory -Receipt $prechange -FileName 'mysql55-disable-prechange.json'
$serviceMutationAttempted = $false
try {
    $serviceMutationAttempted = $true
    Set-Service -Name $legacyServiceName -StartupType Disabled -ErrorAction Stop
    $verified = Get-CimInstance Win32_Service -Filter "Name='$legacyServiceName'" -ErrorAction Stop
    if ($verified.State -ne 'Stopped' -or $verified.StartMode -ne 'Disabled') { throw 'Legacy MySQL service did not remain stopped and Disabled.' }
    if ((Get-ListenerOwners -Port 3306).Count -ne 0) { throw 'Port 3306 began listening after DisableLegacy.' }
    Assert-LiveFinalRuntime -Receipt $cutover

    $receipt = [ordered]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToString('o')
        status = 'PASS'
        approvalId = $approval.ApprovalId
        signerThumbprint = $approval.SignerThumbprint
        prechangeReceiptSha256 = $prechangeFile.Sha256
        target = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $TargetDatabase }
        backend = [ordered]@{ host = '127.0.0.1'; port = 18081; processId = [int](Get-RequiredProperty $cutover 'backend.processId'); flywayVersion = '37' }
        legacy = [ordered]@{ serviceName = $legacyServiceName; serviceImagePathSha256 = (Get-ServiceIdentitySha256 -ServiceName $legacyServiceName); state = 'Stopped'; startMode = 'Disabled'; port3306Listening = $false; serviceDeleted = $false; originalDataDeleted = $false }
        evidence = [ordered]@{ validationReportSha256 = $validationHash; releaseEvidenceSha256 = $releaseHash; cutoverReceiptSha256 = $cutoverHash; backupManifestSha256 = $backupHash; rawSqlSha256 = $rawSqlHash; transformManifestSha256 = $transformHash; backupFileCount = $approval.BackupFileCount; backupTotalBytes = $approval.BackupTotalBytes; backupRetentionUntil = $approval.RetentionUntil.ToString('o') }
        noDualWrite = $true
    }
    $receiptFile = Write-DisableReceipt -Directory $receiptDirectory -Receipt $receipt
    Write-Host 'DisableLegacy: PASS' -ForegroundColor Green
    Write-Host 'MySQL 5.5 remains stopped; startup mode is Disabled. The service and original data were not deleted.'
    Write-Host "Restricted disable receipt: $($receiptFile.Path)"
    Write-Host "Receipt SHA-256: $($receiptFile.Sha256)"
    exit 0
}
catch {
    $current = Get-CimInstance Win32_Service -Filter "Name='$legacyServiceName'" -ErrorAction SilentlyContinue
    $failure = [ordered]@{
        schemaVersion = 1; generatedAt = (Get-Date).ToString('o'); status = 'BLOCKED'; operation = 'DisableLegacy'
        approvalId = $approval.ApprovalId; prechangeReceiptSha256 = $prechangeFile.Sha256; serviceMutationAttempted = $serviceMutationAttempted
        legacy = [ordered]@{ serviceName = $legacyServiceName; observedState = if ($current) { $current.State } else { 'UNKNOWN' }; observedStartMode = if ($current) { $current.StartMode } else { 'UNKNOWN' }; automaticReenableAttempted = $false; serviceDeleted = $false; originalDataDeleted = $false }
        blocker = 'DisableLegacy did not complete every post-change gate. The script intentionally did not re-enable or start MySQL 5.5.'
    }
    try { [void](Write-DisableReceipt -Directory $receiptDirectory -Receipt $failure -FileName 'mysql55-disable-blocked.json') } catch {}
    throw
}
