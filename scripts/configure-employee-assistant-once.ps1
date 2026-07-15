[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

throw '此旧入口已安全弃用：它不再创建账号、不再请求 root 凭据、不再授予数据库权限，也不能启动服务。请使用 configure-assistant-runtime-config.ps1 配置两套助手，并由既有非 root 应用账号运行 start-backend-assistants-secure.ps1。'
