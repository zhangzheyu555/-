[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
throw 'This legacy recovery helper is retired because it could read credentials from a 3306 application process. Use the audited 3309 read-only recovery instance and mysql8-logical-migration.ps1.'
