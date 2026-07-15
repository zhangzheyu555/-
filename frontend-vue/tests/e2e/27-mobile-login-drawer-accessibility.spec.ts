import { expect, test, type Page } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const storeManagerSession = {
  id: 100,
  tenantId: 1,
  tenantName: 'TEST 租户',
  displayName: '测试店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['TEST-STORE'],
  permissions: ['system.dashboard.read', 'warehouse.store.read', 'assistant.use'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] },
    WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] },
  },
  boundStoreId: 'TEST-STORE',
  boundStoreName: '测试门店',
  defaultWorkspace: '/warehouse',
  permissionVersion: 1,
}

const mobileProjectWidths: Record<string, number> = {
  'iphone-390': 390,
  'android-412': 412,
  'ipad-768': 768,
}

async function openStoreShell(page: Page) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-STORE-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, storeManagerSession)
  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/auth/me') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: storeManagerSession }),
      })
    }
    if (pathname === '/api/auth/logout') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ success: true, data: null }),
      })
    }
    return route.abort('failed')
  })
  await page.goto('/warehouse')
  await expect(page.getByRole('button', { name: '打开菜单' })).toBeVisible()
}

test.describe('mobile login and navigation shell', () => {
  for (const width of [375, 390, 430, 768]) {
    test(`login has no horizontal overflow and usable controls at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 812 })
      await page.route('**/api/health', (route) => route.fulfill({ status: 503, body: '' }))
      await page.goto('/login')

      await expectNoWholePageOverflow(page, `login ${width}px`)
      for (const locator of [
        page.locator('.input-shell').first(),
        page.locator('.input-shell').nth(1),
        page.getByRole('button', { name: '登录', exact: true }),
      ]) {
        const box = await locator.boundingBox()
        expect(box?.height, `${width}px 登录控件的点击高度`).toBeGreaterThanOrEqual(44)
      }
    })
  }

  test('mobile drawer is modal, traps focus, restores focus and closes on navigation in configured device projects', async ({ page }, testInfo) => {
    const expectedWidth = mobileProjectWidths[testInfo.project.name]
    test.skip(!expectedWidth, '仅在 iPhone、Android 和 iPad 设备项目中执行')
    expect(page.viewportSize()?.width).toBe(expectedWidth)
    await openStoreShell(page)

    const menuButton = page.locator('button.mobile-menu-button')
    const searchTrigger = page.getByRole('button', { name: '打开全局搜索' })
    const searchTriggerBox = await searchTrigger.boundingBox()
    expect(searchTriggerBox?.height, `${testInfo.project.name} 打开全局搜索按钮的点击高度`).toBeGreaterThanOrEqual(44)
    await expect(menuButton).toHaveAttribute('aria-expanded', 'false')
    await expect(menuButton).toHaveAttribute('aria-controls', 'mobile-navigation-drawer')
    await menuButton.click()

    const drawer = page.getByRole('dialog', { name: '菜单' })
    const closeButton = drawer.getByRole('button', { name: '关闭菜单' })
    await expect(drawer).toHaveAttribute('aria-modal', 'true')
    await expect(menuButton).toHaveAttribute('aria-expanded', 'true')
    const closeButtonBox = await closeButton.boundingBox()
    expect(closeButtonBox?.height, `${testInfo.project.name} 关闭菜单按钮的点击高度`).toBeGreaterThanOrEqual(44)
    await expect(closeButton).toBeFocused()

    await page.keyboard.press('Shift+Tab')
    await expect(drawer.getByRole('button', { name: '退出登录' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(closeButton).toBeFocused()

    await page.keyboard.press('Escape')
    await expect(drawer).toHaveCount(0)
    await expect(menuButton).toHaveAttribute('aria-expanded', 'false')
    await expect(menuButton).toBeFocused()

    await menuButton.click()
    await drawer.getByRole('link', { name: '门店经营助手' }).click()
    await expect(page).toHaveURL(/\/assistant$/)
    await expect(drawer).toHaveCount(0)
    await expect(menuButton).toHaveAttribute('aria-expanded', 'false')

    await page.goto('/warehouse')
    await menuButton.click()
    await page.getByRole('dialog', { name: '菜单' }).getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL(/\/login$/)
    await expect.poll(() => page.evaluate(() => localStorage.getItem('ai_profit_vue_token'))).toBeNull()
  })

  test('configured mobile projects keep the login page within their native device viewport', async ({ page }, testInfo) => {
    const expectedWidth = mobileProjectWidths[testInfo.project.name]
    test.skip(!expectedWidth, '仅在 iPhone、Android 和 iPad 设备项目中执行')
    expect(page.viewportSize()?.width).toBe(expectedWidth)
    await page.route('**/api/health', (route) => route.fulfill({ status: 503, body: '' }))
    await page.goto('/login')

    await expectNoWholePageOverflow(page, `${testInfo.project.name} login`)
    const loginButton = page.getByRole('button', { name: '登录', exact: true })
    const box = await loginButton.boundingBox()
    expect(box?.height, `${testInfo.project.name} 登录按钮的点击高度`).toBeGreaterThanOrEqual(44)
  })
})
