[CmdletBinding()]
param(
  [string]$ApiBase = 'http://127.0.0.1:18081',
  [Parameter(Mandatory)]
  [string]$BackupPath,
  [string]$Username = 'boss',
  [Security.SecureString]$Password,
  [switch]$Apply,
  [string]$ReportPath = (Join-Path $env:LOCALAPPDATA 'AI-Profit-OS\salary-import\legacy-salary-import-report.json')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Get-PlainText([Security.SecureString]$Value) {
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Invoke-Utf8Api([string]$Path, [string]$Method = 'GET', $Body = $null, [switch]$Anonymous) {
  $client = [System.Net.Http.HttpClient]::new()
  $client.Timeout = [TimeSpan]::FromSeconds(20)
  try {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::$Method, "$($script:apiBase)$Path")
    if (-not $Anonymous) { [void]$request.Headers.TryAddWithoutValidation('Authorization', ('Bearer ' + $script:token)) }
    if ($null -ne $Body) {
      $json = $Body | ConvertTo-Json -Depth 8 -Compress
      $request.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $payload = $text | ConvertFrom-Json
    if (-not $response.IsSuccessStatusCode -or -not $payload.success) { throw "API $Path failed: $($payload.message)" }
    return $payload.data
  } finally {
    $client.Dispose()
  }
}

function Add-ApiItems([System.Collections.Generic.List[object]]$Target, $Value) {
  if ($null -eq $Value) { return }
  if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
    foreach ($item in $Value) { [void]$Target.Add($item) }
    return
  }
  [void]$Target.Add($Value)
}

function Set-RestrictedDirectory([string]$Path) {
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
  if (Test-Path -LiteralPath $Path) {
    $existingAcl = Get-Acl -LiteralPath $Path
    $allowed = $false
    foreach ($entry in $existingAcl.Access) {
      try { $entrySid = $entry.IdentityReference.Translate([Security.Principal.SecurityIdentifier]) } catch { continue }
      if ($entrySid -eq $sid -and $entry.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and (($entry.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -ne 0)) {
        $allowed = $true
      }
    }
    if (-not $allowed) { throw 'The salary import report directory is not writable by the current user.' }
    return
  }
  New-Item -ItemType Directory -Force -Path $Path | Out-Null
  $acl = [Security.AccessControl.DirectorySecurity]::new()
  $acl.SetOwner($sid)
  $acl.SetAccessRuleProtection($true, $false)
  $rule = [Security.AccessControl.FileSystemAccessRule]::new(
      $sid,
      [Security.AccessControl.FileSystemRights]::FullControl,
      [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
      [Security.AccessControl.PropagationFlags]::None,
      [Security.AccessControl.AccessControlType]::Allow
  )
  [void]$acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Path -AclObject $acl
}

function Write-ImportReport($Report, [string]$Path) {
  $Report.updatedAt = (Get-Date).ToString('o')
  $Report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

if (-not (Test-Path -LiteralPath $BackupPath -PathType Leaf)) { throw "Salary backup file was not found: $BackupPath" }
if (-not $Password) { $Password = Read-Host "$Username password" -AsSecureString }
if (Test-Path -LiteralPath $ReportPath) { Remove-Item -LiteralPath $ReportPath -Force }

$script:apiBase = $ApiBase.TrimEnd('/')
$plainPassword = Get-PlainText $Password
try {
  $login = Invoke-Utf8Api '/api/auth/login' 'POST' @{ username = $Username; password = $plainPassword } -Anonymous
  if (-not $login.token) { throw 'Login did not return a session token.' }
  $script:token = [string]($login.token)
  if ($login.user.role -ne 'BOSS') { throw 'Only the BOSS account can import salary history.' }

  $backup = Get-Content -LiteralPath $BackupPath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($backup.salary -isnot [string] -or [string]::IsNullOrWhiteSpace($backup.salary)) { throw 'The backup does not contain readable salary data.' }
  $decodedSalary = ConvertFrom-Json -InputObject $backup.salary
  $legacyRows = [System.Collections.Generic.List[object]]::new()
  foreach ($legacyRow in $decodedSalary) { [void]$legacyRows.Add($legacyRow) }
  if ($legacyRows.Count -eq 0) { throw 'The backup salary data is empty.' }
  $expectedMonths = [ordered]@{
    '2026-01' = 99; '2026-03' = 114; '2026-04' = 103
    '2026-05' = 129; '2026-06' = 8; '2026-07' = 8
  }
  $sourceGross = [decimal](($legacyRows | Measure-Object gross -Sum).Sum)
  if ($legacyRows.Count -ne 461 -or $sourceGross -ne [decimal]'1695169.21') {
    throw 'The selected backup does not match the approved 461-row salary source.'
  }
  $actualMonths = @{}
  foreach ($group in ($legacyRows | Group-Object month)) { $actualMonths[$group.Name] = $group.Count }
  foreach ($month in $expectedMonths.Keys) {
    if ($actualMonths[$month] -ne $expectedMonths[$month]) {
      throw "The approved salary count for $month does not match."
    }
  }
  if ($actualMonths.Count -ne $expectedMonths.Count) {
    throw 'The selected backup contains an unexpected salary month.'
  }

  $stores = [System.Collections.Generic.List[object]]::new()
  $employees = [System.Collections.Generic.List[object]]::new()
  $existingRows = [System.Collections.Generic.List[object]]::new()
  Add-ApiItems $stores (Invoke-Utf8Api '/api/stores')
  Add-ApiItems $employees (Invoke-Utf8Api '/api/employees')
  Add-ApiItems $existingRows (Invoke-Utf8Api '/api/salaries?allMonths=true')
  $storesById = @{}
  foreach ($store in $stores) { $storesById[[string]($store.id)] = $store }
  $employeesByStoreAndName = @{}
  foreach ($employee in $employees) {
    $key = ([string]($employee.storeId)) + [char]124 + ([string]($employee.name))
    if (-not $employeesByStoreAndName.ContainsKey($key)) { $employeesByStoreAndName[$key] = @() }
    $employeesByStoreAndName[$key] += $employee
  }
  $existingByEmployeeMonth = @{}
  foreach ($row in $existingRows) {
    if ($row.employeeId) {
      $existingKey = ([string]($row.employeeId)) + [char]124 + ([string]($row.month))
      $existingByEmployeeMonth[$existingKey] = $row
    }
  }

  $prepared = @()
  $issues = @()
  $resumeRows = 0
  foreach ($row in $legacyRows) {
    $storeId = [string]($row.sid)
    if (-not $storesById.ContainsKey($storeId)) {
      $issues += [pscustomobject]@{ type = 'STORE_NOT_FOUND'; legacyId = $row.id; storeId = $storeId; name = $row.name; month = $row.month }
      continue
    }
    $employeeKey = $storeId + [char]124 + ([string]($row.name))
    $employeeMatches = @($employeesByStoreAndName[$employeeKey])
    if ($employeeMatches.Count -ne 1) {
      $issues += [pscustomobject]@{ type = if ($employeeMatches.Count -eq 0) { 'EMPLOYEE_NOT_FOUND' } else { 'EMPLOYEE_AMBIGUOUS' }; legacyId = $row.id; storeId = $storeId; name = $row.name; month = $row.month; matchCount = $employeeMatches.Count }
      continue
    }
    $employee = $employeeMatches[0]
    $salaryKey = ([string]($employee.id)) + [char]124 + ([string]($row.month))
    $existing = $existingByEmployeeMonth[$salaryKey]
    $recordId = 'LEGACY-' + ([string]($row.id))
    if ($existing -and [string]($existing.id) -ne $recordId) {
      $issues += [pscustomobject]@{ type = 'SALARY_ALREADY_EXISTS'; legacyId = $row.id; storeId = $storeId; name = $row.name; month = $row.month; existingId = $existing.id }
      continue
    }
    if ($existing) { $resumeRows++ }
    $prepared += [pscustomobject]@{
      legacyId = [string]($row.id)
      recordId = $recordId
      payload = [ordered]@{
        storeId = $storeId; month = [string]($row.month); employeeId = [string]($employee.id)
        employeeName = [string]($row.name); position = [string]($row.position); attendance = [string]($row.attendance)
        gross = [decimal]($row.gross); normalHours = [decimal]($row.normalHours); otHours = [decimal]($row.otHours)
        workHours = [decimal]($row.workHours); vacationLeft = [decimal]($row.vacationLeft); vacationNote = [string]($row.vacationNote)
        base = [decimal]($row.base); social = [decimal]($row.social); post = [decimal]($row.post); meal = [decimal]($row.meal)
        fullAttendance = [decimal]($row.full); commission = [decimal]($row.commission); overtime = [decimal]($row.overtime)
        seniority = [decimal]($row.seniority); lateNight = [decimal]($row.latenight); subsidy = [decimal]($row.subsidy)
        performance = [decimal]($row.performance); deductUniform = [decimal]($row.deductUniform); returnUniform = [decimal]($row.returnUniform)
      }
    }
  }

  $report = [ordered]@{
    createdAt = (Get-Date).ToString('o'); target = $script:apiBase; mode = if ($Apply) { 'APPLY' } else { 'PREVIEW' }
    sourceRows = $legacyRows.Count; readyRows = $prepared.Count; resumedRows = $resumeRows; issueCount = $issues.Count
    sourceMonths = @($legacyRows | Group-Object month | ForEach-Object { [pscustomobject]@{ month = $_.Name; count = $_.Count; gross = [decimal](($_.Group | Measure-Object gross -Sum).Sum) } })
    issues = $issues
  }
  $directory = Split-Path -Parent $ReportPath
  Set-RestrictedDirectory $directory

  if ($issues.Count -gt 0) {
    Write-ImportReport $report $ReportPath
    throw "Preflight failed: $($issues.Count) rows could not be matched safely. Nothing was written. Report: $ReportPath"
  }

  if (-not $Apply) {
    Write-ImportReport $report $ReportPath
    Write-Host "Preflight passed: $($prepared.Count) salary rows can be imported. Nothing was written. Report: $ReportPath" -ForegroundColor Green
    exit 0
  }

  $imported = @()
  $report.completedRows = 0
  $report.updatedLegacyRows = $resumeRows
  $report.insertedLegacyRows = $prepared.Count - $resumeRows
  $report.skippedRows = 0
  foreach ($item in $prepared) {
    try {
      $created = Invoke-Utf8Api "/api/salaries/history-import/$([uri]::EscapeDataString($item.recordId))" 'PUT' $item.payload
      $imported += $created
      $report.completedRows = $imported.Count
      $report.lastCompletedLegacyId = $item.legacyId
      $report.importedGross = [decimal](($imported | Measure-Object gross -Sum).Sum)
      Write-ImportReport $report $ReportPath
    } catch {
      $report.failedLegacyId = $item.legacyId
      $report.failure = $_.Exception.Message
      Write-ImportReport $report $ReportPath
      throw
    }
  }
  $report.importedRows = $imported.Count
  $report.importedGross = [decimal](($imported | Measure-Object gross -Sum).Sum)
  Write-ImportReport $report $ReportPath
  Write-Host "Import completed: $($imported.Count) salary rows were written to the new salary table. Report: $ReportPath" -ForegroundColor Green
} finally {
  if ($script:token) {
    try {
      [void](Invoke-Utf8Api '/api/auth/logout' 'POST')
    } catch {}
  }
  $plainPassword = $null
  if ($Password) { $Password.Dispose() }
}
