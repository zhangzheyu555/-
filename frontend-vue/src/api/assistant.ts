import { apiGet, apiPost } from './http'

export interface AssistantChatRequest {
  message: string
  history?: Array<{
    role: 'user' | 'assistant'
    content: string
  }>
  dataContext?: string
  mode?: 'AUTO' | 'LOCAL' | 'AI'
  storeId?: string
  month?: string
}

export interface AssistantChatResponse {
  question: string
  selectedMode: 'LOCAL' | 'AI'
  selectionReason: string
  localData: AssistantLocalData
  aiAnalysis: AssistantAiAnalysis
  fallbackUsed: boolean
  error: AssistantError | null
}

export interface AssistantLocalMetric {
  key: string
  label: string
  value: number
  unit: 'CNY' | 'PERCENT' | string
  displayValue: string
  changeRate: number | null
  comparison: string
}

export interface AssistantLocalData {
  summary: string
  metrics: AssistantLocalMetric[]
  dataPeriod: string
  dataScope: string
  source: string
  dataVersion: string
  calculationVersion: string
  updatedAt: string
}

export interface AssistantRisk {
  title: string
  evidence: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
}

export interface AssistantPossibleCause {
  cause: string
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  basis: string
}

export interface AssistantAction {
  action: string
  ownerRole: string
  deadline: string
  expectedImpact: string
  verificationMetric: string
}

export interface AssistantAiAnalysis {
  available: boolean
  provider: string
  model: string
  requestId: string
  latencyMs: number
  summary: string
  findings: string[]
  risks: AssistantRisk[]
  possibleCauses: AssistantPossibleCause[]
  actions: AssistantAction[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | ''
  limitations: string[]
}

export interface AssistantError {
  code: string
  message: string
}

export interface AssistantStatus {
  enabled: boolean
  configured: boolean
  provider: string
  model: string
  baseUrlHost: string
  timeout: number
  lastSuccessAt: string | null
  lastErrorCode: string | null
}

export function askAssistant(payload: AssistantChatRequest) {
  return apiPost<AssistantChatResponse, AssistantChatRequest>('/api/assistant/chat', payload)
}

export function getAssistantStatus() {
  return apiGet<AssistantStatus>('/api/assistant/status')
}
