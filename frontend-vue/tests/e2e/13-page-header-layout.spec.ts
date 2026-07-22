import { expect, test } from '@playwright/test'

const routes = [
  ['/boss', '老板工作台'],
  ['/profit', '利润概览'],
  ['/profit-table', '利润表'],
  ['/store-detail', '门店详情'],
  ['/data-entry', '数据录入'],
  ['/expenses', '报销栏'],
  ['/salary', '员工工资表'],
  ['/export', '数据导出'],
  ['/inspection', '督导巡店'],
  ['/inspection/standards', '稽核标准'],
  ['/warehouse', '仓库中心'],
  ['/warehouse/items', '仓库中心'],
  ['/warehouse/purchase', '仓库中心'],
  ['/exam-center', '培训考试'],
  ['/stores', '门店管理'],
  ['/users', '账号权限'],
  ['/logs', '操作日志'],
  ['/assistant', '门店经营助手'],
] as const

test.beforeEach(async ({ page }) => {
  const sessionUser = {
    id: 1,
    tenantId: 1,
    tenantName: 'TEST 租户',
    displayName: 'TEST 老板',
    role: 'BOSS',
    roleLabel: '老板（系统管理员）',
    storeScope: ['all'],
    permissions: [],
    dataScopes: {
      STORE: { mode: 'ALL', storeIds: [] },
      FINANCE: { mode: 'ALL', storeIds: [] },
      SALARY: { mode: 'ALL', storeIds: [] },
      WAREHOUSE: { mode: 'ALL', storeIds: [] },
      INSPECTION: { mode: 'ALL', storeIds: [] },
      EXAM: { mode: 'ALL', storeIds: [] },
    },
    defaultWorkspace: '/boss',
    permissionVersion: 1,
  }
  await page.addInitScript(() => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-BOSS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify({
      id: 1,
      tenantId: 1,
      tenantName: 'TEST 租户',
      displayName: 'TEST 老板',
      role: 'BOSS',
      roleLabel: '老板（系统管理员）',
      storeScope: ['all'],
    }))
  })

  await page.route('**/*', (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const data = path === '/api/auth/me'
      ? sessionUser
      : path === '/api/boss/todo-dashboard'
      ? { todayFocus: {}, needsBossAction: [], highRiskReminders: [], roleProgress: [], doneReview: [] }
      : path === '/api/boss/exam-summary'
        ? { activeExamCount: 0, assignedCount: 0, completedCount: 0, completionRate: 0, passedCount: 0, passRate: 0, overdueCount: 0, averageScore: 0, riskStores: [] }
        : path === '/api/finance/dashboard'
          ? { months: ['2026-07'], brands: [], summary: { month: '2026-07' }, entries: [], trend: [] }
          : []
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data }),
    })
  })
})

test('all business routes use one content title and a compact tool-only topbar', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })

  for (const [path, title] of routes) {
    await page.goto(path)
    const pageTitle = path === '/salary'
      ? page.locator('.app-main h1')
      : page.locator('.business-page-header h1')
    await expect(pageTitle).toHaveText(title)
    await expect(page.locator('.app-topbar h1')).toHaveCount(0)
    await expect(page.locator('.app-main h1')).toHaveCount(1)
    await expect(page.locator('.date-display')).toBeVisible()
    if (path === '/assistant') {
      await expect(page.getByLabel('全局门店')).toHaveCount(0)
      await expect(page.getByText('范围', { exact: true })).toBeVisible()
    } else {
      await expect(page.getByLabel('全局门店')).toBeVisible()
    }
    await expect(page.getByRole('button', { name: '打开全局搜索' })).toBeVisible()
    await expect(page.getByRole('button', { name: '消息提醒' })).toBeVisible()
    const topbarHeight = await page.locator('.app-topbar').evaluate((element) => element.getBoundingClientRect().height)
    expect(topbarHeight).toBeGreaterThanOrEqual(56)
    expect(topbarHeight).toBeLessThanOrEqual(64)
  }
})

test('store detail title precedes filters and route history restores the correct title', async ({ page }) => {
  await page.goto('/store-detail?month=2026-07')
  await expect(page.locator('.business-page-header')).toBeVisible()
  await expect(page.locator('.store-actions')).toBeVisible()
  const positions = await page.evaluate(() => ({
    titleBottom: document.querySelector('.business-page-header')!.getBoundingClientRect().bottom,
    filtersTop: document.querySelector('.store-actions')!.getBoundingClientRect().top,
  }))
  expect(positions.filtersTop - positions.titleBottom).toBeGreaterThanOrEqual(12)
  expect(positions.filtersTop - positions.titleBottom).toBeLessThanOrEqual(16)
  await page.goto('/profit?month=2026-07')
  await expect(page.locator('.business-page-header h1')).toHaveText('利润概览')
  await page.goto('/store-detail?month=2026-07')
  await expect(page.locator('.business-page-header h1')).toHaveText('门店详情')
  await page.goBack()
  await expect(page.locator('.business-page-header h1')).toHaveText('利润概览')
})

test('warehouse and exam routes keep one h1 and use specific content headings', async ({ page }) => {
  await page.goto('/warehouse/items')
  await expect(page.locator('.app-main h1')).toHaveCount(1)
  await expect(page.locator('.business-page-header h1')).toHaveText('仓库中心')

  await page.goto('/operations/exams')
  await expect(page.locator('.app-main h1')).toHaveCount(1)
  await expect(page.locator('.business-page-header h1')).toHaveText('培训考试')
  await expect(page.getByRole('button', { name: '我的考试', exact: true })).toBeVisible()
  await expect(page.locator('.workspace-section h2, .workspace-section h3')).toHaveCount(0)
})
