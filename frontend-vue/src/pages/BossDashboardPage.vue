<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ClipboardList, RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getProfitEntries, getProfitMonths, type ProfitEntry } from '../api/finance'
import { getBusinessTodos, type BusinessTodo } from '../api/todos'
import BossActionCard from '../components/boss/BossActionCard.vue'
import BossDoneReview from '../components/boss/BossDoneReview.vue'
import BossExamOverview from '../components/boss/BossExamOverview.vue'
import BossFocusCards from '../components/boss/BossFocusCards.vue'
import BossRiskSummary from '../components/boss/BossRiskSummary.vue'
import BossRoleProgress from '../components/boss/BossRoleProgress.vue'
import StatusBadge from '../components/common/StatusBadge.vue'
import TodoWorkflowDrawer from '../components/todo/TodoWorkflowDrawer.vue'
import { routeForSource, useBossStore, type BossRiskGroup, type BossRoleProgressItem } from '../stores/boss'
import { useAuthStore } from '../stores/auth'
import type { RoleTodoItem } from '../api/todos'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const boss = useBossStore()
const examOverview = ref<InstanceType<typeof BossExamOverview> | null>(null)
const profitMonth = ref('')
const profitEntries = ref<ProfitEntry[]>([])
const profitError = ref('')
const profitLoading = ref(false)
const workflowTodos = ref<BusinessTodo[]>([])
const workflowError = ref('')
const workflowLoading = ref(false)
const activeTodoId = ref('')

const canAccess = computed(() => ['ADMIN', 'BOSS', 'OWNER'].includes(auth.role))
const totalRevenue = computed(() => profitEntries.value.reduce((sum, item) => sum + Number(item.sales || 0), 0))
const totalNet = computed(() => profitEntries.value.reduce((sum, item) => sum + Number(item.net || 0), 0))
const netMargin = computed(() => totalRevenue.value ? totalNet.value / totalRevenue.value : 0)
const pendingReviewTodos = computed(() => workflowTodos.value.filter((item) => item.status === 'PENDING_REVIEW'))
const riskStores = computed(() => {
  const names = new Set<string>()
  profitEntries.value
    .filter((item) => Number(item.net || 0) < 0 || Boolean(item.risk && item.risk !== '正常'))
    .forEach((item) => names.add(item.storeName || item.storeId))
  workflowTodos.value
    .filter((item) => item.priority >= 2 && !['COMPLETED', 'REJECTED'].includes(item.status))
    .forEach((item) => {
      if (item.storeName || item.storeId) names.add(item.storeName || item.storeId || '')
    })
  return Array.from(names).filter(Boolean)
})

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
  profitError.value = ''
  profitLoading.value = true
  try {
    const months = await getProfitMonths()
    profitMonth.value = [...months].sort((left, right) => right.localeCompare(left))[0] || ''
    profitEntries.value = profitMonth.value ? await getProfitEntries({ month: profitMonth.value }) : []
  } catch (error) {
    if (isAuthError(error)) return
    console.error('[Boss] profit snapshot load failed', error)
    profitError.value = '经营指标加载失败，请稍后重试'
    profitEntries.value = []
  } finally {
    profitLoading.value = false
  }
}

async function loadWorkflowTodos() {
  workflowError.value = ''
  workflowLoading.value = true
  try {
    workflowTodos.value = await getBusinessTodos()
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
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

async function resolveAction(item: RoleTodoItem) {
  const note = window.prompt('请输入处理说明', '老板已确认处理')
  if (note === null) return
  await boss.resolve(item.id, note || '老板已确认处理')
}

async function closeAction(item: RoleTodoItem) {
  const note = window.prompt('请输入关闭说明', '事情没有很大影响，已默认处理。')
  if (note === null) return
  await boss.close(item.id, note || '事情没有很大影响，已默认处理。')
}

watch(
  () => route.query.section,
  () => {
    void scrollToSection()
  },
)

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel boss-page">
    <div class="page-head">
      <div>
        <h2>今日待办</h2>
      </div>
      <button v-if="canAccess" class="ghost-button" type="button" :disabled="boss.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="!canAccess" class="error-box">当前账号无权访问老板今日待办。</div>

    <template v-else>
      <div v-if="boss.error" class="error-box">{{ boss.error }}</div>
      <div v-if="boss.actionMessage" class="success-box">{{ boss.actionMessage }}</div>
      <div v-if="boss.loading && !boss.needsBossAction.length" class="empty-state">正在读取今日待办...</div>

      <template v-else>
        <section class="boss-business-overview">
          <div class="boss-business-head">
            <h3>{{ profitMonth || '当前月份' }} 经营概况</h3>
            <button class="ghost-button" type="button" @click="router.push('/profit')">查看利润概览</button>
          </div>
          <div v-if="profitError" class="error-box compact-error">{{ profitError }}</div>
          <div v-else class="boss-kpi-grid">
            <div>
              <span>营业额</span>
              <b>{{ formatMoney(totalRevenue) }}</b>
            </div>
            <div>
              <span>净利润</span>
              <b :class="totalNet < 0 ? 'negative' : 'positive'">{{ formatMoney(totalNet) }}</b>
            </div>
            <div>
              <span>净利率</span>
              <b :class="netMargin < 0 ? 'negative' : ''">{{ formatPercent(netMargin) }}</b>
            </div>
            <div>
              <span>待复核</span>
              <b>{{ pendingReviewTodos.length }}</b>
            </div>
          </div>
          <div class="risk-store-row">
            <b>风险门店</b>
            <span v-if="riskStores.length">{{ riskStores.slice(0, 6).join('、') }}{{ riskStores.length > 6 ? ` 等 ${riskStores.length} 家` : '' }}</span>
            <span v-else>暂无</span>
          </div>
        </section>

        <BossFocusCards :focus="boss.focus" />

        <div class="boss-focus-summary">
          <ClipboardList :size="22" />
          <div>
            <b>今日重点</b>
            <span>{{ boss.focus.summary }}</span>
          </div>
        </div>

        <BossExamOverview ref="examOverview" />

        <section id="workflow-review" class="boss-panel">
          <div class="boss-panel-head">
            <h3>待复核事项</h3>
            <span>{{ pendingReviewTodos.length }} 条</span>
          </div>
          <div v-if="workflowError" class="error-box compact-error">{{ workflowError }}</div>
          <div v-else-if="!pendingReviewTodos.length" class="empty-state compact">当前没有等待复核的经营事项。</div>
          <div v-else class="workflow-review-list">
            <article v-for="item in pendingReviewTodos" :key="item.id">
              <div>
                <b>{{ item.title }}</b>
                <span>{{ item.storeName || item.storeId || '全部门店' }}{{ item.month ? ` · ${item.month}` : '' }}</span>
              </div>
              <StatusBadge :label="item.statusLabel" tone="warn" />
              <button class="ghost-button" type="button" @click="openWorkflow(item)">复核</button>
            </article>
          </div>
        </section>

        <section id="needs-action" class="boss-panel">
          <div class="boss-panel-head">
          <div>
            <h3>需要我处理</h3>
          </div>
          </div>
          <div v-if="!boss.needsBossAction.length" class="empty-state compact">今天暂无需要老板处理的事项。</div>
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

        <BossRiskSummary id="risks" :risks="boss.highRiskReminders" @open="openRiskSource" />

        <BossRoleProgress id="progress" :items="boss.roleProgress" @open="openProgressSource" />

        <BossDoneReview id="done" :items="boss.doneReview" />
      </template>
    </template>

    <TodoWorkflowDrawer
      v-if="activeTodoId"
      :todo-id="activeTodoId"
      @close="closeWorkflow"
      @updated="handleWorkflowUpdated"
      @open-source="openWorkflowSource"
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
  font-size: 12px;
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
  border: 1px solid rgba(238, 126, 62, 0.22);
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
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--warn);
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
  font-size: 12px;
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
</style>
