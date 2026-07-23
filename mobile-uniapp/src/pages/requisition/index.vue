<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import {
  createMobileRequisition,
  getMobileRequisitions,
  getMobileWarehouseOverview,
  receiveMobileRequisition,
} from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { showOperationFailure, showOperationSuccess } from '../../platform'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import {
  MOBILE_PERMISSIONS,
  type WarehouseItem,
  type WarehouseRequisition,
} from '../../types/business'

const session = useSessionStore()
const items = ref<WarehouseItem[]>([])
const requisitions = ref<WarehouseRequisition[]>([])
const quantities = reactive<Record<number, number>>({})
const note = ref('')
const loading = ref(false)
const submitting = ref(false)
const receivingId = ref('')
const createFormOpen = ref(false)
const recordFilter = ref<'PROCESSING' | 'HISTORY' | 'ALL'>('PROCESSING')
const errorMessage = ref('')
const actionMessage = ref('')
const requestId = ref('')
const requestFingerprint = ref('')

const hasCapability = computed(() => canUseMobileCapability(session.user, 'requisition'))
const canRead = computed(() => hasCapability.value && session.hasAnyPermission([MOBILE_PERMISSIONS.warehouseRead, 'warehouse.read']))
const canCreate = computed(() => hasCapability.value && session.hasPermission(MOBILE_PERMISSIONS.requisitionCreate))
const canReceive = computed(() => hasCapability.value && session.hasPermission(MOBILE_PERMISSIONS.requisitionReceive))
const activeItems = computed(() => items.value.filter((item) => item.active))
const selectedLines = computed(() => activeItems.value
  .map((item) => ({ item, quantity: normalizeQuantity(quantities[item.id]) }))
  .filter((line) => line.quantity > 0))
const selectedTotal = computed(() => selectedLines.value.reduce((sum, line) => sum + line.quantity, 0))
const pendingReceipts = computed(() => requisitions.value.filter(record => record.status === 'SHIPPED'))
const processingRecords = computed(() => requisitions.value.filter(record => ['SUBMITTED', 'APPROVED'].includes(record.status)))
const historyRecords = computed(() => requisitions.value.filter(record => ['RECEIVED', 'REJECTED', 'TODO_DONE'].includes(record.status)))
const visibleRecords = computed(() => {
  if (recordFilter.value === 'PROCESSING') return processingRecords.value
  if (recordFilter.value === 'HISTORY') return historyRecords.value
  return requisitions.value.filter(record => record.status !== 'SHIPPED')
})

onShow(() => {
  if (!hasCapability.value) return denyAndReturn()
  void refresh()
})
onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})

async function refresh() {
  if (!canRead.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    const [overview, records] = await Promise.all([
      getMobileWarehouseOverview(),
      getMobileRequisitions(),
    ])
    items.value = overview.items || []
    requisitions.value = records || []
  } catch (error) {
    errorMessage.value = friendlyError(error, '叫货数据暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function changeQuantity(itemId: number, delta: number) {
  invalidateRequestId()
  quantities[itemId] = Math.max(0, normalizeQuantity(quantities[itemId]) + delta)
}

function setQuantity(itemId: number, event: unknown) {
  invalidateRequestId()
  quantities[itemId] = normalizeQuantity((event as { detail?: { value?: string } })?.detail?.value)
}

function invalidateRequestId() {
  if (!submitting.value) {
    requestId.value = ''
    requestFingerprint.value = ''
  }
}

async function submit() {
  if (!canCreate.value || submitting.value) return
  if (!selectedLines.value.length) {
    const content = '请先填写至少一种物料的叫货数量。'
    errorMessage.value = content
    showOperationFailure('无法提交叫货', new Error(content))
    return
  }
  if (!(await confirmAction(`本次共叫货 ${selectedLines.value.length} 种、${formatNumber(selectedTotal.value)} 件，确认提交吗？`))) return

  submitting.value = true
  errorMessage.value = ''
  actionMessage.value = ''
  const fingerprint = JSON.stringify({
    note: note.value.trim(),
    lines: selectedLines.value.map(({ item, quantity }) => ({ itemId: item.id, quantity })),
  })
  if (!requestId.value || requestFingerprint.value !== fingerprint) {
    requestId.value = createRequestId('mobile-requisition')
    requestFingerprint.value = fingerprint
  }
  try {
    const saved = await createMobileRequisition({
      clientRequestId: requestId.value,
      note: note.value.trim() || undefined,
      lines: selectedLines.value.map(({ item, quantity }) => ({
        itemId: item.id,
        requestedQuantity: quantity,
      })),
    })
    actionMessage.value = `叫货单 ${saved.id} 已提交，仓库将按真实库存审核。`
    Object.keys(quantities).forEach((key) => delete quantities[Number(key)])
    note.value = ''
    requestId.value = ''
    requestFingerprint.value = ''
    createFormOpen.value = false
    await refresh()
    await showOperationSuccess(
      '叫货已提交',
      `叫货单 ${saved.id} 已提交，仓库将按实际库存审核。`,
    )
  } catch (error) {
    errorMessage.value = friendlyError(error, '叫货单未提交成功；再次提交会沿用同一请求编号，不会重复建单。')
  } finally {
    submitting.value = false
  }
}

function openCreateForm() {
  createFormOpen.value = true
  errorMessage.value = ''
  actionMessage.value = ''
}

async function closeCreateForm() {
  if (submitting.value) return
  if ((selectedLines.value.length || note.value.trim()) && !(await confirmAction('当前填写内容尚未提交，确认放弃吗？'))) return
  resetCreateForm()
}

function resetCreateForm() {
  Object.keys(quantities).forEach(key => delete quantities[Number(key)])
  note.value = ''
  requestId.value = ''
  requestFingerprint.value = ''
  createFormOpen.value = false
}

async function receive(record: WarehouseRequisition) {
  if (!canReceive.value || receivingId.value) return
  if (!(await confirmAction(`确认已收到叫货单 ${record.id} 的全部已发物料吗？`))) return
  receivingId.value = record.id
  errorMessage.value = ''
  actionMessage.value = ''
  try {
    await receiveMobileRequisition(record.id, '移动端确认收货')
    actionMessage.value = '收货已确认，门店库存以服务端入库结果为准。'
    await refresh()
    await showOperationSuccess('收货已确认', '门店库存已按服务端入库结果更新。')
  } catch (error) {
    errorMessage.value = friendlyError(error, '收货确认失败，请刷新单据状态后重试。')
  } finally {
    receivingId.value = ''
  }
}

function normalizeQuantity(value: unknown) {
  const number = Number(value || 0)
  if (!Number.isFinite(number) || number <= 0) return 0
  return Math.round(number * 100) / 100
}

function formatNumber(value: unknown) {
  const number = Number(value || 0)
  return Number.isInteger(number) ? String(number) : number.toFixed(2)
}

function createRequestId(prefix: string) {
  const random = Math.random().toString(36).slice(2, 10)
  return `${prefix}-${Date.now()}-${random}`
}

function openDetail(record: WarehouseRequisition) {
  uni.navigateTo({ url: `/pkg-store/requisition-detail/index?id=${encodeURIComponent(record.id)}` })
}

function denyAndReturn() {
  uni.showToast({ title: '叫货与收货仅向店长开放', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}

function confirmAction(content: string) {
  return new Promise<boolean>((resolve) => {
    uni.showModal({ title: '请确认', content, confirmText: '确认', success: (result) => resolve(Boolean(result.confirm)), fail: () => resolve(false) })
  })
}

function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 403) return '当前账号无权操作该门店或叫货单。'
  if (status === 409) return '单据状态已变化，请刷新后再操作。'
  if (status === 401) return '登录已过期，请重新登录。'
  return fallback
}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view>
        <text class="eyebrow">店长工作台</text>
        <text class="title">门店叫货</text>
      </view>
      <button class="ghost-button" :loading="loading" :disabled="loading || !canRead" @click="refresh">刷新</button>
    </view>

    <view v-if="!canRead" class="state-card">
      <text class="state-title">叫货与收货仅向店长开放</text>
      <text class="muted">还需具备后端叫货权限和本店数据范围，页面不会扩大权限。</text>
    </view>

    <template v-else>
      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="actionMessage" class="message success">{{ actionMessage }}</view>

      <view class="section-head priority-head">
        <view>
          <text class="section-title">待确认收货</text>
          <text class="muted">货物送达门店并核对无误后再确认</text>
        </view>
        <text class="count-badge" :class="{ urgent: pendingReceipts.length }">{{ pendingReceipts.length }} 单</text>
      </view>
      <view v-if="!pendingReceipts.length && !loading" class="state-card compact-state">当前没有待确认收货的叫货单</view>
      <view v-for="record in pendingReceipts" :key="record.id" class="record-card receipt-card">
        <view class="record-head">
          <view>
            <text class="item-name">{{ record.statusLabel || record.status }}</text>
            <text class="muted">{{ record.id }} · {{ record.warehouseName || '配送仓库' }}</text>
          </view>
          <text class="line-count">{{ record.lines.length }} 种</text>
        </view>
        <view class="line-summary">
          <text v-for="line in record.lines" :key="line.itemId" class="line-text">
            {{ line.itemName }} × {{ formatNumber(line.requestedQuantity) }}
          </text>
        </view>
        <button class="detail-button" @click="openDetail(record)">查看详情</button>
        <button
          v-if="canReceive"
          class="receive-button"
          :loading="receivingId === record.id"
          :disabled="Boolean(receivingId)"
          @click="receive(record)"
        >
          确认收货
        </button>
      </view>

      <view v-if="canCreate && !createFormOpen" class="create-entry">
        <view>
          <text class="section-title">需要补货？</text>
          <text class="muted">创建新的叫货单，提交后由仓库审核并安排发货</text>
        </view>
        <button class="create-button" @click="openCreateForm">新建叫货单</button>
      </view>

      <template v-if="canCreate && createFormOpen">
        <view class="section-head create-head">
          <view>
            <text class="section-title">新建叫货单</text>
            <text class="muted">按门店实际缺口填写，数量为 0 的物料不会提交</text>
          </view>
          <button class="cancel-button" :disabled="submitting" @click="closeCreateForm">取消</button>
        </view>
        <view v-if="loading && !activeItems.length" class="state-card">正在读取可叫物料…</view>
        <view v-if="!activeItems.length && !loading" class="state-card">暂无可叫物料</view>
        <view v-for="item in activeItems" :key="item.id" class="item-card">
          <view class="item-copy">
            <text class="item-name">{{ item.name }}</text>
            <text class="muted">本店库存 {{ formatNumber(item.storeStockQuantity) }} {{ item.unit || '' }}</text>
            <text v-if="item.alertText" class="warning-text">{{ item.alertText }}</text>
          </view>
          <view class="stepper">
            <button class="step-button" aria-label="减少数量" :disabled="submitting" @click="changeQuantity(item.id, -1)">−</button>
            <input
              class="quantity-input"
              type="digit"
              :value="quantities[item.id] || ''"
              placeholder="0"
              :disabled="submitting"
              :adjust-position="true"
              :cursor-spacing="140"
              @input="setQuantity(item.id, $event)"
            >
            <button class="step-button" aria-label="增加数量" :disabled="submitting" @click="changeQuantity(item.id, 1)">＋</button>
          </view>
        </view>
        <view class="note-card">
          <text class="field-label">叫货备注（选填）</text>
          <textarea v-model="note" class="note-input" maxlength="300" placeholder="例如：周末活动备货" :disabled="submitting" :adjust-position="true" :cursor-spacing="140" @input="invalidateRequestId" />
        </view>
      </template>

      <view class="section-head records-head">
        <view>
          <text class="section-title">叫货记录</text>
          <text class="muted">待收货单已单独显示在上方</text>
        </view>
        <text class="muted">{{ requisitions.length }} 条</text>
      </view>
      <scroll-view scroll-x class="record-filter-scroll">
        <view class="record-filters">
          <button :class="{ active: recordFilter === 'PROCESSING' }" @click="recordFilter = 'PROCESSING'">处理中 {{ processingRecords.length }}</button>
          <button :class="{ active: recordFilter === 'HISTORY' }" @click="recordFilter = 'HISTORY'">已结束 {{ historyRecords.length }}</button>
          <button :class="{ active: recordFilter === 'ALL' }" @click="recordFilter = 'ALL'">全部</button>
        </view>
      </scroll-view>
      <view v-if="!visibleRecords.length && !loading" class="state-card">当前分类暂无叫货记录</view>
      <view v-for="record in visibleRecords" :key="record.id" class="record-card">
        <view class="record-head">
          <view>
            <text class="item-name">{{ record.statusLabel || record.status }}</text>
            <text class="muted">{{ record.id }} · {{ record.warehouseName || '配送仓库' }}</text>
          </view>
          <text class="line-count">{{ record.lines.length }} 种</text>
        </view>
        <view class="line-summary">
          <text v-for="line in record.lines" :key="line.itemId" class="line-text">
            {{ line.itemName }} × {{ formatNumber(line.requestedQuantity) }}
          </text>
        </view>
        <button class="detail-button" @click="openDetail(record)">查看详情</button>
      </view>
    </template>

    <SafeActionBar v-if="canCreate && createFormOpen">
      <view class="selection-copy">
        <text>已选 {{ selectedLines.length }} 种</text>
        <text class="muted">合计 {{ formatNumber(selectedTotal) }} 件</text>
      </view>
      <button class="primary-button" :loading="submitting" :disabled="submitting || !selectedLines.length" @click="submit">提交叫货</button>
    </SafeActionBar>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx calc(36rpx + env(safe-area-inset-bottom)); background: #f2f6f5; color: #1c1d22; }
.page-head, .section-head, .record-head, .safe-action-bar, .item-card, .create-entry { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .section-title, .item-name, .muted, .warning-text, .field-label, .line-text, .state-title { display: block; }
.eyebrow { color: #71807d; font-size: 24rpx; letter-spacing: 0; }
.title { margin-top: 6rpx; font-size: 38rpx; font-weight: 700; }
.section-head { margin: 26rpx 2rpx 16rpx; }
.section-title { font-size: 30rpx; font-weight: 700; }
.item-card, .record-card, .note-card, .state-card { margin-bottom: 16rpx; padding: 24rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(37,39,45,.045); }
.priority-head { margin-top: 8rpx; }
.count-badge { flex-shrink: 0; padding: 8rpx 14rpx; border-radius: 999rpx; background: #e7efed; color: #52615e; font-size: 23rpx; font-weight: 700; }
.count-badge.urgent { background: #fff0d7; color: #936012; }
.compact-state { padding: 20rpx; color: #71807d; }
.receipt-card { border-color: #ead29d; background: #fffdf7; }
.create-entry { margin: 28rpx 0 12rpx; padding: 24rpx; border: 1px solid #bdd8d3; border-radius: 16rpx; background: #f8fcfb; }
.create-entry > view { flex: 1; min-width: 0; }
.create-button { flex-shrink: 0; min-height: 80rpx; margin: 0; padding: 0 24rpx; border-radius: 14rpx; background: #27655f; color: #fff; font-size: 26rpx; font-weight: 700; }
.create-head { align-items: flex-start; margin-top: 30rpx; }
.cancel-button { flex-shrink: 0; min-height: 72rpx; margin: 0; padding: 0 22rpx; border-radius: 14rpx; background: #fff; color: #596660; font-size: 25rpx; }
.item-copy { flex: 1; min-width: 0; }
.item-name { margin-bottom: 8rpx; font-size: 30rpx; font-weight: 650; }
.muted { color: #71807d; font-size: 23rpx; line-height: 1.5; }
.warning-text { margin-top: 8rpx; color: #a56a14; font-size: 22rpx; }
.stepper { display: flex; align-items: center; flex-shrink: 0; }
.step-button { width: 88rpx; min-width: 88rpx; height: 88rpx; line-height: 88rpx; padding: 0; border-radius: 16rpx; background: #e6f3f1; color: #27655f; font-size: 34rpx; }
.quantity-input { width: 112rpx; height: 88rpx; margin: 0 8rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #fff; text-align: center; font-size: 30rpx; }
.field-label { margin-bottom: 12rpx; font-weight: 650; }
.note-input { width: 100%; min-height: 150rpx; box-sizing: border-box; padding: 18rpx; border-radius: 16rpx; background: #f5f7f4; font-size: 27rpx; }
.records-head { margin-top: 38rpx; }
.record-filter-scroll { width: 100%; margin: 0 0 16rpx; white-space: nowrap; }
.record-filters { display: inline-flex; gap: 12rpx; padding: 2rpx; }
.record-filters button { min-height: 70rpx; margin: 0; padding: 0 24rpx; border-radius: 999rpx; background: #fff; color: #66736f; font-size: 24rpx; }
.record-filters button.active { background: #27655f; color: #fff; }
.line-count { color: #27655f; font-weight: 700; }
.line-summary { margin: 18rpx 0 4rpx; padding-top: 14rpx; border-top: 1px solid #edf0ed; }
.line-text { padding: 6rpx 0; color: #4b574f; font-size: 24rpx; }
.receive-button { min-height: 88rpx; margin-top: 18rpx; border-radius: 16rpx; background: #e6f3f1; color: #27655f; font-size: 28rpx; }
.detail-button { min-height: 80rpx; margin-top: 18rpx; border-radius: 16rpx; background: #f7faf9; color: #4e5966; font-size: 27rpx; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.success { background: #eaf5ed; color: #24663e; }
.state-card { text-align: center; }
.state-title { margin-bottom: 8rpx; font-weight: 650; }
.ghost-button, .primary-button { min-height: 88rpx; line-height: 88rpx; border-radius: 16rpx; font-size: 28rpx; }
.ghost-button { min-width: 136rpx; background: #fff; color: #1f5752; border: 1px solid #d9e6e3; }
.selection-copy { min-width: 180rpx; font-size: 25rpx; }
.primary-button { flex: 1; margin: 0; background: #27655f; color: #fff; border: 0; font-weight: 650; }
button::after { border: 0; }
</style>
