import { defineStore } from 'pinia'
import { closeBossTodo, getBossTodoDashboard, getBossTodos, resolveBossTodo } from '../api/boss'
import type { BossTodoDashboard, RoleTodoItem } from '../api/todos'

export interface BossFocus {
  needsBossActionCount: number
  highRiskCount: number
  roleWorkCount: number
  doneCount: number
  summary: string
}

export interface BossRiskGroup {
  id: string
  title: string
  sourceModule: string
  sourceLabel: string
  ownerName: string
  storeName?: string
  month?: string
  count: number
  highestRiskLabel: string
  earliestDueAt?: string
  topStores: string[]
  targetRoute: string
  tone: 'warn' | 'bad' | 'info'
}

export interface BossRoleProgressItem {
  id: string
  ownerName: string
  openCount: number
  riskCount: number
  pendingCount: number
  earliestDueAt?: string
  topSources: string[]
  targetRoute: string
  tone: 'warn' | 'bad' | 'info' | 'muted'
}

export interface BossDoneItem {
  id: string
  title: string
  actionLabel: string
  note: string
  handledAt?: string
  ownerName?: string
  sourceLabel: string
  storeName?: string
}

interface BossState {
  focus: BossFocus
  needsBossAction: RoleTodoItem[]
  highRiskReminders: BossRiskGroup[]
  roleProgress: BossRoleProgressItem[]
  doneReview: BossDoneItem[]
  loading: boolean
  actioningId: string
  error: string
  actionMessage: string
}

const emptyFocus: BossFocus = {
  needsBossActionCount: 0,
  highRiskCount: 0,
  roleWorkCount: 0,
  doneCount: 0,
  summary: '今天暂无需要老板处理的事项。',
}

export const useBossStore = defineStore('boss-dashboard', {
  state: (): BossState => ({
    focus: emptyFocus,
    needsBossAction: [],
    highRiskReminders: [],
    roleProgress: [],
    doneReview: [],
    loading: false,
    actioningId: '',
    error: '',
    actionMessage: '',
  }),
  actions: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        let dashboard: BossTodoDashboard
        try {
          dashboard = await getBossTodoDashboard({ includeDone: true, limit: 160 })
        } catch {
          dashboard = await fallbackDashboardFromBossTodos()
        }
        this.applyDashboard(dashboard)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '老板驾驶舱加载失败，请稍后重试。'
        this.applyDashboard(emptyDashboard())
      } finally {
        this.loading = false
      }
    },
    applyDashboard(dashboard: BossTodoDashboard) {
      const needsBossAction = dashboard.needsBossAction || []
      const riskGroups = dashboard.highRiskReminders || []
      const progress = dashboard.roleProgress || []
      const doneReview = dashboard.doneReview || []
      this.focus = {
        needsBossActionCount: Number(dashboard.todayFocus?.needsBossActionCount ?? dashboard.todayFocus?.needsBossCount ?? needsBossAction.length),
        highRiskCount: Number(dashboard.todayFocus?.highRiskCount ?? dashboard.todayFocus?.highRiskGroupCount ?? riskGroups.length),
        roleWorkCount: Number(dashboard.todayFocus?.roleWorkCount ?? progress.reduce((sum, item) => sum + Number(item.openCount ?? item.pendingCount ?? 0), 0)),
        doneCount: Number(dashboard.todayFocus?.doneReviewCount ?? doneReview.length),
        summary: dashboard.todayFocus?.summary || `今天老板只需要处理 ${needsBossAction.length} 件上报事项，其余岗位事项按风险和进度汇总。`,
      }
      this.needsBossAction = needsBossAction
      this.highRiskReminders = riskGroups.map((item, index) => mapRiskGroup(item, index))
      this.roleProgress = progress.map((item, index) => mapRoleProgress(item, index))
      this.doneReview = doneReview.map(mapDoneItem)
    },
    async resolve(todoId: string, note = '老板已确认处理') {
      await this.runAction(todoId, async () => {
        await resolveBossTodo(todoId, { note, attachments: [] })
        this.actionMessage = '老板事项已处理'
      })
    },
    async close(todoId: string, note = '事情没有很大影响，已默认处理。') {
      await this.runAction(todoId, async () => {
        await closeBossTodo(todoId, { note: note || '事情没有很大影响，已默认处理。', attachments: [] })
        this.actionMessage = '事项已无影响关闭'
      })
    },
    async runAction(todoId: string, action: () => Promise<void>) {
      this.actioningId = todoId
      this.error = ''
      this.actionMessage = ''
      try {
        await action()
        await this.load()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '老板事项处理失败'
        throw error
      } finally {
        this.actioningId = ''
      }
    },
  },
})

async function fallbackDashboardFromBossTodos(): Promise<BossTodoDashboard> {
  const todos = await getBossTodos({ includeDone: true, limit: 160 })
  const open = todos.items.filter((item) => !isDone(item))
  const done = todos.items.filter(isDone)
  const needsBossAction = open.filter((item) => isBossEscalation(item))
  const highRiskReminders = groupRiskItems(open.filter((item) => !isBossEscalation(item)))
  return {
    roleName: '老板',
    dataSource: todos.dataSource,
    updatedAt: todos.updatedAt,
    todayFocus: {
      needsBossActionCount: needsBossAction.length,
      highRiskCount: highRiskReminders.length,
      roleWorkCount: open.length - needsBossAction.length,
      doneReviewCount: done.length,
      summary: `今天老板只需要处理 ${needsBossAction.length} 件上报事项，其余 ${Math.max(open.length - needsBossAction.length, 0)} 条岗位事项已按风险收起展示。`,
    },
    needsBossAction,
    highRiskReminders,
    roleProgress: groupRoleProgress(open.filter((item) => !isBossEscalation(item))),
    doneReview: done.slice(0, 20),
  }
}

function emptyDashboard(): BossTodoDashboard {
  return {
    roleName: '老板',
    dataSource: 'empty',
    updatedAt: '',
    todayFocus: emptyFocus,
    needsBossAction: [],
    highRiskReminders: [],
    roleProgress: [],
    doneReview: [],
  }
}

function isBossEscalation(item: RoleTodoItem) {
  return includesText(`${item.id} ${item.sourceModule} ${item.dataSource}`, ['boss-escalation', 'todo_escalation', 'escalation', '上报'])
}

function isDone(item: RoleTodoItem) {
  return includesText(`${item.status} ${item.processStatus}`, ['DONE', 'TODO_DONE', '已处理', '已关闭', '已完成'])
}

function groupRiskItems(items: RoleTodoItem[]): NonNullable<BossTodoDashboard['highRiskReminders']> {
  const groups = new Map<string, RoleTodoItem[]>()
  for (const item of items) {
    const key = [item.sourceModule || item.dataSource || '业务风险', item.ownerName || '岗位处理中', item.storeName || item.storeId || '全部门店', item.month || '当前期间'].join('|')
    groups.set(key, [...(groups.get(key) || []), item])
  }
  return Array.from(groups.entries()).map(([key, rows]) => {
    const [sourceModule, ownerName, storeName, month] = key.split('|')
    const priority = Math.max(...rows.map((row) => Number(row.priority || 0)))
    return {
      groupKey: key,
      sourceModule,
      ownerName,
      storeName,
      month,
      count: rows.length,
      highestPriority: priority,
      earliestDueAt: rows.map((row) => row.dueAt).filter(Boolean).sort()[0],
      topStores: Array.from(new Set(rows.map((row) => row.storeName || row.storeId).filter(Boolean))).slice(0, 3) as string[],
    }
  })
}

function groupRoleProgress(items: RoleTodoItem[]): NonNullable<BossTodoDashboard['roleProgress']> {
  const groups = new Map<string, RoleTodoItem[]>()
  for (const item of items) {
    const key = item.ownerName || ownerFromSource(item.sourceModule || item.dataSource || '')
    groups.set(key, [...(groups.get(key) || []), item])
  }
  return Array.from(groups.entries()).map(([ownerName, rows]) => ({
    ownerName,
    openCount: rows.length,
    riskCount: rows.filter((row) => ['RED', 'RISK'].includes(row.status) || Number(row.priority || 0) >= 3).length,
    pendingCount: rows.filter((row) => ['PENDING', 'ORANGE'].includes(row.status)).length,
    earliestDueAt: rows.map((row) => row.dueAt).filter(Boolean).sort()[0],
    topSources: Array.from(new Set(rows.map((row) => sourceLabel(row.sourceModule || row.dataSource || '')).filter(Boolean))).slice(0, 3),
  }))
}

function mapRiskGroup(item: NonNullable<BossTodoDashboard['highRiskReminders']>[number], index: number): BossRiskGroup {
  const source = item.sourceModule || '业务风险'
  const count = Number(item.count ?? item.itemCount ?? 0)
  const highestRiskLabel = item.riskLabel || riskLabel(item.highestRisk, item.highestPriority)
  const sourceName = sourceLabel(source)
  const ownerName = item.ownerName || ownerFromSource(source)
  return {
    id: item.groupKey || `boss-risk-${index}`,
    title: `${sourceName} · ${ownerName}`,
    sourceModule: source,
    sourceLabel: sourceName,
    ownerName,
    storeName: cleanText(item.storeName || ''),
    month: item.month,
    count,
    highestRiskLabel,
    earliestDueAt: formatDateTime(item.earliestDueAt),
    topStores: item.topStores || [],
    targetRoute: routeForSource(source),
    tone: riskTone(item.highestRisk, item.highestPriority),
  }
}

function mapRoleProgress(item: NonNullable<BossTodoDashboard['roleProgress']>[number], index: number): BossRoleProgressItem {
  const ownerName = item.ownerName || `岗位 ${index + 1}`
  const riskCount = Number(item.riskCount || 0)
  const openCount = Number(item.openCount ?? item.pendingCount ?? 0)
  const topSources = (item.topSources || item.topModules || []).map(sourceLabel).filter(Boolean)
  return {
    id: `boss-progress-${ownerName}-${index}`,
    ownerName,
    openCount,
    riskCount,
    pendingCount: Number(item.pendingCount ?? Math.max(openCount - riskCount, 0)),
    earliestDueAt: formatDateTime(item.earliestDueAt),
    topSources,
    targetRoute: routeForOwner(ownerName, topSources[0] || ''),
    tone: riskCount ? 'warn' : openCount ? 'info' : 'muted',
  }
}

function mapDoneItem(item: RoleTodoItem): BossDoneItem {
  return {
    id: item.id,
    title: cleanText(item.title || '已处理事项'),
    actionLabel: doneActionLabel(item),
    note: cleanText(item.summary || item.processStatus || '老板已确认处理'),
    handledAt: formatDateTime(item.updatedAt || item.occurredAt || item.dueAt),
    ownerName: item.ownerName,
    sourceLabel: sourceLabel(item.sourceModule || item.dataSource || ''),
    storeName: cleanText(item.storeName || item.storeId || ''),
  }
}

function doneActionLabel(item: RoleTodoItem) {
  const text = `${item.status} ${item.processStatus} ${item.summary}`
  if (includesText(text, ['close', '关闭', '无影响'])) return '无影响关闭'
  if (includesText(text, ['resolve', '处理', 'done'])) return '老板已处理'
  return '已处理'
}

export function routeForSource(source: string) {
  if (includesText(source, ['exam', '考试', '培训'])) return '/exam-center'
  if (includesText(source, ['expense', '报销'])) return '/expenses'
  if (includesText(source, ['finance', 'profit', '利润'])) return '/profit-table'
  if (includesText(source, ['salary', '工资'])) return '/salary'
  if (includesText(source, ['warehouse', '仓库', '库存', '叫货', '退货'])) return '/warehouse'
  if (includesText(source, ['inspection', 'supervisor', '巡店', '整改'])) return '/inspection'
  if (includesText(source, ['store', '门店', '店长', '经营'])) return '/store-detail'
  if (includesText(source, ['operation', 'ops', '运营', '导入', '平台'])) return '/operations'
  return '/boss'
}

function routeForOwner(ownerName: string, source: string) {
  if (includesText(ownerName, ['财务']) || includesText(source, ['财务', '利润', '报销'])) return '/finance'
  if (includesText(ownerName, ['仓库']) || includesText(source, ['仓库'])) return '/warehouse'
  if (includesText(ownerName, ['督导']) || includesText(source, ['督导'])) return '/inspection'
  if (includesText(ownerName, ['店长']) || includesText(source, ['门店'])) return '/store-detail'
  if (includesText(ownerName, ['运营']) || includesText(source, ['运营'])) return '/operations'
  return '/boss'
}

function ownerFromSource(source: string) {
  if (includesText(source, ['finance', 'profit', 'expense', '报销', '利润', '工资'])) return '财务'
  if (includesText(source, ['warehouse', '仓库', '库存', '叫货', '退货'])) return '仓库管理员'
  if (includesText(source, ['inspection', 'supervisor', '巡店', '整改'])) return '督导'
  if (includesText(source, ['store', '门店', '店长'])) return '店长'
  if (includesText(source, ['operation', 'ops', '运营'])) return '运营'
  return '岗位处理中'
}

export function sourceLabel(source: string) {
  if (includesText(source, ['exam', '考试'])) return '培训考试'
  if (includesText(source, ['expense', '报销'])) return '报销栏'
  if (includesText(source, ['finance', 'profit', '利润'])) return '利润表'
  if (includesText(source, ['salary', '工资'])) return '员工工资'
  if (includesText(source, ['warehouse', '仓库', '库存', '叫货', '退货'])) return '仓库中心'
  if (includesText(source, ['inspection', 'supervisor', '巡店', '整改'])) return '督导巡店'
  if (includesText(source, ['store', '门店', '店长'])) return '门店详情'
  if (includesText(source, ['operation', 'ops', '运营', '导入'])) return '运营中心'
  if (includesText(source, ['escalation', '上报'])) return '岗位上报'
  return cleanText(source || '业务事项')
}

export function statusLabel(status?: string) {
  const map: Record<string, string> = {
    RED: '红色风险',
    ORANGE: '待处理',
    BLUE: '提醒',
    RISK: '红色风险',
    PENDING: '待处理',
    REMINDER: '提醒',
    DONE: '已处理',
    RESOLVE: '老板已处理',
    CLOSE: '无影响关闭',
    TODO_DONE: '已处理',
  }
  return map[status || ''] || cleanText(status || '待处理')
}

function riskLabel(highestRisk?: string, highestPriority?: number) {
  if (highestRisk === 'RED' || highestPriority === 3) return '严重风险'
  if (highestRisk === 'ORANGE' || highestPriority === 2) return '较高风险'
  if (highestRisk === 'BLUE' || highestPriority === 1) return '提醒'
  return '风险提醒'
}

function riskTone(highestRisk?: string, highestPriority?: number): BossRiskGroup['tone'] {
  if (highestRisk === 'RED' || highestPriority === 3) return 'bad'
  if (highestRisk === 'ORANGE' || highestPriority === 2) return 'warn'
  return 'info'
}

function formatDateTime(value?: string) {
  if (!value) return ''
  if (value.includes('T')) {
    const [date, time] = value.split('T')
    return `${date} ${time.slice(0, 5)}`
  }
  return value
}

function includesText(text: string, needles: string[]) {
  const lower = String(text || '').toLowerCase()
  return needles.some((needle) => lower.includes(needle.toLowerCase()))
}

export function cleanText(text: string) {
  return String(text || '')
    .replace(/boss-escalation/gi, '岗位上报')
    .replace(/todo_escalation/gi, '岗位上报')
    .replace(/sourceModule/gi, '来源模块')
    .replace(/todo_id/gi, '事项编号')
    .replace(/\bRESOLVE\b/g, '老板已处理')
    .replace(/\bCLOSE\b/g, '无影响关闭')
    .replace(/\bRISK\b/g, '风险')
    .replace(/\bPENDING\b/g, '待处理')
    .replace(/\bREMINDER\b/g, '提醒')
    .replace(/\bDONE\b/g, '已处理')
    .replace(/\bTODO_DONE\b/g, '已处理')
}
