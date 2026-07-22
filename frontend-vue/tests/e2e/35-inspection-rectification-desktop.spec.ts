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
        status: 'PENDING_REVIEW', statusLabel: '待运营复核', evidenceAttachmentIds: [601],
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
  await page.getByRole('button', { name: '提交整改复核' }).click()
  await expect(page.getByText('整改已提交，等待运营复核。')).toBeVisible()
  await expect(page.locator('.rectification-detail .status-chip')).toHaveText('待运营复核')
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

test('supervisor approves one pending rectification and removes it from the review queue', async ({ page }) => {
  const task: Task = {
    ...pendingSubmission(),
    status: 'PENDING_REVIEW',
    statusLabel: '待运营复核',
    evidenceAttachmentIds: [601],
    managerNote: '已完成清洁并上传现场证据。',
  }
  const calls: Array<{ authorization?: string; body?: Record<string, unknown> }> = []
  const consoleErrors = collectConsoleErrors(page)
  await seed(page, supervisor)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') return route.fulfill(ok(supervisor))
    if (path === '/api/inspections/rectifications/reviews') return route.fulfill(ok([task]))
    if (path === `/api/inspections/${task.recordId}/rectification/review`) {
      calls.push({ authorization: request.headers().authorization, body: request.postDataJSON() as Record<string, unknown> })
      return route.fulfill(ok({ ...task, status: 'APPROVED', statusLabel: '复核通过', reviewNote: '现场证据与整改说明已核对。' }))
    }
    return route.fulfill(ok([]))
  })

  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/operations/inspection/reviews')
  await expect(page.getByRole('heading', { name: '整改复核' })).toBeVisible()
  await page.getByLabel('复核备注').fill('现场证据与整改说明已核对。')
  await page.getByRole('button', { name: '通过复核' }).click()
  await expect(page.getByText('整改已复核通过，处理记录已写入操作日志。')).toBeVisible()
  await expect(page.getByText('当前没有待复核的巡检整改。')).toBeVisible()
  expect(calls).toEqual([{
    authorization: 'Bearer INSPECTION-RECTIFICATION-E2E',
    body: { decision: 'APPROVED', note: '现场证据与整改说明已核对。' },
  }])
  await expectNoWholePageOverflow(page, '1280px 巡检整改复核页')
  expect(consoleErrors).toEqual([])
})
