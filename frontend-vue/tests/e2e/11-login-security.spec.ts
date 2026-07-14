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
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['system.dashboard.read'],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [] } },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

async function mockLogin(page: import('@playwright/test').Page, session = bossSession) {
  await page.route('**/api/auth/login', (route) => route.fulfill(ok({
    token: 'TEST-LOGIN-TOKEN',
    // 登录接口的轻量用户信息不能决定工作台，完整会话以 /api/auth/me 为准。
    user: { ...session, permissions: [], defaultWorkspace: '/store' },
  })))
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(session)))
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/health', (route) => route.fulfill({ status: 503, body: '' }))
  await page.goto('/login')
})

test('login starts empty and validates each field beside its input', async ({ page }) => {
  await expect(page.getByLabel('账号', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
  await expect(page.getByText('系统运行正常')).toHaveCount(0)

  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('请输入账号')).toBeVisible()

  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('请输入密码')).toBeVisible()

  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'password')
  await page.getByRole('button', { name: '显示密码' }).click()
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'text')
})

test('synthetic keyboard events do not crash caps lock detection', async ({ page }) => {
  const pageErrors: string[] = []
  page.on('pageerror', (error) => pageErrors.push(error.message))

  await page.getByLabel('密码', { exact: true }).dispatchEvent('keydown')
  await page.getByLabel('密码', { exact: true }).dispatchEvent('keyup')

  expect(pageErrors).toEqual([])
  await expect(page.getByText('大写锁定已开启')).toHaveCount(0)
})

test('401 and 429 responses are classified correctly', async ({ page }) => {
  let status = 401
  await page.route('**/api/auth/login', (route) => route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify({
      success: false,
      code: status === 401 ? 'LOGIN_FAILED' : 'LOGIN_RATE_LIMITED',
      message: status === 401 ? '账号或密码错误' : '登录尝试过多，请稍后再试',
      data: null,
    }),
  }))

  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByLabel('密码', { exact: true }).fill('incorrect-password')
  await page.getByLabel('密码', { exact: true }).press('Enter')
  await expect(page.getByText('账号或密码错误')).toBeVisible()

  status = 429
  await page.getByLabel('密码', { exact: true }).fill('another-password')
  await page.getByLabel('密码', { exact: true }).press('Enter')
  await expect(page.getByText('登录尝试过多，请稍后再试')).toBeVisible()
})

test('backend proxy failure is shown as a connection problem', async ({ page }) => {
  await page.route('**/api/auth/login', (route) => route.fulfill({
    status: 503,
    contentType: 'application/json',
    body: JSON.stringify({
      success: false,
      code: 'BACKEND_UNAVAILABLE',
      message: '暂时无法连接服务器',
      data: null,
    }),
  }))

  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page.getByText('暂时无法连接服务器')).toBeVisible()
})

test('remember account stores only the username and returns to the target page', async ({ page }) => {
  await page.unroute('**/api/health')
  await page.route('**/api/health', (route) => route.fulfill(ok({ status: 'UP', service: 'test', time: '2026-07-10' })))
  await mockLogin(page)
  await page.route('**/api/boss/todo-dashboard*', (route) => route.fulfill(ok({
    todayFocus: {}, needsBossAction: [], highRiskReminders: [], roleProgress: [], doneReview: [],
  })))
  await page.route('**/api/boss/exam-summary*', (route) => route.fulfill(ok({ riskStores: [] })))
  await page.route('**/api/finance/months*', (route) => route.fulfill(ok([])))
  await page.route('**/api/todos', (route) => route.fulfill(ok([])))

  await page.goto('/login?redirect=/boss')
  await page.getByLabel('账号', { exact: true }).fill('remembered-user')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByLabel('记住账号').check()
  await page.getByLabel('密码', { exact: true }).press('Enter')
  await expect(page).toHaveURL(/\/boss$/)

  const storage = await page.evaluate(() => Object.fromEntries(Object.entries(localStorage)))
  expect(storage.ai_profit_remembered_username).toBe('remembered-user')
  expect(JSON.stringify(storage)).not.toContain('private-password')
})

test('login waits for the complete session and routes from /api/auth/me permissions', async ({ page }) => {
  let sessionRequests = 0
  await page.route('**/api/auth/login', (route) => route.fulfill(ok({
    token: 'TEST-LOGIN-TOKEN',
    user: { ...bossSession, role: 'STORE_MANAGER', defaultWorkspace: '/store' },
  })))
  await page.route('**/api/auth/me', async (route) => {
    sessionRequests += 1
    await new Promise((resolve) => setTimeout(resolve, 150))
    await route.fulfill(ok(bossSession))
  })

  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/boss$/)
  expect(sessionRequests).toBe(1)
})

test('inaccessible default workspace falls back to the first permitted role workspace', async ({ page }) => {
  const financeSession = {
    ...bossSession,
    role: 'FINANCE',
    roleLabel: '财务',
    storeScope: ['all'],
    permissions: ['finance.profit.read'],
    defaultWorkspace: '/warehouse',
  }
  await mockLogin(page, financeSession)

  await page.getByLabel('账号', { exact: true }).fill('finance')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/finance$/)
})

test('store manager never falls back to another role workspace', async ({ page }) => {
  const managerWithoutStoreWorkspace = {
    ...bossSession,
    role: 'STORE_MANAGER',
    roleLabel: '店长',
    storeScope: ['STORE-001'],
    permissions: ['finance.profit.read'],
    dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['STORE-001'] } },
    defaultWorkspace: '/finance',
  }
  await mockLogin(page, managerWithoutStoreWorkspace)

  await page.getByLabel('账号', { exact: true }).fill('rg1')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/no-permission$/)
  await expect(page).not.toHaveURL(/\/finance$/)
  await expect(page.getByText('该账号尚未配置任何可用工作台')).toBeVisible()
})

test('store manager respects the backend no-workspace decision', async ({ page }) => {
  const managerWithRejectedScope = {
    ...bossSession,
    role: 'STORE_MANAGER',
    roleLabel: '店长',
    storeScope: ['STORE-001'],
    permissions: ['store.read'],
    dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['STORE-001'] } },
    defaultWorkspace: '/no-permission',
  }
  await mockLogin(page, managerWithRejectedScope)

  await page.getByLabel('账号', { exact: true }).fill('rg1')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/no-permission$/)
  await expect(page).not.toHaveURL(/\/store$/)
  await expect(page.getByRole('button', { name: '退出并重新登录' })).toBeVisible()
})

test('an inaccessible pre-login redirect falls back to the permitted role workspace', async ({ page }) => {
  const financeSession = {
    ...bossSession,
    role: 'FINANCE',
    roleLabel: '财务',
    storeScope: ['all'],
    permissions: ['finance.profit.read'],
    defaultWorkspace: '/finance',
  }
  await mockLogin(page, financeSession)
  await page.goto('/login?redirect=/store')

  await page.getByLabel('账号', { exact: true }).fill('finance')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/finance$/)
  await expect(page).not.toHaveURL(/\/no-permission/)
})

test('store manager without a bound store is stopped with a clear message', async ({ page }) => {
  const unboundStoreManager = {
    ...bossSession,
    role: 'STORE_MANAGER',
    roleLabel: '店长',
    storeScope: [],
    permissions: ['store.read'],
    dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: [] } },
    defaultWorkspace: '/store',
  }
  await mockLogin(page, unboundStoreManager)

  await page.getByLabel('账号', { exact: true }).fill('manager')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/no-permission\?reason=STORE_NOT_BOUND$/)
  await expect(page.getByText('当前店长账号尚未绑定门店')).toBeVisible()
  await expect(page.getByText('系统不会自动分配或展示其他门店数据。')).toBeVisible()

  await page.route('**/api/auth/logout', (route) => route.fulfill(ok(null)))
  await page.getByRole('button', { name: '退出并重新登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
})

test('no-permission button returns a permitted account to its role workspace', async ({ page }) => {
  const financeSession = {
    ...bossSession,
    role: 'FINANCE',
    roleLabel: '财务',
    permissions: ['finance.profit.read'],
    defaultWorkspace: '/finance',
  }
  await mockLogin(page, financeSession)

  await page.getByLabel('账号', { exact: true }).fill('finance')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.goto('/store')
  await expect(page).toHaveURL(/\/no-permission/)

  await page.getByRole('button', { name: '返回可用工作台' }).click()
  await expect(page).toHaveURL(/\/finance$/)
})

test('an account without any workspace permission exits instead of looping on no-permission', async ({ page }) => {
  const noWorkspaceSession = {
    ...bossSession,
    role: 'FINANCE',
    roleLabel: '财务',
    storeScope: [],
    permissions: [],
    dataScopes: {},
    defaultWorkspace: '/finance',
  }
  await mockLogin(page, noWorkspaceSession)
  await page.route('**/api/auth/logout', (route) => route.fulfill(ok(null)))

  await page.getByLabel('账号', { exact: true }).fill('finance')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/no-permission$/)
  await expect(page.getByText('该账号尚未配置任何可用工作台')).toBeVisible()

  await page.getByRole('button', { name: '退出并重新登录' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ai_profit_vue_token'))).toBeNull()
})

test('boss can leave no-permission even when the permission list is empty', async ({ page }) => {
  await mockLogin(page, { ...bossSession, permissions: [] })

  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.goto('/no-permission')

  await page.getByRole('button', { name: '返回可用工作台' }).click()
  await expect(page).toHaveURL(/\/boss$/)
})

test('permission version change invalidates a stored session before protected routing', async ({ page }) => {
  await page.route('**/api/auth/me', (route) => route.fulfill(ok({
    ...bossSession,
    permissionVersion: 2,
  })))
  await page.evaluate((user) => {
    localStorage.setItem('ai_profit_vue_token', 'STALE-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossSession)

  await page.goto('/boss')

  await expect(page).toHaveURL(/\/login\?redirect=(?:%2F|\/)boss$/)
  const storedAuth = await page.evaluate(() => ({
    token: localStorage.getItem('ai_profit_vue_token'),
    user: localStorage.getItem('ai_profit_vue_user'),
  }))
  expect(storedAuth).toEqual({ token: null, user: null })
})

test('a protected API 401 clears the active Pinia session and returns to login', async ({ page }) => {
  await mockLogin(page)
  await page.route('**/api/boss/todo-dashboard*', (route) => route.fulfill({
    status: 401,
    contentType: 'application/json',
    body: JSON.stringify({ success: false, code: 'UNAUTHORIZED', message: '登录已失效', data: null }),
  }))

  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByLabel('密码', { exact: true }).fill('private-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(/\/login\?redirect=(?:%2F|\/)boss$/)
  const storedAuth = await page.evaluate(() => ({
    token: localStorage.getItem('ai_profit_vue_token'),
    user: localStorage.getItem('ai_profit_vue_user'),
  }))
  expect(storedAuth).toEqual({ token: null, user: null })
})

test('submitting twice sends only one login request', async ({ page }) => {
  let requests = 0
  await page.route('**/api/auth/login', async (route) => {
    requests += 1
    await new Promise((resolve) => setTimeout(resolve, 250))
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, code: 'LOGIN_FAILED', message: '账号或密码错误', data: null }),
    })
  })
  await page.getByLabel('账号', { exact: true }).fill('boss')
  await page.getByLabel('密码', { exact: true }).fill('incorrect-password')
  await page.getByRole('button', { name: '登录', exact: true }).evaluate((button) => {
    ;(button as HTMLButtonElement).click()
    ;(button as HTMLButtonElement).click()
  })
  await expect(page.getByText('账号或密码错误')).toBeVisible()
  expect(requests).toBe(1)
})
