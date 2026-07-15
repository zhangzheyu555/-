import { apiDelete, apiGet, apiPost, apiPostForm, http } from './http'

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
  bankQuestionId?: number
  questionType: 'SINGLE_CHOICE' | 'TEXT' | 'NUMBER' | 'ESSAY'
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
  departmentName?: string
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
  status: 'NOT_STARTED' | 'ASSIGNED' | 'COMPLETED' | 'OVERDUE' | 'REVIEW_PENDING' | 'RETAKE_PENDING'
  statusLabel: string
  startAt: string
  dueAt: string
  completedAt?: string
  attemptId?: number
  score?: number
  passed?: boolean
  retakeAvailableAt?: string
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

export interface TrainingCourse {
  id: number
  courseCode: string
  title: string
  category?: string
  description?: string
  coverUrl?: string
  durationMinutes: number
  requiredRoleScope?: string
  enabled: boolean
  sortOrder: number
  materialCount: number
  materialIds: number[]
}

export type TrainingCoursePayload = Omit<TrainingCourse, 'id' | 'materialCount'> & { id?: number }

export interface TrainingMaterialRecord {
  id: number
  materialCode: string
  title: string
  category: string
  imageUrls: string[]
  content?: string
  enabled: boolean
  sortOrder: number
  learnedCount: number
}

export type TrainingMaterialPayload = Omit<TrainingMaterialRecord, 'id' | 'learnedCount'> & { id?: number }

export interface TrainingVideo {
  id: number
  videoCode: string
  courseId?: number
  courseTitle?: string
  title: string
  category?: string
  description?: string
  fileName: string
  contentType: string
  fileSize: number
  durationSeconds?: number
  enabled: boolean
  sortOrder: number
  createdAt?: string
  myWatchedSeconds: number
  myLastPosition: number
  myPercent: number
  myCompleted: boolean
}

export interface TrainingVideoUploadPayload {
  file: File
  title?: string
  category?: string
  courseId?: number
  sortOrder?: number
}

export interface TrainingVideoProgress {
  videoId: number
  watchedSeconds: number
  lastPosition: number
  percent: number
  completed: boolean
}

export interface TrainingVideoViewerRow {
  userId: number
  userName: string
  storeId?: string
  storeName?: string
  videoId: number
  videoTitle: string
  videoCategory?: string
  watchedSeconds: number
  percent: number
  completed: boolean
  lastWatchedAt?: string
}

export interface ExamQuestionCategory {
  id: number
  categoryCode: string
  categoryName: string
  description?: string
  enabled: boolean
  sortOrder: number
  questionCount: number
}

export type ExamQuestionCategoryPayload = Omit<ExamQuestionCategory, 'id' | 'questionCount'> & { id?: number }

export interface ExamQuestionBankItem {
  id: number
  questionCode: string
  categoryId?: number
  categoryName?: string
  questionType: 'SINGLE_CHOICE' | 'TEXT' | 'NUMBER' | 'ESSAY'
  questionText: string
  options: string[]
  standardAnswer?: string
  answerAnalysis?: string
  acceptKeywords?: string
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  defaultScore: number
  enabled: boolean
  usedCount: number
}

export type ExamQuestionBankPayload = Omit<ExamQuestionBankItem, 'id' | 'categoryName' | 'usedCount'> & { id?: number }

export interface ExamReviewTask {
  attemptId: number
  assignmentId?: number
  examTitle?: string
  paperName: string
  examineeName: string
  storeId?: string
  storeName?: string
  autoScore: number
  submittedAt: string
  reviewStatus: 'PENDING' | 'REVIEWED' | 'AUTO_GRADED'
}

export interface ExamReviewAnswer {
  answerId: number
  questionId: number
  questionType: string
  questionText: string
  standardAnswer?: string
  userAnswer?: string
  maxScore: number
  awardedScore: number
  correct: boolean
  reviewComment?: string
}

export interface ExamReviewDetail {
  task: ExamReviewTask
  answers: ExamReviewAnswer[]
  reviewNote?: string
}

export interface ExamResultRecord {
  attemptId: number
  assignmentId?: number
  campaignId?: number
  examTitle?: string
  paperName: string
  userId?: number
  examineeName: string
  role: string
  storeId?: string
  storeName?: string
  score: number
  passed: boolean
  violated: boolean
  reviewStatus: string
  submittedAt: string
  reviewedAt?: string
}

export interface ExamWrongQuestion {
  id: number
  attemptId: number
  questionId: number
  paperName: string
  questionType: string
  questionText: string
  standardAnswer?: string
  userAnswer?: string
  answerAnalysis?: string
  mastered: boolean
  createdAt: string
}

export interface ExamEncodingCheck {
  databaseCharset: string
  connectionCharset: string
  suspiciousPaperCount: number
  suspiciousQuestionCount: number
  suspiciousMaterialCount: number
  suspiciousHexSamples: string[]
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

export function getTrainingCourses() {
  return apiGet<TrainingCourse[]>('/api/exam-center/courses')
}

export function saveTrainingCourse(payload: TrainingCoursePayload) {
  return apiPost<TrainingCourse, TrainingCoursePayload>('/api/exam-center/courses', payload)
}

export function getTrainingMaterials() {
  return apiGet<TrainingMaterialRecord[]>('/api/exam-center/materials')
}

export function saveTrainingMaterial(payload: TrainingMaterialPayload) {
  return apiPost<TrainingMaterialRecord, TrainingMaterialPayload>('/api/exam-center/materials', payload)
}

export function getTrainingVideos() {
  return apiGet<TrainingVideo[]>('/api/exam-center/videos')
}

export function uploadTrainingVideo(payload: TrainingVideoUploadPayload, onProgress?: (percent: number) => void) {
  const form = new FormData()
  form.append('file', payload.file, payload.file.name)
  if (payload.title?.trim()) form.append('title', payload.title.trim())
  if (payload.category?.trim()) form.append('category', payload.category.trim())
  if (payload.courseId) form.append('courseId', String(payload.courseId))
  if (payload.sortOrder != null) form.append('sortOrder', String(payload.sortOrder))
  return apiPostForm<TrainingVideo>('/api/exam-center/videos', form, {
    timeout: 300_000,
    onUploadProgress: (event) => {
      if (!onProgress) return
      if (!event.total) return onProgress(0)
      onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
    },
  })
}

export function deleteTrainingVideo(videoId: number) {
  return apiDelete<boolean>(`/api/exam-center/videos/${videoId}`)
}

// 播放请求携带现有登录凭据，视频地址本身不公开且不包含令牌。
export async function fetchTrainingVideoBlob(videoId: number) {
  const response = await http.get<Blob>(`/api/exam-center/videos/${videoId}/content`, {
    responseType: 'blob',
    timeout: 300_000,
  })
  return response.data
}

export function reportTrainingVideoProgress(videoId: number, payload: { positionSeconds: number; durationSeconds: number }) {
  return apiPost<TrainingVideoProgress, typeof payload>(`/api/exam-center/videos/${videoId}/progress`, payload)
}

export function getTrainingVideoProgressReport() {
  return apiGet<TrainingVideoViewerRow[]>('/api/exam-center/videos/progress-report')
}

export function getExamQuestionCategories() {
  return apiGet<ExamQuestionCategory[]>('/api/exam-center/question-categories')
}

export function saveExamQuestionCategory(payload: ExamQuestionCategoryPayload) {
  return apiPost<ExamQuestionCategory, ExamQuestionCategoryPayload>('/api/exam-center/question-categories', payload)
}

export function deleteExamQuestionCategory(categoryId: number) {
  return apiDelete<{ deleted: boolean }>(`/api/exam-center/question-categories/${categoryId}`)
}

export function getExamQuestionBank(params?: { categoryId?: number; keyword?: string }) {
  const search = new URLSearchParams()
  if (params?.categoryId) search.set('categoryId', String(params.categoryId))
  if (params?.keyword?.trim()) search.set('keyword', params.keyword.trim())
  const query = search.toString()
  return apiGet<ExamQuestionBankItem[]>(`/api/exam-center/questions${query ? `?${query}` : ''}`)
}

export function saveExamQuestionBankItem(payload: ExamQuestionBankPayload) {
  return apiPost<ExamQuestionBankItem, ExamQuestionBankPayload>('/api/exam-center/questions', payload)
}

export function getExamReviews() {
  return apiGet<ExamReviewTask[]>('/api/exam-center/reviews')
}

export function getExamReviewDetail(attemptId: number) {
  return apiGet<ExamReviewDetail>(`/api/exam-center/reviews/${attemptId}`)
}

export function submitExamReview(
  attemptId: number,
  payload: { reviewNote?: string; answers: Array<{ answerId: number; awardedScore: number; comment?: string }> },
) {
  return apiPost<ExamReviewDetail, typeof payload>(`/api/exam-center/reviews/${attemptId}`, payload)
}

export function getExamResults() {
  return apiGet<ExamResultRecord[]>('/api/exam-center/results')
}

export function getExamWrongQuestions() {
  return apiGet<ExamWrongQuestion[]>('/api/exam-center/wrong-questions')
}

export function markExamWrongQuestion(wrongId: number, mastered: boolean) {
  return apiPost<{ mastered: boolean }, { mastered: boolean }>(`/api/exam-center/wrong-questions/${wrongId}/mastered`, { mastered })
}

export function getExamEncodingCheck() {
  return apiGet<ExamEncodingCheck>('/api/exam-center/encoding-check')
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
