<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { PackageCheck, RefreshCw, Warehouse } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import StatusBadge from '../components/common/StatusBadge.vue'
import CategoryFilter from '../components/warehouse/CategoryFilter.vue'
import MyRequisitionList from '../components/warehouse/MyRequisitionList.vue'
import PendingReceiptList from '../components/warehouse/PendingReceiptList.vue'
import RequisitionForm from '../components/warehouse/RequisitionForm.vue'
import StoreInventoryTable from '../components/warehouse/StoreInventoryTable.vue'
import { useAuthStore } from '../stores/auth'
import { useWarehouseStore } from '../stores/warehouse'
import type { WarehouseItem, WarehouseRequisition } from '../api/warehouse'
import WarehouseWorkbenchPage from './WarehouseWorkbenchPage.vue'

const auth = useAuthStore()
const route = useRoute()
const warehouse = useWarehouseStore()
const selectedOrderItemId = ref<number | null>(null)
const localError = ref('')

const overview = computed(() => warehouse.overview)
const isStoreManager = computed(() => auth.role === 'STORE_MANAGER')
const isWarehouseManager = computed(() => auth.role === 'WAREHOUSE' || auth.role === 'ADMIN')
const isWarehouseReadonly = computed(() => auth.role === 'BOSS' || auth.role === 'OWNER' || auth.role === 'FINANCE')
const pageTitle = computed(() => {
  const routeTitle = route.meta.title
  if (typeof routeTitle === 'string' && routeTitle) return routeTitle
  if (isWarehouseManager.value) return '仓库中心'
  if (isStoreManager.value) return '仓库中心'
  return '仓库总览'
})

const activeItems = computed(() => (overview.value?.items || []).filter((item) => item.active !== false))
const requisitions = computed(() => overview.value?.requisitions || [])
const pendingRequisitions = computed(() => requisitions.value.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status)))
const shippedRequisitions = computed(() => requisitions.value.filter((row) => row.status === 'SHIPPED'))

const filteredItems = computed(() => activeItems.value.filter(matchesSelectedCategory))
const selectedCategoryLabel = computed(() => categoryLabel(warehouse.selectedCategory))

watch(
  () => filteredItems.value.map((item) => item.id).join(','),
  () => {
    if (!selectedOrderItemId.value || !filteredItems.value.some((item) => item.id === selectedOrderItemId.value)) {
      selectedOrderItemId.value = filteredItems.value[0]?.id || null
    }
  },
  { immediate: true },
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

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function statusTone(status: string) {
  if (['正常', '库存充足', 'OK', 'RECEIVED'].includes(status)) return 'ok'
  if (['低库存', '需要补货', 'LOW', 'SUBMITTED', 'APPROVED', 'SHIPPED'].includes(status)) return 'warn'
  if (['缺货', 'OUT', 'REJECTED'].includes(status)) return 'bad'
  return 'muted'
}

function requisitionItems(row: WarehouseRequisition) {
  return row.lines.map((line) => `${line.itemName} × ${qty(line.approvedQuantity || line.requestedQuantity, line.unit)}`).join('，')
}

async function refresh() {
  localError.value = ''
  await warehouse.loadAll()
}

function pickOrderItem(item: WarehouseItem) {
  selectedOrderItemId.value = item.id
  localError.value = ''
  const target = document.getElementById('store-requisition-form')
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function submitRequisition(payload: { itemId: number; quantity: number; note?: string }) {
  localError.value = ''
  try {
    await warehouse.submitRequisition(payload.itemId, payload.quantity, payload.note)
  } catch (error) {
    localError.value = error instanceof Error ? error.message : '叫货提交失败'
  }
}

async function receiveRequisition(requisitionId: string) {
  localError.value = ''
  const confirmed = window.confirm('确认已收到该叫货单商品吗？')
  if (!confirmed) return
  try {
    await warehouse.receiveRequisition(requisitionId, '店长确认收货')
  } catch (error) {
    localError.value = error instanceof Error ? error.message : '确认收货失败'
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel warehouse-page">
    <div class="page-head">
      <div>
        <h2>{{ pageTitle }}</h2>
      </div>
      <button class="ghost-button" type="button" :disabled="warehouse.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="warehouse.error || localError" class="error-box">{{ localError || warehouse.error }}</div>
    <div v-if="warehouse.actionMessage" class="success-box">{{ warehouse.actionMessage }}</div>
    <div v-if="warehouse.loading && !overview" class="empty-state">正在读取仓库数据...</div>

    <template v-if="overview">
      <div v-if="!isWarehouseManager" class="metric-grid">
        <div class="metric-card">
          <span>{{ isStoreManager ? '本店商品' : '商品总数' }}</span>
          <b>{{ activeItems.length }}</b>
        </div>
        <div class="metric-card">
          <span>{{ isStoreManager ? '我的叫货单' : '待处理叫货' }}</span>
          <b>{{ isStoreManager ? requisitions.length : pendingRequisitions.length }}</b>
        </div>
        <div class="metric-card">
          <span>{{ isStoreManager ? '待仓库处理' : '库存预警' }}</span>
          <b>{{ isStoreManager ? pendingRequisitions.length : overview.alerts.length }}</b>
        </div>
        <div class="metric-card">
          <span>待确认收货</span>
          <b>{{ shippedRequisitions.length }}</b>
        </div>
      </div>

      <WarehouseWorkbenchPage v-if="isWarehouseManager" />

      <template v-else-if="isStoreManager">
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
            @pick="pickOrderItem"
          />
        </div>

        <div id="store-requisition-form" class="section-stack warehouse-actions">
          <RequisitionForm
            v-model:selected-item-id="selectedOrderItemId"
            :items="filteredItems"
            :category-label="selectedCategoryLabel"
            :submitting="warehouse.submitting"
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

      <div v-else-if="isWarehouseReadonly" class="section-stack">
        <div class="empty-state compact">
          <b>当前为仓库只读总览</b>
        </div>
        <div class="section-title">
          <Warehouse :size="20" />
          <h3>商品库存</h3>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>商品</th>
                <th>分类</th>
                <th>仓库库存</th>
                <th>库存状态</th>
                <th>提醒</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in activeItems" :key="item.id">
                <td>
                  <b>{{ item.name }}</b>
                  <small>{{ item.code }}</small>
                </td>
                <td>{{ itemCategory(item) }}</td>
                <td>{{ qty(item.stockQuantity, item.unit) }}</td>
                <td><StatusBadge :label="item.stockStatus || '正常'" :tone="statusTone(item.stockStatus)" /></td>
                <td>{{ item.alertText || '无' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="section-title">
          <PackageCheck :size="20" />
          <h3>门店叫货待处理</h3>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>单号</th>
                <th>门店</th>
                <th>商品</th>
                <th>状态</th>
                <th>提交时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in pendingRequisitions" :key="row.id">
                <td><b>{{ row.id }}</b></td>
                <td>{{ row.storeName || row.storeId }}</td>
                <td>{{ requisitionItems(row) }}</td>
                <td><StatusBadge :label="row.statusLabel || row.status" :tone="statusTone(row.status)" /></td>
                <td>{{ row.submittedAt || '-' }}</td>
              </tr>
              <tr v-if="!pendingRequisitions.length">
                <td colspan="5" class="empty-cell">当前没有门店叫货待处理。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-else class="empty-state">
        当前角色仓库中心暂未开放。
      </div>
    </template>
  </section>
</template>
