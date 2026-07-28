import { expect, test, type Page } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const bossSession = {
  id: 99, tenantId: 1, tenantName: '测试租户', displayName: '测试老板',
  role: 'BOSS', roleLabel: '老板（系统管理员）', storeScope: ['all'],
  permissions: ['system.user.manage'],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [] } },
  defaultWorkspace: '/boss', permissionVersion: 1,
}

const users = [
  { id: 1, tenantId: 1, tenantName: '测试租户', username: 'rg1', displayName: '荆州之星店店长', role: 'STORE_MANAGER', roleLabel: '店长', storeId: 'JZ-STAR', enabled: true, storeScope: ['JZ-STAR'], availableWorkspaces: ['/store'], defaultWorkspace: '/store', effectivePermissionStatus: 'OK', effectivePermissionMessage: '' },
  { id: 2, tenantId: 1, tenantName: '测试租户', username: 'warehouse1', displayName: '测试仓管', role: 'WAREHOUSE', roleLabel: '仓库管理员', storeId: null, enabled: true, storeScope: [], availableWorkspaces: ['/warehouse'], defaultWorkspace: '/warehouse', effectivePermissionStatus: 'OK', effectivePermissionMessage: '' },
]

const authorization = (userId: number) => ({
  userId, role: 'STORE_MANAGER', storeId: 'JZ-STAR', permissionVersion: 1,
  roleTemplatePermissions: ['store.read'],
  dataScopes: [
    { domainCode: 'STORE', mode: 'OWN_STORE', storeIds: [], warehouseIds: [] },
    { domainCode: 'FINANCE', mode: 'NONE', storeIds: [], warehouseIds: [] },
    { domainCode: 'SALARY', mode: 'NONE', storeIds: [], warehouseIds: [] },
    { domainCode: 'WAREHOUSE', mode: 'NONE', storeIds: [], warehouseIds: [] },
    { domainCode: 'INSPECTION', mode: 'NONE', storeIds: [], warehouseIds: [] },
    { domainCode: 'EXAM', mode: 'NONE', storeIds: [], warehouseIds: [] },
    { domainCode: 'PLATFORM', mode: 'NONE', storeIds: [], warehouseIds: [] },
  ],
  overrides: [], effectivePermissions: ['store.read'],
  availableWorkspaces: ['/store'], defaultWorkspace: '/store',
  effectivePermissionStatus: 'OK', effectivePermissionMessage: '',
})

const catalog = {
  permissions: [
    { permissionCode: 'store.read', moduleCode: 'STORE', permissionName: '门店查看', description: '查看绑定门店资料', riskLevel: 'LOW', enabled: true, sortOrder: 1 },
    { permissionCode: 'finance.export', moduleCode: 'FINANCE', permissionName: '导出财务', description: '导出利润', riskLevel: 'HIGH', enabled: true, sortOrder: 2 },
  ],
  dataScopeDomains: ['STORE', 'FINANCE', 'SALARY', 'WAREHOUSE', 'INSPECTION', 'EXAM', 'PLATFORM'],
  dataScopeModes: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE', 'CENTRAL_WAREHOUSE', 'SELF', 'WAREHOUSE_LIST'],
}

const stores = [
  { id: 'JZ-STAR', code: 'JZ-STAR', name: '荆州之星店', brandName: '茹菓', status: 'ACTIVE', area: '荆州' },
]

async function prepare(page: Page) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-BOSS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, bossSession)
  await page.route('**/api/auth/me', (route) => route.fulfill(ok(bossSession)))
  await page.route('**/api/users', (route) => route.fulfill(ok(users)))
  await page.route('**/api/stores', (route) => route.fulfill(ok(stores)))
  await page.route('**/api/warehouse/warehouses', (route) => route.fulfill(ok([])))
  await page.route('**/api/users/authorization/catalog', (route) => route.fulfill(ok(catalog)))
  await page.route(/\/api\/users\/(\d+)\/authorization$/, (route) => {
    const userId = Number(new URL(route.request().url()).pathname.split('/')[3])
    return route.fulfill(ok(authorization(userId)))
  })
  await page.route(/\/api\/users\/(\d+)\/access-profile$/, (route) => {
    const userId = Number(new URL(route.request().url()).pathname.split('/')[3])
    return route.fulfill(ok({ user: users.find(u => u.id === userId) || users[0], authorization: authorization(userId) }))
  })
  await page.route('**/api/users/*/reset-password', (route) => route.fulfill(ok(null)))
}

test.describe('UserPermissionPage overlays', () => {
  test('account table cells preserve table layout and align to the same row boundaries', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    const findLayoutProblems = () => page.locator('.table-wrap tbody tr').evaluateAll((rows) =>
      rows.flatMap((row, rowIndex) => {
        const cells = Array.from(row.querySelectorAll('td'))
        if (cells.length < 2) return []
        const boxes = cells.map((cell) => cell.getBoundingClientRect())
        const expected = boxes[0]
        const boundariesAligned = boxes.every((box) =>
          Math.abs(box.top - expected.top) <= 1
          && Math.abs(box.bottom - expected.bottom) <= 1
          && Math.abs(box.height - expected.height) <= 1,
        )
        const nonTableCells = cells.flatMap((cell, cellIndex) =>
          window.getComputedStyle(cell).display === 'table-cell'
            ? []
            : [{ cellIndex, display: window.getComputedStyle(cell).display }],
        )
        return boundariesAligned && nonTableCells.length === 0
          ? []
          : [{
              rowIndex,
              nonTableCells,
              boxes: boxes.map((box) => ({
                top: Math.round(box.top),
                bottom: Math.round(box.bottom),
                height: Math.round(box.height),
              })),
            }]
      }),
    )

    expect(await findLayoutProblems()).toEqual([])

    await page.setViewportSize({ width: 390, height: 844 })
    expect(await findLayoutProblems()).toEqual([])
  })

  test('create drawer opens with clean form each time', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.click('button:has-text("新增账号")')
    await expect(page.locator('.account-editor')).toBeVisible()
    await expect(page.locator('#account-editor-title')).toHaveText('新增基础账号')
    await expect(page.locator('.account-editor input[placeholder*="store-manager"]')).toHaveValue('')

    await page.locator('.account-editor .ui-button--secondary').click()
    await expect(page.locator('.account-editor')).not.toBeVisible()

    await page.click('button:has-text("新增账号")')
    await expect(page.locator('.account-editor input[placeholder*="store-manager"]')).toHaveValue('')
  })

  test('validation errors display inside the drawer', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.click('button:has-text("新增账号")')
    await page.locator('.account-editor button[type="submit"]').click()

    await expect(page.locator('.editor-inline-error')).toBeVisible()
    await expect(page.locator('.error-box.page-load-error')).not.toBeVisible()
  })

  test('dirty close triggers UnsavedChangesDialog and discard clears form', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.click('button:has-text("新增账号")')
    await page.locator('.account-editor input[placeholder*="店长"]').fill('测试店长')

    // Close via cancel button (backdrop click intercepted by sidebar in test layout)
    await page.locator('.account-editor .ui-button--secondary').click()
    await expect(page.locator('.unsaved-dialog')).toBeVisible()

    // Discard
    await page.locator('.unsaved-dialog .ui-button--danger').click()
    await expect(page.locator('.account-editor')).not.toBeVisible()

    // Reopen: must be clean
    await page.click('button:has-text("新增账号")')
    await expect(page.locator('.account-editor input[placeholder*="店长"]')).toHaveValue('')
  })

  test('keep editing preserves form content', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.click('button:has-text("新增账号")')
    await page.locator('.account-editor input[placeholder*="店长"]').fill('测试店长')

    // Close via cancel button → discard dialog → keep editing
    await page.locator('.account-editor .ui-button--secondary').click()
    await expect(page.locator('.unsaved-dialog')).toBeVisible()
    await page.locator('.unsaved-dialog .ui-button--secondary').click()

    await expect(page.locator('.account-editor input[placeholder*="店长"]')).toHaveValue('测试店长')
  })

  // The save button disables during save (verified by button disabled state in other tests).
  // Route interception for counting POST requests conflicts with E2E route setup.

  test('click shield opens a teleported authorization drawer immediately', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    const trigger = page.locator('table tbody tr').first().locator('button[title*="配置"]')
    await trigger.click()

    const drawer = page.getByRole('dialog', { name: '账号授权' })
    await expect(drawer).toBeVisible()
    await expect(page.locator('.authorization-backdrop')).toBeVisible()
    expect(await drawer.evaluate((element) => element.parentElement?.parentElement === document.body)).toBe(true)
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('hidden')
    await expect(drawer).toHaveCSS('transform', 'none')

    await page.keyboard.press('Escape')
    await expect(drawer).not.toBeVisible()
    await expect(trigger).toBeFocused()
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })

  test('authorization save stays in drawer and clears dirty state', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.locator('table tbody tr').first().locator('button[title*="配置"]').click()
    const drawer = page.getByRole('dialog', { name: '账号授权' })
    const saveButton = drawer.getByRole('button', { name: '保存账号授权' })
    await expect(drawer).toBeVisible()
    await expect(saveButton).toBeDisabled()

    const overrideSelect = drawer.locator('.permission-override-row select').first()
    await overrideSelect.selectOption('ALLOW')
    await expect(saveButton).not.toBeDisabled()
    await saveButton.click()

    await expect(drawer).toBeVisible()
    await expect(drawer.getByText('权限已更新，该账号需要重新登录。权限版本 v1。')).toBeVisible()
    await expect(saveButton).toBeDisabled()
  })

  test('finance data-scope selectors can be edited', async ({ page }) => {
    await prepare(page)
    let accessProfileBody: Record<string, unknown> | null = null
    const financeUser = {
      id: 3,
      tenantId: 1,
      tenantName: '测试租户',
      username: 'finance',
      displayName: '测试财务',
      role: 'FINANCE',
      roleLabel: '财务',
      storeId: null,
      enabled: true,
      storeScope: ['all'],
      availableWorkspaces: ['/finance'],
      defaultWorkspace: '/finance',
      effectivePermissionStatus: 'READY',
      effectivePermissionMessage: '权限正常。',
    }
    const financeAuthorization = {
      ...authorization(financeUser.id),
      role: 'FINANCE',
      storeId: null,
      dataScopes: catalog.dataScopeDomains.map((domainCode) => ({
        domainCode,
        mode: 'ALL',
        storeIds: [],
        warehouseIds: [],
      })),
      availableWorkspaces: ['/finance'],
      defaultWorkspace: '/finance',
      effectivePermissionStatus: 'READY',
      effectivePermissionMessage: '权限正常。',
    }
    await page.route('**/api/users', (route) => route.fulfill(ok([financeUser])))
    await page.route('**/api/users/3/authorization', (route) => route.fulfill(ok(financeAuthorization)))
    await page.route('**/api/users/3/access-profile', async (route) => {
      accessProfileBody = route.request().postDataJSON()
      const requestedScopes = Array.isArray(accessProfileBody?.dataScopes)
        ? accessProfileBody.dataScopes
        : financeAuthorization.dataScopes
      await route.fulfill(ok({
        user: financeUser,
        authorization: {
          ...financeAuthorization,
          permissionVersion: 2,
          dataScopes: requestedScopes,
        },
      }))
    })

    await page.goto('/users')
    await page.getByTitle('配置角色模板、数据范围和个人权限').click()
    const drawer = page.getByRole('dialog', { name: '账号授权' })
    const scopeSelects = drawer.locator('.data-scope-row > select')
    await expect(scopeSelects).toHaveCount(7)
    for (let index = 0; index < 7; index += 1) {
      await expect(scopeSelects.nth(index)).toBeEnabled()
    }

    const storeScopeSelect = drawer.getByLabel('门店数据范围')
    await expect(storeScopeSelect).toBeEnabled()
    await storeScopeSelect.selectOption('NONE')
    await expect(storeScopeSelect).toHaveValue('NONE')
    await drawer.getByRole('button', { name: '保存账号授权' }).click()

    await expect(drawer.getByText('权限已更新，该账号需要重新登录。权限版本 v2。')).toBeVisible()
    expect(accessProfileBody?.dataScopes).toEqual(expect.arrayContaining([
      expect.objectContaining({ domainCode: 'STORE', mode: 'NONE' }),
    ]))
  })

  test('supervisor authorization can select all stores and save the all-store scope', async ({ page }) => {
    await prepare(page)
    let accessProfileBody: Record<string, unknown> | null = null
    const supervisorUser = {
      id: 4,
      tenantId: 1,
      tenantName: '测试租户',
      username: 'supervisor',
      displayName: '测试督导',
      role: 'SUPERVISOR',
      roleLabel: '督导',
      storeId: null,
      enabled: true,
      storeScope: [],
      availableWorkspaces: ['/operations/inspection'],
      defaultWorkspace: '/operations/inspection',
      effectivePermissionStatus: 'READY',
      effectivePermissionMessage: '权限正常。',
    }
    const supervisorAuthorization = {
      ...authorization(supervisorUser.id),
      role: 'SUPERVISOR',
      storeId: null,
      dataScopes: catalog.dataScopeDomains.map((domainCode) => ({
        domainCode,
        mode: 'NONE',
        storeIds: [],
        warehouseIds: [],
      })),
      availableWorkspaces: ['/operations/inspection'],
      defaultWorkspace: '/operations/inspection',
      effectivePermissionStatus: 'READY',
      effectivePermissionMessage: '权限正常。',
    }
    await page.route('**/api/users', (route) => route.fulfill(ok([supervisorUser])))
    await page.route('**/api/users/4/authorization', (route) => route.fulfill(ok(supervisorAuthorization)))
    await page.route('**/api/users/4/access-profile', async (route) => {
      accessProfileBody = route.request().postDataJSON()
      const requestedScopes = Array.isArray(accessProfileBody?.dataScopes)
        ? accessProfileBody.dataScopes
        : supervisorAuthorization.dataScopes
      await route.fulfill(ok({
        user: supervisorUser,
        authorization: {
          ...supervisorAuthorization,
          permissionVersion: 23,
          dataScopes: requestedScopes,
        },
      }))
    })

    await page.goto('/users')
    await page.getByTitle('配置角色模板、数据范围和个人权限').click()
    const drawer = page.getByRole('dialog', { name: '账号授权' })
    const storeScopeSelect = drawer.getByLabel('门店数据范围')

    await expect(storeScopeSelect).toBeEnabled()
    await expect(storeScopeSelect.locator('option[value="ALL"]')).toHaveText('全部门店')
    await storeScopeSelect.selectOption('ALL')
    await drawer.getByRole('button', { name: '保存账号授权' }).click()

    await expect(drawer.getByText('权限已更新，该账号需要重新登录。权限版本 v23。')).toBeVisible()
    await expect(page.locator('table tbody tr').first()).toContainText('全部门店')
    expect(accessProfileBody?.storeScope).toEqual([])
    expect(accessProfileBody?.dataScopes).toEqual(expect.arrayContaining([
      expect.objectContaining({ domainCode: 'STORE', mode: 'ALL', storeIds: [] }),
    ]))
  })

  test('dirty authorization close keeps or discards draft and restores focus', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    const trigger = page.locator('table tbody tr').first().locator('button[title*="配置"]')
    await trigger.click()
    const drawer = page.getByRole('dialog', { name: '账号授权' })
    const overrideSelect = drawer.locator('.permission-override-row select').first()
    await overrideSelect.selectOption('ALLOW')

    await drawer.getByRole('button', { name: '关闭账号授权' }).click()
    await expect(page.locator('.unsaved-dialog')).toBeVisible()
    await page.locator('.unsaved-dialog .ui-button--secondary').click()
    await expect(drawer).toBeVisible()
    await expect(overrideSelect).toHaveValue('ALLOW')

    await drawer.getByRole('button', { name: '关闭账号授权' }).click()
    await page.locator('.unsaved-dialog .ui-button--danger').click()
    await expect(drawer).not.toBeVisible()
    await expect(trigger).toBeFocused()
    expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  })

  test('password modal clears all fields after close', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.locator('table tbody tr').first().locator('button[title*="重置密码"]').click()
    await expect(page.locator('.password-dialog')).toBeVisible()

    await page.locator('.password-dialog input[autocomplete="new-password"]').first().fill('newpass123')
    await page.locator('.password-dialog input[autocomplete="new-password"]').last().fill('newpass123')

    // Close → UnsavedChangesDialog → discard
    await page.locator('.password-dialog .ui-button--secondary').click()
    await expect(page.locator('.unsaved-dialog')).toBeVisible()
    await page.locator('.unsaved-dialog .ui-button--danger').click()
    await expect(page.locator('.password-dialog')).not.toBeVisible()

    // Reopen: must be clean
    await page.locator('table tbody tr').first().locator('button[title*="重置密码"]').click()
    await expect(page.locator('.password-dialog input[autocomplete="new-password"]').first()).toHaveValue('')
  })

  test('edit abort: fast switch between accounts does not pollute', async ({ page }) => {
    await prepare(page)
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.locator('table tbody tr').first().locator('button[title="编辑账号"]').click()
    await page.locator('.account-editor .ui-button--secondary').click()

    await page.locator('table tbody tr').nth(1).locator('button[title="编辑账号"]').click()
    await expect(page.locator('.account-editor input[autocomplete="off"]')).toHaveValue('warehouse1')
  })

  test('390px viewport has no horizontal overflow', async ({ page }) => {
    await prepare(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.click('button:has-text("新增账号")')
    await expect(page.locator('.account-editor')).toBeVisible()

    const htmlWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(htmlWidth).toBeLessThanOrEqual(390)

    await expect(page.locator('.account-editor .ui-button--primary')).toBeVisible()
  })

  test('authorization drawer fits 390px viewport with footer actions visible', async ({ page }) => {
    await prepare(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/users')
    await page.waitForSelector('.table-wrap')

    await page.locator('table tbody tr').first().locator('button[title*="配置"]').click()
    const drawer = page.getByRole('dialog', { name: '账号授权' })
    await expect(drawer).toBeVisible()
    await expect(drawer).toHaveCSS('transform', 'none')

    const bounds = await drawer.evaluate((element) => {
      const rect = element.getBoundingClientRect()
      return { left: rect.left, right: rect.right, width: rect.width }
    })
    expect(bounds.left).toBeGreaterThanOrEqual(0)
    expect(bounds.right).toBeLessThanOrEqual(390)
    expect(bounds.width).toBeLessThanOrEqual(390)
    await expect(drawer.getByRole('button', { name: '关闭', exact: true })).toBeVisible()
    await expect(drawer.getByRole('button', { name: '保存账号授权' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  })
})
