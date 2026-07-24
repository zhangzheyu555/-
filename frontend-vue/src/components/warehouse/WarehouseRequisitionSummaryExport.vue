<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Download } from 'lucide-vue-next'
import {
  downloadWarehouseRequisitionSummary,
  type WarehouseItem,
  type WarehouseRequisitionSummaryPeriodType,
} from '../../api/warehouse'
import type { StoreInfo } from '../../api/operations'

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

const productOptions = computed(() => [...props.items]
  .filter((item) => Number.isFinite(Number(item.id)))
  .sort((left, right) => left.name.localeCompare(right.name, 'zh-CN')))

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

const selectedScopeSummary = computed(() => {
  const stores = selectedStoreIds.value.length
    ? `已选 ${selectedStoreIds.value.length} 家门店`
    : '全部授权门店'
  const products = selectedProductIds.value.length
    ? `已选 ${selectedProductIds.value.length} 项物料`
    : '全部可见物料'
  return `${stores}，${products}`
})

function validate() {
  if (!startDate.value || !endDate.value) return '请选择完整的开始日期和结束日期'
  if (startDate.value > endDate.value) return '开始日期不能晚于结束日期'
  if (!periodType.value) return '请选择周期粒度'
  return ''
}

function fallbackFilename() {
  const range = `${startDate.value.replace(/-/g, '')}_${endDate.value.replace(/-/g, '')}`
  return `requisition_report_汇总_门店-物料-周期_${range}.xlsx`
}

async function exportSummary() {
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
      storeIds: [...selectedStoreIds.value],
      productIds: selectedProductIds.value.map(Number),
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
          <input v-model="startDate" type="date" aria-label="报表开始日期" required />
        </label>
        <label class="summary-field">
          <span>结束日期 <b aria-hidden="true">*</b></span>
          <input v-model="endDate" type="date" aria-label="报表结束日期" required />
        </label>
        <label class="summary-field">
          <span>周期粒度 <b aria-hidden="true">*</b></span>
          <select v-model="periodType" aria-label="周期粒度" required>
            <option value="DAY">日报</option>
            <option value="WEEK">周报</option>
            <option value="MONTH">月报</option>
          </select>
        </label>
      </div>

      <div class="scope-fields">
        <label class="summary-field">
          <span>门店（可多选）</span>
          <select v-model="selectedStoreIds" aria-label="报表门店" multiple size="4">
            <option v-for="store in storeOptions" :key="store.id" :value="String(store.id)">
              {{ store.name }}（{{ store.code || store.id }}）
            </option>
          </select>
          <small>不选择表示全部授权门店</small>
        </label>
        <label class="summary-field">
          <span>物料（可多选）</span>
          <select v-model="selectedProductIds" aria-label="报表物料" multiple size="4">
            <option v-for="item in productOptions" :key="item.id" :value="item.id">
              {{ item.name }}（{{ item.code }}）
            </option>
          </select>
          <small>不选择表示全部可见物料</small>
        </label>
      </div>

      <label class="zero-row-option">
        <input v-model="includeZeroRows" type="checkbox" aria-label="包含零量组合" />
        <span>
          <strong>包含零量组合</strong>
          <small>会补齐所选门店、物料和周期中没有叫货记录的行；范围较大时文件会明显增大。</small>
        </span>
      </label>

      <div class="summary-actions">
        <div>
          <strong>{{ selectedScopeSummary }}</strong>
          <span>导出维度固定为门店、物料、周期组合</span>
        </div>
        <button class="primary-action" type="submit" :disabled="exporting">
          <Download :size="16" aria-hidden="true" />
          {{ exporting ? '正在导出…' : '导出聚合报表' }}
        </button>
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

.summary-field select[multiple] {
  min-height: 112px;
  padding: 6px;
}

.summary-field select[multiple] option {
  padding: 7px 8px;
  border-radius: 5px;
}

.summary-field small {
  color: var(--ds-muted);
  font-size: 12px;
  font-weight: 500;
}

.summary-actions {
  padding-top: 2px;
}

.summary-actions > div {
  display: grid;
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

  .primary-action {
    width: 100%;
  }
}
</style>
