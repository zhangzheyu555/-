[CmdletBinding()]
param(
  [ValidateSet('Seed', 'Delete')][string]$Action = 'Seed',
  [ValidatePattern('^\d{4}-\d{2}$')][string]$Month = '2026-07',
  [string]$RuntimeDirectory,
  [string]$MySqlClientPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [switch]$PurgeDemoOperationLogs
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd('\')
. (Join-Path $projectRoot 'scripts\assistant-runtime-config.ps1')
. (Join-Path $projectRoot 'scripts\database-runtime-config.ps1')

$marker = 'LOCAL_DEMO_EMPLOYEE_RG1_20260717'
$tenantId = 1
$storeId = 'rg1'
$employeeUsernames = @('rg1_emp_01', 'rg1_emp_02', 'rg1_emp_03')

function Assert-SafeIdentifier {
  param([Parameter(Mandatory)][string]$Value, [Parameter(Mandatory)][string]$Label)
  if ($Value -notmatch '^[A-Za-z0-9_]+$') { throw "$Label 包含不受支持的字符。" }
}

function New-CurrentUserOnlyOptionFile {
  param(
    [Parameter(Mandatory)]$DatabaseConfig,
    [Parameter(Mandatory)][string]$ClientPath
  )
  if (-not (Test-Path -LiteralPath $ClientPath -PathType Leaf)) {
    throw "未找到 mysql 客户端：$ClientPath"
  }
  $path = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-os-demo-seed-' + [Guid]::NewGuid().ToString('N') + '.cnf')
  $escapedPassword = ([string]$DatabaseConfig.Password).Replace('\', '\\').Replace('"', '\"')
  $content = "[client]`r`nprotocol=TCP`r`nhost=$($DatabaseConfig.Host)`r`nport=$($DatabaseConfig.Port)`r`nuser=$($DatabaseConfig.Username)`r`npassword=`"$escapedPassword`"`r`ndefault-character-set=utf8mb4`r`n"
  [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))

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
  Set-Acl -LiteralPath $path -AclObject $acl
  return $path
}

function Invoke-MySql {
  param(
    [Parameter(Mandatory)][string]$OptionFile,
    [Parameter(Mandatory)][string]$Sql,
    [Parameter(Mandatory)][string]$ActionLabel
  )
  $sqlPath = Join-Path ([IO.Path]::GetTempPath()) ('ai-profit-os-demo-seed-' + [Guid]::NewGuid().ToString('N') + '.sql')
  [IO.File]::WriteAllText($sqlPath, $Sql, [Text.UTF8Encoding]::new($false))
  $mysqlCommand = "`"$MySqlClientPath`" --defaults-extra-file=`"$OptionFile`" --batch --raw --skip-column-names --default-character-set=utf8mb4 < `"$sqlPath`""
  $start = [Diagnostics.ProcessStartInfo]::new()
  $start.FileName = if ([string]::IsNullOrWhiteSpace($env:ComSpec)) { 'cmd.exe' } else { $env:ComSpec }
  $start.Arguments = "/d /s /c `"$mysqlCommand`""
  $start.UseShellExecute = $false
  $start.CreateNoWindow = $true
  $start.RedirectStandardOutput = $true
  $start.RedirectStandardError = $true
  $process = [Diagnostics.Process]::new()
  $process.StartInfo = $start
  try {
    [void]$process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
      throw "$ActionLabel 失败。MySQL 返回：$stderr"
    }
    return $stdout
  } finally {
    $process.Dispose()
    if (Test-Path -LiteralPath $sqlPath) {
      Remove-Item -LiteralPath $sqlPath -Force -ErrorAction SilentlyContinue
    }
  }
}

$configuration = $null
$database = $null
$optionFile = $null
try {
  $configuration = Read-AssistantRuntimeConfig $RuntimeDirectory
  $database = Assert-CompleteDatabaseRuntimeConfig $configuration
  if ($database.Host -notin @('127.0.0.1', 'localhost') -or [int]$database.Port -ne 3307 -or $database.Name -ne 'store_profit_mysql8') {
    throw '该脚本只允许写入本机 127.0.0.1:3307/store_profit_mysql8。'
  }
  Assert-SafeIdentifier -Value $database.Name -Label '数据库名'
  $optionFile = New-CurrentUserOnlyOptionFile -DatabaseConfig $database -ClientPath $MySqlClientPath
  $db = '`' + $database.Name.Replace('`', '``') + '`'

$preflightSql = @"
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET collation_connection = 'utf8mb4_unicode_ci';
USE $db;
SELECT COUNT(*) FROM store_branch WHERE tenant_id = $tenantId AND id = '$storeId';
SELECT COUNT(*) FROM auth_user WHERE tenant_id = $tenantId AND username IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03') AND role = 'EMPLOYEE' AND store_id = '$storeId';
SELECT COUNT(*) FROM employee
WHERE tenant_id = $tenantId
  AND store_id = '$storeId'
  AND name IN ('RG1员工一','RG1员工二','RG1员工三')
  AND coalesce(data_source, '') <> '$marker';
SELECT COUNT(*) FROM employee_salary_profile
WHERE tenant_id = $tenantId
  AND employee_id IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03')
  AND id NOT LIKE CONCAT('$marker', '-PROFILE-%');
SELECT COUNT(*) FROM employee_month_attendance
WHERE tenant_id = $tenantId
  AND employee_id IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03')
  AND month = '$Month'
  AND coalesce(source, '') <> '$marker';
SELECT COUNT(*) FROM salary_record
WHERE tenant_id = $tenantId
  AND employee_id IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03')
  AND month = '$Month'
  AND id NOT LIKE CONCAT('$marker', '-SALARY-%')
  AND coalesce(vacation_note, '') NOT LIKE CONCAT('%', '$marker', '%');
"@
  $preflight = @((Invoke-MySql -OptionFile $optionFile -Sql $preflightSql -ActionLabel '模拟数据导入前置检查') -split "`r?`n" | Where-Object { $_ -ne '' })
  if ($preflight.Count -lt 3 -or [int]$preflight[0] -ne 1) { throw 'rg1 门店不存在，已停止导入。' }
  if ([int]$preflight[1] -ne 3) { throw 'rg1 三个员工登录账号不完整，已停止导入。' }
  if ([int]$preflight[2] -ne 0) { throw 'rg1 已存在同名非模拟员工档案，已停止导入，避免覆盖真实数据。' }
  if ($Action -eq 'Seed') {
    if ([int]$preflight[3] -ne 0) { throw '目标员工已有非模拟工资档案，已停止导入，避免覆盖真实数据。' }
    if ([int]$preflight[4] -ne 0) { throw '目标员工已有非模拟出勤记录，已停止导入，避免覆盖真实数据。' }
    if ([int]$preflight[5] -ne 0) { throw '目标员工本月已有非模拟工资记录，已停止导入，避免覆盖真实数据。' }
  }

  $deleteSql = @"
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET collation_connection = 'utf8mb4_unicode_ci';
USE $db;
START TRANSACTION;
SET @tenant_id := $tenantId;
SET @marker := CONVERT('$marker' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
SET @month := CONVERT('$Month' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
SET @boss_id := (SELECT id FROM auth_user WHERE tenant_id = @tenant_id AND username = 'boss' LIMIT 1);
SET @boss_name := (SELECT display_name FROM auth_user WHERE tenant_id = @tenant_id AND username = 'boss' LIMIT 1);

DELETE FROM salary_record_item
WHERE tenant_id = @tenant_id
  AND salary_record_id LIKE CONCAT(@marker, '-SALARY-%');

DELETE FROM salary_record
WHERE tenant_id = @tenant_id
  AND (
    id LIKE CONCAT(@marker, '-SALARY-%')
    OR vacation_note LIKE CONCAT(@marker, '%')
    OR employee_id IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03') AND month = @month AND vacation_note LIKE CONCAT('%', @marker, '%')
  );

DELETE FROM employee_month_attendance
WHERE tenant_id = @tenant_id
  AND source = @marker
  AND employee_id IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03');

DELETE FROM employee_salary_profile
WHERE tenant_id = @tenant_id
  AND id LIKE CONCAT(@marker, '-PROFILE-%');

DELETE FROM employee
WHERE tenant_id = @tenant_id
  AND data_source = @marker
  AND id IN ('rg1_emp_01','rg1_emp_02','rg1_emp_03');

INSERT INTO operation_log(
  tenant_id, operator_id, operator_name, action, target_type, target_id,
  store_id, month, reason, before_json, after_json, created_at
) VALUES (
  @tenant_id, @boss_id, coalesce(@boss_name, '本地QA脚本'), 'local_demo_employee_delete',
  'local_demo_seed', @marker, '$storeId', @month, CONCAT(@marker, ' 删除本地模拟员工档案与工资数据'),
  NULL, JSON_OBJECT('marker', @marker, 'action', 'delete'), current_timestamp
);

COMMIT;
"@

  if ($Action -eq 'Delete') {
    [void](Invoke-MySql -OptionFile $optionFile -Sql $deleteSql -ActionLabel '删除本地模拟员工数据')
    if ($PurgeDemoOperationLogs) {
      $purgeSql = @"
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET collation_connection = 'utf8mb4_unicode_ci';
USE $db;
DELETE FROM operation_log
WHERE tenant_id = $tenantId
  AND (
    reason LIKE '%$marker%'
    OR target_id = '$marker'
    OR target_id LIKE CONCAT('$marker', '%')
  );
"@
      [void](Invoke-MySql -OptionFile $optionFile -Sql $purgeSql -ActionLabel '清理模拟数据操作日志')
    }
  } else {
    $seedSql = @"
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET collation_connection = 'utf8mb4_unicode_ci';
USE $db;
$deleteSql
START TRANSACTION;
SET @tenant_id := $tenantId;
SET @marker := CONVERT('$marker' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
SET @month := CONVERT('$Month' USING utf8mb4) COLLATE utf8mb4_unicode_ci;
SET @store_id := '$storeId';
SET @boss_id := (SELECT id FROM auth_user WHERE tenant_id = @tenant_id AND username = 'boss' LIMIT 1);
SET @boss_name := (SELECT display_name FROM auth_user WHERE tenant_id = @tenant_id AND username = 'boss' LIMIT 1);

INSERT INTO employee(
  id, tenant_id, store_id, store_name, brand_name, name, phone, role, position,
  employment_type, base_salary, status, hire_date, remark, data_source, created_at, updated_at
) VALUES
  ('rg1_emp_01', @tenant_id, @store_id, '荆州之星店', '如果', 'RG1员工一', NULL, '员工', '收银/前厅', '全职', 3800.00, '在职', '2026-07-01', CONCAT(@marker, ' 本地模拟档案，可删除'), @marker, current_timestamp, current_timestamp),
  ('rg1_emp_02', @tenant_id, @store_id, '荆州之星店', '如果', 'RG1员工二', NULL, '员工', '后厨/备货', '全职', 4200.00, '在职', '2026-07-01', CONCAT(@marker, ' 本地模拟档案，可删除'), @marker, current_timestamp, current_timestamp),
  ('rg1_emp_03', @tenant_id, @store_id, '荆州之星店', '如果', 'RG1员工三', NULL, '员工', '门店值班', '全职', 4600.00, '在职', '2026-07-01', CONCAT(@marker, ' 本地模拟档案，可删除'), @marker, current_timestamp, current_timestamp)
ON DUPLICATE KEY UPDATE
  store_name = VALUES(store_name),
  brand_name = VALUES(brand_name),
  role = VALUES(role),
  position = VALUES(position),
  employment_type = VALUES(employment_type),
  base_salary = VALUES(base_salary),
  status = VALUES(status),
  hire_date = VALUES(hire_date),
  remark = VALUES(remark),
  data_source = VALUES(data_source),
  updated_at = current_timestamp;

INSERT INTO employee_salary_profile(
  id, tenant_id, employee_id, policy_id, base_salary, guarantee_salary,
  overtime_hour_rate, performance_type, commission_type, effective_from, effective_to,
  created_at, updated_at
) VALUES
  (CONCAT(@marker, '-PROFILE-rg1_emp_01'), @tenant_id, 'rg1_emp_01', NULL, 3800.00, NULL, 25.00, 'FIXED_PERCENT', 'REVENUE_PCT', CONCAT(@month, '-01'), NULL, current_timestamp, current_timestamp),
  (CONCAT(@marker, '-PROFILE-rg1_emp_02'), @tenant_id, 'rg1_emp_02', NULL, 4200.00, NULL, 28.00, 'FIXED_PERCENT', 'REVENUE_PCT', CONCAT(@month, '-01'), NULL, current_timestamp, current_timestamp),
  (CONCAT(@marker, '-PROFILE-rg1_emp_03'), @tenant_id, 'rg1_emp_03', NULL, 4600.00, NULL, 30.00, 'FIXED_PERCENT', 'REVENUE_PCT', CONCAT(@month, '-01'), NULL, current_timestamp, current_timestamp)
ON DUPLICATE KEY UPDATE
  base_salary = VALUES(base_salary),
  overtime_hour_rate = VALUES(overtime_hour_rate),
  performance_type = VALUES(performance_type),
  commission_type = VALUES(commission_type),
  effective_from = VALUES(effective_from),
  updated_at = current_timestamp;

INSERT INTO employee_month_attendance(
  id, tenant_id, store_id, employee_id, month, attendance_days, normal_hours, overtime_hours,
  total_hours, vacation_balance, source, status, confirmed_by, confirmed_at, created_at, updated_at
) VALUES
  (CONCAT(@marker, '-ATT-rg1_emp_01-', @month), @tenant_id, @store_id, 'rg1_emp_01', @month, 26.00, 208.00, 8.00, 216.00, 2.00, @marker, 'CONFIRMED', @boss_id, current_timestamp, current_timestamp, current_timestamp),
  (CONCAT(@marker, '-ATT-rg1_emp_02-', @month), @tenant_id, @store_id, 'rg1_emp_02', @month, 25.50, 204.00, 12.00, 216.00, 1.50, @marker, 'CONFIRMED', @boss_id, current_timestamp, current_timestamp, current_timestamp),
  (CONCAT(@marker, '-ATT-rg1_emp_03-', @month), @tenant_id, @store_id, 'rg1_emp_03', @month, 26.00, 208.00, 16.00, 224.00, 1.00, @marker, 'CONFIRMED', @boss_id, current_timestamp, current_timestamp, current_timestamp)
ON DUPLICATE KEY UPDATE
  attendance_days = VALUES(attendance_days),
  normal_hours = VALUES(normal_hours),
  overtime_hours = VALUES(overtime_hours),
  total_hours = VALUES(total_hours),
  vacation_balance = VALUES(vacation_balance),
  source = VALUES(source),
  status = VALUES(status),
  confirmed_by = VALUES(confirmed_by),
  confirmed_at = current_timestamp,
  updated_at = current_timestamp;

INSERT INTO salary_record(
  id, tenant_id, store_id, month, employee_id, employee_name, position, attendance,
  gross, net_pay, normal_hours, ot_hours, work_hours, vacation_left, vacation_note,
  base, social, post, meal, full_attendance, commission, overtime, seniority,
  late_night, subsidy, performance, deduct_uniform, return_uniform, status,
  submitted_by, reviewed_by, reviewed_at, review_note, paid_at, version, created_at, updated_at
) VALUES
  (CONCAT(@marker, '-SALARY-rg1_emp_01-', @month), @tenant_id, @store_id, @month, 'rg1_emp_01', 'RG1员工一', '收银/前厅', '出勤26天，模拟数据', 4860.00, 4860.00, 208.00, 8.00, 216.00, 2.00, CONCAT(@marker, ' 模拟工资，可删除'), 3800.00, 300.00, 200.00, 180.00, 200.00, 300.00, 200.00, 80.00, 0.00, 0.00, 120.00, 520.00, 0.00, 'PAID', @boss_id, @boss_id, current_timestamp, CONCAT(@marker, ' 本地模拟工资已标注'), current_timestamp, 1, current_timestamp, current_timestamp),
  (CONCAT(@marker, '-SALARY-rg1_emp_02-', @month), @tenant_id, @store_id, @month, 'rg1_emp_02', 'RG1员工二', '后厨/备货', '出勤25.5天，模拟数据', 5456.00, 5456.00, 204.00, 12.00, 216.00, 1.50, CONCAT(@marker, ' 模拟工资，可删除'), 4200.00, 300.00, 260.00, 180.00, 200.00, 360.00, 336.00, 100.00, 0.00, 0.00, 160.00, 640.00, 0.00, 'PAID', @boss_id, @boss_id, current_timestamp, CONCAT(@marker, ' 本地模拟工资已标注'), current_timestamp, 1, current_timestamp, current_timestamp),
  (CONCAT(@marker, '-SALARY-rg1_emp_03-', @month), @tenant_id, @store_id, @month, 'rg1_emp_03', 'RG1员工三', '门店值班', '出勤26天，模拟数据', 6230.00, 6230.00, 208.00, 16.00, 224.00, 1.00, CONCAT(@marker, ' 模拟工资，可删除'), 4600.00, 300.00, 320.00, 180.00, 200.00, 430.00, 480.00, 120.00, 0.00, 0.00, 240.00, 640.00, 0.00, 'PAID', @boss_id, @boss_id, current_timestamp, CONCAT(@marker, ' 本地模拟工资已标注'), current_timestamp, 1, current_timestamp, current_timestamp);

INSERT INTO operation_log(
  tenant_id, operator_id, operator_name, action, target_type, target_id,
  store_id, month, reason, before_json, after_json, created_at
) VALUES (
  @tenant_id, @boss_id, coalesce(@boss_name, '本地QA脚本'), 'local_demo_employee_seed',
  'local_demo_seed', @marker, @store_id, @month, CONCAT(@marker, ' 导入本地模拟员工档案与工资数据'),
  NULL, JSON_OBJECT('marker', @marker, 'employees', 3, 'month', @month), current_timestamp
);

COMMIT;
"@
    [void](Invoke-MySql -OptionFile $optionFile -Sql $seedSql -ActionLabel '导入本地模拟员工数据')
  }

  $summarySql = @"
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET collation_connection = 'utf8mb4_unicode_ci';
USE $db;
SELECT 'employee', COUNT(*) FROM employee WHERE tenant_id = $tenantId AND data_source = '$marker';
SELECT 'salary_record', COUNT(*) FROM salary_record WHERE tenant_id = $tenantId AND id LIKE CONCAT('$marker', '-SALARY-%');
SELECT 'attendance', COUNT(*) FROM employee_month_attendance WHERE tenant_id = $tenantId AND source = '$marker';
SELECT 'profile', COUNT(*) FROM employee_salary_profile WHERE tenant_id = $tenantId AND id LIKE CONCAT('$marker', '-PROFILE-%');
SELECT 'operation_log', COUNT(*) FROM operation_log WHERE tenant_id = $tenantId AND (target_id = '$marker' OR reason LIKE '%$marker%');
"@
  $summary = Invoke-MySql -OptionFile $optionFile -Sql $summarySql -ActionLabel '模拟数据汇总'
  Write-Host "完成：$Action 本地 rg1 员工模拟数据。标记=$marker，月份=$Month" -ForegroundColor Green
  Write-Host $summary
} finally {
  if ($optionFile -and (Test-Path -LiteralPath $optionFile)) {
    Remove-Item -LiteralPath $optionFile -Force -ErrorAction SilentlyContinue
  }
  Clear-DatabaseRuntimeConfigPlaintext $configuration
  Clear-AssistantRuntimeConfigPlaintext $configuration
}
