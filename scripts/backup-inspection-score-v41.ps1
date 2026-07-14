[CmdletBinding()]
param(
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3307,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$Database,
    [string]$UserName,
    [pscredential]$Credential,
    [string]$OutputDirectory,
    [switch]$AllowIsolatedTestPort
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$mysqlPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$mysqldumpPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe'
$expectedDumpSha256 = 'ADCAFCB9D489115AEB32419FB3E3F428F2D4DACE3A625DBC2714388B93C1DB5A'
$backupTables = @(
    'inspection_record',
    'inspection_record_standard_snapshot',
    'inspection_standard_version',
    'inspection_standard_item',
    'inspection_result_repair_audit',
    'flyway_schema_history'
)

function Assert-File([string]$Path, [string]$Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label not found: $Path"
    }
}

function Invoke-SecretProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Password,
        [string]$RedirectStandardOutput,
        [string]$RedirectStandardError
    )

    $temporaryOutput = $null
    if (-not $RedirectStandardOutput) {
        $temporaryOutput = Join-Path $env:TEMP ("inspection-v41-process-$([guid]::NewGuid().ToString('N')).out")
        $RedirectStandardOutput = $temporaryOutput
    }
    if (-not $RedirectStandardError) {
        $RedirectStandardError = Join-Path $env:TEMP ("inspection-v41-process-$([guid]::NewGuid().ToString('N')).err")
    }
    $quotedArguments = $Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            if ($_ -match '"') { throw 'Native process argument contains an unsupported double quote.' }
            '"' + $_ + '"'
        }
        else { $_ }
    }
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $Password, 'Process')
        $process = Start-Process -FilePath $FilePath `
            -ArgumentList ($quotedArguments -join ' ') `
            -Wait -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $RedirectStandardOutput `
            -RedirectStandardError $RedirectStandardError
    }
    finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
    $stdout = if ($temporaryOutput) { Get-Content -LiteralPath $temporaryOutput -Raw -Encoding UTF8 } else { '' }
    $stderr = if (Test-Path -LiteralPath $RedirectStandardError) {
        Get-Content -LiteralPath $RedirectStandardError -Raw -Encoding UTF8
    }
    else { '' }
    if ($temporaryOutput) { Remove-Item -LiteralPath $temporaryOutput -Force -ErrorAction SilentlyContinue }
    if ($process.ExitCode -ne 0) {
        throw "$(Split-Path -Leaf $FilePath) failed with exit code $($process.ExitCode). $stderr"
    }
    return $stdout
}

if ($HostName -ne '127.0.0.1') {
    throw 'V41 inspection backup only permits host 127.0.0.1.'
}
if ($Port -ne 3307) {
    if (-not $AllowIsolatedTestPort -or $Database -notmatch '(?i:test|qa)') {
        throw 'Production backup must use TCP port 3307. Non-3307 is allowed only for an explicitly isolated TEST/QA database.'
    }
}

Assert-File $mysqlPath 'mysql.exe'
Assert-File $mysqldumpPath 'mysqldump.exe'
$actualDumpSha256 = (Get-FileHash -LiteralPath $mysqldumpPath -Algorithm SHA256).Hash
if ($actualDumpSha256 -cne $expectedDumpSha256) {
    throw "mysqldump.exe hash mismatch. Expected $expectedDumpSha256, got $actualDumpSha256."
}

if (-not $Credential) {
    $credentialArgs = @{
        Message = ('Enter migration credentials for {0}:{1}/{2}. The password is process-only and is never logged.' -f $HostName, $Port, $Database)
    }
    if ($UserName) { $credentialArgs.UserName = $UserName }
    $Credential = Get-Credential @credentialArgs
}
if (-not $Credential) { throw 'Database credentials are required.' }
if ($UserName -and $Credential.UserName -ne $UserName) {
    throw 'Credential user does not match -UserName.'
}
$UserName = $Credential.UserName

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $env:LOCALAPPDATA ('AI-Profit-OS\backups\inspection-score-v41\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
if ($resolvedOutput.StartsWith($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Database backups must be written outside the Git repository.'
}
[void](New-Item -ItemType Directory -Path $resolvedOutput -Force)

$networkArguments = @(
    '--protocol=TCP',
    "--host=$HostName",
    "--port=$Port",
    "--user=$UserName"
)
$preflightSql = @'
select
  (select version from flyway_schema_history where success = 1 order by installed_rank desc limit 1) as flyway_version,
  (select count(*) from flyway_schema_history where version = '41' and success = 1) as v41_applied,
  (select count(*) from inspection_record where full_score = 100.00 or full_score is null) as legacy_candidates,
  (select count(*) from inspection_record where full_score = 200.00) as current_records,
  (select count(*) from inspection_record where coalesce(full_score, 100.00) not in (100.00, 200.00)) as unsupported_records;
'@

$password = $null
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Credential.Password)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $preflightOutput = Invoke-SecretProcess -FilePath $mysqlPath -Password $password -Arguments ($networkArguments + @(
        "--database=$Database", '--batch', '--skip-column-names', "--execute=$preflightSql"
    )) -RedirectStandardError (Join-Path $resolvedOutput 'mysql-preflight-error.log')
    $preflight = @($preflightOutput.Trim() -split "`t")
    if ($preflight.Count -ne 5) { throw 'Unexpected V41 preflight response.' }
    if ($preflight[0] -ne '40') { throw "Expected Flyway V40 before backup, found V$($preflight[0])." }
    if ([int]$preflight[1] -ne 0) { throw 'Flyway V41 is already applied; this script only creates the mandatory pre-V41 backup.' }
    if ([int]$preflight[4] -ne 0) { throw "Found $($preflight[4]) records with an unsupported score scale. Resolve them before V41." }

    $dumpPath = Join-Path $resolvedOutput ("$Database-inspection-before-v41.sql")
    $dumpErrorPath = Join-Path $resolvedOutput 'mysqldump-error.log'
    [void](Invoke-SecretProcess -FilePath $mysqldumpPath -Password $password -Arguments ($networkArguments + @(
        '--single-transaction', '--quick', '--skip-lock-tables', '--no-tablespaces',
        '--set-gtid-purged=OFF', '--hex-blob', '--default-character-set=utf8mb4',
        $Database
    ) + $backupTables) -RedirectStandardOutput $dumpPath -RedirectStandardError $dumpErrorPath)

    $dumpFile = Get-Item -LiteralPath $dumpPath
    if ($dumpFile.Length -le 0) { throw 'The V41 backup file is empty.' }
    $dumpHash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash
    $receipt = [ordered]@{
        createdAt = (Get-Date).ToString('o')
        endpoint = ('{0}:{1}' -f $HostName, $Port)
        database = $Database
        user = $UserName
        flywayVersion = $preflight[0]
        legacyCandidateCount = [int]$preflight[2]
        currentRecordCount = [int]$preflight[3]
        tables = $backupTables
        dump = [ordered]@{
            path = $dumpFile.FullName
            bytes = $dumpFile.Length
            sha256 = $dumpHash
        }
        tool = [ordered]@{
            path = $mysqldumpPath
            sha256 = $actualDumpSha256
        }
    }
    $receiptPath = Join-Path $resolvedOutput 'inspection-before-v41.receipt.json'
    [System.IO.File]::WriteAllText(
        $receiptPath,
        ($receipt | ConvertTo-Json -Depth 6),
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Output "V41 inspection backup completed: $dumpPath"
    Write-Output "SHA-256: $dumpHash"
    Write-Output "Receipt: $receiptPath"
}
finally {
    if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ($password) { $password = $null }
}
