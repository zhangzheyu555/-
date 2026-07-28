<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import SecondaryNavigation from '../components/common/SecondaryNavigation.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import CategoryFilter from '../components/warehouse/CategoryFilter.vue'
import StoreDeliveryRecordsPanel from '../components/warehouse/StoreDeliveryRecordsPanel.vue'
import StoreInventoryTable from '../components/warehouse/StoreInventoryTable.vue'
import StoreReturnCreateDialog from '../components/warehouse/StoreReturnCreateDialog.vue'
import WarehouseStoreRequisitionForm from '../components/warehouse/WarehouseStoreRequisitionForm.vue'
import { reportAppError } from '../errors/appErrorDialog'
import { useAuthStore } from '../stores/auth'
import { useWarehouseStore } from '../stores/warehouse'
import type { WarehouseItem, WarehouseRequisition, WarehouseReturnCreatePayload } from '../api/warehouse'
import { PERMISSIONS } from '../permissions/permissions'
import { useBusinessScope } from '../composables/useBusinessScope'
import { useForegroundReload } from '../composables/useForegroundReload'
import WarehouseWorkbenchPage from './WarehouseWorkbenchPage.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const warehouse = useWarehouseStore()
const businessScope = useBusinessScope()
const localError = ref('')
const pendingReceiptId = ref<string | null>(null)
const receiptConfirmBusy = ref(false)
const returnTarget = ref<WarehouseRequisition | null>(null)

const overview = computed(() => warehouse.overview)
const canManage = computed(() => (
  auth.hasPermission(PERMISSIONS.WAREHOUSE_CONFIGURE)
  || auth.hasPermission(PERMISSIONS.WAREHOUSE_CENTRAL_MANAGE)
))
const showWorkbench = computed(() => (
  !businessScope.isStoreManager.value
  && (auth.hasPermission(PERMISSIONS.WAREHOUSE_READ) || auth.hasPermission(PERMISSIONS.WAREHOUSE_CENTRAL_READ))
))
const showStoreInventory = computed(() => (
  (businessScope.isStoreManager.value || !showWorkbench.value)
  && auth.hasPermission(PERMISSIONS.WAREHOUSE_STORE_READ)
))
const canCreateRequisition = computed(() => auth.hasPermission(PERMISSIONS.WAREHOUSE_REQUISITION_CREATE))
const canReceiveRequisition = computed(() => auth.hasPermission(PERMISSIONS.WAREHOUSE_REQUISITION_RECEIVE))
const storeNavigationItems = computed(() => {
  const items = [
    { key: 'inventory', label: '本店库存', to: '/store/inventory' },
  ]
  if (canCreateRequisition.value) {
    items.push({ key: 'requisition', label: '门店叫货', to: '/store/inventory/requisition' })
  }
  if (canCreateRequisition.value || canReceiveRequisition.value) {
    items.push({ key: 'records', label: '本店记录', to: '/store/inventory/records' })
  }
  return items
})
const activeStoreNavigation = computed(() => String(route.meta.storeWarehouseTab || 'inventory'))
const activeItems = computed(() => (overview.value?.items || []).filter((item) => item.active !== false))
const supplyWarehouse = computed(() => warehouse.warehouses[0] || overview.value?.warehouse || null)
const supplyWarehouseName = computed(() => supplyWarehouse.value?.name || '供货仓待配置')
const requisitions = computed(() => overview.value?.requisitions || [])
const filteredItems = computed(() => activeItems.value.filter(matchesSelectedCategory))
const selectedCategoryLabel = computed(() => categoryLabel(warehouse.selectedCategory))

function accessibleWarehouseRows() {
  return warehouse.warehouses.filter((row) => row.enabled !== false && row.canRead !== false)
}

function warehouseForCurrentRoute() {
  const rows = accessibleWarehouseRows()
  const routeWarehouseId = Array.isArray(route.params.warehouseId)
    ? route.params.warehouseId[0]
    : route.params.warehouseId
  if (routeWarehouseId) {
    return rows.find((row) => String(row.id) === String(routeWarehouseId)) || null
  }
  const warehouseCode = String(route.meta.warehouseCode || '')
  if (warehouseCode) {
    return rows.find((row) => row.code === warehouseCode) || null
  }
  if (route.name === 'warehouse-transfers' || route.name === 'warehouse-requests') {
    const warehouseId = Array.isArray(route.query.warehouseId)
      ? route.query.warehouseId[0]
      : route.query.warehouseId
    if (warehouseId) return rows.find((row) => String(row.id) === String(warehouseId)) || null
  }
  return rows.find((row) => row.type === 'CENTRAL') || rows[0] || null
}
watch(
  () => filteredItems.value.map((item) => item.id).join(','),
  () => {
    if (!filteredItems.value.length) {
      warehouse.setCategory('all')
    }
  },
)

function matchesSelectedCategory(item: WarehouseItem) {
  const selected = warehouse.selectedCategory
  if (selected === 'all') return true
  if (selected.startsWith('name:')) return itemCategory(item) === selected.slice(5)
  const id = selected.replace(/^id:/, '')
  return String(item.categoryId || '') === id
}

function itemCategory(item: WarehouseItem) {
  return item.categoryName || item.category || '未分类'
}

function flattenCategories() {
  const rows = [...warehouse.categories]
  for (let index = 0; index < rows.length; index += 1) {
    rows.push(...(rows[index].children || []))
  }
  return rows
}

function categoryLabel(value: string) {
  if (value === 'all') return '全部类别'
  if (value.startsWith('name:')) return value.slice(5)
  const id = Number(value.replace(/^id:/, ''))
  return flattenCategories().find((category) => category.id === id)?.name || '全部类别'
}

async function loadWarehouseData() {
  localError.value = ''
  if (businessScope.configurationError.value) {
    localError.value = businessScope.configurationError.value
    return false
  }
  try {
    if (businessScope.isStoreManager.value) {
      await warehouse.loadWarehouses()
      await Promise.all([
        warehouse.loadOverview(warehouse.selectedWarehouseId),
        warehouse.loadCategories(),
        warehouse.loadReturns(),
      ])
    } else {
      await warehouse.loadWarehouses()
      const target = warehouseForCurrentRoute()
      if (!target) {
        localError.value = '当前账号暂无可访问的仓库，请联系管理员授权。'
        return false
      }
      await Promise.all([
        warehouse.selectWarehouse(target.id),
        warehouse.loadCategories(),
        warehouse.loadReturns(),
      ])
      if (route.name === 'warehouse-overview') {
        await router.replace({
          name: 'warehouse-detail',
          params: { warehouseId: String(target.id) },
        })
      }
    }
    markFresh()
    return true
  } catch {
    localError.value = warehouse.error || '仓库数据加载失败'
    return false
  }
}

const { markFresh } = useForegroundReload(async () => {
  if (!await loadWarehouseData()) throw new Error('仓库数据暂时不可用')
}, {
  canReload: () => !warehouse.loading && !receiptConfirmBusy.value && !pendingReceiptId.value,
})

async function submitRequisition(payload: {
  lines: Array<{ itemId: number; requestedQuantity: number; note?: string }>
  note?: string
  clientRequestId: string
}) {
  if (!canCreateRequisition.value) return
  localError.value = ''
  try {
    await warehouse.submitRequisition(payload.lines, payload.note, payload.clientRequestId)
    markFresh()
  } catch {
    localError.value = warehouse.error || '叫货提交失败'
  }
}

async function receiveRequisition(requisitionId: string) {
  if (!canReceiveRequisition.value || receiptConfirmBusy.value) return
  localError.value = ''
  pendingReceiptId.value = requisitionId
}

function cancelReceiveRequisition() {
  if (receiptConfirmBusy.value) return
  pendingReceiptId.value = null
}

async function confirmReceiveRequisition() {
  const requisitionId = pendingReceiptId.value
  if (!requisitionId || receiptConfirmBusy.value) return
  receiptConfirmBusy.value = true
  try {
    await warehouse.receiveRequisition(requisitionId, '店长确认收货')
    markFresh()
  } catch {
    const message = warehouse.error || '确认收货失败'
    warehouse.error = ''
    reportAppError(message, {
      title: '确认收货未完成',
      actionLabel: '重新确认',
      action: async () => {
        pendingReceiptId.value = requisitionId
        await confirmReceiveRequisition()
      },
      sourceKey: `store-receive:${requisitionId}:${message}`,
    })
  } finally {
    receiptConfirmBusy.value = false
    pendingReceiptId.value = null
  }
}

function openReturnDialog(requisition: WarehouseRequisition) {
  returnTarget.value = requisition
}

function closeReturnDialog() {
  if (warehouse.actioningId.startsWith('return-create:')) return
  returnTarget.value = null
}

async function submitReturn(payload: WarehouseReturnCreatePayload) {
  if (warehouse.actioningId.startsWith('return-create:')) return
  try {
    await warehouse.submitReturn(payload)
    returnTarget.value = null
    markFresh()
  } catch {
    const message = warehouse.error || '配送退货单提交失败'
    warehouse.error = ''
    reportAppError(message, {
      title: '配送退货单提交失败',
      sourceKey: `store-return-create:${payload.sourceRequisitionId}:${message}`,
    })
  }
}

async function downloadReturnPdf(returnId: string, returnNo: string) {
  try {
    await warehouse.downloadPdf(
      'return',
      `/api/warehouse/print/returns/${encodeURIComponent(returnId)}`,
      `配送退货单-${returnNo}.pdf`,
    )
  } catch {
    const message = warehouse.error || '配送退货单下载失败'
    warehouse.error = ''
    reportAppError(message, {
      title: '配送退货单下载失败',
      actionLabel: '重新下载',
      action: () => downloadReturnPdf(returnId, returnNo),
      sourceKey: `store-return-download:${returnId}:${message}`,
    })
  }
}

function addItemToRequisition(item: WarehouseItem) {
  if (!canCreateRequisition.value) return
  void router.push({ path: '/store/inventory/requisition', query: { itemId: String(item.id) } })
}

onMounted(() => {
  void loadWarehouseData()
})

watch(
  () => route.name,
  (name, previousName) => {
    if (name === 'warehouse-overview' && previousName !== 'warehouse-overview') void loadWarehouseData()
  },
)
</script>

<template>
  <section class="page-panel warehouse-page">
    <PageHeader title="仓库中心" />

    <div v-if="warehouse.error || localError" class="error-box warehouse-load-error">
      <span>{{ localError || warehouse.error }}</span>
      <button class="ghost-button" type="button" :disabled="warehouse.loading" @click="loadWarehouseData">重试</button>
    </div>
    <div v-if="warehouse.actionMessage" class="success-box">{{ warehouse.actionMessage }}</div>
    <div v-if="warehouse.loading && !overview" class="empty-state">正在读取仓库数据...</div>

    <WarehouseWorkbenchPage v-if="!businessScope.configurationError.value && overview && showWorkbench" :can-manage="canManage" />

    <template v-else-if="!businessScope.configurationError.value && overview && showStoreInventory">
      <div class="store-supply-warehouse" aria-label="供货仓">
        <span>供货仓</span>
        <strong>{{ supplyWarehouseName }}</strong>
        <small>叫货单将由系统自动发送到该仓库，门店不能自行更改。</small>
      </div>
      <SecondaryNavigation
        :items="storeNavigationItems"
        :model-value="activeStoreNavigation"
        label="本店仓库功能"
      />

      <div v-if="activeStoreNavigation === 'inventory'" class="store-warehouse-grid">
        <CategoryFilter
          :categories="warehouse.categories"
          :items="activeItems"
          :selected="warehouse.selectedCategory"
          @select="warehouse.setCategory"
        />
        <StoreInventoryTable
          :items="filteredItems"
          :all-count="activeItems.length"
          :category-label="selectedCategoryLabel"
          :can-requisition="canCreateRequisition"
          :supply-warehouse-name="supplyWarehouseName"
          @pick="addItemToRequisition"
        />
      </div>

      <WarehouseStoreRequisitionForm
        v-else-if="activeStoreNavigation === 'requisition' && canCreateRequisition"
        :items="activeItems"
        :initial-item-id="Number(route.query.itemId || 0)"
        :submitting="warehouse.submitting"
        :success-message="warehouse.actionMessage"
        @submit="submitRequisition"
      />

      <div v-else-if="activeStoreNavigation === 'records'" class="section-stack warehouse-actions">
        <StoreDeliveryRecordsPanel
          v-if="canCreateRequisition || canReceiveRequisition"
          :requisitions="requisitions"
          :returns="warehouse.returns"
          :receiving-id="warehouse.receivingId"
          :actioning-id="warehouse.actioningId"
          :downloading-id="warehouse.downloadingId"
          :can-receive="canReceiveRequisition"
          :can-create-return="canCreateRequisition"
          @receive="receiveRequisition"
          @create-return="openReturnDialog"
          @download-return="downloadReturnPdf"
        />
      </div>
    </template>

    <div v-else-if="!businessScope.configurationError.value && showWorkbench && !overview" class="empty-state">{{ localError || '当前账号暂无可访问的仓库，请联系管理员授权。' }}</div>
    <div v-else-if="!businessScope.configurationError.value && overview" class="empty-state">当前角色无权访问仓库中心。</div>

    <ActionConfirmDialog
      :open="Boolean(pendingReceiptId)"
      title="确认收货"
      message="确认已收到该叫货单商品吗？"
      confirm-label="确认收货"
      :busy="receiptConfirmBusy"
      @cancel="cancelReceiveRequisition"
      @confirm="confirmReceiveRequisition"
    />

    <StoreReturnCreateDialog
      :open="Boolean(returnTarget)"
      :requisition="returnTarget"
      :returns="warehouse.returns"
      :items="activeItems"
      :warehouse-name="supplyWarehouseName"
      :submitting="warehouse.actioningId === `return-create:${returnTarget?.id || ''}`"
      @close="closeReturnDialog"
      @submit="submitReturn"
    />
  </section>
</template>

<style scoped>
.warehouse-page,
.section-stack {
  display: grid;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-head h2 {
  margin: 0;
}

.store-warehouse-grid {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.warehouse-actions {
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
}

.warehouse-actions > :first-child {
  grid-column: auto;
}

.warehouse-page > .error-box,
.warehouse-page > .success-box {
  margin: 0;
}

.warehouse-load-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.store-supply-warehouse {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
  padding: 9px 12px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: var(--ds-surface);
}

.store-supply-warehouse span,
.store-supply-warehouse small {
  color: var(--ds-muted);
  font-size: 13px;
}

.store-supply-warehouse strong {
  color: var(--ds-primary-hover);
}

.store-supply-warehouse small {
  margin-left: auto;
}

@media (max-width: 768px) {
  .store-supply-warehouse {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .store-supply-warehouse small {
    margin-left: 0;
  }
  .store-warehouse-grid,
  .warehouse-actions {
    grid-template-columns: 1fr;
  }
}
</style>
