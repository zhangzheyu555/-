import { expect, test, type Page, type Route } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const managerSession = {
  id: 7,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '荆州之星店店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['rg1'],
  permissions: [
    'store.read',
    'finance.profit.read',
    'finance.export',
    'salary.read',
    'salary.edit',
    'warehouse.store.read',
    'warehouse.requisition.create',
    'warehouse.requisition.receive',
    'assistant.use',
  ],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    FINANCE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    SALARY: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
  },
  dataScope: { mode: 'OWN_STORE', storeIds: ['rg1'] },
  boundStoreId: 'rg1',
  boundStoreName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  defaultWorkspace: '/store',
  permissionVersion: 3,
}

const bossSession = {
  ...managerSession,
  id: 1,
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
    SALARY: { mode: 'ALL', storeIds: [] },
    WAREHOUSE: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
}

const stores = [
  { id: 'rg1', code: 'rg1', name: '荆州之星店', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
  { id: 'other-store', code: 'other-store', name: '其他门店', brandId: 2, brandName: '其他品牌', status: 'ACTIVE' },
]

interface RequestLog {
  urls: string[]
  assistantBody: Record<string, unknown> | null
}

async function prepare(page: Page, session: typeof managerSession | typeof bossSession) {
  const requests: RequestLog = { urls: [], assistantBody: null }
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'SINGLE-STORE-TEST-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => fulfillApi(route, session, requests))
  return requests
}

async function fulfillApi(
  route: Route,
  session: typeof managerSession | typeof bossSession,
  requests: RequestLog,
) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname
  requests.urls.push(request.url())

  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/stores') return route.fulfill(ok(stores))
  if (path === '/api/finance/months') return route.fulfill(ok(['2026-07']))
  if (path === '/api/finance/entries') return route.fulfill(ok([]))
  if (path === '/api/finance/dashboard') {
    return route.fulfill(ok({
      months: ['2026-07'],
      brands: [],
      summary: { month: '2026-07' },
      entries: [],
      trend: [],
    }))
  }
  if (path === '/api/salaries/employee-page') {
    return route.fulfill(ok({
      content: [],
      totalElements: 0,
      totalPages: 1,
      page: 1,
      size: 20,
      statusCounts: {},
      summary: { grossTotal: 0, commissionTotal: 0 },
      workHoursTotal: 0,
      vacationBalanceTotal: 0,
    }))
  }
  if (path === '/api/warehouse/overview') {
    return route.fulfill(ok({
      summary: {
        itemCount: 0,
        lowStockCount: 0,
        expiringCount: 0,
        pendingRequisitionCount: 0,
        pendingReceiptCount: 0,
        pendingPurchaseCount: 0,
        stockValue: 0,
      },
      alerts: [],
      items: [],
      requisitions: [],
    }))
  }
  if (path === '/api/warehouse/item-categories' || path === '/api/warehouse/returns' || path === '/api/inspections') {
    return route.fulfill(ok([]))
  }
  if (path === '/api/assistant/status') {
    return route.fulfill(ok({
      enabled: true,
      configured: false,
      provider: 'DeepSeek',
      model: '',
      baseUrlHost: '',
      timeout: 30,
      lastSuccessAt: null,
      lastErrorCode: 'NOT_CONFIGURED',
    }))
  }
  if (path === '/api/assistant/chat') {
    requests.assistantBody = request.postDataJSON()
    return route.fulfill(ok({
      question: String(requests.assistantBody?.message || ''),
      selectedMode: 'LOCAL',
      selectionReason: '按门店范围查询真实经营数据',
      localData: {
        summary: '当前月份暂无经营数据。',
        metrics: [],
        dataPeriod: '2026-07',
        dataScope: '荆州之星店',
        source: '经营数据库',
        dataVersion: 'test-v1',
        calculationVersion: 'test-v1',
        updatedAt: '2026-07-13T00:00:00Z',
      },
      aiAnalysis: {
        available: false,
        provider: '',
        model: '',
        requestId: '',
        latencyMs: 0,
        summary: '',
        findings: [],
        risks: [],
        possibleCauses: [],
        actions: [],
        confidence: '',
        limitations: [],
      },
      fallbackUsed: false,
      error: null,
    }))
  }
  return route.fulfill(ok([]))
}

function matchingUrl(requests: RequestLog, pathname: string) {
  return requests.urls.map((url) => new URL(url)).find((url) => url.pathname === pathname)
}

test('store manager salary and warehouse pages expose only current-store controls', async ({ page }) => {
  const requests = await prepare(page, managerSession)

  await page.goto('/store/salary?storeId=other-store&brandId=2')
  const salary = page.locator('.salary-workbench')
  await expect(salary.getByRole('heading', { name: '荆州之星店 · 员工工资' })).toBeVisible()
  await expect(salary.getByLabel('月份')).toBeVisible()
  await expect(salary.getByLabel('工资状态')).toBeVisible()
  await expect(salary.getByLabel('搜索工资记录')).toBeVisible()
  await expect(salary.getByLabel('门店')).toHaveCount(0)
  await expect(salary).not.toContainText('全部门店')
  await expect(salary).not.toContainText('全部品牌')

  const salaryUrl = matchingUrl(requests, '/api/salaries/employee-page')
  expect(salaryUrl?.searchParams.get('storeId')).toBe('rg1')
  expect(salaryUrl?.searchParams.get('brandId')).toBe('1')

  await page.goto('/store/inventory')
  const warehouse = page.locator('.warehouse-page')
  await expect(warehouse.getByRole('link', { name: '本店库存', exact: true })).toBeVisible()
  await expect(warehouse.getByRole('link', { name: '门店叫货', exact: true })).toBeVisible()
  await expect(warehouse.getByRole('link', { name: '本店记录', exact: true })).toBeVisible()
  await expect(warehouse).not.toContainText('采购入库')
  await expect(warehouse).not.toContainText('物料档案')
  await expect(warehouse).not.toContainText('总仓管理')
})

test('store manager detail and assistant ignore another store from the URL and requests', async ({ page }) => {
  const requests = await prepare(page, managerSession)

  await page.goto('/store?storeId=other-store')
  const detail = page.locator('.store-detail-page')
  await expect(detail.getByRole('heading', { name: '荆州之星店详情' })).toBeVisible()
  await expect(detail.getByText('茹菓 · 门店编号 rg1 · 营业中')).toBeVisible()
  await expect(detail.locator('.fixed-store-chip')).toHaveCount(0)
  await expect(detail.getByLabel('品牌')).toHaveCount(0)
  await expect(detail.getByLabel('门店')).toHaveCount(0)
  await expect(detail.getByText('月份', { exact: true })).toBeVisible()

  const dashboardUrl = matchingUrl(requests, '/api/finance/dashboard')
  expect(dashboardUrl?.searchParams.get('storeId')).toBe('rg1')
  expect(dashboardUrl?.searchParams.get('brandId')).toBe('1')

  await page.goto('/assistant?storeId=other-store')
  const assistant = page.locator('.store-assistant-page')
  await expect(assistant.getByText('荆州之星店 · 茹菓')).toBeVisible()
  await expect(assistant.locator('.context-bar').getByText('门店', { exact: true })).toHaveCount(0)
  await expect(assistant.locator('.context-bar').getByText('月份', { exact: true })).toBeVisible()
  await assistant.getByLabel('经营问题').fill('本月净利润如何')
  await assistant.getByRole('button', { name: '发送' }).click()
  await expect.poll(() => requests.assistantBody).not.toBeNull()
  expect(requests.assistantBody).toMatchObject({ storeId: 'rg1', month: '2026-07' })

  const financeUrl = requests.urls
    .map((url) => new URL(url))
    .find((url) => url.pathname === '/api/finance/entries' && url.searchParams.has('storeId'))
  expect(financeUrl?.searchParams.get('storeId')).toBe('rg1')
})

test('store manager cannot enter store management even with a stale store.manage permission', async ({ page }) => {
  await prepare(page, {
    ...managerSession,
    permissions: [...managerSession.permissions, 'store.manage'],
  })

  await page.goto('/stores')

  await expect.poll(() => new URL(page.url()).pathname).toBe('/store')
  await expect(page.getByRole('alert')).toHaveText('当前账号无权进入门店管理，已返回本店工作台。')
  await expect(page.locator('.app-sidebar--desktop')).not.toContainText('门店管理')
  await expect(page.getByRole('button', { name: '清空全部数据' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0)
})

test('store management remains hidden from the store manager mobile navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await prepare(page, {
    ...managerSession,
    permissions: [...managerSession.permissions, 'store.manage'],
  })

  await page.goto('/stores')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/store')
  await expect(page.getByRole('alert')).toHaveText('当前账号无权进入门店管理，已返回本店工作台。')

  await page.getByRole('button', { name: '打开菜单' }).click()
  await expect(page.locator('.app-sidebar--mobile')).not.toContainText('门店管理')
  await expect(page.getByRole('button', { name: '清空全部数据' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '删除' })).toHaveCount(0)
})

test('boss retains store and brand selectors on the same pages', async ({ page }) => {
  await prepare(page, bossSession)

  await page.goto('/stores')
  await expect(page.getByRole('button', { name: '清空全部数据' })).toBeVisible()
  await expect(page.getByRole('button', { name: '编辑' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '停用' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '删除' }).first()).toBeVisible()

  await page.goto('/store-detail')
  await expect(page.locator('.store-detail-page').getByLabel('品牌')).toBeVisible()
  await expect(page.locator('.store-detail-page').getByLabel('门店', { exact: true })).toBeVisible()

  await page.goto('/assistant')
  await expect(page.locator('.store-assistant-page').getByText('门店', { exact: true })).toBeVisible()

  await page.goto('/finance/salary')
  await expect(page.locator('.salary-workbench').getByLabel('门店')).toBeVisible()
})
