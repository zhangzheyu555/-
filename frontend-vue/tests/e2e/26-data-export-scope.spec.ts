import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const stores = [
  { id: 'rg1', code: 'rg1', name: '荆州之星店', brandId: 1, brandName: '茹菓' },
  { id: 'rg2', code: 'rg2', name: '万达二店', brandId: 2, brandName: '霸王茶姬' },
]

const brands = [
  { id: 1, name: '茹菓', color: '#76bdb8' },
  { id: 2, name: '霸王茶姬', color: '#9c2f3e' },
]

const entries = [
  {
    id: 1,
    storeId: 'rg1',
    storeCode: 'rg1',
    storeName: '荆州之星店',
    brandId: 1,
    brandName: '茹菓',
    month: '2026-07',
    sales: 120000,
    income: 110000,
    costSum: 50000,
    expenseSum: 24000,
    net: 36000,
    margin: 0.3273,
  },
  {
    id: 2,
    storeId: 'rg2',
    storeCode: 'rg2',
    storeName: '万达二店',
    brandId: 2,
    brandName: '霸王茶姬',
    month: '2026-07',
    sales: 90000,
    income: 85000,
    costSum: 42000,
    expenseSum: 22000,
    net: 21000,
    margin: 0.2471,
  },
]

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['finance.export', 'finance.profit.read'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
    SALARY: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 3,
}

const managerSession = {
  id: 7,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '荆州之星店店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['rg1'],
  permissions: ['store.read', 'finance.profit.read', 'finance.export'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    FINANCE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    SALARY: { mode: 'OWN_STORE', storeIds: ['rg1'] },
  },
  dataScope: { mode: 'OWN_STORE', storeIds: ['rg1'] },
  boundStoreId: 'rg1',
  boundStoreName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  defaultWorkspace: '/store',
  permissionVersion: 3,
}

interface ExportRequestLog {
  dashboardUrls: string[]
  downloadUrls: Record<string, string[]>
}

const csvPaths = [
  '/api/export/profit-ranking.csv',
  '/api/export/expenses.csv',
  '/api/export/salaries.csv',
] as const

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}

function dashboardFor(url: URL) {
  const requestedStoreId = url.searchParams.get('storeId') || ''
  const requestedBrandId = url.searchParams.get('brandId') || ''
  const filteredEntries = entries.filter((entry) => (
    (!requestedStoreId || entry.storeId === requestedStoreId)
    && (!requestedBrandId || String(entry.brandId) === requestedBrandId)
  ))
  const income = filteredEntries.reduce((sum, entry) => sum + entry.income, 0)
  const net = filteredEntries.reduce((sum, entry) => sum + entry.net, 0)

  return {
    months: ['2026-07', '2026-06'],
    brands,
    summary: {
      month: url.searchParams.get('month') || '2026-07',
      storeCount: new Set(filteredEntries.map((entry) => entry.storeId)).size,
      entryCount: filteredEntries.length,
      sales: filteredEntries.reduce((sum, entry) => sum + entry.sales, 0),
      income,
      costSum: filteredEntries.reduce((sum, entry) => sum + entry.costSum, 0),
      expenseSum: filteredEntries.reduce((sum, entry) => sum + entry.expenseSum, 0),
      net,
      margin: income ? net / income : 0,
      riskStoreCount: 0,
    },
    entries: filteredEntries,
    trend: [],
  }
}

async function prepareExportPage(page: Page, session: Record<string, unknown>) {
  const requests: ExportRequestLog = {
    dashboardUrls: [],
    downloadUrls: Object.fromEntries(csvPaths.map((path) => [path, []])),
  }

  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-DATA-EXPORT-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const url = new URL(route.request().url())
    const path = url.pathname

    if (path === '/api/auth/me') return route.fulfill(ok(session))
    if (path === '/api/stores') return route.fulfill(ok(stores))
    if (path === '/api/finance/dashboard') {
      requests.dashboardUrls.push(url.toString())
      return route.fulfill(ok(dashboardFor(url)))
    }
    if (csvPaths.includes(path as typeof csvPaths[number])) {
      requests.downloadUrls[path].push(url.toString())
      return route.fulfill({
        status: 200,
        contentType: 'text/csv; charset=utf-8',
        headers: { 'content-disposition': 'attachment; filename="legacy-export.csv"' },
        body: '门店,月份\n荆州之星店,2026-07\n',
      })
    }
    return route.fulfill(ok([]))
  })

  return requests
}

async function downloadCsv(page: Page, buttonName: string, pathname: string) {
  const response = page.waitForResponse((candidate) => (
    new URL(candidate.url()).pathname === pathname && candidate.status() === 200
  ))
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: buttonName, exact: true }).click()
  await response
  return download
}

function assertScopedCsvRequests(requests: ExportRequestLog, storeId: string, brandId: string) {
  for (const pathname of csvPaths) {
    expect(requests.downloadUrls[pathname], `${pathname} should be downloaded once`).toHaveLength(1)
    const url = new URL(requests.downloadUrls[pathname][0])
    expect(url.searchParams.get('storeId')).toBe(storeId)
    expect(url.searchParams.get('brandId')).toBe(brandId)
    expect(url.searchParams.get('month')).toBe('2026-07')
  }
}

test('带门店参数进入导出页后，范围摘要与三项 CSV 请求保持一致', async ({ page }) => {
  const requests = await prepareExportPage(page, bossSession)

  await page.goto('/export?storeId=rg1')
  const toolbar = page.getByLabel('导出筛选')

  await expect(page).toHaveURL(/\/export\?storeId=rg1/)
  await expect(page.getByLabel('全局门店')).toHaveCount(0)
  await expect(toolbar.getByText('荆州之星店 · 茹菓 · 2026-07', { exact: true })).toBeVisible()
  await expect(page.getByLabel('当前品牌')).toContainText('茹菓')

  const tasks = page.getByRole('list', { name: '导出任务' }).getByRole('listitem')
  await expect(tasks).toHaveCount(3)
  for (let index = 0; index < 3; index += 1) {
    await expect(tasks.nth(index)).toContainText('荆州之星店 · 茹菓 · 2026-07')
  }

  await expect(page.getByRole('button', { name: '下载利润 CSV', exact: true })).toBeEnabled()
  const profitDownload = await downloadCsv(page, '下载利润 CSV', '/api/export/profit-ranking.csv')
  const expenseDownload = await downloadCsv(page, '下载报销 CSV', '/api/export/expenses.csv')
  const salaryDownload = await downloadCsv(page, '下载工资 CSV', '/api/export/salaries.csv')

  assertScopedCsvRequests(requests, 'rg1', '1')
  expect(profitDownload.suggestedFilename()).toBe('门店利润_荆州之星店_茹菓_2026-07.csv')
  expect(expenseDownload.suggestedFilename()).toBe('报销记录_荆州之星店_茹菓_2026-07.csv')
  expect(salaryDownload.suggestedFilename()).toBe('员工工资_荆州之星店_茹菓_2026-07.csv')
  expect(requests.dashboardUrls.length).toBeGreaterThan(0)
  expect(requests.dashboardUrls.every((request) => new URL(request).searchParams.get('storeId') === 'rg1')).toBe(true)
  await expect(page.getByText('员工工资 CSV 已开始下载。', { exact: true })).toBeVisible()
})

test('即使误配 finance.export，店长也不能进入或请求数据导出', async ({ page }) => {
  const requests = await prepareExportPage(page, managerSession)

  await page.goto('/profit')
  await expect(page.locator('.app-sidebar--desktop')).not.toContainText('数据导出')
  const dashboardRequestCount = requests.dashboardUrls.length

  await page.goto('/export?storeId=other-store&brandId=2')

  await expect(page).toHaveURL(/\/no-permission\?from=/)
  await expect(page.getByText('当前账号没有访问该页面的权限')).toBeVisible()
  expect(requests.dashboardUrls).toHaveLength(dashboardRequestCount)
  for (const pathname of csvPaths) {
    expect(requests.downloadUrls[pathname]).toHaveLength(0)
  }
})

test('导出范围满 60 秒后在前台自动更新并合并焦点事件', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-07-25T00:00:00Z') })
  const requests = await prepareExportPage(page, bossSession)

  await page.goto('/export?storeId=rg1')
  await expect(page.getByLabel('导出筛选')).toContainText('荆州之星店 · 茹菓 · 2026-07')
  const initialRequestCount = requests.dashboardUrls.length
  await expect(page.getByRole('button', { name: /刷新|重新加载|重新读取/ })).toHaveCount(0)

  await page.clock.runFor(59_000)
  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.waitForTimeout(100)
  expect(requests.dashboardUrls).toHaveLength(initialRequestCount)

  await page.clock.runFor(1_000)
  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => requests.dashboardUrls.length).toBe(initialRequestCount + 1)
  const refreshedDashboard = new URL(requests.dashboardUrls.at(-1) || '')
  expect(refreshedDashboard.searchParams.get('storeId')).toBe('rg1')
  expect(refreshedDashboard.searchParams.get('brandId')).toBeNull()
  await page.waitForTimeout(100)
  expect(requests.dashboardUrls).toHaveLength(initialRequestCount + 1)
})

test('门店目录加载失败不会把导出范围标记为最新，恢复前台时会立即重试', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-07-25T00:00:00Z') })
  await prepareExportPage(page, bossSession)
  let failStoreCatalog = true
  let storeCatalogRequests = 0

  await page.route('**/api/stores', (route) => {
    storeCatalogRequests += 1
    if (failStoreCatalog) {
      return route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, message: '受控门店目录读取失败' }),
      })
    }
    return route.fulfill(ok(stores))
  })

  await page.goto('/export?storeId=rg1')
  await expect(page.getByRole('alert')).toContainText('门店目录加载失败，请稍后重试。')
  const requestsAfterFailure = storeCatalogRequests
  expect(requestsAfterFailure).toBeGreaterThan(0)

  failStoreCatalog = false
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))

  await expect.poll(() => storeCatalogRequests).toBeGreaterThan(requestsAfterFailure)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByLabel('导出筛选')).toContainText('荆州之星店 · 茹菓 · 2026-07')
})

test('前台自动更新失败时保留当前范围并显示业务化失败提示', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-07-25T00:00:00Z') })
  await prepareExportPage(page, bossSession)

  await page.goto('/export?storeId=rg1')
  const toolbar = page.getByLabel('导出筛选')
  await expect(toolbar).toContainText('荆州之星店 · 茹菓 · 2026-07')

  await page.route('**/api/finance/dashboard**', (route) => route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ success: false, message: '系统处理失败' }),
  }))
  await page.clock.runFor(60_000)
  await page.evaluate(() => {
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })

  await expect(page.getByRole('alert')).toContainText('利润数据加载失败，请稍后重试。')
  await expect(toolbar).toContainText('荆州之星店 · 茹菓 · 2026-07')
})

test('390px 下工具栏和导出任务可换行且页面无横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await prepareExportPage(page, bossSession)

  await page.goto('/export?storeId=rg1')
  await expect(page.getByLabel('导出筛选')).toContainText('荆州之星店 · 茹菓 · 2026-07')
  await expectNoWholePageOverflow(page, '390px 数据导出页')

  const tasks = page.getByRole('list', { name: '导出任务' }).getByRole('listitem')
  await expect(tasks).toHaveCount(3)
  const layout = await tasks.evaluateAll((items) => items.map((item) => {
    const itemRect = item.getBoundingClientRect()
    const contentRect = item.querySelector('.export-task-content')?.getBoundingClientRect()
    const buttonRect = item.querySelector('button')?.getBoundingClientRect()
    return {
      itemRight: itemRect.right,
      contentBottom: contentRect?.bottom || 0,
      buttonTop: buttonRect?.top || 0,
      buttonRight: buttonRect?.right || 0,
      buttonWidth: buttonRect?.width || 0,
    }
  }))

  for (const task of layout) {
    expect(task.itemRight).toBeLessThanOrEqual(391)
    expect(task.buttonRight).toBeLessThanOrEqual(391)
    expect(task.buttonWidth).toBeGreaterThan(250)
    expect(task.buttonTop).toBeGreaterThanOrEqual(task.contentBottom)
  }
})
