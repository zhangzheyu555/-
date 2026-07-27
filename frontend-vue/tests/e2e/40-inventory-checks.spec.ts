import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow, seedAuth } from './auth.setup'

type MockRole = 'BOSS' | 'FINANCE' | 'SUPERVISOR' | 'WAREHOUSE' | 'STORE_MANAGER'

interface SavedPayload {
  storeId?: string
  checkDate?: string
  lines?: Array<{
    itemCode?: string
    countedQuantity?: number
  }>
}

interface MockOptions {
  managerBrandName?: string
}

const inventoryItems = Array.from({ length: 97 }, (_, index) => {
  const position = index + 1
  return {
    id: position,
    itemCode: `PD-E2E-${String(position).padStart(3, '0')}`,
    itemName: `盘存物料 ${position}`,
    category: position <= 48 ? '耗材' : position <= 82 ? '原料' : '水果',
    spec: `测试规格 ${position}`,
    unit: position % 2 ? '个' : '包',
    packageQuantity: 10,
    packagePrice: 20,
    unitPrice: 2,
    priced: true,
    sortOrder: position,
  }
})

function mockUser(role: MockRole, options: MockOptions = {}) {
  const manager = role === 'STORE_MANAGER'
  const managerBrandName = options.managerBrandName || '茹菓'
  const managerStoreId = managerBrandName === '茹菓' || managerBrandName === '茹果'
    ? 'STORE-01'
    : 'STORE-NON-RUGUO'
  const roleNames: Record<MockRole, string> = {
    BOSS: '老板（系统管理员）',
    FINANCE: '财务',
    SUPERVISOR: '督导',
    WAREHOUSE: '仓库管理员',
    STORE_MANAGER: '店长',
  }
  const defaultWorkspaces: Record<MockRole, string> = {
    BOSS: '/boss',
    FINANCE: '/finance',
    SUPERVISOR: '/operations',
    WAREHOUSE: '/warehouse',
    STORE_MANAGER: '/store',
  }
  const ownStoreScope = { mode: 'OWN_STORE', storeIds: [managerStoreId], warehouseIds: [] }
  const allScope = { mode: 'ALL', storeIds: [], warehouseIds: [] }
  return {
    id: role === 'BOSS' ? 1 : manager ? 2 : 3,
    tenantId: 1,
    tenantName: '盘存测试租户',
    displayName: `测试${roleNames[role]}`,
    role,
    roleLabel: roleNames[role],
    storeScope: manager ? [managerStoreId] : ['all'],
    permissions: role === 'BOSS'
      ? []
      : manager
        ? ['inventory.read', 'inventory.manage', 'store.read']
        : ['inventory.read', 'inventory.review', 'store.read'],
    dataScopes: {
      STORE: manager ? ownStoreScope : allScope,
      WAREHOUSE: manager ? ownStoreScope : allScope,
    },
    dataScope: manager ? ownStoreScope : allScope,
    boundStoreId: manager ? managerStoreId : null,
    boundStoreName: manager ? (managerStoreId === 'STORE-01' ? '测试茹菓一店' : '测试非茹菓门店') : null,
    brandId: manager ? (managerStoreId === 'STORE-01' ? 1 : 2) : null,
    brandName: manager ? managerBrandName : null,
    defaultWorkspace: defaultWorkspaces[role],
    permissionVersion: 1,
  }
}

async function fulfillJson(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data }),
  })
}

async function installInventoryMocks(page: Page, role: MockRole, options: MockOptions = {}) {
  const user = mockUser(role, options)
  let savedPayload: SavedPayload | null = null
  let exportRequestCount = 0
  let reviewRequestCount = 0
  const history = role !== 'STORE_MANAGER'
    ? [
      {
        id: 7001,
        checkNo: 'PDC-E2E-7001',
        storeId: 'STORE-02',
        storeName: '测试茹菓二店',
        checkDate: '2026-06-30',
        status: 'SUBMITTED',
        statusLabel: '已提交',
        totalAmount: 1288.5,
        updatedAt: '2026-07-01 09:30:00',
        lines: [],
      },
      {
        id: 7002,
        checkNo: 'PDC-E2E-7002',
        storeId: 'STORE-LUCKIN',
        storeName: '测试瑞幸门店',
        checkDate: '2026-06-30',
        status: 'SUBMITTED',
        statusLabel: '已提交',
        totalAmount: 288.5,
        updatedAt: '2026-07-01 09:30:00',
        lines: [],
      },
      {
        id: 7003,
        checkNo: 'PDC-E2E-7003',
        storeId: 'STORE-BAWANG',
        storeName: '测试霸王茶姬门店',
        checkDate: '2026-06-30',
        status: 'SUBMITTED',
        statusLabel: '已提交',
        totalAmount: 388.5,
        updatedAt: '2026-07-01 09:30:00',
        lines: [],
      },
    ]
    : [{
        id: 7004,
        checkNo: 'PDC-E2E-7004',
        storeId: user.boundStoreId,
        storeName: user.boundStoreName,
        checkDate: '2026-06-30',
        status: 'SUBMITTED',
        statusLabel: '已提交',
        totalAmount: 88.5,
        updatedAt: '2026-07-01 09:30:00',
        lines: [],
      }]

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/auth/me') {
      await fulfillJson(route, user)
      return
    }
    if (path === '/api/operations/inventory-items') {
      await fulfillJson(route, inventoryItems)
      return
    }
    if (path === '/api/stores') {
      await fulfillJson(route, [
        { id: 'STORE-01', code: 'STORE-01', name: '测试茹菓一店', brandId: 1, brandName: '茹果' },
        { id: 'STORE-02', code: 'STORE-02', name: '测试茹菓二店', brandId: 1, brandName: '茹菓' },
        { id: 'STORE-LUCKIN', code: 'STORE-LUCKIN', name: '测试瑞幸门店', brandId: 2, brandName: '瑞幸咖啡' },
        { id: 'STORE-BAWANG', code: 'STORE-BAWANG', name: '测试霸王茶姬门店', brandId: 3, brandName: '霸王茶姬' },
      ])
      return
    }
    if (/^\/api\/operations\/inventory-checks\/\d+\/export\.xlsx$/.test(path)) {
      exportRequestCount += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        headers: { 'Content-Disposition': "attachment; filename*=UTF-8''inventory-e2e.xlsx" },
        body: 'e2e inventory workbook',
      })
      return
    }
    if (/^\/api\/operations\/inventory-checks\/\d+\/review$/.test(path)) {
      reviewRequestCount += 1
      const reviewed = Object.assign(history[0], {
        status: 'REVIEWED',
        statusLabel: '已复核',
        reviewedBy: user.id,
        reviewedByName: user.displayName,
        reviewedByRole: user.role,
        reviewedByRoleLabel: user.role === 'BOSS' ? '老板' : user.role === 'WAREHOUSE' ? '仓管' : user.roleLabel,
        reviewedAt: '2026-07-27 12:30:00',
        updatedAt: '2026-07-27 12:30:00',
      })
      await fulfillJson(route, reviewed)
      return
    }
    if (path === '/api/operations/inventory-checks' && request.method() === 'POST') {
      savedPayload = request.postDataJSON() as SavedPayload
      const submitted = {
        id: 990001,
        checkNo: 'E2E-NO-WRITE',
        storeId: savedPayload.storeId,
        storeName: '测试一店',
        checkDate: savedPayload.checkDate,
        status: 'SUBMITTED',
        statusLabel: '已提交',
        totalAmount: 4,
        updatedAt: '2026-07-27 12:00:00',
        lines: [],
      }
      history.unshift(submitted)
      await fulfillJson(route, submitted)
      return
    }
    if (path === '/api/operations/inventory-checks') {
      await fulfillJson(route, history)
      return
    }
    const detailMatch = path.match(/^\/api\/operations\/inventory-checks\/(\d+)$/)
    if (detailMatch) {
      const detail = history.find((check) => check.id === Number(detailMatch[1]))
      await fulfillJson(route, detail ? { ...detail, lines: [] } : null)
      return
    }
    await fulfillJson(route, [])
  })

  await seedAuth(page, { token: `e2e-${role.toLowerCase()}`, user })
  return {
    savedPayload: () => savedPayload,
    exportRequestCount: () => exportRequestCount,
    reviewRequestCount: () => reviewRequestCount,
  }
}

test.describe('店铺盘存', () => {
  test('店长搜索后仍提交 API 返回的完整 97 项物料，并直接进入已提交状态', async ({ page }) => {
    await page.setViewportSize({ width: 1365, height: 900 })
    const mocks = await installInventoryMocks(page, 'STORE_MANAGER')

    await page.goto('/inventory-checks')
    await page.waitForLoadState('networkidle')
    await expect(page.getByRole('heading', { name: '本店盘存录入' })).toBeVisible()
    await expect(page.locator('.app-sidebar--desktop')).toContainText('店铺盘存')

    const desktopRows = page.locator('.inventory-entry-table tbody tr')
    await expect(desktopRows).toHaveCount(97)
    const firstPricedRow = desktopRows.first()
    await firstPricedRow.locator('input[type="number"]').fill('2')
    await expect(firstPricedRow.locator('.amount-cell')).not.toHaveText('¥0.00')

    const firstCode = (await desktopRows.first().locator('td').first().locator('small').innerText()).split(' · ')[0]
    await page.getByRole('searchbox', { name: '搜索盘存物料' }).fill(firstCode)
    await expect(page.locator('.filter-result')).toContainText('显示 1 / 97 项')
    const statusOptions = await page.getByLabel('盘存状态').locator('option').allTextContents()
    expect(statusOptions).toEqual(['全部状态', '已提交', '已复核'])
    await expect(page.getByText('草稿', { exact: true })).toHaveCount(0)
    await expect(page.getByText('已作废', { exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: '提交盘存' }).click()

    await expect.poll(() => mocks.savedPayload()?.lines?.length || 0).toBe(97)
    expect(mocks.savedPayload()?.lines?.map((line) => line.itemCode)).toEqual(inventoryItems.map((item) => item.itemCode))
    await expect(page.getByText(/盘存已提交/)).toBeVisible()
    await expect(page.locator('.history-table')).toContainText('已提交')
    await expect(page.locator('.history-table').getByRole('button', { name: '导出 Excel' })).toHaveCount(0)
    await expect(page.locator('.history-table').getByRole('button', { name: '复核' })).toHaveCount(0)
  })

  test('老板可复核并导出茹菓门店历史，盘存单记录审核负责人和职务', async ({ page }) => {
    await page.setViewportSize({ width: 1365, height: 900 })
    const mocks = await installInventoryMocks(page, 'BOSS')
    await page.goto('/inventory-checks')
    await page.waitForLoadState('networkidle')

    await expect(page.getByRole('heading', { name: '店铺盘存' })).toBeVisible()
    await expect(page.locator('.app-sidebar--desktop')).toContainText('店铺盘存')
    await expect(page.getByText(/当前为查看与导出/)).toHaveCount(0)
    await expect(page.getByText(/暂不盘存/)).toHaveCount(0)
    await expect(page.locator('.excluded-items-panel')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '设置价格' })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '盘存历史' })).toBeVisible()
    await expect(page.getByRole('heading', { name: '本店盘存录入' })).toHaveCount(0)
    await expect(page.getByLabel('盘存门店')).toBeVisible()
    await expect(page.getByLabel('盘存门店')).toContainText('测试茹菓一店')
    await expect(page.getByLabel('盘存门店')).toContainText('测试茹菓二店')
    await expect(page.getByLabel('盘存门店')).not.toContainText('测试瑞幸门店')
    await expect(page.getByLabel('盘存门店')).not.toContainText('测试霸王茶姬门店')
    await expect(page.locator('.history-table')).toContainText('测试茹菓二店')
    await expect(page.locator('.history-table')).not.toContainText('测试瑞幸门店')
    await expect(page.locator('.history-table')).not.toContainText('测试霸王茶姬门店')
    const exportButton = page.locator('.history-table').getByRole('button', { name: '导出 Excel' })
    await expect(exportButton).toBeVisible()
    await page.locator('.history-table').getByRole('button', { name: '复核' }).click()
    await expect(page.getByText(/将记录当前账号为审核负责人及其职务/)).toBeVisible()
    await page.getByRole('button', { name: '确认复核' }).click()
    await expect.poll(mocks.reviewRequestCount).toBe(1)
    await expect(page.locator('.history-table')).toContainText('测试老板（系统管理员）（老板）')
    await expect(page.locator('.history-table')).toContainText('审核时间：2026-07-27 12:30:00')
    await exportButton.click()
    await expect.poll(mocks.exportRequestCount).toBe(1)
    await expect(page.getByText(/盘存 Excel 已开始下载/)).toBeVisible()
  })

  for (const role of ['FINANCE', 'SUPERVISOR', 'WAREHOUSE'] as const) {
    test(`${role} 可查看、复核并导出茹菓盘存，但不能录入`, async ({ page }) => {
      await page.setViewportSize({ width: 1365, height: 900 })
      const mocks = await installInventoryMocks(page, role)
      await page.goto('/inventory-checks')
      await page.waitForLoadState('networkidle')

      await expect(page.getByRole('heading', { name: '店铺盘存' })).toBeVisible()
      await expect(page.locator('.app-sidebar--desktop')).toContainText('店铺盘存')
      await expect(page.getByText(/当前可查看、导出并复核/)).toBeVisible()
      await expect(page.getByText('查看权限范围内的茹菓门店盘存记录和金额，可导出 Excel，并可复核已提交记录。')).toBeVisible()
      await expect(page.getByRole('heading', { name: '本店盘存录入' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: '提交盘存' })).toHaveCount(0)
      await expect(page.locator('.history-table').getByRole('button', { name: '查看明细' })).toBeVisible()
      await expect(page.getByLabel('盘存门店')).toBeVisible()
      await expect(page.getByLabel('盘存门店')).toContainText('测试茹菓一店')
      await expect(page.getByLabel('盘存门店')).toContainText('测试茹菓二店')
      await expect(page.getByLabel('盘存门店')).not.toContainText('测试瑞幸门店')
      await expect(page.getByLabel('盘存门店')).not.toContainText('测试霸王茶姬门店')
      await expect(page.locator('.history-table')).not.toContainText('测试瑞幸门店')
      await expect(page.locator('.history-table')).not.toContainText('测试霸王茶姬门店')
      const exportButton = page.locator('.history-table').getByRole('button', { name: '导出 Excel' })
      await expect(exportButton).toBeVisible()
      await page.locator('.history-table').getByRole('button', { name: '复核' }).click()
      await page.getByRole('button', { name: '确认复核' }).click()
      await expect.poll(mocks.reviewRequestCount).toBe(1)
      await expect(page.locator('.history-table')).toContainText(`测试${role === 'FINANCE' ? '财务' : role === 'SUPERVISOR' ? '督导' : '仓库管理员'}`)
      await expect(page.locator('.history-table')).toContainText(role === 'FINANCE' ? '（财务）' : role === 'SUPERVISOR' ? '（督导）' : '（仓管）')
      await exportButton.click()
      await expect.poll(mocks.exportRequestCount).toBe(1)
      await expect(page.getByText(/盘存 Excel 已开始下载/)).toBeVisible()
    })
  }

  test('非茹菓品牌店长不显示录入表单，并明确提示适用范围', async ({ page }) => {
    await page.setViewportSize({ width: 1365, height: 900 })
    await installInventoryMocks(page, 'STORE_MANAGER', { managerBrandName: '瑞幸咖啡' })
    await page.goto('/inventory-checks')
    await page.waitForLoadState('networkidle')

    await expect(page.locator('.message--info')).toContainText('店铺盘存仅适用于茹菓门店')
    await expect(page.getByRole('heading', { name: '本店盘存录入' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '提交盘存' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '导出 Excel' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '复核' })).toHaveCount(0)
  })

  test('390px 使用移动卡片且没有整页横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await installInventoryMocks(page, 'STORE_MANAGER')
    await page.goto('/inventory-checks')
    await page.waitForLoadState('networkidle')

    await expect(page.locator('.inventory-entry-cards article').first()).toBeVisible()
    await expect(page.locator('.inventory-entry-table')).not.toBeVisible()
    await expectNoWholePageOverflow(page, '店铺盘存 390px')
  })
})
