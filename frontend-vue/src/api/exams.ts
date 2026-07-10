import { apiGet, apiPost, http } from './http'

export interface ExamPaperSummary {
  id: number
  paperCode: string
  paperName: string
  roleScope?: string
  passScore: number
  enabled: boolean
  questionCount: number
}

export interface ExamQuestionInput {
  questionType: 'SINGLE_CHOICE' | 'TEXT' | 'NUMBER'
  questionText: string
  options: string[]
  standardAnswer: string
  acceptKeywords?: string
  score: number
}

export interface ExamQuestionEditor extends ExamQuestionInput {
  id: number
  sortOrder: number
}

export interface ExamPaperEditor {
  id: number
  paperCode: string
  paperName: string
  roleScope?: string
  passScore: number
  enabled: boolean
  questions: ExamQuestionEditor[]
}

export interface ExamPaperSavePayload {
  id?: number
  paperCode?: string
  paperName: string
  roleScope?: string
  passScore: number
  enabled: boolean
  questions: ExamQuestionInput[]
}

export interface ExamCandidate {
  userId: number
  displayName: string
  role: string
  roleLabel: string
  storeId: string
  storeName: string
}

export interface ExamCampaign {
  id: number
  paperId: number
  paperName: string
  title: string
  status: string
  statusLabel: string
  startAt: string
  dueAt: string
  targetRoles?: string
  assignedCount: number
  completedCount: number
  completionRate: number
  passedCount: number
  passRate: number
  overdueCount: number
  averageScore: number
  publishedAt: string
}

export interface ExamAssignment {
  id: number
  campaignId: number
  paperId: number
  examTitle: string
  paperName: string
  userId: number
  examineeName: string
  examineeRole: string
  storeId: string
  storeName: string
  status: 'NOT_STARTED' | 'ASSIGNED' | 'COMPLETED' | 'OVERDUE'
  statusLabel: string
  startAt: string
  dueAt: string
  completedAt?: string
  attemptId?: number
  score?: number
  passed?: boolean
}

export interface ExamCenterOverview {
  accessMode: 'COMPANY' | 'STORE' | 'SELF'
  canManage: boolean
  canExport: boolean
  papers: ExamPaperSummary[]
  campaigns: ExamCampaign[]
  assignments: ExamAssignment[]
  candidates: ExamCandidate[]
}

export interface ExamCampaignDetail {
  campaign: ExamCampaign
  assignments: ExamAssignment[]
}

export interface ExamPublishPayload {
  paperId: number
  title: string
  startAt: string
  dueAt: string
  storeIds: string[]
  targetRoles: string[]
  userIds: number[]
}

export interface ExamQuestion {
  id: number
  questionType: string
  questionText: string
  options: string[]
  score: number
  sortOrder: number
}

export interface ExamPaper {
  id: number
  paperCode: string
  paperName: string
  roleScope?: string
  passScore: number
  enabled: boolean
  questions: ExamQuestion[]
}

export interface ExamAttempt {
  id: number
  paperId: number
  paperName: string
  examineeName: string
  examineeRole: string
  storeId?: string
  storeName?: string
  score: number
  passed: boolean
  violated: boolean
  submittedBy?: number
  submittedAt: string
}

export interface BossExamRiskStore {
  storeId: string
  storeName: string
  assignedCount: number
  completedCount: number
  completionRate: number
  passedCount: number
  passRate: number
  overdueCount: number
  averageScore: number
  risks: string[]
}

export interface BossExamSummary {
  activeExamCount: number
  assignedCount: number
  completedCount: number
  completionRate: number
  passedCount: number
  passRate: number
  overdueCount: number
  averageScore: number
  riskStores: BossExamRiskStore[]
}

export function getExamCenterOverview() {
  return apiGet<ExamCenterOverview>('/api/exam-center/overview')
}

export function getExamPaperForEdit(paperId: number) {
  return apiGet<ExamPaperEditor>(`/api/exam-center/papers/${paperId}`)
}

export function saveExamPaper(payload: ExamPaperSavePayload) {
  return apiPost<ExamPaperEditor, ExamPaperSavePayload>('/api/exam-center/papers', payload)
}

export function publishExam(payload: ExamPublishPayload) {
  return apiPost<ExamCampaignDetail, ExamPublishPayload>('/api/exam-center/campaigns', payload)
}

export function getExamCampaign(campaignId: number) {
  return apiGet<ExamCampaignDetail>(`/api/exam-center/campaigns/${campaignId}`)
}

export function getAssignedExamPaper(assignmentId: number) {
  return apiGet<ExamPaper>(`/api/exam-center/assignments/${assignmentId}/paper`)
}

export function submitAssignedExam(
  assignmentId: number,
  payload: { violated: boolean; answers: Array<{ questionId: number; userAnswer: string }> },
) {
  return apiPost<ExamAttempt, typeof payload>(`/api/exam-center/assignments/${assignmentId}/submit`, payload)
}

export function getBossExamSummary() {
  return apiGet<BossExamSummary>('/api/boss/exam-summary')
}

export async function downloadExamResults(campaignId: number, title: string) {
  const response = await http.get<Blob>(`/api/exam-center/campaigns/${campaignId}/results.csv`, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = url
  link.download = `${safeFilename(title || '考试成绩')}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function safeFilename(value: string) {
  return value.replace(/[\\/:*?"<>|]/g, '-').slice(0, 80)
}
