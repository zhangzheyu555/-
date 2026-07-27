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

type RecipeUsageResponder = (route: Route, url: URL) => Promise<void>

function recipeUsageSnapshot(month: string, fruit = '芒果') {
  return {
    month,
    matchedProductCount: 1,
    calculation: {
      totalCups: 2.5,
      fruits: [{ fruit, netGrams: 250, rawGrams: 500, rawJin: 1, approximate: false }],
    },
  }
}

async function prepare(page: Page, requested: string[], recipeUsageResponder?: RecipeUsageResponder) {
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
      if (recipeUsageResponder) return recipeUsageResponder(route, url)
      return route.fulfill(ok(recipeUsageSnapshot('2026-07')))
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

  await expect(page.getByRole('button', { name: /刷新|重新加载|重新读取/ })).toHaveCount(0)
  await page.getByRole('button', { name: '物料用量', exact: true }).click()
  await page.getByRole('button', { name: '生成月度用量快照', exact: true }).click()

  await expect(page.getByRole('heading', { name: /水果采购测算/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '芒果', exact: true })).toBeVisible()
  await expect(page.getByText('浏览器不可编辑。')).toBeVisible()
  await expect(page.locator('.usage-grid input')).toHaveCount(0)
  await expect.poll(() => requested).toEqual(['/api/qmai/recipe-usage?month=2026-07&brand=ruguo'])
  await expectNoWholePageOverflow(page, '1280px 企迈服务端配方快照页')
  expect(errors).toEqual([])
})

test('月份切换会隔离迟到的用量快照，且导出只使用当前月份', async ({ page }) => {
  const requested: string[] = []
  const exported: string[] = []
  let releaseJulyRequest: (() => void) | undefined
  const julyRequestGate = new Promise<void>((resolve) => {
    releaseJulyRequest = resolve
  })

  await prepare(page, requested, async (route, url) => {
    const requestedMonth = url.searchParams.get('month') || ''
    if (requestedMonth === '2026-07') {
      await julyRequestGate
      return route.fulfill(ok(recipeUsageSnapshot(requestedMonth, '迟到芒果')))
    }
    return route.fulfill(ok(recipeUsageSnapshot(requestedMonth, '当前草莓')))
  })
  await page.route(/^https?:\/\/[^/]+\/api\/qmai\/recipe-usage\.csv/, async (route) => {
    const url = new URL(route.request().url())
    exported.push(`${url.pathname}?${url.searchParams.toString()}`)
    await route.fulfill({
      status: 200,
      contentType: 'text/csv',
      body: 'fruit,amount\r\n当前草莓,1',
    })
  })

  await page.goto('/platform-login')
  await page.getByRole('button', { name: '物料用量', exact: true }).click()
  await page.getByRole('button', { name: '生成月度用量快照', exact: true }).click()
  await expect.poll(() => requested).toEqual(['/api/qmai/recipe-usage?month=2026-07&brand=ruguo'])

  await page.getByRole('button', { name: '◀ 上一月', exact: true }).click()
  await expect(page.getByRole('button', { name: '生成月度用量快照', exact: true })).toBeVisible()
  const staleResponsePromise = page.waitForResponse((response) =>
    response.url().includes('/api/qmai/recipe-usage?')
      && response.url().includes('month=2026-07'),
  )
  releaseJulyRequest?.()
  const staleResponse = await staleResponsePromise
  await staleResponse.finished()
  await page.evaluate(() => new Promise<void>((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  }))

  await expect(page.getByRole('cell', { name: '迟到芒果', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导出 Excel', exact: true })).toBeDisabled()

  await page.getByRole('button', { name: '生成月度用量快照', exact: true }).click()
  await expect(page.getByRole('cell', { name: '当前草莓', exact: true })).toBeVisible()

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel', exact: true }).click()
  await downloadPromise
  await expect.poll(() => exported).toEqual(['/api/qmai/recipe-usage.csv?month=2026-06&brand=ruguo'])
})
