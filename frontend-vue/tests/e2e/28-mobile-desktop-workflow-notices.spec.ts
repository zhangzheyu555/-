import { expect, test, type Page, type Route } from '@playwright/test'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [],
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

const dashboard = {
  months: ['2026-07'],
  brands: [],
  summary: {
    month: '2026-07',
    storeCount: 0,
    entryCount: 0,
    sales: 0,
    income: 0,
    costSum: 0,
    expenseSum: 0,
    net: 0,
    margin: 0,
    riskStoreCount: 0,
  },
  entries: [],
  trend: [],
}

const workflows = [
  {
    path: '/data-entry',
    description: '财务数据录入、月度导入与覆盖确认',
  },
  {
    path: '/users',
    description: '账号、权限和数据范围设置',
  },
  {
    path: '/export',
    description: '大批量数据导出',
  },
]

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}

async function prepare(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'MOBILE-DESKTOP-NOTICE-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossSession)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (path === '/api/finance/dashboard') return route.fulfill(ok(dashboard))
    if (path === '/api/users/authorization/catalog') {
      return route.fulfill(ok({ permissions: [], dataScopeDomains: [], dataScopeModes: [] }))
    }
    return route.fulfill(ok([]))
  })
}

test('复杂桌面流程只在 768px 以下提示请在电脑端完成', async ({ page }) => {
  await prepare(page)

  for (const workflow of workflows) {
    for (const width of [390, 768]) {
      await page.setViewportSize({ width, height: 844 })
      await page.goto(workflow.path)
      const notice = page.locator('.desktop-workflow-notice')
      await expect(notice).toBeVisible()
      await expect(notice).toContainText('请在电脑端完成')
      await expect(notice).toContainText(workflow.description)
    }

    await page.setViewportSize({ width: 769, height: 900 })
    const notice = page.locator('.desktop-workflow-notice')
    await expect(notice).toBeHidden()
  }
})
