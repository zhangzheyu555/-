<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import PageHeader from '../components/common/PageHeader.vue'
import { PERMISSIONS } from '../permissions/permissions'
import { isBossRole } from '../permissions/roles'
import { useAuthStore } from '../stores/auth'
import {
  createEmployee, createEmployeeAccount, getEmployees, importEmployees,
  removeEmployee, updateEmployee,
  type EmployeeAccountResult, type EmployeeImportReport, type EmployeeRecord, type EmployeeUpsert,
} from '../api/employees'
import { getStores, type StoreInfo } from '../api/operations'

const auth = useAuthStore()
const canManage = computed(() => auth.hasPermission(PERMISSIONS.EMPLOYEE_MANAGE))
const isBoss = computed(() => isBossRole(auth.role))

const rows = ref<EmployeeRecord[]>([])
const stores = ref<StoreInfo[]>([])
const loading = ref(false)
const pageError = ref('')

const storeFilter = ref('')
const statusFilter = ref('在职')
const keyword = ref('')
const onlyHealthProblem = ref(false)

/* ---------- 健康证标红：无证 / 已过期 / 30 天内到期，三种都是红 ---------- */
const MS_PER_DAY = 24 * 3600 * 1000
function healthState(row: EmployeeRecord): { level: 'ok' | 'danger'; text: string } {
  if (row.status && row.status !== '在职') {
    return { level: 'ok', text: row.healthCertExpireDate || '—' }
  }
  if (!row.healthCertExpireDate) {
    return { level: 'danger', text: '无健康证' }
  }
  const expire = new Date(row.healthCertExpireDate + 'T00:00:00')
  const days = Math.floor((expire.getTime() - Date.now()) / MS_PER_DAY)
  if (days < 0) {
    return { level: 'danger', text: `${row.healthCertExpireDate} 已过期` }
  }
  if (days <= 30) {
    return { level: 'danger', text: `${row.healthCertExpireDate}（${days} 天后到期）` }
  }
  return { level: 'ok', text: row.healthCertExpireDate }
}

/* ---------- 工龄工资 & 生日福利：全职(正式员工)/长期兼职享受，普通兼职没有 ---------- */
const SENIORITY_TIERS = [ // 每满半年 +100，两年 400 封顶
  { months: 24, pay: 400, label: '已满2年' },
  { months: 18, pay: 300, label: '已满1年半' },
  { months: 12, pay: 200, label: '已满1年' },
  { months: 6, pay: 100, label: '已满半年' },
]
const BIRTHDAY_BONUS = 200
const BIRTHDAY_SOON_DAYS = 30

function benefitEligible(r: EmployeeRecord): boolean {
  const type = r.employmentType || '全职'
  return r.status === '在职' && (type === '全职' || type === '长期兼职')
}

function seniorityPay(r: EmployeeRecord): { pay: number; text: string } {
  if (!benefitEligible(r) || !r.hireDate) return { pay: 0, text: '—' }
  const hire = new Date(r.hireDate + 'T00:00:00')
  if (Number.isNaN(hire.getTime())) return { pay: 0, text: '—' }
  const now = new Date()
  let months = (now.getFullYear() - hire.getFullYear()) * 12 + (now.getMonth() - hire.getMonth())
  if (now.getDate() < hire.getDate()) months -= 1
  const tier = SENIORITY_TIERS.find((t) => months >= t.months)
  if (!tier) return { pay: 0, text: '未满半年' }
  return { pay: tier.pay, text: `${tier.pay}（${tier.label}）` }
}

/* 生日存「月.日」字符串（部分含年份如 1991.8.19），取最后两段当月/日 */
function birthdayState(r: EmployeeRecord): { soon: boolean; text: string } {
  const raw = (r.birthday || '').trim()
  if (!raw) return { soon: false, text: '—' }
  if (!benefitEligible(r)) return { soon: false, text: raw }
  const parts = raw.split(/[.\-/月日\s]+/).filter(Boolean)
  if (parts.length < 2) return { soon: false, text: raw }
  const month = Number(parts[parts.length - 2])
  const day = Number(parts[parts.length - 1])
  if (!(month >= 1 && month <= 12 && day >= 1 && day <= 31)) return { soon: false, text: raw }
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  let next = new Date(today.getFullYear(), month - 1, day)
  if (next.getTime() < today.getTime()) next = new Date(today.getFullYear() + 1, month - 1, day)
  const days = Math.round((next.getTime() - today.getTime()) / MS_PER_DAY)
  if (days > BIRTHDAY_SOON_DAYS) return { soon: false, text: raw }
  return { soon: true, text: `${raw}（${days === 0 ? '今天生日' : `${days} 天后生日`}·福利 ${BIRTHDAY_BONUS}）` }
}

const onlyBirthdaySoon = ref(false)
const birthdaySoonCount = computed(() =>
  rows.value.filter((r) => (!statusFilter.value || r.status === statusFilter.value)
    && birthdayState(r).soon).length)

const filtered = computed(() =>
  rows.value.filter((r) => {
    if (storeFilter.value && r.storeId !== storeFilter.value) return false
    if (statusFilter.value && r.status !== statusFilter.value) return false
    if (keyword.value) {
      const k = keyword.value.trim()
      if (!r.name.includes(k) && !(r.phone || '').includes(k)) return false
    }
    if (onlyHealthProblem.value && healthState(r).level !== 'danger') return false
    if (onlyBirthdaySoon.value && !birthdayState(r).soon) return false
    return true
  }))

const healthProblemCount = computed(() =>
  rows.value.filter((r) => (!statusFilter.value || r.status === statusFilter.value)
    && healthState(r).level === 'danger').length)

const storeOptions = computed(() => {
  const seen = new Map<string, string>()
  for (const s of stores.value) seen.set(s.id, s.name)
  for (const r of rows.value) if (!seen.has(r.storeId)) seen.set(r.storeId, r.storeName || r.storeId)
  return [...seen.entries()].map(([id, name]) => ({ id, name }))
})

async function load() {
  loading.value = true
  pageError.value = ''
  try {
    rows.value = await getEmployees({})
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : '加载员工档案失败。'
  } finally {
    loading.value = false
  }
}
async function loadStores() {
  try {
    stores.value = await getStores()
  } catch {
    stores.value = [] // 店长无门店管理权限时降级用员工数据推门店
  }
}
onMounted(() => {
  load()
  loadStores()
})

/* ---------- 新增 / 编辑 ---------- */
const emptyForm = (): EmployeeUpsert => ({
  storeId: '', name: '', phone: '', position: '', employmentType: '全职', status: '在职',
  hireDate: '', birthday: '', idCardNo: '', healthCertIssueDate: '', healthCertExpireDate: '',
  contractSignText: '', regularDate: '', trainerDate: '', shiftLeaderDate: '', managerDate: '', remark: '',
  hourlyRate: null,
})

/* ---------- 时薪选择栏（兼职/实习/长期阿姨适用；空=默认：实习兼职15、阿姨18） ---------- */
const HOURLY_RATE_OPTIONS = [12, 13, 15, 18, 20]
const hourlyMode = ref('')
function syncHourlyMode() {
  const v = form.hourlyRate
  if (v === null || v === undefined || v === 0) hourlyMode.value = ''
  else hourlyMode.value = HOURLY_RATE_OPTIONS.includes(Number(v)) ? String(v) : 'custom'
}
function onHourlyModeChange() {
  if (hourlyMode.value === '') form.hourlyRate = null
  else if (hourlyMode.value !== 'custom') form.hourlyRate = Number(hourlyMode.value)
}
const dialogOpen = ref(false)
const editingId = ref('')
const form = reactive<EmployeeUpsert>(emptyForm())
const saving = ref(false)
const dialogError = ref('')

function openCreate() {
  Object.assign(form, emptyForm())
  editingId.value = ''
  dialogError.value = ''
  syncHourlyMode()
  dialogOpen.value = true
}
function openEdit(row: EmployeeRecord) {
  Object.assign(form, emptyForm(), {
    storeId: row.storeId, name: row.name, phone: row.phone, position: row.position,
    employmentType: row.employmentType || '全职', status: row.status || '在职',
    hireDate: row.hireDate, birthday: row.birthday, idCardNo: row.idCardNo,
    healthCertIssueDate: row.healthCertIssueDate, healthCertExpireDate: row.healthCertExpireDate,
    contractSignText: row.contractSignText, regularDate: row.regularDate,
    trainerDate: row.trainerDate, shiftLeaderDate: row.shiftLeaderDate,
    managerDate: row.managerDate, remark: row.remark, hourlyRate: row.hourlyRate ?? null,
  })
  editingId.value = row.id
  dialogError.value = ''
  syncHourlyMode()
  dialogOpen.value = true
}
async function save() {
  if (!form.storeId || !form.name.trim()) {
    dialogError.value = '门店与姓名必填。'
    return
  }
  saving.value = true
  dialogError.value = ''
  try {
    const payload = {
      ...form,
      hourlyRate: typeof form.hourlyRate === 'number' && form.hourlyRate > 0 ? form.hourlyRate : null,
    }
    if (editingId.value) {
      await updateEmployee(editingId.value, payload)
    } else {
      await createEmployee(payload)
    }
    dialogOpen.value = false
    await load()
  } catch (e) {
    dialogError.value = e instanceof Error ? e.message : '保存失败。'
  } finally {
    saving.value = false
  }
}
async function offboard(row: EmployeeRecord) {
  if (!window.confirm(`确认将「${row.name}」标记为离职？档案保留，登录账号将被禁用。`)) return
  try {
    await removeEmployee(row.id)
    await load()
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : '操作失败。'
  }
}

/* ---------- 开号（兼职不开号） ---------- */
const accountResults = ref<EmployeeAccountResult[]>([])
const accountDialogOpen = ref(false)
const accountBusy = ref(false)
const canOpenAccount = (r: EmployeeRecord) =>
  !r.accountUsername && r.status === '在职' && r.employmentType !== '兼职'

async function openAccount(row: EmployeeRecord) {
  accountBusy.value = true
  try {
    const result = await createEmployeeAccount(row.id)
    accountResults.value = [result]
    accountDialogOpen.value = true
    await load()
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : '开号失败。'
  } finally {
    accountBusy.value = false
  }
}
async function batchOpenAccounts() {
  const targets = filtered.value.filter(canOpenAccount)
  if (!targets.length) {
    pageError.value = '当前筛选范围内没有可开号的员工（已有账号、离职或兼职的都会跳过）。'
    return
  }
  if (!window.confirm(`将为 ${targets.length} 名在职全职员工开号（兼职自动跳过），继续？`)) return
  accountBusy.value = true
  const results: EmployeeAccountResult[] = []
  const failures: string[] = []
  for (const t of targets) {
    try {
      results.push(await createEmployeeAccount(t.id))
    } catch (e) {
      failures.push(`${t.name}: ${e instanceof Error ? e.message : '失败'}`)
    }
  }
  accountBusy.value = false
  accountResults.value = results
  accountDialogOpen.value = results.length > 0
  pageError.value = failures.length ? `部分开号失败：${failures.join('；')}` : ''
  await load()
}
function copyAccounts() {
  const text = accountResults.value.map((r) => `${r.employeeName}\t${r.username}\t${r.initialPassword}`).join('\n')
  navigator.clipboard?.writeText(`姓名\t账号\t初始密码\n${text}`)
}

/* ---------- 导入 / 导出 ---------- */
const fileInput = ref<HTMLInputElement>()
const importing = ref(false)
const importReport = ref<EmployeeImportReport | null>(null)
async function onImportFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  importing.value = true
  pageError.value = ''
  importReport.value = null
  try {
    importReport.value = await importEmployees(file)
    await load()
    await loadStores()
  } catch (e) {
    pageError.value = e instanceof Error ? e.message : '导入失败。'
  } finally {
    importing.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}
function exportCsv() {
  const header = ['门店', '姓名', '职位', '用工类型', '账号', '入职日期', '工龄工资', '生日', '生日福利', '身份证',
    '健康证办理', '健康证到期', '健康证状态', '合同签署', '状态', '电话']
  const csvText = (s?: string) => `"${(s || '').replace(/"/g, '""')}"`
  const lines = [header.join(',')]
  for (const r of filtered.value) {
    const h = healthState(r)
    const b = birthdayState(r)
    lines.push([csvText(r.storeName), csvText(r.name), csvText(r.position), csvText(r.employmentType),
      csvText(r.accountUsername), csvText(r.hireDate), csvText(seniorityPay(r).text),
      csvText(r.birthday), csvText(b.soon ? `近期生日·福利${BIRTHDAY_BONUS}` : ''), csvText(r.idCardNo),
      csvText(r.healthCertIssueDate), csvText(r.healthCertExpireDate),
      csvText(h.level === 'danger' ? h.text : '正常'),
      csvText(r.contractSignText), csvText(r.status), csvText(r.phone)].join(','))
  }
  const csv = '﻿' + lines.join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `员工档案_${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<template>
  <section class="page-panel staff-page">
    <PageHeader />

    <div class="content-card">
      <div class="staff-toolbar">
        <label>门店：
          <select v-model="storeFilter">
            <option value="">全部</option>
            <option v-for="s in storeOptions" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </label>
        <label>状态：
          <select v-model="statusFilter">
            <option value="在职">在职</option>
            <option value="离职">离职</option>
            <option value="">全部</option>
          </select>
        </label>
        <input v-model="keyword" type="text" placeholder="搜索姓名/手机号" class="search-input" />
        <button :class="{ danger: onlyHealthProblem }" @click="onlyHealthProblem = !onlyHealthProblem">
          健康证异常（{{ healthProblemCount }}）
        </button>
        <button :class="{ warning: onlyBirthdaySoon }" @click="onlyBirthdaySoon = !onlyBirthdaySoon">
          近期生日（{{ birthdaySoonCount }}）
        </button>
        <span class="spacer" />
        <button v-if="canManage" class="primary" @click="openCreate">新增员工</button>
        <button v-if="canManage" :disabled="accountBusy" @click="batchOpenAccounts">批量开号</button>
        <button v-if="isBoss" :disabled="importing" @click="fileInput?.click()">
          {{ importing ? '导入中…' : '导入 Excel' }}
        </button>
        <input ref="fileInput" type="file" accept=".xlsx" hidden @change="onImportFile" />
        <button @click="exportCsv">导出 CSV</button>
      </div>

      <p v-if="pageError" class="msg warn-text">{{ pageError }}</p>
      <p v-if="loading" class="msg muted">加载中…</p>

      <div v-if="importReport" class="import-report">
        导入完成：新增 {{ importReport.created }}、更新 {{ importReport.updated }}、跳过 {{ importReport.skipped }}
        <template v-if="importReport.createdStores.length">
          ；自动建店 {{ importReport.createdStores.join('、') }}
        </template>
        <details v-if="importReport.problems.length">
          <summary>{{ importReport.problems.length }} 条数据问题（已导入，需人工核对）</summary>
          <p v-for="(p, i) in importReport.problems" :key="i" class="muted">{{ p }}</p>
        </details>
      </div>

      <table v-if="filtered.length" class="staff-table">
        <thead>
          <tr>
            <th>姓名</th><th>门店</th><th>职位</th><th>用工</th><th>账号</th>
            <th>入职日期</th><th>工龄工资</th><th>生日</th><th>健康证到期</th><th>状态</th>
            <th v-if="canManage">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in filtered" :key="r.id"
            :class="{ 'health-danger-row': healthState(r).level === 'danger', 'birthday-soon-row': birthdayState(r).soon }">
            <td>{{ r.name }}</td>
            <td>{{ r.storeName || r.storeId }}</td>
            <td>{{ r.position }}</td>
            <td>{{ r.employmentType || '全职' }}<span v-if="r.hourlyRate" class="muted">（{{ r.hourlyRate }}元/时）</span></td>
            <td>
              <template v-if="r.accountUsername">
                {{ r.accountUsername }}<span v-if="r.accountEnabled === false" class="muted">（已禁用）</span>
              </template>
              <span v-else-if="r.employmentType === '兼职'" class="muted">兼职不开号</span>
              <button v-else-if="canManage && canOpenAccount(r)" class="link-btn" :disabled="accountBusy"
                @click="openAccount(r)">开号</button>
              <span v-else class="muted">—</span>
            </td>
            <td>{{ r.hireDate || '—' }}</td>
            <td>{{ seniorityPay(r).text }}</td>
            <td :class="{ 'birthday-soon': birthdayState(r).soon }">{{ birthdayState(r).text }}</td>
            <td :class="{ 'health-danger': healthState(r).level === 'danger' }">{{ healthState(r).text }}</td>
            <td>{{ r.status }}</td>
            <td v-if="canManage">
              <button class="link-btn" @click="openEdit(r)">编辑</button>
              <button v-if="r.status === '在职'" class="link-btn danger" @click="offboard(r)">离职</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!loading" class="msg muted">
        没有符合条件的员工。老板可点「导入 Excel」上传《门店人员信息.xlsx》初始化档案。
      </p>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="dialogOpen" class="modal-mask" @click.self="dialogOpen = false">
      <div class="modal-box">
        <header class="modal-head">
          <h3>{{ editingId ? '编辑员工档案' : '新增员工' }}</h3>
          <button class="icon-btn" @click="dialogOpen = false">✕</button>
        </header>
        <div class="modal-body form-grid">
          <label>门店 *
            <select v-model="form.storeId" :disabled="!!editingId">
              <option value="" disabled>请选择门店</option>
              <option v-for="s in storeOptions" :key="s.id" :value="s.id">{{ s.name }}</option>
            </select>
          </label>
          <label>姓名 *<input v-model="form.name" type="text" :disabled="!!editingId" /></label>
          <label>职位<input v-model="form.position" type="text" placeholder="店员/训练员/领班/店长" /></label>
          <label>用工类型
            <select v-model="form.employmentType">
              <option value="全职">全职</option>
              <option value="长期兼职">长期兼职（有工龄工资/生日福利）</option>
              <option value="兼职">兼职（不开登录账号，无福利）</option>
            </select>
          </label>
          <label>时薪（兼职/实习/阿姨适用）
            <span class="hourly-row">
              <select v-model="hourlyMode" @change="onHourlyModeChange">
                <option value="">默认（实习15、兼职13、长期兼职/水果阿姨18；个人配置优先）</option>
                <option v-for="r in HOURLY_RATE_OPTIONS" :key="r" :value="String(r)">{{ r }} 元/时</option>
                <option value="custom">自定义…</option>
              </select>
              <input v-if="hourlyMode === 'custom'" v-model.number="form.hourlyRate" type="number"
                min="0" step="0.5" placeholder="元/时" class="hourly-custom" />
            </span>
          </label>
          <label>电话<input v-model="form.phone" type="text" /></label>
          <label>身份证号<input v-model="form.idCardNo" type="text" /></label>
          <label>入职日期<input v-model="form.hireDate" type="date" /></label>
          <label>生日（月.日）<input v-model="form.birthday" type="text" placeholder="如 4.14" /></label>
          <label>健康证办理日期<input v-model="form.healthCertIssueDate" type="date" /></label>
          <label>健康证到期日期<input v-model="form.healthCertExpireDate" type="date" /></label>
          <label>转正时间<input v-model="form.regularDate" type="date" /></label>
          <label>训练员转正<input v-model="form.trainerDate" type="date" /></label>
          <label>领班时间<input v-model="form.shiftLeaderDate" type="date" /></label>
          <label>店长转正<input v-model="form.managerDate" type="date" /></label>
          <label>合同签署<input v-model="form.contractSignText" type="text" /></label>
          <label>备注<input v-model="form.remark" type="text" /></label>
          <p v-if="dialogError" class="msg error">{{ dialogError }}</p>
        </div>
        <footer class="modal-foot">
          <button class="btn ghost" @click="dialogOpen = false">取消</button>
          <button class="btn primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </footer>
      </div>
    </div>

    <!-- 开号结果弹窗 -->
    <div v-if="accountDialogOpen" class="modal-mask" @click.self="accountDialogOpen = false">
      <div class="modal-box">
        <header class="modal-head">
          <h3>账号已创建（初始密码只显示这一次）</h3>
          <button class="icon-btn" @click="accountDialogOpen = false">✕</button>
        </header>
        <div class="modal-body">
          <table class="staff-table">
            <thead><tr><th>姓名</th><th>账号</th><th>初始密码</th></tr></thead>
            <tbody>
              <tr v-for="r in accountResults" :key="r.username">
                <td>{{ r.employeeName }}</td><td>{{ r.username }}</td><td>{{ r.initialPassword }}</td>
              </tr>
            </tbody>
          </table>
          <p class="msg muted">请复制后转交店长发给员工，首次登录后应尽快修改密码。</p>
        </div>
        <footer class="modal-foot">
          <button class="btn ghost" @click="copyAccounts">复制全部</button>
          <button class="btn primary" @click="accountDialogOpen = false">关闭</button>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
.staff-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.staff-toolbar .spacer {
  flex: 1;
}

.staff-toolbar label {
  font-size: 13px;
  color: #374151;
}

.staff-toolbar select,
.staff-toolbar .search-input {
  padding: 6px 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 13px;
  background: #fff;
}

.staff-toolbar button {
  padding: 6px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
}

.staff-toolbar button.primary {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.staff-toolbar button.danger {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}

.staff-toolbar button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.staff-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.staff-table th,
.staff-table td {
  padding: 8px 10px;
  border-bottom: 1px solid #f3f4f6;
  text-align: left;
  white-space: nowrap;
}

.staff-table th {
  color: #6b7280;
  font-weight: 600;
  background: #f9fafb;
}

/* 健康证标红：无证 / 已过期 / 30 天内到期 */
td.health-danger {
  color: #dc2626;
  font-weight: 600;
}

tr.health-danger-row td:first-child {
  border-left: 3px solid #dc2626;
}

/* 生日标黄：30 天内过生日（全职/长期兼职有 200 生日福利） */
td.birthday-soon {
  background: #fef9c3;
  color: #a16207;
  font-weight: 600;
}

tr.birthday-soon-row td {
  background: #fffbeb;
}

tr.birthday-soon-row td.birthday-soon {
  background: #fef9c3;
}

.staff-toolbar button.warning {
  background: #f59e0b;
  border-color: #f59e0b;
  color: #fff;
}

.link-btn {
  border: none;
  background: none;
  color: #2563eb;
  cursor: pointer;
  font-size: 13px;
  padding: 0 4px;
}

.link-btn.danger {
  color: #dc2626;
}

.import-report {
  margin: 8px 0;
  padding: 10px 12px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 8px;
  font-size: 13px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
}

.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: #374151;
}

.form-grid input,
.form-grid select {
  padding: 6px 10px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 13px;
}

.form-grid .msg {
  grid-column: 1 / -1;
}

.hourly-row {
  display: flex;
  gap: 6px;
}

.hourly-row select {
  flex: 1;
}

.hourly-custom {
  width: 90px;
}
</style>
