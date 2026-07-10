<script setup lang="ts">
import { ClipboardPenLine, Eye, Send } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { RoleTodoItem } from '../../api/todos'

defineProps<{
  tasks: RoleTodoItem[]
  actioningId?: string
}>()

defineEmits<{
  start: [item: RoleTodoItem]
  open: [item: RoleTodoItem]
  escalate: [item: RoleTodoItem]
}>()

function taskStatus(item: RoleTodoItem) {
  if (item.processStatus && !['RISK', 'PENDING', 'DONE', 'REMINDER'].includes(item.processStatus)) return item.processStatus
  if (item.status === 'DONE') return '已完成'
  if (item.escalatedToBoss) return '已上报老板'
  if (item.status === 'RISK') return '待整改'
  return '待巡店'
}
</script>

<template>
  <section class="inspection-panel">
    <div class="inspection-panel-head">
      <div>
        <h3>巡店任务</h3>
      </div>
    </div>

    <div v-if="!tasks.length" class="empty-state compact">当前没有巡店任务。</div>

    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>门店</th>
            <th>巡店日期</th>
            <th>负责人</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id">
            <td>
              <b>{{ task.storeName || task.storeId || '待指定门店' }}</b>
              <small>{{ task.brandName || '督导巡店' }}</small>
            </td>
            <td>{{ task.occurredAt || task.dueAt || '今天内' }}</td>
            <td>{{ task.ownerName || '督导' }}</td>
            <td><StatusBadge :label="taskStatus(task)" :tone="task.status === 'RISK' ? 'bad' : 'warn'" /></td>
            <td>
              <div class="inspection-actions">
                <button class="mini-button" type="button" @click="$emit('open', task)">
                  查看任务
                  <Eye :size="14" />
                </button>
                <button class="mini-button primary" type="button" @click="$emit('start', task)">
                  录入记录
                  <ClipboardPenLine :size="14" />
                </button>
                <button
                  class="mini-button"
                  type="button"
                  :disabled="actioningId === task.id"
                  @click="$emit('escalate', task)"
                >
                  上报老板
                  <Send :size="14" />
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.inspection-panel {
  display: grid;
  gap: 12px;
}

.inspection-panel-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.inspection-panel-head h3 {
  margin: 0;
  font-size: 18px;
}

.inspection-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
