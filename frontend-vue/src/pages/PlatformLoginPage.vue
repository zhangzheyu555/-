<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ExternalLink, X } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import SearchableSingleSelect from '../components/common/SearchableSingleSelect.vue'
import { apiGet, apiPost, apiPut, http } from '../api/http'
import { downloadBlob } from '../api/reports'
import { useAuthStore } from '../stores/auth'
import { useForegroundReload } from '../composables/useForegroundReload'

interface QmaiConfigView {
  configured: boolean
  brand: string
  openIdMasked: string
  grantCodeMasked: string
  openKeySet: boolean
  consoleAccountMasked: string
  consolePasswordSet: boolean
  consoleTokenSet: boolean
  baseUrl: string
  version: string
  shops: string
  missing: string[]
  statusText: string
  updatedBy: string | null
  updatedAt: string | null
}

const auth = useAuthStore()
// Personal permission overrides never elevate non-BOSS/SUPERVISOR users to credential management.
const canManage = computed(() => auth.hasPermission('platform.manage')
  && ['BOSS', 'SUPERVISOR'].includes(auth.role))
const canExport = computed(() => auth.hasPermission('finance.export')
  && ['BOSS', 'FINANCE'].includes(auth.role))
const isFinanceViewer = computed(() => auth.role === 'FINANCE')

/* ---------------- 品牌切换（每品牌独立一套企迈凭证与数据） ---------------- */
const BRANDS = [
  { key: 'ruguo', label: '茹菓' },
] as const
type BrandKey = (typeof BRANDS)[number]['key']
const brand = ref<BrandKey>('ruguo')
const brandLabel = computed(() => BRANDS.find((b) => b.key === brand.value)?.label || '')

const qmai = ref<QmaiConfigView | null>(null)
const qmaiStatus = computed(() => (qmai.value?.configured ? '正常' : '未配置'))

// 企迈只读平台可点击配置；其余平台暂为展示。
const otherPlatforms = [
  { name: '美团', status: '未配置' },
  { name: '饿了么', status: '正常' },
  { name: '抖音', status: '未配置' },
  { name: '京东', status: '未配置' },
]

const modalOpen = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')

const form = reactive({
  openId: '',
  grantCode: '',
  openKey: '',
  baseUrl: '',
  version: '',
  shops: '',
  consoleAccount: '',
  consolePassword: '',
  consoleToken: '',
})
let qmaiLoadSerial = 0
let turnoverLoadSerial = 0

async function loadQmai() {
  const serial = ++qmaiLoadSerial
  const requestedBrand = brand.value
  try {
    const path = canManage.value ? '/api/qmai/config' : '/api/qmai/status'
    const nextQmai = await apiGet<QmaiConfigView>(`${path}?brand=${requestedBrand}`)
    if (serial !== qmaiLoadSerial || brand.value !== requestedBrand) return false
    qmai.value = nextQmai
    return true
  } catch {
    // 同一品牌的后台同步失败时保留上次成功读取的配置状态。
    return false
  }
}

function switchBrand(k: BrandKey) {
  if (brand.value === k) {
    return
  }
  brand.value = k
  clearRecipeUsage()
  qmai.value = null
  turnover.value = null
  turnoverError.value = ''
  income.value = null
  incomeError.value = ''
  itemShopFilter.value = ''
  businessDate.value = ''
  resetBackfillState()
  activeTab.value = 'turnover'
  void loadLocalPlatformState()
}

function openModal() {
  if (!canManage.value) {
    return
  }
  error.value = ''
  success.value = ''
  form.openId = ''
  form.grantCode = ''
  form.openKey = ''
  form.baseUrl = qmai.value?.baseUrl || 'https://openapi.qmai.cn'
  form.version = qmai.value?.version || '1.0'
  form.shops = qmai.value?.shops || ''
  form.consoleAccount = ''
  form.consolePassword = ''
  form.consoleToken = ''
  modalOpen.value = true
}

function closeModal() {
  modalOpen.value = false
}

async function submit() {
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    qmai.value = await apiPut<QmaiConfigView>(`/api/qmai/config?brand=${brand.value}`, { ...form })
    success.value = '企迈凭证已保存。'
    markFresh()
    setTimeout(() => {
      closeModal()
      if (!isConsoleBrand.value) void loadTurnover()
    }, 900)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

/* ---------------- 企迈营业额展示 ---------------- */
interface TurnoverRow {
  shopCode: string
  shopName: string
  bizDate: string
  validOrderCount: number
  totalAmountSum: number
  incomeSum: number
  costSum: number
  refundSum: number
  profitSum: number
}
interface ItemRow {
  shopCode: string
  shopName: string
  itemName: string
  categoryName: string
  num: number
  incomeSum: number
  costSum: number
  refundSum: number
  refundNum: number
}
interface TurnoverSummary {
  mode: string
  note: string
  days: number
  generatedAt: string
  totalAmount: number
  income: number
  cost: number
  refund: number
  profit: number
  orderCount: number
  shops: TurnoverRow[]
  items: ItemRow[]
}

interface QmaiRevenueRow {
  storeId: string
  storeName?: string
  orderCount: number
  revenue: number
  refund: number
  cost: number
}

interface QmaiProductRow {
  storeId: string
  storeName?: string
  itemName: string
  categoryName: string
  quantity: number
  refundQuantity: number
  revenue: number
  refund: number
}

const turnover = ref<TurnoverSummary | null>(null)
const turnoverLoading = ref(false)
const turnoverError = ref('')

/* ---------------- 令牌复用通道：商户后台营业收入（按支付渠道，预留） ---------------- */
interface IncomeChannel {
  name: string
  revenue: number
  count: number
}
interface ConsoleIncome {
  mode: string
  note: string
  rangeLabel: string
  generatedAt: string
  totalRevenue: number
  totalCount: number
  channels: IncomeChannel[]
}
// 是否走后台令牌通道（非默认品牌：单店、按支付渠道）
const isConsoleBrand = computed(() => brand.value !== 'ruguo')
// 数据面板是否显示：默认品牌看 openapi 凭证；令牌品牌看是否已粘贴令牌
// 本地已导入的企迈快照可在未配置实时凭证时安全只读查看；不会触发外网请求。
const panelReady = computed(() => isConsoleBrand.value ? !!qmai.value?.consoleTokenSet : true)
const income = ref<ConsoleIncome | null>(null)
const incomeLoading = ref(false)
const incomeError = ref('')
const anyLoading = computed(() =>
  turnoverLoading.value || incomeLoading.value || posLoading.value)

async function loadIncome() {
  if (!qmai.value?.consoleTokenSet) {
    income.value = null
    incomeError.value = '尚未粘贴商户后台登录令牌（qm_seller_token）。请点企迈卡片配置。'
    return
  }
  incomeLoading.value = true
  incomeError.value = ''
  try {
    income.value = await apiGet<ConsoleIncome>(
      `/api/qmai/console-income?month=${month.value}&brand=${brand.value}`, { timeout: 120000 })
    if (income.value?.mode !== 'LIVE') {
      incomeError.value = income.value?.note || '后台营业额暂不可用。'
    }
  } catch (e) {
    incomeError.value = e instanceof Error ? e.message : '拉取后台营业额失败。'
  } finally {
    incomeLoading.value = false
  }
}

function exportIncomeExcel() {
  const inc = income.value
  if (!inc?.channels?.length) {
    return
  }
  const csvText = (s: string) => `"${(s || '').replace(/"/g, '""')}"`
  const header = ['支付渠道', '营业额', '订单数', '占比']
  const lines = [header.join(',')]
  for (const c of inc.channels) {
    const pct = inc.totalRevenue > 0 ? ((c.revenue / inc.totalRevenue) * 100).toFixed(1) + '%' : ''
    lines.push([csvText(c.name), c.revenue, c.count, pct].join(','))
  }
  lines.push(['合计', inc.totalRevenue, inc.totalCount, '100%'].join(','))
  downloadCsv(lines,
    `${brandLabel.value}_企迈营业额_${inc.rangeLabel}_${inc.generatedAt?.slice(0, 10) || ''}.csv`)
}
const incomePct = (c: IncomeChannel) =>
  income.value && income.value.totalRevenue > 0
    ? ((c.revenue / income.value.totalRevenue) * 100).toFixed(1) + '%'
    : '—'

function formatShanghaiDate(date: Date) {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((entry) => entry.type === type)?.value || ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

const today = formatShanghaiDate(new Date())
const latestClosedBusinessDate = formatShanghaiDate(new Date(Date.now() - 24 * 60 * 60 * 1000))
const currentMonth = today.slice(0, 7)
// 选中的月份，格式 YYYY-MM，默认当前月
const month = ref(currentMonth)
// 单日历史筛选仅用于营业额、商品销售及其导出；留空时查看整月。
const businessDate = ref('')
const monthLabel = computed(() => {
  const [y, m] = month.value.split('-')
  return `${y}年${Number(m)}月`
})
const turnoverRangeLabel = computed(() => businessDate.value || monthLabel.value)
const isCurrentMonthOrLater = computed(() => month.value >= currentMonth)

interface QmaiBackfillBatch {
  id: number | string
  status: string
  targetMonth: string
  totalTasks?: number
  completedTasks?: number
  failedTasks?: number
  totalDays?: number
  processedDays?: number
  failedDays?: number
  dailyRows?: number
  productRows?: number
  errorSummary?: string | null
  message?: string | null
  createdAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

const backfillBatch = ref<QmaiBackfillBatch | null>(null)
const backfillSubmitting = ref(false)
const backfillError = ref('')
let backfillRequestSerial = 0
let backfillPollTimer: ReturnType<typeof setTimeout> | null = null

const BACKFILL_RUNNING_STATUSES = new Set(['PENDING', 'QUEUED', 'RUNNING', 'PROCESSING'])
const BACKFILL_SUCCESS_STATUSES = new Set(['SUCCESS', 'SUCCEEDED', 'COMPLETED'])
const backfillRunning = computed(() =>
  !!backfillBatch.value && BACKFILL_RUNNING_STATUSES.has(String(backfillBatch.value.status || '').toUpperCase()))
const backfillBusy = computed(() => backfillSubmitting.value || backfillRunning.value)
const backfillTotal = computed(() =>
  Number(backfillBatch.value?.totalDays ?? backfillBatch.value?.totalTasks ?? 0))
const backfillProcessed = computed(() =>
  Number(backfillBatch.value?.processedDays ?? backfillBatch.value?.completedTasks ?? 0))
const backfillFailed = computed(() =>
  Number(backfillBatch.value?.failedDays ?? backfillBatch.value?.failedTasks ?? 0))
const backfillUnitLabel = computed(() =>
  backfillBatch.value?.totalDays === undefined ? '项' : '天')
const backfillPercent = computed(() => {
  if (!backfillTotal.value) return backfillRunning.value ? 0 : 100
  return Math.min(100, Math.round((backfillProcessed.value / backfillTotal.value) * 100))
})
const backfillStatusLabel = computed(() => {
  if (backfillSubmitting.value) return '正在创建补取任务'
  const status = String(backfillBatch.value?.status || '').toUpperCase()
  if (BACKFILL_RUNNING_STATUSES.has(status)) return '正在补取历史数据'
  if (BACKFILL_SUCCESS_STATUSES.has(status)) return '历史数据补取完成'
  if (['PARTIAL', 'PARTIAL_FAILED', 'PARTIAL_SUCCESS'].includes(status)) return '部分日期补取失败'
  if (status === 'FAILED') return '历史数据补取失败'
  return status ? '历史数据补取状态待确认' : ''
})
const backfillFailureMessage = computed(() => {
  if (!backfillBatch.value || (!backfillFailed.value
    && !['FAILED', 'PARTIAL', 'PARTIAL_FAILED'].includes(String(backfillBatch.value.status || '').toUpperCase()))) {
    return ''
  }
  return backfillBatch.value.errorSummary || backfillBatch.value.message
    || `有 ${backfillFailed.value} 天补取失败，请稍后重试。`
})

function isBackfillBatch(value: unknown): value is QmaiBackfillBatch {
  return !!value && typeof value === 'object' && !Array.isArray(value)
    && 'status' in value && 'targetMonth' in value
}

function stopBackfillPolling() {
  if (backfillPollTimer) {
    clearTimeout(backfillPollTimer)
    backfillPollTimer = null
  }
}

function resetBackfillState() {
  stopBackfillPolling()
  backfillRequestSerial += 1
  backfillBatch.value = null
  backfillSubmitting.value = false
  backfillError.value = ''
}

function scheduleBackfillPoll(requestedBrand: BrandKey, requestedMonth: string) {
  stopBackfillPolling()
  backfillPollTimer = setTimeout(() => {
    if (brand.value === requestedBrand && month.value === requestedMonth) {
      void loadLatestBackfill(true)
    }
  }, 1500)
}

async function loadLatestBackfill(silent = false) {
  const serial = ++backfillRequestSerial
  const requestedBrand = brand.value
  const requestedMonth = month.value
  const wasRunning = backfillRunning.value
  if (!silent) backfillError.value = ''
  try {
    const batch = await apiGet<QmaiBackfillBatch | null>(
      `/api/qmai/sync/batches/latest?brand=${encodeURIComponent(requestedBrand)}&month=${encodeURIComponent(requestedMonth)}`,
    )
    if (serial !== backfillRequestSerial
      || brand.value !== requestedBrand || month.value !== requestedMonth) return
    backfillError.value = ''
    backfillBatch.value = isBackfillBatch(batch) ? batch : null
    if (backfillRunning.value) {
      scheduleBackfillPoll(requestedBrand, requestedMonth)
    } else if (wasRunning
      && BACKFILL_SUCCESS_STATUSES.has(String(backfillBatch.value?.status || '').toUpperCase())) {
      void loadTurnover()
    }
  } catch (e) {
    if (serial === backfillRequestSerial
      && brand.value === requestedBrand && month.value === requestedMonth) {
      backfillError.value = e instanceof Error ? e.message : '读取历史补取进度失败。'
      if (silent && backfillRunning.value) {
        scheduleBackfillPoll(requestedBrand, requestedMonth)
      }
    }
  }
}

async function startBackfill() {
  if (!canManage.value || backfillBusy.value) return
  stopBackfillPolling()
  const serial = ++backfillRequestSerial
  const requestedBrand = brand.value
  const requestedMonth = month.value
  const requestedBusinessDate = businessDate.value
  backfillSubmitting.value = true
  backfillBatch.value = null
  backfillError.value = ''
  try {
    const dayQuery = requestedBusinessDate
      ? `&businessDate=${encodeURIComponent(requestedBusinessDate)}`
      : ''
    const batch = await apiPost<QmaiBackfillBatch>(
      `/api/qmai/sync/backfill?brand=${encodeURIComponent(requestedBrand)}&month=${encodeURIComponent(requestedMonth)}${dayQuery}`,
      {},
      { timeout: 60000 },
    )
    if (serial !== backfillRequestSerial
      || brand.value !== requestedBrand || month.value !== requestedMonth) return
    backfillBatch.value = isBackfillBatch(batch) ? batch : null
    if (!backfillBatch.value) {
      backfillError.value = '补取任务已提交，但没有收到批次进度，请稍后重新进入页面查看。'
      return
    }
    if (backfillRunning.value) {
      scheduleBackfillPoll(requestedBrand, requestedMonth)
    } else if (BACKFILL_SUCCESS_STATUSES.has(String(backfillBatch.value.status || '').toUpperCase())) {
      void loadTurnover()
    }
  } catch (e) {
    if (serial === backfillRequestSerial) {
      backfillError.value = e instanceof Error ? e.message : '提交历史数据补取任务失败。'
    }
  } finally {
    if (serial === backfillRequestSerial) backfillSubmitting.value = false
  }
}

const money = (n: number) =>
  '¥' + (Number(n) || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

// 毛利率 = 毛利 / 实收
const rate = (row: TurnoverRow) =>
  row.incomeSum > 0 ? row.profitSum / row.incomeSum : 0
const ratePct = (row: TurnoverRow) => (rate(row) * 100).toFixed(1) + '%'
// 毛利率低于该阈值标红（可按需调整）
const LOW_MARGIN = 0.4
const isLowMargin = (row: TurnoverRow) => rate(row) < LOW_MARGIN

// 表格排序
type SortKey = 'income' | 'cost' | 'profit' | 'rate' | 'refund'
const sortKey = ref<SortKey>('income')
const sortAsc = ref(false)
const sortedShops = computed(() => {
  const list = [...(turnover.value?.shops ?? [])]
  const val = (r: TurnoverRow) => {
    switch (sortKey.value) {
      case 'income': return r.incomeSum
      case 'cost': return r.costSum
      case 'profit': return r.profitSum
      case 'refund': return r.refundSum
      case 'rate': return rate(r)
      default: return r.incomeSum
    }
  }
  list.sort((a, b) => (sortAsc.value ? val(a) - val(b) : val(b) - val(a)))
  return list
})
function setSort(key: SortKey) {
  if (sortKey.value === key) {
    sortAsc.value = !sortAsc.value
  } else {
    sortKey.value = key
    sortAsc.value = false
  }
}
const sortArrow = (key: SortKey) =>
  sortKey.value === key ? (sortAsc.value ? ' ▲' : ' ▼') : ''

/* ---------------- 企迈商品销售（同一次读取的数据，切标签即看） ---------------- */
const activeTab = ref<'turnover' | 'items' | 'usage' | 'pos'>('turnover')
watch(isFinanceViewer, (financeOnly) => {
  if (financeOnly && (activeTab.value === 'usage' || activeTab.value === 'pos')) {
    activeTab.value = 'turnover'
  }
}, { immediate: true })
const snapshotExportDenied = computed(() => !isConsoleBrand.value
  && (activeTab.value === 'turnover' || activeTab.value === 'items')
  && !canExport.value)

/* ---------------- POS：企迈优惠券核销 ---------------- */
const posLoading = ref(false)
const posError = ref('')
const posResult = ref<Record<string, unknown> | null>(null)
const posConfirmed = ref(false)
const posPayload = ref(JSON.stringify({
  amount: 0,
  bizId: '',
  channelType: 0,
  couponsCardList: [],
  customerId: 0,
  familyCardNo: '',
  multiMark: '',
  orderAmount: 0,
  orderNo: '',
  orderSource: 0,
  orderTotalAmount: 0,
  perCouponUseDetailList: [{ amount: 0, cardId: '' }],
  reason: '',
  tradeMarks: [],
  type: 0,
  useCount: 0,
}, null, 2))

async function submitPosWriteOff() {
  posError.value = ''
  posResult.value = null
  if (!posConfirmed.value) {
    posError.value = '请先确认这是一次真实核销操作。'
    return
  }
  let body: Record<string, unknown>
  try {
    body = JSON.parse(posPayload.value) as Record<string, unknown>
  } catch {
    posError.value = '请求 JSON 格式不正确。'
    return
  }
  if (!String(body.bizId || '').trim() || !String(body.orderNo || '').trim()) {
    posError.value = 'bizId 和 orderNo 不能为空。'
    return
  }
  posLoading.value = true
  try {
    posResult.value = await apiPost<Record<string, unknown>>(
      `/api/qmai/pos/write-off-coupon?brand=${encodeURIComponent(brand.value)}`,
      body,
      { timeout: 60000 },
    )
    posConfirmed.value = false
  } catch (e) {
    posError.value = e instanceof Error ? e.message : '企迈优惠券核销失败。'
  } finally {
    posLoading.value = false
  }
}

// 门店筛选：'' = 全部门店
const itemShopFilter = ref('')
const itemShopOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const it of turnover.value?.items ?? []) {
    if (!seen.has(it.shopCode)) {
      seen.set(it.shopCode, it.shopName || it.shopCode)
    }
  }
  return [...seen.entries()].map(([code, name]) => ({ code, name }))
})
const searchableItemShopOptions = computed(() => itemShopOptions.value.map((shop) => ({
  value: shop.code,
  label: shop.name,
  description: shop.code,
  searchText: `${shop.name} ${shop.code}`,
})))

// 视图：summary=全门店按商品汇总（默认，直观看每个商品总共卖多少杯）；detail=门店×商品明细
const itemView = ref<'summary' | 'detail'>('summary')

type ItemSortKey = 'num' | 'income' | 'cost' | 'refund' | 'refundNum'
const itemSortKey = ref<ItemSortKey>('num')
const itemSortAsc = ref(false)

// 统计口径：drink=只看饮品（默认）；other=费用/小料/零食/水果预定/占位等非饮品；all=全部
const itemScope = ref<'drink' | 'other' | 'all'>('drink')
// 非饮品——模式匹配：费用包材、礼盒、加料、爆珠类小料、水果预定、点单占位符
const OTHER_PATTERN = /费|打包袋|吸管|餐具|杯套|贴纸|礼盒|预定|预订|预售|下个单|重新做|时令之选|零添加|加料|爆珠/
// 非饮品——按名字精确匹配：小料/加料、零食、水果零售
const OTHER_NAMES = new Set([
  '蒟蒻', '西米', '椰果', '麻薯', '米麻薯', '茶冻', '茉莉茶冻', '奶盖',
  '珍珠', '波霸', '芋圆', '布丁', '仙草', '脆啵啵', '红豆', '芋泥', '奶油顶',
  '小胡鸭', '原切雪花牛肉干',
  '龙泉驿夏之梦水蜜桃', '关于水果',
])
const isDrink = (name: string) => !OTHER_PATTERN.test(name) && !OTHER_NAMES.has(name)

const filteredItems = computed(() =>
  (turnover.value?.items ?? []).filter(
    (it) => (!itemShopFilter.value || it.shopCode === itemShopFilter.value)
      && (itemScope.value === 'all'
        || (itemScope.value === 'drink' ? isDrink(it.itemName) : !isDrink(it.itemName))),
  ))

interface ItemSummaryRow {
  itemName: string
  categoryName: string
  shopCount: number
  num: number
  incomeSum: number
  costSum: number
  refundSum: number
  refundNum: number
}
// 全门店汇总：同名商品跨门店累加，统计售卖门店数
const summaryItems = computed<ItemSummaryRow[]>(() => {
  const map = new Map<string, ItemSummaryRow & { shops: Set<string> }>()
  for (const it of filteredItems.value) {
    let g = map.get(it.itemName)
    if (!g) {
      g = {
        itemName: it.itemName, categoryName: it.categoryName || '', shopCount: 0,
        num: 0, incomeSum: 0, costSum: 0, refundSum: 0, refundNum: 0, shops: new Set(),
      }
      map.set(it.itemName, g)
    }
    g.num += Number(it.num) || 0
    g.incomeSum += Number(it.incomeSum) || 0
    g.costSum += Number(it.costSum) || 0
    g.refundSum += Number(it.refundSum) || 0
    g.refundNum += Number(it.refundNum) || 0
    g.shops.add(it.shopCode)
    // 优先保留真实分类，覆盖「未关联商品分类」占位
    if ((!g.categoryName || g.categoryName.includes('未关联'))
        && it.categoryName && !it.categoryName.includes('未关联')) {
      g.categoryName = it.categoryName
    }
  }
  return [...map.values()].map(({ shops, ...g }) => ({ ...g, shopCount: shops.size }))
})
const sortedSummaryItems = computed(() => {
  const list = [...summaryItems.value]
  const val = (r: ItemSummaryRow) => {
    switch (itemSortKey.value) {
      case 'num': return r.num
      case 'income': return r.incomeSum
      case 'cost': return r.costSum
      case 'refund': return r.refundSum
      case 'refundNum': return r.refundNum
      default: return r.num
    }
  }
  list.sort((a, b) => (itemSortAsc.value ? val(a) - val(b) : val(b) - val(a)))
  return list
})
const sortedItems = computed(() => {
  const list = [...filteredItems.value]
  const val = (r: ItemRow) => {
    switch (itemSortKey.value) {
      case 'num': return r.num
      case 'income': return r.incomeSum
      case 'cost': return r.costSum
      case 'refund': return r.refundSum
      case 'refundNum': return r.refundNum
      default: return r.num
    }
  }
  list.sort((a, b) => (itemSortAsc.value ? val(a) - val(b) : val(b) - val(a)))
  return list
})
function setItemSort(key: ItemSortKey) {
  if (itemSortKey.value === key) {
    itemSortAsc.value = !itemSortAsc.value
  } else {
    itemSortKey.value = key
    itemSortAsc.value = false
  }
}
const itemSortArrow = (key: ItemSortKey) =>
  itemSortKey.value === key ? (itemSortAsc.value ? ' ▲' : ' ▼') : ''

const qtyFmt = (n: number) => {
  const v = Number(n) || 0
  return Number.isInteger(v) ? String(v) : v.toFixed(2)
}
const itemTotals = computed(() => {
  const t = { num: 0, income: 0, cost: 0, refund: 0, refundNum: 0 }
  for (const it of filteredItems.value) {
    t.num += Number(it.num) || 0
    t.income += Number(it.incomeSum) || 0
    t.cost += Number(it.costSum) || 0
    t.refund += Number(it.refundSum) || 0
    t.refundNum += Number(it.refundNum) || 0
  }
  return t
})

/* ---------------- 服务端配方目录 × 本地销量快照 → 用量快照 ---------------- */
interface RecipeUsageFruit {
  fruit: string
  netGrams: number
  rawGrams: number
  rawJin: number
  approximate: boolean
}

interface RecipeUsageMaterial {
  materialName: string
  grams: number
}

interface RecipeUsageMatchedProduct {
  recipeName: string
  cups: number
}

interface RecipeUsageUnmatchedProduct {
  name: string
  cups: number
}

interface RecipeUsageSnapshot {
  month: string
  matchedProductCount: number
  matchedProducts: RecipeUsageMatchedProduct[]
  unmatchedProducts: RecipeUsageUnmatchedProduct[]
  calculation: {
    totalCups: number
    fruits: RecipeUsageFruit[]
    otherMaterials: RecipeUsageMaterial[]
  }
}

const recipeUsage = ref<RecipeUsageSnapshot | null>(null)
const recipeUsageLoading = ref(false)
const recipeUsageError = ref('')
const recipeUsageScopeKey = ref('')
let recipeUsageLoadSerial = 0

function recipeUsageKey(requestedBrand: BrandKey, requestedMonth: string) {
  return `${requestedBrand}\u0000${requestedMonth}`
}

const currentRecipeUsageScopeKey = computed(() => recipeUsageKey(brand.value, month.value))
const scopedRecipeUsage = computed(() =>
  recipeUsageScopeKey.value === currentRecipeUsageScopeKey.value ? recipeUsage.value : null,
)
const usageResult = computed(() => {
  const calculation = scopedRecipeUsage.value?.calculation
  return {
    totalCups: calculation?.totalCups ?? 0,
    fruits: calculation?.fruits ?? [] as RecipeUsageFruit[],
    otherMaterials: calculation?.otherMaterials ?? [] as RecipeUsageMaterial[],
  }
})

async function loadRecipeUsage() {
  const serial = ++recipeUsageLoadSerial
  const requestedBrand = brand.value
  const requestedMonth = month.value
  const requestedScopeKey = recipeUsageKey(requestedBrand, requestedMonth)
  recipeUsageLoading.value = true
  recipeUsageError.value = ''
  try {
    const nextRecipeUsage = await apiGet<RecipeUsageSnapshot>(
      `/api/qmai/recipe-usage?month=${encodeURIComponent(requestedMonth)}&brand=${encodeURIComponent(requestedBrand)}`,
    )
    if (serial !== recipeUsageLoadSerial || currentRecipeUsageScopeKey.value !== requestedScopeKey) {
      return false
    }
    recipeUsage.value = nextRecipeUsage
    recipeUsageScopeKey.value = requestedScopeKey
    return true
  } catch (e) {
    if (serial === recipeUsageLoadSerial && currentRecipeUsageScopeKey.value === requestedScopeKey) {
      recipeUsageError.value = e instanceof Error ? e.message : '读取配方用量快照失败。'
    }
    return false
  } finally {
    if (serial === recipeUsageLoadSerial) {
      recipeUsageLoading.value = false
    }
  }
}

function clearRecipeUsage() {
  recipeUsageLoadSerial += 1
  recipeUsage.value = null
  recipeUsageScopeKey.value = ''
  recipeUsageLoading.value = false
  recipeUsageError.value = ''
}

function exportUsageExcel() {
  if (recipeUsageScopeKey.value !== currentRecipeUsageScopeKey.value
    || (!scopedRecipeUsage.value?.calculation.fruits.length
      && !scopedRecipeUsage.value?.calculation.otherMaterials.length)) return
  const requestedBrand = brand.value
  const requestedMonth = month.value
  void downloadServerCsv(
    `/api/qmai/recipe-usage.csv?month=${encodeURIComponent(requestedMonth)}&brand=${encodeURIComponent(requestedBrand)}`,
    `${brandLabel.value}_物料用量测算_${monthLabel.value}.csv`,
  )
}

function shiftMonth(delta: number) {
  const [y, m] = month.value.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  const next = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  if (delta > 0 && next > currentMonth) {
    return // 不查未来月份
  }
  businessDate.value = ''
  month.value = next
  clearRecipeUsage()
  resetBackfillState()
  if (canManage.value && !isConsoleBrand.value) void loadLatestBackfill()
  if (isConsoleBrand.value) {
    income.value = null
    incomeError.value = ''
  } else {
    turnover.value = null
    void loadTurnover()
  }
}

function applyBusinessDateFilter() {
  const selectedDate = businessDate.value
  if (!selectedDate) {
    turnover.value = null
    void loadTurnover()
    return
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(selectedDate)) return
  const selectedMonth = selectedDate.slice(0, 7)
  if (month.value !== selectedMonth) {
    month.value = selectedMonth
    clearRecipeUsage()
    resetBackfillState()
    if (canManage.value && !isConsoleBrand.value) void loadLatestBackfill()
  }
  turnover.value = null
  void loadTurnover()
}

function clearBusinessDateFilter() {
  if (!businessDate.value) return
  businessDate.value = ''
  turnover.value = null
  void loadTurnover()
}

function syncConsoleIncome() {
  return loadIncome()
}

async function loadTurnover() {
  const serial = ++turnoverLoadSerial
  const requestedMonth = month.value
  const requestedBrand = brand.value
  const requestedBusinessDate = businessDate.value
  turnoverLoading.value = true
  turnoverError.value = ''
  try {
    const dayQuery = requestedBusinessDate
      ? `&businessDate=${encodeURIComponent(requestedBusinessDate)}`
      : ''
    const query = `month=${encodeURIComponent(requestedMonth)}&brand=${encodeURIComponent(requestedBrand)}${dayQuery}`
    const [revenueRows, productRows] = await Promise.all([
      apiGet<QmaiRevenueRow[]>(`/api/qmai/revenue?${query}`, { timeout: 300000 }),
      apiGet<QmaiProductRow[]>(`/api/qmai/products?${query}`, { timeout: 300000 }),
    ])
    if (serial !== turnoverLoadSerial || month.value !== requestedMonth
      || brand.value !== requestedBrand || businessDate.value !== requestedBusinessDate) return false
    const shops = revenueRows.map((row) => ({
      shopCode: row.storeId, shopName: row.storeName || row.storeId,
      bizDate: requestedBusinessDate || requestedMonth,
      validOrderCount: row.orderCount, totalAmountSum: row.revenue, incomeSum: row.revenue,
      costSum: row.cost, refundSum: row.refund, profitSum: row.revenue - row.cost,
    }))
    const items = productRows.map((row) => ({
      shopCode: row.storeId, shopName: row.storeName || row.storeId, itemName: row.itemName,
      categoryName: row.categoryName, num: row.quantity, incomeSum: row.revenue,
      costSum: 0, refundSum: row.refund, refundNum: row.refundQuantity,
    }))
    turnover.value = {
      mode: 'SNAPSHOT', note: `已读取${requestedBusinessDate || requestedMonth}本地企迈导入快照，未发起外网请求。`, days: 0,
      generatedAt: new Date().toISOString(),
      totalAmount: shops.reduce((sum, row) => sum + row.totalAmountSum, 0),
      income: shops.reduce((sum, row) => sum + row.incomeSum, 0),
      cost: shops.reduce((sum, row) => sum + row.costSum, 0),
      refund: shops.reduce((sum, row) => sum + row.refundSum, 0),
      profit: shops.reduce((sum, row) => sum + row.profitSum, 0),
      orderCount: shops.reduce((sum, row) => sum + row.validOrderCount, 0), shops, items,
    }
    return true
  } catch (e) {
    if (serial === turnoverLoadSerial && month.value === requestedMonth
      && brand.value === requestedBrand && businessDate.value === requestedBusinessDate) {
      turnoverError.value = e instanceof Error ? e.message : '读取营业额失败。'
    }
    return false
  } finally {
    if (serial === turnoverLoadSerial) turnoverLoading.value = false
  }
}

function exportExcel() {
  const t = turnover.value
  if (!t?.shops?.length) {
    return
  }
  const dayQuery = businessDate.value
    ? `&businessDate=${encodeURIComponent(businessDate.value)}`
    : ''
  void downloadServerCsv(
    `/api/qmai/revenue.csv?month=${encodeURIComponent(month.value)}&brand=${encodeURIComponent(brand.value)}${dayQuery}`,
    `${brandLabel.value}_企迈营业额_${businessDate.value || monthLabel.value}.csv`,
  )
}

function exportItemsExcel() {
  if (!turnover.value?.items.length) return
  // 导出由后端从授权范围快照生成并写审计，避免浏览器绕过导出权限或审计。
  const dayQuery = businessDate.value
    ? `&businessDate=${encodeURIComponent(businessDate.value)}`
    : ''
  void downloadServerCsv(
    `/api/qmai/products.csv?month=${encodeURIComponent(month.value)}&brand=${encodeURIComponent(brand.value)}${dayQuery}`,
    `${brandLabel.value}_企迈商品销量_${businessDate.value || monthLabel.value}.csv`,
  )
}

function downloadCsv(lines: string[], filename: string) {
  // UTF-8 BOM 让 Excel 正确识别中文
  const csv = '﻿' + lines.join('\r\n')
  downloadBlob(new Blob([csv], { type: 'text/csv;charset=utf-8;' }), filename)
}

async function downloadServerCsv(path: string, filename: string) {
  try {
    const response = await http.get<Blob>(path, { responseType: 'blob' })
    downloadBlob(response.data, filename)
  } catch (e) {
    turnoverError.value = e instanceof Error ? e.message : '导出失败，请稍后重试。'
  }
}

function exportActive() {
  if (isConsoleBrand.value) {
    exportIncomeExcel()
  } else if (snapshotExportDenied.value) {
    return
  } else if (activeTab.value === 'items') {
    exportItemsExcel()
  } else if (activeTab.value === 'usage') {
    exportUsageExcel()
  } else if (activeTab.value === 'pos') {
    return
  } else {
    exportExcel()
  }
}

async function loadLocalPlatformState() {
  const qmaiLoaded = await loadQmai()
  if (isConsoleBrand.value) return qmaiLoaded
  const [turnoverLoaded] = await Promise.all([
    loadTurnover(),
    canManage.value ? loadLatestBackfill() : Promise.resolve(),
  ])
  return qmaiLoaded && turnoverLoaded
}

const { markFresh } = useForegroundReload(loadLocalPlatformState, {
  canReload: () => !modalOpen.value
    && !saving.value
    && !anyLoading.value
    && !recipeUsageLoading.value,
})

onMounted(() => {
  void loadLocalPlatformState().then((loaded) => {
    if (loaded) markFresh()
  })
})

onBeforeUnmount(() => {
  stopBackfillPolling()
  backfillRequestSerial += 1
})
</script>

<template>
  <section class="page-panel platform-page">
    <PageHeader />

    <!-- 品牌切换：仅在配置了多个品牌时显示 -->
    <div v-if="BRANDS.length > 1" class="brand-tabs">
      <button
        v-for="b in BRANDS"
        :key="b.key"
        :class="{ active: brand === b.key }"
        @click="switchBrand(b.key)"
      >
        {{ b.label }}
      </button>
    </div>

    <div class="platform-grid">
      <!-- 企迈：可点击配置 -->
      <article
        class="content-card platform-card qmai-card"
        :class="{ clickable: canManage }"
        role="button"
        tabindex="0"
        @click="openModal"
        @keyup.enter="openModal"
      >
        <ExternalLink :size="22" />
        <h3>企迈 · {{ brandLabel }}</h3>
        <span class="status-badge" :class="qmaiStatus === '正常' ? 'ok' : 'warn'">{{ qmaiStatus }}</span>
        <p v-if="canManage" class="card-hint">点击配置账号</p>
        <p v-else class="card-hint muted">无配置权限</p>
      </article>

      <article
        v-for="platform in otherPlatforms"
        :key="platform.name"
        class="content-card platform-card"
      >
        <ExternalLink :size="22" />
        <h3>{{ platform.name }}</h3>
        <span class="status-badge" :class="platform.status === '正常' ? 'ok' : 'warn'">{{ platform.status }}</span>
      </article>
    </div>

    <!-- 企迈营业额 / 企迈商品销售 -->
    <div v-if="panelReady" class="content-card turnover-panel">
      <div class="turnover-head">
        <div class="panel-tabs">
          <button :class="{ active: activeTab === 'turnover' }" @click="activeTab = 'turnover'">
            企迈营业额
          </button>
          <button v-if="!isConsoleBrand" :class="{ active: activeTab === 'items' }" @click="activeTab = 'items'">
            企迈商品销售
          </button>
          <button v-if="!isConsoleBrand && !isFinanceViewer" :class="{ active: activeTab === 'usage' }" @click="activeTab = 'usage'">
            物料用量
          </button>
          <button v-if="!isConsoleBrand && !isFinanceViewer" :class="{ active: activeTab === 'pos' }" @click="activeTab = 'pos'">
            POS
          </button>
        </div>
        <div v-if="activeTab !== 'pos'" class="range-tabs">
          <button :disabled="anyLoading" @click="shiftMonth(-12)">◀◀ 上一年</button>
          <button :disabled="anyLoading" @click="shiftMonth(-1)">◀ 上一月</button>
          <label
            v-if="!isConsoleBrand && (activeTab === 'turnover' || activeTab === 'items')"
            class="date-filter"
          >
            <span>历史日期</span>
            <input
              v-model="businessDate"
              type="date"
              :max="latestClosedBusinessDate"
              :disabled="turnoverLoading"
              aria-label="选择企迈历史日期"
              @change="applyBusinessDateFilter"
            />
          </label>
          <button
            v-if="!isConsoleBrand && businessDate
              && (activeTab === 'turnover' || activeTab === 'items')"
            :disabled="turnoverLoading"
            @click="clearBusinessDateFilter"
          >
            查看整月
          </button>
          <span class="month-label">{{ monthLabel }}</span>
          <button :disabled="anyLoading || isCurrentMonthOrLater" @click="shiftMonth(1)">下一月 ▶</button>
          <button :disabled="anyLoading || isCurrentMonthOrLater" @click="shiftMonth(12)">下一年 ▶▶</button>
          <button
            v-if="!isConsoleBrand && canManage
              && (activeTab === 'turnover' || activeTab === 'items')"
            class="backfill-action"
            :disabled="backfillBusy || anyLoading"
            @click="startBackfill"
          >
            {{ backfillBusy ? '补取中…' : businessDate ? '补取所选日期' : '补取本月历史数据' }}
          </button>
          <button v-if="isConsoleBrand" class="sync-action" :disabled="anyLoading" @click="syncConsoleIncome">
            {{ anyLoading ? '同步中…' : '同步企迈数据' }}
          </button>
          <button
            class="export"
            :disabled="snapshotExportDenied || (isConsoleBrand ? !income?.channels?.length
            : activeTab === 'items' ? !sortedItems.length
                : activeTab === 'usage'
                  ? (!usageResult.fruits.length && !usageResult.otherMaterials.length)
                  : !turnover?.shops?.length)"
            :title="snapshotExportDenied ? '仅老板或财务可以导出企迈营业额和商品销售' : ''"
            @click="exportActive"
          >
            导出 Excel
          </button>
          <span v-if="snapshotExportDenied" class="export-permission-hint">
            仅老板或财务可以导出
          </span>
        </div>
      </div>

      <div
        v-if="!isConsoleBrand && canManage
          && (activeTab === 'turnover' || activeTab === 'items')
          && (backfillSubmitting || backfillBatch || backfillError)"
        class="backfill-progress"
        :class="{ failed: !!backfillFailureMessage || !!backfillError }"
        aria-live="polite"
      >
        <div class="backfill-progress-head">
          <strong>{{ backfillStatusLabel || '历史数据补取' }}</strong>
          <span v-if="backfillBatch && backfillTotal">
            {{ backfillProcessed }}/{{ backfillTotal }} {{ backfillUnitLabel }}（{{ backfillPercent }}%）
          </span>
        </div>
        <progress
          v-if="backfillBatch && backfillTotal"
          :value="backfillProcessed"
          :max="backfillTotal"
          aria-label="历史数据补取进度"
        />
        <p v-if="backfillBatch && !backfillFailureMessage" class="msg muted">
          已写入营业额 {{ Number(backfillBatch.dailyRows || 0).toLocaleString('zh-CN') }} 行，
          商品销售 {{ Number(backfillBatch.productRows || 0).toLocaleString('zh-CN') }} 行。
          {{ backfillRunning ? '任务在后台执行，离开页面不会中断。' : '' }}
        </p>
        <p v-if="backfillFailureMessage || backfillError" class="msg error">
          {{ backfillFailureMessage || backfillError }}
        </p>
      </div>

      <!-- 令牌通道品牌：营业收入按支付渠道 -->
      <template v-if="isConsoleBrand">
        <div v-if="income && income.mode === 'LIVE'" class="stat-cards">
          <div class="stat">
            <span class="stat-label">营业收入</span>
            <span class="stat-value income">{{ money(income.totalRevenue) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">订单数</span>
            <span class="stat-value">{{ income.totalCount.toLocaleString('zh-CN') }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">支付渠道数</span>
            <span class="stat-value">{{ income.channels.length }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">笔单价</span>
            <span class="stat-value profit">{{ income.totalCount > 0
              ? money(income.totalRevenue / income.totalCount) : '—' }}</span>
          </div>
        </div>
        <p v-if="incomeError" class="msg warn-text">{{ incomeError }}</p>
        <p v-else-if="incomeLoading" class="msg muted">正在从企迈后台拉取 {{ monthLabel }} 营业额…（约 5~15 秒）</p>
        <p v-else-if="!income" class="msg muted">选择月份后点击“同步企迈数据”，拉取该月营业额（按支付渠道）。</p>
        <table v-if="income?.channels?.length" class="turnover-table">
          <thead>
            <tr>
              <th>支付渠道</th>
              <th class="num">营业额</th>
              <th class="num">订单数</th>
              <th class="num">占比</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in income.channels" :key="i">
              <td>{{ c.name }}</td>
              <td class="num income">{{ money(c.revenue) }}</td>
              <td class="num">{{ c.count.toLocaleString('zh-CN') }}</td>
              <td class="num rate">{{ incomePct(c) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr class="total-row">
              <td>合计</td>
              <td class="num income">{{ money(income.totalRevenue) }}</td>
              <td class="num">{{ income.totalCount.toLocaleString('zh-CN') }}</td>
              <td class="num">100%</td>
            </tr>
          </tfoot>
        </table>
      </template>

      <template v-else>

      <div v-if="activeTab === 'turnover' && turnover && turnover.mode === 'LIVE'" class="stat-cards">
        <div class="stat">
          <span class="stat-label">实收营业额</span>
          <span class="stat-value income">{{ money(turnover.income) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">成本</span>
          <span class="stat-value">{{ money(turnover.cost) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">毛利（实收-成本）</span>
          <span class="stat-value profit">{{ money(turnover.profit) }}</span>
        </div>
        <div class="stat">
          <span class="stat-label">退款</span>
          <span class="stat-value">{{ money(turnover.refund) }}</span>
        </div>
      </div>

      <p v-if="activeTab !== 'pos' && turnoverError" class="msg warn-text">{{ turnoverError }}</p>
      <p v-else-if="activeTab !== 'pos' && turnoverLoading" class="msg muted">
        正在读取 {{ turnoverRangeLabel }} 全部门店营业额与商品销量…
      </p>
      <p v-else-if="activeTab !== 'pos' && !turnover" class="msg muted">
        系统会自动读取该月已经导入的企迈营业额与商品销量。
      </p>

      <template v-if="activeTab === 'turnover'">
        <table v-if="turnover?.shops?.length" class="turnover-table">
          <thead>
            <tr>
              <th>门店</th>
              <th>统计区间</th>
              <th class="num">实收（营业额）</th>
              <th class="num sortable" @click="setSort('cost')">成本{{ sortArrow('cost') }}</th>
              <th class="num sortable" @click="setSort('profit')">毛利{{ sortArrow('profit') }}</th>
              <th class="num sortable" @click="setSort('rate')">毛利率{{ sortArrow('rate') }}</th>
              <th class="num sortable" @click="setSort('refund')">退款{{ sortArrow('refund') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in sortedShops" :key="i" :class="{ 'low-margin-row': isLowMargin(row) }">
              <td>{{ row.shopName }}</td>
              <td>{{ row.bizDate }}</td>
              <td class="num income">{{ money(row.incomeSum) }}</td>
              <td class="num">{{ money(row.costSum) }}</td>
              <td class="num profit">{{ money(row.profitSum) }}</td>
              <td class="num rate" :class="{ danger: isLowMargin(row) }">{{ ratePct(row) }}</td>
              <td class="num">{{ money(row.refundSum) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="turnover && !turnoverLoading && !turnoverError" class="msg muted">
          所选时间范围内暂无营业额数据。
        </p>
      </template>

      <template v-else-if="activeTab === 'items'">
        <div v-if="turnover?.items?.length" class="items-toolbar">
          <div class="view-toggle">
            <button :class="{ active: itemView === 'summary' }" @click="itemView = 'summary'">全门店汇总</button>
            <button :class="{ active: itemView === 'detail' }" @click="itemView = 'detail'">按门店明细</button>
          </div>
          <label>
            门店：
            <SearchableSingleSelect
              v-model="itemShopFilter"
              :options="searchableItemShopOptions"
              empty-option-label="全部门店"
              empty-value=""
              placeholder="全部门店"
              search-placeholder="搜索企迈门店名称或编码"
              aria-label="企迈商品门店筛选"
            />
          </label>
          <div class="view-toggle">
            <button :class="{ active: itemScope === 'drink' }" @click="itemScope = 'drink'">饮品</button>
            <button :class="{ active: itemScope === 'other' }" @click="itemScope = 'other'">其他（费用/小料/零食）</button>
            <button :class="{ active: itemScope === 'all' }" @click="itemScope = 'all'">全部</button>
          </div>
          <span class="msg muted">
            {{ itemView === 'summary' ? `共 ${sortedSummaryItems.length} 个${itemScope === 'drink' ? '饮品' : '商品'}` : `共 ${sortedItems.length} 行` }}
            · 总销量 {{ qtyFmt(itemTotals.num) }}{{ itemScope === 'drink' ? ' 杯' : '' }} · 实收 {{ money(itemTotals.income) }}
          </span>
        </div>

        <!-- 全门店汇总：每个商品一行，销量为全部门店累加 -->
        <table v-if="itemView === 'summary' && sortedSummaryItems.length" class="turnover-table">
          <thead>
            <tr>
              <th>商品</th>
              <th>类别</th>
              <th class="num">售卖门店数</th>
              <th class="num sortable" @click="setItemSort('num')">总销量{{ itemScope === 'drink' ? '(杯)' : '' }}{{ itemSortArrow('num') }}</th>
              <th class="num sortable" @click="setItemSort('income')">实收{{ itemSortArrow('income') }}</th>
              <th class="num sortable" @click="setItemSort('cost')">成本{{ itemSortArrow('cost') }}</th>
              <th class="num sortable" @click="setItemSort('refund')">退款{{ itemSortArrow('refund') }}</th>
              <th class="num sortable" @click="setItemSort('refundNum')">退款数量{{ itemSortArrow('refundNum') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in sortedSummaryItems" :key="i">
              <td>{{ row.itemName }}</td>
              <td>{{ row.categoryName }}</td>
              <td class="num">{{ row.shopCount }}</td>
              <td class="num income">{{ qtyFmt(row.num) }}</td>
              <td class="num">{{ money(row.incomeSum) }}</td>
              <td class="num">{{ money(row.costSum) }}</td>
              <td class="num">{{ money(row.refundSum) }}</td>
              <td class="num">{{ qtyFmt(row.refundNum) }}</td>
            </tr>
          </tbody>
        </table>

        <table v-else-if="itemView === 'detail' && sortedItems.length" class="turnover-table">
          <thead>
            <tr>
              <th>门店</th>
              <th>商品</th>
              <th>类别</th>
              <th class="num sortable" @click="setItemSort('num')">销量{{ itemSortArrow('num') }}</th>
              <th class="num sortable" @click="setItemSort('income')">实收{{ itemSortArrow('income') }}</th>
              <th class="num sortable" @click="setItemSort('cost')">成本{{ itemSortArrow('cost') }}</th>
              <th class="num sortable" @click="setItemSort('refund')">退款{{ itemSortArrow('refund') }}</th>
              <th class="num sortable" @click="setItemSort('refundNum')">退款数量{{ itemSortArrow('refundNum') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in sortedItems" :key="i">
              <td>{{ row.shopName }}</td>
              <td>{{ row.itemName }}</td>
              <td>{{ row.categoryName }}</td>
              <td class="num income">{{ qtyFmt(row.num) }}</td>
              <td class="num">{{ money(row.incomeSum) }}</td>
              <td class="num">{{ money(row.costSum) }}</td>
              <td class="num">{{ money(row.refundSum) }}</td>
              <td class="num">{{ qtyFmt(row.refundNum) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="turnover && !turnoverLoading && !turnoverError" class="msg muted">
          所选时间范围内的本地企迈快照暂无商品销售数据。
        </p>
      </template>

      <template v-else-if="activeTab === 'usage'">
        <div class="items-toolbar">
          <button class="snapshot-action" :disabled="recipeUsageLoading" @click="loadRecipeUsage">
            {{ recipeUsageLoading ? '生成中…' : '生成月度用量快照' }}
          </button>
          <button @click="clearRecipeUsage">清空</button>
          <span class="msg muted">
            单杯用量按上传表管理；中/大杯及 500/1000ml 同品按 1:1 分匀后计算，浏览器不可编辑。
          </span>
        </div>
        <p v-if="recipeUsageError" class="msg warn-text">{{ recipeUsageError }}</p>
        <p v-else-if="recipeUsageLoading" class="msg muted">正在读取受管配方目录与本地销量快照…</p>

        <template v-if="usageResult.fruits.length || usageResult.otherMaterials.length">
          <h4 class="usage-title">
            水果采购测算 · {{ scopedRecipeUsage?.matchedProductCount || 0 }} 个匹配商品 · {{ qtyFmt(usageResult.totalCups) }} 杯
          </h4>
          <table v-if="usageResult.fruits.length" class="turnover-table">
            <thead>
              <tr>
                <th>水果</th>
                <th class="num">配方用量（公斤）</th>
                <th class="num">折算采购毛重（斤）</th>
                <th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in usageResult.fruits" :key="f.fruit">
                <td>{{ f.fruit }}</td>
                <td class="num">{{ (Number(f.netGrams) / 1000).toFixed(3) }}</td>
                <td class="num income">{{ Number(f.rawJin).toFixed(3) }}</td>
                <td class="muted">{{ f.approximate ? '无出肉率数据，按 1:1 折算' : '' }}</td>
              </tr>
            </tbody>
          </table>

          <h4 v-if="usageResult.otherMaterials.length" class="usage-title">其他物料（非水果）</h4>
          <table v-if="usageResult.otherMaterials.length" class="turnover-table usage-others">
            <thead>
              <tr><th>物料</th><th class="num">折合用量（千克/升）</th></tr>
            </thead>
            <tbody>
              <tr v-for="material in usageResult.otherMaterials" :key="material.materialName">
                <td>{{ material.materialName }}</td>
                <td class="num">{{ (Number(material.grams) / 1000).toFixed(3) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
        <details v-if="scopedRecipeUsage?.unmatchedProducts?.length" class="usage-unmatched">
          <summary>
            配方表没有的售卖商品（{{ scopedRecipeUsage.unmatchedProducts.length }} 个，未计入测算）
          </summary>
          <p class="msg muted">
            <span
              v-for="product in scopedRecipeUsage.unmatchedProducts"
              :key="product.name"
              class="unmatched-item"
            >
              {{ product.name }}（{{ qtyFmt(product.cups) }}杯）
            </span>
          </p>
        </details>
        <p
          v-if="!usageResult.fruits.length && !usageResult.otherMaterials.length
            && !recipeUsageLoading && !recipeUsageError"
          class="msg muted"
        >
          点击上方按钮后，系统会以服务端受管目录和授权范围内销量生成不可编辑的用量快照。
        </p>
      </template>

      <template v-else-if="activeTab === 'pos'">
        <div class="pos-panel">
          <div>
            <h4 class="usage-title">企迈 POS · 优惠券核销</h4>
            <p class="msg muted">请求由完整项目后端签名发送，企迈密钥不会暴露到浏览器。</p>
          </div>
          <label class="pos-json-label">
            核销请求 JSON
            <textarea v-model="posPayload" class="pos-json" spellcheck="false" />
          </label>
          <label class="pos-confirm">
            <input v-model="posConfirmed" type="checkbox" />
            我确认以上信息无误，并执行真实优惠券核销
          </label>
          <div>
            <button class="refresh danger-action" :disabled="posLoading || !canManage" @click="submitPosWriteOff">
              {{ posLoading ? '核销中…' : '提交核销' }}
            </button>
            <span v-if="!canManage" class="msg muted">仅老板或授权的平台管理员可执行。</span>
          </div>
          <p v-if="posError" class="msg warn-text">{{ posError }}</p>
          <div v-if="posResult" class="pos-result">
            <strong>企迈响应</strong>
            <pre>{{ JSON.stringify(posResult, null, 2) }}</pre>
          </div>
        </div>
      </template>

      </template>
    </div>

    <!-- 企迈配置弹窗 -->
    <div v-if="modalOpen" class="modal-mask" @click.self="closeModal">
      <div class="modal-box">
        <header class="modal-head">
          <h3>企迈配置 · {{ brandLabel }}</h3>
          <button class="icon-btn" @click="closeModal"><X :size="18" /></button>
        </header>

        <div class="modal-body">
          <label>
            openId（应用标识）
            <input v-model="form.openId" type="text" :placeholder="qmai?.openIdMasked || '请输入 openId'" />
          </label>
          <label>
            grantCode（门店授权码，选填）
            <input v-model="form.grantCode" type="text"
              :placeholder="qmai?.grantCodeMasked || '暂时没有可留空，拿到后再补'" />
            <small class="field-hint">只有 id 和 secret 时可先留空；门店在企迈后台授权后会得到此码，拉营业额需要它。</small>
          </label>
          <label>
            openKey（签名密钥）
            <input v-model="form.openKey" type="password" autocomplete="new-password"
              :placeholder="qmai?.openKeySet ? '已配置，留空则不修改' : '请输入 openKey'" />
          </label>
          <label>
            网关地址
            <input v-model="form.baseUrl" type="text" placeholder="https://openapi.qmai.cn" />
          </label>
          <label>
            接口版本
            <input v-model="form.version" type="text" placeholder="1.0" />
          </label>
          <label>
            授权门店（门店编码:门店名:本系统storeId，逗号分隔多店）
            <input v-model="form.shops" type="text" placeholder="S001:示范门店:1" />
          </label>

          <p v-if="qmai && !qmai.configured && qmai.statusText" class="msg warn-text">当前状态：{{ qmai.statusText }}</p>
          <p v-if="error" class="msg error">{{ error }}</p>
          <p v-if="success" class="msg success">{{ success }}</p>
          <p v-if="qmai?.updatedAt" class="msg muted">上次更新：{{ qmai.updatedBy || '—' }} · {{ qmai.updatedAt }}</p>
        </div>

        <footer class="modal-foot">
          <button class="btn ghost" @click="closeModal">取消</button>
          <button class="btn primary" :disabled="saving" @click="submit">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
.platform-page {
  display: grid;
  gap: 18px;
}

.brand-tabs {
  display: flex;
  gap: 6px;
}

.brand-tabs button {
  padding: 9px 22px;
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 10px;
  cursor: pointer;
  font-size: 15px;
  font-weight: 700;
  color: #6b7280;
}

.brand-tabs button.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.platform-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.platform-card {
  display: grid;
  align-content: start;
  gap: 10px;
}

.platform-card h3 {
  margin: 0;
}

.qmai-card.clickable {
  cursor: pointer;
  transition: box-shadow 0.15s, transform 0.15s;
}

.qmai-card.clickable:hover {
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.12);
  transform: translateY(-2px);
}

.card-hint {
  margin: 0;
  font-size: 12px;
  color: #2563eb;
}

.card-hint.muted {
  color: #9ca3af;
}

/* 营业额面板 */
.turnover-panel {
  display: grid;
  gap: 16px;
  padding: 18px;
}

.turnover-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.turnover-head h3 {
  margin: 0;
}

.panel-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-width: 100%;
  background: #f3f4f6;
  border-radius: 10px;
  padding: 4px;
}

.panel-tabs button {
  padding: 7px 16px;
  border: none;
  background: transparent;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  color: #6b7280;
}

.panel-tabs button.active {
  background: #fff;
  color: #111827;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}

.items-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  min-width: 0;
  width: 100%;
  max-width: calc(100vw - 72px);
}

.items-toolbar .msg {
  flex: 1 1 260px;
  max-width: 100%;
  min-width: 0;
  overflow-wrap: anywhere;
}

.view-toggle {
  display: flex;
  gap: 4px;
  background: #f3f4f6;
  border-radius: 8px;
  padding: 3px;
}

.view-toggle button {
  padding: 5px 12px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #6b7280;
}

.view-toggle button.active {
  background: #fff;
  color: #111827;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}

.items-toolbar label {
  font-size: 13px;
  color: #374151;
}

.items-toolbar select {
  padding: 6px 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 13px;
  background: #fff;
}

.items-toolbar :deep(.searchable-single-select) {
  width: min(220px, 72vw);
}

.range-tabs {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 6px;
}

/* 物料用量测算 */
.usage-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px 14px;
  margin: 12px 0;
}

.usage-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  color: #374151;
}

.usage-cell .usage-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-cell input {
  width: 76px;
  padding: 5px 8px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 13px;
  text-align: right;
}

.usage-title {
  margin: 18px 0 8px;
  font-size: 14px;
  color: #111827;
}

.usage-others {
  max-width: 480px;
}

.usage-unmatched summary {
  cursor: pointer;
  font-size: 13px;
  color: #92400e;
}

.unmatched-item {
  display: inline-block;
  margin-right: 12px;
}

.items-toolbar button {
  padding: 6px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}

.items-toolbar button.snapshot-action {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.items-toolbar button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

td.muted {
  color: #9ca3af;
  font-size: 12px;
}

.range-tabs button {
  padding: 6px 12px;
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #374151;
}

.range-tabs button.active {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.range-tabs button.sync-action {
  margin-left: 8px;
}

.range-tabs .date-filter {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-left: 8px;
  font-size: 13px;
  color: #374151;
  white-space: nowrap;
}

.range-tabs .date-filter input {
  min-height: 32px;
  padding: 4px 8px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  color: #111827;
  font: inherit;
}

.range-tabs .date-filter input:focus {
  outline: 2px solid rgba(37, 99, 235, 0.22);
  border-color: #2563eb;
}

.range-tabs button.backfill-action {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.range-tabs .month-label {
  min-width: 96px;
  text-align: center;
  font-weight: 700;
  font-size: 15px;
  color: #111827;
}

.range-tabs button:disabled {
  opacity: 0.5;
  cursor: default;
}

.range-tabs button.export {
  background: #16a34a;
  border-color: #16a34a;
  color: #fff;
}

.range-tabs button.export:disabled {
  opacity: 0.5;
  cursor: default;
}

.range-tabs .export-permission-hint {
  align-self: center;
  color: #b45309;
  font-size: 12px;
  white-space: nowrap;
}

.backfill-progress {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid #bfdbfe;
  border-radius: 10px;
  background: #eff6ff;
}

.backfill-progress.failed {
  border-color: #fecaca;
  background: #fef2f2;
}

.backfill-progress-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: #1e3a8a;
  font-size: 13px;
}

.backfill-progress.failed .backfill-progress-head {
  color: #991b1b;
}

.backfill-progress progress {
  width: 100%;
  height: 8px;
  accent-color: #2563eb;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.stat {
  display: grid;
  gap: 6px;
  padding: 14px;
  background: #f8fafc;
  border-radius: 10px;
}

.stat-label {
  font-size: 13px;
  color: #6b7280;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #111827;
}

.stat-value.income {
  color: #16a34a;
}

.stat-value.profit {
  color: #2563eb;
}

.turnover-table td.profit {
  color: #2563eb;
  font-weight: 600;
}

.turnover-table th.sortable {
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

.turnover-table th.sortable:hover {
  color: #2563eb;
}

.turnover-table td.rate {
  font-weight: 600;
  color: #16a34a;
}

.turnover-table td.rate.danger {
  color: #dc2626;
}

.turnover-table tr.low-margin-row {
  background: #fef2f2;
}

.turnover-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.turnover-table th,
.turnover-table td {
  padding: 9px 12px;
  border-bottom: 1px solid #eef2f7;
  text-align: left;
}

.turnover-table th {
  color: #6b7280;
  font-weight: 600;
  background: #f9fafb;
}

.turnover-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.turnover-table td.income {
  color: #16a34a;
  font-weight: 600;
}

.turnover-table tfoot .total-row td {
  font-weight: 700;
  background: #f9fafb;
  border-top: 2px solid #e5e7eb;
}

@media (max-width: 900px) {
  .stat-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: grid;
  place-items: center;
  z-index: 50;
}

.modal-box {
  width: min(520px, 92vw);
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.25);
  overflow: hidden;
}

.modal-head,
.modal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
}

.modal-head {
  border-bottom: 1px solid #eee;
}

.modal-head h3 {
  margin: 0;
}

.modal-foot {
  border-top: 1px solid #eee;
  gap: 10px;
  justify-content: flex-end;
}

.modal-body {
  display: grid;
  gap: 12px;
  padding: 18px;
  max-height: 60vh;
  overflow: auto;
}

.modal-body label {
  display: grid;
  gap: 6px;
  font-size: 13px;
  color: #374151;
}

.modal-body input {
  padding: 9px 11px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
}

.msg {
  margin: 0;
  font-size: 13px;
}

.msg.error {
  color: #dc2626;
}

.msg.success {
  color: #16a34a;
}

.msg.muted {
  color: #9ca3af;
}

.msg.warn-text {
  color: #b45309;
  background: #fffbeb;
  padding: 8px 10px;
  border-radius: 6px;
}

.field-hint {
  color: #9ca3af;
  font-size: 12px;
}

.icon-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  color: #6b7280;
}

.btn {
  padding: 8px 16px;
  border-radius: 8px;
  border: 1px solid transparent;
  cursor: pointer;
  font-size: 14px;
}

.btn.ghost {
  background: #fff;
  border-color: #d1d5db;
  color: #374151;
}

.btn.primary {
  background: #2563eb;
  color: #fff;
}

.btn.primary:disabled {
  opacity: 0.6;
  cursor: default;
}

.pos-panel {
  display: grid;
  gap: 14px;
  max-width: 900px;
}

.pos-json-label {
  display: grid;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}

.pos-json {
  width: 100%;
  min-height: 360px;
  padding: 12px;
  border: 1px solid #d1d5db;
  border-radius: 10px;
  resize: vertical;
  font: 13px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.pos-confirm {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #92400e;
}

.danger-action {
  background: #b91c1c !important;
}

.pos-result {
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #d1d5db;
}

.pos-result pre {
  margin: 8px 0 0;
  overflow: auto;
  white-space: pre-wrap;
}

@media (max-width: 1000px) {
  .platform-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .platform-grid {
    grid-template-columns: 1fr;
  }

  .range-tabs {
    justify-content: flex-start;
  }

  .range-tabs .date-filter {
    width: 100%;
    padding-left: 0;
  }

  .range-tabs .date-filter input {
    flex: 1;
  }

  .backfill-progress-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}
</style>
