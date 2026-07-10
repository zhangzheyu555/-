<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { ElemeStatus, ElemeSummary } from '../../api/operations'

const props = defineProps<{
  status: ElemeStatus | null
  summary: ElemeSummary | null
  month: string
  loading: boolean
}>()

const emit = defineEmits<{
  load: [month: string]
}>()

const selectedMonth = ref(props.month)

watch(
  () => props.month,
  (value) => {
    selectedMonth.value = value
  },
)

const mode = computed(() => props.summary?.mode || props.status?.mode || 'UNCONFIGURED')
const modeLabel = computed(() => {
  const labels: Record<string, string> = {
    LIVE: '已接入',
    UNCONFIGURED: '未配置',
    UNAVAILABLE: '暂不可用',
    ERROR: '接口异常',
  }
  return labels[mode.value] || '暂不可用'
})
const modeTone = computed(() => (mode.value === 'LIVE' ? 'ok' : 'warn'))

function money(value?: number) {
  return Number(value || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' })
}

function refresh() {
  emit('load', selectedMonth.value)
}
</script>

<template>
  <section class="content-card eleme-panel">
    <div class="table-heading">
      <div>
        <h3>饿了么订单</h3>
      </div>
      <div class="eleme-actions">
        <input v-model="selectedMonth" type="month" />
        <button class="ghost-button" type="button" :disabled="loading" @click="refresh">
          <RefreshCw :size="16" />
          查询
        </button>
      </div>
    </div>

    <div class="eleme-mode" :class="{ unavailable: mode !== 'LIVE' }">
      <StatusBadge :label="modeLabel" :tone="modeTone" />
      <span v-if="summary?.note">{{ summary.note }}</span>
    </div>

    <div class="eleme-stat-grid">
      <div class="metric-card">
        <span>有效订单</span>
        <b>{{ summary?.orderCount || 0 }}</b>
      </div>
      <div class="metric-card">
        <span>订单总额</span>
        <b>{{ money(summary?.totalPrice) }}</b>
      </div>
      <div class="metric-card">
        <span>商家实收</span>
        <b>{{ money(summary?.income) }}</b>
      </div>
      <div class="metric-card">
        <span>接入门店</span>
        <b>{{ status?.shopCount || summary?.shops?.length || 0 }}</b>
      </div>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>日期</th>
            <th>门店</th>
            <th>有效订单</th>
            <th>订单总额</th>
            <th>商家实收</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in summary?.shops || []" :key="`${row.shopId}-${row.bizDate}`">
            <td>{{ row.bizDate }}</td>
            <td><b>{{ row.shopName }}</b><small>{{ row.shopId }}</small></td>
            <td>{{ row.validOrderCount }}</td>
            <td>{{ money(row.totalPriceSum) }}</td>
            <td>{{ money(row.incomeSum) }}</td>
          </tr>
          <tr v-if="!summary?.shops?.length">
            <td colspan="5" class="empty-cell">{{ loading ? '正在读取饿了么订单...' : '当前没有饿了么订单明细。' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.eleme-panel {
  display: grid;
  gap: 16px;
}

.eleme-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.eleme-actions input {
  min-height: 40px;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 8px 10px;
}

.eleme-mode {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid rgba(30, 158, 106, 0.22);
  border-radius: 12px;
  background: #eaf8f0;
}

.eleme-mode.unavailable {
  border-color: rgba(215, 131, 34, 0.24);
  background: #fff7ed;
}

.eleme-mode span {
  color: var(--muted);
  font-size: 13px;
}

.eleme-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 900px) {
  .eleme-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .eleme-actions,
  .eleme-mode {
    align-items: stretch;
    flex-direction: column;
  }

  .eleme-stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
