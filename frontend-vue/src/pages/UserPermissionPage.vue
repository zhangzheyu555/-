<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { AlertTriangle, CheckCircle2, KeyRound, Pencil, Plus, RefreshCw, Shield, ShieldCheck, Store, X } from 'lucide-vue-next'
import { getStores, type StoreInfo } from '../api/operations'
import { getWarehouses, type WarehouseInfo } from '../api/warehouse'
import { ApiError } from '../api/http'
import PageHeader from '../components/common/PageHeader.vue'
import ModalFooter from '../components/ui/ModalFooter.vue'
import UiButton from '../components/ui/UiButton.vue'
import UnsavedChangesDialog from '../components/ui/UnsavedChangesDialog.vue'
import {
  createUser,
  getAuthorizationCatalog,
  getUserAuthorization,
  getUsers,
  resetUserPassword,
  updateUserAccessProfile,
  type AuthorizationCatalog,
  type DataScopeMode,
  type PermissionCatalogEntry,
  type PermissionEffect,
  type UserAccount,
  type UserAccessProfileUpdate,
  type UserAuthorization,
  type UserCreatePayload,
  type UserDataScopeAssignment,
  type UserProfilePayload,
} from '../api/users'
import { useAuthStore } from '../stores/auth'
import { PERMISSIONS } from '../permissions/permissions'
import { isBossRole, normalizeRoleCode } from '../permissions/roles'
import { listAvailableWorkspaces } from '../permissions/workspaces'

type AccountForm = UserCreatePayload
type OverrideChoice = PermissionEffect | ''

interface PermissionGroup {
  moduleCode: string
  permissions: PermissionCatalogEntry[]
}

const DATA_SCOPE_DOMAINS = ['STORE', 'FINANCE', 'SALARY', 'WAREHOUSE', 'INSPECTION', 'EXAM', 'PLATFORM'] as const
const DATA_SCOPE_MODE_ORDER: DataScopeMode[] = ['ALL', 'WAREHOUSE_LIST', 'STORE_LIST', 'OWN_STORE', 'CENTRAL_WAREHOUSE', 'SELF', 'NONE']
const MODULE_LABELS: Record<string, string> = {
  SYSTEM: '系统管理',
  OPERATIONS: '运营工作台',
  ASSISTANT: '经营助手',
  STORE: '门店组织',
  EMPLOYEE: '员工管理',
  FINANCE: '经营财务',
  EXPENSE: '报销管理',
  SALARY: '工资管理',
  WAREHOUSE: '仓库管理',
  INVENTORY: '盘存管理',
  INSPECTION: '巡店管理',
  EXAM: '培训考试',
  PLATFORM: '平台配置',
  ATTACHMENT: '附件管理',
  TODO: '待办处理',
}
const DOMAIN_LABELS: Record<string, string> = {
  STORE: '门店',
  FINANCE: '财务',
  SALARY: '工资',
  WAREHOUSE: '仓库',
  INSPECTION: '巡店',
  EXAM: '培训考试',
  PLATFORM: '平台',
}
const DOMAIN_DESCRIPTIONS: Record<string, string> = {
  STORE: '门店资料、组织和经营信息',
  FINANCE: '利润、成本与报销数据',
  SALARY: '员工工资与发放记录',
  WAREHOUSE: '仓库库存、仓间调拨和门店叫货单',
  INSPECTION: '巡店记录、整改和复核',
  EXAM: '课程、考试任务和学习记录',
  PLATFORM: '第三方平台配置与同步数据',
}
const DOMAIN_MODES: Record<string, DataScopeMode[]> = {
  STORE: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE'],
  FINANCE: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE'],
  SALARY: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE'],
  WAREHOUSE: ['ALL', 'WAREHOUSE_LIST', 'OWN_STORE', 'CENTRAL_WAREHOUSE', 'NONE'],
  INSPECTION: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE'],
  EXAM: ['ALL', 'STORE_LIST', 'OWN_STORE', 'SELF', 'NONE'],
  PLATFORM: ['ALL', 'STORE_LIST', 'OWN_STORE', 'NONE'],
}
const HIGH_RISK_PERMISSION_CODES = new Set([
  'salary.pay',
  'finance.export',
  'platform.manage',
  'system.user.manage',
  'system.audit.write',
  'warehouse.central.manage',
  'inventory.review',
])
const HIGH_RISK_HINTS: Record<string, string> = {
  'salary.pay': '可确认工资已发放，影响工资业务状态。',
  'finance.export': '可导出工资、利润等敏感经营数据。',
  'platform.manage': '可修改第三方平台授权和回调配置。',
  'system.user.manage': '可创建账号并调整他人角色与权限。',
  'system.audit.write': '可补写受控的操作审计记录。',
  'warehouse.central.manage': '可维护总仓采购、入库、调拨和退货。',
  'inventory.review': '可复核并确认盘存结果。',
}

const auth = useAuthStore()
const users = ref<UserAccount[]>([])
const stores = ref<StoreInfo[]>([])
const warehouses = ref<WarehouseInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const successMessage = ref('')
const editorOpen = ref(false)
const editingUser = ref<UserAccount | null>(null)
const editingAuthorization = ref<UserAuthorization | null>(null)
const editingAuthorizationLoading = ref(false)
const resetTarget = ref<UserAccount | null>(null)
const resetting = ref(false)
const resetError = ref('')
const resetForm = reactive({ currentPassword: '', password: '', confirmPassword: '' })
const form = reactive<AccountForm>(emptyForm())
const authorizationCatalog = ref<AuthorizationCatalog | null>(null)
const selectedAuthorizationUser = ref<UserAccount | null>(null)
const userAuthorization = ref<UserAuthorization | null>(null)
const authorizationLoading = ref(false)
const authorizationSaving = ref(false)
const authorizationError = ref('')
const authorizationSuccess = ref('')
const draftScopes = reactive<Record<string, UserDataScopeAssignment>>({})
const draftOverrides = reactive<Record<string, OverrideChoice>>({})
const initialAuthorizationSignature = ref('')
const accountFormSnapshot = ref('')
const discardDialogOpen = ref(false)
const discardDialogMessage = ref('')
let authorizationRequestSequence = 0
let pendingDiscardAction: (() => void) | null = null

const roles = [
  { value: 'BOSS', label: '老板（系统管理员）' },
  { value: 'FINANCE', label: '财务' },
  { value: 'STORE_MANAGER', label: '店长' },
  { value: 'WAREHOUSE', label: '仓库管理员' },
  { value: 'OPERATIONS', label: '运营' },
  { value: 'EMPLOYEE', label: '学员（兼容身份）' },
]

const canManage = computed(() => isBossRole(auth.role))
const bossAccountForm = computed(() => isBossRole(form.role))
const storeManagerAccountForm = computed(() => normalizeRoleCode(form.role) === 'STORE_MANAGER')
const activeBossCount = computed(() => users.value.filter((user) => isBossRole(user.role) && user.enabled).length)
const protectedBoss = computed(() => Boolean(
  editingUser.value
  && isBossRole(editingUser.value.role)
  && editingUser.value.enabled
  && activeBossCount.value <= 1,
))
const editingSelf = computed(() => editingUser.value?.id === auth.user?.id)
const enabledCount = computed(() => users.value.filter((user) => user.enabled).length)
const managerCount = computed(() => users.value.filter((user) => user.role === 'STORE_MANAGER').length)
const managerAccounts = computed(() => users.value.filter((user) => user.role === 'STORE_MANAGER'))
const configuredManagerStoreIds = computed(() => new Set(
  managerAccounts.value.flatMap((user) => [user.storeId, ...(user.storeScope || [])]).filter(Boolean),
))
const pendingManagerStores = computed(() => stores.value.filter((store) => !configuredManagerStoreIds.value.has(store.id)))
const roleRows = computed(() => {
  const groups = new Map<string, number>()
  for (const user of users.value) {
    const label = user.roleLabel || user.role
    groups.set(label, (groups.get(label) || 0) + 1)
  }
  return Array.from(groups.entries()).map(([label, count]) => ({ label, count }))
})
const selectedAuthorizationIsBoss = computed(() => isBossRole(selectedAuthorizationUser.value?.role))
const dataScopeRows = computed<UserDataScopeAssignment[]>(() => DATA_SCOPE_DOMAINS
  .map((domainCode) => draftScopes[domainCode])
  .filter((scope): scope is UserDataScopeAssignment => Boolean(scope)))
const catalogPermissions = computed(() => (authorizationCatalog.value?.permissions || [])
  .filter((permission) => permission.enabled)
  .sort((left, right) => left.sortOrder - right.sortOrder || left.permissionCode.localeCompare(right.permissionCode)))
const permissionGroups = computed<PermissionGroup[]>(() => groupPermissions(catalogPermissions.value))
const roleTemplatePermissionSet = computed(() => new Set(userAuthorization.value?.roleTemplatePermissions || []))
const roleTemplateGroups = computed(() => permissionGroups.value
  .map((group) => ({
    ...group,
    permissions: group.permissions.filter((permission) => (
      selectedAuthorizationIsBoss.value || roleTemplatePermissionSet.value.has(permission.permissionCode)
    )),
  }))
  .filter((group) => group.permissions.length))
const draftEffectivePermissions = computed(() => {
  if (!userAuthorization.value) return []
  if (selectedAuthorizationIsBoss.value) return catalogPermissions.value.map((permission) => permission.permissionCode)
  const effective = new Set(userAuthorization.value.roleTemplatePermissions)
  for (const [permissionCode, effect] of Object.entries(draftOverrides)) {
    if (effect === 'ALLOW') effective.add(permissionCode)
    if (effect === 'DENY') effective.delete(permissionCode)
  }
  return catalogPermissions.value
    .map((permission) => permission.permissionCode)
    .filter((permissionCode) => effective.has(permissionCode))
})
const draftEffectivePermissionSet = computed(() => new Set(draftEffectivePermissions.value))
const effectivePermissionGroups = computed(() => permissionGroups.value
  .map((group) => ({
    ...group,
    permissions: group.permissions.filter((permission) => draftEffectivePermissionSet.value.has(permission.permissionCode)),
  }))
  .filter((group) => group.permissions.length))
const effectiveHighRiskCount = computed(() => catalogPermissions.value.filter((permission) => (
  draftEffectivePermissionSet.value.has(permission.permissionCode) && isHighRiskPermission(permission)
)).length)
const selectedManagerWorkspaceReady = computed(() => {
  if (normalizeRoleCode(selectedAuthorizationUser.value?.role) !== 'STORE_MANAGER') return true
  const storeScope = draftScopes.STORE
  return Boolean(
    selectedAuthorizationUser.value?.storeId
    && draftEffectivePermissionSet.value.has(PERMISSIONS.STORE_READ)
    && storeScope?.mode === 'OWN_STORE',
  )
})
const authorizationDirty = computed(() => {
  if (!userAuthorization.value || selectedAuthorizationIsBoss.value) return false
  return initialAuthorizationSignature.value !== draftAuthorizationSignature()
})
const accountDirty = computed(() => editorOpen.value && accountFormSnapshot.value !== accountFormSignature())
const passwordDirty = computed(() => Boolean(
  resetTarget.value
  && (resetForm.currentPassword || resetForm.password || resetForm.confirmPassword),
))

function emptyForm(): AccountForm {
  return {
    username: '',
    displayName: '',
    role: 'STORE_MANAGER',
    storeId: '',
    storeScope: [],
    enabled: true,
    password: '',
  }
}

function accountFormSignature() {
  return JSON.stringify({
    username: form.username,
    displayName: form.displayName,
    role: normalizeRoleCode(form.role),
    storeId: form.storeId,
    storeScope: [...form.storeScope].sort(),
    enabled: form.enabled,
    password: form.password,
  })
}

function captureAccountFormSnapshot() {
  accountFormSnapshot.value = accountFormSignature()
}

function requestDiscardConfirmation(message: string, action: () => void) {
  if (discardDialogOpen.value) return
  discardDialogMessage.value = message
  pendingDiscardAction = action
  discardDialogOpen.value = true
}

function keepEditing() {
  pendingDiscardAction = null
  discardDialogOpen.value = false
}

function discardChanges() {
  const action = pendingDiscardAction
  pendingDiscardAction = null
  discardDialogOpen.value = false
  action?.()
}

function handleDialogEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || discardDialogOpen.value) return
  if (resetTarget.value) {
    event.preventDefault()
    closePasswordReset()
    return
  }
  if (editorOpen.value) {
    event.preventDefault()
    closeEditor()
  }
}

function groupPermissions(permissions: PermissionCatalogEntry[]) {
  const grouped = new Map<string, PermissionCatalogEntry[]>()
  for (const permission of permissions) {
    const rows = grouped.get(permission.moduleCode) || []
    rows.push(permission)
    grouped.set(permission.moduleCode, rows)
  }
  return Array.from(grouped.entries()).map(([moduleCode, rows]) => ({ moduleCode, permissions: rows }))
}

function moduleLabel(moduleCode: string) {
  return MODULE_LABELS[moduleCode] || moduleCode
}

function domainLabel(domainCode: string) {
  return DOMAIN_LABELS[domainCode] || domainCode
}

function domainDescription(domainCode: string) {
  return DOMAIN_DESCRIPTIONS[domainCode] || '该业务域内的数据'
}

function isHighRiskPermission(permission: PermissionCatalogEntry) {
  return permission.riskLevel === 'HIGH' || HIGH_RISK_PERMISSION_CODES.has(permission.permissionCode)
}

function highRiskHint(permission: PermissionCatalogEntry) {
  return HIGH_RISK_HINTS[permission.permissionCode] || permission.description || '该权限可执行敏感业务操作，请确认确有需要。'
}

function scopeModeOptions(domainCode: string) {
  const role = normalizeRoleCode(selectedAuthorizationUser.value?.role)
  let modes = DOMAIN_MODES[domainCode] || DATA_SCOPE_MODE_ORDER
  if (role === 'BOSS') modes = ['ALL']
  if (role === 'STORE_MANAGER') modes = ['OWN_STORE', 'NONE']
  if (role === 'EMPLOYEE') modes = domainCode === 'EXAM' ? ['SELF', 'NONE'] : ['NONE']
  const supported = new Set(authorizationCatalog.value?.dataScopeModes || DATA_SCOPE_MODE_ORDER)
  return modes.filter((mode) => supported.has(mode))
}

function scopeModeLabel(mode: DataScopeMode, domainCode: string) {
  if (mode === 'ALL') {
    const labels: Record<string, string> = {
      STORE: '全部门店',
      FINANCE: '全部门店财务',
      SALARY: '全部员工工资',
      WAREHOUSE: '全部仓库与门店库存',
      INSPECTION: '全部巡店数据',
      EXAM: '全部培训考试',
      PLATFORM: '全部平台数据',
    }
    return labels[domainCode] || '全部数据'
  }
  if (mode === 'WAREHOUSE_LIST') return '指定仓库'
  if (mode === 'STORE_LIST') return '指定门店'
  if (mode === 'OWN_STORE') return '绑定门店（本店）'
  if (mode === 'CENTRAL_WAREHOUSE') return '公司总仓库'
  if (mode === 'SELF') return '本人任务'
  return '无权限'
}

function clearAuthorizationDraft() {
  for (const key of Object.keys(draftScopes)) delete draftScopes[key]
  for (const key of Object.keys(draftOverrides)) delete draftOverrides[key]
  initialAuthorizationSignature.value = ''
}

function compatibilityScopeFallback(user: UserAccount) {
  const fallback = new Map<string, UserDataScopeAssignment>()
  const assign = (domainCode: string, mode: DataScopeMode, storeIds: string[] = [], warehouseIds: string[] = []) => {
    fallback.set(domainCode, { domainCode, mode, storeIds: [...storeIds], warehouseIds: [...warehouseIds] })
  }
  const role = normalizeRoleCode(user.role)
  if (role === 'EMPLOYEE') {
    assign('EXAM', 'SELF')
    return fallback
  }
  if (role === 'WAREHOUSE') {
    const centralId = warehouses.value.find((warehouse) => warehouse.type === 'CENTRAL')?.id
    assign('WAREHOUSE', centralId ? 'WAREHOUSE_LIST' : 'CENTRAL_WAREHOUSE', [], centralId ? [String(centralId)] : [])
    return fallback
  }
  if (role === 'STORE_MANAGER') {
    for (const domainCode of ['STORE', 'FINANCE', 'SALARY', 'WAREHOUSE', 'INSPECTION', 'EXAM']) {
      assign(domainCode, user.storeId ? 'OWN_STORE' : 'NONE')
    }
    return fallback
  }
  const storeIds = [...new Set((user.storeScope || []).filter((storeId) => storeId && storeId !== 'all'))]
  if (!storeIds.length && user.storeId) storeIds.push(user.storeId)
  if (!storeIds.length) return fallback
  const domains = role === 'FINANCE'
    ? ['STORE', 'FINANCE', 'SALARY', 'WAREHOUSE']
    : role === 'OPERATIONS'
      ? ['STORE', 'WAREHOUSE', 'INSPECTION', 'EXAM', 'PLATFORM']
      : []
  for (const domainCode of domains) assign(domainCode, 'STORE_LIST', storeIds)
  return fallback
}

function applyAuthorizationDraft(detail: UserAuthorization) {
  clearAuthorizationDraft()
  const scopeByDomain = new Map(detail.dataScopes.map((scope) => [scope.domainCode, scope]))
  const fallbackByDomain = detail.dataScopes.length || !selectedAuthorizationUser.value
    ? new Map<string, UserDataScopeAssignment>()
    : compatibilityScopeFallback(selectedAuthorizationUser.value)
  for (const domainCode of DATA_SCOPE_DOMAINS) {
    const source = scopeByDomain.get(domainCode) || fallbackByDomain.get(domainCode)
    draftScopes[domainCode] = {
      domainCode,
      mode: selectedAuthorizationIsBoss.value ? 'ALL' : source?.mode || 'NONE',
      storeIds: [...(source?.storeIds || [])],
      warehouseIds: [...(source?.warehouseIds || [])],
    }
  }
  for (const permission of catalogPermissions.value) draftOverrides[permission.permissionCode] = ''
  for (const override of detail.overrides) draftOverrides[override.permissionCode] = override.effect
  initialAuthorizationSignature.value = draftAuthorizationSignature()
}

function normalizedScopeSignature(scopes: UserDataScopeAssignment[]) {
  const byDomain = new Map(scopes.map((scope) => [scope.domainCode, scope]))
  return DATA_SCOPE_DOMAINS.map((domainCode) => {
    const scope = byDomain.get(domainCode)
    return {
      domainCode,
      mode: scope?.mode || 'NONE',
      storeIds: [...new Set(scope?.storeIds || [])].sort(),
      warehouseIds: [...new Set(scope?.warehouseIds || [])].sort(),
    }
  })
}

function draftAuthorizationSignature() {
  const overrides = Object.entries(draftOverrides)
    .filter((entry): entry is [string, PermissionEffect] => entry[1] === 'ALLOW' || entry[1] === 'DENY')
    .map(([permissionCode, effect]) => ({ permissionCode, effect }))
    .sort((left, right) => left.permissionCode.localeCompare(right.permissionCode))
  return JSON.stringify({
    overrides,
    dataScopes: normalizedScopeSignature(DATA_SCOPE_DOMAINS.map((domainCode) => draftScopes[domainCode] || {
      domainCode,
      mode: 'NONE' as DataScopeMode,
      storeIds: [],
      warehouseIds: [],
    })),
  })
}

function accessProfileScopes(
  detail: UserAuthorization,
  role: string,
  storeId: string | null,
): UserDataScopeAssignment[] {
  const normalizedRole = normalizeRoleCode(role)
  const roleChanged = normalizeRoleCode(detail.role) !== normalizedRole
  const existing = new Map(detail.dataScopes.map((scope) => [scope.domainCode, scope]))
  return DATA_SCOPE_DOMAINS.map((domainCode) => {
    const current = existing.get(domainCode) || { domainCode, mode: 'NONE' as DataScopeMode, storeIds: [], warehouseIds: [] }
    if (!roleChanged) return { ...current, storeIds: [...current.storeIds], warehouseIds: [...(current.warehouseIds || [])] }
    if (normalizedRole === 'BOSS') return { domainCode, mode: 'ALL', storeIds: [], warehouseIds: [] }
    if (normalizedRole === 'EMPLOYEE') {
      return { domainCode, mode: domainCode === 'EXAM' ? 'SELF' : 'NONE', storeIds: [], warehouseIds: [] }
    }
    if (normalizedRole === 'STORE_MANAGER') {
      const keepsDomain = domainCode !== 'PLATFORM' && current.mode !== 'NONE'
      return { domainCode, mode: keepsDomain && storeId ? 'OWN_STORE' : 'NONE', storeIds: [], warehouseIds: [] }
    }
    if (normalizedRole === 'WAREHOUSE') {
      const centralId = warehouses.value.find((warehouse) => warehouse.type === 'CENTRAL')?.id
      return {
        domainCode,
        mode: domainCode === 'WAREHOUSE' && centralId ? 'WAREHOUSE_LIST' : 'NONE',
        storeIds: [],
        warehouseIds: domainCode === 'WAREHOUSE' && centralId ? [String(centralId)] : [],
      }
    }
    return { ...current, storeIds: [...current.storeIds], warehouseIds: [...(current.warehouseIds || [])] }
  })
}

function accessProfilePayload(
  profile: UserProfilePayload,
  detail: UserAuthorization,
  scopes = accessProfileScopes(detail, profile.role, profile.storeId || null),
  overrides = detail.overrides,
): UserAccessProfileUpdate {
  return {
    ...profile,
    storeId: profile.storeId || null,
    overrides: overrides.map((override) => ({ ...override })),
    dataScopes: scopes.map((scope) => ({ ...scope, storeIds: [...scope.storeIds], warehouseIds: [...(scope.warehouseIds || [])] })),
  }
}

function previewAvailableWorkspaces(
  role: string,
  permissions: readonly string[],
  scopes: readonly UserDataScopeAssignment[],
  storeId?: string | null,
) {
  const normalizedRole = normalizeRoleCode(role)
  const permissionSet = new Set(permissions.map((permission) => permission.trim().toLowerCase()))
  const available = listAvailableWorkspaces({
    role: normalizedRole,
    defaultWorkspace: '',
    hasPermission: (permission) => isBossRole(normalizedRole) || permissionSet.has(permission.toLowerCase()),
  })
  if (normalizedRole !== 'STORE_MANAGER') return available
  const storeScope = scopes.find((scope) => scope.domainCode === 'STORE')
  return storeId && storeScope?.mode === 'OWN_STORE' ? available : []
}

async function refresh() {
  loading.value = true
  error.value = ''
  const selectedUserId = selectedAuthorizationUser.value?.id
  try {
    const [userRows, storeRows, warehouseRows] = await Promise.all([getUsers(), getStores(), getWarehouses()])
    users.value = userRows
    stores.value = storeRows
    warehouses.value = warehouseRows
    if (canManage.value) authorizationCatalog.value = await getAuthorizationCatalog()
    if (selectedUserId) {
      const refreshedSelection = userRows.find((user) => user.id === selectedUserId)
      if (refreshedSelection) await openAuthorization(refreshedSelection, false)
      else forceCloseAuthorization()
    }
  } catch (loadError) {
    error.value = displayError(loadError, '用户权限加载失败，请刷新后重试。')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingUser.value = null
  editingAuthorization.value = null
  editingAuthorizationLoading.value = false
  Object.assign(form, emptyForm())
  captureAccountFormSnapshot()
  editorOpen.value = true
}

async function openEdit(user: UserAccount) {
  editingUser.value = user
  editingAuthorization.value = null
  Object.assign(form, {
    username: user.username,
    displayName: user.displayName || '',
    role: normalizeRoleCode(user.role),
    storeId: user.storeId || '',
    storeScope: (user.storeScope || []).filter((value) => value !== 'all'),
    enabled: user.enabled,
    password: '',
  })
  captureAccountFormSnapshot()
  editorOpen.value = true
  editingAuthorizationLoading.value = true
  try {
    editingAuthorization.value = await getUserAuthorization(user.id)
  } catch (reason) {
    error.value = displayError(reason, '账号授权加载失败，暂时不能编辑该账号。')
    forceCloseEditor()
  } finally {
    editingAuthorizationLoading.value = false
  }
}

function requestRefresh() {
  if (loading.value) return
  if (authorizationDirty.value) {
    requestDiscardConfirmation(
      '刷新后，当前尚未保存的授权调整将不会保留。',
      () => { void refresh() },
    )
    return
  }
  void refresh()
}

function closeEditor() {
  if (saving.value) return
  if (accountDirty.value) {
    requestDiscardConfirmation('关闭账号编辑后，本次填写的账号资料将不会保留。', forceCloseEditor)
    return
  }
  forceCloseEditor()
}

function forceCloseEditor() {
  editorOpen.value = false
  editingAuthorization.value = null
  editingAuthorizationLoading.value = false
  accountFormSnapshot.value = ''
}

async function save() {
  error.value = ''
  successMessage.value = ''
  if (!form.displayName.trim()) {
    error.value = '请填写姓名或显示名称。'
    return
  }
  if (!editingUser.value && !/^[a-z0-9_.-]{3,40}$/.test(form.username.trim().toLowerCase())) {
    error.value = '登录账号为 3 至 40 位小写字母、数字、点、下划线或短横线。'
    return
  }
  if (!editingUser.value && form.password.length < 8) {
    error.value = '初始密码至少需要 8 位。'
    return
  }
  if (storeManagerAccountForm.value && !form.storeId) {
    error.value = '店长账号必须绑定且只能绑定一家门店。'
    return
  }

  const role = normalizeRoleCode(form.role)
  const scope = isBossRole(role)
    ? []
    : storeManagerAccountForm.value
      ? (form.storeId ? [form.storeId] : [])
      : Array.from(new Set([...form.storeScope, form.storeId].filter((value): value is string => Boolean(value))))
  const profile: UserProfilePayload = {
    displayName: form.displayName.trim(),
    role,
    storeId: isBossRole(role) ? '' : form.storeId || scope[0] || '',
    storeScope: scope,
    enabled: form.enabled,
  }
  if (editingUser.value && !editingAuthorization.value) {
    error.value = editingAuthorizationLoading.value
      ? '正在读取账号权限，请稍后再保存。'
      : '账号权限尚未加载，不能保存。请关闭后重新编辑。'
    return
  }
  if (editingUser.value && !editingUser.value.enabled && profile.enabled && editingAuthorization.value) {
    const roleChanged = normalizeRoleCode(editingAuthorization.value.role) !== role
    const previewScopes = accessProfileScopes(editingAuthorization.value, role, profile.storeId || null)
    const availableWorkspaces = roleChanged
      ? []
      : previewAvailableWorkspaces(role, editingAuthorization.value.effectivePermissions, previewScopes, profile.storeId)
    if (!availableWorkspaces.length) {
      error.value = roleChanged
        ? '更换角色并启用账号前，请先保持停用状态保存，再配置该角色的可用工作台。'
        : role === 'STORE_MANAGER'
          ? '店长工作台未授权：请先确认角色模板包含“门店查看”，并将门店数据范围设为绑定门店。'
          : '该账号没有任何可用工作台，暂时不能启用。请先配置角色权限和数据范围。'
      return
    }
  }

  saving.value = true
  try {
    if (editingUser.value) {
      await updateUserAccessProfile(
        editingUser.value.id,
        accessProfilePayload(profile, editingAuthorization.value!),
      )
      successMessage.value = '权限已更新，该账号需要重新登录。'
    } else {
      await createUser({
        ...profile,
        username: form.username.trim().toLowerCase(),
        password: form.password,
      })
      successMessage.value = '账号已创建。'
    }
    forceCloseEditor()
    await refresh()
  } catch (saveError) {
    error.value = displayError(saveError, '账号保存失败，请稍后重试。')
  } finally {
    saving.value = false
  }
}

function canResetPassword(user: UserAccount) {
  return !isBossRole(user.role) || user.id === auth.user?.id
}

function openPasswordReset(user: UserAccount) {
  if (!canResetPassword(user)) return
  resetTarget.value = user
  resetError.value = ''
  Object.assign(resetForm, { currentPassword: '', password: '', confirmPassword: '' })
}

function closePasswordReset() {
  if (resetting.value) return
  if (passwordDirty.value) {
    requestDiscardConfirmation('关闭密码编辑后，本次输入的密码将不会保留。', forceClosePasswordReset)
    return
  }
  forceClosePasswordReset()
}

function forceClosePasswordReset() {
  resetTarget.value = null
  resetError.value = ''
  Object.assign(resetForm, { currentPassword: '', password: '', confirmPassword: '' })
}

async function submitPasswordReset() {
  if (!resetTarget.value || resetting.value) return
  resetError.value = ''
  if (resetForm.password.length < 8 || resetForm.password.length > 128) {
    resetError.value = '新密码长度必须为 8 至 128 位。'
    return
  }
  if (resetForm.password !== resetForm.confirmPassword) {
    resetError.value = '两次输入的新密码不一致。'
    return
  }
  if (isBossRole(resetTarget.value.role) && !resetForm.currentPassword) {
    resetError.value = '修改老板密码前需要验证当前密码。'
    return
  }
  resetting.value = true
  try {
    await resetUserPassword(
      resetTarget.value.id,
      resetForm.password,
      isBossRole(resetTarget.value.role) ? resetForm.currentPassword : undefined,
    )
    successMessage.value = isBossRole(resetTarget.value.role) ? '密码已修改，请重新登录。' : '密码已安全重置，旧登录已失效。'
    forceClosePasswordReset()
  } catch (reason) {
    const message = displayError(reason, '密码重置失败，请稍后重试。')
    if (resetTarget.value) {
      // Keep the dialog open so the user can correct the current password.
      resetForm.currentPassword = ''
    }
    error.value = ''
    resetError.value = message
  } finally {
    resetting.value = false
  }
}

async function openAuthorization(user: UserAccount, clearFeedback = true) {
  const requestSequence = ++authorizationRequestSequence
  selectedAuthorizationUser.value = user
  userAuthorization.value = null
  clearAuthorizationDraft()
  authorizationError.value = ''
  if (clearFeedback) authorizationSuccess.value = ''
  authorizationLoading.value = true
  try {
    if (!authorizationCatalog.value) authorizationCatalog.value = await getAuthorizationCatalog()
    const detail = await getUserAuthorization(user.id)
    if (requestSequence !== authorizationRequestSequence) return
    userAuthorization.value = detail
    applyAuthorizationDraft(detail)
  } catch (reason) {
    if (requestSequence === authorizationRequestSequence) {
      authorizationError.value = displayError(reason, '账号授权加载失败，请稍后重试。')
    }
  } finally {
    if (requestSequence === authorizationRequestSequence) authorizationLoading.value = false
  }
}

function requestOpenAuthorization(user: UserAccount) {
  if (selectedAuthorizationUser.value?.id === user.id) return
  if (authorizationDirty.value) {
    requestDiscardConfirmation(
      '切换账号后，当前尚未保存的授权调整将不会保留。',
      () => { void openAuthorization(user) },
    )
    return
  }
  void openAuthorization(user)
}

function closeAuthorization() {
  if (authorizationSaving.value) return
  if (authorizationDirty.value) {
    requestDiscardConfirmation('关闭账号授权后，本次尚未保存的授权调整将不会保留。', forceCloseAuthorization)
    return
  }
  forceCloseAuthorization()
}

function forceCloseAuthorization() {
  authorizationRequestSequence += 1
  selectedAuthorizationUser.value = null
  userAuthorization.value = null
  authorizationLoading.value = false
  authorizationError.value = ''
  authorizationSuccess.value = ''
  clearAuthorizationDraft()
}

function onScopeModeChanged(domainCode: string) {
  const scope = draftScopes[domainCode]
  if (scope) {
    if (scope.mode !== 'STORE_LIST') scope.storeIds = []
    if (scope.mode !== 'WAREHOUSE_LIST') scope.warehouseIds = []
  }
  authorizationSuccess.value = ''
}

function onOverrideChanged() {
  authorizationSuccess.value = ''
}

function learnerAllowDisabled(permissionCode: string) {
  return normalizeRoleCode(selectedAuthorizationUser.value?.role) === 'EMPLOYEE' && permissionCode !== 'exam.learn'
}

async function saveAuthorization() {
  const target = selectedAuthorizationUser.value
  if (!target || !userAuthorization.value || authorizationSaving.value || selectedAuthorizationIsBoss.value) return
  authorizationError.value = ''
  authorizationSuccess.value = ''
  const dataScopes = DATA_SCOPE_DOMAINS.map((domainCode) => {
    const scope = draftScopes[domainCode] || { domainCode, mode: 'NONE' as DataScopeMode, storeIds: [], warehouseIds: [] }
    return {
      domainCode,
      mode: scope.mode,
      storeIds: scope.mode === 'STORE_LIST' ? [...new Set(scope.storeIds)].sort() : [],
      warehouseIds: scope.mode === 'WAREHOUSE_LIST' ? [...new Set(scope.warehouseIds || [])].sort() : [],
    }
  })
  const missingStoreScope = dataScopes.find((scope) => scope.mode === 'STORE_LIST' && !scope.storeIds.length)
  if (missingStoreScope) {
    authorizationError.value = `请为“${domainLabel(missingStoreScope.domainCode)}”至少选择一家门店。`
    return
  }
  const missingWarehouseScope = dataScopes.find((scope) => scope.mode === 'WAREHOUSE_LIST' && !scope.warehouseIds.length)
  if (missingWarehouseScope) {
    authorizationError.value = '请为“仓库”至少选择一个可访问仓库。'
    return
  }
  const invalidOwnStore = dataScopes.find((scope) => scope.mode === 'OWN_STORE' && !target.storeId)
  if (invalidOwnStore) {
    authorizationError.value = '该账号没有绑定门店，不能使用“绑定门店（本店）”范围。请先编辑基础账号。'
    return
  }
  const overrides = Object.entries(draftOverrides)
    .filter((entry): entry is [string, PermissionEffect] => entry[1] === 'ALLOW' || entry[1] === 'DENY')
    .map(([permissionCode, effect]) => ({ permissionCode, effect }))

  authorizationSaving.value = true
  try {
    const profile: UserProfilePayload = {
      displayName: target.displayName,
      role: normalizeRoleCode(target.role),
      storeId: target.storeId || '',
      storeScope: [...(target.storeScope || [])],
      enabled: target.enabled,
    }
    const updated = await updateUserAccessProfile(
      target.id,
      accessProfilePayload(profile, userAuthorization.value, dataScopes, overrides),
    )
    const rowIndex = users.value.findIndex((user) => user.id === target.id)
    if (rowIndex >= 0) users.value[rowIndex] = updated.user
    selectedAuthorizationUser.value = updated.user
    userAuthorization.value = updated.authorization
    applyAuthorizationDraft(updated.authorization)
    authorizationSuccess.value = `权限已更新，该账号需要重新登录。权限版本 v${updated.authorization.permissionVersion}。`
  } catch (reason) {
    authorizationError.value = displayError(reason, '账号授权保存失败，请稍后重试。')
  } finally {
    authorizationSaving.value = false
  }
}

function storeName(storeId?: string) {
  if (!storeId) return '未绑定门店'
  return stores.value.find((store) => store.id === storeId)?.name || storeId
}

function scopeText(user: UserAccount) {
  if (!user.storeScope?.length) return '未配置'
  if (user.storeScope.includes('all')) return '全部门店'
  return user.storeScope.map((storeId) => storeName(storeId)).join('、')
}

const WORKSPACE_LABELS: Record<string, string> = {
  '/boss': '老板工作台',
  '/finance': '财务工作台',
  '/warehouse': '仓库中心',
  '/store': '门店工作台',
  '/operations': '运营工作台',
  '/learn/exams': '学习考试',
}

function workspaceText(user: UserAccount) {
  const workspaces = user.availableWorkspaces || []
  if (!workspaces.length) return '无可用工作台'
  return workspaces
    .map((workspace) => `${WORKSPACE_LABELS[workspace] || workspace}${workspace === user.defaultWorkspace ? '（默认）' : ''}`)
    .join('、')
}

function effectivePermissionText(user: UserAccount) {
  const status = String(user.effectivePermissionStatus || '').toUpperCase()
  if (normalizeRoleCode(user.role) === 'STORE_MANAGER' && !(user.availableWorkspaces || []).includes('/store')) {
    return '店长工作台未授权'
  }
  if (status === 'DISABLED' || !user.enabled) return '账号已停用'
  if (status === 'READY') return '权限可用'
  return '无可用工作台'
}

function effectivePermissionTone(user: UserAccount) {
  const status = String(user.effectivePermissionStatus || '').toUpperCase()
  if (status === 'READY' && user.enabled) return 'ok'
  if (status === 'DISABLED' || !user.enabled) return 'info'
  return 'bad'
}

function effectivePermissionDetail(user: UserAccount) {
  if (user.effectivePermissionMessage) return user.effectivePermissionMessage
  if (normalizeRoleCode(user.role) === 'STORE_MANAGER' && !(user.availableWorkspaces || []).includes('/store')) {
    return '最终权限缺少门店查看，或门店数据范围不可用。'
  }
  return user.enabled ? '请配置至少一个角色可用工作台。' : '启用前需确认至少有一个可用工作台。'
}

function roleTone(role: string) {
  if (isBossRole(role)) return 'bad'
  if (role === 'STORE_MANAGER') return 'ok'
  if (role === 'WAREHOUSE') return 'warn'
  return 'info'
}

function displayError(reason: unknown, fallback: string) {
  if (reason instanceof ApiError) {
    if (reason.code === 'BACKEND_UNAVAILABLE' || (reason.status != null && reason.status >= 500)) {
      return '账号权限服务暂时不可用，请确认本机服务已启动后刷新页面。'
    }
  }
  const message = reason instanceof Error ? reason.message : String(reason || '')
  return message || fallback
}

watch(
  () => form.role,
  (role) => {
    if (isBossRole(role)) {
      form.storeId = ''
      form.storeScope = []
      return
    }
    if (normalizeRoleCode(role) === 'STORE_MANAGER') {
      const storeId = form.storeId || form.storeScope[0] || ''
      form.storeId = storeId
      form.storeScope = storeId ? [storeId] : []
    }
  },
)

onMounted(() => {
  document.addEventListener('keydown', handleDialogEscape)
  void refresh()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleDialogEscape)
})
</script>

<template>
  <section class="page-panel users-page">
    <PageHeader>
      <template #actions>
        <div class="page-actions">
          <button v-if="canManage" class="primary-button" type="button" @click="openCreate">
            <Plus :size="16" />新增账号
          </button>
          <button class="ghost-button" type="button" :disabled="loading" @click="requestRefresh">
            <RefreshCw :size="16" />刷新
          </button>
        </div>
      </template>
    </PageHeader>

    <aside class="desktop-workflow-notice" role="note">
      <strong>请在电脑端完成</strong>
      <span>账号、权限和数据范围设置涉及高风险授权，请使用电脑端完成并仔细核对后保存。</span>
    </aside>

    <div v-if="error" class="error-box">{{ error }}</div>
    <div v-if="successMessage" class="success-box">{{ successMessage }}</div>
    <div v-if="loading && !users.length" class="empty-state">正在读取账号权限...</div>

    <template v-else>
      <div class="metric-grid">
        <article class="metric-card"><span>账号总数</span><b>{{ users.length }}</b></article>
        <article class="metric-card"><span>启用账号</span><b>{{ enabledCount }}</b></article>
        <article class="metric-card"><span>店长账号</span><b>{{ managerCount }}</b></article>
        <article class="metric-card"><span>门店数量</span><b>{{ stores.length }}</b></article>
      </div>

      <div class="users-grid">
        <section class="content-card">
          <div class="table-heading account-table-heading">
            <h3>基础账号</h3>
            <p>维护登录账号、角色、绑定门店和启用状态；详细授权通过盾牌按钮配置。</p>
          </div>
          <div v-if="!users.length" class="empty-state compact">暂无账号数据。</div>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>账号</th>
                  <th>店长姓名</th>
                  <th>员工工号</th>
                  <th>角色</th>
                  <th>绑定门店</th>
                  <th>门店范围</th>
                  <th>可用工作台</th>
                  <th>有效权限状态</th>
                  <th>状态</th>
                  <th v-if="canManage" class="r">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="user in users" :key="user.id">
                  <td><b>{{ user.username }}</b></td>
                  <td>{{ user.role === 'STORE_MANAGER' ? (user.displayName || '待配置') : '-' }}</td>
                  <td>{{ user.role === 'STORE_MANAGER' ? '待补充' : '-' }}</td>
                  <td><span class="status-badge" :class="roleTone(user.role)">{{ user.roleLabel || user.role }}</span></td>
                  <td>{{ storeName(user.storeId) }}</td>
                  <td>{{ scopeText(user) }}</td>
                  <td class="workspace-cell">{{ workspaceText(user) }}</td>
                  <td class="permission-status-cell">
                    <span class="status-badge" :class="effectivePermissionTone(user)">{{ effectivePermissionText(user) }}</span>
                    <small>{{ effectivePermissionDetail(user) }}</small>
                  </td>
                  <td><span class="status-badge" :class="user.enabled ? 'ok' : 'bad'">{{ user.enabled ? '启用' : '停用' }}</span></td>
                  <td v-if="canManage" class="r actions-cell">
                    <button
                      class="icon-button"
                      :class="{ selected: selectedAuthorizationUser?.id === user.id }"
                      type="button"
                      title="配置角色模板、数据范围和个人权限"
                      :aria-label="`配置 ${user.username} 的账号授权`"
                      @click="requestOpenAuthorization(user)"
                    ><Shield :size="15" /></button>
                    <button class="icon-button" type="button" title="编辑账号" @click="openEdit(user)"><Pencil :size="15" /></button>
                    <button
                      v-if="canResetPassword(user)"
                      class="icon-button"
                      type="button"
                      :title="isBossRole(user.role) ? '修改本人密码' : '安全重置密码'"
                      @click="openPasswordReset(user)"
                    ><KeyRound :size="15" /></button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="side-stack">
          <section class="content-card">
            <div class="panel-title"><ShieldCheck :size="20" /><h3>角色分布</h3></div>
            <div class="role-list">
              <div v-for="row in roleRows" :key="row.label"><span>{{ row.label }}</span><b>{{ row.count }}</b></div>
            </div>
          </section>
          <section class="content-card">
            <div class="panel-title"><Store :size="20" /><h3>门店范围</h3></div>
            <div class="scope-list">
              <div v-for="user in users.filter((item) => !item.storeScope.includes('all'))" :key="user.id">
                <b>{{ user.displayName || user.username }}</b>
                <span>{{ scopeText(user) }}</span>
              </div>
              <div v-if="!users.some((item) => !item.storeScope.includes('all'))" class="empty-state compact">暂无门店范围配置。</div>
            </div>
          </section>
        </aside>
      </div>

      <section v-if="canManage" class="authorization-workspace" aria-labelledby="authorization-title">
        <header class="authorization-head">
          <div class="authorization-heading">
            <span class="authorization-icon"><ShieldCheck :size="19" /></span>
            <div>
              <h3 id="authorization-title">账号授权</h3>
              <p v-if="selectedAuthorizationUser">
                {{ selectedAuthorizationUser.displayName || selectedAuthorizationUser.username }} ·
                {{ selectedAuthorizationUser.roleLabel || selectedAuthorizationUser.role }} ·
                权限版本 v{{ userAuthorization?.permissionVersion ?? '—' }}
              </p>
              <p v-else>选择账号后，按角色模板、数据范围、个人覆盖和最终权限四部分核对。</p>
            </div>
          </div>
          <UiButton
            v-if="selectedAuthorizationUser"
            variant="ghost"
            type="button"
            icon-only
            aria-label="关闭账号授权"
            title="关闭"
            :disabled="authorizationSaving"
            @click="closeAuthorization"
          >
            <template #icon><X :size="18" /></template>
          </UiButton>
        </header>

        <div v-if="!selectedAuthorizationUser" class="authorization-empty">
          <Shield :size="24" />
          <div><b>尚未选择账号</b><span>点击账号列表操作栏中的盾牌按钮开始配置。</span></div>
        </div>

        <div v-else-if="authorizationLoading" class="authorization-loading" aria-label="正在读取账号授权">
          <span v-for="index in 4" :key="index"></span>
        </div>

        <template v-else>
          <div v-if="authorizationError" class="authorization-message error-box">
            <span>{{ authorizationError }}</span>
            <button v-if="!userAuthorization" class="ghost-button" type="button" @click="openAuthorization(selectedAuthorizationUser)">重新读取</button>
          </div>
          <div v-if="authorizationSuccess" class="authorization-message success-box">{{ authorizationSuccess }}</div>

          <template v-if="userAuthorization">
            <div v-if="selectedAuthorizationIsBoss" class="fixed-authorization-note">
              <ShieldCheck :size="18" />
              <div><b>老板权限由系统固定</b><span>老板始终拥有当前公司全部权限和全部数据范围，本页只读且不会发起授权保存请求。</span></div>
            </div>

            <div
              v-if="normalizeRoleCode(selectedAuthorizationUser.role) === 'STORE_MANAGER' && !selectedManagerWorkspaceReady"
              class="workspace-warning"
            >
              <AlertTriangle :size="18" />
              <div>
                <b>店长工作台未授权</b>
                <span>请确认最终权限包含“门店查看”、已绑定唯一门店，并将门店数据范围设为“绑定门店（本店）”。明确撤销仍然优先。</span>
              </div>
            </div>

            <div v-if="effectiveHighRiskCount" class="high-risk-summary">
              <AlertTriangle :size="18" />
              <div><b>最终权限包含 {{ effectiveHighRiskCount }} 项高风险操作</b><span>请逐项核对工资、导出、平台、账号、审计、总仓和盘存等敏感能力。</span></div>
            </div>

            <div class="authorization-sections">
              <section class="authorization-section">
                <div class="authorization-section-head">
                  <div><h4>角色模板</h4><p>角色默认权限只读；非老板可在“额外权限”中增加或明确撤销。</p></div>
                  <span class="section-count">{{ selectedAuthorizationIsBoss ? catalogPermissions.length : roleTemplatePermissionSet.size }} 项</span>
                </div>
                <div v-if="!roleTemplateGroups.length" class="inline-empty">该角色没有默认管理权限。</div>
                <div v-else class="permission-summary-groups">
                  <div v-for="group in roleTemplateGroups" :key="group.moduleCode" class="permission-summary-group">
                    <b>{{ moduleLabel(group.moduleCode) }}</b>
                    <div class="permission-tags">
                      <span
                        v-for="permission in group.permissions"
                        :key="permission.permissionCode"
                        :class="{ risk: isHighRiskPermission(permission) }"
                        :title="permission.description"
                      >{{ permission.permissionName }}<small v-if="isHighRiskPermission(permission)">高风险</small></span>
                    </div>
                  </div>
                </div>
              </section>

              <section class="authorization-section">
                <div class="authorization-section-head">
                  <div><h4>数据范围</h4><p>功能权限与数据范围分开计算，最终只能访问两者交集内的数据。</p></div>
                  <span class="section-count">7 个业务域</span>
                </div>
                <div class="data-scope-list">
                  <div v-for="scope in dataScopeRows" :key="scope.domainCode" class="data-scope-row">
                    <div class="data-scope-name"><b>{{ domainLabel(scope.domainCode) }}</b><span>{{ domainDescription(scope.domainCode) }}</span></div>
                    <select
                      v-model="scope.mode"
                      :aria-label="`${domainLabel(scope.domainCode)}数据范围`"
                      :disabled="selectedAuthorizationIsBoss || authorizationSaving"
                      @change="onScopeModeChanged(scope.domainCode)"
                    >
                      <option
                        v-for="mode in scopeModeOptions(scope.domainCode)"
                        :key="mode"
                        :value="mode"
                        :disabled="mode === 'OWN_STORE' && !selectedAuthorizationUser.storeId"
                      >{{ scopeModeLabel(mode, scope.domainCode) }}</option>
                    </select>
                    <div v-if="scope.mode === 'WAREHOUSE_LIST'" class="store-scope-picker warehouse-scope-picker" aria-label="指定仓库范围">
                      <label v-for="warehouse in warehouses" :key="warehouse.id">
                        <input v-model="scope.warehouseIds" type="checkbox" :value="String(warehouse.id)" :disabled="authorizationSaving" />
                        <span>{{ warehouse.name }} · {{ warehouse.type === 'CENTRAL' ? '总仓' : '区域分仓' }}</span>
                      </label>
                      <span v-if="!warehouses.length" class="inline-empty">暂无可选仓库。</span>
                    </div>
                    <div v-else-if="scope.mode === 'STORE_LIST'" class="store-scope-picker">
                      <label v-for="store in stores" :key="store.id">
                        <input v-model="scope.storeIds" type="checkbox" :value="store.id" :disabled="authorizationSaving" />
                        <span>{{ store.brandName }} · {{ store.name }}</span>
                      </label>
                      <span v-if="!stores.length" class="inline-empty">暂无可选门店。</span>
                    </div>
                    <span v-else-if="scope.mode === 'OWN_STORE'" class="scope-context">
                      当前绑定：{{ storeName(selectedAuthorizationUser.storeId) }}
                    </span>
                    <span v-else-if="scope.mode === 'CENTRAL_WAREHOUSE'" class="scope-context">仅荆州总仓（历史兼容范围）</span>
                    <span v-else-if="scope.mode === 'SELF'" class="scope-context">仅本人课程与考试任务</span>
                  </div>
                </div>
              </section>

              <section class="authorization-section">
                <div class="authorization-section-head">
                  <div><h4>额外权限</h4><p>“跟随角色模板”不保存个人覆盖；明确撤销优先于角色模板和额外授权。</p></div>
                  <span class="deny-priority">DENY 优先</span>
                </div>
                <div v-if="selectedAuthorizationIsBoss" class="inline-empty">老板不使用个人授权或撤销记录。</div>
                <div v-else class="permission-override-groups">
                  <div v-for="group in permissionGroups" :key="group.moduleCode" class="permission-override-group">
                    <h5>{{ moduleLabel(group.moduleCode) }}</h5>
                    <div class="permission-override-list">
                      <div
                        v-for="permission in group.permissions"
                        :key="permission.permissionCode"
                        class="permission-override-row"
                        :class="{ 'high-risk-row': isHighRiskPermission(permission) }"
                      >
                        <div class="permission-copy">
                          <div class="permission-name-line">
                            <b>{{ permission.permissionName }}</b>
                            <span v-if="isHighRiskPermission(permission)" class="risk-badge"><AlertTriangle :size="12" />高风险</span>
                            <span class="template-state" :class="roleTemplatePermissionSet.has(permission.permissionCode) ? 'included' : ''">
                              模板{{ roleTemplatePermissionSet.has(permission.permissionCode) ? '已含' : '未含' }}
                            </span>
                          </div>
                          <span>{{ permission.description }}</span>
                          <small v-if="isHighRiskPermission(permission)">{{ highRiskHint(permission) }}</small>
                        </div>
                        <select
                          v-model="draftOverrides[permission.permissionCode]"
                          :aria-label="`${permission.permissionName}个人权限`"
                          :class="{
                            'override-allow': draftOverrides[permission.permissionCode] === 'ALLOW',
                            'override-deny': draftOverrides[permission.permissionCode] === 'DENY',
                          }"
                          :disabled="authorizationSaving"
                          @change="onOverrideChanged"
                        >
                          <option value="">跟随角色模板</option>
                          <option value="ALLOW" :disabled="learnerAllowDisabled(permission.permissionCode)">额外授权 ALLOW</option>
                          <option value="DENY">明确撤销 DENY</option>
                        </select>
                      </div>
                    </div>
                  </div>
                </div>
              </section>

              <section class="authorization-section final-permission-section">
                <div class="authorization-section-head">
                  <div><h4>最终权限预览</h4><p>根据角色模板、当前个人覆盖实时计算；保存后由后端再次校验并作为最终结果。</p></div>
                  <span class="section-count">{{ draftEffectivePermissions.length }} 项</span>
                </div>
                <div v-if="!effectivePermissionGroups.length" class="inline-empty">最终没有可用功能权限。</div>
                <div v-else class="permission-summary-groups final-groups">
                  <div v-for="group in effectivePermissionGroups" :key="group.moduleCode" class="permission-summary-group">
                    <b>{{ moduleLabel(group.moduleCode) }}</b>
                    <div class="permission-tags final-tags">
                      <span
                        v-for="permission in group.permissions"
                        :key="permission.permissionCode"
                        :class="{ risk: isHighRiskPermission(permission) }"
                      ><CheckCircle2 :size="13" />{{ permission.permissionName }}</span>
                    </div>
                  </div>
                </div>
              </section>
            </div>

            <ModalFooter class="authorization-actions" sticky>
              <template #info>
                <div>
                  <b>{{ authorizationDirty ? '有尚未保存的授权调整' : '当前授权已与服务器一致' }}</b>
                  <span v-if="!selectedAuthorizationIsBoss">保存会使目标账号旧登录立即失效。</span>
                </div>
              </template>
              <UiButton
                v-if="!selectedAuthorizationIsBoss"
                variant="primary"
                type="button"
                :disabled="authorizationSaving || !authorizationDirty"
                @click="saveAuthorization"
              >{{ authorizationSaving ? '正在保存授权' : '保存账号授权' }}</UiButton>
            </ModalFooter>
          </template>
        </template>
      </section>

      <section class="content-card pending-manager-card">
        <div class="table-heading pending-manager-heading">
          <div>
            <h3>待配置店长的门店</h3>
            <p>备份中没有可按“租户 + 门店编码 + 员工工号”确认的账号关系，不会自动指定店长。</p>
          </div>
          <span class="status-badge" :class="pendingManagerStores.length ? 'warn' : 'ok'">{{ pendingManagerStores.length }} 家待配置</span>
        </div>
        <div v-if="!pendingManagerStores.length" class="empty-state compact">全部门店均已绑定明确的店长账号。</div>
        <div v-else class="pending-store-grid">
          <div v-for="store in pendingManagerStores" :key="store.id" class="pending-store-row">
            <div><b>{{ store.name }}</b><small>{{ store.brandName || '未分品牌' }} · {{ store.code || store.id }}</small></div>
            <span class="status-badge warn">待配置</span>
          </div>
        </div>
      </section>
    </template>

    <div v-if="editorOpen" class="editor-backdrop" role="presentation" @click.self="closeEditor">
      <form
        class="account-editor"
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-editor-title"
        @submit.prevent="save"
      >
        <div class="editor-head">
          <h3 id="account-editor-title">{{ editingUser ? '编辑账号资料' : '新增基础账号' }}</h3>
          <UiButton variant="ghost" type="button" icon-only aria-label="关闭账号编辑" title="关闭" :disabled="saving" @click="closeEditor">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </div>
        <div class="editor-body">
          <label>
            登录账号
            <input v-model.trim="form.username" type="text" autocomplete="off" :disabled="Boolean(editingUser) || saving" placeholder="例如：store-manager-01" />
          </label>
          <label>
            姓名或显示名称
            <input v-model.trim="form.displayName" type="text" :disabled="saving" placeholder="例如：荆州之星店店长" />
          </label>
          <label>
            角色
            <select v-model="form.role" :disabled="saving || editingSelf || protectedBoss">
              <option v-for="role in roles" :key="role.value" :value="role.value">{{ role.label }}</option>
            </select>
          </label>
          <template v-if="storeManagerAccountForm">
            <label>
              绑定门店
              <select v-model="form.storeId" :disabled="saving">
                <option value="">请选择一家门店</option>
                <option v-for="store in stores" :key="store.id" :value="store.id">{{ store.brandName }} · {{ store.name }}</option>
              </select>
            </label>
            <p class="account-role-hint">店长账号必须且只能绑定一家门店；详细业务范围在账号授权中配置。</p>
          </template>
          <p v-else-if="bossAccountForm" class="account-role-hint">老板固定拥有当前公司全部功能和数据范围，无需单独授权。</p>
          <p v-else class="account-role-hint">基础账号只设置身份。保存后请在账号列表点击盾牌按钮，分别配置业务数据范围和个人权限。</p>
          <label v-if="!editingUser">
            初始密码
            <input v-model="form.password" type="password" autocomplete="new-password" :disabled="saving" placeholder="至少 8 位" />
          </label>
          <label class="enabled-row">
            <input v-model="form.enabled" type="checkbox" :disabled="saving || editingSelf || protectedBoss" />
            <span>启用账号</span>
          </label>
        </div>
        <div v-if="editingAuthorizationLoading" class="editor-loading-note">正在读取权限和数据范围，完成后才能保存。</div>
        <ModalFooter>
          <UiButton variant="secondary" type="button" :disabled="saving" @click="closeEditor">取消</UiButton>
          <UiButton variant="primary" type="submit" :disabled="saving || editingAuthorizationLoading">{{ saving ? '正在保存' : '保存' }}</UiButton>
        </ModalFooter>
      </form>
    </div>

    <div v-if="resetTarget" class="editor-backdrop reset-backdrop" role="presentation" @click.self="closePasswordReset">
      <form
        class="password-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="password-dialog-title"
        @submit.prevent="submitPasswordReset"
      >
        <div class="editor-head">
          <h3 id="password-dialog-title">{{ isBossRole(resetTarget.role) ? '修改本人密码' : `重置 ${resetTarget.username} 的密码` }}</h3>
          <UiButton variant="ghost" type="button" icon-only aria-label="关闭密码编辑" title="关闭" :disabled="resetting" @click="closePasswordReset">
            <template #icon><X :size="18" /></template>
          </UiButton>
        </div>
        <div class="password-dialog-body">
          <div v-if="resetError" class="error-box">{{ resetError }}</div>
          <label v-if="isBossRole(resetTarget.role)">
            当前密码
            <input v-model="resetForm.currentPassword" type="password" autocomplete="current-password" :disabled="resetting" />
          </label>
          <label>
            新密码
            <input v-model="resetForm.password" type="password" autocomplete="new-password" :disabled="resetting" placeholder="8 至 128 位" />
          </label>
          <label>
            确认新密码
            <input v-model="resetForm.confirmPassword" type="password" autocomplete="new-password" :disabled="resetting" />
          </label>
        </div>
        <ModalFooter>
          <UiButton variant="secondary" type="button" :disabled="resetting" @click="closePasswordReset">取消</UiButton>
          <UiButton variant="primary" type="submit" :disabled="resetting">{{ resetting ? '正在提交' : '确认修改' }}</UiButton>
        </ModalFooter>
      </form>
    </div>

    <UnsavedChangesDialog
      :open="discardDialogOpen"
      :message="discardDialogMessage"
      keep-label="继续编辑"
      discard-label="放弃修改"
      @keep-editing="keepEditing"
      @discard="discardChanges"
    />
  </section>
</template>

<style scoped>
.users-page,
.side-stack,
.role-list,
.scope-list {
  display: grid;
  gap: 14px;
}

.desktop-workflow-notice {
  display: none;
}

.page-actions,
.actions-cell {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.users-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 14px;
}

.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table-wrap table { min-width: 1180px; }
th, td { padding: 10px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: middle; }
th { color: var(--muted); font-size: 12px; font-weight: 800; }
.r { text-align: right; }
.workspace-cell { min-width: 150px; color: #415a57; }
.permission-status-cell { display: grid; min-width: 190px; gap: 5px; }
.permission-status-cell .status-badge { width: fit-content; }
.permission-status-cell small { color: var(--muted); line-height: 1.45; }

.panel-title { display: flex; align-items: center; gap: 8px; }
.panel-title h3, .table-heading h3 { margin: 0; font-size: 16px; }
.account-table-heading p { margin: 5px 0 0; color: var(--muted); font-size: 12px; }
.pending-manager-card { display: grid; gap: 14px; }
.pending-manager-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.pending-manager-heading p { margin: 6px 0 0; color: var(--muted); font-size: 12px; }
.pending-store-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; }
.pending-store-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; }
.pending-store-row div { display: grid; gap: 3px; }
.pending-store-row small { color: var(--muted); }
.role-list > div, .scope-list > div {
  display: grid;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.role-list > div { grid-template-columns: 1fr auto; align-items: center; }
.role-list span, .scope-list span { color: var(--muted); font-size: 12px; }

.icon-button.selected {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.authorization-workspace {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
}

.authorization-head,
.authorization-actions,
.authorization-section-head,
.authorization-heading,
.fixed-authorization-note,
.workspace-warning,
.high-risk-summary,
.authorization-empty,
.authorization-message {
  display: flex;
  align-items: center;
}

.authorization-head {
  min-height: 68px;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--ds-surface-muted, #f7faf9);
}

.authorization-heading {
  min-width: 0;
  gap: 10px;
}

.authorization-heading h3,
.authorization-section h4,
.permission-override-group h5 {
  margin: 0;
}

.authorization-heading h3 {
  font-size: 16px;
}

.authorization-heading p,
.authorization-section-head p {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

.authorization-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 7px;
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.authorization-empty {
  min-height: 112px;
  justify-content: center;
  gap: 12px;
  color: var(--muted);
}

.authorization-empty div,
.fixed-authorization-note div,
.workspace-warning div,
.high-risk-summary div,
.authorization-actions div {
  display: grid;
  gap: 3px;
}

.authorization-empty b {
  color: var(--ink);
}

.authorization-empty span,
.fixed-authorization-note span,
.workspace-warning span,
.high-risk-summary span,
.authorization-actions span {
  font-size: 12px;
}

.authorization-loading {
  display: grid;
  gap: 10px;
  padding: 18px;
}

.authorization-loading span {
  height: 54px;
  border-radius: 7px;
  background: var(--ds-surface-muted, #f7faf9);
  animation: authorization-pulse 1.4s ease-in-out infinite;
}

.authorization-message {
  justify-content: space-between;
  gap: 12px;
  margin: 14px 16px 0;
}

.authorization-message .ghost-button {
  flex: 0 0 auto;
}

.fixed-authorization-note,
.workspace-warning,
.high-risk-summary {
  gap: 10px;
  margin: 14px 16px 0;
  padding: 10px 12px;
  border-radius: 7px;
}

.fixed-authorization-note {
  border: 1px solid rgba(39, 107, 101, .2);
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.high-risk-summary {
  border: 1px solid rgba(181, 103, 19, .28);
  background: var(--ds-warning-soft, #fff7e8);
  color: #7e460b;
}

.workspace-warning {
  border: 1px solid rgba(181, 103, 19, .28);
  background: var(--ds-warning-soft, #fff7e8);
  color: #7e460b;
}

.authorization-sections {
  padding-top: 8px;
}

.authorization-section {
  padding: 18px 16px;
  border-top: 1px solid var(--line);
}

.authorization-section:first-child {
  border-top: 0;
}

.authorization-section-head {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 13px;
}

.authorization-section h4 {
  font-size: 15px;
}

.section-count,
.deny-priority {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
}

.section-count {
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.deny-priority {
  background: var(--ds-danger-soft, #fff0f1);
  color: var(--bad);
}

.inline-empty {
  padding: 12px;
  border-radius: 7px;
  background: var(--ds-surface-muted, #f7faf9);
  color: var(--muted);
  font-size: 12px;
}

.permission-summary-groups,
.permission-override-groups {
  display: grid;
  gap: 12px;
}

.permission-summary-group {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr);
  align-items: start;
  gap: 12px;
}

.permission-summary-group > b {
  padding-top: 5px;
  color: #415a57;
  font-size: 12px;
}

.permission-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.permission-tags > span {
  display: inline-flex;
  min-height: 27px;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  color: #314743;
  font-size: 12px;
}

.permission-tags > span.risk {
  border-color: rgba(220, 53, 69, .24);
  background: var(--ds-danger-soft, #fff0f1);
  color: #a72b38;
}

.permission-tags small {
  font-size: 10px;
  font-weight: 800;
}

.data-scope-list {
  border-top: 1px solid var(--line);
}

.data-scope-row {
  display: grid;
  grid-template-columns: 210px 230px minmax(240px, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 66px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.data-scope-name {
  display: grid;
  gap: 3px;
}

.data-scope-name span,
.scope-context {
  color: var(--muted);
  font-size: 12px;
}

.data-scope-row > select,
.permission-override-row > select {
  width: 100%;
}

.store-scope-picker {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 6px 12px;
  max-height: 136px;
  overflow-y: auto;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--ds-surface-muted, #f7faf9);
}

.store-scope-picker label {
  display: flex;
  align-items: center;
  gap: 7px;
  min-height: 28px;
  font-size: 12px;
}

.permission-override-group {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px;
}

.permission-override-group h5 {
  padding: 8px 11px;
  border-bottom: 1px solid var(--line);
  background: var(--ds-surface-muted, #f7faf9);
  color: #415a57;
  font-size: 12px;
}

.permission-override-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 190px;
  align-items: center;
  gap: 18px;
  min-height: 70px;
  padding: 10px 11px;
  border-bottom: 1px solid var(--line);
}

.permission-override-row:last-child {
  border-bottom: 0;
}

.permission-override-row.high-risk-row {
  background: #fffafa;
}

.permission-copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.permission-copy > span,
.permission-copy > small {
  color: var(--muted);
  font-size: 12px;
}

.permission-copy > small {
  color: #9e3a45;
}

.permission-name-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
}

.risk-badge,
.template-state {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
}

.risk-badge {
  background: var(--ds-danger-soft, #fff0f1);
  color: #a72b38;
}

.template-state {
  background: #f0f3f3;
  color: #60716f;
}

.template-state.included {
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.permission-override-row select.override-allow {
  border-color: rgba(25, 135, 84, .4);
  background: var(--ds-success-soft, #edf8f2);
  color: #12623d;
}

.permission-override-row select.override-deny {
  border-color: rgba(220, 53, 69, .36);
  background: var(--ds-danger-soft, #fff0f1);
  color: #a72b38;
}

.final-permission-section {
  background: var(--ds-surface-muted, #f7faf9);
}

.final-tags > span {
  border-color: rgba(39, 107, 101, .2);
  background: #fff;
  color: var(--primary-dark);
}

.account-role-hint {
  margin: -2px 0 0;
  padding: 9px 10px;
  border-radius: 6px;
  background: var(--ds-surface-muted, #f7faf9);
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
}

@keyframes authorization-pulse {
  0%, 100% { opacity: .62; }
  50% { opacity: 1; }
}

.editor-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1400;
  display: flex;
  justify-content: flex-end;
  background: rgba(20, 24, 32, .34);
}
.account-editor {
  display: flex;
  width: min(520px, 100vw);
  min-height: 100%;
  flex-direction: column;
  background: #fff;
  box-shadow: -12px 0 32px rgba(22, 26, 34, .16);
}

.reset-backdrop {
  align-items: center;
  justify-content: center;
}

.password-dialog {
  width: 460px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 14px 36px rgba(22, 26, 34, .16);
}

.password-dialog-body {
  display: grid;
  gap: 14px;
  padding: 18px;
}

.password-dialog-body label {
  display: grid;
  gap: 7px;
  font-size: 13px;
  font-weight: 800;
}

.password-dialog-body input {
  width: 100%;
}
.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
}
.editor-head h3 { margin: 0; font-size: 18px; }
.editor-body {
  display: grid;
  flex: 1;
  align-content: start;
  gap: 14px;
  overflow-y: auto;
  padding: 18px;
}
.editor-body > label { display: grid; gap: 7px; font-size: 13px; font-weight: 800; }
.editor-body input[type='text'], .editor-body input[type='password'], .editor-body select {
  width: 100%; box-sizing: border-box; padding: 10px 11px; border: 1px solid var(--line); border-radius: 8px; background: #fff; font: inherit;
}
.editor-body > label.enabled-row { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.editor-loading-note { margin: 0 18px 14px; color: var(--muted); font-size: 12px; }
@media (max-width: 768px) {
  .desktop-workflow-notice {
    display: grid;
    gap: var(--space-1);
    padding: var(--space-3);
    border: 1px solid #efd19f;
    border-radius: var(--radius-md);
    background: #fff8ed;
    color: #73450f;
    font-size: 13px;
    line-height: 1.5;
  }

  .desktop-workflow-notice strong {
    color: #73450f;
    font-size: 14px;
  }
}

@media (max-width: 1100px) {
  .users-grid { grid-template-columns: 1fr; }
  .data-scope-row { grid-template-columns: 180px 210px minmax(220px, 1fr); }
}

@media (prefers-reduced-motion: reduce) {
  .authorization-loading span { animation: none; }
}
</style>
