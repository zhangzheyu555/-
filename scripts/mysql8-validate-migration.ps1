[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$SourceDatabase,
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$TargetDatabase = 'store_profit_mysql8_final',
    [string]$SourceHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$SourcePort = 3309,
    [string]$TargetHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$TargetPort = 3307,
    [string]$SourceUser,
    [string]$SourceCredentialFile,
    [string]$TargetUser,
    [string]$TargetCredentialFile,
    [string]$ApprovedTransformManifest,
    [ValidateSet('PreV37', 'PostV37')]
    [string]$Phase = 'PreV37',
    [string]$ApprovedPreV37Report,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedPreV37ReportSha256,
    [string]$ApprovedV37DifferenceEvidence,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedV37DifferenceEvidenceSha256,
    [string]$ApprovedV37RepairReceipt,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedV37RepairReceiptSha256,
    [string]$ApprovedSensitiveCredentialRotationReceipt,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedSensitiveCredentialRotationReceiptSha256,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$script:approvedTransformEnabled = $false
$script:finalTargetDatabase = 'store_profit_mysql8_final'
$script:approvedRawDumpSha256 = 'A3C791A54E232066B96C40703B60CB0A797F35829AB94A05915AA71614410077'
$script:approvedCompatibleDumpSha256 = 'E8180C1E74698191C2CCA3DEFE600C0093C323327B347C4CEFA0AC1CB6909E3F'
$script:approvedTransformManifestSha256 = 'BFC8C79A91DBE822E4DF5E285825E5A8267794E1B6138CB02E01644D50A11C94'
$script:approvedV37MigrationSha256 = '75712C479633E0180FB8178AC72AB32DB29B71CCB8CB94457B0B84820E25A06F'
$script:approvedV37FlywayChecksum = 761638840
$script:expectedTableCount = 73
$script:v37NewTables = @('permission_catalog', 'user_data_scope', 'user_permission_override')
$script:v37DataChangedTables = @('auth_token', 'auth_user', 'business_todo', 'flyway_schema_history', 'role_permission', 'todo_escalation')
$script:v37SchemaChangedTables = @('auth_token', 'auth_user')
$script:sourceClientPath = 'D:\Program Files\bin\mysql.exe'
$script:targetClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$script:sourceClientSha256 = 'B026C3CFB7EAB28E08B3467A44716699A7340CFBA778B480E79C58226DD19E03'
$script:targetClientSha256 = 'DB5440EA2E7F27A1F5DE1C1DA04AD5480DAD4832804E884F75BC2957F4D8E814'
$script:clearedPasswordMarker = 'MIGRATION_CLEARED_REAUTH_REQUIRED'
$script:sensitiveExclusionContractId = 'AI_PROFIT_OS_PREV37_BUSINESS_FINGERPRINT_V1'
$script:sensitiveExclusionContractSha256 = 'F90DEFF0FADE08CF4850161D7CD1B0521EA9016E9FB23C88D0894B82B48F9019'
$script:sensitiveColumnExclusions = [ordered]@{
    auth_user = @('password_hash')
    auth_token = @('token')
    platform_account = @('password_cipher')
}
$script:sensitiveWholeTableExclusions = @('auth_token')
$script:sensitiveKvKeys = @('accounts', 'app_pin', 'passwords', 'tokens')
$script:sensitiveNamePattern = '(?i)(password|passphrase|token|secret|api_?key|session|credential|private_?key|cipher)'
$script:rotationReceiptSchema = 'ai-profit-os-sensitive-credential-rotation/v1'
$script:rotationReceiptGenerator = 'AuthService+PasswordService+UserManagementService.resetPassword'
$script:v37TransitionSchema = 'ai-profit-os-v37-repair-transition/v1'
$script:v37TransitionGenerator = 'scripts/mysql8-repair-v37.ps1'
$script:v37RepairReceiptSchema = 'ai-profit-os-v37-repair-apply-receipt/exact-v1'
$script:v37RepairGeneratorSha256 = 'B409A87E220BA1857C1D385A024C7584A44FDA3AF1104778BCA5B27B71FC744E'
$script:importReceiptSchema = 'ai-profit-os-mysql8-import/v1'
$script:importReceiptGenerator = 'scripts/mysql8-logical-migration.ps1'
$script:importReceiptGeneratorSha256 = 'B2EFA719D007C927455F6FA0D94F1E7A72825C0E82733A198276804F749B2CD7'
$script:stageDatabase = 'store_profit_mysql8_final_stage_import'
$script:rehearsalDatabase = 'store_profit_mysql8_final_rehearsal'
$script:mysql8DumpPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$script:mysql8DumpSha256 = 'ADCAFCB9D489115AEB32419FB3E3F428F2D4DACE3A625DBC2714388B93C1DB5A'
$script:mavenPath = 'D:\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin\mvn.cmd'
$script:mavenSha256 = 'F89D866139ADD674690BBA0702A0CC4F9769276362C4DA01F96267AB85F6487E'
$script:flywayPluginPath = 'C:\Users\34706\.m2\repository\org\flywaydb\flyway-maven-plugin\11.7.2\flyway-maven-plugin-11.7.2.jar'
$script:flywayPluginSha256 = '4CEEAF2FF4ECC65A3EFF31294DCB832C361FA9DDE0F23BA129447FB1E4B6CBFB'

function Assert-Endpoint {
    param([string]$HostName, [int]$Port, [int]$ExpectedPort, [string]$Label)
    if ($HostName -cne '127.0.0.1') { throw "$Label endpoint must be exactly 127.0.0.1." }
    if ($Label -eq 'Source') {
        if ($Port -ne 3309) { throw 'Source endpoint must be the isolated read-only recovery copy on port 3309.' }
        $legacy = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction SilentlyContinue
        if (-not $legacy -or $legacy.State -ne 'Stopped') { throw 'The original MySQL 5.5 service MySQL must remain Stopped.' }
        if (@(Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue).Count -ne 0) { throw 'Port 3306 must have no listener.' }
        if (@(Get-NetTCPConnection -State Listen -LocalPort 3309 -ErrorAction SilentlyContinue).Count -eq 0) { throw 'The isolated recovery source is not listening on port 3309.' }
    }
    elseif ($Port -ne $ExpectedPort) {
        throw "$Label endpoint must remain 127.0.0.1:$ExpectedPort."
    }
}

function Assert-FinalTargetDatabase {
    if ($TargetDatabase -cne $script:finalTargetDatabase) { throw "Target database is fixed to $($script:finalTargetDatabase)." }
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
        throw 'Validation output must be outside the Git workspace.'
    }
    [void](New-Item -ItemType Directory -Path $fullPath -Force)
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
    param(
        [string]$HostName,
        [int]$Port,
        [string]$UserName,
        [string]$Prompt,
        [string]$Directory,
        [string]$CredentialFile
    )
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
    finally {
        $plain = $null
        if ($secure) { $secure.Dispose() }
        $credential = $null
    }
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

function Protect-Text {
    param([string]$Value)
    if ($null -eq $Value) { return '' }
    $safe = $Value
    $safe = $safe -replace '(?i)((?:password|token|secret|api[_-]?key|access[_-]?token|authorization)\s*[=:]\s*)\S+', '$1<redacted>'
    $safe = $safe -replace '(?i)(jdbc:mysql://)([^\s/@:]+):([^\s/@]+)@', '$1<user>:<redacted>@'
    $safe = $safe -replace "'[^'`r`n]{1,512}'", "'<value>'"
    $safe.Trim()
}

function Get-Sha256ForLines {
    param([AllowEmptyCollection()][string[]]$Lines)
    $bytes = [Text.Encoding]::UTF8.GetBytes((@($Lines) -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function ConvertTo-PropertyMap {
    param($Object)
    $result = [ordered]@{}
    if ($null -eq $Object) { return $result }
    foreach ($property in $Object.PSObject.Properties) { $result[$property.Name] = $property.Value }
    $result
}

function Assert-ExactNameSet {
    param([string[]]$Actual, [string[]]$Expected, [string]$Label)
    $actualValue = (@($Actual) | Sort-Object) -join "`n"
    $expectedValue = (@($Expected) | Sort-Object) -join "`n"
    if ($actualValue -cne $expectedValue) { throw "$Label is not the exact approved set." }
}

function Resolve-ApprovedExternalFile {
    param([string]$Path, [string]$ExpectedSha256, [string]$Label, [switch]$RequireReadOnly)
    if (-not $Path -or -not $ExpectedSha256) { throw "$Label path and SHA-256 are required." }
    $fullPath = [IO.Path]::GetFullPath($Path)
    $repositoryPrefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    if ($fullPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Label must remain outside the Git workspace." }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw "$Label was not found." }
    if ($RequireReadOnly -and -not (Get-Item -LiteralPath $fullPath).IsReadOnly) { throw "$Label must be read-only." }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $fullPath).Hash
    if ($actual -cne $ExpectedSha256.ToUpperInvariant()) { throw "$Label SHA-256 does not match its separately supplied approval hash." }
    [pscustomobject]@{ Path = $fullPath; Sha256 = $actual }
}

function Read-ApprovedTransformManifest {
    if (-not $ApprovedTransformManifest) { throw 'The hard-bound transformation manifest is required.' }
    $manifestFile = Resolve-ApprovedExternalFile -Path $ApprovedTransformManifest -ExpectedSha256 $script:approvedTransformManifestSha256 -Label 'Transformation manifest'
    $transform = Get-Content -LiteralPath $manifestFile.Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$transform.RawSha256 -cne $script:approvedRawDumpSha256 -or
        [string]$transform.CompatibleSha256 -cne $script:approvedCompatibleDumpSha256 -or
        [string]$transform.Rule -cne 'Replace one zero TIMESTAMP default with CURRENT_TIMESTAMP' -or
        [string]$transform.AffectedTable -cne 'platform_webhook_event' -or
        [string]$transform.AffectedColumn -cne 'last_received_at' -or
        [int]$transform.ReplacementCount -ne 1 -or [int]$transform.AffectedDataRows -ne 0 -or
        [bool]$transform.RawModified -or
        -not (Test-Path -LiteralPath $transform.RawPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $transform.CompatiblePath -PathType Leaf)) {
        throw 'Transformation manifest is not the approved single zero-default conversion.'
    }
    $rawPath = [IO.Path]::GetFullPath([string]$transform.RawPath)
    $compatiblePath = [IO.Path]::GetFullPath([string]$transform.CompatiblePath)
    $repositoryPrefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
    if ($rawPath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        $compatiblePath.StartsWith($repositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Dump evidence referenced by the transform manifest must remain outside Git.' }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $rawPath).Hash -cne $script:approvedRawDumpSha256 -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $compatiblePath).Hash -cne $script:approvedCompatibleDumpSha256) {
        throw 'Transformation manifest referenced files do not match the hard-bound dump hashes.'
    }
    $script:approvedTransformEnabled = $true
    [pscustomobject]@{ Path = $manifestFile.Path; Sha256 = $manifestFile.Sha256 }
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
        if ($process.ExitCode -ne 0) {
            throw "MySQL query failed with exit code $($process.ExitCode): $(Protect-Text $stderr)"
        }
        @($stdout -split "`r?`n" | Where-Object { $_ -ne '' })
    }
    finally {
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        $process.Dispose()
    }
}

function Escape-SqlLiteral {
    param([string]$Value)
    $Value.Replace('\', '\\').Replace("'", "''")
}

function Assert-ScopedTargetAccount {
    param([string]$Client, [string]$OptionFile, [string]$CurrentUser)
    if ($CurrentUser -notmatch '^([^@]+)@(.+)$') { throw 'CURRENT_USER() returned an unexpected target identity.' }
    $accountName = $Matches[1]
    $accountHost = $Matches[2]
    if ($accountName -ieq 'root') { throw 'Target validation refuses root.' }
    if ($accountHost -eq '%' -or $accountHost -notin @('127.0.0.1','localhost')) { throw 'Target validation requires a local account Host, never %. ' }
    $grants = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql 'SHOW GRANTS FOR CURRENT_USER();')
    if ($grants.Count -eq 0) { throw 'The target account has no inspectable grants.' }
    $scopedGrantSeen = $false
    foreach ($grant in $grants) {
        if ($grant -match '(?i)\bWITH\s+GRANT\s+OPTION\b') { throw 'Target validation refuses GRANT OPTION.' }
        if ($grant -match '(?i)^GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+') { continue }
        if ($grant -match '(?i)\sON\s+(`?[^`\s.]+`?)\.\*\s+TO\s+') {
            $grantIdentifier = $Matches[1]
            if (-not ($grantIdentifier.StartsWith('`') -and $grantIdentifier.EndsWith('`'))) { throw 'Database-scoped grants must use an escaped quoted database identifier.' }
            $grantBody = $grantIdentifier.Trim('`')
            if ($grantBody -match '(?<!\\)[_%]') { throw 'The target account grant contains an unescaped database wildcard.' }
            $grantedDatabase = [regex]::Replace($grantBody, '\\+([_%])', '$1').Replace('\\', '\')
            if ($grantedDatabase -cne $script:finalTargetDatabase) { throw 'Target validation refuses global or cross-database grants.' }
            $scopedGrantSeen = $true
            continue
        }
        throw 'Target validation refuses unsupported role, table, global, or cross-database grants.'
    }
    if (-not $scopedGrantSeen) { throw "Target account has no database-scoped grant for $($script:finalTargetDatabase)." }
    [ordered]@{ hostLocal = $true; databaseScope = $script:finalTargetDatabase; globalPrivileges = $false; crossDatabasePrivileges = $false; accountIdentitySha256 = Get-Sha256ForLines -Lines @($CurrentUser) }
}

function Get-TableNames {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT table_name FROM information_schema.tables WHERE table_schema='$db' AND table_type='BASE TABLE' ORDER BY table_name;")
}

function Get-SensitiveExclusionContract {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    $discovered = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='$db' ORDER BY table_name, ordinal_position;" | ForEach-Object {
        $cells = $_ -split "`t", 2
        if ($cells.Count -ne 2 -or $cells[0] -notmatch '^[A-Za-z0-9_]+$' -or $cells[1] -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe column metadata returned while checking the sensitive-field contract.' }
        if ($cells[1] -match $script:sensitiveNamePattern) { "$($cells[0]).$($cells[1])" }
    })
    $expected = [Collections.Generic.List[string]]::new()
    foreach ($table in $script:sensitiveColumnExclusions.Keys) {
        foreach ($column in $script:sensitiveColumnExclusions[$table]) { $expected.Add("$table.$column") }
    }
    Assert-ExactNameSet -Actual $discovered -Expected @($expected) -Label 'Sensitive credential columns'
    $contractLines = @(
        "contract=$($script:sensitiveExclusionContractId)",
        'whole-table=auth_token',
        'kv-storage-key-predicate=lower(storage_key) not in (accounts,app_pin,passwords,tokens)'
    ) + @($expected | Sort-Object | ForEach-Object { "excluded-column=$_" })
    $contractSha256 = Get-Sha256ForLines -Lines $contractLines
    if ($contractSha256 -cne $script:sensitiveExclusionContractSha256) { throw 'Built-in sensitive exclusion contract changed unexpectedly.' }
    [ordered]@{
        id = $script:sensitiveExclusionContractId
        sha256 = $contractSha256
        excludedColumns = @($expected | Sort-Object)
        excludedWholeTables = @($script:sensitiveWholeTableExclusions)
        excludedKvStorageKeys = @($script:sensitiveKvKeys)
        callerControlledExclusionsAccepted = $false
    }
}

function Get-ExactTableCounts {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    $result = [ordered]@{}
    if (-not $Tables) { return $result }
    $kvList = ($script:sensitiveKvKeys | ForEach-Object { "'$(($_).Replace("'", "''"))'" }) -join ','
    $parts = foreach ($table in $Tables) {
        if ($table -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe table identifier returned by the server.' }
        if ($script:sensitiveWholeTableExclusions -contains $table) { "SELECT '$table', 0" }
        elseif ($table -ceq 'kv_storage') { "SELECT '$table', COUNT(*) FROM ``$Database``.``$table`` WHERE LOWER(storage_key) NOT IN ($kvList)" }
        else { "SELECT '$table', COUNT(*) FROM ``$Database``.``$table``" }
    }
    foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql (($parts -join ' UNION ALL ') + ';')) {
        $cells = $line -split "`t", 2
        if ($cells.Count -eq 2) { $result[$cells[0]] = [int64]$cells[1] }
    }
    $result
}

function Get-TableDataFingerprints {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    $result = [ordered]@{}
    foreach ($table in @($Tables | Sort-Object)) {
        if ($table -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe table identifier returned by the server.' }
        if ($script:sensitiveWholeTableExclusions -contains $table) {
            $result[$table] = "0:$(Get-Sha256ForLines -Lines @())"
            continue
        }
        $columns = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT column_name FROM information_schema.columns WHERE table_schema='$(Escape-SqlLiteral $Database)' AND table_name='$(Escape-SqlLiteral $table)' ORDER BY ordinal_position;")
        if ($columns.Count -eq 0 -or @($columns | Where-Object { $_ -notmatch '^[A-Za-z0-9_]+$' }).Count -gt 0) { throw 'Table fingerprint metadata is empty or unsafe.' }
        $excludedColumns = if ($script:sensitiveColumnExclusions.Contains($table)) { @($script:sensitiveColumnExclusions[$table]) } else { @() }
        $columns = @($columns | Where-Object { $excludedColumns -notcontains $_ })
        if ($columns.Count -eq 0) { throw 'Sensitive-field exclusion removed every column from a business table fingerprint.' }
        $expressions = @($columns | ForEach-Object { "IF(``$_`` IS NULL,'N',CONCAT('V',HEX(``$_``)))" })
        $whereClause = ''
        if ($table -ceq 'kv_storage') {
            $kvList = ($script:sensitiveKvKeys | ForEach-Object { "'$(($_).Replace("'", "''"))'" }) -join ','
            $whereClause = " WHERE LOWER(storage_key) NOT IN ($kvList)"
        }
        $rows = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT CONCAT_WS('|',$($expressions -join ',')) FROM ``$Database``.``$table``$whereClause;" | Sort-Object)
        $result[$table] = "$($rows.Count):$(Get-Sha256ForLines -Lines $rows)"
    }
    $result
}

function Get-ObjectCounts {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    $sql = @"
SELECT 'BASE_TABLE', COUNT(*) FROM information_schema.tables WHERE table_schema='$db' AND table_type='BASE TABLE'
UNION ALL SELECT 'VIEW', COUNT(*) FROM information_schema.views WHERE table_schema='$db'
UNION ALL SELECT 'TRIGGER', COUNT(*) FROM information_schema.triggers WHERE trigger_schema='$db'
UNION ALL SELECT 'ROUTINE', COUNT(*) FROM information_schema.routines WHERE routine_schema='$db'
UNION ALL SELECT 'EVENT', COUNT(*) FROM information_schema.events WHERE event_schema='$db';
"@
    $result = [ordered]@{}
    foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql $sql) {
        $cells = $line -split "`t", 2
        if ($cells.Count -eq 2) { $result[$cells[0]] = [int64]$cells[1] }
    }
    $result
}

function Get-FlywayRows {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    if ($Tables -notcontains 'flyway_schema_history') { return @() }
    @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT installed_rank, version, script, checksum, success FROM ``$Database``.flyway_schema_history ORDER BY installed_rank;")
}

function Get-BusinessAggregates {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    $queries = [Collections.Generic.List[string]]::new()
    # Every branch returns the same eight-column signature:
    # domain, count, metric1..metric4, min_month, max_month.
    # HEX preserves the exact month bytes while avoiding UNION collation coercion
    # between legacy tables that use utf8mb4_general_ci and utf8mb4_unicode_ci.
    if ($Tables -contains 'profit_entry') { $queries.Add("SELECT 'profit_entry', COUNT(*), COALESCE(SUM(sales),0), COALESCE(SUM(material),0), NULL, NULL, HEX(MIN(month)), HEX(MAX(month)) FROM ``$Database``.profit_entry") }
    if ($Tables -contains 'salary_record') { $queries.Add("SELECT 'salary_record', COUNT(*), COALESCE(SUM(gross),0), COALESCE(SUM(work_hours),0), COALESCE(SUM(commission),0), NULL, HEX(MIN(month)), HEX(MAX(month)) FROM ``$Database``.salary_record") }
    if ($Tables -contains 'expense_claim') { $queries.Add("SELECT 'expense_claim', COUNT(*), COALESCE(SUM(amount),0), NULL, NULL, NULL, HEX(MIN(month)), HEX(MAX(month)) FROM ``$Database``.expense_claim") }
    if ($Tables -contains 'warehouse_stock_batch') { $queries.Add("SELECT 'warehouse_stock_batch', COUNT(*), COALESCE(SUM(quantity),0), NULL, NULL, NULL, NULL, NULL FROM ``$Database``.warehouse_stock_batch") }
    if ($Tables -contains 'store_inventory') { $queries.Add("SELECT 'store_inventory', COUNT(*), COALESCE(SUM(quantity),0), NULL, NULL, NULL, NULL, NULL FROM ``$Database``.store_inventory") }
    if ($queries.Count -eq 0) { return @() }
    @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql (($queries -join ' UNION ALL ') + ';'))
}

function Get-SensitiveCredentialState {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    foreach ($required in @('auth_user', 'auth_token', 'platform_account', 'kv_storage')) {
        if ($Tables -notcontains $required) { throw "Sensitive credential validation requires table $required." }
    }
    $marker = $script:clearedPasswordMarker.Replace("'", "''")
    $kvList = ($script:sensitiveKvKeys | ForEach-Object { "'$(($_).Replace("'", "''"))'" }) -join ','
    $factLine = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql @"
SELECT
  (SELECT COUNT(*) FROM ``$Database``.auth_user),
  (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE password_hash='$marker'),
  (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE password_hash IS NULL OR password_hash=''),
  (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE password_hash <> '$marker' AND password_hash IS NOT NULL AND password_hash <> ''),
  (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE password_hash <> '$marker' AND password_hash IS NOT NULL AND password_hash <> '' AND password_hash NOT LIKE 'pbkdf2$%'),
  (SELECT COUNT(*) FROM ``$Database``.auth_token),
  (SELECT COUNT(*) FROM ``$Database``.platform_account WHERE password_cipher IS NOT NULL AND password_cipher <> ''),
  (SELECT COUNT(*) FROM ``$Database``.kv_storage WHERE LOWER(storage_key) IN ($kvList));
"@ | Select-Object -First 1
    $cells = $factLine -split "`t", 8
    if ($cells.Count -ne 8) { throw 'Sensitive credential state query returned malformed facts.' }
    $userIds = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT id FROM ``$Database``.auth_user ORDER BY id;")
    $passwordProofRows = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT id, SHA2(password_hash,256) FROM ``$Database``.auth_user ORDER BY id;")
    [ordered]@{
        authUserCount = [int64]$cells[0]
        clearedPasswordMarkerCount = [int64]$cells[1]
        emptyPasswordCount = [int64]$cells[2]
        formallyRotatedPasswordCount = [int64]$cells[3]
        unsupportedRotatedPasswordFormatCount = [int64]$cells[4]
        authTokenCount = [int64]$cells[5]
        platformPasswordCipherCount = [int64]$cells[6]
        sensitiveKvRecordCount = [int64]$cells[7]
        authUserIdsSha256 = Get-Sha256ForLines -Lines $userIds
        passwordProofSha256 = Get-Sha256ForLines -Lines $passwordProofRows
        rawCredentialMaterialRecorded = $false
    }
}

function Read-ApprovedSensitiveRotationReceipt {
    param($TargetState, [string]$Client, [string]$OptionFile)
    if (-not $ApprovedSensitiveCredentialRotationReceipt -or -not $ApprovedSensitiveCredentialRotationReceiptSha256) {
        throw 'Target passwords are active, so a separately hashed formal AuthService/PasswordService rotation receipt is required.'
    }
    $file = Resolve-ApprovedExternalFile -Path $ApprovedSensitiveCredentialRotationReceipt -ExpectedSha256 $ApprovedSensitiveCredentialRotationReceiptSha256 -Label 'Sensitive credential rotation receipt' -RequireReadOnly
    $receipt = Get-Content -LiteralPath $file.Path -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ExactNameSet -Actual @($receipt.PSObject.Properties.Name) -Expected @('schemaVersion','generator','status','target','window','authUsers','passwords','tokens','operationLog') -Label 'Credential rotation receipt fields'
    Assert-ExactNameSet -Actual @($receipt.target.PSObject.Properties.Name) -Expected @('host','port','database') -Label 'Credential rotation target fields'
    Assert-ExactNameSet -Actual @($receipt.window.PSObject.Properties.Name) -Expected @('startedAt','completedAt') -Label 'Credential rotation window fields'
    Assert-ExactNameSet -Actual @($receipt.authUsers.PSObject.Properties.Name) -Expected @('count','idsSha256') -Label 'Credential rotation auth-user fields'
    Assert-ExactNameSet -Actual @($receipt.passwords.PSObject.Properties.Name) -Expected @('algorithm','postRotationFingerprintSha256') -Label 'Credential rotation password fields'
    Assert-ExactNameSet -Actual @($receipt.tokens.PSObject.Properties.Name) -Expected @('remaining') -Label 'Credential rotation token fields'
    Assert-ExactNameSet -Actual @($receipt.operationLog.PSObject.Properties.Name) -Expected @('action','targetType','count','targetIdsSha256') -Label 'Credential rotation audit fields'
    if ([string]$receipt.schemaVersion -cne $script:rotationReceiptSchema -or [string]$receipt.generator -cne $script:rotationReceiptGenerator -or [string]$receipt.status -cne 'PASS' -or
        [string]$receipt.target.host -cne '127.0.0.1' -or [int]$receipt.target.port -ne 3307 -or [string]$receipt.target.database -cne $script:finalTargetDatabase -or
        [int64]$receipt.authUsers.count -ne [int64]$TargetState.authUserCount -or [string]$receipt.authUsers.idsSha256 -cne [string]$TargetState.authUserIdsSha256 -or
        [string]$receipt.passwords.algorithm -cne 'PBKDF2_PASSWORD_SERVICE' -or [string]$receipt.passwords.postRotationFingerprintSha256 -cne [string]$TargetState.passwordProofSha256 -or
        [int64]$receipt.tokens.remaining -ne 0 -or [string]$receipt.operationLog.action -cne '重置账号密码' -or [string]$receipt.operationLog.targetType -cne 'auth_user') {
        throw 'Credential rotation receipt is not bound to the fixed target and current PasswordService state.'
    }
    $startedAt = [DateTimeOffset]::Parse([string]$receipt.window.startedAt).DateTime.ToString('yyyy-MM-dd HH:mm:ss')
    $completedAt = [DateTimeOffset]::Parse([string]$receipt.window.completedAt).DateTime.ToString('yyyy-MM-dd HH:mm:ss')
    $auditIds = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT DISTINCT target_id FROM ``$TargetDatabase``.operation_log WHERE action='重置账号密码' AND target_type='auth_user' AND created_at BETWEEN '$startedAt' AND '$completedAt' ORDER BY target_id;")
    if ([int64]$receipt.operationLog.count -ne $auditIds.Count -or $auditIds.Count -ne [int64]$TargetState.authUserCount -or
        [string]$receipt.operationLog.targetIdsSha256 -cne (Get-Sha256ForLines -Lines $auditIds) -or
        (Get-Sha256ForLines -Lines $auditIds) -cne [string]$TargetState.authUserIdsSha256) {
        throw 'Credential rotation audit evidence does not cover every current auth_user exactly once by identity.'
    }
    [ordered]@{ path = $file.Path; sha256 = $file.Sha256; schemaVersion = $script:rotationReceiptSchema; generator = $script:rotationReceiptGenerator }
}

function Get-EncodingSuspectCount {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    $queries = [Collections.Generic.List[string]]::new()
    foreach ($pair in @(@('brand','name'), @('store_branch','name'), @('employee','name'))) {
        if ($Tables -contains $pair[0]) {
            $queries.Add("SELECT '$($pair[0]).$($pair[1])', COUNT(*) FROM ``$Database``.``$($pair[0])`` WHERE LOCATE(0x3F, CAST(``$($pair[1])`` AS BINARY)) > 0 OR LOCATE(0xEFBFBD, CAST(``$($pair[1])`` AS BINARY)) > 0")
        }
    }
    $total = 0L
    if ($queries.Count -gt 0) {
        foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql (($queries -join ' UNION ALL ') + ';')) {
            $cells = $line -split "`t", 2
            if ($cells.Count -eq 2) { $total += [int64]$cells[1] }
        }
    }
    $total
}

function Get-ZeroDateCount {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    $columns = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='$db' AND data_type IN ('date','datetime','timestamp') ORDER BY table_name, ordinal_position;"
    $queries = [Collections.Generic.List[string]]::new()
    foreach ($line in $columns) {
        $cells = $line -split "`t", 2
        if ($cells.Count -ne 2 -or $cells[0] -notmatch '^[A-Za-z0-9_]+$' -or $cells[1] -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe date-column metadata returned by the server.' }
        $queries.Add("SELECT COUNT(*) FROM ``$Database``.``$($cells[0])`` WHERE CAST(``$($cells[1])`` AS CHAR) LIKE '0000-00-00%'")
    }
    $total = 0L
    if ($queries.Count -gt 0) {
        foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql (($queries -join ' UNION ALL ') + ';')) { $total += [int64]$line }
    }
    $total
}

function Get-ForeignKeyOrphanCount {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    $rows = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT constraint_name, table_name, column_name, referenced_table_name, referenced_column_name, ordinal_position FROM information_schema.key_column_usage WHERE table_schema='$db' AND referenced_table_name IS NOT NULL ORDER BY constraint_name, ordinal_position;"
    $groups = @{}
    foreach ($line in $rows) {
        $cells = $line -split "`t", 6
        if ($cells.Count -ne 6) { throw 'Malformed foreign-key metadata returned by the server.' }
        foreach ($identifier in $cells[0..4]) { if ($identifier -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe foreign-key metadata returned by the server.' } }
        $key = "$($cells[0])`t$($cells[1])`t$($cells[3])"
        if (-not $groups.ContainsKey($key)) { $groups[$key] = [Collections.Generic.List[object]]::new() }
        $groups[$key].Add([pscustomobject]@{ ChildColumn = $cells[2]; ParentColumn = $cells[4]; Ordinal = [int]$cells[5] })
    }
    $total = 0L
    foreach ($key in $groups.Keys) {
        $parts = $key -split "`t", 3
        $child = $parts[1]
        $parent = $parts[2]
        $columns = @($groups[$key] | Sort-Object Ordinal)
        $join = ($columns | ForEach-Object { "c.``$($_.ChildColumn)`` = p.``$($_.ParentColumn)``" }) -join ' AND '
        $notNull = ($columns | ForEach-Object { "c.``$($_.ChildColumn)`` IS NOT NULL" }) -join ' AND '
        $missing = "p.``$($columns[0].ParentColumn)`` IS NULL"
        $count = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT COUNT(*) FROM ``$Database``.``$child`` c LEFT JOIN ``$Database``.``$parent`` p ON $join WHERE $notNull AND $missing;" | Select-Object -First 1
        $total += [int64]$count
    }
    $total
}

function Get-IndexDefinitionRows {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT table_name, index_name, non_unique, seq_in_index, column_name, COALESCE(sub_part,0) FROM information_schema.statistics WHERE table_schema='$db' ORDER BY table_name, index_name, seq_in_index;")
}

function Get-ColumnDefinitionRows {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    # Metadata defaults can contain tabs/newlines and empty EXTRA values. HEX keeps
    # the mysql batch protocol unambiguous on both 5.5 and 8.0.
    $rows = Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT HEX(table_name), HEX(column_name), ordinal_position, HEX(column_type), HEX(is_nullable), IF(column_default IS NULL,'<NULL>',HEX(column_default)), HEX(extra), IF(character_set_name IS NULL,'<NULL>',HEX(character_set_name)), IF(collation_name IS NULL,'<NULL>',HEX(collation_name)) FROM information_schema.columns WHERE table_schema='$db' ORDER BY table_name, ordinal_position;"
    @($rows | ForEach-Object {
        $cells = $_ -split "`t", 9
        if ($cells.Count -ne 9) { throw 'Malformed column metadata returned by the server.' }
        foreach ($index in @(0,1,3,4,6)) { $cells[$index] = ConvertFrom-HexUtf8 -Hex $cells[$index] }
        foreach ($index in @(5,7,8)) { if ($cells[$index] -ne '<NULL>') { $cells[$index] = ConvertFrom-HexUtf8 -Hex $cells[$index] } }
        $cells[3] = ([regex]::Replace($cells[3].ToLowerInvariant(), '\b(tinyint|smallint|mediumint|int|integer|bigint)\(\d+\)', '$1'))
        $cells[3] = $cells[3] -replace '^integer\b', 'int'
        if ($script:approvedTransformEnabled -and $cells[0] -eq 'platform_webhook_event' -and $cells[1] -eq 'last_received_at' -and $cells[5] -in @('0000-00-00 00:00:00', 'CURRENT_TIMESTAMP', 'CURRENT_TIMESTAMP()')) { $cells[5] = '<APPROVED_CURRENT_TIMESTAMP_DEFAULT>' }
        $cells[5] = $cells[5] -replace '(?i)^current_timestamp\(\)$', 'CURRENT_TIMESTAMP'
        $cells[6] = (($cells[6] -replace '(?i)\bDEFAULT_GENERATED\b', '') -replace '\s+', ' ').Trim().ToLowerInvariant()
        $cells[7] = $cells[7].ToLowerInvariant()
        $cells[8] = $cells[8].ToLowerInvariant()
        $cells -join "`t"
    })
}

function ConvertFrom-HexUtf8 {
    param([AllowEmptyString()][string]$Hex)
    if ([string]::IsNullOrEmpty($Hex)) { return '' }
    if (($Hex.Length % 2) -ne 0 -or $Hex -notmatch '^[0-9A-Fa-f]+$') { throw 'Malformed hexadecimal metadata returned by the server.' }
    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($Hex.Substring($i * 2, 2), 16) }
    [Text.Encoding]::UTF8.GetString($bytes)
}

function Get-ForeignKeyDefinitionRows {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT k.constraint_name, k.table_name, k.column_name, k.referenced_table_name, k.referenced_column_name, k.ordinal_position, r.update_rule, r.delete_rule FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name WHERE k.table_schema='$db' AND k.referenced_table_name IS NOT NULL ORDER BY k.table_name, k.constraint_name, k.ordinal_position;")
}

function Get-TableSchemaFingerprints {
    param(
        [string[]]$Tables,
        [string[]]$ColumnRows,
        [string[]]$IndexRows,
        [string[]]$ForeignKeyRows
    )
    $result = [ordered]@{}
    foreach ($table in @($Tables | Sort-Object)) {
        $columns = @($ColumnRows | Where-Object { ($_ -split "`t", 2)[0] -ceq $table } | Sort-Object | ForEach-Object { "COLUMN`t$_" })
        $indexes = @($IndexRows | Where-Object { ($_ -split "`t", 2)[0] -ceq $table } | Sort-Object | ForEach-Object { "INDEX`t$_" })
        $foreignKeys = @($ForeignKeyRows | Where-Object { $cells = $_ -split "`t", 3; $cells.Count -ge 2 -and $cells[1] -ceq $table } | Sort-Object | ForEach-Object { "FOREIGN_KEY`t$_" })
        $result[$table] = Get-Sha256ForLines -Lines @($columns + $indexes + $foreignKeys)
    }
    $result
}

function Get-ProgrammableObjectDefinitionRows {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $db = Escape-SqlLiteral $Database
    $sql = @"
SELECT 'VIEW', table_name, MD5(CONCAT_WS('|', REPLACE(REPLACE(REPLACE(view_definition, CHAR(13), ' '), CHAR(10), ' '), CHAR(9), ' '), check_option, is_updatable, definer, security_type)) FROM information_schema.views WHERE table_schema='$db'
UNION ALL SELECT CONCAT('ROUTINE:', routine_type), routine_name, MD5(CONCAT_WS('|', REPLACE(REPLACE(REPLACE(COALESCE(routine_definition,''), CHAR(13), ' '), CHAR(10), ' '), CHAR(9), ' '), COALESCE(dtd_identifier,''), sql_mode, security_type, definer)) FROM information_schema.routines WHERE routine_schema='$db'
UNION ALL SELECT 'TRIGGER', trigger_name, MD5(CONCAT_WS('|', action_timing, event_manipulation, event_object_table, REPLACE(REPLACE(REPLACE(action_statement, CHAR(13), ' '), CHAR(10), ' '), CHAR(9), ' '), action_orientation, COALESCE(action_condition,''), sql_mode, definer)) FROM information_schema.triggers WHERE trigger_schema='$db'
UNION ALL SELECT 'EVENT', event_name, MD5(CONCAT_WS('|', REPLACE(REPLACE(REPLACE(event_definition, CHAR(13), ' '), CHAR(10), ' '), CHAR(9), ' '), event_type, COALESCE(interval_value,''), COALESCE(interval_field,''), status, sql_mode, definer)) FROM information_schema.events WHERE event_schema='$db'
ORDER BY 1, 2;
"@
    @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql $sql)
}

function Get-BinaryAttachmentRows {
    param([string]$Client, [string]$OptionFile, [string]$Database, [string[]]$Tables)
    $rows = [Collections.Generic.List[string]]::new()
    if ($Tables -contains 'warehouse_attachment') {
        foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT CONCAT('warehouse_attachment:', id), COALESCE(file_size,-1), COALESCE(OCTET_LENGTH(content),-1), COALESCE(SHA2(content,256),'<NULL>') FROM ``$Database``.warehouse_attachment ORDER BY id;") { $rows.Add($line) }
    }
    if ($Tables -contains 'todo_action_attachment') {
        foreach ($line in Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT CONCAT('todo_action_attachment:', id), size_bytes, OCTET_LENGTH(content), SHA2(content,256) FROM ``$Database``.todo_action_attachment ORDER BY id;") { $rows.Add($line) }
    }
    @($rows)
}

function Compare-OrderedMap {
    param($Left, $Right)
    $keys = @(@($Left.Keys) + @($Right.Keys) | Sort-Object -Unique)
    @($keys | Where-Object { -not $Left.Contains($_) -or -not $Right.Contains($_) -or "$($Left[$_])" -cne "$($Right[$_])" })
}

function Get-FlywayRowsBeforeV37 {
    param([string[]]$Rows)
    @($Rows | Where-Object {
        $cells = $_ -split "`t", 5
        $cells.Count -eq 5 -and $cells[1] -ne '37'
    })
}

function Get-FailedV37ResidueFacts {
    param([string]$Client, [string]$OptionFile, [string]$Database)
    $tableCount = [int](Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$Database' AND table_name IN ('permission_catalog','user_permission_override','user_data_scope');" | Select-Object -First 1)
    $tableRows = -1
    if ($tableCount -eq 3) {
        $tableRows = [int](Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT (SELECT COUNT(*) FROM ``$Database``.permission_catalog) + (SELECT COUNT(*) FROM ``$Database``.user_permission_override) + (SELECT COUNT(*) FROM ``$Database``.user_data_scope);" | Select-Object -First 1)
    }
    $columnCount = [int](Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$Database' AND column_name='permission_version' AND table_name IN ('auth_user','auth_token');" | Select-Object -First 1)
    $nonDefaultValues = -1
    if ($columnCount -eq 2) {
        $nonDefaultValues = [int](Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT (SELECT COUNT(*) FROM ``$Database``.auth_user WHERE permission_version <> 1 OR permission_version IS NULL) + (SELECT COUNT(*) FROM ``$Database``.auth_token WHERE permission_version <> 1 OR permission_version IS NULL);" | Select-Object -First 1)
    }
    $failedRows = @(Invoke-MySqlQuery -Client $Client -OptionFile $OptionFile -Sql "SELECT checksum FROM ``$Database``.flyway_schema_history WHERE version='37' AND success=0;")
    [ordered]@{
        tables = $tableCount
        tableRows = $tableRows
        permissionVersionColumns = $columnCount
        nonDefaultPermissionVersionValues = $nonDefaultValues
        failedFlywayRows = $failedRows.Count
        failedFlywayChecksum = if ($failedRows.Count -eq 1) { [int]$failedRows[0] } else { $null }
    }
}

function Assert-ImportAccountScopeRecord {
    param([Parameter(Mandatory)]$Scope, [Parameter(Mandatory)][string]$ExpectedDatabase, [Parameter(Mandatory)][string]$Label)
    Assert-ExactNameSet -Actual @($Scope.PSObject.Properties.Name) -Expected @('hostLocal','databaseScope','globalPrivileges','crossDatabasePrivileges','accountIdentitySha256') -Label "$Label fields"
    if (-not [bool]$Scope.hostLocal -or [string]$Scope.databaseScope -cne $ExpectedDatabase -or
        [bool]$Scope.globalPrivileges -or [bool]$Scope.crossDatabasePrivileges -or
        [string]$Scope.accountIdentitySha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw "$Label does not prove one hashed local account scoped only to $ExpectedDatabase."
    }
}

function Assert-ImportClearanceRecord {
    param([Parameter(Mandatory)]$Clearance, [Parameter(Mandatory)][string]$Label)
    Assert-ExactNameSet -Actual @($Clearance.PSObject.Properties.Name) -Expected @('contract','authUserCount','clearedPasswordMarkerCount','authTokenCount','platformSecretCount','sensitiveKvCount') -Label "$Label fields"
    if ([string]$Clearance.contract -cne 'AI_PROFIT_OS_SENSITIVE_CLEARANCE_V1' -or
        [int64]$Clearance.authUserCount -lt 0 -or
        [int64]$Clearance.authUserCount -ne [int64]$Clearance.clearedPasswordMarkerCount -or
        [int64]$Clearance.authTokenCount -ne 0 -or [int64]$Clearance.platformSecretCount -ne 0 -or
        [int64]$Clearance.sensitiveKvCount -ne 0) {
        throw "$Label does not prove that imported password hashes, tokens, API credentials, and sessions were cleared."
    }
}

function Assert-ImportPreV37StateRecord {
    param([Parameter(Mandatory)]$State, [Parameter(Mandatory)][string]$Label)
    Assert-ExactNameSet -Actual @($State.PSObject.Properties.Name) -Expected @('flywayRows','successfulV1ToV36Rows','failedV37Rows','failedV37Checksum','failedV37Tables','failedV37TableRows','permissionVersionColumns','nonDefaultPermissionVersionRows') -Label "$Label fields"
    if ([int]$State.flywayRows -ne 37 -or [int]$State.successfulV1ToV36Rows -ne 36 -or
        [int]$State.failedV37Rows -ne 1 -or [int]$State.failedV37Checksum -ne 1160061988 -or
        [int]$State.failedV37Tables -ne 3 -or [int64]$State.failedV37TableRows -ne 0 -or
        [int]$State.permissionVersionColumns -ne 2 -or [int64]$State.nonDefaultPermissionVersionRows -ne 0) {
        throw "$Label is not the exact known failed-V37 PreV37 state."
    }
}

function Read-BoundImportReceiptReference {
    param([Parameter(Mandatory)]$Reference)
    Assert-ExactNameSet -Actual @($Reference.PSObject.Properties.Name) -Expected @('path','sha256','generatorSha256','sanitizedDumpSha256') -Label 'V37 import-receipt reference fields'
    if ([string]$Reference.sha256 -notmatch '^[A-Fa-f0-9]{64}$' -or
        [string]$Reference.generatorSha256 -cne $script:importReceiptGeneratorSha256 -or
        [string]$Reference.sanitizedDumpSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'V37 repair receipt does not bind an approved import receipt generator and sanitized dump.'
    }
    $generatorPath = Join-Path $PSScriptRoot 'mysql8-logical-migration.ps1'
    if (-not (Test-Path -LiteralPath $generatorPath -PathType Leaf) -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $generatorPath).Hash -cne $script:importReceiptGeneratorSha256) {
        throw 'The fixed import-receipt generator is missing or has changed.'
    }
    $file = Resolve-ApprovedExternalFile -Path ([string]$Reference.path) -ExpectedSha256 ([string]$Reference.sha256) -Label 'V37-bound import receipt' -RequireReadOnly
    if ((Split-Path -Leaf $file.Path) -cne 'mysql8-import-receipt.json') { throw 'The V37-bound import receipt filename is not approved.' }
    $receipt = Get-Content -LiteralPath $file.Path -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ExactNameSet -Actual @($receipt.PSObject.Properties.Name) -Expected @('schemaVersion','generator','generatedAt','status','taskOwnership','sourceEvidence','toolchain','stage','sanitizedDump','final','rehearsal','invariants','credentialsPersisted') -Label 'Import receipt fields'
    Assert-ExactNameSet -Actual @($receipt.taskOwnership.PSObject.Properties.Name) -Expected @('stageDidNotExistBefore','finalDidNotExistBefore','rehearsalDidNotExistBefore','stageCreatedByTask','finalCreatedByTask','rehearsalCreatedByTask','preservedEvidenceDatabaseAccessed','preservedEvidenceDatabaseModified') -Label 'Import task-ownership fields'
    Assert-ExactNameSet -Actual @($receipt.sourceEvidence.PSObject.Properties.Name) -Expected @('rawDumpSha256','compatibleDumpSha256','transformManifestSha256','rawDumpImported','compatibleDumpImportedOnlyIntoStage') -Label 'Import source-evidence fields'
    Assert-ExactNameSet -Actual @($receipt.toolchain.PSObject.Properties.Name) -Expected @('mysql','mysqldump') -Label 'Import toolchain fields'
    foreach ($toolName in @('mysql','mysqldump')) { Assert-ExactNameSet -Actual @($receipt.toolchain.$toolName.PSObject.Properties.Name) -Expected @('path','sha256') -Label "Import $toolName fields" }
    Assert-ExactNameSet -Actual @($receipt.stage.PSObject.Properties.Name) -Expected @('host','port','database','tableCount','accountScope','credentialBearingImportReceived','clearedBeforeSanitizedDump','clearance','preV37State','retainedUntilFinalValidation') -Label 'Import stage fields'
    Assert-ExactNameSet -Actual @($receipt.sanitizedDump.PSObject.Properties.Name) -Expected @('path','sha256','size','manifestPath','manifestSha256','readOnly') -Label 'Sanitized dump fields'
    foreach ($name in @('final','rehearsal')) {
        Assert-ExactNameSet -Actual @($receipt.$name.PSObject.Properties.Name) -Expected @('host','port','database','tableCount','accountScope','clearance','preV37State','importSourceSha256') -Label "Import $name fields"
    }
    Assert-ExactNameSet -Actual @($receipt.invariants.PSObject.Properties.Name) -Expected @('exactly73TablesEach','sameSanitizedDumpForFinalAndRehearsal','credentialBearingSourceImportedOnlyIntoStage','rawCredentialsNeverEnteredFinalOrRehearsal','callerToolOverrideAccepted') -Label 'Import invariant fields'
    if ([string]$receipt.schemaVersion -cne $script:importReceiptSchema -or [string]$receipt.generator -cne $script:importReceiptGenerator -or
        [string]::IsNullOrWhiteSpace([string]$receipt.generatedAt) -or [string]$receipt.status -cne 'PASS' -or [bool]$receipt.credentialsPersisted -or
        -not [bool]$receipt.taskOwnership.stageDidNotExistBefore -or -not [bool]$receipt.taskOwnership.finalDidNotExistBefore -or -not [bool]$receipt.taskOwnership.rehearsalDidNotExistBefore -or
        -not [bool]$receipt.taskOwnership.stageCreatedByTask -or -not [bool]$receipt.taskOwnership.finalCreatedByTask -or -not [bool]$receipt.taskOwnership.rehearsalCreatedByTask -or
        [bool]$receipt.taskOwnership.preservedEvidenceDatabaseAccessed -or [bool]$receipt.taskOwnership.preservedEvidenceDatabaseModified -or
        [string]$receipt.sourceEvidence.rawDumpSha256 -cne $script:approvedRawDumpSha256 -or [string]$receipt.sourceEvidence.compatibleDumpSha256 -cne $script:approvedCompatibleDumpSha256 -or
        [string]$receipt.sourceEvidence.transformManifestSha256 -cne $script:approvedTransformManifestSha256 -or [bool]$receipt.sourceEvidence.rawDumpImported -or
        -not [bool]$receipt.sourceEvidence.compatibleDumpImportedOnlyIntoStage -or
        [string]$receipt.toolchain.mysql.path -cne $script:targetClientPath -or [string]$receipt.toolchain.mysql.sha256 -cne $script:targetClientSha256 -or
        [string]$receipt.toolchain.mysqldump.path -cne $script:mysql8DumpPath -or [string]$receipt.toolchain.mysqldump.sha256 -cne $script:mysql8DumpSha256 -or
        -not [bool]$receipt.invariants.exactly73TablesEach -or -not [bool]$receipt.invariants.sameSanitizedDumpForFinalAndRehearsal -or
        -not [bool]$receipt.invariants.credentialBearingSourceImportedOnlyIntoStage -or -not [bool]$receipt.invariants.rawCredentialsNeverEnteredFinalOrRehearsal -or
        [bool]$receipt.invariants.callerToolOverrideAccepted) {
        throw 'The V37-bound import receipt does not prove the one approved, isolated, sanitized import chain.'
    }
    $targets = @(
        @('stage',$script:stageDatabase),
        @('final',$script:finalTargetDatabase),
        @('rehearsal',$script:rehearsalDatabase)
    )
    $identityHashes = [Collections.Generic.List[string]]::new()
    foreach ($target in $targets) {
        $name = [string]$target[0]
        $database = [string]$target[1]
        $record = $receipt.$name
        if ([string]$record.host -cne '127.0.0.1' -or [int]$record.port -ne 3307 -or [string]$record.database -cne $database -or [int]$record.tableCount -ne $script:expectedTableCount) {
            throw "The import receipt $name identity is not the fixed 73-table local target."
        }
        Assert-ImportAccountScopeRecord -Scope $record.accountScope -ExpectedDatabase $database -Label "Import $name account scope"
        Assert-ImportClearanceRecord -Clearance $record.clearance -Label "Import $name clearance"
        Assert-ImportPreV37StateRecord -State $record.preV37State -Label "Import $name PreV37 state"
        $identityHashes.Add(([string]$record.accountScope.accountIdentitySha256).ToUpperInvariant())
    }
    if (@($identityHashes | Sort-Object -Unique).Count -ne 3) { throw 'Stage, final, and rehearsal must use three distinct local single-database accounts.' }
    if (-not [bool]$receipt.stage.credentialBearingImportReceived -or -not [bool]$receipt.stage.clearedBeforeSanitizedDump -or -not [bool]$receipt.stage.retainedUntilFinalValidation -or
        [int64]$receipt.stage.clearance.authUserCount -ne [int64]$receipt.final.clearance.authUserCount -or
        [int64]$receipt.final.clearance.authUserCount -ne [int64]$receipt.rehearsal.clearance.authUserCount) {
        throw 'The import receipt does not prove stage clearance and equal sanitized populations before final and rehearsal import.'
    }
    $sanitizedSha = ([string]$receipt.sanitizedDump.sha256).ToUpperInvariant()
    if ($sanitizedSha -cne ([string]$Reference.sanitizedDumpSha256).ToUpperInvariant() -or [int64]$receipt.sanitizedDump.size -le 0 -or
        -not [bool]$receipt.sanitizedDump.readOnly -or [string]$receipt.final.importSourceSha256 -cne $sanitizedSha -or [string]$receipt.rehearsal.importSourceSha256 -cne $sanitizedSha) {
        throw 'Final and rehearsal are not bound to the same approved sanitized dump.'
    }
    $sanitized = Resolve-ApprovedExternalFile -Path ([string]$receipt.sanitizedDump.path) -ExpectedSha256 $sanitizedSha -Label 'Sanitized import dump' -RequireReadOnly
    if ([int64](Get-Item -LiteralPath $sanitized.Path).Length -ne [int64]$receipt.sanitizedDump.size) { throw 'Sanitized import dump size differs from the import receipt.' }
    $manifest = Resolve-ApprovedExternalFile -Path ([string]$receipt.sanitizedDump.manifestPath) -ExpectedSha256 ([string]$receipt.sanitizedDump.manifestSha256) -Label 'Sanitized import manifest' -RequireReadOnly
    [pscustomobject]@{ Path=$file.Path; Sha256=$file.Sha256; SanitizedDumpPath=$sanitized.Path; SanitizedDumpSha256=$sanitized.Sha256; SanitizedManifestPath=$manifest.Path; SanitizedManifestSha256=$manifest.Sha256; Receipt=$receipt }
}

function Read-ApprovedV37RepairReceipt {
    param([Parameter(Mandatory)][string]$ExpectedPreV37ReportSha256)
    if (-not $ApprovedV37RepairReceipt -or -not $ApprovedV37RepairReceiptSha256) {
        throw 'PostV37 validation requires the hash-bound Apply receipt generated by mysql8-repair-v37.ps1.'
    }
    $generatorPath = Join-Path $PSScriptRoot 'mysql8-repair-v37.ps1'
    if (-not (Test-Path -LiteralPath $generatorPath -PathType Leaf) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $generatorPath).Hash -cne $script:v37RepairGeneratorSha256) {
        throw 'The fixed V37 repair receipt generator is missing or has changed.'
    }
    $file = Resolve-ApprovedExternalFile -Path $ApprovedV37RepairReceipt -ExpectedSha256 $ApprovedV37RepairReceiptSha256 -Label 'V37 Apply repair receipt' -RequireReadOnly
    $receipt = Get-Content -LiteralPath $file.Path -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ExactNameSet -Actual @($receipt.PSObject.Properties.Name) -Expected @('generatedAt','status','mode','target','flyway','validation','evidence','trustedTools','credentialsPersisted') -Label 'V37 Apply receipt fields'
    Assert-ExactNameSet -Actual @($receipt.target.PSObject.Properties.Name) -Expected @('host','port','database','mysqlVersion','tableCount','accountScope') -Label 'V37 Apply target fields'
    Assert-ExactNameSet -Actual @($receipt.target.accountScope.PSObject.Properties.Name) -Expected @('identitySha256','localHost','databaseScoped') -Label 'V37 Apply target account-scope fields'
    Assert-ExactNameSet -Actual @($receipt.flyway.PSObject.Properties.Name) -Expected @('pluginVersion','migrationLocation','baselineOnMigrate','v37Script','v37Checksum','v37Sha256','failedRows','v1ToV36ProjectionSha256','repairTransition','runtimeProbe','repair','firstMigrate','firstValidate','secondMigrate','secondValidate') -Label 'V37 Apply Flyway fields'
    Assert-ExactNameSet -Actual @($receipt.flyway.repairTransition.PSObject.Properties.Name) -Expected @('beforeHistorySha256','afterRepairHistorySha256','removedFailedVersion','removedFailedChecksum','v1ToV36Unchanged') -Label 'V37 repair transition fields'
    Assert-ExactNameSet -Actual @($receipt.validation.PSObject.Properties.Name) -Expected @('permissionCatalogCount','permissionTables','permissionVersionColumns','historyStableAcrossSecondMigrate') -Label 'V37 Apply validation fields'
    Assert-ExactNameSet -Actual @($receipt.evidence.PSObject.Properties.Name) -Expected @('preCleanBackup','importReceipt','preV37ValidationReport','preV37ValidationReportSha256','fingerprintContract','transitionEvidence','preCleanManifest','preCleanManifestSha256') -Label 'V37 Apply evidence fields'
    Assert-ExactNameSet -Actual @($receipt.evidence.preCleanBackup.PSObject.Properties.Name) -Expected @('path','sha256') -Label 'V37 pre-clean backup fields'
    Assert-ExactNameSet -Actual @($receipt.evidence.importReceipt.PSObject.Properties.Name) -Expected @('path','sha256','generatorSha256','sanitizedDumpSha256') -Label 'V37 import-receipt reference fields'
    Assert-ExactNameSet -Actual @($receipt.evidence.preV37ValidationReport.PSObject.Properties.Name) -Expected @('path','sha256') -Label 'V37 PreV37 report reference fields'
    Assert-ExactNameSet -Actual @($receipt.evidence.transitionEvidence.PSObject.Properties.Name) -Expected @('path','sha256') -Label 'V37 transition evidence reference fields'
    Assert-ExactNameSet -Actual @($receipt.trustedTools.PSObject.Properties.Name) -Expected @('mysql','mysqldump','maven','flywayPluginJar') -Label 'V37 Apply trusted-tool fields'
    $expectedLocation = 'filesystem:' + ((Join-Path $projectRoot 'backend\src\main\resources\db\migration') -replace '\\','/')
    if ([string]$receipt.status -cne 'PASS' -or [string]$receipt.mode -cne 'Apply' -or
        [string]$receipt.target.host -cne '127.0.0.1' -or [int]$receipt.target.port -ne 3307 -or [string]$receipt.target.database -cne $script:finalTargetDatabase -or [string]$receipt.target.mysqlVersion -cne '8.0.46' -or [int]$receipt.target.tableCount -ne $script:expectedTableCount -or
        [string]$receipt.target.accountScope.identitySha256 -notmatch '^[A-Fa-f0-9]{64}$' -or -not [bool]$receipt.target.accountScope.localHost -or -not [bool]$receipt.target.accountScope.databaseScoped -or
        [string]$receipt.flyway.pluginVersion -cne '11.7.2' -or [string]$receipt.flyway.migrationLocation -cne $expectedLocation -or [bool]$receipt.flyway.baselineOnMigrate -or
        [string]$receipt.flyway.v37Script -cne 'V37__permission_architecture.sql' -or [string]$receipt.flyway.v37Sha256 -cne $script:approvedV37MigrationSha256 -or [int]$receipt.flyway.v37Checksum -ne $script:approvedV37FlywayChecksum -or [int]$receipt.flyway.failedRows -ne 0 -or
        [string]$receipt.flyway.v1ToV36ProjectionSha256 -notmatch '^[A-Fa-f0-9]{64}$' -or [string]$receipt.flyway.repairTransition.beforeHistorySha256 -notmatch '^[A-Fa-f0-9]{64}$' -or [string]$receipt.flyway.repairTransition.afterRepairHistorySha256 -notmatch '^[A-Fa-f0-9]{64}$' -or [int]$receipt.flyway.repairTransition.removedFailedVersion -ne 37 -or [int]$receipt.flyway.repairTransition.removedFailedChecksum -ne 1160061988 -or -not [bool]$receipt.flyway.repairTransition.v1ToV36Unchanged -or
        [int]$receipt.validation.permissionCatalogCount -ne 42 -or [int]$receipt.validation.permissionTables -ne 3 -or [int]$receipt.validation.permissionVersionColumns -ne 2 -or -not [bool]$receipt.validation.historyStableAcrossSecondMigrate -or
        [string]$receipt.evidence.preV37ValidationReportSha256 -cne $ExpectedPreV37ReportSha256 -or [string]$receipt.evidence.preV37ValidationReport.sha256 -cne $ExpectedPreV37ReportSha256 -or [bool]$receipt.credentialsPersisted) {
        throw 'V37 Apply receipt is not bound to the exact target, migration directory, repair transition, PreV37 proof, V37 source, and checksum.'
    }
    Assert-ExactNameSet -Actual @($receipt.evidence.fingerprintContract.PSObject.Properties.Name) -Expected @('id','sha256','excludedColumns','excludedWholeTables','excludedKvStorageKeys','callerControlledExclusionsAccepted') -Label 'V37 repair fingerprint contract fields'
    if ([string]$receipt.evidence.fingerprintContract.id -cne $script:sensitiveExclusionContractId -or [string]$receipt.evidence.fingerprintContract.sha256 -cne $script:sensitiveExclusionContractSha256 -or [bool]$receipt.evidence.fingerprintContract.callerControlledExclusionsAccepted) { throw 'V37 Apply receipt uses a different sensitive-field fingerprint contract.' }
    Assert-ExactNameSet -Actual @($receipt.evidence.fingerprintContract.excludedColumns) -Expected @('auth_token.token','auth_user.password_hash','platform_account.password_cipher') -Label 'V37 receipt excluded columns'
    Assert-ExactNameSet -Actual @($receipt.evidence.fingerprintContract.excludedWholeTables) -Expected @('auth_token') -Label 'V37 receipt whole-table exclusions'
    Assert-ExactNameSet -Actual @($receipt.evidence.fingerprintContract.excludedKvStorageKeys) -Expected $script:sensitiveKvKeys -Label 'V37 receipt KV exclusions'
    $importApproval = Read-BoundImportReceiptReference -Reference $receipt.evidence.importReceipt
    if ([IO.Path]::GetFullPath([string]$receipt.evidence.preV37ValidationReport.path) -ine [IO.Path]::GetFullPath($ApprovedPreV37Report)) { throw 'V37 Apply receipt references a different PreV37 report path.' }
    [void](Resolve-ApprovedExternalFile -Path ([string]$receipt.evidence.preCleanBackup.path) -ExpectedSha256 ([string]$receipt.evidence.preCleanBackup.sha256) -Label 'V37 pre-clean backup' -RequireReadOnly)
    [void](Resolve-ApprovedExternalFile -Path ([string]$receipt.evidence.preCleanManifest) -ExpectedSha256 ([string]$receipt.evidence.preCleanManifestSha256) -Label 'V37 pre-clean manifest' -RequireReadOnly)
    foreach ($actionName in @('runtimeProbe','repair','firstMigrate','firstValidate','secondMigrate','secondValidate')) {
        $action = $receipt.flyway.$actionName
        Assert-ExactNameSet -Actual @($action.PSObject.Properties.Name) -Expected @('action','exitCode','stdoutSha256','stderrSha256') -Label "V37 $actionName action fields"
        if ([int]$action.exitCode -ne 0 -or [string]$action.stdoutSha256 -notmatch '^[A-Fa-f0-9]{64}$' -or [string]$action.stderrSha256 -notmatch '^[A-Fa-f0-9]{64}$') { throw "V37 $actionName action is not a hash-bound success." }
    }
    $expectedActions = [ordered]@{ runtimeProbe='info'; repair='repair'; firstMigrate='migrate'; firstValidate='validate'; secondMigrate='migrate'; secondValidate='validate' }
    foreach ($name in $expectedActions.Keys) { if ([string]$receipt.flyway.$name.action -cne [string]$expectedActions[$name]) { throw 'V37 Apply receipt Flyway action sequence is not the approved info/repair/migrate/validate/no-op sequence.' } }
    foreach ($tool in @(
        @('mysql',$script:targetClientPath,$script:targetClientSha256),
        @('mysqldump',$script:mysql8DumpPath,$script:mysql8DumpSha256),
        @('maven',$script:mavenPath,$script:mavenSha256),
        @('flywayPluginJar',$script:flywayPluginPath,$script:flywayPluginSha256)
    )) {
        $record = $receipt.trustedTools.PSObject.Properties[[string]$tool[0]]
        if ($null -eq $record) { throw 'V37 Apply receipt is missing a fixed trusted tool.' }
        Assert-ExactNameSet -Actual @($record.Value.PSObject.Properties.Name) -Expected @('path','sha256') -Label "V37 trusted tool $($tool[0]) fields"
        if ([string]$record.Value.path -cne [string]$tool[1] -or [string]$record.Value.sha256 -cne [string]$tool[2]) { throw 'V37 Apply receipt does not bind the fixed trusted toolchain.' }
    }
    $transitionPath = [IO.Path]::GetFullPath([string]$receipt.evidence.transitionEvidence.path)
    $transitionSha = [string]$receipt.evidence.transitionEvidence.sha256
    if (-not $ApprovedV37DifferenceEvidence -or -not $ApprovedV37DifferenceEvidenceSha256 -or
        $transitionPath -ine [IO.Path]::GetFullPath($ApprovedV37DifferenceEvidence) -or $transitionSha -cne $ApprovedV37DifferenceEvidenceSha256.ToUpperInvariant()) {
        throw 'Caller-supplied V37 transition evidence is not the exact file and SHA recorded by the repair receipt.'
    }
    [pscustomobject]@{ Path = $file.Path; Sha256 = $file.Sha256; Receipt = $receipt; TransitionPath = $transitionPath; TransitionSha256 = $transitionSha; GeneratorPath = $generatorPath; GeneratorSha256 = $script:v37RepairGeneratorSha256; Schema = $script:v37RepairReceiptSchema; ImportApproval = $importApproval }
}

function Read-ApprovedPostV37Evidence {
    if (-not $ApprovedPreV37Report -or -not $ApprovedPreV37ReportSha256 -or
        -not $ApprovedV37DifferenceEvidence -or -not $ApprovedV37DifferenceEvidenceSha256) {
        throw 'PostV37 validation requires separately hashed PreV37 and V37 difference evidence files.'
    }
    $preFile = Resolve-ApprovedExternalFile -Path $ApprovedPreV37Report -ExpectedSha256 $ApprovedPreV37ReportSha256 -Label 'PreV37 validation report'
    $pre = Get-Content -LiteralPath $preFile.Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($pre.status -ne 'PASS' -or $pre.phase -ne 'PreV37' -or -not [bool]$pre.strictExactMatch -or
        [string]$pre.target.database -cne $script:finalTargetDatabase -or [int]$pre.target.port -ne 3307 -or
        [string]$pre.source.database -cne $SourceDatabase -or [int]$pre.source.port -ne $SourcePort -or
        [string]$pre.approvedTransformManifestSha256 -cne $script:approvedTransformManifestSha256 -or
        [string]$pre.approvedImportEvidence.rawDumpSha256 -cne $script:approvedRawDumpSha256 -or
        [string]$pre.approvedImportEvidence.compatibleDumpSha256 -cne $script:approvedCompatibleDumpSha256 -or
        [string]$pre.sensitiveExclusionContract.id -cne $script:sensitiveExclusionContractId -or
        [string]$pre.sensitiveExclusionContract.sha256 -cne $script:sensitiveExclusionContractSha256 -or
        [bool]$pre.sensitiveExclusionContract.callerControlledExclusionsAccepted -or
        @($pre.approvedExclusions).Count -ne 0) {
        throw 'PreV37 report is not an exact PASS for the current source and fixed target.'
    }

    $repairReceipt = Read-ApprovedV37RepairReceipt -ExpectedPreV37ReportSha256 $preFile.Sha256
    $evidenceFile = Resolve-ApprovedExternalFile -Path $repairReceipt.TransitionPath -ExpectedSha256 $repairReceipt.TransitionSha256 -Label 'Repair-generated V37 difference evidence' -RequireReadOnly
    $evidence = Get-Content -LiteralPath $evidenceFile.Path -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-ExactNameSet -Actual @($evidence.PSObject.Properties.Name) -Expected @(
        'evidenceType', 'preV37ValidationReportSha256', 'importReceiptSha256', 'sanitizedDumpSha256', 'fingerprintContract', 'migration',
        'approvedNewTables', 'approvedDataChangedTables', 'approvedSchemaChangedTables',
        'tableDataTransitions', 'tableSchemaTransitions',
        'newTableDataFingerprints', 'newTableSchemaFingerprints'
    ) -Label 'V37 evidence fields'
    Assert-ExactNameSet -Actual @($evidence.migration.PSObject.Properties.Name) -Expected @('version', 'script', 'sha256', 'flywayChecksum') -Label 'V37 migration evidence fields'
    Assert-ExactNameSet -Actual @($evidence.fingerprintContract.PSObject.Properties.Name) -Expected @('id','sha256','excludedColumns','excludedWholeTables','excludedKvStorageKeys','callerControlledExclusionsAccepted') -Label 'V37 transition fingerprint contract fields'
    if ([string]$evidence.evidenceType -cne 'V37_APPROVED_DIFFERENCES' -or
        [string]$evidence.preV37ValidationReportSha256 -cne $preFile.Sha256 -or
        [string]$evidence.importReceiptSha256 -cne [string]$repairReceipt.ImportApproval.Sha256 -or
        [string]$evidence.sanitizedDumpSha256 -cne [string]$repairReceipt.ImportApproval.SanitizedDumpSha256 -or
        [string]$evidence.fingerprintContract.id -cne $script:sensitiveExclusionContractId -or [string]$evidence.fingerprintContract.sha256 -cne $script:sensitiveExclusionContractSha256 -or [bool]$evidence.fingerprintContract.callerControlledExclusionsAccepted -or
        [string]$evidence.migration.version -cne '37' -or
        [string]$evidence.migration.script -cne 'V37__permission_architecture.sql' -or
        [string]$evidence.migration.sha256 -cne $script:approvedV37MigrationSha256 -or
        [int]$evidence.migration.flywayChecksum -ne $script:approvedV37FlywayChecksum) {
        throw 'V37 evidence is not bound to the approved migration and PreV37 report.'
    }
    Assert-ExactNameSet -Actual @($evidence.fingerprintContract.excludedColumns) -Expected @('auth_token.token','auth_user.password_hash','platform_account.password_cipher') -Label 'V37 transition excluded columns'
    Assert-ExactNameSet -Actual @($evidence.fingerprintContract.excludedWholeTables) -Expected @('auth_token') -Label 'V37 transition whole-table exclusions'
    Assert-ExactNameSet -Actual @($evidence.fingerprintContract.excludedKvStorageKeys) -Expected $script:sensitiveKvKeys -Label 'V37 transition KV exclusions'
    $migrationPath = Join-Path $projectRoot 'backend\src\main\resources\db\migration\V37__permission_architecture.sql'
    if (-not (Test-Path -LiteralPath $migrationPath -PathType Leaf) -or
        (Get-FileHash -Algorithm SHA256 -LiteralPath $migrationPath).Hash -cne $script:approvedV37MigrationSha256) {
        throw 'Repository MySQL V37 is absent or does not match the archive-verified SHA-256.'
    }

    Assert-ExactNameSet -Actual @($evidence.approvedNewTables) -Expected $script:v37NewTables -Label 'V37 new tables'
    Assert-ExactNameSet -Actual @($evidence.approvedDataChangedTables) -Expected $script:v37DataChangedTables -Label 'V37 data-changed tables'
    Assert-ExactNameSet -Actual @($evidence.approvedSchemaChangedTables) -Expected $script:v37SchemaChangedTables -Label 'V37 schema-changed tables'
    $dataTransitions = ConvertTo-PropertyMap $evidence.tableDataTransitions
    $schemaTransitions = ConvertTo-PropertyMap $evidence.tableSchemaTransitions
    $newData = ConvertTo-PropertyMap $evidence.newTableDataFingerprints
    $newSchema = ConvertTo-PropertyMap $evidence.newTableSchemaFingerprints
    Assert-ExactNameSet -Actual @($dataTransitions.Keys) -Expected $script:v37DataChangedTables -Label 'V37 table data transitions'
    Assert-ExactNameSet -Actual @($schemaTransitions.Keys) -Expected $script:v37SchemaChangedTables -Label 'V37 table schema transitions'
    Assert-ExactNameSet -Actual @($newData.Keys) -Expected $script:v37NewTables -Label 'V37 new-table data fingerprints'
    Assert-ExactNameSet -Actual @($newSchema.Keys) -Expected $script:v37NewTables -Label 'V37 new-table schema fingerprints'
    foreach ($table in $script:v37DataChangedTables) {
        $transition = $dataTransitions[$table]
        Assert-ExactNameSet -Actual @($transition.PSObject.Properties.Name) -Expected @('before', 'after') -Label "V37 data transition fields for $table"
        if ([string]$transition.before -notmatch '^\d+:[A-Fa-f0-9]{64}$' -or [string]$transition.after -notmatch '^\d+:[A-Fa-f0-9]{64}$') { throw 'A V37 data transition fingerprint is malformed.' }
    }
    foreach ($table in $script:v37SchemaChangedTables) {
        $transition = $schemaTransitions[$table]
        Assert-ExactNameSet -Actual @($transition.PSObject.Properties.Name) -Expected @('before', 'after') -Label "V37 schema transition fields for $table"
        if ([string]$transition.before -notmatch '^[A-Fa-f0-9]{64}$' -or [string]$transition.after -notmatch '^[A-Fa-f0-9]{64}$') { throw 'A V37 schema transition fingerprint is malformed.' }
    }
    foreach ($table in $script:v37NewTables) {
        if ([string]$newData[$table] -notmatch '^\d+:[A-Fa-f0-9]{64}$' -or [string]$newSchema[$table] -notmatch '^[A-Fa-f0-9]{64}$') { throw 'A V37 new-table fingerprint is malformed.' }
    }
    [pscustomobject]@{
        PreReport = $pre
        PreReportPath = $preFile.Path
        PreReportSha256 = $preFile.Sha256
        Evidence = $evidence
        EvidencePath = $evidenceFile.Path
        EvidenceSha256 = $evidenceFile.Sha256
        RepairReceipt = $repairReceipt
        DataTransitions = $dataTransitions
        SchemaTransitions = $schemaTransitions
        NewData = $newData
        NewSchema = $newSchema
    }
}

Assert-Endpoint -HostName $SourceHost -Port $SourcePort -ExpectedPort 3309 -Label 'Source'
Assert-Endpoint -HostName $TargetHost -Port $TargetPort -ExpectedPort 3307 -Label 'Target'
Assert-FinalTargetDatabase
$SourceClientPath = Assert-PinnedTool -Path $script:sourceClientPath -Sha256 $script:sourceClientSha256 -Label 'MySQL 5.5 mysql.exe'
$TargetClientPath = Assert-PinnedTool -Path $script:targetClientPath -Sha256 $script:targetClientSha256 -Label 'MySQL 8 mysql.exe'
[void](Assert-PinnedTool -Path $script:mysql8DumpPath -Sha256 $script:mysql8DumpSha256 -Label 'MySQL 8 mysqldump.exe')
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql8-migration-validation\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputDirectory = Initialize-RestrictedDirectory -Path $OutputDirectory
$credentialDirectory = Join-Path $OutputDirectory '.credentials'
[void](New-Item -ItemType Directory -Path $credentialDirectory -Force)
$sourceOption = $null
$targetOption = $null
$transformEvidence = $null
$postApproval = $null
$reportPath = Join-Path $OutputDirectory 'mysql8-validation.json'

try {
    $transformEvidence = Read-ApprovedTransformManifest
    if ($Phase -eq 'PostV37') { $postApproval = Read-ApprovedPostV37Evidence }
    $sourceOption = New-MySqlOptionFile -HostName $SourceHost -Port $SourcePort -UserName $SourceUser -CredentialFile $SourceCredentialFile -Prompt 'Read-only MySQL 5.5 recovery source' -Directory $credentialDirectory
    $targetOption = New-MySqlOptionFile -HostName $TargetHost -Port $TargetPort -UserName $TargetUser -CredentialFile $TargetCredentialFile -Prompt 'Scoped MySQL 8 validation account' -Directory $credentialDirectory
    Write-Host 'Validation phase: server identity'
    $sourceIdentity = Invoke-MySqlQuery -Client $SourceClientPath -OptionFile $sourceOption -Sql "USE ``$SourceDatabase``; SELECT VERSION(), @@port, DATABASE(), CURRENT_USER(), @@global.read_only;" | Select-Object -Last 1
    $sourceIdentityCells = $sourceIdentity -split "`t", 5
    if ($sourceIdentityCells.Count -ne 5 -or $sourceIdentityCells[0] -ne '5.5.62' -or [int]$sourceIdentityCells[1] -ne 3309 -or $sourceIdentityCells[2] -cne $SourceDatabase -or [int]$sourceIdentityCells[4] -ne 1) { throw 'Source identity is not the read-only MySQL 5.5 recovery database on 127.0.0.1:3309.' }
    $sourceVersion = $sourceIdentityCells[0]
    $targetIdentity = Invoke-MySqlQuery -Client $TargetClientPath -OptionFile $targetOption -Sql "USE ``$TargetDatabase``; SELECT VERSION(), @@port, DATABASE(), CURRENT_USER();" | Select-Object -Last 1
    $targetIdentityCells = $targetIdentity -split "`t", 4
    if ($targetIdentityCells.Count -ne 4 -or $targetIdentityCells[0] -ne '8.0.46' -or [int]$targetIdentityCells[1] -ne 3307 -or $targetIdentityCells[2] -cne $TargetDatabase) { throw 'Target identity is not MySQL 8.0.46 on 127.0.0.1:3307/store_profit_mysql8_final.' }
    $targetVersion = $targetIdentityCells[0]
    $targetAccountScope = Assert-ScopedTargetAccount -Client $TargetClientPath -OptionFile $targetOption -CurrentUser $targetIdentityCells[3]
    Write-Host 'Validation phase: table sets and exact row counts'
    $sourceTables = Get-TableNames -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetTables = Get-TableNames -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceSensitiveContract = Get-SensitiveExclusionContract -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetSensitiveContract = Get-SensitiveExclusionContract -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    if ([string]$sourceSensitiveContract.sha256 -cne [string]$targetSensitiveContract.sha256) { throw 'Source and target sensitive-field exclusion contracts differ.' }
    $sourceCounts = Get-ExactTableCounts -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase -Tables $sourceTables
    $targetCounts = Get-ExactTableCounts -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables
    Write-Host 'Validation phase: deterministic full-table fingerprints'
    $sourceDataFingerprints = Get-TableDataFingerprints -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase -Tables $sourceTables
    $targetDataFingerprints = Get-TableDataFingerprints -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables
    Write-Host 'Validation phase: object inventory and Flyway history'
    $sourceObjects = Get-ObjectCounts -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetObjects = Get-ObjectCounts -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceFlyway = Get-FlywayRows -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase -Tables $sourceTables
    $targetFlyway = Get-FlywayRows -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables
    Write-Host 'Validation phase: business aggregates and encoding fingerprints'
    $sourceAggregates = Get-BusinessAggregates -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase -Tables $sourceTables
    $targetAggregates = Get-BusinessAggregates -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables
    $targetSensitiveState = Get-SensitiveCredentialState -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables
    $sensitiveRotationEvidence = $null
    if ([int64]$targetSensitiveState.authTokenCount -ne 0 -or [int64]$targetSensitiveState.platformPasswordCipherCount -ne 0 -or [int64]$targetSensitiveState.sensitiveKvRecordCount -ne 0 -or [int64]$targetSensitiveState.emptyPasswordCount -ne 0) {
        throw 'Target still contains login tokens, platform credentials, sensitive legacy KV records, or empty password fields.'
    }
    if ([int64]$targetSensitiveState.authUserCount -eq [int64]$targetSensitiveState.clearedPasswordMarkerCount) {
        $sensitiveCredentialDisposition = 'CLEARED_REAUTH_REQUIRED'
    }
    elseif ([int64]$targetSensitiveState.clearedPasswordMarkerCount -eq 0 -and [int64]$targetSensitiveState.formallyRotatedPasswordCount -eq [int64]$targetSensitiveState.authUserCount -and [int64]$targetSensitiveState.unsupportedRotatedPasswordFormatCount -eq 0) {
        $sensitiveRotationEvidence = Read-ApprovedSensitiveRotationReceipt -TargetState $targetSensitiveState -Client $TargetClientPath -OptionFile $targetOption
        $sensitiveCredentialDisposition = 'FORMALLY_ROTATED_WITH_AUTH_SERVICE_EVIDENCE'
    }
    else { throw 'Target passwords are neither fully cleared nor fully rotated through PasswordService with formal evidence.' }
    $sourceEncodingSuspects = Get-EncodingSuspectCount -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase -Tables $sourceTables
    $targetEncodingSuspects = Get-EncodingSuspectCount -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables
    Write-Host 'Validation phase: zero dates, foreign-key orphans, and schema definitions'
    $sourceZeroDates = Get-ZeroDateCount -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetZeroDates = Get-ZeroDateCount -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceOrphans = Get-ForeignKeyOrphanCount -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetOrphans = Get-ForeignKeyOrphanCount -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceIndexes = Get-IndexDefinitionRows -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetIndexes = Get-IndexDefinitionRows -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceColumns = Get-ColumnDefinitionRows -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetColumns = Get-ColumnDefinitionRows -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceForeignKeys = Get-ForeignKeyDefinitionRows -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetForeignKeys = Get-ForeignKeyDefinitionRows -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    $sourceSchemaFingerprints = Get-TableSchemaFingerprints -Tables $sourceTables -ColumnRows $sourceColumns -IndexRows $sourceIndexes -ForeignKeyRows $sourceForeignKeys
    $targetSchemaFingerprints = Get-TableSchemaFingerprints -Tables $targetTables -ColumnRows $targetColumns -IndexRows $targetIndexes -ForeignKeyRows $targetForeignKeys
    $sourceProgrammableObjects = Get-ProgrammableObjectDefinitionRows -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
    $targetProgrammableObjects = Get-ProgrammableObjectDefinitionRows -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
    Write-Host 'Validation phase: BLOB attachment lengths and hashes'
    $sourceBinaryAttachments = Get-BinaryAttachmentRows -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase -Tables $sourceTables
    $targetBinaryAttachments = Get-BinaryAttachmentRows -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase -Tables $targetTables

    $blockers = [Collections.Generic.List[string]]::new()
    $tableMismatches = [Collections.Generic.List[string]]::new()
    $schemaMismatches = [Collections.Generic.List[string]]::new()
    $dataMismatches = [Collections.Generic.List[string]]::new()
    if ($sourceVersion -ne '5.5.62') { $blockers.Add("Unexpected source version: $sourceVersion") }
    if ($targetVersion -ne '8.0.46') { $blockers.Add("Unexpected target version: $targetVersion") }
    if ($sourceTables.Count -ne $script:expectedTableCount -or $targetTables.Count -ne $script:expectedTableCount) { $blockers.Add("Source and target must each contain exactly $($script:expectedTableCount) base tables.") }
    if (($sourceAggregates -join "`n") -ne ($targetAggregates -join "`n")) { $blockers.Add('Critical monetary, salary, inventory, or month aggregates differ.') }
    if ($targetEncodingSuspects -gt $sourceEncodingSuspects) { $blockers.Add('The target contains additional question-mark or replacement-character text.') }
    if ($sourceZeroDates -gt 0) { $blockers.Add('Source zero dates require an explicit compatibility plan before migration.') }
    if ($sourceZeroDates -ne $targetZeroDates) { $blockers.Add('Zero-date counts differ.') }
    if ($sourceOrphans -ne 0 -or $targetOrphans -ne 0) { $blockers.Add('Foreign-key orphan rows exist.') }
    if (($sourceProgrammableObjects -join "`n") -ne ($targetProgrammableObjects -join "`n")) { $blockers.Add('View, trigger, routine, event definition, or DEFINER hashes differ.') }
    if (($sourceBinaryAttachments -join "`n") -ne ($targetBinaryAttachments -join "`n")) { $blockers.Add('BLOB attachment lengths or SHA-256 values differ.') }

    $v37Facts = $null
    $failedV37ResidueFacts = $null
    if ($Phase -eq 'PreV37') {
        foreach ($name in Compare-OrderedMap -Left $sourceCounts -Right $targetCounts) { $tableMismatches.Add($name) }
        foreach ($name in Compare-OrderedMap -Left $sourceDataFingerprints -Right $targetDataFingerprints) { $dataMismatches.Add($name) }
        foreach ($name in Compare-OrderedMap -Left $sourceSchemaFingerprints -Right $targetSchemaFingerprints) { $schemaMismatches.Add($name) }
        if ($tableMismatches.Count -gt 0) { $blockers.Add('PreV37 table sets or exact row counts differ.') }
        if ($dataMismatches.Count -gt 0) { $blockers.Add('PreV37 deterministic business-table fingerprints differ under the fixed sensitive-field exclusion contract.') }
        if ($schemaMismatches.Count -gt 0) { $blockers.Add('PreV37 per-table schema fingerprints differ outside the one hard-bound timestamp-default normalization.') }
        if ((Compare-OrderedMap -Left $sourceObjects -Right $targetObjects).Count -gt 0) { $blockers.Add('PreV37 database object counts differ.') }
        if (($sourceFlyway -join "`n") -ne ($targetFlyway -join "`n")) { $blockers.Add('PreV37 Flyway history differs.') }
        $sourceResidue = Get-FailedV37ResidueFacts -Client $SourceClientPath -OptionFile $sourceOption -Database $SourceDatabase
        $targetResidue = Get-FailedV37ResidueFacts -Client $TargetClientPath -OptionFile $targetOption -Database $TargetDatabase
        $failedV37ResidueFacts = [ordered]@{ source = $sourceResidue; target = $targetResidue }
        foreach ($facts in @($sourceResidue, $targetResidue)) {
            if ([int]$facts.tables -ne 3 -or [int]$facts.tableRows -ne 0 -or
                [int]$facts.permissionVersionColumns -ne 2 -or [int]$facts.nonDefaultPermissionVersionValues -ne 0 -or
                [int]$facts.failedFlywayRows -ne 1 -or [int]$facts.failedFlywayChecksum -ne 1160061988) {
                $blockers.Add('Failed V37 residue is not exactly three empty tables, two default-only columns, and one known failed history row.')
                break
            }
        }
    }
    else {
        $pre = $postApproval.PreReport
        $preSourceCounts = ConvertTo-PropertyMap $pre.sourceTableCounts
        $preTargetCounts = ConvertTo-PropertyMap $pre.targetTableCounts
        $preSourceData = ConvertTo-PropertyMap $pre.sourceTableDataFingerprints
        $preTargetData = ConvertTo-PropertyMap $pre.targetTableDataFingerprints
        $preSourceSchema = ConvertTo-PropertyMap $pre.sourceTableSchemaFingerprints
        $preTargetSchema = ConvertTo-PropertyMap $pre.targetTableSchemaFingerprints
        if ((Compare-OrderedMap -Left $preSourceCounts -Right $preTargetCounts).Count -gt 0 -or
            (Compare-OrderedMap -Left $preSourceData -Right $preTargetData).Count -gt 0 -or
            (Compare-OrderedMap -Left $preSourceSchema -Right $preTargetSchema).Count -gt 0) {
            $blockers.Add('Hashed PreV37 report does not itself prove exact source/target equality.')
        }
        foreach ($name in Compare-OrderedMap -Left $preSourceCounts -Right $sourceCounts) { $tableMismatches.Add("source:$name") }
        foreach ($name in Compare-OrderedMap -Left $preSourceData -Right $sourceDataFingerprints) { $dataMismatches.Add("source:$name") }
        foreach ($name in Compare-OrderedMap -Left $preSourceSchema -Right $sourceSchemaFingerprints) { $schemaMismatches.Add("source:$name") }

        foreach ($table in $script:v37NewTables) {
            if (-not $preTargetCounts.Contains($table) -or [int64]$preTargetCounts[$table] -ne 0) { $blockers.Add("PreV37 proof does not show empty failed-migration residue for $table.") }
        }
        $expectedTargetTables = @($preTargetCounts.Keys)
        try { Assert-ExactNameSet -Actual $targetTables -Expected $expectedTargetTables -Label 'PostV37 target table set' }
        catch { $blockers.Add('PostV37 target table set differs from the exact PreV37 set after controlled residue recreation.') }
        foreach ($table in @($preTargetCounts.Keys)) {
            if ($script:v37NewTables -contains $table) {
                # Failed V37 residue tables were proven empty in PreV37 and are
                # validated against exact approved post-migration fingerprints below.
            }
            elseif ($script:v37DataChangedTables -contains $table) {
                $transition = $postApproval.DataTransitions[$table]
                if ([string]$transition.before -cne [string]$preTargetData[$table] -or [string]$transition.after -cne [string]$targetDataFingerprints[$table]) { $dataMismatches.Add("target:$table") }
            }
            elseif ([string]$preTargetData[$table] -cne [string]$targetDataFingerprints[$table]) { $dataMismatches.Add("target:$table") }

            if ($script:v37NewTables -contains $table) {
                # Handled by the exact new-table schema fingerprint evidence below.
            }
            elseif ($script:v37SchemaChangedTables -contains $table) {
                $transition = $postApproval.SchemaTransitions[$table]
                if ([string]$transition.before -cne [string]$preTargetSchema[$table] -or [string]$transition.after -cne [string]$targetSchemaFingerprints[$table]) { $schemaMismatches.Add("target:$table") }
            }
            elseif ([string]$preTargetSchema[$table] -cne [string]$targetSchemaFingerprints[$table]) { $schemaMismatches.Add("target:$table") }
        }
        foreach ($table in $script:v37NewTables) {
            if ([string]$postApproval.NewData[$table] -cne [string]$targetDataFingerprints[$table]) { $dataMismatches.Add("target:$table") }
            if ([string]$postApproval.NewSchema[$table] -cne [string]$targetSchemaFingerprints[$table]) { $schemaMismatches.Add("target:$table") }
        }
        if ($tableMismatches.Count -gt 0) { $blockers.Add('The source changed after the approved PreV37 proof.') }
        if ($dataMismatches.Count -gt 0) { $blockers.Add('A data fingerprint differs outside the exact hashed V37 transition evidence.') }
        if ($schemaMismatches.Count -gt 0) { $blockers.Add('A schema fingerprint differs outside the exact hashed V37 transition evidence.') }

        foreach ($key in @($sourceObjects.Keys)) {
            $expected = [int64]$sourceObjects[$key]
            if (-not $targetObjects.Contains($key) -or [int64]$targetObjects[$key] -ne $expected) { $blockers.Add("PostV37 object count delta is not approved for $key.") }
        }
        $sourcePreV37Flyway = Get-FlywayRowsBeforeV37 -Rows $sourceFlyway
        $targetPreV37Flyway = Get-FlywayRowsBeforeV37 -Rows $targetFlyway
        $targetV37Rows = @($targetFlyway | Where-Object { $cells = $_ -split "`t", 5; $cells.Count -eq 5 -and $cells[1] -eq '37' })
        if (($sourcePreV37Flyway -join "`n") -ne ($targetPreV37Flyway -join "`n")) { $blockers.Add('V1-V36 Flyway history changed.') }
        if ($targetFlyway.Count -ne ($targetPreV37Flyway.Count + 1) -or $targetV37Rows.Count -ne 1) { $blockers.Add('Target Flyway history is not exactly V1-V36 plus one V37 row.') }
        elseif (($targetV37Rows[0] -split "`t", 5)[2] -cne 'V37__permission_architecture.sql' -or
            [int](($targetV37Rows[0] -split "`t", 5)[3]) -ne $script:approvedV37FlywayChecksum -or
            (($targetV37Rows[0] -split "`t", 5)[4]) -ne '1') { $blockers.Add('Target V37 Flyway row does not match the archive-verified migration.') }

        $v37FactLine = Invoke-MySqlQuery -Client $TargetClientPath -OptionFile $targetOption -Sql @"
SELECT
  (SELECT COUNT(*) FROM ``$TargetDatabase``.permission_catalog),
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$TargetDatabase' AND table_name IN ('permission_catalog','user_permission_override','user_data_scope')),
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$TargetDatabase' AND column_name='permission_version' AND table_name IN ('auth_user','auth_token')),
  (SELECT COUNT(*) FROM ``$TargetDatabase``.flyway_schema_history WHERE success=0);
"@ | Select-Object -First 1
        $v37Cells = $v37FactLine -split "`t", 4
        $v37Facts = [ordered]@{ permissionCatalog = [int]$v37Cells[0]; tables = [int]$v37Cells[1]; permissionVersionColumns = [int]$v37Cells[2]; failedFlywayRows = [int]$v37Cells[3] }
        if ($v37Cells.Count -ne 4 -or [int]$v37Cells[0] -ne 42 -or [int]$v37Cells[1] -ne 3 -or [int]$v37Cells[2] -ne 2 -or [int]$v37Cells[3] -ne 0) { $blockers.Add('V37 semantic database facts are not 42 permissions, 3 tables, 2 version columns, and 0 failed Flyway rows.') }
    }

    $report = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        mode = 'Validate'
        phase = $Phase
        status = if ($blockers.Count -eq 0) { 'PASS' } else { 'BLOCKED' }
        strictExactMatch = ($Phase -eq 'PreV37' -and $blockers.Count -eq 0)
        approvedV37DifferencesAccepted = ($Phase -eq 'PostV37' -and $blockers.Count -eq 0)
        approvedExclusions = @()
        sensitiveExclusionContract = $targetSensitiveContract
        sensitiveCredentialDisposition = [ordered]@{ status = $sensitiveCredentialDisposition; targetState = $targetSensitiveState; formalRotationEvidence = $sensitiveRotationEvidence; sourceCredentialEqualityRequired = $false }
        approvedTransformManifestSha256 = $transformEvidence.Sha256
        approvedImportEvidence = [ordered]@{ rawDumpSha256 = $script:approvedRawDumpSha256; compatibleDumpSha256 = $script:approvedCompatibleDumpSha256; transformationManifestSha256 = $script:approvedTransformManifestSha256 }
        approvedPreV37Report = if ($postApproval) { [ordered]@{ path = $postApproval.PreReportPath; sha256 = $postApproval.PreReportSha256 } } else { $null }
        approvedV37DifferenceEvidence = if ($postApproval) { [ordered]@{ path = $postApproval.EvidencePath; sha256 = $postApproval.EvidenceSha256; schema = $script:v37TransitionSchema; generator = $script:v37TransitionGenerator; migrationSha256 = $script:approvedV37MigrationSha256; flywayChecksum = $script:approvedV37FlywayChecksum; repairReceipt = [ordered]@{ path = $postApproval.RepairReceipt.Path; sha256 = $postApproval.RepairReceipt.Sha256; schema = $postApproval.RepairReceipt.Schema; generatorSha256 = $postApproval.RepairReceipt.GeneratorSha256 } } } else { $null }
        source = [ordered]@{ host = $SourceHost; port = $SourcePort; database = $SourceDatabase; version = $sourceVersion; accountIdentitySha256 = Get-Sha256ForLines -Lines @($sourceIdentityCells[3]); hostLocal = $true; readOnly = $true; tableCount = $sourceTables.Count; objectCounts = $sourceObjects; encodingSuspectCount = $sourceEncodingSuspects; zeroDateCount = $sourceZeroDates; orphanCount = $sourceOrphans }
        target = [ordered]@{ host = $TargetHost; port = $TargetPort; database = $TargetDatabase; version = $targetVersion; accountIdentitySha256 = $targetAccountScope.accountIdentitySha256; accountScope = $targetAccountScope; tableCount = $targetTables.Count; objectCounts = $targetObjects; encodingSuspectCount = $targetEncodingSuspects; zeroDateCount = $targetZeroDates; orphanCount = $targetOrphans }
        tableCountMismatches = @($tableMismatches)
        tableDataFingerprintMismatches = @($dataMismatches)
        tableSchemaFingerprintMismatches = @($schemaMismatches)
        flywayMatches = if ($Phase -eq 'PreV37') { (($sourceFlyway -join "`n") -eq ($targetFlyway -join "`n")) } else { $blockers -notcontains 'V1-V36 Flyway history changed.' }
        failedV37ResidueFacts = $failedV37ResidueFacts
        v37Facts = $v37Facts
        criticalAggregatesMatch = (($sourceAggregates -join "`n") -eq ($targetAggregates -join "`n"))
        sourceCredentialValuesCompared = $false
        programmableObjectDefinitionsMatch = (($sourceProgrammableObjects -join "`n") -eq ($targetProgrammableObjects -join "`n"))
        binaryAttachmentsMatch = (($sourceBinaryAttachments -join "`n") -eq ($targetBinaryAttachments -join "`n"))
        blockers = @($blockers)
        # Exact business counts/fingerprints use the fixed sensitive exclusion contract and are confined to this restricted, external report.
        sourceTableCounts = $sourceCounts
        targetTableCounts = $targetCounts
        sourceTableDataFingerprints = $sourceDataFingerprints
        targetTableDataFingerprints = $targetDataFingerprints
        sourceTableSchemaFingerprints = $sourceSchemaFingerprints
        targetTableSchemaFingerprints = $targetSchemaFingerprints
        sourceFlywayHistory = $sourceFlyway
        targetFlywayHistory = $targetFlyway
        sourceCriticalAggregates = $sourceAggregates
        targetCriticalAggregates = $targetAggregates
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host "Validation status: $($report.status)" -ForegroundColor $(if ($report.status -eq 'PASS') { 'Green' } else { 'Yellow' })
    Write-Host "Restricted validation report: $reportPath"
    foreach ($blocker in $blockers) { Write-Warning $blocker }
    if ($blockers.Count -gt 0) { exit 2 }
}
catch {
    $safeMessage = Protect-Text $_.Exception.Message
    $failure = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        mode = 'Validate'
        phase = $Phase
        status = 'BLOCKED'
        target = [ordered]@{ host = '127.0.0.1'; port = 3307; database = $script:finalTargetDatabase }
        approvedExclusions = @()
        blockers = @($safeMessage)
        credentialMaterialRecorded = $false
    }
    $failure | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    throw
}
finally {
    Remove-CredentialFile -Path $sourceOption
    Remove-CredentialFile -Path $targetOption
    Remove-Item -LiteralPath $credentialDirectory -Force -ErrorAction SilentlyContinue
}

exit 0
