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
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const brands = [
  { id: 1, code: 'RG', name: '茹菓', color: '#76bdb8', sortOrder: 1 },
  { id: 2, code: 'BW', name: '霸王茶姬', color: '#9c2f3e', sortOrder: 2 },
]

type StoreRow = {
  id: string
  code: string
  name: string
  brandId: number
  brandName: string
  area?: string
  manager?: string
  openDate?: string
  status?: string
  note?: string
  regionCode?: string
}

interface State {
  stores: StoreRow[]
  createBodies: Array<Record<string, unknown>>
  updateBodies: Array<Record<string, unknown>>
  consoleErrors: string[]
}

function initialStores(): StoreRow[] {
  return [
    { id: 'rg1', code: 'RG001', name: '营业门店', brandId: 1, brandName: '茹菓', area: '荆州', manager: '张三', status: '营业中', regionCode: 'JINGZHOU' },
    { id: 'rg2', code: 'RG002', name: '停用门店', brandId: 1, brandName: '茹菓', area: '荆州', manager: '李四', status: '停用', regionCode: 'JINGZHOU' },
    { id: 'bw1', code: 'BW001', name: '停业门店', brandId: 2, brandName: '霸王茶姬', area: '山东', manager: '王五', status: '停业', regionCode: 'SHANDONG' },
  ]
}

function newState(): State {
  return { stores: initialStores(), createBodies: [], updateBodies: [], consoleErrors: [] }
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
  const path = new URL(request.url()).pathname
  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/brands') return route.fulfill(ok(brands))
  if (path === '/api/stores' && request.method() === 'GET') return route.fulfill(ok(state.stores))
  if (path === '/api/stores' && request.method() === 'POST') {
    const body = request.postDataJSON() as Record<string, unknown>
    state.createBodies.push(body)
    if (body.code === 'RG001') {
      return route.fulfill(businessError(409, 'STORE_CODE_DUPLICATE', '门店编号已存在，请更换后再保存'))
    }
    if (body.regionCode === 'INVALID') {
      return route.fulfill(businessError(400, 'STORE_REGION_INVALID', '区域编码仅支持荆州或山东'))
    }
    const brand = brands.find((item) => item.id === Number(body.brandId))!
    state.stores.push({
      id: String(body.id),
      code: String(body.code || ''),
      name: String(body.name),
      brandId: Number(body.brandId),
      brandName: brand.name,
      area: String(body.area || ''),
      manager: String(body.manager || ''),
      openDate: String(body.openDate || ''),
      status: String(body.status || '营业中'),
      note: String(body.note || ''),
      regionCode: String(body.regionCode || ''),
    })
    return route.fulfill(ok(null))
  }
  if (path === '/api/stores' && request.method() === 'PUT') {
    const body = request.postDataJSON() as Record<string, unknown>
    state.updateBodies.push(body)
    const row = state.stores.find((store) => store.id === body.id)
    if (row) Object.assign(row, body)
    return route.fulfill(ok(null))
  }
  return route.fulfill(ok([]))
}

async function createStore(page: Page, values: Partial<{ id: string; code: string; name: string; regionCode: string }> = {}) {
  await page.getByRole('button', { name: '新增门店', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '新增门店档案' })
  await dialog.getByLabel('门店 ID').fill(values.id || 'rg3')
  await dialog.getByLabel('门店编号').fill(values.code || 'RG003')
  await dialog.getByLabel('门店名称').fill(values.name || '新增测试店')
  await dialog.getByLabel('品牌').selectOption('1')
  await dialog.getByLabel('区域', { exact: true }).fill('荆州')
  await dialog.getByLabel('负责人').fill('赵六')
  await dialog.getByLabel('区域编码').fill(values.regionCode || 'JINGZHOU')
  await dialog.getByRole('button', { name: '新增门店', exact: true }).click()
}

test('boss creates a store, filters state, and synchronizes the list after enablement without desktop overflow', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/stores')

  await expect(page.getByRole('button', { name: '新增门店', exact: true }).first()).toBeVisible()
  await createStore(page)
  await expect.poll(() => state.createBodies.length).toBe(1)
  await expect(page.getByText('门店档案已新增。')).toBeVisible()
  await expect(page.getByRole('cell', { name: '新增测试店' })).toBeVisible()

  const filter = page.getByLabel('门店状态筛选')
  await filter.selectOption('ACTIVE')
  await expect(page.getByText('营业门店', { exact: true })).toBeVisible()
  await expect(page.getByText('新增测试店', { exact: true })).toBeVisible()
  await expect(page.getByText('停用门店', { exact: true })).toHaveCount(0)

  await filter.selectOption('INACTIVE')
  await expect(page.getByText('停用门店', { exact: true })).toBeVisible()
  await expect(page.getByText('停业门店', { exact: true })).toBeVisible()
  await page.getByRole('row', { name: /停用门店/ }).getByRole('button', { name: '启用', exact: true }).click()
  await page.getByRole('button', { name: '确认启用', exact: true }).click()
  await expect.poll(() => state.updateBodies.length).toBe(1)
  await expect(page.getByText('停用门店', { exact: true })).toHaveCount(0)

  await filter.selectOption('ACTIVE')
  await expect(page.getByText('停用门店', { exact: true })).toBeVisible()
  await filter.selectOption('ALL')
  await expect(page.getByText('营业门店', { exact: true })).toBeVisible()
  await expect(page.getByText('停用门店', { exact: true })).toBeVisible()
  await expect(page.getByText('停业门店', { exact: true })).toBeVisible()
  await expectNoWholePageOverflow(page, '1280px 门店管理页')
  expect(state.consoleErrors).toEqual([])
})

test('duplicate code and invalid region show the server business error without mutating the browser mock data', async ({ page }) => {
  const state = newState()
  await prepare(page, state)
  await page.goto('/stores')

  await createStore(page, { id: 'duplicate-code', code: 'RG001', name: '重复编号门店' })
  await expect(page.getByText('门店编号已存在，请更换后再保存')).toBeVisible()
  expect(state.stores).toHaveLength(3)

  await page.getByRole('button', { name: '取消', exact: true }).click()
  await createStore(page, { id: 'invalid-region', code: 'RG009', name: '非法区域门店', regionCode: 'INVALID' })
  await expect(page.getByText('区域编码仅支持荆州或山东')).toBeVisible()
  expect(state.stores).toHaveLength(3)
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
