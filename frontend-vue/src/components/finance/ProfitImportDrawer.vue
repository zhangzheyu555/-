<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, FileSpreadsheet, Upload, X } from 'lucide-vue-next'
import {
  cancelProfitImportPreview,
  commitProfitImport,
  confirmProfitImportPreview,
  createProfitImportPreview,
  getProfitImportPreview,
  type ProfitImportPreviewJob,
  type ProfitImportRow,
} from '../../api/imports'
import { money } from '../../stores/profit'
import { prepareCsvFile, type PreparedCsvFile } from '../../utils/csv'
import ModalFooter from '../ui/ModalFooter.vue'
import UiButton from '../ui/UiButton.vue'

interface EditableRow extends ProfitImportRow {
  overwrite: boolean
}

const props = withDefaults(defineProps<{ storeId: string; storeName?: string; month: string; scopeLocked?: boolean }>(), {
  storeName: '',
  scopeLocked: false,
})
const emit = defineEmits<{ close: []; saved: [count: number] }>()

const input = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const rows = ref<EditableRow[]>([])
const error = ref('')
const message = ref('')
const recognizing = ref(false)
const committing = ref(false)
const csvMetadata = ref<PreparedCsvFile | null>(null)
const job = ref<ProfitImportPreviewJob | null>(null)
const stage = ref('等待选择文件')
const progress = ref(0)
const confirmMonthConflict = ref(false)
const showCancelConfirm = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const readyRows = computed(() => rows.value.filter((row) => row.status !== 'ERROR' && !row.errors.length && row.storeId && row.month))
const failedRows = computed(() => rows.value.filter((row) => row.status === 'ERROR' || row.errors.length))
const previewRows = computed(() => rows.value.slice(0, 10))
const conflictCount = computed(() => readyRows.value.filter((row) => row.existing).length)
const hasUnconfirmedConflicts = computed(() => readyRows.value.some((row) => row.existing && !row.overwrite))
const isWorking = computed(() => ['QUEUED', 'PARSING', 'VALIDATING', 'CONFIRMING'].includes(job.value?.status || ''))
const canCommit = computed(() => job.value?.status === 'READY'
  && readyRows.value.length > 0
  && failedRows.value.length === 0
  && (!job.value.monthConflict || confirmMonthConflict.value)
  && !hasUnconfirmedConflicts.value
  && !recognizing.value
  && !committing.value)

function chooseFile() {
  input.value?.click()
}

function onFileChange(event: Event) {
  stopPolling()
  const file = (event.target as HTMLInputElement).files?.[0] || null
  selectedFile.value = file
  rows.value = []
  error.value = ''
  message.value = ''
  csvMetadata.value = null
  job.value = null
  stage.value = file ? '文件已选择' : '等待选择文件'
  progress.value = 0
  confirmMonthConflict.value = false
}

async function recognize() {
  error.value = ''
  message.value = ''
  if (!selectedFile.value) {
    error.value = '请选择 Excel 或 CSV 文件。'
    return
  }
  if (!props.storeId || !props.month) {
    error.value = props.scopeLocked ? '当前账号未配置唯一门店，暂时不能导入。' : '请先选择门店和月份。'
    return
  }
  recognizing.value = true
  stage.value = '上传中'
  progress.value = 5
  confirmMonthConflict.value = false
  try {
    const uploadFile = selectedFile.value.name.toLowerCase().endsWith('.csv')
      ? (csvMetadata.value = await prepareCsvFile(selectedFile.value)).file
      : selectedFile.value
    const response = await createProfitImportPreview(uploadFile, { storeId: props.storeId, month: props.month })
    applyJob(response)
    if (!isTerminal(response.status)) await pollPreview(response.jobId)
  } catch (reason) {
    stage.value = '解析失败'
    error.value = displayError(reason, '文件识别失败，请检查格式后重试。')
  } finally {
    recognizing.value = false
  }
}

async function commit() {
  error.value = ''
  message.value = ''
  if (!canCommit.value || !job.value) {
    error.value = '预览尚未通过校验，暂时不能导入。'
    return
  }
  committing.value = true
  stage.value = '导入中'
  progress.value = 95
  try {
    const response = job.value.legacy
      ? await commitProfitImport(readyRows.value.map((row) => ({
          rowId: row.rowId,
          storeId: props.scopeLocked ? props.storeId : row.storeId,
          month: row.month,
          overwrite: row.overwrite,
          values: row.values,
          note: 'Excel/CSV 导入',
        })))
      : await confirmProfitImportPreview(job.value.jobId, {
          confirmMonthConflict: confirmMonthConflict.value,
          rows: readyRows.value.map((row) => ({ rowId: row.rowId, overwrite: row.overwrite })),
        })
    message.value = `已保存 ${response.saved} 条数据${response.skipped ? `，跳过 ${response.skipped} 条` : ''}。`
    rows.value = (response.rows || []).map((row) => ({ ...row, overwrite: false }))
    stage.value = '导入成功'
    progress.value = 100
    if (response.saved) emit('saved', response.saved)
  } catch (reason) {
    stage.value = '等待确认'
    progress.value = 100
    error.value = displayError(reason, '导入保存失败，请稍后重试。')
  } finally {
    committing.value = false
  }
}

function applyJob(response: ProfitImportPreviewJob) {
  job.value = response
  stage.value = response.stage
  progress.value = response.progress
  const overwriteById = new Map(rows.value.map((row) => [row.rowId, row.overwrite]))
  rows.value = (response.rows || []).map((row) => {
    const mismatchedStore = props.scopeLocked && Boolean(row.storeId) && row.storeId !== props.storeId
    return {
      ...row,
      storeId: props.scopeLocked ? props.storeId : row.storeId,
      storeName: props.scopeLocked ? (props.storeName || row.storeName) : row.storeName,
      warnings: mismatchedStore
        ? Array.from(new Set([...(row.warnings || []), '文件中的门店字段已忽略，按当前绑定门店导入']))
        : row.warnings,
      overwrite: overwriteById.get(row.rowId) || false,
    }
  })
  error.value = response.errors?.join('；') || ''
  if (response.status === 'READY') {
    message.value = `解析完成：有效 ${response.validRows} 条，错误 ${response.errorRows} 条，营业额合计 ${money(response.salesTotal || 0)}。`
  }
}

async function pollPreview(jobId: string) {
  stopPolling()
  await new Promise<void>((resolve) => {
    const poll = async () => {
      try {
        const response = await getProfitImportPreview(jobId)
        applyJob(response)
        if (isTerminal(response.status)) {
          resolve()
          return
        }
        pollTimer = setTimeout(poll, 700)
      } catch (reason) {
        stage.value = '查询进度失败'
        error.value = displayError(reason, '无法获取解析进度，请直接重新识别。')
        resolve()
      }
    }
    pollTimer = setTimeout(poll, 400)
  })
}

function isTerminal(status: string) {
  return ['READY', 'PARTIAL', 'FAILED', 'COMPLETED', 'CANCELLED'].includes(status)
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function requestClose() {
  if (isWorking.value) {
    showCancelConfirm.value = true
    return
  }
  emit('close')
}

async function cancelAndClose() {
  stopPolling()
  if (job.value && isWorking.value && !job.value.legacy) {
    try {
      await cancelProfitImportPreview(job.value.jobId)
    } catch {
      // Closing the drawer must not be blocked by a best-effort cancellation request.
    }
  }
  emit('close')
}

function displayError(reason: unknown, fallback: string) {
  const text = reason instanceof Error ? reason.message : ''
  if (/timeout|超时/i.test(text)) return '文件解析超时，请重试。已选择的文件仍然保留。'
  return /java\.|spring|exception|errorresponse|noclassdeffounderror/i.test(text) ? fallback : (text || fallback)
}

function rowIssue(row: ProfitImportRow) {
  return [...(row.errors || []), ...(row.warnings || [])].join('；') || '-'
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <Teleport to="body">
    <div class="import-drawer-backdrop" @click.self="requestClose" @keydown.esc="requestClose">
      <aside class="import-drawer" role="dialog" aria-modal="true" aria-label="经营数据导入">
        <header class="import-drawer__head">
          <div>
            <span>数据导入</span>
            <h2>{{ storeName || storeId }} · {{ month }}</h2>
          </div>
          <UiButton variant="ghost" icon-only type="button" aria-label="关闭导入" title="关闭" @click="requestClose">
            <template #icon><X :size="18" /></template>
          </UiButton>
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

          <section v-if="selectedFile" class="import-progress" aria-live="polite">
            <div class="import-progress__head">
              <strong>{{ stage }}</strong>
              <span>{{ progress }}%</span>
            </div>
            <div class="import-progress__track"><span :style="{ width: `${progress}%` }" /></div>
            <p v-if="job?.elapsedMs">解析耗时 {{ (job.elapsedMs / 1000).toFixed(1) }} 秒 · 已解析 {{ job.parsedRows }} 条</p>
          </section>

          <div v-if="rows.length" class="import-summary">
            <span>成功 {{ readyRows.length }} 条</span>
            <span>失败 {{ failedRows.length }} 条</span>
            <span v-if="conflictCount">其中 {{ conflictCount }} 条需要确认覆盖</span>
            <span v-if="rows.length > previewRows.length">当前预览前 {{ previewRows.length }} 条</span>
          </div>
          <p v-if="csvMetadata" class="csv-metadata">
            已按 {{ csvMetadata.encoding }} 读取，分隔符：{{ csvMetadata.delimiter === '\t' ? '制表符' : csvMetadata.delimiter === ';' ? '分号' : '逗号' }}；
            表头：{{ csvMetadata.headers.join('、') || '（空）' }}
          </p>
          <div v-if="job?.monthConflict" class="month-conflict">
            <strong>月份不一致</strong>
            <p>文件识别为 {{ job.detectedMonths.join('、') }}，当前选择为 {{ job.selectedMonth }}。</p>
            <label>
              <input v-model="confirmMonthConflict" type="checkbox" />
              使用文件月份 {{ job.detectedMonths.join('、') }} 导入
            </label>
          </div>
          <div v-if="job?.fieldMappings?.length" class="field-mappings">
            <strong>字段映射</strong>
            <span v-for="mapping in job.fieldMappings" :key="mapping">{{ mapping }}</span>
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
                <tr v-for="row in previewRows" :key="row.rowId">
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

        <ModalFooter v-if="!showCancelConfirm" sticky>
          <UiButton variant="secondary" type="button" @click="requestClose">取消</UiButton>
          <UiButton variant="primary" type="button" :disabled="!canCommit" :loading="committing" @click="commit">
            <template #icon><Check :size="16" /></template>
            确认导入
          </UiButton>
        </ModalFooter>
        <ModalFooter v-else sticky>
          <template #info>文件正在解析，确定取消任务并关闭吗？</template>
          <UiButton variant="secondary" type="button" @click="showCancelConfirm = false">继续解析</UiButton>
          <UiButton variant="danger" type="button" @click="cancelAndClose">取消任务并关闭</UiButton>
        </ModalFooter>
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
  max-height: 100dvh;
  overflow: hidden;
  background: var(--surface);
  box-shadow: -18px 0 40px rgba(20, 24, 30, 0.16);
}

.import-drawer__head {
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

.import-file-row {
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

.csv-metadata {
  margin: -4px 0 12px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

.import-progress {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #f8fbfa;
}

.import-progress__head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--ink);
  font-size: 13px;
}

.import-progress__track {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #dfe9e7;
}

.import-progress__track span {
  display: block;
  height: 100%;
  background: var(--primary);
  transition: width 180ms ease;
}

.import-progress p {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.month-conflict {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid #e7b66f;
  border-radius: 6px;
  background: #fff8ec;
  color: var(--ink);
}

.month-conflict p {
  margin: 5px 0 10px;
  color: var(--muted);
}

.month-conflict label,
.field-mappings {
  display: flex;
  gap: 8px;
}

.field-mappings {
  flex-wrap: wrap;
  align-items: center;
  margin: 12px 0;
  color: var(--muted);
  font-size: 12px;
}

.field-mappings span {
  padding: 4px 7px;
  border-radius: 4px;
  background: #eef5f4;
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

@media (max-width: 680px) {
  .import-drawer__head,
  .import-drawer__body {
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
