import { expect, test, type Page, type Route } from '@playwright/test'

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
  assignment?: Record<string, unknown>
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
) {
  let assignedRecord: typeof salaryRecord | undefined
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
      const records = [initialRecord, ...additionalRecords, ...(assignedRecord ? [assignedRecord] : [])]
        .filter((record) => !deletedRecordIds.has(record.id))
      return route.fulfill(ok(salaryPage(
        records,
        workHoursTotal,
      )))
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
  await expect(dialog).toContainText('不会修改员工档案所属门店，岗位也保持不变')
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
