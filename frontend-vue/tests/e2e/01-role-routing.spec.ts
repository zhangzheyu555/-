import { expect, test } from '@playwright/test'
import { API_BASE_URL, expectDefaultRoute, loginApi, loginAs, roleByKey, roles } from './auth.setup'
import { normalizeRoleCode } from '../../src/permissions/roles'

test.describe('role login and default routing', () => {
  for (const role of roles) {
    test(`${role.key} enters the correct default page`, async ({ page }) => {
      await page.setViewportSize({ width: 1365, height: 900 })
      await loginAs(page, role.key)
      await page.goto('/')
      await expectDefaultRoute(page, role.expectedPath)

      await page.reload()
      await expectDefaultRoute(page, role.expectedPath)

      const keys = await page.evaluate(() => Object.keys(localStorage).sort())
      expect(keys).toEqual(['ai_profit_vue_token', 'ai_profit_vue_user'])
    })

    test(`${role.key} can return from no-permission to an accessible workspace`, async ({ page }) => {
      await page.setViewportSize({ width: 1365, height: 900 })
      const session = await loginAs(page, role.key)
      await page.goto('/no-permission')
      await expect(page.getByRole('button', { name: '返回可用工作台' })).toBeVisible()

      await page.getByRole('button', { name: '返回可用工作台' }).click()
      await expectDefaultRoute(page, role.expectedPath)

      if (role.key === 'boss') {
        await page.screenshot({
          path: '../output/playwright/no-permission-button-fixed.png',
          fullPage: true,
        })
      }

      await page.request.post(`${API_BASE_URL}/api/auth/logout`, {
        headers: { Authorization: `Bearer ${session.token}` },
      })
    })
  }

  test('supervisor is the formal role and legacy role codes normalize to supervisor', async () => {
    const supervisor = await loginApi(roleByKey.supervisor.username, roleByKey.supervisor.password)
    expect(supervisor.user.role).toBe('SUPERVISOR')
    expect(normalizeRoleCode('OPS')).toBe('SUPERVISOR')
    expect(normalizeRoleCode('OPERATIONS')).toBe('SUPERVISOR')
  })

  test('supervisor and employee keep independent role identities', () => {
    expect(normalizeRoleCode('SUPERVISOR')).toBe('SUPERVISOR')
    expect(normalizeRoleCode('EMPLOYEE')).toBe('EMPLOYEE')
  })

  test('legacy workbench links redirect each role to an accessible page', async ({ page }) => {
    for (const role of roles) {
      await loginAs(page, role.key)
      await page.goto('/todos')
      await expectDefaultRoute(page, role.expectedPath)
    }

    await loginAs(page, 'finance')
    await page.goto('/finance?tab=expenses')
    await expect(page).toHaveURL(/\/expenses$/)
  })
})
