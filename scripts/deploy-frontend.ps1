[CmdletBinding()]
param(
  [string]$Server = '',
  [string]$User = 'deploy',
  [string]$RemoteRoot = '/opt/store-profit',
  [string]$PublicUrl = '',
  [switch]$All,
  [string[]]$Files = @(),
  [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

throw 'The legacy runtime-static deploy entrypoint is retired. It cannot deploy as root or overwrite static files. Build a candidate with scripts/build-release-candidate.ps1, deploy it with scripts/deploy-vue3-frontend.ps1 -CandidateDirectory <candidate-directory>, and use scripts/rollback-vue3-frontend.ps1 for rollback.'
