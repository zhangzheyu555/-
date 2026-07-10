<script setup lang="ts">
import { onMounted } from 'vue'
import {
  Download, Eye, RefreshCw,
} from 'lucide-vue-next'
import SalaryFilters from '../components/salary/SalaryFilters.vue'
import SalarySummary from '../components/salary/SalarySummary.vue'
import SalaryTable from '../components/salary/SalaryTable.vue'
import SalaryEmptyState from '../components/salary/SalaryEmptyState.vue'
import SalaryGenerationDialog from '../components/salary/SalaryGenerationDialog.vue'
import SalaryDetailDrawer from '../components/salary/SalaryDetailDrawer.vue'
import { useSalaryPage } from '../composables/useSalaryPage'
import { useSalaryWorkflow, recalcGross } from '../composables/useSalaryWorkflow'
import type { SalaryRecord } from '../api/finance'

/* ---- constants ---- */
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTED', label: '待审核' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'PAID', label: '已发放' },
  { value: 'LOCKED', label: '已锁定' },
]

/* ---- composables ---- */
const page = useSalaryPage()

const workflow = useSalaryWorkflow({
  selectedMonth: page.selectedMonth,
  selectedStoreId: page.selectedStoreId,
  hasValidMonth: page.hasValidMonth,
  canGenerate: page.canGenerate,
  canEdit: page.canEdit,
  pageError: page.error,
  successMessage: page.successMessage,
  loadPage: () => page.loadPage(),
})

/* ---- wire drawer recalc ---- */
function onFormFieldInput() {
  recalcGross(workflow.form)
}

/* ---- template helpers (for ref assignment type-safety) ---- */
function closePreview() { workflow.showPreview.value = false }
function switchToEditMode() { workflow.drawerMode.value = 'edit' }
function setMonth(v: string) { page.selectedMonth.value = v }
function setStoreId(v: string) { page.selectedStoreId.value = v }
function setStatus(v: string) { page.statusFilter.value = v }
function setKeyword(v: string) { page.keyword.value = v }

/* ---- lifecycle ---- */
onMounted(async () => {
  await page.loadStores()
  page.applyRouteDefaults()
  page.initializing.value = false
  await page.reloadScopeData(1)
})
</script>

<template>
  <section class="page-panel salary-page">
    <div class="page-head">
      <div>
        <h2>员工工资</h2>
        <span class="head-sub">{{ page.selectedStoreName.value }} · {{ page.selectedMonth.value || '未选择' }}</span>
      </div>
      <div class="head-actions">
        <button v-if="page.canEdit.value && page.selectedStoreId.value !== 'all'" class="primary-button" :disabled="workflow.previewLoading.value || !page.canGenerate.value" @click="workflow.doPreview()">
          <Eye :size="16" /> {{ workflow.previewLoading.value ? '预览中...' : '预览生成名单' }}
        </button>
        <button v-if="page.canEdit.value && page.selectedStoreId.value !== 'all'" class="primary-button submit-inline" :disabled="!page.canGenerate.value" @click="workflow.doGenerate()">
          <RefreshCw :size="16" /> {{ workflow.generating.value ? '生成中...' : '生成本月工资' }}
        </button>
        <button class="ghost-button" :disabled="!page.hasValidMonth.value || page.loading.value" @click="workflow.doExport()"><Download :size="16" /> 导出</button>
        <button class="ghost-button" :disabled="page.loading.value || page.storesLoading.value || page.employeesLoading.value" @click="page.reloadAll()"><RefreshCw :size="16" /> 重新加载</button>
      </div>
    </div>

    <!-- error / success banners -->
    <div v-if="page.storesError.value" class="inline-error"><span>{{ page.storesError.value }}</span><button type="button" @click="page.loadStores()">重试</button></div>
    <div v-if="page.employeesError.value" class="inline-error"><span>{{ page.employeesError.value }}</span><button type="button" @click="page.loadEmployees()">重试</button></div>
    <div v-if="page.error.value" class="inline-error"><span>{{ page.error.value }}</span><button type="button" @click="page.loadPage()">重试</button></div>
    <div v-if="page.successMessage.value" class="success-box">{{ page.successMessage.value }}</div>

    <!-- filters -->
    <SalaryFilters
      :selected-month="page.selectedMonth.value"
      :selected-store-id="page.selectedStoreId.value"
      :status-filter="page.statusFilter.value"
      :keyword="page.keyword.value"
      :stores="page.stores.value"
      :accessible-stores="page.accessibleStores.value"
      :stores-loading="page.storesLoading.value"
      :auth-role="page.canEdit.value ? 'ADMIN' : 'STORE_MANAGER'"
      :status-options="STATUS_OPTIONS"
      @update:selected-month="setMonth"
      @update:selected-store-id="setStoreId"
      @update:status-filter="setStatus"
      @update:keyword="setKeyword"
    />

    <!-- summary -->
    <SalarySummary
      :total="page.total.value"
      :employee-count="page.employeeCount.value"
      :employees-loading="page.employeesLoading.value"
      :status-counts="page.statusCounts.value"
      :gross-total="page.summary.value?.grossTotal"
    />

    <!-- empty states -->
    <SalaryEmptyState
      v-if="page.isEmployeeEmpty.value"
      type="no-employee"
      :can-edit="page.canEdit.value"
      :can-generate="page.canGenerate.value"
      :preview-loading="workflow.previewLoading.value"
      :selected-store-id="page.selectedStoreId.value"
      @preview="workflow.doPreview()"
      @generate="workflow.doGenerate()"
    />
    <SalaryEmptyState
      v-else-if="page.isEmpty.value"
      type="no-salary"
      :can-edit="page.canEdit.value"
      :can-generate="page.canGenerate.value"
      :preview-loading="workflow.previewLoading.value"
      :selected-store-id="page.selectedStoreId.value"
      @preview="workflow.doPreview()"
      @generate="workflow.doGenerate()"
    />

    <!-- table -->
    <SalaryTable
      v-if="page.showTable.value"
      :rows="page.filteredRows.value"
      :total="page.total.value"
      :page="page.page.value"
      :total-pages="page.totalPages.value"
      :loading="page.loading.value"
      :can-edit="page.canEdit.value"
      :can-review="page.canReview.value"
      :actioning-id="workflow.actioningId.value"
      :deleting-id="workflow.deletingId.value"
      @page-change="(p: number) => page.loadPage(p)"
      @view="(r: SalaryRecord) => workflow.openDrawer(r, 'view')"
      @edit="(r: SalaryRecord) => workflow.openDrawer(r, 'edit')"
      @submit="(r: SalaryRecord) => workflow.doSubmit(r)"
      @approve="(r: SalaryRecord) => workflow.doApprove(r)"
      @reject="(r: SalaryRecord) => workflow.doReject(r)"
      @delete="(r: SalaryRecord) => workflow.doDelete(r)"
      @mark-paid="(r: SalaryRecord) => workflow.doMarkPaid(r)"
      @lock="(r: SalaryRecord) => workflow.doLock(r)"
    />

    <!-- generation preview dialog -->
    <SalaryGenerationDialog
      :show="workflow.showPreview.value"
      :preview-data="workflow.previewData.value"
      :preview-loading="workflow.previewLoading.value"
      :generating="workflow.generating.value"
      :can-generate="page.canGenerate.value"
      @close="closePreview()"
      @generate="workflow.doGenerate()"
    />

    <!-- detail / edit drawer -->
    <SalaryDetailDrawer
      :show="workflow.showDrawer.value"
      :mode="workflow.drawerMode.value"
      :record="workflow.drawerRecord.value"
      :form="workflow.form"
      :form-error="workflow.formError.value"
      :saving="workflow.saving.value"
      :can-edit="page.canEdit.value"
      :store-map="page.storeMap.value"
      @close="workflow.closeDrawer()"
      @edit="switchToEditMode()"
      @save="workflow.doSave()"
      @update:form="onFormFieldInput()"
    />
  </section>
</template>

<style scoped>
.salary-page { display: grid; gap: 16px; max-width: 1280px; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.head-sub { color: var(--muted); font-size: 13px; margin-left: 8px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.inline-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 12px; border-left: 3px solid var(--bad); background: rgba(220,38,38,0.06); color: var(--bad); font-size: 13px; }
.inline-error button { flex: none; border: 0; background: transparent; color: inherit; font-weight: 800; cursor: pointer; }

@media (max-width: 720px) {
  .head-actions { grid-template-columns: 1fr; width: 100%; }
  .head-actions .ghost-button, .head-actions .primary-button { width: 100%; }
}
</style>
