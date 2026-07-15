import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

const managerSession = {
  id: 7,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '荆州之星店店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  storeScope: ['rg1'],
  // 模拟遗留会话误带导入权限：前端仍必须以角色边界拒绝入口。
  permissions: ['store.read', 'finance.profit.read', 'finance.profit.write', 'finance.profit.import'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
    FINANCE: { mode: 'OWN_STORE', storeIds: ['rg1'] },
  },
  dataScope: { mode: 'OWN_STORE', storeIds: ['rg1'] },
  boundStoreId: 'rg1',
  boundStoreName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  defaultWorkspace: '/store',
  permissionVersion: 4,
}

const financeSession = {
  id: 2,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试财务',
  role: 'FINANCE',
  roleLabel: '财务',
  storeScope: ['all'],
  permissions: ['finance.profit.read', 'finance.profit.write', 'finance.profit.import'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/finance',
  permissionVersion: 4,
}

const financeImportWriteDeniedSession = {
  ...financeSession,
  id: 21,
  displayName: '写入受限财务',
  // 后端处理显式 DENY 后，/api/auth/me 不会返回 finance.profit.write。
  permissions: ['finance.profit.read', 'finance.profit.import'],
  permissionVersion: 5,
}

const bossSession = {
  ...financeSession,
  id: 1,
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  permissions: [],
  defaultWorkspace: '/boss',
}

const stores = [
  { id: 'rg1', code: 'rg1', name: '荆州之星店', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
  { id: 'rg2', code: 'rg2', name: '万达二店', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
  { id: 'bw1', code: 'bw1', name: '霸王中心店', brandId: 2, brandName: '霸王茶姬', status: 'ACTIVE' },
]

interface RequestLog {
  urls: string[]
}

async function prepare(page: Page, session: typeof managerSession | typeof financeSession | typeof bossSession) {
  const requests: RequestLog = { urls: [] }
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'FINANCE-IMPORT-ACCESS-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => fulfillApi(route, session, requests))
  return requests
}

async function fulfillApi(
  route: Route,
  session: typeof managerSession | typeof financeSession | typeof bossSession,
  requests: RequestLog,
) {
  const url = new URL(route.request().url())
  const path = url.pathname
  requests.urls.push(url.toString())

  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/stores') return route.fulfill(ok(stores))
  if (path === '/api/brands') return route.fulfill(ok([
    { id: 1, code: 'RG', name: '茹菓' },
    { id: 2, code: 'BW', name: '霸王茶姬' },
  ]))
  if (path === '/api/finance/months') return route.fulfill(ok(['2026-07']))
  if (path === '/api/finance/entries') return route.fulfill(ok([]))
  if (path === '/api/finance/dashboard') {
    return route.fulfill(ok({
      months: ['2026-07'],
      brands: [{ id: 1, name: '茹菓', color: '#76bdb8' }],
      summary: {
        month: '2026-07',
        storeCount: session.role === 'STORE_MANAGER' ? 1 : 2,
        entryCount: 0,
        sales: 0,
        income: 0,
        costSum: 0,
        expenseSum: 0,
        net: 0,
        margin: 0,
        riskStoreCount: 0,
      },
      entries: [],
      trend: [],
    }))
  }
  return route.fulfill(ok([]))
}

test('店长即使遗留写入权限也不能进入数据录入或导入直链', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const requests = await prepare(page, managerSession)

  await page.goto('/data-entry')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/profit-table')
  await expect(page.getByRole('button', { name: '导入月度汇总' })).toHaveCount(0)

  await page.getByRole('button', { name: '打开菜单' }).click()
  await expect(page.locator('.app-sidebar--mobile')).not.toContainText('导入月度汇总')

  await page.goto('/finance/import')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/profit-table')
  await expect(page.getByRole('dialog', { name: '经营数据导入' })).toHaveCount(0)
  expect(requests.urls.every((url) => !new URL(url).pathname.startsWith('/api/profit-imports'))).toBe(true)
})

test('财务从工作台旧入口进入数据录入页并自动打开同一校验抽屉', async ({ page }) => {
  await prepare(page, financeSession)

  await page.goto('/finance')
  const financeWorkspace = page.locator('.finance-workspace')
  const importWorkspaceLink = financeWorkspace.getByRole('link', { name: /导入月度汇总/ })
  await expect(importWorkspaceLink).toBeVisible()
  await expect(page.locator('.app-sidebar--desktop')).not.toContainText('导入月度汇总')

  await importWorkspaceLink.click()
  await expect.poll(() => new URL(page.url()).pathname).toBe('/data-entry')
  await expect(page.getByRole('heading', { name: '数据录入', level: 1 })).toBeVisible()
  await expect(page.locator('.entry-sheet')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存' })).toBeVisible()
  await expect(page.getByLabel('品牌', { exact: true }).locator('option', { hasText: '全部品牌' })).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: '经营数据导入' })).toBeVisible()
  await expect(page.getByRole('button', { name: '选择文件' })).toBeVisible()

  await page.setViewportSize({ width: 390, height: 844 })
  await expectNoWholePageOverflow(page, '财务导入窄屏')
  const drawerBox = await page.locator('.import-drawer').boundingBox()
  expect(drawerBox?.width).toBeLessThanOrEqual(390)
})

test('财务被显式拒绝写入后，即使保留导入权限也不能看到或直达导入入口', async ({ page }) => {
  const requests = await prepare(page, financeImportWriteDeniedSession)

  await page.goto('/finance')
  const financeWorkspace = page.locator('.finance-workspace')
  await expect(financeWorkspace.getByRole('link', { name: /导入月度汇总/ })).toHaveCount(0)
  await expect(page.locator('.app-sidebar--desktop')).not.toContainText('导入月度汇总')

  await page.goto('/finance/import')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/no-permission')
  await expect(page.getByText('当前账号没有访问该页面的权限')).toBeVisible()
  await expect(page.getByRole('dialog', { name: '经营数据导入' })).toHaveCount(0)
  expect(requests.urls.every((url) => !new URL(url).pathname.startsWith('/api/profit-imports'))).toBe(true)
})

test('数据录入品牌门店保持一致，刷新会重载四类数据并提示结果', async ({ page }) => {
  const requests = await prepare(page, financeSession)
  await page.goto('/data-entry')

  const brand = page.getByLabel('品牌', { exact: true })
  const store = page.getByLabel('门店', { exact: true })
  await expect(brand.locator('option', { hasText: '全部品牌' })).toHaveCount(0)
  await expect(brand).toHaveValue('1')
  await expect(store).toHaveValue('rg1')

  await brand.selectOption('2')
  await expect(store).toHaveValue('bw1')
  await expect(store.locator('option')).toHaveText(['请选择门店', '霸王茶姬 · 霸王中心店'])

  const paths = ['/api/brands', '/api/stores', '/api/finance/dashboard', '/api/finance/entries']
  const before = Object.fromEntries(paths.map((path) => [path, requests.urls.filter((url) => new URL(url).pathname === path).length]))
  await page.locator('#profit-sales').fill('999')
  await page.getByRole('button', { name: '刷新数据' }).click()
  const confirm = page.getByRole('alertdialog', { name: '当前修改尚未保存' })
  await expect(confirm).toContainText('刷新将放弃未保存修改，是否继续？')
  await confirm.getByRole('button', { name: '放弃修改并继续' }).click()

  await expect(page.getByRole('status')).toContainText('数据已刷新')
  for (const path of paths) {
    await expect.poll(() => requests.urls.filter((url) => new URL(url).pathname === path).length)
      .toBeGreaterThan(before[path])
  }
})

test('老板从旧直链进入数据录入页悬浮导入弹窗', async ({ page }) => {
  await prepare(page, bossSession)

  await page.goto('/finance/import')
  await expect.poll(() => new URL(page.url()).pathname).toBe('/data-entry')
  await expect(page.getByRole('heading', { name: '数据录入', level: 1 })).toBeVisible()
  await expect(page.locator('.entry-sheet')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存' })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '经营数据导入' })).toBeVisible()
  await expect(page.locator('.app-sidebar--desktop')).not.toContainText('导入月度汇总')
})
