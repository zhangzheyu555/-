<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import {
  getMobileBossExamSummary,
  getMobileBossTodoDashboard,
  getMobileProfitDashboard,
  getMobileRoleTodos,
} from '@/api/business'
import { useContextStore, useSessionStore } from '@/stores'
import type {
  BossExamSummary,
  BossTodoDashboard,
  BossTodoRiskGroup,
  ProfitDashboard,
  RoleTodoItem,
} from '@/types/business'
import { todoStage } from '@/utils/todoStatus'

type DetailSection = 'action' | 'review' | 'exam' | 'risk' | 'progress' | 'done'

const session = useSessionStore()
const context = useContextStore()
const loading = ref(false)
const errorMessage = ref('')
const partialMessage = ref('')
const selectedMonth = ref('')
const selectedStoreId = ref('')
const activeSection = ref<DetailSection>('action')
const finance = ref<ProfitDashboard | null>(null)
const dashboard = ref<BossTodoDashboard | null>(null)
const exams = ref<BossExamSummary | null>(null)
const roleTodos = ref<RoleTodoItem[]>([])

const storeOptions = computed(() => [
  { id: '', name: '全部门店' },
  ...context.stores.map((store) => ({ id: store.id, name: store.name })),
])
const selectedStoreIndex = computed(() => Math.max(0, storeOptions.value.findIndex((store) => store.id === selectedStoreId.value)))
const monthOptions = computed(() => finance.value?.months || (selectedMonth.value ? [selectedMonth.value] : []))
const selectedMonthIndex = computed(() => Math.max(0, monthOptions.value.indexOf(selectedMonth.value)))
const pendingReviewTodos = computed(() => roleTodos.value.filter((item) => todoStage(item) === 'PENDING_REVIEW'))
const needsAction = computed(() => dashboard.value?.needsBossAction || [])
const riskGroups = computed(() => dashboard.value?.highRiskReminders || [])
const roleProgress = computed(() => dashboard.value?.roleProgress || [])
const doneReview = computed(() => dashboard.value?.doneReview || [])
const riskStoreCount = computed(() => Math.max(
  Number(finance.value?.summary.riskStoreCount || 0),
  Number(dashboard.value?.todayFocus.highRiskGroupCount || riskGroups.value.length),
))
const sectionTabs = computed(() => [
  { key: 'action' as const, label: '需要我处理', count: needsAction.value.length },
  { key: 'review' as const, label: '待复核', count: pendingReviewTodos.value.length },
  { key: 'exam' as const, label: '培训考试', count: Number(exams.value?.activeExamCount || 0) },
  { key: 'risk' as const, label: '风险门店', count: riskGroups.value.length },
  { key: 'progress' as const, label: '岗位进度', count: roleProgress.value.length },
  { key: 'done' as const, label: '已完成', count: doneReview.value.length },
])
const latestTrend = computed(() => (finance.value?.trend || []).slice(-7))

onMounted(() => { void refresh() })

async function refresh(): Promise<void> {
  if (loading.value) return
  loading.value = true
  errorMessage.value = ''
  partialMessage.value = ''
  try {
    if (!session.user && !await session.restore()) {
      uni.reLaunch({ url: '/pages/login/index' })
      return
    }
    if (session.user?.role !== 'BOSS') {
      errorMessage.value = '当前账号不是老板角色，无法读取老板工作台。'
      return
    }
    if (!context.stores.length && !context.loading) await context.load(session.user)
    const results = await Promise.allSettled([
      getMobileProfitDashboard(selectedMonth.value || undefined, selectedStoreId.value || undefined),
      getMobileBossTodoDashboard(),
      getMobileBossExamSummary(),
      getMobileRoleTodos('BOSS', true),
    ])
    const failed: string[] = []
    if (results[0].status === 'fulfilled') {
      finance.value = results[0].value
      if (!selectedMonth.value) selectedMonth.value = results[0].value.summary.month
    } else {
      failed.push('经营数据')
    }
    if (results[1].status === 'fulfilled') dashboard.value = results[1].value
    else failed.push('老板待办')
    if (results[2].status === 'fulfilled') exams.value = results[2].value
    else failed.push('培训考试')
    if (results[3].status === 'fulfilled') roleTodos.value = results[3].value.items || []
    else failed.push('待复核事项')
    if (failed.length) partialMessage.value = `${failed.join('、')}暂时无法更新，其他数据仍可正常查看。`
  } catch (error) {
    errorMessage.value = friendlyError(error)
  } finally {
    loading.value = false
  }
}

async function chooseMonth(event: { detail: { value: string | number } }): Promise<void> {
  selectedMonth.value = monthOptions.value[Number(event.detail.value)] || ''
  await refresh()
}

async function chooseStore(event: { detail: { value: string | number } }): Promise<void> {
  selectedStoreId.value = storeOptions.value[Number(event.detail.value)]?.id || ''
  await refresh()
}

function openTodo(todo: RoleTodoItem): void {
  uni.navigateTo({ url: `/pages/todo-detail/index?id=${encodeURIComponent(todo.id)}` })
}

function openAllTodos(): void {
  uni.switchTab({ url: '/pages/todo/index' })
}

function openProfit(): void {
  const query = [
    selectedMonth.value ? `month=${encodeURIComponent(selectedMonth.value)}` : '',
    selectedStoreId.value ? `storeId=${encodeURIComponent(selectedStoreId.value)}` : '',
  ].filter(Boolean).join('&')
  uni.navigateTo({ url: `/pkg-finance/profit/index${query ? `?${query}` : ''}` })
}

function openRisk(risk: BossTodoRiskGroup): void {
  openBusinessSource(risk.sourceModule)
}

function openProgress(sources: string[]): void {
  openBusinessSource(sources.join(' '))
}

function openBusinessSource(source: string): void {
  const normalized = String(source || '').toLowerCase()
  if (includes(normalized, ['expense', '报销'])) return navigate('/pkg-finance/expenses/index')
  if (includes(normalized, ['salary', '工资'])) return navigate('/pkg-finance/salary/index')
  if (includes(normalized, ['daily_loss', 'daily-loss', '报损'])) return navigate('/pkg-store/daily-loss/index')
  if (includes(normalized, ['finance', 'profit', '利润'])) return openProfit()
  if (includes(normalized, ['warehouse', '库存', '仓库', '叫货', '退货'])) return navigate('/pkg-warehouse/operations/index')
  if (includes(normalized, ['inspection', '巡检', '整改', '督导'])) return navigate('/pkg-inspection/inspection/index')
  if (includes(normalized, ['exam', '培训', '考试'])) return navigate('/pkg-learning/progress/index')
  if (includes(normalized, ['store', '门店', '店长'])) return navigate('/pkg-boss/stores/index')
  uni.switchTab({ url: '/pages/apps/index' })
}

function navigate(url: string): void {
  uni.navigateTo({ url })
}

function selectSection(section: DetailSection): void {
  activeSection.value = section
}

async function jumpToSection(section: DetailSection): Promise<void> {
  activeSection.value = section
  await nextTick()
  uni.pageScrollTo({ selector: '#boss-detail', duration: 240 })
}

function includes(source: string, keywords: string[]): boolean {
  return keywords.some((keyword) => source.includes(keyword.toLowerCase()))
}

function money(value: unknown): string {
  const amount = Number(value || 0)
  if (Math.abs(amount) >= 10_000) return `¥${(amount / 10_000).toFixed(1)}万`
  return `¥${amount.toFixed(0)}`
}

function percent(value: unknown): string {
  return `${Number(value || 0).toFixed(1)}%`
}

function sourceLabel(source: string): string {
  const value = String(source || '').toLowerCase()
  if (includes(value, ['finance', 'profit'])) return '经营利润'
  if (includes(value, ['expense'])) return '报销审核'
  if (includes(value, ['salary'])) return '员工工资'
  if (includes(value, ['warehouse', 'inventory', 'requisition', 'return'])) return '仓库履约'
  if (includes(value, ['inspection', 'rectification'])) return '巡检整改'
  if (includes(value, ['exam', 'training'])) return '培训考试'
  if (includes(value, ['store'])) return '门店经营'
  return source || '业务事项'
}

function riskLabel(risk: BossTodoRiskGroup): string {
  if (risk.highestRisk === 'RED' || Number(risk.highestPriority || 0) >= 3) return '严重风险'
  if (risk.highestRisk === 'ORANGE' || Number(risk.highestPriority || 0) >= 2) return '较高风险'
  return '风险提醒'
}

function shortTime(value?: string): string {
  if (!value) return '暂无截止时间'
  return value.slice(0, 16).replace('T', ' ')
}

function friendlyError(error: unknown): string {
  const status = Number((error as { status?: number })?.status || 0)
  if (status === 401) return '登录已过期，请重新登录。'
  if (status === 403) return '当前账号没有老板工作台查看权限。'
  return '老板工作台暂时无法加载，请检查网络后重试。'
}

defineExpose({ refresh })
</script>

<template>
  <view class="boss-dashboard">
    <view class="boss-topbar">
      <view>
        <text class="boss-topbar__eyebrow">老板（系统管理员）</text>
        <text class="boss-topbar__title">老板工作台</text>
        <text class="boss-topbar__copy">经营结果、风险提醒和岗位进度集中查看</text>
      </view>
      <button class="refresh-button" :loading="loading" :disabled="loading" @click="refresh">刷新</button>
    </view>

    <view class="scope-filters">
      <picker :range="monthOptions" :value="selectedMonthIndex" @change="chooseMonth">
        <view class="filter-control"><text>经营月份</text><b>{{ selectedMonth || '暂无月份' }}</b><text>切换 ›</text></view>
      </picker>
      <picker :range="storeOptions" range-key="name" :value="selectedStoreIndex" @change="chooseStore">
        <view class="filter-control"><text>查看范围</text><b>{{ storeOptions[selectedStoreIndex]?.name || '全部门店' }}</b><text>切换 ›</text></view>
      </picker>
    </view>

    <view v-if="errorMessage" class="state-card state-card--error">
      <text>{{ errorMessage }}</text>
      <button @click="refresh">重新加载</button>
    </view>
    <view v-else-if="loading && !finance && !dashboard" class="state-card">正在汇总全部门店经营数据…</view>
    <view v-if="partialMessage" class="partial-message">{{ partialMessage }}</view>

    <view class="kpi-grid">
      <button class="kpi-card kpi-card--primary" @click="openProfit">
        <text class="kpi-card__label">营业额</text>
        <text class="kpi-card__value">{{ money(finance?.summary.sales) }}</text>
        <text class="kpi-card__hint">{{ finance?.summary.month || selectedMonth || '本期经营数据' }}</text>
      </button>
      <button class="kpi-card" @click="openProfit">
        <text class="kpi-card__label">净利润</text>
        <text class="kpi-card__value" :class="{ negative: Number(finance?.summary.net || 0) < 0 }">{{ money(finance?.summary.net) }}</text>
        <text class="kpi-card__hint">净利率 {{ percent(finance?.summary.margin) }}</text>
      </button>
      <button class="kpi-card" @click="jumpToSection('review')">
        <text class="kpi-card__label">待复核</text>
        <text class="kpi-card__value">{{ pendingReviewTodos.length }}<text>项</text></text>
        <text class="kpi-card__hint">等待老板确认</text>
      </button>
      <button class="kpi-card kpi-card--risk" @click="jumpToSection('risk')">
        <text class="kpi-card__label">风险门店</text>
        <text class="kpi-card__value">{{ riskStoreCount }}<text>家</text></text>
        <text class="kpi-card__hint">{{ riskStoreCount ? '需要持续关注' : '经营状态正常' }}</text>
      </button>
    </view>

    <view class="focus-card">
      <view class="focus-card__head">
        <view>
          <text class="section-eyebrow">今天先处理</text>
          <text class="section-title">紧急待办</text>
        </view>
        <button class="text-button" @click="openAllTodos">全部待办</button>
      </view>
      <text class="focus-summary">{{ dashboard?.todayFocus.summary || '当前没有需要老板立即处理的事项。' }}</text>
      <view v-if="!needsAction.length" class="empty-inline">当前没有必须由老板拍板的事项</view>
      <button v-for="todo in needsAction.slice(0, 4)" :key="todo.id" class="action-row" @click="openTodo(todo)">
        <view>
          <text class="action-row__title">{{ todo.title }}</text>
          <text class="action-row__copy">{{ todo.summary || todo.storeName || '请及时处理' }}</text>
          <text class="action-row__meta">{{ todo.storeName || '全部门店' }} · {{ shortTime(todo.updatedAt || todo.dueAt) }}</text>
        </view>
        <text class="action-row__action">处理 ›</text>
      </button>
    </view>

    <view class="trend-card">
      <view class="section-head">
        <view><text class="section-eyebrow">经营走势</text><text class="section-title">近七月经营趋势</text></view>
        <button class="text-button" @click="openProfit">利润详情</button>
      </view>
      <view v-if="!latestTrend.length" class="empty-inline">当前范围暂无经营趋势数据</view>
      <view v-for="point in latestTrend" :key="point.month" class="trend-row">
        <text class="trend-row__month">{{ point.month }}</text>
        <view><text>营业额</text><b>{{ money(point.sales) }}</b></view>
        <view><text>净利润</text><b :class="{ negative: point.net < 0 }">{{ money(point.net) }}</b></view>
      </view>
    </view>

    <view id="boss-detail" class="detail-card">
      <scroll-view scroll-x class="section-scroll" :show-scrollbar="false">
        <view class="section-tabs">
          <button
            v-for="tab in sectionTabs"
            :key="tab.key"
            :class="{ active: activeSection === tab.key }"
            @click="selectSection(tab.key)"
          >
            {{ tab.label }}<text>{{ tab.count }}</text>
          </button>
        </view>
      </scroll-view>

      <view v-if="activeSection === 'action'" class="detail-content">
        <view v-if="!needsAction.length" class="empty-block">当前没有需要老板亲自处理的事项</view>
        <button v-for="todo in needsAction" :key="todo.id" class="detail-row" @click="openTodo(todo)">
          <view><text class="detail-row__title">{{ todo.title }}</text><text class="detail-row__copy">{{ todo.summary || '进入待办查看详情' }}</text></view>
          <text>处理 ›</text>
        </button>
      </view>

      <view v-else-if="activeSection === 'review'" class="detail-content">
        <view v-if="!pendingReviewTodos.length" class="empty-block">当前没有等待老板复核的事项</view>
        <button v-for="todo in pendingReviewTodos" :key="todo.id" class="detail-row" @click="openTodo(todo)">
          <view><text class="detail-row__title">{{ todo.title }}</text><text class="detail-row__copy">{{ todo.summary || todo.storeName || '进入待办查看复核证据' }}</text></view>
          <text>复核 ›</text>
        </button>
      </view>

      <view v-else-if="activeSection === 'exam'" class="detail-content">
        <view class="exam-grid">
          <view><text>进行中考试</text><b>{{ exams?.activeExamCount || 0 }}</b></view>
          <view><text>完成率</text><b>{{ percent(exams?.completionRate) }}</b></view>
          <view><text>通过率</text><b>{{ percent(exams?.passRate) }}</b></view>
          <view><text>逾期人数</text><b>{{ exams?.overdueCount || 0 }}</b></view>
        </view>
        <button class="wide-button" @click="navigate('/pkg-learning/progress/index')">查看培训进度</button>
      </view>

      <view v-else-if="activeSection === 'risk'" class="detail-content">
        <view v-if="!riskGroups.length" class="empty-block">当前没有高风险门店提醒</view>
        <button v-for="risk in riskGroups" :key="risk.groupKey" class="risk-row" @click="openRisk(risk)">
          <view class="risk-row__head"><text>{{ sourceLabel(risk.sourceModule) }} · {{ risk.ownerName || '岗位处理中' }}</text><text>{{ riskLabel(risk) }}</text></view>
          <text class="detail-row__copy">{{ risk.storeName || risk.topStores?.join('、') || '全部门店' }} · {{ risk.count }} 条提醒</text>
          <text class="detail-row__meta">最早截止 {{ shortTime(risk.earliestDueAt) }}</text>
        </button>
      </view>

      <view v-else-if="activeSection === 'progress'" class="detail-content">
        <view v-if="!roleProgress.length" class="empty-block">当前没有岗位处理中事项</view>
        <button v-for="item in roleProgress" :key="item.ownerName" class="progress-row" @click="openProgress(item.topSources || [])">
          <view><text class="detail-row__title">{{ item.ownerName }}</text><text class="detail-row__copy">{{ (item.topSources || []).map(sourceLabel).join('、') || '岗位日常事项' }}</text></view>
          <view class="progress-row__count"><b>{{ item.openCount }}</b><text>处理中</text><text v-if="item.riskCount">{{ item.riskCount }} 条风险</text></view>
        </button>
      </view>

      <view v-else class="detail-content">
        <view v-if="!doneReview.length" class="empty-block">当前没有已完成复盘</view>
        <view v-for="todo in doneReview" :key="todo.id" class="done-row">
          <view><text class="detail-row__title">{{ todo.title }}</text><text class="detail-row__copy">{{ todo.summary || '事项已完成' }}</text></view>
          <text class="detail-row__meta">{{ shortTime(todo.updatedAt || todo.occurredAt) }}</text>
        </view>
      </view>
    </view>

    <view class="quick-card">
      <view class="section-head"><view><text class="section-eyebrow">常用管理</text><text class="section-title">业务中心</text></view></view>
      <view class="quick-grid">
        <button @click="openProfit"><b>经营利润</b><text>门店利润与趋势</text></button>
        <button @click="navigate('/pkg-boss/stores/index')"><b>门店管理</b><text>资料、状态和范围</text></button>
        <button @click="navigate('/pkg-boss/users/index')"><b>账号权限</b><text>角色与逐项权限</text></button>
        <button @click="navigate('/pkg-boss/employees/index')"><b>员工档案</b><text>员工资料与账号</text></button>
        <button @click="navigate('/pkg-warehouse/operations/index')"><b>仓库中心</b><text>库存与履约处理</text></button>
        <button @click="navigate('/pkg-inspection/inspection/index')"><b>巡检整改</b><text>风险与整改闭环</text></button>
        <button @click="navigate('/pkg-finance/expenses/index')"><b>报销审核</b><text>报销记录与证据</text></button>
        <button @click="navigate('/pkg-boss/audit/index')"><b>操作日志</b><text>关键操作追溯</text></button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.boss-dashboard { display: flex; flex-direction: column; gap: 20rpx; color: #1f2b2a; }
.boss-topbar,.focus-card__head,.section-head,.risk-row__head,.progress-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.boss-topbar { align-items: flex-end; padding-top: 4rpx; }
.boss-topbar > view,.focus-card__head > view,.section-head > view { display: flex; min-width: 0; flex-direction: column; gap: 5rpx; }
.boss-topbar__eyebrow,.section-eyebrow { color: #6b8581; font-size: 21rpx; font-weight: 700; }
.boss-topbar__title { color: #1f2b2a; font-family: $mobile-font-display; font-size: 42rpx; font-weight: 850; line-height: 1.18; }
.boss-topbar__copy { color: #788b87; font-size: 22rpx; line-height: 1.5; }
.refresh-button { min-width: 88rpx; min-height: 66rpx; margin: 0; padding: 0 18rpx; color: #315b57; background: #fff; border: 1rpx solid #cfe0dc; border-radius: 12rpx; font-size: 23rpx; font-weight: 700; line-height: 64rpx; }
.refresh-button::after,.kpi-card::after,.action-row::after,.text-button::after,.section-tabs button::after,.detail-row::after,.risk-row::after,.progress-row::after,.wide-button::after,.quick-grid button::after { border: 0; }
.scope-filters { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12rpx; }
.filter-control { display: flex; min-height: 112rpx; padding: 16rpx 18rpx; flex-direction: column; justify-content: center; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; }
.filter-control > text:first-child { color: #7b908c; font-size: 20rpx; }
.filter-control b { overflow: hidden; margin-top: 5rpx; color: #253634; font-size: 26rpx; text-overflow: ellipsis; white-space: nowrap; }
.filter-control > text:last-child { margin-top: 4rpx; color: #27655f; font-size: 20rpx; }
.partial-message,.state-card { padding: 20rpx 22rpx; color: #8a5a34; background: #fff8ef; border: 1rpx solid #efdcc8; border-radius: 12rpx; font-size: 23rpx; line-height: 1.5; }
.state-card { color: #718581; text-align: center; background: #fff; border-color: #d9e6e3; }
.state-card--error { color: #a34c41; background: #fff4f1; border-color: #efd4ce; }
.state-card button { min-height: 62rpx; margin: 14rpx auto 0; padding: 0 22rpx; color: #fff; background: #27655f; font-size: 22rpx; line-height: 62rpx; }
.kpi-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14rpx; }
.kpi-card { display: flex; min-height: 164rpx; margin: 0; padding: 20rpx; flex-direction: column; align-items: flex-start; text-align: left; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; }
.kpi-card--primary { background: #27655f; border-color: #27655f; }
.kpi-card--risk { background: #fff8ef; border-color: #efdcc8; }
.kpi-card__label { color: #728783; font-size: 21rpx; font-weight: 700; }
.kpi-card--primary .kpi-card__label,.kpi-card--primary .kpi-card__hint,.kpi-card--primary .kpi-card__value { color: #fff; }
.kpi-card__value { display: block; margin-top: 10rpx; color: #1f2b2a; font-size: 38rpx; font-weight: 850; line-height: 1.1; }
.kpi-card__value text { margin-left: 4rpx; font-size: 20rpx; font-weight: 700; }
.kpi-card__hint { display: block; margin-top: auto; color: #7a8d89; font-size: 20rpx; }
.negative { color: #b84c40 !important; }
.focus-card,.trend-card,.detail-card,.quick-card { padding: 24rpx; background: #fff; border: 1rpx solid #d9e6e3; border-radius: 16rpx; box-shadow: 0 5rpx 14rpx rgba(46,79,73,.03); }
.section-title { color: #1f2b2a; font-size: 30rpx; font-weight: 820; }
.text-button { min-height: 62rpx; margin: 0; padding: 0 6rpx; color: #27655f; background: transparent; font-size: 22rpx; font-weight: 750; line-height: 62rpx; }
.focus-summary { display: block; margin-top: 8rpx; color: #6f827e; font-size: 22rpx; line-height: 1.55; }
.empty-inline,.empty-block { padding: 24rpx 0 8rpx; color: #80918e; font-size: 23rpx; text-align: center; }
.empty-block { padding: 40rpx 16rpx; }
.action-row,.detail-row,.risk-row,.progress-row { display: flex; width: 100%; min-height: 108rpx; margin: 0; padding: 18rpx 0; align-items: center; justify-content: space-between; gap: 16rpx; text-align: left; background: transparent; border-bottom: 1rpx solid #e5ecea; border-radius: 0; }
.action-row:last-child,.detail-row:last-child,.risk-row:last-child,.progress-row:last-child { border-bottom: 0; }
.action-row > view,.detail-row > view,.progress-row > view:first-child { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 5rpx; }
.action-row__title,.detail-row__title { overflow: hidden; color: #253633; font-size: 26rpx; font-weight: 780; text-overflow: ellipsis; white-space: nowrap; }
.action-row__copy,.detail-row__copy { display: block; color: #6f827e; font-size: 22rpx; line-height: 1.5; }
.action-row__meta,.detail-row__meta { display: block; color: #8a9996; font-size: 20rpx; }
.action-row__action,.detail-row > text { flex: 0 0 auto; color: #27655f; font-size: 22rpx; font-weight: 750; }
.trend-card { display: flex; flex-direction: column; gap: 4rpx; }
.trend-row { display: grid; grid-template-columns: 104rpx repeat(2,minmax(0,1fr)); gap: 12rpx; padding: 16rpx 0; align-items: center; border-bottom: 1rpx solid #e5ecea; }
.trend-row:last-child { border-bottom: 0; }
.trend-row__month { color: #586d69; font-size: 22rpx; font-weight: 750; }
.trend-row view { display: flex; min-width: 0; flex-direction: column; gap: 4rpx; }
.trend-row view text { color: #849490; font-size: 19rpx; }
.trend-row view b { overflow: hidden; color: #253633; font-size: 23rpx; text-overflow: ellipsis; white-space: nowrap; }
.section-scroll { width: 100%; margin: -4rpx 0 8rpx; white-space: nowrap; }
.section-tabs { display: inline-flex; gap: 10rpx; }
.section-tabs button { min-height: 66rpx; margin: 0; padding: 0 18rpx; color: #6d817d; background: #f5f8f7; border: 1rpx solid #e0e9e7; border-radius: 10rpx; font-size: 22rpx; line-height: 64rpx; }
.section-tabs button text { margin-left: 8rpx; color: #27655f; font-weight: 800; }
.section-tabs button.active { color: #fff; background: #27655f; border-color: #27655f; }
.section-tabs button.active text { color: #fff; }
.detail-content { padding-top: 8rpx; }
.risk-row { display: block; }
.risk-row__head text:first-child { color: #253633; font-size: 25rpx; font-weight: 780; }
.risk-row__head text:last-child { padding: 6rpx 10rpx; color: #9a5b20; background: #fff0df; border-radius: 8rpx; font-size: 20rpx; }
.risk-row .detail-row__copy,.risk-row .detail-row__meta { margin-top: 7rpx; }
.progress-row__count { display: flex; flex: 0 0 auto; flex-direction: column; align-items: flex-end; gap: 3rpx; }
.progress-row__count b { color: #27655f; font-size: 30rpx; }
.progress-row__count text { color: #7d8f8b; font-size: 19rpx; }
.progress-row__count text:last-child { color: #a2602c; }
.exam-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12rpx; }
.exam-grid view { display: flex; min-height: 100rpx; padding: 16rpx; flex-direction: column; justify-content: space-between; background: #f6f9f8; border-radius: 12rpx; }
.exam-grid text { color: #758984; font-size: 20rpx; }
.exam-grid b { color: #253633; font-size: 30rpx; }
.wide-button { min-height: 70rpx; margin: 16rpx 0 0; color: #27655f; background: #e7f4f1; border-radius: 10rpx; font-size: 23rpx; font-weight: 750; line-height: 70rpx; }
.done-row { padding: 18rpx 0; border-bottom: 1rpx solid #e5ecea; }
.done-row:last-child { border-bottom: 0; }
.done-row .detail-row__copy,.done-row .detail-row__meta { margin-top: 5rpx; }
.quick-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12rpx; margin-top: 16rpx; }
.quick-grid button { display: flex; min-height: 112rpx; margin: 0; padding: 17rpx; flex-direction: column; align-items: flex-start; text-align: left; background: #f6f9f8; border: 1rpx solid #e1e9e7; border-radius: 12rpx; }
.quick-grid b { color: #253633; font-size: 25rpx; }
.quick-grid text { margin-top: 6rpx; color: #7c8e8a; font-size: 20rpx; line-height: 1.4; }
</style>
