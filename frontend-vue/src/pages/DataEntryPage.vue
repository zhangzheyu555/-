<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, FileSpreadsheet, History, RefreshCw, Save, X } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { getProfitEntries, getProfitMonths, saveProfitEntry, type ProfitEntry } from '../api/finance'
import { getBrands, getStores, type BrandInfo, type StoreInfo } from '../api/operations'
import BrandSelect from '../components/common/BrandSelect.vue'
import ProfitImportDrawer from '../components/finance/ProfitImportDrawer.vue'
import { money, percent, useProfitStore } from '../stores/profit'
import { useAuthStore } from '../stores/auth'
import { normalizeBrandName } from '../utils/brand'
import { filterStoresByBrand } from '../utils/storeFilter'

interface ProfitDraft {
  sales: number | null
  refund: number | null
  discount: number | null
  material: number | null
  packaging: number | null
  loss: number | null
  costOther: number | null
  rent: number | null
  labor: number | null
  utility: number | null
  property: number | null
  commission: number | null
  promo: number | null
  repair: number | null
  equip: number | null
  expOther: number | null
  note: string
}

const HISTORY_FETCH_LIMIT = 8
const HISTORY_PAGE_SIZE = 6

const router = useRouter()
const auth = useAuthStore()
const profit = useProfitStore()
const brands = ref<BrandInfo[]>([])
const stores = ref<StoreInfo[]>([])
const selectedBrandId = ref('')
const selectedStoreId = ref('')
const selectedMonth = ref(new Date().toISOString().slice(0, 7))
const error = ref('')
const success = ref('')
const saving = ref(false)
const loadingEntry = ref(false)
const loadingHistory = ref(false)
const historyRows = ref<ProfitEntry[]>([])
const historyError = ref('')
const historyDrawerOpen = ref(false)
const importDrawerOpen = ref(false)
const historyPage = ref(1)
const draft = ref<ProfitDraft>(createEmptyDraft())

let entryRequestId = 0
let historyRequestId = 0

const filteredStores = computed(() => filterStoresByBrand(stores.value, selectedBrandId.value))
const selectedStore = computed(() => stores.value.find((store) => store.id === selectedStoreId.value) || null)
const canSave = computed(() => ['ADMIN', 'BOSS', 'FINANCE', 'STORE_MANAGER'].includes(auth.role))
const historyPreview = computed(() => historyRows.value.slice(0, 5))
const historyPageCount = computed(() => Math.max(1, Math.ceil(historyRows.value.length / HISTORY_PAGE_SIZE)))
const pagedHistory = computed(() => {
  const start = (historyPage.value - 1) * HISTORY_PAGE_SIZE
  return historyRows.value.slice(start, start + HISTORY_PAGE_SIZE)
})

const calcPreview = computed(() => {
  const sales = num(draft.value.sales)
  const refund = num(draft.value.refund)
  const discount = num(draft.value.discount)
  const income = sales - refund - discount
  const cost = num(draft.value.material) + num(draft.value.packaging) + num(draft.value.loss) + num(draft.value.costOther)
  const gross = income - cost
  const expense = num(draft.value.rent) + num(draft.value.labor) + num(draft.value.utility) + num(draft.value.property) + num(draft.value.commission) + num(draft.value.promo) + num(draft.value.repair) + num(draft.value.equip) + num(draft.value.expOther)
  const net = gross - expense
  return { sales, income, cost, gross, expense, net, margin: income === 0 ? 0 : net / income }
})

function createEmptyDraft(): ProfitDraft {
  return {
    sales: null,
    refund: null,
    discount: null,
    material: null,
    packaging: null,
    loss: null,
    costOther: null,
    rent: null,
    labor: null,
    utility: null,
    property: null,
    commission: null,
    promo: null,
    repair: null,
    equip: null,
    expOther: null,
    note: '',
  }
}

function num(value: number | null | undefined) {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) ? parsed : 0
}

function inputValue(value: unknown) {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) && parsed !== 0 ? parsed : null
}

function applyEntry(entry?: ProfitEntry) {
  if (!entry) {
    draft.value = createEmptyDraft()
    return
  }
  draft.value = {
    sales: inputValue(entry.sales),
    refund: inputValue(entry.refund),
    discount: inputValue(entry.discount),
    material: inputValue(entry.material),
    packaging: inputValue(entry.packaging),
    loss: inputValue(entry.loss),
    costOther: inputValue(entry.costOther),
    rent: inputValue(entry.rent),
    labor: inputValue(entry.labor),
    utility: inputValue(entry.utility),
    property: inputValue(entry.property),
    commission: inputValue(entry.commission),
    promo: inputValue(entry.promo),
    repair: inputValue(entry.repair),
    equip: inputValue(entry.equip),
    expOther: inputValue(entry.expOther),
    note: entry.note || '',
  }
}

async function loadCurrentEntry() {
  const requestId = ++entryRequestId
  if (!selectedStoreId.value || !selectedMonth.value) {
    applyEntry()
    return
  }
  loadingEntry.value = true
  try {
    const rows = await getProfitEntries({ month: selectedMonth.value, storeId: selectedStoreId.value })
    if (requestId === entryRequestId) applyEntry(rows.find((row) => row.storeId === selectedStoreId.value))
  } catch (loadError) {
    if (requestId === entryRequestId) {
      applyEntry()
      error.value = displayError(loadError, '当前门店数据读取失败，请刷新后重试。')
    }
  } finally {
    if (requestId === entryRequestId) loadingEntry.value = false
  }
}

async function loadHistory() {
  const requestId = ++historyRequestId
  historyError.value = ''
  historyPage.value = 1
  if (!selectedStoreId.value) {
    historyRows.value = []
    return
  }
  loadingHistory.value = true
  try {
    const months = await getProfitMonths()
    const targetMonths = [...new Set(months.filter(Boolean))]
      .sort((left, right) => right.localeCompare(left))
      .slice(0, HISTORY_FETCH_LIMIT)
    const rows = await Promise.all(targetMonths.map((month) => getProfitEntries({ month, storeId: selectedStoreId.value })))
    if (requestId !== historyRequestId) return
    historyRows.value = rows
      .flat()
      .filter((row) => row.storeId === selectedStoreId.value)
      .sort((left, right) => right.month.localeCompare(left.month))
  } catch {
    if (requestId === historyRequestId) {
      historyRows.value = []
      historyError.value = '历史记录暂时无法读取。'
    }
  } finally {
    if (requestId === historyRequestId) loadingHistory.value = false
  }
}

async function load() {
  error.value = ''
  success.value = ''
  try {
    await Promise.all([
      profit.load(),
      getBrands().then((rows) => {
        brands.value = rows
      }),
      getStores().then((rows) => {
        stores.value = rows
      }),
    ])
    selectedMonth.value = profit.summary.month || selectedMonth.value
    if (!selectedStoreId.value) selectedStoreId.value = filteredStores.value[0]?.id || ''
    await Promise.all([loadCurrentEntry(), loadHistory()])
  } catch (loadError) {
    error.value = displayError(loadError, '数据录入页面加载失败，请刷新后重试。')
  }
}

async function save() {
  error.value = ''
  success.value = ''
  if (!canSave.value) {
    error.value = '当前账号没有保存利润数据的权限。'
    return
  }
  if (!selectedStoreId.value) {
    error.value = '请选择门店后再保存。'
    return
  }
  if (!selectedMonth.value) {
    error.value = '请选择月份后再保存。'
    return
  }
  saving.value = true
  try {
    await saveProfitEntry({
      storeId: selectedStoreId.value,
      month: selectedMonth.value,
      sales: num(draft.value.sales),
      refund: num(draft.value.refund),
      discount: num(draft.value.discount),
      material: num(draft.value.material),
      packaging: num(draft.value.packaging),
      loss: num(draft.value.loss),
      costOther: num(draft.value.costOther),
      rent: num(draft.value.rent),
      labor: num(draft.value.labor),
      utility: num(draft.value.utility),
      property: num(draft.value.property),
      commission: num(draft.value.commission),
      promo: num(draft.value.promo),
      repair: num(draft.value.repair),
      equip: num(draft.value.equip),
      expOther: num(draft.value.expOther),
      note: draft.value.note.trim() || undefined,
    })
    profit.month = selectedMonth.value
    await profit.load()
    await Promise.all([loadCurrentEntry(), loadHistory()])
    success.value = `${selectedStore.value?.name || '当前门店'} ${selectedMonth.value} 数据已保存。`
  } catch (saveError) {
    error.value = displayError(saveError, '保存失败，请稍后重试。')
  } finally {
    saving.value = false
  }
}

function displayError(reason: unknown, fallback: string) {
  const message = reason instanceof Error ? reason.message : ''
  if (/java\.|spring|exception|errorresponse|noclassdeffounderror/i.test(message)) return fallback
  return message || fallback
}

function goImportStatus() {
  error.value = ''
  if (!selectedStoreId.value || !selectedMonth.value) {
    error.value = '请选择门店和月份后再导入。'
    return
  }
  importDrawerOpen.value = true
}

async function onImportSaved(saved: number) {
  importDrawerOpen.value = false
  success.value = `已导入 ${saved} 条经营数据。`
  await Promise.all([profit.load(), loadCurrentEntry(), loadHistory()])
}

function goProfitTable() {
  void router.push({
    path: '/profit-table',
    query: {
      month: selectedMonth.value,
      brandId: selectedBrandId.value || undefined,
      storeId: selectedStoreId.value || undefined,
    },
  })
}

function selectHistory(row: ProfitEntry) {
  selectedMonth.value = row.month
  historyDrawerOpen.value = false
}

function previousHistoryPage() {
  historyPage.value = Math.max(1, historyPage.value - 1)
}

function nextHistoryPage() {
  historyPage.value = Math.min(historyPageCount.value, historyPage.value + 1)
}

onMounted(() => {
  void load()
})

watch(selectedBrandId, () => {
  if (selectedStoreId.value && !filteredStores.value.some((store) => store.id === selectedStoreId.value)) {
    selectedStoreId.value = filteredStores.value[0]?.id || ''
  }
})

watch([selectedStoreId, selectedMonth], () => {
  success.value = ''
  void loadCurrentEntry()
  void loadHistory()
})
</script>

<template>
  <section class="data-entry-page">
    <header class="entry-toolbar" aria-label="数据录入操作栏">
      <div class="entry-toolbar__title">
        <h2>数据录入</h2>
      </div>

      <div class="entry-toolbar__fields">
        <label class="toolbar-field toolbar-field--brand">
          <span>品牌</span>
          <BrandSelect v-model="selectedBrandId" :brands="brands" />
        </label>
        <label class="toolbar-field toolbar-field--month">
          <span>月份</span>
          <input v-model="selectedMonth" type="month" />
        </label>
        <label class="toolbar-field toolbar-field--store">
          <span>门店</span>
          <select v-model="selectedStoreId" aria-label="门店">
            <option value="">请选择门店</option>
            <option v-for="store in filteredStores" :key="store.id" :value="store.id">
              {{ normalizeBrandName(store.brandName) }} · {{ store.name }}
            </option>
          </select>
        </label>
      </div>

      <div class="entry-toolbar__actions">
        <button class="tool-icon-button" type="button" title="刷新数据" aria-label="刷新数据" @click="load">
          <RefreshCw :size="17" />
        </button>
        <button class="tool-button" type="button" @click="goImportStatus">
          <FileSpreadsheet :size="16" />
          导入数据
        </button>
        <button class="save-button" type="button" :disabled="!canSave || saving" :title="canSave ? '保存当前门店和月份的经营数据' : '当前角色没有保存权限'" @click="save">
          <Save :size="16" />
          {{ saving ? '保存中' : '保存' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="entry-notice entry-notice--error" role="alert">{{ error }}</div>
    <div v-else-if="success" class="entry-notice entry-notice--success">{{ success }}</div>

    <ProfitImportDrawer
      v-if="importDrawerOpen"
      :store-id="selectedStoreId"
      :store-name="selectedStore?.name"
      :month="selectedMonth"
      @close="importDrawerOpen = false"
      @saved="onImportSaved"
    />

    <div class="entry-workspace">
      <form class="entry-sheet" @submit.prevent="save">
        <section class="entry-section">
          <div class="entry-section__head">
            <h3>营业</h3>
          </div>
          <div class="entry-fields entry-fields--income">
            <label class="amount-field">
              <span>营业额</span>
              <span class="amount-field__control"><input v-model.number="draft.sales" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>退款金额</span>
              <span class="amount-field__control"><input v-model.number="draft.refund" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>优惠金额</span>
              <span class="amount-field__control"><input v-model.number="draft.discount" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
          </div>
        </section>

        <section class="entry-section">
          <div class="entry-section__head"><h3>成本</h3></div>
          <div class="entry-fields entry-fields--cost">
            <label class="amount-field">
              <span>原材料成本</span>
              <span class="amount-field__control"><input v-model.number="draft.material" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>包材成本</span>
              <span class="amount-field__control"><input v-model.number="draft.packaging" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>损耗成本</span>
              <span class="amount-field__control"><input v-model.number="draft.loss" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>其他成本</span>
              <span class="amount-field__control"><input v-model.number="draft.costOther" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
          </div>
        </section>

        <section class="entry-section">
          <div class="entry-section__head"><h3>费用</h3></div>
          <div class="entry-fields entry-fields--expense">
            <label class="amount-field">
              <span>房租</span>
              <span class="amount-field__control"><input v-model.number="draft.rent" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>人工工资</span>
              <span class="amount-field__control"><input v-model.number="draft.labor" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>水电费</span>
              <span class="amount-field__control"><input v-model.number="draft.utility" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>物业费</span>
              <span class="amount-field__control"><input v-model.number="draft.property" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>平台佣金</span>
              <span class="amount-field__control"><input v-model.number="draft.commission" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>推广费</span>
              <span class="amount-field__control"><input v-model.number="draft.promo" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>维修费</span>
              <span class="amount-field__control"><input v-model.number="draft.repair" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>设备费</span>
              <span class="amount-field__control"><input v-model.number="draft.equip" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
            <label class="amount-field">
              <span>其他费用</span>
              <span class="amount-field__control"><input v-model.number="draft.expOther" type="number" min="0" inputmode="decimal" placeholder="0" /><em>元</em></span>
            </label>
          </div>
        </section>

        <section class="entry-section entry-section--note">
          <label class="note-field">
            <span>备注</span>
            <input v-model="draft.note" type="text" placeholder="本月数据说明" />
          </label>
        </section>
      </form>

      <aside class="entry-summary-column">
        <section class="profit-summary" :class="{ 'profit-summary--loss': calcPreview.net < 0 }">
          <div class="profit-summary__head">
            <div>
              <span class="profit-summary__eyebrow">{{ selectedStore?.name || '当前门店' }} · {{ selectedMonth || '—' }}</span>
              <h3>利润汇总</h3>
            </div>
            <div class="profit-summary__head-actions">
              <span v-if="loadingEntry" class="summary-loading">读取中</span>
              <button class="text-action" type="button" @click="goProfitTable">查看利润表</button>
            </div>
          </div>

          <dl class="profit-summary__rows">
            <div><dt>营业额</dt><dd>{{ money(calcPreview.sales) }}</dd></div>
            <div><dt>实收收入</dt><dd>{{ money(calcPreview.income) }}</dd></div>
            <div><dt>成本合计</dt><dd>{{ money(calcPreview.cost) }}</dd></div>
            <div><dt>毛利润</dt><dd>{{ money(calcPreview.gross) }}</dd></div>
            <div><dt>费用合计</dt><dd>{{ money(calcPreview.expense) }}</dd></div>
          </dl>

          <div class="profit-summary__net">
            <span>净利润</span>
            <strong>{{ money(calcPreview.net) }}</strong>
            <div><span>净利率</span><b>{{ calcPreview.income ? percent(calcPreview.margin) : '—' }}</b></div>
          </div>
        </section>

        <section class="entry-history">
          <div class="entry-history__head">
            <div><History :size="16" /><h3>历史记录</h3></div>
            <button class="text-action" type="button" :disabled="!historyRows.length" @click="historyDrawerOpen = true">查看全部</button>
          </div>
          <div v-if="loadingHistory" class="history-placeholder">正在读取历史记录…</div>
          <div v-else-if="historyError" class="history-placeholder">{{ historyError }}</div>
          <div v-else-if="!selectedStoreId" class="history-placeholder">请选择门店</div>
          <div v-else-if="!historyPreview.length" class="history-placeholder">暂无历史记录</div>
          <template v-else>
            <button v-for="row in historyPreview" :key="`${row.storeId}-${row.month}`" class="history-row" type="button" @click="selectHistory(row)">
              <span>{{ row.month }}</span>
              <span><b>{{ money(row.income ?? row.sales) }}</b><em :class="{ negative: Number(row.net) < 0 }">{{ money(row.net) }} · {{ percent(row.margin) }}</em></span>
            </button>
          </template>
        </section>
      </aside>
    </div>

    <Teleport to="body">
      <div v-if="historyDrawerOpen" class="history-drawer-backdrop" @click.self="historyDrawerOpen = false">
        <section class="history-drawer" role="dialog" aria-modal="true" aria-labelledby="history-drawer-title">
          <header class="history-drawer__head">
            <div>
              <span>{{ selectedStore?.name || '当前门店' }}</span>
              <h2 id="history-drawer-title">历史经营记录</h2>
            </div>
            <button class="tool-icon-button" type="button" aria-label="关闭历史记录" title="关闭" @click="historyDrawerOpen = false"><X :size="18" /></button>
          </header>
          <div class="history-drawer__body">
            <button v-for="row in pagedHistory" :key="`${row.storeId}-${row.month}`" class="history-drawer__row" type="button" @click="selectHistory(row)">
              <span>{{ row.month }}</span>
              <b>{{ money(row.income ?? row.sales) }}</b>
              <b :class="{ negative: Number(row.net) < 0 }">{{ money(row.net) }}</b>
              <em :class="{ negative: Number(row.margin) < 0 }">{{ percent(row.margin) }}</em>
            </button>
          </div>
          <footer v-if="historyPageCount > 1" class="history-drawer__footer">
            <button class="tool-button" type="button" :disabled="historyPage === 1" @click="previousHistoryPage"><ChevronLeft :size="16" />上一页</button>
            <span>{{ historyPage }} / {{ historyPageCount }}</span>
            <button class="tool-button" type="button" :disabled="historyPage === historyPageCount" @click="nextHistoryPage">下一页<ChevronRight :size="16" /></button>
          </footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.data-entry-page {
  width: 100%;
  max-width: 1320px;
  display: grid;
  gap: var(--space-3);
  padding: var(--space-1) 0 var(--space-5);
}

.entry-toolbar {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: end;
  gap: var(--space-3);
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
}

.entry-toolbar__title h2 {
  margin: 0;
  font-size: 20px;
  line-height: 36px;
  font-weight: 800;
}

.entry-toolbar__fields {
  display: grid;
  grid-template-columns: minmax(132px, 0.8fr) 128px minmax(190px, 1.2fr);
  gap: 8px;
  min-width: 0;
}

.toolbar-field,
.amount-field,
.note-field {
  display: grid;
  gap: 5px;
  min-width: 0;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.toolbar-field > span,
.amount-field > span,
.note-field > span {
  line-height: 1;
}

.toolbar-field input,
.toolbar-field select,
.toolbar-field :deep(select),
.amount-field input,
.note-field input {
  width: 100%;
  min-width: 0;
  height: var(--control-height);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  outline: none;
  background: #fff;
  color: var(--ink);
  padding: 0 10px;
  font-variant-numeric: tabular-nums;
}

.toolbar-field input:focus,
.toolbar-field select:focus,
.toolbar-field :deep(select:focus),
.amount-field input:focus,
.note-field input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(238, 126, 62, 0.12);
}

.entry-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tool-icon-button,
.tool-button,
.save-button,
.text-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--line-strong);
  background: #fff;
  color: var(--ink);
  font-size: 13px;
  font-weight: 700;
}

.tool-icon-button {
  width: var(--control-height);
  height: var(--control-height);
  padding: 0;
  border-radius: var(--radius-sm);
}

.tool-button,
.save-button {
  min-height: var(--control-height);
  padding: 0 11px;
  border-radius: var(--radius-sm);
}

.tool-icon-button:hover,
.tool-button:hover {
  border-color: var(--primary);
  color: var(--primary);
}

.save-button {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.save-button:hover:not(:disabled) {
  background: #d96e2c;
}

.text-action {
  min-height: 28px;
  border: 0;
  padding: 0;
  color: var(--primary);
  background: transparent;
  white-space: nowrap;
}

.text-action:hover:not(:disabled) {
  color: #c65f25;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.entry-notice {
  padding: 9px 12px;
  border: 1px solid;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 700;
}

.entry-notice--error {
  border-color: rgba(217, 79, 61, 0.28);
  background: #fff6f4;
  color: var(--bad);
}

.entry-notice--success {
  border-color: rgba(30, 158, 106, 0.28);
  background: #f1faf5;
  color: var(--good);
}

.entry-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 350px);
  align-items: start;
  gap: var(--space-3);
}

.entry-sheet,
.profit-summary,
.entry-history {
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface);
}

.entry-sheet {
  overflow: hidden;
}

.entry-section {
  padding: 16px;
  border-bottom: 1px solid var(--line);
}

.entry-section:last-child {
  border-bottom: 0;
}

.entry-section__head {
  display: flex;
  align-items: baseline;
  gap: 9px;
  margin-bottom: 11px;
}

.entry-section__head h3 {
  margin: 0;
  color: var(--ink);
  font-size: 14px;
  font-weight: 800;
}

.entry-section__head h3::before {
  display: inline-block;
  width: 3px;
  height: 13px;
  margin-right: 7px;
  border-radius: 2px;
  background: var(--primary);
  content: '';
  vertical-align: -1px;
}

.entry-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(145px, 1fr));
  gap: 11px 10px;
}

.amount-field__control {
  position: relative;
  display: block;
}

.amount-field input {
  padding-right: 30px;
}

.amount-field em {
  position: absolute;
  top: 50%;
  right: 10px;
  transform: translateY(-50%);
  color: var(--muted);
  font-size: 12px;
  font-style: normal;
  pointer-events: none;
}

.entry-section--note {
  padding-top: 14px;
}

.entry-summary-column {
  position: sticky;
  top: var(--space-3);
  display: grid;
  gap: var(--space-3);
}

.profit-summary {
  overflow: hidden;
  border-top: 3px solid var(--good);
}

.profit-summary--loss {
  border-top-color: var(--bad);
}

.profit-summary__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 16px 12px;
}

.profit-summary__eyebrow,
.summary-loading {
  display: block;
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
}

.profit-summary__head h3 {
  margin: 3px 0 0;
  font-size: 16px;
  font-weight: 800;
}

.profit-summary__head-actions {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.summary-loading {
  padding-top: 2px;
}

.profit-summary__rows {
  margin: 0;
  padding: 0 16px;
}

.profit-summary__rows > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px solid var(--line);
}

.profit-summary dt {
  color: var(--muted);
  font-size: 13px;
}

.profit-summary dd {
  margin: 0;
  color: var(--ink);
  font-size: 13px;
  font-weight: 750;
  font-variant-numeric: tabular-nums;
}

.profit-summary__net {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 12px;
  margin-top: 5px;
  padding: 14px 16px;
  background: #f1faf5;
}

.profit-summary--loss .profit-summary__net {
  background: #fff5f3;
}

.profit-summary__net > span,
.profit-summary__net div span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

.profit-summary__net strong {
  grid-column: 1 / -1;
  color: var(--good);
  font-size: 28px;
  line-height: 1.1;
  font-weight: 850;
  font-variant-numeric: tabular-nums;
}

.profit-summary--loss .profit-summary__net strong {
  color: var(--bad);
}

.profit-summary__net div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  grid-column: 1 / -1;
  gap: 12px;
}

.profit-summary__net b {
  color: var(--ink);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.entry-history {
  overflow: hidden;
}

.entry-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
}

.entry-history__head > div {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.entry-history__head svg {
  color: var(--muted);
}

.entry-history h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 800;
}

.history-placeholder {
  padding: 22px 14px;
  color: var(--muted);
  font-size: 13px;
  text-align: center;
}

.history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 10px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 10px 14px;
  color: var(--ink);
  text-align: left;
}

.history-row:last-child {
  border-bottom: 0;
}

.history-row:hover {
  background: #fff8f3;
}

.history-row > span:first-child {
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.history-row > span:last-child {
  display: grid;
  justify-items: end;
  gap: 2px;
}

.history-row b,
.history-row em {
  font-size: 12px;
  font-style: normal;
  font-variant-numeric: tabular-nums;
}

.history-row b {
  font-weight: 750;
}

.history-row em {
  color: var(--good);
}

.negative {
  color: var(--bad) !important;
}

.history-drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  justify-content: flex-end;
  background: rgba(22, 25, 31, 0.28);
}

.history-drawer {
  display: flex;
  flex-direction: column;
  width: min(480px, 100%);
  height: 100%;
  background: var(--surface);
  box-shadow: -18px 0 40px rgba(20, 24, 30, 0.16);
}

.history-drawer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 18px;
  border-bottom: 1px solid var(--line);
}

.history-drawer__head span {
  display: block;
  color: var(--muted);
  font-size: 12px;
}

.history-drawer__head h2 {
  margin: 3px 0 0;
  font-size: 19px;
}

.history-drawer__body {
  flex: 1;
  overflow-y: auto;
}

.history-drawer__row {
  display: grid;
  grid-template-columns: 0.9fr 1.2fr 1.2fr 0.8fr;
  width: 100%;
  gap: 10px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 14px 18px;
  color: var(--ink);
  text-align: right;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.history-drawer__row span {
  color: var(--muted);
  text-align: left;
}

.history-drawer__row:hover {
  background: #fff8f3;
}

.history-drawer__row em {
  color: var(--good);
  font-style: normal;
}

.history-drawer__footer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--line);
}

.history-drawer__footer .tool-button {
  width: 100%;
  min-width: 0;
  white-space: nowrap;
}

.history-drawer__footer > span {
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-align: center;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .entry-toolbar {
    grid-template-columns: auto 1fr;
  }

  .entry-toolbar__actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }

  .entry-workspace {
    grid-template-columns: minmax(0, 1fr) 320px;
  }
}

@media (max-width: 960px) {
  .entry-toolbar {
    grid-template-columns: 1fr;
  }

  .entry-toolbar__fields {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .entry-toolbar__actions {
    grid-column: auto;
    justify-content: flex-start;
  }

  .entry-workspace {
    grid-template-columns: 1fr;
  }

  .entry-summary-column {
    position: static;
    order: -1;
    grid-template-columns: minmax(0, 1fr) minmax(260px, 0.9fr);
  }
}

@media (max-width: 680px) {
  .data-entry-page {
    gap: 10px;
    padding-top: 0;
  }

  .entry-toolbar {
    gap: 10px;
    padding: 12px;
  }

  .entry-toolbar__fields {
    grid-template-columns: 1fr 1fr;
  }

  .toolbar-field--brand,
  .toolbar-field--store {
    grid-column: 1 / -1;
  }

  .entry-toolbar__actions {
    display: grid;
    grid-template-columns: var(--control-height) 1fr 1fr;
    width: 100%;
  }

  .entry-toolbar__actions .tool-button,
  .entry-toolbar__actions .save-button {
    width: 100%;
  }

  .entry-summary-column {
    grid-template-columns: 1fr;
  }

  .entry-section,
  .entry-sheet__utility {
    padding-left: 12px;
    padding-right: 12px;
  }

  .entry-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .entry-section__head {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  .history-drawer {
    width: 100%;
  }

  .history-drawer__row {
    grid-template-columns: 0.85fr 1fr 1fr;
  }

  .history-drawer__row em {
    display: none;
  }
}

@media (max-width: 390px) {
  .entry-fields {
    grid-template-columns: 1fr;
  }

  .entry-toolbar__actions {
    grid-template-columns: var(--control-height) 1fr;
  }

  .entry-toolbar__actions .save-button {
    grid-column: 2;
  }

  .entry-toolbar__actions .tool-button {
    grid-column: 1 / -1;
    grid-row: 2;
  }
}
</style>
