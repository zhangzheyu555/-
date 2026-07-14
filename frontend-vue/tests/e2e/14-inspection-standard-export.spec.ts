import { expect, test, type Page, type Route } from '@playwright/test'

type MockStandardItem = {
  id: number
  dimension: string
  code: string
  title: string
  description: string
  checkMethod: string
  suggestedScore: number
  redLine: boolean
  riskLevel: 'RED' | 'YELLOW' | 'NORMAL'
  enabled: boolean
  sortOrder: number
}

function mockItem(
  id: number,
  dimension: string,
  code: string,
  suggestedScore: number,
  riskLevel: MockStandardItem['riskLevel'] = 'NORMAL',
): MockStandardItem {
  return {
    id,
    dimension,
    code,
    title: `${code} 检查条款`,
    description: `${code} 判定说明`,
    checkMethod: '现场检查并保留证据',
    suggestedScore,
    redLine: riskLevel === 'RED',
    riskLevel,
    enabled: true,
    sortOrder: id,
  }
}

function createValidStandardItems() {
  const material = Array.from({ length: 40 }, (_, index) => {
    const id = index + 1
    if (index === 0) return mockItem(id, '物料标准', 'M-RED-01', 0, 'RED')
    if (index === 1) return mockItem(id, '物料标准', 'M-01', 37)
    return mockItem(id, '物料标准', `M-${String(index).padStart(2, '0')}`, 0, index <= 21 ? 'RED' : 'NORMAL')
  })
  const hygiene = Array.from({ length: 47 }, (_, index) => {
    const id = 41 + index
    return mockItem(id, '卫生标准', index === 0 ? 'H-01' : `H-${String(index + 1).padStart(2, '0')}`, index === 0 ? 63 : 0, index <= 8 ? 'YELLOW' : 'NORMAL')
  })
  const service = Array.from({ length: 18 }, (_, index) => {
    const id = 88 + index
    const item = mockItem(id, '服务标准', index === 0 ? 'S-01' : `S-${String(index + 1).padStart(2, '0')}`, index === 0 ? 100 : 0)
    // A service title may contain “产品”; dimension must still win over title keywords.
    if (index === 0) item.title = '产品核对'
    return item
  })
  return [...material, ...hygiene, ...service]
}

function createInvalidStandardItems() {
  const material = Array.from({ length: 43 }, (_, index) => mockItem(index + 1, '物料标准', `M-${index + 1}`, index === 0 ? 49 : 0, index < 21 ? 'RED' : 'NORMAL'))
  const hygiene = Array.from({ length: 47 }, (_, index) => mockItem(index + 44, '卫生标准', `H-${index + 1}`, index === 0 ? 66 : 0, index < 9 ? 'YELLOW' : 'NORMAL'))
  const service = Array.from({ length: 15 }, (_, index) => mockItem(index + 91, '服务标准', `S-${index + 1}`, index === 0 ? 85 : 0))
  return [...material, ...hygiene, ...service]
}

const standard = {
  id: 20251106,
  title: '茹菓门店品质稽核标准 2025.11.06',
  version: '2025.11.06',
  effectiveDate: '2025-11-06',
  fullScore: 200,
  passScore: 180,
  valid: true,
  saveAllowed: true,
  items: createValidStandardItems(),
}

const invalidStandard = {
  ...standard,
  id: 20251107,
  version: '2025.11.06-错误导入',
  valid: false,
  saveAllowed: false,
  validationError: '分类数量和分值分配不符合权威标准',
  items: createInvalidStandardItems(),
}

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

async function seedSession(page: Page, user = bossUser) {
  await page.goto('/login')
  await page.evaluate(({ sessionUser }) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-INSPECTION-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(sessionUser))
  }, { sessionUser: user })
}

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ success: status < 400, data }) })
}

async function mockInspectionApi(
  page: Page,
  records: unknown[] = [],
  capture?: (payload: Record<string, unknown>) => void,
  standardResponse: unknown = standard,
  saveFailure?: () => { code: string; message: string } | undefined,
  detailRecords: Record<string, unknown> = {},
  captureDetailRequest?: (recordId: string) => void,
) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    if (path === '/api/auth/me') return json(route, bossUser)
    if (path === '/api/inspection/standards') return json(route, standardResponse)
    if (path === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (path === '/api/inspections/detect') return json(route, { image_id: 'IMG-1', passed: true, detection_count: 0, detections: [], auto_status: '未识别到问题', annotated_image: 'data:image/png;base64,TRANSIENT_ONLY' })
    if (path === '/api/storage/upload') return json(route, { id: 501, fileName: '现场照片.png', contentType: 'image/png', fileSize: 16, url: '/api/storage/501/content', storagePath: 'C:/sensitive/internal/path.png' })
    if (path === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (path === '/api/stores') return json(route, [{ id: 'STORE-1', code: 'STORE-1', name: '测试门店', brandId: 1, brandName: '茹菓' }])
    if (path === '/api/inspections' && request.method() === 'GET') return json(route, records)
    const detailMatch = path.match(/^\/api\/inspections\/([^/]+)$/)
    if (detailMatch && request.method() === 'GET') {
      const recordId = decodeURIComponent(detailMatch[1])
      captureDetailRequest?.(recordId)
      const record = detailRecords[recordId]
        ?? records.find((item) => String((item as Record<string, unknown>).id) === recordId)
      return json(route, record)
    }
    if (path === '/api/inspections' && request.method() === 'POST') {
      const payload = request.postDataJSON() as Record<string, unknown>
      capture?.(payload)
      const failure = saveFailure?.()
      if (failure) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: failure.code, message: failure.message, data: null }),
        })
      }
      return json(route, { id: 'INS-1', ...payload })
    }
    if (path.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })
}

test('latest 200-point standard calculates 180/179 and red-line result while saving complete itemResults', async ({ page }) => {
  let savedPayload: Record<string, unknown> | undefined
  await mockInspectionApi(page, [], (payload) => { savedPayload = payload })
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  await expect(page.getByText('物料标准', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('卫生标准', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('服务标准', { exact: true }).first()).toBeVisible()
  await expect(page.getByText(/105条（物料40 \/ 卫生47 \/ 服务18）· 200分 · 合格线180分/)).toBeVisible()
  await expect(page.locator('[data-category="MATERIAL"] tbody tr')).toHaveCount(40)
  await expect(page.locator('[data-category="HYGIENE"] tbody tr')).toHaveCount(47)
  await expect(page.locator('[data-category="SERVICE"] tbody tr')).toHaveCount(18)
  await expect(page.locator('[data-category="SERVICE"] tbody tr').filter({ hasText: '产品核对' })).toHaveCount(1)
  await expect(page.locator('[data-category="MATERIAL"] tbody tr').filter({ hasText: '产品核对' })).toHaveCount(0)
  await expect(page.getByLabel('满分基准')).toHaveAttribute('readonly', '')

  const fileInput = page.locator('input[type="file"][accept="image/*"]')
  await fileInput.setInputFiles({ name: '现场照片.png', mimeType: 'image/png', buffer: Buffer.from('test-image') })
  await page.getByRole('button', { name: '确认未发现问题' }).click()

  const hygieneScore = page.getByLabel('H-01实际分')
  await hygieneScore.fill('62')
  await hygieneScore.blur()
  await page.getByLabel('H-01扣分原因').fill('卫生黄线问题')
  await expect(page.getByText('黄线风险')).toBeVisible()
  await expect(page.getByText('199 / 200')).toBeVisible()
  await expect(page.getByText('红线不合格', { exact: true })).toHaveCount(0)
  await hygieneScore.fill('63')
  await hygieneScore.blur()
  await page.getByLabel('H-01扣分原因').fill('')

  const serviceScore = page.getByLabel('S-01实际分')
  await serviceScore.fill('80')
  await serviceScore.blur()
  await page.getByLabel('S-01扣分原因').fill('服务动作不规范')
  await expect(page.getByText('180 / 200')).toBeVisible()
  await expect(page.getByText(/最终以保存后服务端判定为准/)).toBeVisible()

  await serviceScore.fill('79')
  await serviceScore.blur()
  await expect(page.getByText('179 / 200')).toBeVisible()
  await expect(page.getByText(/最终以保存后服务端判定为准/)).toBeVisible()

  await page.getByRole('row', { name: /M-RED-01/ }).getByRole('checkbox').first().check()
  await page.getByLabel('M-RED-01扣分原因').fill('发现禁用原料')
  await page.getByRole('row', { name: /S-01/ }).getByLabel('问题：现场照片.png', { exact: true }).check()
  await expect(page.getByText('不合格（命中红线）', { exact: true })).toBeVisible()
  await expect(page.getByText('179 / 200')).toBeVisible()

  await page.getByLabel('督导人').fill('测试督导')
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect.poll(() => savedPayload).toBeTruthy()

  const itemResults = savedPayload?.itemResults as Array<Record<string, unknown>>
  expect(itemResults).toHaveLength(105)
  expect(itemResults.find((item) => item.standardItemId === 1)?.issueFound).toBe(true)
  expect(itemResults.find((item) => item.standardItemId === 88)?.actualScore).toBe(79)
  expect(itemResults.find((item) => item.standardItemId === 88)?.deductionReason).toBe('服务动作不规范')
  expect(itemResults.find((item) => item.standardItemId === 88)?.photoAttachmentIds).toEqual([501])
  expect(savedPayload?.standardVersionId).toBe(20251106)
  expect(savedPayload?.fullScore).toBe(200)
  expect(savedPayload).not.toHaveProperty('passed')

  const photosJson = String(savedPayload?.photosJson)
  expect(photosJson).not.toContain('storagePath')
  expect(photosJson).not.toContain('sensitive/internal')
  expect(photosJson).not.toContain('TRANSIENT_ONLY')
  expect(photosJson).toContain('"attachmentId":501')
})

test('record detail shows version/category scores and downloads xlsx with authenticated blob request', async ({ page }) => {
  let requestedDetailId = ''
  const listRecord = {
    id: 'INS-2025', storeId: 'STORE-1', storeName: '测试门店', brand: '茹菓', inspectionDate: '2026-07-13', inspector: '测试督导',
    fullScore: 200, score: 179, maxScore: 200, passScore: 180, passed: false, resultCode: 'FAILED', standardVersionId: 20251106, standardVersion: '2025.11.06',
    materialScore: 37, hygieneScore: 63, serviceScore: 79, photosJson: '[]', note: '', repairStatus: 'REPAIRED', repairAuditId: 801,
    deductionsJson: JSON.stringify([{ standardId: 88, standardTitle: '旧JSON条款', suggestedScore: 100, deduct: 98, issue: '旧JSON错误分值' }]),
    itemResults: [],
  }
  const detailRecord = {
    ...listRecord,
    itemResults: standard.items.map((item) => ({ standardItemId: item.id, code: item.code, dimension: item.dimension, categoryName: item.dimension.replace('标准', ''), categoryCode: item.dimension.includes('物料') ? 'MATERIAL' : item.dimension.includes('卫生') ? 'HYGIENE' : 'SERVICE', title: item.title, standardScore: item.suggestedScore, actualScore: item.id === 88 ? 79 : item.suggestedScore, deductionReason: item.id === 88 ? '服务动作不规范' : '', riskLevel: item.riskLevel, issueFound: false, photoAttachmentIds: [] })),
  }
  await mockInspectionApi(page, [listRecord], undefined, standard, undefined, { 'INS-2025': detailRecord }, (recordId) => { requestedDetailId = recordId })
  await page.route('**/api/inspections/INS-2025/export.xlsx', (route) => route.fulfill({
    status: 200,
    headers: { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'content-disposition': "attachment; filename*=UTF-8''%E8%8C%B9%E8%8F%93-%E6%B5%8B%E8%AF%95%E9%97%A8%E5%BA%97.xlsx" },
    body: Buffer.from('xlsx-content'),
  }))
  await seedSession(page)
  await page.goto('/operations/inspection/records')
  await page.getByRole('row', { name: /测试门店/ }).click()

  await expect.poll(() => requestedDetailId).toBe('INS-2025')
  await expect(page.getByText('2025.11.06')).toBeVisible()
  await expect(page.getByText('37 / 37')).toBeVisible()
  await expect(page.getByText('63 / 63')).toBeVisible()
  await expect(page.getByText('79 / 100')).toBeVisible()
  await expect(page.locator('.inspection-detail-grid').getByText('179 / 200')).toBeVisible()
  await expect(page.getByText('当时使用的完整标准快照（105条）')).toBeVisible()
  await expect(page.getByRole('cell', { name: '服务动作不规范' })).toBeVisible()
  await expect(page.getByText(/旧JSON错误分值|扣 98 分/)).toHaveCount(0)

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出Excel' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('茹菓-测试门店.xlsx')
})

test('inspection export decodes a JSON score-repair error returned as a blob', async ({ page }) => {
  const incompleteRecord = {
    id: 'INS-SCORE-REPAIR',
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-07-13',
    inspector: '测试督导',
    score: 98,
    passed: true,
    resultCode: 'PASSED',
    deductionsJson: '[]',
    redlinesJson: '[]',
    photosJson: '[]',
    itemResults: [],
  }
  await mockInspectionApi(page, [incompleteRecord])
  await page.route('**/api/inspections/INS-SCORE-REPAIR/export.xlsx', (route) => route.fulfill({
    status: 422,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({
      success: false,
      code: 'INSPECTION_SCORE_REPAIR_REQUIRED',
      message: '评分数据待修复：缺少满分、合格线',
      data: null,
    }),
  }))
  await seedSession(page)
  await page.goto('/operations/inspection/records')
  await page.getByRole('row', { name: /测试门店/ }).click()

  await page.getByRole('button', { name: '导出Excel' }).click()
  await expect(page.getByText('该巡检记录评分数据不完整，请先修复评分后再导出。缺失项：缺少满分、合格线')).toBeVisible()
  await expect(page.getByText('巡检报告导出失败，请稍后重试。')).toHaveCount(0)
})

test('invalid 105-item standard remains visible with category diagnostics and blocks new inspection saving', async ({ page }) => {
  await mockInspectionApi(page, [], undefined, invalidStandard)
  await seedSession(page)
  await page.goto('/operations/inspection/standards')

  await expect(page.getByText('标准校验未通过，以下条款仅供核对')).toBeVisible()
  await expect(page.getByText('物料应为 40 条 / 37 分，当前 43 条 / 49 分')).toBeVisible()
  await expect(page.getByText('卫生应为 47 条 / 63 分，当前 47 条 / 66 分')).toBeVisible()
  await expect(page.getByText('服务应为 18 条 / 100 分，当前 15 条 / 85 分')).toBeVisible()
  await expect(page.getByRole('table', { name: '物料标准条款' }).locator('tbody tr')).toHaveCount(43)
  await expect(page.getByRole('table', { name: '卫生标准条款' }).locator('tbody tr')).toHaveCount(47)
  await expect(page.getByRole('table', { name: '服务标准条款' }).locator('tbody tr')).toHaveCount(15)

  await page.getByRole('button', { name: '发起巡检' }).click()
  await expect(page.getByText('当前标准未通过校验，只能只读查看，不能保存巡检。')).toBeVisible()
  await expect(page.locator('[data-category="MATERIAL"] tbody tr')).toHaveCount(43)
  await expect(page.locator('[data-category="HYGIENE"] tbody tr')).toHaveCount(47)
  await expect(page.locator('[data-category="SERVICE"] tbody tr')).toHaveCount(15)
  await expect(page.getByRole('button', { name: '保存巡检' }).first()).toBeDisabled()
  await expect(page.getByRole('button', { name: '刷新标准' }).first()).toBeEnabled()
})

test('record list, result and average all use the backend 200-point contract', async ({ page }) => {
  const records = [
    {
      id: 'INS-HUATAI-196', storeId: 'STORE-1', storeName: '花台店', brand: '茹菓', inspectionDate: '2026-07-12', inspector: '测试督导',
      score: 196, maxScore: 200, passScore: 180, passed: true, resultCode: 'PASSED', repairStatus: 'REPAIRED',
      originalScore: 98, originalFullScore: 100, repairAuditId: 701, deductionsJson: '[]', redlinesJson: '[]', photosJson: '[]', itemResults: [],
    },
    {
      id: 'INS-JINGZHOU-164', storeId: 'STORE-2', storeName: '荆州之星店', brand: '茹菓', inspectionDate: '2026-07-11', inspector: '测试督导',
      score: 164, maxScore: 200, passScore: 180, passed: false, resultCode: 'FAILED', repairStatus: 'REPAIRED',
      originalScore: 82, originalFullScore: 100, repairAuditId: 702, deductionsJson: '[]', redlinesJson: '[]', photosJson: '[]', itemResults: [],
    },
  ]
  await mockInspectionApi(page, records)
  await seedSession(page)
  await page.goto('/operations/inspection/records')

  await expect(page.getByRole('row', { name: /花台店/ })).toContainText('196 / 200')
  await expect(page.getByRole('row', { name: /花台店/ })).toContainText('合格')
  await expect(page.getByRole('row', { name: /荆州之星店/ })).toContainText('164 / 200')
  await expect(page.getByRole('row', { name: /荆州之星店/ })).toContainText('不合格')
  await expect(page.getByText('180 / 200', { exact: true })).toBeVisible()

  await page.getByRole('row', { name: /花台店/ }).click()
  await expect(page.getByText('98 / 100 → 196 / 200 · 审计记录 #701')).toBeVisible()
  await expect(page.getByText('历史巡检条款快照（0条）')).toBeVisible()
  await expect(page.getByText('历史问题快照不完整，原始说明仅供审计，不参与当前评分。')).toBeVisible()
})

test('missing score metadata is exposed for repair instead of being converted or rejudged in the browser', async ({ page }) => {
  const incompleteRecord = {
    id: 'INS-INCOMPLETE-98',
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-01-01',
    inspector: '历史督导',
    score: 98,
    passed: true,
    resultCode: 'PASSED',
    deductionsJson: '[]',
    redlinesJson: '[]',
    photosJson: '[]',
    itemResults: [],
  }
  await mockInspectionApi(page, [incompleteRecord])
  await seedSession(page)
  await page.goto('/operations/inspection/records')

  const row = page.getByRole('row', { name: /测试门店/ })
  await expect(row).toContainText('评分数据待修复')
  await row.click()
  await expect(page.getByText(/评分数据待修复：缺少满分、合格线/)).toBeVisible()
  await expect(page.getByText('历史巡检条款快照（0条）')).toBeVisible()
  await expect(page.getByText(/完整标准快照/)).toHaveCount(0)
})

test('only an inspection record conflict is labelled as another-user concurrent update', async ({ page }) => {
  let failure = {
    code: 'INSPECTION_STANDARD_INVALID',
    message: '当前巡检标准校验未通过',
  }
  await mockInspectionApi(page, [], undefined, standard, () => failure)
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  const fileInput = page.locator('input[type="file"][accept="image/*"]')
  await fileInput.setInputFiles({ name: '现场照片.png', mimeType: 'image/png', buffer: Buffer.from('test-image') })
  await page.getByRole('button', { name: '确认未发现问题' }).click()
  await page.getByRole('row', { name: /S-01/ }).getByLabel('问题：现场照片.png', { exact: true }).check()
  await page.getByLabel('督导人').fill('测试督导')

  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect(page.getByText('当前巡检标准校验未通过，已禁止保存。请刷新标准后再试。')).toBeVisible()
  await expect(page.getByText(/其他人更新/)).toHaveCount(0)

  failure = {
    code: 'INSPECTION_STANDARD_STALE',
    message: '巡检标准已更新',
  }
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect(page.getByText('巡检标准已更新，请点击“刷新标准”后重新评分。')).toBeVisible()
  await expect(page.getByText(/其他人更新/)).toHaveCount(0)

  failure = {
    code: 'INSPECTION_RECORD_CONFLICT',
    message: '巡检记录版本冲突',
  }
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect(page.getByText('这条巡检已被其他人更新，请刷新记录后再提交。')).toBeVisible()
})

test('read-only store manager is routed to records and cannot see manage tabs', async ({ page }) => {
  const storeManager = {
    ...bossUser,
    role: 'STORE_MANAGER',
    roleLabel: '店长',
    permissions: ['inspection.read'],
    dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['STORE-1'] }, INSPECTION: { mode: 'OWN_STORE', storeIds: ['STORE-1'] } },
    boundStoreId: 'STORE-1',
    boundStoreName: '测试门店',
    defaultWorkspace: '/store',
  }
  await mockInspectionApi(page)
  await page.route('**/api/auth/me', (route) => json(route, storeManager))
  await seedSession(page, storeManager)
  await page.goto('/operations/inspection')

  await expect(page).toHaveURL(/\/operations\/inspection\/records$/)
  await expect(page.getByRole('button', { name: '巡检记录' })).toBeVisible()
  await expect(page.getByRole('button', { name: '发起巡检' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '稽核标准' })).toHaveCount(0)
})
