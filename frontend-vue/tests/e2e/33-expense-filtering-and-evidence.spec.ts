import { expect, test, type Page } from '@playwright/test'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const stores = [
  { id: 'store-a', code: 'A', name: '门店 A', brandId: 1, brandName: '茹菓' },
  { id: 'store-b', code: 'B', name: '门店 B', brandId: 2, brandName: '霸王茶姬' },
]

const tinyPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAF/gJ+Xw1fYQAAAABJRU5ErkJggg==',
  'base64',
)

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}

function expense(id = 'expense-1', overrides: Record<string, unknown> = {}) {
  return {
    id,
    storeId: 'store-b',
    storeCode: 'B',
    storeName: '门店 B',
    brandId: 2,
    brandName: '霸王茶姬',
    month: '2026-08',
    expenseDate: '2026-08-16',
    amount: 128.5,
    category: '设备维护',
    reason: '受控测试报销',
    status: '待审核',
    attachments: [],
    supplements: [],
    ...overrides,
  }
}

interface ExpenseMockOptions {
  rows: Array<Record<string, unknown>>
  expenseRequests: URL[]
  mutationEvents?: string[]
  createIdempotencyKeys?: string[]
}

async function prepareExpensePage(page: Page, options: ExpenseMockOptions) {
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'expense-filter-test-token')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossSession)

  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()

    if (url.pathname === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (url.pathname === '/api/stores') return route.fulfill(ok(stores))
    if (url.pathname === '/api/expenses' && route.request().method() === 'GET') {
      options.expenseRequests.push(url)
      return route.fulfill(ok(options.rows))
    }
    if (url.pathname === '/api/expenses' && route.request().method() === 'POST') {
      const key = route.request().headers()['idempotency-key'] || ''
      options.createIdempotencyKeys?.push(key)
      if (options.createIdempotencyKeys?.length === 1) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'CONTROLLED_CREATE_FAILURE', message: '受控创建失败，请重试。' }),
        })
      }
      const payload = route.request().postDataJSON() as Record<string, unknown>
      const created = expense('expense-created', payload)
      options.rows.push(created)
      return route.fulfill(ok(created))
    }

    const current = options.rows[0]
    if (url.pathname === `/api/expenses/${String(current?.id || '')}` && route.request().method() === 'PUT') {
      const payload = route.request().postDataJSON() as Record<string, unknown>
      Object.assign(current, payload)
      options.mutationEvents?.push('update')
      return route.fulfill(ok(current))
    }
    if (url.pathname === `/api/expenses/${String(current?.id || '')}/approve`) {
      Object.assign(current, { status: '已完成', reviewNote: '财务已核对凭证和金额。' })
      options.mutationEvents?.push('approve')
      return route.fulfill(ok(current))
    }
    if (url.pathname === '/api/storage/upload') {
      options.mutationEvents?.push('upload')
      return route.fulfill(ok({
        id: 11,
        fileName: 'new-primary.png',
        contentType: 'image/png',
        fileSize: tinyPng.length,
        url: '/api/storage/attachments/11',
      }))
    }
    if (url.pathname.startsWith(`/api/expenses/${String(current?.id || '')}/attachments/`) && route.request().method() === 'DELETE') {
      const pathSegments = url.pathname.split('/')
      const attachmentId = pathSegments[pathSegments.length - 1]
      options.mutationEvents?.push(`delete-primary-${attachmentId}`)
      return route.fulfill(ok(null))
    }
    if (url.pathname.startsWith('/api/storage/attachments/')) {
      return route.fulfill({ status: 200, contentType: 'image/png', body: tinyPng })
    }
    return route.fulfill(ok([]))
  })
}

function querySnapshot(url: URL) {
  return {
    month: url.searchParams.get('month'),
    brandId: url.searchParams.get('brandId'),
    storeId: url.searchParams.get('storeId'),
    status: url.searchParams.get('status'),
  }
}

test('报销筛选和审核后的刷新始终携带当前服务器查询参数', async ({ page }) => {
  const expenseRequests: URL[] = []
  const mutationEvents: string[] = []
  await prepareExpensePage(page, {
    rows: [expense()],
    expenseRequests,
    mutationEvents,
  })

  await page.goto('expenses')
  await expect.poll(() => expenseRequests.length).toBeGreaterThan(0)

  await page.getByLabel('月份筛选').fill('2026-08')
  await expect.poll(() => expenseRequests.some((request) => request.searchParams.get('month') === '2026-08')).toBe(true)

  await page.getByLabel('品牌').selectOption('2')
  await expect.poll(() => expenseRequests.some((request) => request.searchParams.get('brandId') === '2')).toBe(true)

  await page.getByLabel('门店', { exact: true }).selectOption('store-b')
  await expect.poll(() => expenseRequests.some((request) => request.searchParams.get('storeId') === 'store-b')).toBe(true)

  await page.getByLabel('状态筛选').selectOption('待审核')
  await expect.poll(() => expenseRequests.some((request) => request.searchParams.get('status') === '待审核')).toBe(true)

  const expectedQuery = { month: '2026-08', brandId: '2', storeId: 'store-b', status: '待审核' }
  await page.getByRole('button', { name: '刷新' }).click()
  await expect.poll(() => querySnapshot(expenseRequests[expenseRequests.length - 1]!)).toEqual(expectedQuery)

  const requestCountBeforeApprove = expenseRequests.length
  await page.getByRole('button', { name: '通过' }).click()
  await expect.poll(() => mutationEvents.includes('approve')).toBe(true)
  await expect.poll(() => expenseRequests.length).toBeGreaterThan(requestCountBeforeApprove)
  await expect.poll(() => querySnapshot(expenseRequests[expenseRequests.length - 1]!)).toEqual(expectedQuery)
  await expect(page.getByText('财务说明')).toBeVisible()
  await expect(page.getByText('财务已核对凭证和金额。')).toBeVisible()
})

test('今日状态清晰显示且刷新后同步最新报销统计', async ({ page }) => {
  const expenseRequests: URL[] = []
  const rows = [expense()]
  await prepareExpensePage(page, { rows, expenseRequests })

  await page.goto('expenses')
  const todayStatus = page.locator('.metric-card').filter({ hasText: '今日状态' })
  const recordCount = page.locator('.metric-card').filter({ hasText: '记录数量' })
  await expect(todayStatus).toContainText('0 单')
  await expect(todayStatus.locator('b')).toHaveCSS('color', 'rgb(255, 255, 255)')
  await expect(recordCount).toContainText('1')

  const now = new Date()
  const localToday = [
    now.getFullYear(),
    String(now.getMonth() + 1).padStart(2, '0'),
    String(now.getDate()).padStart(2, '0'),
  ].join('-')
  rows.push(expense('expense-today', { expenseDate: localToday, amount: 20 }))

  const requestCount = expenseRequests.length
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect.poll(() => expenseRequests.length).toBeGreaterThan(requestCount)
  await expect(todayStatus).toContainText('1 单')
  await expect(recordCount).toContainText('2')
})

test('审核和要求补资料说明都限制为 255 个字符并给出中文提示', async ({ page }) => {
  const expenseRequests: URL[] = []
  await prepareExpensePage(page, { rows: [expense()], expenseRequests })

  await page.goto('expenses')
  await expect(page.getByRole('button', { name: '要求补资料' })).toBeVisible()
  await page.getByRole('button', { name: '要求补资料' }).click()

  const requestInfoDialog = page.getByRole('dialog', { name: '要求补充资料' })
  await expect(requestInfoDialog.getByRole('textbox')).toHaveAttribute('maxlength', '255')
  await expect(requestInfoDialog.getByText(/补充资料说明最多 255 个字符/)).toBeVisible()
  await requestInfoDialog.getByRole('button', { name: '关闭' }).click()

  await page.getByRole('button', { name: '驳回' }).click()
  const rejectDialog = page.getByRole('alertdialog', { name: '请输入驳回原因' })
  await expect(rejectDialog.getByRole('textbox')).toHaveAttribute('maxlength', '255')
  await expect(rejectDialog.getByText(/最多 255 个字符/)).toBeVisible()
})

test('更换主凭证只删除明确由 imageUrl 指向的旧主附件，不触碰补充附件', async ({ page }) => {
  const expenseRequests: URL[] = []
  const mutationEvents: string[] = []
  await prepareExpensePage(page, {
    rows: [expense('expense-replace', {
      status: '草稿',
      imageUrl: '/api/storage/attachments/10',
      attachments: [{
        id: 10,
        fileName: 'old-primary.png',
        contentType: 'image/png',
        previewUrl: '/api/storage/attachments/10',
        downloadUrl: '/api/storage/attachments/10',
      }],
      supplements: [{
        id: 77,
        note: '历史补充说明',
        attachments: [{ id: 77, fileName: 'supplement.pdf', contentType: 'application/pdf' }],
      }],
    })],
    expenseRequests,
    mutationEvents,
  })

  await page.goto('expenses')
  await page.getByRole('button', { name: '编辑' }).click()
  const drawer = page.getByRole('dialog', { name: '编辑报销' })
  await drawer.locator('input[type="file"]').setInputFiles({
    name: 'new-primary.png',
    mimeType: 'image/png',
    buffer: tinyPng,
  })
  await expect(drawer.getByText('new-primary.png')).toBeVisible()
  await drawer.getByRole('button', { name: '保存草稿' }).click()

  await expect.poll(() => mutationEvents).toEqual(['update', 'upload', 'update', 'delete-primary-10'])
  expect(mutationEvents).not.toContain('delete-supplement-77')
})

test('新建报销失败重试复用同一个 Idempotency-Key', async ({ page }) => {
  const expenseRequests: URL[] = []
  const createIdempotencyKeys: string[] = []
  await prepareExpensePage(page, {
    rows: [],
    expenseRequests,
    createIdempotencyKeys,
  })

  await page.goto('expenses')
  await page.getByRole('button', { name: '新增报销' }).click()
  const drawer = page.getByRole('dialog', { name: '新增报销' })
  await drawer.getByLabel('门店').selectOption('store-b')
  await drawer.getByLabel('报销月份').fill('2026-08')
  await drawer.getByLabel('报销日期').fill('2026-08-16')
  await drawer.getByLabel('报销金额').fill('99')
  await drawer.getByLabel('报销类别').fill('门店耗材')
  await drawer.getByLabel('报销说明').fill('验证新建报销幂等重试')

  await drawer.getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => createIdempotencyKeys.length).toBe(1)
  await expect(drawer.getByText('受控创建失败，请重试。')).toBeVisible()

  await drawer.getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => createIdempotencyKeys.length).toBe(2)
  expect(createIdempotencyKeys[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/i)
  expect(createIdempotencyKeys[1]).toBe(createIdempotencyKeys[0])
})
