import { defineStore } from 'pinia'
import { loginApi, logoutApi, type SessionUser } from '../api/auth'
import { allowProtectedRequests, cancelProtectedRequests } from '../api/http'

const TOKEN_KEY = 'ai_profit_vue_token'
const USER_KEY = 'ai_profit_vue_user'
const LOGIN_TIMEOUT_MS = 15000

export function getStoredToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function clearStoredAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

function readStoredUser(): SessionUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    return normalizeStoredUser(parsed)
  } catch {
    clearStoredAuth()
    return null
  }
}

function normalizeStoredUser(value: unknown): SessionUser | null {
  if (!value || typeof value !== 'object') {
    clearStoredAuth()
    return null
  }
  const user = value as Partial<SessionUser>
  const role = normalizeRole(user.role)
  if (!role) {
    clearStoredAuth()
    return null
  }
  return {
    id: Number(user.id || 0),
    tenantId: Number(user.tenantId || 1),
    tenantName: typeof user.tenantName === 'string' ? user.tenantName : '',
    displayName: typeof user.displayName === 'string' ? user.displayName : '',
    role,
    roleLabel: roleName(role),
    storeScope: Array.isArray(user.storeScope) ? user.storeScope.filter((item): item is string => typeof item === 'string') : [],
  }
}

function normalizeRole(role?: string) {
  const value = String(role || '').trim().toUpperCase()
  const map: Record<string, string> = {
    ADMIN: 'ADMIN',
    BOSS: 'BOSS',
    OWNER: 'BOSS',
    FINANCE: 'FINANCE',
    WAREHOUSE: 'WAREHOUSE',
    STORE_MANAGER: 'STORE_MANAGER',
    STORE: 'STORE_MANAGER',
    MANAGER: 'STORE_MANAGER',
    SUPERVISOR: 'SUPERVISOR',
    OPERATIONS: 'OPERATIONS',
    OPS: 'OPERATIONS',
    EMPLOYEE: 'EMPLOYEE',
  }
  return map[value] || ''
}

const storedUser = readStoredUser()

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: storedUser ? getStoredToken() : '',
    user: storedUser,
    loading: false,
    loggingOut: false,
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token && state.user),
    role: (state) => state.user?.role || '',
    roleLabel: (state) => roleName(state.user?.role) || state.user?.roleLabel,
    storeScope: (state) => state.user?.storeScope || [],
    scopeText(): string {
      if (this.storeScope.includes('all')) return '全部门店'
      if (!this.storeScope.length) return '未配置门店范围'
      return this.storeScope.length === 1 ? `门店 ${this.storeScope[0]}` : `${this.storeScope.length} 家门店`
    },
  },
  actions: {
    async login(username: string, password: string) {
      this.loading = true
      try {
        const data = await withLoginTimeout(loginApi(username, password))
        this.token = data.token
        const normalizedUser = normalizeStoredUser(data.user)
        if (!normalizedUser) {
          throw new Error('登录返回的账号信息不完整，请重新登录')
        }
        allowProtectedRequests()
        this.user = normalizedUser
        localStorage.setItem(TOKEN_KEY, data.token)
        localStorage.setItem(USER_KEY, JSON.stringify(normalizedUser))
      } finally {
        this.loading = false
      }
    },
    async logout() {
      if (this.loggingOut) return
      this.loggingOut = true
      cancelProtectedRequests()
      try {
        await logoutApi()
      } catch (error) {
        console.warn('[Auth] 注销接口暂时不可用，已清理本地登录状态', {
          message: error instanceof Error ? error.message : '注销请求失败',
        })
      } finally {
        this.token = ''
        this.user = null
        clearStoredAuth()
        this.loggingOut = false
      }
    },
  },
})

function withLoginTimeout<T>(promise: Promise<T>) {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      reject(new Error('登录请求超时，请检查后端服务是否正常'))
    }, LOGIN_TIMEOUT_MS)

    promise
      .then(resolve)
      .catch(reject)
      .finally(() => window.clearTimeout(timer))
  })
}

export function roleName(role?: string) {
  const map: Record<string, string> = {
    ADMIN: '系统管理员',
    BOSS: '老板',
    OWNER: '老板',
    FINANCE: '财务',
    WAREHOUSE: '仓库管理员',
    STORE_MANAGER: '店长',
    SUPERVISOR: '督导',
    OPERATIONS: '运营',
    OPS: '运营',
    EMPLOYEE: '员工',
  }
  return map[role || ''] || role || '未登录'
}
