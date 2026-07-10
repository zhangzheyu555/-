<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WarehouseAlertPanel from '../components/warehouse/WarehouseAlertPanel.vue'
import WarehouseInventoryPanel from '../components/warehouse/WarehouseInventoryPanel.vue'
import WarehouseMaterialCatalogPanel from '../components/warehouse/WarehouseMaterialCatalogPanel.vue'
import WarehouseMaterialEditor from '../components/warehouse/WarehouseMaterialEditor.vue'
import WarehouseMovementPanel from '../components/warehouse/WarehouseMovementPanel.vue'
import WarehousePurchaseReceivePanel from '../components/warehouse/WarehousePurchaseReceivePanel.vue'
import WarehouseRequisitionPanel from '../components/warehouse/WarehouseRequisitionPanel.vue'
import WarehouseReturnPanel from '../components/warehouse/WarehouseReturnPanel.vue'
import WarehouseStatCards from '../components/warehouse/WarehouseStatCards.vue'
import { useWarehouseStore, type WarehouseTab } from '../stores/warehouse'
import type { WarehouseItem, WarehouseItemPayload } from '../api/warehouse'

withDefaults(defineProps<{
  canManage?: boolean
}>(), {
  canManage: false,
})

const warehouse = useWarehouseStore()
const route = useRoute()
const router = useRouter()
const editorOpen = ref(false)
const editorItem = ref<WarehouseItem | null>(null)

const overview = computed(() => warehouse.overview)
const items = computed(() => overview.value?.items || [])
const activeItems = computed(() => items.value.filter((item) => item.active !== false))
const requisitions = computed(() => overview.value?.requisitions || [])
const pendingRequisitions = computed(() => requisitions.value.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status)))
const stockBatches = computed(() => overview.value?.stockBatches || [])
const movements = computed(() => overview.value?.movements || [])
const returns = computed(() => warehouse.returns || [])
const alerts = computed(() => overview.value?.alerts || [])

const tabs: Array<{ key: WarehouseTab; label: string; count?: number }> = [
  { key: 'overview', label: '仓库总览' },
  { key: 'inventory', label: '库存物料' },
  { key: 'requisitions', label: '门店叫货', count: pendingRequisitions.value.length },
  { key: 'purchase', label: '采购入库' },
  { key: 'catalog', label: '物料档案' },
  { key: 'movements', label: '出入库记录' },
]

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

function tabCount(key: WarehouseTab) {
  if (key === 'requisitions') return pendingRequisitions.value.length
  return undefined
}

async function setTab(tab: WarehouseTab) {
  warehouse.setTab(tab)
  try {
    await router.replace({ path: '/warehouse', query: tab === 'overview' ? {} : { tab } })
  } catch {
    // 当前路由已是目标页时无需提示。
  }
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

async function rejectRequisition(id: string) {
  const note = window.prompt('请输入驳回原因', '库存不足或叫货信息需要重新确认')
  if (note === null) return
  try {
    await warehouse.rejectRequisition(id, note)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function shipRequisition(id: string) {
  if (!window.confirm('确认按先进先出规则发货并扣减总仓库存吗？')) return
  try {
    await warehouse.shipRequisition(id)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function receiveStock(payload: {
  itemId: number
  batchNo: string
  receivedDate: string
  expiryDate?: string
  quantity: number
  unitCost: number
  note?: string
  clientRequestId: string
}) {
  try {
    await warehouse.receiveStock(payload)
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

async function rejectReturn(id: string) {
  const note = window.prompt('请输入驳回原因', '退货数量或商品状态需要重新确认')
  if (note === null) return
  try {
    await warehouse.reviewReturn(id, false, note)
  } catch {
    // store 已保留业务错误提示。
  }
}

async function receiveReturn(id: string) {
  if (!window.confirm('确认已收到门店退回商品并入库吗？')) return
  try {
    await warehouse.receiveReturn(id, '仓库管理员确认退货入库')
  } catch {
    // store 已保留业务错误提示。
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
</script>

<template>
  <div class="warehouse-workbench">
    <WarehouseStatCards
      :item-count="overview?.summary.itemCount || activeItems.length"
      :stock-value="Number(overview?.summary.stockValue || 0)"
      :low-stock-count="overview?.summary.lowStockCount || 0"
      :expiring-count="overview?.summary.expiringCount || 0"
      :pending-requisition-count="overview?.summary.pendingRequisitionCount || 0"
    />

    <div class="warehouse-tabs" role="tablist" aria-label="仓库中心">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="warehouse-tab"
        :class="{ active: currentTab() === tab.key }"
        type="button"
        @click="setTab(tab.key)"
      >
        {{ tab.label }}
        <b v-if="tabCount(tab.key)">{{ tabCount(tab.key) }}</b>
      </button>
    </div>

    <section v-if="currentTab() === 'overview'" class="warehouse-overview-grid">
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
      v-else-if="currentTab() === 'inventory'"
      :items="items"
      :categories="warehouse.categories"
      :selected-category="warehouse.selectedCategory"
      :batches="stockBatches"
      :movements="movements"
      :downloading-id="warehouse.downloadingId"
      :actioning-id="warehouse.actioningId"
      :can-manage="canManage"
      @select-category="warehouse.setCategory"
      @save-category="saveCategory"
      @delete-category="deleteCategory"
      @create-item="openCreateItem"
      @edit-item="openEditItem"
      @set-item-enabled="setItemEnabled"
      @download-movement="downloadMovement"
    />

    <WarehouseRequisitionPanel
      v-else-if="currentTab() === 'requisitions'"
      :requisitions="requisitions"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      :can-manage="canManage"
      @approve="approveRequisition"
      @reject="rejectRequisition"
      @ship="shipRequisition"
      @download-delivery="downloadDelivery"
    />

    <WarehousePurchaseReceivePanel
      v-else-if="currentTab() === 'purchase'"
      :items="activeItems"
      :batches="stockBatches"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      :can-manage="canManage"
      :success-message="warehouse.actionMessage"
      mode="receive"
      @receive="receiveStock"
      @download-receipt="downloadReceipt"
    />

    <WarehouseMaterialCatalogPanel
      v-else-if="currentTab() === 'catalog'"
      :items="items"
      :categories="warehouse.categories"
      :selected-category="warehouse.selectedCategory"
      :can-manage="canManage"
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
      mode="records"
      @receive="receiveStock"
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
  </div>
</template>

<style scoped>
.warehouse-workbench,
.section-stack {
  display: grid;
  gap: 14px;
}

.warehouse-tabs {
  display: flex;
  gap: 2px;
  padding: 4px;
  border: 1px solid #dfe7e9;
  border-radius: 999px;
  background: #f4f7f8;
}

.warehouse-tab {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  padding: 6px 13px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #6c7887;
  font-weight: 800;
}

.warehouse-tab.active {
  background: #fff;
  color: var(--primary-dark);
  box-shadow: 0 2px 8px rgba(19, 35, 42, 0.08);
}

.warehouse-tab b {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--primary);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
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
</style>
