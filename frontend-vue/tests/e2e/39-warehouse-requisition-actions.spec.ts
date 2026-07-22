import { expect, test, type Page, type Route } from '@playwright/test'

const session = {
  id: 39,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试仓库管理员',
  role: 'WAREHOUSE',
  roleLabel: '仓库管理员',
  storeScope: ['all'],
  permissions: [
    'warehouse.read',
    'warehouse.central.read',
    'warehouse.requisition.process',
    'warehouse.requisition.review',
  ],
  dataScopes: {
    WAREHOUSE: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1'] },
  },
  dataScope: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1'] },
  defaultWorkspace: '/warehouse',
  permissionVersion: 1,
}

const warehouse = {
  id: 1,
  code: 'CENTRAL-01',
  name: '测试总仓',
  type: 'CENTRAL',
  active: true,
  storeSupplyAllowed: true,
}

function requisition(id: string, status: 'SUBMITTED' | 'APPROVED') {
  return {
    id,
    storeId: 'rg1',
    storeName: '测试门店',
    warehouseId: 1,
    warehouseName: warehouse.name,
    status,
    totalAmount: 100,
    note: id,
    submittedAt: '2026-07-22 10:00',
    lines: [{
      itemId: 1,
      itemName: '测试物料',
      unit: '箱',
      requestedQuantity: 2,
      approvedQuantity: status === 'APPROVED' ? 2 : 0,
      shippedQuantity: 0,
      unitPrice: 50,
      amount: 100,
    }],
  }
}

function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, code: 'OK', message: 'OK', data }),
  }
}

async function fulfillApi(route: Route) {
  const path = new URL(route.request().url()).pathname
  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/warehouse/warehouses') return route.fulfill(ok([warehouse]))
  if (path === '/api/warehouse/overview') {
    return route.fulfill(ok({
      warehouse,
      summary: {
        itemCount: 0,
        lowStockCount: 0,
        expiringCount: 0,
        overstockCount: 0,
        pendingRequisitionCount: 2,
        pendingReceiptCount: 0,
        pendingPurchaseCount: 0,
        stockValue: 0,
        inTransitQuantity: 0,
      },
      alerts: [],
      items: [],
      requisitions: [
        requisition('REQ-SUBMITTED', 'SUBMITTED'),
        requisition('REQ-APPROVED', 'APPROVED'),
      ],
      stockBatches: [],
      movements: [],
    }))
  }
  return route.fulfill(ok([]))
}

async function prepare(page: Page) {
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'REQUISITION-ACTIONS-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, fulfillApi)
}

test('approved requisition can only ship while submitted requisition can approve or reject', async ({ page }) => {
  await prepare(page)
  await page.goto('/warehouse/requests')

  const submitted = page.getByRole('row').filter({ hasText: 'REQ-SUBMITTED' })
  await expect(submitted.getByRole('button', { name: '审核通过', exact: true })).toBeVisible()
  await expect(submitted.getByRole('button', { name: '驳回', exact: true })).toBeVisible()
  await expect(submitted.getByRole('button', { name: '发货出库', exact: true })).toHaveCount(0)

  const approved = page.getByRole('row').filter({ hasText: 'REQ-APPROVED' })
  await expect(approved.getByRole('button', { name: '发货出库', exact: true })).toBeVisible()
  await expect(approved.getByRole('button', { name: '审核通过', exact: true })).toHaveCount(0)
  await expect(approved.getByRole('button', { name: '驳回', exact: true })).toHaveCount(0)
})
