[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'Cutover', 'RollbackPlan')]
    [string]$Mode = 'Preflight',
    [string]$ValidationReport,
    [string]$ValidationReportSha256,
    [string]$ReleaseEvidenceReport,
    [string]$ReleaseEvidenceReportSha256,
    [string]$ApprovalManifest,
    [string]$ApprovedSignerThumbprint,
    [string]$LatestJarPath,
    [string]$ExpenseStorageRoot,
    [ValidateRange(1, [int]::MaxValue)]
    [int]$OldBackendProcessId,
    [string]$JavaPath = 'java.exe',
    [string]$MySqlClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [string]$RunRoot,
    [string]$PlanOutputPath,
    [ValidateRange(30, 600)]
    [int]$HealthTimeoutSeconds = 180,
    [switch]$Execute
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$migrationDirectory = Join-Path $projectRoot 'backend\src\main\resources\db\migration'
$trustedSignerPolicyPath = Join-Path $env:ProgramData 'AI-Profit-OS\release-trust\trusted-signers.psd1'
$TargetDatabase = 'store_profit_mysql8_final'
$FinalEnvironment = 'STAGING'
$approvedJdbcUrl = 'jdbc:mysql://127.0.0.1:3307/store_profit_mysql8_final?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=false&connectTimeout=3000&socketTimeout=3000'

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
    $workspacePrefix = $projectRoot + '\'
    if ($full.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Label must be outside the Git workspace." }
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
        [Parameter(Mandatory)][string]$Operation,
        [Parameter(Mandatory)][string]$ValidationHash,
        [Parameter(Mandatory)][string]$ReleaseHash,
        [Parameter(Mandatory)][string]$JarHash,
        [Parameter(Mandatory)][string]$V37Hash,
        [Parameter(Mandatory)][int]$V37Checksum
    )
    $manifestPath = Assert-OutsideWorkspaceFile -Path $Path -Label 'Signed approval manifest'
    if ([IO.Path]::GetExtension($manifestPath) -ine '.psd1') { throw 'The signed approval manifest must be a .psd1 PowerShell data file.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $manifestPath
    if ($signature.Status -ne [Management.Automation.SignatureStatus]::Valid -or -not $signature.SignerCertificate) { throw 'The approval manifest Authenticode signature is not valid.' }
    $expectedThumbprint = ($SignerThumbprint -replace '\s', '').ToUpperInvariant()
    if ($expectedThumbprint -notmatch '^[0-9A-F]{40,64}$' -or $signature.SignerCertificate.Thumbprint.ToUpperInvariant() -ne $expectedThumbprint) { throw 'The approval manifest signer does not match the explicitly approved certificate thumbprint.' }
    $trustedThumbprints = Get-TrustedSignerThumbprints
    if ($trustedThumbprints -notcontains $expectedThumbprint) { throw 'The approval signer is not present in the protected machine release-trust policy.' }
    $codeSigningEku = @($signature.SignerCertificate.Extensions | Where-Object { $_ -is [Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension] } | ForEach-Object { $_.EnhancedKeyUsages } | Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.3' })
    if ($codeSigningEku.Count -eq 0) { throw 'The approval signer certificate is not authorized for Code Signing.' }
    $manifest = Import-PowerShellDataFile -LiteralPath $manifestPath
    if ([int](Get-RequiredProperty $manifest 'SchemaVersion') -ne 1) { throw 'Unsupported approval manifest schema.' }
    if ([string](Get-RequiredProperty $manifest 'Status') -cne 'APPROVED') { throw 'Approval manifest status is not APPROVED.' }
    if (@(Get-RequiredProperty $manifest 'Operations') -notcontains $Operation) { throw "Approval manifest does not authorize $Operation." }
    if ([string](Get-RequiredProperty $manifest 'TargetDatabase') -cne $TargetDatabase) { throw 'Approval manifest target database differs from the requested target.' }
    if ((Get-NormalizedSha256 (Get-RequiredProperty $manifest 'ValidationReportSha256')) -ne $ValidationHash) { throw 'Approval manifest does not bind the validation report.' }
    if ((Get-NormalizedSha256 (Get-RequiredProperty $manifest 'ReleaseEvidenceSha256')) -ne $ReleaseHash) { throw 'Approval manifest does not bind the release evidence.' }
    if ((Get-NormalizedSha256 (Get-RequiredProperty $manifest 'JarSha256')) -ne $JarHash) { throw 'Approval manifest does not bind the candidate Jar.' }
    if ((Get-NormalizedSha256 (Get-RequiredProperty $manifest 'V37Sha256')) -ne $V37Hash -or [int](Get-RequiredProperty $manifest 'V37Checksum') -ne $V37Checksum) { throw 'Approval manifest does not bind the verified V37 migration.' }
    if ([string](Get-RequiredProperty $manifest 'FinalEnvironment') -cne $FinalEnvironment) { throw 'Approval manifest final environment differs from the requested environment.' }
    $approvalId = [string](Get-RequiredProperty $manifest 'ApprovalId')
    $approver = [string](Get-RequiredProperty $manifest 'Approver')
    if ($approvalId -notmatch '^[A-Za-z0-9._-]{6,128}$' -or [string]::IsNullOrWhiteSpace($approver)) { throw 'Approval identity is incomplete.' }
    $approvedAt = [DateTimeOffset]::Parse([string](Get-RequiredProperty $manifest 'ApprovedAt'), [Globalization.CultureInfo]::InvariantCulture)
    $expiresAt = [DateTimeOffset]::Parse([string](Get-RequiredProperty $manifest 'ExpiresAt'), [Globalization.CultureInfo]::InvariantCulture)
    $now = [DateTimeOffset]::Now
    if ($approvedAt -gt $now.AddMinutes(5) -or $expiresAt -le $now -or $expiresAt -le $approvedAt) { throw 'Approval manifest is not currently valid.' }
    [pscustomobject]@{ Path = $manifestPath; ApprovalId = $approvalId; Approver = $approver; ExpiresAt = $expiresAt; SignerThumbprint = $expectedThumbprint }
}

function Get-TrustedSignerThumbprints {
    if (-not (Test-Path -LiteralPath $trustedSignerPolicyPath -PathType Leaf)) { throw 'Protected release-trust policy is missing; cutover remains blocked.' }
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

if (-not ('CutoverFlywayChecksum' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
public static class CutoverFlywayChecksum {
    private static int Finish(uint crc) { return unchecked((int)(crc ^ 0xFFFFFFFFu)); }
    private static uint Add(uint crc, byte value) {
        crc ^= value;
        for (int bit = 0; bit < 8; bit++) crc = (crc & 1u) != 0 ? (crc >> 1) ^ 0xEDB88320u : crc >> 1;
        return crc;
    }
    public static int CalculateBytes(byte[] content) {
        uint crc = 0xFFFFFFFFu;
        using (var reader = new StreamReader(new MemoryStream(content), new UTF8Encoding(false, true), true)) {
            string line;
            while ((line = reader.ReadLine()) != null) {
                foreach (byte value in Encoding.UTF8.GetBytes(line)) crc = Add(crc, value);
            }
        }
        return Finish(crc);
    }
}
'@
}

function Get-BytesSha256 {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
}

function Get-ServiceIdentitySha256 {
    param([Parameter(Mandatory)][string]$ServiceName)
    $service = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'" -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($service.PathName)) { throw 'Windows service executable identity is missing.' }
    Get-BytesSha256 ([Text.Encoding]::UTF8.GetBytes(("{0}|{1}" -f $service.Name, $service.PathName)))
}

function Get-V37ArtifactIdentity {
    param([Parameter(Mandatory)][string]$ScriptName, [Parameter(Mandatory)][string]$JarPath)
    if ($ScriptName -notmatch '^V37__[A-Za-z0-9_.-]+\.sql$') { throw 'Release evidence does not name a V37 migration script.' }
    $sourcePath = Join-Path $migrationDirectory $ScriptName
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) { throw 'The authoritative V37 source file is missing.' }
    $sourceBytes = [IO.File]::ReadAllBytes($sourcePath)
    $sourceSha = Get-BytesSha256 $sourceBytes
    $sourceChecksum = [CutoverFlywayChecksum]::CalculateBytes($sourceBytes)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entryName = "BOOT-INF/classes/db/migration/$ScriptName"
        $entries = @($archive.Entries | Where-Object { $_.FullName -ceq $entryName })
        if ($entries.Count -ne 1) { throw 'The candidate Jar does not contain exactly one authoritative V37 migration.' }
        $stream = $entries[0].Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $jarBytes = $memory.ToArray() }
        finally { $memory.Dispose(); $stream.Dispose() }
        $jarSha = Get-BytesSha256 $jarBytes
        $jarChecksum = [CutoverFlywayChecksum]::CalculateBytes($jarBytes)
    }
    finally { $archive.Dispose() }
    if ($sourceSha -ne $jarSha -or $sourceChecksum -ne $jarChecksum) { throw 'V37 differs between the source tree and the candidate Jar.' }
    [pscustomobject]@{ Script = $ScriptName; Sha256 = $sourceSha; Checksum = $sourceChecksum }
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

function Assert-MySqlRuntimeIsolation {
    $legacy = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction Stop
    if ($legacy.State -ne 'Stopped') { throw 'Legacy MySQL service is not stopped.' }
    if ((Get-ListenerOwners -Port 3306).Count -ne 0) { throw 'Port 3306 is listening; cutover is forbidden.' }
    $targetService = Get-CimInstance Win32_Service -Filter "Name='MySQL80Test'" -ErrorAction Stop
    if ($targetService.State -ne 'Running' -or [int]$targetService.ProcessId -le 0) { throw 'MySQL80Test is not running.' }
    $owners = Get-ListenerOwners -Port 3307
    if ($owners.Count -ne 1 -or -not (Test-ProcessDescendsFrom -ProcessId ([int]$owners[0]) -AncestorId ([int]$targetService.ProcessId))) { throw 'The 3307 listener is not owned by MySQL80Test or its child process.' }
}

function Assert-RestrictedStorageAcl {
    param([Parameter(Mandatory)][string]$Path)
    $acl = Get-Acl -LiteralPath $Path
    $broadSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
    $writeRights = [Security.AccessControl.FileSystemRights]'Write, Modify, FullControl, CreateFiles, CreateDirectories, Delete, DeleteSubdirectoriesAndFiles, ChangePermissions, TakeOwnership'
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        try { $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { throw 'An attachment storage ACL identity could not be resolved safely.' }
        if ($sid -in $broadSids -and (($rule.FileSystemRights -band $writeRights) -ne 0)) { throw 'Expense attachment storage grants broad write access to Everyone, Authenticated Users, or Users.' }
    }
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

function Assert-TestBackendRuntime {
    param([Parameter(Mandatory)]$ReleaseEvidence)
    $expectedPid = [int](Get-RequiredProperty $ReleaseEvidence 'backend18082.processId')
    $owners = Get-ListenerOwners -Port 18082
    if ($owners.Count -ne 1 -or [int]$owners[0] -ne $expectedPid) { throw 'The live 18082 process does not match the approved release evidence.' }
    $process = Get-Process -Id $expectedPid -ErrorAction Stop
    if ($process.ProcessName -notmatch '^java') { throw 'The approved 18082 listener is not a Java process.' }
    $runtime = Get-CimInstance Win32_Process -Filter "ProcessId=$expectedPid" -ErrorAction Stop
    if ([IO.Path]::GetFullPath($runtime.ExecutablePath) -ine $javaExecutablePath) { throw 'The live 18082 Java executable differs from the approved runtime.' }
    if ([string]::IsNullOrWhiteSpace($runtime.CommandLine) -or $runtime.CommandLine.IndexOf($jarPath, [StringComparison]::OrdinalIgnoreCase) -lt 0) { throw 'The live 18082 process command does not reference the approved candidate Jar path.' }
    if ((Get-Item -LiteralPath $jarPath).LastWriteTimeUtc -gt $process.StartTime.ToUniversalTime().AddSeconds(1)) { throw 'The candidate Jar was modified after the live 18082 process started.' }
    $health = Get-HealthData -Port 18082
    Assert-HealthDatabaseIdentity -HealthData $health -Label 'The 18082 STAGING backend'
    if ([string](Get-RequiredProperty $health 'environment') -cne 'STAGING') { throw 'The 18082 backend is not running in STAGING.' }
    if ([string](Get-RequiredProperty $health 'databaseMigrationVersion') -cne '37') { throw 'The 18082 backend does not report Flyway V37.' }
    if ([string](Get-RequiredProperty $health 'sourceVersion') -cne [string](Get-RequiredProperty $ReleaseEvidence 'artifact.sourceVersion')) { throw 'The live 18082 source version differs from the approved artifact.' }
}

function Assert-ValidationEvidence {
    param([Parameter(Mandatory)]$Validation)
    Assert-PassProperty $Validation 'status'
    if ([int](Get-RequiredProperty $Validation 'source.port') -ne 3309) { throw 'Validation source is not the isolated 3309 recovery instance.' }
    if ([string](Get-RequiredProperty $Validation 'target.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $Validation 'target.port') -ne 3307 -or [string](Get-RequiredProperty $Validation 'target.database') -cne $TargetDatabase) { throw 'Validation target identity differs from the approved final target.' }
    if ([string](Get-RequiredProperty $Validation 'target.version') -notmatch '^8\.0\.46(?:[-+].*)?$') { throw 'Validation target is not the approved MySQL 8.0.46 runtime.' }
    foreach ($gate in @('flywayMatches','criticalAggregatesMatch','passwordHashesMatch','indexDefinitionsMatch','columnDefinitionsMatch','foreignKeyDefinitionsMatch','programmableObjectDefinitionsMatch','binaryAttachmentsMatch')) { Assert-TrueProperty $Validation $gate }
    if (@(Get-RequiredProperty $Validation 'blockers').Count -ne 0 -or @(Get-RequiredProperty $Validation 'tableCountMismatches').Count -ne 0) { throw 'Validation report still contains blockers or table mismatches.' }
}

function Assert-ReleaseEvidence {
    param([Parameter(Mandatory)]$Release)
    Assert-PassProperty $Release 'status'
    if ([string](Get-RequiredProperty $Release 'target.host') -cne '127.0.0.1' -or [int](Get-RequiredProperty $Release 'target.port') -ne 3307 -or [string](Get-RequiredProperty $Release 'target.database') -cne $TargetDatabase) { throw 'Release evidence target identity differs from the requested target.' }
    if ([string](Get-RequiredProperty $Release 'target.mysqlVersion') -notmatch '^8\.0\.46(?:[-+].*)?$') { throw 'Release evidence target is not the approved MySQL 8.0.46 runtime.' }
    if ([string](Get-RequiredProperty $Release 'flyway.version') -cne '37' -or [string](Get-RequiredProperty $Release 'flyway.script') -notmatch '^V37__') { throw 'Release evidence is not for Flyway V37.' }
    foreach ($gate in @('flyway.success','flyway.validatePass','flyway.repeatedStartPass','target.noFailedFlywayRows','backend18082.healthPass','backend18082.databaseIdentityPass','backend18082.secondStartPass','browser.crossStore403','browser.no404Or500','browser.noJavaScriptErrors','attachments.filesystemPass','attachments.trainingImagesPass','attachments.expensePass','attachments.inspectionPass','attachments.examPass','network.no3306Connection')) { Assert-TrueProperty $Release $gate }
    foreach ($gate in @('backend18082.status','automatedTests.backend','automatedTests.vueTypeCheck','automatedTests.vueBuild','automatedTests.e2e','browser.status','browser.roles.BOSS','browser.roles.FINANCE','browser.roles.STORE_MANAGER','browser.roles.SUPERVISOR','browser.roles.WAREHOUSE','browser.roles.OPERATIONS','browser.roles.EMPLOYEE','attachments.status')) { Assert-PassProperty $Release $gate }
    if ([int](Get-RequiredProperty $Release 'backend18082.port') -ne 18082 -or [string](Get-RequiredProperty $Release 'backend18082.environment') -cne 'STAGING' -or [int](Get-RequiredProperty $Release 'backend18082.mysqlPort') -ne 3307 -or [string](Get-RequiredProperty $Release 'backend18082.database') -cne $TargetDatabase) { throw 'The 18082 STAGING evidence does not identify the approved final target.' }
}

function Initialize-RestrictedDirectory {
    param([Parameter(Mandatory)][string]$Path)
    $full = Assert-OutsideWorkspaceDirectory -Path $Path -Label 'Cutover run directory'
    if (Test-Path -LiteralPath $full) { throw 'Cutover run directory must be a new dedicated directory.' }
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

function Set-ReadOnlyArtifactAcl {
    param([Parameter(Mandatory)][string]$Path)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, [Security.AccessControl.FileSystemRights]'ReadAndExecute, ReadAttributes, ReadExtendedAttributes, ReadPermissions', [Security.AccessControl.AccessControlType]::Allow))
    Set-Acl -LiteralPath $Path -AclObject $acl
    [IO.File]::SetAttributes($Path, ([IO.File]::GetAttributes($Path) -bor [IO.FileAttributes]::ReadOnly))
}

function Get-ProcessIdentity {
    param([Parameter(Mandatory)][int]$ProcessId)
    $managed = Get-Process -Id $ProcessId -ErrorAction Stop
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($cim.ExecutablePath) -or [string]::IsNullOrWhiteSpace($cim.CommandLine)) { throw 'Backend process identity is incomplete.' }
    [pscustomobject]@{
        ProcessId = $ProcessId
        StartTimeUtc = $managed.StartTime.ToUniversalTime().ToString('o')
        ExecutablePath = [IO.Path]::GetFullPath($cim.ExecutablePath)
        CommandSha256 = Get-BytesSha256 ([Text.Encoding]::UTF8.GetBytes($cim.CommandLine))
    }
}

function Assert-ProcessIdentityUnchanged {
    param([Parameter(Mandatory)]$Expected, [Parameter(Mandatory)][int]$ListenerPort)
    $owners = Get-ListenerOwners -Port $ListenerPort
    if ($owners.Count -ne 1 -or [int]$owners[0] -ne [int]$Expected.ProcessId) { throw 'Backend listener ownership changed before the approved operation.' }
    $actual = Get-ProcessIdentity -ProcessId ([int]$Expected.ProcessId)
    if ($actual.StartTimeUtc -cne $Expected.StartTimeUtc -or $actual.ExecutablePath -ine $Expected.ExecutablePath -or $actual.CommandSha256 -ne $Expected.CommandSha256) { throw 'Backend PID identity changed before the approved operation.' }
    Get-Process -Id ([int]$Expected.ProcessId) -ErrorAction Stop
}

function ConvertFrom-SecureValue {
    param([Parameter(Mandatory)][Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function New-MySqlOptionFile {
    param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$UserName, [Parameter(Mandatory)][string]$Password)
    if ($UserName -notmatch '^[A-Za-z0-9_.@-]+$' -or $Password -match "[`r`n]") { throw 'The local MySQL credential contains an unsupported character.' }
    $path = Join-Path $Directory ([IO.Path]::GetRandomFileName())
    $escaped = $Password.Replace('\', '\\').Replace('"', '\"')
    [IO.File]::WriteAllText($path, "[client]`r`nprotocol=TCP`r`nhost=127.0.0.1`r`nport=3307`r`nuser=$UserName`r`npassword=`"$escaped`"`r`ndatabase=$TargetDatabase`r`ndefault-character-set=utf8mb4`r`n", [Text.UTF8Encoding]::new($false))
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    [void]$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, [Security.AccessControl.FileSystemRights]::FullControl, [Security.AccessControl.AccessControlType]::Allow))
    Set-Acl -LiteralPath $path -AclObject $acl
    $path
}

function Remove-CredentialFile {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path)) { return }
    try {
        $length = (Get-Item -LiteralPath $Path).Length
        if ($length -gt 0) {
            $bytes = New-Object byte[] $length
            $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
            [IO.File]::WriteAllBytes($Path, $bytes)
        }
    }
    finally { Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue }
}

function Invoke-MySqlQuery {
    param([Parameter(Mandatory)][string]$OptionFile, [Parameter(Mandatory)][string]$Sql)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $MySqlClientPath
    $startInfo.Arguments = "--defaults-file=`"$OptionFile`" --batch --raw --skip-column-names"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Environment.Clear()
    foreach ($name in @('SystemRoot','WINDIR','TEMP','TMP')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) { $startInfo.Environment[$name] = $value }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($Sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $output = $stdoutTask.GetAwaiter().GetResult()
        [void]$stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'The MySQL 8 application account check failed. No server output is shown because it may contain sensitive values.' }
        @($output -split "`r?`n" | Where-Object { $_ -ne '' })
    }
    finally { try { if (-not $process.HasExited) { $process.Kill() } } catch {}; $process.Dispose() }
}

function Assert-ApplicationAccount {
    param([Parameter(Mandatory)][string]$OptionFile)
    $identity = Invoke-MySqlQuery -OptionFile $OptionFile -Sql 'SELECT VERSION(), @@port, DATABASE(), CURRENT_USER();' | Select-Object -First 1
    $cells = $identity -split "`t", 4
    if ($cells.Count -ne 4 -or $cells[0] -notmatch '^8\.0\.46(?:[-+].*)?$' -or [int]$cells[1] -ne 3307 -or $cells[2] -cne $TargetDatabase -or $cells[3] -match '^(?i:root)@' -or $cells[3] -match '@%$') { throw 'The application account does not resolve to the approved local MySQL 8.0.46 target, or it is not an independent application account.' }
    $grants = Invoke-MySqlQuery -OptionFile $OptionFile -Sql 'SHOW GRANTS FOR CURRENT_USER();'
    if (-not $grants) { throw 'The application account has no visible grants.' }
    foreach ($grant in $grants) {
        if ($grant -match '(?i)WITH\s+GRANT\s+OPTION') { throw 'The application account has GRANT OPTION.' }
        if ($grant -match '(?i)^GRANT\s+USAGE\s+ON\s+\*\.\*') { continue }
        if ($grant -notmatch '(?i)\sON\s+(`?)([^`.\s]+)\1\.(`?)([^`\s]+)\3') { throw 'The application account has an unsupported role, proxy, or non-database grant.' }
        $schema = $matches[2]
        if ($schema -eq '*') { throw 'The application account has global privileges.' }
        if ($schema -cne $TargetDatabase) { throw 'The application account has privileges outside the approved target database.' }
    }
}

function Wait-BackendHealth {
    param([Parameter(Mandatory)][Diagnostics.Process]$Process)
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    do {
        if ($Process.HasExited) { throw 'The candidate backend exited before becoming healthy.' }
        try {
            $health = Get-HealthData -Port 18081
            Assert-HealthDatabaseIdentity -HealthData $health -Label 'The new 18081 backend'
            if ([string](Get-RequiredProperty $health 'environment') -cne $FinalEnvironment) { throw 'The new 18081 backend reports the wrong environment.' }
            if ([string](Get-RequiredProperty $health 'databaseMigrationVersion') -cne '37') { throw 'The new 18081 backend does not report Flyway V37.' }
            return $health
        }
        catch {
            if ((Get-Date) -ge $deadline) { throw }
            Start-Sleep -Seconds 2
        }
    } while ((Get-Date) -lt $deadline)
    throw 'The new 18081 backend did not become healthy before the deadline.'
}

function Write-Receipt {
    param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)]$Receipt)
    $path = Join-Path $Directory 'mysql8-cutover-receipt.json'
    $Receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -Encoding UTF8
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToUpperInvariant()
    [IO.File]::WriteAllText("$path.sha256", $hash + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{ Path = $path; Sha256 = $hash }
}

function Write-RollbackPlan {
    $plan = @"
# MySQL 8 cutover rollback plan

- This plan does not execute any command.
- Before the first business write on 3307: stop only the exact new 18081 PID, investigate, and obtain a new signed approval before any restart.
- After any business write on 3307: do not point the backend back to 3306. First freeze writes, take a fresh logical backup of 3307, reconcile the write delta, and approve a separate logical reverse-migration plan.
- Never give a MySQL 8 data directory to MySQL 5.5.
- Keep the original MySQL 5.5 datadir, pristine offline copy, SQL dump, manifests, and SHA-256 evidence unchanged for at least 30 days.
- Do not enable the legacy service or start port 3306 from this plan.
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
    ApprovalManifest = $ApprovalManifest
    ApprovedSignerThumbprint = $ApprovedSignerThumbprint
    LatestJarPath = $LatestJarPath
    ExpenseStorageRoot = $ExpenseStorageRoot
}.GetEnumerator()) { if ([string]::IsNullOrWhiteSpace([string]$required.Value)) { throw "Missing required parameter: $($required.Key)." } }

$validationPath = Assert-OutsideWorkspaceFile -Path $ValidationReport -Label 'Validation report'
$releasePath = Assert-OutsideWorkspaceFile -Path $ReleaseEvidenceReport -Label 'Release evidence report'
$validationHash = Assert-FileHash -Path $validationPath -Expected $ValidationReportSha256 -Label 'Validation report'
$releaseHash = Assert-FileHash -Path $releasePath -Expected $ReleaseEvidenceReportSha256 -Label 'Release evidence report'
$validation = Get-Content -LiteralPath $validationPath -Raw -Encoding UTF8 | ConvertFrom-Json
$release = Get-Content -LiteralPath $releasePath -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-ValidationEvidence -Validation $validation
Assert-ReleaseEvidence -Release $release

$jarPath = [IO.Path]::GetFullPath($LatestJarPath)
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf) -or [IO.Path]::GetExtension($jarPath) -ine '.jar') { throw 'The candidate Jar does not exist.' }
$jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarPath).Hash.ToUpperInvariant()
if ($jarHash -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.jarSha256'))) { throw 'Candidate Jar SHA-256 differs from release evidence.' }
$v37 = Get-V37ArtifactIdentity -ScriptName ([string](Get-RequiredProperty $release 'flyway.script')) -JarPath $jarPath
if ($v37.Sha256 -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.v37Sha256')) -or $v37.Checksum -ne [int](Get-RequiredProperty $release 'flyway.checksum')) { throw 'V37 source/Jar identity differs from the successful release evidence.' }
$approval = Assert-SignedApprovalManifest -Path $ApprovalManifest -SignerThumbprint $ApprovedSignerThumbprint -Operation 'Cutover' -ValidationHash $validationHash -ReleaseHash $releaseHash -JarHash $jarHash -V37Hash $v37.Sha256 -V37Checksum $v37.Checksum

$javaCommand = Get-Command $JavaPath -ErrorAction Stop
if ($javaCommand.CommandType -ne [Management.Automation.CommandTypes]::Application -or [IO.Path]::GetFileName($javaCommand.Source) -ine 'java.exe') { throw 'JavaPath must resolve to a java.exe application.' }
$javaExecutablePath = Assert-OutsideWorkspaceFile -Path $javaCommand.Source -Label 'Approved Java executable'
if ($javaExecutablePath -ine [IO.Path]::GetFullPath([string](Get-RequiredProperty $release 'artifact.javaPath')) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $javaExecutablePath).Hash.ToUpperInvariant() -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.javaSha256'))) { throw 'Java executable path or SHA-256 differs from signed release evidence.' }
$approvedMySqlClient = Assert-OutsideWorkspaceFile -Path $MySqlClientPath -Label 'Approved MySQL 8 client'
if ($approvedMySqlClient -ine [IO.Path]::GetFullPath([string](Get-RequiredProperty $release 'artifact.mysqlClientPath')) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $approvedMySqlClient).Hash.ToUpperInvariant() -ne (Get-NormalizedSha256 (Get-RequiredProperty $release 'artifact.mysqlClientSha256'))) { throw 'MySQL 8 client path or SHA-256 differs from signed release evidence.' }
$MySqlClientPath = $approvedMySqlClient

Assert-MySqlRuntimeIsolation
$legacyServiceIdentityHash = Get-ServiceIdentitySha256 -ServiceName 'MySQL'
Assert-TestBackendRuntime -ReleaseEvidence $release
$storageRoot = Assert-OutsideWorkspaceDirectory -Path $ExpenseStorageRoot -Label 'Expense attachment storage root'
if (-not (Test-Path -LiteralPath $storageRoot -PathType Container)) { throw 'Expense attachment storage root does not exist.' }
if ($storageRoot -ine (Assert-OutsideWorkspaceDirectory -Path ([string](Get-RequiredProperty $release 'attachments.expenseStorageRoot')) -Label 'Release-evidence attachment storage root')) { throw 'Cutover attachment storage differs from the directory used by the signed release evidence.' }
Assert-RestrictedStorageAcl -Path $storageRoot

Write-Host 'Cutover preflight: PASS' -ForegroundColor Green
Write-Host "Target: 127.0.0.1:3307/$TargetDatabase"
Write-Host "Flyway: V37 (checksum $($v37.Checksum))"
Write-Host "Candidate Jar SHA-256: $jarHash"
Write-Host "Approval expires: $($approval.ExpiresAt.ToString('o'))"
if ($Mode -eq 'Preflight') { exit 0 }

if (-not $Execute) { throw 'Cutover is fail-closed. Re-run with -Mode Cutover -Execute after reviewing the PASS preflight.' }
if ($OldBackendProcessId -le 0) { throw 'Cutover requires the exact old 18081 backend PID.' }
$oldOwners = Get-ListenerOwners -Port 18081
if ($oldOwners.Count -ne 1 -or [int]$oldOwners[0] -ne $OldBackendProcessId) { throw 'OldBackendProcessId does not exclusively own 127.0.0.1:18081.' }
$oldProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$OldBackendProcessId" -ErrorAction Stop
if ($oldProcess.Name -notmatch '^java(?:\.exe)?$' -or $oldProcess.CommandLine -notmatch '(?i)\.jar(?:"|\s|$)') { throw 'The exact 18081 process is not an identifiable Java Jar backend.' }
$oldProcessIdentity = Get-ProcessIdentity -ProcessId $OldBackendProcessId

$firstPhrase = 'CUTOVER-18081-TO-3307'
if ((Read-Host "First confirmation: type $firstPhrase") -cne $firstPhrase) { throw 'First cutover confirmation did not match.' }
if ((Read-Host "Second confirmation: type approval ID $($approval.ApprovalId)") -cne $approval.ApprovalId) { throw 'Second cutover confirmation did not match.' }

if (-not $RunRoot) { $RunRoot = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql8-cutover\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$runDirectory = Initialize-RestrictedDirectory -Path $RunRoot
$credential = $null
$plainPassword = $null
$optionFile = $null
$newProcess = $null
$jarLock = $null
$stagedJarPath = Join-Path $runDirectory 'store-profit-backend-approved.jar'
$success = $false
try {
    Copy-Item -LiteralPath $jarPath -Destination $stagedJarPath -ErrorAction Stop
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $stagedJarPath).Hash.ToUpperInvariant() -ne $jarHash) { throw 'Restricted candidate Jar copy does not match the approved SHA-256.' }
    Set-ReadOnlyArtifactAcl -Path $stagedJarPath
    $jarLock = [IO.File]::Open($stagedJarPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $stagedJarPath).Hash.ToUpperInvariant() -ne $jarHash) { throw 'Restricted candidate Jar changed before credential entry.' }

    $credential = Get-Credential -Message 'Enter the LOCAL application database account for 127.0.0.1:3307. The password remains hidden and is not logged.'
    if (-not $credential) { throw 'Application database credential entry was cancelled.' }
    if ($credential.UserName -match '^(?i:root)(?:@|$)') { throw 'The final backend must not use the MySQL root account.' }
    $plainPassword = ConvertFrom-SecureValue $credential.Password
    $optionFile = New-MySqlOptionFile -Directory $runDirectory -UserName $credential.UserName -Password $plainPassword
    Assert-ApplicationAccount -OptionFile $optionFile

    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $stagedJarPath).Hash.ToUpperInvariant() -ne $jarHash) { throw 'Restricted candidate Jar changed before backend stop.' }
    $oldProcessToStop = Assert-ProcessIdentityUnchanged -Expected $oldProcessIdentity -ListenerPort 18081
    Stop-Process -InputObject $oldProcessToStop -ErrorAction Stop
    Wait-Process -Id $OldBackendProcessId -Timeout 30 -ErrorAction SilentlyContinue
    if (Get-Process -Id $OldBackendProcessId -ErrorAction SilentlyContinue) { throw 'The exact old 18081 backend did not stop.' }

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $javaCommand.Source
    $startInfo.Arguments = "-jar `"$stagedJarPath`""
    $startInfo.WorkingDirectory = $runDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.Environment.Clear()
    $systemRoot = [Environment]::GetEnvironmentVariable('SystemRoot')
    $javaBin = Split-Path -Parent $javaCommand.Source
    $javaHome = Split-Path -Parent $javaBin
    $baseEnvironment = [ordered]@{
        'SystemRoot' = $systemRoot
        'WINDIR' = $systemRoot
        'SystemDrive' = [Environment]::GetEnvironmentVariable('SystemDrive')
        'ComSpec' = [Environment]::GetEnvironmentVariable('ComSpec')
        'ProgramData' = [Environment]::GetEnvironmentVariable('ProgramData')
        'ProgramFiles' = [Environment]::GetEnvironmentVariable('ProgramFiles')
        'ProgramFiles(x86)' = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
        'USERPROFILE' = [Environment]::GetEnvironmentVariable('USERPROFILE')
        'LOCALAPPDATA' = [Environment]::GetEnvironmentVariable('LOCALAPPDATA')
        'APPDATA' = [Environment]::GetEnvironmentVariable('APPDATA')
        'TEMP' = $runDirectory
        'TMP' = $runDirectory
        'JAVA_HOME' = $javaHome
        'PATH' = ($javaBin + ';' + (Join-Path $systemRoot 'System32'))
    }
    foreach ($entry in $baseEnvironment.GetEnumerator()) { if (-not [string]::IsNullOrWhiteSpace([string]$entry.Value)) { $startInfo.Environment[$entry.Key] = [string]$entry.Value } }
    $startInfo.Environment['MYSQL_HOST'] = '127.0.0.1'
    $startInfo.Environment['MYSQL_PORT'] = '3307'
    $startInfo.Environment['MYSQL_DATABASE'] = $TargetDatabase
    $startInfo.Environment['MYSQL_USERNAME'] = $credential.UserName
    $startInfo.Environment['MYSQL_PASSWORD'] = $plainPassword
    $startInfo.Environment['SPRING_DATASOURCE_URL'] = $approvedJdbcUrl
    $startInfo.Environment['SPRING_DATASOURCE_USERNAME'] = $credential.UserName
    $startInfo.Environment['SPRING_DATASOURCE_PASSWORD'] = $plainPassword
    $startInfo.Environment['SERVER_PORT'] = '18081'
    $startInfo.Environment['APP_ENV'] = $FinalEnvironment
    $startInfo.Environment['APP_SEED_DEMO_ENABLED'] = 'false'
    $startInfo.Environment['APP_SEED_LEGACY_EMPLOYEE_ENABLED'] = 'false'
    $startInfo.Environment['APP_BOOTSTRAP_DEFAULT_USERS_ENABLED'] = 'false'
    $startInfo.Environment['APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED'] = 'false'
    $startInfo.Environment['APP_MIGRATION_AUTO_RUN'] = 'false'
    $startInfo.Environment['APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT'] = $storageRoot
    $startInfo.Environment['LOGGING_LEVEL_ROOT'] = 'OFF'
    $startInfo.Environment['LOGGING_LEVEL_COM_ZAXXER_HIKARI'] = 'OFF'
    $startInfo.Environment['LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_AUTOCONFIGURE_JDBC'] = 'OFF'
    $newProcess = [Diagnostics.Process]::new()
    $newProcess.StartInfo = $startInfo
    [void]$newProcess.Start()
    [void]$startInfo.Environment.Remove('MYSQL_PASSWORD')
    [void]$startInfo.Environment.Remove('SPRING_DATASOURCE_PASSWORD')
    $plainPassword = $null
    $credential.Password.Dispose()
    $credential = $null
    Remove-CredentialFile -Path $optionFile
    $optionFile = $null

    $health = Wait-BackendHealth -Process $newProcess
    if ([string](Get-RequiredProperty $health 'sourceVersion') -cne [string](Get-RequiredProperty $release 'artifact.sourceVersion')) { throw 'The new 18081 source version differs from the approved release.' }
    $owners = Get-ListenerOwners -Port 18081
    if ($owners.Count -ne 1 -or [int]$owners[0] -ne $newProcess.Id) { throw 'The new backend does not exclusively own 127.0.0.1:18081.' }
    $frontendHealth = Get-HealthData -Port 5173
    Assert-HealthDatabaseIdentity -HealthData $frontendHealth -Label 'The 5173 proxied backend'
    if ([string](Get-RequiredProperty $frontendHealth 'environment') -cne $FinalEnvironment -or [string](Get-RequiredProperty $frontendHealth 'databaseMigrationVersion') -cne '37') { throw 'The 5173 frontend is not proxying to the new 18081 backend.' }
    $active3306 = @(Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object { ($_.LocalPort -eq 3306 -or $_.RemotePort -eq 3306) -and $_.State -notin @('Closed','TimeWait','DeleteTcb') })
    if ($active3306.Count -ne 0) { throw 'An active TCP endpoint still references port 3306.' }

    $receipt = [ordered]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToString('o')
        status = 'PASS'
        approvalId = $approval.ApprovalId
        signerThumbprint = $approval.SignerThumbprint
        validationReportSha256 = $validationHash
        releaseEvidenceSha256 = $releaseHash
        jarSha256 = $jarHash
        v37 = [ordered]@{ script = $v37.Script; checksum = $v37.Checksum; sha256 = $v37.Sha256; success = $true }
        target = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $TargetDatabase }
        backend = [ordered]@{ host = '127.0.0.1'; port = 18081; processId = $newProcess.Id; javaPath = $javaExecutablePath; javaSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $javaExecutablePath).Hash.ToUpperInvariant(); jarPath = $stagedJarPath; jarSha256 = $jarHash; environment = $FinalEnvironment; health = 'UP'; flywayVersion = '37'; sourceVersion = [string](Get-RequiredProperty $health 'sourceVersion') }
        frontend = [ordered]@{ host = '127.0.0.1'; port = 5173; proxyVerified = $true }
        legacy = [ordered]@{ serviceName = 'MySQL'; serviceImagePathSha256 = $legacyServiceIdentityHash; port3306Listening = $false; active3306ConnectionCount = 0; serviceStopped = $true; serviceDisabled = $false }
        noDualWrite = $true
    }
    $receiptFile = Write-Receipt -Directory $runDirectory -Receipt $receipt
    $success = $true
    Write-Host 'Cutover: PASS' -ForegroundColor Green
    Write-Host "New 18081 backend PID: $($newProcess.Id)"
    Write-Host "Restricted cutover receipt: $($receiptFile.Path)"
    Write-Host "Receipt SHA-256: $($receiptFile.Sha256)"
}
catch {
    if ($newProcess -and -not $newProcess.HasExited) { Stop-Process -Id $newProcess.Id -Force -ErrorAction SilentlyContinue }
    $failure = [ordered]@{ schemaVersion = 1; generatedAt = (Get-Date).ToString('o'); status = 'BLOCKED'; approvalId = $approval.ApprovalId; validationReportSha256 = $validationHash; releaseEvidenceSha256 = $releaseHash; jarSha256 = $jarHash; newBackendStopped = $true; blocker = 'Cutover failed after approval. Sensitive server output was intentionally suppressed; inspect only restricted application diagnostics.' }
    [void](Write-Receipt -Directory $runDirectory -Receipt $failure)
    throw
}
finally {
    Remove-CredentialFile -Path $optionFile
    $plainPassword = $null
    if ($credential) { $credential.Password.Dispose() }
    if ($jarLock) { $jarLock.Dispose() }
    if ($newProcess) { $newProcess.Dispose() }
}

if (-not $success) { exit 2 }
exit 0
