<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileBossExamSummary, getMobileProfitDashboard } from '../../api/business'
import { canUseMobileCapability, useContextStore, useSessionStore } from '../../stores'
import {
  MOBILE_PERMISSIONS,
  type BossExamSummary,
  type ProfitDashboard,
} from '../../types/business'

const session = useSessionStore()
const context = useContextStore()
const finance = ref<ProfitDashboard | null>(null)
const exams = ref<BossExamSummary | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const hasCapability = computed(() => canUseMobileCapability(session.user, 'summary'))
const canReadFinance = computed(() => hasCapability.value && session.hasPermission(MOBILE_PERMISSIONS.financeRead))
const canReadBoss = computed(() => hasCapability.value && session.user?.role === 'BOSS' && session.hasPermission(MOBILE_PERMISSIONS.bossDashboardRead))
const hasSummaryAccess = computed(() => canReadFinance.value || canReadBoss.value)

onShow(() => {
  if (!hasCapability.value) return denyAndReturn()
  void refresh()
})
onPullDownRefresh(async () => {
  await refresh()
  uni.stopPullDownRefresh()
})

async function refresh() {
  if (!hasSummaryAccess.value || loading.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    if (!context.stores.length && session.user) await context.load(session.user)
    const [financeResult, examResult] = await Promise.all([
      canReadFinance.value ? getMobileProfitDashboard(undefined, context.currentStoreId || undefined) : Promise.resolve(null),
      canReadBoss.value ? getMobileBossExamSummary() : Promise.resolve(null),
    ])
    finance.value = financeResult
    exams.value = examResult
  } catch (error) {
    errorMessage.value = friendlyError(error, '经营摘要暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function money(value: unknown) {
  const number = Number(value || 0)
  if (Math.abs(number) >= 10_000) return `¥${(number / 10_000).toFixed(1)}万`
  return `¥${number.toFixed(2)}`
}

function percent(value: unknown) {
  const number = Number(value || 0)
  return `${number.toFixed(1)}%`
}

function friendlyError(error: unknown, fallback: string) {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 403) return '当前账号无权查看该经营摘要。'
  if (status === 401) return '登录已过期，请重新登录。'
  return fallback
}

function denyAndReturn() {
  uni.showToast({ title: '手机经营摘要仅向老板和财务开放', icon: 'none' })
  setTimeout(() => uni.reLaunch({ url: '/pages/home/index' }), 500)
}
</script>

<template>
  <view class="page">
    <view class="page-head">
      <view><text class="eyebrow">财务与老板</text><text class="title">经营摘要</text></view>
      <button class="ghost-button" :loading="loading" :disabled="loading || !hasSummaryAccess" @click="refresh">刷新</button>
    </view>

    <view v-if="!hasSummaryAccess" class="state-card">手机经营摘要仅向老板和财务开放，并需具备后端只读权限。</view>
    <template v-else>
      <view v-if="errorMessage" class="message error">{{ errorMessage }}</view>
      <view v-if="loading && !finance && !exams" class="state-card">正在读取经营摘要…</view>

      <template v-if="finance">
        <view class="section-head"><text class="section-title">经营概览</text><text class="muted">{{ finance.summary.month }}</text></view>
        <view class="hero-card">
          <text class="hero-label">本期净利润</text>
          <text class="hero-value" :class="{ negative: finance.summary.net < 0 }">{{ money(finance.summary.net) }}</text>
          <text class="hero-caption">{{ finance.summary.storeCount }} 家门店 · 利润率 {{ percent(finance.summary.margin) }}</text>
        </view>
        <view class="metric-grid">
          <view class="metric-card"><text class="metric-value">{{ money(finance.summary.sales) }}</text><text class="metric-label">营业额</text></view>
          <view class="metric-card"><text class="metric-value">{{ money(finance.summary.costSum) }}</text><text class="metric-label">成本</text></view>
          <view class="metric-card"><text class="metric-value">{{ finance.summary.riskStoreCount }}</text><text class="metric-label">风险门店</text></view>
          <view class="metric-card"><text class="metric-value">{{ finance.summary.entryCount }}</text><text class="metric-label">已录入门店</text></view>
        </view>
      </template>

      <template v-if="exams">
        <view class="section-head"><text class="section-title">培训考试</text><text class="muted">老板只读</text></view>
        <view class="metric-grid">
          <view class="metric-card"><text class="metric-value">{{ exams.activeExamCount }}</text><text class="metric-label">进行中考试</text></view>
          <view class="metric-card"><text class="metric-value">{{ percent(exams.completionRate) }}</text><text class="metric-label">完成率</text></view>
          <view class="metric-card"><text class="metric-value">{{ percent(exams.passRate) }}</text><text class="metric-label">通过率</text></view>
          <view class="metric-card"><text class="metric-value">{{ exams.overdueCount }}</text><text class="metric-label">逾期人数</text></view>
        </view>
      </template>

      <view class="desktop-card">
        <text class="desktop-title">复杂操作请使用桌面端</text>
        <text class="desktop-copy">经营数据录入、月度批量导入、权限管理、复杂仓库配置和批量导出，一期手机端仅提供只读摘要。</text>
        <text class="desktop-copy">桌面正式前端仍为 frontend-vue，本入口不会替代桌面业务中心。</text>
      </view>
    </template>
  </view>
</template>

<style scoped lang="scss">
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx; background: #f4f6f2; color: #172019; }
.page-head, .section-head { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .section-title, .muted, .hero-label, .hero-value, .hero-caption, .metric-value, .metric-label, .desktop-title, .desktop-copy { display: block; }
.eyebrow { color: #657168; font-size: 24rpx; letter-spacing: 2rpx; }
.title { margin-top: 6rpx; font-size: 44rpx; font-weight: 700; }
.section-head { margin: 30rpx 2rpx 14rpx; }
.section-title { font-size: 30rpx; font-weight: 700; }
.muted { color: #6b746d; font-size: 23rpx; }
.hero-card { padding: 30rpx; border-radius: 24rpx; background: #173d2b; color: #fff; box-shadow: 0 14rpx 30rpx rgba(23,61,43,.15); }
.hero-label { color: #c7d9ce; font-size: 24rpx; }
.hero-value { margin-top: 12rpx; font-size: 58rpx; font-weight: 760; }
.hero-value.negative { color: #ffd2ca; }
.hero-caption { margin-top: 10rpx; color: #c7d9ce; font-size: 24rpx; }
.metric-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14rpx; }
.metric-card, .desktop-card, .state-card { padding: 24rpx; border: 1px solid #dce2db; border-radius: 22rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(27,45,32,.045); }
.metric-value { font-size: 34rpx; font-weight: 720; }
.metric-label { margin-top: 7rpx; color: #657168; font-size: 23rpx; }
.desktop-card { margin-top: 36rpx; border-color: #e7d5a6; background: #fff9e8; }
.desktop-title { margin-bottom: 12rpx; color: #6d5018; font-size: 29rpx; font-weight: 700; }
.desktop-copy { margin-top: 8rpx; color: #755d2d; font-size: 24rpx; line-height: 1.65; }
.ghost-button { min-width: 136rpx; min-height: 88rpx; line-height: 88rpx; border-radius: 18rpx; background: #fff; color: #264c36; border: 1px solid #cbd6cd; font-size: 27rpx; }
.message, .state-card { margin-bottom: 18rpx; }
.message { padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.state-card { text-align: center; color: #657168; }
button::after { border: 0; }
</style>
