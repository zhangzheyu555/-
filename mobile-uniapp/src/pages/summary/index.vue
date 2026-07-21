<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import { getMobileBossExamSummary, getMobileBossTodoDashboard, getMobileProfitDashboard, getMobileUserAccounts } from '../../api/business'
import { hasPermission } from '@/permissions'
import { canUseMobileCapability, useContextStore, useSessionStore } from '../../stores'
import { routeForTodo } from '@/utils/todoRoute'
import {
  MOBILE_PERMISSIONS,
  type BossExamSummary,
  type BossTodoDashboard,
  type BossTodoRiskGroup,
  type MobileUserAccount,
  type ProfitDashboard,
  type RoleTodoItem,
} from '../../types/business'

const session = useSessionStore()
const context = useContextStore()
const finance = ref<ProfitDashboard | null>(null)
const exams = ref<BossExamSummary | null>(null)
const bossDashboard = ref<BossTodoDashboard | null>(null)
const accounts = ref<MobileUserAccount[]>([])
const loading = ref(false)
const errorMessage = ref('')

const hasCapability = computed(() => canUseMobileCapability(session.user, 'summary'))
const canReadFinance = computed(() => hasCapability.value && session.hasPermission(MOBILE_PERMISSIONS.financeRead))
const canReadBoss = computed(() => hasCapability.value && session.user?.role === 'BOSS' && session.hasPermission(MOBILE_PERMISSIONS.bossDashboardRead))
const canReadAccounts = computed(() => canReadBoss.value && hasPermission(session.user, 'system.user.manage'))
const hasSummaryAccess = computed(() => canReadFinance.value || canReadBoss.value)
const enabledAccountCount = computed(() => accounts.value.filter((item) => item.enabled).length)
const accountRiskCount = computed(() => accounts.value.filter((item) => item.effectivePermissionStatus !== 'READY').length)

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
    const [financeResult, examResult, dashboardResult, accountResult] = await Promise.all([
      canReadFinance.value ? getMobileProfitDashboard(undefined, context.currentStoreId || undefined) : Promise.resolve(null),
      canReadBoss.value ? getMobileBossExamSummary() : Promise.resolve(null),
      canReadBoss.value ? getMobileBossTodoDashboard() : Promise.resolve(null),
      canReadAccounts.value ? getMobileUserAccounts() : Promise.resolve([]),
    ])
    finance.value = financeResult
    exams.value = examResult
    bossDashboard.value = dashboardResult
    accounts.value = accountResult
  } catch (error) {
    errorMessage.value = friendlyError(error, '经营摘要暂时无法加载，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function openTodo(item: RoleTodoItem) {
  uni.navigateTo({ url: `/pages/todo-detail/index?id=${encodeURIComponent(item.id)}` })
}

function openRisk(group: BossTodoRiskGroup) {
  const route = routeForTodo({
    id: group.groupKey,
    title: group.sourceModule,
    summary: group.highestRisk,
    status: 'RISK',
    priority: group.highestPriority,
    sourceModule: group.sourceModule,
    action: group.action,
  }, 'BOSS')
  uni.navigateTo({ url: route })
}

function permissionStatus(item: MobileUserAccount) {
  if (!item.enabled || item.effectivePermissionStatus === 'DISABLED') return '已停用'
  return item.effectivePermissionStatus === 'READY' ? '权限正常' : '需检查'
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

function openProfitDetail() {
  if (!canReadFinance.value) return
  const query = context.currentStoreId ? `?storeId=${encodeURIComponent(context.currentStoreId)}` : ''
  uni.navigateTo({ url: `/pkg-finance/profit/index${query}` })
}

function openFinanceWorkbench() {
  if (session.user?.role !== 'FINANCE') return
  uni.navigateTo({ url: '/pkg-finance/workbench/index' })
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

      <template v-if="bossDashboard">
        <view class="section-head"><text class="section-title">老板今日决策</text><text class="muted">{{ bossDashboard.updatedAt }}</text></view>
        <view class="boss-focus">
          <text class="focus-title">{{ bossDashboard.todayFocus.summary }}</text>
          <view class="focus-grid"><view><text>{{ bossDashboard.todayFocus.needsBossActionCount }}</text><text>需老板决策</text></view><view><text>{{ bossDashboard.todayFocus.highRiskCount }}</text><text>高风险事项</text></view><view><text>{{ bossDashboard.todayFocus.roleWorkCount }}</text><text>岗位处理中</text></view></view>
        </view>

        <view v-if="bossDashboard.needsBossAction.length" class="subsection">
          <view class="subsection-head"><text>需要我处理</text><text>{{ bossDashboard.needsBossAction.length }} 项</text></view>
          <button v-for="item in bossDashboard.needsBossAction.slice(0, 12)" :key="item.id" class="list-card" @click="openTodo(item)"><view><text class="list-title">{{ item.title }}</text><text class="list-copy">{{ item.storeName || '全公司' }} · {{ item.summary }}</text></view><text class="risk-level">P{{ item.priority }}</text></button>
        </view>

        <view v-if="bossDashboard.highRiskReminders.length" class="subsection">
          <view class="subsection-head"><text>跨模块风险</text><text>{{ bossDashboard.highRiskReminders.length }} 组</text></view>
          <button v-for="group in bossDashboard.highRiskReminders.slice(0, 16)" :key="group.groupKey" class="list-card" @click="openRisk(group)"><view><text class="list-title">{{ group.sourceModule }} · {{ group.storeName }}</text><text class="list-copy">{{ group.ownerName }}负责 · {{ group.count }} 项 · {{ group.highestRisk }}</text><text v-if="group.earliestDueAt" class="list-copy">最早截止：{{ group.earliestDueAt }}</text></view><text class="risk-level">进入</text></button>
        </view>

        <view v-if="bossDashboard.roleProgress.length" class="subsection">
          <view class="subsection-head"><text>岗位处理进度</text><text>服务端汇总</text></view>
          <view v-for="item in bossDashboard.roleProgress" :key="item.ownerName" class="progress-row"><view><text class="list-title">{{ item.ownerName }}</text><text class="list-copy">{{ item.topSources.join('、') || '暂无来源' }}</text></view><view class="progress-count"><text>{{ item.openCount }}</text><text>待处理</text></view><view class="progress-count risk"><text>{{ item.riskCount }}</text><text>风险</text></view></view>
        </view>
      </template>

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
        <button class="detail-button" @click="openProfitDetail">查看利润明细与趋势</button>
        <button v-if="session.user?.role==='FINANCE'" class="detail-button secondary" @click="openFinanceWorkbench">进入财务核对工作台</button>
      </template>

      <template v-if="exams">
        <view class="section-head"><text class="section-title">培训考试</text><text class="muted">老板只读</text></view>
        <view class="metric-grid">
          <view class="metric-card"><text class="metric-value">{{ exams.activeExamCount }}</text><text class="metric-label">进行中考试</text></view>
          <view class="metric-card"><text class="metric-value">{{ percent(exams.completionRate) }}</text><text class="metric-label">完成率</text></view>
          <view class="metric-card"><text class="metric-value">{{ percent(exams.passRate) }}</text><text class="metric-label">通过率</text></view>
          <view class="metric-card"><text class="metric-value">{{ exams.overdueCount }}</text><text class="metric-label">逾期人数</text></view>
        </view>
        <view v-if="exams.riskStores?.length" class="subsection">
          <view class="subsection-head"><text>考试风险门店</text><text>{{ exams.riskStores.length }} 家</text></view>
          <view v-for="store in exams.riskStores" :key="store.storeId" class="exam-risk"><view><text class="list-title">{{ store.storeName }}</text><text class="list-copy">{{ store.risks.join('、') }}</text></view><view class="exam-numbers"><text>完成 {{ percent(store.completionRate) }}</text><text>通过 {{ percent(store.passRate) }}</text><text>逾期 {{ store.overdueCount }} 人</text></view></view>
        </view>
      </template>

      <template v-if="canReadAccounts">
        <view class="section-head"><text class="section-title">账号授权健康</text><text class="muted">只读摘要</text></view>
        <view class="metric-grid">
          <view class="metric-card"><text class="metric-value">{{ accounts.length }}</text><text class="metric-label">账号总数</text></view>
          <view class="metric-card"><text class="metric-value">{{ enabledAccountCount }}</text><text class="metric-label">启用账号</text></view>
          <view class="metric-card"><text class="metric-value">{{ accounts.length-enabledAccountCount }}</text><text class="metric-label">停用账号</text></view>
          <view class="metric-card"><text class="metric-value" :class="{ negative: accountRiskCount>0 }">{{ accountRiskCount }}</text><text class="metric-label">授权需检查</text></view>
        </view>
        <view class="subsection">
          <view v-for="item in accounts.slice(0, 80)" :key="item.id" class="account-row"><view><text class="list-title">{{ item.displayName }} · {{ item.roleLabel }}</text><text class="list-copy">{{ item.storeId || '公司范围' }} · {{ item.effectivePermissionMessage }}</text></view><text class="account-status" :class="{ healthy: item.enabled && item.effectivePermissionStatus==='READY' }">{{ permissionStatus(item) }}</text></view>
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
.page { min-height: 100vh; box-sizing: border-box; padding: 24rpx; background: #f2f6f5; color: #1c1d22; }
.page-head, .section-head, .subsection-head, .list-card, .progress-row, .exam-risk, .account-row, .exam-numbers { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.page-head { margin-bottom: 24rpx; }
.eyebrow, .title, .section-title, .muted, .hero-label, .hero-value, .hero-caption, .metric-value, .metric-label, .desktop-title, .desktop-copy { display: block; }
.eyebrow { color: #71807d; font-size: 24rpx; letter-spacing: 0; }
.title { margin-top: 6rpx; font-size: 38rpx; font-weight: 700; }
.section-head { margin: 30rpx 2rpx 14rpx; }
.section-title { font-size: 30rpx; font-weight: 700; }
.muted { color: #71807d; font-size: 23rpx; }
.hero-card { padding: 30rpx; border-radius: 16rpx; background: #27655f; color: #fff; box-shadow: 0 14rpx 30rpx rgba(47,37,33,.15); }
.hero-label { color: #d9eeea; font-size: 24rpx; }
.hero-value { margin-top: 12rpx; font-size: 58rpx; font-weight: 760; }
.hero-value.negative { color: #ffd2ca; }
.hero-caption { margin-top: 10rpx; color: #d9eeea; font-size: 24rpx; }
.metric-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 14rpx; }
.metric-card, .desktop-card, .state-card { padding: 24rpx; border: 1px solid #d9e6e3; border-radius: 16rpx; background: #fff; box-shadow: 0 8rpx 24rpx rgba(37,39,45,.045); }
.metric-value { font-size: 34rpx; font-weight: 720; }
.metric-label { margin-top: 7rpx; color: #71807d; font-size: 23rpx; }
.metric-value.negative { color: #963b30; }
.boss-focus { padding: 26rpx; border-radius: 16rpx; background: #2f6757; color: #fff; }
.focus-title { display: block; font-size: 27rpx; font-weight: 700; line-height: 1.55; }
.focus-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12rpx; margin-top: 20rpx; }
.focus-grid view { padding: 16rpx 8rpx; text-align: center; border-radius: 12rpx; background: rgba(255,255,255,.11); }
.focus-grid text { display: block; }
.focus-grid text:first-child { font-size: 34rpx; font-weight: 750; }
.focus-grid text:last-child { margin-top: 5rpx; color: #dcece6; font-size: 20rpx; }
.subsection { margin-top: 18rpx; }
.subsection-head { margin: 26rpx 2rpx 12rpx; font-size: 27rpx; font-weight: 700; }
.subsection-head text:last-child { color: #71807d; font-size: 22rpx; font-weight: 400; }
.list-card, .progress-row, .exam-risk, .account-row { width: 100%; box-sizing: border-box; margin: 0 0 12rpx; padding: 20rpx 22rpx; text-align: left; border: 1px solid #d9e6e3; border-radius: 14rpx; background: #fff; }
.list-card::after { border: 0; }
.list-card>view, .progress-row>view:first-child, .exam-risk>view:first-child, .account-row>view:first-child { min-width: 0; flex: 1; }
.list-title, .list-copy { display: block; }
.list-title { font-size: 26rpx; font-weight: 700; line-height: 1.45; }
.list-copy { margin-top: 7rpx; color: #68707c; font-size: 22rpx; line-height: 1.5; }
.risk-level { flex-shrink: 0; color: #1f5752; font-size: 23rpx; font-weight: 700; }
.progress-count { flex-shrink: 0; text-align: center; }
.progress-count text { display: block; }
.progress-count text:first-child { font-size: 30rpx; font-weight: 750; }
.progress-count text:last-child { color: #71807d; font-size: 20rpx; }
.progress-count.risk text:first-child { color: #963b30; }
.exam-numbers { flex-shrink: 0; flex-direction: column; align-items: flex-end; gap: 5rpx; color: #59606b; font-size: 21rpx; }
.account-status { flex-shrink: 0; padding: 7rpx 11rpx; border-radius: 10rpx; background: #fff0ed; color: #963b30; font-size: 21rpx; }
.account-status.healthy { background: #eaf5ed; color: #24663e; }
.desktop-card { margin-top: 36rpx; border-color: #e7d5a6; background: #fff9e8; }
.detail-button{min-height:88rpx;margin:18rpx 0 0;background:#e6f3f1;color:#1f5752;font-size:27rpx}
.detail-button.secondary{margin-top:12rpx;background:#f7faf9;color:#4e5966}
.desktop-title { margin-bottom: 12rpx; color: #6d5018; font-size: 29rpx; font-weight: 700; }
.desktop-copy { margin-top: 8rpx; color: #755d2d; font-size: 24rpx; line-height: 1.65; }
.ghost-button { min-width: 136rpx; min-height: 88rpx; line-height: 88rpx; border-radius: 16rpx; background: #fff; color: #1f5752; border: 1px solid #d9e6e3; font-size: 27rpx; }
.message, .state-card { margin-bottom: 18rpx; }
.message { padding: 18rpx 20rpx; border-radius: 14rpx; font-size: 25rpx; }
.message.error { background: #fff0ed; color: #963b30; }
.state-card { text-align: center; color: #71807d; }
button::after { border: 0; }
</style>
