<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, FileSpreadsheet, Upload, X } from 'lucide-vue-next'
import {
  commitProfitImport,
  recognizeProfitImport,
  type ProfitImportRow,
} from '../../api/imports'
import { money } from '../../stores/profit'

interface EditableRow extends ProfitImportRow {
  overwrite: boolean
}

const props = defineProps<{
  storeId: string
  storeName?: string
  month: string
}>()

const emit = defineEmits<{
  close: []
  saved: [count: number]
}>()

const input = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const rows = ref<EditableRow[]>([])
const error = ref('')
const message = ref('')
const recognizing = ref(false)
const committing = ref(false)

const readyRows = computed(() => rows.value.filter((row) => row.status !== 'ERROR' && !row.errors.length && row.storeId && row.month))
const conflictCount = computed(() => readyRows.value.filter((row) => row.existing).length)

function chooseFile() {
  input.value?.click()
}

function onFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] || null
  selectedFile.value = file
  rows.value = []
  error.value = ''
  message.value = ''
}

async function recognize() {
  error.value = ''
  message.value = ''
  if (!selectedFile.value) {
    error.value = '请选择 Excel 或 CSV 文件。'
    return
  }
  if (!props.storeId || !props.month) {
    error.value = '请先选择门店和月份。'
    return
  }
  recognizing.value = true
  try {
    const response = await recognizeProfitImport(selectedFile.value, {
      storeId: props.storeId,
      month: props.month,
    })
    rows.value = (response.rows || []).map((row) => ({ ...row, overwrite: false }))
    error.value = response.errors?.join('；') || ''
    if (!error.value) message.value = `已识别 ${rows.value.length} 条数据，请确认后导入。`
  } catch (reason) {
    error.value = displayError(reason, '文件识别失败，请检查格式后重试。')
  } finally {
    recognizing.value = false
  }
}

async function commit() {
  error.value = ''
  message.value = ''
  if (!readyRows.value.length) {
    error.value = '没有可导入的数据。'
    return
  }
  if (readyRows.value.some((row) => row.existing && !row.overwrite)) {
    error.value = '存在已有月份数据，请勾选覆盖后再确认导入。'
    return
  }
  committing.value = true
  try {
    const response = await commitProfitImport(readyRows.value.map((row) => ({
      rowId: row.rowId,
      storeId: row.storeId,
      month: row.month,
      overwrite: row.overwrite,
      values: row.values,
      note: 'Excel/CSV 导入',
    })))
    message.value = `已保存 ${response.saved} 条数据${response.skipped ? `，跳过 ${response.skipped} 条` : ''}。`
    rows.value = (response.rows || []).map((row) => ({ ...row, overwrite: false }))
    if (response.saved) emit('saved', response.saved)
  } catch (reason) {
    error.value = displayError(reason, '导入保存失败，请稍后重试。')
  } finally {
    committing.value = false
  }
}

function displayError(reason: unknown, fallback: string) {
  const text = reason instanceof Error ? reason.message : ''
  return /java\.|spring|exception|errorresponse|noclassdeffounderror/i.test(text) ? fallback : (text || fallback)
}

function rowIssue(row: ProfitImportRow) {
  return [...(row.errors || []), ...(row.warnings || [])].join('；') || '-'
}
</script>

<template>
  <Teleport to="body">
    <div class="import-drawer-backdrop" @click.self="emit('close')">
      <aside class="import-drawer" aria-label="经营数据导入">
        <header class="import-drawer__head">
          <div>
            <span>数据导入</span>
            <h2>{{ storeName || storeId }} · {{ month }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭导入" @click="emit('close')">
            <X :size="18" />
          </button>
        </header>

        <div class="import-drawer__body">
          <div class="import-file-row">
            <input ref="input" type="file" accept=".xlsx,.xls,.csv" @change="onFileChange" />
            <button class="secondary-button" type="button" @click="chooseFile">
              <FileSpreadsheet :size="16" />
              {{ selectedFile ? selectedFile.name : '选择文件' }}
            </button>
            <button class="primary-button" type="button" :disabled="recognizing" @click="recognize">
              <Upload :size="16" />
              {{ recognizing ? '识别中' : '识别并预览' }}
            </button>
          </div>

          <p v-if="error" class="import-notice import-notice--error">{{ error }}</p>
          <p v-else-if="message" class="import-notice import-notice--success">{{ message }}</p>

          <div v-if="rows.length" class="import-summary">
            <span>可导入 {{ readyRows.length }} 条</span>
            <span v-if="conflictCount">其中 {{ conflictCount }} 条需要确认覆盖</span>
          </div>

          <div v-if="rows.length" class="table-wrap import-table-wrap">
            <table>
              <thead>
                <tr>
                  <th>门店</th>
                  <th>月份</th>
                  <th>营业额</th>
                  <th>食材成本</th>
                  <th>人工成本</th>
                  <th>覆盖</th>
                  <th>识别结果</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.rowId">
                  <td>{{ row.storeName || row.storeId }}</td>
                  <td>{{ row.month || '-' }}</td>
                  <td>{{ money(row.values.sales || 0) }}</td>
                  <td>{{ money(row.values.material || 0) }}</td>
                  <td>{{ money(row.values.labor || 0) }}</td>
                  <td>
                    <label v-if="row.existing" class="overwrite-check">
                      <input v-model="row.overwrite" type="checkbox" />
                      覆盖
                    </label>
                    <span v-else>-</span>
                  </td>
                  <td :class="{ 'row-error': row.errors?.length }">{{ rowIssue(row) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="import-empty">选择文件后先预览识别结果。</div>
        </div>

        <footer class="import-drawer__footer">
          <button class="ghost-button" type="button" @click="emit('close')">取消</button>
          <button class="primary-button" type="button" :disabled="!readyRows.length || committing" @click="commit">
            <Check :size="16" />
            {{ committing ? '导入中' : '确认导入' }}
          </button>
        </footer>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.import-drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  justify-content: flex-end;
  background: rgba(22, 25, 31, 0.28);
}

.import-drawer {
  display: flex;
  flex-direction: column;
  width: min(980px, 100%);
  height: 100%;
  background: var(--surface);
  box-shadow: -18px 0 40px rgba(20, 24, 30, 0.16);
}

.import-drawer__head,
.import-drawer__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.import-drawer__head span {
  color: var(--muted);
  font-size: 12px;
}

.import-drawer__head h2 {
  margin: 3px 0 0;
  font-size: 18px;
}

.import-drawer__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 18px;
}

.import-file-row,
.import-drawer__footer {
  display: flex;
  align-items: center;
  gap: 10px;
}

.import-file-row input {
  display: none;
}

.import-file-row .secondary-button {
  max-width: min(480px, 100%);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-notice {
  margin: 12px 0;
  padding: 9px 11px;
  border-radius: 6px;
  font-size: 13px;
}

.import-notice--error {
  background: #fff3f1;
  color: var(--bad);
}

.import-notice--success {
  background: #eff8f2;
  color: var(--good);
}

.import-summary {
  display: flex;
  gap: 14px;
  margin: 12px 0;
  color: var(--muted);
  font-size: 13px;
}

.import-table-wrap {
  border: 1px solid var(--line);
  border-radius: 6px;
}

.overwrite-check {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.row-error {
  color: var(--bad);
}

.import-empty {
  padding: 30px 0;
  color: var(--muted);
  text-align: center;
}

.import-drawer__footer {
  justify-content: flex-end;
  border-top: 1px solid var(--line);
  border-bottom: 0;
}

.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: var(--ink);
}

@media (max-width: 680px) {
  .import-drawer__head,
  .import-drawer__body,
  .import-drawer__footer {
    padding-left: 12px;
    padding-right: 12px;
  }

  .import-file-row {
    align-items: stretch;
    flex-direction: column;
  }

  .import-file-row .secondary-button,
  .import-file-row .primary-button {
    max-width: none;
    width: 100%;
  }
}
</style>
