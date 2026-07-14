<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import BusinessScopeBar from '../components/common/BusinessScopeBar.vue'
import ExpenseReviewPanel from '../components/finance/ExpenseReviewPanel.vue'
import ExpenseFormDrawer from '../components/finance/ExpenseFormDrawer.vue'
import ExpenseSupplementDialog from '../components/finance/ExpenseSupplementDialog.vue'
import ExpenseRequestInfoDialog from '../components/finance/ExpenseRequestInfoDialog.vue'
import ActionConfirmDialog from '../components/ui/ActionConfirmDialog.vue'
import BrandSelect from '../components/common/BrandSelect.vue'
import { useFinanceActions } from '../composables/useFinanceActions'
import { useBusinessScope } from '../composables/useBusinessScope'
import { submitExpense, type ExpenseClaim } from '../api/finance'
import { getStores, type StoreInfo } from '../api/operations'
import { useFinanceStore } from '../stores/finance'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import { isSameBrand, normalizeBrandName } from '../utils/brand'

const finance = useFinanceStore()
const actions = useFinanceActions()
const auth = useAuthStore()
const scope = useBusinessScope()
const selectedMonth = ref('')
const selectedBrand = ref(scope.isStoreManager.value ? scope.brandName.value : '')
const selectedStore = ref(scope.scopedStoreId())
const selectedStatus = ref('')
const stores = ref<StoreInfo[]>([])
const storesError = ref('')
const storesLoading = ref(false)
const formOpen = ref(false)
const editingExpense = ref<ExpenseClaim | null>(null)
const supplementExpense = ref<ExpenseClaim | null>(null)
const requestInfoExpense = ref<ExpenseClaim | null>(null)

const canEditExpense = computed(() => auth.hasPermission(PERMISSIONS.EXPENSE_CREATE))
const canReviewExpense = computed(() => auth.hasPermission(PERMISSIONS.EXPENSE_REVIEW))
const brandOptions = computed(() => Array.from(new Set(stores.value.map((item) => normalizeBrandName(item.brandName) || '').filter(Boolean))).map((name) => ({ name })))
const storeOptions = computed(() => {
  return stores.value
    .filter((store) => !selectedBrand.value || isSameBrand(store.brandName, selectedBrand.value))
    .map((store) => ({
      value: store.id,
      label: store.name || store.id,
      brandName: normalizeBrandName(store.brandName),
    }))
})
const statusOptions = computed(() => Array.from(new Set(finance.expenseReviews.map((item) => item.status || '').filter(Boolean))))
const filteredExpenses = computed(() => finance.expenseReviews.filter((item) => {
  if (scope.isStoreManager.value && item.storeId !== scope.boundStoreId.value) return false
  if (selectedMonth.value && item.month !== selectedMonth.value) return false
  if (selectedBrand.value && !isSameBrand(item.brandName, selectedBrand.value)) return false
  if (selectedStore.value && item.storeId !== selectedStore.value) return false
  if (selectedStatus.value && item.status !== selectedStatus.value) return false
  return true
}))
const pendingCount = computed(() => filteredExpenses.value.filter((item) => !['已完成', '已通过', 'APPROVED', '已驳回', 'REJECTED'].includes(item.status)).length)
const doneCount = computed(() => filteredExpenses.value.filter((item) => ['已完成', '已通过', 'APPROVED'].includes(item.status)).length)
const totalAmount = computed(() => filteredExpenses.value.reduce((total, item) => total + Number(item.amount || 0), 0))

function isAuthError(err: unknown) {
  return err instanceof Error && (err.message.includes('登录已失效') || err.message.includes('请先登录') || err.message.includes('UNAUTHORIZED'))
}

function isForbiddenError(err: unknown) {
  return err instanceof Error && (err.message.includes('FORBIDDEN') || err.message.includes('403') || err.message.includes('无权'))
}

async function refresh() {
  storesError.value = ''
  finance.error = ''

  try {
    const results = await Promise.allSettled([
      loadStores(),
      finance.load(),
    ])

    for (const result of results) {
      if (result.status === 'rejected') {
        if (isAuthError(result.reason)) return
      }
    }
  } catch (err) {
    if (isAuthError(err)) return
  }
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
    selectedBrand.value = scope.brandName.value
    selectedStore.value = storeId
    return
  }
  storesLoading.value = true
  storesError.value = ''
  try {
    stores.value = await getStores()
  } catch (err) {
    if (isAuthError(err)) return
    if (isForbiddenError(err)) {
      storesError.value = '无权查看门店列表'
      return
    }
    console.error('[Expense] stores load failed', err)
    storesError.value = '门店列表加载失败，请稍后重试'
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
  await refresh()
}

async function onRequestInfoSubmitted() {
  requestInfoExpense.value = null
  finance.actionMessage = '已要求门店补充资料'
  await refresh()
}

async function submitForReview(expense: ExpenseClaim) {
  finance.actioningId = expense.id
  finance.error = ''
  try {
    await submitExpense(expense.id)
    finance.actionMessage = '报销已提交审核'
    await refresh()
  } catch (reason) {
    finance.error = reason instanceof Error ? reason.message : '报销提交失败，请稍后重试。'
  } finally {
    finance.actioningId = ''
  }
}

async function onSaved() {
  formOpen.value = false
  finance.actionMessage = '报销已保存'
  await refresh()
}

onMounted(() => {
  void refresh()
})

watch(selectedBrand, () => {
  if (selectedStore.value && !storeOptions.value.some((store) => store.value === selectedStore.value)) {
    selectedStore.value = storeOptions.value[0]?.value || ''
  }
})
</script>

<template>
  <section class="page-panel finance-business-page">
    <PageHeader>
      <template #actions>
        <div class="expense-actions">
          <button class="ghost-button" type="button" :disabled="finance.loading" @click="refresh">
            <RefreshCw :size="16" />刷新
          </button>
          <button v-if="canEditExpense" class="primary-button submit-inline" type="button" @click="openCreate">
            <Plus :size="16" />新增报销
          </button>
        </div>
      </template>
    </PageHeader>

    <div v-if="finance.error" class="error-box">{{ finance.error }}</div>
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
        <select v-model="selectedStore" aria-label="门店">
          <option value="">全部门店</option>
          <option v-for="store in storeOptions" :key="store.value" :value="store.value">{{ store.brandName }} · {{ store.label }}</option>
        </select>
      </label>
      <label>
        状态筛选
        <select v-model="selectedStatus">
          <option value="">全部状态</option>
          <option v-for="status in statusOptions" :key="status" :value="status">{{ status }}</option>
        </select>
      </label>
    </section>

    <div class="metric-grid">
      <article class="metric-card"><span>待处理</span><b>{{ pendingCount }}</b></article>
      <article class="metric-card"><span>已完成</span><b>{{ doneCount }}</b></article>
      <article class="metric-card"><span>报销总额</span><b>¥{{ Math.round(totalAmount).toLocaleString('zh-CN') }}</b></article>
      <article class="metric-card"><span>记录数量</span><b>{{ filteredExpenses.length }}</b></article>
    </div>

    <div v-if="finance.loading && !finance.expenseReviews.length" class="empty-state">正在读取报销记录...</div>
    <ExpenseReviewPanel
      v-else
      :expenses="filteredExpenses"
      :actioning-id="finance.actioningId"
      :reviewable="canReviewExpense"
      :editable="canEditExpense"
      @approve="actions.approveExpense"
      @reject="actions.rejectExpense"
      @supplement="openSupplement"
      @request-info="openRequestInfo"
      @escalate="actions.escalateExpense"
      @edit="editExpense"
      @submit="submitForReview"
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
      :busy="actions.confirmation.busy"
      @cancel="actions.cancelConfirmation"
      @confirm="actions.confirmAction"
    />
  </section>
</template>

<style scoped>
.finance-business-page {
  display: grid;
  gap: 18px;
}

.expense-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
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
  .business-summary,
  .expense-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .expense-filter-card {
    grid-template-columns: 1fr;
  }

  .expense-actions,
  .expense-actions button {
    width: 100%;
  }
}
</style>
