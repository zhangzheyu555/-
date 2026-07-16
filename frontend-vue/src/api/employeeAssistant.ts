import { apiGet, apiPost, apiPut } from './http'

/**
 * Safe availability state from the local adapter. It deliberately contains no upstream address,
 * token, or provider details.
 */
export type EmployeeAssistantServiceState = 'UNCONFIGURED' | 'AUTH_FAILED' | 'UNAVAILABLE' | 'READY'

export interface EmployeeAssistantStatus {
  enabled: boolean
  configured: boolean
  /** Optional only while talking to an older backend that has not yet exposed the explicit state. */
  state?: EmployeeAssistantServiceState
  message?: string
  knowledgeAvailable?: boolean
  canAsk?: boolean
}

export type EmployeeAssistantAnswerSource = 'KNOWLEDGE' | 'ASSISTANT' | 'HUMAN_REQUIRED'

export interface EmployeeAssistantChatRequest {
  sessionId: string
  message: string
}

export interface EmployeeAssistantChatResponse {
  answer: string
  configured: boolean
  requestId?: string
  sessionId?: string
  needsHuman?: boolean
  answerSource?: EmployeeAssistantAnswerSource
  knowledgeId?: number | null
  knowledgeVersion?: number | null
  knowledgeTitle?: string | null
  handoffCategory?: string | null
}

export interface EmployeeAssistantKnowledgeDraft {
  category: string
  title: string
  keywords: string
  standardAnswer: string
}

export interface EmployeeAssistantKnowledge extends EmployeeAssistantKnowledgeDraft {
  id: number
  status: 'DRAFT' | 'PUBLISHED'
  currentVersion: number
  createdAt?: string
  updatedAt?: string
}

export interface EmployeeAssistantKnowledgeVersion extends EmployeeAssistantKnowledgeDraft {
  id: number
  knowledgeId: number
  version: number
  publishAction: 'PUBLISH' | 'ROLLBACK'
  publishedAt?: string
}

export interface EmployeeAssistantHandoff {
  id: string
  storeId?: string | null
  question: string
  category: string
  status: 'OPEN' | 'CLAIMED' | 'IN_PROGRESS' | 'CLOSED' | 'EXPIRED'
  requestedByName?: string | null
  handledBy?: number | null
  handledByName?: string | null
  resolution?: string | null
  createdAt?: string
  claimedAt?: string | null
  respondedAt?: string | null
  closedAt?: string | null
  expiresAt?: string
}

export function getEmployeeAssistantStatus() {
  return apiGet<EmployeeAssistantStatus>('/api/employee-assistant/status')
}

export function askEmployeeAssistant(payload: EmployeeAssistantChatRequest) {
  // The backend budget is capped at 10s; leave a small transport margin, then surface retry/handoff.
  return apiPost<EmployeeAssistantChatResponse, EmployeeAssistantChatRequest>(
    '/api/employee-assistant/chat', payload, { timeout: 11_000 },
  )
}

export function createEmployeeAssistantHandoff(payload: { question: string; storeId?: string }) {
  return apiPost<EmployeeAssistantHandoff, { question: string; storeId?: string }>('/api/employee-assistant/handoffs', payload)
}

export function myEmployeeAssistantHandoffs() {
  return apiGet<EmployeeAssistantHandoff[]>('/api/employee-assistant/handoffs/mine')
}

export function managedEmployeeAssistantHandoffs() {
  return apiGet<EmployeeAssistantHandoff[]>('/api/employee-assistant/handoffs/manage')
}

export function claimEmployeeAssistantHandoff(id: string) {
  return apiPost<EmployeeAssistantHandoff>(`/api/employee-assistant/handoffs/${encodeURIComponent(id)}/claim`)
}

export function replyEmployeeAssistantHandoff(id: string, resolution: string) {
  return apiPost<EmployeeAssistantHandoff, { resolution: string }>(
    `/api/employee-assistant/handoffs/${encodeURIComponent(id)}/reply`, { resolution },
  )
}

export function closeEmployeeAssistantHandoff(id: string, resolution: string) {
  return apiPost<EmployeeAssistantHandoff, { resolution: string }>(
    `/api/employee-assistant/handoffs/${encodeURIComponent(id)}/close`, { resolution },
  )
}

export function submitEmployeeAssistantFeedback(payload: {
  answerSource: EmployeeAssistantAnswerSource
  knowledgeId?: number | null
  knowledgeVersion?: number | null
  helpful: boolean
  reasonCode?: string
}) {
  return apiPost<void, typeof payload>('/api/employee-assistant/feedback', payload)
}

export function employeeAssistantKnowledge() {
  return apiGet<EmployeeAssistantKnowledge[]>('/api/employee-assistant/knowledge')
}

export function createEmployeeAssistantKnowledge(payload: EmployeeAssistantKnowledgeDraft) {
  return apiPost<EmployeeAssistantKnowledge, EmployeeAssistantKnowledgeDraft>('/api/employee-assistant/knowledge', payload)
}

export function updateEmployeeAssistantKnowledge(id: number, payload: EmployeeAssistantKnowledgeDraft) {
  return apiPut<EmployeeAssistantKnowledge, EmployeeAssistantKnowledgeDraft>(
    `/api/employee-assistant/knowledge/${id}`, payload,
  )
}

export function publishEmployeeAssistantKnowledge(id: number) {
  return apiPost<EmployeeAssistantKnowledge>(`/api/employee-assistant/knowledge/${id}/publish`)
}

export function employeeAssistantKnowledgeVersions(id: number) {
  return apiGet<EmployeeAssistantKnowledgeVersion[]>(`/api/employee-assistant/knowledge/${id}/versions`)
}

export function rollbackEmployeeAssistantKnowledge(id: number, version: number) {
  return apiPost<EmployeeAssistantKnowledge>(`/api/employee-assistant/knowledge/${id}/rollback/${version}`)
}
