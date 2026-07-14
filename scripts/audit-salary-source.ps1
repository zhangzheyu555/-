[CmdletBinding()]
param(
  [string]$SourceHost = '127.0.0.1',
  [int]$SourcePort = 3309,
  [Parameter(Mandatory)]
  [string]$SourceUser
)

$ErrorActionPreference = 'Stop'
$mysql = 'D:\Program Files\bin\mysql.exe'
$report = Join-Path $env:TEMP 'ai-profit-salary-source-audit.txt'
if (-not (Test-Path -LiteralPath $mysql)) { throw 'MySQL 5.5 client was not found.' }
if ($SourceHost -ne '127.0.0.1' -or $SourcePort -ne 3309) { throw '旧数据只允许从本机3309只读恢复实例审计。' }
if ($SourceUser -match '^(?i:root)(?:@|$)') { throw '3309审计必须使用只读账号，禁止root。' }
$legacy = Get-CimInstance Win32_Service -Filter "Name='MySQL'" -ErrorAction SilentlyContinue
if (-not $legacy -or $legacy.State -ne 'Stopped' -or (Get-NetTCPConnection -State Listen -LocalPort 3306 -ErrorAction SilentlyContinue)) {
  throw '3306原服务必须保持停止且无监听。'
}

function ConvertFrom-SecureValue([Security.SecureString]$Value) {
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$securePassword = Read-Host "$SourceUser MySQL 5.5 source password" -AsSecureString
$password = ConvertFrom-SecureValue $securePassword
$env:MYSQL_PWD = $password
try {
  $sql = @'
select concat('server_version\t', version());
select concat('database\t', schema_name)
from information_schema.schemata
where schema_name not in ('information_schema','mysql','performance_schema','sys')
order by schema_name;
select concat('candidate\t', c.table_schema, '\t', c.table_name, '\t',
              group_concat(c.column_name order by c.ordinal_position separator ','), '\t',
              coalesce(t.table_rows, 0))
from information_schema.columns c
join information_schema.tables t
  on t.table_schema = c.table_schema and t.table_name = c.table_name
where c.table_schema not in ('information_schema','mysql','performance_schema','sys')
group by c.table_schema, c.table_name, t.table_rows
having sum(lower(c.column_name) in ('name','employee_name','emp_name','staff_name','username','real_name','姓名')) > 0
   and sum(lower(c.column_name) in ('store_id','shop_id','branch_id','store','shop','门店')) > 0
order by c.table_schema, c.table_name;
select concat('employee_tenant\t', tenant_id, '\t', count(*))
from store_profit.employee group by tenant_id order by tenant_id;
select concat('employee_status\t', tenant_id, '\t', coalesce(status, '<NULL>'), '\t', count(*))
from store_profit.employee group by tenant_id, status order by tenant_id, status;
select concat('employee_store\t', e.tenant_id, '\t', e.store_id, '\t', count(*), '\t',
              case when s.id is null then 'INVALID' else 'VALID' end)
from store_profit.employee e
left join store_profit.store_branch s on s.tenant_id = e.tenant_id and s.id = e.store_id
group by e.tenant_id, e.store_id, case when s.id is null then 'INVALID' else 'VALID' end
order by e.tenant_id, e.store_id;
select concat('support_table\t', table_name, '\t', table_rows)
from information_schema.tables
where table_schema = 'store_profit'
  and table_name in ('brand','store_branch','employee_salary_profile','salary_policy','employee_month_attendance')
order by table_name;
select concat('support_columns\t', table_name, '\t', group_concat(column_name order by ordinal_position separator ','))
from information_schema.columns
where table_schema = 'store_profit'
  and table_name in ('brand','store_branch','employee_salary_profile','salary_policy','employee_month_attendance')
group by table_name order by table_name;
'@
  $output = $sql | & $mysql --protocol=TCP --host=$SourceHost --port=$SourcePort --user=$SourceUser --batch --skip-column-names
  if ($LASTEXITCODE -ne 0) { throw 'MySQL 5.5 source audit failed.' }
  Set-Content -LiteralPath $report -Value $output -Encoding utf8
  Write-Host "Read-only source audit complete: $report" -ForegroundColor Green
} finally {
  Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
  $password = $null
  $securePassword.Dispose()
}
