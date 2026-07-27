import { expect, test, type Route } from '@playwright/test'

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, code: 'OK', message: 'OK', data }),
})

const storeManager = {
  id: 48,
  tenantId: 1,
  tenantName: '报损品类搜索租户',
  displayName: '茹菓测试店长',
  role: 'STORE_MANAGER',
  roleLabel: '店长',
  boundStoreId: 'rg1',
  boundStoreName: '茹菓测试店',
  brandId: 1,
  brandName: '茹菓',
  storeScope: ['rg1'],
  permissions: ['daily_loss.read', 'daily_loss.create'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] },
  },
  dataScope: { mode: 'OWN_STORE', storeIds: ['rg1'], warehouseIds: [] },
  defaultWorkspace: '/store',
  permissionVersion: 1,
}

const items = [
  {
    id: 101,
    itemCode: 'FRUIT-101',
    itemName: '黄桃',
    categoryCode: 'FRUIT',
    categoryName: '水果',
    unit: '盒',
    pricingUnit: '盒',
    quantityPerPricingUnit: 1,
    unitPrice: 6,
    active: true,
  },
  {
    id: 202,
    itemCode: 'MILK-202',
    itemName: '鲜牛奶',
    categoryCode: 'DAIRY',
    categoryName: '奶制品',
    unit: '瓶',
    pricingUnit: '瓶',
    quantityPerPricingUnit: 1,
    unitPrice: 8,
    active: true,
  },
]

function localDate() {
  const date = new Date()
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

test('每日报损可按分类和单位搜索品类，提交仍使用数值型 itemConfigId', async ({ page }) => {
  const saveBodies: Array<Record<string, unknown>> = []
  const today = localDate()
  const existingReport = {
    id: 'report-today',
    storeId: 'rg1',
    storeCode: 'RG1',
    storeName: '茹菓测试店',
    lossDate: today,
    month: today.slice(0, 7),
    status: 'DRAFT',
    statusLabel: '已保存',
    reported: true,
    totalAmount: 0,
    supplierCompensationAmount: 0,
    storeBorneAmount: 0,
    detailCount: 0,
    attachmentCount: 1,
    details: [],
    attachments: [{
      id: 'existing-photo',
      fileName: 'existing.png',
      contentType: 'image/png',
    }],
  }

  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'DAILY-LOSS-MATERIAL-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, storeManager)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(storeManager))
    if (url.pathname === '/api/stores') {
      return route.fulfill(ok([{
        id: 'rg1',
        code: 'RG1',
        name: '茹菓测试店',
        brandId: 1,
        brandName: '茹菓',
        status: 'ACTIVE',
      }]))
    }
    if (url.pathname === '/api/daily-loss/items') return route.fulfill(ok(items))
    if (url.pathname === '/api/daily-loss/reports' && request.method() === 'GET') {
      return route.fulfill(ok([existingReport]))
    }
    if (url.pathname === '/api/daily-loss/reports' && request.method() === 'POST') {
      saveBodies.push(request.postDataJSON())
      return route.fulfill(ok({ ...existingReport, id: 'report-saved' }))
    }
    if (url.pathname === '/api/daily-loss/reports/report-saved/submit') {
      return route.fulfill(ok({ ...existingReport, id: 'report-saved', status: 'SUBMITTED' }))
    }
    if (url.pathname === '/api/storage/attachments/existing-photo') {
      return route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=', 'base64'),
      })
    }
    return route.fulfill(ok([]))
  })

  await page.goto('/daily-loss')
  await expect(page.getByRole('heading', { name: '今日报损' })).toBeVisible()
  await page.locator('.item-picker-trigger').first().click()

  const material = page.getByRole('combobox', { name: '搜索报损品类', exact: true })
  await material.fill('奶制品 瓶')
  await expect(page.getByRole('option', { name: /鲜牛奶.*MILK-202.*奶制品.*瓶/ })).toBeVisible()
  await expect(page.getByRole('option', { name: /黄桃/ })).toHaveCount(0)
  await page.getByRole('option', { name: /鲜牛奶.*MILK-202.*奶制品.*瓶/ }).click()

  await page.locator('.quantity-control input').first().fill('2')
  await page.getByRole('button', { name: '变质', exact: true }).click()
  await page.getByRole('button', { name: '提交今日报损', exact: true }).click()

  await expect.poll(() => saveBodies.length).toBe(1)
  expect(saveBodies[0]).toMatchObject({
    storeId: 'rg1',
    details: [{
      itemConfigId: 202,
      lossQuantity: 2,
      lossReason: '变质',
    }],
  })
  expect(typeof (saveBodies[0].details as Array<{ itemConfigId: unknown }>)[0].itemConfigId).toBe('number')
})
