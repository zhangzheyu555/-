import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { seedAuth } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const assistantUser = {
  id: 802,
  tenantId: 1,
  tenantName: '经营助手超时测试租户',
  displayName: '经营助手超时测试老板',
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

function assistantStatus() {
  return {
    enabled: true,
    configured: true,
    provider: 'DeepSeek',
    model: 'approved-model',
    baseUrlHost: 'model.example.test',
    timeout: 30,
    lastSuccessAt: '2026-07-14T00:00:00Z',
    lastErrorCode: null,
  }
}

function localAnswer(question: string) {
  return {
    question,
    selectedMode: 'LOCAL',
    selectionReason: '自动模式识别为金额或指标事实查询',
    localData: {
      summary: '本月真实经营数据已查询完成。',
      metrics: [{ key: 'revenue', label: '营业额', value: 68967, unit: 'CNY', displayValue: '¥68,967', changeRate: null, comparison: '' }],
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
    fallbackUsed: false,
    error: null,
  }
}

function aiAnswer(question: string) {
  const response = localAnswer(question)
  return {
    ...response,
    selectedMode: 'AI',
    selectionReason: '用户手动选择AI分析，必须调用真实模型',
    aiAnalysis: {
      available: true,
      provider: 'DeepSeek',
      model: 'approved-model',
      requestId: 'test-request',
      latencyMs: 16_000,
      summary: '成本风险需要持续跟进。',
      findings: ['成本率较上期上升。'],
      risks: [{ title: '成本风险', evidence: '成本率上升', severity: 'MEDIUM' }],
      possibleCauses: [{ cause: '采购价格波动', confidence: 'MEDIUM', basis: '成本率变化' }],
      actions: [{ action: '核对采购单价', ownerRole: 'FINANCE', deadline: '本周', expectedImpact: '控制成本', verificationMetric: '成本率' }],
      confidence: 'MEDIUM',
      limitations: [],
    },
  }
}

async function prepareAssistantPage(page: Page) {
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(assistantUser)))
  await page.route('**/api/stores', (route) => route.fulfill(ok([
    { id: 'store-1', code: 'S001', name: '测试门店', brandId: 1, brandName: '测试品牌', status: 'ACTIVE' },
  ])))
  await page.route('**/api/finance/months', (route) => route.fulfill(ok(['2026-07'])))
  await page.route('**/api/finance/entries**', (route) => route.fulfill(ok([])))
  await page.route('**/api/assistant/status', (route) => route.fulfill(ok(assistantStatus())))
  await seedAuth(page, { token: 'e2e-assistant-timeout-token', user: assistantUser })
}

test.describe('store assistant chat timeout boundary', () => {
  test('keeps the shared 15 second default and scopes the 90 second override to assistant chat', () => {
    const httpSource = readFileSync(resolve(process.cwd(), 'src/api/http.ts'), 'utf8')
    const assistantSource = readFileSync(resolve(process.cwd(), 'src/api/assistant.ts'), 'utf8')
    const importDrawerSource = readFileSync(resolve(process.cwd(), 'src/components/finance/ProfitImportDrawer.vue'), 'utf8')

    expect(httpSource).toMatch(/timeout:\s*15000/)
    expect(httpSource).toContain("'REQUEST_TIMEOUT'")
    expect(httpSource).toContain("'ETIMEDOUT'")
    expect(httpSource).not.toContain('文件解析超时')
    expect(assistantSource).toMatch(/ASSISTANT_CHAT_TIMEOUT_MS\s*=\s*90_000/)
    expect(assistantSource).toMatch(/timeout:\s*ASSISTANT_CHAT_TIMEOUT_MS/)
    expect(importDrawerSource).toContain('文件解析超时，请重试。已选择的文件仍然保留。')
  })

  test('routes an AUTO fact question to LOCAL before it can call the model', async ({ page }) => {
    await prepareAssistantPage(page)
    const requestModes: string[] = []
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string; mode: string }
      requestModes.push(body.mode)
      await route.fulfill(ok(localAnswer(body.message)))
    })

    await page.goto('/assistant')
    await page.getByLabel('经营问题').fill('7月营业额、成本和净利润分别是多少？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByText('本月真实经营数据已查询完成。')).toBeVisible()
    expect(requestModes).toEqual(['LOCAL'])
    await expect(page.getByText('AI经营分析')).toHaveCount(0)
  })

  test('allows an assistant response that takes longer than the shared 15 second timeout', async ({ page }) => {
    test.setTimeout(45_000)
    await prepareAssistantPage(page)
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string; mode: string }
      expect(body.mode).toBe('AI')
      await new Promise<void>((resolve) => setTimeout(resolve, 16_000))
      await route.fulfill(ok(aiAnswer(body.message)))
    })

    await page.goto('/assistant')
    await page.getByRole('button', { name: 'AI分析', exact: true }).click()
    await page.getByLabel('经营问题').fill('7月成本的主要风险是什么？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByText('AI经营分析')).toBeVisible({ timeout: 25_000 })
    await expect(page.getByText('成本风险需要持续跟进。')).toBeVisible()
  })

  test('shows the operating-assistant timeout guidance without file-import wording', async ({ page }) => {
    await prepareAssistantPage(page)
    await page.route('**/api/assistant/chat', (route) => route.fulfill({
      status: 408,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, code: 'REQUEST_TIMEOUT', message: '请求超时，请稍后重试' }),
    }))

    await page.goto('/assistant')
    await page.getByRole('button', { name: 'AI分析', exact: true }).click()
    await page.getByLabel('经营问题').fill('7月成本的主要风险是什么？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByText('AI分析耗时较长，请稍后重试，或先使用‘查数据’获取经营事实。')).toBeVisible()
    await expect(page.getByText(/文件解析/)).toHaveCount(0)
  })
})
