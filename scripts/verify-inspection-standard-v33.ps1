[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$JarPath,
  [ValidateSet('ai_profit_test_empty', 'ai_profit_test_upgrade', 'ai_profit_test_runtime')]
  [string]$Database = 'ai_profit_test_runtime',
  [int]$Port = 18080
)

$ErrorActionPreference = 'Stop'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$evidenceRoot = Join-Path $env:TEMP ('ai-profit-inspection-v33-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$evidencePath = Join-Path $evidenceRoot 'evidence.json'
$mysqlPassword = $null
$bossPassword = $null
$mysqlCredential = $null
$bossCredential = $null
$mysqlPlain = $null
$bossPlain = $null
$token = $null
$backend = $null

function ConvertFrom-SecureValue {
  param([Parameter(Mandatory)][Security.SecureString]$Value)
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Invoke-TestQuery {
  param([Parameter(Mandatory)][string]$Sql)
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $mysql
  $startInfo.Arguments = "--protocol=TCP --host=127.0.0.1 --port=3307 --user=ai_profit_test --database=$Database --batch --skip-column-names"
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.CreateNoWindow = $true
  $startInfo.Environment['MYSQL_PWD'] = $mysqlPlain
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $startInfo
  try {
    [void]$process.Start()
    $process.StandardInput.WriteLine($Sql)
    $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw ('MySQL query failed: ' + $errorOutput.Trim()) }
    $output.Trim()
  }
  finally {
    [void]$startInfo.Environment.Remove('MYSQL_PWD')
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
  }
}

try {
  if (-not (Test-Path -LiteralPath $JarPath)) { throw 'Verification Jar does not exist.' }
  if (-not (Test-Path -LiteralPath $mysql)) { throw 'MySQL 8 client is unavailable.' }
  $service = Get-Service MySQL80Test -ErrorAction Stop
  if ($service.Status -ne 'Running') { throw 'MySQL80Test is not running.' }
  $listener = @(Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction Stop)
  if ($listener.Count -ne 1 -or $listener[0].LocalAddress -ne '127.0.0.1') {
    throw 'Port 3307 is not an isolated 127.0.0.1 listener.'
  }

  Write-Host 'Inspection standard V33 verification' -ForegroundColor Cyan
  $mysqlCredential = Get-Credential -UserName 'ai_profit_test' -Message '请输入 MySQL 8 测试账号密码（127.0.0.1:3307）'
  if (-not $mysqlCredential) { throw 'MySQL 8 credential input was cancelled.' }
  $mysqlPassword = $mysqlCredential.Password
  $bossCredential = Get-Credential -UserName 'boss' -Message '请输入 TEST BOSS 登录密码'
  if (-not $bossCredential) { throw 'TEST BOSS credential input was cancelled.' }
  $bossUser = $bossCredential.UserName
  $bossPassword = $bossCredential.Password
  $mysqlPlain = ConvertFrom-SecureValue $mysqlPassword
  $bossPlain = ConvertFrom-SecureValue $bossPassword

  $identity = Invoke-TestQuery "select concat(version(),'|',@@port,'|',database());"
  $identityLine = @($identity -split "`r?`n" | Where-Object { $_.Trim() })[-1].Trim()
  $identityParts = @($identityLine -split '\|' | ForEach-Object { $_.Trim() })
  if ($identityParts.Count -ne 3 -or $identityParts[0] -notmatch '^8\.0\.' -or $identityParts[1] -ne '3307' -or $identityParts[2] -ne $Database) {
    throw ("Database identity assertion failed. Received: {0}" -f $identityLine)
  }
  $standardTableExists = [int](Invoke-TestQuery "select count(*) from information_schema.tables where table_schema=database() and table_name='inspection_standard_item';")
  $before = if ($standardTableExists -eq 1) {
    Invoke-TestQuery "select count(*) from inspection_standard_item i join inspection_standard_version v on v.id=i.standard_version_id where v.status='ACTIVE' and i.enabled=1 and concat(coalesce(i.title,''),coalesce(i.description,'')) regexp '生熟|生肉|熟肉';"
  }
  else { -1 }

  if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Verification port $Port is already in use."
  }
  New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = 'java.exe'
  $startInfo.Arguments = '-jar "' + $JarPath + '"'
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardOutput = $false
  $startInfo.RedirectStandardError = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.Environment['APP_ENV'] = 'TEST'
  $startInfo.Environment['MYSQL_HOST'] = '127.0.0.1'
  $startInfo.Environment['MYSQL_PORT'] = '3307'
  $startInfo.Environment['MYSQL_DATABASE'] = $Database
  $startInfo.Environment['MYSQL_USERNAME'] = 'ai_profit_test'
  $startInfo.Environment['MYSQL_PASSWORD'] = $mysqlPlain
  $startInfo.Environment['SERVER_PORT'] = [string]$Port
  $backend = [Diagnostics.Process]::new()
  $backend.StartInfo = $startInfo
  [void]$backend.Start()
  [void]$startInfo.Environment.Remove('MYSQL_PASSWORD')

  $versionResponse = $null
  for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ($backend.HasExited) { throw 'Verification backend exited during startup.' }
    try {
      $versionResponse = Invoke-RestMethod "http://127.0.0.1:$Port/api/system/version" -TimeoutSec 2
      if ($versionResponse) { break }
    }
    catch { Start-Sleep -Seconds 1 }
  }
  if (-not $versionResponse) { throw 'Verification backend did not become healthy.' }
  if ($versionResponse.data.environment -ne 'TEST' -or [int]$versionResponse.data.databaseMigrationVersion -lt 33) {
    throw 'Verification backend did not report TEST environment and migration V33.'
  }

  $loginBody = @{ username = $bossUser; password = $bossPlain } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/api/auth/login" -ContentType 'application/json; charset=utf-8' -Body $loginBody
  $loginBody = $null
  $token = $login.data.token
  if ([string]::IsNullOrWhiteSpace($token)) { throw 'TEST BOSS login did not return a token.' }
  $standard = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/inspection/standards" -Headers @{ Authorization = "Bearer $token" }
  $item = @($standard.data.items | Where-Object { $_.code -eq 'M03' }) | Select-Object -First 1
  if (-not $item) { throw 'Active standard M03 was not returned by the API.' }
  $expectedTitle = '食品原料与非食品用品分区存放'
  $expectedDescription = '茶叶、糖浆、奶制品、水果、珍珠等食品原料及成品配料应分类密封存放；清洁剂、消毒剂和私人物品必须设置独立区域，不得与食品同柜混放。'
  if ($item.title -ne $expectedTitle -or $item.description -ne $expectedDescription) {
    throw 'Inspection standard API still returns stale wording.'
  }
  $after = Invoke-TestQuery "select count(*) from inspection_standard_item i join inspection_standard_version v on v.id=i.standard_version_id where v.status='ACTIVE' and i.enabled=1 and concat(coalesce(i.title,''),coalesce(i.description,'')) regexp '生熟|生肉|熟肉';"
  if ([int]$after -ne 0) { throw 'Current active standards still contain prohibited wording.' }

  $evidence = [ordered]@{
    generatedAt = (Get-Date).ToString('o')
    database = $Database
    mysqlVersion = $identityParts[0]
    port = 3307
    migrationVersion = $versionResponse.data.databaseMigrationVersion
    activeMatchesBefore = [int]$before
    activeMatchesAfter = [int]$after
    apiPath = '/api/inspection/standards'
    standard = [ordered]@{ id = $item.id; code = $item.code; title = $item.title; description = $item.description }
    backendPid = $backend.Id
    backendPort = $Port
  }
  $evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
  Set-Content -LiteralPath (Join-Path $env:TEMP 'ai-profit-inspection-v33-evidence.txt') -Value $evidencePath -Encoding UTF8
  Write-Host 'V33 database and API verification passed.' -ForegroundColor Green
  Write-Host "Evidence: $evidencePath"
}
catch {
  if ($backend -and -not $backend.HasExited) { $backend.Kill(); $backend.WaitForExit() }
  $failurePath = Join-Path $env:TEMP 'ai-profit-inspection-v33-failure.txt'
  Set-Content -LiteralPath $failurePath -Value $_.Exception.Message -Encoding UTF8
  Write-Host ('Verification failed: ' + $_.Exception.Message) -ForegroundColor Red
  throw
}
finally {
  Remove-Item Env:MYSQL_PWD,Env:MYSQL_PASSWORD -ErrorAction SilentlyContinue
  $token = $null
  $mysqlCredential = $null
  $bossCredential = $null
  $mysqlPlain = $null
  $bossPlain = $null
  if ($mysqlPassword) { $mysqlPassword.Dispose() }
  if ($bossPassword) { $bossPassword.Dispose() }
  [GC]::Collect()
}
