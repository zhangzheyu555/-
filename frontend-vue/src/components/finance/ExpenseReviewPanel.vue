<script setup lang="ts">
import { CheckCircle2, FileQuestion, Pencil, Send, XCircle } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { ExpenseClaim } from '../../api/finance'

const props = withDefaults(defineProps<{
  expenses: ExpenseClaim[]
  actioningId?: string
  reviewable?: boolean
  editable?: boolean
}>(), {
  reviewable: true,
  editable: false,
})

defineEmits<{
  approve: [expense: ExpenseClaim]
  reject: [expense: ExpenseClaim]
  requestInfo: [expense: ExpenseClaim]
  escalate: [expense: ExpenseClaim]
  edit: [expense: ExpenseClaim]
  submit: [expense: ExpenseClaim]
}>()

function money(value?: number) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function tone(status: string) {
  if (status === '已通过' || status === '已完成' || status === 'APPROVED') return 'ok'
  if (status === '已驳回' || status === 'REJECTED') return 'bad'
  if (status === '草稿') return 'info'
  return 'warn'
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    PENDING: '待财务审核',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    REQUEST_INFO: '待补资料',
    '待补资料': '待补资料',
  }
  return map[status] || status || '待财务审核'
}

function canReview(status: string) {
  return ['待审核', 'PENDING'].includes(status)
}

function canEdit(status: string) {
  return ['草稿', '已驳回', 'REJECTED', '待补资料', 'REQUEST_INFO'].includes(status)
}
</script>

<template>
  <section class="finance-panel">
    <div class="finance-panel-head">
      <div>
        <h3>报销审核</h3>
      </div>
    </div>

    <div v-if="!props.expenses.length" class="empty-state compact">当前没有报销审核记录。</div>

    <div v-else class="finance-list">
      <article v-for="expense in props.expenses" :key="expense.id" class="finance-row-card">
        <div>
          <div class="finance-row-title">
            <b>{{ expense.storeName || expense.storeCode || expense.storeId }}</b>
            <span>{{ money(expense.amount) }}</span>
          </div>
          <p>{{ expense.category || '报销事项' }} · {{ expense.reason || '暂无说明' }}</p>
          <div class="finance-row-meta">
            <span>月份：{{ expense.month || '-' }}</span>
            <span>品牌：{{ expense.brandName || '-' }}</span>
          </div>
        </div>
        <div class="finance-row-side">
          <StatusBadge :label="statusLabel(expense.status)" :tone="tone(expense.status)" />
          <div v-if="props.reviewable && canReview(expense.status)" class="finance-actions">
            <button
              class="mini-button"
              type="button"
              :disabled="props.actioningId === expense.id"
              @click="$emit('requestInfo', expense)"
            >
              补资料
              <FileQuestion :size="14" />
            </button>
            <button
              class="mini-button"
              type="button"
              :disabled="props.actioningId === expense.id"
              @click="$emit('reject', expense)"
            >
              驳回
              <XCircle :size="14" />
            </button>
            <button
              class="mini-button"
              type="button"
              :disabled="props.actioningId === expense.id"
              @click="$emit('escalate', expense)"
            >
              上报老板
              <Send :size="14" />
            </button>
            <button
              class="mini-button primary"
              type="button"
              :disabled="props.actioningId === expense.id"
              @click="$emit('approve', expense)"
            >
              通过
              <CheckCircle2 :size="14" />
            </button>
          </div>
          <div v-else-if="props.editable && canEdit(expense.status)" class="finance-actions">
            <button class="mini-button" type="button" :disabled="props.actioningId === expense.id" @click="$emit('edit', expense)">
              编辑
              <Pencil :size="14" />
            </button>
            <button class="mini-button primary" type="button" :disabled="props.actioningId === expense.id" @click="$emit('submit', expense)">
              提交审核
              <Send :size="14" />
            </button>
          </div>
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

.finance-panel-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.finance-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.finance-row-card p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.finance-list {
  display: grid;
  gap: 10px;
}

.finance-row-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}

.finance-row-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 5px;
}

.finance-row-title b {
  font-size: 16px;
}

.finance-row-title span {
  color: var(--primary-dark);
  font-size: 17px;
  font-weight: 900;
}

.finance-row-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
  color: var(--muted);
  font-size: 12px;
}

.finance-row-side {
  display: grid;
  justify-items: end;
  align-content: space-between;
  gap: 12px;
}

.finance-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 860px) {
  .finance-row-card {
    grid-template-columns: 1fr;
  }

  .finance-row-side {
    justify-items: start;
  }
}
</style>
