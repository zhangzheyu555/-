import { expect, test, type Page } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const bossSession = {
  id: 99,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['system.user.manage'],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [] } },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const managers = [
  {
    id: 1,
    tenantId: 1,
    tenantName: '测试租户',
    username: 'rg1',
    displayName: '荆州之星店店长',
    role: 'STORE_MANAGER',
    roleLabel: '店长',
    storeId: 'JZ-STAR',
    enabled: true,
    storeScope: ['JZ-STAR'],
    availableWorkspaces: [],
    defaultWorkspace: '/store',
    effectivePermissionStatus: 'NO_WORKSPACE',
    effectivePermissionMessage: '最终权限缺少 store.read。',
  },
  {
    id: 2,
    tenantId: 1,
    tenantName: '测试租户',
    username: 'rg2',
    displayName: '测试停用店长',
    role: 'STORE_MANAGER',
    roleLabel: '店长',
    storeId: 'JZ-STAR',
    enabled: false,
    storeScope: ['JZ-STAR'],
    availableWorkspaces: [],
    defaultWorkspace: '/store',
    effectivePermissionStatus: 'DISABLED',
    effectivePermissionMessage: '账号已停用，启用前没有可用工作台。',
  },
]

const authorization = (userId: number) => ({
  userId,
  role: 'STORE_MANAGER',
  storeId: 'JZ-STAR',
  permissionVersion: 1,
  roleTemplatePermissions: [],
  dataScopes: [
    { domainCode: 'STORE', mode: 'OWN_STORE', storeIds: [] },
  ],
  overrides: [],
  effectivePermissions: [],
  availableWorkspaces: [],
  defaultWorkspace: '/store',
  effectivePermissionStatus: managers.find((user) => user.id === userId)?.enabled ? 'NO_WORKSPACE' : 'DISABLED',
  effectivePermissionMessage: '店长工作台未授权。',
})

async function prepare(page: Page) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-BOSS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, bossSession)
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(bossSession)))
  await page.route('**/api/users', (route) => route.fulfill(ok(managers)))
  await page.route('**/api/stores', (route) => route.fulfill(ok([
    { id: 'JZ-STAR', code: 'JZ-STAR', name: '荆州之星店', brandName: '茹菓' },
  ])))
  await page.route('**/api/users/authorization/catalog', (route) => route.fulfill(ok({
    permissions: [{
      permissionCode: 'store.read',
      moduleCode: 'STORE',
      permissionName: '门店查看',
      description: '查看绑定门店资料和经营信息',
      riskLevel: 'LOW',
      enabled: true,
      sortOrder: 1,
    }],
    dataScopeDomains: ['STORE', 'FINANCE', 'SALARY', 'WAREHOUSE', 'INSPECTION', 'EXAM', 'PLATFORM'],
    dataScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE', 'CENTRAL_WAREHOUSE', 'SELF'],
  })))
  await page.route(/\/api\/users\/(\d+)\/authorization$/, (route) => {
    const userId = Number(new URL(route.request().url()).pathname.split('/')[3])
    return route.fulfill(ok(authorization(userId)))
  })
}

test('account list exposes workspace readiness and blocks enabling a manager without a workspace', async ({ page }) => {
  await prepare(page)
  let accessProfileWrites = 0
  await page.route('**/api/users/*/access-profile', (route) => {
    accessProfileWrites += 1
    return route.fulfill(ok({ user: managers[1], authorization: authorization(2) }))
  })

  await page.goto('/users')

  await expect(page.getByRole('columnheader', { name: '可用工作台' })).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '有效权限状态' })).toBeVisible()
  await expect(page.getByText('店长工作台未授权')).toHaveCount(2)

  const disabledManagerRow = page.getByRole('row').filter({ hasText: 'rg2' })
  await disabledManagerRow.getByTitle('编辑账号').click()
  await page.getByLabel('启用账号').check()
  await page.getByRole('button', { name: '保存', exact: true }).click()

  await expect(page.getByText(/店长工作台未授权：请先确认角色模板包含/)).toBeVisible()
  expect(accessProfileWrites).toBe(0)
})

test('authorization save uses the atomic access-profile endpoint', async ({ page }) => {
  await prepare(page)
  let requestBody: Record<string, unknown> | null = null
  const readyUser = {
    ...managers[0],
    availableWorkspaces: ['/store'],
    effectivePermissionStatus: 'READY',
    effectivePermissionMessage: '可进入门店工作台。',
  }
  const readyAuthorization = {
    ...authorization(1),
    permissionVersion: 2,
    overrides: [{ permissionCode: 'store.read', effect: 'ALLOW' }],
    effectivePermissions: ['store.read'],
    availableWorkspaces: ['/store'],
    effectivePermissionStatus: 'READY',
    effectivePermissionMessage: '可进入门店工作台。',
  }
  await page.route('**/api/users/1/access-profile', async (route) => {
    requestBody = route.request().postDataJSON()
    await route.fulfill(ok({ user: readyUser, authorization: readyAuthorization }))
  })

  await page.goto('/users')
  const managerRow = page.getByRole('row').filter({ hasText: 'rg1' })
  await managerRow.getByTitle('配置角色模板、数据范围和个人权限').click()

  await expect(page.getByText('店长工作台未授权').last()).toBeVisible()
  await page.getByLabel('门店查看个人权限').selectOption('ALLOW')
  await page.getByRole('button', { name: '保存账号授权' }).click()

  await expect(page.getByText('权限已更新，该账号需要重新登录。权限版本 v2。')).toBeVisible()
  expect(requestBody).toMatchObject({
    displayName: '荆州之星店店长',
    role: 'STORE_MANAGER',
    storeId: 'JZ-STAR',
    storeScope: ['JZ-STAR'],
    enabled: true,
    overrides: [{ permissionCode: 'store.read', effect: 'ALLOW' }],
  })
  expect(Array.isArray(requestBody?.dataScopes)).toBe(true)
})
