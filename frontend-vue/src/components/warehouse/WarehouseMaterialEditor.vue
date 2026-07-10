<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ImagePlus, Plus, Trash2, X } from 'lucide-vue-next'
import type { WarehouseItem, WarehouseItemCategory, WarehouseItemDepartment, WarehouseItemPayload } from '../../api/warehouse'

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

const categoryOptions = computed(() => flattenCategories(props.categories))

watch(
  () => props.item,
  (item) => {
    Object.assign(form, emptyForm(item))
    imageError.value = ''
  },
  { immediate: true },
)

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
  <div class="material-overlay" role="dialog" aria-modal="true" aria-label="物料档案编辑">
    <section class="material-editor">
      <header class="material-editor-head">
        <div>
          <h2>{{ form.id ? '编辑物料档案' : '新增物料档案' }}</h2>
        </div>
        <button class="icon-button" type="button" title="关闭" :disabled="saving" @click="emit('close')">
          <X :size="20" />
        </button>
      </header>

      <form @submit.prevent="submit">
        <div class="editor-section-title">基本信息</div>
        <div class="basic-info-grid">
          <div class="image-column">
            <div class="image-preview">
              <img v-if="form.imageUrl" :src="form.imageUrl" alt="物料图片预览" />
              <span v-else>暂无图片</span>
            </div>
            <input ref="fileInput" class="hidden-file" type="file" accept="image/jpeg,image/png,image/webp" @change="readImage" />
            <div class="image-actions">
              <button class="mini-button" type="button" @click="pickImage">
                <ImagePlus :size="15" />
                上传图片
              </button>
              <button v-if="form.imageUrl" class="mini-button" type="button" @click="clearImage">移除</button>
            </div>
            <small v-if="imageError" class="field-error">{{ imageError }}</small>
            <small v-else>JPG、PNG、WEBP，最大 2MB</small>
            <div class="status-radio">
              <label><input v-model="form.active" type="radio" :value="true" />启用</label>
              <label><input v-model="form.active" type="radio" :value="false" />停用</label>
            </div>
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
            <label class="span-two">
              物品说明
              <textarea v-model="form.itemDescription" rows="2" maxlength="3000" placeholder="填写储存、使用或采购说明" />
            </label>
          </div>
        </div>

        <div class="editor-section-title departments-title">
          <span>适用部门</span>
          <button class="mini-button primary" type="button" @click="addDepartment">
            <Plus :size="15" />
            添加
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
                <th></th>
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
                  <button class="icon-button danger" type="button" title="删除部门" @click="removeDepartment(index)">
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

        <footer class="material-editor-footer">
          <button class="ghost-button" type="button" :disabled="saving" @click="emit('close')">取消</button>
          <button class="primary-button" type="submit" :disabled="saving || !form.code.trim() || !form.name.trim() || !form.categoryId">
            {{ saving ? '正在保存' : '保存物料' }}
          </button>
        </footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.material-overlay {
  position: fixed;
  z-index: 50;
  inset: 0;
  overflow: auto;
  padding: 28px;
  background: rgba(25, 38, 45, 0.36);
}

.material-editor {
  width: min(1280px, 100%);
  margin: 0 auto;
  overflow: hidden;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 18px 54px rgba(15, 23, 42, 0.2);
}

.material-editor-head,
.editor-section-title,
.image-actions,
.status-radio,
.material-editor-footer {
  display: flex;
  align-items: center;
}

.material-editor-head {
  min-height: 58px;
  justify-content: space-between;
  padding: 0 18px;
  border-bottom: 1px solid #dfecef;
}

.material-editor-head h2 {
  margin: 0;
  font-size: 19px;
}

form {
  padding: 16px 18px 18px;
}

.editor-section-title {
  min-height: 38px;
  justify-content: space-between;
  margin: 0 -18px 14px;
  padding: 0 18px;
  border-left: 4px solid #12afc1;
  background: #eefafd;
  color: #204b54;
  font-weight: 800;
}

.basic-info-grid {
  display: grid;
  grid-template-columns: 176px minmax(0, 1fr);
  gap: 18px;
}

.image-column {
  display: grid;
  align-content: start;
  gap: 8px;
}

.image-preview {
  display: grid;
  width: 152px;
  height: 132px;
  place-items: center;
  overflow: hidden;
  border: 1px dashed #b8c8cd;
  background: #fbfdfe;
  color: #93a4aa;
  font-size: 13px;
}

.image-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.hidden-file {
  display: none;
}

.image-actions,
.status-radio {
  gap: 8px;
}

.image-column small {
  color: var(--muted);
  font-size: 12px;
}

.image-column .field-error {
  color: var(--bad);
}

.status-radio label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #475569;
  font-size: 13px;
}

.material-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 18px;
}

.material-form-grid label {
  display: grid;
  gap: 6px;
  color: #475569;
  font-size: 13px;
}

.material-form-grid label.span-two {
  grid-column: span 2;
}

input,
select,
textarea {
  width: 100%;
  min-height: 34px;
  border: 1px solid #cdd9dd;
  border-radius: 4px;
  padding: 6px 9px;
  outline: 0;
  background: #fff;
  color: var(--ink);
}

textarea {
  resize: vertical;
}

input:focus,
select:focus,
textarea:focus {
  border-color: #28a9b8;
  box-shadow: 0 0 0 2px rgba(40, 169, 184, 0.12);
}

.departments-title {
  margin-top: 18px;
  margin-bottom: 0;
}

.department-table-wrap {
  overflow: auto;
  margin: 0 -18px;
  border-bottom: 1px solid #dfecef;
}

.department-table {
  width: 100%;
  min-width: 920px;
  border-collapse: collapse;
}

.department-table th {
  padding: 10px 12px;
  background: #f5fbfc;
  color: #475569;
  font-size: 13px;
  text-align: left;
}

.department-table td {
  padding: 8px 10px;
  border-top: 1px solid #edf2f3;
}

.department-table input {
  min-width: 110px;
}

.department-table .icon-button {
  margin: auto;
}

.material-editor-footer {
  justify-content: flex-end;
  gap: 10px;
  padding-top: 16px;
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
</style>
