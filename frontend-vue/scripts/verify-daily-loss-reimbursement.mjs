import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const base = process.env.FRONTEND_PREVIEW_URL || 'http://127.0.0.1:5174'
const artifactDir = process.env.FRONTEND_DAILY_LOSS_REIMBURSEMENT_ARTIFACT_DIR
  || resolve(process.cwd(), '../output/playwright')
const tokenKey = 'ai_profit_vue_token'
const userKey = 'ai_profit_vue_user'
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=',
  'base64',
)

function scope(mode, storeIds = []) {
  return { mode, storeIds, warehouseIds: [] }
}

function makeUser(role) {
  const profiles = {
    STORE_MANAGER: {
      id: 301,
      displayName: '店长验收',
      roleLabel: '店长',
      defaultWorkspace: '/store',
      storeScope: ['TEST-STORE-01'],
      dataScope: scope('OWN_STORE', ['TEST-STORE-01']),
      dataScopes: {
        STORE: scope('OWN_STORE', ['TEST-STORE-01']),
        FINANCE: scope('OWN_STORE', ['TEST-STORE-01']),
        INSPECTION: scope('STORE_LIST', ['TEST-STORE-01']),
      },
      boundStoreId: 'TEST-STORE-01',
      boundStoreName: '测试门店',
      brandId: 1,
      brandName: '验收品牌',
      // 故意模拟遗留/误配的报损权限；路由仍必须拒绝店长。
      permissions: [
        'system.dashboard.read', 'store.read', 'expense.create', 'expense.read',
        'daily_loss.read', 'daily_loss.create', 'daily_loss.export',
        'attachment.read', 'attachment.write',
      ],
    },
    SUPERVISOR: {
      id: 302,
      displayName: '督导验收',
      roleLabel: '督导',
      defaultWorkspace: '/operations/inspection',
      storeScope: ['all'],
      dataScope: scope('STORE_LIST', ['TEST-STORE-01']),
      dataScopes: {
        STORE: scope('STORE_LIST', ['TEST-STORE-01']),
        FINANCE: scope('NONE'),
        INSPECTION: scope('STORE_LIST', ['TEST-STORE-01']),
      },
      boundStoreId: null,
      boundStoreName: null,
      brandId: null,
      brandName: null,
      // 故意模拟遗留/误配的报损权限；路由仍必须拒绝督导。
      permissions: [
        'system.dashboard.read', 'operations.dashboard.read', 'store.read',
        'daily_loss.read', 'daily_loss.review', 'daily_loss.export',
        'inspection.read', 'inspection.manage', 'attachment.read', 'attachment.write',
      ],
    },
    FINANCE: {
      id: 303,
      displayName: '财务验收',
      roleLabel: '财务',
      defaultWorkspace: '/finance',
      storeScope: ['TEST-STORE-01'],
      dataScope: scope('STORE_LIST', ['TEST-STORE-01']),
      dataScopes: {
        STORE: scope('STORE_LIST', ['TEST-STORE-01']),
        FINANCE: scope('STORE_LIST', ['TEST-STORE-01']),
        INSPECTION: scope('NONE'),
      },
      boundStoreId: null,
      boundStoreName: null,
      brandId: null,
      brandName: null,
      permissions: [
        'system.dashboard.read', 'store.read', 'finance.profit.read',
        'daily_loss.read', 'daily_loss.export', 'attachment.read',
      ],
    },
    BOSS: {
      id: 1,
      displayName: '老板验收',
      roleLabel: '老板（系统管理员）',
      defaultWorkspace: '/boss',
      storeScope: ['all'],
      dataScope: scope('ALL', ['all']),
      dataScopes: {
        STORE: scope('ALL', ['all']),
        FINANCE: scope('ALL', ['all']),
        INSPECTION: scope('ALL', ['all']),
      },
      boundStoreId: null,
      boundStoreName: null,
      brandId: null,
      brandName: null,
      permissions: [],
    },
  }
  const profile = profiles[role]
  if (!profile) throw new Error(`Unsupported verification role: ${role}`)
  return {
    ...profile,
    tenantId: 1,
    tenantName: '验收租户',
    username: role.toLowerCase(),
    role,
    permissionVersion: 63,
  }
}

function apiBody(data) {
  return JSON.stringify({ success: true, message: 'OK', code: 'OK', data })
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function month() {
  return today().slice(0, 7)
}

function mockApiData(path, user) {
  if (path === '/api/auth/me') return user
  if (path === '/api/auth/logout') return null
  if (path === '/api/stores') return [{ id: 'TEST-STORE-01', code: 'TS01', name: '测试门店' }]
  if (path === '/api/brands') return []
  if (path === '/api/todos') return []
  if (path.startsWith('/api/todos/')) return []
  if (path === '/api/expenses') {
    return [
      {
        id: 'EXP-1001',
        storeId: 'TEST-STORE-01',
        storeName: '测试门店',
        month: month(),
        expenseDate: today(),
        amount: 38.5,
        reason: '门店耗材报销',
        status: '已提交',
        submitterName: '店长验收',
        submittedAt: `${today()}T08:30:00`,
        attachments: [
          {
            id: 8101,
            fileName: 'expense-receipt.png',
            contentType: 'image/png',
            sizeBytes: png.length,
            downloadUrl: '/api/storage/attachments/8101',
          },
        ],
        supplements: [],
      },
    ]
  }
  if (path === '/api/daily-loss/items') {
    return [
      {
        id: 701,
        itemCode: 'FRUIT_CHECK_007',
        itemName: '凤梨',
        category: '水果检查表',
        unit: '个',
        unitPrice: 25,
      },
      {
        id: 702,
        itemCode: 'FRUIT_CHECK_002',
        itemName: '新鲜牛油果',
        category: '水果检查表',
        unit: '个',
        unitPrice: 4.5,
      },
    ]
  }
  if (path === '/api/daily-loss/reports') {
    return [
      {
        id: 'DLR-9001',
        storeId: 'TEST-STORE-01',
        storeCode: 'TS01',
        storeName: '测试门店',
        lossDate: today(),
        status: 'SUBMITTED',
        statusLabel: '待复核',
        reported: true,
        totalAmount: 29.5,
        detailCount: 2,
        attachmentCount: 1,
        details: [
          { id: 1, itemName: '凤梨', lossQuantity: 1, unit: '个', amount: 25 },
          { id: 2, itemName: '新鲜牛油果', lossQuantity: 1, unit: '个', amount: 4.5 },
        ],
        attachments: [
          {
            id: 9101,
            fileName: 'daily-loss-photo.png',
            contentType: 'image/png',
            sizeBytes: png.length,
            downloadUrl: '/api/storage/attachments/9101',
          },
        ],
      },
    ]
  }
  if (path.includes('/dashboard') || path.includes('/overview')) return {}
  if (path.includes('/page')) return { items: [], total: 0 }
  return []
}

async function preparePage(browser, role, viewport, { exportFailure = false } = {}) {
  const user = makeUser(role)
  const context = await browser.newContext({ viewport, acceptDownloads: true })
  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/storage/attachments/8101' || url.pathname === '/api/storage/attachments/9101') {
      await route.fulfill({ status: 200, contentType: 'image/png', body: png })
      return
    }
    if (url.pathname === '/api/daily-loss/exports/monthly.xlsx') {
      if (exportFailure) {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, message: '导出服务暂不可用，请稍后重试。', code: 'DAILY_LOSS_EXCEL_EXPORT_FAILED' }),
        })
        return
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        headers: { 'content-disposition': "attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E9%97%A8%E5%BA%97-2026%E5%B9%B407%E6%9C%88-%E6%AF%8F%E6%97%A5%E6%8A%A5%E6%8D%9F.xlsx" },
        body: Buffer.from('mock-xlsx'),
      })
      return
    }
    if (!url.pathname.startsWith('/api/')) {
      await route.continue()
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiBody(mockApiData(url.pathname, user)),
    })
  })
  const page = await context.newPage()
  await page.addInitScript(({ tokenKey, userKey, user }) => {
    window.localStorage.setItem(tokenKey, `daily-loss-reimbursement-${user.role}-token`)
    window.localStorage.setItem(userKey, JSON.stringify(user))
  }, { tokenKey, userKey, user })
  return { context, page }
}

async function verifyExpenseUploadPreview(browser) {
  const { context, page } = await preparePage(browser, 'STORE_MANAGER', { width: 390, height: 844 })
  const uploadPath = resolve(artifactDir, 'daily-loss-reimbursement-upload-sample.png')
  try {
    await writeFile(uploadPath, png)
    await page.goto(`${base}/expenses`, { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('networkidle').catch(() => undefined)
    await page.locator('.submit-inline').click()
    await page.locator('.expense-drawer').waitFor({ state: 'visible', timeout: 15000 })
    await page.locator('input.file-input').setInputFiles(uploadPath)
    await page.locator('.selected-attachment img').waitFor({ state: 'visible', timeout: 15000 })
    await page.locator('.preview-button').click()
    await page.locator('.drawer-preview-dialog img').waitFor({ state: 'visible', timeout: 15000 })
    const path = resolve(artifactDir, 'daily-loss-reimbursement-store-manager-upload-preview-mobile-390x844.png')
    await page.screenshot({ path, fullPage: true })
    return path
  } finally {
    await context.close()
  }
}

async function assertNoHorizontalOverflow(page, label) {
  const overflow = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    body: document.body.scrollWidth - document.body.clientWidth,
    width: window.innerWidth,
  }))
  if (overflow.document > 1 || overflow.body > 1) throw new Error(`${label} has horizontal overflow: ${JSON.stringify(overflow)}`)
}

async function verifyDailyLossPhotoPreview(browser, role, viewport, file) {
  const { context, page } = await preparePage(browser, role, viewport)
  try {
    await page.goto(`${base}/daily-loss`, { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('networkidle').catch(() => undefined)
    await assertNoHorizontalOverflow(page, `${role} daily-loss ${viewport.width}px`)
    await page.locator('.photo-grid img').first().waitFor({ state: 'visible', timeout: 15000 })
    await page.locator('.photo-grid button').first().click()
    await page.locator('.image-preview-dialog img').waitFor({ state: 'visible', timeout: 15000 })
    const path = resolve(artifactDir, file)
    await page.screenshot({ path, fullPage: true })
    return path
  } finally {
    await context.close()
  }
}

async function verifyMonthlyExcelExport(browser, role) {
  const { context, page } = await preparePage(browser, role, { width: 1280, height: 900 })
  try {
    const exportRequests = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname === '/api/daily-loss/exports/monthly.xlsx') exportRequests.push(request.url())
    })
    await page.goto(`${base}/daily-loss`, { waitUntil: 'domcontentloaded' })
    await page.waitForLoadState('networkidle').catch(() => undefined)
    await assertNoHorizontalOverflow(page, `${role} daily-loss 1280px`)
    const button = page.getByRole('button', { name: '导出本月报损 Excel' })
    await button.waitFor({ state: 'visible', timeout: 15000 })
    if (await page.getByText('导出照片包', { exact: true }).count()) throw new Error('旧照片 ZIP 导出入口仍可见')
    const download = page.waitForEvent('download')
    await button.click()
    const result = await download
    if (!result.suggestedFilename().endsWith('.xlsx')) throw new Error('每日报损导出未返回 xlsx 文件名')
    await page.getByText('本月报损 Excel 已开始下载。').waitFor({ state: 'visible', timeout: 5000 })
    if (!exportRequests.some((url) => new URL(url).searchParams.has('month'))) {
      throw new Error('前端没有调用 monthly.xlsx 接口或未传月份')
    }
  } finally {
    await context.close()
  }

  const failed = await preparePage(browser, 'FINANCE', { width: 1280, height: 900 }, { exportFailure: true })
  try {
    await failed.page.goto(`${base}/daily-loss`, { waitUntil: 'domcontentloaded' })
    await failed.page.waitForLoadState('networkidle').catch(() => undefined)
    await failed.page.getByRole('button', { name: '导出本月报损 Excel' }).click()
    await failed.page.getByRole('alert').getByText('导出服务暂不可用，请稍后重试。').waitFor({ state: 'visible', timeout: 5000 })
  } finally {
    await failed.context.close()
  }
}

async function verifyDailyLossRouteDenied(browser) {
  for (const role of ['STORE_MANAGER', 'SUPERVISOR']) {
    const { context, page } = await preparePage(browser, role, { width: 1280, height: 900 })
    try {
      const dailyLossRequests = []
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith('/api/daily-loss/')) dailyLossRequests.push(request.url())
      })
      await page.goto(`${base}/daily-loss`, { waitUntil: 'domcontentloaded' })
      await page.waitForLoadState('networkidle').catch(() => undefined)
      if (new URL(page.url()).pathname !== '/no-permission') {
        throw new Error(`${role} with misconfigured daily-loss permissions was not routed to /no-permission`)
      }
      if (dailyLossRequests.length) throw new Error(`${role} route denial issued daily-loss API requests: ${dailyLossRequests.join(', ')}`)
      if (await page.getByRole('button', { name: '导出本月报损 Excel' }).count()) {
        throw new Error(`${role} route denial still exposed the Excel export entry`)
      }
    } finally {
      await context.close()
    }
  }
}

await mkdir(artifactDir, { recursive: true })

const browser = await chromium.launch()
const screenshots = []
try {
  screenshots.push(await verifyExpenseUploadPreview(browser))
  await verifyDailyLossRouteDenied(browser)
  screenshots.push(await verifyDailyLossPhotoPreview(
    browser,
    'FINANCE',
    { width: 1280, height: 900 },
    'daily-loss-reimbursement-finance-loss-photo-preview-desktop-1280x900.png',
  ))
  await verifyMonthlyExcelExport(browser, 'BOSS')
  await verifyMonthlyExcelExport(browser, 'FINANCE')
  const summaryPath = resolve(artifactDir, 'daily-loss-reimbursement-ui-summary.json')
  await writeFile(summaryPath, `${JSON.stringify({ base, screenshots }, null, 2)}\n`, 'utf8')
  console.log(`daily loss reimbursement UI verification passed; artifacts=${artifactDir}`)
  console.log(`summary=${summaryPath}`)
  for (const screenshot of screenshots) console.log(`screenshot=${screenshot}`)
} finally {
  await browser.close()
}
