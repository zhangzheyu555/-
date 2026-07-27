import { apiGet, apiPost, apiPut, http } from './http'
import { decodeFilename, downloadBlob } from './reports'

export type InventoryDecimal = number | string | null | undefined

export interface InventoryItem {
  id?: number
  itemCode: string
  itemName: string
  category?: string
  spec?: string
  unit?: string
  packageQuantity?: InventoryDecimal
  packagePrice?: InventoryDecimal
  unitPrice?: InventoryDecimal
  priceMissing?: boolean
  priced?: boolean
  sortOrder?: number
}

export interface InventoryCheckLine {
  id?: number
  itemId?: number
  itemCode?: string
  itemName: string
  category?: string
  spec?: string
  unit?: string
  packageQuantity?: InventoryDecimal
  packagePrice?: InventoryDecimal
  unitPrice?: InventoryDecimal
  /** 后端旧列名沿用至今：明细响应里该字段承载包装价。 */
  unitPriceEach?: InventoryDecimal
  countedQuantity: InventoryDecimal
  amount?: InventoryDecimal
  priceMissing?: boolean
  note?: string
}

export interface InventoryCheck {
  id: number
  checkNo: string
  storeId: string
  storeName: string
  checkDate: string
  status: string
  statusLabel?: string
  totalAmount: InventoryDecimal
  submittedBy?: number
  reviewedBy?: number
  reviewedByName?: string
  reviewedByRole?: string
  reviewedByRoleLabel?: string
  reviewedAt?: string
  note?: string
  createdAt?: string
  updatedAt?: string
  lines?: InventoryCheckLine[]
}

export interface InventoryCheckLinePayload {
  itemId?: number
  itemCode: string
  itemName: string
  category?: string
  spec?: string
  unit?: string
  packageQuantity?: number
  packagePrice?: number
  unitPrice?: number
  unitPriceEach?: number
  countedQuantity: number
  note?: string
}

export interface InventoryCheckSaveRequest {
  id?: number
  storeId: string
  checkDate: string
  note?: string
  lines: InventoryCheckLinePayload[]
}

export interface InventoryItemPriceRequest {
  packageQuantity: number
  packagePrice: number
  unitPrice: number
}

export function getInventoryItems() {
  return apiGet<InventoryItem[]>('/api/operations/inventory-items')
}

export function updateInventoryItemPrice(itemCode: string, payload: InventoryItemPriceRequest) {
  return apiPut<InventoryItem, InventoryItemPriceRequest>(
    `/api/operations/inventory-items/${encodeURIComponent(itemCode)}/price`,
    payload,
  )
}

export function getInventoryChecks() {
  return apiGet<InventoryCheck[]>('/api/operations/inventory-checks')
}

export function getInventoryCheck(id: number) {
  return apiGet<InventoryCheck>(`/api/operations/inventory-checks/${id}`)
}

export function saveInventoryCheck(payload: InventoryCheckSaveRequest) {
  return apiPost<InventoryCheck, InventoryCheckSaveRequest>('/api/operations/inventory-checks', payload)
}

export function submitInventoryCheck(id: number) {
  return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/submit`)
}

export function reviewInventoryCheck(id: number) {
  return apiPost<InventoryCheck>(`/api/operations/inventory-checks/${id}/review`)
}

export async function downloadInventoryCheckExcel(check: Pick<InventoryCheck, 'id' | 'checkNo' | 'storeName' | 'checkDate'>) {
  const response = await http.get<Blob>(
    `/api/operations/inventory-checks/${check.id}/export.xlsx`,
    {
      responseType: 'blob',
      timeout: 120_000,
    },
  )
  const disposition = String(response.headers['content-disposition'] || '')
  const fallbackName = `店铺盘存-${check.storeName || check.checkNo}-${check.checkDate}.xlsx`
  downloadBlob(response.data, decodeFilename(disposition) || fallbackName)
}
