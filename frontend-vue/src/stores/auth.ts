import { defineStore } from 'pinia'
import { currentSessionApi, loginApi, logoutApi, type SessionDataScope, type SessionUser } from '../api/auth'
import { allowProtectedRequests, cancelProtectedRequests } from '../api/http'
import { hasPermission as userHasPermission, normalizePermissionCode } from '../permissions/permissions'
import { normalizeRoleCode, roleName } from '../permissions/roles'

const TOKEN_KEY = 'ai_profit_vue_token'
const USER_KEY = 'ai_profit_vue_user'
const LOGIN_TIMEOUT_MS = 15000
export const AUTH_SESSION_INVALIDATED_EVENT = 'ai-profit:auth-session-invalidated'
let sessionInvalidationBound = false

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

function normalizeStoredUser(value: unknown, requireCompleteSession = false): SessionUser | null {
  if (!value || typeof value !== 'object') {
    clearStoredAuth()
    return null
  }
  const user = value as Partial<SessionUser>
  if (requireCompleteSession && (
    !Array.isArray(user.permissions)
    || !user.dataScopes
    || typeof user.dataScopes !== 'object'
    || Array.isArray(user.dataScopes)
    || typeof user.defaultWorkspace !== 'string'
    || !Number.isInteger(Number(user.permissionVersion))
    || Number(user.permissionVersion) < 1
  )) {
    return null
  }
  const role = normalizeRoleCode(user.role)
  if (!role) {
    clearStoredAuth()
    return null
  }
  const dataScopes = normalizeDataScopes(user.dataScopes)
  const storeScope = normalizeStoreScope(user.storeScope, dataScopes)
  const storeDataScope = normalizeSessionDataScope(user.dataScope) || dataScopes.STORE || { mode: 'NONE', storeIds: [], warehouseIds: [] }
  const permissionVersion = Number(user.permissionVersion || 1)
  return {
    id: Number(user.id || 0),
    tenantId: Number(user.tenantId || 1),
    tenantName: typeof user.tenantName === 'string' ? user.tenantName : '',
    displayName: typeof user.displayName === 'string' ? user.displayName : '',
    role,
    roleLabel: roleName(role),
    storeScope,
    permissions: Array.isArray(user.permissions)
      ? Array.from(new Set(user.permissions
          .filter((item): item is string => typeof item === 'string')
          .map(normalizePermissionCode)
          .filter(Boolean)))
      : [],
    dataScopes,
    dataScope: storeDataScope,
    boundStoreId: nullableText(user.boundStoreId),
    boundStoreName: nullableText(user.boundStoreName),
    brandId: nullablePositiveNumber(user.brandId),
    brandName: nullableText(user.brandName),
    defaultWorkspace: normalizeDefaultWorkspace(user.defaultWorkspace, role),
    permissionVersion: Number.isFinite(permissionVersion) ? Math.max(1, permissionVersion) : 1,
  }
}

function normalizeSessionDataScope(value: unknown): SessionDataScope | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const scope = value as Partial<SessionDataScope>
  return {
    mode: String(scope.mode || 'NONE').trim().toUpperCase(),
    storeIds: Array.isArray(scope.storeIds)
      ? scope.storeIds.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())).map((item) => item.trim())
      : [],
    warehouseIds: Array.isArray(scope.warehouseIds)
      ? scope.warehouseIds.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())).map((item) => item.trim())
      : [],
  }
}

function nullableText(value: unknown) {
  const text = typeof value === 'string' ? value.trim() : ''
  return text || null
}

function nullablePositiveNumber(value: unknown) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : null
}

function normalizeDataScopes(value: unknown): Record<string, SessionDataScope> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const result: Record<string, SessionDataScope> = {}
  for (const [domain, rawScope] of Object.entries(value)) {
    if (!rawScope || typeof rawScope !== 'object' || Array.isArray(rawScope)) continue
    const scope = rawScope as Partial<SessionDataScope>
    result[domain.trim().toUpperCase()] = {
      mode: String(scope.mode || 'NONE').trim().toUpperCase(),
      storeIds: Array.isArray(scope.storeIds)
        ? scope.storeIds.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())).map((item) => item.trim())
        : [],
      warehouseIds: Array.isArray(scope.warehouseIds)
        ? scope.warehouseIds.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())).map((item) => item.trim())
        : [],
    }
  }
  return result
}

function normalizeStoreScope(
  value: unknown,
  dataScopes: Record<string, SessionDataScope>,
) {
  const legacy = Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && Boolean(item.trim())).map((item) => item.trim())
    : []
  if (legacy.length) return Array.from(new Set(legacy))
  const storeScope = dataScopes.STORE
  if (!storeScope) return []
  return storeScope.mode === 'ALL' ? ['all'] : Array.from(new Set(storeScope.storeIds))
}

function normalizeDefaultWorkspace(value: unknown, role: string) {
  const workspace = typeof value === 'string' && value.startsWith('/') ? value.trim() : ''
  if (workspace) return workspace
  const fallbacks: Record<string, string> = {
    BOSS: '/boss',
    FINANCE: '/finance',
    WAREHOUSE: '/warehouse',
    STORE_MANAGER: '/store',
    SUPERVISOR: '/operations',
    EMPLOYEE: '/employee',
  }
  return fallbacks[role] || '/no-permission'
}

const storedUser = readStoredUser()

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: storedUser ? getStoredToken() : '',
    user: storedUser,
    loading: false,
    loggingOut: false,
    sessionValidated: !storedUser,
  }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token && state.user),
    role: (state) => state.user?.role || '',
    roleLabel: (state) => roleName(state.user?.role) || state.user?.roleLabel,
    storeScope: (state) => state.user?.storeScope || [],
    permissions: (state) => state.user?.permissions || [],
    dataScopes: (state) => state.user?.dataScopes || {},
    storeDataScope: (state) => state.user?.dataScope || { mode: 'NONE', storeIds: [], warehouseIds: [] },
    boundStoreId: (state) => state.user?.boundStoreId || '',
    boundStoreName: (state) => state.user?.boundStoreName || '',
    boundBrandId: (state) => state.user?.brandId || null,
    boundBrandName: (state) => state.user?.brandName || '',
    defaultWorkspace: (state) => state.user?.defaultWorkspace || '/no-permission',
    permissionVersion: (state) => state.user?.permissionVersion || 0,
    storeManagerHasStoreBinding: (state) => {
      if (state.user?.role !== 'STORE_MANAGER') return true
      const storeDataScope = state.user.dataScope || state.user.dataScopes?.STORE
      const storeIds = Array.from(new Set((storeDataScope?.storeIds || []).filter((storeId) => storeId && storeId !== 'all')))
      return storeDataScope?.mode === 'OWN_STORE'
        && storeIds.length === 1
        && Boolean(state.user.boundStoreId)
        && storeIds[0] === state.user.boundStoreId
    },
    hasPermission: (state) => (permission: string) => userHasPermission(
      state.user?.role,
      state.user?.permissions,
      permission,
    ),
    dataScope: (state) => (domain: string) => state.user?.dataScopes?.[domain.trim().toUpperCase()],
    scopeText(): string {
      if (this.storeScope.includes('all')) return '全部门店'
      if (!this.storeScope.length) return '未配置门店范围'
      return this.storeScope.length === 1 ? `门店 ${this.storeScope[0]}` : `${this.storeScope.length} 家门店`
    },
  },
  actions: {
    bindSessionInvalidation() {
      if (sessionInvalidationBound || typeof window === 'undefined') return
      sessionInvalidationBound = true
      window.addEventListener(AUTH_SESSION_INVALIDATED_EVENT, () => {
        this.invalidateSession()
      })
    },
    invalidateSession() {
      this.token = ''
      this.user = null
      this.sessionValidated = true
      clearStoredAuth()
    },
    async validateStoredSession() {
      if (this.sessionValidated) return this.isLoggedIn
      if (!this.token || !this.user) {
        this.invalidateSession()
        return false
      }
      try {
        allowProtectedRequests()
        const cachedPermissionVersion = this.user.permissionVersion
        const normalizedUser = normalizeStoredUser(await currentSessionApi(), true)
        if (!normalizedUser) throw new Error('账号会话信息不完整')
        if (cachedPermissionVersion > 0 && normalizedUser.permissionVersion !== cachedPermissionVersion) {
          throw new Error('账号权限版本已更新，请重新登录')
        }
        this.user = normalizedUser
        localStorage.setItem(USER_KEY, JSON.stringify(normalizedUser))
        return true
      } catch {
        this.invalidateSession()
        return false
      } finally {
        this.sessionValidated = true
      }
    },
    async login(username: string, password: string) {
      this.loading = true
      try {
        const data = await withLoginTimeout(loginApi(username, password))
        if (data.status === 'PASSWORD_CHANGE_REQUIRED') {
          const credential = String(data.passwordChangeCredential || '').trim()
          if (!credential) throw new Error('首次改密凭据缺失，请重新登录')
          this.invalidateSession()
          return { passwordChangeRequired: true as const, credential }
        }
        const token = String(data.token || '').trim()
        if (!token) throw new Error('登录返回的会话凭据不完整，请重新登录')

        this.token = token
        this.user = null
        this.sessionValidated = false
        localStorage.setItem(TOKEN_KEY, token)
        allowProtectedRequests()

        const normalizedUser = normalizeStoredUser(await currentSessionApi(), true)
        if (!normalizedUser) throw new Error('账号会话信息不完整，请重新登录')
        this.user = normalizedUser
        this.sessionValidated = true
        localStorage.setItem(USER_KEY, JSON.stringify(normalizedUser))
        return { passwordChangeRequired: false as const, credential: '' }
      } catch (error) {
        this.invalidateSession()
        throw error
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
        this.invalidateSession()
        this.loggingOut = false
      }
    },
  },
})

function withLoginTimeout<T>(promise: Promise<T>) {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      reject(new Error('登录请求超时，请稍后重试'))
    }, LOGIN_TIMEOUT_MS)

    promise
      .then(resolve)
      .catch(reject)
      .finally(() => window.clearTimeout(timer))
  })
}
