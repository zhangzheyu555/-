import { todoPriorityLabel, todoPriorityTier } from '../src/utils/todoStatus'
import type { RoleTodoItem } from '../src/types/business'

function todo(priority: number): RoleTodoItem {
  return { id: `todo-${priority}`, title: '待办', priority } as RoleTodoItem
}

function expectEqual<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${expected}, received ${actual}`)
}

expectEqual(todoPriorityTier(todo(68)), 'LOW', '68 分待办应为低优先级')
expectEqual(todoPriorityTier(todo(75)), 'MEDIUM', '75 分待办应为中优先级')
expectEqual(todoPriorityTier(todo(90)), 'HIGH', '90 分待办应为高优先级')
expectEqual(todoPriorityLabel(todo(96)), '高', '高优先级应显示业务化标签')
