import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const boss = {
  id: 901,
  tenantId: 1,
  tenantName: 'H2 合成租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['platform.read', 'platform.manage'],
  dataScopes: { PLATFORM: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

async function prepare(page: Page, requested: string[]) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'QMAI-DESKTOP-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, boss)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(boss))
    if (url.pathname === '/api/qmai/config') {
      return route.fulfill(ok({ configured: false, brand: 'ruguo', openKeySet: false, consoleTokenSet: false }))
    }
    if (url.pathname === '/api/qmai/recipe-usage') {
      requested.push(`${url.pathname}?${url.searchParams.toString()}`)
      expect(request.headers().authorization).toBe('Bearer QMAI-DESKTOP-E2E')
      return route.fulfill(ok({
        month: '2026-07',
        matchedProductCount: 1,
        calculation: {
          totalCups: 2.5,
          fruits: [{ fruit: '芒果', netGrams: 250, rawGrams: 500, rawJin: 1, approximate: false }],
        },
      }))
    }
    return route.fulfill(ok([]))
  })
}

test('1280px 物料用量只读取服务端快照，不渲染本地可编辑配方目录', async ({ page }) => {
  const requested: string[] = []
  const errors: string[] = []
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()) })
  page.on('pageerror', (error) => errors.push(error.message))
  await prepare(page, requested)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/platform-login')

  await page.getByRole('button', { name: '物料用量', exact: true }).click()
  await page.getByRole('button', { name: /按 .*销量生成快照/ }).click()

  await expect(page.getByRole('heading', { name: /水果采购测算/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '芒果', exact: true })).toBeVisible()
  await expect(page.getByText('浏览器不可编辑。')).toBeVisible()
  await expect(page.locator('.usage-grid input')).toHaveCount(0)
  await expect.poll(() => requested).toEqual(['/api/qmai/recipe-usage?month=2026-07&brand=ruguo'])
  await expectNoWholePageOverflow(page, '1280px 企迈服务端配方快照页')
  expect(errors).toEqual([])
})
