import { expect, test } from '@playwright/test'
import { apiFetchAs, loginAs, roles, type RoleKey } from './auth.setup'

test.describe('role menu permission', () => {
  for (const role of roles) {
    test(`${role.key} sees only allowed navigation entries`, async ({ page }) => {
      await page.setViewportSize({ width: 1365, height: 900 })
      await loginAs(page, role.key)
      await page.goto('/')
      await page.waitForLoadState('networkidle')

      const navText = await page.locator('.sidebar-desktop').innerText()
      for (const label of role.expectedMenus) {
        expect(navText, `${role.key} should show ${label}`).toContain(label)
      }
      for (const label of role.forbiddenMenus) {
        expect(navText, `${role.key} should not show ${label}`).not.toContain(label)
      }
    })
  }

  test('boss menu follows the legacy HTML top-role navigation', async ({ page }) => {
    await page.setViewportSize({ width: 1365, height: 900 })
    await loginAs(page, 'boss')
    await page.goto('/boss')
    await page.waitForLoadState('networkidle')

    const nav = page.locator('.sidebar-desktop .nav-list')
    await expect(nav.locator('.utility-navigation .nav-group-title')).toHaveText('辅助工具')
    await expect(nav).toContainText('今日待办')
    await expect(nav).toContainText('数据录入')
    await expect(nav).toContainText('报销栏')
    await expect(nav).toContainText('平台登录')
    await expect(nav).not.toContainText('老板驾驶舱')
  })
})

test.describe('page and API permission guardrails', () => {
  const pageChecks: Array<{ role: RoleKey; path: string }> = [
    { role: 'finance', path: '/boss' },
    { role: 'warehouse', path: '/boss' },
    { role: 'warehouse', path: '/profit' },
    { role: 'store', path: '/finance' },
    { role: 'store', path: '/boss' },
    { role: 'store', path: '/profit' },
    { role: 'supervisor', path: '/finance' },
    { role: 'supervisor', path: '/profit' },
    { role: 'operations', path: '/boss' },
    { role: 'operations', path: '/profit' },
  ]

  for (const check of pageChecks) {
    test(`${check.role} cannot open ${check.path}`, async ({ page }) => {
      await loginAs(page, check.role)
      await page.goto(check.path)
      await page.waitForLoadState('networkidle')
      await expect(page.getByText('当前账号没有访问该页面的权限')).toBeVisible()
    })
  }

  const bossAccessChecks = [
    { path: '/boss', text: '今日待办' },
    { path: '/profit', text: '利润概览' },
    { path: '/profit-table', text: '利润表' },
    { path: '/store-detail', text: '门店详情' },
    { path: '/data-entry', text: '数据录入' },
    { path: '/expenses', text: '报销栏' },
    { path: '/export', text: '数据导出' },
    { path: '/stores', text: '门店管理' },
    { path: '/logs', text: '操作日志' },
    { path: '/platform-login', text: '平台登录' },
    { path: '/warehouse', text: '仓库中心' },
    { path: '/warehouse/items', text: '商品档案' },
    { path: '/warehouse/purchase', text: '采购入库' },
    { path: '/warehouse/movements', text: '出入库记录' },
    { path: '/warehouse/returns', text: '配送退货单' },
    { path: '/warehouse/alerts', text: '库存预警' },
    { path: '/warehouse/receipts', text: '入库记录' },
    { path: '/inspection', text: '督导巡店' },
    { path: '/inspection/tasks', text: '发起巡检' },
    { path: '/inspection/records', text: '巡检记录' },
    { path: '/inspection/reviews', text: '督导巡店' },
    { path: '/inspection/rules', text: '稽核标准' },
    { path: '/inspection/standards', text: '稽核标准' },
    { path: '/operations', text: '运营中心' },
    { path: '/operations/analysis', text: '数据分析' },
    { path: '/operations/training', text: '新人培训' },
    { path: '/operations/exam', text: '考试系统' },
    { path: '/operations/inventory-check', text: '店铺盘存' },
    { path: '/operations/eleme', text: '饿了么订单' },
    { path: '/operations/platform', text: '平台账号' },
    { path: '/operations/imports', text: '数据导入' },
    { path: '/operations/logs', text: '操作日志' },
    { path: '/assistant', text: '门店经营助手' },
  ]

  for (const check of bossAccessChecks) {
    test(`boss can open ${check.path}`, async ({ page }) => {
      await loginAs(page, 'boss')
      await page.goto(check.path)
      await page.waitForLoadState('networkidle')
      await expect(page.getByText('当前账号没有访问该页面的权限')).toHaveCount(0)
      await expect(page.locator('body')).toContainText(check.text)
    })
  }

  test('boss profit overview keeps legacy visual anchors', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/profit')
    await page.waitForLoadState('networkidle')

    const body = page.locator('body')
    await expect(body).toContainText('老板，你好')
    await expect(body).toContainText('合并营业收入')
    await expect(body).toContainText('茹菓')
    await expect(body).toContainText('霸王茶姬')
    await expect(body).toContainText('瑞幸咖啡')
    await expect(body).not.toContainText('苹果奶茶')
    await expect(body).toContainText('各店净利率排名')
    await expect(body).not.toContainText('第一阶段占位')
    await expect(body).not.toContainText('暂未迁移')
  })

  test('finance can open profit overview but it is not a placeholder', async ({ page }) => {
    await loginAs(page, 'finance')
    await page.goto('/profit')
    await page.waitForLoadState('networkidle')
    await expect(page.getByText('利润概览').first()).toBeVisible()
    await expect(page.locator('body')).toContainText('合并营业收入')
    await expect(page.locator('body')).toContainText('合并净利润')
    await expect(page.locator('body')).toContainText('整体净利率')
    await expect(page.locator('body')).not.toContainText('暂未迁移')
  })

  const apiChecks: Array<{ role: RoleKey; path: string; method?: string; data?: unknown }> = [
    { role: 'finance', path: '/api/boss/todo-dashboard' },
    { role: 'warehouse', path: '/api/boss/todo-dashboard' },
    { role: 'store', path: '/api/finance/workbench' },
    { role: 'store', path: '/api/warehouse/requisitions/TEST-NO-SUCH/ship', method: 'POST', data: { note: 'permission smoke' } },
    { role: 'finance', path: '/api/warehouse/requisitions/TEST-NO-SUCH/ship', method: 'POST', data: { note: 'permission smoke' } },
    { role: 'warehouse', path: '/api/finance/todos/TEST-NO-SUCH/complete', method: 'POST', data: { note: 'permission smoke' } },
    { role: 'supervisor', path: '/api/boss/todos/TEST-NO-SUCH/resolve', method: 'POST', data: { note: 'permission smoke' } },
    { role: 'operations', path: '/api/boss/todos/TEST-NO-SUCH/resolve', method: 'POST', data: { note: 'permission smoke' } },
  ]

  for (const check of apiChecks) {
    test(`${check.role} receives 403 for ${check.method || 'GET'} ${check.path}`, async ({ request }) => {
      const response = await apiFetchAs(request, check.role, check.path, { method: check.method, data: check.data })
      expect(response.status()).toBe(403)
    })
  }
})
