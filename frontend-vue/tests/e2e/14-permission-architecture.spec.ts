import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'
import { roles } from './auth.setup'

const root = process.cwd()
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

test('session and role contracts preserve effective authorization fields', () => {
  const authApi = source('src/api/auth.ts')
  const authStore = source('src/stores/auth.ts')
  const roleSource = source('src/permissions/roles.ts')

  for (const field of ['permissions', 'dataScopes', 'defaultWorkspace', 'permissionVersion']) {
    expect(authApi).toContain(field)
    expect(authStore).toContain(field)
  }
  expect(roleSource).toContain("SUPERVISOR: '督导'")
  expect(roleSource).toContain("EMPLOYEE: '员工'")
  expect(roles.map((role) => role.key)).toEqual([
    'boss', 'finance', 'warehouse', 'store', 'supervisor', 'learner',
  ])
})

test('router and sidebar use permissions instead of role allowlists', () => {
  const router = source('src/router/index.ts')
  const sidebar = source('src/components/sidebar/AppSidebar.vue')

  expect(router).toContain('meta.permission')
  expect(router).toContain('auth.hasPermission')
  expect(router).not.toContain('meta.roles')
  expect(router).not.toContain('canAccessRoles')
  expect(sidebar).toContain('resolveMenuGroups')
  expect(sidebar).toContain('resolveUtilityMenuItems')
  expect(sidebar).not.toContain('canAccessRoles')
  expect(sidebar).not.toContain('bossMenuGroups')
})

test('account access summaries and atomic access-profile updates are wired', () => {
  const usersApi = source('src/api/users.ts')
  const userPage = source('src/pages/UserPermissionPage.vue')

  for (const field of ['availableWorkspaces', 'defaultWorkspace', 'effectivePermissionStatus', 'effectivePermissionMessage']) {
    expect(usersApi).toContain(field)
  }
  expect(usersApi).toContain('/access-profile')
  expect(userPage).toContain('可用工作台')
  expect(userPage).toContain('有效权限状态')
  expect(userPage).toContain('店长工作台未授权')
  expect(userPage).toContain('权限已更新，该账号需要重新登录')
  expect(userPage).toContain('updateUserAccessProfile')
  expect(userPage).not.toContain('await updateUser(editingUser.value.id, profile)')
})

test('all management workspaces and learner-only route are wired', () => {
  const router = source('src/router/index.ts')
  const workspacePermissions = source('src/permissions/workspaces.ts')
  for (const path of ['/boss', '/finance', '/warehouse', '/store', '/operations', '/employee', '/learn/exams']) {
    expect(`${router}\n${workspacePermissions}`).toContain(`'${path}'`)
  }
  for (const workspace of [
    'BossWorkspace.vue',
    'FinanceWorkspace.vue',
    'WarehouseWorkspace.vue',
    'StoreManagerWorkspace.vue',
    'OperationsWorkspace.vue',
  ]) {
    expect(() => source(`src/pages/workspaces/${workspace}`)).not.toThrow()
  }
  for (const workspace of [
    'CentralWarehouseWorkspace.vue',
    'StoreInventoryWorkspace.vue',
    'FinanceSalaryWorkspace.vue',
    'StoreSalaryWorkspace.vue',
    'ExamAdminWorkspace.vue',
    'ExamProgressWorkspace.vue',
  ]) {
    expect(() => source(`src/pages/workspaces/business/${workspace}`)).not.toThrow()
  }
  expect(router).toContain('LearnerLayout')
  expect(router).toContain('PERMISSIONS.EXAM_LEARN')
})

test('401 invalidation synchronizes browser storage and Pinia while 403 stays explicit', () => {
  const http = source('src/api/http.ts')
  const authStore = source('src/stores/auth.ts')

  expect(http).toContain('AUTH_SESSION_INVALIDATED_EVENT')
  expect(http).toContain('cancelProtectedRequests()')
  expect(authStore).toContain('bindSessionInvalidation')
  expect(authStore).toContain('invalidateSession()')
  expect(http).toContain('当前账号没有访问该数据或执行此操作的权限')
})
