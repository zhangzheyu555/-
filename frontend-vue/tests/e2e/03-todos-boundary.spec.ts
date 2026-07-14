import { expect, test } from '@playwright/test'
import { loginAs } from './auth.setup'

test.describe('legacy workbench redirects', () => {
  for (const item of [
    { path: '/todos', expected: '/boss' },
    { path: '/finance-data-check', expected: '/finance' },
    { path: '/operations/analysis', expected: '/operations' },
  ]) {
    test(`${item.path} redirects to its current workspace`, async ({ page }) => {
      await loginAs(page, 'boss')
      await page.goto(item.path)
      await expect.poll(() => new URL(page.url()).pathname).toBe(item.expected)
      await expect(page.locator('body')).not.toBeEmpty()
    })
  }
})
