<script setup lang="ts">
import { CheckCircle2, FileQuestion, Send, Table2 } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { ProfitEntry } from '../../api/finance'

defineProps<{
  risks: ProfitEntry[]
  actioningId?: string
}>()

defineEmits<{
  openReport: [entry: ProfitEntry]
  requestInfo: [entry: ProfitEntry]
  checked: [entry: ProfitEntry]
  escalate: [entry: ProfitEntry]
}>()

function money(value?: number) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

function percent(value?: number) {
  return `${(Number(value || 0) * 100).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%`
}

function riskLabel(entry: ProfitEntry) {
  if (Number(entry.net || 0) < 0) return '亏损风险'
  if (Number(entry.margin || 0) < 0.05) return '利润率偏低'
  return entry.risk || '利润异常'
}

function riskTone(entry: ProfitEntry) {
  if (Number(entry.net || 0) < 0) return 'bad'
  return 'warn'
}

function actionId(entry: ProfitEntry) {
  return `profit-risk-${entry.storeId}-${entry.month}`
}

function financeExpense(entry: ProfitEntry) {
  return Number(entry.expenseSum || 0)
}
</script>

<template>
  <section class="finance-panel">
    <div class="finance-panel-head">
      <div>
        <h3>利润异常</h3>
      </div>
    </div>

    <div v-if="!risks.length" class="empty-state compact">当前没有利润异常提醒。</div>

    <div v-else class="profit-grid">
      <article v-for="entry in risks" :key="actionId(entry)" class="profit-card">
        <div class="profit-card-head">
          <div>
            <b>{{ entry.storeName || entry.storeCode || entry.storeId }}</b>
            <span>{{ entry.month }}</span>
          </div>
          <StatusBadge :label="riskLabel(entry)" :tone="riskTone(entry)" />
        </div>
        <div class="profit-metrics">
          <span><b>{{ money(entry.sales ?? entry.income) }}</b>营业额</span>
          <span><b>{{ money(entry.costSum) }}</b>成本</span>
          <span><b>{{ money(entry.labor) }}</b>工资</span>
          <span><b>{{ money(financeExpense(entry)) }}</b>报销</span>
          <span><b>{{ money(entry.net) }}</b>净利润</span>
          <span><b>{{ percent(entry.margin) }}</b>净利率</span>
        </div>
        <p>{{ entry.note || '暂无备注' }}</p>
        <div class="finance-actions">
          <button class="mini-button" type="button" @click="$emit('openReport', entry)">
            查看利润表
            <Table2 :size="14" />
          </button>
          <button
            class="mini-button"
            type="button"
            :disabled="actioningId === actionId(entry)"
            @click="$emit('requestInfo', entry)"
          >
            要求门店说明
            <FileQuestion :size="14" />
          </button>
          <button
            class="mini-button"
            type="button"
            :disabled="actioningId === actionId(entry)"
            @click="$emit('escalate', entry)"
          >
            上报老板
            <Send :size="14" />
          </button>
          <button
            class="mini-button primary"
            type="button"
            :disabled="actioningId === actionId(entry)"
            @click="$emit('checked', entry)"
          >
            标记已核对
            <CheckCircle2 :size="14" />
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.finance-panel {
  display: grid;
  gap: 12px;
}

.finance-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.profit-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

.profit-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.profit-card {
  display: grid;
  gap: 12px;
  padding: 15px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.profit-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.profit-card-head b {
  display: block;
  font-size: 16px;
}

.profit-card-head span {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}

.profit-metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
}

.profit-metrics span {
  padding: 9px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fafbfc;
  color: var(--muted);
  font-size: 12px;
}

.profit-metrics b {
  display: block;
  color: var(--ink);
  font-size: 14px;
}

.finance-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 980px) {
  .profit-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .profit-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
