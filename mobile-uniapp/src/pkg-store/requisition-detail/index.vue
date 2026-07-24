<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import { getMobileRequisitions, receiveMobileRequisition, reviewMobileRequisition } from '@/api/business'
import StatusTimeline, { type TimelineItem } from '@/components/StatusTimeline.vue'
import { canPerformMobileAction, hasPermission } from '@/permissions'
import { openProtectedFile } from '@/platform'
import { canUseMobileCapability, useSessionStore } from '@/stores'
import { MOBILE_PERMISSIONS, type WarehouseRequisition } from '@/types/business'

const session = useSessionStore()
const requisitionId = ref('')
const record = ref<WarehouseRequisition | null>(null)
const loading = ref(false)
const receiving = ref(false)
const message = ref('')
const canRead = computed(() => canUseMobileCapability(session.user, 'requisition') || canUseMobileCapability(session.user, 'warehouse'))
const canReceive = computed(() => canRead.value && session.hasPermission(MOBILE_PERMISSIONS.requisitionReceive))
const canReview = computed(() => canPerformMobileAction(session.user, 'warehouse.requisition.review'))
const canReadFiles = computed(() => hasPermission(session.user, 'attachment.read'))
const timeline = computed<TimelineItem[]>(() => {
  const row = record.value
  if (!row) return []
  const rejected = row.status === 'REJECTED'
  return [
    { label: '门店提交叫货', time: row.submittedAt, done: Boolean(row.submittedAt) || row.status !== 'DRAFT' },
    { label: rejected ? '仓库审核驳回' : '仓库完成审核', time: row.reviewedAt, done: rejected || ['APPROVED','SHIPPED','RECEIVED'].includes(row.status) },
    { label: '仓库确认发货', time: row.shippedAt, done: ['SHIPPED','RECEIVED'].includes(row.status) },
    { label: '门店确认收货', time: row.receivedAt, done: row.status === 'RECEIVED' },
  ]
})

onLoad(query => { requisitionId.value = String(query?.id || ''); void refresh() })
onPullDownRefresh(async () => { await refresh(); uni.stopPullDownRefresh() })

async function refresh() {
  if (!canRead.value || !requisitionId.value || loading.value) return
  loading.value = true
  message.value = ''
  try {
    const rows = await getMobileRequisitions()
    record.value = rows.find(row => row.id === requisitionId.value) || null
    if (!record.value) message.value = '叫货单不存在或当前账号无权查看。'
  } catch (cause) { message.value = friendlyError(cause, '叫货单详情暂时无法加载。') }
  finally { loading.value = false }
}

async function receive() {
  const row = record.value
  if (!row || !canReceive.value || row.status !== 'SHIPPED' || receiving.value) return
  if (!await confirmAction(`确认已收到叫货单 ${row.id} 的全部已发物料吗？`)) return
  receiving.value = true
  message.value = ''
  try { await receiveMobileRequisition(row.id, '移动端详情页确认收货'); message.value = '收货已确认，库存以服务端结果为准。'; await refresh() }
  catch (cause) { message.value = friendlyError(cause, '确认收货失败，请刷新后重试。') }
  finally { receiving.value = false }
}

async function review(approved: boolean) {
  const row = record.value
  if (!row || !canReview.value || row.status !== 'SUBMITTED' || receiving.value) return
  const note = approved ? '移动端仓库审核通过' : await promptText('请填写驳回原因')
  if (!approved && !note) return
  receiving.value = true
  message.value = ''
  try {
    await reviewMobileRequisition(row.id, approved, note || undefined, row.lines.map(line => ({
      itemId: line.itemId,
      approvedQuantity: approved ? Number(line.requestedQuantity) : 0,
    })))
    message.value = approved ? '叫货单已审核通过，可继续确认发货。' : '叫货单已驳回。'
    await refresh()
  } catch (cause) { message.value = friendlyError(cause, '审核失败，请刷新后重试。') }
  finally { receiving.value = false }
}

async function openDelivery() {
  const row = record.value
  if (!row) return
  try { await openProtectedFile({ path: `/api/warehouse/print/requisitions/${encodeURIComponent(row.id)}/delivery`, fileName: `配送出库单-${row.id}.pdf`, contentType: 'application/pdf' }) }
  catch (cause) { message.value = friendlyError(cause, '配送出库单打开失败。') }
}

function confirmAction(content: string) { return new Promise<boolean>(resolve => uni.showModal({ title: '确认收货', content, confirmText: '确认', success: result => resolve(Boolean(result.confirm)), fail: () => resolve(false) })) }
function promptText(placeholder: string) { return new Promise<string>(resolve => uni.showModal({ title: '驳回叫货单', editable: true, placeholderText: placeholder, success: result => resolve(result.confirm ? String(result.content || '').trim() : ''), fail: () => resolve('') })) }
function formatQuantity(value: number | undefined) { const number = Number(value || 0); return Number.isInteger(number) ? String(number) : number.toFixed(2) }
function friendlyError(cause: unknown, fallback: string) { const status = Number((cause as { status?: number })?.status || 0); return status === 403 ? '当前账号无权查看或操作该叫货单。' : status === 409 ? '单据状态已变化，请刷新后重试。' : status === 401 ? '登录已过期，请重新登录。' : fallback }
</script>

<template>
  <view class="page">
    <view v-if="message" class="notice">{{ message }}</view>
    <view v-if="!record && !loading" class="state">叫货单不存在或当前账号无权查看。</view>
    <template v-if="record">
      <view class="header"><view><text class="eyebrow">{{ record.warehouseName || '配送仓库' }}</text><text class="title">叫货单详情</text><text class="copy">{{ record.id }}</text></view><text class="status">{{ record.statusLabel || record.status }}</text></view>
      <view class="section"><text class="section-title">物料明细</text><view v-for="line in record.lines" :key="line.itemId" class="line"><text class="name">{{ line.itemName }}</text><view class="quantities"><text>申请 {{ formatQuantity(line.requestedQuantity) }}</text><text>核定 {{ formatQuantity(line.approvedQuantity) }}</text><text>实发 {{ formatQuantity(line.shippedQuantity) }}</text><text>已收 {{ formatQuantity(line.receivedQuantity) }}</text></view><text v-if="line.warningText" class="warning">{{ line.warningText }}</text></view></view>
      <view v-if="record.note" class="section"><text class="section-title">叫货备注</text><text class="copy note">{{ record.note }}</text></view>
      <view class="section"><text class="section-title">处理进度</text><StatusTimeline :items="timeline"/></view>
      <view class="actions"><button v-if="canReview&&record.status==='SUBMITTED'" :loading="receiving" :disabled="receiving" @click="review(false)">驳回</button><button v-if="canReview&&record.status==='SUBMITTED'" class="primary" :loading="receiving" :disabled="receiving" @click="review(true)">审核通过</button><button v-if="canReadFiles&&['SHIPPED','RECEIVED'].includes(record.status)" @click="openDelivery">查看配送出库单</button><button v-if="canReceive&&record.status==='SHIPPED'" class="primary" :loading="receiving" :disabled="receiving" @click="receive">确认收货</button></view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page{min-height:100vh;box-sizing:border-box;padding:24rpx 24rpx calc(48rpx + env(safe-area-inset-bottom));background:#f2f6f5;color:#202124}.header,.section,.notice,.state{padding:24rpx;background:#fff;border:1rpx solid #d9e6e3;border-radius:16rpx}.header{display:flex;align-items:flex-start;justify-content:space-between;gap:16rpx}.eyebrow,.title,.copy,.section-title,.name,.warning{display:block}.eyebrow,.copy{color:#71807d;font-size:23rpx}.title{margin-top:6rpx;font-size:38rpx;font-weight:750}.status{padding:10rpx 14rpx;background:#e6f3f1;color:#1f5752;border-radius:12rpx;font-size:23rpx}.section{margin-top:16rpx}.section-title,.name{font-size:28rpx;font-weight:700}.line{margin-top:16rpx;padding-top:16rpx;border-top:1rpx solid #edf0f3}.quantities{display:grid;grid-template-columns:repeat(2,1fr);gap:8rpx;margin-top:10rpx;color:#4f5661;font-size:24rpx}.warning{margin-top:10rpx;color:#a56a14;font-size:23rpx}.note{margin-top:12rpx;line-height:1.6}.actions{display:flex;gap:12rpx;margin-top:18rpx}.actions button{flex:1;margin:0;background:#f7faf9;color:#4e5966}.actions .primary{background:#27655f;color:#fff}.notice,.state{text-align:center;color:#71807d}
</style>
