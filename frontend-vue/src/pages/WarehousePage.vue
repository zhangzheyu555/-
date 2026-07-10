<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import CategoryFilter from '../components/warehouse/CategoryFilter.vue'
import MyRequisitionList from '../components/warehouse/MyRequisitionList.vue'
import PendingReceiptList from '../components/warehouse/PendingReceiptList.vue'
import StoreInventoryTable from '../components/warehouse/StoreInventoryTable.vue'
import WarehouseStoreRequisitionForm from '../components/warehouse/WarehouseStoreRequisitionForm.vue'
import { useAuthStore } from '../stores/auth'
import { useWarehouseStore } from '../stores/warehouse'
import type { WarehouseItem } from '../api/warehouse'
import WarehouseWorkbenchPage from './WarehouseWorkbenchPage.vue'

const auth = useAuthStore()
const route = useRoute()
const warehouse = useWarehouseStore()
const localError = ref('')
const storeRequisitionForm = ref<{ addItem: (item: WarehouseItem) => void } | null>(null)

const overview = computed(() => warehouse.overview)
const isStoreManager = computed(() => auth.role === 'STORE_MANAGER')
const canManage = computed(() => auth.role === 'WAREHOUSE' || auth.role === 'ADMIN')
const isReadonlyViewer = computed(() => auth.role === 'BOSS' || auth.role === 'OWNER')
const showWorkbench = computed(() => canManage.value || isReadonlyViewer.value)
const activeItems = computed(() => (overview.value?.items || []).filter((item) => item.active !== false))
const requisitions = computed(() => overview.value?.requisitions || [])
const shippedRequisitions = computed(() => requisitions.value.filter((row) => row.status === 'SHIPPED'))
const filteredItems = computed(() => activeItems.value.filter(matchesSelectedCategory))
const selectedCategoryLabel = computed(() => categoryLabel(warehouse.selectedCategory))
const pageTitle = computed(() => {
  const routeTitle = route.meta.title
  return typeof routeTitle === 'string' && routeTitle ? routeTitle : '仓库中心'
})

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

async function refresh() {
  localError.value = ''
  try {
    await warehouse.loadAll()
  } catch {
    localError.value = warehouse.error || '仓库数据加载失败'
  }
}

async function submitRequisition(payload: {
  lines: Array<{ itemId: number; requestedQuantity: number; note?: string }>
  note?: string
  clientRequestId: string
}) {
  localError.value = ''
  try {
    await warehouse.submitRequisition(payload.lines, payload.note, payload.clientRequestId)
  } catch {
    localError.value = warehouse.error || '叫货提交失败'
  }
}

async function receiveRequisition(requisitionId: string) {
  localError.value = ''
  if (!window.confirm('确认已收到该叫货单商品吗？')) return
  try {
    await warehouse.receiveRequisition(requisitionId, '店长确认收货')
  } catch {
    localError.value = warehouse.error || '确认收货失败'
  }
}

function addItemToRequisition(item: WarehouseItem) {
  storeRequisitionForm.value?.addItem(item)
  document.getElementById('store-requisition-form')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel warehouse-page">
    <div class="page-head">
      <h2>{{ pageTitle }}</h2>
      <button class="ghost-button" type="button" :disabled="warehouse.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="warehouse.error || localError" class="error-box">{{ localError || warehouse.error }}</div>
    <div v-if="warehouse.actionMessage" class="success-box">{{ warehouse.actionMessage }}</div>
    <div v-if="warehouse.loading && !overview" class="empty-state">正在读取仓库数据...</div>

    <WarehouseWorkbenchPage v-if="overview && showWorkbench" :can-manage="canManage" />

    <template v-else-if="overview && isStoreManager">
      <div class="store-warehouse-grid">
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
          @pick="addItemToRequisition"
        />
      </div>

      <div class="section-stack warehouse-actions">
        <WarehouseStoreRequisitionForm
          ref="storeRequisitionForm"
          :items="activeItems"
          :submitting="warehouse.submitting"
          :success-message="warehouse.actionMessage"
          @submit="submitRequisition"
        />
        <PendingReceiptList
          :requisitions="shippedRequisitions"
          :receiving-id="warehouse.receivingId"
          @receive="receiveRequisition"
        />
        <MyRequisitionList :requisitions="requisitions" />
      </div>
    </template>

    <div v-else-if="overview" class="empty-state">当前角色无权访问仓库中心。</div>
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
}

.warehouse-actions > :first-child {
  grid-column: 1 / -1;
}
</style>
