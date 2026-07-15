[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

throw '此 V43 单助手时代的 18081 启动入口已安全弃用。当前库已超过 V43，且该入口不会从双助手 DPAPI 配置源注入 DEEPSEEK_* 与 EMPLOYEE_ASSISTANT_*。请使用 start-backend-assistants-secure.ps1。'
