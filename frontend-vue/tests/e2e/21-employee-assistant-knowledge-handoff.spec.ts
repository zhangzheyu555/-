import { expect, test } from '@playwright/test'
import { seedAuth } from './auth.setup'

test('retired employee-assistant links return to the employee service assistant', async ({ page }) => {
  const user = {
    id: 901, tenantId: 1, tenantName: 'E2E 租户', displayName: 'E2E 用户', role: 'BOSS', roleLabel: '老板',
    storeScope: ['all'], permissions: ['employee_assistant.use'], dataScopes: { STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
    dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] }, boundStoreId: null, boundStoreName: null,
    brandId: null, brandName: null, defaultWorkspace: '/operations', permissionVersion: 1,
  }
  await page.route('**/api/auth/me', async (route) => route.fulfill(json(user)))
  await page.route('**/api/employee-assistant/status', async (route) => route.fulfill(json({
    enabled: true, configured: true, state: 'READY', message: '员工服务助手已就绪', canAsk: true,
  })))
  await seedAuth(page, { token: 'e2e-employee-assistant-token', user })

  for (const retiredPath of ['/employee-assistant/knowledge', '/employee-assistant/handoffs']) {
    await page.goto(retiredPath)
    await expect(page).toHaveURL(/\/employee-assistant$/)
    await expect(page.getByRole('heading', { name: '员工服务助手' })).toBeVisible()
  }
})

function json(data: unknown) {
  return { contentType: 'application/json', body: JSON.stringify({ success: true, data }) }
}
