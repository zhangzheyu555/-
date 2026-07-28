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
  permissions: ['platform.read', 'platform.manage', 'finance.export'],
  dataScopes: { PLATFORM: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const finance = {
  ...boss,
  id: 902,
  displayName: '测试财务',
  role: 'FINANCE',
  roleLabel: '财务',
  permissions: ['finance.profit.read', 'finance.export'],
}

const supervisor = {
  ...boss,
  id: 903,
  displayName: '测试督导',
  role: 'SUPERVISOR',
  roleLabel: '督导',
  // 即使误配 finance.export，角色限制仍不得让督导导出财务数据。
  permissions: ['platform.read', 'platform.manage', 'finance.export'],
}

type RecipeUsageResponder = (route: Route, url: URL) => Promise<void>

function recipeUsageSnapshot(month: string, fruit = '芒果') {
  return {
    month,
    matchedProductCount: 1,
    matchedProducts: [{ recipeName: '芒芒甘露', cups: 2.5 }],
    unmatchedProducts: [{ name: '未收录水果茶', cups: 1 }],
    calculation: {
      totalCups: 2.5,
      fruits: [{ fruit, netGrams: 250, rawGrams: 500, rawJin: 1, approximate: false }],
      otherMaterials: [{ materialName: '椰奶', grams: 125 }],
    },
  }
}

async function prepare(
  page: Page,
  requested: string[],
  recipeUsageResponder?: RecipeUsageResponder,
  session = boss,
  seedSession = true,
) {
  // Keep month/date expectations stable across real calendar days while allowing polling timers to run.
  await page.clock.setFixedTime('2026-07-27T05:30:00+08:00')
  if (seedSession) {
    await page.addInitScript((storedSession) => {
      localStorage.setItem('ai_profit_vue_token', 'QMAI-DESKTOP-E2E')
      localStorage.setItem('ai_profit_vue_user', JSON.stringify(storedSession))
    }, session)
  }
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/login') {
      return route.fulfill(ok({
        token: 'QMAI-DESKTOP-E2E',
        user: session,
        status: 'AUTHENTICATED',
        passwordChangeCredential: null,
      }))
    }
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(session))
    if (url.pathname === '/api/qmai/config' || url.pathname === '/api/qmai/status') {
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
  await expect(page.getByRole('cell', { name: '椰奶', exact: true })).toBeVisible()
  await page.getByText(/配方表没有的售卖商品/).click()
  await expect(page.getByText(/未收录水果茶（1杯）/)).toBeVisible()
  await expect(page.getByText(/中\/大杯及 500\/1000ml 同品按 1:1 分匀/)).toBeVisible()
  await expect(page.getByText('浏览器不可编辑。')).toBeVisible()
  await expect(page.locator('.usage-grid input')).toHaveCount(0)
  await expect.poll(() => requested).toEqual(['/api/qmai/recipe-usage?month=2026-07&brand=ruguo'])
  await expectNoWholePageOverflow(page, '1280px 企迈服务端配方快照页')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('heading', { name: /水果采购测算/ })).toBeVisible()
  await expect(page.getByRole('cell', { name: '椰奶', exact: true })).toBeVisible()
  await expectNoWholePageOverflow(page, '390px 企迈服务端配方快照页')
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

test('历史日期紧接上一月并同时筛选营业额、商品销售和导出，清除后恢复整月', async ({ page }) => {
  const requested: string[] = []
  const scopedRequests: string[] = []
  const exported: string[] = []
  await prepare(page, requested)

  await page.route(/\/api\/qmai\/(revenue|products)(?:\?|$)/, async (route) => {
    const url = new URL(route.request().url())
    scopedRequests.push(`${url.pathname}?${url.searchParams.toString()}`)
    if (url.pathname === '/api/qmai/revenue') {
      return route.fulfill(ok([{
        storeId: 'RG001',
        storeName: '茹菓门店',
        orderCount: 3,
        revenue: 88,
        refund: 2,
        cost: 30,
      }]))
    }
    return route.fulfill(ok([{
      storeId: 'RG001',
      storeName: '茹菓门店',
      itemName: '芒果茶',
      categoryName: '鲜果茶',
      quantity: 3,
      refundQuantity: 0,
      revenue: 88,
      refund: 0,
    }]))
  })
  await page.route(/\/api\/qmai\/(revenue|products)\.csv(?:\?|$)/, async (route) => {
    const url = new URL(route.request().url())
    exported.push(`${url.pathname}?${url.searchParams.toString()}`)
    return route.fulfill({ status: 200, contentType: 'text/csv', body: 'name,amount\r\n芒果茶,88' })
  })

  await page.goto('/platform-login')
  await expect.poll(() => scopedRequests.length).toBeGreaterThanOrEqual(2)

  const previousMonthButton = page.getByRole('button', { name: '◀ 上一月', exact: true })
  const dateFilter = page.getByLabel('选择企迈历史日期')
  await expect(previousMonthButton.locator('xpath=following-sibling::*[1]')).toHaveClass(/date-filter/)
  await expect(dateFilter).toHaveAttribute('max', '2026-07-26')

  scopedRequests.length = 0
  await dateFilter.evaluate((element) => {
    const input = element as HTMLInputElement
    input.value = '2026-06-15'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  })
  await expect(page.getByText('2026年6月', { exact: true })).toBeVisible()
  await expect.poll(() => scopedRequests.slice().sort()).toEqual([
    '/api/qmai/products?month=2026-06&brand=ruguo&businessDate=2026-06-15',
    '/api/qmai/revenue?month=2026-06&brand=ruguo&businessDate=2026-06-15',
  ])
  await expect(page.getByRole('cell', { name: '2026-06-15', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '茹菓门店', exact: true })).toBeVisible()

  let downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel', exact: true }).click()
  await downloadPromise

  await page.getByRole('button', { name: '企迈商品销售', exact: true }).click()
  await expect(page.getByRole('cell', { name: '芒果茶', exact: true })).toBeVisible()
  downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel', exact: true }).click()
  await downloadPromise

  await expect.poll(() => exported.slice().sort()).toEqual([
    '/api/qmai/products.csv?month=2026-06&brand=ruguo&businessDate=2026-06-15',
    '/api/qmai/revenue.csv?month=2026-06&brand=ruguo&businessDate=2026-06-15',
  ])

  scopedRequests.length = 0
  await expect(page.getByRole('button', { name: '查看整月', exact: true })).toBeVisible()
  await dateFilter.evaluate((element) => {
    const input = element as HTMLInputElement
    input.value = ''
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  })
  await expect(dateFilter).toHaveValue('')
  await expect.poll(() => scopedRequests.slice().sort()).toEqual([
    '/api/qmai/products?month=2026-06&brand=ruguo',
    '/api/qmai/revenue?month=2026-06&brand=ruguo',
  ])

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(dateFilter).toBeVisible()
  await expectNoWholePageOverflow(page, '390px 企迈历史日期筛选页')
})

test('补取历史数据展示进度，且企迈快照导出仅向老板和财务开放', async ({ page, browser }) => {
  const requested: string[] = []
  let latestCalls = 0
  const submitted: string[] = []
  await prepare(page, requested)
  await page.route(/\/api\/qmai\/sync\/batches\/latest(?:\?|$)/, async (route) => {
    latestCalls += 1
    if (latestCalls === 1) return route.fulfill(ok(null))
    return route.fulfill(ok({
      id: 711,
      status: 'PARTIAL',
      targetMonth: '2026-07',
      totalTasks: 3,
      completedTasks: 3,
      failedTasks: 1,
      dailyRows: 2,
      productRows: 16,
      errorSummary: '2026-07-02 拉取失败，请重试。',
    }))
  })
  await page.route(/\/api\/qmai\/sync\/backfill(?:\?|$)/, async (route) => {
    const url = new URL(route.request().url())
    submitted.push(`${url.pathname}?${url.searchParams.toString()}`)
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers().authorization).toBe('Bearer QMAI-DESKTOP-E2E')
    return route.fulfill(ok({
      id: 711,
      status: 'RUNNING',
      targetMonth: url.searchParams.get('month'),
      totalTasks: 3,
      completedTasks: 1,
      failedTasks: 0,
      dailyRows: 1,
      productRows: 8,
    }))
  })

  await page.goto('/platform-login')
  const backfillButton = page.getByRole('button', { name: '补取本月历史数据', exact: true })
  await expect(backfillButton).toBeVisible()
  await backfillButton.click()

  await expect.poll(() => submitted[0]).toMatch(/^\/api\/qmai\/sync\/backfill\?brand=ruguo&month=\d{4}-\d{2}$/)
  await expect(page.getByText('1/3 项（33%）', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '补取中…', exact: true })).toBeDisabled()

  await expect(page.getByText('部分日期补取失败', { exact: true })).toBeVisible()
  await expect(page.getByText('2026-07-02 拉取失败，请重试。', { exact: true })).toBeVisible()
  await expect(page.getByText('3/3 项（100%）', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '补取本月历史数据', exact: true })).toBeEnabled()
  const partialFailureDialog = page.getByRole('alertdialog', { name: '操作未完成' })
  await expect(partialFailureDialog).toContainText('2026-07-02 拉取失败，请重试。')
  await partialFailureDialog.getByRole('button', { name: '我知道了' }).click()

  const dateFilter = page.getByLabel('选择企迈历史日期')
  await dateFilter.evaluate((element) => {
    const input = element as HTMLInputElement
    input.value = '2026-07-03'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  })
  const dayBackfillButton = page.getByRole('button', { name: '补取所选日期', exact: true })
  await expect(dayBackfillButton).toBeEnabled()
  await dayBackfillButton.click()
  await expect.poll(() => submitted[1]).toBe(
    '/api/qmai/sync/backfill?brand=ruguo&month=2026-07&businessDate=2026-07-03',
  )
  await expect(dayBackfillButton).toBeEnabled()

  const origin = new URL(page.url()).origin
  for (const roleCase of [
    { session: finance, allowed: true },
    { session: supervisor, allowed: false },
  ]) {
    const context = await browser.newContext()
    const rolePage = await context.newPage()
    try {
      const loginFromProtectedUrl = roleCase.session.role === 'FINANCE'
      await prepare(rolePage, [], undefined, roleCase.session, !loginFromProtectedUrl)
      await rolePage.route(/\/api\/qmai\/(revenue|products)(?:\?|$)/, async (route) => {
        const url = new URL(route.request().url())
        if (url.pathname === '/api/qmai/revenue') {
          return route.fulfill(ok([{
            storeId: 'RG001',
            storeName: '茹菓门店',
            orderCount: 1,
            revenue: 20,
            refund: 0,
            cost: 8,
          }]))
        }
        return route.fulfill(ok([{
          storeId: 'RG001',
          storeName: '茹菓门店',
          itemName: '权限测试饮品',
          categoryName: '鲜果茶',
          quantity: 1,
          refundQuantity: 0,
          revenue: 20,
          refund: 0,
        }]))
      })
      await rolePage.goto(`${origin}/platform-login`)
      if (loginFromProtectedUrl) {
        await expect(rolePage).toHaveURL(/\/login\?redirect=/)
        await rolePage.getByLabel('账号', { exact: true }).fill('finance')
        await rolePage.getByLabel('密码', { exact: true }).fill('private-password')
        await rolePage.getByRole('button', { name: '登录', exact: true }).click()
        await expect(rolePage).toHaveURL(/\/platform-login$/)
      }

      const turnoverExport = rolePage.getByRole('button', { name: '导出 Excel', exact: true })
      await expect(rolePage.getByLabel('选择企迈历史日期')).toBeVisible()
      if (roleCase.allowed) {
        await expect(rolePage.locator('[data-menu-key="platform-settings"]')).toBeVisible()
        await expect(turnoverExport).toBeEnabled()
        await expect(rolePage.getByText('仅老板或财务可以导出', { exact: true })).toHaveCount(0)
        await expect(rolePage.getByText('无配置权限', { exact: true })).toBeVisible()
        await expect(rolePage.getByRole('button', { name: '补取本月历史数据', exact: true })).toHaveCount(0)
        await expect(rolePage.getByRole('button', { name: '物料用量', exact: true })).toHaveCount(0)
        await expect(rolePage.getByRole('button', { name: 'POS', exact: true })).toHaveCount(0)
        await rolePage.locator('.qmai-card').click()
        await expect(rolePage.getByRole('heading', { name: /企迈配置/ })).toHaveCount(0)
      } else {
        await expect(turnoverExport).toBeDisabled()
        await expect(rolePage.getByText('仅老板或财务可以导出', { exact: true })).toBeVisible()
        await expect(rolePage.getByRole('button', { name: '物料用量', exact: true })).toBeVisible()
        await expect(rolePage.getByRole('button', { name: 'POS', exact: true })).toBeVisible()
      }

      await rolePage.getByRole('button', { name: '企迈商品销售', exact: true }).click()
      if (roleCase.allowed) {
        await expect(turnoverExport).toBeEnabled()
      } else {
        await expect(turnoverExport).toBeDisabled()
      }
    } finally {
      await context.close()
    }
  }
})

test('平台页的历史警告样式也统一进入错误弹窗', async ({ page }) => {
  const requested: string[] = []
  await prepare(page, requested)

  await page.goto('/platform-login')
  await page.getByRole('button', { name: 'POS', exact: true }).click()
  await page.getByRole('button', { name: '提交核销', exact: true }).click()

  await expect(page.getByRole('alertdialog', { name: '操作未完成' }))
    .toContainText('请先确认这是一次真实核销操作')
  await expect(page.locator('.msg.warn-text').filter({ hasText: '请先确认这是一次真实核销操作' })).toBeHidden()
})

test('持续配置状态保留在原位置且不会误弹错误窗口', async ({ page }) => {
  const requested: string[] = []
  await prepare(page, requested)
  await page.route('**/api/qmai/config**', (route) => route.fulfill(ok({
    configured: false,
    brand: 'ruguo',
    openKeySet: false,
    consoleTokenSet: false,
    statusText: '尚未配置连接凭据',
  })))

  await page.goto('/platform-login')
  await page.locator('.qmai-card').click()

  await expect(page.getByText('当前状态：尚未配置连接凭据')).toBeVisible()
  await expect(page.getByRole('alertdialog')).toHaveCount(0)
})
