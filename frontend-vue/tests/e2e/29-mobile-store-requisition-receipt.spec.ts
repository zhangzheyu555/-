import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const mobileProjectWidths: Record<string, number> = {
  'iphone-390': 390,
  'android-412': 412,
  'ipad-768': 768,
}

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

const shippedRequisition = {
  id: 'REQ-MOBILE-RECEIPT-001',
  storeId: 'STORE-1',
  storeName: '测试门店',
  warehouseId: 1,
  warehouseName: '荆州总仓',
  status: 'SHIPPED',
  statusLabel: '待确认收货',
  totalAmount: 150,
  submittedAt: '2026-07-15 09:00',
  shippedAt: '2026-07-15 10:30',
  lines: [{
    itemId: 11,
    itemName: '鲜牛奶',
    requestedQuantity: 3,
    approvedQuantity: 3,
    shippedQuantity: 3,
    unit: '箱',
  }],
}

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: status < 400, data }),
  })
}

function overview(received: boolean) {
  return {
    warehouse: supplyWarehouse,
    summary: {
      itemCount: 1,
      lowStockCount: 0,
      expiringCount: 0,
      overstockCount: 0,
      pendingRequisitionCount: 0,
      pendingReceiptCount: received ? 0 : 1,
      pendingPurchaseCount: 0,
      stockValue: 1200,
      inTransitQuantity: received ? 0 : 3,
    },
    alerts: [],
    items: [{
      id: 11,
      code: 'MILK-01',
      name: '鲜牛奶',
      unit: '箱',
      stockQuantity: 20,
      storeStockQuantity: received ? 5 : 2,
      warehouseAvailableQuantity: 18,
      unitPrice: 50,
      stockStatus: '正常',
      alertLevel: 'NORMAL',
      alertText: '',
      active: true,
    }],
    requisitions: received ? [] : [shippedRequisition],
    stockBatches: [],
    movements: [],
  }
}

async function prepare(page: Page) {
  let received = false
  let receiveBody: unknown = null
  let receiveRequestCount = 0
  let requisitionBody: unknown = null
  const consoleErrors: string[] = []

  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(error.message))

  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'MOBILE-STORE-RECEIPT-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, storeManagerSession)

  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const request = route.request()
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/auth/me') return json(route, storeManagerSession)
    if (pathname === '/api/warehouse/warehouses') return json(route, [supplyWarehouse])
    if (pathname === '/api/warehouse/overview') return json(route, overview(received))
    if (pathname === '/api/warehouse/item-categories' || pathname === '/api/warehouse/returns') return json(route, [])
    if (pathname === '/api/warehouse/requisitions' && request.method() === 'POST') {
      requisitionBody = request.postDataJSON()
      return json(route, { id: 'REQ-MOBILE-DRAFT-001', status: 'SUBMITTED', lines: [] })
    }
    if (pathname === `/api/warehouse/requisitions/${shippedRequisition.id}/receive` && request.method() === 'POST') {
      receiveRequestCount += 1
      receiveBody = request.postDataJSON()
      received = true
      return json(route, null)
    }
    if (pathname === '/api/stores') return json(route, [{ id: 'STORE-1', name: '测试门店', status: 'ACTIVE' }])
    return json(route, [])
  })

  return {
    getReceiveBody: () => receiveBody,
    getReceiveRequestCount: () => receiveRequestCount,
    getRequisitionBody: () => requisitionBody,
    consoleErrors,
  }
}

function requireMobileProject(testInfo: TestInfo) {
  const expectedWidth = mobileProjectWidths[testInfo.project.name]
  test.skip(!expectedWidth, '仅在 iPhone、Android 和 iPad 设备项目中执行')
}

test('mobile store manager edits a requisition card and submits without page overflow', async ({ page }, testInfo) => {
  requireMobileProject(testInfo)
  expect(page.viewportSize()?.width).toBe(mobileProjectWidths[testInfo.project.name])

  const log = await prepare(page)
  await page.goto('/store/inventory/requisition')

  const form = page.locator('#store-requisition-form')
  await expect(form).toBeVisible()
  const material = form.locator('select')
  const requestedQuantity = form.locator('input[type="number"]')
  const lineNote = form.locator('input[maxlength="500"]')
  const addButton = form.getByRole('button', { name: '添加', exact: true })
  for (const control of [material, requestedQuantity, lineNote, addButton]) {
    const box = await control.boundingBox()
    expect(box?.height, `${await control.getAttribute('aria-label') || await control.textContent() || '叫货控件'} 的点击高度`).toBeGreaterThanOrEqual(44)
  }

  await material.selectOption('11')
  await requestedQuantity.fill('2')
  await lineNote.fill('首批叫货备注')
  await addButton.click()

  const requisitionCard = form.locator('.requisition-line').filter({ hasText: '鲜牛奶' })
  await expect(requisitionCard).toBeVisible()
  await expect(requisitionCard.getByText(/供货仓可配\s*18\s*箱/)).toBeVisible()
  const decrease = requisitionCard.getByRole('button', { name: /减少 鲜牛奶 的叫货数量/ })
  const increase = requisitionCard.getByRole('button', { name: /增加 鲜牛奶 的叫货数量/ })
  const remove = requisitionCard.getByRole('button', { name: /删除 鲜牛奶/ })
  const cardQuantity = requisitionCard.locator('input[type="number"]')
  const cardNote = requisitionCard.locator('input[maxlength="500"]')
  for (const control of [decrease, increase, remove, cardQuantity, cardNote]) {
    const box = await control.boundingBox()
    expect(box?.height, `${await control.getAttribute('aria-label') || '物料卡片控件'} 的点击高度`).toBeGreaterThanOrEqual(44)
  }
  await increase.click()
  await expect(cardQuantity).toHaveValue('3')
  await decrease.click()
  await expect(cardQuantity).toHaveValue('2')
  await cardNote.fill('已修改的单项备注')
  await expectNoWholePageOverflow(page, `${testInfo.project.name} 门店叫货卡片`)

  await remove.click()
  await expect(requisitionCard).toHaveCount(0)
  await material.selectOption('11')
  await requestedQuantity.fill('1')
  await lineNote.fill('再次添加')
  await addButton.click()
  await expect(requisitionCard).toBeVisible()

  const submitButton = form.getByRole('button', { name: '提交叫货', exact: true })
  const submitBox = await submitButton.boundingBox()
  expect(submitBox?.height, '提交叫货按钮的点击高度').toBeGreaterThanOrEqual(44)
  await submitButton.click()
  await expect.poll(log.getRequisitionBody).toMatchObject({
    lines: [{ itemId: 11, requestedQuantity: 1, note: '再次添加' }],
  })
  const submitted = log.getRequisitionBody() as { clientRequestId?: string }
  expect(submitted.clientRequestId).toMatch(/^req-/)
  await expect(page.getByText('叫货单已提交', { exact: true })).toBeVisible()
  await expectNoWholePageOverflow(page, `${testInfo.project.name} 门店叫货提交后`)
  expect(log.consoleErrors).toEqual([])
})

test('mobile store manager completes requisition receipt with a visible touch-safe card action', async ({ page }, testInfo) => {
  requireMobileProject(testInfo)
  expect(page.viewportSize()?.width).toBe(mobileProjectWidths[testInfo.project.name])

  const log = await prepare(page)
  await page.goto('/store/inventory/records')

  const receiptCard = page.locator('.pending-receipt-card').filter({ hasText: shippedRequisition.id })
  await expect(receiptCard).toBeVisible()
  await expect(receiptCard).toContainText('鲜牛奶 × 3箱')
  await expect(receiptCard).toContainText('2026-07-15 10:30')
  await expect(page.locator('.pending-receipt-table-wrap')).toBeHidden()

  const receiveButton = receiptCard.getByRole('button', { name: '确认已收货', exact: true })
  const [cardBox, receiveButtonBox] = await Promise.all([receiptCard.boundingBox(), receiveButton.boundingBox()])
  expect(receiveButtonBox?.height, '确认收货按钮的点击高度').toBeGreaterThanOrEqual(44)
  expect(receiveButtonBox?.width, '确认收货按钮应占满收货卡片内容宽度').toBeGreaterThanOrEqual((cardBox?.width || 0) - 32)
  await expectNoWholePageOverflow(page, `${testInfo.project.name} 待确认收货列表`)

  await receiveButton.click()
  const dialog = page.getByRole('alertdialog', { name: '确认收货' })
  await expect(dialog).toBeVisible()
  const confirmButton = dialog.getByRole('button', { name: '确认收货', exact: true })
  const confirmButtonBox = await confirmButton.boundingBox()
  expect(confirmButtonBox?.height, '确认弹框按钮的点击高度').toBeGreaterThanOrEqual(44)
  await confirmButton.click()

  await expect.poll(log.getReceiveBody).toEqual({ note: '店长确认收货' })
  await expect.poll(log.getReceiveRequestCount).toBe(1)
  await expect(page.getByText('已确认收货', { exact: true })).toBeVisible()
  await expect(receiptCard).toHaveCount(0)
  await expectNoWholePageOverflow(page, `${testInfo.project.name} 确认收货后`)
  expect(log.consoleErrors).toEqual([])
})
