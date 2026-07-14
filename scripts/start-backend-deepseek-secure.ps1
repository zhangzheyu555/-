[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.@-]+$')]
    [string]$MySqlUsername,

    [ValidateSet('store_profit_mysql8', 'store_profit_mysql8_final')]
    [string]$MySqlDatabase = 'store_profit_mysql8',

    [ValidateSet('LOCAL', 'TEST', 'STAGING')]
    [string]$AppEnvironment = 'STAGING',

    [ValidateRange(1, 65535)]
    [int]$ServerPort = 18081,

    [ValidatePattern('^https://[^\s]+$')]
    [string]$DeepSeekBaseUrl = 'https://api.deepseek.com',

    [ValidatePattern('^[A-Za-z0-9_.:-]+$')]
    [string]$DeepSeekModel = 'deepseek-v4-flash',

    [ValidateRange(5, 300)]
    [int]$DeepSeekTimeoutSeconds = 45,

    [string]$JarPath = (Join-Path $PSScriptRoot '..\backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedJar = [IO.Path]::GetFullPath($JarPath)
if (-not (Test-Path -LiteralPath $resolvedJar -PathType Leaf)) {
    throw 'Backend JAR not found. Build the backend first.'
}
if ($MySqlUsername -match '^(?i:root)(?:@|$)') {
    throw 'Use the dedicated MySQL 3307 application account. Root is forbidden.'
}

$java = Get-Command java.exe -ErrorAction Stop
$mysqlPassword = Read-Host 'Enter the MySQL 3307 application password (hidden)' -AsSecureString
$deepSeekKey = Read-Host 'Enter the DeepSeek API Key for this process only (hidden and not saved)' -AsSecureString
$mysqlPointer = [IntPtr]::Zero
$deepSeekPointer = [IntPtr]::Zero
$mysqlPlain = $null
$deepSeekPlain = $null
$process = $null

try {
    $mysqlPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($mysqlPassword)
    $deepSeekPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($deepSeekKey)
    $mysqlPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($mysqlPointer)
    $deepSeekPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($deepSeekPointer)

    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $java.Source
    $startInfo.Arguments = "-jar `"$resolvedJar`""
    $startInfo.WorkingDirectory = Split-Path -Parent $resolvedJar
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $false
    $startInfo.Environment['APP_ENV'] = $AppEnvironment
    $startInfo.Environment['SERVER_PORT'] = [string]$ServerPort
    $startInfo.Environment['MYSQL_HOST'] = '127.0.0.1'
    $startInfo.Environment['MYSQL_PORT'] = '3307'
    $startInfo.Environment['MYSQL_DATABASE'] = $MySqlDatabase
    $startInfo.Environment['MYSQL_USERNAME'] = $MySqlUsername
    $startInfo.Environment['MYSQL_PASSWORD'] = $mysqlPlain
    $startInfo.Environment['DEEPSEEK_API_KEY'] = $deepSeekPlain
    $startInfo.Environment['DEEPSEEK_BASE_URL'] = $DeepSeekBaseUrl.TrimEnd('/')
    $startInfo.Environment['DEEPSEEK_MODEL'] = $DeepSeekModel
    $startInfo.Environment['DEEPSEEK_TIMEOUT_SECONDS'] = [string]$DeepSeekTimeoutSeconds
    $startInfo.Environment['APP_SEED_DEMO_ENABLED'] = 'false'
    $startInfo.Environment['APP_BOOTSTRAP_DEFAULT_USERS_ENABLED'] = 'false'
    $startInfo.Environment['APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED'] = 'false'

    Write-Host "Starting the backend securely at http://127.0.0.1:$ServerPort" -ForegroundColor Cyan
    Write-Host "Database: 127.0.0.1:3307/$MySqlDatabase. DeepSeek key exists only in the child process." -ForegroundColor Cyan
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()

    # ProcessStartInfo no longer needs to retain the copied secret values after process creation.
    [void]$startInfo.Environment.Remove('MYSQL_PASSWORD')
    [void]$startInfo.Environment.Remove('DEEPSEEK_API_KEY')
    $mysqlPlain = $null
    $deepSeekPlain = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($mysqlPointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($deepSeekPointer)
    $mysqlPointer = [IntPtr]::Zero
    $deepSeekPointer = [IntPtr]::Zero
    $mysqlPassword.Dispose()
    $deepSeekKey.Dispose()
    $mysqlPassword = $null
    $deepSeekKey = $null

    $process.WaitForExit()
    exit $process.ExitCode
}
finally {
    if ($mysqlPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($mysqlPointer)
    }
    if ($deepSeekPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($deepSeekPointer)
    }
    $mysqlPlain = $null
    $deepSeekPlain = $null
    if ($mysqlPassword) { $mysqlPassword.Dispose() }
    if ($deepSeekKey) { $deepSeekKey.Dispose() }
    if ($process) { $process.Dispose() }
}
