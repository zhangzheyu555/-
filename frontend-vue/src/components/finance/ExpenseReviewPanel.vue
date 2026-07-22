<script setup lang="ts">
import { CheckCircle2, FileQuestion, MessageSquareText, Paperclip, Pencil, Send, XCircle } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import ExpenseClaimAttachments from './ExpenseClaimAttachments.vue'
import ExpenseSupplementAttachments from './ExpenseSupplementAttachments.vue'
import type { ExpenseAttachment, ExpenseClaim, ExpenseSupplementAttachment } from '../../api/finance'

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
  supplement: [expense: ExpenseClaim]
  edit: [expense: ExpenseClaim]
  submit: [expense: ExpenseClaim]
  attachmentDeleted: [expense: ExpenseClaim]
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

function canSupplement(status: string) {
  return ['待补资料', 'REQUEST_INFO'].includes(status)
}

function supplementAttachments(expense: ExpenseClaim): ExpenseSupplementAttachment[] {
  return (expense.supplements || []).flatMap((supplement) => supplement.attachments || [])
}

function claimAttachments(expense: ExpenseClaim): ExpenseAttachment[] {
  return expense.attachments || []
}

function supplementNote(expense: ExpenseClaim) {
  if (expense.latestSupplementNote) return expense.latestSupplementNote
  return (expense.supplements || []).find((supplement) => supplement.note?.trim())?.note || ''
}

function supplementCount(expense: ExpenseClaim) {
  return Number(expense.supplementAttachmentCount ?? supplementAttachments(expense).length)
}

function hasSupplement(expense: ExpenseClaim) {
  return Boolean(supplementNote(expense) || supplementCount(expense))
}

function reviewNote(expense: ExpenseClaim) {
  return String(expense.reviewNote || '').trim()
}

function shouldShowReviewNote(expense: ExpenseClaim) {
  return Boolean(reviewNote(expense)) && [
    '待补资料',
    'REQUEST_INFO',
    '已驳回',
    'REJECTED',
    '已完成',
    '已通过',
    'APPROVED',
  ].includes(expense.status)
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
            <span>日期：{{ expense.expenseDate || '-' }}</span>
            <span>品牌：{{ expense.brandName || '-' }}</span>
          </div>
          <div v-if="shouldShowReviewNote(expense)" class="review-note">
            <MessageSquareText :size="15" />
            <div>
              <b>财务说明</b>
              <p>{{ reviewNote(expense) }}</p>
            </div>
          </div>
          <div v-if="claimAttachments(expense).length" class="claim-evidence">
            <div class="supplement-count">
              <Paperclip :size="14" />报销凭证 {{ claimAttachments(expense).length }} 张
            </div>
            <ExpenseClaimAttachments
              :attachments="claimAttachments(expense)"
              :expense-id="expense.id"
              :removable="props.editable && canEdit(expense.status)"
              @deleted="$emit('attachmentDeleted', expense)"
            />
          </div>
          <div v-if="hasSupplement(expense)" class="supplement-evidence">
            <div v-if="supplementNote(expense)" class="supplement-note">
              <MessageSquareText :size="15" />
              <div>
                <b>补充说明</b>
                <p>{{ supplementNote(expense) }}</p>
              </div>
            </div>
            <div v-if="supplementCount(expense)" class="supplement-count">
              <Paperclip :size="14" />{{ supplementCount(expense) }} 个附件
            </div>
            <ExpenseSupplementAttachments
              v-if="supplementAttachments(expense).length"
              :expense-id="expense.id"
              :attachments="supplementAttachments(expense)"
            />
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
              要求补资料
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
            <button
              v-if="canSupplement(expense.status)"
              class="mini-button"
              type="button"
              :disabled="props.actioningId === expense.id"
              @click="$emit('supplement', expense)"
            >
              补充资料
              <Paperclip :size="14" />
            </button>
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

.claim-evidence,
.supplement-evidence {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.supplement-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  color: var(--primary-dark);
}

.review-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin-top: 10px;
  padding: 9px 10px;
  border-left: 3px solid var(--primary);
  border-radius: 6px;
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.review-note > div {
  display: grid;
  gap: 2px;
}

.review-note b,
.review-note p {
  font-size: 12px;
}

.review-note p {
  margin: 0;
  color: var(--ink);
  line-height: 1.55;
  white-space: pre-wrap;
}

.supplement-note > div {
  display: grid;
  gap: 2px;
}

.supplement-note b,
.supplement-note p,
.supplement-count {
  font-size: 12px;
}

.supplement-note p {
  margin: 0;
  color: var(--ink);
  line-height: 1.55;
  white-space: pre-wrap;
}

.supplement-count {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
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
