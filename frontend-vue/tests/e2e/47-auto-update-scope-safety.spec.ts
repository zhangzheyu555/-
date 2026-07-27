import { expect, test, type Page } from '@playwright/test'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [
    'finance.profit.read',
    'finance.profit.write',
    'expense.read',
    'expense.create',
    'expense.review',
    'daily_loss.read',
    'daily_loss.create',
    'daily_loss.review',
    'daily_loss.export',
  ],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const stores = [
  { id: 'store-a', code: 'A', name: '门店 A', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
  { id: 'store-b', code: 'B', name: '门店 B', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
]

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}

function controlledFailure(message: string) {
  return {
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ success: false, code: 'CONTROLLED_FAILURE', message }),
  }
}

async function installSession(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'AUTO-UPDATE-SCOPE-SAFETY-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossSession)
}

function profitEntry(storeId: string, month: string, sales: number) {
  const store = stores.find((item) => item.id === storeId)!
  return {
    id: storeId === 'store-a' ? 1 : 2,
    storeId,
    storeCode: store.code,
    storeName: store.name,
    brandId: store.brandId,
    brandName: store.brandName,
    month,
    sales,
    refund: 0,
    discount: 0,
    income: sales,
    material: 0,
    packaging: 0,
    loss: 0,
    costOther: 0,
    costSum: 0,
    gross: sales,
    rent: 0,
    labor: 0,
    utility: 0,
    property: 0,
    commission: 0,
    expenseSum: 0,
    net: sales,
    margin: 1,
    risk: '正常',
  }
}

function profitDashboard(storeId: string, month: string) {
  const sales = storeId === 'store-b' ? 222 : 111
  const entry = profitEntry(storeId || 'store-a', month, sales)
  return {
    months: ['2026-07', '2026-08'],
    brands: [{ id: 1, code: 'RG', name: '茹菓' }],
    summary: {
      month,
      storeCount: 1,
      entryCount: 1,
      sales,
      income: sales,
      costSum: 0,
      expenseSum: 0,
      net: sales,
      margin: 1,
      riskStoreCount: 0,
    },
    entries: [entry],
    trend: [],
  }
}

test('数据录入切换范围失败时不会把上一门店草稿保存到新门店', async ({ page }) => {
  await installSession(page)
  let failStoreB = true
  const savedPayloads: Array<Record<string, unknown>> = []

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/brands') return route.fulfill(ok([{ id: 1, code: 'RG', name: '茹菓' }]))
    if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
    if (url.pathname === '/api/finance/months') return route.fulfill(ok(['2026-07']))
    if (url.pathname === '/api/finance/entries' && route.request().method() === 'GET') {
      const storeId = url.searchParams.get('storeId') || 'store-a'
      const month = url.searchParams.get('month') || '2026-07'
      if (storeId === 'store-b' && failStoreB) {
        return route.fulfill(controlledFailure('门店 B 数据受控读取失败'))
      }
      return route.fulfill(ok([profitEntry(storeId, month, storeId === 'store-b' ? 222 : 111)]))
    }
    if (url.pathname === '/api/finance/entries' && route.request().method() === 'PUT') {
      savedPayloads.push(route.request().postDataJSON() as Record<string, unknown>)
      return route.fulfill(ok(null))
    }
    if (url.pathname === '/api/finance/dashboard') {
      const storeId = url.searchParams.get('storeId') || 'store-a'
      const month = url.searchParams.get('month') || '2026-07'
      return route.fulfill(ok(profitDashboard(storeId, month)))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/data-entry')
  const salesInput = page.getByLabel('营业额')
  const saveButton = page.getByRole('button', { name: '保存', exact: true })
  await expect(salesInput).toHaveValue('111')
  await expect(saveButton).toBeEnabled()

  await page.getByRole('combobox', { name: '门店', exact: true }).selectOption('store-b')
  await expect(page.getByText('门店 B 数据受控读取失败')).toBeVisible()
  await expect(salesInput).toHaveValue('')
  await expect(saveButton).toBeDisabled()

  await page.locator('form.entry-sheet').evaluate((form: HTMLFormElement) => form.requestSubmit())
  await expect(page.getByText(/当前门店和月份的数据尚未成功读取/)).toBeVisible()
  expect(savedPayloads).toEqual([])

  failStoreB = false
  await page.evaluate(() => {
    const staleNow = Date.now() + 61_000
    Date.now = () => staleNow
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })

  await expect(salesInput).toHaveValue('222')
  await expect(saveButton).toBeEnabled()
  await salesInput.fill('333')
  await saveButton.click()
  await expect.poll(() => savedPayloads).toHaveLength(1)
  expect(savedPayloads[0]).toMatchObject({ storeId: 'store-b', sales: 333 })
})

test('报销筛选请求失败时隐藏旧范围记录和审核动作，同筛选重试成功后恢复', async ({ page }) => {
  await installSession(page)
  let failTargetMonth = true
  const oldExpense = {
    id: 'expense-old',
    storeId: 'store-a',
    storeName: '门店 A',
    brandId: 1,
    brandName: '茹菓',
    month: '2026-07',
    expenseDate: '2026-07-01',
    amount: 88,
    category: '物料',
    reason: '旧范围报销',
    status: '待审核',
    attachments: [],
    supplements: [],
  }
  const targetExpense = {
    ...oldExpense,
    id: 'expense-target',
    month: '2026-08',
    expenseDate: '2026-08-01',
    amount: 99,
    reason: '新范围报销',
  }

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
    if (url.pathname === '/api/expenses' && route.request().method() === 'GET') {
      if (url.searchParams.get('month') === '2026-08') {
        if (failTargetMonth) return route.fulfill(controlledFailure('目标月份报销受控读取失败'))
        return route.fulfill(ok([targetExpense]))
      }
      return route.fulfill(ok([oldExpense]))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/expenses')
  await expect(page.getByText('旧范围报销')).toBeVisible()

  await page.getByLabel('月份筛选').fill('2026-08')
  await expect(page.getByText('目标月份报销受控读取失败')).toBeVisible()
  await expect(page.getByText('旧范围报销')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '通过', exact: true })).toHaveCount(0)
  await expect(page.locator('.metric-card').filter({ hasText: '记录数量' }).locator('b')).toHaveText('0')

  failTargetMonth = false
  await page.locator('.expense-error').getByRole('button', { name: '重试' }).click()
  await expect(page.getByText('新范围报销')).toBeVisible()
  await expect(page.locator('.metric-card').filter({ hasText: '记录数量' }).locator('b')).toHaveText('1')
})

test('利润概览新筛选读取失败时隐藏旧范围数据并禁用导出', async ({ page }) => {
  await installSession(page)
  let failAugust = true

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/finance/dashboard') {
      const month = url.searchParams.get('month') || '2026-07'
      if (month === '2026-08' && failAugust) {
        return route.fulfill(controlledFailure('八月利润受控读取失败'))
      }
      return route.fulfill(ok(profitDashboard(url.searchParams.get('storeId') || '', month)))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/profit?month=2026-07')
  const exportButton = page.getByRole('button', { name: '导出报表' })
  await expect(page.locator('.profit-metric-grid')).toContainText('¥111')
  await expect(exportButton).toBeEnabled()

  await page.getByLabel('月份').selectOption('2026-08')
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.locator('.profit-metric-grid')).toHaveCount(0)
  await expect(page.getByText('当前筛选范围暂时无法读取，请稍后重试。')).toBeVisible()
  await expect(exportButton).toBeDisabled()

  failAugust = false
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.locator('.profit-metric-grid')).toContainText('¥111')
  await expect(exportButton).toBeEnabled()
})

test('利润概览前台请求被新路由取代时，不会替失败的新筛选标记为最新', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-07-25T00:00:00Z') })
  await installSession(page)
  let julyRequests = 0
  let augustRequests = 0
  let releaseForegroundJuly: (() => void) | null = null
  let releaseAugustFailure: (() => void) | null = null

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/finance/dashboard') {
      const month = url.searchParams.get('month') || '2026-07'
      if (month === '2026-07') {
        julyRequests += 1
        if (julyRequests === 2) {
          await new Promise<void>((resolve) => { releaseForegroundJuly = resolve })
        }
        return route.fulfill(ok(profitDashboard('', month)))
      }

      augustRequests += 1
      if (augustRequests === 1) {
        await new Promise<void>((resolve) => { releaseAugustFailure = resolve })
        return route.fulfill(controlledFailure('八月利润受控读取失败'))
      }
      return route.fulfill(ok(profitDashboard('', month)))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/profit?month=2026-07')
  await expect(page.locator('.profit-metric-grid')).toContainText('¥111')

  await page.clock.runFor(60_000)
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect.poll(() => julyRequests).toBe(2)

  await page.evaluate(() => {
    window.history.pushState({}, '', '/profit?month=2026-08')
    window.dispatchEvent(new PopStateEvent('popstate', { state: window.history.state }))
  })
  await expect.poll(() => augustRequests).toBe(1)

  const foregroundResponse = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/finance/dashboard' && url.searchParams.get('month') === '2026-07'
  })
  releaseForegroundJuly?.()
  await foregroundResponse

  const augustFailure = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/finance/dashboard'
      && url.searchParams.get('month') === '2026-08'
      && response.status() === 500
  })
  releaseAugustFailure?.()
  await augustFailure
  await expect(page.getByRole('alert')).toContainText('八月利润受控读取失败')
  await expect(page.locator('.profit-metric-grid')).toHaveCount(0)

  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect.poll(() => augustRequests).toBe(2)
  await expect(page.locator('.profit-metric-grid')).toContainText('¥111')
})

test('每日报损切换门店读取失败时隐藏旧记录和复核动作，前台恢复请求去重', async ({ page }) => {
  await installSession(page)
  let failStoreB = true
  let storeBRequests = 0
  const report = (storeId: string, storeName: string) => ({
    id: `report-${storeId}`,
    storeId,
    storeCode: storeId === 'store-a' ? 'A' : 'B',
    storeName,
    lossDate: '2026-07-24',
    month: '2026-07',
    status: 'SUBMITTED',
    statusLabel: '待复核',
    reported: true,
    totalAmount: 18,
    supplierCompensationAmount: 0,
    storeBorneAmount: 18,
    detailCount: 1,
    attachmentCount: 0,
    attachments: [],
    details: [],
  })

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
    if (url.pathname === '/api/daily-loss/items') return route.fulfill(ok([]))
    if (url.pathname === '/api/daily-loss/monthly-archive') return route.fulfill(ok(null))
    if (url.pathname === '/api/daily-loss/reports' && route.request().method() === 'GET') {
      const storeId = url.searchParams.get('storeId') || ''
      if (storeId === 'store-b') {
        storeBRequests += 1
        if (failStoreB) return route.fulfill(controlledFailure('门店 B 报损受控读取失败'))
        return route.fulfill(ok([report('store-b', '门店 B')]))
      }
      return route.fulfill(ok([report('store-a', '门店 A')]))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/daily-loss')
  await expect(page.getByText('门店 A · 2026-07-24')).toBeVisible()
  await expect(page.getByRole('button', { name: '复核通过', exact: true })).toBeVisible()

  await page.getByRole('combobox', { name: '门店', exact: true }).selectOption('store-b')
  await expect(page.getByText('门店 B 报损受控读取失败')).toBeVisible()
  await expect(page.locator('.record-list')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '复核通过', exact: true })).toHaveCount(0)

  failStoreB = false
  await page.evaluate(() => {
    const staleNow = Date.now() + 61_000
    Date.now = () => staleNow
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })

  await expect(page.getByText('门店 B · 2026-07-24')).toBeVisible()
  await expect(page.getByRole('button', { name: '复核通过', exact: true })).toBeVisible()
  await expect.poll(() => storeBRequests).toBe(2)
})

test('数据录入首次目录失败后，前台恢复会立即重试目录并加载当前范围', async ({ page }) => {
  await installSession(page)
  let storeCatalogRequests = 0
  let entryRequests = 0
  let historyMonthRequests = 0
  let dashboardRequests = 0

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/brands') return route.fulfill(ok([{ id: 1, code: 'RG', name: '茹菓' }]))
    if (url.pathname === '/api/stores') {
      storeCatalogRequests += 1
      if (storeCatalogRequests === 1) return route.fulfill(controlledFailure('门店目录首次读取失败'))
      return route.fulfill(ok(stores))
    }
    if (url.pathname === '/api/finance/months') {
      historyMonthRequests += 1
      return route.fulfill(ok(['2026-07']))
    }
    if (url.pathname === '/api/finance/entries') {
      entryRequests += 1
      const storeId = url.searchParams.get('storeId') || 'store-a'
      const month = url.searchParams.get('month') || '2026-07'
      return route.fulfill(ok([profitEntry(storeId, month, 111)]))
    }
    if (url.pathname === '/api/finance/dashboard') {
      dashboardRequests += 1
      const storeId = url.searchParams.get('storeId') || 'store-a'
      const month = url.searchParams.get('month') || '2026-07'
      return route.fulfill(ok(profitDashboard(storeId, month)))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/data-entry')
  await expect(page.getByText('门店目录首次读取失败')).toBeVisible()
  await expect.poll(() => storeCatalogRequests).toBe(1)

  await page.evaluate(() => window.dispatchEvent(new Event('focus')))

  await expect.poll(() => storeCatalogRequests).toBe(2)
  await expect.poll(() => entryRequests).toBe(2)
  await expect.poll(() => historyMonthRequests).toBe(1)
  // 品牌筛选下 profit store 会同时获取品牌范围和全品牌基线，各一次。
  await expect.poll(() => dashboardRequests).toBe(2)
  await expect(page.getByLabel('营业额')).toHaveValue('111')
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeEnabled()
})

test('数据录入前台同步在请求期间开始编辑时不会覆盖未保存内容', async ({ page }) => {
  await installSession(page)
  let entryRequests = 0
  let signalDelayedRequest: (() => void) | undefined
  let releaseDelayedRequest: (() => void) | undefined
  const delayedRequestStarted = new Promise<void>((resolve) => {
    signalDelayedRequest = resolve
  })
  const delayedRequestGate = new Promise<void>((resolve) => {
    releaseDelayedRequest = resolve
  })

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/brands') return route.fulfill(ok([{ id: 1, code: 'RG', name: '茹菓' }]))
    if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
    if (url.pathname === '/api/finance/months') return route.fulfill(ok(['2026-07']))
    if (url.pathname === '/api/finance/entries') {
      entryRequests += 1
      if (entryRequests === 2) {
        signalDelayedRequest?.()
        await delayedRequestGate
        return route.fulfill(ok([profitEntry('store-a', '2026-07', 444)]))
      }
      return route.fulfill(ok([profitEntry('store-a', '2026-07', 111)]))
    }
    if (url.pathname === '/api/finance/dashboard') {
      return route.fulfill(ok(profitDashboard('store-a', '2026-07')))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/data-entry')
  const salesInput = page.getByLabel('营业额')
  await expect(salesInput).toHaveValue('111')

  await page.evaluate(() => {
    const staleNow = Date.now() + 61_000
    Date.now = () => staleNow
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await delayedRequestStarted

  await salesInput.fill('333')
  releaseDelayedRequest?.()
  await expect.poll(() => entryRequests).toBe(2)
  await page.waitForTimeout(100)
  await expect(salesInput).toHaveValue('333')
})

test('报销首次门店目录失败后，前台恢复会立即重试目录且保留已加载列表', async ({ page }) => {
  await installSession(page)
  let storeCatalogRequests = 0
  const expense = {
    id: 'expense-loaded',
    storeId: 'store-a',
    storeName: '门店 A',
    brandId: 1,
    brandName: '茹菓',
    month: '2026-07',
    expenseDate: '2026-07-01',
    amount: 88,
    category: '物料',
    reason: '目录失败时仍已加载的报销',
    status: '待审核',
    attachments: [],
    supplements: [],
  }

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/stores') {
      storeCatalogRequests += 1
      if (storeCatalogRequests === 1) return route.fulfill(controlledFailure('门店目录首次读取失败'))
      return route.fulfill(ok(stores))
    }
    if (url.pathname === '/api/expenses') return route.fulfill(ok([expense]))
    return route.fulfill(ok([]))
  })

  await page.goto('/expenses')
  await expect(page.getByText('门店列表加载失败，请稍后重试')).toBeVisible()
  await expect(page.getByText('目录失败时仍已加载的报销')).toBeVisible()
  await expect.poll(() => storeCatalogRequests).toBe(1)

  await page.evaluate(() => window.dispatchEvent(new Event('focus')))

  await expect.poll(() => storeCatalogRequests).toBe(2)
  await expect(page.getByText('门店列表加载失败，请稍后重试')).toHaveCount(0)
  await expect(page.getByRole('option', { name: /门店 A/ })).toHaveCount(1)
  await expect(page.getByText('目录失败时仍已加载的报销')).toBeVisible()
})
