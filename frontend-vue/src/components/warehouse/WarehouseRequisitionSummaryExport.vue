<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Download } from 'lucide-vue-next'
import {
  downloadWarehouseRequisitionSummary,
  type WarehouseItem,
  type WarehouseRequisitionSummaryPeriodType,
} from '../../api/warehouse'
import type { StoreInfo } from '../../api/operations'
import SearchableMultiSelect from '../common/SearchableMultiSelect.vue'

type ScopeMode = 'ALL' | 'SELECTED'

const props = defineProps<{
  warehouseId?: string | number
  stores: StoreInfo[]
  items: WarehouseItem[]
}>()

function localDateValue(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const today = new Date()
const startDate = ref(localDateValue(new Date(today.getFullYear(), today.getMonth(), 1)))
const endDate = ref(localDateValue(today))
const storeScopeMode = ref<ScopeMode>('ALL')
const productScopeMode = ref<ScopeMode>('ALL')
const selectedStoreIds = ref<string[]>([])
const selectedProductIds = ref<number[]>([])
const periodType = ref<WarehouseRequisitionSummaryPeriodType>('MONTH')
const includeZeroRows = ref(false)
const exporting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const storeOptions = computed(() => {
  const hasWarehouseAssignments = props.stores.some((store) => (
    store.supplyWarehouseId !== undefined && store.supplyWarehouseId !== null
  ))
  return [...props.stores]
    .filter((store) => String(store.id || '').trim())
    .filter((store) => (
      !hasWarehouseAssignments
      || props.warehouseId === undefined
      || props.warehouseId === null
      || String(store.supplyWarehouseId) === String(props.warehouseId)
    ))
    .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
})
const searchableStoreOptions = computed(() => storeOptions.value.map((store) => ({
  value: String(store.id),
  label: store.name || store.code || String(store.id),
  description: [store.code || store.id, store.area || store.regionCode, store.status].filter(Boolean).join(' · '),
  searchText: [store.name, store.code, store.id, store.area, store.regionCode, store.status, store.brandName].filter(Boolean).join(' '),
})))

const productOptions = computed(() => [...props.items]
  .filter((item) => Number.isFinite(Number(item.id)))
  .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')))
const searchableProductOptions = computed(() => productOptions.value.map((item) => {
  const category = item.categoryName || item.category
  const unit = item.purchaseUnit || item.stockUnit || item.unit || item.ingredientUnit
  return {
    value: Number(item.id),
    label: item.name || item.code || String(item.id),
    description: [item.code, category, unit].filter(Boolean).join(' · '),
    searchText: [
      item.name,
      item.code,
      category,
      item.purchaseUnit,
      item.stockUnit,
      item.unit,
      item.ingredientUnit,
      item.spec,
    ].filter(Boolean).join(' '),
  }
}))

function clearResultMessages() {
  errorMessage.value = ''
  successMessage.value = ''
}

watch(
  () => storeOptions.value.map((store) => String(store.id)),
  (storeIds) => {
    const allowedIds = new Set(storeIds)
    selectedStoreIds.value = selectedStoreIds.value.filter((storeId) => allowedIds.has(storeId))
  },
  { immediate: true },
)

watch(
  () => props.items.map((item) => Number(item.id)),
  (productIds) => {
    const allowedIds = new Set(productIds)
    selectedProductIds.value = selectedProductIds.value.filter((productId) => allowedIds.has(productId))
  },
  { immediate: true },
)

watch(storeScopeMode, (mode) => {
  if (mode === 'ALL') selectedStoreIds.value = []
})

watch(productScopeMode, (mode) => {
  if (mode === 'ALL') selectedProductIds.value = []
})

watch(
  [
    startDate,
    endDate,
    storeScopeMode,
    productScopeMode,
    selectedStoreIds,
    selectedProductIds,
    periodType,
    includeZeroRows,
  ],
  clearResultMessages,
)

const storeSelectionError = computed(() => {
  if (storeScopeMode.value !== 'SELECTED' || selectedStoreIds.value.length) return ''
  return storeOptions.value.length
    ? '请至少选择一家门店'
    : '当前仓库没有可供指定的门店'
})

const productSelectionError = computed(() => {
  if (productScopeMode.value !== 'SELECTED' || selectedProductIds.value.length) return ''
  return productOptions.value.length
    ? '请至少选择一项物料'
    : '当前仓库没有可供指定的物料'
})

const selectedScopeSummary = computed(() => {
  const stores = storeScopeMode.value === 'ALL'
    ? `全部门店（${storeOptions.value.length} 家）`
    : `指定门店（${selectedStoreIds.value.length} 家）`
  const products = productScopeMode.value === 'ALL'
    ? `全部物料（${productOptions.value.length} 项）`
    : `指定物料（${selectedProductIds.value.length} 项）`
  return `${stores}，${products}`
})

function validate() {
  if (!startDate.value || !endDate.value) return '请选择完整的开始日期和结束日期'
  if (startDate.value > endDate.value) return '开始日期不能晚于结束日期'
  if (!periodType.value) return '请选择周期粒度'
  if (storeSelectionError.value) return storeSelectionError.value
  if (productSelectionError.value) return productSelectionError.value
  return ''
}

function fallbackFilename() {
  const range = `${startDate.value.replace(/-/g, '')}_${endDate.value.replace(/-/g, '')}`
  return `requisition_report_汇总_门店-物料-周期_${range}.xlsx`
}

async function exportSummary() {
  if (exporting.value) return
  errorMessage.value = validate()
  successMessage.value = ''
  if (errorMessage.value) return

  exporting.value = true
  try {
    const filename = await downloadWarehouseRequisitionSummary({
      ...(props.warehouseId === undefined || props.warehouseId === null
        ? {}
        : { warehouseId: props.warehouseId }),
      startDate: startDate.value,
      endDate: endDate.value,
      storeIds: storeScopeMode.value === 'SELECTED' ? [...selectedStoreIds.value] : [],
      productIds: productScopeMode.value === 'SELECTED'
        ? selectedProductIds.value.map(Number)
        : [],
      periodType: periodType.value,
      includeZeroRows: includeZeroRows.value,
      groupBy: ['store', 'product', 'period'],
    }, fallbackFilename())
    successMessage.value = `聚合报表已导出：${filename}`
  } catch (error) {
    errorMessage.value = error instanceof Error && error.message
      ? error.message
      : '报表导出失败，请稍后重试'
  } finally {
    exporting.value = false
  }
}

function resetScopeSelection() {
  storeScopeMode.value = 'ALL'
  productScopeMode.value = 'ALL'
  selectedStoreIds.value = []
  selectedProductIds.value = []
  clearResultMessages()
}
</script>

<template>
  <section class="content-card requisition-summary-export" aria-labelledby="requisition-summary-title">
    <div class="summary-heading">
      <div>
        <span class="eyebrow">按门店 · 物料 · 周期聚合</span>
        <h3 id="requisition-summary-title">叫货汇总报表</h3>
        <p>统计叫货明细的申请数量和金额；不影响下方逐张叫货单的配送单下载。</p>
      </div>
      <Download :size="22" aria-hidden="true" />
    </div>

    <form class="summary-form" novalidate @submit.prevent="exportSummary">
      <div class="date-fields">
        <label class="summary-field">
          <span>开始日期 <b aria-hidden="true">*</b></span>
          <input
            v-model="startDate"
            type="date"
            aria-label="报表开始日期"
            :disabled="exporting"
            required
          />
        </label>
        <label class="summary-field">
          <span>结束日期 <b aria-hidden="true">*</b></span>
          <input
            v-model="endDate"
            type="date"
            aria-label="报表结束日期"
            :disabled="exporting"
            required
          />
        </label>
        <label class="summary-field">
          <span>周期粒度 <b aria-hidden="true">*</b></span>
          <select v-model="periodType" aria-label="周期粒度" :disabled="exporting" required>
            <option value="DAY">日报</option>
            <option value="WEEK">周报</option>
            <option value="MONTH">月报</option>
          </select>
        </label>
      </div>

      <div class="scope-fields">
        <fieldset class="scope-field" :disabled="exporting">
          <legend>门店范围 <b aria-hidden="true">*</b></legend>
          <div class="scope-mode-options">
            <label :class="{ active: storeScopeMode === 'ALL' }">
              <input
                v-model="storeScopeMode"
                type="radio"
                name="requisition-summary-store-scope"
                value="ALL"
              />
              <span>全部门店</span>
            </label>
            <label :class="{ active: storeScopeMode === 'SELECTED' }">
              <input
                v-model="storeScopeMode"
                type="radio"
                name="requisition-summary-store-scope"
                value="SELECTED"
              />
              <span>指定门店</span>
            </label>
          </div>
          <SearchableMultiSelect
            v-if="storeScopeMode === 'SELECTED'"
            :model-value="selectedStoreIds"
            :options="searchableStoreOptions"
            selected-noun="家门店"
            search-placeholder="搜索门店名称、编号或区域"
            aria-label="报表门店"
            :placeholder="storeOptions.length ? '请选择门店' : '当前仓库没有可选门店'"
            :disabled="exporting || !storeOptions.length"
            compact
            @update:model-value="selectedStoreIds = $event.map(String)"
          />
          <small v-if="storeScopeMode === 'ALL'" class="scope-help">
            导出当前仓库内账号有权查看的全部门店
          </small>
          <small v-else-if="storeSelectionError" class="scope-error" role="status">
            {{ storeSelectionError }}
          </small>
          <small v-else class="scope-help">已指定 {{ selectedStoreIds.length }} 家门店</small>
        </fieldset>

        <fieldset class="scope-field" :disabled="exporting">
          <legend>物料范围 <b aria-hidden="true">*</b></legend>
          <div class="scope-mode-options">
            <label :class="{ active: productScopeMode === 'ALL' }">
              <input
                v-model="productScopeMode"
                type="radio"
                name="requisition-summary-product-scope"
                value="ALL"
              />
              <span>全部物料</span>
            </label>
            <label :class="{ active: productScopeMode === 'SELECTED' }">
              <input
                v-model="productScopeMode"
                type="radio"
                name="requisition-summary-product-scope"
                value="SELECTED"
              />
              <span>指定物料</span>
            </label>
          </div>
          <SearchableMultiSelect
            v-if="productScopeMode === 'SELECTED'"
            :model-value="selectedProductIds"
            :options="searchableProductOptions"
            selected-noun="项物料"
            search-placeholder="搜索物料名称、编号、分类或单位"
            aria-label="报表物料"
            :placeholder="productOptions.length ? '请选择物料' : '当前仓库没有可选物料'"
            :disabled="exporting || !productOptions.length"
            compact
            @update:model-value="selectedProductIds = $event.map(Number)"
          />
          <small v-if="productScopeMode === 'ALL'" class="scope-help">
            导出当前仓库内账号有权查看的全部物料
          </small>
          <small v-else-if="productSelectionError" class="scope-error" role="status">
            {{ productSelectionError }}
          </small>
          <small v-else class="scope-help">已指定 {{ selectedProductIds.length }} 项物料</small>
        </fieldset>
      </div>

      <label class="zero-row-option">
        <input
          v-model="includeZeroRows"
          type="checkbox"
          aria-label="包含零量组合"
          :disabled="exporting"
        />
        <span>
          <strong>包含零量组合</strong>
          <small>会补齐所选门店、物料和周期中没有叫货记录的行；范围较大时文件会明显增大。</small>
        </span>
      </label>

      <div class="summary-actions">
        <div>
          <strong>{{ selectedScopeSummary }}</strong>
          <span>门店与物料按同时满足（AND）筛选；“全部”仅限当前仓库及当前账号的数据权限。</span>
        </div>
        <div class="action-buttons">
          <button
            class="secondary-action"
            type="button"
            :disabled="exporting || (storeScopeMode === 'ALL' && productScopeMode === 'ALL')"
            @click="resetScopeSelection"
          >
            重置选择
          </button>
          <button class="primary-action" type="submit" :disabled="exporting">
            <Download :size="16" aria-hidden="true" />
            {{ exporting ? '正在导出…' : '导出聚合报表' }}
          </button>
        </div>
      </div>

      <p v-if="errorMessage" class="form-message error" role="alert">{{ errorMessage }}</p>
      <p v-else-if="successMessage" class="form-message success" role="status">{{ successMessage }}</p>
    </form>
  </section>
</template>

<style scoped>
.requisition-summary-export {
  display: grid;
  gap: 18px;
}

.summary-heading,
.summary-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.summary-heading {
  padding-bottom: 15px;
  border-bottom: 1px solid var(--ds-line);
}

.summary-heading > svg {
  flex: 0 0 auto;
  color: var(--ds-primary-hover);
}

.eyebrow {
  display: block;
  margin-bottom: 5px;
  color: var(--ds-primary-hover);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.summary-heading h3 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 18px;
}

.summary-heading p {
  margin: 6px 0 0;
  color: var(--ds-muted);
  font-size: 13px;
}

.summary-form {
  display: grid;
  gap: 16px;
}

.zero-row-option {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--ds-line);
  border-radius: 10px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  cursor: pointer;
}

.zero-row-option input {
  width: 16px;
  height: 16px;
  margin-top: 2px;
}

.zero-row-option span {
  display: grid;
  gap: 3px;
}

.zero-row-option small {
  color: var(--ds-muted);
  font-weight: 400;
}

.date-fields,
.scope-fields {
  display: grid;
  gap: 12px;
}

.date-fields {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.scope-fields {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.scope-field {
  display: grid;
  min-width: 0;
  margin: 0;
  padding: 13px;
  gap: 10px;
  border: 1px solid var(--ds-line);
  border-radius: 9px;
  background: var(--ds-surface-muted);
}

.scope-field legend {
  padding: 0 5px;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 800;
}

.scope-field legend b {
  color: var(--ds-danger);
}

.scope-mode-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.scope-mode-options label {
  display: flex;
  min-width: 0;
  min-height: 38px;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: #fff;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.scope-mode-options label.active {
  border-color: var(--ds-primary-hover);
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.scope-mode-options input {
  flex: 0 0 auto;
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: var(--ds-primary-hover);
}

.scope-field:disabled .scope-mode-options label {
  cursor: wait;
  opacity: 0.7;
}

.scope-help,
.scope-error {
  min-height: 18px;
  font-size: 12px;
  font-weight: 600;
}

.scope-help {
  color: var(--ds-muted);
}

.scope-error {
  color: var(--ds-danger);
}

.summary-field {
  display: grid;
  min-width: 0;
  gap: 7px;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 700;
}

.summary-field b {
  color: var(--ds-danger);
}

.summary-field input,
.summary-field select {
  width: 100%;
  min-width: 0;
}

.summary-field :deep(.searchable-multi-select) {
  width: 100%;
}

.summary-field small {
  color: var(--ds-muted);
  font-size: 12px;
  font-weight: 500;
}

.summary-actions {
  padding-top: 2px;
}

.summary-actions > div:first-child {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.summary-actions strong {
  color: var(--ds-ink);
  font-size: 13px;
}

.summary-actions span {
  color: var(--ds-muted);
  font-size: 12px;
}

.action-buttons {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.secondary-action,
.primary-action {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 16px;
  border: 1px solid var(--ds-primary-hover);
  border-radius: 8px;
  background: var(--ds-primary-hover);
  color: #fff;
  font-weight: 800;
  cursor: pointer;
}

.secondary-action {
  border-color: var(--ds-line);
  background: #fff;
  color: var(--ds-primary-hover);
}

.secondary-action:disabled {
  color: var(--ds-muted);
  cursor: not-allowed;
  opacity: 0.65;
}

.primary-action:disabled {
  cursor: wait;
  opacity: 0.62;
}

.form-message {
  margin: 0;
  padding: 10px 12px;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 700;
}

.form-message.error {
  background: var(--ds-danger-soft);
  color: var(--ds-danger);
}

.form-message.success {
  background: var(--ds-success-soft);
  color: var(--ds-success);
}

@media (max-width: 760px) {
  .date-fields,
  .scope-fields {
    grid-template-columns: 1fr;
  }

  .summary-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .action-buttons {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
    width: 100%;
  }

  .secondary-action,
  .primary-action {
    width: 100%;
  }
}

@media (max-width: 430px) {
  .scope-mode-options,
  .action-buttons {
    grid-template-columns: 1fr;
  }

  .scope-field :deep(.searchable-multi-select.is-compact .searchable-multi-select__panel) {
    right: auto;
    left: 0;
    width: 100%;
    min-width: 0;
    max-width: 100%;
  }
}
</style>
