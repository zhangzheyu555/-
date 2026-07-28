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

/**
 * Code-owned export fixture for the browser regression.  It deliberately has the same ID as
 * the old database-only E2E sample, but it is a complete immutable 200-point record rather
 * than a shortcut around the historical-report export gate.
 */
const canonicalExportStandard = {
  ...standard,
  id: 20260701,
  title: '2026年巡检正式标准',
  version: '2026.07-R1',
  effectiveDate: '2026-07-01',
  items: createValidStandardItems().map((item) => {
    if (item.id === 1) return { ...item, suggestedScore: 4 }
    if (item.id === 2) return { ...item, suggestedScore: 33 }
    return item
  }),
}

const tinyPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL13wAAAABJRU5ErkJggg==',
  'base64',
)

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
  await expect(page.locator('.inspection-standard-note')).toContainText('105条（物料40 / 卫生47 / 服务18） · 200分 · 合格线180分')
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

  const clausePicker = page.getByRole('combobox', { name: '搜索选择检查条款' })
  const manualDeduct = page.locator('.inspection-add-form input[type="number"]')
  await expect(manualDeduct).toHaveValue('')
  await clausePicker.fill('M-01 判定说明')
  await expect(manualDeduct).toHaveValue('')
  await page.getByRole('option').filter({ hasText: 'M-01 · M-01 检查条款' }).click()
  await expect(clausePicker).toHaveValue('M-01 · M-01 检查条款')
  await expect(manualDeduct).toHaveValue('37')
  await expect(manualDeduct).toHaveAttribute('readonly', '')
  await page.getByLabel('问题描述').fill('物料手工扣分使用稳定条款 ID')
  await page.getByRole('button', { name: '添加', exact: true }).click()
  await expect(page.locator('.inspection-deduction-card').getByRole('row', { name: /M-01 M-01 检查条款/ })).toContainText('37分')

  await page.getByLabel('督导人').fill('测试督导')
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect.poll(() => savedPayload).toBeTruthy()

  const itemResults = savedPayload?.itemResults as Array<Record<string, unknown>>
  expect(itemResults).toHaveLength(105)
  expect(itemResults.find((item) => item.standardItemId === 1)?.issueFound).toBe(true)
  expect(itemResults.find((item) => item.standardItemId === 2)?.actualScore).toBe(0)
  expect(itemResults.find((item) => item.standardItemId === 2)?.deductionReason).toBe('物料手工扣分使用稳定条款 ID')
  expect(itemResults.find((item) => item.standardItemId === 3)?.actualScore).toBe(0)
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

test('manual red-line selection keeps the prescribed zero score and cannot be edited', async ({ page }) => {
  await mockInspectionApi(page)
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  const clausePicker = page.getByRole('combobox', { name: '搜索选择检查条款' })
  const prescribedScore = page.getByLabel('条款规定扣分')

  await clausePicker.fill('M-RED-01')
  await page.getByRole('option').filter({ hasText: 'M-RED-01 · M-RED-01 检查条款' }).click()

  await expect(prescribedScore).toHaveValue('0')
  await expect(prescribedScore).toHaveAttribute('readonly', '')
  await expect(page.getByText('红线条款：命中即判定不合格，本条款规定扣分为 0 分。')).toBeVisible()

  await page.getByLabel('问题描述').fill('发现红线违规')
  await page.getByRole('button', { name: '添加', exact: true }).click()

  const redLineRecord = page.locator('.inspection-deduction-card').getByRole('row', { name: /M-RED-01 M-RED-01 检查条款/ })
  await expect(redLineRecord).toContainText('红线')
  await expect(redLineRecord).not.toContainText('1分')
  await expect(page.getByRole('row', { name: /M-RED-01/ }).getByRole('checkbox').first()).toBeChecked()
})

test('manual deduction validation is shown next to the action in a modal instead of the page banner', async ({ page }) => {
  await mockInspectionApi(page)
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  const clausePicker = page.getByRole('combobox', { name: '搜索选择检查条款' })
  await clausePicker.fill('M-01')
  await page.getByRole('option').filter({ hasText: 'M-01 · M-01 检查条款' }).click()
  await page.getByRole('button', { name: '添加', exact: true }).click()

  const dialog = page.getByRole('alertdialog', { name: '无法添加扣分项' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('请写清楚现场问题，方便门店整改。')
  await expect(page.locator('.error-box').filter({ hasText: '请写清楚现场问题' })).toHaveCount(0)

  await dialog.getByRole('button', { name: '返回修改' }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: '添加', exact: true })).toBeFocused()
})

test('manual review precedes the clause list, final save follows it, and standards render only on their tab', async ({ page }) => {
  await mockInspectionApi(page)
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  const search = page.getByRole('searchbox', { name: '搜索检查条款' })
  await expect(search).toBeVisible()
  await expect(page.getByText('共 105 条', { exact: true })).toBeVisible()

  const manualReviewComesFirst = await page.evaluate(() => {
    const clauseSearch = document.querySelector('.inspection-clause-search')
    const reviewCards = [
      document.querySelector('.inspection-deduction-card'),
      document.querySelector('.inspection-add-card'),
    ]
    const noteCard = document.querySelector('.inspection-note-card')
    return Boolean(clauseSearch)
      && reviewCards.every((card) => (
        Boolean(card) && Boolean(card?.compareDocumentPosition(clauseSearch as Node) & Node.DOCUMENT_POSITION_FOLLOWING)
      ))
      && Boolean(noteCard?.compareDocumentPosition(clauseSearch as Node) & Node.DOCUMENT_POSITION_PRECEDING)
  })
  expect(manualReviewComesFirst).toBe(true)

  await search.fill('S-01 判定说明')
  await expect(page.locator('[data-category="MATERIAL"]')).toHaveCount(0)
  await expect(page.locator('[data-category="HYGIENE"]')).toHaveCount(0)
  await expect(page.locator('[data-category="SERVICE"] tbody tr')).toHaveCount(1)
  await expect(page.getByText('找到 1 / 105 条', { exact: true })).toBeVisible()

  await search.fill('卫生')
  await expect(page.locator('[data-category="MATERIAL"]')).toHaveCount(0)
  await expect(page.locator('[data-category="HYGIENE"] tbody tr')).toHaveCount(47)
  await expect(page.locator('[data-category="SERVICE"]')).toHaveCount(0)

  await search.fill('评分规则 100 分')
  await expect(page.locator('[data-category="SERVICE"] tbody tr')).toHaveCount(1)
  await expect(page.locator('[data-category="SERVICE"]').getByText('产品核对', { exact: true })).toBeVisible()

  await search.fill('现场检查并保留证据')
  await expect(page.getByText('找到 105 / 105 条', { exact: true })).toBeVisible()

  await search.fill('不存在的检查项')
  await expect(page.getByText('未找到包含“不存在的检查项”的检查项，请更换关键词或清空搜索。')).toBeVisible()

  await page.getByRole('button', { name: '清空搜索' }).click()
  await expect(search).toHaveValue('')
  await expect(page.locator('[data-category="MATERIAL"] tbody tr')).toHaveCount(40)
  await expect(page.locator('[data-category="HYGIENE"] tbody tr')).toHaveCount(47)
  await expect(page.locator('[data-category="SERVICE"] tbody tr')).toHaveCount(18)

  await expect(page.locator('.inspection-standards-view')).toHaveCount(0)
  await page.goto('/operations/inspection/standards')
  const standardCatalog = page.locator('.inspection-standards-view')
  const standardSearch = standardCatalog.getByRole('searchbox', { name: '搜索标准条款' })
  await expect(standardSearch).toBeVisible()
  await expect(standardCatalog.getByText('显示 105 / 105 条', { exact: true })).toBeVisible()
  await standardCatalog.getByRole('button', { name: '卫生', exact: true }).click()
  await standardSearch.fill('H-01 判定说明')
  await expect(standardCatalog.locator('.standards-table tbody tr')).toHaveCount(1)
  await expect(standardCatalog.getByText('显示 1 / 105 条', { exact: true })).toBeVisible()
  await standardSearch.fill('S-01')
  await expect(standardCatalog.locator('.standards-table tbody tr')).toHaveCount(0)
  await expect(standardCatalog.getByText('当前维度没有包含“S-01”的标准条款。')).toBeVisible()
  await standardCatalog.getByRole('button', { name: '全部维度', exact: true }).click()
  await expect(standardCatalog.locator('.standards-table tbody tr')).toHaveCount(1)
})

test('dirty inspection drafts require confirmation before being cleared', async ({ page }) => {
  await mockInspectionApi(page)
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  await page.getByLabel('督导人').fill('测试督导')
  await page.getByLabel('整改要求 / 备注').fill('闭店前完成复查')
  await page.getByRole('button', { name: '清空表单' }).click()

  const dialog = page.getByRole('alertdialog', { name: '放弃未保存的巡店草稿？' })
  await expect(dialog).toBeVisible()
  await page.getByRole('button', { name: '继续填写' }).click()
  await expect(page.getByLabel('督导人')).toHaveValue('测试督导')
  await expect(page.getByLabel('整改要求 / 备注')).toHaveValue('闭店前完成复查')

  await page.getByRole('button', { name: '清空表单' }).click()
  await page.getByRole('button', { name: '放弃草稿' }).click()
  await expect(page.getByLabel('督导人')).toHaveValue('')
  await expect(page.getByLabel('整改要求 / 备注')).toHaveValue('')
})

test('save stays clickable and reports incomplete inspection fields in an error dialog', async ({ page }) => {
  await mockInspectionApi(page)
  await seedSession(page)
  await page.goto('/operations/inspection/tasks')

  const saveButtons = page.getByRole('button', { name: '保存巡检' })
  await expect(saveButtons).toHaveCount(2)
  await expect(saveButtons.first()).toBeEnabled()
  await expect(saveButtons.last()).toBeEnabled()
  await expect(page.locator('.inspection-save-hint')).toHaveCount(0)

  await saveButtons.first().click()

  const dialog = page.getByRole('alertdialog', { name: '无法保存巡检' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('请填写督导人姓名')
  await page.getByRole('button', { name: '返回填写' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(saveButtons.first()).toBeEnabled()
})

test('inspection detail exposes a back button while detail data is still loading', async ({ page }) => {
  const recordId = 'INS-DETAIL-LOADING'
  let releaseDetailRequest!: () => void
  const detailRequestGate = new Promise<void>((resolve) => {
    releaseDetailRequest = resolve
  })
  const record = {
    id: recordId,
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-07-28',
    inspector: '测试督导',
    fullScore: 200,
    score: 200,
    maxScore: 200,
    passScore: 180,
    passed: true,
    resultCode: 'PASSED',
    standardVersionId: canonicalExportStandard.id,
    standardVersion: canonicalExportStandard.version,
    photosJson: '[]',
    deductionsJson: '[]',
    redlinesJson: '[]',
    itemResults: [],
  }
  await mockInspectionApi(
    page,
    [record],
    undefined,
    canonicalExportStandard,
    undefined,
    { [recordId]: record },
  )
  await page.route(`**/api/inspections/${recordId}`, async (route) => {
    await detailRequestGate
    await json(route, record)
  })
  await seedSession(page)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)

  await expect(page.getByText('正在读取巡检详情...', { exact: true })).toBeVisible()
  const backButton = page.getByRole('button', { name: '返回巡检列表', exact: true })
  await expect(backButton).toBeVisible()
  await backButton.click()
  await expect(page).toHaveURL('/operations/inspection/records')
  await expect(page.getByRole('table')).toBeVisible()

  releaseDetailRequest()
})

test('inspection detail prioritizes the issue with evidence and keeps the 105-clause audit snapshot collapsed', async ({ page }) => {
  const recordId = 'INS-DETAIL-PRIORITY-ISSUE'
  const attachmentId = 777
  const issueClauseId = 70
  const reason = '角落、灯带及边角有积灰污点，需及时清理干净'
  const itemResults = canonicalExportStandard.items.map((item) => {
    const isIssue = item.id === issueClauseId
    return {
      snapshotId: item.id,
      standardItemId: item.id,
      code: isIssue ? 'H-4.1.2' : item.code,
      dimension: item.dimension,
      categoryName: item.dimension.replace('标准', ''),
      categoryCode: item.dimension.includes('物料') ? 'MATERIAL' : item.dimension.includes('卫生') ? 'HYGIENE' : 'SERVICE',
      title: isIssue ? '店铺内部' : item.title,
      standardScore: isIssue ? 2 : item.suggestedScore,
      actualScore: isIssue ? 0 : item.suggestedScore,
      deductionScore: isIssue ? 4 : 0,
      deductionReason: isIssue ? reason : '',
      riskLevel: item.riskLevel,
      issueFound: isIssue,
      redLineHit: false,
      photoAttachmentIds: isIssue ? [attachmentId] : [],
    }
  })
  const record = {
    id: recordId,
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-07-28',
    inspector: '测试督导',
    fullScore: 200,
    score: 196,
    maxScore: 200,
    passScore: 180,
    passed: true,
    resultCode: 'PASSED',
    standardVersionId: canonicalExportStandard.id,
    standardVersion: canonicalExportStandard.version,
    materialScore: 37,
    hygieneScore: 59,
    serviceScore: 100,
    photosJson: JSON.stringify([{ attachmentId, fileName: '店铺内部问题.jpg', contentType: 'image/jpeg' }]),
    deductionsJson: '[]',
    redlinesJson: '[]',
    itemResults,
  }
  await mockInspectionApi(
    page,
    [record],
    undefined,
    canonicalExportStandard,
    undefined,
    { [recordId]: record },
  )
  await page.route(`**/api/inspections/${recordId}/evidence/attachments`, (route) => json(route, {
    recordId,
    storeId: 'STORE-1',
    candidates: [{
      attachmentId,
      photoIndex: 0,
      fileName: '店铺内部问题.jpg',
      contentType: 'image/jpeg',
      status: 'LINKED',
      linkedClauseIds: [issueClauseId],
    }],
  }))
  await page.route(`**/api/storage/attachments/${attachmentId}`, (route) => route.fulfill({
    status: 200,
    contentType: 'image/png',
    body: tinyPng,
  }))
  await seedSession(page)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)

  const issues = page.getByRole('region', { name: '本次发现的问题（1项）' })
  await expect(issues).toBeVisible()
  await expect(issues.getByText('H-4.1.2', { exact: true })).toBeVisible()
  await expect(issues.getByText('扣 4 分', { exact: true })).toBeVisible()
  await expect(issues.getByText(reason, { exact: true })).toBeVisible()
  await expect(issues.getByRole('button', { name: '预览 店铺内部问题.jpg' })).toBeVisible()
  const issueBox = await issues.boundingBox()
  expect(issueBox).not.toBeNull()
  expect(issueBox!.y + issueBox!.height).toBeLessThanOrEqual(720)

  await page.setViewportSize({ width: 1464, height: 1000 })
  const backButton = page.getByRole('button', { name: '返回巡检列表', exact: true })
  await expect(backButton).toBeVisible()
  const backButtonBox = await backButton.boundingBox()
  const detailCardBox = await page.locator('.inspection-detail-card').boundingBox()
  expect(backButtonBox).not.toBeNull()
  expect(detailCardBox).not.toBeNull()
  expect(backButtonBox!.y + backButtonBox!.height).toBeLessThanOrEqual(detailCardBox!.y)

  const actionButtons = [
    page.getByRole('button', { name: '补传并关联证据' }),
    page.getByRole('button', { name: '导出Excel' }),
  ]
  const actionGeometry = await Promise.all(actionButtons.map(async (button) => {
    await expect(button).toBeVisible()
    const box = await button.boundingBox()
    const layout = await button.evaluate((element) => {
      const style = window.getComputedStyle(element)
      return {
        whiteSpace: style.whiteSpace,
        clientHeight: element.clientHeight,
        scrollHeight: element.scrollHeight,
      }
    })
    return { box, layout }
  }))
  const actionTops = actionGeometry.map(({ box }) => box!.y)
  const actionHeights = actionGeometry.map(({ box }) => box!.height)
  expect(Math.max(...actionTops) - Math.min(...actionTops)).toBeLessThanOrEqual(1)
  expect(Math.max(...actionHeights) - Math.min(...actionHeights)).toBeLessThanOrEqual(1)
  expect(Math.min(...actionHeights)).toBeGreaterThanOrEqual(40)
  actionGeometry.forEach(({ layout }) => {
    expect(layout.whiteSpace).toBe('nowrap')
    expect(layout.scrollHeight).toBeLessThanOrEqual(layout.clientHeight)
  })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)

  await page.setViewportSize({ width: 390, height: 844 })
  const mobileActionGeometry = await Promise.all(actionButtons.map(async (button) => {
    const box = await button.boundingBox()
    const whiteSpace = await button.evaluate((element) => window.getComputedStyle(element).whiteSpace)
    return { box, whiteSpace }
  }))
  const mobileLefts = mobileActionGeometry.map(({ box }) => box!.x)
  const mobileWidths = mobileActionGeometry.map(({ box }) => box!.width)
  const mobileHeights = mobileActionGeometry.map(({ box }) => box!.height)
  expect(Math.max(...mobileLefts) - Math.min(...mobileLefts)).toBeLessThanOrEqual(1)
  expect(Math.max(...mobileWidths) - Math.min(...mobileWidths)).toBeLessThanOrEqual(1)
  expect(Math.max(...mobileHeights) - Math.min(...mobileHeights)).toBeLessThanOrEqual(1)
  expect(Math.min(...mobileHeights)).toBeGreaterThanOrEqual(44)
  mobileActionGeometry.forEach(({ whiteSpace }) => expect(whiteSpace).toBe('nowrap'))
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)

  const expandSnapshot = page.getByRole('button', { name: '查看完整标准快照（105条）' })
  await expect(expandSnapshot).toBeVisible()
  const snapshotTable = page.getByRole('table', { name: '当时使用的完整标准快照（105条）条款' })
  await expect(snapshotTable).toHaveCount(0)
  await expandSnapshot.click()
  await expect(page.getByRole('button', { name: '收起完整标准快照（105条）' })).toBeVisible()
  await expect(snapshotTable).toBeVisible()
  await expect(snapshotTable.locator('tbody tr')).toHaveCount(105)
})

test('canonical E2E red-line fixture uses a complete formal standard and downloads xlsx with an authenticated request', async ({ page }) => {
  let requestedDetailId = ''
  let exportAuthorization = ''
  const recordId = 'e2e-seed-redline-inspection'
  const listRecord = {
    id: recordId, storeId: 'STORE-1', storeName: '测试门店', brand: '茹菓', inspectionDate: '2026-07-13', inspector: 'E2E-SEED 督导',
    fullScore: 200, score: 196, maxScore: 200, passScore: 180, passed: false, resultCode: 'RED_LINE_FAILED', standardVersionId: canonicalExportStandard.id, standardVersion: canonicalExportStandard.version,
    materialScore: 33, hygieneScore: 63, serviceScore: 100, photosJson: '[]', note: '正式红线导出回归样本', repairStatus: 'NOT_NEEDED',
    deductionsJson: JSON.stringify([{ standardId: 1, standardTitle: 'M-RED-01 检查条款', suggestedScore: 4, actualScore: 0, deduct: 4, issue: '食安红线：E2E 导出回归' }]),
    redlinesJson: JSON.stringify([{ standardId: 1, standardTitle: 'M-RED-01 检查条款', issue: '食安红线：E2E 导出回归' }]),
    itemResults: [],
  }
  const detailRecord = {
    ...listRecord,
    itemResults: canonicalExportStandard.items.map((item) => {
      const redLineHit = item.id === 1
      return {
        snapshotId: item.id,
        standardItemId: item.id,
        code: item.code,
        dimension: item.dimension,
        categoryName: item.dimension.replace('标准', ''),
        categoryCode: item.dimension.includes('物料') ? 'MATERIAL' : item.dimension.includes('卫生') ? 'HYGIENE' : 'SERVICE',
        title: item.title,
        standardScore: item.suggestedScore,
        actualScore: redLineHit ? 0 : item.suggestedScore,
        deductionScore: redLineHit ? 4 : 0,
        deductionReason: redLineHit ? '食安红线：E2E 导出回归' : '',
        riskLevel: item.riskLevel,
        issueFound: redLineHit,
        redLineHit,
        photoAttachmentIds: [],
      }
    }),
  }
  await mockInspectionApi(page, [listRecord], undefined, canonicalExportStandard, undefined, { [recordId]: detailRecord }, (id) => { requestedDetailId = id })
  await page.route(`**/api/inspections/${recordId}/export.xlsx`, (route) => {
    exportAuthorization = route.request().headers().authorization ?? ''
    return route.fulfill({
      status: 200,
      headers: { 'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'content-disposition': "attachment; filename*=UTF-8''%E8%8C%B9%E8%8F%93-%E6%B5%8B%E8%AF%95%E9%97%A8%E5%BA%97.xlsx" },
      body: Buffer.from('xlsx-content'),
    })
  })
  await seedSession(page)
  await page.goto('/operations/inspection/records')
  await page.getByRole('row', { name: /测试门店/ }).click()

  await expect.poll(() => requestedDetailId).toBe(recordId)
  await expect(page.locator('.inspection-detail-grid').getByText('2026.07-R1', { exact: true })).toBeVisible()
  await expect(page.getByText('当时使用的完整标准快照（105条）')).toBeVisible()
  await expect(page.locator('.inspection-detail-grid').getByText('33 / 37', { exact: true })).toBeVisible()
  await expect(page.locator('.inspection-detail-grid').getByText('63 / 63', { exact: true })).toBeVisible()
  await expect(page.locator('.inspection-detail-grid').getByText('100 / 100', { exact: true })).toBeVisible()
  await expect(page.locator('.inspection-detail-grid').getByText('196 / 200')).toBeVisible()
  await expect(page.locator('.inspection-detail-grid').getByText('1 / 0', { exact: true })).toBeVisible()
  const issues = page.getByRole('region', { name: '本次发现的问题（1项）' })
  await expect(issues.getByText('食安红线：E2E 导出回归', { exact: true })).toBeVisible()
  await expect(issues.getByText('扣 4 分', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看完整标准快照（105条）' })).toBeVisible()

  await page.setViewportSize({ width: 1280, height: 720 })
  await expect(page.locator('.inspection-detail-grid').getByText('2026.07-R1', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '导出Excel' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1)).toBe(true)

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出Excel' }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('茹菓-测试门店.xlsx')
  // The browser fixture cannot observe a server-side operation log; it does prove the download
  // uses the authenticated API path. InspectionExportServiceTest covers the persisted log write.
  expect(exportAuthorization).toBe('Bearer TEST-INSPECTION-TOKEN')
})

test('inspection export preserves 409 score-repair details and requires manual score repair', async ({ page }) => {
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
    status: 409,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({
      success: false,
      code: 'INSPECTION_SCORE_REPAIR_REQUIRED',
      message: '评分数据待修复：标准快照版本、标准快照条款 ID',
      data: null,
    }),
  }))
  await seedSession(page)
  await page.goto('/operations/inspection/records')
  await page.getByRole('row', { name: /测试门店/ }).click()

  await page.getByRole('button', { name: '导出Excel' }).click()
  await expect(page.getByText('该巡检记录评分数据不完整，缺失项：标准快照版本、标准快照条款 ID。需人工修复评分后导出。')).toBeVisible()
  await expect(page.getByText('数据已发生变化，请重新打开当前记录后再试')).toHaveCount(0)
  await expect(page.getByText('巡检报告导出失败，请稍后重试。')).toHaveCount(0)
})

test('inspection export labels only an optimistic-lock conflict as reopen and retry', async ({ page }) => {
  const completeRecord = {
    id: 'INS-EXPORT-CONFLICT',
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-07-13',
    inspector: '测试督导',
    score: 200,
    fullScore: 200,
    maxScore: 200,
    passScore: 180,
    passed: true,
    resultCode: 'PASSED',
    standardVersionId: 20251106,
    standardVersion: '2025.11.06',
    deductionsJson: '[]',
    redlinesJson: '[]',
    photosJson: '[]',
    itemResults: [],
  }
  await mockInspectionApi(page, [completeRecord])
  await page.route('**/api/inspections/INS-EXPORT-CONFLICT/export.xlsx', (route) => route.fulfill({
    status: 409,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({
      success: false,
      code: 'INSPECTION_RECORD_CONFLICT',
      message: '巡检记录版本冲突',
      data: null,
    }),
  }))
  await seedSession(page)
  await page.goto('/operations/inspection/records')
  await page.getByRole('row', { name: /测试门店/ }).click()

  await page.getByRole('button', { name: '导出Excel' }).click()
  await expect(page.getByText('数据已发生变化，请重新打开当前记录后再试')).toBeVisible()
  await expect(page.getByText(/需人工修复评分后导出/)).toHaveCount(0)
})

test('invalid 105-item standard remains visible and explains the save failure in a dialog', async ({ page }) => {
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

  await page.getByRole('tab', { name: '发起巡检' }).click()
  await expect(page.getByText('当前标准未通过校验，只能只读查看，不能保存巡检。')).toBeVisible()
  await expect(page.locator('[data-category="MATERIAL"] tbody tr')).toHaveCount(43)
  await expect(page.locator('[data-category="HYGIENE"] tbody tr')).toHaveCount(47)
  await expect(page.locator('[data-category="SERVICE"] tbody tr')).toHaveCount(15)
  await expect(page.getByRole('button', { name: '拍照/选图' })).toBeEnabled()
  await page.getByRole('button', { name: '拍照/选图' }).click()
  const uploadDialog = page.getByRole('alertdialog', { name: '无法上传巡检照片' })
  await expect(uploadDialog).toContainText('当前稽核标准尚未就绪')
  await uploadDialog.getByRole('button', { name: '返回检查' }).click()
  await page.getByLabel('督导人').fill('测试督导')
  await expect(page.getByRole('button', { name: '保存巡检' }).first()).toBeEnabled()
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  const saveDialog = page.getByRole('alertdialog', { name: '无法保存巡检' })
  await expect(saveDialog).toContainText('当前标准未通过校验，请重试获取标准后再保存')
  await saveDialog.getByRole('button', { name: '返回填写' }).click()
  await expect(page.getByRole('button', { name: '重试获取标准' }).first()).toBeEnabled()
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
  await expect(page.getByText('当前巡检标准校验未通过，已禁止保存。请重试获取标准后再试。')).toBeVisible()
  await expect(page.getByText(/其他人更新/)).toHaveCount(0)
  await page.getByRole('alertdialog', { name: '无法保存巡检' }).getByRole('button', { name: '返回填写' }).click()

  failure = {
    code: 'INSPECTION_STANDARD_STALE',
    message: '巡检标准已更新',
  }
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect(page.getByText('巡检标准已更新，请清空当前草稿并重试获取标准后重新评分。')).toBeVisible()
  await expect(page.getByText(/其他人更新/)).toHaveCount(0)
  await page.getByRole('alertdialog', { name: '无法保存巡检' }).getByRole('button', { name: '返回填写' }).click()

  failure = {
    code: 'INSPECTION_RECORD_CONFLICT',
    message: '巡检记录版本冲突',
  }
  await page.getByRole('button', { name: '保存巡检' }).first().click()
  await expect(page.getByText('这条巡检已被其他人更新，请重新打开当前记录后再提交。')).toBeVisible()
  await expect(page.locator('.error-box').filter({ hasText: '这条巡检已被其他人更新' })).toHaveCount(0)
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
  await expect(page.getByRole('tab', { name: '巡检记录' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '发起巡检' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '稽核标准' })).toHaveCount(0)
})
