<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import WarehouseAlertPanel from '../components/warehouse/WarehouseAlertPanel.vue'
import WarehouseInventoryPanel from '../components/warehouse/WarehouseInventoryPanel.vue'
import WarehouseMovementPanel from '../components/warehouse/WarehouseMovementPanel.vue'
import WarehousePurchaseReceivePanel from '../components/warehouse/WarehousePurchaseReceivePanel.vue'
import WarehouseRequisitionPanel from '../components/warehouse/WarehouseRequisitionPanel.vue'
import WarehouseReturnPanel from '../components/warehouse/WarehouseReturnPanel.vue'
import WarehouseStatCards from '../components/warehouse/WarehouseStatCards.vue'
import { useWarehouseStore, type WarehouseTab } from '../stores/warehouse'
import type { WarehouseItem } from '../api/warehouse'

const warehouse = useWarehouseStore()
const route = useRoute()
const router = useRouter()

const overview = computed(() => warehouse.overview)
const activeItems = computed(() => (overview.value?.items || []).filter((item) => item.active !== false))
const requisitions = computed(() => overview.value?.requisitions || [])
const pendingRequisitions = computed(() => requisitions.value.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status)))
const stockBatches = computed(() => overview.value?.stockBatches || [])
const movements = computed(() => overview.value?.movements || [])
const returns = computed(() => warehouse.returns || [])
const pendingReturns = computed(() => returns.value.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status)))
const alerts = computed(() => overview.value?.alerts || [])

const todayOutboundCount = computed(() => {
  const today = new Date().toISOString().slice(0, 10)
  return movements.value.filter((row) => Number(row.quantityDelta || 0) < 0 && String(row.createdAt || '').startsWith(today)).length
})

const totalStock = computed(() => activeItems.value.reduce((sum, item) => sum + Number(item.stockQuantity || 0), 0))
const lowStockCount = computed(() => activeItems.value.filter((item) => isLowStock(item)).length)

const tabs: Array<{ key: WarehouseTab; label: string; count?: number }> = [
  { key: 'overview', label: '仓库总览' },
  { key: 'requisitions', label: '门店叫货' },
  { key: 'inventory', label: '商品库存' },
  { key: 'purchase', label: '采购入库' },
  { key: 'alerts', label: '库存预警' },
  { key: 'returns', label: '配送退货单' },
  { key: 'movements', label: '出入库记录' },
  { key: 'receipts', label: '入库记录' },
  { key: 'prints', label: '打印单据' },
]

const tabRoutes: Record<WarehouseTab, string> = {
  overview: '/warehouse',
  requisitions: '/warehouse',
  inventory: '/warehouse/items',
  purchase: '/warehouse/purchase',
  alerts: '/warehouse/alerts',
  returns: '/warehouse/returns',
  movements: '/warehouse/movements',
  receipts: '/warehouse/receipts',
  prints: '/warehouse/receipts',
}

function routeTab() {
  const metaTab = route.meta.warehouseTab
  if (typeof metaTab === 'string') return metaTab
  const queryTab = route.query.tab
  return Array.isArray(queryTab) ? queryTab[0] : queryTab
}

function isLowStock(item: WarehouseItem) {
  return item.stockStatus === '低库存' || item.stockStatus === '缺货' || Number(item.stockQuantity || 0) < Number(item.minStockQuantity || 0)
}

function currentTab() {
  return warehouse.activeTab
}

function tabCount(key: WarehouseTab) {
  if (key === 'requisitions') return pendingRequisitions.value.length
  if (key === 'alerts') return alerts.value.length
  if (key === 'returns') return pendingReturns.value.length
  return undefined
}

async function setTab(tab: WarehouseTab) {
  warehouse.setTab(tab)
  await router.replace({ path: tabRoutes[tab], query: tab === 'requisitions' ? { tab: 'requisitions' } : {} })
}

async function approveRequisition(id: string) {
  await warehouse.approveRequisition(id)
}

async function rejectRequisition(id: string) {
  const note = window.prompt('请输入驳回原因', '库存不足或门店叫货信息需要重新确认')
  if (note === null) return
  await warehouse.rejectRequisition(id, note)
}

async function shipRequisition(id: string) {
  const confirmed = window.confirm('确认按先进先出规则发货并扣减总仓库存吗？')
  if (!confirmed) return
  await warehouse.shipRequisition(id)
}

async function receiveStock(payload: {
  itemId: number
  batchNo: string
  receivedDate: string
  expiryDate?: string
  quantity: number
  unitCost: number
  note?: string
}) {
  await warehouse.receiveStock(payload)
}

async function saveAlert(itemId: number, payload: { minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number }) {
  await warehouse.saveAlertSettings(itemId, payload)
}

async function approveReturn(id: string) {
  await warehouse.reviewReturn(id, true, '仓库管理员审核通过')
}

async function rejectReturn(id: string) {
  const note = window.prompt('请输入驳回原因', '退货数量或商品状态需要重新确认')
  if (note === null) return
  await warehouse.reviewReturn(id, false, note)
}

async function receiveReturn(id: string) {
  const confirmed = window.confirm('确认已收到门店退回商品并入库吗？')
  if (!confirmed) return
  await warehouse.receiveReturn(id, '仓库管理员确认退货入库')
}

async function downloadReceipt(batchId: number, itemName: string, batchNo: string) {
  await warehouse.downloadPdf('receipt', `/api/warehouse/print/receipts/${batchId}`, `入库单-${itemName}-${batchNo}.pdf`)
}

async function downloadDelivery(requisitionId: string) {
  await warehouse.downloadPdf('delivery', `/api/warehouse/print/requisitions/${encodeURIComponent(requisitionId)}/delivery`, `出库单-${requisitionId}.pdf`)
}

async function downloadMovement(movementId: number, itemName: string) {
  await warehouse.downloadPdf('movement', `/api/warehouse/print/movements/${movementId}`, `库存流水单-${itemName}-${movementId}.pdf`)
}

async function downloadReturn(returnId: string, returnNo: string) {
  await warehouse.downloadPdf('return', `/api/warehouse/print/returns/${encodeURIComponent(returnId)}`, `配送退货单-${returnNo}.pdf`)
}

watch(
  () => route.fullPath,
  () => warehouse.setTab(routeTab()),
  { immediate: true },
)

onMounted(() => {
  warehouse.setTab(routeTab())
})
</script>

<template>
  <div class="warehouse-workbench">
    <WarehouseStatCards
      :item-count="activeItems.length"
      :total-stock="totalStock"
      :low-stock-count="lowStockCount"
      :pending-requisition-count="pendingRequisitions.length"
      :pending-return-count="pendingReturns.length"
      :today-outbound-count="todayOutboundCount"
    />

    <div class="warehouse-tabs" role="tablist" aria-label="仓库工作台">
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

    <section v-if="currentTab() === 'overview'" class="section-stack">
      <div class="warehouse-overview-grid">
        <div class="content-card summary-card">
          <h3>今日重点</h3>
          <div class="summary-lines">
            <span>待审核/待发货叫货：<b>{{ pendingRequisitions.length }}</b></span>
            <span>库存提醒：<b>{{ alerts.length }}</b></span>
            <span>待处理退货：<b>{{ pendingReturns.length }}</b></span>
          </div>
        </div>
        <div class="content-card summary-card">
          <h3>库存规则</h3>
          <div class="summary-lines">
            <span>入库批次：<b>{{ stockBatches.length }}</b></span>
            <span>库存流水：<b>{{ movements.length }}</b></span>
            <span>启用商品：<b>{{ activeItems.length }}</b></span>
          </div>
        </div>
      </div>
      <WarehouseRequisitionPanel
        :requisitions="pendingRequisitions"
        :actioning-id="warehouse.actioningId"
        :downloading-id="warehouse.downloadingId"
        @approve="approveRequisition"
        @reject="rejectRequisition"
        @ship="shipRequisition"
        @download-delivery="downloadDelivery"
      />
      <WarehouseAlertPanel :items="activeItems.filter(isLowStock)" :actioning-id="warehouse.actioningId" @save="saveAlert" />
    </section>

    <WarehouseRequisitionPanel
      v-else-if="currentTab() === 'requisitions'"
      :requisitions="requisitions"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      @approve="approveRequisition"
      @reject="rejectRequisition"
      @ship="shipRequisition"
      @download-delivery="downloadDelivery"
    />

    <WarehouseInventoryPanel
      v-else-if="currentTab() === 'inventory'"
      :items="activeItems"
      :batches="stockBatches"
      :movements="movements"
      :downloading-id="warehouse.downloadingId"
      @download-movement="downloadMovement"
    />

    <WarehousePurchaseReceivePanel
      v-else-if="currentTab() === 'purchase'"
      :items="activeItems"
      :batches="stockBatches"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      mode="receive"
      @receive="receiveStock"
      @download-receipt="downloadReceipt"
    />

    <WarehousePurchaseReceivePanel
      v-else-if="currentTab() === 'receipts'"
      :items="activeItems"
      :batches="stockBatches"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      mode="records"
      @receive="receiveStock"
      @download-receipt="downloadReceipt"
    />

    <WarehouseAlertPanel
      v-else-if="currentTab() === 'alerts'"
      :items="activeItems"
      :actioning-id="warehouse.actioningId"
      @save="saveAlert"
    />

    <WarehouseReturnPanel
      v-else-if="currentTab() === 'returns'"
      :returns="returns"
      :actioning-id="warehouse.actioningId"
      :downloading-id="warehouse.downloadingId"
      @approve="approveReturn"
      @reject="rejectReturn"
      @receive="receiveReturn"
      @download="downloadReturn"
    />

    <WarehouseMovementPanel
      v-else-if="currentTab() === 'movements'"
      :movements="movements"
      :downloading-id="warehouse.downloadingId"
      @download-movement="downloadMovement"
      @download-delivery="downloadDelivery"
    />

    <section v-else-if="currentTab() === 'prints'" class="section-stack">
      <WarehousePurchaseReceivePanel
        :items="activeItems"
        :batches="stockBatches"
        :actioning-id="warehouse.actioningId"
        :downloading-id="warehouse.downloadingId"
        mode="records"
        @receive="receiveStock"
        @download-receipt="downloadReceipt"
      />
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
        @approve="approveReturn"
        @reject="rejectReturn"
        @receive="receiveReturn"
        @download="downloadReturn"
      />
    </section>
  </div>
</template>

<style scoped>
.warehouse-workbench {
  display: grid;
  gap: 18px;
}

.warehouse-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #f7f8fa;
}

.warehouse-tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 38px;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--muted);
  font-weight: 900;
}

.warehouse-tab.active {
  border-color: rgba(238, 126, 62, 0.24);
  background: #fff;
  color: var(--primary-dark);
  box-shadow: 0 8px 20px rgba(22, 26, 34, 0.06);
}

.warehouse-tab b {
  min-width: 22px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--primary);
  color: #fff;
  font-size: 12px;
  text-align: center;
}

.warehouse-overview-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.summary-card h3 {
  margin: 0 0 12px;
  font-size: 18px;
}

.summary-lines {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 0;
}

.summary-lines span {
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--muted);
  font-size: 13px;
}

.summary-lines b {
  display: block;
  margin-top: 4px;
  color: var(--ink);
  font-size: 20px;
}

@media (max-width: 900px) {
  .warehouse-overview-grid,
  .summary-lines {
    grid-template-columns: 1fr;
  }
}
</style>
