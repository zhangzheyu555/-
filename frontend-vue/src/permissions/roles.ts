export const BOSS_ROLE = 'BOSS'

/** Roles that always have full store scope regardless of database assignments. */
export const GLOBAL_STORE_ROLES = ['BOSS', 'FINANCE', 'SUPERVISOR', 'WAREHOUSE'] as const

export function normalizeRoleCode(role?: string) {
  const value = String(role || '').trim().toUpperCase()
  const aliases: Record<string, string> = {
    ADMIN: BOSS_ROLE,
    OWNER: BOSS_ROLE,
    OPS: 'SUPERVISOR',
    OPERATIONS: 'SUPERVISOR',
    STORE: 'STORE_MANAGER',
    MANAGER: 'STORE_MANAGER',
  }
  return aliases[value] || value
}

export function isBossRole(role?: string) {
  return normalizeRoleCode(role) === BOSS_ROLE
}

/** Returns true when the role always grants access to every store in the tenant. */
export function isGlobalStoreRole(role?: string) {
  return (GLOBAL_STORE_ROLES as readonly string[]).includes(normalizeRoleCode(role))
}

export function isLearnerRole(role?: string) {
  return normalizeRoleCode(role) === 'EMPLOYEE'
}

export function canAccessRoles(role: string | undefined, allowedRoles: readonly string[]) {
  const normalized = normalizeRoleCode(role)
  if (!normalized) return false
  if (isBossRole(normalized)) return true
  return allowedRoles.some((allowedRole) => normalizeRoleCode(allowedRole) === normalized)
}

export function roleName(role?: string) {
  const names: Record<string, string> = {
    BOSS: '老板（系统管理员）',
    FINANCE: '财务',
    WAREHOUSE: '仓库管理员',
    SUPERVISOR: '督导',
    STORE_MANAGER: '店长',
    EMPLOYEE: '员工',
  }
  const normalized = normalizeRoleCode(role)
  return names[normalized] || normalized || '未登录'
}
