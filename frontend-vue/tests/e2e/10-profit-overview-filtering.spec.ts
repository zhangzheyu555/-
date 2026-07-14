import { expect, test, type Page, type Route } from '@playwright/test'

const brands = [
  { id: 1, name: '茹菓', color: '#76bdb8' },
  { id: 2, name: '霸王茶姬', color: '#9c2f3e' },
  { id: 3, name: '瑞幸咖啡', color: '#2458c7' },
]

const entries = [
  profitEntry('rg1', '荆州之星店', 1, '茹菓', 120000, 24000),
  profitEntry('rg2', '花台店', 1, '茹菓', 90000, 9000),
  profitEntry('bw1', '新圩店', 2, '霸王茶姬', 150000, -6000),
]

async function seedBoss(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-BOSS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify({
      id: 1,
      tenantId: 1,
      tenantName: 'TEST 租户',
      displayName: 'TEST 老板',
      role: 'BOSS',
      roleLabel: '老板',
      storeScope: ['all'],
    }))
  })
}

function dashboardRoute(requestUrls: string[], fail = false, sourceEntries = entries) {
  return async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname !== '/api/finance/dashboard') return route.continue()
    requestUrls.push(url.toString())
    if (fail) {
      return route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, message: '系统处理失败 java.lang.IllegalStateException', data: null }),
      })
    }
    const month = url.searchParams.get('month') || '2026-07'
    const brandId = url.searchParams.get('brandId')
    const rows = sourceEntries.filter((entry) => !brandId || String(entry.brandId) === brandId)
    const income = rows.reduce((sum, entry) => sum + Number(entry.income), 0)
    const net = rows.reduce((sum, entry) => sum + Number(entry.net), 0)
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: {
          months: ['2026-07', '2026-06'],
          brands,
          summary: {
            month,
            storeCount: new Set(rows.map((entry) => entry.storeId)).size,
            entryCount: rows.length,
            sales: income,
            income,
            costSum: income - net,
            expenseSum: 0,
            net,
            margin: income ? net / income : 0,
            riskStoreCount: rows.filter((entry) => Number(entry.net) < 0).length,
          },
          entries: rows.map((entry) => ({ ...entry, month })),
          trend: rows.length ? [
            { month: '2026-06', income: income * 0.9, net: net * 0.8, margin: income ? net / income : 0 },
            { month, income, net, margin: income ? net / income : 0 },
          ] : [],
        },
      }),
    })
  }
}

test('brand cards filter the overview and preserve URL navigation state', async ({ page }) => {
  const requests: string[] = []
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  await seedBoss(page)
  await page.route('**/*', dashboardRoute(requests))
  await page.goto('/profit?month=2026-07')

  await page.getByLabel('月份').selectOption('2026-06')
  await expect(page).toHaveURL(/month=2026-06/)
  await page.getByLabel('月份').selectOption('2026-07')
  await expect(page).toHaveURL(/month=2026-07/)

  const ruguo = page.locator('.legacy-brand-card-base').filter({ hasText: '茹菓' })
  const bawang = page.locator('.legacy-brand-card-base').filter({ hasText: '霸王茶姬' })
  await ruguo.click()
  await expect(page).toHaveURL(/\/profit\?month=2026-07&brandId=1|\/profit\?brandId=1&month=2026-07/)
  await expect(ruguo).toHaveClass(/selected/)
  await expect(page.getByText('茹菓 各店排名（2026-07）')).toBeVisible()
  await expect(page.getByRole('cell', { name: '荆州之星店' })).toBeVisible()
  await expect(page.locator('.legacy-kpis')).toContainText('¥210,000')

  await page.getByLabel('门店', { exact: true }).selectOption('rg1')
  await expect(page).toHaveURL(/storeId=rg1/)
  await bawang.click()
  await expect(page).toHaveURL(/brandId=2/)
  await expect(page).not.toHaveURL(/storeId=/)
  await expect(bawang).toHaveClass(/selected/)
  await expect(page.getByText('霸王茶姬 各店排名（2026-07）')).toBeVisible()
  await expect(page.locator('.legacy-kpis')).toContainText('¥150,000')
  await expect(page.locator('.legacy-kpis')).toContainText('1 家需要关注')

  await page.goBack()
  await expect(page).toHaveURL(/brandId=1/)
  await expect(page).toHaveURL(/storeId=rg1/)
  await expect(ruguo).toHaveClass(/selected/)
  await page.goForward()
  await expect(page).toHaveURL(/brandId=2/)
  await expect(bawang).toHaveClass(/selected/)
  await page.goBack()
  await expect(page).toHaveURL(/brandId=1/)
  await expect(page).toHaveURL(/storeId=rg1/)
  await page.reload()
  await expect(page.getByLabel('门店', { exact: true })).toHaveValue('rg1')
  await expect(ruguo).toHaveClass(/selected/)

  await page.getByRole('button', { name: '查看利润表' }).click()
  await expect(page).toHaveURL(/\/profit-table/)
  await expect(page).toHaveURL(/month=2026-07/)
  await expect(page).toHaveURL(/brandId=1/)
  await expect(page).toHaveURL(/storeId=rg1/)
  await expect(page).toHaveURL(/mode=single/)

  expect(requests.some((url) => url.includes('brandId=1'))).toBe(true)
  expect(requests.some((url) => url.includes('brandId=2'))).toBe(true)
  expect(requests.every((url) => !url.includes('storeId='))).toBe(true)
  expect(consoleErrors).toEqual([])
})

test('all brand cards stay on the overview and no-data state remains usable', async ({ page }) => {
  await seedBoss(page)
  await page.route('**/*', dashboardRoute([]))
  await page.goto('/profit?month=2026-07')

  const cardTextStyles = await page.locator('.legacy-brand-card-base').evaluateAll((cards) => cards.map((card) => ({
    color: getComputedStyle(card).color,
    nameWeight: getComputedStyle(card.querySelector('.legacy-brand-name')!).fontWeight,
  })))
  expect(new Set(cardTextStyles.map((style) => style.color))).toEqual(new Set(['rgb(255, 255, 255)']))
  expect(new Set(cardTextStyles.map((style) => style.nameWeight)).size).toBe(1)

  for (const brand of brands) {
    const card = page.locator('.legacy-brand-card-base').filter({ hasText: brand.name })
    await card.click()
    await expect(page).toHaveURL(/\/profit\?/)
    await expect(page).not.toHaveURL(/\/profit-table/)
    await expect(card).toHaveClass(/selected/)
  }

  await expect(page.getByText('当前筛选条件下暂无门店利润排行。')).toBeVisible()
  await expect(page.getByRole('button', { name: '查看利润表' })).toBeVisible()
  await page.getByRole('button', { name: '显示全部品牌' }).click()
  await expect(page).not.toHaveURL(/brandId=/)
})

test('dashboard failure is localized and technical details are hidden', async ({ page }) => {
  await seedBoss(page)
  await page.route('**/*', dashboardRoute([], true))
  await page.goto('/profit?month=2026-07&brandId=1')

  await expect(page.getByRole('heading', { name: '利润概览', level: 1 })).toBeVisible()
  await expect(page.getByLabel('利润筛选条件')).toBeVisible()
  await expect(page.getByText('利润数据加载失败，请稍后重试。')).toBeVisible()
  await expect(page.getByText('品牌数据暂时无法读取。')).toBeVisible()
  await expect(page.getByText('门店排行暂时无法读取。')).toBeVisible()
  await expect(page.getByText('趋势数据暂时无法读取。')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('java.lang')
  await expect(page.locator('body')).not.toContainText('系统处理失败')
})

test('profit page uses app-main as the only vertical scroll owner with no ranking data', async ({ page }) => {
  await seedBoss(page)
  await page.route('**/*', dashboardRoute([], false, []))
  await page.goto('/profit?month=2026-07')

  await expect(page.getByText('当前筛选条件下暂无门店利润排行。')).toBeVisible()
  await expectProfitScrollOwnership(page)
})

test('three ranking rows flow naturally below brand cards', async ({ page }) => {
  await seedBoss(page)
  await page.route('**/*', dashboardRoute([]))
  await page.goto('/profit?month=2026-07')

  await expect(page.locator('.legacy-rank-table tbody tr')).toHaveCount(3)
  await expectProfitScrollOwnership(page)
  const positions = await page.evaluate(() => ({
    brandBottom: document.querySelector('.legacy-brand-section')!.getBoundingClientRect().bottom,
    tableTop: document.querySelector('.legacy-table-card')!.getBoundingClientRect().top,
  }))
  expect(positions.tableTop).toBeGreaterThan(positions.brandBottom)
})

test('thirty-eight ranking rows expand the main scroll without an inner vertical scrollbar', async ({ page }) => {
  const manyEntries = Array.from({ length: 38 }, (_, index) => profitEntry(
    `TEST-${index + 1}`,
    `TEST 门店 ${index + 1}`,
    (index % 3) + 1,
    brands[index % 3].name,
    100000 + index * 1000,
    12000 - index * 100,
  ))
  await seedBoss(page)
  await page.route('**/*', dashboardRoute([], false, manyEntries))
  await page.goto('/profit?month=2026-07')

  await expect(page.locator('.legacy-rank-table tbody tr')).toHaveCount(38)
  const metrics = await expectProfitScrollOwnership(page)
  expect(metrics.mainScrollHeight).toBeGreaterThan(metrics.mainClientHeight)
  expect(metrics.pageScrollHeight).toBe(metrics.pageClientHeight)
  await page.locator('.legacy-trend-card').scrollIntoViewIfNeeded()
  await expect(page.locator('.legacy-trend-card')).toBeVisible()
})

test('primary metric card is opaque and compact topbar search keeps assistant handoff', async ({ page }) => {
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  await seedBoss(page)
  await page.route('**/*', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/finance/dashboard') return dashboardRoute([])(route)
    if (path === '/api/stores') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: [
            { id: 'rg1', code: 'TEST-RG1', name: 'TEST 荆州之星店', brandId: 1, brandName: '茹菓' },
            { id: 'rg2', code: 'TEST-RG2', name: 'TEST 花台店', brandId: 1, brandName: '茹菓' },
          ],
        }),
      })
    }
    if (path === '/api/brands') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: brands }) })
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) })
  })
  await page.goto('/profit?month=2026-07')

  const primaryCard = page.locator('.legacy-kpis .legacy-kpi').first()
  const colors = await primaryCard.evaluate((element) => {
    const card = getComputedStyle(element)
    const label = getComputedStyle(element.querySelector('.lab')!)
    const value = getComputedStyle(element.querySelector('.val')!)
    return {
      background: card.backgroundColor,
      border: card.borderTopColor,
      label: label.color,
      value: value.color,
      opacity: card.opacity,
    }
  })
  expect(colors.background).not.toBe('rgba(0, 0, 0, 0)')
  expect(colors.border).toBe(colors.background)
  expect(colors.label).toBe('rgb(255, 255, 255)')
  expect(colors.value).toBe('rgb(255, 255, 255)')
  expect(colors.opacity).toBe('1')

  await expect(page.locator('.topbar-tool-row')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '打开全局搜索' })).toBeVisible()
  await expect(page.getByRole('search')).toHaveCount(0)

  await page.getByLabel('全局门店').focus()
  await expect(page.getByLabel('全局门店').locator('option')).toHaveCount(3)
  await page.getByLabel('全局门店').selectOption('rg1')
  await expect(page).toHaveURL(/storeId=rg1/)

  await page.getByRole('button', { name: '打开全局搜索' }).click()
  const search = page.getByRole('search')
  await expect(search).toBeVisible()
  const searchBox = await search.boundingBox()
  expect(searchBox?.width || 0).toBeLessThanOrEqual(320)
  await page.getByLabel('搜索经营数据').press('Escape')
  await expect(search).toHaveCount(0)

  const question = '荆州之星店本月利润'
  await page.getByRole('button', { name: '打开全局搜索' }).click()
  await page.getByLabel('搜索经营数据').fill(question)
  await page.getByLabel('搜索经营数据').press('Enter')
  await expect(page).toHaveURL(/\/assistant\?q=/)
  await expect(page.getByText(question, { exact: true })).toBeVisible()
  expect(consoleErrors).toEqual([])
})

function profitEntry(storeId: string, storeName: string, brandId: number, brandName: string, income: number, net: number) {
  return {
    storeId,
    storeName,
    brandId,
    brandName,
    month: '2026-07',
    sales: income,
    income,
    costSum: income - net,
    expenseSum: 0,
    net,
    margin: income ? net / income : 0,
  }
}

async function expectProfitScrollOwnership(page: Page) {
  const metrics = await page.evaluate(() => {
    const main = document.querySelector('.app-main') as HTMLElement
    const root = document.querySelector('.legacy-profit-page') as HTMLElement
    const tableWrap = document.querySelector('.legacy-table-wrap') as HTMLElement | null
    const tableCard = document.querySelector('.legacy-table-card') as HTMLElement
    const lastModule = root.lastElementChild as HTMLElement
    const mainStyle = getComputedStyle(main)
    const rootStyle = getComputedStyle(root)
    const tableStyle = tableWrap ? getComputedStyle(tableWrap) : null
    const tableCardStyle = getComputedStyle(tableCard)
    const mainRect = main.getBoundingClientRect()
    const lastRect = lastModule.getBoundingClientRect()
    return {
      mainOverflowY: mainStyle.overflowY,
      rootHeight: rootStyle.height,
      rootMinHeight: rootStyle.minHeight,
      rootMaxHeight: rootStyle.maxHeight,
      rootOverflowY: rootStyle.overflowY,
      rootFlex: rootStyle.flex,
      tableOverflowX: tableStyle?.overflowX || '',
      tableOverflowY: tableStyle?.overflowY || '',
      tableMaxHeight: tableStyle?.maxHeight || '',
      hasTableWrap: Boolean(tableWrap),
      tableCardOverflowY: tableCardStyle.overflowY,
      mainClientHeight: main.clientHeight,
      mainScrollHeight: main.scrollHeight,
      pageClientHeight: root.clientHeight,
      pageScrollHeight: root.scrollHeight,
      bottomGap: main.scrollHeight - (lastRect.bottom - mainRect.top + main.scrollTop),
    }
  })

  expect(metrics.mainOverflowY).toBe('auto')
  expect(metrics.rootMinHeight).toBe('0px')
  expect(metrics.rootMaxHeight).toBe('none')
  expect(metrics.rootOverflowY).toBe('visible')
  expect(metrics.rootFlex).toBe('0 0 auto')
  if (metrics.hasTableWrap) {
    expect(metrics.tableOverflowX).toBe('auto')
    expect(metrics.tableOverflowY).toBe('hidden')
    expect(metrics.tableMaxHeight).toBe('none')
  }
  expect(metrics.tableCardOverflowY).not.toMatch(/auto|scroll/)
  expect(metrics.pageScrollHeight).toBe(metrics.pageClientHeight)
  expect(metrics.bottomGap).toBeGreaterThanOrEqual(24)
  expect(metrics.bottomGap).toBeLessThanOrEqual(32)
  return metrics
}
