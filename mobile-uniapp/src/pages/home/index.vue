<script setup lang="ts">
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app'
import StatePanel from '@/components/StatePanel.vue'
import {
  getMobileExamOverview,
  getMobileProfitEntries,
  getMobileProfitDashboard,
  getMobileRoleTodos,
  getMobileWarehouseOverview,
} from '@/api/business'
import { canUseMobileCapability, useContextStore, useMenuStore, useSessionStore } from '@/stores'
import type { ExamOverview, ProfitDashboard, ProfitEntry, RoleTodoItem, WarehouseOverview } from '@/types/business'

const session = useSessionStore()
const menu = useMenuStore()
const context = useContextStore()
const storeNames = computed(() => context.stores.map((store) => store.name))
const currentStoreLabel = computed(() => context.currentStore?.name || session.user?.boundStoreName || '请选择当前门店')
const currentStoreInfo = computed(() => context.currentStore)
const todayLabel = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}年${String(now.getMonth() + 1).padStart(2, '0')}月${String(now.getDate()).padStart(2, '0')}日`
})
const workspaceLoading = ref(false)
const workspaceError = ref('')
const todos = ref<RoleTodoItem[]>([])
const warehouse = ref<WarehouseOverview | null>(null)
const finance = ref<ProfitDashboard | null>(null)
const exams = ref<ExamOverview | null>(null)
const managerEntries = ref<ProfitEntry[]>([])
let workspaceRequestId = 0

onShow(initializeHome)
onPullDownRefresh(async () => {
  await initializeHome(true)
  uni.stopPullDownRefresh()
})

async function initializeHome(force = false): Promise<void> {
  if (!session.user && !await session.restore()) {
    if (!session.error) uni.reLaunch({ url: '/pages/login/index' })
    return
  }
  menu.rebuild(session.user)
  if (!context.stores.length && !context.loading) await context.load(session.user)
  if (force || (!todos.value.length && !workspaceLoading.value)) await loadWorkspace()
}

async function loadWorkspace(): Promise<void> {
  if (!session.user) return
  const requestId = ++workspaceRequestId
  const storeId = context.currentStoreId || undefined
  const warehouseId = context.currentStore?.supplyWarehouseId || undefined
  workspaceLoading.value = true
  workspaceError.value = ''
  const role = session.user.role
  try {
    const todoPromise = session.hasPermission('todo.read')
      ? getMobileRoleTodos(role)
      : Promise.resolve({ roleName: '', updatedAt: '', items: [] })
    const warehousePromise = canUseMobileCapability(session.user, 'inventory')
      || canUseMobileCapability(session.user, 'warehouse')
      ? getMobileWarehouseOverview(warehouseId)
      : Promise.resolve(null)
    const financePromise = canUseMobileCapability(session.user, 'summary')
      ? getMobileProfitDashboard(undefined, storeId)
      : Promise.resolve(null)
    const examPromise = canUseMobileCapability(session.user, 'exam')
      ? getMobileExamOverview()
      : Promise.resolve(null)
    const managerEntriesPromise = canUseMobileCapability(session.user, 'business')
      ? getMobileProfitEntries({ storeId })
      : Promise.resolve([])
    const [todoResult, warehouseResult, financeResult, examResult, entryResult] = await Promise.all([
      todoPromise,
      warehousePromise,
      financePromise,
      examPromise,
      managerEntriesPromise,
    ])
    if (requestId !== workspaceRequestId) return
    todos.value = todoResult.items || []
    warehouse.value = warehouseResult
    finance.value = financeResult
    exams.value = examResult
    managerEntries.value = entryResult
  } catch {
    if (requestId === workspaceRequestId) workspaceError.value = '部分工作台数据暂时无法加载，下拉可重新获取。'
  } finally {
    if (requestId === workspaceRequestId) workspaceLoading.value = false
  }
}

interface WorkspaceMetric {
  label: string
  value: string | number
  unit?: string
  hint?: string
  tone?: 'brand' | 'success' | 'info' | 'danger' | 'neutral'
}

const workspaceTitle = computed(() => {
  const role = session.user?.role || ''
  return {
    BOSS: '经营驾驶舱',
    FINANCE: '财务工作台',
    STORE_MANAGER: '门店工作台',
    WAREHOUSE: '仓库工作台',
    OPERATIONS: '运营工作台',
    EMPLOYEE: '学习中心',
  }[role] || '今日工作台'
})

const pendingTodos = computed(() => todos.value.filter((item) => !['COMPLETED', 'REJECTED'].includes(item.status)))
const latestManagerEntry = computed(() => [...managerEntries.value].sort((left, right) => right.month.localeCompare(left.month))[0] || null)

const metrics = computed<WorkspaceMetric[]>(() => {
  if (session.user?.role === 'STORE_MANAGER' && latestManagerEntry.value) {
    const entry = latestManagerEntry.value
    return [
      { label: '本月营业额', value: compactMoney(entry.sales), tone: 'brand' },
      { label: '实收收入', value: compactMoney(entry.income), tone: 'info' },
      { label: '成本合计', value: compactMoney(entry.costSum), tone: 'neutral' },
      { label: '净利润', value: compactMoney(entry.net), hint: `净利率 ${percent(entry.margin)}`, tone: entry.net < 0 ? 'danger' : 'success' },
    ]
  }
  if (finance.value) {
    const summary = finance.value.summary
    return [
      { label: '本期营收', value: compactMoney(summary.sales), tone: 'brand' },
      { label: '净利润', value: compactMoney(summary.net), tone: summary.net < 0 ? 'danger' : 'success' },
      { label: '风险门店', value: summary.riskStoreCount, unit: '家', tone: summary.riskStoreCount ? 'danger' : 'success' },
      { label: '今日待办', value: pendingTodos.value.length, unit: '项', tone: 'info' },
    ]
  }
  if (warehouse.value) {
    const summary = warehouse.value.summary
    return [
      { label: '库存商品', value: summary.itemCount, unit: '项', tone: 'brand' },
      { label: '库存预警', value: summary.lowStockCount, unit: '项', tone: summary.lowStockCount ? 'danger' : 'success' },
      { label: '待处理叫货', value: summary.pendingRequisitionCount, unit: '单', tone: 'info' },
      { label: '待确认收货', value: summary.pendingReceiptCount, unit: '单', tone: 'neutral' },
    ]
  }
  if (exams.value) {
    const assignments = exams.value.assignments || []
    return [
      { label: '待学习考试', value: assignments.filter((item) => !['COMPLETED'].includes(item.status)).length, unit: '项', tone: 'brand' },
      { label: '已经完成', value: assignments.filter((item) => item.status === 'COMPLETED').length, unit: '项', tone: 'success' },
      { label: '逾期任务', value: assignments.filter((item) => item.status === 'OVERDUE').length, unit: '项', tone: 'danger' },
      { label: '今日待办', value: pendingTodos.value.length, unit: '项', tone: 'info' },
    ]
  }
  return [
    { label: '今日待办', value: pendingTodos.value.length, unit: '项', tone: 'brand' },
    { label: '待复核', value: pendingTodos.value.filter((item) => item.status === 'PENDING_REVIEW').length, unit: '项', tone: 'info' },
    { label: '负责门店', value: context.stores.length || (session.user?.boundStoreId ? 1 : 0), unit: '家', tone: 'neutral' },
    { label: '权限应用', value: menu.groups.reduce((sum, group) => sum + group.items.length, 0), unit: '个', tone: 'success' },
  ]
})

function compactMoney(value: number): string {
  return String(value ?? '')
}

async function chooseStore(event: { detail: { value: string | number } }): Promise<void> {
  const index = Number(event.detail.value)
  const store = context.stores[index]
  if (!store || store.id === context.currentStoreId) return
  context.selectStore(store.id)
  todos.value = []
  warehouse.value = null
  finance.value = null
  exams.value = null
  managerEntries.value = []
  await loadWorkspace()
}

function openProfile(): void {
  uni.switchTab({ url: '/pages/profile/index' })
}

function openTodos(): void {
  uni.switchTab({ url: '/pages/todo/index' })
}
function openBusiness(): void { uni.navigateTo({ url: '/pkg-store/business/index' }) }
function money(value: number | undefined): string { return `¥${Number(value || 0).toFixed(2)}` }
function percent(value: number | undefined): string { return `${Number(value || 0).toFixed(1)}%` }
function storeStatusLabel(status: string | undefined): string {
  return String(status || '').toUpperCase() === 'ACTIVE' ? '营业中' : (status || '待确认')
}
</script>

<template>
  <view class="mobile-page home-page">
    <view class="topbar">
      <view class="topbar__date"><text class="topbar__calendar">▦</text><text>{{ todayLabel }}</text></view>
      <button class="profile-link" aria-label="打开我的账号" @click="openProfile">我的</button>
    </view>

    <view class="workspace-head">
      <view>
        <text class="workspace-head__eyebrow">{{ session.user?.roleLabel || workspaceTitle }}</text>
        <text class="workspace-head__title">{{ workspaceTitle }}</text>
        <text class="workspace-head__meta">{{ currentStoreLabel }} · {{ session.scopeLabel }}</text>
      </view>
      <button class="refresh-button" :loading="workspaceLoading" :disabled="workspaceLoading" @click="loadWorkspace">刷新</button>
    </view>

    <picker v-if="context.stores.length > 1" :range="storeNames" @change="chooseStore">
      <view class="store-selector tap-target">
        <view><text class="store-selector__label">当前门店</text><text class="store-selector__value">{{ currentStoreLabel }}</text></view>
        <text class="store-selector__action">切换 ›</text>
      </view>
    </picker>
    <view v-else-if="currentStoreLabel" class="store-selector store-selector--locked">
      <view><text class="store-selector__label">当前门店</text><text class="store-selector__value">{{ currentStoreLabel }}</text></view>
      <text class="store-selector__action">已按权限锁定</text>
    </view>

    <view class="metric-grid">
      <view
        v-for="metric in metrics"
        :key="metric.label"
        class="metric-card"
        :class="{ 'metric-card--featured': metric.tone === 'brand' }"
      >
        <text class="metric-card__label">{{ metric.label }}</text>
        <view class="metric-card__number"><text>{{ metric.value }}</text><text v-if="metric.unit" class="metric-card__unit">{{ metric.unit }}</text></view>
        <text class="metric-card__hint">{{ metric.hint || (metric.tone === 'brand' ? '本期汇总数据' : '实时同步') }}</text>
      </view>
    </view>

    <view v-if="session.user?.role === 'STORE_MANAGER' && currentStoreInfo" class="section-card store-profile-preview">
      <view class="section-card__head"><view><text class="section-card__eyebrow">我的门店</text><text class="section-card__title">门店基础资料</text></view></view>
      <view class="store-profile-preview__grid"><view><text>门店名称</text><b>{{ currentStoreInfo.name }}</b></view><view><text>门店编号</text><b>{{ currentStoreInfo.code || currentStoreInfo.id }}</b></view><view><text>所属品牌</text><b>{{ currentStoreInfo.brandName || '待补充' }}</b></view><view><text>门店状态</text><b>{{ storeStatusLabel(currentStoreInfo.status) }}</b></view><view><text>所在区域</text><b>{{ currentStoreInfo.area || '待补充' }}</b></view><view><text>配送仓库</text><b>{{ currentStoreInfo.supplyWarehouseName || '暂未配置' }}</b></view></view>
    </view>

    <view v-if="session.user?.role === 'STORE_MANAGER' && warehouse" class="section-card fulfilment-preview">
      <view class="section-card__head"><view><text class="section-card__eyebrow">库存履约</text><text class="section-card__title">本店库存与收货</text></view></view>
      <view class="fulfilment-preview__grid"><view><text>库存预警</text><b>{{ warehouse.summary.lowStockCount }} 项</b></view><view><text>待确认收货</text><b>{{ warehouse.summary.pendingReceiptCount }} 单</b></view><view><text>待处理叫货</text><b>{{ warehouse.summary.pendingRequisitionCount }} 单</b></view></view>
    </view>

    <view v-if="workspaceError" class="workspace-message">{{ workspaceError }}</view>

    <view v-if="pendingTodos.length" class="section-card todo-preview">
      <view class="section-card__head"><view><text class="section-card__eyebrow">今日优先事项</text><text class="section-card__title">待办处理</text></view><button class="text-button" @click="openTodos">全部待办</button></view>
      <button v-for="todo in pendingTodos.slice(0, 3)" :key="todo.id" class="todo-row" @click="openTodos"><view><text class="todo-row__title">{{ todo.title }}</text><text class="todo-row__meta">{{ todo.storeName || '权限范围内事项' }}</text></view><text class="todo-row__arrow">›</text></button>
      <button v-if="pendingTodos.length > 3" class="all-todos" @click="openTodos">查看全部 {{ pendingTodos.length }} 项待办</button>
    </view>

    <StatePanel
      v-if="session.error && !session.user"
      type="error"
      title="暂时无法连接工作台"
      :description="session.error"
      action-text="重新连接"
      @action="initializeHome"
    />

    <view class="apps-hint"><text>完整功能请进入底部“应用”查看。</text></view>

    <view v-if="session.user?.role === 'STORE_MANAGER' && latestManagerEntry" class="section-card business-preview">
      <view class="section-card__head"><view><text class="section-card__eyebrow">本店经营</text><text class="section-card__title">{{ latestManagerEntry.month }} 经营明细</text></view><button class="text-button" @click="openBusiness">查看全部</button></view>
      <view class="business-preview__grid"><view><text>实收收入</text><b>{{ money(latestManagerEntry.income) }}</b></view><view><text>成本合计</text><b>{{ money(latestManagerEntry.costSum) }}</b></view><view><text>费用合计</text><b>{{ money(latestManagerEntry.expenseSum) }}</b></view><view><text>净利润 · 净利率</text><b :class="{ negative: latestManagerEntry.net < 0 }">{{ money(latestManagerEntry.net) }} · {{ percent(latestManagerEntry.margin) }}</b></view></view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.home-page { display: flex; flex-direction: column; gap: 22rpx; color: #1f2b2a; }
.home-page :deep(.state-panel) { border-color: #dbe7e4; box-shadow: none; }
.topbar { display: flex; min-height: 68rpx; align-items: center; justify-content: space-between; }
.topbar__date { display: flex; align-items: center; gap: 12rpx; color: #58706d; font-size: 23rpx; font-weight: 700; }
.topbar__calendar { color: #27655f; font-size: 27rpx; }
.profile-link,.refresh-button { min-width: 88rpx; min-height: 68rpx; margin: 0; padding: 0 20rpx; color: #315b57; background: #ffffff; border: 1rpx solid #cfe0dc; border-radius: 12rpx; font-size: 23rpx; font-weight: 700; line-height: 66rpx; }
.profile-link::after,.refresh-button::after,.menu-card::after,.todo-row::after,.all-todos::after,.text-button::after { border: 0; }
.workspace-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20rpx; }
.workspace-head > view { display: flex; min-width: 0; flex-direction: column; gap: 7rpx; }
.workspace-head__eyebrow,.section-card__eyebrow { color: #6b8581; font-size: 22rpx; font-weight: 700; }
.workspace-head__title { color: #1f2b2a; font-family: $mobile-font-display; font-size: 42rpx; font-weight: 850; line-height: 1.2; }
.workspace-head__meta { overflow: hidden; color: #738783; font-size: 23rpx; text-overflow: ellipsis; white-space: nowrap; }
.store-selector { display: flex; min-height: 92rpx; padding: 17rpx 22rpx; align-items: center; justify-content: space-between; gap: 16rpx; background: #ffffff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; }
.store-selector > view { display: flex; min-width: 0; flex-direction: column; gap: 5rpx; }
.store-selector__label { color: #7b908c; font-size: 21rpx; }
.store-selector__value { overflow: hidden; color: #253634; font-size: 27rpx; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.store-selector__action { flex: 0 0 auto; color: #27655f; font-size: 23rpx; font-weight: 700; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16rpx; }
.metric-card { display: flex; min-height: 166rpx; padding: 22rpx; flex-direction: column; background: #ffffff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; }
.metric-card--featured { background: #edf7f5; border-color: #bddbd5; }
.metric-card__label { color: #728783; font-size: 22rpx; font-weight: 700; }
.metric-card--featured .metric-card__label,.metric-card--featured .metric-card__hint { color: #5b827a; }
.metric-card__number { display: flex; margin-top: 11rpx; align-items: baseline; gap: 5rpx; color: #1f2b2a; font-size: 39rpx; font-weight: 850; line-height: 1; }
.metric-card--featured .metric-card__number { color: #27655f; }
.metric-card__unit { color: inherit; font-size: 21rpx; }
.metric-card__hint { margin-top: auto; color: #718581; font-size: 20rpx; }
.workspace-message { padding: 18rpx 22rpx; color: #96603b; background: #fff7ed; border: 1rpx solid #f0ddc8; border-radius: 12rpx; font-size: 24rpx; }
.section-card { padding: 24rpx; background: #ffffff; border: 1rpx solid #d9e6e3; border-radius: 14rpx; box-shadow: 0 5rpx 14rpx rgba(46, 79, 73, .03); }
.store-profile-preview__grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14rpx; margin-top:14rpx; }
.store-profile-preview__grid view { display:flex; min-height:94rpx; padding:16rpx; flex-direction:column; justify-content:space-between; background:#f7faf9; border-radius:12rpx; }
.store-profile-preview__grid text { color:#718581; font-size:21rpx; }
.store-profile-preview__grid b { overflow:hidden; color:#263633; font-size:25rpx; line-height:1.35; text-overflow:ellipsis; white-space:nowrap; }
.business-preview__grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14rpx; margin-top:14rpx; }
.business-preview__grid view { display:flex; min-height:94rpx; padding:16rpx; flex-direction:column; justify-content:space-between; background:#f7faf9; border-radius:12rpx; }
.business-preview__grid text { color:#718581; font-size:21rpx; }
.business-preview__grid b { color:#263633; font-size:25rpx; line-height:1.35; }
.business-preview__grid b.negative { color:#b84c40; }
.fulfilment-preview__grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12rpx; margin-top:14rpx; }
.fulfilment-preview__grid view { display:flex; min-height:90rpx; padding:14rpx; flex-direction:column; justify-content:space-between; background:#f7faf9; border-radius:12rpx; }
.fulfilment-preview__grid text { color:#718581; font-size:20rpx; }.fulfilment-preview__grid b { color:#263633; font-size:24rpx; }
.section-card__head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin-bottom: 10rpx; }
.section-card__head > view { display: flex; flex-direction: column; gap: 4rpx; }
.section-card__title { color: #1f2b2a; font-size: 30rpx; font-weight: 800; }
.text-button { min-height: 62rpx; margin: 0; padding: 0 8rpx; color: #27655f; background: transparent; font-size: 23rpx; font-weight: 750; line-height: 62rpx; }
.todo-row { display: flex; width: 100%; min-height: 94rpx; margin: 0; padding: 17rpx 0; align-items: center; justify-content: space-between; text-align: left; background: transparent; border-bottom: 1rpx solid #e3ecea; }
.todo-row > view { display: flex; min-width: 0; flex-direction: column; gap: 5rpx; }
.todo-row__title { overflow: hidden; color: #263533; font-size: 26rpx; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.todo-row__meta { color: #80918e; font-size: 21rpx; }
.todo-row__arrow { color: #27655f; font-size: 34rpx; }
.all-todos { min-height: 70rpx; margin: 14rpx 0 0; padding: 0; color: #27655f; background: #e7f4f1; border-radius: 10rpx; font-size: 23rpx; font-weight: 700; line-height: 70rpx; }
.apps-hint { padding:20rpx 24rpx; color:#718581; background:#f8fbfa; border:1rpx dashed #cadeda; border-radius:14rpx; font-size:23rpx; text-align:center; }
</style>
