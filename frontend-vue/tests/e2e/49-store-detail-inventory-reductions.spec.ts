import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '库存减少记录测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['store.read', 'finance.read', 'inspection.read'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const store = {
  id: 'rg4',
  code: 'RG004',
  name: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  area: '荆州',
  manager: '测试店长',
  managerPhone: '13800000000',
  openDate: '2025-05-01',
  status: 'ACTIVE',
}

const dashboard = {
  months: ['2026-07'],
  brands: [{ id: 1, code: 'RG', name: '茹菓' }],
  summary: {
    month: '2026-07',
    storeCount: 1,
    entryCount: 1,
    sales: 10000,
    income: 10000,
    costSum: 5000,
    expenseSum: 2000,
    net: 3000,
    margin: 0.3,
    riskStoreCount: 0,
  },
  entries: [{
    storeId: 'rg4',
    storeCode: 'RG004',
    storeName: '荆州之星店',
    brandId: 1,
    brandName: '茹菓',
    month: '2026-07',
    sales: 10000,
    income: 10000,
    costSum: 5000,
    expenseSum: 2000,
    net: 3000,
    margin: 0.3,
  }],
  trend: [],
}

const inventoryReductions = {
  storeId: 'rg4',
  storeName: '荆州之星店',
  month: '2026-07',
  movementCount: 2,
  itemCount: 2,
  truncated: false,
  rows: [
    {
      id: 1,
      itemId: 23,
      itemCode: 'DAILY_LOSS_023',
      itemName: '葡萄',
      unit: '斤',
      quantityReduced: 0.2031,
      currentQuantity: 9.7969,
      sourceType: 'DAILY_LOSS',
      sourceLabel: '每日报损',
      sourceId: 'DLR-TEST',
      note: '日常报损',
      operatorName: '老板',
      createdAt: '2026-07-28T13:52:10',
    },
    {
      id: 2,
      itemId: 24,
      itemCode: 'DAILY_LOSS_024',
      itemName: '荔枝',
      unit: '斤',
      quantityReduced: 0.11,
      currentQuantity: 9.89,
      sourceType: 'DAILY_LOSS',
      sourceLabel: '每日报损',
      sourceId: 'DLR-TEST',
      note: '日常报损',
      operatorName: '老板',
      createdAt: '2026-07-28T13:52:10',
    },
  ],
}

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}

async function prepare(page: Page) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'STORE-INVENTORY-REDUCTION-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, bossSession)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (path === '/api/stores') return route.fulfill(ok([store]))
    if (path === '/api/finance/dashboard') return route.fulfill(ok(dashboard))
    if (path === '/api/stores/rg4/inventory-reductions') return route.fulfill(ok(inventoryReductions))
    if (path === '/api/inspections') return route.fulfill(ok([]))
    return route.fulfill(ok([]))
  })
}

test('利润概览按所选门店显示库存减少记录，并适配 390px 手机宽度', async ({ page }, testInfo) => {
  await prepare(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/profit?storeId=rg4&month=2026-07')

  const card = page.getByRole('region', { name: '库存减少记录' })
  await expect(card).toBeVisible()
  await expect(card).toContainText('荆州之星店 · 2026-07')
  await expect(card).toContainText('2 笔减少')
  await expect(card).toContainText('2 种物料')
  await expect(card).toContainText('葡萄')
  await expect(card).toContainText('-0.2031 斤')
  await expect(card).toContainText('荔枝')
  await expect(card).toContainText('-0.11 斤')
  await expect(card).toContainText('每日报损')

  await expectNoWholePageOverflow(page, '390px 门店库存减少记录')
  const tableOverflow = await card.locator('.inventory-reduction-table-wrap').evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
    overflowX: getComputedStyle(element).overflowX,
  }))
  expect(tableOverflow.scrollWidth).toBeGreaterThan(tableOverflow.clientWidth)
  expect(tableOverflow.overflowX).toBe('auto')

  await card.screenshot({
    path: testInfo.outputPath('profit-store-inventory-mobile.png'),
  })
})
