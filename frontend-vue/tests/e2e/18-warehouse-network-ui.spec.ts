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
    canProcessRequisition: true,
    canConfigure: true,
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
    canProcessRequisition: true,
  },
]

const permissions = [
  'warehouse.read',
  'warehouse.purchase',
  'warehouse.transfer.request',
  'warehouse.transfer.approve',
  'warehouse.transfer.ship',
  'warehouse.transfer.receive',
  'warehouse.requisition.process',
  'warehouse.configure',
  'warehouse.store.read',
  'warehouse.requisition.create',
  'warehouse.requisition.receive',
  'system.user.manage',
]

const baseSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions,
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

const warehouseAdminSession = {
  ...baseSession,
  id: 8,
  displayName: '两仓管理员',
  role: 'WAREHOUSE',
  roleLabel: '仓库管理员',
  storeScope: [],
  dataScopes: {
    STORE: { mode: 'NONE', storeIds: [], warehouseIds: [] },
    WAREHOUSE: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1', '2'] },
  },
  dataScope: { mode: 'NONE', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/warehouse',
}

const shandongAdminSession = {
  ...warehouseAdminSession,
  id: 9,
  displayName: '山东仓管理员',
  permissions: ['warehouse.read', 'warehouse.transfer.request', 'warehouse.transfer.receive'],
  dataScopes: {
    STORE: { mode: 'NONE', storeIds: [], warehouseIds: [] },
    WAREHOUSE: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['2'] },
  },
}

const noPendingCentralSession = {
  ...baseSession,
  id: 10,
  displayName: '无待办总仓管理员',
}

const requisitionProcessorSession = {
  ...warehouseAdminSession,
  id: 31,
  displayName: '叫货处理仓管',
}

const storeSession = {
  ...baseSession,
  id: 21,
  displayName: '荆州之星店店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['rg1'],
  permissions: ['warehouse.store.read', 'warehouse.requisition.create', 'warehouse.requisition.receive'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] },
    WAREHOUSE: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] },
  },
  dataScope: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] },
  boundStoreId: 'rg1',
  boundStoreName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  defaultWorkspace: '/store',
}

const shortageRequisition = {
  id: 'REQ-SHORTAGE-001',
  storeId: 'rg1',
  storeName: '荆州之星店',
  warehouseId: 1,
  warehouseName: '荆州总仓',
  status: 'SUBMITTED',
  statusLabel: '待仓库处理',
  totalAmount: 250,
  note: '门店周末备货',
  submittedAt: '2026-07-24 09:00',
  lines: [{
    id: 91,
    itemId: 11,
    itemName: '鲜牛奶',
    requestedQuantity: 5,
    approvedQuantity: 0,
    shippedQuantity: 0,
    unitPrice: 50,
    amount: 250,
    unit: '箱',
  }],
}

const availableRequisition = {
  id: 'REQ-AVAILABLE-002',
  storeId: 'bw1',
  storeName: '滨江万达店',
  warehouseId: 1,
  warehouseName: '荆州总仓',
  status: 'SUBMITTED',
  statusLabel: '待仓库处理',
  totalAmount: 50,
  note: '日常补货',
  submittedAt: '2026-07-20 15:30',
  lines: [{
    id: 92,
    itemId: 11,
    itemName: '鲜牛奶',
    requestedQuantity: 1,
    approvedQuantity: 0,
    shippedQuantity: 0,
    unitPrice: 50,
    amount: 50,
    unit: '箱',
  }],
}

const overview = (warehouseId = 1, includeShortageRequisition = false) => ({
  warehouse: warehouses.find((row) => row.id === warehouseId),
  summary: {
    itemCount: 1,
    lowStockCount: 0,
    expiringCount: 0,
    overstockCount: 0,
    pendingRequisitionCount: includeShortageRequisition ? 2 : 0,
    pendingReceiptCount: 0,
    pendingPurchaseCount: 0,
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
    warehouseAvailableQuantity: includeShortageRequisition ? 3 : 18,
    unitPrice: 50,
    stockStatus: '正常',
    alertLevel: 'NORMAL',
    alertText: '',
    active: true,
  }],
  requisitions: includeShortageRequisition ? [availableRequisition, shortageRequisition] : [],
  stockBatches: [],
  movements: [],
})

const transferActions = (overrides: Partial<{
  canCreate: boolean
  canSubmit: boolean
  canApprove: boolean
  canReject: boolean
  canShip: boolean
  canReceive: boolean
  canCancel: boolean
}> = {}) => ({
  canCreate: false,
  canSubmit: false,
  canApprove: false,
  canReject: false,
  canShip: false,
  canReceive: false,
  canCancel: false,
  ...overrides,
})

const transferContext = (warehouseId: number, session: typeof baseSession) => {
  const can = (permission: string) => session.role === 'BOSS' || session.permissions.includes(permission)
  const route = {
    sourceWarehouse: { id: 1, code: 'JZ-CENTRAL', name: '荆州总仓' },
    targetWarehouse: { id: 2, code: 'SD-REGIONAL', name: '山东分仓' },
    materials: [{
      itemId: 11,
      itemName: '鲜牛奶',
      itemCode: 'MILK-01',
      unit: '箱',
      availableQuantity: 18,
      shortageMessage: '荆州总仓当前可发 18 箱鲜牛奶，请调整数量或等待补货。',
    }, {
      itemId: 12,
      itemName: '双杯纸袋',
      itemCode: '01003',
      unit: '个',
      availableQuantity: 60,
    }, {
      itemId: 13,
      itemName: '卡士酸奶',
      itemCode: 'BC0104',
      unit: '件',
      availableQuantity: 24,
    }],
  }
  if (warehouseId === 1) {
    return {
      currentWarehouse: route.sourceWarehouse,
      mode: 'PROACTIVE_ALLOCATION',
      routes: [{
        ...route,
        actions: transferActions({
          canCreate: can('warehouse.transfer.request'),
          canSubmit: can('warehouse.transfer.request'),
          canCancel: can('warehouse.transfer.request'),
          canApprove: can('warehouse.transfer.approve'),
          canReject: can('warehouse.transfer.approve'),
          canShip: can('warehouse.transfer.ship'),
        }),
      }],
      todos: session.id === noPendingCentralSession.id
        ? { completed: 3 }
        : { pendingApproval: 1, pendingShipment: 1, completed: 3 },
    }
  }
  return {
    currentWarehouse: route.targetWarehouse,
    mode: 'REQUEST_REPLENISHMENT',
    routes: [{
      ...route,
      actions: transferActions({
        canCreate: can('warehouse.transfer.request'),
        canSubmit: can('warehouse.transfer.request'),
        canCancel: can('warehouse.transfer.request'),
        canReceive: can('warehouse.transfer.receive'),
      }),
    }],
    todos: { draft: 1, pendingReceipt: 2, completed: 4 },
  }
}

const transferRecords = (warehouseId: number) => {
  const sharedLine = {
    id: 1,
    itemId: 11,
    itemName: '鲜牛奶',
    unit: '箱',
    requestedQuantity: 3,
    approvedQuantity: 3,
    reservedQuantity: 3,
    shippedQuantity: 0,
    receivedQuantity: 0,
    inTransitQuantity: 0,
    unitCost: 50,
    amount: 150,
  }
  if (warehouseId === 1) {
    return [
      {
        id: 'TR-JZ-DRAFT',
        transferNo: 'DB-JZ-DRAFT',
        status: 'DRAFT',
        sourceWarehouseId: 1,
        sourceWarehouseName: '荆州总仓',
        targetWarehouseId: 2,
        targetWarehouseName: '山东分仓',
        totalAmount: 150,
        requestedBy: '荆州仓管理员',
        createdAt: '2026-07-15 08:30',
        note: '总仓主动配货草稿',
        version: 1,
        lines: [sharedLine],
      },
      {
        id: 'TR-JZ',
        transferNo: 'DB-JZ-001',
        status: 'APPROVED',
        sourceWarehouseId: 1,
        sourceWarehouseName: '荆州总仓',
        targetWarehouseId: 2,
        targetWarehouseName: '山东分仓',
        totalAmount: 150,
        requestedBy: '山东仓管理员',
        createdAt: '2026-07-15 09:00',
        note: '总仓待发货调拨',
        version: 1,
        lines: [sharedLine],
      },
    ]
  }
  return [{
    id: 'TR-SD',
    transferNo: 'DB-SD-001',
    status: 'SHIPPED',
    sourceWarehouseId: 1,
    sourceWarehouseName: '荆州总仓',
    targetWarehouseId: 2,
    targetWarehouseName: '山东分仓',
    totalAmount: 150,
    shippedBy: '荆州仓管理员',
    createdAt: '2026-07-15 10:00',
    note: '山东待收货调拨',
    version: 2,
    lines: [{ ...sharedLine, shippedQuantity: 3, inTransitQuantity: 3 }],
  }]
}

interface RequestLog {
  urls: string[]
  itemBodies: Record<string, unknown>[]
  requisitionBody: Record<string, unknown> | null
  requisitionReviewBodies: Record<string, unknown>[]
  requisitionShipCount: number
  transferBody: Record<string, unknown> | null
  accessProfileBody: Record<string, unknown> | null
  consoleErrors: string[]
}

async function prepare(page: Page, session: typeof baseSession) {
  const log: RequestLog = {
    urls: [],
    itemBodies: [],
    requisitionBody: null,
    requisitionReviewBodies: [],
    requisitionShipCount: 0,
    transferBody: null,
    accessProfileBody: null,
    consoleErrors: [],
  }
  page.on('console', (message) => {
    if (message.type() === 'error') log.consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => log.consoleErrors.push(error.message))
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'WAREHOUSE-NETWORK-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => fulfillApi(route, session, log))
  return log
}

async function fulfillApi(route: Route, session: typeof baseSession, log: RequestLog) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname
  log.urls.push(request.url())
  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/warehouse/warehouses') {
    if (session.role === 'STORE_MANAGER') return route.fulfill(ok([warehouses[0]]))
    const scopedIds = session.dataScopes.WAREHOUSE.warehouseIds
    return route.fulfill(ok(
      session.dataScopes.WAREHOUSE.mode === 'WAREHOUSE_LIST'
        ? warehouses.filter((warehouse) => scopedIds.includes(String(warehouse.id)))
        : warehouses,
    ))
  }
  if (path === '/api/warehouse/overview') {
    const warehouseId = Number(url.searchParams.get('warehouseId') || (session.role === 'STORE_MANAGER' ? 1 : 1))
    return route.fulfill(ok(overview(warehouseId, session.id === requisitionProcessorSession.id)))
  }
  if (path === '/api/warehouse/transfers/context') {
    const warehouseId = Number(url.searchParams.get('warehouseId') || 1)
    return route.fulfill(ok(transferContext(warehouseId, session)))
  }
  if (path === '/api/warehouse/transfers' && request.method() === 'POST') {
    log.transferBody = request.postDataJSON()
    return route.fulfill(ok({ id: 'TR-1', status: 'DRAFT', lines: [] }))
  }
  if (path === '/api/warehouse/transfers') {
    const warehouseId = Number(url.searchParams.get('warehouseId') || 1)
    return route.fulfill(ok(transferRecords(warehouseId)))
  }
  if (path === '/api/warehouse/item-categories') {
    return route.fulfill(ok([{ id: 1, name: '耗材', sortOrder: 1, enabled: true, children: [] }]))
  }
  if (path === '/api/warehouse/items/requisition-scope-context') {
    return route.fulfill(ok({
      activeStoreCount: 3,
      regions: [
        { code: 'JINGZHOU', name: '荆州区域' },
        { code: 'SHANDONG', name: '山东区域' },
      ],
      stores: [
        { id: 'rg1', name: '荆州之星店', regionCode: 'JINGZHOU' },
        { id: 'bw1', name: '滨江万达店', regionCode: 'SHANDONG' },
        { id: 'xnm1', name: '西南商圈店', regionCode: 'SHANDONG' },
      ],
    }))
  }
  if (path === '/api/warehouse/items' && request.method() === 'POST') {
    log.itemBodies.push(request.postDataJSON())
    return route.fulfill(ok(null))
  }
  if (path === '/api/warehouse/returns') return route.fulfill(ok([]))
  if (path === `/api/warehouse/requisitions/${shortageRequisition.id}/review` && request.method() === 'POST') {
    log.requisitionReviewBodies.push(request.postDataJSON())
    return route.fulfill(ok(null))
  }
  if (path === `/api/warehouse/requisitions/${shortageRequisition.id}/ship` && request.method() === 'POST') {
    log.requisitionShipCount += 1
    return route.fulfill(ok(null))
  }
  if (path === '/api/warehouse/requisitions' && request.method() === 'POST') {
    log.requisitionBody = request.postDataJSON()
    return route.fulfill(ok({ id: 'REQ-1', status: 'SUBMITTED', lines: [] }))
  }
  if (path === '/api/stores') {
    return route.fulfill(ok([
      { id: 'rg1', name: '荆州之星店', brandName: '茹菓', status: 'ACTIVE', regionCode: 'JINGZHOU' },
      { id: 'bw1', name: '滨江万达店', brandName: '百味鸡', status: 'ACTIVE', regionCode: 'SHANDONG' },
      { id: 'xnm1', name: '西南商圈店', brandName: '小柠檬', status: 'ACTIVE', regionCode: 'SHANDONG' },
    ]))
  }
  if (path === '/api/users') {
    return route.fulfill(ok([{
      id: 8,
      tenantId: 1,
      tenantName: '测试租户',
      username: 'warehouse_admin',
      displayName: '仓库管理员',
      role: 'WAREHOUSE',
      roleLabel: '仓库管理员',
      enabled: true,
      storeScope: [],
      availableWorkspaces: ['/warehouse'],
      defaultWorkspace: '/warehouse',
      effectivePermissionStatus: 'READY',
      effectivePermissionMessage: '权限可用',
    }]))
  }
  if (path === '/api/users/authorization/catalog') {
    return route.fulfill(ok({
      permissions: [{
        permissionCode: 'warehouse.read',
        moduleCode: 'WAREHOUSE',
        permissionName: '仓库查看',
        description: '查看授权仓库',
        riskLevel: 'LOW',
        enabled: true,
        sortOrder: 1,
      }],
      dataScopeDomains: ['WAREHOUSE'],
      dataScopeModes: ['ALL', 'WAREHOUSE_LIST', 'STORE_LIST', 'OWN_STORE', 'CENTRAL_WAREHOUSE', 'SELF', 'NONE'],
    }))
  }
  if (path === '/api/users/8/authorization') {
    return route.fulfill(ok({
      userId: 8,
      role: 'WAREHOUSE',
      storeId: null,
      permissionVersion: 5,
      roleTemplatePermissions: ['warehouse.read'],
      dataScopes: [{ domainCode: 'WAREHOUSE', mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1'] }],
      overrides: [],
      effectivePermissions: ['warehouse.read'],
      availableWorkspaces: ['/warehouse'],
      defaultWorkspace: '/warehouse',
      effectivePermissionStatus: 'READY',
      effectivePermissionMessage: '权限可用',
    }))
  }
  if (path === '/api/users/8/access-profile' && request.method() === 'PUT') {
    log.accessProfileBody = request.postDataJSON()
    const body = log.accessProfileBody as { dataScopes?: unknown[] }
    return route.fulfill(ok({
      user: {
        id: 8,
        tenantId: 1,
        tenantName: '测试租户',
        username: 'warehouse_admin',
        displayName: '仓库管理员',
        role: 'WAREHOUSE',
        roleLabel: '仓库管理员',
        enabled: true,
        storeScope: [],
        availableWorkspaces: ['/warehouse'],
        defaultWorkspace: '/warehouse',
        effectivePermissionStatus: 'READY',
        effectivePermissionMessage: '权限可用',
      },
      authorization: {
        userId: 8,
        role: 'WAREHOUSE',
        storeId: null,
        permissionVersion: 6,
        roleTemplatePermissions: ['warehouse.read'],
        dataScopes: body.dataScopes,
        overrides: [],
        effectivePermissions: ['warehouse.read'],
        availableWorkspaces: ['/warehouse'],
        defaultWorkspace: '/warehouse',
        effectivePermissionStatus: 'READY',
        effectivePermissionMessage: '权限可用',
      },
    }))
  }
  return route.fulfill(ok([]))
}

test('central warehouse entry prioritizes identity, risks, pending work and one main action', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/central')
  const pageRoot = page.locator('.warehouse-page')
  await expect(pageRoot.getByRole('link', { name: '仓库中心', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('heading', { name: '荆州总仓', exact: true })).toBeVisible()
  for (const label of ['总仓', '荆州区域', '可外部采购', '可向分仓配货']) {
    await expect(pageRoot.getByText(label, { exact: true })).toBeVisible()
  }
  const statGrid = pageRoot.locator('.warehouse-stat-grid')
  for (const label of ['库存总值', '低库存', '临期风险', '待处理调拨', '待处理叫货']) {
    await expect(statGrid.getByText(label, { exact: true })).toBeVisible()
  }
  await expect(pageRoot.getByRole('heading', { name: '待优先处理', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '处理 2 笔调拨', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '外部采购入库', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: /物料档案/ })).toBeVisible()
  for (const label of ['库存', '门店叫货', '外部采购', '出入库记录']) {
    await expect(pageRoot.getByRole('link', { name: label, exact: true })).toBeVisible()
  }
  await expect(pageRoot.locator('.warehouse-business-navigation').getByRole('link', { name: /调拨/ })).toBeVisible()
  await page.screenshot({ path: '../output/playwright/warehouse-central-workbench.png', fullPage: true })

  await page.goto('/warehouse/shandong')
  await expect(pageRoot.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()
  await expect(pageRoot.getByText('上级：荆州总仓', { exact: true })).toBeVisible()
  await expect(pageRoot.getByText('仅可申请补货', { exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '向上级总仓申请补货', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '外部采购', exact: true })).toHaveCount(0)
  await expect(pageRoot.getByRole('link', { name: '外部采购入库', exact: true })).toHaveCount(0)
  await page.screenshot({ path: '../output/playwright/warehouse-shandong-context.png', fullPage: true })
  expect(log.consoleErrors).toEqual([])
})

test('central warehouse without pending work falls back to proactive allocation and shows an empty priority state', async ({ page }) => {
  const log = await prepare(page, noPendingCentralSession)
  await page.goto('/warehouse/central')

  await expect(page.getByRole('link', { name: '向分仓配货', exact: true })).toBeVisible()
  await expect(page.getByText('当前没有需要优先处理的事项。', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /处理 .*笔调拨/ })).toHaveCount(0)
  expect(log.consoleErrors).toEqual([])
})

test('item editor requires explicit scope, confirms all stores, and submits region-store union', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  let editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('全门店测试物料')
  await editor.getByLabel('编号').fill('SCOPE-ALL')
  await editor.getByLabel('类别').selectOption('1')
  await expect(editor.getByRole('button', { name: '保存物料' })).toBeDisabled()
  await expect(editor.getByText('请选择全部门店或指定区域、指定门店。')).toBeVisible()

  await editor.getByRole('radio', { name: /全部门店/ }).check()
  await expect(editor.locator('.scope-coverage')).toContainText('当前将覆盖 3 家营业门店')
  await editor.getByRole('button', { name: '保存物料' }).click()

  const allStoresConfirmation = page.getByRole('alertdialog', { name: '确认允许全部门店叫货？' })
  await expect(allStoresConfirmation).toContainText('当前 3 家营业门店')
  await allStoresConfirmation.getByRole('button', { name: '确认全部门店并保存' }).click()
  await expect.poll(() => log.itemBodies.length).toBe(1)
  expect(log.itemBodies[0]).toMatchObject({
    code: 'SCOPE-ALL',
    requisitionPolicy: {
      scopeMode: 'ALL',
      regionCodes: [],
      storeIds: [],
    },
  })

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('区域门店并集测试物料')
  await editor.getByLabel('编号').fill('SCOPE-UNION')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /指定区域/ }).check()
  await editor.getByRole('checkbox', { name: '山东区域 SHANDONG', exact: true }).check()
  await expect(editor.locator('.scope-coverage')).toContainText('当前将覆盖 2 家营业门店')
  await editor.getByRole('checkbox', { name: /荆州之星店/ }).check()
  await expect(editor.locator('.scope-coverage')).toContainText('当前将覆盖 3 家营业门店')
  await editor.getByLabel('活动名称（选填）').fill('山东联动活动')
  await editor.getByLabel('生效开始时间（选填）').fill('2026-07-24T08:00')
  await editor.getByLabel('生效结束时间（选填）').fill('2026-08-24T23:00')
  await editor.getByRole('button', { name: '保存物料' }).click()

  await expect.poll(() => log.itemBodies.length).toBe(2)
  expect(log.itemBodies[1]).toMatchObject({
    code: 'SCOPE-UNION',
    requisitionPolicy: {
      scopeMode: 'SELECTED',
      regionCodes: ['SHANDONG'],
      storeIds: ['rg1'],
      campaignName: '山东联动活动',
      startsAt: '2026-07-24T08:00',
      endsAt: '2026-08-24T23:00',
    },
  })
  expect(log.consoleErrors).toEqual([])
})

test('warehouse center sidebar opens the authorized central workbench and keeps warehouse context in the URL', async ({ page }) => {
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/central')

  await page.locator('.app-sidebar--desktop').getByRole('link', { name: '仓库中心', exact: true }).click()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/detail/1')
  await expect(page.getByRole('heading', { name: '荆州总仓', exact: true })).toBeVisible()

  const selector = page.getByLabel('当前仓库')
  await expect(selector).toHaveValue('1')
  await expect(selector.locator('option')).toHaveText(['荆州总仓', '山东分仓'])
  await expect.poll(() => log.urls.some((raw) => {
    const requestUrl = new URL(raw)
    return requestUrl.pathname === '/api/warehouse/overview' && requestUrl.searchParams.get('warehouseId') === '1'
  })).toBe(true)

  await selector.selectOption('2')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/detail/2')
  await expect(page.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()
  for (const endpoint of ['/api/warehouse/overview', '/api/warehouse/transfers', '/api/warehouse/transfers/context']) {
    expect(log.urls.some((raw) => {
      const requestUrl = new URL(raw)
      return requestUrl.pathname === endpoint && requestUrl.searchParams.get('warehouseId') === '2'
    })).toBe(true)
  }
  await expect.poll(() => page.evaluate(() => Math.max(document.documentElement.scrollWidth, document.body.scrollWidth))).toBeLessThanOrEqual(1280)

  await page.goBack()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/detail/1')
  await expect(page.getByRole('heading', { name: '荆州总仓', exact: true })).toBeVisible()
  await page.goForward()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/detail/2')
  await expect(page.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()

  await page.goto('/warehouse')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/detail/1')
  await expect(page.getByRole('heading', { name: '荆州总仓', exact: true })).toBeVisible()
  expect(log.consoleErrors).toEqual([])
})

test('multi-warehouse admin switches by scoped warehouse id and ignores query tampering', async ({ page }) => {
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/central')
  const selector = page.getByLabel('当前仓库')
  await expect(selector).toBeVisible()
  await expect(selector.locator('option')).toHaveCount(2)
  await selector.selectOption('2')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/detail/2')

  await page.goto('/warehouse/shandong?warehouseId=1')
  await expect(page.getByRole('link', { name: '向上级总仓申请补货' })).toBeVisible()
  const overviewRequests = log.urls.map((raw) => new URL(raw)).filter((url) => url.pathname === '/api/warehouse/overview')
  expect(overviewRequests.at(-1)?.searchParams.get('warehouseId')).toBe('2')
  expect(log.consoleErrors).toEqual([])
})

test('regional warehouse requisition link preserves the selected warehouse context', async ({ page }) => {
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/detail/2')

  await expect(page.getByLabel('当前仓库')).toHaveValue('2')
  await expect(page.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()
  await page.getByRole('link', { name: '门店叫货', exact: true }).click()

  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/requests')
  await expect.poll(() => new URL(page.url()).searchParams.get('warehouseId')).toBe('2')
  await expect(page.getByLabel('当前仓库')).toHaveValue('2')
  await expect(page.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()

  await page.reload()
  await expect.poll(() => new URL(page.url()).searchParams.get('warehouseId')).toBe('2')
  await expect(page.getByLabel('当前仓库')).toHaveValue('2')
  await expect(page.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()
  await expect.poll(() => log.urls.some((raw) => {
    const requestUrl = new URL(raw)
    return requestUrl.pathname === '/api/warehouse/overview'
      && requestUrl.searchParams.get('warehouseId') === '2'
  })).toBe(true)

  const warehouseSelector = page.getByLabel('当前仓库')
  await warehouseSelector.selectOption('1')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/requests')
  await expect.poll(() => new URL(page.url()).searchParams.get('warehouseId')).toBe('1')
  await expect(page.getByRole('heading', { name: '荆州总仓', exact: true })).toBeVisible()

  await warehouseSelector.selectOption('2')
  await expect.poll(() => new URL(page.url()).searchParams.get('warehouseId')).toBe('2')
  await expect(page.getByRole('heading', { name: '山东分仓', exact: true })).toBeVisible()
  expect(log.consoleErrors).toEqual([])
})

test('transfer workbench keeps its route while switching warehouse context and reloads real workbench data', async ({ page }) => {
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/transfers?warehouseId=1')

  const selector = page.getByLabel('当前仓库')
  await expect(selector).toHaveValue('1')
  await expect(page.getByRole('heading', { name: '向分仓主动配货', exact: true })).toBeVisible()
  await expect(page.getByLabel('调出仓')).toHaveValue('荆州总仓')
  const centralTarget = page.getByLabel('调入仓')
  await expect(centralTarget).toHaveValue('山东分仓')
  await expect(centralTarget).toHaveAttribute('readonly', '')
  await expect(page.getByText('主动配货草稿', { exact: true })).toBeVisible()
  await expect(page.getByText('待审批', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('待发货', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('DB-JZ-001', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '发货', exact: true })).toBeVisible()

  await page.getByRole('button', { name: '增加物料', exact: true }).click()
  const materialSearch = page.getByLabel('搜索调拨物料', { exact: true })
  const materialSelect = page.getByLabel('调拨物料', { exact: true })
  await expect(materialSelect.locator('option')).toHaveCount(4)
  await materialSearch.fill('01003')
  await expect(materialSelect.locator('option')).toHaveCount(2)
  await expect(materialSelect.locator('option')).toContainText(['请选择物料', '双杯纸袋 · 01003 · 可发 60 个'])
  await expect(page.getByText('找到 1 条，共 3 条物料', { exact: true })).toBeVisible()
  await materialSelect.selectOption('12')

  await page.getByRole('button', { name: '提交', exact: true }).click()
  await expect.poll(() => log.urls.some((raw) => new URL(raw).pathname.endsWith('/TR-JZ-DRAFT/submit'))).toBe(true)

  await selector.selectOption('2')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/warehouse/transfers')
  await expect.poll(() => new URL(page.url()).searchParams.get('warehouseId')).toBe('2')
  await expect(selector).toHaveValue('2')
  await expect(page.getByRole('heading', { name: '向上级总仓申请补货', exact: true })).toBeVisible()
  await expect(page.getByLabel('调出仓')).toHaveValue('荆州总仓')
  await expect(page.getByLabel('调入仓')).toHaveValue('山东分仓')
  await expect(page.getByText('DB-SD-001', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '确认收货', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '发货', exact: true })).toHaveCount(0)
  await expect(page.getByText('待收货', { exact: true }).first()).toBeVisible()

  for (const endpoint of ['/api/warehouse/overview', '/api/warehouse/transfers', '/api/warehouse/transfers/context']) {
    expect(log.urls.some((raw) => {
      const requestUrl = new URL(raw)
      return requestUrl.pathname === endpoint && requestUrl.searchParams.get('warehouseId') === '2'
    })).toBe(true)
  }

  await selector.selectOption('1')
  await expect.poll(() => new URL(page.url()).searchParams.get('warehouseId')).toBe('1')
  await expect(page.getByRole('heading', { name: '向分仓主动配货', exact: true })).toBeVisible()
  await expect(page.getByText('DB-JZ-001', { exact: true })).toBeVisible()
  expect(log.consoleErrors).toEqual([])
})

test('Shandong-only admin creates replenishment through the backend-provided route', async ({ page }) => {
  const log = await prepare(page, shandongAdminSession)
  await page.goto('/warehouse/transfers')
  await expect(page.getByLabel('当前仓库')).toHaveCount(0)
  await page.getByRole('button', { name: '增加物料', exact: true }).click()
  await page.getByLabel('调拨物料', { exact: true }).selectOption('11')
  await page.getByLabel('调拨数量', { exact: true }).fill('3')
  await page.getByRole('button', { name: '保存调拨草稿', exact: true }).click()
  await expect.poll(() => log.transferBody).not.toBeNull()
  expect(log.transferBody).toMatchObject({
    sourceWarehouseId: 1,
    targetWarehouseId: 2,
    lines: [{ itemId: 11, quantity: 3 }],
  })
  expect(log.transferBody?.clientRequestId).toMatch(/^transfer-/)
  expect(log.consoleErrors).toEqual([])
})

test('store manager sees a read-only supply warehouse and requisition does not submit warehouse id', async ({ page }) => {
  const log = await prepare(page, storeSession)
  await page.goto('/store/inventory')
  const pageRoot = page.locator('.warehouse-page')
  await expect(pageRoot.getByLabel('供货仓')).toContainText('荆州总仓')
  await expect(pageRoot.getByLabel('当前仓库')).toHaveCount(0)
  await expect(pageRoot).not.toContainText('外部采购')

  await page.goto('/store/inventory/requisition')
  await pageRoot.getByLabel('物料').selectOption('11')
  await pageRoot.getByLabel('叫货数量').fill('2')
  await pageRoot.getByRole('button', { name: '添加', exact: true }).click()
  await pageRoot.getByRole('button', { name: '提交叫货', exact: true }).click()
  await expect.poll(() => log.requisitionBody).not.toBeNull()
  expect(log.requisitionBody).not.toHaveProperty('warehouseId')
  expect(log.requisitionBody).not.toHaveProperty('storeId')
  expect(log.consoleErrors).toEqual([])
})

test('warehouse handles insufficient stock with explicit available, shortage and partial shipment actions', async ({ page }) => {
  const log = await prepare(page, requisitionProcessorSession)
  await page.goto('/warehouse/requests?warehouseId=1')

  const requisitionRow = page.locator('tbody tr').filter({ hasText: shortageRequisition.id }).first()
  await expect(requisitionRow).toBeVisible()
  await expect(requisitionRow).toContainText('鲜牛奶 申请 5 箱 / 已发 0 箱')
  await expect(requisitionRow).toContainText('鲜牛奶缺货 2 箱')
  for (const action of ['按可用库存发货', '标记缺货', '等补货后再发', '驳回']) {
    await expect(requisitionRow.getByRole('button', { name: action, exact: true })).toBeVisible()
  }
  await expect(requisitionRow.getByRole('button', { name: '审核通过', exact: true })).toHaveCount(0)

  await requisitionRow.getByRole('button', { name: '查看明细', exact: true }).click()
  const detail = page.locator('.detail-line').filter({ hasText: '鲜牛奶' })
  await expect(detail).toContainText('申请：5 箱')
  await expect(detail).toContainText('待发：5 箱')
  await expect(detail).toContainText('当前可发：3 箱')
  await expect(detail).toContainText('已发：0 箱')
  await expect(detail).toContainText('缺货：2 箱')

  await requisitionRow.getByRole('button', { name: '按可用库存发货', exact: true }).click()
  const dialog = page.getByRole('alertdialog', { name: '按可用库存发货' })
  await expect(dialog).toContainText('只扣减实际发出数量，未发数量转为缺货待处理')
  await dialog.getByRole('button', { name: '确认部分发货', exact: true }).click()

  await expect.poll(() => log.requisitionReviewBodies.length).toBe(1)
  expect(log.requisitionReviewBodies[0]).toMatchObject({
    approved: true,
    handlingMode: 'AVAILABLE_ONLY',
    lines: [{ itemId: 11, approvedQuantity: 5 }],
  })
  await expect.poll(() => log.requisitionShipCount).toBe(1)
  await page.screenshot({ path: '../output/playwright/warehouse-requisition-shortage-desktop.png', fullPage: true })
  expect(log.consoleErrors).toEqual([])
})

test('warehouse requisition filters list each store and apply the selected submission date', async ({ page }) => {
  const log = await prepare(page, requisitionProcessorSession)
  await page.goto('/warehouse/requests?warehouseId=1')

  const panel = page.locator('.content-card').filter({ hasText: '门店叫货待处理' })
  await expect(panel).toHaveCount(1)
  const storeFilter = panel.getByRole('combobox', { name: '叫货门店', exact: true })
  const dateFilter = panel.getByLabel('提交日期', { exact: true })

  await expect(storeFilter.getByRole('option', { name: '全部门店', exact: true })).toHaveCount(1)
  await expect(storeFilter.getByRole('option', { name: '荆州之星店', exact: true })).toHaveCount(1)
  await expect(storeFilter.getByRole('option', { name: '滨江万达店', exact: true })).toHaveCount(1)
  await expect(storeFilter.getByRole('option', { name: '西南商圈店', exact: true })).toHaveCount(1)

  await storeFilter.selectOption('bw1')
  await expect(panel.getByRole('row').filter({ hasText: availableRequisition.id })).toBeVisible()
  await expect(panel.getByRole('row').filter({ hasText: shortageRequisition.id })).toHaveCount(0)

  await dateFilter.fill('2026-07-24')
  await expect(panel).toContainText('没有符合筛选条件的叫货单')

  await storeFilter.selectOption('')
  await expect(panel.getByRole('row').filter({ hasText: shortageRequisition.id })).toBeVisible()
  await expect(panel.getByRole('row').filter({ hasText: availableRequisition.id })).toHaveCount(0)

  await dateFilter.fill('')
  await expect(panel.getByRole('row').filter({ hasText: shortageRequisition.id })).toBeVisible()
  await expect(panel.getByRole('row').filter({ hasText: availableRequisition.id })).toBeVisible()
  expect(log.consoleErrors).toEqual([])
})

test('warehouse shortage controls remain usable without whole-page overflow on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const log = await prepare(page, requisitionProcessorSession)
  await page.goto('/warehouse/requests?warehouseId=1')

  const requisitionRow = page.locator('tbody tr').filter({ hasText: shortageRequisition.id }).first()
  await expect(requisitionRow).toBeVisible()
  const availableButton = requisitionRow.getByRole('button', { name: '按可用库存发货', exact: true })
  await expect(availableButton).toBeVisible()
  await expect(requisitionRow.getByRole('button', { name: '等补货后再发', exact: true })).toBeVisible()
  await availableButton.scrollIntoViewIfNeeded()
  for (const action of ['按可用库存发货', '标记缺货', '等补货后再发', '驳回']) {
    const box = await requisitionRow.getByRole('button', { name: action, exact: true }).boundingBox()
    expect(box?.x).toBeGreaterThanOrEqual(0)
    expect((box?.x || 0) + (box?.width || 0)).toBeLessThanOrEqual(390)
    expect(box?.height).toBeGreaterThanOrEqual(44)
  }
  const viewport = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1)
  await page.screenshot({ path: '../output/playwright/warehouse-requisition-shortage-mobile.png', fullPage: true })
  await requisitionRow.screenshot({ path: '../output/playwright/warehouse-requisition-shortage-mobile-card.png' })
  expect(log.consoleErrors).toEqual([])
})

test('warehouse role keeps its tenant-wide warehouse scope read-only', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/users')
  await page.getByRole('button', { name: '配置 warehouse_admin 的账号授权' }).click()
  await expect(page.getByLabel('仓库数据范围')).toHaveValue('ALL')
  await expect(page.getByLabel('仓库数据范围')).toBeDisabled()
  await expect(page.getByLabel('指定仓库范围')).toHaveCount(0)
  await page.screenshot({ path: '../output/playwright/warehouse-permission-scope.png', fullPage: true })
  expect(log.accessProfileBody).toBeNull()
  expect(log.consoleErrors).toEqual([])
})

test('regional warehouse context remains usable without page overflow on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/shandong')

  const pageRoot = page.locator('.warehouse-page')
  await expect(pageRoot.getByText('山东分仓', { exact: true }).first()).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '向上级总仓申请补货', exact: true })).toBeVisible()
  await expect(pageRoot.getByRole('link', { name: '外部采购', exact: true })).toHaveCount(0)
  const viewport = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1)
  await page.screenshot({ path: '../output/playwright/warehouse-shandong-mobile.png', fullPage: true })
  expect(log.consoleErrors).toEqual([])
})
