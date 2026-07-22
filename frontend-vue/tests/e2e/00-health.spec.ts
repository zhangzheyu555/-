import { expect, test } from '@playwright/test'
import { API_BASE_URL, loginApi, roleByKey } from './auth.setup'

test.describe('P0 health checks', () => {
  test('backend public health endpoint is a minimal liveness response', async ({ request }) => {
    const response = await request.get(`${API_BASE_URL}/api/health`)
    expect(response.ok()).toBeTruthy()
    const payload = await response.json() as { success?: boolean, data?: Record<string, unknown> }
    expect(payload.success).toBeTruthy()
    expect(payload.data?.status).toBe('UP')
    expect(payload.data?.service).toBe('store-profit-backend')
    expect(Object.keys(payload.data || {}).sort()).toEqual(['service', 'status', 'time'])
  })

  test('backend diagnostics remain protected', async ({ request }) => {
    const response = await request.get(`${API_BASE_URL}/api/health/diagnostics`)
    expect(response.status()).toBe(401)
  })

  test('Vue login page loads', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByText('门店经营').first()).toBeVisible()
    await expect(page.getByRole('heading', { name: '安全登录' })).toBeVisible()
  })

  test('all role credentials can log in through API', async () => {
    for (const role of Object.values(roleByKey)) {
      const session = await loginApi(role.username, role.password)
      expect(session.token, `${role.key} should receive token`).toBeTruthy()
      expect(session.user?.role, `${role.key} should receive user role`).toBeTruthy()
    }
  })
})
