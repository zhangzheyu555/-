import { expect, test } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: 'TEST 租户',
  displayName: 'TEST 老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['system.dashboard.read'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
    SALARY: { mode: 'ALL', storeIds: [] },
    WAREHOUSE: { mode: 'ALL', storeIds: [] },
    INSPECTION: { mode: 'ALL', storeIds: [] },
    EXAM: { mode: 'ALL', storeIds: [] },
  },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

let bossDashboardOverride: (() => unknown) | null = null

test.beforeEach(async ({ page }) => {
  bossDashboardOverride = null
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-BOSS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, bossSession)

  await page.route('**/*', (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (path === '/api/boss/todo-dashboard') {
      if (bossDashboardOverride) return route.fulfill(ok(bossDashboardOverride()))
      return route.fulfill(ok({
        roleName: '老板',
        dataSource: 'TEST',
        updatedAt: '2026-07-10T12:00:00',
        todayFocus: {
          totalOpenCount: 34,
          needsBossActionCount: 0,
          roleWorkCount: 40,
          highRiskCount: 40,
          highRiskGroupCount: 3,
          doneReviewCount: 0,
          summary: '今天暂无必须老板拍板的事项；岗位仍有 40 条高风险事项。',
        },
        needsBossAction: [],
        highRiskReminders: [{
          groupKey: 'TEST-RISK',
          sourceModule: 'finance',
          ownerName: '财务',
          storeName: 'TEST 门店',
          month: '2026-07',
          count: 34,
          highestRisk: 'RISK',
          highestPriority: 95,
          earliestDueAt: '2026-07-10T18:00:00',
          topStores: ['TEST 门店'],
          action: {
            target: 'report',
            label: '查看利润表',
            params: {
              storeId: 'TEST-STORE',
              month: '2026-07',
              mode: 'single',
            },
          },
        }, {
          groupKey: 'TEST-RISK-SECOND',
          sourceModule: 'inspection',
          ownerName: '督导',
          storeName: 'TEST 门店',
          month: '2026-07',
          count: 4,
          highestRisk: 'RISK',
          highestPriority: 90,
          earliestDueAt: '2026-07-11T18:00:00',
          topStores: ['TEST 门店'],
          action: {
            target: 'inspect',
            label: '查看巡店记录',
            params: {
              storeId: 'TEST-STORE',
              inspectionId: 'INSP-EXACT-001',
              month: '2026-07',
            },
          },
        }, {
          groupKey: 'TEST-RISK-THIRD',
          sourceModule: 'warehouse',
          ownerName: '仓库管理员',
          storeName: '第二门店',
          month: '2026-07',
          count: 2,
          highestRisk: 'RISK',
          highestPriority: 85,
          earliestDueAt: '2026-07-12T18:00:00',
          topStores: ['第二门店'],
          action: {
            target: 'warehouse',
            label: '查看库存预警',
            params: {
              warehouseId: '1',
              itemId: '12',
              month: '2026-07',
            },
          },
        }],
        roleProgress: [{
          ownerName: '财务',
          openCount: 40,
          riskCount: 40,
          pendingCount: 0,
          earliestDueAt: '2026-07-10T18:00:00',
          topSources: ['finance'],
        }],
        doneReview: [],
      }))
    }
    if (path === '/api/boss/exam-summary') {
      return route.fulfill(ok({
        activeExamCount: 0,
        assignedCount: 0,
        completedCount: 0,
        completionRate: 0,
        passedCount: 0,
        passRate: 0,
        overdueCount: 0,
        averageScore: 0,
        riskStores: [],
      }))
    }
    return route.fulfill(ok([]))
  })
})

test('boss action count is separated from role risk counts and every tab remains usable', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/boss')

  await expect(page.getByText('仅显示需要老板立即处理的事项')).toBeVisible()
  await expect(page.getByText('当前没有需要你处理的事项')).toBeVisible()
  await expect(page.locator('.urgent-panel')).not.toContainText('34')
  await expect(page.locator('.boss-support-panel')).not.toContainText('需我处理')

  const tabs = page.locator('.boss-support-tabs')
  await expect(tabs.getByRole('button', { name: /需要我处理\s*0/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /待复核\s*0/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /培训考试\s*0/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /风险门店\s*2/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /岗位进度\s*1/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /已完成\s*0/ })).toBeVisible()

  await tabs.getByRole('button', { name: /待复核/ }).click()
  await expect(page.getByText('当前没有等待复核的经营事项')).toBeVisible()
  await tabs.getByRole('button', { name: /培训考试/ }).click()
  await expect(page.getByText('当前没有进行中的考试。')).toBeVisible()
  const riskMetric = page.getByRole('button', { name: /40 条风险提醒.*2 家风险门店/ })
  await expect(riskMetric).toContainText('40')
  await expect(riskMetric).toContainText('涉及 2 家风险门店')
  await riskMetric.click()
  await expect(tabs.getByRole('button', { name: /风险门店\s*2/ })).toHaveClass(/active/)
  await expect(page.locator('#risks')).toBeVisible()
  await expect(page.getByText(/有 34 条风险提醒/)).toBeVisible()
  await tabs.getByRole('button', { name: /岗位进度/ }).click()
  await expect(page.getByText(/40 条处理中/)).toBeVisible()
  await tabs.getByRole('button', { name: /已完成/ }).click()
  await expect(page.getByText('当前没有已处理复盘。')).toBeVisible()
})

test('risk summary follows the rendered reminders and unique real store names', async ({ page }) => {
  bossDashboardOverride = () => ({
    roleName: '老板',
    dataSource: 'TEST',
    updatedAt: '2026-07-29T12:00:00',
    todayFocus: {
      totalOpenCount: 9,
      needsBossActionCount: 0,
      roleWorkCount: 9,
      highRiskCount: 99,
      highRiskGroupCount: 99,
      doneReviewCount: 0,
    },
    needsBossAction: [],
    highRiskReminders: [{
      groupKey: '利润表|财务|真实一店|2026-06',
      sourceModule: '利润表',
      ownerName: '财务',
      storeName: '真实一店',
      month: '2026-06',
      count: 2,
      highestRisk: '严重风险',
      highestPriority: 96,
      topStores: ['真实一店 2条'],
    }, {
      groupKey: '利润表|财务|真实一店|2026-04',
      sourceModule: '利润表',
      ownerName: '财务',
      storeName: '真实一店',
      month: '2026-04',
      count: 3,
      highestRisk: '严重风险',
      highestPriority: 96,
      topStores: ['真实一店 3条'],
    }, {
      groupKey: '督导巡店|督导|真实二店|2026-07',
      sourceModule: '督导巡店',
      ownerName: '督导',
      storeName: '真实二店',
      month: '2026-07',
      count: 4,
      highestRisk: '高风险',
      highestPriority: 90,
      topStores: ['真实二店 4条'],
    }],
    roleProgress: [],
    doneReview: [],
  })

  await page.goto('/boss')

  const riskMetric = page.getByRole('button', { name: /9 条风险提醒.*2 家风险门店/ })
  await expect(riskMetric).toContainText('9')
  await expect(riskMetric).toContainText('涉及 2 家风险门店')
  await riskMetric.click()

  await expect(page.getByRole('button', { name: /风险门店\s*2/ })).toHaveClass(/active/)
  await expect(page.locator('.boss-risk-card')).toHaveCount(3)
})

test('every risk card opens its exact source record with the original context', async ({ page }) => {
  const openRisks = async () => {
    await page.goto('/boss')
    await page.getByRole('button', { name: /40 条风险提醒.*2 家风险门店/ }).click()
  }

  await openRisks()
  const profitRisk = page.locator('.boss-risk-card').filter({ hasText: '利润表 · 财务' })
  await profitRisk.getByRole('button', { name: '查看利润表' }).click()
  await expect(page).toHaveURL(/\/profit-table\?/)
  expect(new URL(page.url()).searchParams.get('storeId')).toBe('TEST-STORE')
  expect(new URL(page.url()).searchParams.get('month')).toBe('2026-07')
  expect(new URL(page.url()).searchParams.get('mode')).toBe('single')

  await openRisks()
  const inspectionRisk = page.locator('.boss-risk-card').filter({ hasText: '督导巡店 · 督导' })
  await inspectionRisk.getByRole('button', { name: '查看巡店记录' }).click()
  await expect(page).toHaveURL(/\/operations\/inspection\/records\?/)
  expect(new URL(page.url()).searchParams.get('recordId')).toBe('INSP-EXACT-001')
  expect(new URL(page.url()).searchParams.get('storeId')).toBe('TEST-STORE')
  expect(new URL(page.url()).searchParams.get('month')).toBe('2026-07')

  await openRisks()
  const warehouseRisk = page.locator('.boss-risk-card').filter({ hasText: '仓库中心 · 仓库管理员' })
  await warehouseRisk.getByRole('button', { name: '查看库存预警' }).click()
  await expect(page).toHaveURL(/\/warehouse\/alerts\?/)
  expect(new URL(page.url()).searchParams.get('warehouseId')).toBe('1')
  expect(new URL(page.url()).searchParams.get('itemId')).toBe('12')
  expect(new URL(page.url()).searchParams.get('month')).toBe('2026-07')
})

test('a stale profit risk card refreshes its exact store and month before navigation', async ({ page }) => {
  let dashboardRequests = 0
  bossDashboardOverride = () => {
    dashboardRequests += 1
    return {
      roleName: '老板',
      dataSource: 'TEST',
      updatedAt: '2026-07-29T08:00:00',
      todayFocus: {
        totalOpenCount: 1,
        needsBossActionCount: 0,
        roleWorkCount: 1,
        highRiskCount: 1,
        highRiskGroupCount: 1,
        doneReviewCount: 0,
      },
      needsBossAction: [],
      highRiskReminders: [{
        groupKey: '利润表|财务|万达2店|2026-06',
        sourceModule: '利润表',
        ownerName: '财务',
        storeName: '万达2店',
        month: '2026-06',
        count: 1,
        highestRisk: '高风险',
        highestPriority: 90,
        topStores: ['万达2店'],
        ...(dashboardRequests > 1 ? {
          action: {
            target: 'report',
            label: '查看利润表',
            params: {
              storeId: 'rg4',
              month: '2026-06',
              mode: 'single',
            },
          },
        } : {}),
      }],
      roleProgress: [],
      doneReview: [],
    }
  }

  await page.goto('/boss')
  await page.getByRole('button', { name: /1 条风险提醒.*1 家风险门店/ }).click()
  const wandaRisk = page.locator('.boss-risk-card').filter({ hasText: '万达2店' })
  await wandaRisk.getByRole('button', { name: '查看对应利润表' }).click()

  await expect(page).toHaveURL(/\/profit-table\?/)
  expect(new URL(page.url()).searchParams.get('storeId')).toBe('rg4')
  expect(new URL(page.url()).searchParams.get('month')).toBe('2026-06')
  expect(new URL(page.url()).searchParams.get('mode')).toBe('single')
  expect(dashboardRequests).toBe(2)
})

test('boss dashboard uses readable typography and one scroll owner', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/boss')
  await expect(page.locator('.sidebar-navigation-title').first()).toBeVisible()
  await expect(page.locator('.boss-kpi-strip')).toBeVisible()

  const styles = await page.evaluate(() => {
    const body = getComputedStyle(document.body)
    const nav = getComputedStyle(document.querySelector('.sidebar-navigation-link')!)
    const group = getComputedStyle(document.querySelector('.sidebar-navigation-title')!)
    const label = getComputedStyle(document.querySelector('.boss-kpi-strip > div > span:not(.kpi-icon)')!)
    const value = getComputedStyle(document.querySelector('.boss-kpi-strip b')!)
    const tab = getComputedStyle(document.querySelector('.boss-support-tabs button')!)
    const panel = getComputedStyle(document.querySelector('.boss-support-panel')!)
    const pagePanel = getComputedStyle(document.querySelector('.boss-page')!)
    return {
      family: body.fontFamily,
      bodySize: body.fontSize,
      navSize: nav.fontSize,
      navWeight: nav.fontWeight,
      groupSize: group.fontSize,
      labelSize: label.fontSize,
      valueSize: value.fontSize,
      tabSize: tab.fontSize,
      supportMinHeight: panel.minHeight,
      supportOverflow: panel.overflowY,
      pageOverflow: pagePanel.overflowY,
      pageFlex: pagePanel.flex,
      pageTransform: pagePanel.transform,
      kpiBackground: getComputedStyle(document.querySelector('.boss-kpi-strip')!).backgroundColor,
      kpiBorder: getComputedStyle(document.querySelector('.boss-kpi-strip')!).borderTopWidth,
    }
  })

  expect(styles.family).toContain('Microsoft YaHei UI')
  expect(styles.bodySize).toBe('14px')
  expect(styles.navSize).toBe('15px')
  expect(Number(styles.navWeight)).toBeGreaterThanOrEqual(600)
  expect(styles.groupSize).toBe('13px')
  expect(styles.labelSize).toBe('14px')
  expect(styles.valueSize).toBe('30px')
  expect(styles.tabSize).toBe('15px')
  expect(parseFloat(styles.supportMinHeight)).toBeGreaterThanOrEqual(330)
  expect(styles.supportOverflow).toBe('visible')
  expect(styles.pageOverflow).toBe('visible')
  expect(styles.pageFlex).toBe('0 0 auto')
  expect(styles.pageTransform).toBe('none')
  expect(styles.kpiBackground).toBe('rgb(255, 255, 255)')
  expect(styles.kpiBorder).toBe('1px')
})

test('zero values remain visible while a failed profit request does not hide review data', async ({ page }) => {
  await page.route('**/api/finance/months', (route) => route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ success: false, message: 'TEST finance unavailable' }),
  }))

  await page.goto('/boss')

  const strip = page.locator('.boss-kpi-strip')
  await expect(strip).toBeVisible()
  await expect(strip.locator('.kpi-item').filter({ hasText: '营业额' })).toContainText('暂时无法获取')
  await expect(strip.locator('.kpi-item').filter({ hasText: '净利润' })).toContainText('暂时无法获取')
  await expect(strip.locator('.kpi-item').filter({ hasText: '待复核' }).locator('.kpi-value')).toHaveText('0')
  await expect(page.locator('.boss-primary-grid')).toBeVisible()
  await expect(page.locator('.boss-support-panel')).toBeVisible()
})

test('month and store filters are forwarded to the real profit query', async ({ page }) => {
  const entryRequests: URL[] = []
  await page.route('**/api/finance/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/finance/months') return route.fulfill(ok(['2026-07', '2026-06']))
    if (url.pathname === '/api/finance/entries') {
      entryRequests.push(url)
      return route.fulfill(ok([{
        storeId: 'TEST-STORE',
        storeName: 'TEST 门店',
        month: url.searchParams.get('month'),
        sales: 1200,
        net: 240,
        risk: '正常',
      }]))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/boss?month=2026-06&storeId=TEST-STORE')

  const revenueMetric = page.locator('.kpi-item').filter({ hasText: '营业额' })
  await expect(revenueMetric).toContainText('2026-06')
  await expect(revenueMetric.locator('.kpi-value')).toContainText('1,200')
  await expect(page.locator('.kpi-item').filter({ hasText: '净利润' })).toContainText('净利率 20.0%')
  expect(entryRequests.length).toBeGreaterThan(0)
  expect(entryRequests.every((url) => url.searchParams.get('storeId') === 'TEST-STORE')).toBeTruthy()
  expect(entryRequests.some((url) => url.searchParams.get('month') === '2026-06')).toBeTruthy()
})
