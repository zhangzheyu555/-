import { expect, test, type Page } from '@playwright/test'

type TestRole = 'BOSS' | 'FINANCE' | 'STORE_MANAGER' | 'WAREHOUSE' | 'OPERATIONS' | 'SUPERVISOR' | 'EMPLOYEE'

const contracts: Record<TestRole, { permissions: string[]; dataScopes: Record<string, { mode: string; storeIds: string[] }>; defaultWorkspace: string }> = {
  BOSS: { permissions: [], dataScopes: { STORE: { mode: 'ALL', storeIds: [] }, FINANCE: { mode: 'ALL', storeIds: [] }, SALARY: { mode: 'ALL', storeIds: [] }, WAREHOUSE: { mode: 'ALL', storeIds: [] }, INSPECTION: { mode: 'ALL', storeIds: [] }, EXAM: { mode: 'ALL', storeIds: [] } }, defaultWorkspace: '/boss' },
  FINANCE: { permissions: ['system.dashboard.read', 'store.read', 'finance.profit.read', 'finance.profit.write', 'finance.export', 'expense.create', 'expense.read', 'expense.review', 'salary.read', 'salary.edit', 'assistant.use'], dataScopes: { STORE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, FINANCE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, SALARY: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/finance' },
  STORE_MANAGER: { permissions: ['system.dashboard.read', 'store.read', 'finance.profit.read', 'finance.profit.write', 'expense.create', 'expense.read', 'salary.read', 'warehouse.store.read', 'warehouse.requisition.create', 'warehouse.requisition.receive', 'inspection.read', 'exam.learn', 'exam.report', 'assistant.use'], dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, FINANCE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, SALARY: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, INSPECTION: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/store' },
  WAREHOUSE: { permissions: ['system.dashboard.read', 'warehouse.central.read', 'warehouse.central.manage', 'assistant.use'], dataScopes: { WAREHOUSE: { mode: 'CENTRAL_WAREHOUSE', storeIds: [] } }, defaultWorkspace: '/warehouse' },
  OPERATIONS: { permissions: ['system.dashboard.read', 'operations.dashboard.read', 'store.read', 'warehouse.store.read', 'inspection.read', 'inspection.manage', 'exam.learn', 'exam.manage', 'exam.report', 'platform.read', 'platform.manage', 'assistant.use'], dataScopes: { STORE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, WAREHOUSE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, INSPECTION: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/operations' },
  SUPERVISOR: { permissions: ['system.dashboard.read', 'operations.dashboard.read', 'inspection.read', 'inspection.manage', 'exam.learn', 'exam.manage', 'exam.report', 'assistant.use'], dataScopes: { INSPECTION: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/operations' },
  EMPLOYEE: { permissions: ['exam.learn'], dataScopes: { EXAM: { mode: 'SELF', storeIds: [] } }, defaultWorkspace: '/learn/exams' },
}

async function seedSession(page: Page, role: TestRole, permissionOverride?: string[]) {
  const contract = contracts[role]
  const permissions = permissionOverride || contract.permissions
  const sessionUser = {
    id: 9001,
    tenantId: 1,
    tenantName: 'TEST 租户',
    displayName: `TEST ${role}`,
    role,
    roleLabel: role,
    storeScope: role === 'STORE_MANAGER' ? ['TEST-STORE'] : ['all'],
    permissions,
    dataScopes: contract.dataScopes,
    defaultWorkspace: contract.defaultWorkspace,
    permissionVersion: 1,
  }
  await page.unroute('**/*')
  await page.route('**/*', (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: path === '/api/auth/me' ? sessionUser : [] }),
    })
  })
  if (page.url().startsWith('http')) await page.evaluate(() => localStorage.clear())
  await page.goto('/login')
  await page.evaluate(({ roleCode, user }) => {
    localStorage.setItem('ai_profit_vue_token', `TEST-${roleCode}-TOKEN`)
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, { roleCode: role, user: sessionUser })
}

test('management roles have permission-driven navigation while learners never receive AppLayout', async ({ page }) => {
  for (const role of ['BOSS', 'FINANCE', 'STORE_MANAGER', 'WAREHOUSE', 'OPERATIONS'] as const) {
    await seedSession(page, role)
    await page.goto('/')
    const nav = page.locator('.app-sidebar--desktop')
    await expect(nav).toBeVisible()
    for (const removed of ['决策支持', '今日待办', '数据核对', '运营中心']) await expect(nav).not.toContainText(removed)
  }

  await seedSession(page, 'EMPLOYEE')
  await page.goto('/')
  await expect(page).toHaveURL(/\/learn\/exams$/)
  await expect(page.locator('.app-sidebar--desktop')).toHaveCount(0)
  await expect(page.locator('.learner-header')).toContainText('学习中心')
})

test('warehouse and migrated supervisor routes keep the matching permission menu active', async ({ page }) => {
  await seedSession(page, 'WAREHOUSE')
  await page.goto('/warehouse/items')
  await expect(page.locator('.app-sidebar--desktop').getByRole('link', { name: '仓库中心', exact: true })).toHaveClass(/router-link-active/)

  await seedSession(page, 'SUPERVISOR')
  await page.goto('/inspection/records')
  await expect(page).toHaveURL(/\/operations\/inspection\/records$/)
  await expect(page.locator('.sidebar-account')).toContainText('运营')
})

test('each business route exposes exactly one current sidebar menu item', async ({ page }) => {
  const cases: Array<{ role: TestRole; path: string; menu: string }> = [
    { role: 'BOSS', path: '/operations/exams', menu: '培训考试' },
    { role: 'BOSS', path: '/store/exams', menu: '培训考试' },
    { role: 'WAREHOUSE', path: '/warehouse', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/inventory', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/requests', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/items', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/purchase', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/movements', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/returns', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/alerts', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/receipts', menu: '仓库中心' },
    { role: 'WAREHOUSE', path: '/warehouse/workspace', menu: '仓库中心' },
    { role: 'STORE_MANAGER', path: '/store/inventory/requisition', menu: '仓库中心' },
    { role: 'STORE_MANAGER', path: '/store/inventory/records', menu: '仓库中心' },
  ]

  for (const entry of cases) {
    await seedSession(page, entry.role)
    await page.goto(entry.path)
    const current = page.locator('[aria-current="page"]')
    await expect(current).toHaveCount(1)
    await expect(current).toHaveText(entry.menu)
  }
})

test('exam management and progress share one sidebar entry', async ({ page }) => {
  await seedSession(page, 'BOSS')
  await page.goto('/operations/exams')

  const sidebar = page.locator('.app-sidebar--desktop')
  const examEntry = sidebar.getByRole('link', { name: '培训考试', exact: true })
  await expect(examEntry).toHaveCount(1)
  await expect(examEntry).toHaveAttribute('href', '/exam-center')
  await expect(sidebar.getByRole('link', { name: '培训进度', exact: true })).toHaveCount(0)
  await expect(examEntry).toHaveAttribute('aria-current', 'page')

  await page.goto('/store/exams')
  await expect(examEntry).toHaveAttribute('aria-current', 'page')

  await seedSession(page, 'STORE_MANAGER')
  await page.goto('/exam-center')
  await expect(page).toHaveURL(/\/store\/exams$/)
  await expect(page.locator('.app-sidebar--desktop').getByRole('link', { name: '培训考试', exact: true })).toHaveAttribute('aria-current', 'page')
})

test('warehouse child functions live only in route-backed secondary navigation', async ({ page }) => {
  await seedSession(page, 'WAREHOUSE')
  await page.goto('/warehouse/items')

  const sidebar = page.locator('.app-sidebar--desktop')
  await expect(sidebar.getByRole('link', { name: '仓库中心', exact: true })).toHaveCount(1)
  for (const duplicate of ['总仓库存', '物料档案', '采购入库', '出入库记录']) {
    await expect(sidebar.getByRole('link', { name: duplicate, exact: true })).toHaveCount(0)
  }

  const secondary = page.getByRole('navigation', { name: '仓库中心功能' })
  await expect(secondary.getByRole('link', { name: '物料档案', exact: true })).toHaveAttribute('aria-current', 'location')
  await expect(secondary.getByRole('link', { name: '采购入库', exact: true })).toHaveAttribute('href', '/warehouse/purchase')
  await expect(secondary.getByRole('link', { name: '出入库记录', exact: true })).toHaveAttribute('href', '/warehouse/movements')

  await secondary.getByRole('link', { name: '采购入库', exact: true }).click()
  await expect(page).toHaveURL(/\/warehouse\/purchase$/)
  await expect(page.getByRole('navigation', { name: '仓库中心功能' }).getByRole('link', { name: '采购入库', exact: true })).toHaveAttribute('aria-current', 'location')
  await page.goBack()
  await expect(page).toHaveURL(/\/warehouse\/items$/)
  await page.goForward()
  await expect(page).toHaveURL(/\/warehouse\/purchase$/)
})

test('store managers enter the same warehouse module but only receive store-scoped functions', async ({ page }) => {
  await seedSession(page, 'STORE_MANAGER')
  await page.goto('/warehouse')
  await expect(page).toHaveURL(/\/store\/inventory$/)
  await expect(page.locator('[aria-current="page"]')).toHaveText('仓库中心')

  const secondary = page.getByRole('navigation', { name: '本店仓库功能' })
  await expect(secondary.getByRole('link')).toHaveCount(3)
  await expect(secondary).toContainText('本店库存')
  await expect(secondary).toContainText('门店叫货')
  await expect(secondary).toContainText('本店记录')
  await expect(secondary).not.toContainText('采购入库')

  await page.goto('/warehouse/items')
  await expect(page).toHaveURL(/\/no-permission\?from=/)
})

test('legacy addresses redirect to the current permission workspace instead of boss', async ({ page }) => {
  await seedSession(page, 'BOSS')
  await page.goto('/todos')
  await expect(page).toHaveURL(/\/boss$/)

  await seedSession(page, 'FINANCE')
  await page.goto('/finance-data-check')
  await expect(page).toHaveURL(/\/finance$/)

  await seedSession(page, 'STORE_MANAGER')
  await page.goto('/salary')
  await expect(page).toHaveURL(/\/store\/salary$/)

  await seedSession(page, 'OPERATIONS')
  await page.goto('/operations/training')
  await expect(page).toHaveURL(/\/operations$/)
})

test('revoked permission removes the menu and direct URL is rejected', async ({ page }) => {
  const permissions = contracts.FINANCE.permissions.filter((permission) => permission !== 'finance.export')
  await seedSession(page, 'FINANCE', permissions)
  await page.goto('/finance')
  await expect(page.locator('.app-sidebar--desktop')).not.toContainText('数据导出')

  await page.goto('/export')
  await expect(page).toHaveURL(/\/no-permission\?from=/)
  await expect(page.getByText('当前账号没有访问该页面的权限')).toBeVisible()
})
