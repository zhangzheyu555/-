[CmdletBinding()]
param(
  [string]$MySqlHost = '127.0.0.1',
  [ValidateRange(1, 65535)]
  [int]$MySqlPort = 3307,
  [string]$AdminUser = 'root'
)

$ErrorActionPreference = 'Stop'

if ($MySqlHost -notin @('127.0.0.1', 'localhost')) {
  throw 'This setup script only permits the local TEST MySQL instance.'
}
if ($MySqlPort -ne 3307) {
  throw 'This setup script only permits the isolated MySQL 8 TEST port 3307.'
}

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) {
  throw 'MySQL 8 client was not found at the expected installation path.'
}

function ConvertFrom-SecureValue {
  param([Parameter(Mandatory)][Security.SecureString]$Value)
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  }
  finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
  }
}

function New-RandomPassword {
  param([int]$Length = 24)
  $sets = @(
    'ABCDEFGHJKLMNPQRSTUVWXYZ',
    'abcdefghijkmnopqrstuvwxyz',
    '23456789',
    '!@#$%*-_=+'
  )
  $all = $sets -join ''
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $pick = {
      param([string]$Set)
      $bytes = New-Object byte[] 1
      do { $rng.GetBytes($bytes) } while ($bytes[0] -ge (256 - (256 % $Set.Length)))
      $Set[$bytes[0] % $Set.Length]
    }
    $characters = New-Object Collections.Generic.List[char]
    foreach ($set in $sets) { $characters.Add((& $pick $set)) }
    for ($index = $characters.Count; $index -lt $Length; $index++) {
      $characters.Add((& $pick $all))
    }
    for ($index = $characters.Count - 1; $index -gt 0; $index--) {
      $bytes = New-Object byte[] 1
      do { $rng.GetBytes($bytes) } while ($bytes[0] -ge (256 - (256 % ($index + 1))))
      $swapIndex = $bytes[0] % ($index + 1)
      $value = $characters[$index]
      $characters[$index] = $characters[$swapIndex]
      $characters[$swapIndex] = $value
    }
    -join $characters
  }
  finally {
    $rng.Dispose()
  }
}

function Escape-MySqlLiteral {
  param([Parameter(Mandatory)][string]$Value)
  $Value.Replace('\', '\\').Replace("'", "''")
}

$service = Get-Service -Name 'MySQL80Test' -ErrorAction Stop
if ($service.Status -ne 'Running') {
  throw 'MySQL80Test is not running. Start the isolated TEST service first.'
}

$adminPasswordSecure = Read-Host 'MySQL 8 root password' -AsSecureString
$adminPassword = ConvertFrom-SecureValue $adminPasswordSecure
$appPassword = New-RandomPassword
Set-Clipboard -Value $appPassword
Write-Host 'A random password for ai_profit_test was copied to the clipboard.' -ForegroundColor Green
Write-Host 'Save it in your local password manager. It will not be written to disk or logs.'
[void](Read-Host 'Press Enter after saving the application password')

$escapedAppPassword = Escape-MySqlLiteral $appPassword
$sql = @"
CREATE DATABASE IF NOT EXISTS ai_profit_test_empty CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ai_profit_test_upgrade CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ai_profit_test_runtime CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'ai_profit_test'@'127.0.0.1' IDENTIFIED BY '$escapedAppPassword';
ALTER USER 'ai_profit_test'@'127.0.0.1' IDENTIFIED BY '$escapedAppPassword';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ai_profit_test'@'127.0.0.1';
GRANT ALL PRIVILEGES ON ai_profit_test_empty.* TO 'ai_profit_test'@'127.0.0.1';
GRANT ALL PRIVILEGES ON ai_profit_test_upgrade.* TO 'ai_profit_test'@'127.0.0.1';
GRANT ALL PRIVILEGES ON ai_profit_test_runtime.* TO 'ai_profit_test'@'127.0.0.1';
FLUSH PRIVILEGES;
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME IN ('ai_profit_test_empty', 'ai_profit_test_upgrade', 'ai_profit_test_runtime')
ORDER BY SCHEMA_NAME;
"@

$startInfo = New-Object Diagnostics.ProcessStartInfo
$startInfo.FileName = $mysql
$startInfo.Arguments = "--protocol=TCP --host=$MySqlHost --port=$MySqlPort --user=$AdminUser --batch --skip-column-names"
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$startInfo.CreateNoWindow = $true
$startInfo.EnvironmentVariables['MYSQL_PWD'] = $adminPassword

$process = New-Object Diagnostics.Process
$process.StartInfo = $startInfo
try {
  [void]$process.Start()
  $process.StandardInput.WriteLine($sql)
  $process.StandardInput.Close()
  $output = $process.StandardOutput.ReadToEnd()
  $errorOutput = $process.StandardError.ReadToEnd()
  $process.WaitForExit()
  if ($process.ExitCode -ne 0) {
    throw ('MySQL initialization failed. ' + $errorOutput.Trim())
  }
  Write-Host 'TEST databases and the scoped application user were created successfully.' -ForegroundColor Green
  Write-Host $output.Trim()
}
finally {
  if (-not $process.HasExited) { $process.Kill() }
  $process.Dispose()
  $sql = $null
  $escapedAppPassword = $null
  $appPassword = $null
  $adminPassword = $null
  $adminPasswordSecure.Dispose()
}
