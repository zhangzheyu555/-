<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Send } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { askAssistant, type AssistantChatRequest } from '../api/assistant'
import { getProfitEntries, getProfitMonths, type ProfitEntry } from '../api/finance'
import { getStores, type StoreInfo } from '../api/operations'
import { useAuthStore } from '../stores/auth'
import { normalizeBrandName } from '../utils/brand'

interface ChatMessage {
  id: number
  role: 'assistant' | 'user'
  text: string
  tone?: 'welcome' | 'local' | 'ai' | 'error'
  /** When true, this is a LOCAL result that can be analyzed further */
  canDeepAnalyze?: boolean
  /** The question text that generated this message, for deep analysis replay */
  questionText?: string
}

interface StoreMetric {
  revenue: number
  sales: number
  net: number
  margin: number
  cost: number
  expense: number
  status: '正常' | '异常'
  hasData: boolean
}

interface QuestionIntent {
  key: string
  label: string
  field?: keyof Pick<StoreMetric, 'revenue' | 'sales' | 'net' | 'margin' | 'cost' | 'expense'>
  percent?: boolean
}

interface ResolvedQuestion {
  store: StoreInfo | null
  storeSource: 'question' | 'default'
  months: string[]
  resolvedMonth: string
  monthSource: 'question' | 'relative' | 'current' | 'trend'
  intent: QuestionIntent
  trend: boolean
  entries: ProfitEntry[]
}

const route = useRoute()
const auth = useAuthStore()

const stores = ref<StoreInfo[]>([])
const monthOptions = ref<string[]>([])
const selectedStoreId = ref(typeof route.query.storeId === 'string' ? route.query.storeId : '')
const selectedMonth = ref(validMonth(route.query.month) || currentMonth())
const currentEntries = ref<ProfitEntry[]>([])
const trendEntries = ref<ProfitEntry[]>([])
const messages = ref<ChatMessage[]>([])
const input = ref(typeof route.query.q === 'string' ? route.query.q : '')
const assistantMode = ref<'AUTO' | 'LOCAL' | 'AI'>('AUTO')
const pageLoading = ref(false)
const metricLoading = ref(false)
const sending = ref(false)
const pageError = ref('')
const chatScroll = ref<HTMLElement | null>(null)
let messageId = 0
let initialized = false

const monthNameMap: Record<string, number> = {
  一: 1,
  二: 2,
  两: 2,
  三: 3,
  四: 4,
  五: 5,
  六: 6,
  七: 7,
  八: 8,
  九: 9,
  十: 10,
  十一: 11,
  十二: 12,
}

const intentDefinitions: QuestionIntent[] = [
  { key: 'revenue', label: '营业额', field: 'revenue' },
  { key: 'sales', label: '营业总收入', field: 'sales' },
  { key: 'net_profit', label: '净利润', field: 'net' },
  { key: 'net_margin', label: '净利率', field: 'margin', percent: true },
  { key: 'cost', label: '成本', field: 'cost' },
  { key: 'expense', label: '费用', field: 'expense' },
  { key: 'inventory', label: '库存' },
  { key: 'inspection', label: '巡店' },
  { key: 'salary', label: '工资' },
]

const accessibleStores = computed(() => {
  if (auth.role !== 'STORE_MANAGER' || !auth.storeScope.length) return stores.value
  return stores.value.filter((store) => auth.storeScope.includes(store.id))
})

const selectedStore = computed(() => (
  accessibleStores.value.find((store) => store.id === selectedStoreId.value)
  || accessibleStores.value[0]
  || null
))

const currentEntry = computed(() => currentEntries.value.find((entry) => entry.storeId === selectedStore.value?.id))

const selectedStoreName = computed(() => selectedStore.value?.name || '当前门店')
const selectedBrandName = computed(() => normalizeBrandName(selectedStore.value?.brandName || ''))
const selectedMonthText = computed(() => monthText(selectedMonth.value))
const assistantTitle = computed(() => `${selectedStoreName.value} 经营助手`)

const storeMetric = computed<StoreMetric>(() => metricFromEntry(currentEntry.value))

const metricCards = computed(() => [
  { label: '营业额', value: money(storeMetric.value.revenue), tone: '' },
  { label: '净利润', value: money(storeMetric.value.net), tone: storeMetric.value.net >= 0 ? 'good' : 'bad' },
  { label: '净利率', value: percent(storeMetric.value.margin), tone: storeMetric.value.net >= 0 ? 'good' : 'bad' },
  { label: '状态', value: storeMetric.value.hasData ? storeMetric.value.status : '暂无数据', tone: storeMetric.value.status === '正常' ? 'good' : 'bad' },
])

const quickQuestions = computed(() => {
  const name = selectedStoreName.value
  const month = selectedMonthText.value
  return [
    `${name}${month}经营表现怎么样？`,
    `${name}净利润为什么变化？`,
    `${name}哪项成本最需要关注？`,
    `与近三个月相比有什么异常？`,
    `给出本周最重要的三个行动建议`,
  ]
})

const inputPlaceholder = computed(() => `问 ${selectedStoreName.value}，例如：本月营业额 / 净利润 / 成本异常`)

onMounted(async () => {
  await loadPage()
  if (input.value.trim()) {
    await submitQuestion()
  }
})

watch(selectedMonth, async () => {
  if (!initialized) return
  await loadCurrentEntries()
  await loadTrendEntries()
  resetConversation()
})

watch(selectedStoreId, async () => {
  if (!initialized) return
  await loadTrendEntries()
  resetConversation()
})

async function loadPage() {
  pageLoading.value = true
  pageError.value = ''
  try {
    const [storeRows, months] = await Promise.all([
      getStores(),
      getProfitMonths().catch(() => [] as string[]),
    ])
    stores.value = storeRows
    monthOptions.value = normalizeMonths(months)
    if (!validMonth(selectedMonth.value)) {
      selectedMonth.value = monthOptions.value[0] || currentMonth()
    }
    await loadCurrentEntries()
    applyDefaultStore()
    await loadTrendEntries()
    resetConversation()
    initialized = true
  } catch (error) {
    pageError.value = normalizeUserError(error, '门店经营数据加载失败，请刷新后重试。')
    resetConversation()
    initialized = true
  } finally {
    pageLoading.value = false
  }
}

async function loadCurrentEntries() {
  metricLoading.value = true
  try {
    currentEntries.value = await getProfitEntries({ month: selectedMonth.value })
  } catch {
    currentEntries.value = []
  } finally {
    metricLoading.value = false
  }
}

async function loadTrendEntries() {
  const store = selectedStore.value
  if (!store) {
    trendEntries.value = []
    return
  }
  const months = (monthOptions.value.length ? monthOptions.value : [selectedMonth.value]).slice(0, 8)
  const rows = await Promise.all(months.map((month) => (
    getProfitEntries({ month, storeId: store.id }).catch(() => [] as ProfitEntry[])
  )))
  trendEntries.value = rows.flat()
}

function applyDefaultStore() {
  const storeIds = new Set(accessibleStores.value.map((store) => store.id))
  const routeStoreId = typeof route.query.storeId === 'string' ? route.query.storeId : ''
  if (routeStoreId && storeIds.has(routeStoreId)) {
    selectedStoreId.value = routeStoreId
    return
  }
  if (auth.role === 'STORE_MANAGER' && auth.storeScope.length) {
    const scopedStore = accessibleStores.value.find((store) => auth.storeScope.includes(store.id))
    if (scopedStore) {
      selectedStoreId.value = scopedStore.id
      return
    }
  }
  const storeWithData = currentEntries.value.find((entry) => storeIds.has(entry.storeId))
  selectedStoreId.value = storeWithData?.storeId || accessibleStores.value[0]?.id || ''
}

function clearConversation() {
  resetConversation()
}

function resetConversation() {
  messages.value = [{
    id: nextMessageId(),
    role: 'assistant',
    tone: 'welcome',
    text: '你好，我可以帮你分析当前门店的营业额、净利润、成本异常和趋势。',
  }]
  void nextTick(scrollToBottom)
}

async function submitQuickQuestion(question: string) {
  input.value = question
  await submitQuestion(question)
}

async function submitQuestion(preset?: string) {
  const question = (preset || input.value).trim()
  if (!question) {
    pushMessage('assistant', '请输入问题。', 'error')
    return
  }
  if (!selectedStore.value) {
    pushMessage('assistant', '请选择门店。', 'error')
    return
  }
  if (sending.value) return

  input.value = ''
  const store = selectedStore.value
  const month = selectedMonth.value
  const history = assistantHistory()
  pushMessage('user', question)
  sending.value = true
  try {
    const response = await askAssistant({
      message: question,
      history,
      dataContext: buildDataContext(question, '', await quickResolve(question)),
      mode: assistantMode.value,
      storeId: store.id,
      month,
    })

    const isAi = response.aiUsed === true || (response.deepSeekAvailable === true && !response.fallback)
    const isLocal = !isAi || response.fallback === true

    if (isLocal) {
      // Show local answer with deep analysis option
      const localText = response.localAnswer || response.answer
      if (localText && localText.trim()) {
        const msg = pushMessageRet('assistant', localText, 'local')
        msg.canDeepAnalyze = response.fallback !== true || response.aiUsed !== true
        msg.questionText = question
      }
      if (response.fallback === true) {
        const reason = response.fallbackReason || response.deepSeekError
        console.warn('[Assistant] DeepSeek fallback', {
          fallbackReason: reason,
          requestId: response.requestId,
          source: response.source,
        })
        pushMessage('assistant', `AI 分析暂时不可用（${reason || '服务异常'}）。已显示本地数据结果。`, 'error')
      } else if (!response.aiUsed && !response.fallback) {
        // LOCAL mode - show "深入分析" button hint
        // The canDeepAnalyze flag on the message will show the button
      }
    } else {
      // AI mode: show combined analysis
      const aiText = response.deepSeekAnswer || response.answer
      if (aiText && aiText.trim()) {
        pushMessage('assistant', aiText, 'ai')
      }
    }
  } catch (error) {
    console.error('[Assistant] chat request failed', error)
    pushMessage('assistant', normalizeUserError(error, '门店经营助手暂时无法完成查询，请稍后重试。'), 'error')
  } finally {
    sending.value = false
  }
}

/** Deep analysis: re-send the same question in AI mode */
async function submitDeepAnalysis(message: ChatMessage) {
  if (!message.questionText || sending.value) return
  const question = message.questionText
  if (!selectedStore.value) return

  // Remove the "深入分析" flag from the message
  message.canDeepAnalyze = false

  const store = selectedStore.value
  const history = assistantHistory()
  sending.value = true
  try {
    const response = await askAssistant({
      message: question,
      history,
      dataContext: buildDataContext(question, '', await quickResolve(question)),
      mode: 'AI',
      storeId: store.id,
      month: selectedMonth.value,
    })

    const aiText = response.deepSeekAnswer || response.answer
    if (aiText && aiText.trim()) {
      pushMessage('assistant', aiText, 'ai')
    } else if (response.fallback === true) {
      pushMessage('assistant', `AI 分析暂时不可用（${response.fallbackReason || '服务异常'}）。`, 'error')
    }
  } catch (error) {
    console.error('[Assistant] deep analysis failed', error)
    pushMessage('assistant', 'DeepSeek 分析请求失败，请稍后重试。', 'error')
  } finally {
    sending.value = false
  }
}

/** Quick resolve for data context without showing duplicate local answer */
async function quickResolve(question: string): Promise<ResolvedQuestion> {
  return resolveQuestion(question)
}

function pushMessageRet(role: ChatMessage['role'], text: string, tone?: ChatMessage['tone']): ChatMessage {
  const msg: ChatMessage = {
    id: nextMessageId(),
    role,
    text,
    tone,
  }
  messages.value.push(msg)
  void nextTick(scrollToBottom)
  return msg
}

function assistantHistory(): AssistantChatRequest['history'] {
  return messages.value
    .filter((message) => message.text && message.tone !== 'welcome' && message.tone !== 'error')
    .slice(-8)
    .map((message) => ({
      role: message.role,
      content: message.text,
    }))
}

function pushMessage(role: ChatMessage['role'], text: string, tone?: ChatMessage['tone']) {
  messages.value.push({
    id: nextMessageId(),
    role,
    text,
    tone,
  })
  void nextTick(scrollToBottom)
}

function scrollToBottom() {
  if (chatScroll.value) {
    chatScroll.value.scrollTop = chatScroll.value.scrollHeight
  }
}

async function resolveQuestion(question: string): Promise<ResolvedQuestion> {
  const storeInfo = resolveQuestionStore(question)
  const monthInfo = resolveQuestionMonths(question)
  const entries = await loadQuestionEntries(storeInfo.store, monthInfo.months)
  return {
    store: storeInfo.store,
    storeSource: storeInfo.source,
    months: monthInfo.months,
    resolvedMonth: monthInfo.months[0] || selectedMonth.value,
    monthSource: monthInfo.source,
    intent: resolveIntent(question),
    trend: monthInfo.months.length > 1 || wantsTrend(question),
    entries,
  }
}

function resolveQuestionStore(question: string): { store: StoreInfo | null; source: 'question' | 'default' } {
  const normalizedQuestion = normalizeSearchText(question)
  const matched = accessibleStores.value.find((store) => {
    const names = [
      store.name,
      store.name.replace(/店$/, ''),
      store.code,
      store.id,
    ].filter((value) => value && value.length >= 2)
    return names.some((name) => normalizedQuestion.includes(normalizeSearchText(name)))
  })
  return matched
    ? { store: matched, source: 'question' }
    : { store: selectedStore.value, source: 'default' }
}

function resolveQuestionMonths(question: string): { months: string[]; source: ResolvedQuestion['monthSource'] } {
  const explicit = parseExplicitMonths(question)
  if (explicit.length) {
    return { months: explicit, source: 'question' }
  }
  const base = parseMonthValue(selectedMonth.value)
  if (/最近三个月/.test(question)) {
    return {
      months: [shiftMonth(base, -2), shiftMonth(base, -1), selectedMonth.value],
      source: 'trend',
    }
  }
  if (/上上月|上上个月/.test(question)) {
    return { months: [shiftMonth(base, -2)], source: 'relative' }
  }
  if (/上月|上个月/.test(question)) {
    return { months: [shiftMonth(base, -1)], source: 'relative' }
  }
  if (/本月|这个月|当前月|当月/.test(question)) {
    return { months: [selectedMonth.value], source: 'current' }
  }
  if (wantsTrend(question)) {
    const months = availableTrendMonths()
    return { months: months.length ? months : [selectedMonth.value], source: 'trend' }
  }
  return { months: [selectedMonth.value], source: 'current' }
}

function parseExplicitMonths(question: string) {
  const months = new Set<string>()
  const defaultYear = Number(selectedMonth.value.split('-')[0]) || new Date().getFullYear()
  let match: RegExpExecArray | null

  const fullRange = /(20\d{2})[-/.年]\s*(1[0-2]|0?[1-9])\s*(?:-|到|至|~)\s*(20\d{2})[-/.年]\s*(1[0-2]|0?[1-9])/g
  while ((match = fullRange.exec(question))) {
    addMonthRange(months, toMonth(Number(match[1]), Number(match[2])), toMonth(Number(match[3]), Number(match[4])))
  }

  const range = /(?:(20\d{2})\s*年?)?\s*(1[0-2]|0?[1-9]|[一二两三四五六七八九十]{1,3})\s*(?:-|到|至|~)\s*(?:(20\d{2})\s*年?)?\s*(1[0-2]|0?[1-9]|[一二两三四五六七八九十]{1,3})\s*月/g
  while ((match = range.exec(question))) {
    const year = Number(match[1] || match[3] || defaultYear)
    const start = monthNumber(match[2])
    const end = monthNumber(match[4])
    if (start && end) addMonthRange(months, toMonth(year, start), toMonth(year, end))
  }

  const full = /(20\d{2})\s*[-/.年]\s*(1[0-2]|0?[1-9])\s*月?/g
  while ((match = full.exec(question))) {
    months.add(toMonth(Number(match[1]), Number(match[2])))
  }

  const numericMonth = /(^|[^\d])(1[0-2]|0?[1-9])\s*月份?/g
  while ((match = numericMonth.exec(question))) {
    months.add(toMonth(defaultYear, Number(match[2])))
  }

  const chineseMonth = /([一二两三四五六七八九十]{1,3})\s*月份?/g
  while ((match = chineseMonth.exec(question))) {
    const month = monthNumber(match[1])
    if (month) months.add(toMonth(defaultYear, month))
  }

  return Array.from(months)
}

function resolveIntent(question: string): QuestionIntent {
  const rules: Array<[RegExp, QuestionIntent]> = [
    [/净利率|利润率/, intentDefinitions[3]],
    [/净利润|净利|利润|盈利/, intentDefinitions[2]],
    [/营业总收入|总收入|流水/, intentDefinitions[1]],
    [/营业额|营收|收入|revenue/i, intentDefinitions[0]],
    [/成本|成本异常|原材料/, intentDefinitions[4]],
    [/费用|报销/, intentDefinitions[5]],
    [/库存|叫货|入库|出库/, intentDefinitions[6]],
    [/巡店|不合格|整改/, intentDefinitions[7]],
    [/工资|人效|人工/, intentDefinitions[8]],
  ]
  return rules.find(([pattern]) => pattern.test(question))?.[1] || intentDefinitions[0]
}

async function loadQuestionEntries(store: StoreInfo | null, months: string[]) {
  if (!store) return []
  const rows = await Promise.all(months.map((month) => (
    getProfitEntries({ month, storeId: store.id }).catch((error) => {
      console.error('[Assistant Local Data Error]', { storeId: store.id, month, error })
      return [] as ProfitEntry[]
    })
  )))
  return rows.flat()
}

function buildDataContext(question: string, localAnswer: string, resolved: ResolvedQuestion) {
  const store = resolved.store
  const rows = resolved.entries
    .filter((entry) => !store || entry.storeId === store.id)
    .sort((a, b) => a.month.localeCompare(b.month))
    .map((entry) => {
      const rowMetric = metricFromEntry(entry)
      return [
        entry.month,
        entry.brandName || selectedBrandName.value,
        entry.storeName || store?.name || '',
        Math.round(rowMetric.revenue),
        Math.round(rowMetric.sales),
        Math.round(rowMetric.net),
        percent(rowMetric.margin),
        Math.round(rowMetric.cost),
        Math.round(rowMetric.expense),
        rowMetric.status,
      ].join(',')
    })
  return [
    '页面：门店经营助手',
    `用户问题：${question}`,
    `defaultMonth：${selectedMonth.value}`,
    `resolvedMonth：${resolved.resolvedMonth}`,
    `resolvedMonths：${resolved.months.join(',')}`,
    `monthSource：${resolved.monthSource}`,
    `storeId：${store?.id || ''}`,
    `storeName：${store?.name || ''}`,
    `resolvedStoreId：${store?.id || ''}`,
    `resolvedStoreName：${store?.name || ''}`,
    `brandName：${store ? normalizeBrandName(store.brandName || '') : selectedBrandName.value}`,
    `intent：${resolved.intent.key}`,
    `intentLabel：${resolved.intent.label}`,
    `本地基础回答：\n${localAnswer}`,
    '查询数据CSV：月份,品牌,门店,营业额,营业总收入,净利润,净利率,成本合计,费用合计,状态',
    rows.join('\n') || '暂无查询数据',
  ].join('\n')
}

function normalizeSearchText(value: string) {
  return String(value || '').toLowerCase().replace(/\s+/g, '')
}

function availableTrendMonths() {
  const months = monthOptions.value.filter((month) => /^\d{4}-\d{2}$/.test(month))
  return Array.from(new Set(months)).sort()
}

function parseMonthValue(month: string) {
  const [year, value] = month.split('-').map((part) => Number(part))
  return {
    year: year || new Date().getFullYear(),
    month: value || 1,
  }
}

function shiftMonth(base: { year: number; month: number }, offset: number) {
  const date = new Date(base.year, base.month - 1 + offset, 1)
  return toMonth(date.getFullYear(), date.getMonth() + 1)
}

function addMonthRange(months: Set<string>, start: string, end: string) {
  const startValue = parseMonthValue(start)
  const endValue = parseMonthValue(end)
  let cursor = new Date(startValue.year, startValue.month - 1, 1)
  const last = new Date(endValue.year, endValue.month - 1, 1)
  if (cursor > last) {
    cursor = new Date(endValue.year, endValue.month - 1, 1)
    const reverseLast = new Date(startValue.year, startValue.month - 1, 1)
    while (cursor <= reverseLast) {
      months.add(toMonth(cursor.getFullYear(), cursor.getMonth() + 1))
      cursor = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1)
    }
    return
  }
  while (cursor <= last) {
    months.add(toMonth(cursor.getFullYear(), cursor.getMonth() + 1))
    cursor = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1)
  }
}

function toMonth(year: number, month: number) {
  return `${year}-${String(month).padStart(2, '0')}`
}

function monthNumber(value: string) {
  if (/^\d+$/.test(value)) {
    const month = Number(value)
    return month >= 1 && month <= 12 ? month : 0
  }
  return monthNameMap[value] || 0
}

function metricFromEntry(entry?: ProfitEntry): StoreMetric {
  if (!entry) {
    return {
      revenue: 0,
      sales: 0,
      net: 0,
      margin: 0,
      cost: 0,
      expense: 0,
      status: '异常',
      hasData: false,
    }
  }
  const revenue = numberValue(entry.income ?? entry.sales)
  const sales = numberValue(entry.sales ?? entry.income)
  const net = numberValue(entry.net)
  const margin = entry.margin !== undefined ? numberValue(entry.margin) : (revenue > 0 ? net / revenue : 0)
  return {
    revenue,
    sales,
    net,
    margin,
    cost: numberValue(entry.costSum),
    expense: numberValue(entry.expenseSum),
    status: net >= 0 ? '正常' : '异常',
    hasData: true,
  }
}

function wantsTrend(question: string) {
  return /各月|每月|趋势|月趋势|最近三个月|全部月份|所有月份|收入走势|历史/.test(question)
}

function numberValue(value?: number) {
  return Number(value || 0)
}

function money(value: number) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

function percent(value: number) {
  return `${(Number(value || 0) * 100).toFixed(1)}%`
}

function monthText(month: string) {
  const parts = month.split('-')
  const value = Number(parts[1] || 0)
  return value ? `${value}月` : month
}

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

function validMonth(value: unknown) {
  const text = typeof value === 'string' ? value : ''
  return /^\d{4}-\d{2}$/.test(text) ? text : ''
}

function normalizeMonths(values: string[]) {
  const months = values.filter((month) => /^\d{4}-\d{2}$/.test(month))
  return months.length ? Array.from(new Set(months)) : [currentMonth()]
}

function nextMessageId() {
  messageId += 1
  return messageId
}

const technicalErrorPatterns = [
  'handler dispatch failed',
  'java.lang',
  'noclassdeffounderror',
  'org.springframework',
  'stacktrace',
  'exception',
  'errorresponse$builder',
]

function normalizeUserError(value: unknown, fallback: string) {
  const message = value instanceof Error ? value.message : String(value || '')
  const lower = message.toLowerCase()
  if (!message || technicalErrorPatterns.some((pattern) => lower.includes(pattern))) {
    return fallback
  }
  return message
}
</script>

<template>
  <section class="page-panel store-assistant-page">
    <div class="assistant-title-row">
      <h2>门店经营助手</h2>
      <button class="ghost-button clear-button" type="button" @click="clearConversation">清空对话</button>
    </div>

    <div v-if="pageError" class="error-box">{{ pageError }}</div>

    <section class="assistant-storebar">
      <div class="store-context-card">
        <div class="context-eyebrow">当前门店</div>
        <div class="store-pickers">
          <label class="picker-field">
            <span>门店</span>
            <select v-model="selectedStoreId" :disabled="pageLoading || auth.role === 'STORE_MANAGER'">
              <option v-if="!accessibleStores.length" value="">暂无可选门店</option>
              <option v-for="store in accessibleStores" :key="store.id" :value="store.id">
                {{ store.name }}
              </option>
            </select>
          </label>
          <label class="picker-field">
            <span>月份</span>
            <select v-model="selectedMonth" :disabled="pageLoading || !monthOptions.length">
              <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
            </select>
          </label>
        </div>
      </div>

      <div class="metric-card-grid" :class="{ loading: metricLoading }">
        <article v-for="card in metricCards" :key="card.label" class="metric-mini" :class="card.tone">
          <span>{{ card.label }}</span>
          <b>{{ card.value }}</b>
        </article>
      </div>
    </section>

    <section class="content-card assistant-chat-card">
      <div class="chat-card-head">
        <h3>{{ assistantTitle }}</h3>
      </div>

      <div ref="chatScroll" class="assistant-messages" aria-live="polite">
        <div
          v-for="message in messages"
          :key="message.id"
          class="chat-message"
          :class="[message.role, message.tone]"
        >
          <div class="message-name">
            {{ message.role === 'user' ? '我' : message.tone === 'ai' ? 'DeepSeek 经营分析' : message.tone === 'local' ? '系统数据' : '经营助手' }}
          </div>
          <div class="message-body">{{ message.text }}</div>
          <button
            v-if="message.canDeepAnalyze && message.role === 'assistant' && message.tone === 'local'"
            class="deep-analyze-button"
            type="button"
            :disabled="sending"
            @click="submitDeepAnalysis(message)"
          >
            让 DeepSeek 深入分析
          </button>
        </div>
        <div v-if="sending" class="chat-message assistant thinking">
          <div class="message-name">DeepSeek 分析</div>
          <div class="message-body">正在分析当前门店数据...</div>
        </div>
      </div>

      <div class="assistant-chips" aria-label="模式切换">
        <span class="mode-label">模式：</span>
        <button
          v-for="mode in ([['AUTO','自动'],['LOCAL','查数据'],['AI','AI分析']] as const)"
          :key="mode[0]"
          class="question-chip mode-chip"
          :class="{ active: assistantMode === mode[0] }"
          type="button"
          :disabled="sending"
          @click="assistantMode = mode[0]"
        >
          {{ mode[1] }}
        </button>
      </div>

      <div class="assistant-chips" aria-label="快捷问题">
        <button
          v-for="question in quickQuestions"
          :key="question"
          class="question-chip"
          type="button"
          :disabled="sending || pageLoading"
          @click="submitQuickQuestion(question)"
        >
          {{ question }}
        </button>
      </div>

      <form class="assistant-input" @submit.prevent="submitQuestion()">
        <input
          v-model.trim="input"
          type="text"
          :placeholder="inputPlaceholder"
          :disabled="sending || pageLoading"
          autocomplete="off"
        />
        <button type="submit" :disabled="sending || pageLoading">
          <Send :size="16" />
          {{ sending ? '发送中...' : '发送' }}
        </button>
      </form>
    </section>
  </section>
</template>

<style scoped>
.store-assistant-page {
  display: flex;
  width: 100%;
  height: calc(100vh - 202px);
  min-height: 410px;
  flex-direction: column;
  gap: 9px;
  overflow: hidden;
  padding: 0;
  max-width: 1180px;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.assistant-title-row {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 32px;
}

.assistant-title-row h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
  line-height: 1.2;
}

.clear-button {
  min-height: 32px;
  padding: 6px 12px;
}

.assistant-storebar {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: minmax(280px, 420px) 1fr;
  gap: 12px;
  align-items: stretch;
  height: 82px;
  min-height: 0;
}

.store-context-card,
.metric-card-grid,
.assistant-chat-card {
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(31, 35, 48, 0.05);
}

.store-context-card {
  min-height: 0;
  padding: 9px 12px;
}

.context-eyebrow {
  margin-bottom: 3px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
}

.store-pickers {
  display: flex;
  gap: 8px;
}

.picker-field {
  display: flex;
  flex: 1 1 150px;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.picker-field span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
}

.store-pickers select {
  min-height: 30px;
  width: 100%;
  max-width: 100%;
  padding: 5px 9px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  font-weight: 800;
  outline: none;
}

.store-pickers select:focus,
.assistant-input input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.metric-card-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(112px, 1fr));
  gap: 8px;
  min-height: 0;
  padding: 9px 12px;
}

.metric-mini {
  min-width: 0;
  padding: 7px 10px;
  border-radius: 9px;
  background: var(--bg);
}

.metric-mini span {
  display: block;
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
  white-space: nowrap;
}

.metric-mini b {
  display: block;
  margin-top: 3px;
  overflow: hidden;
  color: var(--ink);
  font-size: 17px;
  font-variant-numeric: tabular-nums;
  font-weight: 900;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-mini.good b {
  color: var(--good);
}

.metric-mini.bad b {
  color: var(--bad);
}

.assistant-chat-card {
  display: flex;
  min-height: 0;
  height: auto;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
  padding: 10px 12px;
}

.chat-card-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.chat-card-head h3 {
  margin: 0;
  font-size: 16px;
  line-height: 1.2;
}

.assistant-messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 9px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #f7f8fb;
}

.chat-message {
  max-width: 82%;
  margin-bottom: 7px;
}

.chat-message.user {
  margin-left: auto;
  text-align: right;
}

.message-name {
  margin-bottom: 4px;
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
}

.message-body {
  display: inline-block;
  padding: 7px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.58;
  text-align: left;
  white-space: pre-line;
}

.chat-message.user .message-body {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.chat-message.local .message-body {
  border-left: 4px solid var(--primary);
}

.chat-message.ai .message-body {
  border-left: 4px solid var(--good);
}

.chat-message.error .message-body {
  border-left: 4px solid var(--warn);
  color: #8a5a00;
}

.assistant-chips {
  flex-shrink: 0;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.question-chip {
  min-height: 28px;
  padding: 5px 10px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: #fff;
  color: var(--ink);
  font-size: 12px;
  font-weight: 800;
  transition: 0.15s;
}

.question-chip:hover:not(:disabled) {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.question-chip:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

.mode-label {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
  align-self: center;
}

.mode-chip.active {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.deep-analyze-button {
  margin-top: 6px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 28px;
  padding: 4px 12px;
  border: 1px solid var(--good);
  border-radius: 999px;
  background: #e8f5ed;
  color: var(--good);
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  transition: 0.15s;
}

.deep-analyze-button:hover:not(:disabled) {
  background: var(--good);
  color: #fff;
}

.deep-analyze-button:disabled {
  opacity: 0.58;
  cursor: not-allowed;
}

.assistant-input {
  flex-shrink: 0;
  display: flex;
  gap: 8px;
}

.assistant-input input {
  flex: 1;
  min-height: 38px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font-size: 14px;
  outline: none;
  transition: 0.15s;
}

.assistant-input button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 20px;
  border: 0;
  border-radius: 10px;
  background: var(--primary);
  color: #fff;
  font-size: 14px;
  font-weight: 900;
}

.assistant-input button:disabled {
  background: #c9cdd6;
}

@media (max-width: 980px) {
  .store-assistant-page {
    height: auto;
    min-height: 0;
    overflow: visible;
  }

  .assistant-storebar {
    grid-template-columns: 1fr;
    height: auto;
    max-height: none;
  }

  .metric-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .assistant-chat-card {
    min-height: 430px;
  }
}

@media (max-height: 680px) and (min-width: 981px) {
  .store-assistant-page {
    height: calc(100vh - 212px);
    min-height: 360px;
  }

  .assistant-title-row {
    min-height: 30px;
  }

  .assistant-storebar {
    height: 70px;
  }

  .assistant-chat-card {
    padding: 9px 11px;
  }

  .assistant-messages {
    padding: 8px;
  }
}

@media (max-width: 720px) {
  .store-assistant-page {
    gap: 9px;
  }

  .assistant-title-row,
  .assistant-input {
    align-items: stretch;
    flex-direction: column;
  }

  .store-pickers,
  .picker-field,
  .store-pickers select,
  .metric-card-grid,
  .assistant-input button {
    width: 100%;
  }

  .metric-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .assistant-chat-card {
    min-height: 360px;
    padding: 10px;
  }

  .chat-message {
    max-width: 100%;
  }

  .assistant-input input,
  .assistant-input button {
    min-height: 38px;
  }
}
</style>
