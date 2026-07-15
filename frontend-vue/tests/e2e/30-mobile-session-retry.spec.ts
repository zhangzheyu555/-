import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const recordId = 'INS-MOBILE-SESSION-RETRY'

const bossSession = {
  id: 9301,
  tenantId: 1,
  tenantName: 'TEST 租户',
  displayName: 'Android 测试老板',
  role: 'BOSS',
  roleLabel: '老板',
  storeScope: ['all'],
  permissions: [],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    INSPECTION: { mode: 'ALL', storeIds: [] },
  },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const record = {
  id: recordId,
  storeId: 'STORE-RETRY',
  storeName: 'Android 测试门店',
  brand: '茹菓',
  inspectionDate: '2026-07-15',
  inspector: '测试督导',
  fullScore: 200,
  score: 196,
  maxScore: 200,
  passScore: 180,
  passed: true,
  resultCode: 'PASSED',
  photosJson: JSON.stringify([{ fileName: '微信历史图片.jpg', contentType: 'image/png' }]),
  itemResults: [{
    snapshotId: 841,
    standardItemId: 841,
    code: 'H-RETRY',
    dimension: '卫生标准',
    categoryCode: 'HYGIENE',
    categoryName: '卫生',
    title: '历史卫生条款',
    standardScore: 4,
    actualScore: 0,
    deductionScore: 4,
    deductionReason: '历史现场问题',
    riskLevel: 'YELLOW',
    issueFound: true,
    photoAttachmentIds: [],
  }],
}

function ok(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: true, data }),
  })
}

function failure(route: Route, status: number, code: string, message: string) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: false, code, message, data: null }),
  })
}

function requireAndroid(testInfo: TestInfo) {
  test.skip(testInfo.project.name !== 'android-412', '本用例仅在 Android 412 设备项目中运行')
}

async function seedSession(page: Page) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-STALE-ANDROID-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, bossSession)
}

async function expectRecordDetail(page: Page) {
  await expect(page.getByRole('heading', { name: 'Android 测试门店', exact: true })).toBeVisible()
  await expect(page.locator('.inspection-detail-card')).toBeVisible()
}

test('Android 会话失效会说明原因、保留原路径并允许重新登录', async ({ page }, testInfo) => {
  requireAndroid(testInfo)
  let expireNextCandidateRequest = false

  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/login' && request.method() === 'POST') {
      return ok(route, { token: 'TEST-REAUTH-ANDROID-TOKEN', user: bossSession })
    }
    if (pathname === '/api/auth/me') return ok(route, bossSession)
    if (pathname === '/api/inspections' && request.method() === 'GET') return ok(route, [record])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return ok(route, record)
    if (pathname === `/api/inspections/${recordId}/evidence/attachments` && request.method() === 'GET') {
      if (expireNextCandidateRequest) {
        expireNextCandidateRequest = false
        return failure(route, 401, 'UNAUTHORIZED', '登录已失效')
      }
      return ok(route, {
        recordId,
        storeId: record.storeId,
        candidates: [{ photoIndex: 0, fileName: '微信历史图片.jpg', contentType: 'image/png', status: 'ORIGINAL_NOT_STORED', linkedClauseIds: [] }],
      })
    }
    if (pathname === '/api/inspection/standards') return ok(route, { id: 1, version: '测试标准', fullScore: 200, passScore: 180, items: [] })
    if (pathname === '/api/inspections/service-health') return ok(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return ok(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return ok(route, [{ id: record.storeId, name: record.storeName, brandId: 1, brandName: '茹菓' }])
    if (pathname.startsWith('/api/supervisor/todos')) return ok(route, { items: [] })
    return ok(route, [])
  })

  await seedSession(page)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)
  await expectRecordDetail(page)

  expireNextCandidateRequest = true
  await page.locator('.inspection-detail-actions').getByRole('button', { name: '补传并关联证据' }).click()
  await expect(page).toHaveURL(new RegExp(`/login\\?redirect=.*${recordId}.*reason=SESSION_EXPIRED`))
  await expect(page.getByText('登录已失效，请重新登录后重试。')).toBeVisible()
  await expectNoWholePageOverflow(page, 'Android 会话失效重新登录页')

  await page.getByLabel('账号', { exact: true }).fill('android-boss')
  await page.getByLabel('密码', { exact: true }).fill('not-a-real-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()

  await expect(page).toHaveURL(new RegExp(`/operations/inspection/records\\?recordId=${recordId}`))
  await expectRecordDetail(page)
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ai_profit_vue_token'))).toBe('TEST-REAUTH-ANDROID-TOKEN')
})

test('Android 原图补传遇到弱网失败后保留选择并可再次提交', async ({ page }, testInfo) => {
  requireAndroid(testInfo)
  let uploadAttempts = 0

  await page.route((url) => url.pathname.startsWith('/api/'), (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return ok(route, bossSession)
    if (pathname === '/api/inspections' && request.method() === 'GET') return ok(route, [record])
    if (pathname === `/api/inspections/${recordId}` && request.method() === 'GET') return ok(route, record)
    if (pathname === `/api/inspections/${recordId}/evidence/attachments` && request.method() === 'GET') return ok(route, {
      recordId,
      storeId: record.storeId,
      candidates: [{ photoIndex: 0, fileName: '微信历史图片.jpg', contentType: 'image/png', status: 'ORIGINAL_NOT_STORED', linkedClauseIds: [] }],
    })
    if (pathname === `/api/inspections/${recordId}/evidence/upload` && request.method() === 'POST') {
      uploadAttempts += 1
      if (uploadAttempts === 1) return route.abort('failed')
      return ok(route, { recordId, attachmentIds: [991], clauseIds: [], action: 'SUPPLEMENT', record })
    }
    if (pathname === '/api/inspection/standards') return ok(route, { id: 1, version: '测试标准', fullScore: 200, passScore: 180, items: [] })
    if (pathname === '/api/inspections/service-health') return ok(route, { status: 'UP', configured: true, message: '识别服务正常' })
    if (pathname === '/api/brands') return ok(route, [{ id: 1, name: '茹菓' }])
    if (pathname === '/api/stores') return ok(route, [{ id: record.storeId, name: record.storeName, brandId: 1, brandName: '茹菓' }])
    if (pathname.startsWith('/api/supervisor/todos')) return ok(route, { items: [] })
    return ok(route, [])
  })

  await seedSession(page)
  await page.goto(`/operations/inspection/records?recordId=${recordId}`)
  await expectRecordDetail(page)
  await page.locator('.inspection-detail-actions').getByRole('button', { name: '补传并关联证据' }).click()

  const dialog = page.getByRole('dialog', { name: '补传并关联证据' })
  await expect(dialog.getByText('从微信重新选择原图补传')).toBeVisible()
  const fileInput = dialog.locator('input[type="file"]')
  const uploadButton = dialog.locator('.upload-original-button')
  const uploadButtonBox = await uploadButton.boundingBox()
  expect(uploadButtonBox?.height, 'Android 选择原图按钮的点击高度').toBeGreaterThanOrEqual(44)
  await fileInput.setInputFiles({
    name: '微信重新选择原图.png',
    mimeType: 'image/png',
    buffer: Buffer.from('android-network-retry-image'),
  })
  await dialog.getByRole('checkbox', { name: /H-RETRY/ }).check()

  const submit = dialog.getByRole('button', { name: '补传并关联', exact: true })
  const submitBox = await submit.boundingBox()
  expect(submitBox?.height, 'Android 补传确认按钮的点击高度').toBeGreaterThanOrEqual(44)
  await submit.click()

  await expect(dialog.getByRole('alert')).toHaveText('原图补传没有保存成功，请稍后重试。')
  await expect(dialog.getByText('微信重新选择原图.png', { exact: true })).toBeVisible()
  await expect(submit).toBeEnabled()
  await expectNoWholePageOverflow(page, 'Android 弱网补传重试弹窗')

  await submit.click()
  await expect.poll(() => uploadAttempts).toBe(2)
  await expect(dialog).toHaveCount(0)
  await expect(page.getByText('原图已补传并关联到所选历史条款；历史评分和整改状态未改动。')).toBeVisible()
})
