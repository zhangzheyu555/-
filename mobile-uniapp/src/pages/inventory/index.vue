<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileWarehouseOverview } from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import { MOBILE_PERMISSIONS, type WarehouseOverview } from '../../types/business'

const session = useSessionStore()
const overview = ref<WarehouseOverview | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const hasCapability = computed(() => canUseMobileCapability(session.user, 'inventory'))
const canRead = computed(() => hasCapability.value && session.hasAnyPermission([MOBILE_PERMISSIONS.warehouseRead, 'warehouse.read']))
const canOrder = computed(() => canUseMobileCapability(session.user, 'requisition') && session.hasPermission(MOBILE_PERMISSIONS.requisitionCreate))
const items = computed(() => (overview.value?.items || []).filter((item) => item.active))

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
    overview.value = await getMobileWarehouseOverview()
  } catch (error) {
    errorMessage.value = friendlyError(error, '库存暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function openRequisition() {
  if (!canOrder.value) return
  uni.navigateTo({ url: '/pages/requisition/index' })
}

function denyAndReturn() {
  uni.showToast({ title: '该功能不在当前手机角色范围内', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}

function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 403) return '当前账号没有查看本店库存的权限。'
  if (status === 401) return '登录已过期，请重新登录。'
  return fallback
}

function quantity(value: number | undefined) {
  const number = Number(value || 0)
  return Number.isInteger(number) ? String(number) : number.toFixed(2)
}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view>
        <text class="eyebrow">店长工作台</text>
        <text class="title">本店库存</text>
      </view>
      <button class="ghost-button" :loading="loading" :disabled="loading || !canRead" @click="refresh">
        刷新
      </button>
    </view>

    <view v-if="!canRead" class="state-card">
      <text class="state-title">本功能仅向店长开放</text>
      <text class="muted">还需具备后端库存权限和本店数据范围，本页不会扩大权限。</text>
    </view>

    <template v-else>
      <view v-if="overview" class="summary-grid">
        <view class="metric-card">
          <text class="metric-value">{{ overview.summary.itemCount }}</text>
          <text class="metric-label">在用物料</text>
        </view>
        <view class="metric-card warning">
          <text class="metric-value">{{ overview.summary.lowStockCount }}</text>
          <text class="metric-label">库存预警</text>
        </view>
        <view class="metric-card">
          <text class="metric-value">{{ overview.summary.pendingReceiptCount }}</text>
          <text class="metric-label">待确认收货</text>
        </view>
      </view>

      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="loading && !overview" class="state-card"><text>正在读取真实库存…</text></view>
      <view v-else-if="!items.length" class="state-card">
        <text class="state-title">暂无可查看物料</text>
        <text class="muted">库存数据由后端按当前门店范围返回。</text>
      </view>

      <view v-for="item in items" :key="item.id" class="item-card">
        <view class="item-main">
          <view>
            <text class="item-name">{{ item.name }}</text>
            <text class="muted">{{ item.spec || item.categoryName || item.code }}</text>
          </view>
          <view class="stock-value">
            <text class="stock-number">{{ quantity(item.storeStockQuantity) }}</text>
            <text class="muted">{{ item.unit || '单位' }}</text>
          </view>
        </view>
        <view v-if="item.alertText" class="alert" :class="{ critical: item.alertLevel === 'LOW' }">
          {{ item.alertText }}
        </view>
      </view>
    </template>

    <SafeActionBar v-if="canOrder" primary-text="去叫货" @primary="openRequisition" />
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx calc(160rpx + env(safe-area-inset-bottom)); background: #f4f6f2; color: #162019; }
.page-head, .item-main { display: flex; align-items: center; justify-content: space-between; gap: 20rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .item-name, .muted, .metric-value, .metric-label, .state-title { display: block; }
.eyebrow { color: #657168; font-size: 24rpx; letter-spacing: 2rpx; }
.title { margin-top: 6rpx; font-size: 44rpx; font-weight: 700; }
.summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14rpx; margin-bottom: 22rpx; }
.metric-card, .item-card, .state-card { border: 1px solid #dce2db; border-radius: 22rpx; background: #fff; box-shadow: 0 10rpx 28rpx rgba(27, 45, 32, .05); }
.metric-card { padding: 22rpx 12rpx; text-align: center; }
.metric-card.warning { background: #fff8e8; border-color: #f0d78f; }
.metric-value { font-size: 40rpx; font-weight: 700; }
.metric-label { margin-top: 4rpx; color: #657168; font-size: 22rpx; }
.item-card { margin-bottom: 18rpx; padding: 24rpx; }
.item-name { margin-bottom: 8rpx; font-size: 32rpx; font-weight: 650; }
.muted { color: #6b746d; font-size: 24rpx; line-height: 1.5; }
.stock-value { min-width: 130rpx; text-align: right; }
.stock-number { margin-right: 6rpx; font-size: 38rpx; font-weight: 750; color: #1f6741; }
.alert { margin-top: 18rpx; padding: 14rpx 18rpx; border-radius: 14rpx; background: #f4f6f2; color: #58635b; font-size: 24rpx; }
.alert.critical { background: #fff0ed; color: #a13a2f; }
.state-card { padding: 36rpx 28rpx; text-align: center; }
.state-title { margin-bottom: 8rpx; font-weight: 650; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 26rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.ghost-button, .primary-button { min-height: 88rpx; line-height: 88rpx; border-radius: 18rpx; font-size: 28rpx; }
.ghost-button { min-width: 136rpx; background: #fff; color: #264c36; border: 1px solid #cbd6cd; }
button::after { border: 0; }
</style>
