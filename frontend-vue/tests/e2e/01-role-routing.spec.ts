import { expect, test } from '@playwright/test'
import { expectDefaultRoute, loginApi, loginAs, roleByKey, roles } from './auth.setup'

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
  }

  test('ops and operations usernames are both accepted for operations role', async () => {
    const ops = await loginApi(roleByKey.operations.username, roleByKey.operations.password)
    expect(ops.user.role).toBe('OPERATIONS')

    const operations = await loginApi('operations', roleByKey.operations.password)
    expect(operations.user.role).toBe('OPERATIONS')
  })
})
