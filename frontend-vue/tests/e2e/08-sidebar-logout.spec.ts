import { expect, test, type Page } from '@playwright/test'

const roleCases = [
  { role: 'ADMIN', label: '系统管理员', name: '测试系统管理员', assistant: true },
  { role: 'BOSS', label: '老板', name: '测试老板', assistant: true },
  { role: 'FINANCE', label: '财务', name: '测试财务', assistant: true },
  { role: 'STORE_MANAGER', label: '店长', name: '测试店长', assistant: true },
  { role: 'SUPERVISOR', label: '督导', name: '测试督导', assistant: true },
  { role: 'WAREHOUSE', label: '仓库管理员', name: '测试仓库管理员', assistant: true },
  { role: 'OPERATIONS', label: '运营', name: '测试运营', assistant: true },
  { role: 'EMPLOYEE', label: '员工', name: '测试员工', assistant: false },
] as const

async function seedSession(page: Page, item: typeof roleCases[number]) {
  await page.addInitScript((session) => {
    if (sessionStorage.getItem('TEST_AUTH_SEEDED') === '1') return
    sessionStorage.setItem('TEST_AUTH_SEEDED', '1')
    localStorage.setItem('ai_profit_vue_token', `TEST-${session.role}-TOKEN`)
    localStorage.setItem('ai_profit_vue_user', JSON.stringify({
      id: 100,
      tenantId: 1,
      tenantName: 'TEST 租户',
      displayName: session.name,
      role: session.role,
      roleLabel: session.label,
      storeScope: session.role === 'STORE_MANAGER' ? ['TEST-STORE'] : ['all'],
    }))
  }, item)
}

async function openProtectedShell(page: Page, item: typeof roleCases[number], width = 1366, height = 768) {
  await page.setViewportSize({ width, height })
  await seedSession(page, item)
  await page.goto('/no-permission')
  await expect(page.locator('.sidebar-desktop')).toBeVisible()
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

      const sidebar = page.locator('.sidebar-desktop')
      const footer = sidebar.locator('.sidebar-footer')
      await expect(footer).toBeVisible()
      await expect(footer.getByTitle(item.name)).toBeVisible()
      await expect(footer).toContainText(item.label)
      await expect(footer.getByRole('button', { name: '退出登录' })).toBeVisible()

      if (item.assistant) {
        const utility = sidebar.locator('.utility-navigation')
        await expect(utility.locator('.nav-group-title')).toHaveText('辅助工具')
        await expect(utility.locator('.nav-link')).toHaveText(/门店经营助手/)
        const gap = await sidebar.evaluate((element) => {
          const primary = element.querySelector('.primary-navigation')?.getBoundingClientRect()
          const utilityBox = element.querySelector('.utility-navigation')?.getBoundingClientRect()
          return primary && utilityBox ? utilityBox.top - primary.bottom : Number.POSITIVE_INFINITY
        })
        expect(gap).toBeLessThanOrEqual(20)
      } else {
        await expect(sidebar.locator('.utility-navigation')).toHaveCount(0)
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
      await openProtectedShell(page, roleCases[1], viewport.width, viewport.height)
      const result = await page.locator('.sidebar-desktop').evaluate((sidebar) => {
        const scroll = sidebar.querySelector('.sidebar-scroll') as HTMLElement
        const footer = sidebar.querySelector('.sidebar-footer') as HTMLElement
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
    await openProtectedShell(page, roleCases[1])
    await page.evaluate(() => {
      const state = window as Window & { __cancelMessage?: string }
      state.__cancelMessage = '等待响应'
      void import('/src/api/http.ts')
        .then((api) => api.apiGet('/api/test-protected'))
        .then(() => { state.__cancelMessage = '请求已完成' })
        .catch((error: Error) => { state.__cancelMessage = error.message })
    })

    await page.waitForTimeout(100)
    await page.locator('.sidebar-desktop .logout-button').click()

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
    await openProtectedShell(page, roleCases[1])
    await page.goto('/no-permission?step=2')

    const button = page.locator('.sidebar-desktop .logout-button')
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

    await page.locator('.sidebar-desktop .logout-button').click()

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
    await page.setViewportSize({ width: 375, height: 812 })
    await seedSession(page, roleCases[3])
    await page.goto('/no-permission')
    await page.getByRole('button', { name: '打开菜单' }).click()

    const drawer = page.locator('.mobile-nav-drawer')
    await expect(drawer).toHaveClass(/open/)
    await expect(drawer.locator('.utility-navigation')).toContainText('门店经营助手')
    await expect(drawer.locator('.sidebar-footer')).toBeVisible()
    await expect(drawer.getByRole('button', { name: '退出登录' })).toBeVisible()
    const overflow = await drawer.locator('.sidebar-scroll').evaluate((element) => getComputedStyle(element).overflowY)
    expect(overflow).toBe('auto')

    await drawer.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(drawer).toHaveCount(0)
  })
})
