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
    errorMessage.value = '请先填写至少一种物料的叫货数量。'
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
    await refresh()
  } catch (error) {
    errorMessage.value = friendlyError(error, '叫货单未提交成功；再次提交会沿用同一请求编号，不会重复建单。')
  } finally {
    submitting.value = false
  }
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

      <view class="section-head">
        <text class="section-title">填写叫货数量</text>
        <text class="muted">库存与可叫物料均来自后端</text>
      </view>
      <view v-if="loading && !activeItems.length" class="state-card">正在读取可叫物料…</view>
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

      <view v-if="canCreate" class="note-card">
        <text class="field-label">叫货备注（选填）</text>
        <textarea v-model="note" class="note-input" maxlength="300" placeholder="例如：周末活动备货" :disabled="submitting" :adjust-position="true" :cursor-spacing="140" @input="invalidateRequestId" />
      </view>

      <view class="section-head records-head">
        <text class="section-title">我的叫货单</text>
        <text class="muted">{{ requisitions.length }} 条</text>
      </view>
      <view v-if="!requisitions.length && !loading" class="state-card">暂无叫货记录</view>
      <view v-for="record in requisitions" :key="record.id" class="record-card">
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
        <button
          v-if="record.status === 'SHIPPED' && canReceive"
          class="receive-button"
          :loading="receivingId === record.id"
          :disabled="Boolean(receivingId)"
          @click="receive(record)"
        >
          确认收货
        </button>
      </view>
    </template>

    <SafeActionBar v-if="canCreate">
      <view class="selection-copy">
        <text>已选 {{ selectedLines.length }} 种</text>
        <text class="muted">合计 {{ formatNumber(selectedTotal) }} 件</text>
      </view>
      <button class="primary-button" :loading="submitting" :disabled="submitting || !selectedLines.length" @click="submit">提交叫货</button>
    </SafeActionBar>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx calc(190rpx + env(safe-area-inset-bottom)); background: #f4f6f2; color: #172019; }
.page-head, .section-head, .record-head, .safe-action-bar, .item-card { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .section-title, .item-name, .muted, .warning-text, .field-label, .line-text, .state-title { display: block; }
.eyebrow { color: #657168; font-size: 24rpx; letter-spacing: 2rpx; }
.title { margin-top: 6rpx; font-size: 44rpx; font-weight: 700; }
.section-head { margin: 26rpx 2rpx 16rpx; }
.section-title { font-size: 30rpx; font-weight: 700; }
.item-card, .record-card, .note-card, .state-card { margin-bottom: 16rpx; padding: 24rpx; border: 1px solid #dce2db; border-radius: 22rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(27,45,32,.045); }
.item-copy { flex: 1; min-width: 0; }
.item-name { margin-bottom: 8rpx; font-size: 30rpx; font-weight: 650; }
.muted { color: #6b746d; font-size: 23rpx; line-height: 1.5; }
.warning-text { margin-top: 8rpx; color: #a56a14; font-size: 22rpx; }
.stepper { display: flex; align-items: center; flex-shrink: 0; }
.step-button { width: 88rpx; min-width: 88rpx; height: 88rpx; line-height: 88rpx; padding: 0; border-radius: 18rpx; background: #edf3ee; color: #1f6741; font-size: 34rpx; }
.quantity-input { width: 112rpx; height: 88rpx; margin: 0 8rpx; border: 1px solid #cbd6cd; border-radius: 16rpx; background: #fff; text-align: center; font-size: 30rpx; }
.field-label { margin-bottom: 12rpx; font-weight: 650; }
.note-input { width: 100%; min-height: 150rpx; box-sizing: border-box; padding: 18rpx; border-radius: 16rpx; background: #f5f7f4; font-size: 27rpx; }
.records-head { margin-top: 38rpx; }
.line-count { color: #1f6741; font-weight: 700; }
.line-summary { margin: 18rpx 0 4rpx; padding-top: 14rpx; border-top: 1px solid #edf0ed; }
.line-text { padding: 6rpx 0; color: #4b574f; font-size: 24rpx; }
.receive-button { min-height: 88rpx; margin-top: 18rpx; border-radius: 18rpx; background: #e8f2eb; color: #1f6741; font-size: 28rpx; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.message.success { background: #eaf5ed; color: #24663e; }
.state-card { text-align: center; }
.state-title { margin-bottom: 8rpx; font-weight: 650; }
.ghost-button, .primary-button { min-height: 88rpx; line-height: 88rpx; border-radius: 18rpx; font-size: 28rpx; }
.ghost-button { min-width: 136rpx; background: #fff; color: #264c36; border: 1px solid #cbd6cd; }
.selection-copy { min-width: 180rpx; font-size: 25rpx; }
.primary-button { flex: 1; margin: 0; background: #1f6741; color: #fff; border: 0; font-weight: 650; }
button::after { border: 0; }
</style>
