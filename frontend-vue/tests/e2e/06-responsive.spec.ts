import { expect, test } from '@playwright/test'
import { expectNoWholePageOverflow, loginAs, type RoleKey } from './auth.setup'

const widths = [375, 390, 430, 768]

const pages: Array<{ role: RoleKey | null; path: string; name: string }> = [
  { role: null, path: '/login', name: 'login' },
  { role: 'store', path: '/warehouse', name: 'store-warehouse' },
  { role: 'boss', path: '/boss', name: 'boss' },
  { role: 'boss', path: '/profit', name: 'profit' },
  { role: 'boss', path: '/store-detail', name: 'store-detail' },
  { role: 'boss', path: '/users', name: 'users' },
  { role: 'boss', path: '/export', name: 'export' },
  { role: 'finance', path: '/expenses', name: 'finance-expenses' },
  { role: 'warehouse', path: '/warehouse', name: 'warehouse' },
  { role: 'warehouse', path: '/warehouse/items', name: 'warehouse-items' },
  { role: 'operations', path: '/operations/inspection', name: 'operations-inspection' },
  { role: 'operations', path: '/operations/inspection/records', name: 'inspection-records' },
  { role: 'operations', path: '/operations/exams', name: 'operations-exam' },
]

test.describe('responsive smoke checks', () => {
  for (const width of widths) {
    for (const item of pages) {
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
