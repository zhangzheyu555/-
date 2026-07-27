import { expect, test, type Route } from '@playwright/test'

const session = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['finance.profit.read', 'finance.profit.export'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const stores = [
  { id: 'rg1', code: 'rg1', name: '荆州之星店', brandId: 1, brandName: '茹菓', status: '营业中' },
  { id: 'rg13', code: 'rg13', name: '荆江之星店', brandId: 1, brandName: '茹菓', status: '营业中' },
  { id: 'rg14', code: 'rg14', name: '菰果奶茶14号店', brandId: 1, brandName: '茹菓', status: '营业中' },
]

function profitEntry(storeId: string, storeName: string, month: string) {
  return {
    id: `${storeId}-${month}`,
    storeId,
    storeCode: storeId,
    storeName,
    brandId: 1,
    brandName: '茹菓',
    month,
    sales: 100000,
    refund: 0,
    discount: 0,
    income: 100000,
    material: 20000,
    packaging: 1000,
    loss: 200,
    costOther: 0,
    costSum: 21200,
    rent: 10000,
    labor: 20000,
    utility: 1000,
    property: 0,
    commission: 0,
    promo: 0,
    repair: 0,
    equip: 0,
    expOther: 0,
    expenseSum: 31000,
    net: 47800,
    margin: 0.478,
  }
}

const julyEntries = stores.map((store) => profitEntry(store.id, store.name, '2026-07'))
const mayEntries = [profitEntry('rg13', '荆江之星店', '2026-05')]

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, code: 'OK', message: 'OK', data }),
})

async function fulfillApi(route: Route) {
  const url = new URL(route.request().url())
  const month = url.searchParams.get('month') || '2026-07'
  const monthEntries = month === '2026-05' ? mayEntries : julyEntries
  const requestedStoreId = url.searchParams.get('storeId')
  const entries = requestedStoreId
    ? monthEntries.filter((entry) => entry.storeId === requestedStoreId)
    : monthEntries

  if (url.pathname === '/api/auth/me') return route.fulfill(ok(session))
  if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
  if (url.pathname === '/api/brands') return route.fulfill(ok([{ id: 1, code: 'RG', name: '茹菓' }]))
  if (url.pathname === '/api/finance/months') return route.fulfill(ok(['2026-07', '2026-06', '2026-05']))
  if (url.pathname === '/api/finance/entries') return route.fulfill(ok(entries))
  if (url.pathname === '/api/finance/dashboard') {
    return route.fulfill(ok({
      months: ['2026-07', '2026-06', '2026-05'],
      brands: [{ id: 1, code: 'RG', name: '茹菓' }],
      summary: {
        month,
        storeCount: entries.length,
        entryCount: entries.length,
        sales: entries.reduce((sum, entry) => sum + entry.sales, 0),
        income: entries.reduce((sum, entry) => sum + entry.income, 0),
        costSum: entries.reduce((sum, entry) => sum + entry.costSum, 0),
        expenseSum: entries.reduce((sum, entry) => sum + entry.expenseSum, 0),
        net: entries.reduce((sum, entry) => sum + entry.net, 0),
        margin: entries.length ? 0.478 : 0,
        riskStoreCount: 0,
      },
      entries,
      trend: [],
    }))
  }
  return route.fulfill(ok([]))
}

test('切换到只有一家有数据的月份后仍可选择同品牌全部门店', async ({ page }) => {
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'PROFIT-TABLE-MONTH-STORE-TEST')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, fulfillApi)

  await page.goto('/profit-table?month=2026-07&brandId=1&storeId=rg13')
  await expect(page.getByRole('heading', { name: '利润表', level: 1 })).toBeVisible()

  await page.getByLabel('月份', { exact: true }).selectOption('2026-05')
  await expect(page).toHaveURL(/month=2026-05/)

  const storeSearch = page.getByRole('combobox', { name: '门店' })
  await storeSearch.click()
  await expect(page.getByRole('option', { name: /荆州之星店/ })).toBeVisible()
  await expect(page.getByRole('option', { name: /荆江之星店/ })).toBeVisible()
  await expect(page.getByRole('option', { name: /菰果奶茶14号店/ })).toBeVisible()

  await page.getByRole('option', { name: /荆州之星店/ }).click()
  await expect(page).toHaveURL(/storeId=rg1/)
  await expect(storeSearch).toHaveValue('茹菓 · 荆州之星店')
  await expect(page.getByText('荆州之星店 2026-05 暂无数据')).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(storeSearch).toBeVisible()
  await expect.poll(() => page.evaluate(() => (
    document.documentElement.scrollWidth <= document.documentElement.clientWidth
  ))).toBe(true)
})
