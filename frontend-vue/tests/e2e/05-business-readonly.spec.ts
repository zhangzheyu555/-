import { expect, test } from '@playwright/test'
import { forbiddenVisibleTerms, loginAs, type RoleKey } from './auth.setup'

const pages: Array<{ role: RoleKey; path: string }> = [
  { role: 'boss', path: '/boss' },
  { role: 'boss', path: '/profit' },
  { role: 'finance', path: '/profit-table' },
  { role: 'finance', path: '/profit' },
  { role: 'warehouse', path: '/warehouse' },
  { role: 'operations', path: '/operations/inspection' },
  { role: 'operations', path: '/operations/exams' },
  { role: 'store', path: '/warehouse' },
]

test.describe('business pages expose Chinese business language', () => {
  for (const item of pages) {
    test(`${item.role} ${item.path} does not show technical labels`, async ({ page }) => {
      await loginAs(page, item.role)
      await page.goto(item.path)
      await page.waitForLoadState('networkidle')

      const body = await page.locator('body').innerText()
      for (const term of forbiddenVisibleTerms) {
        expect(body, `${item.role} ${item.path} should not show ${term}`).not.toContain(term)
      }
    })
  }
})
