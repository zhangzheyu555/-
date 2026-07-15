import { expect, test, type Page, type Route } from '@playwright/test'

const boss = {
  id: 9101,
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

const readOnlyStoreManager = {
  ...boss,
  id: 9102,
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  permissions: ['inspection.read'],
  dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['STORE-1'] }, INSPECTION: { mode: 'OWN_STORE', storeIds: ['STORE-1'] } },
  boundStoreId: 'STORE-1',
  boundStoreName: '测试门店',
  defaultWorkspace: '/store',
}

const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL13wAAAABJRU5ErkJggg==', 'base64')

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: status < 400, data }),
  })
}

function apiError(route: Route, status: number, code: string, message: string) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: false, code, message, data: null }),
  })
}

async function seed(page: Page, user = boss) {
  await page.goto('/login')
  await page.evaluate((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-HISTORICAL-EVIDENCE-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, user)
}

function baseRecord(recordId: string, photosJson: string, itemResults: unknown[]) {
  return {
    id: recordId,
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-07-14',
    inspector: '历史督导',
    fullScore: 200,
    score: 196,
    maxScore: 200,
    passScore: 180,
    passed: true,
    resultCode: 'PASSED',
    standardVersion: '2026.07-R1',
    deductionsJson: '[]',
    redlinesJson: '[]',
    photosJson,
    itemResults,
  }
}

function clause(id: number, code: string, attachments: number[] = []) {
  return {
    snapshotId: id,
    standardItemId: id,
    code,
    title: `${code} 历史条款`,
    categoryCode: 'HYGIENE',
    categoryName: '卫生标准',
    standardScore: 4,
    actualScore: 0,
    deductionScore: 4,
    deductionReason: '历史卫生扣分',
    riskLevel: 'YELLOW',
    issueFound: true,
    redLineHit: false,
    photoAttachmentIds: attachments,
  }
}

test('老板手工关联已入库证据后，历史条款显示受认证缩略图并可预览原图', async ({ page }) => {
  const recordId = 'INS-HISTORICAL-LINK'
  let linked = false
  let submitted: unknown
  const currentRecord = () => baseRecord(
    recordId,
    JSON.stringify([{ attachmentId: 701, fileName: '微信原图.jpg', contentType: 'image/jpeg' }]),
    [clause(41, 'H-01', linked ? [701] : [])],
  )
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return json(route, boss)
    if (pathname === '/api/inspections' && request.method() === 'GET') return json(route, [currentRecord()])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return json(route, currentRecord())
    if (pathname === `/api/inspections/${recordId}/evidence/attachments` && request.method() === 'GET') {
      return json(route, { recordId, storeId: 'STORE-1', candidates: [{ attachmentId: 701, photoIndex: 0, fileName: '微信原图.jpg', contentType: 'image/jpeg', status: linked ? 'LINKED' : 'UNLINKED', linkedClauseIds: linked ? [41] : [] }] })
    }
    if (pathname === `/api/inspections/${recordId}/evidence/link` && request.method() === 'POST') {
      submitted = request.postDataJSON()
      linked = true
      return json(route, { recordId, attachmentIds: [701], clauseIds: [41], action: 'ASSOCIATE', record: currentRecord() })
    }
    if (pathname === '/api/storage/attachments/701') return route.fulfill({ status: 200, contentType: 'image/png', body: tinyPng })
    if (pathname === '/api/inspection/standards') return json(route, { id: 1, version: '测试标准', fullScore: 200, passScore: 180, valid: true, saveAllowed: true, items: [] })
    if (pathname === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', name: '测试门店', brandId: 1, brandName: '茹菓' }])
    if (pathname.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })

  await seed(page)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)
  await expect(page.getByText('未关联现场证据')).toBeVisible()
  await expect(page.getByRole('button', { name: '预览 微信原图.jpg' })).toHaveCount(0)
  await page.locator('.unlinked-evidence-list').getByRole('button', { name: '关联已有证据' }).click()

  const dialog = page.getByRole('dialog', { name: '补传并关联证据' })
  await expect(dialog.getByText('微信原图.jpg')).toBeVisible()
  await expect(dialog.locator('.evidence-candidate input')).toBeChecked()
  await dialog.getByRole('checkbox', { name: /H-01 历史条款/ }).check()
  await dialog.getByRole('button', { name: '确认关联' }).click()
  await expect.poll(() => submitted).toEqual({ attachmentIds: [701], clauseIds: [41] })

  await expect(page.getByText('已将所选证据关联到历史条款；历史评分和整改状态未改动。')).toBeVisible()
  const preview = page.getByRole('button', { name: '预览 微信原图.jpg' })
  await expect(preview).toBeVisible()
  await preview.click()
  await expect(page.getByRole('dialog', { name: '现场图片预览' }).locator('img')).toHaveAttribute('src', /^blob:/)
  await expect(page.locator('.inspection-detail-grid').getByText('196 / 200', { exact: true })).toBeVisible()
})

test('缺失的旧 attachmentId 必须按 photoIndex 打开补传模式，不会误关联其他未关联图片', async ({ page }) => {
  const recordId = 'INS-HISTORICAL-UPLOAD'
  let uploaded = false
  let uploadBody = ''
  const currentRecord = () => baseRecord(
    recordId,
    uploaded
      ? JSON.stringify([
          { attachmentId: 702, fileName: '微信重新选择原图.png', contentType: 'image/png' },
          { attachmentId: 703, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg' },
        ])
      : JSON.stringify([
          { attachmentId: 701, fileName: '微信历史图片.jpg', contentType: 'image/jpeg' },
          { attachmentId: 703, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg' },
        ]),
    [clause(42, 'H-02', uploaded ? [702] : [])],
  )
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return json(route, boss)
    if (pathname === '/api/inspections' && request.method() === 'GET') return json(route, [currentRecord()])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return json(route, currentRecord())
    if (pathname === `/api/inspections/${recordId}/evidence/attachments`) return json(route, { recordId, storeId: 'STORE-1', candidates: uploaded ? [{ attachmentId: 702, photoIndex: 0, fileName: '微信重新选择原图.png', contentType: 'image/png', status: 'LINKED', linkedClauseIds: [42] }, { attachmentId: 703, photoIndex: 1, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg', status: 'UNLINKED', linkedClauseIds: [] }] : [{ attachmentId: 701, photoIndex: 0, fileName: '微信历史图片.jpg', contentType: 'image/jpeg', status: 'ORIGINAL_NOT_STORED', linkedClauseIds: [] }, { attachmentId: 703, photoIndex: 1, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg', status: 'UNLINKED', linkedClauseIds: [] }] })
    if (pathname === `/api/inspections/${recordId}/evidence/upload` && request.method() === 'POST') {
      uploadBody = request.postData() || ''
      uploaded = true
      return json(route, { recordId, attachmentIds: [702], clauseIds: [42], action: 'SUPPLEMENT', record: currentRecord() })
    }
    if (pathname === '/api/storage/attachments/702') return route.fulfill({ status: 200, contentType: 'image/png', body: tinyPng })
    if (pathname === '/api/inspection/standards') return json(route, { id: 1, version: '测试标准', fullScore: 200, passScore: 180, valid: true, saveAllowed: true, items: [] })
    if (pathname === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', name: '测试门店', brandId: 1, brandName: '茹菓' }])
    if (pathname.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })

  await seed(page)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)
  const missingCard = page.locator('.unlinked-evidence-list .inspection-evidence-item').filter({ hasText: '微信历史图片.jpg' })
  await expect(missingCard.getByText('原图未入库，需补传')).toBeVisible()
  await missingCard.getByRole('button', { name: '补传并关联证据' }).click()

  const dialog = page.getByRole('dialog', { name: '补传并关联证据' })
  await expect(dialog.getByText('从微信重新选择原图补传')).toBeVisible()
  await expect(dialog.locator('.evidence-candidate-list')).toHaveCount(0)
  await dialog.locator('input[type="file"]').setInputFiles({ name: '微信重新选择原图.png', mimeType: 'image/png', buffer: tinyPng })
  await dialog.getByRole('checkbox', { name: /H-02 历史条款/ }).check()
  await dialog.getByRole('button', { name: '补传并关联', exact: true }).click()
  await expect.poll(() => uploadBody).toContain('clauseIds')
  await expect.poll(() => uploadBody).toContain('sourcePhotoIndex')
  expect(uploadBody).toMatch(/sourcePhotoIndex[\s\S]*\r\n0\r\n/)
  await expect(page.getByText('原图已补传并关联到所选历史条款；历史评分和整改状态未改动。')).toBeVisible()
  await expect(page.getByRole('button', { name: '预览 微信重新选择原图.png' })).toBeVisible()
})

test('详情精确说明原图不存在或无查看权限，店长不显示历史补证据操作', async ({ page }) => {
  const recordId = 'INS-HISTORICAL-ACCESS'
  const currentRecord = () => baseRecord(
    recordId,
    JSON.stringify([
      { attachmentId: 703, fileName: '原图已删除.jpg', contentType: 'image/jpeg' },
      { attachmentId: 704, fileName: '跨店图片.jpg', contentType: 'image/jpeg' },
    ]),
    [clause(43, 'H-03', [703]), clause(44, 'H-04', [704])],
  )
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === '/api/auth/me') return json(route, readOnlyStoreManager)
    if (pathname === '/api/inspections' || pathname === `/api/inspections/${recordId}`) return json(route, pathname === '/api/inspections' ? [currentRecord()] : currentRecord())
    if (pathname === '/api/storage/attachments/703') return apiError(route, 404, 'ATTACHMENT_NOT_FOUND', 'not found')
    if (pathname === '/api/storage/attachments/704') return apiError(route, 403, 'FORBIDDEN', 'forbidden')
    if (pathname === '/api/inspection/standards') return json(route, { id: 1, version: '测试标准', fullScore: 200, passScore: 180, valid: true, saveAllowed: true, items: [] })
    if (pathname === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', name: '测试门店', brandId: 1, brandName: '茹菓' }])
    if (pathname.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })

  await seed(page, readOnlyStoreManager)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)
  await expect(page.getByText('图片不存在')).toBeVisible()
  await expect(page.getByText('无查看权限')).toBeVisible()
  await expect(page.getByRole('button', { name: '补传并关联证据' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '关联已有证据' })).toHaveCount(0)
})

test('候选证据空列表为中性提示，读取失败按状态说明且底部按钮始终横排', async ({ page }, testInfo) => {
  const recordId = 'INS-HISTORICAL-CANDIDATE-STATES'
  let candidateStatus: 200 | 401 | 403 | 404 | 500 | 'NETWORK' = 200
  const currentRecord = () => baseRecord(
    recordId,
    JSON.stringify([{ fileName: '微信历史图片.jpg', contentType: 'image/jpeg' }]),
    [clause(45, 'H-05')],
  )

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return json(route, boss)
    if (pathname === '/api/inspections' && request.method() === 'GET') return json(route, [currentRecord()])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return json(route, currentRecord())
    if (pathname === `/api/inspections/${recordId}/evidence/attachments` && request.method() === 'GET') {
      if (candidateStatus === 'NETWORK') return route.abort('failed')
      if (candidateStatus === 200) return json(route, { recordId, storeId: 'STORE-1', candidates: [] })
      return apiError(route, candidateStatus, `TEST_${candidateStatus}`, '测试候选证据接口失败')
    }
    if (pathname === '/api/inspection/standards') return json(route, { id: 1, version: '测试标准', fullScore: 200, passScore: 180, valid: true, saveAllowed: true, items: [] })
    if (pathname === '/api/inspections/service-health') return json(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return json(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', name: '测试门店', brandId: 1, brandName: '茹菓' }])
    if (pathname.startsWith('/api/supervisor/todos')) return json(route, { items: [] })
    return json(route, [])
  })

  const openDialog = async () => {
    await page.locator('.inspection-detail-actions').getByRole('button', { name: '补传并关联证据' }).click()
    return page.getByRole('dialog', { name: '补传并关联证据' })
  }

  await seed(page)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)

  let dialog = await openDialog()
  await dialog.getByRole('button', { name: '关联已有证据' }).click()
  await expect(dialog.getByText('暂无可关联的已入库图片，请从微信重新选择原图补传。')).toBeVisible()
  await expect(dialog.locator('.dialog-error')).toHaveCount(0)
  await expect(dialog.locator('footer > div')).toHaveCSS('display', 'grid')
  const cancel = dialog.getByRole('button', { name: '取消', exact: true })
  await expect(cancel).toHaveCSS('writing-mode', 'horizontal-tb')
  await expect(cancel).toHaveCSS('white-space', 'nowrap')
  await expect(cancel).toHaveCSS('height', '48px')
  await cancel.click()

  candidateStatus = 403
  dialog = await openDialog()
  await expect(dialog.getByRole('alert')).toHaveText('你无权查看该巡检记录的历史证据。重试')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  candidateStatus = 404
  dialog = await openDialog()
  await expect(dialog.getByRole('alert')).toHaveText('历史证据服务尚未完成部署，请联系管理员。重试')
  await expect(dialog.getByRole('button', { name: '重试' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('historical-evidence-404-desktop.png') })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  candidateStatus = 500
  dialog = await openDialog()
  await expect(dialog.getByRole('alert')).toHaveText('读取失败，请稍后重试。重试')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  candidateStatus = 'NETWORK'
  dialog = await openDialog()
  await expect(dialog.getByRole('alert')).toHaveText('读取失败，请稍后重试。重试')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  candidateStatus = 200
  await page.setViewportSize({ width: 390, height: 844 })
  dialog = await openDialog()
  await expect(dialog.locator('footer > div')).toHaveCSS('display', 'grid')
  await expect(dialog.getByRole('button', { name: '取消', exact: true })).toHaveCSS('writing-mode', 'horizontal-tb')
  await expect(dialog.getByRole('button', { name: '取消', exact: true })).toHaveText('取消')
  const mobileCancelBox = await dialog.getByRole('button', { name: '取消', exact: true }).boundingBox()
  const mobileSubmitBox = await dialog.getByRole('button', { name: '补传并关联', exact: true }).boundingBox()
  expect(mobileCancelBox).not.toBeNull()
  expect(mobileSubmitBox).not.toBeNull()
  expect(Math.abs((mobileCancelBox?.width || 0) - (mobileSubmitBox?.width || 0))).toBeLessThanOrEqual(1)
  await page.screenshot({ path: testInfo.outputPath('historical-evidence-empty-mobile.png') })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()

  candidateStatus = 401
  await page.locator('.inspection-detail-actions').getByRole('button', { name: '补传并关联证据' }).click()
  await expect(page).toHaveURL(/\/login\?/)
  await expect(page.getByText('登录已失效，请重新登录后重试。')).toBeVisible()
})
