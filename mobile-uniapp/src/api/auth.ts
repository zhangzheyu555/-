import { apiGet, apiPost } from './client'
import type { LoginRequest, LoginResponse, SessionUser, WeChatBindingStatus } from '@/types/auth'

export function login(request: LoginRequest): Promise<LoginResponse> {
  return apiPost<LoginResponse, LoginRequest>('/api/auth/login', request, { skipAuth: true })
}

export function weChatLogin(code: string, tenantId?: number): Promise<LoginResponse> {
  return apiPost<LoginResponse, { code: string; tenantId?: number }>('/api/auth/wechat/login', { code, tenantId }, { skipAuth: true })
}

export function weChatBindingStatus(): Promise<WeChatBindingStatus> {
  return apiGet<WeChatBindingStatus>('/api/auth/wechat/binding')
}

export function bindWeChat(code: string): Promise<WeChatBindingStatus> {
  return apiPost<WeChatBindingStatus, { code: string }>('/api/auth/wechat/bind', { code })
}

export function currentSession(): Promise<SessionUser> {
  return apiGet<SessionUser>('/api/auth/me')
}

export function logout(): Promise<void> {
  return apiPost<void>('/api/auth/logout', undefined, { timeout: 5_000 })
}
