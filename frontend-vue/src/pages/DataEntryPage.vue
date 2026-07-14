<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ChevronDown, ChevronLeft, ChevronRight, FileSpreadsheet, History, RefreshCw, Save, X } from 'lucide-vue-next'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { getProfitEntries, getProfitMonths, saveProfitEntry, type ProfitEntry } from '../api/finance'
import { getBrands, getStores, type BrandInfo, type StoreInfo } from '../api/operations'
import BrandSelect from '../components/common/BrandSelect.vue'
import PageHeader from '../components/common/PageHeader.vue'
import ProfitImportDrawer from '../components/finance/ProfitImportDrawer.vue'
import UiButton from '../components/ui/UiButton.vue'
import UnsavedChangesDialog from '../components/ui/UnsavedChangesDialog.vue'
import { useBusinessScope } from '../composables/useBusinessScope'
import { money, percent, useProfitStore } from '../stores/profit'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
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

type AmountFieldKey = Exclude<keyof ProfitDraft, 'note'>

interface AmountFieldDefinition {
  key: AmountFieldKey
  label: string
}

const INCOME_FIELDS = [
  { key: 'sales', label: '营业额' },
  { key: 'refund', label: '退款金额' },
  { key: 'discount', label: '优惠金额' },
] satisfies readonly AmountFieldDefinition[]

const COST_FIELDS = [
  { key: 'material', label: '原材料成本' },
  { key: 'packaging', label: '包材成本' },
  { key: 'loss', label: '损耗成本' },
  { key: 'costOther', label: '其他成本' },
] satisfies readonly AmountFieldDefinition[]

const PRIMARY_EXPENSE_FIELDS = [
  { key: 'rent', label: '房租' },
  { key: 'labor', label: '人工工资' },
  { key: 'utility', label: '水电费' },
  { key: 'property', label: '物业费' },
  { key: 'commission', label: '平台佣金' },
  { key: 'promo', label: '推广费' },
] satisfies readonly AmountFieldDefinition[]

const MORE_EXPENSE_FIELDS = [
  { key: 'repair', label: '维修费' },
  { key: 'equip', label: '设备费' },
  { key: 'expOther', label: '其他费用' },
] satisfies readonly AmountFieldDefinition[]

const ALL_AMOUNT_FIELDS = [
  ...INCOME_FIELDS,
  ...COST_FIELDS,
  ...PRIMARY_EXPENSE_FIELDS,
  ...MORE_EXPENSE_FIELDS,
]

const amountFormatter = new Intl.NumberFormat('zh-CN', {
  maximumFractionDigits: 2,
  useGrouping: true,
})

const HISTORY_FETCH_LIMIT = 8
const HISTORY_PAGE_SIZE = 6

const router = useRouter()
const auth = useAuthStore()
const scope = useBusinessScope()
const profit = useProfitStore()
const brands = ref<BrandInfo[]>([])
const stores = ref<StoreInfo[]>([])
const selectedBrandId = ref(scope.scopedBrandId())
const selectedStoreId = ref(scope.scopedStoreId())
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
const baselineDraft = ref(snapshotDraft(draft.value))
const moreFeesOpen = ref(false)
const activeAmountField = ref<AmountFieldKey | null>(null)
const amountInputText = ref<Partial<Record<AmountFieldKey, string>>>({})
const fieldErrors = ref<Partial<Record<AmountFieldKey, string>>>({})
const filterRenderKey = ref(0)
const unsavedDialogOpen = ref(false)
const unsavedDialogMessage = ref('')

let entryRequestId = 0
let historyRequestId = 0
let pendingUnsavedConfirm: (() => void) | null = null
let pendingUnsavedCancel: (() => void) | null = null

const managerStore = computed<StoreInfo | null>(() => scope.isStoreManager.value && scope.boundStoreId.value
  ? {
      id: scope.boundStoreId.value,
      code: scope.boundStoreId.value,
      name: scope.boundStoreName.value,
      brandId: scope.brandId.value || 0,
      brandName: scope.brandName.value,
    }
  : null)
const filteredStores = computed(() => scope.isStoreManager.value
  ? (managerStore.value ? [managerStore.value] : [])
  : filterStoresByBrand(stores.value, selectedBrandId.value))
const selectedStore = computed(() => stores.value.find((store) => store.id === selectedStoreId.value) || managerStore.value)
const canSave = computed(() => auth.hasPermission(PERMISSIONS.FINANCE_PROFIT_WRITE))
const historyPreview = computed(() => historyRows.value.slice(0, 5))
const historyPageCount = computed(() => Math.max(1, Math.ceil(historyRows.value.length / HISTORY_PAGE_SIZE)))
const pagedHistory = computed(() => {
  const start = (historyPage.value - 1) * HISTORY_PAGE_SIZE
  return historyRows.value.slice(start, start + HISTORY_PAGE_SIZE)
})
const isDirty = computed(() => !loadingEntry.value && snapshotDraft(draft.value) !== baselineDraft.value)
const moreFeesFilledCount = computed(() => MORE_EXPENSE_FIELDS.filter(({ key }) => num(draft.value[key]) !== 0).length)
const moreFeesHasError = computed(() => MORE_EXPENSE_FIELDS.some(({ key }) => Boolean(fieldErrors.value[key])))

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

function snapshotDraft(value: ProfitDraft) {
  return JSON.stringify(value)
}

function num(value: number | null | undefined) {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) ? parsed : 0
}

function inputValue(value: unknown) {
  const parsed = Number(value ?? 0)
  return Number.isFinite(parsed) && parsed !== 0 ? parsed : null
}

function amountFieldId(field: AmountFieldKey) {
  return `profit-${field}`
}

function amountFieldErrorId(field: AmountFieldKey) {
  return `${amountFieldId(field)}-error`
}

function displayAmount(field: AmountFieldKey) {
  const editingValue = amountInputText.value[field]
  if ((activeAmountField.value === field || fieldErrors.value[field]) && editingValue !== undefined) return editingValue
  const value = draft.value[field]
  return value === null ? '' : amountFormatter.format(value)
}

function clearFieldError(field: AmountFieldKey) {
  if (!fieldErrors.value[field]) return
  const next = { ...fieldErrors.value }
  delete next[field]
  fieldErrors.value = next
}

function startEditingAmount(field: AmountFieldKey, event: FocusEvent) {
  activeAmountField.value = field
  amountInputText.value = {
    ...amountInputText.value,
    [field]: (event.target as HTMLInputElement).value,
  }
}

function updateAmount(field: AmountFieldKey, event: Event) {
  const rawValue = (event.target as HTMLInputElement).value
  const normalizedValue = rawValue.replace(/,/g, '').trim()
  amountInputText.value = { ...amountInputText.value, [field]: rawValue }

  if (!normalizedValue) {
    draft.value[field] = null
    clearFieldError(field)
    return
  }

  if (!/^(?:\d+|\d*\.\d{0,2})$/.test(normalizedValue)) {
    fieldErrors.value = { ...fieldErrors.value, [field]: '请输入大于等于 0 的金额，最多保留 2 位小数' }
    return
  }

  const parsed = Number(normalizedValue)
  if (!Number.isFinite(parsed) || parsed < 0) {
    fieldErrors.value = { ...fieldErrors.value, [field]: '请输入大于等于 0 的有效金额' }
    return
  }

  draft.value[field] = parsed
  clearFieldError(field)
}

function finishEditingAmount(field: AmountFieldKey) {
  activeAmountField.value = null
  if (fieldErrors.value[field]) return
  const next = { ...amountInputText.value }
  delete next[field]
  amountInputText.value = next
}

function validateAmounts() {
  const nextErrors = { ...fieldErrors.value }
  for (const { key } of ALL_AMOUNT_FIELDS) {
    const value = draft.value[key]
    if (value !== null && (!Number.isFinite(value) || value < 0)) {
      nextErrors[key] = '请输入大于等于 0 的有效金额'
    }
  }
  fieldErrors.value = nextErrors
  if (MORE_EXPENSE_FIELDS.some(({ key }) => Boolean(nextErrors[key]))) moreFeesOpen.value = true
  return Object.keys(nextErrors).length === 0
}

function applyEntry(entry?: ProfitEntry) {
  if (!entry) {
    draft.value = createEmptyDraft()
  } else {
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
  fieldErrors.value = {}
  amountInputText.value = {}
  activeAmountField.value = null
  moreFeesOpen.value = false
  baselineDraft.value = snapshotDraft(draft.value)
}

async function loadCurrentEntry() {
  const requestId = ++entryRequestId
  const storeId = scope.scopedStoreId(selectedStoreId.value)
  if (!storeId || !selectedMonth.value) {
    applyEntry()
    return
  }
  loadingEntry.value = true
  try {
    const rows = await getProfitEntries({ month: selectedMonth.value, storeId })
    if (requestId === entryRequestId) applyEntry(rows.find((row) => row.storeId === storeId))
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
  const storeId = scope.scopedStoreId(selectedStoreId.value)
  if (!storeId) {
    historyRows.value = []
    return
  }
  loadingHistory.value = true
  try {
    const months = await getProfitMonths()
    const targetMonths = [...new Set(months.filter(Boolean))]
      .sort((left, right) => right.localeCompare(left))
      .slice(0, HISTORY_FETCH_LIMIT)
    const rows = await Promise.all(targetMonths.map((month) => getProfitEntries({ month, storeId })))
    if (requestId !== historyRequestId) return
    historyRows.value = rows
      .flat()
      .filter((row) => row.storeId === storeId)
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
    if (scope.isStoreManager.value) {
      selectedBrandId.value = scope.scopedBrandId()
      selectedStoreId.value = scope.scopedStoreId()
      stores.value = managerStore.value ? [managerStore.value] : []
      brands.value = scope.brandId.value && scope.brandName.value
        ? [{ id: scope.brandId.value, code: String(scope.brandId.value), name: scope.brandName.value }]
        : []
      profit.setFilters({ month: selectedMonth.value, brandId: selectedBrandId.value, storeId: selectedStoreId.value })
      await profit.load()
    } else {
      await Promise.all([
        profit.load(),
        getBrands().then((rows) => { brands.value = rows }),
        getStores().then((rows) => { stores.value = rows }),
      ])
    }
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
  const storeId = scope.scopedStoreId(selectedStoreId.value)
  if (!storeId) {
    error.value = scope.configurationError.value || '请选择门店后再保存。'
    return
  }
  if (!selectedMonth.value) {
    error.value = '请选择月份后再保存。'
    return
  }
  if (!validateAmounts()) return
  saving.value = true
  try {
    await saveProfitEntry({
      storeId,
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

function openUnsavedDialog(message: string, confirm: () => void, cancel: () => void = () => {}) {
  if (unsavedDialogOpen.value) pendingUnsavedCancel?.()
  pendingUnsavedConfirm = confirm
  pendingUnsavedCancel = cancel
  unsavedDialogMessage.value = message
  unsavedDialogOpen.value = true
}

function cancelUnsavedChange() {
  const cancel = pendingUnsavedCancel
  pendingUnsavedConfirm = null
  pendingUnsavedCancel = null
  unsavedDialogOpen.value = false
  cancel?.()
}

function confirmUnsavedChange() {
  const confirm = pendingUnsavedConfirm
  pendingUnsavedConfirm = null
  pendingUnsavedCancel = null
  unsavedDialogOpen.value = false
  baselineDraft.value = snapshotDraft(draft.value)
  confirm?.()
}

function runAfterDirtyCheck(message: string, action: () => void) {
  if (!isDirty.value) {
    action()
    return
  }
  openUnsavedDialog(message, action)
}

function requestBrandChange(value: string) {
  if (value === selectedBrandId.value) return
  filterRenderKey.value += 1
  runAfterDirtyCheck('切换品牌将放弃当前尚未保存的录入内容。', () => {
    const nextStores = filterStoresByBrand(stores.value, value)
    const nextStoreId = nextStores.some((store) => store.id === selectedStoreId.value)
      ? selectedStoreId.value
      : (nextStores[0]?.id || '')
    selectedBrandId.value = value
    selectedStoreId.value = nextStoreId
  })
}

function requestMonthChange(event: Event) {
  const input = event.target as HTMLInputElement
  const value = input.value
  input.value = selectedMonth.value
  if (!value || value === selectedMonth.value) return
  runAfterDirtyCheck('切换月份将放弃当前尚未保存的录入内容。', () => {
    selectedMonth.value = value
  })
}

function requestStoreChange(event: Event) {
  const select = event.target as HTMLSelectElement
  const value = select.value
  select.value = selectedStoreId.value
  if (value === selectedStoreId.value) return
  runAfterDirtyCheck('切换门店将放弃当前尚未保存的录入内容。', () => {
    selectedStoreId.value = value
  })
}

function refreshEntry() {
  runAfterDirtyCheck('刷新数据将放弃当前尚未保存的录入内容。', () => {
    void load()
  })
}

function goImportStatus() {
  error.value = ''
  if (!scope.scopedStoreId(selectedStoreId.value) || !selectedMonth.value) {
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
      brandId: scope.isStoreManager.value ? undefined : selectedBrandId.value || undefined,
      storeId: scope.isStoreManager.value ? undefined : selectedStoreId.value || undefined,
      mode: 'single',
    },
  })
}

function selectHistory(row: ProfitEntry) {
  historyDrawerOpen.value = false
  if (row.month === selectedMonth.value) return
  runAfterDirtyCheck('查看历史月份将放弃当前尚未保存的录入内容。', () => {
    selectedMonth.value = row.month
  })
}

function previousHistoryPage() {
  historyPage.value = Math.max(1, historyPage.value - 1)
}

function nextHistoryPage() {
  historyPage.value = Math.min(historyPageCount.value, historyPage.value + 1)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && historyDrawerOpen.value && !unsavedDialogOpen.value) historyDrawerOpen.value = false
}

onMounted(() => {
  document.addEventListener('keydown', handleKeydown)
  void load()
})
onBeforeUnmount(() => document.removeEventListener('keydown', handleKeydown))

onBeforeRouteLeave(() => {
  if (!isDirty.value) return true
  return new Promise<boolean>((resolve) => {
    openUnsavedDialog(
      '离开数据录入页将放弃当前尚未保存的内容。',
      () => resolve(true),
      () => resolve(false),
    )
  })
})

watch([selectedStoreId, selectedMonth], () => {
  success.value = ''
  void loadCurrentEntry()
  void loadHistory()
})

watch(
  draft,
  () => {
    if (isDirty.value) success.value = ''
  },
  { deep: true },
)
</script>

<template>
  <section class="page-panel data-entry-page">
    <PageHeader title="数据录入">
      <template #actions>
        <div class="entry-toolbar__actions">
          <span v-if="isDirty" class="entry-dirty" role="status"><i aria-hidden="true" />尚未保存</span>
          <button class="tool-icon-button" type="button" title="刷新数据" aria-label="刷新数据" :disabled="loadingEntry || saving" @click="refreshEntry">
            <RefreshCw :size="17" />
          </button>
          <button class="tool-button" type="button" @click="goImportStatus">
            <FileSpreadsheet :size="16" />导入数据
          </button>
          <button class="save-button" type="button" :disabled="!canSave || saving" :title="canSave ? '保存当前门店和月份的经营数据' : '当前角色没有保存权限'" @click="save">
            <Save :size="16" />{{ saving ? '保存中' : '保存' }}
          </button>
        </div>
      </template>
    </PageHeader>

    <div class="entry-toolbar" aria-label="数据录入筛选条件">
      <div class="entry-toolbar__fields">
        <div v-if="scope.isStoreManager.value" class="entry-manager-context" aria-label="当前录入门店">
          <strong>{{ scope.boundStoreName.value }} · {{ selectedMonth }}</strong>
          <small>{{ scope.brandName.value }}</small>
        </div>
        <label v-if="!scope.isStoreManager.value" class="toolbar-field toolbar-field--brand">
          <span>品牌</span>
          <BrandSelect
            :key="`brand-${filterRenderKey}`"
            :model-value="selectedBrandId"
            :brands="brands"
            @update:model-value="requestBrandChange"
          />
        </label>
        <label class="toolbar-field toolbar-field--month">
          <span>月份</span>
          <input :value="selectedMonth" type="month" @change="requestMonthChange" />
        </label>
        <label v-if="!scope.isStoreManager.value" class="toolbar-field toolbar-field--store">
          <span>门店</span>
          <select :value="selectedStoreId" aria-label="门店" @change="requestStoreChange">
            <option value="">请选择门店</option>
            <option v-for="store in filteredStores" :key="store.id" :value="store.id">
              {{ normalizeBrandName(store.brandName) }} · {{ store.name }}
            </option>
          </select>
        </label>
      </div>
    </div>

    <div v-if="error" class="entry-notice entry-notice--error" role="alert">{{ error }}</div>
    <div v-else-if="success" class="entry-notice entry-notice--success" role="status" aria-live="polite">{{ success }}</div>

    <ProfitImportDrawer
      v-if="importDrawerOpen"
      :store-id="selectedStoreId"
      :store-name="selectedStore?.name"
      :month="selectedMonth"
      :scope-locked="scope.isStoreManager.value"
      @close="importDrawerOpen = false"
      @saved="onImportSaved"
    />

    <div class="entry-workspace">
      <form class="entry-sheet" novalidate @submit.prevent="save">
        <section class="entry-section">
          <div class="entry-section__head">
            <h3>营业收入</h3>
            <p>录入本月营业、退款与优惠金额</p>
          </div>
          <div class="entry-fields">
            <label v-for="field in INCOME_FIELDS" :key="field.key" class="amount-field" :for="amountFieldId(field.key)">
              <span>{{ field.label }}</span>
              <span class="amount-field__control" :class="{ 'amount-field__control--error': fieldErrors[field.key] }">
                <input
                  :id="amountFieldId(field.key)"
                  :value="displayAmount(field.key)"
                  type="text"
                  inputmode="decimal"
                  autocomplete="off"
                  placeholder="0"
                  :aria-invalid="Boolean(fieldErrors[field.key])"
                  :aria-describedby="fieldErrors[field.key] ? amountFieldErrorId(field.key) : undefined"
                  @focus="startEditingAmount(field.key, $event)"
                  @input="updateAmount(field.key, $event)"
                  @blur="finishEditingAmount(field.key)"
                />
                <em>元</em>
              </span>
              <small v-if="fieldErrors[field.key]" :id="amountFieldErrorId(field.key)" class="field-error">{{ fieldErrors[field.key] }}</small>
            </label>
          </div>
        </section>

        <section class="entry-section">
          <div class="entry-section__head">
            <h3>成本</h3>
            <p>记录直接产生的原料、包材与损耗成本</p>
          </div>
          <div class="entry-fields">
            <label v-for="field in COST_FIELDS" :key="field.key" class="amount-field" :for="amountFieldId(field.key)">
              <span>{{ field.label }}</span>
              <span class="amount-field__control" :class="{ 'amount-field__control--error': fieldErrors[field.key] }">
                <input
                  :id="amountFieldId(field.key)"
                  :value="displayAmount(field.key)"
                  type="text"
                  inputmode="decimal"
                  autocomplete="off"
                  placeholder="0"
                  :aria-invalid="Boolean(fieldErrors[field.key])"
                  :aria-describedby="fieldErrors[field.key] ? amountFieldErrorId(field.key) : undefined"
                  @focus="startEditingAmount(field.key, $event)"
                  @input="updateAmount(field.key, $event)"
                  @blur="finishEditingAmount(field.key)"
                />
                <em>元</em>
              </span>
              <small v-if="fieldErrors[field.key]" :id="amountFieldErrorId(field.key)" class="field-error">{{ fieldErrors[field.key] }}</small>
            </label>
          </div>
        </section>

        <section class="entry-section">
          <div class="entry-section__head">
            <h3>费用</h3>
            <p>优先填写日常高频经营费用</p>
          </div>
          <div class="entry-fields">
            <label v-for="field in PRIMARY_EXPENSE_FIELDS" :key="field.key" class="amount-field" :for="amountFieldId(field.key)">
              <span>{{ field.label }}</span>
              <span class="amount-field__control" :class="{ 'amount-field__control--error': fieldErrors[field.key] }">
                <input
                  :id="amountFieldId(field.key)"
                  :value="displayAmount(field.key)"
                  type="text"
                  inputmode="decimal"
                  autocomplete="off"
                  placeholder="0"
                  :aria-invalid="Boolean(fieldErrors[field.key])"
                  :aria-describedby="fieldErrors[field.key] ? amountFieldErrorId(field.key) : undefined"
                  @focus="startEditingAmount(field.key, $event)"
                  @input="updateAmount(field.key, $event)"
                  @blur="finishEditingAmount(field.key)"
                />
                <em>元</em>
              </span>
              <small v-if="fieldErrors[field.key]" :id="amountFieldErrorId(field.key)" class="field-error">{{ fieldErrors[field.key] }}</small>
            </label>
          </div>

          <div class="more-fees">
            <button class="more-fees__toggle" type="button" :aria-expanded="moreFeesOpen" aria-controls="more-fees-fields" @click="moreFeesOpen = !moreFeesOpen">
              <span><b>更多费用</b><small>维修费、设备费和其他费用</small></span>
              <span class="more-fees__status" :class="{ 'more-fees__status--error': moreFeesHasError }">
                {{ moreFeesHasError ? '请检查' : moreFeesFilledCount ? `已填写 ${moreFeesFilledCount} 项` : '按需填写' }}
                <ChevronDown :size="18" :class="{ 'is-open': moreFeesOpen }" />
              </span>
            </button>
            <div v-if="moreFeesOpen" id="more-fees-fields" class="more-fees__body">
              <div class="entry-fields">
                <label v-for="field in MORE_EXPENSE_FIELDS" :key="field.key" class="amount-field" :for="amountFieldId(field.key)">
                  <span>{{ field.label }}</span>
                  <span class="amount-field__control" :class="{ 'amount-field__control--error': fieldErrors[field.key] }">
                    <input
                      :id="amountFieldId(field.key)"
                      :value="displayAmount(field.key)"
                      type="text"
                      inputmode="decimal"
                      autocomplete="off"
                      placeholder="0"
                      :aria-invalid="Boolean(fieldErrors[field.key])"
                      :aria-describedby="fieldErrors[field.key] ? amountFieldErrorId(field.key) : undefined"
                      @focus="startEditingAmount(field.key, $event)"
                      @input="updateAmount(field.key, $event)"
                      @blur="finishEditingAmount(field.key)"
                    />
                    <em>元</em>
                  </span>
                  <small v-if="fieldErrors[field.key]" :id="amountFieldErrorId(field.key)" class="field-error">{{ fieldErrors[field.key] }}</small>
                </label>
              </div>
            </div>
          </div>
        </section>

        <section class="entry-section entry-section--note">
          <label class="note-field">
            <span>备注</span>
            <textarea v-model="draft.note" rows="3" maxlength="500" placeholder="补充说明本月数据变化或异常情况（选填）" />
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
          <p class="profit-summary__formula">净利润 = 毛利润 - 费用合计</p>
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
            <button
              v-for="row in historyPreview"
              :key="`${row.storeId}-${row.month}`"
              class="history-row"
              :class="{ 'history-row--active': row.month === selectedMonth }"
              type="button"
              @click="selectHistory(row)"
            >
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
            <UiButton variant="ghost" icon-only aria-label="关闭历史记录" title="关闭" @click="historyDrawerOpen = false"><template #icon><X :size="18" /></template></UiButton>
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

    <UnsavedChangesDialog
      :open="unsavedDialogOpen"
      title="当前修改尚未保存"
      :message="unsavedDialogMessage"
      keep-label="返回继续编辑"
      discard-label="放弃修改并继续"
      @keep-editing="cancelUnsavedChange"
      @discard="confirmUnsavedChange"
    />
  </section>
</template>

<style scoped>
.data-entry-page {
  --entry-primary: #276b65;
  --entry-primary-hover: #205b56;
  --entry-primary-soft: #eaf4f2;
  --entry-bg: #f4f8f7;
  --entry-surface: #ffffff;
  --entry-ink: #182424;
  --entry-muted: #526765;
  --entry-line: #dbe7e5;
  --entry-line-strong: #bed3d0;
  --entry-danger: #c43f4c;
  --entry-danger-soft: #fff3f4;
  --entry-success: #187b55;
  --entry-success-soft: #eef8f3;
  --entry-radius: 6px;
  --entry-control-height: 44px;
  --entry-z-drawer: 80;
  width: 100%;
  display: grid;
  gap: 16px;
  padding: 0 0 24px;
  color: var(--entry-ink);
}

.entry-toolbar {
  padding: 16px;
  border: 1px solid var(--entry-line);
  border-radius: var(--entry-radius);
  background: var(--entry-surface);
}

.entry-toolbar__fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  min-width: 0;
}

.entry-manager-context {
  display: grid;
  grid-column: span 2;
  align-content: center;
  min-width: 0;
  gap: 3px;
  padding: 4px 0;
  color: var(--entry-ink);
}

.entry-manager-context strong {
  overflow: hidden;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-manager-context small {
  color: var(--entry-muted);
  font-size: 13px;
}

.toolbar-field,
.amount-field,
.note-field {
  display: grid;
  gap: 8px;
  min-width: 0;
  color: var(--entry-muted);
  font-size: 14px;
  font-weight: 600;
}

.toolbar-field > span,
.amount-field > span,
.note-field > span {
  line-height: 1.4;
}

.toolbar-field input,
.toolbar-field select,
.toolbar-field :deep(select),
.amount-field input,
.note-field textarea {
  width: 100%;
  min-width: 0;
  height: var(--entry-control-height);
  border: 1px solid var(--entry-line-strong);
  border-radius: var(--entry-radius);
  outline: none;
  background: #fff;
  color: var(--entry-ink);
  padding: 0 12px;
  font-variant-numeric: tabular-nums;
}

.toolbar-field :deep(.brand-select-wrap) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  width: 100%;
  gap: 8px;
}

.toolbar-field :deep(.brand-select-wrap select) {
  width: 100%;
  min-width: 0;
}

.note-field textarea {
  min-height: 88px;
  height: auto;
  resize: vertical;
  padding: 12px;
  line-height: 1.6;
}

.toolbar-field input::placeholder,
.amount-field input::placeholder,
.note-field textarea::placeholder {
  color: #667a78;
  opacity: 1;
}

.toolbar-field input:focus,
.toolbar-field select:focus,
.toolbar-field :deep(select:focus),
.amount-field input:focus,
.note-field textarea:focus {
  border-color: var(--entry-primary);
  outline: 2px solid rgba(39, 107, 101, 0.14);
  outline-offset: 0;
}

.entry-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.entry-dirty {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--entry-muted);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.entry-dirty i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #b56713;
}

.tool-icon-button,
.tool-button,
.save-button,
.text-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid var(--entry-line-strong);
  background: #fff;
  color: var(--entry-ink);
  font-size: 13px;
  font-weight: 700;
}

.tool-icon-button {
  width: 40px;
  height: 40px;
  padding: 0;
  border-radius: var(--entry-radius);
}

.tool-button,
.save-button {
  min-height: 40px;
  padding: 0 12px;
  border-radius: var(--entry-radius);
}

.tool-icon-button:hover,
.tool-button:hover {
  border-color: var(--entry-primary);
  color: var(--entry-primary);
}

.save-button {
  border-color: var(--entry-primary);
  background: var(--entry-primary);
  color: #fff;
}

.save-button:hover:not(:disabled) {
  border-color: var(--entry-primary-hover);
  background: var(--entry-primary-hover);
}

.text-action {
  min-height: 32px;
  border: 0;
  padding: 0;
  color: var(--entry-primary);
  background: transparent;
  white-space: nowrap;
}

.text-action:hover:not(:disabled) {
  color: var(--entry-primary-hover);
  text-decoration: underline;
  text-underline-offset: 4px;
}

.entry-notice {
  padding: 8px 12px;
  border-radius: var(--entry-radius);
  font-size: 14px;
  font-weight: 600;
}

.entry-notice--error {
  background: var(--entry-danger-soft);
  color: var(--entry-danger);
}

.entry-notice--success {
  background: var(--entry-success-soft);
  color: var(--entry-success);
}

.entry-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  align-items: start;
  gap: 24px;
}

.entry-sheet,
.profit-summary,
.entry-history {
  border: 1px solid var(--entry-line);
  border-radius: var(--entry-radius) !important;
  background: var(--entry-surface);
  box-shadow: none !important;
}

.entry-sheet {
  overflow: clip;
}

.entry-section {
  padding: 24px;
  border-bottom: 1px solid var(--entry-line);
}

.entry-section:last-child {
  border-bottom: 0;
}

.entry-section__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.entry-section__head h3 {
  margin: 0;
  color: var(--entry-ink);
  font-size: 16px;
  font-weight: 700;
}

.entry-section__head p {
  margin: 0;
  color: var(--entry-muted);
  font-size: 14px;
  line-height: 1.5;
  text-align: right;
}

.entry-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.amount-field__control {
  position: relative;
  display: block;
}

.amount-field input {
  padding: 0 40px 0 12px !important;
  text-align: right;
}

.amount-field em {
  position: absolute;
  top: 50%;
  right: 12px;
  transform: translateY(-50%);
  color: var(--entry-muted);
  font-size: 14px;
  font-style: normal;
  pointer-events: none;
}

.amount-field__control--error input {
  border-color: var(--entry-danger);
}

.field-error {
  color: var(--entry-danger);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
}

.more-fees {
  margin-top: 24px;
  border-top: 1px solid var(--entry-line);
}

.more-fees__toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 56px;
  gap: 16px;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: var(--entry-ink);
  text-align: left;
}

.more-fees__toggle > span:first-child {
  display: grid;
  gap: 4px;
}

.more-fees__toggle b {
  font-size: 14px;
  font-weight: 700;
}

.more-fees__toggle small,
.more-fees__status {
  color: var(--entry-muted);
  font-size: 13px;
  font-weight: 500;
}

.more-fees__status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.more-fees__status--error {
  color: var(--entry-danger);
}

.more-fees__status svg {
  transition: transform 180ms ease-out;
}

.more-fees__status svg.is-open {
  transform: rotate(180deg);
}

.more-fees__body {
  padding: 8px 0 16px;
}

.entry-section--note {
  padding-top: 24px;
}

.entry-summary-column {
  position: sticky;
  top: 24px;
  display: grid;
  gap: 16px;
}

.profit-summary {
  overflow: clip;
}

.profit-summary__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  border-bottom: 1px solid var(--entry-line);
}

.profit-summary__eyebrow,
.summary-loading {
  display: block;
  color: var(--entry-muted);
  font-size: 13px;
  font-weight: 600;
}

.profit-summary__head h3 {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 700;
}

.profit-summary__head-actions {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.summary-loading {
  padding-top: 4px;
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
  padding: 12px 0;
  border-bottom: 1px solid var(--entry-line);
}

.profit-summary dt {
  color: var(--entry-muted);
  font-size: 13px;
}

.profit-summary dd {
  margin: 0;
  color: var(--entry-ink);
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.profit-summary__net {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 12px;
  margin: 8px 16px 0;
  padding: 16px;
  border-radius: var(--entry-radius);
  background: var(--entry-primary-soft);
}

.profit-summary--loss .profit-summary__net {
  background: var(--entry-danger-soft);
}

.profit-summary__net > span,
.profit-summary__net div span {
  color: var(--entry-muted);
  font-size: 12px;
  font-weight: 600;
}

.profit-summary__net strong {
  grid-column: 1 / -1;
  color: var(--entry-primary);
  font-size: 24px;
  line-height: 1.2;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.profit-summary--loss .profit-summary__net strong {
  color: var(--entry-danger);
}

.profit-summary__net div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  grid-column: 1 / -1;
  gap: 12px;
}

.profit-summary__net b {
  color: var(--entry-ink);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.profit-summary__formula {
  margin: 0;
  padding: 12px 16px 16px;
  color: var(--entry-muted);
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
}

.entry-history {
  overflow: clip;
}

.entry-history__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--entry-line);
}

.entry-history__head > div {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.entry-history__head svg {
  color: var(--entry-muted);
}

.entry-history h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
}

.history-placeholder {
  padding: 24px 16px;
  color: var(--entry-muted);
  font-size: 13px;
  text-align: center;
}

.history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
  border: 0;
  border-bottom: 1px solid var(--entry-line);
  background: transparent;
  padding: 12px 16px;
  color: var(--entry-ink);
  text-align: left;
}

.history-row:last-child {
  border-bottom: 0;
}

.history-row:hover,
.history-row--active {
  background: var(--entry-primary-soft);
}

.history-row > span:first-child {
  color: var(--entry-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.history-row > span:last-child {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.history-row b,
.history-row em {
  font-size: 12px;
  font-style: normal;
  font-variant-numeric: tabular-nums;
}

.history-row b {
  font-weight: 700;
}

.history-row em {
  color: var(--entry-success);
}

.negative {
  color: var(--entry-danger) !important;
}

.history-drawer-backdrop {
  --entry-surface: #ffffff;
  --entry-ink: #182424;
  --entry-muted: #526765;
  --entry-line: #dbe7e5;
  --entry-line-strong: #bed3d0;
  --entry-primary: #276b65;
  --entry-primary-soft: #eaf4f2;
  --entry-success: #187b55;
  --entry-radius: 6px;
  --entry-z-drawer: 80;
  position: fixed;
  inset: 0;
  z-index: var(--entry-z-drawer);
  display: flex;
  justify-content: flex-end;
  background: rgba(24, 36, 36, 0.28);
}

.history-drawer {
  display: flex;
  flex-direction: column;
  width: min(480px, 100%);
  height: 100%;
  background: var(--entry-surface);
  box-shadow: -4px 0 8px rgba(24, 36, 36, 0.12);
}

.history-drawer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 24px;
  border-bottom: 1px solid var(--entry-line);
}

.history-drawer__head span {
  display: block;
  color: var(--entry-muted);
  font-size: 12px;
}

.history-drawer__head h2 {
  margin: 4px 0 0;
  font-size: 20px;
}

.history-drawer__body {
  flex: 1;
  overflow-y: auto;
}

.history-drawer__row {
  display: grid;
  grid-template-columns: 0.9fr 1.2fr 1.2fr 0.8fr;
  width: 100%;
  gap: 12px;
  border: 0;
  border-bottom: 1px solid var(--entry-line);
  background: transparent;
  padding: 16px 24px;
  color: var(--entry-ink);
  text-align: right;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.history-drawer__row span {
  color: var(--entry-muted);
  text-align: left;
}

.history-drawer__row:hover {
  background: var(--entry-primary-soft);
}

.history-drawer__row em {
  color: var(--entry-success);
  font-style: normal;
}

.history-drawer__footer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 40px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 12px 24px;
  border-top: 1px solid var(--entry-line);
}

.history-drawer__footer .tool-button {
  width: 100%;
  min-width: 0;
  white-space: nowrap;
}

.history-drawer__footer > span {
  color: var(--entry-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-align: center;
  white-space: nowrap;
}

:global(.app-main:has(.data-entry-page)) {
  background: #f4f8f7;
}

:global(#app) .data-entry-page .entry-sheet,
:global(#app) .data-entry-page .profit-summary,
:global(#app) .data-entry-page .entry-history,
:global(#app) .data-entry-page .history-drawer {
  border-radius: var(--entry-radius) !important;
}

@media (prefers-reduced-motion: reduce) {
  .more-fees__status svg {
    transition: none;
  }
}

@media (max-width: 1040px) {
  :global(html:has(.data-entry-page)),
  :global(body:has(.data-entry-page)),
  :global(#app:has(.data-entry-page)) {
    min-width: 0;
  }

  .entry-workspace {
    grid-template-columns: 1fr;
  }

  .entry-summary-column {
    position: static;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 800px) {
  .entry-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  :global(.app-shell:has(.data-entry-page)) {
    display: block;
    width: 100%;
  }

  :global(.app-shell:has(.data-entry-page) > .app-sidebar--desktop) {
    display: none !important;
  }

  :global(#app .app-main > .page-panel.data-entry-page) {
    margin: 0 16px 24px !important;
  }

  .data-entry-page {
    gap: 12px;
  }

  .entry-toolbar__fields,
  .entry-summary-column {
    grid-template-columns: 1fr;
  }

  .entry-manager-context {
    grid-column: auto;
  }

  .entry-toolbar__actions {
    flex-wrap: wrap;
  }

  .entry-dirty {
    width: 100%;
    justify-content: flex-end;
  }

  .entry-section {
    padding: 16px;
  }

  .entry-section__head {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .entry-section__head p {
    text-align: left;
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

@media (max-width: 520px) {
  :global(.app-main:has(.data-entry-page) .topbar-primary-row) {
    justify-content: flex-start;
    gap: 8px;
  }

  :global(.app-main:has(.data-entry-page) .topbar-context) {
    flex: 1;
    min-width: 0;
    gap: 8px;
  }

  :global(.app-main:has(.data-entry-page) .date-display) {
    display: none;
  }

  :global(.app-main:has(.data-entry-page) .scope-display) {
    flex: 1;
    min-width: 0;
  }

  :global(.app-main:has(.data-entry-page) .scope-display select) {
    width: 100%;
    min-width: 0;
  }

  .data-entry-page :deep(.business-page-header) {
    align-items: stretch;
    flex-direction: column;
    gap: 12px;
  }

  .data-entry-page :deep(.business-page-actions) {
    width: 100%;
  }

  .entry-fields {
    grid-template-columns: 1fr;
  }

  .entry-toolbar__actions {
    display: grid;
    grid-template-columns: 40px minmax(0, 1fr) minmax(0, 1fr);
    width: 100%;
  }

  .entry-dirty {
    grid-column: 1 / -1;
  }

  .entry-toolbar__actions .tool-button {
    grid-column: auto;
    width: 100%;
  }

  .entry-toolbar__actions .save-button {
    width: 100%;
  }

  .more-fees__toggle {
    align-items: flex-start;
  }

  .more-fees__status {
    padding-top: 4px;
  }

}
</style>
