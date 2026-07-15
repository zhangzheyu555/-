import { ApiError, apiGet, apiPost, apiPostForm } from './http'

/**
 * 整改闭环只传递后端已授权的业务字段。页面不接触附件存储路径，
 * 也不会根据图片文件名推断条款或整改状态。
 */
export type InspectionRectificationStatus =
  | 'PENDING_SUBMISSION'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | string

/** 上传接口返回的安全元数据；不携带存储路径，也不提供匿名访问链接。 */
export interface InspectionRectificationEvidenceUpload {
  attachmentId: number
  fileName: string
  contentType?: string
  fileSize?: number
}

export interface InspectionRectificationTask {
  recordId: string
  storeId: string
  storeName?: string
  inspectionDate?: string
  status: InspectionRectificationStatus
  statusLabel?: string
  requirement?: string
  /** 已关联整改证据的附件 ID；预览必须继续走受认证附件接口。 */
  evidenceAttachmentIds: number[]
  managerNote?: string
  reviewNote?: string
  updatedAt?: string
}

export interface InspectionRectificationListResponse {
  items: InspectionRectificationTask[]
}

export interface SubmitInspectionRectificationPayload {
  note: string
  attachmentIds: number[]
}

export interface ReviewInspectionRectificationPayload {
  decision: 'APPROVED' | 'REJECTED'
  note: string
}

/**
 * 仅通过整改工作流的受认证 multipart 入口上传现场证据。
 * 服务端同时校验店长身份、巡检记录归属、门店范围和当前整改状态。
 */
export async function uploadInspectionRectificationEvidence(recordId: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return apiPostForm<InspectionRectificationEvidenceUpload>(
    `/api/inspections/${encodeURIComponent(recordId)}/rectification/evidence`,
    form,
  )
}

/** 店长可见且已由后端按门店范围过滤的整改待办。 */
export async function getMyInspectionRectifications() {
  const response = await apiGet<InspectionRectificationListResponse | InspectionRectificationTask[]>(
    '/api/inspections/rectifications/mine',
  )
  return normalizeTasks(response)
}

/** 运营可见且已由后端按租户/门店范围过滤的待复核队列。 */
export async function getInspectionRectificationReviewQueue() {
  const response = await apiGet<InspectionRectificationListResponse | InspectionRectificationTask[]>(
    '/api/inspections/rectifications/reviews',
  )
  return normalizeTasks(response)
}

/**
 * 提交整改时只传后端附件 ID；附件二进制由整改工作流的受认证上传接口处理。
 * 服务器负责验证巡检记录、门店范围、状态和附件归属。
 */
export function submitInspectionRectification(
  recordId: string,
  payload: SubmitInspectionRectificationPayload,
) {
  return apiPost<InspectionRectificationTask, SubmitInspectionRectificationPayload>(
    `/api/inspections/${encodeURIComponent(recordId)}/rectification`,
    payload,
  )
}

/** 运营复核只能由后端确认状态迁移，前端不自行把任务标记为完成。 */
export function reviewInspectionRectification(
  recordId: string,
  payload: ReviewInspectionRectificationPayload,
) {
  return apiPost<InspectionRectificationTask, ReviewInspectionRectificationPayload>(
    `/api/inspections/${encodeURIComponent(recordId)}/rectification/review`,
    payload,
  )
}

/**
 * 候选包尚未部署整改合同的时候，明确区分接口未提供和真实业务失败；
 * 调用方不得把这种状态伪装成“整改已提交”。
 */
export function isInspectionRectificationServiceUnavailable(error: unknown) {
  return error instanceof ApiError && error.status === 404
}

function normalizeTasks(response: InspectionRectificationListResponse | InspectionRectificationTask[]) {
  const items = Array.isArray(response) ? response : response?.items
  if (!Array.isArray(items)) return []
  return items.map((item) => ({
    ...item,
    recordId: String(item.recordId || '').trim(),
    storeId: String(item.storeId || '').trim(),
    evidenceAttachmentIds: Array.isArray(item.evidenceAttachmentIds)
      ? item.evidenceAttachmentIds
        .map((attachmentId) => Number(attachmentId))
        .filter((attachmentId) => Number.isInteger(attachmentId) && attachmentId > 0)
      : [],
  })).filter((item) => Boolean(item.recordId && item.storeId))
}
