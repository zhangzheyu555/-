import { expect, test } from '@playwright/test'
import { expectNoWholePageOverflow, loginAs, type RoleKey } from './auth.setup'

const widths = [375, 390, 430, 768]

const corePages: Array<{ role: RoleKey | null; path: string; name: string }> = [
  { role: null, path: '/login', name: 'login' },
  { role: 'store', path: '/warehouse', name: 'store-warehouse' },
  { role: 'boss', path: '/boss', name: 'boss' },
  { role: 'boss', path: '/profit', name: 'profit' },
  { role: 'boss', path: '/store-detail', name: 'store-detail' },
  { role: 'boss', path: '/users', name: 'users' },
  { role: 'boss', path: '/export', name: 'export' },
  { role: 'boss', path: '/inventory-checks', name: 'boss-inventory-checks' },
  { role: 'store', path: '/inventory-checks', name: 'store-inventory-checks' },
  { role: 'finance', path: '/expenses', name: 'finance-expenses' },
  { role: 'warehouse', path: '/warehouse', name: 'warehouse' },
  { role: 'warehouse', path: '/warehouse/items', name: 'warehouse-items' },
  { role: 'supervisor', path: '/operations/inspection', name: 'supervisor-inspection' },
  { role: 'supervisor', path: '/operations/inspection/records', name: 'inspection-records' },
  { role: 'supervisor', path: '/operations/exams', name: 'supervisor-exam' },
]

const mobileRoutesByRole: Record<RoleKey, string[]> = {
  boss: [
    '/boss',
    '/profit',
    '/profit-table',
    '/data-entry',
    '/expenses',
    '/store-detail',
    '/stores',
    '/staff',
    '/logs',
    '/users',
    '/assistant',
    '/knowledge-base',
  ],
  finance: [
    '/finance',
    '/profit',
    '/profit-table',
    '/data-entry',
    '/expenses',
    '/export',
    '/finance/salary',
    '/assistant',
  ],
  warehouse: [
    '/warehouse',
    '/warehouse/central',
    '/warehouse/shandong',
    '/warehouse/detail/1',
    '/warehouse/transfers',
    '/warehouse/items',
    '/warehouse/inventory',
    '/warehouse/requests',
    '/warehouse/purchase',
    '/warehouse/movements',
    '/warehouse/returns',
    '/warehouse/alerts',
    '/warehouse/receipts',
    '/assistant',
  ],
  store: [
    '/store',
    '/store-detail',
    '/store/salary',
    '/store/inventory',
    '/store/inventory/requisition',
    '/store/inventory/receipts',
    '/store/inventory/records',
    '/daily-loss',
    '/store/inspection/rectifications',
    '/store/exams',
    '/assistant',
  ],
  supervisor: [
    '/operations',
    '/daily-loss',
    '/operations/inspection',
    '/operations/inspection/tasks',
    '/operations/inspection/records',
    '/operations/inspection/reviews',
    '/operations/inspection/standards',
    '/operations/exams',
    '/platform-login',
  ],
  learner: [
    '/employee',
    '/employee/profile',
    '/employee/exams',
    '/employee-assistant',
    '/learn/exams',
  ],
}

test.describe('responsive smoke checks', () => {
  for (const width of widths) {
    for (const item of corePages) {
      test(`${item.name} works at ${width}px`, async ({ page }) => {
        await page.setViewportSize({ width, height: width === 768 ? 1024 : 812 })
        if (item.role) {
          await loginAs(page, item.role)
        }
        await page.goto(item.path)
        await page.waitForLoadState('networkidle')

        await expect(page.locator('body')).not.toBeEmpty()
        await expectNoWholePageOverflow(page, `${item.name} ${width}px`)
      })
    }
  }

  for (const [role, paths] of Object.entries(mobileRoutesByRole) as Array<[RoleKey, string[]]>) {
    test(`${role} 的全部业务路由在 390px 下不产生整页横向溢出`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 })
      await loginAs(page, role)

      for (const path of paths) {
        await page.goto(path)
        await page.waitForLoadState('networkidle')
        await expect(page.locator('body'), `${path} should render`).not.toBeEmpty()
        await expect.poll(
          () => new URL(page.url()).pathname,
          { message: `${role} should be allowed to stay on ${path}` },
        ).toBe(path)
        await expectNoWholePageOverflow(page, `${role} ${path} 390px`)
      }
    })
  }

  test('mobile menu can open and close', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 812 })
    await loginAs(page, 'store')
    await page.goto('/warehouse')
    await page.waitForLoadState('networkidle')

    await page.getByRole('button', { name: '打开菜单' }).click()
    await expect(page.locator('.mobile-nav-drawer')).toHaveClass(/open/)
    await expect(page.locator('.mobile-nav-drawer .sidebar-navigation')).toContainText('本店库存')
    await expect(page.locator('.mobile-nav-drawer .sidebar-navigation')).not.toContainText('今日待办')

    await page.getByRole('button', { name: '关闭菜单' }).click()
    await expect(page.locator('.mobile-nav-drawer')).not.toHaveClass(/open/)
  })
})
