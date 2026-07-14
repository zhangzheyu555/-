<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { AlertTriangle, ArrowRight, ClipboardCheck, RefreshCw, Store, TrendingUp, WalletCards } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getProfitEntries, getProfitMonths, type ProfitEntry } from '../api/finance'
import { getBusinessTodos, type BusinessTodo } from '../api/todos'
import BossActionCard from '../components/boss/BossActionCard.vue'
import BusinessTodoEvidence from '../components/boss/BusinessTodoEvidence.vue'
import BossDoneReview from '../components/boss/BossDoneReview.vue'
import BossExamOverview from '../components/boss/BossExamOverview.vue'
import BossFocusCards from '../components/boss/BossFocusCards.vue'
import BossRiskSummary from '../components/boss/BossRiskSummary.vue'
import BossRoleProgress from '../components/boss/BossRoleProgress.vue'
import RevenueTrendChart from '../components/common/RevenueTrendChart.vue'
import PageHeader from '../components/common/PageHeader.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import TodoWorkflowDrawer from '../components/todo/TodoWorkflowDrawer.vue'
import { routeForSource, useBossStore, type BossRiskGroup, type BossRoleProgressItem } from '../stores/boss'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import type { RoleTodoItem } from '../api/todos'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const boss = useBossStore()
const examOverview = ref<InstanceType<typeof BossExamOverview> | null>(null)
const profitMonth = ref('')
const profitEntries = ref<ProfitEntry[]>([])
const profitTrend = ref<Array<{ label: string; value: number }>>([])
const profitError = ref('')
const trendError = ref('')
const profitLoading = ref(false)
const profitLoaded = ref(false)
const workflowTodos = ref<BusinessTodo[]>([])
const workflowError = ref('')
const workflowLoading = ref(false)
const workflowLoaded = ref(false)
const activeTodoId = ref('')
const activeSection = ref<'action' | 'review' | 'exam' | 'risk' | 'progress' | 'done'>('action')
const activeExamCount = ref(0)
const actionConfirmation = reactive({
  open: false,
  kind: 'resolve' as 'resolve' | 'close',
  title: '',
  confirmLabel: '确认',
  confirmVariant: 'primary' as 'primary' | 'danger',
  note: '',
  noteLabel: '',
  notePlaceholder: '',
  busy: false,
})
let pendingActionItem: RoleTodoItem | null = null
let profitRequestId = 0

const canAccess = computed(() => auth.hasPermission(PERMISSIONS.SYSTEM_DASHBOARD_READ))
const totalRevenue = computed(() => profitEntries.value.reduce((sum, item) => sum + Number(item.sales || 0), 0))
const totalNet = computed(() => profitEntries.value.reduce((sum, item) => sum + Number(item.net || 0), 0))
const netMargin = computed(() => totalRevenue.value ? totalNet.value / totalRevenue.value : 0)
const selectedStoreId = computed(() => queryValue(route.query.storeId))
const workflowTodosInScope = computed(() => workflowTodos.value.filter((item) => {
  const storeMatches = !selectedStoreId.value || !item.storeId || item.storeId === selectedStoreId.value
  const monthMatches = !profitMonth.value || !item.month || item.month === profitMonth.value
  return storeMatches && monthMatches
}))
const pendingReviewTodos = computed(() => workflowTodosInScope.value.filter((item) => item.status === 'PENDING_REVIEW'))
const riskStores = computed(() => {
  const names = new Set<string>()
  profitEntries.value
    .filter((item) => Number(item.net || 0) < 0 || Boolean(item.risk && item.risk !== '正常'))
    .forEach((item) => names.add(item.storeName || item.storeId))
  workflowTodosInScope.value
    .filter((item) => item.priority >= 2 && !['COMPLETED', 'REJECTED'].includes(item.status))
    .forEach((item) => {
      if (item.storeName || item.storeId) names.add(item.storeName || item.storeId || '')
    })
  return Array.from(names).filter(Boolean)
})
const riskMetricLoading = computed(() => profitLoading.value || workflowLoading.value)
const riskMetricError = computed(() => Boolean(profitError.value || workflowError.value))
const urgentActions = computed(() => boss.needsBossAction.slice(0, 4))
const urgentSummary = computed(() => boss.needsBossAction.length
  ? `${boss.needsBossAction.length} 项需要老板立即处理`
  : '仅显示需要老板立即处理的事项')
const supportTabs = computed(() => [
  { key: 'action' as const, label: '需要我处理', count: boss.needsBossAction.length },
  { key: 'review' as const, label: '待复核', count: pendingReviewTodos.value.length },
  { key: 'exam' as const, label: '培训考试', count: activeExamCount.value },
  { key: 'risk' as const, label: '风险门店', count: boss.highRiskReminders.length },
  { key: 'progress' as const, label: '岗位进度', count: boss.roleProgress.length },
  { key: 'done' as const, label: '已完成', count: boss.doneReview.length },
])

function isAuthError(err: unknown) {
  return err instanceof Error && (err.message.includes('登录已失效') || err.message.includes('请先登录') || err.message.includes('UNAUTHORIZED'))
}

async function refresh() {
  if (!canAccess.value) return

  const results = await Promise.allSettled([
    boss.load(),
    examOverview.value?.load() || Promise.resolve(),
    loadProfitSnapshot(),
    loadWorkflowTodos(),
  ])

  for (const result of results) {
    if (result.status === 'rejected' && isAuthError(result.reason)) {
      return
    }
  }

  await scrollToSection()
}

async function loadProfitSnapshot() {
  const requestId = ++profitRequestId
  profitError.value = ''
  trendError.value = ''
  profitLoading.value = true
  profitLoaded.value = false
  try {
    const requestedMonth = validMonth(queryValue(route.query.month))
    let months: string[] = []
    try {
      const monthResult = await getProfitMonths()
      months = (Array.isArray(monthResult) ? [...monthResult] : [])
        .filter((month): month is string => Boolean(validMonth(month)))
        .sort((left, right) => right.localeCompare(left))
    } catch (error) {
      if (!requestedMonth) throw error
      console.error('[Boss] profit months load failed', error)
      trendError.value = '趋势数据暂时无法获取'
    }

    let selectedMonth = requestedMonth
    if (!selectedMonth) {
      for (const candidate of months) {
        const candidateEntries = await getProfitEntries({ month: candidate, storeId: selectedStoreId.value || undefined })
        if (candidateEntries.some(hasRecordedBusinessData)) {
          selectedMonth = candidate
          break
        }
      }
    }
    if (requestId !== profitRequestId) return
    profitMonth.value = selectedMonth

    if (!selectedMonth) {
      profitEntries.value = []
      profitTrend.value = []
      profitLoaded.value = true
      return
    }

    const availableMonths = Array.from(new Set([...months, selectedMonth])).sort((left, right) => right.localeCompare(left))
    const trendMonths = availableMonths.filter((month) => month <= selectedMonth).slice(0, 7).reverse()
    const snapshotMonths = trendMonths.length ? trendMonths : [selectedMonth]
    const results = await Promise.allSettled(snapshotMonths.map(async (month) => ({
      month,
      entries: await getProfitEntries({ month, storeId: selectedStoreId.value || undefined }),
    })))
    if (requestId !== profitRequestId) return

    const snapshots = results
      .filter((result): result is PromiseFulfilledResult<{ month: string; entries: ProfitEntry[] }> => result.status === 'fulfilled')
      .map((result) => result.value)
    const selectedSnapshot = snapshots.find((item) => item.month === selectedMonth)

    if (selectedSnapshot) {
      profitEntries.value = selectedSnapshot.entries
      profitLoaded.value = true
    } else {
      const selectedFailure = results[snapshotMonths.indexOf(selectedMonth)]
      if (selectedFailure?.status === 'rejected') console.error('[Boss] current profit metric load failed', selectedFailure.reason)
      profitEntries.value = []
      profitError.value = '暂时无法获取'
    }

    profitTrend.value = snapshots.map((item) => ({
      label: `${Number(item.month.slice(5, 7))}月`,
      value: item.entries.reduce((sum, entry) => sum + Number(entry.sales || 0), 0),
    }))
    const failedTrendRequests = results.filter((result) => result.status === 'rejected')
    if (failedTrendRequests.length) {
      failedTrendRequests.forEach((result) => console.error('[Boss] profit trend month load failed', result.reason))
      trendError.value = snapshots.length ? '部分月份暂时无法获取' : '趋势数据暂时无法获取'
    }
  } catch (error) {
    if (isAuthError(error)) return
    console.error('[Boss] profit snapshot load failed', error)
    profitError.value = '暂时无法获取'
    trendError.value = '趋势数据暂时无法获取'
    profitEntries.value = []
    profitTrend.value = []
  } finally {
    if (requestId === profitRequestId) profitLoading.value = false
  }
}

async function loadWorkflowTodos() {
  workflowError.value = ''
  workflowLoading.value = true
  workflowLoaded.value = false
  try {
    workflowTodos.value = await getBusinessTodos()
    workflowLoaded.value = true
  } catch (error) {
    if (isAuthError(error)) return
    console.error('[Boss] business workflow load failed', error)
    workflowError.value = '待复核事项加载失败，请稍后重试'
    workflowTodos.value = []
  } finally {
    workflowLoading.value = false
  }
}

async function scrollToSection() {
  const section = typeof route.query.section === 'string' ? route.query.section : ''
  if (!section) return
  await nextTick()
  const target = document.getElementById(section)
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function openActionSource(item: RoleTodoItem) {
  const target = item.action?.targetRoute || item.action?.sourcePage || routeForSource(`${item.sourceModule || ''} ${item.dataSource || ''}`)
  void router.push(target || '/boss')
}

function openRiskSource(risk: BossRiskGroup) {
  void router.push(risk.targetRoute)
}

function openProgressSource(item: BossRoleProgressItem) {
  void router.push(item.targetRoute)
}

function openWorkflow(todo: BusinessTodo) {
  activeTodoId.value = todo.id
}

function closeWorkflow() {
  activeTodoId.value = ''
}

async function handleWorkflowUpdated() {
  const results = await Promise.allSettled([loadWorkflowTodos(), loadProfitSnapshot()])
  for (const result of results) {
    if (result.status === 'rejected' && isAuthError(result.reason)) return
  }
}

function openWorkflowSource(route: string) {
  closeWorkflow()
  void router.push(route)
}

function formatMoney(value: number) {
  if (value === 0) return '0'
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function queryValue(value: unknown) {
  if (Array.isArray(value)) return String(value[0] || '')
  return typeof value === 'string' ? value : ''
}

function validMonth(value: string) {
  return /^\d{4}-(0[1-9]|1[0-2])$/.test(value) ? value : ''
}

function hasRecordedBusinessData(entry: ProfitEntry) {
  return [
    entry.sales, entry.refund, entry.discount, entry.material, entry.packaging,
    entry.loss, entry.costOther, entry.rent, entry.labor, entry.utility,
    entry.property, entry.commission, entry.promo, entry.repair, entry.equip,
    entry.expOther,
  ].some((value) => Number(value || 0) !== 0)
}

function resolveAction(item: RoleTodoItem) {
  if (actionConfirmation.busy) return
  pendingActionItem = item
  Object.assign(actionConfirmation, {
    open: true,
    kind: 'resolve',
    title: '请输入处理说明',
    confirmLabel: '确认处理',
    confirmVariant: 'primary',
    note: '老板已确认处理',
    noteLabel: '处理说明',
    notePlaceholder: '说明处理结果',
  })
}

function closeAction(item: RoleTodoItem) {
  if (actionConfirmation.busy) return
  pendingActionItem = item
  Object.assign(actionConfirmation, {
    open: true,
    kind: 'close',
    title: '请输入关闭说明',
    confirmLabel: '确认关闭',
    confirmVariant: 'danger',
    note: '事情没有很大影响，已默认处理。',
    noteLabel: '关闭说明',
    notePlaceholder: '说明关闭该事项的原因',
  })
}

function cancelActionConfirmation() {
  if (actionConfirmation.busy) return
  resetActionConfirmation()
}

async function confirmBossAction() {
  if (!pendingActionItem || actionConfirmation.busy) return
  const item = pendingActionItem
  actionConfirmation.busy = true
  try {
    if (actionConfirmation.kind === 'resolve') {
      await boss.resolve(item.id, actionConfirmation.note || '老板已确认处理')
    } else {
      await boss.close(item.id, actionConfirmation.note || '事情没有很大影响，已默认处理。')
    }
    resetActionConfirmation()
  } catch {
    // boss store 已保留业务错误，弹窗保持打开以便重试。
  } finally {
    actionConfirmation.busy = false
  }
}

function resetActionConfirmation() {
  actionConfirmation.open = false
  actionConfirmation.note = ''
  pendingActionItem = null
}

watch(
  () => route.query.section,
  () => {
    void scrollToSection()
  },
)

watch(
  () => [route.query.month, route.query.storeId],
  () => {
    if (canAccess.value) void loadProfitSnapshot()
  },
)

watch(activeSection, async (section) => {
  if (section !== 'exam') return
  await nextTick()
  await examOverview.value?.load()
})

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel boss-page">
    <PageHeader :subtitle="profitMonth ? `${profitMonth} 经营数据` : '经营数据'">
      <template #actions>
        <button v-if="canAccess" class="ghost-button" type="button" :disabled="boss.loading" @click="refresh">
          <RefreshCw :size="16" />刷新数据
        </button>
      </template>
    </PageHeader>

    <div v-if="!canAccess" class="error-box">当前账号无权访问老板工作台。</div>

    <template v-else>
      <div v-if="boss.error" class="error-box">{{ boss.error }}</div>
      <div v-if="boss.actionMessage" class="success-box">{{ boss.actionMessage }}</div>
      <section class="boss-kpi-strip" aria-label="经营数据摘要">
        <div class="kpi-item">
          <span class="kpi-icon revenue"><TrendingUp :size="19" /></span>
          <span class="kpi-label">营业额</span>
          <span v-if="profitLoading" class="metric-skeleton" aria-label="营业额加载中" />
          <span v-else-if="profitError || !profitLoaded" class="kpi-unavailable">暂时无法获取</span>
          <b v-else class="kpi-value">{{ formatMoney(totalRevenue) }}</b>
          <small>{{ profitError ? '请稍后重试' : profitMonth || '暂无月份' }}</small>
        </div>
        <div class="kpi-item">
          <span class="kpi-icon profit"><WalletCards :size="19" /></span>
          <span class="kpi-label">净利润</span>
          <span v-if="profitLoading" class="metric-skeleton" aria-label="净利润加载中" />
          <span v-else-if="profitError || !profitLoaded" class="kpi-unavailable">暂时无法获取</span>
          <b v-else class="kpi-value profit-value" :class="{ negative: totalNet < 0 }">{{ formatMoney(totalNet) }}</b>
          <small>{{ profitError ? '请稍后重试' : `净利率 ${formatPercent(netMargin)}` }}</small>
        </div>
        <div class="kpi-item">
          <span class="kpi-icon review"><ClipboardCheck :size="19" /></span>
          <span class="kpi-label">待复核</span>
          <span v-if="workflowLoading" class="metric-skeleton" aria-label="待复核加载中" />
          <span v-else-if="workflowError || !workflowLoaded" class="kpi-unavailable">暂时无法获取</span>
          <b v-else class="kpi-value">{{ pendingReviewTodos.length }}</b>
          <small>{{ workflowError ? '请稍后重试' : '等待老板确认' }}</small>
        </div>
        <div class="kpi-item">
          <span class="kpi-icon risk"><AlertTriangle :size="19" /></span>
          <span class="kpi-label">风险提醒</span>
          <span v-if="riskMetricLoading" class="metric-skeleton" aria-label="风险提醒加载中" />
          <span v-else-if="riskMetricError || !profitLoaded || !workflowLoaded" class="kpi-unavailable">暂时无法获取</span>
          <b v-else class="kpi-value risk-value">{{ riskStores.length }}</b>
          <small>{{ riskMetricError ? '请稍后重试' : (riskStores.length ? riskStores.slice(0, 2).join('、') : '经营状态正常') }}</small>
        </div>
      </section>

        <section class="boss-primary-grid">
          <div class="boss-work-panel urgent-panel">
            <div class="boss-work-head">
              <div>
                <h3>紧急待办</h3>
                <span>{{ urgentSummary }}</span>
              </div>
            </div>
            <div v-if="!urgentActions.length" class="empty-state compact">当前没有需要立即处理的事项</div>
            <div v-else class="urgent-list">
              <article v-for="item in urgentActions" :key="item.id">
                <span class="urgent-marker" :class="item.priority >= 3 ? 'danger' : 'warning'">
                  <AlertTriangle v-if="item.priority >= 3" :size="17" />
                  <Store v-else :size="17" />
                </span>
                <div>
                  <b>{{ item.title }}</b>
                  <span>{{ item.summary || item.storeName || '请及时处理' }}</span>
                  <small>{{ item.storeName || item.brandName || '全部门店' }}{{ item.updatedAt ? ` · ${item.updatedAt.slice(0, 16).replace('T', ' ')}` : '' }}</small>
                </div>
                <button class="ghost-button" type="button" @click="openActionSource(item)">处理</button>
              </article>
            </div>
          </div>

          <div class="boss-work-panel trend-panel">
            <div class="boss-work-head">
              <div>
                <h3>近七月营业额趋势</h3>
                <span>按已录入的真实经营数据汇总</span>
              </div>
              <button class="text-button" type="button" @click="router.push('/profit')">查看详情 <ArrowRight :size="15" /></button>
            </div>
            <div v-if="profitLoading" class="trend-skeleton" aria-label="营业额趋势加载中" />
            <div v-else-if="trendError && !profitTrend.length" class="empty-state compact">{{ trendError }}</div>
            <template v-else>
              <RevenueTrendChart :points="profitTrend" />
              <div v-if="trendError" class="trend-note">{{ trendError }}</div>
            </template>
          </div>
        </section>

        <section class="boss-support-panel">
          <div class="boss-support-tabs" role="tablist" aria-label="工作台明细">
            <button
              v-for="tab in supportTabs"
              :key="tab.key"
              type="button"
              :class="{ active: activeSection === tab.key }"
              @click="activeSection = tab.key"
            >
              {{ tab.label }}<span>{{ tab.count }}</span>
            </button>
          </div>

          <section v-if="activeSection === 'action'" id="needs-action" class="boss-panel tab-panel">
            <div v-if="!boss.needsBossAction.length" class="empty-state compact support-empty">当前没有需要你处理的事项</div>
            <div v-else class="boss-list">
              <BossActionCard
                v-for="item in boss.needsBossAction"
                :key="item.id"
                :item="item"
                :actioning-id="boss.actioningId"
                @open="openActionSource"
                @resolve="resolveAction"
                @close="closeAction"
              />
            </div>
          </section>

          <section v-else-if="activeSection === 'review'" id="workflow-review" class="boss-panel tab-panel">
            <div v-if="workflowError" class="error-box compact-error">{{ workflowError }}</div>
            <div v-else-if="!pendingReviewTodos.length" class="empty-state compact">当前没有等待复核的经营事项</div>
            <div v-else class="workflow-review-list">
              <article v-for="item in pendingReviewTodos" :key="item.id">
                <BusinessTodoEvidence :todo="item" />
                <div>
                  <b>{{ item.title }}</b>
                  <span>{{ item.storeName || item.storeId || '全部门店' }}{{ item.month ? ` · ${item.month}` : '' }}</span>
                </div>
                <StatusBadge :label="item.statusLabel" tone="warn" />
                <button class="ghost-button" type="button" @click="openWorkflow(item)">复核</button>
              </article>
            </div>
          </section>

          <div v-else-if="activeSection === 'exam'" class="tab-panel">
            <BossExamOverview ref="examOverview" @summary-count="activeExamCount = $event" />
          </div>
          <div v-else-if="activeSection === 'risk'" id="risks" class="tab-panel">
            <BossRiskSummary :risks="boss.highRiskReminders" @open="openRiskSource" />
          </div>
          <div v-else-if="activeSection === 'progress'" class="progress-tab-content">
            <BossFocusCards :focus="boss.focus" />
            <BossRoleProgress id="progress" :items="boss.roleProgress" @open="openProgressSource" />
          </div>
          <div v-else id="done" class="tab-panel">
            <BossDoneReview :items="boss.doneReview" />
          </div>
        </section>
    </template>

    <TodoWorkflowDrawer
      v-if="activeTodoId"
      :todo-id="activeTodoId"
      @close="closeWorkflow"
      @updated="handleWorkflowUpdated"
      @open-source="openWorkflowSource"
    />
    <ActionConfirmDialog
      v-model="actionConfirmation.note"
      :open="actionConfirmation.open"
      :title="actionConfirmation.title"
      :confirm-label="actionConfirmation.confirmLabel"
      :confirm-variant="actionConfirmation.confirmVariant"
      :note-label="actionConfirmation.noteLabel"
      :note-placeholder="actionConfirmation.notePlaceholder"
      :busy="actionConfirmation.busy"
      @cancel="cancelActionConfirmation"
      @confirm="confirmBossAction"
    />
  </section>
</template>

<style scoped>
.boss-page {
  display: grid;
  gap: 18px;
}

.boss-business-overview {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}

.boss-business-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.boss-business-head h3 {
  margin: 0;
  font-size: 17px;
}

.boss-kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.boss-kpi-grid > div {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fafbfc;
}

.boss-kpi-grid span,
.risk-store-row span {
  color: var(--muted);
  font-size: 14px;
}

.boss-kpi-grid b {
  overflow: hidden;
  color: var(--ink);
  font-size: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.boss-kpi-grid .positive {
  color: var(--good);
}

.boss-kpi-grid .negative {
  color: var(--bad);
}

.risk-store-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}

.risk-store-row b {
  flex: 0 0 auto;
  font-size: 13px;
}

.risk-store-row span {
  line-height: 1.6;
}

.compact-error {
  padding: 9px 11px;
}

.boss-focus-summary {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(118, 189, 184, 0.22);
  border-left: 4px solid var(--primary);
  border-radius: 14px;
  background: var(--primary-soft);
}

.boss-focus-summary b {
  display: block;
  margin-bottom: 4px;
  color: var(--primary-dark);
  font-size: 14px;
  font-weight: 900;
}

.boss-focus-summary span {
  color: var(--ink);
  font-size: 16px;
  font-weight: 900;
  line-height: 1.55;
}

.boss-panel {
  display: grid;
  gap: 12px;
  scroll-margin-top: 16px;
}

.boss-panel-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.boss-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.boss-panel-head > span {
  color: var(--muted);
  font-size: 13px;
  font-weight: 800;
}

.boss-list {
  display: grid;
  gap: 10px;
}

.workflow-review-list {
  display: grid;
  gap: 8px;
}

.workflow-review-list article {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid #efddb9;
  border-radius: 7px;
  background: #fff;
}

.workflow-review-list article > div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.workflow-review-list article b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-review-list article span {
  color: var(--muted);
  font-size: 13px;
}

@media (max-width: 840px) {
  .boss-kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .boss-business-head {
    align-items: flex-start;
  }

  .workflow-review-list article {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .workflow-review-list article .ghost-button {
    grid-column: 1 / -1;
    justify-self: start;
  }
}

/* Final dashboard composition based on the approved desktop design. */
.boss-page {
  display: grid;
  flex: none !important;
  align-content: start;
  gap: 14px;
  min-height: auto !important;
  overflow: visible !important;
}

.boss-page-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--ds-secondary);
  font-size: 14px;
  font-weight: 500;
}

.boss-kpi-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  min-height: 114px;
  overflow: hidden;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background-color: #fff;
}

.boss-kpi-strip > .kpi-item {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  grid-template-rows: auto auto auto;
  column-gap: 12px;
  min-width: 0;
  min-height: 112px;
  padding: 16px 18px;
  border-right: 1px solid var(--ds-line);
  background-color: #fff;
}

.boss-kpi-strip > .kpi-item:last-child {
  border-right: 0;
}

.boss-kpi-strip .kpi-label {
  align-self: end;
  color: var(--ds-secondary);
  font-size: 14px;
  font-weight: 600;
}

.boss-kpi-strip .kpi-value,
.boss-kpi-strip .kpi-unavailable,
.boss-kpi-strip .metric-skeleton {
  grid-column: 2;
}

.boss-kpi-strip .kpi-value {
  overflow: hidden;
  margin-top: 2px;
  color: var(--ds-ink);
  font-size: 30px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.boss-kpi-strip .profit-value {
  color: #285f5c;
}

.boss-kpi-strip .risk-value {
  color: var(--ds-danger);
}

.boss-kpi-strip .kpi-unavailable {
  align-self: center;
  margin-top: 4px;
  color: var(--ds-secondary);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.4;
}

.metric-skeleton {
  width: min(150px, 82%);
  height: 31px;
  align-self: center;
  margin-top: 4px;
  border-radius: 5px;
  background-color: #e9efee;
  animation: metric-pulse 1.2s ease-in-out infinite alternate;
}

.boss-kpi-strip small {
  grid-column: 2;
  overflow: hidden;
  margin-top: 3px;
  color: var(--ds-muted);
  font-size: 13px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kpi-icon {
  display: inline-flex;
  grid-row: 1 / 4;
  width: 40px;
  height: 40px;
  align-self: center;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.kpi-icon.revenue,
.kpi-icon.review,
.kpi-icon.profit,
.kpi-icon.risk {
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.positive {
  color: var(--ds-success) !important;
}

.negative {
  color: var(--ds-danger) !important;
}

.boss-primary-grid {
  display: grid;
  grid-template-columns: minmax(0, .95fr) minmax(460px, 1.05fr);
  gap: 14px;
}

.boss-work-panel,
.boss-support-panel {
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.boss-work-panel {
  overflow: hidden;
}

.trend-skeleton {
  min-height: 220px;
  margin: 16px;
  border-radius: 6px;
  background-color: #edf2f1;
  animation: metric-pulse 1.2s ease-in-out infinite alternate;
}

.trend-note {
  padding: 0 16px 12px;
  color: var(--ds-muted);
  font-size: 13px;
}

@keyframes metric-pulse {
  from { opacity: .62; }
  to { opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .metric-skeleton,
  .trend-skeleton {
    animation: none;
  }
}

.boss-support-panel {
  min-height: 330px;
  overflow: visible;
}

.boss-work-head {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--ds-line);
}

.boss-work-head > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.boss-work-head h3,
.boss-panel-head h3 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 16px;
  font-weight: 700;
}

.boss-work-head span {
  overflow: hidden;
  color: var(--ds-muted);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-button {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  color: var(--ds-primary-hover);
  font-size: 14px;
  font-weight: 600;
}

.urgent-list {
  display: grid;
  padding: 0 16px;
}

.urgent-list article {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  min-height: 69px;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--ds-line);
}

.urgent-list article:last-child {
  border-bottom: 0;
}

.urgent-marker {
  display: inline-flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.urgent-marker.danger {
  background: var(--ds-danger-soft);
  color: var(--ds-danger);
}

.urgent-marker.warning {
  background: var(--ds-warning-soft);
  color: var(--ds-warning);
}

.urgent-list article > div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.urgent-list b,
.urgent-list span,
.urgent-list small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.urgent-list b {
  color: var(--ds-ink);
  font-size: 14px;
  font-weight: 600;
}

.urgent-list span {
  color: var(--ds-secondary);
  font-size: 14px;
  font-weight: 400;
}

.urgent-list small {
  color: var(--ds-muted);
  font-size: 13px;
  font-weight: 400;
}

.boss-support-tabs {
  display: flex;
  min-height: 44px;
  align-items: stretch;
  gap: 20px;
  padding: 0 16px;
  border-bottom: 1px solid var(--ds-line);
}

.boss-support-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 2px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--ds-secondary);
  font-size: 15px;
  font-weight: 600;
}

.boss-support-tabs button.active {
  border-bottom-color: var(--ds-primary);
  color: var(--ds-ink);
}

.boss-support-tabs button span {
  min-width: 20px;
  padding: 1px 6px;
  border-radius: 10px;
  background: #f1f2f4;
  color: var(--ds-secondary);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.tab-panel,
.progress-tab-content {
  min-height: 270px;
  align-content: start;
  padding: 16px;
}

.support-empty {
  display: grid;
  min-height: 220px;
  place-items: center;
  border: 0;
  background: transparent;
  font-size: 14px;
}

.boss-panel,
.progress-tab-content,
.boss-list {
  display: grid;
  gap: 12px;
}

.boss-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.boss-panel-head > span {
  color: var(--ds-muted);
  font-size: 13px;
}

.boss-support-panel :deep(.boss-panel-head) {
  display: none;
}

.boss-support-panel :deep(.boss-exam-title) {
  display: none;
}

.boss-support-panel :deep(.boss-exam-head) {
  justify-content: flex-end;
}

.boss-support-panel :deep(.boss-panel),
.boss-support-panel :deep(.exam-overview),
.boss-support-panel :deep(.boss-risk-summary),
.boss-support-panel :deep(.boss-role-progress),
.boss-support-panel :deep(.boss-done-review) {
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
</style>
