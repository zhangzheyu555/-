import { defineStore } from 'pinia'
import {
  createInspectionRecord,
  escalateSupervisorTodo,
  getInspectionRecords,
  getSupervisorTodos,
  resolveSupervisorTodo,
  type InspectionRecord,
  type InspectionRecordPayload,
  type InspectionSummary,
  type InspectionWorkbench,
} from '../api/inspection'
import type { RoleTodoItem } from '../api/todos'
import { inspectionScoreView, INSPECTION_MAX_SCORE } from '../utils/inspectionScore'

export type InspectionTab = 'overview' | 'tasks' | 'records' | 'issues' | 'reviews' | 'escalated' | 'done'

interface InspectionState {
  summary: InspectionSummary
  todoItems: RoleTodoItem[]
  tasks: RoleTodoItem[]
  records: InspectionRecord[]
  issues: RoleTodoItem[]
  reviews: RoleTodoItem[]
  escalatedItems: RoleTodoItem[]
  doneItems: RoleTodoItem[]
  activeTab: InspectionTab
  draft: InspectionRecordPayload
  loading: boolean
  actioningId: string
  error: string
  actionMessage: string
}

const emptySummary: InspectionSummary = {
  todayTaskCount: 0,
  reviewCount: 0,
  issueCount: 0,
  escalatedCount: 0,
  headline: '今天暂时没有必须处理的巡店事项。',
}

export const useInspectionStore = defineStore('inspection', {
  state: (): InspectionState => ({
    summary: emptySummary,
    todoItems: [],
    tasks: [],
    records: [],
    issues: [],
    reviews: [],
    escalatedItems: [],
    doneItems: [],
    activeTab: 'overview',
    draft: emptyDraft(),
    loading: false,
    actioningId: '',
    error: '',
    actionMessage: '',
  }),
  actions: {
    setTab(tab: string | null | undefined) {
      const normalized = String(tab || 'overview') as InspectionTab
      this.activeTab = ['overview', 'tasks', 'records', 'issues', 'reviews', 'escalated', 'done'].includes(normalized)
        ? normalized
        : 'overview'
    },
    prepareRecordFromTodo(item: RoleTodoItem) {
      this.draft.storeId = item.storeId || this.draft.storeId
      this.draft.inspector = item.ownerName || this.draft.inspector
      this.draft.issueDescription = item.summary || item.title || this.draft.issueDescription
      this.activeTab = 'records'
    },
    clearDraft() {
      this.draft = emptyDraft()
      this.actionMessage = '巡店记录表单已清空'
    },
    async load() {
      this.loading = true
      this.error = ''
      try {
        const data = await aggregateInspectionWorkbench()
        this.applyWorkbench(data)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '督导工作台加载失败'
        this.applyWorkbench(emptyWorkbench())
      } finally {
        this.loading = false
      }
    },
    applyWorkbench(data: InspectionWorkbench) {
      this.summary = data.summary
      this.todoItems = data.todoItems
      this.tasks = data.tasks
      this.records = data.records
      this.issues = data.issues
      this.reviews = data.reviews
      this.escalatedItems = data.escalatedItems
      this.doneItems = data.doneItems
    },
    async submitRecord() {
      const payload = normalizeRecordPayload(this.draft)
      await this.runAction('inspection-record-create', async () => {
        await createInspectionRecord(payload)
        this.actionMessage = '巡店记录已保存'
        this.draft = emptyDraft()
      })
    },
    async completeTodo(item: RoleTodoItem, note: string) {
      await this.runAction(item.id, async () => {
        await resolveSupervisorTodo(item.id, note || '督导已在 Vue3 工作台处理完成')
        this.actionMessage = '事项已标记处理'
      })
    },
    async passReview(item: RoleTodoItem, note: string) {
      await this.runAction(item.id, async () => {
        await resolveSupervisorTodo(item.id, note || '督导复查通过，整改已完成')
        this.actionMessage = '整改复查已通过'
      })
    },
    async continueRectification(item: RoleTodoItem, note: string) {
      await this.runAction(item.id, async () => {
        await resolveSupervisorTodo(item.id, note || '督导要求继续整改，请店长补充整改结果')
        this.actionMessage = '已记录继续整改意见'
      })
    },
    async escalate(item: RoleTodoItem, reason: string) {
      await this.runAction(item.id, async () => {
        await escalateSupervisorTodo(item.id, reason || '督导判断该问题需要老板确认')
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
        this.error = error instanceof Error ? error.message : '操作失败'
        throw error
      } finally {
        this.actioningId = ''
      }
    },
  },
})

async function aggregateInspectionWorkbench(): Promise<InspectionWorkbench> {
  const [todos, records] = await Promise.all([
    getSupervisorTodos().catch(() => ({ items: [] as RoleTodoItem[] })),
    getInspectionRecords().catch(() => [] as InspectionRecord[]),
  ])
  const todoItems = todos.items || []
  const open = todoItems.filter((item) => !isDone(item))
  const doneItems = todoItems.filter(isDone).slice(0, 20)
  const tasks = open.filter(isTaskTodo)
  const reviews = open.filter(isReviewTodo)
  const issues = [
    ...open.filter(isIssueTodo),
    ...records.filter((record) => {
      const score = inspectionScoreView(record)
      return score.valid && score.passed === false
    }).map(recordIssueTodo),
  ]
  const escalatedItems = open.filter((item) => item.escalatedToBoss || includesAny(item, ['上报老板', 'boss-escalation', 'escalation']))
  const summary = {
    todayTaskCount: tasks.length,
    reviewCount: reviews.length,
    issueCount: issues.length,
    escalatedCount: escalatedItems.length,
    headline: `今天有 ${tasks.length} 个门店需要巡检，${reviews.length} 条整改待复查，${escalatedItems.length} 条问题已上报老板。`,
  }
  return {
    summary,
    todoItems: open,
    tasks,
    records,
    issues,
    reviews,
    escalatedItems,
    doneItems,
  }
}

function emptyWorkbench(): InspectionWorkbench {
  return {
    summary: emptySummary,
    todoItems: [],
    tasks: [],
    records: [],
    issues: [],
    reviews: [],
    escalatedItems: [],
    doneItems: [],
  }
}

function emptyDraft(): InspectionRecordPayload {
  return {
    storeId: '',
    inspectionDate: new Date().toISOString().slice(0, 10),
    inspector: '',
    brand: '',
    fullScore: INSPECTION_MAX_SCORE,
    score: INSPECTION_MAX_SCORE,
    hygieneScore: undefined,
    serviceScore: undefined,
    productScore: undefined,
    displayScore: undefined,
    issueDescription: '',
    rectificationRequirement: '',
    deductionsJson: '[]',
    redlinesJson: '[]',
    photosJson: '[]',
    note: '',
  }
}

function normalizeRecordPayload(draft: InspectionRecordPayload): InspectionRecordPayload {
  if (!draft.storeId?.trim()) throw new Error('请填写巡店门店')
  if (!draft.inspectionDate?.trim()) throw new Error('请选择巡店日期')
  const fullScore = Number(draft.fullScore ?? INSPECTION_MAX_SCORE)
  const score = Number(draft.score ?? fullScore)
  if (fullScore <= 0) throw new Error('满分必须大于 0')
  if (score < 0 || score > fullScore) throw new Error('巡店得分必须在 0 到满分之间')
  const deductionsJson = scoreDetailsJson(draft)
  const redlinesJson = issueDetailsJson(draft)
  return {
    storeId: draft.storeId.trim(),
    inspectionDate: draft.inspectionDate.trim(),
    inspector: blankToUndefined(draft.inspector),
    brand: blankToUndefined(draft.brand),
    fullScore,
    score,
    deductionsJson,
    redlinesJson,
    photosJson: normalizeJsonArray(draft.photosJson),
    note: blankToUndefined(draft.note),
  }
}

function scoreDetailsJson(draft: InspectionRecordPayload) {
  const manual = normalizeJsonArray(draft.deductionsJson)
  const details = [
    { project: '卫生评分', score: draft.hygieneScore },
    { project: '服务评分', score: draft.serviceScore },
    { project: '出品评分', score: draft.productScore },
    { project: '陈列评分', score: draft.displayScore },
  ].filter((item) => item.score !== undefined && item.score !== null && String(item.score) !== '')
  if (details.length) return JSON.stringify(details)
  return manual
}

function issueDetailsJson(draft: InspectionRecordPayload) {
  const manual = normalizeJsonArray(draft.redlinesJson)
  const issueDescription = blankToUndefined(draft.issueDescription)
  const rectificationRequirement = blankToUndefined(draft.rectificationRequirement)
  if (issueDescription || rectificationRequirement) {
    return JSON.stringify([
      {
        content: issueDescription || '现场问题待补充',
        rectificationRequirement: rectificationRequirement || '请店长按督导要求完成整改',
      },
    ])
  }
  return manual
}

function isTaskTodo(item: RoleTodoItem) {
  return includesAny(item, ['巡店任务', '待巡店', '巡检任务', 'inspection-task', 'inspection_task'])
}

function isReviewTodo(item: RoleTodoItem) {
  return includesAny(item, ['整改复查', '待复查', '复查', 'rectification', 'review'])
}

function isIssueTodo(item: RoleTodoItem) {
  if (item.status === 'RISK' || item.status === 'RED' || item.status === 'ORANGE') return true
  return includesAny(item, ['巡店异常', '现场问题', '整改要求', '异常', 'risk', 'issue'])
}

function recordIssueTodo(record: InspectionRecord): RoleTodoItem {
  const score = inspectionScoreView(record)
  return {
    id: `inspection-record-${record.id}`,
    title: `巡店异常：${record.storeName || record.storeCode || record.storeId}`,
    summary: record.note || `巡店得分 ${score.scoreText}，需要跟进整改。`,
    status: 'RISK',
    priority: 2,
    brandName: record.brandName || record.brand,
    storeId: record.storeId,
    storeName: record.storeName,
    dueAt: record.inspectionDate,
    sourceModule: 'inspection_record',
    sourceRecordId: record.id,
    processStatus: '待督导复查',
    dataSource: 'inspection_record',
    updatedAt: record.inspectionDate,
    occurredAt: record.inspectionDate,
  }
}

function isDone(item: RoleTodoItem) {
  return includesText(`${item.status} ${item.processStatus}`, ['DONE', 'TODO_DONE', '已处理', '已完成', '老板已处理'])
}

function includesAny(item: RoleTodoItem, needles: string[]) {
  return includesText(`${item.id} ${item.title} ${item.summary} ${item.sourceModule} ${item.dataSource} ${item.processStatus}`, needles)
}

function includesText(text: string, needles: string[]) {
  const lower = text.toLowerCase()
  return needles.some((needle) => lower.includes(needle.toLowerCase()))
}

function normalizeJsonArray(value?: string) {
  if (!value || !value.trim()) return '[]'
  return value.trim()
}

function blankToUndefined(value?: string) {
  const normalized = value?.trim()
  return normalized ? normalized : undefined
}
