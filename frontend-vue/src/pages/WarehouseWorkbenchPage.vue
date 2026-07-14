<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SecondaryNavigation from '../components/common/SecondaryNavigation.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import WarehouseAlertPanel from '../components/warehouse/WarehouseAlertPanel.vue'
import WarehouseInventoryPanel from '../components/warehouse/WarehouseInventoryPanel.vue'
import WarehouseMaterialCatalogPanel from '../components/warehouse/WarehouseMaterialCatalogPanel.vue'
import WarehouseMaterialEditor from '../components/warehouse/WarehouseMaterialEditor.vue'
import WarehouseMovementPanel from '../components/warehouse/WarehouseMovementPanel.vue'
import WarehousePurchaseReceivePanel from '../components/warehouse/WarehousePurchaseReceivePanel.vue'
import WarehouseRequisitionPanel from '../components/warehouse/WarehouseRequisitionPanel.vue'
import WarehouseReturnPanel from '../components/warehouse/WarehouseReturnPanel.vue'
import WarehouseStatCards from '../components/warehouse/WarehouseStatCards.vue'
import WarehouseNetworkOverview from '../components/warehouse/WarehouseNetworkOverview.vue'
import WarehouseTransferPanel from '../components/warehouse/WarehouseTransferPanel.vue'
import { useWarehouseStore, type WarehouseTab } from '../stores/warehouse'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import type {
  WarehouseInfo,
  WarehouseItem,
  WarehouseItemPayload,
  WarehousePurchaseOrderCreatePayload,
  WarehousePurchaseOrderReceivePayload,
  WarehouseTransferCreatePayload,
} from '../api/warehouse'

type WarehouseConfirmation =
  | { kind: 'reject-requisition'; id: string }
  | { kind: 'ship-requisition'; id: string }
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

const overview = computed(() => warehouse.overview)
const items = computed(() => overview.value?.items || [])
const activeItems = computed(() => items.value.filter((item) => item.active !== false))
const requisitions = computed(() => overview.value?.requisitions || [])
const pendingRequisitions = computed(() => requisitions.value.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status)))
const stockBatches = computed(() => overview.value?.stockBatches || [])
const suppliers = computed(() => overview.value?.suppliers || [])
const purchaseOrders = computed(() => overview.value?.purchaseOrders || [])
const movements = computed(() => overview.value?.movements || [])
const returns = computed(() => warehouse.returns || [])
const alerts = computed(() => overview.value?.alerts || [])
const accessibleWarehouses = computed(() => warehouse.warehouses.filter((row) => row.enabled !== false))
const selectedWarehouse = computed(() => accessibleWarehouses.value.find((row) => String(row.id) === String(warehouse.selectedWarehouseId)) || null)
const centralWarehouse = computed(() => accessibleWarehouses.value.find((row) => row.type === 'CENTRAL') || null)
const regionalWarehouse = computed(() => accessibleWarehouses.value.find((row) => row.type === 'REGIONAL') || null)
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
const canRequestTransfer = computed(() => Boolean(
  regionalWarehouse.value
  && (isBoss.value || hasPermission(PERMISSIONS.WAREHOUSE_TRANSFER_REQUEST) || regionalWarehouse.value.canRequestTransfer),
))
const canApproveTransfer = computed(() => Boolean(
  centralWarehouse.value
  && (isBoss.value || hasPermission(PERMISSIONS.WAREHOUSE_TRANSFER_APPROVE) || centralWarehouse.value.canApproveTransfer),
))
const canShipTransfer = computed(() => Boolean(
  centralWarehouse.value
  && (isBoss.value || hasPermission(PERMISSIONS.WAREHOUSE_TRANSFER_SHIP) || centralWarehouse.value.canShipTransfer),
))
const canReceiveTransfer = computed(() => Boolean(
  regionalWarehouse.value
  && (isBoss.value || hasPermission(PERMISSIONS.WAREHOUSE_TRANSFER_RECEIVE) || regionalWarehouse.value.canReceiveTransfer),
))
const canProcessRequisition = computed(() => (
  isBoss.value
  || hasPermission(PERMISSIONS.WAREHOUSE_REQUISITION_PROCESS)
  || hasPermission(PERMISSIONS.WAREHOUSE_REQUISITION_REVIEW)
))
const showWarehouseSwitcher = computed(() => accessibleWarehouses.value.length > 1 && currentTab() !== 'overview')
const warehouseDetailSection = computed(() => String(route.meta.warehouseSection || 'inventory'))
const pendingTransfers = computed(() => warehouse.transfers.filter((row) => ['SUBMITTED', 'APPROVED', 'SHIPPED', 'PARTIALLY_RECEIVED'].includes(row.status)))
const confirmationCopy = computed(() => {
  switch (pendingConfirmation.value?.kind) {
    case 'reject-requisition':
      return {
        title: '驳回叫货单',
        message: '请输入驳回原因。',
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
        message: '确认荆州总仓接受该山东分仓补货申请吗？',
        confirmLabel: '审批通过',
        confirmVariant: 'primary' as const,
        noteLabel: '审批说明（选填）',
      }
    case 'reject-transfer':
      return {
        title: '驳回调拨申请',
        message: '请填写驳回原因，山东分仓可据此调整申请。',
        confirmLabel: '确认驳回',
        confirmVariant: 'danger' as const,
        noteLabel: '驳回原因',
      }
    case 'ship-transfer':
      return {
        title: '调拨发货',
        message: '发货后将扣减荆州总仓在库库存并形成在途库存。',
        confirmLabel: '确认发货',
        confirmVariant: 'primary' as const,
        noteLabel: '发货说明（选填）',
      }
    case 'receive-transfer':
      return {
        title: '调拨收货',
        message: '确认山东分仓已收到本次调拨商品并入库吗？',
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

const tabs = computed<Array<{ key: string; label: string; badge?: number; to: string }>>(() => {
  const rows: Array<{ key: string; label: string; badge?: number; to: string }> = []
  rows.push({
    key: 'overview',
    label: accessibleWarehouses.value.length > 1 || isBoss.value ? '全部仓库总览' : '仓库总览',
    to: '/warehouse',
  })
  if (centralWarehouse.value) rows.push({ key: 'central', label: '荆州总仓', to: '/warehouse/central' })
  if (regionalWarehouse.value) rows.push({ key: 'shandong', label: '山东分仓', to: '/warehouse/shandong' })
  if (canRequestTransfer.value || canApproveTransfer.value || canShipTransfer.value || canReceiveTransfer.value) {
    rows.push({ key: 'transfers', label: '仓间调拨', badge: pendingTransfers.value.length, to: '/warehouse/transfers' })
  }
  rows.push({ key: 'requisitions', label: '门店叫货', badge: pendingRequisitions.value.length, to: '/warehouse/requests' })
  if (canPurchase.value && selectedWarehouse.value?.type === 'CENTRAL') {
    rows.push({ key: 'purchase', label: '外部采购', to: '/warehouse/purchase' })
  }
  rows.push({ key: 'movements', label: '出入库记录', to: '/warehouse/movements' })
  return rows
})
const activeNavigationKey = computed(() => {
  if (currentTab() !== 'warehouse') return currentTab()
  return selectedWarehouse.value?.type === 'REGIONAL' ? 'shandong' : 'central'
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
  if (raw === 'receipts' || raw === 'prints') return 'purchase'
  return raw
}

function currentTab() {
  return warehouse.activeTab
}

async function setTab(tab: WarehouseTab) {
  try {
    await router.push(tabRoutes[tab])
  } catch {
    // 当前路由已是目标页时无需提示。
  }
}

async function openWarehouse(target: WarehouseInfo) {
  const path = target.type === 'REGIONAL' ? '/warehouse/shandong' : '/warehouse/central'
  try {
    if (String(warehouse.selectedWarehouseId) !== String(target.id)) await warehouse.selectWarehouse(target.id)
    await router.push(path)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function selectWarehouse(event: Event) {
  const warehouseId = (event.target as HTMLSelectElement).value
  const target = accessibleWarehouses.value.find((row) => String(row.id) === warehouseId)
  if (target) await openWarehouse(target)
}

async function syncWarehouseFromRoute() {
  const warehouseCode = String(route.meta.warehouseCode || '')
  if (!warehouseCode) return
  const target = accessibleWarehouses.value.find((row) => row.code === warehouseCode)
  if (target && String(target.id) !== String(warehouse.selectedWarehouseId)) {
    try {
      await warehouse.selectWarehouse(target.id)
    } catch {
      // 无权访问时由后端和路由守卫保持当前范围。
    }
  }
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

function openCreateItem() {
  editorItem.value = null
  editorOpen.value = true
}

function openEditItem(item: WarehouseItem) {
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
  confirmationNote.value = '库存不足或叫货信息需要重新确认'
  pendingConfirmation.value = { kind: 'reject-requisition', id }
}

function shipRequisition(id: string) {
  if (confirmationBusy.value) return
  confirmationNote.value = ''
  pendingConfirmation.value = { kind: 'ship-requisition', id }
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
    await warehouse.downloadPdf('delivery', `/api/warehouse/print/requisitions/${encodeURIComponent(requisitionId)}/delivery`, `出库单-${requisitionId}.pdf`)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function downloadMovement(movementId: number, itemName: string) {
  try {
    await warehouse.downloadPdf('movement', `/api/warehouse/print/movements/${movementId}`, `库存流水单-${itemName}-${movementId}.pdf`)
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
  () => warehouse.setTab(routeTab()),
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
    <SecondaryNavigation
      :items="tabs"
      :model-value="activeNavigationKey"
      label="仓库中心功能"
    />

    <div v-if="currentTab() !== 'overview' && selectedWarehouse" class="warehouse-context-bar">
      <div class="warehouse-context-copy">
        <b>{{ selectedWarehouse.name }}</b>
          <span>{{ selectedWarehouse.code }} · {{ selectedWarehouse.type === 'CENTRAL' ? '总仓' : '区域分仓' }} · {{ selectedWarehouse.regionCode === 'JINGZHOU' ? '荆州区域' : '山东区域' }}</span>
      </div>
      <label v-if="showWarehouseSwitcher" class="warehouse-switcher">
        <span>当前仓库</span>
        <select :value="String(selectedWarehouse.id)" aria-label="当前仓库" @change="selectWarehouse">
          <option v-for="row in accessibleWarehouses" :key="row.id" :value="String(row.id)">{{ row.name }}</option>
        </select>
      </label>
      <div v-if="currentTab() === 'warehouse'" class="warehouse-context-actions">
        <RouterLink v-if="selectedWarehouse.type === 'REGIONAL' && canRequestTransfer" class="primary-button" to="/warehouse/transfers">向荆州总仓申请补货</RouterLink>
        <RouterLink v-if="selectedWarehouse.type === 'CENTRAL' && canPurchase" class="primary-button" to="/warehouse/purchase">外部采购入库</RouterLink>
        <RouterLink v-if="selectedWarehouse.type === 'CENTRAL' && (canApproveTransfer || canShipTransfer)" class="ghost-button" to="/warehouse/transfers">处理分仓申请</RouterLink>
        <RouterLink v-if="canConfigureWarehouse && warehouseDetailSection !== 'catalog'" class="ghost-button" to="/warehouse/items">物料档案</RouterLink>
        <RouterLink v-if="warehouseDetailSection === 'catalog'" class="ghost-button" :to="selectedWarehouse.type === 'REGIONAL' ? '/warehouse/shandong' : '/warehouse/central'">返回库存</RouterLink>
      </div>
    </div>

    <WarehouseNetworkOverview
      v-if="currentTab() === 'overview'"
      :warehouses="accessibleWarehouses"
      @open="openWarehouse"
    />

    <WarehouseStatCards
      v-if="currentTab() === 'warehouse'"
      :item-count="overview?.summary.itemCount || activeItems.length"
      :stock-value="Number(overview?.summary.stockValue || 0)"
      :low-stock-count="overview?.summary.lowStockCount || 0"
      :expiring-count="overview?.summary.expiringCount || 0"
      :pending-requisition-count="overview?.summary.pendingRequisitionCount || 0"
    />

    <section v-if="currentTab() === 'warehouse'" class="warehouse-overview-grid">
      <div class="content-card overview-card">
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

      <div class="content-card overview-card">
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
      </div>
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
      :warehouses="accessibleWarehouses"
      :items="activeItems"
      :active-warehouse="selectedWarehouse"
      :can-request="canRequestTransfer"
      :can-approve="canApproveTransfer"
      :can-ship="canShipTransfer"
      :can-receive="canReceiveTransfer"
      :actioning-id="warehouse.actioningId"
      @create="createTransfer"
      @submit="submitTransfer"
      @approve="requestTransferAction('approve-transfer', $event)"
      @reject="requestTransferAction('reject-transfer', $event)"
      @ship="requestTransferAction('ship-transfer', $event)"
      @receive="requestTransferAction('receive-transfer', $event)"
      @cancel="requestTransferAction('cancel-transfer', $event)"
    />

    <WarehouseRequisitionPanel
      v-else-if="currentTab() === 'requisitions'"
      :requisitions="requisitions"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      :can-manage="canProcessRequisition"
      @approve="approveRequisition"
      @reject="rejectRequisition"
      @ship="shipRequisition"
      @download-delivery="downloadDelivery"
    />

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

    <WarehousePurchaseReceivePanel
      v-else-if="currentTab() === 'receipts' || currentTab() === 'prints'"
      :items="activeItems"
      :batches="stockBatches"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      :can-manage="false"
      :warehouse-name="centralWarehouse?.name || '荆州总仓'"
      mode="records"
      @download-receipt="downloadReceipt"
    />

    <WarehouseMaterialEditor
      v-if="editorOpen"
      :item="editorItem"
      :categories="warehouse.categories"
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
  gap: 18px;
}

.warehouse-context-bar {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 14px;
  padding: 10px 12px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: var(--ds-surface);
}

.warehouse-context-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
  margin-right: auto;
}

.warehouse-context-copy b {
  color: var(--ds-ink);
  font-size: 15px;
}

.warehouse-context-copy span,
.warehouse-switcher > span {
  color: var(--ds-muted);
  font-size: 12px;
}

.warehouse-switcher {
  display: flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}

.warehouse-switcher select {
  min-width: 150px;
}

.warehouse-context-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.warehouse-context-actions a {
  width: auto;
  min-height: 36px;
  text-decoration: none;
}

.warehouse-overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.overview-card {
  min-height: 216px;
}

.overview-list {
  display: grid;
}

.overview-list-row {
  display: grid;
  grid-template-columns: 58px minmax(104px, 0.7fr) minmax(0, 1.8fr);
  gap: 10px;
  align-items: center;
  min-height: 44px;
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
  grid-template-columns: minmax(100px, 0.8fr) minmax(0, 1.6fr) auto;
}

.overview-list-row.order-row b {
  color: #d17b35;
  font-size: 12px;
}

@media (max-width: 768px) {
  .warehouse-context-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .warehouse-switcher,
  .warehouse-switcher select,
  .warehouse-context-actions,
  .warehouse-context-actions a {
    width: 100%;
  }

  .warehouse-overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
