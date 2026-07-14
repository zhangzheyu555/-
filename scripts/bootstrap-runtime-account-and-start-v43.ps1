[CmdletBinding()]
param(
    [string]$JarPath = "$env:LOCALAPPDATA\AI-Profit-OS\warehouse-v43\20260713-175700\store-profit-backend-v43.jar",
    [string]$BackupReceipt = "$env:LOCALAPPDATA\AI-Profit-OS\backups\warehouse-v43\20260713-175516\warehouse-before-v43.receipt.json",
    [string]$StorageRoot = "$env:LOCALAPPDATA\AI-Profit-OS\expense-supplements",
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string]$RuntimeUser = 'ai_profit_runtime_v43',
    [int]$ServerPort = 18081
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$approvedHost = '127.0.0.1'
$approvedPort = 3307
$approvedDatabase = 'store_profit_mysql8'
$approvedEnvironment = 'STAGING'
$deploymentDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($JarPath))
$stdoutPath = Join-Path $deploymentDirectory 'backend.out.log'
$stderrPath = Join-Path $deploymentDirectory 'backend.err.log'
$processReceiptPath = Join-Path $deploymentDirectory 'backend-process.json'
$deploymentAuditPath = Join-Path $deploymentDirectory 'warehouse-v43-production-audit.json'

function Assert-DeploymentGate {
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) {
        throw "未找到待部署后端 JAR：$JarPath"
    }
    if (-not (Test-Path -LiteralPath $BackupReceipt -PathType Leaf)) {
        throw "未找到 V43 迁移前备份回执：$BackupReceipt"
    }
    if (-not (Test-Path -LiteralPath $StorageRoot -PathType Container)) {
        throw "附件目录不存在：$StorageRoot"
    }
    $receipt = Get-Content -LiteralPath $BackupReceipt -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($receipt.endpoint -ne "$approvedHost`:$approvedPort" -or $receipt.database -ne $approvedDatabase) {
        throw '备份回执对应的数据库端点不正确。'
    }
    if ([string]$receipt.flywayVersion -ne '40') {
        throw '备份回执不是生产 Flyway V40，禁止继续。'
    }
    if ([int]$receipt.preflight.storeCount -ne 38) {
        throw '生产库门店数不是 38，禁止继续。'
    }
    if (-not (Test-Path -LiteralPath $receipt.dump.path -PathType Leaf)) {
        throw '备份回执中的 SQL 文件不存在。'
    }
    if ((Get-FileHash -LiteralPath $receipt.dump.path -Algorithm SHA256).Hash -cne [string]$receipt.dump.sha256) {
        throw '生产库备份文件 SHA-256 与回执不一致，禁止继续。'
    }
    if (Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $ServerPort 已被占用，禁止覆盖现有服务。"
    }
}

function Request-RootCredential {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing

    $form = [Windows.Forms.Form]::new()
    $form.Text = 'AI Profit OS - 初始化非 Root 运行账号'
    $form.StartPosition = [Windows.Forms.FormStartPosition]::CenterScreen
    $form.Size = [Drawing.Size]::new(560, 270)
    $form.FormBorderStyle = [Windows.Forms.FormBorderStyle]::FixedDialog
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true

    $message = [Windows.Forms.Label]::new()
    $message.Location = [Drawing.Point]::new(24, 16)
    $message.Size = [Drawing.Size]::new(500, 50)
    $message.Text = 'Root 仅用于在 3307 创建受限运行账号。后端和 Flyway 不会使用 Root，密码不会写入脚本或日志。'
    $form.Controls.Add($message)

    $userLabel = [Windows.Forms.Label]::new()
    $userLabel.Location = [Drawing.Point]::new(24, 80)
    $userLabel.Size = [Drawing.Size]::new(88, 24)
    $userLabel.Text = 'Root 账号'
    $form.Controls.Add($userLabel)

    $userBox = [Windows.Forms.TextBox]::new()
    $userBox.Location = [Drawing.Point]::new(116, 77)
    $userBox.Size = [Drawing.Size]::new(404, 28)
    $userBox.Text = 'root'
    $form.Controls.Add($userBox)

    $passwordLabel = [Windows.Forms.Label]::new()
    $passwordLabel.Location = [Drawing.Point]::new(24, 120)
    $passwordLabel.Size = [Drawing.Size]::new(88, 24)
    $passwordLabel.Text = 'Root 密码'
    $form.Controls.Add($passwordLabel)

    $passwordBox = [Windows.Forms.TextBox]::new()
    $passwordBox.Location = [Drawing.Point]::new(116, 117)
    $passwordBox.Size = [Drawing.Size]::new(404, 28)
    $passwordBox.UseSystemPasswordChar = $true
    $form.Controls.Add($passwordBox)

    $okButton = [Windows.Forms.Button]::new()
    $okButton.Location = [Drawing.Point]::new(344, 178)
    $okButton.Size = [Drawing.Size]::new(86, 34)
    $okButton.Text = '初始化并启动'
    $okButton.DialogResult = [Windows.Forms.DialogResult]::OK
    $form.Controls.Add($okButton)

    $cancelButton = [Windows.Forms.Button]::new()
    $cancelButton.Location = [Drawing.Point]::new(438, 178)
    $cancelButton.Size = [Drawing.Size]::new(82, 34)
    $cancelButton.Text = '取消'
    $cancelButton.DialogResult = [Windows.Forms.DialogResult]::Cancel
    $form.Controls.Add($cancelButton)

    $form.AcceptButton = $okButton
    $form.CancelButton = $cancelButton
    $form.Add_Shown({ $form.Activate(); $passwordBox.Focus() })
    try {
        if ($form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { return $null }
        if ([string]::IsNullOrWhiteSpace($userBox.Text) -or [string]::IsNullOrEmpty($passwordBox.Text)) {
            throw 'Root 账号和密码不能为空。'
        }
        $securePassword = ConvertTo-SecureString -String $passwordBox.Text -AsPlainText -Force
        $passwordBox.Clear()
        [pscredential]::new($userBox.Text.Trim(), $securePassword)
    }
    finally {
        $passwordBox.Clear()
        $form.Dispose()
    }
}

function Invoke-MySqlScript {
    param(
        [Parameter(Mandatory = $true)][string]$UserName,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$Script
    )

    $mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    if (-not (Test-Path -LiteralPath $mysql -PathType Leaf)) {
        throw '未找到 MySQL 8 客户端。'
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $mysql
    $startInfo.Arguments = "--protocol=TCP --host=$approvedHost --port=$approvedPort --user=$UserName --get-server-public-key --batch --skip-column-names"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        [void]$process.Start()
        $process.StandardInput.WriteLine($Script)
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "MySQL 3307 初始化失败：$($stderr.Trim())"
        }
        $stdout
    }
    finally {
        $startInfo.EnvironmentVariables.Remove('MYSQL_PWD')
        if (-not $process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}

function New-RuntimePassword {
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = New-Object byte[] 40
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
    }
    finally {
        $rng.Dispose()
    }
}

function Start-Application {
    param(
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][string]$SslMode
    )
    $env:APP_ENV = $approvedEnvironment
    $env:SERVER_PORT = [string]$ServerPort
    $env:MYSQL_HOST = $approvedHost
    $env:MYSQL_PORT = [string]$approvedPort
    $env:MYSQL_DATABASE = $approvedDatabase
    $env:MYSQL_USERNAME = $RuntimeUser
    $env:MYSQL_PASSWORD = $Password
    $env:MYSQL_SSL_MODE = $SslMode
    $env:APP_SEED_DEMO_ENABLED = 'false'
    $env:APP_SEED_LEGACY_EMPLOYEE_ENABLED = 'false'
    $env:APP_BOOTSTRAP_DEFAULT_USERS_ENABLED = 'false'
    $env:APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED = 'false'
    $env:APP_MIGRATION_AUTO_RUN = 'false'
    $env:APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT = [IO.Path]::GetFullPath($StorageRoot)

    Remove-Item -LiteralPath $stdoutPath, $stderrPath, $processReceiptPath -Force -ErrorAction SilentlyContinue
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $process = Start-Process -FilePath $java `
        -ArgumentList @('-jar', ('"' + ([IO.Path]::GetFullPath($JarPath)) + '"')) `
        -WorkingDirectory $deploymentDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru
    [IO.File]::WriteAllText(
        $processReceiptPath,
        ([ordered]@{
            processId = $process.Id
            startedAt = (Get-Date).ToString('o')
            jar = [IO.Path]::GetFullPath($JarPath)
            port = $ServerPort
            database = "$approvedHost`:$approvedPort/$approvedDatabase"
            runtimeUser = $RuntimeUser
            sslMode = $SslMode
        } | ConvertTo-Json),
        [Text.UTF8Encoding]::new($false)
    )
    $process.Id
}

function Wait-ForApplicationHealth {
    $lastFailure = $null
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        Start-Sleep -Seconds 1
        try {
            $response = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/api/health" -TimeoutSec 4
            if (-not $response.success -or -not $response.data) {
                $lastFailure = '健康接口未返回成功结果。'
                continue
            }
            $health = $response.data
            $ready = $health.status -eq 'UP' `
                -and [string]$health.databaseMigrationVersion -eq '43' `
                -and [int]$health.databasePort -eq $approvedPort `
                -and [string]$health.databaseName -eq $approvedDatabase `
                -and [string]$health.databaseAccountScope -eq 'LOCAL_SCOPED'
            if ($ready) { return $health }
            $lastFailure = "状态=$($health.status)，Flyway=$($health.databaseMigrationVersion)，数据库=$($health.databasePort)/$($health.databaseName)，账号范围=$($health.databaseAccountScope)"
        }
        catch {
            $lastFailure = $_.Exception.Message
        }
    }
    throw "后端未在 60 秒内通过 V43 健康检查：$lastFailure"
}

function Write-ProductionAudit {
    param(
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)]$Health
    )

    $auditRows = Invoke-MySqlScript -UserName $RuntimeUser -Password $Password -Script @'
select concat('flyway|', version, '|', success)
from flyway_schema_history
where version in ('41', '42', '43')
order by installed_rank;

select concat(
  'topology|', expected_business_store_count, '|', actual_business_store_count, '|',
  bound_store_count, '|', binding_status, '|', coalesce(difference_message, '')
)
from warehouse_topology_migration_audit
where migration_key = 'V43_JINGZHOU_38_STORE_BINDING'
order by id;

select concat(
  'storeBinding|', count(*), '|',
  coalesce(sum(case when region_code = 'JINGZHOU' and supply_warehouse_id is not null then 1 else 0 end), 0)
)
from store_branch;

select concat(
  'facility|', code, '|', name, '|', warehouse_type, '|', region_code, '|',
  external_purchase_allowed, '|', store_supply_allowed, '|', enabled
)
from warehouse_facility
order by code;

select concat(
  'inventory|', facility.code, '|',
  coalesce(sum(inventory.on_hand_quantity), 0), '|',
  coalesce(sum(inventory.reserved_quantity), 0), '|',
  coalesce(sum(inventory.in_transit_quantity), 0), '|',
  coalesce(round(sum(inventory.on_hand_quantity * inventory.unit_cost), 2), 0)
)
from warehouse_facility facility
left join warehouse_inventory inventory on inventory.warehouse_id = facility.id
group by facility.id, facility.code
order by facility.code;
'@
    $lines = @($auditRows -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if (-not ($lines -match '^flyway\|43\|1$')) {
        throw '生产审计未找到成功的 Flyway V43 记录。'
    }
    if (-not ($lines -match '^topology\|38\|38\|38\|BOUND_38\|')) {
        throw '生产审计未确认 38 家门店已全部绑定荆州总仓。'
    }
    if (-not ($lines -match '^storeBinding\|38\|38$')) {
        throw '生产审计中的门店区域或供货仓绑定数量不正确。'
    }
    [IO.File]::WriteAllText(
        $deploymentAuditPath,
        ([ordered]@{
            createdAt = (Get-Date).ToString('o')
            endpoint = "$approvedHost`:$approvedPort/$approvedDatabase"
            health = $Health
            evidence = $lines
        } | ConvertTo-Json -Depth 8),
        [Text.UTF8Encoding]::new($false)
    )
    $deploymentAuditPath
}

$rootCredential = $null
$rootPointer = [IntPtr]::Zero
$rootPassword = $null
$runtimePassword = $null
try {
    Assert-DeploymentGate
    $rootCredential = Request-RootCredential
    if (-not $rootCredential) { throw '已取消初始化。' }
    if ($rootCredential.UserName -notmatch '^(?i:root)(?:@|$)') {
        throw '此入口只接受 root 作为一次性初始化账号。'
    }
    $rootPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($rootCredential.Password)
    $rootPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($rootPointer)
    $runtimePassword = New-RuntimePassword

    $capabilities = Invoke-MySqlScript -UserName $rootCredential.UserName -Password $rootPassword -Script @'
select plugin_name, plugin_status
from information_schema.plugins
where plugin_name in ('mysql_native_password', 'caching_sha2_password');
show variables like 'have_ssl';
'@
    if ($capabilities -match 'mysql_native_password\s+ACTIVE') {
        $authenticationClause = "IDENTIFIED WITH mysql_native_password BY '$runtimePassword'"
        $sslMode = 'DISABLED'
    }
    elseif ($capabilities -match 'have_ssl\s+YES') {
        $authenticationClause = "IDENTIFIED WITH caching_sha2_password BY '$runtimePassword'"
        $sslMode = 'REQUIRED'
    }
    else {
        throw '本机 MySQL 未启用 mysql_native_password 且 SSL 不可用，无法安全创建运行账号。'
    }

    Invoke-MySqlScript -UserName $rootCredential.UserName -Password $rootPassword -Script @"
create user if not exists '$RuntimeUser'@'127.0.0.1' $authenticationClause;
alter user '$RuntimeUser'@'127.0.0.1' $authenticationClause;
grant all privileges on $approvedDatabase.* to '$RuntimeUser'@'127.0.0.1';
flush privileges;
"@ | Out-Null

    $childProcessId = Start-Application -Password $runtimePassword -SslMode $sslMode
    Write-Host "已创建受限运行账号并启动后端，PID=$childProcessId。正在核验 Flyway V43 和健康状态。" -ForegroundColor Green
    $health = Wait-ForApplicationHealth
    $auditPath = Write-ProductionAudit -Password $runtimePassword -Health $health
    Write-Host "V43 生产迁移与健康核验通过。审计回执：$auditPath" -ForegroundColor Green
}
catch {
    Write-Host ''
    Write-Host ("初始化或安全启动失败：" + $_.Exception.Message) -ForegroundColor Red
}
finally {
    Remove-Item Env:MYSQL_PASSWORD -ErrorAction SilentlyContinue
    $rootPassword = $null
    $runtimePassword = $null
    if ($rootPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($rootPointer)
    }
    if ($rootCredential) { $rootCredential.Password.Dispose() }
}

Write-Host ''
[void](Read-Host '核验完成后可按 Enter 关闭此窗口')
