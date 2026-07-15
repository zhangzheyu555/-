import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const mobileProjects = new Set(['iphone-390', 'android-412', 'ipad-768'])

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

const tinyPng = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL13wAAAABJRU5ErkJggg==', 'base64')

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: status < 400, data }),
  })
}

async function seed(page: Page) {
  await page.goto('/login')
  await page.evaluate((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-MOBILE-HISTORICAL-UPLOAD-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, boss)
}

test('移动设备：历史巡检缺失原图可补传并关联，控件可触达且页面不横向溢出', async ({ page }, testInfo) => {
  test.skip(!mobileProjects.has(testInfo.project.name), '仅在已配置的移动设备浏览器项目中执行')

  const recordId = 'INS-MOBILE-HISTORICAL-UPLOAD'
  let uploaded = false
  let uploadBody = ''
  const record = () => ({
    id: recordId,
    storeId: 'STORE-1',
    storeName: '测试门店',
    brand: '茹菓',
    inspectionDate: '2026-07-15',
    inspector: '移动端督导',
    fullScore: 200,
    score: 196,
    maxScore: 200,
    passScore: 180,
    passed: true,
    resultCode: 'PASSED',
    standardVersion: '2026.07-R1',
    deductionsJson: '[]',
    redlinesJson: '[]',
    photosJson: JSON.stringify(uploaded
      ? [
          { attachmentId: 702, fileName: '微信重新选择原图.png', contentType: 'image/png' },
          { attachmentId: 703, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg' },
        ]
      : [
          { attachmentId: 701, fileName: '微信历史图片.jpg', contentType: 'image/jpeg' },
          { attachmentId: 703, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg' },
        ]),
    itemResults: [{
      snapshotId: 42,
      standardItemId: 42,
      code: 'H-02',
      title: '历史卫生条款',
      categoryCode: 'HYGIENE',
      categoryName: '卫生标准',
      standardScore: 4,
      actualScore: 0,
      deductionScore: 4,
      deductionReason: '历史卫生扣分',
      riskLevel: 'YELLOW',
      issueFound: true,
      redLineHit: false,
      photoAttachmentIds: uploaded ? [702] : [],
    }],
  })

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return json(route, boss)
    if (pathname === '/api/inspections' && request.method() === 'GET') return json(route, [record()])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return json(route, record())
    if (pathname === `/api/inspections/${recordId}/evidence/attachments` && request.method() === 'GET') {
      return json(route, {
        recordId,
        storeId: 'STORE-1',
        candidates: uploaded
          ? [
              { attachmentId: 702, photoIndex: 0, fileName: '微信重新选择原图.png', contentType: 'image/png', status: 'LINKED', linkedClauseIds: [42] },
              { attachmentId: 703, photoIndex: 1, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg', status: 'UNLINKED', linkedClauseIds: [] },
            ]
          : [
              { attachmentId: 701, photoIndex: 0, fileName: '微信历史图片.jpg', contentType: 'image/jpeg', status: 'ORIGINAL_NOT_STORED', linkedClauseIds: [] },
              { attachmentId: 703, photoIndex: 1, fileName: '另一张可关联图片.jpg', contentType: 'image/jpeg', status: 'UNLINKED', linkedClauseIds: [] },
            ],
      })
    }
    if (pathname === `/api/inspections/${recordId}/evidence/upload` && request.method() === 'POST') {
      uploadBody = request.postData() || ''
      uploaded = true
      return json(route, { recordId, attachmentIds: [702], clauseIds: [42], action: 'SUPPLEMENT', record: record() })
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
  await expect(page.locator('.unlinked-evidence-list').getByText('原图未入库，需补传')).toBeVisible()
  await page.locator('.unlinked-evidence-list').getByRole('button', { name: '补传并关联证据' }).click()

  const dialog = page.getByRole('dialog', { name: '补传并关联证据' })
  const uploadInput = dialog.locator('input[type="file"]')
  const uploadButton = dialog.locator('.upload-original-button')
  const closeButton = dialog.getByTestId('historical-evidence-close')
  const cancelButton = dialog.getByRole('button', { name: '取消', exact: true })
  const submitButton = dialog.getByRole('button', { name: '补传并关联', exact: true })

  await expect(dialog.getByText('从微信重新选择原图补传')).toBeVisible()
  await expect(uploadInput).toHaveAttribute('accept', 'image/*')
  await expect(uploadInput).toHaveAttribute('capture', 'environment')
  for (const [name, control] of [
    ['关闭补传弹窗', closeButton],
    ['选择图片原图', uploadButton],
    ['取消', cancelButton],
    ['补传并关联', submitButton],
  ] as const) {
    const box = await control.boundingBox()
    expect(box?.height, `${name} 的移动端触控高度`).toBeGreaterThanOrEqual(44)
  }
  const modeButtons = dialog.locator('.evidence-mode-tabs button')
  await expect(modeButtons).toHaveCount(2)
  for (let index = 0; index < await modeButtons.count(); index += 1) {
    const box = await modeButtons.nth(index).boundingBox()
    expect(box?.height, '历史证据处理方式的移动端触控高度').toBeGreaterThanOrEqual(44)
  }
  await expectNoWholePageOverflow(page, 'historical evidence upload dialog on mobile')

  await uploadInput.setInputFiles({ name: '微信重新选择原图.png', mimeType: 'image/png', buffer: tinyPng })
  await dialog.getByRole('checkbox', { name: /H-02.*历史卫生条款/ }).check()
  await expect(submitButton).toBeEnabled()
  await expectNoWholePageOverflow(page, 'selected historical evidence upload on mobile')
  await submitButton.click()

  await expect.poll(() => uploadBody).toContain('historicalSnapshotIds')
  await expect.poll(() => uploadBody).toContain('sourcePhotoIndex')
  expect(uploadBody).toMatch(/sourcePhotoIndex[\s\S]*\r\n0\r\n/)
  await expect(page.getByText('原图已补传并关联到所选历史条款；历史评分和整改状态未改动。')).toBeVisible()
  await expect(page.getByRole('button', { name: '预览 微信重新选择原图.png' })).toBeVisible()
  await expectNoWholePageOverflow(page, 'saved historical evidence upload on mobile')
})
