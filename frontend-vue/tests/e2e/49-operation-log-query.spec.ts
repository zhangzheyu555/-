import { expect, test, type Page, type Route } from '@playwright/test'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['audit.read'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const auditLogs = [
  {
    id: 101,
    operatorName: '老板',
    action: 'warehouse_item_delete',
    targetType: 'warehouse_item',
    targetId: '421',
    storeId: null,
    month: null,
    reason: '删除测试物料',
    createdAt: '2026-07-27 23:59:59',
  },
  {
    id: 102,
    operatorName: '店长·荆州之星店',
    action: 'warehouse_requisition_submit',
    targetType: 'warehouse',
    targetId: 'REQ-001',
    storeId: 'rg1',
    month: '2026-07',
    reason: '门店日常叫货',
    createdAt: '2026-07-26 09:20:00',
  },
]

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, code: 'OK', message: 'OK', data }),
})

interface PrepareOptions {
  url?: string
  failSearchAfter?: number
  totalPages?: number
}

async function prepare(page: Page, options: PrepareOptions = {}) {
  const searchRequests: URL[] = []

  async function fulfillApi(route: Route) {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/stores') {
      return route.fulfill(ok([
        { id: 'rg1', code: 'RG1', name: '荆州之星店', status: 'ACTIVE' },
        { id: 'rg2', code: 'RG2', name: '荆州大学城店', status: 'ACTIVE' },
      ]))
    }
    if (url.pathname === '/api/audit/logs/search') {
      searchRequests.push(url)
      if (options.failSearchAfter && searchRequests.length >= options.failSearchAfter) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({
            success: false,
            code: 'AUDIT_QUERY_FAILED',
            message: '日志服务暂时不可用，请稍后重试',
          }),
        })
      }
      const requestedPage = Number(url.searchParams.get('page') || 1)
      const pages = options.totalPages || 1
      const rows = pages > 1
        ? [requestedPage === 1 ? auditLogs[0] : auditLogs[1]]
        : auditLogs
      return route.fulfill(ok({
        rows,
        total: pages > 1 ? 61 : rows.length,
        page: requestedPage,
        pageSize: Number(url.searchParams.get('pageSize') || 30),
        totalPages: pages,
        operators: ['老板', '店长·荆州之星店'],
        actions: ['warehouse_item_delete', 'warehouse_requisition_submit'],
      }))
    }
    return route.fulfill(ok([]))
  }

  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'OPERATION-LOG-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, bossSession)
  await page.route(/^https?:\/\/[^/]+\/api\//, fulfillApi)
  await page.goto(options.url || '/logs')
  await expect(page.getByRole('heading', { name: '操作日志' })).toBeVisible()
  await expect.poll(() => searchRequests.length).toBeGreaterThan(0)
  return searchRequests
}

test('BOSS 可按关键词、操作人、动作、门店和日期组合查询操作日志', async ({ page }) => {
  const requests = await prepare(page)

  await expect(page.getByLabel('日志关键词')).toBeVisible()
  await expect(page.getByLabel('操作人')).toBeVisible()
  await expect(page.getByLabel('日志动作')).toBeVisible()
  await expect(page.getByLabel('日志门店')).toBeVisible()
  await expect(page.getByLabel('开始日期')).toBeVisible()
  await expect(page.getByLabel('结束日期')).toBeVisible()

  await page.getByLabel('日志关键词').fill('日常')
  await page.getByLabel('操作人').selectOption('店长·荆州之星店')
  await page.getByLabel('日志动作').selectOption('warehouse_requisition_submit')
  await page.getByLabel('日志门店').selectOption('rg1')
  await page.getByLabel('开始日期').fill('2026-07-26')
  await page.getByLabel('结束日期').fill('2026-07-27')

  await expect(page.getByText('筛选条件已修改，点击“查询日志”后更新结果。')).toBeVisible()
  expect(requests).toHaveLength(1)

  await page.getByRole('button', { name: '查询日志' }).click()
  await expect.poll(() => requests.length).toBe(2)
  const query = requests.at(-1)!.searchParams
  expect(query.get('keyword')).toBe('日常')
  expect(query.get('operatorName')).toBe('店长·荆州之星店')
  expect(query.get('action')).toBe('warehouse_requisition_submit')
  expect(query.get('storeScope')).toBe('STORE')
  expect(query.get('storeId')).toBe('rg1')
  expect(query.get('startDate')).toBe('2026-07-26')
  expect(query.get('endDate')).toBe('2026-07-27')
  expect(query.get('page')).toBe('1')
  await expect(page.getByLabel('当前查询条件')).toContainText('荆州之星店')
})

test('日期错误会就地提示且不会发送错误查询', async ({ page }) => {
  const requests = await prepare(page)

  await page.getByLabel('开始日期').fill('2026-07-27')
  await page.getByLabel('结束日期').fill('2026-07-26')
  await page.getByRole('button', { name: '查询日志' }).click()

  await expect(page.getByRole('alert')).toContainText('开始日期不能晚于结束日期')
  expect(requests).toHaveLength(1)
  await expect(page.getByLabel('开始日期')).toBeFocused()
})

test('分页只复用已应用条件，失败时保留上一批结果并提供重试', async ({ page }) => {
  const requests = await prepare(page, { totalPages: 3, failSearchAfter: 3 })

  await expect(page.getByText('删除测试物料')).toBeVisible()
  await page.getByRole('button', { name: '下一页' }).click()
  await expect.poll(() => requests.length).toBe(2)
  expect(requests[1]!.searchParams.get('page')).toBe('2')
  await expect(page.getByText('门店日常叫货')).toBeVisible()

  await page.getByLabel('日志关键词').fill('不会覆盖已应用条件')
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByRole('alert')).toContainText('日志服务暂时不可用')
  await expect(page.getByText('门店日常叫货')).toBeVisible()
  expect(requests[2]!.searchParams.get('keyword')).toBe('')
})

test('带门店参数进入日志页时，首次查询和重置都保留该范围', async ({ page }) => {
  const requests = await prepare(page, { url: '/logs?storeId=rg1' })

  expect(requests[0]!.searchParams.get('storeScope')).toBe('STORE')
  expect(requests[0]!.searchParams.get('storeId')).toBe('rg1')
  await expect(page.getByLabel('日志门店')).toHaveValue('rg1')

  await page.getByLabel('日志关键词').fill('待清除')
  await page.getByRole('button', { name: '重置条件' }).click()
  await expect.poll(() => requests.length).toBe(2)
  expect(requests[1]!.searchParams.get('keyword')).toBe('')
  expect(requests[1]!.searchParams.get('storeId')).toBe('rg1')
})

test('390px 小屏仍可完整查询并以卡片展示日志', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await prepare(page)

  await expect(page.getByRole('button', { name: '查询日志' })).toBeVisible()
  await expect(page.getByText('删除测试物料')).toBeVisible()
  const overflow = await page.evaluate(() => ({
    body: document.body.scrollWidth,
    viewport: window.innerWidth,
  }))
  expect(overflow.body).toBeLessThanOrEqual(overflow.viewport)
})
