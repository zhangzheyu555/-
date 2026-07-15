import { expect, test, type Page } from '@playwright/test'
import { seedAuth } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const assistantUser = {
  id: 801,
  tenantId: 1,
  tenantName: '经营助手测试租户',
  displayName: '经营助手测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['assistant.use'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

async function prepareAssistantPage(page: Page) {
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(assistantUser)))
  await page.route('**/api/stores', (route) => route.fulfill(ok([
    { id: 'store-1', code: 'S001', name: '测试门店', brandId: 1, brandName: '测试品牌', status: 'ACTIVE' },
  ])))
  await page.route('**/api/finance/months', (route) => route.fulfill(ok(['2026-07'])))
  await page.route('**/api/finance/entries**', (route) => route.fulfill(ok([])))
  await seedAuth(page, { token: 'e2e-assistant-status-token', user: assistantUser })
}

function assistantStatus(
  configured: boolean,
  state: 'NOT_CONFIGURED' | 'CONFIGURED' | 'READY' | 'RESPONSE_REJECTED' | 'UPSTREAM_ERROR' = configured ? 'CONFIGURED' : 'NOT_CONFIGURED',
) {
  return {
    enabled: true,
    configured,
    state,
    provider: 'DeepSeek',
    model: configured ? 'approved-model' : '',
    baseUrlHost: '',
    timeout: 30,
    lastSuccessAt: configured ? '2026-07-14T00:00:00Z' : null,
    lastErrorCode: configured ? null : 'NOT_CONFIGURED',
  }
}

function localFallbackAnswer() {
  return {
    question: '本月净利润怎么样？',
    selectedMode: 'LOCAL',
    selectionReason: 'AI服务未配置，仅查询真实经营数据。',
    localData: {
      summary: '本月真实经营数据已查询完成。',
      metrics: [{ key: 'net-profit', label: '净利润', value: 12800, unit: 'CNY', displayValue: '¥12,800', changeRate: null, comparison: '' }],
      dataPeriod: '2026-07',
      dataScope: '测试门店',
      source: '经营数据库',
      dataVersion: 'test-v1',
      calculationVersion: 'test-v1',
      updatedAt: '2026-07-14T00:00:00Z',
    },
    aiAnalysis: {
      available: false,
      provider: '',
      model: '',
      requestId: '',
      latencyMs: 0,
      summary: '',
      findings: [],
      risks: [],
      possibleCauses: [],
      actions: [],
      confidence: '',
      limitations: [],
    },
    fallbackUsed: true,
    error: { code: 'DEEPSEEK_NOT_CONFIGURED', message: 'AI服务未配置' },
  }
}

test.describe('store assistant status refresh', () => {
  test('rechecks a known unconfigured service once after the safe delay', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-07-14T00:00:00Z') })
    await prepareAssistantPage(page)
    let deployed = false
    let statusRequests = 0
    await page.route('**/api/assistant/status', async (route) => {
      statusRequests += 1
      await route.fulfill(ok(assistantStatus(deployed)))
    })

    await page.goto('/assistant')
    const serviceStatus = page.getByTestId('assistant-service-status')
    await expect(serviceStatus).toHaveText('AI服务未配置')
    await expect.poll(() => statusRequests).toBe(1)

    deployed = true
    await page.clock.runFor(15_000)

    await expect(serviceStatus).toHaveText('DeepSeek 已配置')
    await expect.poll(() => statusRequests).toBe(2)
  })

  test('refreshes after window focus without duplicating rapid focus events', async ({ page }) => {
    await prepareAssistantPage(page)
    let deployed = false
    let statusRequests = 0
    await page.route('**/api/assistant/status', async (route) => {
      statusRequests += 1
      await route.fulfill(ok(assistantStatus(deployed)))
    })

    await page.goto('/assistant')
    const serviceStatus = page.getByTestId('assistant-service-status')
    await expect(serviceStatus).toHaveText('AI服务未配置')
    const requestsBeforeFocus = statusRequests

    deployed = true
    await page.evaluate(() => {
      window.dispatchEvent(new Event('focus'))
      window.dispatchEvent(new Event('focus'))
      window.dispatchEvent(new Event('focus'))
    })

    await expect(serviceStatus).toHaveText('DeepSeek 已配置')
    await expect.poll(() => statusRequests).toBe(requestsBeforeFocus + 1)
    await page.waitForTimeout(100)
    expect(statusRequests).toBe(requestsBeforeFocus + 1)
  })

  test('cancels the pending unconfigured retry after leaving the assistant page', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-07-14T00:00:00Z') })
    await prepareAssistantPage(page)
    let statusRequests = 0
    await page.route('**/api/assistant/status', async (route) => {
      statusRequests += 1
      await route.fulfill(ok(assistantStatus(false)))
    })

    await page.goto('/assistant')
    await expect(page.getByTestId('assistant-service-status')).toHaveText('AI服务未配置')
    await expect.poll(() => statusRequests).toBe(1)

    await page.goto('/login')
    await expect(page.locator('.store-assistant-page')).toHaveCount(0)
    await page.clock.runFor(15_000)
    expect(statusRequests).toBe(1)
  })

  test('keeps the database-only fallback available while AI is unconfigured', async ({ page }) => {
    await prepareAssistantPage(page)
    await page.route('**/api/assistant/status', (route) => route.fulfill(ok(assistantStatus(false))))
    await page.route('**/api/assistant/chat', (route) => route.fulfill(ok(localFallbackAnswer())))

    await page.goto('/assistant')
    await expect(page.getByTestId('assistant-service-status')).toHaveText('AI服务未配置')

    await page.getByLabel('经营问题').fill('本月净利润怎么样？')
    await page.getByRole('button', { name: '发送' }).click()

    await expect(page.getByText('经营数据', { exact: true })).toBeVisible()
    await expect(page.getByText('本月真实经营数据已查询完成。')).toBeVisible()
    await expect(page.getByText('AI分析服务尚未配置，本地经营数据仍可正常查询。')).toBeVisible()
  })

  test('separates configured, verified-ready, and rejected-response states', async ({ page }) => {
    await prepareAssistantPage(page)
    let state: 'CONFIGURED' | 'READY' | 'RESPONSE_REJECTED' = 'CONFIGURED'
    await page.route('**/api/assistant/status', (route) => route.fulfill(ok(assistantStatus(true, state))))

    await page.goto('/assistant')
    const serviceStatus = page.getByTestId('assistant-service-status')
    await expect(serviceStatus).toHaveText('DeepSeek 已配置')
    await expect(serviceStatus).not.toHaveText('分析服务正常')

    state = 'READY'
    await page.reload()
    await expect(serviceStatus).toHaveText('分析服务正常')

    state = 'RESPONSE_REJECTED'
    await page.reload()
    await expect(serviceStatus).toHaveText('模型格式异常')
    await expect(serviceStatus).not.toHaveText('分析服务正常')
  })
})
