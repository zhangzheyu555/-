<script setup lang="ts">
import StatusBadge from '../common/StatusBadge.vue'
import type { WarehouseItem } from '../../api/warehouse'

defineProps<{
  items: WarehouseItem[]
  allCount: number
  categoryLabel: string
}>()

const emit = defineEmits<{
  pick: [item: WarehouseItem]
}>()

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function categoryName(item: WarehouseItem) {
  return item.categoryName || item.category || '未分类'
}

function storeStatus(item: WarehouseItem) {
  if (Number(item.storeStockQuantity || 0) <= 0) return '暂无库存'
  if (item.stockStatus === '低库存') return '需要补货'
  return '库存充足'
}

function statusTone(label: string) {
  if (label === '库存充足') return 'ok'
  if (label === '需要补货') return 'warn'
  return 'bad'
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>本店库存</h3>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>商品</th>
            <th>分类</th>
            <th>本店库存</th>
            <th>公司仓库可配送</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>
              <b>{{ item.name }}</b>
              <small>{{ item.code }}</small>
            </td>
            <td>{{ categoryName(item) }}</td>
            <td>{{ qty(item.storeStockQuantity, item.unit) }}</td>
            <td>{{ qty(item.warehouseAvailableQuantity, item.unit) }}</td>
            <td><StatusBadge :label="storeStatus(item)" :tone="statusTone(storeStatus(item))" /></td>
            <td>
              <button class="mini-button" type="button" @click="emit('pick', item)">加入叫货单</button>
            </td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="6" class="empty-cell">当前分类下没有可叫货商品。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
