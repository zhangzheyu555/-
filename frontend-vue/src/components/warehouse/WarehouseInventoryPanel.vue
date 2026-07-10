<script setup lang="ts">
import { computed, ref } from 'vue'
import StatusBadge from '../common/StatusBadge.vue'
import WarehouseBatchDrawer from './WarehouseBatchDrawer.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'
import type { WarehouseItem, WarehouseStockBatch, WarehouseStockMovement } from '../../api/warehouse'

const props = defineProps<{
  items: WarehouseItem[]
  batches: WarehouseStockBatch[]
  movements: WarehouseStockMovement[]
  downloadingId: string
}>()

const expandedItemId = ref<number | null>(null)
const selectedItem = computed(() => props.items.find((item) => item.id === expandedItemId.value) || null)
const selectedBatches = computed(() => selectedItem.value ? batchesFor(selectedItem.value.id) : [])

const emit = defineEmits<{
  downloadMovement: [movementId: number, itemName: string]
}>()

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function categoryName(item: WarehouseItem) {
  return item.categoryName || item.category || '未分类'
}

function statusTone(status?: string) {
  if (status === '正常' || status === '库存充足') return 'ok'
  if (status === '低库存' || status === '需要补货') return 'warn'
  if (status === '缺货') return 'bad'
  return 'muted'
}

function batchesFor(itemId: number) {
  return props.batches.filter((batch) => batch.itemId === itemId)
}

function latestMovement(itemId: number) {
  return props.movements.find((row) => row.itemId === itemId)
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>商品库存管理</h3>
        <span>总仓库存由入库、发货和退货入库自动驱动；批次按先进先出配送。</span>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>商品</th>
            <th>分类</th>
            <th>当前库存</th>
            <th>最低安全库存</th>
            <th>库存金额</th>
            <th>库存状态</th>
            <th>最近入库</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="item in props.items" :key="item.id">
            <tr>
              <td>
                <b>{{ item.name }}</b>
                <small>{{ item.code }}</small>
              </td>
              <td>{{ categoryName(item) }}</td>
              <td>{{ qty(item.stockQuantity, item.unit) }}</td>
              <td>{{ qty(item.minStockQuantity, item.unit) }}</td>
              <td>{{ Number(item.stockValue || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' }) }}</td>
              <td><StatusBadge :label="item.stockStatus || '正常'" :tone="statusTone(item.stockStatus)" /></td>
              <td>{{ item.nearestExpiryDate || '-' }}</td>
              <td>
                <div class="row-actions">
                  <button class="mini-button" type="button" @click="expandedItemId = expandedItemId === item.id ? null : item.id">
                    {{ expandedItemId === item.id ? '收起批次' : '查看批次' }}
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
          <tr v-if="!props.items.length">
            <td colspan="8" class="empty-cell">当前没有启用商品。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
