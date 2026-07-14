import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, test, type Page, type Route } from '@playwright/test'

const bossUser = {
  id: 9001,
  tenantId: 1,
  tenantName: 'TEST 租户',
  displayName: 'TEST BOSS',
  role: 'BOSS',
  roleLabel: '老板',
  storeScope: ['all'],
  permissions: [],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [] }, INSPECTION: { mode: 'ALL', storeIds: [] } },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

function standardItem(
  id: number,
  dimension: string,
  code: string,
  suggestedScore: number,
  riskLevel: 'RED' | 'YELLOW' | 'NORMAL' = 'NORMAL',
) {
  return {
    id,
    dimension,
    code,
    title: code === 'H-4.1.2' ? '地面清洁无纸屑' : `${code} 检查条款`,
    description: `${code} 判定说明`,
    checkMethod: '现场检查并保留证据',
    suggestedScore,
    redLine: riskLevel === 'RED',
    riskLevel,
    enabled: true,
    sortOrder: id,
  }
}

function validStandardItems() {
  const material = Array.from({ length: 40 }, (_, index) => {
    if (index === 0) return standardItem(1, '物料标准', 'M-RED-01', 0, 'RED')
    if (index === 1) return standardItem(2, '物料标准', 'M-01', 37)
    return standardItem(index + 1, '物料标准', `M-${String(index).padStart(2, '0')}`, 0, index <= 21 ? 'RED' : 'NORMAL')
  })
  const hygiene = Array.from({ length: 47 }, (_, index) => {
    const score = index === 0 ? 2 : index === 1 ? 61 : 0
    return standardItem(41 + index, '卫生标准', index === 0 ? 'H-4.1.2' : `H-${String(index + 1).padStart(2, '0')}`, score, index <= 8 ? 'YELLOW' : 'NORMAL')
  })
  const service = Array.from({ length: 18 }, (_, index) => standardItem(88 + index, '服务标准', `S-${String(index + 1).padStart(2, '0')}`, index === 0 ? 100 : 0))
  return [...material, ...hygiene, ...service]
}

const standard = {
  id: 2025110601,
  title: '茹菓门店品质稽核标准 2025.11.06-R1',
  version: '2025.11.06-R1',
  effectiveDate: '2025-11-06',
  fullScore: 200,
  passScore: 180,
  valid: true,
  saveAllowed: true,
  items: validStandardItems(),
}

const detectedSuggestion = {
  image_id: 'IMG-FOCUS-1',
  imageId: 'IMG-FOCUS-1',
  filename: '巡检现场.jpg',
  passed: false,
  review_status: 'PENDING',
  auto_status: '发现地面纸屑',
  detection_count: 1,
  detections: [{ class_id: 1, class_name: 'paper_scrap', confidence: 0.96, source: 'model', on_floor: true }],
  detection_summary: '发现1处地面纸屑',
  deduction_project: '卫生标准',
  deduction_content: '地面存在纸屑，需要及时清理',
  detectionKey: 'DET-FOCUS-1',
  clauseId: 41,
  clauseCode: 'H-4.1.2',
  clauseTitle: '地面清洁无纸屑',
  issueCode: 'paper_scrap',
  issueName: '地面纸屑',
  scoreScale: 100,
  persistedScoreScale: 200,
  legacyDeduction: 2,
  convertedDeduction200: 4,
  standardDeduction: 4,
  clauseDeduction: 2,
  scaleAdjustmentDeduction: 2,
  deductionPolicyVersion: 'LEGACY_100_TO_200_H412_V1',
  finalDeduction: 4,
  confidence: 0.96,
  decisionStatus: 'PENDING',
  revision: 0,
}

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify({ success: status < 400, data }),
  })
}

async function seedSession(page: Page) {
  await page.goto('/login')
  await page.evaluate((user) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-INSPECTION-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossUser)
}

function nestedKeys(value: unknown): string[] {
  if (Array.isArray(value)) return value.flatMap(nestedKeys)
  if (!value || typeof value !== 'object') return []
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) => [key, ...nestedKeys(child)])
}

test('model suggestion is server-confirmed as 2 -> 4 and previewed as hygiene 59 / total 196 without trusting browser final score', async ({ page }) => {
  let confirmationBody: Record<string, unknown> | undefined

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (!pathname.startsWith('/api/')) return route.continue()
    if (pathname === '/api/auth/me') return json(route, bossUser)
    if (pathname === '/api/inspection/standards') return json(route, standard)
    if (pathname === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', code: 'STORE-1', name: '测试门店', brandId: 1, brandName: '茹菓' }])
    if (pathname === '/api/inspections' && request.method() === 'GET') return json(route, [])
    if (pathname === '/api/storage/upload') return json(route, { id: 501, fileName: '巡检现场.jpg', contentType: 'image/jpeg', fileSize: 32, url: '/api/storage/501/content' })
    if (pathname === '/api/inspections/detect') return json(route, detectedSuggestion)
    if (pathname === '/api/inspections/detection-suggestions/DET-FOCUS-1/confirm') {
      confirmationBody = request.postDataJSON() as Record<string, unknown>
      return json(route, {
        ...detectedSuggestion,
        confirmedDeduction: 4,
        finalDeduction: 4,
        decisionStatus: 'CONFIRMED',
        review_status: 'CONFIRMED',
        revision: 1,
      })
    }
    if (pathname.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })

  await seedSession(page)
  await page.goto('/operations/inspection/tasks')
  await page.locator('input[type="file"][accept="image/*"]').setInputFiles({
    name: '巡检现场.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from('inspection-focus-image'),
  })

  const rule = page.locator('.inspection-detection-rule')
  await expect(rule).toContainText('H-4.1.2 · 地面清洁无纸屑 · 条款ID 41')
  await expect(rule.locator('.inspection-deduction-metrics > div').nth(0)).toContainText('旧100分制建议2 分')
  await expect(rule.locator('.inspection-deduction-metrics > div').nth(1)).toContainText('200分制换算4 分')
  await expect(rule.locator('.inspection-deduction-metrics > div').nth(2)).toContainText('条款 + 换算调整2 + 2 分')
  await expect(rule.locator('.inspection-deduction-metrics > div').nth(3)).toContainText('最终扣分4 分')
  await expect(rule).toContainText('识别置信度96%')
  await expect(rule).toContainText('模型仅建议；最终扣分由服务端按正式条款规则计算，需督导确认。')

  await page.getByRole('button', { name: '确认问题并加入扣分' }).click()
  await expect.poll(() => confirmationBody).toBeTruthy()

  expect(Object.keys(confirmationBody || {})).toEqual(['evidence'])
  const forbiddenKeys = new Set([
    'finalDeduction', 'confirmedDeduction', 'standardDeduction', 'suggestedDeduction',
    'clauseDeduction', 'scaleAdjustmentDeduction', 'deductionPolicyVersion',
    'clauseId', 'clauseCode', 'clauseTitle', 'deduction_score',
  ])
  expect(nestedKeys(confirmationBody).filter((key) => forbiddenKeys.has(key))).toEqual([])

  const hygieneScore = page.locator('.category-score').filter({ hasText: '卫生得分' })
  await expect(hygieneScore).toContainText('59 / 63')
  await expect(page.locator('.inspection-score-bar').getByText('196 / 200', { exact: true })).toBeVisible()
  await expect(page.locator('.inspection-score-bar').getByText('-4', { exact: true })).toBeVisible()
  await expect(page.locator('.inspection-score-bar').getByText('合格', { exact: true })).toBeVisible()
  await expect(page.getByText('督导已确认，保存后按条款扣分')).toBeVisible()

  const screenshotPath = path.resolve(process.cwd(), '..', 'output', 'playwright', 'inspection-detection-confirm-196.png')
  mkdirSync(path.dirname(screenshotPath), { recursive: true })
  await page.screenshot({ path: screenshotPath, fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.inspection-score-bar').getByText('196 / 200', { exact: true })).toBeVisible()
  await expect(page.locator('.inspection-score-bar').getByText('合格', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)
  await page.screenshot({
    path: path.resolve(process.cwd(), '..', 'output', 'playwright', 'inspection-detection-confirm-196-mobile.png'),
    fullPage: true,
  })
})
