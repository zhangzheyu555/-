[CmdletBinding()]
param(
  [string]$MySqlHost = '127.0.0.1',
  [ValidateRange(1, 65535)][int]$MySqlPort = 3307,
  [string]$MySqlDatabase = 'store_profit_mysql8',
  [string]$MySqlClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [Parameter(Mandatory)][string]$CandidateJarPath,
  [string]$BackendWorkingDirectory,
  [string]$RuntimeDirectory,
  [ValidateRange(1, 65535)][int]$CandidatePort = 18082,
  [ValidateRange(5, 300)][int]$TimeoutSeconds = 60,
  [switch]$PromoteTo18081
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($BackendWorkingDirectory)) {
  $BackendWorkingDirectory = Join-Path $PSScriptRoot '..\backend'
}

$script:RuntimeUserName = 'ai_profit_assist_runtime'
$script:ExpectedMigrationVersion = 51

function Convert-SecureValueToPlainText {
  param([Parameter(Mandatory)][Security.SecureString]$Value)

  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
  }
}

function Set-CurrentUserOnlyFileAcl {
  param([Parameter(Mandatory)][string]$Path)

  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
  if ($null -eq $identity) { throw '无法确认当前 Windows 用户，拒绝写入临时凭据文件。' }
  $acl = [Security.AccessControl.FileSecurity]::new()
  $acl.SetOwner($identity)
  $acl.SetAccessRuleProtection($true, $false)
  $rule = [Security.AccessControl.FileSystemAccessRule]::new(
    $identity,
    [Security.AccessControl.FileSystemRights]::FullControl,
    [Security.AccessControl.AccessControlType]::Allow
  )
  [void]$acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}

function New-MySqlOptionFile {
  param([Parameter(Mandatory)][string]$UserName, [Parameter(Mandatory)][string]$Password)

  if ($UserName -notmatch '^[A-Za-z0-9_.@-]+$' -or $Password -match "[`r`n]") {
    throw '本机 MySQL 凭据格式不受支持。'
  }
  $path = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-os-' + [Guid]::NewGuid().ToString('N') + '.cnf')
  $escapedPassword = $Password.Replace('\', '\\').Replace('"', '\"')
  $content = "[client]`r`nprotocol=TCP`r`nhost=$MySqlHost`r`nport=$MySqlPort`r`nuser=$UserName`r`npassword=`"$escapedPassword`"`r`ndefault-character-set=utf8mb4`r`n"
  [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
  Set-CurrentUserOnlyFileAcl $path
  return $path
}

function Invoke-MySqlCommandSafely {
  param(
    [Parameter(Mandatory)][string]$OptionFile,
    [Parameter(Mandatory)][string]$Sql,
    [Parameter(Mandatory)][string]$Action
  )

  $start = [Diagnostics.ProcessStartInfo]::new()
  $start.FileName = $MySqlClientPath
  $start.Arguments = "--defaults-extra-file=`"$OptionFile`" --batch --raw --skip-column-names --default-character-set=utf8mb4"
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardInput = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $start
  try {
    [void]$process.Start()
    $standardOut = $process.StandardOutput.ReadToEndAsync()
    $standardError = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write($Sql)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $output = $standardOut.GetAwaiter().GetResult()
    $errorOutput = $standardError.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
      $safeReason = if ($errorOutput -match '(?i)access denied') {
        '数据库认证失败，请核对本机 MySQL root 密码及 root 的 127.0.0.1 TCP 登录权限。'
      } elseif ($errorOutput -match '(?i)can.t connect|connection refused') {
        '无法连接 MySQL 3307，请检查本机数据库服务。'
      } elseif ($errorOutput -match '(?i)unknown database') {
        '目标业务库不存在或当前 root 无法访问。'
      } elseif ($errorOutput -match '(?i)doesn.t exist|table.*not found') {
        'Flyway 元数据表不可用，已停止且未轮换账号。'
      } else {
        'MySQL 返回了未分类错误，已停止且不会输出或记录凭据。'
      }
      throw "$Action 未通过：$safeReason"
    }
    return $output
  } finally {
    $process.Dispose()
  }
}

function New-RandomRuntimePassword {
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  $bytes = [byte[]]::new(48)
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  return -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
}

function Get-CandidateMigrationVersion {
  param([Parameter(Mandatory)][string]$JarPath)

  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
  try {
    $versions = @(
      $archive.Entries |
        ForEach-Object { [regex]::Match($_.FullName, 'BOOT-INF/classes/db/migration/V(\d+)__.*\.sql$') } |
        Where-Object { $_.Success } |
        ForEach-Object { [int]$_.Groups[1].Value }
    )
    if ($versions.Count -eq 0) { throw '候选 JAR 未包含可验证的 MySQL Flyway 迁移。' }
    return ($versions | Measure-Object -Maximum).Maximum
  } finally {
    $archive.Dispose()
  }
}

function Assert-StableCandidateJar {
  param([Parameter(Mandatory)][string]$JarPath)

  $resolved = [IO.Path]::GetFullPath($JarPath)
  if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw '未找到候选 JAR。' }
  $firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).Hash
  Start-Sleep -Milliseconds 200
  $secondHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).Hash
  if ($firstHash -cne $secondHash) { throw '候选 JAR 在验证期间发生变化，拒绝启动。' }
  $candidateMigration = Get-CandidateMigrationVersion $resolved
  if ($candidateMigration -ne $script:ExpectedMigrationVersion) {
    throw "候选 JAR Flyway 版本为 V$candidateMigration，预期为 V$script:ExpectedMigrationVersion。"
  }
  return [pscustomobject]@{ Path = $resolved; Sha256 = $firstHash; MigrationVersion = $candidateMigration }
}

$rootPasswordInput = $null
$rootPassword = $null
$rootOptionFile = $null
$runtimePassword = $null
$runtimePasswordInput = $null
try {
  if ($MySqlHost -ne '127.0.0.1' -or $MySqlPort -ne 3307 -or $MySqlDatabase -ne 'store_profit_mysql8') {
    throw '该受控脚本只允许 127.0.0.1:3307/store_profit_mysql8。'
  }
  if (-not (Test-Path -LiteralPath $MySqlClientPath -PathType Leaf)) {
    throw '未找到 MySQL 8 客户端 mysql.exe。'
  }
  if (Get-NetTCPConnection -LocalPort $CandidatePort -State Listen -ErrorAction SilentlyContinue) {
    throw "候选端口 $CandidatePort 已被占用。"
  }

  $candidate = Assert-StableCandidateJar $CandidateJarPath
  $currentHealth = Invoke-RestMethod -Uri 'http://127.0.0.1:18081/api/health' -TimeoutSec 8
  if ($currentHealth.data.status -ne 'UP') {
    throw '当前 18081 公开存活检查未通过；拒绝开始受控替换。'
  }

  $rootPasswordInput = Read-Host '请输入 MySQL 3307 root 密码（输入不会显示）' -AsSecureString
  $rootPassword = Convert-SecureValueToPlainText $rootPasswordInput
  if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw 'root 密码不能为空。' }
  $rootOptionFile = New-MySqlOptionFile -UserName 'root' -Password $rootPassword

  [void](Invoke-MySqlCommandSafely -OptionFile $rootOptionFile -Sql 'SELECT 1;' -Action 'MySQL root 连接只读核验')
  $databaseVersion = [string](Invoke-MySqlCommandSafely -OptionFile $rootOptionFile -Sql 'SELECT version FROM `store_profit_mysql8`.`flyway_schema_history` WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1;' -Action 'Flyway 版本只读核验').Trim()
  if ([int]$databaseVersion -ne $script:ExpectedMigrationVersion) {
    throw "当前数据库 Flyway 为 V$databaseVersion，预期为 V$script:ExpectedMigrationVersion；拒绝开始受控替换。"
  }
  if ([int]$databaseVersion -ne $candidate.MigrationVersion) {
    throw "数据库 Flyway 为 V$databaseVersion，候选为 V$($candidate.MigrationVersion)；拒绝让启动器执行迁移。"
  }
  $accountExists = [string](Invoke-MySqlCommandSafely -OptionFile $rootOptionFile -Sql "SELECT COUNT(*) FROM mysql.user WHERE user = '$script:RuntimeUserName' AND host = '127.0.0.1';`n" -Action '受限应用账号存在性核验').Trim()
  if ($accountExists -ne '1') {
    throw '受限应用账号不存在；本脚本不会创建账号或修改授权。'
  }

  $runtimePassword = New-RandomRuntimePassword
  [void](Invoke-MySqlCommandSafely -OptionFile $rootOptionFile -Sql "ALTER USER '$script:RuntimeUserName'@'127.0.0.1' IDENTIFIED BY '$runtimePassword';`n" -Action '受限应用账号密码轮换')
  $runtimeOptionFile = $null
  try {
    $runtimeOptionFile = New-MySqlOptionFile -UserName $script:RuntimeUserName -Password $runtimePassword
    [void](Invoke-MySqlCommandSafely -OptionFile $runtimeOptionFile -Sql 'SELECT 1;' -Action '受限应用账号连接验证')
  } finally {
    if ($runtimeOptionFile -and (Test-Path -LiteralPath $runtimeOptionFile)) { Remove-Item -LiteralPath $runtimeOptionFile -Force -ErrorAction SilentlyContinue }
  }

  Write-Host "受限应用账号密码已轮换；候选 JAR SHA-256=$($candidate.Sha256)。" -ForegroundColor Green
  $runtimePasswordInput = ConvertTo-SecureString -String $runtimePassword -AsPlainText -Force
  $launcher = Join-Path $PSScriptRoot 'start-backend-assistants-secure.ps1'
  $launcherParameters = @{
    MySqlHost = $MySqlHost
    MySqlPort = $MySqlPort
    MySqlDatabase = $MySqlDatabase
    MySqlUsername = $script:RuntimeUserName
    MySqlPasswordInput = $runtimePasswordInput
    CandidatePort = $CandidatePort
    JarPath = $candidate.Path
    BackendWorkingDirectory = $BackendWorkingDirectory
    RuntimeDirectory = $RuntimeDirectory
    TimeoutSeconds = $TimeoutSeconds
  }
  if ($PromoteTo18081) {
    $launcherParameters.PromoteTo18081 = $true
    $launcherParameters.PauseBeforePromotion = $true
  }
  & $launcher @launcherParameters
  if ($LASTEXITCODE -ne 0) { throw '候选启动或受控替换未完成。' }
} finally {
  $rootPassword = $null
  $runtimePassword = $null
  if ($rootPasswordInput) { $rootPasswordInput.Dispose() }
  if ($runtimePasswordInput) { $runtimePasswordInput.Dispose() }
  if ($rootOptionFile -and (Test-Path -LiteralPath $rootOptionFile)) { Remove-Item -LiteralPath $rootOptionFile -Force -ErrorAction SilentlyContinue }
}
