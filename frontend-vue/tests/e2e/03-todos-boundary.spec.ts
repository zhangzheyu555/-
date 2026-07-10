import { expect, test } from '@playwright/test'
import { loginAs, roles } from './auth.setup'

test.describe('today todo page boundary', () => {
  for (const role of roles) {
    test(`${role.key} opening /todos is redirected to its workbench`, async ({ page }) => {
      await loginAs(page, role.key)
      await page.goto('/todos')
      await page.waitForLoadState('networkidle')

      await expect.poll(() => new URL(page.url()).pathname).toBe(role.expectedPath)
      await expect(page.locator('body')).not.toBeEmpty()
    })
  }
})
