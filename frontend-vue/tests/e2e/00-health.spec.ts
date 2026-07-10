import { expect, test } from '@playwright/test'
import { API_BASE_URL, loginApi, roleByKey } from './auth.setup'

test.describe('P0 health checks', () => {
  test('backend health endpoint is available', async ({ request }) => {
    const response = await request.get(`${API_BASE_URL}/api/health`)
    expect(response.ok()).toBeTruthy()
  })

  test('Vue login page loads', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByText('AI Profit').first()).toBeVisible()
    await expect(page.getByRole('heading', { name: '多门店经营异常处理系统' })).toBeVisible()
  })

  test('all role credentials can log in through API', async () => {
    for (const role of Object.values(roleByKey)) {
      const session = await loginApi(role.username, role.password)
      expect(session.token, `${role.key} should receive token`).toBeTruthy()
      expect(session.user?.role, `${role.key} should receive user role`).toBeTruthy()
    }
  })
})
