# Shared, non-interactive helpers for the two assistant deployment scripts.
# The encrypted payload is protected by Windows DPAPI for the current Windows user.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-AssistantRuntimeDirectoryHasNoReparsePoints {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string]$Directory)

  $fullDirectory = [IO.Path]::GetFullPath($Directory)
  $root = [IO.Path]::GetPathRoot($fullDirectory)
  if ([string]::IsNullOrWhiteSpace($root)) {
    throw '助手运行配置目录必须是可验证的绝对路径。'
  }
  $current = $root
  $relative = $fullDirectory.Substring($root.Length).TrimStart('\')
  $segments = @($relative -split '[\\/]' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  foreach ($segment in $segments) {
    $current = Join-Path $current $segment
    if (-not (Test-Path -LiteralPath $current)) { continue }
    $item = Get-Item -LiteralPath $current -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
      throw '助手加密运行配置目录或其已有祖先不得是 junction、符号链接或其他重解析点。'
    }
  }
}

function Get-AssistantRuntimeConfigDirectory {
  [CmdletBinding()]
  param([AllowNull()][string]$RuntimeDirectory)

  $directory = $RuntimeDirectory
  if ([string]::IsNullOrWhiteSpace($directory)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
      throw '无法定位当前 Windows 用户的本地运行目录。'
    }
    $directory = Join-Path $localAppData 'AI-Profit-OS\runtime-config'
  }

  $resolvedDirectory = [IO.Path]::GetFullPath($directory).TrimEnd('\')
  $repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
  if ($resolvedDirectory.Equals($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
      $resolvedDirectory.StartsWith($repositoryRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw '助手加密运行配置必须保存在仓库外的当前 Windows 用户运行目录。'
  }
  Assert-AssistantRuntimeDirectoryHasNoReparsePoints $resolvedDirectory
  return $resolvedDirectory
}

function Get-AssistantRuntimeConfigPath {
  [CmdletBinding()]
  param([AllowNull()][string]$RuntimeDirectory)

  return Join-Path (Get-AssistantRuntimeConfigDirectory $RuntimeDirectory) 'assistant-providers.v1.dpapi'
}

function Set-AssistantRuntimeConfigDirectoryProtection {
  [CmdletBinding()]
  param([Parameter(Mandatory)][string]$Directory)

  [void](New-Item -ItemType Directory -Path $Directory -Force)
  # Check again after creation to prevent a pre-existing/replaced final directory from redirecting writes.
  Assert-AssistantRuntimeDirectoryHasNoReparsePoints $Directory
  $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
  if ($null -eq $identity) {
    throw '无法确认当前 Windows 用户，拒绝保存助手配置。'
  }

  $acl = New-Object Security.AccessControl.DirectorySecurity
  $acl.SetAccessRuleProtection($true, $false)
  $rule = New-Object Security.AccessControl.FileSystemAccessRule(
    $identity,
    [Security.AccessControl.FileSystemRights]::FullControl,
    [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
    [Security.AccessControl.PropagationFlags]::None,
    [Security.AccessControl.AccessControlType]::Allow
  )
  [void]$acl.AddAccessRule($rule)
  Set-Acl -LiteralPath $Directory -AclObject $acl
}

function Get-AssistantConfigProperty {
  [CmdletBinding()]
  param(
    [AllowNull()]$Object,
    [Parameter(Mandatory)][string]$Name
  )

  if ($null -eq $Object) { return $null }
  $property = $Object.PSObject.Properties[$Name]
  if ($null -eq $property) { return $null }
  return $property.Value
}

function Test-AssistantSafeHttpBaseUrl {
  [CmdletBinding()]
  param([AllowNull()][string]$Value)

  if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
  try {
    $uri = [Uri]$Value
    if (-not ($uri.IsAbsoluteUri -and $uri.Host -and $uri.Scheme -in @('http', 'https')) -or
        $uri.UserInfo -or -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
      return $false
    }
    # A base address may contain /v1, but must not already point at an operation endpoint.
    return $uri.AbsolutePath -notmatch '(?i)/(?:models|chat/completions|api/v1/health)/?$'
  } catch {
    return $false
  }
}

function Convert-AssistantSecureStringToPlainText {
  [CmdletBinding()]
  param([Parameter(Mandatory)][Security.SecureString]$SecureString)

  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    if ($pointer -ne [IntPtr]::Zero) {
      [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
  }
}

function Assert-CompleteAssistantRuntimeConfig {
  [CmdletBinding()]
  param([Parameter(Mandatory)]$Configuration)

  $business = Get-AssistantConfigProperty $Configuration 'businessAssistant'
  $businessProvider = [string](Get-AssistantConfigProperty $business 'provider')
  $businessUrl = [string](Get-AssistantConfigProperty $business 'baseUrl')
  $businessModel = [string](Get-AssistantConfigProperty $business 'model')
  $businessApiKey = [string](Get-AssistantConfigProperty $business 'apiKey')
  if ($businessProvider -cne 'DEEPSEEK' -or
      -not (Test-AssistantSafeHttpBaseUrl $businessUrl) -or
      [string]::IsNullOrWhiteSpace($businessModel) -or
      [string]::IsNullOrWhiteSpace($businessApiKey)) {
    throw '门店经营助手 DPAPI 配置不完整或格式无效。'
  }

  $employee = Get-AssistantConfigProperty $Configuration 'employeeAssistant'
  $employeeMode = ([string](Get-AssistantConfigProperty $employee 'provider')).Trim().ToUpperInvariant()
  if ($employeeMode -notin @('REMOTE', 'MODEL')) {
    throw '员工服务助手必须显式选择 REMOTE 或 MODEL。'
  }

  $remoteUrl = [string](Get-AssistantConfigProperty $employee 'remoteUrl')
  $remoteToken = [string](Get-AssistantConfigProperty $employee 'apiToken')
  $modelUrl = [string](Get-AssistantConfigProperty $employee 'modelUrl')
  $modelApiKey = [string](Get-AssistantConfigProperty $employee 'modelApiKey')
  $modelName = [string](Get-AssistantConfigProperty $employee 'modelName')
  if ($employeeMode -eq 'REMOTE') {
    if (-not (Test-AssistantSafeHttpBaseUrl $remoteUrl) -or
        [string]::IsNullOrWhiteSpace($remoteToken) -or
        -not [string]::IsNullOrWhiteSpace($modelUrl) -or
        -not [string]::IsNullOrWhiteSpace($modelApiKey) -or
        -not [string]::IsNullOrWhiteSpace($modelName)) {
      throw '员工服务助手 REMOTE 配置必须完整且不得混入 MODEL 变量。'
    }
  } else {
    if (-not (Test-AssistantSafeHttpBaseUrl $modelUrl) -or
        [string]::IsNullOrWhiteSpace($modelApiKey) -or
        [string]::IsNullOrWhiteSpace($modelName) -or
        -not [string]::IsNullOrWhiteSpace($remoteUrl) -or
        -not [string]::IsNullOrWhiteSpace($remoteToken)) {
      throw '员工服务助手 MODEL 配置必须完整且不得混入 REMOTE 变量。'
    }
  }

  return [pscustomobject]@{
    EmployeeMode = $employeeMode
    BusinessConfigured = $true
    EmployeeConfigured = $true
  }
}

function Save-AssistantRuntimeConfig {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory)]$Configuration,
    [AllowNull()][string]$RuntimeDirectory
  )

  [void](Assert-CompleteAssistantRuntimeConfig $Configuration)
  $directory = Get-AssistantRuntimeConfigDirectory $RuntimeDirectory
  Set-AssistantRuntimeConfigDirectoryProtection $directory
  $path = Get-AssistantRuntimeConfigPath $directory
  $temporaryPath = Join-Path $directory ('.assistant-providers-' + [Guid]::NewGuid().ToString('N') + '.tmp')
  $plainJson = $null
  $secureJson = $null
  try {
    $plainJson = $Configuration | ConvertTo-Json -Depth 8 -Compress
    $secureJson = ConvertTo-SecureString -String $plainJson -AsPlainText -Force
    # No -Key/-SecureKey is supplied: Windows binds this ciphertext to the current user via DPAPI.
    $cipherText = ConvertFrom-SecureString -SecureString $secureJson
    [IO.File]::WriteAllText($temporaryPath, $cipherText, [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $path -PathType Leaf) {
      [IO.File]::Replace($temporaryPath, $path, $null)
    } else {
      Move-Item -LiteralPath $temporaryPath -Destination $path -Force
    }
  } finally {
    if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
      Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    }
    $plainJson = $null
    if ($secureJson) { $secureJson.Dispose() }
  }
}

function Read-AssistantRuntimeConfig {
  [CmdletBinding()]
  param([AllowNull()][string]$RuntimeDirectory)

  $path = Get-AssistantRuntimeConfigPath $RuntimeDirectory
  if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
    throw '未找到当前 Windows 用户的双助手 DPAPI 运行配置。'
  }

  $cipherText = $null
  $secureJson = $null
  $pointer = [IntPtr]::Zero
  $plainJson = $null
  try {
    $cipherText = [IO.File]::ReadAllText($path, [Text.UTF8Encoding]::new($false)).Trim()
    if ([string]::IsNullOrWhiteSpace($cipherText)) {
      throw '双助手 DPAPI 运行配置为空。'
    }
    $secureJson = ConvertTo-SecureString -String $cipherText
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureJson)
    $plainJson = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $configuration = $plainJson | ConvertFrom-Json -ErrorAction Stop
    [void](Assert-CompleteAssistantRuntimeConfig $configuration)
    return $configuration
  } catch {
    # Do not surface the original exception: Windows/DPAPI messages can include local details.
    throw '无法读取当前 Windows 用户的双助手 DPAPI 运行配置；请由同一用户重新安全配置。'
  } finally {
    if ($pointer -ne [IntPtr]::Zero) {
      [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    $plainJson = $null
    $cipherText = $null
    if ($secureJson) { $secureJson.Dispose() }
  }
}

function New-AssistantProviderEnvironment {
  [CmdletBinding()]
  param([Parameter(Mandatory)]$Configuration)

  $summary = Assert-CompleteAssistantRuntimeConfig $Configuration
  $business = Get-AssistantConfigProperty $Configuration 'businessAssistant'
  $employee = Get-AssistantConfigProperty $Configuration 'employeeAssistant'
  $environment = @{
    DEEPSEEK_ENABLED = 'true'
    DEEPSEEK_API_KEY = [string](Get-AssistantConfigProperty $business 'apiKey')
    DEEPSEEK_BASE_URL = ([string](Get-AssistantConfigProperty $business 'baseUrl')).TrimEnd('/')
    DEEPSEEK_MODEL = [string](Get-AssistantConfigProperty $business 'model')
    DEEPSEEK_CONNECT_TIMEOUT = '5s'
    DEEPSEEK_TIMEOUT_SECONDS = '45'
    EMPLOYEE_ASSISTANT_PROVIDER = $summary.EmployeeMode
    EMPLOYEE_ASSISTANT_CONNECT_TIMEOUT = '5s'
    EMPLOYEE_ASSISTANT_TIMEOUT = '45s'
  }
  if ($summary.EmployeeMode -eq 'REMOTE') {
    $environment.EMPLOYEE_ASSISTANT_URL = ([string](Get-AssistantConfigProperty $employee 'remoteUrl')).TrimEnd('/')
    $environment.EMPLOYEE_ASSISTANT_API_TOKEN = [string](Get-AssistantConfigProperty $employee 'apiToken')
  } else {
    $environment.EMPLOYEE_ASSISTANT_MODEL_URL = ([string](Get-AssistantConfigProperty $employee 'modelUrl')).TrimEnd('/')
    $environment.EMPLOYEE_ASSISTANT_MODEL_NAME = [string](Get-AssistantConfigProperty $employee 'modelName')
    $environment.EMPLOYEE_ASSISTANT_MODEL_API_KEY = [string](Get-AssistantConfigProperty $employee 'modelApiKey')
  }
  return $environment
}

function Clear-AssistantRuntimeConfigPlaintext {
  [CmdletBinding()]
  param([AllowNull()]$Configuration)

  $business = Get-AssistantConfigProperty $Configuration 'businessAssistant'
  $employee = Get-AssistantConfigProperty $Configuration 'employeeAssistant'
  foreach ($item in @(
      @{ Object = $business; Name = 'apiKey' },
      @{ Object = $employee; Name = 'apiToken' },
      @{ Object = $employee; Name = 'modelApiKey' }
  )) {
    if ($null -ne $item.Object -and $null -ne $item.Object.PSObject.Properties[$item.Name]) {
      $item.Object.($item.Name) = $null
    }
  }
}
