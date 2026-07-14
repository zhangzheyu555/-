[CmdletBinding()]
param(
    [ValidateSet('RecoveryCopy')]
    [string]$SourceMode = 'RecoveryCopy',
    [string]$SourceHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$SourcePort = 3309,
    [string]$TargetHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$TargetPort = 3307,
    [string]$TargetServiceName = 'MySQL80Test',
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Assert-LocalEndpoint {
    param([string]$HostName, [int]$Port, [int]$ExpectedPort, [string]$Label)
    if ($HostName -notin @('127.0.0.1', 'localhost')) {
        throw "$Label must remain local."
    }
    if ($Port -ne $ExpectedPort) {
        throw "$Label must use port $ExpectedPort."
    }
}

function Set-CurrentUserOnlyAcl {
    param([Parameter(Mandatory)][string]$Path, [switch]$Container)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    if ($Container) {
        $security = [Security.AccessControl.DirectorySecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    }
    else {
        $security = [Security.AccessControl.FileSecurity]::new()
        $inheritance = [Security.AccessControl.InheritanceFlags]::None
    }
    $security.SetOwner($sid)
    $security.SetAccessRuleProtection($true, $false)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, [Security.AccessControl.FileSystemRights]::FullControl, $inheritance, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
    [void]$security.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $security
}

function Initialize-RestrictedDirectory {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $rootPath = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    if ($fullPath.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Audit and backup output must be outside the Git workspace.'
    }
    [void](New-Item -ItemType Directory -Path $fullPath -Force)
    Set-CurrentUserOnlyAcl -Path $fullPath -Container
    $fullPath
}

function Get-Listener {
    param([int]$Port)
    $rows = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if (-not $rows) { return $null }
    $row = $rows | Sort-Object @{ Expression = { if ($_.LocalAddress -eq '127.0.0.1') { 0 } else { 1 } } } | Select-Object -First 1
    [pscustomobject]@{
        Address = $row.LocalAddress
        Port = $row.LocalPort
        ProcessId = $row.OwningProcess
    }
}

function Test-ProcessDescendantOf {
    param([int]$ProcessId, [int]$AncestorProcessId)
    if ($ProcessId -le 0 -or $AncestorProcessId -le 0) { return $false }
    $current = $ProcessId
    for ($depth = 0; $depth -lt 8; $depth++) {
        if ($current -eq $AncestorProcessId) { return $true }
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$current" -ErrorAction SilentlyContinue
        if (-not $process -or [int]$process.ParentProcessId -le 0 -or [int]$process.ParentProcessId -eq $current) { return $false }
        $current = [int]$process.ParentProcessId
    }
    $false
}

function Get-MySqlHandshakeVersion {
    param([string]$HostName, [int]$Port)
    $client = [Net.Sockets.TcpClient]::new()
    try {
        $client.ReceiveTimeout = 3000
        $client.SendTimeout = 3000
        $client.Connect($HostName, $Port)
        $stream = $client.GetStream()
        $header = New-Object byte[] 4
        if ($stream.Read($header, 0, 4) -ne 4) { return $null }
        $length = $header[0] -bor ($header[1] -shl 8) -bor ($header[2] -shl 16)
        $payload = New-Object byte[] $length
        $offset = 0
        while ($offset -lt $length) {
            $read = $stream.Read($payload, $offset, $length - $offset)
            if ($read -le 0) { break }
            $offset += $read
        }
        if ($offset -ne $length -or $payload[0] -ne 10) { return $null }
        $terminator = [Array]::IndexOf($payload, [byte]0, 1)
        if ($terminator -le 1) { return $null }
        [Text.Encoding]::ASCII.GetString($payload, 1, $terminator - 1)
    }
    finally {
        $client.Dispose()
    }
}

function Get-DefaultsFileFromImagePath {
    param([string]$ImagePath)
    if ($ImagePath -match '(?i)--defaults-file=(?:"([^"]+)"|([^\s]+))') {
        if ($Matches[1]) { return $Matches[1] }
        return $Matches[2]
    }
    $null
}

function Get-IniOption {
    param([string]$Path, [string]$Name)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $inMySqlD = $false
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^\[(.+)\]$') {
            $inMySqlD = ($Matches[1] -ieq 'mysqld')
            continue
        }
        if (-not $inMySqlD -or $trimmed -match '^[#;]' -or -not $trimmed) { continue }
        if ($trimmed -match ('^(?i)' + [regex]::Escape($Name) + '\s*(?:=\s*(.*))?$')) {
            if ($null -eq $Matches[1]) { return $true }
            return $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
    $null
}

function Invoke-ConfigValidation {
    param([string]$ServerExecutable, [string]$DefaultsFile)
    if (-not (Test-Path -LiteralPath $ServerExecutable) -or -not (Test-Path -LiteralPath $DefaultsFile)) {
        return [pscustomobject]@{ ExitCode = $null; Status = 'UNAVAILABLE' }
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $ServerExecutable
    $startInfo.Arguments = "--defaults-file=`"$DefaultsFile`" --validate-config"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        [void]$process.StandardOutput.ReadToEnd()
        [void]$process.StandardError.ReadToEnd()
        $process.WaitForExit()
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Status = if ($process.ExitCode -eq 0) { 'PASS' } else { 'FAIL' }
        }
    }
    finally {
        $process.Dispose()
    }
}

function Get-ErrorLogSummary {
    param([string]$Path)
    if (-not $Path) { return [pscustomobject]@{ Status = 'NOT_CONFIGURED'; ErrorLines = $null; WarningLines = $null; Codes = @() } }
    try {
        if (-not (Test-Path -LiteralPath $Path)) {
            return [pscustomobject]@{ Status = 'NOT_FOUND'; ErrorLines = $null; WarningLines = $null; Codes = @() }
        }
        $tail = @(Get-Content -LiteralPath $Path -Tail 1000 -ErrorAction Stop)
        $codes = @($tail | Select-String -Pattern 'MY-[0-9]+' -AllMatches | ForEach-Object { $_.Matches.Value } | Sort-Object -Unique)
        [pscustomobject]@{
            Status = 'READABLE'
            ErrorLines = @($tail | Select-String -Pattern '\[ERROR\]|\bERROR\b').Count
            WarningLines = @($tail | Select-String -Pattern '\[Warning\]|\bWARNING\b').Count
            Codes = $codes
        }
    }
    catch {
        [pscustomobject]@{ Status = 'ACCESS_DENIED'; ErrorLines = $null; WarningLines = $null; Codes = @() }
    }
}

function Get-ServiceAccountAclEvidence {
    param([string]$Path, [string]$Account)
    if (-not $Path) { return [pscustomobject]@{ Status = 'NOT_CONFIGURED'; ModifyAllowed = $false; ExplicitDeny = $false } }
    try {
        if (-not (Test-Path -LiteralPath $Path)) { return [pscustomobject]@{ Status = 'NOT_FOUND'; ModifyAllowed = $false; ExplicitDeny = $false } }
        $sid = ([Security.Principal.NTAccount]::new($Account)).Translate([Security.Principal.SecurityIdentifier]).Value
        $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
        $allowMask = 0
        $denyMask = 0
        foreach ($rule in $acl.Access) {
            try { $ruleSid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { continue }
            if ($ruleSid -ne $sid) { continue }
            if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow) { $allowMask = $allowMask -bor [int]$rule.FileSystemRights }
            if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Deny) { $denyMask = $denyMask -bor [int]$rule.FileSystemRights }
        }
        $effectiveMask = $allowMask -band (-bnot $denyMask)
        $requiredMask = [int][Security.AccessControl.FileSystemRights]::Modify
        $modifyAllowed = (($effectiveMask -band $requiredMask) -eq $requiredMask)
        [pscustomobject]@{ Status = 'READABLE'; ModifyAllowed = $modifyAllowed; ExplicitDeny = ($denyMask -ne 0); DirectAccountEvidenceOnly = $true }
    }
    catch { [pscustomobject]@{ Status = 'ACCESS_DENIED'; ModifyAllowed = $false; ExplicitDeny = $false } }
}

$expectedSourcePort = 3309
Assert-LocalEndpoint -HostName $SourceHost -Port $SourcePort -ExpectedPort $expectedSourcePort -Label 'MySQL 5.5 source'
Assert-LocalEndpoint -HostName $TargetHost -Port $TargetPort -ExpectedPort 3307 -Label 'MySQL 8 target'

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql8-migration-audit\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutputDirectory = Initialize-RestrictedDirectory -Path $OutputDirectory
$blockers = [Collections.Generic.List[string]]::new()

$service = Get-CimInstance Win32_Service -Filter "Name='$TargetServiceName'" -ErrorAction SilentlyContinue
if (-not $service) { $blockers.Add("Windows service $TargetServiceName was not found.") }

$defaultsFile = if ($service) { Get-DefaultsFileFromImagePath -ImagePath $service.PathName } else { $null }
$serverExecutable = if ($service -and $service.PathName -match '^\s*"([^"]+mysqld\.exe)"') { $Matches[1] } elseif ($service -and $service.PathName -match '^\s*([^\s]+mysqld\.exe)') { $Matches[1] } else { $null }

$sourceListener = Get-Listener -Port $SourcePort
$targetListener = Get-Listener -Port $TargetPort
$legacyService = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction SilentlyContinue
$original3306Listeners = @(Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue)
$sourceListenerRows = @(Get-NetTCPConnection -State Listen -LocalPort $SourcePort -ErrorAction SilentlyContinue)
$targetListenerRows = @(Get-NetTCPConnection -State Listen -LocalPort $TargetPort -ErrorAction SilentlyContinue)
$sourceVersion = if ($sourceListener) { Get-MySqlHandshakeVersion -HostName $SourceHost -Port $SourcePort } else { $null }
$targetVersion = if ($targetListener) { Get-MySqlHandshakeVersion -HostName $TargetHost -Port $TargetPort } else { $null }

if (-not $legacyService -or $legacyService.State -ne 'Stopped' -or $original3306Listeners.Count -gt 0) { $blockers.Add('The original MySQL 5.5 service and port 3306 must remain stopped.') }
if (-not $sourceListener) { $blockers.Add('The isolated MySQL 5.5 recovery copy is not listening on port 3309.') }
elseif ($sourceVersion -ne '5.5.62') { $blockers.Add("Unexpected recovery-source version: $sourceVersion") }
if (@($sourceListenerRows | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }).Count -gt 0) { $blockers.Add('The MySQL 5.5 recovery copy has a non-loopback listener.') }
if (-not $targetListener) { $blockers.Add('MySQL 8 target is not listening on port 3307.') }
elseif ($targetVersion -notmatch '^8\.0\.') { $blockers.Add("Unexpected target version: $targetVersion") }
if (@($targetListenerRows | Where-Object { $_.LocalAddress -notin @('127.0.0.1', '::1') }).Count -gt 0) { $blockers.Add('MySQL 8 has a non-loopback listener; remote exposure is forbidden.') }

if ($service -and $targetListener) {
    $ownedByService = Test-ProcessDescendantOf -ProcessId ([int]$targetListener.ProcessId) -AncestorProcessId ([int]$service.ProcessId)
    if ($service.State -ne 'Running' -or -not $ownedByService) {
        $blockers.Add('Port 3307 is not owned by the running MySQL80Test Windows service; the listener origin must be confirmed.')
    }
}

$configuration = [ordered]@{}
foreach ($name in @('port', 'bind-address', 'basedir', 'datadir', 'tmpdir', 'log-error', 'skip-networking', 'shared-memory')) {
    $configuration[$name] = if ($defaultsFile) { Get-IniOption -Path $defaultsFile -Name $name } else { $null }
}
if ($configuration.port -and [int]$configuration.port -ne 3307) { $blockers.Add('MySQL80Test configuration does not use port 3307.') }
if ($configuration.'bind-address' -and $configuration.'bind-address' -notin @('127.0.0.1', 'localhost')) { $blockers.Add('MySQL80Test is not bound exclusively to the local interface.') }
if ($configuration.'skip-networking' -eq $true -or "$($configuration.'skip-networking')" -match '^(?i)(1|on|true|yes)$') { $blockers.Add('skip-networking is enabled for MySQL80Test.') }
if (-not $configuration.port -or -not $configuration.'bind-address' -or -not $configuration.datadir -or -not $configuration.'log-error') { $blockers.Add('MySQL80Test is missing a required explicit port, bind-address, datadir, or log-error setting.') }
if (-not $configuration.tmpdir) { $blockers.Add('MySQL80Test tmpdir is not explicit, so service-account write access cannot be proven.') }
if ($configuration.datadir -and -not [IO.Path]::IsPathRooted($configuration.datadir)) { $blockers.Add('MySQL80Test datadir must be an absolute path.') }
if ($configuration.tmpdir -and -not [IO.Path]::IsPathRooted($configuration.tmpdir)) { $blockers.Add('MySQL80Test tmpdir must be an absolute path.') }

$dataDirectory = $configuration.datadir
$logPath = $configuration.'log-error'
if ($logPath -and -not [IO.Path]::IsPathRooted($logPath) -and $dataDirectory) { $logPath = Join-Path $dataDirectory $logPath }

$dataAccess = 'NOT_CONFIGURED'
$aclReadable = $false
$dataAclEvidence = $null
$tmpAclEvidence = $null
if ($dataDirectory) {
    try {
        $dataAccess = if (Test-Path -LiteralPath $dataDirectory) { 'EXISTS' } else { 'NOT_FOUND' }
        [void](Get-Acl -LiteralPath $dataDirectory -ErrorAction Stop)
        $aclReadable = $true
    }
    catch {
        $dataAccess = 'ACCESS_DENIED'
        $blockers.Add('The current user cannot verify the MySQL 8 data directory or its ACL.')
    }
}
if ($service) {
    $dataAclEvidence = Get-ServiceAccountAclEvidence -Path $dataDirectory -Account $service.StartName
    $tmpAclEvidence = Get-ServiceAccountAclEvidence -Path $configuration.tmpdir -Account $service.StartName
    if ($dataAclEvidence.Status -eq 'READABLE' -and -not $dataAclEvidence.ModifyAllowed) { $blockers.Add('The MySQL80Test service account has no directly verifiable Modify access to datadir.') }
    if ($configuration.tmpdir -and ($tmpAclEvidence.Status -ne 'READABLE' -or -not $tmpAclEvidence.ModifyAllowed)) { $blockers.Add('The MySQL80Test service account has no directly verifiable Modify access to tmpdir.') }
}

$configValidation = Invoke-ConfigValidation -ServerExecutable $serverExecutable -DefaultsFile $defaultsFile
if ($configValidation.Status -ne 'PASS') { $blockers.Add('MySQL 8 configuration validation did not pass.') }
$errorLog = Get-ErrorLogSummary -Path $logPath
if ($errorLog.Status -ne 'READABLE') { $blockers.Add("The MySQL 8 error log is not readable ($($errorLog.Status)), so the service failure root cause is unverified.") }

$report = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    mode = 'Preflight'
    status = if ($blockers.Count -eq 0) { 'PASS' } else { 'BLOCKED' }
    source = [ordered]@{
        mode = $SourceMode
        host = $SourceHost
        port = $SourcePort
        listenerProcessId = if ($sourceListener) { $sourceListener.ProcessId } else { $null }
        serverVersion = $sourceVersion
        originalServiceState = if ($legacyService) { $legacyService.State } else { 'NOT_FOUND' }
        originalPort3306Listening = ($original3306Listeners.Count -gt 0)
    }
    target = [ordered]@{
        host = $TargetHost
        port = $TargetPort
        listenerProcessId = if ($targetListener) { $targetListener.ProcessId } else { $null }
        serverVersion = $targetVersion
        serviceName = $TargetServiceName
        serviceState = if ($service) { $service.State } else { 'NOT_FOUND' }
        serviceProcessId = if ($service) { $service.ProcessId } else { $null }
        serviceAccount = if ($service) { $service.StartName } else { $null }
        defaultsFile = $defaultsFile
        configuration = $configuration
        dataDirectoryAccess = $dataAccess
        dataDirectoryAclReadable = $aclReadable
        serviceAccountDataAcl = $dataAclEvidence
        serviceAccountTmpAcl = $tmpAclEvidence
        configValidation = $configValidation
        errorLogSummary = $errorLog
    }
    aclChangesApplied = $false
    blockers = @($blockers)
}

$reportPath = Join-Path $OutputDirectory 'mysql8-preflight.json'
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "Preflight status: $($report.status)" -ForegroundColor $(if ($report.status -eq 'PASS') { 'Green' } else { 'Yellow' })
Write-Host "Audit report: $reportPath"
foreach ($blocker in $blockers) { Write-Warning $blocker }

if ($blockers.Count -gt 0) { exit 2 }
exit 0
