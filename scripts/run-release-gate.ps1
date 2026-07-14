[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$backendSource = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend-vue'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$database = 'ai_profit_test_empty'
$reportPath = Join-Path $root 'release-gate-final.md'
$temporaryRoot = Join-Path $env:TEMP ('ai-profit-release-gate-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$temporaryBackend = Join-Path $temporaryRoot 'backend'
$backendLog = Join-Path $temporaryRoot 'backend.log'
$backendErrorLog = Join-Path $temporaryRoot 'backend-error.log'
$backendProcess = $null
$results = New-Object Collections.Generic.List[object]

function Add-Result {
  param([string]$Stage, [string]$Status, [string]$Evidence)
  $results.Add([pscustomobject]@{ Stage = $Stage; Status = $Status; Evidence = $Evidence })
  $color = if ($Status -eq 'PASS') { 'Green' } elseif ($Status -eq 'BLOCKED') { 'Yellow' } else { 'Red' }
  Write-Host ("[{0}] {1}: {2}" -f $Status, $Stage, $Evidence) -ForegroundColor $color
}

function ConvertFrom-SecureValue {
  param([Parameter(Mandatory)][Security.SecureString]$Value)
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Invoke-TestMySql {
  param([Parameter(Mandatory)][string]$Sql, [Parameter(Mandatory)][string]$Password)
  $startInfo = New-Object Diagnostics.ProcessStartInfo
  $startInfo.FileName = $mysql
  $startInfo.Arguments = '--protocol=TCP --host=127.0.0.1 --port=3307 --user=ai_profit_test --batch --skip-column-names'
  $startInfo.UseShellExecute = $false
  $startInfo.RedirectStandardInput = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.CreateNoWindow = $true
  $startInfo.EnvironmentVariables['MYSQL_PWD'] = $Password
  $process = New-Object Diagnostics.Process
  $process.StartInfo = $startInfo
  try {
    [void]$process.Start()
    $process.StandardInput.WriteLine($Sql)
    $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd()
    $errorOutput = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw ('MySQL command failed. ' + $errorOutput.Trim()) }
    $output.Trim()
  }
  finally {
    if (-not $process.HasExited) { $process.Kill() }
    $process.Dispose()
  }
}

function Write-GateReport {
  param([string]$Conclusion, [string[]]$Blockers)
  $content = New-Object Collections.Generic.List[string]
  @(
    '# Release Gate Final',
    '',
    "- Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
    '- Environment: TEST',
    '- MySQL: 8.0 isolated local instance on 127.0.0.1:3307',
    '- Backend verification port: 18080',
    '- Secrets persisted: no',
    '',
    '## Results',
    '',
    '| Stage | Status | Evidence |',
    '|---|---|---|'
  ) | ForEach-Object { $content.Add($_) }
  $results | ForEach-Object {
    $content.Add("| $($_.Stage) | $($_.Status) | $($_.Evidence -replace '\|', '\|') |")
  }
  @('', '## Remaining blockers', '') | ForEach-Object { $content.Add($_) }
  if ($Blockers.Count -eq 0) {
    $content.Add('- None')
  }
  else {
    $Blockers | ForEach-Object { $content.Add("- $_") }
  }
  @('', '## Conclusion', '', "**$Conclusion**", '', 'No commit, push, or release was performed.') |
    ForEach-Object { $content.Add($_) }
  [IO.File]::WriteAllLines($reportPath, $content, (New-Object Text.UTF8Encoding($false)))
}

$securePassword = Read-Host 'ai_profit_test password' -AsSecureString
$password = ConvertFrom-SecureValue $securePassword
try {
  if (-not (Test-Path -LiteralPath $mysql)) { throw 'MySQL 8 client is unavailable.' }
  $service = Get-Service -Name 'MySQL80Test' -ErrorAction Stop
  if ($service.Status -ne 'Running' -or $service.StartType -ne 'Manual') {
    throw 'MySQL80Test must be running with Manual startup type.'
  }
  $listener = Get-NetTCPConnection -State Listen -LocalPort 3307 -ErrorAction Stop
  if ($listener.LocalAddress -ne '127.0.0.1') { throw 'MySQL80Test is not restricted to 127.0.0.1.' }
  Add-Result 'Environment preflight' 'PASS' 'MySQL80Test is Manual and listens only on 127.0.0.1:3307.'

  $databaseEvidence = Invoke-TestMySql -Password $password -Sql @"
SELECT CONCAT(VERSION(), '|', @@port, '|', @@character_set_server);
SELECT CONCAT(SCHEMA_NAME, '|', DEFAULT_CHARACTER_SET_NAME)
FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$database';
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$database';
"@
  if ($databaseEvidence -notmatch '8\.0\.46\|3307\|utf8mb4') { throw 'Unexpected MySQL version, port, or server character set.' }
  if ($databaseEvidence -notmatch "$database\|utf8mb4") { throw 'The empty TEST database is missing or not utf8mb4.' }
  $lineValues = @($databaseEvidence -split "`r?`n")
  $tableCount = [int]$lineValues[-1]
  if ($tableCount -ne 0) { throw "$database already contains $tableCount tables; destructive cleanup is intentionally refused." }
  Add-Result 'Empty MySQL database' 'PASS' 'MySQL 8.0.46, utf8mb4, zero pre-migration tables.'

  if (Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue) {
    throw 'Port 18080 is already in use; no process was terminated.'
  }

  New-Item -ItemType Directory -Path $temporaryBackend -Force | Out-Null
  & robocopy $backendSource $temporaryBackend /E /XD target .git /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -gt 7) { throw "Backend source copy failed with robocopy code $LASTEXITCODE." }

  $env:APP_ENV = 'TEST'
  $env:MYSQL_HOST = '127.0.0.1'
  $env:MYSQL_PORT = '3307'
  $env:MYSQL_DATABASE = $database
  $env:MYSQL_USERNAME = 'ai_profit_test'
  $env:MYSQL_PASSWORD = $password
  $env:SERVER_PORT = '18080'

  Push-Location $temporaryBackend
  try {
    & mvn -q test
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed.' }
    Add-Result 'Backend tests' 'PASS' 'Current backend test suite completed successfully.'
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Backend package failed.' }
    Add-Result 'Backend package' 'PASS' 'Fresh executable Jar built in an isolated temporary directory.'
  }
  finally { Pop-Location }

  Push-Location $frontend
  try {
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw 'Vue3 production build failed.' }
    Add-Result 'Vue3 build' 'PASS' 'Type checking and Vite production build completed successfully.'
  }
  finally { Pop-Location }

  $jar = Join-Path $temporaryBackend 'target\store-profit-backend-0.1.0-SNAPSHOT.jar'
  if (-not (Test-Path -LiteralPath $jar)) { throw 'Fresh backend Jar was not produced.' }
  $backendProcess = Start-Process -FilePath 'java.exe' -ArgumentList @('-jar', $jar) -PassThru -RedirectStandardOutput $backendLog -RedirectStandardError $backendErrorLog
  $versionResponse = $null
  for ($attempt = 0; $attempt -lt 90; $attempt++) {
    if ($backendProcess.HasExited) { throw 'Fresh backend exited before becoming healthy.' }
    try {
      $versionResponse = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/system/version' -TimeoutSec 2
      if ($versionResponse) { break }
    }
    catch { Start-Sleep -Seconds 1 }
  }
  if (-not $versionResponse) { throw 'Fresh backend did not become healthy within 90 seconds.' }
  $versionData = if ($versionResponse.data) { $versionResponse.data } else { $versionResponse }
  if ($versionData.environment -ne 'TEST') { throw 'Fresh backend did not report TEST environment.' }
  if ($versionData.databaseMigrationVersion -in @('none', 'unavailable', $null, '')) { throw 'Flyway migration version is unavailable.' }
  Add-Result 'Empty database Flyway migration' 'PASS' ("Migrated through version {0}." -f $versionData.databaseMigrationVersion)
  Add-Result 'Fresh runtime identity' 'PASS' ("TEST on 18080; sourceVersion={0}; buildTime={1}." -f $versionData.sourceVersion, $versionData.buildTime)

  $postMigrationTables = Invoke-TestMySql -Password $password -Sql "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$database';"
  if ([int]$postMigrationTables -le 0) { throw 'Flyway completed without creating schema tables.' }
  Add-Result 'MySQL persistence evidence' 'PASS' ("$postMigrationTables tables exist after migration.")

  $blockers = @(
    'A repository-external sanitized MySQL .sql backup was not supplied, so legacy database upgrade verification is not executed.',
    'Password rotation, six real business workflows, concurrency, and dependency failure recovery still require dedicated TEST accounts and fixtures.',
    'The historical risk replacement matrix is not yet complete.'
  )
  foreach ($blocker in $blockers) { Add-Result 'Release readiness' 'BLOCKED' $blocker }
  Write-GateReport -Conclusion 'BLOCKED' -Blockers $blockers
  Write-Host "BLOCKED - report written to $reportPath" -ForegroundColor Yellow
}
catch {
  Add-Result 'Release gate execution' 'FAIL' $_.Exception.Message
  Write-GateReport -Conclusion 'BLOCKED' -Blockers @($_.Exception.Message)
  throw
}
finally {
  if ($backendProcess -and -not $backendProcess.HasExited) {
    Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    $backendProcess.WaitForExit()
  }
  $env:MYSQL_PASSWORD = $null
  $password = $null
  $securePassword.Dispose()
}
