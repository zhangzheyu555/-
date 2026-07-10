<script setup lang="ts">
import { computed, ref } from 'vue'
import { Package, PackagePlus, Pencil, Search, ToggleLeft, ToggleRight } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import WarehouseCategoryTree from './WarehouseCategoryTree.vue'
import type { WarehouseItem, WarehouseItemCategory } from '../../api/warehouse'

const props = withDefaults(defineProps<{
  items: WarehouseItem[]
  categories: WarehouseItemCategory[]
  selectedCategory: string
  canManage?: boolean
  actioningId?: string
}>(), {
  canManage: false,
  actioningId: '',
})

const emit = defineEmits<{
  selectCategory: [value: string]
  saveCategory: [payload: { id?: number; name: string; parentId?: number | null; sortOrder?: number; enabled?: boolean }]
  deleteCategory: [id: number]
  createItem: []
  editItem: [item: WarehouseItem]
  setItemEnabled: [item: WarehouseItem, enabled: boolean]
}>()

const searchText = ref('')
const visibleItems = computed(() => props.items.filter((item) => {
  const keyword = searchText.value.trim().toLowerCase()
  if (keyword && ![item.name, item.code, item.categoryName, item.spec, item.warehouseLocation, item.itemAttributes]
    .filter(Boolean)
    .some((value) => String(value).toLowerCase().includes(keyword))) {
    return false
  }
  if (props.selectedCategory === 'all') return true
  const categoryId = Number(props.selectedCategory.replace(/^id:/, ''))
  return item.categoryId === categoryId
}))

function categoryName(item: WarehouseItem) {
  return item.categoryName || item.category || '未分类'
}

function units(item: WarehouseItem) {
  return [item.purchaseUnit, item.stockUnit || item.unit, item.ingredientUnit].filter(Boolean).join(' / ') || '-'
}
</script>

<template>
  <div class="catalog-layout">
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

    <section class="content-card catalog-main">
      <div class="table-heading catalog-heading">
        <div>
          <h3>物料档案</h3>
        </div>
        <button v-if="canManage" class="primary-button compact-button" type="button" @click="emit('createItem')">
          <PackagePlus :size="17" />
          新增物料
        </button>
      </div>

      <label class="search-field">
        <Search :size="17" />
        <input v-model="searchText" placeholder="搜索名称、编码、规格、库位或属性" />
      </label>

      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>分类</th>
              <th>单位</th>
              <th>规格 / 库位</th>
              <th>保质期 / 预警</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in visibleItems" :key="item.id" :class="{ disabled: !item.active }">
              <td>
                <div class="item-cell">
                  <span class="item-thumb">
                    <img v-if="item.imageUrl" :src="item.imageUrl" alt="" @error="($event.target as HTMLImageElement).style.display = 'none'" />
                    <Package :size="18" />
                  </span>
                  <span>
                    <b>{{ item.name }}</b>
                    <small>{{ item.code }}</small>
                  </span>
                </div>
              </td>
              <td>{{ categoryName(item) }}</td>
              <td>{{ units(item) }}</td>
              <td>{{ item.spec || '-' }}<small v-if="item.warehouseLocation">{{ item.warehouseLocation }}</small></td>
              <td>{{ item.shelfLifeDays || '-' }} 天<small>提前 {{ item.expiryAlertDays || 0 }} 天提醒</small></td>
              <td><StatusBadge :label="item.active ? '启用' : '停用'" :tone="item.active ? 'ok' : 'muted'" /></td>
              <td>
                <div v-if="canManage" class="row-actions">
                  <button class="mini-button" type="button" @click="emit('editItem', item)">
                    <Pencil :size="14" />
                    编辑
                  </button>
                  <button class="mini-button" type="button" :disabled="actioningId === `item-enabled:${item.id}`" @click="emit('setItemEnabled', item, !item.active)">
                    <ToggleRight v-if="item.active" :size="15" />
                    <ToggleLeft v-else :size="15" />
                    {{ item.active ? '停用' : '启用' }}
                  </button>
                </div>
                <span v-else class="readonly-text">只读</span>
              </td>
            </tr>
            <tr v-if="!visibleItems.length">
              <td colspan="7" class="empty-cell">暂无符合条件的物料档案。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.catalog-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.catalog-main {
  min-width: 0;
}

.catalog-heading,
.item-cell,
.row-actions,
.search-field {
  display: flex;
  align-items: center;
}

.compact-button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 7px;
  padding: 7px 12px;
}

.search-field {
  width: min(400px, 100%);
  gap: 8px;
  min-height: 36px;
  margin: 0 0 14px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--muted);
}

.search-field input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  color: var(--ink);
}

.item-cell {
  gap: 9px;
  min-width: 160px;
}

.item-cell > span:last-child,
td small {
  display: grid;
  gap: 2px;
}

td small {
  margin-top: 3px;
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

.readonly-text {
  color: var(--muted);
  font-size: 13px;
}

tr.disabled td {
  color: #98a3af;
  background: #fbfcfd;
}
</style>
