import { expect, type APIRequestContext, type Page } from '@playwright/test'

export const APP_BASE_URL = process.env.E2E_BASE_URL || 'http://127.0.0.1:5173'
export const API_BASE_URL = process.env.E2E_API_URL || 'http://127.0.0.1:8080'

export type RoleKey = 'boss' | 'finance' | 'warehouse' | 'supervisor' | 'operations' | 'store'

export interface RoleConfig {
  key: RoleKey
  username: string
  password: string
  expectedPath: string
  expectedMenus: string[]
  forbiddenMenus: string[]
}

const env = process.env

export const roles: RoleConfig[] = [
  {
    key: 'boss',
    username: env.E2E_BOSS_USERNAME || '',
    password: env.E2E_BOSS_PASSWORD || '',
    expectedPath: '/boss',
    expectedMenus: [
      '今日待办',
      '利润概览',
      '利润表',
      '门店详情',
      '督导巡店',
      '门店经营助手',
      '数据录入',
      '报销栏',
      '数据导出',
      '门店管理',
      '操作日志',
      '平台登录',
      '仓库中心',
      '退出登录',
    ],
    forbiddenMenus: ['老板驾驶舱', '财务工作台', '仓库工作台', '督导工作台'],
  },
  {
    key: 'finance',
    username: env.E2E_FINANCE_USERNAME || '',
    password: env.E2E_FINANCE_PASSWORD || '',
    expectedPath: '/expenses',
    expectedMenus: ['报销栏', '利润表', '员工工资', '数据导出', '门店经营助手'],
    forbiddenMenus: ['今日待办', '老板驾驶舱', '财务工作台', '仓库工作台', '商品档案', '采购入库', '督导工作台', '用户权限'],
  },
  {
    key: 'warehouse',
    username: env.E2E_WAREHOUSE_USERNAME || '',
    password: env.E2E_WAREHOUSE_PASSWORD || '',
    expectedPath: '/warehouse',
    expectedMenus: ['仓库中心', '商品档案', '采购入库', '出入库记录', '配送退货单', '库存预警', '入库记录', '门店经营助手'],
    forbiddenMenus: ['今日待办', '老板驾驶舱', '财务工作台', '员工工资', '督导工作台', '用户权限'],
  },
  {
    key: 'supervisor',
    username: env.E2E_SUPERVISOR_USERNAME || '',
    password: env.E2E_SUPERVISOR_PASSWORD || '',
    expectedPath: '/inspection',
    expectedMenus: ['督导巡店', '巡检记录', '发起巡检', '稽核标准', '门店经营助手'],
    forbiddenMenus: ['今日待办', '老板驾驶舱', '财务工作台', '仓库工作台', '商品档案', '员工工资', '用户权限'],
  },
  {
    key: 'operations',
    username: env.E2E_OPERATIONS_USERNAME || '',
    password: env.E2E_OPERATIONS_PASSWORD || '',
    expectedPath: '/operations',
    expectedMenus: ['运营中心', '数据分析', '新人培训', '培训考试', '店铺盘存', '饿了么订单', '数据健康', '平台账号', '门店经营助手'],
    forbiddenMenus: ['今日待办', '老板驾驶舱', '财务工作台', '仓库工作台', '确认收货', '员工工资'],
  },
  {
    key: 'store',
    username: env.E2E_STORE_USERNAME || '',
    password: env.E2E_STORE_PASSWORD || '',
    expectedPath: '/warehouse',
    expectedMenus: ['本店库存', '向公司仓库叫货', '我的叫货单', '确认收货', '配送退货', '本店数据', '门店经营助手'],
    forbiddenMenus: ['今日待办', '老板驾驶舱', '财务工作台', '仓库工作台', '商品档案', '采购入库', '库存预警', '用户权限'],
  },
]

export const roleByKey = Object.fromEntries(roles.map((role) => [role.key, role])) as Record<RoleKey, RoleConfig>

export const forbiddenTodoText = [
  '加入叫货单',
  '提交叫货',
  '确认入库',
  '采购到货入库',
  '设置预警',
  '新建产品',
  '审核通过',
  '驳回',
  '工资编辑',
  '巡店录入',
]

export const forbiddenVisibleTerms = [
  'RISK',
  'PENDING',
  'DONE',
  'RESOLVE',
  'CLOSE',
  'SUBMITTED',
  'APPROVED',
  'SHIPPED',
  'RECEIVED',
  'TODO_DONE',
  'sourceModule',
  'todo_id',
  'batch_id',
  'movement_type',
  'legacy_kv',
]

export const forbiddenBusinessStorageKeyParts = [
  'todo',
  'expense',
  'warehouse',
  'inventory',
  'requisition',
  'return',
  'inspection',
  'salary',
  'profit',
  'operation',
  'log',
]

export async function loginApi(username: string, password: string) {
  if (!username.trim() || !password) {
    throw new Error('缺少 E2E 受控账号或密码，请设置对应的 E2E_*_USERNAME 和 E2E_*_PASSWORD 环境变量。')
  }
  const response = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ username, password }),
  })
  const json = await response.json().catch(() => ({}))
  if (!response.ok || json.success === false) {
    throw new Error(`login failed for ${username}: ${response.status} ${json.message || ''}`)
  }
  return json.data || json
}

export async function seedAuth(page: Page, session: { token: string; user: unknown }) {
  await page.goto('/login')
  await page.evaluate((payload) => {
    localStorage.setItem('ai_profit_vue_token', payload.token)
    localStorage.setItem('ai_profit_vue_user', JSON.stringify(payload.user))
  }, session)
}

export async function loginAs(page: Page, roleKey: RoleKey) {
  const role = roleByKey[roleKey]
  const session = await loginApi(role.username, role.password)
  await seedAuth(page, session)
  return session
}

export async function apiFetchAs(
  request: APIRequestContext,
  roleKey: RoleKey,
  path: string,
  options: { method?: string; data?: unknown } = {},
) {
  const role = roleByKey[roleKey]
  const session = await loginApi(role.username, role.password)
  return request.fetch(`${API_BASE_URL}${path}`, {
    method: options.method || 'GET',
    headers: {
      Authorization: `Bearer ${session.token}`,
      'Content-Type': 'application/json; charset=utf-8',
    },
    data: options.data,
  })
}

export async function expectDefaultRoute(page: Page, expectedPath: string) {
  await page.waitForLoadState('networkidle')
  await expect.poll(() => new URL(page.url()).pathname).toBe(expectedPath)
}

export async function expectNoWholePageOverflow(page: Page, context: string) {
  const sizes = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(sizes.scrollWidth, `${context} should not overflow page`).toBeLessThanOrEqual(sizes.clientWidth + 1)
}
