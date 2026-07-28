import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const bossSession = {
  id: 1,
  tenantId: 1,
  tenantName: '测试租户',
  displayName: '测试老板',
  role: 'BOSS',
  roleLabel: '老板（系统管理员）',
  storeScope: ['all'],
  permissions: ['salary.read', 'salary.edit', 'finance.profit.read'],
  dataScopes: {
    STORE: { mode: 'ALL', storeIds: [] },
    FINANCE: { mode: 'ALL', storeIds: [] },
    SALARY: { mode: 'ALL', storeIds: [] },
  },
  dataScope: { mode: 'ALL', storeIds: [] },
  boundStoreId: null,
  boundStoreName: null,
  brandId: null,
  brandName: null,
  defaultWorkspace: '/boss',
  permissionVersion: 1,
}

const stores = [
  { id: 'xls12', code: 'XLS-12', name: '荆江之星', brandId: 1, brandName: '茹菓', status: 'ACTIVE' },
  { id: 'rg1', code: 'RG001', name: '保利店', brandId: 1, brandName: '茹菓', status: '停用' },
  { id: 'bw1', code: 'BW-01', name: '霸王中心店', brandId: 2, brandName: '霸王茶姬', status: '正常' },
]

const salaryRecord = {
  id: 'salary-1',
  storeId: 'xls12',
  storeName: '荆江之星',
  brandId: 1,
  brandName: '茹菓',
  month: '2026-07',
  employeeId: 'EMP-001',
  employeeName: '李店员',
  position: '店员',
  attendance: '26天',
  base: 3_000,
  social: 0,
  post: 0,
  meal: 0,
  fullAttendance: 0,
  commission: 300,
  overtime: 0,
  seniority: 100,
  birthdayBenefit: 200,
  lateNight: 0,
  subsidy: 0,
  performance: 0,
  deductUniform: 0,
  returnUniform: 0,
  gross: 3_600,
  normalHours: 208,
  otHours: 0,
  workHours: 208,
  vacationLeft: 2,
  vacationNote: '7月1日休息',
  status: 'DRAFT',
}

const assignmentCandidate = {
  employeeId: 'EMP-TRANSFER',
  employeeName: '张调店',
  position: '咖啡师',
  sourceStoreId: 'bw1',
  sourceStoreName: '霸王中心店',
}

const salaryBusinessMetrics = {
  revenue: 128_432.67,
  effectiveHours: 288,
  hourlyRevenue: 445,
  perCapitaOutput: 92_560,
  commissionPool: 1_000.5,
  commissionTotal: 300,
  storeFund: 700.5,
}

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, data }),
})

interface CapturedRequests {
  candidateQuery?: URLSearchParams
  businessMetricsQuery?: URLSearchParams
  businessMetricsRequests?: number
  employeePageRequests?: number
  employeePageQueries?: URLSearchParams[]
  previewPayloads?: Record<string, unknown>[]
  generationPayload?: Record<string, unknown>
  assignment?: Record<string, unknown>
  employeeCreate?: Record<string, unknown>
  attendance?: Record<string, unknown>
  salaryUpdate?: Record<string, unknown>
  deletedSalaryId?: string
}

function salaryPage(records = [salaryRecord], workHoursTotal = records.reduce((sum, row) => sum + Number(row.workHours || 0), 0)) {
  return {
    content: records,
    total: records.length,
    totalElements: records.length,
    totalPages: 1,
    page: 1,
    size: 20,
    statusCounts: { DRAFT: 1 },
    summary: {
      month: '2026-07',
      storeCount: 1,
      recordCount: 1,
      grossTotal: records.reduce((sum, row) => sum + Number(row.gross || 0), 0),
      baseTotal: 3_000,
      commissionTotal: records.reduce((sum, row) => sum + Number(row.commission || 0), 0),
      overtimeTotal: 0,
    },
    workHoursTotal,
    vacationBalanceTotal: 2,
  }
}

async function prepare(
  page: Page,
  captured: CapturedRequests,
  initialRecord = salaryRecord,
  metrics = salaryBusinessMetrics,
  workHoursTotal?: number,
  additionalRecords: typeof salaryRecord[] = [],
  generationCandidates?: Array<{
    employeeId: string
    employeeName: string
    position?: string
    storeId?: string
    storeName?: string
  }>,
) {
  let assignedRecord: typeof salaryRecord | undefined
  let newlyCreatedEmployeeRecord: typeof salaryRecord | undefined
  const deletedRecordIds = new Set<string>()
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_token', 'SALARY-ASSIGNMENT-TEST-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, bossSession)

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route: Route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/auth/me') return route.fulfill(ok(bossSession))
    if (path === '/api/stores') return route.fulfill(ok(stores))
    if (path === '/api/salaries/employee-page') {
      captured.employeePageRequests = (captured.employeePageRequests || 0) + 1
      captured.employeePageQueries = [...(captured.employeePageQueries || []), new URLSearchParams(url.search)]
      const records = [
        initialRecord,
        ...additionalRecords,
        ...(assignedRecord ? [assignedRecord] : []),
        ...(newlyCreatedEmployeeRecord ? [newlyCreatedEmployeeRecord] : []),
      ]
        .filter((record) => !deletedRecordIds.has(record.id))
      return route.fulfill(ok(salaryPage(
        records,
        workHoursTotal,
      )))
    }
    if (path === '/api/salaries/preview' && request.method() === 'POST') {
      const payload = request.postDataJSON() as Record<string, unknown>
      captured.previewPayloads = [...(captured.previewPayloads || []), payload]
      const candidates = generationCandidates || [initialRecord, ...additionalRecords]
        .filter((record) => record.status === 'PENDING_GENERATION' && Boolean(record.employeeId))
        .map((record) => ({
          employeeId: record.employeeId,
          employeeName: record.employeeName,
          position: record.position,
        }))
      const requestedIds = Array.isArray(payload.employeeIds)
        ? payload.employeeIds.map(String)
        : candidates.map((candidate) => candidate.employeeId)
      return route.fulfill(ok({
        generated: requestedIds.length,
        skipped: 0,
        errors: 0,
        skipDetails: [],
        candidates,
      }))
    }
    if (path === '/api/salaries/generate-report' && request.method() === 'POST') {
      captured.generationPayload = request.postDataJSON() as Record<string, unknown>
      const employeeIds = Array.isArray(captured.generationPayload.employeeIds)
        ? captured.generationPayload.employeeIds
        : []
      return route.fulfill(ok({
        generated: employeeIds.length,
        skipped: 0,
        errors: 0,
        skipDetails: [],
        candidates: generationCandidates || [],
      }))
    }
    if (path === '/api/salaries/business-metrics') {
      captured.businessMetricsQuery = new URLSearchParams(url.search)
      captured.businessMetricsRequests = (captured.businessMetricsRequests || 0) + 1
      return route.fulfill(ok(metrics))
    }
    if (path === '/api/salaries/assignment-candidates') {
      captured.candidateQuery = new URLSearchParams(url.search)
      return route.fulfill(ok([assignmentCandidate]))
    }
    if (path === '/api/salaries/assign-employee' && request.method() === 'POST') {
      captured.assignment = request.postDataJSON() as Record<string, unknown>
      assignedRecord = {
        ...initialRecord,
        id: 'SALADD-202607-transfer',
        employeeId: assignmentCandidate.employeeId,
        employeeName: assignmentCandidate.employeeName,
        position: assignmentCandidate.position,
      }
      return route.fulfill(ok(assignedRecord))
    }
    if (path === '/api/employees' && request.method() === 'POST') {
      captured.employeeCreate = request.postDataJSON() as Record<string, unknown>
      newlyCreatedEmployeeRecord = {
        ...initialRecord,
        id: '',
        storeId: String(captured.employeeCreate.storeId || ''),
        storeName: '荆江之星',
        employeeId: 'EMP-NEW',
        employeeName: String(captured.employeeCreate.name || ''),
        position: String(captured.employeeCreate.position || ''),
        attendance: '',
        gross: 0,
        base: 0,
        commission: 0,
        seniority: 0,
        birthdayBenefit: 0,
        workHours: 0,
        normalHours: 0,
        otHours: 0,
        vacationLeft: 0,
        status: 'PENDING_GENERATION',
      }
      return route.fulfill(ok({
        id: 'EMP-NEW',
        ...captured.employeeCreate,
        storeName: '荆江之星',
      }))
    }
    if (path === '/api/salaries/attendance' && request.method() === 'PUT') {
      captured.attendance = request.postDataJSON() as Record<string, unknown>
      return route.fulfill(ok({ attendanceDays: 26, normalHours: 208, overtimeHours: 0, totalHours: 208 }))
    }
    if (/^\/api\/salaries\/[^/]+$/.test(path) && request.method() === 'DELETE') {
      const id = decodeURIComponent(path.slice('/api/salaries/'.length))
      captured.deletedSalaryId = id
      deletedRecordIds.add(id)
      return route.fulfill(ok(null))
    }
    if (/^\/api\/salaries\/[^/]+$/.test(path) && request.method() === 'PUT') {
      captured.salaryUpdate = request.postDataJSON() as Record<string, unknown>
      return route.fulfill(ok({ ...(assignedRecord || initialRecord), ...captured.salaryUpdate }))
    }

    return route.fulfill(ok([]))
  })
}

test('跨店添加人员不改岗位，草稿工资可调整工龄、生日福利、深夜加班和假期', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const storeSelect = page.getByRole('combobox', { name: '门店', exact: true })
  await expect(storeSelect.getByRole('option', { name: '保利店' })).toHaveCount(0)

  const addButton = page.getByRole('button', { name: '添加人员', exact: true })
  await expect(addButton).toBeEnabled()
  await addButton.click()

  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('tab', { name: '选择已有人员' }).click()
  await expect(dialog).toContainText('不会修改其档案归属和岗位')
  await expect(dialog).toContainText(assignmentCandidate.employeeName)
  await expect(dialog).toContainText(assignmentCandidate.sourceStoreName)
  await expect(dialog).toContainText(assignmentCandidate.position)
  expect(captured.candidateQuery?.get('storeId')).toBe('xls12')
  expect(captured.candidateQuery?.get('month')).toBe('2026-07')

  await dialog.getByRole('radio').check()
  await dialog.getByRole('button', { name: '添加到工资名单' }).click()

  await expect.poll(() => captured.assignment).toEqual({
    storeId: 'xls12',
    month: '2026-07',
    employeeId: assignmentCandidate.employeeId,
  })
  expect(captured.assignment).not.toHaveProperty('position')
  await expect(page.getByText(/已将 张调店 添加到当月工资名单/)).toBeVisible()

  await page.getByLabel('工龄工资（元）').fill('200')
  await page.getByLabel('员工福利（生日）').fill('200')
  await page.getByLabel('绩效奖罚').fill('50')
  await page.getByLabel('深夜加班（元）').fill('30')
  await page.getByLabel('其他补贴').fill('20')
  await page.getByLabel('其他扣款').fill('10')
  await page.getByLabel('最终提成金额').fill('400')
  await page.getByLabel('假期余额（天）').fill('4.5')
  await page.getByLabel('休息日期备注').fill('7月5日、12日休息')

  const result = page.locator('.result-section')
  await expect(result).toContainText('工龄工资')
  await expect(result).toContainText('员工福利（生日）')
  await expect(result).toContainText('深夜加班')
  await expect(result).toContainText('固定补助')
  await expect(result).toContainText('人工补助/奖罚')
  await expect(result).toContainText('¥3,890')

  await page.getByText('查看计算过程').click()
  await expect(page.getByText(/工龄工资仍按满半年100元/)).toBeVisible()
  await expect(page.getByText(/生日所在工资月份享受200元生日福利/)).toBeVisible()

  await page.getByRole('button', { name: '保存工资与假期' }).click()

  await expect.poll(() => captured.salaryUpdate).toMatchObject({
    storeId: 'xls12',
    month: '2026-07',
    employeeId: assignmentCandidate.employeeId,
    seniority: 200,
    birthdayBenefit: 200,
    performance: 50,
    lateNight: 30,
    subsidy: 20,
    deductUniform: 10,
    commission: 400,
    gross: 3_890,
    vacationLeft: 4.5,
    vacationNote: '7月5日、12日休息',
  })
  expect(captured.attendance).toBeUndefined()
})

test('添加人员可直接新建正式员工并自动加入当前门店工资名单', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByRole('button', { name: '添加人员', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await expect(dialog.getByRole('tab', { name: '新建人员' })).toHaveAttribute('aria-selected', 'true')
  await expect(dialog).toContainText('新人员将建立正式员工档案')
  await expect(dialog).toContainText('所属门店：荆江之星；状态：在职')

  await dialog.getByLabel('员工姓名').fill('王新员工')
  await dialog.getByLabel('岗位').selectOption('营业员')
  await dialog.getByLabel('用工类型').selectOption('全职')
  await dialog.getByLabel('手机号码（选填）').fill('13800138000')
  await dialog.getByLabel('入职日期（选填）').fill('2026-07-27')
  await dialog.getByRole('button', { name: '新建并加入名单' }).click()

  await expect.poll(() => captured.employeeCreate).toEqual({
    storeId: 'xls12',
    name: '王新员工',
    phone: '13800138000',
    position: '营业员',
    employmentType: '全职',
    status: '在职',
    hireDate: '2026-07-27',
    hourlyRate: null,
  })
  expect(captured.assignment).toBeUndefined()
  await expect(dialog).toBeHidden()
  await expect(page.getByText('已新建 王新员工 的员工档案，并加入 2026-07 工资名单')).toBeVisible()
  const newEmployeeRow = page.getByRole('row').filter({ hasText: '王新员工' })
  await expect(newEmployeeRow).toBeVisible()
  await expect(newEmployeeRow).toContainText('待生成')
})

test('新建人员校验错误在弹窗内显示，关闭时保护未保存内容', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByRole('button', { name: '添加人员', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await dialog.getByLabel('员工姓名').fill('待完善员工')
  await dialog.getByLabel('手机号码（选填）').fill('138')
  await dialog.getByRole('button', { name: '新建并加入名单' }).click()

  await expect(dialog.getByText('请选择岗位，工资计算会使用该岗位。')).toBeVisible()
  await expect(dialog.getByText('手机号码必须是11位数字。')).toBeVisible()
  expect(captured.employeeCreate).toBeUndefined()

  await dialog.getByRole('button', { name: '取消' }).click()
  const unsaved = page.getByRole('alertdialog', { name: '尚未保存新人员' })
  await expect(unsaved).toBeVisible()
  await unsaved.getByRole('button', { name: '继续填写', exact: true }).click()
  await expect(dialog.getByLabel('员工姓名')).toHaveValue('待完善员工')
  await dialog.getByRole('button', { name: '取消' }).click()
  await unsaved.getByRole('button', { name: '放弃并关闭' }).click()
  await expect(dialog).toBeHidden()
})

test('新建人员接口报错保留已填内容并允许直接修改重试', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  let createAttempts = 0
  await page.route(/\/api\/employees$/, async (route) => {
    createAttempts += 1
    return route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, code: 'DUPLICATE', message: '该门店已有同名员工：王新员工' }),
    })
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByRole('button', { name: '添加人员', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await dialog.getByLabel('员工姓名').fill('王新员工')
  await dialog.getByLabel('岗位').selectOption('营业员')
  await dialog.getByRole('button', { name: '新建并加入名单' }).click()

  await expect(dialog.getByRole('alert')).toContainText('该门店已有同名员工：王新员工')
  await expect(dialog.getByLabel('员工姓名')).toHaveValue('王新员工')
  await expect(dialog.getByLabel('岗位')).toHaveValue('营业员')
  await expect(dialog.getByRole('button', { name: '新建并加入名单' })).toBeEnabled()
  expect(createAttempts).toBe(1)

  await dialog.getByLabel('员工姓名').fill('王新员工二')
  await expect(dialog.getByRole('alert')).toHaveCount(0)
})

test('新建人员弹窗在390px小屏无横向溢出且底部操作始终可达', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByRole('button', { name: '添加人员', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('员工姓名')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '新建并加入名单' })).toBeVisible()
  expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
  await expectNoWholePageOverflow(page)
})

test('仅有工资编辑权限时仍可选已有人员，但不能越权新建员工档案', async ({ page }) => {
  const captured: CapturedRequests = {}
  const financeSession = {
    ...bossSession,
    role: 'FINANCE',
    roleLabel: '财务',
    permissions: ['salary.read', 'salary.edit', 'finance.profit.read'],
  }
  await prepare(page, captured)
  await page.addInitScript((user) => {
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(user))
  }, financeSession)
  await page.route(/\/api\/auth\/me$/, (route) => route.fulfill(ok(financeSession)))
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByRole('button', { name: '添加人员', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await expect(dialog.getByRole('tab')).toHaveCount(0)
  await expect(dialog.getByPlaceholder('搜索姓名、原门店或岗位')).toBeVisible()
  await expect(dialog.getByText('张调店', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '添加到工资名单' })).toBeDisabled()
})

test('深夜加班手填金额在保底之外相加，保底补足不会丢失', async ({ page }) => {
  const captured: CapturedRequests = {}
  const guaranteedRecord = {
    ...salaryRecord,
    id: 'salary-guarantee',
    employeeName: '王保底',
    base: 1_900,
    commission: 0,
    seniority: 0,
    birthdayBenefit: 0,
    gross: 3_000,
  }
  await prepare(page, captured, guaranteedRecord)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const result = page.locator('.result-section')
  await expect(result).toContainText('保底补足/历史差额')
  await expect(result).toContainText('¥3,000')

  await page.getByLabel('工龄工资（元）').fill('0')
  await page.getByLabel('深夜加班（元）').fill('200')

  await expect(page.locator('.night-overtime-preview')).toContainText('不含深夜加班 ¥3,000')
  await expect(page.locator('.night-overtime-preview')).toContainText('＋ 深夜加班 ¥200')
  await expect(page.locator('.night-overtime-preview')).toContainText('＝ ¥3,200')
  await expect(result).toContainText('¥3,200')

  await page.getByText('查看计算过程').click()
  await expect(page.getByText(/保底3000元、深夜加班200元，合计就是3200元/)).toBeVisible()

  await page.getByRole('button', { name: '保存工资与假期' }).click()
  await expect.poll(() => captured.salaryUpdate).toMatchObject({
    lateNight: 200,
    seniority: 0,
    gross: 3_200,
  })
})

test('荆江之星按正常与加班总工时核算人效，只读数值直接截掉小数', async ({ page }) => {
  const captured: CapturedRequests = {}
  const fractionalRecord = {
    ...salaryRecord,
    gross: 3_600.99,
    commission: 300.99,
    normalHours: 208.25,
    otHours: 0.5,
    workHours: 208.75,
  }
  await prepare(page, captured, fractionalRecord, {
    ...salaryBusinessMetrics,
    effectiveHours: 1_069.75,
    hourlyRevenue: 120.99,
    perCapitaOutput: 24_960.99,
  }, 1_069.75)
  await page.goto('/finance/salary?storeId=xls12&brandId=1&month=2026-07')

  await expect.poll(() => captured.businessMetricsQuery?.get('month')).toBe('2026-07')
  expect(captured.businessMetricsQuery?.get('storeId')).toBe('xls12')
  // 门店已能唯一确定品牌，不再把路由上的 brandId 强行带入工资查询。
  expect(captured.businessMetricsQuery?.get('brandId')).toBeNull()

  const metrics = page.locator('.business-metrics')
  const revenueCard = metrics.locator('article').filter({ hasText: '当月营业额' })
  await expect(revenueCard.locator('b')).toHaveText('¥128,432')
  await expect(revenueCard).toContainText('直接去除小数，不四舍五入')

  const hourlyCard = metrics.getByText('每小时营业额', { exact: true }).locator('..')
  await expect(hourlyCard.locator('b')).toHaveText('¥120')
  await expect(hourlyCard).toContainText('正常工时＋加班工时，兼职/实习折半（1069小时）')

  const outputCard = metrics.locator('article').filter({ hasText: '人均月产值' })
  await expect(outputCard.locator('b')).toHaveText('¥24,960')
  await expect(outputCard).toContainText('每小时营业额 × 26天 × 8小时')

  const fundCard = metrics.locator('article').filter({ hasText: '店铺基金' })
  await expect(fundCard.locator('b')).toHaveText('¥700')
  await expect(fundCard).toContainText('提成总池 − 当前实发提成')

  const detailRevenue = page.locator('.commission-box div').filter({ hasText: '营业额' })
  await expect(detailRevenue.locator('b')).toHaveText('¥128,432')

  const summary = page.locator('.salary-summary-strip')
  await expect(summary.locator('div').filter({ hasText: '应发合计' }).locator('b')).toHaveText('¥3,600')
  await expect(summary.locator('div').filter({ hasText: '总工时' }).locator('b')).toHaveText('1069')

  const salaryRow = page.locator('.salary-table-section tbody tr').first()
  await expect(salaryRow.locator('td').nth(5)).toHaveText('¥3,600')
  await expect(salaryRow.locator('td').nth(6)).toHaveText('¥300')
  await expect(salaryRow.locator('td').nth(7)).toHaveText('208')

  const hours = page.locator('.hour-summary')
  await expect(hours).toContainText('正常工时208 小时')
  await expect(hours).toContainText('总工时208 小时')
  await expect(hours).toContainText('产值工时208 小时')
  await expect(page.locator('.formula-note')).toContainText('正常工时与加班工时都计入月产值')
})

test('实习员工按小时显示整数，仅修改备注不会丢失原工时和金额精度', async ({ page }) => {
  const captured: CapturedRequests = {}
  const hourlyRecord = {
    ...salaryRecord,
    employeeId: 'EMP-INTERN',
    employeeName: '实习员工',
    position: '营业员',
    employmentType: '实习',
    attendance: '218.4',
    base: 899.9,
    commission: 300.9,
    performance: 18.9,
    gross: 4_019.7,
    normalHours: 218.4,
    otHours: 4.9,
    workHours: 223.3,
    vacationLeft: 2.5,
  }
  await prepare(page, captured, hourlyRecord, salaryBusinessMetrics, 223.3)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const salaryRow = page.locator('.salary-table-section tbody tr').first()
  await expect(salaryRow.locator('td').nth(4)).toHaveText('218小时')
  await expect(salaryRow.locator('td').nth(5)).toHaveText('¥4,019')
  await expect(salaryRow.locator('td').nth(7)).toHaveText('223')

  await expect(page.getByLabel('正常工时')).toHaveValue('218')
  await expect(page.getByLabel('加班小时')).toHaveValue('4')
  await expect(page.getByLabel('绩效奖罚')).toHaveValue('18')
  await expect(page.getByLabel('最终提成金额')).toHaveValue('300')
  await expect(page.getByText('出勤天数应在0—31天之间')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '保存工资与假期' })).toBeEnabled()

  await page.getByLabel('休息日期备注').fill('7月5日休息')
  await expect(page.getByRole('button', { name: '保存工资与假期' })).toBeEnabled()
  await page.getByRole('button', { name: '保存工资与假期' }).click()

  expect(captured.attendance).toBeUndefined()
  await expect.poll(() => captured.salaryUpdate).toMatchObject({
    base: 899.9,
    commission: 300.9,
    performance: 18.9,
    normalHours: 218.4,
    otHours: 4.9,
    workHours: 223.3,
    gross: 4_019.7,
    vacationLeft: 2.5,
    vacationNote: '7月5日休息',
  })
})

test('全职员工只改假期时保留原始小数工时，不重新按天数乘8', async ({ page }) => {
  const captured: CapturedRequests = {}
  const fractionalFullTimeRecord = {
    ...salaryRecord,
    attendance: '26天',
    normalHours: 208.25,
    otHours: 0.5,
    workHours: 208.75,
  }
  await prepare(page, captured, fractionalFullTimeRecord)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByLabel('休息日期备注').fill('7月20日休息')
  await page.getByRole('button', { name: '保存工资与假期' }).click()

  expect(captured.attendance).toBeUndefined()
  await expect.poll(() => captured.salaryUpdate).toMatchObject({
    attendance: '26天',
    normalHours: 208.25,
    otHours: 0.5,
    workHours: 208.75,
    vacationNote: '7月20日休息',
  })
})

test('停用门店不能通过工资页面路由继续查询或编辑', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=rg1&month=2026-07')

  await expect(page.getByText('该门店已停用，不能继续查看、添加人员或生成工资。')).toBeVisible()
  await expect(page.getByRole('button', { name: '添加人员', exact: true })).toBeDisabled()
  expect(captured.employeePageRequests || 0).toBe(0)
})

test('其他门店工资表也统一截掉营业额小数', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=bw1&brandId=2&month=2026-07')

  const metrics = page.locator('.business-metrics')
  const revenueCard = metrics.locator('article').filter({ hasText: '当月营业额' })
  await expect(revenueCard.locator('b')).toHaveText('¥128,432')
  await expect(revenueCard).toContainText('直接去除小数，不四舍五入')

  const detailRevenue = page.locator('.commission-box div').filter({ hasText: '营业额' })
  await expect(detailRevenue.locator('b')).toHaveText('¥128,432')
})

test('跨店添加的草稿员工可从本月工资表移出，且不删员工档案', async ({ page }) => {
  const captured: CapturedRequests = {}
  const transferredRecord = {
    ...salaryRecord,
    id: 'SALADD-202607-transfer',
    employeeId: 'EMP-TRANSFER',
    employeeName: '张调店',
    position: '咖啡师',
  }
  await prepare(page, captured, transferredRecord)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const removeButton = page.getByRole('button', { name: '将张调店移出本月工资表' })
  await expect(removeButton).toBeVisible()
  await expect.poll(() => captured.businessMetricsRequests || 0).toBeGreaterThan(0)
  const metricsRequestsBeforeDelete = captured.businessMetricsRequests || 0
  await removeButton.click()

  const confirmation = page.getByRole('alertdialog', { name: '移出本月工资表' })
  await expect(confirmation).toBeVisible()
  await expect(confirmation).toContainText('只删除本月工资记录')
  await expect(confirmation).toContainText('不会删除员工档案')
  await expect(confirmation).toContainText('不会修改员工所属门店和岗位')
  expect(captured.deletedSalaryId).toBeUndefined()

  await confirmation.getByRole('button', { name: '确认移出' }).click()
  await expect.poll(() => captured.deletedSalaryId).toBe('SALADD-202607-transfer')
  await expect(page.getByText('当前筛选范围暂无员工')).toBeVisible()
  await expect(page.locator('.salary-detail-panel')).toContainText('暂无员工')
  await expect(page.getByText(/已将 张调店 从 2026-07 工资表移出/)).toBeVisible()
  await expect.poll(() => captured.businessMetricsRequests || 0).toBeGreaterThan(metricsRequestsBeforeDelete)
})

test('待生成合成行和已提交工资不显示删除入口', async ({ page }) => {
  const captured: CapturedRequests = {}
  const pendingRecord = {
    ...salaryRecord,
    id: '',
    employeeName: '待生成员工',
    status: 'PENDING_GENERATION',
  }
  const submittedRecord = {
    ...salaryRecord,
    id: 'salary-submitted',
    employeeId: 'EMP-SUBMITTED',
    employeeName: '已提交员工',
    status: 'SUBMITTED',
  }
  await prepare(page, captured, pendingRecord, salaryBusinessMetrics, undefined, [submittedRecord])
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await expect(page.getByText('待生成员工', { exact: true })).toBeVisible()
  await expect(page.getByText('已提交员工', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /移出本月工资表|删除.*本月工资记录/ })).toHaveCount(0)
})

test('工资只显示三档业务状态，筛选按钮可展开下拉框，并可按同一名单批量生成', async ({ page }) => {
  const captured: CapturedRequests = {}
  const firstPendingRecord = {
    ...salaryRecord,
    id: '',
    employeeId: 'EMP-GEN-1',
    employeeName: '待生成甲',
    status: 'PENDING_GENERATION',
  }
  const secondPendingRecord = {
    ...salaryRecord,
    id: '',
    employeeId: 'EMP-GEN-2',
    employeeName: '待生成乙',
    status: 'PENDING_GENERATION',
  }
  await prepare(
    page,
    captured,
    firstPendingRecord,
    salaryBusinessMetrics,
    undefined,
    [secondPendingRecord],
  )
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await expect(page.getByRole('combobox', { name: '工资状态' })).toHaveCount(0)
  await expect.poll(() => captured.employeePageQueries?.[0]?.get('status')).toBe('ACTIVE')

  const filterButton = page.getByRole('button', { name: '筛选', exact: true })
  await expect(filterButton).toHaveAttribute('aria-expanded', 'false')
  await filterButton.click()
  const filterMenu = page.getByRole('menu', { name: '工资状态筛选' })
  await expect(filterMenu).toBeVisible()
  await expect(filterButton).toHaveAttribute('aria-expanded', 'true')
  await expect(filterMenu.getByRole('menuitemradio')).toHaveText(['全部', '待生成', '待审核', '待发放'])
  await filterMenu.getByRole('menuitemradio', { name: '待生成' }).click()
  await expect(filterMenu).toBeHidden()
  await expect(filterButton).toHaveAttribute('aria-expanded', 'false')
  await expect(filterButton).toContainText('待生成')
  await expect.poll(() => captured.employeePageQueries?.at(-1)?.get('status')).toBe('PENDING_GENERATION')
  expect(captured.employeePageQueries?.at(-1)?.get('page')).toBe('1')
  await filterButton.click()
  await expect(filterMenu.getByRole('menuitemradio', { name: '待生成' })).toHaveAttribute('aria-checked', 'true')
  await page.getByRole('heading', { name: '员工工资表' }).click()
  await expect(filterMenu).toBeHidden()
  await filterButton.focus()
  await page.keyboard.press('ArrowDown')
  await expect(filterMenu).toBeVisible()
  await expect(filterMenu.getByRole('menuitemradio', { name: '待生成' })).toBeFocused()
  await page.keyboard.press('ArrowDown')
  await expect(filterMenu.getByRole('menuitemradio', { name: '待审核' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(filterMenu).toBeHidden()
  await expect(filterButton).toBeFocused()

  await page.getByRole('checkbox', { name: '选择当前页可操作员工' }).check()
  await expect(page.getByRole('checkbox', { name: '选择待生成甲生成工资' })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: '选择待生成乙生成工资' })).toBeChecked()

  await page.getByRole('checkbox', { name: '选择待生成乙生成工资' }).uncheck()
  await page.getByRole('button', { name: '生成所选工资' }).click()

  const dialog = page.getByRole('dialog', { name: '工资生成预览' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('已选择 1 人').first()).toBeVisible()
  await expect(dialog.getByRole('checkbox', { name: '待生成甲' })).toBeChecked()
  await expect(dialog.getByRole('checkbox', { name: '待生成乙' })).not.toBeChecked()
  await expect.poll(() => captured.previewPayloads?.[0]).toMatchObject({
    storeId: 'xls12',
    month: '2026-07',
    employeeIds: ['EMP-GEN-1'],
  })

  await dialog.getByRole('button', { name: '确认生成所选工资' }).click()
  await expect.poll(() => captured.generationPayload).toEqual({
    storeId: 'xls12',
    month: '2026-07',
    employeeIds: ['EMP-GEN-1'],
  })
  expect(captured.previewPayloads?.at(-1)).toEqual(captured.generationPayload)
})

test('不选具体门店时可预览全部授权门店，候选显示门店且确认名单保持一致', async ({ page }) => {
  const captured: CapturedRequests = {}
  const firstPendingRecord = {
    ...salaryRecord,
    id: '',
    employeeId: 'EMP-ALL-1',
    employeeName: '同名员工',
    status: 'PENDING_GENERATION',
  }
  const secondPendingRecord = {
    ...salaryRecord,
    id: '',
    storeId: 'bw1',
    storeName: '霸王中心店',
    brandId: 2,
    brandName: '霸王茶姬',
    employeeId: 'EMP-ALL-2',
    employeeName: '同名员工',
    position: '咖啡师',
    status: 'PENDING_GENERATION',
  }
  const candidates = [
    {
      employeeId: firstPendingRecord.employeeId,
      employeeName: firstPendingRecord.employeeName,
      position: firstPendingRecord.position,
      storeId: firstPendingRecord.storeId,
      storeName: firstPendingRecord.storeName,
    },
    {
      employeeId: secondPendingRecord.employeeId,
      employeeName: secondPendingRecord.employeeName,
      position: secondPendingRecord.position,
      storeId: secondPendingRecord.storeId,
      storeName: secondPendingRecord.storeName,
    },
  ]
  await prepare(
    page,
    captured,
    firstPendingRecord,
    salaryBusinessMetrics,
    undefined,
    [secondPendingRecord],
    candidates,
  )
  await page.goto('/finance/salary?month=2026-07')

  const generateAllButton = page.getByRole('button', { name: '全部授权门店全选并生成' })
  await expect(generateAllButton).toBeEnabled()
  await generateAllButton.click()

  const dialog = page.getByRole('dialog', { name: '工资生成预览' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('名单包含全部授权门店')
  await expect(dialog).toContainText('荆江之星')
  await expect(dialog).toContainText('霸王中心店')
  await expect(dialog.getByText('已选择 2 人').first()).toBeVisible()
  await expect.poll(() => captured.previewPayloads?.[0]).toEqual({
    month: '2026-07',
  })

  const candidateCheckboxes = dialog.getByRole('checkbox', { name: '同名员工' })
  await expect(candidateCheckboxes).toHaveCount(2)
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()

  const tableCheckboxes = page.getByRole('checkbox', { name: '选择同名员工生成工资' })
  await expect(tableCheckboxes).toHaveCount(2)
  await expect(tableCheckboxes.nth(0)).toBeChecked()
  await expect(tableCheckboxes.nth(1)).toBeChecked()
  await tableCheckboxes.nth(1).uncheck()
  await page.getByRole('button', { name: '生成所选工资' }).click()

  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('已选择 1 人').first()).toBeVisible()
  await expect.poll(() => captured.previewPayloads?.[1]).toEqual({
    month: '2026-07',
    employeeIds: ['EMP-ALL-1'],
  })
  await dialog.getByRole('button', { name: '确认生成所选工资' }).click()

  await expect.poll(() => captured.generationPayload).toEqual({
    month: '2026-07',
    employeeIds: ['EMP-ALL-1'],
  })
  expect(captured.generationPayload).not.toHaveProperty('storeId')
  expect(captured.previewPayloads?.at(-1)).toEqual(captured.generationPayload)
})

test('未保存工资与假期修改会阻止焦点恢复同步覆盖明细', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const vacationNote = page.getByLabel('休息日期备注')
  await expect(vacationNote).toHaveValue('7月1日休息')
  await expect.poll(() => captured.employeePageRequests || 0).toBeGreaterThan(0)
  await expect.poll(() => captured.businessMetricsRequests || 0).toBeGreaterThan(0)

  await vacationNote.fill('尚未保存的 7 月休息安排')
  const requestsBeforeForegroundEvents = captured.employeePageRequests || 0

  await page.evaluate(() => {
    const staleNow = Date.now() + 61_000
    Date.now = () => staleNow
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))
  })

  await page.waitForTimeout(100)
  expect(captured.employeePageRequests || 0).toBe(requestsBeforeForegroundEvents)
  await expect(vacationNote).toHaveValue('尚未保存的 7 月休息安排')

  await page.getByLabel('月份').fill('2026-08')
  await expect(page.getByRole('heading', { name: '放弃未保存的工资修改？' })).toBeVisible()
  await expect(page.getByLabel('月份')).toHaveValue('2026-07')
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(vacationNote).toHaveValue('尚未保存的 7 月休息安排')
  await expect(page.getByLabel('月份')).toHaveValue('2026-07')
})

test('工资筛选、汇总、表格和明细在 390px 下保持可达且不撑宽页面', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  for (const control of [
    page.getByLabel('月份'),
    page.getByRole('combobox', { name: '门店', exact: true }),
    page.getByRole('button', { name: '筛选', exact: true }),
    page.locator('.salary-search'),
  ]) {
    await control.scrollIntoViewIfNeeded()
    await expect(control).toBeVisible()
    const box = await control.boundingBox()
    expect(box?.height, `${await control.getAttribute('aria-label') || '工资筛选控件'} 的点击高度`).toBeGreaterThanOrEqual(44)
  }

  await expect(page.getByLabel('搜索工资记录')).toBeVisible()
  await expect(page.locator('.salary-summary-strip')).toBeVisible()
  await expect(page.locator('.business-metrics')).toBeVisible()
  await page.getByText('李店员', { exact: true }).first().click()
  await expect(page.getByLabel('休息日期备注')).toBeVisible()
  await expectNoWholePageOverflow(page, 'finance salary 390px')
})

test('桌面工资明细限制在可视高度内并保持底部操作可见', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const panel = page.locator('.salary-detail-panel')
  await panel.scrollIntoViewIfNeeded()
  const layout = await panel.evaluate((element) => {
    const scrollArea = element.querySelector<HTMLElement>('.detail-scroll-area')
    const footer = element.querySelector<HTMLElement>('.detail-actions')
    const panelBox = element.getBoundingClientRect()
    const footerBox = footer?.getBoundingClientRect()
    return {
      panelHeight: panelBox.height,
      scrollClientHeight: scrollArea?.clientHeight || 0,
      scrollHeight: scrollArea?.scrollHeight || 0,
      footerBottom: footerBox?.bottom || 0,
      panelBottom: panelBox.bottom,
    }
  })

  expect(layout.panelHeight).toBeLessThanOrEqual(761)
  expect(layout.scrollHeight).toBeGreaterThan(layout.scrollClientHeight)
  expect(layout.footerBottom).toBeLessThanOrEqual(layout.panelBottom + 1)
  await expect(page.getByRole('button', { name: '保存工资与假期' })).toBeVisible()
  await expect(page.getByRole('button', { name: '提交审核' })).toBeVisible()

  const saveBox = await page.getByRole('button', { name: '保存工资与假期' }).boundingBox()
  const submitBox = await page.getByRole('button', { name: '提交审核' }).boundingBox()
  expect(Math.abs(Number(saveBox?.y) - Number(submitBox?.y))).toBeLessThanOrEqual(1)
})

test('草稿工资即使没有新增修改也允许主动保存并反馈结果', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const saveButton = page.getByRole('button', { name: '保存工资与假期' })
  await expect(saveButton).toBeEnabled()
  await saveButton.click()

  expect(captured.attendance).toBeUndefined()
  await expect.poll(() => captured.salaryUpdate).toMatchObject({
    storeId: 'xls12',
    month: '2026-07',
    employeeId: 'EMP-001',
    vacationLeft: 2,
    vacationNote: '7月1日休息',
  })
  await expect(page.getByText(/已保存 李店员 的工资与假期信息/)).toBeVisible()
})

test('工资修改必须先保存再提交，保存失败以弹窗提示且关闭后可直接重试', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  let saveAttempts = 0
  await page.route(/\/api\/salaries\/salary-1$/, async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback()
    saveAttempts += 1
    if (saveAttempts === 1) {
      return route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 'VERSION_CONFLICT',
          message: '工资记录已被其他用户修改，请刷新后重试',
        }),
      })
    }
    captured.salaryUpdate = route.request().postDataJSON() as Record<string, unknown>
    return route.fulfill(ok({ ...salaryRecord, ...captured.salaryUpdate }))
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const saveButton = page.getByRole('button', { name: '保存工资与假期' })
  const submitButton = page.getByRole('button', { name: '提交审核' })
  await expect(saveButton).toBeEnabled()
  await expect(submitButton).toBeEnabled()

  await page.getByLabel('其他补贴').fill('80')
  await expect(saveButton).toBeEnabled()
  await expect(submitButton).toBeDisabled()
  await expect(page.getByText('工资明细有未保存修改，请先保存后再提交审核。')).toBeVisible()

  await saveButton.click()
  const errorDialog = page.getByRole('alertdialog')
  await expect(errorDialog).toContainText('工资记录已被其他用户修改')
  await expect(page.locator('.salary-detail-panel').getByRole('alert')).toBeHidden()
  await errorDialog.getByRole('button', { name: '我知道了' }).click()
  await expect(saveButton).toBeEnabled()
  await expect(submitButton).toBeDisabled()

  await saveButton.click()
  await expect.poll(() => saveAttempts).toBe(2)
  await expect(page.getByText(/已保存 李店员 的工资与假期信息/)).toBeVisible()
  await expect(submitButton).toBeEnabled()
})

test('状态和关键词筛选可清除，员工总数与当前筛选结果不会混淆', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  const salaryQueries: URLSearchParams[] = []
  await page.route(/\/api\/salaries\/employee-page/, async (route) => {
    const url = new URL(route.request().url())
    salaryQueries.push(new URLSearchParams(url.search))
    const status = url.searchParams.get('status')
    const filtered = Boolean((status && status !== 'ACTIVE') || url.searchParams.get('keyword'))
    return route.fulfill(ok({
      ...salaryPage(filtered ? [] : [salaryRecord]),
      content: filtered ? [] : [salaryRecord],
      total: filtered ? 0 : 1,
      totalElements: filtered ? 0 : 1,
      statusCounts: { DRAFT: 1 },
      summary: {
        ...salaryPage([salaryRecord]).summary,
        recordCount: 1,
      },
    }))
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  const statusFilterButton = page.getByRole('button', { name: '筛选', exact: true })
  await statusFilterButton.click()
  await page.getByRole('menu', { name: '工资状态筛选' })
    .getByRole('menuitemradio', { name: '待审核' })
    .click()
  await expect(page.getByText('当前筛选范围暂无员工')).toBeVisible()
  await expect(page.locator('.title-block')).toContainText('共 1 名员工 · 当前显示 0 名')

  const clearButton = page.getByRole('button', { name: '清除筛选' })
  await expect(clearButton).toBeEnabled()
  await clearButton.click()
  await expect(statusFilterButton).not.toContainText('待审核')
  await expect(page.getByText('李店员', { exact: true }).first()).toBeVisible()
  await expect(clearButton).toBeDisabled()
  await expect.poll(() => salaryQueries.some((query) => query.get('status') === 'PENDING_REVIEW')).toBe(true)
  await expect.poll(() => salaryQueries.at(-1)?.get('status')).toBe('ACTIVE')
})

test('批量审核只允许选择待审核工资，部分失败时保留失败记录供重试', async ({ page }) => {
  const captured: CapturedRequests = {}
  const submittedOne = { ...salaryRecord, id: 'salary-submitted-1', employeeName: '待审甲', status: 'SUBMITTED' }
  const submittedTwo = {
    ...salaryRecord,
    id: 'salary-submitted-2',
    employeeId: 'EMP-002',
    employeeName: '待审乙',
    status: 'PENDING_REVIEW',
  }
  await prepare(page, captured, salaryRecord, salaryBusinessMetrics, undefined, [submittedOne, submittedTwo])
  const approvalAttempts: string[] = []
  await page.route(/\/api\/salaries\/[^/]+\/approve$/, async (route) => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split('/').at(-2) || '')
    approvalAttempts.push(id)
    if (id === submittedTwo.id) {
      return route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 'VERSION_CONFLICT',
          message: '工资记录已被其他用户修改，请刷新后重试',
        }),
      })
    }
    return route.fulfill(ok({ ...submittedOne, status: 'APPROVED' }))
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await expect(page.getByLabel('选择李店员')).toBeDisabled()
  await expect(page.getByLabel('选择待审甲')).toBeEnabled()
  await expect(page.getByLabel('选择待审乙')).toBeEnabled()
  await page.getByLabel('选择当前页可操作员工').check()
  const batchButton = page.getByRole('button', { name: /批量审核\s*（2）/ })
  await expect(batchButton).toBeEnabled()
  await batchButton.click()
  await page.getByRole('alertdialog', { name: '批量审核工资' }).getByRole('button', { name: '确认审核' }).click()

  await expect.poll(() => approvalAttempts).toEqual([submittedOne.id, submittedTwo.id])
  await expect(page.getByText('已审核 1 条工资记录')).toBeVisible()
  await expect(page.getByText(/1 条工资审核失败/)).toBeVisible()
  await expect(page.getByLabel('选择待审甲')).not.toBeChecked()
  await expect(page.getByLabel('选择待审乙')).toBeChecked()
})

test('添加人员名单加载失败可在弹窗内重试，成功后恢复选择和提交', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  let candidateAttempts = 0
  await page.route(/\/api\/salaries\/assignment-candidates/, async (route) => {
    candidateAttempts += 1
    if (candidateAttempts === 1) {
      return route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, code: 'BACKEND_UNAVAILABLE', message: '服务暂时不可用' }),
      })
    }
    return route.fulfill(ok([assignmentCandidate]))
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')
  await page.getByRole('button', { name: '添加人员', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: '添加人员' })
  await dialog.getByRole('tab', { name: '选择已有人员' }).click()
  await expect(dialog.getByRole('alert')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '重新加载' })).toBeVisible()
  await dialog.getByRole('button', { name: '重新加载' }).click()
  await expect(dialog.getByText('张调店', { exact: true })).toBeVisible()
  await dialog.getByRole('radio').check()
  await expect(dialog.getByRole('button', { name: '添加到工资名单' })).toBeEnabled()
  expect(candidateAttempts).toBe(2)
})

test('工资生成先展示加载和失败重试，没有可生成员工时禁止确认生成', async ({ page }) => {
  const captured: CapturedRequests = {}
  await prepare(page, captured)
  let previewAttempts = 0
  await page.route(/\/api\/salaries\/preview/, async (route) => {
    previewAttempts += 1
    if (previewAttempts === 1) {
      return route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ success: false, code: 'BACKEND_UNAVAILABLE', message: '服务暂时不可用' }),
      })
    }
    return route.fulfill(ok({
      generated: 0,
      skipped: 1,
      errors: 0,
      skipDetails: [{ employeeId: 'EMP-001', employeeName: '李店员', reason: '缺少已确认考勤' }],
    }))
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')
  await page.getByRole('button', { name: '全选并生成' }).click()

  const dialog = page.getByRole('dialog', { name: '工资生成预览' })
  await expect(dialog.getByRole('alert')).toBeVisible()
  await dialog.getByRole('button', { name: '重新预览' }).click()
  await expect(dialog).toContainText('可生成 0 人')
  await expect(dialog).toContainText('李店员')
  await expect(dialog).toContainText('缺少已确认考勤')
  await expect(dialog.getByRole('button', { name: '确认生成所选工资' })).toBeDisabled()
  expect(previewAttempts).toBe(2)
})

test('全部门店下从员工明细进入预览会使用员工所属门店', async ({ page }) => {
  const captured: CapturedRequests = {}
  const pendingRecord = {
    ...salaryRecord,
    id: '',
    gross: 0,
    status: 'PENDING_GENERATION' as const,
  }
  await prepare(page, captured, pendingRecord)
  let previewQuery: URLSearchParams | undefined
  await page.route(/\/api\/salaries\/preview/, async (route) => {
    previewQuery = new URL(route.request().url()).searchParams
    return route.fulfill(ok({
      generated: 1,
      skipped: 0,
      errors: 0,
      skipDetails: [],
    }))
  })

  await page.goto('/finance/salary?month=2026-07')
  await page.getByRole('button', { name: '进入生成预览' }).click()

  const dialog = page.getByRole('dialog', { name: '工资生成预览' })
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('荆江之星')
  await expect(dialog.getByRole('button', { name: '确认生成所选工资' })).toBeEnabled()
  await expect.poll(() => previewQuery?.get('storeId')).toBe('xls12')
  await expect(page.getByRole('alert').filter({ hasText: '请先选择具体门店' })).toHaveCount(0)
})

test('已发放工资可锁定，锁定失败保留确认框和错误以便直接重试', async ({ page }) => {
  const captured: CapturedRequests = {}
  const paidRecord = { ...salaryRecord, id: 'salary-paid', employeeName: '已发员工', status: 'PAID' }
  await prepare(page, captured, paidRecord)
  let lockAttempts = 0
  await page.route(/\/api\/salaries\/salary-paid\/lock$/, async (route) => {
    lockAttempts += 1
    if (lockAttempts === 1) {
      return route.fulfill({
        status: 409,
        contentType: 'application/json',
        body: JSON.stringify({
          success: false,
          code: 'VERSION_CONFLICT',
          message: '工资记录已被其他用户修改，请刷新后重试',
        }),
      })
    }
    return route.fulfill(ok({ ...paidRecord, status: 'LOCKED' }))
  })
  await page.goto('/finance/salary?storeId=xls12&month=2026-07')

  await page.getByRole('button', { name: '锁定工资记录' }).click()
  const confirmation = page.getByRole('alertdialog', { name: '锁定工资记录' })
  await confirmation.getByRole('button', { name: '确认锁定' }).click()
  await expect(confirmation.getByRole('alert')).toContainText('工资记录已被其他用户修改')
  await expect(confirmation).toBeVisible()

  await confirmation.getByRole('button', { name: '确认锁定' }).click()
  await expect.poll(() => lockAttempts).toBe(2)
  await expect(confirmation).toHaveCount(0)
  await expect(page.getByText('工资记录已锁定')).toBeVisible()
})
