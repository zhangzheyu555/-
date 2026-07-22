import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const boss = {
  id: 971,
  tenantId: 1,
  tenantName: 'FLOW H2 合成租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['system.dashboard.read', 'daily_loss.read', 'daily_loss.review', 'inspection.read', 'warehouse.read'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    INSPECTION: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    WAREHOUSE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

type TodoAction = { target: string, params: Record<string, string> }

function actionItem(action: TodoAction) {
  return {
    id: 'boss-escalation-flow-nav',
    title: 'FLOW 来源上下文',
    summary: '仅验证服务端发放的白名单跳转参数。',
    status: 'PENDING',
    priority: 2,
    storeId: 's1',
    storeName: '合成门店',
    month: '2026-07',
    ownerName: '督导',
    sourceModule: '日报损复核',
    sourceRecordId: 'source-1',
    dataSource: 'todo_escalation',
    escalatedToBoss: true,
    action: { target: action.target, label: '查看来源', params: { ...action.params, unsafe: 'must-not-pass' } },
  }
}

async function prepare(page: Page, action: TodoAction) {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('[API Error]')) errors.push(message.text())
  })
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'FLOW-TODO-NAV-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, boss)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(boss))
    if (url.pathname === '/api/boss/todo-dashboard') {
      return route.fulfill(ok({
        roleName: '老板',
        dataSource: 'FLOW H2 mock',
        updatedAt: '2026-07-21T10:00:00',
        todayFocus: { needsBossActionCount: 1, highRiskCount: 0, roleWorkCount: 0, doneReviewCount: 0, summary: '来源跳转验证' },
        needsBossAction: [actionItem(action)], highRiskReminders: [], roleProgress: [], doneReview: [],
      }))
    }
    if (url.pathname === '/api/boss/exam-summary') return route.fulfill(ok({
      activeExamCount: 0, assignedCount: 0, completedCount: 0, completionRate: 0, passedCount: 0, passRate: 0, overdueCount: 0, averageScore: 0, riskStores: [],
    }))
    if (url.pathname === '/api/finance/months') return route.fulfill(ok(['2026-07']))
    if (url.pathname === '/api/finance/entries') return route.fulfill(ok([]))
    if (url.pathname === '/api/todos') return route.fulfill(ok([]))
    if (url.pathname === '/api/stores') return route.fulfill(ok([{ id: 's1', name: '合成门店', brandName: '合成品牌' }]))
    if (url.pathname === '/api/daily-loss/items') return route.fulfill(ok([]))
    if (url.pathname === '/api/daily-loss/reports') return route.fulfill(ok([{
      id: 'dlr-flow-1', storeId: 's1', storeName: '合成门店', lossDate: '2026-07-20', month: '2026-07',
      reported: true, status: 'SUBMITTED', statusLabel: '待复核', details: [], attachments: [],
    }]))
    return route.fulfill(ok([]))
  })
  return errors
}

test('desktop role-todo actions preserve only whitelisted daily-loss, inspection, and warehouse context', async ({ page }) => {
  const errors = await prepare(page, {
    target: 'daily-loss',
    params: { reportId: 'dlr-flow-1', storeId: 's1', month: '2026-07', lossDate: '2026-07-20', mode: 'review' },
  })
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/boss')
  await page.getByRole('button', { name: '查看来源', exact: true }).click()
  await expect(page).toHaveURL(/\/daily-loss\?storeId=s1&month=2026-07&reportId=dlr-flow-1&lossDate=2026-07-20&mode=review/)
  await expect(page).not.toHaveURL(/unsafe=/)
  await expect(page.getByRole('heading', { name: '每日报损', exact: true })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '报损详情' })).toBeVisible()
  await expectNoWholePageOverflow(page, '1280px FLOW 日报损待办跳转')
  expect(errors).toEqual([])
})

test('role-todo route mapper carries source ids for inspection and warehouse without accepting arbitrary parameters', async ({ browser }) => {
  const scenarios = [
    {
      action: { target: 'inspect', params: { storeId: 's1', month: '2026-07', inspectionId: 'ins-flow-1' } },
      expected: /\/operations\/inspection\/records\?storeId=s1&month=2026-07&recordId=ins-flow-1/,
    },
    {
      action: { target: 'warehouse', params: { storeId: 's1', month: '2026-07', requisitionId: 'req-flow-1' } },
      expected: /\/warehouse\/requests\?storeId=s1&month=2026-07&requisitionId=req-flow-1/,
    },
  ]
  for (const scenario of scenarios) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } })
    const page = await context.newPage()
    const errors = await prepare(page, scenario.action)
    await page.goto('/boss')
    await page.getByRole('button', { name: '查看来源', exact: true }).click()
    await expect(page).toHaveURL(scenario.expected)
    await expect(page).not.toHaveURL(/unsafe=/)
    expect(errors).toEqual([])
    await context.close()
  }
})
