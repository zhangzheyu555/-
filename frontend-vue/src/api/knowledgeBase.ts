import { http, apiGet, apiPost, apiPostForm } from './http'
import { downloadBlob } from './reports'

export type KnowledgeBaseVisibility = 'TENANT' | 'ROLE' | 'STORE'
export type KnowledgeBaseStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type KnowledgeBaseRelationType = 'ORIGINAL' | 'REPLACES' | 'SUPPLEMENTS'

export interface KnowledgeBaseDocument {
  id: number
  topicId: number
  topicName: string
  versionNo: number
  relationType: KnowledgeBaseRelationType
  predecessorDocumentId: number | null
  title: string
  category: string
  originalFileName: string
  contentType: string
  fileSize: number
  visibility: KnowledgeBaseVisibility
  status: KnowledgeBaseStatus
  roleScopes: string[]
  storeScopes: string[]
  parsedCharCount: number
  chunkCount: number
  createdBy: number
  publishedBy: number | null
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

export interface KnowledgeBaseSearchResult {
  documentId: number
  topicId: number
  topicName: string
  versionNo: number
  title: string
  category: string
  sourceLocator: string
  excerpt: string
  score: number
}

export interface KnowledgeBaseTopicSearchResult {
  topicId: number
  topicName: string
  summary: string
  sources: KnowledgeBaseSearchResult[]
}

export interface KnowledgeBaseSearchResponse {
  query: string
  summary: string
  topics: KnowledgeBaseTopicSearchResult[]
  results: KnowledgeBaseSearchResult[]
}

export interface KnowledgeBaseUploadPayload {
  file: File
  title?: string
  category: string
  visibility: KnowledgeBaseVisibility
  roleScopes?: string[]
  storeScopes?: string[]
  topicId?: number
  topicName?: string
  relationType?: KnowledgeBaseRelationType
  predecessorDocumentId?: number
}

export function searchKnowledgeBase(query: string, limit = 5) {
  return apiGet<KnowledgeBaseSearchResult[]>(
    `/api/knowledge-base/search?q=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`,
  )
}

export function searchKnowledgeBaseSummary(query: string, limit = 5) {
  return apiGet<KnowledgeBaseSearchResponse>(
    `/api/knowledge-base/search/summary?q=${encodeURIComponent(query)}&limit=${encodeURIComponent(String(limit))}`,
  )
}

export function knowledgeBaseDocuments() {
  return apiGet<KnowledgeBaseDocument[]>('/api/knowledge-base/documents')
}

export function uploadKnowledgeBaseDocument(payload: KnowledgeBaseUploadPayload) {
  const form = new FormData()
  form.append('file', payload.file, payload.file.name)
  if (payload.title?.trim()) form.append('title', payload.title.trim())
  form.append('category', payload.category.trim())
  form.append('visibility', payload.visibility)
  payload.roleScopes?.forEach((role) => form.append('roleScopes', role))
  payload.storeScopes?.forEach((storeId) => form.append('storeScopes', storeId))
  if (payload.topicId) form.append('topicId', String(payload.topicId))
  if (payload.topicName?.trim()) form.append('topicName', payload.topicName.trim())
  if (payload.relationType) form.append('relationType', payload.relationType)
  if (payload.predecessorDocumentId) {
    form.append('predecessorDocumentId', String(payload.predecessorDocumentId))
  }
  return apiPostForm<KnowledgeBaseDocument>('/api/knowledge-base/documents', form, { timeout: 60_000 })
}

export function publishKnowledgeBaseDocument(id: number) {
  return apiPost<KnowledgeBaseDocument>(`/api/knowledge-base/documents/${encodeURIComponent(String(id))}/publish`)
}

export function archiveKnowledgeBaseDocument(id: number) {
  return apiPost<KnowledgeBaseDocument>(`/api/knowledge-base/documents/${encodeURIComponent(String(id))}/archive`)
}

export async function downloadKnowledgeBaseDocument(id: number, fileName: string) {
  const response = await http.get(`/api/knowledge-base/documents/${encodeURIComponent(String(id))}/download`, {
    responseType: 'blob',
  })
  const responseContentType = response.headers['content-type']
  const contentType = typeof responseContentType === 'string' ? responseContentType : 'application/octet-stream'
  const blob = new Blob([response.data], { type: contentType })
  downloadBlob(blob, fileName)
}
