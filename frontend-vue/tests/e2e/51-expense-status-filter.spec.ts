import { expect, test, type Page, type Route } from '@playwright/test'

const session = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [
    'finance.expense.read',
    'finance.expense.create',
    'finance.expense.review',
  ],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const completedExpense = {
  id: 'expense-completed-1',
  storeId: 'rg1',
  storeName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  month: '2026-07',
  expenseDate: '2026-07-27',
  category: '设备维修',
  expenseType: '设备维修',
  amount: 155,
  status: '已完成',
  reason: '设备维修',
  financeRemark: '财务审核通过',
  attachments: [],
}

const lifecycleStatuses = ['草稿', '待审核', '待补资料', '已驳回', '已完成'] as const
const lifecycleLabels = ['全部状态', ...lifecycleStatuses]

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, code: 'OK', message: 'OK', data }),
})

type ExpenseFixture = typeof completedExpense

async function fulfillApi(route: Route, expenseRows: ExpenseFixture[], expenseRequests: URL[]) {
  const url = new URL(route.request().url())
  const pathname = url.pathname
  if (pathname === '/api/auth/me') return route.fulfill(ok(session))
  if (pathname === '/api/stores') {
    return route.fulfill(ok([{
      id: 'rg1',
      code: 'rg1',
      name: '荆州之星店',
      brandId: 1,
      brandName: '茹菓',
      status: '营业中',
    }]))
  }
  if (pathname === '/api/expenses') {
    expenseRequests.push(url)
    const status = url.searchParams.get('status')
    return route.fulfill(ok(status
      ? expenseRows.filter((expense) => expense.status === status)
      : expenseRows))
  }
  return route.fulfill(ok([]))
}

async function prepare(page: Page, expenseRows: ExpenseFixture[]) {
  const expenseRequests: URL[] = []
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'EXPENSE-STATUS-FILTER-TEST')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => fulfillApi(route, expenseRows, expenseRequests))
  return expenseRequests
}

test('状态筛选始终提供完整报销生命周期而不是只显示当前结果状态', async ({ page }) => {
  const expenseRequests = await prepare(page, [completedExpense])

  await page.goto('/expenses')
  await expect(page.getByRole('heading', { name: '报销栏', level: 1 })).toBeVisible()

  const statusFilter = page.getByRole('combobox', { name: '状态筛选' })
  await expect(statusFilter).toBeVisible()
  const statusLabels = await statusFilter.locator('option').allTextContents()

  expect(statusLabels).toEqual(lifecycleLabels)

  for (const status of lifecycleStatuses) {
    await statusFilter.selectOption(status)
    await expect.poll(() => expenseRequests.at(-1)?.searchParams.get('status')).toBe(status)
    await expect(statusFilter.locator('option')).toHaveText(lifecycleLabels)
    if (status !== '已完成') {
      await expect(page.getByText('当前没有报销审核记录。')).toBeVisible()
    }
  }

  await expect(page.getByText('荆州之星店')).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => page.evaluate(() => (
    document.documentElement.scrollWidth <= document.documentElement.clientWidth
  ))).toBe(true)
})

test('未完成的报销状态统一计入待处理统计', async ({ page }) => {
  const statuses = ['草稿', '待审核', '待补资料', '已驳回', '已完成']
  await prepare(page, statuses.map((status, index) => ({
    ...completedExpense,
    id: `expense-${index + 1}`,
    status,
  })))

  await page.goto('/expenses')
  const pendingCard = page.locator('.metric-card').filter({ hasText: '待处理' })
  const completedCard = page.locator('.metric-card').filter({ hasText: '已完成' })

  await expect(pendingCard.locator('b')).toHaveText('4')
  await expect(completedCard.locator('b')).toHaveText('1')
})
