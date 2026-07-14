[CmdletBinding()]
param(
    [ValidateSet('Preflight', 'Rehearse', 'Apply', 'Validate', 'RollbackPlan')]
    [string]$Mode = 'Preflight',
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$TargetDatabase = 'store_profit_mysql8_final',
    [ValidatePattern('^[A-Za-z0-9_.@-]+$')]
    [string]$DatabaseUser,
    [string]$CredentialFile,
    [string]$RunRoot,
    [string]$PreCleanBackupPath,
    [string]$PreCleanBackupSha256,
    [string]$ApprovedPreV37Report,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedPreV37ReportSha256,
    [string]$ApprovedImportReceipt,
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$ApprovedImportReceiptSha256,
    [string]$RehearsalReceipt,
    [string]$RehearsalReceiptSha256,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
$script:ProjectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$script:BackendDirectory = Join-Path $script:ProjectRoot 'backend'
$script:MigrationDirectory = Join-Path $script:BackendDirectory 'src\main\resources\db\migration'
$script:TargetHost = '127.0.0.1'
$script:TargetPort = 3307
$script:FinalDatabase = 'store_profit_mysql8_final'
$script:RehearsalDatabase = 'store_profit_mysql8_final_rehearsal'
$script:StageDatabase = 'store_profit_mysql8_final_stage_import'
$script:ImportReceiptSchema = 'ai-profit-os-mysql8-import/v1'
$script:ImportReceiptGenerator = 'scripts/mysql8-logical-migration.ps1'
$script:ImportReceiptGeneratorSha256 = 'B2EFA719D007C927455F6FA0D94F1E7A72825C0E82733A198276804F749B2CD7'
$script:ApprovedRawDumpSha256 = 'A3C791A54E232066B96C40703B60CB0A797F35829AB94A05915AA71614410077'
$script:ApprovedCompatibleDumpSha256 = 'E8180C1E74698191C2CCA3DEFE600C0093C323327B347C4CEFA0AC1CB6909E3F'
$script:ApprovedTransformManifestSha256 = 'BFC8C79A91DBE822E4DF5E285825E5A8267794E1B6138CB02E01644D50A11C94'
$script:FlywayVersion = '11.7.2'
$script:FlywayPlugin = 'org.flywaydb:flyway-maven-plugin:11.7.2'
$script:ExpectedMySqlVersion = '8.0.46'
$script:FailedV37Checksum = 1160061988
$script:FinalV37Checksum = 761638840
$script:FinalV37Script = 'V37__permission_architecture.sql'
$script:FinalV37Sha256 = '75712C479633E0180FB8178AC72AB32DB29B71CCB8CB94457B0B84820E25A06F'
$script:PermissionCatalogCount = 42
$script:PermissionTables = @('permission_catalog', 'user_permission_override', 'user_data_scope')
$script:PermissionVersionTables = @('auth_user', 'auth_token')
$script:V37NewTables = @('permission_catalog', 'user_data_scope', 'user_permission_override')
$script:V37DataChangedTables = @('auth_token', 'auth_user', 'business_todo', 'flyway_schema_history', 'role_permission', 'todo_escalation')
$script:V37SchemaChangedTables = @('auth_token', 'auth_user')
$script:SensitiveExclusionContractId = 'AI_PROFIT_OS_PREV37_BUSINESS_FINGERPRINT_V1'
$script:SensitiveExclusionContractSha256 = 'F90DEFF0FADE08CF4850161D7CD1B0521EA9016E9FB23C88D0894B82B48F9019'
$script:SensitiveColumnExclusions = [ordered]@{
    auth_user = @('password_hash')
    auth_token = @('token')
    platform_account = @('password_cipher')
}
$script:SensitiveWholeTableExclusions = @('auth_token')
$script:SensitiveKvKeys = @('accounts', 'app_pin', 'passwords', 'tokens')
$script:SensitiveNamePattern = '(?i)(password|passphrase|token|secret|api_?key|session|credential|private_?key|cipher)'
$script:MySqlClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$script:MySqlClientSha256 = 'DB5440EA2E7F27A1F5DE1C1DA04AD5480DAD4832804E884F75BC2957F4D8E814'
$script:MySqlDumpPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$script:MySqlDumpSha256 = 'ADCAFCB9D489115AEB32419FB3E3F428F2D4DACE3A625DBC2714388B93C1DB5A'
$script:MavenPath = 'D:\apache-maven-3.9.11-bin\apache-maven-3.9.11\bin\mvn.cmd'
$script:MavenSha256 = 'F89D866139ADD674690BBA0702A0CC4F9769276362C4DA01F96267AB85F6487E'
$script:FlywayPluginJar = 'C:\Users\34706\.m2\repository\org\flywaydb\flyway-maven-plugin\11.7.2\flyway-maven-plugin-11.7.2.jar'
$script:FlywayPluginJarSha256 = '4CEEAF2FF4ECC65A3EFF31294DCB832C361FA9DDE0F23BA129447FB1E4B6CBFB'
$script:Stage = 'INITIALIZING'

function Get-NormalizedSha256 {
    param([Parameter(Mandatory)][string]$Value)
    $normalized = ($Value -replace '\s', '').ToUpperInvariant()
    if ($normalized -notmatch '^[0-9A-F]{64}$') { throw 'A required SHA-256 value is malformed.' }
    $normalized
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
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $sid,
        [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow)
    [void]$security.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $security
}

function Initialize-RestrictedDirectory {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $repoPrefix = $script:ProjectRoot + '\'
    if ($fullPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'V37 repair evidence must remain outside the Git workspace.'
    }
    if (Test-Path -LiteralPath $fullPath) {
        if (@(Get-ChildItem -LiteralPath $fullPath -Force -ErrorAction Stop).Count -ne 0) {
            throw 'The requested evidence directory already exists and is not empty.'
        }
    }
    else {
        [void](New-Item -ItemType Directory -Path $fullPath -Force)
    }
    Set-CurrentUserOnlyAcl -Path $fullPath -Container
    $fullPath
}

function Write-Utf8File {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Content, [switch]$ReadOnly)
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
    Set-CurrentUserOnlyAcl -Path $Path
    if ($ReadOnly) { (Get-Item -LiteralPath $Path).IsReadOnly = $true }
}

function Write-JsonEvidence {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value, [switch]$ReadOnly)
    $json = $Value | ConvertTo-Json -Depth 12
    Write-Utf8File -Path $Path -Content ($json + "`r`n") -ReadOnly:$ReadOnly
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Get-TextSha256 {
    param([string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes([string]$Text)
        ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
    }
    finally { $sha.Dispose() }
}

function Assert-RestrictedArtifact {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$Sha256, [switch]$RequireReadOnly)
    $fullPath = [IO.Path]::GetFullPath($Path)
    if ($fullPath.StartsWith(($script:ProjectRoot + '\'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'A protected migration artifact is inside the Git workspace.'
    }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw 'A protected migration artifact is missing.' }
    if ((Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash -ne (Get-NormalizedSha256 $Sha256)) {
        throw 'A protected migration artifact hash does not match.'
    }
    if ($RequireReadOnly -and -not (Get-Item -LiteralPath $fullPath).IsReadOnly) {
        throw 'The pre-clean backup is not marked read-only.'
    }
    $acl = Get-Acl -LiteralPath $fullPath
    $broadWrite = @($acl.Access | Where-Object {
        $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        $_.IdentityReference.Value -match 'Everyone|Authenticated Users|BUILTIN\\Users' -and
        (($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -or
         ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -or
         ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl))
    })
    if ($broadWrite.Count -ne 0) { throw 'A protected migration artifact has broad write permissions.' }
    $fullPath
}

function Get-DatabaseCredential {
    if ($CredentialFile) {
        $fullPath = [IO.Path]::GetFullPath($CredentialFile)
        if ($fullPath.StartsWith(($script:ProjectRoot + '\'), [StringComparison]::OrdinalIgnoreCase)) {
            throw 'The DPAPI credential file must remain outside the Git workspace.'
        }
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { throw 'The DPAPI credential file is missing.' }
        $acl = Get-Acl -LiteralPath $fullPath
        $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        $ownerSid = $acl.Owner
        try { $ownerSid = ([Security.Principal.NTAccount]::new($acl.Owner)).Translate([Security.Principal.SecurityIdentifier]).Value } catch {}
        if ($ownerSid -ne $currentSid) { throw 'The DPAPI credential file is not owned by the current Windows user.' }
        $broadWrite = @($acl.Access | Where-Object {
            $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
            $_.IdentityReference.Value -match 'Everyone|Authenticated Users|BUILTIN\\Users' -and
            (($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -or
             ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -or
             ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl))
        })
        if ($broadWrite.Count -ne 0) { throw 'The DPAPI credential file has broad write permissions.' }
        $credential = Import-Clixml -LiteralPath $fullPath
        if ($credential -isnot [Management.Automation.PSCredential]) {
            throw 'The credential file is not a DPAPI-protected PSCredential export.'
        }
        return $credential
    }
    if ($DatabaseUser) {
        return Get-Credential -UserName $DatabaseUser -Message 'Local MySQL 8 migration account for 127.0.0.1:3307 (password is hidden and is not logged)'
    }
    Get-Credential -Message 'Local MySQL 8 migration account for 127.0.0.1:3307 (password is hidden and is not logged)'
}

function Invoke-SecretProcess {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory,
        [string]$StandardInput,
        [hashtable]$SecretEnvironment = @{}
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.Arguments = ($Arguments -join ' ')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.RedirectStandardInput = $true
    if ($WorkingDirectory) { $start.WorkingDirectory = $WorkingDirectory }
    foreach ($name in @($start.EnvironmentVariables.Keys)) {
        if ($name -match '^(?i:FLYWAY_|MAVEN|SPRING_)' -or
            $name -match '^(?i:JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH|MYSQL_PWD)$') {
            $start.EnvironmentVariables.Remove($name)
        }
    }
    foreach ($name in $SecretEnvironment.Keys) { $start.EnvironmentVariables[$name] = [string]$SecretEnvironment[$name] }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    try {
        [void]$process.Start()
        $started = $true
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if ($null -ne $StandardInput) { $process.StandardInput.Write($StandardInput) }
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        [pscustomobject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdout
            StdoutSha256 = Get-TextSha256 $stdout
            StderrSha256 = Get-TextSha256 $stderr
        }
    }
    finally {
        if ($started) { try { if (-not $process.HasExited) { $process.Kill() } } catch {} }
        $process.Dispose()
        foreach ($name in @($SecretEnvironment.Keys)) {
            if ($start.EnvironmentVariables.ContainsKey($name)) { $start.EnvironmentVariables.Remove($name) }
            $SecretEnvironment[$name] = $null
        }
    }
}

function Use-CredentialSecret {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, [Parameter(Mandatory)][scriptblock]$Action)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Credential.Password)
    $plain = $null
    try {
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
        & $Action $Credential.UserName $plain
    }
    finally {
        $plain = $null
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Invoke-MySqlText {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, [Parameter(Mandatory)][string]$Sql)
    $result = Use-CredentialSecret -Credential $Credential -Action {
        param($user, $password)
        if ($user -notmatch '^[A-Za-z0-9_.@-]+$') { throw 'The database username contains unsupported characters.' }
        Invoke-SecretProcess -FilePath $script:MySqlClientPath -Arguments @(
            '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3307', "--user=$user",
            '--batch', '--silent', '--skip-column-names', '--default-character-set=utf8mb4',
            "--database=$TargetDatabase") -StandardInput ($Sql + "`r`n") -SecretEnvironment @{ MYSQL_PWD = $password }
    }
    if ($result.ExitCode -ne 0) {
        throw "The protected MySQL query failed (exit=$($result.ExitCode), stderrSha256=$($result.StderrSha256))."
    }
    $result.Stdout
}

function Invoke-MySqlLines {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, [Parameter(Mandatory)][string]$Sql)
    @((Invoke-MySqlText -Credential $Credential -Sql $Sql) -split "`r?`n" | Where-Object { $_ -ne '' })
}

function Escape-SqlLiteral {
    param([string]$Value)
    $Value.Replace('\', '\\').Replace("'", "''")
}

function Get-Sha256ForLines {
    param([AllowEmptyCollection()][string[]]$Lines)
    $bytes = [Text.Encoding]::UTF8.GetBytes((@($Lines) -join "`n"))
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '') }
    finally { $sha.Dispose() }
}

function Assert-ExactNameSet {
    param([string[]]$Actual, [string[]]$Expected, [string]$Label)
    $actualValue = (@($Actual) | Sort-Object) -join "`n"
    $expectedValue = (@($Expected) | Sort-Object) -join "`n"
    if ($actualValue -cne $expectedValue) { throw "$Label is not the exact approved set." }
}

function Get-FixedSensitiveExclusionContract {
    $expected = [Collections.Generic.List[string]]::new()
    foreach ($table in $script:SensitiveColumnExclusions.Keys) {
        foreach ($column in $script:SensitiveColumnExclusions[$table]) { $expected.Add("$table.$column") }
    }
    $contractLines = @(
        "contract=$($script:SensitiveExclusionContractId)",
        'whole-table=auth_token',
        'kv-storage-key-predicate=lower(storage_key) not in (accounts,app_pin,passwords,tokens)'
    ) + @($expected | Sort-Object | ForEach-Object { "excluded-column=$_" })
    $calculatedSha256 = Get-Sha256ForLines -Lines $contractLines
    if ($calculatedSha256 -cne $script:SensitiveExclusionContractSha256) {
        throw 'The built-in sensitive-field fingerprint contract no longer matches its approved SHA-256.'
    }
    [ordered]@{
        id = $script:SensitiveExclusionContractId
        sha256 = $script:SensitiveExclusionContractSha256
        excludedColumns = @($expected | Sort-Object)
        excludedWholeTables = @($script:SensitiveWholeTableExclusions)
        excludedKvStorageKeys = @($script:SensitiveKvKeys)
        callerControlledExclusionsAccepted = $false
    }
}

function Get-SensitiveExclusionContract {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $discovered = @(Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema=DATABASE()
ORDER BY table_name, ordinal_position;
'@ | ForEach-Object {
        $cells = $_ -split "`t", 2
        if ($cells.Count -ne 2 -or $cells[0] -notmatch '^[A-Za-z0-9_]+$' -or $cells[1] -notmatch '^[A-Za-z0-9_]+$') {
            throw 'Unsafe column metadata returned while checking the fixed sensitive-field contract.'
        }
        if ($cells[1] -match $script:SensitiveNamePattern) { "$($cells[0]).$($cells[1])" }
    })
    $contract = Get-FixedSensitiveExclusionContract
    Assert-ExactNameSet -Actual $discovered -Expected $contract.excludedColumns -Label 'Sensitive credential columns'
    $contract
}

function Assert-FingerprintContract {
    param([Parameter(Mandatory)]$Contract, [Parameter(Mandatory)][string]$Label)
    $expected = Get-FixedSensitiveExclusionContract
    $actualFields = if ($Contract -is [Collections.IDictionary]) { @($Contract.Keys) } else { @($Contract.PSObject.Properties.Name) }
    Assert-ExactNameSet -Actual $actualFields -Expected @(
        'id', 'sha256', 'excludedColumns', 'excludedWholeTables',
        'excludedKvStorageKeys', 'callerControlledExclusionsAccepted'
    ) -Label "$Label fields"
    $hasCallerControlField = if ($Contract -is [Collections.IDictionary]) {
        $Contract.Contains('callerControlledExclusionsAccepted')
    }
    else {
        $null -ne $Contract.PSObject.Properties['callerControlledExclusionsAccepted']
    }
    if ([string]$Contract.id -cne [string]$expected.id -or [string]$Contract.sha256 -cne [string]$expected.sha256 -or
        -not $hasCallerControlField -or [bool]$Contract.callerControlledExclusionsAccepted) {
        throw "$Label is not bound to the fixed sensitive-field fingerprint contract."
    }
    Assert-ExactNameSet -Actual @($Contract.excludedColumns) -Expected @($expected.excludedColumns) -Label "$Label excluded columns"
    Assert-ExactNameSet -Actual @($Contract.excludedWholeTables) -Expected @($expected.excludedWholeTables) -Label "$Label whole-table exclusions"
    Assert-ExactNameSet -Actual @($Contract.excludedKvStorageKeys) -Expected @($expected.excludedKvStorageKeys) -Label "$Label KV exclusions"
}

function ConvertFrom-HexUtf8 {
    param([AllowEmptyString()][string]$Hex)
    if ([string]::IsNullOrEmpty($Hex)) { return '' }
    if (($Hex.Length % 2) -ne 0 -or $Hex -notmatch '^[0-9A-Fa-f]+$') { throw 'Malformed hexadecimal metadata returned by the server.' }
    $bytes = New-Object byte[] ($Hex.Length / 2)
    for ($index = 0; $index -lt $bytes.Length; $index++) { $bytes[$index] = [Convert]::ToByte($Hex.Substring($index * 2, 2), 16) }
    [Text.Encoding]::UTF8.GetString($bytes)
}

function Get-TableDataFingerprints {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, [string[]]$Tables)
    $result = [ordered]@{}
    foreach ($table in @($Tables | Sort-Object -Unique)) {
        if ($table -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe table identifier requested for transition evidence.' }
        if ($script:SensitiveWholeTableExclusions -contains $table) {
            $result[$table] = "0:$(Get-Sha256ForLines -Lines @())"
            continue
        }
        $columns = @(Invoke-MySqlLines -Credential $Credential -Sql "SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='$(Escape-SqlLiteral $table)' ORDER BY ordinal_position;")
        if ($columns.Count -eq 0 -or @($columns | Where-Object { $_ -notmatch '^[A-Za-z0-9_]+$' }).Count -gt 0) {
            throw "Table fingerprint metadata is empty or unsafe for $table."
        }
        $excludedColumns = if ($script:SensitiveColumnExclusions.Contains($table)) { @($script:SensitiveColumnExclusions[$table]) } else { @() }
        $columns = @($columns | Where-Object { $excludedColumns -notcontains $_ })
        if ($columns.Count -eq 0) { throw 'The fixed sensitive-field contract removed every column from a business-table fingerprint.' }
        $expressions = @($columns | ForEach-Object { "IF(``$_`` IS NULL,'N',CONCAT('V',HEX(``$_``)))" })
        $whereClause = ''
        if ($table -ceq 'kv_storage') {
            $kvList = ($script:SensitiveKvKeys | ForEach-Object { "'$(($_).Replace("'", "''"))'" }) -join ','
            $whereClause = " WHERE LOWER(storage_key) NOT IN ($kvList)"
        }
        $rows = @(Invoke-MySqlLines -Credential $Credential -Sql "SELECT CONCAT_WS('|',$($expressions -join ',')) FROM ``$table``$whereClause;" | Sort-Object)
        $result[$table] = "$($rows.Count):$(Get-Sha256ForLines -Lines $rows)"
    }
    $result
}

function Get-ColumnDefinitionRows {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $rows = Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT HEX(table_name), HEX(column_name), ordinal_position, HEX(column_type), HEX(is_nullable),
       IF(column_default IS NULL,'<NULL>',HEX(column_default)), HEX(extra),
       IF(character_set_name IS NULL,'<NULL>',HEX(character_set_name)),
       IF(collation_name IS NULL,'<NULL>',HEX(collation_name))
FROM information_schema.columns
WHERE table_schema=DATABASE()
ORDER BY table_name, ordinal_position;
'@
    @($rows | ForEach-Object {
        $cells = $_ -split "`t", 9
        if ($cells.Count -ne 9) { throw 'Malformed column metadata returned by the server.' }
        foreach ($index in @(0, 1, 3, 4, 6)) { $cells[$index] = ConvertFrom-HexUtf8 -Hex $cells[$index] }
        foreach ($index in @(5, 7, 8)) { if ($cells[$index] -ne '<NULL>') { $cells[$index] = ConvertFrom-HexUtf8 -Hex $cells[$index] } }
        $cells[3] = ([regex]::Replace($cells[3].ToLowerInvariant(), '\b(tinyint|smallint|mediumint|int|integer|bigint)\(\d+\)', '$1'))
        $cells[3] = $cells[3] -replace '^integer\b', 'int'
        $cells[5] = $cells[5] -replace '(?i)^current_timestamp\(\)$', 'CURRENT_TIMESTAMP'
        $cells[6] = (($cells[6] -replace '(?i)\bDEFAULT_GENERATED\b', '') -replace '\s+', ' ').Trim().ToLowerInvariant()
        $cells[7] = $cells[7].ToLowerInvariant()
        $cells[8] = $cells[8].ToLowerInvariant()
        $cells -join "`t"
    })
}

function Get-IndexDefinitionRows {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    @(Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT table_name, index_name, non_unique, seq_in_index, column_name, COALESCE(sub_part,0)
FROM information_schema.statistics
WHERE table_schema=DATABASE()
ORDER BY table_name, index_name, seq_in_index;
'@)
}

function Get-ForeignKeyDefinitionRows {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    @(Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT k.constraint_name, k.table_name, k.column_name, k.referenced_table_name,
       k.referenced_column_name, k.ordinal_position, r.update_rule, r.delete_rule
FROM information_schema.key_column_usage k
JOIN information_schema.referential_constraints r
  ON r.constraint_schema=k.constraint_schema
 AND r.constraint_name=k.constraint_name
 AND r.table_name=k.table_name
WHERE k.table_schema=DATABASE() AND k.referenced_table_name IS NOT NULL
ORDER BY k.table_name, k.constraint_name, k.ordinal_position;
'@)
}

function Get-TableSchemaFingerprints {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, [string[]]$Tables)
    $columnRows = Get-ColumnDefinitionRows -Credential $Credential
    $indexRows = Get-IndexDefinitionRows -Credential $Credential
    $foreignKeyRows = Get-ForeignKeyDefinitionRows -Credential $Credential
    $result = [ordered]@{}
    foreach ($table in @($Tables | Sort-Object -Unique)) {
        if ($table -notmatch '^[A-Za-z0-9_]+$') { throw 'Unsafe table identifier requested for schema transition evidence.' }
        $columns = @($columnRows | Where-Object { ($_ -split "`t", 2)[0] -ceq $table } | Sort-Object | ForEach-Object { "COLUMN`t$_" })
        $indexes = @($indexRows | Where-Object { ($_ -split "`t", 2)[0] -ceq $table } | Sort-Object | ForEach-Object { "INDEX`t$_" })
        $foreignKeys = @($foreignKeyRows | Where-Object { $cells = $_ -split "`t", 3; $cells.Count -ge 2 -and $cells[1] -ceq $table } | Sort-Object | ForEach-Object { "FOREIGN_KEY`t$_" })
        if ($columns.Count -eq 0) { throw "Schema fingerprint metadata is empty for $table." }
        $result[$table] = Get-Sha256ForLines -Lines @($columns + $indexes + $foreignKeys)
    }
    $result
}

function Get-V37TransitionFingerprintState {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $dataTables = @($script:V37DataChangedTables + $script:V37NewTables | Sort-Object -Unique)
    $schemaTables = @($script:V37SchemaChangedTables + $script:V37NewTables | Sort-Object -Unique)
    [pscustomobject]@{
        fingerprintContract = Get-SensitiveExclusionContract -Credential $Credential
        data = Get-TableDataFingerprints -Credential $Credential -Tables $dataTables
        schema = Get-TableSchemaFingerprints -Credential $Credential -Tables $schemaTables
    }
}

function Invoke-MySqlFile {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, [Parameter(Mandatory)][string]$Path)
    $sql = $null
    try {
        $sql = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
        [void](Invoke-MySqlText -Credential $Credential -Sql $sql)
    }
    finally { $sql = $null }
}

function Invoke-SchemaDump {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $result = Use-CredentialSecret -Credential $Credential -Action {
        param($user, $password)
        $arguments = @(
            '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=3307', "--user=$user",
            '--default-character-set=utf8mb4', '--no-data', '--skip-comments', '--skip-lock-tables',
            '--skip-add-drop-table', '--skip-triggers', '--set-gtid-purged=OFF', '--no-tablespaces',
            $TargetDatabase, 'permission_catalog', 'user_permission_override', 'user_data_scope')
        Invoke-SecretProcess -FilePath $script:MySqlDumpPath -Arguments $arguments -SecretEnvironment @{ MYSQL_PWD = $password }
    }
    if ($result.ExitCode -ne 0) {
        throw "The protected schema-only dump failed (exit=$($result.ExitCode), stderrSha256=$($result.StderrSha256))."
    }
    $result.Stdout
}

if (-not ('FlywayChecksumV37Repair' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
public static class FlywayChecksumV37Repair {
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

function Get-MigrationInventory {
    if (-not (Test-Path -LiteralPath $script:MigrationDirectory -PathType Container)) {
        throw 'The authoritative MySQL migration directory is missing.'
    }
    $files = @(Get-ChildItem -LiteralPath $script:MigrationDirectory -Filter 'V*__*.sql' -File | Sort-Object Name)
    if ($files.Count -ne 37) { throw 'The authoritative MySQL migration directory must contain exactly V1 through V37.' }
    if (@(Get-ChildItem -LiteralPath $script:MigrationDirectory -Filter 'R__*.sql' -File).Count -ne 0) {
        throw 'Repeatable migrations are not allowed during the controlled V37 repair.'
    }
    $inventory = [Collections.Generic.List[object]]::new()
    $seen = @{}
    foreach ($file in $files) {
        if ($file.Name -notmatch '^V([0-9]+)__[A-Za-z0-9_.-]+\.sql$') { throw 'A migration filename is not release-safe.' }
        $version = [int]$Matches[1]
        if ($version -lt 1 -or $version -gt 37 -or $seen.ContainsKey($version)) { throw 'The MySQL migration chain has a gap, duplicate, or version above V37.' }
        $seen[$version] = $true
        $inventory.Add([pscustomobject]@{
            version = $version
            script = $file.Name
            checksum = [FlywayChecksumV37Repair]::Calculate($file.FullName)
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        })
    }
    foreach ($version in 1..37) { if (-not $seen.ContainsKey($version)) { throw "Migration V$version is missing." } }
    $v37 = @($inventory | Where-Object version -eq 37)
    if ($v37.Count -ne 1 -or
        $v37[0].script -cne $script:FinalV37Script -or
        [int]$v37[0].checksum -ne $script:FinalV37Checksum -or
        [string]$v37[0].sha256 -cne $script:FinalV37Sha256) {
        throw 'The authoritative V37 source does not match the approved script name, SHA-256, and Flyway checksum.'
    }
    @($inventory)
}

function Convert-HistoryLines {
    param([string[]]$Lines)
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        $cells = $line -split "`t", 5
        if ($cells.Count -ne 5 -or $cells[0] -notmatch '^[0-9]+$' -or $cells[1] -notmatch '^[0-9]+$' -or $cells[4] -notmatch '^[01]$') {
            throw 'Flyway history returned a malformed row.'
        }
        $rows.Add([pscustomobject]@{
            installedRank = [int]$cells[0]
            version = [int]$cells[1]
            script = $cells[2]
            checksum = if ($cells[3] -eq 'NULL') { $null } else { [int]$cells[3] }
            success = [int]$cells[4]
        })
    }
    @($rows)
}

function Get-HistoryState {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $rows = Convert-HistoryLines (Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT installed_rank, version, script, IFNULL(checksum, 'NULL'), success
FROM flyway_schema_history
WHERE version IS NOT NULL
ORDER BY installed_rank;
'@)
    $total = [int]((Invoke-MySqlLines -Credential $Credential -Sql 'SELECT COUNT(*) FROM flyway_schema_history;') | Select-Object -First 1)
    [pscustomobject]@{ rows = $rows; totalRows = $total }
}

function Get-HistoryProjection {
    param($Rows, [int]$MaximumVersion = 36)
    @($Rows | Where-Object { $_.version -le $MaximumVersion } | ForEach-Object {
        "$($_.installedRank)`t$($_.version)`t$($_.script)`t$($_.checksum)`t$($_.success)"
    }) -join "`n"
}

function Get-CompleteHistoryProjection {
    param($Rows)
    Get-HistoryProjection -Rows $Rows -MaximumVersion 37
}

function Assert-V1ToV36Chain {
    param($HistoryRows, $Inventory)
    $history = @($HistoryRows | Where-Object { $_.version -le 36 })
    if ($history.Count -ne 36) { throw 'Flyway history does not contain exactly one row for each of V1 through V36.' }
    foreach ($version in 1..36) {
        $row = @($history | Where-Object version -eq $version)
        $local = @($Inventory | Where-Object version -eq $version)
        if ($row.Count -ne 1 -or $local.Count -ne 1) { throw "Flyway V$version is missing or duplicated." }
        if ($row[0].success -ne 1 -or $row[0].script -cne $local[0].script -or [int]$row[0].checksum -ne [int]$local[0].checksum) {
            throw "Flyway V$version does not match the authoritative MySQL migration file."
        }
    }
}

function Assert-TargetRuntime {
    $legacy = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction SilentlyContinue
    if (-not $legacy -or $legacy.State -ne 'Stopped') { throw 'The original MySQL 5.5 service is not stopped.' }
    if (Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue) { throw 'Port 3306 is listening.' }
    $target = Get-CimInstance Win32_Service -Filter "Name='MySQL80Test'" -ErrorAction SilentlyContinue
    if (-not $target -or $target.State -ne 'Running') { throw 'MySQL80Test is not running.' }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction SilentlyContinue)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalAddress -notin @('127.0.0.1', '::1')) {
        throw 'MySQL 8 is not listening exclusively on the local 3307 endpoint.'
    }
    $ownerPid = [int]$listeners[0].OwningProcess
    $servicePid = [int]$target.ProcessId
    $cursor = $ownerPid
    $ownedByService = $false
    foreach ($hop in 0..8) {
        if ($cursor -eq $servicePid) { $ownedByService = $true; break }
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$cursor" -ErrorAction SilentlyContinue
        if (-not $process -or [int]$process.ParentProcessId -le 0 -or [int]$process.ParentProcessId -eq $cursor) { break }
        $cursor = [int]$process.ParentProcessId
    }
    if (-not $ownedByService) { throw 'The port 3307 listener is not owned by MySQL80Test or its child process.' }
}

function Assert-NoApplicationConnections {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $databaseSessions = [int]((Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT COUNT(*)
FROM information_schema.processlist
WHERE id <> CONNECTION_ID() AND db = DATABASE();
'@) | Select-Object -First 1)
    if ($databaseSessions -ne 0) { throw 'Another database session is using the V37 repair target.' }
    Start-Sleep -Milliseconds 250
    $clientConnections = @(Get-NetTCPConnection -RemotePort 3307 -State Established -ErrorAction SilentlyContinue)
    if ($clientConnections.Count -ne 0) { throw 'An application or client process has an active TCP connection to port 3307.' }
}

function ConvertFrom-MySqlGrantDatabasePattern {
    param([Parameter(Mandatory)][string]$Pattern)
    $value = $Pattern.Trim('`')
    $normalized = [Text.StringBuilder]::new()
    for ($index = 0; $index -lt $value.Length; $index++) {
        $character = $value[$index]
        if ($character -eq '\') {
            $runStart = $index
            while (($index + 1) -lt $value.Length -and $value[$index + 1] -eq '\') { $index++ }
            $runLength = $index - $runStart + 1
            if ($runLength -notin @(1, 2) -or ($index + 1) -ge $value.Length -or $value[$index + 1] -notin @('_', '%')) {
                throw 'The database grant contains an unsupported escape sequence.'
            }
            $index++
            [void]$normalized.Append($value[$index])
            continue
        }
        if ($character -in @('_', '%', '*', '?')) {
            throw 'The database grant contains an unescaped wildcard.'
        }
        [void]$normalized.Append($character)
    }
    $result = $normalized.ToString()
    if ($result -notmatch '^[A-Za-z0-9_]+$') { throw 'The normalized database grant name is unsafe.' }
    $result
}

function Assert-ScopedDatabaseAccount {
    param(
        [Parameter(Mandatory)][Management.Automation.PSCredential]$Credential,
        [Parameter(Mandatory)][string]$CurrentUser,
        [Parameter(Mandatory)][string]$ExpectedDatabase
    )
    if ($CurrentUser -notmatch '^(.+)@([^@]+)$') { throw 'CURRENT_USER() returned an unexpected account identity.' }
    $accountName = $Matches[1]
    $accountHost = $Matches[2]
    if ($accountName -ieq 'root') { throw 'The V37 migration account must not be root.' }
    if ($accountHost -notin @('127.0.0.1', 'localhost')) { throw 'The V37 migration account must be local and must not use a wildcard Host.' }
    $grants = @(Invoke-MySqlLines -Credential $Credential -Sql 'SHOW GRANTS FOR CURRENT_USER();')
    if ($grants.Count -eq 0) { throw 'The V37 migration account has no inspectable grants.' }
    $scopedGrantSeen = $false
    foreach ($grant in $grants) {
        if ($grant -match '(?i)\bWITH\s+GRANT\s+OPTION\b') { throw 'The V37 migration account must not have GRANT OPTION.' }
        if ($grant -match '(?i)^GRANT\s+USAGE\s+ON\s+\*\.\*\s+TO\s+') { continue }
        if ($grant -notmatch '(?i)^GRANT\s+.+\s+ON\s+(`[^`]+`|[^\s.]+)\.\*\s+TO\s+') {
            throw 'The V37 migration account has an unsupported global, role, table, or malformed grant.'
        }
        $grantedDatabase = ConvertFrom-MySqlGrantDatabasePattern -Pattern $Matches[1]
        if ($grantedDatabase -cne $ExpectedDatabase) { throw 'The V37 migration account has a cross-database grant.' }
        $scopedGrantSeen = $true
    }
    if (-not $scopedGrantSeen) { throw "The V37 migration account requires a database-scoped grant on $ExpectedDatabase only." }
    [ordered]@{
        identitySha256 = Get-TextSha256 "$accountName@$accountHost"
        localHost = $true
        databaseScoped = $true
    }
}

function Assert-AccountScopeRecord {
    param([Parameter(Mandatory)]$Scope, [Parameter(Mandatory)][string]$ExpectedDatabase, [Parameter(Mandatory)][string]$Label)
    $fields = if ($Scope -is [Collections.IDictionary]) { @($Scope.Keys) } else { @($Scope.PSObject.Properties.Name) }
    Assert-ExactNameSet -Actual $fields -Expected @('hostLocal','databaseScope','globalPrivileges','crossDatabasePrivileges','accountIdentitySha256') -Label "$Label fields"
    if (-not [bool]$Scope.hostLocal -or [string]$Scope.databaseScope -cne $ExpectedDatabase -or
        [bool]$Scope.globalPrivileges -or [bool]$Scope.crossDatabasePrivileges -or
        [string]$Scope.accountIdentitySha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw "$Label is not a hashed local, single-database grant."
    }
}

function Assert-SanitizedAccountScopeRecord {
    param([Parameter(Mandatory)]$Scope, [Parameter(Mandatory)][string]$Label)
    $fields = if ($Scope -is [Collections.IDictionary]) { @($Scope.Keys) } else { @($Scope.PSObject.Properties.Name) }
    Assert-ExactNameSet -Actual $fields -Expected @('identitySha256','localHost','databaseScoped') -Label "$Label fields"
    if ([string]$Scope.identitySha256 -notmatch '^[A-Fa-f0-9]{64}$' -or -not [bool]$Scope.localHost -or -not [bool]$Scope.databaseScoped) {
        throw "$Label does not prove a hashed local, database-scoped account identity."
    }
}

function Get-PermissionColumnState {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential)
    $lines = Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT c.table_name, c.column_name, c.column_type, c.is_nullable,
       IFNULL(c.column_default, 'NULL'), c.ordinal_position,
       IFNULL((SELECT p.column_name
               FROM information_schema.columns p
               WHERE p.table_schema = c.table_schema
                 AND p.table_name = c.table_name
                 AND p.ordinal_position = c.ordinal_position - 1), '')
FROM information_schema.columns c
WHERE c.table_schema = DATABASE()
  AND c.column_name = 'permission_version'
  AND c.table_name IN ('auth_user', 'auth_token')
ORDER BY c.table_name;
'@
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        $cells = $line -split "`t", 7
        if ($cells.Count -ne 7) { throw 'permission_version metadata is malformed.' }
        $rows.Add([pscustomobject]@{
            table = $cells[0]
            column = $cells[1]
            columnType = $cells[2]
            nullable = $cells[3]
            defaultValue = $cells[4]
            ordinalPosition = [int]$cells[5]
            previousColumn = $cells[6]
        })
    }
    @($rows)
}

function Get-DatabaseState {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, $Inventory)
    $identity = (Invoke-MySqlLines -Credential $Credential -Sql 'SELECT VERSION(), @@port, DATABASE(), CURRENT_USER();') | Select-Object -First 1
    $identityCells = $identity -split "`t", 4
    if ($identityCells.Count -ne 4 -or $identityCells[0] -cne $script:ExpectedMySqlVersion -or [int]$identityCells[1] -ne 3307 -or $identityCells[2] -cne $TargetDatabase) {
        throw 'The authenticated target is not the approved MySQL 8.0.46 database on port 3307.'
    }
    if ($identityCells[3] -match '@%$') { throw 'The migration account resolved through a wildcard Host entry.' }
    $accountScope = Assert-ScopedDatabaseAccount -Credential $Credential -CurrentUser $identityCells[3] -ExpectedDatabase $TargetDatabase

    $history = Get-HistoryState -Credential $Credential
    Assert-V1ToV36Chain -HistoryRows $history.rows -Inventory $Inventory
    $tableNames = @(Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_type = 'BASE TABLE'
  AND table_name IN ('permission_catalog', 'user_permission_override', 'user_data_scope')
ORDER BY table_name;
'@)
    $columns = Get-PermissionColumnState -Credential $Credential
    $failedRows = [int]((Invoke-MySqlLines -Credential $Credential -Sql 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;') | Select-Object -First 1)
    $baseTableCount = [int]((Invoke-MySqlLines -Credential $Credential -Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE';") | Select-Object -First 1)

    $counts = $null
    $deviations = $null
    if ($tableNames.Count -eq 3) {
        $countLine = (Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT (SELECT COUNT(*) FROM permission_catalog),
       (SELECT COUNT(*) FROM user_permission_override),
       (SELECT COUNT(*) FROM user_data_scope);
'@) | Select-Object -First 1
        $countCells = $countLine -split "`t", 3
        $counts = [ordered]@{
            permission_catalog = [int64]$countCells[0]
            user_permission_override = [int64]$countCells[1]
            user_data_scope = [int64]$countCells[2]
        }
    }
    if ($columns.Count -eq 2) {
        $deviationLine = (Invoke-MySqlLines -Credential $Credential -Sql @'
SELECT (SELECT COALESCE(SUM(CASE WHEN permission_version IS NULL OR permission_version <> 1 THEN 1 ELSE 0 END), 0) FROM auth_user),
       (SELECT COALESCE(SUM(CASE WHEN permission_version IS NULL OR permission_version <> 1 THEN 1 ELSE 0 END), 0) FROM auth_token);
'@) | Select-Object -First 1
        $deviationCells = $deviationLine -split "`t", 2
        $deviations = [ordered]@{ auth_user = [int64]$deviationCells[0]; auth_token = [int64]$deviationCells[1] }
    }
    [pscustomobject]@{
        mysqlVersion = $identityCells[0]
        port = [int]$identityCells[1]
        database = $identityCells[2]
        localAccount = ($identityCells[3] -notmatch '@%$')
        accountScope = $accountScope
        baseTableCount = $baseTableCount
        historyRows = $history.rows
        totalHistoryRows = $history.totalRows
        failedHistoryRows = $failedRows
        permissionTables = $tableNames
        permissionTableCounts = $counts
        permissionVersionColumns = $columns
        permissionVersionNonDefaultRows = $deviations
    }
}

function Assert-FailedV37State {
    param($State)
    if ([int]$State.baseTableCount -ne 73) { throw 'The PreV37 target does not contain exactly 73 base tables.' }
    $v37 = @($State.historyRows | Where-Object version -eq 37)
    if ($State.totalHistoryRows -ne 37 -or $v37.Count -ne 1 -or $v37[0].success -ne 0 -or [int]$v37[0].checksum -ne $script:FailedV37Checksum) {
        throw 'The target does not contain exactly the approved failed V37 evidence.'
    }
    if ($State.failedHistoryRows -ne 1) { throw 'The target contains a failed migration other than the approved failed V37.' }
    if (@($State.permissionTables).Count -ne 3) { throw 'The three failed-V37 tables are not all present.' }
    foreach ($table in $script:PermissionTables) {
        if ([int64]$State.permissionTableCounts[$table] -ne 0) { throw "Failed-V37 table $table contains data; cleanup is forbidden." }
    }
    if (@($State.permissionVersionColumns).Count -ne 2) { throw 'The two failed-V37 permission_version columns are not both present.' }
    foreach ($column in $State.permissionVersionColumns) {
        if ($column.columnType -notmatch '^bigint' -or $column.nullable -ne 'NO' -or "$($column.defaultValue)" -ne '1') {
            throw 'A failed-V37 permission_version column definition is not the approved default-only shape.'
        }
        if ([int64]$State.permissionVersionNonDefaultRows[$column.table] -ne 0) {
            throw "The $($column.table).permission_version column contains a non-default value; cleanup is forbidden."
        }
    }
}

function Assert-FinalV37State {
    param($State, $BaselineProjection)
    if ([int]$State.baseTableCount -ne 73) { throw 'The PostV37 target does not contain exactly 73 base tables.' }
    $v37 = @($State.historyRows | Where-Object version -eq 37)
    if ($State.totalHistoryRows -ne 37 -or $v37.Count -ne 1 -or $v37[0].success -ne 1 -or
        $v37[0].script -cne $script:FinalV37Script -or [int]$v37[0].checksum -ne $script:FinalV37Checksum) {
        throw 'The final V37 history row is missing, unsuccessful, duplicated, or has the wrong checksum.'
    }
    if ($State.failedHistoryRows -ne 0) { throw 'Flyway history still contains a failed migration.' }
    if (@($State.permissionTables).Count -ne 3 -or @($State.permissionVersionColumns).Count -ne 2) {
        throw 'The final V37 schema does not contain exactly three permission tables and two version columns.'
    }
    foreach ($column in $State.permissionVersionColumns) {
        if ($column.columnType -notmatch '^bigint' -or $column.nullable -ne 'NO' -or "$($column.defaultValue)" -ne '1') {
            throw 'A final V37 permission_version column does not have the approved non-null bigint default.'
        }
    }
    if ([int64]$State.permissionTableCounts.permission_catalog -ne $script:PermissionCatalogCount) {
        throw 'The final permission catalog does not contain exactly 42 entries.'
    }
    if ($BaselineProjection -and (Get-HistoryProjection $State.historyRows) -cne $BaselineProjection) {
        throw 'V1-V36 installed_rank, script, checksum, or success changed during V37 repair.'
    }
}

function Get-RequiredJsonPropertyValue {
    param([Parameter(Mandatory)]$Object, [Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Label)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { throw "$Label is missing $Name." }
    $property.Value
}

function Resolve-ApprovedPreV37Report {
    if (-not $ApprovedPreV37Report -or -not $ApprovedPreV37ReportSha256) {
        throw 'Rehearse and Apply require a separately hashed PreV37 validation PASS report.'
    }
    $path = Assert-RestrictedArtifact -Path $ApprovedPreV37Report -Sha256 $ApprovedPreV37ReportSha256
    $sha256 = Get-NormalizedSha256 $ApprovedPreV37ReportSha256
    $report = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([string]$report.status -cne 'PASS' -or [string]$report.phase -cne 'PreV37' -or
        -not [bool]$report.strictExactMatch -or @($report.approvedExclusions).Count -ne 0 -or
        [string]$report.target.host -cne $script:TargetHost -or [int]$report.target.port -ne $script:TargetPort -or
        [string]$report.target.database -cne $script:FinalDatabase -or [string]$report.target.version -cne $script:ExpectedMySqlVersion -or
        [int]$report.target.tableCount -ne 73) {
        throw 'The approved PreV37 report is not an exact PASS for 127.0.0.1:3307/store_profit_mysql8_final on MySQL 8.0.46.'
    }
    Assert-FingerprintContract -Contract $report.sensitiveExclusionContract -Label 'Approved PreV37 report fingerprint contract'
    [pscustomobject]@{ path = $path; sha256 = $sha256; report = $report }
}

function Assert-JsonObjectFields {
    param([Parameter(Mandatory)]$Object, [string[]]$Expected, [Parameter(Mandatory)][string]$Label)
    $actual = if ($Object -is [Collections.IDictionary]) { @($Object.Keys) } else { @($Object.PSObject.Properties.Name) }
    Assert-ExactNameSet -Actual $actual -Expected $Expected -Label $Label
}

function Assert-PreV37ImportStateRecord {
    param([Parameter(Mandatory)]$State, [Parameter(Mandatory)][string]$Label)
    Assert-JsonObjectFields -Object $State -Expected @(
        'flywayRows','successfulV1ToV36Rows','failedV37Rows','failedV37Checksum',
        'failedV37Tables','failedV37TableRows','permissionVersionColumns','nonDefaultPermissionVersionRows'
    ) -Label "$Label fields"
    if ([int]$State.flywayRows -ne 37 -or [int]$State.successfulV1ToV36Rows -ne 36 -or
        [int]$State.failedV37Rows -ne 1 -or [int]$State.failedV37Checksum -ne $script:FailedV37Checksum -or
        [int]$State.failedV37Tables -ne 3 -or [int64]$State.failedV37TableRows -ne 0 -or
        [int]$State.permissionVersionColumns -ne 2 -or [int64]$State.nonDefaultPermissionVersionRows -ne 0) {
        throw "$Label is not the exact approved failed-V37 PreV37 state."
    }
}

function Assert-SensitiveClearanceRecord {
    param([Parameter(Mandatory)]$Clearance, [Parameter(Mandatory)][string]$Label)
    Assert-JsonObjectFields -Object $Clearance -Expected @(
        'contract','authUserCount','clearedPasswordMarkerCount','authTokenCount','platformSecretCount','sensitiveKvCount'
    ) -Label "$Label fields"
    if ([string]$Clearance.contract -cne 'AI_PROFIT_OS_SENSITIVE_CLEARANCE_V1' -or
        [int64]$Clearance.authUserCount -ne [int64]$Clearance.clearedPasswordMarkerCount -or
        [int64]$Clearance.authTokenCount -ne 0 -or [int64]$Clearance.platformSecretCount -ne 0 -or
        [int64]$Clearance.sensitiveKvCount -ne 0) {
        throw "$Label does not prove complete credential clearance."
    }
}

function Resolve-ApprovedImportReceipt {
    if (-not $ApprovedImportReceipt -or -not $ApprovedImportReceiptSha256) {
        throw 'Rehearse and Apply require the separately hashed, read-only import receipt.'
    }
    $generatorPath = Join-Path $script:ProjectRoot 'scripts\mysql8-logical-migration.ps1'
    if (-not (Test-Path -LiteralPath $generatorPath -PathType Leaf) -or
        (Get-FileHash -LiteralPath $generatorPath -Algorithm SHA256).Hash -cne $script:ImportReceiptGeneratorSha256) {
        throw 'The fixed import-receipt generator is missing or does not match its approved SHA-256.'
    }
    $path = Assert-RestrictedArtifact -Path $ApprovedImportReceipt -Sha256 $ApprovedImportReceiptSha256 -RequireReadOnly
    if ((Split-Path -Leaf $path) -cne 'mysql8-import-receipt.json') { throw 'The import receipt filename is not the approved fixed name.' }
    $sha256 = Get-NormalizedSha256 $ApprovedImportReceiptSha256
    $receipt = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-JsonObjectFields -Object $receipt -Expected @(
        'schemaVersion','generator','generatedAt','status','taskOwnership','sourceEvidence','toolchain','stage',
        'sanitizedDump','final','rehearsal','invariants','credentialsPersisted'
    ) -Label 'Import receipt'
    Assert-JsonObjectFields -Object $receipt.taskOwnership -Expected @(
        'stageDidNotExistBefore','finalDidNotExistBefore','rehearsalDidNotExistBefore','stageCreatedByTask',
        'finalCreatedByTask','rehearsalCreatedByTask','preservedEvidenceDatabaseAccessed','preservedEvidenceDatabaseModified'
    ) -Label 'Import task ownership'
    Assert-JsonObjectFields -Object $receipt.sourceEvidence -Expected @(
        'rawDumpSha256','compatibleDumpSha256','transformManifestSha256','rawDumpImported','compatibleDumpImportedOnlyIntoStage'
    ) -Label 'Import source evidence'
    Assert-JsonObjectFields -Object $receipt.toolchain -Expected @('mysql','mysqldump') -Label 'Import toolchain'
    foreach ($toolName in @('mysql','mysqldump')) {
        Assert-JsonObjectFields -Object $receipt.toolchain.$toolName -Expected @('path','sha256') -Label "Import tool $toolName"
    }
    Assert-JsonObjectFields -Object $receipt.stage -Expected @(
        'host','port','database','tableCount','accountScope','credentialBearingImportReceived','clearedBeforeSanitizedDump',
        'clearance','preV37State','retainedUntilFinalValidation'
    ) -Label 'Import stage'
    Assert-JsonObjectFields -Object $receipt.sanitizedDump -Expected @('path','sha256','size','manifestPath','manifestSha256','readOnly') -Label 'Sanitized dump'
    foreach ($targetName in @('final','rehearsal')) {
        Assert-JsonObjectFields -Object $receipt.$targetName -Expected @(
            'host','port','database','tableCount','accountScope','clearance','preV37State','importSourceSha256'
        ) -Label "Import $targetName target"
    }
    Assert-JsonObjectFields -Object $receipt.invariants -Expected @(
        'exactly73TablesEach','sameSanitizedDumpForFinalAndRehearsal','credentialBearingSourceImportedOnlyIntoStage',
        'rawCredentialsNeverEnteredFinalOrRehearsal','callerToolOverrideAccepted'
    ) -Label 'Import invariants'

    if ([string]$receipt.schemaVersion -cne $script:ImportReceiptSchema -or
        [string]$receipt.generator -cne $script:ImportReceiptGenerator -or [string]$receipt.status -cne 'PASS' -or
        [string]::IsNullOrWhiteSpace([string]$receipt.generatedAt) -or [bool]$receipt.credentialsPersisted) {
        throw 'The import receipt identity or status is not approved.'
    }
    if (-not [bool]$receipt.taskOwnership.stageDidNotExistBefore -or -not [bool]$receipt.taskOwnership.finalDidNotExistBefore -or
        -not [bool]$receipt.taskOwnership.rehearsalDidNotExistBefore -or -not [bool]$receipt.taskOwnership.stageCreatedByTask -or
        -not [bool]$receipt.taskOwnership.finalCreatedByTask -or -not [bool]$receipt.taskOwnership.rehearsalCreatedByTask -or
        [bool]$receipt.taskOwnership.preservedEvidenceDatabaseAccessed -or [bool]$receipt.taskOwnership.preservedEvidenceDatabaseModified) {
        throw 'The import receipt does not prove task-owned creation of three previously absent databases.'
    }
    if ([string]$receipt.sourceEvidence.rawDumpSha256 -cne $script:ApprovedRawDumpSha256 -or
        [string]$receipt.sourceEvidence.compatibleDumpSha256 -cne $script:ApprovedCompatibleDumpSha256 -or
        [string]$receipt.sourceEvidence.transformManifestSha256 -cne $script:ApprovedTransformManifestSha256 -or
        [bool]$receipt.sourceEvidence.rawDumpImported -or -not [bool]$receipt.sourceEvidence.compatibleDumpImportedOnlyIntoStage) {
        throw 'The import receipt source evidence differs from the one approved logical migration chain.'
    }
    if ([string]$receipt.toolchain.mysql.path -cne $script:MySqlClientPath -or [string]$receipt.toolchain.mysql.sha256 -cne $script:MySqlClientSha256 -or
        [string]$receipt.toolchain.mysqldump.path -cne $script:MySqlDumpPath -or [string]$receipt.toolchain.mysqldump.sha256 -cne $script:MySqlDumpSha256) {
        throw 'The import receipt does not bind the fixed MySQL 8 toolchain.'
    }

    $targetContracts = @(
        @('stage', $script:StageDatabase),
        @('final', $script:FinalDatabase),
        @('rehearsal', $script:RehearsalDatabase)
    )
    foreach ($targetContract in $targetContracts) {
        $name = [string]$targetContract[0]
        $database = [string]$targetContract[1]
        $target = $receipt.$name
        if ([string]$target.host -cne $script:TargetHost -or [int]$target.port -ne $script:TargetPort -or
            [string]$target.database -cne $database -or [int]$target.tableCount -ne 73) {
            throw "The import receipt $name identity is not the fixed 73-table target."
        }
        Assert-AccountScopeRecord -Scope $target.accountScope -ExpectedDatabase $database -Label "Import $name account scope"
        Assert-SensitiveClearanceRecord -Clearance $target.clearance -Label "Import $name clearance"
        Assert-PreV37ImportStateRecord -State $target.preV37State -Label "Import $name PreV37 state"
    }
    if (-not [bool]$receipt.stage.credentialBearingImportReceived -or -not [bool]$receipt.stage.clearedBeforeSanitizedDump -or
        -not [bool]$receipt.stage.retainedUntilFinalValidation) {
        throw 'The stage receipt does not prove clearance before the sanitized dump and evidence retention.'
    }
    if ([int64]$receipt.stage.clearance.authUserCount -ne [int64]$receipt.final.clearance.authUserCount -or
        [int64]$receipt.final.clearance.authUserCount -ne [int64]$receipt.rehearsal.clearance.authUserCount) {
        throw 'The task-owned stage, final, and rehearsal credential-clearance populations differ.'
    }
    if (-not [bool]$receipt.invariants.exactly73TablesEach -or -not [bool]$receipt.invariants.sameSanitizedDumpForFinalAndRehearsal -or
        -not [bool]$receipt.invariants.credentialBearingSourceImportedOnlyIntoStage -or
        -not [bool]$receipt.invariants.rawCredentialsNeverEnteredFinalOrRehearsal -or [bool]$receipt.invariants.callerToolOverrideAccepted) {
        throw 'The import receipt invariants are not the exact approved fail-closed set.'
    }

    $sanitizedSha256 = Get-NormalizedSha256 ([string]$receipt.sanitizedDump.sha256)
    $sanitizedPath = Assert-RestrictedArtifact -Path ([string]$receipt.sanitizedDump.path) -Sha256 $sanitizedSha256 -RequireReadOnly
    $manifestSha256 = Get-NormalizedSha256 ([string]$receipt.sanitizedDump.manifestSha256)
    $manifestPath = Assert-RestrictedArtifact -Path ([string]$receipt.sanitizedDump.manifestPath) -Sha256 $manifestSha256 -RequireReadOnly
    if (-not [bool]$receipt.sanitizedDump.readOnly -or [int64]$receipt.sanitizedDump.size -le 0 -or
        [int64](Get-Item -LiteralPath $sanitizedPath).Length -ne [int64]$receipt.sanitizedDump.size -or
        [string]$receipt.final.importSourceSha256 -cne $sanitizedSha256 -or
        [string]$receipt.rehearsal.importSourceSha256 -cne $sanitizedSha256) {
        throw 'The final and rehearsal databases are not hash-bound to the same protected sanitized dump.'
    }
    [pscustomobject]@{
        path = $path
        sha256 = $sha256
        receipt = $receipt
        generatorPath = $generatorPath
        generatorSha256 = $script:ImportReceiptGeneratorSha256
        sanitizedDumpPath = $sanitizedPath
        sanitizedDumpSha256 = $sanitizedSha256
        sanitizedManifestPath = $manifestPath
        sanitizedManifestSha256 = $manifestSha256
    }
}

function Assert-PreV37FingerprintBinding {
    param([Parameter(Mandatory)]$Approval, [Parameter(Mandatory)]$Before)
    Assert-FingerprintContract -Contract $Before.fingerprintContract -Label 'Current PreV37 fingerprint contract'
    foreach ($table in @($script:V37DataChangedTables + $script:V37NewTables | Sort-Object -Unique)) {
        $approved = [string](Get-RequiredJsonPropertyValue -Object $Approval.report.targetTableDataFingerprints -Name $table -Label 'Approved PreV37 data fingerprints')
        if ($approved -cne [string]$Before.data[$table]) { throw "Current pre-repair data is not bound to the approved PreV37 report for $table." }
    }
    foreach ($table in @($script:V37SchemaChangedTables + $script:V37NewTables | Sort-Object -Unique)) {
        $approved = [string](Get-RequiredJsonPropertyValue -Object $Approval.report.targetTableSchemaFingerprints -Name $table -Label 'Approved PreV37 schema fingerprints')
        if ($approved -cne [string]$Before.schema[$table]) { throw "Current pre-repair schema is not bound to the approved PreV37 report for $table." }
    }
}

function New-V37TransitionEvidence {
    param(
        [Parameter(Mandatory)]$Approval,
        [Parameter(Mandatory)]$Before,
        [Parameter(Mandatory)]$After,
        [Parameter(Mandatory)]$Inventory,
        [Parameter(Mandatory)]$ImportApproval
    )
    Assert-FingerprintContract -Contract $Before.fingerprintContract -Label 'PreV37 transition fingerprint contract'
    Assert-FingerprintContract -Contract $After.fingerprintContract -Label 'PostV37 transition fingerprint contract'
    if ([string]$Before.fingerprintContract.sha256 -cne [string]$After.fingerprintContract.sha256) {
        throw 'The sensitive-field fingerprint contract changed during V37 repair.'
    }
    $dataTransitions = [ordered]@{}
    foreach ($table in $script:V37DataChangedTables) {
        $dataTransitions[$table] = [ordered]@{ before = [string]$Before.data[$table]; after = [string]$After.data[$table] }
    }
    $schemaTransitions = [ordered]@{}
    foreach ($table in $script:V37SchemaChangedTables) {
        $schemaTransitions[$table] = [ordered]@{ before = [string]$Before.schema[$table]; after = [string]$After.schema[$table] }
    }
    $newData = [ordered]@{}
    $newSchema = [ordered]@{}
    foreach ($table in $script:V37NewTables) {
        $newData[$table] = [string]$After.data[$table]
        $newSchema[$table] = [string]$After.schema[$table]
    }
    $v37 = @($Inventory | Where-Object version -eq 37)
    if ($v37.Count -ne 1) { throw 'Cannot bind transition evidence to one authoritative V37 migration.' }
    $evidence = [ordered]@{
        evidenceType = 'V37_APPROVED_DIFFERENCES'
        preV37ValidationReportSha256 = $Approval.sha256
        importReceiptSha256 = $ImportApproval.sha256
        sanitizedDumpSha256 = $ImportApproval.sanitizedDumpSha256
        fingerprintContract = $Before.fingerprintContract
        migration = [ordered]@{
            version = '37'
            script = $script:FinalV37Script
            sha256 = $script:FinalV37Sha256
            flywayChecksum = $script:FinalV37Checksum
        }
        approvedNewTables = $script:V37NewTables
        approvedDataChangedTables = $script:V37DataChangedTables
        approvedSchemaChangedTables = $script:V37SchemaChangedTables
        tableDataTransitions = $dataTransitions
        tableSchemaTransitions = $schemaTransitions
        newTableDataFingerprints = $newData
        newTableSchemaFingerprints = $newSchema
    }
    $path = Join-Path $RunRoot 'v37-approved-differences.json'
    $sha256 = Write-JsonEvidence -Path $path -Value $evidence -ReadOnly
    [pscustomobject]@{ path = $path; sha256 = $sha256 }
}

function New-PreCleanArtifacts {
    param([Parameter(Mandatory)][Management.Automation.PSCredential]$Credential, $State, $Inventory, $RuntimeProbe, $FingerprintContract)
    Assert-FingerprintContract -Contract $FingerprintContract -Label 'Pre-clean fingerprint contract'
    $schemaDump = Invoke-SchemaDump -Credential $Credential
    $schemaPath = Join-Path $RunRoot 'failed-v37-schema-before.sql'
    Write-Utf8File -Path $schemaPath -Content $schemaDump -ReadOnly

    $cleanupPath = Join-Path $RunRoot 'v37-failed-cleanup.sql'
    $cleanup = @'
DROP TABLE `user_permission_override`;
DROP TABLE `user_data_scope`;
DROP TABLE `permission_catalog`;
ALTER TABLE `auth_token` DROP COLUMN `permission_version`;
ALTER TABLE `auth_user` DROP COLUMN `permission_version`;
'@
    Write-Utf8File -Path $cleanupPath -Content ($cleanup.Trim() + "`r`n") -ReadOnly

    $columnRollback = [Collections.Generic.List[string]]::new()
    foreach ($table in @('auth_user', 'auth_token')) {
        $column = @($State.permissionVersionColumns | Where-Object table -eq $table)
        if ($column.Count -ne 1) { throw "Cannot construct the exact $table rollback statement." }
        $position = if ($column[0].previousColumn) { "AFTER ``$($column[0].previousColumn)``" } else { 'FIRST' }
        $columnRollback.Add("ALTER TABLE ``$table`` ADD COLUMN ``permission_version`` $($column[0].columnType) NOT NULL DEFAULT 1 $position;")
    }
    $rollbackPath = Join-Path $RunRoot 'v37-pre-repair-rollback.sql'
    $rollback = @(
        '-- Valid only after failed-V37 DDL cleanup and before Flyway repair.',
        '-- After Flyway repair begins, restore the protected pre-clean logical backup to a new database instead.',
        ($columnRollback -join "`r`n"),
        $schemaDump
    ) -join "`r`n"
    Write-Utf8File -Path $rollbackPath -Content ($rollback.Trim() + "`r`n") -ReadOnly

    $evidencePath = Join-Path $RunRoot 'v37-pre-clean-evidence.json'
    $evidence = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        status = 'PASS'
        mode = $Mode
        target = [ordered]@{
            host = $script:TargetHost
            port = $script:TargetPort
            database = $TargetDatabase
            mysqlVersion = $State.mysqlVersion
            tableCount = $State.baseTableCount
            accountScope = $State.accountScope
        }
        flyway = [ordered]@{
            pluginVersion = $script:FlywayVersion
            migrationLocation = ('filesystem:' + ($script:MigrationDirectory -replace '\\', '/'))
            baselineOnMigrate = $false
            runtimeProbe = $RuntimeProbe
            history = $State.historyRows
            v1ToV36ProjectionSha256 = Get-TextSha256 (Get-HistoryProjection $State.historyRows)
            failedRows = $State.failedHistoryRows
        }
        failedV37 = [ordered]@{
            checksum = $script:FailedV37Checksum
            tableCounts = $State.permissionTableCounts
            permissionVersionColumns = $State.permissionVersionColumns
            nonDefaultPermissionVersionRows = $State.permissionVersionNonDefaultRows
        }
        authoritativeMigrations = $Inventory
        fingerprintContract = $FingerprintContract
        trustedTools = [ordered]@{
            mysql = [ordered]@{ path = $script:MySqlClientPath; sha256 = $script:MySqlClientSha256 }
            mysqldump = [ordered]@{ path = $script:MySqlDumpPath; sha256 = $script:MySqlDumpSha256 }
            maven = [ordered]@{ path = $script:MavenPath; sha256 = $script:MavenSha256 }
            flywayPluginJar = [ordered]@{ path = $script:FlywayPluginJar; sha256 = $script:FlywayPluginJarSha256 }
        }
        credentialsPersisted = $false
        activeApplicationConnections = 0
    }
    [void](Write-JsonEvidence -Path $evidencePath -Value $evidence -ReadOnly)

    $manifestPath = Join-Path $RunRoot 'v37-pre-clean-manifest.json'
    $manifest = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        files = @($schemaPath, $cleanupPath, $rollbackPath, $evidencePath) | ForEach-Object {
            [ordered]@{ name = Split-Path -Leaf $_; size = (Get-Item -LiteralPath $_).Length; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
        }
    }
    [void](Write-JsonEvidence -Path $manifestPath -Value $manifest -ReadOnly)
    [pscustomobject]@{
        schemaPath = $schemaPath
        cleanupPath = $cleanupPath
        rollbackPath = $rollbackPath
        evidencePath = $evidencePath
        manifestPath = $manifestPath
        manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash
    }
}

function Assert-TrustedTool {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$ExpectedSha256, [Parameter(Mandatory)][string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label is unavailable at its fixed absolute path." }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -cne (Get-NormalizedSha256 $ExpectedSha256)) { throw "$Label does not match its approved SHA-256." }
    [pscustomobject]@{ path = $Path; sha256 = $actual }
}

function Assert-FlywayRuntimeAvailable {
    [void](Assert-TrustedTool -Path $script:MySqlClientPath -ExpectedSha256 $script:MySqlClientSha256 -Label 'MySQL 8 client')
    [void](Assert-TrustedTool -Path $script:MySqlDumpPath -ExpectedSha256 $script:MySqlDumpSha256 -Label 'MySQL 8 logical dump client')
    [void](Assert-TrustedTool -Path $script:MavenPath -ExpectedSha256 $script:MavenSha256 -Label 'Pinned Maven launcher')
    [void](Assert-TrustedTool -Path $script:FlywayPluginJar -ExpectedSha256 $script:FlywayPluginJarSha256 -Label 'Pinned Flyway Maven plugin JAR')
    $script:MavenPath
}

function Invoke-PinnedFlyway {
    param(
        [Parameter(Mandatory)][Management.Automation.PSCredential]$Credential,
        [ValidateSet('info', 'repair', 'migrate', 'validate')][string]$Action,
        [Parameter(Mandatory)][string]$MavenExecutable
    )
    $result = Use-CredentialSecret -Credential $Credential -Action {
        param($user, $password)
        $location = 'filesystem:' + ($script:MigrationDirectory -replace '\\', '/')
        $url = "jdbc:mysql://127.0.0.1:3307/$TargetDatabase?createDatabaseIfNotExist=false&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=false"
        $arguments = @('-q', '-B', '-ntp', "-Dflyway.version=$($script:FlywayVersion)", "$($script:FlywayPlugin):$Action")
        Invoke-SecretProcess -FilePath $MavenExecutable -Arguments $arguments -WorkingDirectory $script:BackendDirectory -SecretEnvironment @{
                FLYWAY_URL = $url
                FLYWAY_USER = $user
                FLYWAY_PASSWORD = $password
                FLYWAY_LOCATIONS = $location
                FLYWAY_SCHEMAS = $TargetDatabase
                FLYWAY_DEFAULT_SCHEMA = $TargetDatabase
                FLYWAY_TABLE = 'flyway_schema_history'
                FLYWAY_BASELINE_ON_MIGRATE = 'false'
                FLYWAY_CLEAN_DISABLED = 'true'
                FLYWAY_CONNECT_RETRIES = '0'
                FLYWAY_VALIDATE_MIGRATION_NAMING = 'true'
            }
    }
    if ($result.ExitCode -ne 0) {
        throw "Pinned Flyway $Action failed (exit=$($result.ExitCode), stdoutSha256=$($result.StdoutSha256), stderrSha256=$($result.StderrSha256))."
    }
    [pscustomobject]@{ action = $Action; exitCode = 0; stdoutSha256 = $result.StdoutSha256; stderrSha256 = $result.StderrSha256 }
}

function Assert-RehearsalReceipt {
    param([Parameter(Mandatory)][string]$ExpectedPreV37ReportSha256, [Parameter(Mandatory)]$ImportApproval)
    if (-not $RehearsalReceipt -or -not $RehearsalReceiptSha256) { throw 'Apply requires a hash-bound PASS rehearsal receipt.' }
    $path = Assert-RestrictedArtifact -Path $RehearsalReceipt -Sha256 $RehearsalReceiptSha256 -RequireReadOnly
    $receipt = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($receipt.status -cne 'PASS' -or $receipt.mode -cne 'Rehearse' -or
        [string]$receipt.target.host -cne $script:TargetHost -or [int]$receipt.target.port -ne $script:TargetPort -or
        [string]$receipt.target.mysqlVersion -cne $script:ExpectedMySqlVersion -or
        [int]$receipt.target.tableCount -ne 73 -or
        [string]$receipt.target.database -cne $script:RehearsalDatabase) {
        throw 'The rehearsal receipt is not an approved V37 rehearsal PASS.'
    }
    Assert-SanitizedAccountScopeRecord -Scope $receipt.target.accountScope -Label 'Rehearsal receipt account scope'
    $expectedLocation = 'filesystem:' + ($script:MigrationDirectory -replace '\\', '/')
    if ($receipt.flyway.pluginVersion -cne $script:FlywayVersion -or
        [string]$receipt.flyway.migrationLocation -cne $expectedLocation -or
        $null -eq $receipt.flyway.PSObject.Properties['baselineOnMigrate'] -or [bool]$receipt.flyway.baselineOnMigrate -or
        [string]$receipt.flyway.v37Script -cne $script:FinalV37Script -or
        [string]$receipt.flyway.v37Sha256 -cne $script:FinalV37Sha256 -or
        [int]$receipt.flyway.v37Checksum -ne $script:FinalV37Checksum -or [int]$receipt.flyway.failedRows -ne 0) {
        throw 'The rehearsal receipt does not bind the approved Flyway runtime, migration location, V37 script, SHA-256, and checksum.'
    }
    if ([int]$receipt.validation.permissionCatalogCount -ne 42 -or [int]$receipt.validation.permissionTables -ne 3 -or [int]$receipt.validation.permissionVersionColumns -ne 2) {
        throw 'The rehearsal receipt does not contain the required V37 validation counts.'
    }
    foreach ($tool in @(
        @('mysql', $script:MySqlClientPath, $script:MySqlClientSha256),
        @('mysqldump', $script:MySqlDumpPath, $script:MySqlDumpSha256),
        @('maven', $script:MavenPath, $script:MavenSha256),
        @('flywayPluginJar', $script:FlywayPluginJar, $script:FlywayPluginJarSha256)
    )) {
        $record = $receipt.trustedTools.PSObject.Properties[[string]$tool[0]]
        if ($null -eq $record -or [string]$record.Value.path -cne [string]$tool[1] -or [string]$record.Value.sha256 -cne [string]$tool[2]) {
            throw 'The rehearsal receipt does not bind the exact approved toolchain paths and SHA-256 values.'
        }
    }
    $expectedPreSha = Get-NormalizedSha256 $ExpectedPreV37ReportSha256
    if ([string]$receipt.evidence.preV37ValidationReportSha256 -cne $expectedPreSha) {
        throw 'The rehearsal receipt is bound to a different PreV37 validation report.'
    }
    Assert-JsonObjectFields -Object $receipt.evidence.importReceipt -Expected @('path','sha256','generatorSha256','sanitizedDumpSha256') -Label 'Rehearsal import-receipt reference'
    if ([IO.Path]::GetFullPath([string]$receipt.evidence.importReceipt.path) -ine [IO.Path]::GetFullPath($ImportApproval.path) -or
        [string]$receipt.evidence.importReceipt.sha256 -cne [string]$ImportApproval.sha256 -or
        [string]$receipt.evidence.importReceipt.generatorSha256 -cne $script:ImportReceiptGeneratorSha256 -or
        [string]$receipt.evidence.importReceipt.sanitizedDumpSha256 -cne [string]$ImportApproval.sanitizedDumpSha256) {
        throw 'The rehearsal receipt is bound to a different import task or sanitized dump.'
    }
    Assert-FingerprintContract -Contract $receipt.evidence.fingerprintContract -Label 'Rehearsal receipt fingerprint contract'
    $transitionPath = Assert-RestrictedArtifact -Path ([string]$receipt.evidence.transitionEvidence.path) -Sha256 ([string]$receipt.evidence.transitionEvidence.sha256) -RequireReadOnly
    $transition = Get-Content -LiteralPath $transitionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-FingerprintContract -Contract $transition.fingerprintContract -Label 'Rehearsal transition fingerprint contract'
    if ([string]$transition.evidenceType -cne 'V37_APPROVED_DIFFERENCES' -or
        [string]$transition.preV37ValidationReportSha256 -cne $expectedPreSha -or
        [string]$transition.importReceiptSha256 -cne [string]$ImportApproval.sha256 -or
        [string]$transition.sanitizedDumpSha256 -cne [string]$ImportApproval.sanitizedDumpSha256 -or
        [string]$transition.fingerprintContract.sha256 -cne [string]$receipt.evidence.fingerprintContract.sha256 -or
        [string]$transition.migration.script -cne $script:FinalV37Script -or
        [string]$transition.migration.sha256 -cne $script:FinalV37Sha256 -or
        [int]$transition.migration.flywayChecksum -ne $script:FinalV37Checksum) {
        throw 'The rehearsal transition evidence is not bound to the approved PreV37 report and final V37 migration.'
    }
    $receipt
}

function Write-RollbackPlan {
    $plan = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        status = 'PLAN_ONLY'
        databaseWritesPerformed = $false
        steps = @(
            'Before Flyway repair: execute only the hash-bound v37-pre-repair-rollback.sql if cleanup DDL must be reversed.',
            'After Flyway repair starts: do not insert or edit flyway_schema_history manually.',
            'Restore the protected pre-clean logical backup into a new isolated database name, validate its hash and V1-V36 history, then obtain new approval.',
            'Never roll back to MySQL 5.5 or port 3306, and never overwrite store_profit_mysql8 evidence.'
        )
    }
    $path = Join-Path $RunRoot 'v37-rollback-plan.json'
    [void](Write-JsonEvidence -Path $path -Value $plan -ReadOnly)
    [pscustomobject]@{ status = 'PLAN_ONLY'; path = $path; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash }
}

function Invoke-ReadOnlyStateMode {
    param([ValidateSet('Preflight', 'Validate')][string]$RequestedMode)
    Assert-TargetRuntime
    $mavenExecutable = Assert-FlywayRuntimeAvailable
    $inventory = Get-MigrationInventory
    $credential = Get-DatabaseCredential
    if (-not $credential) { throw 'Credential entry was cancelled.' }
    try {
        Assert-NoApplicationConnections -Credential $credential
        $state = Get-DatabaseState -Credential $credential -Inventory $inventory
        $fingerprintContract = Get-SensitiveExclusionContract -Credential $credential
        $runtimeProbe = Invoke-PinnedFlyway -Credential $credential -Action info -MavenExecutable $mavenExecutable
        Assert-NoApplicationConnections -Credential $credential
        if ($RequestedMode -eq 'Preflight') {
            Assert-FailedV37State -State $state
            $artifacts = New-PreCleanArtifacts -Credential $credential -State $state -Inventory $inventory -RuntimeProbe $runtimeProbe -FingerprintContract $fingerprintContract
            return [pscustomobject]@{ status = 'PASS'; mode = 'Preflight'; databaseWritesPerformed = $false; evidenceManifest = $artifacts.manifestPath; evidenceManifestSha256 = $artifacts.manifestSha256 }
        }
        Assert-FinalV37State -State $state -BaselineProjection $null
        $reportPath = Join-Path $RunRoot 'v37-validation.json'
        $report = [ordered]@{
            generatedAt = (Get-Date).ToString('o')
            status = 'PASS'
            mode = 'Validate'
            databaseWritesPerformed = $false
            target = [ordered]@{
                host = $script:TargetHost
                port = $script:TargetPort
                database = $TargetDatabase
                mysqlVersion = $state.mysqlVersion
                tableCount = $state.baseTableCount
                accountScope = $state.accountScope
            }
            flyway = [ordered]@{
                pluginVersion = $script:FlywayVersion
                migrationLocation = ('filesystem:' + ($script:MigrationDirectory -replace '\\', '/'))
                baselineOnMigrate = $false
                version = 37
                script = $script:FinalV37Script
                sha256 = $script:FinalV37Sha256
                checksum = $script:FinalV37Checksum
                failedRows = $state.failedHistoryRows
                runtimeProbe = $runtimeProbe
            }
            validation = [ordered]@{ permissionCatalogCount = $state.permissionTableCounts.permission_catalog; permissionTables = @($state.permissionTables).Count; permissionVersionColumns = @($state.permissionVersionColumns).Count }
            fingerprintContract = $fingerprintContract
        }
        [void](Write-JsonEvidence -Path $reportPath -Value $report -ReadOnly)
        [pscustomobject]@{ status = 'PASS'; mode = 'Validate'; report = $reportPath; reportSha256 = (Get-FileHash $reportPath -Algorithm SHA256).Hash }
    }
    finally { $credential = $null }
}

function Invoke-RepairStateMachine {
    param([ValidateSet('Rehearse', 'Apply')][string]$RequestedMode)
    if (-not $Execute) { throw "$RequestedMode is fail-closed unless -Execute is explicitly supplied." }
    $preV37Approval = Resolve-ApprovedPreV37Report
    $importApproval = Resolve-ApprovedImportReceipt
    if ($RequestedMode -eq 'Rehearse') {
        if ($TargetDatabase -cne $script:RehearsalDatabase) {
            throw 'Rehearse may run only against the fixed store_profit_mysql8_final_rehearsal database.'
        }
    }
    else {
        if ($TargetDatabase -cne $script:FinalDatabase) { throw 'Apply may run only against store_profit_mysql8_final.' }
        [void](Assert-RehearsalReceipt -ExpectedPreV37ReportSha256 $preV37Approval.sha256 -ImportApproval $importApproval)
    }
    if (-not $PreCleanBackupPath -or -not $PreCleanBackupSha256) { throw "$RequestedMode requires a protected pre-clean logical backup and SHA-256." }
    $backupPath = Assert-RestrictedArtifact -Path $PreCleanBackupPath -Sha256 $PreCleanBackupSha256 -RequireReadOnly
    $mavenExecutable = Assert-FlywayRuntimeAvailable
    Assert-TargetRuntime
    $inventory = Get-MigrationInventory
    $credential = Get-DatabaseCredential
    if (-not $credential) { throw 'Credential entry was cancelled.' }
    try {
        Assert-NoApplicationConnections -Credential $credential
        $before = Get-DatabaseState -Credential $credential -Inventory $inventory
        Assert-FailedV37State -State $before
        $transitionBefore = Get-V37TransitionFingerprintState -Credential $credential
        Assert-PreV37FingerprintBinding -Approval $preV37Approval -Before $transitionBefore
        $baseline = Get-HistoryProjection $before.historyRows
        $runtimeProbe = Invoke-PinnedFlyway -Credential $credential -Action info -MavenExecutable $mavenExecutable
        Assert-NoApplicationConnections -Credential $credential
        $artifacts = New-PreCleanArtifacts -Credential $credential -State $before -Inventory $inventory -RuntimeProbe $runtimeProbe -FingerprintContract $transitionBefore.fingerprintContract

        $confirmation = Read-Host "Type $($RequestedMode.ToUpperInvariant()) $TargetDatabase to authorize the controlled V37 DDL repair"
        if ($confirmation -cne "$($RequestedMode.ToUpperInvariant()) $TargetDatabase") { throw 'The typed V37 repair confirmation did not match.' }
        Assert-NoApplicationConnections -Credential $credential

        $script:Stage = 'CLEANUP_DDL'
        Invoke-MySqlFile -Credential $credential -Path $artifacts.cleanupPath
        $postCleanupHistory = Get-HistoryState -Credential $credential
        if ((Get-HistoryProjection $postCleanupHistory.rows) -cne $baseline -or (Get-CompleteHistoryProjection $postCleanupHistory.rows) -cne (Get-CompleteHistoryProjection $before.historyRows)) {
            throw 'Flyway history changed during failed-DDL cleanup.'
        }
        $remainingObjects = (Invoke-MySqlLines -Credential $credential -Sql @'
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('permission_catalog','user_permission_override','user_data_scope')),
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name IN ('auth_user','auth_token') AND column_name = 'permission_version');
'@) | Select-Object -First 1
        if ($remainingObjects -ne "0`t0") { throw 'Failed-V37 objects remain after cleanup; Flyway repair is forbidden.' }

        Assert-NoApplicationConnections -Credential $credential
        $script:Stage = 'FLYWAY_REPAIR'
        $repairResult = Invoke-PinnedFlyway -Credential $credential -Action repair -MavenExecutable $mavenExecutable
        $postRepair = Get-HistoryState -Credential $credential
        if ((Get-HistoryProjection $postRepair.rows) -cne $baseline) { throw 'Flyway repair changed V1-V36 history.' }
        if ($postRepair.totalRows -ne 36 -or @($postRepair.rows | Where-Object version -eq 37).Count -ne 0 -or @($postRepair.rows | Where-Object success -eq 0).Count -ne 0) {
            throw 'Flyway repair did not remove exactly the failed V37 row.'
        }

        Assert-NoApplicationConnections -Credential $credential
        $script:Stage = 'FLYWAY_MIGRATE'
        $migrateResult = Invoke-PinnedFlyway -Credential $credential -Action migrate -MavenExecutable $mavenExecutable
        $validateResult = Invoke-PinnedFlyway -Credential $credential -Action validate -MavenExecutable $mavenExecutable
        $afterFirst = Get-DatabaseState -Credential $credential -Inventory $inventory
        Assert-FinalV37State -State $afterFirst -BaselineProjection $baseline
        $firstHistory = ($afterFirst.historyRows | ConvertTo-Json -Depth 5 -Compress)

        $script:Stage = 'SECOND_NOOP_MIGRATE'
        $secondMigrateResult = Invoke-PinnedFlyway -Credential $credential -Action migrate -MavenExecutable $mavenExecutable
        $secondValidateResult = Invoke-PinnedFlyway -Credential $credential -Action validate -MavenExecutable $mavenExecutable
        $afterSecond = Get-DatabaseState -Credential $credential -Inventory $inventory
        Assert-FinalV37State -State $afterSecond -BaselineProjection $baseline
        if (($afterSecond.historyRows | ConvertTo-Json -Depth 5 -Compress) -cne $firstHistory) {
            throw 'The second migrate changed Flyway history; V37 was not idempotent.'
        }
        Assert-NoApplicationConnections -Credential $credential
        $transitionAfter = Get-V37TransitionFingerprintState -Credential $credential
        $transitionEvidence = New-V37TransitionEvidence -Approval $preV37Approval -Before $transitionBefore -After $transitionAfter -Inventory $inventory -ImportApproval $importApproval

        $script:Stage = 'COMPLETE'
        $receiptName = 'v37-apply-receipt.json'
        if ($RequestedMode -eq 'Rehearse') { $receiptName = 'v37-rehearsal-receipt.json' }
        $receiptPath = Join-Path $RunRoot $receiptName
        $v37 = @($inventory | Where-Object version -eq 37)[0]
        $receipt = [ordered]@{
            generatedAt = (Get-Date).ToString('o')
            status = 'PASS'
            mode = $RequestedMode
            target = [ordered]@{
                host = $script:TargetHost
                port = $script:TargetPort
                database = $TargetDatabase
                mysqlVersion = $afterSecond.mysqlVersion
                tableCount = $afterSecond.baseTableCount
                accountScope = $afterSecond.accountScope
            }
            flyway = [ordered]@{
                pluginVersion = $script:FlywayVersion
                migrationLocation = ('filesystem:' + ($script:MigrationDirectory -replace '\\', '/'))
                baselineOnMigrate = $false
                v37Script = $script:FinalV37Script
                v37Checksum = $script:FinalV37Checksum
                v37Sha256 = $script:FinalV37Sha256
                failedRows = $afterSecond.failedHistoryRows
                v1ToV36ProjectionSha256 = Get-TextSha256 $baseline
                repairTransition = [ordered]@{
                    beforeHistorySha256 = Get-TextSha256 (Get-CompleteHistoryProjection $before.historyRows)
                    afterRepairHistorySha256 = Get-TextSha256 (Get-CompleteHistoryProjection $postRepair.rows)
                    removedFailedVersion = 37
                    removedFailedChecksum = $script:FailedV37Checksum
                    v1ToV36Unchanged = $true
                }
                runtimeProbe = $runtimeProbe
                repair = $repairResult
                firstMigrate = $migrateResult
                firstValidate = $validateResult
                secondMigrate = $secondMigrateResult
                secondValidate = $secondValidateResult
            }
            validation = [ordered]@{
                permissionCatalogCount = $afterSecond.permissionTableCounts.permission_catalog
                permissionTables = @($afterSecond.permissionTables).Count
                permissionVersionColumns = @($afterSecond.permissionVersionColumns).Count
                historyStableAcrossSecondMigrate = $true
            }
            evidence = [ordered]@{
                preCleanBackup = [ordered]@{ path = $backupPath; sha256 = Get-NormalizedSha256 $PreCleanBackupSha256 }
                importReceipt = [ordered]@{
                    path = $importApproval.path
                    sha256 = $importApproval.sha256
                    generatorSha256 = $importApproval.generatorSha256
                    sanitizedDumpSha256 = $importApproval.sanitizedDumpSha256
                }
                preV37ValidationReport = [ordered]@{ path = $preV37Approval.path; sha256 = $preV37Approval.sha256 }
                preV37ValidationReportSha256 = $preV37Approval.sha256
                fingerprintContract = $transitionBefore.fingerprintContract
                transitionEvidence = [ordered]@{ path = $transitionEvidence.path; sha256 = $transitionEvidence.sha256 }
                preCleanManifest = $artifacts.manifestPath
                preCleanManifestSha256 = $artifacts.manifestSha256
            }
            trustedTools = [ordered]@{
                mysql = [ordered]@{ path = $script:MySqlClientPath; sha256 = $script:MySqlClientSha256 }
                mysqldump = [ordered]@{ path = $script:MySqlDumpPath; sha256 = $script:MySqlDumpSha256 }
                maven = [ordered]@{ path = $script:MavenPath; sha256 = $script:MavenSha256 }
                flywayPluginJar = [ordered]@{ path = $script:FlywayPluginJar; sha256 = $script:FlywayPluginJarSha256 }
            }
            credentialsPersisted = $false
        }
        [void](Write-JsonEvidence -Path $receiptPath -Value $receipt -ReadOnly)
        [pscustomobject]@{ status = 'PASS'; mode = $RequestedMode; receipt = $receiptPath; receiptSha256 = (Get-FileHash $receiptPath -Algorithm SHA256).Hash }
    }
    finally { $credential = $null }
}

try {
    if ($TargetDatabase -cne $script:FinalDatabase -and $TargetDatabase -cne $script:RehearsalDatabase) {
        throw 'TargetDatabase is neither the approved final database nor the fixed rehearsal database.'
    }
    if (-not $RunRoot) {
        $RunRoot = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\mysql8-v37-repair\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Mode.ToLowerInvariant())
    }
    $RunRoot = Initialize-RestrictedDirectory -Path $RunRoot

    if ($Mode -eq 'RollbackPlan') {
        Write-RollbackPlan | ConvertTo-Json -Compress
        exit 0
    }
    if ($Mode -in @('Preflight', 'Validate')) {
        Invoke-ReadOnlyStateMode -RequestedMode $Mode | ConvertTo-Json -Compress
    }
    else {
        Invoke-RepairStateMachine -RequestedMode $Mode | ConvertTo-Json -Compress
    }
    exit 0
}
catch {
    $message = $_.Exception.Message
    if ($RunRoot -and (Test-Path -LiteralPath $RunRoot -PathType Container)) {
        $blockedPath = Join-Path $RunRoot 'v37-blocked.json'
        $blocked = [ordered]@{
            generatedAt = (Get-Date).ToString('o')
            status = 'BLOCKED'
            mode = $Mode
            stage = $script:Stage
            databaseWritesMayHaveStarted = ($script:Stage -notin @('INITIALIZING'))
            reason = $message
            credentialsPersisted = $false
        }
        try { [void](Write-JsonEvidence -Path $blockedPath -Value $blocked -ReadOnly) } catch {}
    }
    [Console]::Error.WriteLine($message)
    exit 2
}
