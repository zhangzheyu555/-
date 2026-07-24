<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileWarehouseOverview } from '../../api/business'
import SafeActionBar from '../../components/SafeActionBar.vue'
import { canUseMobileCapability, useSessionStore } from '../../stores'
import { MOBILE_PERMISSIONS, type WarehouseOverview } from '../../types/business'
import { isBoss } from '@/permissions'

const session = useSessionStore()
const overview = ref<WarehouseOverview | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const filter = ref<'RISK'|'LOW'|'EXPIRING'|'OK'|'ALL'>('RISK')

const hasCapability = computed(() => canUseMobileCapability(session.user, 'inventory'))
const canRead = computed(() => hasCapability.value && session.hasAnyPermission([MOBILE_PERMISSIONS.warehouseRead, 'warehouse.read']))
const canOrder = computed(() => canUseMobileCapability(session.user, 'requisition') && session.hasPermission(MOBILE_PERMISSIONS.requisitionCreate))
const items = computed(() => (overview.value?.items || []).filter((item) => item.active))
const filteredItems = computed(() => items.value.filter(item => filter.value === 'ALL' || filter.value === 'RISK' ? item.alertLevel !== 'OK' : item.alertLevel === filter.value))
const counts = computed(() => ({ risk: items.value.filter(item => item.alertLevel !== 'OK').length, low: items.value.filter(item => ['LOW','OUT'].includes(item.alertLevel)).length, expiring: items.value.filter(item => item.alertLevel === 'EXPIRING').length }))

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
  uni.navigateTo({ url: '/pkg-store/requisition/index' })
}

function openMovements() {
  if (!canRead.value) return
  uni.navigateTo({ url: '/pkg-store/movements/index' })
}

function openBusiness() {
  if (!isBoss(session.user) && (session.user?.role !== 'STORE_MANAGER' || !session.hasPermission('finance.profit.read'))) return
  uni.navigateTo({ url: '/pkg-store/business/index' })
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
function setFilter(value:string){filter.value=value as typeof filter.value}
function riskLabel(level:string){return({OUT:'缺货',LOW:'低库存',EXPIRING:'临期',OK:'正常'}as Record<string,string>)[level]||'待确认'}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view>
        <text class="eyebrow">店长工作台</text>
        <text class="title">本店库存</text>
      </view>
      <button class="ghost-button" :loading="loading" :disabled="loading || !canRead" @click="refresh">刷新</button>
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
      <view class="quick-links"><button v-if="(isBoss(session.user)||session.user?.role==='STORE_MANAGER')&&session.hasPermission('finance.profit.read')" @click="openBusiness">本店经营详情</button><button @click="openMovements">查看库存流水</button></view>

      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="loading && !overview" class="state-card"><text>正在读取真实库存…</text></view>
      <view v-else-if="!items.length" class="state-card">
        <text class="state-title">暂无可查看物料</text>
        <text class="muted">库存数据由后端按当前门店范围返回。</text>
      </view>

      <scroll-view scroll-x class="filters"><view class="filter-row"><button v-for="item in [{k:'RISK',l:`待处理 ${counts.risk}`},{k:'LOW',l:`低库存 ${counts.low}`},{k:'EXPIRING',l:`临期 ${counts.expiring}`},{k:'OK',l:'正常'},{k:'ALL',l:'全部'}]" :key="item.k" :class="{active:filter===item.k}" @click="setFilter(item.k)">{{item.l}}</button></view></scroll-view>
      <view v-if="!filteredItems.length" class="state-card">当前筛选条件下没有物料</view>
      <view v-for="item in filteredItems" :key="item.id" class="item-card">
        <view class="item-main">
          <view>
            <text class="item-name">{{ item.name }}</text>
            <text class="muted">{{ item.spec || item.categoryName || item.code }}</text>
          </view>
          <view class="stock-value">
            <text class="status" :class="`status-${item.alertLevel.toLowerCase()}`">{{riskLabel(item.alertLevel)}}</text>
            <text class="stock-number">{{ quantity(item.storeStockQuantity) }}</text>
            <text class="muted">{{ item.unit || '单位' }}</text>
          </view>
        </view>
        <view v-if="item.alertText" class="alert" :class="{ critical: item.alertLevel === 'LOW' }">
          {{ item.alertText }}
        </view>
        <text class="expiry">最近到期：{{item.nearestExpiryDate||'暂无'}} · 可用天数：{{item.daysAvailable===undefined?'—':quantity(item.daysAvailable)}} 天</text>
      </view>
    </template>

    <SafeActionBar v-if="canOrder" primary-text="去叫货" @primary="openRequisition" />
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx 24rpx calc(160rpx + env(safe-area-inset-bottom)); background: #f2f6f5; color: #1c1d22; }
.page-head, .item-main, .quick-links { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .item-name, .muted, .metric-value, .metric-label, .state-title { display: block; }
.eyebrow { color: #71807d; font-size: 24rpx; letter-spacing: 0; }
.title { margin-top: 6rpx; font-size: 38rpx; font-weight: 700; }
.summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14rpx; margin-bottom: 22rpx; }
.metric-card, .item-card, .state-card { border: 1px solid #d9e6e3; border-radius: 16rpx; background: #fff; box-shadow: 0 10rpx 28rpx rgba(37, 39, 45, .05); }
.metric-card { padding: 22rpx 12rpx; text-align: center; }
.metric-card.warning { background: #fff8e8; border-color: #f0d78f; }
.metric-value { font-size: 40rpx; font-weight: 700; }
.metric-label { margin-top: 4rpx; color: #71807d; font-size: 22rpx; }
.quick-links{margin-bottom:20rpx}.quick-links button{flex:1;min-height:80rpx;margin:0;background:#fff;color:#1f5752;border:1rpx solid #d9e6e3;font-size:25rpx}
.filters{margin-bottom:16rpx;white-space:nowrap}.filter-row{display:inline-flex;gap:10rpx}.filter-row button{min-height:64rpx;margin:0;padding:0 18rpx;background:#fff;color:#59606b;border:1rpx solid #d9e6e3;font-size:23rpx}.filter-row button.active{background:#e6f3f1;color:#1f5752;border-color:#9fc8c1}
.item-card { margin-bottom: 18rpx; padding: 24rpx; }
.item-name { margin-bottom: 8rpx; font-size: 32rpx; font-weight: 650; }
.muted { color: #71807d; font-size: 24rpx; line-height: 1.5; }
.stock-value { min-width: 130rpx; text-align: right; }
.status{display:inline-block;margin-bottom:6rpx;padding:5rpx 9rpx;border-radius:9rpx;font-size:21rpx}.status-out{background:#fff0ed;color:#963b30}.status-low{background:#fff4e8;color:#a35a18}.status-expiring{background:#fff8e8;color:#9a661b}.status-ok{background:#eaf5ed;color:#24663e}
.stock-number { margin-right: 6rpx; font-size: 38rpx; font-weight: 750; color: #27655f; }
.alert { margin-top: 18rpx; padding: 14rpx 18rpx; border-radius: 14rpx; background: #f2f6f5; color: #58635b; font-size: 24rpx; }
.alert.critical { background: #fff0ed; color: #a13a2f; }
.expiry{display:block;margin-top:12rpx;color:#71807d;font-size:23rpx}
.state-card { padding: 36rpx 28rpx; text-align: center; }
.state-title { margin-bottom: 8rpx; font-weight: 650; }
.message { margin-bottom: 18rpx; padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 26rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.ghost-button, .primary-button { min-height: 88rpx; line-height: 88rpx; border-radius: 16rpx; font-size: 28rpx; }
.ghost-button { min-width: 112rpx; margin:0; background: #fff; color: #1f5752; border: 1px solid #d9e6e3; }
button::after { border: 0; }
</style>
