import { apiDelete, apiGet, apiPost, http } from './http'

export interface WarehouseSummary {
  itemCount: number
  lowStockCount: number
  expiringCount: number
  overstockCount?: number
  pendingRequisitionCount: number
  pendingReceiptCount: number
  pendingPurchaseCount: number
  stockValue: number
}

export interface WarehouseAlert {
  severity: string
  type: string
  itemId: number
  itemName: string
  message: string
}

export interface WarehouseItem {
  id: number
  code: string
  name: string
  categoryId?: number
  categoryName?: string
  category?: string
  imageUrl?: string
  unit?: string
  purchaseUnit?: string
  stockUnit?: string
  ingredientUnit?: string
  unitConversionText?: string
  spec?: string
  warehouseLocation?: string
  shelfLifeDays?: number
  cupsPerUnit?: number
  dailyUsageEstimate?: number
  minStockDays?: number
  maxStockDays?: number
  stockQuantity: number
  storeStockQuantity: number
  warehouseAvailableQuantity: number
  stockValue?: number
  unitPrice: number
  minStockQuantity?: number
  alertEnabled?: boolean
  expiryAlertDays?: number
  nearestExpiryDate?: string
  stockStatus: string
  alertLevel: string
  alertText: string
  active: boolean
  itemDescription?: string
  sortOrder?: number
  itemAttributes?: string
  departments?: WarehouseItemDepartment[]
}

export interface WarehouseItemDepartment {
  id?: number
  departmentName: string
  departmentCode?: string
  departmentGroup?: string
  purchaseMethod?: string
  supplierName?: string
}

export interface WarehouseItemPayload {
  id?: number
  code: string
  name: string
  categoryId?: number | null
  category?: string
  imageUrl?: string
  unit?: string
  purchaseUnit?: string
  stockUnit?: string
  ingredientUnit?: string
  unitConversionText?: string
  spec?: string
  warehouseLocation?: string
  unitPrice?: number
  shelfLifeDays?: number | null
  cupsPerUnit?: number
  dailyUsageEstimate?: number
  minStockDays?: number
  maxStockDays?: number
  minStockQuantity?: number
  alertEnabled?: boolean
  expiryAlertDays?: number | null
  itemDescription?: string
  sortOrder?: number
  itemAttributes?: string
  active?: boolean
  departments?: WarehouseItemDepartment[]
}

export interface WarehouseRequisitionLine {
  id?: number
  itemId: number
  itemName: string
  requestedQuantity: number
  approvedQuantity: number
  shippedQuantity?: number
  unitPrice?: number
  amount?: number
  warningText?: string
  note?: string
  unit?: string
}

export interface WarehouseRequisition {
  id: string
  storeId: string
  storeName: string
  status: string
  statusLabel: string
  totalAmount: number
  note?: string
  submittedAt?: string
  reviewedAt?: string
  shippedAt?: string
  receivedAt?: string
  lines: WarehouseRequisitionLine[]
}

export interface WarehouseOverview {
  summary: WarehouseSummary
  alerts: WarehouseAlert[]
  items: WarehouseItem[]
  requisitions: WarehouseRequisition[]
  suppliers?: WarehouseSupplier[]
  purchaseOrders?: WarehousePurchaseOrder[]
  deliveries?: WarehouseDelivery[]
  movements?: WarehouseStockMovement[]
  stockBatches?: WarehouseStockBatch[]
}

export interface WarehouseSupplier {
  id: number
  name: string
  contactName?: string
  phone?: string
  settlementCycle?: string
  active: boolean
}

export interface WarehousePurchaseOrderLine {
  id: number
  itemId: number
  itemName: string
  unit?: string
  orderedQuantity: number
  receivedQuantity: number
  unitCost: number
  amount: number
  note?: string
}

export interface WarehousePurchaseOrder {
  id: string
  supplierId?: number
  supplierName?: string
  status: string
  statusLabel: string
  totalAmount: number
  note?: string
  createdBy?: string
  receivedBy?: string
  createdAt?: string
  receivedAt?: string
  lines: WarehousePurchaseOrderLine[]
}

export interface WarehouseDeliveryLine {
  id: number
  itemId: number
  itemName: string
  unit?: string
  shippedQuantity: number
  receivedQuantity: number
  unitPrice: number
  amount: number
}

export interface WarehouseDelivery {
  id: string
  requisitionId: string
  storeId: string
  storeName: string
  status: string
  statusLabel: string
  shippedBy?: string
  receivedBy?: string
  shippedAt?: string
  receivedAt?: string
  lines: WarehouseDeliveryLine[]
}

export interface WarehouseStockMovement {
  id: number
  itemId: number
  batchId?: number
  itemName: string
  movementType: string
  movementTypeLabel: string
  quantityDelta: number
  sourceType?: string
  sourceId?: string
  storeId?: string
  storeName?: string
  note?: string
  operatorName?: string
  createdAt?: string
  batchNo?: string
}

export interface WarehouseStockBatch {
  id: number
  itemId: number
  itemName: string
  unit?: string
  batchNo: string
  receivedDate: string
  expiryDate?: string
  quantity: number
  unitCost: number
  note?: string
  createdAt?: string
  status: string
}

export interface WarehouseReturnLine {
  id: number
  itemId: number
  itemName: string
  spec?: string
  batchId?: number
  batchNo?: string
  sourceRequisitionLineId?: number
  quantity: number
  unit?: string
  unitPrice: number
  returnPrice: number
  amount: number
  reason?: string
  note?: string
}

export interface WarehouseReturnOrder {
  id: string
  returnNo: string
  sourceRequisitionId?: string
  sourceDeliveryId?: string
  returnStoreId: string
  returnStoreName: string
  receiveDepartment?: string
  status: string
  statusLabel: string
  totalAmount: number
  handledBy?: string
  createdBy?: string
  updatedBy?: string
  reviewedBy?: string
  checkedBy?: string
  reason?: string
  note?: string
  reviewNote?: string
  receivedNote?: string
  returnDate?: string
  reviewedAt?: string
  receivedAt?: string
  createdAt?: string
  updatedAt?: string
  lineCount: number
  attachmentCount: number
  lines: WarehouseReturnLine[]
}

export interface WarehouseItemCategory {
  id: number
  name: string
  parentId?: number | null
  sortOrder: number
  enabled: boolean
  children: WarehouseItemCategory[]
}

export interface WarehouseRequisitionCreatePayload {
  storeId?: string
  lines: Array<{
    itemId: number
    requestedQuantity: number
    note?: string
  }>
  note?: string
  clientRequestId?: string
}

export function getWarehouseOverview() {
  return apiGet<WarehouseOverview>('/api/warehouse/overview')
}

export function getWarehouseItemCategories() {
  return apiGet<WarehouseItemCategory[]>('/api/warehouse/item-categories')
}

export function saveWarehouseItem(payload: WarehouseItemPayload) {
  return apiPost<void, WarehouseItemPayload>('/api/warehouse/items', payload)
}

export function setWarehouseItemEnabled(itemId: number, enabled: boolean) {
  return apiPost<void, { enabled: boolean }>(`/api/warehouse/items/${itemId}/enabled`, { enabled })
}

export function saveWarehouseItemCategory(payload: {
  id?: number
  name: string
  parentId?: number | null
  sortOrder?: number
  enabled?: boolean
}) {
  return apiPost<WarehouseItemCategory, typeof payload>('/api/warehouse/item-categories', payload)
}

export function deleteWarehouseItemCategory(categoryId: number) {
  return apiDelete<void>(`/api/warehouse/item-categories/${categoryId}`)
}

export function createWarehouseRequisition(payload: WarehouseRequisitionCreatePayload) {
  return apiPost<WarehouseRequisition, WarehouseRequisitionCreatePayload>('/api/warehouse/requisitions', payload)
}

export function receiveWarehouseRequisition(requisitionId: string, note?: string) {
  return apiPost<void, { note?: string }>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/receive`, { note })
}

export function reviewWarehouseRequisition(
  requisitionId: string,
  payload: { approved: boolean; lines: Array<{ itemId: number; approvedQuantity: number }>; note?: string },
) {
  return apiPost<void, typeof payload>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/review`, payload)
}

export function shipWarehouseRequisition(requisitionId: string) {
  return apiPost<void, undefined>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/ship`)
}

export function receiveWarehouseStock(payload: {
  itemId: number
  batchNo: string
  receivedDate: string
  expiryDate?: string
  quantity: number
  unitCost: number
  note?: string
  clientRequestId?: string
}) {
  return apiPost<void, typeof payload>('/api/warehouse/stock-batches', payload)
}

export function updateWarehouseAlertSettings(
  itemId: number,
  payload: { minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number },
) {
  return apiPost<void, typeof payload>(`/api/warehouse/items/${itemId}/alert-settings`, payload)
}

export function getWarehouseReturns() {
  return apiGet<WarehouseReturnOrder[]>('/api/warehouse/returns')
}

export function reviewWarehouseReturn(returnId: string, payload: { approved: boolean; note?: string }) {
  return apiPost<WarehouseReturnOrder, typeof payload>(`/api/warehouse/returns/${encodeURIComponent(returnId)}/review`, payload)
}

export function receiveWarehouseReturn(returnId: string, payload: { note?: string }) {
  return apiPost<WarehouseReturnOrder, typeof payload>(`/api/warehouse/returns/${encodeURIComponent(returnId)}/receive`, payload)
}

export async function downloadWarehousePdf(url: string, fallbackName: string) {
  const response = await http.get<Blob>(url, { responseType: 'blob' })
  const disposition = String(response.headers['content-disposition'] || '')
  const filename = decodeFilename(disposition) || fallbackName
  const blobUrl = URL.createObjectURL(response.data)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(blobUrl)
}

function decodeFilename(disposition: string) {
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) return decodeURIComponent(encoded)
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return plain ? decodeURIComponent(plain) : ''
}
