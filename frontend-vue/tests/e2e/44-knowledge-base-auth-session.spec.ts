import { expect, test, type Page, type Route } from '@playwright/test'
import { seedAuth } from './auth.setup'

type FormalRole = 'BOSS' | 'FINANCE' | 'SUPERVISOR' | 'WAREHOUSE' | 'STORE_MANAGER' | 'EMPLOYEE'

const formalRoles: FormalRole[] = [
  'BOSS',
  'FINANCE',
  'SUPERVISOR',
  'WAREHOUSE',
  'STORE_MANAGER',
  'EMPLOYEE',
]

for (const role of formalRoles) {
  test(`${role} 的完整会话包含检索权限时显示知识库入口并允许访问`, async ({ page }) => {
    const session = sessionFor(role, true)
    await mockApis(page, () => session)
    await seedAuth(page, { token: `e2e-${role.toLowerCase()}-knowledge-token`, user: session })

    await page.goto('/knowledge-base')

    await expect(page).toHaveURL(/\/knowledge-base$/)
    await expect(page.locator('.app-sidebar--desktop').getByRole('link', { name: '知识库', exact: true }))
      .toBeVisible()
    await expect(page.getByText('当前账号没有访问该页面的权限')).toHaveCount(0)
  })
}

test('窗口重新获得焦点后重新拉取完整会话并更新权限菜单', async ({ page }) => {
  let session = sessionFor('EMPLOYEE', false)
  let authMeCalls = 0
  await mockApis(page, () => {
    authMeCalls += 1
    return session
  })
  await seedAuth(page, { token: 'e2e-stale-employee-token', user: session })
  await page.goto('/employee')

  const sidebar = page.locator('.app-sidebar--desktop')
  await expect(sidebar).toBeVisible()
  await expect(sidebar.getByRole('link', { name: '知识库', exact: true })).toHaveCount(0)
  await expect.poll(() => authMeCalls).toBeGreaterThan(0)
  const authMeCallsBeforeFocus = authMeCalls

  session = sessionFor('EMPLOYEE', true)
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))

  await expect.poll(() => authMeCalls).toBe(authMeCallsBeforeFocus + 1)
  await expect(sidebar.getByRole('link', { name: '知识库', exact: true })).toBeVisible()
  await expect.poll(() => page.evaluate(() => {
    const raw = localStorage.getItem('ai_profit_vue_user')
    return raw ? JSON.parse(raw).permissions : []
  })).toContain('knowledge_base.search')
})

async function mockApis(page: Page, session: () => ReturnType<typeof sessionFor>) {
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/auth/me') return ok(route, session())
    if (path === '/api/employee/workbench') {
      const user = session()
      return ok(route, {
        profile: { userId: user.id, displayName: user.displayName, role: user.role },
        store: { storeId: 'TEST-STORE', storeName: '测试门店', brandName: '测试品牌' },
        workItems: [],
        workSummary: { total: 0, pending: 0, overdue: 0, completed: 0, retakePending: 0 },
        assistant: { enabled: true, state: 'READY', message: '员工服务助手可用', route: '/employee-assistant' },
      })
    }
    return ok(route, [])
  })
}

function sessionFor(role: FormalRole, includeKnowledgeSearch: boolean) {
  const ownStore = ['STORE_MANAGER', 'EMPLOYEE'].includes(role)
  return {
    id: 9400 + formalRoles.indexOf(role),
    tenantId: 1,
    tenantName: 'E2E 租户',
    displayName: `E2E ${role}`,
    role,
    roleLabel: role,
    storeScope: ownStore ? ['TEST-STORE'] : ['all'],
    permissions: [
      ...(includeKnowledgeSearch ? ['knowledge_base.search'] : []),
      ...(role === 'EMPLOYEE' ? ['exam.learn', 'employee_assistant.use'] : []),
    ],
    dataScopes: {
      STORE: {
        mode: ownStore ? 'OWN_STORE' : 'ALL',
        storeIds: ownStore ? ['TEST-STORE'] : [],
        warehouseIds: [],
      },
      EXAM: {
        mode: role === 'EMPLOYEE' ? 'SELF' : 'ALL',
        storeIds: [],
        warehouseIds: [],
      },
    },
    dataScope: {
      mode: ownStore ? 'OWN_STORE' : 'ALL',
      storeIds: ownStore ? ['TEST-STORE'] : [],
      warehouseIds: [],
    },
    boundStoreId: ownStore ? 'TEST-STORE' : null,
    boundStoreName: ownStore ? '测试门店' : null,
    brandId: null,
    brandName: null,
    defaultWorkspace: role === 'EMPLOYEE' ? '/employee' : '/no-permission',
    permissionVersion: 1,
  }
}

function ok(route: Route, data: unknown) {
  return route.fulfill({
    status: 200,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: true, data }),
  })
}
