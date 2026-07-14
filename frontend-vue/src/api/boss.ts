import { apiGet, apiPost } from './http'
import type { BossTodoDashboard, RoleTodoItem, RoleTodoResponse } from './todos'

export { getBossTodoDashboard } from './todos'

export type BossDashboardResponse = BossTodoDashboard

export interface BossTodoActionPayload {
  note: string
  attachments?: unknown[]
}

export function getBossTodos(params: { includeDone?: boolean; limit?: number } = {}) {
  const includeDone = params.includeDone ?? true
  const limit = params.limit ?? 120
  return apiGet<RoleTodoResponse>(`/api/boss/todos?includeDone=${includeDone}&limit=${limit}`)
}

export function resolveBossTodo(todoId: string, payload: BossTodoActionPayload) {
  return apiPost<RoleTodoItem, BossTodoActionPayload>(`/api/boss/todos/${encodeURIComponent(todoId)}/resolve`, payload)
}

export function closeBossTodo(todoId: string, payload: BossTodoActionPayload) {
  return apiPost<RoleTodoItem, BossTodoActionPayload>(`/api/boss/todos/${encodeURIComponent(todoId)}/close`, payload)
}
