param(
  [Security.SecureString]$DatabaseCredential,
  [switch]$NonInteractive,
  [int]$MySqlPort = 3307,
  [string]$DatabaseName = 'ai_profit_test_upgrade',
  [string]$DatabaseUser = 'ai_profit_test',
  [int]$BackendPort = 18080,
  [int]$FrontendPort = 15173
)

$ErrorActionPreference = 'Stop'
if ($MySqlPort -ne 3307) { throw '测试运行时只允许连接本机MySQL 8端口3307，禁止3306或其他端口。' }
if ($DatabaseName -notmatch '(?i)test|qa') { throw '测试运行时数据库名必须明确包含test或qa。' }
$root = Split-Path -Parent $PSScriptRoot
$backendSource = Join-Path $root 'backend'
$frontend = Join-Path $root 'frontend-vue'
$runtime = Join-Path $env:TEMP 'ai-profit-salary-test-runtime'
$backendCopy = Join-Path $runtime 'backend'
$backendOut = Join-Path $runtime 'backend.out.log'
$backendErr = Join-Path $runtime 'backend.err.log'
$viteOut = Join-Path $runtime 'vite.out.log'
$viteErr = Join-Path $runtime 'vite.err.log'

function ConvertFrom-SecureValue([Security.SecureString]$Value) {
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function New-TestPassword {
  $bytes = New-Object byte[] 24
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  [Convert]::ToBase64String($bytes).Replace('/', 'A').Replace('+', 'b').Substring(0, 24) + '!7a'
}

function New-PasswordHash([string]$RawPassword) {
  $salt = New-Object byte[] 16
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($salt)
  $derive = [Security.Cryptography.Rfc2898DeriveBytes]::new(
    $RawPassword,
    $salt,
    120000,
    [Security.Cryptography.HashAlgorithmName]::SHA256
  )
  try {
    $hash = $derive.GetBytes(32)
    "pbkdf2`$120000`$$([Convert]::ToBase64String($salt))`$$([Convert]::ToBase64String($hash))"
  } finally {
    $derive.Dispose()
  }
}

function Set-TestBossPassword([string]$DatabasePassword, [string]$TestPassword) {
  $mysql = Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like '*MySQL Server 8.0*' } |
    Select-Object -First 1 -ExpandProperty FullName
  if (-not $mysql) { throw 'MySQL 8 client was not found.' }
  $env:MYSQL_PWD = $DatabasePassword
  try {
    $passwordHash = New-PasswordHash $TestPassword
    $sql = "select count(*) from auth_user where tenant_id=1 and username='boss' and enabled=1; update auth_user set password_hash='$passwordHash' where tenant_id=1 and username='boss' and enabled=1; delete from auth_token where user_id in (select id from auth_user where tenant_id=1 and username='boss');"
    $result = @($sql | & $mysql --host=127.0.0.1 --port=$MySqlPort --user=$DatabaseUser --database=$DatabaseName --batch --skip-column-names)
    if ($LASTEXITCODE -ne 0) { throw 'Temporary TEST boss password rotation failed.' }
    if ($result.Count -lt 1 -or [int]$result[0] -ne 1) {
      throw 'The TEST database must already contain one enabled BOSS account; Web startup no longer creates accounts.'
    }
  } finally {
    $passwordHash = $null
    $sql = $null
    $result = $null
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
  }
}

function Invoke-TestDatabaseSql([string]$DatabasePassword, [string]$Sql) {
  $mysql = Get-ChildItem 'C:\Program Files\MySQL' -Recurse -Filter mysql.exe -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like '*MySQL Server 8.0*' } |
    Select-Object -First 1 -ExpandProperty FullName
  if (-not $mysql) { throw 'MySQL 8 client was not found.' }
  $env:MYSQL_PWD = $DatabasePassword
  try {
    $Sql | & $mysql --host=127.0.0.1 --port=$MySqlPort --user=$DatabaseUser --database=$DatabaseName --batch --skip-column-names
    if ($LASTEXITCODE -ne 0) { throw 'TEST salary fixture SQL failed.' }
  } finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
  }
}

function Initialize-TestSalaryData([string]$DatabasePassword) {
  $sql = @'
set @store_id := (select id from store_branch where tenant_id = 1 order by code, id limit 1);
set @store_name := (select name from store_branch where tenant_id = 1 and id = @store_id);
set @brand_name := (select b.name from store_branch s left join brand b on b.id = s.brand_id and b.tenant_id = s.tenant_id where s.tenant_id = 1 and s.id = @store_id);

delete from salary_record_item where tenant_id = 1 and salary_record_id like 'TEST_SALARY_SR_%';
delete from operation_log where tenant_id = 1 and target_type = 'salary_record' and target_id like 'TEST_SALARY_SR_%';
delete from salary_record where tenant_id = 1 and id like 'TEST_SALARY_SR_%';
delete from employee_month_attendance where tenant_id = 1 and employee_id like 'TEST_SALARY_E%';
delete from employee where tenant_id = 1 and id like 'TEST_SALARY_E%';

insert into brand(id, tenant_id, code, name, color, sort_order)
values (990001, 1, 'TEST_SALARY_BRAND', 'TEST工资验收品牌', '#276B65', 990001)
on duplicate key update name = values(name), color = values(color);
insert into store_branch(id, tenant_id, brand_id, code, name, area, manager, status, note)
values ('TEST_SALARY_STORE', 1, 990001, 'TEST-SALARY-STORE', 'TEST工资验收店', 'TEST', 'TEST', '营业中', 'TEST_SALARY_VISUAL_QA')
on duplicate key update name = values(name), status = values(status), note = values(note);
set @store_id := 'TEST_SALARY_STORE';
set @store_name := 'TEST工资验收店';
set @brand_name := 'TEST工资验收品牌';

insert into profit_entry(tenant_id, store_id, month, sales, refund, discount, material, packaging, loss, cost_other, rent, labor, utility, property, commission, promo, repair, equip, exp_other, note)
values (1, @store_id, '2026-07', 160871.00, 0, 0, 32000, 3000, 0, 0, 12000, 28797, 3800, 0, 0, 2000, 0, 0, 0, 'TEST_SALARY_VISUAL_QA')
on duplicate key update sales = values(sales), labor = values(labor), note = values(note);

insert into employee(id, tenant_id, store_id, store_name, brand_name, name, phone, role, position, employment_type, base_salary, status, hire_date, remark, data_source)
select 'TEST_SALARY_E01', 1, @store_id, @store_name, @brand_name, 'TEST工资-李瑜', 'TEST-001', '店长', '店长', '全职', 5500.00, '在职', '2025-01-01', 'TEST_SALARY_VISUAL_QA', 'TEST'
union all select 'TEST_SALARY_E02', 1, @store_id, @store_name, @brand_name, 'TEST工资-张杰', 'TEST-002', '训练员', '训练员', '全职', 4200.00, '在职', '2025-02-01', 'TEST_SALARY_VISUAL_QA', 'TEST'
union all select 'TEST_SALARY_E03', 1, @store_id, @store_name, @brand_name, 'TEST工资-李根根', 'TEST-003', '训练员', '训练员', '全职', 4300.00, '在职', '2025-03-01', 'TEST_SALARY_VISUAL_QA', 'TEST'
union all select 'TEST_SALARY_E04', 1, @store_id, @store_name, @brand_name, 'TEST工资-李玉华', 'TEST-004', '营业员', '营业员', '全职', 3500.00, '在职', '2025-04-01', 'TEST_SALARY_VISUAL_QA', 'TEST'
union all select 'TEST_SALARY_E05', 1, @store_id, @store_name, @brand_name, 'TEST工资-王美婷', 'TEST-005', '营业员', '营业员', '全职', 3600.00, '在职', '2025-05-01', 'TEST_SALARY_VISUAL_QA', 'TEST'
union all select 'TEST_SALARY_E06', 1, @store_id, @store_name, @brand_name, 'TEST工资-刘小雨', 'TEST-006', '兼职营业员', '兼职营业员', '兼职', 0.00, '在职', '2025-06-01', 'TEST_SALARY_VISUAL_QA', 'TEST';

insert into employee_month_attendance(id, tenant_id, store_id, employee_id, month, attendance_days, normal_hours, overtime_hours, total_hours, vacation_balance, source, status, confirmed_at)
select concat('TEST_SALARY_ATT_', lpad(seq, 2, '0')), 1, @store_id, concat('TEST_SALARY_E', lpad(seq, 2, '0')), '2026-07', days, hours, overtime, hours + overtime, vacation, 'TEST', 'CONFIRMED', current_timestamp
from (
  select 1 seq, 27.00 days, 216.00 hours, 2.75 overtime, 2.00 vacation union all
  select 2, 27.00, 216.00, 0.50, 3.00 union all
  select 3, 27.00, 216.00, 3.60, 2.00 union all
  select 4, 8.00, 64.00, 0.70, 5.00 union all
  select 5, 26.00, 208.00, 2.10, 3.00 union all
  select 6, 7.00, 28.00, 0.20, 6.00
) fixture;

insert into salary_record(
  id, tenant_id, store_id, month, employee_id, employee_name, position, attendance, gross,
  normal_hours, ot_hours, work_hours, vacation_left, vacation_note, base, social, post, meal,
  full_attendance, commission, overtime, seniority, late_night, subsidy, performance,
  deduct_uniform, return_uniform, status, submitted_by, reviewed_by, reviewed_at, review_note, paid_at, version, net_pay
)
select 'TEST_SALARY_SR_01', 1, @store_id, '2026-07', 'TEST_SALARY_E01', 'TEST工资-李瑜', '店长', '27', 5876.00, 216.00, 2.75, 218.75, 2.00, 'TEST假期余额', 5500.00, 0, 0, 120.00, 0, 758.00, 0, 0, 0, 0, 0, 502.00, 0, 'SUBMITTED', 1, null, null, null, null, 1, 5876.00
union all select 'TEST_SALARY_SR_02', 1, @store_id, '2026-07', 'TEST_SALARY_E02', 'TEST工资-张杰', '训练员', '27', 4781.00, 216.00, 0.50, 216.50, 3.00, 'TEST假期余额', 4200.00, 0, 0, 0, 0, 581.00, 0, 0, 0, 0, 0, 0, 0, 'APPROVED', 1, 1, current_timestamp, 'TEST审核通过', null, 1, 4781.00
union all select 'TEST_SALARY_SR_03', 1, @store_id, '2026-07', 'TEST_SALARY_E03', 'TEST工资-李根根', '训练员', '27', 4881.00, 216.00, 3.60, 219.60, 2.00, 'TEST假期余额', 4300.00, 0, 0, 0, 0, 581.00, 0, 0, 0, 0, 0, 0, 0, 'PAID', 1, 1, current_timestamp, 'TEST已发放', current_timestamp, 1, 4881.00
union all select 'TEST_SALARY_SR_04', 1, @store_id, '2026-07', 'TEST_SALARY_E04', 'TEST工资-李玉华', '营业员', '8', 3672.00, 64.00, 0.70, 64.70, 5.00, 'TEST假期余额', 3500.00, 0, 0, 0, 0, 172.00, 0, 0, 0, 0, 0, 0, 0, 'DRAFT', null, null, null, null, null, 1, 3672.00
union all select 'TEST_SALARY_SR_05', 1, @store_id, '2026-07', 'TEST_SALARY_E05', 'TEST工资-王美婷', '营业员', '26', 4111.00, 208.00, 2.10, 210.10, 3.00, 'TEST假期余额', 3600.00, 0, 0, 0, 0, 511.00, 0, 0, 0, 0, 0, 0, 0, 'REJECTED', 1, 1, current_timestamp, 'TEST退回修改', null, 1, 4111.00;
'@
  Invoke-TestDatabaseSql $DatabasePassword $sql
}

function Remove-TestSalaryData([string]$DatabasePassword) {
  $sql = @'
delete from salary_record_item where tenant_id = 1 and salary_record_id like 'TEST_SALARY_SR_%';
delete from operation_log where tenant_id = 1 and target_type = 'salary_record' and target_id like 'TEST_SALARY_SR_%';
delete from salary_record where tenant_id = 1 and id like 'TEST_SALARY_SR_%';
delete from employee_month_attendance where tenant_id = 1 and employee_id like 'TEST_SALARY_E%';
delete from employee where tenant_id = 1 and id like 'TEST_SALARY_E%';
delete from profit_entry where tenant_id = 1 and store_id = 'TEST_SALARY_STORE' and note = 'TEST_SALARY_VISUAL_QA';
delete from store_branch where tenant_id = 1 and id = 'TEST_SALARY_STORE' and note = 'TEST_SALARY_VISUAL_QA';
delete from brand where tenant_id = 1 and id = 990001 and code = 'TEST_SALARY_BRAND';
'@
  Invoke-TestDatabaseSql $DatabasePassword $sql
}

function Get-TestEmployeeAudit([string]$DatabasePassword) {
  $sql = @'
select 'employee_total_all_tenants', count(*) from employee
union all select 'employee_total_tenant_1', count(*) from employee where tenant_id = 1
union all select 'employee_inactive_tenant_1', count(*) from employee where tenant_id = 1 and status in ('离职', '停用', '删除', 'INACTIVE', 'DELETED')
union all select 'employee_invalid_store_tenant_1', count(*)
  from employee e left join store_branch s on s.tenant_id = e.tenant_id and s.id = e.store_id
  where e.tenant_id = 1 and s.id is null
union all select concat('store:', e.store_id), count(*) from employee e where e.tenant_id = 1 group by e.store_id
order by 1;
'@
  @(Invoke-TestDatabaseSql $DatabasePassword $sql)
}

function Wait-Version([Diagnostics.Process]$Process) {
  for ($attempt = 0; $attempt -lt 120; $attempt++) {
    if ($Process.HasExited) { throw 'Latest TEST backend exited during startup.' }
    try {
      $response = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/system/version" -TimeoutSec 2
      if ($response.data.environment -eq 'TEST') { return $response.data }
    } catch { Start-Sleep -Seconds 1 }
  }
  throw 'Latest TEST backend did not become healthy within 120 seconds.'
}

function Start-Backend([string]$Jar, [string]$DatabasePassword) {
  $env:APP_ENV = 'TEST'
  $env:MYSQL_HOST = '127.0.0.1'
  $env:MYSQL_PORT = [string]$MySqlPort
  $env:MYSQL_DATABASE = $DatabaseName
  $env:MYSQL_USERNAME = $DatabaseUser
  $env:MYSQL_PASSWORD = $DatabasePassword
  $env:SERVER_PORT = [string]$BackendPort
  Start-Process java.exe -ArgumentList @('-jar', $Jar) -PassThru -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr
}

if (Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue) {
  throw "Port $BackendPort is already occupied. No process was stopped."
}
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
if (Test-Path $backendCopy) { Remove-Item $backendCopy -Recurse -Force }
New-Item -ItemType Directory -Path $backendCopy -Force | Out-Null
& robocopy $backendSource $backendCopy /E /XD target .git /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -gt 7) { throw "Backend copy failed with code $LASTEXITCODE." }

$databaseSecure = if ($DatabaseCredential) { $DatabaseCredential.Copy() } else { Read-Host "$DatabaseUser MySQL password" -AsSecureString }
$databasePassword = ConvertFrom-SecureValue $databaseSecure
$testPassword = New-TestPassword
$employeeAudit = Get-TestEmployeeAudit $databasePassword
Set-Content -LiteralPath (Join-Path $runtime 'employee-audit.txt') -Value $employeeAudit -Encoding utf8
trap {
  Remove-Item Env:TEST_BOSS_PASSWORD -ErrorAction SilentlyContinue
  Remove-Item Env:MYSQL_PASSWORD -ErrorAction SilentlyContinue
  $databasePassword = $null
  $testPassword = $null
  if ($databaseSecure) { $databaseSecure.Dispose() }
  throw $_
}
Write-Host 'A fresh TEST boss password was generated in process memory for automated browser verification.' -ForegroundColor Green

Push-Location $backendCopy
try {
  & mvn -q -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw 'Latest backend package failed.' }
} finally { Pop-Location }
$jar = Join-Path $backendCopy 'target\store-profit-backend-0.1.0-SNAPSHOT.jar'

$migration = Start-Backend $jar $databasePassword
$migrationVersion = Wait-Version $migration
Stop-Process -Id $migration.Id -Force
$migration.WaitForExit()

Set-TestBossPassword $databasePassword $testPassword

$backend = Start-Backend $jar $databasePassword
$version = Wait-Version $backend
if ([int]$version.databaseMigrationVersion -lt 34) { throw 'Flyway did not reach V34.' }

$loginBody = @{ username = 'boss'; password = $testPassword } | ConvertTo-Json
$login = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$token = $login.data.token
if (-not $token) { throw 'TEST boss login did not return a token.' }
$headers = @{ Authorization = "Bearer $token" }
$salary = Invoke-RestMethod "http://127.0.0.1:$BackendPort/api/salaries/employee-page?month=2026-07&page=1&size=20" -Headers $headers
if (-not $salary.success) { throw 'Salary employee page did not return a successful response.' }
if ([int]$salary.data.totalElements -le 0) {
  throw 'The MySQL 8 TEST tenant has no employees. Stop here and use the approved read-only source migration with de-identification; no fixtures were written.'
}

$env:VITE_BACKEND_PROXY_TARGET = "http://127.0.0.1:$BackendPort"
$vite = Start-Process 'npx.cmd' -WorkingDirectory $frontend -ArgumentList @('vite', '--host', '127.0.0.1', '--port', [string]$FrontendPort) -PassThru -RedirectStandardOutput $viteOut -RedirectStandardError $viteErr
Start-Sleep -Seconds 3

Set-Content -LiteralPath (Join-Path $runtime 'backend.pid') -Value $backend.Id -Encoding ascii
Set-Content -LiteralPath (Join-Path $runtime 'vite.pid') -Value $vite.Id -Encoding ascii
Write-Host "TEST backend ready: http://127.0.0.1:$BackendPort" -ForegroundColor Green
Write-Host "TEST Vue ready: http://127.0.0.1:$FrontendPort" -ForegroundColor Green
Write-Host "Environment: $($version.environment); migration: $($version.databaseMigrationVersion); source: $($version.sourceVersion)"
Write-Host "Salary endpoint: HTTP 200; total employees in TEST database: $($salary.data.total)"
Write-Host 'Running the authenticated salary page verification now...'

$env:TEST_BOSS_PASSWORD = $testPassword
$env:SALARY_TEST_BASE_URL = "http://127.0.0.1:$FrontendPort"
try {
  $browserPassed = $false
  for ($attempt = 1; $attempt -le 2; $attempt++) {
    & node.exe (Join-Path $frontend 'scripts\salary-test-browser.mjs')
    if ($LASTEXITCODE -eq 0) {
      $browserPassed = $true
      break
    }
    if ($attempt -lt 2) {
      Write-Warning 'Browser verification failed once; retrying with the same in-memory TEST credentials.'
      Start-Sleep -Seconds 2
    }
  }
  if (-not $browserPassed) { throw 'Authenticated salary page verification failed. See salary-browser-report.json.' }
} finally {
  Remove-Item Env:TEST_BOSS_PASSWORD -ErrorAction SilentlyContinue
  Remove-Item Env:SALARY_TEST_BASE_URL -ErrorAction SilentlyContinue
}

$env:MYSQL_PASSWORD = $null
$databasePassword = $null
$testPassword = $null
$databaseSecure.Dispose()
if (-not $NonInteractive) {
  Read-Host 'Keep this window open during verification. Press Enter only after testing is finished'
}
