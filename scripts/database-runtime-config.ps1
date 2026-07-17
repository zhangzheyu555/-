Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-CompleteDatabaseRuntimeConfig {
  [CmdletBinding()]
  param([Parameter(Mandatory)]$Configuration)

  $database = Get-AssistantConfigProperty $Configuration 'database'
  $hostName = [string](Get-AssistantConfigProperty $database 'host')
  $portText = [string](Get-AssistantConfigProperty $database 'port')
  $databaseName = [string](Get-AssistantConfigProperty $database 'name')
  $username = [string](Get-AssistantConfigProperty $database 'username')
  $password = [string](Get-AssistantConfigProperty $database 'password')
  $sslMode = ([string](Get-AssistantConfigProperty $database 'sslMode')).Trim().ToUpperInvariant()

  $port = 0
  if ($hostName -notin @('127.0.0.1', 'localhost') -or
      -not [int]::TryParse($portText, [ref]$port) -or $port -ne 3307 -or
      $databaseName -notmatch '^[A-Za-z0-9_]+$' -or
      $username -notmatch '^[A-Za-z0-9_]+$' -or $username -match '^(?i:root)$' -or
      [string]::IsNullOrWhiteSpace($password) -or
      $sslMode -notin @('DISABLED', 'REQUIRED', 'VERIFY_CA', 'VERIFY_IDENTITY')) {
    throw 'The database DPAPI runtime configuration is incomplete or violates the scoped-account policy.'
  }

  return [pscustomobject]@{
    Host = $hostName
    Port = $port
    Name = $databaseName
    Username = $username
    Password = $password
    SslMode = $sslMode
  }
}

function Clear-DatabaseRuntimeConfigPlaintext {
  [CmdletBinding()]
  param([AllowNull()]$Configuration)

  $database = Get-AssistantConfigProperty $Configuration 'database'
  if ($null -ne $database -and $null -ne $database.PSObject.Properties['password']) {
    $database.password = $null
  }
}
