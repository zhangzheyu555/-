import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const base = process.env.FRONTEND_PREVIEW_URL || 'http://127.0.0.1:5174'
const artifactDir = process.env.FRONTEND_ROLE_MENU_ARTIFACT_DIR
  || resolve(process.cwd(), '../output/playwright')
const tokenKey = 'ai_profit_vue_token'
const userKey = 'ai_profit_vue_user'

function scope(mode, storeIds = [], warehouseIds = []) {
  return { mode, storeIds, warehouseIds }
}

const contracts = {
  SUPERVISOR: {
    name: '督导验收',
    label: '督导',
    defaultWorkspace: '/operations/inspection',
    permissions: [
      'system.dashboard.read',
      'operations.dashboard.read',
      'store.read',
      'daily_loss.read',
      'daily_loss.review',
      'inspection.read',
      'inspection.manage',
      'exam.learn',
      'exam.manage',
      'exam.report',
      'platform.read',
      'platform.manage',
      'attachment.read',
      'attachment.write',
      'todo.read',
      'todo.transition',
      'assistant.use',
      'employee_assistant.use',
      'employee_assistant.handoff_manage',
    ],
    dataScopes: {
      STORE: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      INSPECTION: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      EXAM: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
    },
    expectedMenus: ['每日报损', '督导巡店', '整改复核'],
    forbiddenMenus: ['运营工作台', '平台配置', '培训考试', '盘存管理', '财务工作台', '老板工作台', '账号权限'],
    routeDenials: ['/operations', '/platform-login', '/operations/exams', '/store/exams', '/exam-center', '/boss'],
  },
  OPERATIONS: {
    name: '运营验收',
    label: '运营',
    defaultWorkspace: '/operations',
    permissions: [
      'system.dashboard.read',
      'operations.dashboard.read',
      'store.read',
      'employee.read',
      'warehouse.store.read',
      'inventory.read',
      'inventory.manage',
      'inventory.review',
      'inspection.read',
      'inspection.manage',
      'exam.learn',
      'exam.manage',
      'exam.report',
      'platform.read',
      'platform.manage',
      'attachment.read',
      'attachment.write',
      'todo.read',
      'todo.transition',
      'assistant.use',
      'employee_assistant.use',
      'employee_assistant.handoff_manage',
    ],
    dataScopes: {
      STORE: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      WAREHOUSE: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      INSPECTION: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      EXAM: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
    },
    expectedMenus: ['运营工作台', '仓库中心', '门店详情', '督导巡店', '整改复核', '培训考试', '平台配置'],
    forbiddenMenus: ['每日报损', '财务工作台', '老板工作台', '账号权限'],
    routeAllows: [
      { from: '/exam-center', to: '/operations/exams' },
      { from: '/platform-login', to: '/platform-login' },
    ],
  },
  STORE_MANAGER: {
    name: '店长验收',
    label: '店长',
    defaultWorkspace: '/store',
    permissions: [
      'system.dashboard.read',
      'store.read',
      'finance.profit.read',
      'finance.profit.write',
      'expense.create',
      'expense.read',
      'salary.read',
      'daily_loss.read',
      'daily_loss.create',
      'warehouse.store.read',
      'warehouse.requisition.create',
      'warehouse.requisition.receive',
      'inspection.read',
      'exam.learn',
      'exam.report',
      'attachment.read',
      'attachment.write',
      'assistant.use',
    ],
    dataScopes: {
      STORE: scope('OWN_STORE', ['TEST-STORE-01']),
      FINANCE: scope('OWN_STORE', ['TEST-STORE-01']),
      SALARY: scope('OWN_STORE', ['TEST-STORE-01']),
      WAREHOUSE: scope('OWN_STORE', ['TEST-STORE-01']),
      INSPECTION: scope('OWN_STORE', ['TEST-STORE-01']),
      EXAM: scope('OWN_STORE', ['TEST-STORE-01']),
    },
    expectedMenus: ['门店工作台', '报销栏', '本店工资核对', '仓库中心', '每日报损', '门店详情', '巡检记录', '巡检整改', '培训考试'],
    forbiddenMenus: ['运营工作台', '平台配置', '老板工作台', '财务工作台', '账号权限', '整改复核'],
    routeDenials: ['/operations', '/platform-login', { from: '/boss', to: '/store' }, '/finance'],
    routeAllows: [
      { from: '/warehouse', to: '/store/inventory' },
      { from: '/exam-center', to: '/store/exams' },
    ],
  },
  WAREHOUSE: {
    name: '仓库验收',
    label: '仓库管理员',
    defaultWorkspace: '/warehouse',
    permissions: [
      'system.dashboard.read',
      'warehouse.read',
      'warehouse.purchase',
      'warehouse.transfer.request',
      'warehouse.transfer.approve',
      'warehouse.transfer.ship',
      'warehouse.transfer.receive',
      'warehouse.requisition.process',
      'warehouse.configure',
      'warehouse.central.read',
      'warehouse.central.manage',
      'daily_loss.read',
      'assistant.use',
    ],
    dataScopes: {
      WAREHOUSE: scope('CENTRAL_WAREHOUSE', [], ['JZ-CENTRAL']),
    },
    expectedMenus: ['仓库工作台', '仓库中心'],
    forbiddenMenus: ['运营工作台', '平台配置', '培训考试', '财务工作台', '老板工作台', '账号权限'],
    routeDenials: ['/operations', '/platform-login', '/boss', '/finance', '/store'],
  },
  FINANCE: {
    name: '财务验收',
    label: '财务',
    defaultWorkspace: '/finance',
    permissions: [
      'system.dashboard.read',
      'store.read',
      'finance.profit.read',
      'finance.profit.write',
      'finance.profit.import',
      'finance.export',
      'expense.create',
      'expense.read',
      'expense.review',
      'salary.read',
      'salary.edit',
      'assistant.use',
    ],
    dataScopes: {
      STORE: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      FINANCE: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
      SALARY: scope('STORE_LIST', ['TEST-STORE-01', 'TEST-STORE-02']),
    },
    expectedMenus: ['财务工作台', '利润概览', '利润表', '数据录入', '报销栏', '员工工资', '数据导出'],
    forbiddenMenus: ['运营工作台', '平台配置', '培训考试', '老板工作台', '账号权限', '门店工作台'],
    routeDenials: ['/operations', '/platform-login', '/boss', '/store'],
  },
  BOSS: {
    name: '老板验收',
    label: '老板（系统管理员）',
    defaultWorkspace: '/boss',
    permissions: [],
    dataScopes: {
      STORE: scope('ALL', ['all']),
      FINANCE: scope('ALL', ['all']),
      SALARY: scope('ALL', ['all']),
      WAREHOUSE: scope('ALL', ['all'], ['all']),
      INSPECTION: scope('ALL', ['all']),
      EXAM: scope('ALL', ['all']),
    },
    expectedMenus: ['老板工作台', '利润概览', '仓库中心', '门店详情', '督导巡店', '培训考试', '平台配置', '账号权限'],
    forbiddenMenus: [],
    routeAllows: [
      { from: '/operations', to: '/operations' },
      { from: '/finance', to: '/finance' },
      { from: '/store', to: '/store' },
      { from: '/warehouse', to: '/warehouse' },
    ],
  },
}

const screenshotCases = [
  { role: 'SUPERVISOR', viewport: { width: 1365, height: 900 }, file: 'daily-loss-reimbursement-supervisor-desktop-1365x900.png', mobile: false },
  { role: 'OPERATIONS', viewport: { width: 1365, height: 900 }, file: 'daily-loss-reimbursement-operations-desktop-1365x900.png', mobile: false },
  { role: 'STORE_MANAGER', viewport: { width: 1365, height: 900 }, file: 'daily-loss-reimbursement-store-manager-desktop-1365x900.png', mobile: false },
  { role: 'SUPERVISOR', viewport: { width: 390, height: 844 }, file: 'daily-loss-reimbursement-supervisor-mobile-390x844.png', mobile: true },
  { role: 'OPERATIONS', viewport: { width: 390, height: 844 }, file: 'daily-loss-reimbursement-operations-mobile-390x844.png', mobile: true },
]

function makeUser(role) {
  const contract = contracts[role]
  return {
    id: role === 'BOSS' ? 1 : 100 + Object.keys(contracts).indexOf(role),
    tenantId: 1,
    tenantName: '验收租户',
    username: role.toLowerCase(),
    displayName: contract.name,
    role,
    roleLabel: contract.label,
    storeScope: role === 'STORE_MANAGER' ? ['TEST-STORE-01'] : ['all'],
    permissions: contract.permissions,
    dataScopes: contract.dataScopes,
    dataScope: contract.dataScopes.STORE || scope('NONE'),
    boundStoreId: role === 'STORE_MANAGER' ? 'TEST-STORE-01' : null,
    boundStoreName: role === 'STORE_MANAGER' ? '测试门店' : null,
    brandId: null,
    brandName: null,
    defaultWorkspace: contract.defaultWorkspace,
    permissionVersion: 63,
  }
}

function apiBody(data) {
  return JSON.stringify({ success: true, message: 'OK', code: 'OK', data })
}

function mockApiData(path, user) {
  if (path === '/api/auth/me') return user
  if (path === '/api/auth/logout') return null
  if (path === '/api/stores') return [{ id: 'TEST-STORE-01', code: 'TEST-STORE-01', name: '测试门店' }]
  if (path === '/api/brands') return []
  if (path === '/api/todos') return []
  if (path.startsWith('/api/todos/')) return []
  if (path.includes('/todo-dashboard')) {
    return {
      roleName: '老板',
      dataSource: '验收数据',
      updatedAt: new Date().toISOString(),
      todayFocus: { totalOpenCount: 0, needsBossActionCount: 0, roleWorkCount: 0, highRiskCount: 0, doneReviewCount: 0 },
      needsBossAction: [],
      highRiskReminders: [],
      roleProgress: [],
      doneReview: [],
    }
  }
  if (path.includes('/todo') || path.includes('/todos')) return { items: [], summary: {}, actions: [] }
  if (path.includes('/dashboard')) return {}
  if (path.includes('/overview')) return {}
  if (path.includes('/status') || path.includes('/config')) return {}
  if (path.includes('/page')) return { items: [], total: 0 }
  return []
}

async function preparePage(browser, viewport, role) {
  const user = makeUser(role)
  const context = await browser.newContext({ viewport })
  const diagnostics = []
  context.on('page', (page) => {
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) diagnostics.push(`${message.type()}: ${message.text()}`)
    })
    page.on('pageerror', (error) => diagnostics.push(`pageerror: ${error.stack || error.message}`))
  })
  await context.route('**/*', async (route) => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiBody(mockApiData(url.pathname, user)),
    })
  })
  const page = await context.newPage()
  await page.addInitScript(({ tokenKey, userKey, user }) => {
    window.localStorage.setItem(tokenKey, `phase4b-${user.role}-token`)
    window.localStorage.setItem(userKey, JSON.stringify(user))
  }, { tokenKey, userKey, user })
  return { context, page, diagnostics, user }
}

async function gotoAndWait(page, path) {
  await page.goto(`${base}${path}`, { waitUntil: 'domcontentloaded' })
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.locator('.app-sidebar--desktop:visible, .mobile-menu-button:visible, .no-permission-page:visible')
    .first()
    .waitFor({ state: 'visible', timeout: 15000 })
}

async function expectPath(page, expectedPath, label) {
  await page.waitForFunction((path) => window.location.pathname === path, expectedPath, { timeout: 10000 })
    .catch(async (error) => {
      const state = await page.evaluate(() => ({
        href: window.location.href,
        bodyText: document.body.innerText.slice(0, 800),
      })).catch((stateError) => ({ error: String(stateError) }))
      throw new Error(`${label} expected ${expectedPath}, actual ${page.url()}, state=${JSON.stringify(state)}`, { cause: error })
    })
}

async function visibleMenuText(page) {
  return page.locator('.sidebar-navigation-link:visible span').evaluateAll((nodes) => (
    nodes.map((node) => node.textContent?.trim()).filter(Boolean)
  ))
}

function assertHasMenu(texts, expected, label) {
  const joined = texts.join(' | ')
  for (const item of expected) {
    if (!joined.includes(item)) throw new Error(`${label} should show ${item}; menu=${joined}`)
  }
}

function assertNoMenu(texts, forbidden, label) {
  const joined = texts.join(' | ')
  for (const item of forbidden) {
    if (joined.includes(item)) throw new Error(`${label} should not show ${item}; menu=${joined}`)
  }
}

async function assertDesktopShell(page, label) {
  await page.locator('.app-sidebar--desktop').waitFor({ state: 'visible', timeout: 10000 })
  const metrics = await page.locator('.app-sidebar--desktop').evaluate((sidebar) => {
    const nav = sidebar.querySelector('.sidebar-navigation')
    const footer = sidebar.querySelector('.sidebar-account')
    return {
      documentOverflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      bodyOverflowX: document.body.scrollWidth - document.body.clientWidth,
      sidebarRight: sidebar.getBoundingClientRect().right,
      footerBottom: footer?.getBoundingClientRect().bottom || 0,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      navOverflowY: nav ? getComputedStyle(nav).overflowY : '',
    }
  })
  if (metrics.documentOverflowX > 1 || metrics.bodyOverflowX > 1) {
    throw new Error(`${label} desktop has horizontal overflow ${JSON.stringify(metrics)}`)
  }
  if (metrics.sidebarRight > metrics.viewportWidth + 1 || metrics.footerBottom > metrics.viewportHeight + 1) {
    throw new Error(`${label} desktop sidebar exceeds viewport ${JSON.stringify(metrics)}`)
  }
  if (metrics.navOverflowY !== 'auto') {
    throw new Error(`${label} desktop menu should be scroll-contained ${JSON.stringify(metrics)}`)
  }
}

async function openMobileMenu(page, label) {
  const button = page.locator('.mobile-menu-button')
  await button.waitFor({ state: 'visible', timeout: 10000 })
  await button.click()
  await page.locator('.mobile-nav-drawer.open').waitFor({ state: 'visible', timeout: 10000 })
  const metrics = await page.locator('.mobile-nav-drawer.open').evaluate((drawer) => {
    const rect = drawer.getBoundingClientRect()
    const links = Array.from(drawer.querySelectorAll('.sidebar-navigation-link'))
      .map((link) => link.getBoundingClientRect())
    return {
      documentOverflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      drawerLeft: rect.left,
      drawerRight: rect.right,
      drawerScrollOverflowX: drawer.scrollWidth - drawer.clientWidth,
      viewportWidth: window.innerWidth,
      clippedLinks: links.filter((rect) => rect.left < -1 || rect.right > window.innerWidth + 1).length,
    }
  })
  if (metrics.documentOverflowX > 1 || metrics.drawerLeft < -1 || metrics.drawerRight > metrics.viewportWidth + 1 || metrics.drawerScrollOverflowX > 1 || metrics.clippedLinks > 0) {
    throw new Error(`${label} mobile drawer overflow ${JSON.stringify(metrics)}`)
  }
}

async function verifyRole(browser, role) {
  const contract = contracts[role]
  const { context, page, diagnostics } = await preparePage(browser, { width: 1365, height: 900 }, role)
  const result = { role, defaultWorkspace: contract.defaultWorkspace, menu: [], routeDenials: [], routeAllows: [] }
  try {
    await gotoAndWait(page, '/')
    await expectPath(page, contract.defaultWorkspace, `${role} default workspace`)
    await assertDesktopShell(page, `${role} default`)
    const menus = await visibleMenuText(page)
    result.menu = menus
    assertHasMenu(menus, contract.expectedMenus, `${role} desktop`)
    assertNoMenu(menus, contract.forbiddenMenus, `${role} desktop`)

    for (const denied of contract.routeDenials || []) {
      const path = typeof denied === 'string' ? denied : denied.from
      const expected = typeof denied === 'string' ? '/no-permission' : denied.to
      await gotoAndWait(page, path)
      await expectPath(page, expected, `${role} denied ${path}`)
      result.routeDenials.push({ from: path, to: expected })
    }
    for (const entry of contract.routeAllows || []) {
      await gotoAndWait(page, entry.from)
      await expectPath(page, entry.to, `${role} allowed ${entry.from}`)
      result.routeAllows.push(entry)
    }
    return result
  } catch (error) {
    console.error(JSON.stringify({ role, url: page.url(), diagnostics }, null, 2))
    throw error
  } finally {
    await context.close()
  }
}

async function captureScreenshot(browser, item) {
  const contract = contracts[item.role]
  const { context, page, diagnostics } = await preparePage(browser, item.viewport, item.role)
  try {
    await gotoAndWait(page, '/')
    await expectPath(page, contract.defaultWorkspace, `${item.role} screenshot default`)
    if (item.mobile) {
      await openMobileMenu(page, `${item.role} screenshot`)
      const menus = await visibleMenuText(page)
      assertHasMenu(menus, contract.expectedMenus, `${item.role} mobile`)
      assertNoMenu(menus, contract.forbiddenMenus, `${item.role} mobile`)
    } else {
      await assertDesktopShell(page, `${item.role} screenshot`)
    }
    const path = resolve(artifactDir, item.file)
    await page.screenshot({ path, fullPage: true })
    return path
  } catch (error) {
    console.error(JSON.stringify({ role: item.role, screenshot: item.file, url: page.url(), diagnostics }, null, 2))
    throw error
  } finally {
    await context.close()
  }
}

await mkdir(artifactDir, { recursive: true })

const browser = await chromium.launch()
const summary = { base, roles: [], screenshots: [] }
try {
  for (const role of Object.keys(contracts)) {
    summary.roles.push(await verifyRole(browser, role))
  }
  for (const item of screenshotCases) {
    summary.screenshots.push(await captureScreenshot(browser, item))
  }
  const summaryPath = resolve(artifactDir, 'daily-loss-reimbursement-role-menu-summary.json')
  await writeFile(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, 'utf8')
  console.log(`frontend role menu verification passed; artifacts=${artifactDir}`)
  console.log(`summary=${summaryPath}`)
  for (const path of summary.screenshots) console.log(`screenshot=${path}`)
} finally {
  await browser.close()
}
