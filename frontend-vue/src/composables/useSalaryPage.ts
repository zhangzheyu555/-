import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { getEmployees, type EmployeeRecord } from '../api/employees'
import { getStores, type StoreInfo } from '../api/operations'
import { ApiError } from '../api/http'
import { getSalaryPage, type SalaryPageResponse } from '../api/finance'

const PAGE_SIZE = 20

/* ---- helpers (pure, no side-effects) ---- */
export function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export function money(v?: number) {
  return `¥${Number(v || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

export function statusLabel(s?: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿', SUBMITTED: '待审核', PENDING_REVIEW: '待审核', APPROVED: '已审核',
    REJECTED: '已驳回', PAID: '已发放', LOCKED: '已锁定',
  }
  return labels[s || 'DRAFT'] || '草稿'
}

export function statusClass(s?: string) {
  const map: Record<string, string> = {
    DRAFT: 'pending', SUBMITTED: 'warn', PENDING_REVIEW: 'warn', APPROVED: 'done',
    REJECTED: 'rejected', PAID: 'done', LOCKED: 'muted',
  }
  return map[s || 'DRAFT'] || 'pending'
}

export function isEditable(s?: string) {
  return !s || ['DRAFT', 'REJECTED'].includes(s)
}

const errorPatterns = [
  'handler dispatch failed', 'java.lang', 'noclassdeffounderror', 'org.springframework',
  'stacktrace', 'exception', 'request failed with status code', 'sql',
]
export function userError(e: unknown, fallback = '数据加载失败，请稍后重试。') {
  if (e instanceof ApiError) {
    const suffix = e.requestId ? `（编号：${e.requestId}）` : ''
    if (e.status === 401) return `登录已失效，请重新登录。${suffix}`
    if (e.status === 403) return `无权查看当前工资数据。${suffix}`
    if (e.code === 'DATABASE_MIGRATION_INCOMPLETE') return `系统升级尚未完成，请联系管理员。${suffix}`
    const msg = e.message.trim()
    const isTechnical = errorPatterns.some((p) => msg.toLowerCase().includes(p))
    if (!msg || isTechnical || !/[\u4e00-\u9fff]/.test(msg) || (e.status && e.status >= 500)) {
      return `${fallback}${suffix}`
    }
    return `${msg}${suffix}`
  }
  const msg = String(e instanceof Error ? e.message : e || '').trim()
  if (!msg || errorPatterns.some((p) => msg.toLowerCase().includes(p)) || !/[\u4e00-\u9fff]/.test(msg)) {
    return fallback
  }
  return msg
}

/* ---- composable ---- */
export function useSalaryPage() {
  const route = useRoute()
  const auth = useAuthStore()

  /* ---- reactive state ---- */
  const stores = ref<StoreInfo[]>([])
  const employees = ref<EmployeeRecord[]>([])
  const selectedMonth = ref(currentMonth())
  const selectedStoreId = ref('all')
  const statusFilter = ref('')
  const keyword = ref('')
  const page = ref(1)
  const loading = ref(false)
  const storesLoading = ref(false)
  const employeesLoading = ref(false)
  const error = ref('')
  const storesError = ref('')
  const employeesError = ref('')
  const successMessage = ref('')
  const initializing = ref(true)

  const pageData = ref<SalaryPageResponse | null>(null)

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
  const employeeCount = computed(() => employees.value.length)
  const hasValidMonth = computed(() => /^\d{4}-(0[1-9]|1[0-2])$/.test(selectedMonth.value))
  const isEmployeeEmpty = computed(() => !employeesLoading.value && !employeesError.value && employeeCount.value === 0)
  const isEmpty = computed(() =>
    !loading.value && !error.value && !employeesError.value && employeeCount.value > 0 && total.value === 0,
  )
  const showTable = computed(() => loading.value || total.value > 0)
  const canGenerate = computed(() =>
    canEdit.value
      && selectedStoreId.value !== 'all'
      && hasValidMonth.value
      && employeeCount.value > 0
      && !employeesError.value
      && !loading.value,
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

  /* ---- API calls ---- */
  async function loadStores() {
    storesLoading.value = true
    storesError.value = ''
    try {
      stores.value = await getStores()
    } catch (e) {
      storesError.value = userError(e, '门店数据加载失败。')
    } finally {
      storesLoading.value = false
    }
  }

  async function loadEmployees() {
    employeesLoading.value = true
    employeesError.value = ''
    try {
      employees.value = await getEmployees({
        storeId: selectedStoreId.value === 'all' ? undefined : selectedStoreId.value,
      })
    } catch (e) {
      employeesError.value = userError(e, '员工数据加载失败。')
    } finally {
      employeesLoading.value = false
    }
  }

  async function loadPage(p = page.value) {
    if (!hasValidMonth.value) {
      error.value = '请选择有效月份。'
      return
    }
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

  async function reloadScopeData(p = page.value) {
    await Promise.allSettled([loadEmployees(), loadPage(p)])
  }

  async function reloadAll() {
    await loadStores()
    applyRouteDefaults()
    await reloadScopeData(page.value)
  }

  function applyRouteDefaults() {
    const qStoreId = typeof route.query.storeId === 'string' ? route.query.storeId : ''
    const qMonth = typeof route.query.month === 'string' ? route.query.month : ''
    const ids = new Set(accessibleStores.value.map((s) => s.id))
    if (qStoreId && ids.has(qStoreId)) selectedStoreId.value = qStoreId
    else if (auth.role === 'STORE_MANAGER' && accessibleStores.value.length)
      selectedStoreId.value = accessibleStores.value[0].id
    if (/^\d{4}-(0[1-9]|1[0-2])$/.test(qMonth)) selectedMonth.value = qMonth
  }

  /* ---- watchers ---- */
  watch([selectedMonth, selectedStoreId], () => {
    if (initializing.value) return
    page.value = 1
    void reloadScopeData(1)
  })

  watch(
    () => [route.query.storeId, route.query.month],
    () => { applyRouteDefaults() },
  )

  return {
    // state
    stores, employees, selectedMonth, selectedStoreId, statusFilter, keyword, page,
    loading, storesLoading, employeesLoading, error, storesError, employeesError,
    successMessage, initializing, pageData,
    // computed
    canEdit, canReview, accessibleStores, storeMap, selectedStoreName,
    rows, total, totalPages, summary, employeeCount, hasValidMonth,
    isEmployeeEmpty, isEmpty, showTable, canGenerate,
    statusCounts, filteredRows,
    // methods
    loadStores, loadEmployees, loadPage, reloadScopeData, reloadAll, applyRouteDefaults,
    // constants
    PAGE_SIZE,
  }
}
