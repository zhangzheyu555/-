import type { RouteLocationRaw } from 'vue-router'
import type { RoleTodoItem } from '../api/todos'

type AllowedParam = 'storeId' | 'month' | 'reportId' | 'lossDate' | 'mode' | 'recordId' | 'inspectionId'
  | 'requisitionId' | 'returnId' | 'purchaseOrderId' | 'adjustmentId' | 'itemId' | 'expenseId'

function actionValue(item: RoleTodoItem, key: AllowedParam) {
  const value = item.action?.params?.[key]
  if (typeof value !== 'string' && typeof value !== 'number') return undefined
  const normalized = String(value).trim()
  return normalized || undefined
}

function query(item: RoleTodoItem, keys: AllowedParam[]) {
  return Object.fromEntries(keys
    .map((key) => [key, actionValue(item, key)] as const)
    .filter((entry): entry is [AllowedParam, string] => Boolean(entry[1])))
}

/**
 * Turns the server-issued todo action into a route with an explicit whitelist.  The browser never
 * accepts an arbitrary URL or arbitrary query object from a todo payload; source APIs remain the
 * authority for permission and store/tenant checks after navigation.
 */
export function roleTodoActionRoute(item: RoleTodoItem): RouteLocationRaw | null {
  switch (item.action?.target) {
    case 'daily-loss':
      return { path: '/daily-loss', query: query(item, ['storeId', 'month', 'reportId', 'lossDate', 'mode']) }
    case 'inspect':
      return {
        path: '/operations/inspection/records',
        query: {
          ...query(item, ['storeId', 'month']),
          ...(actionValue(item, 'inspectionId') ? { recordId: actionValue(item, 'inspectionId')! } : {}),
        },
      }
    case 'warehouse':
      return {
        path: '/warehouse/requests',
        query: query(item, ['storeId', 'month', 'requisitionId', 'returnId', 'purchaseOrderId', 'adjustmentId', 'itemId']),
      }
    case 'expense':
      return { path: '/expenses', query: query(item, ['storeId', 'month', 'expenseId']) }
    default:
      return null
  }
}
