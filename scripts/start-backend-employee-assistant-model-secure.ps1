[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

throw '此 MODEL 单助手入口已安全弃用，不能单独启动 18081。请先运行 configure-assistant-runtime-config.ps1 保存两套隔离配置，再使用 start-backend-assistants-secure.ps1。'
