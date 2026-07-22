[CmdletBinding()]
param(
  [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedHost = '127.0.0.1'
$expectedPort = 3307
$expectedDatabase = 'ai_profit_qa45_20260718'
$expectedCollation = 'utf8mb4_unicode_ci'
$expectedFlywayVersion = '68'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outputRoot = Join-Path $projectRoot 'output\qa'
$reportPath = Join-Path $outputRoot 'qa45-fixture-verification.json'
$batchId = 'QA45-BASE-20260718'

function Require-EnvironmentValue([string]$Name) {
  $value = [Environment]::GetEnvironmentVariable($Name, [EnvironmentVariableTarget]::Process)
  if ([string]::IsNullOrWhiteSpace($value)) {
    throw "Required runtime environment variable '$Name' is not set."
  }
  return $value
}

function Get-MySqlClient {
  foreach ($candidate in @(
      (Get-Command mysql.exe -ErrorAction SilentlyContinue),
      (Get-Command mysql -ErrorAction SilentlyContinue),
      (Get-Item 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -ErrorAction SilentlyContinue)
    )) {
    if ($null -ne $candidate) {
      $sourceProperty = $candidate.PSObject.Properties['Source']
      if ($null -ne $sourceProperty -and -not [string]::IsNullOrWhiteSpace([string]$sourceProperty.Value)) {
        return $sourceProperty.Value
      }
      return $candidate.FullName
    }
  }
  throw 'mysql client was not found.'
}

function Invoke-Qa45Sql([string]$Sql) {
  $client = Get-MySqlClient
  $startInfo = [Diagnostics.ProcessStartInfo]::new()
  $startInfo.FileName = $client
  $startInfo.Arguments = "--protocol=TCP --host=$script:mysqlHost --port=$script:mysqlPort --user=$script:mysqlUsername --database=$script:mysqlDatabase --batch --skip-column-names --raw"
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $startInfo.RedirectStandardInput = $true
  $startInfo.EnvironmentVariables['MYSQL_PWD'] = $script:mysqlPassword
  $process = [Diagnostics.Process]::new()
  try {
    $process.StartInfo = $startInfo
    if (-not $process.Start()) { throw 'Unable to start mysql client.' }
    $process.StandardInput.WriteLine($Sql)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "QA45 MySQL command failed: $stderr" }
    return $stdout.Trim()
  } finally {
    if ($null -ne $process) { $process.Dispose() }
  }
}

function ConvertTo-Pbkdf2Hash([string]$Password) {
  $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', [EnvironmentVariableTarget]::Process)
  $javac = if ([string]::IsNullOrWhiteSpace($javaHome)) { 'javac' } else { Join-Path $javaHome 'bin\javac.exe' }
  $java = if ([string]::IsNullOrWhiteSpace($javaHome)) { 'java' } else { Join-Path $javaHome 'bin\java.exe' }
  $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('qa45-password-' + [Guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
  $sourcePath = Join-Path $tempRoot 'Qa45PasswordHash.java'
$javaSource = @'
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Qa45PasswordHash {
  public static void main(String[] args) throws Exception {
    char[] password = new BufferedReader(new InputStreamReader(System.in)).readLine().toCharArray();
    byte[] salt = new byte[16];
    byte[] hash = null;
    try {
      new SecureRandom().nextBytes(salt);
      PBEKeySpec spec = new PBEKeySpec(password, salt, 120000, 256);
      try { hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
      finally { spec.clearPassword(); }
      System.out.print("pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash));
    } finally {
      java.util.Arrays.fill(password, '\0');
      java.util.Arrays.fill(salt, (byte) 0);
      if (hash != null) java.util.Arrays.fill(hash, (byte) 0);
    }
  }
}
'@
  [IO.File]::WriteAllText($sourcePath, $javaSource, [Text.UTF8Encoding]::new($false))
  try {
    & $javac -d $tempRoot $sourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Unable to compile the isolated QA password helper.' }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $java
    $startInfo.Arguments = "-cp `"$tempRoot`" Qa45PasswordHash"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    try {
      $process.StartInfo = $startInfo
      if (-not $process.Start()) { throw 'Unable to run the isolated QA password helper.' }
      $process.StandardInput.WriteLine($Password)
      $process.StandardInput.Close()
      $hash = $process.StandardOutput.ReadToEnd().Trim()
      $errorText = $process.StandardError.ReadToEnd()
      $process.WaitForExit()
      if ($process.ExitCode -ne 0 -or -not $hash.StartsWith('pbkdf2$120000$')) { throw 'QA password helper failed.' }
      return $hash
    } finally { if ($null -ne $process) { $process.Dispose() } }
  } finally {
    Remove-Item -LiteralPath $tempRoot -Force -Recurse -ErrorAction SilentlyContinue
  }
}

function Assert-QA45Gate {
  if ($script:mysqlHost -ne $expectedHost -or [int]$script:mysqlPort -ne $expectedPort -or $script:mysqlDatabase -ne $expectedDatabase) {
    throw 'QA45 fixture initialization is locked to 127.0.0.1:3307/ai_profit_qa45_20260718.'
  }
  $identity = Invoke-Qa45Sql @'
select concat(database(), '|', @@port, '|', (select default_collation_name from information_schema.schemata where schema_name = database()));
'@
  if ($identity -ne "$expectedDatabase|$expectedPort|$expectedCollation") {
    throw 'QA45 database identity, port, or collation gate failed.'
  }
  $flyway = Invoke-Qa45Sql @'
select version from flyway_schema_history where success = 1 order by installed_rank desc limit 1;
'@
  if ($flyway -ne $expectedFlywayVersion) { throw 'QA45 Flyway gate failed: expected V68.' }
  $nonQaRows = Invoke-Qa45Sql @'
select sum(row_count) from (
  select count(*) as row_count from store_branch where code not like 'QA-S%'
  union all select count(*) from auth_user where username not like 'qa45_%'
  union all select count(*) from employee where id not like 'QA45-%'
  union all select count(*) from profit_entry where tenant_id <> coalesce((select id from tenant where name='QA45 Tenant' limit 1), -1)
  union all select count(*) from expense_claim where tenant_id <> coalesce((select id from tenant where name='QA45 Tenant' limit 1), -1)
  union all select count(*) from daily_loss_report where tenant_id <> coalesce((select id from tenant where name='QA45 Tenant' limit 1), -1)
  union all select count(*) from inspection_record where tenant_id <> coalesce((select id from tenant where name='QA45 Tenant' limit 1), -1)
  union all select count(*) from business_todo where tenant_id <> coalesce((select id from tenant where name='QA45 Tenant' limit 1), -1)
) qa_business_rows;
'@
  if ([int64]$nonQaRows -ne 0) { throw 'QA45 database contains existing store, account, or business data.' }
  return [ordered]@{ database = $script:mysqlDatabase; port = [int]$script:mysqlPort; collation = $expectedCollation; flywayVersion = $flyway }
}

$script:mysqlHost = Require-EnvironmentValue 'MYSQL_HOST'
$script:mysqlPort = Require-EnvironmentValue 'MYSQL_PORT'
$script:mysqlDatabase = Require-EnvironmentValue 'MYSQL_DATABASE'
$script:mysqlUsername = Require-EnvironmentValue 'MYSQL_USERNAME'
$script:mysqlPassword = Require-EnvironmentValue 'MYSQL_PASSWORD'

$gate = Assert-QA45Gate
$result = [ordered]@{
  schema = 'ai-profit-os/qa45-fixtures/v1'
  batchId = $batchId
  applyRequested = [bool]$Apply
  status = 'DRY_RUN'
  database = $gate
  created = [ordered]@{}
  reused = [ordered]@{}
  failed = 0
  verification = $null
}

if (-not $Apply) {
  $result.message = 'Dry run passed. Re-run with -Apply and a runtime-only QA45_ACCOUNT_PASSWORD to create QA45 fixtures.'
  $result | ConvertTo-Json -Depth 8
  exit 0
}

$accountPassword = Require-EnvironmentValue 'QA45_ACCOUNT_PASSWORD'
$passwordHash = ConvertTo-Pbkdf2Hash $accountPassword
$qaTenantId = [int64](Invoke-Qa45Sql "select id from tenant where name='QA45 Tenant' limit 1;")
$expenseStorageRoot = [Environment]::GetEnvironmentVariable('APP_EXPENSE_SUPPLEMENT_STORAGE_ROOT', [EnvironmentVariableTarget]::Process)
if ([string]::IsNullOrWhiteSpace($expenseStorageRoot)) { $expenseStorageRoot = Join-Path $projectRoot 'backend\expense-supplements' }
$expenseStorageKey = '11111111-1111-4111-8111-111111111111.png'
$expenseImagePath = Join-Path (Join-Path (Join-Path $expenseStorageRoot $qaTenantId) '11') $expenseStorageKey
$qaImageBytes = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z/AAAAUBA f+JmT0dAAAAAElFTkSuQmCC'.Replace(' ', ''))
New-Item -ItemType Directory -Path (Split-Path -Parent $expenseImagePath) -Force | Out-Null
if (-not (Test-Path -LiteralPath $expenseImagePath -PathType Leaf)) { [IO.File]::WriteAllBytes($expenseImagePath, $qaImageBytes) }
try {
  $sql = @'
start transaction;

insert into tenant(name, industry, scale, status, created_at)
select 'QA45 Tenant', 'QA', 'QA45', 'ACTIVE', current_timestamp
where not exists (select 1 from tenant where name = 'QA45 Tenant');
set @tenant_id := (select id from tenant where name = 'QA45 Tenant' limit 1);

insert into brand(tenant_id, code, name, color, sort_order, active, created_at)
values (@tenant_id, 'QA45', 'QA45 Brand', '#1f6f6b', 1, 1, current_timestamp)
on duplicate key update name=values(name), active=1, updated_at=current_timestamp;
set @brand_id := (select id from brand where tenant_id=@tenant_id and code='QA45' limit 1);

insert into warehouse_facility(tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id, external_purchase_allowed, store_supply_allowed, enabled, created_at)
values (@tenant_id, 'QA45-WH-CENTRAL', 'QA45 Central Warehouse', 'CENTRAL', 'JINGZHOU', null, 1, 1, 1, current_timestamp)
on duplicate key update enabled=1, updated_at=current_timestamp;
set @central_warehouse_id := (select id from warehouse_facility where tenant_id=@tenant_id and code='QA45-WH-CENTRAL' limit 1);
insert into warehouse_facility(tenant_id, code, name, warehouse_type, region_code, parent_warehouse_id, external_purchase_allowed, store_supply_allowed, enabled, created_at)
values (@tenant_id, 'QA45-WH-REGIONAL', 'QA45 Regional Warehouse', 'REGIONAL', 'JINGZHOU', @central_warehouse_id, 0, 1, 1, current_timestamp)
on duplicate key update parent_warehouse_id=@central_warehouse_id, enabled=1, updated_at=current_timestamp;

insert into store_branch(id, tenant_id, brand_id, code, name, area, region_code, supply_warehouse_id, manager, open_date, status, note, created_at)
values
 ('QA-S01', @tenant_id, @brand_id, 'QA-S01', 'QA45 Store S01', 'QA45', 'JINGZHOU', @central_warehouse_id, 'QA45 Manager S01', '2026-01-01', 'ACTIVE', 'QA45 fixture', current_timestamp),
 ('QA-S02', @tenant_id, @brand_id, 'QA-S02', 'QA45 Store S02', 'QA45', 'JINGZHOU', @central_warehouse_id, 'QA45 Manager S02', '2026-01-01', 'ACTIVE', 'QA45 fixture', current_timestamp),
 ('QA-S03', @tenant_id, @brand_id, 'QA-S03', 'QA45 Store S03', 'QA45', 'JINGZHOU', @central_warehouse_id, 'QA45 Manager S03', '2026-01-01', 'ACTIVE', 'QA45 fixture', current_timestamp)
on duplicate key update brand_id=values(brand_id), supply_warehouse_id=values(supply_warehouse_id), status='ACTIVE', updated_at=current_timestamp;

insert into auth_user(tenant_id, username, password_hash, display_name, role, store_id, enabled, permission_version, created_at)
values
 (@tenant_id, 'qa45_boss', '__PASSWORD_HASH__', 'QA45 BOSS', 'BOSS', null, 1, 1, current_timestamp),
 (@tenant_id, 'qa45_finance', '__PASSWORD_HASH__', 'QA45 FINANCE', 'FINANCE', null, 1, 1, current_timestamp),
 (@tenant_id, 'qa45_supervisor', '__PASSWORD_HASH__', 'QA45 SUPERVISOR', 'SUPERVISOR', null, 1, 1, current_timestamp),
 (@tenant_id, 'qa45_warehouse', '__PASSWORD_HASH__', 'QA45 WAREHOUSE', 'WAREHOUSE', null, 1, 1, current_timestamp),
 (@tenant_id, 'qa45_employee', '__PASSWORD_HASH__', 'QA45 EMPLOYEE', 'EMPLOYEE', 'QA-S01', 1, 1, current_timestamp),
 (@tenant_id, 'qa45_manager_s01', '__PASSWORD_HASH__', 'QA45 STORE_MANAGER S01', 'STORE_MANAGER', 'QA-S01', 1, 1, current_timestamp),
 (@tenant_id, 'qa45_manager_s02', '__PASSWORD_HASH__', 'QA45 STORE_MANAGER S02', 'STORE_MANAGER', 'QA-S02', 1, 1, current_timestamp)
on duplicate key update password_hash=values(password_hash), display_name=values(display_name), role=values(role), store_id=values(store_id), enabled=1, updated_at=current_timestamp;

insert ignore into user_store_scope(tenant_id, user_id, store_id)
select @tenant_id, u.id, s.id from auth_user u join store_branch s on s.tenant_id=@tenant_id
where u.tenant_id=@tenant_id and u.username in ('qa45_boss','qa45_finance','qa45_supervisor','qa45_warehouse') and s.id in ('QA-S01','QA-S02','QA-S03');
insert ignore into user_store_scope(tenant_id, user_id, store_id)
select @tenant_id, u.id, u.store_id from auth_user u where u.tenant_id=@tenant_id and u.username in ('qa45_employee','qa45_manager_s01','qa45_manager_s02');

insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
select @tenant_id, u.id, d.domain_code, 'OWN_STORE', null, current_timestamp
from auth_user u join (
  select 'STORE' as domain_code union all select 'FINANCE' union all select 'SALARY'
  union all select 'WAREHOUSE' union all select 'INSPECTION' union all select 'EXAM'
) d
where u.tenant_id=@tenant_id and u.username in ('qa45_manager_s01','qa45_manager_s02')
on duplicate key update scope_type=values(scope_type), scope_value_json=null, updated_at=current_timestamp;
insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
select @tenant_id, u.id, d.domain_code, 'STORE_LIST', json_array('QA-S01','QA-S02','QA-S03'), current_timestamp
from auth_user u join (select 'STORE' as domain_code union all select 'WAREHOUSE' union all select 'INSPECTION' union all select 'EXAM' union all select 'PLATFORM') d
where u.tenant_id=@tenant_id and u.username='qa45_supervisor'
on duplicate key update scope_type=values(scope_type), scope_value_json=values(scope_value_json), updated_at=current_timestamp;
insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
select @tenant_id, u.id, d.domain_code, 'STORE_LIST', json_array('QA-S01','QA-S02','QA-S03'), current_timestamp
from auth_user u join (select 'STORE' as domain_code union all select 'FINANCE' union all select 'SALARY') d
where u.tenant_id=@tenant_id and u.username='qa45_finance'
on duplicate key update scope_type=values(scope_type), scope_value_json=values(scope_value_json), updated_at=current_timestamp;
insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
select @tenant_id, u.id, 'WAREHOUSE', 'WAREHOUSE_LIST', json_array(cast(@central_warehouse_id as char)), current_timestamp
from auth_user u where u.tenant_id=@tenant_id and u.username='qa45_warehouse'
on duplicate key update scope_type=values(scope_type), scope_value_json=values(scope_value_json), updated_at=current_timestamp;
insert into user_data_scope(tenant_id, user_id, domain_code, scope_type, scope_value_json, created_at)
select @tenant_id, u.id, 'EXAM', 'SELF', null, current_timestamp
from auth_user u where u.tenant_id=@tenant_id and u.username='qa45_employee'
on duplicate key update scope_type=values(scope_type), scope_value_json=null, updated_at=current_timestamp;

insert into employee(id, tenant_id, store_id, store_name, brand_name, name, role, position, employment_type, base_salary, status, hire_date, auth_user_id, remark, data_source, created_at)
select concat('QA45-EMP-', s.id, '-01'), @tenant_id, s.id, s.name, 'QA45 Brand', concat('QA45 Employee ', s.id, ' 01'), 'EMPLOYEE', 'QA', 'FULL_TIME', 4200.00, 'ACTIVE', '2026-01-01', null, 'QA45 fixture', 'QA45', current_timestamp from store_branch s where s.tenant_id=@tenant_id and s.id in ('QA-S01','QA-S02','QA-S03')
on duplicate key update status='ACTIVE', updated_at=current_timestamp;
insert into employee(id, tenant_id, store_id, store_name, brand_name, name, role, position, employment_type, base_salary, status, hire_date, auth_user_id, remark, data_source, created_at)
select concat('QA45-EMP-', s.id, '-02'), @tenant_id, s.id, s.name, 'QA45 Brand', concat('QA45 Employee ', s.id, ' 02'), 'EMPLOYEE', 'QA', 'FULL_TIME', 4300.00, 'ACTIVE', '2026-01-01', null, 'QA45 fixture', 'QA45', current_timestamp from store_branch s where s.tenant_id=@tenant_id and s.id in ('QA-S01','QA-S02','QA-S03')
on duplicate key update status='ACTIVE', updated_at=current_timestamp;

insert into warehouse_item_category(tenant_id, name, parent_id, sort_order, enabled, created_at)
values (@tenant_id, 'QA45 Fruit', null, 10, 1, current_timestamp), (@tenant_id, 'QA45 Packaging', null, 20, 1, current_timestamp)
on duplicate key update enabled=values(enabled), updated_at=current_timestamp;
set @fruit_category_id := (select id from warehouse_item_category where tenant_id=@tenant_id and name='QA45 Fruit' and parent_id is null limit 1);
set @packaging_category_id := (select id from warehouse_item_category where tenant_id=@tenant_id and name='QA45 Packaging' and parent_id is null limit 1);
insert into warehouse_item(tenant_id, code, name, category, category_id, unit, purchase_unit, stock_unit, ingredient_unit, unit_price, cups_per_unit, daily_usage_estimate, min_stock_days, max_stock_days, min_stock_quantity, alert_enabled, sort_order, active, created_at)
values
 (@tenant_id, 'QA45-MAT-FRUIT', 'QA45 Fruit', 'QA45 Fruit', @fruit_category_id, 'kg', 'kg', 'kg', 'kg', 12.50, 1, 2, 2, 10, 5, 1, 10, 1, current_timestamp),
 (@tenant_id, 'QA45-MAT-MILK', 'QA45 Milk', 'QA45 Packaging', @packaging_category_id, 'box', 'box', 'box', 'box', 8.80, 1, 2, 2, 10, 5, 1, 20, 1, current_timestamp),
 (@tenant_id, 'QA45-MAT-CUP', 'QA45 Cup', 'QA45 Packaging', @packaging_category_id, 'piece', 'piece', 'piece', 'piece', 0.35, 1, 20, 2, 10, 20, 1, 30, 1, current_timestamp),
 (@tenant_id, 'QA45-MAT-STOP', 'QA45 Disabled Item', 'QA45 Packaging', @packaging_category_id, 'piece', 'piece', 'piece', 'piece', 1.00, 1, 1, 2, 10, 1, 1, 40, 0, current_timestamp)
on duplicate key update active=values(active), unit_price=values(unit_price), category_id=values(category_id), updated_at=current_timestamp;
insert into warehouse_inventory(tenant_id, warehouse_id, item_id, on_hand_quantity, reserved_quantity, in_transit_quantity, unit_cost, min_stock_quantity, alert_enabled, version, created_at)
select @tenant_id, @central_warehouse_id, i.id, 100.00, 0, 0, i.unit_price, 5, 1, 0, current_timestamp from warehouse_item i where i.tenant_id=@tenant_id and i.code in ('QA45-MAT-FRUIT','QA45-MAT-MILK','QA45-MAT-CUP')
on duplicate key update on_hand_quantity=values(on_hand_quantity), updated_at=current_timestamp;
insert into store_inventory(tenant_id, store_id, item_id, quantity, unit, updated_at)
select @tenant_id, s.id, i.id, 20.00, i.unit, current_timestamp from store_branch s join warehouse_item i on i.tenant_id=@tenant_id and i.code in ('QA45-MAT-FRUIT','QA45-MAT-MILK','QA45-MAT-CUP') where s.tenant_id=@tenant_id and s.id in ('QA-S01','QA-S02','QA-S03')
  and not exists (select 1 from store_inventory si where si.tenant_id=@tenant_id and si.store_id=s.id and si.item_id=i.id);

insert into loss_item_config(tenant_id, item_code, item_name, category, warehouse_category_id, unit, unit_price, source_sheet, active, created_at)
values
 (@tenant_id, 'QA45-LOSS-FRUIT', 'QA45 Loss Fruit', 'QA45 Fruit', @fruit_category_id, 'gram', 0.0125, 'QA45', 1, current_timestamp),
 (@tenant_id, 'QA45-LOSS-MILK', 'QA45 Loss Milk', 'QA45 Packaging', @packaging_category_id, 'box', 8.8000, 'QA45', 1, current_timestamp),
 (@tenant_id, 'QA45-LOSS-CUP', 'QA45 Loss Cup', 'QA45 Packaging', @packaging_category_id, 'piece', 0.3500, 'QA45', 1, current_timestamp),
 (@tenant_id, 'QA45-LOSS-STOP', 'QA45 Loss Disabled', 'QA45 Packaging', @packaging_category_id, 'piece', 1.0000, 'QA45', 0, current_timestamp)
on duplicate key update item_name=values(item_name), unit_price=values(unit_price), active=values(active), warehouse_category_id=values(warehouse_category_id), updated_at=current_timestamp;

insert into profit_entry(tenant_id, store_id, month, sales, refund, discount, material, packaging, loss, cost_other, rent, labor, utility, property, commission, promo, repair, equip, exp_other, note, created_at)
select @tenant_id, s.id, m.month, 100000, 1000, 500, 26000, 3000, 500, 1200, 10000, 18000, 2500, 800, 3000, 1000, 200, 300, 400, concat('QA45 fixture ', m.month), current_timestamp
from store_branch s join (select '2026-06' as month union all select '2026-07') m
where s.tenant_id=@tenant_id and s.id in ('QA-S01','QA-S02')
  and not exists (select 1 from profit_entry p where p.tenant_id=@tenant_id and p.store_id=s.id and p.month=m.month and p.note=concat('QA45 fixture ',m.month));

set @manager_s01 := (select id from auth_user where tenant_id=@tenant_id and username='qa45_manager_s01' limit 1);
set @supervisor := (select id from auth_user where tenant_id=@tenant_id and username='qa45_supervisor' limit 1);
insert into salary_record(id, tenant_id, store_id, month, employee_id, employee_name, position, gross, net_pay, normal_hours, ot_hours, work_hours, vacation_left, base, social, post, meal, full_attendance, commission, overtime, seniority, late_night, subsidy, performance, deduct_uniform, return_uniform, status, version, created_at)
select concat('QA45-SAL-', e.id, '-', m.month), @tenant_id, e.store_id, m.month, e.id, e.name, e.position, 4500, 4300, 174, 0, 174, 0, 4200, 100, 0, 100, 100, 0, 0, 0, 0, 0, 100, 0, 0, 'DRAFT', 0, current_timestamp
from employee e join (select '2026-06' as month union all select '2026-07') m
where e.tenant_id=@tenant_id and e.id in ('QA45-EMP-QA-S01-01','QA45-EMP-QA-S02-01')
on duplicate key update gross=values(gross), net_pay=values(net_pay), updated_at=current_timestamp;

insert into inspection_standard_version(tenant_id, version, title, full_score, pass_score, effective_date, status, created_at)
values (@tenant_id, 'QA45-V1', 'QA45 Inspection Standard', 200, 180, '2026-01-01', 'ACTIVE', current_timestamp)
on duplicate key update status='ACTIVE', updated_at=current_timestamp;
set @inspection_standard_id := (select id from inspection_standard_version where tenant_id=@tenant_id and version='QA45-V1' limit 1);
insert into inspection_standard_item(tenant_id, standard_version_id, dimension, code, title, suggested_score, category_score, red_line, enabled, sort_order, risk_level, created_at)
select @tenant_id, @inspection_standard_id, 'QA45', 'QA45-INS-01', 'QA45 inspection item', 10, 10, 0, 1, 1, 'NORMAL', current_timestamp
where not exists (select 1 from inspection_standard_item where tenant_id=@tenant_id and standard_version_id=@inspection_standard_id and code='QA45-INS-01');
insert into inspection_record(id, tenant_id, store_id, inspection_date, inspector, brand, full_score, pass_score, score, passed, deductions_json, redlines_json, photos_json, note, standard_version_id, standard_version, result_code, test_marker, created_at)
values ('QA45-INS-S01', @tenant_id, 'QA-S01', current_date, 'QA45 SUPERVISOR', 'QA45 Brand', 200, 180, 150, 0, '[]', '[]', '[]', 'QA45 inspection fixture', @inspection_standard_id, 'QA45-V1', 'RECTIFICATION_REQUIRED', 'QA45', current_timestamp)
on duplicate key update score=values(score), test_marker='QA45', updated_at=current_timestamp;
insert into inspection_rectification(id, tenant_id, inspection_record_id, store_id, status, manager_note, submitted_by, submitted_by_name, submitted_at, version, created_at, updated_at)
values ('QA45-RECT-S01', @tenant_id, 'QA45-INS-S01', 'QA-S01', 'SUBMITTED', 'QA45 rectification pending review', @manager_s01, 'QA45 STORE_MANAGER S01', current_timestamp, 0, current_timestamp, current_timestamp)
on duplicate key update status='SUBMITTED', updated_at=current_timestamp;

insert into training_course(tenant_id, course_code, title, category, description, duration_minutes, required_role_scope, enabled, sort_order, created_at)
values (@tenant_id, 'QA45-COURSE-01', 'QA45 Store Basics', 'QA45', 'QA45 training fixture', 30, 'EMPLOYEE,STORE_MANAGER', 1, 1, current_timestamp)
on duplicate key update enabled=1, updated_at=current_timestamp;
insert into training_exam_paper(tenant_id, paper_code, paper_name, role_scope, pass_score, enabled, created_at)
values (@tenant_id, 'QA45-PAPER-01', 'QA45 Basic Paper', 'EMPLOYEE,STORE_MANAGER', 80, 1, current_timestamp)
on duplicate key update enabled=1, updated_at=current_timestamp;
set @paper_id := (select id from training_exam_paper where tenant_id=@tenant_id and paper_code='QA45-PAPER-01' limit 1);
insert into training_exam_question(tenant_id, paper_id, question_type, question_text, options_json, standard_answer, score, sort_order, enabled, created_at)
values (@tenant_id, @paper_id, 'SINGLE_CHOICE', 'QA45 question: choose A', '["A","B"]', 'A', 100, 1, 1, current_timestamp)
on duplicate key update question_text=values(question_text), enabled=1, updated_at=current_timestamp;
insert into training_exam_campaign(tenant_id, paper_id, title, status, start_at, due_at, target_roles, created_by, published_by, published_at, created_at)
select @tenant_id, @paper_id, 'QA45 Campaign', 'PUBLISHED', current_timestamp, date_add(current_timestamp, interval 30 day), 'EMPLOYEE', @supervisor, @supervisor, current_timestamp, current_timestamp
where not exists (select 1 from training_exam_campaign where tenant_id=@tenant_id and title='QA45 Campaign');
set @campaign_id := (select id from training_exam_campaign where tenant_id=@tenant_id and title='QA45 Campaign' limit 1);
set @employee_user := (select id from auth_user where tenant_id=@tenant_id and username='qa45_employee' limit 1);
insert into training_exam_assignment(tenant_id, campaign_id, user_id, examinee_name, examinee_role, store_id, store_name, status, assigned_at, due_at, created_at)
select @tenant_id, @campaign_id, @employee_user, 'QA45 EMPLOYEE', 'EMPLOYEE', 'QA-S01', 'QA45 Store S01', 'ASSIGNED', current_timestamp, date_add(current_timestamp, interval 30 day), current_timestamp
where not exists (select 1 from training_exam_assignment where tenant_id=@tenant_id and campaign_id=@campaign_id and user_id=@employee_user);

insert into expense_claim(id, tenant_id, store_id, month, expense_date, amount, category, reason, status, image_url, submitted_by, created_at)
values ('QA45-EXP-S01', @tenant_id, 'QA-S01', date_format(current_date, '%Y-%m'), current_date, 12.50, 'QA45', 'QA45 expense fixture', 'SUBMITTED', 'qa45://fixture/expense.png', @manager_s01, current_timestamp)
on duplicate key update amount=values(amount), status='SUBMITTED', updated_at=current_timestamp;
insert into expense_supplement(tenant_id, expense_id, note, submitted_by, submitted_by_name, submitted_at)
select @tenant_id, 'QA45-EXP-S01', 'QA45 receipt image fixture', @manager_s01, 'QA45 STORE_MANAGER S01', current_timestamp
where not exists (select 1 from expense_supplement where tenant_id=@tenant_id and expense_id='QA45-EXP-S01' and note='QA45 receipt image fixture');
set @expense_supplement_id := (select id from expense_supplement where tenant_id=@tenant_id and expense_id='QA45-EXP-S01' and note='QA45 receipt image fixture' order by id desc limit 1);
insert into expense_supplement_attachment(tenant_id, supplement_id, expense_id, file_name, content_type, file_size, storage_key, uploaded_by, uploaded_at)
select @tenant_id, @expense_supplement_id, 'QA45-EXP-S01', 'QA45-expense.png', 'image/png', __EXPENSE_IMAGE_SIZE__, '__EXPENSE_STORAGE_KEY__', @manager_s01, current_timestamp
where not exists (select 1 from expense_supplement_attachment where tenant_id=@tenant_id and expense_id='QA45-EXP-S01' and storage_key='__EXPENSE_STORAGE_KEY__');
insert into daily_loss_report(id, tenant_id, store_id, loss_date, status, submitted_by, submitted_at, created_at)
values ('QA45-DLR-S01', @tenant_id, 'QA-S01', current_date, 'SUBMITTED', @manager_s01, current_timestamp, current_timestamp)
on duplicate key update status='SUBMITTED', updated_at=current_timestamp;
set @loss_config_id := (select id from loss_item_config where tenant_id=@tenant_id and item_code='QA45-LOSS-FRUIT' limit 1);
insert into daily_loss_record(id, report_id, tenant_id, store_id, loss_date, item_config_id, item_code, item_name, stock_unit, loss_quantity, unit_price_snapshot, amount_snapshot, loss_reason, status, submitted_by, submitted_at)
values ('QA45-DL-S01-01', 'QA45-DLR-S01', @tenant_id, 'QA-S01', current_date, @loss_config_id, 'QA45-LOSS-FRUIT', 'QA45 Loss Fruit', 'gram', 100, 0.0125, 1.25, 'QA45 fixture', 'SUBMITTED', @manager_s01, current_timestamp)
on duplicate key update report_id=values(report_id), amount_snapshot=values(amount_snapshot), status='SUBMITTED', updated_at=current_timestamp;
insert into warehouse_attachment(tenant_id, store_id, business_type, business_id, file_name, content_type, file_size, storage_path, content, uploaded_by, uploaded_at)
select @tenant_id, 'QA-S01', 'DAILY_LOSS', 'QA45-DLR-S01', 'QA45-loss.png', 'image/png', octet_length(unhex('89504E470D0A1A0A0000000D49484452000000010000000108060000001F15C4890000000D49444154789C6360F8CFF00000050101FF89993D1D0000000049454E44AE426082')), 'qa45://fixture/QA45-loss.png', unhex('89504E470D0A1A0A0000000D49484452000000010000000108060000001F15C4890000000D49444154789C6360F8CFF00000050101FF89993D1D0000000049454E44AE426082'), @manager_s01, current_timestamp
where not exists (select 1 from warehouse_attachment where tenant_id=@tenant_id and business_type='DAILY_LOSS' and business_id='QA45-DLR-S01' and file_name='QA45-loss.png');

insert into business_todo(id, tenant_id, rule_code, source_module, source_record_id, source_key, occurrence_no, title, summary, assignee_role, review_role, store_id, month, priority, status, condition_active, metadata_json, created_at)
values
 ('QA45-TODO-DL', @tenant_id, 'QA45_DAILY_LOSS', 'DAILY_LOSS', 'QA45-DLR-S01', 'QA45-DLR-S01', 1, 'QA45 Daily Loss Review', 'QA45 fixture', 'SUPERVISOR', null, 'QA-S01', date_format(current_date, '%Y-%m'), 2, 'PENDING', 1, '{"batch":"QA45"}', current_timestamp),
 ('QA45-TODO-EXP', @tenant_id, 'QA45_EXPENSE', 'EXPENSE', 'QA45-EXP-S01', 'QA45-EXP-S01', 1, 'QA45 Expense Review', 'QA45 fixture', 'FINANCE', null, 'QA-S01', date_format(current_date, '%Y-%m'), 2, 'PENDING', 1, '{"batch":"QA45"}', current_timestamp),
 ('QA45-TODO-INS', @tenant_id, 'QA45_INSPECTION', 'INSPECTION', 'QA45-RECT-S01', 'QA45-RECT-S01', 1, 'QA45 Inspection Review', 'QA45 fixture', 'SUPERVISOR', null, 'QA-S01', date_format(current_date, '%Y-%m'), 2, 'PENDING', 1, '{"batch":"QA45"}', current_timestamp),
 ('QA45-TODO-WH', @tenant_id, 'QA45_WAREHOUSE', 'WAREHOUSE', 'QA45-WH-CENTRAL', 'QA45-WH-CENTRAL', 1, 'QA45 Warehouse Check', 'QA45 fixture', 'WAREHOUSE', null, null, date_format(current_date, '%Y-%m'), 1, 'PENDING', 1, '{"batch":"QA45"}', current_timestamp)
on duplicate key update status='PENDING', condition_active=1, updated_at=current_timestamp;

insert into qmai_platform_config(tenant_id, brand, open_id, grant_code, open_key, base_url, version, shops, console_account, console_password, console_token, updated_at, created_at)
select @tenant_id, 'QA45', '', '', '', '', 'MOCK', '[]', '', '', null, current_timestamp, current_timestamp
where not exists (select 1 from qmai_platform_config where tenant_id=@tenant_id and brand='QA45');

insert into operation_log(tenant_id, operator_id, operator_name, action, target_type, target_id, reason, created_at)
select @tenant_id, @manager_s01, 'QA45 Fixture', 'qa45_fixture_initialize', 'qa45_fixture_batch', '__BATCH_ID__', 'QA45 idempotent fixture initialization', current_timestamp
where not exists (select 1 from operation_log where tenant_id=@tenant_id and action='qa45_fixture_initialize' and target_id='__BATCH_ID__');
commit;
'@
  $sql = $sql.Replace('__PASSWORD_HASH__', $passwordHash).Replace('__BATCH_ID__', $batchId).Replace('__EXPENSE_STORAGE_KEY__', $expenseStorageKey).Replace('__EXPENSE_IMAGE_SIZE__', [string]$qaImageBytes.Length)
  Invoke-Qa45Sql $sql | Out-Null
} finally {
  $accountPassword = $null
  $passwordHash = $null
}

$verificationSql = @'
select concat('tenant=', (select count(*) from tenant where name='QA45 Tenant'));
select concat('stores=', (select count(*) from store_branch where tenant_id=(select id from tenant where name='QA45 Tenant') and id in ('QA-S01','QA-S02','QA-S03')));
select concat('accounts=', (select count(*) from auth_user where tenant_id=(select id from tenant where name='QA45 Tenant') and username like 'qa45_%'));
select concat('employees=', (select count(*) from employee where tenant_id=(select id from tenant where name='QA45 Tenant') and id like 'QA45-%'));
select concat('items=', (select count(*) from warehouse_item where tenant_id=(select id from tenant where name='QA45 Tenant') and code like 'QA45-%'));
select concat('lossItems=', (select count(*) from loss_item_config where tenant_id=(select id from tenant where name='QA45 Tenant') and item_code like 'QA45-%'));
select concat('expenseImages=', (select count(*) from expense_supplement_attachment where tenant_id=(select id from tenant where name='QA45 Tenant') and expense_id='QA45-EXP-S01'));
select concat('todos=', (select count(*) from business_todo where tenant_id=(select id from tenant where name='QA45 Tenant') and id like 'QA45-%'));
select concat('audit=', (select count(*) from operation_log where tenant_id=(select id from tenant where name='QA45 Tenant') and action='qa45_fixture_initialize'));
select concat('managerScopeMismatch=', (select count(*) from user_store_scope us join auth_user u on u.id=us.user_id where u.tenant_id=(select id from tenant where name='QA45 Tenant') and u.username='qa45_manager_s01' and us.store_id <> 'QA-S01'));
'@
$pairs = @{}
foreach ($line in (Invoke-Qa45Sql $verificationSql) -split "`r?`n") {
  if ($line -match '^(?<key>[^=]+)=(?<value>.*)$') { $pairs[$Matches.key] = [int64]$Matches.value }
}
if ($pairs.tenant -ne 1 -or $pairs.stores -ne 3 -or $pairs.accounts -ne 8 -or $pairs.employees -lt 6 -or $pairs.items -ne 4 -or $pairs.lossItems -ne 4 -or $pairs.expenseImages -ne 1 -or $pairs.todos -ne 4 -or $pairs.audit -ne 1 -or $pairs.managerScopeMismatch -ne 0) {
  throw 'QA45 fixture verification failed; no automatic cleanup was performed.'
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$result.status = 'PASS'
$result.created = [ordered]@{ tenant = 1; brand = 1; stores = 3; accounts = 8; employees = [int]$pairs.employees; warehouseItems = 4; lossItems = 4; todos = 4 }
$result.reused = [ordered]@{ idempotent = $true }
$result.verification = $pairs
[IO.File]::WriteAllText($reportPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "QA45 fixture initialization passed. Evidence: $reportPath"
