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

test('每日报损在 390px 下完整展示并可按分类和单位搜索、提交', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
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
  await expect(page.getByText(/厂商赔付/)).toHaveCount(0)
  await expect(page.getByText('总计损耗金额', { exact: true })).toHaveCount(0)
  await expect(page.getByText(/店铺承担/)).toHaveCount(0)
  await expect(page.getByLabel('报损结算')).toHaveCount(0)
  await expect(page.getByText('茹菓测试店 · 茹菓', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '增加品类' })).toBeVisible()
  await expect(page.getByText('选择照片', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => Math.max(
    document.documentElement.scrollWidth,
    document.body.scrollWidth,
  ) - window.innerWidth)).toBeLessThanOrEqual(1)

  await page.locator('.record-row').first().click()
  const detailDialog = page.getByRole('dialog', { name: '报损详情' })
  await expect(detailDialog).toBeVisible()
  await expect(detailDialog.locator('.detail-settlement')).toHaveCount(0)
  await expect(detailDialog.getByText('总计损耗金额', { exact: true })).toHaveCount(0)
  await expect(detailDialog.getByText(/店铺承担/)).toHaveCount(0)
  await expect(detailDialog.getByText(/厂商赔付/)).toHaveCount(0)
  await expect(detailDialog.getByRole('button', { name: '关闭报损详情' })).toBeInViewport()
  const detailBox = await detailDialog.boundingBox()
  expect(detailBox).not.toBeNull()
  expect(detailBox!.x).toBeGreaterThanOrEqual(0)
  expect(detailBox!.x + detailBox!.width).toBeLessThanOrEqual(390)
  await detailDialog.getByRole('button', { name: '关闭报损详情' }).click()
  await page.locator('.item-picker-trigger').first().click()

  const pickerDialog = page.getByRole('dialog', { name: '选择报损品类' })
  await expect(pickerDialog.getByRole('button', { name: '关闭品类选择' })).toBeInViewport()
  const pickerBox = await pickerDialog.boundingBox()
  expect(pickerBox).not.toBeNull()
  expect(pickerBox!.x).toBeGreaterThanOrEqual(0)
  expect(pickerBox!.x + pickerBox!.width).toBeLessThanOrEqual(390)

  const material = page.getByRole('combobox', { name: '搜索报损品类', exact: true })
  await material.fill('奶制品 瓶')
  await expect(page.getByRole('option', { name: /鲜牛奶.*MILK-202.*奶制品.*瓶/ })).toBeVisible()
  await expect(page.getByRole('option', { name: /黄桃/ })).toHaveCount(0)
  await page.getByRole('option', { name: /鲜牛奶.*MILK-202.*奶制品.*瓶/ }).click()

  await page.locator('.quantity-control input').first().fill('2')
  await expect(page.getByLabel('报损结算')).toHaveCount(0)
  await page.getByRole('button', { name: '变质', exact: true }).click()
  const submit = page.getByRole('button', { name: '提交今日报损', exact: true })
  await submit.scrollIntoViewIfNeeded()
  await expect(submit).toBeInViewport()
  await submit.click()

  await expect.poll(() => saveBodies.length).toBe(1)
  expect(saveBodies[0]).toMatchObject({
    storeId: 'rg1',
    details: [{
      itemConfigId: 202,
      lossQuantity: 2,
      lossReason: '变质',
    }],
  })
  expect(saveBodies[0]).not.toHaveProperty('supplierCompensationAmount')
  expect(saveBodies[0]).not.toHaveProperty('storeBorneAmount')
  expect(typeof (saveBodies[0].details as Array<{ itemConfigId: unknown }>)[0].itemConfigId).toBe('number')
  expect(await page.evaluate(() => Math.max(
    document.documentElement.scrollWidth,
    document.body.scrollWidth,
  ) - window.innerWidth)).toBeLessThanOrEqual(1)
})
