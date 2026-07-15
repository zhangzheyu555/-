[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

throw '此 V43 运行账号初始化入口已安全弃用：它不再请求 root、不再创建或修改账号、不再授予数据库权限，也不能启动 18081。请使用既有非 root 应用账号和 start-backend-assistants-secure.ps1。'
