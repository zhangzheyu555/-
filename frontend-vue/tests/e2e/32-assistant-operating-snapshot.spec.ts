import { expect, test, type Page, type Route } from '@playwright/test'
import { seedAuth } from './auth.setup'
import { operatingSnapshot, withSnapshotLocalData } from './assistant-snapshot.fixture'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const assistantUser = {
  id: 832,
  tenantId: 1,
  tenantName: '助手快照测试租户',
  displayName: '快照测试老板',
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

const mtdSnapshot = operatingSnapshot({
  snapshotId: 'mtd-snapshot-28pct',
  expectedStoreCount: 2,
  reportedStoreCount: 1,
  missingStoreNames: ['待补门店'],
})

function status() {
  return {
    enabled: true,
    configured: true,
    state: 'READY',
    provider: 'DeepSeek',
    model: 'not-displayed',
    baseUrlHost: '',
    timeout: 30,
    lastSuccessAt: '2026-07-16T08:00:00Z',
    lastErrorCode: null,
  }
}

function localResponse(question: string) {
  return {
    question,
    selectedMode: 'LOCAL',
    selectionReason: '用户选择查数据，仅返回数据库事实。',
    localData: withSnapshotLocalData(mtdSnapshot),
    aiAnalysis: { available: false, provider: '', model: '', requestId: '', latencyMs: 0, summary: '', findings: [], risks: [], possibleCauses: [], actions: [], confidence: '', limitations: [] },
    fallbackUsed: false,
    error: null,
  }
}

function insufficientResponse(question: string) {
  return {
    ...localResponse(question),
    localData: withSnapshotLocalData(mtdSnapshot, {
      aiInvocation: 'NOT_CALLED_INSUFFICIENT',
      insufficientData: {
        kind: 'INSUFFICIENT_DATA',
        verifiedFacts: ['当前范围已有可验证的收入、成本、费用和经营利润汇总。'],
        cannotDetermine: ['无法按同门店、同营业日、同天数和同口径进行环比。'],
        missingItems: ['businessAsOf', 'dailyOperatingCoverage'],
        nextSteps: ['补齐缺失门店的当月经营数据。'],
        modelInvoked: false,
      },
    }),
  }
}

function aiDataLimitedResponse(question: string) {
  return {
    ...localResponse(question),
    selectedMode: 'AI',
    selectionReason: 'AI分析；当前快照只能输出数据受限分析。',
    localData: withSnapshotLocalData(mtdSnapshot, {
      aiInvocation: 'LIVE',
    }),
    aiAnalysis: {
      available: true,
      analysisType: 'DATA_LIMITED',
      provider: 'DeepSeek',
      model: 'deepseek-test',
      requestId: 'provider-data-limited',
      latencyMs: 1200,
      summary: '当前快照缺少业务截至日和日级经营覆盖，暂不做原因归因。',
      findings: ['已有收入、成本、费用和经营利润汇总，但不能证明同营业日口径。'],
      risks: [],
      possibleCauses: [],
      actions: [
        { action: '补全日级经营数据覆盖', ownerRole: 'FINANCE', deadline: '本周五', expectedImpact: '支持后续同营业日分析', verificationMetric: '日级经营数据完整率' },
      ],
      confidence: 'LOW',
      limitations: ['缺少业务截至日', '缺少日级经营覆盖'],
    },
    error: null,
  }
}

async function prepare(page: Page) {
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(assistantUser)))
  await page.route('**/api/stores', (route) => route.fulfill(ok([
    { id: 'store-1', code: 'S001', name: '测试门店', brandId: 1, brandName: '测试品牌', status: 'ACTIVE' },
  ])))
  await page.route('**/api/finance/months', (route) => route.fulfill(ok(['2026-07'])))
  await page.route('**/api/assistant/status', (route) => route.fulfill(ok(status())))
  await seedAuth(page, { token: 'e2e-assistant-snapshot-token', user: assistantUser })
}

test.describe('store assistant operating snapshot', () => {
  test('keeps the boss workbench focused while binding answers to the same operating snapshot', async ({ page }) => {
    await prepare(page)
    await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill(ok(mtdSnapshot)))
    let requestBody: Record<string, unknown> | null = null
    await page.route('**/api/assistant/chat', async (route) => {
      requestBody = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill(ok(localResponse(String(requestBody.message))))
    })

    await page.goto('/assistant')
    const pageSnapshot = page.locator('.snapshot-panel')
    await expect(pageSnapshot).toHaveAttribute('data-snapshot-id', mtdSnapshot.snapshotId)
    await expect(page.getByLabel('门店范围')).toHaveCount(0)
    await expect(page.locator('.kpi-grid')).toHaveCount(0)
    await expect(page.getByText('数据截至日期')).toHaveCount(0)
    await expect(page.getByText('门店覆盖')).toHaveCount(0)
    await expect(page.getByText(/不能与完整上月直接环比/)).toHaveCount(0)
    await expect(page.getByText('可比期实收收入')).toHaveCount(0)
    expect(await page.locator('.store-assistant-page').evaluate((element) => element.scrollWidth - element.clientWidth)).toBeLessThanOrEqual(1)

    await page.getByRole('button', { name: '查数据' }).click()
    await page.getByLabel('经营问题').fill('7月经营利润是多少？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    expect(requestBody?.snapshotId).toBe(mtdSnapshot.snapshotId)
    await expect(page.locator('.run-facts')).toHaveAttribute('data-snapshot-id', mtdSnapshot.snapshotId)
    await expect(page.getByText('本回答仅使用与页面一致的经营快照。')).toBeVisible()
    await expect(page.getByText('经营数据', { exact: true })).toBeVisible()
  })

  test('returns structured insufficient data without claiming a real model call and refreshes only the snapshot', async ({ page }) => {
    await prepare(page)
    let snapshotCalls = 0
    let chatCalls = 0
    await page.route('**/api/assistant/operating-snapshot**', (route) => {
      snapshotCalls += 1
      return route.fulfill(ok(mtdSnapshot))
    })
    await page.route('**/api/assistant/chat', async (route) => {
      chatCalls += 1
      const body = route.request().postDataJSON() as { message: string }
      await route.fulfill(ok(insufficientResponse(body.message)))
    })

    await page.goto('/assistant')
    await expect.poll(() => snapshotCalls).toBe(1)
    await page.getByRole('button', { name: '深度分析', exact: true }).click()
    await page.getByLabel('经营问题').fill('为什么本月利润率是 28%？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByTestId('assistant-data-limited')).toContainText('经营数据不足，暂不能判断原因')
    await expect(page.getByText('当前范围已有可验证的收入、成本、费用和经营利润汇总。')).toBeVisible()
    await expect(page.getByText('本次已调用 AI 分析。')).toHaveCount(0)
    await expect(page.getByText('经营数据不足，未调用 AI。')).toBeVisible()
    expect(chatCalls).toBe(1)

    await page.getByRole('button', { name: '重新分析', exact: true }).first().click()
    await expect.poll(() => snapshotCalls).toBe(2)
    expect(chatCalls).toBe(1)
    await expect(page.getByText('经营数据未变化，已重新确认当前快照。')).toBeVisible()
  })

  test('shows a live DATA_LIMITED model answer as an AI call', async ({ page }) => {
    await prepare(page)
    await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill(ok(mtdSnapshot)))
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string }
      await route.fulfill(ok(aiDataLimitedResponse(body.message)))
    })

    await page.goto('/assistant')
    await page.getByRole('button', { name: '深度分析', exact: true }).click()
    await page.getByLabel('经营问题').fill('为什么本月利润率是 28%？')
    await page.getByRole('button', { name: '发送', exact: true }).click()

    await expect(page.getByRole('heading', { name: 'AI经营分析' })).toBeVisible()
    await expect(page.getByText('本次已调用 AI 分析。')).toBeVisible()
    await expect(page.getByText('当前快照缺少业务截至日和日级经营覆盖，暂不做原因归因。')).toBeVisible()
    await expect(page.getByText('经营数据不足，未调用 AI。')).toHaveCount(0)
  })

  test('requires confirmation before clearing only the in-page conversation', async ({ page }) => {
    await prepare(page)
    await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill(ok(mtdSnapshot)))
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string }
      await route.fulfill(ok(localResponse(body.message)))
    })

    await page.goto('/assistant')
    await page.getByLabel('经营问题').fill('7月经营利润是多少？')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByText('经营数据', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '清空记录', exact: true }).click()
    await expect(page.getByRole('alertdialog')).toContainText('不会删除经营数据、已创建待办或操作日志')
    await page.getByRole('button', { name: '确认清空', exact: true }).click()
    await expect(page.getByText('AI 会基于全部授权门店的经营数据，解释原因并给出可以落地的行动建议。')).toBeVisible()
  })

  test('explains that a missing snapshot endpoint requires a matched backend restart', async ({ page }) => {
    await prepare(page)
    await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, code: 'INTERNAL_ERROR', message: '服务端处理失败，请稍后重试', data: null }),
    }))

    await page.goto('/assistant')
    await expect(page.getByRole('alert')).toContainText('经营快照服务暂不可用，可能是后端未更新或服务异常。请确认前端与后端已更新到同一版本，并重启后端后再试。')
  })

  test('rejects a success response that does not satisfy the operating snapshot contract', async ({ page }) => {
    await prepare(page)
    await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill(ok({
      snapshotId: 'legacy-response-without-required-fields',
    })))

    await page.goto('/assistant')
    await expect(page.getByRole('alert')).toContainText('经营快照服务返回的数据版本不兼容，请更新并重启后端后再试。')
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
  })

  test('rejects a chat response whose embedded snapshot conflicts with the displayed snapshot', async ({ page }) => {
    await prepare(page)
    await page.route('**/api/assistant/operating-snapshot**', (route) => route.fulfill(ok(mtdSnapshot)))
    await page.route('**/api/assistant/chat', async (route) => {
      const body = route.request().postDataJSON() as { message: string }
      await route.fulfill(ok({
        ...localResponse(body.message),
        localData: withSnapshotLocalData(mtdSnapshot, {
          operatingSnapshot: operatingSnapshot({ snapshotId: 'conflicting-snapshot-id' }),
        }),
      }))
    })

    await page.goto('/assistant')
    await page.getByLabel('经营问题').fill('7月经营利润是多少？')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByText('服务器未返回与当前页面一致的经营快照，已阻止展示可能混用的数据。')).toBeVisible()
    await expect(page.locator('.run-facts')).toHaveCount(0)
  })

  test('clears stale snapshots on month changes and ignores a late response from the previous month', async ({ page }) => {
    await prepare(page)
    const august = operatingSnapshot({ month: '2026-08', snapshotId: 'august-snapshot' })
    const july = operatingSnapshot({ month: '2026-07', snapshotId: 'july-snapshot' })
    let delayedJuly: Route | null = null

    await page.route('**/api/finance/months', (route) => route.fulfill(ok(['2026-08', '2026-07'])))
    await page.route('**/api/assistant/operating-snapshot**', async (route) => {
      const month = new URL(route.request().url()).searchParams.get('month')
      if (month === '2026-07' && delayedJuly === null) {
        delayedJuly = route
        return
      }
      if (month === '2026-07') {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'INTERNAL_ERROR', message: '服务端处理失败，请稍后重试', data: null }),
        })
        return
      }
      await route.fulfill(ok(august))
    })

    await page.goto('/assistant')
    const panel = page.locator('.snapshot-panel')
    await expect(panel).toHaveAttribute('data-snapshot-id', 'august-snapshot')
    await page.getByLabel('经营月份').selectOption('2026-07')
    await expect.poll(() => delayedJuly !== null).toBe(true)
    await page.getByLabel('经营月份').selectOption('2026-08')
    await expect(panel).toHaveAttribute('data-snapshot-id', 'august-snapshot')
    if (delayedJuly) await delayedJuly.fulfill(ok(july)).catch(() => undefined)
    await page.waitForTimeout(100)
    await expect(panel).toHaveAttribute('data-snapshot-id', 'august-snapshot')

    await page.getByLabel('经营月份').selectOption('2026-07')
    await expect(page.getByRole('alert')).toContainText('经营快照服务暂不可用')
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeDisabled()
    await expect(panel).not.toHaveAttribute('data-snapshot-id', /.+/)
  })
})
