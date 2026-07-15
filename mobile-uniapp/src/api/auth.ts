import { apiGet, apiPost } from './client'
import type { LoginRequest, LoginResponse, SessionUser } from '@/types/auth'

export function login(request: LoginRequest): Promise<LoginResponse> {
  return apiPost<LoginResponse, LoginRequest>('/api/auth/login', request, { skipAuth: true })
}

export function currentSession(): Promise<SessionUser> {
  return apiGet<SessionUser>('/api/auth/me')
}

export function logout(): Promise<void> {
  return apiPost<void>('/api/auth/logout', undefined, { timeout: 5_000 })
}

