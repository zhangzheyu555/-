<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { ArrowRight, ClipboardList, PackagePlus, Warehouse } from 'lucide-vue-next'
import SecondaryNavigation from '../components/common/SecondaryNavigation.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import WarehouseAlertPanel from '../components/warehouse/WarehouseAlertPanel.vue'
import WarehouseInventoryPanel from '../components/warehouse/WarehouseInventoryPanel.vue'
import WarehouseMaterialCatalogPanel from '../components/warehouse/WarehouseMaterialCatalogPanel.vue'
import WarehouseMaterialEditor from '../components/warehouse/WarehouseMaterialEditor.vue'
import WarehouseMovementPanel from '../components/warehouse/WarehouseMovementPanel.vue'
import WarehousePurchaseReceivePanel from '../components/warehouse/WarehousePurchaseReceivePanel.vue'
import WarehouseRequisitionPanel from '../components/warehouse/WarehouseRequisitionPanel.vue'
import WarehouseRequisitionSummaryExport from '../components/warehouse/WarehouseRequisitionSummaryExport.vue'
import WarehouseReturnPanel from '../components/warehouse/WarehouseReturnPanel.vue'
import WarehouseStatCards from '../components/warehouse/WarehouseStatCards.vue'
import WarehouseNetworkOverview from '../components/warehouse/WarehouseNetworkOverview.vue'
import WarehouseTransferPanel from '../components/warehouse/WarehouseTransferPanel.vue'
import { useWarehouseStore, type WarehouseTab } from '../stores/warehouse'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import { getStores, type StoreInfo } from '../api/operations'
import { getWarehouseItemRequisitionScopeContext } from '../api/warehouse'
import type {
  WarehouseInfo,
  WarehouseItem,
  WarehouseItemPayload,
  WarehouseItemRequisitionScopeContext,
  WarehousePurchaseOrderCreatePayload,
  WarehousePurchaseOrderReceivePayload,
  WarehouseTransferCreatePayload,
} from '../api/warehouse'

type WarehouseConfirmation =
  | { kind: 'reject-requisition'; id: string }
  | { kind: 'ship-requisition'; id: string }
  | { kind: 'fulfill-available-requisition'; id: string }
  | { kind: 'mark-backorder-requisition'; id: string }
  | { kind: 'wait-replenishment-requisition'; id: string }
  | { kind: 'reject-return'; id: string }
  | { kind: 'receive-return'; id: string }
  | { kind: 'approve-transfer'; id: string }
  | { kind: 'reject-transfer'; id: string }
  | { kind: 'ship-transfer'; id: string }
  | { kind: 'receive-transfer'; id: string }
  | { kind: 'cancel-transfer'; id: string }

const props = withDefaults(defineProps<{
  canManage?: boolean
}>(), {
  canManage: false,
})

const warehouse = useWarehouseStore()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const editorOpen = ref(false)
const editorItem = ref<WarehouseItem | null>(null)
const pendingConfirmation = ref<WarehouseConfirmation | null>(null)
const confirmationNote = ref('')
const confirmationBusy = ref(false)
const requisitionStores = ref<StoreInfo[]>([])
const requisitionStoresAttempted = ref(false)
const itemScopeContext = ref<WarehouseItemRequisitionScopeContext | null>(null)
const itemScopeContextAttempted = ref(false)

const overview = computed(() => warehouse.overview)
const items = computed(() => overview.value?.items || [])
const activeItems = computed(() => items.value.filter((item) => item.active !== false))
const requisitions = computed(() => overview.value?.requisitions || [])
const pendingRequisitions = computed(() => requisitions.value.filter((row) => (
  ['SUBMITTED', 'APPROVED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(row.status)
)))
const stockBatches = computed(() => overview.value?.stockBatches || [])
const suppliers = computed(() => overview.value?.suppliers || [])
const purchaseOrders = computed(() => overview.value?.purchaseOrders || [])
const movements = computed(() => overview.value?.movements || [])
const returns = computed(() => warehouse.returns || [])
const alerts = computed(() => overview.value?.alerts || [])
const accessibleWarehouses = computed(() => warehouse.warehouses.filter((row) => row.enabled !== false))
const itemScopeRegions = computed(() => {
  const regions = new Map<string, string>()
  for (const region of itemScopeContext.value?.regions || []) {
    const code = region.code?.trim().toUpperCase()
    if (code) regions.set(code, region.name?.trim() || code)
  }
  for (const facility of accessibleWarehouses.value) {
    const code = facility.regionCode?.trim().toUpperCase()
    if (!code || regions.has(code)) continue
    regions.set(code, code === 'JINGZHOU' ? '荆州区域' : (code === 'SHANDONG' ? '山东区域' : facility.name))
  }
  return Array.from(regions, ([code, name]) => ({ code, name }))
})
const selectedWarehouse = computed(() => accessibleWarehouses.value.find((row) => String(row.id) === String(warehouse.selectedWarehouseId)) || null)
const centralWarehouse = computed(() => accessibleWarehouses.value.find((row) => row.type === 'CENTRAL') || null)
const isBoss = computed(() => auth.role === 'BOSS')
const hasPermission = (permission: string) => auth.hasPermission(permission)
const canConfigureWarehouse = computed(() => (
  isBoss.value
  || hasPermission(PERMISSIONS.WAREHOUSE_CONFIGURE)
  || (props.canManage && hasPermission(PERMISSIONS.WAREHOUSE_CENTRAL_MANAGE))
))
const canPurchase = computed(() => Boolean(
  centralWarehouse.value
  && centralWarehouse.value.externalPurchaseAllowed
  && (isBoss.value || hasPermission(PERMISSIONS.WAREHOUSE_PURCHASE) || centralWarehouse.value.canPurchase),
))
const transferContext = computed(() => warehouse.transferContext)
const transferRoutes = computed(() => transferContext.value?.routes || [])
const hasTransferRoute = computed(() => transferRoutes.value.length > 0)
const canCreateTransfer = computed(() => Boolean(
  transferRoutes.value.some((route) => route.actions.canCreate),
))
const canProcessTransfer = computed(() => Boolean(
  transferContext.value?.mode === 'PROACTIVE_ALLOCATION'
  && transferRoutes.value.some((route) => (
    route.actions.canApprove || route.actions.canReject || route.actions.canShip
  )),
))
const canProcessRequisition = computed(() => (
  isBoss.value
  || hasPermission(PERMISSIONS.WAREHOUSE_REQUISITION_PROCESS)
  || hasPermission(PERMISSIONS.WAREHOUSE_REQUISITION_REVIEW)
))
const canExportRequisitionSummary = computed(() => (
  hasPermission(PERMISSIONS.WAREHOUSE_READ)
  || hasPermission(PERMISSIONS.WAREHOUSE_CENTRAL_READ)
))
const showWarehouseSwitcher = computed(() => accessibleWarehouses.value.length > 1 && currentTab() !== 'overview')
const warehouseDetailSection = computed(() => String(route.meta.warehouseSection || 'inventory'))
const isWarehouseEntrance = computed(() => currentTab() === 'warehouse' && warehouseDetailSection.value === 'inventory')
const pendingTransfers = computed(() => {
  const todos = transferContext.value?.todos
  return Number(todos?.draft || 0)
    + Number(todos?.pendingApproval || 0)
    + Number(todos?.pendingShipment || 0)
    + Number(todos?.pendingReceipt || 0)
})
const confirmationCopy = computed(() => {
  switch (pendingConfirmation.value?.kind) {
    case 'reject-requisition':
      return {
        title: '驳回叫货单',
        message: '驳回仅用于重复申请、门店误填或申请不合理，请填写具体业务原因。',
        confirmLabel: '确认驳回',
        confirmVariant: 'danger' as const,
        noteLabel: '驳回原因',
      }
    case 'ship-requisition':
      return {
        title: '确认发货',
        message: '确认按先进先出规则发货并扣减总仓库存吗？',
        confirmLabel: '确认发货',
        confirmVariant: 'primary' as const,
        noteLabel: '',
      }
    case 'fulfill-available-requisition':
      return {
        title: '按可用库存发货',
        message: '系统将重新校验并预占当前可用库存，只扣减实际发出数量，未发数量转为缺货待处理。',
        confirmLabel: '确认部分发货',
        confirmVariant: 'primary' as const,
        noteLabel: '',
      }
    case 'mark-backorder-requisition':
      return {
        title: '标记缺货',
        message: '本次不生成出库单，未发数量保留为缺货明细。',
        confirmLabel: '确认标记缺货',
        confirmVariant: 'primary' as const,
        noteLabel: '缺货说明（选填）',
      }
    case 'wait-replenishment-requisition':
      return {
        title: '等待补货后再发',
        message: '叫货单将保持待补货状态，库存补充后可继续按可用数量发货。',
        confirmLabel: '转为待补货',
        confirmVariant: 'primary' as const,
        noteLabel: '补发说明（选填）',
      }
    case 'reject-return':
      return {
        title: '驳回退货单',
        message: '请输入驳回原因。',
        confirmLabel: '确认驳回',
        confirmVariant: 'danger' as const,
        noteLabel: '驳回原因',
      }
    case 'receive-return':
      return {
        title: '确认退货入库',
        message: '确认已收到门店退回商品并入库吗？',
        confirmLabel: '确认入库',
        confirmVariant: 'primary' as const,
        noteLabel: '',
      }
    case 'approve-transfer':
      return {
        title: '审批调拨申请',
        message: `确认接受${transferRouteLabel(pendingConfirmation.value?.id)}调拨申请吗？`,
        confirmLabel: '审批通过',
        confirmVariant: 'primary' as const,
        noteLabel: '审批说明（选填）',
      }
    case 'reject-transfer':
      return {
        title: '驳回调拨申请',
        message: `请填写驳回原因，${transferRouteLabel(pendingConfirmation.value?.id)}申请方可据此调整。`,
        confirmLabel: '确认驳回',
        confirmVariant: 'danger' as const,
        noteLabel: '驳回原因',
      }
    case 'ship-transfer':
      return {
        title: '调拨发货',
        message: `确认按${transferRouteLabel(pendingConfirmation.value?.id)}发货吗？发货后将扣减调出仓在库库存并形成在途库存。`,
        confirmLabel: '确认发货',
        confirmVariant: 'primary' as const,
        noteLabel: '发货说明（选填）',
      }
    case 'receive-transfer':
      return {
        title: '调拨收货',
        message: `确认${transferRouteLabel(pendingConfirmation.value?.id)}调入仓已收到本次调拨商品并入库吗？`,
        confirmLabel: '确认收货',
        confirmVariant: 'primary' as const,
        noteLabel: '收货说明（选填）',
      }
    case 'cancel-transfer':
      return {
        title: '取消调拨单',
        message: '确认取消该调拨单吗？已完成的库存动作不会由前端自行回退。',
        confirmLabel: '确认取消',
        confirmVariant: 'danger' as const,
        noteLabel: '取消原因（选填）',
      }
    default:
      return {
        title: '',
        message: '',
        confirmLabel: '确认',
        confirmVariant: 'primary' as const,
        noteLabel: '',
      }
  }
})
const confirmationNoteRequired = computed(() => pendingConfirmation.value?.kind === 'reject-requisition')

const transferRoute = computed<RouteLocationRaw>(() => ({
  path: '/warehouse/transfers',
  query: warehouse.selectedWarehouseId ? { warehouseId: String(warehouse.selectedWarehouseId) } : undefined,
}))

const requisitionRoute = computed<RouteLocationRaw>(() => ({
  path: '/warehouse/requests',
  query: warehouse.selectedWarehouseId ? { warehouseId: String(warehouse.selectedWarehouseId) } : undefined,
}))

const warehouseHomeRoute = computed<RouteLocationRaw>(() => ({
  name: 'warehouse-detail',
  params: selectedWarehouse.value ? { warehouseId: String(selectedWarehouse.value.id) } : undefined,
}))

const warehouseCapabilities = computed(() => {
  const current = selectedWarehouse.value
  if (!current) return []
  if (current.type === 'REGIONAL') {
    return [
      `上级：${current.parentWarehouseName || centralWarehouse.value?.name || '荆州总仓'}`,
      '仅可申请补货',
    ]
  }
  return [
    current.externalPurchaseAllowed ? '可外部采购' : '不可外部采购',
    current.storeSupplyAllowed ? '可向分仓配货' : '不向分仓配货',
  ]
})

const primaryAction = computed(() => {
  const current = selectedWarehouse.value
  if (!current) return null
  if (current.type === 'REGIONAL') {
    return canCreateTransfer.value
      ? { label: '向上级总仓申请补货', to: transferRoute.value }
      : null
  }
  if (pendingTransfers.value > 0 && canProcessTransfer.value) {
    return { label: `处理 ${pendingTransfers.value} 笔调拨`, to: transferRoute.value }
  }
  return canCreateTransfer.value ? { label: '向分仓配货', to: transferRoute.value } : null
})

const priorityItems = computed(() => {
  const rows: Array<{ key: string; title: string; detail: string; count: number; to: RouteLocationRaw }> = []
  if (pendingTransfers.value > 0) {
    rows.push({
      key: 'transfers',
      title: '调拨待处理',
      detail: selectedWarehouse.value?.type === 'REGIONAL' ? '请确认在途货物或补货申请。' : '请优先审批、发货或处理调拨单。',
      count: pendingTransfers.value,
      to: transferRoute.value,
    })
  }
  if (Number(overview.value?.summary.lowStockCount || 0) > 0) {
    rows.push({
      key: 'low-stock',
      title: '低库存物料',
      detail: '请查看库存风险并安排补货。',
      count: Number(overview.value?.summary.lowStockCount || 0),
      to: warehouseHomeRoute.value,
    })
  }
  if (Number(overview.value?.summary.expiringCount || 0) > 0) {
    rows.push({
      key: 'expiring',
      title: '临期物料',
      detail: '请核对批次并优先处理临期风险。',
      count: Number(overview.value?.summary.expiringCount || 0),
      to: warehouseHomeRoute.value,
    })
  }
  if (pendingRequisitions.value.length > 0) {
    rows.push({
      key: 'requisitions',
      title: '门店叫货单',
      detail: '请处理门店提交的叫货需求。',
      count: pendingRequisitions.value.length,
      to: requisitionRoute.value,
    })
  }
  return rows
})

const tabs = computed<Array<{ key: string; label: string; badge?: number; to: RouteLocationRaw }>>(() => {
  const rows: Array<{ key: string; label: string; badge?: number; to: RouteLocationRaw }> = []
  rows.push({ key: 'inventory', label: '库存', to: warehouseHomeRoute.value })
  if (hasTransferRoute.value) {
    rows.push({ key: 'transfers', label: '调拨', badge: pendingTransfers.value, to: transferRoute.value })
  }
  rows.push({ key: 'requisitions', label: '门店叫货', badge: pendingRequisitions.value.length, to: requisitionRoute.value })
  if (canPurchase.value && selectedWarehouse.value?.type === 'CENTRAL') {
    rows.push({ key: 'purchase', label: '外部采购', to: '/warehouse/purchase' })
  }
  rows.push({ key: 'movements', label: '出入库记录', to: '/warehouse/movements' })
  return rows
})
const activeNavigationKey = computed(() => {
  return currentTab() === 'warehouse' ? 'inventory' : currentTab()
})

const tabRoutes: Record<WarehouseTab, string> = {
  overview: '/warehouse',
  warehouse: '/warehouse/central',
  transfers: '/warehouse/transfers',
  inventory: '/warehouse/inventory',
  requisitions: '/warehouse/requests',
  purchase: '/warehouse/purchase',
  catalog: '/warehouse/items',
  movements: '/warehouse/movements',
  returns: '/warehouse/returns',
  alerts: '/warehouse/alerts',
  receipts: '/warehouse/receipts',
  prints: '/warehouse/receipts',
}

function routeTab() {
  const metaTab = route.meta.warehouseTab
  const raw = typeof metaTab === 'string'
    ? metaTab
    : (Array.isArray(route.query.tab) ? route.query.tab[0] : route.query.tab)
  if (raw === 'alerts') return 'inventory'
  if (raw === 'receipts' || raw === 'prints') return 'movements'
  return raw
}

function currentTab() {
  return warehouse.activeTab
}

async function ensureRequisitionStoresLoaded() {
  if (requisitionStoresAttempted.value) return
  requisitionStoresAttempted.value = true
  try {
    requisitionStores.value = await getStores()
  } catch {
    // 兼容没有门店档案读取权限的旧仓管账号；面板仍会从可见叫货单生成安全的门店选项。
    requisitionStores.value = []
  }
}

async function ensureItemScopeContextLoaded() {
  if (itemScopeContextAttempted.value) return
  itemScopeContextAttempted.value = true
  try {
    itemScopeContext.value = await getWarehouseItemRequisitionScopeContext()
  } catch {
    itemScopeContext.value = null
  }
}

async function setTab(tab: WarehouseTab) {
  try {
    await router.push(
      tab === 'transfers'
        ? transferRoute.value
        : tab === 'requisitions'
          ? requisitionRoute.value
          : (tab === 'warehouse' || tab === 'inventory' ? warehouseHomeRoute.value : tabRoutes[tab]),
    )
  } catch {
    // 当前路由已是目标页时无需提示。
  }
}

async function openWarehouse(target: WarehouseInfo) {
  try {
    if (String(warehouse.selectedWarehouseId) !== String(target.id)) await warehouse.selectWarehouse(target.id)
    await router.push({ name: 'warehouse-detail', params: { warehouseId: String(target.id) } })
  } catch {
    // store 已保留业务错误提示。
  }
}

async function selectWarehouse(event: Event) {
  const warehouseId = (event.target as HTMLSelectElement).value
  const target = accessibleWarehouses.value.find((row) => String(row.id) === warehouseId)
  if (!target) return
  const queryRoutePath = currentWarehouseQueryRoutePath()
  if (queryRoutePath) {
    await router.replace({
      path: queryRoutePath,
      query: { ...route.query, warehouseId: String(target.id) },
    })
    return
  }
  await openWarehouse(target)
}

function currentWarehouseQueryRoutePath() {
  if (route.name === 'warehouse-transfers') return '/warehouse/transfers'
  if (route.name === 'warehouse-requests') return '/warehouse/requests'
  return ''
}

async function syncWarehouseFromRoute() {
  const routeWarehouseParam = Array.isArray(route.params.warehouseId)
    ? route.params.warehouseId[0]
    : route.params.warehouseId
  const routeWarehouseId = Array.isArray(route.query.warehouseId)
    ? route.query.warehouseId[0]
    : route.query.warehouseId
  const isTransferRoute = route.name === 'warehouse-transfers'
  const queryRoutePath = currentWarehouseQueryRoutePath()
  const warehouseCode = queryRoutePath ? '' : String(route.meta.warehouseCode || '')
  const target = routeWarehouseParam
    ? accessibleWarehouses.value.find((row) => String(row.id) === String(routeWarehouseParam))
    : queryRoutePath && routeWarehouseId
    ? accessibleWarehouses.value.find((row) => String(row.id) === String(routeWarehouseId))
    : accessibleWarehouses.value.find((row) => row.code === warehouseCode)
  if (target && String(target.id) !== String(warehouse.selectedWarehouseId)) {
    try {
      await warehouse.selectWarehouse(target.id)
    } catch {
      // 无权访问时由后端和路由守卫保持当前范围。
    }
  }
  if (!queryRoutePath || !warehouse.selectedWarehouseId) return
  const selected = accessibleWarehouses.value.find((row) => (
    String(row.id) === String(warehouse.selectedWarehouseId)
  ))
  if (!selected) return
  if (String(routeWarehouseId || '') !== String(selected.id)) {
    await router.replace({
      path: queryRoutePath,
      query: { ...route.query, warehouseId: String(selected.id) },
    })
    return
  }
  if (!isTransferRoute) return
  try {
    await Promise.all([
      warehouse.loadTransfers(selected.id),
      warehouse.loadTransferContext(selected.id),
    ])
  } catch {
    // store 已保留业务错误提示。
  }
}

function transferRouteLabel(transferId: string | undefined) {
  const transfer = warehouse.transfers.find((row) => row.id === transferId)
  return transfer ? `${transfer.sourceWarehouseName} → ${transfer.targetWarehouseName}` : '当前路线'
}

async function createTransfer(payload: WarehouseTransferCreatePayload) {
  try {
    await warehouse.createTransfer(payload)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function submitTransfer(id: string) {
  try {
    await warehouse.submitTransfer(id)
  } catch {
    // store 已保留业务错误提示。
  }
}

function requestTransferAction(kind: WarehouseConfirmation['kind'], id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = kind === 'reject-transfer' ? '当前申请需要调整后重新提交' : ''
  pendingConfirmation.value = { kind, id } as WarehouseConfirmation
}

function statusClass(severity: string) {
  return severity === 'RISK' ? 'risk' : 'warn'
}

async function openCreateItem() {
  await ensureItemScopeContextLoaded()
  editorItem.value = null
  editorOpen.value = true
}

async function openEditItem(item: WarehouseItem) {
  await ensureItemScopeContextLoaded()
  editorItem.value = item
  editorOpen.value = true
}

async function saveItem(payload: WarehouseItemPayload) {
  try {
    await warehouse.saveItem(payload)
    editorOpen.value = false
  } catch {
    // store 已保留业务错误提示。
  }
}

async function setItemEnabled(item: WarehouseItem, enabled: boolean) {
  try {
    await warehouse.setItemEnabled(item.id, enabled)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function saveCategory(payload: { id?: number; name: string; parentId?: number | null; sortOrder?: number; enabled?: boolean }) {
  try {
    await warehouse.saveCategory(payload)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function deleteCategory(id: number) {
  try {
    await warehouse.deleteCategory(id)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function approveRequisition(id: string) {
  try {
    await warehouse.approveRequisition(id)
  } catch {
    // store 已保留业务错误提示。
  }
}

function rejectRequisition(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = ''
  pendingConfirmation.value = { kind: 'reject-requisition', id }
}

function shipRequisition(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = ''
  pendingConfirmation.value = { kind: 'ship-requisition', id }
}

function fulfillAvailableRequisition(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = ''
  pendingConfirmation.value = { kind: 'fulfill-available-requisition', id }
}

function markBackorderRequisition(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = '已标记缺货，待安排补货'
  pendingConfirmation.value = { kind: 'mark-backorder-requisition', id }
}

function waitReplenishmentRequisition(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = '等待补货后继续发货'
  pendingConfirmation.value = { kind: 'wait-replenishment-requisition', id }
}

async function createPurchaseOrder(payload: Omit<WarehousePurchaseOrderCreatePayload, 'warehouseId'>) {
  try {
    await warehouse.createPurchaseOrder(payload)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function approvePurchaseOrder(purchaseOrderId: string) {
  try {
    await warehouse.approvePurchaseOrder(purchaseOrderId)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function receivePurchaseOrder(purchaseOrderId: string, payload: WarehousePurchaseOrderReceivePayload) {
  try {
    await warehouse.receivePurchaseOrder(purchaseOrderId, payload)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function saveAlert(itemId: number, payload: { minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number }) {
  try {
    await warehouse.saveAlertSettings(itemId, payload)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function approveReturn(id: string) {
  try {
    await warehouse.reviewReturn(id, true, '仓库管理员审核通过')
  } catch {
    // store 已保留业务错误提示。
  }
}

function rejectReturn(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = '退货数量或商品状态需要重新确认'
  pendingConfirmation.value = { kind: 'reject-return', id }
}

function receiveReturn(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = ''
  pendingConfirmation.value = { kind: 'receive-return', id }
}

function cancelWarehouseConfirmation() {
  if (confirmationBusy.value) return
  pendingConfirmation.value = null
  confirmationNote.value = ''
}

async function confirmWarehouseAction() {
  const action = pendingConfirmation.value
  if (!action || confirmationBusy.value) return
  confirmationBusy.value = true
  try {
    switch (action.kind) {
      case 'reject-requisition':
        await warehouse.rejectRequisition(action.id, confirmationNote.value)
        break
      case 'ship-requisition':
        await warehouse.shipRequisition(action.id)
        break
      case 'fulfill-available-requisition':
        await warehouse.fulfillAvailableRequisition(action.id)
        break
      case 'mark-backorder-requisition':
        await warehouse.markRequisitionBackordered(action.id, 'MARK_BACKORDER', confirmationNote.value)
        break
      case 'wait-replenishment-requisition':
        await warehouse.markRequisitionBackordered(action.id, 'WAIT_REPLENISHMENT', confirmationNote.value)
        break
      case 'reject-return':
        await warehouse.reviewReturn(action.id, false, confirmationNote.value)
        break
      case 'receive-return':
        await warehouse.receiveReturn(action.id, '仓库管理员确认退货入库')
        break
      case 'approve-transfer':
        await warehouse.reviewTransfer(action.id, true, confirmationNote.value || undefined)
        break
      case 'reject-transfer':
        await warehouse.reviewTransfer(action.id, false, confirmationNote.value || undefined)
        break
      case 'ship-transfer':
        await warehouse.shipTransfer(action.id, `ship-${crypto.randomUUID()}`, confirmationNote.value || undefined)
        break
      case 'receive-transfer':
        await warehouse.receiveTransfer(action.id, {
          clientRequestId: `receive-${crypto.randomUUID()}`,
          note: confirmationNote.value || undefined,
        })
        break
      case 'cancel-transfer':
        await warehouse.cancelTransfer(action.id, `cancel-${crypto.randomUUID()}`, confirmationNote.value || undefined)
        break
    }
  } catch {
    // store 已保留业务错误提示。
  } finally {
    confirmationBusy.value = false
    pendingConfirmation.value = null
    confirmationNote.value = ''
  }
}

async function downloadReceipt(batchId: number, itemName: string, batchNo: string) {
  try {
    await warehouse.downloadPdf('receipt', `/api/warehouse/print/receipts/${batchId}`, `入库单-${itemName}-${batchNo}.pdf`)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function downloadDelivery(requisitionId: string) {
  try {
    await warehouse.downloadPdf('delivery', `/api/warehouse/print/requisitions/${encodeURIComponent(requisitionId)}/delivery`, `配送单-${requisitionId}.pdf`)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function downloadMovement(movementId: number, itemName: string, movementType = '') {
  try {
    const prefix = movementType === 'IN' ? '入库单' : '库存流水单'
    await warehouse.downloadPdf('movement', `/api/warehouse/print/movements/${movementId}`, `${prefix}-${itemName}-${movementId}.pdf`)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function downloadReturn(returnId: string, returnNo: string) {
  try {
    await warehouse.downloadPdf('return', `/api/warehouse/print/returns/${encodeURIComponent(returnId)}`, `配送退货单-${returnNo}.pdf`)
  } catch {
    // store 已保留业务错误提示。
  }
}

watch(
  () => route.fullPath,
  () => {
    const tab = routeTab()
    warehouse.setTab(tab)
    if (tab === 'requisitions') void ensureRequisitionStoresLoaded()
  },
  { immediate: true },
)
watch(
  [() => route.fullPath, () => accessibleWarehouses.value.map((row) => `${row.id}:${row.code}`).join('|')],
  () => { void syncWarehouseFromRoute() },
  { immediate: true },
)
</script>

<template>
  <div class="warehouse-workbench">
    <section v-if="currentTab() !== 'overview' && selectedWarehouse" class="warehouse-context-section">
      <section class="warehouse-context-header">
        <div class="warehouse-context-copy">
          <div class="warehouse-breadcrumb">
            <RouterLink to="/warehouse">仓库中心</RouterLink>
            <span>/</span>
            <strong>{{ selectedWarehouse.name }}</strong>
          </div>
          <div class="warehouse-title-line">
            <Warehouse :size="22" aria-hidden="true" />
            <h2>{{ selectedWarehouse.name }}</h2>
          </div>
          <div class="warehouse-context-tags" aria-label="仓库身份与能力">
            <span class="warehouse-type-tag">{{ selectedWarehouse.type === 'CENTRAL' ? '总仓' : '区域分仓' }}</span>
            <span>{{ selectedWarehouse.regionCode === 'JINGZHOU' ? '荆州区域' : '山东区域' }}</span>
            <span v-for="capability in warehouseCapabilities" :key="capability">{{ capability }}</span>
          </div>
        </div>
        <label v-if="showWarehouseSwitcher" class="warehouse-switcher">
          <span>切换仓库</span>
          <select :value="String(selectedWarehouse.id)" aria-label="当前仓库" @change="selectWarehouse">
            <option v-for="row in accessibleWarehouses" :key="row.id" :value="String(row.id)">{{ row.name }}</option>
          </select>
        </label>
      </section>

      <SecondaryNavigation
        class="warehouse-business-navigation"
        :items="tabs"
        :model-value="activeNavigationKey"
        label="仓库业务导航"
      />
    </section>

    <WarehouseNetworkOverview
      v-if="currentTab() === 'overview'"
      :warehouses="accessibleWarehouses"
      @open="openWarehouse"
    />

    <WarehouseStatCards
      v-if="isWarehouseEntrance"
      :stock-value="Number(overview?.summary.stockValue || 0)"
      :low-stock-count="overview?.summary.lowStockCount || 0"
      :expiring-count="overview?.summary.expiringCount || 0"
      :pending-transfer-count="pendingTransfers"
      :pending-requisition-count="overview?.summary.pendingRequisitionCount || 0"
    />

    <section v-if="isWarehouseEntrance" class="warehouse-operations-overview" aria-labelledby="warehouse-overview-heading">
      <div class="warehouse-operations-main">
        <div class="warehouse-overview-title">
          <span aria-hidden="true"></span>
          <h3 id="warehouse-overview-heading">运营概览</h3>
        </div>

        <section class="warehouse-priority-panel" aria-labelledby="warehouse-priority-heading">
          <div class="workbench-section-heading">
            <div>
              <span>今日工作重点</span>
              <h3 id="warehouse-priority-heading">待优先处理</h3>
            </div>
          </div>
          <div v-if="priorityItems.length" class="priority-list">
            <RouterLink v-for="item in priorityItems" :key="item.key" class="priority-item" :to="item.to">
              <span class="priority-item__count">{{ item.count }}</span>
              <span class="priority-item__copy"><b>{{ item.title }}</b><small>{{ item.detail }}</small></span>
              <ArrowRight :size="18" aria-hidden="true" />
            </RouterLink>
          </div>
          <div v-else class="priority-empty">当前没有需要优先处理的事项。</div>
        </section>

        <div class="warehouse-alerts-panel">
          <div class="table-heading">
            <div><h3>库存预警</h3></div>
            <button class="mini-button" type="button" @click="setTab('inventory')">查看库存</button>
          </div>
          <div v-if="alerts.length" class="overview-list">
            <div v-for="alert in alerts.slice(0, 6)" :key="`${alert.type}-${alert.itemId}`" class="overview-list-row">
              <b :class="statusClass(alert.severity)">{{ alert.type === 'EXPIRING' ? '临期' : '低库存' }}</b>
              <strong>{{ alert.itemName }}</strong>
              <span>{{ alert.message }}</span>
            </div>
          </div>
          <div v-else class="empty-state compact">当前没有库存预警。</div>
        </div>
      </div>

      <aside class="warehouse-operations-rail">
        <section class="warehouse-action-panel" aria-labelledby="warehouse-actions-heading">
          <div class="workbench-section-heading">
            <div>
              <span>按当前仓库权限执行</span>
              <h3 id="warehouse-actions-heading">快捷处理</h3>
            </div>
          </div>
          <div class="warehouse-action-list">
            <RouterLink v-if="primaryAction" class="primary-button warehouse-primary-action" :to="primaryAction.to">
              <ClipboardList :size="18" />{{ primaryAction.label }}
            </RouterLink>
            <RouterLink v-if="selectedWarehouse?.type === 'CENTRAL' && canPurchase" class="secondary-action" to="/warehouse/purchase">
              <PackagePlus :size="18" />外部采购入库
            </RouterLink>
            <RouterLink v-if="canConfigureWarehouse && warehouseDetailSection !== 'catalog'" class="text-action" to="/warehouse/items">物料档案 <ArrowRight :size="15" /></RouterLink>
            <RouterLink v-if="warehouseDetailSection === 'catalog'" class="text-action" :to="warehouseHomeRoute">返回库存 <ArrowRight :size="15" /></RouterLink>
            <span v-if="!primaryAction && !(selectedWarehouse?.type === 'CENTRAL' && canPurchase)" class="action-empty">当前账号没有可执行的仓库操作。</span>
          </div>
        </section>

        <section class="warehouse-requisition-overview">
          <div class="table-heading">
            <div><h3>门店叫货</h3></div>
            <button class="mini-button" type="button" @click="setTab('requisitions')">查看全部</button>
          </div>
          <div v-if="pendingRequisitions.length" class="overview-list">
            <div v-for="row in pendingRequisitions.slice(0, 6)" :key="row.id" class="overview-list-row order-row">
              <strong>{{ row.storeName || row.storeId }}</strong>
              <span>{{ row.lines.map((line) => `${line.itemName} × ${line.requestedQuantity}${line.unit || ''}`).join('，') }}</span>
              <b>{{ row.statusLabel || row.status }}</b>
            </div>
          </div>
          <div v-else class="empty-state compact">当前没有待处理叫货。</div>
        </section>
      </aside>
    </section>

    <WarehouseInventoryPanel
      v-if="currentTab() === 'warehouse' && warehouseDetailSection === 'inventory'"
      :items="items"
      :categories="warehouse.categories"
      :selected-category="warehouse.selectedCategory"
      :batches="stockBatches"
      :movements="movements"
      :downloading-id="warehouse.downloadingId"
      :actioning-id="warehouse.actioningId"
      :can-manage="canConfigureWarehouse"
      :warehouse-name="selectedWarehouse?.name || '当前仓库'"
      @select-category="warehouse.setCategory"
      @save-category="saveCategory"
      @delete-category="deleteCategory"
      @create-item="openCreateItem"
      @edit-item="openEditItem"
      @set-item-enabled="setItemEnabled"
      @download-movement="downloadMovement"
    />

    <WarehouseTransferPanel
      v-else-if="currentTab() === 'transfers'"
      :transfers="warehouse.transfers"
      :context="warehouse.transferContext"
      :actioning-id="warehouse.actioningId"
      @create="createTransfer"
      @submit="submitTransfer"
      @approve="requestTransferAction('approve-transfer', $event)"
      @reject="requestTransferAction('reject-transfer', $event)"
      @ship="requestTransferAction('ship-transfer', $event)"
      @receive="requestTransferAction('receive-transfer', $event)"
      @cancel="requestTransferAction('cancel-transfer', $event)"
    />

    <section
      v-else-if="currentTab() === 'requisitions'"
      class="section-stack"
    >
      <WarehouseRequisitionSummaryExport
        v-if="canExportRequisitionSummary"
        :warehouse-id="selectedWarehouse?.id"
        :stores="requisitionStores"
        :items="items"
      />
      <WarehouseRequisitionPanel
        :requisitions="requisitions"
        :items="items"
        :stores="requisitionStores"
        :actioning-id="warehouse.actioningId"
        :downloading-id="warehouse.downloadingId"
        :can-manage="canProcessRequisition"
        @approve="approveRequisition"
        @fulfill-available="fulfillAvailableRequisition"
        @mark-backorder="markBackorderRequisition"
        @wait-replenishment="waitReplenishmentRequisition"
        @reject="rejectRequisition"
        @ship="shipRequisition"
        @download-delivery="downloadDelivery"
      />
    </section>

    <WarehousePurchaseReceivePanel
      v-else-if="currentTab() === 'purchase' && selectedWarehouse?.type === 'CENTRAL' && canPurchase"
      :items="activeItems"
      :batches="stockBatches"
      :suppliers="suppliers"
      :purchase-orders="purchaseOrders"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      :can-manage="canPurchase"
      :warehouse-name="centralWarehouse?.name || '荆州总仓'"
      :success-message="warehouse.actionMessage"
      mode="receive"
      @create-order="createPurchaseOrder"
      @approve-order="approvePurchaseOrder"
      @receive-order="receivePurchaseOrder"
      @download-receipt="downloadReceipt"
    />

    <div v-else-if="currentTab() === 'purchase'" class="content-card empty-cell">
      当前账号没有可办理外部采购的总仓范围。山东分仓只能向荆州总仓申请调拨补货。
    </div>

    <WarehouseMaterialCatalogPanel
      v-else-if="currentTab() === 'warehouse' && warehouseDetailSection === 'catalog'"
      :items="items"
      :categories="warehouse.categories"
      :selected-category="warehouse.selectedCategory"
      :can-manage="canConfigureWarehouse"
      :actioning-id="warehouse.actioningId"
      @select-category="warehouse.setCategory"
      @save-category="saveCategory"
      @delete-category="deleteCategory"
      @create-item="openCreateItem"
      @edit-item="openEditItem"
      @set-item-enabled="setItemEnabled"
    />

    <section v-else-if="currentTab() === 'movements'" class="section-stack">
      <WarehouseMovementPanel
        :movements="movements"
        :downloading-id="warehouse.downloadingId"
        @download-movement="downloadMovement"
        @download-delivery="downloadDelivery"
      />
      <WarehouseReturnPanel
        :returns="returns"
        :actioning-id="warehouse.actioningId"
        :downloading-id="warehouse.downloadingId"
        :can-manage="canManage"
        @approve="approveReturn"
        @reject="rejectReturn"
        @receive="receiveReturn"
        @download="downloadReturn"
      />
    </section>

    <WarehouseReturnPanel
      v-else-if="currentTab() === 'returns'"
      :returns="returns"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      :can-manage="canManage"
      @approve="approveReturn"
      @reject="rejectReturn"
      @receive="receiveReturn"
      @download="downloadReturn"
    />

    <WarehouseAlertPanel
      v-else-if="currentTab() === 'alerts'"
      :items="activeItems"
      :actioning-id="warehouse.actioningId"
      :can-manage="canManage"
      @save="saveAlert"
    />

    <WarehouseMaterialEditor
      v-if="editorOpen"
      :item="editorItem"
      :categories="warehouse.categories"
      :stores="itemScopeContext?.stores || []"
      :regions="itemScopeRegions"
      :scope-context-available="Boolean(itemScopeContext)"
      :saving="warehouse.actioningId.startsWith('item:')"
      @close="editorOpen = false"
      @save="saveItem"
    />

    <ActionConfirmDialog
      v-model="confirmationNote"
      :open="Boolean(pendingConfirmation)"
      :title="confirmationCopy.title"
      :message="confirmationCopy.message"
      :confirm-label="confirmationCopy.confirmLabel"
      :confirm-variant="confirmationCopy.confirmVariant"
      :note-label="confirmationCopy.noteLabel"
      :note-required="confirmationNoteRequired"
      :busy="confirmationBusy"
      @cancel="cancelWarehouseConfirmation"
      @confirm="confirmWarehouseAction"
    />
  </div>
</template>

<style scoped>
.warehouse-workbench,
.section-stack {
  display: grid;
  gap: 16px;
}

.warehouse-workbench {
  width: min(100%, 1280px);
  margin: 0 auto;
}

.warehouse-context-section {
  display: grid;
  gap: 14px;
  padding-top: 2px;
}

.warehouse-context-header {
  display: flex;
  min-width: 0;
  align-items: flex-end;
  gap: 20px;
}

.warehouse-context-copy {
  display: grid;
  min-width: 0;
  gap: 8px;
  margin-right: auto;
}

.warehouse-breadcrumb,
.warehouse-title-line,
.warehouse-context-tags,
.warehouse-switcher,
.warehouse-action-list,
.priority-item {
  display: flex;
  align-items: center;
}

.warehouse-breadcrumb {
  gap: 7px;
  color: var(--ds-muted);
  font-size: 13px;
}

.warehouse-breadcrumb a {
  color: var(--ds-secondary);
  text-decoration: none;
}

.warehouse-breadcrumb a:hover {
  color: var(--ds-primary-hover);
}

.warehouse-breadcrumb strong {
  color: var(--ds-ink);
}

.warehouse-title-line {
  gap: 9px;
  color: var(--ds-primary-hover);
}

.warehouse-title-line h2 {
  margin: 0;
  color: var(--ds-ink);
  font-size: clamp(22px, 2.3vw, 28px);
  line-height: 1.15;
}

.warehouse-context-tags {
  flex-wrap: wrap;
  gap: 7px;
}

.warehouse-context-tags span {
  min-height: 26px;
  display: inline-flex;
  align-items: center;
  padding: 0 9px;
  border-radius: 999px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 12px;
  font-weight: 700;
}

.warehouse-context-tags .warehouse-type-tag {
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.warehouse-switcher > span {
  color: var(--ds-muted);
  font-size: 12px;
}

.warehouse-switcher {
  min-height: 44px;
  flex: 0 0 auto;
  gap: 7px;
  white-space: nowrap;
}

.warehouse-switcher select {
  min-width: 150px;
  min-height: 44px;
}

.warehouse-operations-overview {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(310px, 0.9fr);
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--ds-line);
  border-radius: 12px;
  background: var(--ds-surface);
}

.warehouse-operations-main,
.warehouse-operations-rail,
.warehouse-priority-panel,
.warehouse-action-panel,
.warehouse-alerts-panel,
.warehouse-requisition-overview {
  min-width: 0;
}

.warehouse-operations-main {
  padding: 20px 22px 18px;
}

.warehouse-operations-rail {
  display: grid;
  align-content: start;
  background: color-mix(in srgb, var(--ds-surface-muted) 52%, var(--ds-surface));
  border-left: 1px solid var(--ds-line);
}

.warehouse-overview-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
}

.warehouse-overview-title > span {
  width: 4px;
  height: 20px;
  border-radius: 999px;
  background: var(--ds-primary-hover);
}

.warehouse-overview-title h3 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 17px;
  line-height: 1.2;
}

.warehouse-priority-panel {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--ds-line);
}

.warehouse-alerts-panel {
  padding-top: 16px;
}

.warehouse-action-panel,
.warehouse-requisition-overview {
  padding: 20px;
}

.warehouse-requisition-overview {
  border-top: 1px solid var(--ds-line);
}

.workbench-section-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  margin-bottom: 10px;
}

.workbench-section-heading span {
  display: block;
  margin-bottom: 4px;
  color: var(--ds-muted);
  font-size: 12px;
  font-weight: 700;
}

.workbench-section-heading h3 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 17px;
}

.priority-list {
  display: grid;
}

.priority-item {
  min-height: 56px;
  gap: 11px;
  padding: 8px 0;
  border-bottom: 1px solid var(--ds-line);
  color: inherit;
  text-decoration: none;
}

.priority-item:last-child {
  border-bottom: 0;
}

.priority-item:hover .priority-item__copy b,
.priority-item:hover > svg {
  color: var(--ds-primary-hover);
}

.priority-item__count {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
  font-size: 13px;
  font-weight: 800;
}

.priority-item__copy {
  display: grid;
  min-width: 0;
  gap: 2px;
  margin-right: auto;
}

.priority-item__copy b {
  color: var(--ds-ink);
  font-size: 14px;
}

.priority-item__copy small,
.priority-empty,
.action-empty {
  color: var(--ds-muted);
  font-size: 12px;
  line-height: 1.45;
}

.priority-empty {
  display: grid;
  min-height: 82px;
  place-items: center;
  border-radius: 9px;
  background: var(--ds-surface-muted);
  text-align: center;
}

.warehouse-action-list {
  align-items: stretch;
  flex-direction: column;
  gap: 8px;
}

.warehouse-primary-action,
.secondary-action {
  width: 100%;
  min-height: 44px;
  box-sizing: border-box;
  justify-content: center;
  text-decoration: none;
}

.secondary-action {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 13px;
  border: 1px solid var(--ds-line-strong, var(--ds-line));
  border-radius: 7px;
  background: var(--ds-surface);
  color: var(--ds-primary-hover);
  font-size: 14px;
  font-weight: 800;
  text-decoration: none;
}

.secondary-action:hover {
  border-color: var(--ds-primary);
  background: var(--ds-primary-soft);
}

.text-action {
  display: inline-flex;
  width: fit-content;
  min-height: 34px;
  align-items: center;
  gap: 5px;
  color: var(--ds-primary-hover);
  font-size: 13px;
  font-weight: 800;
  text-decoration: none;
}

.warehouse-business-navigation {
  width: 100%;
  border-bottom: 1px solid var(--ds-line);
}

.warehouse-business-navigation :deep(.secondary-navigation) {
  gap: 3px;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.warehouse-business-navigation :deep(.secondary-navigation__item) {
  min-height: 44px;
  padding: 10px 14px 8px;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--ds-secondary);
}

.warehouse-business-navigation :deep(.secondary-navigation__item:hover) {
  background: var(--ds-surface-muted);
}

.warehouse-business-navigation :deep(.secondary-navigation__item--active) {
  border-bottom-color: var(--ds-primary);
  background: transparent;
  box-shadow: none;
  color: var(--ds-primary-hover);
}

.overview-list {
  display: grid;
}

.overview-list-row {
  display: grid;
  grid-template-columns: 58px minmax(104px, 0.7fr) minmax(0, 1.8fr);
  gap: 10px;
  align-items: center;
  min-height: 40px;
  border-bottom: 1px solid #edf1f2;
  color: #687586;
  font-size: 13px;
}

.overview-list-row:last-child {
  border-bottom: 0;
}

.overview-list-row strong {
  color: var(--ink);
}

.overview-list-row b.warn {
  color: #db7b2c;
}

.overview-list-row b.risk {
  color: var(--bad);
}

.overview-list-row.order-row {
  grid-template-columns: minmax(86px, 0.8fr) minmax(0, 1.6fr) auto;
}

.overview-list-row.order-row b {
  color: #d17b35;
  font-size: 12px;
}

@media (max-width: 768px) {
  .warehouse-context-header {
    align-items: stretch;
    flex-direction: column;
  }

  .warehouse-switcher,
  .warehouse-switcher select {
    width: 100%;
  }

  .warehouse-operations-overview {
    grid-template-columns: 1fr;
  }

  .warehouse-operations-rail {
    border-top: 1px solid var(--ds-line);
    border-left: 0;
  }

  .warehouse-operations-main,
  .warehouse-action-panel,
  .warehouse-requisition-overview {
    padding: 16px;
  }

  .overview-list-row,
  .overview-list-row.order-row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .overview-list-row > span {
    grid-column: 1 / -1;
  }
}
</style>
