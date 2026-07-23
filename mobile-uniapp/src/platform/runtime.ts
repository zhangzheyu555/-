export type RuntimePlatform = 'h5' | 'mp-weixin' | 'app'

export function runtimePlatform(): RuntimePlatform {
  let platform: RuntimePlatform = 'h5'
  // #ifdef MP-WEIXIN
  platform = 'mp-weixin'
  // #endif
  // #ifdef APP-PLUS
  platform = 'app'
  // #endif
  return platform
}

/** Only exposes WeChat sign-in when the matching backend endpoints are deployed. */
export function weChatAuthEnabled(): boolean {
  return runtimePlatform() === 'mp-weixin'
    && String(import.meta.env.VITE_WECHAT_AUTH_ENABLED || '').trim().toLowerCase() === 'true'
}

export function apiBaseUrl(): string {
  const configured = normalizeApiBaseUrl(import.meta.env.VITE_API_BASE_URL)
  // 开发者工具运行开发包时可访问电脑的 127.0.0.1；真机调试中的手机不能。
  // 因此仅在开发构建且真实设备上切换至本机未跟踪配置的 HTTPS 后端。
  // #ifdef MP-WEIXIN
  if (import.meta.env.DEV && !isWeChatDevtools()) {
    return normalizeApiBaseUrl(import.meta.env.VITE_MOBILE_DEVICE_API_BASE_URL) || configured
  }
  // #endif
  return configured
}

function normalizeApiBaseUrl(value: string | undefined): string {
  return String(value || '').trim().replace(/\/$/, '')
}

function isWeChatDevtools(): boolean {
  // #ifdef MP-WEIXIN
  try { return uni.getSystemInfoSync().platform === 'devtools' }
  catch { return false }
  // #endif
  return false
}

export function resolveApiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const baseUrl = apiBaseUrl()
  if (baseUrl) {
    if (baseUrl.endsWith('/api') && normalizedPath.startsWith('/api/')) {
      return `${baseUrl}${normalizedPath.slice('/api'.length)}`
    }
    return `${baseUrl}${normalizedPath}`
  }

  // #ifdef H5
  return normalizedPath
  // #endif

  // 小程序端没有接口地址时不能伪造本地业务数据或把请求转到生产库。
  // 面向业务人员只说明可执行的下一步，不暴露构建变量或技术状态码。
  throw new Error('测试服务尚未接入，当前只能预览页面。请联系管理员配置测试环境后再登录。')
}
