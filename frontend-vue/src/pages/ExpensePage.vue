<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Plus } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import ExpenseReviewPanel from '../components/finance/ExpenseReviewPanel.vue'
import ExpenseFormDrawer from '../components/finance/ExpenseFormDrawer.vue'
import ExpenseSupplementDialog from '../components/finance/ExpenseSupplementDialog.vue'
import ExpenseRequestInfoDialog from '../components/finance/ExpenseRequestInfoDialog.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import BrandSelect from '../components/common/BrandSelect.vue'
import SearchableSingleSelect from '../components/common/SearchableSingleSelect.vue'
import { useFinanceActions } from '../composables/useFinanceActions'
import { useBusinessScope } from '../composables/useBusinessScope'
import { useForegroundReload } from '../composables/useForegroundReload'
import { submitExpense, type ExpenseClaim, type ExpenseClaimQuery } from '../api/finance'
import { getStores, type StoreInfo } from '../api/operations'
import { useFinanceStore } from '../stores/finance'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import { normalizeBrandName } from '../utils/brand'
import {
  EXPENSE_STATUS_OPTIONS,
  isExpenseCompleted,
  isExpenseReviewable,
  isExpenseUnfinished,
  needsExpenseSubmission,
} from '../utils/expenseStatus'

const finance = useFinanceStore()
const actions = useFinanceActions()
const auth = useAuthStore()
const scope = useBusinessScope()
const selectedMonth = ref('')
const selectedBrand = ref(scope.isStoreManager.value ? String(scope.brandId.value || '') : '')
const selectedStore = ref(scope.scopedStoreId())
const selectedStatus = ref('')
const stores = ref<StoreInfo[]>([])
const storesError = ref('')
const storesLoading = ref(false)
const formOpen = ref(false)
const editingExpense = ref<ExpenseClaim | null>(null)
const supplementExpense = ref<ExpenseClaim | null>(null)
const requestInfoExpense = ref<ExpenseClaim | null>(null)
const initialized = ref(false)
const storesLoaded = ref(false)
const loadedExpenseQueryKey = ref('')
let expenseLoadSerial = 0

const canEditExpense = computed(() => auth.hasPermission(PERMISSIONS.EXPENSE_CREATE))
const canReviewExpense = computed(() => auth.hasPermission(PERMISSIONS.EXPENSE_REVIEW))
const brandOptions = computed(() => {
  const seen = new Set<number>()
  return stores.value.flatMap((store) => {
    const id = Number(store.brandId)
    const name = normalizeBrandName(store.brandName)
    if (!Number.isSafeInteger(id) || id <= 0 || !name || seen.has(id)) return []
    seen.add(id)
    return [{ id, name }]
  })
})
const selectedBrandId = computed<number | undefined>(() => {
  const value = Number(selectedBrand.value)
  return Number.isSafeInteger(value) && value > 0 ? value : undefined
})
const storeOptions = computed(() => {
  return stores.value
    .filter((store) => !selectedBrandId.value || Number(store.brandId) === selectedBrandId.value)
    .map((store) => ({
      value: store.id,
      label: store.name || store.id,
      brandName: normalizeBrandName(store.brandName),
      code: store.code,
      area: store.area || store.regionCode,
      status: store.status,
    }))
})
const searchableStoreOptions = computed(() => storeOptions.value.map((store) => ({
  value: store.value,
  label: `${store.brandName} · ${store.label}`,
  description: [store.code, store.area, store.status].filter(Boolean).join(' · '),
  searchText: [store.label, store.code, store.area, store.status, store.brandName].filter(Boolean).join(' '),
})))
const expenseQuery = computed<ExpenseClaimQuery>(() => ({
  month: selectedMonth.value || undefined,
  brandId: selectedBrandId.value,
  storeId: scope.scopedStoreId(selectedStore.value) || undefined,
  status: selectedStatus.value || undefined,
}))
const currentExpenseQueryKey = computed(() => expenseQueryKey(expenseQuery.value))
// The API is the data-boundary authority.  It receives every selected filter;
// this page intentionally does not simulate server filtering over a stale list.
const filteredExpenses = computed(() => loadedExpenseQueryKey.value === currentExpenseQueryKey.value
  ? finance.expenseReviews
  : [])
const statusOptions = EXPENSE_STATUS_OPTIONS
const pendingCount = computed(() => filteredExpenses.value.filter((item) => isExpenseUnfinished(item.status)).length)
const doneCount = computed(() => filteredExpenses.value.filter((item) => isExpenseCompleted(item.status)).length)
const totalAmount = computed(() => filteredExpenses.value.reduce((total, item) => total + Number(item.amount || 0), 0))
const today = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
})
const todayExpenses = computed(() => filteredExpenses.value.filter((item) => item.expenseDate === today.value))
const todayStatusText = computed(() => {
  if (!scope.isStoreManager.value) return `${todayExpenses.value.length} 单`
  if (!todayExpenses.value.length) return '未报'
  if (todayExpenses.value.some((item) => needsExpenseSubmission(item.status))) return '待提交'
  if (todayExpenses.value.some((item) => isExpenseReviewable(item.status))) return '审核中'
  if (todayExpenses.value.every((item) => isExpenseCompleted(item.status))) return '已完成'
  return '处理中'
})

const { markFresh } = useForegroundReload(async () => {
  const loaded = await loadExpensePageData()
  if (!loaded) throw new Error(finance.error || storesError.value || '报销数据加载失败')
}, {
  canReload: () => initialized.value
    && !finance.loading
    && !storesLoading.value
    && !formOpen.value
    && !supplementExpense.value
    && !requestInfoExpense.value
    && !actions.confirmation.open,
})

function isAuthError(err: unknown) {
  return err instanceof Error && (err.message.includes('登录已失效') || err.message.includes('请先登录') || err.message.includes('UNAUTHORIZED'))
}

function isForbiddenError(err: unknown) {
  return err instanceof Error && (err.message.includes('FORBIDDEN') || err.message.includes('403') || err.message.includes('无权'))
}

function expenseQueryKey(query: ExpenseClaimQuery) {
  return [
    query.month || '',
    query.brandId === undefined ? '' : String(query.brandId),
    query.storeId || '',
    query.status || '',
  ].join('\u0000')
}

async function loadExpenseData(options: { markAsFresh?: boolean } = {}) {
  const loadSerial = ++expenseLoadSerial
  const requestedQuery = { ...expenseQuery.value }
  const requestedQueryKey = expenseQueryKey(requestedQuery)
  const previousExpenses = finance.expenseReviews
  const previousLoadedQueryKey = loadedExpenseQueryKey.value
  await finance.load(requestedQuery)
  if (loadSerial !== expenseLoadSerial || requestedQueryKey !== currentExpenseQueryKey.value) return false
  if (finance.error) {
    if (previousLoadedQueryKey === requestedQueryKey) {
      finance.expenseReviews = previousExpenses
    }
    return false
  }
  loadedExpenseQueryKey.value = requestedQueryKey
  if (options.markAsFresh !== false) markFresh()
  return true
}

async function loadExpensePageData() {
  const [storeCatalogLoaded, expensesLoaded] = await Promise.all([
    storesLoaded.value ? Promise.resolve(true) : loadStores(),
    loadExpenseData({ markAsFresh: false }),
  ])
  const loaded = storeCatalogLoaded && expensesLoaded
  if (loaded) markFresh()
  return loaded
}

async function loadStores() {
  if (scope.isStoreManager.value) {
    const storeId = scope.boundStoreId.value
    stores.value = storeId ? [{
      id: storeId,
      code: storeId,
      name: scope.boundStoreName.value,
      brandId: scope.brandId.value || 0,
      brandName: scope.brandName.value,
    }] : []
    selectedBrand.value = String(scope.brandId.value || '')
    selectedStore.value = storeId
    storesLoaded.value = true
    return true
  }
  storesLoading.value = true
  storesError.value = ''
  try {
    stores.value = await getStores()
    storesLoaded.value = true
    return true
  } catch (err) {
    if (isAuthError(err)) return false
    if (isForbiddenError(err)) {
      storesError.value = '无权查看门店列表'
      return false
    }
    console.error('[Expense] stores load failed', err)
    storesError.value = '门店列表加载失败，请稍后重试'
    return false
  } finally {
    storesLoading.value = false
  }
}

function openCreate() {
  if (scope.configurationError.value) {
    finance.error = scope.configurationError.value
    return
  }
  editingExpense.value = null
  formOpen.value = true
}

function editExpense(expense: ExpenseClaim) {
  editingExpense.value = expense
  formOpen.value = true
}

function openSupplement(expense: ExpenseClaim) {
  supplementExpense.value = expense
  finance.error = ''
  finance.actionMessage = ''
}

function openRequestInfo(expense: ExpenseClaim) {
  requestInfoExpense.value = expense
  finance.error = ''
  finance.actionMessage = ''
}

async function onSupplementSubmitted() {
  supplementExpense.value = null
  finance.actionMessage = '补充资料已提交并保存'
  await loadExpenseData()
}

async function onRequestInfoSubmitted() {
  requestInfoExpense.value = null
  finance.actionMessage = '已要求门店补充资料'
  await loadExpenseData()
}

async function submitForReview(expense: ExpenseClaim) {
  finance.actioningId = expense.id
  finance.error = ''
  try {
    await submitExpense(expense.id)
    finance.actionMessage = '报销已提交审核'
    await loadExpenseData()
  } catch (reason) {
    finance.error = reason instanceof Error ? reason.message : '报销提交失败，请稍后重试。'
  } finally {
    finance.actioningId = ''
  }
}

async function onSaved() {
  formOpen.value = false
  finance.actionMessage = '报销已保存'
  await loadExpenseData()
}

async function onClaimAttachmentDeleted() {
  finance.error = ''
  finance.actionMessage = '报销凭证已删除'
  await loadExpenseData()
}

async function approveExpense(expense: ExpenseClaim) {
  try {
    await actions.approveExpense(expense)
    if (finance.error) loadedExpenseQueryKey.value = ''
    else markFresh()
  } catch {
    if (finance.error) loadedExpenseQueryKey.value = ''
  }
}

async function confirmExpenseAction() {
  await actions.confirmAction()
  if (finance.error) loadedExpenseQueryKey.value = ''
  else markFresh()
}

onMounted(async () => {
  await loadExpensePageData()
  initialized.value = true
})

watch(selectedBrand, () => {
  if (selectedStore.value && !storeOptions.value.some((store) => store.value === selectedStore.value)) {
    selectedStore.value = storeOptions.value[0]?.value || ''
  }
})

watch([selectedMonth, selectedBrand, selectedStore, selectedStatus], () => {
  if (initialized.value) void loadExpenseData()
})
</script>

<template>
  <section class="page-panel finance-business-page">
    <PageHeader>
      <template #actions>
        <button v-if="canEditExpense" class="primary-button submit-inline" type="button" @click="openCreate">
          <Plus :size="16" />新增报销
        </button>
      </template>
    </PageHeader>

    <div v-if="finance.error" class="error-box expense-error" role="alert">
      <span>{{ finance.error }}</span>
      <button type="button" :disabled="finance.loading" @click="() => loadExpenseData()">重试</button>
    </div>
    <div v-if="storesError" class="error-box">{{ storesError }}</div>
    <div v-if="finance.actionMessage" class="success-box">{{ finance.actionMessage }}</div>

    <section class="content-card expense-filter-card" :class="{ 'manager-scope': scope.isStoreManager.value }">
      <BusinessScopeBar v-if="scope.isStoreManager.value" />
      <label>
        月份筛选
        <input v-model="selectedMonth" type="month" />
      </label>
      <label v-if="!scope.isStoreManager.value">
        品牌筛选
        <BrandSelect v-model="selectedBrand" :brands="brandOptions" />
      </label>
      <label v-if="!scope.isStoreManager.value">
        门店筛选
        <SearchableSingleSelect
          v-model="selectedStore"
          :options="searchableStoreOptions"
          empty-option-label="全部门店"
          empty-value=""
          placeholder="全部门店"
          search-placeholder="搜索门店名称、编号、区域或状态"
          aria-label="搜索门店"
        />
      </label>
      <label>
        状态筛选
        <select v-model="selectedStatus">
          <option value="">全部状态</option>
          <option v-for="status in statusOptions" :key="status.value" :value="status.value">{{ status.label }}</option>
        </select>
      </label>
    </section>

    <div class="metric-grid">
      <article class="metric-card today-status"><span>今日状态</span><b>{{ todayStatusText }}</b></article>
      <article class="metric-card"><span>待处理</span><b>{{ pendingCount }}</b></article>
      <article class="metric-card"><span>已完成</span><b>{{ doneCount }}</b></article>
      <article class="metric-card"><span>报销总额</span><b>¥{{ Math.round(totalAmount).toLocaleString('zh-CN') }}</b></article>
      <article class="metric-card"><span>记录数量</span><b>{{ filteredExpenses.length }}</b></article>
    </div>

    <div v-if="finance.loading && !filteredExpenses.length" class="empty-state">正在读取报销记录...</div>
    <ExpenseReviewPanel
      v-else
      :expenses="filteredExpenses"
      :actioning-id="finance.actioningId"
      :reviewable="canReviewExpense"
      :editable="canEditExpense"
      @approve="approveExpense"
      @reject="actions.rejectExpense"
      @supplement="openSupplement"
      @request-info="openRequestInfo"
      @edit="editExpense"
      @submit="submitForReview"
      @attachment-deleted="onClaimAttachmentDeleted"
    />
    <ExpenseFormDrawer
      v-if="formOpen"
      :stores="stores"
      :claim="editingExpense"
      :locked-store-id="scope.isStoreManager.value ? scope.boundStoreId.value : ''"
      :locked-store-name="scope.isStoreManager.value ? scope.boundStoreName.value : ''"
      @close="formOpen = false"
      @saved="onSaved"
    />
    <ExpenseSupplementDialog
      v-if="supplementExpense"
      :expense="supplementExpense"
      @close="supplementExpense = null"
      @submitted="onSupplementSubmitted"
    />
    <ExpenseRequestInfoDialog
      v-if="requestInfoExpense"
      :expense="requestInfoExpense"
      @close="requestInfoExpense = null"
      @submitted="onRequestInfoSubmitted"
    />
    <ActionConfirmDialog
      v-model="actions.confirmation.note"
      :open="actions.confirmation.open"
      :title="actions.confirmation.title"
      :message="actions.confirmation.message"
      :confirm-label="actions.confirmation.confirmLabel"
      :confirm-variant="actions.confirmation.confirmVariant"
      :note-label="actions.confirmation.noteLabel"
      :note-placeholder="actions.confirmation.notePlaceholder"
      :note-max-length="actions.confirmation.noteMaxLength"
      :note-required="actions.confirmation.noteRequired"
      :busy="actions.confirmation.busy"
      @cancel="actions.cancelConfirmation"
      @confirm="confirmExpenseAction"
    />
  </section>
</template>

<style scoped>
.finance-business-page {
  display: grid;
  gap: 18px;
}

.expense-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.expense-error button {
  flex: none;
  border: 0;
  background: transparent;
  color: currentColor;
  font-weight: 700;
}

.expense-filter-card {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.expense-filter-card.manager-scope {
  grid-template-columns: minmax(240px, 1fr) minmax(160px, .55fr) minmax(160px, .55fr);
  align-items: end;
}

.expense-filter-card label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.expense-filter-card input,
.expense-filter-card :deep(.brand-select-wrap select),
.expense-filter-card select {
  min-height: 40px;
  padding: 8px 11px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.expense-filter-card :deep(.searchable-single-select) { width: 100%; }

.business-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid rgba(118, 189, 184, 0.22);
  border-left: 4px solid var(--primary);
  border-radius: 14px;
  background: var(--primary-soft);
}

.business-summary b {
  color: var(--primary-dark);
  font-size: 16px;
}

.business-summary span {
  color: var(--muted);
  font-size: 13px;
}

@media (max-width: 720px) {
  .business-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .expense-filter-card {
    grid-template-columns: 1fr;
  }

}
</style>
