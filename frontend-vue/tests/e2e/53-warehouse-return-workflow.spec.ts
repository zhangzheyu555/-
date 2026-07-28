import { expect, test, type Page, type Route } from '@playwright/test'

const session = {
  id: 8,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '仓库管理员',
  role: 'WAREHOUSE',
  roleLabel: '仓库管理员',
  storeScope: [],
  permissions: [
    'warehouse.read',
    'warehouse.central.manage',
    'warehouse.requisition.process',
  ],
  dataScopes: {
    STORE: { mode: 'NONE', storeIds: [], warehouseIds: [] },
    WAREHOUSE: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1', '2'] },
  },
  dataScope: { mode: 'NONE', storeIds: [], warehouseIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/warehouse',
  permissionVersion: 1,
}

const warehouses = [
  {
    id: 1,
    code: 'JZ-CENTRAL',
    name: '荆州总仓',
    type: 'CENTRAL',
    regionCode: 'JINGZHOU',
    parentWarehouseId: null,
    parentWarehouseName: null,
    externalPurchaseAllowed: true,
    storeSupplyAllowed: true,
    enabled: true,
    canRead: true,
  },
  {
    id: 2,
    code: 'SD-REGIONAL',
    name: '山东分仓',
    type: 'REGIONAL',
    regionCode: 'SHANDONG',
    parentWarehouseId: 1,
    parentWarehouseName: '荆州总仓',
    externalPurchaseAllowed: false,
    storeSupplyAllowed: true,
    enabled: true,
    canRead: true,
  },
]

function returnOrder(warehouseId: number, status: string) {
  const central = warehouseId === 1
  return {
    id: central ? 'PSTH260728162857D06' : 'PSTH-SD-001',
    returnNo: central ? 'PSTH260728162857D06' : 'PSTH-SD-001',
    sourceRequisitionId: central ? 'REQ1784869683093-26675d' : 'REQ-SD-001',
    sourceDeliveryId: null,
    returnStoreId: central ? 'rg1' : 'bw1',
    returnStoreName: central ? '荆州之星店' : '滨江万达店',
    receiveWarehouseId: warehouseId,
    receiveWarehouseName: central ? '荆州总仓' : '山东分仓',
    receiveDepartment: central ? '荆州总仓' : '山东分仓',
    status,
    statusLabel: status === 'SUBMITTED'
      ? '待仓库审核'
      : status === 'APPROVED' ? '待退货入库' : '仓库已收货',
    totalAmount: 25,
    reason: '门店配送退货',
    note: '',
    returnDate: '2026-07-28',
    createdAt: '2026-07-28 16:28',
    lineCount: 1,
    attachmentCount: 0,
    lines: [{
      id: warehouseId,
      itemId: 11,
      itemName: central ? '凤爪（小胡鸭）' : '鲜牛奶',
      quantity: 10,
      unit: '袋',
      unitPrice: 2.5,
      returnPrice: 2.5,
      amount: 25,
      reason: '门店配送退货',
    }],
  }
}

function overview(warehouseId: number) {
  return {
    warehouse: warehouses.find((row) => row.id === warehouseId),
    summary: {
      itemCount: 0,
      lowStockCount: 0,
      expiringCount: 0,
      overstockCount: 0,
      pendingRequisitionCount: 0,
      pendingReceiptCount: 0,
      pendingPurchaseCount: 0,
      stockValue: 0,
      inTransitQuantity: 0,
    },
    alerts: [],
    items: [],
    requisitions: [],
    stockBatches: [],
    movements: [],
    suppliers: [],
    purchaseOrders: [],
  }
}

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: true, data }),
  }
}

async function prepare(page: Page, options: { failReview?: boolean } = {}) {
  let centralStatus = 'SUBMITTED'
  const returnWarehouseQueries: string[] = []
  let movementQueryCount = 0

  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'WAREHOUSE-RETURN-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/auth/me') return route.fulfill(ok(session))
    if (path === '/api/warehouse/warehouses') return route.fulfill(ok(warehouses))
    if (path === '/api/warehouse/overview') {
      return route.fulfill(ok(overview(Number(url.searchParams.get('warehouseId') || 1))))
    }
    if (path === '/api/warehouse/item-categories') return route.fulfill(ok([]))
    if (path === '/api/warehouse/transfers') return route.fulfill(ok([]))
    if (path === '/api/warehouse/transfers/context') {
      return route.fulfill(ok({
        currentWarehouse: null,
        mode: 'NONE',
        workbenchLabel: '',
        routes: [],
        todos: {},
      }))
    }
    if (path === '/api/warehouse/movements/filter-options') {
      return route.fulfill(ok({ stores: [], items: [] }))
    }
    if (path === '/api/warehouse/movements/query') {
      movementQueryCount += 1
      return route.fulfill(ok({
        rows: [],
        total: 0,
        page: 1,
        pageSize: 50,
        totalIn: 0,
        totalOut: 0,
        netChange: 0,
      }))
    }
    if (path === '/api/warehouse/returns' && request.method() === 'GET') {
      const warehouseId = url.searchParams.get('warehouseId') || ''
      returnWarehouseQueries.push(warehouseId)
      const selectedId = Number(warehouseId || 1)
      return route.fulfill(ok([
        returnOrder(selectedId, selectedId === 1 ? centralStatus : 'SUBMITTED'),
      ]))
    }
    if (path === '/api/warehouse/returns/PSTH260728162857D06/review') {
      if (options.failReview) {
        return route.fulfill({
          status: 409,
          contentType: 'application/json; charset=UTF-8',
          body: JSON.stringify({
            success: false,
            message: '退货单状态已变更，请刷新后重试。',
          }),
        })
      }
      centralStatus = 'APPROVED'
      return route.fulfill(ok(returnOrder(1, centralStatus)))
    }
    if (path === '/api/warehouse/returns/PSTH260728162857D06/receive') {
      centralStatus = 'RECEIVED'
      return route.fulfill(ok(returnOrder(1, centralStatus)))
    }
    return route.fulfill(ok([]))
  })

  return {
    returnWarehouseQueries,
    movementQueryCount: () => movementQueryCount,
  }
}

test('仓库在独立退货工作区按当前仓库处理店长提交的配送退货', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '完整状态流仅在桌面项目执行')
  const log = await prepare(page)

  await page.goto('/warehouse/returns')

  const returnNavigation = page.getByRole('link', { name: /配送退货/ })
  await expect(returnNavigation).toHaveAttribute('aria-current', 'location')
  await expect(page.getByRole('heading', { name: '配送退货单', exact: true })).toBeVisible()
  await expect(page.getByText('PSTH260728162857D06')).toBeVisible()
  await expect(page.getByText('荆州之星店')).toBeVisible()
  await expect(page.getByRole('heading', { name: '出入库查询' })).toHaveCount(0)
  await expect.poll(() => log.returnWarehouseQueries.at(-1)).toBe('1')
  expect(log.movementQueryCount()).toBe(0)

  await page.getByRole('button', { name: '审核通过' }).click()
  await expect(page.getByText('待退货入库')).toBeVisible()
  await page.getByRole('button', { name: '确认退货入库' }).click()
  const receiveDialog = page.getByRole('alertdialog', { name: '确认退货入库' })
  await expect(receiveDialog).toBeVisible()
  await receiveDialog.getByRole('button', { name: '确认入库' }).click()
  await expect(page.getByText('仓库已收货')).toBeVisible()

  await page.getByLabel('当前仓库').selectOption('2')
  await expect.poll(() => log.returnWarehouseQueries.at(-1)).toBe('2')
  await expect(page.getByText('PSTH-SD-001')).toBeVisible()
  await expect(page.getByText('PSTH260728162857D06')).toHaveCount(0)
})

test('配送退货工作区在小屏上保留状态与处理按钮', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'iphone-390', '移动端布局仅在 390px 项目执行')
  await prepare(page)
  await page.goto('/warehouse/returns')

  await expect(page.getByText('PSTH260728162857D06')).toBeVisible()
  const approveButton = page.getByRole('button', { name: '审核通过' })
  await approveButton.scrollIntoViewIfNeeded()
  await expect(approveButton).toBeInViewport()
  const viewport = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1)
})

test('仓库审核失败时用弹窗说明原因并保留待处理状态', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '错误交互仅在桌面项目执行')
  await prepare(page, { failReview: true })
  await page.goto('/warehouse/returns')

  await page.getByRole('button', { name: '审核通过' }).click()

  const errorDialog = page.getByRole('alertdialog', { name: '退货单审核失败' })
  await expect(errorDialog).toContainText('退货单状态已变更，请刷新后重试。')
  await expect(page.getByText('待仓库审核')).toBeVisible()
})
