<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  Check, ChevronLeft, ChevronRight, Download, Edit3, Eye, Loader2,
  Lock, RefreshCw, Search, Send, Trash2, X, XCircle,
} from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { getStores, type StoreInfo } from '../api/operations'
import {
  approveSalaryRecord, deleteSalaryRecord, exportSalaryCsv,
  generateSalaryWithReport, getSalaryPage, lockSalaryRecord,
  markSalaryPaid, previewSalaryGeneration, rejectSalaryRecord,
  saveSalaryRecord, submitSalaryRecord, type SalaryGenerateReport,
  type SalaryPageResponse, type SalaryRecord, type SalaryRecordPayload,
} from '../api/finance'

/* ---- constants ---- */
const PAGE_SIZE = 20

const WAGE_FIELDS: Array<{ key: keyof SalaryRecordPayload; label: string; kind: 'add' | 'minus' }> = [
  { key: 'base', label: '基本工资', kind: 'add' },
  { key: 'social', label: '社保补助', kind: 'add' },
  { key: 'post', label: '岗位工资', kind: 'add' },
  { key: 'meal', label: '餐补', kind: 'add' },
  { key: 'fullAttendance', label: '全勤', kind: 'add' },
  { key: 'commission', label: '提成', kind: 'add' },
  { key: 'overtime', label: '加班工资', kind: 'add' },
  { key: 'seniority', label: '工龄工资', kind: 'add' },
  { key: 'lateNight', label: '深夜班', kind: 'add' },
  { key: 'subsidy', label: '补贴', kind: 'add' },
  { key: 'performance', label: '绩效', kind: 'add' },
  { key: 'deductUniform', label: '扣工服费', kind: 'minus' },
  { key: 'returnUniform', label: '返工服费', kind: 'minus' },
]

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'PENDING_REVIEW', label: '待审核' },
  { value: 'APPROVED', label: '已审核' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'PAID', label: '已发放' },
  { value: 'LOCKED', label: '已锁定' },
]

/* ---- auth & route ---- */
const route = useRoute()
const auth = useAuthStore()

/* ---- reactive state ---- */
const stores = ref<StoreInfo[]>([])
const selectedMonth = ref(currentMonth())
const selectedStoreId = ref('all')
const statusFilter = ref('')
const keyword = ref('')
const page = ref(1)
const loading = ref(false)
const generating = ref(false)
const saving = ref(false)
const actioningId = ref('')
const deletingId = ref('')
const error = ref('')
const successMessage = ref('')
const formError = ref('')

const pageData = ref<SalaryPageResponse | null>(null)

const previewLoading = ref(false)
const previewData = ref<SalaryGenerateReport | null>(null)
const showPreview = ref(false)

const showDrawer = ref(false)
const drawerMode = ref<'view' | 'edit'>('view')
const drawerRecord = ref<SalaryRecord | null>(null)
const form = reactive<SalaryRecordPayload>(emptyForm())

/* ---- computed ---- */
const canEdit = computed(() => ['ADMIN', 'BOSS', 'OWNER', 'FINANCE'].includes(auth.role))
const canReview = computed(() => ['ADMIN', 'BOSS', 'OWNER'].includes(auth.role))
const accessibleStores = computed(() => {
  if (auth.role !== 'STORE_MANAGER' || !auth.storeScope.length) return stores.value
  return stores.value.filter((s) => auth.storeScope.includes(s.id))
})
const storeMap = computed(() => new Map(stores.value.map((s) => [s.id, s])))
const selectedStoreName = computed(() => {
  if (selectedStoreId.value === 'all') return '全部门店'
  return storeMap.value.get(selectedStoreId.value)?.name || selectedStoreId.value
})

const rows = computed(() => pageData.value?.rows || [])
const total = computed(() => pageData.value?.total || 0)
const totalPages = computed(() => pageData.value?.totalPages || 1)
const summary = computed(() => pageData.value?.summary)
const isEmpty = computed(() => !loading.value && total.value === 0)
const canGenerate = computed(() =>
  canEdit.value && selectedStoreId.value !== 'all' && !loading.value && !generating.value,
)

const statusCounts = computed(() => {
  const map: Record<string, number> = {}
  for (const r of rows.value) map[r.status || 'DRAFT'] = (map[r.status || 'DRAFT'] || 0) + 1
  return map
})

const filteredRows = computed(() => {
  const q = keyword.value.trim()
  const sf = statusFilter.value
  let list = rows.value
  if (sf) list = list.filter((r) => (r.status || 'DRAFT') === sf)
  if (q) {
    list = list.filter((r) =>
      [r.employeeName, r.employeeId, r.position, r.storeName].some(
        (v) => String(v || '').includes(q),
      ),
    )
  }
  return list
})

/* ---- helpers ---- */
function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function money(v?: number) {
  return `¥${Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

function statusLabel(s?: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已审核',
    REJECTED: '已驳回', PAID: '已发放', LOCKED: '已锁定',
  }
  return labels[s || 'DRAFT'] || '草稿'
}

function statusClass(s?: string) {
  const map: Record<string, string> = {
    DRAFT: 'pending', PENDING_REVIEW: 'warn', APPROVED: 'done',
    REJECTED: 'rejected', PAID: 'done', LOCKED: 'muted',
  }
  return map[s || 'DRAFT'] || 'pending'
}

function isEditable(s?: string) {
  return !s || ['DRAFT', 'REJECTED'].includes(s)
}

function deductionTotal(r: SalaryRecord) {
  return Number(r.deductUniform || 0) + Number(r.returnUniform || 0)
}

function allowanceTotal(r: SalaryRecord) {
  return Number(r.social || 0) + Number(r.post || 0) + Number(r.meal || 0) + Number(r.subsidy || 0)
}

function bonusTotal(r: SalaryRecord) {
  return Number(r.fullAttendance || 0) + Number(r.seniority || 0) + Number(r.lateNight || 0)
}

const errorPatterns = ['handler dispatch failed', 'java.lang', 'noclassdeffounderror', 'org.springframework', 'stacktrace', 'exception']
function userError(e: unknown, fallback = '数据加载失败，请稍后重试。') {
  const msg = String(e instanceof Error ? e.message : e || '').trim()
  if (!msg) return fallback
  if (errorPatterns.some((p) => msg.toLowerCase().includes(p))) return fallback
  return msg
}

/* ---- API calls ---- */
async function loadStores() {
  try { stores.value = await getStores() } catch { /* ignore */ }
}

async function loadPage(p = page.value) {
  loading.value = true
  error.value = ''
  try {
    const resp = await getSalaryPage({
      month: selectedMonth.value || undefined,
      storeId: selectedStoreId.value === 'all' ? undefined : selectedStoreId.value,
      page: p,
      size: PAGE_SIZE,
    })
    pageData.value = resp
    page.value = p
  } catch (e) {
    error.value = userError(e)
  } finally {
    loading.value = false
  }
}

function applyRouteDefaults() {
  const qStoreId = typeof route.query.storeId === 'string' ? route.query.storeId : ''
  const qMonth = typeof route.query.month === 'string' ? route.query.month : ''
  const ids = new Set(accessibleStores.value.map((s) => s.id))
  if (qStoreId && ids.has(qStoreId)) selectedStoreId.value = qStoreId
  else if (auth.role === 'STORE_MANAGER' && accessibleStores.value.length)
    selectedStoreId.value = accessibleStores.value[0].id
  if (/^\d{4}-\d{2}$/.test(qMonth)) selectedMonth.value = qMonth
}

async function doPreview() {
  if (selectedStoreId.value === 'all') return
  previewLoading.value = true
  error.value = ''
  try {
    previewData.value = await previewSalaryGeneration(selectedStoreId.value, selectedMonth.value)
    showPreview.value = true
  } catch (e) {
    error.value = userError(e, '预览生成失败。')
  } finally {
    previewLoading.value = false
  }
}

async function doGenerate() {
  if (!canGenerate.value || selectedStoreId.value === 'all') return
  generating.value = true
  error.value = ''
  successMessage.value = ''
  try {
    const report = await generateSalaryWithReport({
      storeId: selectedStoreId.value,
      month: selectedMonth.value,
    })
    const parts = [`已生成 ${report.generated} 条工资记录`]
    if (report.skipped > 0) parts.push(`跳过 ${report.skipped} 条`)
    if (report.errors > 0) parts.push(`${report.errors} 条异常`)
    if (report.skipDetails?.length) parts.push('点击"预览"查看跳过原因')
    successMessage.value = parts.join('，')
    previewData.value = null
    showPreview.value = false
    await loadPage(1)
  } catch (e) {
    error.value = userError(e, '工资记录生成失败。')
  } finally {
    generating.value = false
  }
}

async function doExport() {
  try {
    await exportSalaryCsv({
      month: selectedMonth.value || undefined,
      storeId: selectedStoreId.value === 'all' ? undefined : selectedStoreId.value,
    })
    successMessage.value = '工资 CSV 已导出'
  } catch (e) {
    error.value = userError(e, '导出失败。')
  }
}

/* ---- drawer ---- */
function emptyForm(): SalaryRecordPayload {
  return {
    storeId: '', month: currentMonth(), employeeId: '', employeeName: '',
    position: '', attendance: '', gross: 0, normalHours: 0, otHours: 0,
    workHours: 0, vacationLeft: 0, vacationNote: '', base: 0, social: 0,
    post: 0, meal: 0, fullAttendance: 0, commission: 0, overtime: 0,
    seniority: 0, lateNight: 0, subsidy: 0, performance: 0,
    deductUniform: 0, returnUniform: 0,
  }
}

function openDrawer(record: SalaryRecord, mode: 'view' | 'edit' = 'view') {
  drawerRecord.value = record
  drawerMode.value = canEdit.value && isEditable(record.status) ? mode : 'view'
  Object.assign(form, {
    storeId: record.storeId, month: record.month,
    employeeId: record.employeeId || '', employeeName: record.employeeName,
    position: record.position || '', attendance: record.attendance || '',
    gross: Number(record.gross || 0), normalHours: Number(record.normalHours || 0),
    otHours: Number(record.otHours || 0), workHours: Number(record.workHours || 0),
    vacationLeft: Number(record.vacationLeft || 0), vacationNote: record.vacationNote || '',
    base: Number(record.base || 0), social: Number(record.social || 0),
    post: Number(record.post || 0), meal: Number(record.meal || 0),
    fullAttendance: Number(record.fullAttendance || 0), commission: Number(record.commission || 0),
    overtime: Number(record.overtime || 0), seniority: Number(record.seniority || 0),
    lateNight: Number(record.lateNight || 0), subsidy: Number(record.subsidy || 0),
    performance: Number(record.performance || 0), deductUniform: Number(record.deductUniform || 0),
    returnUniform: Number(record.returnUniform || 0),
  })
  formError.value = ''
  showDrawer.value = true
}

function closeDrawer() {
  showDrawer.value = false
  formError.value = ''
}

function recalcGross() {
  const t = WAGE_FIELDS.reduce((sum, f) => {
    return sum + (f.kind === 'minus' ? -Number(form[f.key] || 0) : Number(form[f.key] || 0))
  }, 0)
  form.gross = Math.max(0, Math.round(t))
}

async function doSave() {
  formError.value = ''
  if (!form.storeId) { formError.value = '请选择门店'; return }
  if (!form.month) { formError.value = '请填写月份'; return }
  if (!form.employeeName.trim()) { formError.value = '请填写员工姓名'; return }
  saving.value = true
  try {
    await saveSalaryRecord(form, drawerRecord.value?.id)
    successMessage.value = '工资记录已保存'
    showDrawer.value = false
    await loadPage()
  } catch (e) {
    formError.value = userError(e, '保存失败。')
  } finally {
    saving.value = false
  }
}

/* ---- row actions ---- */
async function doSubmit(r: SalaryRecord) {
  actioningId.value = r.id; error.value = ''
  try { await submitSalaryRecord(r.id); successMessage.value = '已提交审核'; await loadPage() }
  catch (e) { error.value = userError(e, '提交审核失败。') }
  finally { actioningId.value = '' }
}

async function doApprove(r: SalaryRecord) {
  actioningId.value = r.id; error.value = ''
  try { await approveSalaryRecord(r.id); successMessage.value = '已审核通过'; await loadPage() }
  catch (e) { error.value = userError(e, '审核失败。') }
  finally { actioningId.value = '' }
}

async function doReject(r: SalaryRecord) {
  const note = window.prompt('请输入驳回原因', '请调整工资明细后重新提交')
  if (note === null) return
  actioningId.value = r.id; error.value = ''
  try { await rejectSalaryRecord(r.id, note || '请调整后重新提交'); successMessage.value = '已驳回'; await loadPage() }
  catch (e) { error.value = userError(e, '驳回失败。') }
  finally { actioningId.value = '' }
}

async function doDelete(r: SalaryRecord) {
  if (!canEdit.value || !isEditable(r.status)) return
  if (!window.confirm(`确定删除 ${r.employeeName} ${r.month} 的工资记录？`)) return
  deletingId.value = r.id; error.value = ''
  try { await deleteSalaryRecord(r.id); successMessage.value = '工资记录已删除'; await loadPage() }
  catch (e) { error.value = userError(e, '删除失败。') }
  finally { deletingId.value = '' }
}

async function doMarkPaid(r: SalaryRecord) {
  if (!window.confirm(`确定将 ${r.employeeName} 的工资标记为已发放？`)) return
  actioningId.value = r.id; error.value = ''
  try { await markSalaryPaid(r.id); successMessage.value = '已标记发放'; await loadPage() }
  catch (e) { error.value = userError(e, '操作失败。') }
  finally { actioningId.value = '' }
}

async function doLock(r: SalaryRecord) {
  if (!window.confirm('锁定后该记录将不能修改，确定继续？')) return
  actioningId.value = r.id; error.value = ''
  try { await lockSalaryRecord(r.id); successMessage.value = '工资记录已锁定'; await loadPage() }
  catch (e) { error.value = userError(e, '操作失败。') }
  finally { actioningId.value = '' }
}

/* ---- watchers & lifecycle ---- */
watch([selectedMonth, selectedStoreId], () => { page.value = 1; loadPage() })
watch(
  () => [route.query.storeId, route.query.month],
  () => { applyRouteDefaults(); loadPage() },
)

onMounted(async () => {
  await loadStores()
  applyRouteDefaults()
  await loadPage()
})
</script>

<template>
  <section class="page-panel salary-page">
    <div class="page-head">
      <div>
        <h2>员工工资</h2>
        <span class="head-sub">{{ selectedStoreName }} · {{ selectedMonth || '未选择' }}</span>
      </div>
      <div class="head-actions">
        <button v-if="canEdit && selectedStoreId !== 'all'" class="primary-button" :disabled="previewLoading" @click="doPreview">
          <Eye :size="16" /> {{ previewLoading ? '预览中...' : '预览生成名单' }}
        </button>
        <button v-if="canEdit && selectedStoreId !== 'all'" class="primary-button submit-inline" :disabled="!canGenerate" @click="doGenerate">
          <RefreshCw :size="16" /> {{ generating ? '生成中...' : '生成本月工资' }}
        </button>
        <button class="ghost-button" @click="doExport"><Download :size="16" /> 导出</button>
        <button class="ghost-button" :disabled="loading" @click="loadPage()"><RefreshCw :size="16" /> 刷新</button>
      </div>
    </div>
    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="successMessage" class="success-box">{{ successMessage }}</div>
    <section class="content-card filter-row">
      <label>月份 <input v-model="selectedMonth" type="month" :disabled="loading" /></label>
      <label>门店 <select v-model="selectedStoreId" :disabled="loading || auth.role === 'STORE_MANAGER'">
          <option v-if="auth.role !== 'STORE_MANAGER'" value="all">全部门店</option>
          <option v-for="s in accessibleStores" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select></label>
      <label>状态 <select v-model="statusFilter">
          <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select></label>
      <label class="search-field"><Search :size="16" /><input v-model.trim="keyword" type="search" placeholder="姓名、工号、岗位" /></label>
    </section>
    <div class="summary-row">
      <article class="sum-card main"><span>记录数</span><b>{{ total }}</b></article>
      <article class="sum-card"><span>草稿</span><b>{{ statusCounts['DRAFT'] || 0 }}</b></article>
      <article class="sum-card"><span>待审核</span><b>{{ statusCounts['PENDING_REVIEW'] || 0 }}</b></article>
      <article class="sum-card"><span>已审核</span><b>{{ statusCounts['APPROVED'] || 0 }}</b></article>
      <article class="sum-card"><span>已发放</span><b>{{ (statusCounts['PAID'] || 0) + (statusCounts['LOCKED'] || 0) }}</b></article>
      <article v-if="summary" class="sum-card"><span>应发合计</span><b>{{ money(summary.grossTotal) }}</b></article>
    </div>
    <section v-if="isEmpty && !loading" class="content-card empty-block">
      <div class="empty-icon">📋</div>
      <h3>本月暂无工资记录</h3>
      <p v-if="selectedStoreId === 'all'">请先选择具体门店，再预览并生成工资记录。</p>
      <p v-else>请先预览可生成名单，确认后生成本月工资记录。</p>
      <div class="empty-actions">
        <button v-if="canEdit && selectedStoreId !== 'all'" class="primary-button" :disabled="previewLoading" @click="doPreview"><Eye :size="16" /> 预览生成名单</button>
        <button v-if="canEdit && selectedStoreId !== 'all'" class="primary-button submit-inline" :disabled="!canGenerate" @click="doGenerate"><RefreshCw :size="16" /> 生成本月工资记录</button>
      </div>
    </section>
    <div v-if="loading" class="content-card loading-block"><Loader2 :size="24" class="spin" /> 加载中...</div>
    <section v-if="!isEmpty || loading" class="content-card table-card">
      <div class="table-head"><h3>工资明细</h3><span class="table-count">共 {{ total }} 条 · 第 {{ page }}/{{ totalPages }} 页</span></div>
      <div class="table-wrap">
        <table class="salary-table">
          <thead><tr>
              <th>工号</th><th>姓名</th><th>门店</th><th>岗位</th>
              <th class="r">基本工资</th><th class="r">加班</th><th class="r">绩效</th>
              <th class="r">提成</th><th class="r">津贴</th><th class="r">奖金</th>
              <th class="r">扣款</th><th class="r">应发</th><th>状态</th><th class="r">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="r in filteredRows" :key="r.id" @click="openDrawer(r, 'view')">
              <td class="muted">{{ r.employeeId || '-' }}</td>
              <td><b>{{ r.employeeName }}</b></td>
              <td class="muted">{{ r.storeName || storeMap.get(r.storeId)?.name || r.storeId }}</td>
              <td>{{ r.position || '-' }}</td>
              <td class="r">{{ money(r.base) }}</td>
              <td class="r">{{ money(r.overtime) }}</td>
              <td class="r">{{ money(r.performance) }}</td>
              <td class="r">{{ money(r.commission) }}</td>
              <td class="r">{{ money(allowanceTotal(r)) }}</td>
              <td class="r">{{ money(bonusTotal(r)) }}</td>
              <td class="r">{{ money(deductionTotal(r)) }}</td>
              <td class="r strong">{{ money(r.gross) }}</td>
              <td><span class="status-pill" :class="statusClass(r.status)">{{ statusLabel(r.status) }}</span></td>
              <td class="r action-cell" @click.stop>
                <button class="mini-button" @click="openDrawer(r, 'view')">查看</button>
                <button v-if="canEdit && isEditable(r.status)" class="icon-button" title="编辑" @click="openDrawer(r, 'edit')"><Edit3 :size="14" /></button>
                <button v-if="canEdit && isEditable(r.status)" class="mini-button primary" :disabled="actioningId===r.id" @click="doSubmit(r)">提交<Send :size="12" /></button>
                <button v-if="canReview && r.status==='PENDING_REVIEW'" class="mini-button primary" :disabled="actioningId===r.id" @click="doApprove(r)"><Check :size="14" /></button>
                <button v-if="canReview && r.status==='PENDING_REVIEW'" class="mini-button" :disabled="actioningId===r.id" @click="doReject(r)"><XCircle :size="14" /></button>
                <button v-if="canEdit && r.status==='APPROVED'" class="mini-button" :disabled="actioningId===r.id" @click="doMarkPaid(r)">发放</button>
                <button v-if="canEdit && (r.status==='APPROVED'||r.status==='PAID')" class="icon-button" title="锁定" :disabled="actioningId===r.id" @click="doLock(r)"><Lock :size="13" /></button>
                <button v-if="canEdit && isEditable(r.status)" class="icon-button danger" title="删除" :disabled="deletingId===r.id" @click="doDelete(r)"><Trash2 :size="14" /></button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="totalPages > 1" class="pager">
        <button :disabled="page <= 1" @click="loadPage(page - 1)"><ChevronLeft :size="16" /></button>
        <template v-for="p in totalPages" :key="p">
          <button v-if="p === 1 || p === totalPages || Math.abs(p - page) <= 2" :class="{ active: p === page }" @click="loadPage(p)">{{ p }}</button>
          <span v-else-if="p === page - 3 || p === page + 3" class="pager-ellipsis">…</span>
        </template>
        <button :disabled="page >= totalPages" @click="loadPage(page + 1)"><ChevronRight :size="16" /></button>
      </div>
    </section>
    <!-- preview modal -->
    <div v-if="showPreview" class="modal-backdrop" @click.self="showPreview = false">
      <div class="preview-modal">
        <div class="modal-head"><h3>工资生成预览</h3><button class="icon-button" @click="showPreview = false"><X :size="17" /></button></div>
        <div class="preview-stats">
          <span class="preview-stat warn">跳过 {{ previewData?.skipped || 0 }} 人</span>
          <span v-if="previewData?.errors" class="preview-stat bad">异常 {{ previewData?.errors || 0 }} 人</span>
        </div>
        <div v-if="previewData?.skipDetails?.length" class="preview-list">
          <h4>跳过明细</h4>
          <div v-for="d in previewData.skipDetails" :key="d.employeeId" class="skip-item"><b>{{ d.employeeName }}</b> <span class="muted">{{ d.reason }}</span></div>
        </div>
        <div v-else-if="!previewLoading" class="empty-state compact">所有符合条件员工都可生成工资记录。</div>
        <div class="modal-actions">
          <button class="ghost-button" @click="showPreview = false">关闭</button>
          <button class="primary-button submit-inline" :disabled="!canGenerate" @click="doGenerate">{{ generating ? '生成中...' : '确认生成本月工资' }}</button>
        </div>
      </div>
    </div>
    <!-- detail drawer -->
    <div v-if="showDrawer" class="drawer-backdrop" @click.self="closeDrawer">
      <div class="drawer-panel">
        <div class="drawer-head"><div><h3>{{ drawerMode === 'view' ? '工资详情' : '编辑工资' }}</h3><span>{{ form.employeeName }} · {{ form.month }}</span></div><button class="icon-button" @click="closeDrawer"><X :size="17" /></button></div>
        <div v-if="formError" class="error-box">{{ formError }}</div>
        <div class="drawer-grid">
          <label>门店 <select v-model="form.storeId" disabled><option :value="form.storeId">{{ storeMap.get(form.storeId)?.name || form.storeId }}</option></select></label>
          <label>月份 <input v-model="form.month" type="month" disabled /></label>
          <label>员工 <input v-model="form.employeeName" disabled /></label>
          <label>岗位 <input v-model="form.position" disabled /></label>
          <label>出勤 <input v-model="form.attendance" :disabled="drawerMode==='view'" placeholder="例：26天" /></label>
          <label>应发 <input :value="money(form.gross)" disabled class="gross-input" /></label>
        </div>
        <div class="drawer-section-title">工资明细</div>
        <div class="wage-grid">
          <label v-for="f in WAGE_FIELDS" :key="f.key">{{ f.label }}<input v-model.number="form[f.key]" type="number" step="1" :disabled="drawerMode==='view'" @input="recalcGross" /></label>
        </div>
        <div class="drawer-section-title">工时 / 假期</div>
        <div class="drawer-grid">
          <label>上班时长 <input v-model.number="form.normalHours" type="number" step="0.01" :disabled="drawerMode==='view'" @input="recalcGross" /></label>
          <label>加班时长 <input v-model.number="form.otHours" type="number" step="0.01" :disabled="drawerMode==='view'" @input="recalcGross" /></label>
          <label>总工时 <input v-model.number="form.workHours" type="number" step="0.01" disabled /></label>
          <label>假期剩余 <input v-model.number="form.vacationLeft" type="number" step="1" :disabled="drawerMode==='view'" /></label>
          <label class="wide">备注 <input v-model="form.vacationNote" :disabled="drawerMode==='view'" placeholder="假期、工资核对说明" /></label>
        </div>
        <div class="drawer-actions">
          <button class="ghost-button" @click="closeDrawer">关闭</button>
          <button v-if="drawerMode==='view' && canEdit && isEditable(drawerRecord?.status)" class="primary-button" @click="drawerMode = 'edit'">编辑</button>
          <button v-if="drawerMode==='edit'" class="primary-button submit-inline" :disabled="saving" @click="doSave">{{ saving ? '保存中...' : '保存工资记录' }}</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.salary-page { display: grid; gap: 16px; max-width: 1280px; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.head-sub { color: var(--muted); font-size: 13px; margin-left: 8px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.filter-row { display: grid; grid-template-columns: 150px 220px 140px minmax(200px, 1fr); align-items: end; gap: 12px; padding: 16px; }
.filter-row label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 900; }
.filter-row select, .filter-row input { min-height: 38px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 800; }
.search-field { display: flex; align-items: center; gap: 7px; min-height: 38px; padding: 0 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; }
.search-field input { min-height: auto; width: 100%; padding: 0; border: 0; outline: 0; }
.summary-row { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
.sum-card { min-height: 82px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 12px; background: #fff; box-shadow: 0 10px 24px rgba(24,28,35,0.05); }
.sum-card.main { color: #fff; border: 0; background: #ee7e3e; box-shadow: 0 14px 28px rgba(238,126,62,0.2); }
.sum-card span { color: var(--muted); font-size: 12.5px; font-weight: 900; }
.sum-card.main span { color: rgba(255,255,255,0.86); }
.sum-card b { display: block; margin-top: 7px; font-size: 22px; line-height: 1.05; }
.sum-card.main b { color: #fff; font-size: 26px; }
.table-card { padding: 18px 20px 20px; border-radius: 16px; box-shadow: 0 16px 38px rgba(24,28,35,0.06); }
.table-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.table-head h3 { margin: 0; font-size: 17px; }
.table-count { color: var(--muted); font-size: 13px; }
.table-wrap { overflow-x: auto; }
.salary-table { width: 100%; min-width: 1200px; border-collapse: collapse; font-size: 13.5px; }
.salary-table th, .salary-table td { padding: 10px 8px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: middle; }
.salary-table th { color: var(--muted); font-size: 12px; font-weight: 900; white-space: nowrap; position: sticky; top: 0; background: #fff; z-index: 1; }
.salary-table tbody tr { cursor: pointer; }
.salary-table tbody tr:hover { background: var(--bg); }
.salary-table .r { text-align: right; font-variant-numeric: tabular-nums; }
.muted { color: var(--muted); }
.strong { color: var(--ink); font-weight: 900; }
.status-pill { display: inline-flex; align-items: center; min-height: 24px; padding: 3px 9px; border-radius: 999px; background: rgba(39,174,96,0.12); color: var(--good); font-size: 11.5px; font-weight: 900; }
.status-pill.warn, .status-pill.pending { background: rgba(245,158,11,0.14); color: #a16207; }
.status-pill.done { background: rgba(39,174,96,0.12); color: var(--good); }
.status-pill.rejected { background: rgba(220,38,38,0.12); color: var(--bad); }
.status-pill.muted { background: rgba(107,114,128,0.13); color: var(--muted); }
.action-cell { display: flex; justify-content: flex-end; gap: 5px; white-space: nowrap; }
.icon-button { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--muted); cursor: pointer; }
.icon-button:hover { border-color: rgba(238,126,62,0.34); color: var(--primary-dark); }
.icon-button.danger:hover { border-color: rgba(231,76,60,0.32); color: var(--bad); }
.pager { display: flex; justify-content: center; align-items: center; gap: 4px; margin-top: 16px; }
.pager button { display: inline-flex; align-items: center; justify-content: center; min-width: 34px; height: 34px; padding: 0 8px; border: 1px solid var(--line); border-radius: 8px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 700; cursor: pointer; }
.pager button.active { background: #ee7e3e; border-color: #ee7e3e; color: #fff; }
.pager button:disabled { opacity: 0.4; cursor: default; }
.pager-ellipsis { padding: 0 6px; color: var(--muted); }
.empty-block { text-align: center; padding: 48px 20px; }
.empty-icon { font-size: 48px; margin-bottom: 12px; }
.empty-block h3 { margin: 0 0 8px; font-size: 18px; }
.empty-block p { color: var(--muted); margin: 0 0 20px; }
.empty-actions { display: flex; justify-content: center; gap: 10px; flex-wrap: wrap; }
.loading-block { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 40px; color: var(--muted); font-size: 15px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.modal-backdrop { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px; background: rgba(17,24,39,0.32); }
.preview-modal { width: min(560px, 100%); max-height: calc(100vh - 44px); overflow: auto; padding: 20px; border-radius: 16px; background: #fff; box-shadow: 0 24px 60px rgba(17,24,39,0.22); }
.modal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.modal-head h3 { margin: 0; font-size: 18px; }
.preview-stats { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
.preview-stat { font-size: 15px; font-weight: 800; }
.preview-stat.good { color: var(--good); }
.preview-stat.warn { color: #a16207; }
.preview-stat.bad { color: var(--bad); }
.preview-list { max-height: 260px; overflow-y: auto; margin-bottom: 16px; }
.preview-list h4 { margin: 0 0 8px; font-size: 14px; }
.skip-item { padding: 8px 10px; border-bottom: 1px solid var(--line); font-size: 13px; display: flex; gap: 8px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.drawer-backdrop { position: fixed; inset: 0; z-index: 75; display: grid; place-items: center end; padding: 0; background: rgba(17,24,39,0.28); }
.drawer-panel { width: min(680px, 90vw); height: 100vh; overflow-y: auto; padding: 24px; background: #fff; box-shadow: -8px 0 40px rgba(17,24,39,0.18); }
.drawer-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.drawer-head h3 { margin: 0 0 4px; font-size: 19px; }
.drawer-head span { color: var(--muted); font-size: 13px; }
.drawer-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.drawer-grid .wide { grid-column: 1 / -1; }
.drawer-grid label, .wage-grid label { display: grid; gap: 6px; color: var(--muted); font-size: 12px; font-weight: 900; }
.drawer-grid select, .drawer-grid input, .wage-grid input { min-height: 38px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 10px; background: #fff; color: var(--ink); font-size: 13px; font-weight: 800; }
.drawer-section-title { margin: 18px 0 8px; color: var(--muted); font-size: 12.5px; font-weight: 900; }
.wage-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.gross-input { color: var(--ink) !important; font-weight: 900 !important; }
.drawer-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line); }

@media (max-width: 1080px) {
  .summary-row { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .filter-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .head-actions, .filter-row, .summary-row, .drawer-grid, .wage-grid { grid-template-columns: 1fr; width: 100%; }
  .head-actions .ghost-button, .head-actions .primary-button { width: 100%; }
  .drawer-panel { width: 100vw; padding: 16px; }
}
</style>
