<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, FileSpreadsheet, Upload, X } from 'lucide-vue-next'
import {
  cancelProfitImportPreview,
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

const props = withDefaults(defineProps<{ storeId: string; storeName?: string; month: string }>(), {
  storeName: '',
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
const showCancelConfirm = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null

const targetLabel = computed(() => `${props.storeName || props.storeId} · ${props.month}`)
const rowIsTarget = (row: ProfitImportRow) => row.storeId === props.storeId && row.month === props.month
const rowIsValidTarget = (row: ProfitImportRow) => rowIsTarget(row) && row.status !== 'ERROR' && !(row.errors || []).length
const readyRows = computed(() => rows.value.filter(rowIsValidTarget))
const failedRows = computed(() => rows.value.filter((row) => !rowIsValidTarget(row)))
const targetRow = computed(() => readyRows.value.length === 1 ? readyRows.value[0] : null)
const conflictRow = computed(() => targetRow.value?.existing ? targetRow.value : null)
const hasUnconfirmedConflict = computed(() => Boolean(conflictRow.value && !conflictRow.value.overwrite))
const hasOverwriteSummary = computed(() => !conflictRow.value || Boolean(conflictRow.value.existingValues))
const isWorking = computed(() => ['QUEUED', 'PARSING', 'VALIDATING', 'CONFIRMING'].includes(job.value?.status || ''))
const canCommit = computed(() => job.value?.status === 'READY'
  && rows.value.length === 1
  && readyRows.value.length === 1
  && failedRows.value.length === 0
  && !hasUnconfirmedConflict.value
  && hasOverwriteSummary.value
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
  stage.value = '上传中'
  progress.value = 5
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
    const response = await confirmProfitImportPreview(job.value.jobId, {
      rows: [targetRow.value!].map((row) => ({ rowId: row.rowId, overwrite: row.overwrite })),
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
  rows.value = (response.rows || []).map((row) => ({
    ...row,
    overwrite: overwriteById.get(row.rowId) || false,
  }))
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
  if (job.value && isWorking.value) {
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

          <p class="import-scope-note">
            本页仅导入“{{ storeName || storeId }} · {{ month }}”的一条月度汇总记录；多门店、跨月份或重复记录请拆分或合并后再导入。
          </p>

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
            <span>可导入 {{ readyRows.length }} 条</span>
            <span>范围或格式错误 {{ failedRows.length }} 条</span>
            <span v-if="conflictRow">当前目标已有数据，需确认覆盖</span>
          </div>
          <p v-if="csvMetadata" class="csv-metadata">
            已按 {{ csvMetadata.encoding }} 读取，分隔符：{{ csvMetadata.delimiter === '\t' ? '制表符' : csvMetadata.delimiter === ';' ? '分号' : '逗号' }}；
            表头：{{ csvMetadata.headers.join('、') || '（空）' }}
          </p>
          <section v-if="conflictRow" class="overwrite-summary" aria-label="覆盖前后金额摘要">
            <strong>覆盖确认：{{ targetLabel }}</strong>
            <p>本次仅会覆盖当前门店当前月份的一条月度汇总记录。</p>
            <div v-if="conflictRow.existingValues" class="overwrite-summary__amounts">
              <span>营业额：{{ money(conflictRow.existingValues.sales || 0) }} → {{ money(conflictRow.values.sales || 0) }}</span>
              <span>食材成本：{{ money(conflictRow.existingValues.material || 0) }} → {{ money(conflictRow.values.material || 0) }}</span>
              <span>人工成本：{{ money(conflictRow.existingValues.labor || 0) }} → {{ money(conflictRow.values.labor || 0) }}</span>
            </div>
            <p v-else class="row-error">未返回覆盖前金额摘要，暂不能确认覆盖，请重新预览。</p>
            <label v-if="conflictRow.existingValues" class="overwrite-check">
              <input v-model="conflictRow.overwrite" type="checkbox" />
              我已核对上述金额，确认覆盖 {{ targetLabel }} 的已有数据
            </label>
          </section>
          <div v-if="job?.fieldMappings?.length" class="field-mappings">
            <strong>字段映射</strong>
            <span v-for="mapping in job.fieldMappings" :key="mapping">{{ mapping }}</span>
          </div>

          <div v-if="rows.length" class="table-wrap import-table-wrap" aria-label="文件逐行校验结果">
            <table>
              <thead>
                <tr>
                  <th>门店</th>
                  <th>月份</th>
                  <th>营业额</th>
                  <th>食材成本</th>
                  <th>人工成本</th>
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
                  <td :class="{ 'row-error': row.errors?.length }">{{ rowIssue(row) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="import-empty">选择文件后先预览识别结果。</div>
        </div>

        <ModalFooter v-if="!showCancelConfirm" sticky>
          <template v-if="failedRows.length" #info>
            文件含 {{ failedRows.length }} 条范围或格式错误记录，不能导入。请拆分或合并文件后重新预览。
          </template>
          <template v-else-if="!hasOverwriteSummary" #info>
            未返回覆盖前金额摘要，暂不能确认覆盖，请重新预览。
          </template>
          <template v-else-if="hasUnconfirmedConflict" #info>
            请先确认覆盖当前门店当前月份的一条已有数据
          </template>
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
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: center;
  gap: 10px;
}

.import-file-row input {
  display: none;
}

.import-file-row .secondary-button,
.import-file-row .primary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 40px;
}

.import-file-row .secondary-button {
  flex: 1;
  min-width: 0;
  max-width: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-file-row .primary-button {
  flex: 0 0 auto;
  width: auto;
  margin: 0;
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

.overwrite-summary {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid #e7b66f;
  border-radius: 6px;
  background: #fff8ec;
  color: var(--ink);
}

.overwrite-summary p {
  margin: 5px 0 10px;
  color: var(--muted);
}

.overwrite-summary__amounts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 10px 0;
  color: var(--ink);
  font-size: 13px;
}

.overwrite-summary__amounts span {
  padding: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.72);
}

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
  max-height: min(440px, 50dvh);
  overflow: auto;
}

.import-scope-note {
  margin: 10px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.55;
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
    flex: 0 0 auto;
    max-width: none;
    width: 100%;
    margin: 0;
  }

  .overwrite-summary__amounts {
    grid-template-columns: 1fr;
  }
}
</style>
