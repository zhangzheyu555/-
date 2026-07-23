[CmdletBinding()]
param(
  [string]$ExcelPath = '',
  [string]$Sheet = '',
  [long]$TenantId = 1,
  [string]$OutSql = '',
  [switch]$DryRun,
  [switch]$Apply
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($DryRun -and $Apply) {
  throw 'Choose either -DryRun or -Apply, not both.'
}
if (-not $DryRun -and -not $Apply) {
  $DryRun = $true
}

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
if (-not $OutSql) {
  $OutSql = Join-Path $repoRoot 'output\loss-item-config-preview.sql'
}
$outDir = Split-Path -Parent $OutSql
if ($outDir) {
  New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}

if (-not $ExcelPath) {
  $defaultExcelDir = 'C:\Users\34706\Documents\xwechat_files\wxid_zgw781jjm8h522_0eed\msg\file\2026-07'
  $candidate = Get-ChildItem -LiteralPath $defaultExcelDir -Filter '*.xlsx' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*(1)(1).xlsx' } |
    Select-Object -First 1
  if (-not $candidate) {
    $candidate = Get-ChildItem -LiteralPath $defaultExcelDir -Filter '*.xlsx' -ErrorAction SilentlyContinue |
      Select-Object -First 1
  }
  if ($candidate) {
    $ExcelPath = $candidate.FullName
  }
}

if (-not $ExcelPath -or -not (Test-Path -LiteralPath $ExcelPath)) {
  throw "Excel file not found: $ExcelPath"
}

$generator = Join-Path $repoRoot 'scripts\ops\Import-LossItemConfigFromExcel.py'
if (-not (Test-Path -LiteralPath $generator)) {
  throw "Generator not found: $generator"
}

$python = $env:PYTHON
if (-not $python) {
  $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
  if ($pythonCommand) {
    $python = $pythonCommand.Source
  }
}
if (-not $python) {
  throw 'Python is required to parse the Excel file.'
}

$generatorArgs = @($generator, '--xlsx', $ExcelPath, '--tenant-id', $TenantId, '--out', $OutSql)
if ($Sheet) {
  $generatorArgs += @('--sheet', $Sheet)
}
& $python @generatorArgs
if ($LASTEXITCODE -ne 0) {
  throw 'Excel loss item config extraction failed.'
}

if ($DryRun) {
  Write-Host "Dry run complete. SQL written to: $OutSql"
  Write-Host 'No dated sample daily-loss rows were imported.'
  return
}

$appEnv = ''
if ($env:APP_ENV) {
  $appEnv = $env:APP_ENV.Trim().ToUpperInvariant()
}
if (@('LOCAL', 'STAGING') -notcontains $appEnv) {
  throw 'Applying loss item config is allowed only when APP_ENV is LOCAL or STAGING.'
}

$requiredEnv = @('MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USERNAME', 'MYSQL_PASSWORD')
foreach ($name in $requiredEnv) {
  if (-not [Environment]::GetEnvironmentVariable($name)) {
    throw "Missing required environment variable: $name"
  }
}

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if ($mysql) {
  $oldMysqlPwd = $env:MYSQL_PWD
  try {
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    Get-Content -Raw -LiteralPath $OutSql | & $mysql.Source `
      --host=$env:MYSQL_HOST `
      --port=$env:MYSQL_PORT `
      --database=$env:MYSQL_DATABASE `
      --user=$env:MYSQL_USERNAME `
      --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) {
      throw 'mysql client failed to apply loss item config SQL.'
    }
    & $mysql.Source `
      --host=$env:MYSQL_HOST `
      --port=$env:MYSQL_PORT `
      --database=$env:MYSQL_DATABASE `
      --user=$env:MYSQL_USERNAME `
      --default-character-set=utf8mb4 `
      --execute="select count(*) as active_count from loss_item_config where tenant_id = $TenantId and active = 1; select item_name, unit, pricing_unit, quantity_per_pricing_unit, unit_price from loss_item_config where tenant_id = $TenantId and active = 1 order by source_sheet, category, item_code, id limit 10;"
    if ($LASTEXITCODE -ne 0) {
      throw 'mysql client failed to verify loss item config rows.'
    }
    Write-Host 'Loss item config applied with mysql client.'
    return
  } finally {
    $env:MYSQL_PWD = $oldMysqlPwd
  }
}

$connector = Get-ChildItem "$env:USERPROFILE\.m2\repository" -Recurse -Filter 'mysql-connector-j-*.jar' -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1
if (-not $connector) {
  throw 'mysql client was not found, and mysql-connector-j was not found in the local Maven cache.'
}
$java = Get-Command java -ErrorAction SilentlyContinue
$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $java -or -not $javac) {
  throw 'Java and javac are required for the JDBC fallback.'
}

$tmpDir = Join-Path $repoRoot 'output\tmp\daily-loss-import'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$javaFile = Join-Path $tmpDir 'DailyLossSqlApply.java'
$javaSource = @'
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DailyLossSqlApply {
  public static void main(String[] args) throws Exception {
    String sql = Files.readString(Path.of(args[0])).trim();
    if (sql.endsWith(";")) {
      sql = sql.substring(0, sql.length() - 1);
    }
    long tenantId = Long.parseLong(args[1]);
    String host = System.getenv("MYSQL_HOST");
    String port = System.getenv("MYSQL_PORT");
    String database = System.getenv("MYSQL_DATABASE");
    String username = System.getenv("MYSQL_USERNAME");
    String password = System.getenv("MYSQL_PASSWORD");
    String url = "jdbc:mysql://" + host + ":" + port + "/" + database
        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    try (Connection connection = DriverManager.getConnection(url, username, password);
         Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
      try (ResultSet count = statement.executeQuery(
          "select count(*) from loss_item_config where tenant_id = " + tenantId + " and active = 1")) {
        if (count.next()) {
          System.out.println("active_count=" + count.getLong(1));
        }
      }
      try (ResultSet rows = statement.executeQuery(
          "select item_name, unit, pricing_unit, quantity_per_pricing_unit, unit_price from loss_item_config"
              + " where tenant_id = " + tenantId + " and active = 1"
              + " order by source_sheet, category, item_code, id limit 10")) {
        while (rows.next()) {
          System.out.println(rows.getString(1) + "\t" + rows.getString(2) + "\t" + rows.getString(3)
              + "\t" + rows.getBigDecimal(4) + "\t" + rows.getBigDecimal(5));
        }
      }
    }
  }
}
'@
[IO.File]::WriteAllText($javaFile, $javaSource, [Text.UTF8Encoding]::new($false))

& $javac.Source -encoding UTF-8 -cp $connector.FullName $javaFile
if ($LASTEXITCODE -ne 0) {
  throw 'Failed to compile JDBC loss item config importer.'
}
& $java.Source -cp "$tmpDir;$($connector.FullName)" DailyLossSqlApply $OutSql $TenantId
if ($LASTEXITCODE -ne 0) {
  throw 'JDBC fallback failed to apply loss item config SQL.'
}
Write-Host 'Loss item config applied with JDBC fallback.'
