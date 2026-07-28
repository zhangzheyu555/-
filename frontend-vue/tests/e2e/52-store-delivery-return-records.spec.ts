import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test'

const storeManagerSession = {
  id: 2201,
  tenantId: 1,
  tenantName: 'TEST 租户',
  displayName: '测试店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['STORE-1'],
  permissions: ['warehouse.store.read', 'warehouse.requisition.create', 'warehouse.requisition.receive'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['STORE-1'] },
    WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['STORE-1'] },
  },
  dataScope: { mode: 'OWN_STORE', storeIds: ['STORE-1'] },
  boundStoreId: 'STORE-1',
  boundStoreName: '测试门店',
  brandId: 1,
  brandName: '测试品牌',
  defaultWorkspace: '/store',
  permissionVersion: 1,
}

const supplyWarehouse = {
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
}

const receivedRequisition = {
  id: 'REQ-RECEIVED-001',
  storeId: 'STORE-1',
  storeName: '测试门店',
  warehouseId: 1,
  warehouseName: '荆州总仓',
  status: 'RECEIVED',
  statusLabel: '门店已收货',
  totalAmount: 150,
  submittedAt: '2026-07-27 09:00',
  shippedAt: '2026-07-27 10:00',
  receivedAt: '2026-07-27 11:00',
  lines: [{
    itemId: 11,
    itemName: '鲜牛奶',
    requestedQuantity: 3,
    approvedQuantity: 3,
    shippedQuantity: 3,
    unit: '箱',
  }],
}

const shippedRequisition = {
  ...receivedRequisition,
  id: 'REQ-SHIPPED-002',
  status: 'SHIPPED',
  statusLabel: '待确认收货',
  receivedAt: undefined,
}

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: status < 400, data }),
  })
}

function warehouseOverview(requisitions: Array<Record<string, unknown>> = [shippedRequisition, receivedRequisition]) {
  return {
    warehouse: supplyWarehouse,
    summary: {
      itemCount: 1,
      lowStockCount: 0,
      expiringCount: 0,
      overstockCount: 0,
      pendingRequisitionCount: 0,
      pendingReceiptCount: 1,
      pendingPurchaseCount: 0,
      stockValue: 1200,
      inTransitQuantity: 3,
    },
    alerts: [],
    items: [{
      id: 11,
      code: 'MILK-01',
      name: '鲜牛奶',
      unit: '箱',
      stockUnit: '箱',
      stockQuantity: 20,
      storeStockQuantity: 5,
      warehouseAvailableQuantity: 18,
      unitPrice: 50,
      stockStatus: '正常',
      alertLevel: 'NORMAL',
      alertText: '',
      active: true,
    }],
    requisitions,
    stockBatches: [],
    movements: [],
  }
}

interface PrepareOptions {
  requisitions?: Array<Record<string, unknown>>
  returns?: Array<Record<string, unknown>>
}

async function prepare(page: Page, options: PrepareOptions = {}) {
  const createBodies: unknown[] = []
  const returnRows: Array<Record<string, unknown>> = [...(options.returns || [])]
  let downloadRequests = 0
  const consoleErrors: string[] = []

  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(error.message))

  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'STORE-DELIVERY-RETURN-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, storeManagerSession)

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return json(route, storeManagerSession)
    if (pathname === '/api/warehouse/warehouses') return json(route, [supplyWarehouse])
    if (pathname === '/api/warehouse/overview') return json(route, warehouseOverview(options.requisitions))
    if (pathname === '/api/warehouse/item-categories') return json(route, [])
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', name: '测试门店', status: 'ACTIVE' }])
    if (pathname === '/api/warehouse/returns' && request.method() === 'GET') {
      return json(route, returnRows)
    }
    if (pathname === '/api/warehouse/returns' && request.method() === 'POST') {
      const body = request.postDataJSON()
      createBodies.push(body)
      const saved = {
        id: 'RETURN-001',
        returnNo: 'PSTH260728000000001',
        sourceRequisitionId: receivedRequisition.id,
        sourceDeliveryId: 'DELIVERY-001',
        returnStoreId: 'STORE-1',
        returnStoreName: '测试门店',
        receiveWarehouseId: 1,
        receiveWarehouseName: '荆州总仓',
        receiveDepartment: '荆州总仓',
        status: 'SUBMITTED',
        statusLabel: '待仓库审核',
        totalAmount: 0,
        reason: body.reason,
        note: body.note,
        returnDate: body.returnDate,
        lineCount: 1,
        attachmentCount: 0,
        lines: [{
          id: 1,
          itemId: 11,
          itemName: '鲜牛奶',
          quantity: 2,
          unit: '箱',
          unitPrice: 0,
          returnPrice: 0,
          amount: 0,
          reason: body.reason,
        }],
      }
      returnRows.unshift(saved)
      return json(route, saved)
    }
    if (pathname === '/api/warehouse/print/returns/RETURN-001') {
      downloadRequests += 1
      return route.fulfill({
        status: 200,
        contentType: 'application/pdf',
        headers: {
          'Content-Disposition': 'attachment; filename="store-return.pdf"',
        },
        body: '%PDF-1.4\n%%EOF',
      })
    }
    if (pathname === `/api/warehouse/requisitions/${shippedRequisition.id}/receive`) {
      return json(route, null)
    }
    return json(route, [])
  })

  return {
    createBodies,
    getDownloadRequests: () => downloadRequests,
    consoleErrors,
  }
}

test('大量叫货记录不会把配送退货单入口推到页面底部', async ({ page }, testInfo: TestInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '长列表布局仅在 chromium 项目执行')
  const requisitions = Array.from({ length: 20 }, (_, index) => ({
    ...receivedRequisition,
    id: `REQ-HISTORY-${String(index + 1).padStart(3, '0')}`,
  }))
  const existingReturn = {
    id: 'RETURN-HISTORY-001',
    returnNo: 'PSTH260728000000099',
    sourceRequisitionId: requisitions[0]?.id,
    status: 'SUBMITTED',
    statusLabel: '待仓库审核',
    totalAmount: 50,
    reason: '包装破损',
    returnDate: '2026-07-28',
    lines: [{
      id: 1,
      itemId: 11,
      itemName: '鲜牛奶',
      quantity: 1,
      unit: '箱',
    }],
  }
  await prepare(page, { requisitions, returns: [existingReturn] })
  await page.goto('/store/inventory/records')

  const records = page.getByRole('region', { name: '配送与退货记录' })
  const returnTab = records.getByRole('tab', { name: /配送退货单.*1/ })
  await expect(returnTab).toBeVisible()
  await expect(returnTab).toHaveAttribute('aria-selected', 'true')
  const [recordsBox, returnTabBox] = await Promise.all([records.boundingBox(), returnTab.boundingBox()])
  expect((returnTabBox?.y || 0) - (recordsBox?.y || 0), '退货入口应固定在记录卡片顶部').toBeLessThan(220)

  await returnTab.click()
  await expect(records.locator('.store-return-table-wrap').getByText('PSTH260728000000099', { exact: true })).toBeVisible()
  await expect(records.locator('.store-delivery-table-wrap')).toBeHidden()

  await records.getByRole('tab', { name: /叫货与收货.*20/ }).click()
  await expect(records.locator('.store-delivery-table-wrap').getByText('REQ-HISTORY-020', { exact: true })).toBeVisible()
  await expect(records.locator('.store-return-table-wrap')).toBeHidden()
})

test('门店把收货与叫货合并展示并完成配送退货和退货单下载', async ({ page }, testInfo: TestInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '桌面配送退货流程仅在 chromium 项目执行')
  const log = await prepare(page)
  await page.goto('/store/inventory/records')

  const records = page.getByRole('region', { name: '配送与退货记录' })
  const requisitionTable = records.locator('.store-delivery-table-wrap')
  const returnTable = records.locator('.store-return-table-wrap')
  await expect(records).toBeVisible()
  await expect(records.getByRole('heading', { name: '待确认收货', exact: true })).toHaveCount(0)
  await expect(records.getByRole('heading', { name: '我的叫货单', exact: true })).toHaveCount(0)
  await expect(requisitionTable.getByText(shippedRequisition.id, { exact: true })).toBeVisible()
  await expect(requisitionTable.getByText(receivedRequisition.id, { exact: true })).toBeVisible()
  await expect(records.getByRole('button', { name: '确认已收货', exact: true })).toBeVisible()

  await records.getByRole('button', { name: '发起配送退货', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '发起配送退货' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('退货部门')
  await expect(dialog).toContainText('测试门店')
  await expect(dialog).toContainText('收货部门')
  await expect(dialog).toContainText('荆州总仓')
  await dialog.getByLabel('退货原因').fill('包装破损')
  await dialog.getByLabel('备注（选填）').fill('门店核对后退回')
  await dialog.getByLabel('鲜牛奶退货数量').fill('2')
  await dialog.getByRole('button', { name: '提交配送退货', exact: true }).click()

  await expect.poll(() => log.createBodies.length).toBe(1)
  expect(log.createBodies[0]).toMatchObject({
    returnStoreId: 'STORE-1',
    sourceRequisitionId: receivedRequisition.id,
    reason: '包装破损',
    note: '门店核对后退回',
    lines: [{
      itemId: 11,
      quantity: 2,
      reason: '包装破损',
      note: '门店核对后退回',
    }],
    attachments: [],
  })
  await expect(dialog).toHaveCount(0)
  await expect(returnTable.getByText('PSTH260728000000001', { exact: true })).toBeVisible()
  await expect(returnTable.getByText('待仓库审核', { exact: true })).toBeVisible()

  await records.getByRole('button', { name: '下载退货单', exact: true }).click()
  await expect.poll(log.getDownloadRequests).toBe(1)
  expect(log.consoleErrors).toEqual([])
})

test('配送退货弹窗在390px小屏完整可达且数量错误使用弹窗提示', async ({ page }, testInfo: TestInfo) => {
  test.skip(testInfo.project.name !== 'chromium', '小屏退货弹窗通过 chromium 固定视口验证')
  await page.setViewportSize({ width: 390, height: 844 })
  const log = await prepare(page)
  await page.goto('/store/inventory/records')

  const records = page.getByRole('region', { name: '配送与退货记录' })
  await records.getByRole('button', { name: '发起配送退货', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '发起配送退货' })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('退货原因').fill('数量校验')
  await dialog.getByLabel('鲜牛奶退货数量').fill('6')
  await dialog.getByRole('button', { name: '提交配送退货', exact: true }).click()

  const errorDialog = page.getByRole('alertdialog', { name: '退货数量不正确' })
  await expect(errorDialog).toContainText('不能超过当前可退数量 3箱')
  await errorDialog.getByRole('button', { name: '我知道了', exact: true }).click()
  await expect(dialog).toBeVisible()

  await dialog.getByLabel('鲜牛奶退货数量').fill('2')
  const submitButton = dialog.getByRole('button', { name: '提交配送退货', exact: true })
  await submitButton.scrollIntoViewIfNeeded()
  await expect(submitButton).toBeVisible()
  const [documentWidth, viewportWidth] = await page.evaluate(() => [
    document.documentElement.scrollWidth,
    document.documentElement.clientWidth,
  ])
  expect(documentWidth).toBeLessThanOrEqual(viewportWidth)
  expect(log.consoleErrors).toEqual([])
})
