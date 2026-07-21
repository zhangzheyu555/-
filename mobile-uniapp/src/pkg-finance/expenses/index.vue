<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  createMobileExpense,
  getMobileExpenses,
  reviewMobileExpense,
  submitMobileExpense,
  updateMobileExpense,
  uploadMobileExpenseSupplement,
} from '@/api/business'
import ProtectedAttachmentList, { type ProtectedAttachment } from '@/components/ProtectedAttachmentList.vue'
import { canPerformMobileAction } from '@/permissions'
import { chooseMedia } from '@/platform'
import { useContextStore, useSessionStore } from '@/stores'
import type { ExpenseClaim } from '@/types/business'

const session = useSessionStore()
const context = useContextStore()
const rows = ref<ExpenseClaim[]>([])
const loading = ref(false)
const acting = ref('')
const message = ref('')
const editingId = ref('')
const editingStoreId = ref('')
const amount = ref('')
const category = ref('')
const reason = ref('')
const month = ref(new Date().toISOString().slice(0, 7))
const canCreate = computed(() => canPerformMobileAction(session.user, 'expense.create'))
const canReview = computed(() => canPerformMobileAction(session.user, 'expense.review'))

onShow(async () => {
  if (!context.stores.length && session.user) await context.load(session.user)
  await refresh()
})
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (loading.value) return
  loading.value = true
  message.value = ''
  try { rows.value = await getMobileExpenses({ storeId: context.currentStoreId || undefined }) }
  catch (cause) { message.value = errorText(cause) }
  finally { loading.value = false }
}

async function save(submitAfterSave: boolean) {
  if (!canCreate.value || acting.value) return
  const storeId = editingStoreId.value || context.currentStoreId || session.user?.boundStoreId || ''
  const value = Number(amount.value)
  if (!storeId || !/^\d{4}-\d{2}$/.test(month.value) || !Number.isFinite(value) || value <= 0 || !reason.value.trim()) {
    message.value = '请填写正确月份、金额和报销事由。'
    return
  }
  acting.value = editingId.value || 'create'
  try {
    const payload = { storeId, month: month.value, amount: value, category: category.value.trim(), reason: reason.value.trim() }
    const row = editingId.value ? await updateMobileExpense(editingId.value, payload) : await createMobileExpense(payload)
    if (submitAfterSave) await submitMobileExpense(row.id)
    message.value = submitAfterSave ? '报销已提交审核。' : '报销草稿已保存。'
    resetForm()
    await refresh()
  } catch (cause) { message.value = errorText(cause) }
  finally { acting.value = '' }
}

function edit(row: ExpenseClaim) {
  if (!canCreate.value || !isDraft(row.status)) return
  editingId.value = row.id
  editingStoreId.value = row.storeId
  month.value = row.month
  amount.value = String(row.amount)
  category.value = row.category || ''
  reason.value = row.reason || ''
  message.value = '正在编辑报销草稿。'
}

async function submitDraft(row: ExpenseClaim) {
  if (!canCreate.value || !isDraft(row.status) || acting.value) return
  acting.value = row.id
  try { await submitMobileExpense(row.id); message.value = '报销已提交审核。'; await refresh() }
  catch (cause) { message.value = errorText(cause) }
  finally { acting.value = '' }
}

async function act(row: ExpenseClaim, action: 'approve' | 'reject' | 'request-info') {
  if (!canReview.value || acting.value) return
  const note = await prompt(action === 'approve' ? '填写审核备注（可留空）' : '请填写处理说明')
  if (note === null) return
  acting.value = row.id
  try { await reviewMobileExpense(row.id, action, note); await refresh() }
  catch (cause) { message.value = errorText(cause) }
  finally { acting.value = '' }
}

async function supplement(row: ExpenseClaim) {
  if (!canPerformMobileAction(session.user, 'expense.supplement') || acting.value) return
  const note = await prompt('填写补充说明')
  if (!note) return
  const files = await chooseMedia({ count: 3, source: 'both', kinds: ['image'] })
  if (!files.length) return
  acting.value = row.id
  try { for (const file of files) await uploadMobileExpenseSupplement(row.id, file.path, note); await refresh() }
  catch (cause) { message.value = errorText(cause) }
  finally { acting.value = '' }
}

function resetForm() {
  editingId.value = ''
  editingStoreId.value = ''
  amount.value = ''
  category.value = ''
  reason.value = ''
  month.value = new Date().toISOString().slice(0, 7)
}
function isDraft(status?: string) { return ['DRAFT', '草稿'].includes(String(status || '')) }
function isPending(status?: string) { return ['SUBMITTED', 'PENDING_REVIEW', '待审核'].includes(String(status || '')) }
function needsInfo(status?: string) { return ['NEED_MORE_INFO', 'INFO_REQUIRED', '待补充资料'].includes(String(status || '')) }
function statusText(status?: string) { return ({ DRAFT: '草稿', SUBMITTED: '待审核', PENDING_REVIEW: '待审核', NEED_MORE_INFO: '待补充资料', INFO_REQUIRED: '待补充资料', APPROVED: '已通过', REJECTED: '已驳回' } as Record<string, string>)[String(status || '')] || status || '未知状态' }
function prompt(title: string) { return new Promise<string | null>(resolve => uni.showModal({ title, editable: true, placeholderText: '请输入说明', success: result => resolve(result.confirm ? String(result.content || '').trim() : null), fail: () => resolve(null) })) }
function errorText(cause: unknown) { return cause instanceof Error ? cause.message : '操作失败，请稍后重试。' }
function attachments(row: ExpenseClaim): ProtectedAttachment[] { return (row.supplements || []).flatMap(supplementRow => (supplementRow.attachments || []).map(attachment => ({ id: attachment.id, fileName: attachment.fileName, contentType: attachment.contentType, path: attachment.previewUrl || `/api/expenses/${encodeURIComponent(row.id)}/attachments/${attachment.id}/content` }))) }
</script>

<template>
  <view class="page">
    <view class="head"><view><text class="eyebrow">经营财务</text><text class="title">报销</text></view><button @click="refresh">刷新</button></view>
    <view v-if="message" class="notice">{{ message }}</view>
    <view v-if="canCreate" class="panel">
      <view class="row"><text class="section">{{ editingId ? '编辑报销草稿' : '新建报销' }}</text><button v-if="editingId" class="text-button" @click="resetForm">取消编辑</button></view>
      <input v-model="month" placeholder="月份 YYYY-MM"/>
      <input v-model="amount" type="digit" placeholder="金额"/>
      <input v-model="category" placeholder="类别"/>
      <textarea v-model="reason" placeholder="报销事由"/>
      <view class="form-actions"><button :loading="Boolean(acting)" :disabled="Boolean(acting)" @click="save(false)">保存草稿</button><button class="primary" :loading="Boolean(acting)" :disabled="Boolean(acting)" @click="save(true)">保存并提交</button></view>
    </view>
    <text class="section list-heading">报销记录</text>
    <view v-if="!rows.length && !loading" class="empty">暂无报销记录</view>
    <view v-for="row in rows" :key="row.id" class="card">
      <view class="row"><text class="name">{{ row.storeName || row.storeId }}</text><text>{{ statusText(row.status) }}</text></view>
      <text class="money">¥{{ Number(row.amount).toFixed(2) }}</text>
      <text class="copy">{{ row.month }} · {{ row.category || '未分类' }}</text>
      <text class="reason">{{ row.reason || '暂无事由' }}</text>
      <text v-if="row.latestSupplementNote" class="supplement-note">补充说明：{{ row.latestSupplementNote }}</text>
      <ProtectedAttachmentList :items="attachments(row)" @error="message=$event"/>
      <view class="actions">
        <button v-if="canCreate && isDraft(row.status)" @click="edit(row)">编辑草稿</button>
        <button v-if="canCreate && isDraft(row.status)" :loading="acting===row.id" :disabled="Boolean(acting)" @click="submitDraft(row)">提交审核</button>
        <button v-if="canReview && isPending(row.status)" @click="act(row,'approve')">通过</button>
        <button v-if="canReview && isPending(row.status)" @click="act(row,'reject')">驳回</button>
        <button v-if="canReview && isPending(row.status)" @click="act(row,'request-info')">要求补件</button>
        <button v-if="canCreate && needsInfo(row.status)" @click="supplement(row)">补充材料</button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx;background:#f2f6f5;color:#202124}.head,.row,.actions,.form-actions{display:flex;align-items:center;justify-content:space-between;gap:12rpx}.eyebrow,.title,.section,.name,.money,.copy,.reason,.supplement-note{display:block}.title{font-size:40rpx;font-weight:750}.eyebrow,.copy{color:#71807d;font-size:24rpx}.section{font-size:30rpx;font-weight:700}.list-heading{margin:28rpx 0 14rpx}.panel,.card,.empty,.notice{margin-top:16rpx;padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.panel input,.panel textarea{box-sizing:border-box;width:100%;margin-top:14rpx;padding:18rpx;background:#f7faf9;border-radius:12rpx}.panel textarea{height:130rpx}.form-actions{margin-top:16rpx}.form-actions button{flex:1;margin:0}.primary{background:#27655f;color:#fff}.text-button{margin:0;background:transparent;color:#1f5752;font-size:24rpx}.money{margin-top:12rpx;font-size:36rpx;font-weight:750}.copy,.reason,.supplement-note{margin-top:8rpx;line-height:1.5}.reason{font-size:26rpx}.supplement-note{padding:14rpx;background:#fff9e8;color:#755d2d;font-size:24rpx}.actions{margin-top:16rpx;justify-content:flex-start;flex-wrap:wrap}.actions button{margin:0;padding:0 22rpx;background:#e6f3f1;color:#1f5752;font-size:24rpx}.empty{text-align:center;color:#71807d}
</style>
