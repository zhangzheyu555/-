import { defineStore } from 'pinia'
import {
  escalateOperationsTodo,
  getAuditLogs,
  getBrands,
  getElemeStatus,
  getElemeSummary,
  getLegacyKvPreview,
  getMigrationStatus,
  getOperationsTodos,
  getStores,
  resolveOperationsTodo,
  type BrandInfo,
  type ElemeStatus,
  type ElemeSummary,
  type LegacyKvPreview,
  type MigrationStatus,
  type OperationLog,
  type StoreInfo,
} from '../api/operations'
import type { RoleTodoItem } from '../api/todos'
import { useAuthStore } from './auth'

export type OperationsTab =
  | 'overview'
  | 'todos'
  | 'analysis'
  | 'training'
  | 'exam'
  | 'inventory-check'
  | 'eleme'
  | 'data-health'
  | 'imports'
  | 'platform'
  | 'migration'
  | 'logs'
  | 'done'

export interface OperationsHealthItem {
  id: string
  name: string
  status: '正常' | '需要检查' | '数据异常' | '已处理'
  scope: string
  checkedAt: string
  action: string
}

export interface OperationsImportItem {
  id: string
  importType: string
  source: string
  month?: string
  storeName?: string
  status: string
  error?: string
}

export interface PlatformAccountItem {
  id: string
  platformName: string
  storeName: string
  loginStatus: string
  syncedAt: string
  issue: string
}

interface OperationsState {
  activeTab: OperationsTab
  todos: RoleTodoItem[]
  doneItems: RoleTodoItem[]
  migrationStatus: MigrationStatus | null
  legacyPreview: LegacyKvPreview | null
  auditLogs: OperationLog[]
  stores: StoreInfo[]
  brands: BrandInfo[]
  elemeStatus: ElemeStatus | null
  elemeSummary: ElemeSummary | null
  elemeMonth: string
  elemeLoading: boolean
  loading: boolean
  actioningId: string
  error: string
  actionMessage: string
}

export const useOperationsStore = defineStore('operations', {
  state: (): OperationsState => ({
    activeTab: 'overview',
    todos: [],
    doneItems: [],
    migrationStatus: null,
    legacyPreview: null,
    auditLogs: [],
    stores: [],
    brands: [],
    elemeStatus: null,
    elemeSummary: null,
    elemeMonth: currentMonth(),
    elemeLoading: false,
    loading: false,
    actioningId: '',
    error: '',
    actionMessage: '',
  }),
  getters: {
    openTodos: (state) => state.todos.filter((item) => !isDone(item)),
    dataTodos: (state) => state.todos.filter((item) => includesAny(item, ['数据', '导入', '格式', 'data', 'import'])),
    platformTodos: (state) => state.todos.filter((item) => includesAny(item, ['平台', '账号', '登录', '同步', 'platform', 'account'])),
    migrationTodos: (state) => state.todos.filter((item) => includesAny(item, ['迁移', 'legacy', '浏览器', 'browser', 'localStorage'])),
    escalatedCount: (state) => state.todos.filter((item) => item.escalatedToBoss).length,
  },
  actions: {
    setTab(tab: string | null | undefined) {
      const normalized = String(tab || 'overview') as OperationsTab
      this.activeTab = [
        'overview',
        'todos',
        'analysis',
        'training',
        'exam',
        'inventory-check',
        'eleme',
        'data-health',
        'imports',
        'platform',
        'migration',
        'logs',
        'done',
      ].includes(normalized)
        ? normalized
        : 'overview'
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        const auth = useAuthStore()
        const canReadAudit = ['ADMIN', 'BOSS', 'OWNER'].includes(auth.role)
        const [todoResponse, migrationStatus, legacyPreview, auditLogs, stores, brands, elemeStatus, elemeSummary] = await Promise.all([
          getOperationsTodos().catch(() => ({ items: [] as RoleTodoItem[] })),
          getMigrationStatus().catch(() => null),
          getLegacyKvPreview().catch(() => null),
          canReadAudit ? getAuditLogs().catch(() => [] as OperationLog[]) : Promise.resolve([] as OperationLog[]),
          getStores().catch(() => [] as StoreInfo[]),
          getBrands().catch(() => [] as BrandInfo[]),
          getElemeStatus().catch(() => null),
          getElemeSummary(this.elemeMonth).catch(() => null),
        ])
        const items = todoResponse.items || []
        this.todos = items.filter((item) => !isDone(item))
        this.doneItems = items.filter(isDone).slice(0, 20)
        this.migrationStatus = migrationStatus
        this.legacyPreview = legacyPreview
        this.auditLogs = auditLogs
        this.stores = stores
        this.brands = brands
        this.elemeStatus = elemeStatus
        this.elemeSummary = elemeSummary
      } catch (error) {
        this.error = error instanceof Error ? error.message : '运营中心加载失败'
      } finally {
        this.loading = false
      }
    },
    async loadEleme(month?: string) {
      const selectedMonth = month || this.elemeMonth
      this.elemeMonth = selectedMonth
      this.elemeLoading = true
      this.error = ''
      try {
        const [status, summary] = await Promise.all([
          getElemeStatus().catch(() => null),
          getElemeSummary(selectedMonth),
        ])
        this.elemeStatus = status
        this.elemeSummary = summary
      } catch (error) {
        this.error = error instanceof Error ? error.message : '饿了么订单营业额加载失败'
      } finally {
        this.elemeLoading = false
      }
    },
    async resolveTodo(item: RoleTodoItem, note: string) {
      await this.runAction(item.id, async () => {
        await resolveOperationsTodo(item.id, note || '运营已处理该事项')
        this.actionMessage = '运营事项已标记处理'
      })
    },
    async escalateTodo(item: RoleTodoItem, reason: string) {
      await this.runAction(item.id, async () => {
        await escalateOperationsTodo(item.id, reason || '运营判断该事项需要老板确认')
        this.actionMessage = '已上报老板'
      })
    },
    async runAction(id: string, action: () => Promise<void>) {
      this.actioningId = id
      this.error = ''
      this.actionMessage = ''
      try {
        await action()
        await this.load()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '运营操作失败'
        throw error
      } finally {
        this.actioningId = ''
      }
    },
  },
})

export function buildHealthItems(state: OperationsState): OperationsHealthItem[] {
  const lastLogAt = state.auditLogs[0]?.createdAt || '暂无记录'
  const missingStoreCount = state.stores.filter((store) => !store.manager || !store.brandName || store.status === 'DISABLED').length
  const migrationRequired = state.migrationStatus?.migrationRequired || false
  const actionableKv = state.legacyPreview?.actionableKeyCount || 0
  return [
    {
      id: 'profit-data',
      name: '利润数据完整性',
      status: state.auditLogs.some((log) => includesText(`${log.action} ${log.reason}`, ['profit', '利润', '异常'])) ? '需要检查' : '正常',
      scope: '全部门店',
      checkedAt: lastLogAt,
      action: '查看财务数据核对结果',
    },
    {
      id: 'store-profile',
      name: '门店基础资料完整性',
      status: missingStoreCount ? '数据异常' : '正常',
      scope: missingStoreCount ? `${missingStoreCount} 家门店需要补资料` : `${state.stores.length} 家门店`,
      checkedAt: lastLogAt,
      action: '补齐门店资料',
    },
    {
      id: 'legacy-kv',
      name: '旧 KV 数据迁移状态',
      status: actionableKv ? '需要检查' : migrationRequired ? '需要检查' : '正常',
      scope: `可迁移 ${actionableKv} 项`,
      checkedAt: lastLogAt,
      action: '查看迁移状态',
    },
    {
      id: 'mysql-storage',
      name: 'MySQL 数据落库状态',
      status: migrationRequired ? '需要检查' : '正常',
      scope: `${state.migrationStatus?.presentBusinessKeyCount || 0}/${state.migrationStatus?.businessKeyCount || 0} 项已落库`,
      checkedAt: lastLogAt,
      action: '核对迁移预览',
    },
  ]
}

export function buildImportItems(logs: OperationLog[], todos: RoleTodoItem[]): OperationsImportItem[] {
  const todoItems = todos.filter((item) => includesAny(item, ['导入', '数据', '格式', 'import']))
  const fromTodos = todoItems.map((item) => ({
    id: item.id,
    importType: cleanText(item.title || '数据导入提醒'),
    source: item.sourceModule || item.dataSource || '运营待办',
    month: item.month,
    storeName: item.storeName,
    status: statusLabel(item.status),
    error: item.summary,
  }))
  const fromLogs = logs
    .filter((log) => includesText(`${log.action} ${log.targetType} ${log.reason}`, ['导入', 'import', 'migration', '迁移']))
    .slice(0, 12)
    .map((log) => ({
      id: `log-${log.id}`,
      importType: cleanText(log.action || '数据处理记录'),
      source: log.targetType || '操作日志',
      month: log.month,
      storeName: log.storeId,
      status: '已记录',
      error: log.reason,
    }))
  return [...fromTodos, ...fromLogs]
}

export function buildPlatformItems(_stores: StoreInfo[], todos: RoleTodoItem[]): PlatformAccountItem[] {
  const todoItems = todos.filter((item) => includesAny(item, ['平台', '账号', '登录', '同步', 'platform']))
  const fromTodos = todoItems.map((item) => ({
    id: item.id,
    platformName: item.sourceModule || '平台账号',
    storeName: item.storeName || item.storeId || '全部门店',
    loginStatus: statusLabel(item.status),
    syncedAt: item.updatedAt || item.occurredAt || '待检查',
    issue: item.summary || item.title,
  }))
  return fromTodos
}

function isDone(item: RoleTodoItem) {
  return includesText(`${item.status} ${item.processStatus}`, ['DONE', 'TODO_DONE', '已处理', '已完成', '已关闭'])
}

function includesAny(item: RoleTodoItem, needles: string[]) {
  return includesText(`${item.id} ${item.title} ${item.summary} ${item.sourceModule} ${item.dataSource}`, needles)
}

function includesText(text: string, needles: string[]) {
  const lower = text.toLowerCase()
  return needles.some((needle) => lower.includes(needle.toLowerCase()))
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    RISK: '数据异常',
    PENDING: '待处理',
    DONE: '已处理',
    RED: '红色风险',
    ORANGE: '待处理',
    BLUE: '需要检查',
  }
  return map[status] || cleanText(status || '待检查')
}

function cleanText(text: string) {
  return text
    .replace(/legacy_kv/gi, '旧 KV 数据')
    .replace(/localStorage/gi, '浏览器本地数据')
    .replace(/\bRISK\b/g, '数据异常')
    .replace(/\bPENDING\b/g, '待处理')
    .replace(/\bDONE\b/g, '已处理')
}

function currentMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}
