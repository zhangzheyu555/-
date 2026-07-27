<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { AlertTriangle, ImagePlus, Plus, Trash2, X } from 'lucide-vue-next'
import type {
  WarehouseItem,
  WarehouseItemCategory,
  WarehouseItemDepartment,
  WarehouseItemPayload,
  WarehouseItemRequisitionScopeStore,
  WarehouseItemRequisitionScopeMode,
} from '../../api/warehouse'
import ActionConfirmDialog from '../ui/ActionConfirmDialog.vue'
import SearchableMultiSelect from '../common/SearchableMultiSelect.vue'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

export interface MaterialSubmitError {
  message: string
  code?: string
  status?: number
  target?: 'form' | 'category' | 'scope' | 'region' | 'store' | 'time' | 'image'
  retryable: boolean
}

interface RegionOption {
  code: string
  name: string
}

type MaterialValidationTarget =
  | 'name'
  | 'category'
  | 'code'
  | 'unitPrice'
  | 'shelfLifeDays'
  | 'minStockQuantity'
  | 'expiryAlertDays'
  | 'sortOrder'
  | 'scope'
  | 'time'

interface MaterialValidationIssue {
  target: MaterialValidationTarget
  message: string
}

type NumericInputValue = string | number

const props = defineProps<{
  item: WarehouseItem | null
  categories: WarehouseItemCategory[]
  stores: WarehouseItemRequisitionScopeStore[]
  regions: RegionOption[]
  scopeContextAvailable: boolean
  saving: boolean
  submitError?: MaterialSubmitError | null
}>()

const emit = defineEmits<{
  close: []
  save: [payload: WarehouseItemPayload]
}>()

interface MaterialForm {
  id?: number
  code: string
  name: string
  categoryId: number | null
  imageUrl: string
  purchaseUnit: string
  stockUnit: string
  ingredientUnit: string
  unitConversionText: string
  spec: string
  warehouseLocation: string
  unitPrice: NumericInputValue
  shelfLifeDays: NumericInputValue
  minStockQuantity: NumericInputValue
  expiryAlertDays: NumericInputValue
  itemDescription: string
  sortOrder: NumericInputValue
  itemAttributes: string
  active: boolean
  departments: WarehouseItemDepartment[]
  requisitionScopeMode: '' | WarehouseItemRequisitionScopeMode
  requisitionRegionCodes: string[]
  requisitionStoreIds: string[]
  campaignName: string
  startsAt: string
  endsAt: string
}

const fileInput = ref<HTMLInputElement | null>(null)
const editorRef = ref<HTMLElement | null>(null)
const imageError = ref('')
const form = reactive<MaterialForm>(emptyForm())
const openingSnapshot = ref('')
const unsavedDialogOpen = ref(false)
const allStoresConfirmationOpen = ref(false)
const allStoresConfirmationNote = ref('')
const pendingPayload = ref<WarehouseItemPayload | null>(null)
const submitErrorRef = ref<HTMLElement | null>(null)
const localSubmitError = ref<MaterialSubmitError | null>(null)
const validationAttempted = ref(false)
const nameInputRef = ref<HTMLInputElement | null>(null)
const categoryInputRef = ref<HTMLSelectElement | null>(null)
const codeInputRef = ref<HTMLInputElement | null>(null)
const unitPriceInputRef = ref<HTMLInputElement | null>(null)
const shelfLifeDaysInputRef = ref<HTMLInputElement | null>(null)
const minStockQuantityInputRef = ref<HTMLInputElement | null>(null)
const expiryAlertDaysInputRef = ref<HTMLInputElement | null>(null)
const sortOrderInputRef = ref<HTMLInputElement | null>(null)
const scopePanelRef = ref<HTMLElement | null>(null)
const startsAtInputRef = ref<HTMLInputElement | null>(null)
let previouslyFocusedElement: HTMLElement | null = null
let previousBodyOverflow = ''

function clearSubmitError() {
  localSubmitError.value = null
}

function clearFieldErrorIfTarget(target: MaterialSubmitError['target']) {
  if (localSubmitError.value?.target === target) {
    localSubmitError.value = null
  }
}

watch(() => props.submitError, (error) => {
  localSubmitError.value = error ?? null
  if (error) {
    void nextTick(() => {
      if (error.target && error.target !== 'form') {
        const fieldEl = editorRef.value?.querySelector(`[data-error-target="${error.target}"]`)
        if (fieldEl instanceof HTMLElement) {
          fieldEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
          fieldEl.focus()
          return
        }
      }
      submitErrorRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      submitErrorRef.value?.focus()
    })
  }
})

watch(() => form.categoryId, () => clearFieldErrorIfTarget('category'))
watch(() => form.requisitionScopeMode, () => clearFieldErrorIfTarget('scope'))
watch(() => form.requisitionRegionCodes, () => clearFieldErrorIfTarget('region'), { deep: true })
watch(() => form.requisitionStoreIds, () => clearFieldErrorIfTarget('store'), { deep: true })
watch(() => form.campaignName, () => clearFieldErrorIfTarget('time'))
watch(() => form.startsAt, () => clearFieldErrorIfTarget('time'))
watch(() => form.endsAt, () => clearFieldErrorIfTarget('time'))
watch(() => form.imageUrl, () => clearFieldErrorIfTarget('image'))

const categoryOptions = computed(() => flattenCategories(props.categories))
const dirty = computed(() => Boolean(openingSnapshot.value) && snapshotForm() !== openingSnapshot.value)
const activeStores = computed(() => props.stores)
const activeStoreOptions = computed(() => activeStores.value.map((store) => ({
  value: store.id,
  label: store.name || store.id,
  description: store.regionCode ? `${regionLabel(store.regionCode)} · ${store.regionCode}` : '未配置区域',
  searchText: [store.name, store.id, store.regionCode, store.regionCode ? regionLabel(store.regionCode) : ''].filter(Boolean).join(' '),
})))
const regionOptions = computed<RegionOption[]>(() => {
  const options = new Map<string, string>()
  for (const region of props.regions) {
    if (region.code?.trim()) options.set(region.code.trim().toUpperCase(), region.name?.trim() || regionLabel(region.code))
  }
  for (const store of activeStores.value) {
    if (store.regionCode?.trim()) {
      const code = store.regionCode.trim().toUpperCase()
      if (!options.has(code)) options.set(code, regionLabel(code))
    }
  }
  return Array.from(options, ([code, name]) => ({ code, name }))
})
const affectedStoreCount = computed(() => {
  if (form.requisitionScopeMode === 'ALL') return activeStores.value.length
  if (form.requisitionScopeMode !== 'SELECTED') return 0
  const selectedRegions = new Set(form.requisitionRegionCodes)
  const selectedStores = new Set(form.requisitionStoreIds)
  return activeStores.value.filter((store) => (
    selectedStores.has(store.id)
    || Boolean(store.regionCode && selectedRegions.has(store.regionCode.toUpperCase()))
  )).length
})
const scopeValidationMessage = computed(() => {
  if (!props.scopeContextAvailable) return '未能读取门店叫货范围，请关闭后重试。'
  if (!form.requisitionScopeMode) return '请选择全部门店或指定区域、指定门店。'
  if (
    form.requisitionScopeMode === 'SELECTED'
    && !form.requisitionRegionCodes.length
    && !form.requisitionStoreIds.length
  ) return '指定范围时，请至少选择一个区域或门店。'
  if (form.startsAt && form.endsAt && form.startsAt >= form.endsAt) return '活动结束时间必须晚于开始时间。'
  return ''
})
const validationIssues = computed<MaterialValidationIssue[]>(() => {
  const issues: MaterialValidationIssue[] = []
  if (!form.name.trim()) issues.push({ target: 'name', message: '请输入物品名称。' })
  if (!form.categoryId) issues.push({ target: 'category', message: '请选择可用的物料类别。' })
  if (!form.code.trim()) issues.push({ target: 'code', message: '请输入物料编号。' })
  appendNumberIssue(issues, 'unitPrice', form.unitPrice, '采购单价')
  appendNumberIssue(issues, 'shelfLifeDays', form.shelfLifeDays, '保质期', true)
  appendNumberIssue(issues, 'minStockQuantity', form.minStockQuantity, '最低安全库存')
  appendNumberIssue(issues, 'expiryAlertDays', form.expiryAlertDays, '预警天数', true)
  appendNumberIssue(issues, 'sortOrder', form.sortOrder, '排序', true)
  if (scopeValidationMessage.value) {
    issues.push({
      target: form.startsAt && form.endsAt && form.startsAt >= form.endsAt ? 'time' : 'scope',
      message: scopeValidationMessage.value,
    })
  }
  return issues
})
const canSubmit = computed(() => validationIssues.value.length === 0)
const visibleValidationIssues = computed(() => validationAttempted.value ? validationIssues.value : [])

function appendNumberIssue(
  issues: MaterialValidationIssue[],
  target: MaterialValidationTarget,
  rawValue: NumericInputValue,
  label: string,
  integer = false,
) {
  if (!String(rawValue).trim()) return
  const value = Number(rawValue)
  if (!Number.isFinite(value)) {
    issues.push({ target, message: `${label}必须是有效数字。` })
  } else if (value < 0) {
    issues.push({ target, message: `${label}不能小于 0。` })
  } else if (integer && !Number.isInteger(value)) {
    issues.push({ target, message: `${label}必须填写整数。` })
  }
}

watch(
  () => props.item,
  (item) => {
    Object.assign(form, emptyForm(item))
    imageError.value = ''
    openingSnapshot.value = snapshotForm()
    unsavedDialogOpen.value = false
    allStoresConfirmationOpen.value = false
    pendingPayload.value = null
    localSubmitError.value = null
    validationAttempted.value = false
  },
  { immediate: true },
)

onMounted(() => {
  previouslyFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  document.addEventListener('keydown', handleDialogKeydown)
  void nextTick(() => nameInputRef.value?.focus())
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleDialogKeydown)
  document.body.style.overflow = previousBodyOverflow
  previouslyFocusedElement?.focus()
})

function snapshotForm() {
  return JSON.stringify(form)
}

function requestClose() {
  if (props.saving) return
  if (dirty.value) {
    unsavedDialogOpen.value = true
    return
  }
  emit('close')
}

function discardChanges() {
  if (props.saving) return
  unsavedDialogOpen.value = false
  emit('close')
}

function handleDialogKeydown(event: KeyboardEvent) {
  if (unsavedDialogOpen.value || allStoresConfirmationOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab' || !editorRef.value) return
  const focusableElements = Array.from(editorRef.value.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])',
  )).filter((element) => element.getClientRects().length > 0)
  if (!focusableElements.length) return
  const first = focusableElements[0]
  const last = focusableElements[focusableElements.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  } else if (!editorRef.value.contains(document.activeElement)) {
    event.preventDefault()
    first?.focus()
  }
}

function emptyForm(item: WarehouseItem | null = null): MaterialForm {
  const policy = item?.requisitionPolicy
  const configured = policy?.configured === true
  return {
    id: item?.id,
    code: item?.code || '',
    name: item?.name || '',
    categoryId: item?.categoryId || null,
    imageUrl: item?.imageUrl || '',
    purchaseUnit: item?.purchaseUnit || item?.unit || '',
    stockUnit: item?.stockUnit || item?.unit || '',
    ingredientUnit: item?.ingredientUnit || '',
    unitConversionText: item?.unitConversionText || '',
    spec: item?.spec || '',
    warehouseLocation: item?.warehouseLocation || '',
    unitPrice: numberText(item?.unitPrice),
    shelfLifeDays: numberText(item?.shelfLifeDays),
    minStockQuantity: numberText(item?.minStockQuantity),
    expiryAlertDays: numberText(item?.expiryAlertDays ?? 3),
    itemDescription: item?.itemDescription || '',
    sortOrder: numberText(item?.sortOrder ?? 593),
    itemAttributes: item?.itemAttributes || '',
    active: item?.active !== false,
    departments: (item?.departments || []).map((department) => ({ ...department })),
    requisitionScopeMode: configured ? policy.scopeMode : '',
    requisitionRegionCodes: configured ? [...(policy.regionCodes || [])] : [],
    requisitionStoreIds: configured ? [...(policy.storeIds || [])] : [],
    campaignName: configured ? policy.campaignName || '' : '',
    startsAt: configured ? dateTimeInputValue(policy.startsAt) : '',
    endsAt: configured ? dateTimeInputValue(policy.endsAt) : '',
  }
}

function flattenCategories(categories: WarehouseItemCategory[], depth = 0): Array<WarehouseItemCategory & { displayName: string }> {
  return categories.flatMap((category) => [
    { ...category, displayName: `${'　'.repeat(depth)}${category.name}` },
    ...flattenCategories(category.children || [], depth + 1),
  ])
}

function numberText(value: number | undefined) {
  return value == null ? '' : String(value)
}

function toNumber(value: NumericInputValue, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function toOptionalNumber(value: NumericInputValue) {
  return String(value).trim() === '' ? null : toNumber(value)
}

function pickImage() {
  fileInput.value?.click()
}

function readImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    imageError.value = '仅支持 JPG、PNG 或 WEBP 图片。'
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    imageError.value = '图片不能超过 2MB。'
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    form.imageUrl = typeof reader.result === 'string' ? reader.result : ''
    imageError.value = ''
  }
  reader.onerror = () => {
    imageError.value = '图片读取失败，请重新选择。'
  }
  reader.readAsDataURL(file)
}

function clearImage() {
  form.imageUrl = ''
  imageError.value = ''
}

function addDepartment() {
  form.departments.push({ departmentName: '', departmentCode: '', departmentGroup: '', purchaseMethod: '', supplierName: '' })
}

function removeDepartment(index: number) {
  form.departments.splice(index, 1)
}

function dateTimeInputValue(value: string | undefined) {
  return value ? value.slice(0, 16) : ''
}

function regionLabel(code: string) {
  const normalized = code.trim().toUpperCase()
  if (normalized === 'JINGZHOU') return '荆州区域'
  if (normalized === 'SHANDONG') return '山东区域'
  return normalized
}

function buildPayload(): WarehouseItemPayload | null {
  if (!canSubmit.value || !form.requisitionScopeMode) return null
  return {
    id: form.id,
    code: form.code.trim(),
    name: form.name.trim(),
    categoryId: form.categoryId,
    imageUrl: form.imageUrl || undefined,
    unit: form.stockUnit.trim() || undefined,
    purchaseUnit: form.purchaseUnit.trim() || undefined,
    stockUnit: form.stockUnit.trim() || undefined,
    ingredientUnit: form.ingredientUnit.trim() || undefined,
    unitConversionText: form.unitConversionText.trim() || undefined,
    spec: form.spec.trim() || undefined,
    warehouseLocation: form.warehouseLocation.trim() || undefined,
    unitPrice: toNumber(form.unitPrice),
    shelfLifeDays: toOptionalNumber(form.shelfLifeDays),
    minStockQuantity: toNumber(form.minStockQuantity),
    alertEnabled: true,
    expiryAlertDays: toOptionalNumber(form.expiryAlertDays),
    itemDescription: form.itemDescription.trim() || undefined,
    sortOrder: toNumber(form.sortOrder, 593),
    itemAttributes: form.itemAttributes.trim() || undefined,
    active: form.active,
    departments: form.departments
      .filter((department) => department.departmentName.trim())
      .map((department) => ({
        departmentName: department.departmentName.trim(),
        departmentCode: department.departmentCode?.trim() || undefined,
        departmentGroup: department.departmentGroup?.trim() || undefined,
        purchaseMethod: department.purchaseMethod?.trim() || undefined,
        supplierName: department.supplierName?.trim() || undefined,
      })),
    requisitionPolicy: {
      scopeMode: form.requisitionScopeMode,
      regionCodes: form.requisitionScopeMode === 'SELECTED' ? [...form.requisitionRegionCodes] : [],
      storeIds: form.requisitionScopeMode === 'SELECTED' ? [...form.requisitionStoreIds] : [],
      campaignName: form.campaignName.trim() || undefined,
      startsAt: form.startsAt || undefined,
      endsAt: form.endsAt || undefined,
    },
  }
}

function validationMessageFor(target: MaterialValidationTarget) {
  return visibleValidationIssues.value.find((issue) => issue.target === target)?.message || ''
}

async function focusFirstValidationError() {
  await nextTick()
  const target = validationIssues.value[0]?.target
  const element = target === 'name'
    ? nameInputRef.value
    : target === 'category'
      ? categoryInputRef.value
      : target === 'code'
        ? codeInputRef.value
        : target === 'unitPrice'
          ? unitPriceInputRef.value
          : target === 'shelfLifeDays'
            ? shelfLifeDaysInputRef.value
            : target === 'minStockQuantity'
              ? minStockQuantityInputRef.value
              : target === 'expiryAlertDays'
                ? expiryAlertDaysInputRef.value
                : target === 'sortOrder'
                  ? sortOrderInputRef.value
                  : target === 'time'
                    ? startsAtInputRef.value
                    : scopePanelRef.value
  element?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  element?.focus()
}

function submit() {
  if (props.saving) return
  clearSubmitError()
  validationAttempted.value = true
  const payload = buildPayload()
  if (!payload) {
    void focusFirstValidationError()
    return
  }
  if (payload.requisitionPolicy.scopeMode === 'ALL') {
    pendingPayload.value = payload
    allStoresConfirmationOpen.value = true
    return
  }
  emit('save', payload)
}

function handleFormKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.isComposing) return
  const target = event.target
  if (!(target instanceof HTMLInputElement)) return
  if (['checkbox', 'radio', 'file', 'button', 'submit'].includes(target.type)) return
  event.preventDefault()
  submit()
}

function cancelAllStoresConfirmation() {
  if (props.saving) return
  allStoresConfirmationOpen.value = false
  pendingPayload.value = null
}

function confirmAllStores() {
  if (!pendingPayload.value || props.saving) return
  const payload = pendingPayload.value
  allStoresConfirmationOpen.value = false
  pendingPayload.value = null
  emit('save', payload)
}
</script>

<template>
  <Teleport to="body">
    <div
      class="material-overlay"
      @click.self="requestClose"
    >
      <section ref="editorRef" class="material-editor" role="dialog" aria-modal="true" aria-labelledby="material-editor-title">
      <header class="material-editor-head">
        <div class="material-editor-heading">
          <h2 id="material-editor-title">{{ form.id ? '编辑物料档案' : '新增物料档案' }}</h2>
          <p>维护物料基础资料、库存规则和适用部门</p>
        </div>
        <UiButton variant="ghost" icon-only type="button" title="关闭" aria-label="关闭物料编辑" :disabled="saving" @click="requestClose">
          <template #icon><X :size="20" /></template>
        </UiButton>
      </header>

      <form class="material-editor-form" novalidate @submit.prevent="submit" @keydown="handleFormKeydown">
        <div
          v-if="localSubmitError"
          ref="submitErrorRef"
          class="material-submit-error"
          role="alert"
          aria-live="assertive"
          :tabindex="-1"
        >
          <div class="material-submit-error-icon">
            <AlertTriangle :size="18" aria-hidden="true" />
          </div>
          <div class="material-submit-error-copy">
            <div class="material-submit-error-title">物料未保存</div>
            <p class="material-submit-error-body">{{ localSubmitError.message }}</p>
          </div>
        </div>
        <div class="material-editor-body">
          <div
            v-if="visibleValidationIssues.length"
            class="material-validation-summary"
            role="alert"
            aria-live="assertive"
          >
            <div class="material-validation-summary-title">
              <AlertTriangle :size="18" aria-hidden="true" />
              请完成以下必填项并修正错误
            </div>
            <ul>
              <li v-for="issue in visibleValidationIssues" :key="issue.target">{{ issue.message }}</li>
            </ul>
          </div>
          <div class="editor-section-title">
            <span>基本信息</span>
            <small>带名称、类别和编号的项目为必填项</small>
          </div>
          <div class="basic-info-grid">
            <div class="image-column" data-error-target="image" tabindex="-1">
              <button class="image-preview" type="button" :aria-label="form.imageUrl ? '更换物料图片' : '上传物料图片'" @click="pickImage">
                <img v-if="form.imageUrl" :src="form.imageUrl" alt="物料图片预览" />
                <span v-else>
                  <ImagePlus :size="24" />
                  上传物料图片
                </span>
              </button>
              <input ref="fileInput" class="hidden-file" type="file" accept="image/jpeg,image/png,image/webp" @change="readImage" />
              <div class="image-actions">
                <button class="mini-button" type="button" @click="pickImage">
                  <ImagePlus :size="15" />
                  {{ form.imageUrl ? '更换图片' : '选择图片' }}
                </button>
                <button v-if="form.imageUrl" class="mini-button" type="button" @click="clearImage">移除</button>
              </div>
              <small v-if="imageError" class="field-error">{{ imageError }}</small>
              <small v-else>JPG、PNG、WEBP，最大 2MB</small>
              <fieldset class="status-radio">
                <legend>使用状态</legend>
                <label><input v-model="form.active" type="radio" :value="true" />启用</label>
                <label><input v-model="form.active" type="radio" :value="false" />停用</label>
              </fieldset>
            </div>

            <div class="material-form-grid">
              <label>
                物品名称
                <input
                  ref="nameInputRef"
                  v-model="form.name"
                  required
                  maxlength="160"
                  placeholder="请输入物品名称"
                  data-error-target="name"
                  :aria-invalid="Boolean(validationMessageFor('name')) || undefined"
                  :aria-describedby="validationMessageFor('name') ? 'material-error-name' : undefined"
                />
                <span v-if="validationMessageFor('name')" id="material-error-name" class="field-target-error" role="alert">{{ validationMessageFor('name') }}</span>
              </label>
              <label>
                类别
                <select
                  ref="categoryInputRef"
                  v-model.number="form.categoryId"
                  required
                  data-error-target="category"
                  :aria-invalid="Boolean(validationMessageFor('category') || localSubmitError?.target === 'category') || undefined"
                  :aria-describedby="validationMessageFor('category') ? 'material-error-category' : localSubmitError?.target === 'category' ? 'submit-error-category' : undefined"
                >
                  <option :value="null" disabled>请选择分类</option>
                  <option v-for="category in categoryOptions" :key="category.id" :value="category.id" :disabled="!category.enabled">{{ category.displayName }}</option>
                </select>
                <span v-if="validationMessageFor('category')" id="material-error-category" class="field-target-error" role="alert">{{ validationMessageFor('category') }}</span>
                <span v-if="localSubmitError?.target === 'category'" id="submit-error-category" class="field-target-error" role="alert">{{ localSubmitError.message }}</span>
              </label>
              <label>
                编号
                <input
                  ref="codeInputRef"
                  v-model="form.code"
                  required
                  maxlength="80"
                  placeholder="例如 CUP-700"
                  data-error-target="code"
                  :aria-invalid="Boolean(validationMessageFor('code')) || undefined"
                  :aria-describedby="validationMessageFor('code') ? 'material-error-code' : undefined"
                />
                <span v-if="validationMessageFor('code')" id="material-error-code" class="field-target-error" role="alert">{{ validationMessageFor('code') }}</span>
              </label>
              <label>
                采购单位
                <input v-model="form.purchaseUnit" maxlength="40" placeholder="例如 箱" />
              </label>
              <label>
                库存单位
                <input v-model="form.stockUnit" maxlength="40" placeholder="例如 件" />
              </label>
              <label>
                配料单位
                <input v-model="form.ingredientUnit" maxlength="40" placeholder="例如 个 / 克" />
              </label>
              <label class="span-two">
                单位换算
                <input v-model="form.unitConversionText" maxlength="160" placeholder="例如 1箱=12件，1件=1000个" />
              </label>
              <label>
                采购单价
                <input
                  ref="unitPriceInputRef"
                  v-model="form.unitPrice"
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder="0.00"
                  :aria-invalid="Boolean(validationMessageFor('unitPrice')) || undefined"
                  :aria-describedby="validationMessageFor('unitPrice') ? 'material-error-unit-price' : undefined"
                />
                <span v-if="validationMessageFor('unitPrice')" id="material-error-unit-price" class="field-target-error" role="alert">{{ validationMessageFor('unitPrice') }}</span>
              </label>
              <label>
                规格
                <input v-model="form.spec" maxlength="160" placeholder="例如 1000个 / 件" />
              </label>
              <label>
                库位
                <input v-model="form.warehouseLocation" maxlength="120" placeholder="例如 A-01" />
              </label>
              <label>
                保质期（天）
                <input
                  ref="shelfLifeDaysInputRef"
                  v-model="form.shelfLifeDays"
                  type="number"
                  min="0"
                  step="1"
                  placeholder="例如 30"
                  :aria-invalid="Boolean(validationMessageFor('shelfLifeDays')) || undefined"
                  :aria-describedby="validationMessageFor('shelfLifeDays') ? 'material-error-shelf-life' : undefined"
                />
                <span v-if="validationMessageFor('shelfLifeDays')" id="material-error-shelf-life" class="field-target-error" role="alert">{{ validationMessageFor('shelfLifeDays') }}</span>
              </label>
              <label>
                预警天数
                <input
                  ref="expiryAlertDaysInputRef"
                  v-model="form.expiryAlertDays"
                  type="number"
                  min="0"
                  step="1"
                  placeholder="例如 3"
                  :aria-invalid="Boolean(validationMessageFor('expiryAlertDays')) || undefined"
                  :aria-describedby="validationMessageFor('expiryAlertDays') ? 'material-error-expiry-alert' : undefined"
                />
                <span v-if="validationMessageFor('expiryAlertDays')" id="material-error-expiry-alert" class="field-target-error" role="alert">{{ validationMessageFor('expiryAlertDays') }}</span>
              </label>
              <label>
                最低安全库存
                <input
                  ref="minStockQuantityInputRef"
                  v-model="form.minStockQuantity"
                  type="number"
                  min="0"
                  step="0.01"
                  placeholder="例如 20"
                  :aria-invalid="Boolean(validationMessageFor('minStockQuantity')) || undefined"
                  :aria-describedby="validationMessageFor('minStockQuantity') ? 'material-error-min-stock' : undefined"
                />
                <span v-if="validationMessageFor('minStockQuantity')" id="material-error-min-stock" class="field-target-error" role="alert">{{ validationMessageFor('minStockQuantity') }}</span>
              </label>
              <label>
                排序
                <input
                  ref="sortOrderInputRef"
                  v-model="form.sortOrder"
                  type="number"
                  min="0"
                  step="1"
                  :aria-invalid="Boolean(validationMessageFor('sortOrder')) || undefined"
                  :aria-describedby="validationMessageFor('sortOrder') ? 'material-error-sort-order' : undefined"
                />
                <span v-if="validationMessageFor('sortOrder')" id="material-error-sort-order" class="field-target-error" role="alert">{{ validationMessageFor('sortOrder') }}</span>
              </label>
              <label class="span-two">
                属性
                <input v-model="form.itemAttributes" maxlength="255" placeholder="例如 冷藏、易碎、食品原料" />
              </label>
              <label class="span-all">
                物品说明
                <textarea v-model="form.itemDescription" rows="2" maxlength="3000" placeholder="填写储存、使用或采购说明" />
              </label>
            </div>
          </div>

          <div class="editor-section-title requisition-scope-title">
            <span>商品可叫货范围</span>
            <small>必选；指定区域和指定门店按并集生效</small>
          </div>
          <section
            ref="scopePanelRef"
            class="requisition-scope-panel"
            :class="{ 'requisition-scope-panel--invalid': Boolean(validationMessageFor('scope') || validationMessageFor('time')) }"
            aria-labelledby="requisition-scope-heading"
            :aria-invalid="Boolean(validationMessageFor('scope') || validationMessageFor('time')) || undefined"
            tabindex="-1"
            data-error-target="scope"
          >
            <h3 id="requisition-scope-heading" class="visually-hidden">选择商品可叫货范围</h3>
            <p
              v-if="item && item.requisitionPolicy?.configured !== true"
              class="legacy-scope-note"
            >
              此商品为历史商品，当前仍兼容为全部门店可叫货；本次编辑必须重新明确范围后才能保存。
            </p>

            <fieldset class="scope-mode-options">
              <legend>可叫货范围（必选）</legend>
              <label class="scope-mode-card" :class="{ selected: form.requisitionScopeMode === 'ALL' }">
                <input v-model="form.requisitionScopeMode" type="radio" value="ALL" />
                <span>
                  <strong>全部门店</strong>
                  <small>当前覆盖 {{ activeStores.length }} 家营业门店，后续新增门店也自动覆盖</small>
                </span>
              </label>
              <label class="scope-mode-card" :class="{ selected: form.requisitionScopeMode === 'SELECTED' }">
                <input v-model="form.requisitionScopeMode" type="radio" value="SELECTED" />
                <span>
                  <strong>指定区域 / 指定门店</strong>
                  <small>可同时选择，满足任意一项的门店即可叫货</small>
                </span>
              </label>
            </fieldset>

            <div v-if="form.requisitionScopeMode === 'SELECTED'" class="scope-target-grid">
              <fieldset class="scope-target-list" data-error-target="region" tabindex="-1">
                <legend>指定区域（可多选）</legend>
                <label v-for="region in regionOptions" :key="region.code">
                  <input v-model="form.requisitionRegionCodes" type="checkbox" :value="region.code" />
                  <span>{{ region.name }}</span>
                  <small>{{ region.code }}</small>
                </label>
                <p v-if="!regionOptions.length" class="scope-empty">暂无可选区域，请先配置仓库区域。</p>
              </fieldset>

              <fieldset class="scope-target-list store-target-list" data-error-target="store" tabindex="-1">
                <legend>指定门店（可多选）</legend>
                <SearchableMultiSelect
                  :model-value="form.requisitionStoreIds"
                  :options="activeStoreOptions"
                  selected-noun="家门店"
                  search-placeholder="搜索门店名称、编号或区域"
                  aria-label="搜索并选择可叫货门店"
                  empty-message="暂无营业中的门店"
                  @update:model-value="form.requisitionStoreIds = $event.map(String)"
                />
              </fieldset>
            </div>

            <div v-if="form.requisitionScopeMode" class="scope-coverage" aria-live="polite">
              当前将覆盖 <strong>{{ affectedStoreCount }}</strong> 家营业门店
              <span v-if="form.requisitionScopeMode === 'ALL'">（保存时需要再次确认）</span>
            </div>

            <div class="campaign-grid" data-error-target="time" tabindex="-1">
              <label>
                活动名称（选填）
                <input v-model="form.campaignName" maxlength="160" placeholder="例如 山东新店开业活动" />
              </label>
              <label>
                生效开始时间（选填）
                <input ref="startsAtInputRef" v-model="form.startsAt" type="datetime-local" />
              </label>
              <label>
                生效结束时间（选填）
                <input v-model="form.endsAt" type="datetime-local" />
              </label>
            </div>
            <p v-if="validationAttempted && scopeValidationMessage" class="scope-error" role="alert">{{ scopeValidationMessage }}</p>
          </section>

          <div class="editor-section-title departments-title">
            <span>适用部门</span>
            <button class="mini-button primary" type="button" @click="addDepartment">
              <Plus :size="15" />
              添加部门
            </button>
          </div>
          <div class="department-table-wrap">
            <table class="department-table">
              <thead>
                <tr>
                  <th>部门名称</th>
                  <th>部门编号</th>
                  <th>部门分组</th>
                  <th>采购方式</th>
                  <th>供应商名称</th>
                  <th><span class="visually-hidden">操作</span></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(department, index) in form.departments" :key="`${department.id || 'new'}-${index}`">
                  <td><input v-model="department.departmentName" maxlength="120" placeholder="例如 采购部" /></td>
                  <td><input v-model="department.departmentCode" maxlength="80" placeholder="编号" /></td>
                  <td><input v-model="department.departmentGroup" maxlength="120" placeholder="分组" /></td>
                  <td><input v-model="department.purchaseMethod" maxlength="120" placeholder="例如 集采" /></td>
                  <td><input v-model="department.supplierName" maxlength="160" placeholder="供应商" /></td>
                  <td>
                    <button class="icon-button danger" type="button" title="删除部门" aria-label="删除该部门" @click="removeDepartment(index)">
                      <Trash2 :size="16" />
                    </button>
                  </td>
                </tr>
                <tr v-if="!form.departments.length">
                  <td colspan="6" class="empty-cell">暂无适用部门，可按需添加。</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <ModalFooter>
          <UiButton variant="secondary" type="button" :disabled="saving" @click="requestClose">取消</UiButton>
          <UiButton
            variant="primary"
            type="button"
            aria-label="保存物料"
            :disabled="saving"
            :loading="saving"
            @click="submit"
          >{{ form.id ? '保存修改' : '完成新增' }}</UiButton>
        </ModalFooter>
      </form>
      </section>
    </div>
  </Teleport>

  <UnsavedChangesDialog
    :open="unsavedDialogOpen"
    title="物料修改尚未保存"
    message="关闭后，本次物料资料、图片和适用部门调整将不会保留。"
    @keep-editing="unsavedDialogOpen = false"
    @discard="discardChanges"
  />

  <ActionConfirmDialog
    v-model="allStoresConfirmationNote"
    :open="allStoresConfirmationOpen"
    title="确认允许全部门店叫货？"
    :message="`该商品将覆盖当前 ${activeStores.length} 家营业门店，未来新增门店也会自动获得叫货权限。确认后保存本次配置。`"
    confirm-label="确认全部门店并保存"
    :busy="saving"
    @cancel="cancelAllStoresConfirmation"
    @confirm="confirmAllStores"
  />
</template>

<style scoped>
.material-overlay {
  position: fixed;
  z-index: var(--ds-z-modal, 1400);
  inset: 0;
  display: grid;
  place-items: center;
  overflow: hidden;
  padding: 24px;
  background: rgba(20, 35, 39, 0.48);
}

.material-editor {
  display: grid;
  width: min(1180px, 100%);
  max-height: calc(100dvh - 48px);
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 14px 36px rgba(15, 31, 35, 0.24);
}

.material-editor-head,
.editor-section-title,
.image-actions {
  display: flex;
  align-items: center;
}

.material-editor-head {
  min-height: 70px;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 22px;
  border-bottom: 1px solid #dfecef;
  background: #fff;
}

.material-editor-head h2 {
  margin: 0;
  color: #182424;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.35;
}

.material-editor-heading p {
  margin: 3px 0 0;
  color: #657876;
  font-size: 13px;
}

.material-editor-form {
  display: grid;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr) auto;
  overflow: hidden;
}

.material-submit-error {
  display: flex;
  grid-row: 1;
  gap: 12px;
  align-items: flex-start;
  margin: 0;
  padding: 13px 22px;
  border-bottom: 2px solid #f0c4c4;
  background: #fff5f5;
  outline: none;
}

.material-submit-error:focus-visible {
  box-shadow: inset 0 0 0 2px #e06060;
}

.material-submit-error-icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  place-items: center;
  margin-top: 1px;
  border-radius: 50%;
  background: #fce4e4;
  color: #c53030;
}

.material-submit-error-copy {
  min-width: 0;
}

.material-submit-error-title {
  color: #9b2c2c;
  font-size: 14px;
  font-weight: 700;
  line-height: 1.35;
}

.material-submit-error-body {
  margin: 3px 0 0;
  color: #5c3d3d;
  font-size: 13px;
  line-height: 1.55;
}

.field-target-error {
  display: block;
  margin-top: 4px;
  color: #c53030;
  font-size: 12px;
  font-weight: 600;
}

.material-editor-body {
  grid-row: 2;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 20px 22px 24px;
  scroll-padding-block: 18px;
}

.material-editor-form :deep(.modal-footer) {
  grid-row: 3;
}

.material-validation-summary {
  margin: 0 0 18px;
  padding: 12px 14px;
  border: 1px solid #efb5b5;
  border-radius: 7px;
  background: #fff5f5;
  color: #7f2929;
}

.material-validation-summary-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 700;
}

.material-validation-summary ul {
  display: grid;
  gap: 3px;
  margin: 8px 0 0;
  padding-left: 22px;
  font-size: 13px;
  line-height: 1.5;
}

.editor-section-title {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  padding-bottom: 10px;
  border-bottom: 1px solid #dfe9e8;
  color: #214846;
  font-size: 16px;
  font-weight: 700;
}

.editor-section-title small {
  color: #6f817f;
  font-size: 13px;
  font-weight: 400;
}

.basic-info-grid {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  gap: 22px;
  align-items: start;
}

.image-column {
  display: grid;
  align-content: start;
  gap: 10px;
}

.image-preview {
  display: grid;
  width: 100%;
  aspect-ratio: 1 / 1;
  place-items: center;
  overflow: hidden;
  border: 1px dashed #aabfbd;
  border-radius: 6px;
  background: #f7fbfa;
  color: #56716f;
  font-size: 13px;
  text-align: center;
}

.image-preview:hover {
  border-color: #2f7772;
  background: #eef8f7;
}

.image-preview span {
  display: grid;
  place-items: center;
  gap: 8px;
}

.image-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #fff;
}

.hidden-file {
  display: none;
}

.image-actions,
.status-radio label {
  gap: 8px;
}

.image-column small {
  color: #6f817f;
  font-size: 13px;
  line-height: 1.45;
}

.image-column .field-error {
  color: var(--bad);
}

.status-radio {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  margin: 2px 0 0;
  padding: 10px 0 0;
  border: 0;
  border-top: 1px solid #e2ebea;
}

.status-radio legend {
  width: 100%;
  margin-bottom: 2px;
  padding: 0;
  color: #526765;
  font-size: 13px;
  font-weight: 600;
}

.status-radio label {
  display: inline-flex;
  align-items: center;
  color: #334d4b;
  font-size: 14px;
}

.material-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px 16px;
}

.material-form-grid label {
  display: grid;
  gap: 6px;
  min-width: 0;
  color: #526765;
  font-size: 14px;
  font-weight: 600;
}

.material-form-grid label.span-two {
  grid-column: span 2;
}

.material-form-grid label.span-all {
  grid-column: 1 / -1;
}

input,
select,
textarea {
  width: 100%;
  min-height: 40px;
  border: 1px solid #c8d8d6;
  border-radius: 6px;
  padding: 8px 10px;
  outline: 0;
  background: #fff;
  color: #182424;
  font-weight: 400;
}

input::placeholder,
textarea::placeholder {
  color: #758684;
}

textarea {
  resize: vertical;
}

input:focus,
select:focus,
textarea:focus {
  border-color: #2f7772;
  box-shadow: 0 0 0 3px rgba(118, 189, 184, 0.18);
}

input[aria-invalid="true"],
select[aria-invalid="true"],
textarea[aria-invalid="true"] {
  border-color: #d65353;
  background: #fffafa;
}

input[aria-invalid="true"]:focus,
select[aria-invalid="true"]:focus,
textarea[aria-invalid="true"]:focus {
  box-shadow: 0 0 0 3px rgba(214, 83, 83, 0.14);
}

.requisition-scope-title {
  margin-top: 26px;
}

.requisition-scope-panel {
  display: grid;
  gap: 16px;
  padding: 18px;
  border: 1px solid #dce9e7;
  border-radius: 8px;
  background: #f9fcfb;
}

.requisition-scope-panel--invalid {
  border-color: #d65353;
  background: #fffafa;
}

.requisition-scope-panel:focus-visible {
  outline: 3px solid rgba(214, 83, 83, 0.18);
  outline-offset: 2px;
}

.legacy-scope-note,
.scope-error,
.scope-empty {
  margin: 0;
}

.legacy-scope-note {
  padding: 10px 12px;
  border-left: 3px solid #c68726;
  background: #fff8e8;
  color: #765111;
  font-size: 13px;
  line-height: 1.55;
}

.scope-mode-options,
.scope-target-list {
  margin: 0;
  padding: 0;
  border: 0;
}

.store-target-list :deep(.searchable-multi-select) {
  border: 0;
  padding: 0;
}

.scope-mode-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.scope-mode-options > legend,
.scope-target-list > legend {
  width: 100%;
  margin-bottom: 8px;
  color: #526765;
  font-size: 14px;
  font-weight: 700;
}

.scope-mode-card {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  min-width: 0;
  padding: 13px 14px;
  border: 1px solid #c8d8d6;
  border-radius: 7px;
  background: #fff;
  color: #283f3d;
  cursor: pointer;
}

.scope-mode-card.selected {
  border-color: #2f7772;
  box-shadow: 0 0 0 2px rgba(47, 119, 114, .12);
}

.scope-mode-card input,
.scope-target-list input {
  width: auto;
  min-height: 0;
  margin-top: 3px;
}

.scope-mode-card > span {
  display: grid;
  gap: 3px;
}

.scope-mode-card strong {
  font-size: 14px;
}

.scope-mode-card small,
.scope-target-list small {
  color: #6b7e7c;
  font-size: 12px;
  font-weight: 400;
  line-height: 1.45;
}

.scope-target-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.scope-target-list {
  max-height: 240px;
  overflow: auto;
  padding: 12px;
  border: 1px solid #dce9e7;
  border-radius: 7px;
  background: #fff;
}

.scope-target-list > label {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 9px;
  align-items: start;
  padding: 8px 4px;
  border-bottom: 1px solid #edf3f2;
  color: #334d4b;
  font-size: 13px;
  cursor: pointer;
}

.scope-target-list > label:last-of-type {
  border-bottom: 0;
}

.scope-empty {
  padding: 8px 4px;
  color: #718280;
  font-size: 13px;
}

.scope-coverage {
  padding: 10px 12px;
  border-radius: 6px;
  background: #eaf6f4;
  color: #285d59;
  font-size: 13px;
}

.scope-coverage strong {
  font-size: 16px;
}

.campaign-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.campaign-grid label {
  display: grid;
  gap: 6px;
  min-width: 0;
  color: #526765;
  font-size: 13px;
  font-weight: 600;
}

.scope-error {
  color: var(--bad);
  font-size: 13px;
  font-weight: 600;
}

.departments-title {
  margin-top: 26px;
  margin-bottom: 12px;
}

.department-table-wrap {
  overflow: auto;
  border: 1px solid #dfe9e8;
  border-radius: 6px;
}

.department-table {
  width: 100%;
  min-width: 920px;
  border-collapse: collapse;
}

.department-table th {
  padding: 10px 12px;
  background: #f3f8f7;
  color: #526765;
  font-size: 13px;
  font-weight: 600;
  text-align: left;
}

.department-table td {
  padding: 8px 10px;
  border-top: 1px solid #edf2f3;
}

.department-table input {
  min-width: 110px;
  min-height: 36px;
}

.department-table .icon-button {
  margin: auto;
}

.image-actions .mini-button,
.departments-title .mini-button {
  border-radius: 6px;
  font-weight: 600;
}

.icon-button {
  display: inline-grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #4d7880;
}

.icon-button:hover {
  background: #eaf7f8;
}

.icon-button.danger:hover {
  background: #fff0ef;
  color: var(--bad);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  clip-path: inset(50%);
}

@media (max-width: 1100px) {
  .material-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .material-overlay {
    padding: 0;
  }

  .material-editor {
    width: 100%;
    height: 100dvh;
    max-height: none;
    border-radius: 0;
  }

  .material-editor-head {
    padding-right: 16px;
    padding-left: 16px;
  }

  .material-editor-body {
    padding: 18px 16px 22px;
  }

  .material-editor-form :deep(.modal-footer) {
    padding-bottom: calc(12px + env(safe-area-inset-bottom));
  }

  .editor-section-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .basic-info-grid,
  .material-form-grid,
  .scope-mode-options,
  .scope-target-grid,
  .campaign-grid {
    grid-template-columns: 1fr;
  }

  .image-column {
    width: min(180px, 100%);
  }

  .material-form-grid label.span-two,
  .material-form-grid label.span-all {
    grid-column: auto;
  }
}
</style>
