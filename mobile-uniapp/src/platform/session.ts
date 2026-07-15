const SESSION_TOKEN_KEY = 'ai_profit_mobile_session_token'

export const AUTH_EXPIRED_EVENT = 'ai-profit-mobile:auth-expired'
let redirectingToLogin = false

export function readSessionToken(): string {
  const value = uni.getStorageSync(SESSION_TOKEN_KEY)
  return typeof value === 'string' ? value.trim() : ''
}

export function writeSessionToken(token: string): void {
  const normalized = token.trim()
  if (!normalized) {
    clearSessionToken()
    return
  }
  // 仅保存认证凭据。用户、门店、库存、巡检和财务等业务数据始终从后端获取。
  uni.setStorageSync(SESSION_TOKEN_KEY, normalized)
}

export function clearSessionToken(): void {
  uni.removeStorageSync(SESSION_TOKEN_KEY)
}

export function expireSession(): void {
  clearSessionToken()
  uni.$emit(AUTH_EXPIRED_EVENT)
  if (redirectingToLogin) return
  redirectingToLogin = true
  uni.showToast({ title: '登录已失效，请重新登录', icon: 'none' })
  setTimeout(() => {
    uni.reLaunch({ url: '/pages/login/index?reason=expired' })
    redirectingToLogin = false
  }, 300)
}
