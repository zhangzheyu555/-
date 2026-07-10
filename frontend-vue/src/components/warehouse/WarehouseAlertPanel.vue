<script setup lang="ts">
import { reactive, watch } from 'vue'
import StatusBadge from '../common/StatusBadge.vue'
import type { WarehouseItem } from '../../api/warehouse'

const props = defineProps<{
  items: WarehouseItem[]
  actioningId: string
  canManage?: boolean
}>()

const emit = defineEmits<{
  save: [itemId: number, payload: { minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number }]
}>()

const forms = reactive<Record<number, { minStockQuantity: string; alertEnabled: boolean; expiryAlertDays: string }>>({})

watch(
  () => props.items.map((item) => `${item.id}:${item.minStockQuantity}:${item.alertEnabled}:${item.expiryAlertDays}`).join('|'),
  () => {
    for (const item of props.items) {
      forms[item.id] = {
        minStockQuantity: item.minStockQuantity == null ? '' : String(item.minStockQuantity),
        alertEnabled: item.alertEnabled !== false,
        expiryAlertDays: item.expiryAlertDays == null ? '' : String(item.expiryAlertDays),
      }
    }
  },
  { immediate: true },
)

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit ? ` ${unit}` : ''}`
}

function save(item: WarehouseItem) {
  const form = forms[item.id]
  emit('save', item.id, {
    minStockQuantity: Number(form?.minStockQuantity || 0),
    alertEnabled: form?.alertEnabled !== false,
    expiryAlertDays: form?.expiryAlertDays ? Number(form.expiryAlertDays) : undefined,
  })
}

function statusTone(status?: string) {
  if (status === '正常') return 'ok'
  if (status === '低库存') return 'warn'
  if (status === '缺货') return 'bad'
  return 'muted'
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>预警设置</h3>
      </div>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>商品</th>
            <th>当前库存</th>
            <th>最低安全库存</th>
            <th>库存不足提醒</th>
            <th>临期提醒</th>
            <th>库存状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>
              <b>{{ item.name }}</b>
              <small>{{ item.code }}</small>
            </td>
            <td>{{ qty(item.stockQuantity, item.unit) }}</td>
            <td>
              <input v-if="props.canManage" v-model="forms[item.id].minStockQuantity" class="inline-input" type="number" min="0" step="0.01" />
              <span v-else>{{ qty(item.minStockQuantity, item.unit) }}</span>
            </td>
            <td>
              <label v-if="props.canManage" class="check-line">
                <input v-model="forms[item.id].alertEnabled" type="checkbox" />
                启用
              </label>
              <span v-else>{{ item.alertEnabled === false ? '未启用' : '已启用' }}</span>
            </td>
            <td>
              <template v-if="props.canManage">
                <input v-model="forms[item.id].expiryAlertDays" class="inline-input small" type="number" min="0" step="1" />
                <span class="unit-text">天</span>
              </template>
              <span v-else>{{ item.expiryAlertDays || 0 }} 天</span>
            </td>
            <td><StatusBadge :label="item.stockStatus || '正常'" :tone="statusTone(item.stockStatus)" /></td>
            <td>
              <button v-if="props.canManage" class="mini-button primary" type="button" :disabled="actioningId === `alert-${item.id}`" @click="save(item)">
                设置预警
              </button>
              <span v-else class="readonly-text">只读</span>
            </td>
          </tr>
          <tr v-if="!items.length">
            <td colspan="7" class="empty-cell">暂无商品可设置预警。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.inline-input {
  width: 130px;
  min-height: 32px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 5px 8px;
}

.inline-input.small {
  width: 90px;
}

.check-line {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink);
  font-weight: 900;
}

.unit-text {
  margin-left: 6px;
  color: var(--muted);
  font-size: 12px;
}

.readonly-text {
  color: var(--muted);
  font-size: 13px;
}
</style>
