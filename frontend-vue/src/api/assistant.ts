import { apiPost } from './http'

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
  answer: string
  localAnswer?: string
  deepSeekAnswer?: string | null
  deepSeekAvailable?: boolean
  deepSeekError?: string | null
  resolvedStoreId?: string
  resolvedStoreName?: string
  resolvedMonth?: string
  intent?: string
  aiUsed?: boolean
  blocked?: boolean
  source?: string
  dataSource?: string
  month?: string
  storeScope?: string[]
  warnings?: string[]
  model?: string
  fallback?: boolean
  fallbackReason?: string
  requestId?: string
  generatedAt?: string
  localOnly?: boolean
}

export function askAssistant(payload: AssistantChatRequest) {
  return apiPost<AssistantChatResponse, AssistantChatRequest>('/api/assistant/chat', payload)
}
