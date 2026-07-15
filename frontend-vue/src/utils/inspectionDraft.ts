import type { InspectionCategoryCode, InspectionItemResult, InspectionRecord } from '../api/inspection'

export const inspectionCanonicalBrandNames = ['茹菓', '霸王茶姬', '瑞幸咖啡']

export const inspectionBrandPalette: Record<string, { color: string; soft: string }> = {
  茹菓: { color: '#76bdb8', soft: '#e9f6f5' },
  霸王茶姬: { color: '#9c2f3e', soft: '#fff0f2' },
  瑞幸咖啡: { color: '#2458c7', soft: '#edf4ff' },
}

export function todayInspectionDate() {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function currentInspectionMonth() {
  return todayInspectionDate().slice(0, 7)
}

export function normalizeInspectionBrandName(name?: string) {
  const value = String(name || '').trim()
  if (!value) return ''
  if (value.includes('茹菓') || value.includes('茹果') || value.includes('苹果')) return '茹菓'
  if (value.includes('霸王')) return '霸王茶姬'
  if (value.includes('瑞幸')) return '瑞幸咖啡'
  return value
}

export function safeInspectionNumber(value: unknown) {
  const numberValue = Number(value)
  return Number.isFinite(numberValue) ? numberValue : 0
}

export function roundInspectionScore(value: number) {
  return Math.round(value * 100) / 100
}

export function inspectionCategoryCodeForDimension(value?: string): InspectionCategoryCode {
  const text = String(value || '')
  if (text.includes('卫生')) return 'HYGIENE'
  if (text.includes('服务')) return 'SERVICE'
  return 'MATERIAL'
}

export function inspectionItemDeduction(item: InspectionItemResult) {
  const deductionScore = Number(item.deductionScore)
  if (Number.isFinite(deductionScore) && deductionScore >= 0) {
    return roundInspectionScore(deductionScore)
  }
  return Math.max(0, roundInspectionScore(safeInspectionNumber(item.standardScore) - safeInspectionNumber(item.actualScore)))
}

export function inspectionItemNeedsRectification(item: InspectionItemResult) {
  return Boolean(item.issueFound) || inspectionItemDeduction(item) > 0 || Boolean(item.deductionReason?.trim())
}

export function normalizeInspectionItemScore(item: InspectionItemResult) {
  const standardScore = Math.max(0, safeInspectionNumber(item.standardScore))
  item.actualScore = Math.max(0, Math.min(standardScore, roundInspectionScore(safeInspectionNumber(item.actualScore))))
  item.redLineHit = item.riskLevel === 'RED' && Boolean(item.issueFound)
  if (!inspectionItemNeedsRectification(item)) {
    item.deductionReason = ''
    item.responsiblePerson = ''
    item.rectificationDeadline = ''
    item.rectificationStatus = '无需整改'
    item.reviewResult = ''
  } else if (!item.rectificationStatus || item.rectificationStatus === '无需整改') {
    item.rectificationStatus = '待整改'
  }
}

export function toggleInspectionRedLineIssue(item: InspectionItemResult, checked: boolean) {
  item.issueFound = checked
  item.redLineHit = checked
  if (!checked) item.deductionReason = ''
  normalizeInspectionItemScore(item)
}

export function isInspectionItemPhotoSelected(item: InspectionItemResult, attachmentId?: number) {
  return Boolean(attachmentId && item.photoAttachmentIds.includes(attachmentId))
}

export function toggleInspectionItemPhoto(item: InspectionItemResult, attachmentId: number | undefined, checked: boolean) {
  if (!attachmentId) return
  item.photoAttachmentIds = checked
    ? Array.from(new Set([...item.photoAttachmentIds, attachmentId]))
    : item.photoAttachmentIds.filter((id) => id !== attachmentId)
  item.beforePhotoAttachmentIds = [...item.photoAttachmentIds]
}

export function isInspectionItemAfterPhotoSelected(item: InspectionItemResult, attachmentId?: number) {
  return Boolean(attachmentId && item.afterPhotoAttachmentIds?.includes(attachmentId))
}

export function toggleInspectionItemAfterPhoto(item: InspectionItemResult, attachmentId: number | undefined, checked: boolean) {
  if (!attachmentId) return
  const current = item.afterPhotoAttachmentIds || []
  item.afterPhotoAttachmentIds = checked
    ? Array.from(new Set([...current, attachmentId]))
    : current.filter((id) => id !== attachmentId)
}

export function inspectionBrandMeta(name?: string) {
  const normalized = normalizeInspectionBrandName(name)
  return inspectionBrandPalette[normalized] || { color: '#64748b', soft: '#f1f5f9' }
}

export function compareInspectionRecords(a: InspectionRecord, b: InspectionRecord) {
  return (b.inspectionDate || '').localeCompare(a.inspectionDate || '') || String(b.id || '').localeCompare(String(a.id || ''))
}
