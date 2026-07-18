import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const baseUrl = process.env.FRONTEND_URL || 'http://127.0.0.1:5173'
const username = process.env.DAILY_LOSS_E2E_USERNAME || 'rg1'
const password = process.env.DAILY_LOSS_E2E_PASSWORD || '123'
const artifactDir = process.env.DAILY_LOSS_E2E_ARTIFACT_DIR
  || resolve(process.cwd(), '../output/playwright')
const tokenKey = 'ai_profit_vue_token'
const userKey = 'ai_profit_vue_user'
const uploadPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=',
  'base64',
)
const screenshots = {
  desktop: 'daily-loss-runtime-desktop.png',
  categoryFields: 'daily-loss-runtime-category-fields.png',
  list: 'daily-loss-list-below-form.png',
  mobile: 'daily-loss-runtime-mobile.png',
}
const requiredDailyLossItemFields = [
  'id',
  'itemCode',
  'itemName',
  'category',
  'categoryCode',
  'categoryName',
  'unit',
  'active',
]
const expectedWarehouseCategoryNames = ['水果', '奶制品', '茶叶', '小料/配料']
const legacySheetCategoryNames = ['每日报损表', '水果检查表']

function unwrap(payload) {
  if (!payload || payload.success === false) {
    throw new Error(payload?.message || '接口返回失败')
  }
  return payload.data
}

async function login(page) {
  const loginResponse = await page.request.post(`${baseUrl}/api/auth/login`, {
    data: { username, password },
  })
  if (!loginResponse.ok()) {
    throw new Error(`登录失败 HTTP ${loginResponse.status()}`)
  }
  const session = unwrap(await loginResponse.json())
  const meResponse = await page.request.get(`${baseUrl}/api/auth/me`, {
    headers: { Authorization: `Bearer ${session.token}` },
  })
  if (!meResponse.ok()) {
    throw new Error(`读取当前账号失败 HTTP ${meResponse.status()}`)
  }
  const user = unwrap(await meResponse.json())
  await page.goto(`${baseUrl}/login`, { waitUntil: 'domcontentloaded' })
  await page.evaluate(({ tokenKey, userKey, token, user }) => {
    window.localStorage.setItem(tokenKey, token)
    window.localStorage.setItem(userKey, JSON.stringify(user))
  }, { tokenKey, userKey, token: session.token, user })
  return { token: session.token, user }
}

async function gotoDailyLoss(page) {
  const responses = []
  page.on('response', async (response) => {
    const url = response.url()
    if (!url.includes('/api/daily-loss/')) return
    responses.push({ url, status: response.status() })
  })
  await page.goto(`${baseUrl}/daily-loss`, { waitUntil: 'domcontentloaded' })
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.locator('.daily-loss-page').waitFor({ state: 'visible', timeout: 20_000 })
  await page.locator('.loss-toolbar').waitFor({ state: 'visible', timeout: 20_000 })
  return responses
}

async function assertDailyLossItemFields(page, token) {
  const response = await page.request.get(`${baseUrl}/api/daily-loss/items`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!response.ok()) {
    throw new Error(`/api/daily-loss/items HTTP ${response.status()}`)
  }
  const items = unwrap(await response.json())
  if (!Array.isArray(items) || items.length === 0) {
    throw new Error('/api/daily-loss/items returned no items')
  }
  const missing = items.flatMap((item, index) => {
    const fields = new Set(Object.keys(item || {}))
    return requiredDailyLossItemFields
      .filter((field) => !fields.has(field))
      .map((field) => `${index}:${field}`)
  })
  if (missing.length > 0) {
    throw new Error(`/api/daily-loss/items missing fields: ${missing.join(', ')}`)
  }
  const categoryNames = [...new Set(items
    .map((item) => String(item.categoryName || item.category || '').trim())
    .filter(Boolean))]
  if (!categoryNames.some((name) => expectedWarehouseCategoryNames.includes(name))) {
    throw new Error(`/api/daily-loss/items did not expose warehouse category names: ${categoryNames.join(', ')}`)
  }
  if (categoryNames.length > 0 && categoryNames.every((name) => legacySheetCategoryNames.includes(name))) {
    throw new Error('/api/daily-loss/items is still grouped only by legacy Excel sheet categories')
  }
  return items
}

function firstItemName(items) {
  return String(items?.[0]?.itemName || items?.[0]?.name || '').trim()
}

async function verifyDesktop(browser) {
  const context = await browser.newContext({ viewport: { width: 1365, height: 900 }, acceptDownloads: true })
  const page = await context.newPage()
  try {
    const { token } = await login(page)
    const items = await assertDailyLossItemFields(page, token)
    await gotoDailyLoss(page)

    await page.screenshot({
      path: resolve(artifactDir, screenshots.desktop),
      fullPage: true,
    })

    await page.locator('.item-picker-trigger').first().click()
    await page.locator('.item-picker-dialog').waitFor({ state: 'visible', timeout: 10_000 })
    const categoryCount = await page.locator('.category-tabs button').count()
    if (categoryCount < 2) {
      throw new Error('category tabs were not rendered')
    }
    const categoryNames = [...new Set(items
      .map((item) => String(item.categoryName || item.category || '').trim())
      .filter(Boolean))]
    const tabTexts = await page.locator('.category-tabs button').allTextContents()
    if (categoryNames.length > 0
      && !categoryNames.some((name) => tabTexts.some((text) => text.includes(name)))) {
      throw new Error('category tabs did not render backend categoryName values')
    }
    if (categoryCount > 2) {
      await page.locator('.category-tabs button').nth(2).click()
      await page.locator('.category-tabs button').first().click()
    }
    const keyword = firstItemName(items).slice(0, 2)
    if (keyword) await page.locator('.picker-search input').fill(keyword)
    await page.locator('.picker-card').first().waitFor({ state: 'visible', timeout: 10_000 })
    await page.screenshot({
      path: resolve(artifactDir, screenshots.categoryFields),
      fullPage: true,
    })
    await page.locator('.picker-card').first().click()

    await page.locator('.quantity-control input').first().fill('12.50')
    await page.locator('.quick-reasons button').first().click()

    const uploadPath = resolve(artifactDir, 'daily-loss-layout-upload-sample.png')
    await writeFile(uploadPath, uploadPng)
    await page.locator('.attachment-field input[type="file"]').setInputFiles(uploadPath)
    await page.locator('.selected-preview-grid img').first().waitFor({ state: 'visible', timeout: 10_000 })
    await page.locator('.selected-preview-grid figure button').first().click()
    await page.locator('.image-preview-dialog img').waitFor({ state: 'visible', timeout: 10_000 })
    await page.keyboard.press('Escape')

    const records = page.locator('.records-card')
    await records.scrollIntoViewIfNeeded()
    await page.locator('.record-row').first().waitFor({ state: 'visible', timeout: 10_000 })
    await page.locator('.record-row').first().click()
    await page.waitForTimeout(500)
    if (await page.locator('.detail-dialog').isVisible().catch(() => false)) {
      await page.keyboard.press('Escape')
    }
    await records.scrollIntoViewIfNeeded()
    await page.screenshot({
      path: resolve(artifactDir, screenshots.list),
      fullPage: true,
    })
  } finally {
    await context.close()
  }
}

async function verifyMobile(browser) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, acceptDownloads: true })
  const page = await context.newPage()
  try {
    await login(page)
    await gotoDailyLoss(page)
    await page.locator('.item-picker-trigger').first().click()
    await page.locator('.item-picker-dialog').waitFor({ state: 'visible', timeout: 10_000 })
    await page.keyboard.press('Escape')
    const overflow = await page.evaluate(() => {
      const roots = [
        document.documentElement,
        document.body,
        document.querySelector('.app-main'),
        document.querySelector('.daily-loss-page'),
      ].filter(Boolean)
      return roots.some((node) => node.scrollWidth > window.innerWidth + 2)
    })
    if (overflow) throw new Error('移动端存在横向溢出')
    await page.screenshot({
      path: resolve(artifactDir, screenshots.mobile),
      fullPage: true,
    })
  } finally {
    await context.close()
  }
}

await mkdir(artifactDir, { recursive: true })

const browser = await chromium.launch()
try {
  await verifyDesktop(browser)
  await verifyMobile(browser)
  console.log(`daily-loss layout verification passed: ${artifactDir}`)
} finally {
  await browser.close()
}
