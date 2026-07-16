import { apiGet, apiPost } from './http'

// Backend analysis budget ≈ 30s. Frontend timeout = budget + 5s safety margin.
export const ASSISTANT_CHAT_TIMEOUT_MS = 35_000

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
  /**
   * FULL means the model could make a complete, evidence-checked operating analysis.
   * DATA_LIMITED is still a valid response, but only contains conclusions that the
   * available operating data can support plus concrete data-completion actions.
   */
  analysisType?: 'FULL' | 'DATA_LIMITED'
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
  /**
   * Configuration and proven analysis health are deliberately different.  Older
   * backend packages do not include this field; callers must treat a missing value
   * as configured-only rather than claiming the analysis service is healthy.
   */
  state?: 'NOT_CONFIGURED' | 'CONFIGURED' | 'READY' | 'RESPONSE_REJECTED' | 'UPSTREAM_ERROR'
  provider: string
  model: string
  baseUrlHost: string
  timeout: number
  lastSuccessAt: string | null
  lastErrorCode: string | null
}

export function askAssistant(payload: AssistantChatRequest) {
  return apiPost<AssistantChatResponse, AssistantChatRequest>('/api/assistant/chat', payload, {
    timeout: ASSISTANT_CHAT_TIMEOUT_MS,
  })
}

export function getAssistantStatus() {
  return apiGet<AssistantStatus>('/api/assistant/status')
}
