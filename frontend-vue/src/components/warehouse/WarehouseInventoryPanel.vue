<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Package, PackagePlus, Pencil, ToggleLeft, ToggleRight, Trash2 } from 'lucide-vue-next'
import SearchInput from '../common/SearchInput.vue'
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
  warehouseName?: string
}>(), {
  actioningId: '',
  canManage: false,
  warehouseName: '当前仓库',
})

const emit = defineEmits<{
  selectCategory: [value: string]
  saveCategory: [payload: { id?: number; name: string; parentId?: number | null; sortOrder?: number; enabled?: boolean }]
  deleteCategory: [id: number]
  createItem: []
  editItem: [item: WarehouseItem]
  setItemEnabled: [item: WarehouseItem, enabled: boolean]
  deleteItem: [item: WarehouseItem]
  downloadMovement: [movementId: number, itemName: string, movementType: string]
}>()

const searchText = ref('')
const lowStockOnly = ref(false)
const expiringOnly = ref(false)
const expandedItemId = ref<number | null>(null)
const focusedItemId = ref<number | null>(null)
const inventoryMainRef = ref<HTMLElement | null>(null)

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
  const matchesLowStock = isLowStock(item)
  const matchesExpiring = isExpiring(item)
  const hasRiskFilter = lowStockOnly.value || expiringOnly.value
  const matchesRiskFilter = (lowStockOnly.value && matchesLowStock)
    || (expiringOnly.value && matchesExpiring)
  if (hasRiskFilter && !matchesRiskFilter && focusedItemId.value !== item.id) return false
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

function isExpiring(item: WarehouseItem) {
  return item.alertLevel === 'EXPIRING' || item.stockStatus === '临期'
}

function clearFilters() {
  searchText.value = ''
  lowStockOnly.value = false
  expiringOnly.value = false
  focusedItemId.value = null
}

async function scrollToInventory() {
  await nextTick()
  inventoryMainRef.value?.scrollIntoView({ block: 'start' })
  inventoryMainRef.value?.focus({ preventScroll: true })
}

async function showRiskInventory() {
  searchText.value = ''
  lowStockOnly.value = true
  expiringOnly.value = true
  focusedItemId.value = null
  await scrollToInventory()
}

async function focusInventoryItem(itemId: number, alertType = '') {
  searchText.value = ''
  lowStockOnly.value = alertType !== 'EXPIRING'
  expiringOnly.value = alertType === 'EXPIRING'
  focusedItemId.value = itemId
  await nextTick()
  const targetRow = inventoryMainRef.value?.querySelector<HTMLElement>(`[data-inventory-item-id="${itemId}"]`)
  if (!targetRow) {
    await scrollToInventory()
    return
  }
  targetRow.scrollIntoView({ block: 'center' })
  targetRow.focus({ preventScroll: true })
}

defineExpose({
  showRiskInventory,
  focusInventoryItem,
})

function batchesFor(itemId: number) {
  return props.batches.filter((batch) => batch.itemId === itemId)
}

function latestMovement(itemId: number) {
  return props.movements.find((row) => row.itemId === itemId)
}

function documentLabel(row: WarehouseStockMovement | undefined) {
  return row?.movementType === 'IN' ? '下载入库单' : '下载流水单'
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

    <section ref="inventoryMainRef" class="content-card inventory-main" tabindex="-1" aria-label="库存物料">
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
        <SearchInput
          v-model="searchText"
          class="inventory-search"
          placeholder="搜索物料名称、编码、规格或库位"
          aria-label="搜索库存物料"
          @update:model-value="focusedItemId = null"
        />
        <label class="filter-check">
          <input v-model="lowStockOnly" type="checkbox" @change="focusedItemId = null" />
          低库存
        </label>
        <label class="filter-check">
          <input v-model="expiringOnly" type="checkbox" @change="focusedItemId = null" />
          临期
        </label>
        <button class="mini-button" type="button" @click="clearFilters">清除筛选</button>
      </div>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>分类</th>
              <th>{{ warehouseName }}库存</th>
              <th>安全库存</th>
              <th>临近到期</th>
              <th>库存状态</th>
              <th>库存金额</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in visibleItems" :key="item.id">
              <tr
                :class="{ disabled: !item.active, 'inventory-target-row': focusedItemId === item.id }"
                :data-inventory-item-id="item.id"
                :data-inventory-target="focusedItemId === item.id ? 'true' : undefined"
                :tabindex="focusedItemId === item.id ? -1 : undefined"
              >
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
                      :label="documentLabel(latestMovement(item.id))"
                      :disabled="downloadingId.includes(`/movements/${latestMovement(item.id)?.id}`)"
                      @download="emit(
                        'downloadMovement',
                        Number(latestMovement(item.id)?.id),
                        item.name,
                        String(latestMovement(item.id)?.movementType || ''),
                      )"
                    />
                    <button
                      v-if="canManage"
                      class="mini-button danger-action"
                      type="button"
                      :aria-label="`删除物料 ${item.name}`"
                      :disabled="actioningId === `item-delete:${item.id}`"
                      @click="emit('deleteItem', item)"
                    >
                      <Trash2 :size="14" />
                      删除
                    </button>
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
  scroll-margin-top: 18px;
}

.inventory-main:focus {
  outline: none;
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
.filter-check {
  display: flex;
  align-items: center;
}

.inventory-filters {
  gap: 12px;
  margin: 0 0 14px;
}

.inventory-filters > .inventory-search {
  width: min(380px, 100%);
  flex: none;
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

.danger-action {
  border-color: #efc2c7;
  color: #b83243;
}

.danger-action:hover:not(:disabled) {
  border-color: #c33f4d;
  background: #fff5f5;
  color: #9b2c3a;
}

tr.disabled td {
  color: #98a3af;
  background: #fbfcfd;
}

tr.inventory-target-row td {
  background: var(--ds-primary-soft, #edf9f8);
}

tr.inventory-target-row td:first-child {
  box-shadow: inset 4px 0 0 var(--ds-primary, #2c8582);
}

tr.inventory-target-row:focus {
  outline: 2px solid var(--ds-primary, #2c8582);
  outline-offset: -2px;
}

@media (max-width: 768px) {
  .inventory-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .inventory-heading {
    align-items: stretch;
  }

  .inventory-heading .compact-button {
    width: 100%;
    min-height: 44px;
    justify-content: center;
  }

  .inventory-filters {
    align-items: stretch;
    flex-wrap: wrap;
  }

  .inventory-filters > .inventory-search {
    width: 100%;
    flex: 1 1 100%;
  }

  .filter-check {
    min-height: 44px;
    flex: 1 1 120px;
    padding: 0 10px;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: #fff;
  }

  .inventory-filters > .mini-button {
    width: 100%;
    min-height: 44px;
  }
}
</style>
