import { expect, test, type Page } from '@playwright/test'

const managerSession = {
  id: 7,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '荆州之星店店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['rg1'],
  permissions: ['store.read', 'finance.profit.read', 'finance.profit.write', 'finance.export', 'expense.read', 'expense.create'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    FINANCE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
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
  dataScopes: { STORE: { mode: 'ALL', storeIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
}

const stores = [
  { id: 'rg1', code: 'rg1', name: '荆州之星店', brandId: 1, brandName: '茹菓' },
  { id: 'rg2', code: 'rg2', name: '万达二店', brandId: 1, brandName: '茹菓' },
]

const entry = {
  id: 11,
  storeId: 'rg1',
  storeCode: 'rg1',
  storeName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  month: '2026-07',
  sales: 160871,
  refund: 1800,
  discount: 3200,
  income: 155871,
  material: 52000,
  packaging: 5000,
  loss: 1800,
  costOther: 1200,
  costSum: 60000,
  rent: 10000,
  labor: 28797,
  utility: 2800,
  property: 1200,
  commission: 4200,
  promo: 2100,
  repair: 500,
  equip: 0,
  expOther: 900,
  expenseSum: 50500,
  net: 45371,
  margin: 0.2911,
}

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

async function prepare(page: Page, session: typeof managerSession | typeof bossSession, dashboardRequests: string[]) {
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-SCOPE-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(session))
    if (url.pathname === '/api/finance/dashboard') {
      dashboardRequests.push(url.toString())
      const managerRows = session.role === 'STORE_MANAGER' ? [entry] : [entry, { ...entry, id: 12, storeId: 'rg2', storeCode: 'rg2', storeName: '万达二店', sales: 120000, income: 116000, net: 25000 }]
      return route.fulfill(ok({
        months: ['2026-07', '2026-06'],
        brands: [{ id: 1, name: '茹菓', color: '#76bdb8' }],
        summary: {
          month: url.searchParams.get('month') || '2026-07',
          storeCount: managerRows.length,
          entryCount: managerRows.length,
          sales: managerRows.reduce((sum, row) => sum + Number(row.sales), 0),
          income: managerRows.reduce((sum, row) => sum + Number(row.income), 0),
          costSum: managerRows.reduce((sum, row) => sum + Number(row.costSum), 0),
          expenseSum: managerRows.reduce((sum, row) => sum + Number(row.expenseSum), 0),
          net: managerRows.reduce((sum, row) => sum + Number(row.net), 0),
          margin: 0.25,
          riskStoreCount: 0,
        },
        entries: managerRows,
        trend: [
          { month: '2026-06', sales: 148000, income: 143000, net: 38000, margin: 0.265 },
          { month: '2026-07', sales: entry.sales, income: entry.income, net: entry.net, margin: entry.margin },
        ],
      }))
    }
    if (url.pathname === '/api/finance/entries') return route.fulfill(ok([entry]))
    if (url.pathname === '/api/finance/months') return route.fulfill(ok(['2026-07', '2026-06']))
    if (url.pathname === '/api/brands') return route.fulfill(ok([{ id: 1, code: 'RG', name: '茹菓' }]))
    if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
    if (url.pathname === '/api/expenses') return route.fulfill(ok([{ id: 'e1', storeId: 'rg1', storeName: '荆州之星店', brandId: 1, brandName: '茹菓', month: '2026-07', amount: 320, status: '草稿', reason: '设备维护' }]))
    return route.fulfill(ok([]))
  })
}

test('店长业务页只使用登录会话中的唯一门店范围', async ({ page }) => {
  const dashboardRequests: string[] = []
  await prepare(page, managerSession, dashboardRequests)

  await page.goto('/profit?month=2026-07&brandId=999&storeId=OTHER')
  await expect(page.getByRole('heading', { name: '本店经营概览', level: 1 })).toBeVisible()
  await expect(page.getByLabel('当前经营范围')).toContainText('荆州之星店 · 茹菓')
  await expect(page.getByLabel('全局门店')).toHaveCount(0)
  await expect(page.getByLabel('品牌', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('门店', { exact: true })).toHaveCount(0)
  await expect(page.locator('option', { hasText: '全部品牌' })).toHaveCount(0)
  await expect(page.locator('option', { hasText: '全部门店' })).toHaveCount(0)
  await expect(page.getByText('本月营业额')).toBeVisible()
  await expect(page.getByText('实收收入')).toBeVisible()
  await expect(page.getByText('近6个月营业额与净利润趋势')).toBeVisible()
  await expect(page.getByText('品牌卡片')).toHaveCount(0)
  await expect(page.getByText(/各店.*排名/)).toHaveCount(0)
  await expect(page).not.toHaveURL(/OTHER|brandId|storeId/)
  expect(dashboardRequests.some((request) => request.includes('brandId=1') && request.includes('storeId=rg1'))).toBe(true)
  expect(dashboardRequests.every((request) => !request.includes('OTHER') && !request.includes('999'))).toBe(true)

  await page.goto('/profit-table?mode=summary&brandId=999&storeId=OTHER&month=2026-07')
  await expect(page.getByRole('heading', { name: '本店利润表', level: 1 })).toBeVisible()
  await expect(page.getByRole('tablist', { name: '利润表模式' })).toHaveCount(0)
  await expect(page.getByLabel('品牌', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('门店', { exact: true })).toHaveCount(0)
  await expect(page).not.toHaveURL(/OTHER|brandId|storeId|mode=summary/)

  await page.goto('/data-entry')
  await expect(page.getByLabel('当前录入门店')).toContainText('荆州之星店 · 2026-07')
  await expect(page.getByLabel('品牌', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('门店', { exact: true })).toHaveCount(0)

  await page.goto('/expenses')
  await expect(page.getByLabel('当前经营范围')).toContainText('荆州之星店 · 茹菓')
  await expect(page.getByLabel('品牌', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('门店', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '新增报销' }).click()
  await expect(page.getByText('报销门店')).toBeVisible()
  await expect(page.getByRole('dialog', { name: '新增报销' }).getByRole('combobox')).toHaveCount(0)

  await page.goto('/export')
  await expect(page.getByRole('heading', { name: '本店数据导出', level: 1 })).toBeVisible()
  await expect(page.getByLabel('品牌', { exact: true })).toHaveCount(0)
  await expect(page.getByText('本店月度利润明细')).toBeVisible()
})

test('老板仍保留品牌、门店和全部范围选择', async ({ page }) => {
  await prepare(page, bossSession, [])
  await page.goto('/profit?month=2026-07')

  await expect(page.getByRole('heading', { name: '利润概览', level: 1 })).toBeVisible()
  await expect(page.getByLabel('全局门店')).toBeVisible()
  await expect(page.getByLabel('品牌', { exact: true })).toBeVisible()
  await expect(page.getByLabel('门店', { exact: true })).toBeVisible()
  await expect(page.getByLabel('品牌', { exact: true }).locator('option', { hasText: '全部品牌' })).toHaveCount(1)
  await expect(page.getByLabel('门店', { exact: true }).locator('option', { hasText: '全部门店' })).toHaveCount(1)
  await expect(page.getByText('品牌卡片')).toBeVisible()
  await expect(page.getByText(/各店净利率排名/)).toBeVisible()
})

test('绑定多个门店的店长会被所有业务路由统一拒绝', async ({ page }) => {
  const invalidSession = {
    ...managerSession,
    storeScope: ['rg1', 'rg2'],
    dataScopes: {
      ...managerSession.dataScopes,
      STORE: { mode: 'OWN_STORE', storeIds: ['rg1', 'rg2'] },
    },
    dataScope: { mode: 'OWN_STORE', storeIds: ['rg1', 'rg2'] },
  }
  await prepare(page, invalidSession, [])

  for (const path of ['/profit', '/data-entry', '/expenses', '/export']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/no-permission\?reason=STORE_NOT_BOUND/)
    await expect(page.getByText('当前店长账号绑定了多个门店')).toBeVisible()
  }
})
