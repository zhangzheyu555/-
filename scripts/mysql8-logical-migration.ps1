[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'DryRun', 'Import', 'Validate', 'Cutover', 'Rollback', 'RollbackPlan')]
    [string]$Mode = 'Preflight',
    [ValidatePattern('^[A-Za-z0-9_]*$')]
    [string]$SourceDatabase,
    [ValidatePattern('^[A-Za-z0-9_]*$')]
    [string]$TargetDatabase = 'store_profit_mysql8_final',
    [string]$SourceHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$SourcePort = 3309,
    [string]$TargetHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$TargetPort = 3307,
    [string]$SourceUser,
    [string]$SourceCredentialFile,
    [string]$StageUser,
    [string]$StageCredentialFile,
    [string]$RehearsalUser,
    [string]$RehearsalCredentialFile,
    [Alias('TargetAdminUser')]
    [string]$TargetUser,
    [string]$TargetCredentialFile,
    [string]$RunRoot,
    [string]$RawDumpPath,
    [string]$RawDumpSha256,
    [string]$CompatibleDumpPath,
    [string]$ApprovedTransformManifest,
    [ValidateSet('PreV37', 'PostV37')]
    [string]$ValidationPhase = 'PreV37',
    [string]$ApprovedPreV37ValidationReport,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedPreV37ValidationReportSha256,
    [string]$ApprovedV37DifferenceEvidence,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedV37DifferenceEvidenceSha256,
    [string]$ApprovedValidationReport,
    [string]$ApprovedV37RepairReceipt,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedV37RepairReceiptSha256,
    [string]$ApprovedSensitiveCredentialRotationReceipt,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedSensitiveCredentialRotationReceiptSha256
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$preflightScript = Join-Path $PSScriptRoot 'mysql8-migration-preflight.ps1'
$validateScript = Join-Path $PSScriptRoot 'mysql8-validate-migration.ps1'
$migrationDirectory = Join-Path $projectRoot 'backend\src\main\resources\db\migration'
$approvedRawDumpSha256 = 'A3C791A54E232066B96C40703B60CB0A797F35829AB94A05915AA71614410077'
$approvedCompatibleDumpSha256 = 'E8180C1E74698191C2CCA3DEFE600C0093C323327B347C4CEFA0AC1CB6909E3F'
$approvedTransformManifestSha256 = 'BFC8C79A91DBE822E4DF5E285825E5A8267794E1B6138CB02E01644D50A11C94'
$finalTargetDatabase = 'store_profit_mysql8_final'
$preservedEvidenceDatabase = 'store_profit_mysql8'
$stageDatabase = 'store_profit_mysql8_final_stage_import'
$rehearsalDatabase = 'store_profit_mysql8_final_rehearsal'
$importReceiptSchema = 'ai-profit-os-mysql8-import/v1'
$sourceClientPath = 'D:\Program Files\bin\mysql.exe'
$sourceDumpPath = 'D:\Program Files\bin\mysqldump.exe'
$targetClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$targetDumpPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$sourceClientSha256 = 'B026C3CFB7EAB28E08B3467A44716699A7340CFBA778B480E79C58226DD19E03'
$sourceDumpSha256 = 'AD497FAC9198ED9ED3D6324C510D65606A807777E7EBD31A44B8C6DFD0A08823'
$targetClientSha256 = 'DB5440EA2E7F27A1F5DE1C1DA04AD5480DAD4832804E884F75BC2957F4D8E814'
$targetDumpSha256 = 'ADCAFCB9D489115AEB32419FB3E3F428F2D4DACE3A625DBC2714388B93C1DB5A'
$clearedPasswordMarker = 'MIGRATION_CLEARED_REAUTH_REQUIRED'
$sensitiveKvKeys = @('accounts', 'app_pin', 'passwords', 'tokens')

function Assert-LocalEndpoints {
    if ($SourceHost -cne '127.0.0.1' -or $SourcePort -ne 3309) { throw 'Source must be exactly the isolated read-only recovery copy at 127.0.0.1:3309.' }
    if ($TargetHost -cne '127.0.0.1' -or $TargetPort -ne 3307) { throw 'Target must be exactly 127.0.0.1:3307.' }
    $legacy = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction SilentlyContinue
    if (-not $legacy -or $legacy.State -ne 'Stopped') { throw 'The original MySQL 5.5 service MySQL must remain Stopped.' }
    if (@(Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue).Count -ne 0) { throw 'Port 3306 must have no listener.' }
    if (@(Get-NetTCPConnection -State Listen -LocalPort 3309 -ErrorAction SilentlyContinue).Count -eq 0) { throw 'The isolated read-only recovery source is not listening on port 3309.' }
}

function Assert-FinalTargetDatabase {
    if ($TargetDatabase -cne $finalTargetDatabase) { throw "Target database is fixed to $finalTargetDatabase." }
    if ($TargetDatabase -ceq $preservedEvidenceDatabase) { throw 'The preserved evidence database must never be used as an import target.' }
}

function Resolve-ApprovedImportEvidence {
    if (-not $RawDumpPath -or -not $CompatibleDumpPath -or -not $ApprovedTransformManifest) {
        throw 'Import requires the original dump, compatible copy, and transformation manifest.'
    }
    if ($RawDumpSha256 -and $RawDumpSha256.ToUpperInvariant() -ne $approvedRawDumpSha256) {
        throw 'RawDumpSha256 is not the hard-bound original dump hash.'
    }
    $repositoryPrefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    $raw = [IO.Path]::GetFullPath($RawDumpPath)
    $compatible = [IO.Path]::GetFullPath($CompatibleDumpPath)
    $manifestPath = [IO.Path]::GetFullPath($ApprovedTransformManifest)
    foreach ($path in @($raw, $compatible, $manifestPath)) {
        if ($path.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Import evidence must remain outside the Git workspace.' }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'One or more approved import evidence files are missing.' }
    }
    if ($raw -ieq $compatible) { throw 'The original dump and compatible copy must be distinct files.' }
    $rawHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $raw).Hash
    $compatibleHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $compatible).Hash
    $manifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).Hash
    if ($rawHash -cne $approvedRawDumpSha256) { throw 'Original dump SHA-256 is not the approved immutable evidence hash.' }
    if ($compatibleHash -cne $approvedCompatibleDumpSha256) { throw 'Compatible dump SHA-256 is not the approved import hash.' }
    if ($manifestHash -cne $approvedTransformManifestSha256) { throw 'Transformation manifest SHA-256 is not the approved manifest hash.' }

    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifestRaw = [IO.Path]::GetFullPath([string]$manifest.RawPath)
    $manifestCompatible = [IO.Path]::GetFullPath([string]$manifest.CompatiblePath)
    if ($manifestRaw -ine $raw -or $manifestCompatible -ine $compatible -or
        [string]$manifest.RawSha256 -cne $approvedRawDumpSha256 -or
        [string]$manifest.CompatibleSha256 -cne $approvedCompatibleDumpSha256 -or
        [string]$manifest.Rule -cne 'Replace one zero TIMESTAMP default with CURRENT_TIMESTAMP' -or
        [string]$manifest.AffectedTable -cne 'platform_webhook_event' -or
        [string]$manifest.AffectedColumn -cne 'last_received_at' -or
        [int]$manifest.ReplacementCount -ne 1 -or [int]$manifest.AffectedDataRows -ne 0 -or
        [bool]$manifest.RawModified) {
        throw 'Transformation manifest does not describe the single approved zero-default conversion.'
    }

    $forbidden = Select-String -LiteralPath $compatible -Encoding UTF8 -Pattern @(
        '(?i)^\s*(?:CREATE|DROP)\s+DATABASE\b',
        '(?i)^\s*USE\s+',
        '(?i)`store_profit_mysql8`\s*\.',
        '(?i)`store_profit_mysql8_final`\s*\.',
        '(?i)\bstore_profit_mysql8\s*\.',
        '(?i)\bstore_profit_mysql8_final\s*\.'
    ) | Select-Object -First 1
    if ($forbidden) { throw 'Compatible dump contains a database-selection or evidence-database-qualified statement.' }

    [pscustomobject]@{
        RawPath = $raw
        RawSha256 = $rawHash
        CompatiblePath = $compatible
        CompatibleSha256 = $compatibleHash
        ManifestPath = $manifestPath
        ManifestSha256 = $manifestHash
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
    if ($fullPath.StartsWith($rootPath, [StringComparison]::OrdinalIgnoreCase)) { throw 'Migration artifacts must be outside the Git workspace.' }
    if (Test-Path -LiteralPath $fullPath) {
        if (@(Get-ChildItem -LiteralPath $fullPath -Force -ErrorAction Stop).Count -ne 0) { throw 'Migration artifact directory already exists and is not empty.' }
    }
    else { [void](New-Item -ItemType Directory -Path $fullPath -Force) }
    Set-CurrentUserOnlyAcl -Path $fullPath -Container
    $fullPath
}

function ConvertFrom-SecureValue {
    param([Parameter(Mandatory)][Security.SecureString]$Value)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Import-CurrentUserDpapiCredential {
    param([Parameter(Mandatory)][string]$Path, [string]$ExpectedUserName, [Parameter(Mandatory)][string]$Label)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $repositoryPrefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    if ($fullPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Label DPAPI credential file must remain outside the Git workspace." }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw "$Label DPAPI credential file was not found." }
    $item = Get-Item -LiteralPath $fullPath -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label DPAPI credential file must not be a reparse point." }
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $fullPath
    $ownerSid = if ($acl.Owner -match '^S-1-') { [Security.Principal.SecurityIdentifier]::new($acl.Owner) } else { ([Security.Principal.NTAccount]$acl.Owner).Translate([Security.Principal.SecurityIdentifier]) }
    if ($ownerSid -ne $currentSid) { throw "$Label DPAPI credential file must be owned by the current Windows user." }
    $rules = $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
    foreach ($rule in $rules) {
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and $rule.IdentityReference -ne $currentSid) { throw "$Label DPAPI credential file grants access to another identity." }
    }
    $credential = Import-Clixml -LiteralPath $fullPath
    if ($credential -isnot [Management.Automation.PSCredential]) { throw "$Label DPAPI file is not a PSCredential exported by the current Windows user." }
    if ($ExpectedUserName -and $credential.UserName -cne $ExpectedUserName) { throw "$Label DPAPI username differs from the explicitly requested MySQL username." }
    $credential
}

function New-MySqlOptionFile {
    param([string]$HostName, [int]$Port, [string]$UserName, [string]$Prompt, [string]$Directory, [string]$CredentialFile)
    if ($UserName -and $UserName -notmatch '^[A-Za-z0-9_.@-]+$') { throw 'The MySQL username contains unsupported characters.' }
    $credential = if ($CredentialFile) {
        Import-CurrentUserDpapiCredential -Path $CredentialFile -ExpectedUserName $UserName -Label $Prompt
    }
    elseif ($UserName) {
        Get-Credential -UserName $UserName -Message "$Prompt (credentials are hidden and used only for this run)"
    }
    else { Get-Credential -Message "$Prompt (credentials are hidden and used only for this run)" }
    if (-not $credential) { throw 'Credential entry was cancelled.' }
    $UserName = $credential.UserName
    if ($UserName -notmatch '^[A-Za-z0-9_.@-]+$') { throw 'The MySQL username contains unsupported characters.' }
    $secure = $credential.Password
    $plain = ConvertFrom-SecureValue -Value $secure
    try {
        if ($plain -match "[`r`n]") { throw 'The password cannot contain a line break.' }
        $escaped = $plain.Replace('\', '\\').Replace('"', '\"')
        $path = Join-Path $Directory ([IO.Path]::GetRandomFileName())
        $content = "[client]`r`nprotocol=TCP`r`nhost=$HostName`r`nport=$Port`r`nuser=$UserName`r`npassword=`"$escaped`"`r`ndefault-character-set=utf8mb4`r`n"
        [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
        Set-CurrentUserOnlyAcl -Path $path
        $path
    }
    finally { $plain = $null; $secure.Dispose(); $credential = $null }
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

function Assert-PinnedTool {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Sha256, [Parameter(Mandatory)][string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is missing from its approved absolute path." }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
    if ($actual -cne $Sha256) { throw "$Label SHA-256 differs from the locally verified approved binary." }
    $Path
}

function Resolve-PinnedTools {
    [pscustomobject]@{
        SourceClient = Assert-PinnedTool -Path $sourceClientPath -Sha256 $sourceClientSha256 -Label 'MySQL 5.5 mysql.exe'
        SourceDump = Assert-PinnedTool -Path $sourceDumpPath -Sha256 $sourceDumpSha256 -Label 'MySQL 5.5 mysqldump.exe'
        TargetClient = Assert-PinnedTool -Path $targetClientPath -Sha256 $targetClientSha256 -Label 'MySQL 8 mysql.exe'
        TargetDump = Assert-PinnedTool -Path $targetDumpPath -Sha256 $targetDumpSha256 -Label 'MySQL 8 mysqldump.exe'
    }
}

function Invoke-MySqlQuery {
    param([string]$Client, [string]$OptionFile, [string]$Sql)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Client
    $startInfo.Arguments = "--defaults-extra-file=`"$OptionFile`" --batch --raw --skip-column-names"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($Sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "MySQL command failed with exit code $($process.ExitCode): $(Protect-Text $stderr)" }
        @($stdout -split "`r?`n" | Where-Object { $_ -ne '' })
    }
    finally {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        $process.Dispose()
    }
}

function Protect-Text {
    param([string]$Value)
    if ($null -eq $Value) { return '' }
    $safe = $Value
    $safe = $safe -replace '(?i)((?:password|token|secret|api[_-]?key|access[_-]?token|authorization)\s*[=:]\s*)\S+', '$1<redacted>'
    $safe = $safe -replace '(?i)(jdbc:mysql://)([^\s/@:]+):([^\s/@]+)@', '$1<user>:<redacted>@'
    $safe = $safe -replace "'[^'`r`n]{1,512}'", "'<value>'"
    $safe.Trim()
}

function Get-TextSha256 {
    param([string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes([string]$Value)))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Get-ZeroDateCount {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $columns = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='$Database' AND data_type IN ('date','datetime','timestamp') ORDER BY table_name, ordinal_position;"
    $queries = [Collections.Generic.List[string]]::new()
    foreach ($line in $columns) {
        $cells = $line -split "`t", 2
        if ($cells.Count -ne 2 -or $cells[0] -notmatch '^[A-Za-z0-9_]+$' -or $cells[1] -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe date-column metadata returned by the source.' }
        $queries.Add("SELECT COUNT(*) FROM ``$Database``.``$($cells[0])`` WHERE CAST(``$($cells[1])`` AS CHAR) LIKE '0000-00-00%'")
    }
    $total = 0L
    if ($queries.Count -gt 0) {
        foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql (($queries -join ' UNION ALL ') + ';')) { $total += [int64]$line }
    }
    $total
}

function Normalize-ComparablePath {
    param([string]$Path)
    if (-not $Path -or -not [IO.Path]::IsPathRooted($Path)) { return $null }
    ([IO.Path]::GetFullPath($Path)).TrimEnd('\', '/')
}

function Assert-SourceCompatibility {
    param(
        [string]$SourceClient,
        [string]$SourceOptionFile,
        [string]$TargetClient,
        [string]$TargetOptionFile,
        [string]$Database,
        [string]$ExpectedTargetDataDirectory,
        [string]$ExpectedTargetTempDirectory,
        [string]$OutputPath
    )
    $blockers = [Collections.Generic.List[string]]::new()
    $zeroDates = Get-ZeroDateCount -Client $SourceClient -OptionFile $SourceOptionFile -Database $Database
    if ($zeroDates -gt 0) { $blockers.Add('Source zero dates require an explicit conversion copy before import.') }

    $sourceSqlMode = Invoke-MySqlQuery -Client $SourceClient -OptionFile $SourceOptionFile -Sql 'SELECT @@SESSION.sql_mode;' | Select-Object -First 1
    $targetSqlMode = Invoke-MySqlQuery -Client $TargetClient -OptionFile $TargetOptionFile -Sql 'SELECT @@SESSION.sql_mode;' | Select-Object -First 1
    $removedModes = @('NO_AUTO_CREATE_USER', 'MYSQL323', 'MYSQL40', 'NO_FIELD_OPTIONS', 'NO_KEY_OPTIONS', 'NO_TABLE_OPTIONS')
    $sourceModes = @(([string]$sourceSqlMode).ToUpperInvariant() -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if (@($sourceModes | Where-Object { $_ -in $removedModes }).Count -gt 0) { $blockers.Add('Source SQL_MODE contains a mode removed from MySQL 8.') }

    $definers = @(Invoke-MySqlQuery -Client $SourceClient -OptionFile $SourceOptionFile -Sql @"
SELECT definer FROM information_schema.views WHERE table_schema='$Database'
UNION SELECT definer FROM information_schema.routines WHERE routine_schema='$Database'
UNION SELECT definer FROM information_schema.triggers WHERE trigger_schema='$Database'
UNION SELECT definer FROM information_schema.events WHERE event_schema='$Database';
"@ | Sort-Object -Unique)
    if ($definers.Count -gt 0) { $blockers.Add('Source programmable objects contain DEFINER clauses; validate them with a separately approved, scoped compatibility plan rather than reading mysql.user.') }

    $sourceIdentifiers = @(Invoke-MySqlQuery -Client $SourceClient -OptionFile $SourceOptionFile -Sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$Database' UNION SELECT column_name FROM information_schema.columns WHERE table_schema='$Database';" | ForEach-Object { $_.ToUpperInvariant() } | Sort-Object -Unique)
    $reservedWords = @(Invoke-MySqlQuery -Client $TargetClient -OptionFile $TargetOptionFile -Sql "SELECT WORD FROM information_schema.keywords WHERE RESERVED=1;" | ForEach-Object { $_.ToUpperInvariant() })
    $reservedIdentifiers = @($sourceIdentifiers | Where-Object { $reservedWords -contains $_ })
    if ($reservedIdentifiers.Count -gt 0) { $blockers.Add('Source table or column identifiers collide with MySQL 8 reserved words.') }

    $runtime = Invoke-MySqlQuery -Client $TargetClient -OptionFile $TargetOptionFile -Sql 'SELECT @@port, @@datadir, @@tmpdir;' | Select-Object -First 1
    $runtimeCells = $runtime -split "`t", 3
    if ($runtimeCells.Count -ne 3 -or [int]$runtimeCells[0] -ne 3307) { $blockers.Add('Authenticated target runtime identity does not report port 3307.') }
    $runtimeData = if ($runtimeCells.Count -ge 2) { Normalize-ComparablePath $runtimeCells[1] } else { $null }
    $runtimeTemp = if ($runtimeCells.Count -ge 3) { Normalize-ComparablePath $runtimeCells[2] } else { $null }
    $expectedData = Normalize-ComparablePath $ExpectedTargetDataDirectory
    $expectedTemp = Normalize-ComparablePath $ExpectedTargetTempDirectory
    if (-not $runtimeData -or -not $expectedData -or $runtimeData -ine $expectedData) { $blockers.Add('Authenticated @@datadir does not match the preflight service configuration.') }
    if (-not $runtimeTemp -or -not $expectedTemp -or $runtimeTemp -ine $expectedTemp) { $blockers.Add('Authenticated @@tmpdir does not match the preflight service configuration.') }

    $objects = [ordered]@{}
    foreach ($line in Invoke-MySqlQuery -Client $SourceClient -OptionFile $SourceOptionFile -Sql @"
SELECT 'BASE_TABLE', COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_type='BASE TABLE'
UNION ALL SELECT 'VIEW', COUNT(*) FROM information_schema.views WHERE table_schema='$Database'
UNION ALL SELECT 'TRIGGER', COUNT(*) FROM information_schema.triggers WHERE trigger_schema='$Database'
UNION ALL SELECT 'ROUTINE', COUNT(*) FROM information_schema.routines WHERE routine_schema='$Database'
UNION ALL SELECT 'EVENT', COUNT(*) FROM information_schema.events WHERE event_schema='$Database';
"@) {
        $cells = $line -split "`t", 2
        if ($cells.Count -eq 2) { $objects[$cells[0]] = [int64]$cells[1] }
    }
    $result = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        status = if ($blockers.Count -eq 0) { 'PASS' } else { 'BLOCKED' }
        zeroDateCount = $zeroDates
        sourceSqlModePresent = [bool]$sourceSqlMode
        targetSqlModePresent = [bool]$targetSqlMode
        definerCount = $definers.Count
        missingDefinerCount = $missingDefiners.Count
        reservedIdentifierCount = $reservedIdentifiers.Count
        objectCounts = $objects
        runtimePort = if ($runtimeCells.Count -ge 1) { $runtimeCells[0] } else { $null }
        runtimeDataDirectoryMatches = ($runtimeData -and $expectedData -and $runtimeData -ieq $expectedData)
        runtimeTempDirectoryMatches = ($runtimeTemp -and $expectedTemp -and $runtimeTemp -ieq $expectedTemp)
        blockers = @($blockers)
    }
    $result | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
    if ($blockers.Count -gt 0) { throw 'Source compatibility preflight is BLOCKED. No target database was created.' }
}

function Invoke-Dump {
    param([string]$Executable, [string]$OptionFile, [string[]]$Arguments, [string]$LogPath)
    $quoted = @("--defaults-extra-file=`"$OptionFile`"") + $Arguments
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Executable
    $startInfo.Arguments = $quoted -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($LogPath, (Protect-Text ($stdout + "`r`n" + $stderr)), [Text.UTF8Encoding]::new($false))
        if ($process.ExitCode -ne 0) { throw "mysqldump failed with exit code $($process.ExitCode). See the restricted log." }
    }
    finally {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        $process.Dispose()
    }
}

function Invoke-Import {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string]$SqlPath, [string]$LogPath)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Client
    $startInfo.Arguments = "--defaults-extra-file=`"$OptionFile`" --binary-mode=1 --database=`"$Database`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $file = $null
    $started = $false
    $copyError = $null
    try {
        [void]$process.Start()
        $started = $true
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $file = [IO.File]::OpenRead($SqlPath)
        try { $file.CopyTo($process.StandardInput.BaseStream) } catch { $copyError = $_ }
        try { $process.StandardInput.Close() } catch {}
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($LogPath, (Protect-Text ($stdout + "`r`n" + $stderr)), [Text.UTF8Encoding]::new($false))
        if ($copyError) { throw 'MySQL 8 import input stream ended early. See the restricted, redacted log for the server error.' }
        if ($process.ExitCode -ne 0) { throw "MySQL 8 import failed with exit code $($process.ExitCode). See the restricted log." }
    }
    finally {
        if ($file) { $file.Dispose() }
        if ($started) { try { if (-not $process.HasExited) { $process.Kill() } } catch {} }
        $process.Dispose()
    }
}

if (-not ('FlywayChecksum' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
public static class FlywayChecksum {
    public static int Calculate(string path) {
        uint crc = 0xFFFFFFFFu;
        foreach (string line in File.ReadLines(path, new UTF8Encoding(false, true))) {
            byte[] bytes = Encoding.UTF8.GetBytes(line);
            foreach (byte value in bytes) {
                crc ^= value;
                for (int bit = 0; bit < 8; bit++) crc = (crc & 1u) != 0 ? (crc >> 1) ^ 0xEDB88320u : crc >> 1;
            }
        }
        return unchecked((int)(crc ^ 0xFFFFFFFFu));
    }
}
'@
}

function Assert-FlywayChain {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $history = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT version, script, checksum, success FROM ``$Database``.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank;"
    if (-not $history) { throw 'Source database has no Flyway history.' }
    $local = @{}
    foreach ($file in Get-ChildItem -LiteralPath $migrationDirectory -Filter 'V*__*.sql' -File) { $local[$file.Name] = $file.FullName }
    $highest = $null
    foreach ($line in $history) {
        $cells = $line -split "`t", 4
        if ($cells.Count -ne 4 -or $cells[3] -ne '1') { throw 'Source Flyway history contains an unsuccessful or malformed migration.' }
        $highest = $cells[0]
        $script = $cells[1]
        if (-not $local.ContainsKey($script)) { throw "Flyway BLOCKED: applied migration $($cells[0]) is absent from the MySQL source tree." }
        if ($cells[2] -ne 'NULL' -and $cells[2] -ne '') {
            $actual = [FlywayChecksum]::Calculate($local[$script])
            if ([int]$cells[2] -ne $actual) { throw "Flyway BLOCKED: checksum mismatch for applied migration $($cells[0])." }
        }
    }
    $highest
}

function Invoke-Preflight {
    param([string]$Output)
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $preflightScript -SourceMode RecoveryCopy -SourceHost $SourceHost -SourcePort $SourcePort -TargetHost $TargetHost -TargetPort $TargetPort -OutputDirectory $Output
    if ($LASTEXITCODE -ne 0) { throw 'Preflight is BLOCKED. No database backup, import, or cutover was performed.' }
}

function Assert-ApprovedCutover {
    if (-not $ApprovedValidationReport -or -not (Test-Path -LiteralPath $ApprovedValidationReport)) { throw 'Cutover requires a prior PASS validation report.' }
    $approval = Get-Content -LiteralPath $ApprovedValidationReport -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($approval.status -ne 'PASS') { throw 'The referenced validation report is not PASS.' }
    $first = Read-Host 'Type CUTOVER to authorize a fresh final logical migration'
    if ($first -cne 'CUTOVER') { throw 'Cutover was not authorized.' }
    $second = Read-Host "Type CUTOVER $TargetDatabase to confirm the clean target database"
    if ($second -cne "CUTOVER $TargetDatabase") { throw 'Second cutover confirmation failed.' }
}

function Invoke-Rollback {
    Write-Host 'Rollback does not copy MySQL 8 data back to MySQL 5.5.' -ForegroundColor Yellow
    $old = @(Get-NetTCPConnection -State Listen -LocalPort 18081 -ErrorAction SilentlyContinue)
    if ($old) {
        Write-Host 'The original backend is still listening on 18081; no process or database change is required.' -ForegroundColor Green
        return
    }
    throw 'The old 18081 backend is not running. Restart requires a separate secure credential prompt and verified old Jar; no automatic guess was attempted.'
}

function Assert-ScopedTargetAccount {
    param([string]$Client, [string]$OptionFile, [string]$CurrentUser, [Parameter(Mandatory)][string]$ExpectedDatabase)
    if ($CurrentUser -notmatch '^([^@]+)@(.+)$') { throw 'CURRENT_USER() returned an unexpected account identity.' }
    $accountName = $Matches[1]
    $accountHost = $Matches[2]
    if ($accountName -ieq 'root') { throw 'The target migration account must not be root.' }
    if ($accountHost -eq '%' -or $accountHost -notin @('127.0.0.1', 'localhost')) { throw 'The target migration account must be bound to 127.0.0.1 or localhost, never %. ' }
    $grants = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql 'SHOW GRANTS FOR CURRENT_USER();')
    if ($grants.Count -eq 0) { throw 'The target migration account has no inspectable grants.' }
    $scopedGrantSeen = $false
    foreach ($grant in $grants) {
        if ($grant -match '(?i)\bWITH\s+GRANT\s+OPTION\b') { throw 'The target migration account must not have GRANT OPTION.' }
        if ($grant -match '(?i)^GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+') { continue }
        if ($grant -match '(?i)\sON\s+(`?[^`\s.]+`?)\.\*\s+TO\s+') {
            $grantIdentifier = $Matches[1]
            if (-not ($grantIdentifier.StartsWith('`') -and $grantIdentifier.EndsWith('`'))) { throw 'Database-scoped grants must use an escaped quoted database identifier.' }
            $grantBody = $grantIdentifier.Trim('`')
            if ($grantBody -match '(?<!\\)[_%]') { throw 'The migration account grant contains an unescaped database wildcard.' }
            $grantedDatabase = [regex]::Replace($grantBody, '\\+([_%])', '$1').Replace('\\', '\')
            if ($grantedDatabase -cne $ExpectedDatabase) { throw 'The migration account has a cross-database or global grant.' }
            $scopedGrantSeen = $true
            continue
        }
        throw 'The target migration account has an unsupported global, role, table, or cross-database grant.'
    }
    if (-not $scopedGrantSeen) { throw "The migration account requires a database-scoped grant on $ExpectedDatabase only." }
    [pscustomobject]@{ hostLocal = $true; databaseScope = $ExpectedDatabase; globalPrivileges = $false; crossDatabasePrivileges = $false; accountIdentitySha256 = Get-TextSha256 -Value $CurrentUser }
}

function Clear-ImportedSensitiveCredentials {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $marker = $clearedPasswordMarker.Replace("'", "''")
    $kvList = ($sensitiveKvKeys | ForEach-Object { "'$(($_).Replace("'", "''"))'" }) -join ','
    $state = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql @"
USE ``$Database``;
START TRANSACTION;
UPDATE auth_user SET password_hash='$marker';
SET @cleared_password_rows = ROW_COUNT();
DELETE FROM auth_token;
SET @cleared_token_rows = ROW_COUNT();
UPDATE platform_account SET password_cipher=NULL WHERE password_cipher IS NOT NULL;
SET @cleared_platform_secret_rows = ROW_COUNT();
DELETE FROM kv_storage WHERE LOWER(storage_key) IN ($kvList);
SET @cleared_sensitive_kv_rows = ROW_COUNT();
COMMIT;
SELECT
  @cleared_password_rows,
  @cleared_token_rows,
  @cleared_platform_secret_rows,
  @cleared_sensitive_kv_rows,
  (SELECT COUNT(*) FROM auth_user),
  (SELECT COUNT(*) FROM auth_user WHERE password_hash='$marker'),
  (SELECT COUNT(*) FROM auth_token),
  (SELECT COUNT(*) FROM platform_account WHERE password_cipher IS NOT NULL),
  (SELECT COUNT(*) FROM kv_storage WHERE LOWER(storage_key) IN ($kvList));
"@ | Select-Object -Last 1
    $cells = $state -split "`t", 9
    if ($cells.Count -ne 9 -or [int64]$cells[4] -ne [int64]$cells[5] -or [int64]$cells[6] -ne 0 -or [int64]$cells[7] -ne 0 -or [int64]$cells[8] -ne 0) {
        throw 'Imported password hashes, tokens, platform credentials, or sensitive legacy KV records were not fully cleared.'
    }
    [ordered]@{
        contract = 'AI_PROFIT_OS_SENSITIVE_CLEARANCE_V1'
        passwordRowsCleared = [int64]$cells[0]
        tokenRowsDeleted = [int64]$cells[1]
        platformSecretRowsCleared = [int64]$cells[2]
        sensitiveKvRowsDeleted = [int64]$cells[3]
        authUsersDisabledPendingFormalRotation = [int64]$cells[5]
        remainingTokenRows = [int64]$cells[6]
        remainingPlatformSecretRows = [int64]$cells[7]
        remainingSensitiveKvRows = [int64]$cells[8]
    }
}

function Get-SensitiveCredentialClearanceState {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $marker = $clearedPasswordMarker.Replace("'", "''")
    $kvList = ($sensitiveKvKeys | ForEach-Object { "'$(($_).Replace("'", "''"))'" }) -join ','
    $state = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql @"
SELECT
  (SELECT COUNT(*) FROM ``$Database``.auth_user),
  (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE password_hash='$marker'),
  (SELECT COUNT(*) FROM ``$Database``.auth_token),
  (SELECT COUNT(*) FROM ``$Database``.platform_account WHERE password_cipher IS NOT NULL),
  (SELECT COUNT(*) FROM ``$Database``.kv_storage WHERE LOWER(storage_key) IN ($kvList));
"@ | Select-Object -First 1
    $cells = $state -split "`t", 5
    if ($cells.Count -ne 5 -or [int64]$cells[0] -ne [int64]$cells[1] -or [int64]$cells[2] -ne 0 -or [int64]$cells[3] -ne 0 -or [int64]$cells[4] -ne 0) {
        throw "$Database does not satisfy the fixed sensitive-credential clearance contract."
    }
    [ordered]@{ contract = 'AI_PROFIT_OS_SENSITIVE_CLEARANCE_V1'; authUserCount = [int64]$cells[0]; clearedPasswordMarkerCount = [int64]$cells[1]; authTokenCount = [int64]$cells[2]; platformSecretCount = [int64]$cells[3]; sensitiveKvCount = [int64]$cells[4] }
}

function Get-PreV37ImportState {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $line = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql @"
SELECT
  (SELECT COUNT(*) FROM ``$Database``.flyway_schema_history WHERE version IS NOT NULL),
  (SELECT COUNT(*) FROM ``$Database``.flyway_schema_history WHERE version REGEXP '^[0-9]+$' AND CAST(version AS UNSIGNED) BETWEEN 1 AND 36 AND success=1),
  (SELECT COUNT(*) FROM ``$Database``.flyway_schema_history WHERE version='37' AND checksum=1160061988 AND success=0),
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_name IN ('permission_catalog','user_permission_override','user_data_scope')),
  (SELECT (SELECT COUNT(*) FROM ``$Database``.permission_catalog)+(SELECT COUNT(*) FROM ``$Database``.user_permission_override)+(SELECT COUNT(*) FROM ``$Database``.user_data_scope)),
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$Database' AND table_name IN ('auth_user','auth_token') AND column_name='permission_version'),
  (SELECT (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE permission_version IS NULL OR permission_version<>1)+(SELECT COUNT(*) FROM ``$Database``.auth_token WHERE permission_version IS NULL OR permission_version<>1));
"@ | Select-Object -First 1
    $cells = $line -split "`t", 7
    if ($cells.Count -ne 7 -or [int]$cells[0] -ne 37 -or [int]$cells[1] -ne 36 -or [int]$cells[2] -ne 1 -or [int]$cells[3] -ne 3 -or [int]$cells[4] -ne 0 -or [int]$cells[5] -ne 2 -or [int]$cells[6] -ne 0) {
        throw "$Database is not the exact known failed-V37 PreV37 state."
    }
    [ordered]@{ flywayRows = 37; successfulV1ToV36Rows = 36; failedV37Rows = 1; failedV37Checksum = 1160061988; failedV37Tables = 3; failedV37TableRows = 0; permissionVersionColumns = 2; nonDefaultPermissionVersionRows = 0 }
}

function Import-RecoveredDump {
    Assert-LocalEndpoints
    Assert-FinalTargetDatabase
    $evidence = Resolve-ApprovedImportEvidence
    $tools = Resolve-PinnedTools
    $service = Get-CimInstance Win32_Service -Filter "Name='MySQL80Test'" -ErrorAction Stop
    if ($service.State -ne 'Running') { throw 'MySQL80Test is not running.' }
    if (-not (Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue | Where-Object { $_.LocalAddress -in @('127.0.0.1', '::1') })) { throw 'MySQL 8 is not listening locally on port 3307.' }

    if (-not $RunRoot) { $script:RunRoot = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql8-target-import\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
    $script:RunRoot = Initialize-RestrictedDirectory -Path $RunRoot
    foreach ($directory in @('logs', 'sanitized', '.credentials')) { [void](New-Item -ItemType Directory -Path (Join-Path $RunRoot $directory) -Force) }
    $credentialDirectory = Join-Path $RunRoot '.credentials'
    $stageOption = $null
    $rehearsalOption = $null
    $targetOption = $null
    $createdStage = $false
    $stageSanitized = $false
    $createdRehearsal = $false
    $createdTarget = $false
    try {
        $stageOption = New-MySqlOptionFile -HostName $TargetHost -Port $TargetPort -UserName $StageUser -CredentialFile $StageCredentialFile -Prompt 'Stage-only MySQL 8 migration account' -Directory $credentialDirectory
        $rehearsalOption = New-MySqlOptionFile -HostName $TargetHost -Port $TargetPort -UserName $RehearsalUser -CredentialFile $RehearsalCredentialFile -Prompt 'Rehearsal-only MySQL 8 migration account' -Directory $credentialDirectory
        $targetOption = New-MySqlOptionFile -HostName $TargetHost -Port $TargetPort -UserName $TargetUser -CredentialFile $TargetCredentialFile -Prompt 'Final-only MySQL 8 migration account' -Directory $credentialDirectory
        $stageIdentity = Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $stageOption -Sql 'SELECT VERSION(), @@port, CURRENT_USER(), @@global.read_only;' | Select-Object -First 1
        $stageIdentityCells = $stageIdentity -split "`t", 4
        if ($stageIdentityCells.Count -ne 4 -or $stageIdentityCells[0] -ne '8.0.46' -or [int]$stageIdentityCells[1] -ne 3307 -or [int]$stageIdentityCells[3] -ne 0) { throw 'Stage account is not connected to writable MySQL 8.0.46 on port 3307.' }
        $stageAccountScope = Assert-ScopedTargetAccount -Client $tools.TargetClient -OptionFile $stageOption -CurrentUser $stageIdentityCells[2] -ExpectedDatabase $stageDatabase
        $rehearsalIdentity = Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $rehearsalOption -Sql 'SELECT VERSION(), @@port, CURRENT_USER(), @@global.read_only;' | Select-Object -First 1
        $rehearsalIdentityCells = $rehearsalIdentity -split "`t", 4
        if ($rehearsalIdentityCells.Count -ne 4 -or $rehearsalIdentityCells[0] -ne '8.0.46' -or [int]$rehearsalIdentityCells[1] -ne 3307 -or [int]$rehearsalIdentityCells[3] -ne 0) { throw 'Rehearsal account is not connected to writable MySQL 8.0.46 on port 3307.' }
        $rehearsalAccountScope = Assert-ScopedTargetAccount -Client $tools.TargetClient -OptionFile $rehearsalOption -CurrentUser $rehearsalIdentityCells[2] -ExpectedDatabase $rehearsalDatabase
        $targetIdentity = Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql 'SELECT VERSION(), @@port, CURRENT_USER(), @@global.read_only, @@datadir, @@tmpdir, @@sql_mode, @@lower_case_table_names, @@max_allowed_packet;' | Select-Object -First 1
        $targetIdentityCells = $targetIdentity -split "`t", 9
        if ($targetIdentityCells.Count -ne 9 -or $targetIdentityCells[0] -ne '8.0.46' -or [int]$targetIdentityCells[1] -ne 3307 -or [int]$targetIdentityCells[3] -ne 0) { throw 'Final account is not connected to writable MySQL 8.0.46 on port 3307.' }
        $accountScope = Assert-ScopedTargetAccount -Client $tools.TargetClient -OptionFile $targetOption -CurrentUser $targetIdentityCells[2] -ExpectedDatabase $finalTargetDatabase

        $stageExists = [int](Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $stageOption -Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$stageDatabase';" | Select-Object -First 1)
        $rehearsalExists = [int](Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $rehearsalOption -Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$rehearsalDatabase';" | Select-Object -First 1)
        $finalExists = [int](Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$TargetDatabase';" | Select-Object -First 1)
        if ($stageExists -ne 0) { throw 'The fixed stage database already exists; retain it as evidence and do not overwrite it.' }
        if ($rehearsalExists -ne 0) { throw 'The fixed rehearsal database already exists and will not be overwritten.' }
        if ($finalExists -ne 0) { throw 'The final target database already exists and will not be overwritten.' }

        Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $stageOption -Sql "CREATE DATABASE ``$stageDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
        $createdStage = $true
        Invoke-Import -Client $tools.TargetClient -OptionFile $stageOption -Database $stageDatabase -SqlPath $evidence.CompatiblePath -LogPath (Join-Path $RunRoot 'logs\stage-import.log')
        $stageSensitiveClearance = Clear-ImportedSensitiveCredentials -Client $tools.TargetClient -OptionFile $stageOption -Database $stageDatabase
        $stageClearanceState = Get-SensitiveCredentialClearanceState -Client $tools.TargetClient -OptionFile $stageOption -Database $stageDatabase
        $stageSanitized = $true
        $stagePreV37State = Get-PreV37ImportState -Client $tools.TargetClient -OptionFile $stageOption -Database $stageDatabase

        $sanitizedDumpPath = Join-Path $RunRoot 'sanitized\store_profit_mysql8_final-sanitized.sql'
        Invoke-Dump -Executable $tools.TargetDump -OptionFile $stageOption -Arguments @('--single-transaction','--quick','--routines','--events','--triggers','--hex-blob','--column-statistics=0','--set-gtid-purged=OFF','--no-tablespaces',"--result-file=`"$sanitizedDumpPath`"",$stageDatabase) -LogPath (Join-Path $RunRoot 'logs\sanitized-dump.log')
        $sanitizedDumpSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sanitizedDumpPath).Hash
        $sanitizedDumpSize = (Get-Item -LiteralPath $sanitizedDumpPath).Length
        if ($sanitizedDumpSize -le 0) { throw 'Sanitized logical dump is empty.' }
        (Get-Item -LiteralPath $sanitizedDumpPath).IsReadOnly = $true
        $sanitizedManifestPath = Join-Path $RunRoot 'sanitized\manifest.json'
        $sanitizedManifest = [ordered]@{
            generatedAt = (Get-Date).ToString('o')
            generator = [ordered]@{ executable = $tools.TargetDump; sha256 = $targetDumpSha256 }
            sourceStage = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $stageDatabase; accountScope = $stageAccountScope }
            targetDatabases = @($TargetDatabase, $rehearsalDatabase)
            sanitizedDump = [ordered]@{ path = $sanitizedDumpPath; sha256 = $sanitizedDumpSha256; size = $sanitizedDumpSize }
            clearance = $stageClearanceState
            rawCredentialMaterialRecorded = $false
        }
        $sanitizedManifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $sanitizedManifestPath -Encoding UTF8
        $sanitizedManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sanitizedManifestPath).Hash
        (Get-Item -LiteralPath $sanitizedManifestPath).IsReadOnly = $true

        Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql "CREATE DATABASE ``$TargetDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
        $createdTarget = $true
        Invoke-Import -Client $tools.TargetClient -OptionFile $targetOption -Database $TargetDatabase -SqlPath $sanitizedDumpPath -LogPath (Join-Path $RunRoot 'logs\final-import.log')
        $finalClearanceState = Get-SensitiveCredentialClearanceState -Client $tools.TargetClient -OptionFile $targetOption -Database $TargetDatabase
        $finalPreV37State = Get-PreV37ImportState -Client $tools.TargetClient -OptionFile $targetOption -Database $TargetDatabase
        Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $rehearsalOption -Sql "CREATE DATABASE ``$rehearsalDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null
        $createdRehearsal = $true
        Invoke-Import -Client $tools.TargetClient -OptionFile $rehearsalOption -Database $rehearsalDatabase -SqlPath $sanitizedDumpPath -LogPath (Join-Path $RunRoot 'logs\rehearsal-import.log')
        $rehearsalClearanceState = Get-SensitiveCredentialClearanceState -Client $tools.TargetClient -OptionFile $rehearsalOption -Database $rehearsalDatabase
        $rehearsalPreV37State = Get-PreV37ImportState -Client $tools.TargetClient -OptionFile $rehearsalOption -Database $rehearsalDatabase
        $postIdentity = Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql "USE ``$TargetDatabase``; SELECT VERSION(), @@port, DATABASE(), CURRENT_USER();" | Select-Object -Last 1
        $postIdentityCells = $postIdentity -split "`t", 4
        if ($postIdentityCells.Count -ne 4 -or $postIdentityCells[0] -ne '8.0.46' -or [int]$postIdentityCells[1] -ne 3307 -or $postIdentityCells[2] -cne $TargetDatabase -or $postIdentityCells[3] -cne $targetIdentityCells[2]) {
            throw 'Post-import VERSION(), @@port, DATABASE(), or CURRENT_USER() identity is not the approved target.'
        }
        $objectCounts = Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TargetDatabase' AND table_type='BASE TABLE';" | Select-Object -First 1
        $stageTableCount = [int](Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $stageOption -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$stageDatabase' AND table_type='BASE TABLE';" | Select-Object -First 1)
        $rehearsalTableCount = [int](Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $rehearsalOption -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$rehearsalDatabase' AND table_type='BASE TABLE';" | Select-Object -First 1)
        if ([int]$objectCounts -ne 73 -or $stageTableCount -ne 73 -or $rehearsalTableCount -ne 73) { throw 'Stage, final, and rehearsal must each contain exactly 73 base tables.' }
        $flywayRows = @(Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql "SELECT version, script, checksum, success FROM ``$TargetDatabase``.flyway_schema_history ORDER BY installed_rank;")
        $importReceiptPath = Join-Path $RunRoot 'mysql8-import-receipt.json'
        $importReceipt = [ordered]@{
            schemaVersion = $importReceiptSchema
            generator = 'scripts/mysql8-logical-migration.ps1'
            generatedAt = (Get-Date).ToString('o')
            status = 'PASS'
            taskOwnership = [ordered]@{
                stageDidNotExistBefore = ($stageExists -eq 0)
                finalDidNotExistBefore = ($finalExists -eq 0)
                rehearsalDidNotExistBefore = ($rehearsalExists -eq 0)
                stageCreatedByTask = $createdStage
                finalCreatedByTask = $createdTarget
                rehearsalCreatedByTask = $createdRehearsal
                preservedEvidenceDatabaseAccessed = $false
                preservedEvidenceDatabaseModified = $false
            }
            sourceEvidence = [ordered]@{ rawDumpSha256 = $evidence.RawSha256; compatibleDumpSha256 = $evidence.CompatibleSha256; transformManifestSha256 = $evidence.ManifestSha256; rawDumpImported = $false; compatibleDumpImportedOnlyIntoStage = $true }
            toolchain = [ordered]@{
                mysql = [ordered]@{ path = $tools.TargetClient; sha256 = $targetClientSha256 }
                mysqldump = [ordered]@{ path = $tools.TargetDump; sha256 = $targetDumpSha256 }
            }
            stage = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $stageDatabase; tableCount = $stageTableCount; accountScope = $stageAccountScope; credentialBearingImportReceived = $true; clearedBeforeSanitizedDump = $true; clearance = $stageClearanceState; preV37State = $stagePreV37State; retainedUntilFinalValidation = $true }
            sanitizedDump = [ordered]@{ path = $sanitizedDumpPath; sha256 = $sanitizedDumpSha256; size = $sanitizedDumpSize; manifestPath = $sanitizedManifestPath; manifestSha256 = $sanitizedManifestSha256; readOnly = $true }
            final = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $TargetDatabase; tableCount = [int]$objectCounts; accountScope = $accountScope; clearance = $finalClearanceState; preV37State = $finalPreV37State; importSourceSha256 = $sanitizedDumpSha256 }
            rehearsal = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $rehearsalDatabase; tableCount = $rehearsalTableCount; accountScope = $rehearsalAccountScope; clearance = $rehearsalClearanceState; preV37State = $rehearsalPreV37State; importSourceSha256 = $sanitizedDumpSha256 }
            invariants = [ordered]@{ exactly73TablesEach = $true; sameSanitizedDumpForFinalAndRehearsal = $true; credentialBearingSourceImportedOnlyIntoStage = $true; rawCredentialsNeverEnteredFinalOrRehearsal = $true; callerToolOverrideAccepted = $false }
            credentialsPersisted = $false
        }
        $importReceipt | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $importReceiptPath -Encoding UTF8
        $importReceiptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $importReceiptPath).Hash
        (Get-Item -LiteralPath $importReceiptPath).IsReadOnly = $true
        $summary = [ordered]@{
            generatedAt = (Get-Date).ToString('o')
            status = 'IMPORTED_NOT_CUTOVER'
            target = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $postIdentityCells[2]; version = $postIdentityCells[0]; accountIdentitySha256 = $accountScope.accountIdentitySha256; datadir = $targetIdentityCells[4]; tmpdir = $targetIdentityCells[5]; lowerCaseTableNames = $targetIdentityCells[7]; maxAllowedPacket = $targetIdentityCells[8]; accountScope = $accountScope }
            stage = [ordered]@{ database = $stageDatabase; retainedUntilFinalValidation = $true; originalCredentialsClearedBeforeSanitizedDump = $true; accountScope = $stageAccountScope; clearance = $stageSensitiveClearance }
            originalDump = [ordered]@{ path = $evidence.RawPath; sha256 = $evidence.RawSha256; size = (Get-Item -LiteralPath $evidence.RawPath).Length; imported = $false }
            compatibleDump = [ordered]@{ path = $evidence.CompatiblePath; sha256 = $evidence.CompatibleSha256; size = (Get-Item -LiteralPath $evidence.CompatiblePath).Length; importedIntoStage = $true; importedIntoFinal = $false; importedIntoRehearsal = $false }
            transformationManifest = [ordered]@{ path = $evidence.ManifestPath; sha256 = $evidence.ManifestSha256 }
            preservedEvidenceDatabase = [ordered]@{ database = $preservedEvidenceDatabase; accessedByScript = $false; modifiedByScript = $false }
            sanitizedImportEvidence = [ordered]@{ dump = [ordered]@{ path = $sanitizedDumpPath; sha256 = $sanitizedDumpSha256; size = $sanitizedDumpSize }; manifest = [ordered]@{ path = $sanitizedManifestPath; sha256 = $sanitizedManifestSha256 }; finalClearance = $finalClearanceState }
            importReceipt = [ordered]@{ path = $importReceiptPath; sha256 = $importReceiptSha256; schemaVersion = $importReceiptSchema }
            pinnedTools = [ordered]@{ mysql8ClientSha256 = $targetClientSha256; mysql8DumpSha256 = $targetDumpSha256; mysql55ClientSha256 = $sourceClientSha256; mysql55DumpSha256 = $sourceDumpSha256 }
            importedTableCount = [int]$objectCounts
            flywayHistory = $flywayRows
            backendSwitched = $false
        }
        $summaryPath = Join-Path $RunRoot 'target-import-summary.json'
        $summary | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
        [pscustomobject]@{ status = 'IMPORTED_NOT_CUTOVER'; stageDatabase = $stageDatabase; targetDatabase = $TargetDatabase; rehearsalDatabase = $rehearsalDatabase; tableCount = [int]$objectCounts; runRoot = $RunRoot; summaryPath = $summaryPath; importReceipt = $importReceiptPath; importReceiptSha256 = $importReceiptSha256; sanitizedDumpSha256 = $sanitizedDumpSha256; sensitiveCredentialsClearedBeforeFinalImport = $true }
    }
    catch {
        if ($createdRehearsal -and $rehearsalOption) {
            try { Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $rehearsalOption -Sql "DROP DATABASE IF EXISTS ``$rehearsalDatabase``;" | Out-Null } catch { Write-Warning 'The task-created rehearsal database could not be removed after failure.' }
        }
        if ($createdTarget -and $targetOption) {
            try { Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $targetOption -Sql "DROP DATABASE IF EXISTS ``$TargetDatabase``;" | Out-Null } catch { Write-Warning 'The task-created target database could not be removed after failure; existing databases were not touched.' }
        }
        if ($createdStage -and -not $stageSanitized -and $stageOption) {
            try { Invoke-MySqlQuery -Client $tools.TargetClient -OptionFile $stageOption -Sql "DROP DATABASE IF EXISTS ``$stageDatabase``;" | Out-Null } catch { Write-Warning 'The unsanitized task-created stage database could not be removed after failure; restrict access and clear it before any retry.' }
        }
        throw
    }
    finally {
        Remove-CredentialFile -Path $stageOption
        Remove-CredentialFile -Path $rehearsalOption
        Remove-CredentialFile -Path $targetOption
        Remove-Item -LiteralPath $credentialDirectory -Force -ErrorAction SilentlyContinue
    }
}

if ($Mode -eq 'Import') { Import-RecoveredDump | ConvertTo-Json -Compress; exit 0 }
if ($Mode -eq 'RollbackPlan') {
    [pscustomobject]@{ status = 'PLAN_ONLY'; steps = @('Stop only a verified task-owned backend connected to 3307.', 'Do not copy MySQL 8 files to MySQL 5.5.', 'If 3307 has accepted business writes, require a separate data-reconciliation plan before any rollback.', 'Keep the original MySQL 5.5 datadir and offline recovery copy unchanged.') } | ConvertTo-Json -Depth 4
    exit 0
}
Assert-LocalEndpoints
if ($Mode -eq 'Cutover') {
    throw 'Cutover is fail-closed in this release: authoritative MySQL V37, Jar/Flyway repeat-start validation, scoped application accounts, browser approval binding, and an audited service-switch state machine are not yet available.'
}
if ($Mode -eq 'Rollback') {
    throw 'Rollback is fail-closed in this release: no cutover state was created, and the script will not guess credentials, stop an unverified PID, or copy MySQL 8 data back to MySQL 5.5.'
}
if ($Mode -eq 'Preflight') {
    $path = if ($RunRoot) { $RunRoot } else { Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql55-to-mysql8\preflight-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
    Invoke-Preflight -Output $path
    exit 0
}
if ($Mode -eq 'Validate') {
    if (-not $SourceDatabase -or -not $TargetDatabase) { throw 'Validate requires SourceDatabase and TargetDatabase.' }
    Assert-FinalTargetDatabase
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $validateScript, '-SourceDatabase', $SourceDatabase, '-TargetDatabase', $TargetDatabase, '-SourceHost', $SourceHost, '-SourcePort', "$SourcePort", '-TargetHost', $TargetHost, '-TargetPort', "$TargetPort", '-Phase', $ValidationPhase)
    if ($SourceUser) { $arguments += @('-SourceUser', $SourceUser) }
    if ($SourceCredentialFile) { $arguments += @('-SourceCredentialFile', $SourceCredentialFile) }
    if ($TargetUser) { $arguments += @('-TargetUser', $TargetUser) }
    if ($TargetCredentialFile) { $arguments += @('-TargetCredentialFile', $TargetCredentialFile) }
    if ($RunRoot) { $arguments += @('-OutputDirectory', $RunRoot) }
    if ($ApprovedTransformManifest) { $arguments += @('-ApprovedTransformManifest', $ApprovedTransformManifest) }
    if ($ApprovedPreV37ValidationReport) { $arguments += @('-ApprovedPreV37Report', $ApprovedPreV37ValidationReport) }
    if ($ApprovedPreV37ValidationReportSha256) { $arguments += @('-ApprovedPreV37ReportSha256', $ApprovedPreV37ValidationReportSha256) }
    if ($ApprovedV37DifferenceEvidence) { $arguments += @('-ApprovedV37DifferenceEvidence', $ApprovedV37DifferenceEvidence) }
    if ($ApprovedV37DifferenceEvidenceSha256) { $arguments += @('-ApprovedV37DifferenceEvidenceSha256', $ApprovedV37DifferenceEvidenceSha256) }
    if ($ApprovedV37RepairReceipt) { $arguments += @('-ApprovedV37RepairReceipt', $ApprovedV37RepairReceipt) }
    if ($ApprovedV37RepairReceiptSha256) { $arguments += @('-ApprovedV37RepairReceiptSha256', $ApprovedV37RepairReceiptSha256) }
    if ($ApprovedSensitiveCredentialRotationReceipt) { $arguments += @('-ApprovedSensitiveCredentialRotationReceipt', $ApprovedSensitiveCredentialRotationReceipt) }
    if ($ApprovedSensitiveCredentialRotationReceiptSha256) { $arguments += @('-ApprovedSensitiveCredentialRotationReceiptSha256', $ApprovedSensitiveCredentialRotationReceiptSha256) }
    & powershell.exe @arguments
    exit $LASTEXITCODE
}

if ($Mode -eq 'DryRun') {
    throw 'The legacy DryRun import path is disabled. Use Preflight, then the hard-bound Import evidence package, then PreV37/PostV37 validation.'
}

throw 'No executable path is defined for the requested mode.'
