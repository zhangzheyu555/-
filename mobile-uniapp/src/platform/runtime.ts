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

export function apiBaseUrl(): string {
  return String(import.meta.env.VITE_API_BASE_URL || '').trim().replace(/\/$/, '')
}

export function resolveApiUrl(path: string): string {
  if (/^https?:\/\//i.test(path)) return path
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const baseUrl = apiBaseUrl()
  if (baseUrl) return `${baseUrl}${normalizedPath}`

  // #ifdef H5
  return normalizedPath
  // #endif

  // 小程序端没有接口地址时不能伪造本地业务数据或把请求转到生产库。
  // 面向业务人员只说明可执行的下一步，不暴露构建变量或技术状态码。
  throw new Error('测试服务尚未接入，当前只能预览页面。请联系管理员配置测试环境后再登录。')
}
