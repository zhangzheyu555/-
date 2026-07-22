import { expect, test, type Page } from '@playwright/test'
import { expectNoWholePageOverflow, seedAuth } from './auth.setup'

type ServiceState = 'UNCONFIGURED' | 'AUTH_FAILED' | 'UNAVAILABLE' | 'READY'

const stateCases: Array<{
  state: ServiceState
  enabled: boolean
  configured: boolean
  title: string
  nextAction: string
}> = [
  {
    state: 'UNCONFIGURED',
    enabled: false,
    configured: false,
    title: '服务未配置',
    nextAction: '请联系管理员配置员工助手服务',
  },
  {
    state: 'AUTH_FAILED',
    enabled: false,
    configured: true,
    title: '服务授权异常',
    nextAction: '请联系管理员检查员工助手服务授权',
  },
  {
    state: 'UNAVAILABLE',
    enabled: false,
    configured: true,
    title: '服务暂不可用',
    nextAction: '请稍后点击“检查服务”',
  },
  {
    state: 'READY',
    enabled: true,
    configured: true,
    title: '服务已就绪',
    nextAction: '仅发送通用服务问题',
  },
]

test.describe('employee assistant availability', () => {
  for (const scenario of stateCases) {
    test(`shows ${scenario.state} as a distinct business state`, async ({ page }) => {
      await seedEmployeeAssistantSession(page, scenario.state === 'UNCONFIGURED' ? 'BOSS' : 'SUPERVISOR')
      await mockStatus(page, scenario)

      await page.goto('/employee-assistant')
      await expect(page.getByTestId('employee-assistant-status')).toContainText(scenario.title)
      await expect(page.getByTestId('employee-assistant-status')).toContainText(scenario.nextAction)
      await expect(page.getByRole('button', { name: '检查服务' })).toBeVisible()

      if (scenario.state === 'UNCONFIGURED') {
        const guide = page.getByTestId('employee-assistant-deployment-guide')
        await expect(guide).toBeVisible()
        await expect(guide).toContainText('维护人员部署说明')
        await guide.locator('summary').click()
        await expect(guide).toContainText('EMPLOYEE_ASSISTANT_PROVIDER=REMOTE')
        await expect(guide).toContainText('EMPLOYEE_ASSISTANT_PROVIDER=MODEL')
        await expect(guide).toContainText('同一进程环境')
        await expect(guide).toContainText('不会自动读取')
        await expect(guide).toContainText('不能混用')
        await expect(guide).toContainText('DEEPSEEK_*')
        await expect(guide).toContainText('verify-employee-assistant-config.ps1')
        await expect(guide).not.toContainText('http://')
        await expect(guide).not.toContainText('Bearer ')
      } else {
        await expect(page.getByTestId('employee-assistant-deployment-guide')).toHaveCount(0)
      }

      const input = page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')
      const send = page.getByRole('button', { name: '发送问题' })
      if (scenario.state === 'READY') {
        await expect(input).toBeEnabled()
        await expect(send).toBeDisabled()
      } else {
        await expect(input).toBeDisabled()
        await expect(send).toBeDisabled()
      }
    })
  }

  test('sends only the question and current conversation to a ready employee assistant', async ({ page }) => {
    await seedEmployeeAssistantSession(page)
    await mockStatus(page, stateCases[3])
    let chatPayload: Record<string, unknown> | undefined
    await page.route('**/api/employee-assistant/chat', async (route) => {
      chatPayload = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: { answer: '可以先向顾客致歉，再确认口味偏好并协助调整。', configured: true, needsHuman: false },
        }),
      })
    })

    await page.goto('/employee-assistant')
    const input = page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')
    await input.fill('顾客说饮品太甜，怎样礼貌处理？')
    await expect(page.getByRole('button', { name: '发送问题' })).toBeEnabled()
    await page.getByRole('button', { name: '发送问题' }).click()

    await expect(page.getByText('可以先向顾客致歉，再确认口味偏好并协助调整。')).toBeVisible()
    expect(chatPayload).toBeDefined()
    expect(Object.keys(chatPayload || {}).sort()).toEqual(['message', 'sessionId'])
    expect(chatPayload?.message).toBe('顾客说饮品太甜，怎样礼貌处理？')
    expect(typeof chatPayload?.sessionId).toBe('string')
  })

  for (const scenario of [
    { code: 'EMPLOYEE_ASSISTANT_AUTH_FAILED', title: '服务授权异常', message: '员工服务助手授权异常，请联系管理员检查服务配置' },
    { code: 'EMPLOYEE_ASSISTANT_TIMEOUT', title: '服务暂不可用', message: '员工服务助手响应超时，请稍后重试或转人工处理' },
  ]) {
    test(`disables follow-up messages after chat reports ${scenario.code}`, async ({ page }) => {
      await seedEmployeeAssistantSession(page)
      await mockStatus(page, stateCases[3])
      await page.route('**/api/employee-assistant/chat', async (route) => {
        await route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: scenario.code, message: scenario.message }),
        })
      })

      await page.goto('/employee-assistant')
      const input = page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')
      await input.fill('顾客说饮品太甜，怎样礼貌处理？')
      await page.getByRole('button', { name: '发送问题' }).click()

      await expect(page.getByTestId('employee-assistant-status')).toContainText(scenario.title)
      await expect(page.getByRole('alert')).toContainText(scenario.message)
      await expect(input).toBeDisabled()
      await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled()
    })
  }

  test('keeps the unavailable state readable and contained on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await seedEmployeeAssistantSession(page)
    await mockStatus(page, stateCases[2])

    await page.goto('/employee-assistant')
    await expect(page.getByTestId('employee-assistant-status')).toContainText('服务暂不可用')
    await expect(page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')).toBeDisabled()
    await expectNoWholePageOverflow(page, 'employee assistant unavailable at 390px')
  })

  test('keeps the unconfigured deployment guide contained on mobile and readable on desktop', async ({ page }) => {
    await seedEmployeeAssistantSession(page, 'BOSS')
    await mockStatus(page, stateCases[0])

    await page.goto('/employee-assistant')
    const guide = page.getByTestId('employee-assistant-deployment-guide')
    await guide.locator('summary').click()
    await expect(guide).toContainText('EMPLOYEE_ASSISTANT_MODEL_API_KEY')
    await expectNoWholePageOverflow(page, 'employee assistant deployment guide at desktop')

    await page.setViewportSize({ width: 390, height: 844 })
    await expectNoWholePageOverflow(page, 'employee assistant deployment guide at 390px')
  })

  test('keeps deployment variables hidden from an ordinary assistant user', async ({ page }) => {
    await seedEmployeeAssistantSession(page, 'SUPERVISOR')
    await mockStatus(page, stateCases[0])

    await page.goto('/employee-assistant')
    await expect(page.getByTestId('employee-assistant-status')).toContainText('请联系管理员配置员工助手服务')
    await expect(page.getByTestId('employee-assistant-deployment-guide')).toHaveCount(0)
    await expect(page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')).toBeDisabled()
  })

  test('allows published local knowledge when the upstream is unconfigured without exposing status details', async ({ page }) => {
    await seedEmployeeAssistantSession(page)
    await page.route('**/api/employee-assistant/status', async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            enabled: false,
            configured: false,
            state: 'UNCONFIGURED',
            canAsk: true,
            knowledgeAvailable: true,
            message: '内部地址 https://assistant.example.test/status?token=must-not-display',
          },
        }),
      })
    })
    await page.route('**/api/employee-assistant/chat', async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          success: true,
          data: {
            answer: '请先确认顾客需求，再使用已发布的话术回复。',
            configured: true,
            needsHuman: false,
            answerSource: 'KNOWLEDGE',
          },
        }),
      })
    })

    await page.goto('/employee-assistant')
    await expect(page.getByTestId('employee-assistant-status')).toContainText('标准话术可用')
    await expect(page.getByTestId('employee-assistant-status')).not.toContainText('assistant.example.test')
    await expect(page.getByTestId('employee-assistant-status')).not.toContainText('must-not-display')
    const input = page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')
    await expect(input).toBeEnabled()
    await input.fill('顾客投诉等待太久，怎么回应？')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByText('请先确认顾客需求，再使用已发布的话术回复。')).toBeVisible()
  })
})

async function mockStatus(page: Page, scenario: (typeof stateCases)[number]) {
  await page.route('**/api/employee-assistant/status', async (route) => {
    await route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: {
          enabled: scenario.enabled,
          configured: scenario.configured,
          state: scenario.state,
          message: messageFor(scenario.state),
        },
      }),
    })
  })
}

async function seedEmployeeAssistantSession(page: Page, role: 'BOSS' | 'SUPERVISOR' = 'SUPERVISOR') {
  const isBoss = role === 'BOSS'
  const user = {
    id: 901,
    tenantId: 1,
    tenantName: 'E2E 租户',
    displayName: isBoss ? 'E2E 老板' : 'E2E 督导',
    role,
    roleLabel: isBoss ? '老板（系统管理员）' : '运营',
    storeScope: ['all'],
    permissions: ['employee_assistant.use'],
    dataScopes: { STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
    dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    boundStoreId: null,
    boundStoreName: null,
    brandId: null,
    brandName: null,
    defaultWorkspace: isBoss ? '/boss' : '/operations',
    permissionVersion: 1,
  }
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ success: true, data: user }) })
  })
  await seedAuth(page, { token: 'e2e-employee-assistant-token', user })
}

function messageFor(state: ServiceState) {
  switch (state) {
    case 'UNCONFIGURED': return '员工服务助手未配置'
    case 'AUTH_FAILED': return '员工服务助手授权未通过'
    case 'UNAVAILABLE': return '员工服务助手响应超时，请稍后重试'
    case 'READY': return '员工服务助手可用'
  }
}
