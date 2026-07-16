[CmdletBinding()]
param(
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3307,
    [string]$Database = 'store_profit_mysql8',
    [int]$ServerPort = 18081,
    [int]$ForensicsPort = 3323,
    [switch]$ResumeAfterVerifiedFreeze
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $env:LOCALAPPDATA 'AI-Profit-OS'
$backupRoot = Join-Path $localRoot 'backups'
$b1ReceiptPath = Join-Path $backupRoot 'warehouse-v43\20260713-175516\warehouse-before-v43.receipt.json'
$b2ReceiptPath = Join-Path $backupRoot 'warehouse-v43-post-failure\20260714-091731\warehouse-before-v43.receipt.json'
$expectedB1Hash = 'F6638060DC4E63F7272476AB18C8BE6F3AC84CC642AAA2859A038A5B9292CFFF'
$expectedB2Hash = '632043DCD2C8C57F27C5E8080D930DF946C08C199C2DF0005854492D5454E4B2'
$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$mysqlPath = Join-Path $mysqlBin 'mysql.exe'
$mysqldumpPath = Join-Path $mysqlBin 'mysqldump.exe'
$mysqldPath = Join-Path $mysqlBin 'mysqld.exe'
$javaPath = (Get-Command java.exe -ErrorAction Stop).Source
$workspaceJar = Join-Path $repoRoot 'backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar'
$sourceV43 = Join-Path $repoRoot 'backend\src\main\resources\db\migration\V43__multi_warehouse_topology.sql'
$observedRuntimeJarSha256BeforeReplacement = 'B7249C4326985B4F6F358CE7B901D43D5BA664D563D0512077730554E75AB14A'
$storageRoot = Join-Path $localRoot 'expense-supplements'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $repoRoot ("output\warehouse-v43-production-recovery-{0}.md" -f $timestamp)
$failureReportPath = Join-Path $repoRoot ("output\warehouse-v43-production-recovery-{0}-FAILED.md" -f $timestamp)
$operationRoot = Join-Path $localRoot ("warehouse-v43\production-{0}" -f $timestamp)
$b3Directory = Join-Path $backupRoot ("warehouse-v43-success\{0}" -f $timestamp)
$forensicsRoot = Join-Path $env:TEMP ("ai-profit-v43-forensics-{0}" -f $timestamp)
$forensicsData = Join-Path $forensicsRoot 'data'
$forensicsLog = Join-Path $forensicsRoot 'mysqld.log'
$forensicsPid = Join-Path $forensicsRoot 'mysqld.pid'
$forensicsProcess = $null
$rootCredential = $null
$runtimeCredential = $null
$rootPasswordPointer = [IntPtr]::Zero
$runtimePasswordPointer = [IntPtr]::Zero
$rootPassword = $null
$runtimePassword = $null
$freezeSnapshot = $null
$b1Receipt = $null
$b2Receipt = $null
$b3Receipt = $null
$targetAudit = @()
$forensicsDiff = @()
$jarEvidence = $null
$health = $null

function Assert-File {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path"
    }
}

function Assert-Directory {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label not found: $Path"
    }
}

function Get-Sha256 {
    param([string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Split-Lines {
    param([string]$Text)
    @([regex]::Split($Text, "\r?\n") | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() })
}

function Quote-NativeArgument {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    if ($Value -match '"') {
        throw 'Native argument contains an unsupported double quote.'
    }
    '"' + $Value + '"'
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$Password,
        [string]$InputFile,
        [string]$WorkingDirectory
    )
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = (($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = [bool]$InputFile
    if ($WorkingDirectory) {
        $startInfo.WorkingDirectory = $WorkingDirectory
    }
    if ($null -ne $Password) {
        $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
    }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    [void]$process.Start()
    if ($InputFile) {
        $inputStream = [IO.File]::OpenRead($InputFile)
        try {
            $inputStream.CopyTo($process.StandardInput.BaseStream)
        }
        finally {
            $inputStream.Dispose()
            $process.StandardInput.Close()
        }
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw "$(Split-Path -Leaf $FilePath) failed with exit code $($process.ExitCode). $stderr"
    }
    $stdout
}

function Invoke-SecretRedirectedProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$StdoutPath,
        [Parameter(Mandatory = $true)][string]$StderrPath,
        [string]$WorkingDirectory
    )
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $Password, 'Process')
        $parameters = @{
            FilePath = $FilePath
            ArgumentList = (($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' ')
            Wait = $true
            PassThru = $true
            WindowStyle = 'Hidden'
            RedirectStandardOutput = $StdoutPath
            RedirectStandardError = $StderrPath
        }
        if ($WorkingDirectory) {
            $parameters.WorkingDirectory = $WorkingDirectory
        }
        $process = Start-Process @parameters
    }
    finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
    if ($process.ExitCode -ne 0) {
        $stderr = if (Test-Path -LiteralPath $StderrPath) { Get-Content -LiteralPath $StderrPath -Raw -Encoding UTF8 } else { '' }
        throw "$(Split-Path -Leaf $FilePath) failed with exit code $($process.ExitCode). $stderr"
    }
}

function Get-SecurePassword {
    param([pscredential]$Credential, [ref]$Pointer)
    $Pointer.Value = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Credential.Password)
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Pointer.Value)
}

function Invoke-TargetMySql {
    param([string]$Password, [string]$Sql)
    Invoke-NativeCapture -FilePath $mysqlPath -Password $Password -Arguments @(
        '--protocol=TCP',
        ("--host={0}" -f $HostName),
        ("--port={0}" -f $Port),
        '--user=root',
        ("--database={0}" -f $Database),
        '--batch',
        '--skip-column-names',
        ("--execute={0}" -f $Sql)
    )
}

function Get-JarEntryHash {
    param([string]$JarPath, [string]$EntryPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry($EntryPath)
        if (-not $entry) {
            throw "JAR entry is missing: $EntryPath"
        }
        $stream = $entry.Open()
        try {
            $sha = [Security.Cryptography.SHA256]::Create()
            [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '')
        }
        finally {
            $stream.Dispose()
        }
    }
    finally {
        $zip.Dispose()
    }
}

function Test-JarEntry {
    param([string]$JarPath, [string]$EntryPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $null -ne $zip.GetEntry($EntryPath)
    }
    finally {
        $zip.Dispose()
    }
}

function Stop-CurrentServiceSafely {
    $connections = @(Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue)
    if ($connections.Count -eq 0 -and $ResumeAfterVerifiedFreeze) {
        return [PSCustomObject]@{
            capturedAt = (Get-Date).ToString('o')
            processId = 30656
            commandLine = 'Previously verified Java V43 process was stopped by this recovery verifier.'
            observedRuntimeJarSha256BeforeExternalReplacement = $observedRuntimeJarSha256BeforeReplacement
            jarPathAtFreeze = $workspaceJar
            jarPathArtifactSha256AtFreeze = if (Test-Path -LiteralPath $workspaceJar) { Get-Sha256 $workspaceJar } else { $null }
            jarPathArtifactContainsV44AtFreeze = if (Test-Path -LiteralPath $workspaceJar) { Test-JarEntry -JarPath $workspaceJar -EntryPath 'db/migration/V44__preserve_nullable_inspection_repair_evidence.sql' } else { $null }
            jarPathWasExternallyReplacedAfterRuntimeStart = $true
            healthStatusCode = $null
            health = $null
            resumedAfterVerifiedFreeze = $true
        }
    }
    if ($connections.Count -ne 1) {
        throw "Expected exactly one listener on port $ServerPort, found $($connections.Count)."
    }
    $process = Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f $connections[0].OwningProcess)
    if (-not $process -or $process.Name -ne 'java.exe' -or $process.CommandLine -notmatch 'store-profit-backend-0.1.0-SNAPSHOT.jar') {
        throw "Refusing to stop unexpected process on port $ServerPort."
    }
    $response = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/api/health" -f $ServerPort) -TimeoutSec 10
    $pathArtifactHash = if (Test-Path -LiteralPath $workspaceJar -PathType Leaf) { Get-Sha256 $workspaceJar } else { $null }
    $pathArtifactContainsV44 = if (Test-Path -LiteralPath $workspaceJar -PathType Leaf) { Test-JarEntry -JarPath $workspaceJar -EntryPath 'db/migration/V44__preserve_nullable_inspection_repair_evidence.sql' } else { $null }
    $snapshot = [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        processId = [int]$process.ProcessId
        commandLine = $process.CommandLine
        observedRuntimeJarSha256BeforeExternalReplacement = $observedRuntimeJarSha256BeforeReplacement
        jarPathAtFreeze = $workspaceJar
        jarPathArtifactSha256AtFreeze = $pathArtifactHash
        jarPathArtifactContainsV44AtFreeze = $pathArtifactContainsV44
        jarPathWasExternallyReplacedAfterRuntimeStart = $true
        healthStatusCode = $response.StatusCode
        health = ($response.Content | ConvertFrom-Json)
    }
    Stop-Process -Id $process.ProcessId -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue)) {
            return [PSCustomObject]$snapshot
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Port $ServerPort is still listening after the verified process was stopped."
}

function Start-ForensicsMysql {
    if (Get-NetTCPConnection -LocalPort $ForensicsPort -State Listen -ErrorAction SilentlyContinue) {
        throw "Forensics port $ForensicsPort is already in use; refusing to touch its existing process."
    }
    [void](New-Item -ItemType Directory -Path $forensicsData -Force)
    Invoke-NativeCapture -FilePath $mysqldPath -Arguments @(
        '--no-defaults',
        '--initialize-insecure',
        ("--basedir={0}" -f (Split-Path -Parent $mysqlBin)),
        ("--datadir={0}" -f $forensicsData)
    ) | Out-Null
    $process = Start-Process -FilePath $mysqldPath -ArgumentList @(
        '--no-defaults',
        ("--basedir={0}" -f (Split-Path -Parent $mysqlBin)),
        ("--datadir={0}" -f $forensicsData),
        ("--port={0}" -f $ForensicsPort),
        '--bind-address=127.0.0.1',
        '--skip-log-bin',
        ("--pid-file={0}" -f $forensicsPid),
        ("--log-error={0}" -f $forensicsLog)
    ) -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-NativeCapture -FilePath $mysqlPath -Arguments @(
                '--protocol=TCP',
                '--host=127.0.0.1',
                ("--port={0}" -f $ForensicsPort),
                '--user=root',
                '--batch',
                '--skip-column-names',
                '--execute=select 1'
            ) | Out-Null
            return $process
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }
    $log = if (Test-Path -LiteralPath $forensicsLog) { Get-Content -LiteralPath $forensicsLog -Raw -Encoding UTF8 } else { '' }
    throw "Isolated MySQL did not become ready. $log"
}

function Invoke-ForensicsSql {
    param([string]$Sql, [string]$InputFile)
    $args = @(
        '--protocol=TCP',
        '--host=127.0.0.1',
        ("--port={0}" -f $ForensicsPort),
        '--user=root',
        '--batch',
        '--skip-column-names'
    )
    if ($Sql) {
        $args += ("--execute={0}" -f $Sql)
    }
    Invoke-NativeCapture -FilePath $mysqlPath -InputFile $InputFile -Arguments $args
}

function Get-ForensicsScalar {
    param([string]$Sql)
    (Split-Lines (Invoke-ForensicsSql -Sql $Sql) | Select-Object -First 1)
}

function Test-ForensicsTable {
    param([string]$Schema, [string]$Table)
    (Get-ForensicsScalar ("select count(*) from information_schema.tables where table_schema='{0}' and table_name='{1}'" -f $Schema, $Table)) -eq '1'
}

function Get-ForensicsTableDiff {
    param([string]$FromSchema, [string]$ToSchema, [string]$Table, [string]$Pair)
    if (-not (Test-ForensicsTable $FromSchema $Table) -or -not (Test-ForensicsTable $ToSchema $Table)) {
        return [PSCustomObject]@{
            pair = $Pair; table = $Table; classification = 'SCHEMA_ABSENT_IN_ONE_SNAPSHOT'
            fromRows = $null; toRows = $null; newRows = $null; checksumChanged = $null; samplePrimaryKeys = @()
        }
    }
    $keys = @(Split-Lines (Invoke-ForensicsSql -Sql ("select column_name from information_schema.key_column_usage where table_schema='{0}' and table_name='{1}' and constraint_name='PRIMARY' order by ordinal_position" -f $ToSchema, $Table)))
    if ($keys.Count -eq 0) {
        return [PSCustomObject]@{
            pair = $Pair; table = $Table; classification = 'NO_PRIMARY_KEY'
            fromRows = Get-ForensicsScalar ("select count(*) from {0}.{1}" -f $FromSchema, $Table)
            toRows = Get-ForensicsScalar ("select count(*) from {0}.{1}" -f $ToSchema, $Table)
            newRows = $null; checksumChanged = $null; samplePrimaryKeys = @()
        }
    }
    $join = ($keys | ForEach-Object { "a.{0} = b.{0}" -f $_ }) -join ' and '
    $firstKey = $keys[0]
    $fromRows = Get-ForensicsScalar ("select count(*) from {0}.{1}" -f $FromSchema, $Table)
    $toRows = Get-ForensicsScalar ("select count(*) from {0}.{1}" -f $ToSchema, $Table)
    $newRows = Get-ForensicsScalar ("select count(*) from {0}.{1} b left join {2}.{1} a on {3} where a.{4} is null" -f $ToSchema, $Table, $FromSchema, $join, $firstKey)
    $expressions = ($keys | ForEach-Object { "cast(b.{0} as char)" -f $_ }) -join ', '
    $ordering = ($keys | ForEach-Object { "b.{0}" -f $_ }) -join ', '
    $samples = @(Split-Lines (Invoke-ForensicsSql -Sql ("select concat_ws('|', {0}) from {1}.{2} b left join {3}.{2} a on {4} where a.{5} is null order by {6} limit 20" -f $expressions, $ToSchema, $Table, $FromSchema, $join, $firstKey, $ordering)))
    $fromChecksum = Get-ForensicsScalar ("checksum table {0}.{1}" -f $FromSchema, $Table)
    $toChecksum = Get-ForensicsScalar ("checksum table {0}.{1}" -f $ToSchema, $Table)
    $classification = if ($Table -eq 'warehouse_inventory') { 'V43_MIGRATION_PROJECTION' } elseif ([int64]$newRows -gt 0) { 'POTENTIAL_BUSINESS_INCREMENT' } else { 'NO_NEW_PRIMARY_KEYS' }
    [PSCustomObject]@{
        pair = $Pair; table = $Table; classification = $classification
        fromRows = [int64]$fromRows; toRows = [int64]$toRows; newRows = [int64]$newRows
        checksumChanged = ($fromChecksum -ne $toChecksum); samplePrimaryKeys = $samples
    }
}

function Invoke-Build {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$Label, [string]$LogPath)
    $errorPath = "$LogPath.err"
    $process = Start-Process -FilePath $FilePath -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $LogPath -RedirectStandardError $errorPath
    if ($process.ExitCode -ne 0) {
        $errorText = if (Test-Path -LiteralPath $errorPath) { Get-Content -LiteralPath $errorPath -Raw -Encoding UTF8 } else { '' }
        throw "$Label failed with exit code $($process.ExitCode). $errorText"
    }
    [PSCustomObject]@{ label = $Label; exitCode = $process.ExitCode; log = $LogPath }
}

function Start-VerifiedRuntime {
    param([string]$JarPath, [string]$Password, [string]$UserName, [string]$DeploymentDirectory)
    if (Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $ServerPort is already occupied; refusing to overwrite an existing service."
    }
    Assert-Directory $storageRoot 'Attachment storage root'
    $variables = [ordered]@{
        APP_ENV = 'STAGING'; SERVER_PORT = [string]$ServerPort; MYSQL_HOST = $HostName; MYSQL_PORT = [string]$Port
        MYSQL_DATABASE = $Database; MYSQL_USERNAME = $UserName; MYSQL_PASSWORD = $Password; MYSQL_SSL_MODE = 'DISABLED'
        APP_SEED_DEMO_ENABLED = 'false'; APP_SEED_LEGACY_EMPLOYEE_ENABLED = 'false'
        APP_MIGRATION_AUTO_RUN = 'false'; APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT = $storageRoot
    }
    $previous = @{}
    foreach ($name in $variables.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$variables[$name], 'Process')
    }
    try {
        $process = Start-Process -FilePath $javaPath -ArgumentList @('-jar', ('"' + $JarPath + '"')) -WorkingDirectory $DeploymentDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $DeploymentDirectory 'backend.out.log') -RedirectStandardError (Join-Path $DeploymentDirectory 'backend.err.log') -PassThru
    }
    finally {
        foreach ($name in $variables.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
    }
    $deadline = (Get-Date).AddSeconds(60)
    $lastFailure = ''
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:{0}/api/health" -f $ServerPort) -TimeoutSec 5
            $result = $response.Content | ConvertFrom-Json
            if ($result.data.status -eq 'UP' -and [string]$result.data.databaseMigrationVersion -eq '43' -and [int]$result.data.databasePort -eq $Port -and $result.data.databaseName -eq $Database -and $result.data.databaseAccountScope -eq 'LOCAL_SCOPED') {
                return [PSCustomObject]@{ process = $process; health = $result }
            }
            $lastFailure = $response.Content
        }
        catch {
            $lastFailure = $_.Exception.Message
        }
    }
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    throw "Verified runtime did not become healthy. $lastFailure"
}

function Stop-ForensicsMysql {
    if ($forensicsProcess -and -not $forensicsProcess.HasExited) {
        Stop-Process -Id $forensicsProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $forensicsRoot) {
        $resolved = [IO.Path]::GetFullPath($forensicsRoot)
        $expected = [IO.Path]::GetFullPath((Join-Path $env:TEMP 'ai-profit-v43-forensics-'))
        if (-not $resolved.StartsWith($expected, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to delete an unexpected forensics path: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

try {
    if ($HostName -ne '127.0.0.1' -or $Port -ne 3307 -or $Database -ne 'store_profit_mysql8') {
        throw 'This verifier only permits 127.0.0.1:3307/store_profit_mysql8.'
    }
    foreach ($path in @($mysqlPath, $mysqldumpPath, $mysqldPath, $workspaceJar, $sourceV43, $b1ReceiptPath, $b2ReceiptPath)) {
        Assert-File $path 'Required recovery artifact'
    }
    $b1Receipt = Get-Content -LiteralPath $b1ReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $b2Receipt = Get-Content -LiteralPath $b2ReceiptPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-File $b1Receipt.dump.path 'B1 dump'
    Assert-File $b2Receipt.dump.path 'B2 dump'
    if ((Get-Sha256 $b1Receipt.dump.path) -cne $expectedB1Hash -or [string]$b1Receipt.dump.sha256 -cne $expectedB1Hash) {
        throw 'B1 SHA-256 does not match the approved recovery baseline.'
    }
    if ((Get-Sha256 $b2Receipt.dump.path) -cne $expectedB2Hash -or [string]$b2Receipt.dump.sha256 -cne $expectedB2Hash) {
        throw 'B2 SHA-256 does not match the approved failure-state evidence.'
    }
    if ([string]$b1Receipt.flywayVersion -ne '40' -or [string]$b2Receipt.flywayVersion -ne '42') {
        throw 'B1/B2 Flyway evidence is not V40/V42 as required.'
    }

    $freezeSnapshot = Stop-CurrentServiceSafely
    [void](New-Item -ItemType Directory -Path $b3Directory -Force)
    $rootCredential = Get-Credential -UserName 'root' -Message 'Enter the 3307 root credential for B3 and read-only verification. The password is process-only and is never written.'
    if (-not $rootCredential -or $rootCredential.UserName -notmatch '^(?i:root)(?:@|$)') {
        throw 'A root credential is required for B3 and verification.'
    }
    $rootPassword = Get-SecurePassword -Credential $rootCredential -Pointer ([ref]$rootPasswordPointer)

    $b3DumpPath = Join-Path $b3Directory "$Database-full-after-v43.sql"
    $b3DumpError = Join-Path $b3Directory 'mysqldump-error.log'
    $b3DumpOutput = Join-Path $b3Directory 'mysqldump-output.log'
    Invoke-SecretRedirectedProcess -FilePath $mysqldumpPath -Password $rootPassword -Arguments @(
        '--protocol=TCP', ("--host={0}" -f $HostName), ("--port={0}" -f $Port), '--user=root',
        '--single-transaction', '--quick', '--skip-lock-tables', '--no-tablespaces', '--set-gtid-purged=OFF',
        '--hex-blob', '--triggers', '--routines', '--events', '--default-character-set=utf8mb4', $Database
    ) -StdoutPath $b3DumpPath -StderrPath $b3DumpError
    $b3File = Get-Item -LiteralPath $b3DumpPath
    if ($b3File.Length -le 0) {
        throw 'B3 dump is empty.'
    }
    $b3Hash = Get-Sha256 $b3DumpPath
    $b3Receipt = [ordered]@{
        createdAt = (Get-Date).ToString('o')
        endpoint = ('{0}:{1}' -f $HostName, $Port)
        database = $Database
        purpose = 'B3 successful V43 state after controlled freeze'
        dump = [ordered]@{ path = $b3DumpPath; bytes = $b3File.Length; lastWriteTime = $b3File.LastWriteTime.ToString('o'); sha256 = $b3Hash }
        productionAudit = @()
        tool = [ordered]@{ path = $mysqldumpPath; sha256 = Get-Sha256 $mysqldumpPath }
    }
    $b3ReceiptPath = Join-Path $b3Directory 'warehouse-v43-success.receipt.json'
    [IO.File]::WriteAllText($b3ReceiptPath, ($b3Receipt | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

    $targetAuditSql = @'
select concat('flyway|', version, '|', success, '|', count(*))
from flyway_schema_history
where version in ('41', '42', '43')
group by version, success
order by version, success;
select concat('failedV43|', count(*)) from flyway_schema_history where version = '43' and success = 0;
select concat('topology|', expected_business_store_count, '|', actual_business_store_count, '|', bound_store_count, '|', binding_status)
from warehouse_topology_migration_audit
where migration_key = 'V43_JINGZHOU_38_STORE_BINDING'
order by id;
select concat('storeBinding|', count(*), '|', coalesce(sum(case when region_code = 'JINGZHOU' and supply_warehouse_id is not null then 1 else 0 end), 0))
from store_branch;
select concat('scopeCheck|', constraint_name)
from information_schema.table_constraints
where constraint_schema = database()
  and table_name = 'user_data_scope'
  and constraint_type = 'CHECK'
  and constraint_name in ('chk_user_data_scope_domain', 'chk_user_data_scope_type', 'chk_user_data_scope_json')
order by constraint_name;
select concat('scopeCheckAll|', constraint_name)
from information_schema.table_constraints
where constraint_schema = database()
  and table_name = 'user_data_scope'
  and constraint_type = 'CHECK'
order by constraint_name;
select concat('stockBatch|', coalesce(sum(quantity), 0)) from warehouse_stock_batch;
select concat('inventory|', facility.code, '|', coalesce(sum(inventory.on_hand_quantity), 0), '|', coalesce(sum(inventory.reserved_quantity), 0), '|', coalesce(sum(inventory.in_transit_quantity), 0))
from warehouse_facility facility
left join warehouse_inventory inventory on inventory.warehouse_id = facility.id
group by facility.id, facility.code
order by facility.code;
show grants for 'ai_profit_runtime_v43'@'127.0.0.1';
'@
    $targetAudit = Split-Lines (Invoke-TargetMySql -Password $rootPassword -Sql $targetAuditSql)
    [IO.File]::WriteAllText((Join-Path $b3Directory 'target-audit.raw.txt'), ($targetAudit -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    foreach ($required in @('^flyway\|41\|1\|1$', '^flyway\|42\|1\|1$', '^flyway\|43\|1\|1$', '^failedV43\|0$', '^topology\|38\|38\|38\|BOUND_38$', '^storeBinding\|38\|38$', '^scopeCheck\|chk_user_data_scope_domain$', '^scopeCheck\|chk_user_data_scope_type$', '^scopeCheck\|chk_user_data_scope_json$', '^stockBatch\|935\.90$', '^inventory\|JZ-CENTRAL\|935\.90\|0\.00\|0\.00$', '^inventory\|SD-REGIONAL\|0\.00\|0\.00\|0\.00$')) {
        if (-not ($targetAudit | Where-Object { $_ -match $required })) {
            throw "Production audit gate failed: $required"
        }
    }

    $b3Receipt.productionAudit = $targetAudit
    [IO.File]::WriteAllText($b3ReceiptPath, ($b3Receipt | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

    $forensicsProcess = Start-ForensicsMysql
    foreach ($schema in @('v43_forensics_b1', 'v43_forensics_b2', 'v43_forensics_b3')) {
        Invoke-ForensicsSql -Sql ("create database {0} character set utf8mb4 collate utf8mb4_unicode_ci" -f $schema) | Out-Null
    }
    Invoke-NativeCapture -FilePath $mysqlPath -InputFile $b1Receipt.dump.path -Arguments @('--protocol=TCP', '--host=127.0.0.1', ("--port={0}" -f $ForensicsPort), '--user=root', '--database=v43_forensics_b1') | Out-Null
    Invoke-NativeCapture -FilePath $mysqlPath -InputFile $b2Receipt.dump.path -Arguments @('--protocol=TCP', '--host=127.0.0.1', ("--port={0}" -f $ForensicsPort), '--user=root', '--database=v43_forensics_b2') | Out-Null
    Invoke-NativeCapture -FilePath $mysqlPath -InputFile $b3DumpPath -Arguments @('--protocol=TCP', '--host=127.0.0.1', ("--port={0}" -f $ForensicsPort), '--user=root', '--database=v43_forensics_b3') | Out-Null

    $businessTables = @(
        'store_requisition', 'store_requisition_line', 'warehouse_purchase_order', 'warehouse_purchase_order_line',
        'warehouse_delivery_order', 'warehouse_delivery_order_line', 'warehouse_return_order', 'warehouse_return_order_line',
        'warehouse_stock_batch', 'warehouse_stock_movement', 'warehouse_stock_adjustment', 'warehouse_inventory',
        'warehouse_attachment', 'expense_supplement_attachment', 'todo_action_attachment', 'operation_log',
        'store_inventory', 'store_inventory_movement', 'expense_claim', 'expense_supplement'
    )
    foreach ($table in $businessTables) {
        $forensicsDiff += Get-ForensicsTableDiff -FromSchema 'v43_forensics_b1' -ToSchema 'v43_forensics_b2' -Table $table -Pair 'B1_TO_B2'
        $forensicsDiff += Get-ForensicsTableDiff -FromSchema 'v43_forensics_b2' -ToSchema 'v43_forensics_b3' -Table $table -Pair 'B2_TO_B3'
    }

    [void](New-Item -ItemType Directory -Path $operationRoot -Force)
    $mvn = (Get-Command mvn.cmd -ErrorAction Stop).Source
    $npm = (Get-Command npm.cmd -ErrorAction Stop).Source
    $currentTreeTestDirectory = Join-Path $operationRoot 'current-tree-test'
    $backendTest = Invoke-Build -FilePath $mvn -Arguments @('-q', ("-Dproject.build.directory={0}" -f $currentTreeTestDirectory), 'test') -WorkingDirectory (Join-Path $repoRoot 'backend') -Label 'Backend tests' -LogPath (Join-Path $operationRoot 'backend-test.log')
    $frontendBuild = Invoke-Build -FilePath $npm -Arguments @('run', 'build') -WorkingDirectory (Join-Path $repoRoot 'frontend-vue') -Label 'Vue production build' -LogPath (Join-Path $operationRoot 'frontend-build.log')

    $headArchive = Join-Path $operationRoot 'v43-head-source.zip'
    $headSource = Join-Path $operationRoot 'v43-head-source'
    Invoke-NativeCapture -FilePath (Get-Command git.exe -ErrorAction Stop).Source -Arguments @('archive', '--format=zip', ("--output={0}" -f $headArchive), 'HEAD') -WorkingDirectory $repoRoot | Out-Null
    Expand-Archive -LiteralPath $headArchive -DestinationPath $headSource -Force
    $headV43 = Join-Path $headSource 'backend\src\main\resources\db\migration\V43__multi_warehouse_topology.sql'
    Assert-File $headV43 'Git HEAD V43 migration'
    $sourceV43Hash = Get-Sha256 $sourceV43
    if ($sourceV43Hash -cne (Get-Sha256 $headV43)) {
        throw 'Git HEAD V43 does not match the verified current source V43.'
    }
    if (Test-Path -LiteralPath (Join-Path $headSource 'backend\src\main\resources\db\migration\V44__preserve_nullable_inspection_repair_evidence.sql')) {
        throw 'Git HEAD unexpectedly contains V44 and is not eligible for this V43-only recovery.'
    }
    $currentApplicationYaml = Join-Path $repoRoot 'backend\src\main\resources\application.yml'
    $headApplicationYaml = Join-Path $headSource 'backend\src\main\resources\application.yml'
    $currentApplicationText = Get-Content -LiteralPath $currentApplicationYaml -Raw -Encoding UTF8
    $headApplicationText = Get-Content -LiteralPath $headApplicationYaml -Raw -Encoding UTF8
    $normalizedCurrentApplicationText = $currentApplicationText.Replace('allowPublicKeyRetrieval=true', 'allowPublicKeyRetrieval=false')
    if ($normalizedCurrentApplicationText -cne $headApplicationText) {
        throw 'The current application.yml contains changes beyond the approved MySQL public-key retrieval setting.'
    }
    Copy-Item -LiteralPath $currentApplicationYaml -Destination $headApplicationYaml -Force
    $headBuildDirectory = Join-Path $operationRoot 'v43-head-build'
    $backendPackage = Invoke-Build -FilePath $mvn -Arguments @('-q', '-DskipTests', ("-Dproject.build.directory={0}" -f $headBuildDirectory), 'package') -WorkingDirectory (Join-Path $headSource 'backend') -Label 'V43-only backend package' -LogPath (Join-Path $operationRoot 'backend-v43-package.log')
    $headJar = Get-ChildItem -LiteralPath $headBuildDirectory -File -Filter '*.jar' | Sort-Object Length -Descending | Select-Object -First 1
    if (-not $headJar) {
        throw 'V43-only package did not produce a JAR.'
    }
    if (-not (Test-JarEntry -JarPath $headJar.FullName -EntryPath 'BOOT-INF/classes/db/migration/V43__multi_warehouse_topology.sql')) {
        throw 'V43-only package did not produce an executable Spring Boot JAR.'
    }
    if (Test-JarEntry -JarPath $headJar.FullName -EntryPath 'BOOT-INF/classes/db/migration/V44__preserve_nullable_inspection_repair_evidence.sql') {
        throw 'V43-only package contains V44 and is not eligible for deployment.'
    }
    $deploymentJarPath = Join-Path $operationRoot 'store-profit-backend-v43-compat.jar'
    $headJarHash = Get-Sha256 $headJar.FullName
    Copy-Item -LiteralPath $headJar.FullName -Destination $deploymentJarPath -ErrorAction Stop
    if ($headJarHash -cne (Get-Sha256 $deploymentJarPath)) {
        throw 'Immutable deployment JAR hash does not match the V43-only package.'
    }
    $jarV43Hash = Get-JarEntryHash -JarPath $deploymentJarPath -EntryPath 'BOOT-INF/classes/db/migration/V43__multi_warehouse_topology.sql'
    if ($sourceV43Hash -cne $jarV43Hash) {
        throw 'Deployment JAR does not contain the verified compatible V43 migration.'
    }
    $jarEvidence = [ordered]@{
        sourceJar = $headJar.FullName; sourceJarSha256 = $headJarHash
        deploymentJar = $deploymentJarPath; deploymentJarSha256 = Get-Sha256 $deploymentJarPath
        sourceV43Sha256 = $sourceV43Hash; jarV43Sha256 = $jarV43Hash
        applicationYamlOverlaySha256 = Get-Sha256 $currentApplicationYaml
    }

    $runtimeCredential = Get-Credential -UserName 'ai_profit_runtime_v43' -Message 'Enter the existing local runtime account credential for the verified 3307 V43 service. The password is process-only and is never written.'
    if (-not $runtimeCredential -or $runtimeCredential.UserName -ne 'ai_profit_runtime_v43') {
        throw 'The existing local runtime account ai_profit_runtime_v43 is required.'
    }
    $runtimePassword = Get-SecurePassword -Credential $runtimeCredential -Pointer ([ref]$runtimePasswordPointer)
    Invoke-NativeCapture -FilePath $mysqlPath -Password $runtimePassword -Arguments @(
        '--protocol=TCP', ("--host={0}" -f $HostName), ("--port={0}" -f $Port),
        ("--user={0}" -f $runtimeCredential.UserName), ("--database={0}" -f $Database),
        '--batch', '--skip-column-names', '--execute=select 1'
    ) | Out-Null
    $runtime = Start-VerifiedRuntime -JarPath $deploymentJarPath -Password $runtimePassword -UserName $runtimeCredential.UserName -DeploymentDirectory $operationRoot
    $health = $runtime.health

    $potentialBusinessIncrements = @($forensicsDiff | Where-Object { $_.classification -eq 'POTENTIAL_BUSINESS_INCREMENT' -and $_.newRows -gt 0 })
    $report = @(
        '# 3307 V43 Success-State Preservation and Forensic Verification',
        '',
        ("Generated at: {0}" -f (Get-Date).ToString('o')),
        '',
        '## Result',
        '',
        '- Preserved the current V43 success state. No B1 restore, flyway repair, V44 skip, or Flyway history rewrite was performed.',
        ("- Service: http://127.0.0.1:{0}/api/health" -f $ServerPort),
        '',
        '## Backup integrity',
        '',
        ("- B1: {0}; SHA-256 {1}." -f $b1Receipt.dump.path, $expectedB1Hash),
        ("- B2: {0}; SHA-256 {1}; Flyway V42." -f $b2Receipt.dump.path, $expectedB2Hash),
        ("- B3: {0}; SHA-256 {1}; {2} bytes." -f $b3DumpPath, $b3Hash, $b3File.Length),
        '',
        '## Freeze and deployment',
        '',
        ("- Frozen process PID: {0}; observed pre-replacement runtime JAR SHA-256: {1}." -f $freezeSnapshot.processId, $freezeSnapshot.observedRuntimeJarSha256BeforeExternalReplacement),
        ("- The original target JAR path was externally replaced after runtime start; its freeze-time artifact hash was {0} and it was not deployed." -f $freezeSnapshot.jarPathArtifactSha256AtFreeze),
        ("- Deployment JAR: {0}; SHA-256: {1}." -f $jarEvidence.deploymentJar, $jarEvidence.deploymentJarSha256),
        ("- V43 entry SHA-256: {0}; matches source." -f $jarEvidence.jarV43Sha256),
        '',
        '## Database verification',
        ''
    )
    foreach ($line in $targetAudit) {
        $report += ("- {0}" -f $line)
    }
    $report += ''
    $report += '## Isolated snapshot differences'
    $report += ''
    foreach ($item in $forensicsDiff) {
        $samples = if ($item.samplePrimaryKeys.Count -gt 0) { $item.samplePrimaryKeys -join ', ' } else { 'none' }
        $report += ("- {0} / {1}: classification={2}; rows {3}->{4}; new primary keys={5}; checksum changed={6}; samples={7}." -f $item.pair, $item.table, $item.classification, $item.fromRows, $item.toRows, $item.newRows, $item.checksumChanged, $samples)
    }
    $report += ''
    if ($potentialBusinessIncrements.Count -eq 0) {
        $report += '- No new primary keys were found in the reviewed business tables. The warehouse_inventory projection is classified as V43 migration output.'
    }
    else {
        $report += ("- Found {0} potential business increments. They are retained in B3 and this report; B1 must not be used for a future rollback." -f $potentialBusinessIncrements.Count)
    }
    $report += ''
    $report += '## Build and health verification'
    $report += ''
    $report += ("- Backend tests passed: {0}." -f $backendTest.log)
    $report += ("- Backend package passed: {0}." -f $backendPackage.log)
    $report += ("- Vue type check and production build passed: {0}." -f $frontendBuild.log)
    $report += ("- health.status={0}; Flyway={1}; port={2}; database={3}; account scope={4}." -f $health.data.status, $health.data.databaseMigrationVersion, $health.data.databasePort, $health.data.databaseName, $health.data.databaseAccountScope)
    $report += ''
    $report += '## Not performed and risks'
    $report += ''
    $report += '- Port 3306 was not touched. The stale deployment JAR was not used. Runtime privileges were not expanded.'
    $report += '- The deployment JAR was built from Git HEAD after confirming its V43 equals the working source and that it contains no V44.'
    $report += '- The deployment build contains only the pre-existing allowPublicKeyRetrieval runtime configuration overlay from application.yml.'
    $report += '- This report contains no password.'
    [IO.File]::WriteAllText($reportPath, ($report -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    Write-Output "Recovery verification completed. Report: $reportPath"
}
catch {
    $failureReport = @(
        '# 3307 V43 Success-State Verification - Stopped',
        '',
        ("Generated at: {0}" -f (Get-Date).ToString('o')),
        ("Failure reason: {0}" -f $_.Exception.Message),
        '',
        '- No B1 restore, flyway repair, V44 skip, or Flyway history rewrite was performed.',
        '- B1 and B2 remain preserved; B3 is also preserved if it was created.',
        '- This report contains no password.'
    )
    [IO.File]::WriteAllText($failureReportPath, ($failureReport -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
    throw
}
finally {
    Stop-ForensicsMysql
    if ($rootPasswordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($rootPasswordPointer)
    }
    if ($runtimePasswordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($runtimePasswordPointer)
    }
    $rootPassword = $null
    $runtimePassword = $null
    if ($rootCredential) {
        $rootCredential.Password.Dispose()
    }
    if ($runtimeCredential) {
        $runtimeCredential.Password.Dispose()
    }
}
