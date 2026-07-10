import { expect, test } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { loginAs, roles } from './auth.setup'

function walkFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) return walkFiles(full)
    return entry.isFile() ? [full] : []
  })
}

test.describe('local browser storage policy', () => {
  test('source code only writes Vue login state to localStorage', () => {
    const srcDir = path.join(process.cwd(), 'src')
    const offenders: string[] = []

    for (const file of walkFiles(srcDir)) {
      if (!/\.(ts|vue)$/.test(file)) continue
      const text = fs.readFileSync(file, 'utf8')
      const relative = path.relative(process.cwd(), file).replace(/\\/g, '/')

      if (/sessionStorage\.setItem|indexedDB/i.test(text)) {
        offenders.push(relative)
      }
      if (/localStorage\.setItem/i.test(text) && relative !== 'src/stores/auth.ts') {
        offenders.push(relative)
      }
    }

    expect(offenders).toEqual([])
  })

  for (const role of roles) {
    test(`${role.key} runtime storage only keeps auth keys`, async ({ page }) => {
      await loginAs(page, role.key)
      await page.goto(role.expectedPath)
      await page.waitForLoadState('networkidle')

      const keys = await page.evaluate(() => Object.keys(localStorage).sort())
      expect(keys).toEqual(['ai_profit_vue_token', 'ai_profit_vue_user'])
    })
  }
})
