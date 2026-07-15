<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { CheckCircle2, FileUp, PackageMinus, RefreshCw, Send, ShieldCheck } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import UiButton from '../components/ui/UiButton.vue'
import {
  approveDailyLossRecord,
  createDailyLossRecord,
  getDailyLossItems,
  getDailyLossRecords,
  submitDailyLossRecord,
  uploadDailyLossAttachments,
  type DailyLossItem,
  type DailyLossRecord,
} from '../api/dailyLoss'
import { getStores, type StoreInfo } from '../api/operations'
import { useBusinessScope } from '../composables/useBusinessScope'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const scope = useBusinessScope()
const stores = ref<StoreInfo[]>([])
const items = ref<DailyLossItem[]>([])
const records = ref<DailyLossRecord[]>([])
const selectedStoreId = ref('')
const filterStatus = ref('')
const loading = ref(true)
const submitting = ref(false)
const refreshing = ref(false)
const pageError = ref('')
const actionMessage = ref('')
const uploadProgress = ref(0)
const selectedFiles = ref<File[]>([])
const approvalNotes = ref<Record<string, string>>({})
const approvingId = ref('')
const form = ref({
  lossDate: localDate(),
  itemId: '',
  quantity: '',
  reason: '',
})

const canSubmit = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_CREATE))
const canReview = computed(() => auth.hasPermission(PERMISSIONS.DAILY_LOSS_REVIEW))
const warehouseScope = computed(() => auth.dataScope('WAREHOUSE') || auth.dataScope('STORE'))
const accessibleStores = computed(() => {
  if (scope.isStoreManager.value) {
    return stores.value.filter((store) => store.id === scope.boundStoreId.value)
  }
  if (warehouseScope.value?.mode === 'ALL') return stores.value
  const storeIds = warehouseScope.value?.storeIds || []
  if (storeIds.length) return stores.value.filter((store) => storeIds.includes(store.id))
  return stores.value
})
const effectiveStoreId = computed(() => scope.scopedStoreId(selectedStoreId.value))
const pendingCount = computed(() => records.value.filter((record) => isPending(record.status)).length)
const approvedCount = computed(() => records.value.filter((record) => isApproved(record.status)).length)
const filteredRecords = computed(() => records.value.filter((record) => !filterStatus.value || normalizeStatus(record.status) === filterStatus.value))

onMounted(() => {
  void initialize()
})

watch(effectiveStoreId, () => {
  if (!loading.value) void refreshData()
})

async function initialize() {
  loading.value = true
  pageError.value = ''
  try {
    if (scope.configurationError.value) {
      pageError.value = scope.configurationError.value
      return
    }
    stores.value = await getStores()
    selectedStoreId.value = scope.isStoreManager.value
      ? scope.boundStoreId.value
      : accessibleStores.value[0]?.id || ''
    await refreshData()
  } catch (error) {
    pageError.value = readableError(error, '每日报损暂时无法读取，请稍后刷新。')
  } finally {
    loading.value = false
  }
}

async function refreshData() {
  if (!effectiveStoreId.value) {
    items.value = []
    records.value = []
    return
  }
  refreshing.value = true
  pageError.value = ''
  try {
    const [itemRows, recordRows] = await Promise.all([
      getDailyLossItems(),
      getDailyLossRecords({ storeId: effectiveStoreId.value }),
    ])
    items.value = itemRows
    records.value = recordRows
  } catch (error) {
    pageError.value = readableError(error, '报损数据读取失败，请刷新后重试。')
  } finally {
    refreshing.value = false
  }
}

function onFilesChanged(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFiles.value = Array.from(input.files || []).slice(0, 6)
}

async function submitRecord() {
  if (submitting.value || !canSubmit.value) return
  if (!effectiveStoreId.value) {
    pageError.value = '请先选择有权限的门店。'
    return
  }
  const itemId = Number(form.value.itemId)
  const quantity = Number(form.value.quantity)
  if (!Number.isInteger(itemId) || itemId <= 0 || !Number.isFinite(quantity) || quantity <= 0 || !form.value.reason.trim()) {
    pageError.value = '请完整填写报损日期、物料、数量和原因。'
    return
  }
  submitting.value = true
  uploadProgress.value = 0
  pageError.value = ''
  actionMessage.value = ''
  try {
    // 单价和金额只由后端按成本快照计算，客户端只提交物料、数量和业务原因。
    const created = await createDailyLossRecord({
      storeId: effectiveStoreId.value,
      lossDate: form.value.lossDate,
      itemId,
      lossQuantity: quantity,
      lossReason: form.value.reason.trim(),
    })
    if (selectedFiles.value.length) {
      await uploadDailyLossAttachments(created.id, selectedFiles.value, (percent) => { uploadProgress.value = percent })
    }
    await submitDailyLossRecord(created.id)
    actionMessage.value = '报损单已提交，审核通过后才会扣减库存。'
    form.value = { lossDate: localDate(), itemId: '', quantity: '', reason: '' }
    selectedFiles.value = []
    await refreshData()
  } catch (error) {
    pageError.value = readableError(error, '报损提交失败，请检查填写内容后重试。')
  } finally {
    submitting.value = false
    uploadProgress.value = 0
  }
}

async function approveRecord(record: DailyLossRecord) {
  if (!canReview.value || approvingId.value) return
  approvingId.value = record.id
  pageError.value = ''
  actionMessage.value = ''
  try {
    await approveDailyLossRecord(record.id, approvalNotes.value[record.id])
    actionMessage.value = `报损单 ${record.id} 已审核通过，系统将按单据幂等扣减库存。`
    await refreshData()
  } catch (error) {
    pageError.value = readableError(error, '审核失败，请刷新记录后重试。')
  } finally {
    approvingId.value = ''
  }
}

function formatAmount(value?: number) {
  return value == null ? '待后端核算' : `¥${Number(value).toFixed(2)}`
}

function normalizeStatus(value?: string) {
  return String(value || '').trim().toUpperCase()
}

function isPending(value?: string) {
  return ['SUBMITTED', 'PENDING_REVIEW', '待审核'].includes(normalizeStatus(value))
}

function isApproved(value?: string) {
  return ['APPROVED', '已通过', 'COMPLETED'].includes(normalizeStatus(value))
}

function statusLabel(value?: string) {
  const status = normalizeStatus(value)
  if (isPending(status)) return '待审核'
  if (isApproved(status)) return '已通过'
  if (['REJECTED', '已驳回'].includes(status)) return '已驳回'
  if (['DRAFT', '草稿'].includes(status)) return '草稿'
  return value || '处理中'
}

function readableError(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function localDate() {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}
</script>

<template>
  <section class="daily-loss-page">
    <PageHeader subtitle="先登记、后审核；审核通过后系统才会按成本快照扣减库存。">
      <template #actions>
        <UiButton :loading="refreshing" @click="refreshData">
          <template #icon><RefreshCw :size="16" /></template>
          刷新记录
        </UiButton>
      </template>
    </PageHeader>

    <div v-if="pageError" class="message message--error" role="alert">{{ pageError }}</div>
    <div v-if="actionMessage" class="message message--success" role="status">{{ actionMessage }}</div>

    <section class="loss-scope content-card">
      <BusinessScopeBar v-if="scope.isStoreManager.value" />
      <label v-else>
        <span>门店</span>
        <select v-model="selectedStoreId" :disabled="loading || refreshing">
          <option value="" disabled>请选择门店</option>
          <option v-for="store in accessibleStores" :key="store.id" :value="store.id">
            {{ store.brandName ? `${store.brandName} · ` : '' }}{{ store.name || store.id }}
          </option>
        </select>
      </label>
      <div class="loss-principle"><ShieldCheck :size="17" /><span>金额由后端根据成本快照核算，页面不录入单价。</span></div>
    </section>

    <div class="loss-summary" aria-label="报损汇总">
      <article><span>待审核</span><strong>{{ pendingCount }}</strong><small>尚未扣减库存</small></article>
      <article><span>已通过</span><strong>{{ approvedCount }}</strong><small>已生成一次库存流水</small></article>
      <article><span>当前记录</span><strong>{{ records.length }}</strong><small>仅显示权限范围内门店</small></article>
    </div>

    <section class="loss-layout">
      <form class="content-card loss-form" @submit.prevent="submitRecord">
        <div class="section-heading"><PackageMinus :size="20" /><div><h2>提交每日报损</h2><p>提交不直接扣库存，等待审核确认。</p></div></div>
        <label>
          <span>报损日期</span>
          <input v-model="form.lossDate" type="date" required />
        </label>
        <label>
          <span>物料</span>
          <select v-model="form.itemId" required :disabled="!effectiveStoreId || refreshing">
            <option value="" disabled>请选择在用物料</option>
            <option v-for="item in items" :key="item.id" :value="String(item.id)">
              {{ item.code ? `${item.code} · ` : '' }}{{ item.name }}{{ item.stockUnit ? `（${item.stockUnit}）` : '' }}
            </option>
          </select>
        </label>
        <label>
          <span>报损数量</span>
          <input v-model="form.quantity" type="number" min="0.01" step="0.01" inputmode="decimal" placeholder="例如 2.5" required />
        </label>
        <label>
          <span>报损原因</span>
          <textarea v-model="form.reason" rows="4" maxlength="500" placeholder="例如临期变质、操作损耗或配送破损" required />
        </label>
        <label class="attachment-field">
          <span><FileUp :size="15" />附件（可选，最多 6 个）</span>
          <input type="file" multiple accept="image/*,.pdf" @change="onFilesChanged" />
          <small v-if="selectedFiles.length">已选择 {{ selectedFiles.length }} 个附件<span v-if="submitting && uploadProgress">，上传 {{ uploadProgress }}%</span></small>
        </label>
        <UiButton variant="primary" type="submit" :loading="submitting" :disabled="!canSubmit || !effectiveStoreId">
          <template #icon><Send :size="17" /></template>
          提交报损
        </UiButton>
        <p v-if="!canSubmit" class="permission-hint">当前账号只有查看权限，不能提交报损。</p>
      </form>

      <section class="content-card records-card">
        <div class="records-toolbar">
          <div><h2>报损记录</h2><p>单价与金额来自后端快照，不能在页面修改。</p></div>
          <label><span class="sr-only">状态筛选</span><select v-model="filterStatus"><option value="">全部状态</option><option value="SUBMITTED">待审核</option><option value="APPROVED">已通过</option><option value="REJECTED">已驳回</option></select></label>
        </div>
        <div v-if="loading || refreshing" class="empty-state">正在读取报损记录...</div>
        <div v-else-if="!filteredRecords.length" class="empty-state">暂无报损记录。提交后会在这里跟踪审核和库存处理状态。</div>
        <ol v-else class="record-list">
          <li v-for="record in filteredRecords" :key="record.id" class="record-row">
            <div class="record-main">
              <div class="record-title"><strong>{{ record.itemName }}</strong><span class="status-pill" :class="`status-${normalizeStatus(record.status).toLowerCase()}`">{{ statusLabel(record.status) }}</span></div>
              <p>{{ record.lossDate }} · {{ record.lossQuantity }}{{ record.stockUnit || '' }} · {{ record.lossReason }}</p>
              <small>金额 {{ formatAmount(record.amountSnapshot) }} · 提交人 {{ record.submittedByName || '当前业务人员' }}</small>
              <div v-if="record.attachments?.length" class="attachment-list">
                <a v-for="attachment in record.attachments" :key="String(attachment.id)" :href="attachment.downloadUrl" target="_blank" rel="noreferrer">{{ attachment.fileName }}</a>
              </div>
            </div>
            <div v-if="canReview && isPending(record.status)" class="review-action">
              <input v-model="approvalNotes[record.id]" maxlength="200" placeholder="审核备注（可选）" />
              <UiButton variant="primary" :loading="approvingId === record.id" @click="approveRecord(record)"><template #icon><CheckCircle2 :size="16" /></template>审核通过</UiButton>
            </div>
            <p v-else-if="record.reviewNote" class="approval-note">审核说明：{{ record.reviewNote }}</p>
          </li>
        </ol>
      </section>
    </section>
  </section>
</template>

<style scoped>
.daily-loss-page { display: grid; gap: 18px; }
.message { padding: 11px 13px; border-radius: 8px; font-weight: 650; font-size: 14px; }
.message--error { color: #9f2734; border: 1px solid #efc9cf; background: #fff5f5; }
.message--success { color: #185c48; border: 1px solid #bde4d5; background: #f0fbf6; }
.loss-scope { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.loss-scope label { display: grid; min-width: 220px; gap: 5px; color: var(--ds-muted); font-weight: 700; font-size: 12px; }
.loss-scope select, .loss-form input, .loss-form select, .loss-form textarea, .review-action input, .records-toolbar select { width: 100%; min-height: 40px; padding: 8px 10px; border: 1px solid var(--ds-line); border-radius: 7px; background: var(--ds-surface); color: var(--ds-ink); font: inherit; }
.loss-form textarea { resize: vertical; min-height: 94px; }
.loss-principle { display: inline-flex; align-items: center; gap: 8px; color: #31645e; font-size: 13px; font-weight: 650; }
.loss-principle svg { color: var(--ds-primary-hover); }
.loss-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.loss-summary article { display: grid; gap: 4px; padding: 15px 16px; border: 1px solid var(--ds-line); border-radius: 8px; background: var(--ds-surface); }
.loss-summary span, .loss-summary small { color: var(--ds-muted); font-size: 12px; }
.loss-summary strong { color: var(--ds-ink); font-size: 25px; line-height: 1; }
.loss-layout { display: grid; grid-template-columns: minmax(280px, .78fr) minmax(0, 1.4fr); gap: 18px; align-items: start; }
.loss-form { display: grid; gap: 13px; }
.loss-form label { display: grid; gap: 6px; color: var(--ds-secondary); font-size: 13px; font-weight: 700; }
.section-heading { display: flex; align-items: flex-start; gap: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--ds-line); }
.section-heading svg { margin-top: 2px; color: var(--ds-primary-hover); }
.section-heading h2, .records-toolbar h2 { margin: 0; color: var(--ds-ink); font-size: 17px; }
.section-heading p, .records-toolbar p { margin: 3px 0 0; color: var(--ds-muted); font-size: 12px; line-height: 1.5; }
.attachment-field span { display: inline-flex; align-items: center; gap: 6px; }
.attachment-field input { padding: 7px; }
.attachment-field small, .permission-hint { margin: 0; color: var(--ds-muted); font-size: 12px; }
.records-card { display: grid; gap: 12px; min-width: 0; }
.records-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--ds-line); }
.records-toolbar select { min-width: 124px; }
.record-list { display: grid; margin: 0; padding: 0; list-style: none; gap: 10px; }
.record-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; padding: 13px; border: 1px solid var(--ds-line); border-radius: 8px; background: #fff; }
.record-main { min-width: 0; }
.record-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.record-title strong { color: var(--ds-ink); font-size: 15px; }
.record-main p { margin: 7px 0 4px; color: var(--ds-secondary); font-size: 13px; line-height: 1.5; }
.record-main small { color: var(--ds-muted); font-size: 12px; }
.status-pill { padding: 3px 7px; border-radius: 999px; background: #eef4f3; color: #3e5e5b; font-size: 11px; font-weight: 800; }
.status-submitted, .status-pending_review { background: #fff5df; color: #9a6814; }
.status-approved, .status-completed { background: #e9f8f0; color: #1d7655; }
.status-rejected { background: #fff0f1; color: #a53a46; }
.review-action { display: grid; align-content: start; min-width: 174px; gap: 8px; }
.review-action input { min-height: 36px; font-size: 12px; }
.review-action :deep(.ui-button) { min-width: 126px; height: 38px; padding: 0 12px; font-size: 13px; }
.approval-note { grid-column: 1 / -1; margin: 0; color: var(--ds-muted); font-size: 12px; }
.attachment-list { display: flex; margin-top: 8px; gap: 7px; flex-wrap: wrap; }
.attachment-list a { color: var(--ds-primary-hover); font-size: 12px; font-weight: 650; }
.empty-state { padding: 32px 16px; color: var(--ds-muted); text-align: center; font-size: 14px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@media (max-width: 900px) { .loss-layout { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .loss-summary { grid-template-columns: 1fr; } .loss-scope label { width: 100%; min-width: 0; } .records-toolbar, .record-row { grid-template-columns: 1fr; flex-direction: column; } .records-toolbar { align-items: stretch; } .review-action { min-width: 0; } .review-action :deep(.ui-button) { width: 100%; } }
</style>
