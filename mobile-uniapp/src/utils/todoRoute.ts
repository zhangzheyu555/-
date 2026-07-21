import type { RoleTodoItem } from '@/types/business'

const TARGET_ROUTES: Record<string, string> = {
  inspect: '/pkg-inspection/inspection/index',
  inspection: '/pkg-inspection/inspection/index',
  rectification: '/pkg-inspection/rectification/index',
  report: '/pkg-summary/index',
  exam: '/pkg-learning/exam/index',
  learning: '/pkg-learning/learning/index',
  expense: '/pkg-finance/expenses/index',
  salary: '/pkg-finance/salary/index',
  'daily-loss': '/pkg-store/daily-loss/index',
}

export function routeForTodo(todo: RoleTodoItem, role: string): string {
  const target = String(todo.action?.target || '').trim().toLowerCase()
  const base = target === 'warehouse'
    ? (String(role).toUpperCase() === 'STORE_MANAGER' ? '/pkg-store/requisition/index' : '/pkg-warehouse/index')
    : TARGET_ROUTES[target] || routeForSource(todo.sourceModule, role)
  return appendParams(base, todo.action?.params)
}

function routeForSource(sourceModule: string | undefined, role: string): string {
  const source = String(sourceModule || '').trim()
  if (source.includes('巡店') || source.includes('巡检')) return '/pkg-inspection/inspection/index'
  if (source.includes('整改')) return '/pkg-inspection/rectification/index'
  if (source.includes('仓库') || source.includes('叫货') || source.includes('库存')) {
    return String(role).toUpperCase() === 'STORE_MANAGER' ? '/pkg-store/requisition/index' : '/pkg-warehouse/index'
  }
  if (source.includes('考试')) return '/pkg-learning/exam/index'
  if (source.includes('培训')) return '/pkg-learning/learning/index'
  if (source.includes('报销')) return '/pkg-finance/expenses/index'
  if (source.includes('工资')) return '/pkg-finance/salary/index'
  if (source.includes('利润')) return '/pkg-summary/index'
  return '/pages/apps/index'
}

function appendParams(path: string, params?: Record<string, unknown>): string {
  if (!params) return path
  const query = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&')
  return query ? `${path}?${query}` : path
}
