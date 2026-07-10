<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Download, Plus, Save, Send, Trash2 } from 'lucide-vue-next'
import {
  getInventoryCheck,
  getInventoryChecks,
  reviewInventoryCheck,
  saveInventoryCheck,
  submitInventoryCheck,
  type InventoryCheck,
  type InventoryCheckLine,
  type StoreInfo,
} from '../../api/operations'
import { useAuthStore } from '../../stores/auth'

defineProps<{
  stores: StoreInfo[]
}>()

const auth = useAuthStore()
let nextId = 4
const editingId = ref<number | undefined>()
const selectedStoreId = ref('')
const checkDate = ref(new Date().toISOString().slice(0, 10))
const note = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const checks = ref<InventoryCheck[]>([])

const lines = reactive<Array<InventoryCheckLine & { rowId: number }>>([
  { rowId: 1, itemName: '鲜奶', category: '奶制品', unit: '件', countedQuantity: 12, unitPrice: 68 },
  { rowId: 2, itemName: '700ml杯子', category: '包装', unit: '件', countedQuantity: 80, unitPrice: 0.28 },
  { rowId: 3, itemName: '桃子', category: '水果', unit: '件', countedQuantity: 6, unitPrice: 8 },
])

const canReview = computed(() => ['ADMIN', 'BOSS', 'OWNER', 'OPERATIONS', 'FINANCE'].includes(auth.role))
const totalAmount = computed(() => lines.reduce((sum, item) => sum + amount(item), 0))

function amount(item: InventoryCheckLine) {
  return Number(item.countedQuantity || 0) * Number(item.unitPrice || 0)
}

function money(value: number) {
  return Number(value || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' })
}

function addLine() {
  lines.push({ rowId: nextId++, itemName: '', category: '', unit: '件', countedQuantity: 0, unitPrice: 0 })
}

function removeLine(rowId: number) {
  const index = lines.findIndex((item) => item.rowId === rowId)
  if (index >= 0) lines.splice(index, 1)
}

function resetDraft() {
  editingId.value = undefined
  checkDate.value = new Date().toISOString().slice(0, 10)
  note.value = ''
  lines.splice(0, lines.length, { rowId: nextId++, itemName: '', category: '', unit: '件', countedQuantity: 0, unitPrice: 0 })
  message.value = '已切换为新的盘存单草稿'
}

async function loadChecks() {
  loading.value = true
  error.value = ''
  try {
    checks.value = await getInventoryChecks()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '盘存单加载失败'
  } finally {
    loading.value = false
  }
}

async function editCheck(id: number) {
  error.value = ''
  try {
    const check = await getInventoryCheck(id)
    editingId.value = check.id
    selectedStoreId.value = check.storeId
    checkDate.value = check.checkDate
    note.value = check.note || ''
    lines.splice(0, lines.length, ...check.lines.map((line) => ({
      ...line,
      rowId: nextId++,
      countedQuantity: Number(line.countedQuantity || 0),
      unitPrice: Number(line.unitPrice || 0),
    })))
    message.value = `已载入盘存单 ${check.checkNo}`
  } catch (err) {
    error.value = err instanceof Error ? err.message : '盘存单详情加载失败'
  }
}

async function saveDraft() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    if (auth.role !== 'STORE_MANAGER' && !selectedStoreId.value) {
      throw new Error('请选择盘存门店')
    }
    const saved = await saveInventoryCheck({
      id: editingId.value,
      storeId: selectedStoreId.value || undefined,
      checkDate: checkDate.value,
      note: note.value,
      lines: lines.map((line) => ({
        itemName: line.itemName,
        itemCode: line.itemCode,
        category: line.category,
        spec: line.spec,
        unit: line.unit,
        packageQuantity: Number(line.packageQuantity || 0),
        unitPrice: Number(line.unitPrice || 0),
        unitPriceEach: Number(line.unitPriceEach || 0),
        countedQuantity: Number(line.countedQuantity || 0),
        note: line.note,
      })),
    })
    editingId.value = saved.id
    message.value = `盘存单已保存：${saved.checkNo}`
    await loadChecks()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '盘存单保存失败'
  } finally {
    saving.value = false
  }
}

async function submitDraft() {
  await saveDraft()
  if (!editingId.value || error.value) return
  saving.value = true
  try {
    const submitted = await submitInventoryCheck(editingId.value)
    message.value = `盘存单已提交：${submitted.checkNo}`
    await loadChecks()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '盘存单提交失败'
  } finally {
    saving.value = false
  }
}

async function reviewCheck(id: number) {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const reviewed = await reviewInventoryCheck(id)
    message.value = `盘存单已复核：${reviewed.checkNo}`
    await loadChecks()
  } catch (err) {
    error.value = err instanceof Error ? err.message : '盘存单复核失败'
  } finally {
    saving.value = false
  }
}

function exportCsv() {
  const header = ['物品名称', '类别', '单位', '盘存数量', '单价', '金额']
  const body = lines.map((item) => [
    item.itemName,
    item.category || '',
    item.unit || '',
    item.countedQuantity || 0,
    item.unitPrice || 0,
    amount(item).toFixed(2),
  ])
  const csv = [header, ...body, ['合计', '', '', '', '', totalAmount.value.toFixed(2)]]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `店铺盘存测算-${checkDate.value}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  void loadChecks()
})
</script>

<template>
  <section class="content-card inventory-panel">
    <div class="table-heading">
      <div>
        <h3>店铺盘存</h3>
      </div>
      <div class="inventory-actions">
        <button class="ghost-button" type="button" @click="resetDraft">新盘存单</button>
        <button class="ghost-button" type="button" @click="addLine">
          <Plus :size="16" />
          添加物品
        </button>
        <button class="ghost-button" type="button" @click="exportCsv">
          <Download :size="16" />
          导出表格
        </button>
      </div>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="message" class="success-box">{{ message }}</div>

    <div class="inventory-summary">
      <div>
        <span>当前盘存金额</span>
        <b>{{ money(totalAmount) }}</b>
        <small>{{ editingId ? '编辑中' : '新盘存单' }}</small>
      </div>
      <label>
        盘存日期
        <input v-model="checkDate" type="date" />
      </label>
      <label v-if="auth.role !== 'STORE_MANAGER'">
        盘存门店
        <select v-model="selectedStoreId">
          <option value="">请选择门店</option>
          <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.name }}</option>
        </select>
      </label>
      <label>
        备注
        <input v-model.trim="note" placeholder="例如：月底盘存 / 临时复核" />
      </label>
    </div>

    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>物品名称</th>
            <th>类别</th>
            <th>单位</th>
            <th>盘存数量</th>
            <th>单价</th>
            <th>金额</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="line in lines" :key="line.rowId">
            <td><input v-model.trim="line.itemName" placeholder="例如：鲜奶" /></td>
            <td><input v-model.trim="line.category" placeholder="例如：奶制品" /></td>
            <td><input v-model.trim="line.unit" placeholder="件" /></td>
            <td><input v-model.number="line.countedQuantity" type="number" min="0" step="0.01" /></td>
            <td><input v-model.number="line.unitPrice" type="number" min="0" step="0.01" /></td>
            <td><b>{{ money(amount(line)) }}</b></td>
            <td>
              <button class="mini-button" type="button" @click="removeLine(line.rowId)">
                <Trash2 :size="15" />
                删除
              </button>
            </td>
          </tr>
          <tr v-if="!lines.length">
            <td colspan="7" class="empty-cell">暂无盘存行，点击“添加物品”开始测算。</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="inventory-submit">
      <button class="ghost-button" type="button" :disabled="saving" @click="saveDraft">
        <Save :size="16" />
        保存盘存单
      </button>
      <button class="primary-button submit-inline" type="button" :disabled="saving" @click="submitDraft">
        <Send :size="16" />
        提交盘存单
      </button>
    </div>

    <section class="saved-checks">
      <div class="table-heading">
        <div>
          <h3>已保存盘存单</h3>
          <span>草稿可继续编辑，已提交盘存单由运营或财务复核。</span>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>日期</th>
              <th>单号</th>
              <th>门店</th>
              <th>金额</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="check in checks" :key="check.id">
              <td>{{ check.checkDate }}</td>
              <td><b>{{ check.checkNo }}</b></td>
              <td>{{ check.storeName }}</td>
              <td>{{ money(check.totalAmount) }}</td>
              <td>{{ check.statusLabel }}</td>
              <td>
                <div class="row-actions">
                  <button class="mini-button" type="button" @click="editCheck(check.id)">查看</button>
                  <button v-if="canReview && check.status === 'SUBMITTED'" class="mini-button primary" type="button" @click="reviewCheck(check.id)">
                    复核
                  </button>
                </div>
              </td>
            </tr>
            <tr v-if="!checks.length">
              <td colspan="6" class="empty-cell">{{ loading ? '正在读取盘存单...' : '暂无盘存单。' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.inventory-panel {
  display: grid;
  gap: 16px;
}

.inventory-actions,
.inventory-submit,
.row-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.inventory-summary {
  display: grid;
  grid-template-columns: 1.2fr repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 15px;
  border: 1px solid rgba(238, 126, 62, 0.24);
  border-radius: 12px;
  background: var(--primary-soft);
}

.inventory-summary span,
.inventory-summary small {
  display: block;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.inventory-summary b {
  display: block;
  margin: 4px 0;
  font-size: 28px;
}

.inventory-summary label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.inventory-summary input,
.inventory-summary select,
td input {
  width: 100%;
  min-height: 36px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 9px;
  background: #fff;
  outline: none;
}

td input:focus,
.inventory-summary input:focus,
.inventory-summary select:focus {
  border-color: var(--primary);
}

.saved-checks {
  display: grid;
  gap: 10px;
}

@media (max-width: 900px) {
  .inventory-summary {
    grid-template-columns: 1fr;
  }
}
</style>
