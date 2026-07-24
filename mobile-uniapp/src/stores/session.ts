import { defineStore } from 'pinia'
import { currentSession, login as loginApi, logout as logoutApi, weChatLogin } from '@/api/auth'
import { ApiError } from '@/api/client'
import { clearSessionToken, readSessionToken, writeSessionToken } from '@/platform/session'
import type { LoginRequest, LoginResponse, SessionDataScope, SessionUser } from '@/types/auth'
import { dataScope, hasAnyPermission, hasPermission } from '@/permissions'

const NONE_SCOPE: SessionDataScope = { mode: 'NONE', storeIds: [], warehouseIds: [] }

export const useSessionStore = defineStore('mobile-session', {
  state: () => ({
    token: readSessionToken(),
    user: null as SessionUser | null,
    restoring: false,
    submitting: false,
    initialized: false,
    error: '',
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.token && state.user),
    hasPermission: (state) => (permission: string) => hasPermission(state.user, permission),
    hasAnyPermission(): (permissions: string[]) => boolean {
      return (permissions) => hasAnyPermission(this.user, permissions)
    },
    dataScope: (state) => (domain: string): SessionDataScope => (
      dataScope(state.user, domain) || NONE_SCOPE
    ),
    scopeLabel(): string {
      const scope = this.dataScope('STORE')
      if (scope.mode === 'ALL') return '全部门店'
      if (scope.mode === 'OWN_STORE') return this.user?.boundStoreName || '本人所属门店'
      if (scope.storeIds.length) return `${scope.storeIds.length} 家门店`
      return '未配置门店范围'
    },
  },
  actions: {
    async restore(): Promise<boolean> {
      if (this.restoring) return this.isAuthenticated
      this.token = readSessionToken()
      this.initialized = true
      if (!this.token) {
        this.user = null
        return false
      }
      this.restoring = true
      this.error = ''
      try {
        this.user = await currentSession()
        return true
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) this.clear()
        else this.error = error instanceof Error ? error.message : '会话校验失败，请检查网络后重试'
        return false
      } finally {
        this.restoring = false
      }
    },
    async login(request: LoginRequest): Promise<void> {
      this.submitting = true
      this.error = ''
      try {
        await this.completeLogin(loginApi(request))
      } catch (error) {
        this.clear()
        this.error = error instanceof Error ? error.message : '登录失败，请稍后重试'
        throw error
      } finally {
        this.submitting = false
      }
    },
    async loginWithWeChatCode(code: string): Promise<void> {
      this.submitting = true
      this.error = ''
      try {
        await this.completeLogin(weChatLogin(code))
      } catch (error) {
        this.clear()
        this.error = error instanceof Error ? error.message : '微信登录失败，请稍后重试'
        throw error
      } finally {
        this.submitting = false
      }
    },
    async logout(): Promise<void> {
      this.submitting = true
      try {
        if (this.token) await logoutApi()
      } catch {
        // 服务暂时不可达时也必须结束本机会话；后端 Token 会在有效期结束后失效。
      } finally {
        this.clear()
        this.submitting = false
      }
    },
    clear(): void {
      clearSessionToken()
      this.token = ''
      this.user = null
      this.error = ''
      this.initialized = true
    },
    async completeLogin(responsePromise: Promise<LoginResponse>): Promise<void> {
      const response = await responsePromise
      const token = response.token?.trim()
      if (!token) throw new Error('登录返回的会话凭据不完整')
      writeSessionToken(token)
      this.token = token
      // 登录响应与 /me 使用同一 SessionUser 契约；再次读取可捕获即时权限版本变化。
      this.user = await currentSession()
      this.initialized = true
    },
  },
})
