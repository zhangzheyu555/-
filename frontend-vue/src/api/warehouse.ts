import { apiDelete, apiGet, apiPost, http } from './http'
import { decodeFilename, downloadBlob } from './reports'

export interface WarehouseSummary {
  itemCount: number
  lowStockCount: number
  expiringCount: number
  overstockCount?: number
  pendingRequisitionCount: number
  pendingReceiptCount: number
  pendingPurchaseCount: number
  stockValue: number
  inTransitQuantity?: number
}

export type WarehouseType = 'CENTRAL' | 'REGIONAL'
export type WarehouseRegion = 'JINGZHOU' | 'SHANDONG'

/** 权限裁剪后的仓库范围。前端只用它切换上下文，后端仍负责最终鉴权。 */
export interface WarehouseInfo {
  id: string | number
  code: string
  name: string
  type: WarehouseType
  regionCode: WarehouseRegion
  parentWarehouseId?: string | number | null
  parentWarehouseName?: string | null
  externalPurchaseAllowed: boolean
  storeSupplyAllowed: boolean
  enabled: boolean
  canRead?: boolean
  canPurchase?: boolean
  canRequestTransfer?: boolean
  canApproveTransfer?: boolean
  canShipTransfer?: boolean
  canReceiveTransfer?: boolean
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
  requisitionPolicy?: WarehouseItemRequisitionPolicy
}

export type WarehouseItemRequisitionScopeMode = 'ALL' | 'SELECTED'

export interface WarehouseItemRequisitionPolicy {
  scopeMode: WarehouseItemRequisitionScopeMode
  regionCodes: string[]
  storeIds: string[]
  campaignName?: string
  startsAt?: string
  endsAt?: string
  configured: boolean
}

export interface WarehouseItemRequisitionPolicyPayload {
  scopeMode: WarehouseItemRequisitionScopeMode
  regionCodes: string[]
  storeIds: string[]
  campaignName?: string
  startsAt?: string
  endsAt?: string
}

export interface WarehouseItemRequisitionScopeRegion {
  code: string
  name: string
}

export interface WarehouseItemRequisitionScopeStore {
  id: string
  name: string
  regionCode?: string
}

export interface WarehouseItemRequisitionScopeContext {
  activeStoreCount: number
  regions: WarehouseItemRequisitionScopeRegion[]
  stores: WarehouseItemRequisitionScopeStore[]
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
  requisitionPolicy: WarehouseItemRequisitionPolicyPayload
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
  warehouseId?: string | number
  warehouseName?: string
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

export type WarehouseRequisitionHandlingMode =
  | 'FULL'
  | 'AVAILABLE_ONLY'
  | 'MARK_BACKORDER'
  | 'WAIT_REPLENISHMENT'

export type WarehouseRequisitionSummaryPeriodType = 'DAY' | 'WEEK' | 'MONTH'
export type WarehouseRequisitionSummaryGroupDimension = 'store' | 'product' | 'period'

export interface WarehouseRequisitionSummaryExportPayload {
  warehouseId?: string | number
  startDate: string
  endDate: string
  storeIds: string[]
  productIds: number[]
  periodType: WarehouseRequisitionSummaryPeriodType
  includeZeroRows: boolean
  groupBy: WarehouseRequisitionSummaryGroupDimension[]
}

export interface WarehouseOverview {
  warehouse?: WarehouseInfo
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
  warehouseId?: string | number
  warehouseName?: string
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

export interface WarehousePurchaseOrderCreatePayload {
  warehouseId: string | number
  supplierId?: number
  note?: string
  clientRequestId: string
  lines: Array<{
    itemId: number
    orderedQuantity: number
    unitCost: number
    note?: string
  }>
}

export interface WarehousePurchaseOrderReceivePayload {
  clientRequestId: string
  note?: string
  lines: Array<{
    itemId: number
    batchNo: string
    receivedDate: string
    expiryDate?: string
    quantity: number
    note?: string
  }>
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
  warehouseId?: string | number
  warehouseName?: string
  sourceWarehouseId?: string | number
  sourceWarehouseName?: string
  targetWarehouseId?: string | number
  targetWarehouseName?: string
  note?: string
  operatorName?: string
  createdAt?: string
  batchNo?: string
}

export interface WarehouseStockBatch {
  id: number
  itemId: number
  itemName: string
  warehouseId?: string | number
  warehouseName?: string
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
  receiveWarehouseId?: string | number
  receiveWarehouseName?: string
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

export type WarehouseTransferStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'SHIPPED'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CANCELLED'

export interface WarehouseTransferLine {
  id: number
  itemId: number
  itemName: string
  unit: string
  requestedQuantity: number
  approvedQuantity: number
  reservedQuantity: number
  shippedQuantity: number
  receivedQuantity: number
  inTransitQuantity: number
  unitCost: number
  amount: number
  note?: string
}

export interface WarehouseTransfer {
  id: string
  transferNo?: string
  status: WarehouseTransferStatus
  statusLabel?: string
  sourceWarehouseId: string | number
  sourceWarehouseName: string
  targetWarehouseId: string | number
  targetWarehouseName: string
  totalAmount: number
  requestedBy?: string
  approvedBy?: string
  shippedBy?: string
  receivedBy?: string
  cancelledBy?: string
  createdAt?: string
  submittedAt?: string
  reviewedAt?: string
  shippedAt?: string
  receivedAt?: string
  cancelledAt?: string
  note?: string
  reviewNote?: string
  version: number
  lines: WarehouseTransferLine[]
}

export interface WarehouseTransferCreatePayload {
  sourceWarehouseId: string | number
  targetWarehouseId: string | number
  lines: Array<{ itemId: number; quantity: number; note?: string }>
  note?: string
  clientRequestId: string
}

/**
 * 调拨工作台由后端根据当前用户、仓库范围和启用路线裁剪后返回的上下文。
 * 前端只能使用 routes 中的来源仓、目标仓、可执行动作和物料库存，不能自行推断路线。
 */
export type WarehouseTransferContextMode = 'REQUEST_REPLENISHMENT' | 'PROACTIVE_ALLOCATION' | 'NONE'

export interface WarehouseTransferEndpoint {
  id: string | number
  code?: string
  name: string
}

export interface WarehouseTransferRouteActions {
  canCreate: boolean
  canSubmit: boolean
  canApprove: boolean
  canReject: boolean
  canShip: boolean
  canReceive: boolean
  canCancel: boolean
}

export interface WarehouseTransferMaterial {
  itemId: number
  itemName: string
  itemCode?: string
  unit?: string
  availableQuantity: number
  shortageMessage?: string
}

export interface WarehouseTransferRoute {
  sourceWarehouse: WarehouseTransferEndpoint
  targetWarehouse: WarehouseTransferEndpoint
  formAction?: WarehouseTransferContextMode
  workbenchLabel?: string
  actions: WarehouseTransferRouteActions
  materials: WarehouseTransferMaterial[]
}

export interface WarehouseTransferTodos {
  draft?: number
  pendingApproval?: number
  pendingShipment?: number
  pendingReceipt?: number
  completed?: number
}

export interface WarehouseTransferContext {
  currentWarehouse: WarehouseTransferEndpoint
  mode: WarehouseTransferContextMode
  workbenchLabel?: string
  routes: WarehouseTransferRoute[]
  todos: WarehouseTransferTodos
}

export function getWarehouses() {
  return apiGet<WarehouseInfo[]>('/api/warehouse/warehouses')
}

export function getWarehouseOverview(warehouseId?: string | number) {
  return apiGet<WarehouseOverview>('/api/warehouse/overview', {
    params: warehouseId ? { warehouseId } : undefined,
  })
}

export function getWarehouseTransfers(warehouseId?: string | number) {
  return apiGet<WarehouseTransfer[]>('/api/warehouse/transfers', {
    params: warehouseId ? { warehouseId } : undefined,
  })
}

export function getWarehouseTransferContext(warehouseId: string | number) {
  return apiGet<WarehouseTransferContext>('/api/warehouse/transfers/context', {
    params: { warehouseId },
  })
}

export function createWarehouseTransfer(payload: WarehouseTransferCreatePayload) {
  return apiPost<WarehouseTransfer, WarehouseTransferCreatePayload>('/api/warehouse/transfers', payload)
}

export function submitWarehouseTransfer(id: string) {
  return apiPost<WarehouseTransfer, undefined>(`/api/warehouse/transfers/${encodeURIComponent(id)}/submit`)
}

export function reviewWarehouseTransfer(id: string, approved: boolean, note?: string) {
  return apiPost<WarehouseTransfer, { approved: boolean; note?: string }>(
    `/api/warehouse/transfers/${encodeURIComponent(id)}/review`,
    { approved, note },
  )
}

export function shipWarehouseTransfer(id: string, clientRequestId: string, note?: string) {
  return apiPost<WarehouseTransfer, { clientRequestId: string; note?: string }>(
    `/api/warehouse/transfers/${encodeURIComponent(id)}/ship`,
    { clientRequestId, note },
  )
}

export function receiveWarehouseTransfer(
  id: string,
  payload: { clientRequestId: string; note?: string; lines?: Array<{ itemId: number; receivedQuantity: number }> },
) {
  return apiPost<WarehouseTransfer, typeof payload>(
    `/api/warehouse/transfers/${encodeURIComponent(id)}/receive`,
    payload,
  )
}

export function cancelWarehouseTransfer(id: string, clientRequestId: string, note?: string) {
  return apiPost<WarehouseTransfer, { clientRequestId: string; note?: string }>(
    `/api/warehouse/transfers/${encodeURIComponent(id)}/cancel`,
    { clientRequestId, note },
  )
}

export function getWarehouseItemCategories() {
  return apiGet<WarehouseItemCategory[]>('/api/warehouse/item-categories')
}

export function getWarehouseItemRequisitionScopeContext() {
  return apiGet<WarehouseItemRequisitionScopeContext>('/api/warehouse/items/requisition-scope-context')
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

export async function downloadWarehouseRequisitionSummary(
  payload: WarehouseRequisitionSummaryExportPayload,
  fallbackName: string,
) {
  const response = await http.post<Blob>('/api/warehouse/requests/export-summary', payload, {
    responseType: 'blob',
  })
  const disposition = String(response.headers['content-disposition'] || '')
  const filename = decodeFilename(disposition) || fallbackName
  downloadBlob(response.data, filename)
  return filename
}

export function receiveWarehouseRequisition(requisitionId: string, note?: string) {
  return apiPost<void, { note?: string }>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/receive`, { note })
}

export function reviewWarehouseRequisition(
  requisitionId: string,
  payload: {
    approved: boolean
    lines: Array<{ itemId: number; approvedQuantity: number }>
    note?: string
    handlingMode?: WarehouseRequisitionHandlingMode
  },
) {
  return apiPost<void, typeof payload>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/review`, payload)
}

export function shipWarehouseRequisition(requisitionId: string) {
  return apiPost<void, undefined>(`/api/warehouse/requisitions/${encodeURIComponent(requisitionId)}/ship`)
}

export function receiveWarehouseStock(payload: {
  warehouseId?: string | number
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

export function createWarehousePurchaseOrder(payload: WarehousePurchaseOrderCreatePayload) {
  return apiPost<WarehousePurchaseOrder, WarehousePurchaseOrderCreatePayload>('/api/warehouse/purchase-orders', payload)
}

export function approveWarehousePurchaseOrder(purchaseOrderId: string) {
  return apiPost<WarehousePurchaseOrder, undefined>(
    `/api/warehouse/purchase-orders/${encodeURIComponent(purchaseOrderId)}/approve`,
  )
}

export function receiveWarehousePurchaseOrder(
  purchaseOrderId: string,
  payload: WarehousePurchaseOrderReceivePayload,
) {
  return apiPost<WarehousePurchaseOrder, WarehousePurchaseOrderReceivePayload>(
    `/api/warehouse/purchase-orders/${encodeURIComponent(purchaseOrderId)}/receive`,
    payload,
  )
}

export function updateWarehouseAlertSettings(
  itemId: number,
  payload: { warehouseId: string | number; minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number },
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
  downloadBlob(response.data, filename)
}
