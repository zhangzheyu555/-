import { expect, test, type Page } from '@playwright/test'

type TestRole = 'BOSS' | 'FINANCE' | 'STORE_MANAGER' | 'WAREHOUSE' | 'SUPERVISOR' | 'EMPLOYEE'

const contracts: Record<TestRole, { permissions: string[]; dataScopes: Record<string, { mode: string; storeIds: string[] }>; defaultWorkspace: string }> = {
  BOSS: { permissions: [], dataScopes: { STORE: { mode: 'ALL', storeIds: [] }, FINANCE: { mode: 'ALL', storeIds: [] }, SALARY: { mode: 'ALL', storeIds: [] }, WAREHOUSE: { mode: 'ALL', storeIds: [] }, INSPECTION: { mode: 'ALL', storeIds: [] }, EXAM: { mode: 'ALL', storeIds: [] } }, defaultWorkspace: '/boss' },
  FINANCE: { permissions: ['system.dashboard.read', 'store.read', 'finance.profit.read', 'finance.profit.write', 'finance.export', 'expense.create', 'expense.read', 'expense.review', 'salary.read', 'salary.edit', 'assistant.use'], dataScopes: { STORE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, FINANCE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, SALARY: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/finance' },
  STORE_MANAGER: { permissions: ['system.dashboard.read', 'store.read', 'finance.profit.read', 'finance.profit.write', 'expense.create', 'expense.read', 'salary.read', 'warehouse.store.read', 'warehouse.requisition.create', 'warehouse.requisition.receive', 'daily_loss.read', 'daily_loss.create', 'inspection.read', 'exam.learn', 'exam.report', 'assistant.use'], dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, FINANCE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, SALARY: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, INSPECTION: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/store' },
  WAREHOUSE: { permissions: ['system.dashboard.read', 'warehouse.read', 'warehouse.purchase', 'warehouse.transfer.request', 'warehouse.transfer.approve', 'warehouse.transfer.ship', 'warehouse.transfer.receive', 'warehouse.requisition.process', 'warehouse.configure', 'warehouse.central.read', 'warehouse.central.manage', 'assistant.use'], dataScopes: { WAREHOUSE: { mode: 'CENTRAL_WAREHOUSE', storeIds: [] } }, defaultWorkspace: '/warehouse' },
  SUPERVISOR: { permissions: ['system.dashboard.read', 'operations.dashboard.read', 'store.read', 'warehouse.store.read', 'employee.read', 'employee_assistant.use', 'daily_loss.read', 'daily_loss.review', 'daily_loss.export', 'inspection.read', 'inspection.manage', 'exam.learn', 'exam.manage', 'exam.report', 'platform.read', 'platform.manage', 'assistant.use'], dataScopes: { STORE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, WAREHOUSE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, INSPECTION: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/operations' },
  EMPLOYEE: { permissions: ['exam.learn', 'employee_assistant.use'], dataScopes: { EXAM: { mode: 'SELF', storeIds: [] } }, defaultWorkspace: '/employee' },
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
    roleLabel: role === 'EMPLOYEE' ? '员工' : role,
    storeScope: role === 'STORE_MANAGER' ? ['TEST-STORE'] : ['all'],
    permissions,
    dataScopes: contract.dataScopes,
    boundStoreId: ['STORE_MANAGER', 'EMPLOYEE'].includes(role) ? 'TEST-STORE' : null,
    boundStoreName: ['STORE_MANAGER', 'EMPLOYEE'].includes(role) ? '测试门店' : null,
    defaultWorkspace: contract.defaultWorkspace,
    permissionVersion: 1,
  }
  await page.unroute('**/*')
  await page.route('**/*', (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/employee/workbench') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            profile: { userId: sessionUser.id, displayName: sessionUser.displayName, role },
            store: { storeId: 'TEST-STORE', storeName: '测试门店', brandName: '测试品牌' },
            workItems: [],
            workSummary: { total: 0, pending: 0, overdue: 0, completed: 0, retakePending: 0 },
            assistant: { enabled: true, state: 'READY', message: '员工服务助手可用', route: '/employee-assistant' },
          },
        }),
      })
    }
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

test('management roles and employee workbench have permission-driven navigation', async ({ page }) => {
  for (const role of ['BOSS', 'FINANCE', 'STORE_MANAGER', 'WAREHOUSE', 'SUPERVISOR'] as const) {
    await seedSession(page, role)
    await page.goto('/')
    const nav = page.locator('.app-sidebar--desktop')
    await expect(nav).toBeVisible()
    for (const removed of ['决策支持', '今日待办', '数据核对', '运营中心']) await expect(nav).not.toContainText(removed)
  }

  await seedSession(page, 'EMPLOYEE')
  await page.goto('/')
  await expect(page).toHaveURL(/\/employee$/)
  const employeeNav = page.locator('.app-sidebar--desktop')
  await expect(employeeNav).toBeVisible()
  await expect(employeeNav).toContainText('员工工作台')
  await expect(employeeNav).toContainText('培训考试')
  await expect(employeeNav).toContainText('员工服务助手')
  await expect(employeeNav).not.toContainText('老板工作台')
  await expect(employeeNav).not.toContainText('仓库中心')
  await expect(employeeNav).not.toContainText('平台配置')
})

test('warehouse and migrated supervisor routes keep the matching permission menu active', async ({ page }) => {
  await seedSession(page, 'WAREHOUSE')
  await page.goto('/warehouse/items')
  const warehouseSidebar = page.locator('.app-sidebar--desktop')
  await expect(warehouseSidebar.getByRole('link', { name: '仓库工作台', exact: true })).toBeVisible()
  await expect(warehouseSidebar.getByRole('link', { name: '仓库中心', exact: true })).toHaveClass(/router-link-active/)

  await seedSession(page, 'SUPERVISOR')
  await page.goto('/inspection/records')
  await expect(page).toHaveURL(/\/operations\/inspection\/records$/)
  await expect(page.locator('.sidebar-account')).toContainText('督导')
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

test('store manager default workspace shares the single store detail navigation entry', async ({ page }) => {
  await seedSession(page, 'STORE_MANAGER')
  await page.goto('/store')

  const sidebar = page.locator('.app-sidebar--desktop')
  await expect(sidebar.getByRole('link', { name: '门店工作台', exact: true })).toHaveCount(0)
  await expect(sidebar.getByRole('link', { name: '门店详情', exact: true })).toHaveCount(1)
  await expect(sidebar.getByRole('link', { name: '门店详情', exact: true })).toHaveAttribute('aria-current', 'page')
  await expect(page).toHaveURL(/\/store$/)
  await expect(page.locator('.store-detail-page')).toBeVisible()
})

test('store manager workbench exposes daily loss while keeping own-store scope', async ({ page }) => {
  await seedSession(page, 'STORE_MANAGER')
  await page.goto('/store')

  const dailyLossEntry = page.locator('.app-sidebar--desktop').getByRole('link', { name: '每日报损', exact: true })
  await expect(dailyLossEntry).toBeVisible()
  await dailyLossEntry.click()
  await expect(page).toHaveURL(/\/daily-loss$/)
  await expect(page.getByRole('heading', { name: '每日报损', exact: true })).toBeVisible()
  await expect(page.locator('.business-page-heading p')).toHaveCount(0)
})

test('supervisor owns daily loss but cannot enter store detail warehouse profiles or assistants', async ({ page }) => {
  // 即使浏览器仍保留迁移前的权限快照，固定的角色入口也不能被隐藏。
  await seedSession(
    page,
    'SUPERVISOR',
    contracts.SUPERVISOR.permissions.filter((permission) => !permission.startsWith('daily_loss.')),
  )
  await page.goto('/operations')

  const sidebar = page.locator('.app-sidebar--desktop')
  await expect(sidebar.getByRole('link', { name: '每日报损', exact: true })).toBeVisible()
  await expect(page.locator('main a[href="/daily-loss"]')).toHaveCount(1)
  await expect(sidebar.getByRole('link', { name: '门店详情', exact: true })).toHaveCount(0)
  await expect(sidebar.getByRole('link', { name: '仓库中心', exact: true })).toHaveCount(0)
  await expect(sidebar.getByRole('link', { name: '员工档案', exact: true })).toHaveCount(0)
  await expect(sidebar.getByRole('link', { name: '门店经营助手', exact: true })).toHaveCount(0)
  await expect(sidebar.getByRole('link', { name: '员工服务助手', exact: true })).toHaveCount(0)

  await sidebar.getByRole('link', { name: '每日报损', exact: true }).click()
  await expect(page).toHaveURL(/\/daily-loss$/)
  await expect(page.getByRole('heading', { name: '每日报损', exact: true })).toBeVisible()

  for (const forbiddenPath of ['/store-detail', '/warehouse', '/staff', '/assistant', '/employee-assistant']) {
    await page.goto(forbiddenPath)
    await expect(page).toHaveURL(/\/no-permission/)
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

  await seedSession(page, 'SUPERVISOR')
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
