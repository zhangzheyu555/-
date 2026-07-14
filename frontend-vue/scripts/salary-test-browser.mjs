import { chromium } from '@playwright/test'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const password = process.env.TEST_BOSS_PASSWORD
const baseUrl = process.env.SALARY_TEST_BASE_URL || 'http://127.0.0.1:15173'
delete process.env.TEST_BOSS_PASSWORD
delete process.env.SALARY_TEST_BASE_URL
if (!password) throw new Error('TEST boss password was not provided to the browser process')

const runtimeDir = join(tmpdir(), 'ai-profit-salary-test-runtime')
const reportPath = join(runtimeDir, 'salary-browser-report.json')
const screenshotPath = join(runtimeDir, 'salary-page.png')
const singleStoreScreenshotPath = join(runtimeDir, 'salary-page-single-store.png')
const browser = await chromium.launch({ headless: false })
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const consoleErrors = []
const failedResponses = []
let currentStep = '启动浏览器'

async function saveProgress(extra = {}) {
  await writeFile(reportPath, JSON.stringify({
    status: 'RUNNING',
    currentStep,
    consoleErrors,
    failedResponses,
    ...extra,
  }, null, 2), 'utf8')
}

page.on('console', (message) => {
  if (message.type() === 'error') consoleErrors.push(message.text())
})
page.on('response', async (response) => {
  if (response.status() < 400) return
  failedResponses.push({
    method: response.request().method(),
    url: response.url(),
    status: response.status(),
  })
})

try {
  currentStep = '登录老板账号'
  await saveProgress()
  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' })
  await page.getByRole('textbox', { name: '账号' }).fill('boss')
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/auth/login')),
    page.getByRole('button', { name: '登录', exact: true }).click(),
  ])
  await page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 15_000 })
  currentStep = '加载全部门店工资页'
  await saveProgress()
  await page.goto(`${baseUrl}/salary`, { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '员工工资表' }).waitFor({ timeout: 15_000 })
  const firstRow = page.locator('tbody tr').first()
  await firstRow.waitFor({ timeout: 15_000 })
  await page.screenshot({ path: screenshotPath, fullPage: true })

  const employeeRows = await page.locator('tbody tr').count()
  if (employeeRows < 1) throw new Error('The TEST tenant returned no employees')
  const firstEmployeeName = (await firstRow.locator('td').nth(1).innerText()).trim()

  currentStep = '联动右侧员工明细'
  await saveProgress({ employeeRows, firstEmployeeName })
  await firstRow.click()
  const detailHeading = page.locator('.salary-detail-panel h2', { hasText: firstEmployeeName })
  await detailHeading.waitFor()
  const detailLinked = await detailHeading.isVisible()

  const storeSelect = page.getByRole('combobox', { name: '门店', exact: true })
  currentStep = '切换单门店'
  await saveProgress({ employeeRows, firstEmployeeName, detailLinked })
  const concreteStore = await storeSelect.locator('option').evaluateAll((options) => (
    options.map((option) => option.value).find((value) => value && value !== 'all') || ''
  ))
  if (!concreteStore) throw new Error('No concrete store is available for salary verification')
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/salaries/employee-page') && response.status() === 200),
    storeSelect.selectOption(concreteStore),
  ])
  await page.getByRole('heading', { name: /· 员工工资表$/ }).waitFor()
  await page.screenshot({ path: singleStoreScreenshotPath, fullPage: true })

  const statusSelect = page.getByRole('combobox', { name: '工资状态' })
  currentStep = '验证状态筛选'
  await saveProgress({ employeeRows, firstEmployeeName, detailLinked })
  const statusCounts = {}
  for (const status of ['PENDING_GENERATION', 'SUBMITTED', 'APPROVED', 'PAID']) {
    await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/salaries/employee-page') && response.status() === 200),
      statusSelect.selectOption(status),
    ])
    statusCounts[status] = await page.locator('tbody tr').count()
  }
  currentStep = '验证员工搜索'
  await saveProgress({ employeeRows, firstEmployeeName, detailLinked, statusCounts })
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/salaries/employee-page') && response.status() === 200),
    statusSelect.selectOption(''),
  ])

  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/salaries/employee-page') && response.status() === 200),
    page.getByPlaceholder('搜索姓名 / 工号 / 岗位').fill(firstEmployeeName),
  ])
  const searchRows = await page.locator('tbody tr').count()
  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/salaries/employee-page') && response.status() === 200),
    page.getByPlaceholder('搜索姓名 / 工号 / 岗位').fill(''),
  ])

  currentStep = '验证生成预览'
  await saveProgress({ employeeRows, firstEmployeeName, detailLinked, statusCounts, searchRows })
  await page.getByRole('button', { name: '生成本月工资' }).click()
  await page.getByText('生成预览', { exact: false }).first().waitFor({ timeout: 15_000 })
  await page.getByRole('button', { name: /关闭|取消/ }).first().click()

  currentStep = '验证工资导出'
  await saveProgress({ employeeRows, firstEmployeeName, detailLinked, statusCounts, searchRows })
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出工资表' }).click()
  const download = await downloadPromise
  const exportFilename = download.suggestedFilename()

  currentStep = '验证刷新后状态'
  await saveProgress({ employeeRows, firstEmployeeName, detailLinked, statusCounts, searchRows, exportFilename })
  await page.reload({ waitUntil: 'networkidle' })
  await page.locator('tbody tr').first().waitFor()
  const rowsAfterRefresh = await page.locator('tbody tr').count()

  const bodyText = await page.locator('body').innerText()
  const report = {
    status: 'PASSED',
    url: page.url(),
    title: await page.title(),
    employeeRows,
    rowsAfterRefresh,
    detailLinked,
    statusCounts,
    searchRows,
    exportFilename,
    firstEmployeeName,
    hasPendingGeneration: bodyText.includes('待生成'),
    hasTechnicalError: /Request failed|Handler dispatch failed|NoClassDefFoundError|java\.lang|org\.springframework/.test(bodyText),
    hasRedBusinessError: bodyText.includes('未找到相关数据') || bodyText.includes('工资明细加载失败'),
    consoleErrors,
    failedResponses,
    screenshotPath,
    singleStoreScreenshotPath,
  }
  await writeFile(reportPath, JSON.stringify(report, null, 2), 'utf8')
  process.stdout.write(`Salary browser report: ${reportPath}\n`)
} catch (error) {
  await writeFile(reportPath, JSON.stringify({
    status: 'FAILED',
    currentStep,
    error: error instanceof Error ? error.stack || error.message : String(error),
    consoleErrors,
    failedResponses,
    screenshotPath,
    singleStoreScreenshotPath,
  }, null, 2), 'utf8')
  throw error
} finally {
  await browser.close()
}
