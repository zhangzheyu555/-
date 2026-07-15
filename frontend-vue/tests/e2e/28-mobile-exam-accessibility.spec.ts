import { expect, test, type Page, type Route } from '@playwright/test'
import { expectNoWholePageOverflow } from './auth.setup'

const employeeSession = {
  id: 2301,
  tenantId: 1,
  tenantName: 'TEST 租户',
  displayName: '测试员工',
  role: 'EMPLOYEE',
  roleLabel: '员工',
  storeScope: ['TEST-STORE'],
  permissions: ['exam.learn'],
  dataScopes: {
    STORE: { mode: 'OWN_STORE', storeIds: ['TEST-STORE'] },
  },
  boundStoreId: 'TEST-STORE',
  boundStoreName: '测试门店',
  defaultWorkspace: '/learn/exams',
  permissionVersion: 1,
}

const mobileProjectWidths: Record<string, number> = {
  'iphone-390': 390,
  'android-412': 412,
  'ipad-768': 768,
}

const assignment = {
  id: 601,
  campaignId: 301,
  paperId: 201,
  examTitle: '门店服务基础考试',
  paperName: '服务标准入门',
  userId: employeeSession.id,
  examineeName: employeeSession.displayName,
  examineeRole: 'EMPLOYEE',
  storeId: 'TEST-STORE',
  storeName: '测试门店',
  status: 'ASSIGNED',
  statusLabel: '待考试',
  startAt: '2026-07-15 09:00',
  dueAt: '2026-07-20 18:00',
}

function json(route: Route, data: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json; charset=UTF-8',
    body: JSON.stringify({ success: status < 400, data }),
  })
}

async function seedMobileExam(page: Page) {
  await page.addInitScript((session) => {
    localStorage.setItem('ai_profit_vue_token', 'TEST-MOBILE-EXAM-TOKEN')
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(session))
  }, employeeSession)
  await page.route((url) => url.pathname.startsWith('/api/'), async (route) => {
    const pathname = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (pathname === '/api/auth/me') return json(route, employeeSession)
    if (pathname === '/api/exam-center/overview') {
      return json(route, {
        accessMode: 'SELF',
        canManage: false,
        canExport: false,
        papers: [],
        campaigns: [],
        assignments: [assignment],
        candidates: [],
      })
    }
    if (['/api/exam-center/courses', '/api/exam-center/materials', '/api/exam-center/videos', '/api/exam-center/results', '/api/exam-center/wrong-questions'].includes(pathname)) return json(route, [])
    if (pathname === '/api/exam-center/assignments/601/paper') {
      return json(route, {
        id: 201,
        paperCode: 'PAPER-201',
        paperName: assignment.paperName,
        passScore: 80,
        enabled: true,
        questions: [
          { id: 701, questionType: 'SINGLE_CHOICE', questionText: '顾客进店后应先做什么？', options: ['A. 主动问候', 'B. 继续整理货架'], score: 50, sortOrder: 1 },
          { id: 702, questionType: 'SINGLE_CHOICE', questionText: '交接班前应完成什么？', options: ['A. 记录异常', 'B. 关闭系统'], score: 50, sortOrder: 2 },
        ],
      })
    }
    if (pathname === '/api/exam-center/assignments/601/submit' && method === 'POST') {
      return json(route, {
        id: 901,
        paperId: 201,
        paperName: assignment.paperName,
        examineeName: employeeSession.displayName,
        examineeRole: 'EMPLOYEE',
        score: 100,
        passed: true,
        violated: false,
        submittedAt: '2026-07-15 10:00',
      })
    }
    return json(route, [])
  })
}

test('configured mobile projects keep exam choices and navigation touch-safe without overriding the device viewport', async ({ page }, testInfo) => {
  const expectedWidth = mobileProjectWidths[testInfo.project.name]
  test.skip(!expectedWidth, '仅在 iPhone、Android 和 iPad 设备项目中执行')
  expect(page.viewportSize()?.width).toBe(expectedWidth)

  await seedMobileExam(page)
  await page.goto('/learn/exams')
  await page.getByRole('button', { name: '开始考试', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: '考试作答' })
  await expect(dialog.getByText('第 1 / 2 题')).toBeVisible()
  const firstChoice = dialog.locator('.exam-question--current .choice').first()
  const firstChoiceBox = await firstChoice.boundingBox()
  expect(firstChoiceBox?.height, '整行选项的点击高度').toBeGreaterThanOrEqual(44)
  await firstChoice.click()
  await expect(firstChoice.getByRole('radio')).toBeChecked()

  const previous = dialog.getByRole('button', { name: '上一题', exact: true })
  const next = dialog.getByRole('button', { name: '下一题', exact: true })
  const submit = dialog.getByRole('button', { name: '提交考试', exact: true })
  for (const control of [previous, next, submit]) {
    const box = await control.boundingBox()
    expect(box?.height, `${await control.innerText()} 的点击高度`).toBeGreaterThanOrEqual(44)
  }
  await expect(previous).toBeDisabled()

  await next.click()
  await expect(dialog.getByText('第 2 / 2 题')).toBeVisible()
  await expect(previous).toBeEnabled()
  await previous.click()
  await expect(dialog.getByText('第 1 / 2 题')).toBeVisible()
  await next.click()
  await expect(dialog.getByText('第 2 / 2 题')).toBeVisible()
  const secondChoice = dialog.locator('.exam-question--current .choice').first()
  await secondChoice.click()
  await expect(secondChoice.getByRole('radio')).toBeChecked()
  await expectNoWholePageOverflow(page, `${testInfo.project.name} exam`)

  await submit.click()
  await expect(page.getByText('考试已提交，得分 100 分。')).toBeVisible()
})
