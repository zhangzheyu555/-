<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardPlus,
  Database,
  Maximize2,
  Minimize2,
  RefreshCcw,
  Send,
  Sparkles,
  X,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import PageHeader from '../components/common/PageHeader.vue'
import ModalFooter from '../components/ui/ModalFooter.vue'
import UiButton from '../components/ui/UiButton.vue'
import {
  askAssistant,
  getAssistantStatus,
  type AssistantAction,
  type AssistantChatResponse,
  type AssistantLocalMetric,
  type AssistantStatus,
} from '../api/assistant'
import { getProfitEntries, getProfitMonths, type ProfitEntry } from '../api/finance'
import { getStores, type StoreInfo } from '../api/operations'
import { createManualBusinessTodo } from '../api/todos'
import { useBusinessScope } from '../composables/useBusinessScope'
import { useAuthStore } from '../stores/auth'

type AssistantMode = 'AUTO' | 'LOCAL' | 'AI'
type RunStatus = 'loading' | 'success' | 'error'

interface AssistantRun {
  id: number
  question: string
  mode: AssistantMode
  status: RunStatus
  response?: AssistantChatResponse
  error?: string
}

interface TodoConfirmation {
  run: AssistantRun
  action: AssistantAction
  actionIndex: number
}

const route = useRoute()
const auth = useAuthStore()
const businessScope = useBusinessScope()
const stores = ref<StoreInfo[]>([])
const months = ref<string[]>([])
const selectedStoreId = ref('')
const selectedMonth = ref('')
const currentEntry = ref<ProfitEntry | null>(null)
const pageLoading = ref(true)
const metricLoading = ref(false)
const sending = ref(false)
const input = ref('')
const assistantMode = ref<AssistantMode>('AUTO')
const runs = ref<AssistantRun[]>([])
const pageError = ref('')
const assistantStatus = ref<AssistantStatus | null>(null)
const todoConfirmation = ref<TodoConfirmation | null>(null)
const todoSubmitting = ref(false)
const todoError = ref('')
const todoDialog = ref<HTMLElement | null>(null)
const addedTodoActions = ref<Record<string, boolean>>({})
const resultScroll = ref<HTMLElement | null>(null)
const assistantWorkspace = ref<HTMLElement | null>(null)
const workspaceToggle = ref<HTMLButtonElement | null>(null)
const workspaceFullscreen = ref(false)
const fullscreenStyle = ref<Record<string, string>>({})
const followLatest = ref(true)
let appMainScrollTop = 0
let bodyOverflow = ''
let documentOverflow = ''
let todoDialogTrigger: HTMLElement | null = null
let runId = 0
let initialized = false

const financeScope = computed(() => auth.dataScope('FINANCE') || auth.dataScope('STORE'))
const accessibleStores = computed(() => {
  if (businessScope.isStoreManager.value) {
    return stores.value.filter((store) => store.id === businessScope.boundStoreId.value)
  }
  if (financeScope.value?.mode === 'ALL') return stores.value
  const ids = financeScope.value?.storeIds || []
  if (ids.length) return stores.value.filter((store) => ids.includes(store.id))
  return stores.value
})
const effectiveStoreId = computed(() => businessScope.scopedStoreId(selectedStoreId.value))
const selectedStore = computed(() => accessibleStores.value.find((store) => store.id === effectiveStoreId.value))
const selectedStoreName = computed(() => selectedStore.value?.name || businessScope.boundStoreName.value || '当前门店')
const mainMetrics = computed(() => metricCards(currentEntry.value))
const modeHint = computed(() => {
  if (assistantMode.value === 'LOCAL') return '只查数据库，不调用AI'
  if (assistantMode.value === 'AI') return '必须取得真实AI分析，失败会明确提示'
  return '数字查询走数据库，原因与建议走AI'
})

const quickQuestions = computed(() => [
  `${selectedMonthText()}营业额是多少`,
  `${selectedMonthText()}净利润为什么变化`,
  `${selectedStoreName.value}最近三个月有什么风险`,
])

onMounted(() => {
  document.addEventListener('keydown', handlePageKeydown)
  void loadPage()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handlePageKeydown)
  releaseFullscreenLayout(false)
})

watch([effectiveStoreId, selectedMonth], async ([storeId, month], [oldStoreId, oldMonth]) => {
  if (!initialized || (!storeId && !month)) return
  await loadCurrentEntry()
  if (oldStoreId && oldMonth && (storeId !== oldStoreId || month !== oldMonth)) {
    runs.value = []
  }
})

async function loadPage() {
  pageLoading.value = true
  pageError.value = ''
  if (businessScope.configurationError.value) {
    pageError.value = businessScope.configurationError.value
    pageLoading.value = false
    return
  }
  try {
    const [storeRows, monthRows] = await Promise.all([getStores(), getProfitMonths()])
    stores.value = storeRows
    months.value = normalizeMonths(monthRows)
    applyDefaults()
    await loadCurrentEntry()
    await loadAssistantStatus()
    initialized = true
  } catch (error) {
    pageError.value = normalizeError(error, '经营助手初始化失败，请刷新后重试。')
  } finally {
    pageLoading.value = false
  }
}

async function loadAssistantStatus() {
  try {
    assistantStatus.value = await getAssistantStatus()
  } catch {
    assistantStatus.value = null
  }
}

function applyDefaults() {
  const routeStore = String(route.query.storeId || '')
  const routeMonth = validMonth(String(route.query.month || ''))
  selectedStoreId.value = businessScope.isStoreManager.value
    ? businessScope.boundStoreId.value
    : accessibleStores.value.some((store) => store.id === routeStore)
      ? routeStore
      : accessibleStores.value[0]?.id || ''
  selectedMonth.value = routeMonth && months.value.includes(routeMonth)
    ? routeMonth
    : months.value[0] || currentMonth()
}

async function loadCurrentEntry() {
  if (!effectiveStoreId.value || !selectedMonth.value) {
    currentEntry.value = null
    return
  }
  metricLoading.value = true
  try {
    const rows = await getProfitEntries({
      storeId: effectiveStoreId.value,
      brandId: businessScope.isStoreManager.value ? businessScope.brandId.value ?? undefined : undefined,
      month: selectedMonth.value,
    })
    currentEntry.value = rows[0] || null
  } catch {
    currentEntry.value = null
  } finally {
    metricLoading.value = false
  }
}

async function submitQuestion(preset?: string) {
  const question = String(preset || input.value).trim()
  if (!question || sending.value) return
  if (!effectiveStoreId.value || !selectedMonth.value) {
    pageError.value = businessScope.configurationError.value || '请先选择门店和月份。'
    return
  }
  pageError.value = ''
  input.value = ''
  const run: AssistantRun = {
    id: ++runId,
    question,
    mode: assistantMode.value,
    status: 'loading',
  }
  runs.value.push(run)
  sending.value = true
  followLatest.value = true
  await nextTick(() => scrollToBottom(true))
  try {
    run.response = await askAssistant({
      message: question,
      mode: assistantMode.value,
      storeId: effectiveStoreId.value,
      month: selectedMonth.value,
    })
    run.status = 'success'
  } catch (error) {
    run.status = 'error'
    run.error = normalizeError(error, '经营助手暂时无法完成请求，请稍后重试。')
  } finally {
    sending.value = false
    await nextTick(() => scrollToBottom())
  }
}

async function requestAiAnalysis(run: AssistantRun) {
  if (sending.value) return
  run.status = 'loading'
  run.error = ''
  sending.value = true
  try {
    run.response = await askAssistant({
      message: run.question,
      mode: 'AI',
      storeId: effectiveStoreId.value,
      month: selectedMonth.value,
    })
    run.mode = 'AI'
    run.status = 'success'
  } catch (error) {
    run.status = 'error'
    run.error = normalizeError(error, 'AI分析请求失败，请稍后重试。')
  } finally {
    sending.value = false
    await nextTick(() => scrollToBottom())
  }
}

async function retryRun(run: AssistantRun) {
  if (run.mode === 'AI' || run.response?.selectedMode === 'AI') {
    await requestAiAnalysis(run)
    return
  }
  if (sending.value) return
  run.status = 'loading'
  run.error = ''
  sending.value = true
  try {
    run.response = await askAssistant({
      message: run.question,
      mode: run.mode,
      storeId: effectiveStoreId.value,
      month: selectedMonth.value,
    })
    run.status = 'success'
  } catch (error) {
    run.status = 'error'
    run.error = normalizeError(error, '经营助手暂时无法完成请求，请稍后重试。')
  } finally {
    sending.value = false
    await nextTick(() => scrollToBottom())
  }
}

function clearConversation() {
  runs.value = []
  input.value = ''
  followLatest.value = true
}

function openTodoConfirmation(run: AssistantRun, action: AssistantAction, actionIndex: number) {
  if (!run.response?.aiAnalysis.available || todoSubmitting.value) return
  todoDialogTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  todoError.value = ''
  todoConfirmation.value = { run, action, actionIndex }
  void nextTick(() => todoDialog.value?.querySelector<HTMLElement>('[data-todo-cancel]')?.focus())
}

function closeTodoConfirmation() {
  if (todoSubmitting.value) return
  dismissTodoConfirmation()
}

function dismissTodoConfirmation() {
  const trigger = todoDialogTrigger
  todoConfirmation.value = null
  todoError.value = ''
  todoDialogTrigger = null
  void nextTick(() => {
    if (trigger?.isConnected) trigger.focus()
  })
}

function trapTodoDialogFocus(event: KeyboardEvent) {
  const dialog = todoDialog.value
  if (!dialog) return
  const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(
    'button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
  ))
  if (!focusable.length) {
    event.preventDefault()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

async function confirmAddTodo() {
  const draft = todoConfirmation.value
  const response = draft?.run.response
  if (!draft || !response || todoSubmitting.value) return
  todoSubmitting.value = true
  todoError.value = ''
  const key = actionKey(draft.run, draft.action, draft.actionIndex)
  try {
    await createManualBusinessTodo({
      title: draft.action.action,
      summary: draft.action.action,
      storeId: effectiveStoreId.value,
      month: selectedMonth.value,
      assigneeRole: todoOwnerRole(draft.action.ownerRole),
      dueAt: draft.action.deadline,
      sourceModule: 'ASSISTANT',
      sourceRecordId: actionSourceRecordId(response.localData.dataVersion, draft.action),
      expectedImpact: draft.action.expectedImpact,
      verificationMetric: draft.action.verificationMetric,
      confirmed: true,
    })
    addedTodoActions.value = { ...addedTodoActions.value, [key]: true }
    dismissTodoConfirmation()
  } catch (error) {
    todoError.value = normalizeError(error, '加入待办失败，已保留当前建议，请稍后重试。')
  } finally {
    todoSubmitting.value = false
  }
}

function actionKey(run: AssistantRun, action: AssistantAction, actionIndex: number) {
  return `${run.id}:${actionIndex}:${action.action}`
}

function actionSourceRecordId(dataVersion: string, action: AssistantAction) {
    const content = [dataVersion, effectiveStoreId.value, selectedMonth.value, action.action,
    action.ownerRole, action.deadline, action.verificationMetric].join('|')
  let hash = 2166136261
  for (let index = 0; index < content.length; index += 1) {
    hash ^= content.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return `AI_ACTION_${(hash >>> 0).toString(16).padStart(8, '0')}`
}

function handleResultScroll() {
  const element = resultScroll.value
  if (!element) return
  followLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 96
}

function scrollToBottom(force = false) {
  if (!resultScroll.value || (!force && !followLatest.value)) return
  resultScroll.value.scrollTop = resultScroll.value.scrollHeight
}

async function toggleWorkspaceFullscreen() {
  if (workspaceFullscreen.value) {
    releaseFullscreenLayout(true)
    return
  }

  const appMain = assistantWorkspace.value?.closest<HTMLElement>('.app-main')
  appMainScrollTop = appMain?.scrollTop || 0
  bodyOverflow = document.body.style.overflow
  documentOverflow = document.documentElement.style.overflow
  document.body.style.overflow = 'hidden'
  document.documentElement.style.overflow = 'hidden'
  workspaceFullscreen.value = true
  window.addEventListener('resize', updateFullscreenBounds)
  await nextTick()
  updateFullscreenBounds()
}

function updateFullscreenBounds() {
  if (!workspaceFullscreen.value) return
  const appMain = assistantWorkspace.value?.closest<HTMLElement>('.app-main')
  const rect = appMain?.getBoundingClientRect()
  const inset = 12
  fullscreenStyle.value = rect
    ? {
        top: `${Math.max(inset, rect.top + inset)}px`,
        right: `${Math.max(inset, window.innerWidth - rect.right + inset)}px`,
        bottom: `${Math.max(inset, window.innerHeight - rect.bottom + inset)}px`,
        left: `${Math.max(inset, rect.left + inset)}px`,
      }
    : { inset: `${inset}px` }
}

function releaseFullscreenLayout(restoreFocus: boolean) {
  if (!workspaceFullscreen.value) return
  workspaceFullscreen.value = false
  fullscreenStyle.value = {}
  window.removeEventListener('resize', updateFullscreenBounds)
  document.body.style.overflow = bodyOverflow
  document.documentElement.style.overflow = documentOverflow
  const appMain = assistantWorkspace.value?.closest<HTMLElement>('.app-main')
  if (appMain) appMain.scrollTop = appMainScrollTop
  if (restoreFocus) void nextTick(() => workspaceToggle.value?.focus())
}

function handlePageKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  if (todoConfirmation.value) {
    if (todoSubmitting.value) return
    event.preventDefault()
    closeTodoConfirmation()
    return
  }
  if (!workspaceFullscreen.value) return
  event.preventDefault()
  releaseFullscreenLayout(true)
}

function metricCards(entry: ProfitEntry | null) {
  return [
    { label: '营业额', value: money(entry?.sales), tone: '' },
    { label: '成本', value: money(entry?.costSum), tone: '' },
    { label: '净利润', value: money(entry?.net), tone: numberValue(entry?.net) < 0 ? 'bad' : 'good' },
    { label: '净利率', value: percent(entry?.margin), tone: numberValue(entry?.margin) < 0.05 ? 'bad' : 'good' },
  ]
}

function visibleLocalMetrics(metrics: AssistantLocalMetric[]) {
  const keys = ['sales', 'income', 'cost', 'gross', 'expense', 'net', 'margin', 'momNetChange', 'yoyNetChange']
  return [...metrics].sort((left, right) => keys.indexOf(left.key) - keys.indexOf(right.key))
    .filter((metric) => keys.includes(metric.key))
}

function confidenceText(value: string) {
  return ({ HIGH: '高', MEDIUM: '中', LOW: '低' } as Record<string, string>)[value] || '未评估'
}

function severityText(value: string) {
  return ({ HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' } as Record<string, string>)[value] || '待核实'
}

function roleText(value: string) {
  return ({
    BOSS: '老板',
    FINANCE: '财务',
    STORE_MANAGER: '店长',
    WAREHOUSE: '仓库管理员',
    SUPERVISOR: '督导',
    OPERATIONS: '运营',
  } as Record<string, string>)[String(value || '').toUpperCase()] || value || '待确认'
}

function todoOwnerRole(value: string) {
  const normalized = String(value || '').trim()
  const aliases: Record<string, string> = {
    老板: 'BOSS',
    系统管理员: 'BOSS',
    财务: 'FINANCE',
    店长: 'STORE_MANAGER',
    督导: 'SUPERVISOR',
    仓库: 'WAREHOUSE',
    仓库管理员: 'WAREHOUSE',
    运营: 'OPERATIONS',
  }
  return aliases[normalized] || normalized.toUpperCase()
}

function formatUpdatedAt(value: string) {
  if (!value) return '本次查询'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function latencyText(value: number) {
  return `${(Math.max(0, Number(value || 0)) / 1000).toFixed(1)}秒`
}

function maskedRequestId(value: string) {
  const id = String(value || '').trim()
  if (!id) return ''
  if (id.length <= 10) return id
  return `${id.slice(0, 6)}…${id.slice(-4)}`
}

function aiUnavailableMessage(run: AssistantRun) {
  if (assistantStatus.value?.configured === false || run.response?.error?.code === 'DEEPSEEK_NOT_CONFIGURED') {
    return 'AI分析服务尚未配置，本地经营数据仍可正常查询。'
  }
  return run.response?.error?.message || 'AI分析暂时不可用，请稍后重新分析。'
}

function selectedMonthText() {
  const value = Number(selectedMonth.value.split('-')[1] || 0)
  return value ? `${value}月` : '本月'
}

function normalizeMonths(values: string[]) {
  const normalized = values.filter((value) => /^\d{4}-\d{2}$/.test(value))
  return Array.from(new Set(normalized)).sort().reverse()
}

function validMonth(value: string) {
  return /^\d{4}-\d{2}$/.test(value) ? value : ''
}

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

function money(value?: number) {
  return `¥${numberValue(value).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

function percent(value?: number) {
  return `${(numberValue(value) * 100).toFixed(1)}%`
}

function numberValue(value?: number) {
  return Number(value || 0)
}

const technicalErrors = ['java.lang', 'exception', 'stacktrace', 'handler dispatch', 'axios']
function normalizeError(value: unknown, fallback: string) {
  const message = value instanceof Error ? value.message : String(value || '')
  if (!message || technicalErrors.some((item) => message.toLowerCase().includes(item))) return fallback
  return message
}
</script>

<template>
  <section class="page-panel store-assistant-page">
    <div class="assistant-page-header">
      <PageHeader
        title="门店经营助手"
        :subtitle="businessScope.isStoreManager.value
          ? `${businessScope.managerScopeLabel.value} · 数据库负责事实，AI负责解释和行动建议`
          : '数据库负责事实，AI负责解释和行动建议'"
      >
        <template #actions>
          <button class="secondary-button" type="button" :disabled="!runs.length" @click="clearConversation">
            清空记录
          </button>
        </template>
      </PageHeader>

      <div v-if="pageError" class="page-error" role="alert">{{ pageError }}</div>
    </div>

    <section
      class="context-bar"
      :class="{ 'context-bar--single-store': businessScope.isStoreManager.value }"
      aria-label="当前分析范围"
    >
      <label v-if="!businessScope.isStoreManager.value" class="context-field">
        <span>门店</span>
        <select v-model="selectedStoreId" :disabled="pageLoading || financeScope?.mode === 'OWN_STORE'">
          <option v-if="!accessibleStores.length" value="">暂无可选门店</option>
          <option v-for="store in accessibleStores" :key="store.id" :value="store.id">{{ store.name }}</option>
        </select>
      </label>
      <label class="context-field month-field">
        <span>月份</span>
        <select v-model="selectedMonth" :disabled="pageLoading || !months.length">
          <option v-for="month in months" :key="month" :value="month">{{ month }}</option>
        </select>
      </label>
      <div class="metric-strip" :class="{ muted: metricLoading }">
        <article v-for="card in mainMetrics" :key="card.label" class="metric-item" :class="card.tone">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
        </article>
      </div>
    </section>

    <section
      ref="assistantWorkspace"
      class="assistant-workspace"
      :class="{ 'is-fullscreen': workspaceFullscreen }"
      :style="fullscreenStyle"
    >
      <button
        ref="workspaceToggle"
        class="workspace-toggle"
        type="button"
        :title="workspaceFullscreen ? '退出全屏' : '放大对话区域'"
        :aria-label="workspaceFullscreen ? '退出全屏' : '放大对话区域'"
        :aria-pressed="workspaceFullscreen"
        @click="toggleWorkspaceFullscreen"
      >
        <Minimize2 v-if="workspaceFullscreen" :size="18" />
        <Maximize2 v-else :size="18" />
      </button>

      <div ref="resultScroll" class="result-stream" aria-live="polite" @scroll.passive="handleResultScroll">
        <div v-if="!runs.length" class="assistant-empty">
          <div class="empty-icon"><Sparkles :size="22" /></div>
          <h2>先查清经营数据，再让AI解释原因</h2>
          <p>数字查询不会调用模型；趋势、异常和改善建议会进入AI分析。</p>
          <div class="empty-questions">
            <button v-for="question in quickQuestions" :key="question" type="button" @click="submitQuestion(question)">
              {{ question }}
            </button>
          </div>
        </div>

        <article v-for="run in runs" :key="run.id" class="assistant-run">
          <div class="question-row">
            <span>我的问题</span>
            <p>{{ run.question }}</p>
          </div>

          <div v-if="run.status === 'loading'" class="analysis-loading" role="status">
            <span class="loading-line wide"></span>
            <span class="loading-line"></span>
            <p>{{ run.mode === 'LOCAL' ? '正在查询经营数据库…' : '正在整理真实数据并请求AI分析…' }}</p>
          </div>

          <div v-else-if="run.status === 'error'" class="run-error" role="alert">
            <AlertTriangle :size="18" />
            <span>{{ run.error }}</span>
            <button type="button" :disabled="sending" @click="retryRun(run)">重试</button>
          </div>

          <template v-else-if="run.response">
            <section class="data-result" aria-label="经营数据">
              <header class="result-heading">
                <div>
                  <Database :size="18" />
                  <h3>经营数据</h3>
                </div>
                <span>{{ run.response.localData.dataPeriod }} · {{ run.response.localData.dataScope }}</span>
              </header>
              <div class="data-meta" aria-label="数据口径">
                <span><strong>来源</strong>{{ run.response.localData.source }}</span>
                <span><strong>更新时间</strong>{{ formatUpdatedAt(run.response.localData.updatedAt) }}</span>
                <span><strong>处理方式</strong>{{ run.response.selectedMode === 'AI' ? '数据库计算 + AI分析' : '仅数据库计算' }}</span>
              </div>
              <p class="data-summary">{{ run.response.localData.summary }}</p>
              <div v-if="run.response.localData.metrics.length" class="local-metrics">
                <article v-for="metric in visibleLocalMetrics(run.response.localData.metrics)" :key="metric.key">
                  <span>{{ metric.label }}</span>
                  <strong>{{ metric.displayValue }}</strong>
                  <small v-if="metric.comparison && metric.changeRate !== null">
                    {{ metric.comparison }} {{ metric.changeRate >= 0 ? '+' : '' }}{{ (metric.changeRate * 100).toFixed(1) }}%
                  </small>
                </article>
              </div>
              <footer>
                {{ run.response.selectionReason }}
                <span v-if="run.response.localData.calculationVersion"> · 计算版本 {{ run.response.localData.calculationVersion }}</span>
              </footer>
            </section>

            <section v-if="run.response.aiAnalysis.available" class="ai-result" aria-label="AI经营分析">
              <header class="result-heading ai-heading">
                <div>
                  <Sparkles :size="18" />
                  <h3>AI经营分析</h3>
                </div>
                <span>
                  {{ run.response.aiAnalysis.provider }} · {{ run.response.aiAnalysis.model }} · {{ latencyText(run.response.aiAnalysis.latencyMs) }}
                  <template v-if="maskedRequestId(run.response.aiAnalysis.requestId)"> · 请求 {{ maskedRequestId(run.response.aiAnalysis.requestId) }}</template>
                </span>
              </header>
              <section class="analysis-block conclusion-block">
                <h4>核心判断</h4>
                <p>{{ run.response.aiAnalysis.summary }}</p>
              </section>
              <div class="analysis-columns">
                <section v-if="run.response.aiAnalysis.findings.length" class="analysis-block">
                  <h4>关键发现</h4>
                  <ul><li v-for="item in run.response.aiAnalysis.findings" :key="item">{{ item }}</li></ul>
                </section>
                <section v-if="run.response.aiAnalysis.risks.length" class="analysis-block risk-block">
                  <h4>风险与异常</h4>
                  <ul class="risk-list">
                    <li v-for="item in run.response.aiAnalysis.risks" :key="`${item.title}-${item.evidence}`">
                      <span class="severity-tag" :class="item.severity.toLowerCase()">{{ severityText(item.severity) }}</span>
                      <div><strong>{{ item.title }}</strong><p>{{ item.evidence }}</p></div>
                    </li>
                  </ul>
                </section>
              </div>
              <section v-if="run.response.aiAnalysis.possibleCauses.length" class="analysis-block">
                <h4>可能原因 <small>以下为推测，需结合业务核实</small></h4>
                <ul class="cause-list">
                  <li v-for="item in run.response.aiAnalysis.possibleCauses" :key="`${item.cause}-${item.basis}`">
                    <div><strong>{{ item.cause }}</strong><span class="confidence-tag">可信度{{ confidenceText(item.confidence) }}</span></div>
                    <p>{{ item.basis }}</p>
                  </li>
                </ul>
              </section>
              <section class="analysis-block action-block">
                <h4>本周行动建议</h4>
                <ol class="action-list">
                  <li v-for="(item, actionIndex) in run.response.aiAnalysis.actions" :key="`${item.action}-${actionIndex}`">
                    <div class="action-copy">
                      <strong>{{ item.action }}</strong>
                      <dl>
                        <div><dt>负责人</dt><dd>{{ roleText(item.ownerRole) }}</dd></div>
                        <div><dt>建议期限</dt><dd>{{ item.deadline }}</dd></div>
                        <div><dt>预期改善</dt><dd>{{ item.expectedImpact }}</dd></div>
                        <div><dt>验收指标</dt><dd>{{ item.verificationMetric }}</dd></div>
                      </dl>
                    </div>
                    <button
                      type="button"
                      class="action-add-button"
                      :class="{ added: addedTodoActions[actionKey(run, item, actionIndex)] }"
                      :disabled="sending || !!addedTodoActions[actionKey(run, item, actionIndex)]"
                      @click="openTodoConfirmation(run, item, actionIndex)"
                    >
                      <CheckCircle2 v-if="addedTodoActions[actionKey(run, item, actionIndex)]" :size="15" />
                      <ClipboardPlus v-else :size="15" />
                      {{ addedTodoActions[actionKey(run, item, actionIndex)] ? '已加入待办' : '加入待办' }}
                    </button>
                  </li>
                </ol>
              </section>
              <section v-if="run.response.aiAnalysis.limitations.length" class="analysis-limitations">
                <strong>数据限制</strong>
                <span>{{ run.response.aiAnalysis.limitations.join('；') }}</span>
              </section>
              <footer>判断可信度：{{ confidenceText(run.response.aiAnalysis.confidence) }}</footer>
            </section>

            <section v-else-if="run.response.error" class="ai-unavailable" role="status">
              <div><AlertTriangle :size="18" /><strong>AI分析暂时不可用</strong></div>
              <p>{{ aiUnavailableMessage(run) }}</p>
              <button type="button" :disabled="sending" @click="requestAiAnalysis(run)">
                <RefreshCcw :size="15" />重新分析
              </button>
            </section>

            <div v-else-if="run.mode === 'LOCAL' || run.mode === 'AUTO'" class="local-followup">
              <span>当前只查询了真实经营数据。</span>
              <button type="button" :disabled="sending" @click="requestAiAnalysis(run)">
                <Sparkles :size="15" />让AI分析原因和建议
              </button>
            </div>
          </template>
        </article>
      </div>

      <footer class="assistant-composer">
        <div class="mode-switch" aria-label="回答模式">
          <button
            v-for="mode in ([['LOCAL', '查数据'], ['AI', 'AI分析'], ['AUTO', '自动']] as const)"
            :key="mode[0]"
            type="button"
            :class="{ active: assistantMode === mode[0] }"
            :aria-pressed="assistantMode === mode[0]"
            :disabled="sending"
            @click="assistantMode = mode[0]"
          >{{ mode[1] }}</button>
          <span>{{ modeHint }}</span>
          <span v-if="assistantStatus" class="service-status" :class="{ ready: assistantStatus.configured }">
            {{ assistantStatus.configured ? `${assistantStatus.provider} 已就绪` : 'AI服务未配置' }}
          </span>
        </div>
        <form class="question-form" @submit.prevent="submitQuestion()">
          <label class="sr-only" for="assistant-question">经营问题</label>
          <input
            id="assistant-question"
            v-model.trim="input"
            type="text"
            :placeholder="`问${selectedStoreName}，例如：7月净利润为什么变化？`"
            :disabled="sending || pageLoading"
            autocomplete="off"
          />
          <button type="submit" :disabled="sending || pageLoading || !input.trim()">
            <Send :size="17" />{{ sending ? '处理中' : '发送' }}
          </button>
        </form>
      </footer>
    </section>

    <Teleport to="body">
      <div
        v-if="todoConfirmation"
        class="todo-dialog-backdrop"
        role="presentation"
        @click.self="closeTodoConfirmation"
      >
        <section
          ref="todoDialog"
          class="todo-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="todo-dialog-title"
          aria-describedby="todo-dialog-description"
          @keydown.tab="trapTodoDialogFocus"
        >
          <header>
            <div>
              <h2 id="todo-dialog-title">确认加入待办</h2>
              <p id="todo-dialog-description">AI只提供建议，确认后才会写入正式待办。</p>
            </div>
            <UiButton
              variant="ghost"
              type="button"
              icon-only
              aria-label="关闭待办确认"
              title="关闭"
              :disabled="todoSubmitting"
              @click="closeTodoConfirmation"
            >
              <template #icon><X :size="20" /></template>
            </UiButton>
          </header>
          <div class="todo-dialog-content">
            <strong>{{ todoConfirmation.action.action }}</strong>
            <dl>
              <div><dt>负责人</dt><dd>{{ roleText(todoConfirmation.action.ownerRole) }}</dd></div>
              <div><dt>建议期限</dt><dd>{{ todoConfirmation.action.deadline }}</dd></div>
              <div><dt>预期改善</dt><dd>{{ todoConfirmation.action.expectedImpact }}</dd></div>
              <div><dt>验收指标</dt><dd>{{ todoConfirmation.action.verificationMetric }}</dd></div>
            </dl>
            <p v-if="todoError" class="todo-dialog-error" role="alert">{{ todoError }}</p>
          </div>
          <ModalFooter>
            <UiButton data-todo-cancel variant="secondary" type="button" :disabled="todoSubmitting" @click="closeTodoConfirmation">取消</UiButton>
            <UiButton variant="primary" type="button" :disabled="todoSubmitting" @click="confirmAddTodo">
              {{ todoSubmitting ? '正在加入…' : '确认加入待办' }}
            </UiButton>
          </ModalFooter>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.store-assistant-page {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 12px;
  overflow: hidden !important;
  container-type: inline-size;
}

.assistant-page-header {
  display: grid;
  gap: 8px;
}

.secondary-button,
.context-field select,
.empty-questions button,
.mode-switch button,
.question-form input,
.question-form button,
.local-followup button,
.ai-unavailable button,
.run-error button {
  min-height: 44px;
  border: 1px solid var(--ds-line-strong);
  border-radius: 6px;
  background: var(--ds-surface);
  color: var(--ds-ink);
}

.secondary-button { padding: 0 16px; }
.secondary-button:disabled { cursor: not-allowed; opacity: .45; }

.page-error,
.run-error {
  border: 1px solid #efc2c6;
  border-radius: 6px;
  background: var(--ds-danger-soft);
  color: #9d2632;
}
.page-error { padding: 10px 14px; }

.context-bar {
  display: grid;
  grid-template-columns: minmax(220px, 1.3fr) 160px minmax(480px, 2fr);
  gap: 12px;
  align-items: end;
  padding: 12px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: var(--ds-surface);
}

.context-bar--single-store {
  grid-template-columns: 160px minmax(480px, 1fr);
}

.context-field { display: grid; gap: 6px; }
.context-field span { color: var(--ds-secondary); font-size: 13px; font-weight: 600; }
.context-field select { width: 100%; padding: 0 12px; outline: none; }
.context-field select:focus,
.question-form input:focus { border-color: var(--ds-primary-hover); box-shadow: 0 0 0 3px var(--ds-primary-soft); }

.metric-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); }
.metric-strip.muted { opacity: .55; }
.metric-item { min-width: 0; padding: 3px 14px; border-left: 1px solid var(--ds-line); }
.metric-item span { display: block; color: var(--ds-muted); font-size: 12px; }
.metric-item strong { display: block; margin-top: 5px; overflow: hidden; font-size: 18px; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; }
.metric-item.good strong { color: var(--ds-success); }
.metric-item.bad strong { color: var(--ds-danger); }

.assistant-workspace {
  position: relative;
  display: grid;
  min-height: 0;
  grid-template-rows: minmax(0, 1fr) auto;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: var(--ds-surface);
  overflow: hidden;
}

.assistant-workspace.is-fullscreen {
  position: fixed;
  z-index: 60;
  min-height: 0;
  animation: workspace-fade-in 180ms ease-out;
}

.workspace-toggle {
  position: absolute;
  z-index: 2;
  top: 8px;
  right: 8px;
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ds-line-strong);
  border-radius: 6px;
  background: var(--ds-surface);
  color: var(--ds-secondary);
}

.workspace-toggle:hover {
  border-color: var(--ds-primary-hover);
  color: var(--ds-primary-hover);
}

.workspace-toggle:focus-visible {
  outline: 3px solid var(--ds-primary-soft);
  outline-offset: 2px;
}

.result-stream {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: 16px;
  background: var(--ds-surface-muted);
}
.assistant-empty { display: grid; width: 100%; max-width: 680px; min-height: 100%; margin: 0 auto; padding: 48px 16px 24px; place-content: center; justify-items: center; text-align: center; }
.empty-icon { display: grid; width: 44px; height: 44px; place-items: center; border-radius: 50%; background: var(--ds-primary-soft); color: var(--ds-primary-hover); }
.assistant-empty h2 { margin: 14px 0 6px; font-size: 20px; }
.assistant-empty p { margin: 0; color: var(--ds-secondary); }
.empty-questions { display: flex; margin-top: 20px; gap: 8px; flex-wrap: wrap; justify-content: center; }
.empty-questions button { min-height: 40px; padding: 0 14px; }
.empty-questions button:hover { border-color: var(--ds-primary-hover); background: var(--ds-primary-soft); }

.assistant-run { max-width: 1160px; margin: 0 auto 16px; }
.question-row { display: flex; align-items: flex-start; justify-content: flex-end; gap: 10px; margin-bottom: 10px; }
.question-row span { padding-top: 10px; color: var(--ds-muted); font-size: 12px; }
.question-row p { max-width: 70%; margin: 0; padding: 10px 14px; border-radius: 6px; background: var(--ds-primary-hover); color: #fff; line-height: 1.55; }

.data-result,
.ai-result,
.ai-unavailable,
.analysis-loading,
.run-error,
.local-followup {
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: var(--ds-surface);
}
.data-result,
.ai-result { padding: 18px; }
.ai-result { margin-top: 12px; }
.result-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.result-heading > div { display: flex; align-items: center; gap: 8px; }
.result-heading h3 { margin: 0; font-size: 16px; }
.result-heading > span { color: var(--ds-muted); font-size: 12px; }
.data-summary { margin: 14px 0; color: var(--ds-ink); line-height: 1.65; }
.data-meta { display: flex; margin-top: 12px; padding: 9px 0; gap: 20px; flex-wrap: wrap; border-block: 1px solid var(--ds-line); color: var(--ds-secondary); font-size: 12px; }
.data-meta span { display: inline-flex; gap: 5px; }
.data-meta strong { color: var(--ds-muted); font-weight: 500; }
.local-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border-block: 1px solid var(--ds-line); }
.local-metrics article { min-width: 0; padding: 12px; border-right: 1px solid var(--ds-line); }
.local-metrics article:nth-child(5n) { border-right: 0; }
.local-metrics span,
.local-metrics small { display: block; color: var(--ds-muted); font-size: 12px; }
.local-metrics strong { display: block; margin: 5px 0 3px; font-size: 17px; font-variant-numeric: tabular-nums; }
.data-result footer,
.ai-result footer { margin-top: 12px; color: var(--ds-muted); font-size: 12px; }

.ai-heading { padding-bottom: 14px; border-bottom: 1px solid var(--ds-line); color: var(--ds-primary-hover); }
.analysis-block { margin-top: 18px; }
.analysis-block h4 { margin: 0 0 8px; color: var(--ds-ink); font-size: 14px; }
.analysis-block h4 small { margin-left: 6px; color: var(--ds-muted); font-weight: 400; }
.analysis-block p,
.analysis-block ul,
.analysis-block ol { margin: 0; color: var(--ds-secondary); line-height: 1.75; }
.analysis-block ul,
.analysis-block ol { padding-left: 22px; }
.conclusion-block { padding: 14px; border: 1px solid var(--ds-line); border-radius: 6px; background: var(--ds-primary-soft); }
.analysis-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.risk-block { color: var(--ds-warning); }
.risk-list,
.cause-list,
.action-list { padding: 0 !important; list-style: none; }
.risk-list li { display: flex; gap: 9px; padding: 10px 0; align-items: flex-start; border-top: 1px solid var(--ds-line); }
.risk-list li:first-child { padding-top: 0; border-top: 0; }
.risk-list div { min-width: 0; }
.risk-list strong { display: block; color: var(--ds-ink); font-size: 13px; }
.risk-list p,
.cause-list p { margin: 3px 0 0; color: var(--ds-secondary); font-size: 13px; line-height: 1.55; }
.severity-tag,
.confidence-tag { display: inline-flex; flex: none; min-height: 22px; padding: 0 7px; align-items: center; border-radius: 999px; background: var(--ds-warning-soft); color: #8a560c; font-size: 11px; font-weight: 600; white-space: nowrap; }
.severity-tag.low { background: var(--ds-primary-soft); color: var(--ds-primary-hover); }
.severity-tag.high { background: var(--ds-danger-soft); color: var(--ds-danger); }
.cause-list li { padding: 10px 0; border-top: 1px solid var(--ds-line); }
.cause-list li:first-child { padding-top: 0; border-top: 0; }
.cause-list li > div { display: flex; gap: 8px; align-items: center; justify-content: space-between; }
.cause-list strong { color: var(--ds-ink); font-size: 13px; }
.confidence-tag { background: var(--ds-surface-muted); color: var(--ds-secondary); }
.action-list { counter-reset: action-counter; }
.action-list > li { display: flex; padding: 14px 0; align-items: flex-start; gap: 16px; border-top: 1px solid var(--ds-line); counter-increment: action-counter; }
.action-list > li::before { content: counter(action-counter); display: grid; width: 26px; height: 26px; flex: none; place-items: center; border-radius: 50%; background: var(--ds-primary-soft); color: var(--ds-primary-hover); font-size: 12px; font-weight: 700; }
.action-list > li:first-child { padding-top: 0; border-top: 0; }
.action-copy { min-width: 0; flex: 1; }
.action-copy > strong { display: block; color: var(--ds-ink); font-size: 14px; line-height: 1.5; }
.action-copy dl,
.todo-dialog-content dl { display: grid; margin: 8px 0 0; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 18px; }
.action-copy dl div,
.todo-dialog-content dl div { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 6px; font-size: 12px; }
.action-copy dt,
.todo-dialog-content dt { color: var(--ds-muted); }
.action-copy dd,
.todo-dialog-content dd { margin: 0; color: var(--ds-secondary); }
.action-add-button { display: inline-flex; min-height: 36px; padding: 0 11px; flex: none; align-items: center; gap: 6px; border: 1px solid var(--ds-primary-hover); border-radius: 6px; background: var(--ds-surface); color: var(--ds-primary-hover); white-space: nowrap; }
.action-add-button:hover { background: var(--ds-primary-soft); }
.action-add-button.added { border-color: var(--ds-line); color: var(--ds-success); }
.action-add-button:disabled { cursor: not-allowed; opacity: .72; }
.analysis-limitations { display: flex; margin-top: 18px; gap: 8px; padding-top: 12px; border-top: 1px solid var(--ds-line); color: var(--ds-muted); font-size: 12px; }

.ai-unavailable { margin-top: 12px; padding: 16px; background: var(--ds-warning-soft); }
.ai-unavailable > div { display: flex; align-items: center; gap: 8px; color: #8a560c; }
.ai-unavailable p { margin: 7px 0 12px; color: var(--ds-secondary); }
.ai-unavailable button,
.local-followup button,
.run-error button { display: inline-flex; align-items: center; gap: 6px; padding: 0 14px; }
.local-followup { display: flex; margin-top: 12px; padding: 12px 14px; align-items: center; justify-content: space-between; color: var(--ds-secondary); }
.local-followup button { color: var(--ds-primary-hover); }
.run-error { display: flex; padding: 14px; align-items: center; gap: 10px; }
.run-error span { flex: 1; }
.analysis-loading { display: grid; gap: 9px; padding: 20px; }
.analysis-loading p { margin: 2px 0 0; color: var(--ds-muted); }
.loading-line { width: 52%; height: 12px; border-radius: 4px; background: #e7efed; animation: pulse 1.2s ease-in-out infinite; }
.loading-line.wide { width: 86%; }

.assistant-composer { flex: none; padding: 12px 16px 16px; border-top: 1px solid var(--ds-line); background: var(--ds-surface); }
.mode-switch { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; }
.mode-switch button { min-height: 34px; padding: 0 12px; }
.mode-switch button.active { border-color: var(--ds-primary-hover); background: var(--ds-primary-hover); color: #fff; }
.mode-switch > span { margin-left: 6px; color: var(--ds-muted); font-size: 12px; }
.mode-switch .service-status { display: inline-flex; margin-left: auto; align-items: center; gap: 6px; white-space: nowrap; }
.mode-switch .service-status::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--ds-warning); }
.mode-switch .service-status.ready::before { background: var(--ds-success); }
.question-form { display: flex; gap: 10px; }
.question-form input { min-width: 0; flex: 1; padding: 0 14px; outline: none; }
.question-form button { display: inline-flex; min-width: 108px; padding: 0 18px; align-items: center; justify-content: center; gap: 7px; border-color: var(--ds-primary-hover); background: var(--ds-primary-hover); color: #fff; font-weight: 700; }
.question-form button:disabled { cursor: not-allowed; border-color: #b8c8c6; background: #b8c8c6; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }

.todo-dialog-backdrop { position: fixed; z-index: 1400; inset: 0; display: grid; padding: 20px; place-items: center; background: rgb(18 36 36 / 46%); }
.todo-dialog { width: min(560px, 100%); border: 1px solid var(--ds-line-strong); border-radius: 8px; background: var(--ds-surface); box-shadow: 0 4px 8px rgb(18 36 36 / 14%); color: var(--ds-ink); }
.todo-dialog > header { display: flex; padding: 18px 20px; align-items: flex-start; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--ds-line); }
.todo-dialog h2 { margin: 0; font-size: 18px; }
.todo-dialog header p { margin: 5px 0 0; color: var(--ds-secondary); font-size: 13px; }
.todo-dialog-content { padding: 20px; }
.todo-dialog-content > strong { display: block; font-size: 15px; line-height: 1.55; }
.todo-dialog-content dl { margin-top: 16px; padding: 14px; border: 1px solid var(--ds-line); border-radius: 6px; background: var(--ds-surface-muted); }
.todo-dialog-content dl div { grid-template-columns: 72px minmax(0, 1fr); font-size: 13px; }
.todo-dialog-error { margin: 14px 0 0; padding: 10px 12px; border-radius: 6px; background: var(--ds-danger-soft); color: var(--ds-danger); font-size: 13px; }

@keyframes pulse { 50% { opacity: .45; } }
@keyframes workspace-fade-in { from { opacity: 0; } to { opacity: 1; } }

@media (max-width: 1280px) {
  .context-bar { grid-template-columns: 1fr 1fr; }
  .metric-strip { grid-column: 1 / -1; }
  .local-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .analysis-columns { grid-template-columns: 1fr; gap: 0; }
  .action-list > li { flex-wrap: wrap; }
  .action-add-button { margin-left: 42px; }
}

@media (max-width: 720px) {
  .context-bar { grid-template-columns: 1fr; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-item:nth-child(odd) { border-left: 0; }
  .result-stream { padding: 12px; }
  .question-row p { max-width: 88%; }
  .local-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .local-metrics article:nth-child(5n) { border-right: 1px solid var(--ds-line); }
  .local-followup { align-items: stretch; flex-direction: column; }
  .mode-switch { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 4px; }
  .mode-switch button { padding: 0 8px; }
  .mode-switch > span:not(.service-status) { grid-column: 1 / -1; margin-left: 0; }
  .mode-switch .service-status { grid-column: 1 / -1; margin-left: 0; }
  .question-form { gap: 8px; }
  .question-form button { min-width: 88px; padding: 0 12px; }
  .action-copy dl,
  .todo-dialog-content dl { grid-template-columns: 1fr; }
  .action-add-button { width: calc(100% - 42px); justify-content: center; }
  .assistant-run:first-of-type { padding-top: 44px; }
}

@media (prefers-reduced-motion: reduce) {
  .loading-line,
  .assistant-workspace.is-fullscreen { animation: none; }
}
</style>
