[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9_.@-]+$')]
    [string]$MySqlUsername,
    [Parameter(Mandatory)]
    [string]$ExpenseStorageRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$approvedMySqlHost = '127.0.0.1'
$approvedMySqlPort = 3307
$approvedMySqlDatabase = 'store_profit_mysql8_final'
$approvedMySqlVersion = '8.0.46'
$approvedAppEnvironment = 'STAGING'
$approvedBackendPort = 18082
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$backendDirectory = Join-Path $projectRoot 'backend'
$jarPath = Join-Path $backendDirectory 'target\store-profit-backend-0.1.0-SNAPSHOT.jar'
$jdbcUrl = 'jdbc:mysql://127.0.0.1:3307/store_profit_mysql8_final?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=false&connectTimeout=3000&socketTimeout=3000'

function Assert-RestrictedExternalStorageRoot {
    param([Parameter(Mandatory)][string]$Path)
    if (-not [IO.Path]::IsPathRooted($Path)) {
        throw '附件目录必须使用仓库外绝对路径。'
    }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (($full + '\').StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw '附件目录不得位于Git工作区内。'
    }
    if (-not (Test-Path -LiteralPath $full -PathType Container)) {
        throw '附件目录不存在。'
    }
    $item = Get-Item -LiteralPath $full -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw '附件目录不得是重解析点。'
    }
    $broadSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
    $writeRights = [Security.AccessControl.FileSystemRights]'Write, Modify, FullControl, CreateFiles, CreateDirectories, Delete, DeleteSubdirectoriesAndFiles, ChangePermissions, TakeOwnership'
    foreach ($rule in (Get-Acl -LiteralPath $full).Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow) { continue }
        try { $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value }
        catch { throw '无法安全解析附件目录ACL。' }
        if ($sid -in $broadSids -and (($rule.FileSystemRights -band $writeRights) -ne 0)) {
            throw '附件目录向宽泛用户组授予了写权限。'
        }
    }
    $full
}

if ($MySqlUsername -match '^(?i:root)(?:@|$)') {
    throw '必须使用3307最终库的独立应用数据库账号，禁止使用root。'
}

if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw '未找到后端JAR，请先在backend目录执行mvn clean package。'
}
$storageRoot = Assert-RestrictedExternalStorageRoot -Path $ExpenseStorageRoot
$javaCommand = Get-Command java.exe -ErrorAction Stop
if ($javaCommand.CommandType -ne [Management.Automation.CommandTypes]::Application) {
    throw 'java.exe不是可执行应用程序。'
}

$securePassword = Read-Host '请输入MySQL应用账号密码（输入内容不会显示）' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$plainPassword = $null
$process = $null
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $javaCommand.Source
    $startInfo.Arguments = "-jar `"$jarPath`""
    $startInfo.WorkingDirectory = $backendDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $false
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
        'TEMP' = [Environment]::GetEnvironmentVariable('TEMP')
        'TMP' = [Environment]::GetEnvironmentVariable('TMP')
        'JAVA_HOME' = $javaHome
        'PATH' = ($javaBin + ';' + (Join-Path $systemRoot 'System32'))
    }
    foreach ($entry in $baseEnvironment.GetEnumerator()) {
        if (-not [string]::IsNullOrWhiteSpace([string]$entry.Value)) {
            $startInfo.Environment[$entry.Key] = [string]$entry.Value
        }
    }

    $startInfo.Environment['APP_ENV'] = $approvedAppEnvironment
    $startInfo.Environment['SERVER_PORT'] = [string]$approvedBackendPort
    $startInfo.Environment['MYSQL_HOST'] = $approvedMySqlHost
    $startInfo.Environment['MYSQL_PORT'] = [string]$approvedMySqlPort
    $startInfo.Environment['MYSQL_DATABASE'] = $approvedMySqlDatabase
    $startInfo.Environment['MYSQL_USERNAME'] = $MySqlUsername
    $startInfo.Environment['MYSQL_PASSWORD'] = $plainPassword
    $startInfo.Environment['SPRING_DATASOURCE_URL'] = $jdbcUrl
    $startInfo.Environment['SPRING_DATASOURCE_USERNAME'] = $MySqlUsername
    $startInfo.Environment['SPRING_DATASOURCE_PASSWORD'] = $plainPassword
    $startInfo.Environment['APP_SEED_DEMO_ENABLED'] = 'false'
    $startInfo.Environment['APP_SEED_LEGACY_EMPLOYEE_ENABLED'] = 'false'
    $startInfo.Environment['APP_MIGRATION_AUTO_RUN'] = 'false'
    $startInfo.Environment['APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT'] = $storageRoot

    Write-Host "正在启动STAGING预验收后端：http://127.0.0.1:$approvedBackendPort" -ForegroundColor Cyan
    Write-Host "数据库固定为：$approvedMySqlHost`:$approvedMySqlPort/$approvedMySqlDatabase（MySQL $approvedMySqlVersion）" -ForegroundColor Cyan
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    [void]$startInfo.Environment.Remove('MYSQL_PASSWORD')
    [void]$startInfo.Environment.Remove('SPRING_DATASOURCE_PASSWORD')
    $plainPassword = $null
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $passwordPointer = [IntPtr]::Zero
    $securePassword.Dispose()
    $securePassword = $null
    $process.WaitForExit()
    exit $process.ExitCode
}
finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $plainPassword = $null
    if ($securePassword) { $securePassword.Dispose() }
    if ($process) { $process.Dispose() }
}
