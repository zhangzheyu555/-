[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
throw '该一次性3306迁移脚本已停用。请使用mysql8-logical-migration.ps1从3309只读恢复实例迁移到固定3307最终库。'
