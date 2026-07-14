[CmdletBinding()]
param(
    [string]$JarPath = "$env:LOCALAPPDATA\AI-Profit-OS\warehouse-v43\20260713-175700\store-profit-backend-v43.jar",
    [string]$BackupReceipt = "$env:LOCALAPPDATA\AI-Profit-OS\backups\warehouse-v43\20260713-175516\warehouse-before-v43.receipt.json",
    [string]$StorageRoot = "$env:LOCALAPPDATA\AI-Profit-OS\expense-supplements",
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
        throw '生产库备份回执不是 Flyway V40，禁止继续。'
    }
    if ([int]$receipt.preflight.storeCount -ne 38) {
        throw '生产库门店数不是 38，禁止继续。'
    }
    if (-not (Test-Path -LiteralPath $receipt.dump.path -PathType Leaf)) {
        throw '备份回执中的 SQL 文件不存在。'
    }
    $actualHash = (Get-FileHash -LiteralPath $receipt.dump.path -Algorithm SHA256).Hash
    if ($actualHash -cne [string]$receipt.dump.sha256) {
        throw '生产库备份文件 SHA-256 与回执不一致，禁止继续。'
    }
    if (Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $ServerPort 已被占用，禁止覆盖现有服务。"
    }
}

function Clear-SecretEnvironment {
    foreach ($name in @('MYSQL_PASSWORD', 'SPRING_DATASOURCE_PASSWORD')) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}

function Request-DatabaseCredential {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing

    $form = [Windows.Forms.Form]::new()
    $form.Text = 'AI Profit OS - MySQL 3307 安全启动'
    $form.StartPosition = [Windows.Forms.FormStartPosition]::CenterScreen
    $form.Size = [Drawing.Size]::new(520, 250)
    $form.FormBorderStyle = [Windows.Forms.FormBorderStyle]::FixedDialog
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.TopMost = $true

    $message = [Windows.Forms.Label]::new()
    $message.Location = [Drawing.Point]::new(24, 18)
    $message.Size = [Drawing.Size]::new(460, 38)
    $message.Text = '请输入 127.0.0.1:3307/store_profit_mysql8 的独立非 root 凭据。'
    $form.Controls.Add($message)

    $userLabel = [Windows.Forms.Label]::new()
    $userLabel.Location = [Drawing.Point]::new(24, 68)
    $userLabel.Size = [Drawing.Size]::new(80, 24)
    $userLabel.Text = '账号'
    $form.Controls.Add($userLabel)

    $userBox = [Windows.Forms.TextBox]::new()
    $userBox.Location = [Drawing.Point]::new(108, 65)
    $userBox.Size = [Drawing.Size]::new(368, 28)
    $form.Controls.Add($userBox)

    $passwordLabel = [Windows.Forms.Label]::new()
    $passwordLabel.Location = [Drawing.Point]::new(24, 108)
    $passwordLabel.Size = [Drawing.Size]::new(80, 24)
    $passwordLabel.Text = '密码'
    $form.Controls.Add($passwordLabel)

    $passwordBox = [Windows.Forms.TextBox]::new()
    $passwordBox.Location = [Drawing.Point]::new(108, 105)
    $passwordBox.Size = [Drawing.Size]::new(368, 28)
    $passwordBox.UseSystemPasswordChar = $true
    $form.Controls.Add($passwordBox)

    $okButton = [Windows.Forms.Button]::new()
    $okButton.Location = [Drawing.Point]::new(300, 158)
    $okButton.Size = [Drawing.Size]::new(82, 34)
    $okButton.Text = '安全启动'
    $okButton.DialogResult = [Windows.Forms.DialogResult]::OK
    $form.Controls.Add($okButton)

    $cancelButton = [Windows.Forms.Button]::new()
    $cancelButton.Location = [Drawing.Point]::new(394, 158)
    $cancelButton.Size = [Drawing.Size]::new(82, 34)
    $cancelButton.Text = '取消'
    $cancelButton.DialogResult = [Windows.Forms.DialogResult]::Cancel
    $form.Controls.Add($cancelButton)

    $form.AcceptButton = $okButton
    $form.CancelButton = $cancelButton
    $form.Add_Shown({ $form.Activate(); $userBox.Focus() })
    try {
        if ($form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) {
            return $null
        }
        if ([string]::IsNullOrWhiteSpace($userBox.Text) -or [string]::IsNullOrEmpty($passwordBox.Text)) {
            throw '账号和密码不能为空。'
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

$credential = $null
$passwordPointer = [IntPtr]::Zero
$plainPassword = $null
try {
    Assert-DeploymentGate
    $credential = Request-DatabaseCredential
    if (-not $credential) {
        throw '未提供数据库凭据。'
    }
    if ($credential.UserName -match '^(?i:root)(?:@|$)') {
        throw '禁止使用 root，必须使用 3307 独立应用账号。'
    }

    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($credential.Password)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)

    $env:APP_ENV = $approvedEnvironment
    $env:SERVER_PORT = [string]$ServerPort
    $env:MYSQL_HOST = $approvedHost
    $env:MYSQL_PORT = [string]$approvedPort
    $env:MYSQL_DATABASE = $approvedDatabase
    $env:MYSQL_USERNAME = $credential.UserName
    $env:MYSQL_PASSWORD = $plainPassword
    $env:MYSQL_SSL_MODE = 'DISABLED'
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
        } | ConvertTo-Json),
        [Text.UTF8Encoding]::new($false)
    )
    Write-Host "后端进程已启动，PID=$($process.Id)。Codex 正在核验 Flyway V43 和健康状态。" -ForegroundColor Green
}
catch {
    Write-Host ''
    Write-Host ("安全启动失败：" + $_.Exception.Message) -ForegroundColor Red
}
finally {
    Clear-SecretEnvironment
    $plainPassword = $null
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($credential) {
        $credential.Password.Dispose()
    }
}

Write-Host ''
[void](Read-Host '核验完成后可按 Enter 关闭此窗口')
