import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const boss = {
  id: 981,
  tenantId: 1,
  tenantName: '日报损工具栏合成租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['daily_loss.read', 'daily_loss.export'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

async function prepare(page: Page) {
  const reportQueries: string[] = []
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'DAILY-LOSS-BRAND-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, boss)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(boss))
    if (url.pathname === '/api/stores') return route.fulfill(ok([
      { id: 's1', code: 'S1', name: '品牌甲一店', brandId: 1, brandName: '品牌甲' },
      { id: 's2', code: 'S2', name: '品牌甲二店', brandId: 1, brandName: '品牌甲' },
      { id: 's3', code: 'S3', name: '品牌乙一店', brandId: 2, brandName: '品牌乙' },
    ]))
    if (url.pathname === '/api/daily-loss/items') return route.fulfill(ok([]))
    if (url.pathname === '/api/daily-loss/reports') {
      reportQueries.push(url.search)
      return route.fulfill(ok([]))
    }
    return route.fulfill(ok([]))
  })
  return reportQueries
}

test('daily-loss brand selector filters stores and keeps desktop toolbar aligned', async ({ page }) => {
  const reportQueries = await prepare(page)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/daily-loss')

  const brand = page.getByLabel('品牌', { exact: true })
  const store = page.getByLabel('门店', { exact: true })
  const month = page.getByLabel('月份', { exact: true })
  const exportButton = page.getByRole('button', { name: '导出本月报损 Excel', exact: true })

  await expect(brand).toBeVisible()
  await expect(store).toBeVisible()
  await expect(month).toBeVisible()
  await expect(exportButton).toBeVisible()
  await expect(brand.locator('option')).toHaveText(['全部品牌', '品牌甲', '品牌乙'])

  const boxes = await Promise.all([brand, store, month, exportButton].map((locator) => locator.boundingBox()))
  expect(boxes.every(Boolean)).toBe(true)
  const controlBottoms = boxes.map((box) => Math.round((box?.y || 0) + (box?.height || 0)))
  expect(Math.max(...controlBottoms) - Math.min(...controlBottoms)).toBeLessThanOrEqual(2)

  await brand.selectOption('2')
  await expect(store).toHaveValue('s3')
  await expect(store.locator('option')).toHaveText(['请选择门店', '品牌乙 · 品牌乙一店'])
  await expect.poll(() => reportQueries.some((query) => query.includes('storeId=s3'))).toBe(true)

  await brand.selectOption('')
  await expect(store).toHaveValue('')
  await expect(store.locator('option')).toHaveText([
    '全部门店',
    '品牌甲 · 品牌甲一店',
    '品牌甲 · 品牌甲二店',
    '品牌乙 · 品牌乙一店',
  ])
  await expect.poll(() => reportQueries.some((query) => !query.includes('storeId='))).toBe(true)
  await expectNoWholePageOverflow(page, '1280px 每日报损品牌筛选工具栏')
})
