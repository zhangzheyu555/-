[CmdletBinding()]
param(
  [string]$Server = '',
  [string]$User = 'deploy',
  [string]$RemoteRoot = '/opt/store-profit',
  [string]$BackupName = '',
  [string]$PublicUrl = '',
  [switch]$List,
  [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

throw 'The legacy runtime-static rollback entrypoint is retired. It cannot overwrite static files. Use scripts/rollback-vue3-frontend.ps1 -Server <server> -User <non-root-deploy-user> -ReleaseId <candidate-release-id>.'
