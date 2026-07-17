import { expect, test, type Page } from '@playwright/test'
import { seedAuth } from './auth.setup'
import { operatingSnapshot, withSnapshotLocalData } from './assistant-snapshot.fixture'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const assistantUser = {
  id: 803,
  tenantId: 1,
  tenantName: '经营助手质量状态测试租户',
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
const snapshot = operatingSnapshot({ expectedStoreCount: 2, reportedStoreCount: 1, missingStoreNames: ['待补门店'] })

type AssistantState = 'NOT_CONFIGURED' | 'CONFIGURED' | 'READY' | 'RESPONSE_REJECTED' | 'UPSTREAM_ERROR'

function assistantStatus(state: AssistantState, lastErrorCode: string | null = null) {
  const configured = state !== 'NOT_CONFIGURED'
  return {
    enabled: true,
    configured,
    state,
    provider: 'DeepSeek',
    model: configured ? 'approved-model' : '',
    baseUrlHost: '',
    timeout: 30,
    lastSuccessAt: state === 'READY' ? '2026-07-14T00:00:00Z' : null,
    lastErrorCode: state === 'RESPONSE_REJECTED' ? lastErrorCode || 'SCHEMA_INVALID' : null,
  }
}

function localData(aiInvocation = 'NOT_REQUESTED') {
  return withSnapshotLocalData(snapshot, {
    metrics: [{ key: 'sales', label: '实收收入', value: '68967', unit: 'CNY', displayValue: '¥68,967', changeRate: null, comparison: '' }],
    aiInvocation,
  })
}

function dataLimitedAnswer(question: string) {
  return {
    question,
    selectedMode: 'LOCAL',
    selectionReason: '当前经营数据不足，仅返回数据补全建议。',
    localData: {
      ...localData('NOT_CALLED_INSUFFICIENT'),
      insufficientData: {
        kind: 'INSUFFICIENT_DATA',
        verifiedFacts: ['当前范围已有可验证的收入、成本、费用和经营利润汇总。'],
        cannotDetermine: ['当前数据不足以支持经营原因、趋势或门店结论归因。'],
        missingItems: ['businessAsOf', 'dailyOperatingCoverage', 'otherIncomeExpense', 'tax'],
        nextSteps: ['补录本月原材料、包材和损耗成本。', '补齐至少一个可比历史月份的经营数据。'],
        modelInvoked: false,
      },
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

function schemaRejectedAnswer(question: string) {
  return {
    question,
    selectedMode: 'AI',
    selectionReason: '用户请求原因分析。',
    localData: localData('FAILED'),
    aiAnalysis: {
      available: false,
      analysisType: undefined,
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
    error: { code: 'SCHEMA_INVALID', message: '模型返回格式不符合分析协议' },
  }
}

function qualityRejectedAnswer(question: string, code: string) {
  const response = schemaRejectedAnswer(question)
  response.error = { code, message: '内部质量门禁已拒绝该结果' }
  return response
}

async function prepareAssistantPage(page: Page) {
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(assistantUser)))
  await page.route('**/api/stores', (route) => route.fulfill(ok([
    { id: 'store-1', code: 'S001', name: '测试门店', brandId: 1, brandName: '测试品牌', status: 'ACTIVE' },
  ])))
  await page.route('**/api/finance/months', (route) => route.fulfill(ok(['2026-07'])))
  await page.route('**/api/finance/entries**', (route) => route.fulfill(ok([])))
  await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill(ok(snapshot)))
  await seedAuth(page, { token: 'e2e-assistant-quality-token', user: assistantUser })
}

test.describe('store assistant quality state presentation', () => {
  test('presents DATA_LIMITED as actionable data completion instead of a failed analysis', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await prepareAssistantPage(page)
    await page.route('**/api/assistant/status', (route) => route.fulfill(ok(assistantStatus('READY'))))
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string }
      await route.fulfill(ok(dataLimitedAnswer(body.message)))
    })

    await page.goto('/assistant')
    await expect(page.getByTestId('assistant-service-status')).toHaveText('分析服务正常')
    await page.getByRole('button', { name: '深度分析', exact: true }).click()
    await page.getByLabel('经营问题').fill('为什么本月利润看起来很高？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByTestId('assistant-data-limited')).toContainText('经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据')
    await expect(page.getByText('请先补全以下经营数据')).toBeVisible()
    await expect(page.getByText('补录本月原材料、包材和损耗成本。')).toBeVisible()
    await expect(page.getByText('补齐至少一个可比历史月份的经营数据。')).toBeVisible()
    await expect(page.getByText('风险与异常')).toHaveCount(0)
    await expect(page.getByText('可能原因')).toHaveCount(0)
    await expect(page.getByText('AI分析暂时不可用')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  })

  test('refreshes to the rejection state after a schema-invalid response', async ({ page }) => {
    await prepareAssistantPage(page)
    let responseRejected = false
    await page.route('**/api/assistant/status', (route) => route.fulfill(ok(
      assistantStatus(responseRejected ? 'RESPONSE_REJECTED' : 'READY'),
    )))
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string }
      responseRejected = true
      await route.fulfill(ok(schemaRejectedAnswer(body.message)))
    })

    await page.goto('/assistant')
    await expect(page.getByTestId('assistant-service-status')).toHaveText('分析服务正常')
    await page.getByRole('button', { name: '深度分析', exact: true }).click()
    await page.getByLabel('经营问题').fill('为什么本月利润变化？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByText('模型返回格式异常，已自动重试仍未成功，请稍后重试。')).toBeVisible()
    await expect(page.getByTestId('assistant-service-status')).toHaveText('模型格式异常')
    await expect(page.getByTestId('assistant-service-status')).not.toHaveText('分析服务正常')
    await expect(page.getByText('SCHEMA_INVALID')).toHaveCount(0)
  })

  for (const scenario of [
    {
      code: 'DATA_LIMITED_REQUIRED',
      message: '经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据。',
      status: 'DeepSeek 已配置',
    },
    {
      code: 'ANALYSIS_UNKNOWN_NUMERIC',
      message: '模型引用了当前经营数据中没有的金额或比例，系统已拦截该结果，请核对数据后重新分析。',
      status: '分析结果已拦截',
    },
    {
      code: 'ANALYSIS_SNAPSHOT_CONTRADICTION',
      message: '模型结论与当前经营数据不一致，系统已拦截该结果，请核对数据后重新分析。',
      status: '分析结果已拦截',
    },
    {
      code: 'ANALYSIS_ACTION_ROLE_INVALID',
      message: '模型建议的处理角色不符合系统职责范围，系统已拦截该结果，请稍后重新分析。',
      status: '分析结果已拦截',
    },
  ]) {
    test(`uses business copy for ${scenario.code}`, async ({ page }) => {
      await prepareAssistantPage(page)
      let responseRejected = false
      await page.route('**/api/assistant/status', (route) => route.fulfill(ok(
        assistantStatus(
          responseRejected ? 'RESPONSE_REJECTED' : 'READY',
          responseRejected ? scenario.code : null,
        ),
      )))
      await page.route('**/api/assistant/chat', async (route) => {
        const body = route.request().postDataJSON() as { message: string }
        responseRejected = true
        await route.fulfill(ok(qualityRejectedAnswer(body.message, scenario.code)))
      })

      await page.goto('/assistant')
    await page.getByRole('button', { name: '深度分析', exact: true }).click()
      await page.getByLabel('经营问题').fill('为什么本月利润变化？')
      await page.getByRole('button', { name: '发送', exact: true }).click()

      await expect(page.getByText(scenario.message)).toBeVisible()
      await expect(page.getByTestId('assistant-service-status')).toHaveText(scenario.status)
      await expect(page.getByText(scenario.code)).toHaveCount(0)
      await expect(page.getByText('模型结果未通过经营数据校验')).toHaveCount(0)
    })
  }
})
