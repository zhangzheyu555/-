import { expect, test } from '@playwright/test'
import { apiFetchAs, loginAs, type RoleKey } from './auth.setup'

const pages: Array<{ role: RoleKey; path: string; text: string }> = [
  { role: 'boss', path: '/boss', text: '今日待办' },
  { role: 'boss', path: '/profit', text: '利润概览' },
  { role: 'boss', path: '/profit-table', text: '利润表' },
  { role: 'boss', path: '/store-detail', text: '门店详情' },
  { role: 'boss', path: '/assistant', text: '门店经营助手' },
  { role: 'boss', path: '/data-entry', text: '数据录入' },
  { role: 'boss', path: '/expenses', text: '报销栏' },
  { role: 'boss', path: '/export', text: '数据导出' },
  { role: 'boss', path: '/stores', text: '门店管理' },
  { role: 'finance', path: '/profit', text: '利润概览' },
  { role: 'finance', path: '/export', text: '数据导出' },
  { role: 'finance', path: '/expenses', text: '报销栏' },
  { role: 'finance', path: '/profit-table', text: '利润表' },
  { role: 'finance', path: '/salary', text: '员工工资' },
  { role: 'warehouse', path: '/warehouse', text: '仓库中心' },
  { role: 'warehouse', path: '/warehouse/items', text: '商品档案' },
  { role: 'warehouse', path: '/warehouse/purchase', text: '采购入库' },
  { role: 'warehouse', path: '/warehouse/movements', text: '出入库记录' },
  { role: 'operations', path: '/operations/inspection', text: '督导巡店' },
  { role: 'operations', path: '/operations/inspection/records', text: '巡检记录' },
  { role: 'operations', path: '/operations/inspection/tasks', text: '发起巡检' },
  { role: 'operations', path: '/operations/exams', text: '培训考试' },
  { role: 'operations', path: '/platform-login', text: '平台配置' },
  { role: 'store', path: '/warehouse', text: '仓库中心' },
]

test.describe('core business pages load', () => {
  for (const item of pages) {
    test(`${item.role} can load ${item.path}`, async ({ page }) => {
      await loginAs(page, item.role)
      await page.goto(item.path)
      await page.waitForLoadState('networkidle')
      await expect(page.getByText(item.text).first()).toBeVisible()
    })
  }

  test('profit overview has migrated from placeholder to real business page', async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    await loginAs(page, 'boss')
    await page.goto('/profit')
    await page.waitForLoadState('networkidle')

    const body = await page.locator('body').innerText()
    expect(body).toContain('老板，你好')
    expect(body).toContain('合并营业收入')
    expect(body).toContain('茹菓')
    expect(body).toContain('霸王茶姬')
    expect(body).toContain('瑞幸咖啡')
    expect(body).not.toContain('苹果奶茶')
    expect(body).toContain('各店净利率排名')
    expect(body).toContain('品牌卡片')
    expect(body).not.toContain('暂未迁移')
    expect(body).not.toContain('第一阶段占位')
    expect(consoleErrors).toEqual([])
  })

  test('profit table follows the legacy report structure', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/profit-table?mode=single&storeId=rx13')
    await page.waitForLoadState('networkidle')

    const body = await page.locator('body').innerText()
    expect(body).toContain('利润表')
    expect(body).toContain('单店利润表')
    expect(body).toContain('全部门店汇总')
    expect(body).toContain('营业总收入')
    expect(body).toContain('实收收入')
    expect(body).toContain('成本')
    expect(body).toContain('费用')
    expect(body).toContain('净利润')
    expect(body).not.toContain('财务工作台')
    expect(body).not.toContain('暂未迁移')
    expect(body).not.toContain('第一阶段占位')

    await page.getByRole('button', { name: '全部门店汇总' }).click()
    await page.waitForLoadState('networkidle')
    await expect(page.locator('body')).toContainText('全部门店利润汇总')
    await expect(page.locator('body')).toContainText('营业额')
    await expect(page.locator('body')).toContainText('成本合计')
    await expect(page.locator('body')).toContainText('费用合计')
  })

  test('boss core pages follow legacy replication keywords', async ({ page }) => {
    await loginAs(page, 'boss')
    const checks = [
      { path: '/boss', texts: ['老板，你好', '今日待办', '需要我处理', '已处理复盘'] },
      { path: '/profit', texts: ['老板，你好', '合并营业收入', '合并净利润', '茹菓', '霸王茶姬', '瑞幸咖啡', '各店净利率排名'] },
      { path: '/profit-table', texts: ['单店利润表', '全部门店汇总', '收入', '成本', '费用', '净利润'] },
      { path: '/store-detail', texts: ['累计营收', '累计净利', '平均净利率', '门店基础资料', '逐月经营明细'] },
      { path: '/data-entry', texts: ['Excel 表格', '收入（元）', '成本（元）', '费用（元）', '实时利润核算'] },
    ]
    for (const check of checks) {
      await page.goto(check.path)
      await page.waitForLoadState('networkidle')
      const body = await page.locator('body').innerText()
      for (const text of check.texts) {
        expect(body, `${check.path} should contain ${text}`).toContain(text)
      }
      expect(body, `${check.path} should not show placeholder text`).not.toContain('暂未迁移')
      expect(body, `${check.path} should not show phase placeholder`).not.toContain('第一阶段占位')
      expect(body, `${check.path} should not show Vue3 workbench wording`).not.toContain('Vue3 工作台')
      expect(body, `${check.path} should not show finance workbench wording`).not.toContain('财务工作台')
    }
  })

  test('profit table brand selector controls store options and URL query', async ({ page, request }) => {
    await loginAs(page, 'boss')
    await page.goto('/profit-table?mode=single')
    await page.waitForLoadState('networkidle')

    const brandSelect = page.getByLabel('品牌')
    const storeSelect = page.getByLabel('门店')
    await expect(brandSelect).toBeVisible()
    await expect(storeSelect).toBeVisible()

    const brandLabels = await brandSelect.locator('option').evaluateAll((options) => options.map((option) => option.textContent?.trim() || ''))
    expect(brandLabels).toEqual(expect.arrayContaining(['茹菓', '霸王茶姬', '瑞幸咖啡']))
    expect(brandLabels).not.toContain('苹果奶茶')

    const response = await apiFetchAs(request, 'boss', '/api/finance/dashboard')
    const payload = await response.json()
    const entries = payload.data.entries as Array<{ storeId: string; brandId?: number; brandName?: string }>

    for (const brandName of ['茹菓', '霸王茶姬', '瑞幸咖啡']) {
      await brandSelect.selectOption({ label: brandName })
      const selectedBrandValue = await brandSelect.inputValue()
      await expect.poll(() => new URL(page.url()).searchParams.get('brandId')).toBe(selectedBrandValue)

      const actualStoreIds = await storeSelect.locator('option').evaluateAll((options) => options.map((option) => (option as HTMLOptionElement).value).filter(Boolean))
      const expectedStoreIds = Array.from(new Set(entries
        .filter((entry) => normalizeBrandNameForTest(entry.brandName || '') === brandName || String(entry.brandId || '') === selectedBrandValue)
        .map((entry) => entry.storeId)))

      expect(actualStoreIds).toEqual(expectedStoreIds)
      if (actualStoreIds.length) {
        await expect.poll(() => new URL(page.url()).searchParams.get('storeId')).toBe(actualStoreIds[0])
      }
    }

    await page.getByRole('button', { name: '全部门店汇总' }).click()
    await page.waitForLoadState('networkidle')
    await brandSelect.selectOption({ label: '瑞幸咖啡' })
    const selectedBrandValue = await brandSelect.inputValue()
    const url = new URL(page.url())
    expect(url.searchParams.get('mode')).toBe('summary')
    expect(url.searchParams.get('brandId')).toBe(selectedBrandValue)
    expect(url.searchParams.get('storeId')).toBeNull()
  })

  test('store detail brand selector limits store dropdown to the selected brand', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/store-detail')
    await page.waitForLoadState('networkidle')

    const brandSelect = page.getByLabel('品牌')
    const storeSelect = page.getByLabel('门店')
    await expect(brandSelect).toBeVisible()
    await expect(storeSelect).toBeVisible()

    await brandSelect.selectOption({ label: '茹菓' })
    await expect.poll(async () => {
      return storeSelect.locator('option').evaluateAll((options) => options.map((option) => option.textContent?.trim() || ''))
    }).not.toContainEqual(expect.stringContaining('霸王茶姬'))
    const ruguoStoreOptions = await storeSelect.locator('option').evaluateAll((options) => options.map((option) => option.textContent?.trim() || ''))
    expect(ruguoStoreOptions.join('\n')).not.toContain('瑞幸咖啡')
    expect(ruguoStoreOptions.join('\n')).toContain('茹菓')
  })

  test('inspection page follows legacy three-tab layout instead of workbench cards', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/inspection')
    await page.waitForLoadState('networkidle')

    const body = page.locator('body')
    await expect(body).toContainText('督导巡店')
    await expect(body).toContainText('巡检记录')
    await expect(body).toContainText('发起巡检')
    await expect(body).toContainText('稽核标准')
    await expect(body).toContainText('巡检总次数')
    await expect(body).toContainText('本月巡检')
    await expect(body).toContainText('平均得分')
    await expect(body).toContainText('红线门店次数')
    await expect(body).toContainText('全部品牌')
    await expect(body).toContainText('茹菓')
    await expect(body).toContainText('霸王茶姬')
    await expect(body).toContainText('瑞幸咖啡')
    await expect(body).not.toContainText('督导工作台')
    await expect(body).not.toContainText('今日巡店重点')
    await expect(body).not.toContainText('待我处理')
    await expect(body).not.toContainText('已处理复盘')

    await page.getByRole('button', { name: '发起巡检' }).click()
    await expect(page).toHaveURL(/\/operations\/inspection\/tasks$/)
    await expect(body).toContainText('保存巡检')
    await expect(body).toContainText('品牌')
    await expect(body).toContainText('门店')

    await page.getByRole('button', { name: '稽核标准' }).click()
    await expect(page).toHaveURL(/\/operations\/inspection\/standards$/)
    await expect(body).toContainText('标准版本')
    await expect(body).toContainText('红线项')
    await expect(body).toContainText('完整评分条款')
    await expect(body).toContainText('标准分')

    await page.getByRole('button', { name: '巡检记录' }).click()
    await expect(page).toHaveURL(/\/operations\/inspection\/records$/)
    await expect(body).toContainText('日期')
    await expect(body).toContainText('得分')
  })

  test('store detail stays focused on store operation data instead of workbench modules', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/store-detail')
    await page.waitForLoadState('networkidle')

    const body = await page.locator('body').innerText()
    expect(body).toContain('门店基础资料')
    expect(body).toContain('逐月经营明细')
    expect(body).not.toContain('查看仓库记录')
    expect(body).not.toContain('查看巡检记录')
    expect(body).not.toContain('查看报销记录')
    expect(body).not.toContain('仓库叫货摘要')
    expect(body).not.toContain('巡检问题摘要')
    expect(body).not.toContain('叫货单')
    expect(body).not.toContain('最近评分')
  })

  test('data entry brand selector limits store dropdown to the selected brand', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/data-entry')
    await page.waitForLoadState('networkidle')

    const brandSelect = page.getByLabel('品牌')
    const storeSelect = page.getByLabel('门店')
    await expect(brandSelect).toBeVisible()
    await expect(storeSelect).toBeVisible()

    await brandSelect.selectOption({ label: '瑞幸咖啡' })
    const optionText = (await storeSelect.locator('option').evaluateAll((options) => options.map((option) => option.textContent?.trim() || ''))).join('\n')
    expect(optionText).toContain('瑞幸咖啡')
    expect(optionText).not.toContain('茹菓')
    expect(optionText).not.toContain('霸王茶姬')
  })

  test('brand badges use the unified brand colors', async ({ page }) => {
    await loginAs(page, 'boss')
    await page.goto('/stores')
    await page.waitForLoadState('networkidle')

    const expectedColors = [
      { name: '茹菓', color: 'rgb(239, 125, 60)' },
      { name: '霸王茶姬', color: 'rgb(156, 47, 62)' },
      { name: '瑞幸咖啡', color: 'rgb(36, 88, 199)' },
    ]

    for (const brand of expectedColors) {
      const badge = page.locator('.brand-badge', { hasText: brand.name }).first()
      await expect(badge).toBeVisible()
      await expect.poll(() => badge.evaluate((element) => window.getComputedStyle(element).color)).toBe(brand.color)
    }
  })

  test('P0 boss pages are no longer placeholders', async ({ page }) => {
    await loginAs(page, 'boss')
    const checks = [
      { path: '/profit', text: '合并营业收入' },
      { path: '/profit-table', text: '利润表' },
      { path: '/store-detail', text: '累计营收' },
      { path: '/assistant', text: '门店经营助手' },
      { path: '/data-entry', text: '实时利润核算' },
      { path: '/expenses', text: '新增报销' },
      { path: '/export', text: '全部门店月度利润汇总' },
      { path: '/stores', text: '门店管理' },
    ]
    for (const check of checks) {
      await page.goto(check.path)
      await page.waitForLoadState('networkidle')
      const body = await page.locator('body').innerText()
      expect(body, `${check.path} should contain legacy page content`).toContain(check.text)
      if (check.path === '/assistant') {
        expect(body).toContain('清空对话')
        expect(body).toContain('当前门店')
        expect(body).toContain('月份')
        expect(body).toContain('发送')
      }
      expect(body, `${check.path} should not show placeholder text`).not.toContain('暂未迁移')
      expect(body, `${check.path} should not show phase placeholder`).not.toContain('第一阶段占位')
      expect(body, `${check.path} should not show Vue3 workbench wording`).not.toContain('Vue3 工作台')
    }
  })
})

function normalizeBrandNameForTest(name: string) {
  if (name.includes('茹菓') || name.includes('茹果') || name.includes('苹果')) return '茹菓'
  if (name.includes('霸王')) return '霸王茶姬'
  if (name.includes('瑞幸')) return '瑞幸咖啡'
  return name
}
