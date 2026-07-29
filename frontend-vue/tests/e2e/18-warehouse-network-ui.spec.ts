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

function riskOverview() {
  const baseOverview = overview(1)
  return {
    ...baseOverview,
    summary: {
      ...baseOverview.summary,
      itemCount: 3,
      lowStockCount: 1,
      expiringCount: 1,
    },
    alerts: [{
      severity: 'WARNING',
      type: 'LOW_STOCK',
      itemId: 11,
      itemName: '低库存冻品',
      message: '当前库存 1 箱，请安排补货',
    }, {
      severity: 'WARNING',
      type: 'EXPIRING',
      itemId: 12,
      itemName: '临期原料',
      message: '最近批次将在 3 天后到期',
    }],
    items: [{
      ...baseOverview.items[0],
      id: 11,
      code: 'LOW-11',
      name: '低库存冻品',
      stockQuantity: 1,
      minStockQuantity: 5,
      stockStatus: '低库存',
      alertLevel: 'LOW',
    }, {
      ...baseOverview.items[0],
      id: 12,
      code: 'EXP-12',
      name: '临期原料',
      nearestExpiryDate: '2026-07-31',
      stockStatus: '临期',
      alertLevel: 'EXPIRING',
    }, {
      ...baseOverview.items[0],
      id: 13,
      code: 'NORMAL-13',
      name: '正常物料',
      stockStatus: '正常',
      alertLevel: 'NORMAL',
    }],
  }
}

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
  itemSaveCallCount: number
  itemDeleteIds: number[]
  movementQueryBodies: Record<string, unknown>[]
  requisitionBody: Record<string, unknown> | null
  requisitionReviewBodies: Record<string, unknown>[]
  requisitionShipCount: number
  transferBody: Record<string, unknown> | null
  accessProfileBody: Record<string, unknown> | null
  consoleErrors: string[]
}

// Mutable state for controlling item-save mock responses across tests
let mockItemSaveResponses: Array<{ status: number; code?: string; message?: string }> = []
let mockItemSaveCallIndex = 0
let mockItemSaveDelay = 0
let mockItemDeleteResponse: { status: number; code?: string; message?: string } | null = null
let mockItemDeleteDelay = 0
let mockDeletedItemIds = new Set<number>()
let mockOverviewFails = false
let mockOverviewCallCount = 0
let mockOverviewFailOnCall = -1
let mockOverviewOverride: ReturnType<typeof overview> | null = null

function resetItemSaveMock() {
  mockItemSaveResponses = []
  mockItemSaveCallIndex = 0
  mockItemSaveDelay = 0
  mockItemDeleteResponse = null
  mockItemDeleteDelay = 0
  mockDeletedItemIds = new Set<number>()
  mockOverviewFails = false
  mockOverviewCallCount = 0
  mockOverviewFailOnCall = -1
  mockOverviewOverride = null
}

async function prepare(page: Page, session: typeof baseSession) {
  const log: RequestLog = {
    urls: [],
    itemBodies: [],
    itemSaveCallCount: 0,
    itemDeleteIds: [],
    movementQueryBodies: [],
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
    mockOverviewCallCount += 1
    const shouldFail = mockOverviewFails || mockOverviewCallCount === mockOverviewFailOnCall
    if (shouldFail) {
      return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ success: false, code: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用' }) })
    }
    const warehouseId = Number(url.searchParams.get('warehouseId') || (session.role === 'STORE_MANAGER' ? 1 : 1))
    const data = mockOverviewOverride || overview(warehouseId, session.id === requisitionProcessorSession.id)
    return route.fulfill(ok({
      ...data,
      items: data.items.filter((item) => !mockDeletedItemIds.has(item.id)),
    }))
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
    log.itemSaveCallCount += 1
    const resp = mockItemSaveResponses[mockItemSaveCallIndex]
    mockItemSaveCallIndex += 1
    if (mockItemSaveDelay > 0) {
      await new Promise((resolve) => setTimeout(resolve, mockItemSaveDelay))
    }
    if (resp && resp.status >= 400) {
      return route.fulfill({
        status: resp.status,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, code: resp.code, message: resp.message }),
      })
    }
    log.itemBodies.push(request.postDataJSON())
    return route.fulfill(ok(null))
  }
  const itemDeleteMatch = path.match(/^\/api\/warehouse\/items\/(\d+)$/)
  if (itemDeleteMatch && request.method() === 'DELETE') {
    const itemId = Number(itemDeleteMatch[1])
    log.itemDeleteIds.push(itemId)
    if (mockItemDeleteDelay > 0) {
      await new Promise((resolve) => setTimeout(resolve, mockItemDeleteDelay))
    }
    if (mockItemDeleteResponse && mockItemDeleteResponse.status >= 400) {
      return route.fulfill({
        status: mockItemDeleteResponse.status,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: mockItemDeleteResponse.code,
          message: mockItemDeleteResponse.message,
        }),
      })
    }
    mockDeletedItemIds.add(itemId)
    return route.fulfill(ok(null))
  }
  if (path === '/api/warehouse/movements/filter-options') {
    return route.fulfill(ok({
      stores: [
        { id: 'rg1', name: '荆州之星店', code: 'RG001', area: '荆州区域', status: '营业中' },
        { id: 'bw1', name: '滨江万达店', code: 'BW001', area: '山东区域', status: '营业中' },
      ],
      items: [
        { id: 11, name: '鲜牛奶', code: 'MILK-01', category: '奶制品', unit: '箱', active: true },
        { id: 12, name: '双杯纸袋', code: '01003', category: '包装', unit: '个', active: true },
      ],
    }))
  }
  if (path === '/api/warehouse/movements/query' && request.method() === 'POST') {
    const body = request.postDataJSON() as {
      warehouseId: number
      startDate: string
      endDate: string
      storeIds?: string[]
      itemIds?: number[]
      directions?: string[]
      page?: number
      pageSize?: number
    }
    log.movementQueryBodies.push(body)
    const allRows = [{
      id: 501,
      itemId: 11,
      itemName: '鲜牛奶',
      movementType: 'OUT',
      movementTypeLabel: '配送出库',
      quantityDelta: -5,
      sourceType: 'REQUISITION',
      sourceId: 'REQ-501',
      storeId: 'rg1',
      storeName: '荆州之星店',
      warehouseId: 1,
      warehouseName: '荆州总仓',
      sourceWarehouseId: 1,
      sourceWarehouseName: '荆州总仓',
      operatorName: '仓库管理员',
      createdAt: '2026-07-20 09:30',
      batchNo: 'MILK-202607',
    }, {
      id: 502,
      itemId: 12,
      itemName: '双杯纸袋',
      movementType: 'IN',
      movementTypeLabel: '采购入库',
      quantityDelta: 100,
      sourceType: 'PURCHASE_ORDER',
      sourceId: 'PO-502',
      storeId: null,
      storeName: null,
      warehouseId: 1,
      warehouseName: '荆州总仓',
      targetWarehouseId: 1,
      targetWarehouseName: '荆州总仓',
      operatorName: '仓库管理员',
      createdAt: '2026-07-19 10:00',
      batchNo: 'BAG-202607',
    }]
    const rows = allRows.filter((row) => {
      if (body.storeIds?.length && (!row.storeId || !body.storeIds.includes(row.storeId))) return false
      if (body.itemIds?.length && !body.itemIds.includes(row.itemId)) return false
      if (body.directions?.includes('IN') && row.quantityDelta <= 0) return false
      if (body.directions?.includes('OUT') && row.quantityDelta >= 0) return false
      return true
    })
    const totalIn = rows.filter((row) => row.quantityDelta > 0).reduce((sum, row) => sum + row.quantityDelta, 0)
    const totalOut = Math.abs(rows.filter((row) => row.quantityDelta < 0).reduce((sum, row) => sum + row.quantityDelta, 0))
    return route.fulfill(ok({
      rows,
      total: rows.length,
      page: body.page || 1,
      pageSize: body.pageSize || 50,
      totalIn,
      totalOut,
      netChange: totalIn - totalOut,
    }))
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

test.beforeEach(() => {
  resetItemSaveMock()
})

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

test('view inventory reveals the low-stock and expiring material union', async ({ page }) => {
  mockOverviewOverride = riskOverview()
  const log = await prepare(page, baseSession)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/warehouse/detail/1')

  const alertPanel = page.locator('.warehouse-alerts-panel')
  const inventory = page.locator('.inventory-main')
  await alertPanel.getByRole('button', { name: '查看库存', exact: true }).click()

  await expect(inventory).toBeInViewport()
  await expect(inventory.getByRole('checkbox', { name: '低库存', exact: true })).toBeChecked()
  await expect(inventory.getByRole('checkbox', { name: '临期', exact: true })).toBeChecked()
  await expect(inventory.locator('tbody tr').filter({ hasText: '低库存冻品' })).toBeVisible()
  await expect(inventory.locator('tbody tr').filter({ hasText: '临期原料' })).toBeVisible()
  await expect(inventory.locator('tbody tr').filter({ hasText: '正常物料' })).toHaveCount(0)
  expect(log.consoleErrors).toEqual([])
})

test('clicking one inventory alert focuses its corresponding material row', async ({ page }) => {
  mockOverviewOverride = riskOverview()
  const log = await prepare(page, baseSession)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/warehouse/detail/1')

  const alertPanel = page.locator('.warehouse-alerts-panel')
  const inventory = page.locator('.inventory-main')
  await alertPanel.getByRole('button', { name: '查看临期原料库存', exact: true }).click()
  const targetRow = inventory.locator('tbody tr[data-inventory-target="true"]')
  await expect(targetRow).toHaveCount(1)
  await expect(targetRow).toContainText('临期原料')
  await expect(targetRow).toBeInViewport()
  await expect(targetRow).toBeFocused()
  expect(log.consoleErrors).toEqual([])
})

test('a risk deep link opens and focuses the exact inventory material', async ({ page }) => {
  mockOverviewOverride = riskOverview()
  const log = await prepare(page, baseSession)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.goto('/warehouse/alerts?warehouseId=1&itemId=12&month=2026-07')

  const inventory = page.locator('.inventory-main')
  const expiringRow = inventory.locator('tbody tr[data-inventory-target="true"]')
  await expect(expiringRow).toHaveCount(1)
  await expect(expiringRow).toContainText('临期原料')
  await expect(expiringRow).toBeFocused()
  await expect(inventory.getByRole('checkbox', { name: '临期', exact: true })).toBeChecked()

  await page.goto('/warehouse/alerts?warehouseId=1&itemId=13&adjustmentId=701&month=2026-07')
  const adjustmentRow = inventory.locator('tbody tr[data-inventory-target="true"]')
  await expect(adjustmentRow).toHaveCount(1)
  await expect(adjustmentRow).toContainText('正常物料')
  await expect(adjustmentRow).toBeFocused()
  await expect(inventory.getByRole('checkbox', { name: '低库存', exact: true })).not.toBeChecked()
  await expect(inventory.getByRole('checkbox', { name: '临期', exact: true })).not.toBeChecked()
  expect(log.consoleErrors).toEqual([])
})

test('material category action icons remain fully visible before hover', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/warehouse/detail/1')

  const tree = page.locator('.warehouse-category-tree')
  const actionNames = ['为耗材新增下级分类', '编辑分类耗材', '删除分类耗材']

  for (const name of actionNames) {
    const button = tree.getByRole('button', { name })
    await expect(button).toBeVisible()
    const geometry = await button.evaluate((element) => {
      const buttonBox = element.getBoundingClientRect()
      const lineBox = element.closest('.category-line')?.getBoundingClientRect()
      const iconBox = element.querySelector('svg')?.getBoundingClientRect()
      const style = getComputedStyle(element)
      return {
        buttonWidth: buttonBox.width,
        buttonHeight: buttonBox.height,
        iconWidth: iconBox?.width || 0,
        iconHeight: iconBox?.height || 0,
        withinRow: Boolean(lineBox && buttonBox.left >= lineBox.left && buttonBox.right <= lineBox.right),
        opacity: style.opacity,
        color: style.color,
      }
    })

    expect(geometry.buttonWidth).toBeGreaterThanOrEqual(28)
    expect(geometry.buttonHeight).toBeGreaterThanOrEqual(28)
    expect(geometry.iconWidth).toBeGreaterThanOrEqual(13)
    expect(geometry.iconHeight).toBeGreaterThanOrEqual(13)
    expect(geometry.withinRow).toBe(true)
    expect(geometry.opacity).toBe('1')
    expect(geometry.color).not.toBe('rgba(0, 0, 0, 0)')
  }

  await tree.getByRole('button', { name: '编辑分类耗材' }).click()
  await expect(tree.getByText('编辑分类', { exact: true })).toBeVisible()
  await tree.getByRole('button', { name: '取消', exact: true }).click()
  await expect(tree.getByText('编辑分类', { exact: true })).not.toBeVisible()
  expect(log.consoleErrors).toEqual([])
})

test('material editor keeps completion actions visible and validates an incomplete form', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/detail/1')

  const openButton = page.getByRole('button', { name: '新增物料', exact: true })
  await openButton.click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  const saveButton = editor.getByRole('button', { name: '保存物料' })
  const cancelButton = editor.getByRole('button', { name: '取消', exact: true })

  await expect(editor.getByLabel('物品名称')).toBeFocused()
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('hidden')
  await expect(saveButton).toBeInViewport()
  await expect(cancelButton).toBeInViewport()
  await expect(saveButton).toBeEnabled()
  await expect(saveButton).toHaveText('完成新增')
  await expect(editor.getByText('请选择全部门店或指定区域、指定门店。')).not.toBeVisible()

  const editorBody = editor.locator('.material-editor-body')
  await editorBody.evaluate((element) => { element.scrollTop = element.scrollHeight })
  await expect(saveButton).toBeInViewport()
  await expect(cancelButton).toBeInViewport()
  await editorBody.evaluate((element) => { element.scrollTop = 0 })

  await saveButton.click()

  await expect(editor.locator('.material-validation-summary')).toBeVisible()
  await expect(editor.locator('.material-validation-summary')).toContainText('请完成以下必填项')
  await expect(editor.getByLabel('物品名称')).toHaveAttribute('aria-invalid', 'true')
  await expect(editor.getByLabel('类别')).toHaveAttribute('aria-invalid', 'true')
  await expect(editor.getByLabel('编号')).toHaveAttribute('aria-invalid', 'true')
  await expect(editor.locator('.scope-error')).toHaveText('请选择全部门店或指定区域、指定门店。')
  await expect(editor.getByLabel('物品名称')).toBeFocused()
  expect(log.itemSaveCallCount).toBe(0)
  expect(log.consoleErrors).toEqual([])

  await cancelButton.click()
  await expect(editor).toHaveCount(0)
  expect(await page.evaluate(() => document.body.style.overflow)).toBe('')
  await expect(openButton).toBeFocused()
})

test('material editor blocks invalid numeric values with an inline error before saving', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('数值校验测试物料')
  await editor.getByLabel('编号').fill('NUMBER-INVALID')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /指定区域/ }).check()
  await editor.getByRole('checkbox', { name: '荆州区域 JINGZHOU', exact: true }).check()
  await editor.getByLabel('采购单价').fill('-1')

  await editor.getByRole('button', { name: '保存物料' }).click()

  await expect(editor).toBeVisible()
  await expect(editor.getByLabel('采购单价')).toHaveAttribute('aria-invalid', 'true')
  await expect(editor.locator('#material-error-unit-price')).toHaveText('采购单价不能小于 0。')
  await expect(editor.getByLabel('采购单价')).toBeFocused()
  expect(log.itemSaveCallCount).toBe(0)
  expect(log.consoleErrors).toEqual([])
})

test('warehouse item delete requires confirmation, removes an unused item, and restores focus on cancel', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/detail/1')

  const itemRow = page.getByRole('row', { name: /鲜牛奶/ })
  const deleteButton = itemRow.getByRole('button', { name: '删除物料 鲜牛奶' })
  await expect(deleteButton).toBeVisible()

  await deleteButton.click()
  let confirmation = page.getByRole('alertdialog', { name: '删除物料“鲜牛奶”？' })
  await expect(confirmation).toContainText('删除后无法恢复')
  await expect(confirmation).toContainText('已有业务记录请使用“停用”')
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  await expect(confirmation).toHaveCount(0)
  await expect(deleteButton).toBeFocused()
  expect(log.itemDeleteIds).toEqual([])

  await deleteButton.click()
  confirmation = page.getByRole('alertdialog', { name: '删除物料“鲜牛奶”？' })
  await confirmation.getByRole('button', { name: '确认删除' }).click()

  await expect(confirmation).toHaveCount(0)
  await expect(page.getByRole('row', { name: /鲜牛奶/ })).toHaveCount(0)
  await expect(page.locator('.success-box')).toContainText('物料“鲜牛奶”已删除')
  expect(log.itemDeleteIds).toEqual([11])
  expect(log.consoleErrors).toEqual([])
})

test('referenced item delete keeps confirmation open and shows a useful error', async ({ page }) => {
  mockItemDeleteResponse = {
    status: 409,
    code: 'ITEM_IN_USE',
    message: '该物料已有库存或业务记录，不能删除；请改为停用。',
  }
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/detail/1')

  const itemRow = page.getByRole('row', { name: /鲜牛奶/ })
  await itemRow.getByRole('button', { name: '删除物料 鲜牛奶' }).click()
  const confirmation = page.getByRole('alertdialog', { name: '删除物料“鲜牛奶”？' })
  await confirmation.getByRole('button', { name: '确认删除' }).click()

  await expect(confirmation).toBeVisible()
  await expect(confirmation.getByRole('alert')).toHaveText('该物料已有库存或业务记录，不能删除；请改为停用。')
  await expect(confirmation.getByRole('button', { name: '确认删除' })).toBeEnabled()
  await expect(itemRow).toBeVisible()
  expect(log.itemDeleteIds).toEqual([11])
  expect(log.consoleErrors.filter((message) => message.includes('pageerror'))).toEqual([])
})

test('item editor requires explicit scope, confirms all stores, and submits region-store union', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  let editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('全门店测试物料')
  await editor.getByLabel('编号').fill('SCOPE-ALL')
  await editor.getByLabel('类别').selectOption('1')
  await expect(editor.getByRole('button', { name: '保存物料' })).toBeEnabled()
  await expect(editor.locator('.scope-error')).toHaveCount(0)
  await editor.getByRole('button', { name: '保存物料' }).click()
  await expect(editor.locator('.scope-error')).toHaveText('请选择全部门店或指定区域、指定门店。')
  expect(log.itemSaveCallCount).toBe(0)

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
  const transferLine = page.locator('.transfer-line').first()
  const materialSelect = transferLine.getByRole('combobox', { name: '调拨物料', exact: true })
  await materialSelect.fill('01003')
  await expect(transferLine.getByRole('option')).toHaveCount(1)
  await transferLine.getByRole('option', { name: /双杯纸袋.*01003.*可发 60 个/ }).click()

  const alignedLineControls = await Promise.all([
    transferLine.locator('.searchable-single-select__control').boundingBox(),
    transferLine.getByLabel('调拨数量', { exact: true }).boundingBox(),
    transferLine.getByPlaceholder('选填', { exact: true }).boundingBox(),
    transferLine.getByRole('button', { name: '删除调拨物料', exact: true }).boundingBox(),
  ])
  expect(alignedLineControls.every(Boolean)).toBe(true)
  const lineControlTops = alignedLineControls.map((box) => box!.y)
  const lineControlHeights = alignedLineControls.map((box) => box!.height)
  if ((page.viewportSize()?.width || 0) > 820) {
    expect(Math.max(...lineControlTops) - Math.min(...lineControlTops)).toBeLessThanOrEqual(1)
  } else {
    const lineControlLefts = alignedLineControls.map((box) => box!.x)
    const lineControlWidths = alignedLineControls.map((box) => box!.width)
    expect(Math.max(...lineControlLefts) - Math.min(...lineControlLefts)).toBeLessThanOrEqual(1)
    expect(Math.max(...lineControlWidths) - Math.min(...lineControlWidths)).toBeLessThanOrEqual(1)
  }
  expect(Math.max(...lineControlHeights) - Math.min(...lineControlHeights)).toBeLessThanOrEqual(1)

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
  const material = page.getByRole('combobox', { name: '调拨物料', exact: true })
  await material.fill('MILK-01')
  await page.getByRole('option', { name: /鲜牛奶.*MILK-01.*箱/ }).click()
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
  const requisitionMaterial = pageRoot.getByRole('combobox', { name: '叫货物料', exact: true })
  await requisitionMaterial.fill('MILK-01')
  await pageRoot.getByRole('option', { name: /鲜牛奶.*MILK-01.*箱/ }).click()
  await pageRoot.getByLabel('叫货数量').fill('2')
  await pageRoot.getByRole('button', { name: '添加', exact: true }).click()
  await pageRoot.getByRole('button', { name: '提交叫货', exact: true }).click()
  await expect.poll(() => log.requisitionBody).not.toBeNull()
  expect(log.requisitionBody).not.toHaveProperty('warehouseId')
  expect(log.requisitionBody).not.toHaveProperty('storeId')
  expect(log.consoleErrors).toEqual([])
})

test('warehouse handles insufficient stock with editable review price and immediate negative inventory transfer', async ({ page }) => {
  const log = await prepare(page, requisitionProcessorSession)
  await page.goto('/warehouse/requests?warehouseId=1')

  const requisitionRow = page.locator('tbody tr').filter({ hasText: shortageRequisition.id }).first()
  await expect(requisitionRow).toBeVisible()
  await expect(requisitionRow).toContainText('鲜牛奶 申请 5 箱 / 已完成 0 箱')
  await expect(requisitionRow).toContainText('鲜牛奶缺货 2 箱')
  for (const action of ['审核并完成', '驳回']) {
    await expect(requisitionRow.getByRole('button', { name: action, exact: true })).toBeVisible()
  }
  for (const action of ['按可用库存审核', '标记缺货', '等补货后再发']) {
    await expect(requisitionRow.getByRole('button', { name: action, exact: true })).toHaveCount(0)
  }

  await requisitionRow.getByRole('button', { name: '查看明细', exact: true }).click()
  const detail = page.locator('.detail-line').filter({ hasText: '鲜牛奶' })
  await expect(detail).toContainText('申请：5 箱')
  await expect(detail).toContainText('本次审核：5 箱')
  await expect(detail).toContainText('当前仓库库存：3 箱')
  await expect(detail).toContainText('审核后仓库库存：-2 箱')
  await expect(detail).toContainText('已完成：0 箱')
  await expect(detail).toContainText('不转待补货')

  await requisitionRow.getByRole('button', { name: '审核并完成', exact: true }).click()
  await page.getByRole('spinbutton', { name: '鲜牛奶审核单价' }).fill('52.30')
  await page.getByRole('button', { name: '确认审核并完成', exact: true }).click()

  await expect.poll(() => log.requisitionReviewBodies.length).toBe(1)
  expect(log.requisitionReviewBodies[0]).toMatchObject({
    approved: true,
    handlingMode: 'FULL',
    completeOnReview: true,
    lines: [{ itemId: 11, approvedQuantity: 5, unitPrice: 52.3 }],
  })
  expect(log.requisitionShipCount).toBe(0)
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

  await storeFilter.click()
  await expect(panel.getByRole('option', { name: '全部门店', exact: true })).toHaveCount(1)
  await expect(panel.getByRole('option', { name: /荆州之星店/ })).toHaveCount(1)
  await expect(panel.getByRole('option', { name: /滨江万达店/ })).toHaveCount(1)
  await expect(panel.getByRole('option', { name: /西南商圈店/ })).toHaveCount(1)

  await storeFilter.fill('滨江')
  await panel.getByRole('option', { name: /滨江万达店/ }).click()
  await expect(panel.getByRole('row').filter({ hasText: availableRequisition.id })).toBeVisible()
  await expect(panel.getByRole('row').filter({ hasText: shortageRequisition.id })).toHaveCount(0)

  await dateFilter.fill('2026-07-24')
  await expect(panel).toContainText('没有符合筛选条件的叫货单')

  await storeFilter.click()
  await panel.getByRole('option', { name: '全部门店', exact: true }).click()
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
  const reviewButton = requisitionRow.getByRole('button', { name: '审核并完成', exact: true })
  await expect(reviewButton).toBeVisible()
  await expect(requisitionRow.getByRole('button', { name: '等补货后再发', exact: true })).toHaveCount(0)
  await reviewButton.scrollIntoViewIfNeeded()
  for (const action of ['审核并完成', '驳回']) {
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

test('warehouse role exposes its assigned warehouse list without silently saving', async ({ page }) => {
  const log = await prepare(page, baseSession)
  await page.goto('/users')
  await page.getByRole('button', { name: '配置 warehouse_admin 的账号授权' }).click()
  await expect(page.getByLabel('仓库数据范围')).toHaveValue('WAREHOUSE_LIST')
  await expect(page.getByLabel('仓库数据范围')).toBeEnabled()
  const warehouseScope = page.getByLabel('指定仓库范围')
  await expect(warehouseScope).toBeVisible()
  await expect(warehouseScope.getByRole('checkbox', { name: '荆州总仓 · 总仓' })).toBeChecked()
  await expect(warehouseScope.getByRole('checkbox', { name: '山东分仓 · 区域分仓' })).not.toBeChecked()
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

// ─── Material Editor Save Error Tests ─────────────────────────────────────────

test('CATEGORY_DISABLED shows error inside dialog with aria-invalid on category field', async ({ page }) => {
  mockItemSaveResponses = [{ status: 400, code: 'CATEGORY_DISABLED', message: '商品类别不存在或已停用' }]
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('测试物料')
  await editor.getByLabel('编号').fill('TEST-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /全部门店/ }).check()

  // Confirm all-stores
  await editor.getByRole('button', { name: '保存物料' }).click()
  const confirmation = page.getByRole('alertdialog', { name: '确认允许全部门店叫货？' })
  await confirmation.getByRole('button', { name: '确认全部门店并保存' }).click()

  // Error must be inside the editor dialog
  await expect(editor.locator('.material-submit-error')).toBeVisible()
  await expect(editor.locator('.material-submit-error')).toContainText('物料未保存')
  await expect(editor.locator('.material-submit-error')).toContainText('商品类别不存在或已停用')
  await expect(editor.locator('.material-submit-error')).toHaveAttribute('role', 'alert')

  // Category field has aria-invalid
  await expect(editor.getByLabel('类别')).toHaveAttribute('aria-invalid', 'true')

  // Form data preserved
  await expect(editor.getByLabel('物品名称')).toHaveValue('测试物料')
  await expect(editor.getByLabel('编号')).toHaveValue('TEST-001')
  await expect(editor.getByLabel('类别')).toHaveValue('1')

  // Dialog stays open, save button re-enabled
  await expect(editor).toBeVisible()
  await expect(editor.getByRole('button', { name: '保存物料' })).toBeEnabled()
  await expect(editor.getByRole('button', { name: '保存物料' })).toBeInViewport()
  await expect(editor.getByRole('button', { name: '取消', exact: true })).toBeInViewport()

  // Only one POST was made
  expect(log.itemSaveCallCount).toBe(1)
  // Console errors from API error logging are expected in dev mode
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('service unavailable then retry succeeds with error in dialog and second POST closing editor', async ({ page }) => {
  mockItemSaveResponses = [
    { status: 503, code: 'SERVICE_UNAVAILABLE', message: '服务暂时不可用，请稍后重试' },
    { status: 200 },
  ]
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('重试测试物料')
  await editor.getByLabel('编号').fill('RETRY-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /全部门店/ }).check()

  // First attempt: confirm all-stores → 503 error
  await editor.getByRole('button', { name: '保存物料' }).click()
  const confirmation = page.getByRole('alertdialog', { name: '确认允许全部门店叫货？' })
  await confirmation.getByRole('button', { name: '确认全部门店并保存' }).click()

  // Error visible inside dialog
  await expect(editor.locator('.material-submit-error')).toBeVisible()
  await expect(editor.locator('.material-submit-error')).toContainText('物料未保存')
  await expect(editor.locator('.material-submit-error')).toContainText('服务暂时不可用')

  // Form data preserved
  await expect(editor.getByLabel('物品名称')).toHaveValue('重试测试物料')
  await expect(editor.getByLabel('编号')).toHaveValue('RETRY-001')

  // Second attempt: confirm again succeeds
  await editor.getByRole('button', { name: '保存物料' }).click()
  const confirmation2 = page.getByRole('alertdialog', { name: '确认允许全部门店叫货？' })
  await confirmation2.getByRole('button', { name: '确认全部门店并保存' }).click()

  // Editor closes on success
  await expect(editor).toHaveCount(0)

  // Exactly 2 POST requests
  expect(log.itemSaveCallCount).toBe(2)
  expect(log.itemBodies.length).toBe(1) // only the second was recorded as success
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('all-stores confirmation failure returns to editor with error visible', async ({ page }) => {
  mockItemSaveResponses = [{ status: 400, code: 'REQUISITION_SCOPE_TARGET_REQUIRED', message: '指定范围时，请至少选择一个区域或门店' }]
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('全店确认失败测试')
  await editor.getByLabel('编号').fill('CONFIRM-FAIL-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /全部门店/ }).check()

  // Trigger save → opens all-stores confirmation
  await editor.getByRole('button', { name: '保存物料' }).click()
  const confirmation = page.getByRole('alertdialog', { name: '确认允许全部门店叫货？' })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '确认全部门店并保存' }).click()

  // Confirmation closed
  await expect(confirmation).toHaveCount(0)

  // Editor still open with error
  await expect(editor).toBeVisible()
  await expect(editor.locator('.material-submit-error')).toBeVisible()
  await expect(editor.locator('.material-submit-error')).toContainText('物料未保存')

  // Only one POST
  expect(log.itemSaveCallCount).toBe(1)
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('duplicate submit produces only one POST and blocks close during save', async ({ page }) => {
  mockItemSaveResponses = [{ status: 200 }]
  mockItemSaveDelay = 2000 // 2s delay to allow rapid clicks while saving
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('防重复测试')
  await editor.getByLabel('编号').fill('DUP-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /指定区域/ }).check()
  await editor.getByRole('checkbox', { name: '荆州区域 JINGZHOU', exact: true }).check()

  // Trigger submit via button click, then verify duplicate Enter is blocked while saving
  const saveButton = editor.getByRole('button', { name: '保存物料' })
  await saveButton.click()
  // Press Enter on code field while save is in progress (2s delay) - should be ignored
  await editor.getByLabel('编号').press('Enter')

  // Wait for save to complete and editor to close
  await expect(editor).toHaveCount(0, { timeout: 5000 })

  // Only one POST was sent
  expect(log.itemSaveCallCount).toBe(1)
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('save success with refresh failure shows refresh message not save failure', async ({ page }) => {
  mockItemSaveResponses = [{ status: 200 }]
  // Fail the second overview call (after save triggers loadAll), not the initial page load
  mockOverviewFailOnCall = 2
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('刷新失败测试')
  await editor.getByLabel('编号').fill('REFRESH-FAIL-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /指定区域/ }).check()
  await editor.getByRole('checkbox', { name: '荆州区域 JINGZHOU', exact: true }).check()

  await editor.getByRole('button', { name: '保存物料' }).click()

  // Editor closes (save succeeded)
  await expect(editor).toHaveCount(0)

  // Page-level refresh failure is promoted into the unified error dialog.
  const refreshError = page.getByRole('alertdialog', { name: '保存未完成' })
  await expect(refreshError).toContainText('物料已保存，但列表刷新失败')

  // Success message should also appear
  const successBox = page.locator('.warehouse-page .success-box')
  await expect(successBox).toContainText('物料档案已新增')
  await refreshError.getByRole('button', { name: '重试' }).click()
  await expect(refreshError).toHaveCount(0)

  // Only one POST
  expect(log.itemSaveCallCount).toBe(1)
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('closing and reopening editor clears previous error', async ({ page }) => {
  mockItemSaveResponses = [{ status: 400, code: 'CATEGORY_DISABLED', message: '商品类别不存在或已停用' }]
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  // First save attempt fails
  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await editor.getByLabel('物品名称').fill('旧错误测试')
  await editor.getByLabel('编号').fill('OLD-ERR-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /指定区域/ }).check()
  await editor.getByRole('checkbox', { name: '荆州区域 JINGZHOU', exact: true }).check()
  await editor.getByRole('button', { name: '保存物料' }).click()

  await expect(editor.locator('.material-submit-error')).toBeVisible()

  // Close editor
  await editor.getByRole('button', { name: '关闭物料编辑' }).click()
  const unsaved = page.getByRole('alertdialog', { name: '物料修改尚未保存' })
  await unsaved.getByRole('button', { name: '放弃修改' }).click()
  await expect(editor).toHaveCount(0)

  // Reopen: no old error
  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor2 = page.getByRole('dialog', { name: '新增物料档案' })
  await expect(editor2.locator('.material-submit-error')).toHaveCount(0)

  // Only one POST
  expect(log.itemSaveCallCount).toBe(1)
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('material editor error is visible on mobile viewport without overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  mockItemSaveResponses = [{ status: 400, code: 'CATEGORY_DISABLED', message: '商品类别不存在或已停用' }]
  const log = await prepare(page, baseSession)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await expect(editor.getByRole('button', { name: '保存物料' })).toBeInViewport()
  await expect(editor.getByRole('button', { name: '取消', exact: true })).toBeInViewport()
  await editor.getByLabel('物品名称').fill('移动端错误测试')
  await editor.getByLabel('编号').fill('MOBILE-ERR-001')
  await editor.getByLabel('类别').selectOption('1')
  await editor.getByRole('radio', { name: /指定区域/ }).check()
  await editor.getByRole('checkbox', { name: '荆州区域 JINGZHOU', exact: true }).check()
  await editor.getByRole('button', { name: '保存物料' }).click()

  await expect(editor.locator('.material-submit-error')).toBeVisible()
  await expect(editor.locator('.material-submit-error')).toContainText('物料未保存')
  await expect(editor.getByRole('button', { name: '保存物料' })).toBeInViewport()
  await expect(editor.getByRole('button', { name: '取消', exact: true })).toBeInViewport()

  const viewport = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1)
  expect(log.consoleErrors.filter((m) => m.includes('pageerror'))).toEqual([])
})

test('movement query applies store, date and material only after explicit query', async ({ page }) => {
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/movements')

  await expect(page.getByRole('heading', { name: '出入库查询' })).toBeVisible()
  await expect.poll(() => log.movementQueryBodies.length).toBe(1)
  await expect(page.getByRole('checkbox', { name: /荆州之星店/ })).toHaveCount(0)
  await expect(page.getByRole('checkbox', { name: /鲜牛奶/ })).toHaveCount(0)

  const alignedControls = await Promise.all([
    page.getByRole('button', { name: /选择查询门店/ }).boundingBox(),
    page.getByLabel('开始', { exact: true }).boundingBox(),
    page.getByLabel('结束', { exact: true }).boundingBox(),
    page.getByRole('button', { name: /选择查询物料/ }).boundingBox(),
  ])
  expect(alignedControls.every(Boolean)).toBe(true)
  const controlTops = alignedControls.map((box) => box!.y)
  const controlHeights = alignedControls.map((box) => box!.height)
  expect(Math.max(...controlTops) - Math.min(...controlTops)).toBeLessThanOrEqual(1)
  expect(Math.max(...controlHeights) - Math.min(...controlHeights)).toBeLessThanOrEqual(1)

  await page.getByRole('button', { name: /选择查询门店/ }).click()
  await page.getByRole('searchbox', { name: '选择查询门店' }).fill('RG001')
  await page.getByRole('checkbox', { name: /荆州之星店/ }).check()
  await page.getByRole('button', { name: '完成选择' }).click()

  await page.getByRole('button', { name: /选择查询物料/ }).click()
  await page.getByRole('searchbox', { name: '选择查询物料' }).fill('MILK-01')
  await page.getByRole('checkbox', { name: /鲜牛奶/ }).check()
  await page.getByRole('button', { name: '完成选择' }).click()

  await page.getByLabel('开始', { exact: true }).fill('2026-07-01')
  await page.getByLabel('结束', { exact: true }).fill('2026-07-25')
  await page.getByRole('button', { name: '出库', exact: true }).click()

  await expect(page.getByText('筛选条件已修改，点击“查询”后更新下方结果。')).toBeVisible()
  await expect(page.getByRole('button', { name: '导出查询结果' })).toBeDisabled()
  await page.waitForTimeout(500)
  expect(log.movementQueryBodies).toHaveLength(1)

  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect.poll(() => log.movementQueryBodies.length).toBe(2)
  expect(log.movementQueryBodies[1]).toMatchObject({
    warehouseId: 1,
    startDate: '2026-07-01',
    endDate: '2026-07-25',
    storeIds: ['rg1'],
    itemIds: [11],
    directions: ['OUT'],
    page: 1,
    pageSize: 50,
  })

  const applied = page.getByLabel('当前查询条件')
  await expect(applied).toContainText('2026-07-01 至 2026-07-25')
  await expect(applied).toContainText('荆州之星店')
  await expect(applied).toContainText('鲜牛奶')
  await expect(applied).toContainText('出库')
  await expect(page.getByRole('row', { name: /鲜牛奶/ })).toBeVisible()
  await expect(page.getByRole('row', { name: /双杯纸袋/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导出查询结果' })).toBeEnabled()
  expect(log.consoleErrors).toEqual([])
})

test('movement query validates date range locally and reset restores defaults', async ({ page }) => {
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/movements')
  await expect.poll(() => log.movementQueryBodies.length).toBe(1)

  await page.getByLabel('开始', { exact: true }).fill('2026-07-26')
  await page.getByLabel('结束', { exact: true }).fill('2026-07-20')
  await expect(page.getByRole('alert')).toContainText('开始日期不能晚于结束日期')
  await expect(page.getByRole('button', { name: '查询', exact: true })).toBeDisabled()
  expect(log.movementQueryBodies).toHaveLength(1)

  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect.poll(() => log.movementQueryBodies.length).toBe(2)
  await expect(page.getByRole('alert')).toHaveCount(0)
  const resetBody = log.movementQueryBodies[1]
  expect(resetBody.storeIds).toBeUndefined()
  expect(resetBody.itemIds).toBeUndefined()
  expect(resetBody.directions).toBeUndefined()
  expect(resetBody.startDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  expect(resetBody.endDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  expect(log.consoleErrors).toEqual([])
})

test('movement filters stay compact and usable at 390px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const log = await prepare(page, warehouseAdminSession)
  await page.goto('/warehouse/movements')
  await expect.poll(() => log.movementQueryBodies.length).toBe(1)

  const storeTrigger = page.getByRole('button', { name: /选择查询门店/ })
  const itemTrigger = page.getByRole('button', { name: /选择查询物料/ })
  await expect(storeTrigger).toBeInViewport()
  await expect(itemTrigger).toBeInViewport()
  await storeTrigger.click()
  await expect(page.getByRole('searchbox', { name: '选择查询门店' })).toBeInViewport()
  await expect(page.getByRole('button', { name: '完成选择' })).toBeInViewport()

  const viewport = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))
  expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.clientWidth + 1)
  expect(log.consoleErrors).toEqual([])
})
