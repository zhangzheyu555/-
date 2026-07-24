import { expect, test, type Page } from '@playwright/test'
import { expectNoWholePageOverflow, seedAuth } from './auth.setup'

const availableDocument = {
  id: 81,
  title: '门店交接班规范',
  category: '门店运营',
  originalFileName: '门店交接班规范.docx',
  fileSize: 4096,
  publishedAt: '2026-07-24T10:30:00',
  updatedAt: '2026-07-24T10:30:00',
}

const stores = [
  { id: 'store-a', code: 'A001', name: '人民路店', brandId: 1, brandName: '测试品牌', regionCode: 'JINGZHOU', area: '荆州', status: 'ACTIVE' },
  { id: 'store-c', code: 'C003', name: '万达店', brandId: 1, brandName: '测试品牌', regionCode: 'WUHAN', area: '武汉', status: 'ACTIVE' },
]

test('普通账号进入知识库即可看到自己有权查看的已发布资料', async ({ page }) => {
  let storeRequests = 0
  await mockKnowledgeBase(page, regularUser(), {
    onStoreRequest: () => { storeRequests += 1 },
  })
  await page.goto('/knowledge-base')

  await expect(page.getByRole('heading', { name: '我可查看的资料' })).toBeVisible()
  await expect(page.getByText('门店交接班规范', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '刷新资料' })).toBeVisible()
  expect(storeRequests).toBe(0)
  await expect(page.getByRole('heading', { name: '上传资料' })).toHaveCount(0)
})

test('资料目录支持手动刷新和窗口重新聚焦刷新', async ({ page }) => {
  await mockKnowledgeBase(page, regularUser())
  let availableRequests = 0
  await page.route('**/api/knowledge-base/documents/available', async (route) => {
    availableRequests += 1
    await route.fulfill(json([{
      ...availableDocument,
      title: `门店交接班规范 v${availableRequests}`,
    }]))
  })
  await page.goto('/knowledge-base')

  await expect(page.getByText('门店交接班规范 v1', { exact: true })).toBeVisible()
  const requestsAfterInitialLoad = availableRequests
  await page.getByRole('button', { name: '刷新资料' }).click()
  await expect.poll(() => availableRequests).toBe(requestsAfterInitialLoad + 1)
  await expect(page.getByText(`门店交接班规范 v${availableRequests}`, { exact: true })).toBeVisible()

  const requestsBeforeFocus = availableRequests
  await page.evaluate(() => window.dispatchEvent(new Event('focus')))
  await expect.poll(() => availableRequests).toBe(requestsBeforeFocus + 1)
  await expect(page.getByText(`门店交接班规范 v${availableRequests}`, { exact: true })).toBeVisible()
})

test('老板配置指定门店时通过可搜索复选框选择门店', async ({ page }) => {
  let storeRequestUrl = ''
  await mockKnowledgeBase(page, bossUser(), {
    onStoreRequest: (url) => { storeRequestUrl = url.toString() },
  })
  await page.goto('/knowledge-base')

  await page.getByLabel('适用范围').selectOption('STORE')
  await expect.poll(() => storeRequestUrl).toContain('knowledgeBaseScope=true')
  await expect(page.getByRole('checkbox', { name: /人民路店.*A001.*荆州/ })).toBeVisible()
  await expect(page.getByRole('checkbox', { name: /万达店.*C003.*武汉/ })).toBeVisible()
  await expect(page.getByText('已选择 0 家门店')).toBeVisible()
  await expect(page.getByRole('button', { name: '全选当前结果' })).toBeVisible()
  await expect(page.getByRole('button', { name: '清空' })).toBeVisible()
})

test('指定门店支持搜索、全选、清空，并按门店 ID 重复提交 multipart 字段', async ({ page }) => {
  await mockKnowledgeBase(page, bossUser())
  let uploadBody = ''
  await page.route('**/api/knowledge-base/documents', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    uploadBody = route.request().postData() || ''
    await route.fulfill(json(uploadedDocument('DRAFT')))
  })
  await page.goto('/knowledge-base')

  await page.getByLabel('适用范围').selectOption('STORE')
  await page.getByLabel('搜索门店').fill('武汉')
  await expect(page.getByRole('checkbox', { name: /人民路店/ })).toHaveCount(0)
  await page.getByRole('button', { name: '全选当前结果' }).click()
  await expect(page.getByText('已选择 1 家门店')).toBeVisible()
  await page.getByRole('button', { name: '清空' }).click()
  await expect(page.getByText('已选择 0 家门店')).toBeVisible()

  await page.getByLabel('搜索门店').fill('')
  await page.getByRole('checkbox', { name: /人民路店/ }).check()
  await page.getByRole('checkbox', { name: /万达店/ }).check()
  await page.getByLabel('适用范围').selectOption('TENANT')
  await page.getByLabel('适用范围').selectOption('STORE')
  await expect(page.getByText('已选择 0 家门店')).toBeVisible()
  await page.getByRole('checkbox', { name: /人民路店/ }).check()
  await page.getByRole('checkbox', { name: /万达店/ }).check()
  await page.getByLabel('资料文件').setInputFiles({
    name: '指定门店规范.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('knowledge-base-store-scope'),
  })
  await page.getByRole('button', { name: '仅保存草稿' }).click()

  await expect.poll(() => uploadBody).not.toBe('')
  expect(uploadBody.match(/name="storeScopes"/g)).toHaveLength(2)
  expect(uploadBody).toContain('name="storeScopes"\r\n\r\nstore-a')
  expect(uploadBody).toContain('name="storeScopes"\r\n\r\nstore-c')
  expect(uploadBody).toContain('name="publishNow"\r\n\r\nfalse')
  await expect(page.getByRole('status')).toContainText('已保存为草稿，普通账号暂不可见')
})

test('管理账号可明确选择上传并发布或仅保存草稿', async ({ page }) => {
  await mockKnowledgeBase(page, bossUser())
  await page.goto('/knowledge-base')

  await expect(page.getByRole('button', { name: '上传并发布' })).toBeVisible()
  await expect(page.getByRole('button', { name: '仅保存草稿' })).toBeVisible()
  await expect(page.getByText(/单个文件不超过 50MB/)).toBeVisible()
})

test('全企业上传并发布需二次确认并提交 publishNow=true', async ({ page }) => {
  await mockKnowledgeBase(page, bossUser())
  let uploadBody = ''
  let confirmation = ''
  await page.route('**/api/knowledge-base/documents', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    uploadBody = route.request().postData() || ''
    await route.fulfill(json(uploadedDocument('PUBLISHED')))
  })
  await page.goto('/knowledge-base')
  await page.getByLabel('资料文件').setInputFiles({
    name: '全企业规范.doc',
    mimeType: 'application/msword',
    buffer: Buffer.from('knowledge-base-tenant-scope'),
  })
  page.once('dialog', async (dialog) => {
    confirmation = dialog.message()
    await dialog.accept()
  })

  await page.getByRole('button', { name: '上传并发布' }).click()

  await expect.poll(() => confirmation).toContain('发布到全企业')
  await expect.poll(() => uploadBody).not.toBe('')
  expect(uploadBody).toContain('name="visibility"\r\n\r\nTENANT')
  expect(uploadBody).toContain('name="publishNow"\r\n\r\ntrue')
  await expect(page.getByRole('status')).toContainText('已发布，符合范围的账号现在可以查看')
})

test('请求立即发布但后端仍返回草稿时不得误报已发布', async ({ page }) => {
  await mockKnowledgeBase(page, bossUser())
  await page.route('**/api/knowledge-base/documents', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    await route.fulfill(json(uploadedDocument('DRAFT')))
  })
  await page.goto('/knowledge-base')
  await page.getByLabel('资料文件').setInputFiles({
    name: '兼容发布.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('publish-status-mismatch'),
  })
  page.once('dialog', async (dialog) => dialog.accept())

  await page.getByRole('button', { name: '上传并发布' }).click()

  await expect(page.getByRole('alert')).toContainText('已保存为草稿，但未完成发布')
  await expect(page.getByRole('status')).toHaveCount(0)
})

test('全企业草稿从资料管理发布时同样需要二次确认', async ({ page }) => {
  await mockKnowledgeBase(page, bossUser())
  let confirmation = ''
  let publishRequests = 0
  await page.route((url) => url.pathname === '/api/knowledge-base/documents', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill(json([uploadedDocument('DRAFT')]))
      return
    }
    await route.fallback()
  })
  await page.route((url) => url.pathname === '/api/knowledge-base/documents/82/publish', async (route) => {
    publishRequests += 1
    await route.fulfill(json(uploadedDocument('PUBLISHED')))
  })
  await page.goto('/knowledge-base')
  page.once('dialog', async (dialog) => {
    confirmation = dialog.message()
    await dialog.accept()
  })

  await page.getByRole('button', { name: '立即发布' }).click()

  await expect.poll(() => confirmation).toContain('发布到全企业')
  expect(publishRequests).toBe(1)
})

test('发布完成时即使目录旧请求仍在进行也会再次拉取最新资料', async ({ page }) => {
  await mockKnowledgeBase(page, bossUser())
  let availableRequests = 0
  let releaseInitialRequest: (() => void) | undefined
  const initialRequestBlocked = new Promise<void>((resolve) => {
    releaseInitialRequest = resolve
  })
  await page.route('**/api/knowledge-base/documents/available', async (route) => {
    availableRequests += 1
    if (availableRequests === 1) {
      await initialRequestBlocked
      await route.fulfill(json([{ ...availableDocument, title: '旧目录资料' }]))
      return
    }
    await route.fulfill(json([{ ...availableDocument, title: '发布后的最新资料' }]))
  })
  await page.route((url) => url.pathname === '/api/knowledge-base/documents', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill(json([uploadedDocument('DRAFT')]))
      return
    }
    await route.fallback()
  })
  await page.route((url) => url.pathname === '/api/knowledge-base/documents/82/publish', async (route) => {
    releaseInitialRequest?.()
    await route.fulfill(json(uploadedDocument('PUBLISHED')))
  })
  await page.goto('/knowledge-base')
  page.once('dialog', async (dialog) => dialog.accept())

  await page.getByRole('button', { name: '立即发布' }).click()

  await expect.poll(() => availableRequests).toBe(2)
  await expect(page.getByText('发布后的最新资料', { exact: true })).toBeVisible()
})

test('非老板或督导即使会话误含管理权限也不显示管理区', async ({ page }) => {
  await mockKnowledgeBase(page, {
    ...regularUser(),
    permissions: ['knowledge_base.search', 'knowledge_base.manage'],
  })
  await page.goto('/knowledge-base')

  await expect(page.getByRole('heading', { name: '上传资料' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '上传并发布' })).toHaveCount(0)
})

test('知识库目录和门店多选在移动端没有整页横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockKnowledgeBase(page, bossUser())
  await page.goto('/knowledge-base')
  await page.getByLabel('适用范围').selectOption('STORE')

  await expect(page.getByRole('checkbox', { name: /人民路店/ })).toBeVisible()
  await expectNoWholePageOverflow(page, '知识库移动端门店多选')
})

async function mockKnowledgeBase(
  page: Page,
  user: ReturnType<typeof regularUser>,
  options: { onStoreRequest?: (url: URL) => void } = {},
) {
  await page.route('**/api/auth/me', async (route) => route.fulfill(json(user)))
  await page.route('**/api/knowledge-base/documents/available', async (route) => route.fulfill(json([availableDocument])))
  await page.route('**/api/knowledge-base/documents', async (route) => {
    if (route.request().method() === 'GET') await route.fulfill(json([]))
    else await route.fallback()
  })
  await page.route((url) => url.pathname === '/api/stores', async (route) => {
    options.onStoreRequest?.(new URL(route.request().url()))
    await route.fulfill(json(stores))
  })
  await seedAuth(page, { token: 'e2e-knowledge-base-token', user })
}

function uploadedDocument(status: 'DRAFT' | 'PUBLISHED') {
  return {
    id: 82,
    title: '知识库测试资料',
    category: '门店运营',
    originalFileName: '知识库测试资料.txt',
    contentType: 'text/plain',
    fileSize: 24,
    visibility: 'TENANT',
    status,
    roleScopes: [],
    storeScopes: [],
    parsedCharCount: 24,
    chunkCount: 1,
    createdBy: 901,
    publishedBy: status === 'PUBLISHED' ? 901 : null,
    createdAt: '2026-07-24T11:00:00',
    updatedAt: '2026-07-24T11:00:00',
    publishedAt: status === 'PUBLISHED' ? '2026-07-24T11:00:00' : null,
  }
}

function regularUser() {
  return {
    id: 902,
    tenantId: 1,
    tenantName: 'E2E 租户',
    displayName: 'E2E 财务',
    role: 'FINANCE',
    roleLabel: '财务',
    storeScope: ['all'],
    permissions: ['knowledge_base.search'],
    dataScopes: { STORE: { mode: 'ALL', storeIds: [], warehouseIds: [] } },
    dataScope: { mode: 'ALL', storeIds: [], warehouseIds: [] },
    boundStoreId: null,
    boundStoreName: null,
    brandId: null,
    brandName: null,
    defaultWorkspace: '/finance',
    permissionVersion: 1,
  }
}

function bossUser() {
  return {
    ...regularUser(),
    id: 901,
    displayName: 'E2E 老板',
    role: 'BOSS',
    roleLabel: '老板',
    permissions: ['knowledge_base.search', 'knowledge_base.manage'],
    defaultWorkspace: '/boss',
  }
}

function json(data: unknown) {
  return { contentType: 'application/json', body: JSON.stringify({ success: true, data }) }
}
