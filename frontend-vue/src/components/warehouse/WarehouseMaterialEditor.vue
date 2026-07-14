<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ImagePlus, Plus, Trash2, X } from 'lucide-vue-next'
import type { WarehouseItem, WarehouseItemCategory, WarehouseItemDepartment, WarehouseItemPayload } from '../../api/warehouse'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'
import UnsavedChangesDialog from '../ui/UnsavedChangesDialog.vue'

const props = defineProps<{
  item: WarehouseItem | null
  categories: WarehouseItemCategory[]
  saving: boolean
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
  unitPrice: string
  shelfLifeDays: string
  minStockQuantity: string
  expiryAlertDays: string
  itemDescription: string
  sortOrder: string
  itemAttributes: string
  active: boolean
  departments: WarehouseItemDepartment[]
}

const fileInput = ref<HTMLInputElement | null>(null)
const imageError = ref('')
const form = reactive<MaterialForm>(emptyForm())
const openingSnapshot = ref('')
const unsavedDialogOpen = ref(false)

const categoryOptions = computed(() => flattenCategories(props.categories))
const dirty = computed(() => Boolean(openingSnapshot.value) && snapshotForm() !== openingSnapshot.value)

watch(
  () => props.item,
  (item) => {
    Object.assign(form, emptyForm(item))
    imageError.value = ''
    openingSnapshot.value = snapshotForm()
    unsavedDialogOpen.value = false
  },
  { immediate: true },
)

onMounted(() => document.addEventListener('keydown', handleEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', handleEscape))

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

function handleEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || unsavedDialogOpen.value) return
  event.preventDefault()
  requestClose()
}

function emptyForm(item: WarehouseItem | null = null): MaterialForm {
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

function toNumber(value: string, fallback = 0) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function toOptionalNumber(value: string) {
  return value.trim() === '' ? null : toNumber(value)
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

function submit() {
  if (!form.code.trim() || !form.name.trim() || !form.categoryId) return
  emit('save', {
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
  })
}
</script>

<template>
  <Teleport to="body">
    <div
      class="material-overlay"
      @click.self="requestClose"
    >
      <section class="material-editor" role="dialog" aria-modal="true" aria-labelledby="material-editor-title">
      <header class="material-editor-head">
        <div class="material-editor-heading">
          <h2 id="material-editor-title">{{ form.id ? '编辑物料档案' : '新增物料档案' }}</h2>
          <p>维护物料基础资料、库存规则和适用部门</p>
        </div>
        <UiButton variant="ghost" icon-only type="button" title="关闭" aria-label="关闭物料编辑" :disabled="saving" @click="requestClose">
          <template #icon><X :size="20" /></template>
        </UiButton>
      </header>

      <form class="material-editor-form" @submit.prevent="submit">
        <div class="material-editor-body">
          <div class="editor-section-title">
            <span>基本信息</span>
            <small>带名称、类别和编号的项目为必填项</small>
          </div>
          <div class="basic-info-grid">
            <div class="image-column">
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
                <input v-model="form.name" required maxlength="160" placeholder="请输入物品名称" />
              </label>
              <label>
                类别
                <select v-model.number="form.categoryId" required>
                  <option :value="null" disabled>请选择分类</option>
                  <option v-for="category in categoryOptions" :key="category.id" :value="category.id" :disabled="!category.enabled">{{ category.displayName }}</option>
                </select>
              </label>
              <label>
                编号
                <input v-model="form.code" required maxlength="80" placeholder="例如 CUP-700" />
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
                <input v-model="form.unitPrice" type="number" min="0" step="0.01" placeholder="0.00" />
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
                <input v-model="form.shelfLifeDays" type="number" min="0" step="1" placeholder="例如 30" />
              </label>
              <label>
                预警天数
                <input v-model="form.expiryAlertDays" type="number" min="0" step="1" placeholder="例如 3" />
              </label>
              <label>
                最低安全库存
                <input v-model="form.minStockQuantity" type="number" min="0" step="0.01" placeholder="例如 20" />
              </label>
              <label>
                排序
                <input v-model="form.sortOrder" type="number" min="0" step="1" />
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
            type="submit"
            :disabled="!form.code.trim() || !form.name.trim() || !form.categoryId"
            :loading="saving"
          >保存物料</UiButton>
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
  grid-template-rows: minmax(0, 1fr) auto;
}

.material-editor-body {
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 20px 22px 24px;
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

  .editor-section-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }

  .basic-info-grid,
  .material-form-grid {
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
