import { expect, test } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-BOSS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify({
      id: 1,
      tenantId: 1,
      tenantName: 'TEST 租户',
      displayName: 'TEST 老板',
      role: 'BOSS',
      roleLabel: '老板（系统管理员）',
      storeScope: ['all'],
    }))
  })

  await page.route('**/*', (route) => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/boss/todo-dashboard') {
      return route.fulfill(ok({
        roleName: '老板',
        dataSource: 'TEST',
        updatedAt: '2026-07-10T12:00:00',
        todayFocus: {
          totalOpenCount: 34,
          needsBossActionCount: 0,
          roleWorkCount: 34,
          highRiskCount: 34,
          highRiskGroupCount: 1,
          doneReviewCount: 0,
          summary: '今天暂无必须老板拍板的事项；岗位仍有 34 条高风险事项。',
        },
        needsBossAction: [],
        highRiskReminders: [{
          groupKey: 'TEST-RISK',
          sourceModule: 'finance',
          ownerName: '财务',
          storeName: 'TEST 门店',
          count: 34,
          highestRisk: 'RISK',
          highestPriority: 95,
          earliestDueAt: '2026-07-10T18:00:00',
          topStores: ['TEST 门店'],
        }],
        roleProgress: [{
          ownerName: '财务',
          openCount: 34,
          riskCount: 34,
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
  await expect(tabs.getByRole('button', { name: /风险门店\s*1/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /岗位进度\s*1/ })).toBeVisible()
  await expect(tabs.getByRole('button', { name: /已完成\s*0/ })).toBeVisible()

  await tabs.getByRole('button', { name: /待复核/ }).click()
  await expect(page.getByText('当前没有等待复核的经营事项')).toBeVisible()
  await tabs.getByRole('button', { name: /培训考试/ }).click()
  await expect(page.getByText('当前没有进行中的考试。')).toBeVisible()
  await tabs.getByRole('button', { name: /风险门店/ }).click()
  await expect(page.getByText(/有 34 条风险提醒/)).toBeVisible()
  await tabs.getByRole('button', { name: /岗位进度/ }).click()
  await expect(page.getByText(/34 条处理中/)).toBeVisible()
  await tabs.getByRole('button', { name: /已完成/ }).click()
  await expect(page.getByText('当前没有已处理复盘。')).toBeVisible()
})

test('boss dashboard uses readable typography and one scroll owner', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/boss')

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

  await expect(page.getByText('2026-06 经营数据')).toBeVisible()
  await expect(page.locator('.kpi-item').filter({ hasText: '营业额' }).locator('.kpi-value')).toContainText('1,200')
  await expect(page.locator('.kpi-item').filter({ hasText: '净利润' })).toContainText('净利率 20.0%')
  expect(entryRequests.length).toBeGreaterThan(0)
  expect(entryRequests.every((url) => url.searchParams.get('storeId') === 'TEST-STORE')).toBeTruthy()
  expect(entryRequests.some((url) => url.searchParams.get('month') === '2026-06')).toBeTruthy()
})
