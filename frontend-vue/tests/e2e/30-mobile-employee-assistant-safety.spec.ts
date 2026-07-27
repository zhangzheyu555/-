import { expect, test, type Locator, type Page } from '@playwright/test'
import { expectNoWholePageOverflow, seedAuth } from './auth.setup'

const employeeAssistantUser = {
  id: 930,
  tenantId: 1,
  tenantName: '移动端员工助手测试租户',
  displayName: '移动端员工',
  role: 'EMPLOYEE',
  roleLabel: '员工',
  storeScope: ['all'],
  permissions: ['exam.learn', 'employee_assistant.use'],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/employee',
  permissionVersion: 1,
}

type ServiceState = 'UNCONFIGURED' | 'READY'

async function prepareEmployeeAssistant(page: Page, state: ServiceState) {
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill(json(employeeAssistantUser))
  })
  await page.route('**/api/employee-assistant/status', async (route) => {
    await route.fulfill(json({
      enabled: state === 'READY',
      configured: state === 'READY',
      state,
      message: state === 'READY' ? '员工服务助手已就绪' : '员工服务助手未配置',
    }))
  })
  await seedAuth(page, { token: 'e2e-mobile-employee-assistant-token', user: employeeAssistantUser })
}

async function expectTouchTarget(locator: Locator, label: string) {
  await expect(locator).toBeVisible()
  const box = await locator.boundingBox()
  expect(box, `${label} should have a layout box`).not.toBeNull()
  expect(box!.width, `${label} width`).toBeGreaterThanOrEqual(44)
  expect(box!.height, `${label} height`).toBeGreaterThanOrEqual(44)
}

test.describe('employee assistant mobile safety', () => {
  test('keeps an unconfigured service understandable, safe, and usable on mobile', async ({ page }) => {
    await prepareEmployeeAssistant(page, 'UNCONFIGURED')

    await page.goto('/employee-assistant')

    await expect(page.getByTestId('employee-assistant-status')).toContainText('服务未配置')
    await expect(page.getByTestId('employee-assistant-status')).toContainText('请联系管理员配置员工助手服务')
    await expect(page.getByTestId('employee-assistant-deployment-guide')).toHaveCount(0)
    await expect(page.getByText('请勿发送任何顾客、订单、支付、附件或身份信息 · Ctrl + Enter 发送')).toBeVisible()

    const input = page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')
    const send = page.getByRole('button', { name: '发送问题' })
    await expect(input).toBeDisabled()
    await expect(send).toBeDisabled()
    await expect(page.getByRole('button', { name: '检查服务' })).toHaveCount(0)
    await expectTouchTarget(input, '员工助手输入框')
    await expectTouchTarget(send, '发送问题按钮')
    await expectNoWholePageOverflow(page, 'employee assistant unconfigured mobile state')
  })

  test('presents a privacy rejection without exposing an internal code on mobile', async ({ page }) => {
    await prepareEmployeeAssistant(page, 'READY')
    let submittedMessage = ''
    await page.route('**/api/employee-assistant/chat', async (route) => {
      submittedMessage = (route.request().postDataJSON() as { message: string }).message
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 'EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED',
          message: '员工服务助手不接收顾客姓名、订单或地址信息，请删除隐私内容后再咨询',
        }),
      })
    })

    await page.goto('/employee-assistant')

    const input = page.getByPlaceholder('例如：顾客说饮品太甜，怎样礼貌处理？')
    const send = page.getByRole('button', { name: '发送问题' })
    await expect(input).toBeEnabled()
    await expectTouchTarget(input, '员工助手输入框')
    await expectTouchTarget(send, '发送问题按钮')
    await input.fill('顾客订单号：A20260715001，请帮忙查询')
    await send.click()

    await expect(page.getByRole('alert')).toContainText('员工服务助手不接收顾客姓名、订单或地址信息，请删除隐私内容后再咨询')
    await expect(page.getByText('EMPLOYEE_ASSISTANT_PRIVACY_BLOCKED')).toHaveCount(0)
    expect(submittedMessage).toBe('顾客订单号：A20260715001，请帮忙查询')
    await expect(input).toBeEnabled()
    await expectNoWholePageOverflow(page, 'employee assistant privacy rejection on mobile')
  })
})

function json(data: unknown) {
  return {
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  }
}
