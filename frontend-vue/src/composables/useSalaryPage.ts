import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import { getStores, type StoreInfo } from '../api/operations'
import { ApiError } from '../api/http'
import { getSalaryEmployeePage, type SalaryPageResponse, type SalaryRecord } from '../api/finance'
import { useBusinessScope } from './useBusinessScope'

const PAGE_SIZE = 20
const SALARY_STATUSES = new Set([
  'PENDING_GENERATION', 'DRAFT', 'SUBMITTED', 'PENDING_REVIEW',
  'APPROVED', 'REJECTED', 'PAID', 'LOCKED',
])

/* ---- helpers (pure, no side-effects) ---- */
export function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export function money(v?: number) {
  const number = Number(v || 0)
  const integer = Number.isFinite(number) ? Math.trunc(number) : 0
  return `¥${integer.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

export function wholeNumber(v?: number) {
  const number = Number(v || 0)
  return String(Number.isFinite(number) ? Math.trunc(number) : 0)
}

export function isHourlyEmployee(employmentTypeValue?: string, positionValue?: string, attendanceValue?: string) {
  const employmentType = String(employmentTypeValue || '').trim().toUpperCase()
  if (['兼职', '长期兼职', '实习', 'PART_TIME', 'LONG_TERM_PART_TIME', 'INTERN'].includes(employmentType)) return true
  const position = String(positionValue || '')
  if (['兼职', '实习', '水果', '阿姨'].some((keyword) => position.includes(keyword))) return true
  return /(?:按小时|小时)/.test(String(attendanceValue || ''))
}

export function isHourlySalaryRecord(record?: SalaryRecord | null) {
  return Boolean(record && isHourlyEmployee(record.employmentType, record.position, record.attendance))
}

export function statusLabel(s?: string) {
  const labels: Record<string, string> = {
    PENDING_GENERATION: '待生成', DRAFT: '草稿', SUBMITTED: '待审核', PENDING_REVIEW: '待审核', APPROVED: '已审核',
    REJECTED: '已驳回', PAID: '已发放', LOCKED: '已锁定',
  }
  return labels[s || 'DRAFT'] || '草稿'
}

export function statusClass(s?: string) {
  const map: Record<string, string> = {
    PENDING_GENERATION: 'muted', DRAFT: 'pending', SUBMITTED: 'warn', PENDING_REVIEW: 'warn', APPROVED: 'done',
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
  const businessScope = useBusinessScope()

  /* ---- reactive state ---- */
  const stores = ref<StoreInfo[]>([])
  const selectedMonth = ref(currentMonth())
  const selectedStoreId = ref(businessScope.scopedStoreId('all') || 'all')
  const selectedBrandId = ref<number | undefined>(businessScope.isStoreManager.value
    ? businessScope.brandId.value ?? undefined
    : undefined)
  const statusFilter = ref('')
  const keyword = ref('')
  const page = ref(1)
  const loading = ref(false)
  const storesLoading = ref(false)
  const error = ref('')
  const storesError = ref('')
  const successMessage = ref('')
  const initializing = ref(true)

  const pageData = ref<SalaryPageResponse | null>(null)

  /* ---- computed ---- */
  const canEdit = computed(() => auth.hasPermission(PERMISSIONS.SALARY_EDIT))
  const canReview = computed(() => auth.hasPermission(PERMISSIONS.SALARY_REVIEW))
  const canPay = computed(() => auth.hasPermission(PERMISSIONS.SALARY_PAY))
  const canExport = computed(() => auth.hasPermission(PERMISSIONS.FINANCE_EXPORT))
  const isStoreManager = businessScope.isStoreManager
  const managerScopeLabel = businessScope.managerScopeLabel
  const scopeConfigurationError = businessScope.configurationError
  const salaryScope = computed(() => auth.dataScope('SALARY'))
  const isOwnStoreScope = computed(() => salaryScope.value?.mode === 'OWN_STORE')
  const effectiveStoreId = computed(() => businessScope.scopedStoreId(selectedStoreId.value))
  const effectiveBrandId = computed<number | undefined>(() => {
    const value = businessScope.scopedBrandId(selectedBrandId.value)
    return value ? Number(value) : undefined
  })
  const selectableStores = computed(() => stores.value.filter((store) => {
    const status = String(store.status || '').trim().toUpperCase()
    return !status || status === '营业中' || status === '正常' || status === 'ACTIVE'
  }))
  const isEffectiveStoreActive = computed(() => {
    const storeId = effectiveStoreId.value
    if (!storeId) return false
    if (storeId === 'all') return true
    return selectableStores.value.some((store) => store.id === storeId)
  })
  const accessibleStores = computed(() => {
    if (isStoreManager.value) {
      return selectableStores.value.filter((store) => store.id === businessScope.boundStoreId.value)
    }
    if (salaryScope.value?.mode === 'ALL') return selectableStores.value
    const allowedStoreIds = salaryScope.value?.storeIds || []
    if (!allowedStoreIds.length) return []
    return selectableStores.value.filter((store) => allowedStoreIds.includes(store.id))
  })
  const brandOptions = computed(() => Array.from(new Map(
    selectableStores.value.filter((store) => store.brandId !== undefined).map((store) => [store.brandId, { id: store.brandId as number, name: store.brandName || `品牌${store.brandId}` }]),
  ).values()))
  const filteredAccessibleStores = computed(() => selectedBrandId.value === undefined
    ? accessibleStores.value
    : accessibleStores.value.filter((store) => store.brandId === selectedBrandId.value))
  const storeMap = computed(() => new Map(stores.value.map((s) => [s.id, s])))
  const selectedStoreName = computed(() => {
    if (isStoreManager.value) {
      return businessScope.boundStoreName.value
        || storeMap.value.get(effectiveStoreId.value)?.name
        || '本店'
    }
    if (selectedStoreId.value === 'all') return '全部门店'
    return storeMap.value.get(selectedStoreId.value)?.name || selectedStoreId.value
  })

  const rows = computed(() => pageData.value?.content || pageData.value?.rows || [])
  const total = computed(() => pageData.value?.totalElements ?? pageData.value?.total ?? 0)
  const totalPages = computed(() => pageData.value?.totalPages || 1)
  const summary = computed(() => pageData.value?.summary)
  const employeeCount = computed(() => total.value)
  const employeesLoading = computed(() => loading.value)
  const employeesError = computed(() => error.value)
  const hasValidMonth = computed(() => /^\d{4}-(0[1-9]|1[0-2])$/.test(selectedMonth.value))
  const isEmployeeEmpty = computed(() => !employeesLoading.value && !employeesError.value && employeeCount.value === 0)
  const isEmpty = computed(() =>
    !loading.value && !error.value && employeeCount.value > 0 && total.value === 0,
  )
  const showTable = computed(() => loading.value || total.value > 0)
  const canGenerate = computed(() =>
    canEdit.value
      && Boolean(effectiveStoreId.value)
      && effectiveStoreId.value !== 'all'
      && isEffectiveStoreActive.value
      && hasValidMonth.value
      && employeeCount.value > 0
      && !loading.value,
  )

  const statusCounts = computed(() => {
    if (pageData.value?.statusCounts) return pageData.value.statusCounts
    const map: Record<string, number> = {}
    for (const r of rows.value) map[r.status || 'DRAFT'] = (map[r.status || 'DRAFT'] || 0) + 1
    return map
  })

  const filteredRows = computed(() => rows.value)

  /* ---- API calls ---- */
  let pageRequestController: AbortController | null = null
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

  async function loadPage(p = page.value) {
    if (scopeConfigurationError.value) {
      pageData.value = null
      error.value = scopeConfigurationError.value
      return
    }
    if (!hasValidMonth.value) {
      error.value = '请选择有效月份。'
      return
    }
    if (!isEffectiveStoreActive.value) {
      pageData.value = null
      error.value = '该门店已停用，不能继续查看、添加人员或生成工资。'
      return
    }
    pageRequestController?.abort()
    const controller = new AbortController()
    pageRequestController = controller
    loading.value = true
    error.value = ''
    try {
      const resp = await getSalaryEmployeePage({
        month: selectedMonth.value || undefined,
        storeId: effectiveStoreId.value === 'all' ? undefined : effectiveStoreId.value,
        brandId: effectiveBrandId.value,
        status: statusFilter.value || undefined,
        keyword: keyword.value.trim() || undefined,
        page: p,
        size: PAGE_SIZE,
      }, controller.signal)
      if (controller.signal.aborted) return
      pageData.value = resp
      page.value = p
    } catch (e) {
      if (e instanceof ApiError && e.code === 'REQUEST_CANCELLED') return
      pageData.value = null
      error.value = userError(e)
    } finally {
      if (pageRequestController === controller) {
        pageRequestController = null
        loading.value = false
      }
    }
  }

  async function reloadScopeData(p = page.value) {
    await loadPage(p)
  }

  async function reloadAll() {
    await loadStores()
    applyRouteDefaults()
    await reloadScopeData(page.value)
  }

  function applyRouteDefaults() {
    const qStoreId = typeof route.query.storeId === 'string' ? route.query.storeId : ''
    const qMonth = typeof route.query.month === 'string' ? route.query.month : ''
    const qStatus = typeof route.query.status === 'string' ? route.query.status.toUpperCase() : ''
    if (isStoreManager.value) {
      selectedStoreId.value = businessScope.boundStoreId.value
      selectedBrandId.value = businessScope.brandId.value ?? undefined
    } else {
      const ids = new Set(accessibleStores.value.map((s) => s.id))
      if (qStoreId && ids.has(qStoreId)) selectedStoreId.value = qStoreId
      else if (qStoreId && storeMap.value.has(qStoreId)) selectedStoreId.value = qStoreId
      else if (isOwnStoreScope.value && accessibleStores.value.length)
        selectedStoreId.value = accessibleStores.value[0].id
    }
    if (/^\d{4}-(0[1-9]|1[0-2])$/.test(qMonth)) selectedMonth.value = qMonth
    if (SALARY_STATUSES.has(qStatus)) statusFilter.value = qStatus
  }

  /* ---- watchers ---- */
  watch([selectedMonth, selectedBrandId, selectedStoreId, statusFilter], () => {
    if (initializing.value) return
    if (isStoreManager.value) {
      if (
        selectedStoreId.value !== businessScope.boundStoreId.value
        || selectedBrandId.value !== (businessScope.brandId.value ?? undefined)
      ) {
        selectedStoreId.value = businessScope.boundStoreId.value
        selectedBrandId.value = businessScope.brandId.value ?? undefined
        return
      }
      page.value = 1
      void loadPage(1)
      return
    }
    if (selectedStoreId.value !== 'all' && !filteredAccessibleStores.value.some((store) => store.id === selectedStoreId.value)) {
      const selectedStoreExists = storeMap.value.has(selectedStoreId.value)
      const selectedStoreIsInactive = selectedStoreExists
        && !selectableStores.value.some((store) => store.id === selectedStoreId.value)
      if (selectedStoreIsInactive) {
        page.value = 1
        void loadPage(1)
        return
      }
      selectedStoreId.value = isOwnStoreScope.value && filteredAccessibleStores.value.length
        ? filteredAccessibleStores.value[0].id
        : 'all'
      return
    }
    page.value = 1
    void loadPage(1)
  })

  let keywordTimer: ReturnType<typeof setTimeout> | undefined
  watch(keyword, () => {
    if (initializing.value) return
    if (keywordTimer) clearTimeout(keywordTimer)
    keywordTimer = setTimeout(() => {
      page.value = 1
      void loadPage(1)
    }, 250)
  })

  watch(
    () => [route.query.storeId, route.query.month, route.query.status],
    () => { applyRouteDefaults() },
  )

  return {
    // state
    stores, selectedMonth, selectedBrandId, selectedStoreId, statusFilter, keyword, page,
    loading, storesLoading, employeesLoading, error, storesError, employeesError,
    successMessage, initializing, pageData,
    // computed
    canEdit, canReview, canPay, canExport, isStoreManager, isOwnStoreScope,
    managerScopeLabel, scopeConfigurationError, effectiveStoreId, effectiveBrandId,
    accessibleStores, brandOptions, filteredAccessibleStores, storeMap, selectedStoreName,
    isEffectiveStoreActive,
    rows, total, totalPages, summary, employeeCount, hasValidMonth,
    isEmployeeEmpty, isEmpty, showTable, canGenerate,
    statusCounts, filteredRows,
    // methods
    loadStores, loadPage, reloadScopeData, reloadAll, applyRouteDefaults,
    // constants
    PAGE_SIZE,
  }
}
