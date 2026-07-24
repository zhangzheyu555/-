import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const businessError = (status: number, code: string, message: string) => ({
  status,
  contentType: 'application/json',
  body: JSON.stringify({ success: false, code, message, data: null }),
})

const boss = {
  id: 1,
  tenantId: 1,
  tenantName: 'H2 合成租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['store.read', 'store.manage'],
  dataScopes: { STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const brands = [
  { id: 1, code: 'RG', name: '茹菓', color: '#76bdb8', sortOrder: 1 },
  { id: 2, code: 'BW', name: '霸王茶姬', color: '#9c2f3e', sortOrder: 2 },
]

const options = {
  regions: [
    { code: 'JINGZHOU', name: '荆州总仓', supplyWarehouseId: 11 },
    { code: 'SHANDONG', name: '山东分仓', supplyWarehouseId: 12 },
  ],
  managers: [
    { employeeId: 'e1', name: '张三', phone: '13800138000', storeId: 'rg1', storeName: '营业门店' },
    { employeeId: 'e2', name: '王五', phone: '0716-1234567', storeId: 'rg1', storeName: '营业门店' },
  ],
  statuses: [
    { value: '营业中', label: '营业中', active: true },
    { value: '停用', label: '停用', active: false },
    { value: '停业', label: '停业', active: false },
  ],
  costAccounts: [
    { storeId: 'rg1', storeCode: 'RG001', storeName: '营业门店', status: '营业中' },
  ],
}

type StoreRow = {
  id: string
  code: string
  name: string
  brandId: number
  brandName: string
  area?: string
  manager?: string
  managerPhone?: string
  managerEmployeeId?: string
  openDate?: string
  status: string
  note?: string
  regionCode?: string
  supplyWarehouseId?: number
  supplyWarehouseName?: string
  costAccountStoreId?: string
  costAccountStoreName?: string
  version: number
}

interface State {
  stores: StoreRow[]
  createBodies: Array<Record<string, unknown>>
  updateBodies: Array<Record<string, unknown>>
  statusBodies: Array<Record<string, unknown>>
  consoleErrors: string[]
}

function initialStores(): StoreRow[] {
  return [
    {
      id: 'rg1',
      code: 'RG001',
      name: '营业门店',
      brandId: 1,
      brandName: '茹菓',
      area: '荆州总仓',
      manager: '张三',
      managerPhone: '13800138000',
      managerEmployeeId: 'e1',
      status: '营业中',
      regionCode: 'JINGZHOU',
      supplyWarehouseId: 11,
      supplyWarehouseName: '荆州总仓',
      costAccountStoreId: 'rg1',
      costAccountStoreName: '营业门店',
      version: 0,
    },
  ]
}

function newState(): State {
  return {
    stores: initialStores(),
    createBodies: [],
    updateBodies: [],
    statusBodies: [],
    consoleErrors: [],
  }
}

async function prepare(page: Page, state: State, session: Record<string, unknown> = boss) {
  page.on('console', (message) => {
    if (message.type() === 'error') state.consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => state.consoleErrors.push(error.message))
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'STORE-MANAGEMENT-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => fulfillApi(route, state, session))
}

async function fulfillApi(route: Route, state: State, session: Record<string, unknown> = boss) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname
  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/brands') return route.fulfill(ok(brands))
  if (path === '/api/stores/options') return route.fulfill(ok(options))
  if (path === '/api/stores' && request.method() === 'GET') return route.fulfill(ok(state.stores))
  if (path === '/api/stores' && request.method() === 'POST') {
    const body = request.postDataJSON() as Record<string, unknown>
    state.createBodies.push(body)
    if (body.name === '营业门店') {
      return route.fulfill(businessError(409, 'STORE_NAME_DUPLICATE', '门店名称已存在，请更换后再保存'))
    }
    const manager = options.managers.find((item) => item.employeeId === body.managerEmployeeId)!
    const region = options.regions.find((item) => item.code === body.regionCode)!
    const brand = brands.find((item) => item.id === Number(body.brandId))!
    const costAccountStoreId = body.costAccountStoreId === 'SELF'
      ? 'store-generated-3'
      : String(body.costAccountStoreId)
    const costAccountStoreName = costAccountStoreId === 'store-generated-3'
      ? String(body.name)
      : state.stores.find((item) => item.id === costAccountStoreId)?.name || ''
    const created: StoreRow = {
      id: 'store-generated-3',
      code: String(body.code),
      name: String(body.name),
      brandId: Number(body.brandId),
      brandName: brand.name,
      area: region.name,
      manager: manager.name,
      managerPhone: String(body.managerPhone),
      managerEmployeeId: manager.employeeId,
      openDate: String(body.openDate || ''),
      status: String(body.status),
      note: String(body.note || ''),
      regionCode: region.code,
      supplyWarehouseId: region.supplyWarehouseId,
      supplyWarehouseName: region.name,
      costAccountStoreId,
      costAccountStoreName,
      version: 0,
    }
    state.stores.push(created)
    return route.fulfill(ok(created))
  }
  if (path === '/api/stores' && request.method() === 'PUT') {
    const body = request.postDataJSON() as Record<string, unknown>
    state.updateBodies.push(body)
    const row = state.stores.find((store) => store.id === body.id)!
    const manager = options.managers.find((item) => item.employeeId === body.managerEmployeeId)!
    const region = options.regions.find((item) => item.code === body.regionCode)!
    Object.assign(row, body, {
      manager: manager.name,
      area: region.name,
      supplyWarehouseId: region.supplyWarehouseId,
      supplyWarehouseName: region.name,
      costAccountStoreId: body.costAccountStoreId === 'SELF' ? row.id : body.costAccountStoreId,
      costAccountStoreName: body.costAccountStoreId === 'SELF'
        ? body.name
        : state.stores.find((item) => item.id === body.costAccountStoreId)?.name,
      version: row.version + 1,
    })
    return route.fulfill(ok(row))
  }
  const statusMatch = path.match(/^\/api\/stores\/([^/]+)\/status$/)
  if (statusMatch && request.method() === 'PUT') {
    const body = request.postDataJSON() as Record<string, unknown>
    state.statusBodies.push(body)
    const row = state.stores.find((store) => store.id === decodeURIComponent(statusMatch[1]))!
    row.status = String(body.status)
    row.version += 1
    return route.fulfill(ok(row))
  }
  return route.fulfill(ok([]))
}

async function fillRequiredArchive(page: Page, name = '新增测试店', code = 'RG003') {
  const dialog = page.getByRole('dialog', { name: '新增门店档案' })
  await dialog.getByLabel('门店编号').fill(code)
  await dialog.getByLabel('门店名称').fill(name)
  await dialog.getByLabel('品牌').selectOption('1')
  await dialog.getByLabel('所属区域').selectOption('JINGZHOU')
  await dialog.getByLabel('负责人').selectOption('e1')
  await dialog.getByLabel('联系方式').fill('13800138000')
  await dialog.getByLabel('经营状态').selectOption('营业中')
  await dialog.getByLabel('成本账归属').selectOption('SELF')
  return dialog
}

test('dynamic dropdowns create a searchable store without exposing an internal id field', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/stores')

  await page.getByRole('button', { name: '新增门店', exact: true }).click()
  const dialog = await fillRequiredArchive(page)
  await expect(dialog.getByLabel('门店 ID')).toHaveCount(0)
  await expect(dialog.getByLabel('所属区域').locator('option')).toHaveText([
    '请选择所属区域',
    '荆州总仓',
    '山东分仓',
  ])
  await expect(dialog.getByLabel('负责人').locator('option')).toHaveText([
    '请选择负责人',
    '张三（营业门店）',
    '王五（营业门店）',
  ])
  await dialog.getByRole('button', { name: '新增门店', exact: true }).click()

  await expect.poll(() => state.createBodies.length).toBe(1)
  expect(state.createBodies[0]).toMatchObject({
    code: 'RG003',
    name: '新增测试店',
    regionCode: 'JINGZHOU',
    managerEmployeeId: 'e1',
    managerPhone: '13800138000',
    status: '营业中',
    costAccountStoreId: 'SELF',
  })
  expect(state.createBodies[0]).not.toHaveProperty('id')
  await expect(page.getByText('门店档案已新增。')).toBeVisible()

  await page.getByLabel('查询门店').fill('RG003')
  await expect(page.getByRole('cell', { name: '新增测试店', exact: true })).toBeVisible()
  await expect(page.getByText('营业门店', { exact: true })).toHaveCount(0)
  await page.getByLabel('查询门店').fill('张三')
  await expect(page.getByRole('cell', { name: '新增测试店', exact: true })).toBeVisible()
  await expectNoWholePageOverflow(page, '1280px 门店管理页')
  expect(state.consoleErrors).toEqual([])
})

test('required fields, invalid contact, and duplicate name stay in the editor with clear errors', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.goto('/stores')

  await page.getByRole('button', { name: '新增门店', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增门店档案' })
  await expect(dialog.getByRole('button', { name: '新增门店', exact: true })).toBeDisabled()
  await fillRequiredArchive(page, '联系方式错误门店', 'RG009')
  await dialog.getByLabel('联系方式').fill('abc-123')
  await expect(dialog.getByText('请输入手机号或合法联系电话')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '新增门店', exact: true })).toBeDisabled()
  expect(state.createBodies).toHaveLength(0)

  await dialog.getByLabel('联系方式').fill('0716-1234567')
  await dialog.getByLabel('门店名称').fill('营业门店')
  await dialog.getByRole('button', { name: '新增门店', exact: true }).click()
  await expect(page.getByText('门店名称已存在，请更换后再保存')).toBeVisible()
  await expect(dialog).toBeVisible()
  expect(state.stores).toHaveLength(1)
})

test('edit is immediate and stop or reenable uses a second confirmation without physical delete', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.goto('/stores')

  const row = page.getByRole('row', { name: /营业门店/ })
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /编辑门店档案/ })
  await dialog.getByLabel('门店名称').fill('营业门店（已编辑）')
  await dialog.getByLabel('负责人').selectOption('e2')
  await dialog.getByLabel('联系方式').fill('0716-1234567')
  await dialog.getByRole('button', { name: '保存门店档案' }).click()
  await expect.poll(() => state.updateBodies.length).toBe(1)
  await expect(page.getByRole('cell', { name: '营业门店（已编辑）', exact: true })).toBeVisible()
  await expect(page.getByText('王五', { exact: true })).toBeVisible()

  const editedRow = page.getByRole('row', { name: /营业门店（已编辑）/ })
  await expect(editedRow.getByRole('button', { name: '删除' })).toHaveCount(0)
  await editedRow.getByRole('button', { name: '停用', exact: true }).click()
  await expect(page.getByText('停用后仍保留历史经营、财务、库存和业务单据')).toBeVisible()
  await page.getByRole('button', { name: '确认停用', exact: true }).click()
  await expect.poll(() => state.statusBodies).toEqual([{ status: '停用', version: 1 }])
  await expect(page.getByText('门店“营业门店（已编辑）”已停用。')).toBeVisible()

  await page.getByRole('row', { name: /营业门店（已编辑）/ })
    .getByRole('button', { name: '启用', exact: true }).click()
  await page.getByRole('button', { name: '确认启用', exact: true }).click()
  await expect.poll(() => state.statusBodies).toEqual([
    { status: '停用', version: 1 },
    { status: '营业中', version: 2 },
  ])
  await expect(page.getByText('门店“营业门店（已编辑）”已启用。')).toBeVisible()
  expect(state.stores).toHaveLength(1)
  expect(state.consoleErrors).toEqual([])
})

test('non-BOSS users cannot enter store management even when a stale session claims store.manage', async ({ browser }) => {
  for (const role of ['STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'EMPLOYEE', 'FINANCE']) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const page = await context.newPage()
    const session = role === 'STORE_MANAGER'
      ? {
          ...boss,
          id: 21,
          role,
          storeScope: ['rg1'],
          dataScopes: { STORE: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] } },
          dataScope: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] },
          boundStoreId: 'rg1',
          boundStoreName: '营业门店',
          defaultWorkspace: '/store',
        }
      : { ...boss, id: role.length + 10, role, defaultWorkspace: '/no-permission' }
    await prepare(page, newState(), session)
    await page.goto('/stores')
    if (role === 'STORE_MANAGER') {
      await expect(page).toHaveURL(/\/store\?notice=STORE_MANAGEMENT_FORBIDDEN/)
    } else {
      await expect(page).toHaveURL(/\/no-permission\?from=\/stores/)
    }
    await expect(page.getByRole('button', { name: '新增门店', exact: true })).toHaveCount(0)
    await context.close()
  }
})
