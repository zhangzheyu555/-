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
  {
    id: 303,
    itemCode: 'FRUIT_CHECK_003',
    itemName: '芒果',
    categoryCode: 'FRUIT',
    categoryName: '水果',
    unit: '克',
    pricingUnit: '斤',
    quantityPerPricingUnit: 500,
    unitPrice: 4.5,
    active: true,
    peelSelectionEnabled: true,
    defaultPeelState: 'UNPEELED',
    peeledUnit: '克',
    unpeeledUnit: '克',
    unpeeledPricingUnit: '斤',
    unpeeledQuantityPerPricingUnit: 500,
    unpeeledUnitPrice: 4.5,
    yieldRate: 0.53030303,
    inventoryUnit: '斤',
  },
]

function localDate() {
  const date = new Date()
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

test('每日报损支持品类搜索和去皮换算，提交数值型 itemConfigId 与形态', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const saveBodies: Array<Record<string, unknown>> = []
  let uploadCount = 0
  const today = localDate()
  const existingReport = {
    id: 'report-existing-1',
    storeId: 'rg1',
    storeCode: 'RG1',
    storeName: '茹菓测试店',
    lossDate: today,
    month: today.slice(0, 7),
    status: 'SUBMITTED',
    statusLabel: '待复核',
    reported: true,
    totalAmount: 0,
    supplierCompensationAmount: 0,
    storeBorneAmount: 0,
    detailCount: 0,
    attachmentCount: 1,
    submittedAt: `${today}T09:30:00`,
    details: [],
    attachments: [{
      id: 'existing-photo',
      fileName: 'existing.png',
      contentType: 'image/png',
    }],
  }
  const secondExistingReport = {
    ...existingReport,
    id: 'report-existing-2',
    status: 'REVIEWED',
    statusLabel: '已复核',
    submittedAt: `${today}T08:15:00`,
    attachments: [],
    attachmentCount: 0,
  }
  const imageBuffer = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=',
    'base64',
  )

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
      return route.fulfill(ok([existingReport, secondExistingReport]))
    }
    if (url.pathname === '/api/daily-loss/reports' && request.method() === 'POST') {
      saveBodies.push(request.postDataJSON())
      return route.fulfill(ok({ ...existingReport, id: `report-saved-${saveBodies.length}`, status: 'DRAFT' }))
    }
    if (/^\/api\/daily-loss\/reports\/report-saved-\d+\/attachments$/.test(url.pathname)) {
      uploadCount += 1
      return route.fulfill(ok({ ...existingReport, id: url.pathname.split('/')[4] }))
    }
    if (/^\/api\/daily-loss\/reports\/report-saved-\d+\/submit$/.test(url.pathname)) {
      return route.fulfill(ok({ ...existingReport, id: url.pathname.split('/')[4], status: 'SUBMITTED' }))
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
  await expect(page.getByText('已报 2 次', { exact: true })).toBeVisible()
  await expect(page.getByText(/厂商赔付/)).toHaveCount(0)
  await expect(page.getByText('总计损耗金额', { exact: true })).toBeVisible()
  await expect(page.getByText(/店铺承担/)).toHaveCount(0)
  await expect(page.getByLabel('报损结算')).toBeVisible()
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
  await expect(detailDialog.locator('.detail-settlement')).toBeVisible()
  await expect(detailDialog.getByText('总计损耗金额', { exact: true })).toBeVisible()
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
  await expect(page.getByLabel('报损结算')).toBeVisible()
  await page.getByRole('button', { name: '变质', exact: true }).click()
  await page.locator('.attachment-field input[type="file"]').setInputFiles({
    name: 'milk-loss.png',
    mimeType: 'image/png',
    buffer: imageBuffer,
  })
  await page.getByRole('button', { name: '提交本次报损', exact: true }).click()

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

  await page.locator('.item-picker-trigger').first().click()
  await material.fill('芒果')
  await page.getByRole('option', { name: /芒果.*FRUIT_CHECK_003/ }).click()
  await expect(page.getByRole('button', { name: '不去皮', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: '去皮', exact: true }).click()
  await page.locator('.quantity-control input').first().fill('100')
  await expect(page.locator('.pricing-hint').first()).toContainText('去皮重量 ÷ 出肉率 53.03%')
  await expect(page.locator('.settlement-block strong')).toHaveText('¥1.70')
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('button', { name: '去皮', exact: true })).toBeVisible()
  await expect.poll(() => page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
  }))).toEqual({ viewport: 390, content: 390 })
  await page.getByRole('button', { name: '切配损耗', exact: true }).click()
  await page.locator('.attachment-field input[type="file"]').setInputFiles({
    name: 'mango-loss.png',
    mimeType: 'image/png',
    buffer: imageBuffer,
  })
  await page.getByRole('button', { name: '提交本次报损', exact: true }).click()

  await expect.poll(() => saveBodies.length).toBe(2)
  await expect.poll(() => uploadCount).toBe(2)
  expect(saveBodies[1]).toMatchObject({
    storeId: 'rg1',
    details: [{
      itemConfigId: 303,
      lossQuantity: 100,
      lossReason: '切配损耗',
      peelState: 'PEELED',
    }],
  })
  expect(await page.evaluate(() => Math.max(
    document.documentElement.scrollWidth,
    document.body.scrollWidth,
  ) - window.innerWidth)).toBeLessThanOrEqual(1)
})
