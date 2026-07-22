<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  AlertTriangle,
  CheckCircle2,
  ClipboardPlus,
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
  getOperatingSnapshot,
  getAssistantStatus,
  type AssistantAction,
  type AssistantChatResponse,
  type AssistantLocalMetric,
  type OperatingSnapshot,
  type AssistantStatus,
} from '../api/assistant'
import { getProfitEntries, getProfitMonths, type ProfitEntry } from '../api/finance'
import { getProfitDashboard, type ProfitSummary } from '../api/profit'
import { getStores, type StoreInfo } from '../api/operations'
import { ApiError } from '../api/http'
import { createManualBusinessTodo } from '../api/todos'
import { useBusinessScope } from '../composables/useBusinessScope'
import { useAuthStore } from '../stores/auth'

type AssistantMode = 'AUTO' | 'LOCAL' | 'AI'
type RunStatus = 'loading-data' | 'loading-ai' | 'success' | 'error' | 'partial-error'
type AssistantServiceTone = 'ready' | 'configured' | 'warning'

interface AssistantServicePresentation {
  text: string
  tone: AssistantServiceTone
}

interface AssistantRun {
  id: number
  question: string
  mode: AssistantMode
  status: RunStatus
  startedAt: number
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
const entryState = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
const entryError = ref('')
const entryRequestId = ref('')
const dashboardSummary = ref<ProfitSummary | null>(null)
const dataIntegrityWarning = ref('')
let entryAbortController: AbortController | null = null
let snapshotAbortController: AbortController | null = null
let snapshotRequestVersion = 0
const pageLoading = ref(true)
const metricLoading = ref(false)
const sending = ref(false)
const input = ref('')
const assistantMode = ref<AssistantMode>('AUTO')
const runs = ref<AssistantRun[]>([])
const pageError = ref('')
const operatingSnapshot = ref<OperatingSnapshot | null>(null)
const snapshotLoading = ref(false)
const snapshotError = ref('')
const snapshotNotice = ref('')
const assistantStatus = ref<AssistantStatus | null>(null)
const clearConfirmation = ref(false)
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
const progressClock = ref(Date.now())
let appMainScrollTop = 0
let bodyOverflow = ''
let documentOverflow = ''
let todoDialogTrigger: HTMLElement | null = null
let runId = 0
let initialized = false
let assistantStatusRequest: Promise<void> | null = null
let assistantStatusRetryTimer: number | null = null
let lastAssistantStatusFocusRefreshAt = 0
let assistantStatusUnconfiguredRetryUsed = false
let pageDisposed = false
let progressTimer: number | null = null

// A fresh backend process does not inherit the previous Java process environment. Recheck once
// after a known "not configured" result and whenever the operator returns to this page, without
// polling continuously or creating duplicate status requests.
const ASSISTANT_STATUS_FOCUS_DEBOUNCE_MS = 1_500
const ASSISTANT_STATUS_UNCONFIGURED_RECHECK_DELAY_MS = 15_000

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
const effectiveStoreId = computed(() =>
  businessScope.isBoss.value ? '' : businessScope.scopedStoreId(selectedStoreId.value)
)
const selectedStore = computed(() => accessibleStores.value.find((store) => store.id === effectiveStoreId.value))
const selectedStoreName = computed(() => selectedStore.value?.name || businessScope.boundStoreName.value || '当前门店')
const mainMetrics = computed(() => metricCards(currentEntry.value))
const modeHint = computed(() => {
  if (assistantMode.value === 'LOCAL') return '只查数据库，不调用AI'
  if (assistantMode.value === 'AI') return '深度模型分析更完整，但等待时间会更长'
  return '数字查询走数据库，原因与建议优先使用快速AI'
})
const assistantServicePresentation = computed<AssistantServicePresentation | null>(() => {
  if (!assistantStatus.value) return null
  return servicePresentation(assistantStatus.value)
})

const quickQuestions = computed(() => [
  `${selectedMonthText()}营业额是多少`,
  `${selectedMonthText()}净利润为什么变化`,
  `${selectedStoreName.value}最近三个月有什么风险`,
])

onMounted(() => {
  document.addEventListener('keydown', handlePageKeydown)
  window.addEventListener('focus', refreshAssistantStatusOnFocus)
  void loadPage()
})

onBeforeUnmount(() => {
  pageDisposed = true
  snapshotAbortController?.abort()
  document.removeEventListener('keydown', handlePageKeydown)
  window.removeEventListener('focus', refreshAssistantStatusOnFocus)
  clearAssistantStatusRetryTimer()
  stopProgressClock()
  releaseFullscreenLayout(false)
})

watch([effectiveStoreId, selectedMonth], async ([storeId, month], [oldStoreId, oldMonth]) => {
  if (!initialized || (!storeId && !month)) return
  await Promise.all([loadCurrentEntry(), loadOperatingSnapshot()])
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
    // Let the selection watcher consume the initial values while initialization is still gated;
    // otherwise it would issue a second identical snapshot request after this first load.
    await nextTick()
    await Promise.all([loadCurrentEntry(), loadAssistantStatus(), loadOperatingSnapshot()])
    initialized = true
  } catch (error) {
    pageError.value = normalizeError(error, '经营助手初始化失败，请刷新后重试。')
  } finally {
    pageLoading.value = false
  }
}

/**
 * The page owns one read-only server snapshot for its selected authorized scope. Every chat
 * request must refer to this identifier; a late response for an older store/month is ignored.
 */
async function loadOperatingSnapshot(options: { announce?: boolean } = {}) {
  const requestVersion = ++snapshotRequestVersion
  const previousId = operatingSnapshot.value?.snapshotId || ''
  snapshotAbortController?.abort()
  const controller = new AbortController()
  snapshotAbortController = controller
  operatingSnapshot.value = null
  snapshotError.value = ''
  snapshotNotice.value = ''
  snapshotLoading.value = true
  try {
    const snapshot = await getOperatingSnapshot({
      storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
      month: selectedMonth.value,
    }, { signal: controller.signal })
    if (controller.signal.aborted || requestVersion !== snapshotRequestVersion || pageDisposed) return
    operatingSnapshot.value = snapshot
    if (options.announce) {
      snapshotNotice.value = snapshot.snapshotId === previousId
        ? '经营数据未变化，已重新确认当前快照。'
        : '经营数据已更新，已切换至新的经营快照。'
    }
  } catch (error) {
    if (controller.signal.aborted || requestVersion !== snapshotRequestVersion || pageDisposed) return
    operatingSnapshot.value = null
    snapshotError.value = error instanceof ApiError && error.code === 'OPERATING_SNAPSHOT_UNSUPPORTED'
      ? error.message
      : '经营快照服务暂不可用，可能是后端未更新或服务异常。请确认前端与后端已更新到同一版本，并重启后端后再试。'
  } finally {
    if (requestVersion === snapshotRequestVersion) snapshotLoading.value = false
  }
}

function currentSnapshotId() {
  return operatingSnapshot.value?.snapshotId || ''
}

function responseUsesCurrentSnapshot(response: AssistantChatResponse) {
  const displayedId = currentSnapshotId()
  return Boolean(displayedId)
    && String(response.localData?.snapshotId || '').trim() === displayedId
    && String(response.localData?.operatingSnapshot?.snapshotId || '').trim() === displayedId
}

function assertResponseUsesCurrentSnapshot(response: AssistantChatResponse) {
  if (!responseUsesCurrentSnapshot(response)) {
    throw new Error('服务器未返回与当前页面一致的经营快照，已阻止展示可能混用的数据。')
  }
}

function loadAssistantStatus() {
  if (assistantStatusRequest) return assistantStatusRequest

  const request = (async () => {
    try {
      const nextStatus = await getAssistantStatus()
      if (!pageDisposed) assistantStatus.value = nextStatus
    } catch {
      if (!pageDisposed) assistantStatus.value = null
    } finally {
      assistantStatusRequest = null
      if (!pageDisposed) scheduleUnconfiguredAssistantStatusRefresh()
    }
  })()

  assistantStatusRequest = request
  return request
}

function refreshAssistantStatusOnFocus() {
  if (pageDisposed || pageLoading.value || document.visibilityState !== 'visible') return

  const now = Date.now()
  if (now - lastAssistantStatusFocusRefreshAt < ASSISTANT_STATUS_FOCUS_DEBOUNCE_MS) return
  lastAssistantStatusFocusRefreshAt = now

  // A focus refresh is the single retry for an already-known unconfigured state. Cancel the
  // deferred retry so rapidly returning to the page cannot make a second request.
  if (assistantStatus.value?.configured === false) {
    assistantStatusUnconfiguredRetryUsed = true
    clearAssistantStatusRetryTimer()
  }
  void loadAssistantStatus()
}

function scheduleUnconfiguredAssistantStatusRefresh() {
  if (assistantStatus.value?.configured !== false) {
    assistantStatusUnconfiguredRetryUsed = false
    clearAssistantStatusRetryTimer()
    return
  }
  if (assistantStatusUnconfiguredRetryUsed || assistantStatusRetryTimer !== null) return

  assistantStatusUnconfiguredRetryUsed = true
  assistantStatusRetryTimer = window.setTimeout(() => {
    assistantStatusRetryTimer = null
    if (pageDisposed || document.visibilityState !== 'visible' || assistantStatus.value?.configured !== false) return
    void loadAssistantStatus()
  }, ASSISTANT_STATUS_UNCONFIGURED_RECHECK_DELAY_MS)
}

function clearAssistantStatusRetryTimer() {
  if (assistantStatusRetryTimer === null) return
  window.clearTimeout(assistantStatusRetryTimer)
  assistantStatusRetryTimer = null
}

function applyDefaults() {
  if (businessScope.isBoss.value) {
    selectedStoreId.value = ''
    selectedMonth.value = months.value[0] || currentMonth()
    return
  }
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
  if (!selectedMonth.value) {
    currentEntry.value = null
    entryState.value = 'idle'
    entryError.value = ''
    return
  }
  // Cancel any in-flight request from a previous store/month selection.
  if (entryAbortController) { entryAbortController.abort() }
  const controller = new AbortController()
  entryAbortController = controller
  entryState.value = 'loading'
  entryError.value = ''
  entryRequestId.value = ''
  metricLoading.value = true
  try {
    if (businessScope.isBoss.value) {
      // BOSS uses dashboard summary aggregating all authorized stores
      const dashboard = await getProfitDashboard({ month: selectedMonth.value })
      if (controller.signal.aborted) return
      dashboardSummary.value = dashboard.summary
      if (!dashboard.summary || dashboard.summary.entryCount === 0) {
        currentEntry.value = null
        entryState.value = 'empty'
      } else {
        currentEntry.value = {
          storeId: '',
          month: dashboard.summary.month,
          sales: dashboard.summary.sales,
          costSum: dashboard.summary.costSum,
          expenseSum: dashboard.summary.expenseSum,
          net: dashboard.summary.net,
          margin: dashboard.summary.margin,
        }
        entryState.value = 'ready'
        // Cost=0 with positive sales → data integrity warning
        dataIntegrityWarning.value = ''
        if (dashboard.summary.sales > 0 && (dashboard.summary.costSum === 0 || dashboard.summary.margin === 1)) {
          dataIntegrityWarning.value = '成本或费用数据不完整，当前净利率可能不准确'
        }
      }
    } else {
      const rows = await getProfitEntries({
        storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
        brandId: businessScope.isStoreManager.value ? businessScope.brandId.value ?? undefined : undefined,
        month: selectedMonth.value,
      })
      if (controller.signal.aborted) return
      if (!rows.length) {
        currentEntry.value = null
        entryState.value = 'empty'
      } else {
        currentEntry.value = rows[0]
        entryState.value = 'ready'
        dataIntegrityWarning.value = ''
        if ((rows[0].sales ?? 0) > 0 && ((rows[0].costSum ?? 0) === 0 || (rows[0].margin ?? 0) === 1)) {
          dataIntegrityWarning.value = '成本或费用数据不完整，当前净利率可能不准确'
        }
      }
    }
  } catch (error) {
    if (controller.signal.aborted) return
    currentEntry.value = null
    entryState.value = 'error'
    if (error instanceof ApiError) {
      if (error.status === 401) entryError.value = '登录已失效，请刷新后重新登录'
      else if (error.status === 403) entryError.value = '无权查看该门店的经营数据'
      else entryError.value = error.message || '经营数据加载失败，请稍后重试'
      entryRequestId.value = error.requestId || ''
    } else {
      entryError.value = '经营数据加载失败，请稍后重试'
    }
  } finally {
    metricLoading.value = false
  }
}

async function submitQuestion(preset?: string) {
  const question = String(preset || input.value).trim()
  if (!question || sending.value) return
  if (!currentSnapshotId()) {
    pageError.value = snapshotError.value || '经营快照尚未就绪，请稍后重试。'
    return
  }
  if (!effectiveStoreId.value && !businessScope.isBoss.value) {
    pageError.value = businessScope.configurationError.value || '请先选择门店和月份。'
    return
  }
  if (!selectedMonth.value) {
    pageError.value = '请先选择月份。'
    return
  }
  pageError.value = ''
  input.value = ''
  const requestMode = assistantRequestMode(question, assistantMode.value)
  const run = reactive<AssistantRun>({
    id: ++runId,
    question,
    mode: requestMode,
    status: 'loading-data',
    startedAt: Date.now(),
  })
  runs.value.push(run)
  sending.value = true
  startProgressClock()
  followLatest.value = true
  await nextTick(() => scrollToBottom(true))
  try {
    const localResponse = await askAssistant({
      message: question,
      mode: 'LOCAL',
      storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
      month: selectedMonth.value,
      snapshotId: currentSnapshotId(),
    })
    assertResponseUsesCurrentSnapshot(localResponse)
    run.response = localResponse

    if (requestMode !== 'LOCAL' && !localResponse.localData.insufficientData) {
      run.status = 'loading-ai'
      await nextTick(() => scrollToBottom())
      run.response = await askAssistant({
        message: question,
        mode: requestMode,
        storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
        month: selectedMonth.value,
        snapshotId: currentSnapshotId(),
      })
      assertResponseUsesCurrentSnapshot(run.response)
    }
    run.status = 'success'
  } catch (error) {
    run.status = run.response ? 'partial-error' : 'error'
    run.error = normalizeError(error, '经营助手暂时无法完成请求，请稍后重试。')
  } finally {
    refreshAssistantStatusAfterAnalysis(requestMode === 'LOCAL' ? 'LOCAL' : 'AI', run.response)
    sending.value = false
    stopProgressClock()
    await nextTick(() => focusCompletedAnswer(run))
  }
}

async function requestAiAnalysis(run: AssistantRun) {
  if (sending.value) return
  if (!currentSnapshotId() || !run.response || !responseUsesCurrentSnapshot(run.response)) {
    run.status = 'partial-error'
    run.error = snapshotError.value || '当前经营快照已失效，请重新拉取数据后再分析。'
    return
  }
  run.status = 'loading-ai'
  run.startedAt = Date.now()
  run.error = ''
  sending.value = true
  startProgressClock()
  try {
    run.response = await askAssistant({
      message: run.question,
      mode: 'AI',
      storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
      month: selectedMonth.value,
      snapshotId: currentSnapshotId(),
    })
    assertResponseUsesCurrentSnapshot(run.response)
    run.mode = 'AI'
    run.status = 'success'
  } catch (error) {
    run.status = run.response ? 'partial-error' : 'error'
    run.error = normalizeError(error, 'AI分析请求失败，请稍后重试。')
  } finally {
    refreshAssistantStatusAfterAnalysis('AI', run.response)
    sending.value = false
    stopProgressClock()
    await nextTick(() => focusCompletedAnswer(run))
  }
}

async function retryRun(run: AssistantRun) {
  if (run.status === 'partial-error' || run.mode === 'AI' || run.response?.selectedMode === 'AI') {
    await requestAiAnalysis(run)
    return
  }
  if (sending.value) return
  run.status = 'loading-data'
  run.startedAt = Date.now()
  run.error = ''
  sending.value = true
  startProgressClock()
  const requestMode = assistantRequestMode(run.question, run.mode)
  try {
    if (!currentSnapshotId()) throw new Error(snapshotError.value || '经营快照尚未就绪，请稍后重试。')
    run.response = await askAssistant({
      message: run.question,
      mode: 'LOCAL',
      storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
      month: selectedMonth.value,
      snapshotId: currentSnapshotId(),
    })
    assertResponseUsesCurrentSnapshot(run.response)
    if (requestMode !== 'LOCAL' && !run.response.localData.insufficientData) {
      run.status = 'loading-ai'
      await nextTick(() => scrollToBottom())
      run.response = await askAssistant({
        message: run.question,
        mode: requestMode,
        storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
        month: selectedMonth.value,
        snapshotId: currentSnapshotId(),
      })
      assertResponseUsesCurrentSnapshot(run.response)
    }
    run.status = 'success'
  } catch (error) {
    run.status = run.response ? 'partial-error' : 'error'
    run.error = normalizeError(error, '经营助手暂时无法完成请求，请稍后重试。')
  } finally {
    refreshAssistantStatusAfterAnalysis(requestMode === 'LOCAL' ? 'LOCAL' : 'AI', run.response)
    sending.value = false
    stopProgressClock()
    await nextTick(() => focusCompletedAnswer(run))
  }
}

function startProgressClock() {
  progressClock.value = Date.now()
  if (progressTimer !== null) return
  progressTimer = window.setInterval(() => {
    progressClock.value = Date.now()
  }, 1_000)
}

function stopProgressClock() {
  if (progressTimer === null) return
  window.clearInterval(progressTimer)
  progressTimer = null
  progressClock.value = Date.now()
}

function elapsedSeconds(run: AssistantRun) {
  return Math.max(0, Math.floor((progressClock.value - run.startedAt) / 1_000))
}

function aiProgressText(run: AssistantRun) {
  return elapsedSeconds(run) >= 8
    ? '正在整理原因与行动建议'
    : '正在分析经营变化与风险'
}

function focusCompletedAnswer(run: AssistantRun) {
  const container = resultScroll.value
  if (!container || !run.response?.aiAnalysis.available) {
    scrollToBottom()
    return
  }
  const target = container.querySelector<HTMLElement>(`[data-run-id="${run.id}"] .ai-result`)
  if (!target) {
    scrollToBottom()
    return
  }
  const containerRect = container.getBoundingClientRect()
  const targetRect = target.getBoundingClientRect()
  container.scrollTo({
    top: Math.max(0, container.scrollTop + targetRect.top - containerRect.top - 12),
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}

function refreshAssistantStatusAfterAnalysis(
  requestMode: AssistantMode,
  response?: AssistantChatResponse,
) {
  // A configured key is not proof that the last model response passed the quality gate.
  // Refresh after every possible AI path so a rejected response cannot coexist with a stale
  // green health marker after the user retries or refreshes this page.
  if (requestMode !== 'AI' && response?.selectedMode !== 'AI') return
  if (assistantStatus.value && response?.selectedMode === 'AI') {
    if (response.aiAnalysis.available) {
      assistantStatus.value = { ...assistantStatus.value, state: 'READY', lastErrorCode: null }
    } else if (response.error && isAnalysisResponseRejectedCode(response.error.code)) {
      assistantStatus.value = {
        ...assistantStatus.value,
        state: 'RESPONSE_REJECTED',
        lastErrorCode: response.error.code,
      }
    }
  }
  void loadAssistantStatus()
}

function requestClearConversation() {
  if (!runs.value.length || sending.value) return
  clearConfirmation.value = true
}

function clearConversation() {
  runs.value = []
  input.value = ''
  followLatest.value = true
  clearConfirmation.value = false
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
      storeId: businessScope.isBoss.value ? '' : effectiveStoreId.value,
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
  const marginIsSuspect = dataIntegrityWarning.value !== ''
  return [
    { label: '营业额', value: money(entry?.sales), tone: '' },
    { label: '成本', value: money(entry?.costSum), tone: '' },
    { label: '净利润', value: money(entry?.net), tone: numberValue(entry?.net) < 0 ? 'bad' : '' },
    { label: '净利率', value: percent(entry?.margin), tone: marginIsSuspect ? '' : numberValue(entry?.margin) < 0.05 ? 'bad' : 'good' },
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
    运营: 'SUPERVISOR',
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
  const code = run.response?.error?.code || ''
  if (assistantStatus.value?.configured === false || code === 'DEEPSEEK_NOT_CONFIGURED') {
    return 'AI分析服务尚未配置，本地经营数据仍可正常查询。'
  }
  const rejectionMessage = analysisRejectionMessage(code)
  if (rejectionMessage) return rejectionMessage
  return 'AI分析暂时不可用，请稍后重新分析。'
}

function aiUnavailableTitle(run: AssistantRun) {
  return analysisRejectionTitle(run.response?.error?.code || '') || 'AI分析暂时不可用'
}

const analysisResponseRejectedCodes = new Set([
  'SCHEMA_INVALID',
  'DATA_LIMITED_REQUIRED',
  'ANALYSIS_UNKNOWN_NUMERIC',
  'ANALYSIS_SNAPSHOT_CONTRADICTION',
  'ANALYSIS_ACTION_ROLE_INVALID',
  'ANALYSIS_QUALITY_REJECTED',
  // Compatibility with responses emitted by the already-running package during rollout.
  'DEEPSEEK_QUALITY_INSUFFICIENT',
  'UNKNOWN_AMOUNT',
  'CONTRADICTION',
  'ACTION_OWNER_ROLE',
  'ACTION_OWNER_ROLE_INVALID',
  'ANALYSIS_TYPE',
  'DATA_LIMITATIONS',
  'DATA_ACTIONS',
  'DATA_LIMITED_CAUSES',
])

function isAnalysisResponseRejectedCode(code: string) {
  return analysisResponseRejectedCodes.has(String(code || '').trim())
}

function analysisRejectionMessage(code: string) {
  switch (String(code || '').trim()) {
    case 'SCHEMA_INVALID':
      return '模型返回格式异常，已自动重试仍未成功，请稍后重试。'
    case 'DATA_LIMITED_REQUIRED':
    case 'ANALYSIS_TYPE':
    case 'DATA_LIMITATIONS':
    case 'DATA_ACTIONS':
    case 'DATA_LIMITED_CAUSES':
      return '经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据。'
    case 'ANALYSIS_UNKNOWN_NUMERIC':
    case 'UNKNOWN_AMOUNT':
      return '模型引用了当前经营数据中没有的金额或比例，系统已拦截该结果，请核对数据后重新分析。'
    case 'ANALYSIS_SNAPSHOT_CONTRADICTION':
    case 'CONTRADICTION':
      return '模型结论与当前经营数据不一致，系统已拦截该结果，请核对数据后重新分析。'
    case 'ANALYSIS_ACTION_ROLE_INVALID':
    case 'ACTION_OWNER_ROLE':
    case 'ACTION_OWNER_ROLE_INVALID':
      return '模型建议的处理角色不符合系统职责范围，系统已拦截该结果，请稍后重新分析。'
    case 'ANALYSIS_QUALITY_REJECTED':
    case 'DEEPSEEK_QUALITY_INSUFFICIENT':
      return '模型结果未通过必要的完整性校验，未展示不可靠结论，请稍后重新分析。'
    default:
      return ''
  }
}

function analysisRejectionTitle(code: string) {
  switch (String(code || '').trim()) {
    case 'SCHEMA_INVALID':
      return '模型格式异常'
    case 'DATA_LIMITED_REQUIRED':
    case 'ANALYSIS_TYPE':
    case 'DATA_LIMITATIONS':
    case 'DATA_ACTIONS':
    case 'DATA_LIMITED_CAUSES':
      return '经营数据待补全'
    case 'ANALYSIS_UNKNOWN_NUMERIC':
    case 'UNKNOWN_AMOUNT':
    case 'ANALYSIS_SNAPSHOT_CONTRADICTION':
    case 'CONTRADICTION':
    case 'ANALYSIS_ACTION_ROLE_INVALID':
    case 'ACTION_OWNER_ROLE':
    case 'ACTION_OWNER_ROLE_INVALID':
      return '分析结果已拦截'
    case 'ANALYSIS_QUALITY_REJECTED':
    case 'DEEPSEEK_QUALITY_INSUFFICIENT':
      return '分析结果待复核'
    default:
      return ''
  }
}

function servicePresentation(status: AssistantStatus): AssistantServicePresentation {
  const state = status.state || (status.configured ? 'CONFIGURED' : 'NOT_CONFIGURED')
  const provider = String(status.provider || 'DeepSeek').trim() || 'DeepSeek'
  if (state === 'READY') return { text: '分析服务正常', tone: 'ready' }
  if (state === 'CONFIGURED') return { text: `${provider} 已配置`, tone: 'configured' }
  if (state === 'RESPONSE_REJECTED') {
    return { text: analysisRejectionTitle(status.lastErrorCode || '') || '模型格式异常', tone: 'warning' }
  }
  if (state === 'UPSTREAM_ERROR') return { text: 'AI服务暂不可用', tone: 'warning' }
  return { text: 'AI服务未配置', tone: 'warning' }
}

function isDataLimitedAnalysis(analysis: AssistantChatResponse['aiAnalysis']) {
  return analysis.analysisType === 'DATA_LIMITED'
}

// AUTO keeps a direct request for operating facts on the MySQL-only path. The backend repeats
// this classification as the authorization boundary; doing it here prevents a slow model call
// for the clear, common cases before the request leaves the browser.
function assistantRequestMode(question: string, selectedMode: AssistantMode): AssistantMode {
  if (selectedMode !== 'AUTO' || !isDirectFactQuestion(question)) return selectedMode
  return 'LOCAL'
}

function isDirectFactQuestion(question: string) {
  const normalized = String(question || '').trim().toLowerCase()
  if (!normalized) return false
  const analysisIntent = [
    '为什么', '原因', '异常', '趋势', '对比', '建议', '改善', '风险', '怎么办', '怎么做',
    '分析', '变化', '表现', '优化', '如何',
  ]
  if (analysisIntent.some((keyword) => normalized.includes(keyword))) return false

  return [
    '多少', '金额', '营业额', '净利润', '净利率', '营收', '收入', '成本', '费用', '利润',
    '查询', '排名', '亏损', '本月', '上月', '这个月', 'revenue', 'profit', 'cost', 'amount',
  ].some((keyword) => normalized.includes(keyword))
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
  const code = value && typeof value === 'object'
    ? String((value as { code?: unknown }).code || '')
    : ''
  if (code === 'REQUEST_TIMEOUT') {
    return 'AI分析耗时较长，请稍后重试，或先使用‘查数据’获取经营事实。'
  }
  if (code === 'DEEPSEEK_NOT_CONFIGURED') {
    return 'AI分析服务尚未配置，本地经营数据仍可正常查询。'
  }
  const rejectionMessage = analysisRejectionMessage(code)
  if (rejectionMessage) return rejectionMessage
  const message = value instanceof Error ? value.message : String(value || '')
  if (!message || technicalErrors.some((item) => message.toLowerCase().includes(item)) || !/[\u3400-\u9fff]/.test(message)) return fallback
  return message
}
</script>

<template>
  <section class="page-panel store-assistant-page">
    <div class="assistant-page-header">
      <PageHeader
        title="门店经营助手"
      >
        <template #actions>
          <button class="secondary-button" type="button" :disabled="!runs.length || sending" @click="requestClearConversation">
            清空记录
          </button>
        </template>
      </PageHeader>

      <div v-if="pageError || snapshotError" class="page-error" role="alert">{{ pageError || snapshotError }}</div>
    </div>

    <section
      class="context-bar"
      :class="{
        'context-bar--single-store': businessScope.isStoreManager.value,
        'context-bar--conversation': runs.length,
      }"
      aria-label="当前分析范围"
    >
      <label v-if="businessScope.isBoss.value" class="context-field boss-scope">
        <span>范围</span>
        <strong>全部门店</strong>
        <span v-if="dashboardSummary" class="boss-store-count">{{ dashboardSummary.storeCount }} 家门店</span>
      </label>
      <label v-else-if="!businessScope.isStoreManager.value" class="context-field">
        <span>门店</span>
        <select v-model="selectedStoreId" :disabled="pageLoading || financeScope?.mode === 'OWN_STORE'">
          <option v-if="!accessibleStores.length" value="">暂无可选门店</option>
          <option v-for="store in accessibleStores" :key="store.id" :value="store.id">{{ store.name }}</option>
        </select>
      </label>
      <label class="context-field month-field">
        <span>经营月份</span>
        <select v-model="selectedMonth" :disabled="pageLoading || !months.length">
          <option v-for="month in months" :key="month" :value="month">{{ month }}</option>
        </select>
      </label>
      <div class="metric-strip" :class="{ muted: entryState === 'loading' }">
        <template v-if="entryState === 'loading'">
          <article v-for="i in 4" :key="'skel-'+i" class="metric-item metric-item--skeleton"><span>&nbsp;</span><strong>&nbsp;</strong></article>
        </template>
        <template v-else-if="entryState === 'error'">
          <div class="metric-error">
            <span>{{ entryError }}</span>
            <UiButton variant="ghost" size="sm" @click="loadCurrentEntry()">重试</UiButton>
          </div>
        </template>
        <template v-else-if="entryState === 'empty'">
          <article class="metric-item metric-item--empty"><span>该门店本月尚未录入经营数据</span></article>
        </template>
        <template v-else>
          <article v-for="card in mainMetrics" :key="card.label" class="metric-item" :class="card.tone">
            <span>{{ card.label }}</span>
            <strong>{{ card.value }}</strong>
          </article>
        </template>
      </div>
      <div v-if="dataIntegrityWarning && entryState === 'ready'" class="integrity-warning" role="alert">
        <AlertTriangle :size="14" /> {{ dataIntegrityWarning }}
      </div>
    </section>

    <section
      class="snapshot-panel"
      :class="{ unavailable: !operatingSnapshot }"
      :data-snapshot-id="operatingSnapshot?.snapshotId || undefined"
      aria-label="当前经营快照"
    >
      <template v-if="operatingSnapshot">
        <strong>经营快照</strong>
        <span>范围：{{ operatingSnapshot.storeScope.label }}</span>
        <span>快照：{{ operatingSnapshot.snapshotId }}</span>
        <span>确认时间：{{ formatUpdatedAt(operatingSnapshot.generatedAt) }}</span>
        <span v-if="snapshotNotice" class="snapshot-notice" role="status">{{ snapshotNotice }}</span>
      </template>
      <template v-else>
        <strong>经营快照</strong>
        <span>{{ snapshotLoading ? '正在确认当前授权范围…' : '快照未就绪，暂不可提问。' }}</span>
      </template>
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
        <span>{{ workspaceFullscreen ? '退出展开' : '展开' }}</span>
      </button>

      <div ref="resultScroll" class="result-stream" aria-live="polite" @scroll.passive="handleResultScroll">
        <div v-if="!runs.length" class="assistant-empty">
          <div class="empty-icon"><Sparkles :size="22" /></div>
          <h2>先查清经营数据，再让AI解释原因</h2>
          <p>数字查询不会调用模型；趋势、异常和改善建议会进入AI分析。</p>
          <p>AI 会基于全部授权门店的经营数据，解释原因并给出可以落地的行动建议。</p>
          <div class="empty-questions">
            <button v-for="question in quickQuestions" :key="question" type="button" @click="submitQuestion(question)">
              {{ question }}
            </button>
          </div>
        </div>

        <article v-for="run in runs" :key="run.id" class="assistant-run" :data-run-id="run.id">
          <div class="question-row">
            <span>我的问题</span>
            <p>{{ run.question }}</p>
          </div>

          <div v-if="run.status === 'loading-data' && !run.response" class="analysis-loading" role="status">
            <span class="loading-line wide"></span>
            <span class="loading-line"></span>
            <p>正在读取当前范围的经营数据…</p>
          </div>

          <div v-else-if="run.status === 'error'" class="run-error" role="alert">
            <AlertTriangle :size="18" />
            <span>{{ run.error }}</span>
            <button type="button" :disabled="sending" @click="retryRun(run)">重试</button>
          </div>

          <div v-else-if="run.response" class="assistant-answer">
            <section
              class="data-result run-facts"
              aria-label="经营数据"
              :data-snapshot-id="run.response.localData.snapshotId"
            >
              <header class="result-heading">
                <div>
                  <CheckCircle2 :size="18" />
                  <h3>经营数据</h3>
                  <span>经营数据已就绪</span>
                </div>
                <span>{{ run.response.localData.dataPeriod }} · {{ run.response.localData.dataScope }}</span>
              </header>
              <p class="data-summary">{{ run.response.localData.summary }}</p>
              <div v-if="run.response.localData.metrics.length" class="local-metrics">
                <article v-for="metric in visibleLocalMetrics(run.response.localData.metrics)" :key="metric.key">
                  <span>{{ metric.label }}</span>
                  <strong>{{ metric.displayValue }}</strong>
                  <small v-if="metric.comparison && metric.changeRate !== null">
                    {{ metric.comparison }} {{ Number(metric.changeRate) >= 0 ? '+' : '' }}{{ (Number(metric.changeRate) * 100).toFixed(1) }}%
                  </small>
                </article>
              </div>
              <div class="data-meta" aria-label="数据口径">
                <span><strong>来源</strong>{{ run.response.localData.source }}</span>
                <span><strong>更新时间</strong>{{ formatUpdatedAt(run.response.localData.updatedAt) }}</span>
                <span><strong>处理方式</strong>{{ run.response.selectedMode === 'AI' ? '数据库计算 + AI分析' : '仅数据库计算' }}</span>
                <span>本回答仅使用与页面一致的经营快照。</span>
              </div>
            </section>

            <section
              v-if="run.status === 'loading-ai'"
              class="ai-progress"
              role="status"
              data-testid="assistant-ai-progress"
            >
              <div class="ai-progress-copy">
                <span class="ai-progress-icon"><Sparkles :size="18" /></span>
                <div>
                  <strong>经营数据已显示，AI 继续分析</strong>
                  <p>{{ aiProgressText(run) }}</p>
                </div>
              </div>
              <span class="elapsed-time">已等待 {{ elapsedSeconds(run) }} 秒</span>
            </section>

            <section v-else-if="run.status === 'partial-error'" class="ai-unavailable" role="alert">
              <div><AlertTriangle :size="18" /><strong>经营数据已保留，AI 分析未完成</strong></div>
              <p>{{ run.error }}</p>
              <button type="button" :disabled="sending" @click="requestAiAnalysis(run)">
                <RefreshCcw :size="15" />重新分析
              </button>
            </section>

            <section v-else-if="run.response.localData.insufficientData" class="ai-result" aria-label="经营数据不足">
              <section class="analysis-data-limited" role="status" data-testid="assistant-data-limited">
                <div><AlertTriangle :size="18" /><strong>经营数据不足</strong></div>
                <p>经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据</p>
              </section>
              <section v-if="run.response.localData.insufficientData.verifiedFacts.length" class="analysis-block">
                <h4>已确认事实</h4>
                <ul><li v-for="item in run.response.localData.insufficientData.verifiedFacts" :key="item">{{ item }}</li></ul>
              </section>
              <section v-if="run.response.localData.insufficientData.nextSteps.length" class="analysis-block action-block">
                <h4>请先补全以下经营数据</h4>
                <ul><li v-for="item in run.response.localData.insufficientData.nextSteps" :key="item">{{ item }}</li></ul>
              </section>
              <footer class="data-meta">
                <span>经营数据不足，未调用 AI。</span>
                <button type="button" :disabled="snapshotLoading || sending" @click="loadOperatingSnapshot({ announce: true })">
                  <RefreshCcw :size="15" />重新分析
                </button>
              </footer>
            </section>

            <section v-else-if="run.response.aiAnalysis.available" class="ai-result" aria-label="AI经营分析">
              <header class="result-heading ai-heading">
                <div>
                  <Sparkles :size="18" />
                  <h3>AI经营分析</h3>
                  <span v-if="isDataLimitedAnalysis(run.response.aiAnalysis)" class="analysis-type-tag">经营数据不足</span>
                </div>
              </header>
              <section v-if="isDataLimitedAnalysis(run.response.aiAnalysis)" class="analysis-data-limited" role="status" data-testid="assistant-data-limited">
                <div><AlertTriangle :size="18" /><strong>经营数据不足</strong></div>
                <p>经营数据不足，暂不能判断原因，请先补全成本、费用或历史月份数据</p>
              </section>
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
              <details v-if="run.response.aiAnalysis.possibleCauses.length" class="analysis-disclosure">
                <summary>
                  <span>可能原因</span>
                  <small>需业务核实 · {{ run.response.aiAnalysis.possibleCauses.length }} 项</small>
                </summary>
                <ul class="cause-list">
                  <li v-for="item in run.response.aiAnalysis.possibleCauses" :key="`${item.cause}-${item.basis}`">
                    <div><strong>{{ item.cause }}</strong><span class="confidence-tag">可信度{{ confidenceText(item.confidence) }}</span></div>
                    <p>{{ item.basis }}</p>
                  </li>
                </ul>
              </details>
              <section class="analysis-block action-block">
                <h4>{{ isDataLimitedAnalysis(run.response.aiAnalysis) ? '请先补全以下经营数据' : '本周行动建议' }}</h4>
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
              <footer class="analysis-meta">
                <span>本次已调用 AI 分析。</span>
                <span>判断可信度：{{ confidenceText(run.response.aiAnalysis.confidence) }}</span>
                <span>{{ run.response.aiAnalysis.provider }} · {{ run.response.aiAnalysis.model }} · {{ latencyText(run.response.aiAnalysis.latencyMs) }}</span>
                <span v-if="maskedRequestId(run.response.aiAnalysis.requestId)">请求 {{ maskedRequestId(run.response.aiAnalysis.requestId) }}</span>
              </footer>
            </section>

            <section v-else-if="run.response.error" class="ai-unavailable" role="status">
              <div><AlertTriangle :size="18" /><strong>{{ aiUnavailableTitle(run) }}</strong></div>
              <p>{{ aiUnavailableMessage(run) }}</p>
              <button type="button" :disabled="sending" @click="requestAiAnalysis(run)">
                <RefreshCcw :size="15" />重新分析
              </button>
            </section>

            <div v-else-if="run.mode === 'LOCAL' || run.mode === 'AUTO'" class="local-followup">
              <span>当前只查询了真实经营数据。</span>
              <button type="button" :disabled="sending" @click="requestAiAnalysis(run)">
                <Sparkles :size="15" />使用深度分析原因和建议
              </button>
            </div>
          </div>
        </article>
      </div>

      <footer class="assistant-composer">
        <div class="mode-switch" aria-label="回答模式">
          <button
            v-for="mode in ([['LOCAL', '查数据'], ['AI', '深度分析'], ['AUTO', '自动']] as const)"
            :key="mode[0]"
            type="button"
            :class="{ active: assistantMode === mode[0] }"
            :aria-pressed="assistantMode === mode[0]"
            :disabled="sending"
            @click="assistantMode = mode[0]"
          >{{ mode[1] }}</button>
          <span>{{ modeHint }}</span>
          <span
            v-if="assistantServicePresentation"
            class="service-status"
            :class="assistantServicePresentation.tone"
            role="status"
            aria-live="polite"
            data-testid="assistant-service-status"
          >
            {{ assistantServicePresentation.text }}
          </span>
        </div>
        <form class="question-form" @submit.prevent="submitQuestion()">
          <label class="sr-only" for="assistant-question">经营问题</label>
          <input
            id="assistant-question"
            v-model.trim="input"
            type="text"
            :placeholder="`问${selectedStoreName}，例如：7月净利润为什么变化？`"
            :disabled="sending || pageLoading || snapshotLoading || !operatingSnapshot"
            autocomplete="off"
          />
          <button type="submit" :disabled="sending || pageLoading || snapshotLoading || !operatingSnapshot || !input.trim()">
            <Send :size="17" />{{ sending ? '处理中' : '发送' }}
          </button>
        </form>
      </footer>
    </section>

    <Teleport to="body">
      <div
        v-if="clearConfirmation"
        class="todo-dialog-backdrop"
        role="presentation"
        @click.self="clearConfirmation = false"
      >
        <section class="todo-dialog" role="alertdialog" aria-modal="true" aria-labelledby="clear-conversation-title">
          <header>
            <div>
              <h2 id="clear-conversation-title">确认清空对话记录</h2>
              <p>不会删除经营数据、已创建待办或操作日志。</p>
            </div>
          </header>
          <ModalFooter>
            <UiButton variant="secondary" type="button" @click="clearConfirmation = false">取消</UiButton>
            <UiButton variant="primary" type="button" @click="clearConversation">确认清空</UiButton>
          </ModalFooter>
        </section>
      </div>

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
  grid-template-rows: auto auto auto minmax(0, 1fr);
  gap: 10px;
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
  grid-template-columns: minmax(190px, 1fr) 150px minmax(520px, 2.4fr);
  gap: 12px;
  align-items: end;
  padding: 10px 12px;
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
.metric-item--skeleton { }
.metric-item--skeleton span { background: var(--ds-soft); border-radius: 4px; min-height: 14px; width: 60%; }
.metric-item--skeleton strong { background: var(--ds-soft); border-radius: 4px; min-height: 22px; width: 80%; margin-top: 6px; }
.metric-item--empty { grid-column: 1 / -1; border-left: none; padding: 8px 14px; color: var(--ds-muted); }
.metric-error { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; padding: 6px 14px; color: var(--ds-danger); font-size: 14px; }
.boss-scope strong { font-size: 15px; color: var(--ds-ink); }
.boss-store-count { margin-left: 8px; color: var(--ds-muted); font-size: 12px; }
.integrity-warning { display: flex; align-items: center; gap: 6px; padding: 6px 14px; color: #b45309; background: #fffbeb; border-top: 1px solid var(--ds-line); font-size: 13px; }

.snapshot-panel {
  display: flex;
  min-width: 0;
  padding: 8px 12px;
  align-items: center;
  gap: 8px 16px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-size: 12px;
  flex-wrap: wrap;
}
.snapshot-panel strong { color: var(--ds-ink); font-size: 13px; }
.snapshot-panel span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.snapshot-panel .snapshot-notice { color: var(--ds-primary-hover); }
.snapshot-panel.unavailable { color: var(--ds-muted); }

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
  width: auto;
  min-width: 44px;
  height: 44px;
  padding: 0 12px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ds-line-strong);
  border-radius: 6px;
  background: var(--ds-surface);
  color: var(--ds-secondary);
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
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
  padding: 20px 24px 28px;
  background: var(--ds-surface-muted);
}
.assistant-empty { display: grid; width: 100%; max-width: 680px; min-height: 100%; margin: 0 auto; padding: 48px 16px 24px; place-content: center; justify-items: center; text-align: center; }
.empty-icon { display: grid; width: 44px; height: 44px; place-items: center; border-radius: 50%; background: var(--ds-primary-soft); color: var(--ds-primary-hover); }
.assistant-empty h2 { margin: 14px 0 6px; font-size: 20px; }
.assistant-empty p { margin: 0; color: var(--ds-secondary); }
.empty-questions { display: flex; margin-top: 20px; gap: 8px; flex-wrap: wrap; justify-content: center; }
.empty-questions button { min-height: 40px; padding: 0 14px; }
.empty-questions button:hover { border-color: var(--ds-primary-hover); background: var(--ds-primary-soft); }

.assistant-run { width: min(1280px, 100%); margin: 0 auto 28px; }
.question-row { display: flex; align-items: flex-start; justify-content: flex-start; gap: 10px; margin-bottom: 10px; }
.question-row span { padding-top: 10px; color: var(--ds-muted); font-size: 12px; }
.question-row p { max-width: min(760px, 86%); margin: 0; padding: 10px 14px; border-radius: 6px; background: #e5f4f1; color: var(--ds-ink); line-height: 1.6; }

.assistant-answer {
  overflow: hidden;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: var(--ds-surface);
}

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
.assistant-answer > .data-result,
.assistant-answer > .ai-result,
.assistant-answer > .ai-unavailable,
.assistant-answer > .local-followup {
  margin: 0;
  border: 0;
  border-radius: 0;
}
.data-result,
.ai-result { padding: 20px 22px; }
.assistant-answer > .ai-result,
.assistant-answer > .ai-unavailable,
.assistant-answer > .local-followup { border-top: 1px solid var(--ds-line); }
.result-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.result-heading > div { display: flex; align-items: center; gap: 8px; }
.result-heading h3 { margin: 0; font-size: 16px; }
.result-heading > span { color: var(--ds-muted); font-size: 12px; }
.data-summary { max-width: 76ch; margin: 14px 0; color: var(--ds-ink); font-size: 15px; line-height: 1.7; text-wrap: pretty; }
.data-meta { display: flex; margin-top: 12px; padding-top: 10px; gap: 20px; flex-wrap: wrap; border-top: 1px solid var(--ds-line); color: var(--ds-secondary); font-size: 12px; }
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
.data-meta button {
  display: inline-flex;
  min-height: 36px;
  padding: 0 11px;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--ds-primary-hover);
  border-radius: 6px;
  background: var(--ds-surface);
  color: var(--ds-primary-hover);
}
.data-meta button:disabled { cursor: not-allowed; opacity: .65; }

.ai-heading { padding-bottom: 14px; border-bottom: 1px solid var(--ds-line); color: var(--ds-primary-hover); }
.analysis-type-tag { display: inline-flex; min-height: 22px; padding: 0 7px; align-items: center; border-radius: 999px; background: var(--ds-primary-soft); color: var(--ds-primary-hover); font-size: 11px; font-weight: 600; }
.analysis-data-limited { margin-top: 16px; padding: 12px 14px; border: 1px solid var(--ds-line); border-radius: 6px; background: var(--ds-primary-soft); }
.analysis-data-limited > div { display: flex; align-items: center; gap: 8px; color: var(--ds-primary-hover); }
.analysis-data-limited p { margin: 6px 0 0; color: var(--ds-secondary); line-height: 1.6; }
.analysis-block { margin-top: 18px; }
.analysis-block h4 { margin: 0 0 8px; color: var(--ds-ink); font-size: 14px; }
.analysis-block h4 small { margin-left: 6px; color: var(--ds-muted); font-weight: 400; }
.analysis-block p,
.analysis-block ul,
.analysis-block ol { margin: 0; color: var(--ds-secondary); line-height: 1.75; }
.analysis-block p,
.analysis-block li { max-width: 78ch; text-wrap: pretty; }
.analysis-block ul,
.analysis-block ol { padding-left: 22px; }
.conclusion-block { padding: 16px 18px; border-radius: 6px; background: var(--ds-primary-soft); }
.conclusion-block p { color: var(--ds-ink); font-size: 15px; }
.analysis-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 36px; }
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
.analysis-disclosure { margin-top: 18px; border-top: 1px solid var(--ds-line); }
.analysis-disclosure summary { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: 16px; cursor: pointer; color: var(--ds-ink); font-size: 14px; font-weight: 700; }
.analysis-disclosure summary small { color: var(--ds-muted); font-size: 12px; font-weight: 400; }
.analysis-disclosure .cause-list { margin: 0 0 6px; }
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
.analysis-meta { display: flex; gap: 6px 18px; flex-wrap: wrap; padding-top: 12px; border-top: 1px solid var(--ds-line); }

.ai-progress {
  display: flex;
  min-height: 96px;
  padding: 18px 22px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  border-top: 1px solid var(--ds-line);
  background: var(--ds-primary-soft);
}
.ai-progress-copy { display: flex; min-width: 0; align-items: center; gap: 12px; }
.ai-progress-icon { display: grid; width: 38px; height: 38px; flex: none; place-items: center; border-radius: 50%; background: var(--ds-surface); color: var(--ds-primary-hover); animation: pulse 1.4s ease-in-out infinite; }
.ai-progress-copy strong { display: block; color: var(--ds-ink); font-size: 14px; }
.ai-progress-copy p { margin: 4px 0 0; color: var(--ds-secondary); font-size: 13px; }
.elapsed-time { flex: none; color: var(--ds-primary-hover); font-size: 13px; font-variant-numeric: tabular-nums; }

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

.assistant-composer { position: relative; z-index: 3; flex: none; padding: 12px 16px 16px; border-top: 1px solid var(--ds-line); background: var(--ds-surface); }
.mode-switch { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; }
.mode-switch button { min-height: 34px; padding: 0 12px; }
.mode-switch button.active { border-color: var(--ds-primary-hover); background: var(--ds-primary-hover); color: #fff; }
.mode-switch > span { margin-left: 6px; color: var(--ds-muted); font-size: 12px; }
.mode-switch .service-status { display: inline-flex; margin-left: auto; align-items: center; gap: 6px; white-space: nowrap; }
.mode-switch .service-status::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--ds-warning); }
.mode-switch .service-status.ready::before { background: var(--ds-success); }
.mode-switch .service-status.configured::before { background: var(--ds-secondary); }
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
  .store-assistant-page {
    height: auto;
    min-height: 100%;
    grid-template-rows: auto auto auto minmax(560px, 1fr);
    overflow-y: auto !important;
  }
  .assistant-workspace { min-height: 560px; }
  .context-bar { grid-template-columns: 1fr; }
  .context-bar.context-bar--conversation { grid-template-columns: minmax(0, 1fr) 132px; align-items: end; }
  .context-bar--conversation .metric-strip,
  .context-bar--conversation .integrity-warning { display: none; }
  .context-bar--conversation .boss-store-count { display: none; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .snapshot-panel { align-items: flex-start; flex-direction: column; gap: 4px; }
  .snapshot-panel span { max-width: 100%; }
  .metric-item:nth-child(odd) { border-left: 0; }
  .result-stream { padding: 12px; }
  .workspace-toggle span { display: none; }
  .workspace-toggle { padding: 0; }
  .question-row p { max-width: 88%; }
  .local-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .local-metrics article:nth-child(5n) { border-right: 1px solid var(--ds-line); }
  .local-followup { align-items: stretch; flex-direction: column; }
  .mode-switch { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 4px; }
  .mode-switch button { padding: 0 8px; }
  .mode-switch > span:not(.service-status) { display: none; }
  .mode-switch .service-status { grid-column: 1 / -1; margin-left: 0; }
  .question-form { gap: 8px; }
  .question-form button { min-width: 88px; padding: 0 12px; }
  .action-copy dl,
  .todo-dialog-content dl { grid-template-columns: 1fr; }
  .action-add-button { width: calc(100% - 42px); justify-content: center; }
  .assistant-run:first-of-type { padding-top: 44px; }
  .data-result,
  .ai-result { padding: 16px; }
  .ai-progress { min-height: 0; padding: 15px 16px; align-items: flex-start; flex-direction: column; gap: 10px; }
  .elapsed-time { padding-left: 50px; }
  .result-heading { align-items: flex-start; flex-direction: column; gap: 6px; }
}

@media (prefers-reduced-motion: reduce) {
  .loading-line,
  .ai-progress-icon,
  .assistant-workspace.is-fullscreen { animation: none; }
}
</style>
