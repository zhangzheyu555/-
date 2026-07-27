import type {
  KnowledgeBaseRelationType,
  KnowledgeBaseVisibility,
} from '../../api/knowledgeBase'

export interface KnowledgeBaseUploadFormModel {
  title: string
  category: string
  visibility: KnowledgeBaseVisibility
  roleScopes: string[]
  storeScopesText: string
  topicMode: 'NEW' | 'EXISTING'
  topicId: number | null
  topicName: string
  relationType: KnowledgeBaseRelationType
  predecessorDocumentId: number | null
}
