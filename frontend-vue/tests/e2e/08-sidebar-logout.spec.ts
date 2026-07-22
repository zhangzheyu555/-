import { expect, test, type Page } from '@playwright/test'

const roleCases = [
  { role: 'BOSS', label: '老板（系统管理员）', name: '测试老板', assistant: true },
  { role: 'FINANCE', label: '财务', name: '测试财务', assistant: true },
  { role: 'STORE_MANAGER', label: '店长', name: '测试店长', assistant: true },
  { role: 'WAREHOUSE', label: '仓库管理员', name: '测试仓库管理员', assistant: true },
  { role: 'SUPERVISOR', label: '督导', name: '测试督导', assistant: true },
] as const

const roleContracts: Record<string, { permissions: string[]; dataScopes: Record<string, { mode: string; storeIds: string[] }>; defaultWorkspace: string }> = {
  BOSS: { permissions: [], dataScopes: { STORE: { mode: 'ALL', storeIds: [] }, FINANCE: { mode: 'ALL', storeIds: [] }, SALARY: { mode: 'ALL', storeIds: [] }, WAREHOUSE: { mode: 'ALL', storeIds: [] }, INSPECTION: { mode: 'ALL', storeIds: [] }, EXAM: { mode: 'ALL', storeIds: [] } }, defaultWorkspace: '/boss' },
  FINANCE: { permissions: ['system.dashboard.read', 'store.read', 'finance.profit.read', 'finance.profit.write', 'finance.export', 'expense.read', 'expense.create', 'expense.review', 'salary.read', 'salary.edit', 'assistant.use'], dataScopes: { STORE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, FINANCE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, SALARY: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/finance' },
  STORE_MANAGER: { permissions: ['system.dashboard.read', 'store.read', 'finance.profit.read', 'finance.profit.write', 'expense.read', 'expense.create', 'salary.read', 'warehouse.store.read', 'warehouse.requisition.create', 'warehouse.requisition.receive', 'inspection.read', 'exam.learn', 'exam.report', 'assistant.use'], dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, FINANCE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, SALARY: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, INSPECTION: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/store' },
  WAREHOUSE: { permissions: ['system.dashboard.read', 'warehouse.read', 'warehouse.purchase', 'warehouse.transfer.request', 'warehouse.transfer.approve', 'warehouse.transfer.ship', 'warehouse.transfer.receive', 'warehouse.requisition.process', 'warehouse.configure', 'warehouse.central.read', 'warehouse.central.manage', 'assistant.use'], dataScopes: { WAREHOUSE: { mode: 'CENTRAL_WAREHOUSE', storeIds: [] } }, defaultWorkspace: '/warehouse' },
  SUPERVISOR: { permissions: ['system.dashboard.read', 'operations.dashboard.read', 'store.read', 'warehouse.store.read', 'inspection.read', 'inspection.manage', 'exam.learn', 'exam.manage', 'exam.report', 'platform.read', 'platform.manage', 'assistant.use'], dataScopes: { STORE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, WAREHOUSE: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, INSPECTION: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] }, EXAM: { mode: 'STORE_LIST', storeIds: ['TEST-STORE'] } }, defaultWorkspace: '/operations' },
}

async function seedSession(page: Page, item: typeof roleCases[number]) {
  const contract = roleContracts[item.role]
  const sessionUser = {
    id: 100,
    tenantId: 1,
    tenantName: 'TEST 租户',
    displayName: item.name,
    role: item.role,
    roleLabel: item.label,
    storeScope: item.role === 'STORE_MANAGER' ? ['TEST-STORE'] : ['all'],
    permissions: contract.permissions,
    dataScopes: contract.dataScopes,
    boundStoreId: item.role === 'STORE_MANAGER' ? 'TEST-STORE' : null,
    boundStoreName: item.role === 'STORE_MANAGER' ? '测试门店' : null,
    defaultWorkspace: contract.defaultWorkspace,
    permissionVersion: 1,
  }
  await page.addInitScript(({ role, user }) => {
    if (sessionStorage.getItem('TEST_AUTH_SEEDED') === '1') return
    sessionStorage.setItem('TEST_AUTH_SEEDED', '1')
    localStorage.setItem('ai_profit_vue_token', `TEST-${role}-TOKEN`)
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, { role: item.role, user: sessionUser })
  return sessionUser
}

async function openProtectedShell(page: Page, item: typeof roleCases[number], width = 1366, height = 768) {
  await page.setViewportSize({ width, height })
  const sessionUser = await seedSession(page, item)
  await page.route('**/*', (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (!pathname.startsWith('/api/')) return route.continue()
    if (pathname === '/api/auth/me') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: sessionUser }),
      })
    }
    if (pathname === '/api/auth/logout' || pathname === '/api/test-protected') return route.fallback()
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [] }),
    })
  })
  await page.goto(roleContracts[item.role].defaultWorkspace)
  if (width <= 768) {
    await expect(page.locator('.mobile-menu-button')).toBeVisible()
  } else {
    await expect(page.locator('.app-sidebar--desktop')).toBeVisible()
  }
}

test.describe('sidebar structure and role visibility', () => {
  for (const item of roleCases) {
    test(`${item.role} sees a fixed user and logout area`, async ({ page }) => {
      let logoutRequests = 0
      await page.route('**/api/auth/logout', (route) => {
        logoutRequests += 1
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: null }) })
      })
      await openProtectedShell(page, item)

      const sidebar = page.locator('.app-sidebar--desktop')
      const footer = sidebar.locator('.sidebar-account')
      await expect(footer).toBeVisible()
      await expect(footer.getByTitle(item.name)).toBeVisible()
      await expect(footer).toContainText(item.label)
      await expect(footer.getByRole('button', { name: '退出登录' })).toBeVisible()

      if (item.assistant) {
        const utility = sidebar.locator('.sidebar-navigation-utility')
        await expect(utility.locator('.sidebar-navigation-title')).toHaveText('辅助工具')
        await expect(utility.getByRole('link', { name: '门店经营助手', exact: true })).toBeVisible()
        const gap = await sidebar.evaluate((element) => {
          const primary = element.querySelector('.sidebar-navigation-primary')?.getBoundingClientRect()
          const utilityBox = element.querySelector('.sidebar-navigation-utility')?.getBoundingClientRect()
          return primary && utilityBox ? utilityBox.top - primary.bottom : Number.POSITIVE_INFINITY
        })
        expect(gap).toBeLessThanOrEqual(20)
      } else {
        await expect(sidebar.locator('.sidebar-navigation-utility')).toHaveCount(0)
        await expect(sidebar).not.toContainText('门店经营助手')
      }

      const footerBox = await footer.boundingBox()
      expect(footerBox).not.toBeNull()
      expect((footerBox?.y || 0) + (footerBox?.height || 0)).toBeLessThanOrEqual(769)

      await footer.getByRole('button', { name: '退出登录' }).click()
      await expect(page).toHaveURL(/\/login$/)
      expect(logoutRequests).toBe(1)
    })
  }

  for (const viewport of [
    { width: 1920, height: 1022 },
    { width: 1920, height: 1080 },
    { width: 1366, height: 768 },
  ]) {
    test(`boss sidebar remains compact at ${viewport.width}x${viewport.height}`, async ({ page }) => {
      await openProtectedShell(page, roleCases[0], viewport.width, viewport.height)
      const result = await page.locator('.app-sidebar--desktop').evaluate((sidebar) => {
        const scroll = sidebar.querySelector('.sidebar-navigation') as HTMLElement
        const footer = sidebar.querySelector('.sidebar-account') as HTMLElement
        const style = getComputedStyle(scroll)
        return {
          overflowY: style.overflowY,
          pageOverflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
          footerBottom: footer.getBoundingClientRect().bottom,
          viewportHeight: window.innerHeight,
        }
      })
      expect(result.overflowY).toBe('auto')
      expect(result.pageOverflow).toBeLessThanOrEqual(1)
      expect(result.footerBottom).toBeLessThanOrEqual(result.viewportHeight + 1)
    })
  }
})

test.describe('logout flow', () => {
  test('logout cancels protected requests that are still in flight', async ({ page }) => {
    await page.route('**/api/test-protected', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: { ok: true } }),
      }).catch(() => undefined)
    })
    await page.route('**/api/auth/logout', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: null }),
    }))
    await openProtectedShell(page, roleCases[0])
    await page.evaluate(() => {
      const state = window as Window & { __cancelMessage?: string }
      state.__cancelMessage = '等待响应'
      void import('/src/api/http.ts')
        .then((api) => api.apiGet('/api/test-protected'))
        .then(() => { state.__cancelMessage = '请求已完成' })
        .catch((error: Error) => { state.__cancelMessage = error.message })
    })

    await page.waitForTimeout(100)
    await page.locator('.app-sidebar--desktop .sidebar-logout').click()

    await expect.poll(() => page.evaluate(() => (window as Window & { __cancelMessage?: string }).__cancelMessage))
      .toBe('请求已取消')
    await expect(page).toHaveURL(/\/login$/)
  })

  test('double click sends one request, clears auth and protects browser back', async ({ page }) => {
    let logoutRequests = 0
    await page.route('**/api/auth/logout', async (route) => {
      logoutRequests += 1
      await new Promise((resolve) => setTimeout(resolve, 120))
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: null }) })
    })
    await openProtectedShell(page, roleCases[0])
    await page.goto('/boss?step=2')

    const button = page.locator('.app-sidebar--desktop .sidebar-logout')
    await button.evaluate((element) => {
      ;(element as HTMLButtonElement).click()
      ;(element as HTMLButtonElement).click()
    })

    await expect(page).toHaveURL(/\/login$/)
    expect(logoutRequests).toBe(1)
    expect(await page.evaluate(() => ({
      token: localStorage.getItem('ai_profit_vue_token'),
      user: localStorage.getItem('ai_profit_vue_user'),
    }))).toEqual({ token: null, user: null })

    await page.goBack()
    await expect(page).toHaveURL(/\/login(?:\?|$)/)
  })

  test('backend logout failure still clears the local session without an uncaught error', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (error) => pageErrors.push(error.message))
    await page.route('**/api/auth/logout', (route) => route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, message: '测试服务暂不可用', data: null }),
    }))
    await openProtectedShell(page, roleCases[2])

    await page.locator('.app-sidebar--desktop .sidebar-logout').click()

    await expect(page).toHaveURL(/\/login$/)
    expect(await page.evaluate(() => localStorage.length)).toBe(0)
    expect(pageErrors).toEqual([])
  })

  test('mobile drawer keeps menu scroll and logout footer visible', async ({ page }) => {
    await page.route('**/api/auth/logout', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: null }),
    }))
    await openProtectedShell(page, roleCases[3], 375, 812)
    await page.getByRole('button', { name: '打开菜单' }).click()

    const drawer = page.locator('.mobile-nav-drawer')
    await expect(drawer).toHaveClass(/open/)
    await expect(drawer.locator('.sidebar-navigation-utility')).toContainText('门店经营助手')
    await expect(drawer.locator('.sidebar-account')).toBeVisible()
    await expect(drawer.getByRole('button', { name: '退出登录' })).toBeVisible()
    const overflow = await drawer.locator('.sidebar-navigation').evaluate((element) => getComputedStyle(element).overflowY)
    expect(overflow).toBe('auto')

    await drawer.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(drawer).toHaveCount(0)
  })
})
