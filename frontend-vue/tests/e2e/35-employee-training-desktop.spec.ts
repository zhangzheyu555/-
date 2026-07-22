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

type EmployeeRow = {
  id: string
  storeId: string
  storeName: string
  name: string
  phone?: string
  position: string
  employmentType: string
  baseSalary: number
  status: string
  hireDate: string
  idCardNo?: string
  accountUsername?: string
  accountEnabled?: boolean
}

type Session = Record<string, unknown>

interface State {
  employees: EmployeeRow[]
  submittedExamBodies: Array<Record<string, unknown>>
  consoleErrors: string[]
}

const stores = [{ id: 'EMP_A', code: 'EMP-A', name: '合成员工门店', brandId: 1, brandName: '合成品牌', status: '营业中' }]

function session(role: string, permissions: string[], id: number, defaultWorkspace: string): Session {
  return {
    id,
    tenantId: 1,
    tenantName: 'H2 合成员工租户',
    displayName: role === 'EMPLOYEE' ? '本人员工' : role === 'SUPERVISOR' ? '合成督导' : '合成店长',
    role,
    roleLabel: role,
    storeScope: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? ['EMP_A'] : ['all'],
    boundStoreId: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? 'EMP_A' : null,
    boundStoreName: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? '合成员工门店' : null,
    permissions,
    dataScope: { mode: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? 'OWN_STORE' : 'ALL', storeIds: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? ['EMP_A'] : [], warehouseIds: [] },
    dataScopes: {
      STORE: { mode: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? 'OWN_STORE' : 'ALL', storeIds: role === 'STORE_MANAGER' || role === 'EMPLOYEE' ? ['EMP_A'] : [], warehouseIds: [] },
      EXAM: { mode: role === 'EMPLOYEE' ? 'SELF' : role === 'STORE_MANAGER' ? 'OWN_STORE' : 'ALL', storeIds: role === 'STORE_MANAGER' ? ['EMP_A'] : [], warehouseIds: [] },
    },
    defaultWorkspace,
    permissionVersion: 1,
  }
}

function newState(): State {
  return {
    employees: [{
      id: 'emp-existing', storeId: 'EMP_A', storeName: '合成员工门店', name: '已有员工', phone: '13800138000',
      position: '店员', employmentType: '全职', baseSalary: 3500, status: '在职', hireDate: '2026-07-01', idCardNo: 'EMP-ID-CARD-SECRET',
    }],
    submittedExamBodies: [],
    consoleErrors: [],
  }
}

async function prepare(page: Page, state: State, user: Session) {
  page.on('console', (message) => {
    if (message.type() === 'error') state.consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => state.consoleErrors.push(error.message))
  await page.addInitScript((sessionData) => {
    localStorage.setItem('ai_profit_vue_token', 'EMP-ISOLATED-CHROMIUM')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(sessionData))
  }, user)
  await page.route(/^https?:\/\/[^/]+\/api\//, (route) => fulfillApi(route, state, user))
}

async function fulfillApi(route: Route, state: State, user: Session) {
  const request = route.request()
  const path = new URL(request.url()).pathname
  const role = String(user.role)
  if (path === '/api/auth/me') return route.fulfill(ok(user))
  if (path === '/api/stores') return route.fulfill(ok(stores))
  if (path === '/api/employees' && request.method() === 'GET') return route.fulfill(ok(state.employees))
  if (path === '/api/employees' && request.method() === 'POST') {
    const body = request.postDataJSON() as Record<string, unknown>
    if (state.employees.some((item) => item.storeId === body.storeId && item.name === body.name)) {
      return route.fulfill(businessError(409, 'DUPLICATE', `该门店已有同名员工：${String(body.name)}`))
    }
    state.employees.push({
      id: `emp-${state.employees.length + 1}`,
      storeId: String(body.storeId), storeName: '合成员工门店', name: String(body.name), phone: String(body.phone || ''),
      position: String(body.position || ''), employmentType: String(body.employmentType || '全职'),
      baseSalary: 0, status: String(body.status || '在职'), hireDate: String(body.hireDate || ''),
    })
    return route.fulfill(ok(state.employees.at(-1)))
  }
  if (path.startsWith('/api/employees/') && request.method() === 'PUT') {
    const body = request.postDataJSON() as Record<string, unknown>
    const id = path.split('/').at(-1)!
    const row = state.employees.find((item) => item.id === id)
    if (row) Object.assign(row, body)
    return route.fulfill(ok(row))
  }
  if (path.startsWith('/api/employees/') && path.endsWith('/account') && request.method() === 'POST') {
    const id = path.split('/').at(-2)!
    const row = state.employees.find((item) => item.id === id)!
    row.accountUsername = `emp-manager-${state.employees.indexOf(row) + 1}`
    row.accountEnabled = true
    // Test-only ephemeral fixture; it is neither a runtime default nor a real credential.
    const syntheticInitialPassword = `TestOnly!${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
    return route.fulfill(ok({ employeeId: id, employeeName: row.name, username: row.accountUsername, initialPassword: syntheticInitialPassword }))
  }
  if (path.startsWith('/api/employees/') && request.method() === 'DELETE') {
    const row = state.employees.find((item) => item.id === path.split('/').at(-1))!
    row.status = '离职'
    if (row.accountUsername) row.accountEnabled = false
    return route.fulfill(ok(null))
  }
  if (path === '/api/employee/workbench') return route.fulfill(ok({
    profile: { userId: 42, displayName: '本人员工', role: 'EMPLOYEE' },
    store: { storeId: 'EMP_A', storeName: '合成员工门店', brandName: '合成品牌' },
    workSummary: { total: 1, pending: 1, overdue: 0, completed: 0, retakePending: 0 },
    workItems: [{ id: 'assignment-1', type: 'EXAM', title: '食品安全考试', description: '待参加', status: 'ASSIGNED', priority: 'NORMAL', actionText: '去考试', route: '/employee/exams' }],
    assistant: { enabled: false, state: 'UNCONFIGURED', message: '员工服务助手未配置', route: '' },
  }))
  if (path === '/api/employee/profile') return route.fulfill(ok({
    profile: { userId: 42, displayName: '本人员工', username: 'emp-42', role: 'EMPLOYEE' },
    store: { storeId: 'EMP_A', storeName: '合成员工门店', brandName: '合成品牌' },
    archive: { linked: true, employeeId: 'emp-self', name: '本人员工', position: '店员', employmentType: '全职', status: '在职', hireDate: '2026-07-01', baseSalary: 3500, message: '已关联员工档案' },
    salary: { available: true, month: '2026-07', status: 'PAID', statusLabel: '已发放', base: 3500, gross: 3800, netPay: 3600, commission: 200, overtime: 100, performance: 0, deductUniform: 0, returnUniform: 0, vacationLeft: 2, message: '工资已发放' },
    checklist: [{ key: 'exam', title: '培训考试', description: '请完成食品安全考试。', state: '待处理', severity: 'NORMAL' }],
  }))
  if (path === '/api/exam-center/overview') return route.fulfill(ok(examOverview(role, state)))
  if (path === '/api/exam-center/courses') return route.fulfill(ok([{ id: 1, courseCode: 'SAFE', title: '食品安全培训', category: '食品安全', durationMinutes: 30, enabled: true, sortOrder: 1, materialCount: 1, materialIds: [1] }]))
  if (path === '/api/exam-center/materials') return route.fulfill(ok([{ id: 1, materialCode: 'SAFE-1', title: '食品安全手册', category: '食品安全', imageUrls: [], content: '合成培训资料', enabled: true, sortOrder: 1, learnedCount: 1 }]))
  if (path === '/api/exam-center/videos') return route.fulfill(ok([]))
  if (path === '/api/exam-center/results') return route.fulfill(ok([]))
  if (path === '/api/exam-center/wrong-questions') return route.fulfill(ok([]))
  if (path === '/api/exam-center/question-categories') return route.fulfill(ok([]))
  if (path === '/api/exam-center/questions') return route.fulfill(ok([]))
  if (path === '/api/exam-center/reviews') return route.fulfill(ok([]))
  if (path === '/api/exam-center/videos/progress-report') return route.fulfill(ok([]))
  if (path === '/api/exam-center/assignments/501/paper') return route.fulfill(ok({
    id: 11, paperCode: 'SAFE-EXAM', paperName: '食品安全试卷', passScore: 60, enabled: true,
    questions: [{ id: 1001, questionType: 'SINGLE_CHOICE', questionText: '正确处理方式是什么？', options: ['立即上报', '继续使用'], score: 100, sortOrder: 1 }],
  }))
  if (path === '/api/exam-center/assignments/501/submit' && request.method() === 'POST') {
    state.submittedExamBodies.push(request.postDataJSON() as Record<string, unknown>)
    return route.fulfill(ok({ id: 9001, paperId: 11, paperName: '食品安全试卷', examineeName: '本人员工', examineeRole: 'EMPLOYEE', score: 100, passed: true, violated: false, submittedAt: '2026-07-21 10:00:00' }))
  }
  return route.fulfill(ok([]))
}

function examOverview(role: string, state: State) {
  const submitted = state.submittedExamBodies.length > 0
  const employeeAssignment = {
    id: 501, campaignId: 301, paperId: 11, examTitle: '食品安全考试', paperName: '食品安全试卷',
    userId: 42, examineeName: '本人员工', examineeRole: 'EMPLOYEE', storeId: 'EMP_A', storeName: '合成员工门店',
    status: submitted ? 'COMPLETED' : 'ASSIGNED', statusLabel: submitted ? '已完成' : '待参加',
    startAt: '2026-07-20 09:00:00', dueAt: '2026-07-30 18:00:00', ...(submitted ? { score: 100, passed: true } : {}),
  }
  const canManage = role === 'SUPERVISOR'
  return {
    accessMode: canManage ? 'COMPANY' : role === 'STORE_MANAGER' ? 'STORE' : 'SELF',
    canManage, canExport: canManage,
    papers: canManage ? [{ id: 11, paperCode: 'SAFE-EXAM', paperName: '食品安全试卷', passScore: 60, enabled: true, questionCount: 1 }] : [],
    campaigns: [], assignments: [employeeAssignment],
    candidates: [{ userId: 42, displayName: '本人员工', role: 'EMPLOYEE', roleLabel: '员工', storeId: 'EMP_A', storeName: '合成员工门店' }],
  }
}

test('store manager completes employee archive create, account, offboarding and failure feedback at 1280px', async ({ page }) => {
  const state = newState()
  await prepare(page, state, session('STORE_MANAGER', ['employee.read', 'employee.manage'], 21, '/store'))
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/staff')

  await page.getByRole('button', { name: '新增员工' }).click()
  const dialog = page.locator('.modal-box')
  await dialog.getByLabel(/门店/).selectOption('EMP_A')
  await dialog.getByLabel(/姓名/).fill('新员工')
  await dialog.getByLabel(/职位/).fill('店员')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByRole('cell', { name: '新员工' })).toBeVisible()

  const row = page.locator('tr', { hasText: '新员工' })
  await row.getByRole('button', { name: '开号', exact: true }).click()
  await expect(page.getByText('账号已创建（初始密码只显示这一次）')).toBeVisible()
  await page.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByText('账号已创建（初始密码只显示这一次）')).toHaveCount(0)

  page.once('dialog', (confirmation) => confirmation.accept())
  await row.getByRole('button', { name: '离职', exact: true }).click()
  await expect.poll(() => state.employees.find((item) => item.name === '新员工')?.status).toBe('离职')

  await page.getByRole('button', { name: '新增员工' }).click()
  await dialog.getByLabel(/门店/).selectOption('EMP_A')
  await dialog.getByLabel(/姓名/).fill('新员工')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('该门店已有同名员工：新员工')).toBeVisible()
  expect(state.employees.filter((item) => item.name === '新员工')).toHaveLength(1)

  await expectNoWholePageOverflow(page, '1280px 员工档案管理')
  expect(state.consoleErrors.filter((message) => !message.includes('DUPLICATE') && !message.includes('409'))).toEqual([])
})

test('employee sees only own profile and completes assigned training exam at 1280px', async ({ page }) => {
  const state = newState()
  await prepare(page, state, session('EMPLOYEE', ['exam.learn'], 42, '/employee'))
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.goto('/employee')
  await expect(page.getByText('我的资料与工资')).toBeVisible()
  await expect(page.getByText('食品安全考试')).toBeVisible()

  await page.goto('/employee/profile')
  await expect(page.getByText('工资信息')).toBeVisible()
  await expect(page.locator('body')).not.toContainText('EMP-ID-CARD-SECRET')

  await page.goto('/employee/exams')
  await expect(page.getByRole('button', { name: '开始考试' })).toBeVisible()
  await page.getByRole('button', { name: '开始考试' }).click()
  const examOverlay = page.locator('body > .overlay.exam-taking')
  const immediateReport = page.getByRole('radio', { name: '立即上报' })
  await expect(examOverlay).toBeVisible()
  await expect(immediateReport).toBeVisible()
  expect(await immediateReport.evaluate((radio) => {
    const rect = radio.getBoundingClientRect()
    const topElement = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2)
    return !topElement?.closest('nav.sidebar-navigation')
  })).toBe(true)
  await immediateReport.check()
  await page.getByRole('button', { name: '提交考试' }).click()
  await expect(page.getByText('考试已提交，得分 100 分。')).toBeVisible()
  expect(state.submittedExamBodies).toEqual([{ violated: false, answers: [{ questionId: 1001, userAnswer: '立即上报' }] }])

  await expectNoWholePageOverflow(page, '1280px 员工培训考试')
  expect(state.consoleErrors).toEqual([])
})

test('employee and store manager cannot reach supervisor training administration even with stale permissions', async ({ browser }) => {
  for (const role of ['EMPLOYEE', 'STORE_MANAGER']) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const page = await context.newPage()
    const state = newState()
    await prepare(page, state, session(role, ['exam.learn', 'exam.manage', 'exam.report'], role === 'EMPLOYEE' ? 42 : 21, role === 'EMPLOYEE' ? '/employee' : '/store'))
    await page.goto('/operations/exams')
    await expect(page).toHaveURL(/\/no-permission\?from=\/operations\/exams/)
    await expect(page.getByText('当前账号没有访问该页面的权限')).toBeVisible()
    expect(state.consoleErrors).toEqual([])
    await context.close()
  }
})
