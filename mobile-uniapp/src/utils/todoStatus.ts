import type { RoleTodoItem } from '@/types/business'

export type TodoStage = 'PENDING' | 'IN_PROGRESS' | 'PENDING_REVIEW' | 'COMPLETED' | 'REJECTED'
export type TodoDisplayStatus = TodoStage | 'RISK' | 'REMINDER'
export type TodoStatusTone = 'warning' | 'info' | 'success' | 'danger' | 'neutral'

export function todoStage(todo: RoleTodoItem): TodoStage {
  const raw = rawStatus(todo)
  const process = `${todo.processStatus || ''} ${todo.title || ''} ${todo.summary || ''}`
  if (['DONE', 'TODO_DONE', 'COMPLETED', 'CLOSED'].includes(raw)) return 'COMPLETED'
  if (['REJECTED', 'CANCELLED'].includes(raw)) return 'REJECTED'
  if (raw === 'PENDING_REVIEW' || /待复核|人工复核|待审核|审核中/.test(process)) return 'PENDING_REVIEW'
  if (raw === 'IN_PROGRESS' || /处理中|整改中|待发货|在途|待收货|未入库/.test(process)) return 'IN_PROGRESS'
  return 'PENDING'
}

export function todoDisplayStatus(todo: RoleTodoItem): TodoDisplayStatus {
  const stage = todoStage(todo)
  if (stage !== 'PENDING') return stage
  const raw = rawStatus(todo)
  if (raw === 'RISK') return 'RISK'
  if (raw === 'REMINDER') return 'REMINDER'
  return 'PENDING'
}

export function todoStatusLabel(todo: RoleTodoItem): string {
  const labels: Record<TodoDisplayStatus, string> = {
    PENDING: '待处理',
    IN_PROGRESS: '处理中',
    PENDING_REVIEW: '待复核',
    COMPLETED: '已完成',
    REJECTED: '已驳回',
    RISK: '风险待处理',
    REMINDER: '提醒',
  }
  return labels[todoDisplayStatus(todo)]
}

export function todoStatusTone(todo: RoleTodoItem): TodoStatusTone {
  const tones: Record<TodoDisplayStatus, TodoStatusTone> = {
    PENDING: 'warning',
    IN_PROGRESS: 'info',
    PENDING_REVIEW: 'warning',
    COMPLETED: 'success',
    REJECTED: 'neutral',
    RISK: 'danger',
    REMINDER: 'info',
  }
  return tones[todoDisplayStatus(todo)]
}

export function isTodoResult(todo: RoleTodoItem): boolean {
  return ['COMPLETED', 'REJECTED'].includes(todoStage(todo))
}

function rawStatus(todo: RoleTodoItem): string {
  return String(todo.status || '').trim().toUpperCase()
}
