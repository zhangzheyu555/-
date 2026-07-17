<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ExternalLink, X } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import { apiGet, apiPut } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { FRUIT_YIELD, RECIPES } from '../data/fruitUsage'

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
const canManage = computed(() => auth.hasPermission('platform.manage'))

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

async function loadQmai() {
  try {
    const path = canManage.value ? '/api/qmai/config' : '/api/qmai/status'
    qmai.value = await apiGet<QmaiConfigView>(`${path}?brand=${brand.value}`)
  } catch {
    qmai.value = null
  }
}

function switchBrand(k: BrandKey) {
  if (brand.value === k) {
    return
  }
  brand.value = k
  qmai.value = null
  turnover.value = null
  turnoverError.value = ''
  income.value = null
  incomeError.value = ''
  itemShopFilter.value = ''
  activeTab.value = 'turnover'
  loadQmai()
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
    setTimeout(() => {
      closeModal()
      loadTurnover()
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
const panelReady = computed(() =>
  isConsoleBrand.value ? !!qmai.value?.consoleTokenSet : !!qmai.value?.configured)
const income = ref<ConsoleIncome | null>(null)
const incomeLoading = ref(false)
const incomeError = ref('')
const anyLoading = computed(() =>
  turnoverLoading.value || incomeLoading.value)

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

const now = new Date()
const currentMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
// 选中的月份，格式 YYYY-MM，默认当前月
const month = ref(currentMonth)
const monthLabel = computed(() => {
  const [y, m] = month.value.split('-')
  return `${y}年${Number(m)}月`
})
const isCurrentMonthOrLater = computed(() => month.value >= currentMonth)

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

/* ---------------- 企迈商品销售（同一次刷新的数据，切标签即看） ---------------- */
const activeTab = ref<'turnover' | 'items' | 'usage'>('turnover')

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

/* ---------------- 物料用量测算：配方（5月产品用量核算表）× 杯数 → 水果采购斤数 ---------------- */
// 每个配方产品的杯数：可手输，也可用当月企迈商品销量一键填充后再改
const cupInputs = reactive<Record<string, number>>({})
const usageFillMsg = ref('')
// 企迈卖了但配方表里没有的饮品（不参与测算，列出来给用户看缺口）
const usageUnmatched = ref<{ name: string; num: number }[]>([])

// 名称归一：去空格/反引号/标点，便于企迈商品名和配方名对上
const usageNorm = (s: string) => (s || '').replace(/[\s`·.。()（）【】[\]-]/g, '')
// 中/大杯变体：企迈名带「大杯」找大杯配方，否则默认填中杯
const USAGE_SIZE = /(中|大)杯?/

function fillCupsFromQmai() {
  const items = turnover.value?.items ?? []
  if (!items.length) {
    usageFillMsg.value = '请先在所选月份点「刷新」拉取企迈商品销量。'
    return
  }
  // 全门店按商品聚合杯数（饮品口径）
  const sold = new Map<string, number>()
  for (const it of items) {
    if (!isDrink(it.itemName)) continue
    sold.set(it.itemName, (sold.get(it.itemName) || 0) + (Number(it.num) || 0))
  }
  // 配方索引：精确名 + 基础名(中/大杯变体归到同一基础名)
  const exact = new Map<string, string>()
  const byBase = new Map<string, { 中?: string; 大?: string; single?: string }>()
  for (const r of RECIPES) {
    exact.set(usageNorm(r.name), r.name)
    const base = usageNorm(r.baseName || r.name.replace(/[（(](中|大)杯?[)）]/g, ''))
    const slot = byBase.get(base) || {}
    if (/[（(]中/.test(r.name)) slot.中 = r.name
    else if (/[（(]大/.test(r.name)) slot.大 = r.name
    else slot.single = r.name
    byBase.set(base, slot)
  }
  for (const r of RECIPES) cupInputs[r.name] = 0
  let matched = 0
  const unmatched: { name: string; num: number }[] = []
  for (const [qName, num] of sold) {
    const qn = usageNorm(qName)
    let target = exact.get(qn)
    if (!target) {
      const sizeHit = qn.match(USAGE_SIZE)
      const slot = byBase.get(qn.replace(USAGE_SIZE, ''))
      if (slot) target = (sizeHit?.[1] === '大' ? slot.大 : slot.中) || slot.single || slot.中 || slot.大
    }
    if (target) {
      cupInputs[target] = (cupInputs[target] || 0) + num
      matched++
    } else if (num > 0) {
      unmatched.push({ name: qName, num })
    }
  }
  unmatched.sort((a, b) => b.num - a.num)
  usageUnmatched.value = unmatched
  usageFillMsg.value = `已按 ${monthLabel.value} 全门店销量填充 ${matched} 个商品；` +
    (unmatched.length ? `${unmatched.length} 个售卖商品配方表里没有（见下方清单），杯数可手动调整。` : '全部对上。')
}

function clearCups() {
  for (const r of RECIPES) cupInputs[r.name] = 0
  usageFillMsg.value = ''
  usageUnmatched.value = []
}

interface FruitUsageRow { fruit: string; netG: number; rawG: number; approx: boolean }
const usageResult = computed(() => {
  const fruits = new Map<string, FruitUsageRow>()
  const others = new Map<string, number>()
  let cups = 0
  let products = 0
  for (const r of RECIPES) {
    const n = Number(cupInputs[r.name]) || 0
    if (n <= 0) continue
    cups += n
    products++
    for (const ing of r.ingredients) {
      const g = ing.grams * n
      if (!ing.fruit) {
        others.set(ing.label, (others.get(ing.label) || 0) + g)
        continue
      }
      const row = fruits.get(ing.fruit) || { fruit: ing.fruit, netG: 0, rawG: 0, approx: false }
      row.netG += g
      if (ing.kind === 'juice') {
        row.rawG += g * (ing.factor || 1)
      } else if (ing.kind === 'flesh' && FRUIT_YIELD[ing.fruit]) {
        row.rawG += g / FRUIT_YIELD[ing.fruit]
      } else {
        // 无出肉率数据（百香果/耙耙柑/羊角蜜/榴莲/羽衣甘蓝等）按 1:1，偏保守
        row.rawG += g
        row.approx = true
      }
      fruits.set(ing.fruit, row)
    }
  }
  return {
    cups,
    products,
    fruits: [...fruits.values()].sort((a, b) => b.rawG - a.rawG),
    others: [...others.entries()].map(([label, g]) => ({ label, g })).sort((a, b) => b.g - a.g),
  }
})
// 斤 = 500 克
const jin = (g: number) => (g / 500).toFixed(1)
const kg = (g: number) => (g / 1000).toFixed(1)

function exportUsageExcel() {
  const u = usageResult.value
  if (!u.fruits.length) return
  const lines: string[] = []
  lines.push(`物料用量测算,${monthLabel.value},共 ${u.products} 个产品 ${qtyFmt(u.cups)} 杯`.split(',').join(','))
  lines.push('')
  lines.push(['水果', '配方用量(公斤)', '出肉率', '折算采购毛重(斤)', '备注'].join(','))
  for (const f of u.fruits) {
    lines.push([
      f.fruit, kg(f.netG),
      FRUIT_YIELD[f.fruit] ? (FRUIT_YIELD[f.fruit] * 100).toFixed(1) + '%' : '—',
      jin(f.rawG),
      f.approx ? '无出肉率数据,按1:1折算' : '',
    ].join(','))
  }
  lines.push('')
  lines.push(['其他物料', '用量(公斤)'].join(','))
  for (const o of u.others) lines.push([o.label, kg(o.g)].join(','))
  lines.push('')
  lines.push(['产品', '杯数'].join(','))
  for (const r of RECIPES) {
    const n = Number(cupInputs[r.name]) || 0
    if (n > 0) lines.push([`"${r.name}"`, n].join(','))
  }
  downloadCsv(lines, `${brandLabel.value}_物料用量测算_${monthLabel.value}.csv`)
}

function shiftMonth(delta: number) {
  const [y, m] = month.value.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  const next = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  if (delta > 0 && next > currentMonth) {
    return // 不查未来月份
  }
  month.value = next
  refreshActive()
}

function refreshActive() {
  if (isConsoleBrand.value) {
    loadIncome()
  } else {
    loadTurnover()
  }
}

async function loadTurnover() {
  if (!qmai.value?.configured) {
    turnover.value = null
    return
  }
  turnoverLoading.value = true
  turnoverError.value = ''
  try {
    turnover.value = await apiGet<TurnoverSummary>(
      `/api/qmai/summary?month=${month.value}&brand=${brand.value}`, { timeout: 300000 })
    if (turnover.value?.mode === 'ERROR') {
      turnoverError.value = turnover.value.note || '企迈接口暂时不可用。'
    }
  } catch (e) {
    turnoverError.value = e instanceof Error ? e.message : '拉取营业额失败。'
  } finally {
    turnoverLoading.value = false
  }
}

function exportExcel() {
  const t = turnover.value
  if (!t?.shops?.length) {
    return
  }
  const rangeLabel = monthLabel.value
  const header = ['门店', '统计区间', '实收(营业额)', '成本', '毛利', '退款', '应收', '记录数']
  const lines = [header.join(',')]
  for (const row of t.shops) {
    lines.push([
      `"${(row.shopName || '').replace(/"/g, '""')}"`,
      row.bizDate,
      row.incomeSum,
      row.costSum,
      row.profitSum,
      row.refundSum,
      row.totalAmountSum,
      row.validOrderCount,
    ].join(','))
  }
  lines.push(['合计', '', t.income, t.cost, t.profit, t.refund, t.totalAmount, t.orderCount].join(','))
  downloadCsv(lines, `${brandLabel.value}_企迈营业额_${rangeLabel}_${t.generatedAt?.slice(0, 10) || ''}.csv`)
}

function exportItemsExcel() {
  const t = turnover.value
  if (!t) {
    return
  }
  const csvText = (s: string) => `"${(s || '').replace(/"/g, '""')}"`
  const tt = itemTotals.value
  const lines: string[] = []
  let kind: string
  const scope = itemScope.value === 'drink' ? '饮品' : itemScope.value === 'other' ? '其他商品' : '商品'
  if (itemView.value === 'summary') {
    // 全门店汇总：每个商品一行，销量为全部门店累加
    kind = `企迈${scope}销售汇总`
    const rows = sortedSummaryItems.value
    if (!rows.length) {
      return
    }
    lines.push(['商品', '类别', '售卖门店数',
      itemScope.value === 'drink' ? '总销量(杯)' : '总销量',
      '实收', '成本', '退款', '退款数量'].join(','))
    for (const row of rows) {
      lines.push([
        csvText(row.itemName),
        csvText(row.categoryName),
        row.shopCount,
        qtyFmt(row.num),
        row.incomeSum.toFixed(2),
        row.costSum.toFixed(2),
        row.refundSum.toFixed(2),
        qtyFmt(row.refundNum),
      ].join(','))
    }
    lines.push(['合计', '', '', qtyFmt(tt.num), tt.income.toFixed(2), tt.cost.toFixed(2),
      tt.refund.toFixed(2), qtyFmt(tt.refundNum)].join(','))
  } else {
    kind = `企迈${scope}销售明细`
    const rows = sortedItems.value
    if (!rows.length) {
      return
    }
    lines.push(['门店', '商品', '类别', '销量', '实收', '成本', '退款', '退款数量'].join(','))
    for (const row of rows) {
      lines.push([
        csvText(row.shopName),
        csvText(row.itemName),
        csvText(row.categoryName),
        row.num,
        row.incomeSum,
        row.costSum,
        row.refundSum,
        row.refundNum,
      ].join(','))
    }
    lines.push(['合计', '', '', qtyFmt(tt.num), tt.income.toFixed(2), tt.cost.toFixed(2),
      tt.refund.toFixed(2), qtyFmt(tt.refundNum)].join(','))
  }
  const shopLabel = itemShopFilter.value
    ? '_' + (itemShopOptions.value.find((s) => s.code === itemShopFilter.value)?.name || itemShopFilter.value)
    : ''
  downloadCsv(lines,
    `${brandLabel.value}_${kind}_${monthLabel.value}${shopLabel}_${t.generatedAt?.slice(0, 10) || ''}.csv`)
}

function downloadCsv(lines: string[], filename: string) {
  // UTF-8 BOM 让 Excel 正确识别中文
  const csv = '﻿' + lines.join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function exportActive() {
  if (isConsoleBrand.value) {
    exportIncomeExcel()
  } else if (activeTab.value === 'items') {
    exportItemsExcel()
  } else if (activeTab.value === 'usage') {
    exportUsageExcel()
  } else {
    exportExcel()
  }
}

onMounted(loadQmai)
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
          <button v-if="!isConsoleBrand" :class="{ active: activeTab === 'usage' }" @click="activeTab = 'usage'">
            物料用量
          </button>
        </div>
        <div class="range-tabs">
          <button :disabled="anyLoading" @click="shiftMonth(-12)">◀◀ 上一年</button>
          <button :disabled="anyLoading" @click="shiftMonth(-1)">◀ 上一月</button>
          <span class="month-label">{{ monthLabel }}</span>
          <button :disabled="anyLoading || isCurrentMonthOrLater" @click="shiftMonth(1)">下一月 ▶</button>
          <button :disabled="anyLoading || isCurrentMonthOrLater" @click="shiftMonth(12)">下一年 ▶▶</button>
          <button class="refresh" :disabled="anyLoading" @click="refreshActive">
            {{ anyLoading ? '加载中…' : '刷新' }}
          </button>
          <button
            class="export"
            :disabled="isConsoleBrand ? !income?.channels?.length
              : activeTab === 'items' ? !sortedItems.length
                : activeTab === 'usage' ? !usageResult.fruits.length : !turnover?.shops?.length"
            @click="exportActive"
          >
            导出 Excel
          </button>
        </div>
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
        <p v-else-if="!income" class="msg muted">选择月份后点「刷新」拉取该月营业额（按支付渠道）。</p>
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

      <p v-if="turnoverError" class="msg warn-text">{{ turnoverError }}</p>
      <p v-else-if="turnoverLoading" class="msg muted">
        正在拉取 {{ monthLabel }} 全部门店营业额与商品销量…（整月门店多，约需 1~3 分钟，请稍候）
      </p>
      <p v-else-if="!turnover" class="msg muted">
        选择月份后点「刷新」查询该月全部门店营业额与商品销量（整月约需 1~3 分钟）。
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
            <select v-model="itemShopFilter">
              <option value="">全部门店</option>
              <option v-for="s in itemShopOptions" :key="s.code" :value="s.code">{{ s.name }}</option>
            </select>
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
          所选时间范围内暂无商品销售数据（旧数据请点「刷新」重新拉取）。
        </p>
      </template>

      <template v-else-if="activeTab === 'usage'">
        <div class="items-toolbar">
          <button class="refresh" :disabled="!turnover?.items?.length" @click="fillCupsFromQmai">
            用 {{ monthLabel }} 企迈销量填充杯数
          </button>
          <button @click="clearCups">清空</button>
          <span class="msg muted">配方来自《5月产品用量核算表·单杯用量》，共 {{ RECIPES.length }} 个产品；杯数可手动输入或修改，结果实时更新。</span>
        </div>
        <p v-if="usageFillMsg" class="msg muted">{{ usageFillMsg }}</p>

        <div class="usage-grid">
          <label v-for="r in RECIPES" :key="r.name" class="usage-cell">
            <span class="usage-name">{{ r.name }}</span>
            <input v-model.number="cupInputs[r.name]" type="number" min="0" placeholder="0" />
          </label>
        </div>

        <details v-if="usageUnmatched.length" class="usage-unmatched">
          <summary>配方表没有的售卖商品（{{ usageUnmatched.length }} 个，未计入测算）</summary>
          <p class="msg muted">
            <span v-for="u in usageUnmatched" :key="u.name" class="unmatched-item">{{ u.name }}（{{ qtyFmt(u.num) }}杯）</span>
          </p>
        </details>

        <template v-if="usageResult.fruits.length">
          <h4 class="usage-title">
            水果采购测算 · {{ usageResult.products }} 个产品 · {{ qtyFmt(usageResult.cups) }} 杯
          </h4>
          <table class="turnover-table">
            <thead>
              <tr>
                <th>水果</th>
                <th class="num">配方用量（公斤）</th>
                <th class="num">出肉率</th>
                <th class="num">折算采购毛重（斤）</th>
                <th>备注</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in usageResult.fruits" :key="f.fruit">
                <td>{{ f.fruit }}</td>
                <td class="num">{{ kg(f.netG) }}</td>
                <td class="num">{{ FRUIT_YIELD[f.fruit] ? (FRUIT_YIELD[f.fruit] * 100).toFixed(1) + '%' : '—' }}</td>
                <td class="num income">{{ jin(f.rawG) }}</td>
                <td class="muted">{{ f.approx ? '无出肉率数据，按 1:1 折算' : '' }}</td>
              </tr>
            </tbody>
          </table>

          <h4 v-if="usageResult.others.length" class="usage-title">其他物料（非水果）</h4>
          <table v-if="usageResult.others.length" class="turnover-table usage-others">
            <thead>
              <tr><th>物料</th><th class="num">用量（公斤）</th></tr>
            </thead>
            <tbody>
              <tr v-for="o in usageResult.others" :key="o.label">
                <td>{{ o.label }}</td>
                <td class="num">{{ kg(o.g) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
        <p v-else class="msg muted">输入杯数（或点上方按钮用企迈销量填充）后，这里会算出每种水果的配方用量和折算采购毛重。</p>
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
  gap: 4px;
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

.range-tabs {
  display: flex;
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

.items-toolbar button.refresh {
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

.range-tabs button.refresh {
  margin-left: 8px;
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

@media (max-width: 1000px) {
  .platform-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .platform-grid {
    grid-template-columns: 1fr;
  }
}
</style>
