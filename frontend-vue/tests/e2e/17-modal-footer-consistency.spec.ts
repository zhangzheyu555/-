import { expect, test, type Locator, type Page, type Route } from '@playwright/test'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: [
    'expense.read',
    'expense.create',
    'expense.review',
    'salary.read',
    'salary.edit',
    'warehouse.central.read',
    'warehouse.central.manage',
  ],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
    SALARY: { mode: 'ALL', storeIds: [] },
    WAREHOUSE: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 3,
}

const stores = [
  { id: 'rg1', code: 'rg1', name: '荆州之星店', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
]

const expense = {
  id: 'expense-1',
  storeId: 'rg1',
  storeCode: 'rg1',
  storeName: '荆州之星店',
  brandId: 1,
  brandName: '茹菓',
  month: '2026-07',
  amount: 320,
  category: '设备维护',
  reason: '门店设备检修',
  status: 'PENDING',
  supplements: [],
  supplementAttachmentCount: 0,
}

const salaryRecord = {
  id: 'salary-1',
  storeId: 'rg1',
  storeName: '荆州之星店',
  month: '2026-07',
  employeeId: 'EMP-001',
  employeeName: '测试员工',
  position: '店员',
  attendance: '26天',
  base: 3000,
  commission: 300,
  gross: 3300,
  normalHours: 160,
  otHours: 0,
  workHours: 160,
  vacationLeft: 2,
  status: 'SUBMITTED',
}

const warehouseCategories = [
  { id: 10, name: '包材', parentId: null, sortOrder: 1, enabled: true, children: [] },
]

const warehouseItem = {
  id: 1,
  code: 'CUP-700',
  name: '700ml纸杯',
  categoryId: 10,
  categoryName: '包材',
  unit: '个',
  purchaseUnit: '箱',
  stockUnit: '个',
  ingredientUnit: '个',
  spec: '500个/箱',
  warehouseLocation: 'A-01',
  unitPrice: 0.35,
  shelfLifeDays: 365,
  minStockQuantity: 100,
  expiryAlertDays: 30,
  sortOrder: 1,
  active: true,
  departments: [],
}

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

async function prepareReadonlyPage(page: Page) {
  const blockedWrites: string[] = []
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'MODAL-READONLY-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossSession)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      blockedWrites.push(`${request.method()} ${url.pathname}`)
      await route.abort('blockedbyclient')
      return
    }
    await fulfillReadonlyApi(route, url.pathname)
  })

  return blockedWrites
}

async function fulfillReadonlyApi(route: Route, path: string) {
  if (path === '/api/auth/me') return route.fulfill(ok(bossSession))
  if (path === '/api/stores') return route.fulfill(ok(stores))
  if (path === '/api/brands') return route.fulfill(ok([{ id: 1, code: 'RG', name: '茹菓' }]))
  if (path === '/api/expenses') return route.fulfill(ok([expense]))
  if (path === '/api/salaries/employee-page') {
    return route.fulfill(ok({
      content: [salaryRecord],
      totalElements: 1,
      totalPages: 1,
      page: 1,
      size: 20,
      statusCounts: { SUBMITTED: 1 },
      summary: { grossTotal: 3300, commissionTotal: 300 },
      workHoursTotal: 160,
      vacationBalanceTotal: 2,
    }))
  }
  if (path === '/api/salaries') return route.fulfill(ok([salaryRecord]))
  if (path === '/api/salaries/preview') {
    return route.fulfill(ok({ generated: 1, skipped: 0, errors: 0, skipDetails: [] }))
  }
  if (path === '/api/warehouse/overview') {
    return route.fulfill(ok({
      summary: {
        itemCount: 1,
        lowStockCount: 0,
        expiringCount: 0,
        pendingRequisitionCount: 0,
        pendingReceiptCount: 0,
        pendingPurchaseCount: 0,
        stockValue: 175,
      },
      alerts: [],
      items: [warehouseItem],
      requisitions: [],
      stockBatches: [],
      movements: [],
    }))
  }
  if (path === '/api/warehouse/item-categories') return route.fulfill(ok(warehouseCategories))
  if (path === '/api/warehouse/returns') return route.fulfill(ok([]))
  if (path === '/api/finance/months') return route.fulfill(ok(['2026-07']))
  if (path === '/api/finance/entries') return route.fulfill(ok([]))
  if (path === '/api/finance/dashboard') {
    return route.fulfill(ok({
      months: ['2026-07'],
      brands: [{ id: 1, name: '茹菓', color: '#76bdb8' }],
      summary: {
        month: '2026-07',
        storeCount: 0,
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

async function expectSingleLineCancel(button: Locator) {
  await expect(button).toBeVisible()
  await expect(button).toHaveAttribute('type', 'button')
  await expect(button).toHaveCSS('height', '44px')
  await expect(button).toHaveCSS('white-space', 'nowrap')
  const box = await button.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.height).toBe(44)
  expect(box!.width).toBeGreaterThanOrEqual(88)
}

test('新增报销取消按钮保持单行，填写后关闭进入统一未保存确认', async ({ page }) => {
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/expenses')

  await page.getByRole('button', { name: '新增报销' }).click()
  const drawer = page.getByRole('dialog', { name: '新增报销' })
  await expect(drawer).toBeVisible()

  const cancel = drawer.getByRole('button', { name: '取消', exact: true })
  await expectSingleLineCancel(cancel)
  await drawer.getByLabel('报销说明').fill('只读界面回归测试，不会提交')
  await cancel.click()

  const unsaved = page.getByRole('alertdialog', { name: '放弃未保存内容？' })
  await expect(unsaved).toBeVisible()
  await expect(unsaved).toContainText('当前填写的报销信息和已选择附件将不会保存。')
  await unsaved.getByRole('button', { name: '继续填写', exact: true }).click()
  await expect(drawer).toBeVisible()

  await cancel.click()
  await page.getByRole('alertdialog', { name: '放弃未保存内容？' })
    .getByRole('button', { name: '放弃修改' })
    .click()
  await expect(drawer).toHaveCount(0)
  expect(blockedWrites).toEqual([])
})

test('补充资料取消按钮保持单行，未提交说明受到统一放弃确认保护', async ({ page }) => {
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/expenses')

  await page.getByRole('button', { name: '补充资料', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '补充报销资料' })
  await expect(dialog).toBeVisible()

  const cancel = dialog.getByRole('button', { name: '取消', exact: true })
  await expectSingleLineCancel(cancel)
  await dialog.getByLabel('补充说明').fill('只读界面回归测试，不会上传')
  await cancel.click()

  const unsaved = page.getByRole('alertdialog', { name: '放弃未提交的补充资料？' })
  await expect(unsaved).toBeVisible()
  await expect(unsaved).toContainText('当前填写的说明和已选择文件将不会保留。')
  await unsaved.getByRole('button', { name: '继续填写', exact: true }).click()
  await expect(dialog).toBeVisible()

  await dialog.getByRole('button', { name: '关闭补资料窗口' }).click()
  await page.getByRole('alertdialog', { name: '放弃未提交的补充资料？' })
    .getByRole('button', { name: '放弃修改' })
    .click()
  await expect(dialog).toHaveCount(0)
  expect(blockedWrites).toEqual([])
})

test('数据录入导入抽屉沿用统一底栏且关闭时不触发写请求', async ({ page }) => {
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/data-entry')

  await page.getByRole('button', { name: '导入数据' }).click()
  const drawer = page.getByRole('dialog', { name: '经营数据导入' })
  await expect(drawer).toBeVisible()

  const cancel = drawer.getByRole('button', { name: '取消', exact: true })
  await expectSingleLineCancel(cancel)
  await expect(drawer.getByRole('button', { name: '关闭导入' })).toHaveAttribute('type', 'button')
  await expect(drawer.getByRole('button', { name: '确认导入' })).toBeDisabled()

  await cancel.click()
  await expect(drawer).toHaveCount(0)
  expect(blockedWrites).toEqual([])
})

test('工资生成预览使用统一关闭按钮且不会执行工资生成', async ({ page }) => {
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/finance/salary?storeId=rg1&month=2026-07')

  await page.getByRole('button', { name: '生成本月工资' }).click()
  const dialog = page.getByRole('dialog', { name: '工资生成预览' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('可生成 1 人')

  const close = dialog.getByRole('button', { name: '关闭', exact: true })
  await expectSingleLineCancel(close)
  await expect(dialog.getByRole('button', { name: '关闭工资生成预览' })).toHaveAttribute('type', 'button')

  await close.click()
  await expect(dialog).toHaveCount(0)
  expect(blockedWrites).toEqual([])
})

test('工资批量审核使用正式确认框，取消按钮保持单行且不写数据', async ({ page }) => {
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/finance/salary?storeId=rg1&month=2026-07')

  await page.getByRole('checkbox', { name: '选择测试员工' }).check()
  await page.getByRole('button', { name: '批量审核' }).click()

  const confirmation = page.getByRole('alertdialog', { name: '批量审核工资' })
  await expect(confirmation).toBeVisible()
  await expect(confirmation).toContainText('确认审核通过所选 1 名员工的工资？')
  const cancel = confirmation.getByRole('button', { name: '取消', exact: true })
  await expectSingleLineCancel(cancel)
  await expect(confirmation.getByRole('button', { name: '关闭确认窗口' })).toHaveAttribute('type', 'button')

  await cancel.click()
  await expect(confirmation).toHaveCount(0)
  expect(blockedWrites).toEqual([])
})

test('仓库新增物料取消按钮统一，填写后关闭进入未保存确认', async ({ page }) => {
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/warehouse/items')

  await page.getByRole('button', { name: '新增物料', exact: true }).click()
  const editor = page.getByRole('dialog', { name: '新增物料档案' })
  await expect(editor).toBeVisible()

  const cancel = editor.getByRole('button', { name: '取消', exact: true })
  await expectSingleLineCancel(cancel)
  await editor.getByLabel('物品名称').fill('只读回归测试物料')
  await cancel.click()

  const unsaved = page.getByRole('alertdialog', { name: '物料修改尚未保存' })
  await expect(unsaved).toBeVisible()
  await expect(unsaved).toContainText('本次物料资料、图片和适用部门调整将不会保留。')
  await unsaved.getByRole('button', { name: '继续填写', exact: true }).click()
  await expect(editor).toBeVisible()

  await editor.getByRole('button', { name: '关闭物料编辑' }).click()
  await page.getByRole('alertdialog', { name: '物料修改尚未保存' })
    .getByRole('button', { name: '放弃修改' })
    .click()
  await expect(editor).toHaveCount(0)
  expect(blockedWrites).toEqual([])
})

test('窄屏弹窗底栏可重新排布且取消按钮仍保持单行', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const blockedWrites = await prepareReadonlyPage(page)
  await page.goto('/expenses')

  await page.getByRole('button', { name: '补充资料', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '补充报销资料' })
  const cancel = dialog.getByRole('button', { name: '取消', exact: true })
  await expectSingleLineCancel(cancel)
  await expect(dialog.getByRole('button', { name: '提交补充资料' })).toBeVisible()

  const hasHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(hasHorizontalOverflow).toBe(false)
  expect(blockedWrites).toEqual([])
})
