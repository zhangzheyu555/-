import type { RouteLocationRaw } from 'vue-router'
import type { RoleTodoAction, RoleTodoItem } from '../api/todos'

type AllowedParam = 'storeId' | 'warehouseId' | 'month' | 'reportId' | 'lossDate' | 'mode' | 'recordId' | 'inspectionId'
  | 'requisitionId' | 'returnId' | 'purchaseOrderId' | 'adjustmentId' | 'itemId' | 'expenseId'
  | 'salaryId' | 'storageKey'

type RoleTodoNavigationSource = Pick<RoleTodoItem, 'action'>

function actionValue(action: RoleTodoAction | undefined, key: AllowedParam) {
  const value = action?.params?.[key]
  if (typeof value !== 'string' && typeof value !== 'number') return undefined
  const normalized = String(value).trim()
  return normalized || undefined
}

function query(action: RoleTodoAction | undefined, keys: AllowedParam[]) {
  return Object.fromEntries(keys
    .map((key) => [key, actionValue(action, key)] as const)
    .filter((entry): entry is [AllowedParam, string] => Boolean(entry[1])))
}

function warehouseActionRoute(action: RoleTodoAction): RouteLocationRaw {
  const commonQuery = query(action, ['storeId', 'warehouseId', 'month'])
  const requisitionId = actionValue(action, 'requisitionId')
  const returnId = actionValue(action, 'returnId')
  const purchaseOrderId = actionValue(action, 'purchaseOrderId')
  const adjustmentId = actionValue(action, 'adjustmentId')
  const itemId = actionValue(action, 'itemId')

  if (returnId) {
    return { path: '/warehouse/returns', query: { ...commonQuery, returnId } }
  }
  if (purchaseOrderId) {
    return { path: '/warehouse/purchase', query: { ...commonQuery, purchaseOrderId } }
  }
  if (requisitionId) {
    return { path: '/warehouse/requests', query: { ...commonQuery, requisitionId } }
  }
  if (itemId || adjustmentId) {
    return {
      path: '/warehouse/alerts',
      query: {
        ...commonQuery,
        ...(itemId ? { itemId } : {}),
        ...(adjustmentId ? { adjustmentId } : {}),
      },
    }
  }
  return { path: '/warehouse', query: commonQuery }
}

/**
 * Turns the server-issued todo action into a route with an explicit whitelist.  The browser never
 * accepts an arbitrary URL or arbitrary query object from a todo payload; source APIs remain the
 * authority for permission and store/tenant checks after navigation.
 */
export function roleTodoActionRoute(item: RoleTodoNavigationSource): RouteLocationRaw | null {
  const action = item.action
  switch (action?.target) {
    case 'report': {
      const reportQuery = query(action, ['storeId', 'month', 'mode'])
      if (reportQuery.storeId && !reportQuery.mode) reportQuery.mode = 'single'
      return { path: '/profit-table', query: reportQuery }
    }
    case 'daily-loss':
      return { path: '/daily-loss', query: query(action, ['storeId', 'month', 'reportId', 'lossDate', 'mode']) }
    case 'inspect':
      return {
        path: '/operations/inspection/records',
        query: {
          ...query(action, ['storeId', 'month']),
          ...(actionValue(action, 'inspectionId') ? { recordId: actionValue(action, 'inspectionId')! } : {}),
        },
      }
    case 'warehouse':
      return warehouseActionRoute(action)
    case 'expense':
      return { path: '/expenses', query: query(action, ['storeId', 'month', 'expenseId']) }
    case 'salary':
      return { path: '/finance/salary', query: query(action, ['storeId', 'month', 'salaryId']) }
    case 'dataHealth':
      return {
        path: '/data-entry',
        query: {
          import: '1',
          ...query(action, ['storageKey']),
        },
      }
    default:
      return null
  }
}
