import type { SessionDataScope, SessionUser } from '@/types/auth'

export const EMPTY_SCOPE: SessionDataScope = { mode: 'NONE', storeIds: [], warehouseIds: [] }

export function normalizeRole(role?: string): string {
  const value = String(role || '').trim().toUpperCase()
  if (value === 'ADMIN' || value === 'OWNER') return 'BOSS'
  if (value === 'OPS') return 'OPERATIONS'
  return value
}

export function isBoss(user: SessionUser | null | undefined): boolean {
  return normalizeRole(user?.role) === 'BOSS'
}

export function hasPermission(user: SessionUser | null | undefined, permission: string): boolean {
  if (!user) return false
  if (isBoss(user)) return true
  const expected = permission.trim().toLowerCase()
  return Boolean(expected && user.permissions.some((item) => item.trim().toLowerCase() === expected))
}

export function hasAnyPermission(user: SessionUser | null | undefined, permissions: readonly string[]): boolean {
  return permissions.some((permission) => hasPermission(user, permission))
}

export function dataScope(user: SessionUser | null | undefined, domain: string): SessionDataScope {
  return user?.dataScopes[domain.trim().toUpperCase()] || EMPTY_SCOPE
}

export function hasScopeMode(
  user: SessionUser | null | undefined,
  domain: string | undefined,
  allowedModes: readonly string[] | undefined,
): boolean {
  if (!domain || !allowedModes?.length) return true
  if (isBoss(user)) return true
  const mode = dataScope(user, domain).mode.trim().toUpperCase()
  return allowedModes.includes(mode)
}

export function hasAllowedRole(user: SessionUser | null | undefined, roles: readonly string[]): boolean {
  if (!user) return false
  if (isBoss(user)) return true
  const role = normalizeRole(user.role)
  return roles.some((allowed) => normalizeRole(allowed) === role)
}
