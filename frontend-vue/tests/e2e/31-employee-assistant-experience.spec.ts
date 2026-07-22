import { expect, test, type Page } from '@playwright/test'
import { expectNoWholePageOverflow, seedAuth } from './auth.setup'

const employeeAssistantUser = {
  id: 931,
  tenantId: 1,
  tenantName: '员工助手体验测试租户',
  displayName: '员工助手体验测试用户',
  role: 'SUPERVISOR',
  roleLabel: '督导',
  storeScope: ['all'],
  permissions: ['employee_assistant.use'],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/operations',
  permissionVersion: 1,
}

const refundSpeech = '理解您的着急，我先按门店流程帮您确认处理方式。'
const refundAnswer = [
  `1. 可以这样说：${refundSpeech}`,
  '2. 员工怎么处理',
  '- 先安抚顾客情绪。',
  '- 在现有业务系统内按门店规则核验，不在聊天中提交顾客或订单隐私。',
  '3. 什么时候转人工',
  '- 需要判断具体订单是否符合条件或无法确认门店规则时，转值班负责人。',
].join('\n')

test.describe('employee assistant experience', () => {
  test('uses the YaHei font stack and keeps the shell and input in the first desktop viewport', async ({ page }) => {
    await page.setViewportSize({ width: 1365, height: 768 })
    await prepareReadyAssistant(page)
    await page.goto('/employee-assistant')

    await expect(page.locator('.ea-error')).toHaveCount(0)
    await expect(page.getByLabel('服务问题')).toBeVisible()

    const fontFamilies = await page.locator('body, .ea-page, .ea-empty p, textarea, .ea-input button').evaluateAll((elements) =>
      elements.map((element) => getComputedStyle(element).fontFamily),
    )
    expect(fontFamilies.length).toBeGreaterThan(0)
    for (const family of fontFamilies) {
      expect(family).toMatch(/^['"]?Microsoft YaHei UI['"]?\s*,\s*['"]?Microsoft YaHei['"]?/i)
      expect(family).not.toMatch(/SimSun|NSimSun|宋体/i)
    }

    for (const viewport of [
      { width: 1365, height: 768 },
      { width: 1920, height: 1080 },
    ]) {
      await page.setViewportSize(viewport)
      await assertShellFillsPage(page, `${viewport.width}x${viewport.height}`)
      await assertInputInViewport(page, `${viewport.width}x${viewport.height}`)
      await captureEvidence(page, `employee-assistant-empty-${viewport.width}x${viewport.height}.png`)
    }
  })

  test('renders a refund answer in three sections and copies only the customer-facing speech', async ({ page }) => {
    await installClipboardProbe(page)
    await prepareReadyAssistant(page)
    await page.route('**/api/employee-assistant/chat', async (route) => {
      await route.fulfill(json({
        answer: refundAnswer,
        configured: true,
        needsHuman: true,
        answerSource: 'HUMAN_REQUIRED',
        handoffCategory: 'REFUND_REVIEW',
      }))
    })
    await page.goto('/employee-assistant')

    await page.getByLabel('服务问题').fill('顾客要求退款，应该怎么回应？')
    await page.getByRole('button', { name: '发送问题', exact: true }).click()

    const sections = page.locator('.ea-answer__section')
    await expect(sections).toHaveCount(3)
    await expect(sections.nth(0).getByRole('heading', { name: '可以这样说' })).toBeVisible()
    await expect(sections.nth(1).getByRole('heading', { name: '员工怎么处理' })).toBeVisible()
    await expect(sections.nth(2).getByRole('heading', { name: '什么时候转人工' })).toBeVisible()
    await expect(sections.nth(0)).toContainText(refundSpeech)
    await expect(sections.nth(1).locator('li')).toHaveCount(2)
    await expect(sections.nth(2).locator('li')).toHaveCount(1)
    await expect(page.getByRole('button', { name: '转人工处理' })).toBeVisible()

    await page.getByRole('button', { name: '复制话术' }).click()
    const copied = await page.evaluate(() => navigator.clipboard.readText())
    expect(copied).toBe(refundSpeech)
    expect(copied).not.toContain('现有业务系统')
    expect(copied).not.toContain('值班负责人')
    await captureEvidence(page, 'employee-assistant-refund-desktop.png')
  })

  test('shows the slow-response notice after five seconds and keeps the input visible at 390px', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await prepareReadyAssistant(page)

    let releaseResponse!: () => void
    const responseGate = new Promise<void>((resolve) => { releaseResponse = resolve })
    await page.route('**/api/employee-assistant/chat', async (route) => {
      await responseGate
      await route.fulfill(json({
        answer: refundAnswer,
        configured: true,
        needsHuman: false,
        answerSource: 'ASSISTANT',
      }))
    })
    await page.goto('/employee-assistant')

    await expect(page.getByLabel('服务问题')).toBeVisible()
    await expect(page.getByLabel('服务问题')).toBeEnabled()
    await assertInputInViewport(page, '390x844 before sending')
    await expectNoWholePageOverflow(page, '390px employee assistant')
    await page.getByLabel('服务问题').fill('顾客等待太久，应该怎么回应？')
    await page.getByRole('button', { name: '发送问题', exact: true }).click()

    await expect(page.getByText('正在查找标准话术', { exact: false })).toBeVisible()
    await expect(page.getByText('回复稍慢，仍在处理中')).toBeVisible({ timeout: 6_500 })
    await expect(page.getByRole('button', { name: '继续等待' })).toBeVisible()
    await expect(page.getByRole('button', { name: '转人工', exact: true })).toBeVisible()
    await captureEvidence(page, 'employee-assistant-slow-390.png')

    releaseResponse()
    await expect(page.getByRole('heading', { name: '可以这样说' })).toBeVisible()
    await assertInputInViewport(page, '390x844 after response')
    await captureEvidence(page, 'employee-assistant-answer-390.png')
  })

  test('keeps a timed-out question and supports handoff and retry', async ({ page }) => {
    await prepareReadyAssistant(page)
    const question = '顾客等待太久，应该怎么回应？'
    let chatCalls = 0
    let handoffQuestion = ''
    await page.route('**/api/employee-assistant/chat', async (route) => {
      chatCalls += 1
      if (chatCalls === 1) {
        await route.fulfill({
          status: 504,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'EMPLOYEE_ASSISTANT_UNAVAILABLE', message: '请求超时，请重新发送或转人工处理' }),
        })
        return
      }
      await route.fulfill(json({
        answer: refundAnswer,
        configured: true,
        needsHuman: false,
        answerSource: 'ASSISTANT',
      }))
    })
    await page.route('**/api/employee-assistant/handoffs', async (route) => {
      handoffQuestion = (route.request().postDataJSON() as { question: string }).question
      await route.fulfill(json({ id: 'handoff-e2e-1', question: handoffQuestion, category: 'GENERAL', status: 'OPEN' }))
    })
    await page.goto('/employee-assistant')

    await page.getByLabel('服务问题').fill(question)
    await page.getByRole('button', { name: '发送问题', exact: true }).click()
    await expect(page.getByText(question, { exact: true })).toHaveCount(1)
    await expect(page.locator('.ea-msg__recovery').getByText('请求超时，请重新发送或转人工处理', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '重新发送' })).toBeVisible()
    await expect(page.getByRole('button', { name: '转人工', exact: true })).toBeVisible()
    await assertInputInViewport(page, 'timeout recovery')

    await page.getByRole('button', { name: '转人工', exact: true }).click()
    await expect(page.getByText(/已创建人工事项/)).toBeVisible()
    expect(handoffQuestion).toBe(question)
    await page.getByRole('button', { name: '重新发送' }).click()
    await expect(page.getByRole('heading', { name: '可以这样说' })).toBeVisible()
    expect(chatCalls).toBe(2)
    await expect(page.getByText(question, { exact: true })).toHaveCount(1)
  })
})

async function prepareReadyAssistant(page: Page) {
  await page.route('**/api/auth/me', async (route) => route.fulfill(json(employeeAssistantUser)))
  await page.route('**/api/employee-assistant/status', async (route) => route.fulfill(json({
    enabled: true,
    configured: true,
    state: 'READY',
    canAsk: true,
    knowledgeAvailable: true,
    message: '员工服务助手已就绪',
  })))
  await seedAuth(page, { token: 'e2e-employee-assistant-experience-token', user: employeeAssistantUser })
}

async function installClipboardProbe(page: Page) {
  await page.addInitScript(() => {
    let copiedText = ''
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: async (text: string) => { copiedText = text },
        readText: async () => copiedText,
      },
    })
  })
}

async function assertShellFillsPage(page: Page, label: string) {
  const geometry = await page.evaluate(() => {
    const pageElement = document.querySelector<HTMLElement>('.ea-page')
    const topbar = document.querySelector<HTMLElement>('.ea-topbar')
    const shell = document.querySelector<HTMLElement>('.ea-shell')
    if (!pageElement || !topbar || !shell) throw new Error('员工助手布局元素缺失')
    const pageRect = pageElement.getBoundingClientRect()
    const topbarRect = topbar.getBoundingClientRect()
    const shellRect = shell.getBoundingClientRect()
    return {
      pageTop: pageRect.top,
      pageBottom: pageRect.bottom,
      pageHeight: pageRect.height,
      topbarBottom: topbarRect.bottom,
      shellTop: shellRect.top,
      shellBottom: shellRect.bottom,
      shellHeight: shellRect.height,
      shellGridRow: getComputedStyle(shell).gridRowStart,
    }
  })

  expect(geometry.shellGridRow, `${label} shell grid row`).toBe('3')
  expect(Math.abs(geometry.shellTop - geometry.topbarBottom), `${label} shell should start below topbar`).toBeLessThanOrEqual(1.5)
  expect(Math.abs(geometry.shellBottom - geometry.pageBottom), `${label} shell should fill remaining page height`).toBeLessThanOrEqual(1.5)
  expect(geometry.shellHeight, `${label} shell should have useful height`).toBeGreaterThan(geometry.pageHeight * 0.6)
  expect(geometry.pageTop, `${label} page should start in viewport`).toBeGreaterThanOrEqual(0)
}

async function assertInputInViewport(page: Page, label: string) {
  const geometry = await page.evaluate(() => {
    const input = document.querySelector<HTMLElement>('.ea-input')
    if (!input) throw new Error('员工助手输入区缺失')
    const rect = input.getBoundingClientRect()
    return {
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
      left: rect.left,
      width: rect.width,
      height: rect.height,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
    }
  })

  expect(geometry.width, `${label} input width`).toBeGreaterThan(0)
  expect(geometry.height, `${label} input height`).toBeGreaterThan(0)
  expect(geometry.top, `${label} input top`).toBeGreaterThanOrEqual(0)
  expect(geometry.left, `${label} input left`).toBeGreaterThanOrEqual(0)
  expect(geometry.right, `${label} input right`).toBeLessThanOrEqual(geometry.viewportWidth + 1)
  expect(geometry.bottom, `${label} input bottom`).toBeLessThanOrEqual(geometry.viewportHeight + 1)
}

function json(data: unknown) {
  return {
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}

async function captureEvidence(page: Page, fileName: string) {
  if (process.env.EMPLOYEE_ASSISTANT_CAPTURE !== '1') return
  await page.screenshot({ path: `../output/playwright/${fileName}`, fullPage: false })
}
