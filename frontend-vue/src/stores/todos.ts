import { defineStore } from 'pinia'
import {
  getBossTodoDashboard,
  getBusinessTodos,
  getRoleTodos,
  type BossTodoDashboard,
  type BusinessTodo,
  type RoleTodoItem,
} from '../api/todos'
import { getWarehouseOverview, type WarehouseAlert, type WarehouseRequisition } from '../api/warehouse'

export interface TodoStat {
  label: string
  value: number
  tone?: 'primary' | 'warn' | 'bad' | 'info' | 'muted'
}

export interface TodoReminder {
  id: string
  title: string
  description: string
  sourceModule: string
  sourceLabel: string
  storeName?: string
  deadline?: string
  statusLabel: string
  actionLabel: string
  targetRoute: string
  tone?: 'warn' | 'bad' | 'info' | 'ok' | 'muted'
  workflowTodoId?: string
  workflowStatus?: BusinessTodo['status']
  workflowCanTransition?: boolean
}

export interface TodoWorkbench {
  roleName: string
  headline: string
  stats: TodoStat[]
  attentionItems: TodoReminder[]
  riskItems: TodoReminder[]
  progressItems: TodoReminder[]
  doneItems: TodoReminder[]
}

const emptyWorkbench: TodoWorkbench = {
  roleName: '当前角色',
  headline: '今天暂时没有需要关注的提醒。',
  stats: [],
  attentionItems: [],
  riskItems: [],
  progressItems: [],
  doneItems: [],
}

export const useTodoStore = defineStore('todos', {
  state: () => ({
    workbench: emptyWorkbench as TodoWorkbench,
    loading: false,
    error: '',
  }),
  actions: {
    async loadForRole(role: string) {
      this.loading = true
      this.error = ''
      try {
        this.workbench = await loadWorkbenchForRole(role)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '今日待办加载失败'
        this.workbench = fallbackWorkbench(role)
      } finally {
        this.loading = false
      }
    },
    clear() {
      this.workbench = emptyWorkbench
      this.error = ''
    },
  },
})

async function loadWorkbenchForRole(role: string): Promise<TodoWorkbench> {
  const [workbench, workflowTodos] = await Promise.all([
    loadLegacyWorkbenchForRole(role),
    getBusinessTodos().catch((error) => {
      console.error('[Todo] business workflow load failed', error)
      return [] as BusinessTodo[]
    }),
  ])
  return mergeBusinessWorkflow(workbench, workflowTodos)
}

async function loadLegacyWorkbenchForRole(role: string): Promise<TodoWorkbench> {
  if (role === 'STORE_MANAGER') return storeManagerWorkbench()
  if (role === 'WAREHOUSE') return warehouseWorkbench()
  if (role === 'BOSS' || role === 'OWNER') return bossWorkbench()
  if (role === 'FINANCE') return roleTodoWorkbench('FINANCE', 'finance')
  if (role === 'SUPERVISOR') return roleTodoWorkbench('SUPERVISOR', 'supervisor')
  if (role === 'OPERATIONS' || role === 'OPS') return roleTodoWorkbench('OPERATIONS', 'operations')
  return fallbackWorkbench(role)
}

async function storeManagerWorkbench(): Promise<TodoWorkbench> {
  const [overview, todos] = await Promise.all([
    getWarehouseOverview(),
    getRoleTodos('store-manager').catch(() => null),
  ])
  const shipped = overview.requisitions.filter((row) => row.status === 'SHIPPED')
  const todoItems = todos?.items || []
  const openTodos = todoItems.filter((item) => !isDoneTodo(item))
  const doneTodos = todoItems.filter(isDoneTodo).slice(0, 10)
  const receiptItems = shipped.map(storeReceiptReminder)
  const attentionItems = [...receiptItems, ...openTodos.map(mapRoleTodo)]
  const businessItems = attentionItems.filter((item) => item.targetRoute !== '/warehouse')
  return {
    roleName: '店长',
    headline: attentionItems.length
      ? `今天有 ${attentionItems.length} 件本店事项需要关注，请进入对应页面处理。`
      : '今天暂时没有需要处理的本店事项。',
    stats: [
      { label: '待确认收货', value: shipped.length, tone: shipped.length ? 'warn' : 'muted' },
      { label: '本店经营提醒', value: businessItems.length, tone: businessItems.length ? 'warn' : 'muted' },
      { label: '巡店整改', value: openTodos.filter((item) => includesAny(item, ['巡店', '整改', 'inspection'])).length, tone: 'info' },
      { label: '报销补资料', value: openTodos.filter((item) => includesAny(item, ['报销', 'expense'])).length, tone: 'info' },
    ],
    attentionItems,
    riskItems: businessItems,
    progressItems: [],
    doneItems: doneTodos.map(mapRoleTodo),
  }
}

async function warehouseWorkbench(): Promise<TodoWorkbench> {
  const [overview, todos] = await Promise.all([
    getWarehouseOverview(),
    getRoleTodos('warehouse').catch(() => null),
  ])
  const pending = overview.requisitions.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status))
  const attentionItems = pending.map(warehouseRequisitionReminder)
  const riskItems = overview.alerts.map(warehouseAlertReminder)
  const todoItems = todos?.items || []
  const openTodos = todoItems.filter((item) => !isDoneTodo(item)).map(mapRoleTodo)
  const doneTodos = todoItems.filter(isDoneTodo).slice(0, 10).map(mapRoleTodo)
  return {
    roleName: '仓库管理员',
    headline: `今天有 ${pending.length} 张门店叫货待处理，${overview.alerts.length} 个库存提醒需要关注。`,
    stats: [
      { label: '门店叫货待处理', value: pending.length, tone: pending.length ? 'warn' : 'muted' },
      { label: '库存不足', value: overview.alerts.length, tone: overview.alerts.length ? 'bad' : 'muted' },
      { label: '退货待入库', value: openTodos.filter((item) => includesText(`${item.title} ${item.sourceModule}`, ['退货', 'return'])).length, tone: 'info' },
      { label: '已处理', value: doneTodos.length, tone: 'info' },
    ],
    attentionItems: [...attentionItems, ...openTodos.filter((item) => item.targetRoute !== '/warehouse' || !item.title.includes('门店叫货待处理'))],
    riskItems,
    progressItems: [],
    doneItems: doneTodos,
  }
}

async function bossWorkbench(): Promise<TodoWorkbench> {
  try {
    const dashboard = await getBossTodoDashboard()
    return mapBossDashboard(dashboard)
  } catch {
    const todos = await getRoleTodos('finance')
    const riskItems = todos.items.filter((item) => item.status !== 'DONE').slice(0, 12).map(mapRoleTodo)
    return {
      roleName: '老板',
      headline: '今日风险提醒已汇总，请进入对应业务页面处理。',
      stats: [
        { label: '需要我处理', value: 0, tone: 'muted' },
        { label: '高风险提醒', value: riskItems.length, tone: riskItems.length ? 'bad' : 'muted' },
        { label: '各岗位处理中', value: riskItems.length, tone: 'info' },
        { label: '已处理复盘', value: 0, tone: 'muted' },
      ],
      attentionItems: [],
      riskItems,
      progressItems: [],
      doneItems: [],
    }
  }
}

async function roleTodoWorkbench(role: string, endpoint: 'finance' | 'supervisor' | 'operations'): Promise<TodoWorkbench> {
  const todos = await getRoleTodos(endpoint)
  const open = todos.items.filter((item) => item.status !== 'DONE')
  const risks = open.filter((item) => item.status === 'RISK').map(mapRoleTodo)
  const attention = open.filter((item) => item.status !== 'RISK').map(mapRoleTodo)
  const done = todos.items.filter((item) => item.status === 'DONE').slice(0, 10).map(mapRoleTodo)
  if (role === 'FINANCE') {
    const expenseCount = open.filter((item) => includesAny(item, ['报销', 'expense'])).length
    const profitCount = open.filter((item) => includesAny(item, ['利润', 'profit'])).length
    return {
      roleName: '财务',
      headline: `今天有 ${expenseCount} 条报销提醒，${profitCount} 条利润异常需要核对。`,
      stats: [
        { label: '报销待审核', value: expenseCount, tone: expenseCount ? 'warn' : 'muted' },
        { label: '利润异常', value: profitCount, tone: profitCount ? 'bad' : 'muted' },
        { label: '工资待核对', value: 0, tone: 'muted' },
        { label: '已上报老板', value: open.filter((item) => item.escalatedToBoss).length, tone: 'info' },
      ],
      attentionItems: attention,
      riskItems: risks,
      progressItems: [],
      doneItems: done,
    }
  }
  if (role === 'SUPERVISOR') {
    return {
      roleName: '督导',
      headline: open.length ? `今天有 ${open.length} 条巡店和整改提醒。` : '今天暂时没有巡店整改提醒。',
      stats: [
        { label: '巡店整改', value: open.length, tone: open.length ? 'warn' : 'muted' },
        { label: '高风险', value: risks.length, tone: risks.length ? 'bad' : 'muted' },
        { label: '待复查', value: attention.length, tone: 'info' },
        { label: '已处理', value: done.length, tone: 'muted' },
      ],
      attentionItems: attention,
      riskItems: risks,
      progressItems: [],
      doneItems: done,
    }
  }
  return {
    roleName: '运营',
    headline: open.length ? `今天有 ${open.length} 条数据和平台配置提醒。` : '今天暂时没有运营提醒。',
    stats: [
      { label: '数据提醒', value: open.length, tone: open.length ? 'warn' : 'muted' },
      { label: '高风险', value: risks.length, tone: risks.length ? 'bad' : 'muted' },
      { label: '待处理', value: attention.length, tone: 'info' },
      { label: '已处理', value: done.length, tone: 'muted' },
    ],
    attentionItems: attention,
    riskItems: risks,
    progressItems: [],
    doneItems: done,
  }
}

function mapBossDashboard(dashboard: BossTodoDashboard): TodoWorkbench {
  const needsBoss = dashboard.needsBossAction || []
  const riskGroups = dashboard.highRiskReminders || []
  const progress = dashboard.roleProgress || []
  const done = dashboard.doneReview || []
  return {
    roleName: '老板',
    headline: dashboard.todayFocus?.summary || `今天有 ${needsBoss.length} 件事项需要老板决策。`,
    stats: [
      { label: '需要我处理', value: dashboard.todayFocus?.needsBossActionCount || dashboard.todayFocus?.needsBossCount || needsBoss.length, tone: needsBoss.length ? 'warn' : 'muted' },
      { label: '高风险提醒', value: dashboard.todayFocus?.highRiskGroupCount || riskGroups.length, tone: riskGroups.length ? 'bad' : 'muted' },
      { label: '各岗位处理中', value: dashboard.todayFocus?.roleWorkCount || 0, tone: 'info' },
      { label: '已处理复盘', value: done.length, tone: 'muted' },
    ],
    attentionItems: needsBoss.map(mapBossActionReminder),
    riskItems: riskGroups.map((group, index) => ({
      id: group.groupKey || `boss-risk-${index}`,
      title: `${group.riskLabel || riskText(group.highestRisk, group.highestPriority) || '高风险提醒'}：${sourceModuleLabel(group.sourceModule || '业务事项')}`,
      description: `涉及 ${group.count ?? group.itemCount ?? 0} 条事项，负责岗位：${group.ownerName || '待分配'}。${(group.topStores || []).join('，')}`,
      sourceModule: group.sourceModule || 'risk',
      sourceLabel: sourceModuleLabel(group.sourceModule || '业务风险'),
      storeName: group.storeName,
      deadline: formatDeadline(group.earliestDueAt),
      statusLabel: group.riskLabel || riskText(group.highestRisk, group.highestPriority) || '高风险提醒',
      actionLabel: '查看来源页面',
      targetRoute: '/boss?section=risks',
      tone: riskTone(group.highestRisk, group.highestPriority),
    })),
    progressItems: progress.map((group, index) => ({
      id: `boss-progress-${group.ownerName || index}`,
      title: `${group.ownerName || '岗位'}处理中`,
      description: `还有 ${group.openCount ?? group.pendingCount ?? 0} 条事项处理中。${(group.topSources || group.topModules || []).map(sourceModuleLabel).join('，')}`,
      sourceModule: 'role_progress',
      sourceLabel: '岗位进度',
      deadline: formatDeadline(group.earliestDueAt),
      statusLabel: `${group.openCount ?? group.pendingCount ?? 0} 条处理中`,
      actionLabel: '查看提醒',
      targetRoute: '/boss?section=progress',
      tone: group.riskCount ? 'warn' : 'info',
    })),
    doneItems: done.map((item) => ({ ...mapRoleTodo(item), targetRoute: '/boss?section=done', actionLabel: '查看复盘' })),
  }
}

function mapBossActionReminder(item: RoleTodoItem): TodoReminder {
  return {
    ...mapRoleTodo(item),
    title: cleanBusinessText(item.title || '岗位上报事项'),
    description: item.summary || '该事项由岗位上报，需要老板确认处理方向。',
    sourceLabel: '岗位上报',
    statusLabel: statusLabel(item),
    actionLabel: '去老板驾驶舱处理',
    targetRoute: '/boss?section=needs-action',
    tone: toneForStatus(item.status),
  }
}

function storeReceiptReminder(row: WarehouseRequisition): TodoReminder {
  return {
    id: `store-receipt-${row.id}`,
    title: '待确认收货：仓库已发货，请确认本店收货',
    description: `叫货单 ${row.id} 已发货，请到仓库中心核对商品和数量。`,
    sourceModule: 'warehouse_receipt',
    sourceLabel: '仓库中心',
    storeName: row.storeName || row.storeId,
    deadline: '今天内',
    statusLabel: '待确认收货',
    actionLabel: '去仓库中心处理',
    targetRoute: '/warehouse',
    tone: 'warn',
  }
}

function warehouseRequisitionReminder(row: WarehouseRequisition): TodoReminder {
  const label = row.status === 'SUBMITTED' ? '门店叫货待审核' : '待仓库发货'
  return {
    id: `warehouse-requisition-${row.id}`,
    title: `${label}：${row.storeName || row.storeId}`,
    description: `门店提交了 ${row.lines.length} 项叫货，请到仓库中心审核并发货。`,
    sourceModule: 'warehouse_requisition',
    sourceLabel: '仓库中心',
    storeName: row.storeName || row.storeId,
    deadline: formatDeadline(row.submittedAt),
    statusLabel: label,
    actionLabel: '去仓库中心处理',
    targetRoute: '/warehouse?tab=requisitions',
    tone: 'warn',
  }
}

function warehouseAlertReminder(alert: WarehouseAlert): TodoReminder {
  return {
    id: `warehouse-alert-${alert.itemId}-${alert.type}`,
    title: `库存提醒：${alert.itemName}`,
    description: alert.message,
    sourceModule: 'warehouse_stock_alert',
    sourceLabel: '仓库中心',
    statusLabel: '库存提醒',
    actionLabel: '查看库存预警',
    targetRoute: '/warehouse?tab=alerts',
    tone: alert.severity === 'HIGH' ? 'bad' : 'warn',
  }
}

function mergeBusinessWorkflow(workbench: TodoWorkbench, workflowTodos: BusinessTodo[]): TodoWorkbench {
  if (!workflowTodos.length) return workbench

  const open = workflowTodos.filter((item) => !['COMPLETED', 'REJECTED'].includes(item.status))
  const closed = workflowTodos.filter((item) => ['COMPLETED', 'REJECTED'].includes(item.status))
  const retainedAttention = workbench.attentionItems.filter((item) => !isLegacyFinanceReminder(item))
  const retainedRisk = workbench.riskItems.filter((item) => !isLegacyFinanceReminder(item))
  const retainedProgress = workbench.progressItems.filter((item) => !isLegacyFinanceReminder(item))
  const retainedDone = workbench.doneItems.filter((item) => !isLegacyFinanceReminder(item))
  const mappedPending = open.filter((item) => item.status === 'PENDING').map(mapBusinessTodo)
  const mappedReview = open.filter((item) => item.status === 'PENDING_REVIEW').map(mapBusinessTodo)
  const mappedProgress = open.filter((item) => item.status === 'IN_PROGRESS').map(mapBusinessTodo)
  const mappedRisk = mappedPending.filter((item) => item.tone === 'bad' || item.tone === 'warn')
  const mappedAttention = [
    ...mappedReview,
    ...mappedPending.filter((item) => item.tone !== 'bad' && item.tone !== 'warn'),
  ]
  const pendingReviewCount = open.filter((item) => item.status === 'PENDING_REVIEW').length

  return {
    ...workbench,
    headline: open.length
      ? `当前有 ${open.length} 条待办，其中 ${pendingReviewCount} 条等待复核。`
      : workbench.headline,
    stats: mergeWorkflowStats(workbench, workflowTodos),
    attentionItems: [...mappedAttention, ...retainedAttention],
    riskItems: [...mappedRisk, ...retainedRisk],
    progressItems: [...mappedProgress, ...retainedProgress],
    doneItems: [...closed.map(mapBusinessTodo), ...retainedDone].slice(0, 20),
  }
}

function mergeWorkflowStats(workbench: TodoWorkbench, workflowTodos: BusinessTodo[]) {
  const open = workflowTodos.filter((item) => !['COMPLETED', 'REJECTED'].includes(item.status))
  const closed = workflowTodos.filter((item) => ['COMPLETED', 'REJECTED'].includes(item.status))
  if (workbench.roleName === '财务') {
    const missingCount = open.filter((item) => item.ruleCode === 'PROFIT_DATA_MISSING').length
    const profitRiskCount = open.filter((item) => item.sourceModule === 'profit_entry' && item.ruleCode !== 'PROFIT_DATA_MISSING').length
    const expenseCount = open.filter((item) => item.sourceModule === 'expense_claim').length
    const salaryCount = open.filter((item) => item.sourceModule === 'salary_record').length
    return [
      { label: '数据缺失', value: missingCount, tone: missingCount ? 'warn' as const : 'muted' as const },
      { label: '利润异常', value: profitRiskCount, tone: profitRiskCount ? 'bad' as const : 'muted' as const },
      { label: '报销待审核', value: expenseCount, tone: expenseCount ? 'warn' as const : 'muted' as const },
      { label: '工资待核对', value: salaryCount, tone: salaryCount ? 'warn' as const : 'muted' as const },
    ]
  }
  if (workbench.roleName === '店长') {
    const businessCount = open.filter((item) => item.sourceModule === 'profit_entry').length
    return workbench.stats.map((stat) => stat.label === '本店经营提醒'
      ? { ...stat, value: businessCount, tone: businessCount ? 'warn' as const : 'muted' as const }
      : stat)
  }
  if (workbench.roleName === '老板') {
    const actionCount = open.filter((item) => item.status === 'PENDING_REVIEW' || item.assigneeRole === '老板').length
    const riskCount = open.filter((item) => item.priority >= 3).length
    const progressCount = open.filter((item) => item.status === 'IN_PROGRESS').length
    return [
      { label: '需要我处理', value: actionCount, tone: actionCount ? 'warn' as const : 'muted' as const },
      { label: '高风险提醒', value: riskCount, tone: riskCount ? 'bad' as const : 'muted' as const },
      { label: '各岗位处理中', value: progressCount, tone: progressCount ? 'info' as const : 'muted' as const },
      { label: '已处理复盘', value: closed.length, tone: closed.length ? 'info' as const : 'muted' as const },
    ]
  }
  return workbench.stats
}

function mapBusinessTodo(item: BusinessTodo): TodoReminder {
  const statusLabel = item.statusLabel || workflowStatusLabel(item.status)
  const tone: TodoReminder['tone'] = item.status === 'REJECTED'
    ? 'muted'
    : item.status === 'COMPLETED'
      ? 'ok'
      : item.priority >= 3
        ? 'bad'
        : item.priority >= 2
          ? 'warn'
          : 'info'
  const sourceLabel = item.sourceModule === 'expense_claim' ? '报销栏' : '经营数据'
  return {
    id: `workflow-${item.id}`,
    title: item.title,
    description: item.summary,
    sourceModule: item.sourceModule,
    sourceLabel,
    storeName: item.storeName || item.storeId,
    deadline: item.month || '今天内',
    statusLabel,
    actionLabel: item.status === 'PENDING_REVIEW' ? '去复核' : '去业务页处理',
    targetRoute: item.targetRoute || '/data-entry',
    tone,
    workflowTodoId: item.id,
    workflowStatus: item.status,
    workflowCanTransition: item.canTransition,
  }
}

function isLegacyFinanceReminder(item: TodoReminder) {
  return includesText(`${item.sourceModule} ${item.sourceLabel} ${item.id}`, ['profit', '利润', 'expense', '报销', 'salary', '工资', 'finance'])
}

function workflowStatusLabel(status: BusinessTodo['status']) {
  const labels: Record<BusinessTodo['status'], string> = {
    PENDING: '待处理',
    IN_PROGRESS: '处理中',
    PENDING_REVIEW: '待复核',
    COMPLETED: '已完成',
    REJECTED: '已驳回',
  }
  return labels[status]
}

function mapRoleTodo(item: RoleTodoItem): TodoReminder {
  return {
    id: item.id,
    title: businessTitle(item),
    description: item.summary || '请进入对应业务模块查看并处理。',
    sourceModule: item.sourceModule || item.dataSource || '',
    sourceLabel: sourceLabelFor(item),
    storeName: item.storeName || item.storeId,
    deadline: formatDeadline(item.dueAt),
    statusLabel: statusLabel(item),
    actionLabel: actionLabelFor(item),
    targetRoute: targetRouteFor(`${item.sourceModule || ''} ${item.dataSource || ''} ${item.id}`),
    tone: toneForStatus(item.status),
  }
}

function businessTitle(item: RoleTodoItem) {
  if (item.id?.startsWith('store-receipt-')) return '待确认收货：仓库已发货，请确认本店收货'
  return cleanBusinessText(item.title || '待关注事项')
}

function sourceLabelFor(item: RoleTodoItem) {
  const text = `${item.sourceModule || ''} ${item.dataSource || ''} ${item.id || ''}`
  if (includesText(text, ['boss-escalation', 'todo_escalation', 'escalation'])) return '岗位上报'
  if (includesText(text, ['报销', 'expense'])) return '报销栏'
  if (includesText(text, ['仓库', '库存', '叫货', '退货', 'warehouse'])) return '仓库中心'
  if (includesText(text, ['finance-data', 'data-check', '数据核对'])) return '财务数据核对'
  if (includesText(text, ['利润', 'profit'])) return '利润表'
  if (includesText(text, ['工资', 'salary'])) return '员工工资'
  if (includesText(text, ['巡店', '整改', 'inspection'])) return '督导巡店'
  if (includesText(text, ['数据', '导入', 'operation', '平台', '账号', '迁移', 'legacy'])) return '运营中心'
  return safeBusinessLabel(item.sourceModule || item.dataSource || '') || '业务提醒'
}

function actionLabelFor(item: RoleTodoItem) {
  const route = targetRouteFor(`${item.sourceModule || ''} ${item.dataSource || ''} ${item.id}`)
  if (route.startsWith('/warehouse')) return '去仓库中心处理'
  if (route === '/expenses') return '去报销栏处理'
  if (route === '/profit-table') return '去利润表核对'
  if (route === '/salary') return '去员工工资核对'
  if (route === '/finance-data-check') return '去财务数据核对'
  if (route.startsWith('/inspection')) return '去督导工作台处理'
  if (route === '/store-detail') return '查看本店数据'
  if (route.startsWith('/operations')) return '去运营中心处理'
  return '查看来源页面'
}

function targetRouteFor(source: string) {
  if (includesText(source, ['退货', 'return'])) return '/warehouse?tab=returns'
  if (includesText(source, ['库存预警', '库存不足', '低库存', 'stock_alert', 'alert'])) return '/warehouse?tab=alerts'
  if (includesText(source, ['叫货', 'requisition'])) return '/warehouse?tab=requisitions'
  if (includesText(source, ['出库', '流水', 'movement'])) return '/warehouse?tab=movements'
  if (includesText(source, ['采购', '入库', 'purchase', 'receipt'])) return '/warehouse?tab=purchase'
  if (includesText(source, ['warehouse', '仓库', '库存'])) return '/warehouse'
  if (includesText(source, ['expense', '报销'])) return '/expenses'
  if (includesText(source, ['salary', '工资'])) return '/salary'
  if (includesText(source, ['finance-data', 'data-check', '数据核对'])) return '/finance-data-check'
  if (includesText(source, ['profit', '利润'])) return '/profit-table'
  if (includesText(source, ['inspection', '巡店', '整改', '复查', '现场问题'])) {
    if (includesText(source, ['boss-escalation', 'todo_escalation', 'escalation', '上报老板'])) return '/inspection?tab=escalated'
    if (includesText(source, ['整改复查', '待复查', '复查', 'rectification', 'review'])) return '/inspection?tab=reviews'
    if (includesText(source, ['巡店任务', '待巡店', 'task', 'inspection-task', 'inspection_task'])) return '/inspection?tab=tasks'
    if (includesText(source, ['巡店异常', '现场问题', '异常', '问题', 'risk', 'issue'])) return '/inspection?tab=issues'
    return '/inspection'
  }
  if (includesText(source, ['store', '门店', '本店'])) return '/store-detail'
  if (includesText(source, ['饿了么', 'eleme', '订单营业额'])) return '/operations?tab=eleme'
  if (includesText(source, ['培训', '新人培训', 'train'])) return '/operations?tab=training'
  if (includesText(source, ['考试', 'exam'])) return '/exam-center'
  if (includesText(source, ['盘存', '盘点', 'inventory-check', 'inventory_check'])) return '/operations?tab=inventory-check'
  if (includesText(source, ['出肉率', '单杯用量', '采购测算', 'analysis'])) return '/operations?tab=analysis'
  if (includesText(source, ['迁移', 'legacy', '浏览器', 'localStorage'])) return '/operations?tab=migration'
  if (includesText(source, ['数据异常', 'data-health', '数据健康', '数据核对'])) return '/operations?tab=data-health'
  if (includesText(source, ['数据导入', '导入', 'import'])) return '/operations?tab=imports'
  if (includesText(source, ['平台', '账号', '登录', '同步', 'platform'])) return '/operations?tab=platform'
  if (includesText(source, ['operation', '运营'])) return '/operations'
  return '/assistant'
}

function statusLabel(item: RoleTodoItem) {
  if (item.id?.startsWith('store-receipt-')) return '待确认收货'
  if (item.processStatus && !includesText(item.processStatus, ['RISK', 'PENDING', 'DONE', 'REMINDER'])) {
    return safeBusinessLabel(item.processStatus) || '已处理'
  }
  const map: Record<string, string> = {
    RED: '红色风险',
    ORANGE: '橙色待处理',
    BLUE: '蓝色提醒',
    RISK: '风险提醒',
    PENDING: '待处理',
    REMINDER: '提醒',
    DONE: '已处理',
  }
  return map[item.status] || '待关注'
}

function toneForStatus(status: string): TodoReminder['tone'] {
  if (status === 'RED') return 'bad'
  if (status === 'ORANGE') return 'warn'
  if (status === 'BLUE') return 'info'
  if (status === 'RISK') return 'bad'
  if (status === 'PENDING') return 'warn'
  if (status === 'DONE') return 'ok'
  return 'info'
}

function formatDeadline(value?: string) {
  if (!value) return '今天内'
  if (value.includes('T')) {
    const [date, time] = value.split('T')
    return `${date} ${time.slice(0, 5)}`
  }
  return value
}

function includesAny(item: RoleTodoItem, needles: string[]) {
  return includesText(`${item.id} ${item.title} ${item.summary} ${item.sourceModule} ${item.dataSource}`, needles)
}

function includesText(text: string, needles: string[]) {
  const lower = text.toLowerCase()
  return needles.some((needle) => lower.includes(needle.toLowerCase()))
}

function fallbackWorkbench(role: string): TodoWorkbench {
  const roleNameMap: Record<string, string> = {
    BOSS: '老板',
    OWNER: '老板',
    FINANCE: '财务',
    WAREHOUSE: '仓库管理员',
    STORE_MANAGER: '店长',
    SUPERVISOR: '督导',
    OPERATIONS: '运营',
    OPS: '运营',
  }
  return {
    roleName: roleNameMap[role] || '当前角色',
    headline: '今日待办暂时无法读取，请稍后刷新。',
    stats: [
      { label: '需要我关注', value: 0, tone: 'muted' },
      { label: '风险提醒', value: 0, tone: 'muted' },
      { label: '已处理', value: 0, tone: 'muted' },
      { label: '跳转入口', value: 0, tone: 'muted' },
    ],
    attentionItems: [],
    riskItems: [],
    progressItems: [],
    doneItems: [],
  }
}

function isDoneTodo(item: RoleTodoItem) {
  return includesText(`${item.status} ${item.processStatus}`, ['DONE', 'TODO_DONE', '已处理', '已完成', '已关闭'])
}

function sourceModuleLabel(source: string) {
  if (includesText(source, ['warehouse', '仓库', '库存', '叫货', '退货'])) return '仓库中心'
  if (includesText(source, ['finance', 'profit', '利润'])) return '财务利润'
  if (includesText(source, ['expense', '报销'])) return '报销栏'
  if (includesText(source, ['inspection', 'supervisor', '巡店', '整改'])) return '督导巡店'
  if (includesText(source, ['operation', '运营', '导入', 'platform', '平台', '账号', '迁移', 'legacy'])) return '运营中心'
  if (includesText(source, ['escalation', '上报'])) return '岗位上报'
  return cleanBusinessText(source)
}

function riskText(highestRisk?: string, highestPriority?: number) {
  if (highestRisk === 'RED' || highestPriority === 3) return '红色风险'
  if (highestRisk === 'ORANGE' || highestPriority === 2) return '橙色待处理'
  if (highestRisk === 'BLUE' || highestPriority === 1) return '蓝色提醒'
  return ''
}

function riskTone(highestRisk?: string, highestPriority?: number): TodoReminder['tone'] {
  if (highestRisk === 'RED' || highestPriority === 3) return 'bad'
  if (highestRisk === 'ORANGE' || highestPriority === 2) return 'warn'
  if (highestRisk === 'BLUE' || highestPriority === 1) return 'info'
  return 'warn'
}

function cleanBusinessText(text: string) {
  return text
    .replace(/boss-escalation/gi, '岗位上报')
    .replace(/todo_escalation/gi, '岗位上报')
    .replace(/legacy_kv/gi, '旧数据迁移')
    .replace(/localStorage/gi, '浏览器本地数据')
    .replace(/\bSUBMITTED\b/g, '待仓库审核')
    .replace(/\bAPPROVED\b/g, '待仓库发货')
    .replace(/\bSHIPPED\b/g, '待门店收货')
    .replace(/\bRECEIVED\b/g, '门店已收货')
    .replace(/\bREJECTED\b/g, '已驳回')
    .replace(/\bTODO_DONE\b/g, '已处理')
}

function safeBusinessLabel(text: string) {
  const cleaned = cleanBusinessText(text).trim()
  if (!cleaned) return ''
  if (/[�]/.test(cleaned)) return ''
  if (/\?{2,}/.test(cleaned)) return ''
  if (/codex/i.test(cleaned) && /[?？]/.test(cleaned)) return ''
  if (/[ÃÂâèæå]/.test(cleaned)) return ''
  return cleaned
}
