import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const manager = {
  id: 801,
  tenantId: 1,
  tenantName: 'H2 合成巡检租户',
  displayName: '测试店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeId: 'INS-STORE-A',
  boundStoreId: 'INS-STORE-A',
  boundStoreName: '巡检合成门店',
  storeScope: ['INS-STORE-A'],
  permissions: ['inspection.read', 'todo.transition'],
  dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['INS-STORE-A'] }, INSPECTION: { mode: 'OWN_STORE', storeIds: ['INS-STORE-A'] } },
  defaultWorkspace: '/store',
  permissionVersion: 1,
}

const supervisor = {
  id: 802,
  tenantId: 1,
  tenantName: 'H2 合成巡检租户',
  displayName: '测试督导',
  role: 'SUPERVISOR',
  roleLabel: '督导',
  storeScope: ['INS-STORE-A'],
  permissions: ['inspection.read', 'inspection.manage'],
  dataScopes: { STORE: { mode: 'CUSTOM', storeIds: ['INS-STORE-A'] }, INSPECTION: { mode: 'CUSTOM', storeIds: ['INS-STORE-A'] } },
  defaultWorkspace: '/operations',
  permissionVersion: 1,
}

type Task = {
  recordId: string
  storeId: string
  storeName: string
  inspectionDate: string
  status: string
  statusLabel: string
  requirement: string
  evidenceAttachmentIds: number[]
  managerNote?: string
  reviewNote?: string
  updatedAt: string
}

const pendingSubmission = (): Task => ({
  recordId: 'INS-RECT-DESKTOP-1',
  storeId: 'INS-STORE-A',
  storeName: '巡检合成门店',
  inspectionDate: '2026-07-21',
  status: 'PENDING_SUBMISSION',
  statusLabel: '待整改',
  requirement: '清洁后厨地面并补充现场照片。',
  evidenceAttachmentIds: [],
  updatedAt: '2026-07-21T10:00:00',
})

function ok(data: unknown) {
  return { status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) }
}

async function seed(page: Page, user: Record<string, unknown>) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'INSPECTION-RECTIFICATION-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, user)
}

function collectConsoleErrors(page: Page) {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().includes('[API Error]')) errors.push(message.text())
  })
  return errors
}

test('store manager submits rectification evidence through authenticated APIs without desktop overflow', async ({ page }) => {
  const task = pendingSubmission()
  const calls: Array<{ path: string; authorization?: string; body?: Record<string, unknown> }> = []
  const consoleErrors = collectConsoleErrors(page)
  await seed(page, manager)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(manager))
    if (path === '/api/inspections/rectifications/mine') return route.fulfill(ok([task]))
    if (path === `/api/inspections/${task.recordId}/rectification/evidence`) {
      calls.push({ path, authorization: request.headers().authorization })
      return route.fulfill(ok({ attachmentId: 601, fileName: '整改现场.png', contentType: 'image/png', fileSize: 12 }))
    }
    if (path === `/api/inspections/${task.recordId}/rectification`) {
      calls.push({ path, authorization: request.headers().authorization, body: request.postDataJSON() as Record<string, unknown> })
      Object.assign(task, {
        status: 'PENDING_REVIEW', statusLabel: '待督导复核', evidenceAttachmentIds: [601],
        managerNote: '已完成清洁并上传现场证据。', updatedAt: '2026-07-21T10:05:00',
      })
      return route.fulfill(ok(task))
    }
    return route.fulfill(ok([]))
  })

  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/store/inspection/rectifications')
  await expect(page.getByRole('heading', { name: '巡检整改' })).toBeVisible()
  await page.getByLabel('整改说明').fill('已完成清洁并上传现场证据。')
  await page.locator('input[type="file"]').setInputFiles({ name: '整改现场.png', mimeType: 'image/png', buffer: Buffer.from('synthetic-evidence') })
  await page.getByRole('button', { name: '提交至督导复核' }).click()
  await expect(page.getByRole('alertdialog', { name: '确认提交本次整改？' })).toBeVisible()
  expect(calls).toEqual([])
  await page.getByRole('button', { name: '确认提交' }).click()
  await expect(page.getByText('整改已提交，等待督导复核。')).toBeVisible()
  await expect(page.locator('.rectification-detail .status-chip')).toHaveText('待督导复核')
  expect(calls).toEqual([
    { path: `/api/inspections/${task.recordId}/rectification/evidence`, authorization: 'Bearer INSPECTION-RECTIFICATION-E2E' },
    {
      path: `/api/inspections/${task.recordId}/rectification`,
      authorization: 'Bearer INSPECTION-RECTIFICATION-E2E',
      body: { note: '已完成清洁并上传现场证据。', attachmentIds: [601] },
    },
  ])
  await expectNoWholePageOverflow(page, '1280px 巡检整改页')
  expect(consoleErrors).toEqual([])
})

test('rectification upload retry resumes after the last successful evidence file', async ({ page }) => {
  const task = pendingSubmission()
  let uploadAttempts = 0
  const submitBodies: Record<string, unknown>[] = []
  await seed(page, manager)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(manager))
    if (path === '/api/inspections/rectifications/mine') return route.fulfill(ok([task]))
    if (path === `/api/inspections/${task.recordId}/rectification/evidence`) {
      uploadAttempts += 1
      if (uploadAttempts === 1) {
        return route.fulfill(ok({ attachmentId: 601, fileName: '证据一.png', contentType: 'image/png', fileSize: 12 }))
      }
      if (uploadAttempts === 2) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'UPLOAD_FAILED', message: '第二份证据上传失败，请重试。' }),
        })
      }
      return route.fulfill(ok({ attachmentId: 602, fileName: '证据二.png', contentType: 'image/png', fileSize: 12 }))
    }
    if (path === `/api/inspections/${task.recordId}/rectification`) {
      submitBodies.push(request.postDataJSON() as Record<string, unknown>)
      Object.assign(task, {
        status: 'PENDING_REVIEW',
        statusLabel: '待督导复核',
        evidenceAttachmentIds: [601, 602],
        managerNote: '两处问题均已整改。',
      })
      return route.fulfill(ok(task))
    }
    return route.fulfill(ok([]))
  })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/store/inspection/rectifications')
  await page.getByLabel('整改说明').fill('两处问题均已整改。')
  await page.locator('input[type="file"]').setInputFiles([
    { name: '证据一.png', mimeType: 'image/png', buffer: Buffer.from('evidence-one') },
    { name: '证据二.png', mimeType: 'image/png', buffer: Buffer.from('evidence-two') },
  ])
  await page.getByRole('button', { name: '提交至督导复核' }).click()
  await page.getByRole('button', { name: '确认提交' }).click()

  await expect(page.getByRole('alertdialog', { name: '确认提交本次整改？' })).toContainText('第二份证据上传失败')
  await expect(page.getByText('已上传 1 份现场证据，正在等待提交整改。')).toBeVisible()
  expect(uploadAttempts).toBe(2)
  expect(submitBodies).toEqual([])

  await page.getByRole('button', { name: '确认提交' }).click()
  await expect(page.getByText('整改已提交，等待督导复核。')).toBeVisible()
  expect(uploadAttempts).toBe(3)
  expect(submitBodies).toEqual([{
    note: '两处问题均已整改。',
    attachmentIds: [601, 602],
  }])
  await expectNoWholePageOverflow(page, '390px 巡检整改提交页')
})

test('foreground task response does not replace rectification text entered while the request is in flight', async ({ page }) => {
  const task = pendingSubmission()
  const replacementTask: Task = {
    ...pendingSubmission(),
    recordId: 'INS-RECT-DESKTOP-2',
    requirement: '整理前厅物料并补充现场照片。',
  }
  let mineRequests = 0
  let signalForegroundRequest: (() => void) | undefined
  let releaseForegroundRequest: (() => void) | undefined
  const foregroundRequestStarted = new Promise<void>((resolve) => {
    signalForegroundRequest = resolve
  })
  const foregroundRequestGate = new Promise<void>((resolve) => {
    releaseForegroundRequest = resolve
  })

  await seed(page, manager)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(manager))
    if (path === '/api/inspections/rectifications/mine') {
      mineRequests += 1
      if (mineRequests === 2) {
        signalForegroundRequest?.()
        await foregroundRequestGate
        return route.fulfill(ok([replacementTask]))
      }
      return route.fulfill(ok([task]))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/store/inspection/rectifications')
  const noteInput = page.getByLabel('整改说明')
  await expect(noteInput).toBeVisible()

  await page.evaluate(() => {
    const staleNow = Date.now() + 61_000
    Date.now = () => staleNow
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await foregroundRequestStarted

  await noteInput.fill('请求期间开始填写的整改说明')
  releaseForegroundRequest?.()
  await expect.poll(() => mineRequests).toBe(2)
  await page.waitForTimeout(100)

  await expect(noteInput).toHaveValue('请求期间开始填写的整改说明')
  await expect(page.getByText('清洁后厨地面并补充现场照片。').first()).toBeVisible()
  await expect(page.getByText('整理前厅物料并补充现场照片。')).toHaveCount(0)
})

test('supervisor approves one pending rectification and removes it from the review queue', async ({ page }) => {
  const task: Task = {
    ...pendingSubmission(),
    status: 'PENDING_REVIEW',
    statusLabel: '待督导复核',
    evidenceAttachmentIds: [601],
    managerNote: '已完成清洁并上传现场证据。',
  }
  const calls: Array<{ authorization?: string; body?: Record<string, unknown> }> = []
  let attachmentAuthorization = ''
  const consoleErrors = collectConsoleErrors(page)
  await seed(page, supervisor)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(supervisor))
    if (path === '/api/inspections/rectifications/reviews') return route.fulfill(ok([task]))
    if (path === '/api/storage/attachments/601') {
      attachmentAuthorization = request.headers().authorization || ''
      return route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64'),
      })
    }
    if (path === `/api/inspections/${task.recordId}/rectification/review`) {
      calls.push({ authorization: request.headers().authorization, body: request.postDataJSON() as Record<string, unknown> })
      return route.fulfill(ok({ ...task, status: 'APPROVED', statusLabel: '复核通过', reviewNote: '现场证据与整改说明已核对。' }))
    }
    return route.fulfill(ok([]))
  })

  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/operations/inspection/reviews')
  await expect(page.getByRole('heading', { name: '整改复核' })).toBeVisible()
  await page.getByRole('button', { name: '预览整改现场证据 601' }).click()
  await expect(page.getByRole('dialog', { name: '整改现场证据 601' })).toBeVisible()
  await expect(page.getByAltText('巡检合成门店 整改现场证据')).toBeVisible()
  expect(attachmentAuthorization).toBe('Bearer INSPECTION-RECTIFICATION-E2E')
  await page.getByRole('button', { name: '关闭证据预览' }).click()
  await page.getByLabel('复核备注').fill('现场证据与整改说明已核对。')
  await page.getByRole('button', { name: '通过复核' }).click()
  await expect(page.getByRole('alertdialog', { name: '确认通过本次整改？' })).toBeVisible()
  expect(calls).toEqual([])
  await page.getByRole('button', { name: '确认通过' }).click()
  await expect(page.getByText('整改已复核通过，处理记录已写入操作日志。')).toBeVisible()
  await expect(page.getByText('当前没有待复核的巡检整改。')).toBeVisible()
  expect(calls).toEqual([{
    authorization: 'Bearer INSPECTION-RECTIFICATION-E2E',
    body: { decision: 'APPROVED', note: '现场证据与整改说明已核对。' },
  }])
  await expectNoWholePageOverflow(page, '1280px 巡检整改复核页')
  expect(consoleErrors).toEqual([])
})

test('supervisor cannot approve missing evidence and rejection requires explicit confirmation', async ({ page }) => {
  const task: Task = {
    ...pendingSubmission(),
    status: 'PENDING_REVIEW',
    statusLabel: '待督导复核',
    managerNote: '已处理，但未提交现场证据。',
  }
  const decisions: Record<string, unknown>[] = []
  await seed(page, supervisor)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(supervisor))
    if (path === '/api/inspections/rectifications/reviews') return route.fulfill(ok([task]))
    if (path === `/api/inspections/${task.recordId}/rectification/review`) {
      decisions.push(request.postDataJSON() as Record<string, unknown>)
      return route.fulfill(ok({ ...task, status: 'REJECTED', statusLabel: '已驳回' }))
    }
    return route.fulfill(ok([]))
  })

  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/operations/inspection/reviews')
  const approve = page.getByRole('button', { name: '通过复核' })
  await expect(approve).toBeDisabled()
  await expect(approve).toHaveAttribute('title', '缺少现场证据，不能通过复核')

  await page.getByLabel('复核备注').fill('缺少整改现场证据，请补充后重新提交。')
  await page.getByRole('button', { name: '驳回整改' }).click()
  await expect(page.getByRole('alertdialog', { name: '确认驳回本次整改？' })).toBeVisible()
  expect(decisions).toEqual([])
  await page.getByRole('button', { name: '确认驳回' }).click()
  await expect(page.getByText('整改已驳回，店长将看到驳回原因并可重新提交。')).toBeVisible()
  expect(decisions).toEqual([{
    decision: 'REJECTED',
    note: '缺少整改现场证据，请补充后重新提交。',
  }])
  await expectNoWholePageOverflow(page, '390px 巡检整改复核页')
})

test('review notes are protected when the supervisor opens the original inspection', async ({ page }) => {
  const task: Task = {
    ...pendingSubmission(),
    status: 'PENDING_REVIEW',
    statusLabel: '待督导复核',
    evidenceAttachmentIds: [601],
    managerNote: '已完成现场整改。',
  }
  await seed(page, supervisor)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(supervisor))
    if (path === '/api/inspections/rectifications/reviews') return route.fulfill(ok([task]))
    if (path === '/api/inspection/standards') {
      return route.fulfill(ok({
        id: 1,
        title: '测试标准',
        version: 'E2E',
        fullScore: 200,
        passScore: 180,
        valid: false,
        saveAllowed: false,
        items: [],
      }))
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/operations/inspection/reviews')
  await page.getByLabel('复核备注').fill('这条备注尚未提交')
  await page.getByRole('link', { name: '查看原巡检' }).click()

  const dialog = page.getByRole('alertdialog', { name: '放弃未提交的复核备注？' })
  await expect(dialog).toBeVisible()
  await page.getByRole('button', { name: '继续复核' }).click()
  await expect(page).toHaveURL(/\/operations\/inspection\/reviews$/)
  await expect(page.getByLabel('复核备注')).toHaveValue('这条备注尚未提交')

  await page.getByRole('link', { name: '查看原巡检' }).click()
  await page.getByRole('button', { name: '放弃备注' }).click()
  await expect(page).toHaveURL(/\/operations\/inspection\/records\?recordId=INS-RECT-DESKTOP-1$/)
})
