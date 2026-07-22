import { mkdirSync } from 'node:fs'
import path from 'node:path'
import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

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

async function useDesktopViewport(page: Page) {
  await page.setViewportSize({ width: 1280, height: 720 })
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
  annotated_image: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL13wAAAABJRU5ErkJggg==',
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

test('model suggestion shows a returned annotation and only the 200-point deduction', async ({ page }) => {
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
  const photoInput = page.locator('.inspection-upload-box input[type="file"]')
  await expect(photoInput).toHaveAttribute('accept', 'image/*')
  await expect(photoInput).toHaveAttribute('capture', 'environment')
  const uploadButtonBox = await page.locator('.inspection-upload-box .upload-button').boundingBox()
  expect(uploadButtonBox?.height, '拍照/选图按钮的点击高度').toBeGreaterThanOrEqual(44)
  await photoInput.setInputFiles({
    name: '巡检现场.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from('inspection-focus-image'),
  })

  const rule = page.locator('.inspection-detection-rule')
  await expect(rule).toContainText('H-4.1.2 · 地面清洁无纸屑 · 条款ID 41')
  await expect(page.locator('.inspection-detection-result .inspection-detection-preview img')).toHaveAttribute('src', /^data:image\/png;base64,/)
  await expect(rule.locator('.inspection-deduction-metrics > div').nth(0)).toContainText('200分制建议扣分4 分')
  await expect(rule.locator('.inspection-deduction-metrics')).not.toContainText('旧100分制')
  await expect(rule.locator('.inspection-deduction-metrics')).not.toContainText('200分制换算')
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
  await expect(hygieneScore).toContainText('59 分（满分63）')
  const scoreSummary = page.locator('.inspection-score-summary')
  await expect(scoreSummary.locator('div').filter({ hasText: '总分' })).toContainText('196 / 200')
  await expect(scoreSummary.locator('div').filter({ hasText: '扣分合计' })).toContainText('-4')
  await expect(scoreSummary.locator('div').filter({ hasText: '结果' })).toContainText('合格')
  await expect(page.getByText('督导已确认，保存后按条款扣分')).toBeVisible()

  const screenshotPath = path.resolve(process.cwd(), '..', 'output', 'playwright', 'inspection-detection-confirm-196.png')
  mkdirSync(path.dirname(screenshotPath), { recursive: true })
  await page.screenshot({ path: screenshotPath, fullPage: true })

  await useDesktopViewport(page)
  await expect(scoreSummary.locator('div').filter({ hasText: '总分' })).toContainText('196 / 200')
  await expect(scoreSummary.locator('div').filter({ hasText: '结果' })).toContainText('合格')
  await expectNoWholePageOverflow(page, '1280px desktop inspection confirmation')
  await page.screenshot({
    path: path.resolve(process.cwd(), '..', 'output', 'playwright', 'inspection-detection-confirm-196-desktop.png'),
    fullPage: true,
  })
})

test('record detail keeps effective deductions separate from unmatched AI evidence and loads protected image blobs', async ({ page }) => {
  const recordId = 'INS-EVIDENCE-196'
  const effectiveImageName = '微信图片_20260713101607_240_14.jpg'
  const unmatchedImageName = '微信图片_AI待确认.jpg'
  const attachmentAuthorization: Record<string, string | undefined> = {}
  const listRecord = {
    id: recordId,
    storeId: 'STORE-1',
    storeName: '花台店',
    brand: '茹菓',
    inspectionDate: '2026-07-13',
    inspector: '测试督导',
    fullScore: 200,
    score: 196,
    maxScore: 200,
    passScore: 180,
    passed: true,
    resultCode: 'PASSED',
    photosJson: '[]',
    itemResults: [],
  }
  const detailRecord = {
    ...listRecord,
    photosJson: JSON.stringify([
      {
        attachmentId: 501,
        fileName: effectiveImageName,
        contentType: 'image/png',
        detection: {
          attachmentId: 501,
          imageId: 'IMG-EFFECTIVE-501',
          detectionKey: 'DET-EFFECTIVE-501',
          decisionStatus: 'CONFIRMED',
          detection_count: 1,
        },
      },
      {
        attachmentId: 502,
        fileName: unmatchedImageName,
        contentType: 'image/png',
        detection: {
          attachmentId: 502,
          imageId: 'IMG-UNMATCHED-502',
          detectionKey: 'DET-UNMATCHED-502',
          decisionStatus: 'UNMATCHED',
          detection_count: 1,
          confidence: 0.94,
        },
      },
    ]),
    itemResults: [{
      standardItemId: 41,
      code: 'H-4.1',
      dimension: '卫生标准',
      categoryCode: 'HYGIENE',
      categoryName: '卫生',
      title: '店铺内部卫生',
      standardScore: 4,
      actualScore: 0,
      deductionScore: 4,
      deductionReason: '地面有纸屑/垃圾/污点，角落有积灰，需要及时清理干净',
      riskLevel: 'NORMAL',
      issueFound: true,
      photoAttachmentIds: [501],
    }],
  }

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (!pathname.startsWith('/api/')) return route.continue()
    if (pathname === '/api/auth/me') return json(route, bossUser)
    if (pathname === '/api/inspection/standards') return json(route, standard)
    if (pathname === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', code: 'STORE-1', name: '花台店', brandId: 1, brandName: '茹菓' }])
    if (pathname === '/api/inspections' && request.method() === 'GET') return json(route, [listRecord])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return json(route, detailRecord)
    if (pathname === '/api/storage/attachments/501') {
      attachmentAuthorization['501'] = request.headers().authorization
      return route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360f8cfc0000003010100c9fea9f70000000049454e44ae426082', 'hex'),
      })
    }
    if (pathname === '/api/storage/attachments/502') {
      attachmentAuthorization['502'] = request.headers().authorization
      return route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, code: 'FORBIDDEN', message: '无查看权限' }),
      })
    }
    if (pathname.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })

  await seedSession(page)
  await page.goto('/operations/inspection/records')
  await page.getByRole('row', { name: /花台店/ }).click()

  await expect(page.getByText('历史巡检条款快照（1条）')).toBeVisible()
  const snapshot = page.locator('.snapshot-table')
  await expect(snapshot.getByRole('columnheader', { name: '实得分' })).toBeVisible()
  await expect(snapshot.getByRole('columnheader', { name: '实际扣分' })).toBeVisible()
  const hygieneRow = snapshot.getByRole('row', { name: /店铺内部卫生/ })
  await expect(hygieneRow).toContainText('4')
  await expect(hygieneRow).toContainText('实得 0 / 4')
  await expect(hygieneRow).toContainText('扣 4 分')
  await expect(hygieneRow).toContainText('历史已生效（AI已确认）')
  await expect(page.locator('.inspection-detail-grid').getByText('196 / 200', { exact: true })).toBeVisible()

  const effectiveThumb = hygieneRow.getByRole('button', { name: `预览 ${effectiveImageName}` })
  await expect(effectiveThumb.locator('img')).toBeVisible()
  await expect(hygieneRow).toContainText(effectiveImageName)
  await expect.poll(() => attachmentAuthorization['501']).toBe('Bearer TEST-INSPECTION-TOKEN')
  await effectiveThumb.click()
  await expect(page.getByRole('dialog', { name: '现场图片预览' })).toBeVisible()
  await expect(page.getByRole('dialog').getByRole('img', { name: effectiveImageName })).toBeVisible()

  const pendingCard = page.locator('.ai-pending-card')
  await expect(pendingCard).toContainText(unmatchedImageName)
  await expect(pendingCard).toContainText('未匹配正式条款，未计入本次得分')
  await expect(page.getByText('AI 待确认识别结果（不计分）')).toBeVisible()
  await expect(snapshot.getByText('扣 0 分')).toHaveCount(0)
  // An unlinked AI suggestion is intentionally not fetched: the original image is only read
  // after its attachment ID is explicitly persisted against a historical clause.  The linked
  // evidence above still proves the protected attachment request carries Authorization.
  expect(attachmentAuthorization['502']).toBeUndefined()
  await expect(pendingCard).toContainText('待人工关联历史条款，不能预览原图')

  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(page.getByText('历史巡检条款快照（1条）')).toBeVisible()
  await expect(hygieneRow).toContainText('实得 0 / 4')
  await expect(pendingCard).toContainText('未匹配正式条款，未计入本次得分')
  await expect(pendingCard).toContainText('待人工关联历史条款，不能预览原图')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)
})
