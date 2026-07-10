<script setup lang="ts">
import { computed, ref } from 'vue'
import { Package, PackagePlus, Pencil, Search, ToggleLeft, ToggleRight } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import WarehouseBatchDrawer from './WarehouseBatchDrawer.vue'
import WarehouseCategoryTree from './WarehouseCategoryTree.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseItem, WarehouseItemCategory, WarehouseStockBatch, WarehouseStockMovement } from '../../api/warehouse'

const props = withDefaults(defineProps<{
  items: WarehouseItem[]
  categories: WarehouseItemCategory[]
  selectedCategory: string
  batches: WarehouseStockBatch[]
  movements: WarehouseStockMovement[]
  downloadingId: string
  actioningId?: string
  canManage?: boolean
}>(), {
  actioningId: '',
  canManage: false,
})

const emit = defineEmits<{
  selectCategory: [value: string]
  saveCategory: [payload: { id?: number; name: string; parentId?: number | null; sortOrder?: number; enabled?: boolean }]
  deleteCategory: [id: number]
  createItem: []
  editItem: [item: WarehouseItem]
  setItemEnabled: [item: WarehouseItem, enabled: boolean]
  downloadMovement: [movementId: number, itemName: string]
}>()

const searchText = ref('')
const lowStockOnly = ref(false)
const expiringOnly = ref(false)
const expandedItemId = ref<number | null>(null)

const selectedItem = computed(() => props.items.find((item) => item.id === expandedItemId.value) || null)
const selectedBatches = computed(() => selectedItem.value ? batchesFor(selectedItem.value.id) : [])

const visibleItems = computed(() => props.items.filter((item) => {
  const keyword = searchText.value.trim().toLowerCase()
  if (keyword && ![item.name, item.code, item.categoryName, item.spec, item.warehouseLocation]
    .filter(Boolean)
    .some((value) => String(value).toLowerCase().includes(keyword))) {
    return false
  }
  if (!matchesCategory(item)) return false
  if (lowStockOnly.value && !isLowStock(item)) return false
  if (expiringOnly.value && item.alertLevel !== 'EXPIRING') return false
  return true
}))

function matchesCategory(item: WarehouseItem) {
  if (props.selectedCategory === 'all') return true
  const selectedId = Number(props.selectedCategory.replace(/^id:/, ''))
  if (!Number.isFinite(selectedId)) return false
  return descendantIds(selectedId).has(item.categoryId || -1)
}

function descendantIds(id: number) {
  const ids = new Set<number>([id])
  const visit = (categories: WarehouseItemCategory[]) => {
    for (const category of categories) {
      if (ids.has(category.parentId || -1)) {
        ids.add(category.id)
      }
      visit(category.children || [])
    }
  }
  visit(props.categories)
  return ids
}

function isLowStock(item: WarehouseItem) {
  return ['LOW', 'OUT'].includes(item.alertLevel) || ['低库存', '缺货'].includes(item.stockStatus)
}

function batchesFor(itemId: number) {
  return props.batches.filter((batch) => batch.itemId === itemId)
}

function latestMovement(itemId: number) {
  return props.movements.find((row) => row.itemId === itemId)
}

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit ? ` ${unit}` : ''}`
}

function categoryName(item: WarehouseItem) {
  return item.categoryName || item.category || '未分类'
}

function statusTone(status?: string) {
  if (status === '正常') return 'ok'
  if (status === '低库存' || status === '临期') return 'warn'
  if (status === '缺货') return 'bad'
  return 'muted'
}
</script>

<template>
  <div class="inventory-layout">
    <WarehouseCategoryTree
      :categories="categories"
      :items="items"
      :selected="selectedCategory"
      :can-manage="canManage"
      :actioning-id="actioningId"
      @select="emit('selectCategory', $event)"
      @save="emit('saveCategory', $event)"
      @remove="emit('deleteCategory', $event)"
    />

    <section class="content-card inventory-main">
      <div class="table-heading inventory-heading">
        <div>
          <h3>库存物料</h3>
        </div>
        <button v-if="canManage" class="primary-button compact-button" type="button" @click="emit('createItem')">
          <PackagePlus :size="17" />
          新增物料
        </button>
      </div>

      <div class="inventory-filters">
        <label class="search-field">
          <Search :size="17" />
          <input v-model="searchText" placeholder="搜索物料名称、编码、规格或库位" />
        </label>
        <label class="filter-check">
          <input v-model="lowStockOnly" type="checkbox" />
          低库存
        </label>
        <label class="filter-check">
          <input v-model="expiringOnly" type="checkbox" />
          临期
        </label>
        <button class="mini-button" type="button" @click="searchText = ''; lowStockOnly = false; expiringOnly = false">清除筛选</button>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>分类</th>
              <th>总仓库存</th>
              <th>安全库存</th>
              <th>临近到期</th>
              <th>库存状态</th>
              <th>库存金额</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in visibleItems" :key="item.id">
              <tr :class="{ disabled: !item.active }">
                <td>
                  <div class="item-cell">
                    <span class="item-thumb">
                      <img v-if="item.imageUrl" :src="item.imageUrl" alt="" @error="($event.target as HTMLImageElement).style.display = 'none'" />
                      <Package :size="18" />
                    </span>
                    <span>
                      <b>{{ item.name }}</b>
                      <small>{{ item.code }}<template v-if="item.spec"> · {{ item.spec }}</template></small>
                    </span>
                  </div>
                </td>
                <td>{{ categoryName(item) }}</td>
                <td>{{ qty(item.stockQuantity, item.stockUnit || item.unit) }}</td>
                <td>{{ qty(item.minStockQuantity, item.stockUnit || item.unit) }}</td>
                <td>{{ item.nearestExpiryDate || '-' }}</td>
                <td><StatusBadge :label="item.active ? (item.stockStatus || '正常') : '已停用'" :tone="item.active ? statusTone(item.stockStatus) : 'muted'" /></td>
                <td>{{ Number(item.stockValue || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</td>
                <td>
                  <div class="row-actions">
                    <button class="mini-button" type="button" @click="expandedItemId = expandedItemId === item.id ? null : item.id">
                      {{ expandedItemId === item.id ? '收起批次' : '查看批次' }}
                    </button>
                    <button v-if="canManage" class="mini-button" type="button" title="编辑物料" @click="emit('editItem', item)">
                      <Pencil :size="14" />
                      编辑
                    </button>
                    <button
                      v-if="canManage"
                      class="mini-button"
                      type="button"
                      :disabled="actioningId === `item-enabled:${item.id}`"
                      @click="emit('setItemEnabled', item, !item.active)"
                    >
                      <ToggleRight v-if="item.active" :size="15" />
                      <ToggleLeft v-else :size="15" />
                      {{ item.active ? '停用' : '启用' }}
                    </button>
                    <WarehousePrintButtons
                      v-if="latestMovement(item.id)"
                      label="下载流水单"
                      :disabled="downloadingId.includes(`/movements/${latestMovement(item.id)?.id}`)"
                      @download="emit('downloadMovement', Number(latestMovement(item.id)?.id), item.name)"
                    />
                  </div>
                </td>
              </tr>
              <tr v-if="expandedItemId === item.id && selectedItem">
                <td colspan="8">
                  <WarehouseBatchDrawer :item="selectedItem" :batches="selectedBatches" @close="expandedItemId = null" />
                </td>
              </tr>
            </template>
            <tr v-if="!visibleItems.length">
              <td colspan="8" class="empty-cell">暂无符合条件的物料。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.inventory-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.inventory-main {
  min-width: 0;
}

.inventory-heading {
  align-items: center;
}

.compact-button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 7px;
  padding: 7px 12px;
}

.inventory-filters,
.row-actions,
.item-cell,
.search-field,
.filter-check {
  display: flex;
  align-items: center;
}

.inventory-filters {
  gap: 12px;
  margin: 0 0 14px;
}

.search-field {
  width: min(380px, 100%);
  gap: 8px;
  min-height: 36px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
  background: #fff;
  color: var(--muted);
}

.search-field input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  color: var(--ink);
}

.filter-check {
  gap: 6px;
  color: #475569;
  font-size: 13px;
  white-space: nowrap;
}

.item-cell {
  gap: 9px;
  min-width: 170px;
}

.item-cell > span:last-child {
  display: grid;
  gap: 2px;
}

.item-cell small {
  color: var(--muted);
}

.item-thumb {
  position: relative;
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  overflow: hidden;
  border: 1px solid #e0e7eb;
  border-radius: 4px;
  background: #f8fafb;
  color: #8aa1a7;
}

.item-thumb img {
  position: absolute;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: cover;
  background: #fff;
}

.row-actions {
  gap: 7px;
  flex-wrap: wrap;
}

tr.disabled td {
  color: #98a3af;
  background: #fbfcfd;
}
</style>
