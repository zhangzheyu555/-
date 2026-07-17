Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-CompleteQmaiRuntimeConfig {
  [CmdletBinding()]
  param([Parameter(Mandatory)]$Configuration)

  $qmai = Get-AssistantConfigProperty $Configuration 'qmai'
  $openId = [string](Get-AssistantConfigProperty $qmai 'openId')
  $grantCode = [string](Get-AssistantConfigProperty $qmai 'grantCode')
  $openKey = [string](Get-AssistantConfigProperty $qmai 'openKey')
  if ([string]::IsNullOrWhiteSpace($openId) -or
      [string]::IsNullOrWhiteSpace($grantCode) -or
      [string]::IsNullOrWhiteSpace($openKey)) {
    throw 'Qmai DPAPI runtime configuration is incomplete.'
  }
  return $qmai
}

function New-QmaiProviderEnvironment {
  [CmdletBinding()]
  param([Parameter(Mandatory)]$Configuration)

  $qmai = Assert-CompleteQmaiRuntimeConfig $Configuration
  return @{
    QMAI_OPEN_ID = [string](Get-AssistantConfigProperty $qmai 'openId')
    QMAI_GRANT_CODE = [string](Get-AssistantConfigProperty $qmai 'grantCode')
    QMAI_OPEN_KEY = [string](Get-AssistantConfigProperty $qmai 'openKey')
    QMAI_BASE_URL = 'https://openapi.qmai.cn'
    QMAI_TIMEOUT = '20s'
    QMAI_MAX_RETRIES = '4'
    QMAI_CONCURRENCY = '4'
  }
}

function Assert-QmaiProviderEnvironment {
  [CmdletBinding()]
  param([Parameter(Mandatory)][hashtable]$Environment)

  foreach ($name in @('QMAI_OPEN_ID', 'QMAI_GRANT_CODE', 'QMAI_OPEN_KEY')) {
    if (-not $Environment.ContainsKey($name) -or
        [string]::IsNullOrWhiteSpace([string]$Environment[$name])) {
      throw 'The Qmai launch environment is incomplete.'
    }
  }
  if ([string]$Environment.QMAI_BASE_URL -cne 'https://openapi.qmai.cn') {
    throw 'The Qmai launch environment must use the official HTTPS gateway.'
  }
}

function Clear-QmaiRuntimeConfigPlaintext {
  [CmdletBinding()]
  param([AllowNull()]$Configuration)

  $qmai = Get-AssistantConfigProperty $Configuration 'qmai'
  foreach ($name in @('openId', 'grantCode', 'openKey')) {
    if ($null -ne $qmai -and $null -ne $qmai.PSObject.Properties[$name]) {
      $qmai.$name = $null
    }
  }
}
