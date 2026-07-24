import { readFile } from 'node:fs/promises'
import { inflateRawSync } from 'node:zlib'
import { expect, test, type Download, type Page, type Route } from '@playwright/test'

const session = {
  id: 45,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '报表仓库管理员',
  role: 'WAREHOUSE',
  roleLabel: '仓库管理员',
  storeScope: ['all'],
  permissions: [
    'warehouse.read',
    'warehouse.central.read',
    'warehouse.requisition.process',
    'warehouse.requisition.review',
  ],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    WAREHOUSE: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1', '2'] },
  },
  dataScope: { mode: 'WAREHOUSE_LIST', storeIds: [], warehouseIds: ['1', '2'] },
  defaultWorkspace: '/warehouse',
  permissionVersion: 1,
}

const warehouse = {
  id: 1,
  code: 'CENTRAL-01',
  name: '测试总仓',
  type: 'CENTRAL',
  regionCode: 'JINGZHOU',
  parentWarehouseId: null,
  parentWarehouseName: null,
  externalPurchaseAllowed: true,
  storeSupplyAllowed: true,
  enabled: true,
  canRead: true,
  canProcessRequisition: true,
}

const regionalWarehouse = {
  ...warehouse,
  id: 2,
  code: 'REGIONAL-02',
  name: '山东分仓',
  type: 'REGIONAL',
  regionCode: 'SHANDONG',
  parentWarehouseId: 1,
  parentWarehouseName: warehouse.name,
  externalPurchaseAllowed: false,
}

const items = [
  {
    id: 11,
    code: 'MILK-11',
    name: '鲜牛奶',
    unit: '箱',
    stockQuantity: 20,
    storeStockQuantity: 0,
    warehouseAvailableQuantity: 20,
    unitPrice: 50,
    stockStatus: 'NORMAL',
    alertLevel: 'NORMAL',
    alertText: '',
    active: true,
  },
  {
    id: 22,
    code: 'STRAW-22',
    name: '吸管',
    unit: '包',
    stockQuantity: 30,
    storeStockQuantity: 0,
    warehouseAvailableQuantity: 30,
    unitPrice: 8,
    stockStatus: 'NORMAL',
    alertLevel: 'NORMAL',
    alertText: '',
    active: true,
  },
]

const requisition = {
  id: 'REQ-EXISTING-PDF',
  storeId: 'rg1',
  storeName: '荆州之星店',
  warehouseId: 1,
  warehouseName: warehouse.name,
  status: 'RECEIVED',
  statusLabel: '门店已收货',
  totalAmount: 100,
  note: '原按单导出入口',
  submittedAt: '2026-07-03 10:00',
  lines: [{
    itemId: 11,
    itemName: '鲜牛奶',
    unit: '箱',
    requestedQuantity: 2,
    approvedQuantity: 2,
    shippedQuantity: 2,
    unitPrice: 50,
    amount: 100,
  }],
}

interface RequestLog {
  summaryBodies: Record<string, unknown>[]
  deliveryDownloads: string[]
}

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, code: 'OK', message: 'OK', data }),
})

async function readDownload(download: Download) {
  const stream = await download.createReadStream()
  if (!stream) throw new Error('下载文件流不可用')
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks)
}

function readXlsxEntry(archive: Buffer, entryName: string) {
  const centralSignature = Buffer.from([0x50, 0x4b, 0x01, 0x02])
  let offset = archive.indexOf(centralSignature)
  while (offset >= 0) {
    const compressionMethod = archive.readUInt16LE(offset + 10)
    const compressedSize = archive.readUInt32LE(offset + 20)
    const fileNameLength = archive.readUInt16LE(offset + 28)
    const extraLength = archive.readUInt16LE(offset + 30)
    const commentLength = archive.readUInt16LE(offset + 32)
    const localHeaderOffset = archive.readUInt32LE(offset + 42)
    const fileName = archive.subarray(offset + 46, offset + 46 + fileNameLength).toString('utf8')
    if (fileName === entryName) {
      const localFileNameLength = archive.readUInt16LE(localHeaderOffset + 26)
      const localExtraLength = archive.readUInt16LE(localHeaderOffset + 28)
      const dataStart = localHeaderOffset + 30 + localFileNameLength + localExtraLength
      const compressed = archive.subarray(dataStart, dataStart + compressedSize)
      if (compressionMethod === 0) return compressed
      if (compressionMethod === 8) return inflateRawSync(compressed)
      throw new Error(`不支持的 XLSX 压缩方式：${compressionMethod}`)
    }
    offset = archive.indexOf(
      centralSignature,
      offset + 46 + fileNameLength + extraLength + commentLength,
    )
  }
  throw new Error(`XLSX 中缺少文件：${entryName}`)
}

async function fulfillApi(route: Route, log: RequestLog) {
  const request = route.request()
  const url = new URL(request.url())
  const path = url.pathname

  if (path === '/api/auth/me') return route.fulfill(ok(session))
  if (path === '/api/warehouse/warehouses') return route.fulfill(ok([warehouse, regionalWarehouse]))
  if (path === '/api/stores') {
    return route.fulfill(ok([
      {
        id: 'rg1',
        code: 'RG1',
        name: '荆州之星店',
        status: 'ACTIVE',
        supplyWarehouseId: 1,
      },
      {
        id: 'rg2',
        code: 'RG2',
        name: '荆州大学城店',
        status: 'ACTIVE',
        supplyWarehouseId: 1,
      },
      {
        id: 'sd1',
        code: 'SD1',
        name: '山东首店',
        status: 'ACTIVE',
        supplyWarehouseId: 2,
      },
    ]))
  }
  if (path === '/api/warehouse/overview') {
    const currentWarehouse = url.searchParams.get('warehouseId') === '2' ? regionalWarehouse : warehouse
    const currentItems = currentWarehouse.id === 2 ? [items[1]] : items
    return route.fulfill(ok({
      warehouse: currentWarehouse,
      summary: {
        itemCount: currentItems.length,
        lowStockCount: 0,
        expiringCount: 0,
        overstockCount: 0,
        pendingRequisitionCount: 0,
        pendingReceiptCount: 0,
        pendingPurchaseCount: 0,
        stockValue: 1240,
        inTransitQuantity: 0,
      },
      alerts: [],
      items: currentItems,
      requisitions: [requisition],
      stockBatches: [],
      movements: [],
    }))
  }
  if (path === '/api/warehouse/requests/export-summary' && request.method() === 'POST') {
    log.summaryBodies.push(request.postDataJSON())
    return route.fulfill({
      status: 200,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      headers: {
        'Content-Disposition': "attachment; filename*=UTF-8''requisition_report_%E6%B1%87%E6%80%BB_%E9%97%A8%E5%BA%97-%E7%89%A9%E6%96%99-%E5%91%A8%E6%9C%9F_20260701_20260731.xlsx",
      },
      body: await readFile(new URL('../fixtures/requisition-summary-sample.xlsx', import.meta.url)),
    })
  }
  if (path === '/api/warehouse/print/requisitions/REQ-EXISTING-PDF/delivery') {
    log.deliveryDownloads.push(path)
    return route.fulfill({
      status: 200,
      contentType: 'application/pdf',
      headers: {
        'Content-Disposition': "attachment; filename*=UTF-8''delivery-REQ-EXISTING-PDF.pdf",
      },
      body: Buffer.from('%PDF-1.4 fake'),
    })
  }
  return route.fulfill(ok([]))
}

async function prepare(page: Page) {
  const log: RequestLog = {
    summaryBodies: [],
    deliveryDownloads: [],
  }
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'REQUISITION-SUMMARY-E2E')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, session)
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => fulfillApi(route, log))
  await page.goto('/warehouse/requests?warehouseId=1')
  await expect(page.getByRole('heading', { name: '叫货汇总报表' })).toBeVisible()
  return log
}

async function fillDateRange(page: Page, startDate = '2026-07-01', endDate = '2026-07-31') {
  await page.getByLabel('报表开始日期').fill(startDate)
  await page.getByLabel('报表结束日期').fill(endDate)
}

test('按门店、物料和月份导出聚合报表，并使用响应文件名', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page)
  await page.getByLabel('报表门店').selectOption(['rg1', 'rg2'])
  await page.getByLabel('报表物料').selectOption(['11', '22'])

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出聚合报表' }).click()
  const download = await downloadPromise

  await expect.poll(() => log.summaryBodies.length).toBe(1)
  expect(log.summaryBodies[0]).toMatchObject({
    warehouseId: 1,
    startDate: '2026-07-01',
    endDate: '2026-07-31',
    storeIds: expect.arrayContaining(['rg1', 'rg2']),
    productIds: expect.arrayContaining([11, 22]),
    periodType: 'MONTH',
    includeZeroRows: false,
    groupBy: ['store', 'product', 'period'],
  })
  expect(log.summaryBodies[0].storeIds).toHaveLength(2)
  expect(log.summaryBodies[0].productIds).toHaveLength(2)
  expect(download.suggestedFilename()).toBe('requisition_report_汇总_门店-物料-周期_20260701_20260731.xlsx')
  const workbook = await readDownload(download)
  const sharedStrings = readXlsxEntry(workbook, 'xl/sharedStrings.xml').toString('utf8')
  const worksheet = readXlsxEntry(workbook, 'xl/worksheets/sheet1.xml').toString('utf8')
  expect(sharedStrings).toContain('门店ID')
  expect(sharedStrings).toContain('鲜奶')
  expect(sharedStrings).toContain('2026年06月')
  expect(worksheet).toContain('<dimension ref="A1:J4"/>')
  expect(worksheet).toContain('<c r="I2" t="n" s="2"><v>5.0</v></c>')
  expect(worksheet).toContain('<c r="J2" t="n" s="3"><v>25.0</v></c>')
  await expect(page.getByText('聚合报表已导出')).toBeVisible()
})

test('可仅选择门店导出，未选物料表示全部物料', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page)
  await page.getByLabel('报表门店').selectOption(['rg2'])

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出聚合报表' }).click()
  await downloadPromise

  await expect.poll(() => log.summaryBodies.length).toBe(1)
  expect(log.summaryBodies[0]).toMatchObject({
    storeIds: ['rg2'],
    productIds: [],
  })
})

test('可仅选择物料导出，未选门店表示全部门店', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page)
  await page.getByLabel('报表物料').selectOption(['22'])

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出聚合报表' }).click()
  await downloadPromise

  await expect.poll(() => log.summaryBodies.length).toBe(1)
  expect(log.summaryBodies[0]).toMatchObject({
    storeIds: [],
    productIds: [22],
  })
})

test('可选择包含零量组合并传递给后端', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page)
  await page.getByLabel('包含零量组合').check()

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出聚合报表' }).click()
  await downloadPromise

  await expect.poll(() => log.summaryBodies.length).toBe(1)
  expect(log.summaryBodies[0]).toMatchObject({
    includeZeroRows: true,
  })
})

test('日期区间反选时显示中文提示且不发送导出请求', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page, '2026-07-31', '2026-07-01')

  await page.getByRole('button', { name: '导出聚合报表' }).click()

  await expect(page.getByRole('alert')).toContainText('开始日期不能晚于结束日期')
  expect(log.summaryBodies).toHaveLength(0)
})

test('日报、周报、月报切换会提交对应周期粒度', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page)

  for (const periodType of ['DAY', 'WEEK', 'MONTH']) {
    await page.getByLabel('周期粒度').selectOption(periodType)
    const downloadPromise = page.waitForEvent('download')
    await page.getByRole('button', { name: '导出聚合报表' }).click()
    await downloadPromise
  }

  await expect.poll(() => log.summaryBodies.length).toBe(3)
  expect(log.summaryBodies.map((body) => body.periodType)).toEqual(['DAY', 'WEEK', 'MONTH'])
})

test('切换仓库会更新导出仓库并清理已不可见物料', async ({ page }) => {
  const log = await prepare(page)
  await fillDateRange(page)
  await page.getByLabel('报表门店').selectOption(['rg1'])
  await page.getByLabel('报表物料').selectOption(['11'])

  await page.getByLabel('当前仓库').selectOption('2')
  await expect(page).toHaveURL(/warehouseId=2/)
  await expect(page.getByLabel('报表门店').locator('option')).toHaveCount(1)
  await expect(page.getByLabel('报表门店').locator('option')).toHaveText(['山东首店（SD1）'])
  await expect(page.getByLabel('报表物料').locator('option')).toHaveCount(1)
  await expect(page.getByLabel('报表物料').locator('option')).toHaveText(['吸管（STRAW-22）'])

  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出聚合报表' }).click()
  await downloadPromise

  await expect.poll(() => log.summaryBodies.length).toBe(1)
  expect(log.summaryBodies[0]).toMatchObject({
    warehouseId: 2,
    storeIds: [],
    productIds: [],
  })
})

test('原叫货单配送单下载与聚合导出入口并行保留', async ({ page }) => {
  const log = await prepare(page)

  const row = page.getByRole('row').filter({ hasText: requisition.id })
  const downloadPromise = page.waitForEvent('download')
  await row.getByRole('button', { name: '下载出库单' }).click()
  const download = await downloadPromise

  await expect.poll(() => log.deliveryDownloads.length).toBe(1)
  expect(log.summaryBodies).toHaveLength(0)
  expect(download.suggestedFilename()).toBe('delivery-REQ-EXISTING-PDF.pdf')
})
