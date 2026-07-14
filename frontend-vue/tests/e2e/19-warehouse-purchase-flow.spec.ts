import { expect, test, type Page, type Route } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

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
    canPurchase: true,
    canApproveTransfer: true,
    canShipTransfer: true,
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
    canRequestTransfer: true,
    canReceiveTransfer: true,
  },
]

const session = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [
    'warehouse.read',
    'warehouse.purchase',
    'warehouse.transfer.request',
    'warehouse.transfer.approve',
    'warehouse.transfer.ship',
    'warehouse.transfer.receive',
    'warehouse.requisition.process',
    'warehouse.configure',
  ],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    WAREHOUSE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 5,
}

type PurchaseStatus = 'DRAFT' | 'ORDERED' | 'RECEIVED'

interface PurchaseState {
  status: PurchaseStatus | null
  createBodies: Array<Record<string, unknown>>
  approveCalls: number
  receiveBodies: Array<Record<string, unknown>>
  consoleErrors: string[]
}

function purchaseOrder(state: PurchaseState) {
  if (!state.status) return []
  return [{
    id: 'PO-JZ-001',
    supplierId: 31,
    supplierName: '荆州鲜配供应商',
    warehouseId: 1,
    warehouseName: '荆州总仓',
    status: state.status,
    statusLabel: state.status === 'DRAFT' ? '草稿' : state.status === 'ORDERED' ? '已审批' : '已入库',
    totalAmount: 600,
    createdBy: '测试老板',
    createdAt: '2026-07-13T17:30:00',
    receivedAt: state.status === 'RECEIVED' ? '2026-07-13T17:35:00' : undefined,
    lines: [{
      id: 1,
      itemId: 11,
      itemName: '鲜牛奶',
      unit: '箱',
      orderedQuantity: 12,
      receivedQuantity: state.status === 'RECEIVED' ? 12 : 0,
      unitCost: 50,
      amount: 600,
    }],
  }]
}

function overview(warehouseId: number, state: PurchaseState) {
  return {
    warehouse: warehouses.find((warehouse) => warehouse.id === warehouseId),
    summary: {
      itemCount: 1,
      lowStockCount: 0,
      expiringCount: 0,
      pendingRequisitionCount: 0,
      pendingReceiptCount: 0,
      pendingPurchaseCount: state.status && state.status !== 'RECEIVED' ? 1 : 0,
      stockValue: warehouseId === 1 ? 1200 : 0,
      inTransitQuantity: 0,
    },
    alerts: [],
    items: [{
      id: 11,
      code: 'MILK-01',
      name: '鲜牛奶',
      unit: '箱',
      stockQuantity: warehouseId === 1 ? 20 : 0,
      storeStockQuantity: 2,
      warehouseAvailableQuantity: warehouseId === 1 ? 18 : 0,
      unitPrice: 50,
      stockStatus: '正常',
      alertLevel: 'NORMAL',
      alertText: '',
      active: true,
    }],
    suppliers: [{ id: 31, name: '荆州鲜配供应商', active: true }],
    purchaseOrders: warehouseId === 1 ? purchaseOrder(state) : [],
    requisitions: [],
    movements: [],
    stockBatches: state.status === 'RECEIVED' && warehouseId === 1
      ? [{
          id: 91,
          itemId: 11,
          itemName: '鲜牛奶',
          warehouseId: 1,
          warehouseName: '荆州总仓',
          unit: '箱',
          batchNo: 'JZ-BATCH-001',
          receivedDate: '2026-07-13',
          quantity: 12,
          unitCost: 50,
          status: 'ACTIVE',
        }]
      : [],
  }
}

async function prepare(page: Page, state: PurchaseState) {
  page.on('console', (message) => {
    if (message.type() === 'error') state.consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => state.consoleErrors.push(error.message))
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'WAREHOUSE-PURCHASE-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => fulfillApi(route, state))
}

async function fulfillApi(route: Route, state: PurchaseState) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname
  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/warehouse/warehouses') return route.fulfill(ok(warehouses))
  if (path === '/api/warehouse/overview') {
    return route.fulfill(ok(overview(Number(url.searchParams.get('warehouseId') || 1), state)))
  }
  if (path === '/api/warehouse/purchase-orders' && request.method() === 'POST') {
    state.createBodies.push(request.postDataJSON())
    state.status = 'DRAFT'
    return route.fulfill(ok(purchaseOrder(state)[0]))
  }
  if (path === '/api/warehouse/purchase-orders/PO-JZ-001/approve' && request.method() === 'POST') {
    state.approveCalls += 1
    state.status = 'ORDERED'
    return route.fulfill(ok(purchaseOrder(state)[0]))
  }
  if (path === '/api/warehouse/purchase-orders/PO-JZ-001/receive' && request.method() === 'POST') {
    state.receiveBodies.push(request.postDataJSON())
    state.status = 'RECEIVED'
    return route.fulfill(ok(purchaseOrder(state)[0]))
  }
  if (
    path === '/api/warehouse/transfers'
    || path === '/api/warehouse/item-categories'
    || path === '/api/warehouse/returns'
  ) {
    return route.fulfill(ok([]))
  }
  return route.fulfill(ok([]))
}

function newState(): PurchaseState {
  return { status: null, createBodies: [], approveCalls: 0, receiveBodies: [], consoleErrors: [] }
}

test('荆州总仓外部采购按草稿、审批、按单入库顺序完成', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.goto('/warehouse/purchase')

  const form = page.locator('.receive-form')
  await expect(form.getByText('采购仓：荆州总仓')).toBeVisible()
  await form.getByLabel('供应商').selectOption('31')
  await form.getByLabel('商品').selectOption('11')
  await form.getByLabel('采购数量').fill('12')
  await form.getByLabel('采购单价').fill('50')
  await form.getByLabel('备注').fill('荆州补货测试')
  await form.getByRole('button', { name: '创建采购草稿' }).click()

  await expect.poll(() => state.createBodies.length).toBe(1)
  expect(state.createBodies[0]).toMatchObject({
    warehouseId: 1,
    supplierId: 31,
    note: '荆州补货测试',
    lines: [{ itemId: 11, orderedQuantity: 12, unitCost: 50 }],
  })
  expect(state.createBodies[0]?.clientRequestId).toMatch(/^purchase-/)
  await expect(page.getByText('PO-JZ-001', { exact: true })).toBeVisible()
  await expect(page.getByText('草稿', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: '审批采购单' }).click()
  await expect.poll(() => state.approveCalls).toBe(1)
  await expect(page.getByText('已审批', { exact: true })).toBeVisible()

  await page.getByLabel('鲜牛奶批次号').fill('JZ-BATCH-001')
  await page.getByRole('button', { name: '按单确认入库' }).click()
  await expect.poll(() => state.receiveBodies.length).toBe(1)
  expect(state.receiveBodies[0]?.clientRequestId).toMatch(/^purchase-receive-/)
  expect(state.receiveBodies[0]).toMatchObject({
    lines: [{ itemId: 11, batchNo: 'JZ-BATCH-001', quantity: 12 }],
  })
  await expect(page.getByText('已入库', { exact: true })).toBeVisible()
  await expect(page.getByText('JZ-BATCH-001', { exact: true })).toBeVisible()
  await page.screenshot({ path: '../output/playwright/warehouse-purchase-flow.png', fullPage: true })
  expect(state.consoleErrors).toEqual([])
})

test('山东分仓页面不提供外部采购入口', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.goto('/warehouse/shandong')

  const pageRoot = page.locator('.warehouse-page')
  await expect(pageRoot.getByRole('link', { name: '向荆州总仓申请补货', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '外部采购', exact: true })).toHaveCount(0)
  await expect(pageRoot.getByRole('link', { name: '外部采购入库', exact: true })).toHaveCount(0)
  await expect(pageRoot.getByRole('button', { name: '创建采购草稿' })).toHaveCount(0)
  expect(state.createBodies).toEqual([])
  expect(state.consoleErrors).toEqual([])
})
